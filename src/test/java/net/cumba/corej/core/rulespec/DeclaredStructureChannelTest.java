package net.cumba.corej.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.metadata.AdamDataStructureDetector;
import org.junit.jupiter.api.Test;

/**
 * <b>Fix #235 — the declared-structure channel, and the proof it is load-bearing.</b>
 *
 * <p>
 * A parity spec declares its dataset's ADaM data structure in {@code library_mocks.classes}:
 * Define-XML carries the ADaM structure in the very same {@code def:Class} element that carries an
 * SDTM observation class, so {@code classes: {occurrence data structure: [ADAE]}} <em>is</em> a
 * structure declaration. Until Fix #235 that statement reached only the class channel, so
 * {@code RuleRunner}'s {@code Scope.Data_Structures} gate saw nothing, the column heuristic
 * answered {@code ADAM OTHER}, and the rule SKIPped.
 * </p>
 *
 * <h2>⚑ Why these assertions are on the SHIPPED state</h2>
 *
 * <p>
 * The end-to-end pin builds its own spec in-test rather than reaching for one of the 1 048 corpus
 * specs, and that is deliberate. The corpus rules this channel unblocks are still scoped on
 * {@code Scope.Classes}; their migration to {@code Scope.Data_Structures} is a {@code [regen]}
 * change owned by a later corpus lane. A test that asserted against those rules would be asserting
 * against a <b>pending</b> change — and would empty its own population the moment the migration
 * landed, so its success and its vacuity would become the same event (wave 34's lesson). The spec
 * below instead exercises {@code Scope.Data_Structures}, a shipped engine field, on a fixture this
 * test owns. Its population is one hand-built spec and the migration cannot drain it.
 * </p>
 *
 * <h2>The neuter</h2>
 *
 * <p>
 * {@link #declarationIsLoadBearing} is a paired assertion, not a single green: the <em>same</em>
 * spec with the {@code classes} block removed must SKIP. Deleting the routing in
 * {@link MockLibraryProvider#getDeclaredDatasetClass} (returning {@code null}) reds the declared
 * half; deleting the {@code CompositeMetadataProvider} override reds it too, because the ADaM specs
 * all name a standard/version and therefore run behind the composite.
 * </p>
 */
class DeclaredStructureChannelTest
{

    /**
     * ADAE with no BDS indicator ({@code PARAMCD}/{@code PARAM}/{@code AVAL}/{@code AVALC}) and no
     * OCCDS suffix ({@code TERM}/{@code TRT}/{@code DECOD}) — the column heuristic can only answer
     * {@code ADAM OTHER}, which is exactly the shape the 49 held specs have.
     */
    private static final List<String> UNDETECTABLE_ADAE_COLUMNS = List.of("STUDYID", "USUBJID",
            "AESEQ");

    private static String specText(boolean aDeclareStructure)
    {
        return """
                name: declared_structure_load_bearing
                kind: rule
                rule:
                  Core: {Id: WAVE35-B-DECLARE}
                  Description: Probe for the declared-structure channel.
                  Sensitivity: Record
                  Scope:
                    Data_Structures: {Include: ["OCCURRENCE DATA STRUCTURE"]}
                  Check:
                    expression: 'AESEQ == 1'
                  Outcome:
                    Message: AESEQ probe fired.
                    Output_Variables: [STUDYID, USUBJID, AESEQ]
                datasets:
                  ADAE:
                    row_key: [STUDYID, USUBJID, AESEQ]
                    column_types:
                      AESEQ: LONG
                    rows:
                      - {STUDYID: "S1", USUBJID: "001", AESEQ: 1}
                config:
                  standard: adamig
                  standard_version: "1.3"
                """ + (aDeclareStructure ? """
                library_mocks:
                  classes:
                    occurrence data structure: [ADAE]
                """ : "") + """
                expected_violations:
                  - rule_id: WAVE35-B-DECLARE
                    dataset: ADAE
                    row_key: {STUDYID: "S1", USUBJID: "001", AESEQ: 1}
                    status: SUCCESS
                    # ⚠ row_key keeps the typed value (LONG 1); output_variables are stringified.
                    output_variables: {STUDYID: "S1", USUBJID: "001", AESEQ: "1"}
                expected_errors: []
                """;
    }


    /**
     * The premise, pinned: the fixture's columns alone really do detect {@code ADAM OTHER}, so the
     * end-to-end pair below cannot pass by accident through the heuristic. Without this the
     * "withheld ⇒ SKIP" half could be green for the wrong reason.
     */
    @Test
    void fixtureColumnsAloneDetectAdamOther()
    {
        assertEquals(AdamDataStructureDetector.OTHER,
                AdamDataStructureDetector.detect("ADAE", UNDETECTABLE_ADAE_COLUMNS));
    }


