package net.cumba.cdisc.define.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.cdisc.define.conformance.engine.DefineConformanceEngine;
import net.cumba.cdisc.define.conformance.engine.DefineConformanceInput;
import net.cumba.cdisc.define.conformance.engine.DefineConformanceReport;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.eval.RuleEvaluator;
import net.cumba.cdisc.define.conformance.eval.RuleResult;
import net.cumba.cdisc.define.conformance.report.ExecutionStatus;
import net.cumba.cdisc.define.conformance.report.Severity;
import net.cumba.cdisc.define.conformance.rule.ConformanceRule;
import net.cumba.cdisc.define.conformance.rule.RuleRepository;
import net.cumba.cdisc.define.conformance.tree.ElementNode;
import net.cumba.cdisc.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

/**
 * PMDA DD0085 ("Missing Define XSL"): the {@code <?xml-stylesheet?>} prolog PI's {@code href} must
 * exist in the submission folder. The PI is prolog content the {@link ElementNode} tree cannot see,
 * so {@link ElementNodeBuilder#stylesheetHrefs} extracts the hrefs off the DOM and the
 * {@code stylesheet_file_exists} kind checks them against the folder — folder-gated like DD0084
 * (SKIPPED_MISSING_FOLDER without one).
 */
class PmdaDd0085RuleTest
{

    private static final Path RULES_DIR = TestRules.PMDA;

    private static final String PROLOG = """
            <?xml version="1.0" encoding="UTF-8"?>
            <?xml-stylesheet type="text/xsl" href="define2-1.xsl"?>
            """;

    private static final String ODM = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.1"
                 FileOID="www.example.org/define/dd0085" FileType="Snapshot" ODMVersion="1.3.2"
                 CreationDateTime="2026-07-23T10:15:30">
              <Study OID="ST.001">
                <GlobalVariables>
                  <StudyName>DD0085</StudyName>
                  <StudyDescription>DD0085 fixture</StudyDescription>
                  <ProtocolName>DD0085</ProtocolName>
                </GlobalVariables>
              </Study>
            </ODM>
            """;

    @TempDir
    Path tempDir;

    private static ConformanceRule rule()
    {
        List<ConformanceRule> matches = RuleRepository.loadDirectory(RULES_DIR).stream()
                .filter(r -> "PMDA-DD0085".equals(r.ruleId())).toList();
        assertEquals(1, matches.size(), "PMDA-DD0085.yaml present");
        return matches.get(0);
    }


    private static DocumentContext context(String aXml, Path aFolder)
    {
        try (InputStream in = new ByteArrayInputStream(aXml.getBytes(StandardCharsets.UTF_8)))
        {
            Document document = DefineDomIo.parse(in);
            return new DocumentContext(ElementNodeBuilder.build(document), "2.1", null, aFolder,
                    ElementNodeBuilder.stylesheetHrefs(document));
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // PI extraction
    // ------------------------------------------------------------------


    @Test
    void extractsPrologStylesheetHrefs() throws Exception
    {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <?xml-stylesheet type="text/xsl" href="define2-1.xsl"?>
                <?xml-stylesheet type='text/xsl' href='extra.xsl'?>
                <?other-target href="not-a-stylesheet.xsl"?>
                <?xml-stylesheet type="text/xsl"?>
                """ + ODM;
        try (InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
        {
            assertEquals(List.of("define2-1.xsl", "extra.xsl"),
                    ElementNodeBuilder.stylesheetHrefs(DefineDomIo.parse(in)),
                    "both quote styles collected, foreign targets and href-less PIs ignored");
        }
    }


