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
 * Batch C (datasets, the ItemGroupDef family): sheet ids 57, 93, 94, 104, 108-110, 112-129,
 * 239-241, 260 against a clean and a deliberately broken 2.1 fixture. Ids 105, 106, 251, 252
 * (deref-guarded rules needing the referenced def:Standard's Name) and 265 (dataset label vs the
 * IG-library label) are deferred, not authored. Loads only this batch's rules (filtered by id) so
 * concurrent batches adding files never move these assertions.
 */
class BatchCRulesTest
{

    /** This batch's rule ids. */
    private static final Set<String> BATCH_IDS = Set.of(//
            "DEFINE-XML-0057", "DEFINE-XML-0093", "DEFINE-XML-0094", "DEFINE-XML-0104",
            "DEFINE-XML-0108", "DEFINE-XML-0109", "DEFINE-XML-0110", "DEFINE-XML-0112",
            "DEFINE-XML-0113", "DEFINE-XML-0114", "DEFINE-XML-0115", "DEFINE-XML-0116",
            "DEFINE-XML-0117", "DEFINE-XML-0118", "DEFINE-XML-0119", "DEFINE-XML-0120",
            "DEFINE-XML-0121", "DEFINE-XML-0122", "DEFINE-XML-0123", "DEFINE-XML-0124",
            "DEFINE-XML-0125", "DEFINE-XML-0126", "DEFINE-XML-0127", "DEFINE-XML-0128",
            "DEFINE-XML-0129", "DEFINE-XML-0239", "DEFINE-XML-0240", "DEFINE-XML-0241",
            "DEFINE-XML-0260");

    /** The 2.1-only subset ({@code Applicable_Versions: ["2.1"]}). */
    private static final Set<String> V21_ONLY_IDS = Set.of(//
            "DEFINE-XML-0057", "DEFINE-XML-0093", "DEFINE-XML-0108", "DEFINE-XML-0119",
            "DEFINE-XML-0121", "DEFINE-XML-0122", "DEFINE-XML-0123", "DEFINE-XML-0124",
            "DEFINE-XML-0125", "DEFINE-XML-0126", "DEFINE-XML-0239", "DEFINE-XML-0240");

    /**
     * Expected finding counts on the 2.1 violations fixture: exactly one per rule. Two datasets
     * deliberately carry two findings each (see the fixture header): IG.NOLOC violates 0119 and
     * 0125 (the either-or pair cannot be separated without a def:HasNoData="No" side effect on
     * 0126), and IG.NSNODESC violates 0057 and 0128 (a missing Description in a Submission-context
     * document always fires 0128 as well).
     */
    private static final Map<String, Integer> EXPECTED_21 = BATCH_IDS.stream()
            .collect(Collectors.toMap(id -> id, _ -> 1));

    /** Surefire pins the CWD to target/test-cwd; anchor via projectBasedir (root pom). */
    private static final Path RULES_DIR = TestRules.CDISC;

    private static DocumentContext context(String aFixture, String aVersion)
    {
        try (InputStream in = BatchCRulesTest.class.getResourceAsStream("/fixtures/" + aFixture))
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
        assertEquals(BATCH_IDS.size(), batch.size(), "batch-C rule files present");
        RuleEvaluator evaluator = new RuleEvaluator();
        return batch.stream().collect(Collectors.toMap(ConformanceRule::ruleId,
                rule -> evaluator.evaluate(rule, aContext)));
    }


    @Test
    void cleanFixtureProducesNoFindings()
    {
        Map<String, RuleResult> results = evaluateBatch(context("batch-c-clean-21.xml", "2.1"));
        assertEquals(BATCH_IDS.size(), results.size());
        for (RuleResult result : results.values())
        {
            assertEquals(ExecutionStatus.EXECUTED, result.status(),
                    result.rule().ruleId() + " status");
            assertEquals(List.of(), result.findings(), result.rule().ruleId() + " findings");
        }
    }


