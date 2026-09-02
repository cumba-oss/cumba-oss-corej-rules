package net.cumba.cdisc.core.ruletestsuites.sdtm;

import org.junit.jupiter.api.Test;

/**
 * Tests for SDTM-IG 3.4 conformance rules — TA domain/category.
 *
 * <p>
 * Most TA rule-test scenarios have been migrated to extended-CDT files under
 * {@code src/test/resources/net/cumba/cdisc/core/ruletestsuites/core/CORE-*\/} and are executed by
 * {@code RuleTestSuitesCoreFactoryTest}. Methods below remain in Java because their data cannot
 * round-trip through the CDT format (e.g. a non-numeric string deliberately stuffed into a
 * {@code type=Num} column to trigger a pattern-mismatch rule).
 * </p>
 */
class SdtmTaRuleTest extends AbstractSdtmRuleTest // NOSONAR S3577 — Underscore-categorised name is
                                                  // the suite convention.
{

    // CORE-000534: TAETORD must be an integer — "INVALID_PATTERN" in a Num column
    // cannot round-trip through CdtWriter/CdtLoader, so this stays in Java.
    //
    // D-TA-1b (twin-alignment, 2026-08-17; aligned to FDA-SD1267's r2 T5 shape): the Check
    // gained the populated-value guard `not empty(TAETORD)`. This case still FIRES — the
    // non-numeric string set into the Num column is a populated VALUE, not an
    // invalid/missing fold, so `not empty(TAETORD)` passes and the integer regex rejects it
    // (measured: the pre-guard and post-guard verdicts are identical here). What the guard
    // exempts is a BLANK cell, which the CDT fixtures pin (blank TAETORD rows in
    // CORE-000534-{invalid,valid}-TA.cdt are silent).

    @Test
    void CORE_000534_invalid()
    {
        var table = testTable("TA");
        table.setValue(0, "TAETORD", "INVALID_PATTERN");
        assertViolation("CORE-000534", table, "TA");
    }

}
