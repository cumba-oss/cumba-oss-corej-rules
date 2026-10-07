package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.PermittedColumnOrder;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code get_permitted_column_order()} over the <b>real</b> store
 * ({@code PLAN-permitted-addition-position} C12): every provider is built by the production factory
 * ({@link StoreMetadataProviderFactory#forSdtm}), as a CLI or data-browser run builds it — the
 * {@code .cdt} scenarios hand-author their inputs and execute none of
 * {@link MetadataLibraryProvider}.
 *
 * <ol>
 * <li><b>The placement roster</b> — for every special-purpose dataset (the class gate's population:
 * DM, CO, SE, SV, SM where the version has it, SJ in SENDIG-DART / -AR) of every stored SDTMIG and
 * SENDIG version, the reference for a dataset carrying its model order plus every Findings-walk
 * variable, so every placement is exercised. Pinned as an <b>exact</b> roster in
 * {@code permitted-column-order-roster.tsv}, produced by an independent model of the plan's §2.3
 * (not by the engine): a disagreement is a defect in one of the two, never a rebaseline. Regenerate
 * it in the internal corpus with {@code scripts/gen-permitted-order-roster.sh}
 * ({@code PermittedOrderStoreProbe} dumps the provider's inputs,
 * {@code scripts/permitted-order-oracle.py} places them) and review the diff; the OSS twin receives
 * the file, never the tooling. ⚠ The model is a second implementation of the SAME reading of §2.3,
 * fed from the SAME provider seams: it catches coding errors, not a misread plan, IG or store.</li>
 * <li><b>CO, pinned by hand</b> — its model order is not in role order (M1).</li>
 * <li><b>The gate arm</b> (P5) — every other dataset of every version answers its model order
 * unchanged, whatever columns it carries: the class gate alone keeps the Findings role tables off
 * the general-class datasets.</li>
 * </ol>
 *
 * <p>
 * ⛔ No assumption skips: without a pickle corpus the store cannot be seeded and every test here
 * FAILS, naming the variable to set.
 * </p>
 */
class PermittedColumnOrderStoreTest
{

    private static final List<String> PACKAGES = List.of("sdtmig 3-1-2", "sdtmig 3-1-3",
            "sdtmig 3-2", "sdtmig 3-3", "sdtmig 3-4", "sendig 3-0", "sendig 3-1", "sendig 3-1-1",
            "sendig dart-1-1", "sendig ar-1-0", "sendig genetox-1-0");

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    /**
     * The class gate's population BY NAME, per stored version (owner N3 (a), phase 0 step 0.5): the
     * special-purpose datasets — SM only from SDTMIG 3.3, SJ only in SENDIG-DART / -AR (SDTMIG 3.3
     * defines an SJ that is not special-purpose). The gate arm asserts the product class agrees
     * with this list for every dataset and skips exactly these, so a dataset moving in or out of
     * the class reds here rather than silently changing which datasets the arm covers.
     */
    private static java.util.Set<String> gatePopulation(String aPackage)
    {
        java.util.Set<String> base = new java.util.TreeSet<>(List.of("DM", "CO", "SE", "SV"));
        if ("sdtmig 3-3".equals(aPackage) || "sdtmig 3-4".equals(aPackage))
        {
            base.add("SM");
        }
        if ("sendig dart-1-1".equals(aPackage) || "sendig ar-1-0".equals(aPackage))
        {
            base.add("SJ");
        }
        return base;
    }


    private static StoreMetadataProviderFactory factory()
    {
        return MetadataStoreFixture.factory().orElseThrow(() -> new IllegalStateException(
                "No metadata store: set CDISC_PICKLE_CACHE_DIR (or -Dcdisc.pickle.cache.dir) to a"
                        + " pickle cache directory — MetadataStoreFixture seeds the store from it."));
    }


    private static MetadataProvider provider(String aPackage)
    {
        String[] parts = aPackage.split(" ", -1);
        return factory().forSdtm(parts[0], parts[1], List.of()).orElseThrow(
                () -> new AssertionError("the seeded store lacks the IG product " + aPackage));
    }


    private static IDataTable dataset(String aName, List<String> aColumns)
    {
        MockTable t = MockTable.of().name(aName);
        if (!aColumns.contains("DOMAIN"))
        {
            t.col("DOMAIN", aName);
        }
        for (String column : aColumns)
        {
            t.col(column, "DOMAIN".equals(column) ? aName : "X");
        }
        return t.build();
    }


    /** The model order plus every Findings-walk variable, de-duplicated, model order first. */
    private static List<String> withEveryFindingsVariable(MetadataProvider aProvider, String aName)
    {
        IDataTable probe = dataset(aName, List.of());
        List<String> columns = new ArrayList<>(
                Objects.requireNonNull(aProvider.getStandardModelVariables(probe, NO_RESOLVER)));
        List<Map<String, String>> findings = aProvider.getStandardModelVariablesForClass(probe,
                NO_RESOLVER, "FINDINGS");
        for (Map<String, String> row : Objects.requireNonNull(findings, aName))
        {
            String name = row.get("name");
            if (name != null && !columns.contains(name))
            {
                columns.add(name);
            }
        }
        return columns;
    }


    private static boolean specialPurpose(MetadataProvider aProvider, String aName)
    {
        String c = SdtmObservationClasses.normalise(aProvider.getDatasetClass(null, aName));
        return "SPECIAL PURPOSE".equals(c) || "SPECIAL-PURPOSE".equals(c);
    }


    private static Map<String, Map<String, List<String>>> roster() throws IOException
    {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        try (InputStream in = Objects.requireNonNull(PermittedColumnOrderStoreTest.class
                .getResourceAsStream("permitted-column-order-roster.tsv"), "roster resource"))
        {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n",
                    -1))
            {
                if (line.isBlank() || line.startsWith("#"))
                {
                    continue;
                }
                String[] f = line.split("\t", -1);
                out.computeIfAbsent(f[0], k -> new TreeMap<>()).put(f[1],
                        List.of(f[2].split(" ", -1)));
            }
        }
        return out;
    }


    @Test
    void everySpecialPurposeDatasetOfEveryVersionMatchesTheIndependentRoster() throws IOException
    {
        Map<String, Map<String, List<String>>> expected = roster();
        Map<String, Map<String, List<String>>> actual = new LinkedHashMap<>();
        for (String pkg : PACKAGES)
        {
            MetadataProvider provider = provider(pkg);
            for (String name : Objects.requireNonNull(provider.getStandardDatasetNames()))
            {
                if (!specialPurpose(provider, name))
                {
                    continue;
                }
                List<String> reference = PermittedColumnOrder.resolve(provider,
                        dataset(name, withEveryFindingsVariable(provider, name)), NO_RESOLVER);
                actual.computeIfAbsent(pkg, k -> new TreeMap<>()).put(name, reference);
            }
        }
        assertEquals(expected.keySet(), actual.keySet(), "the versions");
        for (String pkg : expected.keySet())
        {
            assertEquals(expected.get(pkg).keySet(), actual.get(pkg).keySet(),
                    pkg + ": the special-purpose datasets (N3: SM in SDTMIG 3.3 / 3.4)");
            for (Map.Entry<String, List<String>> e : expected.get(pkg).entrySet())
            {
                assertEquals(e.getValue(), actual.get(pkg).get(e.getKey()), pkg + " " + e.getKey());
            }
        }
        assertEquals(48, expected.values().stream().mapToInt(Map::size).sum(),
                "the roster's population");
    }


    /** M1: CO 3.4 is not in role order; each addition still lands by its rank's anchors. */
    @Test
    void coAtSdtmig34IsPinnedByHand()
    {
        MetadataProvider provider = provider("sdtmig 3-4");
        assertEquals(
                List.of("STUDYID", "DOMAIN", "RDOMAIN", "USUBJID", "POOLID", "SPDEVID", "COSEQ",
                        "COGRPID", "IDVAR", "IDVARVAL", "COREF", "COVAL", "COEVAL", "COEVALID",
                        "VISITNUM", "CODTC", "CODY"),
                PermittedColumnOrder.resolve(provider,
                        dataset("CO", List.of("STUDYID", "COSEQ", "CODY", "VISITNUM", "COGRPID")),
                        NO_RESOLVER));
    }


    /**
     * P2 refined at phase 0: at SDTMIG 3.4 the Findings walk omits {@code SVSTDTC}; the PhUSE pilot
     * SV order ({@code EPOCH} between {@code VISITDY} and {@code SVSTDTC}) is an ordered subset of
     * the reference, so the five rules raise no false finding there.
     */
    @Test
    void thePhuseSvOrderIsPermittedAtSdtmig34()
    {
        MetadataProvider provider = provider("sdtmig 3-4");
        List<String> phuse = List.of("STUDYID", "DOMAIN", "USUBJID", "VISITNUM", "VISIT", "VISITDY",
                "EPOCH", "SVSTDTC", "SVENDTC", "SVSTDY", "SVENDY", "SVUPDES");
        List<Map<String, String>> findings = provider
                .getStandardModelVariablesForClass(dataset("SV", phuse), NO_RESOLVER, "FINDINGS");
        assertFalse(
                Objects.requireNonNull(findings).stream()
                        .anyMatch(r -> "SVSTDTC".equals(r.get("name"))),
                "the premise: the 3.4 Findings walk lists no SVSTDTC");
        List<String> reference = Objects.requireNonNull(
                PermittedColumnOrder.resolve(provider, dataset("SV", phuse), NO_RESOLVER));
        int at = 0;
        for (String column : phuse)
        {
            int found = reference.subList(at, reference.size()).indexOf(column);
            assertTrue(found >= 0, column + " out of order in " + reference);
            at += found + 1;
        }
    }


    /** P5: the class gate keeps every other dataset on its model order, whatever it carries. */
    @Test
    void everyOtherDatasetAnswersItsModelOrderUnchanged()
    {
        int checked = 0;
        for (String pkg : PACKAGES)
        {
            MetadataProvider provider = provider(pkg);
            for (String name : Objects.requireNonNull(provider.getStandardDatasetNames()))
            {
                boolean listed = gatePopulation(pkg).contains(name);
                assertEquals(listed, specialPurpose(provider, name),
                        pkg + " " + name + ": the class gate's population, by name");
                if (listed)
                {
                    continue;
                }
                IDataTable probe = dataset(name, List.of());
                List<String> model = provider.getStandardModelVariables(probe, NO_RESOLVER);
                if (model == null || model.isEmpty())
                {
                    continue; // SKIPs with get_model_column_order (P4) — pinned in the engine
                }
                List<Map<String, String>> findings = provider
                        .getStandardModelVariablesForClass(probe, NO_RESOLVER, "FINDINGS");
                List<String> columns = new ArrayList<>(model);
                if (findings != null)
                {
                    findings.stream().map(r -> r.get("name")).filter(Objects::nonNull)
                            .filter(n -> !columns.contains(n)).forEach(columns::add);
                }
                assertEquals(model,
                        PermittedColumnOrder.resolve(provider, dataset(name, columns), NO_RESOLVER),
                        pkg + " " + name);
                checked++;
            }
        }
        assertTrue(checked > 300, "the gate arm must cover the general-class datasets: " + checked);
    }
}
