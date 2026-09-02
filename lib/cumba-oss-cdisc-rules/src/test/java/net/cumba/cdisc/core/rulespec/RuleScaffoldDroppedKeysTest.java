package net.cumba.cdisc.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.cdisc.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * The scaffold's drop-net ({@code RuleScaffold.rejectDroppedKeys}).
 *
 * <p>
 * {@link RuleScaffold} is a third reader of rule JSON that runs none of the loader's validation
 * passes — in particular neither gate R1 (the retired {@code Scope.Variables} spelling) nor gate R2
 * (an unknown key under {@code Requirements} / {@code Requirements.Variables} /
 * {@code Scope.Datasets}), both of which live in
 * {@code RulePackageLoader.validateOperandSubstitution}. The mapper runs with
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so on this lane an unbound key does not skip the
 * rule, it <b>deletes the requirement</b> and the spec runs unrestricted — which reads as a parity
 * result rather than as an authoring error. Several parity-baseline entries exist precisely because
 * a variable requirement makes Java SKIP where Python executes, so a silently deleted requirement
 * here is indistinguishable from a genuine divergence.
 *
 * <p>
 * ⚠ These are hand-authored gate tests: they prove the net fires, never that a shipped spec carries
 * an offending key. Measured 2026-08-25 over 1 105 files — the module's 1 048 spec YAMLs plus their
 * one {@code $ref}ed rule fixture and the 56 {@code coreId}-resolved
 * {@code ../corej-cdisc-rules/rules/rules-*.json} packages — 14 435 {@code Scope} and 5 736
 * {@code Requirements} blocks (4 734 of them with {@code Variables}) carry <b>0</b> unknown nested
 * keys. The net is preventive, and {@code Scope.Datasets} has no carrier at all yet.
 */
class RuleScaffoldDroppedKeysTest
{

    /** A well-formed rule whose {@code Requirements} block binds completely. */
    private static final String GOOD = """
            name: net_good
            kind: rule
            rule:
              id: net_good
              Core: {Id: AUTO-GOOD}
              Description: ""
              Sensitivity: Record
              Scope:
                Domains: {Include: [AE]}
              Requirements:
                Variables:
                  All: [AESTDTC]
              Check: {all: [{name: AESTDTC, operator: is_not_empty}]}
              Outcome: {Message: "m", Output_Variables: [AESTDTC]}
            datasets:
              AE:
                row_key: [STUDYID, USUBJID, AESEQ]
                rows:
                  - {STUDYID: "S1", USUBJID: "001", AESEQ: 1, AESTDTC: "2020-01-01"}
            config: {standard: sdtmig, standard_version: "3.4"}
            expected_violations: []
            """;

    private static String specWith(String name, String scopeAndRequirements)
    {
        return """
                name: %s
                kind: rule
                rule:
                  id: %s
                  Core: {Id: AUTO-%s}
                  Description: ""
                  Sensitivity: Record
                %s
                  Check: {all: [{name: AESTDTC, operator: is_not_empty}]}
                  Outcome: {Message: "m", Output_Variables: [AESTDTC]}
                datasets:
                  AE:
                    row_key: [STUDYID, USUBJID, AESEQ]
                    rows:
                      - {STUDYID: "S1", USUBJID: "001", AESEQ: 1, AESTDTC: "2020-01-01"}
                config: {standard: sdtmig, standard_version: "3.4"}
                expected_violations: []
                """.formatted(name, name, name, scopeAndRequirements.indent(2).stripTrailing());
    }


    private static IllegalStateException buildAndExpectRejection(String yaml)
    {
        return assertThrows(IllegalStateException.class, () ->
        {
            SpecSchema spec = SpecLoader.loadFromString(yaml);
            RuleScaffold.build(spec, "AE");
        });
    }


