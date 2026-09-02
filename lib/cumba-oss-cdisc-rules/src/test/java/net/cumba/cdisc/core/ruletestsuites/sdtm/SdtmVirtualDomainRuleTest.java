package net.cumba.cdisc.core.ruletestsuites.sdtm;

import org.junit.jupiter.api.Test;

/**
 * Tests for SDTM-IG 3.4 conformance rules using virtual (synthetic) domains.
 *
 * <p>
 * Only CORE-000953 still lives here: the sibling-aware capture produces a scenario that fires the
 * rule even though the Java test considers the same data valid, because the self-contained
 * resolver's inventory differs from the shared study's inventory in a way the CO / RDOMAIN lookup
 * is sensitive to.
 * </p>
 */
class SdtmVirtualDomainRuleTest extends AbstractSdtmRuleTest
{ // NOSONAR S3577 — Underscore-categorised name is the suite convention.

    // CORE-000953: CO IDVAR not a valid variable in RDOMAIN

    @Test
    void CORE_000953_valid()
    {
        assertNoViolationCdtSelfResolver("CORE-000953", "CORE-000953/CORE-000953-valid-CO.cdt");
    }


    @Test
    void CORE_000953_invalid()
    {
        assertViolationCdtSelfResolver("CORE-000953", "CORE-000953/CORE-000953-invalid-CO.cdt");
    }

}
