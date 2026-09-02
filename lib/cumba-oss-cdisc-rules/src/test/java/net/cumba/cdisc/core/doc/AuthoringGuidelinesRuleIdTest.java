package net.cumba.cdisc.core.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Structural pins on {@code documentation/CORE-RULES-AUTHORING-GUIDELINES.md}'s numbered rules and
 * its §10 audit checklist.
 *
 * <p>
 * <b>Why this test exists, and why it is derived from the document rather than from the code.</b>
 * Wave-13 lane E's decisive finding was that test coverage of the <em>code</em> is uncorrelated
 * with correctness of the <em>documentation</em>: {@code ScopeMatcher} has nine test classes and
 * its prose is wrong in three places. Assertions derived from the engine therefore add nothing to
 * doc quality. Assertions derived from the document's own <em>text</em> are a different matter, and
 * this is the cheapest, highest-value one available: the guidelines are a numbered rule set with a
 * checklist that claims to index it, so "every rule is numbered once" and "every rule appears in
 * the checklist" are decidable from the file alone.
 * </p>
 *
 * <p>
 * They were also both violated when this test was written: {@code R-4.11} and {@code R-4.12} were
 * each <b>defined twice</b> — once in §4.4 ({@code Scope.Variables} guard hoisting, the operational
 * pair that {@code SilencingGuardHoistGateTest} and half a dozen plans cite) and once in §4.5
 * ({@code Use_Case} well-formedness). The §10 checklist carried one line, {@code R-4.10..R-4.12 —
 * Use_Case well-formed}, which resolved to the §4.5 reading and so hid three operationally-loaded
 * rules from the audit entirely. Seven further rules ({@code R-4.15}, {@code R-4.16},
 * {@code R-4.17}, {@code R-5.2.1}, {@code R-5.15}, {@code R-5.16}, {@code R-6.5}) were unreachable
 * from the checklist outright.
 * </p>
 *
 * <h2>What counts as a declaration</h2>
 * <p>
 * A line whose first non-blockquote, non-whitespace content is <code>**R-&lt;id&gt;</code>
 * immediately followed by <code>**</code> or by an em dash. That is the form every rule in the
 * document uses, and it deliberately excludes the many in-prose <em>references</em> of the shape
 * <code>… **R-4.9 is deliberately untouched** …</code>.
 * </p>
 */
class AuthoringGuidelinesRuleIdTest
{

    private static final Pattern DECLARATION = Pattern
            .compile("^\\*\\*R-(\\d+\\.\\d+(?:\\.\\d+)?[a-z]?)(\\*\\*| \\u2014)");

    private static final Pattern RANGE = Pattern.compile(
            "R-(\\d+)\\.(\\d+)(?:\\.\\d+)?[a-z]?\\.\\.R-(\\d+)\\.(\\d+)(?:\\.\\d+)?[a-z]?");

    private static final Pattern TOKEN = Pattern.compile("R-(\\d+\\.\\d+(?:\\.\\d+)?[a-z]?)");

    /** Major and minor of a declared id, ignoring any {@code .1} / {@code a} suffix. */
    private static final Pattern ID_PARTS = Pattern.compile("^(\\d+)\\.(\\d+)");

    /** Declared rule id -> the 1-based line numbers declaring it. */
    private static Map<String, List<Integer>> declarations;

    private static List<String> checklistLines;

    @BeforeAll
    static void read() throws IOException
    {
        Path file = RuleDocCensus.moduleDir().resolve("documentation")
                .resolve("CORE-RULES-AUTHORING-GUIDELINES.md");
        List<String> lines = Files.readAllLines(file);

        declarations = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++)
        {
            Matcher m = DECLARATION.matcher(stripQuote(lines.get(i)));
            if (m.find())
            {
                declarations.computeIfAbsent(m.group(1), _ -> new ArrayList<>()).add(i + 1);
            }
        }

