/*
 * COPY. The original lives in corej-core's test tree
 * (lib/corej-core/src/test/java/net/cumba/corej/core/expr/eval/
 * CompiledProgramCacheIsolationExtension.java).
 *
 * Copied here on 2026-09-01 by plans/done/PLAN-corej-restructure.md section 4, the same change that
 * deleted this module's <type>test-jar</type> dependency on corej-core. That dependency was how
 * this extension reached this module at all: a test-jar packages target/test-classes, so it carried
 * the two JUnit RESOURCES that register this class (junit-platform.properties and
 * META-INF/services/org.junit.jupiter.api.extension.Extension) as well as the class itself. The pom
 * comment on that deleted dependency enumerated four CLASSES and missed the resources.
 *
 * It stays in package net.cumba.corej.core.expr.eval because
 * NativeExprEvaluator.clearCacheForTesting() is package-private in corej-core's MAIN jar, and this
 * module's test tree already shares that package (see SyntheticDataTable).
 *
 * ACCEPTED COST: like the other two copies, this one can drift from the original.
 */
package net.cumba.corej.core.expr.eval;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Clears {@link NativeExprEvaluator}'s compiled-program cache before every test in this module.
 *
 * <p>
 * ⛔⛔ <b>Do not delete this as dead weight.</b> {@code NativeExprEvaluator} memoises compiled
 * programs in a {@code static} map that lives for the whole JVM, so {@code ExprCompiler.compile}
 * runs <b>at most once per distinct expression per JVM</b>. Surefire shares one JVM across a test
 * class and pitest across a whole minion, so without this extension pitest attributes each compiler
 * line only to the <em>first</em> test that compiled that expression, then runs only those tests
 * against a mutant on that line — and reports the mutant as SURVIVED when they do not detect it.
 * </p>
 *
 * <p>
 * Measured on {@code ExprCompiler} (cold, scoped run, 2026-08-30): <b>370</b> surviving mutants
 * with the cache live, <b>131</b> with it cleared per test. 239 of the reported "survivors" were
 * already killed by tests that existed all along; the mutation score for this class was understated
 * by 20 points. That is why the module's ratchet is meaningful only with this extension in place.
 * </p>
 *
 * <p>
 * The cache is correct and wanted in production — programs are immutable and resolve columns at
 * evaluation time, so sharing them across the cohort fan-out is the point. Only the cross-test
 * leakage is unwanted, which is why this is a test-side extension and not a production toggle. It
 * lives in {@code net.cumba.corej.core.expr.eval} so it can reach the package-private
 * {@code clearCacheForTesting()} hook, and applies module-wide regardless of a test's own package
 * via the ServiceLoader registration in {@code src/test/resources/META-INF/services/} plus
 * {@code junit.jupiter.extensions.autodetection.enabled=true}.
 * </p>
 *
 * <p>
 * ⚠ Note what this does <b>not</b> fix. A separate order dependence survives it:
 * {@code ExprCompilerExistsModesTest.dsExists_datasetPresence_andStructuralNegation} fails against
 * a mutated compiler branch when run alone but passes inside its own class, and it still does so
 * with this extension active. Some other cross-test state is responsible; it has not been
 * identified.
 * </p>
 */
public class CompiledProgramCacheIsolationExtension implements BeforeEachCallback
{

    @Override
    public void beforeEach(ExtensionContext context)
    {
        NativeExprEvaluator.clearCacheForTesting();
    }

}
