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
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import org.junit.jupiter.api.Test;

/**
 * Every case here is drawn from a real corpus pair. The pairs named in the comments are the ones
 * that a naive comparison mis-buckets; see {@code plans/done/PLAN-rule-inventory.md} §2.2.
 *
 * <p>
 * ⚑ The L1 cases take <b>expression</b> input since {@code PLAN-retire-check-lowering} (H5): L1 is
 * computed on the parsed {@link Expr}, and the operator-leaf fixtures these cases used to be
 * written in are a load error. Each property — free {@code $}-names, operand order, flattening,
 * class-aware guard stripping — was re-proven red-first against a canonicaliser with its sort
 * disabled before the switch.
 * </p>
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


    private static Expr parse(String expression)
    {
        return CheckExpressionParser.parse(expression);
    }

    // ------------------------------------------------------------ α-rename


    /** FDA-SD1307's {@code $HLTSUBJI_N} vs PMDA-SD1307's {@code $HLTSUBJI_N_COUNT}. */
    @Test
    void operationIdsAreFreeNamesSoAlphaRenameMakesTheRulesEqual()
    {
        JsonNode fda = rule("""
                Bindings:
                - name: "$HLTSUBJI_N"
                  expression: "record_count(group=[STUDYID])"
                Check:
                  expression: "$HLTSUBJI_N >= 1"
                """);
        JsonNode pmda = rule("""
                Bindings:
                - name: "$HLTSUBJI_N_COUNT"
                  expression: "record_count(group=[STUDYID])"
                Check:
                  expression: "$HLTSUBJI_N_COUNT >= 1"
                """);
        assertEquals(RuleFingerprint.l1(fda, false), RuleFingerprint.l1(pmda, false));
        assertTrue(RuleFingerprint.l1(fda, false).contains("$__0 >= 1"),
                "the renamed id reaches the printed Check: " + RuleFingerprint.l1(fda, false));
    }


    /** The rename must reach Output_Variables too, or L3 charges a phantom OV divergence. */
    @Test
    void alphaRenameSubstitutesThroughOutputVariables()
    {
        JsonNode fda = rule("""
                Bindings:
                - name: "$a"
                  expression: "record_count()"
                Check:
                  expression: "$a > 1"
                Outcome:
                  Message: "m"
                  Output_Variables: ["$a", "TSVAL"]
                """);
        JsonNode pmda = rule("""
                Bindings:
                - name: "$a_count"
                  expression: "record_count()"
                Check:
                  expression: "$a_count > 1"
                Outcome:
                  Message: "m"
                  Output_Variables: ["$a_count", "TSVAL"]
                """);
        assertEquals(RuleFingerprint.l3(fda), RuleFingerprint.l3(pmda));
    }


    /** Two bindings that compute different things must never collapse onto one synthetic id. */
    @Test
    void alphaRenameDoesNotMergeGenuinelyDifferentOperations()
    {
        JsonNode a = rule("""
                Bindings:
                - name: "$x"
                  expression: "record_count()"
                Check:
                  expression: "$x > 1"
                """);
        JsonNode b = rule("""
                Bindings:
                - name: "$x"
                  expression: "distinct(USUBJID)"
                Check:
                  expression: "$x > 1"
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
                  expression: "not empty(--DY) and is_incomplete_date(DM.RFSTDTC)"
                """);
        JsonNode b = rule("""
                Check:
                  expression: "is_incomplete_date(DM.RFSTDTC) and not empty(--DY)"
                """);
        assertEquals(RuleFingerprint.l1(a, false), RuleFingerprint.l1(b, false));
    }


    /** Disjunctions are commutative too, and sorting reaches a nested one. */
    @Test
    void anyOperandOrderIsIrrelevantAtAnyDepth()
    {
        JsonNode a = rule("""
                Check:
                  expression: "AETERM == \\"X\\" and (empty(AESEV) or empty(AEDECOD))"
                """);
        JsonNode b = rule("""
                Check:
                  expression: "(empty(AEDECOD) or empty(AESEV)) and AETERM == \\"X\\""
                """);
        assertEquals(RuleFingerprint.l1(a, false), RuleFingerprint.l1(b, false));
    }

    // ------------------------------------------------- flattening


    /** {@code a and (b and c)} is {@code a and b and c} — a grouping, not a logic divergence. */
    @Test
    void nestedConjunctionsFlattenInTheBaseL1()
    {
        JsonNode grouped = rule("""
                Check:
                  expression: "not empty(--ORRES) and (empty(--STRESC) and --ORRESU != \\"\\")"
                """);
        JsonNode flat = rule("""
                Check:
                  expression: "not empty(--ORRES) and empty(--STRESC) and --ORRESU != \\"\\""
                """);
        assertEquals(RuleFingerprint.l1(grouped, false), RuleFingerprint.l1(flat, false));
    }


    /** The canonical text, pinned literally: flattened, sorted by printed operand. */
    @Test
    void canonicalExpressionIsFlattenedAndSorted()
    {
        assertEquals("A > 1 and B > 2 and C > 3",
                RuleFingerprint.canonicalExpression(parse("(C > 3 and A > 1) and B > 2"), false));
        assertEquals("not (A > 1 or B > 2)",
                RuleFingerprint.canonicalExpression(parse("not (B > 2 or A > 1)"), false));
        assertEquals("empty(X) or not empty(Y)",
                RuleFingerprint.canonicalExpression(parse("(not empty(Y)) or (empty(X))"), false));
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
                  expression: "var_exists(--DTC) and is_incomplete_date(--DTC)"
                """);
        JsonNode plain = rule("""
                Check:
                  expression: "is_incomplete_date(--DTC)"
                """);
        assertNotEquals(RuleFingerprint.l1(guarded, false), RuleFingerprint.l1(plain, false));
        assertEquals(RuleFingerprint.l1(guarded, true), RuleFingerprint.l1(plain, true));
    }


    /** The quoted and the bare spelling of the guarded name are one guard. */
    @Test
    void aQuotedExistsOperandIsTheSameGuard()
    {
        JsonNode quoted = rule("""
                Check:
                  expression: "var_exists(\\"--DTC\\") and is_incomplete_date(--DTC)"
                """);
        JsonNode plain = rule("""
                Check:
                  expression: "is_incomplete_date(--DTC)"
                """);
        assertEquals(RuleFingerprint.l1(quoted, true), RuleFingerprint.l1(plain, true));
    }


    /** Class E: {@code empty} fires on an absent column, so the guard is NOT inert. */
    @Test
    void existsGuardIsNeverStrippedForAClassETarget()
    {
        JsonNode guarded = rule("""
                Check:
                  expression: "var_exists(DTHDTC) and empty(DTHDTC)"
                """);
        JsonNode plain = rule("""
                Check:
                  expression: "empty(DTHDTC)"
                """);
        assertNotEquals(RuleFingerprint.l1(guarded, true), RuleFingerprint.l1(plain, true));
    }


    /** Class D: drop-and-regroup fires the coarser check on an absent column. */
    @Test
    void existsGuardIsNeverStrippedForAClassDTarget()
    {
        JsonNode guarded = rule(
                """
                        Check:
                          expression: "var_exists(--DECOD) and is_inconsistent_across_dataset(--DECOD, keys=[--TESTCD])"
                        """);
        JsonNode plain = rule("""
                Check:
                  expression: "is_inconsistent_across_dataset(--DECOD, keys=[--TESTCD])"
                """);
        assertNotEquals(RuleFingerprint.l1(guarded, true), RuleFingerprint.l1(plain, true));
    }


    /** An {@code exists} under {@code not} is {@code not_exists} — a predicate, never a guard. */
    @Test
    void existsUnderNotIsNotStripped()
    {
        JsonNode negated = rule("""
                Check:
                  expression: "not var_exists(--DTC) and is_incomplete_date(--DTC)"
                """);
        String stripped = RuleFingerprint.l1(negated, true);
        assertTrue(stripped.contains("var_exists"),
                "the negated exists must survive guard stripping: " + stripped);
    }


    /** A guard inside a negated conjunction is not in positive polarity and stays. */
    @Test
    void aGuardUnderAStructuralNotIsNotStripped()
    {
        JsonNode node = rule("""
                Check:
                  expression: "not (var_exists(--DTC) and is_incomplete_date(--DTC))"
                """);
        assertEquals(RuleFingerprint.l1(node, false), RuleFingerprint.l1(node, true));
    }


    /** A guard on a variable the Check never otherwise reads is a real predicate. */
    @Test
    void existsOnAnUnusedNameIsNotAGuard()
    {
        JsonNode node = rule("""
                Check:
                  expression: "var_exists(AESEQ) and not empty(AETERM)"
                """);
        assertEquals(RuleFingerprint.l1(node, false), RuleFingerprint.l1(node, true));
    }


    /** A guard in a conjunction nested under a disjunction (positive polarity) is stripped too. */
    @Test
    void aGuardInAConjunctionUnderAnOrIsStripped()
    {
        JsonNode guarded = rule(
                """
                        Check:
                          expression: "empty(AESEV) or (var_exists(AESTDTC) and is_incomplete_date(AESTDTC))"
                        """);
        JsonNode plain = rule("""
                Check:
                  expression: "empty(AESEV) or is_incomplete_date(AESTDTC)"
                """);
        assertEquals(RuleFingerprint.l1(guarded, true), RuleFingerprint.l1(plain, true));
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
     * is handed whole and whose facet lists live at {@code Variables.All}. The pair FDA-SD1047 /
     * CDISC-CG0111 carried the same requirement in opposite entry order and bucketed differently.
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
     * however they spelled it.
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
                Bindings:
                - name: "$cnt"
                  expression: "record_count()"
                Check:
                  expression: "$cnt > 1"
                Outcome:
                  Message: "m"
                  Output_Variables: ["AGE", "$cnt"]
                """);
        JsonNode excluded = rule("""
                Bindings:
                - name: "$other_name"
                  expression: "record_count()"
                Check:
                  expression: "$other_name > 1"
                Outcome:
                  Message: "m"
                  Output_Variables: ["AGE", "$other_name", "!$other_name"]
                """);
        JsonNode ageOnly = rule("""
                Bindings:
                - name: "$third"
                  expression: "record_count()"
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
                  expression: "not empty(AETERM)"
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
    void namesUsedIgnoresPresenceCallsAndLiteralValues()
    {
        Set<String> used = RuleFingerprint.namesUsed(
                parse("var_exists(GUARD) and TARGET == \"Y\" and OTHER == $op and len(DM.AGE) > 1"
                        + " and var_label(\"DATA\") =~ /x/ and not empty(value())"));
        assertTrue(used.contains("TARGET"));
        assertTrue(used.contains("OTHER"));
        assertTrue(used.contains("$op"));
        assertTrue(used.contains("DM.AGE"), "a read inside a value call is a read");
        assertTrue(used.contains("variable_label"), "an accessor reads its metadata operand");
        assertTrue(used.contains("variable_value"), "the value() cursor reads variable_value");
        assertFalse(used.contains("GUARD"));
        assertFalse(used.contains("Y"), "a literal value is not a name");
    }


    @Test
    void guardSensitiveNamesFindsClassEAndClassDTargets()
    {
        Set<String> sensitive = RuleFingerprint.guardSensitiveNames(parse(
                "empty(ETARGET) and not is_unique_set([DTARGET, USUBJID]) and not empty(VTARGET)"));
        assertEquals(Set.of("ETARGET", "DTARGET"), sensitive);
    }


    /**
     * Owner requirement #1 (2026-08-23): the single-list spelling {@code not is_unique_set([A, B,
     * …])} names its first member (D-2), so {@code guardSensitiveNames} still returns it. ⚠
     * Asserted NON-EMPTY: an empty return is the silent failure mode the D-2 decision exists to
     * prevent.
     */
    @Test
    void guardSensitiveNamesStillFindsMemberZeroOfTheSingleListSpelling()
    {
        Set<String> sensitive = RuleFingerprint.guardSensitiveNames(
                parse("var_exists(EPOCH) and not is_unique_set([EPOCH, USUBJID, DSSCAT])"));
        assertFalse(sensitive.isEmpty(), "⚠ an empty result is the silent failure mode");
        assertEquals(Set.of("EPOCH"), sensitive, "member 0 is the unit's name (D-2)");
        assertEquals(Set.of("USUBJID"),
                RuleFingerprint.guardSensitiveNames(parse("not is_unique_set([USUBJID])")));
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
        // `lib/corej-rules/rules/` carry `Requirements` (1 355 of their 3 799 distinct
        // Core.Ids), as do 1 161 of the 3 804 authored files under `rules-src/checks` — the
        // population this fingerprint is actually computed over. The term is load-bearing now: two
        // rules that agreed on L2 before the migration and differ in their required variables fall
        // into different equivalence classes. What this case still pins is only the SHAPE — a rule
        // authoring none of the three terms renders all three as `null`.
        assertEquals("null|null|null", RuleFingerprint.l2(node));
    }

    // ------------------------------------------------- Plan C §3.3 level maps


    /**
     * The plain branch is the canonical expression text behind the bindings. Pinned literally,
     * because "the partition did not move" is a claim about this text and nothing else re-derives
     * it. (Until {@code PLAN-retire-check-lowering} this pinned the lowered leaf JSON.)
     */
    @Test
    void aPlainCheckHashesToItsCanonicalExpressionText()
    {
        JsonNode node = rule("""
                Check:
                  expression: "not  empty( AESER )"
                """);
        assertEquals("[]|not empty(AESER)", RuleFingerprint.l1(node, false));
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
                    expression: "A > B"
                  INFO:
                    expression: "A >= B"
                """);
        JsonNode reversed = rule("""
                Check:
                  INFO:
                    expression: "A >= B"
                  ERROR:
                    expression: "A > B"
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
                    expression: "A > B"
                """);
        JsonNode messaged = rule("""
                Check:
                  ERROR:
                    expression: "A > B"
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
                  expression: "A > B"
                Outcome:
                  Message: "m"
                """);
        JsonNode levelled = rule("""
                Check:
                  ERROR:
                    expression: "A > B"
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
                Bindings:
                - name: "$N"
                  expression: "record_count()"
                Check:
                  ERROR:
                    expression: "$N > 1"
                  INFO:
                    expression: "$N > 0"
                """);
        JsonNode right = rule("""
                Bindings:
                - name: "$COUNT"
                  expression: "record_count()"
                Check:
                  ERROR:
                    expression: "$COUNT > 1"
                  INFO:
                    expression: "$COUNT > 0"
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
        assertNull(RuleFingerprint.levels(rule("Check:\n  expression: \"A > B\"\n").get("Check")),
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
                    expression: "A >= B"
                  ERROR:
                    expression: "A > B"
                """).get("Check")).keySet()));
    }


    /** The walk surface: one condition for a plain Check, one per level for a level map. */
    @Test
    void checkConditionsSpansEveryDeclaredLevel()
    {
        assertEquals(1,
                RuleFingerprint.checkConditions(rule("Check:\n  expression: \"A > B\"\n")).size());
        assertEquals(2, RuleFingerprint.checkConditions(rule("""
                Check:
                  ERROR:
                    expression: "A > B"
                  INFO:
                    expression: "A >= B"
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
                  expression: "empty(AEOBJ)"
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
                RuleFingerprint.of(rule(body.replace("empty(AEOBJ)", "not empty(AEOBJ)"))));
    }
}
