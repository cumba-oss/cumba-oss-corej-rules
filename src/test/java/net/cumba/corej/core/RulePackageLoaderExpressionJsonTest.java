package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import net.cumba.corej.core.expr.convert.RulePackageExpressionJson;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * {@link net.cumba.corej.core.expr.convert.RulePackageExpressionJson#toExpressionJson(Rule)}: the
 * serialized {@code Check} / {@code Precondition} subtrees must surface in expression notation.
 *
 * <p>
 * ⭐ <b>An {@code expression:} subtree is carried through VERBATIM</b> — phase 7 of
 * {@code PLAN-typed-expression-engine.md}. The deserializer no longer lowers {@code {"expression":
 * …}} to a legacy tree, so the rendering is the authored text rather than
 * {@code print(raise(lower(parse(s))))}.
 * </p>
 *
 * <p>
 * ⛔ <b>Phase 7d (D121) retired the operator-leaf {@code Check} form outright</b>, and with it the
 * <em>conversion</em> this class was originally about. {@code CheckConditionDeserializer} now
 * refuses a node carrying {@code operator:}, so no {@link Rule} reaching {@code toExpressionJson}
 * can carry a leaf, and three tests went with the capability:
 * </p>
 * <ul>
 * <li>{@code legacyNotationConvertsToExpression} — a legacy leaf tree converted to expression
 * notation;</li>
 * <li>{@code nonRaisableLeafStaysOldStyle} — an {@code not_in} leaf with no expression surface kept
 * old-style;</li>
 * <li>{@code mixedCompositeStaysOldStyleWholesale} — one raisable and one non-raisable leaf, kept
 * old-style in their entirety.</li>
 * </ul>
 * <p>
 * Re-pointing them at the new contract (the loud load-time rejection) would have duplicated, at
 * this converter, a contract the loader owns and the engine already tests; they were dropped rather
 * than re-aimed. What survives is the raise over the <em>composites</em>, which is still live:
 * {@link #notWrapperConvertsToExpression} and {@link #preconditionConvertsToExpression} each author
 * an {@code all:} / {@code not:} wrapper over {@code expression:} nodes — a shape with no
 * expression text of its own, so a rendered {@code expression} can only come from
 * {@code CheckToExpr} plus {@code ExpressionPrinter} rather than from pass-through.
 * </p>
 *
 * <p>
 * ⚠ <b>One arm lost its only coverage:</b> {@code raiseSubtree}'s
 * {@code catch (RuntimeException | IOException)} fallback — "no full expression surface, keep
 * old-style byte-faithful" — is now unreachable from <em>any</em> rule, loadable or not.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>CORRECTED 2026-09-17 (D121 / L5).</b> This paragraph used to say the one shape
 * {@code CheckToExpr} still refuses is {@code CheckConditionConstant}. <b>That type no longer
 * exists</b> — it was deleted for having no producer anywhere, which also made
 * {@code RuleRunner.referencesOperandPrefix} exhaustive over the sealed {@code CheckCondition}. So
 * {@code CheckToExpr.toExpr} now has <em>no throw site at all</em>, and the fallback is unreachable
 * for a strictly stronger reason than the one recorded here before. The empty-composite skeleton
 * {@code {"all": []}} is a <b>loud load error</b> since H3/D132 and reaches the renderer only
 * programmatically, through the separate {@code printed.isBlank()} arm that
 * {@link #emptyAllSkeletonIsPreserved} covers. The fallback is defensive code in the engine
 * repository, not this repo's to remove.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>{@code toExpressionJson} is production</b> — the REST client's {@code RuleDefsAssembler}
 * consumes it — so the phase-7 change was <b>measured across the shipped corpus</b> before these
 * expectations were touched, rendering all 58 packages under the engine's {@code 7e0f98b} and under
 * {@code @b048aa9} and diffing the canonicalised JSON per rule: <b>397 of 14 937 rules render
 * differently, across 90 distinct rule ids, and 397 of 397 are ONE shape</b> — the authored
 * {@code date(…)} / {@code time(…)} type tag now survives where the one-{@code name}/{@code value}
 * v1 leaf could not hold it ({@code date_part(*DTM) != *DT} becomes the authored
 * {@code date_part(date(*DTM)) != date(*DT)}). Stripping those wrappers from both sides makes every
 * pair identical, with <b>zero</b> residue; D102's six-operator differential already proved that
 * tag verdict-neutral. <b>No shipped rule changes the bare-vs-quoted spelling
 * {@link #expressionNotationRoundTrips} keys on</b>: the corpus writes a plain name QUOTED
 * ({@code var_exists("PARAMCD")}) and only a dotted reference bare, so the bare
 * {@code var_not_exists(ADSL)} authored here is a shape the corpus does not contain.
 * </p>
 */
class RulePackageLoaderExpressionJsonTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Rule loadRule(String ruleBody) throws IOException
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"x\":" + ruleBody + "}}");
        assertNotNull(pkg.getRules());
        assertEquals(1, pkg.getRules().size());
        return pkg.getRules().values().iterator().next();
    }


    private static JsonNode expressionTree(String ruleBody) throws IOException
    {
        return MAPPER.readTree(RulePackageExpressionJson.toExpressionJson(loadRule(ruleBody)));
    }


    @Test
    void expressionNotationRoundTrips() throws IOException
    {
        // ⭐ Loaded from expression notation and KEPT as it was written (phase 7) — the rendering
        // reproduces the original text exactly, which is what this test's name always claimed.
        // Before the phase the round-trip through the v1 leaf normalised the bare name into the
        // quoted literal `var_not_exists("ADSL")`; the corpus never authors a bare undotted name,
        // so no shipped rule moved with this (see the class javadoc's 397-of-14 937 measurement).
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0001","Status":"Draft","Version":"1"},
                 "Check":{"expression":"var_not_exists(ADSL)"}}""");
        assertEquals("var_not_exists(ADSL)", node.get("Check").get("expression").asText());
    }


    @Test
    void nativeOnlyExpressionKeptVerbatim() throws IOException
    {
        // A var_* accessor with two literal arguments — a shape the retired v1 leaf could not
        // hold. The model keeps the CheckConditionExpression with its source, quotes and all,
        // and the rendering must pass it through unchanged.
        JsonNode node = expressionTree(
                """
                        {"Core":{"Id":"T-0004","Status":"Draft","Version":"1"},
                         "Check":{"expression":"var_label(\\"AESTDTC\\", \\"DEFINE\\") == \\"Start Date\\""}}""");
        assertEquals("var_label(\"AESTDTC\", \"DEFINE\") == \"Start Date\"",
                node.get("Check").get("expression").asText());
    }


    /**
     * The {@code Precondition} field goes through the same raise as {@code Check} — the point of
     * the two-element loop in {@code toExpressionJson}. The Precondition is authored as a
     * <em>composite</em> on purpose: an {@code all:} of two {@code expression:} nodes carries no
     * expression text of its own, so a rendered {@code Precondition.expression} can only have come
     * from the raise actually running on that field. (Before phase 7d the field carried an operator
     * leaf, which made the same point.)
     */
    @Test
    void preconditionConvertsToExpression() throws IOException
    {
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0005","Status":"Draft","Version":"1"},
                 "Precondition":{"all":[{"expression":"DTHFL == \\"Y\\""},
                                        {"expression":"AGE > 18"}]},
                 "Check":{"expression":"var_not_exists(ADSL)"}}""");
        assertFalse(node.get("Precondition").has("all"));
        assertEquals("DTHFL == \"Y\" and AGE > 18",
                node.get("Precondition").get("expression").asText());
        // The Check is expression-form: kept verbatim since phase 7 — see
        // expressionNotationRoundTrips.
        assertEquals("var_not_exists(ADSL)", node.get("Check").get("expression").asText());
    }


    @Test
    void ruleWithoutCheckSerializes() throws IOException
    {
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0006","Status":"Draft","Version":"1"},
                 "Description":"no check yet"}""");
        assertTrue(node.get("Check") == null || node.get("Check").isNull());
        assertEquals("no check yet", node.get("Description").asText());
    }


    @Test
    void nullRuleSerializesAsNullLiteral()
    {
        assertEquals("null", RulePackageExpressionJson.toExpressionJson(null));
    }


    @Test
    void emptyAllSkeletonIsPreserved() throws IOException
    {
        // ⚠ RE-AIMED under D121/H3 (2026-09-17): the empty-composite placeholder ({"all": []})
        // is a LOUD LOAD ERROR now — an empty `all` compiles to the vacuous truth and fires on
        // every row, so CheckConditionDeserializer.compositeList rejects it (measured zero users
        // across all generated packages and fixtures before the rejection shipped). The shape
        // can therefore exist only PROGRAMMATICALLY (like the field-form operation below), and
        // that is what still exercises the renderer's printed.isBlank() arm: the skeleton must
        // survive the rendering unchanged rather than becoming a blank `expression`.
        net.cumba.corej.core.model.Rule rule = new net.cumba.corej.core.model.Rule();
        net.cumba.corej.core.model.RuleCore core = new net.cumba.corej.core.model.RuleCore();
        core.setId("T-0008");
        rule.setCore(core);
        rule.setCheck(new net.cumba.corej.core.model.CheckConditionAll(java.util.List.of()));
        JsonNode node = MAPPER.readTree(RulePackageExpressionJson.toExpressionJson(rule));
        assertTrue(node.get("Check").has("all"));
        assertEquals(0, node.get("Check").get("all").size());
        assertFalse(node.get("Check").has("expression"));
    }


    @Test
    void emptyAllSkeletonNoLongerLoads()
    {
        // The load-time half of the re-aim above, pinned where the old expectation lived so the
        // pair cannot silently drift: the JSON spelling of the placeholder is rejected by the
        // loader with the grammar's own message.
        Exception ex = assertThrows(Exception.class, () -> loadRule("""
                {"Core":{"Id":"T-0008","Status":"Draft","Version":"1"},
                 "Check":{"all":[]}}"""));
        assertTrue(ex.getMessage().contains("holds no conditions"),
                "the placeholder must fail load loudly: " + ex.getMessage());
    }


    /**
     * The {@code not:} composite still exists in the {@code Check:} grammar after phase 7d — only
     * its operator-leaf operand is gone. Wrapping an {@code expression:} node keeps the subject
     * intact: the wrapper has no expression text of its own, so the rendered
     * {@code not DTHFL == "Y"} is produced by {@code CheckToExpr}'s {@code Expr.Not} arm and
     * {@code ExpressionPrinter}, not carried through.
     */
    @Test
    void notWrapperConvertsToExpression() throws IOException
    {
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0009","Status":"Draft","Version":"1"},
                 "Check":{"not":{"expression":"DTHFL == \\"Y\\""}}}""");
        assertFalse(node.get("Check").has("not"));
        assertEquals("not DTHFL == \"Y\"", node.get("Check").get("expression").asText());
    }


    /**
     * Phase 7b: a field-form operation record can only exist <em>programmatically</em> (the
     * executor-internal carrier, e.g. a specialised rule) — the rendering still turns it back into
     * the authored {@code Bindings} shape.
     */
    @Test
    void programmaticFieldFormOperationConvertsToExpression() throws IOException
    {
        net.cumba.corej.core.model.Rule rule = new net.cumba.corej.core.model.Rule();
        net.cumba.corej.core.model.Operation op = new net.cumba.corej.core.model.Operation();
        op.setId("$VAR");
        op.setOperator("variable_count");
        op.setName("--LNKGRP");
        rule.setOperations(java.util.List.of(op));
        JsonNode node = MAPPER.readTree(RulePackageExpressionJson.toExpressionJson(rule));
        JsonNode entry = node.get("Bindings").get(0);
        assertEquals("$VAR", entry.get("name").asText());
        assertEquals("variable_count(--LNKGRP)", entry.get("expression").asText());
        assertFalse(entry.has("operator"));
    }


    @Test
    void expressionFormOperationRoundTrips() throws IOException
    {
        // Authored in expression form, lowered to field form on load — the rendering must
        // reproduce the original function-call text.
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0011","Status":"Draft","Version":"1"},
                 "Bindings":[{"name":"$VAR","expression":"variable_count(--LNKGRP)"}],
                 "Check":{"expression":"var_not_exists(ADSL)"}}""");
        JsonNode op = node.get("Bindings").get(0);
        assertEquals("variable_count(--LNKGRP)", op.get("expression").asText());
        assertFalse(op.has("operator"));
    }


    @Test
    void constantOperationQuotesName() throws IOException
    {
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0012","Status":"Draft","Version":"1"},
                 "Bindings":[{"name":"$C","expression":"constant(\\"5\\")"}],
                 "Check":{"expression":"var_not_exists(ADSL)"}}""");
        assertEquals("constant(\"5\")", node.get("Bindings").get(0).get("expression").asText());
    }


    @Test
    void operationWithFilterRoundTrips() throws IOException
    {
        JsonNode node = expressionTree(
                """
                        {"Core":{"Id":"T-0013","Status":"Draft","Version":"1"},
                         "Bindings":[{"name":"$RC","expression":"record_count(filter=filter(TSPARMCD=\\"INDIC\\"))"}],
                         "Check":{"expression":"var_not_exists(ADSL)"}}""");
        assertEquals("record_count(filter=filter(TSPARMCD=\"INDIC\"))",
                node.get("Bindings").get(0).get("expression").asText());
    }


    @Test
    void ruleWithoutOperationsIsUnaffected() throws IOException
    {
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0014","Status":"Draft","Version":"1"},
                 "Check":{"expression":"var_not_exists(ADSL)"}}""");
        assertTrue(node.get("Bindings") == null || node.get("Bindings").isNull());
        // Kept verbatim since phase 7 (see expressionNotationRoundTrips).
        assertEquals("var_not_exists(ADSL)", node.get("Check").get("expression").asText());
    }

}
