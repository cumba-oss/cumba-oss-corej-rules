package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.expr.ast.Expr;
import org.junit.jupiter.api.Test;

/**
 * <b>R-4.27</b> — the boundary ({@code PLAN-requirements-doctrine} row 9; guidelines §4.4 step 1):
 * a rule never requires what it reports. Owner {@code M2-D24} / {@code M3-F.1} (2026-08-21): a
 * requirement is for a thing whose absence means <b>nothing to check</b> — never for a thing whose
 * absence <b>is</b> the defect.
 *
 * <ul>
 * <li><b>(a)</b> no name in {@code Requirements.Variables.All} / {@code Any} (a column) or
 * {@code Requirements.Datasets} (a dataset) that the {@code Check} tests for absence with a
 * positive-firing presence test — {@code not var_exists}, {@code var_not_exists},
 * {@code count(find_vars(T)) == 0}, {@code not ds_exists}, {@code ds_not_exists} — compared as
 * written, ignoring case, a type suffix stripped, {@code --} on both sides. The gate would skip the
 * rule on exactly the dataset it exists to report (the {@code COREJ-SDTM-0002} defect, catalog
 * {@code Q88}, had the {@code empty} spelling of the same mistake — R-4.11b's half).</li>
 * <li><b>(c)</b> no {@code :N} / {@code :C} suffix on a rule that reports a column's type
 * ({@code var_type(…)} in its {@code Check}): the suffix would turn the type defect into a
 * skip.</li>
 * </ul>
 * (b) — the verdict half — belongs to {@code AbsentColumnVerdictsLintTest} (internal corpus only):
 * a Type 1 pair stays ungated; a 2-1 reporter reads its column ungated. Corpus hits on 2026-10-07:
 * 0 for (a), 0 for (c) (three suffixed entries, {@code CDISC-SEND-0401} / {@code 0402} /
 * {@code 0403}, none on a type reporter); a hit is a defect for its own plan, never a silent edit
 * (owner O8).
 */
class RequirementBoundaryLintTest
{

    /** The calls that report a column's TYPE (the cursor accessors). */
    private static final Set<String> TYPE_REPORTERS = Set.of("var_type");

    @Test
    void noRuleRequiresWhatItReports() throws IOException
    {
        Map<String, RequirementLintSupport.Shipped> shipped = RequirementLintSupport.shipped();
        assertTrue(shipped.size() > 3000, "only " + shipped.size() + " shipped rules read");
        int absenceTests = 0;
        int suffixed = 0;
        List<String> hits = new ArrayList<>();
        for (RequirementLintSupport.Shipped s : shipped.values())
        {
            Census c = census(s);
            absenceTests += c.absenceTests();
            suffixed += c.suffixed();
            hits.addAll(c.hits());
        }
        // ⛔ Non-vacuity: measured 2026-10-07 over 3 396 shipped rules — 728 positive-firing absence
        // tests, 3 suffixed entries. A drop means the presence-test reader stopped matching a
        // spelling (the MintedParentageLintTest lesson: a detector matching one of two spellings).
        // 2026-10-12 (rule round, var_exists sweep): 728 -> 726. CDISC-CG0087 and FDA-SD1392 lost
        // their `not var_exists("--X") or <negative test>` disjuncts, which the bare negative test
        // already covered on an absent column; the reader did not go blind, the leaves are gone.
        // 2026-10-12 (rule round, batch C item 1): 726 -> 723. CDISC-CG0089, FDA-SD0041 and
        // PMDA-SD0041 moved --PRESP from the `not var_exists("--PRESP") or` disjunct to
        // Requirements.Variables.All (verdict 2-1'); the leaves are gone, not unread.
        // 723 -> 721 (batch C item 2): CDISC-SEND-0149 and FDA-SE2354 moved --EXCLFL the same way
        // (verdict 2-1, guarded sister CDISC-SEND-9445). 721 -> 720 (batch C item 5): CDISC-CG0553
        // reads empty(--DRVFL), the sheet's Condition, in place of `not var_exists or != "Y"`.
        // 720 -> 716 (item 8a): CDISC-CG0348's four arms became one; its four `not var_exists`
        // leaves (two --STAT, two --DRVFL) were each contained in the remaining arm.
        assertTrue(absenceTests >= 716, "only " + absenceTests + " positive-firing absence"
                + " test(s) read (716 on 2026-10-12) — the presence-test reader went blind");
        assertTrue(suffixed >= 3,
                "only " + suffixed + " suffixed requirement entries (3 on" + " 2026-10-07)");
        assertEquals(List.of(), hits, "R-4.27: a rule requires what it reports — the gate skips"
                + " it on exactly the dataset it exists for (guidelines §4.4 step 1)");
    }


