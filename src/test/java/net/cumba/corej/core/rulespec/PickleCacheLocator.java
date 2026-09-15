package net.cumba.corej.core.rulespec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.cumba.corej.core.metadata.pickle.PickleCache;

/**
 * Single resolution point for the Python-engine pickle corpus this module's tests seed their
 * metadata store from (PLAN-fork-removal-stage4 phase 1; since cache plan step 8g the corpus is no
 * longer read directly — {@link MetadataStoreFixture} seeds a store from it once per JVM, and every
 * consumer reads the store).
 *
 * <p>
 * Precedence — the {@code cdisc.pickle.cache.dir} system property, then the
 * {@code CDISC_PICKLE_CACHE_DIR} environment variable. <b>There is no built-in default.</b> Stage 4
 * phase 4 removed the two {@code cdisc-rules-engine} submodule entries along with the fork itself;
 * the one entry that survived it was an absolute path on a particular set of dev hosts, and it has
 * now gone the same way. The pickle corpus is an input this repository neither ships nor generates,
 * so it has no location this repository is entitled to guess.
 * </p>
 *
 * <p>
 * ⚠ <b>A caller that sets neither key resolves nothing here.</b> That is every caller that has not
 * been told where a corpus is — a CI runner, a fresh clone, and equally a dev host that happens to
 * carry one. It is deliberate: {@code LibraryProviderGuardTest} then fails loudly, naming both
 * keys, rather than letting the provider-less specs flip {@code EXECUTED} → {@code SKIPPED}
 * silently. The project's CI workflow fetches the pickles into a directory of its own and exports
 * {@code CDISC_PICKLE_CACHE_DIR} for exactly that reason; a local run that wants the same coverage
 * must do the same.
 * </p>
 *
 * <p>
 * Precedence is <b>property first, then environment</b> — the order
 * {@link PickleCache#openIfConfigured()} established and every call site here inherited. (The
 * deleted {@code PickleMetadataProviderFactory.resolveConfiguredDir} used the opposite order; the
 * two differed only when both keys were set and disagreed, a combination with no caller in this
 * tree, and the store's own {@code StoreMetadataProviderFactory.resolveConfiguredFile} is a
 * separate key entirely.)
 * </p>
 */
public final class PickleCacheLocator
{

    private PickleCacheLocator()
    {
    }


    /**
     * Resolves the pickle metadata cache directory.
     *
     * @return the configured directory, absolutised, or empty when neither the property nor the
     *         environment names one. Empty is never a silent success: every caller that cannot work
     *         without a corpus either fails (see {@code LibraryProviderGuardTest}, which names both
     *         keys in its message) or reports a visible skip.
     * @throws IllegalStateException
     *             when the property or the environment names a path that is not a directory. This
     *             is deliberate and is half the anti-vacuity design: silently falling back is
     *             exactly how a typo'd path becomes a green, vacuous run.
     */
    public static Optional<Path> locate()
    {
        String configured = System.getProperty(PickleCache.CACHE_DIR_PROPERTY);
        if (configured == null || configured.isBlank())
        {
            configured = System.getenv(PickleCache.CACHE_DIR_ENV);
        }
        if (configured == null || configured.isBlank())
        {
            return Optional.empty();
        }
        Path explicit = Path.of(configured);
        if (!Files.isDirectory(explicit))
        {
            throw new IllegalStateException(PickleCache.CACHE_DIR_ENV + " / "
                    + PickleCache.CACHE_DIR_PROPERTY + " is set to '" + configured
                    + "', which is not a directory. Fix it or unset it — this is never "
                    + "silently ignored, because a wrong path and no path look identical "
                    + "in a test log.");
        }
        // Absolutised so that a caller which stores the value does not have it re-resolve against
        // a different working directory.
        return Optional.of(explicit.toAbsolutePath());
    }
}
