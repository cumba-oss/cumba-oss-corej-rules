package net.cumba.cdisc.core.metadata;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.metadata.pickle.PickleMetadataProviderFactory;
import net.cumba.cdisc.core.rulespec.PickleCacheLocator;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parity tests for {@link MetadataLibraryProvider}'s algorithm-A (pure Model walk,
 * {@code get_variables_metadata_from_standard_model}) and algorithm-B (IG-base + Model-merge,
 * {@code get_variables_metadata_from_standard}) resolvers, exercised against the real Python-engine
 * pickle cache located by {@link PickleCacheLocator}.
 *
 * <p>
 * Every test {@code assumeTrue}s the cache is present so the suite stays green when no cache
 * directory resolves. The provider is built via {@link PickleMetadataProviderFactory}, which wires
 * the SDTM-IG product <em>and</em> the linked SDTM Model product — so the Model-sourced base walk
 * is exercised end-to-end.
 * </p>
 */
class MetadataLibraryProviderPickleParityTest
{

    private static final @Nullable Path CACHE_DIR = PickleCacheLocator.locate().orElse(null);

    private MetadataProvider provider;

    @BeforeEach
    void setUp()
    {
        assumeTrue(CACHE_DIR != null, "no pickle cache directory resolved");
        Optional<MetadataProvider> p = PickleMetadataProviderFactory.open(CACHE_DIR)
                .forSdtm("sdtmig", "3-4", null);
        assumeTrue(p.isPresent(), "sdtmig 3-4 provider not built from pickle cache");
        provider = p.get();
    }


    /**
     * Mock table whose CDISC domain resolves to {@code aName} (no DOMAIN column, falls to name).
     */
    private static IDataTable mockTable(String aName)
    {
        IDataTable table = mock(IDataTable.class);
        DataTableMeta meta = mock(DataTableMeta.class);
        lenient().when(meta.getName()).thenReturn(aName);
        lenient().when(meta.getColumnIndex("DOMAIN")).thenReturn(-1);
        lenient().when(table.getMetaData()).thenReturn(meta);
        lenient().when(table.getRowCount()).thenReturn(0L);
        return table;
    }

    // ------------------------------------------------------------------
    // Algorithm A — getStandardModelVariables (pure Model walk)
    // ------------------------------------------------------------------


    @Test
    void algorithmA_ae_includesTimingAndModelVariables()
    {
        List<String> vars = provider.getStandardModelVariables(mockTable("AE"), null);
        assertNotNull(vars, "AE algorithm-A resolves (products configured)");
        assertFalse(vars.isEmpty(), "AE algorithm-A is non-empty");
        // EVENTS is detectable: the Model walk contributes GENERAL OBSERVATIONS timing variables
        // and the EVENTS class-vars that the IG dataset omits. These are the load-bearing parity
        // members that the old IG-only behaviour dropped.
        assertTrue(vars.contains("AETPT"), "AE algorithm-A includes timing var AETPT");
        assertTrue(vars.contains("VISIT"), "AE algorithm-A includes timing var VISIT");
        assertTrue(vars.contains("AEOCCUR"), "AE algorithm-A includes EVENTS class var AEOCCUR");
        assertTrue(vars.contains("STUDYID"), "AE algorithm-A includes STUDYID");
        assertTrue(vars.contains("USUBJID"), "AE algorithm-A includes USUBJID");
        // The pure-Model EVENTS set is materially larger than the ~60-var IG AE dataset.
        assertTrue(vars.size() > 90,
                "AE algorithm-A is the broad Model set, not the IG dataset (size=" + vars.size()
                        + ")");
    }


    @Test
    void algorithmA_detailed_carriesAttributes()
    {
        List<Map<String, String>> detailed = provider
                .getStandardModelVariablesDetailed(mockTable("AE"), null);
        assertNotNull(detailed, "AE algorithm-A detailed resolves");
        assertFalse(detailed.isEmpty(), "AE algorithm-A detailed is non-empty");
        Map<String, String> aetpt = detailed.stream().filter(m -> "AETPT".equals(m.get("name")))
                .findFirst().orElse(null);
        assertNotNull(aetpt, "AETPT present in detailed output");
        assertTrue("Timing".equalsIgnoreCase(aetpt.getOrDefault("role", "")),
                "AETPT carries a Timing role (got " + aetpt.get("role") + ")");
    }

    // ------------------------------------------------------------------
    // Algorithm B — getColumnOrder / getStandardVariablesDetailed (IG-base + Model-merge)
    // ------------------------------------------------------------------


