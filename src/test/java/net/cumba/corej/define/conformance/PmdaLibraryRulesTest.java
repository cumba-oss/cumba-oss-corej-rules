package net.cumba.corej.define.conformance;

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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.define.conformance.ct.CodelistClass;
import net.cumba.corej.define.conformance.ct.CtCodelist;
import net.cumba.corej.define.conformance.ct.CtProvider;
import net.cumba.corej.define.conformance.engine.DefineConformanceEngine;
import net.cumba.corej.define.conformance.engine.DefineConformanceInput;
import net.cumba.corej.define.conformance.engine.DefineConformanceReport;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.eval.RuleEvaluator;
import net.cumba.corej.define.conformance.eval.RuleResult;
import net.cumba.corej.define.conformance.library.LibraryProvider;
import net.cumba.corej.define.conformance.library.StubLibraryProvider;
import net.cumba.corej.define.conformance.report.ExecutionStatus;
import net.cumba.corej.define.conformance.report.Severity;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.RuleRepository;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.jspecify.annotations.Nullable;
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
        return context(aXml, aVersion, aProvider, null);
    }


    private static DocumentContext context(String aXml, String aVersion, LibraryProvider aProvider,
            @Nullable CtProvider aCtProvider)
    {
        try (InputStream in = new ByteArrayInputStream(aXml.getBytes(StandardCharsets.UTF_8)))
        {
            return new DocumentContext(ElementNodeBuilder.build(DefineDomIo.parse(in)), aVersion,
                    aCtProvider, null, List.of(), aProvider);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }


    /**
     * A 2.1 document with one RS dataset governed by SDTMIG {@code aIgVersion}, whose
     * {@code aVariable} is bound to a codelist aliased {@code aCCode}.
     */
    private static String rs(String aIgVersion, String aVariable, String aCCode)
    {
        return """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1"
                     FileOID="www.example.org/define/rs" FileType="Snapshot"
                     ODMVersion="1.3.2" CreationDateTime="2026-10-15T10:15:30">
                  <Study OID="ST.RS">
                    <GlobalVariables>
                      <StudyName>RS</StudyName>
                      <StudyDescription>RS clinical classification fixture</StudyDescription>
                      <ProtocolName>RS</ProtocolName>
                    </GlobalVariables>
                    <MetaDataVersion OID="MDV.001" Name="MDV" def:DefineVersion="2.1.0">
                      <def:Standards>
                        <def:Standard OID="STD.SDTM" Name="SDTMIG" Type="IG" Version="%s"/>
                      </def:Standards>
                      <ItemGroupDef OID="IG.RS" Name="RS" Domain="RS" Repeating="Yes"
                                    Purpose="Tabulation" def:StandardOID="STD.SDTM"
                                    def:Structure="One record per response assessment per subject">
                        <Description>
                          <TranslatedText xml:lang="en">Disease Response and Clin Classification</TranslatedText>
                        </Description>
                        <ItemRef ItemOID="IT.RS.VAR" Mandatory="Yes"/>
                      </ItemGroupDef>
                      <ItemDef OID="IT.RS.VAR" Name="%s" DataType="text" Length="8">
                        <Description>
                          <TranslatedText xml:lang="en">Response Assessment</TranslatedText>
                        </Description>
                        <CodeListRef CodeListOID="CL.HAMD"/>
                      </ItemDef>
                      <CodeList OID="CL.HAMD" Name="HAMD 17 Test Code" DataType="text">
                        <EnumeratedItem CodedValue="HAMD101"/>
                        <Alias Context="nci:ExtCodeID" Name="%s"/>
                      </CodeList>
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """
                .formatted(aIgVersion, aVariable, aCCode);
    }

    /**
     * A CT provider knowing HAMD-17's Test Code and Test Name lists and HAM-A's Standardized
     * Character Response and Original Response lists as Clinical Classification.
     */
    private static final class HamdCtProvider implements CtProvider
    {

        @Override
        public Optional<CtCodelist> codelistByCCode(String aCCode)
        {
            return Optional.empty();
        }


        @Override
        public Optional<CodelistClass> codelistClass(String aCCode)
        {
            return switch (aCCode)
            {
            case "C100138" -> Optional
                    .of(new CodelistClass("Clinical Classification", Optional.of("Test Code")));
            case "C100137" -> Optional
                    .of(new CodelistClass("Clinical Classification", Optional.of("Test Name")));
            case "C182506" -> Optional.of(new CodelistClass("Clinical Classification",
                    Optional.of("Character Response")));
            case "C182468" -> Optional.of(
                    new CodelistClass("Clinical Classification", Optional.of("Original Response")));
            default -> Optional.empty();
            };
        }
    }

    /**
     * PLAN-stopped-rule-engine-fixes item 2 (SR2 / SR3 / SR7 / T8): the SHIPPED PMDA-DD0118 with
     * its {@code exempt:} block. RS since SDTMIG 3.3 holds clinical classifications, each with its
     * own instrument codelist (SDTMIG 3.3 RSTESTCD: "For Clinical Classifications (when RSCAT is a
     * term in codelist CCCAT), QRS Naming Rules apply. These instruments have individual dedicated
     * terminology codelists"), while the Library assigns RSTESTCD the oncology list C96782 only.
     * With a CT bound that classes HAMD-17's list, RS 3.3 is silent; RS 3.2 (Disease Response
     * only), the wrong role (RSTESTCD on the Test NAME list) and a run without CT still fire.
     */
    @Test
    void dd0118ExemptsAClinicalClassificationCodelistOnRsSince33WhenCtIsBound()
    {
        HamdCtProvider ct = new HamdCtProvider();
        // precondition: the stub library assigns RS its oncology lists for 3.2 and 3.3, so a
        // silence below is the exemption's, never an empty library answer
        assertEquals(List.of("C96782"), new StubLibraryProvider().variableCodelistCCodes("SDTMIG",
                "3.3", "RS", "RSTESTCD"));
        assertEquals(List.of("C96782"), new StubLibraryProvider().variableCodelistCCodes("SDTMIG",
                "3.2", "RS", "RSTESTCD"));
        Map<String, RuleResult> exempt = evaluate(rs("3.3", "RSTESTCD", "C100138"), "2.1",
                new StubLibraryProvider(), ct);
        assertEquals(ExecutionStatus.EXECUTED, exempt.get("PMDA-DD0118").status());
        assertEquals(List.of(), exempt.get("PMDA-DD0118").findings(),
                "RS 3.3 + a Clinical Classification Test Code list on RSTESTCD is conformant");
        assertEquals(List.of(),
                evaluate(rs("3.3", "RSTEST", "C100137"), "2.1", new StubLibraryProvider(), ct)
                        .get("PMDA-DD0118").findings(),
                "RSTEST takes the instrument's Test Name list");

        assertEquals(
                "Codelist NCI Code does not match the Standard Codelist NCI Code "
                        + "[RSTESTCD: C100138 vs C96782]",
                evaluate(rs("3.2", "RSTESTCD", "C100138"), "2.1", new StubLibraryProvider(), ct)
                        .get("PMDA-DD0118").findings().get(0).getMessage(),
                "SDTMIG 3.2's RS is Disease Response only");
        assertEquals(1,
                evaluate(rs("3.3", "RSTESTCD", "C100137"), "2.1", new StubLibraryProvider(), ct)
                        .get("PMDA-DD0118").findings().size(),
                "the Test NAME list on RSTESTCD is a mismatch (T8: the role is matched too)");
        // rule round 2026-10-15 (owner): RSSTRESC takes the instrument's Standardized Character
        // Response list (SDTMIG 3.3 RSSTRESC: "For Clinical Classifications, this may be a score";
        // the Library assigns the oncology list C96785 only)
        assertEquals(List.of("C96785"), new StubLibraryProvider().variableCodelistCCodes("SDTMIG",
                "3.3", "RS", "RSSTRESC"));
        assertEquals(List.of(),
                evaluate(rs("3.3", "RSSTRESC", "C182506"), "2.1", new StubLibraryProvider(), ct)
                        .get("PMDA-DD0118").findings(),
                "RSSTRESC takes the instrument's Standardized Character Response list");
        assertEquals(1,
                evaluate(rs("3.3", "RSSTRESC", "C182468"), "2.1", new StubLibraryProvider(), ct)
                        .get("PMDA-DD0118").findings().size(),
                "the Original Response list on RSSTRESC is a mismatch (the role is matched too)");
        assertEquals(1,
                evaluate(rs("3.2", "RSSTRESC", "C182506"), "2.1", new StubLibraryProvider(), ct)
                        .get("PMDA-DD0118").findings().size(),
                "SDTMIG 3.2's RS is Disease Response only");
        // SR7 (a): no CT bound => the class is unknown and the rule fires as before (T4)
        assertEquals(1,
                evaluate(rs("3.3", "RSTESTCD", "C100138"), "2.1", new StubLibraryProvider())
                        .get("PMDA-DD0118").findings().size(),
                "without a bound CT the exemption is inert");
    }


    private static Map<String, RuleResult> evaluate(String aXml, String aVersion,
            LibraryProvider aProvider, CtProvider aCtProvider)
    {
        List<ConformanceRule> rules = RuleRepository.loadDirectory(RULES_DIR).stream()
                .filter(rule -> LIB_IDS.contains(rule.ruleId())).toList();
        DocumentContext context = context(aXml, aVersion, aProvider, aCtProvider);
        RuleEvaluator evaluator = new RuleEvaluator();
        return rules.stream().collect(Collectors.toMap(ConformanceRule::ruleId,
                rule -> evaluator.evaluate(rule, context)));
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


    /**
     * Rule round 2026-10-12 (owner, DCE-1: "a value-level codelist satisfies"): SEX carries no
     * CodeListRef on its own ItemDef but a {@code def:ValueListRef} whose value-level ItemDef
     * carries one — the CT is attached at value level, as CDISC's MSG v2 example does for RACE or
     * LBORRESU, and PMDA-DD0124 must not fire. The near-miss: the same ValueListRef with a
     * value-level ItemDef WITHOUT a CodeListRef still fires — the guard needs a value-level
     * codelist, not merely a value list.
     */
    @Test
    void aValueLevelCodelistSatisfiesTheCodelistRequirement()
    {
        Map<String, RuleResult> withCodelist = evaluate(
                valueLevelSEX("<CodeListRef CodeListOID=\"CL.VL\"/>"), "2.1",
                new StubLibraryProvider());
        assertEquals(ExecutionStatus.EXECUTED, withCodelist.get("PMDA-DD0124").status());
        assertEquals(List.of(), withCodelist.get("PMDA-DD0124").findings(),
                "a value-level CodeListRef satisfies PMDA-DD0124");

        Map<String, RuleResult> withoutCodelist = evaluate(valueLevelSEX(""), "2.1",
                new StubLibraryProvider());
        assertEquals(1, withoutCodelist.get("PMDA-DD0124").findings().size(),
                "a value list without a value-level codelist still fires");
        assertTrue(
                withoutCodelist.get("PMDA-DD0124").findings().get(0).getMessage().contains("[SEX]"),
                "PMDA-DD0124 names SEX");
    }


    /**
     * The violating 2.1 document with SEX's ItemDef given a def:ValueListRef to one value-level
     * ItemDef whose body is {@code aValueLevelBody} (a CodeListRef, or nothing).
     */
    private static String valueLevelSEX(String aValueLevelBody)
    {
        String header = "<ItemDef OID=\"IT.SEX\" Name=\"SEX\" DataType=\"text\" Length=\"1\">";
        return VIOLATIONS_21.replace(header, header + "<def:ValueListRef ValueListOID=\"VL.SEX\"/>")
                .replace("</MetaDataVersion>", "<def:ValueListDef OID=\"VL.SEX\">"
                        + "<ItemRef ItemOID=\"IT.SEX.VL\" Mandatory=\"No\"/></def:ValueListDef>"
                        + "<ItemDef OID=\"IT.SEX.VL\" Name=\"SEX\" DataType=\"text\" Length=\"16\">"
                        + aValueLevelBody + "</ItemDef>"
                        + "<CodeList OID=\"CL.VL\" Name=\"Value level\" DataType=\"text\">"
                        + "<EnumeratedItem CodedValue=\"X\"/></CodeList></MetaDataVersion>");
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
