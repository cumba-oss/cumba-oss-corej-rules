package net.cumba.cdisc.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * D3's key-typo guard (PLAN-parity-expected-status §2.1): every spec's top-level keys must be
 * declared by {@link SpecSchema}.
 *
 * <p>
 * A bad <b>value</b> (e.g. {@code expected_status: skipped}) now throws on both lanes. A bad
 * <b>key</b> ({@code expected_statu:}) does not: Python's {@code extra="forbid"} catches it, but
 * {@link SpecLoader}'s mappers set {@code FAIL_ON_UNKNOWN_PROPERTIES=false}, so on this lane it is
 * silently dropped and the field falls back to its default. That is exactly the
 * silently-never-matching mode that left {@code expected_status} dead for the whole of M1. While
 * the Python lane existed, its strict check was opt-in ({@code PyParity} profile) and a default
 * {@code -P main} build never saw it; wave 33 removed that lane entirely, so today nothing else
 * checks a spec's keys at all. Hence this test.
 *
 * <p>
 * The allow-list is <b>derived by reflection from the record</b> rather than hard-coded, so it
 * tracks the schema automatically. (It was 17 keys before
 * {@code PLAN-parity-java-lane-native-corpus} added {@code pinned_from}, and hard-coding the old
 * number would have rejected five valid specs.) Turning {@code FAIL_ON_UNKNOWN_PROPERTIES} on
 * instead is not an option: specs legitimately carry keys the loader strips before mapping.
 */
class SpecTopLevelKeysTest
{

    /** Resolved by the loader and removed before the schema mapping — never top-level. */
    private static final Set<String> LOADER_ONLY_KEYS = Set.of("coreId", "override", "$ref");

    /**
     * Jackson's OWN strategy instance — not a reimplementation.
     *
     * <p>
     * A hand-rolled "insert {@code _} before every uppercase char" is subtly wrong: Jackson
     * suppresses the separator after an already-translated uppercase, so {@code defineXML} binds as
     * {@code define_xml} while the naive version yields {@code define_x_m_l}. No current component
     * has consecutive capitals, so the two agree today — but this test exists to track schema
     * drift, and the first component someone adds with an acronym would produce a false red
     * pointing at the spec files rather than at this test.
     */
    private static final PropertyNamingStrategies.SnakeCaseStrategy SNAKE = new PropertyNamingStrategies.SnakeCaseStrategy();

    private static Set<String> allowedKeys()
    {
        Set<String> allowed = new TreeSet<>();
        for (RecordComponent rc : SpecSchema.class.getRecordComponents())
        {
            allowed.add(SNAKE.translate(rc.getName()));
        }
        return allowed;
    }


    @Test
    void everySpecUsesOnlyDeclaredTopLevelKeys() throws IOException
    {
        Set<String> allowed = allowedKeys();
        YAMLMapper yaml = new YAMLMapper();
        List<String> offenders = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        Path root = Path.of("rulespec/specs").toAbsolutePath();
        List<Path> specs = SpecLoader.findSpecs(root);
        // An empty spec library would make every assertion below vacuously true — the same trap
        // RuleExecutionSpecTest.specs() guards against. Fail instead of pretending to check.
        assertFalse(specs.isEmpty(), () -> "No specs found under " + root);
        for (Path spec : specs)
        {
            JsonNode node = yaml.readTree(spec.toFile());
            for (Map.Entry<String, JsonNode> e : node.properties())
            {
                String key = e.getKey();
                seen.add(key);
                if (!allowed.contains(key))
                {
                    offenders.add(spec.getFileName() + ": '" + key + "'");
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                () -> "Spec files carry top-level keys that SpecSchema does not declare — a typo "
                        + "here is silently dropped on the Java lane (FAIL_ON_UNKNOWN_PROPERTIES "
                        + "is off), so it must fail here instead.\n  allowed: " + allowed
                        + "\n  offenders: " + offenders);

        // The loader-only keys must never appear at the TOP level: they live inside `rule:`.
        // If one ever surfaces here the allow-list above would have to grow, so assert the
        // premise rather than assuming it.
        for (String key : LOADER_ONLY_KEYS)
        {
            assertFalse(seen.contains(key),
                    () -> "'" + key + "' appeared as a top-level spec key; it is a rule-level "
                            + "redirect resolved by SpecLoader, not a schema field.");
        }
    }


    @Test
    void theAllowListContainsTheStatusFieldAndIsDerivedNotHardCoded()
    {
        Set<String> allowed = allowedKeys();
        assertTrue(allowed.contains("expected_status"), allowed::toString);
        assertTrue(allowed.contains("expected_violations"), allowed::toString);
        assertTrue(allowed.contains("pinned_from"), allowed::toString);
        assertTrue(allowed.contains("library_mocks"), allowed::toString);
        // A typo'd key must NOT be in the allow-list — this is the whole point.
        assertFalse(allowed.contains("expected_statu"), allowed::toString);
    }
}
