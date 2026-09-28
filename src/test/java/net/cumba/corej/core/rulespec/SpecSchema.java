package net.cumba.corej.core.rulespec;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.RuleExecutionStatus;

/**
 * Data carrier for parity-test specs. Maps the YAML schema described in
 * {@code core-engine-test-frw-plan.md} §3 to Java records. Snake_case throughout (OD-9).
 *
 * <p>
 * Loaded via {@link SpecLoader}, which resolves embedded {@code $ref:} pointers and applies row-key
 * defaulting. The records here are pure data — no validation logic.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SpecSchema(String name, Kind kind, String description, String pythonSource,
        String pythonNotes,
        /* Inline rule object or pre-resolved {@code $ref}. Null for micro-specs. */
        JsonNode rule,
        /* Single CheckCondition leaf for {@link Kind#CHECK_OPERATOR} micro-specs. */
        JsonNode check,
        /* Operation definition for {@link Kind#OPERATION} micro-specs. */
        JsonNode operation, Map<String, SpecDataset> datasets, SpecConfig config,
        ScopeOverride scopeOverride, SpecMocks libraryMocks, JsonNode defineXml,
        JsonNode dictionaries, List<CanonicalViolation> expectedViolations,
        List<String> expectedErrors,
        /*
         * Rule-level execution status this lane is expected to produce (D1,
         * PLAN-parity-expected-status): Java-canonical, exactly like {@code expectedViolations}.
         * Typed as the engine's own enum so a bad value throws out of Jackson at load instead of
         * silently never matching — Jackson enum coercion is case-sensitive by default and
         * ACCEPT_CASE_INSENSITIVE_ENUMS is deliberately NOT enabled, because the vendored Python
         * fork's own vocabulary is lowercase and a lowercase value here is an author error.
         * Missing or null ⇒ {@code EXECUTED} at comparison time (see
         * {@link SpecRunner.SpecResult#statusMatches()}).
         */
        @JsonAlias("expected_status") RuleExecutionStatus expectedStatus,
        /*
         * The rule-level status MESSAGE this lane is expected to produce, compared exactly when
         * declared and ignored when absent (PLAN-binding-expressions review round 1, T1). Two
         * SKIPPED specs can differ only in WHICH gate skipped them — "no Library access" versus
         * "library returned no data for …" — and the status channel alone cannot tell them apart.
         */
        String expectedStatusMessage,
        /*
         * Provenance of a fully inline pinned rule (D1/D4, PLAN-parity-java-lane-native-corpus):
         * the spec's {@code rule:} was derived once from the named corpus rule and does not track
         * it. {@code PinnedSpecSyncGuardTest} fingerprints the source file and fails when it
         * moves, so the pin is re-derived deliberately rather than drifting silently.
         */
        PinnedFrom pinnedFrom)
{

    public enum Kind
    {
        @JsonProperty("rule")
        RULE, @JsonProperty("check_operator")
        CHECK_OPERATOR, @JsonProperty("operation")
        OPERATION
    }


    /** Pin provenance: the source rule id and the SHA-256 of its {@code rules-src} YAML. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PinnedFrom(String coreId, String rulesSrcSha256)
    {
    }


    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SpecDataset(
            /* Optional file reference; mutually exclusive with {@link #rows}. */
            @JsonAlias("$ref") String ref,
            /* Inline rows as ordered column→value maps. Null when {@link #ref} is used. */
            List<Map<String, Object>> rows,
            /*
             * Required (per OD-2) except for the DM default; enforced by {@link SpecLoader}.
             * Special token {@code __row_number__} triggers a virtual zero-based index column.
             */
            List<String> rowKey,
            /* Optional explicit type hints — keys are column names, values are
             *  {@link net.cumba.datatable.values.DataValueType} names (STRING, LONG, etc.). */
            Map<String, String> columnTypes)
    {
    }


    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SpecConfig(String standard, String standardVersion, List<String> ctPackages,
            String useCase)
    {
    }


    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScopeOverride(List<String> domainsInclude, List<String> domainsExclude,
            List<String> classesInclude, List<String> classesExclude)
    {
    }


    /**
     * Library-metadata mocks. All fields optional — null means "no override at this layer". The
     * absence of {@code library_mocks} entirely yields a null-provider instance via
     * {@link MockLibraryProvider}; when present, missing fields fall back to empty.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SpecMocks(Map<String, List<Map<String, String>>> domainVariables,
            Map<String, List<Map<String, String>>> modelVariables, List<String> publishedCtPackages,
            Map<String, List<String>> modelColumnOrder, Map<String, List<String>> requiredVariables,
            Map<String, List<String>> expectedVariables, Map<String, List<String>> columnOrder,
            Map<String, Map<String, String>> datasetMetadata,
            Map<String, List<String>> codelistTerms,
            Map<String, Map<String, String>> codelistTermMappings,
            /* Codelist C-code → {submission value → term NCI C-code}. Feeds the LIBRARY
             *  codelist_coded_codes accessor (the ADaM code accessor, DRAFT-900024) — Java
             *  derives the JSON list in MockLibraryProvider.withCodelistAccessors, Python
             *  attaches per-term conceptId in _build_mock_ct_package. Codelists absent here
             *  keep an absent coded-codes key, mirroring a loaded CT without conceptIds. */
            Map<String, Map<String, String>> codelistTermCodes,
            Map<String, Boolean> codelistExtensible, Map<String, Boolean> domainCustom,
            /* Class-name → list of dataset names. ⚠ The note this comment used to carry —
             *  "parsed-but-unused on the Java side" — was already false (it backs
             *  getStandardVariableNames / getStandardDatasetNames) and Fix #235 gives it a
             *  second, load-bearing role: when a key names an ADaM DATA STRUCTURE rather than
             *  an SDTM class, MockLibraryProvider.getDeclaredDatasetClass surfaces it on the
             *  declared def:Class tier that RuleRunner's Scope.Data_Structures gate reads.
             *  Define-XML has no separate ADaM Class element, so a structure token authored
             *  here IS the dataset's structure declaration. SDTM class names are unaffected:
             *  the routing goes through AdamDataStructureDetector.structureTokenFromDeclaredClass,
             *  a closed vocabulary that rejects them. */
            Map<String, List<String>> classes,
            /* EC-14 layer (ii): an explicit canonical SDTM domain-name set for the
             *  {@code standard_domains} operation, standing in for the companion SDTM
             *  product on an ADaM-family run. When present it is returned verbatim by
             *  {@link MockLibraryProvider#getStandardDatasetNames()} (Java) and set as the
             *  container's {@code companion_dataset_names} (Python), overriding the
             *  {@code classes}-derived union. */
            List<String> standardDomains)
    {
    }
}
