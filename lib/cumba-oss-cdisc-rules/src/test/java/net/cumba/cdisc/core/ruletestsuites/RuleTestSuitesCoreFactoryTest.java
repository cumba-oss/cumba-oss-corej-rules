package net.cumba.cdisc.core.ruletestsuites;

/**
 * Replays the CORE-family {@code .cdt} rule-test scenarios ({@code core/} subtree, historically the
 * SDTM {@code CORE-*} suite) against the merged {@code rules-core-*.json} packages.
 */
class RuleTestSuitesCoreFactoryTest extends AbstractRuleTestSuitesFactory
{

    @Override
    String family()
    {
        return "core";
    }
}
