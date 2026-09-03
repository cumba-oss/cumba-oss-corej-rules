package net.cumba.corej.core.metadata.pickle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.rulespec.PickleCacheLocator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the pickle metadata reader against a real Python-engine pickle cache, located by
 * {@link PickleCacheLocator}. All tests {@code assumeTrue} the cache is present so the suite stays
 * green when no cache directory resolves.
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


    @Test
    void buildsTypedSdtmProductsFromPickle()
    {
        PickleProductSource source = new PickleProductSource(cache);
        Optional<SdtmProduct> ig = source.igSdtm("sdtmig", "3-4");
        assertTrue(ig.isPresent(), "IG product built");
        assertFalse(ig.get().classes().isEmpty(), "IG product has classes");

        Optional<SdtmProduct> model = source.sdtmModelFor(ig.get());
        assertTrue(model.isPresent(), "model product resolved from IG _links.model");
        assertFalse(model.get().classes().isEmpty(), "model product has classes");
    }


    @Test
    void emptyCtPackageIsUsable()
    {
        PickleProductSource source = new PickleProductSource(cache);
        CtPackage empty = source.emptyCtPackage();
        assertTrue(empty.codelists().isEmpty(), "empty CT package has no codelists");
    }


    @Test
    void factoryBuildsProviderForSdtmig()
    {
        Optional<MetadataProvider> provider = PickleMetadataProviderFactory.open(CACHE_DIR)
                .forSdtm("sdtmig", "3-4", null);
        assertTrue(provider.isPresent(), "provider built from pickle cache");
    }


    @Test
    void factoryReturnsEmptyForUnknownStandard()
    {
        Optional<MetadataProvider> provider = PickleMetadataProviderFactory.open(CACHE_DIR)
                .forSdtm("sdtmig", "9-9", null);
        assertTrue(provider.isEmpty(), "unknown standard → empty (graceful degrade)");
    }
}