    @Test
    void ignoresProcessingInstructionsAfterTheRootElement() throws Exception
    {
        String xml = PROLOG + ODM + "<?xml-stylesheet type=\"text/xsl\" href=\"late.xsl\"?>\n";
        try (InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
        {
            assertEquals(List.of("define2-1.xsl"),
                    ElementNodeBuilder.stylesheetHrefs(DefineDomIo.parse(in)),
                    "only prolog PIs are stylesheet links");
        }
    }

    // ------------------------------------------------------------------
    // Rule evaluation
    // ------------------------------------------------------------------


    @Test
    void firesWhenTheStylesheetFileIsMissing()
    {
        RuleResult result = new RuleEvaluator().evaluate(rule(), context(PROLOG + ODM, tempDir));
        assertEquals(ExecutionStatus.EXECUTED, result.status());
        assertEquals(1, result.findings().size());
        assertEquals("Missing Define XSL [define2-1.xsl]", result.findings().get(0).getMessage());
    }


    @Test
    void cleanWhenTheStylesheetFileExists() throws Exception
    {
        Files.createFile(tempDir.resolve("define2-1.xsl"));
        RuleResult result = new RuleEvaluator().evaluate(rule(), context(PROLOG + ODM, tempDir));
        assertEquals(ExecutionStatus.EXECUTED, result.status());
        assertEquals(List.of(), result.findings());
    }


    @Test
    void cleanWhenTheDocumentHasNoStylesheetPi()
    {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + ODM;
        RuleResult result = new RuleEvaluator().evaluate(rule(), context(xml, tempDir));
        assertEquals(ExecutionStatus.EXECUTED, result.status());
        assertEquals(List.of(), result.findings(),
                "the sheet requires the referenced file to exist, not that one be referenced");
    }


    @Test
    void skipsWithoutASubmissionFolder()
    {
        RuleResult result = new RuleEvaluator().evaluate(rule(), context(PROLOG + ODM, null));
        assertEquals(ExecutionStatus.SKIPPED_MISSING_FOLDER, result.status());
        assertEquals(List.of(), result.findings());
    }


    @Test
    void hrefEscapingTheFolderCountsAsMissingAndFragmentsAreStripped() throws Exception
    {
        Files.createFile(tempDir.resolve("define2-1.xsl"));
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <?xml-stylesheet type="text/xsl" href="../outside.xsl"?>
                <?xml-stylesheet type="text/xsl" href="define2-1.xsl#top"?>
                """ + ODM;
        RuleResult result = new RuleEvaluator().evaluate(rule(), context(xml, tempDir));
        assertEquals(1, result.findings().size(),
                "escaping href flagged, fragment-carrying href resolves to the existing file");
        assertEquals("Missing Define XSL [../outside.xsl]", result.findings().get(0).getMessage());
    }


    @Test
    void sheetSeverityAndVersionsArePinned()
    {
        ConformanceRule rule = rule();
        assertEquals(Severity.ERROR, rule.effectiveSeverity(), "sheet PMDA Severity: Error");
        assertEquals(List.of("2.0", "2.1"), rule.applicableVersions());
    }


    @Test
    void loadRejectsTheKindWithoutTheFolderRequirement()
    {
        ConformanceRule rule = rule();
        ConformanceRule unguarded = new ConformanceRule(rule.ruleId(), rule.sheetRuleIdentifier(),
                rule.ruleSet(), rule.element(), rule.attribute(), rule.applicableVersions(),
                rule.sourceType(), rule.severity(), null, rule.plainTextRule(), rule.message(),
                rule.check());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                unguarded::validate);
        assertTrue(failure.getMessage().contains("stylesheet_file_exists"));
    }

    // ------------------------------------------------------------------
    // Engine integration
    // ------------------------------------------------------------------


    @Test
    void engineRunReportsTheMissingStylesheet() throws Exception
    {
        Path define = tempDir.resolve("define.xml");
        Files.writeString(define, PROLOG + ODM);
        DefineConformanceEngine engine = new DefineConformanceEngine(List.of(rule()));
        // The default submission folder is the define's parent — no explicit folder needed.
        DefineConformanceReport report = engine
                .validate(DefineConformanceInput.builder(define).build());
        assertEquals(1, report.executions().size());
        assertEquals(ExecutionStatus.EXECUTED, report.executions().get(0).getStatus());
        assertEquals(1, report.executions().get(0).getFindingCount());
        assertTrue(
                report.findings().stream()
                        .anyMatch(f -> "PMDA-DD0085".equals(f.getRuleId())
                                && f.getMessage().contains("define2-1.xsl")),
                "DD0085 finding present in the engine report");
    }


    @Test
    void engineRunIsCleanWhenTheStylesheetExists() throws Exception
    {
        Path define = tempDir.resolve("define.xml");
        Files.writeString(define, PROLOG + ODM);
        Files.createFile(tempDir.resolve("define2-1.xsl"));
        DefineConformanceReport report = new DefineConformanceEngine(List.of(rule()))
                .validate(DefineConformanceInput.builder(define).build());
        assertEquals(List.of(), report.findings().stream()
                .filter(f -> "PMDA-DD0085".equals(f.getRuleId())).toList());
        assertEquals(ExecutionStatus.EXECUTED, report.executions().get(0).getStatus());
    }

}
