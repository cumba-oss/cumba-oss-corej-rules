package net.cumba.corej.core.ruletestsuites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.metadata.AdamDataStructureDetector;
import net.cumba.corej.core.metadata.AdamSubclassDetector;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.ruletest.cdt.CdtLoader;
import net.cumba.corej.ruletest.cdt.ruletest.MapBackedLibraryMetadataProvider;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins the declared-tier channel of the {@code .cdt} scenario format end to end: a scenario's
 * {@code structure=} / {@code subclass=} declaration must reach
 * {@link net.cumba.corej.core.exec.RuleRunner}'s ADaM scope gates (Fix #117/#118/#119) through
 * {@link ScenarioDeclaredScopeProvider}, with the same precedence a real Define-XML
 * {@code def:Class} / {@code def:SubClass} declaration gets.
 *
 * <p>
 * The vehicle is the shipped rule <b>CDISC-AD0261</b> — {@code Scope.Data_Structures =
 * [OCCURRENCE DATA STRUCTURE]}, {@code Scope.Subclasses = [ADVERSE EVENT]}, check
 * {@code not var_exists("AEDECOD")} — because both scope axes are column-detected
 * ({@code AETERM}/{@code AEDECOD}) and the check fires on a fixture that carries neither. So a
 * fixture with no AE column at all is out of scope <em>unless</em> the declaration reaches the
 * engine, and once it does the rule produces a violation. Test 1 and test 2 differ in nothing but
 * the declaration.
 * </p>
 *
 * <p>
 * The three end-to-end cases also ship as {@code .cdt} scenarios under {@code cdisc/CDISC-AD0261/};
 * they are repeated here because only a Java test can toggle {@code corej.defineFirst}, and because
 * the assertions here read the engine's own SKIPPED message rather than the harness verdict.
 * </p>
 *
 * <p>
 * <b>Fix #154</b> flipped {@code corej.defineFirst} to default {@code true}, which inverted the
 * precedence pair below: the declaration now wins by default and {@code -Dcorej.defineFirst=false}
 * is what restores the columns-first order.
 * </p>
 */
class ScenarioDeclaredScopeProviderTest
{

    private static final String RULES_PATH = CorpusPaths.rules("rules-cdisc-adamig-1-3.json")
            .toString();

    private static final String DEFINE_FIRST = "corej.defineFirst";

    /** No {@code AETERM}/{@code AEDECOD}: neither scope axis has a column signal. */
    private static final String NO_SIGNAL_COLUMNS = """
            col STUDYID type=Char
            col USUBJID type=Char
            ---
            CDISCPILOT01 | 01-701-1015
            """;

    /** {@code PARAMCD}/{@code AVAL} detect BDS — a confident, contradicting detection. */
    private static final String BDS_COLUMNS = """
            col STUDYID type=Char
            col PARAMCD type=Char
            col AVAL type=Num
            ---
            CDISCPILOT01 | HEIGHT | 170
            """;

    /**
     * Phase 3a.3: {@code AETERM} satisfies the {@code ADVERSE EVENT} subclass, {@code AEDECOD} is
     * absent so CDISC-AD0261 fires — and {@code PARAMCD}/{@code AVAL} make the columns detect
     * {@code BASIC DATA STRUCTURE}, so a {@code class=OCCDS} declaration genuinely contradicts
     * them.
     */
    private static final String CONTRADICTED_AE_COLUMNS = """
            col STUDYID type=Char
            col AETERM type=Char
            col PARAMCD type=Char
            col AVAL type=Num
            ---
            CDISCPILOT01 | HEADACHE | HEIGHT | 170
            """;

    private static Map<String, Rule> rulesByCoreId;

    @BeforeAll
    static void loadRules() throws IOException
    {
        RulePackage pkg = RulePackageLoader.load(Path.of(RULES_PATH));
        rulesByCoreId = new HashMap<>();
        for (Rule rule : pkg.getRules().values())
        {
            String coreId = rule.getCore() != null ? rule.getCore().getId() : rule.getId();
            rulesByCoreId.put(coreId, rule);
        }
    }