    /** Baseline: the net's original reach — a key the {@link Rule} model itself did not bind. */
    @Test
    void topLevelUnboundKey_isRejected()
    {
        String yaml = specWith("net_top", """
                Scope:
                  Domains: {Include: [AE]}
                _wildcards: {}""");
        IllegalStateException ex = buildAndExpectRejection(yaml);
        assertTrue(ex.getMessage().contains("_wildcards"),
                () -> "Message must name the dropped key: " + ex.getMessage());
    }


    /**
     * Gate R1's shape, on this lane. {@code Scope.Variables} retired to
     * {@code Requirements.Variables} ({@code plans/PLAN-scope-requirements-split.md}); {@link Rule}
     * binds nothing for it, and the key lands in {@code Scope}'s own collector, which the
     * {@code Rule}-level one never sees. Without the nested arm the spec runs with its variable
     * requirement deleted.
     */
    @Test
    void retiredScopeVariablesBlock_isRejected()
    {
        String yaml = specWith("net_scope_vars", """
                Scope:
                  Domains: {Include: [AE]}
                  Variables: {Include: [COMPLFL]}""");
        IllegalStateException ex = buildAndExpectRejection(yaml);
        assertTrue(ex.getMessage().contains("Scope.Variables"),
                () -> "Message must name the block AND the key ('Scope.Variables'): "
                        + ex.getMessage());
    }


    /** Gate R2's shape: a misspelled block name directly under {@code Requirements}. */
    @Test
    void misspelledRequirementsBlockKey_isRejected()
    {
        String yaml = specWith("net_req_block", """
                Scope:
                  Domains: {Include: [AE]}
                Requirements:
                  Variabels: {All: [COMPLFL]}""");
        IllegalStateException ex = buildAndExpectRejection(yaml);
        assertTrue(ex.getMessage().contains("Requirements.Variabels"),
                () -> "Message must name the block AND the key ('Requirements.Variabels'): "
                        + ex.getMessage());
    }


    /**
     * Gate R2's shape one level further down: a misspelled <em>facet</em>. Same silent deletion —
     * {@code AnyOf} binds to nothing, so the rule requires nothing and runs unrestricted.
     */
    @Test
    void misspelledVariableRequirementFacet_isRejected()
    {
        String yaml = specWith("net_req_facet", """
                Scope:
                  Domains: {Include: [AE]}
                Requirements:
                  Variables:
                    AnyOf: [AESTDTC, AEENDTC]""");
        IllegalStateException ex = buildAndExpectRejection(yaml);
        assertTrue(ex.getMessage().contains("Requirements.Variables.AnyOf"),
                () -> "Message must name the facet's full path"
                        + " ('Requirements.Variables.AnyOf'): " + ex.getMessage());
    }


    /** Gate R2's third block: an unknown key under {@code Scope.Datasets}. */
    @Test
    void unknownKeyUnderScopeDatasets_isRejected()
    {
        String yaml = specWith("net_ds_key", """
                Scope:
                  Domains: {Include: [AE]}
                  Datasets: {Includes: [AE]}""");
        IllegalStateException ex = buildAndExpectRejection(yaml);
        assertTrue(ex.getMessage().contains("Scope.Datasets.Includes"),
                () -> "Message must name the block AND the key ('Scope.Datasets.Includes'): "
                        + ex.getMessage());
    }


    /**
     * Negative control — the net must not fire on a {@code Requirements} block that binds. Without
     * this, every assertion above would still pass if the net rejected everything.
     */
    @Test
    void wellFormedRequirementsBlock_isAccepted()
    {
        Rule rule = assertDoesNotThrow(
                () -> RuleScaffold.build(SpecLoader.loadFromString(GOOD), "AE"));
        assertNotNull(rule.getRequirements());
        assertNotNull(rule.getRequirements().getVariables());
        assertEquals(List.of("AESTDTC"), rule.getRequirements().getVariables().getAll());
        assertTrue(rule.getScope().getUnknownKeys().isEmpty(),
                "the control rule must carry no unbound Scope key of its own");
    }
}
