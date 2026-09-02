package net.cumba.cdisc.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.RuleExecutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dataset-level {@code define_xml} channel — Java lane (PLAN-parity-define-dataset-channel
 * P1.1, §3.1/§3.3/§3.4/§3.5, D1–D10).
 *
 * <p>
 * Formerly one half of a hand-mirrored pair with a Python twin. The Python twin was cut in wave 33;
 * this lane is the only lane, and the lock-step duty the old header imposed is void. The scenarios
 * below are retained as coreJ contract coverage of {@link SpecDefineXml}.
 *
 * <p>
 * Covers: the shape discrimination (array = variable level, object = dataset level), the
 * unconditional dataset-name registration that closes §3.5's trap, D4's defaults <em>on both
 * shapes</em>, D10's {@code className} mapping, the R1–R8 rejections, and the Define-only /
 * {@code SUPPAE} key shapes the ten in-scope rules need.
 */
class DefineDatasetChannelTest
{

    private static final YAMLMapper YAML = new YAMLMapper();

    private static JsonNode defineXml(String yaml) throws IOException
    {
        return YAML.readTree(yaml);
    }


    private static MetadataProvider providerFor(String yaml) throws IOException
    {
        MetadataProvider p = SpecRunner.defineProviderFrom("unit", defineXml(yaml));
        assertNotNull(p, "a non-empty define_xml block must yield a provider");
        return p;
    }

    // --- shape A: variable level, unchanged -------------------------------------------


    @Test
    void variableLevelShapeIsUnchanged() throws IOException
    {
        MetadataProvider p = providerFor("""
                LB:
                  - {define_variable_name: LBORRES, define_variable_label: Result,
                     define_variable_role: Result Qualifier, define_variable_data_type: text,
                     define_variable_ccode: C66741,
                     define_variable_codelist_coded_codes: [C1, C2]}
                """);

        Map<String, String> v = p.getVariableMetadata("LB", "LBORRES");
        assertEquals("LBORRES", v.get("name"));
        assertEquals("Result", v.get("label"));
        assertEquals("Result Qualifier", v.get("role"));
        assertEquals("text", v.get("simpleDatatype"));
        assertEquals("C66741", v.get("ccode"));
        assertEquals("[\"C1\",\"C2\"]", v.get("codelist_coded_codes"));
        assertEquals(1, p.getDomainVariables("LB").size());
    }


    @Test
    void variableOnlyBlockStillRegistersTheDefineDatasetName() throws IOException
    {
        // D6 half 1 / §3.5 — getDatasetNames() must read the dedicated name list, never the
        // dataset-attribute map's key set. An empty list here is not a harmless emptiness: it makes
        // FDA-SD1063 fire on every dataset instead of skipping. (The mutation "point
        // getDatasetNames() at datasetMeta.keySet()" is caught by
        // StubMetadataProviderDefineDatasetTest, which can register a name with no attributes at
        // all; this lane always registers both, see the D4 assertion below.)
        MetadataProvider p = providerFor("""
                LB:
                  - {define_variable_name: LBORRES}
                """);

        assertEquals(List.of("LB"), p.getDatasetNames());
        // D4 on shape A — the Define does declare LB, so define_dataset_name is factually true for
        // it and the Python lane serves the key. Serving nothing here (the pre-remediation Java
        // behaviour) is a silent cross-lane divergence on EVERY variable-level key.
        assertEquals(Map.of("name", "LB"), p.getDatasetMetadata("LB"),
                "a variable-level key carries D4's name default and nothing else");
    }

    // --- shape B: dataset level -------------------------------------------------------


    @Test
    void datasetLevelShapeReachesTheProvider() throws IOException
    {
        MetadataProvider p = providerFor("""
                XY:
                  define_dataset_class: TRIAL DESIGN
                  define_dataset_label: Custom Dataset
                """);

        assertEquals(List.of("XY"), p.getDatasetNames());
        Map<String, String> ds = p.getDatasetMetadata("XY");
        assertEquals("XY", ds.get("name"));
        assertEquals("Custom Dataset", ds.get("label"));
        assertEquals("TRIAL DESIGN", ds.get("className"));
    }


