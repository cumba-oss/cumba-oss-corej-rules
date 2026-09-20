package net.cumba.corej.core.ruletestsuites.sdtm;

import org.junit.jupiter.api.Test;

/**
 * Tests for SDTM-IG 3.4 conformance rules — ALL category (mixed domains).
 *
 * <p>
 * Most of the ~290 original test methods in this class have been migrated to extended-CDT files
 * under {@code src/test/resources/net/cumba/corej/core/ruletestsuites/<family>/} and are executed
 * by the per-family {@code RuleTestSuites*FactoryTest} factories. The methods that remain below
 * fall into two buckets:
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
    void CDISC_CG0554_C_invalid()
    {
        var table = testTable("AE");
        table.removeColumn("APID");
        table.removeColumn("USUBJID");
        table.removeColumn("SPDEVID");
        table.removeColumn("POOLID");
        table.removeColumn("SPTOBID");
        table.removeColumn("STOCONID");
        // R-5.25 wave-3 split (top-level-or board group X): the non-AP identifier branch this
        // dataset exercises now lives on the minted sibling; the bare id keeps the AP/APID half.
        assertViolation("CDISC-CG0554-C", table, "AE");
    }


    @Test
    void CDISC_CG0467_valid()
    {
        var table = testTable("AE");
        table.removeColumn("AESTDTC");
        assertNoViolation("CDISC-CG0467", table, "AE");
    }


    // Non-numeric "VALUE" in Num column AEPTCD — cannot round-trip.
    @Test
    void CDISC_CG0050_invalid()
    {
        var table = testTable("AE");
        table.setValue(0, "AEPTCD", "VALUE");
        table.setValue(0, "AEDECOD", null);
        assertViolation("CDISC-CG0050", table, "AE");
    }


    // Non-numeric "WRONG_VALUE" in Num column AESOCCD — cannot round-trip.
    //
    // ⭐⭐ THE SECOND setValue IS NEW (2026-09-19, PLAN-retire-core-family phase 6) AND IT IS THE
    // WHOLE POINT OF THE RE-POINT. This assertion named CORE-000362, whose Check was the bare
    // `--SOCCD != --BDSYCD`. Its twin CDISC-CG0037 authors
    // `not empty(--SOCCD) and not empty(--BDSYCD) and --SOCCD != --BDSYCD` and is graded
    // "Partially Executable - Possible Overreporting" for exactly that reason. Measured: row 0 of
    // the trimmed AE fixture carries AEBDSYCD = null, so the CORE rule FIRED on a missing operand
    // — the over-reporting §8.1 of that plan identified — and the twin correctly stays SILENT.
    // ⇒ Re-pointing the id alone made this test red, and §12.2a predicted it would ("CORE
    // over-reports here, so the assertion's expected verdict may change"). ⛔ The repair is NOT to
    // flip the expectation to noViolation: that would assert the guard and abandon this method's
    // actual subject, which is that a non-numeric literal in a type=Num column cannot round-trip
    // through CdtWriter/CdtLoader and so has to stay in Java. Populating the partner column
    // satisfies the twin's guard, leaves the inequality true, and keeps the round-trip subject.
    @Test
    void CDISC_CG0037_invalid()
    {
        var table = testTable("AE");
        table.setValue(0, "AESOCCD", "WRONG_VALUE");
        table.setValue(0, "AEBDSYCD", "10019211");
        assertViolation("CDISC-CG0037", table, "AE");
    }


    // Non-numeric "Y" in Num column AESEQ — cannot round-trip.
    @Test
    void CDISC_CG0028_invalid()
    {
        var table = testTable("AE");
        table.setValue(0, "AESEQ", "Y");
        table.setValue(0, "DOMAIN", "GRP1");
        table.setValue(0, "USUBJID", "GRP1");
        table.setValue(1, "AESEQ", "Y");
        table.setValue(1, "DOMAIN", "GRP1");
        table.setValue(1, "USUBJID", "GRP1");
        assertViolation("CDISC-CG0028", table, "AE");
    }


    // Uses virtualTable (programmatic construction beyond the migratable shapes).
    @Test
    void FDA_SD0007_invalid()
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
        assertViolation("FDA-SD0007", table, "LB");
    }


    // Non-numeric "VALUE" in Num column LBSTRESN — cannot round-trip.
    @Test
    void FDA_SD1448_invalid()
    {
        var table = testTable("LB");
        table.setValue(0, "LBSTRESC", "INVALID!@#PATTERN");
        table.setValue(0, "LBSTRESN", "VALUE");
        assertViolation("FDA-SD1448", table, "LB");
    }

    // ⛔ REMOVED by PLAN-retire-core-family phase 6 (§12.2a), NOT re-pointed, and the reason is
    // recorded here because the coverage really is lost. The method was CORE_000793_invalid:
    // Shape B against a plain CDT fixture, kept in Java because the captured scenario loses the
    // cross-dataset context the rule needs (DM in the resolver). Its four .cdt fixtures were
    // DROPPED in phase 5 (migration census target "(none)"), and the twin the READ census names
    // -- FDA-SD1084 / PMDA-SD1084 -- carries an extra `not empty(DM.RFSTDTC)` conjunct that this
    // fixture does not control. Re-pointing would therefore either red or, worse, pass for the
    // wrong reason, which is exactly why §12.2a flags that read verdict as SUSPECT and refers it
    // back to a §8 read. Failed test: not-CLEAR.


    @Test
    void FDA_SD2271_invalid()
    {
        var table = testTable("AE");
        table.removeColumn("AESTDTC");
        assertViolation("FDA-SD2271", table, "AE");
    }


    @Test
    void FDA_SD0031_invalid()
    {
        var table = testTable("AE");
        // The start columns stay PRESENT (null-valued on row 0): since 2026-09-11 the rule gates
        // on Requirements.Variables.Any (R-4.11b, verdict 2-1), and since PLAN-any-variable-sets
        // phase 6 (2026-09-18) that Any is GROUPED — [[--STDTC, --STRF, --STRTPT],
        // [--ENDTC, --ENRF, --ENRTPT]], at least one member of EACH group. The end columns this
        // fixture populates therefore satisfy group 2, and only group 1 is at issue here: a
        // fixture that REMOVED the start columns no longer exercises the Check — it lands in
        // the gate's auditable skip, which is the .cdt suite's skipped_no_start_columns scenario
        // (its group-2 counterpart is skipped_no_end_columns), and the absence itself is reported
        // once by the minted sister FDA-SD9721.
        table.setValue(0, "AEENDTC", "VALUE");
        table.setValue(0, "AEENRF", "VALUE");
        table.setValue(0, "AEENRTPT", "VALUE");
        table.setValue(0, "AESTDTC", null);
        table.setValue(0, "AESTRF", null);
        table.setValue(0, "AESTRTPT", null);
        assertViolation("FDA-SD0031", table, "AE");
    }

}
