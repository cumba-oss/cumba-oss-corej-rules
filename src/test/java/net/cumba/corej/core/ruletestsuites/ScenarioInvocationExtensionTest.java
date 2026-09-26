package net.cumba.corej.core.ruletestsuites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The invocation index {@link ScenarioInvocationExtension} reads from a JUnit unique id. JUnit's
 * live id format is exercised end to end by capture mode itself (a {@code @ParameterizedTest} suite
 * method captures {@code _1}, {@code _2}, {@code _3}) and by {@code ScenarioCaptureFamilyTest} in
 * {@code cumba-corej-ruletest}.
 */
class ScenarioInvocationExtensionTest
{

    @Test
    void aPlainTestHasNoIndex()
    {
        assertNull(ScenarioInvocationExtension.invocationIndex(
                "[engine:junit-jupiter]/[class:a.B]/[method:CDISC_CG0001_valid()]"));
    }


    @Test
    void aTemplateInvocationCarriesItsIndex()
    {
        assertEquals(12,
                ScenarioInvocationExtension.invocationIndex(
                        "[engine:junit-jupiter]/[class:a.B]/[test-template:m(java.lang.String)]"
                                + "/[test-template-invocation:#12]"));
    }
}
