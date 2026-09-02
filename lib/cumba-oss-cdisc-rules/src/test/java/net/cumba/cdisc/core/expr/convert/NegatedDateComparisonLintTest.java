package net.cumba.cdisc.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.stream.Stream;
import net.cumba.cdisc.core.CorpusPaths;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.ExpressionPrinter;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionAny;
import net.cumba.cdisc.core.model.CheckConditionConstant;
import net.cumba.cdisc.core.model.CheckConditionExpression;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.CheckConditionNot;
import net.cumba.cdisc.core.model.LevelCheck;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.report.Severity;
import org.junit.jupiter.api.Test;

/**
 * Corpus lint — <b>a hull-semantics {@code date_*} comparison must never be authored under a
 * negation</b> ({@code plans/done/PLAN-negated-date-comparison-lint.md}).
 *
 * <h2>Why</h2>
 * <p>
 * {@code Fix #193} made the {@code date_*} comparison family <b>uniform &forall;</b>:
 * {@code A op B} holds only if it holds against <em>every</em> candidate of a partial date's hull
 * ({@code Primitives.dateComparison} &rarr; {@code IsoDateComparison}). A Check leaf is a
 * <em>failure condition</em>, not a logical dual, so negating a &forall; yields an <b>&exist;</b> —
 * <em>"there exists a completion for which the comparison holds"</em> — and the rule then fires on
 * a <em>possibility</em> rather than on an established violation. With {@code MHSTDTC = 2026-01}
 * and {@code RFSTDTC = 2026-01-17}, the &exist; reading raises an error because
 * {@code 2026-01-31 >= 2026-01-17}, even though the history may have started on 01-05.
 * </p>
 * <p>
 * The loose reading is still expressible — {@code Fix #193} shipped the per-cell builtins
 * {@code earliest_possible(X)} / {@code latest_possible(X)}, which reduce a partial date to a
 * <em>point</em> so the comparison is unambiguous. &#9888; The comparison over those points must
 * itself carry the {@code date()} tag — {@code date(latest_possible(X)) >= earliest_possible(Y)} —
 * because an untagged comparison compiles to the numeric-only plain form and never fires on an ISO
 * string ({@code HullBoundsBareOperandProbeTest}, phase 5b step 0). An author who wants "there
 * exists a completion" writes it down; the lint points at that escape hatch in its failure message.
 * </p>
 *
 * <h2>Scope — which operators, decided from the compiler, not from the enum name</h2>
 * <p>
 * {@code CheckOperator} declares <b>ten</b> date/time comparison operators, all of which
 * {@code CheckToExpr.dateCmp} raises to a tagged binary comparison. They split in
 * {@code ExprCompiler.compileTagged}:
 * </p>
 * <ul>
 * <li><b>In scope (six)</b> — {@code date_equal_to}, {@code date_not_equal_to},
 * {@code date_less_than}, {@code date_less_than_or_equal_to}, {@code date_greater_than},
 * {@code date_greater_than_or_equal_to}. These carry the {@code date(…)} tag and compile to
 * {@code Primitives.dateComparison}, which is the &forall;-over-the-hull comparison
 * {@code Fix #193} made uniform. Negating one of these is the &exist; over-report.</li>
 * <li><b>Out of scope (four)</b> — {@code date_part_equal_to}, {@code date_part_not_equal_to},
 * {@code time_part_equal_to}, {@code time_part_not_equal_to}. These carry the {@code date_part(…)}
 * / {@code time_part(…)} tag and compile to {@code Primitives.datePartComparison}, which compares
 * only the date or time portion and has <b>no hull &forall; reading at all</b> — so negating one
 * does not produce the over-report this lint exists to prevent. (This is the same split
 * {@code Fix #193} measured when it reported an affected set of 106 rules rather than 110.)</li>
 * </ul>
 * <p>
 * A <b>bare</b> {@code date_not_equal_to} is deliberately <em>not</em> a finding: it is a
 * first-class &forall; operator ("definitely different"), not {@code not(date_equal_to)}. Rejecting
 * it would reject hundreds of legitimate leaves. It is a finding only when it sits <em>under</em> a
 * negation, where the &forall; is inverted like any other.
 * </p>
 *
 * <h2>The two negation mechanisms are NOT equivalent — and each gets its own message</h2>
 * <ol>
 * <li><b>A structural {@code not:} node</b> ({@code CheckConditionDeserializer} &rarr;
 * {@link CheckConditionNot}) inverts every leaf beneath it, and {@code CheckToExpr} raises it to
 * {@code Expr.Not}, which the native backend compiles to a genuine bit-flip. <b>This is the live
 * &exist; over-report.</b></li>
 * <li><b>A per-leaf {@code negative:} flag</b> ({@link CheckConditionLeaf#getNegative()}) is
 * <em>not</em> a logical negation in coreJ. Its only evaluator consumer is
 * {@code invalid_duration}, where it means <em>allow a negative ISO-8601 duration</em>
 * ({@code ScalarSemantics.isInvalidDuration(s, allowNegative)}); on the affix operators it is one
 * more affix modifier. On <em>any</em> other operator — including every date comparison —
 * {@code CheckToExpr.rejectAffixFields} throws, so the rule keeps {@code checkExpr == null} and
 * {@code RuleRunner} answers a per-rule {@code ERROR} ("the Check has no native expression form;
 * the legacy evaluator has been retired"). It is therefore a <b>different defect</b> — a dead rule,
 * not an over-firing one — and is reported with a different message.</li>
 * </ol>
 *
 * <h2>Corpus views scanned — both, each named in its own count</h2>
 * <ul>
 * <li>{@code rules/rules-*.json} — the assembled <b>expression-form</b> packages, the corpus the
 * engine ships. ⚠ A walker keyed on a JSON {@code operator} field returns zero leaves here, because
 * the node shape is {@code {"expression": "date(X) < Y"}} — reporting that zero as a finding would
 * be a wrong-population error. This lint loads the packages instead, so
 * {@code CheckConditionDeserializer} parses the expression and {@code ExprLowering} lowers it back
 * to the operator-leaf AST; anything that has no legacy lowering arrives as a
 * {@link CheckConditionExpression} and is walked on the {@link Expr} IR by {@link #walkExpr}. The
 * view is therefore <b>measured</b>, not excluded.</li>
 * <li>{@code rules-src/checks/*.yaml} — the authoring view, read exactly as
 * {@code MixedGranularityLintTest} reads it, and the one that still exercises {@link #walk}'s
 * operator-leaf arm end to end. Scanning it catches the mistake at authoring time, before the
 * assembled corpus is regenerated.</li>
 * </ul>
 * <p>
 * ⚠ <b>A third view — the assembled operator-leaf packages — used to sit between these two, and
 * dropping it removed one comparison.</b> What is lost is the <em>assembled</em> leaf form: a
 * defect introduced by the assembler rather than by the author, and not surviving into the
 * expression form, is no longer observed. What is <em>not</em> lost is coverage of the leaf walker
 * itself — {@code rules-src} is authored in operator-leaf form, so {@link #walkLeaf} is still
 * driven over &gt;3 000 real rules, and its own {@code dateComparisonLeaves} floor of 50 still
 * fails loudly if that stops being true.
 * </p>
 * <p>
 * Every count below is a <b>Check-leaf</b> count within a named view — never a file count: one
 * {@code rules} file is a whole package holding hundreds of rules, so a file-level intersection is
 * noise.
 * </p>
 *
 * <h2>&#9733; Every declared check level is walked, not the strictest one</h2>
 * <p>
 * A rule's {@code Check:} may be a <b>map from ladder level to condition</b> (Plan C &#167;3.3),
 * and {@code Rule.getCheck()} is then the <em>strictest</em> level's condition alone. This lint
 * iterates {@code Rule.effectiveCheckLevels()} instead — see {@link #scanRule} for the measured
 * size of the hole that closes (40 shipped + 11 authored hull date comparisons that no view
 * walked). It matters more here than almost anywhere: the escape hatch this lint's failure message
 * recommends — {@code date(latest_possible(X)) >= earliest_possible(Y)} — is exactly what the 9
 * level-keyed rules author on their <em>weaker</em> rung.
 * </p>
 *
 * <h2>Anti-vacuity</h2>
 * <p>
 * The population is <b>zero</b>, so the corpus scan alone would pass without ever executing the
 * detector. Three independent assertions guard that:
 * </p>
 * <ol>
 * <li>the scan asserts a plausible rule count <em>and</em> a plausible count of date-comparison
 * leaves per view, so neither a broken path nor a broken operator match can masquerade as "no
 * findings";</li>
 * <li>{@link #structuralNotNodeOverDateComparisonIsReported},
 * {@link #negationInheritedThroughSubtreeIsReported},
 * {@link #negativeFlagOnDateComparisonIsReported} and {@link #expressionFormNegationIsReported}
 * build the forbidden shapes in-test and assert they are reported — for both mechanisms and both
 * AST forms;</li>
 * <li>{@link #nearMissesAreNotReported} and {@link #doubleNegationIsNotReported} assert the
 * detector stays silent on a bare {@code date_less_than}, a negated non-date leaf, a bare
 * {@code date_not_equal_to}, a negated {@code date_part_*} / {@code time_part_*} leaf, and a
 * doubly-negated date comparison — without which a detector that answered "everything is a
 * violation" would also pass.</li>
 * </ol>
 */
