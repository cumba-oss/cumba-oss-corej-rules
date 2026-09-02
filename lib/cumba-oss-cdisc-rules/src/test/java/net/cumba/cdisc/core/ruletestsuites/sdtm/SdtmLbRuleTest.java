package net.cumba.cdisc.core.ruletestsuites.sdtm;

import org.junit.jupiter.api.Test;

/**
 * Tests for SDTM-IG 3.4 conformance rules — LB domain/category.
 *
 * <p>
 * Most LB rule-test scenarios have been migrated to extended-CDT files under
 * {@code src/test/resources/net/cumba/cdisc/core/ruletestsuites/core/CORE-*\/} and are executed by
 * {@code RuleTestSuitesCoreFactoryTest}. Methods below remain in Java because the test data
 * (non-numeric strings such as {@code "VALUE"} deliberately stored in {@code type=Num} columns)
 * cannot round-trip through CDT.
 * </p>
 */
class SdtmLbRuleTest extends AbstractSdtmRuleTest // NOSONAR S3577 — Underscore-categorised name is
                                                  // the suite convention.
{

    // CORE-000298: Non-numeric "VALUE" in Num column LBSTNRLO — cannot round-trip.

    @Test
    void CORE_000298_invalid()
    {
        var table = testTable("LB");
        table.setValue(0, "LBSTNRLO", "VALUE");
        table.setValue(0, "LBORRES", "VALUE");
        assertViolation("CORE-000298", table, "LB");
    }

    // CORE-000299: Non-numeric "VALUE" in Num column LBSTNRHI — cannot round-trip.


    @Test
    void CORE_000299_invalid()
    {
        var table = testTable("LB");
        table.setValue(0, "LBSTNRHI", "VALUE");
        table.setValue(0, "LBORRES", "VALUE");
        assertViolation("CORE-000299", table, "LB");
    }

}
