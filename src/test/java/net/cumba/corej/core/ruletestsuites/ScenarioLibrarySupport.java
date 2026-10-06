package net.cumba.corej.core.ruletestsuites;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.store.StoreFormatException;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.corej.ruletest.cdt.ruletest.RunEnvironment.StoreAvailability;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

/**
 * The corpus's store for {@code #library-ref} scenarios, resolved <b>once per JVM</b> and handed to
 * the lifted {@link ScenarioRunner} as a {@link StoreAvailability}: a {@code CDISC_METADATA_STORE}
 * / {@code cdisc.metadata.store} that names an existing file wins, else the store
 * {@link MetadataStoreFixture} seeds for this build. Nothing here writes a system property (until
 * the LAC review round 1 it published the fixture store into {@code cdisc.metadata.store} around
 * every resolve — a global mutation, a reopen per scenario, and blind to an ambient
 * {@code CDISC_METADATA_STORE} that named no file: the env var outranks the property, so the
 * fallback silently never happened).
 *
 * <p>
 * ⛔ The seeding stays HERE, in the corpus test tree ({@code PLAN-rule-authoring-api} §3.1): the
 * runner never seeds, it only reads what it is given. The two skip gates (no usable store /
 * store-but-rule-not-executed) and the pinned-CT check now live in
 * {@link ScenarioRunner#resolveLibrary} and {@link ScenarioRunner#judge}; {@link #resolveOrSkip} is
 * the JUnit face of the first for the tests that call it directly.
 * </p>
 */
final class ScenarioLibrarySupport
{

    private ScenarioLibrarySupport()
    {
    }

    /** Initialization-on-demand holder: opened once, on first use, thread-safely. */
    private static final class Holder
    {

        static final StoreAvailability STORE = openStore();
    }

    private static StoreAvailability openStore()
    {
        Path configured = StoreMetadataProviderFactory.resolveConfiguredFile(null);
        if (configured != null)
        {
            String origin = "the configured metadata store " + configured;
            try
            {
                return StoreAvailability.of(StoreMetadataProviderFactory.open(configured), origin);
            }
            catch (StoreFormatException e)
            {
                // PLAN-define-ct-evaluation T1-10 a / S17: a format bump is a stop, not a skip.
                return StoreAvailability.unavailable(origin,
                        origin + " cannot serve #library-ref" + " scenarios: " + e.getMessage()
                                + " (an old-format store fails the"
                                + " scenario; it never skips it)",
                        true);
            }
            catch (IOException e)
            {
                return StoreAvailability.unavailable(origin,
                        origin + " is unreadable (" + e.getMessage() + ")", false);
            }
        }
        Optional<StoreMetadataProviderFactory> fixture = MetadataStoreFixture.factory();
        String origin = "the fixture store "
                + MetadataStoreFixture.storeFile().map(Path::toString).orElse("(none)");
        return fixture.map(f -> StoreAvailability.of(f, origin))
                .orElseGet(() -> StoreAvailability.unavailable(origin,
                        "no CDISC metadata store (set "
                                + "CDISC_METADATA_STORE, or CDISC_PICKLE_CACHE_DIR /"
                                + " -Dcdisc.pickle.cache.dir so the fixture can seed one)",
                        false));
    }


    /** The store every {@code #library-ref} scenario of this JVM resolves against. */
    static StoreAvailability store()
    {
        return Holder.STORE;
    }


    /**
     * Resolve the {@link MetadataProvider} for a scenario the way the factories do, mapped onto
     * JUnit: an {@link ScenarioRunner.Outcome#ABORTED} stop aborts the test, a
     * {@link ScenarioRunner.Outcome#FAIL} stop fails it.
     *
     * @param aScenario
     *            the scenario
     * @return the provider (possibly {@code null} for a non-ref scenario without a library)
     */
    static MetadataProvider resolveOrSkip(RuleTestScenario aScenario)
    {
        ScenarioRunner.LibraryResolution resolution = ScenarioRunner.resolveLibrary(aScenario,
                store());
        ScenarioRunner.Stop stop = resolution.stop();
        if (stop != null)
        {
            if (stop.outcome() == ScenarioRunner.Outcome.ABORTED)
            {
                Assumptions.abort(stop.detail());
            }
            Assertions.fail(stop.detail());
        }
        return resolution.provider();
    }
}
