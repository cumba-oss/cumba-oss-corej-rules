package net.cumba.cdisc.define.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.eval.RuleEvaluator;
import net.cumba.cdisc.define.conformance.eval.RuleResult;
import net.cumba.cdisc.define.conformance.report.ExecutionStatus;
import net.cumba.cdisc.define.conformance.rule.ConformanceRule;
import net.cumba.cdisc.define.conformance.tree.ElementNode;
import net.cumba.cdisc.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/**
 * End-to-end pilot: the shipped pilot rules against a clean and a deliberately broken 2.1 fixture.
 * Locks the YAML shape and the evaluator contract for the batch-authoring phases.
 */
class PilotRulesTest
{

    private static DocumentContext context(String aFixture, String aVersion)
    {
        try (InputStream in = PilotRulesTest.class.getResourceAsStream("/fixtures/" + aFixture))
        {
            org.w3c.dom.Document doc = DefineDomIo
                    .parse(java.util.Objects.requireNonNull(in, aFixture));
            ElementNode root = ElementNodeBuilder.build(doc);
            return new DocumentContext(root, aVersion, null, null);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot load fixture " + aFixture, e);
        }
    }

    /** The nine pilot rules this test owns — the shipped corpus keeps growing around them. */
    private static final java.util.Set<String> PILOT_IDS = java.util.Set.of("DEFINE-XML-0002",
            "DEFINE-XML-0004", "DEFINE-XML-0006", "DEFINE-XML-0011", "DEFINE-XML-0017",
            "DEFINE-XML-0044", "DEFINE-XML-0063", "DEFINE-XML-0065", "DEFINE-XML-0230");

    private static Map<String, RuleResult> evaluateAll(DocumentContext aContext)
    {
        RuleEvaluator evaluator = new RuleEvaluator();
        return TestRules.load().stream().filter(rule -> PILOT_IDS.contains(rule.ruleId()))
                .collect(Collectors.toMap(ConformanceRule::ruleId,
                        rule -> evaluator.evaluate(rule, aContext)));
    }


    @Test
    void cleanFixtureProducesNoFindings()
    {
        Map<String, RuleResult> results = evaluateAll(context("pilot-clean-21.xml", "2.1"));
        assertEquals(PILOT_IDS.size(), results.size());
        for (RuleResult result : results.values())
        {
            assertEquals(ExecutionStatus.EXECUTED, result.status(),
                    result.rule().ruleId() + " status");
            assertEquals(List.of(), result.findings(), result.rule().ruleId() + " findings");
        }
    }


    @Test
    void violationsFixtureFiresEachPilotRule()
    {
        Map<String, RuleResult> results = evaluateAll(context("pilot-violations-21.xml", "2.1"));
        Map<String, Integer> expected = Map.of(//
                "DEFINE-XML-0002", 0, // ODM is present even in the broken fixture
                "DEFINE-XML-0004", 1, // CreationDateTime not ISO 8601
                "DEFINE-XML-0006", 1, // ODMVersion missing
                "DEFINE-XML-0011", 1, // two Study children
                "DEFINE-XML-0017", 1, // def:Context invalid
                "DEFINE-XML-0044", 1, // Collected/Investigator DocumentRef without PDFPageRef
                "DEFINE-XML-0063", 1, // duplicate xml:lang
                "DEFINE-XML-0065", 1, // dangling ItemOID
                "DEFINE-XML-0230", 1); // OID DUP.1 reused
        for (Map.Entry<String, Integer> entry : expected.entrySet())
        {
            RuleResult result = results.get(entry.getKey());
            assertEquals(ExecutionStatus.EXECUTED, result.status(), entry.getKey() + " status");
            assertEquals(entry.getValue().intValue(), result.findings().size(),
                    entry.getKey() + " finding count: " + result.findings());
        }
    }


    @Test
    void findingCarriesLocationAndRenderedMessage()
    {
        Map<String, RuleResult> results = evaluateAll(context("pilot-violations-21.xml", "2.1"));
        var finding = results.get("DEFINE-XML-0065").findings().get(0);
        assertEquals("DEFINE-XML-0065", finding.getRuleId());
        assertTrue(finding.getMessage().contains("IT.MISSING"), finding.getMessage());
        assertTrue(finding.getXpath().contains("ItemGroupDef[@OID='IG.DM']"), finding.getXpath());
    }


    @Test
    void versionGateSkips21OnlyRulesOn20Documents()
    {
        Map<String, RuleResult> results = evaluateAll(context("pilot-violations-21.xml", "2.0"));
        assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION,
                results.get("DEFINE-XML-0017").status());
        assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION,
                results.get("DEFINE-XML-0044").status());
        assertEquals(ExecutionStatus.EXECUTED, results.get("DEFINE-XML-0006").status());
    }

}
