package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.convert.RulePackageConverter.ConversionReport;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionConstant;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.CheckConditionNot;
import org.junit.jupiter.api.Test;

/**
 * The real round-trip guard for the combining converter: for every canonical package the
 * {@link RuleStorageAssembler} builds in memory from {@code rules-src/}, every rule's {@code Check}
 * (and {@code Precondition}) is run through {@link RulePackageConverter#convertCheck} — the
 * production path — then the <em>converted output</em> is deserialized back to a
 * {@link CheckCondition} (parse + lower, the same path the engine uses) and asserted
 * <strong>semantically equal</strong> to the original.
 *
 * <p>
 * <b>Why the assembler and not a corpus directory.</b> The assembler's canonical node is the same
 * tree the forward converter consumes on the native branch (see {@link RuleCorpusGenerator}), so it
 * is the authentic input rather than a stand-in. Since the flip (wave 43) that input is
 * expression-form — {@code convertExisting} keeps each Check verbatim, and the round-trip proves
 * every assembled expression parses and lowers semantically equal through the production path; the
 * per-leaf conversion coverage moved to {@code CheckToExprForwardTableTest}.
 * {@link #MIN_LEAVES_SCANNED} is the explicit guard against silent vacuity.
 * </p>
 *
 * <p>
 * The guarantee is semantic, not structural: combining re-associates and flattens {@code and}/
 * {@code or} and collapses a single-element {@code all}/{@code any} to its bare element, so the
 * comparison ({@link #cmp}) normalises both sides — unwrapping single-element composites,
 * flattening nested same-kind composites, and comparing {@code all}/{@code any} children as
 * unordered multisets — and compares leaves via {@link #semanticDiff}. The obligation applies only
 * to conditions the converter produced from operator-leaf form; hand-authored / native-only
 * expressions are out of scope. All mismatches are accumulated and the test fails once with a
 * diagnosable list.
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
     * one unit per Check — measured {@code 14 281} at the flip. The per-leaf conversion teeth moved
     * to the synthetic {@code CheckToExprForwardTableTest}; what this corpus sweep still proves is
     * that every assembled expression parses, lowers and compares semantically equal through the
     * production path. The floor is set well below the measured figure so ordinary corpus churn
     * does not trip it, but a collapse to (near) zero does.
     *
     * <p>
     * ⚠ Without this, an empty input makes every assertion in
     * {@link #everyConvertedCheckRoundTripsSemanticallyAcrossTheCorpus} hold trivially and the gate
     * goes green having verified nothing. That is a broken measurement, not a pass.
     * </p>
     */
    private static final int MIN_LEAVES_SCANNED = 12_000;

    /**
     * Level entries (rule &#215; declared check level) this run actually round-tripped. Reported
     * next to the leaf population so the level arm's reach is <b>re-derivable on every run</b>
     * rather than asserted against a figure that rots; the arm's teeth are
     * {@link #aLevelMapCannotBindAsACondition}, which does not depend on the corpus carrying a
     * level map at all.
     */
    private static int levelEntries;

    @Test
    void everyConvertedCheckRoundTripsSemanticallyAcrossTheCorpus() throws IOException
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
                    // No exemptions: the converter's two special-case registries (curated
                    // whole-Check by Core.Id; the does_not_equal_string_part leaf signature) were
                    // retired with the conversion machinery (PLAN-retire-corpus-transforms.md
                    // phase 1) — the formerly-curated texts are authored directly, so every Check
                    // round-trips through the same carry-through path.
                    ruleChecks++;
                    roundTrip(pkgName, coreId, field, checkNode, report, mismatches);
                }
            }
        }

        System.out.printf(
                "[corpus] %d rule-checks (%d level entries); converted %d / %d leaves (%.1f%%)%n",
                ruleChecks, levelEntries, report.getConvertedLeaves(), report.getTotalLeaves(),
                report.getTotalLeaves() == 0 ? 0.0
                        : 100.0 * report.getConvertedLeaves() / report.getTotalLeaves());

        // Checked BEFORE the mismatch list: an empty mismatch list over an empty leaf population is
        // not a pass, so the population has to be established first.
        assertTrue(report.getTotalLeaves() >= MIN_LEAVES_SCANNED,
                "converted Check population collapsed to " + report.getTotalLeaves() + " (floor "
                        + MIN_LEAVES_SCANNED + "; measured 14 281 at the flip) across " + ruleChecks
                        + " rule-checks. The round-trip assertions only mean"
                        + " something over a real population: at (near) zero they all hold"
                        + " trivially. This is a BROKEN MEASUREMENT, not a pass — the input has"
                        + " stopped carrying Checks. Fix the input, do not lower the floor.");

        if (!mismatches.isEmpty())
        {
            fail("Semantic round-trip mismatches (" + mismatches.size() + "):\n"
                    + String.join("\n", mismatches));
        }
    }

    // ---- focused unit tests for the normalising comparison ----------------


    @Test
    void singleElementAllUnwrapsToEqualItsBareChild() throws IOException
    {
        CheckCondition a = leaf("{\"name\":\"A\",\"operator\":\"var_exists\"}");
        org.junit.jupiter.api.Assertions.assertNull(cmp(new CheckConditionAll(List.of(a)), a));
        org.junit.jupiter.api.Assertions.assertNull(cmp(new CheckConditionAny(List.of(a)), a));
    }


    @Test
    void nestedSameKindCompositesFlatten() throws IOException
    {
        CheckCondition a = leaf("{\"name\":\"A\",\"operator\":\"var_exists\"}");
        CheckCondition b = leaf("{\"name\":\"B\",\"operator\":\"var_exists\"}");
        CheckCondition c = leaf("{\"name\":\"C\",\"operator\":\"var_exists\"}");
        CheckCondition nested = new CheckConditionAll(
                List.of(a, new CheckConditionAll(List.of(b, c))));
        CheckCondition flat = new CheckConditionAll(List.of(a, b, c));
        org.junit.jupiter.api.Assertions.assertNull(cmp(nested, flat));
    }


    @Test
    void reorderedChildrenAreEqual() throws IOException
    {
        CheckCondition a = leaf("{\"name\":\"A\",\"operator\":\"var_exists\"}");
        CheckCondition b = leaf("{\"name\":\"B\",\"operator\":\"var_exists\"}");
        org.junit.jupiter.api.Assertions.assertNull(
                cmp(new CheckConditionAny(List.of(a, b)), new CheckConditionAny(List.of(b, a))));
    }


    @Test
    void genuineDifferencesAreRejected() throws IOException
    {
        CheckCondition a = leaf("{\"name\":\"A\",\"operator\":\"var_exists\"}");
        CheckCondition b = leaf("{\"name\":\"B\",\"operator\":\"var_exists\"}");
        // all vs any over the same children
        org.junit.jupiter.api.Assertions.assertNotNull(
                cmp(new CheckConditionAll(List.of(a, b)), new CheckConditionAny(List.of(a, b))));
        // a changed leaf
        org.junit.jupiter.api.Assertions.assertNotNull(cmp(a, b));
    }


    @Test
    void valueIsReferenceTwoHopIsNotEqualToSingleHopReference() throws IOException
    {
        // The two-hop (value_is_reference:true -> colref) must NOT compare equal to a flag-less
        // single-hop reference: this is exactly the distinction the converter used to drop. Guards
        // both the tightened semanticDiff axis and the exprEquivalent rescue (colref vs bare ref).
        CheckCondition twoHop = leaf("{\"name\":\"IDVARVAL\",\"operator\":\"not_equal_to\","
                + "\"value\":\"IDVAR\",\"value_is_reference\":true}");
        CheckCondition singleHop = leaf(
                "{\"name\":\"IDVARVAL\",\"operator\":\"not_equal_to\",\"value\":\"IDVAR\"}");
        org.junit.jupiter.api.Assertions.assertNotNull(cmp(twoHop, singleHop));
        // ...and the two-hop round-trips to itself (sanity: the tightening is not over-strict).
        org.junit.jupiter.api.Assertions.assertNull(cmp(twoHop, twoHop));
    }


    @Test
    void structuralNotOverNegationPairLeafIsEquivalentToBareNegativeLeaf() throws IOException
    {
        // A structural `not` over the positive operator round-trips to the bare negative leaf;
        // the Expr-IR fallback in cmp() must treat the two as equivalent.
        CheckCondition structuralNot = leaf("{\"not\":{\"name\":\"USUBJID\","
                + "\"operator\":\"present_on_multiple_rows_within\",\"within\":\"DOMAIN\"}}");
        CheckCondition negativeLeaf = leaf("{\"name\":\"USUBJID\","
                + "\"operator\":\"not_present_on_multiple_rows_within\",\"within\":\"DOMAIN\"}");
        org.junit.jupiter.api.Assertions.assertNull(cmp(structuralNot, negativeLeaf));
        // ... and the fallback must NOT mask a genuine difference between two negation-pair leaves.
        CheckCondition otherName = leaf("{\"name\":\"STUDYID\","
                + "\"operator\":\"not_present_on_multiple_rows_within\",\"within\":\"DOMAIN\"}");
        org.junit.jupiter.api.Assertions.assertNotNull(cmp(structuralNot, otherName));
    }


    /**
     * ⛔ The teeth behind the per-level round-trip: a level map <b>must not bind</b> as a
     * {@link CheckCondition}, whatever the mapper's leniency.
     *
     * <p>
     * {@link #MAPPER} disables {@code FAIL_ON_UNKNOWN_PROPERTIES} — it has to, the corpus carries
     * leaf keys this model does not model — so the level map used to bind to an all-null
     * {@code CheckConditionLeaf} rather than throwing, and two all-null leaves compare equal. The
     * refusal now lives in {@code CheckConditionDeserializer} itself, which is the only place it
     * cannot be configured away.
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
                "a level map bound silently to an ALL-NULL leaf, which compares equal to any other"
                        + " all-null leaf — the round-trip then passes having checked nothing");
        assertTrue(thrown.getMessage() != null && thrown.getMessage().contains("ERROR"),
                () -> "the refusal names the offending level(s): " + thrown.getMessage());

        // ...and each level's own condition still binds normally, which is what roundTripLevels
        // relies on.
        org.junit.jupiter.api.Assertions
                .assertNotNull(MAPPER.treeToValue(levelMap.get("ERROR"), CheckCondition.class));
    }


    private static CheckCondition leaf(String json) throws IOException
    {
        return MAPPER.treeToValue(MAPPER.readTree(json), CheckCondition.class);
    }


    /**
     * Converts one {@code Check}/{@code Precondition} node through the production converter, then
     * deserializes the converted output and asserts it is semantically equal to the original.
     *
     * <p>
     * ⛔⛔ <b>A level-keyed {@code Check} is compared LEVEL BY LEVEL.</b> Before this arm the whole
     * level map was handed to {@code MAPPER.treeToValue(…, CheckCondition.class)} — a node with no
     * {@code all}/{@code any}/{@code not}/{@code expression} key, so it fell through to the leaf
     * branch, and because {@link #MAPPER} disables {@code FAIL_ON_UNKNOWN_PROPERTIES} it bound to
     * an <b>all-null {@code CheckConditionLeaf}</b>: sixteen null fields. Both sides bound to the
     * same all-null leaf, {@link #cmp} compared null to null, and the round-trip passed on all 33
     * level entries of the 9 level-keyed rules <em>having verified nothing</em>.
     * {@code CheckConditionDeserializer} now refuses that binding outright (see
     * {@link #aLevelMapCannotBindAsACondition}); this arm is what gives those entries a real
     * round-trip instead of merely a loud failure.
     * </p>
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


    /** Binds both sides and records a semantic difference, if any. */
    private static void compare(String pkg, String ruleId, String field, JsonNode originalNode,
            JsonNode convertedNode, List<String> mismatches)
    {
        CheckCondition original;
        try
        {
            original = MAPPER.treeToValue(originalNode, CheckCondition.class);
        }
        catch (RuntimeException | IOException ex)
        {
            mismatches.add(pkg + " / " + ruleId + " / " + field + " : original deserialize threw: "
                    + ex.getMessage());
            return;
        }
        CheckCondition roundTripped;
        try
        {
            roundTripped = MAPPER.treeToValue(convertedNode, CheckCondition.class);
        }
        catch (RuntimeException | IOException ex)
        {
            mismatches
                    .add(pkg + " / " + ruleId + " / " + field + " : converted re-deserialize threw"
                            + " on `" + convertedNode + "` : " + ex.getMessage());
            return;
        }
        String diff = cmp(original, roundTripped);
        if (diff != null)
        {
            mismatches.add(pkg + " / " + ruleId + " / " + field + " : " + diff + " (converted=`"
                    + convertedNode + "`)");
        }
    }

    // ---- semantic comparison ---------------------------------------------


    /**
     * Returns {@code null} when {@code a} and {@code b} are semantically equal after normalisation
     * (single-element {@code all}/{@code any} unwrapped, nested same-kind composites flattened,
     * {@code all}/{@code any} children compared as unordered multisets, leaves compared via
     * {@link #semanticDiff}), or a human-readable description of the first difference.
     *
     * @param a
     *            the original condition
     * @param b
     *            the round-tripped condition
     * @return {@code null} when semantically equal, else a description of the first difference
     */
    static String cmp(CheckCondition a, CheckCondition b)
    {
        String diff = cmpNormalized(normalize(a), normalize(b));
        if (diff == null)
        {
            return null;
        }
        // The structural compare found a difference. Rescue trees that are semantically equal but
        // differ in a shape the structural compare cannot see — e.g. a structural `not` wrapping a
        // negation-pair operator (present_on_multiple_rows_within, has_next_corresponding_record,
        // …) lowers to the bare negative leaf, so an original CheckConditionNot round-trips to a
        // CheckConditionLeaf. Comparing the Expr IR settles it: CheckToExpr maps both the negative
        // operator and `not <positive>` to the identical `Not(positive(…))`. Only reached on a
        // structural mismatch, so it never weakens the proven leaf-level comparison.
        return exprEquivalent(a, b) ? null : diff;
    }


    /**
     * Whether two conditions raise to the same (normalised) {@link Expr} IR. Returns {@code false}
     * if either has no expression surface (a kept old-style leaf), in which case the structural
     * comparison stands.
     */
    private static boolean exprEquivalent(CheckCondition a, CheckCondition b)
    {
        Expr ea;
        Expr eb;
        try
        {
            ea = CheckToExpr.toExpr(a);
            eb = CheckToExpr.toExpr(b);
        }
        catch (ExpressionException ex)
        {
            return false;
        }
        return exprDiff(normExpr(ea), normExpr(eb)) == null;
    }


    /** Flattens nested same-kind {@code And}/{@code Or} and unwraps single-element ones. */
    private static Expr normExpr(Expr e)
    {
        return switch (e)
        {
        case Expr.And and -> flattenExpr(and.parts(), true);
        case Expr.Or or -> flattenExpr(or.parts(), false);
        case Expr.Not not -> new Expr.Not(normExpr(not.inner()));
        default -> e;
        };
    }


    private static Expr flattenExpr(List<Expr> parts, boolean and)
    {
        List<Expr> flat = new ArrayList<>();
        for (Expr p : parts)
        {
            Expr n = normExpr(p);
            if (and && n instanceof Expr.And inner)
            {
                flat.addAll(inner.parts());
            }
            else if (!and && n instanceof Expr.Or inner)
            {
                flat.addAll(inner.parts());
            }
            else
            {
                flat.add(n);
            }
        }
        if (flat.size() == 1)
        {
            return flat.get(0);
        }
        return and ? new Expr.And(flat) : new Expr.Or(flat);
    }


    /** Returns {@code null} when two normalised {@link Expr} trees are equal (And/Or unordered). */
    private static String exprDiff(Expr a, Expr b)
    {
        return switch (a)
        {
        case Expr.And and -> b instanceof Expr.And bAnd ? exprMultiset(and.parts(), bAnd.parts())
                : "expr type";
        case Expr.Or or -> b instanceof Expr.Or bOr ? exprMultiset(or.parts(), bOr.parts())
                : "expr type";
        case Expr.Not not -> b instanceof Expr.Not bNot ? exprDiff(not.inner(), bNot.inner())
                : "expr type";
        default -> a.equals(b) ? null : "expr leaf";
        };
    }


    private static String exprMultiset(List<Expr> as, List<Expr> bs)
    {
        if (as.size() != bs.size())
        {
            return "expr arity";
        }
        List<Expr> remaining = new ArrayList<>(bs);
        for (Expr a : as)
        {
            boolean matched = false;
            for (Iterator<Expr> it = remaining.iterator(); it.hasNext();)
            {
                if (exprDiff(a, it.next()) == null)
                {
                    it.remove();
                    matched = true;
                    break;
                }
            }
            if (!matched)
            {
                return "expr unmatched";
            }
        }
        return null;
    }


    /** Compares two already-{@link #normalize}d conditions. */
    private static String cmpNormalized(CheckCondition a, CheckCondition b)
    {
        return switch (a)
        {
        case CheckConditionAll all -> b instanceof CheckConditionAll bAll
                ? multisetDiff(all.getConditions(), bAll.getConditions(), "all")
                : typeMismatch(a, b);
        case CheckConditionAny any -> b instanceof CheckConditionAny bAny
                ? multisetDiff(any.getConditions(), bAny.getConditions(), "any")
                : typeMismatch(a, b);
        case CheckConditionNot not -> b instanceof CheckConditionNot bNot
                ? cmpNormalized(not.getCondition(), bNot.getCondition())
                : typeMismatch(a, b);
        case CheckConditionLeaf leaf -> b instanceof CheckConditionLeaf bLeaf
                ? semanticDiff(leaf, bLeaf)
                : typeMismatch(a, b);
        case CheckConditionConstant con -> b instanceof CheckConditionConstant bCon
                ? (con.value() == bCon.value() ? null
                        : "constant " + con.value() + " != " + bCon.value())
                : typeMismatch(a, b);
        case CheckConditionExpression ex -> b instanceof CheckConditionExpression bEx
                ? (java.util.Objects.equals(ex.source(), bEx.source()) ? null
                        : "expression source `" + ex.source() + "` != `" + bEx.source() + "`")
                : typeMismatch(a, b);
        };
    }


    /**
     * Flattens nested same-kind composites ({@code all}-in-{@code all}, {@code any}-in-{@code any})
     * and unwraps a single-element {@code all}/{@code any} to its sole child — the canonical form
     * combining + printing produces (a one-element {@code And}/{@code Or} prints with no
     * {@code and}/{@code or} glue and re-lowers to a bare leaf).
     */
    static CheckCondition normalize(CheckCondition c)
    {
        return switch (c)
        {
        case CheckConditionAll all -> flatten(all.getConditions(), true);
        case CheckConditionAny any -> flatten(any.getConditions(), false);
        case CheckConditionNot not -> new CheckConditionNot(normalize(not.getCondition()));
        default -> c; // leaf / constant / native-only expression
        };
    }


    private static CheckCondition flatten(List<CheckCondition> children, boolean all)
    {
        List<CheckCondition> flat = new ArrayList<>();
        for (CheckCondition child : children)
        {
            CheckCondition n = normalize(child);
            if (all && n instanceof CheckConditionAll inner)
            {
                flat.addAll(inner.getConditions());
            }
            else if (!all && n instanceof CheckConditionAny inner)
            {
                flat.addAll(inner.getConditions());
            }
            else
            {
                flat.add(n);
            }
        }
        if (flat.size() == 1)
        {
            return flat.get(0);
        }
        return all ? new CheckConditionAll(flat) : new CheckConditionAny(flat);
    }


    /** Compares two normalised child lists as unordered multisets. */
    private static String multisetDiff(List<CheckCondition> as, List<CheckCondition> bs,
            String label)
    {
        if (as.size() != bs.size())
        {
            return label + " arity " + as.size() + " != " + bs.size();
        }
        List<CheckCondition> remaining = new ArrayList<>(bs);
        for (CheckCondition a : as)
        {
            boolean matched = false;
            for (Iterator<CheckCondition> it = remaining.iterator(); it.hasNext();)
            {
                if (cmpNormalized(a, it.next()) == null)
                {
                    it.remove();
                    matched = true;
                    break;
                }
            }
            if (!matched)
            {
                return label + " has an unmatched child";
            }
        }
        return null;
    }


    private static String typeMismatch(CheckCondition a, CheckCondition b)
    {
        return "type " + a.getClass().getSimpleName() + " != " + b.getClass().getSimpleName();
    }


    /**
     * Returns {@code null} when the two leaves are semantically equal (same operator, name, value,
     * and effective literalness), or a human-readable description of the first difference.
     */
    private static String semanticDiff(CheckConditionLeaf a, CheckConditionLeaf b)
    {
        if (!java.util.Objects.equals(a.getOperator(), b.getOperator()))
        {
            return "operator " + a.getOperator() + " != " + b.getOperator();
        }
        if (!java.util.Objects.equals(a.getName(), b.getName()))
        {
            return "name " + a.getName() + " != " + b.getName();
        }
        // The case-insensitive membership operators (Agreed change #3) compare their value list
        // case-insensitively at runtime — the operator uppercases both the cell and the value set
        // (Locale.ROOT). The converter now pre-uppercases the literal terms, so lowering recovers
        // an
        // uppercased value list whose case differs from the original (mixed-case) one. That is a
        // representational difference with no behavioural effect for these operators, so the values
        // are compared case-insensitively here; a genuine difference (a changed term) is still
        // caught. Not a weakening of the general comparison — it is scoped to the two operators
        // whose value semantics are intrinsically case-insensitive.
        boolean ciValue = isCaseInsensitiveMembershipOperator(a.getOperator());
        if (ciValue ? !valuesEqualIgnoreCase(a.getValue(), b.getValue())
                : !valuesEqual(a.getValue(), b.getValue()))
        {
            return "value " + a.getValue() + " != " + b.getValue();
        }
        if (effectiveLiteral(a) != effectiveLiteral(b))
        {
            return "effective literalness " + effectiveLiteral(a) + " != " + effectiveLiteral(b);
        }
        if (!valuesEqual(a.getWithin(), b.getWithin()))
        {
            return "within " + a.getWithin() + " != " + b.getWithin();
        }
        if (!java.util.Objects.equals(a.getOrdering(), b.getOrdering()))
        {
            return "ordering " + a.getOrdering() + " != " + b.getOrdering();
        }
        if (!java.util.Objects.equals(a.getRegex(), b.getRegex()))
        {
            return "regex " + a.getRegex() + " != " + b.getRegex();
        }
        if (!java.util.Objects.equals(a.getPrefix(), b.getPrefix()))
        {
            return "prefix " + a.getPrefix() + " != " + b.getPrefix();
        }
        if (!java.util.Objects.equals(a.getSuffix(), b.getSuffix()))
        {
            return "suffix " + a.getSuffix() + " != " + b.getSuffix();
        }
        if (!java.util.Objects.equals(a.getNegative(), b.getNegative()))
        {
            return "negative " + a.getNegative() + " != " + b.getNegative();
        }
        if (!java.util.Objects.equals(a.getTypeInsensitive(), b.getTypeInsensitive()))
        {
            return "typeInsensitive " + a.getTypeInsensitive() + " != " + b.getTypeInsensitive();
        }
        if (referenceFlag(a) != referenceFlag(b))
        {
            return "value_is_reference " + referenceFlag(a) + " != " + referenceFlag(b);
        }
        return null;
    }


    /**
     * The {@code value_is_reference} flag as an operand attribute — the two-hop dereference
     * (lowered/emitted as {@code colref(...)}). Distinct from {@link #effectiveLiteral}'s
     * literal-vs-reference axis: a flag-less single-hop reference and a {@code value_is_reference}
     * two-hop differ here. Operators whose value is intrinsically a literal (regex / substring)
     * never resolve a reference, so the flag is inert for them and ignored.
     *
     * @param leaf
     *            the leaf to inspect
     * @return {@code true} when the leaf carries an effective two-hop {@code value_is_reference}
     */
    private static boolean referenceFlag(CheckConditionLeaf leaf)
    {
        if (isRegexOperator(leaf.getOperator()) || isSubstringOperator(leaf.getOperator()))
        {
            return false;
        }
        return Boolean.TRUE.equals(leaf.getValueIsReference());
    }


    /**
     * Compares two value nodes for semantic equality. Numbers are compared by magnitude (so an
     * {@code IntNode 0} equals a {@code LongNode 0} — lowering normalises integral literals to a
     * {@code long} node, a representational difference with no behavioural effect); arrays compare
     * element-wise the same way; everything else falls back to {@link JsonNode#equals(Object)}.
     *
     * @param a
     *            the original value node (may be {@code null})
     * @param b
     *            the round-tripped value node (may be {@code null})
     * @return {@code true} when the two are semantically equal
     */
    private static boolean valuesEqual(JsonNode a, JsonNode b)
    {
        if (a == null || b == null)
        {
            return a == null && b == null;
        }
        if (a.isNumber() && b.isNumber())
        {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isArray() && b.isArray())
        {
            if (a.size() != b.size())
            {
                return false;
            }
            for (int i = 0; i < a.size(); i++)
            {
                if (!valuesEqual(a.get(i), b.get(i)))
                {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }


    /**
     * The effective literal/reference kind, normalised to match the engine default: a textual value
     * with neither {@code value_is_literal} nor {@code value_is_reference} set to TRUE is treated
     * as a reference (the engine's {@code ValueResolver} default), so a flag-less reference
     * round-trips to a flag-less reference. Non-textual values (number/array/bool) are always
     * literal, and the pattern operand of a regex operator is intrinsically a literal pattern (the
     * engine never resolves it as a column reference, regardless of any flag).
     *
     * @return {@code true} when the value is effectively a literal, {@code false} when effectively
     *         a reference
     */
    private static boolean effectiveLiteral(CheckConditionLeaf leaf)
    {
        JsonNode v = leaf.getValue();
        if (v == null || v.isNull())
        {
            return true;
        }
        if (!v.isTextual())
        {
            return true;
        }
        if (isRegexOperator(leaf.getOperator()) || isSubstringOperator(leaf.getOperator()))
        {
            return true;
        }
        boolean explicitLiteral = Boolean.TRUE.equals(leaf.getValueIsLiteral());
        boolean explicitReference = Boolean.TRUE.equals(leaf.getValueIsReference());
        if (explicitLiteral)
        {
            return true;
        }
        if (explicitReference)
        {
            return false;
        }
        // No flag on a textual value: engine treats it as a reference.
        return false;
    }


    /**
     * Returns {@code true} for the operators whose value operand is a regular-expression pattern
     * (always a literal, never a column reference).
     *
     * @param operator
     *            the leaf operator (may be {@code null})
     * @return {@code true} when the operator carries a regex pattern value
     */
    private static boolean isRegexOperator(String operator)
    {
        if (operator == null)
        {
            return false;
        }
        return switch (operator)
        {
        case "matches_regex", "not_matches_regex", "prefix_matches_regex", "suffix_matches_regex", "not_prefix_matches_regex", "not_suffix_matches_regex" -> true;
        default -> false;
        };
    }


    /**
     * Returns {@code true} for the substring predicates, whose value operand the engine resolves as
     * a literal — it is intrinsically a literal substring regardless of any
     * {@code value_is_literal}/{@code value_is_reference} flag, so the converter emits it as a
     * quoted string literal and the (semantically irrelevant) flag must not count as a round-trip
     * difference.
     *
     * @param operator
     *            the leaf operator (may be {@code null})
     * @return {@code true} when the operator's value is an intrinsically-literal substring
     */
    private static boolean isSubstringOperator(String operator)
    {
        if (operator == null)
        {
            return false;
        }
        return switch (operator)
        {
        case "contains", "does_not_contain", "starts_with", "ends_with" -> true;
        default -> false;
        };
    }


    /**
     * Returns {@code true} for the case-insensitive membership operators (Agreed change #3), whose
     * value list the engine compares case-insensitively (it uppercases both the cell and the value
     * set with {@code Locale.ROOT}). The converter pre-uppercases the literal terms, so the
     * round-tripped value list differs from the original only in case — compared case-insensitively
     * by {@link #semanticDiff}.
     *
     * @param operator
     *            the leaf operator (may be {@code null})
     * @return {@code true} when the operator's value list is intrinsically case-insensitive
     */
    private static boolean isCaseInsensitiveMembershipOperator(String operator)
    {
        return "is_contained_by_case_insensitive".equals(operator)
                || "is_not_contained_by_case_insensitive".equals(operator);
    }


    /**
     * Like {@link #valuesEqual} but compares textual leaves (including array elements)
     * case-insensitively, for the case-insensitive membership operators.
     *
     * @param a
     *            the original value node (may be {@code null})
     * @param b
     *            the round-tripped value node (may be {@code null})
     * @return {@code true} when the two are semantically equal ignoring case on textual values
     */
    private static boolean valuesEqualIgnoreCase(JsonNode a, JsonNode b)
    {
        if (a == null || b == null)
        {
            return a == null && b == null;
        }
        if (a.isTextual() && b.isTextual())
        {
            return a.asText().equalsIgnoreCase(b.asText());
        }
        if (a.isArray() && b.isArray())
        {
            if (a.size() != b.size())
            {
                return false;
            }
            for (int i = 0; i < a.size(); i++)
            {
                if (!valuesEqualIgnoreCase(a.get(i), b.get(i)))
                {
                    return false;
                }
            }
            return true;
        }
        return valuesEqual(a, b);
    }


    private static String ruleId(JsonNode rule)
    {
        JsonNode core = rule.get("Core");
        if (core != null && core.isObject() && core.get("Id") != null && core.get("Id").isTextual())
        {
            return core.get("Id").asText();
        }
        JsonNode id = rule.get("id");
        return id != null && id.isTextual() ? id.asText() : "<unknown>";
    }

}
