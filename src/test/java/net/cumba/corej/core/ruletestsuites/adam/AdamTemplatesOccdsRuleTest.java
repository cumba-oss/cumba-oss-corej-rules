package net.cumba.corej.core.ruletestsuites.adam;

import static org.junit.jupiter.api.Assumptions.*;

import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Phase 5: Template rule tests expanded via DatasetRuleResolver for OCCDS (Occurrence Data
 * Structure).
 * <p>
 * Expands template rules (those whose Check tree carries wildcard variables) against the real ADAE
 * test dataset. Template rules contain wildcard variable names like {@code *FL}, {@code *DT}, etc.
 * that are matched against actual dataset columns by the {@code WildcardExpander}.
 * <p>
 * Scope: OCCURRENCE DATA STRUCTURE + ALL
 */
class AdamTemplatesOccdsRuleTest extends AbstractAdamTemplateTest
{ // NOSONAR S3577 — Underscore-categorised name is the suite convention.

    private static final String DOMAIN = "ADAE";

    private static final String CLASS_NAME = "OCCURRENCE DATA STRUCTURE";

    @BeforeAll
    static void expandTemplateRules()
    {
        expandedRulesForDomain = expandRules(DOMAIN, CLASS_NAME, adamDomains.get(DOMAIN));
    }

    /** Shared static field set in @BeforeAll, copied to instance in @TestFactory. */
    private static java.util.List<net.cumba.corej.core.model.Rule> expandedRulesForDomain;

    @TestFactory
    Stream<DynamicTest> templateTests_OCCDS_valid()
    {
        expandedRules = expandedRulesForDomain;
        assumeFalse(expandedRules.isEmpty(), "No template rules expanded for " + DOMAIN);
        return templateTests_valid(DOMAIN, CLASS_NAME);
    }


    @TestFactory
    Stream<DynamicTest> templateTests_OCCDS_invalid()
    {
        expandedRules = expandedRulesForDomain;
        assumeFalse(expandedRules.isEmpty(), "No template rules expanded for " + DOMAIN);
        return templateTests_invalid(DOMAIN, CLASS_NAME);
    }

}