    /**
     * ⭐ The load-bearing pin. Declared ⇒ the rule EXECUTES and its violation is observed; withheld
     * ⇒ the very same rule and the very same data SKIP.
     */
    @Test
    void declarationIsLoadBearing() throws IOException
    {
        SpecRunner.SpecResult declared = SpecRunner
                .runSchema(SpecLoader.loadFromString(specText(true)));
        assertEquals(RuleExecutionStatus.EXECUTED, declared.engineStatus(),
                "declared structure must reach RuleRunner's Data_Structures gate");
        assertTrue(declared.violationsMatch(), declared::formatDiff);

        SpecRunner.SpecResult withheld = SpecRunner
                .runSchema(SpecLoader.loadFromString(specText(false)));
        assertEquals(RuleExecutionStatus.SKIPPED, withheld.engineStatus(),
                "without the declaration the column heuristic yields ADAM OTHER and the rule"
                        + " must SKIP — if this ever goes EXECUTED the channel is no longer what"
                        + " admits the rule, and the assertion above is vacuous");
        assertNotEquals(declared.engineStatus(), withheld.engineStatus());
    }

    // ---- the routing predicate itself ------------------------------------------------


    private static MetadataProvider mockWithClasses(Map<String, List<String>> aClasses)
    {
        // Only `classes` (component 14 of 15) is populated — everything else is "no override".
        SpecSchema.SpecMocks mocks = new SpecSchema.SpecMocks(null, null, null, null, null, null,
                null, null, null, null, null, null, null, aClasses, null);
        return MockLibraryProvider.of(mocks, null);
    }


    @Test
    void adamStructureKeyBecomesADeclaration()
    {
        MetadataProvider provider = mockWithClasses(
                Map.of("occurrence data structure", List.of("ADAE")));
        assertEquals(AdamDataStructureDetector.OCCDS, provider.getDeclaredDatasetClass("ADAE"));
        // Dataset matching is case-insensitive, as it is in ScenarioDeclaredScopeProvider.
        assertEquals(AdamDataStructureDetector.OCCDS, provider.getDeclaredDatasetClass("adae"));
        // A dataset the block does not list is undeclared, not defaulted.
        assertNull(provider.getDeclaredDatasetClass("ADLB"));
    }


    @Test
    void medicalDeviceKeyKeepsItsSpecialisation()
    {
        MetadataProvider provider = mockWithClasses(
                Map.of("medical device basic data structure", List.of("ADLB")));
        assertEquals(AdamDataStructureDetector.MEDICAL_DEVICE_BDS,
                provider.getDeclaredDatasetClass("ADLB"));
    }


    /**
     * ⚠ The closed-vocabulary guard, and the reason this routing cannot over-reach: an SDTM class
     * key stays a pure class declaration. Were this to answer non-null, every SDTM spec's
     * {@code classes} block would silently become a structure declaration.
     */
    @Test
    void sdtmClassKeyIsNotAStructureDeclaration()
    {
        assertNull(mockWithClasses(Map.of("events", List.of("AE"))).getDeclaredDatasetClass("AE"));
        assertNull(
                mockWithClasses(Map.of("findings", List.of("LB"))).getDeclaredDatasetClass("LB"));
        assertNull(mockWithClasses(Map.of("not a real class", List.of("XX")))
                .getDeclaredDatasetClass("XX"));
    }


    @Test
    void noClassesBlockDeclaresNothing()
    {
        assertNull(mockWithClasses(null).getDeclaredDatasetClass("ADAE"));
    }


    /**
     * The composite must forward the mock's declaration. Without the Fix #235 override it fell
     * through to {@link MetadataProvider}'s {@code null} default, silently swallowing the answer on
     * every spec that names a standard/version — i.e. on all the ADaM ones.
     */
    @Test
    void compositeForwardsTheDeclaration()
    {
        MetadataProvider mock = mockWithClasses(
                Map.of("occurrence data structure", List.of("ADAE")));
        // A second-layer provider that declares nothing — the shape the store-backed provider
        // has for these specs. The mock's answer must survive the composition.
        MetadataProvider stored = mockWithClasses(Map.of("events", List.of("AE")));
        assertEquals(AdamDataStructureDetector.OCCDS,
                new CompositeMetadataProvider(mock, stored).getDeclaredDatasetClass("ADAE"));
        assertNull(new CompositeMetadataProvider(stored, stored).getDeclaredDatasetClass("ADAE"));
    }
}
