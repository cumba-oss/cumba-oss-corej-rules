package net.cumba.corej.define.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.define.conformance.ct.StubCtProvider;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.eval.RuleEvaluator;
import net.cumba.corej.define.conformance.eval.RuleResult;
import net.cumba.corej.define.conformance.report.ExecutionStatus;
import net.cumba.corej.define.conformance.report.Severity;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.RuleRepository;
import net.cumba.corej.define.conformance.tree.ElementNode;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The P5 CT-gated batch (plan §3.6/§6.5): 13 sheet rows authored as 20 rule files (DD0024 / DD0028
 * / DD0032 / DD0034 split into EnumeratedItem + CodeListItem legs; DD0021-B / DD0055-C are the
 * deferred 2.1 legs of their P4 base rules; CDISC 132/153/179/186/187/192/201/202 are the
 * CT-deferred CDISC backlog — 153 per its formal Rule column needs no CT and is the batch's one
 * ungated rule). Fixtures: a clean 2.1 document (also evaluated as 2.0 — the CT rules are
 * version-gated, not re-authored, per document version), a 2.1 violations document, and a 2.0
 * violations document for the 2.0-only DD0031. Loads only this batch's rules (filtered by id) so
 * concurrent batches adding files never move these assertions.
 */
class CtRulesTest
{

    private static final Set<String> BATCH_IDS = Set.of(//
            "DEFINE-XML-0132", "DEFINE-XML-0153", "DEFINE-XML-0179", "DEFINE-XML-0186",
            "DEFINE-XML-0187", "DEFINE-XML-0192", "DEFINE-XML-0201", "DEFINE-XML-0202",
            "PMDA-DD0021-B", "PMDA-DD0024", "PMDA-DD0024-B", "PMDA-DD0028", "PMDA-DD0028-B",
            "PMDA-DD0031", "PMDA-DD0032", "PMDA-DD0032-B", "PMDA-DD0033", "PMDA-DD0034",
            "PMDA-DD0034-B", "PMDA-DD0055-C");

    /** 2.1-only rules (all CDISC rows plus the two deferred PMDA 2.1 legs). */
    private static final Set<String> V21_ONLY_IDS = Set.of(//
            "DEFINE-XML-0132", "DEFINE-XML-0153", "DEFINE-XML-0179", "DEFINE-XML-0186",
            "DEFINE-XML-0187", "DEFINE-XML-0192", "DEFINE-XML-0201", "DEFINE-XML-0202",
            "PMDA-DD0021-B", "PMDA-DD0055-C");

    /** 2.0-only rules. */
    private static final Set<String> V20_ONLY_IDS = Set.of("PMDA-DD0031");

    /**
     * Expected findings per rule on the 2.1 violations fixture. 179/192 fire twice by design: once
     * on their dedicated non-member and once on the unmarked extension shared with 186/201 (an
     * unmarked extension both mismatches CT and lacks its marking).
     */
    private static final Map<String, Integer> EXPECTED_21 = Map.ofEntries(//
            Map.entry("DEFINE-XML-0132", 1), Map.entry("DEFINE-XML-0153", 1),
            Map.entry("DEFINE-XML-0179", 2), Map.entry("DEFINE-XML-0186", 1),
            Map.entry("DEFINE-XML-0187", 1), Map.entry("DEFINE-XML-0192", 2),
            Map.entry("DEFINE-XML-0201", 1), Map.entry("DEFINE-XML-0202", 1),
            Map.entry("PMDA-DD0021-B", 1), Map.entry("PMDA-DD0024", 1),
            Map.entry("PMDA-DD0024-B", 1), Map.entry("PMDA-DD0028", 1),
            Map.entry("PMDA-DD0028-B", 1), Map.entry("PMDA-DD0032", 1),
            Map.entry("PMDA-DD0032-B", 1), Map.entry("PMDA-DD0033", 1), Map.entry("PMDA-DD0034", 1),
            Map.entry("PMDA-DD0034-B", 1), Map.entry("PMDA-DD0055-C", 1));

    /** Expected findings per 2.0-applicable rule on the 2.0 violations fixture. */
    private static final Map<String, Integer> EXPECTED_20 = Map.of(//
            "PMDA-DD0031", 1, "PMDA-DD0024", 1);

    /** Surefire pins the CWD to target/test-cwd; anchor via projectBasedir (root pom). */
    private static final Path RULES_ROOT = TestRules.DIR;

