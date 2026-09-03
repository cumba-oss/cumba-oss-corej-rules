package net.cumba.corej.define.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.eval.RuleEvaluator;
import net.cumba.corej.define.conformance.eval.RuleResult;
import net.cumba.corej.define.conformance.report.ExecutionStatus;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/**
 * Coordinator tests for the review-batch-d resolutions: the 0140/0140-B DataType version split,
 * 0154's either-level-Origin custom exemption, and 0155-B's value-level branch.
 */
class DReviewResolutionsTest
{

    private static final Set<String> IDS = Set.of("DEFINE-XML-0140", "DEFINE-XML-0140-B",
            "DEFINE-XML-0154", "DEFINE-XML-0155", "DEFINE-XML-0155-B");

    private static Map<String, RuleResult> evaluate(String aXml, String aVersion)
    {
        try (var in = new ByteArrayInputStream(aXml.getBytes(StandardCharsets.UTF_8)))
        {
            DocumentContext ctx = new DocumentContext(
                    ElementNodeBuilder.build(DefineDomIo.parse(in)), aVersion, null, null);
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
     * IT.VAR delegates to VL.X and carries no Origin itself; IT.VLM1 (value level) has an Origin,
     * IT.VLM2 has none → 0155-B fires once (IT.VLM2's ItemRef). IT.PLAIN has neither Origin nor
     * ValueListRef and is not VLM-referenced → 0154 fires once. IT.VLMONLY is Origin-less but IS a
     * value-level member → exempt from 0154 (custom). IT.DELEGATED's variable (IT.VAR2) has an
     * Origin → its Origin-less VLM member IT.DELEGATED stays silent under 0155-B. DataTypes:
     * IT.PLAIN uses intervalDatetime (valid 2.1, invalid 2.0).
     */
    private static final String DOC = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.1" def:Context="Submission">
              <Study OID="ST.1">
                <MetaDataVersion OID="MDV.1">
                  <ItemGroupDef OID="IG.LB" Name="LB">
                    <ItemRef ItemOID="IT.VAR"/>
                    <ItemRef ItemOID="IT.VAR2"/>
                    <ItemRef ItemOID="IT.PLAIN"/>
                  </ItemGroupDef>
                  <ItemDef OID="IT.VAR" Name="LBORRES" DataType="text">
                    <def:ValueListRef ValueListOID="VL.X"/>
                  </ItemDef>
                  <ItemDef OID="IT.VAR2" Name="LBORRESU" DataType="text">
                    <def:ValueListRef ValueListOID="VL.Y"/>
                    <def:Origin Type="Collected"/>
                  </ItemDef>
                  <ItemDef OID="IT.PLAIN" Name="LBSPEC" DataType="intervalDatetime"/>
                  <ItemDef OID="IT.VLM1" Name="V1" DataType="text">
                    <def:Origin Type="Derived"/>
                  </ItemDef>
                  <ItemDef OID="IT.VLM2" Name="V2" DataType="text"/>
                  <ItemDef OID="IT.VLMONLY" Name="V3" DataType="text"/>
                  <ItemDef OID="IT.DELEGATED" Name="V4" DataType="text"/>
                  <def:ValueListDef OID="VL.X">
                    <ItemRef ItemOID="IT.VLM1"/>
                    <ItemRef ItemOID="IT.VLM2"/>
                    <ItemRef ItemOID="IT.VLMONLY"/>
                  </def:ValueListDef>
                  <def:ValueListDef OID="VL.Y">
                    <ItemRef ItemOID="IT.DELEGATED"/>
                  </def:ValueListDef>
                </MetaDataVersion>
              </Study>
            </ODM>
            """;

    @Test
    void originPlacementRulesFireExactlyWhereTheSpecDemands()
    {
        Map<String, RuleResult> results = evaluate(DOC, "2.1");
        List<String> origin154 = results.get("DEFINE-XML-0154").findings().stream()
                .map(f -> f.getXpath()).toList();
        assertEquals(1, origin154.size(), origin154.toString());
        assertTrue(origin154.get(0).contains("IT.PLAIN"), origin154.toString());

        // Both Origin-less members of VL.X are genuine 0155-B violations: IT.VLM2 and
        // IT.VLMONLY (the latter is 0154-exempt as a VLM member but still needs its
        // value-level Origin). IT.VLM1 (has Origin) and VL.Y's IT.DELEGATED (variable
        // carries the Origin) stay silent.
        List<String> valueLevel = results.get("DEFINE-XML-0155-B").findings().stream()
                .map(f -> f.getXpath()).toList();
        assertEquals(2, valueLevel.size(), valueLevel.toString());
        assertTrue(valueLevel.stream().allMatch(x -> x.contains("VL.X")), valueLevel.toString());
    }


    @Test
    void dataTypeVersionSplitGatesCorrectly()
    {
        Map<String, RuleResult> on21 = evaluate(DOC, "2.1");
        assertEquals(ExecutionStatus.EXECUTED, on21.get("DEFINE-XML-0140").status());
        assertEquals(List.of(), on21.get("DEFINE-XML-0140").findings(),
                "intervalDatetime is valid 2.1");
        assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION,
                on21.get("DEFINE-XML-0140-B").status());

        Map<String, RuleResult> on20 = evaluate(DOC, "2.0");
        assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, on20.get("DEFINE-XML-0140").status());
        assertEquals(1, on20.get("DEFINE-XML-0140-B").findings().size(),
                "intervalDatetime is not a 2.0 DataType");
    }

}
