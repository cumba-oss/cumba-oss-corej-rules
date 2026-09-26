package net.cumba.corej.core.ruletestsuites.sdtm;

import org.junit.jupiter.api.Test;

/**
 * Tests for SDTM-IG 3.4 conformance rules — DM domain/category.
 *
 * <p>
 * Most DM scenarios have been migrated to extended-CDT files under
 * {@code src/test/resources/net/cumba/corej/core/ruletestsuites/core/CORE-*\/} and were executed by
 * {@code RuleTestSuitesCoreFactoryTest} (⚑ both deleted with the CORE family in 2026-09; since
 * PLAN-dead-code-followups a capture from this suite writes into the captured rule's own family
 * directory, {@code cdisc/}, {@code fda/}, {@code pmda/} or {@code draft/}). The one method below
 * remains in Java: the "AGE=VALUE" pattern stuffs a string into a {@code type=Num} column, which
 * cannot round-trip through CDT.
 * </p>
 */
class SdtmDmRuleTest extends AbstractSdtmRuleTest // NOSONAR S3577 — Underscore-categorised name is
                                                  // the suite convention.
{

    // CDISC-CG0665: string in Num column cannot round-trip through CDT.

    @Test
    void CDISC_CG0665_invalid()
    {
        var table = testTable("DM");
        table.setValue(0, "AGE", "VALUE");
        table.setValue(0, "AGEU", null);
        assertViolation("CDISC-CG0665", table, "DM");
    }

}
