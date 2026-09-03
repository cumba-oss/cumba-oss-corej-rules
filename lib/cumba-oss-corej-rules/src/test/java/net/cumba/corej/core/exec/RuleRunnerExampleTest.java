package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Smoke-runs the {@link RuleRunnerExample} entry points to drive coverage. These call into the full
 * {@code RuleRunner} engine against the bundled SDTM-IG rule package and emit results to stdout. We
 * don't assert on contents — only that the public entry points complete without exceptions on a
 * minimal table.
 *
 * <p>
 * The test depends on the surefire working directory pointing at {@code lib/net.cumba.corej.core/}
 * so the relative path {@code rules/rules-sdtmig-3-4.json} resolves to the bundled package.
 * </p>
 */
class RuleRunnerExampleTest
{

    @Test
    void singleTableExample_runsToCompletion()
    {
        IDataTable ae = MockTable.of().col("STUDYID", "STUDY1", "STUDY1")
                .col("USUBJID", "S01", "S02").col("DOMAIN", "AE", "AE").col("AESEQ", "1", "2")
                .col("AETERM", "Headache", "Nausea").name("AE").build();

        // Just exercise the entry point — must complete without throwing.
        assertDoesNotThrow(() -> RuleRunnerExample.singleTableExample(ae));
    }


    @Test
    void allRulesSingleTable_runsToCompletion()
    {
        IDataTable ae = MockTable.of().col("STUDYID", "STUDY1").col("USUBJID", "S01")
                .col("DOMAIN", "AE").col("AESEQ", "1").col("AETERM", "Headache").name("AE").build();

        assertDoesNotThrow(() -> RuleRunnerExample.allRulesSingleTable(ae, "AE"));
    }


    @Test
    void multipleTablesExample_runsToCompletion()
    {
        IDataTable ae = MockTable.of().col("STUDYID", "STUDY1").col("USUBJID", "S01")
                .col("DOMAIN", "AE").col("AESEQ", "1").col("AETERM", "Headache").name("AE").build();
        IDataTable dm = MockTable.of().col("STUDYID", "STUDY1").col("USUBJID", "S01")
                .col("DOMAIN", "DM").col("SEX", "F").name("DM").build();
        IDataTable vs = MockTable.of().col("STUDYID", "STUDY1").col("USUBJID", "S01")
                .col("DOMAIN", "VS").col("VSTESTCD", "HR").col("VSSTRESN", "65").name("VS").build();

        assertDoesNotThrow(() -> RuleRunnerExample.multipleTablesExample(ae, dm, vs));
    }
}
