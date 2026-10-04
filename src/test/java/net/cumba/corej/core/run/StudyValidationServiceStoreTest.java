package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Corpus-gated store provider wiring for {@link StudyValidationService} — the successor of
 * {@code StudyValidationServicePickleTest} (cache plan step 8g): the pickle leg those tests drove
 * ({@code tryPickleProvider}) is deleted, and the run resolves its library metadata through
 * {@code tryStoreProvider} from the unified metadata store, which {@link MetadataStoreFixture}
 * seeds from the pickle corpus located by {@link net.cumba.corej.core.rulespec.PickleCacheLocator}.
 *
 * <p>
 * {@code tryStoreProvider} reads its configuration from {@code CDISC_METADATA_STORE} /
 * {@code cdisc.metadata.store} (not from a params field, unlike the deleted pickle leg), so each
 * test sets and restores the system property around the call.
 * </p>
 */
class StudyValidationServiceStoreTest
{

    private static final @Nullable Path STORE = MetadataStoreFixture.storeFile().orElse(null);

    /** Runs {@code aBody} with {@code cdisc.metadata.store} pointing at the seeded store. */
    private static void withStoreConfigured(Runnable aBody)
    {
        org.junit.jupiter.api.Assumptions.assumeTrue(STORE != null,
                "no metadata store seeded (no pickle corpus resolved)");
        String prev = System.getProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, STORE.toString());
        try
        {
            aBody.run();
        }
        finally
        {
            if (prev == null)
            {
                System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
            }
            else
            {
                System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, prev);
            }
        }
    }


    @Test
    void tryStoreProvider_servesAdam_unknownKindStillNull()
    {
        // Carried over from the pickle leg (PLAN-metadata-product-selection Phase 7a): an ADaM run
        // with a configured store loads offline; UNKNOWN (with nothing ADaM-shaped declared) has
        // no loadable family and resolves nothing.
        withStoreConfigured(() ->
        {
            StudyValidationParams params = StudyValidationParams.builder()
                    .manager(mock(IDataTableManager.class))
                    .metadataProducts(List.of("standards/adam/adamig-1-3")).build();
            var adam = tryStore(params, StudyValidationService.StandardKind.ADAM,
                    params.metadataProducts(), RunStandard.of("standards/adam/adamig-1-3"));
            assertNotNull(adam, "an ADaM run with a configured store loads offline");
            // PLAN-library-template-label-resolution D10: ADaMIG 1.3 declared alone carries the
            // OCCDS of its own generation (OCCDS 1.1) at lowest precedence, named in provenance.
            assertEquals(List.of("standards/adam/adamig-1-3", "standards/adam/adam-occds-1-1"),
                    adam.declaredStructureKeyedProducts());
            StudyValidationParams unknown = StudyValidationParams.builder()
                    .manager(mock(IDataTableManager.class))
                    .metadataProducts(List.of("standards/mystery/1-0")).build();
            assertEquals(null, tryStore(unknown, StudyValidationService.StandardKind.UNKNOWN,
                    unknown.metadataProducts(), RunStandard.of("standards/mystery/1-0")));
        });
    }


    @Test
    void tryStoreProvider_nullWhenNoStoreConfigured()
    {
        // No store property; guard against an env var leaking a store into the test.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv(StoreMetadataProviderFactory.STORE_ENV) == null,
                "CDISC_METADATA_STORE set in environment");
        String prev = System.getProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
        try
        {
            System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
            StudyValidationParams params = StudyValidationParams.builder()
                    .manager(mock(IDataTableManager.class))
                    .metadataProducts(List.of("standards/sdtmig/3-4")).build();
            assertEquals(null, tryStore(params, StudyValidationService.StandardKind.SDTM,
                    params.metadataProducts(), RunStandard.of("standards/sdtmig/3-4")));
        }
        finally
        {
            if (prev != null)
            {
                System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, prev);
            }
        }
    }


    @Test
    void tryStoreProvider_nullWhenStandardAbsentFromStore()
    {
        withStoreConfigured(() ->
        {
            StudyValidationParams params = StudyValidationParams.builder()
                    .manager(mock(IDataTableManager.class))
                    .metadataProducts(List.of("standards/sdtmig/9-9")).build();
            // Store resolves but no sdtmig/9-9 product ⇒ WARNING + degrade (null), no throw.
            assertEquals(null, tryStore(params, StudyValidationService.StandardKind.SDTM,
                    params.metadataProducts(), RunStandard.of("standards/sdtmig/9-9")));
        });
    }


    @Test
    void tryStoreProvider_resolvesProductAndSuppClassFromStore()
    {
        withStoreConfigured(() ->
        {
            StudyValidationParams params = StudyValidationParams.builder()
                    .manager(mock(IDataTableManager.class))
                    .metadataProducts(List.of("standards/sdtmig/3-4")).build();
            var provider = tryStore(params, StudyValidationService.StandardKind.SDTM,
                    params.metadataProducts(), RunStandard.of("standards/sdtmig/3-4"));
            org.junit.jupiter.api.Assumptions.assumeTrue(provider != null,
                    "sdtmig/3-4 not in the seeded store");
            // getStandardModelVariables (the method behind GET_MODEL_COLUMN_ORDER) resolves a
            // non-empty list for a Findings domain (LB) once the product is loaded.
            List<String> vars = provider.getStandardModelVariables(
                    MockTable.of().name("LB").col("STUDYID", "x").build(), null);
            assertNotNull(vars);
            assertFalse(vars.isEmpty());
            // Part B on the product-derived library: SUPPAE classifies via the actual columns.
            assertEquals("RELATIONSHIP", provider.getDatasetClass("SUPPAE", "SUPPAE",
                    Set.of("RDOMAIN", "USUBJID", "QNAM", "QVAL")));
        });
    }


    /** The retired 4-argument form: the CT selection defaulted to the user's field alone (U13). */
    private static net.cumba.corej.core.exec.@Nullable MetadataProvider tryStore(
            StudyValidationParams aParams, StudyValidationService.StandardKind aKind,
            List<String> aProducts, RunStandard aRunStandard)
    {
        return StudyValidationService.tryStoreProvider(aParams, aKind, aProducts, aRunStandard,
                CtSelection.resolve(aParams.controlledTerminologyPackages(), List.of()));
    }

}
