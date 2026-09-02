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
 * Batch A (document root and study identity): sheet ids 3, 5, 7-10, 12-16, 18-36, 131, 134, 233,
 * 254-258, 264 against a clean and a deliberately broken 2.1 fixture (plus a bare 2.0 fixture for
 * the one violation - a missing Study - that cannot coexist with the Study-scoped violations).
 * Loads only this batch's rules (filtered by id) so concurrent batches adding files never move
 * these assertions. Pilot ids 2, 4, 6, 11, 17 (also Batch A elements) are deliberately absent.
 */
class BatchARulesTest
{

    /** This batch's rule ids. Ids 132, 261-263 are deferred (CT / library gaps), not authored. */
    private static final Set<String> BATCH_IDS = Set.of(//
            "DEFINE-XML-0003", "DEFINE-XML-0005", "DEFINE-XML-0007", "DEFINE-XML-0008",
            "DEFINE-XML-0009", "DEFINE-XML-0010", "DEFINE-XML-0012", "DEFINE-XML-0013",
            "DEFINE-XML-0014", "DEFINE-XML-0015", "DEFINE-XML-0016", "DEFINE-XML-0018",
            "DEFINE-XML-0019", "DEFINE-XML-0020", "DEFINE-XML-0021", "DEFINE-XML-0022",
            "DEFINE-XML-0023", "DEFINE-XML-0024", "DEFINE-XML-0025", "DEFINE-XML-0026",
            "DEFINE-XML-0027", "DEFINE-XML-0028", "DEFINE-XML-0029", "DEFINE-XML-0030",
            "DEFINE-XML-0031", "DEFINE-XML-0032", "DEFINE-XML-0033", "DEFINE-XML-0034",
            "DEFINE-XML-0035", "DEFINE-XML-0036", "DEFINE-XML-0131", "DEFINE-XML-0134",
            "DEFINE-XML-0233", "DEFINE-XML-0254", "DEFINE-XML-0255", "DEFINE-XML-0256",
            "DEFINE-XML-0257", "DEFINE-XML-0258", "DEFINE-XML-0264");

    /** The 2.1-only subset ({@code Applicable_Versions: ["2.1"]}). */
    private static final Set<String> V21_ONLY_IDS = Set.of(//
            "DEFINE-XML-0016", "DEFINE-XML-0030", "DEFINE-XML-0031", "DEFINE-XML-0032",
            "DEFINE-XML-0034", "DEFINE-XML-0035", "DEFINE-XML-0036", "DEFINE-XML-0131",
            "DEFINE-XML-0134", "DEFINE-XML-0233", "DEFINE-XML-0256", "DEFINE-XML-0257",
            "DEFINE-XML-0258", "DEFINE-XML-0264");

    /**
     * Expected finding counts on the 2.1 violations fixture. Two rules cannot fire there by
     * construction: 0003 (a well-formed XML document has exactly one root, so "more than one ODM"
     * is unreachable - same situation as pilot 0002) and 0016 (def:Context must be "Submission" so
     * the Submission-guarded rules 0031/0131 can fire; a document cannot violate both sides at
     * once). 0010 (Study missing) fires on the separate bare 2.0 fixture instead, because every
     * Study-scoped violation needs Study elements present.
     */
    private static final Map<String, Integer> EXPECTED_21 = Map.ofEntries(//
            Map.entry("DEFINE-XML-0003", 0), // unreachable: single document root
            Map.entry("DEFINE-XML-0005", 1), // AsOfDateTime not ISO 8601
            Map.entry("DEFINE-XML-0007", 1), // ODMVersion 1.3.0
            Map.entry("DEFINE-XML-0008", 1), // FileType blank, not "Snapshot"
            Map.entry("DEFINE-XML-0009", 1), // FileOID missing
            Map.entry("DEFINE-XML-0010", 0), // Studies required by other rules; see 2.0 fixture
            Map.entry("DEFINE-XML-0012", 1), // third Study without OID
            Map.entry("DEFINE-XML-0013", 1), // CreationDateTime blank counts as missing
            Map.entry("DEFINE-XML-0014", 1), // third Study without GlobalVariables
            Map.entry("DEFINE-XML-0015", 1), // second Study with two GlobalVariables
            Map.entry("DEFINE-XML-0016", 0), // def:Context kept for the 0031/0131 guards
            Map.entry("DEFINE-XML-0018", 1), // first GV without StudyName
            Map.entry("DEFINE-XML-0019", 1), // GV with two StudyName children
            Map.entry("DEFINE-XML-0020", 1), // GV without StudyDescription
            Map.entry("DEFINE-XML-0021", 1), // GV with two StudyDescription children
            Map.entry("DEFINE-XML-0022", 1), // GV without ProtocolName
            Map.entry("DEFINE-XML-0023", 1), // GV with two ProtocolName children
            Map.entry("DEFINE-XML-0024", 1), // third Study without MetaDataVersion
            Map.entry("DEFINE-XML-0025", 1), // second Study with two MetaDataVersions
            Map.entry("DEFINE-XML-0026", 1), // MDV without OID
            Map.entry("DEFINE-XML-0027", 1), // MDV without Name
            Map.entry("DEFINE-XML-0028", 1), // def:DefineVersion 3.0.0
            Map.entry("DEFINE-XML-0029", 1), // MDV.B1 without def:DefineVersion
            Map.entry("DEFINE-XML-0030", 1), // dangling MDV def:CommentOID
            Map.entry("DEFINE-XML-0031", 2), // MDV.B1 + MDV.B2 without def:Standards
            Map.entry("DEFINE-XML-0032", 1), // MDV with two def:Standards
            Map.entry("DEFINE-XML-0033", 1), // def:Standard without Name
            Map.entry("DEFINE-XML-0034", 1), // def:Standard without Type
            Map.entry("DEFINE-XML-0035", 1), // CT standard without PublishingSet
            Map.entry("DEFINE-XML-0036", 1), // def:Standard without Version
            Map.entry("DEFINE-XML-0131", 1), // IG.DM without def:Class under Submission
            Map.entry("DEFINE-XML-0134", 1), // def:SubClass without Name
            Map.entry("DEFINE-XML-0233", 1), // empty def:Standards
            Map.entry("DEFINE-XML-0254", 1), // CreationDateTime blank fails the format
            Map.entry("DEFINE-XML-0255", 1), // FileType blank counts as missing
            Map.entry("DEFINE-XML-0256", 1), // dangling def:Standard def:CommentOID
            Map.entry("DEFINE-XML-0257", 1), // def:Standard without OID
            Map.entry("DEFINE-XML-0258", 1), // empty def:Standards
            Map.entry("DEFINE-XML-0264", 1)); // Status "Bogus"

