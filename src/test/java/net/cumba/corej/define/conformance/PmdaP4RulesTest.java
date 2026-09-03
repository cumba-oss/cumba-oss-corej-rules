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
 * PMDA batch P4 (value / format / enum, plan §6.5): 39 sheet rows authored as 58 rule files
 * (DD0020B/DD0022/DD0055/DD0073 split into version and standard-family legs per the 0140/0160
 * convention, DD0064 into four parent-scoped legs, DD0112 into a document-level and a per-codelist
 * leg, OD0077 into integer/float legs). DD0049 is deferred to batch P5 (split-dataset detection
 * needs cross-element counting); DD0140 and the 2.1 halves of DD0021/DD0055 are deferred to the
 * CT-gated batch. Fixtures: a clean 2.1 document (zero findings), a 2.1 violations document (one
 * dedicated violator per 2.1-applicable rule — except DD0112, whose "no MedDRA at all" trigger
 * cannot coexist with the MedDRA-codelist violators), and a 2.0 violations document exercising the
 * 2.0-only legs plus DD0112. Loads only this batch's rules (filtered by id) so concurrent batches
 * adding files never move these assertions.
 */
class PmdaP4RulesTest
{

    private static final Set<String> BATCH_IDS = Set.of(//
            "PMDA-DD0019", "PMDA-DD0020B", "PMDA-DD0020B-B", "PMDA-DD0021", "PMDA-DD0022",
            "PMDA-DD0022-B", "PMDA-DD0022-C", "PMDA-DD0022-D", "PMDA-DD0022-E", "PMDA-DD0022-F",
            "PMDA-DD0022-G", "PMDA-DD0022-H", "PMDA-DD0022-I", "PMDA-DD0022-J", "PMDA-DD0025",
            "PMDA-DD0030", "PMDA-DD0044", "PMDA-DD0048", "PMDA-DD0052", "PMDA-DD0053",
            "PMDA-DD0053-B", "PMDA-DD0055", "PMDA-DD0055-B", "PMDA-DD0064", "PMDA-DD0064-B",
            "PMDA-DD0064-C", "PMDA-DD0064-D", "PMDA-DD0073", "PMDA-DD0073-B", "PMDA-DD0073-C",
            "PMDA-DD0073-D", "PMDA-DD0073-E", "PMDA-DD0074", "PMDA-DD0104", "PMDA-DD0106",
            "PMDA-DD0107", "PMDA-DD0108", "PMDA-DD0109", "PMDA-DD0110", "PMDA-DD0112",
            "PMDA-DD0112-B", "PMDA-DD0113", "PMDA-DD0123", "PMDA-DD0130", "PMDA-DD0135",
            "PMDA-DD0141", "PMDA-DD0148", "PMDA-OD0019", "PMDA-OD0021", "PMDA-OD0072",
            "PMDA-OD0073", "PMDA-OD0074", "PMDA-OD0075", "PMDA-OD0076", "PMDA-OD0077",
            "PMDA-OD0077-B", "PMDA-OD0078", "PMDA-OD0080");

    /** The batch's 2.1-only legs (skipped on 2.0 documents). */
    private static final Set<String> V21_ONLY_IDS = Set.of(//
            "PMDA-DD0020B", "PMDA-DD0022", "PMDA-DD0022-B", "PMDA-DD0022-C", "PMDA-DD0022-D",
            "PMDA-DD0022-E", "PMDA-DD0073", "PMDA-DD0073-B", "PMDA-DD0073-C", "PMDA-DD0130",
            "PMDA-DD0141");

    /** The batch's 2.0-only legs (skipped on 2.1 documents). */
    private static final Set<String> V20_ONLY_IDS = Set.of(//
            "PMDA-DD0020B-B", "PMDA-DD0021", "PMDA-DD0022-F", "PMDA-DD0022-G", "PMDA-DD0022-H",
            "PMDA-DD0022-I", "PMDA-DD0022-J", "PMDA-DD0055", "PMDA-DD0055-B", "PMDA-DD0073-D",
            "PMDA-DD0073-E");

    /**
     * DD0112 ("no MedDRA definition anywhere") cannot fire on the 2.1 violations document, which
     * must carry MedDRA codelists for the DD0025/DD0112-B/DD0113 violators; it fires on the 2.0
     * fixture instead.
     */
    private static final Map<String, Integer> EXPECTED_21 = Map.of("PMDA-DD0112", 0);

    /** Dual-version rules with a violator in the 2.0 fixture. */
    private static final Map<String, Integer> EXPECTED_20 = Map.of("PMDA-DD0112", 1);

    /** Surefire pins the CWD to target/test-cwd; anchor via projectBasedir (root pom). */
    private static final Path RULES_DIR = TestRules.PMDA;

    private static DocumentContext context(String aFixture, String aVersion)
    {
        try (InputStream in = PmdaP4RulesTest.class.getResourceAsStream("/fixtures/" + aFixture))
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
        assertEquals(BATCH_IDS.size(), batch.size(), "batch-P4 rule files present");
        RuleEvaluator evaluator = new RuleEvaluator();
        return batch.stream().collect(Collectors.toMap(ConformanceRule::ruleId,
                rule -> evaluator.evaluate(rule, aContext)));
    }


