package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-send-base-ig-view} on the real metadata store: the base-IG view ({@code LVC-F1} (b))
 * for SENDIG-AR 1.0 / SENDIG-DART 1.1 / 1.2 and the model-dataset arm ({@code B-MD},
 * {@code B-MD-REACH} (b), {@code B-MD-WALK} (b)) for every shipped SDTMIG / SENDIG package — the
 * sensitivity arm the findings snapshot cannot be ({@code testdata/study} carries no SEND and no
 * model-only dataset).
 */
class BaseIgViewStoreTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    /** Run package → the base SENDIG its document names ({@code B-BASE}). */
    private static final Map<String, String> BASE = Map.of("sendig ar-1-0", "sendig 3-1",
            "sendig dart-1-1", "sendig 3-1", "sendig dart-1-2", "sendig 3-1-1");

    /**
     * The P0d census, per shipped package (with the view): the model datasets the run's IG lacks
     * whose class walk returns no row — unplaced, or placed in a special-purpose / trial-design /
     * relationship class with nothing to walk.
     */
    private static final Map<String, Set<String>> MODEL_ONLY = modelOnly();

    private static Map<String, Set<String>> modelOnly()
    {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put("sdtmig 3-1-2", Set.of());
        m.put("sdtmig 3-1-3", Set.of("TX", "POOLDEF"));
        m.put("sdtmig 3-2", Set.of("TX", "POOLDEF", "RELSUB", "APRELSUB"));
        m.put("sdtmig 3-3", Set.of("SJ", "TX", "TT", "TP", "POOLDEF", "DR", "DI", "APRELSUB"));
        m.put("sdtmig 3-4",
                Set.of("SJ", "TX", "TT", "TP", "AC", "DI", "POOLDEF", "DR", "APRELSUB"));
        m.put("sendig 3-0", Set.of("SV", "TV", "TI"));
        Set<String> send15 = Set.of("SV", "SM", "TV", "TI", "TD", "TM", "RELSUB", "APRELSUB");
        m.put("sendig 3-1", send15);
        m.put("sendig 3-1-1", send15);
        m.put("sendig genetox-1-0", send15);
        m.put("sendig ar-1-0", Set.of("SV", "SJ", "TT", "TP", "SM", "TV", "TI", "TD", "TM",
                "RELSUB", "DR", "DI", "OI", "APRELSUB"));
        m.put("sendig dart-1-1", send15);
        m.put("sendig dart-1-2", send15);
        return m;
    }


    private static MetadataProvider provider(String aPackage)
    {
        String[] parts = aPackage.split(" ", -1);
        StoreMetadataProviderFactory factory = MetadataStoreFixture.factory().orElseThrow(
                () -> new IllegalStateException("No metadata store: set CDISC_PICKLE_CACHE_DIR"));
        return factory.forSdtm(parts[0], parts[1], List.of()).orElseThrow();
    }


    private static Map<String, String> read(MetadataProvider aProvider, IDataTable aTable,
            String aVariable)
    {
        return ExprCompiler.libraryVariableMetadata(aProvider, aTable, NO_RESOLVER,
                aTable.getMetaData().getName(), aVariable);
    }


    /** One row carrying {@code aColumns}; {@code DOMAIN} holds the dataset's name. */
    private static IDataTable table(String aName, List<String> aColumns)
    {
        MockTable t = MockTable.of().name(aName);
        for (String column : aColumns)
        {
            t.col(column, "DOMAIN".equals(column) ? aName : "X");
        }
        return t.build();
    }

    // ------------------------------------------------------------------
    // 1. The base-IG view
    // ------------------------------------------------------------------


    @Test
    void aCarriedDomainAnswersTheBaseSendigsSpecification()
    {
        // AR / DART 1.1 carry LB / VS from SENDIG 3.1, DART 1.2 carries VS from SENDIG 3.1.1.
        Map<String, String> carried = Map.of("sendig ar-1-0", "LB", "sendig dart-1-1", "VS",
                "sendig dart-1-2", "VS");
        for (Map.Entry<String, String> e : carried.entrySet())
        {
            MetadataProvider run = provider(e.getKey());
            MetadataProvider base = provider(BASE.get(e.getKey()));
            String domain = e.getValue();
            String what = e.getKey() + " " + domain;
            assertFalse(run.isDomainCustom(domain), what);
            assertEquals(base.getDatasetMetadata(domain).get("label"),
                    run.getDatasetMetadata(domain).get("label"), what + " dataset label");
            // The walk lists the run's MODEL class variables around the IG's (AR names SDTM 1.8,
            // DART SDTM 1.6; the bases are SDTM 1.5): the base IG's own variables come in the base
            // IG's order, and every one reads the base IG's row.
            List<String> igVariables = storedVariables(BASE.get(e.getKey()), domain);
            List<String> order = run.getColumnOrder(domain);
            assertEquals(igVariables, order.stream().filter(igVariables::contains).toList(),
                    what + " IG order");
            for (String variable : igVariables)
            {
                Map<String, String> want = base.getVariableMetadata(domain, variable);
                Map<String, String> got = run.getVariableMetadata(domain, variable);
                for (String key : List.of("label", "core", "simpleDatatype", "role"))
                {
                    assertEquals(want.get(key), got.get(key), what + "." + variable + " " + key);
                }
                assertEquals(base.getCodelistCodeMap(domain, variable),
                        run.getCodelistCodeMap(domain, variable), what + "." + variable + " CT");
            }
        }
    }


    @Test
    void arReadsSendig31NotSendig311()
    {
        // B-BASE pinned where the two bases differ (P0c: PC / PP only, AR only).
        MetadataProvider ar = provider("sendig ar-1-0");
        assertFalse(storedVariables("sendig 3-1", "PC").contains("PCUSCHFL"));
        assertTrue(storedVariables("sendig 3-1-1", "PC").contains("PCUSCHFL"));
        assertEquals("Exp", ar.getVariableMetadata("PC", "PCDTC").get("core"));
        assertEquals("Perm",
                provider("sendig 3-1-1").getVariableMetadata("PC", "PCDTC").get("core"));
        assertEquals("Perm", ar.getVariableMetadata("PP", "PPTPTREF").get("core"));
        assertEquals("Exp",
                provider("sendig 3-1-1").getVariableMetadata("PP", "PPTPTREF").get("core"));
    }


    @Test
    void arReadsSuppqualFromTheBase()
    {
        // B-SUPP: SENDIG-AR prints no SUPPQUAL; every SUPP-- dataset of an AR run reads the
        // base's.
        MetadataProvider ar = provider("sendig ar-1-0");
        MetadataProvider base = provider("sendig 3-1");
        IDataTable suppbw = table("SUPPBW", base.getColumnOrder("SUPPQUAL"));
        int served = 0;
        for (String column : base.getColumnOrder("SUPPQUAL"))
        {
            Map<String, String> want = read(base, suppbw, column);
            assertFalse(want.isEmpty(), column);
            assertEquals(want, read(ar, suppbw, column), "SUPPBW." + column);
            served++;
        }
        assertTrue(served >= 10, "SUPPQUAL columns " + served);
        assertEquals(base.getDatasetMetadata("SUPPQUAL").get("label"),
                ar.getDatasetMetadata("SUPPQUAL").get("label"));
    }


    @Test
    void aDomainTheRunsIgPrintsKeepsItsOwnSpecification()
    {
        // B-GRAIN: DART revises BW (Repro Phase timing); the view never replaces it with the
        // base's.
        MetadataProvider dart = provider("sendig dart-1-1");
        List<String> dartBw = dart.getColumnOrder("BW");
        assertTrue(dartBw.contains("RPHASE"), "DART BW: " + dartBw);
        assertFalse(provider("sendig 3-1").getColumnOrder("BW").contains("RPHASE"));
        assertNotEquals(provider("sendig 3-1").getColumnOrder("BW"), dartBw);
        // AR prints DM: its own roster stays.
        List<String> stored = new ArrayList<>();
        for (StoredDataset ds : StoredSendRosters.product("sendig ar-1-0").classes().stream()
                .flatMap(c -> c.datasets().stream()).toList())
        {
            if ("DM".equals(ds.name()))
            {
                ds.variables().forEach(v -> stored.add(v.name()));
            }
        }
        assertTrue(provider("sendig ar-1-0").getColumnOrder("DM").containsAll(stored));
    }


    @Test
    void scopeIsExactlyTheThreeProducts()
    {
        // B-SCOPE: GeneTox 1.0 and SENDIG 3.0 get no base view — AR's AC never appears there and
        // SENDIG 3.0's IG-absent CV stays absent.
        assertEquals(List.of(), provider("sendig 3-0").getColumnOrder("CV"));
        assertEquals(Map.of(), provider("sendig genetox-1-0").getDatasetMetadata("AC"));
        for (String pkg : BASE.keySet())
        {
            assertEquals(Set.of(), absentRelativeToBase(pkg), pkg);
        }
    }


    /** The variables the stored product prints for {@code aDomain}, in its order. */
    private static List<String> storedVariables(String aPackage, String aDomain)
    {
        List<String> out = new ArrayList<>();
        for (StoredDataset ds : StoredSendRosters.product(aPackage).classes().stream()
                .flatMap(c -> c.datasets().stream()).toList())
        {
            if (aDomain.equals(ds.name()))
            {
                ds.variables().forEach(v -> out.add(v.name()));
            }
        }
        assertFalse(out.isEmpty(), aPackage + " prints " + aDomain);
        return out;
    }


    private static Set<String> absentRelativeToBase(String aPackage)
    {
        MetadataProvider run = provider(aPackage);
        Set<String> out = new TreeSet<>();
        for (String name : StoredSendRosters.datasetNames(BASE.get(aPackage)))
        {
            if (run.getDatasetMetadata(name).isEmpty())
            {
                out.add(name);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 2. The model-dataset arm (B-MD) — the P0d census, every shipped package
    // ------------------------------------------------------------------


    @Test
    void everyModelOnlyDatasetReadsItsModelDefinitionNameTypeAndRole()
    {
        int reads = 0;
        int armReads = 0;
        Map<String, Set<String>> measured = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : MODEL_ONLY.entrySet())
        {
            String pkg = e.getKey();
            MetadataProvider provider = provider(pkg);
            StoredProduct ig = StoredSendRosters.product(pkg);
            StoredProduct model = StoredSendRosters.stored(modelKey(ig));
            Set<String> population = new TreeSet<>();
            for (StoredDataset ds : model.datasets())
            {
                if (!provider.getDatasetMetadata(ds.name()).isEmpty()
                        || "SUPPQUAL".equals(ds.name()))
                {
                    continue; // the run's (composed) IG defines it
                }
                List<String> columns = ds.variables().stream().map(StoredVariable::name).toList();
                IDataTable t = table(ds.name(), columns);
                // The dataset's walk answers nothing, else the arm is not what answers it —
                // except an AP-- model dataset (APRELSUB), whose walk is its stripped parent's
                // (RELSUB) and which the arm reaches by its own name.
                if (!ds.name().startsWith("AP")
                        && !provider.getStandardVariablesDetailed(t, NO_RESOLVER).isEmpty())
                {
                    continue;
                }
                population.add(ds.name());
                for (StoredVariable v : ds.variables())
                {
                    // Tiers 1-3 are asked first and win where they answer: an AP-- dataset whose
                    // stripped parent the IG defines (APRELSUB -> RELSUB at SDTMIG 3.3 / 3.4)
                    // reads the parent's IG row through the AP shim; the arm answers the rest.
                    Map<String, String> tiers = provider.getVariableMetadata(ds.name(), v.name());
                    Map<String, String> expected = tiers.isEmpty() ? served(v) : tiers;
                    assertEquals(expected, read(provider, t, v.name()),
                            pkg + " " + ds.name() + "." + v.name());
                    reads++;
                    armReads += tiers.isEmpty() ? 1 : 0;
                }
                assertEquals(Map.of(), read(provider, table(ds.name(), List.of("ZZFOO")), "ZZFOO"),
                        pkg + " " + ds.name() + ": a variable the model does not define");
            }
            measured.put(pkg, population);
        }
        Map<String, Set<String>> expected = new TreeMap<>();
        MODEL_ONLY.forEach((k, v) -> expected.put(k, new TreeSet<>(v)));
        assertEquals(expected, measured, "the P0d census");
        // SDTMIG 3.1.3 11, 3.2 22, 3.3 55, 3.4 67; SENDIG 3.0 28, 3.1 / 3.1.1 / GeneTox / DART 1.1
        // /
        // DART 1.2 62 each, AR 1.0 107.
        assertEquals(600, reads, "model reads");
        System.out.println("[BaseIgViewStoreTest] model-arm reads " + armReads + " of " + reads);
        // The 10 others: APRELSUB at SDTMIG 3.3 / 3.4, all five columns via the AP shim.
        assertEquals(590, armReads, "model-arm reads");
    }


    @Test
    void theSdtm14ModelDefinesNoDeviceOrStudyReferenceDatasetSoSdtmig32ReadsNothing()
    {
        // §8 named gap: SDTMIG 3.2's model (SDTM 1.4) defines none of DI DR OI RELSPEC.
        MetadataProvider p = provider("sdtmig 3-2");
        IDataTable di = table("DI", List.of("STUDYID", "DOMAIN", "DIPARMCD", "DIVAL"));
        for (String column : List.of("STUDYID", "DOMAIN", "DIPARMCD", "DIVAL"))
        {
            assertEquals(Map.of(), read(p, di, column), "DI." + column);
        }
    }


    /** The served row: name, type, role, absent or empty values omitted (LVC-D1 (a)). */
    private static Map<String, String> served(StoredVariable aVariable)
    {
        Map<String, String> out = new LinkedHashMap<>();
        if (aVariable.name() != null && !aVariable.name().isEmpty())
        {
            out.put("name", aVariable.name());
        }
        if (aVariable.simpleDatatype() != null && !aVariable.simpleDatatype().isEmpty())
        {
            out.put("simpleDatatype", aVariable.simpleDatatype());
        }
        if (aVariable.role() != null && !aVariable.role().isEmpty())
        {
            out.put("role", aVariable.role());
        }
        return out;
    }


    private static String modelKey(StoredProduct aIg)
    {
        String[] parts = aIg.modelHref().split("/", -1);
        String version = parts[3].startsWith(parts[2] + "-")
                ? parts[3].substring(parts[2].length() + 1)
                : parts[3];
        return "models/" + parts[2] + "/" + version.replace('.', '-');
    }
}
