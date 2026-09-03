package net.cumba.corej.define.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.EnumSet;
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
 * PMDA batch P2 (duplicates/uniqueness + OID/reference integrity, plan §6.5): 37 sheet rows
 * authored as 38 rule files (OD0079 splits into EnumeratedItem/CodeListItem legs), covering the
 * inventory's declarative unique_among_siblings / unique_in_document / references rows plus the
 * seven orphan rows (DD0067/78/79/80/81/82/139) rescued by the is_referenced kind (plan §3.35).
 * Fixtures: a clean 2.1 document (zero findings — every definable entity referenced, all scoped
 * values unique) and a 2.1 violations document (one dedicated violator per rule). The same
 * violations document evaluated as 2.0 exercises the version gate: the four 2.1-only rules skip,
 * the version-agnostic remainder still fires exactly once. Loads only this batch's rules (filtered
 * by id) so concurrent batches adding files never move these assertions.
 */
class PmdaP2RulesTest
{

    private static final Set<String> BATCH_IDS = Set.of(//
            "PMDA-DD0012", "PMDA-DD0013", "PMDA-DD0014", "PMDA-DD0015", "PMDA-DD0016",
            "PMDA-DD0017", "PMDA-DD0018", "PMDA-DD0041", "PMDA-DD0051", "PMDA-DD0062",
            "PMDA-DD0067", "PMDA-DD0071", "PMDA-DD0078", "PMDA-DD0079", "PMDA-DD0080",
            "PMDA-DD0081", "PMDA-DD0082", "PMDA-DD0083", "PMDA-DD0088", "PMDA-DD0089",
            "PMDA-DD0091", "PMDA-DD0095", "PMDA-DD0097", "PMDA-DD0122", "PMDA-DD0125",
            "PMDA-DD0131", "PMDA-DD0139", "PMDA-OD0022", "PMDA-OD0027", "PMDA-OD0030",
            "PMDA-OD0031", "PMDA-OD0032", "PMDA-OD0041", "PMDA-OD0042", "PMDA-OD0046",
            "PMDA-OD0048", "PMDA-OD0079", "PMDA-OD0079-B");

    /** The batch's 2.1-only rules (sheet 2.1 X-mark without the 2.0 one). */
    private static final Set<String> V21_ONLY_IDS = Set.of(//
            "PMDA-DD0122", "PMDA-DD0125", "PMDA-DD0131", "PMDA-DD0139");

    /** Surefire pins the CWD to target/test-cwd; anchor via projectBasedir (root pom). */
    private static final Path RULES_DIR = TestRules.PMDA;

    private static DocumentContext context(String aFixture, String aVersion)
    {
        try (InputStream in = PmdaP2RulesTest.class.getResourceAsStream("/fixtures/" + aFixture))
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
        assertEquals(BATCH_IDS.size(), batch.size(), "batch-P2 rule files present");
        RuleEvaluator evaluator = new RuleEvaluator();
        return batch.stream().collect(Collectors.toMap(ConformanceRule::ruleId,
                rule -> evaluator.evaluate(rule, aContext)));
    }


    @Test
    void cleanFixtureProducesNoFindings()
    {
        Map<String, RuleResult> results = evaluateBatch(context("pmda-p2-clean-21.xml", "2.1"));
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
                context("pmda-p2-violations-21.xml", "2.1"));
        for (String ruleId : BATCH_IDS)
        {
            RuleResult result = results.get(ruleId);
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            assertEquals(1, result.findings().size(),
                    ruleId + " finding count: " + result.findings());
        }
    }


    @Test
    void versionGateSkips21OnlyRulesAndKeepsTheRestOn20Documents()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("pmda-p2-violations-21.xml", "2.0"));
        for (String ruleId : BATCH_IDS)
        {
            RuleResult result = results.get(ruleId);
            if (V21_ONLY_IDS.contains(ruleId))
            {
                assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, result.status(),
                        ruleId + " status under 2.0");
                continue;
            }
            // The batch's 2.0+2.1 rows are version-agnostic (no 2.0-specific guard legs),
            // so the same violators fire under a 2.0 evaluation.
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status under 2.0");
            assertEquals(1, result.findings().size(),
                    ruleId + " finding count under 2.0: " + result.findings());
        }
    }


    @Test
    void severitiesMessagesAndXpathsMatchTheSheet()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("pmda-p2-violations-21.xml", "2.1"));

        // The sheet's severity column verbatim: the batch carries all three PMDA levels
        // (Reject on OD0022/OD0027, Warning on OD0042 and the orphan rows, Error elsewhere).
        EnumSet<Severity> present = results.values().stream()
                .map(result -> result.rule().effectiveSeverity())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Severity.class)));
        assertEquals(EnumSet.of(Severity.REJECT, Severity.ERROR, Severity.WARNING), present);

        ConformanceFinding dupStudy = results.get("PMDA-OD0022").findings().get(0);
        assertEquals(Severity.REJECT, dupStudy.getSeverity());
        assertEquals("Duplicate Study OID [ST.P2V]", dupStudy.getMessage());
        assertTrue(dupStudy.getXpath().contains("Study[@OID='ST.P2V']"), dupStudy.getXpath());

        ConformanceFinding orphanMethod = results.get("PMDA-DD0080").findings().get(0);
        assertEquals(Severity.WARNING, orphanMethod.getSeverity());
        assertEquals("Method MT.ORPHAN is not referenced", orphanMethod.getMessage());
        assertTrue(orphanMethod.getXpath().contains("MethodDef[@OID='MT.ORPHAN']"),
                orphanMethod.getXpath());

        // ItemRef carries no OID, so its xpath step is positional under the dataset.
        ConformanceFinding danglingItem = results.get("PMDA-OD0046").findings().get(0);
        assertEquals(Severity.ERROR, danglingItem.getSeverity());
        assertEquals("Referenced ItemDef is missing [IT.NOWHERE]", danglingItem.getMessage());
        assertTrue(danglingItem.getXpath().contains("ItemGroupDef[@OID='IG.SAS2']/ItemRef[2]"),
                danglingItem.getXpath());

        // DD0125's guarded not_exists path reports without a value (see the rule file).
        ConformanceFinding wrongTypeStandard = results.get("PMDA-DD0125").findings().get(0);
        assertEquals("Referenced CT Standard is missing", wrongTypeStandard.getMessage());
        assertTrue(wrongTypeStandard.getXpath().contains("CodeList[@OID='CL.WRONGSTD']"),
                wrongTypeStandard.getXpath());

        ConformanceFinding dupLang = results.get("PMDA-DD0062").findings().get(0);
        assertEquals("Duplicate xml:lang [en]", dupLang.getMessage());
        assertTrue(
                dupLang.getXpath()
                        .contains("ItemGroupDef[@OID='IG.SAS1']/Description/TranslatedText[2]"),
                dupLang.getXpath());

        ConformanceFinding dupTerm = results.get("PMDA-OD0079").findings().get(0);
        assertEquals("Duplicate Term [M] in Codelist", dupTerm.getMessage());
        assertTrue(dupTerm.getXpath().contains("CodeList[@OID='CL.TERMDUP']/EnumeratedItem[2]"),
                dupTerm.getXpath());

        ConformanceFinding orphanStandard = results.get("PMDA-DD0139").findings().get(0);
        assertEquals("Standard 'STD.ORPHAN' is not referenced", orphanStandard.getMessage());
    }

}
