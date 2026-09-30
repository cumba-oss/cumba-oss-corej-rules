package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * R-P5 — every shipped rule package in the expression-syntax {@code rules/} corpus must validate
 * against the machine-readable format schema {@code schema/rule-format.schema.json}. The schema is
 * strict on the shapes the engine dispatches on (package envelope, Check tree, Operations,
 * Match_Datasets) and, since {@code PLAN-rule-unknown-keys-gate}, closed on every object the engine
 * binds — an unknown key anywhere in a rule is a load error there, and the schema says the same one
 * step earlier.
 *
 * <p>
 * ⚠ This used to validate <b>two</b> folders against the one schema, and the agreement between them
 * was a free cross-check: a broken path or glob on one side showed up as a disagreement. The second
 * folder is gone, so the only thing standing between a mis-resolved directory and a green "all
 * packages validate" is the explicit floor in {@link #assertFolderValidates} — do not weaken it.
 * </p>
 */
class RuleFormatSchemaTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ⚠ Corpus-size floor dropped on extraction: it asserted the FULL internal corpus,
    // which this subset deliberately is not. Enforced internally over the whole corpus.

    private static Schema schema() throws Exception
    {
        Path schemaPath = Path.of(System.getProperty("projectBasedir"), "schema",
                "rule-format.schema.json");
        if (!Files.isRegularFile(schemaPath))
        {
            throw new IllegalStateException(schemaPath + " not found — the format gate cannot run "
                    + "without the schema, and a missing schema must never read as a pass.");
        }
        SchemaRegistry registry = SchemaRegistry
                .withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        return registry.getSchema(Files.readString(schemaPath), InputFormat.JSON);
    }


    private static List<Path> packages(Path folder) throws Exception
    {
        try (Stream<Path> files = Files.list(folder))
        {
            // rules-*.json only — the packages.json manifest is not a rule package.
            return files.filter(p ->
            {
                String n = p.getFileName().toString();
                return n.startsWith("rules-") && n.endsWith(".json");
            }).sorted().toList();
        }
    }


    /** The number of packages the folder's {@code packages.json} manifest declares. */
    private static int manifestPackages(Path folder) throws Exception
    {
        return RulePackageManifest.load(folder).packages().size();
    }


    private static void assertFolderValidates(Path folder, int expectedPackages) throws Exception
    {
        Schema schema = schema();
        List<String> failures = new ArrayList<>();
        int validated = 0;
        for (Path file : packages(folder))
        {
            JsonNode root = MAPPER.readTree(Files.readString(file));
            List<com.networknt.schema.Error> errors = schema.validate(root);
            for (com.networknt.schema.Error m : errors)
            {
                failures.add(file.getFileName() + ": " + m);
            }
            validated++;
        }
        // ⚠ The corpus-SIZE floor that stood here was removed when this repository was
        // extracted: it asserted the FULL internal corpus (>= 56 packages), which this
        // subset deliberately is not. The internal repository still enforces the size.
        // What is kept is the non-vacuity half, which is subset-safe: an empty or
        // mis-resolved corpus must red rather than report a clean pass over nothing.
        assertTrue(expectedPackages > 0, "no rule packages found in " + folder + "/ —"
                + " the corpus did not generate; a pass over nothing is not a check");
        assertTrue(validated == expectedPackages, "expected all shipped packages in " + folder
                + "/, saw " + validated + " (manifest declares " + expectedPackages + ")");
        assertTrue(failures.isEmpty(),
                "schema violations in " + folder + ":\n" + String.join("\n", failures));
    }


    @Test
    void expressionPackagesValidate() throws Exception
    {
        // rules/ holds the family-scoped expression packages RuleExprGenerator produces from
        // rules-src/, mirroring the manifest.
        assertFolderValidates(CorpusPaths.rules(), manifestPackages(CorpusPaths.rules()));
    }

    // ---- Plan C §3.3: the level-keyed Check ------------------------------


    /** A one-rule package wrapping {@code check} as the rule's {@code Check}. */
    private static JsonNode packageWith(String check) throws Exception
    {
        return MAPPER.readTree("{\"rules\":{\"T-0001\":{\"Check\":" + check + "}}}");
    }


    /**
     * The schema must accept both shapes of a {@code Check} and reject the two the loader calls
     * grammar violations. Without this the schema's {@code oneOf} could be wrong in either
     * direction and the corpus — every rule of which authors the plain shape — would never say so.
     */
    @Test
    void theSchemaAcceptsALevelMapAndRejectsAMalformedOne() throws Exception
    {
        Schema schema = schema();
        assertTrue(schema.validate(packageWith("{\"expression\":\"a > b\"}")).isEmpty(),
                "a plain condition is still valid");
        assertTrue(
                schema.validate(packageWith("{\"ERROR\":{\"expression\":\"a > b\"},"
                        + "\"INFO\":{\"expression\":\"a >= b\",\"Message\":\"m\"}}")).isEmpty(),
                "a level map, one level carrying its own Message, is valid");
        assertFalse(schema
                .validate(
                        packageWith("{\"ERROR\":{\"expression\":\"a > b\"},\"expression\":\"c\"}"))
                .isEmpty(), "a MIXED map is not a Check");
        assertFalse(
                schema.validate(packageWith("{\"FATAL\":{\"expression\":\"a > b\"}}")).isEmpty(),
                "an unknown level name is not a Check");
        assertFalse(
                schema.validate(packageWith("{\"NOTICE\":{\"expression\":\"a > b\"}}")).isEmpty(),
                "Notice is report-only and no rule may author it as a level");
        // Schema-contract drift (Plan C review): plainCheck's negative clause used to enumerate
        // the four canonical names, so a mixed map whose level key was misspelled or
        // non-canonical slipped through as a plain Check with tolerated extra keys. The clause
        // now uses the deserialiser's ^[A-Z][A-Z0-9_]*$ level-SHAPED test.
        assertFalse(schema
                .validate(packageWith(
                        "{\"CRITICAL\":{\"expression\":\"a > b\"},\"expression\":\"c\"}"))
                .isEmpty(), "a mixed map with a MISSPELLED all-caps level key is not a Check");
        assertFalse(
                schema.validate(
                        packageWith("{\"ERROR\":{\"expression\":\"a > b\"},\"expression\":\"c\","
                                + "\"NOTICE\":{\"expression\":\"d\"}}"))
                        .isEmpty(),
                "NOTICE mixed into a plain Check is rejected too");
    }


    /**
     * ⭐ The schema-side negative control ({@code PLAN-rule-unknown-keys-gate} &#167;7.3, review
     * C1): one unknown key planted under every closed object of the packaged format must be
     * rejected, and the clean twin of each plant must validate — so a closure that quietly reopened
     * (or one the schema never had) reds here, not only in the engine.
     */
    @Test
    void everyClosedObjectRejectsAnUnknownKey() throws Exception
    {
        Schema schema = schema();
        String clean = """
                {"standards":[{"id":"sdtmig/3-4","role":"primary"}],
                 "rules":{"T-0001":{
                   "Core":{"Id":"T-0001","Status":"Published","Version":"1"},
                   "ExecutabilityHint":{"Category":"Library","Detail":"d"},
                   "Authorities":[{"Organization":"CDISC","Rule_Ids":["CG0001"]}],
                   "Scope":{"Domains":{"Include":["AE"]},"Datasets":{"Include":["AE"]}},
                   "Requirements":{"Variables":{"All":["AESEQ"]},"Datasets":["DM"]},
                   "Bindings":[{"name":"$n","expression":"record_count()"}],
                   "Match_Datasets":[{"Name":"DM","Keys":["USUBJID",{"left":"A","right":"B"}]}],
                   "Grouping":{"Variables":["USUBJID"]},
                   "wildcards":{"xx":{"min":1,"max":9}},
                   "Expansion":[{"token":"&V&","over":"shared_variables","with":"ADSL"}],
                   "Outcome":{"Message":"m","Output_Variables":["AESEQ"]},
                   "Check":{"ERROR":{"expression":"a > b","Message":"e"}}}}}
                """;
        assertTrue(schema.validate(MAPPER.readTree(clean)).isEmpty(),
                "the control package validates: " + schema.validate(MAPPER.readTree(clean)));
        // Pointers relative to the RULE object (the package's rules map is reached through
        // Jackson, not a path — CorpusPathAnchoringGuardTest); "" and "/standards/0" are the two
        // package-level closures.
        List<String> packageLevel = List.of("", "/standards/0");
        List<String> ruleLevel = List.of("", "/Core", "/ExecutabilityHint", "/Authorities/0",
                "/Scope", "/Scope/Datasets", "/Requirements", "/Requirements/Variables",
                "/Bindings/0", "/Match_Datasets/0", "/Match_Datasets/0/Keys/1", "/Grouping",
                "/wildcards/xx", "/Expansion/0", "/Outcome", "/Check", "/Check/ERROR");
        List<String> accepted = new ArrayList<>();
        for (String pointer : packageLevel)
        {
            JsonNode root = MAPPER.readTree(clean);
            ((com.fasterxml.jackson.databind.node.ObjectNode) root.at(pointer)).put("Xplant", 1);
            List<com.networknt.schema.Error> errors = schema.validate(root);
            if (errors.stream().noneMatch(e -> e.toString().contains("Xplant")))
            {
                accepted.add("package" + pointer + " -> " + errors);
            }
        }
        for (String pointer : ruleLevel)
        {
            JsonNode root = MAPPER.readTree(clean);
            JsonNode rule = root.get("rules").get("T-0001");
            ((com.fasterxml.jackson.databind.node.ObjectNode) rule.at(pointer)).put("Xplant", 1);
            List<com.networknt.schema.Error> errors = schema.validate(root);
            if (errors.stream().noneMatch(e -> e.toString().contains("Xplant")))
            {
                accepted.add("rule" + pointer + " -> " + errors);
            }
        }
        assertTrue(accepted.isEmpty(), "closed objects that accepted a planted key: " + accepted);
    }
}
