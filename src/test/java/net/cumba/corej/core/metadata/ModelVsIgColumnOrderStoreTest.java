package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code FDA-SD0058} over the <b>real</b> library-metadata provider, across the model/IG version
 * skew ({@code PLAN-model-vs-ig-column-order} §5, phase 4).
 *
 * <p>
 * The rule's {@code .cdt} scenarios hand-author both lists through the map-backed test provider, so
 * they execute none of {@link MetadataLibraryProvider}. This class executes it: the store is the
 * one {@link MetadataStoreFixture} seeds from the pickle corpus into {@code target/} (never the
 * user home), and every provider is built by the production factory
 * ({@link StoreMetadataProviderFactory#forSdtm}), exactly as a CLI or data-browser run builds it.
 * </p>
 *
 * <p>
 * Two things are pinned:
 * </p>
 * <ol>
 * <li><b>The skew itself</b> — for each of the ten packages {@code FDA-SD0058} ships in, the
 * variables the IG defines for a dataset that the SDTM model version it links to does not list for
 * the dataset's observation class ({@code get_column_order_from_library} minus
 * {@code get_model_column_order}). Pinned as an exact roster, not a count: a model or IG bump that
 * trades one variable for another conserves a count and changes the roster.</li>
 * <li><b>The rule's verdict on it</b> — the shipped corpus rule, loaded from the generated package,
 * reports an IG-only variable at WARNING and a variable in neither list at ERROR, and a dataset
 * whose model list is empty (a sponsor-defined domain) SKIPS.</li>
 * </ol>
 *
 * <p>
 * ⛔ No assumption skips: without a pickle corpus the store cannot be seeded and every test here
 * FAILS, naming the variable to set. A skipped metadata test and a passing one look identical in a
 * log.
 * </p>
 */
class ModelVsIgColumnOrderStoreTest
{

    /** The rule under test. */
    private static final String RULE_ID = "FDA-SD0058";

    /**
     * Per package ({@code "<standard> <version>"} as the store keys it), per dataset: the
     * IG-defined variables absent from the dataset's model list. Measured 2026-09-26 through this
     * class's own {@link #skewOf} walk; an empty map is a package with no skew.
     */
    private static final Map<String, Map<String, Set<String>>> SKEW = skewRoster();

    private static Map<String, Map<String, Set<String>>> skewRoster()
    {
        Map<String, Map<String, Set<String>>> skew = new LinkedHashMap<>();
        skew.put("sdtmig 3-1-2", Map.of());
        skew.put("sdtmig 3-1-3", Map.of());
        skew.put("sdtmig 3-2", Map.of());
        skew.put("sdtmig 3-3",
                Map.ofEntries(Map.entry("EG", Set.of("EGBEATNO")),
                        Map.entry("MH", Set.of("MHEVDTYP")),
                        Map.entry("MS", Set.of("MSAGENT", "MSCONC", "MSCONCU"))));
        skew.put("sdtmig 3-4", Map.of());
        skew.put("sendig 3-0", Map.ofEntries(Map.entry("BG", Set.of("BGEXCLFL", "BGREASEX")),
                Map.entry("BW", Set.of("BWEXCLFL", "BWREASEX")),
                Map.entry("CL", Set.of("CLEXCLFL", "CLREASEX", "POOLID")),
                Map.entry("CO", Set.of("CODY")),
                Map.entry("DM", Set.of("AGETXT", "SBSTRAIN", "SETCD", "SPECIES", "STRAIN")),
                Map.entry("EG", Set.of("EGCSTATE", "EGEXCLFL", "EGLEAD", "EGREASEX")),
                Map.entry("EX", Set.of("EXMETHOD", "POOLID")),
                Map.entry("FW", Set.of("FWEXCLFL", "FWREASEX", "POOLID")),
                Map.entry("LB",
                        Set.of("LBANTREG", "LBDIR", "LBEXCLFL", "LBLAT", "LBPORTOT", "LBREASEX",
                                "LBSPCUFL", "POOLID")),
                Map.entry("MA", Set.of("MAANTREG", "MADIR", "MALAT", "MAPORTOT", "MASPCUFL")),
                Map.entry("MI", Set.of("MIANTREG", "MIDIR", "MILAT", "MISPCUFL")),
                Map.entry("OM",
                        Set.of("OMANTREG", "OMDIR", "OMEXCLFL", "OMLAT", "OMPORTOT", "OMREASEX",
                                "OMSPCUFL")),
                Map.entry("PC", Set.of("PCEXCLFL", "PCREASEX", "POOLID")),
                Map.entry("PP", Set.of("POOLID", "PPENINT", "PPSTINT")),
                Map.entry("RELREC", Set.of("POOLID")),
                Map.entry("TF", Set.of("TFANTREG", "TFDETECT", "TFDIR", "TFLAT")),
                Map.entry("VS", Set.of("VSCSTATE", "VSEXCLFL", "VSREASEX"))));
        skew.put("sendig 3-1", Map.ofEntries(Map.entry("CO", Set.of("CODY")),
                Map.entry("EX", Set.of("EXMETHOD"))));
        skew.put("sendig 3-1-1", Map.ofEntries(Map.entry("CO", Set.of("CODY")),
                Map.entry("EX", Set.of("EXMETHOD"))));
        skew.put("sendig dart-1-1",
                Map.ofEntries(Map.entry("CO", Set.of("CODY", "CORPDY", "RPHASE")),
                        Map.entry("EX", Set.of("EXMETHOD")), Map.entry("IC", Set.of("ICIMPLBL"))));
        skew.put("sendig ar-1-0", Map.of());
        return skew;
    }


    private static StoreMetadataProviderFactory factory()
    {
        return MetadataStoreFixture.factory().orElseThrow(() -> new IllegalStateException(
                "No metadata store: the model/IG skew is a property of the CDISC Library products,"
                        + " not of this repository. Set CDISC_PICKLE_CACHE_DIR (or"
                        + " -Dcdisc.pickle.cache.dir) to a pickle cache directory —"
                        + " MetadataStoreFixture seeds the store from it."));
    }


    private static MetadataProvider provider(String aPackage)
    {
        String[] parts = aPackage.split(" ", -1);
        return factory().forSdtm(parts[0], parts[1], List.of()).orElseThrow(
                () -> new AssertionError("the seeded store lacks the IG product " + aPackage));
    }


    /** A one-row dataset carrying {@code DOMAIN} and the given columns. */
    private static IDataTable dataset(String aName, String... aColumns)
    {
        MockTable t = MockTable.of().name(aName).col("DOMAIN", aName);
        for (String column : aColumns)
        {
            t.col(column, "X");
        }
        return t.build();
    }


    /**
     * The skew of one package: per non-SUPP dataset, the names {@code getColumnOrder} (the
     * IG-resolved list, algorithm B) returns that {@code getStandardModelVariables} (the model
     * walk, algorithm A) does not.
     */
    private static Map<String, Set<String>> skewOf(MetadataProvider aProvider)
    {
        List<String> datasets = aProvider.getStandardDatasetNames();
        assertNotNull(datasets, "the provider enumerates no datasets");
        assertFalse(datasets.isEmpty(), "the provider enumerates no datasets");
        Map<String, Set<String>> out = new TreeMap<>();
        for (String name : datasets)
        {
            if (name.startsWith("SUPP") || name.startsWith("SQ"))
            {
                continue;
            }
            List<String> model = aProvider.getStandardModelVariables(dataset(name), null);
            Set<String> diff = new TreeSet<>(aProvider.getColumnOrder(name));
            if (model != null)
            {
                diff.removeAll(model);
            }
            if (!diff.isEmpty())
            {
                out.put(name, diff);
            }
        }
        return out;
    }


    private static Rule shippedRule(String aPackageFile) throws IOException
    {
        Rule rule = RulePackageLoader.load(CorpusPaths.rules(aPackageFile)).getRules().get(RULE_ID);
        assertNotNull(rule, RULE_ID + " must ship in " + aPackageFile);
        assertNull(rule.getLoadError(), RULE_ID + " must load clean: " + rule.getLoadError());
        return rule;
    }


    private static RuleExecutionResult run(String aPackageFile, String aStorePackage,
            IDataTable aTable)
        throws IOException
    {
        return RuleRunnerCalls.execute(shippedRule(aPackageFile), aTable,
                name -> name.equals(aTable.getMetaData().getName()) ? aTable : null,
                aTable.getMetaData().getName(), provider(aStorePackage));
    }


    /** variable name -> the level it was reported at. */
    private static Map<String, Severity> findings(RuleExecutionResult aResult)
    {
        assertFalse(aResult.isSkipped(), "the rule must execute: " + aResult.getStatusMessage());
        assertFalse(aResult.isError(), "the rule must not error: " + aResult.getStatusMessage());
        Map<String, Severity> out = new TreeMap<>();
        for (Violation v : aResult.getViolations())
        {
            String variable = v.getValues().get("variable_name");
            assertNotNull(variable, "every finding names its variable: " + v);
            assertNotNull(v.getLevel(), "every finding carries its level: " + v);
            assertNull(out.put(variable, v.getLevel()), "one finding per variable: " + variable);
        }
        return out;
    }


    /**
     * The packages the generated corpus ships {@link #RULE_ID} in, as store keys
     * ({@code rules-fda-sendig-dart-1-1.json} &rarr; {@code "sendig dart-1-1"}): every
     * {@code rules-*.json} under the corpus directory is loaded and asked, so the population is the
     * corpus's, not a count written into this class.
     */
    private static Set<String> shippingPackages() throws IOException
    {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.list(CorpusPaths.rules()))
        {
            for (Path file : files.sorted().toList())
            {
                String name = file.getFileName().toString();
                if (!name.startsWith("rules-") || !name.endsWith(".json"))
                {
                    continue;
                }
                if (RulePackageLoader.load(file).getRules().containsKey(RULE_ID))
                {
                    // rules-<family>-<standard>-<version>.json
                    String[] parts = name.substring(0, name.length() - ".json".length()).split("-",
                            4);
                    out.add(parts[2] + " " + parts[3]);
                }
            }
        }
        assertFalse(out.isEmpty(), RULE_ID + " ships in no generated package");
        return out;
    }


    @Test
    void theSkewRosterIsExactForEveryPackageTheRuleShipsIn() throws IOException
    {
        assertEquals(shippingPackages(), SKEW.keySet(),
                "the roster must cover exactly the packages the generated corpus ships " + RULE_ID
                        + " in");
        for (Map.Entry<String, Map<String, Set<String>>> e : SKEW.entrySet())
        {
            assertEquals(new TreeMap<>(e.getValue()), skewOf(provider(e.getKey())),
                    "IG-defined variables the linked model does not list, " + e.getKey());
        }
    }


    @Test
    void sdtmig34HasNoSkewAndAnAe120NameModelList()
    {
        // The plan's phase-0 control, through the real walk: AE / EVENTS / SDTMIG 3.4 is the list
        // rulespec/specs/FDA-SD0058.yaml pins as (120 names).
        List<String> ae = provider("sdtmig 3-4").getStandardModelVariables(dataset("AE"), null);
        assertNotNull(ae);
        assertEquals(120, new TreeSet<>(ae).size());
        assertTrue(ae.containsAll(List.of("AEBEATNO", "STUDYID", "USUBJID", "AESEQ", "AETERM")));
        assertTrue(SKEW.get("sdtmig 3-4").isEmpty());
    }


    @Test
    void sendig30IgOnlyVariableIsAWarningAndASponsorVariableAnError() throws IOException
    {
        IDataTable lb = dataset("LB", "STUDYID", "USUBJID", "LBSEQ", "LBTESTCD", "LBTEST",
                "LBORRES", "LBANTREG", "LBFOO");
        assertTrue(SKEW.get("sendig 3-0").get("LB").contains("LBANTREG"));
        assertEquals(Map.of("LBANTREG", Severity.WARNING, "LBFOO", Severity.ERROR),
                findings(run("rules-fda-sendig-3-0.json", "sendig 3-0", lb)));
    }


    @Test
    void sendig30SpecialPurposeSkewIsAWarningToo() throws IOException
    {
        // DM is non-detectable: the IG list is the IG dataset itself, the model list the model's
        // own DM variables — the skew the plan's first probe did not model.
        IDataTable dm = dataset("DM", "STUDYID", "USUBJID", "SPECIES", "STRAIN", "DMFOO");
        assertEquals(
                Map.of("SPECIES", Severity.WARNING, "STRAIN", Severity.WARNING, "DMFOO",
                        Severity.ERROR),
                findings(run("rules-fda-sendig-3-0.json", "sendig 3-0", dm)));
    }


    @Test
    void sdtmig33BeatNumberIsAWarning() throws IOException
    {
        IDataTable eg = dataset("EG", "STUDYID", "USUBJID", "EGSEQ", "EGTESTCD", "EGTEST",
                "EGORRES", "EGBEATNO");
        assertEquals(Map.of("EGBEATNO", Severity.WARNING),
                findings(run("rules-fda-sdtmig-3-3.json", "sdtmig 3-3", eg)));
    }


    @Test
    void sendigDart11ImplantationLabelIsAWarning() throws IOException
    {
        IDataTable ic = dataset("IC", "STUDYID", "USUBJID", "ICSEQ", "ICIMPLBL");
        assertEquals(Map.of("ICIMPLBL", Severity.WARNING),
                findings(run("rules-fda-sendig-dart-1-1.json", "sendig dart-1-1", ic)));
    }


    @Test
    void sdtmig34ReportsOnlyErrors() throws IOException
    {
        IDataTable ae = dataset("AE", "STUDYID", "USUBJID", "AESEQ", "AETERM", "AEBEATNO", "AEFOO");
        assertEquals(Map.of("AEFOO", Severity.ERROR),
                findings(run("rules-fda-sdtmig-3-4.json", "sdtmig 3-4", ae)));
    }


    @Test
    void aSponsorDefinedDomainResolvesNoModelListAndSkips() throws IOException
    {
        // §4b row 3 on real metadata: the model walk places a custom domain in no class, so the
        // model list is empty and the rule SKIPS — before the ladder as after it. The IG list is
        // empty too, so nothing here depends on which IG operation is bound.
        IDataTable xx = dataset("XX", "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD", "XXTEST",
                "XXORRES", "XXFOO");
        MetadataProvider sendig = provider("sendig 3-0");
        assertEquals(List.of(), sendig.getStandardModelVariables(xx, null));
        assertEquals(List.of(), sendig.getColumnOrder("XX"));
        RuleExecutionResult result = run("rules-fda-sendig-3-0.json", "sendig 3-0", xx);
        assertTrue(result.isSkipped(), "a custom domain resolves no model list: " + result);
        assertTrue(result.getViolations().isEmpty());
    }
}
