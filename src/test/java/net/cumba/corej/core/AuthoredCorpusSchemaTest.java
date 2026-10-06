package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The authored split-storage corpus must validate against its own schemas —
 * {@code documentation/rule-check.schema.json} over {@code rules-src/checks/} and
 * {@code documentation/rule-doc.schema.json} over {@code rules-src/docs/}.
 *
 * <p>
 * ⭐ <b>Added 2026-09-21. Both schemas already existed and both were already correct; neither was
 * wired to anything.</b> {@code PLAN-rule-storage-split.md} produced three schemas and only
 * {@code rule-format.schema.json} ever got a test ({@link RuleFormatSchemaTest}), so for the other
 * two a corpus change could contradict its own published shape with nothing red. Measured at the
 * moment of wiring: <b>3435 of 3435</b> check files and <b>3096 of 3096</b> doc sidecars validated
 * clean, so this gate went in green — it pins a shape that already held, it does not repair one.
 * </p>
 *
 * <p>
 * ⚠ The schemas describe the <b>authored</b> corpus under {@code rules-src/}, not the assembled
 * {@code rules/} packages — that is {@link RuleFormatSchemaTest}'s beat, against a third schema.
 * The two layers are deliberately separate: the assembler is free to produce a shape the author
 * never types.
 * </p>
 */
class AuthoredCorpusSchemaTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * Population floors. Measured 2026-09-21: <b>3435</b> files under {@code rules-src/checks/} and
     * <b>3096</b> under {@code rules-src/docs/}. These are FLOORS, so corpus growth never reds
     * them, and they sit ~13% below the measurement so an ordinary retirement wave does not either.
     * <p>
     * ⛔ Their job is the one this whole class would otherwise be missing: a walk that resolves the
     * wrong directory, or a glob that matches nothing, validates zero files and reports a clean
     * pass. "All files validate" over an empty set is true and worthless. Do not weaken these to
     * make a run go green — re-measure and say so, as the wiring commit did.
     */
    private static final int MIN_CHECK_FILES = 3000;

    /** @see #MIN_CHECK_FILES */
    private static final int MIN_DOC_FILES = 2700;

    /**
     * The schema file: under {@code documentation/} in the authoring repository, under
     * {@code schema/} in the published one (which also has a {@code documentation/} tree, so the
     * FILE decides, not the directory).
     */
    static Path schemaFile(String aFileName)
    {
        Path base = Path.of(System.getProperty("projectBasedir"));
        Path internal = base.resolve("documentation").resolve(aFileName);
        return Files.isRegularFile(internal) ? internal : base.resolve("schema").resolve(aFileName);
    }


    private static Schema schema(String fileName) throws Exception
    {
        // documentation/ in the authoring repository, schema/ in the published one (the same
        // relocation rule-format.schema.json follows; check_oss_identity.py pins both pairs).
        Path schemaPath = schemaFile(fileName);
        if (!Files.isRegularFile(schemaPath))
        {
            throw new IllegalStateException(schemaPath + " not found — the schema gate cannot run "
                    + "without its schema, and a missing schema must never read as a pass.");
        }
        SchemaRegistry registry = SchemaRegistry
                .withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        return registry.getSchema(Files.readString(schemaPath), InputFormat.JSON);
    }


    /** Every {@code <FAMILY>/<Core.Id>.yaml} under the given {@code rules-src} subtree. */
    private static List<Path> authored(String subtree) throws Exception
    {
        Path root = Path.of(System.getProperty("projectBasedir"), "rules-src", subtree);
        try (Stream<Path> files = Files.walk(root))
        {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
        }
    }


    private static void assertSubtreeValidates(String subtree, String schemaFile, int floor)
        throws Exception
    {
        Schema schema = schema(schemaFile);
        List<Path> files = authored(subtree);

        assertTrue(files.size() >= floor,
                "only " + files.size() + " file(s) found under rules-src/" + subtree
                        + ", below the floor of " + floor + " — the corpus was not walked, so a"
                        + " clean result below would mean nothing");

        List<String> failures = new ArrayList<>();
        for (Path file : files)
        {
            JsonNode root = YAML.readTree(Files.readString(file));
            for (com.networknt.schema.Error error : schema.validate(root))
            {
                failures.add(file.getFileName() + ": " + error);
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " schema violation(s) in rules-src/"
                + subtree + " against " + schemaFile + ":\n" + String.join("\n", failures));
    }


    @Test
    void everyAuthoredCheckFileValidates() throws Exception
    {
        assertSubtreeValidates("checks", "rule-check.schema.json", MIN_CHECK_FILES);
    }


    @Test
    void everyAuthoredDocSidecarValidates() throws Exception
    {
        assertSubtreeValidates("docs", "rule-doc.schema.json", MIN_DOC_FILES);
    }


    /**
     * ⭐ The schemas must REJECT something, or the two tests above are a tautology: a schema that
     * requires nothing validates every file ever written, and 3435-of-3435 would prove only that
     * the files parse as YAML. Each required key is probed by its absence.
     */
    @Test
    void theSchemasRejectAFileMissingItsRequiredKeys() throws Exception
    {
        // ⚠ The two schemas spell Core differently and the difference is easy to get wrong: in a
        // CHECK file it is an object with a required Id, in a DOC sidecar it is a plain string.
        // This probe was written with a string first and the gate caught it.
        Schema check = schema("rule-check.schema.json");
        assertTrue(check
                .validate(
                        YAML.readTree("Core:\n  Id: \"T-0001\"\nCheck:\n  expression: \"a > b\"\n"))
                .isEmpty(), "a minimal well-formed check file is valid");
        assertFalse(check.validate(YAML.readTree("Check:\n  expression: \"a > b\"\n")).isEmpty(),
                "a check file with no Core is not a check file");
        assertFalse(check.validate(YAML.readTree("Core:\n  Id: \"T-0001\"\n")).isEmpty(),
                "a check file with no Check is not a check file");
        assertFalse(check.validate(YAML.readTree("Core: \"T-0001\"\nCheck:\n  expression: \"a\"\n"))
                .isEmpty(), "a check file whose Core is a bare string is not a check file");

        Schema doc = schema("rule-doc.schema.json");
        assertTrue(doc.validate(YAML.readTree("Core: \"T-0001\"\nCitations: {}\n")).isEmpty(),
                "a minimal well-formed doc sidecar is valid");
        assertFalse(doc.validate(YAML.readTree("Citations: {}\n")).isEmpty(),
                "a doc sidecar with no Core is not a doc sidecar");
        assertFalse(doc.validate(YAML.readTree("Core: \"T-0001\"\n")).isEmpty(),
                "a doc sidecar with no Citations is not a doc sidecar");
    }


    /**
     * ⭐ The schema-side negative control ({@code PLAN-rule-unknown-keys-gate} &#167;7.3, review
     * C1): one unknown key planted under every closed object of the authored format must be
     * rejected, and the clean twin validates — so a closure that quietly reopened reds here.
     */
    @Test
    void everyClosedObjectRejectsAnUnknownKey() throws Exception
    {
        Schema check = schema("rule-check.schema.json");
        String clean = """
                Core: {Id: "T-0001", Status: "Published", Version: "1"}
                ExecutabilityHint: {Category: "Library", Detail: "d"}
                Standards:
                  - Organization: CDISC
                    Standards:
                      - Name: SDTMIG
                        Version: "3.4"
                        References:
                          - Origin: o
                            Rule_Identifier: {Id: CG0001, Version: "1"}
                Scope: {Domains: {Include: [AE]}, Datasets: {Include: [AE]}}
                Requirements: {Variables: {All: [AESEQ]}, Datasets: [DM]}
                Bindings: [{name: "$n", expression: "record_count()"}]
                Match_Datasets: [{Name: DM, Keys: [USUBJID, {left: A, right: B}]}]
                Grouping: {Variables: [USUBJID]}
                wildcards: {xx: {min: 1, max: 9}}
                Expansion: [{token: "&V&", over: shared_variables, with: ADSL}]
                Outcome: {Message: m, Output_Variables: [AESEQ]}
                Check: {ERROR: {expression: "a > b", Message: e}}
                """;
        assertTrue(check.validate(YAML.readTree(clean)).isEmpty(),
                "the control file validates: " + check.validate(YAML.readTree(clean)));
        List<String> closed = List.of("", "/Core", "/ExecutabilityHint", "/Standards/0",
                "/Standards/0/Standards/0", "/Standards/0/Standards/0/References/0",
                "/Standards/0/Standards/0/References/0/Rule_Identifier", "/Scope",
                "/Scope/Datasets", "/Requirements", "/Requirements/Variables", "/Bindings/0",
                "/Match_Datasets/0", "/Match_Datasets/0/Keys/1", "/Grouping", "/wildcards/xx",
                "/Expansion/0", "/Outcome", "/Check", "/Check/ERROR");
        List<String> accepted = new ArrayList<>();
        for (String pointer : closed)
        {
            JsonNode root = YAML.readTree(clean);
            ((com.fasterxml.jackson.databind.node.ObjectNode) root.at(pointer)).put("Xplant", 1);
            List<com.networknt.schema.Error> errors = check.validate(root);
            if (errors.stream().noneMatch(e -> e.toString().contains("Xplant")))
            {
                accepted.add(pointer + " -> " + errors);
            }
        }
        assertTrue(accepted.isEmpty(), "closed objects that accepted a planted key: " + accepted);
    }
}
