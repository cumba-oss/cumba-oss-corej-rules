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
 * Batch E (codelists: CodeList, CodeListRef, CodeListItem, EnumeratedItem, Decode,
 * ExternalCodeList, plus the sheet-249 Alias rule) against a clean and a deliberately broken 2.1
 * fixture. The violations fixture carries exactly one dedicated violator per rule (two for
 * DEFINE-XML-0249: the zero-alias and the two-alias leg), so counts are exact. Loads only this
 * batch's rules (filtered by id) so concurrent batches adding files never move these assertions.
 */
class BatchERulesTest
{

    /** This batch's rule ids. */
    private static final Set<String> BATCH_IDS = Set.of(//
            "DEFINE-XML-0135", "DEFINE-XML-0151", "DEFINE-XML-0152", "DEFINE-XML-0152-B",
            "DEFINE-XML-0165", "DEFINE-XML-0165-B", "DEFINE-XML-0165-C", "DEFINE-XML-0166",
            "DEFINE-XML-0167", "DEFINE-XML-0171", "DEFINE-XML-0172", "DEFINE-XML-0173",
            "DEFINE-XML-0174", "DEFINE-XML-0175", "DEFINE-XML-0175-B", "DEFINE-XML-0176",
            "DEFINE-XML-0177", "DEFINE-XML-0178", "DEFINE-XML-0180", "DEFINE-XML-0182",
            "DEFINE-XML-0183", "DEFINE-XML-0184", "DEFINE-XML-0185", "DEFINE-XML-0191",
            "DEFINE-XML-0193", "DEFINE-XML-0194", "DEFINE-XML-0195", "DEFINE-XML-0196",
            "DEFINE-XML-0197", "DEFINE-XML-0198", "DEFINE-XML-0199", "DEFINE-XML-0203",
            "DEFINE-XML-0204", "DEFINE-XML-0244", "DEFINE-XML-0245", "DEFINE-XML-0246",
            "DEFINE-XML-0249", "DEFINE-XML-0250");

    /** The 2.1-only subset (sheet Applicable Versions column); the rest applies to 2.0 and 2.1. */
    private static final Set<String> ONLY_21_IDS = Set.of(//
            "DEFINE-XML-0152", "DEFINE-XML-0152-B", "DEFINE-XML-0167", "DEFINE-XML-0172",
            "DEFINE-XML-0176", "DEFINE-XML-0177", "DEFINE-XML-0244", "DEFINE-XML-0245",
            "DEFINE-XML-0246", "DEFINE-XML-0249");

    /**
     * Expected finding counts on the 2.1 violations fixture: exactly one dedicated violator per
     * rule, except DEFINE-XML-0249 whose exactly-one check has a lower-bound (no
     * {@code nci:ExtCodeID} Alias) and an upper-bound (two of them) violator.
     */
    private static final Map<String, Integer> EXPECTED_21 = BATCH_IDS.stream()
            .collect(Collectors.toMap(id -> id, id -> "DEFINE-XML-0249".equals(id) ? 2 : 1));

    /** Surefire pins the CWD to target/test-cwd; anchor via projectBasedir (root pom). */
    private static final Path RULES_DIR = TestRules.CDISC;

    private static DocumentContext context(String aFixture, String aVersion)
    {
        try (InputStream in = BatchERulesTest.class.getResourceAsStream("/fixtures/" + aFixture))
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
        assertEquals(BATCH_IDS.size(), batch.size(), "batch-E rule files present");
        RuleEvaluator evaluator = new RuleEvaluator();
        return batch.stream().collect(Collectors.toMap(ConformanceRule::ruleId,
                rule -> evaluator.evaluate(rule, aContext)));
    }


    @Test
    void cleanFixtureProducesNoFindings()
    {
        Map<String, RuleResult> results = evaluateBatch(context("batch-e-clean-21.xml", "2.1"));
        assertEquals(BATCH_IDS.size(), results.size());
        for (RuleResult result : results.values())
        {
            assertEquals(ExecutionStatus.EXECUTED, result.status(),
                    result.rule().ruleId() + " status");
            assertEquals(List.of(), result.findings(), result.rule().ruleId() + " findings");
        }
    }


    @Test
    void violationsFixtureFiresEachRuleAtItsDedicatedViolators()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-e-violations-21.xml", "2.1"));
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
    void versionGateSplitsTheBatchOn20Documents()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-e-violations-21.xml", "2.0"));
        for (String ruleId : BATCH_IDS)
        {
            ExecutionStatus expected = ONLY_21_IDS.contains(ruleId)
                    ? ExecutionStatus.NOT_APPLICABLE_VERSION
                    : ExecutionStatus.EXECUTED;
            assertEquals(expected, results.get(ruleId).status(), ruleId + " status under 2.0");
        }
    }


    @Test
    void findingsCarryTheRightLocationsAndMessages()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-e-violations-21.xml", "2.1"));
        var danglingCodeList = results.get("DEFINE-XML-0151").findings().get(0);
        assertTrue(danglingCodeList.getMessage().contains("[CL.MISSING]"),
                danglingCodeList.getMessage());
        assertTrue(danglingCodeList.getXpath().contains("ItemDef[@OID='IT.V151']"),
                danglingCodeList.getXpath());
        var wrongStandardType = results.get("DEFINE-XML-0152-B").findings().get(0);
        assertTrue(wrongStandardType.getXpath().contains("CodeList[@OID='CL.V152B']"),
                wrongStandardType.getXpath());
        var duplicateName = results.get("DEFINE-XML-0172").findings().get(0);
        assertTrue(duplicateName.getMessage().contains("[Duplicate Name]"),
                duplicateName.getMessage());
        assertTrue(duplicateName.getXpath().contains("CodeList[@OID='CL.V172B']"),
                duplicateName.getXpath());
        var badDataType = results.get("DEFINE-XML-0174").findings().get(0);
        assertTrue(badDataType.getMessage().contains("[string]"), badDataType.getMessage());
        var noDollar = results.get("DEFINE-XML-0175-B").findings().get(0);
        assertTrue(noDollar.getMessage().contains("[NODOLLAR]"), noDollar.getMessage());
        var missingStandardOid = results.get("DEFINE-XML-0176").findings().get(0);
        assertTrue(missingStandardOid.getXpath().contains("CodeList[@OID='CL.V176']"),
                missingStandardOid.getXpath());
        Set<String> aliasXpaths = results.get("DEFINE-XML-0249").findings().stream()
                .map(ConformanceFinding::getXpath).collect(Collectors.toSet());
        assertTrue(
                aliasXpaths.stream().anyMatch(xpath -> xpath.contains("CodeList[@OID='CL.V249']")),
                aliasXpaths.toString());
        assertTrue(
                aliasXpaths.stream().anyMatch(xpath -> xpath.contains("CodeList[@OID='CL.V249B']")),
                aliasXpaths.toString());
    }

}
