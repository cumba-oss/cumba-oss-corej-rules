package net.cumba.corej.core.ruletestsuites;

/**
 * Replays the PMDA-family {@code .cdt} rule-test scenarios ({@code pmda/} subtree) against the
 * assembled {@code rules-pmda-*.json} packages.
 *
 * <p>
 * The declaration is <b>mandatory</b> here, as it is for FDA. The CDISC and CORE factories make it
 * optional only to keep their migrated scenarios working; {@code pmda/} is a greenfield subtree
 * with no legacy to accommodate, and PMDA publishes the same rule across four ADaM-IG and five
 * SDTM-IG versions. Without a declaration a scenario falls through
 * {@code DEFAULT_PACKAGE_PREFERENCE} to whichever package happens to sort last — a version nobody
 * chose — and the membership check never runs.
 * </p>
 */
class RuleTestSuitesPmdaFactoryTest extends AbstractRuleTestSuitesFactory
{

    @Override
    String family()
    {
        return "pmda";
    }


    @Override
    boolean requireDeclaredStandard()
    {
        return true;
    }
}