class NegatedDateComparisonLintTest
{

    /**
     * The six operators whose comparison is &forall;-over-the-hull: {@code CheckToExpr.dateCmp}
     * tags them {@code date(…)} and {@code ExprCompiler} compiles that tag to
     * {@code Primitives.dateComparison}. The four {@code date_part_*} / {@code time_part_*}
     * operators are deliberately absent — see the class javadoc.
     */
    private static final Set<String> HULL_DATE_OPERATORS = Set.of("date_equal_to",
            "date_not_equal_to", "date_less_than", "date_less_than_or_equal_to",
            "date_greater_than", "date_greater_than_or_equal_to");

    /**
     * The one operator that actually consumes {@code negative:} — {@code CheckToExpr:182}, where it
     * means <em>allow a negative ISO duration</em>. Every other operator falls through to
     * {@code rejectAffixFields} ({@code CheckToExpr:341}, called at {@code :208}), which throws, so
     * the rule keeps {@code checkExpr == null} and {@code RuleRunner} answers a per-rule
     * {@code ERROR}: the rule validates nothing at all.
     * <p>
     * Re-measured 2026-08-11 across both surviving views: <b>every</b> {@code negative:} in the
     * corpus sits on {@code invalid_duration} — {@code rules-src} carries 23 of them (3
     * {@code true}, 20 {@code false}) and {@code rules/} carries <b>none at all</b>, the expression
     * form having no {@code negative:} carriage. The population this guard protects is therefore
     * <b>0</b> — it is a guard against future authoring, which is exactly why its detector is
     * proved by in-test fixtures rather than by the corpus.
     * </p>
     */
    private static final String NEGATIVE_CONSUMING_OPERATOR = "invalid_duration";

