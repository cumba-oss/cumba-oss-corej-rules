package net.cumba.cdisc.define.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.eval.RuleEvaluator;
import net.cumba.cdisc.define.conformance.eval.RuleResult;
import net.cumba.cdisc.define.conformance.rule.ConformanceRule;
import net.cumba.cdisc.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/**
 * Coordinator tests for the review-pmda-p3 resolutions: PMDA-DD0072-B (value-level Origin branch,
 * W3) and the DD0103 Source-qualified Collected leg (W4).
 */
class PmdaP3ResolutionsTest
{

    private static final Set<String> IDS = Set.of("PMDA-DD0072-B", "PMDA-DD0103");

    private static Map<String, RuleResult> evaluate(String aXml)
    {
        try (var in = new ByteArrayInputStream(aXml.getBytes(StandardCharsets.UTF_8)))
        {
            DocumentContext ctx = new DocumentContext(
                    ElementNodeBuilder.build(DefineDomIo.parse(in)), "2.1", null, null);
            RuleEvaluator evaluator = new RuleEvaluator();
            return TestRules.load().stream().filter(rule -> IDS.contains(rule.ruleId()))
                    .collect(Collectors.toMap(ConformanceRule::ruleId,
                            rule -> evaluator.evaluate(rule, ctx)));
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * DD0072-B: IT.VLM2 (Origin-less value-level member of VL.X, whose delegating variable IT.VAR
     * also lacks an Origin) fires; IT.VLM1 (own Origin) and VL.Y's member (variable IT.VAR2 carries
     * the Origin) stay silent. DD0103: the Collected/Vendor DocumentRef with page refs and no
     * AnnotatedCRF must stay SILENT after W4 (Source not Investigator or Subject); the
     * Collected/Investigator one fires.
     */
    private static final String DOC = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.1" def:Context="Submission">
              <Study OID="ST.1">
                <MetaDataVersion OID="MDV.1">
                  <ItemDef OID="IT.VAR" Name="LBORRES" DataType="text">
                    <def:ValueListRef ValueListOID="VL.X"/>
                  </ItemDef>
                  <ItemDef OID="IT.VAR2" Name="LBORRESU" DataType="text">
                    <def:ValueListRef ValueListOID="VL.Y"/>
                    <def:Origin Type="Collected"/>
                  </ItemDef>
                  <ItemDef OID="IT.VLM1" Name="V1" DataType="text">
                    <def:Origin Type="Derived"/>
                  </ItemDef>
                  <ItemDef OID="IT.VLM2" Name="V2" DataType="text"/>
                  <ItemDef OID="IT.DELEGATED" Name="V4" DataType="text"/>
                  <ItemDef OID="IT.CRF" Name="AETERM" DataType="text">
                    <def:Origin Type="Collected" Source="Investigator">
                      <def:DocumentRef leafID="LF.ACRF">
                        <def:PDFPageRef PageRefs="4" Type="PhysicalRef"/>
                      </def:DocumentRef>
                    </def:Origin>
                  </ItemDef>
                  <ItemDef OID="IT.EDT" Name="LBSEQ" DataType="integer">
                    <def:Origin Type="Collected" Source="Vendor">
                      <def:DocumentRef leafID="LF.EDT">
                        <def:PDFPageRef PageRefs="9" Type="PhysicalRef"/>
                      </def:DocumentRef>
                    </def:Origin>
                  </ItemDef>
                  <def:ValueListDef OID="VL.X">
                    <ItemRef ItemOID="IT.VLM1"/>
                    <ItemRef ItemOID="IT.VLM2"/>
                  </def:ValueListDef>
                  <def:ValueListDef OID="VL.Y">
                    <ItemRef ItemOID="IT.DELEGATED"/>
                  </def:ValueListDef>
                  <def:leaf ID="LF.ACRF" href="acrf.pdf"><def:title>aCRF</def:title></def:leaf>
                  <def:leaf ID="LF.EDT" href="edt.pdf"><def:title>eDT spec</def:title></def:leaf>
                </MetaDataVersion>
              </Study>
            </ODM>
            """;

    @Test
    void valueLevelOriginBranchFiresOnUndelegatedMembersOnly()
    {
        Map<String, RuleResult> results = evaluate(DOC);
        var findings = results.get("PMDA-DD0072-B").findings();
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).getXpath().contains("VL.X"), findings.get(0).getXpath());
    }


    @Test
    void collectedLegRequiresInvestigatorOrSubjectSource()
    {
        Map<String, RuleResult> results = evaluate(DOC);
        var findings = results.get("PMDA-DD0103").findings();
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).getXpath().contains("IT.CRF"), findings.get(0).getXpath());
    }

}
