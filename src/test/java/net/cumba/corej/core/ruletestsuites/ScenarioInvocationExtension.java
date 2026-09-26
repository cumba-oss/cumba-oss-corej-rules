package net.cumba.corej.core.ruletestsuites;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioCapture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Hands JUnit's invocation index of a test-template invocation ({@code @ParameterizedTest},
 * {@code @RepeatedTest}) to {@link ScenarioCapture}, which names a scenario captured by such a
 * method {@code …_<index>}. Registered on the rule-test suites; capture mode
 * ({@code -Dgenerate.scenarios=true}) refuses a test-template capture without it.
 *
 * <p>
 * The index is JUnit's own (the {@code [test-template-invocation:#N]} segment of the unique id),
 * not a count of captures, so a skipped or filtered invocation cannot renumber the others
 * (PLAN-dead-code-followups, review round 2). {@code ScenarioCapture} lives in a module that keeps
 * JUnit off its main class path, which is why this extension lives beside the suites.
 * </p>
 */
public final class ScenarioInvocationExtension implements BeforeEachCallback, AfterEachCallback
{

    private static final Pattern INVOCATION = Pattern
            .compile("\\[test-template-invocation:#(\\d+)]$");

    @Override
    public void beforeEach(ExtensionContext aContext)
    {
        ScenarioCapture.setInvocationIndex(invocationIndex(aContext.getUniqueId()));
    }


    @Override
    public void afterEach(ExtensionContext aContext)
    {
        ScenarioCapture.setInvocationIndex(null);
    }


    /** JUnit's 1-based invocation index in {@code aUniqueId}, or {@code null} for a plain test. */
    static @Nullable Integer invocationIndex(String aUniqueId)
    {
        Matcher m = INVOCATION.matcher(aUniqueId);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }
}
