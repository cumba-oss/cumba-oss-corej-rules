package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.rulespec.ViolationNormaliser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link ViolationNormaliser.ErrorReason#MERGE_ERROR} and
 * {@link ViolationNormaliser.ErrorReason#MERGE_REFUSED} reachable with the <b>engine's own</b>
 * messages ({@code PLAN-merged-evaluation-table} W3a, OD-7 / MET #15): a rule reading a
 * supplemental qualifier two {@code SUPPAE} rows qualify for one record ERRORs through
 * {@code RuleRunner.mergeErrorGate}, and a SUPP-named keyed entry whose key names no column on
 * either side is refused by the datatable while the merged view is built. A reworded engine head
 * reds here instead of degrading every such ERROR to {@code OTHER} in silence.
 */
class MergeErrorReasonReachabilityTest
{

    private static Rule load(String ruleJson)
    {
        try
        {
            Rule rule = RulePackageLoader.loadFromString("{\"rules\":{\"X-1\":" + ruleJson + "}}")
                    .getRules().get("X-1");
            assertNull(rule.getLoadError(), "fixture must load: " + rule.getLoadError());
            return rule;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + ruleJson, e);
        }
    }


    private static DatasetResolver resolver(IDataTable... tables)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                for (IDataTable t : tables)
                {
                    if (t.getMetaData().getName().equalsIgnoreCase(name))
                    {
                        return t;
                    }
                }
                return null;
            }


            @Override
            public Set<String> availableDatasets()
            {
                Set<String> out = new java.util.LinkedHashSet<>();
                for (IDataTable t : tables)
                {
                    out.add(t.getMetaData().getName());
                }
                return out;
            }
        };
    }


    private static RuleExecutionResult run(Rule rule, IDataTable table, DatasetResolver resolver)
    {
        return RuleRunner.execute(rule, table, resolver, "AE", null, null, null, 50, null, null,
                null, Set.of(), Set.of(), Severity.INFO);
    }


    /** A real character table, row-major cells. */
    private static IDataTable table(String name, List<String> columns, String... cells)
    {
        int rows = cells.length / columns.size();
        OverlayDataTable t = OverlayDataTable.empty(name, name, rows);
        for (String column : columns)
        {
            t.addColumn(column, DataValueType.STRING, column);
        }
        for (int r = 0; r < rows; r++)
        {
            for (int c = 0; c < columns.size(); c++)
            {
                t.setValue(r, c, cells[r * columns.size() + c]);
            }
        }
        return t;
    }

    private static final List<String> SUPP_COLUMNS = List.of("STUDYID", "RDOMAIN", "USUBJID",
            "IDVAR", "IDVARVAL", "QNAM", "QVAL");

    /** AE: one record (01, AESEQ 1). */
    private static IDataTable ae()
    {
        return table("AE", List.of("STUDYID", "DOMAIN", "USUBJID", "AESEQ"), "S1", "AE", "01", "1");
    }


    @Test
    void aFlaggedQualifierReadClassifiesAsMergeError()
    {
        // §3.5.3 case 2: two SUPPAE rows (AESEQ=1, AETRTEM) qualify AE's one record.
        IDataTable supp = table("SUPPAE", SUPP_COLUMNS, "S1", "AE", "01", "AESEQ", "1", "AETRTEM",
                "Y", "S1", "AE", "01", "AESEQ", "1", "AETRTEM", "N");
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Check\":{\"expression\":\"AETRTEM == \\\"Y\\\"\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AETRTEM\"]}}");
        RuleExecutionResult result = run(rule, ae(), resolver(ae(), supp));
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus(), result.getStatusMessage());
        assertTrue(
                result.getStatusMessage().contains("AETRTEM") && result.getStatusMessage()
                        .contains("more than one row qualifies one record"),
                () -> "not the finding this test constructed: " + result.getStatusMessage());
        assertEquals(List.of(ViolationNormaliser.ErrorReason.MERGE_ERROR.name()),
                ViolationNormaliser.reasonsFrom(result, rule),
                () -> "the engine's MERGE_ERROR wording no longer matches the classifier's head; it "
                        + "would degrade every such ERROR to OTHER. Engine said: "
                        + result.getStatusMessage());
    }


    /**
     * W3d (MQ7): the read-time backstop — a {@code colref} over the flagged qualifier reaches
     * {@code MergeErrorReadException} (the static gate sees no reference) and classifies as
     * {@code MERGE_ERROR} through the same prefix. RED before W3d: {@code colref} read no qualifier
     * and the rule EXECUTED clean over the duplicate.
     */
    @Test
    void aFlaggedQualifierReadThroughColrefClassifiesAsMergeError()
    {
        IDataTable supp = table("SUPPAE", SUPP_COLUMNS, "S1", "AE", "01", "AESEQ", "1", "AETRTEM",
                "Y", "S1", "AE", "01", "AESEQ", "1", "AETRTEM", "N");
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Check\":{\"expression\":\"colref(\\\"AETRTEM\\\") == \\\"Y\\\"\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]}}");
        RuleExecutionResult result = run(rule, ae(), resolver(ae(), supp));
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus(), result.getStatusMessage());
        assertTrue(result.getStatusMessage().contains("AETRTEM"), result.getStatusMessage());
        assertEquals(List.of(ViolationNormaliser.ErrorReason.MERGE_ERROR.name()),
                ViolationNormaliser.reasonsFrom(result, rule), result.getStatusMessage());
    }


    @Test
    void aMergeTheDatatableRefusesClassifiesAsMergeRefused()
    {
        // P-7: a split dataset (QS36) is fed its own SUPPQS36 and its domain's SUPPQS, stacked as
        // one supplemental table; when their QVAL types disagree the datatable cannot read them
        // as one and refuses the merge while the view is built - the rule's ERROR under the
        // refused-merge head.
        IDataTable qs36 = table("QS36", List.of("STUDYID", "DOMAIN", "USUBJID", "QSSEQ"), "S1",
                "QS", "01", "1");
        IDataTable suppQs36 = table("SUPPQS36", SUPP_COLUMNS, "S1", "QS", "01", "QSSEQ", "1",
                "QSLANG", "ENG");
        OverlayDataTable suppQs = OverlayDataTable.empty("SUPPQS", "SUPPQS", 1);
        for (String column : SUPP_COLUMNS)
        {
            suppQs.addColumn(column,
                    "QVAL".equals(column) ? DataValueType.DOUBLE : DataValueType.STRING, column);
        }
        String[] cells =
        {
                "S1", "QS", "01", "QSSEQ", "1", "QSEVAL"
        };
        for (int c = 0; c < cells.length; c++)
        {
            suppQs.setValue(0, c, cells[c]);
        }
        suppQs.setValue(0, cells.length, 2.5d);
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Check\":{\"expression\":\"QSLANG == \\\"ENG\\\"\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"QSLANG\"]}}");
        RuleExecutionResult result = RuleRunner.execute(rule, qs36,
                resolver(qs36, suppQs36, suppQs), "QS", null, null, null, 50, null, null, null,
                Set.of(), Set.of(), Severity.INFO);
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus(), result.getStatusMessage());
        assertTrue(result.getStatusMessage().contains("cannot be read as one"),
                () -> "not the finding this test constructed: " + result.getStatusMessage());
        assertEquals(List.of(ViolationNormaliser.ErrorReason.MERGE_REFUSED.name()),
                ViolationNormaliser.reasonsFrom(result, rule),
                () -> "the engine's refused-merge wording no longer matches the classifier's head. "
                        + "Engine said: " + result.getStatusMessage());
    }


    @Test
    void aCleanQualifierReadHasNoReason()
    {
        IDataTable supp = table("SUPPAE", SUPP_COLUMNS, "S1", "AE", "01", "AESEQ", "1", "AETRTEM",
                "Y");
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Check\":{\"expression\":\"AETRTEM == \\\"Y\\\"\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AETRTEM\"]}}");
        RuleExecutionResult result = run(rule, ae(), resolver(ae(), supp));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolationCount());
        assertEquals(List.of(), ViolationNormaliser.reasonsFrom(result, rule));
    }
}
