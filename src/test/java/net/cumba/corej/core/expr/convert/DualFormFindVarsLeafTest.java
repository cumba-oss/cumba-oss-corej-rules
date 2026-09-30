package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link DualFormCheckNode}'s {@code count(find_vars("<entry>")) == 0} presence leaf
 * ({@code PLAN-dynamic-column-functions} §2.8; review round 1 L3 restricted it, round 2 L2 pins the
 * restriction): an UNQUALIFIED, non-regex entry lowers to the legacy presence leaf, a qualified or
 * a {@code /regex/} entry stays unlowerable. {@code test_check_expr_find_vars_leaf.py} pins the
 * same three cases on the Python mirror, {@code check_expr.py}.
 */
class DualFormFindVarsLeafTest
{

    private static JsonNode lower(String expression)
    {
        ObjectNode check = JsonNodeFactory.instance.objectNode();
        check.put("expression", expression);
        return DualFormCheckNode.lowerOrNull(check);
    }


    @Test
    void anUnqualifiedTemplateLowersToTheAbsenceLeaf()
    {
        JsonNode leaf = lower("count(find_vars(\"TRTxxP\")) == 0");
        assertNotNull(leaf);
        assertEquals("TRTxxP", leaf.path("name").asText());
        assertEquals("var_not_exists", leaf.path("operator").asText());
        assertEquals("var_exists",
                lower("count(find_vars(\"TRTxxP\")) > 0").path("operator").asText());
    }


    @Test
    void aQualifiedOrRegexEntryStaysUnlowerable()
    {
        for (String entry : List.of("ADSL.TRTxxP", "/TRT.*P/", "ADSL./TRT[0-9]+P/"))
        {
            assertNull(lower("count(find_vars(\"" + entry + "\")) == 0"), entry);
        }
    }
}
