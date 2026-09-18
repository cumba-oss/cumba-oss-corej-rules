package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every case here is drawn from a real corpus pair. The pairs named in the comments are the ones
 * that a naive comparison mis-buckets; see {@code plans/done/PLAN-rule-inventory.md} §2.2.
 */
class RuleFingerprintTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static JsonNode rule(String yaml)
    {
        try
        {
            return YAML.readTree(yaml);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------ α-rename


    /** FDA-SD1307's {@code $HLTSUBJI_N} vs PMDA-SD1307's {@code $HLTSUBJI_N_COUNT}. */
    @Test
    void operationIdsAreFreeNamesSoAlphaRenameMakesTheRulesEqual()
    {
        JsonNode fda = rule("""
                Operations:
                - id: "$HLTSUBJI_N"
                  operator: "record_count"
                  group: ["STUDYID"]
                Check:
                  all:
                  - name: "$HLTSUBJI_N"
                    operator: "greater_than_or_equal_to"
                    value: 1
                """);
        JsonNode pmda = rule("""
                Operations:
                - id: "$HLTSUBJI_N_COUNT"
                  operator: "record_count"
                  group: ["STUDYID"]
                Check:
                  all:
                  - name: "$HLTSUBJI_N_COUNT"
                    operator: "greater_than_or_equal_to"
                    value: 1
                """);
        assertEquals(RuleFingerprint.l1(fda, false), RuleFingerprint.l1(pmda, false));
    }


    /** The rename must reach Output_Variables too, or L3 charges a phantom OV divergence. */
    @Test
    void alphaRenameSubstitutesThroughOutputVariables()
    {
        JsonNode fda = rule("""
                Operations:
                - id: "$a"
                  operator: "record_count"
                Check:
                  all:
                  - name: "$a"
                    operator: "greater_than"
                    value: 1
                Outcome:
                  Message: "m"
                  Output_Variables: ["$a", "TSVAL"]
                """);
        JsonNode pmda = rule("""
                Operations:
                - id: "$a_count"
                  operator: "record_count"
                Check:
                  all:
                  - name: "$a_count"
                    operator: "greater_than"
                    value: 1
                Outcome:
                  Message: "m"
                  Output_Variables: ["$a_count", "TSVAL"]
                """);
        assertEquals(RuleFingerprint.l3(fda), RuleFingerprint.l3(pmda));
    }


    /** Two operations that compute different things must never collapse onto one synthetic id. */
    @Test
    void alphaRenameDoesNotMergeGenuinelyDifferentOperations()
    {
        JsonNode a = rule("""
                Operations:
                - id: "$x"
                  operator: "record_count"
                Check:
                  all:
                  - name: "$x"
                    operator: "greater_than"
                    value: 1
                """);
        JsonNode b = rule("""
                Operations:
                - id: "$x"
                  operator: "distinct"
                Check:
                  all:
                  - name: "$x"
                    operator: "greater_than"
                    value: 1
                """);
        assertNotEquals(RuleFingerprint.l1(a, false), RuleFingerprint.l1(b, false));
    }

    // ------------------------------------------------------- commutativity


    /** PMDA-SD1085 vs FDA-SD1085: same conjunction, operands swapped. */
    @Test
    void allOperandOrderIsIrrelevant()
    {
        JsonNode a = rule("""
                Check:
                  all:
                  - name: "--DY"
                    operator: "non_empty"
                  - name: "DM.RFSTDTC"
                    operator: "is_incomplete_date"
                """);
        JsonNode b = rule("""
                Check:
                  all:
                  - name: "DM.RFSTDTC"
                    operator: "is_incomplete_date"
                  - name: "--DY"
                    operator: "non_empty"
                """);
        assertEquals(RuleFingerprint.l1(a, false), RuleFingerprint.l1(b, false));
    }

    // ------------------------------------------------- redundant wrappers


    /** CORE-000021 ({@code all: [x]}) vs CDISC-CG0397 (bare leaf) — not a logic divergence. */
    @Test
    void redundantSingleChildWrapperCollapsesInTheBaseL1()
    {
        JsonNode wrapped = rule("""
                Check:
                  all:
                  - name: "--ORRES"
                    operator: "non_empty"
                  - all:
                    - name: "--STRESC"
                      operator: "empty"
                """);
        JsonNode bare = rule("""
                Check:
                  all:
                  - name: "--ORRES"
                    operator: "non_empty"
                  - name: "--STRESC"
                    operator: "empty"
                """);
        assertEquals(RuleFingerprint.l1(wrapped, false), RuleFingerprint.l1(bare, false));
    }

    // -------------------------------------------------------------- guards


    /**
     * An {@code exists} guard on a class-V target is inert, so guard-stripping equates the pair.
     */
    @Test
    void inertExistsGuardIsStrippedForAClassVTarget()
    {
        JsonNode guarded = rule("""
                Check:
                  all:
                  - name: "--DTC"
                    operator: "var_exists"
                  - name: "--DTC"
                    operator: "is_incomplete_date"
                """);
        JsonNode plain = rule("""
                Check:
                  all:
                  - name: "--DTC"
                    operator: "is_incomplete_date"
                """);
        assertNotEquals(RuleFingerprint.l1(guarded, false), RuleFingerprint.l1(plain, false));
        assertEquals(RuleFingerprint.l1(guarded, true), RuleFingerprint.l1(plain, true));
    }


    /** Class E: {@code empty} fires on an absent column in Java, so the guard is NOT inert. */
    @Test
    void existsGuardIsNeverStrippedForAClassETarget()
    {
        JsonNode guarded = rule("""
                Check:
                  all:
                  - name: "DTHDTC"
                    operator: "var_exists"
                  - name: "DTHDTC"
                    operator: "empty"
                """);
        JsonNode plain = rule("""
                Check:
                  all:
                  - name: "DTHDTC"
                    operator: "empty"
                """);
        assertNotEquals(RuleFingerprint.l1(guarded, true), RuleFingerprint.l1(plain, true));
    }


    /** Class D: drop-and-regroup fires the coarser check on an absent column. */
    @Test
    void existsGuardIsNeverStrippedForAClassDTarget()
    {
        JsonNode guarded = rule("""
                Check:
                  all:
                  - name: "--DECOD"
                    operator: "var_exists"
                  - name: "--DECOD"
                    operator: "is_inconsistent_across_dataset"
                    value: ["--TESTCD"]
                """);
        JsonNode plain = rule("""
                Check:
                  all:
                  - name: "--DECOD"
                    operator: "is_inconsistent_across_dataset"
                    value: ["--TESTCD"]
                """);
        assertNotEquals(RuleFingerprint.l1(guarded, true), RuleFingerprint.l1(plain, true));
    }


    /** An {@code exists} under {@code not} is {@code not_exists} — a predicate, never a guard. */
    @Test
    void existsUnderNotIsNotStripped()
    {
        JsonNode negated = rule("""
                Check:
                  all:
                  - not:
                      name: "--DTC"
                      operator: "var_exists"
                  - name: "--DTC"
                    operator: "is_incomplete_date"
                """);
        String stripped = RuleFingerprint.l1(negated, true);
        assertTrue(stripped.contains("var_exists"),
                "the negated exists must survive guard stripping");
    }


    /** A guard on a variable the Check never otherwise reads is a real predicate. */
    @Test
    void existsOnAnUnusedNameIsNotAGuard()
    {
        JsonNode node = rule("""
                Check:
                  all:
                  - name: "AESEQ"
                    operator: "var_exists"
                  - name: "AETERM"
                    operator: "non_empty"
                """);
        assertEquals(RuleFingerprint.l1(node, false), RuleFingerprint.l1(node, true));
    }

    // ------------------------------------------------------------ L2 / L3


    /**
     * A {@code Join_Type} difference is behavioural (R-7.6), not prose. ⚠ The originating corpus
     * instance was {@code PMDA-SD1340} vs {@code FDA-SD1340}; {@code D-TA-2} (2026-08-17) removed
     * PMDA's {@code left} and the pair is now {@code 0-identical}, so the fixture below is the only
     * remaining witness — which is why it is synthetic and stays.
     */
    @Test
    void matchDatasetsJoinTypeBelongsToL2()
    {
        JsonNode left = rule("""
                Scope:
                  Domains:
                    Include: ["AE"]
                Match_Datasets:
                - Join_Type: "left"
                  Keys: ["USUBJID"]
                  Name: "DM"
                """);
        JsonNode unspecified = rule("""
                Scope:
                  Domains:
                    Include: ["AE"]
                Match_Datasets:
                - Keys: ["USUBJID"]
                  Name: "DM"
                """);
        assertNotEquals(RuleFingerprint.l2(left), RuleFingerprint.l2(unspecified));
    }


    /** Scope include/exclude lists are sets; PMDA-SD1098's Classes differ only in order. */
    @Test
    void scopeListOrderIsIrrelevant()
    {
        JsonNode a = rule("""
                Scope:
                  Classes:
                    Include: ["INTERVENTIONS", "EVENTS", "FINDINGS"]
                """);
        JsonNode b = rule("""
                Scope:
                  Classes:
                    Include: ["FINDINGS", "INTERVENTIONS", "EVENTS"]
                """);
        assertEquals(RuleFingerprint.l2(a), RuleFingerprint.l2(b));
    }


    /**
     * ⭐ The <b>depth-2</b> case, and the only fixture-free guard on it.
     *
     * <p>
     * {@code sortArraysWithin} sorted arrays exactly one object level deep until 2026-08-24, which
     * was harmless for {@code Scope} — {@code l2} hands it {@code Scope[key]}, so
     * {@code Include: [...]} is already at level one — and wrong for {@code Requirements}, which it
     * is handed whole and whose facet lists live at {@code Variables.All}. Its Python twin
     * ({@code generate-inventory.py::_sort_lists_within}) sorts two levels, so the two halves
     * re-bucketed the corpus against each other the moment the block gained carriers (FDA-SD1047 /
     * CDISC-CG0111).
     * </p>
     *
     * <p>
     * ⚠ {@link #scopeListOrderIsIrrelevant()} cannot catch that: it drives a depth-<b>1</b>
     * {@code Scope.Classes.Include}, which the pre-fix code already sorted. The only other witness
     * is the corpus file {@code CDISC-CG0111.yaml} happening to list {@code --DOSTXT} before
     * {@code --DOSE} — alphabetise that list and the whole regression net goes vacuous in silence.
     * This case is that net: revert the recursion and every one of the three facets below reaches
     * {@code canon} in authored order, so the two L2 strings differ and this test reds.
     * </p>
     */
    @Test
    void requirementsFacetListOrderIsIrrelevant()
    {
        JsonNode a = rule("""
                Requirements:
                  Variables:
                    All: ["--DOSTXT", "--DOSE"]
                    Any: ["AGE", "AGETXT"]
                    None: ["ARMCD", "ACTARMCD"]
                """);
        JsonNode b = rule("""
                Requirements:
                  Variables:
                    All: ["--DOSE", "--DOSTXT"]
                    Any: ["AGETXT", "AGE"]
                    None: ["ACTARMCD", "ARMCD"]
                """);
        assertEquals(RuleFingerprint.l2(a), RuleFingerprint.l2(b),
                "Requirements facet lists live at depth 2, so L2 only ignores their order while"
                        + " sortArraysWithin recurses");
    }


    /** Output_Variables order is prescribed by R-9.7b, so L3 compares it as a set. */
    @Test
    void outputVariableOrderDoesNotChangeL3()
    {
        JsonNode a = rule("""
                Outcome:
                  Message: "m"
                  Output_Variables: ["AGE", "AGEU"]
                """);
        JsonNode b = rule("""
                Outcome:
                  Message: "m"
                  Output_Variables: ["AGEU", "AGE"]
                """);
        assertEquals(RuleFingerprint.l3(a), RuleFingerprint.l3(b));
    }


    /**
     * Ruling 6 of {@code PLAN-authoring-grammar-unique-set-and-output-exclusion}
     * ({@code Fix #354}): L3 fingerprints the POST-exclusion set — an {@code !X} token drops X and
     * is never itself a member, so two rules reaching the same reported set fingerprint identically
     * however they spelled it. The Java twin of {@code generate-inventory.py}'s
     * {@code apply_exclusions}.
     */
    @Test
    void exclusionTokensAreAppliedBeforeL3()
    {
        JsonNode plain = rule("""
                Outcome:
                  Message: "m"
                  Output_Variables: ["AGE"]
                """);
        JsonNode spelledWithExclusion = rule("""
                Outcome:
                  Message: "m"
                  Output_Variables: ["AGE", "AGEU", "!AGEU"]
                """);
        JsonNode tokenOnly = rule("""
                Outcome:
                  Message: "m"
                  Output_Variables: ["AGE", "!AGEU"]
                """);
        assertEquals(RuleFingerprint.l3(plain), RuleFingerprint.l3(spelledWithExclusion));
        assertEquals(RuleFingerprint.l3(plain), RuleFingerprint.l3(tokenOnly));
        assertFalse(RuleFingerprint.l3(tokenOnly).contains("!AGEU"),
                "the token itself is never a member");
    }


    /** The other arm: a genuinely different reported set still moves L3. */
    @Test
    void anExclusionThatChangesTheReportedSetStillMovesL3()
    {
        JsonNode both = rule("""
                Outcome:
                  Message: "m"
                  Output_Variables: ["AGE", "AGEU"]
                """);
        JsonNode excluded = rule("""
                Outcome:
                  Message: "m"
                  Output_Variables: ["AGE", "!AGEU"]
                """);
        assertNotEquals(RuleFingerprint.l3(both), RuleFingerprint.l3(excluded));
    }


    /**
     * {@code alphaRename} substitutes whole strings, so a {@code !$id} token is not renamed — the
     * exclusion must still drop the renamed {@code $__0} it names, or the unrenamed id would leak
     * into the fingerprint as phantom divergence.
     */
    @Test
    void excludedOperationIdIsDroppedDespiteAlphaRename()
    {
        JsonNode reported = rule("""
                Operations:
                - id: "$cnt"
                  operator: "record_count"
                Check:
                  expression: "$cnt > 1"
                Outcome:
                  Message: "m"
                  Output_Variables: ["AGE", "$cnt"]
                """);
        JsonNode excluded = rule("""
                Operations:
                - id: "$other_name"
                  operator: "record_count"
                Check:
                  expression: "$other_name > 1"
                Outcome:
                  Message: "m"
                  Output_Variables: ["AGE", "$other_name", "!$other_name"]
                """);
        JsonNode ageOnly = rule("""
                Operations:
                - id: "$third"
                  operator: "record_count"
                Check:
                  expression: "$third > 1"
                Outcome:
                  Message: "m"
                  Output_Variables: ["AGE"]
                """);
        assertEquals(RuleFingerprint.l3(ageOnly), RuleFingerprint.l3(excluded));
        assertNotEquals(RuleFingerprint.l3(reported), RuleFingerprint.l3(excluded));
        assertFalse(RuleFingerprint.l3(excluded).contains("$other_name"));
    }


    /** Executability belongs to a layer, or the drift gate can never see it change. */
    @Test
    void executabilityIsPartOfL3()
    {
        JsonNode a = rule("""
                Executability: "Fully Executable"
                Outcome:
                  Message: "m"
                """);
        JsonNode b = rule("""
                Executability: "Partially Executable"
                Outcome:
                  Message: "m"
                """);
        assertNotEquals(RuleFingerprint.l3(a), RuleFingerprint.l3(b));
    }


    @Test
    void descriptionIsL4AndNothingElse()
    {
        JsonNode a = rule("Description: \"one\"\n");
        JsonNode b = rule("Description: \"two\"\n");
        assertNotEquals(RuleFingerprint.l4(a), RuleFingerprint.l4(b));
        assertEquals(RuleFingerprint.l1(a, false), RuleFingerprint.l1(b, false));
    }

    // ------------------------------------------------------------ plumbing


    @Test
    void fingerprintExposesAllFourLayers()
    {
        JsonNode node = rule("""
                Description: "d"
                Executability: "Fully Executable"
                Sensitivity: "Record"
                Scope:
                  Domains:
                    Include: ["AE"]
                Check:
                  all:
                  - name: "AETERM"
                    operator: "non_empty"
                Outcome:
                  Message: "m"
                  Output_Variables: ["AETERM"]
                """);
        RuleFingerprint.Fingerprint fingerprint = RuleFingerprint.of(node);
        assertTrue(fingerprint.l1().contains("AETERM"));
        assertTrue(fingerprint.l2().contains("AE"));
        assertTrue(fingerprint.l3().contains("Record"));
        assertEquals("\"d\"", fingerprint.l4());
        assertEquals(fingerprint.l1(), fingerprint.l1NoGuards());
    }


    @Test
    void leavesWalksCompositesAndIgnoresOrder()
    {
        JsonNode node = rule("""
                Check:
                  any:
                  - all:
                    - name: "A"
                      operator: "var_exists"
                    - name: "B"
                      operator: "empty"
                  - name: "C"
                    operator: "non_empty"
                """);
        List<JsonNode> leaves = RuleFingerprint.leaves(node.get("Check"));
        assertEquals(3, leaves.size());
    }


    @Test
    void namesUsedIgnoresExistsLeavesAndLiteralValues()
    {
        JsonNode check = rule("""
                Check:
                  all:
                  - name: "GUARD"
                    operator: "var_exists"
                  - name: "TARGET"
                    operator: "equal_to"
                    value: "Y"
                    value_is_literal: true
                  - name: "OTHER"
                    operator: "equal_to"
                    value: "$op"
                """).get("Check");
        Set<String> used = RuleFingerprint.namesUsed(check);
        assertTrue(used.contains("TARGET"));
        assertTrue(used.contains("OTHER"));
        assertTrue(used.contains("$op"));
        assertTrue(!used.contains("GUARD"));
        assertTrue(!used.contains("Y"), "a literal value is not a name");
    }


    @Test
    void guardSensitiveNamesFindsClassEAndClassDTargets()
    {
        JsonNode check = rule("""
                Check:
                  all:
                  - name: "E_TARGET"
                    operator: "empty"
                  - name: "D_TARGET"
                    operator: "is_not_unique_set"
                    value: ["USUBJID"]
                  - name: "V_TARGET"
                    operator: "non_empty"
                """).get("Check");
        Set<String> sensitive = RuleFingerprint.guardSensitiveNames(check);
        assertEquals(Set.of("E_TARGET", "D_TARGET"), sensitive);
    }


    /**
     * Owner requirement #1 (2026-08-23): the single-list spelling {@code not is_unique_set([A, B,
     * …])} lowers — through {@code DualFormCheckNode.lowerCheckInPlace}, the surface that feeds
     * this inventory its leaf view — to a leaf whose {@code name} is member 0 (D-2), so
     * {@code guardSensitiveNames} still returns it. ⚠ Asserted NON-EMPTY: an empty return is the
     * silent failure mode the D-2 decision exists to prevent.
     */
    @Test
    void guardSensitiveNamesStillFindsMemberZeroOfTheSingleListSpelling()
    {
        JsonNode ruleNode = rule("""
                Check:
                  expression: "var_exists(EPOCH) and not is_unique_set([EPOCH, USUBJID, DSSCAT])"
                """);
        assertTrue(DualFormCheckNode.lowerCheckInPlace(ruleNode), "the expression must lower");
        JsonNode check = ruleNode.get("Check");
        assertTrue(check.has("all"), "lowered to an operator-leaf tree: " + check);
        Set<String> sensitive = RuleFingerprint.guardSensitiveNames(check);
        assertFalse(sensitive.isEmpty(), "⚠ an empty result is the silent failure mode");
        assertEquals(Set.of("EPOCH"), sensitive, "member 0 is the leaf name (D-2)");
        // The one-member and the $-ref shapes too.
        JsonNode one = rule("""
                Check:
                  expression: "not is_unique_set([USUBJID])"
                """);
        assertTrue(DualFormCheckNode.lowerCheckInPlace(one));
        assertEquals(Set.of("USUBJID"), RuleFingerprint.guardSensitiveNames(one.get("Check")));
    }


    @Test
    void canonSortsObjectKeysAndPreservesArrayOrder()
    {
        JsonNode node = rule("b: 2\na: 1\nlist: [3, 1, 2]\n");
        assertEquals("{\"a\":1,\"b\":2,\"list\":[3,1,2]}", RuleFingerprint.canon(node));
    }


    @Test
    void canonRendersMissingNodesAsNull()
    {
        assertEquals("null", RuleFingerprint.canon(null));
    }


    @Test
    void rulesWithoutOperationsOrCheckAreStillFingerprintable()
    {
        JsonNode node = rule("Description: \"no check\"\n");
        assertEquals("[]|null", RuleFingerprint.l1(node, false));
        // Three terms since PLAN-scope-requirements-split phase 1 item 6: Scope | Match_Datasets |
        // Requirements. ⚑ The phase-1 justification — "the added term is a CONSTANT while no rule
        // authors the block, so every L2 string shifts identically and rule-equivalence.yaml does
        // not move" — EXPIRED when the block shipped in phase 4, and is kept here only as history.
        // Measured 2026-08-25: 5 733 of the 14 416 rule records in the 56 shipped packages under
        // `lib/corej-cdisc-rules/rules/` carry `Requirements` (1 355 of their 3 799 distinct
        // Core.Ids), as do 1 161 of the 3 804 authored files under `rules-src/checks` — the
        // population this fingerprint is actually computed over. The term is load-bearing now: two
        // rules that agreed on L2 before the migration and differ in their required variables fall
        // into different equivalence classes. What this case still pins is only the SHAPE — a rule
        // authoring none of the three terms renders all three as `null`.
        assertEquals("null|null|null", RuleFingerprint.l2(node));
    }

    // ------------------------------------------------- Plan C §3.3 level maps


    /**
     * The whole shipped corpus authors a plain {@code Check:}, so the plain branch must render
     * exactly the pre-Plan-C string. Pinned literally, because "the partition did not move" is a
     * claim about this text and nothing else re-derives it.
     */
    @Test
    void aPlainCheckHashesExactlyAsItDidBeforeLevelMaps()
    {
        JsonNode node = rule("""
                Check:
                  expression: "not empty(AESER)"
                """);
        assertEquals("[]|{\"expression\":\"not empty(AESER)\"}", RuleFingerprint.l1(node, false));
    }


    /** L1 is the ladder-ordered map of the levels' conditions, not the strictest one alone. */
    @Test
    void aLevelMapHashesEveryDeclaredLevel()
    {
        JsonNode two = rule("""
                Severity: "Error"
                Check:
                  ERROR:
                    expression: "date(RFSTDTC) > RFENDTC"
                  INFO:
                    expression: "date(latest_possible(RFSTDTC)) > earliest_possible(RFENDTC)"
                """);
        JsonNode strictestOnly = rule("""
                Severity: "Error"
                Check:
                  ERROR:
                    expression: "date(RFSTDTC) > RFENDTC"
                """);
        assertNotEquals(RuleFingerprint.l1(strictestOnly, false), RuleFingerprint.l1(two, false),
                "a second level is a different executable rule");
        assertTrue(RuleFingerprint.l1(two, false).contains("INFO="),
                "the weaker level must be in the hash, not only the strictest");
    }


    /** Ladder order, never file order: {@code REJECT} → {@code ERROR} → {@code WARNING} → INFO. */
    @Test
    void levelsAreHashedStrictestFirstWhateverOrderTheyWereAuthoredIn()
    {
        JsonNode ladder = rule("""
                Check:
                  ERROR:
                    expression: "a > b"
                  INFO:
                    expression: "a >= b"
                """);
        JsonNode reversed = rule("""
                Check:
                  INFO:
                    expression: "a >= b"
                  ERROR:
                    expression: "a > b"
                """);
        assertEquals(RuleFingerprint.l1(ladder, false), RuleFingerprint.l1(reversed, false));
        assertTrue(RuleFingerprint.l1(ladder, false).indexOf("ERROR=") < RuleFingerprint
                .l1(ladder, false).indexOf("INFO="), "strictest first");
    }


    /** A level's {@code Message} is what the finding SAYS — L3's business, never L1's. */
    @Test
    void aPerLevelMessageMovesL3AndLeavesL1Alone()
    {
        JsonNode bare = rule("""
                Check:
                  ERROR:
                    expression: "a > b"
                """);
        JsonNode messaged = rule("""
                Check:
                  ERROR:
                    expression: "a > b"
                    Message: "start is after end"
                """);
        assertEquals(RuleFingerprint.l1(bare, false), RuleFingerprint.l1(messaged, false),
                "the two execute identically");
        assertNotEquals(RuleFingerprint.l3(bare), RuleFingerprint.l3(messaged),
                "but they do not report identically");
        assertTrue(RuleFingerprint.l3(messaged).contains("start is after end"));
    }


    /** A rule declaring no per-level message must hash L3 byte-identically to a plain rule's. */
    @Test
    void l3IsUnchangedForEveryRuleThatDeclaresNoPerLevelMessage()
    {
        JsonNode plain = rule("""
                Check:
                  expression: "a > b"
                Outcome:
                  Message: "m"
                """);
        JsonNode levelled = rule("""
                Check:
                  ERROR:
                    expression: "a > b"
                Outcome:
                  Message: "m"
                """);
        assertEquals(RuleFingerprint.l3(plain), RuleFingerprint.l3(levelled));
    }


    /** α-rename must reach INTO the levels, or a level map charges a phantom L1 divergence. */
    @Test
    void alphaRenameSubstitutesThroughEveryLevel()
    {
        JsonNode left = rule("""
                Operations:
                - id: "$N"
                  operator: "record_count"
                Check:
                  ERROR:
                    all:
                    - name: "$N"
                      operator: "greater_than"
                      value: 1
                  INFO:
                    all:
                    - name: "$N"
                      operator: "greater_than"
                      value: 0
                """);
        JsonNode right = rule("""
                Operations:
                - id: "$COUNT"
                  operator: "record_count"
                Check:
                  ERROR:
                    all:
                    - name: "$COUNT"
                      operator: "greater_than"
                      value: 1
                  INFO:
                    all:
                    - name: "$COUNT"
                      operator: "greater_than"
                      value: 0
                """);
        assertEquals(RuleFingerprint.l1(left, false), RuleFingerprint.l1(right, false));
    }


    /**
     * Only an ALL-level-keyed map is a level map. A mixed map and an unknown level name are load
     * errors, so no such rule reaches an inventory run; treating them as plain conditions is the
     * behaviour every well-formed rule already had.
     */
    @Test
    void onlyAnAllLevelKeyedMapCountsAsALevelMap()
    {
        assertNull(RuleFingerprint.levels(rule("Check:\n  expression: \"a > b\"\n").get("Check")),
                "a plain condition is not a level map");
        assertNull(RuleFingerprint
                .levels(rule("Check:\n  ERROR:\n    expression: \"x\"\n  expression: \"y\"\n")
                        .get("Check")),
                "a mixed map is not a level map");
        assertNull(
                RuleFingerprint
                        .levels(rule("Check:\n  FATAL:\n    expression: \"x\"\n").get("Check")),
                "an unknown level name is not a level map");
        assertEquals(List.of("ERROR", "INFO"), List.copyOf(RuleFingerprint.levels(rule("""
                Check:
                  INFO:
                    expression: "a >= b"
                  ERROR:
                    expression: "a > b"
                """).get("Check")).keySet()));
    }


    /** Every level must reach the leaf-keyed instruments lowered, exactly as a plain Check does. */
    @Test
    void dualFormLoweringReachesEveryLevelAndKeepsItsMessage()
    {
        JsonNode node = rule("""
                Check:
                  ERROR:
                    expression: "not empty(AESER)"
                  INFO:
                    expression: "not empty(AEDECOD)"
                    Message: "look at this one"
                """);
        assertTrue(DualFormCheckNode.lowerCheckInPlace(node));
        JsonNode check = node.get("Check");
        assertFalse(check.get("ERROR").has("expression"), "the ERROR level was lowered");
        assertFalse(check.get("INFO").has("expression"), "the INFO level was lowered too");
        assertEquals("look at this one", check.get("INFO").get("Message").asText(),
                "the level's Message survives the lowering");
    }


    /** The walk surface: one condition for a plain Check, one per level for a level map. */
    @Test
    void checkConditionsSpansEveryDeclaredLevel()
    {
        assertEquals(1,
                RuleFingerprint.checkConditions(rule("Check:\n  expression: \"a > b\"\n")).size());
        assertEquals(2, RuleFingerprint.checkConditions(rule("""
                Check:
                  ERROR:
                    expression: "a > b"
                  INFO:
                    expression: "a >= b"
                """)).size());
        assertEquals(List.of(), RuleFingerprint.checkConditions(rule("Description: \"none\"\n")));
    }


    /**
     * ⭐ {@code Source_Proposed} is INVISIBLE to every fingerprint layer — asserted, not argued
     * ({@code plans/done/PLAN-source-proposed-block.md} §4).
     *
     * <p>
     * The two nodes are the same rule, one carrying the block and one not. If any layer saw it, the
     * committed {@code rule-equivalence.yaml} partition would move the moment phase 3 seeds 114
     * rules, and a pure-provenance key would be reported as a semantic change on every one of them.
     * </p>
     *
     * <p>
     * ⚠ The mechanism, so a future edit knows what it may not break: {@link RuleFingerprint#of}
     * reads <b>named keys</b> off the root node, and every {@code fieldNames()} walk in the class
     * is on a <em>sub</em>-node. A whole-root walk added anywhere above would silently break this.
     * ⚠⚠ And "Java is inert" does not prove "Python is inert" — the two halves already diverge in
     * one known place — so the Python fingerprint
     * ({@code documentation/rule-inventory/generate-inventory.py}) is asserted separately, by run,
     * in phase 3, by diffing {@code rule-equivalence.yaml} across the seeding.
     * </p>
     */
    @Test
    void sourceProposedIsInvisibleToEveryFingerprintLayer()
    {
        String body = """
                Core:
                  Id: "FDA-SD9701"
                Description: "Raise an error when --OBJ is not populated."
                Scope:
                  Domains:
                    Include: ["AE"]
                Check:
                  all:
                  - name: "AEOBJ"
                    operator: "is_null"
                Outcome:
                  Message: "m"
                """;
        String proposal = """
                Source_Proposed:
                  Sheet_Issue: "#160"
                  FDA_Validator_Rule_Message: "AEOBJ must be populated"
                  FDA_Validator_Rule_Description: "..."
                  Domains: "AE"
                """;
        assertEquals(RuleFingerprint.of(rule(body)), RuleFingerprint.of(rule(body + proposal)));

        // Positive control: the same shape of edit to a key the fingerprint DOES read must move it,
        // or this assertion would pass on a fingerprint that reads nothing at all.
        assertNotEquals(RuleFingerprint.of(rule(body)),
                RuleFingerprint.of(rule(body.replace("is_null", "is_not_null"))));
    }
}
