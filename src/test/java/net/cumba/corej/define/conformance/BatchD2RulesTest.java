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
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import net.cumba.corej.define.conformance.report.ExecutionStatus;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.RuleRepository;
import net.cumba.corej.define.conformance.tree.ElementNode;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/**
 * Batch D2 (value-level metadata and range checks): sheet ids 55, 80-83, 85-92, 162-164, 235-237
 * against a clean and a deliberately broken 2.1 fixture. The violations fixture carries exactly one
 * dedicated violator per rule next to compliant decoys, so exact per-rule counts prove both firing
 * and non-firing. Sheet id 84 (comment required when a where clause spans datasets) shipped
 * separately as a custom check — it needs a custom reverse-membership check (see the batch report).
 * Loads only this batch's rules (filtered by id) so concurrent batches adding files never move
 * these assertions.
 */
class BatchD2RulesTest
{

    /** This batch's rule ids; all {@code ["2.0","2.1"]} except 0235 ({@code ["2.1"]}). */
    private static final Set<String> BATCH_IDS = Set.of(//
            "DEFINE-XML-0055", "DEFINE-XML-0080", "DEFINE-XML-0081", "DEFINE-XML-0082",
            "DEFINE-XML-0083", "DEFINE-XML-0085", "DEFINE-XML-0086", "DEFINE-XML-0087",
            "DEFINE-XML-0088", "DEFINE-XML-0089", "DEFINE-XML-0090", "DEFINE-XML-0091",
            "DEFINE-XML-0092", "DEFINE-XML-0162", "DEFINE-XML-0163", "DEFINE-XML-0164",
            "DEFINE-XML-0235", "DEFINE-XML-0236", "DEFINE-XML-0237");

    /** The only 2.1-gated rule of the batch (ValueListDef Description arrived in 2.1). */
    private static final String RULE_235 = "DEFINE-XML-0235";

    /** Expected finding counts on the violations fixture: exactly one dedicated violator each. */
    private static final Map<String, Integer> EXPECTED_21 = BATCH_IDS.stream()
            .collect(Collectors.toMap(id -> id, _ -> 1));

    /** Surefire pins the CWD to target/test-cwd; anchor via projectBasedir (root pom). */
    private static final Path RULES_DIR = TestRules.CDISC;

    private static DocumentContext context(String aFixture, String aVersion)
    {
        try (InputStream in = BatchD2RulesTest.class.getResourceAsStream("/fixtures/" + aFixture))
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
        assertEquals(BATCH_IDS.size(), batch.size(), "batch-D2 rule files present");
        RuleEvaluator evaluator = new RuleEvaluator();
        return batch.stream().collect(Collectors.toMap(ConformanceRule::ruleId,
                rule -> evaluator.evaluate(rule, aContext)));
    }


    private static ConformanceFinding onlyFinding(Map<String, RuleResult> aResults, String aRuleId)
    {
        List<ConformanceFinding> findings = aResults.get(aRuleId).findings();
        assertEquals(1, findings.size(), aRuleId + " findings: " + findings);
        return findings.get(0);
    }


