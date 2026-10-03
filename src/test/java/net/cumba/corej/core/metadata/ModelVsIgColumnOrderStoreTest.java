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
 * reports an IG-only variable at WARNING and a variable in neither list at ERROR; a sponsor-defined
 * domain is judged by the model list of the class the scope matcher gives it
 * ({@code PLAN-custom-domain-model-walk}), and one no class tier places SKIPS.</li>
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
     *
     * <p>
     * 2026-10-03 store format 5: the errata register adds the printed domain-specific variable
     * tables to SDTM 1.5-1.8 (metadata issue {@code #283}.1-283.4) as class-table roots restricted
     * to their domain, and the walk honours that restriction. That emptied the SDTMIG 3.3 roster
     * (SDTM 1.7: EGBEATNO, MHEVDTYP, MSAGENT, MSCONC, MSCONCU), and took EXMETHOD out of SENDIG 3.1
     * / 3.1.1 (SDTM 1.5) and EXMETHOD and ICIMPLBL out of SENDIG-DART 1.1 (SDTM 1.6). SENDIG 3.0
     * links SDTM 1.2, which prints no such table, so its EXMETHOD stays.
     * </p>
     */
    private static final Map<String, Map<String, Set<String>>> SKEW = skewRoster();

    private static Map<String, Map<String, Set<String>>> skewRoster()
    {
        Map<String, Map<String, Set<String>>> skew = new LinkedHashMap<>();
        skew.put("sdtmig 3-1-2", Map.of());
        skew.put("sdtmig 3-1-3", Map.of());
        skew.put("sdtmig 3-2", Map.of());
        skew.put("sdtmig 3-3", Map.of());
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
        skew.put("sendig 3-1", Map.of("CO", Set.of("CODY")));
        skew.put("sendig 3-1-1", Map.of("CO", Set.of("CODY")));
        skew.put("sendig dart-1-1", Map.of("CO", Set.of("CODY", "CORPDY", "RPHASE")));
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
    void sdtmig34HasNoSkewAndAnAe112NameModelList()
    {
        // The plan's phase-0 control, through the real walk: AE / EVENTS / SDTMIG 3.4 is the list
        // rulespec/specs/FDA-SD0058.yaml pins as (112 names).
        // 2026-10-03 store format 5: 120 -> 112. The walk honours SDTM 2.0's usageRestrictions:
        // --BEATNO (EG only), --EVDTYP (MH only), --OCCUR / --REASOC / --STAT / --REASND (not in
        // AE) and --PTFL / --PDUR (specimen-based Findings domains only) leave the AE walk.
        List<String> ae = provider("sdtmig 3-4").getStandardModelVariables(dataset("AE"), null);
        assertNotNull(ae);
        assertEquals(112, new TreeSet<>(ae).size());
        assertTrue(ae.containsAll(List.of("STUDYID", "USUBJID", "AESEQ", "AETERM", "AESINTV")));
        for (String gone : List.of("AEBEATNO", "AEEVDTYP", "AEOCCUR", "AEREASOC", "AESTAT",
                "AEREASND", "AEPTFL", "AEPDUR"))
        {
            assertFalse(ae.contains(gone), gone + " is restricted out of the AE walk");
        }
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
    void sdtmig33BeatNumberIsAModelVariableOfEg() throws IOException
    {
        // 2026-10-03 store format 5: EGBEATNO used to be IG-only at SDTMIG 3.3 (WARNING). SDTM 1.7
        // prints it in Table 2.2.12.1 Domain-Specific Variables (metadata issue #283.3), so it is
        // now in both lists and silent; EGFOO keeps the rule's ERROR rung in view.
        IDataTable eg = dataset("EG", "STUDYID", "USUBJID", "EGSEQ", "EGTESTCD", "EGTEST",
                "EGORRES", "EGBEATNO", "EGFOO");
        assertEquals(Map.of("EGFOO", Severity.ERROR),
                findings(run("rules-fda-sdtmig-3-3.json", "sdtmig 3-3", eg)));
    }


    @Test
    void sendigDart11ImplantationLabelIsAModelVariableOfIc() throws IOException
    {
        // 2026-10-03 store format 5: ICIMPLBL used to be IG-only at SENDIG-DART 1.1 (WARNING).
        // SDTM 1.6, which DART 1.1 links, prints it in Table 2.2.12 Domain-Specific Variables
        // (metadata issue #283.2), so it is now in both lists and silent.
        IDataTable ic = dataset("IC", "STUDYID", "USUBJID", "ICSEQ", "ICIMPLBL", "ICFOO");
        assertEquals(Map.of("ICFOO", Severity.ERROR),
                findings(run("rules-fda-sendig-dart-1-1.json", "sendig dart-1-1", ic)));
    }


    @Test
    void sdtmig34ReportsOnlyErrors() throws IOException
    {
        // 2026-10-03 store format 5: SDTM 2.0 restricts --BEATNO to EG ("EG Domain only") and the
        // walk now honours it, so AEBEATNO is in neither list and an ERROR like AEFOO.
        IDataTable ae = dataset("AE", "STUDYID", "USUBJID", "AESEQ", "AETERM", "AEBEATNO", "AEFOO");
        assertEquals(Map.of("AEBEATNO", Severity.ERROR, "AEFOO", Severity.ERROR),
                findings(run("rules-fda-sdtmig-3-4.json", "sdtmig 3-4", ae)));
    }


    @Test
    void aSponsorDefinedDomainIsJudgedByItsSniffedClass() throws IOException
    {
        // PLAN-custom-domain-model-walk: the walk places a custom domain in the class the scope
        // matcher sniffs from its own columns (FINDINGS here), so the model list is that class's.
        // A custom domain has no IG dataset, so the IG list is the same walk: the sponsor variable
        // is in neither list (ERROR), every other column is in both (silent).
        IDataTable xx = dataset("XX", "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD", "XXTEST",
                "XXORRES", "XXFOO");
        MetadataProvider sendig = provider("sendig 3-0");
        List<String> model = sendig.getStandardModelVariables(xx, null);
        assertNotNull(model);
        assertTrue(
                model.containsAll(
                        List.of("STUDYID", "USUBJID", "XXSEQ", "XXTESTCD", "XXTEST", "XXORRES")),
                "the FINDINGS model walk under the XX prefix: " + model);
        assertEquals(List.of(), sendig.getColumnOrder("XX"),
                "the domain-keyed IG order stays unserved (CDW-D2 (b))");
        assertEquals(Map.of("XXFOO", Severity.ERROR),
                findings(run("rules-fda-sendig-3-0.json", "sendig 3-0", xx)));
    }


    @Test
    void aSponsorDefinedDomainNoClassTierPlacesSkips() throws IOException
    {
        // No topic variable: no tier resolves a class, the model list is empty and the rule SKIPS
        // — before the fix as after it (CDW-D3 (b)).
        IDataTable xx = dataset("XX", "STUDYID", "USUBJID", "XXSEQ", "XXVAL", "XXFOO");
        MetadataProvider sendig = provider("sendig 3-0");
        assertEquals(List.of(), sendig.getStandardModelVariables(xx, null));
        RuleExecutionResult result = run("rules-fda-sendig-3-0.json", "sendig 3-0", xx);
        assertTrue(result.isSkipped(), "no class tier places the domain: " + result);
        assertTrue(result.getViolations().isEmpty());
    }
}
