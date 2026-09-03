package net.cumba.corej.core.ruletestsuites;

/**
 * Replays the CDISC-family {@code .cdt} rule-test scenarios ({@code cdisc/} subtree, historically
 * the ADaM {@code CDISC-AD*} suite) against the merged {@code rules-cdisc-*.json} packages.
 */
class RuleTestSuitesCdiscFactoryTest extends AbstractRuleTestSuitesFactory
{

    @Override
    String family()
    {
        return "cdisc";
    }
}
