package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * CIT &#167;3, phase 3c of {@code PLAN-case-insensitive-templates} (2026-09-28): a rule tests a
 * column NAME as a string with the dataset side folded ({@code ends_with(upper(varname()), "FL")},
 * {@code upper(varname()) =~ /^ANL/}, {@code prefix(upper(varname()), 2) not in $x}). This census
 * walks every authored Check's AST and pins the folded name tests, the folded name memberships, the
 * deliberately unfolded rosters and the two-sidedness of exact-cell probes.
 *
 * <p>
 * ⚑ Until {@code PLAN-retire-check-lowering} (2026-10) this class was
 * {@code CaseFoldedNameTestLoweringTest} and also proved that the operator-leaf lowering
 * ({@code DualFormCheckNode}, the Java mirror of the Python {@code check_expr.py}) mapped a folded
 * subject to the same leaf as the unwrapped form; the lowering and its Python mirror (whose own
 * suite was {@code test_case_folded_name_tests.py}) were deleted with that plan, and the three
 * lowering cases went with them. The AST census — the live half — is unchanged.
 * </p>
 *
 * <p>
 * Review round 3 (2026-09-28) made the corpus census an AST walk with EXACT pins, as in the then
 * Python suite, and added the name MEMBERSHIP arm: a line-wrapped {@code varname() not in
 * $library_variables} in {@code CDISC-AD0509} / {@code AD0510} survived 3b and 3c beside a folded
 * {@code ends_with(upper(varname()), "SDT")} and fired on a conformant lowercase ADSL. Every name
 * test and every name membership / equality probe is folded bar exact rosters; a folded subject
 * meets literals in its own case; and an exact cell compared with a folded name list (or a folded
 * name compared with an exact cell list) is two-sided, so an all-lowercase study whose cell names a
 * column in the column's own spelling stays silent.
 * </p>
 */
class CaseFoldedNameTestCensusTest
{

    /** Name tests deliberately NOT folded, each for a reason its authority gives. */
    private static final Set<String> UNFOLDED = Set.of(
            // naming rules: the subject IS the letter case / characters of the name
            "CDISC-AD0014", "CDISC-AD0015", "CDISC-SEND-0030", "PMDA-AD0013",
            // (CDISC-AD0018 / CDISC-AD0709 / PMDA-AD0018 left 2026-09-28: their carve-out tests
            // the LIBRARY name, var_name("LIBRARY") !~ /[a-z]/, which isName does not count)
            // no letter in the pattern: varname() !~ /[0-9]/
            "CDISC-AD0708");

    /** Unfolded name tests paired with their folded twin against the same exact cell list. */
    private static final Set<String> TWO_SIDED_NAME_TESTS = Set.of("CDISC-CG0349");

    /**
     * Name membership / equality probes deliberately NOT folded. Empty since 2026-09-28, when
     * PMDA-AD0018 compared {@code equalsIgnoreCase(varname(), var_name("LIBRARY"))}; since
     * {@code PLAN-library-template-label-resolution} that rule pairs the names through the library
     * resolution itself (a numbered column resolves to its template, {@code TRT01P} to
     * {@code TRTxxP}) and carries no name probe at all.
     */
    private static final Set<String> UNFOLDED_MEMBERSHIP = Set.of();

    /** Exact cells compared with a folded name-list binding and with its unfolded source. */
    private static final Set<String> TWO_SIDED_CELL_PROBES = Set.of("CDISC-CG0370", "FDA-SD0075",
            "PMDA-SD0075");

    /** Measured 2026-09-28 (both corpora identical); exact, like the Python suite's pins. */
    // 2026-10-02 (rule round 2026-10-02): 112 -> 115 rules / 191 -> 197 sites, and membership
    // 48 -> 61 rules / 68 -> 114 sites - the round added upper() folds (CG0332/CG0333 prefix and
    // suffix, CG0425/CG9672/SD0029/SD9715 none, AD0746 upper(SRCDOM), the CG0013/SD0058 siblings
    // upper(varname()) membership). Re-derived from a run, not nudged.
    // 2026-10-03 (rule round 2026-10-02 c): membership sites 114 -> 135, rules unchanged at 61.
    // The CG0013 family's allowed = model OR IG adds one upper(varname()) membership to each of
    // CG0013, -A..-D and CG0351 (+6), and the PMDA-SD0058 ladder adds three to each of SD0058,
    // -A..-D (+15). Re-derived per rule against 43233244^.
    // 2026-10-04 (PLAN-library-template-label-resolution): name-test sites 197 -> 192 and
    // membership 61 -> 50 rules / 135 -> 124 sites, rules unchanged at 115. The 18 stem rules
    // CDISC-AD0503-AD0520 select their stem by var_name("LIBRARY") == "*SDT" (a LIBRARY-name
    // test, which this census does not count), so the eleven `upper(varname()) not in
    // $library_variables` memberships (-11 rules / -11 sites), the four
    // `upper(varname()) !~ /^TR(T|0[1-9]|[1-9][0-9])SDTF$/` exclusions of AD0513/14/19/20 and
    // AD0504's `not ends_with(upper(varname()), "DTM")` (-5 sites) are retired as structural;
    // the rules' own ends_with gates stay counted. Re-derived from a run, not nudged.
    private static final int EXPECTED_FOLDED_RULES = 115;

