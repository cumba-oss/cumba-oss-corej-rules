package net.cumba.cdisc.define.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.eval.RuleEvaluator;
import net.cumba.cdisc.define.conformance.eval.RuleResult;
import net.cumba.cdisc.define.conformance.library.LibraryProvider;
import net.cumba.cdisc.define.conformance.library.StubLibraryProvider;
import net.cumba.cdisc.define.conformance.report.ExecutionStatus;
import net.cumba.cdisc.define.conformance.rule.ConformanceRule;
import net.cumba.cdisc.define.conformance.rule.RuleRepository;
import net.cumba.cdisc.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/**
 * The eight CDISC library-gated twins (plan define-library-provider follow-up), authored against
 * the same {@code LibraryProvider} SPI as the PMDA five: DEFINE-XML-0067 (Core=Req ⇒
 * Mandatory=Yes), 0097/0098/0099 (nci alias on CT-required codelists / their items), 0149
 * (CodeListRef twin of PMDA-DD0124), 0263/-B (published standard version), 0265/0266 (label twins
 * of PMDA-DD0136/DD0137). All SKIP with {@code SKIPPED_MISSING_LIBRARY} without a provider.
 */
class CdiscLibraryRulesTest
{

    private static final Set<String> TWIN_IDS = Set.of("DEFINE-XML-0067", "DEFINE-XML-0097",
            "DEFINE-XML-0098", "DEFINE-XML-0099", "DEFINE-XML-0149", "DEFINE-XML-0263",
            "DEFINE-XML-0263-B", "DEFINE-XML-0265", "DEFINE-XML-0266");

    private static final Path RULES_DIR = TestRules.CDISC;

