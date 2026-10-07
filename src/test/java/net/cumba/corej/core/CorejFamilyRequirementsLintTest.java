package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.BroadcastFold;
import net.cumba.corej.core.expr.eval.FoldCapability;
import net.cumba.corej.core.expr.typed.TypeExpectations;
import org.junit.jupiter.api.Test;

/**
 * The MUSTs guidelines §4.4 sets for the generated {@code COREJ} family only
 * ({@code PLAN-requirements-doctrine} row 12; owner decisions D2 / D4 and {@code O2}, 2026-10-05):
 * <ul>
 * <li><b>R-4.28 (d)</b> (REQ-3d, D4) — a keyed ordinary join's dataset is declared in
 * {@code Requirements.Datasets} too;</li>
 * <li><b>R-7.8</b>'s {@code COREJ} clause (REQ-5, D2) — an OPERAND pair {@code X op DS.X} is gated
 * on BOTH sides in {@code Requirements.Variables.All}, and carries no {@code Check}-side
 * {@code var_exists("DS.X")}. This AST check also covers the plain column-vs-column pairs the
 * absent-true detector cannot fold (D39a);</li>
 * <li><b>R-4.11</b> as a MUST (REQ-6, D2) — a column read only by POSITIVE leaves of the top-level
 * conjunction (FALSE on the absent column, the runner's own fold) is in {@code All}; KEYs, marker
 * templates and presence-tested names excluded;</li>
 * <li><b>R-4.31</b> (REQ-11, {@code O2}: <i>"COREJ only. We might have a corpus sweep in future but
 * not now."</i>) — an {@code All} / {@code Any} entry over a column the rule reads in a typed
 * position carries {@code :N} (numeric) / {@code :C} (character), and no suffix without one.</li>
 * </ul>
 *
 * <p>
 * ⚠ <b>The family is not landed</b> (catalog task {@code T18}: {@code rules-src/checks} holds CDISC
 * / DRAFT / FDA / PMDA), so on 2026-10-07 the shipped population is EMPTY — and a lint over an
 * empty population passes vacuously. The population floor is therefore a set of SYNTHETIC planted
 * rules, one per MUST plus a clean one, run through the same decision on every build; the day the
 * family lands its rules join the population and the corpus assertion starts to bite.
 * </p>
 */
class CorejFamilyRequirementsLintTest
{

    static final String FAMILY = "COREJ-";

    private static final Set<Expr.BinOp> COMPARISONS = Set.of(Expr.BinOp.EQ, Expr.BinOp.NEQ,
            Expr.BinOp.LT, Expr.BinOp.GT, Expr.BinOp.LE, Expr.BinOp.GE);

