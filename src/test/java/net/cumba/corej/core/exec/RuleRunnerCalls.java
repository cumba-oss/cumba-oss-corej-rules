package net.cumba.corej.core.exec;

import java.util.Set;
import net.cumba.corej.core.metadata.VlmResolver;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.dictionary.house.RuntimeDictionaryProvider;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * Test shorthand for {@link RuleRunner}: the convenience overloads the corpus tests call,
 * forwarding to the one production entry point with the defaults the removed overloads used to
 * supply. ⚑ A copy of the engine repository's test-tree class of the same name: this repository
 * consumes the engine as released jars and deliberately carries no test-jar dependency, so the
 * shorthand lives here too.
 *
 * <p>
 * ⭐ {@code PLAN-retire-dead-multi-match-lookup} U1 (A1) and K5 removed {@code RuleRunner}'s
 * telescoping {@code execute} chain (2 … 13 arguments) from {@code src/main}: no production code
 * called any of them — {@code LibraryValidator.executeRule} calls the terminal 14-argument
 * {@code execute}. They lived on only as test conveniences, which is what this class now is:
 * <b>test infrastructure</b>, in the test tree, forwarding the same defaults
 * ({@code Integer.MAX_VALUE} findings, no caches, no providers, empty coverage sets,
 * {@link EngineLimits#DEFAULT_SEVERITY_THRESHOLD}). A test that needs a different value passes it
 * to the full form.
 * </p>
 *
 * <p>
 * ⛔ Keep every method here a pure forwarder. The engine's behaviour is defined by the production
 * signatures; this class must never hold a default the product does not.
 * </p>
 */
public final class RuleRunnerCalls
{

    private RuleRunnerCalls()
    {
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table)
    {
        return execute(rule, table, _ -> null);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver)
    {
        return execute(rule, table, resolver, null);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix)
    {
        return execute(rule, table, resolver, domainPrefix, (MetadataProvider) null);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, null);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache, null);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, Integer.MAX_VALUE);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, null);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, null);
    }


    /** Unlimited findings, no caches — the VLM convenience the former 8-argument overload was. */
    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            @Nullable VlmResolver vlmResolver)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, Integer.MAX_VALUE, null, null, vlmResolver);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache,
            @Nullable RuntimeDictionaryProvider dictionaryProvider)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, dictionaryProvider, null);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache,
            @Nullable RuntimeDictionaryProvider dictionaryProvider,
            @Nullable VlmResolver vlmResolver)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, dictionaryProvider, vlmResolver,
                Set.of());
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache,
            @Nullable RuntimeDictionaryProvider dictionaryProvider,
            @Nullable VlmResolver vlmResolver, Set<String> reportedDatasets)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, dictionaryProvider, vlmResolver,
                reportedDatasets, Set.of());
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache,
            @Nullable RuntimeDictionaryProvider dictionaryProvider,
            @Nullable VlmResolver vlmResolver, Set<String> reportedDatasets,
            Set<String> crossStandardDatasets)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, dictionaryProvider, vlmResolver,
                reportedDatasets, crossStandardDatasets, EngineLimits.DEFAULT_SEVERITY_THRESHOLD);
    }


    /** The production entry point, passed through so every test call reads the same. */
    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache,
            @Nullable RuntimeDictionaryProvider dictionaryProvider,
            @Nullable VlmResolver vlmResolver, Set<String> reportedDatasets,
            Set<String> crossStandardDatasets, Severity severityThreshold)
    {
        return RuleRunner.execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, dictionaryProvider, vlmResolver,
                reportedDatasets, crossStandardDatasets, severityThreshold);
    }
}
