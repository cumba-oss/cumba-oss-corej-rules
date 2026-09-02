package net.cumba.cdisc.core.ruletestsuites.sdtm;

import org.junit.jupiter.api.Test;

/**
 * Tests for SDTM-IG 3.4 conformance rules — DM domain/category.
 *
 * <p>
 * Most DM scenarios have been migrated to extended-CDT files under
 * {@code src/test/resources/net/cumba/cdisc/core/ruletestsuites/core/CORE-*\/} and are executed by
 * {@code RuleTestSuitesCoreFactoryTest}. The one method below remains in Java: the "AGE=VALUE"
 * pattern stuffs a string into a {@code type=Num} column, which cannot round-trip through CDT.
 * </p>
 */
class SdtmDmRuleTest extends AbstractSdtmRuleTest // NOSONAR S3577 — Underscore-categorised name is
                                                  // the suite convention.
{

    // CORE-000189: string in Num column cannot round-trip through CDT.

    @Test
    void CORE_000189_invalid()
    {
        var table = testTable("DM");
        table.setValue(0, "AGE", "VALUE");
        table.setValue(0, "AGEU", null);
        assertViolation("CORE-000189", table, "DM");
    }

}