    private static final int EXPECTED_FOLDED_SITES = 192;

    private static final int EXPECTED_FOLDED_MEMBERSHIP_RULES = 50;

    private static final int EXPECTED_FOLDED_MEMBERSHIP_SITES = 124;

    private static final Set<String> PREDICATES = Set.of("ends_with", "starts_with", "contains",
            "does_not_contain", "prefix_matches", "suffix_matches");

    private static final Set<Expr.BinOp> MEMBERSHIP = Set.of(Expr.BinOp.IN, Expr.BinOp.NOT_IN,
            Expr.BinOp.EQ, Expr.BinOp.NEQ);

    private static final Pattern REGEX_ESCAPE = Pattern.compile("\\\\.");

    private static final Pattern FOLDED_LIST_BINDING = Pattern
            .compile("\\s*upper\\((\\$\\w+)\\)\\s*");

    /** Per rule: the census of its Check (every level). */
    private static final Map<String, Census> CORPUS = new TreeMap<>();

    private static final Map<String, JsonNode> CHECKS = new TreeMap<>();

    /**
     * A name-test or membership site: the fold ({@code upper}/{@code lower}) or null, and the
     * comparison node.
     */
    private record Site(@Nullable String fold, Expr node)
    {

    }


    /** One comparison of an exact operand with a name-list binding. */
    private record CellProbe(Expr.BinOp op, Expr left, String list)
    {

    }


    private static final class Census
    {

        final List<Site> tests = new ArrayList<>();

        final List<Site> members = new ArrayList<>();

        final List<CellProbe> foldedCellProbes = new ArrayList<>();

        final Set<CellProbe> plainProbes = new java.util.HashSet<>();

    }

    /**
     * A DATA-side name source: {@code varname()} or {@code var_name(level)} for any level but
     * LIBRARY. {@code var_name("LIBRARY")} is the name the standard publishes, in the standard's
     * own spelling (a template keeps its lower-case index, TRTxxP), so testing it is no data-name
     * test and needs no fold.
     */
    private static boolean isName(Expr e)
    {
        return e instanceof Expr.Call c && (("varname".equals(c.name()) && c.args().isEmpty())
                || ("var_name".equals(c.name()) && !isLibraryName(c)));
    }


    private static boolean isLibraryName(Expr.Call c)
    {
        return c.args().size() == 1 && c.args().get(0) instanceof Expr.Lit l
                && l.kind() == Expr.LitKind.STRING
                && "LIBRARY".equalsIgnoreCase((String) l.value());
    }


    private static @Nullable String foldOf(Expr e)
    {
        return e instanceof Expr.Call c && ("upper".equals(c.name()) || "lower".equals(c.name()))
                && c.args().size() == 1 && isName(c.args().get(0)) ? c.name() : null;
    }


    private static boolean isNameOrFolded(Expr e)
    {
        return isName(e) || foldOf(e) != null;
    }


    /** {@code e} with every fold of a name source replaced by the bare name source. */
    private static Expr stripFold(Expr e)
    {
        if (foldOf(e) != null)
        {
            return ((Expr.Call) e).args().get(0);
        }
        return switch (e)
        {
        case Expr.And a -> new Expr.And(
                a.parts().stream().map(CaseFoldedNameTestCensusTest::stripFold).toList());
        case Expr.Or o -> new Expr.Or(
                o.parts().stream().map(CaseFoldedNameTestCensusTest::stripFold).toList());
        case Expr.Not n -> new Expr.Not(stripFold(n.inner()));
        case Expr.Binary b -> new Expr.Binary(b.op(), stripFold(b.left()), stripFold(b.right()));
        case Expr.Call c ->
        {
            Map<String, Expr> kwargs = new LinkedHashMap<>();
            c.kwargs().forEach((k, v) -> kwargs.put(k, stripFold(v)));
            yield new Expr.Call(c.name(),
                    c.args().stream().map(CaseFoldedNameTestCensusTest::stripFold).toList(),
                    kwargs);
        }
        default -> e;
        };
    }