    /** The planted controls: each violation reds, its corrected twin does not. */
    @Test
    void thePlantedControlsRed()
    {
        assertEquals(1,
                census(rule("{\"All\":[\"--OCCUR\"]}", "",
                        "not var_exists(\\\"--OCCUR\\\") or --OCCUR != \\\"N\\\"")).hits().size(),
                "(a) a gated column tested for absence (the C1 hoist)");
        assertEquals(1,
                census(rule("{\"Any\":[\"AEDUR\",\"AEENDTC\"]}", "",
                        "var_not_exists(\\\"AEDUR\\\") and AESER == \\\"Y\\\"")).hits().size(),
                "(a) var_not_exists, an Any member");
        assertEquals(1,
                census(rule("", ",\"Datasets\":[\"SV\"]",
                        "not ds_exists(\\\"SV\\\") and VISITNUM > 1")).hits().size(),
                "(a) a required dataset tested for absence (the -B sibling's gate)");
        assertEquals(1,
                census(rule("{\"All\":[\"TRTxxP\"]}", "",
                        "count(find_vars(\\\"TRTxxP\\\")) == 0 and ARM == \\\"A\\\"")).hits()
                                .size(),
                "(a) the template family spelling");
        assertEquals(1,
                census(rule("{\"All\":[\"AESTDY:N\"]}", "",
                        "var_type(\\\"DATA\\\") != \\\"Num\\\" and AESTDY > 0")).hits().size(),
                "(c) a type suffix on a type reporter");
        assertEquals(0,
                census(rule("{\"All\":[\"--OCCUR\"]}", "",
                        "var_exists(\\\"--OCCUR\\\") and --OCCUR != \\\"N\\\"")).hits().size(),
                "a POSITIVE presence test on a gated column is no boundary violation");
        assertEquals(0,
                census(rule("", "", "not var_exists(\\\"--OCCUR\\\") and AESER == \\\"Y\\\""))
                        .hits().size(),
                "the absence test without the gate is the rule working");
    }

    /**
     * One rule's census.
     *
     * @param absenceTests
     *            the positive-firing absence tests read
     * @param suffixed
     *            the suffixed {@code All} / {@code Any} entries
     * @param hits
     *            the violations
     */
    record Census(int absenceTests, int suffixed, List<String> hits)
    {
    }

    static Census census(RequirementLintSupport.Shipped s)
    {
        Set<String> variables = RequirementLintSupport
                .keys(RequirementLintSupport.entries(s.body(), "All"));
        variables.addAll(
                RequirementLintSupport.keys(RequirementLintSupport.entries(s.body(), "Any")));
        Set<String> datasets = RequirementLintSupport
                .keys(RequirementLintSupport.datasets(s.body()));
        int absence = 0;
        boolean typeReporter = false;
        List<String> hits = new ArrayList<>();
        for (Expr root : RequirementLintSupport.roots(s.rule()))
        {
            for (AbsentTrueLeafDetector.Leaf leaf : RequirementLintSupport.leaves(root))
            {
                typeReporter |= readsType(leaf.expr());
                RequirementLintSupport.Presence p = RequirementLintSupport.presence(leaf);
                if (p == null || !p.firesOnAbsence())
                {
                    continue;
                }
                absence++;
                String k = RequirementLintSupport.key(p.name());
                if (p.variable() ? variables.contains(k) : datasets.contains(k))
                {
                    hits.add(s.id() + ": requires " + p.name() + " and reports its absence");
                }
            }
        }
        int suffixed = 0;
        for (String facet : List.of("All", "Any"))
        {
            for (String e : RequirementLintSupport.entries(s.body(), facet))
            {
                if (!RequirementLintSupport.key(e)
                        .equals(e.trim().toUpperCase(java.util.Locale.ROOT)))
                {
                    suffixed++;
                    if (typeReporter)
                    {
                        hits.add(s.id() + ": " + e + " demands a type the rule reports");
                    }
                }
            }
        }
        return new Census(absence, suffixed, hits);
    }


    private static boolean readsType(Expr e)
    {
        return switch (e)
        {
        case Expr.Call c -> TYPE_REPORTERS.contains(c.name())
                || c.args().stream().anyMatch(RequirementBoundaryLintTest::readsType);
        case Expr.Binary b -> readsType(b.left()) || readsType(b.right());
        default -> false;
        };
    }


    private static RequirementLintSupport.Shipped rule(String variables, String datasets,
            String check)
    {
        String req = variables.isEmpty() && datasets.isEmpty() ? ""
                : ",\"Requirements\":{" + (variables.isEmpty() ? "" : "\"Variables\":" + variables)
                        + (variables.isEmpty() || datasets.isEmpty() ? "" : ",")
                        + (datasets.isEmpty() ? "" : datasets.substring(1)) + "}";
        return RequirementLintSupport.synthetic("T-RB",
                "{\"Core\":{\"Id\":\"T-RB\"},\"Sensitivity\":\"Record\"" + req
                        + ",\"Check\":{\"expression\":\"" + check
                        + "\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}");
    }
}