    /** The value-function tag the six operators raise to in the {@link Expr} IR. */
    private static final String HULL_DATE_TAG = "date";

    /** The binary operators a {@code date(…)}-tagged comparison can carry. */
    private static final Set<Expr.BinOp> COMPARISON_OPS = Set.of(Expr.BinOp.EQ, Expr.BinOp.NEQ,
            Expr.BinOp.LT, Expr.BinOp.LE, Expr.BinOp.GT, Expr.BinOp.GE);

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    // ------------------------------------------------------------------
    // Corpus scan
    // ------------------------------------------------------------------

    @Test
    void corpusHasNoNegatedDateComparisons() throws IOException
    {
        Path basedir = Path.of(System.getProperty("projectBasedir"));

        Scan expressionView = scanPackages(CorpusPaths.rules(), "rules");
        Scan sourceView = scanSources(basedir.resolve("rules-src").resolve("checks"), "rules-src");
        List<Scan> views = List.of(expressionView, sourceView);

        for (Scan view : views)
        {
            // Re-derivable on every run: the plan's §2.3 population, per view, in leaves.
            System.out.println("[negated-date-comparison-lint] " + view);
        }

        for (Scan view : views)
        {
            assertTrue(view.rules > 3000, "corpus scan looks broken: only " + view.rules
                    + " rules loaded from the " + view.view + " view — expected the full corpus");
            assertTrue(view.dateComparisonLeaves > 50,
                    "the detector's operator match looks broken: only " + view.dateComparisonLeaves
                            + " hull date-comparison leaves found in the " + view.view + " view, "
                            + "out of " + view.leaves + " Check leaves in " + view.rules
                            + " rules — a lint that matches nothing cannot prove it detects "
                            + "anything");
        }

        List<String> structural = new ArrayList<>();
        List<String> negativeFlag = new ArrayList<>();
        for (Scan view : views)
        {
            structural.addAll(view.structuralNegation);
            negativeFlag.addAll(view.negativeFlag);
        }

        assertTrue(structural.isEmpty(),
                "a hull date_* comparison is authored under a negation (" + structural.size()
                        + "). Fix #193 made date_* comparison uniform ∀ (it holds only "
                        + "against every candidate of a partial date's hull); negating it yields "
                        + "∃, so the rule fires on a possibility rather than on an "
                        + "established violation. Express the loose reading explicitly instead, "
                        + "by comparing points under the date() tag: "
                        + "date(latest_possible(X)) >= earliest_possible(Y) (untagged, the "
                        + "comparison is numeric-only and never fires on ISO strings).\n"
                        + String.join("\n", structural));

        assertTrue(negativeFlag.isEmpty(),
                "a hull date_* comparison leaf carries `negative:` (" + negativeFlag.size()
                        + "). In coreJ `negative:` is not a logical negation — only "
                        + "invalid_duration consumes it (as \"allow a negative duration\") and the "
                        + "affix operators treat it as an affix modifier. On a date comparison "
                        + "CheckToExpr.rejectAffixFields throws, so the rule keeps checkExpr == "
                        + "null and RuleRunner answers a per-rule ERROR. Drop the flag; if a "
                        + "loose comparison was meant, compare points under the date() tag: "
                        + "date(latest_possible(X)) >= earliest_possible(Y).\n"
                        + String.join("\n", negativeFlag));

        List<String> dead = new ArrayList<>();
        for (Scan view : views)
        {
            dead.addAll(view.deadNegativeFlag);
        }

        assertTrue(dead.isEmpty(),
                "a `negative:` flag sits on an operator that cannot consume it (" + dead.size()
                        + "). THIS RULE DOES NOT RUN AT ALL — it is a different defect from the "
                        + "over-report above. Only " + NEGATIVE_CONSUMING_OPERATOR + " consumes "
                        + "`negative:` (CheckToExpr:182, \"allow a negative duration\"); on every "
                        + "other operator rejectAffixFields (CheckToExpr:341) throws, so "
                        + "checkExpr stays null and RuleRunner answers a per-rule ERROR instead of "
                        + "validating anything. Drop the flag and express the intent with the "
                        + "operator's own negative twin, or with a not: node.\n"
                        + String.join("\n", dead));
    }