    @Test
    void datasetClassLandsUnderClassNameNotClass() throws IOException
    {
        // D10 — the one key whose provider spelling differs from the operand. Copying
        // define_dataset_class literally leaves the operand undefined (putIfNonNull skips a null)
        // and FDA-SD9999 / PMDA-SD9999 / PMDA-AD9999 silently stop firing on this lane.
        Map<String, String> ds = providerFor("""
                XY:
                  define_dataset_class: TRIAL DESIGN
                """).getDatasetMetadata("XY");

        assertEquals("TRIAL DESIGN", ds.get("className"));
        assertNull(ds.get("class"), "the provider channel spells it className, not class");
        assertNull(ds.get("define_dataset_class"),
                "the YAML spelling must not survive translation");
    }


    @Test
    void datasetNameDefaultsToTheKey() throws IOException
    {
        // D4 — the fork outer-merges on dataset_name + dataset_location, so a record missing either
        // splits the row and would masquerade as the multiplication P3 measures.
        assertEquals("XY", providerFor("""
                XY:
                  define_dataset_label: Custom Dataset
                """).getDatasetMetadata("XY").get("name"));
        assertEquals("xy.xpt", SpecDefineXml.defaultLocation("XY"));
        assertEquals("suppae.xpt", SpecDefineXml.defaultLocation("SUPPAE"));
    }


    @Test
    void explicitSubKeysRestatingTheDerivedDefaultsAreAccepted() throws IOException
    {
        // R6's accepting half — a spec may spell the defaults out, as long as it agrees with the
        // key. Both are then no-ops.
        Map<String, String> ds = providerFor("""
                XY:
                  define_dataset_name: XY
                  define_dataset_location: xy.xpt
                """).getDatasetMetadata("XY");

        assertEquals(Map.of("name", "XY"), ds);
    }


    @Test
    void attributesWithNoJavaSinkAreAcceptedSilently() throws IOException
    {
        // D10 — every allowed sub-key other than name/label/class is Python-lane-only. Accepting
        // them is deliberate: define_dataset_location is required by D4 on the fork side, and
        // define_dataset_domain is in the fork's define_col_order even though nothing here reads
        // it.
        MetadataProvider p = providerFor("""
                XY:
                  define_dataset_location: xy.xpt
                  define_dataset_domain: XY
                  define_dataset_structure: One record per subject
                  define_dataset_is_non_standard: 'Yes'
                  define_dataset_variables: [STUDYID, USUBJID]
                  define_dataset_variable_order: [1, 2]
                  define_dataset_key_sequence: [STUDYID]
                  define_dataset_has_no_data: 'No'
                  define_dataset_label: Custom Dataset
                """);

        Map<String, String> ds = p.getDatasetMetadata("XY");
        assertEquals(Map.of("name", "XY", "label", "Custom Dataset"), ds,
                "only the three sinks survive translation");
        assertEquals(List.of("XY"), p.getDatasetNames());
    }


    @Test
    void theAllowListIsTheForksElevenColumnsInTheForksOrder()
    {
        // A5 / lock-step guard. dataset_metadata_define_dataset_builder.py's define_col_order
        // (lines 66-78) is the authority, and both lanes' R4 messages print this list — so the
        // ORDER is part of the mirrored contract, not cosmetics. define_dataset_domain used to be
        // missing from both lanes while both comments claimed the list was complete.
        assertEquals(List.of("define_dataset_name", "define_dataset_label",
                "define_dataset_location", "define_dataset_domain", "define_dataset_class",
                "define_dataset_structure", "define_dataset_is_non_standard",
                "define_dataset_variables", "define_dataset_variable_order",
                "define_dataset_key_sequence", "define_dataset_has_no_data"),
                SpecDefineXml.DATASET_ATTRIBUTE_KEYS);
    }


    @Test
    void everyKeyOfEitherShapeIsOnTheDatasetNameListInDeclarationOrder() throws IOException
    {
        // D3 — the block's key set IS the Define's dataset list, whatever shape each key carries.
        MetadataProvider p = providerFor("""
                VS:
                  - {define_variable_name: VSPOS}
                XY:
                  define_dataset_class: TRIAL DESIGN
                ZZ:
                  define_dataset_label: Another
                """);

        assertEquals(List.of("VS", "XY", "ZZ"), p.getDatasetNames());
    }


    @Test
    void absentBlockYieldsNoProvider() throws IOException
    {
        assertNull(SpecRunner.defineProviderFrom("unit", null));
    }

    // --- R1–R8: loud rejection --------------------------------------------------------


