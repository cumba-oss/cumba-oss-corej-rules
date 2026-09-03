package net.cumba.corej.core.metadata.pickle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.core.rulespec.PickleCacheLocator;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit coverage for {@link PickleMetadataProviderFactory#resolveConfiguredDir(String)} — the
 * explicit → env → system-property precedence with an existence guard, used by the CLI
 * {@code --pickle-cache} flag and {@code StudyValidationService}.
 */
class PickleMetadataProviderFactoryTest
{

    @Test
    void explicitDirWins(@TempDir Path dir)
    {
        assertEquals(dir, PickleMetadataProviderFactory.resolveConfiguredDir(dir.toString()));
    }


    @Test
    void explicitNonexistentDir_returnsNull()
    {
        assertNull(PickleMetadataProviderFactory
                .resolveConfiguredDir("/no/such/pickle/cache/path-xyz"));
    }


    @Test
    void blankExplicit_fallsBackToSystemProperty(@TempDir Path dir)
    {
        // env can't be set portably in-test; cover the sysprop branch (lower precedence than env,
        // higher than nothing). Guard against an env var being set in the CI environment.
        assumeTrue(System.getenv(PickleCache.CACHE_DIR_ENV) == null,
                "CDISC_PICKLE_CACHE_DIR set in environment");
        String prev = System.getProperty(PickleCache.CACHE_DIR_PROPERTY);
        try
        {
            System.setProperty(PickleCache.CACHE_DIR_PROPERTY, dir.toString());
            assertEquals(dir, PickleMetadataProviderFactory.resolveConfiguredDir("  "));
        }
        finally
        {
            if (prev == null)
            {
                System.clearProperty(PickleCache.CACHE_DIR_PROPERTY);
            }
            else
            {
                System.setProperty(PickleCache.CACHE_DIR_PROPERTY, prev);
            }
        }
    }


    @Test
    void nothingConfigured_returnsNull()
    {
        assumeTrue(System.getenv(PickleCache.CACHE_DIR_ENV) == null,
                "CDISC_PICKLE_CACHE_DIR set in environment");
        String prev = System.getProperty(PickleCache.CACHE_DIR_PROPERTY);
        try
        {
            System.clearProperty(PickleCache.CACHE_DIR_PROPERTY);
            assertNull(PickleMetadataProviderFactory.resolveConfiguredDir(null));
        }
        finally
        {
            if (prev != null)
            {
                System.setProperty(PickleCache.CACHE_DIR_PROPERTY, prev);
            }
        }
    }


    @Test
    void forSdtm_loadsProductFromBundledCache()
    {
        // SDTM IG product resolution from a real Python pickle cache, when one is located.
        Path cache = PickleCacheLocator.locate().orElse(null);
        assumeTrue(cache != null, "no pickle cache directory resolved");
        var provider = PickleMetadataProviderFactory.open(cache).forSdtm("sdtmig", "3-4", null);
        assumeTrue(provider.isPresent(), "sdtmig/3-4 not in the located cache");
        // getStandardModelVariables is the method behind message #1 (GET_MODEL_COLUMN_ORDER). With
        // the product loaded it resolves a non-empty allowed-variable list for a Findings domain.
        List<String> vars = provider.get().getStandardModelVariables(
                MockTable.of().name("LB").col("STUDYID", "x").build(), null);
        assertNotNull(vars);
        assertFalse(vars.isEmpty());
    }
}
