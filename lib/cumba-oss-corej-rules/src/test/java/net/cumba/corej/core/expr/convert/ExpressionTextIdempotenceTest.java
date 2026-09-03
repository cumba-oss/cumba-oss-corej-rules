package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionPrinter;
import org.junit.jupiter.api.Test;

/**
 * The P4 text gate, re-shaped at the flip (wave 43, {@code PLAN-rules-src-expression-flip.md} §6):
 * every expression the two corpora carry must be a <b>{@code print ∘ parse} fixed point</b>,
 * because the text <em>is</em> the corpus now. If {@code print(parse(t)) != t} for some rule, the
 * next regeneration (or any canonicalising tool) rewrites that file and every diff after it is
 * noise.
 *
 * <ul>
 * <li><b>P4a — the shipped native corpus.</b> For all {@code rules/} expressions {@code e}:
 * {@code print(parse(e)) == e}. Pins that the text {@code rules/} ships is a fixed point.
 * {@code NativeCorpusRoundTripTest} does <em>not</em> cover this: it regenerates from
 * {@code rules-src} and compares bytes, which proves the generator is deterministic, not that its
 * output survives a parse.</li>
 * <li><b>P4b — the authored corpus.</b> Since the flip, every {@code rules-src/checks/*.yaml}
 * authors a single {@code Check: {expression}} block — asserted here, so a legacy operator-leaf
 * Check cannot creep back in — and each authored text must equal {@code print(parse(text))}. This
 * covers the four parked {@code CDISC-SEND-020x} rules, which
 * {@code RuleStorageAssembler.dropParkedRules} keeps out of the shipped corpora — {@code rules-src}
 * is their only source, so only this population sees them.</li>
 * </ul>
 *
 * <p>
 * ⚠ <b>What it does NOT cover.</b> Idempotence is a property of the <em>text</em>, not of meaning:
 * it says a regeneration is a no-op, not that the expression states the right rule. Semantic checks
 * live in the rulespec suite and the corpus lints. Before the flip this gate additionally raised
 * every authored operator-leaf Check ({@code CheckToExpr}) and ratcheted the nine degenerate
 * singleton-composite files (emptied by {@code Fix #251}); the corpus authors no leaves any more,
 * so that half retired with the flip — the forward raise's remaining synthetic coverage is
 * {@code CheckToExprForwardTableTest}.
 * </p>
 */
class ExpressionTextIdempotenceTest
{

    private static final Path SRC_CHECKS = Path.of("rules-src", "checks");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** Floors, not equalities: both corpora grow. */
    private static final int MIN_SHIPPED = 3700;

    private static final int MIN_AUTHORED = 3700;

    @Test
    void p4aShippedExpressionsAreAFixedPointOfPrintAfterParse()
    {
        Map<String, String> expressions = BackConversionScan.shippedExpressions();
        // ⚠ Corpus-size floor removed on extraction: it asserted the FULL internal corpus,
        // which this subset deliberately is not. Enforced internally over the whole corpus.
        Map<String, String> unstable = new LinkedHashMap<>();
        expressions.forEach((id, text) ->
        {
            String reprinted = ExpressionPrinter.print(CheckExpressionParser.parse(text));
            if (!text.equals(reprinted))
            {
                unstable.put(id, "shipped=<" + text + "> reprinted=<" + reprinted + ">");
            }
        });
        System.out.printf(Locale.ROOT, "[P4a] %d shipped expressions, %d not a fixed point%n",
                expressions.size(), unstable.size());
        assertEquals(Map.of(), unstable,
                "rules/ ships expression text that does not survive print(parse(.))");
    }


    /**
     * P4b since the flip: the authored corpus IS expression text, and that text is canonical. Every
     * file must author the {@code {expression}} Check form (the legacy leaf form retired with the
     * flip) and every authored expression must be a {@code print ∘ parse} fixed point, so a
     * canonicalising rewrite of the corpus is a no-op.
     */
    @Test
    void p4bAuthoredExpressionsAreCanonicalFixedPoints() throws IOException
    {
        List<Path> files = authoredCheckFiles();
        // ⚠ Corpus-size floor removed on extraction: it asserted the FULL internal corpus,
        // which this subset deliberately is not. Enforced internally over the whole corpus.

        Map<String, String> notExpressionForm = new LinkedHashMap<>();
        Map<String, String> unstable = new LinkedHashMap<>();
        int checked = 0;
        for (Path file : files)
        {
            JsonNode root = YAML.readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode checkNode = root.get("Check");
            String id = idOf(root, file);
            if (checkNode == null || checkNode.isNull())
            {
                continue;
            }
            java.util.SequencedMap<String, JsonNode> levels = RuleFingerprint.levels(checkNode);
            List<String> texts;
            if (levels != null)
            {
                // A level-keyed Check (Plan C §3.3): one expression per level, each held to the
                // same fixed-point gate as a plain Check.
                texts = levels.values().stream().map(n -> n.path("expression").asText()).toList();
                if (texts.stream().anyMatch(String::isBlank))
                {
                    notExpressionForm.put(id, checkNode.toString());
                    continue;
                }
            }
            else if (DualFormCheckNode.isExpressionCheck(checkNode))
            {
                texts = List.of(checkNode.get("expression").asText());
            }
            else
            {
                notExpressionForm.put(id, checkNode.toString());
                continue;
            }
            checked++;
            for (String text : texts)
            {
                String reprinted = reprint(text);
                if (!text.equals(reprinted))
                {
                    unstable.put(id, "authored=<" + text + "> reprinted=<" + reprinted + ">");
                }
            }
        }
        System.out.printf(Locale.ROOT,
                "[P4b] %d authored checks, %d expression-form, %d not a fixed point%n",
                files.size(), checked, unstable.size());
        assertEquals(Map.of(), notExpressionForm,
                "rules-src authors a non-expression Check — the legacy leaf form retired at the"
                        + " flip (wave 43)");
        assertEquals(Map.of(), unstable,
                "rules-src authors expression text that does not survive print(parse(.))");
    }


    private static String reprint(String aText)
    {
        return ExpressionPrinter.print(CheckExpressionParser.parse(aText));
    }


    /**
     * Positive control (⚑ {@code Fix #191}): the comparison must be able to see instability. A
     * deliberately non-canonical spelling — redundant parentheses and non-canonical spacing — must
     * be reported as NOT a fixed point, while its canonical form must be.
     */
    @Test
    void theFixedPointCheckCanActuallySeeInstability()
    {
        String nonCanonical = "((not empty(AESEV))  and  AESEV != \"Y\")";
        String reprinted = ExpressionPrinter.print(CheckExpressionParser.parse(nonCanonical));
        assertTrue(!nonCanonical.equals(reprinted),
                "the control text must NOT be a fixed point, else this gate is vacuous");
        assertEquals(reprinted, ExpressionPrinter.print(CheckExpressionParser.parse(reprinted)),
                "and its canonical form must be");
    }


    private static String idOf(JsonNode aRoot, Path aFile)
    {
        JsonNode core = aRoot.get("Core");
        if (core != null && core.hasNonNull("Id"))
        {
            return core.get("Id").asText();
        }
        return aFile.getFileName().toString();
    }


    private static List<Path> authoredCheckFiles() throws IOException
    {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC_CHECKS))
        {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted()
                    .forEach(out::add);
        }
        return out;
    }
}
