package net.cumba.corej.core;

import java.nio.file.Path;

/**
 * The one place that resolves where the <em>generated</em> rule corpora live.
 *
 * <h2>Why this exists</h2>
 *
 * <p>
 * Until {@code plans/PLAN-rules-corpus-build-integration.md} the generated corpus was committed
 * under the module directory and reached by three different anchoring styles at once — a bare
 * CWD-relative {@code Path.of("rules")} (which only works because this module overrides surefire's
 * {@code workingDirectory} back to the module basedir), {@code projectBasedir} plus a relative
 * segment, and {@code repoRoot} plus {@code lib/corej-cdisc-rules/…}. Three styles meant three
 * things to update whenever the directory moved, and a partial migration is invisible locally: the
 * tests that were missed keep reading a stale copy still sitting on disk, and only a fresh clone or
 * CI reds.
 * </p>
 *
 * <p>
 * The corpus is now generated into {@code target/} during {@code process-test-classes} and is not
 * committed, so it moves for real. Every consumer resolves it here instead.
 * </p>
 *
 * <h2>Resolution order</h2>
 *
 * <ol>
 * <li>the {@code corpusDir} / {@code defineCorpusDir} system property, when the build sets it;
 * <li>otherwise {@code <projectBasedir>/target/<name>}, which is what the build would have set — so
 * an IDE run with no properties at all still finds the corpus the last Maven build produced.
 * </ol>
 *
 * <p>
 * ⚠ {@code projectBasedir} itself falls back to {@code "."}. That is correct <em>for this
 * module</em> and only for it: this module's pom pins surefire's {@code workingDirectory} to
 * {@code ${project.basedir}}, so the CWD and the module directory are the same thing here. A
 * consumer in another module must not copy that fallback.
 * </p>
 *
 * <p>
 * ⚠ Resolved through methods, not static finals. A static initialiser would capture the property at
 * class-load time, and surefire supplies it per fork — the same reason
 * {@code ScenarioTrimmer.projectBasedir()} is a method.
 * </p>
 */
public final class CorpusPaths
{

    /** System property naming the generated CDISC rule corpus directory. */
    public static final String RULES_PROPERTY = "corpusDir";

    /** System property naming the generated Define-XML rule corpus directory. */
    public static final String DEFINE_RULES_PROPERTY = "defineCorpusDir";

    /** Directory name of the generated CDISC rule corpus, under {@code target/}. */
    public static final String RULES_DIR_NAME = "rules";

    /** Directory name of the generated Define-XML rule corpus, under {@code target/}. */
    public static final String DEFINE_RULES_DIR_NAME = "rules-define";

    private CorpusPaths()
    {
    }


    /**
     * The module base directory.
     *
     * @return {@code projectBasedir} when surefire supplies it, else the working directory
     */
    public static Path moduleDir()
    {
        return Path.of(System.getProperty("projectBasedir", "."));
    }


    /**
     * The generated CDISC rule corpus directory — {@code packages.json} plus the per-package
     * {@code rules-<family>-<standard>-<version>.json} files.
     *
     * @return the corpus root
     */
    public static Path rules()
    {
        return resolve(RULES_PROPERTY, RULES_DIR_NAME);
    }


    /**
     * One file inside the generated CDISC rule corpus.
     *
     * @param aFile
     *            the file name, e.g. {@code rules-sdtmig-3-4.json}
     * @return the resolved path
     */
    public static Path rules(String aFile)
    {
        return rules().resolve(aFile);
    }


    /**
     * The generated Define-XML rule corpus directory.
     *
     * @return the corpus root
     */
    public static Path defineRules()
    {
        return resolve(DEFINE_RULES_PROPERTY, DEFINE_RULES_DIR_NAME);
    }


    private static Path resolve(String aProperty, String aName)
    {
        String explicit = System.getProperty(aProperty);
        if (explicit != null && !explicit.isBlank())
        {
            return Path.of(explicit);
        }
        return moduleDir().resolve("target").resolve(aName);
    }

}
