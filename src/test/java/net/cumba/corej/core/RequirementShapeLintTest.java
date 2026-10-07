package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.FoldCapability;
import org.junit.jupiter.api.Test;

/**
 * Three shape rules of guidelines §4.4's decision procedure ({@code PLAN-requirements-doctrine} row
 * 11), each a corpus lint with a planted control. Corpus hits on 2026-10-07: 0 / 0 / 0 — the rules
 * are written down so a NEW rule cannot break them silently; a hit is a defect for its own plan,
 * never a silent edit (owner O8).
 * <ul>
 * <li><b>R-4.29</b> (REQ-7) — an {@code All} entry read in SOME but not all branches of a top-level
 * {@code or} skips the branches that do not read it (a multi-entry {@code All} is ANDed).</li>
 * <li><b>R-4.11a</b> (REQ-8) — the ONLY member of a keyless unique set ({@code is_unique_set([X])},
 * no keyword argument) is gated: an absent X empties the key tuple and every row fires
 * ({@code CDISC-AD0054}, the owner-granted exception, 2026-08-04). 6 sites, 0 ungated.</li>
 * <li><b>R-4.30</b> (REQ-10) — {@code Requirements.Variables.None} beside a Check that is nothing
 * but presence tests: the {@code None} hoist leaves no remainder, so the sister keeps
 * {@code not var_exists(X)} in its Check (the 2026-09-11 M15 precedent, re-admitted by owner
 * {@code N1}). 13 carriers, 0 hits.</li>
 * </ul>
 */
class RequirementShapeLintTest
{

    @Test
    void theCorpusKeepsTheThreeShapes() throws IOException
    {
        Map<String, RequirementLintSupport.Shipped> shipped = RequirementLintSupport.shipped();
        int disjunctive = 0;
        int keyless = 0;
        int noneCarriers = 0;
        List<String> hits = new ArrayList<>();
        for (RequirementLintSupport.Shipped s : shipped.values())
        {
            Census c = census(s);
            disjunctive += c.disjunctiveWithAll();
            keyless += c.keylessUniqueSets();
            noneCarriers += c.noneCarrier() ? 1 : 0;
            hits.addAll(c.hits());
        }
        // ⛔ Non-vacuity, measured 2026-10-07: 17 rules whose top-level Check is an `or` while they
        // declare an All entry; 6 keyless single-member unique sets; 13 None carriers.
        assertTrue(disjunctive >= 17, disjunctive + " top-level-or rules with an All entry"
                + " (17 on 2026-10-07) — the R-4.29 population went dark");
        assertTrue(keyless >= 6, keyless + " keyless single-member unique sets (6 on 2026-10-07)");
        assertTrue(noneCarriers >= 13, noneCarriers + " None carriers (13 on 2026-10-07)");
        assertEquals(List.of(), hits, "guidelines §4.4 shape rules (R-4.29, R-4.11a, R-4.30)");
    }


    @Test
    void thePlantedControlsRed()
    {
        assertEquals(1, census(rule("{\"All\":[\"AEDUR\"]}",
                "empty(AEDUR) and AESER == \\\"Y\\\" or empty(AEENDTC) and AESER == \\\"N\\\""))
                        .hits().size(),
                "R-4.29: an All entry only one disjunct reads");
        assertEquals(0, census(rule("{\"All\":[\"AESER\"]}",
                "empty(AEDUR) and AESER == \\\"Y\\\" or empty(AEENDTC) and AESER == \\\"N\\\""))
                        .hits().size(),
                "R-4.29: an All entry every disjunct reads is fine");
        assertEquals(1, census(rule("", "not is_unique_set([USUBJID])")).hits().size(),
                "R-4.11a: an ungated keyless single-member unique set");
        assertEquals(0, census(rule("{\"All\":[\"USUBJID\"]}", "not is_unique_set([USUBJID])"))
                .hits().size(), "R-4.11a: gated, as CDISC-AD0054 is");
        assertEquals(0,
                census(rule("", "var_exists(\\\"USUBJID\\\") and not is_unique_set([USUBJID])"))
                        .hits().size(),
                "R-4.11a: the legacy Check-side guard, as PMDA-AD0054 carries it");
        assertEquals(1,
                census(rule("{\"None\":[\"EPOCH\"]}", "var_exists(\\\"SESTDTC\\\")")).hits().size(),
                "R-4.30: None beside a presence-only Check");
        assertEquals(0,
                census(rule("{\"None\":[\"EPOCH\"]}",
                        "var_exists(\\\"SESTDTC\\\") and" + " empty(SESTDTC)")).hits().size(),
                "R-4.30: a non-presence remainder");
    }

