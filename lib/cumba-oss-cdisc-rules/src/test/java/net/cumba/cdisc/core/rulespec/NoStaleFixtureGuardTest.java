package net.cumba.cdisc.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the split-storage single-source invariant (Phase 5,
 * {@code plans/done/PLAN-rule-storage-split.md}, re-aimed by
 * {@code plans/done/PLAN-parity-java-lane-native-corpus.md}): a spec fixture
 * {@code rulespec/specs/fixtures/<X>/rule.json} may only remain as a local copy if it genuinely
 * differs from what a {@code rule: { coreId: <Core.Id> }} redirect would resolve — which, since the
 * lane switch, is the shipped native {@code rules/} entry, not the {@code rules-src} authoring
 * YAML. Any fixture whose execution content (everything except the non-exec
 * {@code Authorities}/{@code id}) is identical to that entry should have been redirected — this
 * test fails if such a redundant copy is (re)introduced. Paths resolve against the module basedir
 * (Surefire {@code workingDirectory}).
 */
class NoStaleFixtureGuardTest
{

    private static final Path FIXTURES = Path.of("rulespec", "specs", "fixtures");

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Serializes with all nested map keys sorted, for an order-insensitive structural compare. */
    private static final ObjectMapper SORTED = new ObjectMapper().configure(
            com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Test
    void noRemainingFixtureDuplicatesASharedRule() throws IOException
    {
        if (!Files.isDirectory(FIXTURES))
        {
            return;
        }
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(FIXTURES))
        {
            for (Path dir : dirs.sorted().toList())
            {
                Path ruleJson = dir.resolve("rule.json");
                if (!Files.isRegularFile(ruleJson))
                {
                    continue;
                }
                JsonNode fixture = JSON.readTree(ruleJson.toFile());
                JsonNode coreNode = fixture.get("Core");
                if (coreNode == null || coreNode.get("Id") == null)
                {
                    continue;
                }
                String coreId = coreNode.get("Id").asText();
                JsonNode shipped = SpecLoader.nativeRuleOrNull(coreId);
                if (shipped == null)
                {
                    continue; // no shipped rule (e.g. synthetic fixture id) — legitimately local
                }
                if (executionEqual(fixture, shipped))
                {
                    offenders.add(dir.getFileName() + " (Core.Id " + coreId + ")");
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "fixtures execution-identical to their shipped rules/ entry must use 'coreId:'"
                        + " instead of a local rule.json copy (" + offenders.size() + "): "
                        + offenders);
    }


    /**
     * Compares execution content, ignoring non-exec fields.
     *
     * <p>
     * ⚠⚠ {@code Source} and {@code Source_Proposed} joined the ignore list on 2026-09-02
     * (PLAN-rules-corpus-build-integration §10). The released packages no longer carry them, while
     * the committed spec-fixture copies still do — so without this the two sides could never be
     * equal, every offender would look "different", and this guard would go green while catching
     * <b>nothing</b>. That is a silent weakening, not a failure: the assertion is that offenders is
     * EMPTY. Any field the release strips must be added here at the same time.
     * </p>
     */
    private static boolean executionEqual(JsonNode fixture, JsonNode check)
    {
        return normalise(fixture).equals(normalise(check));
    }


    private static String normalise(JsonNode rule)
    {
        ObjectNode copy = rule.deepCopy();
        copy.remove("Authorities");
        copy.remove("id");
        copy.remove("Standards");
        copy.remove("Source");
        copy.remove("Source_Proposed");
        return canonical(copy);
    }


    /** Order-insensitive canonical string of a JSON object (sorts map keys recursively). */
    private static String canonical(JsonNode node)
    {
        try
        {
            return SORTED.writeValueAsString(SORTED.treeToValue(node, Object.class));
        }
        catch (IOException e)
        {
            return node.toString();
        }
    }

}
