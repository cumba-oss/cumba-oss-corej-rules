package net.cumba.corej.define.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import org.junit.jupiter.api.io.TempDir;

/**
 * PMDA batch P5b (submission-folder + split-dataset/misc cluster, plan §3.6/§6.5): 8 sheet rows
 * authored as 8 rule files. DD0084 is the first folder-gated rule ({@code Requires: folder},
 * SKIPPED_MISSING_FOLDER without a submission folder); DD0049/DD0050/DD0063/DD0075/DD0114/DD0115
 * are custom checks (split detection / AnnotatedCRF membership need document-wide logic); DD0102 is
 * declarative (forward deref from the AnnotatedCRF's own DocumentRef). DD0101 (define.xml per study
 * in the eCTD folder) is deliberately not authored — see the batch report. DD0085 (stylesheet PI)
 * was authored later via the {@code stylesheet_file_exists} kind ({@link PmdaDd0085RuleTest}). All
 * 8 rules are dual-version; the 2.0 fixture exercises the 2.0-shaped legs (StandardName guard,
 * Type='CRF' origin, def:Class attribute) organically, not via version gates. Loads only this
 * batch's rules (filtered by id) so concurrent batches adding files never move these assertions.
 */
class PmdaP5bRulesTest
{

    private static final Set<String> BATCH_IDS = Set.of(//
            "PMDA-DD0049", "PMDA-DD0050", "PMDA-DD0063", "PMDA-DD0075", "PMDA-DD0084",
            "PMDA-DD0102", "PMDA-DD0114", "PMDA-DD0115");

    /** Surefire pins the CWD to target/test-cwd; anchor via projectBasedir (root pom). */
    private static final Path RULES_DIR = TestRules.PMDA;

    @TempDir
    Path tempDir;

    private static DocumentContext context(String aFixture, String aVersion, Path aFolder)
    {
        try (InputStream in = PmdaP5bRulesTest.class.getResourceAsStream("/fixtures/" + aFixture))
        {
            org.w3c.dom.Document doc = DefineDomIo.parse(Objects.requireNonNull(in, aFixture));
            ElementNode root = ElementNodeBuilder.build(doc);
            return new DocumentContext(root, aVersion, null, aFolder);
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
        assertEquals(BATCH_IDS.size(), batch.size(), "batch-P5b rule files present");
        RuleEvaluator evaluator = new RuleEvaluator();
        return batch.stream().collect(Collectors.toMap(ConformanceRule::ruleId,
                rule -> evaluator.evaluate(rule, aContext)));
    }


    private Path submissionFolder(String... aFiles)
    {
        try
        {
            Path folder = Files.createDirectories(tempDir.resolve("submission"));
            for (String file : aFiles)
            {
                Files.createFile(folder.resolve(file));
            }
            return folder;
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }


    @Test
    void cleanFixtureProducesNoFindings()
    {
        Path folder = submissionFolder("dm.xpt", "qscg.xpt", "qsmd.xpt", "acrf.pdf");
        Map<String, RuleResult> results = evaluateBatch(
                context("pmda-p5b-clean-21.xml", "2.1", folder));
        assertEquals(BATCH_IDS.size(), results.size());
        for (RuleResult result : results.values())
        {
            String ruleId = result.rule().ruleId();
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            assertEquals(List.of(), result.findings(), ruleId + " findings");
        }
    }


    @Test
    void folderGatedRuleSkipsWithoutSubmissionFolder()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("pmda-p5b-clean-21.xml", "2.1", null));
        RuleResult gated = results.get("PMDA-DD0084");
        assertEquals(ExecutionStatus.SKIPPED_MISSING_FOLDER, gated.status());
        assertEquals(List.of(), gated.findings());
        for (String ruleId : BATCH_IDS)
        {
            if (!"PMDA-DD0084".equals(ruleId))
            {
                assertEquals(ExecutionStatus.EXECUTED, results.get(ruleId).status(),
                        ruleId + " status without a folder");
            }
        }
    }


