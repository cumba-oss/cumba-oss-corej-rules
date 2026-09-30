package net.cumba.corej.core.ruletestsuites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.provider.csv.CsvProviderSupplier;
import net.cumba.datatable.provider.csv.CsvTableProvider;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * PLAN-csv-unquoted-dot-missing phase 3 -- the downstream check of owner ruling K7 (2026-09-30):
 * <i>"missing only if it is not in quotation marks."</i>
 * <p>
 * K7 came from a CSV ADSL: a SAS DATA-step export writes a numeric missing as a bare {@code .}, so
 * an all-missing {@code TR02EDT} arrives as a column of {@code .} beside a numeric {@code TR01EDT}.
 * Before K7 the CSV provider loaded those cells as the TEXT {@code "."}, {@code row_max} saw a
 * value-bearing character column beside a numeric one, and CDISC-/PMDA-AD0084 ERRORed with a
 * column-type mismatch instead of answering. Since K7 an unquoted {@code .} is
 * {@link MissingValue#MIS}; a column that holds no value on any row takes no part in
 * {@code row_max}'s type decision, so the numeric {@code TR01EDT} decides and the rule answers.
 * <p>
 * ⚠ Why here and not as a {@code .cdt} scenario: the {@code .cdt} harness carries its data inline
 * ({@code CdtLoader}) and cannot read a CSV file, and the defect is in how the CSV PROVIDER loads
 * the cell -- a {@code .cdt} declaring {@code TR02EDT} {@code Char} with empty cells already exists
 * ({@code *-invalid_all_blank_char_trxxedt_beside_num-ADSL.cdt}) and is green on the old provider.
 * So this runs the SHIPPED corpus rules through the real {@link CsvTableProvider}, which is the one
 * thing the scenario cannot reach.
 * <p>
 * The control arm loads the same file with the {@code .} quoted: K7 makes a quoted {@code "."} the
 * one-character text, which is exactly what the pre-K7 provider made of the UNQUOTED one, so the
 * control reproduces the pre-K7 load in-process and must ERROR. A green main arm beside an ERROR
 * control is what shows the main arm's green is owed to the unquoted {@code .} being missing.
 */
class CsvLoadedAdslAd0084Test
{

    private static final String HEADER = "STUDYID,USUBJID,TRTEDT,TR01EDT,TR02EDT\n";

    private static final int TR02EDT = 4;

    @TempDir
    Path tempDir;

    @ParameterizedTest(name = "{0}")
    @CsvSource(
    {
            "CDISC-AD0084, rules-cdisc-adamig-1-3.json", "PMDA-AD0084, rules-pmda-adamig-1-3.json"
    })
    void anAllUnquotedDotColumnIsMissingSoTheRuleAnswers(String aCoreId, String aPackage)
        throws IOException
    {
        Rule rule = shippedRule(aCoreId, aPackage);
        IDataTable adsl = load("adsl.csv", ".");

        RuleExecutionResult result = run(rule, adsl);

        assertFalse(result.isError(),
                aCoreId + " ERRORed instead of answering: " + result.getStatusMessage());
        assertEquals(1, result.getViolationCount(), aCoreId + " status=" + result.getStatus());
        Violation v = result.getViolations().get(0);
        assertEquals(1L, v.getRowNumber(), "row 1 fires (22000 != 22010), row 2 is clean");
        assertEquals(22_010.0, Double.parseDouble(v.getValues().get("$trxx_max")),
                "the numeric TR01EDT decides the row maximum: " + v.getValues());

        // The representation behind the answer: every TR02EDT cell is the SAS numeric missing.
        for (long r = 0; r < adsl.getRowCount(); r++)
        {
            assertSame(MissingValue.MIS, adsl.getValue(r, TR02EDT), "TR02EDT row " + r);
        }
    }


    /**
     * The control: the same file with the {@code .} quoted. A quoted {@code "."} is text (K7), and
     * text is what the pre-K7 provider made of the unquoted {@code .} -- so this is the pre-K7
     * load, and the rule ERRORs on the column-type mismatch.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
    {
            "CDISC-AD0084, rules-cdisc-adamig-1-3.json", "PMDA-AD0084, rules-pmda-adamig-1-3.json"
    })
    void controlAQuotedDotIsTextAndTheRuleErrors(String aCoreId, String aPackage) throws IOException
    {
        Rule rule = shippedRule(aCoreId, aPackage);
        IDataTable adsl = load("adsl.csv", "\".\"");

        assertSame(DataValueType.STRING, adsl.getMetaData().getColumn(TR02EDT).getType());
        for (long r = 0; r < adsl.getRowCount(); r++)
        {
            assertEquals(".", adsl.getValue(r, TR02EDT), "TR02EDT row " + r);
        }

        RuleExecutionResult result = run(rule, adsl);

        assertTrue(result.isError(),
                "CONTROL FAILED: a text \".\" beside a numeric TRxxEDT no longer ERRORs, so the "
                        + "main arm's green no longer proves the unquoted '.' is missing; status="
                        + result.getStatus());
    }


    private IDataTable load(String aName, String aTr02edtCell) throws IOException
    {
        Path csv = tempDir.resolve(aName);
        // Row 1: TRTEDT 22000 != max(22010, missing) -- fires. Row 2: equal -- clean.
        Files.writeString(csv, HEADER + "S1,01-001,22000,22010," + aTr02edtCell + "\n"
                + "S1,01-002,22010,22010," + aTr02edtCell + "\n", StandardCharsets.UTF_8);
        IDataTable table = new CsvTableProvider().provide(csv.toUri(), CsvProviderSupplier.FI_CSV);
        assertEquals("TR02EDT", table.getMetaData().getColumn(TR02EDT).getName());
        assertSame(DataValueType.DOUBLE, table.getMetaData().getColumn(3).getType(),
                "TR01EDT is numeric");
        assertSame(DataValueType.DOUBLE, table.getMetaData().getColumn(2).getType(),
                "TRTEDT is numeric");
        assertEquals(2L, table.getRowCount());
        return table;
    }


    private static RuleExecutionResult run(Rule aRule, IDataTable aAdsl)
    {
        return RuleRunnerCalls.execute(aRule, aAdsl,
                name -> "ADSL".equalsIgnoreCase(name) ? aAdsl : null, "ADSL");
    }


    private static Rule shippedRule(String aCoreId, String aPackage) throws IOException
    {
        List<Rule> matches = RulePackageLoader.load(CorpusPaths.rules(aPackage)).getRules().values()
                .stream()
                .filter(r -> aCoreId.equals(r.getCore() != null ? r.getCore().getId() : r.getId()))
                .toList();
        assertEquals(1, matches.size(), aCoreId + " in " + aPackage);
        Rule rule = matches.get(0);
        assertNotNull(rule);
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }
}
