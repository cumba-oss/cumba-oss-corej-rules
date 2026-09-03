package net.cumba.corej.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RuleFamilyTest
{

    @Test
    void of_takesLeadingAlphaRun()
    {
        assertEquals("CORE", RuleFamily.of("CORE-000351"));
        assertEquals("CORE", RuleFamily.of("CORE-DRAFT-900003"));
        assertEquals("FDA", RuleFamily.of("FDA-SD0001"));
        assertEquals("PMDA", RuleFamily.of("PMDA-AD0001"));
    }


    @Test
    void of_collapsesEverySubPrefixToTheFamilyToken()
    {
        // The CDISC family spans SDTM (CG), SEND, and ADaM (AD) after the ADAMCR->CDISC merge.
        assertEquals("CDISC", RuleFamily.of("CDISC-CG0001"));
        assertEquals("CDISC", RuleFamily.of("CDISC-SEND-0001"));
        assertEquals("CDISC", RuleFamily.of("CDISC-AD0059"));
    }


    @Test
    void of_returnsOtherForNonAlphaLead()
    {
        assertEquals("OTHER", RuleFamily.of("123"));
        assertEquals("OTHER", RuleFamily.of("-X"));
    }


    @Test
    void of_handlesAllLetters()
    {
        assertEquals("ABC", RuleFamily.of("ABC"));
    }


    @Test
    void fileToken_isLowerCased()
    {
        assertEquals("core", RuleFamily.fileToken("CORE-000351"));
        assertEquals("cdisc", RuleFamily.fileToken("CDISC-AD0059"));
        assertEquals("pmda", RuleFamily.fileToken("PMDA-SD0001"));
    }
}
