package net.cumba.corej.core.rulespec;

import java.util.List;
import java.util.Map;

import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.AdamDataStructureDetector;

/**
 * Test {@link MetadataProvider} backed by a {@link SpecSchema.SpecMocks} payload.
 *
 * <p>
 * Each accessor returns the mocked value when present, or an empty default (list/map/false).
 * Callers that want "no provider at all" should pass {@code null} to
 * {@link net.cumba.corej.core.exec.RuleRunner#execute} instead — the engine's null-check triggers a
 * SKIPPED result for any rule needing Library data.
 *
 * <p>
 * Standard / version are sourced from the {@link SpecSchema.SpecConfig} so mocks don't have to
 * repeat them.
 */
public final class MockLibraryProvider implements MetadataProvider
{

    private final SpecSchema.SpecMocks mocks;

    private final String standard;

    private final String version;

    public static MetadataProvider of(SpecSchema.SpecMocks mocks, SpecSchema.SpecConfig config)
    {
        if (mocks == null)
        {
            return null;
        }
        return new MockLibraryProvider(mocks, config != null ? config.standard() : null,
                config != null ? config.standardVersion() : null);
    }


    private MockLibraryProvider(SpecSchema.SpecMocks mocks, String standard, String version)
    {
        this.mocks = mocks;
        this.standard = standard;
        this.version = version;
    }


    @Override
    public List<String> getRequiredVariables(String domain)
    {
        return get(mocks.requiredVariables(), domain, List.of());
    }


    @Override
    public List<String> getExpectedVariables(String domain)
    {
        return get(mocks.expectedVariables(), domain, List.of());
    }


    @Override
    public List<String> getColumnOrder(String domain)
    {
        return get(mocks.columnOrder(), domain, List.of());
    }


    @Override
    public boolean isDomainCustom(String domain)
    {
        return get(mocks.domainCustom(), domain, false);
    }


    @Override
    public List<String> getCodelistTerms(String codelistCode)
    {
        return get(mocks.codelistTerms(), codelistCode, List.of());
    }


    @Override
    public Map<String, String> getVariableMetadata(String domain, String variable)
    {
        // Look up via getDomainVariables; cheap and consistent with how rules use this.
        for (Map<String, String> v : getDomainVariables(domain))
        {
            if (variable.equals(v.get("name")))
            {
                return withCodelistAccessors(v);
            }
        }
        return Map.of();
    }


    /**
     * Mirror {@code MetadataLibraryProvider.columnToMap}'s codelist-accessor population for the
     * parity harness: when a mocked variable carries a flat {@code codelist} binding (and does not
     * already provide the keys), derive {@code codelist_coded_values} (JSON list, from the
     * {@code codelist_terms} mock), {@code codelist_coded_codes} (from the
     * {@code codelist_term_codes} mock — the ADaM code accessor) and {@code codelist_extensible}
     * (from the {@code codelist_extensible} mock) so the {@code Value Check against Library
     * Metadata} accessors resolve. A flat binding is used because the typed
     * {@code Map<String,String>} mock cannot carry the nested {@code _links.codelist} the
     * production providers read.
     */
    private Map<String, String> withCodelistAccessors(Map<String, String> v)
    {
        String codelist = v.get("codelist");
        if (codelist == null)
        {
            return v;
        }
        Map<String, String> out = new java.util.LinkedHashMap<>(v);
        // The flat binding IS the codelist's NCI C-code — derive it even when the mock
        // pre-supplies the coded-values keys below (production always carries both).
        out.putIfAbsent("ccode", codelist);
        // Term C-codes (the ADaM code accessor): derived from the codelist_term_codes mock in
        // codelist_terms order. Mirrors MetadataLibraryProvider.codelistCodedCodes — when no
        // term resolves to a C-code the key stays ABSENT (production returns null), so the
        // rule-side non_empty guard excludes the codelist.
        if (!v.containsKey("codelist_coded_codes"))
        {
            Map<String, String> termCodes = get(mocks.codelistTermCodes(), codelist, Map.of());
            List<String> codes = getCodelistTerms(codelist).stream().map(termCodes::get)
                    .filter(java.util.Objects::nonNull).toList();
            if (!codes.isEmpty())
            {
                out.put("codelist_coded_codes",
                        net.cumba.corej.core.metadata.DefineMetadataListCodec.encode(codes));
            }
        }
        if (v.containsKey("codelist_coded_values"))
        {
            return out;
        }
        out.put("codelist_coded_values", net.cumba.corej.core.metadata.DefineMetadataListCodec
                .encode(getCodelistTerms(codelist)));
        // F-corej-ct-02: the var_codelist_extensible attribute channel shares the miss behaviour
        // of the accessor above — production's MetadataLibraryProvider.codelistExtensible does
        // `isCodelistExtensible(name).map(String::valueOf).orElse(null)`, so an UNRESOLVABLE
        // codelist leaves the key ABSENT rather than stringifying a defaulted boolean.
        isCodelistExtensible(codelist)
                .ifPresent(e -> out.put("codelist_extensible", String.valueOf(e)));
        return out;
    }


