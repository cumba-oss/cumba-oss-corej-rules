package net.cumba.corej.core.ruletestsuites.sdtm;

import org.junit.jupiter.api.Test;

/**
 * Tests for SDTM-IG 3.4 conformance rules using virtual (synthetic) domains.
 *
 * <p>
 * Only CDISC-CG0370 still lives here: the sibling-aware capture produces a scenario that fires rule
 * even though the Java test considers the same data valid, because the self-contained resolver's
 * inventory differs from the shared study's inventory in a way the CO / RDOMAIN lookup is sensitive
 * to.
 * </p>
 */
class SdtmVirtualDomainRuleTest extends AbstractSdtmRuleTest
{ // NOSONAR S3577 — Underscore-categorised name is the suite convention.

    // CDISC-CG0370: CO IDVAR not a valid variable in RDOMAIN

    @Test
    void CDISC_CG0370_valid()
    {
        assertNoViolationCdtSelfResolver("CDISC-CG0370",
                "cdisc/CDISC-CG0370/CDISC-CG0370-valid-CO.cdt");
    }


    @Test
    void CDISC_CG0370_invalid()
    {
        assertViolationCdtSelfResolver("CDISC-CG0370",
                "cdisc/CDISC-CG0370/CDISC-CG0370-invalid-CO.cdt");
    }

}
