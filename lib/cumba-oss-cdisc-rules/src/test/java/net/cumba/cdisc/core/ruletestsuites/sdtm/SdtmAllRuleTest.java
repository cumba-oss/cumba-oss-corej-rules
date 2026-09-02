package net.cumba.cdisc.core.ruletestsuites.sdtm;

import org.junit.jupiter.api.Test;

/**
 * Tests for SDTM-IG 3.4 conformance rules — ALL category (mixed domains).
 *
 * <p>
 * Most of the ~290 original test methods in this class have been migrated to extended-CDT files
 * under {@code src/test/resources/net/cumba/cdisc/core/ruletestsuites/core/CORE-*\/} and are
 * executed by {@code RuleTestSuitesCoreFactoryTest}. The methods that remain below fall into two
 * buckets:
 * </p>
 *
 * <ul>
 * <li><b>Round-trip-incompatible data</b> — the test writes a non-numeric literal (e.g.
 * {@code "VALUE"}, {@code "WRONG_VALUE"}, {@code "Y"}) into a {@code type=Num} column to trigger a
 * pattern-mismatch rule. {@code CdtWriter} emits the string and {@code CdtLoader} rejects it when
 * re-parsing, so these scenarios cannot be expressed as a plain CDT file today.</li>
 * <li><b>Pre-existing engine bugs</b> — the test fails under the rule engine on master and
 * therefore never reached the capture hook. Kept in Java so the failing cases stay visible (same
 * status as before the migration).</li>
 * </ul>
 */
class SdtmAllRuleTest extends AbstractSdtmRuleTest // NOSONAR S3577 — Underscore-categorised name
                                                   // is the suite convention.
{

    @Test
    void CORE_000107_invalid()
    {
        var table = testTable("AE");
        table.removeColumn("APID");
        table.removeColumn("USUBJID");
        table.removeColumn("SPDEVID");
        table.removeColumn("POOLID");
        table.removeColumn("SPTOBID");
        table.removeColumn("STOCONID");
        assertViolation("CORE-000107", table, "AE");
    }


    @Test
    void CORE_000207_valid()
    {
        var table = testTable("AE");
        table.removeColumn("AESTDTC");
        assertNoViolation("CORE-000207", table, "AE");
    }


    // Non-numeric "VALUE" in Num column AEPTCD — cannot round-trip.
    @Test
    void CORE_000267_invalid()
    {
        var table = testTable("AE");
        table.setValue(0, "AEPTCD", "VALUE");
        table.setValue(0, "AEDECOD", null);
        assertViolation("CORE-000267", table, "AE");
    }


    // Non-numeric "WRONG_VALUE" in Num column AESOCCD — cannot round-trip.
    @Test
    void CORE_000362_invalid()
    {
        var table = testTable("AE");
        table.setValue(0, "AESOCCD", "WRONG_VALUE");
        assertViolation("CORE-000362", table, "AE");
    }


    // Non-numeric "Y" in Num column AESEQ — cannot round-trip.
    @Test
    void CORE_000544_invalid()
    {
        var table = testTable("AE");
        table.setValue(0, "AESEQ", "Y");
        table.setValue(0, "DOMAIN", "GRP1");
        table.setValue(0, "USUBJID", "GRP1");
        table.setValue(1, "AESEQ", "Y");
        table.setValue(1, "DOMAIN", "GRP1");
        table.setValue(1, "USUBJID", "GRP1");
        assertViolation("CORE-000544", table, "AE");
    }


    // Uses virtualTable (programmatic construction beyond the migratable shapes).
    @Test
    void CORE_000699_invalid()
    {
        // LBSTRESU inconsistent across (LBTESTCD, LBCAT, LBSCAT, LBSPEC, LBMETHOD, LBSTAT).
        var table = virtualTable("LB", 2, "STUDYID", "DOMAIN", "USUBJID", "LBSEQ", "LBTESTCD",
                "LBTEST", "LBCAT", "LBSCAT", "LBORRES", "LBSTRESC", "LBSTRESU", "LBSPEC",
                "LBMETHOD", "LBSTAT");
        for (int r = 0; r < 2; r++)
        {
            table.setValue(r, "LBTESTCD", "ALB");
            table.setValue(r, "LBCAT", "CHEMISTRY");
            table.setValue(r, "LBSCAT", "GENERAL");
            table.setValue(r, "LBSPEC", "SERUM");
            table.setValue(r, "LBMETHOD", "ENZYMATIC");
            table.setValue(r, "LBSTAT", "");
        }
        table.setValue(0, "LBSTRESU", "g/L");
        table.setValue(1, "LBSTRESU", "mg/dL");
        assertViolation("CORE-000699", table, "LB");
    }


    // Non-numeric "VALUE" in Num column LBSTRESN — cannot round-trip.
    @Test
    void CORE_000732_invalid()
    {
        var table = testTable("LB");
        table.setValue(0, "LBSTRESC", "INVALID!@#PATTERN");
        table.setValue(0, "LBSTRESN", "VALUE");
        assertViolation("CORE-000732", table, "LB");
    }


    // Shape B against a plain CDT fixture; the captured scenario loses cross-dataset
    // context (rule needs DM in the resolver), so it stays in Java.
    @Test
    void CORE_000793_invalid()
    {
        assertViolationCdt("CORE-000793", "CORE-000793/CORE-000793-invalid-AE.cdt");
    }


    @Test
    void CORE_000862_invalid()
    {
        var table = testTable("AE");
        table.removeColumn("AESTDTC");
        assertViolation("CORE-000862", table, "AE");
    }


    @Test
    void CORE_000892_invalid()
    {
        var table = testTable("AE");
        table.setValue(0, "AEENDTC", "VALUE");
        table.removeColumn("AESTDTC");
        table.setValue(0, "AESTDTC", null);
        table.removeColumn("AESTRF");
        table.setValue(0, "AESTRF", null);
        table.removeColumn("AESTRTPT");
        table.setValue(0, "AESTRTPT", null);
        table.setValue(0, "AEENRF", "VALUE");
        table.removeColumn("AESTDTC");
        table.setValue(0, "AESTDTC", null);
        table.removeColumn("AESTRF");
        table.setValue(0, "AESTRF", null);
        table.removeColumn("AESTRTPT");
        table.setValue(0, "AESTRTPT", null);
        table.setValue(0, "AEENRTPT", "VALUE");
        table.removeColumn("AESTDTC");
        table.setValue(0, "AESTDTC", null);
        table.removeColumn("AESTRF");
        table.setValue(0, "AESTRF", null);
        table.removeColumn("AESTRTPT");
        table.setValue(0, "AESTRTPT", null);
        assertViolation("CORE-000892", table, "AE");
    }

}
