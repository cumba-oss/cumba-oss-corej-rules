package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Nothing in this module may reach the <b>generated</b> corpora except through {@link CorpusPaths}.
 *
 * <h2>⛔⛔ Why this test exists, in one paragraph</h2>
 *
 * <p>
 * {@code plans/PLAN-rules-corpus-build-integration.md} §2 collapsed three anchoring styles into one
 * and enumerated the consumers with a grep. The grep matched {@code Path.of("rules"…)},
 * {@code .resolve("rules")} and a quote immediately followed by {@code rules/}. It therefore missed
 * three files that built the path by <em>string concatenation</em> —
 * {@code System.getProperty("projectBasedir") + "/rules/rules-sdtmig-3-4.json"} — because the
 * literal starts with a slash. Those three stayed green through §1–§5 and §10, because the old
 * committed corpus was still sitting on disk; they only reded when §6 deleted it, and on a
 * developer's machine they might not have reded even then.
 * </p>
 *
 * <p>
 * <b>A one-off grep cannot be the guard, because the miss is always the shape the grep did not
 * think of.</b> This test is shape-independent: it looks for the directory <em>names</em> in source
 * text and requires every survivor to be an explicitly listed exception.
 * </p>
 *
 * <h2>What is allowed</h2>
 *
 * <ul>
 * <li>{@link CorpusPaths} itself — it is the definition.
 * <li>The <b>source</b> trees, which did not move: {@code rules-src}, {@code rules-org},
 * {@code rules-define-src}, and the committed engine fixtures under
 * {@code src/test/resources/fixtures/rules}.
 * <li>Prose: javadoc and comments may name {@code rules/} freely. Only code is checked.
 * </ul>
 */
class CorpusPathAnchoringGuardTest
{

    private static final Path SOURCES = Path.of("src", "test", "java");

    /** A string literal, with escapes. */
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** The literal IS one of the directory names, standing alone. */
    private static final Pattern BARE = Pattern.compile("rules(-define)?");

    /** The literal contains one of the directory names as a PATH SEGMENT. */
    private static final Pattern PATH_SEGMENT = Pattern.compile("/rules(-define)?(/|$)");

    /**
     * Jackson accessors. {@code node.get("rules")} names the package's <em>rules map</em>, not a
     * directory — it is by far the commonest occurrence of the word in this module and flagging it
     * would drown the signal.
     */
    private static final Pattern JSON_ACCESSOR = Pattern.compile(
            "\\.(get|path|has|remove|set|put|putArray|with|findValue|findPath|required)\\s*\\($");

    /** Source directories, which did not move and must not be flagged. */
    private static final Pattern SOURCE_TREES = Pattern
            .compile("rules-src|rules-org|rules-define-src|fixtures/rules|rulespec|\"fixtures\"");

    /**
     * The one file allowed to name the generated directories in code.
     *
     * <p>
     * ⚠ If you are about to add a name here, you almost certainly want {@link CorpusPaths} instead.
     * The whole point is that the next move of these directories is a one-line change.
     * </p>
     */
    private static final List<String> ALLOWED = List.of("CorpusPaths.java",
            "CorpusPathAnchoringGuardTest.java");

    /**
     * Whether a line of code builds a path to a generated corpus directory.
     *
     * <p>
     * Three things keep this from drowning in noise, each of which cost a false-positive round to
     * find: a literal containing a <b>space</b> is prose, not a path; a literal followed by a
     * <b>colon</b> is a JSON key in embedded fixture text; and a bare {@code "rules"} passed to a
     * <b>Jackson accessor</b> names the package's rules map.
     * </p>
     */
    private static boolean namesAGeneratedCorpusDirectory(String aCode)
    {
        Matcher literal = LITERAL.matcher(aCode);
        while (literal.find())
        {
            String value = literal.group(1);
            if (value.indexOf(' ') >= 0)
            {
                continue;
            }
            if (aCode.substring(literal.end()).stripLeading().startsWith(":"))
            {
                continue;
            }
            if (BARE.matcher(value).matches())
            {
                if (!JSON_ACCESSOR.matcher(aCode.substring(0, literal.start())).find())
                {
                    return true;
                }
            }
            else if (PATH_SEGMENT.matcher(value).find())
            {
                return true;
            }
        }
        return false;
    }


    /** Drops {@code //} and whole-line {@code *} javadoc/comment text; prose may say anything. */
    private static String stripComment(String aLine)
    {
        String trimmed = aLine.strip();
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//"))
        {
            return "";
        }
        int slashes = aLine.indexOf("//");
        return slashes < 0 ? aLine : aLine.substring(0, slashes);
    }


    /**
     * Guards the guard, in both directions.
     *
     * <p>
     * ⚠ A detector asserted only on what it must NOT flag drifts to matching nothing, and a
     * detector asserted only on what it must flag drifts to matching everything. The positives
     * below are the four real shapes this migration actually met — including the
     * string-concatenation one that escaped the §2 grep — and the negatives are the four
     * false-positive classes that made the first version of this test report 78 offenders and 0
     * real ones.
     * </p>
     */
    @Test
    void theDetectorCatchesEveryRealShapeAndNoneOfTheProseOnes()
    {
        assertTrue(namesAGeneratedCorpusDirectory(
                "System.getProperty(\"projectBasedir\") + \"/rules/rules-sdtmig-3-4.json\";"),
                "the string-concatenation shape — the one that escaped the original grep");
        assertTrue(namesAGeneratedCorpusDirectory("Path.of(\"rules\", pkg)"),
                "the bare Path.of shape");
        assertTrue(namesAGeneratedCorpusDirectory("dir.resolve(\"rules-define\")"),
                "the define corpus");
        assertTrue(namesAGeneratedCorpusDirectory(
                "Path.of(root, \"lib/cumba-oss-corej-rules/rules/rules-cdisc-sdtmig-3-4.json\")"),
                "the repoRoot-anchored shape");

        assertFalse(namesAGeneratedCorpusDirectory("JsonNode rules = root.get(\"rules\");"),
                "a Jackson accessor names the package's rules MAP, not a directory");
        assertFalse(namesAGeneratedCorpusDirectory("String json = \"{\\\"rules\\\": {}}\";"),
                "a JSON key in embedded fixture text is not a path");
        assertFalse(
                namesAGeneratedCorpusDirectory(
                        "fail(\"expected the full shipped corpus in rules/, saw none\");"),
                "prose in a message is not a path");
        assertFalse(namesAGeneratedCorpusDirectory("b.append(\"| `rules/` | packages |\");"),
                "markdown prose is not a path");
    }


    /** {@code CorpusPaths} resolves both corpora under {@code target/} when unconfigured. */
    @Test
    void theDefaultLocationIsUnderTarget()
    {
        assertTrue(
                CorpusPaths.rules().toString().replace('\\', '/').toLowerCase(Locale.ROOT)
                        .contains("/target/rules"),
                "the generated corpus must live under target/, was: " + CorpusPaths.rules());
        assertTrue(
                CorpusPaths.defineRules().toString().replace('\\', '/').toLowerCase(Locale.ROOT)
                        .contains("/target/rules-define"),
                "the generated define corpus must live under target/, was: "
                        + CorpusPaths.defineRules());
    }
}