    // ---- 1: the declaration alone puts a columnless fixture in scope --------------


    @Test
    void declaredStructureAndSubclassPutAColumnlessFixtureInScope()
    {
        OverlayDataTable table = table("dataset ADAE structure=OCCDS subclass=AE",
                NO_SIGNAL_COLUMNS);

        // Pre-condition: nothing in the columns says OCCDS or ADVERSE EVENT.
        assertEquals(AdamDataStructureDetector.OTHER,
                AdamDataStructureDetector.detect("ADAE", List.of("STUDYID", "USUBJID")));

        RuleExecutionResult result = run(table);

        assertFalse(result.isSkipped(),
                "the declared OCCDS / ADVERSE EVENT tier must reach RuleRunner: "
                        + statusOf(result));
        assertFalse(result.isError(), statusOf(result));
        assertTrue(result.hasViolations(),
                "CDISC-AD0261 fires when AEDECOD is absent; got " + result.getViolationCount());
    }

    // ---- 2: without the declaration the same fixture stays out of scope -----------


    @Test
    void withoutTheDeclarationTheSameFixtureIsOutOfScope()
    {
        RuleExecutionResult result = run(table("dataset ADAE", NO_SIGNAL_COLUMNS));

        assertTrue(result.isSkipped(),
                "no column signal and no declaration — the rule must not run");
        assertNotNull(result.getStatusMessage());
        assertTrue(result.getStatusMessage().contains("OCCURRENCE DATA STRUCTURE"),
                "the skip must name the Data_Structures axis: " + result.getStatusMessage());
        assertFalse(result.hasViolations());
    }

    // ---- 3: precedence — override by default (Fix #154), fallback only under the opt-out ----


    /**
     * <b>Fix #154 inverted this case.</b> {@code corej.defineFirst} now defaults to {@code true},
     * so a declaration <em>beats</em> a confident contradicting column detection. The shipped
     * {@code .cdt} twin is
     * {@code cdisc/CDISC-AD0261/CDISC-AD0261-invalid-ADAE-declaration-wins-over-columns.cdt} (which
     * was {@code -skipped-ADAE-declaration-loses-to-columns.cdt} before the flip).
     */
    @Test
    void declarationBeatsAContradictingColumnDetection_fix154()
    {
        assertNull(System.getProperty(DEFINE_FIRST), "test must run with the shipped default");

        OverlayDataTable table = table("dataset ADAE structure=OCCDS subclass=AE", BDS_COLUMNS);

        // Pre-condition: the columns really do say BDS, i.e. there IS a contradiction to resolve.
        assertEquals(AdamDataStructureDetector.BDS,
                AdamDataStructureDetector.detect("ADAE", List.of("STUDYID", "PARAMCD", "AVAL")));

        RuleExecutionResult result = run(table);

        assertFalse(result.isSkipped(),
                "Fix #154: the declaration must override the columns by default: "
                        + statusOf(result));
        assertFalse(result.isError(), statusOf(result));
        assertTrue(result.hasViolations(),
                "CDISC-AD0261 fires when AEDECOD is absent; got " + result.getViolationCount());
    }


