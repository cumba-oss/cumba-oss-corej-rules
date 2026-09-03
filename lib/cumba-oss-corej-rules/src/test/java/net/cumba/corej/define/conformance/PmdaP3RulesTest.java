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
import net.cumba.corej.define.conformance.report.Severity;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.RuleRepository;
import net.cumba.corej.define.conformance.tree.ElementNode;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/**
 * PMDA batch P3 (presence / conditional-presence, plan §6.5): 39 sheet rows authored as 43 rule
 * files (DD0057 splits into three scoped legs, DD0076/DD0077 into EnumeratedItem/CodeListItem legs;
 * DD0063 is deferred to batch P5 — split-dataset detection needs cross-element counting). Fixtures:
 * a clean 2.1 document (zero findings), a 2.1 violations document (one dedicated violator per
 * rule), and a 2.0 violations document exercising the 2.0 legs of the dual-version guards
 * (StandardName, def:Class attribute, Origin Type='CRF'). Loads only this batch's rules (filtered
 * by id) so concurrent batches adding files never move these assertions.
 */
class PmdaP3RulesTest
{

    private static final Set<String> BATCH_IDS = Set.of(//
            "PMDA-DD0035", "PMDA-DD0037", "PMDA-DD0038", "PMDA-DD0040", "PMDA-DD0042",
            "PMDA-DD0045", "PMDA-DD0046", "PMDA-DD0047", "PMDA-DD0054", "PMDA-DD0056",
            "PMDA-DD0057", "PMDA-DD0057-B", "PMDA-DD0057-C", "PMDA-DD0058", "PMDA-DD0061",
            "PMDA-DD0068", "PMDA-DD0069", "PMDA-DD0070", "PMDA-DD0072", "PMDA-DD0076",
            "PMDA-DD0076-B", "PMDA-DD0077", "PMDA-DD0077-B", "PMDA-DD0087", "PMDA-DD0090",
            "PMDA-DD0094", "PMDA-DD0096", "PMDA-DD0098", "PMDA-DD0103", "PMDA-DD0111",
            "PMDA-DD0117", "PMDA-DD0119", "PMDA-DD0120", "PMDA-DD0121", "PMDA-DD0126",
            "PMDA-DD0127", "PMDA-DD0128", "PMDA-DD0129", "PMDA-DD0133", "PMDA-DD0134",
            "PMDA-OD0070", "PMDA-OD0071", "PMDA-OD0081");

    /** The batch's 2.1-only rules (sheet 2.1 X-mark without the 2.0 one). */
    private static final Set<String> V21_ONLY_IDS = Set.of(//
            "PMDA-DD0119", "PMDA-DD0120", "PMDA-DD0121", "PMDA-DD0126", "PMDA-DD0127",
            "PMDA-DD0128", "PMDA-DD0129", "PMDA-DD0133", "PMDA-DD0134");

    /** The six rules whose Define-XML 2.0 guard legs the 2.0 fixture violates. */
    private static final Map<String, Integer> EXPECTED_20 = Map.of(//
            "PMDA-DD0035", 1, "PMDA-DD0045", 1, "PMDA-DD0046", 1, //
            "PMDA-DD0054", 1, "PMDA-DD0087", 1, "PMDA-DD0111", 1);

    /** Surefire pins the CWD to target/test-cwd; anchor via projectBasedir (root pom). */
    private static final Path RULES_DIR = TestRules.PMDA;

    private static DocumentContext context(String aFixture, String aVersion)
    {
        try (InputStream in = PmdaP3RulesTest.class.getResourceAsStream("/fixtures/" + aFixture))
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
        assertEquals(BATCH_IDS.size(), batch.size(), "batch-P3 rule files present");
        RuleEvaluator evaluator = new RuleEvaluator();
        return batch.stream().collect(Collectors.toMap(ConformanceRule::ruleId,
                rule -> evaluator.evaluate(rule, aContext)));
    }


