package net.cumba.corej.core.ruletestsuites;

import java.util.Optional;

import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.ruletest.cdt.ruletest.LibraryRef;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioLibraryResolver;
import org.junit.jupiter.api.Assumptions;

/**
 * Shared {@code #library-ref} wiring for the two suites {@code @TestFactory} classes. Keeps the two
 * skip gates (no Library configured / configured-but-degraded) in one place.
 */
final class ScenarioLibrarySupport
{

    private ScenarioLibrarySupport()
    {
    }


    /**
     * Resolve the {@link MetadataProvider} for a scenario.
     *
     * <ul>
     * <li>Non-ref scenario → the inline provider (possibly {@code null}); unchanged behaviour.</li>
     * <li>{@code #library-ref} scenario → a real provider, or — when no CDISC Library is configured
     * ({@code CDISC_API_KEY} unset) — the test is <em>aborted</em> (skipped) via
     * {@link Assumptions#assumeTrue}.</li>
     * </ul>
     */
    static MetadataProvider resolveOrSkip(RuleTestScenario aScenario)
    {
        LibraryRef ref = aScenario.getLibraryRef();
        if (ref == null)
        {
            return aScenario.getLibrary();
        }
        Optional<MetadataProvider> resolved = ScenarioLibraryResolver.resolve(ref);
        Assumptions.assumeTrue(resolved.isPresent(), "CDISC Library not configured (set "
                + "CDISC_API_KEY) — skipping " + aScenario.getCoreId());
        return resolved.orElseThrow();
    }


    /**
     * Second skip gate for {@code #library-ref} scenarios only: when the Library was configured but
     * the rule could not actually run against it (degraded product →
     * {@code SKIPPED}/{@code ERROR}), abort (skip) rather than assert a verdict against an empty
     * result. A no-op for non-ref scenarios and for refs whose rule executed.
     */
    static void skipRefIfNotExecuted(RuleTestScenario aScenario, boolean aExecuted, String aDetail)
    {
        if (aScenario.getLibraryRef() != null)
        {
            Assumptions.assumeTrue(aExecuted, "CDISC Library configured but rule not executable"
                    + aDetail + " — skipping " + aScenario.getCoreId());
        }
    }
}
