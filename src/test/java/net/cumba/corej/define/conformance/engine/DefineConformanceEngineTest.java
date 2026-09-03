package net.cumba.corej.define.conformance.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.cumba.corej.define.conformance.TestRules;
import net.cumba.corej.define.conformance.report.Category;
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import net.cumba.corej.define.conformance.report.ExecutionStatus;
import net.cumba.corej.define.conformance.report.RuleExecution;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.Requires;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end engine tests over the committed fixtures. Assertions are written to survive the
 * concurrent addition of new rules: they assert per-rule execution completeness and the gate-driven
 * statuses of the rules that declare {@code Requires}, never a corpus-wide finding total.
 */
class DefineConformanceEngineTest
{

    private static final Instant PINNED = Instant.parse("2026-07-03T12:00:00Z");

    private static final List<ConformanceRule> RULES = TestRules.load();

    @TempDir
    Path tempDir;

    private Path fixture(String aName)
    {
        Path target = tempDir.resolve(aName);
        try (InputStream in = DefineConformanceEngineTest.class
                .getResourceAsStream("/fixtures/" + aName))
        {
            Files.write(target, Objects.requireNonNull(in, aName).readAllBytes());
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot stage fixture " + aName, e);
        }
        return target;
    }


    private static Map<String, RuleExecution> byRuleId(DefineConformanceReport aReport)
    {
        return aReport.executions().stream().collect(
                java.util.stream.Collectors.toMap(RuleExecution::getRuleId, Function.identity()));
    }


    @Test
    void pilotCleanReportHasExpectedStructure()
    {
        Path define = fixture("pilot-clean-21.xml");
        DefineConformanceReport report = new DefineConformanceEngine(RULES)
                .validate(DefineConformanceInput.builder(define).build(), PINNED);

        assertEquals(define.toString(), report.defineXml());
        assertEquals("2.1", report.defineVersion());
        assertEquals(PINNED, report.generatedAt());

        // One RuleExecution per shipped rule — completeness, not a finding count.
        assertEquals(RULES.size(), report.executions().size());
        assertEquals(RULES.size(), byRuleId(report).size(), "duplicate execution rows");

        // Every skipped/not-applicable rule reports zero findings.
        for (RuleExecution execution : report.executions())
        {
            if (execution.getStatus() != ExecutionStatus.EXECUTED)
            {
                assertEquals(0, execution.getFindingCount(),
                        () -> execution.getRuleId() + " skipped but has findings");
            }
        }

        // The pilot fixture's one known pre-pass finding (def:Class attribute → PMDA-DD0004).
        assertTrue(report.findings().stream().anyMatch(f -> "PMDA-DD0004".equals(f.getRuleId())),
                "expected the known PMDA-DD0004 pre-pass finding");

        // totalFindings and the summary map agree.
        long summed = report.findingsByCategory().values().stream()
                .flatMap(m -> m.values().stream()).mapToLong(Long::longValue).sum();
        assertEquals(report.totalFindings(), summed);
    }


    @Test
    void ctRulesSkipWhenNoProviderSupplied()
    {
        Path define = fixture("pilot-clean-21.xml");
        DefineConformanceReport report = new DefineConformanceEngine(RULES)
                .validate(DefineConformanceInput.builder(define).build(), PINNED);
        Map<String, RuleExecution> executions = byRuleId(report);

        // Only CT rules that also apply to the document's version reach the CT gate — the
        // version gate runs first (a CT rule scoped to 2.0 alone is NOT_APPLICABLE_VERSION here).
        List<ConformanceRule> ctRules = RULES.stream().filter(r -> r.requires() == Requires.CT)
                .filter(r -> r.applicableVersions().contains("2.1")).toList();
        assertFalse(ctRules.isEmpty(), "expected at least one Requires: ct rule applicable to 2.1");
        for (ConformanceRule rule : ctRules)
        {
            RuleExecution execution = executions.get(rule.ruleId());
            assertNotNull(execution, rule.ruleId());
            assertEquals(ExecutionStatus.SKIPPED_MISSING_CT, execution.getStatus(), rule.ruleId());
        }
    }


    @Test
    void folderRulesSkipWhenFolderDisabled()
    {
        Path define = fixture("pilot-clean-21.xml");
        DefineConformanceReport report = new DefineConformanceEngine(RULES).validate(
                DefineConformanceInput.builder(define).useDefaultSubmissionFolder(false).build(),
                PINNED);
        Map<String, RuleExecution> executions = byRuleId(report);

        List<ConformanceRule> folderRules = RULES.stream()
                .filter(r -> r.requires() == Requires.FOLDER)
                .filter(r -> r.applicableVersions().contains("2.1")).toList();
        assertFalse(folderRules.isEmpty(),
                "expected at least one Requires: folder rule applicable to 2.1");
        for (ConformanceRule rule : folderRules)
        {
            RuleExecution execution = executions.get(rule.ruleId());
            assertNotNull(execution, rule.ruleId());
            assertEquals(ExecutionStatus.SKIPPED_MISSING_FOLDER, execution.getStatus(),
                    rule.ruleId());
        }
    }


    @Test
    void pilotViolationsProducesFindings()
    {
        Path define = fixture("pilot-violations-21.xml");
        DefineConformanceReport report = new DefineConformanceEngine(RULES)
                .validate(DefineConformanceInput.builder(define).build(), PINNED);

        assertFalse(report.findings().isEmpty(), "expected violations to yield findings");
        List<String> ids = report.findings().stream().map(ConformanceFinding::getRuleId).toList();
        assertTrue(ids.contains("DEFINE-XML-0004"), () -> "rule ids: " + ids);
        assertTrue(ids.contains("DEFINE-XML-0006"), () -> "rule ids: " + ids);
    }


    @Test
    void defineXml10IsOutOfScopeNotMislabelledOrRun()
    {
        // review-terminal-b M1: a 1.0 document must NOT run the 2.1 corpus nor be labelled "2.1".
        Path define = fixture("engine-define-10.xml");
        DefineConformanceReport report = new DefineConformanceEngine(RULES)
                .validate(DefineConformanceInput.builder(define).build(), PINNED);

        assertEquals("1.0", report.defineVersion(), "1.0 must be reported, not mislabelled 2.1");
        assertTrue(report.executions().isEmpty(), "the 2.0/2.1 rule corpus must not run on 1.0");
        assertTrue(
                report.findings().stream()
                        .anyMatch(f -> "DEFINE-XML-UNSUPPORTED".equals(f.getRuleId())),
                "expected an out-of-scope finding");
    }


    @Test
    void unparseableDocumentIsReportedNotThrown()
    {
        Path define = fixture("xsd-not-well-formed-21.xml");
        DefineConformanceReport report = new DefineConformanceEngine(RULES)
                .validate(DefineConformanceInput.builder(define).build(), PINNED);

        // No rule ran; an empty execution summary, a fatal XSD-category finding present.
        assertTrue(report.executions().isEmpty(), "no rule should run on an unparseable document");
        assertFalse(report.findings().isEmpty());
        assertTrue(report.findings().stream().anyMatch(f -> f.getCategory() == Category.XSD),
                "expected a fatal XSD-category finding");
    }

}
