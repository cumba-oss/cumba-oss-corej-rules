package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.authoring.RulePackageConverter;
import net.cumba.corej.authoring.RulePackageConverter.ConversionReport;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CheckCondition;
import org.junit.jupiter.api.Test;

/**
 * The corpus gate for the carry-through converter: for every canonical package the
 * {@link RuleStorageAssembler} builds in memory from {@code rules-src/}, every rule's {@code Check}
 * (and {@code Precondition}) is run through {@link RulePackageConverter#convertCheck} — the
 * production path — and the <em>converted output</em> is asserted to carry the authored expression
 * <strong>byte-verbatim</strong> and to parse to the <strong>identical {@link Expr}</strong>.
 *
 * <p>
 * <b>Why the assembler and not a corpus directory.</b> The assembler's canonical node is the same
 * tree the forward converter consumes on the native branch (see {@link RuleCorpusGenerator}), so it
 * is the authentic input rather than a stand-in. Since the flip (wave 43) that input is
 * expression-form throughout, and {@code convertExisting} keeps each Check verbatim.
 * {@link #MIN_LEAVES_SCANNED} is the explicit guard against silent vacuity.
 * </p>
 *
 * <p>
 * ⚑ <b>History.</b> Until D121 (phase 7 of {@code PLAN-typed-expression-engine.md}) this test's
 * comparison was <em>semantic over the v1 operator-leaf AST</em>: the converted output was
 * deserialized back through the leaf lowering and compared leaf by leaf, with a normalising
 * {@code cmp} (multiset children, single-element unwrap) and an Expr-IR equivalence rescue for the
 * negation pairs. That machinery guarded the leaf↔expression <em>conversion</em>, which no longer
 * exists — the corpus is expression-form, the converter a carry-through, and the leaf model is
 * retired — so the byte-verbatim + parse-equal assertion here is the whole surviving obligation,
 * and it is <em>stronger</em> where it applies: a converter that rewrote a single character of an
 * authored expression now fails, where a semantic compare would have shrugged.
 * </p>
 */
class RulePackageConverterCorpusTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Non-zero floor on the Check population this test actually feeds through the converter. Until
     * the flip (wave 43) the authored corpus carried operator-leaf trees and the converter reported
     * {@code ~36k} leaves (floor {@code 30_000}); since the flip every authored Check is one
     * expression, the converter's {@code convertExisting} keeps it verbatim, and the report counts
     * one unit per Check — measured {@code 14 281} at the flip. The floor is set well below the
     * measured figure so ordinary corpus churn does not trip it, but a collapse to (near) zero
     * does.
     *
     * <p>
     * ⚠ Without this, an empty input makes every assertion in
     * {@link #everyConvertedCheckIsCarriedVerbatimAcrossTheCorpus} hold trivially and the gate goes
     * green having verified nothing. That is a broken measurement, not a pass.
     * </p>
     */
    private static final int MIN_LEAVES_SCANNED = 12_000;

    /**
     * Level entries (rule &#215; declared check level) this run actually round-tripped. Reported
     * next to the Check population so the level arm's reach is <b>re-derivable on every run</b>
     * rather than asserted against a figure that rots; the arm's teeth are
     * {@link #aLevelMapCannotBindAsACondition}, which does not depend on the corpus carrying a
     * level map at all.
     */
    private static int levelEntries;

    @Test
    void everyConvertedCheckIsCarriedVerbatimAcrossTheCorpus() throws IOException
    {
        RuleStorageAssembler assembler = new RuleStorageAssembler();
        List<RuleStorageAssembler.Triple> triples = assembler.discoverPackages();
        // rules-src/ assembles into the 8 core packages after the corpus cleanup.
        assertTrue(triples.size() >= 8,
                "expected the assembled rules-*.json corpus, found " + triples.size());

        List<String> mismatches = new ArrayList<>();
        ConversionReport report = new ConversionReport();
        int ruleChecks = 0;
        levelEntries = 0;

        for (RuleStorageAssembler.Triple t : triples)
        {
            String pkgName = RuleStorageAssembler.packageFileName(t.family(), t.standard(),
                    t.version());
            JsonNode root = assembler.assemblePackage(t.family(), t.standard(), t.version());
            JsonNode rules = root.get("rules");
            if (rules == null || !rules.isObject())
            {
                continue;
            }
            for (Map.Entry<String, JsonNode> entry : rules.properties())
            {
                JsonNode rule = entry.getValue();
                if (rule == null || !rule.isObject())
                {
                    continue;
                }
                String coreId = ruleId(rule);
                for (String field : List.of("Check", "Precondition"))
                {
                    JsonNode checkNode = rule.get(field);
                    if (checkNode == null || checkNode.isNull())
                    {
                        continue;
                    }
                    ruleChecks++;
                    roundTrip(pkgName, coreId, field, checkNode, report, mismatches);
                }
            }
        }

        System.out.printf("[corpus] %d rule-checks (%d level entries); %d / %d units carried%n",
                ruleChecks, levelEntries, report.getConvertedLeaves(), report.getTotalLeaves());

        // Checked BEFORE the mismatch list: an empty mismatch list over an empty population is
        // not a pass, so the population has to be established first.
        assertTrue(report.getTotalLeaves() >= MIN_LEAVES_SCANNED,
                "converted Check population collapsed to " + report.getTotalLeaves() + " (floor "
                        + MIN_LEAVES_SCANNED + "; measured 14 281 at the flip) across " + ruleChecks
                        + " rule-checks. The carry-through assertions only mean"
                        + " something over a real population: at (near) zero they all hold"
                        + " trivially. This is a BROKEN MEASUREMENT, not a pass — the input has"
                        + " stopped carrying Checks. Fix the input, do not lower the floor.");

        if (!mismatches.isEmpty())
        {
            fail("Carry-through mismatches (" + mismatches.size() + "):\n"
                    + String.join("\n", mismatches));
        }
    }


    /**
     * ⛔ The teeth behind the per-level round-trip: a level map <b>must not bind</b> as a
     * {@link CheckCondition}, whatever the mapper's leniency.
     *
     * <p>
     * {@link #MAPPER} disables {@code FAIL_ON_UNKNOWN_PROPERTIES} — it has to, the corpus carries
     * keys this model does not model — so the level map used to bind silently rather than throwing,
     * and two silently-bound nodes compare equal. The refusal now lives in
     * {@code CheckConditionDeserializer} itself, which is the only place it cannot be configured
     * away.
     * </p>
     */
    @Test
    void aLevelMapCannotBindAsACondition() throws IOException
    {
        JsonNode levelMap = MAPPER.readTree("{\"ERROR\":{\"expression\":\"empty(AETERM)\"},"
                + "\"INFO\":{\"expression\":\"empty(AEDECOD)\"}}");
        assertTrue(CheckLevelNodes.isLevelMap(levelMap), "fixture must be a level map");

        Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> MAPPER.treeToValue(levelMap, CheckCondition.class),
                "a level map bound silently as a condition, which compares equal to any other"
                        + " silently-bound node — the round-trip then passes having checked"
                        + " nothing");
        assertTrue(thrown.getMessage() != null && thrown.getMessage().contains("ERROR"),
                () -> "the refusal names the offending level(s): " + thrown.getMessage());

        // ...and each level's own condition still binds normally, which is what roundTripLevels
        // relies on.
        org.junit.jupiter.api.Assertions
                .assertNotNull(MAPPER.treeToValue(levelMap.get("ERROR"), CheckCondition.class));
    }


    /**
     * Converts one {@code Check}/{@code Precondition} node through the production converter, then
     * asserts the converted output carries the authored expression verbatim and parses to the
     * identical {@link Expr}.
     */
    private static void roundTrip(String pkg, String ruleId, String field, JsonNode checkNode,
            ConversionReport report, List<String> mismatches)
    {
        JsonNode converted = RulePackageConverter.convertCheck(checkNode, report);
        if (CheckLevelNodes.isLevelMap(checkNode))
        {
            roundTripLevels(pkg, ruleId, field, checkNode, converted, mismatches);
            return;
        }
        compare(pkg, ruleId, field, checkNode, converted, mismatches);
    }


    /** Per-level round-trip of a level-keyed {@code Check} against its per-level conversion. */
    private static void roundTripLevels(String pkg, String ruleId, String field, JsonNode checkNode,
            JsonNode converted, List<String> mismatches)
    {
        if (!CheckLevelNodes.isLevelMap(converted))
        {
            mismatches.add(pkg + " / " + ruleId + " / " + field
                    + " : the converter turned a level map into `" + converted + "`");
            return;
        }
        for (Map.Entry<String, JsonNode> level : checkNode.properties())
        {
            JsonNode convertedLevel = converted.get(level.getKey());
            if (convertedLevel == null)
            {
                mismatches.add(pkg + " / " + ruleId + " / " + field + " : the converter dropped"
                        + " check level " + level.getKey());
                continue;
            }
            levelEntries++;
            compare(pkg, ruleId, field + "[" + level.getKey() + "]",
                    withoutMessage(level.getValue()), withoutMessage(convertedLevel), mismatches);
        }
    }


    /** The {@code Message} key stripped — it is reporting prose, not part of the condition. */
    private static JsonNode withoutMessage(JsonNode condition)
    {
        if (!(condition instanceof ObjectNode object) || !object.has(CheckLevelNodes.MESSAGE_KEY))
        {
            return condition;
        }
        ObjectNode stripped = object.deepCopy();
        stripped.remove(CheckLevelNodes.MESSAGE_KEY);
        return stripped;
    }


    /**
     * The verbatim + parse-equal comparison. Since the flip the corpus authors a single
     * {@code {"expression": …}} node per Check (or per level); anything else reaching this method
     * is itself a finding — a non-expression shape in the assembled corpus is exactly what D121
     * makes a load-time error.
     */
    private static void compare(String pkg, String ruleId, String field, JsonNode originalNode,
            JsonNode convertedNode, List<String> mismatches)
    {
        String where = pkg + " / " + ruleId + " / " + field;
        String original = expressionText(originalNode);
        if (original == null)
        {
            mismatches.add(where + " : the assembled Check is not a single expression node: `"
                    + originalNode + "` — under D121 a non-expression Check is an incorrect rule");
            return;
        }
        String converted = expressionText(convertedNode);
        if (converted == null)
        {
            mismatches.add(where + " : the converter turned an expression Check into `"
                    + convertedNode + "`");
            return;
        }
        if (!original.equals(converted))
        {
            mismatches.add(where + " : the carry-through rewrote the authored text: <" + original
                    + "> -> <" + converted + ">");
            return;
        }
        Expr parsedOriginal;
        try
        {
            parsedOriginal = CheckExpressionParser.parse(original);
        }
        catch (RuntimeException ex)
        {
            mismatches.add(where + " : the authored expression does not parse: " + ex.getMessage());
            return;
        }
        Expr parsedConverted = CheckExpressionParser.parse(converted);
        if (!parsedOriginal.equals(parsedConverted))
        {
            mismatches.add(where + " : byte-equal texts parsed to different IR — the parser is"
                    + " non-deterministic, which every instrument downstream assumes it is not");
        }
    }


    private static String expressionText(JsonNode node)
    {
        return node != null && node.isObject() && node.size() == 1 && node.hasNonNull("expression")
                ? node.get("expression").asText()
                : null;
    }


    private static String ruleId(JsonNode rule)
    {
        JsonNode core = rule.get("Core");
        if (core != null && core.hasNonNull("Id"))
        {
            return core.get("Id").asText();
        }
        return rule.hasNonNull("id") ? rule.get("id").asText() : "<no-id>";
    }
}
