package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import net.cumba.cdisc.core.CorpusPaths;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.expr.convert.RulePackageExpressionJson;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * End-to-end integration tests for the {@code variable_count + name_pattern} Operation extension
 * and the rules that consume it:
 * <ul>
 * <li>CDISC-AD0048 — at least one population flag (*FL) must be present in ADSL.</li>
 * <li>CDISC-AD9001-TRTPRES — at least one treatment-identification variable must be present in any
 * ADaM dataset.</li>
 * </ul>
 *
 * <p>
 * Both rules used to depend on hardcoded {@code RuleGenerator} handlers
 * ({@code generateFlagPresenceCheck}, {@code generateTreatmentPresenceCheck}) that injected
 * synthetic {@code IDX-FLPRES}/{@code IDX-TRTPRES} rules with placeholder {@code USUBJID exists}
 * checks. The handlers were retired 2026-04-28 in favour of these JSON-authored rules.
 * </p>
 */
@Disabled("rules-adamig-1-3-additions.json temporarily moved; @BeforeAll loads it. "
        + "Re-enable when the additions corpus is restored.")
class CdiscAd0048AndTrtpresIntegrationTest
{

    private static final Path RULES_1_3 = CorpusPaths.rules("rules-adamig-1-3.json");

    private static final Path RULES_1_3_ADD = CorpusPaths.rules("rules-adamig-1-3-additions.json");

    private static RulePackage rules13;

    private static RulePackage rules13Add;

    @BeforeAll
    static void loadPackages() throws Exception
    {
        rules13 = RulePackageLoader.loadCombined(RULES_1_3);
        rules13Add = RulePackageLoader.loadCombined(RULES_1_3_ADD);
    }


    private static Rule findByCoreId(RulePackage pkg, String coreId)
    {
        return pkg.getRules().values().stream()
                .filter(r -> r.getCore() != null && coreId.equals(r.getCore().getId())).findFirst()
                .orElseThrow(() -> new AssertionError("Rule not in package: " + coreId));
    }


    private static IDataTable adsl(String... extraCols)
    {
        MockTable mt = MockTable.of().col("STUDYID", "S001").col("USUBJID", "S01");
        mt.name("ADSL");
        for (String c : extraCols)
        {
            mt.col(c, "Y");
        }
        return mt.build();
    }


    private static DatasetResolver self(IDataTable t)
    {
        return name -> name.equals(t.getMetaData().getName()) ? t : null;
    }

    // ---- CDISC-AD0048 ----


    @Test
    void cdiscAd0048_noFlColumn_oneViolation()
    {
        Rule rule = findByCoreId(rules13, "CDISC-AD0048");

        IDataTable adsl = adsl("AGE", "SEX", "RACE");

        RuleExecutionResult result = RuleRunner.execute(rule, adsl, self(adsl));

        assertEquals(1, result.getViolationCount(),
                "ADSL has no *FL column → variable_count=0 → rule fires once");
    }


    @Test
    void cdiscAd0048_oneFlColumn_noViolation()
    {
        Rule rule = findByCoreId(rules13, "CDISC-AD0048");

        IDataTable adsl = adsl("SAFFL");

        RuleExecutionResult result = RuleRunner.execute(rule, adsl, self(adsl));

        assertEquals(0, result.getViolationCount(),
                "ADSL has SAFFL → variable_count=1 → rule does not fire");
    }


    @Test
    void cdiscAd0048_multipleFlColumns_noViolation()
    {
        Rule rule = findByCoreId(rules13, "CDISC-AD0048");

        IDataTable adsl = adsl("SAFFL", "EFFFL", "ITTFL");

        RuleExecutionResult result = RuleRunner.execute(rule, adsl, self(adsl));

        assertEquals(0, result.getViolationCount(),
                "ADSL has multiple *FL columns → variable_count>0 → rule does not fire");
    }


    @Test
    void cdiscAd0048_columnNamedFL_alone_doesNotMatch()
    {
        // The regex is `^.+FL$` — requires at least one character before FL.
        // A column literally named `FL` is not a *suffixed* flag; it's not
        // counted. Confirms the regex anchoring matches the original wildcard
        // (`*FL` → `^(.+)FL$`).
        Rule rule = findByCoreId(rules13, "CDISC-AD0048");

        IDataTable adsl = adsl("FL");

        RuleExecutionResult result = RuleRunner.execute(rule, adsl, self(adsl));

        assertEquals(1, result.getViolationCount(),
                "Bare 'FL' column does not satisfy `^.+FL$` → variable_count=0 → rule fires");
    }

