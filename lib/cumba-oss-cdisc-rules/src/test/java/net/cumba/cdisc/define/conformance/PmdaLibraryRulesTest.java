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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.cdisc.define.conformance.engine.DefineConformanceEngine;
import net.cumba.cdisc.define.conformance.engine.DefineConformanceInput;
import net.cumba.cdisc.define.conformance.engine.DefineConformanceReport;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.eval.RuleEvaluator;
import net.cumba.cdisc.define.conformance.eval.RuleResult;
import net.cumba.cdisc.define.conformance.library.LibraryProvider;
import net.cumba.cdisc.define.conformance.library.StubLibraryProvider;
import net.cumba.cdisc.define.conformance.report.ExecutionStatus;
import net.cumba.cdisc.define.conformance.report.Severity;
import net.cumba.cdisc.define.conformance.rule.ConformanceRule;
import net.cumba.cdisc.define.conformance.rule.RuleRepository;
import net.cumba.cdisc.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The five library-gated PMDA rules (plan define-library-provider): DD0136 (dataset label), DD0137
 * (variable label), DD0124 (CT requirement → CodeListRef), DD0118 (codelist c-code agreement),
 * DD0116 (FATESTCD/FATEST qualifier decode). All declare {@code Requires: library} and SKIP with
 * {@code SKIPPED_MISSING_LIBRARY} when no {@link LibraryProvider} is supplied; library-unknown
 * material is out of reach (no finding) by design.
 */
class PmdaLibraryRulesTest
{

    private static final Set<String> LIB_IDS = Set.of("PMDA-DD0116", "PMDA-DD0118", "PMDA-DD0124",
            "PMDA-DD0136", "PMDA-DD0137");

    private static final Path RULES_DIR = TestRules.PMDA;

    @TempDir
    Path tempDir;