    // ------------------------------------------------------------------
    // Anti-vacuity: the detector fires on both mechanisms and both AST forms
    // ------------------------------------------------------------------


    /** F1 / mechanism 1 — a {@code not:} node directly over a hull date comparison. */
    @Test
    void structuralNotNodeOverDateComparisonIsReported()
    {
        Scan scan = scanCheck(new CheckConditionNot(dateLeaf("date_less_than")));

        assertEquals(1, scan.structuralNegation.size(),
                "not(date_less_than) must be reported: " + scan.structuralNegation);
        assertTrue(scan.negativeFlag.isEmpty(),
                "a structural not: is not a `negative:` finding: " + scan.negativeFlag);
        assertEquals(1, scan.dateComparisonLeaves, "the date leaf must have been recognised");
    }


    /**
     * F2 — negation is <b>inherited</b> through a subtree: the {@code not:} wraps an {@code all:}
     * two levels above the date leaf, and the leaf itself carries no marker at all.
     */
    @Test
    void negationInheritedThroughSubtreeIsReported()
    {
        CheckCondition inner = new CheckConditionAny(
                List.of(CheckConditionLeaf.builder().name("MHSTDTC").operator("non_empty").build(),
                        dateLeaf("date_greater_than_or_equal_to")));
        CheckCondition subtree = new CheckConditionAll(List.of(inner));

        Scan scan = scanCheck(new CheckConditionNot(subtree));

        assertEquals(1, scan.structuralNegation.size(),
                "negation inherited through all:/any: must reach the leaf: "
                        + scan.structuralNegation);
    }