    @Test
    void violationsFixtureFiresEachRuleExactlyOnce()
    {
        // Every referenced file except dm.xpt exists — exactly one DD0084 violator.
        Path folder = submissionFolder("qscg.xpt", "qsmd.xpt", "xx1.xpt", "xx2.xpt", "ae.xpt",
                "ae1.xpt", "annotated-crf.pdf", "suppdoc.pdf");
        Map<String, RuleResult> results = evaluateBatch(
                context("pmda-p5b-violations-21.xml", "2.1", folder));
        for (String ruleId : BATCH_IDS)
        {
            RuleResult result = results.get(ruleId);
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            assertEquals(1, result.findings().size(),
                    ruleId + " finding count: " + result.findings());
        }

        assertTrue(results.get("PMDA-DD0049").findings().get(0).getXpath()
                .contains("ItemGroupDef[@OID='IG.DM']"), "DD0049 fires on the non-split DM");
        assertTrue(results.get("PMDA-DD0050").findings().get(0).getXpath()
                .contains("ItemGroupDef[@OID='IG.QSMD']"), "DD0050 fires on the QX prefix");
        assertTrue(results.get("PMDA-DD0063").findings().get(0).getXpath()
                .contains("ItemGroupDef[@OID='IG.QSCG']"), "DD0063 fires on the Alias-less part");
        assertTrue(results.get("PMDA-DD0075").findings().get(0).getXpath()
                .contains("ItemDef[@OID='IT.QSCAT']"), "DD0075 fires on the Subject origin");
        assertTrue(results.get("PMDA-DD0084").findings().get(0).getXpath()
                .contains("ItemGroupDef[@OID='IG.DM']/leaf"), "DD0084 fires on the missing dm.xpt");
        assertTrue(results.get("PMDA-DD0102").findings().get(0).getXpath().contains("AnnotatedCRF"),
                "DD0102 fires on the AnnotatedCRF DocumentRef");
        assertTrue(results.get("PMDA-DD0114").findings().get(0).getXpath()
                .contains("ItemGroupDef[@OID='IG.XX2']"), "DD0114 fires on the RELATIONSHIP part");
        assertTrue(results.get("PMDA-DD0115").findings().get(0).getXpath()
                .contains("ItemGroupDef[@OID='IG.AE']"), "DD0115 fires on the unsplit AE");
    }


    @Test
    void define20FixtureExercisesThe20ShapedLegs()
    {
        Map<String, RuleResult> results = evaluateBatch(
                context("pmda-p5b-violations-20.xml", "2.0", null));

        assertEquals(ExecutionStatus.SKIPPED_MISSING_FOLDER, results.get("PMDA-DD0084").status());

        Map<String, Integer> expected = Map.of("PMDA-DD0049", 1, "PMDA-DD0075", 1, "PMDA-DD0114", 1,
                "PMDA-DD0050", 0, "PMDA-DD0063", 0, "PMDA-DD0102", 0, "PMDA-DD0115", 0);
        for (Map.Entry<String, Integer> entry : expected.entrySet())
        {
            RuleResult result = results.get(entry.getKey());
            assertEquals(ExecutionStatus.EXECUTED, result.status(), entry.getKey() + " status");
            assertEquals(entry.getValue().intValue(), result.findings().size(),
                    entry.getKey() + " finding count: " + result.findings());
        }
        assertTrue(
                results.get("PMDA-DD0049").findings().get(0).getXpath()
                        .contains("ItemGroupDef[@OID='IG.VS']"),
                "DD0049 fires via the 2.0 StandardName guard leg");
        assertTrue(
                results.get("PMDA-DD0075").findings().get(0).getXpath()
                        .contains("ItemDef[@OID='IT.VSORRES']"),
                "DD0075 fires on the DocumentRef-less CRF origin (review-pmda-p3 W1 shape)");
        assertTrue(
                results.get("PMDA-DD0114").findings().get(0).getXpath()
                        .contains("ItemGroupDef[@OID='IG.EG2']"),
                "DD0114 fires via the 2.0 def:Class attribute branch");
    }


