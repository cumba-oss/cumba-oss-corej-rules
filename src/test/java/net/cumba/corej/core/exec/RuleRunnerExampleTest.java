package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests for {@link RuleRunnerExample}: the examples run to completion on small real tables
 * (the merged evaluation view of {@code PLAN-merged-evaluation-table} W3a copies column metadata,
 * which a Mockito-backed fixture cannot give).
 */
class RuleRunnerExampleTest
{

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

    private static final List<String> AE_COLUMNS = List.of("STUDYID", "USUBJID", "DOMAIN", "AESEQ",
            "AETERM");

    @Test
    void singleTableExample_runsToCompletion()
    {
        IDataTable ae = table("AE", AE_COLUMNS, "STUDY1", "S01", "AE", "1", "Headache", "STUDY1",
                "S02", "AE", "2", "Nausea");
        assertDoesNotThrow(() -> RuleRunnerExample.singleTableExample(ae));
    }


    @Test
    void allRulesSingleTable_runsToCompletion()
    {
        IDataTable ae = table("AE", AE_COLUMNS, "STUDY1", "S01", "AE", "1", "Headache");
        assertDoesNotThrow(() -> RuleRunnerExample.allRulesSingleTable(ae, "AE"));
    }


    @Test
    void multipleTablesExample_runsToCompletion()
    {
        IDataTable ae = table("AE", AE_COLUMNS, "STUDY1", "S01", "AE", "1", "Headache");
        IDataTable dm = table("DM", List.of("STUDYID", "USUBJID", "DOMAIN", "SEX"), "STUDY1", "S01",
                "DM", "F");
        IDataTable vs = table("VS", List.of("STUDYID", "USUBJID", "DOMAIN", "VSTESTCD", "VSSTRESN"),
                "STUDY1", "S01", "VS", "HR", "65");
        assertDoesNotThrow(() -> RuleRunnerExample.multipleTablesExample(ae, dm, vs));
    }
}
