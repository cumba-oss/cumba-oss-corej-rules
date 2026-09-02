package net.cumba.cdisc.core.ruletestsuites;

/**
 * Replays the FDA-family {@code .cdt} rule-test scenarios ({@code fda/} subtree) against the merged
 * {@code rules-fda-*.json} packages. FDA scenarios must declare their standard+version (authoring
 * convention D2 of {@code plans/done/PLAN-FDA-rule-review-and-tests.md}); the factory asserts the
 * rule's membership in the declared package, so a wrong {@code Standards} block in the rule — or a
 * wrong declaration in the scenario — fails the scenario.
 */
class RuleTestSuitesFdaFactoryTest extends AbstractRuleTestSuitesFactory
{

    @Override
    String family()
    {
        return "fda";
    }


    @Override
    boolean requireDeclaredStandard()
    {
        return true;
    }
}
