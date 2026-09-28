package net.cumba.corej.core.ruletestsuites;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.store.Presence;
import net.cumba.corej.core.metadata.store.StoreFormatException;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.corej.ruletest.cdt.ruletest.LibraryRef;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioLibraryResolver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

/**
 * Shared {@code #library-ref} wiring for the two suites {@code @TestFactory} classes. Keeps the two
 * skip gates (no usable store or product / store-but-rule-not-executed) and the pinned-CT check in
 * one place.
 *
 * <p>
 * The store is resolved <b>once per JVM</b> and handed to
 * {@link ScenarioLibraryResolver#resolve(LibraryRef, StoreMetadataProviderFactory)} explicitly: a
 * {@code CDISC_METADATA_STORE} / {@code cdisc.metadata.store} that names an existing file wins,
 * else the store {@link MetadataStoreFixture} seeds for this build. Nothing here writes a system
 * property (until the LAC review round 1 it published the fixture store into
 * {@code cdisc.metadata.store} around every resolve — a global mutation, a reopen per scenario, and
 * blind to an ambient {@code CDISC_METADATA_STORE} that named no file: the env var outranks the
 * property, so the fallback silently never happened).
 * </p>
 */
final class ScenarioLibrarySupport
{

    private ScenarioLibrarySupport()
    {
    }

    /** The store every {@code #library-ref} scenario of this JVM resolves against. */
    private record ScenarioStore(@Nullable StoreMetadataProviderFactory factory, String origin,
            String unavailable, boolean fatal)
    {
    }


    /** Initialization-on-demand holder: opened once, on first use, thread-safely. */
    private static final class Holder
    {

        static final ScenarioStore STORE = openStore();
    }

    private static ScenarioStore openStore()
    {
        Path configured = StoreMetadataProviderFactory.resolveConfiguredFile(null);
        if (configured != null)
        {
            String origin = "the configured metadata store " + configured;
            try
            {
                return new ScenarioStore(StoreMetadataProviderFactory.open(configured), origin, "",
                        false);
            }
            catch (StoreFormatException e)
            {
                // PLAN-define-ct-evaluation T1-10 a / S17: a format bump is a stop, not a skip.
                return new ScenarioStore(null, origin,
                        origin + " cannot serve #library-ref" + " scenarios: " + e.getMessage()
                                + " (an old-format store fails the"
                                + " scenario; it never skips it)",
                        true);
            }
            catch (IOException e)
            {
                return new ScenarioStore(null, origin,
                        origin + " is unreadable (" + e.getMessage() + ")", false);
            }
        }
        Optional<StoreMetadataProviderFactory> fixture = MetadataStoreFixture.factory();
        String origin = "the fixture store "
                + MetadataStoreFixture.storeFile().map(Path::toString).orElse("(none)");
        return fixture.map(f -> new ScenarioStore(f, origin, "", false))
                .orElseGet(() -> new ScenarioStore(null, origin, "no CDISC metadata store (set "
                        + "CDISC_METADATA_STORE, or CDISC_PICKLE_CACHE_DIR so the fixture can seed"
                        + " one)", false));
    }


    /**
     * Resolve the {@link MetadataProvider} for a scenario.
     *
     * <ul>
     * <li>Non-ref scenario → the inline provider (possibly {@code null}); unchanged behaviour.</li>
     * <li>{@code #library-ref} scenario → a real provider over our unified metadata store (see the
     * class javadoc for which one). The test is <em>aborted</em> (skipped) via
     * {@link Assumptions#assumeTrue} when there is no store, the store is unreadable, or it does
     * not serve the referenced product. It <b>fails</b> when the configured store is of another
     * format version, and when the ref pins a {@code ct=} package the store does not hold — the
     * resolver would substitute an empty package and a scenario could pass against no terms at
     * all.</li>
     * </ul>
     */
    static MetadataProvider resolveOrSkip(RuleTestScenario aScenario)
    {
        LibraryRef ref = aScenario.getLibraryRef();
        if (ref == null)
        {
            return aScenario.getLibrary();
        }
        ScenarioStore store = Holder.STORE;
        StoreMetadataProviderFactory factory = store.factory();
        Assertions.assertFalse(store.fatal(),
                () -> store.unavailable() + " — failing " + aScenario.getCoreId());
        Assumptions.assumeTrue(factory != null,
                () -> store.unavailable() + " — skipping " + aScenario.getCoreId());
        requirePinnedCtPackages(aScenario, ref, store);
        Optional<MetadataProvider> resolved = ScenarioLibraryResolver.resolve(ref,
                Objects.requireNonNull(factory));
        Assumptions.assumeTrue(resolved.isPresent(),
                () -> store.origin() + " does not serve " + ref.getStandard() + " "
                        + ref.getVersion() + " (product not in the store) — skipping "
                        + aScenario.getCoreId());
        return resolved.orElseThrow();
    }


    /**
     * Fails the scenario when its ref pins a CT package the store does not hold. The resolver would
     * run the rule over an empty substitute (a WARNING in the log, nothing else), so a
     * {@code noViolation} scenario over a CT-dependent rule would pass vacuously.
     */
    private static void requirePinnedCtPackages(RuleTestScenario aScenario, LibraryRef aRef,
            ScenarioStore aStore)
    {
        StoreMetadataProviderFactory factory = Objects.requireNonNull(aStore.factory());
        for (String id : aRef.getCtPackages())
        {
            Presence presence = factory.presence(id);
            Assertions.assertEquals(Presence.PRESENT, presence,
                    () -> aScenario.getCoreId() + " pins ct=" + id + " but " + aStore.origin()
                            + " answers " + presence + " for it — the rule would run against an"
                            + " empty package and could pass vacuously. Re-seed the store or"
                            + " re-pin the scenario.");
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