    private static DocumentContext context(String aFixture, String aVersion, boolean aWithCt)
    {
        try (InputStream in = CtRulesTest.class.getResourceAsStream("/fixtures/" + aFixture))
        {
            org.w3c.dom.Document doc = DefineDomIo.parse(Objects.requireNonNull(in, aFixture));
            ElementNode root = ElementNodeBuilder.build(doc);
            return new DocumentContext(root, aVersion, aWithCt ? new StubCtProvider() : null, null);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot load fixture " + aFixture, e);
        }
    }


    private static Map<String, RuleResult> evaluateBatch(DocumentContext aContext)
    {
        List<ConformanceRule> batch = Stream
                .concat(RuleRepository.loadDirectory(RULES_ROOT.resolve("CDISC")).stream(),
                        RuleRepository.loadDirectory(RULES_ROOT.resolve("PMDA")).stream())
                .filter(rule -> BATCH_IDS.contains(rule.ruleId())).toList();
        assertEquals(BATCH_IDS.size(), batch.size(), "batch-CT rule files present");
        RuleEvaluator evaluator = new RuleEvaluator();
        return batch.stream().collect(Collectors.toMap(ConformanceRule::ruleId,
                rule -> evaluator.evaluate(rule, aContext)));
    }


    private static void assertStatuses(Map<String, RuleResult> aResults, String aVersion,
            @Nullable Map<String, Integer> aExpectedCounts)
    {
        assertEquals(BATCH_IDS.size(), aResults.size());
        for (String ruleId : BATCH_IDS)
        {
            RuleResult result = aResults.get(ruleId);
            boolean applicable = "2.1".equals(aVersion) ? !V20_ONLY_IDS.contains(ruleId)
                    : !V21_ONLY_IDS.contains(ruleId);
            if (!applicable)
            {
                assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, result.status(),
                        ruleId + " status");
                continue;
            }
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            int expected = aExpectedCounts == null ? 0 : aExpectedCounts.getOrDefault(ruleId, 0);
            assertEquals(expected, result.findings().size(),
                    ruleId + " finding count: " + result.findings());
        }
    }


    @Test
    void cleanFixtureProducesNoFindingsAt21()
    {
        assertStatuses(evaluateBatch(context("ct-clean-21.xml", "2.1", true)), "2.1", null);
    }


    @Test
    void cleanFixtureProducesNoFindingsAt20()
    {
        assertStatuses(evaluateBatch(context("ct-clean-21.xml", "2.0", true)), "2.0", null);
    }


    @Test
    void violationsFixtureFiresEachApplicableRuleAt21()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("ct-violations-21.xml", "2.1", true));
        assertStatuses(results, "2.1", EXPECTED_21);
        // Severities travel from the sheets: PMDA rows verbatim, CDISC rows default ERROR.
        assertEquals(Severity.REJECT, results.get("PMDA-DD0021-B").findings().get(0).getSeverity());
        assertEquals(Severity.WARNING, results.get("PMDA-DD0024").findings().get(0).getSeverity());
        assertEquals(Severity.ERROR,
                results.get("DEFINE-XML-0179").findings().get(0).getSeverity());
        assertEquals("Invalid Term X in codelist",
                results.get("PMDA-DD0024").findings().get(0).getMessage());
    }


    @Test
    void violationsFixtureFiresTheV20RulesAt20()
    {
        assertStatuses(evaluateBatch(context("ct-violations-20.xml", "2.0", true)), "2.0",
                EXPECTED_20);
    }


    @Test
    void withoutProviderEveryCtGatedRuleSkipsExplicitly()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("ct-violations-21.xml", "2.1", false));
        for (String ruleId : BATCH_IDS)
        {
            RuleResult result = results.get(ruleId);
            if ("PMDA-DD0031".equals(ruleId))
            {
                // 2.0-only: the version gate fires before the CT gate.
                assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, result.status(), ruleId);
            }
            else if ("DEFINE-XML-0153".equals(ruleId))
            {
                // The batch's one ungated rule (formal Rule column is intra-document).
                assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId);
                assertEquals(1, result.findings().size(), ruleId);
            }
            else
            {
                assertEquals(ExecutionStatus.SKIPPED_MISSING_CT, result.status(), ruleId);
                assertEquals(List.of(), result.findings(), ruleId);
            }
        }
    }

}
