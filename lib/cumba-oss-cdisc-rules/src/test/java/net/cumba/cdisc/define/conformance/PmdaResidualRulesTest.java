package net.cumba.cdisc.define.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.cdisc.define.conformance.ct.CtCodelist;
import net.cumba.cdisc.define.conformance.ct.CtProvider;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.eval.RuleEvaluator;
import net.cumba.cdisc.define.conformance.eval.RuleResult;
import net.cumba.cdisc.define.conformance.report.ExecutionStatus;
import net.cumba.cdisc.define.conformance.report.Severity;
import net.cumba.cdisc.define.conformance.rule.ConformanceRule;
import net.cumba.cdisc.define.conformance.rule.RuleRepository;
import net.cumba.cdisc.define.conformance.tree.ElementNode;
import net.cumba.cdisc.define.conformance.tree.ElementNodeBuilder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * PMDA residual batch (the final 16 sheet rows no prior batch claimed). Eleven rule files are
 * authored: two extended_value_marking legs each for DD0029 (required) and DD0132 (forbidden); the
 * ARM AnalysisReason/AnalysisPurpose CT rows DD0099/DD0100; the Study-Day-origin rule DD0105; the
 * mixed-codelist rule OD0082; the wildcard Standard-reference rule DD0138; and two customs — DD0093
 * (ARM ParameterOID usage) and DD0150 (def:Standards IG/CT pairing). The remaining rows are gaps
 * (see docs/define-rules-gaps.md). Loads only this batch's rules (filtered by id) so concurrent
 * batches adding files never move these assertions.
 */
class PmdaResidualRulesTest
{

    /** Non-CT rules, evaluated on the pmda-res fixtures without a CtProvider. */
    private static final Set<String> NON_CT_IDS = Set.of(//
            "PMDA-DD0105", "PMDA-OD0082", "PMDA-DD0138", "PMDA-DD0093", "PMDA-DD0150");

    /** CT-gated rules, evaluated on the pmda-res-ct fixtures with the residual stub provider. */
    private static final Set<String> CT_IDS = Set.of(//
            "PMDA-DD0029", "PMDA-DD0029-B", "PMDA-DD0132", "PMDA-DD0132-B", "PMDA-DD0099",
            "PMDA-DD0100");

    /** Rules that apply to Define-XML 2.1 only (skip on 2.0). */
    private static final Set<String> V21_ONLY_IDS = Set.of(//
            "PMDA-DD0138", "PMDA-DD0150", "PMDA-DD0132", "PMDA-DD0132-B");

    /** Surefire pins the CWD to target/test-cwd; anchor via projectBasedir (root pom). */
    private static final Path RULES_DIR = TestRules.PMDA;

    private static DocumentContext context(String aFixture, String aVersion,
            @Nullable CtProvider aProvider)
    {
        try (InputStream in = PmdaResidualRulesTest.class
                .getResourceAsStream("/fixtures/" + aFixture))
        {
            org.w3c.dom.Document doc = DefineDomIo.parse(Objects.requireNonNull(in, aFixture));
            ElementNode root = ElementNodeBuilder.build(doc);
            return new DocumentContext(root, aVersion, aProvider, null);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot load fixture " + aFixture, e);
        }
    }


    private static Map<String, RuleResult> evaluate(Set<String> aIds, DocumentContext aContext)
    {
        List<ConformanceRule> batch = RuleRepository.loadDirectory(RULES_DIR).stream()
                .filter(rule -> aIds.contains(rule.ruleId())).toList();
        assertEquals(aIds.size(), batch.size(), "residual rule files present");
        RuleEvaluator evaluator = new RuleEvaluator();
        return batch.stream().collect(Collectors.toMap(ConformanceRule::ruleId,
                rule -> evaluator.evaluate(rule, aContext)));
    }

    // ------------------------------------------------------------------
    // Non-CT rules
    // ------------------------------------------------------------------


    @Test
    void nonCtCleanFixtureProducesNoFindings()
    {
        Map<String, RuleResult> results = evaluate(NON_CT_IDS,
                context("pmda-res-clean-21.xml", "2.1", null));
        for (RuleResult result : results.values())
        {
            String ruleId = result.rule().ruleId();
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            assertEquals(List.of(), result.findings(), ruleId + " findings");
        }
    }


