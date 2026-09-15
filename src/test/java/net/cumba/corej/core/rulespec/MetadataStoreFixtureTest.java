package net.cumba.corej.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Optional;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link MetadataStoreFixture}'s contract: a resolvable pickle corpus becomes a seeded store
 * whose factory answers real products, and the degrade shapes the deleted pickle-backed factory
 * used to pin here survive the migration (cache plan step 8g — the successors to
 * {@code PickleMetadataReaderTest}'s provider-level tests and
 * {@code PickleMetadataProviderFactoryTest.forSdtm_loadsProductFromBundledCache}).
 *
 * <p>
 * Every test {@code assumeTrue}s the store is present so the suite stays green when no pickle
 * corpus resolves — the same gating those predecessors used. {@code LibraryProviderGuardTest} is
 * what turns an absent corpus into a loud failure for the specs that need it.
 * </p>
 */
class MetadataStoreFixtureTest
{

    private static final @Nullable StoreMetadataProviderFactory FACTORY = MetadataStoreFixture
            .factory().orElse(null);

    @Test
    void seededStoreBuildsProviderForSdtmig()
    {
        assumeTrue(FACTORY != null, "no metadata store seeded (no pickle corpus resolved)");
        Optional<MetadataProvider> provider = FACTORY.forSdtm("sdtmig", "3-4", List.of());
        assertTrue(provider.isPresent(), "provider built from the seeded store");
        // getStandardModelVariables is the method behind GET_MODEL_COLUMN_ORDER. With the product
        // loaded it resolves a non-empty allowed-variable list for a Findings domain — proving the
        // seeded IG product carries real classes, as the deleted typed-pickle-view test did.
        List<String> vars = provider.get().getStandardModelVariables(
                MockTable.of().name("LB").col("STUDYID", "x").build(), null);
        assertNotNull(vars);
        assertFalse(vars.isEmpty());
    }


    @Test
    void unknownStandardAnswersEmpty()
    {
        assumeTrue(FACTORY != null, "no metadata store seeded (no pickle corpus resolved)");
        assertTrue(FACTORY.forSdtm("sdtmig", "9-9", List.of()).isEmpty(),
                "unknown standard → empty (graceful degrade)");
    }


    @Test
    void noCtSelectedDegradesToEmptyCodelistReads()
    {
        assumeTrue(FACTORY != null, "no metadata store seeded (no pickle corpus resolved)");
        MetadataProvider provider = FACTORY.forSdtm("sdtmig", "3-4", List.of()).orElseThrow();
        // The no-CT anonymous empty package (the deleted PickleProductSource.emptyCtPackage's
        // successor): codelist reads answer empty instead of throwing.
        assertNotNull(provider.getCodelistTerms("C66734"));
        assertTrue(provider.getCodelistTerms("C66734").isEmpty(),
                "no CT selected → codelist reads degrade to empty");
    }
}
