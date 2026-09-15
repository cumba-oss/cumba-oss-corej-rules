/*
 * COPY. The original lives in corej-core's test tree
 * (lib/corej-core/src/test/java/net/cumba/corej/core/exec/StubMetadataProvider.java).
 *
 * Copied here on 2026-09-01 by plans/done/PLAN-corej-restructure.md section 4, which deleted this
 * module's <type>test-jar</type> dependency on corej-core. That dependency existed so four
 * cdisc-core test classes could be reached across the module boundary; two of them (MockTable,
 * TestMetadataFixtures) became the published corej-datatable-testkit, but neither of the remaining
 * two belongs in a DATATABLE testkit -- this one is a cdisc-core metadata SPI stub -- and there are
 * only 1 call site here, so a copy is cheaper than a second published artifact.
 *
 * ACCEPTED COST: the two copies can drift. That is tolerable for a test stub. If this class ever
 * starts carrying real behaviour, revisit -- the alternative is a corej-core-testkit alongside the
 * datatable one.
 */
package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, configurable {@link MetadataProvider} for define / library-level unit tests. Holds an
 * ordered per-domain variable list (each variable a {@code Map<String,String>} of attributes keyed
 * exactly as the provider channel expects — e.g. {@code role}, {@code ccode},
 * {@code codelist_coded_codes}), an ordered dataset-name list plus per-dataset attributes, and
 * optional codelist terms and a custom-domain flag. Everything else returns empty defaults.
 *
 * <p>
 * <b>The dataset-name list and the dataset-attribute map are deliberately separate</b>
 * (PLAN-parity-define-dataset-channel D3/D6). {@link #getDatasetNames()} reads <em>only</em>
 * {@link #defineDatasets} — never {@link #datasetMeta}'s key set. A Define may declare a dataset
 * the caller registers no attributes for (a variable-level-only overlay is exactly that shape), and
 * deriving the name list from the attribute map would return an empty list for it. That is not a
 * harmless emptiness: {@code define_dataset_names} has no
 * empty&nbsp;&rArr;&nbsp;{@code LIBRARY_NOT_AVAILABLE} demotion in {@code OperationExecutor}
 * (unlike its neighbour {@code define_key_variables}), so an empty list is reported as a successful
 * execution with {@code $define_datasets = []} and rules such as {@code FDA-SD1063} then fire on
 * <em>every</em> dataset — a silently wrong verdict rather than a skip.
 */
public final class StubMetadataProvider implements MetadataProvider
{

    private final Map<String, List<Map<String, String>>> domainVars = new LinkedHashMap<>();

    /**
     * The dataset names the Define declares, in registration order. Backs
     * {@link #getDatasetNames()} and nothing else.
     */
    private final List<String> defineDatasets = new ArrayList<>();

    /**
     * Dataset name &rarr; dataset-level attributes, keyed exactly as the provider channel expects:
     * {@code name}, {@code label}, {@code className} (see {@code MetadataAttribute.DS_CLASS} and
     * {@code RuleRunner.injectDatasetLevel} — the class attribute is <b>not</b> spelled
     * {@code class}). Backs {@link #getDatasetMetadata(String)}.
     */
    private final Map<String, Map<String, String>> datasetMeta = new LinkedHashMap<>();

    private final Map<String, List<String>> codelistTerms = new LinkedHashMap<>();

    private final java.util.Set<String> customDomains = new java.util.HashSet<>();

    /**
     * Codelist code -> extensibility; absent codes fall back to the {@link #isCodelistExtensible}
     * default.
     */
    private final Map<String, Boolean> codelistExtensible = new LinkedHashMap<>();

    /**
     * E9 — {@code "domain\0variable"} → (term submission value → concept id) map, used by
     * {@link #getCodelistCodeMap} for the {@code library_variable_code_pair_matches} accessor.
     */
    private final Map<String, Map<String, String>> codeMaps = new LinkedHashMap<>();

    /**
     * Fix #223 — the <b>declare channel</b>: dataset name (upper-cased) &rarr; the sponsor's
     * declared {@code def:Class}. Backs {@link #getDeclaredDatasetClass(String)}, tier 1 of the
     * {@code Scope.Data_Structures} determination ({@code RuleRunner.declaredClassOf} &rarr;
     * {@code AdamDataStructureDetector.detectAll}).
     */
    private final Map<String, String> declaredClasses = new LinkedHashMap<>();

    /**
     * Fix #223 — dataset name (upper-cased) &rarr; the sponsor's declared {@code def:SubClass}
     * names, in declaration order. Backs {@link #getDeclaredSubClasses(String)}, tier 1 of the
     * {@code Scope.Subclasses} determination.
     */
    private final Map<String, List<String>> declaredSubClasses = new LinkedHashMap<>();

    /**
     * Registers a codelist's extensibility (by name or NCI C-code), used by CT2004/CT2005 tests.
     */
    public StubMetadataProvider extensible(String codelistCode, boolean isExtensible)
    {
        codelistExtensible.put(codelistCode, isExtensible);
        return this;
    }


    public StubMetadataProvider variable(String domain, Map<String, String> attrs)
    {
        domainVars.computeIfAbsent(domain, _ -> new ArrayList<>()).add(new LinkedHashMap<>(attrs));
        return this;
    }


    /**
     * Registers {@code name} on the Define's dataset list ({@link #getDatasetNames()}). Idempotent
     * and order-preserving. Registering a name does <b>not</b> register any attributes for it — a
     * Define that declares a dataset without the three attributes this provider exposes is a
     * legitimate shape.
     */
    public StubMetadataProvider defineDataset(String name)
    {
        if (!defineDatasets.contains(name))
        {
            defineDatasets.add(name);
        }
        return this;
    }


    /**
     * Registers a dataset's dataset-level attributes ({@code name} / {@code label} /
     * {@code className}). Does <b>not</b> touch the dataset-name list — call
     * {@link #defineDataset(String)} for that.
     */
    public StubMetadataProvider datasetMeta(String name, Map<String, String> attrs)
    {
        datasetMeta.put(name, new LinkedHashMap<>(attrs));
        return this;
    }


    /** Registers a codelist's terms (codes), e.g. the published {@code DOMAIN} abbreviations. */
    public StubMetadataProvider codelist(String codelistCode, List<String> terms)
    {
        codelistTerms.put(codelistCode, List.copyOf(terms));
        return this;
    }


    /** Marks {@code domain} as a custom (non-standard) domain for {@link #isDomainCustom}. */
    public StubMetadataProvider customDomain(String domain)
    {
        customDomains.add(domain);
        return this;
    }


    /**
     * <b>Fix #223 — the declare channel.</b> Registers what the sponsor <em>declares</em> the
     * dataset to be: the Define-XML {@code def:Class} value, and optionally its
     * {@code def:SubClass} names. Hand this provider to the {@code defineProvider} parameter of
     * {@code RuleRunner.execute(rule, table, resolver, prefix, library, joinCache, define)} and the
     * {@code Scope.Data_Structures} / {@code Scope.Subclasses} gates resolve from the declaration
     * instead of re-deriving a structure out of the fixture's columns.
     *
     * <p>
     * ⚑ <b>Why this exists.</b> A probe on the 3-arg {@code execute} overload has no way to
     * declare, so its fixture has to be shaped until
     * {@link net.cumba.corej.core.metadata.AdamDataStructureDetector} /
     * {@link net.cumba.corej.core.metadata.AdamSubclassDetector} happen to infer the structure the
     * rule is scoped to — an {@code AETERM} column no {@code Check} reads, a {@code PARAMCD} that
     * exists only to say "this is BDS". That is <em>make-the-data-true</em>, which the ADaM
     * structure work explicitly rejected: a conformance engine validates data against what the
     * sponsor declared and reports the mismatch, it does not quietly re-declare on the sponsor's
     * behalf.
     * </p>
     *
     * @param datasetName
     *            the dataset (member) name, matched case-insensitively
     * @param declaredClass
     *            the declared class verbatim, e.g. {@code OCCURRENCE DATA STRUCTURE}
     * @param subClasses
     *            the declared subclass names, e.g. {@code ADVERSE EVENT}; may be empty
     * @return {@code this}, for chaining
     */
    public StubMetadataProvider declares(String datasetName, String declaredClass,
            String... subClasses)
    {
        String key = datasetName.toUpperCase(java.util.Locale.ROOT);
        declaredClasses.put(key, declaredClass);
        declaredSubClasses.put(key, List.of(subClasses));
        return this;
    }


    @Override
    public @org.jspecify.annotations.Nullable String getDeclaredDatasetClass(String datasetName)
    {
        return declaredClasses.get(datasetName.toUpperCase(java.util.Locale.ROOT));
    }


    @Override
    public List<String> getDeclaredSubClasses(String datasetName)
    {
        return declaredSubClasses.getOrDefault(datasetName.toUpperCase(java.util.Locale.ROOT),
                List.of());
    }


    @Override
    public List<Map<String, String>> getDomainVariables(String domain)
    {
        return domainVars.getOrDefault(domain, List.of());
    }


    @Override
    public Map<String, String> getVariableMetadata(String domain, String variable)
    {
        for (Map<String, String> v : domainVars.getOrDefault(domain, List.of()))
        {
            if (variable.equals(v.get("name")))
            {
                return v;
            }
        }
        return Map.of();
    }


    @Override
    public List<String> getCodelistTerms(String codelistCode)
    {
        return codelistTerms.getOrDefault(codelistCode, List.of());
    }


    @Override
    public boolean isDomainCustom(String domain)
    {
        return customDomains.contains(domain);
    }


    @Override
    public List<String> getRequiredVariables(String domain)
    {
        return List.of();
    }


    @Override
    public List<String> getExpectedVariables(String domain)
    {
        return List.of();
    }


    @Override
    public List<String> getColumnOrder(String domain)
    {
        return getDomainVariables(domain).stream().map(v -> v.get("name")).toList();
    }


    @Override
    public List<String> getModelColumnOrder(String domain)
    {
        return List.of();
    }


    @Override
    public List<String> getDatasetNames()
    {
        // ONLY defineDatasets — see the class javadoc. Do not "simplify" this to
        // datasetMeta.keySet(): PLAN-parity-define-dataset-channel P1.1 mutation-checked exactly
        // that change and it turns StubMetadataProviderDefineDatasetTest (3 cases) and the parity
        // lane's DefineDatasetChannelTest (3 cases) red.
        return List.copyOf(defineDatasets);
    }


    @Override
    public Map<String, String> getDatasetMetadata(String domain)
    {
        // Copy on read: the registration map must not escape. Callers only read it today
        // (RuleRunner.injectDatasetLevel, the native ds_* accessors), but handing out the live map
        // means one caller's put() silently rewrites the Define overlay for every later rule in
        // the same run — a cross-test leak that would surface as an unexplained verdict change.
        Map<String, String> attrs = datasetMeta.get(domain);
        return attrs == null ? Map.of() : new LinkedHashMap<>(attrs);
    }


    @Override
    public java.util.Optional<Boolean> isCodelistExtensible(String codelistName)
    {
        return java.util.Optional.of(codelistExtensible.getOrDefault(codelistName, Boolean.FALSE));
    }


    @Override
    public Map<String, String> getCodelistTermMappings(String codelistName)
    {
        return Map.of();
    }


    /** Registers a variable's term submission value → concept id map (E9). */
    public StubMetadataProvider codeMap(String domain, String variable, Map<String, String> map)
    {
        codeMaps.put(domain + "\0" + variable, new LinkedHashMap<>(map));
        return this;
    }


    @Override
    public Map<String, String> getCodelistCodeMap(String domain, String variable)
    {
        return codeMaps.getOrDefault(domain + "\0" + variable, Map.of());
    }


    @Override
    public String getStandard()
    {
        return "sdtmig";
    }


    @Override
    public String getVersion()
    {
        return "3.4";
    }

}