    /**
     * One rule's census.
     *
     * @param disjunctiveWithAll
     *            1 when the Check is a top-level {@code or} and the rule declares {@code All}
     * @param keylessUniqueSets
     *            the keyless single-member unique-set calls
     * @param noneCarrier
     *            whether the rule declares {@code Requirements.Variables.None}
     * @param hits
     *            the violations
     */
    record Census(int disjunctiveWithAll, int keylessUniqueSets, boolean noneCarrier,
            List<String> hits)
    {
    }

    static Census census(RequirementLintSupport.Shipped s)
    {
        Set<String> all = RequirementLintSupport
                .keys(RequirementLintSupport.entries(s.body(), "All"));
        boolean none = !RequirementLintSupport.entries(s.body(), "None").isEmpty();
        int disjunctive = 0;
        int keyless = 0;
        List<String> hits = new ArrayList<>();
        boolean presenceOnly = true;
        boolean anyLeaf = false;
        for (Expr root : RequirementLintSupport.roots(s.rule()))
        {
            if (root instanceof Expr.Or or && !all.isEmpty())
            {
                disjunctive++;
                for (String entry : all)
                {
                    int reading = 0;
                    for (Expr branch : or.parts())
                    {
                        reading += names(branch).contains(entry) ? 1 : 0;
                    }
                    if (reading > 0 && reading < or.parts().size())
                    {
                        hits.add(s.id() + ": All entry " + entry + " is read by " + reading + " of "
                                + or.parts().size() + " top-level disjuncts (R-4.29)");
                    }
                }
            }
            for (AbsentTrueLeafDetector.Leaf leaf : RequirementLintSupport.leaves(root))
            {
                anyLeaf = true;
                presenceOnly &= RequirementLintSupport.isPresenceLeaf(leaf);
                String member = keylessMember(leaf.expr());
                if (member != null)
                {
                    keyless++;
                    // gated in All, or — the legacy spelling D3 retired as a MUST but did not
                    // sweep — guarded by a positive var_exists(X) in the same Check
                    // (PMDA-AD0054)
                    if (!all.contains(member.toUpperCase(Locale.ROOT))
                            && !checkGuards(root).contains(member.toUpperCase(Locale.ROOT)))
                    {
                        hits.add(s.id() + ": the only member of a keyless unique set, " + member
                                + ", is not gated (R-4.11a)");
                    }
                }
            }
        }
        if (none && anyLeaf && presenceOnly)
        {
            hits.add(s.id() + ": Requirements.Variables.None beside a Check of presence tests only"
                    + " — keep not var_exists(X) in the Check (R-4.30)");
        }
        return new Census(disjunctive, keyless, none, hits);
    }


    /** The names a Check tests with a POSITIVE {@code var_exists} (silent on their absence). */
    private static Set<String> checkGuards(Expr root)
    {
        Set<String> out = new LinkedHashSet<>();
        for (AbsentTrueLeafDetector.Leaf leaf : RequirementLintSupport.leaves(root))
        {
            RequirementLintSupport.Presence p = RequirementLintSupport.presence(leaf);
            if (p != null && p.variable() && !p.firesOnAbsence())
            {
                out.add(RequirementLintSupport.key(p.name()));
            }
        }
        return out;
    }


    /** The names a branch reads or tests for presence, upper-cased. */
    private static Set<String> names(Expr branch)
    {
        Set<String> out = new LinkedHashSet<>();
        FoldCapability.referencedColumnNames(branch)
                .forEach(n -> out.add(n.toUpperCase(Locale.ROOT)));
        for (AbsentTrueLeafDetector.Leaf leaf : RequirementLintSupport.leaves(branch))
        {
            RequirementLintSupport.Presence p = RequirementLintSupport.presence(leaf);
            if (p != null)
            {
                out.add(RequirementLintSupport.key(p.name()));
            }
        }
        return out;
    }


    /** The member of {@code is_(not_)unique_set([X])} with no keyword argument, else null. */
    private static String keylessMember(Expr e)
    {
        if (e instanceof Expr.Call c
                && ("is_unique_set".equals(c.name()) || "is_not_unique_set".equals(c.name()))
                && c.kwargs().isEmpty() && c.args().size() == 1
                && c.args().getFirst() instanceof Expr.Lit list
                && list.value() instanceof List<?> members && members.size() == 1
                && members.getFirst() instanceof Expr member)
        {
            return RequirementLintSupport.nameOf(member);
        }
        return null;
    }


    private static RequirementLintSupport.Shipped rule(String variables, String check)
    {
        String req = variables.isEmpty() ? ""
                : ",\"Requirements\":{\"Variables\":" + variables + "}";
        return RequirementLintSupport.synthetic("T-RS",
                "{\"Core\":{\"Id\":\"T-RS\"},\"Sensitivity\":\"Record\"" + req
                        + ",\"Check\":{\"expression\":\"" + check
                        + "\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}");
    }
}
