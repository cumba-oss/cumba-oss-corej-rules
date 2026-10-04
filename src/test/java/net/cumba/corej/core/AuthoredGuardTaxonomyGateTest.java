package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.cumba.corej.core.CheckExprUnits.Unit;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Gate for the two ERROR arms of the authored guard taxonomy — {@code guard-on-any-member} and
 * {@code guard-on-all-or-none-member} — over the authored corpus, on the expression AST.
 *
 * <p>
 * <b>The hazard.</b> A positive presence leaf ({@code var_exists(X)}, {@code not empty(X)}) on a
 * variable the rule's {@code Requirements.Variables} declares in an {@code Any} group silences
 * exactly the run the facet admits: a satisfied {@code Any} (some <em>other</em> member present)
 * binds the rule on a dataset where {@code X} is absent, and the guard is then false on every row.
 * An {@code All_Or_None} group ({@code PLAN-join-key-pairing}) runs the rule when the whole group
 * is absent, so a guard on a member silences that run the same way, and is unreachable-false when
 * the group is present. Both are authoring defects, never a corpus state to baseline.
 * </p>
 *
 * <p>
 * <b>The one exemption, and why it is the only one.</b> A presence leaf that is one disjunct of an
 * {@code or} whose presence disjuncts cover {@code X}'s <em>whole</em> {@code Any} group cannot
 * silence anything: whichever member satisfied the facet has its own disjunct in the same
 * {@code or}. That shape is what a grouped {@code Any} asks the Check to be ({@code Any: [[--ENDTC,
 * --ENRF, --ENRTPT], …]} beside {@code (not empty(--ENDTC) or not empty(--ENRF) or not
 * empty(--ENRTPT)) and …}). Coverage of the group is load-bearing: a disjunction over only PART of
 * a group re-opens the hazard exactly, and so does a second, rule-wide guard on the same name — the
 * exemption is per leaf (object identity), never per name. The {@code All_Or_None} arm takes no
 * exemption (there is no disjunction to mirror), and a group holding an {@code All} member is
 * forced all-present and is not this hazard (the Q9 join-key shape). Neither arm consults the guard
 * whitelist: nothing may sit above an ERROR arm.
 * </p>
 *
 * <p>
 * ⭐ <b>History.</b> Until {@code PLAN-retire-check-lowering} (2026-10-03) these arms lived in the
 * Python {@code generated/rule-inventory/lint-rules.py} lint 5, walking a lowered operator-leaf
 * view of each Check, and {@code ShippedCorpusIsClean.test_no_guard_on_any_member_in_rules_src} was
 * the gate — blind, by construction, for every rule the lowering refused (68 at the retirement, 24
 * of them carrying guards) and for the {@code All_Or_None} arm entirely. This is the 1:1 Java port
 * on {@link CheckExprUnits}, which walks the parsed expression and refuses no shape. Measured at
 * the port: on every lowerable rule the Java guard view (names, groups, mirroring set) equals the
 * Python view, and both arms report nothing on the shipped corpus — a population that was zero on
 * the Python side only for the rules it could see.
 * </p>
 *
 * <p>
 * <b>Why the corpus proves nothing about the arms.</b> The shipped population of both findings is
 * zero, and any population can fall to zero with nothing red. The synthetic cases below (the Python
 * suite's {@code AuthoredGuardTaxonomy} fixtures, ported 1:1 as expressions) prove each arm can
 * fire, and the <b>sensitivity arm</b> proves it on two real on-disk carriers mutated in memory —
 * the exact mutations the Python lint was shown to report before it was retired.
 * </p>
 */
class AuthoredGuardTaxonomyGateTest
{

    private static final Path SRC_CHECKS = Path.of("rules-src", "checks");

    /**
     * The families the gate reads. {@code DRAFT} is included: the retired Python lint excluded it
     * (the inventory's scope), but a parked candidate carries the same hazard and the arms report
     * nothing on it today, so narrowing the population would only buy blindness.
     */
    private static final List<String> FAMILIES = List.of("CDISC", "DRAFT", "FDA", "PMDA");

    /**
     * Operators whose positive unit establishes that a column is present (the retired lint's
     * {@code CHECK_GUARD_OPERATORS}): {@code var_exists(X)} and {@code not empty(X)}, which
     * {@link CheckExprUnits} reports as the folded {@code non_empty}. ({@code exists} is the
     * pre-flip generic spelling; the writer disambiguates every one to {@code var_exists}.)
     */
    private static final Set<String> GUARD_OPERATORS = Set.of("exists", "var_exists", "non_empty");

    /** {@code VISITNUM:N} → {@code VISITNUM} ({@code PLAN-variable-type-requirements}). */
    private static final Pattern TYPE_SUFFIX = Pattern.compile(":(?:N|C|Num|Char)$",
            Pattern.CASE_INSENSITIVE);

    private static final String ANY_MEMBER = "guard-on-any-member";

    private static final String ALL_OR_NONE_MEMBER = "guard-on-all-or-none-member";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Non-vacuity floors (measured 2026-10-03: 3 461 rules, 20 / 15 carriers, 2 063 guarded). */
    private static final int MIN_RULES = 3_400;

    private static final int MIN_ANY_CARRIERS = 20;

    private static final int MIN_ALL_OR_NONE_CARRIERS = 15;

    private static final int MIN_GUARDED_RULES = 2_000;

    /** One ERROR finding: the rule, the arm, the guarded variable. */
    record Finding(String rule, String finding, String variable)
    {
    }


    /** One rule's inputs: its id, its Check conditions (one per level) and its Requirements. */
    record Subject(String id, List<Expr> checks, JsonNode variables)
    {
    }


    /** The names and groups {@code Requirements.Variables} declares, type suffixes stripped. */
    record Facets(Set<String> requiredAll, Set<String> requiredAny, List<Set<String>> anyGroups,
            Set<String> paired)
    {
    }

    // ------------------------------------------------------------------ the gate

    @Test
    void theShippedCorpusCarriesNoGuardOnAnAnyOrAllOrNoneMember() throws IOException
    {
        List<Subject> corpus = authoredCorpus();
        assertTrue(corpus.size() >= MIN_RULES, "read only " + corpus.size() + " rules");
        int anyCarriers = 0;
        int pairedCarriers = 0;
        int guarded = 0;
        List<Finding> findings = new ArrayList<>();
        for (Subject s : corpus)
        {
            Facets facets = facets(s.variables());
            anyCarriers += facets.requiredAny().isEmpty() ? 0 : 1;
            pairedCarriers += facets.paired().isEmpty() ? 0 : 1;
            guarded += guardUnits(s.checks()).isEmpty() ? 0 : 1;
            findings.addAll(errorArms(s));
        }
        assertTrue(anyCarriers >= MIN_ANY_CARRIERS, "only " + anyCarriers + " Any carriers");
        assertTrue(pairedCarriers >= MIN_ALL_OR_NONE_CARRIERS,
                "only " + pairedCarriers + " All_Or_None carriers");
        assertTrue(guarded >= MIN_GUARDED_RULES, "only " + guarded + " guarded rules");
        assertEquals(List.of(), findings,
                "a positive presence guard on a Requirements.Variables.Any member (outside a"
                        + " disjunction mirroring its whole group) or on an All_Or_None member"
                        + " silences the run the facet admits — drop the guard or re-think the"
                        + " membership (rule-update workflow); never baseline it");
    }

    // ------------------------------------------------------ the synthetic port


    @Test
    void anAnyMemberGuardIsAnErrorWhateverTheWhitelistSays()
    {
        // The arms take no whitelist: the Python H3 finding was a whitelist `continue` sitting
        // above the arm. var_exists(QNAM) and QNAM =~ /.*COMM/ with Any: [QNAM, QLABEL].
        assertEquals(List.of(new Finding("R-1", ANY_MEMBER, "QNAM")),
                errorArms(subject("var_exists(QNAM) and QNAM =~ /.*COMM/", """
                        Any: ["QNAM", "QLABEL"]
                        """)));
    }


    @Test
    void aNonEmptyGuardOnAnAnyMemberIsAlsoAnError()
    {
        assertEquals(List.of(new Finding("R-1", ANY_MEMBER, "TEDUR")),
                errorArms(subject("not empty(TEDUR) and len(TEDUR) > 1", """
                        Any: ["TEDUR", "TEENRL"]
                        """)));
    }


    @Test
    void anAllOrNoneMemberGuardIsAnError()
    {
        assertEquals(List.of(new Finding("R-1", ALL_OR_NONE_MEMBER, "VISITDY")),
                errorArms(subject("var_exists(VISITDY) and VISITDY > 0", """
                        All_Or_None: [["VISITDY", "TV.VISITDY"]]
                        """)));
    }


    /**
     * Ruling Q9 ({@code PLAN-join-key-authoring-gate}): a join's first key sits bare in {@code All}
     * AND in its {@code All_Or_None} group, which forces the group all-present. A guard on that key
     * is not this hazard; a guard on a member of the OTHER group still is.
     */
    @Test
    void theQ9ShapeIsNotTheHazardButAnotherGroupStillIs()
    {
        String q9 = """
                All: ["VISITNUM"]
                All_Or_None: [["VISITNUM", "TV.VISITNUM"], ["VISITDY", "TV.VISITDY"]]
                """;
        assertEquals(List.of(), errorArms(subject("var_exists(VISITNUM) and VISITNUM > 0", q9)));
        assertEquals(List.of(new Finding("R-1", ALL_OR_NONE_MEMBER, "VISITDY")),
                errorArms(subject("var_exists(VISITDY) and VISITDY > 0", q9)));
    }


    /**
     * R3-3 / T4-1: a typed entry ({@code VISITNUM:N}, {@code AESTDTC:C}) names the same variable.
     */
    @Test
    void typedEntriesNameTheSameVariableInEveryFacet()
    {
        assertEquals("VISITNUM", stripTypeSuffix("VISITNUM:N"));
        assertEquals("VISITNUM", stripTypeSuffix("VISITNUM:char"));
        assertEquals("TV.VISITNUM", stripTypeSuffix("TV.VISITNUM"));
        assertEquals(List.of(), errorArms(subject("var_exists(VISITNUM) and VISITNUM > 0", """
                All: ["VISITNUM:N"]
                All_Or_None: [["VISITNUM", "TV.VISITNUM"]]
                """)), "a typed All entry forces the group all-present (Q9)");
        assertEquals(List.of(), errorArms(
                subject("(not empty(AESTDTC) or not empty(AEENDTC)) and len(AESTDTC) > 1", """
                        Any: [["AESTDTC:C", "AEENDTC:C"]]
                        """)), "a typed Any group mirrored by the Check is the design");
        assertEquals(List.of(Set.of("AESTDTC", "AEENDTC")), facets(variables("""
                Any: [["AESTDTC:C", "AEENDTC:C"]]
                """)).anyGroups());
        assertEquals(List.of(new Finding("R-1", ALL_OR_NONE_MEMBER, "VISITDY")),
                errorArms(subject("var_exists(VISITDY) and VISITDY > 0", """
                        All_Or_None: [["VISITDY:N", "TV.VISITDY"]]
                        """)), "a typed All_Or_None member under a rule-wide guard is the hazard");
    }


    @Test
    void allOrNoneGroupsAreReadInBothAuthoredShapes()
    {
        assertEquals(Set.of("VISITDY", "TV.VISITDY"), facets(variables("""
                All_Or_None: ["VISITDY", "TV.VISITDY"]
                """)).paired());
        assertEquals(Set.of("A", "Y.A", "B", "Y.B"), facets(variables("""
                All_Or_None: [["A", "Y.A"], ["B", "Y.B"]]
                """)).paired());
        assertEquals(Set.of(), facets(variables("{}")).paired());
    }


    /**
     * {@code Any} may be a list of groups (AND of ORs); a guarded member of group 2 still trips.
     */
    @Test
    void nestedAnyGroupsAreFlattened()
    {
        assertEquals(List.of(new Finding("R-1", ANY_MEMBER, "TEDUR")),
                errorArms(subject("not empty(TEDUR) and len(TEDUR) > 1", """
                        Any: [["TESTRL", "TEENRL"], ["TEDUR", "TEDOSE"]]
                        """)));
    }


    /** The FDA-SD0031 / PMDA-SD0031 shape: group 2 mirrored as a disjunction. Nothing silenced. */
    @Test
    void aDisjunctionMirroringAWholeGroupIsTheDesignNotTheHazard()
    {
        assertEquals(List.of(), errorArms(subject(
                "(not empty(--ENDTC) or not empty(--ENRF) or not empty(--ENRTPT)) and"
                        + " empty(--STDTC) and empty(--STRF) and empty(--STRTPT)",
                """
                        Any: [["--STDTC", "--STRF", "--STRTPT"], ["--ENDTC", "--ENRF", "--ENRTPT"]]
                        """)));
    }


    /** The CDISC-CG9675 shape: a flat {@code Any} is one group, and the whole Check mirrors it. */
    @Test
    void aFlatAnyMirroredByTheWholeCheckIsExempt()
    {
        assertEquals(List.of(), errorArms(subject("not empty(IDVAR) or not empty(IDVARVAL)", """
                None: ["RDOMAIN"]
                Any: ["IDVAR", "IDVARVAL"]
                """)));
    }


    /**
     * ⚠⚠ The counter-direction, and the reason the exemption tests for COVERAGE: {@code Any: [[A,
     * B, C]]} with a disjunction over A and B only binds on a dataset that carries C alone and can
     * then never fire.
     */
    @Test
    void aDisjunctionOverOnlyPartOfAGroupStillReports()
    {
        assertEquals(
                List.of(new Finding("R-1", ANY_MEMBER, "A"), new Finding("R-1", ANY_MEMBER, "B")),
                errorArms(subject("(not empty(A) or not empty(B)) and empty(P)", """
                        Any: [["A", "B", "C"]]
                        """)));
    }


    /** The exemption is keyed by leaf identity, not by name. */
    @Test
    void aRuleWideGuardBesideAMirrorStillReports()
    {
        assertEquals(List.of(new Finding("R-1", ANY_MEMBER, "IDVAR")), errorArms(
                subject("var_exists(IDVAR) and (not empty(IDVAR) or not empty(IDVARVAL))", """
                        Any: ["IDVAR", "IDVARVAL"]
                        """)));
    }


    /**
     * {@code not var_exists(X)} is an absence test, never a guard (the retired lowering spelled it
     * as the flat {@code var_not_exists} leaf); a guard nested under a structural {@code not} of a
     * conjunction is still a presence leaf and still reports, exactly as the lowered view did.
     */
    @Test
    void aNegatedPresenceTestIsNotAGuardButAGuardUnderAStructuralNotIs()
    {
        assertEquals(List.of(), errorArms(subject("not var_exists(QNAM) and empty(QLABEL)", """
                Any: ["QNAM", "QLABEL"]
                """)));
        assertEquals(List.of(new Finding("R-1", ANY_MEMBER, "QNAM")),
                errorArms(subject("not (var_exists(QNAM) and empty(QLABEL))", """
                        Any: ["QNAM", "QLABEL"]
                        """)));
    }

    // ---------------------------------------------------------- the sensitivity arm


    /**
     * Two real on-disk carriers, mutated in memory: the mutant must report, the authored rule must
     * not, and the mutation must have changed the text. These are the exact mutations the retired
     * Python lint was shown to report on 2026-10-03 (FDA-SD0031 minus its {@code --ENRTPT} disjunct
     * → {@code --ENDTC}, {@code --ENRF}; FDA-SD1012 behind {@code var_exists(ELEMENT)} →
     * {@code ELEMENT}), so the port agrees with the instrument it replaces on a firing case, not
     * only on an empty corpus.
     */
    @Test
    void theArmsFireOnRealCarriersMutatedInMemory() throws IOException
    {
        Subject sd0031 = readSubject("FDA", "FDA-SD0031");
        assertEquals(List.of(), errorArms(sd0031), "the authored FDA-SD0031 is the design");
        String authored = authoredText("FDA", "FDA-SD0031");
        String dropped = authored.replace(" or not empty(--ENRTPT)", "");
        assertNotEquals(authored, dropped, "the mutation must change the text");
        assertEquals(
                List.of(new Finding("FDA-SD0031", ANY_MEMBER, "--ENDTC"),
                        new Finding("FDA-SD0031", ANY_MEMBER, "--ENRF")),
                errorArms(new Subject("FDA-SD0031", List.of(CheckExpressionParser.parse(dropped)),
                        sd0031.variables())),
                "a disjunction over only part of group 2 re-opens the hazard");

        Subject sd1012 = readSubject("FDA", "FDA-SD1012");
        assertEquals(List.of(), errorArms(sd1012), "the authored FDA-SD1012 carries no guard");
        String guarded = "var_exists(ELEMENT) and (" + authoredText("FDA", "FDA-SD1012") + ")";
        assertEquals(List.of(new Finding("FDA-SD1012", ALL_OR_NONE_MEMBER, "ELEMENT")),
                errorArms(new Subject("FDA-SD1012", List.of(CheckExpressionParser.parse(guarded)),
                        sd1012.variables())));
    }

    // ------------------------------------------------------------------ the arms


    /**
     * The two ERROR arms over one rule, in Check document order, de-duplicated per (arm, variable).
     * The {@code Any} arm comes first and carries exactly one exemption (the mirroring leaves); the
     * {@code All_Or_None} arm follows for the units the first did not claim.
     */
    static List<Finding> errorArms(Subject s)
    {
        Facets facets = facets(s.variables());
        Set<Finding> seen = new LinkedHashSet<>();
        for (Expr check : s.checks())
        {
            Set<Expr> mirroring = mirroringGuardLeaves(check, facets.anyGroups());
            for (Unit unit : guardUnits(List.of(check)))
            {
                String name = unit.name();
                if (facets.requiredAny().contains(name) && !mirroring.contains(unit.node()))
                {
                    seen.add(new Finding(s.id(), ANY_MEMBER, name));
                    continue;
                }
                if (facets.paired().contains(name))
                {
                    seen.add(new Finding(s.id(), ALL_OR_NONE_MEMBER, name));
                }
            }
        }
        return List.copyOf(seen);
    }


    /**
     * Every positive presence unit of the conditions, in document order. {@code not var_exists(X)}
     * — a {@code var_exists} unit directly under a structural {@code not} — is an absence test, not
     * a guard (the retired lowering's flat {@code var_not_exists} leaf).
     */
    static List<Unit> guardUnits(List<Expr> checks)
    {
        List<Unit> out = new ArrayList<>();
        for (Expr check : checks)
        {
            for (Unit unit : CheckExprUnits.units(check))
            {
                if (isGuard(unit))
                {
                    out.add(unit);
                }
            }
        }
        return out;
    }


    private static boolean isGuard(Unit unit)
    {
        String operator = unit.operator();
        if (operator == null || unit.name() == null || !GUARD_OPERATORS.contains(operator))
        {
            return false;
        }
        boolean directlyNegated = "not".equals(unit.path()) || unit.path().endsWith("/not");
        return !("var_exists".equals(operator) && directlyNegated);
    }


    /**
     * The presence units that are one disjunct of an {@code or} whose presence disjuncts cover a
     * whole {@code Any} group, by identity of their AST node — see the class javadoc for why this
     * is the one exemption and why coverage of the whole group is load-bearing.
     */
    static Set<Expr> mirroringGuardLeaves(Expr check, List<Set<String>> anyGroups)
    {
        Set<Expr> mirroring = Collections.newSetFromMap(new IdentityHashMap<>());
        IdentityHashMap<Expr, Unit> byNode = new IdentityHashMap<>();
        for (Unit unit : CheckExprUnits.units(check))
        {
            byNode.put(unit.node(), unit);
        }
        collectMirroring(check, anyGroups, byNode, mirroring);
        return mirroring;
    }


    private static void collectMirroring(Expr node, List<Set<String>> groups,
            IdentityHashMap<Expr, Unit> byNode, Set<Expr> out)
    {
        switch (node)
        {
        case Expr.Or or ->
        {
            Map<String, Unit> presence = new java.util.HashMap<>();
            for (Expr part : or.parts())
            {
                Unit unit = directUnit(part, byNode);
                if (unit != null && isGuard(unit))
                {
                    presence.putIfAbsent(unit.name(), unit);
                }
            }
            for (Set<String> group : groups)
            {
                if (!group.isEmpty() && presence.keySet().containsAll(group))
                {
                    group.forEach(name -> out.add(presence.get(name).node()));
                }
            }
            or.parts().forEach(p -> collectMirroring(p, groups, byNode, out));
        }
        case Expr.And and -> and.parts().forEach(p -> collectMirroring(p, groups, byNode, out));
        case Expr.Not not -> collectMirroring(not.inner(), groups, byNode, out);
        default ->
        {
            // a leaf: nothing to collect
        }
        }
    }


    /**
     * The unit a direct operand of an {@code or} is, or {@code null} for a composite operand. A
     * folded negation ({@code not empty(X)} → {@code non_empty}) is keyed on the call under the
     * {@code not}, exactly as the retired lowering folded it into one flat leaf.
     */
    private static @Nullable Unit directUnit(Expr part, IdentityHashMap<Expr, Unit> byNode)
    {
        Unit unit = byNode.get(part);
        if (unit == null && part instanceof Expr.Not not)
        {
            unit = byNode.get(not.inner());
        }
        return unit;
    }

    // ---------------------------------------------------------------- the facets


    static Facets facets(@Nullable JsonNode variables)
    {
        JsonNode v = variables == null ? YAML.createObjectNode() : variables;
        Set<String> requiredAll = new TreeSet<>();
        for (JsonNode entry : v.path("All"))
        {
            requiredAll.add(stripTypeSuffix(entry.asText()));
        }
        List<Set<String>> anyGroups = groups(v.path("Any"));
        Set<String> requiredAny = new TreeSet<>();
        anyGroups.forEach(requiredAny::addAll);
        // Only groups with NO member in `All`: a group holding an All member is forced
        // all-present (the Q9 shape), so a guard on one of its members is not this hazard.
        Set<String> paired = new TreeSet<>();
        for (Set<String> group : groups(v.path("All_Or_None")))
        {
            if (Collections.disjoint(group, requiredAll))
            {
                paired.addAll(group);
            }
        }
        return new Facets(requiredAll, requiredAny, anyGroups, paired);
    }


    /**
     * A grouped facet as sets of names: a flat list of strings is ONE group, a list of lists is one
     * group per list; a stray string beside real groups is its own one-member group.
     */
    private static List<Set<String>> groups(JsonNode facet)
    {
        if (!facet.isArray() || facet.isEmpty())
        {
            return List.of();
        }
        boolean flat = true;
        for (JsonNode entry : facet)
        {
            flat &= entry.isTextual();
        }
        List<Set<String>> out = new ArrayList<>();
        if (flat)
        {
            Set<String> group = new TreeSet<>();
            facet.forEach(entry -> group.add(stripTypeSuffix(entry.asText())));
            out.add(group);
            return out;
        }
        for (JsonNode entry : facet)
        {
            Set<String> group = new TreeSet<>();
            if (entry.isArray())
            {
                entry.forEach(member -> group.add(stripTypeSuffix(member.asText())));
            }
            else
            {
                group.add(stripTypeSuffix(entry.asText()));
            }
            out.add(group);
        }
        return out;
    }


    static String stripTypeSuffix(String entry)
    {
        return TYPE_SUFFIX.matcher(entry).replaceAll("");
    }

    // ------------------------------------------------------------------ loading


    static List<Subject> authoredCorpus() throws IOException
    {
        List<Subject> out = new ArrayList<>();
        for (String family : FAMILIES)
        {
            Path dir = SRC_CHECKS.resolve(family);
            if (!Files.isDirectory(dir))
            {
                continue;
            }
            try (Stream<Path> files = Files.list(dir))
            {
                for (Path file : files.filter(p -> p.toString().endsWith(".yaml")).sorted()
                        .toList())
                {
                    out.add(subjectOf(
                            YAML.readTree(Files.readString(file, StandardCharsets.UTF_8))));
                }
            }
        }
        return out;
    }


    private static Subject subjectOf(JsonNode rule)
    {
        List<Expr> checks = new ArrayList<>();
        // ⚠ EVERY declared level (Plan C §3.3): a level map has no expression of its own.
        for (JsonNode condition : conditions(rule.get("Check")))
        {
            JsonNode text = condition.get("expression");
            if (text != null && text.isTextual())
            {
                checks.add(CheckExpressionParser.parse(text.asText()));
            }
        }
        return new Subject(rule.path("Core").path("Id").asText(), checks,
                rule.path("Requirements").path("Variables"));
    }

    /** The authorable check levels — a {@code Check:} whose every key is one is a level map. */
    private static final Set<String> LADDER = Set.of("REJECT", "ERROR", "WARNING", "INFO");

    /** One condition for a plain {@code Check:}, one per declared level for a level map. */
    private static List<JsonNode> conditions(@Nullable JsonNode check)
    {
        if (check == null || !check.isObject() || check.isEmpty())
        {
            return List.of();
        }
        List<JsonNode> levels = new ArrayList<>();
        for (java.util.Iterator<String> names = check.fieldNames(); names.hasNext();)
        {
            String name = names.next();
            if (!LADDER.contains(name))
            {
                return List.of(check);
            }
            levels.add(check.get(name));
        }
        return levels;
    }


    private static Subject readSubject(String family, String id) throws IOException
    {
        return subjectOf(YAML.readTree(Files.readString(
                SRC_CHECKS.resolve(family).resolve(id + ".yaml"), StandardCharsets.UTF_8)));
    }


    private static String authoredText(String family, String id) throws IOException
    {
        JsonNode rule = YAML.readTree(Files.readString(
                SRC_CHECKS.resolve(family).resolve(id + ".yaml"), StandardCharsets.UTF_8));
        return rule.path("Check").path("expression").asText();
    }


    private static Subject subject(String expression, String variablesYaml)
    {
        return new Subject("R-1", List.of(CheckExpressionParser.parse(expression)),
                variables(variablesYaml));
    }


    private static JsonNode variables(String yaml)
    {
        try
        {
            return YAML.readTree(yaml);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }
}