    /** F1 / mechanism 2 — the per-leaf {@code negative:} flag, with no {@code not:} anywhere. */
    @Test
    void negativeFlagOnDateComparisonIsReported()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("MHSTDTC")
                .operator("date_less_than").value(TextNode.valueOf("RFSTDTC"))
                .negative(Boolean.TRUE).build();

        Scan scan = scanCheck(new CheckConditionAll(List.of(leaf)));

        assertEquals(1, scan.negativeFlag.size(),
                "a `negative: true` date leaf must be reported: " + scan.negativeFlag);
        assertTrue(scan.structuralNegation.isEmpty(),
                "`negative:` is not a structural-negation finding: " + scan.structuralNegation);
        assertEquals(1, scan.deadNegativeFlag.size(),
                "a date leaf is also a non-consuming operator, so job 2 reports it too: "
                        + scan.deadNegativeFlag);
    }


    /**
     * Job 2, the wider half — a {@code negative:} on an operator with nothing to do with dates is
     * <b>equally dead</b>, and was invisible until 2026-08-10 because the reporting sat behind the
     * hull-date filter.
     */
    @Test
    void negativeFlagOnANonDateOperatorIsReported()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("AESEV").operator("equal_to")
                .value(TextNode.valueOf("SEVERE")).negative(Boolean.TRUE).build();

        Scan scan = scanCheck(new CheckConditionAll(List.of(leaf)));

        assertEquals(1, scan.deadNegativeFlag.size(),
                "a `negative: true` on equal_to must be reported — rejectAffixFields throws on it, "
                        + "so the rule answers a per-rule ERROR: " + scan.deadNegativeFlag);
        assertTrue(scan.negativeFlag.isEmpty(),
                "equal_to is not a hull date operator, so job 1 must stay silent: "
                        + scan.negativeFlag);
        assertTrue(scan.structuralNegation.isEmpty(),
                "no structural negation is present: " + scan.structuralNegation);
    }


    /**
     * ⚑ The load-bearing near-miss: {@code invalid_duration} is the <b>only</b> operator that
     * consumes {@code negative:} ({@code CheckToExpr:182}), and every one of the corpus's flags
     * sits on it. If this control ever inverts, the lint reds on the 23 correct {@code rules-src}
     * leaves that carry one.
     */
    @Test
    void negativeFlagOnInvalidDurationIsNotReported()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("AEDUR")
                .operator(NEGATIVE_CONSUMING_OPERATOR).negative(Boolean.TRUE).build();

        Scan scan = scanCheck(new CheckConditionAll(List.of(leaf)));

        assertTrue(scan.deadNegativeFlag.isEmpty(),
                "invalid_duration CONSUMES `negative:` — reporting it would red the lint on the "
                        + "23 correct rules-src leaves that carry it: " + scan.deadNegativeFlag);
        assertTrue(scan.negativeFlag.isEmpty(),
                "invalid_duration is not a hull date operator: " + scan.negativeFlag);
    }


    /**
     * The {@code rules/} view's node shape: an expression-form Check that has no legacy lowering
     * stays a {@link CheckConditionExpression}, so the detector must also walk the {@link Expr} IR.
     */
    @Test
    void expressionFormNegationIsReported()
    {
        String source = "not (date(MHSTDTC) < RFSTDTC)";
        Expr expr = CheckExpressionParser.parse(source);

        Scan scan = scanCheck(new CheckConditionExpression(expr, source));

        assertEquals(1, scan.structuralNegation.size(),
                "not(date(X) < Y) in expression form must be reported: " + scan.structuralNegation);
        assertEquals(1, scan.expressionChecks, "the expression Check must have been walked");
    }

    // ------------------------------------------------------------------
    // Anti-vacuity: the detector stays silent on the near misses
    // ------------------------------------------------------------------


    /**
     * F5 — a detector that answered "everything is a violation" would pass the fire tests above.
     * None of these five shapes may be reported.
     */
    @Test
    void nearMissesAreNotReported()
    {
        CheckCondition bareDate = dateLeaf("date_less_than");
        CheckCondition negatedNonDate = new CheckConditionNot(CheckConditionLeaf.builder()
                .name("AESTDTC").operator("equal_to").value(TextNode.valueOf("X")).build());
        CheckCondition bareDateNotEqual = dateLeaf("date_not_equal_to");
        CheckCondition negatedDatePart = new CheckConditionNot(dateLeaf("date_part_not_equal_to"));
        CheckCondition negativeOnNonDate = CheckConditionLeaf.builder().name("EXDUR")
                .operator("invalid_duration").negative(Boolean.TRUE).build();

        Scan scan = scanCheck(new CheckConditionAll(List.of(bareDate, negatedNonDate,
                bareDateNotEqual, negatedDatePart, negativeOnNonDate)));

        assertTrue(scan.structuralNegation.isEmpty(),
                "no near miss may be reported as a structural negation: " + scan.structuralNegation
                        + " (a bare date_less_than, a negated non-date leaf, a bare "
                        + "date_not_equal_to, and a negated date_part_* leaf are all legitimate)");
        assertTrue(scan.negativeFlag.isEmpty(),
                "`negative:` on invalid_duration is its documented modifier, not a finding: "
                        + scan.negativeFlag);
        assertEquals(2, scan.dateComparisonLeaves,
                "exactly the two hull date leaves must be recognised — date_part_not_equal_to is "
                        + "out of scope because it compiles to datePartComparison, not to the "
                        + "hull comparison");
    }


    /** {@code not(not(date_less_than))} is the original &forall; again, so it is not a finding. */
    @Test
    void doubleNegationIsNotReported()
    {
        Scan scan = scanCheck(
                new CheckConditionNot(new CheckConditionNot(dateLeaf("date_less_than"))));

        assertTrue(scan.structuralNegation.isEmpty(),
                "double negation restores the ∀ reading: " + scan.structuralNegation);
        assertEquals(1, scan.dateComparisonLeaves, "the date leaf must still have been recognised");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------


    private static CheckConditionLeaf dateLeaf(String operator)
    {
        return CheckConditionLeaf.builder().name("MHSTDTC").operator(operator)
                .value(TextNode.valueOf("RFSTDTC")).build();
    }


    private static Scan scanCheck(CheckCondition check)
    {
        Scan scan = new Scan("fixture");
        scan.rules++;
        walk(check, false, "fixture", scan);
        return scan;
    }

    // ------------------------------------------------------------------
    // Corpus readers
    // ------------------------------------------------------------------


    /** Loads every {@code rules-*.json} package under {@code dir} and walks each rule's Check. */
    private static Scan scanPackages(Path dir, String view) throws IOException
    {
        Scan scan = new Scan(view);
        try (Stream<Path> files = Files.list(dir))
        {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.toString().endsWith(".json")).sorted().toList())
            {
                RulePackage pkg = RulePackageLoader.load(file);
                for (Rule rule : pkg.getRules().values())
                {
                    if (rule != null)
                    {
                        scanRule(rule, view + "/" + file.getFileName(), scan);
                    }
                }
            }
        }
        return scan;
    }


    /** Reads every authoring YAML under {@code dir} and walks each rule's Check. */
    private static Scan scanSources(Path dir, String view) throws IOException
    {
        Scan scan = new Scan(view);
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir))
        {
            files = walk.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        for (Path file : files)
        {
            scanRule(YAML.readValue(file.toFile(), Rule.class), view + "/" + file.getFileName(),
                    scan);
        }
        return scan;
    }


    /**
     * Walks <b>every declared check level</b>, not the strictest one.
     *
     * <p>
     * ⛔ {@code rule.getCheck()} is the <em>strictest level's</em> condition alone (Plan C §3.3), so
     * a walker keyed on it sees nothing a weaker rung authors. That is not hypothetical here: every
     * one of the 9 level-keyed rules puts its hull-semantics comparison on the <em>weaker</em> rung
     * — the {@code date(latest_possible(X)) >= earliest_possible(Y)} escape hatch this lint's own
     * failure message points authors at — so the exact construct the lint exists to police was the
     * one construct it could not reach. Measured at the widening: the {@code rules/} view moves 477
     * → 517 hull date leaves and {@code rules-src} 108 → 119, i.e. <b>40 shipped + 11 authored
     * comparisons that were never walked</b>. The {@code dateComparisonLeaves > 50} floor cannot
     * detect a 40-leaf hole, so nothing said so.
     * </p>
     *
     * <p>
     * ⚑ {@link Rule#effectiveCheckLevels()} is never {@code null}: for the ~3 795 rules that author
     * a plain {@code Check:} it synthesises a one-entry map holding exactly {@code getCheck()}, so
     * their leaf counts are unchanged by this widening.
     * </p>
     */
    private static void scanRule(Rule rule, String file, Scan scan)
    {
        scan.rules++;
        String id = rule.getCore() != null && rule.getCore().getId() != null
                ? rule.getCore().getId()
                : rule.getId();
        SequencedMap<Severity, LevelCheck> levels = rule.effectiveCheckLevels();
        boolean levelled = rule.getCheckLevels() != null;
        for (Map.Entry<Severity, LevelCheck> level : levels.entrySet())
        {
            // The rung is named in the origin only for a genuinely level-keyed rule, so a plain
            // rule's finding text is byte-identical to what it was before the widening.
            String origin = levelled ? file + "|" + id + "@" + level.getKey().name()
                    : file + "|" + id;
            walk(level.getValue().condition(), false, origin, scan);
        }
    }

    // ------------------------------------------------------------------
    // The detector
    // ------------------------------------------------------------------


    /**
     * Walks the legacy Check AST, carrying the inherited negation state down through every
     * composite node so a {@code not:} several levels above a leaf still reaches it.
     */
    private static void walk(CheckCondition condition, boolean negated, String origin, Scan scan)
    {
        switch (condition)
        {
        case null -> scan.absentChecks++;
        case CheckConditionAll all -> walkAll(all.getConditions(), negated, origin, scan);
        case CheckConditionAny any -> walkAll(any.getConditions(), negated, origin, scan);
        case CheckConditionNot not -> walk(not.getCondition(), !negated, origin, scan);
        case CheckConditionExpression expression -> walkExpression(expression, negated, origin,
                scan);
        case CheckConditionConstant _ -> scan.foldedConstants++;
        case CheckConditionLeaf leaf -> walkLeaf(leaf, negated, origin, scan);
        }
    }


    private static void walkAll(List<CheckCondition> parts, boolean negated, String origin,
            Scan scan)
    {
        for (CheckCondition part : parts)
        {
            walk(part, negated, origin, scan);
        }
    }


    private static void walkExpression(CheckConditionExpression expression, boolean negated,
            String origin, Scan scan)
    {
        scan.expressionChecks++;
        walkExpr(expression.expr(), negated, origin, scan);
    }


    private static void walkLeaf(CheckConditionLeaf leaf, boolean negated, String origin, Scan scan)
    {
        scan.leaves++;
        boolean flagged = Boolean.TRUE.equals(leaf.getNegative());
        if (flagged)
        {
            scan.negativeFlagLeaves++;
        }
        String operator = leaf.getOperator();
        // Job 2 is reported BEFORE the hull-date filter, deliberately: a stray `negative:` is dead
        // on every operator that does not consume it, not only on the six. CheckToExpr:182 shows
        // invalid_duration is the sole consumer; rejectAffixFields (:341, called at :208) throws
        // for all the rest, leaving checkExpr == null and a per-rule RuleRunner ERROR.
        if (flagged && operator != null && !NEGATIVE_CONSUMING_OPERATOR.equals(operator))
        {
            scan.deadNegativeFlag
                    .add(origin + "|" + operator + "(" + leaf.getName() + ") negative:");
        }
        if (operator == null || !HULL_DATE_OPERATORS.contains(operator))
        {
            return;
        }
        scan.dateComparisonLeaves++;
        if (negated)
        {
            scan.structuralNegation.add(origin + "|not " + operator + "(" + leaf.getName() + ")");
        }
        if (flagged)
        {
            scan.negativeFlag.add(origin + "|" + operator + "(" + leaf.getName() + ") negative:");
        }
    }


    /**
     * Walks the {@link Expr} IR — the shape a {@code rules/} expression Check keeps when it has no
     * legacy lowering. A {@code date(…)}-tagged comparison is the IR form of the six in-scope
     * operators; {@code date_part(…)} / {@code time_part(…)} tags are deliberately not matched.
     */
    private static void walkExpr(Expr expr, boolean negated, String origin, Scan scan)
    {
        switch (expr)
        {
        case Expr.Not not -> walkExpr(not.inner(), !negated, origin, scan);
        case Expr.And and -> walkExprAll(and.parts(), negated, origin, scan);
        case Expr.Or or -> walkExprAll(or.parts(), negated, origin, scan);
        case Expr.Binary binary -> walkBinary(binary, negated, origin, scan);
        case Expr.Call call -> walkExprAll(call.args(), negated, origin, scan);
        case Expr.Ref _ -> scan.operands++;
        case Expr.Lit _ -> scan.operands++;
        }
    }


    private static void walkExprAll(List<Expr> parts, boolean negated, String origin, Scan scan)
    {
        for (Expr part : parts)
        {
            walkExpr(part, negated, origin, scan);
        }
    }


    private static void walkBinary(Expr.Binary binary, boolean negated, String origin, Scan scan)
    {
        if (!isHullDateComparison(binary))
        {
            walkExpr(binary.left(), negated, origin, scan);
            walkExpr(binary.right(), negated, origin, scan);
            return;
        }
        scan.dateComparisonLeaves++;
        if (negated)
        {
            scan.structuralNegation.add(origin + "|not (" + ExpressionPrinter.print(binary) + ")");
        }
    }


    private static boolean isHullDateComparison(Expr.Binary binary)
    {
        return COMPARISON_OPS.contains(binary.op())
                && (isHullDateTag(binary.left()) || isHullDateTag(binary.right()));
    }


    private static boolean isHullDateTag(Expr expr)
    {
        return expr instanceof Expr.Call call && HULL_DATE_TAG.equals(call.name())
                && call.args().size() == 1;
    }

    /** Per-view counters and findings; the counts are all <b>Check leaves</b>, never files. */
    private static final class Scan
    {

        private final String view;

        private int rules;

        private int leaves;

        private int dateComparisonLeaves;

        private int negativeFlagLeaves;

        private int expressionChecks;

        private int foldedConstants;

        private int absentChecks;

        private int operands;

        private final List<String> structuralNegation = new ArrayList<>();

        private final List<String> negativeFlag = new ArrayList<>();

        /** Job 2, corpus-wide: a {@code negative:} on any operator that cannot consume it. */
        private final List<String> deadNegativeFlag = new ArrayList<>();

        Scan(String view)
        {
            this.view = view;
        }


        @Override
        public String toString()
        {
            return view + ": rules=" + rules + " leaves=" + leaves + " hullDateLeaves="
                    + dateComparisonLeaves + " negativeFlagLeaves=" + negativeFlagLeaves
                    + " expressionChecks=" + expressionChecks + " foldedConstants="
                    + foldedConstants + " absentChecks=" + absentChecks + " exprOperands="
                    + operands + " negatedDate=" + structuralNegation.size() + " negativeFlagDate="
                    + negativeFlag.size() + " deadNegativeFlag=" + deadNegativeFlag.size();
        }

    }

}