    @Override
    public List<Map<String, String>> getDomainVariables(String domain)
    {
        return get(mocks.domainVariables(), domain, List.of());
    }


    @Override
    public List<Map<String, String>> getModelVariables(String domain)
    {
        return get(mocks.modelVariables(), domain, List.of());
    }


    /**
     * EC-13 {@code variable_names} — the order-preserving union of variable names across every
     * dataset declared in {@code library_mocks.classes}, read from
     * {@code library_mocks.domain_variables}. Mirrors the Python parity adapter, whose
     * {@code variables_metadata} is populated (only) for datasets declared in {@code classes} from
     * the same {@code domain_variables} mock (engine_adapter.py). Returns {@code null} when no
     * class/domain-variable mock is present so the operation SKIPs (LIBRARY_NOT_AVAILABLE),
     * matching the production provider's null-on-no-product signal.
     */
    @Override
    public List<String> getStandardVariableNames()
    {
        if (mocks.classes() == null || mocks.domainVariables() == null)
        {
            return null;
        }
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (List<String> datasets : mocks.classes().values())
        {
            if (datasets == null)
            {
                continue;
            }
            for (String ds : datasets)
            {
                for (Map<String, String> var : getDomainVariables(ds))
                {
                    String name = var.get("name");
                    if (name != null)
                    {
                        names.add(name);
                    }
                }
            }
        }
        return names.isEmpty() ? null : List.copyOf(names);
    }


    /**
     * EC-14 layer (i) {@code standard_domains} — the order-preserving union of dataset (domain)
     * names declared in {@code library_mocks.classes} unioned with {@code library_mocks
     * .dataset_metadata} keys. Mirrors the Python parity adapter, whose
     * {@code standard_metadata['dataset_names']} is the union of {@code classes} datasets and
     * {@code dataset_metadata} keys (engine_adapter.py); the model contribution is empty for a
     * non-SDTM-family config (curated model mock carries no {@code dataset_names}). Returns
     * {@code null} when neither mock is present so the operation SKIPs (LIBRARY_NOT_AVAILABLE).
     *
     * <p>
     * EC-14 layer (ii): when {@code library_mocks.standard_domains} is set it wins outright,
     * standing in for the companion SDTM product's domain list on an ADaM-family run (the Python
     * adapter mirrors this via the container's {@code companion_dataset_names}).
     * </p>
     */
    @Override
    public List<String> getStandardDatasetNames()
    {
        if (mocks.standardDomains() != null)
        {
            return List.copyOf(mocks.standardDomains());
        }
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        if (mocks.classes() != null)
        {
            for (List<String> datasets : mocks.classes().values())
            {
                if (datasets != null)
                {
                    names.addAll(datasets);
                }
            }
        }
        if (mocks.datasetMetadata() != null)
        {
            names.addAll(mocks.datasetMetadata().keySet());
        }
        return names.isEmpty() ? null : List.copyOf(names);
    }