    @Test
    void topLevelEmptyBlockIsRejected(@TempDir Path tmp) throws IOException
    {
        // R1 / D6 half 2 — its own test on purpose: `define_xml: {}` reads as "already covered" by
        // the per-key cases, but it is the fully-armed form of §3.5's trap. It yields a NON-null
        // provider (opening RuleRunner's define skip gate) with an empty dataset-name list.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load(tmp, "define_xml: {}\n"));
        assertTrue(e.getMessage().contains("declares no datasets"), e.getMessage());
        assertTrue(e.getMessage().contains("FDA-SD1063"), e.getMessage());
        assertTrue(e.getMessage().contains("s.yaml"), "the message must name the spec: " + e);

        // …and the same block is rejected on the provider path, not just at load.
        assertThrows(IllegalArgumentException.class,
                () -> SpecRunner.defineProviderFrom("unit", defineXml("{}")));
    }


    @Test
    void perKeyEmptyArrayIsRejected(@TempDir Path tmp)
    {
        // R3
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load(tmp, "define_xml:\n  LB: []\n"));
        assertTrue(e.getMessage().contains("define_xml[LB]"), e.getMessage());
        assertTrue(e.getMessage().contains("empty variable-record array"), e.getMessage());
    }


    @Test
    void perKeyEmptyObjectIsRejected(@TempDir Path tmp)
    {
        // R3
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load(tmp, "define_xml:\n  XY: {}\n"));
        assertTrue(e.getMessage().contains("define_xml[XY]"), e.getMessage());
        assertTrue(e.getMessage().contains("empty dataset-attribute object"), e.getMessage());
    }


    @Test
    void nonArrayNonObjectValueIsRejected(@TempDir Path tmp)
    {
        // R2 — before P1 this hit a `return` inside the forEach lambda, which silently dropped the
        // key while leaving the provider installed.
        IllegalArgumentException scalar = assertThrows(IllegalArgumentException.class,
                () -> load(tmp, "define_xml:\n  XY: TRIAL DESIGN\n"));
        assertTrue(scalar.getMessage().contains("define_xml[XY]"), scalar.getMessage());
        assertTrue(scalar.getMessage().contains("expected an array"), scalar.getMessage());

        IllegalArgumentException nullValue = assertThrows(IllegalArgumentException.class,
                () -> load(tmp, "define_xml:\n  XY:\n"));
        assertTrue(nullValue.getMessage().contains("define_xml[XY]"), nullValue.getMessage());
    }


    @Test
    void unknownDatasetAttributeIsRejected(@TempDir Path tmp)
    {
        // R4 — the typo that both lanes used to drop silently.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load(tmp, "define_xml:\n  XY:\n    define_dataset_clas: TRIAL DESIGN\n"));
        assertTrue(e.getMessage().contains("define_xml[XY]"), e.getMessage());
        assertTrue(e.getMessage().contains("define_dataset_clas"), e.getMessage());
        assertTrue(e.getMessage().contains("define_dataset_class"),
                "the message must list the allowed attributes: " + e.getMessage());
    }


    @Test
    void mixedShapeIsRejected(@TempDir Path tmp)
    {
        // D2 — one key carries variable records OR dataset attributes, never both. The mixed form
        // {variables: [...], dataset: {...}} is an object whose sub-keys are not allowed
        // attributes, so R4 catches it.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load(tmp, """
                        define_xml:
                          VS:
                            variables:
                              - {define_variable_name: VSPOS}
                            dataset:
                              define_dataset_class: FINDINGS
                        """));
        assertTrue(e.getMessage().contains("define_xml[VS]"), e.getMessage());
        assertTrue(e.getMessage().contains("cannot be mixed"), e.getMessage());
    }


    @Test
    void nonMappingVariableRecordIsRejected(@TempDir Path tmp)
    {
        // R5
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load(tmp, "define_xml:\n  LB:\n    - LBORRES\n"));
        assertTrue(e.getMessage().contains("define_xml[LB]"), e.getMessage());
        assertTrue(e.getMessage().contains("must be a mapping"), e.getMessage());
    }


    @Test
    void nonObjectBlockIsRejected(@TempDir Path tmp)
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load(tmp, "define_xml: [LB]\n"));
        assertTrue(e.getMessage().contains("define_xml must be a mapping"), e.getMessage());
    }


    @Test
    void defineDatasetDomainIsAccepted(@TempDir Path tmp) throws IOException
    {
        // A5 — the eleventh key. Both lanes used to reject it as "unknown" while the fork's
        // define_col_order carries it, so a spec could not express the fork's own column set.
        SpecSchema spec = load(tmp, """
                define_xml:
                  XY:
                    define_dataset_domain: XY
                """);
        MetadataProvider p = SpecRunner.defineProviderFrom(spec.name(), spec.defineXml());
        assertNotNull(p);
        // Accepted-and-dropped: there is no Java sink for it, only D4's name default survives.
        assertEquals(Map.of("name", "XY"), p.getDatasetMetadata("XY"));
    }


    @Test
    void contradictoryDefineDatasetNameIsRejected(@TempDir Path tmp) throws IOException
    {
        // R6 — the key IS the dataset identity here (D3) while the fork reads the RECORD's
        // define_dataset_name as the identity (define_dataset_names.py:29-31 and the
        // extract_dataset_metadata lookup). A mismatch therefore diverges the lanes in three
        // places at once, silently.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load(tmp, "define_xml:\n  XY:\n    define_dataset_name: OTHER\n"));
        assertTrue(e.getMessage().contains("define_xml[XY]"), e.getMessage());
        assertTrue(e.getMessage().contains("define_dataset_name is 'OTHER'"), e.getMessage());
        assertTrue(e.getMessage().contains("'OTHER' key instead"),
                "the message must send the author to a second block key: " + e.getMessage());

        // …and the provider path rejects it too, not only the loader.
        assertThrows(IllegalArgumentException.class, () -> SpecRunner.defineProviderFrom("unit",
                defineXml("XY:\n  define_dataset_name: OTHER\n")));
    }


    @Test
    void contradictoryDefineDatasetLocationIsRejected(@TempDir Path tmp) throws IOException
    {
        // R6 — the fork outer-merges on define_dataset_name + define_dataset_location, so a
        // location that does not derive from the key splits the merged row in two. That artefact
        // is indistinguishable from the real N-times multiplication P3 measures.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> load(tmp,
                "define_xml:\n  XY:\n    define_dataset_location: elsewhere.xpt\n"));
        assertTrue(e.getMessage().contains("define_xml[XY]"), e.getMessage());
        assertTrue(e.getMessage().contains("elsewhere.xpt"), e.getMessage());
        assertTrue(e.getMessage().contains("xy.xpt"),
                "the message must state the derived default: " + e.getMessage());
        assertTrue(e.getMessage().contains("second block key"), e.getMessage());

        assertThrows(IllegalArgumentException.class, () -> SpecRunner.defineProviderFrom("unit",
                defineXml("XY:\n  define_dataset_location: elsewhere.xpt\n")));
    }


    @Test
    void bareNullValuedDatasetAttributeIsRejected(@TempDir Path tmp) throws IOException
    {
        // R7 — `define_dataset_name:` with nothing after it. Java used to skip the null (so the
        // attribute fell back to the key) while Python's setdefault kept None, and the fork then
        // calls None.upper(). An author error on both lanes rather than a papered-over one.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load(tmp, "define_xml:\n  XY:\n    define_dataset_name:\n"));
        assertTrue(e.getMessage().contains("define_xml[XY]"), e.getMessage());
        assertTrue(e.getMessage().contains("define_dataset_name"), e.getMessage());
        assertTrue(e.getMessage().contains("has no value"), e.getMessage());

        // Any sub-key, not just the ones with a Java sink — the Python lane carries them all.
        IllegalArgumentException other = assertThrows(IllegalArgumentException.class,
                () -> load(tmp, "define_xml:\n  XY:\n    define_dataset_structure:\n"));
        assertTrue(other.getMessage().contains("has no value"), other.getMessage());

        assertThrows(IllegalArgumentException.class, () -> SpecRunner.defineProviderFrom("unit",
                defineXml("XY:\n  define_dataset_label:\n")));
    }


    @Test
    void nonTextualNameLabelOrClassIsRejected(@TempDir Path tmp) throws IOException
    {
        // R8 — all three are strings in the fork's ItemGroupDef representation. Java's asText()
        // would coerce an unquoted scalar ("true", "123") while the Python lane keeps the YAML
        // bool / int, so the same spec would render two different Define overlays.
        IllegalArgumentException bool = assertThrows(IllegalArgumentException.class,
                () -> load(tmp, "define_xml:\n  XY:\n    define_dataset_label: true\n"));
        assertTrue(bool.getMessage().contains("define_xml[XY]"), bool.getMessage());
        assertTrue(bool.getMessage().contains("must be a string"), bool.getMessage());
        assertTrue(bool.getMessage().contains("Quote the value"), bool.getMessage());

        IllegalArgumentException number = assertThrows(IllegalArgumentException.class,
                () -> load(tmp, "define_xml:\n  XY:\n    define_dataset_class: 123\n"));
        assertTrue(number.getMessage().contains("must be a string"), number.getMessage());

        IllegalArgumentException seq = assertThrows(IllegalArgumentException.class,
                () -> load(tmp, "define_xml:\n  XY:\n    define_dataset_name: [XY]\n"));
        assertTrue(seq.getMessage().contains("must be a string"), seq.getMessage());

        assertThrows(IllegalArgumentException.class, () -> SpecRunner.defineProviderFrom("unit",
                defineXml("XY:\n  define_dataset_class: 123\n")));

        // …while the Python-lane-only keys keep their YAML type: they are never asText()-coerced
        // here, so a list or a bool under them is legal.
        assertNotNull(SpecRunner.defineProviderFrom("unit",
                defineXml("XY:\n  define_dataset_variables: [STUDYID]\n")));
    }

    // --- end to end: the operand reaches the engine -----------------------------------


    @Test
    void defineDatasetClassReachesTheJavaOperand() throws IOException
    {
        // Proves the whole chain: spec YAML → SpecDefineXml.datasetAttrs → StubMetadataProvider
        // (className) → RuleRunner.injectDatasetLevel / the native ds_class accessor → operand.
        SpecRunner.SpecResult r = SpecRunner.runSchema(SpecLoader
                .loadFromString(datasetMetaSpec("XY", "XY", "define_dataset_class: TRIAL DESIGN")));

        assertEquals(RuleExecutionStatus.EXECUTED, r.engineStatus(), r::formatDiff);
        assertEquals(1, r.observed().size(), () -> "expected one finding, got " + r.observed());
        assertEquals("TRIAL DESIGN",
                r.observed().get(0).outputVariables().get("define_dataset_class"));
    }


    @Test
    void suppaeKeyedDatasetLevelBlockWorks() throws IOException
    {
        // D3's namespace note: shape B is dataset-NAME-keyed, so a key whose name deliberately
        // differs from its domain (SUPPAE, APDM, AESEX1, …) must resolve. 25 such dataset keys
        // exist in the corpus.
        SpecRunner.SpecResult r = SpecRunner.runSchema(SpecLoader.loadFromString(
                datasetMetaSpec("SUPPAE", "SUPPAE", "define_dataset_class: RELATIONSHIP")));

        assertEquals(RuleExecutionStatus.EXECUTED, r.engineStatus(), r::formatDiff);
        assertEquals(1, r.observed().size(), () -> "expected one finding, got " + r.observed());
        assertEquals("RELATIONSHIP",
                r.observed().get(0).outputVariables().get("define_dataset_class"));
    }


    @Test
    void defineOnlyDatasetKeyIsAccepted() throws IOException
    {
        // D5 / Q4 — a define_xml key with no `datasets:` entry is the Define-only dataset shape
        // FDA-SD0061 exists to detect. The new contents validation must not reject it, and the key
        // must reach the Define dataset-name list (which is what the define_dataset_names operation
        // reads) without entering the study inventory.
        String yaml = datasetMetaSpec("XY", "XY", "define_dataset_class: TRIAL DESIGN") + """
                  ZZ:
                    define_dataset_label: Not submitted
                """;
        SpecSchema spec = SpecLoader.loadFromString(yaml);
        SpecRunner.SpecResult r = SpecRunner.runSchema(spec);
        assertEquals(RuleExecutionStatus.EXECUTED, r.engineStatus(), r::formatDiff);

        MetadataProvider p = SpecRunner.defineProviderFrom(spec.name(), spec.defineXml());
        assertNotNull(p);
        assertEquals(List.of("XY", "ZZ"), p.getDatasetNames());
        assertEquals("Not submitted", p.getDatasetMetadata("ZZ").get("label"));
    }

    // --- the shipped corpus is unaffected ---------------------------------------------

    /**
     * The specs that carried a {@code define_xml} block when P1.1 landed — all variable level. New
     * dataset-level specs belong to P2/P3/P5 and do not need to be added here; this list pins the
     * pre-existing seven against a silent shape reinterpretation.
     */
    private static final List<String> SHIPPED_DEFINE_SPECS = List.of("CORE-000929.yaml",
            "CORE-001081.yaml", "DRAFT-900021.yaml", "DRAFT-900022.yaml", "DRAFT-900023.yaml",
            "DRAFT-900024.yaml", "FDA-SD0059.yaml");

    @Test
    void shippedDefineXmlSpecsAreVariableLevelAndCarryOnlyTheD4Name() throws IOException
    {
        Path specsRoot = Path.of(System.getProperty("projectBasedir"), "rulespec", "specs");
        assertTrue(Files.isDirectory(specsRoot), "specs root must exist: " + specsRoot);

        for (String fileName : SHIPPED_DEFINE_SPECS)
        {
            Path specFile = specsRoot.resolve(fileName);
            assertTrue(Files.exists(specFile), () -> "missing spec " + specFile);
            SpecSchema spec = SpecLoader.load(specFile);
            assertNotNull(spec.defineXml(), fileName);

            MetadataProvider p = SpecRunner.defineProviderFrom(spec.name(), spec.defineXml());
            assertNotNull(p, fileName);
            List<String> keys = new ArrayList<>();
            spec.defineXml().properties().forEach(e ->
            {
                keys.add(e.getKey());
                assertTrue(e.getValue().isArray(),
                        () -> fileName + '/' + e.getKey() + ": shipped define specs are variable"
                                + " level; re-deriving this one as dataset level would change its"
                                + " verdict silently");
            });
            // D3 — the keys register as Define dataset names even though they carry only variables.
            assertEquals(keys, p.getDatasetNames(), fileName);
            for (String key : keys)
            {
                // D4 on shape A — the name default and NOTHING else. A shipped variable-level spec
                // that started carrying a label or a class would mean its shape was reinterpreted.
                assertEquals(Map.of("name", key), p.getDatasetMetadata(key),
                        () -> fileName + '/' + key + ": only D4's name default expected");
            }
        }
    }

    // --- helpers ----------------------------------------------------------------------


    private static SpecSchema load(Path tmp, String defineBlock) throws IOException
    {
        Path file = tmp.resolve("s.yaml");
        Files.writeString(file, """
                name: define_channel_unit
                kind: check_operator
                check:
                  name: SEX
                  operator: equal_to
                  value: "M"
                datasets:
                  AE:
                    row_key: [STUDYID, USUBJID]
                    rows:
                      - {STUDYID: "S1", USUBJID: "001", SEX: "M"}
                config:
                  standard: sdtmig
                  standard_version: "3.4"
                """ + defineBlock + """
                expected_violations: []
                expected_errors: []
                """);
        return SpecLoader.load(file);
    }


    /**
     * A minimal {@code Dataset Metadata Check against Define XML} spec: one dataset, one
     * dataset-level define key, and a Check that fires when {@code define_dataset_class} is
     * non-empty. Written inline rather than as a {@code coreId:} redirect because P1.1 adds no
     * specs — P2 is where a real rule gets one.
     */
    private static String datasetMetaSpec(String dataset, String defineKey, String attribute)
    {
        return """
                name: define_channel_e2e
                kind: rule
                rule:
                  id: define_channel_e2e
                  Core: {Id: AUTO-DEFINE-DS}
                  Description: "Fires when the Define declares a dataset class."
                  Sensitivity: "Dataset"
                  Executability: "Fully Executable"
                  Scope:
                    Domains:
                      Include: ["%1$s"]
                  Check:
                    all:
                      - {name: define_dataset_class, operator: non_empty}
                  Outcome:
                    Message: "define class present"
                    Output_Variables: [define_dataset_class]
                datasets:
                  %1$s:
                    row_key: [STUDYID, USUBJID]
                    rows:
                      - {STUDYID: "S1", USUBJID: "001"}
                config:
                  standard: sdtmig
                  standard_version: "3.4"
                expected_violations: []
                expected_errors: []
                define_xml:
                  %2$s:
                    %3$s
                """.formatted(dataset, defineKey, attribute);
    }
}
