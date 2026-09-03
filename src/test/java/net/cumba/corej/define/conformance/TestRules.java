package net.cumba.corej.define.conformance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.RuleRepository;

/**
 * Test access to the authored Define-XML corpus, which lives in <b>this</b> module as
 * {@code rules-define-src/checks/}.
 *
 * <p>
 * ⛔ <b>The corpus location is declared here once, on purpose.</b> These tests used to live in
 * {@code corej-define-conformance} and each rebuilt its own path from
 * {@code projectBasedir + "rules"} — seventeen of them. In <em>this</em> module {@code rules/} is
 * the <b>CDISC data-rule corpus</b>, 59 JSON packages and not one Define-XML rule. A test that kept
 * the old path would not crash: {@code loadDirectory} filters on {@code *.yaml}, finds none among
 * the JSON, and returns an <b>empty corpus</b> — after which every "these rules raise no violation"
 * assertion passes over nothing at all. Seventeen chances to make that mistake are now one, and
 * {@link #load()} refuses an implausibly small corpus outright.
 * </p>
 */
public final class TestRules
{

    /** The authored Define-XML corpus, {@code <module>/rules-define-src/checks}. */
    public static final Path DIR = Path.of(System.getProperty("projectBasedir", "."),
            "rules-define-src", "checks");

    /** The CDISC Define-XML Conformance Rules sheet. */
    public static final Path CDISC = DIR.resolve("CDISC");

    /** The PMDA Validation Rules Define-XML sheet. */
    public static final Path PMDA = DIR.resolve("PMDA");

    /**
     * Floor, not a pin: the corpus grows and rules get parked, so this must not be an equality. Its
     * only job is to red when the path resolves somewhere with no Define-XML rules in it.
     */
    private static final int MIN_RULES = 400;

    private TestRules()
    {
    }


    /** The full authored corpus. */
    public static List<ConformanceRule> load()
    {
        return guard(RuleRepository.loadResolved(DIR, List.of()), DIR);
    }


    /** One family's rules. */
    public static List<ConformanceRule> load(Path aFamilyDir)
    {
        return RuleRepository.loadDirectory(aFamilyDir);
    }


    private static List<ConformanceRule> guard(List<ConformanceRule> aRules, Path aDir)
    {
        if (aRules.size() < MIN_RULES)
        {
            throw new IllegalStateException("only " + aRules.size() + " Define-XML rules loaded"
                    + " from " + aDir.toAbsolutePath() + " (expected at least " + MIN_RULES
                    + "). Either the corpus moved, or the path resolves to a directory holding no"
                    + " *.yaml — which loads clean and empty, so every assertion downstream would"
                    + " pass over nothing.");
        }
        if (!Files.isDirectory(CDISC) || !Files.isDirectory(PMDA))
        {
            throw new IllegalStateException(
                    "both family directories must exist under " + aDir.toAbsolutePath());
        }
        return aRules;
    }

}
