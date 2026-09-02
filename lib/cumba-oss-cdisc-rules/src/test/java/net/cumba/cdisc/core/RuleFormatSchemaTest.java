package net.cumba.cdisc.core;

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
 * R-P5 ({@code plans/done/PLAN-native-engine-residuals.md}) — every shipped rule package in the
 * expression-syntax {@code rules/} corpus must validate against the machine-readable format schema
 * {@code documentation/rule-format.schema.json}. The schema is strict on the shapes the engine
 * dispatches on (package envelope, Check tree, Operations, Match_Datasets) and deliberately
 * permissive on extra fields (the loader runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} off).
 *
 * <p>
 * ⚠ This used to validate <b>two</b> folders against the one schema, and the agreement between them
 * was a free cross-check: a broken path or glob on one side showed up as a disagreement. The second
 * folder is gone, so the only thing standing between a mis-resolved directory and a green "all
 * packages validate" is the explicit floor in {@link #validateFolder} — do not weaken it.
 * </p>
 */
class RuleFormatSchemaTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Floor on the number of packages the manifest must declare — the shipped corpus is 58
     * family-scoped packages plus the {@code packages.json} manifest itself. ⚑ It grew from 56 when
     * {@code DRAFT-900044} declared ADaMIG 1.0 / 1.1, for which the DRAFT family previously shipped
     * no package at all. Deliberately a FLOOR, so corpus growth does not red it.
     */
    private static final int SHIPPED_PACKAGES = 56;

    private static Schema schema() throws Exception
    {
        Path schemaPath = Path.of(System.getProperty("projectBasedir"), "documentation",
                "rule-format.schema.json");
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


    private static void validateFolder(Path folder, int expectedPackages) throws Exception
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
        // Guard the guard: `validated == expectedPackages` is satisfied by 0 == 0, so an empty or
        // mis-resolved folder whose manifest is equally unreadable would report a clean pass. This
        // floor is the replacement for the cross-folder agreement the second corpus used to give.
        assertTrue(expectedPackages >= SHIPPED_PACKAGES,
                "the " + folder + "/ manifest declares only " + expectedPackages
                        + " packages — expected at least " + SHIPPED_PACKAGES);
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
        validateFolder(CorpusPaths.rules(), manifestPackages(CorpusPaths.rules()));
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
}
