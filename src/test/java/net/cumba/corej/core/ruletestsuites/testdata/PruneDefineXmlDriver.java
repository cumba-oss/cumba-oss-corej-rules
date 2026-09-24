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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.cdisc.define.DefineXmlPruner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * One-shot driver that regenerates the trimmed test-resource Define-XML files from the upstream
 * {@code testdata/study/} copies. Keeps only the ItemGroups (domains) actually consumed by the
 * Java-resident test classes and cascades orphan ItemDefs / CodeLists / MethodDefs / CommentDefs /
 * Leafs via {@link DefineXmlPruner#cascadeOrphans()}.
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
 *   mvn -T6 test -pl lib/net.cumba.corej.core.ruletest.suites \
 *       -Dtest=PruneDefineXmlDriver -Dprune.defines=true
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

    /** Upstream defines (full 22-domain SDTM + 10-domain ADaM studies). */
    private static final Path SDTM_SOURCE = Path.of(System.getProperty("repoRoot"),
            "testdata/study/sdtm/define.xml");

    private static final Path ADAM_SOURCE = Path.of(System.getProperty("repoRoot"),
            "testdata/study/adam/define.xml");

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

    /** Domains consumed by ADaM Java-resident template tests. */
    private static final Set<String> ADAM_KEEP = Set.of("ADSL", "ADLBC", "ADAE");

    @Test
    void pruneSdtmDefine() throws Exception
    {
        pruneDefine(SDTM_SOURCE, SDTM_TARGET, SDTM_KEEP);
        assertKeptExactly(SDTM_SOURCE, SDTM_TARGET, SDTM_KEEP);
    }


    @Test
    void pruneAdamDefine() throws Exception
    {
        pruneDefine(ADAM_SOURCE, ADAM_TARGET, ADAM_KEEP);
        assertKeptExactly(ADAM_SOURCE, ADAM_TARGET, ADAM_KEEP);
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
