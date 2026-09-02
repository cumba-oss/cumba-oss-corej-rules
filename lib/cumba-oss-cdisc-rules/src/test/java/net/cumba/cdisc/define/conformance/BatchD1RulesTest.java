package net.cumba.cdisc.define.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.eval.RuleEvaluator;
import net.cumba.cdisc.define.conformance.eval.RuleResult;
import net.cumba.cdisc.define.conformance.report.ExecutionStatus;
import net.cumba.cdisc.define.conformance.rule.ConformanceRule;
import net.cumba.cdisc.define.conformance.rule.RuleRepository;
import net.cumba.cdisc.define.conformance.tree.ElementNode;
import net.cumba.cdisc.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/**
 * Batch D1 (variables core: ItemRef, ItemDef, ItemDef/Description, def:Origin, def:Origin
 * Description — sheet ids 66–79, 137–148, 154–161, 168/169, 238, 242, 243) against a clean and a
 * deliberately broken 2.1 fixture, plus a small 2.0 fixture for the version-split
 * {@code DEFINE-XML-0160-B} (the Define-XML 2.0 Origin Type term set). Loads only this batch's
 * rules (filtered by id) so concurrent batches adding files never move these assertions.
 */
class BatchD1RulesTest
{

    /** This batch's rule ids, including the two split companions (0159-B, 0160-B). */
    private static final Set<String> BATCH_IDS = Set.of(//
            "DEFINE-XML-0066", "DEFINE-XML-0068", "DEFINE-XML-0069", "DEFINE-XML-0070",
            "DEFINE-XML-0071", "DEFINE-XML-0072", "DEFINE-XML-0073", "DEFINE-XML-0074",
            "DEFINE-XML-0075", "DEFINE-XML-0077", "DEFINE-XML-0079", "DEFINE-XML-0137",
            "DEFINE-XML-0138", "DEFINE-XML-0139", "DEFINE-XML-0140", "DEFINE-XML-0141",
            "DEFINE-XML-0142", "DEFINE-XML-0143", "DEFINE-XML-0144", "DEFINE-XML-0145",
            "DEFINE-XML-0146", "DEFINE-XML-0147", "DEFINE-XML-0148", "DEFINE-XML-0154",
            "DEFINE-XML-0155", "DEFINE-XML-0158", "DEFINE-XML-0159", "DEFINE-XML-0159-B",
            "DEFINE-XML-0160", "DEFINE-XML-0160-B", "DEFINE-XML-0161", "DEFINE-XML-0168",
            "DEFINE-XML-0169", "DEFINE-XML-0238", "DEFINE-XML-0242", "DEFINE-XML-0243");

    /** The batch's 2.1-only rules ({@code Applicable_Versions: ["2.1"]}). */
    // 0140 joined this set when review-batch-d split it into 0140 (2.1) / 0140-B (2.0).
    private static final Set<String> V21_ONLY_IDS = Set.of(//
            "DEFINE-XML-0070", "DEFINE-XML-0075", "DEFINE-XML-0077", "DEFINE-XML-0140",
            "DEFINE-XML-0147", "DEFINE-XML-0154", "DEFINE-XML-0159", "DEFINE-XML-0159-B",
            "DEFINE-XML-0160", "DEFINE-XML-0161", "DEFINE-XML-0242", "DEFINE-XML-0243");

    /** The single 2.0-only rule (the 0160 version split's Define-XML 2.0 half). */
    private static final String V20_ONLY_ID = "DEFINE-XML-0160-B";

    /**
     * Expected finding counts on the 2.1 violations fixture: exactly one per rule — one dedicated
     * violator each (see the fixture header), while the compliant decoys stay silent.
     */
    private static final Map<String, Integer> EXPECTED_21 = BATCH_IDS.stream()
            .filter(id -> !V20_ONLY_ID.equals(id)).collect(Collectors.toMap(id -> id, _ -> 1));

    /** Surefire pins the CWD to target/test-cwd; anchor via projectBasedir (root pom). */
    private static final Path RULES_DIR = TestRules.CDISC;

    private static DocumentContext context(String aFixture, String aVersion)
    {
        try (InputStream in = BatchD1RulesTest.class.getResourceAsStream("/fixtures/" + aFixture))
        {
            org.w3c.dom.Document doc = DefineDomIo.parse(Objects.requireNonNull(in, aFixture));
            ElementNode root = ElementNodeBuilder.build(doc);
            return new DocumentContext(root, aVersion, null, null);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot load fixture " + aFixture, e);
        }
    }


    private static Map<String, RuleResult> evaluateBatch(DocumentContext aContext)
    {
        List<ConformanceRule> batch = RuleRepository.loadDirectory(RULES_DIR).stream()
                .filter(rule -> BATCH_IDS.contains(rule.ruleId())).toList();
        assertEquals(BATCH_IDS.size(), batch.size(), "batch-D1 rule files present");
        RuleEvaluator evaluator = new RuleEvaluator();
        return batch.stream().collect(Collectors.toMap(ConformanceRule::ruleId,
                rule -> evaluator.evaluate(rule, aContext)));
    }