    /**
     * <b>Fix #235 — the declaration the specs already carry, routed to the axis that reads it.</b>
     * The declared {@code def:Class} for {@code datasetName}, derived from
     * {@code library_mocks.classes} when — and only when — the class key names an ADaM <em>data
     * structure</em>.
     *
     * <h4>Why the class channel is already a structure declaration</h4>
     *
     * <p>
     * Define-XML carries the ADaM data structure in the very same {@code def:Class} element that
     * carries an SDTM observation class — <b>ADaM has no separate {@code Class}</b>. A spec writing
     * {@code library_mocks.classes: {occurrence data structure: [ADAE]}} has therefore already
     * declared ADAE's structure; before this fix that statement reached only
     * {@link #getStandardVariableNames} / {@link #getStandardDatasetNames} (the class channel),
     * while {@code RuleRunner}'s {@code Scope.Data_Structures} gate read the <em>declared</em> tier
     * through {@link MetadataProvider#getDeclaredDatasetClass(String)} and found nothing — so the
     * column heuristic answered {@code ADAM OTHER} and the rule SKIPped. Measured 2026-08-12 over
     * the 49 parity-held ADaM rules: all 49 carry such a block and every one of the 55 keys across
     * them is a structure token, not one an SDTM class.
     * </p>
     *
     * <p>
     * ⚠ <b>Closed vocabulary, deliberately.</b> Routing goes through
     * {@link AdamDataStructureDetector#structureTokenFromDeclaredClass}, which returns {@code null}
     * for the eight SDTM {@code ItemGroupClass} values and for anything unrecognised. An SDTM class
     * key such as {@code events} therefore stays a pure class declaration and can never become a
     * silent structure declaration. This is the same predicate
     * {@code ScenarioDeclaredScopeProvider.effectiveDeclaredStructure} applies to a {@code .cdt}
     * {@code class=} attribute, so the two harnesses read an authored class the same way.
     * </p>
     *
     * <p>
     * ⚑ <b>No spec file changes.</b> The declaration is the one the author already wrote; a spec
     * whose {@code classes} block names no ADaM structure (or that has no block at all) answers
     * {@code null} here and behaves exactly as before.
     * </p>
     *
     * @return the canonical structure token declared for {@code datasetName}, or {@code null} when
     *         the spec declares no ADaM data structure for it
     */
    @Override
    public String getDeclaredDatasetClass(String datasetName)
    {
        if (mocks.classes() == null)
        {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : mocks.classes().entrySet())
        {
            String token = AdamDataStructureDetector
                    .structureTokenFromDeclaredClass(entry.getKey());
            if (token == null || entry.getValue() == null)
            {
                continue;
            }
            for (String dataset : entry.getValue())
            {
                if (dataset != null && dataset.equalsIgnoreCase(datasetName))
                {
                    return token;
                }
            }
        }
        return null;
    }


    @Override
    public List<String> getPublishedCtPackages()
    {
        return mocks.publishedCtPackages() != null ? mocks.publishedCtPackages() : List.of();
    }


    @Override
    public Map<String, String> getDatasetMetadata(String domain)
    {
        return get(mocks.datasetMetadata(), domain, Map.of());
    }


    /**
     * F-corej-ct-02 — mirrors {@code MetadataLibraryProvider.isCodelistExtensible}'s two cases
     * rather than the old fixture-friendly default-to-true.
     *
     * <ul>
     * <li>The codelist is <b>not held</b> by this spec's mocks at all ⇒
     * {@link java.util.Optional#empty()} — unresolvable, and the engine surfaces it as a rule SKIP.
     * A defaulted {@code true} here silently disarmed every shipped {@code == false} guard.</li>
     * <li>The codelist <b>is held</b> (declared on any codelist channel) but carries no
     * {@code codelist_extensible} flag ⇒ {@code Optional.of(TRUE)} — a real answer, exactly as
     * production treats a found codelist whose {@code ICodeList.isExtensible()} is null. This is
     * <em>not</em> the unresolvable case and must not become empty.</li>
     * </ul>
     */
    @Override
    public java.util.Optional<Boolean> isCodelistExtensible(String codelistName)
    {
        Boolean flag = get(mocks.codelistExtensible(), codelistName, null);
        if (flag != null)
        {
            return java.util.Optional.of(flag);
        }
        return holdsCodelist(codelistName) ? java.util.Optional.of(Boolean.TRUE)
                : java.util.Optional.empty();
    }


    /**
     * Whether the spec declares {@code codelistName} on any codelist channel — the mock's analogue
     * of {@code MetadataLibraryProvider.findCodelist} being present. Used only to separate "found,
     * flag unspecified" from "not resolvable" above.
     */
    private boolean holdsCodelist(String codelistName)
    {
        return declares(mocks.codelistExtensible(), codelistName)
                || declares(mocks.codelistTerms(), codelistName)
                || declares(mocks.codelistTermCodes(), codelistName)
                || declares(mocks.codelistTermMappings(), codelistName);
    }


    private static boolean declares(Map<String, ?> map, String key)
    {
        return map != null && map.containsKey(key);
    }


    @Override
    public String getStandard()
    {
        return standard;
    }


    private static <K, V> V get(Map<K, V> map, K key, V fallback)
    {
        if (map == null)
        {
            return fallback;
        }
        V v = map.get(key);
        return v != null ? v : fallback;
    }
}