    @Test
    void cleanFixtureProducesNoFindings()
    {
        Map<String, RuleResult> results = evaluateBatch(context("pmda-p3-clean-21.xml", "2.1"));
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
                context("pmda-p3-violations-21.xml", "2.1"));
        for (String ruleId : BATCH_IDS)
        {
            RuleResult result = results.get(ruleId);
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            assertEquals(1, result.findings().size(),
                    ruleId + " finding count: " + result.findings());
        }
    }


    @Test
    void versionGateSkips21OnlyRulesOn20Documents()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("pmda-p3-violations-21.xml", "2.0"));
        for (String ruleId : V21_ONLY_IDS)
        {
            assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, results.get(ruleId).status(),
                    ruleId + " status under 2.0");
        }
    }


    @Test
    void define20LegsFireOnThe20Fixture()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("pmda-p3-violations-20.xml", "2.0"));
        for (String ruleId : BATCH_IDS)
        {
            RuleResult result = results.get(ruleId);
            if (V21_ONLY_IDS.contains(ruleId))
            {
                assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, result.status(),
                        ruleId + " status under 2.0");
                continue;
            }
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            assertEquals(EXPECTED_20.getOrDefault(ruleId, 0).intValue(), result.findings().size(),
                    ruleId + " finding count: " + result.findings());
        }
        assertTrue(results.get("PMDA-DD0045").findings().get(0).getXpath().contains("IG.A45"),
                "DD0045 fires on the Domain-less SDTM-IG dataset");
        assertTrue(results.get("PMDA-DD0054").findings().get(0).getXpath().contains("IG.A54"),
                "DD0054 fires on the def:Class-less dataset");
    }


    @Test
    void severitiesMessagesAndXpathsMatchTheSheet()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("pmda-p3-violations-21.xml", "2.1"));

        // The P3 batch carries Error and Warning rows only (no PMDA Reject row is a
        // declarative presence rule); severities come from the sheet column verbatim.
        for (RuleResult result : results.values())
        {
            Severity severity = result.rule().effectiveSeverity();
            assertTrue(severity == Severity.ERROR || severity == Severity.WARNING,
                    result.rule().ruleId() + " severity " + severity);
        }

        ConformanceFinding domain = results.get("PMDA-DD0045").findings().get(0);
        assertEquals(Severity.ERROR, domain.getSeverity());
        assertEquals("Missing Domain value", domain.getMessage());
        assertTrue(domain.getXpath().contains("ItemGroupDef[@OID='IG.V45']"), domain.getXpath());

        ConformanceFinding emptyCodeList = results.get("PMDA-OD0081").findings().get(0);
        assertEquals(Severity.WARNING, emptyCodeList.getSeverity());
        assertEquals("Codelist is empty", emptyCodeList.getMessage());
        assertTrue(emptyCodeList.getXpath().contains("CodeList[@OID='CL.V81']"),
                emptyCodeList.getXpath());

        ConformanceFinding pages = results.get("PMDA-DD0111").findings().get(0);
        assertEquals(Severity.WARNING, pages.getSeverity());
        assertEquals("Invalid use of Pages", pages.getMessage());

        ConformanceFinding annotatedCrf = results.get("PMDA-DD0103").findings().get(0);
        assertTrue(annotatedCrf.getXpath().contains("ItemDef[@OID='IT.V103']"),
                annotatedCrf.getXpath());

        // ItemRef carries no OID, so its xpath step is positional under the dataset.
        ConformanceFinding variableComment = results.get("PMDA-DD0134").findings().get(0);
        assertTrue(variableComment.getXpath().contains("ItemGroupDef[@OID='IG.V40']/ItemRef"),
                variableComment.getXpath());

        ConformanceFinding armInSdtm = results.get("PMDA-DD0087").findings().get(0);
        assertTrue(armInSdtm.getXpath().contains("MetaDataVersion[@OID='MDV.3']"),
                armInSdtm.getXpath());
    }

}