    @Test
    void nonCtViolationsFireEachRuleExactlyOnce()
    {
        Map<String, RuleResult> results = evaluate(NON_CT_IDS,
                context("pmda-res-violations-21.xml", "2.1", null));
        for (String ruleId : NON_CT_IDS)
        {
            RuleResult result = results.get(ruleId);
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            assertEquals(1, result.findings().size(),
                    ruleId + " finding count: " + result.findings());
        }
        assertTrue(results.get("PMDA-DD0105").findings().get(0).getXpath()
                .contains("ItemDef[@OID='IT.AESTDY']"), "DD0105 fires on the Study Day var");
        assertTrue(results.get("PMDA-OD0082").findings().get(0).getXpath()
                .contains("CodeList[@OID='CL.MIXED']"), "OD0082 fires on the mixed codelist");
        assertTrue(results.get("PMDA-DD0138").findings().get(0).getXpath().contains(
                "ItemGroupDef[@OID='IG.BAD']"), "DD0138 fires on the dangling StandardOID");
        assertTrue(results.get("PMDA-DD0093").findings().get(0).getXpath().contains("AR.BAD"),
                "DD0093 fires on the improper ParameterOID");

        assertEquals("Origin for Study Day variable is not set to Derived (found Collected)",
                results.get("PMDA-DD0105").findings().get(0).getMessage());
        assertEquals("Codelist is missing Decoded Values",
                results.get("PMDA-OD0082").findings().get(0).getMessage());
        assertEquals("Referenced Standard is missing [STD.MISSING]",
                results.get("PMDA-DD0138").findings().get(0).getMessage());
        assertEquals("Improper use of ParameterOID",
                results.get("PMDA-DD0093").findings().get(0).getMessage());
        assertEquals("Missing expected Standard",
                results.get("PMDA-DD0150").findings().get(0).getMessage());
    }


