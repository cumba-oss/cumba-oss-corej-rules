package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionExpression;
import org.junit.jupiter.api.Test;

/**
 * Classifier branches from {@code plans/done/PLAN-generator-silencing-guard-hoist.md} §5.1: only a
 * top-level-conjunction {@code var_exists} guard for a non-dotted variable with a substantive use
 * elsewhere in the Check qualifies; disjunction-/negation-nested, {@code var_not_exists}, dotted,
 * and literal-value-only cases are left alone. Fixtures are authored in expression form — since
 * D121 (phase 7 of {@code PLAN-typed-expression-engine.md}) that is the only authorable form, and
 * the scan walks the parsed expression through {@code CheckExprUnits}.
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
        assertEquals(List.of("COMPLFL"),
                scan("""
                        {"expression":"var_exists(\\"COMPLFL\\") and COMPLFL not in [\\"Y\\", \\"N\\"]"}"""));
    }


    @Test
    void anyNestedGuard_kept() throws Exception
    {
        assertEquals(List.of(),
                scan("""
                        {"expression":"(var_exists(\\"EPOCH\\") and not empty(EPOCH)) or AESEV == \\"Y\\""}"""));
    }


    @Test
    void notExistsGuard_kept() throws Exception
    {
        assertEquals(List.of(), scan("""
                {"expression":"var_not_exists(\\"EPOCH\\") and not empty(AESEV)"}"""));
    }


    @Test
    void dottedGuard_kept() throws Exception
    {
        assertEquals(List.of(), scan("""
                {"expression":"var_exists(AE.AESDTH) and AE.AESDTH == \\"Y\\""}"""));
    }


    @Test
    void valueOperandUse_isSubstantive() throws Exception
    {
        // A value-operand use counts as a substantive use (here via a non-R-4.11a operator).
        assertEquals(List.of("EPOCH"), scan("""
                {"expression":"var_exists(\\"EPOCH\\") and USUBJID == EPOCH"}"""));
    }


    @Test
    void uniqueSetGuards_carveOutIsPositionBlind() throws Exception
    {
        // CORE-000213 class. Plan A D-8 (ruling 3, 2026-08-23): is_(not_)unique_set builds ONE
        // key tuple from every member and drops an absent one uniformly wherever it sits
        // (EC-53), so a guard on member 0 (EPOCH) is exactly as load-bearing as one on a
        // grouping key (DSSCAT) — NEITHER is flagged. (Before the collapse EPOCH was, on the
        // warrant of the wave-33-retired Python fork's _absent_unique_set_target short-circuit.)
        assertEquals(List.of(), scan("""
                {"expression":"var_exists(\\"EPOCH\\") and var_exists(\\"DSSCAT\\") and\
                 not is_unique_set([EPOCH, USUBJID, DSSCAT])"}"""));
        // The keyless shape (CDISC-AD0054): the sole member is the whole tuple — required.
        assertEquals(List.of(), scan("""
                {"expression":"var_exists(\\"USUBJID\\") and not is_unique_set([USUBJID])"}"""));
    }


    @Test
    void uniqueSetGuards_carveOutHiddenVars_reportsEveryMemberGuard() throws Exception
    {
        // The other half of the same Check: what the carve-out hides — since D-8, member 0's
        // guard too.
        assertEquals(List.of("EPOCH", "DSSCAT"),
                SilencingGuardScan.carveOutHiddenVars(MAPPER.readValue("""
                        {"expression":"var_exists(\\"EPOCH\\") and var_exists(\\"DSSCAT\\") and\
                         not is_unique_set([EPOCH, USUBJID, DSSCAT])"}""", CheckCondition.class)));
    }


    @Test
    void inconsistentAcrossDatasetGuards_carveOutStaysPositionAware() throws Exception
    {
        // The second arm of D-8 — the one that stops the collapse from being a blanket
        // disabling: is_inconsistent_across_dataset's target is the checked VALUE, not a key
        // member (§3.6 D-7), so its target guard (--TPT) is still silencing and IS flagged while
        // its grouping-key guard (VISITNUM) is still hidden.
        assertEquals(List.of("--TPT"), scan("""
                {"expression":"var_exists(\\"--TPT\\") and var_exists(\\"VISITNUM\\") and\
                 is_inconsistent_across_dataset(--TPT, keys=[VISITNUM])"}"""));
        assertEquals(List.of("VISITNUM"), SilencingGuardScan.carveOutHiddenVars(MAPPER.readValue("""
                {"expression":"var_exists(\\"--TPT\\") and var_exists(\\"VISITNUM\\") and\
                 is_inconsistent_across_dataset(--TPT, keys=[VISITNUM])"}""",
                CheckCondition.class)));
    }


    @Test
    void negatedTargetPositionGuard_kept() throws Exception
    {
        // Defensive branch (no corpus instance today): under a structural `not`, the all-False
        // not-applicable verdict inverts to an all-True over-fire, so the target guard is
        // load-bearing after all and must not be flagged.
        assertEquals(List.of(), scan("""
                {"expression":"var_exists(\\"EPOCH\\") and\
                 not (not is_unique_set([EPOCH, USUBJID]))"}"""));
    }


    @Test
    void literalValue_isNotSubstantive() throws Exception
    {
        // "SNOMED"-style: the guarded name only reappears as a literal value, not a variable use.
        assertEquals(List.of(), scan("""
                {"expression":"var_exists(\\"TSVCDREF\\") and OTHER == \\"TSVCDREF\\""}"""));
    }


    @Test
    void guardWithoutSubstantiveUse_kept() throws Exception
    {
        // A pure presence check (no other use of the variable) is the rule's substance, not a
        // silencing guard.
        assertEquals(List.of(), scan("""
                {"expression":"var_exists(\\"AESEV\\")"}"""));
    }


    @Test
    void multiVariableGuards_allHoisted() throws Exception
    {
        assertEquals(List.of("EPOCH", "DSSCAT"), scan("""
                {"expression":"var_exists(\\"EPOCH\\") and var_exists(\\"DSSCAT\\") and\
                 EPOCH == DSSCAT"}"""));
    }


    @Test
    void mixedRule_inconsistentAcrossDatasetOperand_kept() throws Exception
    {
        // CORE-000685 shape: VISITNUM's substantive uses are is_inconsistent_across_dataset
        // grouping operands — an absent comparator is dropped from the key there, so the guard
        // is required (R-4.11a) and the rule is correctly not flagged.
        assertEquals(List.of(), scan("""
                {"expression":"var_exists(\\"VISITNUM\\") and var_not_exists(\\"--TPTREF\\") and\
                 ((var_exists(\\"--TPT\\") and\
                 is_inconsistent_across_dataset(--TPT, keys=[--TPTNUM, VISITNUM])) or\
                 (var_exists(\\"--TPTNUM\\") and\
                 is_inconsistent_across_dataset(--TPTNUM, keys=[--TPT, VISITNUM])))"}"""));
    }


    @Test
    void mixedUse_groupingKeyPositionWins() throws Exception
    {
        // Used by BOTH a regular operator and an R-4.11a operator's GROUPING KEY: the guard must
        // stay (hoisting would still remove the only guard the unique-set key filter sees).
        assertEquals(List.of(), scan("""
                {"expression":"var_exists(\\"EPOCH\\") and not empty(EPOCH) and\
                 not is_unique_set([USUBJID, EPOCH])"}"""));
    }


    @Test
    void mixedUse_uniqueSetNamePositionProtectsToo() throws Exception
    {
        // Same shape with the guard on the uniqueness tuple's member 0: since D-8 that member is
        // a key like any other, so the guard stays protected despite the ordinary non-empty use.
        assertEquals(List.of(), scan("""
                {"expression":"var_exists(\\"EPOCH\\") and not empty(EPOCH) and\
                 not is_unique_set([EPOCH, USUBJID])"}"""));
    }


    @Test
    void mixedUse_inconsistentAcrossDatasetTargetPositionDoesNotProtect() throws Exception
    {
        // The position-aware arm that survives: is_inconsistent_across_dataset's TARGET owes no
        // protection, and the ordinary non-empty use makes the guard plainly silencing.
        assertEquals(List.of("EPOCH"), scan("""
                {"expression":"var_exists(\\"EPOCH\\") and not empty(EPOCH) and\
                 is_inconsistent_across_dataset(EPOCH, keys=[USUBJID])"}"""));
    }


    @Test
    void wildcardPairGuard_kept() throws Exception
    {
        // CDISC-AD0010 shape: a '*'-wildcard guard binds per WildcardExpander expansion pair
        // (some pairs complete, others missing the guarded twin) — a variable requirement gates
        // the whole rule and cannot express that, so the guard stays in the Check.
        assertEquals(List.of(), scan("""
                {"expression":"*FL == \\"Y\\" and var_exists(\\"*FN\\") and *FN != 1"}"""));
    }


    @Test
    void compositeNamesUse_isSubstantive() throws Exception
    {
        assertEquals(List.of("VISIT"), scan("""
                {"expression":"var_exists(\\"VISIT\\") and\
                 tuple(VISIT, VISITNUM) not in $tv_keys"}"""));
    }


    /**
     * The raise-side regex predicates ({@code is_numeric} et al.) classify as the
     * {@code matches_regex} they were raised from, so their reads count as substantive. (Successor
     * of the {@code Fix #256} legacy-vs-expression twin test: the operator-leaf form is
     * unauthorable since D121, so the expression half IS the contract now.)
     */
    @Test
    void raiseSidePredicateReads_areSubstantive() throws Exception
    {
        assertEquals(List.of("COMPLFL"), scan("""
                {"expression":"var_exists(COMPLFL) and is_numeric(COMPLFL)"}"""));
    }


    /**
     * ⭐ A construct with no v1 classification no longer blinds the scan — the unit walk emits it as
     * an opaque unit whose references still count as substantive uses. (Successor of the fail-loud
     * {@code Fix #256} arm: "cannot read this Check" is impossible by construction under a total
     * walk, and the over-approximation runs in the safe direction — it can only surface more
     * guards, never hide one.)
     */
    @Test
    void nativeOnlyConstructReads_areStillSubstantive() throws Exception
    {
        CheckCondition check = MAPPER.readValue(
                """
                        {"expression":"var_exists(COMPLFL) and between(char(COMPLFL), char(\\"B\\"), char(\\"Z\\"))"}""",
                CheckCondition.class);
        assertTrue(check instanceof CheckConditionExpression,
                "fixture must stay native through the deserializer, or the arm is untested");
        assertEquals(List.of("COMPLFL"), SilencingGuardScan.hoistableVars(check),
                "an opaque unit's reads must stay substantive — an empty result is the silent"
                        + " skip this scan's history warns about");
    }
}
