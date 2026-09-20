package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code RuleCanonicalizer.TOP_ORDER} must name every top-level key the authored corpus actually
 * uses ({@code plans/done/PLAN-scope-requirements-split.md} phase 1 item 7, grounding
 * {@code R-B6}).
 *
 * <p>
 * <b>Why this guard has to exist.</b> An unlisted top-level key is not rejected by the
 * canonicaliser — it is silently <em>relocated</em> to just before {@code Source} with a warning
 * nobody reads ({@code RuleCanonicalizer}'s unknown-key scan). And nothing else catches it:
 * {@code RuleCanonicalizerTest} is {@code @TempDir}-fixture only, never reading {@code rules-src/},
 * and the class's sole production caller is {@code scripts/backfill-rules.sh}. So forgetting a new
 * field here fails <b>silently, at the next backfill run</b>, long after the plan that added the
 * field is closed. That is precisely the shape this test converts into a build failure.
 * </p>
 *
 * <h2>✅ The named exception EXPIRED as designed (2026-08-25)</h2>
 *
 * <p>
 * {@code Expansion} was a live modelled field ({@code Rule.getExpansion()}) with two authored
 * carriers and <b>absent</b> from {@code TOP_ORDER} — board finding <b>L4</b>, held out of this
 * guard behind a deliberately expiring allowlist that said <em>"it dies when L4 lands"</em>. L4
 * landed: {@code Expansion} now heads the expansion-directive group in {@code TOP_ORDER}, and the
 * allowlist is <b>gone</b> rather than weakened. The guard below is now unconditional, which is
 * what the expiry was for.
 * </p>
 *
 * <p>
 * ⚑ Landing it moved <b>no</b> corpus byte: both carriers carry none of the four wildcard keys, so
 * the slot chosen is exactly where the unknown-key relocation already placed the block.
 * </p>
 */
class CanonicalTopOrderCoverageTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final Path CHECKS_DIR = Path.of("rules-src", "checks");

    @Test
    @DisplayName("every top-level key in rules-src/checks is named by TOP_ORDER")
    void topOrderNamesEveryAuthoredTopLevelKey() throws IOException
    {
        assertTrue(Files.isDirectory(CHECKS_DIR),
                "corpus not found at " + CHECKS_DIR.toAbsolutePath());
        // key -> the files carrying it, so a failure names something actionable rather than a set.
        TreeMap<String, TreeSet<String>> unlisted = new TreeMap<>();
        List<Path> files = yamlFiles();
        // 3700 -> 3435: the CORE family's 518 authored rules were deleted (PLAN-retire-core-family
        // phase 6). Re-derived from the walk, not adjusted by arithmetic.
        assertTrue(files.size() >= 3435, "expected the full authored corpus, got " + files.size());
        for (Path file : files)
        {
            JsonNode rule = YAML.readTree(file.toFile());
            if (rule == null || !rule.isObject())
            {
                continue;
            }
            for (java.util.Iterator<String> it = rule.fieldNames(); it.hasNext();)
            {
                String key = it.next();
                if (RuleCanonicalizer.KNOWN.contains(key) || RuleCanonicalizer.DROP.contains(key))
                {
                    continue;
                }
                unlisted.computeIfAbsent(key, _ -> new TreeSet<>())
                        .add(file.getFileName().toString());
            }
        }
        assertTrue(unlisted.isEmpty(),
                "top-level keys the corpus authors but RuleCanonicalizer.TOP_ORDER does not name —"
                        + " each one is SILENTLY relocated to just before `Source` at the next"
                        + " backfill run: " + unlisted);
    }


    private static List<Path> yamlFiles() throws IOException
    {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(CHECKS_DIR))
        {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml")).forEach(out::add);
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

}
