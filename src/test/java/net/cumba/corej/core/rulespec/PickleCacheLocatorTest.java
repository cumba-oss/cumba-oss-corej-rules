package net.cumba.corej.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.cumba.corej.core.metadata.pickle.PickleCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit coverage for {@link PickleCacheLocator} (PLAN-fork-removal-stage4 phase 1).
 *
 * <p>
 * ⚠ The environment branch is deliberately <b>not</b> asserted here: an environment variable cannot
 * be set portably in-process, and the two neuter arms of the plan's phase 2 exercise it from the
 * outside (a partial cache directory and a non-existent one, both passed as
 * {@code CDISC_PICKLE_CACHE_DIR}). Every test that reads the un-configured resolution therefore
 * {@code assumeTrue}s the variable is unset, exactly as {@code PickleMetadataProviderFactoryTest}
 * does.
 * </p>
 *
 * <p>
 * ⚠ These tests mutate {@code cdisc.pickle.cache.dir} and restore it in a {@code finally}. That is
 * safe only because this module configures <b>no</b> JUnit parallelism (no
 * {@code junit-platform.properties}, no surefire {@code parallel} setting): the five cache
 * constants elsewhere in the module are captured once at class initialisation, so a concurrent
 * class-init during one of these windows would capture a {@code @TempDir} instead of the real
 * cache. If parallel execution is ever switched on, move these to a dedicated fork.
 * </p>
 */
class PickleCacheLocatorTest
{

    private static void withProperty(String aValue, Runnable aBody)
    {
        String prev = System.getProperty(PickleCache.CACHE_DIR_PROPERTY);
        try
        {
            System.setProperty(PickleCache.CACHE_DIR_PROPERTY, aValue);
            aBody.run();
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
    void configuredPropertyWins(@TempDir Path dir)
    {
        withProperty(dir.toString(), () ->
        {
            Optional<Path> located = PickleCacheLocator.locate();
            assertEquals(Optional.of(dir), located);
            // Absolutised: a caller that stores the value must not have it re-resolve against a
            // different working directory.
            assertTrue(located.orElseThrow().isAbsolute());
        });
    }


    @Test
    void aBlankPropertyFallsThrough()
    {
        assumeTrue(System.getenv(PickleCache.CACHE_DIR_ENV) == null,
                "CDISC_PICKLE_CACHE_DIR set in environment");
        assumeTrue(System.getProperty(PickleCache.CACHE_DIR_PROPERTY) == null,
                "cdisc.pickle.cache.dir set on the command line");
        Optional<Path> unconfigured = PickleCacheLocator.locate();
        withProperty("   ", () -> assertEquals(unconfigured, PickleCacheLocator.locate()));
    }


    @Test
    void aConfiguredButMissingDirectoryThrows(@TempDir Path dir)
    {
        Path missing = dir.resolve("nope");
        withProperty(missing.toString(), () ->
        {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    PickleCacheLocator::locate);
            // The message must name the offending value and both keys: after the fork's removal
            // this is the only thing distinguishing a typo from an absent cache.
            assertTrue(thrown.getMessage().contains(missing.toString()), thrown.getMessage());
            assertTrue(thrown.getMessage().contains(PickleCache.CACHE_DIR_ENV),
                    thrown.getMessage());
            assertTrue(thrown.getMessage().contains(PickleCache.CACHE_DIR_PROPERTY),
                    thrown.getMessage());
        });
    }


    @Test
    void aConfiguredFileIsNotADirectoryAndThrows(@TempDir Path dir) throws Exception
    {
        Path file = Files.createFile(dir.resolve("standards_details.pkl"));
        withProperty(file.toString(),
                () -> assertThrows(IllegalStateException.class, PickleCacheLocator::locate));
    }


    @Test
    void unconfiguredResolvesNothing()
    {
        assumeTrue(System.getenv(PickleCache.CACHE_DIR_ENV) == null,
                "CDISC_PICKLE_CACHE_DIR set in environment");
        assumeTrue(System.getProperty(PickleCache.CACHE_DIR_PROPERTY) == null,
                "cdisc.pickle.cache.dir set on the command line");
        // Holds on EVERY host, because the locator has no built-in default any more — including a
        // host that happens to carry a pickle cache somewhere. That is the point of the removal:
        // the old default made this assertion, and the outcome of every pickle-backed test,
        // depend on the machine the build ran on rather than on what the caller configured.
        assertEquals(Optional.empty(), PickleCacheLocator.locate());
    }
}
