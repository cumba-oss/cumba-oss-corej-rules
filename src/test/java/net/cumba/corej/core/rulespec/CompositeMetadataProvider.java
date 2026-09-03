package net.cumba.corej.core.rulespec;

import java.util.List;
import java.util.Map;

import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * Parity-harness {@link MetadataProvider} that layers the offline pickle-backed library provider
 * over the spec's {@link MockLibraryProvider}.
 *
 * <p>
 * Precedence: the spec's explicit mock is authoritative — every query resolves from the mock first,
 * falling back to the pickle (offline library) provider only when the mock has nothing to say. This
 * realises decision #3's intent (pickle supplies the library/model metadata the mocks don't) while
 * never overriding a value a spec deliberately mocked (e.g. a small {@code expected_variables}
 * set). When either delegate is {@code null}, the other answers alone.
 * </p>
 */
final class CompositeMetadataProvider implements MetadataProvider
{

    private final @Nullable MetadataProvider mock;

    private final @Nullable MetadataProvider pickle;

    CompositeMetadataProvider(@Nullable MetadataProvider aMock, @Nullable MetadataProvider aPickle)
    {
        mock = aMock;
        pickle = aPickle;
    }

    // ---- library/model queries: mock first (explicit spec mock wins), pickle fallback ----


    @Override
    public List<String> getRequiredVariables(String domain)
    {
        return firstNonEmptyList(p -> p.getRequiredVariables(domain), mock, pickle);
    }


    @Override
    public List<String> getExpectedVariables(String domain)
    {
        return firstNonEmptyList(p -> p.getExpectedVariables(domain), mock, pickle);
    }

    // ⚠ Fix #368: delegated for the same reason as the engine's own wrappers — the interface
    // defaults mean "cannot answer", so inheriting them would make every composed provider report
    // that on behalf of one that can. Here the first NON-NULL answer wins rather than the first
    // non-empty one: an empty list is a real answer ("this structure requires nothing"), and
    // folding it into "no answer" would resurrect the vacuity the fix removed.


    @Override
    public boolean supportsStructureKeyedVariables()
    {
        return (mock != null && mock.supportsStructureKeyedVariables())
                || (pickle != null && pickle.supportsStructureKeyedVariables());
    }


    /**
     * Fix #369 — degraded if either backing provider reports its Library could not be consulted. A
     * composite that answered "library fine" while one half was degraded would let the engine
     * evaluate a library-citing rule against the other half's non-library data.
     */
    @Override
    public boolean isLibraryUnavailable()
    {
        return (mock != null && mock.isLibraryUnavailable())
                || (pickle != null && pickle.isLibraryUnavailable());
    }

    // ⚠ Phase 3 of PLAN-metadata-product-selection: the TWO-ARG form is the primary one. A
    // composite that delegated only the one-arg convenience would drop the dataset's subclass and
    // resolve every ADaM list from the base structure alone.


    @Override
    public @Nullable List<String> getRequiredVariablesForStructure(String structureToken,
            List<String> subclassTokens)
    {
        return firstNonNullList(
                p -> p.getRequiredVariablesForStructure(structureToken, subclassTokens), mock,
                pickle);
    }


    @Override
    public @Nullable List<String> getExpectedVariablesForStructure(String structureToken,
            List<String> subclassTokens)
    {
        return firstNonNullList(
                p -> p.getExpectedVariablesForStructure(structureToken, subclassTokens), mock,
                pickle);
    }


    /**
     * Review finding F6 — the structure-keyed provenance, delegated. Log-only in effect, but the
     * log it feeds is {@code OperationExecutor}'s Fix #369 SKIP diagnostic, and without this
     * override every spec run reported {@code declared product(s) <unknown>} — in the very harness
     * this area is debugged from. Mock-first / pickle-fallback like every other list here: the spec
     * mock declares no products, so in practice this resolves to the pickle provider's declaration.
     */
    @Override
    public List<String> declaredStructureKeyedProducts()
    {
        return firstNonEmptyList(MetadataProvider::declaredStructureKeyedProducts, mock, pickle);
    }


    /** First non-null answer among the given providers, or {@code null} when none answers. */
    private static @Nullable List<String> firstNonNullList(
            java.util.function.Function<MetadataProvider, @Nullable List<String>> aFn,
            @Nullable MetadataProvider... aProviders)
    {
        for (MetadataProvider p : aProviders)
        {
            if (p == null)
            {
                continue;
            }
            List<String> answer = aFn.apply(p);
            if (answer != null)
            {
                return answer;
            }
        }
        return null;
    }


    @Override
    public List<String> getColumnOrder(String domain)
    {
        return firstNonEmptyList(p -> p.getColumnOrder(domain), mock, pickle);
    }


    @Override
    public List<String> getModelColumnOrder(String domain)
    {
        return firstNonEmptyList(p -> p.getModelColumnOrder(domain), mock, pickle);
    }