        checklistLines = new ArrayList<>();
        boolean inChecklist = false;
        for (String line : lines)
        {
            if (line.startsWith("## "))
            {
                inChecklist = line.contains("Audit checklist");
                continue;
            }
            if (inChecklist)
            {
                checklistLines.add(line);
            }
        }
    }


    /** Strips markdown blockquote prefixes and leading whitespace. */
    private static String stripQuote(String line)
    {
        String s = line;
        while (s.startsWith(">"))
        {
            s = s.substring(1);
            s = s.stripLeading();
        }
        return s.stripLeading();
    }


    /** The parse found the document, not an empty file or a renamed section. */
    @Test
    void theParseIsNotVacuous()
    {
        assertTrue(declarations.size() > 80,
                "only " + declarations.size() + " rule declarations parsed — the declaration"
                        + " pattern no longer matches the document's rule heading form, so every"
                        + " other assertion in this class is vacuous");
        assertTrue(checklistLines.stream().anyMatch(l -> l.startsWith("- [ ] R-")),
                "the §10 audit checklist section was not found or has no checklist items");
    }


    /**
     * No rule id is defined twice.
     *
     * <p>
     * A duplicated id is not a cosmetic problem: every cross-reference to it — in this document, in
     * {@code plans/}, in {@code SilencingGuardScan}'s baseline header — silently becomes ambiguous,
     * and the checklist can only index one of the two.
     * </p>
     */
    @Test
    void noRuleIdIsDeclaredTwice()
    {
        List<String> duplicates = declarations.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "R-" + e.getKey() + " at lines " + e.getValue()).sorted().toList();
        assertEquals(List.of(), duplicates,
                "these rule ids are declared more than once in"
                        + " CORE-RULES-AUTHORING-GUIDELINES.md; every reference to them is"
                        + " ambiguous and §10 can index only one: " + duplicates);
    }


    /**
     * Every declared rule is reachable from the §10 audit checklist.
     *
     * <p>
     * The checklist is the document's only index. A rule missing from it is a rule nobody runs
     * through when touching a rule file — which is the whole stated purpose of §10.
     * </p>
     *
     * <p>
     * A checklist range {@code R-x.a..R-x.b} covers every declared id with major {@code x} and
     * minor in {@code [a, b]}, letter- and dot-suffixed variants included ({@code R-9.6a} is
     * covered by {@code R-9.1..R-9.11}). A cross-major range covers nothing implicitly.
     * </p>
     */
    @Test
    void everyDeclaredRuleAppearsInTheAuditChecklist()
    {
        Set<String> covered = checklistCoverage();
        List<String> missing = declarations.keySet().stream().filter(id -> !covered.contains(id))
                .map(id -> "R-" + id).toList();
        assertEquals(List.of(), missing,
                "these numbered rules are not reachable from the §10 audit checklist, so they are"
                        + " invisible to the audit the checklist exists to drive: " + missing);
    }


    /**
     * The checklist names no rule that does not exist.
     *
     * <p>
     * The failure mode this catches is a renumbering: the rule moves, the checklist keeps pointing
     * at the old id, and the audit silently checks nothing.
     * </p>
     */
    @Test
    void theChecklistReferencesOnlyDeclaredRules()
    {
        Set<String> referenced = new LinkedHashSet<>();
        for (String line : checklistLines)
        {
            Matcher t = TOKEN.matcher(line);
            while (t.find())
            {
                referenced.add(t.group(1));
            }
        }
        List<String> dangling = referenced.stream().filter(id -> !declarations.containsKey(id))
                .map(id -> "R-" + id).toList();
        assertEquals(List.of(), dangling,
                "the §10 audit checklist names rules that are not declared anywhere in the"
                        + " document — a renumbering left the index pointing at nothing: "
                        + dangling);
    }


    /** Ids the checklist covers, expanding {@code R-x.a..R-x.b} ranges over declared ids. */
    private static Set<String> checklistCoverage()
    {
        Set<String> covered = new LinkedHashSet<>();
        for (String line : checklistLines)
        {
            Matcher r = RANGE.matcher(line);
            while (r.find())
            {
                if (!r.group(1).equals(r.group(3)))
                {
                    continue;
                }
                int major = Integer.parseInt(r.group(1));
                int from = Integer.parseInt(r.group(2));
                int to = Integer.parseInt(r.group(4));
                for (String id : declarations.keySet())
                {
                    Matcher p = ID_PARTS.matcher(id);
                    if (!p.find())
                    {
                        continue;
                    }
                    int idMajor = Integer.parseInt(p.group(1));
                    int idMinor = Integer.parseInt(p.group(2));
                    if (idMajor == major && idMinor >= from && idMinor <= to)
                    {
                        covered.add(id);
                    }
                }
            }
            Matcher t = TOKEN.matcher(line);
            while (t.find())
            {
                covered.add(t.group(1));
            }
        }
        return covered;
    }

}