    /**
     * 2.1 document violating each 2.1-applicable rule exactly once: SEX's ItemRef Mandatory="No"
     * although Core=Req (0067); CL.AESEV without a codelist nci alias (0097); CL.SEX's
     * EnumeratedItem F without an alias (0098) while M is a declared def:ExtendedValue extension
     * (decoy); CL.AESEV's CodeListItem MILD without an alias (0099) while MODERATE carries one
     * (decoy); AEACN requires CT but has no CodeListRef (0149); STD.BAD declares SDTMIG 1.0, not a
     * published version (0263) while STD.ADAM's ADaMIG is library-unknown (decoy); DM's label
     * "Demographic Data" (0265); AGE's label "Age in Years" (0266). AGE's Mandatory="Yes"/Core=Exp
     * and AESEV's absent Core are 0067 decoys; the VL.DECOY ValueListDef/ItemRef re-references the
     * violating SEX ItemDef, proving the ItemGroupDef/ItemRef scope excludes value-list ItemRefs
     * (the exactly-once assertions would double otherwise).
     */
    private static final String VIOLATIONS_21 = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.1"
                 FileOID="www.example.org/define/cdisc-twin-violations" FileType="Snapshot"
                 ODMVersion="1.3.2" CreationDateTime="2026-07-23T10:15:30">
              <Study OID="ST.001">
                <GlobalVariables>
                  <StudyName>TWIN</StudyName>
                  <StudyDescription>CDISC twin rules fixture</StudyDescription>
                  <ProtocolName>TWIN</ProtocolName>
                </GlobalVariables>
                <MetaDataVersion OID="MDV.001" Name="MDV" def:DefineVersion="2.1.0">
                  <def:Standards>
                    <def:Standard OID="STD.SDTM" Name="SDTMIG" Type="IG" Version="3.4"/>
                    <def:Standard OID="STD.BAD" Name="SDTMIG" Type="IG" Version="1.0"/>
                    <def:Standard OID="STD.ADAM" Name="ADaMIG" Type="IG" Version="9.9"/>
                  </def:Standards>
                  <ItemGroupDef OID="IG.DM" Name="DM" Domain="DM" Repeating="No"
                                Purpose="Tabulation" def:StandardOID="STD.SDTM"
                                def:Structure="One record per subject">
                    <Description>
                      <TranslatedText xml:lang="en">Demographic Data</TranslatedText>
                    </Description>
                    <ItemRef ItemOID="IT.AGE" Mandatory="Yes"/>
                    <ItemRef ItemOID="IT.SEX" Mandatory="No"/>
                  </ItemGroupDef>
                  <ItemGroupDef OID="IG.AE" Name="AE" Domain="AE" Repeating="Yes"
                                Purpose="Tabulation" def:StandardOID="STD.SDTM"
                                def:Structure="One record per event">
                    <Description>
                      <TranslatedText xml:lang="en">Adverse Events</TranslatedText>
                    </Description>
                    <ItemRef ItemOID="IT.AESEV" Mandatory="No"/>
                    <ItemRef ItemOID="IT.AEACN" Mandatory="No"/>
                  </ItemGroupDef>
                  <def:ValueListDef OID="VL.DECOY">
                    <ItemRef ItemOID="IT.SEX" Mandatory="No"/>
                  </def:ValueListDef>
                  <ItemDef OID="IT.AGE" Name="AGE" DataType="integer">
                    <Description>
                      <TranslatedText xml:lang="en">Age in Years</TranslatedText>
                    </Description>
                  </ItemDef>
                  <ItemDef OID="IT.SEX" Name="SEX" DataType="text" Length="1">
                    <Description>
                      <TranslatedText xml:lang="en">Sex</TranslatedText>
                    </Description>
                    <CodeListRef CodeListOID="CL.SEX"/>
                  </ItemDef>
                  <ItemDef OID="IT.AESEV" Name="AESEV" DataType="text" Length="8">
                    <Description>
                      <TranslatedText xml:lang="en">Severity/Intensity</TranslatedText>
                    </Description>
                    <CodeListRef CodeListOID="CL.AESEV"/>
                  </ItemDef>
                  <ItemDef OID="IT.AEACN" Name="AEACN" DataType="text" Length="16">
                    <Description>
                      <TranslatedText xml:lang="en">Action Taken with Study Treatment</TranslatedText>
                    </Description>
                  </ItemDef>
                  <CodeList OID="CL.SEX" Name="Sex" DataType="text">
                    <EnumeratedItem CodedValue="F"/>
                    <EnumeratedItem CodedValue="M" def:ExtendedValue="Yes"/>
                    <Alias Context="nci:ExtCodeID" Name="C66731"/>
                  </CodeList>
                  <CodeList OID="CL.AESEV" Name="Severity/Intensity Scale for Adverse Events" DataType="text">
                    <CodeListItem CodedValue="MILD">
                      <Decode>
                        <TranslatedText xml:lang="en">Mild</TranslatedText>
                      </Decode>
                    </CodeListItem>
                    <CodeListItem CodedValue="MODERATE">
                      <Decode>
                        <TranslatedText xml:lang="en">Moderate</TranslatedText>
                      </Decode>
                      <Alias Context="nci:ExtCodeID" Name="C41139"/>
                    </CodeListItem>
                  </CodeList>
                </MetaDataVersion>
              </Study>
            </ODM>
            """;

    /** The violating document with every defect corrected — all rules come back clean. */
    private static final String CLEAN_21 = VIOLATIONS_21
            .replace("<ItemRef ItemOID=\"IT.SEX\" Mandatory=\"No\"/>",
                    "<ItemRef ItemOID=\"IT.SEX\" Mandatory=\"Yes\"/>")
            .replace("<def:Standard OID=\"STD.BAD\" Name=\"SDTMIG\" Type=\"IG\" Version=\"1.0\"/>",
                    "<def:Standard OID=\"STD.BAD\" Name=\"SDTMIG\" Type=\"IG\" Version=\"3.3\"/>")
            .replace("Demographic Data", "Demographics") //
            .replace("Age in Years", "Age") //
            .replace("<EnumeratedItem CodedValue=\"F\"/>", "<EnumeratedItem CodedValue=\"F\">"
                    + "<Alias Context=\"nci:ExtCodeID\" Name=\"C16576\"/>" + "</EnumeratedItem>")
            // MILD gains its item alias (0099); MODERATE already carries one. Single-line
            // anchors on purpose: the text block strips indentation, so newline-plus-indent
            // patterns would silently not match.
            .replace("<CodeListItem CodedValue=\"MILD\">",
                    "<CodeListItem CodedValue=\"MILD\">"
                            + "<Alias Context=\"nci:ExtCodeID\" Name=\"C70666\"/>")
            // CL.AESEV gains its codelist alias (0097), as first child.
            .replace(
                    "<CodeList OID=\"CL.AESEV\" Name=\"Severity/Intensity Scale for Adverse "
                            + "Events\" DataType=\"text\">",
                    "<CodeList OID=\"CL.AESEV\" Name=\"Severity/Intensity Scale for Adverse "
                            + "Events\" DataType=\"text\">"
                            + "<Alias Context=\"nci:ExtCodeID\" Name=\"C66769\"/>")
            .replace("<ItemDef OID=\"IT.AEACN\" Name=\"AEACN\" DataType=\"text\" Length=\"16\">",
                    "<ItemDef OID=\"IT.AEACN\" Name=\"AEACN\" DataType=\"text\" Length=\"16\">"
                            + "<CodeListRef CodeListOID=\"CL.SEX\"/>");

    /**
     * A 2.0-shaped document with a library-known standard (SDTM-IG 3.4 via the MetaDataVersion
     * attributes) whose SEX codelist material violates the dual-version CT-alias rules: CL.SEX has
     * no codelist alias (0097's 2.0 leg) and its EnumeratedItem F carries neither an alias nor a
     * def:ExtendedValue (0098's 2.0 leg). No CodeListItems — 0099 executes clean.
     */
    private static final String VIOLATIONS_20_CT = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.0"
                 FileOID="www.example.org/define/cdisc-twin-ct-20" FileType="Snapshot"
                 ODMVersion="1.3.2" CreationDateTime="2026-07-23T10:15:30">
              <Study OID="ST.001">
                <GlobalVariables>
                  <StudyName>TWINCT20</StudyName>
                  <StudyDescription>CDISC twin CT-alias 2.0 fixture</StudyDescription>
                  <ProtocolName>TWINCT20</ProtocolName>
                </GlobalVariables>
                <MetaDataVersion OID="MDV.001" Name="MDV" def:DefineVersion="2.0.0"
                                 def:StandardName="SDTM-IG" def:StandardVersion="3.4">
                  <ItemGroupDef OID="IG.DM" Name="DM" Domain="DM" Repeating="No"
                                Purpose="Tabulation"
                                def:Structure="One record per subject">
                    <ItemRef ItemOID="IT.SEX" Mandatory="Yes"/>
                  </ItemGroupDef>
                  <ItemDef OID="IT.SEX" Name="SEX" DataType="text" Length="1">
                    <CodeListRef CodeListOID="CL.SEX"/>
                  </ItemDef>
                  <CodeList OID="CL.SEX" Name="Sex" DataType="text">
                    <EnumeratedItem CodedValue="F"/>
                  </CodeList>
                </MetaDataVersion>
              </Study>
            </ODM>
            """;

