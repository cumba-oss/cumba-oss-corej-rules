package net.cumba.corej.core.rulespec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.cumba.corej.core.metadata.pickle.LocalPickleSource;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.metadata.store.seed.PickleStoreSeeder;
import net.cumba.corej.core.metadata.store.seed.StoreSeedOptions;
import org.jspecify.annotations.Nullable;

/**
 * The unified metadata store this module's tests read library metadata from — seeded ONCE per JVM
 * from the Python-engine pickle corpus located by {@link PickleCacheLocator} (cache plan step 8g:
 * this module no longer reads pickles directly; {@code PickleStoreSeeder} is the one remaining
 * pickle consumer, and it runs here, at the fixture boundary).
 *
 * <p>
 * Gating is unchanged from the pickle era: when {@link PickleCacheLocator} resolves no directory,
 * {@link #storeFile()} / {@link #factory()} answer empty and callers keep their existing
 * disposition — {@code assumeTrue} skips for the cache-gated tests, a loud failure for
 * {@code LibraryProviderGuardTest}. A directory that resolves but cannot be seeded is an
 * {@link UncheckedIOException}, never a silent skip: a half-readable corpus and an absent one must
 * not look alike in a test log.
 * </p>
 *
 * <p>
 * The store is written to {@code target/metadata-store/store.zip}. A second JVM (another Maven
 * invocation, a rulespec lane after the corpus gate) finds the previous seed and re-seeds it
 * incrementally — plan §5.2 carries already-present CT packages forward without re-reading their
 * pickles, so only the first seed of a clean tree pays for the full corpus read. {@code mvn clean}
 * discards it.
 * </p>
 */
public final class MetadataStoreFixture
{

    /** Seed target, relative to the module root (the surefire working directory). */
    private static final Path STORE_FILE = Path.of("target", "metadata-store", "store.zip")
            .toAbsolutePath();

    private static boolean initialised;

    private static @Nullable Path storeFile;

    private static @Nullable StoreMetadataProviderFactory factory;

    private MetadataStoreFixture()
    {
    }


    /**
     * The seeded store file.
     *
     * @return the store zip, or empty when {@link PickleCacheLocator} resolves no pickle corpus to
     *         seed from (callers then skip or fail exactly as they did when the pickle cache itself
     *         was absent)
     */
    public static synchronized Optional<Path> storeFile()
    {
        init();
        return Optional.ofNullable(storeFile);
    }


    /**
     * A factory over the seeded store, opened once and shared — the store is read-only after
     * seeding, so one instance serves every test in the JVM.
     *
     * @return the factory, or empty when no pickle corpus resolved (see {@link #storeFile()})
     */
    public static synchronized Optional<StoreMetadataProviderFactory> factory()
    {
        init();
        return Optional.ofNullable(factory);
    }


    private static void init()
    {
        if (initialised)
        {
            return;
        }
        initialised = true;
        Optional<Path> pickles = PickleCacheLocator.locate();
        if (pickles.isEmpty())
        {
            return;
        }
        try
        {
            Files.createDirectories(STORE_FILE.getParent());
            new PickleStoreSeeder(new LocalPickleSource(pickles.get()))
                    .seed(StoreSeedOptions.of(STORE_FILE));
            factory = StoreMetadataProviderFactory.open(STORE_FILE);
            storeFile = STORE_FILE;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("The pickle corpus at " + pickles.get() + " resolved "
                    + "but could not be seeded into a metadata store at " + STORE_FILE + " — "
                    + "failing loudly rather than skipping, because a broken corpus and an "
                    + "absent one must not produce the same green run.", e);
        }
    }
}
