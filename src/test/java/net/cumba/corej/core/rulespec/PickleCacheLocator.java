package net.cumba.corej.core.rulespec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.cumba.corej.core.metadata.pickle.PickleCache;

/**
 * Single resolution point for the Python-engine pickle metadata cache used by this module's tests
 * and by {@link SpecRunner} (PLAN-fork-removal-stage4 phase 1).
 *
 * <p>
 * Precedence — the {@code cdisc.pickle.cache.dir} system property, then the
 * {@code CDISC_PICKLE_CACHE_DIR} environment variable. <b>There is no built-in default.</b> Stage 4
 * phase 4 removed the two {@code cdisc-rules-engine} submodule entries along with the fork itself;
 * the one entry that survived it was an absolute path on a particular set of dev hosts, and it has
 * now gone the same way. The pickle cache is an input this repository neither ships nor generates,
 * so it has no location this repository is entitled to guess.
 * </p>
 *
 * <p>
 * ⚠ <b>A caller that sets neither key resolves nothing here.</b> That is every caller that has not
 * been told where a cache is — a CI runner, a fresh clone, and equally a dev host that happens to
 * carry one. It is deliberate: {@code PickleProviderGuardTest} then fails loudly, naming both keys,
 * rather than letting the provider-less specs flip {@code EXECUTED} → {@code SKIPPED} silently. The
 * project's CI workflow fetches the pickles into a directory of its own and exports
 * {@code CDISC_PICKLE_CACHE_DIR} for exactly that reason; a local run that wants the same coverage
 * must do the same.
 * </p>
 *
 * <p>
 * ⚠ <b>Why property-before-environment, when {@code PickleMetadataProviderFactory} does the
 * opposite.</b> There are two precedence orders in the tree and they disagree:
 * </p>
 * <ul>
 * <li>{@link PickleCache#openIfConfigured()} (and {@code SpecRunner.pickleCacheDir()}, which this
 * class replaces) reads the <b>property first, then the environment</b>;</li>
 * <li>{@code PickleMetadataProviderFactory.resolveConfiguredDir(String)} resolves <b>explicit
 * argument → environment → property</b>, and {@code PickleMetadataProviderFactoryTest} documents
 * that order deliberately (<i>"the sysprop branch (lower precedence than env, higher than
 * nothing)"</i>).</li>
 * </ul>
 * <p>
 * This locator takes <b>no explicit argument</b> and replaces call sites that used the first order,
 * so it keeps the first order; the factory's documented order is left untouched. The two differ
 * only when the property and the environment are both set and disagree — a combination with no
 * caller in this tree. Aligning them is a separate, deliberate change, not a side effect of a
 * re-point.
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
     *         without a cache either fails (see {@code PickleProviderGuardTest}, which names both
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
