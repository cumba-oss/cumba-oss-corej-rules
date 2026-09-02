package net.cumba.cdisc.core.rulespec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.cumba.cdisc.core.metadata.pickle.PickleCache;

/**
 * Single resolution point for the Python-engine pickle metadata cache used by this module's tests
 * and by {@link SpecRunner} (PLAN-fork-removal-stage4 phase 1).
 *
 * <p>
 * Precedence — the {@code cdisc.pickle.cache.dir} system property, then the
 * {@code CDISC_PICKLE_CACHE_DIR} environment variable, then the built-in defaults, first existing
 * directory wins. Stage 4 phase 4 removed the two {@code cdisc-rules-engine} submodule entries
 * along with the fork itself, so the standalone cache is now the <b>only</b> default.
 * </p>
 *
 * <p>
 * ⚠ <b>That sole default is a path on this project's dev hosts, not a universal one.</b> A host
 * that does not carry {@code /data/cdisc.metadata.library-cache-pkl} — a CI runner, a fresh clone
 * on another machine — resolves nothing here and <b>must</b> set the property or the environment
 * variable. That is deliberate: {@code PickleProviderGuardTest} then fails loudly, naming the fix,
 * rather than letting the provider-less specs flip {@code EXECUTED} → {@code SKIPPED} silently.
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

    /**
     * Tried in order when neither the property nor the environment names a directory. The two
     * fork-relative entries went with the submodule in stage 4 phase 4; the list shape is kept so a
     * future default is one line, not a refactor.
     */
    private static final List<Path> DEFAULTS = List
            // The standalone cache, which survived the fork's removal.
            .of(Path.of("/data/cdisc.metadata.library-cache-pkl"));

    private PickleCacheLocator()
    {
    }


    /**
     * Resolves the pickle metadata cache directory.
     *
     * @return the first directory that exists, or empty when none does.
     * @throws IllegalStateException
     *             when the property or the environment names a path that is not a directory. This
     *             is deliberate and is half the anti-vacuity design: silently falling back to a
     *             default is exactly how a typo'd path becomes a green, vacuous run, and after the
     *             fork's removal that is the difference between a typo and a correct run.
     */
    public static Optional<Path> locate()
    {
        String configured = System.getProperty(PickleCache.CACHE_DIR_PROPERTY);
        if (configured == null || configured.isBlank())
        {
            configured = System.getenv(PickleCache.CACHE_DIR_ENV);
        }
        if (configured != null && !configured.isBlank())
        {
            Path explicit = Path.of(configured);
            if (!Files.isDirectory(explicit))
            {
                throw new IllegalStateException(PickleCache.CACHE_DIR_ENV + " / "
                        + PickleCache.CACHE_DIR_PROPERTY + " is set to '" + configured
                        + "', which is not a directory. Fix it or unset it — this is never "
                        + "silently ignored, because a wrong path and no path look identical "
                        + "in a test log.");
            }
            // Absolutised for the same reason the defaults are: a caller that stores the value
            // must not have it re-resolve against a different working directory.
            return Optional.of(explicit.toAbsolutePath());
        }
        return DEFAULTS.stream().map(Path::toAbsolutePath).filter(Files::isDirectory).findFirst();
    }
}
