package net.cumba.corej.core.ruletestsuites.sdtm;

import org.junit.jupiter.api.Test;

/**
 * Tests for SDTM-IG 3.4 conformance rules — ValueMetadata category.
 *
 * <p>
 * Most scenarios have been migrated to extended-CDT files under
 * {@code src/test/resources/net/cumba/corej/core/ruletestsuites/<family>/} and are executed by the
 * per-family {@code RuleTestSuites*FactoryTest} factories. The {@code FDA_SD1021_invalid} case
 * remains in Java: a leading space in a {@code type=Char} cell doesn't survive {@code CdtWriter}'s
 * unquoted-field whitespace stripping, so the exact value cannot round-trip today.
 * </p>
 *
 * <p>
 * ⚑ It asserted {@code CORE-000867} until phase 6 of {@code PLAN-retire-core-family}. That rule had
 * <b>no twin</b> — it was one of {@code D2}'s three drops — so §12.2a left the disposition open
 * rather than naming one. Re-pointed here at {@code FDA-SD1021}, whose Check is a measured
 * <em>strict superset</em> (leading space, carriage return, <em>and</em> a value of {@code '.'}),
 * scoped {@code Domains.Include [ALL]} and published for SDTMIG 3.4: the fixture's {@code " DM"}
 * satisfies the leading-space arm, so the <b>engine</b> coverage this method exists for — that
 * {@code CdtWriter} cannot round-trip such a value, which is why it is Java-resident at all — is
 * preserved rather than deleted.
 * </p>
 */
class SdtmValueMetadataRuleTest extends AbstractSdtmRuleTest // NOSONAR S3577 —
                                                             // Underscore-categorised name is the
                                                             // suite convention.
{

    // FDA-SD1021: leading-space "value" cannot round-trip through CdtWriter.

    @Test
    void FDA_SD1021_invalid()
    {
        var table = testTable("DM");
        table.setValue(0, "DOMAIN", " DM");
        assertViolation("FDA-SD1021", table, "DM");
    }

}
