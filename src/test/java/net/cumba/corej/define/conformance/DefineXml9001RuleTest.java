package net.cumba.corej.define.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.eval.RuleEvaluator;
import net.cumba.corej.define.conformance.eval.RuleResult;
import net.cumba.corej.define.conformance.report.ExecutionStatus;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.RuleRepository;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * {@code DEFINE-XML-9001} (minted, rule round 2026-10-12, owner item C2b; sheet issue #291): a SEND
 * Define-XML's variable descriptions ({@code ItemDef}) and comments ({@code def:CommentDef}) use
 * "subject" rather than "animal" (SENDIG 3.1.1 section 4.2.3), the SENDIG's own labels exempt. The
 * data-label half is {@code CDISC-SEND-9444}.
 */
class DefineXml9001RuleTest
{

    private static final String ID = "DEFINE-XML-9001";

    private static ConformanceRule rule()
    {
        List<ConformanceRule> matches = RuleRepository.loadDirectory(TestRules.CDISC).stream()
                .filter(r -> ID.equals(r.ruleId())).toList();
        assertEquals(1, matches.size(), ID + ".yaml present");
        return matches.get(0);
    }


    private static RuleResult run(String aXml, String aVersion)
    {
        try (InputStream in = new ByteArrayInputStream(aXml.getBytes(StandardCharsets.UTF_8)))
        {
            Document document = DefineDomIo.parse(in);
            DocumentContext context = new DocumentContext(ElementNodeBuilder.build(document),
                    aVersion, null, null, List.of());
            return new RuleEvaluator().evaluate(rule(), context);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }


    private static List<String> messages(RuleResult aResult)
    {
        assertEquals(ExecutionStatus.EXECUTED, aResult.status());
        return aResult.findings().stream().map(f -> f.getMessage()).toList();
    }


    private static String text(String aText)
    {
        return "<Description><TranslatedText xml:lang=\"en\">" + aText
                + "</TranslatedText></Description>";
    }


    /** A Define-XML 2.1 document whose def:Standards carry {@code aStandard}. */
    private static String define21(String aStandard, String aItemDefs, String aComments)
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1"
                     FileOID="F.9001" FileType="Snapshot" ODMVersion="1.3.2"
                     CreationDateTime="2026-10-12T10:00:00">
                  <Study OID="ST.9001">
                    <GlobalVariables>
                      <StudyName>S</StudyName>
                      <StudyDescription>S</StudyDescription>
                      <ProtocolName>S</ProtocolName>
                    </GlobalVariables>
                    <MetaDataVersion OID="MDV.1" Name="M" def:DefineVersion="2.1.0">
                      <def:Standards>
                        <def:Standard OID="STD.IG" Name="%s" Type="IG" Version="3.1.1" Status="Final"/>
                        <def:Standard OID="STD.CT" Name="CDISC/NCI" Type="CT" PublishingSet="SEND" Version="2024-09-27" Status="Final"/>
                      </def:Standards>
                      <ItemGroupDef OID="IG.BW" Name="BW" Repeating="Yes" Domain="BW" def:Structure="x" def:StandardOID="STD.IG">
                        <Description><TranslatedText xml:lang="en">Body Weight of the animals</TranslatedText></Description>
                      </ItemGroupDef>
                      %s
                      <MethodDef OID="MT.1" Name="M" Type="Computation">
                        <Description><TranslatedText xml:lang="en">Weigh each animal</TranslatedText></Description>
                      </MethodDef>
                      %s
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """
                .formatted(aStandard, aItemDefs, aComments);
    }


    private static String item(String aOid, String aName, String aText)
    {
        return "<ItemDef OID=\"" + aOid + "\" Name=\"" + aName
                + "\" DataType=\"text\" Length=\"40\">" + text(aText) + "</ItemDef>";
    }


    private static String comment(String aOid, String aText)
    {
        return "<def:CommentDef OID=\"" + aOid + "\">" + text(aText) + "</def:CommentDef>";
    }


    @Test
    void anItemDefDescriptionThatIsNotAnIgLabelFires()
    {
        String xml = define21("SENDIG", item("IT.BW.BWTESTCD", "BWTESTCD", "Animal Weight Code")
                + item("IT.BW.BWDTC", "BWDTC", "Date/Time Animal Weighed"), "");
        assertEquals(
                List.of("The description [Animal Weight Code] uses \"animal\"; the SENDIG asks"
                        + " for \"subject\" in all labels and comments."),
                messages(run(xml, "2.1")),
                "the sponsor wording fires; the IG label BWDTC, the ItemGroupDef and the MethodDef"
                        + " descriptions do not (out of the rule's scope)");
    }


    @Test
    void aCommentDefWithAnimalsFires()
    {
        String xml = define21("SENDIG-DART", item("IT.BW.BWDY", "BWDY", "Study Day Animal Weighed"),
                comment("COM.1", "All animals were fasted overnight.")
                        + comment("COM.2", "Each subject was fasted overnight."));
        assertEquals(1, messages(run(xml, "2.1")).size(),
                "the 'animals' comment fires; the 'subject' comment and the IG label BWDY do not");
    }


    @Test
    void theIgLabelsAndACleanDefineDoNotFire()
    {
        String xml = define21("SENDIG",
                item("IT.BW.BWDTC", "BWDTC", "Date/Time Animal Weighed")
                        + item("IT.BG.BGENDTC", "BGENDTC", "End Date/Time Animal Weighed")
                        + item("IT.BW.BWDY", "BWDY", "Study Day Animal Weighed")
                        + item("IT.BW.USUBJID", "USUBJID", "Unique Subject Identifier")
                        + item("IT.BW.BWNOTE", "BWNOTE", "Animalcule count is not a word match"),
                comment("COM.1", "Subjects were weighed before dosing."));
        assertEquals(List.of(), messages(run(xml, "2.1")));
    }


    @Test
    void anSdtmDefineIsOutOfScope()
    {
        String xml = define21("SDTMIG", item("IT.AE.AETERM", "AETERM", "Animal bite"),
                comment("COM.1", "Animal exposure"));
        assertEquals(List.of(), messages(run(xml, "2.1")), "not a SEND define");
    }


    @Test
    void aDefine20NamesTheStandardOnMetaDataVersion()
    {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.0"
                     FileOID="F.9001" FileType="Snapshot" ODMVersion="1.3.2"
                     CreationDateTime="2026-10-12T10:00:00">
                  <Study OID="ST.9001">
                    <GlobalVariables>
                      <StudyName>S</StudyName>
                      <StudyDescription>S</StudyDescription>
                      <ProtocolName>S</ProtocolName>
                    </GlobalVariables>
                    <MetaDataVersion OID="MDV.1" Name="M" def:DefineVersion="2.0.0"
                                     def:StandardName="SEND-IG" def:StandardVersion="3.0">
                      %s
                      %s
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """.formatted(
                item("IT.DM.DMANIM", "DMANIM", "Sponsor Animal Number")
                        + item("IT.BG.BGDTC", "BGDTC", "Date/Time Animal Weighed"),
                comment("COM.1", "Animal room temperature"));
        assertEquals(2, messages(run(xml, "2.0")).size(),
                "the sponsor ItemDef and the comment fire; the IG label BGDTC does not");
    }

}
