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
import net.cumba.corej.core.rulespec.PickleCacheLocator;
import net.cumba.corej.core.run.StudyValidationService.StandardKind;
import net.cumba.datatable.manager.IDataTableManager;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * EC-14 layer (ii) — {@link StudyValidationService#maybeWrapCompanion} /
 * {@code companionFromPickle} seam: ADaM runs wrap in a {@link CompanionDomainsProvider} loaded
 * from the pickle cache located by {@link PickleCacheLocator}; SDTM runs are untouched. (The
 * explicit {@code --sdtm-version} hard-fail went with the removed flag — an explicit companion is
 * now a declared metadata product, reworked in a later phase of
 * {@code plans/PLAN-metadata-product-selection.md}.)
 */
class StudyValidationServiceCompanionTest
{

    private static final @Nullable Path BUNDLED_CACHE = PickleCacheLocator.locate().orElse(null);

    private static StudyValidationParams.Builder baseBuilder()
    {
        return StudyValidationParams.builder().manager(mock(IDataTableManager.class))
                .dataLibrary("x")
                .pickleCacheDir(BUNDLED_CACHE == null ? null : BUNDLED_CACHE.toString());
    }


    @Test
    void adamRun_wrapsWithCompanionDomainsFromPickle()
    {
        assumeTrue(BUNDLED_CACHE != null, "no pickle cache directory resolved");
        assumeTrue(PickleAvailable.hasSdtmig34(), "sdtmig/3-4 not in the pickle cache");
        // ⚑ Plan 2 R9/R10: the companion is no longer derived from an in-engine ADaMIG->SDTMIG
        // table. It arrives as a DECLARED product — rules-cdisc-adamig-1-3.json declares
        // sdtmig/3-4 as its companion, and R7 appends that to the effective product list. This
        // test calls maybeWrapCompanion directly, so it must supply the effective list itself;
        // StudyValidationService.effectiveMetadataProducts builds exactly this at run time.
        StudyValidationParams params = baseBuilder().build();
        List<String> effective = List.of("standards/adam/adamig-1-3", "standards/sdtmig/3-4");
        MetadataProvider base = mock(MetadataProvider.class);

        MetadataProvider wrapped = StudyValidationService.maybeWrapCompanion(base, params,
                StandardKind.ADAM, effective, _ -> null);

        CompanionDomainsProvider companion = assertInstanceOf(CompanionDomainsProvider.class,
                wrapped);
        List<String> domains = companion.getStandardDatasetNames();
        assertNotNull(domains);
        assertFalse(domains.isEmpty());
        assertTrue(domains.contains("DM"), "companion SDTMIG domains should include DM");
    }


    @Test
    void sdtmRun_isUntouched()
    {
        StudyValidationParams params = baseBuilder()
                .metadataProducts(List.of("standards/sdtmig/3-4")).build();
        MetadataProvider base = mock(MetadataProvider.class);

        MetadataProvider result = StudyValidationService.maybeWrapCompanion(base, params,
                StandardKind.SDTM, params.metadataProducts(), null);

        assertSame(base, result, "SDTM run must not be wrapped");
    }


    @Test
    void tigAdamRun_uncachedCompanion_returnsBaseUnwrapped()
    {
        assumeTrue(BUNDLED_CACHE != null, "no pickle cache directory resolved");
        // A declared standards/tig/9-9/adam product routes the companion to tig 9-9/sdtm, which
        // no cache carries. With the API loader also yielding nothing the base is returned
        // unwrapped (with a warning) rather than failing — standard_domains rules then SKIP.
        StudyValidationParams params = baseBuilder()
                .metadataProducts(List.of("standards/tig/9-9/adam")).build();
        MetadataProvider base = mock(MetadataProvider.class);

        MetadataProvider result = StudyValidationService.maybeWrapCompanion(base, params,
                StandardKind.UNKNOWN, params.metadataProducts(), _ -> null);

        assertSame(base, result, "an unresolvable companion must degrade, not wrap or fail");
    }

    /** Small guard so the wrap test skips gracefully when the product is absent from the cache. */
    private static final class PickleAvailable
    {

        private PickleAvailable()
        {
        }


        static boolean hasSdtmig34()
        {
            return BUNDLED_CACHE != null
                    && net.cumba.corej.core.metadata.pickle.PickleMetadataProviderFactory
                            .open(BUNDLED_CACHE).forSdtm("sdtmig", "3-4", null).isPresent();
        }
    }
}
