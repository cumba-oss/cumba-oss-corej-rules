package net.cumba.corej.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Validates the {@code coreId} rule-reference resolver in {@link SpecLoader} (split-storage Phase
 * 5, {@code plans/done/PLAN-rule-storage-split.md}; corpus switched by
 * {@code plans/done/PLAN-parity-java-lane-native-corpus.md}): a spec resolves the shipped rule from
 * the native {@code rules/} packages by id, drops the {@code Authorities} membership block, and an
 * {@code override} block deep-merges on top (override wins) — except an override touching
 * {@code Check}, which is rejected. Mirrors {@code loader.py} on the Python side, which resolves
 * from {@code rules-legacy}, its own production corpus. Paths resolve against the module basedir
 * (Surefire {@code workingDirectory}).
 */
class SpecLoaderCoreIdTest
{

    private static Path writeSpec(Path dir, String body) throws IOException
    {
        Path spec = dir.resolve("spec.yaml");
        Files.writeString(spec, body, StandardCharsets.UTF_8);
        return spec;
    }


    @Test
    void coreIdResolvesSharedRuleAndDropsMembership(@TempDir Path dir) throws IOException
    {
        SpecSchema schema = SpecLoader.load(writeSpec(dir, """
                name: t
                kind: rule
                rule:
                  coreId: FDA-SD1071
                """));
        JsonNode rule = schema.rule();
        assertNotNull(rule, "rule must resolve");
        assertEquals("FDA-SD1071", rule.get("Core").get("Id").asText());
        assertNotNull(rule.get("Check"), "shipped rule must carry its Check");
        assertTrue(rule.get("Check").has("expression"),
                "resolution must yield the shipped native form — the expression Check the"
                        + " production Java engine loads");
        assertFalse(rule.has("Authorities"), "the membership block must be dropped on resolution");
        assertFalse(rule.has("Standards"), "no Standards block in a native package entry");
    }


    @Test
    void anOverrideTouchingCheckIsRejected(@TempDir Path dir) throws IOException
    {
        Path spec = writeSpec(dir, """
                name: t
                kind: rule
                rule:
                  coreId: FDA-SD1071
                  override:
                    Check:
                      all:
                      - name: "AESEV"
                        operator: "non_empty"
                """);
        IOException rejected = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> SpecLoader.load(spec));
        assertTrue(rejected.getMessage().contains("must not touch Check"), rejected.getMessage());
    }


    @Test
    void overrideDeepMergesOnTopOfSharedRule(@TempDir Path dir) throws IOException
    {
        SpecSchema schema = SpecLoader.load(writeSpec(dir, """
                name: t
                kind: rule
                rule:
                  coreId: FDA-SD1071
                  override:
                    Outcome:
                      Message: "OVERRIDDEN"
                """));
        JsonNode rule = schema.rule();
        assertEquals("OVERRIDDEN", rule.get("Outcome").get("Message").asText(),
                "override must win over the shared rule");
        assertNotNull(rule.get("Check"), "non-overridden fields must survive the merge");
        assertEquals("FDA-SD1071", rule.get("Core").get("Id").asText());
    }

}
