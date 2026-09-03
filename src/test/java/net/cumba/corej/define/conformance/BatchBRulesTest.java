package net.cumba.corej.define.conformance;

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
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.eval.RuleEvaluator;
import net.cumba.corej.define.conformance.eval.RuleResult;
import net.cumba.corej.define.conformance.report.ExecutionStatus;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.RuleRepository;
import net.cumba.corej.define.conformance.tree.ElementNode;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/**
 * Batch B (documents / annotations): sheet ids 38, 39, 42, 43, 45–50, 51, 54 against a clean and a
 * deliberately broken 2.1 fixture. Loads only this batch's rules (filtered by id) so concurrent
 * batches adding files never move these assertions.
 */
class BatchBRulesTest
{

    /** This batch's rule ids — pilot id 44 (also a DocumentRef rule) is deliberately absent. */
    private static final Set<String> BATCH_IDS = Set.of(//
            "DEFINE-XML-0038", "DEFINE-XML-0039", "DEFINE-XML-0042", "DEFINE-XML-0043",
            "DEFINE-XML-0045", "DEFINE-XML-0046", "DEFINE-XML-0047", "DEFINE-XML-0048",
            "DEFINE-XML-0049", "DEFINE-XML-0050", "DEFINE-XML-0051", "DEFINE-XML-0054");

    /** Surefire pins the CWD to target/test-cwd; anchor via projectBasedir (root pom). */
    private static final Path RULES_DIR = TestRules.CDISC;

    private static DocumentContext context(String aFixture, String aVersion)
    {
        try (InputStream in = BatchBRulesTest.class.getResourceAsStream("/fixtures/" + aFixture))
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
        assertEquals(BATCH_IDS.size(), batch.size(), "batch-B rule files present");
        RuleEvaluator evaluator = new RuleEvaluator();
        return batch.stream().collect(Collectors.toMap(ConformanceRule::ruleId,
                rule -> evaluator.evaluate(rule, aContext)));
    }


    @Test
    void cleanFixtureProducesNoFindings()
    {
        Map<String, RuleResult> results = evaluateBatch(context("batch-b-clean-21.xml", "2.1"));
        assertEquals(BATCH_IDS.size(), results.size());
        for (RuleResult result : results.values())
        {
            assertEquals(ExecutionStatus.EXECUTED, result.status(),
                    result.rule().ruleId() + " status");
            assertEquals(List.of(), result.findings(), result.rule().ruleId() + " findings");
        }
    }


    @Test
    void violationsFixtureFiresEachRuleExactlyOnce()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-b-violations-21.xml", "2.1"));
        for (String ruleId : BATCH_IDS)
        {
            RuleResult result = results.get(ruleId);
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            assertEquals(1, result.findings().size(),
                    ruleId + " finding count: " + result.findings());
        }
    }


    @Test
    void findingCarriesLocationAndRenderedMessage()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-b-violations-21.xml", "2.1"));
        var dangling = results.get("DEFINE-XML-0043").findings().get(0);
        assertTrue(dangling.getMessage().contains("LF.MISSING"), dangling.getMessage());
        assertTrue(dangling.getXpath().contains("DocumentRef"), dangling.getXpath());
        var badEnum = results.get("DEFINE-XML-0045").findings().get(0);
        assertTrue(badEnum.getMessage().contains("Type[Physical]"), badEnum.getMessage());
        var badInteger = results.get("DEFINE-XML-0047").findings().get(0);
        assertTrue(badInteger.getMessage().contains("FirstPage[one]"), badInteger.getMessage());
    }


    @Test
    void versionGateSkips21OnlyRulesOn20Documents()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-b-violations-21.xml", "2.0"));
        for (String ruleId : BATCH_IDS)
        {
            ExecutionStatus expected = "DEFINE-XML-0043".equals(ruleId)
                    ? ExecutionStatus.NOT_APPLICABLE_VERSION
                    : ExecutionStatus.EXECUTED;
            assertEquals(expected, results.get(ruleId).status(), ruleId + " status under 2.0");
        }
    }

}
