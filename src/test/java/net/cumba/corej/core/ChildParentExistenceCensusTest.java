package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.cumba.corej.core.UnqualifiedJoinedReadLintTest.Libraries;
import net.cumba.corej.core.UnqualifiedJoinedReadLintTest.LibraryDataset;
import net.cumba.corej.core.UnqualifiedJoinedReadLintTest.Universe;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.typed.StageBChecker;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The existence census of {@code PLAN-merged-evaluation-table} W4 (MET #2: <i>"an existence census
 * catches authoring bugs"</i>): since MQ1 a {@code Child: true} parent's columns are read QUALIFIED
 * ({@code AE.AESMIE}), and a qualified read names no column of the evaluated dataset — a misspelled
 * parent ({@code EA.AESMIE}) or column ({@code AE.AESMEI}) reads the not-supplied default on every
 * row, silently, and nothing at load can know the parent's columns. So every static qualified read
 * of every rule carrying a {@code Child} entry — a dotted operand, a {@code colref} /
 * {@code var_exists} / {@code find_vars} literal, a dotted {@code Output_Variables} entry — must
 * name a dataset the package's library publishes and a column that dataset defines. The library is
 * the project's own metadata store ({@link MetadataStoreFixture}), through the same
 * {@link Libraries} the unqualified-read lint judges with.
 *
 * <p>
 * Nothing examined is dropped silently: a {@code Child} rule whose package the store does not
 * publish is counted in UNJUDGED, and a parent read whose qualifier is data-derived
 * ({@code colref(concat(RDOMAIN, ".", IDVAR))}, the per-row pointer rules) in DYNAMIC; both, and
 * the judged reads themselves, are pinned EXACTLY — a census that stopped seeing the rules reports
 * zero misses too.
 * </p>
 *
 * <p>
 * Non-vacuity on every build: synthetic rules with a misspelled column and a misspelled parent must
 * be caught, their correct spelling must not.
 * </p>
 */
class ChildParentExistenceCensusTest
{

    /** (rule \t qualified read) → packages judged. Measured at W4 (both corpora identical). */
    private static final Map<String, Integer> EXPECTED_JUDGED = Map.of("CDISC-CG0043\tAE.AESMIE", 3,
            "FDA-SD1143\tAE.AESMIE", 4);

    /** rule → packages whose parent reads are data-derived (the per-row pointer rules). */
    private static final Map<String, Integer> EXPECTED_DYNAMIC = Map.of("CDISC-CG0371", 3,
            "FDA-SD0077", 10, "PMDA-SD0077", 5);

    /** package \t rule the store publishes no library for. */
    private static final Set<String> EXPECTED_UNJUDGED = Set.of();

    /** The calls whose first argument names a column. */
    private static final Set<String> NAME_CALLS = Set.of("colref", "var_exists", "var_not_exists",
            "exists", "not_exists", "find_vars");

    /** What one census saw. */
    static final class Census
    {

        final Map<String, Set<String>> judged = new TreeMap<>();

        final Map<String, Set<String>> dynamic = new TreeMap<>();

        final Set<String> unjudged = new TreeSet<>();

        /** rule \t read \t why → packages. */
        final Map<String, Set<String>> misses = new TreeMap<>();

        void examine(String aPackage, @Nullable Universe aUniverse, Rule aRule)
        {
            List<MatchDataset> joins = aRule.getMatchDatasets();
            if (joins == null || joins.stream()
                    .noneMatch(md -> md != null && Boolean.TRUE.equals(md.getChild())))
            {
                return;
            }
            String id = String.valueOf(aRule.effectiveId());
            Set<String> reads = new TreeSet<>();
            boolean[] dynamicRead = new boolean[1];
            for (Expr root : StageBChecker.expressionRoots(aRule))
            {
                collect(root, reads, dynamicRead);
            }
            if (aRule.getOutcome() != null && aRule.getOutcome().getOutputVariables() != null)
            {
                for (String ov : aRule.getOutcome().getOutputVariables())
                {
                    if (ov != null && ov.indexOf('.') > 0 && !ov.startsWith("$"))
                    {
                        reads.add(ov);
                    }
                }
            }
            if (dynamicRead[0])
            {
                dynamic.computeIfAbsent(id, k -> new TreeSet<>()).add(aPackage);
            }
            if (reads.isEmpty())
            {
                return;
            }
            if (aUniverse == null)
            {
                unjudged.add(aPackage + "\t" + id);
                return;
            }
            for (String read : reads)
            {
                judged.computeIfAbsent(id + "\t" + read, k -> new TreeSet<>()).add(aPackage);
                int dot = read.indexOf('.');
                String parent = read.substring(0, dot).toUpperCase(Locale.ROOT);
                String column = read.substring(dot + 1);
                LibraryDataset dataset = aUniverse.joinTargets().get(parent);
                String why = dataset == null ? "no dataset " + parent + " in the library"
                        : !dataset.defines(column.toUpperCase(Locale.ROOT))
                                ? parent + " defines no column " + column
                                : null;
                if (why != null)
                {
                    misses.computeIfAbsent(id + "\t" + read + "\t" + why, k -> new TreeSet<>())
                            .add(aPackage);
                }
            }
        }


        /** The static qualified reads of an expression; a computed name marks it dynamic. */
        private static void collect(Expr aExpr, Set<String> aOut, boolean[] aDynamic)
        {
            switch (aExpr)
            {
            case Expr.Ref ref ->
            {
                if (ref.kind() == OperandKind.DOTTED_REF)
                {
                    aOut.add(ref.name());
                }
            }
            case Expr.Lit lit ->
            {
                if (lit.value() instanceof List<?> items)
                {
                    for (Object item : items)
                    {
                        if (item instanceof Expr nested)
                        {
                            collect(nested, aOut, aDynamic);
                        }
                    }
                }
            }
            case Expr.Not not -> collect(not.inner(), aOut, aDynamic);
            case Expr.And and -> and.parts().forEach(p -> collect(p, aOut, aDynamic));
            case Expr.Or or -> or.parts().forEach(p -> collect(p, aOut, aDynamic));
            case Expr.Binary b ->
            {
                collect(b.left(), aOut, aDynamic);
                collect(b.right(), aOut, aDynamic);
            }
            case Expr.Call call ->
            {
                if (NAME_CALLS.contains(call.name()) && !call.args().isEmpty())
                {
                    Expr first = call.args().get(0);
                    if (first instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING)
                    {
                        String name = String.valueOf(lit.value());
                        if (name.indexOf('.') > 0)
                        {
                            aOut.add(name);
                        }
                    }
                    else if ("colref".equals(call.name()) && first instanceof Expr.Call)
                    {
                        aDynamic[0] = true;
                    }
                }
                call.args().forEach(a -> collect(a, aOut, aDynamic));
                call.kwargs().values().forEach(a -> collect(a, aOut, aDynamic));
            }
            }
        }
    }

    @Test
    void everyQualifiedReadOfAChildParentNamesALibraryColumn() throws IOException
    {
        Path storeFile = MetadataStoreFixture.storeFile()
                .orElseThrow(() -> new AssertionError("No metadata store: the census decides"
                        + " which library dataset defines a column. Set CDISC_PICKLE_CACHE_DIR (or"
                        + " -Dcdisc.pickle.cache.dir); failing rather than skipping is deliberate."));
        StoreMetadataProviderFactory factory = MetadataStoreFixture.factory()
                .orElseThrow(() -> new AssertionError("no metadata store provider factory"));
        Census census = new Census();
        try (MetadataStore store = MetadataStore.open(storeFile))
        {
            Libraries libraries = new Libraries(store, factory);
            JsonNode manifest = new ObjectMapper()
                    .readTree(CorpusPaths.rules("packages.json").toFile());
            for (JsonNode pkg : manifest.path("packages"))
            {
                String file = pkg.path("file").asText();
                List<String> primaries = new ArrayList<>();
                for (JsonNode s : pkg.path("standards"))
                {
                    if ("primary".equals(s.path("role").asText()))
                    {
                        primaries.add(s.path("id").asText());
                    }
                }
                Universe universe = libraries.universe(primaries);
                RulePackage rules = RulePackageLoader.load(CorpusPaths.rules(file));
                for (Rule rule : rules.getRules().values())
                {
                    if (rule != null && rule.getLoadError() == null)
                    {
                        census.examine(file, universe, rule);
                    }
                }
            }
        }
        census.misses.forEach((k, v) -> System.out.println("  MISS\t" + k + "\t" + v));
        assertTrue(census.misses.isEmpty(), census.misses.size() + " qualified read(s) of a Child"
                + " parent name no column of the library: each reads the not-supplied default on"
                + " every row. rule / read / why -> packages:\n  "
                + String.join("\n  ", census.misses.keySet()));
        assertEquals(EXPECTED_JUDGED, packageCounts(census.judged), "the static Child-parent"
                + " reads judged, (rule, read) -> packages — a new Child rule moves this (confirm it"
                + " is judged, then move the pin)");
        assertEquals(EXPECTED_DYNAMIC, packageCounts(census.dynamic), "the per-row pointer rules"
                + " whose parent reads are data-derived (no static census sees them)");
        assertEquals(EXPECTED_UNJUDGED, census.unjudged,
                "Child rules in a package the store" + " publishes no library for");
    }


    @Test
    void aMisspelledParentOrColumnIsCaughtAndTheCorrectSpellingIsNot() throws IOException
    {
        Universe universe = new Universe("synthetic", "SDTM", List.of(),
                Map.of("AE", LibraryDataset.of("AE", "EVENTS", List.of(), List.of(),
                        List.of("STUDYID", "USUBJID", "AESEQ", "AESMIE"))));
        Census census = new Census();
        census.examine("p", universe, rule("T-OK", "AE.AESMIE != \\\"Y\\\""));
        census.examine("p", universe, rule("T-COLUMN", "AE.AESMEI != \\\"Y\\\""));
        census.examine("p", universe, rule("T-PARENT", "var_exists(\\\"EA.AESMIE\\\")"));
        census.examine("p", universe,
                rule("T-DYN", "str(IDVARVAL) != str(colref(concat(RDOMAIN, \\\".\\\", IDVAR)))"));
        assertEquals(
                Set.of("T-COLUMN\tAE.AESMEI\tAE defines no column AESMEI",
                        "T-PARENT\tEA.AESMIE\tno dataset EA in the library"),
                census.misses.keySet());
        assertEquals(Set.of("T-DYN"), census.dynamic.keySet());
        assertEquals(Set.of("T-OK\tAE.AESMIE", "T-COLUMN\tAE.AESMEI", "T-PARENT\tEA.AESMIE"),
                census.judged.keySet());
    }


    private static Rule rule(String aId, String aCheck) throws IOException
    {
        String json = "{\"rules\": {\"r\": {\"Core\": {\"Id\": \"" + aId + "\"},"
                + "\"Scope\": {\"Domains\": {\"Include\": [\"SUPPAE\"]}},"
                + "\"Requirements\": {\"Variables\": {\"All\": [\"USUBJID\", \"IDVAR\","
                + " \"IDVARVAL\"]}}, \"Match_Datasets\": [{\"Name\": \"AE\", \"Child\": true,"
                + " \"Keys\": [\"USUBJID\", \"IDVAR\", \"IDVARVAL\"]}],"
                + "\"Check\": {\"expression\": \"" + aCheck + "\"}}}}";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().values().iterator().next();
        assertNull(rule.getLoadError(), () -> aId + " did not load: " + json);
        return rule;
    }


    private static Map<String, Integer> packageCounts(Map<String, Set<String>> aRegister)
    {
        Map<String, Integer> counts = new TreeMap<>();
        aRegister.forEach((k, v) -> counts.put(k, v.size()));
        return counts;
    }
}
