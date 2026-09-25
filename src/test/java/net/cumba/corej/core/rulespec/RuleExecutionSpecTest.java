package net.cumba.corej.core.rulespec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
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
 * ⛔ <b>The baseline XFAIL inversion is GONE (2026-09-17).</b> Specs listed in
 * {@code registers/parity-diff-baseline.json} (removed 2026-09-25) used to have their assertion
 * inverted here, and {@code BaselineManifest} loaded that file for every spec. Python parity is
 * retired (owner; see the {@code Python parity is RETIRED} section of the meta repo's
 * {@code CLAUDE.md} and {@code PLAN-retire-python-parity.md}), so the manifest is no longer an
 * input to this gate and the loader is deleted. <b>Every spec asserts normally, always.</b>
 *
 * <p>
 * ⭐ The removal was verdict-neutral <b>by measurement</b>: the inversion applied only when an
 * entry's lane was {@code java} or {@code both}, and all 178 committed entries are
 * {@code lane: python}, so the inverted branch was unreachable on the committed data and all 1 048
 * specs already took the normal-assert path. The baseline file itself stays as history.
 */
class RuleExecutionSpecTest
{

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
        Path moduleRel = Path.of("lib/corej-rules/rulespec/specs").toAbsolutePath();
        if (java.nio.file.Files.isDirectory(moduleRel))
        {
            return moduleRel;
        }
        throw new IllegalStateException("rulespec/specs directory not found from working dir "
                + Path.of("").toAbsolutePath());
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
        // ⛔ The native-backend tripwire that stood here is GONE (owner, 2026-09-18).
        // `NativeExecutionRecorder` was the legacy engine's decommissioning check; the migration is
        // complete, there is no alternative backend, and that is why its `Backend` enum had a
        // single constant — which made `allMatch(b -> b == NATIVE)` unsatisfiable rather than
        // merely quiet. It is not replaced: a rule with no native form surfaces as a loadError
        // ERROR through the normal expected/observed diff below, and the corpus-wide dispatch
        // evidence lives in NativeCorpusFullCoverageTest.
        //
        // ⛔ Python parity is RETIRED (owner, 2026-09-17 — see the meta repo's CLAUDE.md
        // "Python parity is RETIRED"). A spec asserts normally, always; the baseline's XFAIL
        // inversion is gone and the manifest is no longer an input to this gate.
        //
        // ⭐ Removing it was verdict-neutral by MEASUREMENT, not by assertion
        // (PLAN-retire-python-parity phase 2). The inversion applied only when
        // KnownDivergence.appliesToJavaLane() was true — `lane: java` or `lane: both` — and all
        // 178 known_divergences are `lane: python` (re-derived at phase 0). Every one of the
        // 1 048 specs therefore already took this same normal-assert path, and the branch that
        // stood here was unreachable on the committed data.
        Assertions.assertTrue(result.passed(), result::formatDiff);
    }
}