    @Test
    void nonCtViolationsAt20GateTheV21OnlyRules()
    {
        Map<String, RuleResult> results = evaluate(NON_CT_IDS,
                context("pmda-res-violations-21.xml", "2.0", null));
        assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, results.get("PMDA-DD0138").status());
        assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, results.get("PMDA-DD0150").status());
        for (String ruleId : Set.of("PMDA-DD0105", "PMDA-OD0082", "PMDA-DD0093"))
        {
            RuleResult result = results.get(ruleId);
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            assertEquals(1, result.findings().size(), ruleId + " still fires at 2.0");
        }
    }

    // ------------------------------------------------------------------
    // CT-gated rules
    // ------------------------------------------------------------------


    @Test
    void ctCleanFixtureProducesNoFindings()
    {
        Map<String, RuleResult> results = evaluate(CT_IDS,
                context("pmda-res-ct-clean-21.xml", "2.1", new ResidualCtProvider()));
        for (RuleResult result : results.values())
        {
            String ruleId = result.rule().ruleId();
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            assertEquals(List.of(), result.findings(), ruleId + " findings");
        }
    }


    @Test
    void ctViolationsFireEachLegExactlyOnce()
    {
        Map<String, RuleResult> results = evaluate(CT_IDS,
                context("pmda-res-ct-violations-21.xml", "2.1", new ResidualCtProvider()));
        for (String ruleId : CT_IDS)
        {
            RuleResult result = results.get(ruleId);
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            assertEquals(1, result.findings().size(),
                    ruleId + " finding count: " + result.findings());
        }
        assertEquals("Required attribute def:ExtendedValue is missing or empty for CodedValue [kg]",
                results.get("PMDA-DD0029").findings().get(0).getMessage());
        assertEquals("Required attribute def:ExtendedValue is missing or empty for CodedValue [kg]",
                results.get("PMDA-DD0029-B").findings().get(0).getMessage());
        assertEquals("Invalid use of def:ExtendedValue attribute on CodedValue [F]",
                results.get("PMDA-DD0132").findings().get(0).getMessage());
        assertEquals("Invalid use of def:ExtendedValue attribute on CodedValue [M]",
                results.get("PMDA-DD0132-B").findings().get(0).getMessage());
        assertEquals("Extended value for AnalysisReason [OTHER]",
                results.get("PMDA-DD0099").findings().get(0).getMessage());
        assertEquals("Extended value for AnalysisPurpose [OTHER]",
                results.get("PMDA-DD0100").findings().get(0).getMessage());
    }


    @Test
    void ctRulesSkipWithoutAProvider()
    {
        Map<String, RuleResult> results = evaluate(CT_IDS,
                context("pmda-res-ct-violations-21.xml", "2.1", null));
        for (RuleResult result : results.values())
        {
            assertEquals(ExecutionStatus.SKIPPED_MISSING_CT, result.status(),
                    result.rule().ruleId() + " skips without CT");
            assertEquals(List.of(), result.findings());
        }
    }


    @Test
    void ctViolationsAt20GateTheV21OnlyLegs()
    {
        Map<String, RuleResult> results = evaluate(CT_IDS,
                context("pmda-res-ct-violations-21.xml", "2.0", new ResidualCtProvider()));
        assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, results.get("PMDA-DD0132").status());
        assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, results.get("PMDA-DD0132-B").status());
        for (String ruleId : Set.of("PMDA-DD0029", "PMDA-DD0029-B", "PMDA-DD0099", "PMDA-DD0100"))
        {
            RuleResult result = results.get(ruleId);
            assertEquals(ExecutionStatus.EXECUTED, result.status(), ruleId + " status");
            assertEquals(1, result.findings().size(), ruleId + " still fires at 2.0");
        }
    }

    // ------------------------------------------------------------------
    // Severities and versions match the sheet
    // ------------------------------------------------------------------


    @Test
    void severitiesAndVersionsMatchTheSheet()
    {
        Map<String, RuleResult> all = evaluate(NON_CT_IDS,
                context("pmda-res-violations-21.xml", "2.1", null));
        all.putAll(evaluate(CT_IDS,
                context("pmda-res-ct-violations-21.xml", "2.1", new ResidualCtProvider())));

        Map<String, Severity> expectedSeverity = Map.ofEntries(//
                Map.entry("PMDA-DD0105", Severity.WARNING),
                Map.entry("PMDA-OD0082", Severity.WARNING),
                Map.entry("PMDA-DD0138", Severity.ERROR),
                Map.entry("PMDA-DD0093", Severity.WARNING),
                Map.entry("PMDA-DD0150", Severity.REJECT),
                Map.entry("PMDA-DD0029", Severity.WARNING),
                Map.entry("PMDA-DD0029-B", Severity.WARNING),
                Map.entry("PMDA-DD0132", Severity.ERROR),
                Map.entry("PMDA-DD0132-B", Severity.ERROR),
                Map.entry("PMDA-DD0099", Severity.WARNING),
                Map.entry("PMDA-DD0100", Severity.WARNING));
        for (Map.Entry<String, Severity> entry : expectedSeverity.entrySet())
        {
            ConformanceRule rule = all.get(entry.getKey()).rule();
            assertEquals(entry.getValue(), rule.effectiveSeverity(), entry.getKey() + " severity");
            List<String> expectedVersions = V21_ONLY_IDS.contains(entry.getKey()) ? List.of("2.1")
                    : List.of("2.0", "2.1");
            assertEquals(expectedVersions, rule.applicableVersions(), entry.getKey() + " versions");
        }
    }


    /** Parses an inline ODM document (no CT / folder) for the custom-check branch tests. */
    private static DocumentContext inlineContext(String aXml, String aVersion)
    {
        try (InputStream in = new ByteArrayInputStream(aXml.getBytes(StandardCharsets.UTF_8)))
        {
            return new DocumentContext(ElementNodeBuilder.build(DefineDomIo.parse(in)), aVersion,
                    null, null);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }


    /**
     * DD0150 across the SEND and SDTM families: a SENDIG IG with no {@code PublishingSet="SEND"} CT
     * fires; an SDTMIG IG properly paired with an SDTM CT does not.
     */
    @Test
    void standardsCombinationCoversSendAndSdtmFamilies()
    {
        String sendMissingCt = """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <Study OID="ST.1">
                    <MetaDataVersion OID="MDV.1" Name="MDV">
                      <def:Standards>
                        <def:Standard OID="STD.SEND" Name="SENDIG-AR" Type="IG" Version="1.1"/>
                      </def:Standards>
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """;
        RuleResult sendResult = evaluate(Set.of("PMDA-DD0150"), inlineContext(sendMissingCt, "2.1"))
                .get("PMDA-DD0150");
        assertEquals(1, sendResult.findings().size(), "SENDIG without a SEND CT fires");

        String sdtmPaired = """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <Study OID="ST.1">
                    <MetaDataVersion OID="MDV.1" Name="MDV">
                      <def:Standards>
                        <def:Standard OID="STD.IG" Name="SDTMIG" Type="IG" Version="3.4"/>
                        <def:Standard OID="STD.CT" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM"
                                      Version="2023-12-15"/>
                      </def:Standards>
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """;
        RuleResult sdtmResult = evaluate(Set.of("PMDA-DD0150"), inlineContext(sdtmPaired, "2.1"))
                .get("PMDA-DD0150");
        assertEquals(List.of(), sdtmResult.findings(), "SDTMIG paired with an SDTM CT is clean");
    }


    /** DD0093 fires when a ParameterOID does not resolve to any ItemDef at all. */
    @Test
    void armParameterOidDanglingReferenceFires()
    {
        String dangling = """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1"
                     xmlns:arm="http://www.cdisc.org/ns/arm/v1.0">
                  <Study OID="ST.1">
                    <MetaDataVersion OID="MDV.1" Name="MDV">
                      <arm:AnalysisResultDisplays>
                        <arm:ResultDisplay OID="RD.1" Name="T1">
                          <arm:AnalysisResult OID="AR.X" ParameterOID="IT.NOWHERE"
                                              AnalysisReason="DATA DRIVEN"
                                              AnalysisPurpose="PRIMARY OUTCOME MEASURE">
                            <arm:AnalysisDatasets>
                              <arm:AnalysisDataset ItemGroupOID="IG.MISSING"/>
                            </arm:AnalysisDatasets>
                          </arm:AnalysisResult>
                        </arm:ResultDisplay>
                      </arm:AnalysisResultDisplays>
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """;
        RuleResult result = evaluate(Set.of("PMDA-DD0093"), inlineContext(dangling, "2.1"))
                .get("PMDA-DD0093");
        assertEquals(1, result.findings().size(), "a dangling ParameterOID is improper use");
    }


    /**
     * DD0093 fires when the ParameterOID resolves to a PARAMCD variable that is not a member of any
     * of the result's analysed datasets (covers the dataset-membership loop completing unmatched).
     */
    @Test
    void armParameterOidParamcdNotAMemberFires()
    {
        String notAMember = """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1"
                     xmlns:arm="http://www.cdisc.org/ns/arm/v1.0">
                  <Study OID="ST.1">
                    <MetaDataVersion OID="MDV.1" Name="MDV">
                      <ItemGroupDef OID="IG.ADLB" Name="ADLB" SASDatasetName="ADLB">
                        <ItemRef ItemOID="IT.AVAL" OrderNumber="1" Mandatory="No"/>
                      </ItemGroupDef>
                      <ItemDef OID="IT.PARAMCD" Name="PARAMCD" DataType="text" Length="8"/>
                      <ItemDef OID="IT.AVAL" Name="AVAL" DataType="float" Length="8"/>
                      <arm:AnalysisResultDisplays>
                        <arm:ResultDisplay OID="RD.1" Name="T1">
                          <arm:AnalysisResult OID="AR.X" ParameterOID="IT.PARAMCD"
                                              AnalysisReason="DATA DRIVEN"
                                              AnalysisPurpose="PRIMARY OUTCOME MEASURE">
                            <arm:AnalysisDatasets>
                              <arm:AnalysisDataset ItemGroupOID="IG.ADLB"/>
                            </arm:AnalysisDatasets>
                          </arm:AnalysisResult>
                        </arm:ResultDisplay>
                      </arm:AnalysisResultDisplays>
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """;
        RuleResult result = evaluate(Set.of("PMDA-DD0093"), inlineContext(notAMember, "2.1"))
                .get("PMDA-DD0093");
        assertEquals(1, result.findings().size(),
                "PARAMCD that no analysed dataset references is improper use");
    }

    /**
     * In-memory {@link CtProvider} for the residual CT rules: the extensible Unit codelist
     * ({@code C71620}) and non-extensible Sex codelist ({@code C66731}) shared with the shipped CT
     * fixtures, plus the two ARM codelists the residual rows name by c-code — Analysis Reason
     * ({@code C117744}) and Analysis Purpose ({@code C117745}), both extensible. Own stub (the
     * shipped {@code ct.StubCtProvider} lacks the ARM codelists and must not be edited).
     */
    private static final class ResidualCtProvider implements CtProvider
    {

        private static final CtCodelist UNIT = new CtCodelist("C71620", true,
                Map.of("mg", "C28253", "mL", "C28254"));

        private static final CtCodelist SEX = new CtCodelist("C66731", false,
                Map.of("F", "C16576", "M", "C20197", "U", "C17998"));

        private static final CtCodelist ANALYSIS_REASON = new CtCodelist("C117744", true,
                Map.of("SPECIFIED IN PROTOCOL", "C117752", "SPECIFIED IN SAP", "C117753",
                        "DATA DRIVEN", "C117754", "REQUESTED BY REGULATORY AGENCY", "C117755"));

        private static final CtCodelist ANALYSIS_PURPOSE = new CtCodelist("C117745", true,
                Map.of("PRIMARY OUTCOME MEASURE", "C117748", "SECONDARY OUTCOME MEASURE", "C117749",
                        "EXPLORATORY OUTCOME MEASURE", "C117750"));

        private static final Map<String, CtCodelist> BY_C_CODE = Map.of(//
                UNIT.cCode(), UNIT, SEX.cCode(), SEX, ANALYSIS_REASON.cCode(), ANALYSIS_REASON,
                ANALYSIS_PURPOSE.cCode(), ANALYSIS_PURPOSE);

        @Override
        public Optional<CtCodelist> codelistByCCode(String aCCode)
        {
            return Optional.ofNullable(BY_C_CODE.get(aCCode));
        }


        @Override
        public Optional<CtCodelist> codelistByName(String aName)
        {
            return Optional.empty();
        }

    }

}