    /**
     * 2.1 document violating each rule exactly once: DM's label "Demographic Data" (DD0136), AGE's
     * label "Age in Years" (DD0137), SEX with a library CT requirement but no CodeListRef (DD0124),
     * AESEV's codelist alias C12345 vs the library's C66769 (DD0118), and the FATESTCD codelist
     * decoding OCCUR as "Occurred" instead of "Occurrence" (DD0116). AE's dataset label, AESEV's
     * variable label, and the unknown FATESTCD item XYZ are conforming/unknown decoys proving each
     * rule fires only where intended; SUPPAE (Domain=AE, own label) proves DD0136 keys the lookup
     * off the dataset's own Name, never the parent Domain.
     */
    private static final String VIOLATIONS_21 = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.1"
                 FileOID="www.example.org/define/library-violations" FileType="Snapshot"
                 ODMVersion="1.3.2" CreationDateTime="2026-07-23T10:15:30">
              <Study OID="ST.001">
                <GlobalVariables>
                  <StudyName>LIB</StudyName>
                  <StudyDescription>Library rules fixture</StudyDescription>
                  <ProtocolName>LIB</ProtocolName>
                </GlobalVariables>
                <MetaDataVersion OID="MDV.001" Name="MDV" def:DefineVersion="2.1.0">
                  <def:Standards>
                    <def:Standard OID="STD.SDTM" Name="SDTMIG" Type="IG" Version="3.4"/>
                  </def:Standards>
                  <ItemGroupDef OID="IG.DM" Name="DM" Domain="DM" Repeating="No"
                                Purpose="Tabulation" def:StandardOID="STD.SDTM"
                                def:Structure="One record per subject" def:ArchiveLocationID="LF.DM">
                    <Description>
                      <TranslatedText xml:lang="en">Demographic Data</TranslatedText>
                    </Description>
                    <ItemRef ItemOID="IT.AGE" Mandatory="No"/>
                    <ItemRef ItemOID="IT.SEX" Mandatory="No"/>
                  </ItemGroupDef>
                  <ItemGroupDef OID="IG.AE" Name="AE" Domain="AE" Repeating="Yes"
                                Purpose="Tabulation" def:StandardOID="STD.SDTM"
                                def:Structure="One record per event" def:ArchiveLocationID="LF.AE">
                    <Description>
                      <TranslatedText xml:lang="en">Adverse Events</TranslatedText>
                    </Description>
                    <ItemRef ItemOID="IT.AESEV" Mandatory="No"/>
                  </ItemGroupDef>
                  <ItemGroupDef OID="IG.SUPPAE" Name="SUPPAE" Domain="AE" Repeating="Yes"
                                Purpose="Tabulation" def:StandardOID="STD.SDTM"
                                def:Structure="One record per qualifier">
                    <Description>
                      <TranslatedText xml:lang="en">Supplemental Qualifiers for AE</TranslatedText>
                    </Description>
                  </ItemGroupDef>
                  <ItemDef OID="IT.AGE" Name="AGE" DataType="integer">
                    <Description>
                      <TranslatedText xml:lang="en">Age in Years</TranslatedText>
                    </Description>
                  </ItemDef>
                  <ItemDef OID="IT.SEX" Name="SEX" DataType="text" Length="1">
                    <Description>
                      <TranslatedText xml:lang="en">Sex</TranslatedText>
                    </Description>
                  </ItemDef>
                  <ItemDef OID="IT.AESEV" Name="AESEV" DataType="text" Length="8">
                    <Description>
                      <TranslatedText xml:lang="en">Severity/Intensity</TranslatedText>
                    </Description>
                    <CodeListRef CodeListOID="CL.AESEV"/>
                  </ItemDef>
                  <ItemDef OID="IT.FATESTCD" Name="FATESTCD" DataType="text" Length="8">
                    <Description>
                      <TranslatedText xml:lang="en">Findings About Test Short Name</TranslatedText>
                    </Description>
                    <CodeListRef CodeListOID="CL.FATESTCD"/>
                  </ItemDef>
                  <CodeList OID="CL.AESEV" Name="Severity/Intensity Scale for Adverse Events"
                            DataType="text">
                    <CodeListItem CodedValue="MILD">
                      <Decode>
                        <TranslatedText xml:lang="en">Mild</TranslatedText>
                      </Decode>
                    </CodeListItem>
                    <Alias Context="nci:ExtCodeID" Name="C12345"/>
                  </CodeList>
                  <CodeList OID="CL.FATESTCD" Name="FA Test Code" DataType="text">
                    <CodeListItem CodedValue="OCCUR">
                      <Decode>
                        <TranslatedText xml:lang="en">Occurred</TranslatedText>
                      </Decode>
                    </CodeListItem>
                    <CodeListItem CodedValue="XYZ">
                      <Decode>
                        <TranslatedText xml:lang="en">Anything</TranslatedText>
                      </Decode>
                    </CodeListItem>
                  </CodeList>
                </MetaDataVersion>
              </Study>
            </ODM>
            """;

    /** The violating document with every defect corrected — all five rules come back clean. */
    private static final String CLEAN_21 = VIOLATIONS_21.replace("Demographic Data", "Demographics") //
            .replace("Age in Years", "Age") //
            .replace("<ItemDef OID=\"IT.SEX\" Name=\"SEX\" DataType=\"text\" Length=\"1\">",
                    "<ItemDef OID=\"IT.SEX\" Name=\"SEX\" DataType=\"text\" Length=\"1\">"
                            + "<CodeListRef CodeListOID=\"CL.SEX\"/>")
            .replace("<Alias Context=\"nci:ExtCodeID\" Name=\"C12345\"/>",
                    "<Alias Context=\"nci:ExtCodeID\" Name=\"C66769\"/>")
            .replace("</MetaDataVersion>",
                    "<CodeList OID=\"CL.SEX\" Name=\"Sex\" DataType=\"text\">"
                            + "<CodeListItem CodedValue=\"F\"><Decode>"
                            + "<TranslatedText xml:lang=\"en\">Female</TranslatedText>"
                            + "</Decode></CodeListItem>"
                            + "<Alias Context=\"nci:ExtCodeID\" Name=\"C66731\"/>"
                            + "</CodeList></MetaDataVersion>")
            .replace("Occurred", "Occurrence");

    /**
     * A 2.0-shaped document: no def:Standards / def:StandardOID — the standard comes from the
     * MetaDataVersion's def:StandardName/def:StandardVersion, in the 2.0 CT spelling
     * {@code SDTM-IG} (PMDA-DD0021's value set; providers are hyphen-insensitive per the SPI
     * contract). DM's label is wrong (DD0136's 2.0 leg); SEX's codelist alias C99999 mismatches the
     * library's C66731 (DD0118's 2.0 leg); FATESTCD decodes OCCUR wrongly (DD0116's 2.0 leg).
     */
    private static final String VIOLATIONS_20 = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.0"
                 FileOID="www.example.org/define/library-violations-20" FileType="Snapshot"
                 ODMVersion="1.3.2" CreationDateTime="2026-07-23T10:15:30">
              <Study OID="ST.001">
                <GlobalVariables>
                  <StudyName>LIB20</StudyName>
                  <StudyDescription>Library rules 2.0 fixture</StudyDescription>
                  <ProtocolName>LIB20</ProtocolName>
                </GlobalVariables>
                <MetaDataVersion OID="MDV.001" Name="MDV" def:DefineVersion="2.0.0"
                                 def:StandardName="SDTM-IG" def:StandardVersion="3.4">
                  <ItemGroupDef OID="IG.DM" Name="DM" Domain="DM" Repeating="No"
                                Purpose="Tabulation"
                                def:Structure="One record per subject">
                    <Description>
                      <TranslatedText xml:lang="en">Demographic Data</TranslatedText>
                    </Description>
                    <ItemRef ItemOID="IT.AGE" Mandatory="No"/>
                    <ItemRef ItemOID="IT.SEX" Mandatory="No"/>
                  </ItemGroupDef>
                  <ItemDef OID="IT.AGE" Name="AGE" DataType="integer">
                    <Description>
                      <TranslatedText xml:lang="en">Age</TranslatedText>
                    </Description>
                  </ItemDef>
                  <ItemDef OID="IT.SEX" Name="SEX" DataType="text" Length="1">
                    <Description>
                      <TranslatedText xml:lang="en">Sex</TranslatedText>
                    </Description>
                    <CodeListRef CodeListOID="CL.SEX"/>
                  </ItemDef>
                  <ItemDef OID="IT.FATESTCD" Name="FATESTCD" DataType="text" Length="8">
                    <CodeListRef CodeListOID="CL.FATESTCD"/>
                  </ItemDef>
                  <CodeList OID="CL.SEX" Name="Sex" DataType="text">
                    <CodeListItem CodedValue="F">
                      <Decode>
                        <TranslatedText xml:lang="en">Female</TranslatedText>
                      </Decode>
                    </CodeListItem>
                    <Alias Context="nci:ExtCodeID" Name="C99999"/>
                  </CodeList>
                  <CodeList OID="CL.FATESTCD" Name="FA Test Code" DataType="text">
                    <CodeListItem CodedValue="OCCUR">
                      <Decode>
                        <TranslatedText xml:lang="en">Occurred</TranslatedText>
                      </Decode>
                    </CodeListItem>
                  </CodeList>
                </MetaDataVersion>
              </Study>
            </ODM>
            """;

