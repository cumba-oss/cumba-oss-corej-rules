package net.cumba.corej.core.ruletestsuites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.rulespec.ViolationNormaliser;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import org.junit.jupiter.api.Test;

/**
 * The corpus population of {@link ViolationNormaliser.ErrorReason#MERGE_REFUSED} — the scenarios
 * whose rule ERRORs because the datatable refused the merged evaluation table — measured, not
 * claimed ({@code PLAN-merged-evaluation-table} T2 fold, L3).
 *
 * <p>
 * Until the fold {@code ExpectedErrorsTest} stated this population as <b>zero</b> (true at W3a,
 * when the reason was minted); W3f's OD-10 ({@code Cardinality: "n:1"} on every DM / ADSL join) and
 * W5's OD-5 (RELTYPE as the checked cardinality) made it eight, with nothing red. A comment cannot
 * go red; this test can.
 * </p>
 *
 * <p>
 * <b>Population — taken + why:</b> every {@code expect=executionError} {@code .cdt} of the three
 * families, each executed through its family factory's own path ({@code outcomeOf}) and its engine
 * message classified by {@link ViolationNormaliser#reasonsFrom}. A scenario declaring any other
 * verdict cannot ERROR without its factory going red, so this set is the corpus's whole ERROR
 * population. Asserted as a count and as a roster: a count alone is blind to one scenario replacing
 * another.
 * </p>
 */
class MergeRefusedPopulationTest
{

    /** The measured size of the population (5 OD-10 DM copies, AD0256, CG0006, CG0174). */
    static final int EXPECTED_COUNT = 8;

    /** The population by file name, sorted. */
    static final List<String> EXPECTED = List.of(
            "CDISC-AD0256-error_cardinality_adsl_duplicate_key-ADLBC.cdt",
            "CDISC-CG0006-error_cardinality_dm_duplicate_other_subject-AE.cdt",
            "CDISC-CG0006-invalid_duplicate_dm_usubjid-AE.cdt",
            "CDISC-CG0174-error_reltype_one_side_repeats-FA.cdt",
            "CDISC-SEND-0401-invalid_dm_duplicate_usubjid_copies-BW.cdt",
            "CDISC-SEND-0402-invalid_dm_duplicate_usubjid_copies-EX.cdt",
            "CDISC-SEND-0403-invalid_dm_duplicate_usubjid_copies-FW.cdt",
            "FDA-SD1381-invalid_duplicate_dm_usubjid-MB.cdt");

    private static final String ERROR_HEAD = RuleExecutionStatus.ERROR + ": ";

    @Test
    void theMergeRefusedPopulationIsExactlyTheRoster() throws IOException
    {
        List<String> refused = new ArrayList<>();
        int population = 0;
        for (AbstractRuleTestSuitesFactory factory : RuleTestSuitesFamilyHomeTest.FACTORIES)
        {
            for (Path p : executionErrorScenarios(
                    AbstractRuleTestSuitesFactory.familyRoot(factory.family())))
            {
                population++;
                AbstractRuleTestSuitesFactory.EvalOutcome outcome = factory.outcomeOf(p);
                assertNotNull(outcome, p.getFileName() + ": out of the rule's scope");
                String reason = outcome.notExecutedReason();
                assertTrue(outcome.errored() && reason != null && reason.startsWith(ERROR_HEAD),
                        p.getFileName() + ": expect=executionError but " + reason);
                RuleExecutionResult result = RuleExecutionResult.builder().ruleId("R")
                        .violations(List.of()).totalRows(0L).status(RuleExecutionStatus.ERROR)
                        .statusMessage(reason.substring(ERROR_HEAD.length())).build();
                if (ViolationNormaliser.reasonsFrom(result, null)
                        .contains(ViolationNormaliser.ErrorReason.MERGE_REFUSED.name()))
                {
                    refused.add(p.getFileName().toString());
                }
            }
        }
        refused.sort(null);
        assertTrue(population > refused.size(),
                "every expect=executionError scenario classified MERGE_REFUSED (" + population
                        + ") — the other ERROR reasons' scenarios were not reached");
        assertEquals(EXPECTED_COUNT, refused.size(),
                "the MERGE_REFUSED population moved: " + refused);
        assertEquals(EXPECTED, refused, "the MERGE_REFUSED population changed members");
    }


    private static List<Path> executionErrorScenarios(Path aRoot) throws IOException
    {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(aRoot))
        {
            return found;
        }
        try (Stream<Path> walk = Files.walk(aRoot))
        {
            for (Path p : walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".cdt"))
                    .filter(AbstractRuleTestSuitesFactory::hasShebang).sorted().toList())
            {
                if (RuleTestCdt.load(p).getExpect() == RuleTestScenario.Verdict.EXECUTION_ERROR)
                {
                    found.add(p);
                }
            }
        }
        return found;
    }
}