    @Test
    void algorithmB_ae_columnOrderIsMergedSet()
    {
        List<String> order = provider.getColumnOrder("AE");
        assertFalse(order.isEmpty(), "AE algorithm-B column order is non-empty");
        // The merged set keeps the Model timing/identifier breadth (IG overwrites in place but
        // does not shrink), so timing vars survive into the column order.
        assertTrue(order.contains("AETPT"), "AE algorithm-B column order includes AETPT");
        assertTrue(order.contains("VISIT"), "AE algorithm-B column order includes VISIT");
        assertTrue(order.contains("STUDYID"), "AE algorithm-B column order includes STUDYID");
        assertTrue(order.indexOf("STUDYID") < order.indexOf("AETERM"),
                "identifiers precede topic vars in merged order");
    }


    @Test
    void algorithmB_detailed_carriesAttributes()
    {
        List<Map<String, String>> detailed = provider.getStandardVariablesDetailed(mockTable("AE"),
                null);
        assertNotNull(detailed, "AE algorithm-B detailed resolves");
        assertFalse(detailed.isEmpty(), "AE algorithm-B detailed is non-empty");
        assertTrue(detailed.stream().anyMatch(m -> "AETERM".equals(m.get("name"))),
                "AETERM present in algorithm-B detailed output");
    }


    @Test
    void algorithmB_requiredAndExpected_filterByCore()
    {
        List<String> required = provider.getRequiredVariables("AE");
        List<String> expected = provider.getExpectedVariables("AE");
        assertFalse(required.isEmpty(), "AE required (core=Req) is non-empty");
        assertTrue(required.contains("STUDYID"), "STUDYID is required");
        assertTrue(required.contains("USUBJID"), "USUBJID is required");
        assertTrue(expected.containsAll(required),
                "expected (Req|Exp) is a superset of required (Req)");
        assertTrue(expected.size() >= required.size(), "expected >= required");
    }

    // ------------------------------------------------------------------
    // Non-detectable / FINDINGS ABOUT / AP — resolve without error
    // ------------------------------------------------------------------


    @Test
    void nonDetectableDm_resolvesUnderBothAlgorithms()
    {
        // DM is SPECIAL PURPOSE (non-detectable). Algorithm A falls Model class-vars (empty) →
        // Model domain datasetVariables; algorithm B returns the IG dataset variables. Both must
        // resolve a non-empty list including the core identifiers.
        List<String> a = provider.getStandardModelVariables(mockTable("DM"), null);
        List<String> b = provider.getColumnOrder("DM");
        assertNotNull(a, "DM algorithm-A resolves");
        assertFalse(a.isEmpty(), "DM algorithm-A is non-empty");
        assertTrue(a.contains("STUDYID") && a.contains("USUBJID"),
                "DM algorithm-A includes core identifiers");
        assertFalse(b.isEmpty(), "DM algorithm-B is non-empty");
        assertTrue(b.contains("STUDYID") && b.contains("USUBJID"),
                "DM algorithm-B includes core identifiers");
    }


    @Test
    void findingsAboutFa_resolvesUnderBothAlgorithms()
    {
        // FA is FINDINGS ABOUT (detectable). The FINDINGS-around-TEST merge must produce a
        // non-empty list under both algorithms without throwing.
        List<String> a = provider.getStandardModelVariables(mockTable("FA"), null);
        List<String> b = provider.getColumnOrder("FA");
        assertNotNull(a, "FA algorithm-A resolves");
        assertFalse(a.isEmpty(), "FA algorithm-A is non-empty");
        assertTrue(a.contains("FAOBJ"), "FA algorithm-A includes the FINDINGS ABOUT object var");
        assertFalse(b.isEmpty(), "FA algorithm-B is non-empty");
    }


    @Test
    void apDomain_resolvesUnderBothAlgorithms()
    {
        // APAE: AP-prefixed AE. Strips AP, resolves EVENTS, merges ASSOCIATED PERSONS identifiers
        // (minus USUBJID). Must resolve non-empty under both algorithms without throwing.
        List<String> a = provider.getStandardModelVariables(mockTable("APAE"), null);
        List<String> b = provider.getColumnOrder("APAE");
        assertNotNull(a, "APAE algorithm-A resolves");
        assertFalse(a.isEmpty(), "APAE algorithm-A is non-empty");
        assertFalse(b.isEmpty(), "APAE algorithm-B is non-empty");
    }
}
