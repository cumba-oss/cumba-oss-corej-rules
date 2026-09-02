package net.cumba.cdisc.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.cumba.cdisc.core.rulespec.PickleCacheLocator;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Cache-gated pickle provider wiring for {@link StudyValidationService} — moved out of
 * {@code StudyValidationServiceTest} by PLAN-engine-rules-decoupling (Q4): the pickle metadata
 * cache is a parity-module concern, so the {@code assumeTrue} gates would silently never run in the
 * engine. The directory is located by {@link PickleCacheLocator}.
 */
class StudyValidationServicePickleTest
{

    private static final @Nullable Path PICKLE_CACHE = PickleCacheLocator.locate().orElse(null);

    /** The located cache as a builder argument; {@code null} when none resolved. */
    private static @Nullable String pickleCacheArg()
    {
        return PICKLE_CACHE == null ? null : PICKLE_CACHE.toString();
    }


    @Test
    void tryPickleProvider_servesAdamSincePhase7_unknownKindStillNull()
    {
        // PLAN-metadata-product-selection Phase 7a: the "ADaM is deferred" guard is gone — an
        // ADaM run with a configured pickle cache now loads offline. UNKNOWN (with nothing
        // ADaM-shaped declared beyond the derived default of an unknown standard) still has no
        // loadable family and falls through to the API path.
        org.junit.jupiter.api.Assumptions.assumeTrue(PICKLE_CACHE != null,
                "no pickle cache directory resolved");
        StudyValidationParams params = StudyValidationParams.builder()
                .manager(mock(IDataTableManager.class))
                .metadataProducts(List.of("standards/adam/adamig-1-3"))
                .pickleCacheDir(pickleCacheArg()).build();
        var adam = StudyValidationService.tryPickleProvider(params,
                StudyValidationService.StandardKind.ADAM, params.metadataProducts(),
                RunStandard.of("standards/adam/adamig-1-3"));
        assertNotNull(adam, "Phase 7a: an ADaM run with a pickle cache loads offline");
        assertEquals(List.of("standards/adam/adamig-1-3"), adam.declaredStructureKeyedProducts());
        StudyValidationParams unknown = StudyValidationParams.builder()
                .manager(mock(IDataTableManager.class))
                .metadataProducts(List.of("standards/mystery/1-0")).pickleCacheDir(pickleCacheArg())
                .build();
        assertEquals(null,
                StudyValidationService.tryPickleProvider(unknown,
                        StudyValidationService.StandardKind.UNKNOWN, unknown.metadataProducts(),
                        RunStandard.of("standards/mystery/1-0")));
    }


    @Test
    void tryPickleProvider_nullWhenNoDirConfigured()
    {
        // No pickleCacheDir on the params; guard against an env var leaking a dir into the test.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv(
                        net.cumba.cdisc.core.metadata.pickle.PickleCache.CACHE_DIR_ENV) == null,
                "CDISC_PICKLE_CACHE_DIR set in environment");
        String prev = System
                .getProperty(net.cumba.cdisc.core.metadata.pickle.PickleCache.CACHE_DIR_PROPERTY);
        try
        {
            System.clearProperty(
                    net.cumba.cdisc.core.metadata.pickle.PickleCache.CACHE_DIR_PROPERTY);
            StudyValidationParams params = StudyValidationParams.builder()
                    .manager(mock(IDataTableManager.class))
                    .metadataProducts(List.of("standards/sdtmig/3-4")).build();
            assertEquals(null,
                    StudyValidationService.tryPickleProvider(params,
                            StudyValidationService.StandardKind.SDTM, params.metadataProducts(),
                            RunStandard.of("standards/sdtmig/3-4")));
        }
        finally
        {
            if (prev != null)
            {
                System.setProperty(
                        net.cumba.cdisc.core.metadata.pickle.PickleCache.CACHE_DIR_PROPERTY, prev);
            }
        }
    }


    @Test
    void tryPickleProvider_nullWhenStandardAbsentFromCache()
    {
        org.junit.jupiter.api.Assumptions.assumeTrue(PICKLE_CACHE != null,
                "no pickle cache directory resolved");
        StudyValidationParams params = StudyValidationParams.builder()
                .manager(mock(IDataTableManager.class))
                .metadataProducts(List.of("standards/sdtmig/9-9")).pickleCacheDir(pickleCacheArg())
                .build();
        // Dir resolves but no sdtmig/9-9 product ⇒ WARNING + fall back (null), no throw.
        assertEquals(null,
                StudyValidationService.tryPickleProvider(params,
                        StudyValidationService.StandardKind.SDTM, params.metadataProducts(),
                        RunStandard.of("standards/sdtmig/9-9")));
    }


    @Test
    void tryPickleProvider_resolvesProductAndSuppClassFromCache()
    {
        org.junit.jupiter.api.Assumptions.assumeTrue(PICKLE_CACHE != null,
                "no pickle cache directory resolved");
        StudyValidationParams params = StudyValidationParams.builder()
                .manager(mock(IDataTableManager.class))
                .metadataProducts(List.of("standards/sdtmig/3-4")).pickleCacheDir(pickleCacheArg())
                .build();
        var provider = StudyValidationService.tryPickleProvider(params,
                StudyValidationService.StandardKind.SDTM, params.metadataProducts(),
                RunStandard.of("standards/sdtmig/3-4"));
        org.junit.jupiter.api.Assumptions.assumeTrue(provider != null,
                "sdtmig/3-4 not in bundled cache");
        // Message #1 fix: getStandardModelVariables (the method behind GET_MODEL_COLUMN_ORDER)
        // resolves a non-empty list for a Findings domain (LB) once the product is loaded.
        List<String> vars = provider.getStandardModelVariables(
                MockTable.of().name("LB").col("STUDYID", "x").build(), null);
        assertNotNull(vars);
        assertFalse(vars.isEmpty());
        // Part B on the product-derived library: SUPPAE classifies via the actual columns.
        assertEquals("RELATIONSHIP", provider.getDatasetClass("SUPPAE", "SUPPAE",
                Set.of("RDOMAIN", "USUBJID", "QNAM", "QVAL")));
    }
}