    @Test
    void cleanFixtureProducesNoFindings()
    {
        Map<String, RuleResult> results = evaluateBatch(context("batch-d2-clean-21.xml", "2.1"));
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
                context("batch-d2-violations-21.xml", "2.1"));
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
    void versionGateSkipsOnly235On20Documents()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-d2-violations-21.xml", "2.0"));
        for (String ruleId : BATCH_IDS)
        {
            if (RULE_235.equals(ruleId))
            {
                assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, results.get(ruleId).status(),
                        ruleId + " status under 2.0");
            }
            else
            {
                assertEquals(ExecutionStatus.EXECUTED, results.get(ruleId).status(),
                        ruleId + " status under 2.0");
                assertEquals(1, results.get(ruleId).findings().size(),
                        ruleId + " finding count under 2.0");
            }
        }
    }


    @Test
    void findingsPointAtTheDedicatedViolators()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-d2-violations-21.xml", "2.1"));

        var qval = onlyFinding(results, "DEFINE-XML-0055");
        assertTrue(qval.getXpath().contains("ItemDef[@OID='IT.SUPPAE.QVAL']"), qval.getXpath());

        var wcRefNoOid = onlyFinding(results, "DEFINE-XML-0080");
        assertTrue(wcRefNoOid.getXpath().contains("ValueListDef[@OID='VL.WCREF']"),
                wcRefNoOid.getXpath());

        var wcRefDangling = onlyFinding(results, "DEFINE-XML-0081");
        assertTrue(wcRefDangling.getMessage().contains("[WC.DANGLING]"),
                wcRefDangling.getMessage());

        var wcNoOid = onlyFinding(results, "DEFINE-XML-0082");
        assertTrue(wcNoOid.getXpath().endsWith("WhereClauseDef[2]"), wcNoOid.getXpath());

        var badComment = onlyFinding(results, "DEFINE-XML-0083");
        assertTrue(badComment.getMessage().contains("[COM.MISSING]"), badComment.getMessage());
        assertTrue(badComment.getXpath().contains("WhereClauseDef[@OID='WC.BADCOMMENT']"),
                badComment.getXpath());

        var noRangeCheck = onlyFinding(results, "DEFINE-XML-0085");
        assertTrue(noRangeCheck.getXpath().contains("WhereClauseDef[@OID='WC.EMPTY']"),
                noRangeCheck.getXpath());

        var badComparator = onlyFinding(results, "DEFINE-XML-0086");
        assertTrue(badComparator.getMessage().contains("[EQUALS]"), badComparator.getMessage());
        assertTrue(badComparator.getXpath().contains("WhereClauseDef[@OID='WC.BADCOMP']"),
                badComparator.getXpath());

        var noComparator = onlyFinding(results, "DEFINE-XML-0087");
        assertTrue(noComparator.getXpath().contains("WhereClauseDef[@OID='WC.NOCOMP']"),
                noComparator.getXpath());

        var badSoftHard = onlyFinding(results, "DEFINE-XML-0088");
        assertTrue(badSoftHard.getMessage().contains("[Firm]"), badSoftHard.getMessage());
        assertTrue(badSoftHard.getXpath().contains("WhereClauseDef[@OID='WC.BADSH']"),
                badSoftHard.getXpath());

        var noSoftHard = onlyFinding(results, "DEFINE-XML-0089");
        assertTrue(noSoftHard.getXpath().contains("WhereClauseDef[@OID='WC.NOSH']"),
                noSoftHard.getXpath());

        var noItemOid = onlyFinding(results, "DEFINE-XML-0090");
        assertTrue(noItemOid.getXpath().contains("WhereClauseDef[@OID='WC.NOITEM']"),
                noItemOid.getXpath());

        var badItemOid = onlyFinding(results, "DEFINE-XML-0091");
        assertTrue(badItemOid.getMessage().contains("[IT.MISSING]"), badItemOid.getMessage());
        assertTrue(badItemOid.getXpath().contains("WhereClauseDef[@OID='WC.BADITEM']"),
                badItemOid.getXpath());

        var noCheckValue = onlyFinding(results, "DEFINE-XML-0092");
        assertTrue(noCheckValue.getXpath().contains("WhereClauseDef[@OID='WC.NOCV']"),
                noCheckValue.getXpath());

        var twoValueListRefs = onlyFinding(results, "DEFINE-XML-0162");
        assertTrue(twoValueListRefs.getXpath().contains("ItemDef[@OID='IT.VS.VSORRES']"),
                twoValueListRefs.getXpath());

        var danglingValueList = onlyFinding(results, "DEFINE-XML-0163");
        assertTrue(danglingValueList.getMessage().contains("[VL.DANGLING]"),
                danglingValueList.getMessage());

        var noValueListOid = onlyFinding(results, "DEFINE-XML-0164");
        assertTrue(noValueListOid.getXpath().contains("ItemDef[@OID='IT.EG.EGORRES']"),
                noValueListOid.getXpath());

        var twoDescriptions = onlyFinding(results, "DEFINE-XML-0235");
        assertTrue(twoDescriptions.getXpath().contains("ValueListDef[@OID='VL.TWODESC']"),
                twoDescriptions.getXpath());

        var vldNoOid = onlyFinding(results, "DEFINE-XML-0236");
        assertTrue(vldNoOid.getXpath().endsWith("ValueListDef[6]"), vldNoOid.getXpath());

        var noItemRef = onlyFinding(results, "DEFINE-XML-0237");
        assertTrue(noItemRef.getXpath().contains("ValueListDef[@OID='VL.NOITEMS']"),
                noItemRef.getXpath());
    }

}
