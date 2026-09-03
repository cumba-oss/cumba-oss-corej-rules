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
 * Batch F (methods / comments / alias / misc leaves): sheet ids 60-62, 95, 96, 100, 101, 205-210,
 * 212-216, 218, 219, 221, 248, 259 against a clean and a deliberately broken 2.1 fixture. Loads
 * only this batch's rules (filtered by id) so concurrent batches adding files never move these
 * assertions. Pilot id 63 (also a TranslatedText rule) is deliberately absent; the CT-gated Alias
 * rows 97, 98, 99, 249 are deferred to the PMDA P5 batch (plan section 3.35), not authored here.
 */
class BatchFRulesTest
{

    /** This batch's rule ids. */
    private static final Set<String> BATCH_IDS = Set.of(//
            "DEFINE-XML-0060", "DEFINE-XML-0061", "DEFINE-XML-0062", "DEFINE-XML-0095",
            "DEFINE-XML-0096", "DEFINE-XML-0100", "DEFINE-XML-0101", "DEFINE-XML-0205",
            "DEFINE-XML-0206", "DEFINE-XML-0207", "DEFINE-XML-0208", "DEFINE-XML-0209",
            "DEFINE-XML-0210", "DEFINE-XML-0212", "DEFINE-XML-0213", "DEFINE-XML-0214",
            "DEFINE-XML-0215", "DEFINE-XML-0216", "DEFINE-XML-0218", "DEFINE-XML-0219",
            "DEFINE-XML-0221", "DEFINE-XML-0248", "DEFINE-XML-0259");

    /** Surefire pins the CWD to target/test-cwd; anchor via projectBasedir (root pom). */
    private static final Path RULES_DIR = TestRules.CDISC;

    private static DocumentContext context(String aFixture, String aVersion)
    {
        try (InputStream in = BatchFRulesTest.class.getResourceAsStream("/fixtures/" + aFixture))
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
        assertEquals(BATCH_IDS.size(), batch.size(), "batch-F rule files present");
        RuleEvaluator evaluator = new RuleEvaluator();
        return batch.stream().collect(Collectors.toMap(ConformanceRule::ruleId,
                rule -> evaluator.evaluate(rule, aContext)));
    }


    @Test
    void cleanFixtureProducesNoFindings()
    {
        Map<String, RuleResult> results = evaluateBatch(context("batch-f-clean-21.xml", "2.1"));
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
                context("batch-f-violations-21.xml", "2.1"));
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
                context("batch-f-violations-21.xml", "2.1"));
        var missingAlias = results.get("DEFINE-XML-0095").findings().get(0);
        assertTrue(missingAlias.getMessage().contains("[SUPPAE vs AE]"), missingAlias.getMessage());
        assertTrue(missingAlias.getXpath().contains("ItemGroupDef[@OID='IG.SUPPAE']"),
                missingAlias.getXpath());
        var suppAlias = results.get("DEFINE-XML-0096").findings().get(0);
        assertTrue(suppAlias.getXpath().contains("ItemGroupDef[@OID='IG.SUPPAE']"),
                suppAlias.getXpath());
        var badType = results.get("DEFINE-XML-0209").findings().get(0);
        assertTrue(badType.getMessage().contains("Type [Derivation]"), badType.getMessage());
        assertTrue(badType.getXpath().contains("MethodDef[@OID='MT.4']"), badType.getXpath());
        var duplicateComment = results.get("DEFINE-XML-0213").findings().get(0);
        assertTrue(duplicateComment.getMessage().contains("[COM.DUP]"),
                duplicateComment.getMessage());
        var duplicateLeaf = results.get("DEFINE-XML-0216").findings().get(0);
        assertTrue(duplicateLeaf.getMessage().contains("[LF.DUP]"), duplicateLeaf.getMessage());
        assertTrue(duplicateLeaf.getXpath().contains("leaf"), duplicateLeaf.getXpath());
        var badLeafId = results.get("DEFINE-XML-0259").findings().get(0);
        assertTrue(badLeafId.getMessage().contains("[9BAD LEAF]"), badLeafId.getMessage());
        var badExpression = results.get("DEFINE-XML-0221").findings().get(0);
        assertTrue(badExpression.getXpath().contains("MethodDef[@OID='MT.4']/FormalExpression"),
                badExpression.getXpath());
    }


    @Test
    void everyBatchRuleAppliesToBothVersions()
    {
        // Batch F has no 2.1-only rules (the sole 2.1-only row, 249, is CT-deferred): under a
        // 2.0 document every rule must still execute rather than be version-gated away.
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-f-violations-21.xml", "2.0"));
        for (String ruleId : BATCH_IDS)
        {
            RuleResult result = results.get(ruleId);
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status under 2.0");
            assertEquals(List.of("2.0", "2.1"), result.rule().applicableVersions(),
                    ruleId + " applicable versions");
        }
    }

}