    /** A 2.0-shaped document whose MetaDataVersion declares an unpublished SDTM-IG version. */
    private static final String VIOLATIONS_20 = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.0"
                 FileOID="www.example.org/define/cdisc-twin-violations-20" FileType="Snapshot"
                 ODMVersion="1.3.2" CreationDateTime="2026-07-23T10:15:30">
              <Study OID="ST.001">
                <GlobalVariables>
                  <StudyName>TWIN20</StudyName>
                  <StudyDescription>CDISC twin rules 2.0 fixture</StudyDescription>
                  <ProtocolName>TWIN20</ProtocolName>
                </GlobalVariables>
                <MetaDataVersion OID="MDV.001" Name="MDV" def:DefineVersion="2.0.0"
                                 def:StandardName="SDTM-IG" def:StandardVersion="1.0">
                  <ItemGroupDef OID="IG.DM" Name="DM" Domain="DM" Repeating="No"
                                Purpose="Tabulation"
                                def:Structure="One record per subject">
                    <ItemRef ItemOID="IT.AGE" Mandatory="Yes"/>
                  </ItemGroupDef>
                  <ItemDef OID="IT.AGE" Name="AGE" DataType="integer"/>
                </MetaDataVersion>
              </Study>
            </ODM>
            """;

    private static Map<String, RuleResult> evaluate(String aXml, String aVersion,
            LibraryProvider aProvider)
    {
        List<ConformanceRule> rules = RuleRepository.loadDirectory(RULES_DIR).stream()
                .filter(rule -> TWIN_IDS.contains(rule.ruleId())).toList();
        assertEquals(TWIN_IDS.size(), rules.size(), "twin rule files present");
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
    void violationsFixtureFiresEachApplicableRuleExactlyOnce()
    {
        Map<String, RuleResult> results = evaluate(VIOLATIONS_21, "2.1", new StubLibraryProvider());
        for (String ruleId : TWIN_IDS)
        {
            if ("DEFINE-XML-0263-B".equals(ruleId))
            {
                assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, results.get(ruleId).status(),
                        "0263-B is the 2.0 leg");
                continue;
            }
            RuleResult result = results.get(ruleId);
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            assertEquals(1, result.findings().size(),
                    ruleId + " finding count: " + result.findings());
        }
        assertEquals(
                "ItemRef for [SEX] must have Mandatory=\"Yes\" because the standard defines the "
                        + "variable as Core=\"Req\".",
                results.get("DEFINE-XML-0067").findings().get(0).getMessage());
        assertTrue(results.get("DEFINE-XML-0097").findings().get(0).getMessage()
                .contains("Severity/Intensity Scale"), "0097 names the alias-less codelist");
        assertTrue(results.get("DEFINE-XML-0098").findings().get(0).getMessage().contains("[F]"),
                "0098 fires on the unextended EnumeratedItem");
        assertTrue(results.get("DEFINE-XML-0099").findings().get(0).getMessage().contains("[MILD]"),
                "0099 fires on the alias-less CodeListItem");
        assertTrue(
                results.get("DEFINE-XML-0149").findings().get(0).getMessage().contains("[AEACN]"),
                "0149 fires on the CodeListRef-less CT variable");
        assertTrue(results.get("DEFINE-XML-0263").findings().get(0).getMessage()
                .contains("[SDTMIG 1.0]"), "0263 fires on the unpublished version only");
        assertTrue(results.get("DEFINE-XML-0265").findings().get(0).getMessage()
                .contains("Demographic Data vs Demographics"), "0265 label diff");
        assertTrue(results.get("DEFINE-XML-0266").findings().get(0).getMessage()
                .contains("AGE: Age in Years vs Age"), "0266 label diff");
    }


    @Test
    void cleanFixtureProducesNoFindings()
    {
        Map<String, RuleResult> results = evaluate(CLEAN_21, "2.1", new StubLibraryProvider());
        for (String ruleId : TWIN_IDS)
        {
            if ("DEFINE-XML-0263-B".equals(ruleId))
            {
                continue;
            }
            assertEquals(ExecutionStatus.EXECUTED, results.get(ruleId).status(),
                    ruleId + " status");
            assertEquals(List.of(), results.get(ruleId).findings(), ruleId + " findings");
        }
    }


    @Test
    void allTwinsSkipWithoutALibraryProvider()
    {
        Map<String, RuleResult> results = evaluate(VIOLATIONS_21, "2.1", null);
        for (String ruleId : TWIN_IDS)
        {
            ExecutionStatus status = results.get(ruleId).status();
            assertTrue(
                    status == ExecutionStatus.SKIPPED_MISSING_LIBRARY
                            || status == ExecutionStatus.NOT_APPLICABLE_VERSION,
                    ruleId + " status " + status);
            assertEquals(List.of(), results.get(ruleId).findings());
        }
        assertEquals(ExecutionStatus.SKIPPED_MISSING_LIBRARY,
                results.get("DEFINE-XML-0263").status());
    }


    @Test
    void twoZeroLegChecksTheMetaDataVersionStandardAttributes()
    {
        Map<String, RuleResult> results = evaluate(VIOLATIONS_20, "2.0", new StubLibraryProvider());
        RuleResult leg = results.get("DEFINE-XML-0263-B");
        assertEquals(ExecutionStatus.EXECUTED, leg.status());
        assertEquals(1, leg.findings().size(), "0263-B fires on the unpublished SDTM-IG 1.0");
        assertTrue(leg.findings().get(0).getMessage().contains("[SDTM-IG 1.0]"),
                "verbatim 2.0 spelling in the message");
        assertEquals(ExecutionStatus.EXECUTED, results.get("DEFINE-XML-0263").status());
        assertEquals(List.of(), results.get("DEFINE-XML-0263").findings(),
                "no def:Standard elements in a 2.0 document - empty scope, clean");
        for (String twoOneOnly : List.of("DEFINE-XML-0067", "DEFINE-XML-0149", "DEFINE-XML-0265",
                "DEFINE-XML-0266"))
        {
            assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, results.get(twoOneOnly).status(),
                    twoOneOnly + " is 2.1-only");
        }
    }


    @Test
    void twoZeroLegOfTheCtAliasKindsResolvesTheStandardFromMetaDataVersionAttributes()
    {
        Map<String, RuleResult> results = evaluate(VIOLATIONS_20_CT, "2.0",
                new StubLibraryProvider());
        assertEquals(1, results.get("DEFINE-XML-0097").findings().size(),
                "0097 2.0 leg fires on the alias-less CT-required codelist");
        assertEquals(1, results.get("DEFINE-XML-0098").findings().size(),
                "0098 2.0 leg fires on the alias-less EnumeratedItem");
        assertTrue(results.get("DEFINE-XML-0098").findings().get(0).getMessage().contains("[F]"));
        assertEquals(ExecutionStatus.EXECUTED, results.get("DEFINE-XML-0099").status());
        assertEquals(List.of(), results.get("DEFINE-XML-0099").findings(),
                "no CodeListItems in the fixture");
    }

}