    @Test
    void cleanFixtureProducesNoFindings()
    {
        Map<String, RuleResult> results = evaluateBatch(context("pmda-p4-clean-21.xml", "2.1"));
        assertEquals(BATCH_IDS.size(), results.size());
        for (RuleResult result : results.values())
        {
            String ruleId = result.rule().ruleId();
            ExecutionStatus expected = V20_ONLY_IDS.contains(ruleId)
                    ? ExecutionStatus.NOT_APPLICABLE_VERSION
                    : ExecutionStatus.EXECUTED;
            assertEquals(expected, result.status(), ruleId + " status");
            assertEquals(List.of(), result.findings(), ruleId + " findings");
        }
    }


    @Test
    void violationsFixtureFiresEach21RuleExactlyOnce()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("pmda-p4-violations-21.xml", "2.1"));
        for (String ruleId : BATCH_IDS)
        {
            RuleResult result = results.get(ruleId);
            if (V20_ONLY_IDS.contains(ruleId))
            {
                assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, result.status(),
                        ruleId + " status under 2.1");
                continue;
            }
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            assertEquals(EXPECTED_21.getOrDefault(ruleId, 1).intValue(), result.findings().size(),
                    ruleId + " finding count: " + result.findings());
        }
    }


    @Test
    void versionGateSkips21OnlyRulesOn20Documents()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("pmda-p4-violations-21.xml", "2.0"));
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
                context("pmda-p4-violations-20.xml", "2.0"));
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
            int expected = V20_ONLY_IDS.contains(ruleId) ? 1
                    : EXPECTED_20.getOrDefault(ruleId, 0).intValue();
            assertEquals(expected, result.findings().size(),
                    ruleId + " finding count: " + result.findings());
        }
        assertTrue(results.get("PMDA-DD0021").findings().get(0).getXpath().contains("MDV.1"),
                "DD0021 fires on the MetaDataVersion with StandardName SDTM");
        assertTrue(results.get("PMDA-DD0055").findings().get(0).getXpath()
                .contains("ItemGroupDef[@OID='IG.SDTM20']"), "DD0055 fires on the SDTM dataset");
        assertTrue(results.get("PMDA-DD0073-E").findings().get(0).getXpath()
                .contains("ItemDef[@OID='IT.LBSPEC20']"), "DD0073-E fires on the SEND origin");
    }


    @Test
    void severitiesMessagesAndXpathsMatchTheSheet()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("pmda-p4-violations-21.xml", "2.1"));

        // Sheet severity column verbatim: the Reject rows of this batch are DD0020B,
        // DD0021, DD0022 and DD0025 (DD0021 is 2.0-only, asserted via the rule).
        ConformanceFinding defineVersion = results.get("PMDA-DD0020B").findings().get(0);
        assertEquals(Severity.REJECT, defineVersion.getSeverity());
        assertEquals("Invalid def:DefineVersion value", defineVersion.getMessage());

        ConformanceFinding meddraVersion = results.get("PMDA-DD0025").findings().get(0);
        assertEquals(Severity.REJECT, meddraVersion.getSeverity());
        assertEquals("Invalid MedDRA Version 26", meddraVersion.getMessage());
        assertTrue(meddraVersion.getXpath().contains("CodeList[@OID='CL.MEDDRA']"),
                meddraVersion.getXpath());

        ConformanceFinding standardVersion = results.get("PMDA-DD0022").findings().get(0);
        assertEquals(Severity.REJECT, standardVersion.getSeverity());
        assertEquals("Invalid Standard Version value 3.1.1 for SDTM-IG",
                standardVersion.getMessage());
        assertTrue(standardVersion.getXpath().contains("Standard[@OID='STD.SDTM.BAD']"),
                standardVersion.getXpath());

        ConformanceFinding fileType = results.get("PMDA-DD0019").findings().get(0);
        assertEquals(Severity.ERROR, fileType.getSeverity());
        assertEquals("Invalid FileType value", fileType.getMessage());

        ConformanceFinding archiveMismatch = results.get("PMDA-DD0052").findings().get(0);
        assertEquals(Severity.ERROR, archiveMismatch.getSeverity());
        assertEquals("SASDatasetName/def:ArchiveLocationID mismatch [AE vs ea]",
                archiveMismatch.getMessage());

        ConformanceFinding meddraReference = results.get("PMDA-DD0113").findings().get(0);
        assertEquals(Severity.ERROR, meddraReference.getSeverity());
        assertTrue(meddraReference.getXpath().contains("ItemDef[@OID='IT.AEDECOD']"),
                meddraReference.getXpath());

        ConformanceFinding usubjidOrigin = results.get("PMDA-DD0109").findings().get(0);
        assertEquals(Severity.WARNING, usubjidOrigin.getSeverity());
        assertEquals("Inconsistent Origin for USUBJID", usubjidOrigin.getMessage());
        assertTrue(usubjidOrigin.getXpath().contains("ItemDef[@OID='IT.USUBJID2']"),
                usubjidOrigin.getXpath());

        ConformanceFinding originType = results.get("PMDA-DD0073").findings().get(0);
        assertEquals(Severity.ERROR, originType.getSeverity());
        assertTrue(originType.getXpath().contains("ItemDef[@OID='IT.AESEV']"),
                originType.getXpath());

        ConformanceFinding termType = results.get("PMDA-OD0077").findings().get(0);
        assertEquals(Severity.WARNING, termType.getSeverity());
        assertEquals("Codelist/Term Data Type mismatch", termType.getMessage());
        assertTrue(termType.getXpath().contains("CodeList[@OID='CL.INT']"), termType.getXpath());

        ConformanceFinding lengthMismatch = results.get("PMDA-DD0123").findings().get(0);
        assertEquals("Variable/Value Level Length mismatch [300 vs 200]",
                lengthMismatch.getMessage());
    }

}
