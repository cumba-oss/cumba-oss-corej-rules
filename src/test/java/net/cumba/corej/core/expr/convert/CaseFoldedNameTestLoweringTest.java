package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * CIT &#167;3, phase 3c of {@code PLAN-case-insensitive-templates} (2026-09-28): a rule tests a
 * column NAME as a string with the dataset side folded ({@code ends_with(upper(varname()), "FL")},
 * {@code upper(varname()) =~ /^ANL/}, {@code prefix(upper(varname()), 2) not in $x}), and
 * {@link DualFormCheckNode} lowers the folded subject to the SAME leaf as the unwrapped form — the
 * Java mirror of {@code scripts/lib/check_expr.py}'s {@code _case_folded_subject}, whose own suite
 * is {@code generated/rule-inventory/test_case_folded_name_tests.py}.
 *
 * <p>
 * Without that arm every folded rule lowered to {@code null} and silently left the instruments
 * built on this class ({@code RuleDocCensus}, {@code RuleFingerprint}, the equivalence inventory).
 * The corpus test below compares every folded rule's lowering with that of its textually unfolded
 * twin, so the claim "the fold is invisible to the leaf-derived instruments" is measured on the
 * shipped rules, not argued.
 * </p>
 *
 * <p>
 * Review round 3 (2026-09-28) made the corpus census an AST walk with EXACT pins, as in the Python
 * suite, and added the name MEMBERSHIP arm: a line-wrapped {@code varname() not in
 * $library_variables} in {@code CDISC-AD0509} / {@code AD0510} survived 3b and 3c beside a folded
 * {@code ends_with(upper(varname()), "SDT")} and fired on a conformant lowercase ADSL. Every name
 * test and every name membership / equality probe is folded bar exact rosters; a folded subject
 * meets literals in its own case; and an exact cell compared with a folded name list (or a folded
 * name compared with an exact cell list) is two-sided, so an all-lowercase study whose cell names a
 * column in the column's own spelling stays silent.
 * </p>
 */
class CaseFoldedNameTestLoweringTest
{

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

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
     * Name membership / equality probes deliberately NOT folded. Empty since 2026-09-28:
     * PMDA-AD0018 compares {@code equalsIgnoreCase(varname(), var_name("LIBRARY"))}.
     */
    private static final Set<String> UNFOLDED_MEMBERSHIP = Set.of();

    /** Exact cells compared with a folded name-list binding and with its unfolded source. */
    private static final Set<String> TWO_SIDED_CELL_PROBES = Set.of("CDISC-CG0370", "FDA-SD0075",
            "PMDA-SD0075");

    /** Measured 2026-09-28 (both corpora identical); exact, like the Python suite's pins. */
    private static final int EXPECTED_FOLDED_RULES = 112;

    private static final int EXPECTED_FOLDED_SITES = 191;

    private static final int EXPECTED_FOLDED_MEMBERSHIP_RULES = 48;

    private static final int EXPECTED_FOLDED_MEMBERSHIP_SITES = 68;

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

    private static JsonNode check(String expression)
    {
        ObjectNode node = NODES.objectNode();
        node.put("expression", expression);
        return node;
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
                a.parts().stream().map(CaseFoldedNameTestLoweringTest::stripFold).toList());
        case Expr.Or o -> new Expr.Or(
                o.parts().stream().map(CaseFoldedNameTestLoweringTest::stripFold).toList());
        case Expr.Not n -> new Expr.Not(stripFold(n.inner()));
        case Expr.Binary b -> new Expr.Binary(b.op(), stripFold(b.left()), stripFold(b.right()));
        case Expr.Call c ->
        {
            Map<String, Expr> kwargs = new LinkedHashMap<>();
            c.kwargs().forEach((k, v) -> kwargs.put(k, stripFold(v)));
            yield new Expr.Call(c.name(),
                    c.args().stream().map(CaseFoldedNameTestLoweringTest::stripFold).toList(),
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
                .filter(CaseFoldedNameTestLoweringTest::isNameOrFolded).toList();
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


    @ParameterizedTest
    @CsvSource(delimiter = '|', value =
    {
            "ends_with(upper(varname()), \"FL\")|ends_with(varname(), \"FL\")",
            "starts_with(lower(varname()), \"AE\")|starts_with(varname(), \"AE\")",
            "not contains(upper(varname()), \"DT\")|not contains(varname(), \"DT\")",
            "upper(varname()) =~ /^ANL[0-9]{2}FL$/|varname() =~ /^ANL[0-9]{2}FL$/",
            "upper(varname()) !~ /^TRTSTMF$/|varname() !~ /^TRTSTMF$/",
            "upper(var_name(\"DATA\")) =~ /DTC$/|var_name(\"DATA\") =~ /DTC$/",
            "prefix(upper(varname()), 2) not in $domain_list|prefix(varname(), 2) not in $domain_list"
    })
    void aFoldedNameSubjectLowersLikeTheUnwrappedForm(String folded, String plain)
    {
        JsonNode expected = DualFormCheckNode.lowerOrNull(check(plain));
        assertNotNull(expected, plain);
        assertEquals(expected, DualFormCheckNode.lowerOrNull(check(folded)), folded);
    }


    @Test
    void aFoldedCellValueAndOtherShapesStayUnlowerable()
    {
        // A folded CELL has its own case-insensitive v1 leaf family; a plain leaf would misstate
        // it. Both shapes must lower exactly as before the name arm existed: not at all.
        assertNull(
                DualFormCheckNode.lowerOrNull(check("not contains(upper(VISIT), \"UNSCHEDUL\")")));
        assertNull(DualFormCheckNode.lowerOrNull(check("upper(varname()) == \"AESEQ\"")));
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


    @Test
    void everyFoldedRuleLowersLikeItsUnfoldedTwin()
    {
        int compared = 0;
        for (Map.Entry<String, Census> e : CORPUS.entrySet())
        {
            if (e.getValue().tests.stream().noneMatch(s -> s.fold() != null))
            {
                continue;
            }
            JsonNode checkNode = CHECKS.get(e.getKey());
            JsonNode unfolded = unfold(checkNode);
            assertNotEquals(checkNode, unfolded, e.getKey() + ": the unfold removed nothing");
            assertEquals(DualFormCheckNode.lowerOrNull(unfolded),
                    DualFormCheckNode.lowerOrNull(checkNode), e.getKey());
            compared++;
        }
        assertEquals(EXPECTED_FOLDED_RULES, compared);
    }


    /** The same Check with every name-TEST fold textually removed (membership folds untouched). */
    private static JsonNode unfold(JsonNode checkNode)
    {
        JsonNode copy = checkNode.deepCopy();
        if (copy.has("expression"))
        {
            ((ObjectNode) copy).put("expression", unfoldText(copy.get("expression").asText()));
            return copy;
        }
        for (Iterator<JsonNode> levels = copy.elements(); levels.hasNext();)
        {
            JsonNode level = levels.next();
            if (level.isObject() && level.has("expression"))
            {
                ((ObjectNode) level).put("expression",
                        unfoldText(level.get("expression").asText()));
            }
        }
        return copy;
    }


    private static String unfoldText(String expression)
    {
        return expression.replaceAll("upper\\(varname\\(\\)\\)(\\s*)([=!]~)", "varname()$1$2")
                .replaceAll("(ends_with|starts_with)\\(upper\\(varname\\(\\)\\),", "$1(varname(),")
                .replaceAll("prefix\\(upper\\(varname\\(\\)\\), ", "prefix(varname(), ");
    }
}