    /**
     * The documented opt-out: {@code -Dcorej.defineFirst=false} restores the pre-Fix-#154 order, in
     * which the declaration is only a fallback and a confident column detection wins.
     */
    @Test
    void defineFirstFalseRestoresColumnsFirst_fix154()
    {
        OverlayDataTable table = table("dataset ADAE structure=OCCDS subclass=AE", BDS_COLUMNS);
        System.setProperty(DEFINE_FIRST, "false");
        try
        {
            assertEquals(AdamDataStructureDetector.BDS, AdamDataStructureDetector.detect("ADAE",
                    List.of("STUDYID", "PARAMCD", "AVAL"), "OCCURRENCE DATA STRUCTURE", false));
            assertEquals(List.of(AdamSubclassDetector.ADVERSE_EVENT),
                    AdamSubclassDetector.resolve(List.of(AdamDataStructureDetector.OCCDS),
                            List.of("STUDYID", "PARAMCD", "AVAL"), List.of("ADVERSE EVENT"), true));

            RuleExecutionResult result = run(table);

            assertTrue(result.isSkipped(),
                    "-Dcorej.defineFirst=false makes the columns win again: " + statusOf(result));
            assertNotNull(result.getStatusMessage());
            assertTrue(result.getStatusMessage().contains("BASIC DATA STRUCTURE"),
                    "the skip must report the column-detected structure: "
                            + result.getStatusMessage());
        }
        finally
        {
            System.clearProperty(DEFINE_FIRST);
        }
    }

    // ---- 4: the decorator answers only for its own dataset, delegates the rest ----


    @Test
    void answersOnlyForTheScenariosPrimaryDatasetAndDelegatesTheRest()
    {
        OverlayDataTable adtte = table("dataset ADTTE structure=BDS subclass=TTE",
                NO_SIGNAL_COLUMNS);
        OverlayDataTable adae = table("dataset ADAE structure=OCCDS subclass=AE",
                NO_SIGNAL_COLUMNS);

        MetadataProvider inner = ScenarioDeclaredScopeProvider.wrap(adtte, defineDouble());
        MetadataProvider outer = ScenarioDeclaredScopeProvider.wrap(adae, inner);

        assertEquals("OCCURRENCE DATA STRUCTURE", outer.getDeclaredDatasetClass("ADAE"));
        assertEquals(List.of("ADVERSE EVENT"), outer.getDeclaredSubClasses("adae"));
        // Not this scenario's dataset — falls through to the delegate.
        assertEquals("BASIC DATA STRUCTURE", outer.getDeclaredDatasetClass("ADTTE"));
        assertEquals(List.of("TIME-TO-EVENT"), outer.getDeclaredSubClasses("ADTTE"));
        // Known to neither.
        assertNull(outer.getDeclaredDatasetClass("ADSL"));
        assertEquals(List.of(), outer.getDeclaredSubClasses("ADSL"));
    }


    @Test
    void delegatesEveryOtherAccessorToTheWrappedProvider()
    {
        MetadataProvider delegate = MapBackedLibraryMetadataProvider.builder().standard("adamig")
                .version("1-3").requiredVariables("ADAE", "STUDYID", "USUBJID").build();
        OverlayDataTable adae = table("dataset ADAE structure=OCCDS subclass=AE",
                NO_SIGNAL_COLUMNS);

        MetadataProvider wrapped = ScenarioDeclaredScopeProvider.wrap(adae, delegate);

        assertEquals(List.of("STUDYID", "USUBJID"), wrapped.getRequiredVariables("ADAE"));
        assertEquals("adamig", wrapped.getStandard());
        assertEquals("1-3", wrapped.getVersion());
    }

    // ---- 5: F-R8 — the slot decision, and the refusal that replaces the empty stand-in --


    @Test
    void refusesADeclaredAxisWithNoCarrierBlock()
    {
        OverlayDataTable adae = table("dataset ADAE structure=OCCDS subclass=AE",
                NO_SIGNAL_COLUMNS);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> ScenarioDeclaredScopeProvider.install(adae, null, null, "inline.cdt", true));