    @Test
    void cleanFixtureProducesNoFindings()
    {
        Map<String, RuleResult> results = evaluateBatch(context("batch-d1-clean-21.xml", "2.1"));
        assertEquals(BATCH_IDS.size(), results.size());
        for (RuleResult result : results.values())
        {
            String ruleId = result.rule().ruleId();
            if (V20_ONLY_ID.equals(ruleId))
            {
                assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, result.status(),
                        ruleId + " status");
                continue;
            }
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            assertEquals(List.of(), result.findings(), ruleId + " findings");
        }
    }


    @Test
    void violationsFixtureFiresEachRuleExactlyOnce()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-d1-violations-21.xml", "2.1"));
        for (Map.Entry<String, Integer> entry : EXPECTED_21.entrySet())
        {
            RuleResult result = results.get(entry.getKey());
            assertEquals(ExecutionStatus.EXECUTED, result.status(), entry.getKey() + " status");
            assertEquals(entry.getValue().intValue(), result.findings().size(),
                    entry.getKey() + " finding count: " + result.findings());
        }
        assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, results.get(V20_ONLY_ID).status(),
                V20_ONLY_ID + " status under 2.1");
    }


    @Test
    void versionGateSkips21OnlyRulesOn20Documents()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-d1-violations-21.xml", "2.0"));
        for (String ruleId : V21_ONLY_IDS)
        {
            assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, results.get(ruleId).status(),
                    ruleId + " status under 2.0");
        }
        assertEquals(ExecutionStatus.EXECUTED, results.get(V20_ONLY_ID).status(),
                V20_ONLY_ID + " status under 2.0");
    }


    @Test
    void version20FixtureFiresThe20OriginTypeSplit()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-d1-violations-20.xml", "2.0"));
        RuleResult originType20 = results.get(V20_ONLY_ID);
        assertEquals(ExecutionStatus.EXECUTED, originType20.status());
        assertEquals(1, originType20.findings().size(),
                V20_ONLY_ID + " findings: " + originType20.findings());
        var finding = originType20.findings().get(0);
        assertTrue(finding.getMessage().contains("[Collected]"), finding.getMessage());
        assertTrue(finding.getXpath().contains("ItemDef[@OID='IT.B']"), finding.getXpath());
        // Every other 2.0-applicable rule executes cleanly; the 2.1-only rules are gated.
        for (RuleResult result : results.values())
        {
            String ruleId = result.rule().ruleId();
            if (V21_ONLY_IDS.contains(ruleId))
            {
                assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, result.status(),
                        ruleId + " status under 2.0");
            }
            else if (!V20_ONLY_ID.equals(ruleId))
            {
                assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
                assertEquals(List.of(), result.findings(), ruleId + " findings");
            }
        }
    }


    @Test
    void violatorsCarryTheExpectedMessagesAndLocations()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-d1-violations-21.xml", "2.1"));
        var orderNumber = results.get("DEFINE-XML-0066").findings().get(0);
        assertTrue(orderNumber.getMessage().contains("[1.5]"), orderNumber.getMessage());
        assertTrue(orderNumber.getXpath().contains("ItemGroupDef[@OID='IG.AE']"),
                orderNumber.getXpath());
        var duplicateKey = results.get("DEFINE-XML-0071").findings().get(0);
        assertTrue(duplicateKey.getMessage().contains("[1]"), duplicateKey.getMessage());
        var keyless = results.get("DEFINE-XML-0070").findings().get(0);
        assertTrue(keyless.getXpath().contains("ItemGroupDef[@OID='IG.KS']"), keyless.getXpath());
        var derivedNoMethod = results.get("DEFINE-XML-0073").findings().get(0);
        assertTrue(derivedNoMethod.getXpath().contains("ItemGroupDef[@OID='IG.AE']"),
                derivedNoMethod.getXpath());
        var danglingMethod = results.get("DEFINE-XML-0074").findings().get(0);
        assertTrue(danglingMethod.getMessage().contains("[MT.MISSING]"),
                danglingMethod.getMessage());
        var badDataType = results.get("DEFINE-XML-0140").findings().get(0);
        assertTrue(badDataType.getMessage().contains("[string]"), badDataType.getMessage());
        var valueLength = results.get("DEFINE-XML-0142").findings().get(0);
        assertTrue(valueLength.getMessage().contains("[200 vs 20]"), valueLength.getMessage());
        var noSas = results.get("DEFINE-XML-0147").findings().get(0);
        assertTrue(noSas.getXpath().contains("ItemRef"), noSas.getXpath());
        var noOrigin = results.get("DEFINE-XML-0154").findings().get(0);
        assertTrue(noOrigin.getXpath().contains("ItemDef[@OID='IT.AENOORIG']"),
                noOrigin.getXpath());
        var badOriginType = results.get("DEFINE-XML-0160").findings().get(0);
        assertTrue(badOriginType.getMessage().contains("[Recorded]"), badOriginType.getMessage());
        var badSource = results.get("DEFINE-XML-0161").findings().get(0);
        assertTrue(badSource.getMessage().contains("[CRO]"), badSource.getMessage());
        var notAcrf = results.get("DEFINE-XML-0159-B").findings().get(0);
        assertTrue(notAcrf.getMessage().contains("[LF.OTHER vs LF.ACRF]"), notAcrf.getMessage());
    }

}