    private static void walk(Expr e, @Nullable Expr parent, Census out,
            Map<String, String> foldedLists)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> walk(p, null, out, foldedLists));
        case Expr.Or o -> o.parts().forEach(p -> walk(p, null, out, foldedLists));
        case Expr.Not n -> walk(n.inner(), null, out, foldedLists);
        case Expr.Binary b ->
        {
            if ((b.op() == Expr.BinOp.MATCH || b.op() == Expr.BinOp.NMATCH)
                    && isNameOrFolded(b.left()))
            {
                out.tests.add(new Site(foldOf(b.left()), b));
            }
            if (MEMBERSHIP.contains(b.op()))
            {
                membership(b, out);
            }
            if ((b.op() == Expr.BinOp.IN || b.op() == Expr.BinOp.NOT_IN)
                    && b.right() instanceof Expr.Ref r)
            {
                String source = foldedLists.get(r.name());
                boolean foldedLeft = b.left() instanceof Expr.Call c
                        && ("upper".equals(c.name()) || "lower".equals(c.name()));
                if (source != null && !foldedLeft)
                {
                    out.foldedCellProbes.add(new CellProbe(b.op(), b.left(), source));
                }
                out.plainProbes.add(new CellProbe(b.op(), b.left(), r.name()));
            }
            walk(b.left(), b, out, foldedLists);
            walk(b.right(), b, out, foldedLists);
        }
        case Expr.Call c ->
        {
            if (!c.args().isEmpty() && isNameOrFolded(c.args().get(0)))
            {
                if (PREDICATES.contains(c.name()))
                {
                    out.tests.add(new Site(foldOf(c.args().get(0)), c));
                }
                else if ("prefix".equals(c.name()) || "suffix".equals(c.name()))
                {
                    out.tests.add(new Site(foldOf(c.args().get(0)), parent != null ? parent : c));
                }
            }
            c.args().forEach(a -> walk(a, null, out, foldedLists));
            c.kwargs().values().forEach(a -> walk(a, null, out, foldedLists));
        }
        case Expr.Lit l when l.kind() == Expr.LitKind.LIST -> ((List<?>) l.value())
                .forEach(i -> walk((Expr) i, null, out, foldedLists));
        default ->
        {
            // a leaf: nothing to census
        }
        }
    }


    private static void membership(Expr.Binary b, Census out)
    {
        List<Expr> sides = Stream.of(b.left(), b.right())
                .filter(CaseFoldedNameTestCensusTest::isNameOrFolded).toList();
        if (sides.isEmpty())
        {
            return;
        }
        String fold = null;
        for (Expr side : sides)
        {
            fold = foldOf(side);
            if (fold == null)
            {
                break;
            }
        }
        out.members.add(new Site(fold, b));
    }


    private static List<String> literalTexts(Expr e)
    {
        if (e instanceof Expr.Lit l && l.kind() == Expr.LitKind.STRING)
        {
            return List.of((String) l.value());
        }
        if (e instanceof Expr.Lit l && l.kind() == Expr.LitKind.REGEX)
        {
            return List.of(REGEX_ESCAPE.matcher((String) l.value()).replaceAll(""));
        }
        if (e instanceof Expr.Lit l && l.kind() == Expr.LitKind.LIST)
        {
            List<String> out = new ArrayList<>();
            ((List<?>) l.value()).forEach(i -> out.addAll(literalTexts((Expr) i)));
            return out;
        }
        return List.of();
    }


    /** Literals of a folded comparison whose letters are not in the fold's case. */
    private static List<String> caseMismatches(String fold, Expr node)
    {
        Pattern bad = Pattern.compile("upper".equals(fold) ? "[a-z]" : "[A-Z]");
        List<Expr> operands = switch (node)
        {
        case Expr.Binary b -> List.of(b.left(), b.right());
        case Expr.Call c -> c.args().subList(1, c.args().size());
        default -> List.of();
        };
        return operands.stream().flatMap(o -> literalTexts(o).stream())
                .filter(t -> bad.matcher(t).find()).toList();
    }


    private static List<String> expressions(@Nullable JsonNode checkNode)
    {
        if (checkNode == null || !checkNode.isObject())
        {
            return List.of();
        }
        if (checkNode.has("expression"))
        {
            return List.of(checkNode.get("expression").asText());
        }
        List<String> out = new ArrayList<>();
        for (Iterator<JsonNode> levels = checkNode.elements(); levels.hasNext();)
        {
            JsonNode level = levels.next();
            if (level.isObject() && level.has("expression"))
            {
                out.add(level.get("expression").asText());
            }
        }
        return out;
    }


    private static Census census(List<String> expressions, Map<String, String> bindings)
    {
        Map<String, String> foldedLists = new LinkedHashMap<>();
        bindings.forEach((name, text) ->
        {
            Matcher m = FOLDED_LIST_BINDING.matcher(text);
            if (m.matches())
            {
                foldedLists.put(name, m.group(1));
            }
        });
        Census c = new Census();
        for (String x : expressions)
        {
            walk(CheckExpressionParser.parse(x), null, c, foldedLists);
        }
        return c;
    }


    @BeforeAll
    static void readCorpus() throws IOException
    {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of("rules-src", "checks")))
        {
            files = walk.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        assertTrue(files.size() > 3400, "void run: rules-src/checks not found");
        for (Path file : files)
        {
            JsonNode rule = RuleCanonicalizer.YAML_READ.readTree(file.toFile());
            String id = file.getFileName().toString().replaceFirst("\\.yaml$", "");
            Map<String, String> bindings = new LinkedHashMap<>();
            JsonNode b = rule.get("Bindings");
            if (b != null && b.isArray())
            {
                b.forEach(
                        n -> bindings.put(n.path("name").asText(), n.path("expression").asText()));
            }
            CORPUS.put(id, census(expressions(rule.get("Check")), bindings));
            CHECKS.put(id, rule.get("Check"));
        }
    }


    @Test
    void unfoldedNameTestsAreExactlyTheRoster()
    {
        Set<String> unfolded = new TreeSet<>();
        Set<String> twoSided = new TreeSet<>();
        CORPUS.forEach((id, c) ->
        {
            Set<Expr> foldedKeys = new java.util.HashSet<>();
            c.tests.stream().filter(s -> s.fold() != null)
                    .forEach(s -> foldedKeys.add(stripFold(s.node())));
            c.tests.stream().filter(s -> s.fold() == null)
                    .forEach(s -> (foldedKeys.contains(s.node()) ? twoSided : unfolded).add(id));
        });
        assertEquals(new TreeSet<>(TWO_SIDED_NAME_TESTS), twoSided,
                "an unfolded name test paired with its folded twin against the same exact cell"
                        + " list is two-sided; pin it in TWO_SIDED_NAME_TESTS");
        assertEquals(new TreeSet<>(UNFOLDED), unfolded,
                "a name test on varname() / var_name(...) compares the dataset's own spelling;"
                        + " fold it with upper(...) (CIT 3) unless its authority is the letter case"
                        + " of the name, and then add it to UNFOLDED with the reason");
    }


    @Test
    void foldedNameTestPopulationIsPinned()
    {
        int rules = 0;
        int sites = 0;
        for (Census c : CORPUS.values())
        {
            long n = c.tests.stream().filter(s -> s.fold() != null).count();
            rules += n > 0 ? 1 : 0;
            sites += (int) n;
        }
        assertEquals(List.of(EXPECTED_FOLDED_RULES, EXPECTED_FOLDED_SITES), List.of(rules, sites));
    }


    @Test
    void unfoldedNameMembershipIsExactlyTheRoster()
    {
        Set<String> unfolded = new TreeSet<>();
        CORPUS.forEach((id, c) ->
        {
            if (c.members.stream().anyMatch(s -> s.fold() == null))
            {
                unfolded.add(id);
            }
        });
        assertEquals(new TreeSet<>(UNFOLDED_MEMBERSHIP), unfolded,
                "a varname() / var_name(...) compared with in / not in / == / != compares the"
                        + " dataset's own spelling; fold it with upper(...) (CIT 3: upper(X) in <set>"
                        + " is the case-insensitive membership) or pin it in UNFOLDED_MEMBERSHIP");
    }


    @Test
    void foldedMembershipPopulationIsPinned()
    {
        int rules = 0;
        int sites = 0;
        for (Census c : CORPUS.values())
        {
            long n = c.members.stream().filter(s -> s.fold() != null).count();
            rules += n > 0 ? 1 : 0;
            sites += (int) n;
        }
        assertEquals(List.of(EXPECTED_FOLDED_MEMBERSHIP_RULES, EXPECTED_FOLDED_MEMBERSHIP_SITES),
                List.of(rules, sites));
    }


    @Test
    void foldedLiteralsAreInTheCaseOfTheFold()
    {
        List<String> bad = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, Census> e : CORPUS.entrySet())
        {
            List<Site> folded = new ArrayList<>(e.getValue().tests);
            folded.addAll(e.getValue().members);
            for (Site s : folded)
            {
                if (s.fold() != null)
                {
                    checked++;
                    caseMismatches(s.fold(), s.node()).forEach(t -> bad.add(e.getKey() + ": " + t));
                }
            }
        }
        assertEquals(List.of(), bad, "a folded name is compared with a literal that has letters in"
                + " the other case, so the comparison can never hold");
        assertEquals(EXPECTED_FOLDED_SITES + EXPECTED_FOLDED_MEMBERSHIP_SITES, checked);
    }


    @Test
    void cellProbesOnFoldedNameListsAreTwoSided()
    {
        Map<String, List<CellProbe>> unpaired = new TreeMap<>();
        Set<String> found = new TreeSet<>();
        CORPUS.forEach((id, c) ->
        {
            if (!c.foldedCellProbes.isEmpty())
            {
                found.add(id);
                List<CellProbe> missing = c.foldedCellProbes.stream()
                        .filter(p -> !c.plainProbes.contains(p)).toList();
                if (!missing.isEmpty())
                {
                    unpaired.put(id, missing);
                }
            }
        });
        assertEquals(Map.of(), unpaired, "an exact cell is compared with upper($x) only; compare"
                + " it with $x as well, or an all-lowercase study (cell and column alike) fires");
        assertEquals(new TreeSet<>(TWO_SIDED_CELL_PROBES), found);
    }


    @Test
    void theDetectorsFireOnTheDefectsTheyExistFor()
    {
        // CDISC-AD0509 as phase 3c left it: a folded name test beside an unfolded membership
        Census ad0509 = census(List.of("ends_with(upper(varname()), \"SDT\") and not contains("
                + "var_label(\"DATA\"), \"Start Date\") and varname() not in $library_variables"),
                Map.of());
        assertEquals(1, ad0509.members.size());
        assertNull(ad0509.members.get(0).fold());
        // a fold against a literal of the other case
        Census lower = census(List.of("ends_with(lower(varname()), \"FL\")"), Map.of());
        assertEquals(List.of("FL"),
                caseMismatches(lower.tests.get(0).fold(), lower.tests.get(0).node()));
        Census escaped = census(List.of("upper(varname()) =~ /^ANL\\d\\dFL$/"), Map.of());
        assertEquals(List.of(), caseMismatches("upper", escaped.tests.get(0).node()),
                "an escape is no letter");
        // a one-sided cell probe, and its two-sided repair
        Map<String, String> bindings = Map.of("$v", "distinct(IDVAR)", "$v_upper", "upper($v)");
        Census one = census(List.of("IDVAR not in $v_upper"), bindings);
        assertEquals(1, one.foldedCellProbes.size());
        assertFalse(one.plainProbes.contains(one.foldedCellProbes.get(0)));
        Census two = census(List.of("IDVAR not in $v and IDVAR not in $v_upper"), bindings);
        assertTrue(two.plainProbes.contains(two.foldedCellProbes.get(0)));
        // a bare name test against ANOTHER list is not two-sided
        Census mixed = census(
                List.of("prefix(varname(), 2) not in $a and prefix(upper(varname()), 2) not in $b"),
                Map.of());
        Expr folded = mixed.tests.stream().filter(s -> s.fold() != null).findFirst().orElseThrow()
                .node();
        Expr bare = mixed.tests.stream().filter(s -> s.fold() == null).findFirst().orElseThrow()
                .node();
        assertNotEquals(stripFold(folded), bare);
        // a LIBRARY-name test (CDISC-AD0018 after 2026-09-28) is no data-name test; the data-name
        // spellings of the same leaf are still found, unfolded
        assertEquals(0, census(List.of("var_name(\"LIBRARY\") !~ /[a-z]/"), Map.of()).tests.size());
        for (String dataName : List.of("varname() !~ /[a-z]/", "var_name(\"DATA\") !~ /[a-z]/",
                "var_name(\"DEFINE\") =~ /[a-z]/"))
        {
            Census c = census(List.of(dataName), Map.of());
            assertEquals(1, c.tests.size(), dataName);
            assertNull(c.tests.get(0).fold(), dataName);
        }
        Census probe = census(List.of("varname() == var_name(\"LIBRARY\")"), Map.of());
        assertEquals(1, probe.members.size());
        assertNull(probe.members.get(0).fold(), "the data side is still an unfolded probe");
    }
}
