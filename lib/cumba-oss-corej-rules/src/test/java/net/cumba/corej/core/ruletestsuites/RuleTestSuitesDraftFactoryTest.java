package net.cumba.corej.core.ruletestsuites;

/**
 * Replays the DRAFT-family {@code .cdt} rule-test scenarios ({@code draft/} subtree) against the
 * assembled {@code rules-draft-*.json} packages.
 *
 * <p>
 * ⚠ {@code DRAFT-*} rules are <b>unpublished drafts</b> and ship into the {@code rules-draft-*}
 * packages only — never into a production ({@code cdisc} / {@code core} / {@code fda} /
 * {@code pmda}) package. {@link #family()} returning {@code "draft"} is therefore not just the
 * resource-subtree name: it is what keeps a draft scenario from resolving its rule out of a shipped
 * package. {@code RuleTestSuitesFamilyHomeTest#draftRulesShipOnlyIntoDraftPackages} pins that
 * partition from the corpus side.
 * </p>
 *
 * <p>
 * The declaration is <b>mandatory</b> here, as it is for FDA and PMDA. {@code draft/} is a
 * greenfield subtree with no legacy scenarios to accommodate, and the 20 draft rules are published
 * across up to thirteen standard+version packages ({@code sdtmig} 3-2/3-3/3-4, six {@code sendig}
 * flavours, {@code adamig} 1-2/1-3 and {@code tig} 1-0) whose assembled views differ. Without a
 * declaration a scenario falls through {@code DEFAULT_PACKAGE_PREFERENCE} to whichever package
 * happens to sort last — a version nobody chose — and the membership check never runs.
 * </p>
 */
class RuleTestSuitesDraftFactoryTest extends AbstractRuleTestSuitesFactory
{

    @Override
    String family()
    {
        return "draft";
    }


    @Override
    boolean requireDeclaredStandard()
    {
        return true;
    }
}
