package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.CompanionDomainsProvider;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.corej.core.run.StudyValidationService.StandardKind;
import net.cumba.datatable.manager.IDataTableManager;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * EC-14 layer (ii) — {@link StudyValidationService#maybeWrapCompanion} / {@code companionFromStore}
 * seam: ADaM runs wrap in a {@link CompanionDomainsProvider} loaded from the unified metadata store
 * {@link MetadataStoreFixture} seeds from the pickle corpus; SDTM runs are untouched. (Until cache
 * plan step 8g this pinned the {@code companionFromPickle} leg, now deleted — the store leg it pins
 * here is the one that replaced it at the same position in {@code maybeWrapCompanion}.)
 */
class StudyValidationServiceCompanionTest
{

    private static final @Nullable Path STORE = MetadataStoreFixture.storeFile().orElse(null);

    private static StudyValidationParams.Builder baseBuilder()
    {
        return StudyValidationParams.builder().manager(mock(IDataTableManager.class))
                .dataLibrary("x");
    }


    /** Runs {@code aBody} with {@code cdisc.metadata.store} pointing at the seeded store. */
    private static void withStoreConfigured(Runnable aBody)
    {
        assumeTrue(STORE != null, "no metadata store seeded (no pickle corpus resolved)");
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
    void adamRun_wrapsWithCompanionDomainsFromStore()
    {
        assumeTrue(hasSdtmig34(), "sdtmig/3-4 not in the seeded store");
        // ⚑ Plan 2 R9/R10: the companion is no longer derived from an in-engine ADaMIG->SDTMIG
        // table. It arrives as a DECLARED product — rules-cdisc-adamig-1-3.json declares
        // sdtmig/3-4 as its companion, and R7 appends that to the effective product list. This
        // test calls maybeWrapCompanion directly, so it must supply the effective list itself;
        // StudyValidationService.effectiveMetadataProducts builds exactly this at run time.
        withStoreConfigured(() ->
        {
            StudyValidationParams params = baseBuilder().build();
            List<String> effective = List.of("standards/adam/adamig-1-3", "standards/sdtmig/3-4");
            MetadataProvider base = mock(MetadataProvider.class);

            MetadataProvider wrapped = StudyValidationService.maybeWrapCompanion(base, params,
                    StandardKind.ADAM, effective);

            CompanionDomainsProvider companion = assertInstanceOf(CompanionDomainsProvider.class,
                    wrapped);
            List<String> domains = companion.getStandardDatasetNames();
            assertNotNull(domains);
            assertFalse(domains.isEmpty());
            assertTrue(domains.contains("DM"), "companion SDTMIG domains should include DM");
        });
    }


    @Test
    void sdtmRun_isUntouched()
    {
        StudyValidationParams params = baseBuilder()
                .metadataProducts(List.of("standards/sdtmig/3-4")).build();
        MetadataProvider base = mock(MetadataProvider.class);

        MetadataProvider result = StudyValidationService.maybeWrapCompanion(base, params,
                StandardKind.SDTM, params.metadataProducts());

        assertSame(base, result, "SDTM run must not be wrapped");
    }


    @Test
    void tigAdamRun_uncachedCompanion_returnsBaseUnwrapped()
    {
        // A declared standards/tig/9-9/adam product routes the companion to tig 9-9/sdtm, which
        // no store carries, so the base is returned
        // unwrapped (with a warning) rather than failing — standard_domains rules then SKIP.
        withStoreConfigured(() ->
        {
            StudyValidationParams params = baseBuilder()
                    .metadataProducts(List.of("standards/tig/9-9/adam")).build();
            MetadataProvider base = mock(MetadataProvider.class);

            MetadataProvider result = StudyValidationService.maybeWrapCompanion(base, params,
                    StandardKind.UNKNOWN, params.metadataProducts());

            assertSame(base, result, "an unresolvable companion must degrade, not wrap or fail");
        });
    }


    /** Small guard so the wrap test skips gracefully when the product is absent from the store. */
    private static boolean hasSdtmig34()
    {
        return MetadataStoreFixture.factory()
                .flatMap(factory -> factory.forSdtm("sdtmig", "3-4", List.<String> of()))
                .isPresent();
    }
}
