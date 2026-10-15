package net.cumba.corej.define.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.eval.RuleEvaluator;
import net.cumba.corej.define.conformance.eval.RuleResult;
import net.cumba.corej.define.conformance.library.LibraryProvider;
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import net.cumba.corej.define.conformance.report.ExecutionStatus;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.RuleRepository;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * {@code DEFINE-XML-9001} (minted, rule round 2026-10-12, owner item C2b; sheet issue #291): a SEND
 * Define-XML's variable descriptions ({@code ItemDef}) and comments ({@code def:CommentDef}) use
 * "subject" rather than "animal" (SENDIG 3.1.1 section 4.2.3), the SENDIG's own labels exempt. The
 * data-label half is {@code CDISC-SEND-9444}.
 *
 * <p>
 * Since {@code PLAN-define-rule-library-label} the exemption is the CDISC Library label of the
 * variable the description belongs to ({@code exemptLibraryLabel}, {@code Requires: library}), for
 * the IG version the define declares, no longer a fixed list of three labels. The cases bind
 * {@link Labels}, a small library serving exactly the labels named here, keyed by standard and
 * version; {@link DefineXml9001RealStoreTest} runs the rule against the metadata store.
 * </p>
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
        return run(aXml, aVersion, new Labels());
    }


    private static RuleResult run(String aXml, String aVersion, @Nullable LibraryProvider aLibrary)
    {
        try (InputStream in = new ByteArrayInputStream(aXml.getBytes(StandardCharsets.UTF_8)))
        {
            Document document = DefineDomIo.parse(in);
            DocumentContext context = new DocumentContext(ElementNodeBuilder.build(document),
                    aVersion, null, null, List.of(), aLibrary);
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
        return aResult.findings().stream().map(ConformanceFinding::getMessage).toList();
    }


    private static String text(String aText)
    {
        return "<Description><TranslatedText xml:lang=\"en\">" + aText
                + "</TranslatedText></Description>";
    }


    /**
     * One ItemGroupDef per dataset {@code BW} / {@code BG} with an ItemRef to each of the
     * document's {@code IT.<dataset>.*} items, so every such ItemDef is bound to its variable.
     */
    private static String groups(String aItemDefs, boolean aStandardOid)
    {
        StringBuilder out = new StringBuilder(256);
        for (String dataset : List.of("BW", "BG"))
        {
            out.append("<ItemGroupDef OID=\"IG.").append(dataset).append("\" Name=\"")
                    .append(dataset).append("\" Repeating=\"Yes\" Domain=\"").append(dataset)
                    .append("\" def:Structure=\"x\"")
                    .append(aStandardOid ? " def:StandardOID=\"STD.IG\"" : "").append('>');
            out.append("<Description><TranslatedText xml:lang=\"en\">").append(dataset)
                    .append(" of the animals</TranslatedText></Description>");
            Matcher oid = Pattern.compile("OID=\"(IT\\." + dataset + "\\.[A-Z]+)\"")
                    .matcher(aItemDefs);
            while (oid.find())
            {
                out.append("<ItemRef ItemOID=\"").append(oid.group(1))
                        .append("\" Mandatory=\"No\"/>");
            }
            out.append("</ItemGroupDef>");
        }
        return out.toString();
    }


    /** A Define-XML 2.1 document whose def:Standards carry {@code aStandard} {@code aVersion}. */
    private static String define21(String aStandard, String aVersion, String aItemDefs,
            String aComments)
    {
        return define21(aStandard, aVersion, aItemDefs, aComments, true);
    }


    private static String define21(String aStandard, String aVersion, String aItemDefs,
            String aComments, boolean aStandardOid)
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
                        <def:Standard OID="STD.IG" Name="%s" Type="IG" Version="%s" Status="Final"/>
                        <def:Standard OID="STD.CT" Name="CDISC/NCI" Type="CT" PublishingSet="SEND" Version="2024-09-27" Status="Final"/>
                      </def:Standards>
                      %s
                      %s
                      <MethodDef OID="MT.1" Name="M" Type="Computation">
                        <Description><TranslatedText xml:lang="en">Weigh each animal</TranslatedText></Description>
                      </MethodDef>
                      %s
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """
                .formatted(aStandard, aVersion, groups(aItemDefs, aStandardOid), aItemDefs,
                        aComments);
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
        String xml = define21("SENDIG", "3.1.1",
                item("IT.BW.BWTESTCD", "BWTESTCD", "Animal Weight Code")
                        + item("IT.BW.BWDTC", "BWDTC", "Date/Time Animal Weighed"),
                "");
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
        String xml = define21("SENDIG-DART", "1.2",
                item("IT.BW.BWDY", "BWDY", "Study Day Animal Weighed"),
                comment("COM.1", "All animals were fasted overnight.")
                        + comment("COM.2", "Each subject was fasted overnight."));
        assertEquals(List.of("/CommentDef[@OID='COM.1']/Description/TranslatedText"),
                xpaths(run(xml, "2.1")),
                "the 'animals' comment fires; the 'subject' comment and the IG label BWDY do not");
    }


    @Test
    void theIgLabelsAndACleanDefineDoNotFire()
    {
        String xml = define21("SENDIG", "3.1.1",
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
        String xml = define21("SDTMIG", "3.4", item("IT.AE.AETERM", "AETERM", "Animal bite"),
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
                      <ItemGroupDef OID="IG.BG" Name="BG" Repeating="Yes" Domain="BG" def:Structure="x">
                        <ItemRef ItemOID="IT.BG.BGDTC" Mandatory="No"/>
                      </ItemGroupDef>
                      %s
                      %s
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """
                .formatted(
                        item("IT.DM.DMANIM", "DMANIM", "Sponsor Animal Number")
                                + item("IT.BG.BGDTC", "BGDTC", "Date/Time Animal Weighed"),
                        comment("COM.1", "Animal room temperature"));
        assertEquals(
                List.of("/ItemDef[@OID='IT.DM.DMANIM']/Description/TranslatedText",
                        "/CommentDef[@OID='COM.1']/Description/TranslatedText"),
                xpaths(run(xml, "2.0")),
                "the sponsor ItemDef and the comment fire; the IG label BGDTC does not");
    }


    @Test
    void anItemDefWordedAsAnotherVariablesIgLabelFires()
    {
        // E1: BWTESTCD's label is "Test Short Name"; the old fixed list passed any ItemDef worded
        // as one of its three labels. BWDTC, worded with its own label, stays silent.
        String xml = define21("SENDIG", "3.1.1",
                item("IT.BW.BWTESTCD", "BWTESTCD", "Date/Time Animal Weighed")
                        + item("IT.BW.BWDTC", "BWDTC", "Date/Time Animal Weighed"),
                "");
        assertEquals(List.of("/ItemDef[@OID='IT.BW.BWTESTCD']/Description/TranslatedText"),
                xpaths(run(xml, "2.1")));
    }


    @Test
    void aCommentWordedAsAnIgLabelFires()
    {
        // E4 (LL6 (a)): a comment is not a label, whatever its wording
        String xml = define21("SENDIG", "3.1.1",
                item("IT.BW.BWDTC", "BWDTC", "Date/Time Animal Weighed"),
                comment("COM.1", "Date/Time Animal Weighed"));
        assertEquals(List.of("/CommentDef[@OID='COM.1']/Description/TranslatedText"),
                xpaths(run(xml, "2.1")));
    }


    @Test
    void anIgLabelFiresWhenTheDatasetNamesNoStandard()
    {
        // E6 (LL4 (a)): a 2.1 ItemGroupDef without def:StandardOID has no governing standard, so
        // the IG labels have no exemption although the define declares exactly one SENDIG
        String xml = define21("SENDIG", "3.1.1",
                item("IT.BW.BWDTC", "BWDTC", "Date/Time Animal Weighed")
                        + item("IT.BW.BWDY", "BWDY", "Study Day Animal Weighed"),
                "", false);
        assertEquals(
                List.of("/ItemDef[@OID='IT.BW.BWDTC']/Description/TranslatedText",
                        "/ItemDef[@OID='IT.BW.BWDY']/Description/TranslatedText"),
                xpaths(run(xml, "2.1")));
    }


    @Test
    void withoutALibraryTheRuleIsSkippedCommentsIncluded()
    {
        // E5 (LL2 (a), review L2): no store bound - the rule SKIPs as a whole, so the "animals"
        // comment, which needs no library, is not reported either
        String xml = define21("SENDIG-DART", "1.2",
                item("IT.BW.BWDY", "BWDY", "Study Day Animal Weighed"),
                comment("COM.1", "All animals were fasted overnight."));
        RuleResult result = run(xml, "2.1", null);
        assertEquals(ExecutionStatus.SKIPPED_MISSING_LIBRARY, result.status());
        assertEquals(List.of(), result.findings());
    }


    private static List<String> xpaths(RuleResult aResult)
    {
        assertEquals(ExecutionStatus.EXECUTED, aResult.status());
        return aResult.findings().stream().map(ConformanceFinding::getXpath)
                .map(x -> x.substring(x.indexOf('/', x.indexOf("MetaDataVersion[")))).toList();
    }

    /**
     * The library these cases bind: exactly the labels named here, keyed by the hyphen-stripped
     * standard name and the version, so a label is served only for the version the define declares.
     * The values are the metadata store's labels for those variables.
     */
    private static final class Labels implements LibraryProvider
    {

        private static final Map<String, String> VARIABLE_LABELS = Map.of("SENDIG|3.1.1|BW|BWDTC",
                "Date/Time Animal Weighed", "SENDIG|3.1.1|BW|BWDY", "Study Day Animal Weighed",
                "SENDIG|3.1.1|BW|BWTESTCD", "Test Short Name", "SENDIG|3.1.1|BG|BGENDTC",
                "End Date/Time Animal Weighed", "SENDIGDART|1.2|BW|BWDY",
                "Study Day Animal Weighed", "SENDIG|3.0|BG|BGDTC", "Date/Time Animal Weighed");

        @Override
        public Optional<String> datasetLabel(String aStandardName, String aStandardVersion,
                String aDatasetName)
        {
            return Optional.empty();
        }


        @Override
        public Optional<String> variableLabel(String aStandardName, String aStandardVersion,
                String aDatasetName, String aVariableName)
        {
            return Optional.ofNullable(
                    VARIABLE_LABELS.get(aStandardName.replace("-", "").toUpperCase(Locale.ROOT)
                            + "|" + aStandardVersion + "|" + aDatasetName + "|" + aVariableName));
        }


        @Override
        public Optional<String> variableCodelistCCode(String aStandardName, String aStandardVersion,
                String aDatasetName, String aVariableName)
        {
            return Optional.empty();
        }


        @Override
        public Optional<String> qualifierVariableLabel(String aStandardName,
                String aStandardVersion, String aFragment)
        {
            return Optional.empty();
        }
    }

}