    // ---- CDISC-AD9001-TRTPRES ----


    @Test
    void trtpres_noTreatmentVariable_oneViolation()
    {
        Rule rule = findByCoreId(rules13Add, "CDISC-AD9001-TRTPRES");

        IDataTable adbds = MockTable.of().col("STUDYID", "S001").col("USUBJID", "S01")
                .col("PARAMCD", "PARAM1").name("ADBDS").build();

        RuleExecutionResult result = RuleRunner.execute(rule, adbds, self(adbds));

        assertEquals(1, result.getViolationCount(),
                "No treatment variable + USUBJID present → rule fires");
    }


    @Test
    void trtpres_recordLevelTrtp_noViolation()
    {
        Rule rule = findByCoreId(rules13Add, "CDISC-AD9001-TRTPRES");

        IDataTable adbds = MockTable.of().col("STUDYID", "S001").col("USUBJID", "S01")
                .col("TRTP", "Drug A").name("ADBDS").build();

        RuleExecutionResult result = RuleRunner.execute(rule, adbds, self(adbds));

        assertEquals(0, result.getViolationCount(),
                "TRTP present → treatment_var_count>0 → no violation");
    }


    /**
     * Cases proving that the presence of a treatment-identification variable on ADSL silences
     * CDISC-AD9001-TRTPRES:
     * <ul>
     * <li>{@code TRT01P} — indexed {@code TRTxxP}.</li>
     * <li>{@code TRTPG1} — grouped {@code TRTPGy}.</li>
     * <li>{@code ARM} — ARM counts as a treatment identifier.</li>
     * </ul>
     */
    @ParameterizedTest(name = "trtpres_{0}_noViolation")
    @CsvSource(
    {
            "TRT01P,    Drug A,         TRT01P present → treatment_var_count>0 → no violation",
            "TRTPG1,    Group 1,        TRTPG1 present → treatment_var_count>0 → no violation",
            "ARM,       Treatment Arm A, ARM present → counts as treatment identifier → no violation"
    })
    void trtpres_columnPresent_noViolation(String columnName, String columnValue, String reason)
    {
        Rule rule = findByCoreId(rules13Add, "CDISC-AD9001-TRTPRES");

        IDataTable adsl = MockTable.of().col("STUDYID", "S001").col("USUBJID", "S01")
                .col(columnName, columnValue).name("ADSL").build();

        RuleExecutionResult result = RuleRunner.execute(rule, adsl, self(adsl));

        assertEquals(0, result.getViolationCount(), reason);
    }


    @Test
    void trtpres_columnPrefixedWithTrt_butNotMatching_fires()
    {
        // Verifies the regex anchoring: a sponsor-named column like
        // ``TRTRESP`` (Treatment Response) is NOT a treatment-identification
        // variable per the description and must not silence the rule.
        Rule rule = findByCoreId(rules13Add, "CDISC-AD9001-TRTPRES");

        IDataTable adbds = MockTable.of().col("STUDYID", "S001").col("USUBJID", "S01")
                .col("TRTRESP", "Responder").name("ADBDS").build();

        RuleExecutionResult result = RuleRunner.execute(rule, adbds, self(adbds));

        assertEquals(1, result.getViolationCount(),
                "TRTRESP doesn't match the TRT* identifier patterns → rule fires");
    }


    @Test
    void trtpres_rulePresentInAdditionsPackage()
    {
        // Sanity check: the new rule is loadable from the additions package
        // and carries the expected metadata.
        Rule rule = findByCoreId(rules13Add, "CDISC-AD9001-TRTPRES");
        assertNotNull(rule.getCore());
        assertEquals("CDISC-AD9001-TRTPRES", rule.getCore().getId());
        assertNotNull(rule.getExecutability());
        // The variable_count(name_pattern=…) operation is now inlined into the Check (Form A): the
        // Operations block is dropped and the rule compiles natively with the inline call.
        assertNull(rule.getOperations(), "operation inlined into the Check — no Operations block");
        assertNotNull(rule.getCheckExpr(), "the inlined rule must be native");
        String expr = RulePackageExpressionJson.toExpressionJson(rule);
        assertTrue(expr.contains("variable_count(") && expr.contains("name_pattern"),
                "Check inlines the variable_count(name_pattern=…) operation: " + expr);
    }
}
