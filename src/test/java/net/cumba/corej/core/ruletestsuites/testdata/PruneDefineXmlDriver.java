package net.cumba.corej.core.ruletestsuites.testdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.cdisc.define.DefineXmlPruner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * One-shot driver that regenerates the trimmed test-resource Define-XML files. Keeps only the
 * ItemGroups (domains) actually consumed by the Java-resident test classes and cascades orphan
 * ItemDefs / CodeLists / MethodDefs / CommentDefs / Leafs via
 * {@link DefineXmlPruner#cascadeOrphans()}.
 *
 * <p>
 * Sources: the SDTM define is the pilot define under the untracked {@code testdata/study/sdtm/}.
 * The ADaM define is CDISC's MIT-licensed {@code DataExchange-DatasetJson}
 * {@code examples/adam/define.xml} (Define-XML 2.1), passed as {@code -Dprune.adam.source=<path>}
 * so the regeneration names its input instead of relying on an untracked copy. After the prune the
 * ADaM define is adapted to the fixture beside it ({@link #adaptAdamDefine}): dataset leaves
 * {@code .xpt} → {@code .dsjc}, the two {@code ADLBC} ISO columns {@code AXDTM} / {@code AXTM}
 * declared, and the {@code ODM} {@code SourceSystem} / {@code SourceSystemVersion} attributes
 * removed. The provenance is recorded in {@code testdata_study/README.md}.
 * </p>
 *
 * <p>
 * The trimmed defines live under {@code src/test/resources/testdata_study/{sdtm,adam}/define.xml}
 * and are loaded by {@code AbstractSdtmRuleTest} / {@code AbstractAdamRuleTest} via the classpath.
 * </p>
 *
 * <p>
 * Activated only when {@code -Dprune.defines=true} is passed to the test runner:
 * </p>
 *
 * <pre>
 *   mvn -o test -Dtest=PruneDefineXmlDriver -Dprune.defines=true \
 *       -Dprune.adam.source=&lt;DataExchange-DatasetJson&gt;/examples/adam/define.xml
 * </pre>
 *
 * <p>
 * The class name intentionally does NOT match the default surefire {@code *Test.java} pattern —
 * ordinary runs skip it even if the flag leaks.
 * </p>
 */
@EnabledIfSystemProperty(named = "prune.defines", matches = "true")
class PruneDefineXmlDriver // NOSONAR S3577 — Underscore-categorised name is the suite convention.
{

    /** Upstream SDTM define (the full 22-domain pilot study, untracked). */
    private static final Path SDTM_SOURCE = Path.of(System.getProperty("repoRoot"),
            "testdata/study/sdtm/define.xml");

    /**
     * System property naming the upstream ADaM define: {@code examples/adam/define.xml} of a
     * {@code cdisc-org/DataExchange-DatasetJson} checkout (12 ADaM datasets, Define-XML 2.1).
     */
    private static final String ADAM_SOURCE_PROPERTY = "prune.adam.source";

    /** Target defines shipped as test resources. */
    private static final Path SDTM_TARGET = Path.of(System.getProperty("projectBasedir"),
            "src/test/resources/testdata_study/sdtm/define.xml");

    private static final Path ADAM_TARGET = Path.of(System.getProperty("projectBasedir"),
            "src/test/resources/testdata_study/adam/define.xml");

    /**
     * Domains consumed by SDTM Java-resident tests. All others are pruned.
     * <ul>
     * <li>{@code AE}, {@code DM}, {@code LB}, {@code TA} — primary tables opened directly via
     * {@code testTable(...)}.</li>
     * <li>{@code RELREC} — primary for {@code CORE-000916} variants; the Pilot-01 RELREC rows
     * reference {@code AE} and {@code DS} as RDOMAINs, so both targets must be in the resolver's
     * inventory for the distinct(IDVAR) value_is_reference lookup to resolve.</li>
     * <li>{@code DS} — only loaded to satisfy RELREC's RDOMAIN targets; no test opens it
     * directly.</li>
     * </ul>
     */
    private static final Set<String> SDTM_KEEP = Set.of("AE", "DM", "DS", "LB", "RELREC", "TA");

    /**
     * Domains consumed by ADaM Java-resident template tests, by ItemGroup OID ({@code IG.} is the
     * DatasetJson define's OID prefix).
     */
    private static final Set<String> ADAM_KEEP = Set.of("IG.ADSL", "IG.ADLBC", "IG.ADAE");

    /** The ADLBC ItemGroup, which gains the two ISO columns. */
    private static final String ADLBC_OID = "IG.ADLBC";

    /**
     * The two ISO text columns appended to ADLBC for the {@code CDISC-AD0044} {@code *DTM} /
     * {@code *TM} template pair. The data side is
     * {@code testdata_study/adam/add_adlbc_iso_columns.py}; both must agree.
     */
    private static final List<IsoColumn> ADLBC_ISO_COLUMNS = List.of(
            new IsoColumn("AXDTM", 20, "Analysis Datetime (ISO)"),
            new IsoColumn("AXTM", 8, "Analysis Time (ISO)"));

    private static final String ODM_NS = "http://www.cdisc.org/ns/odm/v1.3";

    private static final String DEF21_NS = "http://www.cdisc.org/ns/def/v2.1";

    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";

    @Test
    void pruneSdtmDefine() throws Exception
    {
        pruneDefine(SDTM_SOURCE, SDTM_TARGET, SDTM_KEEP);
        assertKeptExactly(SDTM_SOURCE, SDTM_TARGET, SDTM_KEEP);
    }


    @Test
    void pruneAdamDefine() throws Exception
    {
        String source = System.getProperty(ADAM_SOURCE_PROPERTY);
        assertTrue(source != null && !source.isBlank(), "-D" + ADAM_SOURCE_PROPERTY
                + "=<DataExchange-DatasetJson>/examples/adam/define.xml is required");
        Path adamSource = Path.of(source);
        pruneDefine(adamSource, ADAM_TARGET, ADAM_KEEP, PruneDefineXmlDriver::adaptAdamDefine);
        assertKeptExactly(adamSource, ADAM_TARGET, ADAM_KEEP);
        assertAdaptedAdamDefine(ADAM_TARGET);
    }


    /**
     * Fits the pruned DatasetJson ADaM define to the fixture beside it: every dataset leaf names
     * the {@code .dsjc} file actually shipped (the source names {@code .xpt}), ADLBC declares the
     * appended {@code AXDTM} / {@code AXTM} columns, and the {@code ODM} source-system attributes
     * go (they name the tool that wrote the upstream file, which is not what wrote this one).
     */
    private static void adaptAdamDefine(Document doc)
    {
        Element odm = doc.getDocumentElement();
        odm.removeAttribute("SourceSystem");
        odm.removeAttribute("SourceSystemVersion");

        NodeList leafs = doc.getElementsByTagNameNS(DEF21_NS, "leaf");
        for (int i = 0; i < leafs.getLength(); i++)
        {
            Element leaf = (Element) leafs.item(i);
            String href = leaf.getAttributeNS(XLINK_NS, "href");
            if (href.endsWith(".xpt"))
            {
                leaf.setAttributeNS(XLINK_NS, "xlink:href", toDsjc(href));
                NodeList titles = leaf.getElementsByTagNameNS(DEF21_NS, "title");
                for (int t = 0; t < titles.getLength(); t++)
                {
                    titles.item(t).setTextContent(toDsjc(titles.item(t).getTextContent().strip()));
                }
            }
        }

        Element adlbc = findByOid(doc, "ItemGroupDef", ADLBC_OID);
        NodeList refs = adlbc.getElementsByTagNameNS(ODM_NS, "ItemRef");
        Element lastRef = (Element) refs.item(refs.getLength() - 1);
        int order = Integer.parseInt(lastRef.getAttribute("OrderNumber"));
        NodeList defs = doc.getElementsByTagNameNS(ODM_NS, "ItemDef");
        Element lastDef = (Element) defs.item(defs.getLength() - 1);
        Node refAnchor = lastRef.getNextSibling();
        Node defAnchor = lastDef.getNextSibling();
        for (IsoColumn col : ADLBC_ISO_COLUMNS)
        {
            order++;
            String oid = col.oid();
            Element ref = doc.createElementNS(ODM_NS, "ItemRef");
            ref.setAttribute("ItemOID", oid);
            ref.setAttribute("Mandatory", "No");
            ref.setAttribute("OrderNumber", Integer.toString(order));
            adlbc.insertBefore(ref, refAnchor);

            Element def = doc.createElementNS(ODM_NS, "ItemDef");
            def.setAttribute("OID", oid);
            def.setAttribute("Name", col.name());
            def.setAttribute("DataType", "text");
            def.setAttribute("Length", Integer.toString(col.length()));
            def.setAttribute("SASFieldName", col.name());
            Element description = doc.createElementNS(ODM_NS, "Description");
            Element text = doc.createElementNS(ODM_NS, "TranslatedText");
            text.setTextContent(col.label());
            description.appendChild(text);
            def.appendChild(description);
            Element origin = doc.createElementNS(DEF21_NS, "def:Origin");
            origin.setAttribute("Type", "Derived");
            origin.setAttribute("Source", "Sponsor");
            def.appendChild(origin);
            lastDef.getParentNode().insertBefore(def, defAnchor);
        }
    }


    private static String toDsjc(String name)
    {
        return name.substring(0, name.length() - ".xpt".length()) + ".dsjc";
    }


    private static Element findByOid(Document doc, String localName, String oid)
    {
        NodeList nodes = doc.getElementsByTagNameNS(ODM_NS, localName);
        for (int i = 0; i < nodes.getLength(); i++)
        {
            Element el = (Element) nodes.item(i);
            if (oid.equals(el.getAttribute("OID")))
            {
                return el;
            }
        }
        throw new IllegalStateException(localName + " " + oid + " not found");
    }


    /**
     * The written ADaM define carries what {@link #adaptAdamDefine} promises: no {@code .xpt} leaf
     * left, both ISO columns declared and referenced, no source-system attribute.
     */
    private static void assertAdaptedAdamDefine(Path target) throws Exception
    {
        String xml = Files.readString(target, StandardCharsets.UTF_8);
        assertFalse(xml.contains(".xpt"), "an .xpt leaf survived in " + target);
        assertFalse(xml.contains("SourceSystem"), "a SourceSystem attribute survived in " + target);
        for (IsoColumn col : ADLBC_ISO_COLUMNS)
        {
            assertTrue(xml.contains("ItemOID=\"" + col.oid() + "\""),
                    col.name() + " ItemRef missing in " + target);
            assertTrue(xml.contains(" OID=\"" + col.oid() + "\""),
                    col.name() + " ItemDef missing in " + target);
        }
    }


    /**
     * The written define holds exactly the kept domains the source carried: every one of them
     * survived the prune, and nothing else did.
     */
    private static void assertKeptExactly(Path source, Path target, Set<String> keep)
        throws Exception
    {
        Set<String> expected = new TreeSet<>(collectItemGroupOIDs(source));
        expected.retainAll(keep);
        assertFalse(expected.isEmpty(), "none of " + keep + " is an ItemGroup of " + source);
        assertEquals(expected, new TreeSet<>(collectItemGroupOIDs(target)),
                "ItemGroups written to " + target);
    }


    private static void pruneDefine(Path source, Path target, Set<String> keep) throws Exception
    {
        pruneDefine(source, target, keep, doc ->
        {
        });
    }


    private static void pruneDefine(Path source, Path target, Set<String> keep,
            Consumer<Document> adapt)
        throws Exception
    {
        assertTrue(Files.isRegularFile(source), "source define not found: " + source);

        List<String> allGroups = collectItemGroupOIDs(source);
        List<String> toRemove = new ArrayList<>();
        for (String oid : allGroups)
        {
            if (!keep.contains(oid))
            {
                toRemove.add(oid);
            }
        }

        DefineXmlPruner pruner = DefineXmlPruner.forFile(source);
        pruner.removeItemGroups(toRemove.toArray(String[]::new));
        pruner.cascadeOrphans();
        adapt.accept(pruner.getDocument());
        Files.createDirectories(target.getParent());
        pruner.writeTo(target);

        System.out.println();
        System.out.println("=== Pruned " + source.getFileName() + " ===");
        System.out.println("  source ItemGroups : " + allGroups);
        System.out.println("  kept              : " + keep);
        System.out.println("  removed           : " + toRemove);
        System.out.println("  written to        : " + target);
    }

    /**
     * One ISO text column appended to ADLBC.
     *
     * @param name
     *            the variable name.
     * @param length
     *            the declared length.
     * @param label
     *            the variable label.
     */
    private record IsoColumn(String name, int length, String label)
    {

        String oid()
        {
            return "IT.ADLBC." + name;
        }
    }

    /**
     * Minimal text-level scan for {@code ItemGroupDef OID="…"} attributes. We can't reuse the
     * pruner's own DOM because it would require first loading the full document — which we'll do
     * anyway, but the OID list is needed up-front to compute the {@code toRemove} argument.
     */
    private static List<String> collectItemGroupOIDs(Path source) throws Exception
    {
        // Define-XML files can be UTF-8 (SDTM) or ISO-8859-1 (older ADaM exports).
        // ASCII bytes are identical in both; read as ISO-8859-1 to avoid decode
        // errors while scanning the ASCII-only OID/attribute tokens.
        Pattern p = Pattern.compile("<\\w*:?ItemGroupDef[^>]*?OID=\"([^\"]+)\"");
        String xml = new String(Files.readAllBytes(source), StandardCharsets.ISO_8859_1);
        Matcher m = p.matcher(xml);
        List<String> out = new ArrayList<>();
        while (m.find())
        {
            out.add(m.group(1));
        }
        return out;
    }
}
