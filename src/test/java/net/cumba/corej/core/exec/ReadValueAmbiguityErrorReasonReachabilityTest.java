package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.ReadValueAmbiguityException;
import net.cumba.corej.core.rulespec.ViolationNormaliser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.SyntheticDataTable;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link ViolationNormaliser.ErrorReason#READ_VALUE_AMBIGUOUS} reachable with the
 * <b>engine's own</b> message (runbook W2a, {@code PLAN-operation-replacements} §2.2): a
 * {@code read_value(…, mode="ONLY")} over a dataset with two qualifying rows throws
 * {@link ReadValueAmbiguityException}, and a {@code RuleExecutionResult} carrying that message as
 * its ERROR status classifies as {@code READ_VALUE_AMBIGUOUS} — never {@code OTHER}. The sibling of
 * {@code UngatedProviderReachErrorReasonReachabilityTest}: the classifier's suffix and the engine's
 * wording are pinned against each other, not against a comment.
 */
class ReadValueAmbiguityErrorReasonReachabilityTest
{

    @Test
    void anOnlyReadOverTwoQualifyingRowsClassifiesAsReadValueAmbiguous()
    {
        // Two SPECIES rows in TS. SyntheticDataTable reads cell (r, c) as cycle[(r + c) % len],
        // so with this cycle TSPARMCD is SPECIES on both rows and TSVAL is SPECIES / DOG.
        IDataTable ts = new SyntheticDataTable("TS", List.of("TSPARMCD", "TSVAL"), new String[]
        {
                "SPECIES", "SPECIES", "DOG"
        }, 2);
        IDataTable dm = new SyntheticDataTable("DM", List.of("USUBJID"), new String[]
        {
                "S1"
        }, 1);
        EvaluationContext ctx = EvaluationContext.builder().table(dm).ruleId("TEST-READ-VALUE-1")
                .datasetResolver(name -> "TS".equals(name) ? ts : null).build();
        ReadValue.Spec spec = ReadValue.spec((Expr.Call) CheckExpressionParser.parse(
                "read_value(TSVAL, domain=TS, filter=(TSPARMCD == \"SPECIES\"), mode=\"ONLY\")"));
        ReadValueAmbiguityException ex = assertThrows(ReadValueAmbiguityException.class,
                () -> spec.evaluate(EvalRun.fullRange(ctx)));
        assertTrue(
                ex.getMessage().startsWith(
                        "read_value(TSVAL, domain=TS, mode=\"ONLY\") matched 2 rows of TS"),
                () -> "not the ambiguity this test constructed: " + ex.getMessage());

        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("TEST-READ-VALUE-1")
                .violations(List.of()).totalRows(1L).status(RuleExecutionStatus.ERROR)
                .statusMessage(String.valueOf(ex.getMessage())).build();
        assertEquals(List.of(ViolationNormaliser.ErrorReason.READ_VALUE_AMBIGUOUS.name()),
                ViolationNormaliser.reasonsFrom(result, null),
                () -> "the engine's ONLY-ambiguity wording no longer matches the classifier's suffix;"
                        + " it would degrade every such ERROR to OTHER. Engine said: "
                        + ex.getMessage());
    }


    @Test
    void anUnrelatedReadValueErrorDoesNotClassifyAsReadValueAmbiguous()
    {
        // The control: the suffix match is a classification of the ambiguity, not of ERROR itself.
        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("TEST-READ-VALUE-2")
                .violations(List.of()).totalRows(1L).status(RuleExecutionStatus.ERROR)
                .statusMessage("read_value(TSVAL, domain=TS, mode=\"ONLY\") matched something else")
                .build();
        assertEquals(List.of(ViolationNormaliser.ErrorReason.OTHER.name()),
                ViolationNormaliser.reasonsFrom(result, null));
    }
}