    @Override
    public List<String> getCodelistTerms(String codelistCode)
    {
        return firstNonEmptyList(p -> p.getCodelistTerms(codelistCode), mock, pickle);
    }


    @Override
    public Map<String, String> getCodelistTermMappings(String codelistName)
    {
        return firstNonEmptyMap(p -> p.getCodelistTermMappings(codelistName), mock, pickle);
    }


    @Override
    public List<String> getCodelistAttribute(String ctPackageId, String ctAttribute)
    {
        // The mock has no CT-package access; this resolves from the pickle-backed provider.
        return firstNonEmptyList(p -> p.getCodelistAttribute(ctPackageId, ctAttribute), mock,
                pickle);
    }


    @Override
    public List<Map<String, String>> getModelVariables(String domain)
    {
        return firstNonEmptyMapList(p -> p.getModelVariables(domain), mock, pickle);
    }


    @Override
    public List<Map<String, String>> getModelVariablesForClass(String aModelClass)
    {
        return firstNonEmptyMapList(p -> p.getModelVariablesForClass(aModelClass), mock, pickle);
    }


    @Override
    public @Nullable List<String> getStandardModelVariables(IDataTable aTable,
            DatasetResolver aResolver)
    {
        return firstNonNull(p -> p.getStandardModelVariables(aTable, aResolver), mock, pickle);
    }


    @Override
    public @Nullable List<Map<String, String>> getStandardModelVariablesDetailed(IDataTable aTable,
            DatasetResolver aResolver)
    {
        return firstNonNull(p -> p.getStandardModelVariablesDetailed(aTable, aResolver), mock,
                pickle);
    }


    @Override
    public @Nullable List<Map<String, String>> getStandardModelVariablesForClass(IDataTable aTable,
            DatasetResolver aResolver, String aModelClass)
    {
        return firstNonNull(
                p -> p.getStandardModelVariablesForClass(aTable, aResolver, aModelClass), mock,
                pickle);
    }


    @Override
    public @Nullable List<Map<String, String>> getStandardVariablesDetailed(IDataTable aTable,
            DatasetResolver aResolver)
    {
        return firstNonNull(p -> p.getStandardVariablesDetailed(aTable, aResolver), mock, pickle);
    }


    @Override
    public @Nullable List<String> getStandardVariableNames()
    {
        // EC-13 — mirror the Python harness, whose `variable_names` reads the mock-built
        // `variables_metadata` only. The spec mock wins; pickle answers only when no mock is
        // supplied (no shipping spec relies on the real product here).
        return firstNonNull(MetadataProvider::getStandardVariableNames, mock, pickle);
    }


    @Override
    public @Nullable List<String> getStandardDatasetNames()
    {
        // EC-14 layer (i) — the spec mock wins; pickle answers only when no mock is supplied.
        return firstNonNull(MetadataProvider::getStandardDatasetNames, mock, pickle);
    }


    /**
     * <b>R11</b> — the name-keyed carry-over lookup.
     *
     * <p>
     * ⛔⛔ <b>Review finding R-5 — this was {@code firstNonNull}, and that made the pickle half
     * unreachable.</b> {@code MetadataProvider.getPublishedVariablesByName} is a {@code default}
     * method returning {@code List.of()}, so a mock that does not override it answers EMPTY BUT
     * NON-NULL — and {@code firstNonNull} accepted that as the answer for every variable name.
     * Measured: mock alone {@code []}, pickle alone one candidate, composite {@code []}. Every
     * other non-nullable list accessor here uses {@code firstNonEmptyList} for exactly this reason
     * (see {@code getDeclaredSubClasses}). Same trap as the {@code default}-method decorator hole
     * that has now bitten this plan three times.
     * </p>
     */
    @Override
    public List<PublishedVariable> getPublishedVariablesByName(String variableName)
    {
        return firstNonEmptyList(p -> p.getPublishedVariablesByName(variableName), mock, pickle);
    }


    @Override
    public boolean isDomainCustom(String domain)
    {
        // Custom iff the explicit spec mock declares it custom OR the offline pickle computes it
        // custom from product membership. A plain mock-first read would shadow the pickle with the
        // mock's false-by-default for un-mocked domains, hiding a genuinely custom domain
        // (CORE-000376, where no domain_is_custom mock is supplied). No spec mocks
        // domain_is_custom=false, so the OR cannot override a deliberate negative mock.
        return (mock != null && mock.isDomainCustom(domain))
                || (pickle != null && pickle.isDomainCustom(domain));
    }


    @Override
    public boolean isCodelistExtensible(String codelistName)
    {
        MetadataProvider primary = mock != null ? mock : pickle;
        return primary != null && primary.isCodelistExtensible(codelistName);
    }

    // ---- study/dataset queries: mock first, pickle fallback ----


