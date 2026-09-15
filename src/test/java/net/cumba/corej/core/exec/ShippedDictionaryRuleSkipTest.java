package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.metadata.RuntimeDictionaryProvider;
import net.cumba.corej.core.metadata.ValueMapDictionary;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * KDICT-F1 / Fix #268 — the no-dictionary SKIP contract asserted over <b>rules loaded from the
 * shipped corpus</b> (the generated {@code target/rules/}), not over a rule the test authored.
 *
 * <p>
 * <b>Why this class exists as a separate test.</b> Every pre-existing dictionary test hand-authored
 * the very gate it was testing: {@code DictionaryValidationTest}'s {@code *SkipsWithNoDictionary}
 * cases pass {@code dictionary_available("meddra")} in as a literal {@code Precondition}, and
 * {@code InjectInlineOperationGatesTest} asserts the loader's injector on a hand-authored
 * <em>inline</em> Check. Both shapes are real, and neither occurs anywhere in {@code rules/}: all
 * 98 shipped dictionary rules keep the operation <em>declared</em> as a {@code $}-ref and the
 * corpus carries zero {@code Precondition} blocks, so those tests could stay green for the whole
 * life of the project while every shipped dictionary rule silently false-PASSed with no dictionary
 * loaded.
 * </p>
 *
 * <p>
 * {@link #theShippedRulesCarryNoAvailabilityGateAtAll()} pins that premise directly, so the SKIPs
 * asserted below can only come from {@link RuleRunner}'s eager dictionary arm — there is no gate in
 * the rule for them to come from.
 * </p>
 */
class ShippedDictionaryRuleSkipTest
{

    /** The shipped package, read from the repo — the same file the product ships. */
    private static final Path SHIPPED_PMDA_SDTMIG_3_4 = CorpusPaths
            .rules("rules-pmda-sdtmig-3-4.json");

    /**
     * ⚠ Was {@code <repoRoot>/lib/corej-core/dictionaries/meddra.json} in the coreJ monorepo. The
     * split moved corej-core to its own repository, so the dummy dictionary bundle is carried in
     * this module's test resources instead — see {@code AbstractRuleTestSuitesFactory}.
     */
    private static final Path DUMMY_MEDDRA = Path.of(System.getProperty("projectBasedir"),
            "src/test/resources/dictionaries/meddra.json");

    private static RulePackage shipped;

    /** MedDRA only — the "provider present, this rule's type absent" case. */
    private static RuntimeDictionaryProvider meddraOnly;

    @BeforeAll
    static void loadShippedPackage() throws Exception
    {
        shipped = RulePackageLoader.load(SHIPPED_PMDA_SDTMIG_3_4);
        meddraOnly = new RuntimeDictionaryProvider(
                Map.of("meddra", ValueMapDictionary.load(DUMMY_MEDDRA)));
    }


    private static Rule shippedRule(String coreId)
    {
        Rule r = shipped.getRules().values().stream()
                .filter(rule -> rule.getCore() != null && coreId.equals(rule.getCore().getId()))
                .findFirst().orElseThrow(() -> new AssertionError("not in the shipped package: "
                        + coreId + " (" + SHIPPED_PMDA_SDTMIG_3_4 + ")"));
        assertNull(r.getLoadError(), coreId + " loads clean: " + r.getLoadError());
        return r;
    }


    private static RuleExecutionResult run(Rule r, IDataTable t, String domainPrefix,
            RuntimeDictionaryProvider dicts)
    {
        return RuleRunner.execute(r, t, name -> name.equals(t.getMetaData().getName()) ? t : null,
                domainPrefix, null, null, null, Integer.MAX_VALUE, null, dicts);
    }


    /** The SKIP reason, asserted present so a null message cannot silently satisfy a contains(). */
    private static String skipReason(RuleExecutionResult result)
    {
        String m = result.getStatusMessage();
        assertNotNull(m, "a SKIP must carry a reason");
        return m;
    }


    /** AE with one MedDRA-invalid term — violating-shaped data, so a false PASS is visible. */
    private static IDataTable ae()
    {
        return MockTable.of().col("USUBJID", "001", "001").col("AEDECOD", "Headache", "Cephalgia")
                .name("AE").build();
    }


    /** TS TRT rows; NOTADRUG is not an FDA-SRS (UNII) preferred term. */
    private static IDataTable tsTrt()
    {
        return MockTable.of().col("STUDYID", "S01", "S01").col("DOMAIN", "TS", "TS")
                .col("TSPARMCD", "TRT", "TRT").col("TSVAL", "ASPIRIN", "NOTADRUG").name("TS")
                .build();
    }


    /** TS PCLAS rows; row 2's TSVAL/TSVALCD are not the same MED-RT record. */
    private static IDataTable tsPclas()
    {
        return MockTable.of().col("STUDYID", "S01", "S01").col("DOMAIN", "TS", "TS")
                .col("TSPARMCD", "PCLAS", "PCLAS")
                .col("TSVAL", "Cyclooxygenase Inhibitors", "Adrenergic beta-Antagonists")
                .col("TSVALCD", "N0000000181", "N0000000181").name("TS").build();
    }


    @Test
    void theShippedRulesCarryNoAvailabilityGateAtAll()
    {
        // The premise the whole defect rested on, asserted rather than assumed: these rules have
        // no Precondition of their own, the loader injected none (it only gates INLINED calls),
        // and the dictionary operation is DECLARED with an external_dictionary_type.
        for (String coreId : new String[]
        {
                "PMDA-SD0008", "PMDA-SD2260", "PMDA-SD2265"
        })
        {
            Rule r = shippedRule(coreId);
            assertNull(r.getPrecondition(), coreId + " has no Precondition in the shipped corpus");
            assertNull(r.getInjectedPreconditionGates(),
                    coreId + " got no injected availability gate at load");
            assertNotNull(r.getOperations(), coreId + " declares its dictionary operation");
            assertEquals(1, r.getOperations().size(), coreId + " declares exactly one operation");
            Operation op = r.getOperations().get(0);
            assertTrue(OperationExecutor.isDictionaryDependent(op.getOperationType()),
                    coreId + " declares a dictionary-dependent operation");
            assertNotNull(op.getExternalDictionaryType(),
                    coreId + " names the dictionary type it needs");
        }
    }


    @Test
    void aShippedMeddraRuleSkipsWithNoDictionaryProvider()
    {
        // PMDA-SD0008: --DECOD must be a MedDRA PT. Row 2 ("Cephalgia") is violating-shaped, so
        // before Fix #268 this run EXECUTED and reported noViolation — a silent false PASS.
        RuleExecutionResult result = run(shippedRule("PMDA-SD0008"), ae(), "AE", null);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "a shipped dictionary rule with no dictionary supplied must SKIP");
        assertEquals(0, result.getViolations().size());
        assertTrue(skipReason(result).contains("meddra"),
                "the skip names the missing type: " + skipReason(result));
    }


    @Test
    void aShippedUniiRuleSkipsWithNoDictionaryProvider()
    {
        // PMDA-SD2260: the TSPARMCD conjunct is perfectly evaluable — the rule must still SKIP,
        // because the half that decides the finding cannot be answered.
        RuleExecutionResult result = run(shippedRule("PMDA-SD2260"), tsTrt(), "TS", null);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus());
        assertEquals(0, result.getViolations().size());
        assertTrue(skipReason(result).contains("unii"), skipReason(result));
    }


    @Test
    void aShippedCodeTermPairRuleSkipsWithNoDictionaryProvider()
    {
        // PMDA-SD2265 — valid_external_dictionary_code_term_pair against MED-RT.
        RuleExecutionResult result = run(shippedRule("PMDA-SD2265"), tsPclas(), "TS", null);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus());
        assertEquals(0, result.getViolations().size());
        assertTrue(skipReason(result).contains("medrt"), skipReason(result));
    }


    @Test
    void aShippedRuleSkipsWhenTheBundleLacksItsOwnType()
    {
        // ⚠ The gate is the rule's dictionary TYPE, not a non-null provider: a MedDRA-only bundle
        // leaves the UNII and MED-RT rules exactly as unanswerable as no bundle at all. This is
        // also the "some types loaded, others not" case at corpus scale — across a run, one rule's
        // dictionary being present says nothing about the next rule's.
        RuleExecutionResult unii = run(shippedRule("PMDA-SD2260"), tsTrt(), "TS", meddraOnly);
        assertEquals(RuleExecutionStatus.SKIPPED, unii.getStatus(),
                "a loaded MedDRA does not make the UNII rule answerable");
        assertTrue(skipReason(unii).contains("unii"), skipReason(unii));

        RuleExecutionResult medrt = run(shippedRule("PMDA-SD2265"), tsPclas(), "TS", meddraOnly);
        assertEquals(RuleExecutionStatus.SKIPPED, medrt.getStatus());
        assertTrue(skipReason(medrt).contains("medrt"), skipReason(medrt));
    }


    @Test
    void theSameBundleStillLetsTheMeddraRuleRunAndFire()
    {
        // The other half of the contract, on the SAME provider that skipped the two rules above:
        // the arm must gate, not silence. Without this the whole dictionary group could be
        // "fixed" by skipping everything.
        RuleExecutionResult result = run(shippedRule("PMDA-SD0008"), ae(), "AE", meddraOnly);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                "MedDRA is loaded, so the MedDRA rule runs");
        assertEquals(1, result.getViolations().size(),
                "'Cephalgia' is not a MedDRA PT in the dummy bundle, so it fires");
    }
}