    @Test
    void violationsFixtureFiresEachRuleTheExpectedNumberOfTimes()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-c-violations-21.xml", "2.1"));
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
    void versionGateSkips21OnlyRulesOn20Documents()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-c-violations-21.xml", "2.0"));
        for (String ruleId : BATCH_IDS)
        {
            ExecutionStatus expected = V21_ONLY_IDS.contains(ruleId)
                    ? ExecutionStatus.NOT_APPLICABLE_VERSION
                    : ExecutionStatus.EXECUTED;
            assertEquals(expected, results.get(ruleId).status(), ruleId + " status under 2.0");
        }
        // A both-versions rule keeps firing when the same document is treated as 2.0.
        assertEquals(1, results.get("DEFINE-XML-0110").findings().size(),
                "0110 finding count under 2.0");
    }


    @Test
    void findingCarriesLocationAndRenderedMessage()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-c-violations-21.xml", "2.1"));
        var nameMismatch = results.get("DEFINE-XML-0110").findings().get(0);
        assertTrue(nameMismatch.getMessage().contains("AEX vs AE"), nameMismatch.getMessage());
        assertTrue(nameMismatch.getXpath().contains("ItemGroupDef[@OID='IG.SASMIS']"),
                nameMismatch.getXpath());
        var fileMismatch = results.get("DEFINE-XML-0112").findings().get(0);
        assertTrue(fileMismatch.getMessage().contains("EG vs egg"), fileMismatch.getMessage());
        var danglingStandard = results.get("DEFINE-XML-0124").findings().get(0);
        assertTrue(danglingStandard.getMessage().contains("STD.MISSING"),
                danglingStandard.getMessage());
        var danglingComment = results.get("DEFINE-XML-0127").findings().get(0);
        assertTrue(danglingComment.getXpath().contains("ItemGroupDef[@OID='IG.DANGCOM']"),
                danglingComment.getXpath());
        var emptyMdv = results.get("DEFINE-XML-0093").findings().get(0);
        assertTrue(emptyMdv.getXpath().contains("MetaDataVersion[@OID='MDV.EMPTY']"),
                emptyMdv.getXpath());
    }


    /**
     * review-batch-c resolutions (coordinator-added): the Context=Other side proves the Submission
     * guards of 0093/0108/0119/0125/0128 (deleting a guard fails here); the same fixture exercises
     * the companion rules 0239-B (TRIAL DESIGN dataset with IsReferenceData absent) and 0124-B
     * (StandardOID resolving to a non-IG Standard), with a compliant decoy.
     */
    @Test
    void guardFixturePinsSubmissionGuardsAndCompanionRules()
    {
        Path dir = RULES_DIR;
        List<ConformanceRule> rules = RuleRepository.loadDirectory(dir).stream()
                .filter(rule -> Set
                        .of("DEFINE-XML-0093", "DEFINE-XML-0108", "DEFINE-XML-0119",
                                "DEFINE-XML-0125", "DEFINE-XML-0128", "DEFINE-XML-0239",
                                "DEFINE-XML-0239-B", "DEFINE-XML-0124", "DEFINE-XML-0124-B")
                        .contains(rule.ruleId()))
                .toList();
        assertEquals(9, rules.size(), "guard-fixture rule set present");
        RuleEvaluator evaluator = new RuleEvaluator();
        DocumentContext ctx = context("batch-c-guards-21.xml", "2.1");
        Map<String, RuleResult> results = rules.stream().collect(
                Collectors.toMap(ConformanceRule::ruleId, rule -> evaluator.evaluate(rule, ctx)));
        // Submission guards off under Context=Other: silent despite violating shapes.
        for (String id : List.of("DEFINE-XML-0093", "DEFINE-XML-0108", "DEFINE-XML-0119",
                "DEFINE-XML-0125", "DEFINE-XML-0128"))
        {
            assertEquals(List.of(), results.get(id).findings(), id + " must stay silent");
        }
        // 0239 is not Context-guarded; it is silent here because the attribute is ABSENT
        // (value-kinds skip) — exactly the half its companion 0239-B covers below.
        assertEquals(List.of(), results.get("DEFINE-XML-0239").findings());
        // Companion rules fire exactly once each (guards independent of Context).
        var refData = results.get("DEFINE-XML-0239-B");
        assertEquals(1, refData.findings().size(), "0239-B: " + refData.findings());
        assertTrue(refData.findings().get(0).getXpath().contains("IG.TDNONE"));
        var nonIg = results.get("DEFINE-XML-0124-B");
        assertEquals(1, nonIg.findings().size(), "0124-B: " + nonIg.findings());
        assertTrue(nonIg.findings().get(0).getXpath().contains("IG.NONIG"));
        assertEquals(List.of(), results.get("DEFINE-XML-0124").findings(),
                "0124 references resolve fine here");
    }

}