    /** Surefire pins the CWD to target/test-cwd; anchor via projectBasedir (root pom). */
    private static final Path RULES_DIR = TestRules.CDISC;

    private static DocumentContext context(String aFixture, String aVersion)
    {
        try (InputStream in = BatchARulesTest.class.getResourceAsStream("/fixtures/" + aFixture))
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
        assertEquals(BATCH_IDS.size(), batch.size(), "batch-A rule files present");
        RuleEvaluator evaluator = new RuleEvaluator();
        return batch.stream().collect(Collectors.toMap(ConformanceRule::ruleId,
                rule -> evaluator.evaluate(rule, aContext)));
    }


    @Test
    void cleanFixtureProducesNoFindings()
    {
        Map<String, RuleResult> results = evaluateBatch(context("batch-a-clean-21.xml", "2.1"));
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
                context("batch-a-violations-21.xml", "2.1"));
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
    void contextOtherDisarmsSubmissionGuardsAndSatisfies0016()
    {
        // review-batch-a WARNs 3+4: prove the over-fire direction — with def:Context="Other",
        // the Submission-guarded rules must stay silent despite missing Standards/Class, and
        // 0016 must not fire because Context is present.
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-a-context-other-21.xml", "2.1"));
        for (String id : List.of("DEFINE-XML-0016", "DEFINE-XML-0031", "DEFINE-XML-0131"))
        {
            RuleResult result = results.get(id);
            assertEquals(ExecutionStatus.EXECUTED, result.status(), id + " status");
            assertEquals(List.of(), result.findings(), id + " must not fire on Context=Other");
        }
    }


    @Test
    void missingStudyFiresOnTheBare20Fixture()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-a-violations-20.xml", "2.0"));
        RuleResult missingStudy = results.get("DEFINE-XML-0010");
        assertEquals(ExecutionStatus.EXECUTED, missingStudy.status());
        assertEquals(1, missingStudy.findings().size(),
                "0010 finding count: " + missingStudy.findings());
    }


    @Test
    void versionGateSkips21OnlyRulesOn20Documents()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-a-violations-20.xml", "2.0"));
        for (String ruleId : BATCH_IDS)
        {
            ExecutionStatus expected = V21_ONLY_IDS.contains(ruleId)
                    ? ExecutionStatus.NOT_APPLICABLE_VERSION
                    : ExecutionStatus.EXECUTED;
            assertEquals(expected, results.get(ruleId).status(), ruleId + " status under 2.0");
        }
    }


    @Test
    void findingCarriesLocationAndRenderedMessage()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("batch-a-violations-21.xml", "2.1"));
        var danglingComment = results.get("DEFINE-XML-0030").findings().get(0);
        assertTrue(danglingComment.getMessage().contains("COM.MISSING"),
                danglingComment.getMessage());
        assertTrue(danglingComment.getXpath().contains("MetaDataVersion"),
                danglingComment.getXpath());
        var badStatus = results.get("DEFINE-XML-0264").findings().get(0);
        assertTrue(badStatus.getMessage().contains("Standard Status [Bogus]"),
                badStatus.getMessage());
        assertTrue(badStatus.getXpath().contains("Standard[@OID='STD.1']"), badStatus.getXpath());
        var badDefineVersion = results.get("DEFINE-XML-0028").findings().get(0);
        assertTrue(badDefineVersion.getMessage().contains("def:DefineVersion[3.0.0]"),
                badDefineVersion.getMessage());
    }

}
