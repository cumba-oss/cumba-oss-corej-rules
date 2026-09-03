package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import net.cumba.corej.core.expr.convert.RulePackageConverter.ConversionReport;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RulePackageConverter} — since
 * {@code plans/done/PLAN-retire-corpus-transforms.md} phase 1 a pure carry-through: expression
 * leaves verbatim, level maps per level with each level's {@code Message} preserved, and any legacy
 * shape byte-faithful as-is.
 */
class RulePackageConverterTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void alreadyExpressionLeafCountsAsConverted() throws IOException
    {
        JsonNode node = MAPPER.readTree("{\"expression\":\"DTHFL == \\\"Y\\\"\"}");
        ConversionReport report = new ConversionReport();
        JsonNode out = RulePackageConverter.convertCheck(node, report);
        assertEquals(node, out);
        assertEquals(1, report.getConvertedLeaves());
        assertEquals(1, report.getTotalLeaves());
    }


    @Test
    void levelKeyedCheckCarriesPerLevelAndPreservesMessages() throws IOException
    {
        // Plan C §3.3 — a level map has no all/any/not/expression key; each level's condition is
        // carried independently and its own Message rides through.
        String check = """
                {"ERROR":{"expression":"DTHFL == \\"Y\\"","Message":"definitely wrong"},
                 "INFO":{"expression":"DTHDTC == \\"X\\""}}""";
        JsonNode node = MAPPER.readTree(check);
        ConversionReport report = new ConversionReport();
        JsonNode out = RulePackageConverter.convertCheck(node, report);

        assertEquals("DTHFL == \"Y\"", out.get("ERROR").get("expression").asText(),
                "each level's condition is carried independently");
        assertEquals("definitely wrong", out.get("ERROR").get("Message").asText(),
                "the level's own Message rides through");
        assertEquals("DTHDTC == \"X\"", out.get("INFO").get("expression").asText());
        assertFalse(out.get("INFO").has("Message"), "no Message is invented for a level");
        assertEquals(node, out, "a level map of expression leaves is carried byte-faithfully");

        assertEquals(2, report.getTotalLeaves(), "one leaf per level — no phantom");
        assertEquals(2, report.getConvertedLeaves());
    }


    @Test
    void legacyLeafIsLeftOldStyleVerbatim() throws IOException
    {
        // A legacy operator-leaf can still reach the converter from a foreign rule (rule editor,
        // CDISC-Library import). The retired conversion machinery no longer raises it — it is
        // kept byte-faithfully, which is always loadable.
        String check = """
                {"name":"PARAMCD","operator":"not_in","value":["A","B"],"value_is_literal":true}""";
        JsonNode node = MAPPER.readTree(check);
        ConversionReport report = new ConversionReport();
        JsonNode out = RulePackageConverter.convertCheck(node, report);

        assertFalse(out.has("expression"), "a legacy leaf is not converted any more");
        assertEquals(node, out, "kept leaf must be byte-faithful");
        assertEquals(1, report.getTotalLeaves());
        assertEquals(0, report.getConvertedLeaves());
    }


    @Test
    void legacyCompositeIsLeftOldStyleVerbatim() throws IOException
    {
        // Composites are legacy shapes too since the flip (zero in rules-src, zero in rules/):
        // carried as-is, children included — no combining, no partial fold.
        String check = """
                {"all":[
                  {"expression":"AGE > 18"},
                  {"name":"USUBJID","operator":"an_unmapped_operator"}
                ]}""";
        JsonNode node = MAPPER.readTree(check);
        ConversionReport report = new ConversionReport();
        JsonNode out = RulePackageConverter.convertCheck(node, report);

        assertEquals(node, out, "a composite is carried byte-faithfully, children included");
        assertEquals(1, report.getTotalLeaves(), "the composite counts as one visited shape");
        assertEquals(0, report.getConvertedLeaves());
    }


    @Test
    void packageStringCarriesEveryFieldThrough() throws IOException
    {
        String pkg = """
                {
                  "_links": {"self": {"href": "/x"}},
                  "rules": {
                    "r1": {
                      "Core": {"Id": "CORE-1"},
                      "Outcome": {"Message": "bad"},
                      "Operations": [{"id": "$op", "operator": "distinct"}],
                      "Check": {"expression": "DTHFL == \\"Y\\""},
                      "Precondition": {"expression": "AGE > 18"}
                    }
                  }
                }""";
        ConversionReport report = new ConversionReport();
        String converted = RulePackageConverter.convertPackageString(pkg, report);
        JsonNode out = MAPPER.readTree(converted);

        // Non-Check fields copied through unchanged.
        JsonNode rule = out.get("rules").get("r1");
        assertEquals("CORE-1", rule.get("Core").get("Id").asText());
        assertEquals("bad", rule.get("Outcome").get("Message").asText());
        assertEquals("$op", rule.get("Operations").get(0).get("id").asText());
        assertEquals("/x", out.get("_links").get("self").get("href").asText());

        // Check and Precondition carried verbatim.
        assertEquals("DTHFL == \"Y\"", rule.get("Check").get("expression").asText());
        assertEquals("AGE > 18", rule.get("Precondition").get("expression").asText());

        assertEquals(2, report.getConvertedLeaves());
        assertEquals(2, report.getTotalLeaves());
    }


    @Test
    void reportToStringIsInformative()
    {
        ConversionReport report = new ConversionReport();
        assertNotNull(report.toString());
        assertTrue(report.toString().contains("total="));
    }

}