    @Test
    void severitiesVersionsAndMessagesMatchTheSheet()
    {
        Path folder = submissionFolder("qscg.xpt", "qsmd.xpt", "xx1.xpt", "xx2.xpt", "ae.xpt",
                "ae1.xpt", "annotated-crf.pdf", "suppdoc.pdf");
        Map<String, RuleResult> results = evaluateBatch(
                context("pmda-p5b-violations-21.xml", "2.1", folder));

        // Sheet severity column: every P5b row is Error; all rows are 2.0+2.1.
        for (String ruleId : BATCH_IDS)
        {
            ConformanceRule rule = results.get(ruleId).rule();
            assertEquals(Severity.ERROR, rule.effectiveSeverity(), ruleId + " severity");
            assertEquals(List.of("2.0", "2.1"), rule.applicableVersions(), ruleId + " versions");
        }

        assertEquals("Name/Domain/SASDatasetName mismatch",
                results.get("PMDA-DD0049").findings().get(0).getMessage());
        assertEquals("Domain/SASDatasetName mismatch for split dataset",
                results.get("PMDA-DD0050").findings().get(0).getMessage());
        assertEquals("Missing Alias", results.get("PMDA-DD0063").findings().get(0).getMessage());
        assertEquals("CRF Origin is not referencing AnnotatedCRF",
                results.get("PMDA-DD0075").findings().get(0).getMessage());
        assertEquals("Referenced File is missing [dm.xpt]",
                results.get("PMDA-DD0084").findings().get(0).getMessage());
        assertEquals("Invalid Annotated CRF document name",
                results.get("PMDA-DD0102").findings().get(0).getMessage());
        assertEquals("Invalid usage of split datasets for non-general-observation-class datasets",
                results.get("PMDA-DD0114").findings().get(0).getMessage());
        assertEquals("Split and unsplit datasets are both listed",
                results.get("PMDA-DD0115").findings().get(0).getMessage());
    }


    /**
     * DD0084 engine semantics at the rule level: a href escaping the submission folder counts as
     * missing even when the target file exists on disk, and a {@code #fragment} is stripped before
     * resolution. Sub-directory hrefs resolve too.
     */
    @Test
    void referencedFileEscapesAndFragmentsAreHandled()
    {
        try
        {
            Files.createFile(tempDir.resolve("outside.xpt"));
            Path folder = Files.createDirectories(tempDir.resolve("submission"));
            Files.createFile(folder.resolve("doc.pdf"));
            Files.createDirectories(folder.resolve("datasets"));
            Files.createFile(folder.resolve("datasets").resolve("dm.xpt"));
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
        String doc = """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1"
                     xmlns:xlink="http://www.w3.org/1999/xlink">
                  <Study OID="ST.1">
                    <MetaDataVersion OID="MDV.1" Name="MDV">
                      <def:leaf ID="LF.ESCAPE" xlink:href="../outside.xpt"/>
                      <def:leaf ID="LF.FRAGMENT" xlink:href="doc.pdf#section2"/>
                      <def:leaf ID="LF.SUBDIR" xlink:href="datasets/dm.xpt"/>
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """;
        DocumentContext context;
        try (var in = new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8)))
        {
            context = new DocumentContext(ElementNodeBuilder.build(DefineDomIo.parse(in)), "2.1",
                    null, tempDir.resolve("submission"));
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
        RuleResult result = evaluateBatch(context).get("PMDA-DD0084");
        assertEquals(ExecutionStatus.EXECUTED, result.status());
        List<ConformanceFinding> findings = result.findings();
        assertEquals(1, findings.size(), "only the escaping href fires: " + findings);
        assertTrue(findings.get(0).getMessage().contains("../outside.xpt"),
                findings.get(0).getMessage());
    }

}
