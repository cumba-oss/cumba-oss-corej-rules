package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.UngatedProviderReachException;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.rulespec.ViolationNormaliser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.SyntheticDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code ViolationNormaliser.ErrorReason.UNGATED_PROVIDER_REACH} is <b>reachable</b> with the
 * engine's own wording — the same proof shape as {@link StageBErrorReasonReachabilityTest}: no
 * message is typed here. The real {@code DictionaryFunctions} is reached with no dictionary
 * provider on the run, throws the real {@code UngatedProviderReachException}, and its message is
 * handed to the classifier exactly as {@code RuleRunner}'s catch hands it
 * ({@code String.valueOf(e.getMessage())} into {@code statusMessage}). A reworded tail in the
 * exception reds here, as {@code OTHER}, instead of silently reclassifying every tripwire ERROR.
 *
 * <p>
 * ⚑ Population is zero by construction: with the gates intact a shipped rule never reaches the
 * function without its provider (the S3a sabotage of {@code PLAN-function-surface-wave1} phase 3 is
 * the end-to-end proof that the pins see it when it does). Reaching {@code RuleRunner.execute}
 * itself is therefore impossible from this repository, so the wrapping into a
 * {@code RuleExecutionResult} is done here — the message is the engine's.
 * </p>
 */
class UngatedProviderReachErrorReasonReachabilityTest
{

    @Test
    void aDictionaryFunctionReachedWithoutItsProviderClassifiesAsUngatedProviderReach()
    {
        IDataTable ts = new SyntheticDataTable("TS", List.of("TSVALCD", "TSVAL"), new String[]
        {
                "R16CO5Y76E", "ASPIRIN"
        }, 1);
        EvalRun run = EvalRun.fullRange(EvaluationContext.builder().table(ts).build());
        List<Vector> args = Arrays.asList(ConstVector.of("R16CO5Y76E"), ConstVector.of("ASPIRIN"),
                ConstVector.of("unii"), null);
        UngatedProviderReachException ex = assertThrows(UngatedProviderReachException.class,
                () -> DictionaryFunctions.codeTermPair(run, args));
        assertTrue(ex.getMessage().contains("valid_external_dictionary_code_term_pair reached"),
                () -> "not the tripwire this test constructed: " + ex.getMessage());

        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("TEST-UNGATED-1")
                .violations(List.of()).totalRows(1L).status(RuleExecutionStatus.ERROR)
                .statusMessage(String.valueOf(ex.getMessage())).build();
        assertEquals(List.of(ViolationNormaliser.ErrorReason.UNGATED_PROVIDER_REACH.name()),
                ViolationNormaliser.reasonsFrom(result, null),
                () -> "the engine's tripwire wording no longer matches the classifier's suffix; it "
                        + "would degrade every such ERROR to OTHER. Engine said: "
                        + ex.getMessage());
    }


    @Test
    void anUnrelatedErrorDoesNotClassifyAsUngatedProviderReach()
    {
        // The control: the suffix match is a classification of the tripwire, not of ERROR itself.
        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("TEST-UNGATED-2")
                .violations(List.of()).totalRows(1L).status(RuleExecutionStatus.ERROR)
                .statusMessage("valid_external_dictionary_code_term_pair reached something else")
                .build();
        assertEquals(List.of(ViolationNormaliser.ErrorReason.OTHER.name()),
                ViolationNormaliser.reasonsFrom(result, null));
    }
}
