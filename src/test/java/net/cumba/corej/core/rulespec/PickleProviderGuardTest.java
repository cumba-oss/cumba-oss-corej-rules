package net.cumba.corej.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guard: the library-provider environment every {@code expected_status} pin is valid against
 * (PLAN-parity-expected-status P0).
 *
 * <p>
 * {@code MockLibraryProvider.of} returns null when a spec declares no {@code library_mocks}. <b>62
 * of the 1 048 specs declare none, and 61 of those are SDTM-family</b> — the 62nd
 * ({@code PMDA-AD9999}) is ADaM and is excluded by {@link #isPickleFamily(String)} — across three
 * {@code (standard, version)} pairs: {@code sdtmig 3.4} (50), {@code sendig 3.1.1} (10) and
 * {@code sendig 3.0} (1). For exactly those, the pickle provider located by
 * {@link PickleCacheLocator} is the <i>only</i> library provider. Without it
 * {@code library == null} and {@code RuleRunner}'s library gates flip those specs
 * {@code EXECUTED → SKIPPED}. Some of them also carry {@code expected_violations: []}, so before
 * the status channel existed the flip was invisible; now it is a hard red with a confusing cause.
 *
 * <p>
 * ⚑ The counts in this paragraph are <b>descriptive</b>: the guard derives its cohort at runtime
 * (see {@link #providerlessSpecStandards()}) and never reads a number from here, so a later drift
 * in the corpus makes this javadoc stale but the guard no less correct.
 *
 * <p>
 * So: fail loudly here, with an actionable message, rather than let a missing cache silently
 * reclassify those specs.
 *
 * <p>
 * The standards to probe are <b>derived from the corpus</b>, not hard-coded — a cache carrying the
 * SDTMIG pickles but not the SENDIG ones would otherwise sail past a single-standard probe and
 * surface as a confusing red on unrelated specs. Deriving them also keeps the guard honest as the
 * corpus grows, and stops it asserting combinations no spec actually uses (there is no
 * {@code sdtm 1.4} spec, and the cache has no such entry).
 */
class PickleProviderGuardTest
{

    /**
     * Distinct {@code (standard, version)} pairs among the specs that declare no
     * {@code library_mocks} AND whose standard is one {@code SpecRunner.pickleProviderFor} accepts
     * — i.e. exactly the specs whose only library provider is the pickle cache.
     */
    private static Set<List<String>> providerlessSpecStandards() throws IOException
    {
        Set<List<String>> out = new LinkedHashSet<>();
        for (Path spec : SpecLoader.findSpecs(Path.of("rulespec/specs").toAbsolutePath()))
        {
            SpecSchema schema = SpecLoader.load(spec);
            if (schema.libraryMocks() != null || schema.config() == null
                    || schema.config().standard() == null
                    || schema.config().standardVersion() == null)
            {
                continue;
            }
            // Select on FAMILY alone — deliberately not on whether a provider currently resolves,
            // which is the very thing under test. Asking pickleProviderFor here would make the
            // guard self-fulfilling: a missing cache would shrink the set to nothing instead of
            // failing.
            String std = schema.config().standard().toLowerCase(Locale.ROOT);
            if (isPickleFamily(std))
            {
                out.add(List.of(std, schema.config().standardVersion()));
            }
        }
        return out;
    }


    private static boolean isPickleFamily(String aStandard)
    {
        return "sdtmig".equals(aStandard) || "sendig".equals(aStandard) || "sdtm".equals(aStandard);
    }


    @Test
    void pickleProviderResolvesForEveryProviderlessSpecStandard() throws IOException
    {
        Set<List<String>> standards = providerlessSpecStandards();
        // Vacuously-passing guard would be worse than none: if the derivation ever finds nothing,
        // that is itself the bug.
        assertFalse(standards.isEmpty(),
                "No provider-less SDTM-family specs found — the derivation is broken, so this "
                        + "guard would pass vacuously.");
        for (List<String> std : standards)
        {
            var config = new SpecSchema.SpecConfig(std.get(0), std.get(1), List.of(), null);
            assertNotNull(SpecRunner.pickleProviderFor(config),
                    () -> "No pickle metadata provider resolved for standard=" + std.get(0) + " "
                            + std.get(1) + ", which " + "at least one spec declaring no "
                            + "`library_mocks` relies on as its ONLY library provider. Without it "
                            + "those specs silently flip EXECUTED → SKIPPED and every "
                            + "expected_status pin is invalid.\n"
                            + "Fix: set CDISC_PICKLE_CACHE_DIR (or "
                            + "-Dcdisc.pickle.cache.dir) to a Python-engine pickle cache "
                            + "directory. There is deliberately no default — the cache is not "
                            + "part of this repository, so the caller has to say where it is. "
                            + "The CI workflow fetches it from the pinned public upstream and "
                            + "exports the variable; a local run must do the same.");
        }
    }


    @Test
    void nonSdtmFamilyResolvesNoPickleProvider()
    {
        // Pins the other half of the contract: the ADaM pickle path is deliberately not wired in
        // the harness, so an ADaM spec's provider comes from its library_mocks alone. If this
        // ever starts returning a provider, the 61-spec reasoning above needs re-deriving.
        assertNull(SpecRunner
                .pickleProviderFor(new SpecSchema.SpecConfig("adamig", "1.3", List.of(), null)));
        assertNull(SpecRunner
                .pickleProviderFor(new SpecSchema.SpecConfig(null, null, List.of(), null)));
    }
}
