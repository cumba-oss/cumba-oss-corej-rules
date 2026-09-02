package net.cumba.cdisc.core.rulespec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.cumba.cdisc.core.exec.NativeExecutionRecorder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parametric JUnit harness — one test invocation per spec under {@code rulespec/specs/}.
 *
 * <p>
 * Walks the spec library at test-discovery time, runs each through {@link SpecRunner} in ASSERT
 * mode, and fails on any diff against {@code expected_violations}.
 *
 * <p>
 * Historical note: a Python-side pytest mirror once ran the same specs through the vendored Python
 * engine. Both are gone — the mirror was cut with the Python lane in wave 33, the vendored engine
 * with stage 4 (wave 39). This suite is the only lane; every expectation is Java-canonical.
 *
 * <p>
 * <b>Baseline XFAIL semantics (plan §9.4):</b> specs listed in
 * {@code documentation/parity-diff-baseline.json} under {@code known_divergences} are expected to
 * fail Java ASSERT (they exercise a known Java/Python engine divergence tied to a Fix # in
 * {@code core-engine-update-plan.md} or a Wave N finding). The test inverts the assertion for these
 * specs:
 * <ul>
 * <li>Spec in baseline AND fails → pass (known divergence, still present).</li>
 * <li>Spec in baseline AND passes → fail with "Fix may have landed — remove baseline entry." This
 * surfaces landed fixes so the baseline doesn't rot.</li>
 * <li>Spec NOT in baseline → behaves as today (passes iff diff is empty).</li>
 * </ul>
 *
 * <p>
 * <b>Two channels (PLAN-parity-expected-status D1'):</b> {@code violations} (the
 * {@code expected_violations} diff) and {@code status} (the rule-execution status). A baseline
 * entry lists the channels it expects to diverge on via {@code channels}; every channel it does NOT
 * list is asserted normally. Without that discriminator a status-only divergence would
 * blanket-suppress the spec's violation coverage, and every entry already on file would become
 * permanently exempt from the status assertion.
 */
class RuleExecutionSpecTest
{

    private static final String BASELINE_LOAD_FAILED_PROPERTY = "parity.baselineLoadFailed";

    private static BaselineManifest.Manifest baseline;

    /**
     * Resolves the spec library directory. We avoid the test classpath because spec files load
     * sibling fixtures via relative {@code $ref:} paths — easier to keep them in their source tree
     * than to materialise a copy under {@code target/test-classes}.
     */
    private static Path specsRoot()
    {
        Path candidate = Path.of("rulespec/specs").toAbsolutePath();
        if (java.nio.file.Files.isDirectory(candidate))
        {
            return candidate;
        }
        // Surefire may run from the parent project root; try the module-relative path.
        Path moduleRel = Path.of("lib/cumba-oss-cdisc-rules/rulespec/specs").toAbsolutePath();
        if (java.nio.file.Files.isDirectory(moduleRel))
        {
            return moduleRel;
        }
        throw new IllegalStateException("rulespec/specs directory not found from working dir "
                + Path.of("").toAbsolutePath());
    }


    private static synchronized BaselineManifest.Manifest baseline()
    {
        if (baseline == null)
        {
            try
            {
                baseline = BaselineManifest.loadDefault();
            }
            catch (IOException e)
            {
                // Don't silently skip baseline logic on read failure — the test suite would
                // stop treating known-divergent specs as XFAIL and start reporting them as
                // fresh failures. Empty manifest + flag prevents that footgun at the cost of
                // one loud message on the first offending spec.
                System.setProperty(BASELINE_LOAD_FAILED_PROPERTY, e.getMessage());
                baseline = new BaselineManifest.Manifest(null, List.of());
            }
        }
        return baseline;
    }


