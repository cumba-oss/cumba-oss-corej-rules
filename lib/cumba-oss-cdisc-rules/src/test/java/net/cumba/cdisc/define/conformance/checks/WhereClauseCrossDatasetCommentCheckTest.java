package net.cumba.cdisc.define.conformance.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.cdisc.define.conformance.TestRules;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.eval.RuleEvaluator;
import net.cumba.cdisc.define.conformance.eval.RuleResult;
import net.cumba.cdisc.define.conformance.rule.ConformanceRule;
import net.cumba.cdisc.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/** DEFINE-XML-0084 end-to-end through the shipped custom check. */
class WhereClauseCrossDatasetCommentCheckTest
{

    private static DocumentContext context(String aXml)
    {
        try (var in = new ByteArrayInputStream(aXml.getBytes(StandardCharsets.UTF_8)))
        {
            return new DocumentContext(ElementNodeBuilder.build(DefineDomIo.parse(in)), "2.1", null,
                    null);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * WC.CROSS references items living only in different datasets (LB vs VS) and has no comment →
     * fires. WC.CROSSOK is the same shape WITH a comment → silent. WC.SINGLE's items share dataset
     * LB → silent. WC.SHARED's items have a common dataset (the shared item lives in both) → not
     * provably cross-dataset → silent. WC.ONE has a single range-check → silent. WC.DANGLING
     * references an item no dataset carries → ignored attribution, effectively single-dataset →
     * silent.
     */
    private static final String DOC = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.1">
              <MetaDataVersion OID="MDV.1">
                <ItemGroupDef OID="IG.LB" Name="LB">
                  <ItemRef ItemOID="IT.LB.A"/>
                  <ItemRef ItemOID="IT.LB.B"/>
                  <ItemRef ItemOID="IT.SHARED"/>
                </ItemGroupDef>
                <ItemGroupDef OID="IG.VS" Name="VS">
                  <ItemRef ItemOID="IT.VS.A"/>
                  <ItemRef ItemOID="IT.SHARED"/>
                </ItemGroupDef>
                <def:WhereClauseDef OID="WC.CROSS">
                  <RangeCheck def:ItemOID="IT.LB.A" Comparator="EQ" SoftHard="Soft"/>
                  <RangeCheck def:ItemOID="IT.VS.A" Comparator="EQ" SoftHard="Soft"/>
                </def:WhereClauseDef>
                <def:WhereClauseDef OID="WC.CROSSOK" def:CommentOID="COM.1">
                  <RangeCheck def:ItemOID="IT.LB.A" Comparator="EQ" SoftHard="Soft"/>
                  <RangeCheck def:ItemOID="IT.VS.A" Comparator="EQ" SoftHard="Soft"/>
                </def:WhereClauseDef>
                <def:WhereClauseDef OID="WC.SINGLE">
                  <RangeCheck def:ItemOID="IT.LB.A" Comparator="EQ" SoftHard="Soft"/>
                  <RangeCheck def:ItemOID="IT.LB.B" Comparator="EQ" SoftHard="Soft"/>
                </def:WhereClauseDef>
                <def:WhereClauseDef OID="WC.SHARED">
                  <RangeCheck def:ItemOID="IT.LB.A" Comparator="EQ" SoftHard="Soft"/>
                  <RangeCheck def:ItemOID="IT.SHARED" Comparator="EQ" SoftHard="Soft"/>
                </def:WhereClauseDef>
                <def:WhereClauseDef OID="WC.ONE">
                  <RangeCheck def:ItemOID="IT.LB.A" Comparator="EQ" SoftHard="Soft"/>
                </def:WhereClauseDef>
                <def:WhereClauseDef OID="WC.DANGLING">
                  <RangeCheck def:ItemOID="IT.LB.A" Comparator="EQ" SoftHard="Soft"/>
                  <RangeCheck def:ItemOID="IT.NOWHERE" Comparator="EQ" SoftHard="Soft"/>
                </def:WhereClauseDef>
                <def:CommentDef OID="COM.1"/>
              </MetaDataVersion>
            </ODM>
            """;

    @Test
    void firesOnlyOnProvablyCrossDatasetWhereClausesWithoutComment()
    {
        ConformanceRule rule = TestRules.load().stream()
                .filter(r -> "DEFINE-XML-0084".equals(r.ruleId())).findFirst().orElseThrow();
        RuleResult result = new RuleEvaluator().evaluate(rule, context(DOC));
        assertEquals(1, result.findings().size(), result.findings().toString());
        assertTrue(result.findings().get(0).getXpath().contains("'WC.CROSS'"),
                result.findings().get(0).getXpath());
        assertEquals(List.of(), result.findings().stream()
                .filter(f -> !f.getXpath().contains("'WC.CROSS'")).toList());
    }

}
