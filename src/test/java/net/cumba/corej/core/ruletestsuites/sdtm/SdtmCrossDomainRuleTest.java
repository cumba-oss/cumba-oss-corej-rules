package net.cumba.corej.core.ruletestsuites.sdtm;

import org.junit.jupiter.api.Test;

/**
 * Tests for SDTM-IG 3.4 rules that use {@code Match_Datasets} cross-dataset joins.
 *
 * <p>
 * Most scenarios have been migrated to multi-dataset extended-CDT files via the sibling-aware
 * capture (sibling tables carry rows filtered by the rule's join keys). The methods below remain in
 * Java for one of these reasons:
 * </p>
 *
 * <ul>
 * <li><b>Self-join tautology</b> — the rule queries the primary's own domain via an Operation (e.g.
 * CDISC-CG0409 does {@code distinct(DM.STUDYID)} while the primary is DM with a mutated row). In
 * self-contained scenario the Operation would read the primary itself and the rule evaluates
 * tautologically (it can't fire). Stays in Java where the Java test's overlay vs. delegate
 * asymmetry produces the intended behaviour.</li>
 * <li><b>Non-numeric value in {@code type=Num} column</b> — CdtWriter emits the string and
 * CdtLoader rejects it on reload. Category E of the status doc.</li>
 * </ul>
 */
class SdtmCrossDomainRuleTest extends AbstractSdtmRuleTest // NOSONAR S3577 —
                                                           // Underscore-categorised name is the
                                                           // suite convention.
{

    // CDISC-CG0409: distinct(DM.STUDYID) self-join tautology — primary IS DM.
    @Test
    void CDISC_CG0409_invalid()
    {
        var table = testTable("DM");
        table.setValue(0, "STUDYID", "INVALID_VALUE");
        assertViolation("CDISC-CG0409", table, "DM");
    }


    // CDISC-CG0029: distinct(DM.USUBJID) self-join tautology — primary IS DM.
    @Test
    void CDISC_CG0029_invalid()
    {
        var table = testTable("DM");
        table.setValue(0, "USUBJID", "INVALID_VALUE");
        assertViolation("CDISC-CG0029", table, "DM");
    }


    // CDISC-CG0006: Num column 'DMDY' written with a string literal — cannot round-trip.
    @Test
    void CDISC_CG0006_invalid()
    {
        var table = testTable("DM");
        table.setValue(0, "DMDY", "VALUE");
        assertViolation("CDISC-CG0006", table, "DM");
    }


    // CDISC-CG0370: distinct(RELREC.IDVAR) self-join tautology — primary IS RELREC.
    @Test
    void CDISC_CG0370_valid()
    {
        var table = testTable("RELREC");
        assertNoViolation("CDISC-CG0370", table, "RELREC");
    }


    @Test
    void CDISC_CG0370_invalid()
    {
        var table = testTable("RELREC");
        table.setValue(0, "IDVAR", "INVALID_VALUE");
        assertViolation("CDISC-CG0370", table, "RELREC");
    }


    @Test
    void CDISC_CG0370_invalid_cross_domain_idvar()
    {
        // Fix #11: RELREC row has RDOMAIN=AE but IDVAR=CMGRPID. CMGRPID is a column
        // in CM but NOT in AE, so IDVAR is not contained in AE's variable names →
        // rule fires. With the pre-fix flat-union semantic, CMGRPID would be in the
        // union (from CM) and the rule would erroneously pass.
        var relrec = testTable("RELREC");
        relrec.setValue(0, "RDOMAIN", "AE");
        relrec.setValue(0, "IDVAR", "CMGRPID");
        assertViolation("CDISC-CG0370", relrec, "RELREC");
    }

}
