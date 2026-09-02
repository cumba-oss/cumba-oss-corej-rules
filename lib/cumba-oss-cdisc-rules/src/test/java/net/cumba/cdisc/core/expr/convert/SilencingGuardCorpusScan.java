package net.cumba.cdisc.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.Rule;

/**
 * Shared corpus walk for the two silencing-guard ratchets ({@link SilencingGuardHoistGateTest} and
 * {@link SilencingGuardCarveOutGateTest}): apply a {@link SilencingGuardScan} classifier to every
 * rule in the {@code rules-src/checks} authoring corpus and render each non-empty verdict as one
 * {@code source-file|Core.Id|vars} line, the format all three baseline files use.
 *
 * <h2>⚑ Why the authoring view, and not a shipped corpus</h2>
 *
 * <p>
 * Both ratchets are about <b>how a rule was authored</b> — R-4.11 says a presence gate belongs in
 * {@code Requirements.Variables.All} rather than as a {@code Check} leaf, and the carve-out records
 * the guards whose Check-level form is deliberately kept. {@code rules-src/checks} is that surface
 * directly: per-rule YAML, authored in operator-leaf form, one file per {@code Core.Id}. It is
 * where an author introduces the shape these gates exist to catch, and where the fix lands.
 * </p>
 *
 * <p>
 * ⚠ <b>The shipped {@code rules/} corpus would be the wrong view.</b> It is generator output: its
 * {@code Check} is expression carriage that {@code ExprLowering} lowers back on load, and
 * {@code NonEmptyGuardInliner} had injected {@code var_exists(…)} terms into it that no author
 * wrote. A gate whose failure message tells the author to change their authoring must not be
 * measuring a machine's output — the "new guard authored" verdict would be unactionable, and a
 * generator change would move a baseline that is supposed to track human edits.
 * </p>
 *
 * <p>
 * ⚠ <b>Two consequences of reading YAML directly rather than an assembled package.</b> (1) There is
 * no package fan-out, so a rule that reaches N family packages contributes <b>one</b> line, not N —
 * the baselines are per-rule, not per-package-instance. (2) The walk bypasses
 * {@code RulePackageLoader}, so a rule parked at load ({@code Executability: "Not Executable"},
 * {@code Fix #159}) is still scanned. Both are deliberate: the question is what was authored, not
 * what the engine ended up running.
 * </p>
 */
final class SilencingGuardCorpusScan
{

    private SilencingGuardCorpusScan()
    {
    }

    /**
     * Lower bound on the number of rules carrying a {@code Check} — a scan that walks far fewer has
     * silently lost the corpus, and every ratchet built on it would pass vacuously. ⚠ This floor is
     * now the <em>only</em> thing standing between a mis-resolved directory and a green ratchet:
     * the walk used to be cross-checkable against a second shipped corpus and no longer is.
     */
    private static final int MIN_RULES_WITH_CHECK = 3000;

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    static Set<String> scan(Function<CheckCondition, List<String>> classifier) throws IOException
    {
        Path checksDir = Path.of(System.getProperty("projectBasedir"), "rules-src", "checks");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(checksDir))
        {
            files = walk.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted()
                    .toList();
        }
        Set<String> lines = new LinkedHashSet<>();
        int rulesScanned = 0;
        for (Path file : files)
        {
            Rule rule = YAML.readValue(file.toFile(), Rule.class);
            if (rule.getCheck() == null)
            {
                continue;
            }
            rulesScanned++;
            List<String> vars = classifier.apply(rule.getCheck());
            if (!vars.isEmpty())
            {
                String id = rule.getCore() != null ? rule.getCore().getId() : rule.getId();
                // '/' explicitly: the baselines are committed text and must not carry a
                // platform-dependent separator.
                String source = checksDir.relativize(file).toString().replace('\\', '/');
                lines.add(source + "|" + id + "|" + String.join(",", vars));
            }
        }
        assertTrue(rulesScanned > MIN_RULES_WITH_CHECK, "corpus scan looks broken: only "
                + rulesScanned + " rules had a Check — expected the full authoring corpus");
        return lines;
    }


    /** Reads a {@code file|Core.Id|vars} baseline resource, skipping blanks and {@code #}. */
    static Set<String> loadBaseline(String resource) throws IOException
    {
        Set<String> baseline = new LinkedHashSet<>();
        try (InputStream in = SilencingGuardCorpusScan.class.getResourceAsStream(resource))
        {
            assertNotNull(in, "missing baseline resource " + resource);
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = r.readLine()) != null)
                {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("#") && familyIsShipped(t))
                    {
                        baseline.add(t);
                    }
                }
            }
        }
        return baseline;
    }


    /**
     * Whether the family a baseline line belongs to is present in this tree's
     * {@code rules-src/checks/}.
     *
     * <p>
     * A baseline line starts {@code <FAMILY>/<Core.Id>.yaml|…}. Distributions that do not ship a
     * whole family — the OSS build ships no {@code CORE/} — would otherwise see every baseline
     * entry of that family as <em>stale</em>, which is not drift but absence. Filtering here keeps
     * ONE baseline file valid in every distribution: a tree that ships the family still checks it
     * exactly as before, because nothing is filtered when the directory exists.
     * </p>
     *
     * @param aLine
     *            a non-comment baseline line
     * @return {@code true} when the family directory exists, so the entry is checkable
     */
    private static boolean familyIsShipped(String aLine)
    {
        int slash = aLine.indexOf('/');
        if (slash <= 0)
        {
            return true;
        }
        return Files.isDirectory(Path.of(System.getProperty("projectBasedir"), "rules-src",
                "checks", aLine.substring(0, slash)));
    }


    /**
     * Writes the freshly computed set beside the build output so a failing ratchet is one
     * {@code cp} away from a reviewable diff instead of a wall of assertion text.
     */
    static Path writeActual(String resource, Set<String> current) throws IOException
    {
        List<String> sorted = new ArrayList<>(current);
        sorted.sort(null);
        Path out = Path.of("target", resource.replace(".txt", ".actual.txt"));
        Files.createDirectories(out.getParent());
        Files.write(out, sorted, StandardCharsets.UTF_8);
        return out.toAbsolutePath();
    }
}
