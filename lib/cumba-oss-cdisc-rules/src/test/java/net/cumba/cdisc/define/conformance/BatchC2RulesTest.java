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
 * Batch C2 (the deref-guarded ItemGroupDef rules deferred from batch C): sheet ids 105, 106, 251,
 * 252 against a clean and a deliberately broken 2.1 fixture. All four guards dereference
 * {@code def:StandardOID} to the referenced {@code def:Standard}'s Name; both fixtures carry
 * SDTM/SEND-bound, ADaM-bound and standard-less datasets so guard selectivity is proven in both
 * directions (firing and non-firing). Loads only this batch's rules (filtered by id) so concurrent
 * batches adding files never move these assertions.
 */
class BatchC2RulesTest
{

    /** This batch's rule ids — all {@code Applicable_Versions: ["2.1"]}. */
    private static final Set<String> BATCH_IDS = Set.of(//
            "DEFINE-XML-0105", "DEFINE-XML-0106", "DEFINE-XML-0251", "DEFINE-XML-0252");

    /**
     * Expected finding counts on the 2.1 violations fixture: exactly one per rule — one dedicated
     * violator each, while the compliant and standard-less decoys (see the fixture header) stay
     * silent.
     */
    private static final Map<String, Integer> EXPECTED_21 = BATCH_IDS.stream()
            .collect(Collectors.toMap(id -> id, _ -> 1));

    /** Surefire pins the CWD to target/test-cwd; anchor via projectBasedir (root pom). */
    private static final Path RULES_DIR = TestRules.CDISC;

    private static DocumentContext context(String aFixture, String aVersion)
    {
        try (InputStream in = BatchC2RulesTest.class.getResourceAsStream("/fixtures/" + aFixture))
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
        assertEquals(BATCH_IDS.size(), batch.size(), "batch-C2 rule files present");
        RuleEvaluator evaluator = new RuleEvaluator();
        return batch.stream().collect(Collectors.toMap(ConformanceRule::ruleId,
                rule -> evaluator.evaluate(rule, aContext)));
    }


    @Test
    void cleanFixtureProducesNoFindings()
    {
        Map<String, RuleResult> results = evaluateBatch(context("batch-c2-clean-21.xml", "2.1"));
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
                context("batch-c2-violations-21.xml", "2.1"));
        assertEquals(BATCH_IDS, EXPECTED_21.keySet(), "expected-count map covers the batch");
        for (Map.Entry<String, Integer> entry : EXPECTED_21.entrySet())
        {
            RuleResult result = results.get(entry.getKey());
            assertEquals(ExecutionStatus.EXECUTED, result.status(), entry.getKey() + " status");
            assertEquals(entry.getValue().intValue(), result.findings().size(),
                    entry.getKey() + " finding count: " + result.findings());
        }
    }


    @Test
    void versionGateSkipsAllRulesOn20Documents()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-c2-violations-21.xml", "2.0"));
        for (String ruleId : BATCH_IDS)
        {
            assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, results.get(ruleId).status(),
                    ruleId + " status under 2.0");
        }
    }


    @Test
    void guardsFireOnTheDedicatedViolatorOnly()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-c2-violations-21.xml", "2.1"));
        var domainMissing = results.get("DEFINE-XML-0105").findings().get(0);
        assertTrue(domainMissing.getXpath().contains("ItemGroupDef[@OID='IG.SDTMNODOM']"),
                domainMissing.getXpath());
        var domainProvided = results.get("DEFINE-XML-0106").findings().get(0);
        assertTrue(domainProvided.getXpath().contains("ItemGroupDef[@OID='IG.ADAMDOM']"),
                domainProvided.getXpath());
        var notTabulation = results.get("DEFINE-XML-0251").findings().get(0);
        assertTrue(notTabulation.getXpath().contains("ItemGroupDef[@OID='IG.SENDPURP']"),
                notTabulation.getXpath());
        assertTrue(notTabulation.getMessage().contains("[Analysis]"), notTabulation.getMessage());
        var notAnalysis = results.get("DEFINE-XML-0252").findings().get(0);
        assertTrue(notAnalysis.getXpath().contains("ItemGroupDef[@OID='IG.ADAMPURP']"),
                notAnalysis.getXpath());
        assertTrue(notAnalysis.getMessage().contains("[Tabulation]"), notAnalysis.getMessage());
    }

}
