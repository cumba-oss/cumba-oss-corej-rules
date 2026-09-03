package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * serialized {@code Check} / {@code Precondition} subtrees must surface in expression notation
 * regardless of the notation the rule was loaded from (the deserializer lowers
 * {@code {"expression": …}} to the legacy {@code CheckCondition} tree, so a plain
 * {@link RulePackageLoader#toJson(Rule)} emits the legacy form). The raise is whole-tree
 * ({@code CheckToExpr}, since {@code PLAN-retire-corpus-transforms.md} phase 1): a subtree
 * containing any leaf with no expression surface stays old-style in its entirety.
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
        // Loaded from expression notation, lowered to the legacy tree on load — the expression
        // rendering must reproduce the original text.
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0001","Status":"Draft","Version":"1"},
                 "Check":{"expression":"var_not_exists(ADSL)"}}""");
        assertEquals("var_not_exists(\"ADSL\")", node.get("Check").get("expression").asText());
    }


    @Test
    void legacyNotationConvertsToExpression() throws IOException
    {
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0002","Status":"Draft","Version":"1"},
                 "Check":{"all":[
                   {"name":"DTHFL","operator":"equal_to","value":"Y","value_is_literal":true},
                   {"name":"AGE","operator":"greater_than","value":18,"value_is_literal":true}
                 ]}}""");
        assertEquals("DTHFL == \"Y\" and AGE > 18", node.get("Check").get("expression").asText());
        assertFalse(node.get("Check").has("all"));
    }


    @Test
    void nonRaisableLeafStaysOldStyle() throws IOException
    {
        // not_in has no expression surface (enum-absent operator) — kept old-style.
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0003","Status":"Draft","Version":"1"},
                 "Check":{"name":"PARAMCD","operator":"not_in","value":["A","B"],
                          "value_is_literal":true}}""");
        assertFalse(node.get("Check").has("expression"));
        assertEquals("not_in", node.get("Check").get("operator").asText());
        assertEquals("PARAMCD", node.get("Check").get("name").asText());
    }


    @Test
    void nativeOnlyExpressionKeptVerbatim() throws IOException
    {
        // A var_* accessor with an arbitrary-literal name has no v1 lowering, so the model keeps
        // the CheckConditionExpression with its source — the rendering must pass it through.
        JsonNode node = expressionTree(
                """
                        {"Core":{"Id":"T-0004","Status":"Draft","Version":"1"},
                         "Check":{"expression":"var_label(\\"AESTDTC\\", \\"DEFINE\\") == \\"Start Date\\""}}""");
        assertEquals("var_label(\"AESTDTC\", \"DEFINE\") == \"Start Date\"",
                node.get("Check").get("expression").asText());
    }


    @Test
    void preconditionConvertsToExpression() throws IOException
    {
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0005","Status":"Draft","Version":"1"},
                 "Precondition":{"name":"DTHFL","operator":"equal_to","value":"Y",
                                 "value_is_literal":true},
                 "Check":{"expression":"var_not_exists(ADSL)"}}""");
        assertEquals("DTHFL == \"Y\"", node.get("Precondition").get("expression").asText());
        assertEquals("var_not_exists(\"ADSL\")", node.get("Check").get("expression").asText());
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
    void mixedCompositeStaysOldStyleWholesale() throws IOException
    {
        // One raisable leaf and one without an expression surface: the raise is whole-tree, so
        // the composite is kept old-style in its entirety (the per-leaf partial fold retired
        // with the conversion machinery — PLAN-retire-corpus-transforms.md phase 1).
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0007","Status":"Draft","Version":"1"},
                 "Check":{"all":[
                   {"name":"DTHFL","operator":"equal_to","value":"Y","value_is_literal":true},
                   {"name":"PARAMCD","operator":"not_in","value":["A","B"],
                    "value_is_literal":true}
                 ]}}""");
        assertFalse(node.get("Check").has("expression"), "no whole-tree surface exists");
        JsonNode parts = node.get("Check").get("all");
        assertEquals(2, parts.size());
        assertFalse(parts.get(0).has("expression"), "no partial fold any more");
        assertEquals("equal_to", parts.get(0).get("operator").asText());
        assertEquals("not_in", parts.get(1).get("operator").asText());
    }


    @Test
    void emptyAllSkeletonIsPreserved() throws IOException
    {
        // The unimplemented-rule placeholder shape (Check: {"all": []}) must survive unchanged.
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0008","Status":"Draft","Version":"1"},
                 "Check":{"all":[]}}""");
        assertTrue(node.get("Check").has("all"));
        assertEquals(0, node.get("Check").get("all").size());
        assertFalse(node.get("Check").has("expression"));
    }


    @Test
    void notWrapperConvertsToExpression() throws IOException
    {
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0009","Status":"Draft","Version":"1"},
                 "Check":{"not":{"name":"DTHFL","operator":"equal_to","value":"Y",
                                 "value_is_literal":true}}}""");
        assertFalse(node.get("Check").has("not"));
        assertEquals("not DTHFL == \"Y\"", node.get("Check").get("expression").asText());
    }


    @Test
    void fieldFormOperationConvertsToExpression() throws IOException
    {
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0010","Status":"Draft","Version":"1"},
                 "Operations":[{"id":"$VAR","operator":"variable_count","name":"--LNKGRP"}],
                 "Check":{"expression":"var_not_exists(ADSL)"}}""");
        JsonNode op = node.get("Operations").get(0);
        assertEquals("$VAR", op.get("id").asText());
        assertEquals("variable_count(--LNKGRP)", op.get("expression").asText());
        assertFalse(op.has("operator"));
        assertFalse(op.has("name"));
    }


    @Test
    void expressionFormOperationRoundTrips() throws IOException
    {
        // Authored in expression form, lowered to field form on load — the rendering must
        // reproduce the original function-call text.
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0011","Status":"Draft","Version":"1"},
                 "Operations":[{"id":"$VAR","expression":"variable_count(--LNKGRP)"}],
                 "Check":{"expression":"var_not_exists(ADSL)"}}""");
        JsonNode op = node.get("Operations").get(0);
        assertEquals("variable_count(--LNKGRP)", op.get("expression").asText());
        assertFalse(op.has("operator"));
    }


    @Test
    void constantOperationQuotesName() throws IOException
    {
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0012","Status":"Draft","Version":"1"},
                 "Operations":[{"id":"$C","operator":"constant","name":"5"}],
                 "Check":{"expression":"var_not_exists(ADSL)"}}""");
        assertEquals("constant(\"5\")", node.get("Operations").get(0).get("expression").asText());
    }


    @Test
    void operationWithFilterRoundTrips() throws IOException
    {
        JsonNode node = expressionTree(
                """
                        {"Core":{"Id":"T-0013","Status":"Draft","Version":"1"},
                         "Operations":[{"id":"$RC","expression":"record_count(filter=filter(TSPARMCD=\\"INDIC\\"))"}],
                         "Check":{"expression":"var_not_exists(ADSL)"}}""");
        assertEquals("record_count(filter=filter(TSPARMCD=\"INDIC\"))",
                node.get("Operations").get(0).get("expression").asText());
    }


    @Test
    void ruleWithoutOperationsIsUnaffected() throws IOException
    {
        JsonNode node = expressionTree("""
                {"Core":{"Id":"T-0014","Status":"Draft","Version":"1"},
                 "Check":{"expression":"var_not_exists(ADSL)"}}""");
        assertTrue(node.get("Operations") == null || node.get("Operations").isNull());
        assertEquals("var_not_exists(\"ADSL\")", node.get("Check").get("expression").asText());
    }

}
