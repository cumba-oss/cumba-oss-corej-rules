package net.cumba.corej.core.metadata.pickle;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import net.cumba.corej.core.rulespec.PickleCacheLocator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the pickle metadata reader against a real Python-engine pickle cache, located by
 * {@link PickleCacheLocator}. All tests {@code assumeTrue} the cache is present so the suite stays
 * green when no cache directory resolves.
 *
 * <p>
 * Since cache plan step 8g the reader's one production consumer is {@code PickleStoreSeeder} —
 * these tests pin the seed substrate. Provider-level reads of the corpus live in
 * {@code MetadataStoreFixtureTest} and {@code MetadataLibraryProviderStoreParityTest}, on the store
 * the fixture seeds; the typed pickle views ({@code PickleProductSource}) and the pickle-backed
 * provider factory they used to exercise here are deleted.
 * </p>
 */
class PickleMetadataReaderTest
{

    private static final @Nullable Path CACHE_DIR = PickleCacheLocator.locate().orElse(null);

    private PickleCache cache;

    @BeforeEach
    void setUp()
    {
        assumeTrue(CACHE_DIR != null, "no pickle cache directory resolved");
        cache = PickleCache.open(CACHE_DIR);
    }


    @Test
    void decodesModelEntry()
    {
        Optional<Map<String, Object>> model = cache.get("models/sdtm/2-0");
        assumeTrue(model.isPresent(), "models/sdtm/2-0 not in cache");
        assertTrue(model.get().containsKey("classes"), "model has classes");
    }


    @Test
    void decodesIgStandardEntry()
    {
        Optional<Map<String, Object>> ig = cache.get("standards/sdtmig/3-4");
        assertTrue(ig.isPresent(), "standards/sdtmig/3-4 present");
        assertTrue(ig.get().containsKey("_links"), "IG has _links");
    }


    @Test
    void missingKeyAndMissingFileYieldEmpty()
    {
        assertTrue(cache.get("models/sdtm/9-9").isEmpty(), "unknown model key → empty");
        assertTrue(cache.get("library_variables_metadata/nope/0").isEmpty(),
                "unknown var key → empty");
        assertTrue(cache.getCtPackage("no-such-package").isEmpty(), "unknown CT package → empty");
        assertTrue(cache.get("totally/unrouted/key").isEmpty(), "unrouted prefix → empty");
    }

}
