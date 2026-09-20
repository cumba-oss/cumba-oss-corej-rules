package net.cumba.corej.core.ruletestsuites.sdtm;

import org.junit.jupiter.api.Test;

/**
 * Tests for SDTM-IG 3.4 conformance rules — LB domain/category.
 *
 * <p>
 * Most LB rule-test scenarios have been migrated to extended-CDT files under
 * {@code src/test/resources/net/cumba/corej/core/ruletestsuites/core/CORE-*\/} and are executed by
 * {@code RuleTestSuitesCoreFactoryTest}. Methods below remain in Java because the test data
 * (non-numeric strings such as {@code "VALUE"} deliberately stored in {@code type=Num} columns)
 * cannot round-trip through CDT.
 * </p>
 */
class SdtmLbRuleTest extends AbstractSdtmRuleTest // NOSONAR S3577 — Underscore-categorised name is
                                                  // the suite convention.
{

    // CDISC-CG0182: Non-numeric "VALUE" in Num column LBSTNRLO — cannot round-trip.

    @Test
    void CDISC_CG0182_invalid()
    {
        var table = testTable("LB");
        table.setValue(0, "LBSTNRLO", "VALUE");
        table.setValue(0, "LBORRES", "VALUE");
        assertViolation("CDISC-CG0182", table, "LB");
    }

    // CDISC-CG0183: Non-numeric "VALUE" in Num column LBSTNRHI — cannot round-trip.


    @Test
    void CDISC_CG0183_invalid()
    {
        var table = testTable("LB");
        table.setValue(0, "LBSTNRHI", "VALUE");
        table.setValue(0, "LBORRES", "VALUE");
        assertViolation("CDISC-CG0183", table, "LB");
    }

}
