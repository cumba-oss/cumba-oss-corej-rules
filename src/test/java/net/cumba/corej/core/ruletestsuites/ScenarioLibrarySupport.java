package net.cumba.corej.core.ruletestsuites;

import java.nio.file.Path;
import java.util.Optional;

import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.corej.ruletest.cdt.ruletest.LibraryRef;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioLibraryResolver;
import org.junit.jupiter.api.Assumptions;

/**
 * Shared {@code #library-ref} wiring for the two suites {@code @TestFactory} classes. Keeps the two
 * skip gates (no metadata store / store-but-degraded) in one place.
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
     * <li>{@code #library-ref} scenario → a real provider over our unified metadata store: the one
     * {@code CDISC_METADATA_STORE} / {@code cdisc.metadata.store} names, else the one
     * {@link MetadataStoreFixture} seeds for this build — or, when neither exists, the test is
     * <em>aborted</em> (skipped) via {@link Assumptions#assumeTrue}.</li>
     * </ul>
     */
    static MetadataProvider resolveOrSkip(RuleTestScenario aScenario)
    {
        LibraryRef ref = aScenario.getLibraryRef();
        if (ref == null)
        {
            return aScenario.getLibrary();
        }
        Optional<MetadataProvider> resolved = resolveAgainstConfiguredOrFixtureStore(ref);
        Assumptions.assumeTrue(resolved.isPresent(), "no CDISC metadata store (set "
                + "CDISC_METADATA_STORE, or CDISC_PICKLE_CACHE_DIR so the fixture can seed one) — "
                + "skipping " + aScenario.getCoreId());
        return resolved.orElseThrow();
    }


    /**
     * {@link ScenarioLibraryResolver} reads {@code CDISC_METADATA_STORE} /
     * {@code cdisc.metadata.store} and nothing else — and until 2026-09-28 nothing in the corpus
     * build set either, so every {@code #library-ref} scenario skipped, always
     * (PLAN-cdisc-library-access-cleanup D1). When neither names a store, point the property at the
     * store the fixture seeds for the duration of the resolve and restore it afterwards (the window
     * FindingsSnapshot uses for the same reason; the suites run single-threaded in one fork).
     */
    private static Optional<MetadataProvider> resolveAgainstConfiguredOrFixtureStore(
            LibraryRef aRef)
    {
        if (StoreMetadataProviderFactory.resolveConfiguredFile(null) != null)
        {
            return ScenarioLibraryResolver.resolve(aRef);
        }
        Optional<Path> fixture = MetadataStoreFixture.storeFile();
        if (fixture.isEmpty())
        {
            return Optional.empty();
        }
        String previous = System.getProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, fixture.get().toString());
        try
        {
            return ScenarioLibraryResolver.resolve(aRef);
        }
        finally
        {
            if (previous == null)
            {
                System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
            }
            else
            {
                System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, previous);
            }
        }
    }


    /**
     * Second skip gate for {@code #library-ref} scenarios only: when a store was available but the
     * rule could not actually run against it (degraded product → {@code SKIPPED}/{@code ERROR}),
     * abort (skip) rather than assert a verdict against an empty result. A no-op for non-ref
     * scenarios and for refs whose rule executed.
     */
    static void skipRefIfNotExecuted(RuleTestScenario aScenario, boolean aExecuted, String aDetail)
    {
        if (aScenario.getLibraryRef() != null)
        {
            Assumptions.assumeTrue(aExecuted, "metadata store available but rule not executable"
                    + aDetail + " — skipping " + aScenario.getCoreId());
        }
    }
}