    @Test
    void theLandedFamilyKeepsItsMusts() throws IOException
    {
        List<String> hits = new ArrayList<>();
        int population = 0;
        for (RequirementLintSupport.Shipped s : RequirementLintSupport.shipped().values())
        {
            if (s.id().startsWith(FAMILY))
            {
                population++;
                hits.addAll(violations(s));
            }
        }
        assertEquals(List.of(), hits, "a COREJ rule breaks a guidelines §4.4 COREJ MUST");
        // ⚑ 0 shipped and 0 authored on 2026-10-07 (not landed). A landing whose authored rules
        // never reach the shipped corpus would leave this lint over an empty population: red then.
        long authored;
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files
                .walk(java.nio.file.Path.of("rules-src", "checks")))
        {
            authored = files.filter(f -> f.getFileName().toString().startsWith(FAMILY)).count();
        }
        assertTrue(authored == 0 || population > 0, authored + " COREJ rule file(s) authored, "
                + population + " shipped — the family landed and this lint sees none of it");
    }


    /**
     * The synthetic population: each MUST broken once reds exactly once; the clean rule is clean.
     */
    @Test
    void theSyntheticPopulationRedsEachMust()
    {
        String keys = ",\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]";
        String join = ",\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}]";
        RequirementLintSupport.Shipped clean = rule("COREJ-T-OK",
                "{\"All\":[\"USUBJID\",\"AESEV:C\",\"AGE\",\"DM.AGE\"]" + keys + "}", "[\"DM\"]",
                join, "AESEV == \\\"SEVERE\\\" and AGE != DM.AGE");
        assertEquals(List.of(), violations(clean), "the clean planted rule");
        assertEquals(1,
                violations(rule("COREJ-T-3D",
                        "{\"All\":[\"USUBJID\",\"AESEV:C\",\"AGE\",\"DM.AGE\"]" + keys + "}", "",
                        join, "AESEV == \\\"SEVERE\\\" and AGE != DM.AGE")).size(),
                "R-4.28 (d)");
        assertEquals(1,
                violations(
                        rule("COREJ-T-5", "{\"All\":[\"USUBJID\",\"AESEV:C\",\"AGE\"]" + keys + "}",
                                "[\"DM\"]", join, "AESEV == \\\"SEVERE\\\" and AGE != DM.AGE"))
                                        .size(),
                "R-7.8 COREJ: one side ungated");
        assertEquals(1,
                violations(
                        rule("COREJ-T-6", "{\"All\":[\"USUBJID\",\"AGE\",\"DM.AGE\"]" + keys + "}",
                                "[\"DM\"]", join, "AESEV == \\\"SEVERE\\\" and AGE != DM.AGE"))
                                        .size(),
                "R-4.11 MUST: AESEV ungated");
        assertEquals(1,
                violations(rule("COREJ-T-11",
                        "{\"All\":[\"USUBJID\",\"AESEV\",\"AGE\",\"DM.AGE\"]" + keys + "}",
                        "[\"DM\"]", join, "AESEV == \\\"SEVERE\\\" and AGE != DM.AGE")).size(),
                "R-4.31: AESEV without :C");
    }


    static List<String> violations(RequirementLintSupport.Shipped s)
    {
        List<String> out = new ArrayList<>();
        JsonNode body = s.body();
        Set<String> all = RequirementLintSupport.keys(RequirementLintSupport.entries(body, "All"));
        Set<String> datasets = RequirementLintSupport.keys(RequirementLintSupport.datasets(body));
        // R-4.28 (d)
        for (JsonNode entry : body.path("Match_Datasets"))
        {
            String name = entry.path("Name").asText("");
            boolean keyed = entry.path("Keys").size() > 0;
            if (keyed && !entry.path("Child").asBoolean(false) && !"RELREC".equalsIgnoreCase(name)
                    && !datasets.contains(name.toUpperCase(Locale.ROOT)))
            {
                out.add(s.id() + ": the keyed join " + name
                        + " is not declared in Requirements.Datasets (R-4.28 (d))");
            }
        }
        AbsentTrueLeafDetector.Probe probe = AbsentTrueLeafDetector.probe(s.rule());
        String checkText = body.path("Check").toString();
        Set<String> notPositive = new HashSet<>();
        Set<String> positive = new LinkedHashSet<>();
        Set<String> excluded = new HashSet<>();
        for (Expr root : AbsentTrueLeafDetector.compiledRoots(probe.spec()))
        {
            List<Expr> conjuncts = root instanceof Expr.And and ? and.parts() : List.of(root);
            for (AbsentTrueLeafDetector.Leaf leaf : RequirementLintSupport.leaves(root))
            {
                RequirementLintSupport.Presence p = RequirementLintSupport.presence(leaf);
                if (p != null)
                {
                    excluded.add(RequirementLintSupport
                            .key(AbsentTrueLeafDetector.written(p.name(), checkText)));
                    if (p.name().indexOf('.') > 0)
                    {
                        out.add(s.id() + ": a Check-side var_exists(\"" + p.name()
                                + "\") — a COREJ rule gates the joined column in All (R-7.8, D2)");
                    }
                    continue;
                }
                operandPair(leaf.expr(), all, checkText, s.id(), out);
                keys(leaf.expr(), excluded, checkText);
                Set<String> reads = FoldCapability.readColumnNames(leaf.expr());
                boolean topLevel = conjuncts.contains(leaf.expr()) || conjuncts.stream()
                        .anyMatch(c -> c instanceof Expr.Not n && n.inner() == leaf.expr());
                boolean isPositive = reads.size() == 1 && topLevel
                        && AbsentTrueLeafDetector.absentVerdict(leaf, reads.iterator().next(),
                                probe.ctx(), probe.expectations()) == BroadcastFold.Verdict.FALSE;
                for (String x : reads)
                {
                    String k = RequirementLintSupport
                            .key(AbsentTrueLeafDetector.written(x, checkText));
                    // an ADaM marker template or a glob (a lowercase marker, a `*`) is a wildcard
                    // pair template, excluded by REQ-6 — decided on the name AS READ, before the
                    // key
                    // upper-cases it (review round 1: the old upper-case test after key() was dead)
                    if (x.indexOf('*') >= 0 || !x.equals(x.toUpperCase(Locale.ROOT)))
                    {
                        excluded.add(k);
                    }
                    (isPositive ? positive : notPositive).add(k);
                }
            }
        }
        for (String k : positive)
        {
            if (!notPositive.contains(k) && !excluded.contains(k) && !all.contains(k))
            {
                out.add(s.id() + ": " + k + " is read only by positive top-level conjuncts and is"
                        + " not in Requirements.Variables.All (R-4.11, a MUST for COREJ)");
            }
        }
        typeSuffixes(s, probe, out);
        return out;
    }


    /** R-7.8 COREJ clause: {@code X op DS.X} is gated on both sides. */
    private static void operandPair(Expr e, Set<String> all, String checkText, String id,
            List<String> out)
    {
        if (e instanceof Expr.Binary b && COMPARISONS.contains(b.op())
                && b.left() instanceof Expr.Ref l && b.right() instanceof Expr.Ref r && isColumn(l)
                && isColumn(r)
                && (l.kind() == OperandKind.DOTTED_REF || r.kind() == OperandKind.DOTTED_REF))
        {
            for (Expr.Ref side : List.of(l, r))
            {
                String k = RequirementLintSupport
                        .key(AbsentTrueLeafDetector.written(side.name(), checkText));
                if (!all.contains(k))
                {
                    out.add(id + ": the OPERAND " + k + " of a column-vs-column comparison is not"
                            + " in Requirements.Variables.All (R-7.8, a MUST for COREJ)");
                }
            }
        }
    }


    private static boolean isColumn(Expr.Ref r)
    {
        return r.kind() == OperandKind.COLUMN || r.kind() == OperandKind.WILDCARD_COLUMN
                || r.kind() == OperandKind.DOTTED_REF;
    }


    /** The KEY columns of a call: unique-set members and {@code keys=} entries. */
    private static void keys(Expr e, Set<String> excluded, String checkText)
    {
        if (e instanceof Expr.Call c)
        {
            if (c.name().contains("unique_set") || c.name().contains("unique_relationship"))
            {
                FoldCapability.referencedColumnNames(c).forEach(n -> excluded.add(
                        RequirementLintSupport.key(AbsentTrueLeafDetector.written(n, checkText))));
            }
            Expr k = c.kwargs().get("keys");
            if (k != null)
            {
                FoldCapability.referencedColumnNames(k).forEach(n -> excluded.add(
                        RequirementLintSupport.key(AbsentTrueLeafDetector.written(n, checkText))));
            }
        }
    }


    /** R-4.31: a typed read carries its suffix; a suffix carries a typed read. */
    private static void typeSuffixes(RequirementLintSupport.Shipped s,
            AbsentTrueLeafDetector.Probe probe, List<String> out)
    {
        TypeExpectations t = probe.expectations();
        for (String facet : List.of("All", "Any"))
        {
            for (String entry : RequirementLintSupport.entries(s.body(), facet))
            {
                String e = entry.trim();
                String name = AbsentTrueLeafDetector
                        .specialised(e.contains(":") ? e.substring(0, e.lastIndexOf(':')) : e);
                String suffix = e.contains(":")
                        ? e.substring(e.lastIndexOf(':') + 1).toUpperCase(Locale.ROOT)
                        : "";
                Set<TypeExpectations.Expectation> x = t.expectationsOf(name);
                boolean numeric = x.contains(TypeExpectations.Expectation.NUMERIC);
                boolean character = !numeric && x.contains(TypeExpectations.Expectation.CHARACTER);
                String want = numeric ? "N" : character ? "C" : "";
                String have = suffix.startsWith("N") ? "N" : suffix.startsWith("C") ? "C" : "";
                if (!want.equals(have))
                {
                    out.add(s.id() + ": " + e + " — the rule reads " + name
                            + (want.isEmpty() ? " in no typed position" : " as " + want)
                            + " (R-4.31, a MUST for COREJ)");
                }
            }
        }
    }


    private static RequirementLintSupport.Shipped rule(String id, String variables, String datasets,
            String join, String check)
    {
        return RequirementLintSupport.synthetic(id, "{\"Core\":{\"Id\":\"" + id + "\"},"
                + "\"Sensitivity\":\"Record\",\"Requirements\":{\"Variables\":" + variables
                + (datasets.isEmpty() ? "" : ",\"Datasets\":" + datasets) + "}" + join
                + ",\"Check\":{\"expression\":\"" + check
                + "\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}");
    }
}
