package net.cumba.corej.core.ruletestsuites.testdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
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
 * {@link DefineXmlPruner#cascadeOrphans()}. The cascade counts a reference from anywhere, an
 * analysis results display included, so for the ADaM define the result displays that analyse a
 * dropped dataset are removed first ({@link #dropForeignResultDisplays}); otherwise they keep that
 * dataset's definitions alive. {@link #assertReferencesResolve} then proves the written ADaM define
 * closed: every reference resolves and no definition is left unreferenced.
 *
 * <p>
 * Sources: the SDTM define is the pilot define under the untracked {@code testdata/study/sdtm/}.
 * The ADaM define is CDISC's MIT-licensed {@code DataExchange-DatasetJson}
 * {@code examples/adam/define.xml} (Define-XML 2.1), passed as {@code -Dprune.adam.source=<path>}
 * so the regeneration names its input instead of relying on an untracked copy. After the prune the
 * ADaM define is adapted to the fixture beside it ({@link #adaptAdamDefine}): dataset leaves
 * {@code .xpt} → {@code .dsjc}, the two {@code ADLBC} ISO columns {@code AXDTM} / {@code AXTM}
 * declared, and the {@code ODM} {@code SourceSystem} / {@code SourceSystemVersion} attributes
 * removed. The input's md5 is checked first, so a different upstream revision fails instead of
 * silently producing a different fixture. The provenance is recorded in
 * {@code testdata_study/README.md}.
 * </p>
 *
 * <p>
 * The trimmed defines live under {@code src/test/resources/testdata_study/{sdtm,adam}/define.xml}
 * and are loaded by {@code AbstractSdtmRuleTest} / {@code AbstractAdamRuleTest} via the classpath.
 * </p>
 *
 * <p>
 * Activated only when {@code -Dprune.defines=true} is passed to the test runner. Regenerate the
 * ADaM define alone:
 * </p>
 *
 * <pre>
 *   mvn -o test -Dtest=PruneDefineXmlDriver#pruneAdamDefine -Dprune.defines=true \
 *       -Dprune.adam.source=&lt;DataExchange-DatasetJson&gt;/examples/adam/define.xml
 * </pre>
 *
 * <p>
 * Do not run the whole class for that: {@link #pruneSdtmDefine} would rewrite
 * {@code sdtm/define.xml} from the untracked pilot define, and it has no leaf rewrite, so the
 * result would name {@code .xpt} datasets where the committed file names the {@code .dsjc} ones
 * beside it.
 * </p>
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

    /**
     * md5 of {@code examples/adam/define.xml} at {@code DataExchange-DatasetJson} {@code a379f49a},
     * the revision {@code testdata_study/README.md} records.
     */
    private static final String ADAM_SOURCE_MD5 = "053254bebd18b4d2316854cb0b409f00";

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

    private static final String ARM_NS = "http://www.cdisc.org/ns/arm/v1.0";

    /** The fixture dataset that carries the ISO columns, beside the ADaM define. */
    private static final Path ADLBC_DATASET = ADAM_TARGET.resolveSibling("adlbc.dsjc");

    /**
     * Reference attribute (local name) → the local name of the element it points at. Each such
     * attribute must name a declared {@code OID} (for {@code leafID}: a declared {@code ID}).
     */
    private static final Map<String, String> REFERENCE_TARGETS = referenceTargets();

    /**
     * Definitions that exist only to be referenced: each declared one must be the target of at
     * least one reference. For {@code ItemDef} only {@code ItemRef} and {@code RangeCheck} count (a
     * variable no dataset or value list carries is dead even if an analysis display names it).
     */
    private static final List<String> MUST_BE_REFERENCED = List.of("ItemDef", "ValueListDef",
            "WhereClauseDef", "CodeList", "MethodDef", "CommentDef");

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
        assertTrue(Files.isRegularFile(adamSource), "source define not found: " + adamSource);
        assertEquals(ADAM_SOURCE_MD5, md5(adamSource), adamSource
                + " is not the DataExchange-DatasetJson a379f49a define the README records");
        pruneDefine(adamSource, ADAM_TARGET, ADAM_KEEP,
                doc -> dropForeignResultDisplays(doc, ADAM_KEEP),
                PruneDefineXmlDriver::adaptAdamDefine);
        assertKeptExactly(adamSource, ADAM_TARGET, ADAM_KEEP);
        assertAdaptedAdamDefine(ADAM_TARGET);
        assertReferencesResolve(ADAM_TARGET);
        assertIsoColumnsMatchDataset(ADAM_TARGET, ADLBC_DATASET);
    }


    /**
     * Removes every {@code arm:ResultDisplay} that analyses a dataset outside {@code keep}, and the
     * {@code arm:AnalysisResultDisplays} container if none is left. Runs before the cascade: the
     * cascade counts the display's {@code ItemOID} / {@code WhereClauseOID} references as uses, so
     * a display of a dropped dataset would otherwise keep that dataset's items, value lists, where
     * clauses, code lists and methods alive.
     */
    private static void dropForeignResultDisplays(Document doc, Set<String> keep)
    {
        NodeList displays = doc.getElementsByTagNameNS(ARM_NS, "ResultDisplay");
        List<Element> drop = new ArrayList<>();
        for (int i = 0; i < displays.getLength(); i++)
        {
            Element display = (Element) displays.item(i);
            NodeList datasets = display.getElementsByTagNameNS(ARM_NS, "AnalysisDataset");
            for (int d = 0; d < datasets.getLength(); d++)
            {
                if (!keep.contains(((Element) datasets.item(d)).getAttribute("ItemGroupOID")))
                {
                    drop.add(display);
                    break;
                }
            }
        }
        for (Element display : drop)
        {
            Node container = display.getParentNode();
            container.removeChild(display);
            if (((Element) container).getElementsByTagNameNS(ARM_NS, "ResultDisplay")
                    .getLength() == 0)
            {
                container.getParentNode().removeChild(container);
            }
        }
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
            // Assigned, not Derived: Define-XML 2.1 requires a MethodOID on the ItemRef of a
            // Derived variable (DEFINE-XML-0073), and these columns have no method.
            Element origin = doc.createElementNS(DEF21_NS, "def:Origin");
            origin.setAttribute("Type", "Assigned");
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
     * The written define is closed: every reference attribute ({@link #REFERENCE_TARGETS}) names a
     * definition the file declares, and every definition of {@link #MUST_BE_REFERENCED} is the
     * target of at least one reference.
     */
    private static void assertReferencesResolve(Path target) throws Exception
    {
        Document doc = DefineXmlPruner.forFile(target).getDocument();
        Map<String, Set<String>> declared = new HashMap<>();
        Map<String, Set<String>> referenced = new HashMap<>();
        Map<String, Set<String>> dangling = new TreeMap<>();
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++)
        {
            Element el = (Element) all.item(i);
            String local = el.getLocalName();
            String id = "leaf".equals(local) ? el.getAttribute("ID") : el.getAttribute("OID");
            if (!id.isEmpty())
            {
                declared.computeIfAbsent(local, k -> new TreeSet<>()).add(id);
            }
        }
        for (int i = 0; i < all.getLength(); i++)
        {
            Element el = (Element) all.item(i);
            var attrs = el.getAttributes();
            for (int a = 0; a < attrs.getLength(); a++)
            {
                Node attr = attrs.item(a);
                String name = attr.getLocalName() != null ? attr.getLocalName()
                        : attr.getNodeName();
                String kind = REFERENCE_TARGETS.get(name);
                if (kind == null)
                {
                    continue;
                }
                String value = attr.getNodeValue();
                if (!declared.getOrDefault(kind, Set.of()).contains(value))
                {
                    dangling.computeIfAbsent(name, k -> new TreeSet<>()).add(value);
                }
                boolean counts = !"ItemDef".equals(kind) || "ItemRef".equals(el.getLocalName())
                        || "RangeCheck".equals(el.getLocalName());
                if (counts)
                {
                    referenced.computeIfAbsent(kind, k -> new TreeSet<>()).add(value);
                }
            }
        }
        assertEquals(Map.of(), dangling, "references to nothing in " + target);
        Map<String, Set<String>> unreferenced = new TreeMap<>();
        for (String kind : MUST_BE_REFERENCED)
        {
            Set<String> orphans = new TreeSet<>(declared.getOrDefault(kind, Set.of()));
            orphans.removeAll(referenced.getOrDefault(kind, Set.of()));
            if (!orphans.isEmpty())
            {
                unreferenced.put(kind, orphans);
            }
        }
        assertEquals(Map.of(), unreferenced, "definitions nothing references in " + target);
    }


    private static Map<String, String> referenceTargets()
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ItemGroupOID", "ItemGroupDef");
        m.put("ItemOID", "ItemDef");
        m.put("ParameterOID", "ItemDef");
        m.put("ValueListOID", "ValueListDef");
        m.put("WhereClauseOID", "WhereClauseDef");
        m.put("CodeListOID", "CodeList");
        m.put("MethodOID", "MethodDef");
        m.put("CommentOID", "CommentDef");
        m.put("StandardOID", "Standard");
        m.put("leafID", "leaf");
        return Map.copyOf(m);
    }


    /**
     * The ISO columns the define declares are the ones {@code add_adlbc_iso_columns.py} appended to
     * the dataset: same item OID, name, label and length on both sides.
     */
    private static void assertIsoColumnsMatchDataset(Path define, Path dataset) throws Exception
    {
        JsonNode metadata;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(dataset)), StandardCharsets.UTF_8)))
        {
            metadata = new ObjectMapper().readTree(in.readLine());
        }
        Map<String, JsonNode> columns = new HashMap<>();
        for (JsonNode column : metadata.get("columns"))
        {
            columns.put(column.get("name").asText(), column);
        }
        Document doc = DefineXmlPruner.forFile(define).getDocument();
        for (IsoColumn col : ADLBC_ISO_COLUMNS)
        {
            JsonNode column = columns.get(col.name());
            assertTrue(column != null, col.name() + " is not a column of " + dataset);
            Element def = findByOid(doc, "ItemDef", col.oid());
            String label = def.getElementsByTagNameNS(ODM_NS, "TranslatedText").item(0)
                    .getTextContent();
            assertEquals(List.of(col.oid(), col.name(), label, def.getAttribute("Length")),
                    List.of(column.get("itemOID").asText(), def.getAttribute("Name"),
                            column.get("label").asText(), column.get("length").asText()),
                    col.name() + ": define " + define + " vs dataset " + dataset);
        }
    }


    private static String md5(Path file) throws Exception
    {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("MD5").digest(Files.readAllBytes(file)));
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
        }, doc ->
        {
        });
    }


    /**
     * Prunes {@code source} to {@code keep}: {@code preCascade} sees the document after the
     * ItemGroups are removed and before the orphan cascade, {@code adapt} after the cascade.
     */
    private static void pruneDefine(Path source, Path target, Set<String> keep,
            Consumer<Document> preCascade, Consumer<Document> adapt)
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
        preCascade.accept(pruner.getDocument());
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
