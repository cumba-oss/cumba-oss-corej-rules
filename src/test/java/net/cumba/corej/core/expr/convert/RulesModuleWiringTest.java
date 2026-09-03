package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wiring smoke for the classes split out of the engine by PLAN-engine-rules-decoupling:
 * {@link RulePackagePruner} (from {@code RulePackageManifest}) and
 * {@link RulePackageExpressionJson} (from {@code RulePackageLoader}).
 */
class RulesModuleWiringTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void pruneRemovesOnlyStalePackages(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("rules-fda-sdtmig-3-4.json"), "{}");
        Files.writeString(dir.resolve("rules-old-scheme-1-0.json"), "{}");
        Files.writeString(dir.resolve("rules-placeholders.json"), "{}");
        Files.writeString(dir.resolve("notes.txt"), "keep");

        RulePackagePruner.pruneStalePackages(dir, Set.of("rules-fda-sdtmig-3-4.json"));

        assertTrue(Files.exists(dir.resolve("rules-fda-sdtmig-3-4.json")), "kept package");
        assertFalse(Files.exists(dir.resolve("rules-old-scheme-1-0.json")), "stale pruned");
        assertTrue(Files.exists(dir.resolve("rules-placeholders.json")), "non-package spared");
        assertTrue(Files.exists(dir.resolve("notes.txt")), "non-rules file untouched");
    }


    @Test
    void toExpressionJsonRendersCheckAsExpression() throws IOException
    {
        String pkg = """
                { "rules": { "r1": {
                    "Core": { "Id": "CORE-WIRING-1" },
                    "Outcome": { "Message": "m" },
                    "Sensitivity": "Record",
                    "Check": { "all": [ { "name": "AESEV", "operator": "non_empty" } ] }
                } } }
                """;
        RulePackage loaded = RulePackageLoader.loadFromString(pkg);
        Rule rule = loaded.getRules().values().iterator().next();

        JsonNode out = MAPPER.readTree(RulePackageExpressionJson.toExpressionJson(rule));
        JsonNode check = out.get("Check");
        assertTrue(check.has("expression"), "expression-notation Check expected, got: " + check);
        assertEquals("null", RulePackageExpressionJson.toExpressionJson(null));
    }
}
