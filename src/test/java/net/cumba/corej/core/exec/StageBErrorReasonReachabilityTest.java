package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.typed.ForeignDatasetInventory;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.rulespec.ViolationNormaliser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.SyntheticDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code ViolationNormaliser.ErrorReason.STAGE_B} is <b>reachable</b>, and this is the proof —
 * D104e of {@code plans/PLAN-typed-expression-engine.md}.
 *
 * <h2>⛔ Why a classification unit test was not enough</h2> The classifier's own tests in
 * {@code ExpectedErrorsTest} feed it a message <em>they</em> wrote. That pins the branch's logic
 * and nothing else: if the engine's prefix and the classifier's constant drifted apart, or if the
 * stage-B gate could not produce an {@code ERROR} at all, every one of those tests would still be
 * green. This codebase has met that shape repeatedly — a test that proves a contract nobody
 * exercises. <b>So these tests type no message.</b> They drive the real gate,
 * {@code RuleRunner.stageBGate}, and hand whatever the <em>engine</em> built straight to the
 * classifier. A reworded prefix in {@code RuleRunner.mapStageBReport} reds here, as {@code OTHER},
 * instead of silently reclassifying every stage-B {@code ERROR}.
 *
 * <h2>⚑ Population is zero, and that is why this exists</h2> Every armed {@code StageBErrorKind} is
 * measured at zero over both of D102c's populations, and the D118 join-flag consumers
 * ({@code CDISC-AD0053}, {@code CDISC-AD0256}, {@code PMDA-AD0258}, {@code PMDA-AD0053}) each
 * declare their joined dataset in {@code Requirements.Variables}, so an absent parent takes the
 * SKIP branch rather than the armed one. A latent channel proved by construction is a different
 * thing from a comment asserting it cannot happen.
 *
 * <h2>Why this class lives in package {@code net.cumba.corej.core.exec}</h2>
 * {@code RuleRunner.stageBGate} is package-private on purpose — it is an internal seam, not API —
 * and this repository already carries tests in that package for the same reason. Reaching it
 * through {@code RuleRunner.execute} is impossible by design: the specialiser resolves every
 * surface the wildcard check asserts, so that path only fires on a specialiser defect.
 */
class StageBErrorReasonReachabilityTest
{

    /** A run in which no foreign dataset resolves — the {@code _matched_} flag's error case. */
    private static final ForeignDatasetInventory NOTHING_RESOLVES = datasetName -> null;

    private static Rule exprRule(String id, String expression)
    {
        Rule rule = new Rule();
        rule.setId(id);
        rule.setCheckExpr(CheckExpressionParser.parse(expression));
        return rule;
    }


    @Test
    void anUnresolvedWildcardBindErrorClassifiesAsStageB()
    {
        IDataTable table = new SyntheticDataTable("AE", List.of("AEOCCUR"), new String[]
        {
                "N"
        }, 1);
        Rule rule = exprRule("TEST-STAGEB-1", "--OCCUR != \"N\"");

        RuleExecutionResult result = RuleRunner.stageBGate(rule, table, true, "TEST-STAGEB-1", null,
                null, Set.of());

        // Anti-vacuity: without this the test would "pass" on a gate that produced nothing at
        // all, because reasonsFrom(null, ...) is the empty list by contract.
        assertNotNull(result,
                "the stage-B gate produced no outcome — the case was not constructed");
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus());
        assertTrue(result.getStatusMessage().contains("--OCCUR"),
                () -> "not the finding this test constructed: " + result.getStatusMessage());
        assertEquals(List.of(ViolationNormaliser.ErrorReason.STAGE_B.name()),
                ViolationNormaliser.reasonsFrom(result, null),
                () -> "the engine's stage-B wording no longer matches the classifier's prefix; it "
                        + "would degrade every stage-B ERROR to OTHER. Engine said: "
                        + result.getStatusMessage());
    }


    @Test
    void anUnresolvableMatchedFlagBindErrorClassifiesAsStageB()
    {
        // The armed kind the shipped corpus can actually reach since D118: four rules spell
        // `<D>._matched_`. Each declares its joined dataset, so production takes the SKIP branch —
        // this drives the branch behind it, with an undeclared flag and a run that resolves
        // nothing.
        IDataTable table = new SyntheticDataTable("ADSL", List.of("USUBJID"), new String[]
        {
                "001"
        }, 1);
        Rule rule = exprRule("TEST-STAGEB-2", "not DM._matched_");

        RuleExecutionResult result = RuleRunner.stageBGate(rule, table, true, "TEST-STAGEB-2", null,
                NOTHING_RESOLVES, Set.of());

        assertNotNull(result,
                "the stage-B gate produced no outcome — the case was not constructed");
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus());
        assertTrue(result.getStatusMessage().contains("DM._matched_"),
                () -> "not the finding this test constructed: " + result.getStatusMessage());
        assertEquals(List.of(ViolationNormaliser.ErrorReason.STAGE_B.name()),
                ViolationNormaliser.reasonsFrom(result, null),
                () -> "the engine's stage-B wording no longer matches the classifier's prefix. "
                        + "Engine said: " + result.getStatusMessage());
    }


    @Test
    void aCleanBindProducesNoReasonAtAll()
    {
        // The control that makes the two above mean something: the gate is not firing on
        // everything, so STAGE_B is a classification of a real condition rather than of the
        // instrument.
        IDataTable table = new SyntheticDataTable("AE", List.of("AEOCCUR"), new String[]
        {
                "N"
        }, 1);
        Rule rule = exprRule("TEST-STAGEB-3", "AEOCCUR != \"N\"");

        RuleExecutionResult result = RuleRunner.stageBGate(rule, table, true, "TEST-STAGEB-3", null,
                null, Set.of());

        assertNull(result);
        assertEquals(List.of(), ViolationNormaliser.reasonsFrom(result, null));
    }
}