        String msg = thrown.getMessage();
        assertTrue(msg.contains("inline.cdt"), "the message must name the scenario: " + msg);
        assertTrue(msg.contains("#define"), "the message must name the fix: " + msg);
        assertTrue(msg.contains("#library"), "the message must name the alternative: " + msg);
    }


    @Test
    void installPutsTheDeclarationInTheDefineSlotAndLeavesTheLibrarySlotEmpty()
    {
        OverlayDataTable adae = table("dataset ADAE structure=OCCDS subclass=AE",
                NO_SIGNAL_COLUMNS);

        ScenarioDeclaredScopeProvider.Slots slots = ScenarioDeclaredScopeProvider.install(adae,
                null, defineDouble(), "inline.cdt", true);

        // The whole point of F-R8: a declared axis must not conjure a Library provider.
        assertNull(slots.library(), "the library slot must stay exactly as the scenario left it");
        assertNotNull(slots.define());
        assertEquals("OCCURRENCE DATA STRUCTURE", slots.define().getDeclaredDatasetClass("ADAE"));
        assertEquals(List.of("ADVERSE EVENT"), slots.define().getDeclaredSubClasses("ADAE"));
    }


    @Test
    void installFallsBackToTheLibrarySlotAndIsANoOpWithoutADeclaration()
    {
        OverlayDataTable declared = table("dataset ADAE structure=OCCDS subclass=AE",
                NO_SIGNAL_COLUMNS);
        MetadataProvider libraryDouble = defineDouble();

        ScenarioDeclaredScopeProvider.Slots viaLibrary = ScenarioDeclaredScopeProvider
                .install(declared, libraryDouble, null, "inline.cdt", true);
        assertNotNull(viaLibrary.library());
        assertEquals("OCCURRENCE DATA STRUCTURE",
                viaLibrary.library().getDeclaredDatasetClass("ADAE"));
        assertNull(viaLibrary.define(), "the define slot must stay as the scenario left it");

        // No declaration: both slots are handed straight back, undecorated.
        OverlayDataTable plain = table("dataset ADAE", NO_SIGNAL_COLUMNS);
        ScenarioDeclaredScopeProvider.Slots untouched = ScenarioDeclaredScopeProvider.install(plain,
                libraryDouble, null, "inline.cdt", true);
        assertSame(libraryDouble, untouched.library());
        assertNull(untouched.define());
    }


    /**
     * The regression the refusal exists for, measured on the engine rather than argued: with the
     * declaration riding in the sanctioned {@code #define} slot the library slot stays
     * {@code null}, so {@code CDISC-AD0497} — {@code Precondition: library_available()} — still
     * self-skips. Under the old wiring the decorator occupied the empty library slot,
     * {@code library_available()} folded TRUE and the rule executed and reported a violation.
     */
    @Test
    void aDeclaredAxisLeavesALibraryDependentRulesSelfSkipIntact()
    {
        OverlayDataTable adae = table("dataset ADAE structure=OCCDS subclass=AE",
                NO_SIGNAL_COLUMNS);
        Rule rule = rulesByCoreId.get("CDISC-AD0497");
        assertNotNull(rule, "CDISC-AD0497 missing from " + RULES_PATH);

        ScenarioDeclaredScopeProvider.Slots slots = ScenarioDeclaredScopeProvider.install(adae,
                null, defineDouble(), "inline.cdt", true);
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, adae, resolverFor(adae), "ADAE",
                slots.library(), null, slots.define());

        assertTrue(result.isSkipped(),
                "library_available() must still gate the rule: " + statusOf(result));
        assertFalse(result.hasViolations());

        // Counter-proof that the fixture is not vacuous: hand the same rule a Library provider and
        // it executes and fires (ADAE starts with "AD", the double declares no dataset class).
        RuleExecutionResult withLibrary = RuleRunnerCalls.execute(rule, adae, resolverFor(adae),
                "ADAE", defineDouble());
        assertFalse(withLibrary.isSkipped(), statusOf(withLibrary));
        assertTrue(withLibrary.hasViolations());
    }

    // ---- 6: the two table-metadata keys ------------------------------------------


    @Test
    void detectsWhetherTheScenarioDeclaresEitherAxis()
    {
        assertFalse(ScenarioDeclaredScopeProvider
                .declaresAdamScope(table("dataset ADAE", NO_SIGNAL_COLUMNS)));
        assertTrue(ScenarioDeclaredScopeProvider
                .declaresAdamScope(table("dataset ADAE structure=OCCDS", NO_SIGNAL_COLUMNS)));
        assertTrue(ScenarioDeclaredScopeProvider
                .declaresAdamScope(table("dataset ADAE subclass=AE", NO_SIGNAL_COLUMNS)));
    }

    // ---- 7: Phase 3a.3 — class= is a structure declaration too --------------------


    /**
     * ⚑⚑ <b>The harness/production divergence, stated.</b> Production has ONE {@code def:Class}
     * element: it is the SDTM observation class for an SDTM dataset and the ADaM <em>data
     * structure</em> for an ADaM one (ADaM has no class of its own), and one value feeds both the
     * class gate and the structure gate. The harness used to read {@code class=} for the class gate
     * only and always detect the structure from the columns, so a fixture saying
     * {@code class="BASIC DATA STRUCTURE"} meant something no real study could mean.
     */
    @Test
    void anAdamClassDeclarationIsAStructureDeclaration_phase3a3()
    {
        assertEquals("BASIC DATA STRUCTURE",
                ScenarioDeclaredScopeProvider.effectiveDeclaredStructure(
                        table("dataset ADLBC class=BDS", NO_SIGNAL_COLUMNS)),
                "the CdtLoader BDS shortcut expands, and the expansion IS a structure token");
        assertEquals("OCCURRENCE DATA STRUCTURE",
                ScenarioDeclaredScopeProvider.effectiveDeclaredStructure(table(
                        "dataset ADAE class=\"OCCURRENCE DATA STRUCTURE\"", NO_SIGNAL_COLUMNS)));
        assertEquals("MEDICAL DEVICE BASIC DATA STRUCTURE", ScenarioDeclaredScopeProvider
                .effectiveDeclaredStructure(table("dataset ADDV class=MD-BDS", NO_SIGNAL_COLUMNS)));
    }


    /**
     * ⚠ An SDTM {@code ItemGroupClass} value is not an ADaM data structure — it must not become
     * one. This is the discriminator that keeps SDTM/SEND untouched by Phase 3a; 1183 of the 1763
     * {@code class=}-declaring fixtures carry one of these.
     */
    @Test
    void anSdtmClassIsNotAStructureDeclaration_phase3a3()
    {
        for (String sdtm : List.of("FINDINGS", "EVENTS", "INTERVENTIONS", "TRIAL DESIGN",
                "SPECIAL PURPOSE", "RELATIONSHIP", "FINDINGS ABOUT", "NOT A CLASS AT ALL"))
        {
            assertNull(ScenarioDeclaredScopeProvider.effectiveDeclaredStructure(
                    table("dataset LB class=\"" + sdtm + "\"", NO_SIGNAL_COLUMNS)), sdtm);
        }
        // No class= at all is still no declaration.
        assertNull(ScenarioDeclaredScopeProvider
                .effectiveDeclaredStructure(table("dataset ADAE", NO_SIGNAL_COLUMNS)));
    }


    /** An explicit {@code structure=} is the more specific statement and still wins. */
    @Test
    void anExplicitStructureDeclarationStillWinsOverClass_phase3a3()
    {
        assertEquals("OCCURRENCE DATA STRUCTURE",
                ScenarioDeclaredScopeProvider.effectiveDeclaredStructure(
                        table("dataset ADAE class=BDS structure=OCCDS", NO_SIGNAL_COLUMNS)));
    }


    /**
     * ⚑⚑ <b>End to end: the divergence really is closed.</b> A fixture whose {@code class=} says
     * OCCDS and whose columns say nothing now reaches {@code RuleRunner} with the declared tier —
     * the same result the {@code structure=OCCDS} twin gets in
     * {@link #declaredStructureAndSubclassPutAColumnlessFixtureInScope()}.
     *
     * <p>
     * ⚠ The subclass axis is deliberately NOT derived from {@code class=} — there is no subclass in
     * a class declaration — so CDISC-AD0261's {@code Scope.Subclasses:[ADVERSE EVENT]} still has to
     * come from the columns; the fixture carries {@code AETERM} for that, and not {@code AEDECOD},
     * whose absence is what the rule fires on. It also carries {@code PARAMCD}/{@code AVAL}, so the
     * <b>columns detect {@code BASIC DATA STRUCTURE}</b> (a BDS indicator wins over an OCCDS
     * suffix) and the {@code class=OCCDS} declaration genuinely contradicts them. Without the
     * declaration reaching the engine the rule is SKIPPED on the structure axis — which is exactly
     * what {@link #aClassDeclarationThatDoesNotReachTheEngineSkips_phase3a3()} shows.
     * </p>
     */
    @Test
    void aClassDeclarationReachesTheEngine_phase3a3()
    {
        OverlayDataTable declared = table("dataset ADAE class=OCCDS", CONTRADICTED_AE_COLUMNS);

        assertEquals("OCCURRENCE DATA STRUCTURE",
                ScenarioDeclaredScopeProvider.effectiveDeclaredStructure(declared));
        assertEquals(AdamDataStructureDetector.BDS,
                AdamDataStructureDetector.detect("ADAE",
                        List.of("STUDYID", "AETERM", "PARAMCD", "AVAL")),
                "pre-condition: the columns must contradict the declaration");

        Rule rule = rulesByCoreId.get("CDISC-AD0261");
        assertNotNull(rule);
        ScenarioDeclaredScopeProvider.Slots slots = ScenarioDeclaredScopeProvider.install(declared,
                null, defineDouble(), "inline.cdt", true);
        assertNotNull(slots.define());
        assertEquals("OCCURRENCE DATA STRUCTURE", slots.define().getDeclaredDatasetClass("ADAE"),
                "the class= declaration must reach RuleRunner's declared tier");

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, declared, resolverFor(declared),
                "ADAE", slots.library(), null, slots.define());
        assertFalse(result.isSkipped(), statusOf(result));
        assertFalse(result.isError(), statusOf(result));
        assertTrue(result.hasViolations(),
                "CDISC-AD0261 must FIRE here, not merely produce the __error__ sentinel");
    }


    /**
     * ⚑⚑ The counter-proof that {@link #aClassDeclarationReachesTheEngine_phase3a3()} is not
     * vacuous, and simultaneously the statement of the divergence itself: hand the engine the same
     * fixture <em>without</em> the decorator and it SKIPs on the structure axis, reporting the
     * column-detected {@code BASIC DATA STRUCTURE}. Before Phase 3a.3 the harness pre-gate reached
     * that same verdict and the {@code class=} declaration was simply never consulted by either
     * gate.
     */
    @Test
    void aClassDeclarationThatDoesNotReachTheEngineSkips_phase3a3()
    {
        OverlayDataTable declared = table("dataset ADAE class=OCCDS", CONTRADICTED_AE_COLUMNS);
        Rule rule = rulesByCoreId.get("CDISC-AD0261");
        assertNotNull(rule);

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, declared, resolverFor(declared),
                "ADAE", null, null, null);

        assertTrue(result.isSkipped(), statusOf(result));
        assertNotNull(result.getStatusMessage());
        assertTrue(result.getStatusMessage().contains("BASIC DATA STRUCTURE"),
                result.getStatusMessage());
    }


    /**
     * ⚠⚠ <b>The carrier-block refusal must NOT fire on the 543 fixtures that declare an ADaM
     * {@code class=} and carry no provider block.</b> Those were authored for the class gate years
     * before this tier existed; refusing them over an inference the author never wrote would have
     * red-lined a fifth of the suite. Two independent conditions keep them out — the rule must
     * actually read the declared tier, and the declaration must actually disagree with the columns.
     */
    @Test
    void aClassOnlyFixtureWithoutACarrierIsNotRefused_phase3a3()
    {
        // (a) rule reads the tier, but the declaration agrees with the columns -> redundant.
        OverlayDataTable agreeing = table("dataset ADLBC class=BDS", BDS_COLUMNS);
        assertFalse(ScenarioDeclaredScopeProvider.needsClassDerivedDelivery(agreeing, true));
        ScenarioDeclaredScopeProvider.Slots a = ScenarioDeclaredScopeProvider.install(agreeing,
                null, null, "inline.cdt", true);
        assertNull(a.library());
        assertNull(a.define());

        // (b) the declaration contradicts the columns, but the rule reads no ADaM axis.
        OverlayDataTable contradicting = table("dataset ADAE class=OCCDS", BDS_COLUMNS);
        assertFalse(ScenarioDeclaredScopeProvider.needsClassDerivedDelivery(contradicting, false));
        ScenarioDeclaredScopeProvider.Slots b = ScenarioDeclaredScopeProvider.install(contradicting,
                null, null, "inline.cdt", false);
        assertNull(b.library());
        assertNull(b.define());

        // Counter-proof that (a) and (b) are not vacuous: with BOTH conditions met the declaration
        // has to be delivered, and with no carrier that is a refusal.
        assertTrue(ScenarioDeclaredScopeProvider.needsClassDerivedDelivery(contradicting, true));
        assertThrows(IllegalStateException.class, () -> ScenarioDeclaredScopeProvider
                .install(contradicting, null, null, "inline.cdt", true));
    }


    /**
     * An explicit {@code structure=} / {@code subclass=} keeps its unconditional carrier
     * requirement — that is an authoring contract, not an inference, and the ten fixtures that
     * declare one were written for it.
     */
    @Test
    void anExplicitDeclarationStillRequiresACarrierEvenForANonAdamScopedRule_phase3a3()
    {
        OverlayDataTable adae = table("dataset ADAE structure=OCCDS", NO_SIGNAL_COLUMNS);
        assertThrows(IllegalStateException.class,
                () -> ScenarioDeclaredScopeProvider.install(adae, null, null, "inline.cdt", false));
    }

    // ---- helpers -----------------------------------------------------------------


    private static OverlayDataTable table(String aDatasetLine, String aBody)
    {
        return CdtLoader.parse(aDatasetLine + "\n" + aBody, "inline");
    }


    /**
     * The minimal {@code #define standard=adamig version=1-3} block — the carrier a scenario must
     * declare before it may declare {@code structure=} / {@code subclass=}. It knows nothing about
     * the two axes; it exists only so the decorator has a slot to occupy.
     */
    private static MetadataProvider defineDouble()
    {
        return MapBackedLibraryMetadataProvider.builder().standard("adamig").version("1-3").build();
    }


    /**
     * Mirrors the factory's wiring: the declaration rides in the mandatory {@code #define} slot.
     */
    private static RuleExecutionResult run(OverlayDataTable aTable)
    {
        Rule rule = rulesByCoreId.get("CDISC-AD0261");
        assertNotNull(rule, "CDISC-AD0261 missing from " + RULES_PATH);
        MetadataProvider define = ScenarioDeclaredScopeProvider.declaresAdamScope(aTable)
                ? defineDouble()
                : null;
        ScenarioDeclaredScopeProvider.Slots slots = ScenarioDeclaredScopeProvider.install(aTable,
                null, define, "inline.cdt", true);
        return RuleRunnerCalls.execute(rule, aTable, resolverFor(aTable), "ADAE", slots.library(),
                null, slots.define());
    }


    private static DatasetResolver.WithInventory resolverFor(OverlayDataTable aTable)
    {
        String name = aTable.getMetaData().getName();
        return new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String aDomainName)
            {
                return name.equalsIgnoreCase(aDomainName) ? aTable : null;
            }


            @Override
            public Set<String> availableDatasets()
            {
                return Set.of(name);
            }
        };
    }


    private static String statusOf(RuleExecutionResult aResult)
    {
        return aResult.getStatus() + ": " + aResult.getStatusMessage();
    }
}
