package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Corpus guard for the domain-scope exact-match contract. The SEND {@code RE} (Respiratory) domain
 * is a proper prefix of the RELREC / RELSUB / RELSPEC / RELREF relationship datasets, and SDTM
 * {@code SU} (Substance Use) is a proper prefix of every {@code SUPPxx}. Under the retired Fix #38
 * prefix semantics the rules below were selected for — and CDISC-SEND-0289(-1), CDISC-SEND-0338 and
 * FDA-SE2306 fired on — datasets they were never scoped to. These assertions read the shipped rule
 * bodies so a future scope-matcher change cannot silently reintroduce the false findings.
 *
 * <p>
 * Lives in {@code corej-cdisc-rules} rather than next to {@link ScopeMatcher} in
 * {@code corej-cdisc-core}: the rule corpus ships in this module, and the dependency runs rules
 * -&gt; core, so core cannot reach it.
 * </p>
 */
class ScopeMatcherShippedRuleScopeTest
{

    @ParameterizedTest
    @CsvSource(
    {
            "rules-cdisc-sendig-3-1-1.json, CDISC-SEND-0338, RE",
            "rules-cdisc-sendig-3-1-1.json, CDISC-SEND-0289.1, RE",
            "rules-cdisc-sendig-3-1.json,   CDISC-SEND-0289,   RE",
            "rules-fda-sendig-3-0.json,     FDA-SE2306,        RE"
    })
    void shippedRule_selectsOnlyItsOwnDomain(String pkgFile, String coreId, String domain)
        throws IOException
    {
        Rule rule = load(pkgFile, coreId);
        assertTrue(ScopeMatcher.matchesDomain(rule, domain),
                coreId + " must still apply to " + domain);
        for (String other : new String[]
        {
                "RELREC", "RELSUB", "RELSPEC", "RELREF", "SUPPAE", "SUPPQUAL"
        })
        {
            if (other.equals(domain))
            {
                continue;
            }
            assertFalse(ScopeMatcher.matchesDomain(rule, other),
                    coreId + " must NOT be selected for " + other);
        }
    }


    @Test
    void relrecIsNotASplitOfRE() throws IOException
    {
        // The unsplit-name re-test must not resurrect the collision either: RELREC carries no
        // DOMAIN=RE, so its canonical base is itself.
        Rule rule = load("rules-cdisc-sendig-3-1-1.json", "CDISC-SEND-0338");
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "RELREC", "RELREC"));
    }


    private static Rule load(String pkgFile, String coreId) throws IOException
    {
        RulePackage pkg = RulePackageLoader.load(CorpusPaths.rules(pkgFile));
        Map<String, Rule> rules = pkg.getRules();
        assertNotNull(rules, pkgFile + " has no rules");
        Rule rule = rules.get(coreId);
        assertNotNull(rule, coreId + " not found in " + pkgFile);
        return rule;
    }
}
