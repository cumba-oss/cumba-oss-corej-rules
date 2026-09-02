package net.cumba.cdisc.core.ruletestsuites.sdtm;

import org.junit.jupiter.api.Test;

/**
 * Tests for SDTM-IG 3.4 conformance rules — ValueMetadata category.
 *
 * <p>
 * Most scenarios have been migrated to extended-CDT files under
 * {@code src/test/resources/net/cumba/cdisc/core/ruletestsuites/core/CORE-*\/} and are executed by
 * {@code RuleTestSuitesCoreFactoryTest}. The {@code CORE_000867_invalid} case remains in Java: a
 * leading space in a {@code type=Char} cell doesn't survive {@code CdtWriter}'s unquoted-field
 * whitespace stripping, so the exact value cannot round-trip today.
 * </p>
 */
class SdtmValueMetadataRuleTest extends AbstractSdtmRuleTest // NOSONAR S3577 —
                                                             // Underscore-categorised name is the
                                                             // suite convention.
{

    // CORE-000867: leading-space "value" cannot round-trip through CdtWriter.

    @Test
    void CORE_000867_invalid()
    {
        var table = testTable("DM");
        table.setValue(0, "DOMAIN", " DM");
        assertViolation("CORE-000867", table, "DM");
    }

}
