package net.cumba.corej.core.expr.convert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;

/**
 * Shared corpus census for the expression instruments: the distinct {@code Core.Id} &rarr; shipped
 * {@code Check.expression} map over {@code rules/}, plus the newline-separated baseline reader.
 *
 * <p>
 * ⚑ <b>History.</b> This class began as the <em>back-conversion</em> scan — {@code rules/}'s
 * shipped expressions lowered by {@code ExprLowering} back to the operator-leaf AST, guarding the
 * inverse that kept {@code rules-legacy/} reachable. Wave 33 removed that purpose (corpus, fork,
 * coverage gate and park list all gone — {@code plans/done/PLAN-cut-rules-legacy.md}); phase 7 /
 * D121 then retired {@code ExprLowering} itself, and the lowering half of this class
 * ({@code scan}/{@code lower}/{@code Outcome}) went with it. What survives is the census plumbing
 * {@code ExpressionTextIdempotenceTest} builds on: {@link #shippedExpressions()} and
 * {@link #loadBaseline(String)}.
 * </p>
 *
 * <p>
 * The population is the <b>distinct {@code Core.Id}</b> across all shipped native packages, not the
 * package entries: a rule that appears in eight IG versions is one rule with one expression, and
 * counting entries would inflate every figure by the ~3.8&times; fan-out
 * ({@code corpus-views-leaf-counts}).
 * </p>
 */
final class BackConversionScan
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The shipped native corpus, resolved relative to the module CWD (surefire basedir). */
    private static final Path RULES_DIR = CorpusPaths.rules();

    private BackConversionScan()
    {
    }


    /**
     * The distinct {@code Core.Id} &rarr; shipped {@code Check.expression} map over {@code rules/}.
     * Rules whose {@code Check} carries no {@code expression} (there are none today) are skipped.
     */
    static Map<String, String> shippedExpressions()
    {
        Map<String, String> byId = new LinkedHashMap<>();
        for (Path pkg : listPackages())
        {
            JsonNode root;
            try
            {
                root = MAPPER.readTree(Files.readString(pkg, StandardCharsets.UTF_8));
            }
            catch (IOException ex)
            {
                throw new UncheckedIOException(ex);
            }
            JsonNode rules = root.get("rules");
            if (rules == null || !rules.isObject())
            {
                continue;
            }
            for (Map.Entry<String, JsonNode> e : rules.properties())
            {
                JsonNode check = e.getValue().get("Check");
                if (check == null || !check.hasNonNull("expression"))
                {
                    continue;
                }
                byId.putIfAbsent(e.getKey(), check.get("expression").asText());
            }
        }
        return byId;
    }


    /** Loads a newline-separated, {@code #}-commented rule-id baseline from the test resources. */
    static Set<String> loadBaseline(String aResource) throws IOException
    {
        Set<String> baseline = new LinkedHashSet<>();
        try (InputStream in = BackConversionScan.class.getResourceAsStream(aResource))
        {
            if (in == null)
            {
                throw new IOException("missing baseline resource " + aResource);
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = r.readLine()) != null)
                {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("#"))
                    {
                        baseline.add(t);
                    }
                }
            }
        }
        return baseline;
    }


    private static java.util.List<Path> listPackages()
    {
        try (Stream<Path> files = Files.list(RULES_DIR))
        {
            return files.filter(p ->
            {
                String n = p.getFileName().toString();
                return n.startsWith("rules-") && n.endsWith(".json");
            }).sorted().toList();
        }
        catch (IOException ex)
        {
            throw new UncheckedIOException(ex);
        }
    }

}