    static Stream<Path> specs() throws IOException
    {
        Path root = specsRoot();
        List<Path> specs = SpecLoader.findSpecs(root);
        if (specs.isEmpty())
        {
            // Empty spec library would silently pass with a 0-arg parametric test. Force a
            // failure so the harness doesn't pretend to have run anything.
            throw new IllegalStateException("No specs found under " + root);
        }
        return specs.stream();
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("specs")
    void runSpec(Path specFile) throws IOException
    {
        SpecRunner.SpecResult result = SpecRunner.run(specFile, SpecRunner.Mode.ASSERT);
        String specName = specNameFrom(specFile);
        Optional<BaselineManifest.KnownDivergence> baselined = baseline().lookup(specName);

        String loadErr = System.getProperty(BASELINE_LOAD_FAILED_PROPERTY);
        if (loadErr != null)
        {
            Assertions.fail("Baseline manifest failed to load: " + loadErr);
        }

        // Native-only gate (PLAN-parity-harness-native): the harness runs nativeEval=true; assert
        // no spec produced its verdict on the legacy engine. A LEGACY/MIXED record means the
        // scaffolded rule installed no native checkExpr (or a native path fell back partway), so
        // the spec is not actually exercising the native engine. Fail loudly rather than accept a
        // silent legacy fallback. Applies to every spec, baselined or not — a known divergence is
        // about the verdict, not the engine. Specs that evaluate no Check (scope-skip,
        // missing-provider SKIP) record nothing and pass this gate; a native-uncompilable
        // construct surfaces as a loadError ERROR through the normal expected/observed diff.
        // Native-only gate (the legacy engine is retired): every backend the recorder saw for
        // this spec must be NATIVE — the enum has no other constant, so this asserts the
        // scaffolded rule actually installed and executed a native checkExpr.
        Assertions.assertTrue(
                result.backends().values().stream()
                        .allMatch(b -> b == NativeExecutionRecorder.Backend.NATIVE),
                () -> "Spec '" + specName + "' recorded a non-native backend (backends="
                        + result.backends() + ").");

        // If the spec isn't baselined at all, OR the baseline entry targets only the
        // Python lane, Java ASSERT is expected to pass normally.
        if (baselined.isEmpty() || !baselined.get().appliesToJavaLane())
        {
            Assertions.assertTrue(result.passed(), result::formatDiff);
            return;
        }

        // Spec is baselined on the Java lane — expect it to fail Java ASSERT, per channel.
        BaselineManifest.KnownDivergence entry = baselined.get();

        // All three channels are always asserted — the opt-in gate that existed while the corpus
        // was triaged was removed in P4 on purpose (see SpecRunner.SpecResult's javadoc).
        Map<String, Boolean> diverged = new LinkedHashMap<>();
        diverged.put(BaselineManifest.VIOLATIONS_CHANNEL, !result.violationsMatch());
        diverged.put(BaselineManifest.STATUS_CHANNEL, !result.statusMatches());
        diverged.put(BaselineManifest.ERRORS_CHANNEL, !result.errorsMatch());

        String ref = "ref: " + entry.fixRef() + ", lane=" + entry.laneOrDefault() + ", channels="
                + entry.effectiveChannels();
        String detail = entry.description() == null ? ""
                : "\n  baseline description: " + entry.description();

        // Every channel the entry does NOT list must still match — a status-only entry keeps its
        // violation coverage asserted, and vice versa.
        List<String> unclaimed = entry.unclaimedDivergences(diverged);
        if (!unclaimed.isEmpty())
        {
            Assertions.fail("Spec '" + specName + "' is baselined (" + ref
                    + ") but diverges on the " + unclaimed
                    + " channel(s), which that entry does not claim. Either add them "
                    + "to its `channels` list or fix the divergence." + detail + "\n"
                    + result.formatDiff());
        }

        // ...and every channel it DOES list must still diverge, or that claim has rotted (D3 of
        // PLAN-parity-expected-errors). This is deliberately stricter than the rule it replaces,
        // which passed when *any* listed channel still diverged: with three channels an entry
        // claiming all three would stay green while two of them healed, and the landed fixes would
        // never be surfaced. Naming the healed channels makes the follow-up obvious.
        List<String> healed = entry.healedChannels(diverged);
        if (!healed.isEmpty())
        {
            Assertions.fail("Spec '" + specName + "' is listed in parity-diff-baseline.json (" + ref
                    + ") but now PASSES Java ASSERT on channel(s) " + healed
                    + ", which that entry claims should diverge. The underlying engine fix may "
                    + "have landed. Remove those channels from the entry (or the whole entry, if "
                    + "none is left) to restore a normal pass." + detail);
        }
        // else: expected divergence still present on every claimed channel — XFAIL passes silently.
    }


    private static String specNameFrom(Path specFile)
    {
        String fn = specFile.getFileName().toString();
        int dot = fn.lastIndexOf('.');
        return dot > 0 ? fn.substring(0, dot) : fn;
    }
}