    private static Map<String, RuleResult> evaluate(String aXml, String aVersion,
            LibraryProvider aProvider)
    {
        List<ConformanceRule> rules = RuleRepository.loadDirectory(RULES_DIR).stream()
                .filter(rule -> LIB_IDS.contains(rule.ruleId())).toList();
        assertEquals(LIB_IDS.size(), rules.size(), "library rule files present");
        DocumentContext context = context(aXml, aVersion, aProvider);
        RuleEvaluator evaluator = new RuleEvaluator();
        return rules.stream().collect(Collectors.toMap(ConformanceRule::ruleId,
                rule -> evaluator.evaluate(rule, context)));
    }


    private static DocumentContext context(String aXml, String aVersion, LibraryProvider aProvider)
    {
        try (InputStream in = new ByteArrayInputStream(aXml.getBytes(StandardCharsets.UTF_8)))
        {
            return new DocumentContext(ElementNodeBuilder.build(DefineDomIo.parse(in)), aVersion,
                    null, null, List.of(), aProvider);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }


    @Test
    void violationsFixtureFiresEachRuleExactlyOnce()
    {
        Map<String, RuleResult> results = evaluate(VIOLATIONS_21, "2.1", new StubLibraryProvider());
        for (String ruleId : LIB_IDS)
        {
            RuleResult result = results.get(ruleId);
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            assertEquals(1, result.findings().size(),
                    ruleId + " finding count: " + result.findings());
        }
        assertEquals("Invalid dataset label value [Demographic Data vs Demographics]",
                results.get("PMDA-DD0136").findings().get(0).getMessage());
        assertEquals("Invalid variable label value [AGE: Age in Years vs Age]",
                results.get("PMDA-DD0137").findings().get(0).getMessage());
        assertEquals("Expected Codelist is missing [SEX]",
                results.get("PMDA-DD0124").findings().get(0).getMessage());
        assertEquals(
                "Codelist NCI Code does not match the Standard Codelist NCI Code "
                        + "[AESEV: C12345 vs C66769]",
                results.get("PMDA-DD0118").findings().get(0).getMessage());
        assertEquals(
                "FATESTCD/FATEST mismatch in Codelist "
                        + "[FA Test Code (OCCUR: Occurred vs Occurrence)]",
                results.get("PMDA-DD0116").findings().get(0).getMessage());
        assertTrue(results.get("PMDA-DD0116").findings().get(0).getXpath().contains("CodeListItem"),
                "DD0116 anchors on the offending CodeListItem");
    }


    @Test
    void cleanFixtureProducesNoFindings()
    {
        Map<String, RuleResult> results = evaluate(CLEAN_21, "2.1", new StubLibraryProvider());
        for (String ruleId : LIB_IDS)
        {
            assertEquals(ExecutionStatus.EXECUTED, results.get(ruleId).status(),
                    ruleId + " status");
            assertEquals(List.of(), results.get(ruleId).findings(), ruleId + " findings");
        }
    }


    @Test
    void allFiveSkipWithoutALibraryProvider()
    {
        Map<String, RuleResult> results = evaluate(VIOLATIONS_21, "2.1", null);
        for (String ruleId : LIB_IDS)
        {
            assertEquals(ExecutionStatus.SKIPPED_MISSING_LIBRARY, results.get(ruleId).status(),
                    ruleId + " status");
            assertEquals(List.of(), results.get(ruleId).findings());
        }
    }


    @Test
    void twoZeroLegResolvesTheStandardFromMetaDataVersionAttributes()
    {
        Map<String, RuleResult> results = evaluate(VIOLATIONS_20, "2.0", new StubLibraryProvider());
        assertEquals(1, results.get("PMDA-DD0136").findings().size(), "DD0136 2.0 leg fires");
        assertEquals(1, results.get("PMDA-DD0116").findings().size(), "DD0116 2.0 leg fires");
        assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, results.get("PMDA-DD0137").status(),
                "DD0137 is 2.1-only");
        assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, results.get("PMDA-DD0124").status(),
                "DD0124 is 2.1-only");
        assertEquals(ExecutionStatus.EXECUTED, results.get("PMDA-DD0118").status());
        assertEquals(1, results.get("PMDA-DD0118").findings().size(), "DD0118 2.0 leg fires");
        assertEquals(
                "Codelist NCI Code does not match the Standard Codelist NCI Code "
                        + "[SEX: C99999 vs C66731]",
                results.get("PMDA-DD0118").findings().get(0).getMessage());
    }


    @Test
    void libraryUnknownMaterialIsOutOfReach()
    {
        // An unknown standard version makes every lookup empty — nothing may fire.
        String unknownVersion = VIOLATIONS_21.replace("Version=\"3.4\"", "Version=\"9.9\"");
        Map<String, RuleResult> results = evaluate(unknownVersion, "2.1",
                new StubLibraryProvider());
        for (String ruleId : LIB_IDS)
        {
            assertEquals(ExecutionStatus.EXECUTED, results.get(ruleId).status());
            assertEquals(List.of(), results.get(ruleId).findings(),
                    ruleId + " must not fire on library-unknown material");
        }
    }


    @Test
    void sheetSeveritiesAndVersionsArePinned()
    {
        Map<String, RuleResult> results = evaluate(CLEAN_21, "2.1", new StubLibraryProvider());
        Map<String, Severity> expected = Map.of(//
                "PMDA-DD0116", Severity.WARNING, //
                "PMDA-DD0118", Severity.ERROR, //
                "PMDA-DD0124", Severity.ERROR, //
                "PMDA-DD0136", Severity.ERROR, //
                "PMDA-DD0137", Severity.ERROR);
        Map<String, List<String>> versions = Map.of(//
                "PMDA-DD0116", List.of("2.0", "2.1"), //
                "PMDA-DD0118", List.of("2.0", "2.1"), //
                "PMDA-DD0124", List.of("2.1"), //
                "PMDA-DD0136", List.of("2.0", "2.1"), //
                "PMDA-DD0137", List.of("2.1"));
        for (String ruleId : LIB_IDS)
        {
            ConformanceRule rule = results.get(ruleId).rule();
            assertEquals(expected.get(ruleId), rule.effectiveSeverity(), ruleId + " severity");
            assertEquals(versions.get(ruleId), rule.applicableVersions(), ruleId + " versions");
        }
    }


    @Test
    void engineWiresTheLibraryProviderThroughTheInput() throws Exception
    {
        Path define = tempDir.resolve("define.xml");
        Files.writeString(define, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + VIOLATIONS_21);
        List<ConformanceRule> rules = RuleRepository.loadDirectory(RULES_DIR).stream()
                .filter(rule -> LIB_IDS.contains(rule.ruleId())).toList();
        DefineConformanceEngine engine = new DefineConformanceEngine(rules);

        DefineConformanceReport with = engine.validate(DefineConformanceInput.builder(define)
                .libraryProvider(new StubLibraryProvider()).build());
        assertEquals(LIB_IDS.size(), with.executions().size());
        assertTrue(
                with.executions().stream().allMatch(e -> e.getStatus() == ExecutionStatus.EXECUTED),
                "all five library rules executed with the provider wired through the input");
        assertEquals(LIB_IDS.size(),
                with.findings().stream().filter(f -> LIB_IDS.contains(f.getRuleId())).count(),
                "each rule's violation surfaces in the engine report");

        DefineConformanceReport without = engine
                .validate(DefineConformanceInput.builder(define).build());
        assertTrue(
                without.executions().stream()
                        .allMatch(e -> e.getStatus() == ExecutionStatus.SKIPPED_MISSING_LIBRARY),
                "no provider on the input - all five SKIP");
    }


    @Test
    void loadRejectsLibraryKindsWithoutTheLibraryRequirement()
    {
        Map<String, RuleResult> results = evaluate(CLEAN_21, "2.1", new StubLibraryProvider());
        ConformanceRule rule = results.get("PMDA-DD0136").rule();
        ConformanceRule unguarded = new ConformanceRule(rule.ruleId(), rule.sheetRuleIdentifier(),
                rule.ruleSet(), rule.element(), rule.attribute(), rule.applicableVersions(),
                rule.sourceType(), rule.severity(), null, rule.plainTextRule(), rule.message(),
                rule.check());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                unguarded::validate);
        assertTrue(failure.getMessage().contains("Requires: library"), failure.getMessage());
    }

}