    @Override
    public Map<String, String> getVariableMetadata(String domain, String variable)
    {
        return firstNonEmptyMap(p -> p.getVariableMetadata(domain, variable), mock, pickle);
    }


    @Override
    public List<Map<String, String>> getDomainVariables(String domain)
    {
        return firstNonEmptyMapList(p -> p.getDomainVariables(domain), mock, pickle);
    }


    @Override
    public Map<String, String> getDatasetMetadata(String domain)
    {
        return firstNonEmptyMap(p -> p.getDatasetMetadata(domain), mock, pickle);
    }


    @Override
    public List<String> getPublishedCtPackages()
    {
        return firstNonEmptyList(MetadataProvider::getPublishedCtPackages, mock, pickle);
    }


    /**
     * Fix #235: the declared {@code def:Class} tier, mock first. Without this override the
     * composite fell through to {@link MetadataProvider}'s {@code null} default and silently
     * swallowed {@link MockLibraryProvider#getDeclaredDatasetClass}'s answer whenever a pickle
     * provider was present — i.e. on every spec that names a standard/version, which is all of the
     * ADaM ones. Same mock-wins precedence as every other accessor here.
     */
    @Override
    public @Nullable String getDeclaredDatasetClass(String aDatasetName)
    {
        return firstNonNull(p -> p.getDeclaredDatasetClass(aDatasetName), mock, pickle);
    }


    /**
     * Phase 11 finding F6c — the {@code def:SubClass} half of the declared tier, which Fix #235
     * added the {@code def:Class} half of and left here. Same mock-wins precedence; first
     * <em>non-empty</em> rather than first non-null, because the contract's "declares nothing" is
     * the empty list, not {@code null}, so an empty answer from the mock must fall through to the
     * pickle exactly as every other list accessor in this class does.
     */
    @Override
    public List<String> getDeclaredSubClasses(String aDatasetName)
    {
        return firstNonEmptyList(p -> p.getDeclaredSubClasses(aDatasetName), mock, pickle);
    }


    @Override
    public @Nullable String getDatasetClass(String aDomain)
    {
        return firstNonNull(p -> p.getDatasetClass(aDomain), mock, pickle);
    }


    @Override
    public @Nullable String getDatasetClass(@Nullable String aMemberName, String aCdiscDomain)
    {
        return firstNonNull(p -> p.getDatasetClass(aMemberName, aCdiscDomain), mock, pickle);
    }


    @Override
    public String getStandard()
    {
        MetadataProvider primary = mock != null ? mock : pickle;
        return primary != null ? primary.getStandard() : "";
    }


    @Override
    public String getVersion()
    {
        MetadataProvider primary = mock != null ? mock : pickle;
        return primary != null ? primary.getVersion() : "";
    }


    @Override
    public @Nullable String getDefineVersion()
    {
        return firstNonNull(MetadataProvider::getDefineVersion, mock, pickle);
    }

    // ---- helpers ----

    private interface Fn<T>
    {

        T apply(MetadataProvider p);
    }

    /**
     * The first provider whose list answer is non-empty, else the second's, else empty.
     *
     * <p>
     * ⛔ Generic in the element type since review finding R-5: the carry-over accessor answers
     * {@code List<PublishedVariable>}, and it was the one list accessor here NOT routed through
     * this helper — it used {@code firstNonNull}, which accepts the {@code default} method's empty
     * but non-null {@code List.of()} as an answer and so never consulted the second provider.
     * </p>
     */
    private static <T> List<T> firstNonEmptyList(Fn<List<T>> fn, @Nullable MetadataProvider first,
            @Nullable MetadataProvider second)
    {
        if (first != null)
        {
            List<T> r = fn.apply(first);
            if (r != null && !r.isEmpty())
            {
                return r;
            }
        }
        return second != null ? fn.apply(second) : List.of();
    }


    private static List<Map<String, String>> firstNonEmptyMapList(Fn<List<Map<String, String>>> fn,
            @Nullable MetadataProvider first, @Nullable MetadataProvider second)
    {
        if (first != null)
        {
            List<Map<String, String>> r = fn.apply(first);
            if (r != null && !r.isEmpty())
            {
                return r;
            }
        }
        return second != null ? fn.apply(second) : List.of();
    }


    private static Map<String, String> firstNonEmptyMap(Fn<Map<String, String>> fn,
            @Nullable MetadataProvider first, @Nullable MetadataProvider second)
    {
        if (first != null)
        {
            Map<String, String> r = fn.apply(first);
            if (r != null && !r.isEmpty())
            {
                return r;
            }
        }
        return second != null ? fn.apply(second) : Map.of();
    }


    private static <T> @Nullable T firstNonNull(Fn<@Nullable T> fn,
            @Nullable MetadataProvider first, @Nullable MetadataProvider second)
    {
        if (first != null)
        {
            T r = fn.apply(first);
            if (r != null)
            {
                return r;
            }
        }
        return second != null ? fn.apply(second) : null;
    }
}
