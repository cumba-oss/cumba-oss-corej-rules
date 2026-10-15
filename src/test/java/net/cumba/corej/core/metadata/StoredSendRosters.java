package net.cumba.corej.core.metadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;

/**
 * The dataset rosters of the IG products <b>as the store holds them</b> — before the base-IG view
 * ({@code PLAN-send-base-ig-view}) composes anything. A provider answers the composed product, so
 * the populations the view fills (the SEND datasets SENDIG-AR / -DART leave out) can only be
 * measured here.
 */
final class StoredSendRosters
{

    private static MetadataStore store;

    private StoredSendRosters()
    {
    }


    /** The stored product for {@code "sendig ar-1-0"}-style package names. */
    static StoredProduct product(String aPackage)
    {
        String[] parts = aPackage.split(" ", -1);
        return stored("standards/" + parts[0] + "/" + parts[1]);
    }


    /** Any stored product by its store key ({@code models/sdtm/2-0}). */
    static synchronized StoredProduct stored(String aKey)
    {
        if (store == null)
        {
            Path file = MetadataStoreFixture.storeFile().orElseThrow(
                    () -> new IllegalStateException("no metadata store (CDISC_PICKLE_CACHE_DIR)"));
            try
            {
                store = MetadataStore.open(file);
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
        }
        return store.product(aKey).orElseThrow(() -> new AssertionError("the store lacks " + aKey));
    }


    /** The dataset names the stored product prints. */
    static Set<String> datasetNames(String aPackage)
    {
        Set<String> out = new TreeSet<>();
        for (StoredClass klass : product(aPackage).classes())
        {
            for (StoredDataset ds : klass.datasets())
            {
                out.add(ds.name());
            }
        }
        return out;
    }


    /** The variables the stored product prints for {@code aDataset}, in its order. */
    static List<String> storedVariables(String aPackage, String aDataset)
    {
        List<String> out = new ArrayList<>();
        for (StoredClass klass : product(aPackage).classes())
        {
            for (StoredDataset ds : klass.datasets())
            {
                if (aDataset.equals(ds.name()))
                {
                    ds.variables().forEach(v -> out.add(v.name()));
                }
            }
        }
        return out;
    }


    /** The SENDIG 3.1.1 datasets the stored {@code aPackage} product does not print. */
    static Set<String> storedAbsentRelativeTo311(String aPackage)
    {
        Set<String> out = new TreeSet<>(datasetNames("sendig 3-1-1"));
        out.removeAll(datasetNames(aPackage));
        return out;
    }
}
