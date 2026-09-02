package net.cumba.cdisc.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionExpression;
import org.junit.jupiter.api.Test;

/**
 * Classifier branches from {@code plans/done/PLAN-generator-silencing-guard-hoist.md} §5.1: only a
 * top-level-{@code all} {@code exists} guard for a non-dotted variable with a substantive use
 * elsewhere in the Check qualifies; {@code any}/{@code not}-nested, {@code not_exists}, dotted, and
 * literal-value-only cases are left alone.
 */
class SilencingGuardScanTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> scan(String checkJson) throws Exception
    {
        return SilencingGuardScan.hoistableVars(MAPPER.readValue(checkJson, CheckCondition.class));
    }


    @Test
    void topLevelSilencingGuard_hoisted() throws Exception
    {
        assertEquals(List.of("COMPLFL"), scan("""
                {"all":[
                  {"name":"COMPLFL","operator":"var_exists"},
                  {"name":"COMPLFL","operator":"is_not_contained_by","value":["Y","N"]}]}"""));
    }


    @Test
    void anyNestedGuard_kept() throws Exception
    {
        assertEquals(List.of(), scan("""
                {"any":[
                  {"all":[
                    {"name":"EPOCH","operator":"var_exists"},
                    {"name":"EPOCH","operator":"non_empty"}]}]}"""));
    }


    @Test
    void notExistsGuard_kept() throws Exception
    {
        assertEquals(List.of(), scan("""
                {"all":[
                  {"name":"EPOCH","operator":"var_not_exists"},
                  {"name":"AESEV","operator":"non_empty"}]}"""));
    }


    @Test
    void dottedGuard_kept() throws Exception
    {
        assertEquals(List.of(),
                scan("""
                        {"all":[
                          {"name":"AE.AESDTH","operator":"var_exists"},
                          {"name":"AE.AESDTH","operator":"equal_to","value":"Y","value_is_literal":true}]}"""));
    }


    @Test
    void valueOperandUse_isSubstantive() throws Exception
    {
        // A value-list operand use counts as a substantive use (here via a non-R-4.11a operator).
        assertEquals(List.of("EPOCH"), scan("""
                {"all":[
                  {"name":"EPOCH","operator":"var_exists"},
                  {"name":"USUBJID","operator":"equal_to","value":"EPOCH"}]}"""));
    }


    @Test
    void uniqueSetGuards_carveOutIsPositionBlind() throws Exception
    {
        // CORE-000213 class. Plan A D-8 (ruling 3, 2026-08-23): is_(not_)unique_set builds ONE
        // key tuple from every member and drops an absent one uniformly wherever it sits
        // (EC-53), so a guard on the `name` (EPOCH) is exactly as load-bearing as one on a
        // grouping key (DSSCAT) — NEITHER is flagged. (Before the collapse EPOCH was, on the
        // warrant of the wave-33-retired Python fork's _absent_unique_set_target short-circuit.)
        assertEquals(List.of(),
                scan("""
                        {"all":[
                          {"name":"EPOCH","operator":"var_exists"},
                          {"name":"DSSCAT","operator":"var_exists"},
                          {"name":"EPOCH","operator":"is_not_unique_set","value":["USUBJID","DSSCAT"]}]}"""));
        // The keyless shape (CDISC-AD0054): the sole member is the whole tuple — required.
        assertEquals(List.of(), scan("""
                {"all":[
                  {"name":"USUBJID","operator":"var_exists"},
                  {"name":"USUBJID","operator":"is_not_unique_set"}]}"""));
    }


    @Test
    void uniqueSetGuards_carveOutHiddenVars_reportsEveryMemberGuard() throws Exception
    {
        // The other half of the same Check: what the carve-out hides — since D-8, the `name`
        // member's guard too.
        assertEquals(List.of("EPOCH", "DSSCAT"),
                SilencingGuardScan.carveOutHiddenVars(MAPPER.readValue(
                        """
                                {"all":[
                                  {"name":"EPOCH","operator":"var_exists"},
                                  {"name":"DSSCAT","operator":"var_exists"},
                                  {"name":"EPOCH","operator":"is_not_unique_set","value":["USUBJID","DSSCAT"]}]}""",
                        CheckCondition.class)));
    }


    @Test
    void inconsistentAcrossDatasetGuards_carveOutStaysPositionAware() throws Exception
    {
        // The second arm of D-8 — the one that stops the collapse from being a blanket
        // disabling: is_inconsistent_across_dataset's `name` is the checked VALUE, not a key
        // member (§3.6 D-7), so its target guard (--TPT) is still silencing and IS flagged while
        // its grouping-key guard (VISITNUM) is still hidden.
        assertEquals(List.of("--TPT"),
                scan("""
                        {"all":[
                          {"name":"--TPT","operator":"var_exists"},
                          {"name":"VISITNUM","operator":"var_exists"},
                          {"name":"--TPT","operator":"is_inconsistent_across_dataset","value":["VISITNUM"]}]}"""));
        assertEquals(List.of("VISITNUM"), SilencingGuardScan.carveOutHiddenVars(MAPPER.readValue(
                """
                        {"all":[
                          {"name":"--TPT","operator":"var_exists"},
                          {"name":"VISITNUM","operator":"var_exists"},
                          {"name":"--TPT","operator":"is_inconsistent_across_dataset","value":["VISITNUM"]}]}""",
                CheckCondition.class)));
    }


    @Test
    void negatedTargetPositionGuard_kept() throws Exception
    {
        // Defensive branch (no corpus instance today): under a `not`, the fork's all-False
        // not-applicable verdict inverts to an all-True over-fire, so the target guard is
        // load-bearing after all and must not be flagged.
        assertEquals(List.of(),
                scan("""
                        {"all":[
                          {"name":"EPOCH","operator":"var_exists"},
                          {"not":{"name":"EPOCH","operator":"is_not_unique_set","value":["USUBJID"]}}]}"""));
    }


    @Test
    void literalValue_isNotSubstantive() throws Exception
    {
        // "SNOMED"-style: the guarded name only reappears as a literal value, not a variable use.
        assertEquals(List.of(),
                scan("""
                        {"all":[
                          {"name":"TSVCDREF","operator":"var_exists"},
                          {"name":"OTHER","operator":"equal_to","value":"TSVCDREF","value_is_literal":true}]}"""));
    }


    @Test
    void guardWithoutSubstantiveUse_kept() throws Exception
    {
        // A pure presence check (no other use of the variable) is the rule's substance, not a
        // silencing guard.
        assertEquals(List.of(), scan("""
                {"all":[
                  {"name":"AESEV","operator":"var_exists"}]}"""));
    }


    @Test
    void multiVariableGuards_allHoisted() throws Exception
    {
        assertEquals(List.of("EPOCH", "DSSCAT"), scan("""
                {"all":[
                  {"name":"EPOCH","operator":"var_exists"},
                  {"name":"DSSCAT","operator":"var_exists"},
                  {"name":"EPOCH","operator":"equal_to","value":"DSSCAT"}]}"""));
    }


    @Test
    void mixedRule_inconsistentAcrossDatasetOperand_kept() throws Exception
    {
        // CORE-000685 shape: VISITNUM's substantive uses are is_inconsistent_across_dataset
        // grouping operands — Python drops absent comparators there, so the guard is required
        // (R-4.11a) and the rule is correctly not flagged.
        assertEquals(List.of(), scan("""
                {"all":[
                  {"name":"VISITNUM","operator":"var_exists"},
                  {"name":"--TPTREF","operator":"var_not_exists"},
                  {"any":[
                    {"all":[
                      {"name":"--TPT","operator":"var_exists"},
                      {"name":"--TPT","operator":"is_inconsistent_across_dataset",
                       "value":["--TPTNUM","VISITNUM"]}]},
                    {"all":[
                      {"name":"--TPTNUM","operator":"var_exists"},
                      {"name":"--TPTNUM","operator":"is_inconsistent_across_dataset",
                       "value":["--TPT","VISITNUM"]}]}]}]}"""));
    }


    @Test
    void mixedUse_groupingKeyPositionWins() throws Exception
    {
        // Used by BOTH a regular operator and an R-4.11a operator's GROUPING KEY: the guard must
        // stay (hoisting would still remove the only guard Python's unique-set path sees).
        assertEquals(List.of(), scan("""
                {"all":[
                  {"name":"EPOCH","operator":"var_exists"},
                  {"name":"EPOCH","operator":"non_empty"},
                  {"name":"USUBJID","operator":"is_not_unique_set","value":["EPOCH"]}]}"""));
    }


    @Test
    void mixedUse_uniqueSetNamePositionProtectsToo() throws Exception
    {
        // Same shape with the guard on the uniqueness leaf's `name`: since D-8 that member is a
        // key like any other, so the guard stays protected despite the ordinary non_empty use.
        assertEquals(List.of(), scan("""
                {"all":[
                  {"name":"EPOCH","operator":"var_exists"},
                  {"name":"EPOCH","operator":"non_empty"},
                  {"name":"EPOCH","operator":"is_not_unique_set","value":["USUBJID"]}]}"""));
    }


    @Test
    void mixedUse_inconsistentAcrossDatasetTargetPositionDoesNotProtect() throws Exception
    {
        // The position-aware arm that survives: is_inconsistent_across_dataset's TARGET owes no
        // protection, and the ordinary non_empty use makes the guard plainly silencing.
        assertEquals(List.of("EPOCH"),
                scan("""
                        {"all":[
                          {"name":"EPOCH","operator":"var_exists"},
                          {"name":"EPOCH","operator":"non_empty"},
                          {"name":"EPOCH","operator":"is_inconsistent_across_dataset","value":["USUBJID"]}]}"""));
    }


    @Test
    void wildcardPairGuard_kept() throws Exception
    {
        // CDISC-AD0010 shape: a '*'-wildcard guard binds per WildcardExpander expansion pair
        // (some pairs complete, others missing the guarded twin) — a variable requirement gates
        // the whole rule and cannot express that, so the guard stays in the Check.
        assertEquals(List.of(), scan("""
                {"all":[
                  {"name":"*FL","operator":"equal_to","value":"Y","value_is_literal":true},
                  {"name":"*FN","operator":"var_exists"},
                  {"name":"*FN","operator":"not_equal_to","value":1}]}"""));
    }


    @Test
    void compositeNamesUse_isSubstantive() throws Exception
    {
        assertEquals(List.of("VISIT"), scan("""
                {"all":[
                  {"name":"VISIT","operator":"var_exists"},
                  {"names":["VISIT","VISITNUM"],"operator":"is_not_contained_by",
                   "value":"$tv_keys"}]}"""));
    }


    /**
     * {@code Fix #256} (pre-flip re-point, {@code PLAN-rules-src-expression-flip.md} §7b): an
     * expression-form Check that the strict load-path lowering declines (here through the raised
     * {@code is_numeric} predicate, which has no v1 lowering) reaches the scan as a
     * {@code CheckConditionExpression} — and must be SCANNED, identically to its legacy-form
     * equivalent, not silently skipped. Before the arm this scan returned the empty list.
     * Unreachable on today's all-legacy corpus; it exists so the flip cannot make the two
     * silencing-guard ratchets vacuous.
     */
    @Test
    void expressionFormCheck_isScannedIdenticallyToItsLegacyForm() throws Exception
    {
        String expressionForm = """
                {"expression":"var_exists(COMPLFL) and is_numeric(COMPLFL)"}""";
        // The exact operator-leaf twin: conversion-mode lowering restores is_numeric(X) to the
        // matches_regex leaf CheckToExpr raised it from, value_is_literal set (ExprLowering
        // regexLeaf).
        String legacyForm = """
                {"all":[
                  {"name":"COMPLFL","operator":"var_exists"},
                  {"name":"COMPLFL","operator":"matches_regex",
                   "value":"^-?(0|[1-9]\\\\d*)(\\\\.\\\\d+)?$","value_is_literal":true}]}""";
        assertEquals(List.of("COMPLFL"), scan(expressionForm),
                "the expression-form Check must be scanned at all — an empty result is the"
                        + " pre-Fix-#256 silent skip");
        assertEquals(scan(legacyForm), scan(expressionForm),
                "the two forms of the same Check must scan identically");
    }


    /**
     * The fail-loud half of the {@code Fix #256} arm: a native-only expression — here a
     * hand-written {@code between(char(X), char("B"), char("Z"))}, whose non-canonical bounds have
     * no regex equivalent even in conversion mode ({@code ExprLowering.firstLetterRangeOperand}) —
     * must throw rather than be skipped, so the first such authored rule reds the ratchets instead
     * of silently leaving their population.
     */
    @Test
    void nativeOnlyExpression_failsLoudRatherThanBeingSkipped() throws Exception
    {
        CheckCondition check = MAPPER.readValue(
                """
                        {"expression":"var_exists(COMPLFL) and between(char(COMPLFL), char(\\"B\\"), char(\\"Z\\"))"}""",
                CheckCondition.class);
        assertTrue(check instanceof CheckConditionExpression,
                "fixture must stay native through the deserializer, or the throw is untested");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SilencingGuardScan.hoistableVars(check));
        assertTrue(ex.getMessage().contains("PLAN-rules-src-expression-flip"),
                "the failure must name the flip plan: " + ex.getMessage());
    }
}
