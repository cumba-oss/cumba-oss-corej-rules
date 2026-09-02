package net.cumba.cdisc.core.expr.convert;

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
import net.cumba.cdisc.core.CorpusPaths;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.ExprLowering;
import net.cumba.cdisc.core.expr.ExpressionException;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.CheckCondition;

/**
 * Shared scan for the <b>back-conversion</b> direction — {@code rules/}'s shipped
 * {@code Check.expression} parsed by {@link CheckExpressionParser} and lowered by
 * {@link ExprLowering} back to the operator-leaf {@link CheckCondition} AST.
 *
 * <p>
 * ⚑ <b>Direction matters.</b> The <em>forward</em> direction (leaf &rarr; expression) was scored by
 * {@code ConverterCoverageTest} over the assembler's canonical packages until the flip retired the
 * authored leaf corpus (wave 43); its synthetic re-base is {@link CheckToExprForwardTableTest}.
 * This scan scores the opposite direction.
 * </p>
 *
 * <p>
 * ⚠ <b>Wave 33 removed this scan's original purpose but not the class.</b> The inverse existed to
 * keep {@code rules-legacy/} — the Python fork's operator-leaf corpus — reachable from the
 * expression form, and {@code BackConversionCoverageTest} ratcheted its residual against a park
 * list. Corpus, fork, coverage gate and park list are all gone
 * ({@code plans/PLAN-cut-rules-legacy.md}), so nothing needs an inverse for its own sake any more.
 * The class survives because {@code ExpressionTextIdempotenceTest} builds on
 * {@link #shippedExpressions()} and {@link #loadBaseline(String)}, and because
 * {@link #lower(String, String, boolean)} still expresses a property of {@code rules/} alone: a
 * shipped expression must parse and lower without throwing. ⛔ Do not delete it on the strength of
 * its name — grep the callers first.
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

    /** One rule's back-conversion outcome. */
    record Outcome(String id, String expression, @org.jspecify.annotations.Nullable String failure)
    {

        boolean lowered()
        {
            return failure == null;
        }
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


    /** Parses and lowers every shipped expression, in id order. */
    static Map<String, Outcome> scan(boolean aConversionMode)
    {
        Map<String, Outcome> out = new LinkedHashMap<>();
        shippedExpressions()
                .forEach((id, expression) -> out.put(id, lower(id, expression, aConversionMode)));
        return out;
    }


    /** Parses and lowers one expression, capturing the rejection message on failure. */
    static Outcome lower(String aId, String aExpression, boolean aConversionMode)
    {
        try
        {
            Expr parsed = CheckExpressionParser.parse(aExpression);
            CheckCondition lowered = aConversionMode
                    ? ExprLowering.toCheckConditionForConversion(parsed)
                    : ExprLowering.toCheckCondition(parsed);
            if (lowered == null)
            {
                return new Outcome(aId, aExpression, "lowering returned null");
            }
            return new Outcome(aId, aExpression, null);
        }
        catch (ExpressionException ex)
        {
            return new Outcome(aId, aExpression, message(ex));
        }
        catch (RuntimeException ex)
        {
            return new Outcome(aId, aExpression,
                    ex.getClass().getSimpleName() + ": " + message(ex));
        }
    }


    private static String message(RuntimeException aEx)
    {
        String m = aEx.getMessage();
        return m == null ? aEx.getClass().getSimpleName() : m;
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
