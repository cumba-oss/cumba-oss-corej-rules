package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.cumba.corej.core.CheckExprUnits;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * The granularity lint of {@code plans/done/PLAN-split-mixed-granularity-rules.md}, run over every
 * rule in {@code rules-src/checks} plus unit coverage of {@link MixedGranularityLint} itself.
 *
 * <p>
 * <b>A hard gate</b> (plan D5, promoted in the Cleanup phase once the six Tier-1 rules were split
 * and the scan reported zero). The corpus scan writes
 * {@code generated/derivation/mixed-granularity.tsv} and <b>fails</b> on any finding, so the
 * authoring rule written into {@code CORE-RULES-AUTHORING-GUIDELINES.md} &sect;5.2.1 cannot be
 * reintroduced silently. It also asserts that the corpus was actually read, so a path or glob
 * breakage cannot masquerade as "no findings".
 * </p>
 *
 * <p>
 * <b>There is deliberately no exemption list.</b> The four rules that would have needed one
 * (&sect;4.2) are handled by the {@code unpopulated(X)} fold in {@link MixedGranularityLint}, which
 * recognises them as the single-granularity assertions they are rather than waiving them.
 * </p>
 */
class MixedGranularityLintTest
{

    private static final Path CHECKS_DIR = Path.of("rules-src", "checks");

    private static final Path REPORT = Path.of("generated", "derivation", "mixed-granularity.tsv");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    // ------------------------------------------------------------------
    // Corpus scan
    // ------------------------------------------------------------------

    @Test
    void corpusHasNoMixedGranularityRules() throws IOException
    {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CHECKS_DIR))
        {
            files = walk.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "no rule YAML found under " + CHECKS_DIR.toAbsolutePath());

        List<String> rows = new ArrayList<>();
        int raised = 0;
        for (Path file : files)
        {
            Rule rule = YAML.readValue(file.toFile(), Rule.class);
            String id = rule.getCore() != null && rule.getCore().getId() != null
                    ? rule.getCore().getId()
                    : file.getFileName().toString();
            Expr check = raise(rule);
            if (check != null)
            {
                raised++;
            }
            for (MixedGranularityLint.Finding f : MixedGranularityLint.scan(check))
            {
                rows.add(id + '\t' + file.getParent().getFileName() + '\t' + f.globalBranches()
                        + '\t' + f.recordBranches());
            }
        }

        // A Check with no expression is scanned as null and can never produce a finding, so a
        // parse regression would quietly turn this hard gate into a no-op. Assert a floor on
        // the rules that actually reached the lint, not just on the files that were read.
        assertTrue(raised > 3000, "expected the whole corpus to raise, but only " + raised + " of "
                + files.size() + " rules produced an expression — the gate would be vacuous");

        Files.createDirectories(REPORT.getParent());
        StringBuilder sb = new StringBuilder("id\tfamily\tglobal_branches\trecord_branches\n");
        rows.forEach(r -> sb.append(r).append('\n'));
        Files.writeString(REPORT, sb.toString(), StandardCharsets.UTF_8);

        assertTrue(rows.isEmpty(),
                "mixed-granularity rules found — a Check must assert at exactly one granularity"
                        + " (CORE-RULES-AUTHORING-GUIDELINES.md §5.2.1). Split the rule into a"
                        + " dataset half and a record half; see"
                        + " plans/done/PLAN-split-mixed-granularity-rules.md. Offenders (id, family,"
                        + " broadcast branches, record branches): " + rows);
    }


    /**
     * Raises a rule's Check the same way {@code RuleClassifier} does — with a {@code null} rule
     * type, so a generic {@code exists} stays generic rather than being guessed at.
     */
    private static @org.jspecify.annotations.Nullable Expr raise(Rule rule)
    {
        try
        {
            return rule.getCheck() == null ? null : CheckExprUnits.toExpr(rule.getCheck());
        }
        catch (RuntimeException _)
        {
            // No expression surface (boolean constant, unconvertible leaf) — nothing to lint.
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Unit coverage — hand-built trees
    // ------------------------------------------------------------------


    @Test
    void flagsBroadcastOrRecord()
    {
        // not_contains_all(TSPARMCD, [TDIGRP]) OR (TSPARMCD == TDIGRP AND TSVAL empty)
        assertEquals(1, MixedGranularityLint.scan(cg0273Shape()).size());
    }


    @Test
    void doesNotFlagWhenGatedByARecordSibling()
    {
        // The FDA-SE2244 shape: the mixed `any` sits under an `all` whose other conjunct is a
        // record predicate, so the mask still varies and no innocent row is named.
        Expr gated = new Expr.And(List.of(binary(col("STRESC"), col("ORRES")), cg0273Shape()));
        assertTrue(MixedGranularityLint.scan(gated).isEmpty());
    }


    @Test
    void doesNotFlagAConjunctiveGate()
    {
        // all[$op >= 1, TSVAL empty] — the broadcast side merely filters.
        Expr conjunctive = new Expr.And(List.of(binary(op("$N"), lit(1)), empty("TSVAL")));
        assertTrue(MixedGranularityLint.scan(conjunctive).isEmpty());
    }


    @Test
    void doesNotFlagUniformBranches()
    {
        assertTrue(
                MixedGranularityLint.scan(new Expr.Or(List.of(empty("A"), empty("B")))).isEmpty());
        assertTrue(MixedGranularityLint
                .scan(new Expr.Or(List.of(binary(op("$A"), lit(0)), binary(op("$B"), lit(0)))))
                .isEmpty());
    }


    @Test
    void foldsUnpopulatedIdiom()
    {
        // CORE-000701: any[EPOCH not_exists, EPOCH empty] is ONE assertion about EPOCH.
        assertTrue(MixedGranularityLint.scan(unpopulated("EPOCH")).isEmpty());
    }


    @Test
    void foldsUnpopulatedIdiomInTheNegatedPositiveSpelling()
    {
        // The corpus authors `not var_exists(X)` for this idiom too; same assertion.
        Expr negatedPositive = new Expr.Or(
                List.of(new Expr.Not(call("var_exists", ref("EPOCH"))), empty("EPOCH")));
        assertTrue(MixedGranularityLint.scan(negatedPositive).isEmpty());
    }


    @Test
    void foldsSeveralIndependentPairsInOneDisjunction()
    {
        // FDA-SE2281: three unpopulated(X) pairs flattened into one six-leaf `any`.
        Expr se2281 = new Expr.Or(List.of(notExists("PCNOMDY"), empty("PCNOMDY"),
                notExists("PCELTM"), empty("PCELTM"), notExists("PCTPTREF"), empty("PCTPTREF")));
        assertTrue(MixedGranularityLint.scan(se2281).isEmpty());
    }


    @Test
    void foldsRecursivelyThroughNestedDisjunctions()
    {
        // FDA-SE2308: any[P_ne, P_e, all[any[V_ne, V_e], any[N_ne, N_e]]]. Only a bottom-up fold
        // reaches this — a flat one leaves the inner `any` nodes mixed and flags the rule.
        Expr se2308 = new Expr.Or(List.of(notExists("PCELTM"), empty("PCELTM"),
                new Expr.And(List.of(unpopulated("VISITDY"), unpopulated("PCNOMDY")))));
        assertTrue(MixedGranularityLint.scan(se2308).isEmpty());
    }


    @Test
    void doesNotFoldAcrossDifferentVariables()
    {
        // "X is absent OR Y is empty" really is two assertions at two granularities.
        Expr crossed = new Expr.Or(List.of(notExists("AESEV"), empty("AEDECOD")));
        assertEquals(1, MixedGranularityLint.scan(crossed).size());
    }


    @Test
    void doesNotFoldAWholeColumnOperatorOnTheSameVariable()
    {
        // The property that keeps the lint honest: CDISC-CG0273's broadcast leaf names TSPARMCD
        // and so does its record branch, so a fold keyed on the variable NAME rather than on the
        // presence OPERATOR would silence all six Tier-1 rules.
        assertEquals(1, MixedGranularityLint.scan(cg0273Shape()).size());
        assertEquals(MixedGranularityLint.Kind.GLOBAL,
                MixedGranularityLint.kind(notContainsAll("TSPARMCD", "TDIGRP")));
    }


    @Test
    void doesNotFoldADatasetPresenceGuard()
    {
        // ds_not_exists names a DATASET, not a variable, so it can never pair with a record leaf.
        Expr dsGuard = new Expr.Or(List.of(call("ds_not_exists", ref("SUPPAE")), empty("SUPPAE")));
        assertEquals(1, MixedGranularityLint.scan(dsGuard).size());
    }


    @Test
    void looksThroughNestedSingleChildNodes()
    {
        assertEquals(1, MixedGranularityLint.scan(new Expr.And(List.of(cg0273Shape()))).size());
    }


    @Test
    void doesNotFlagAMixedDisjunctionReadUnderANot()
    {
        // De Morgan: `not any[GLOBAL, RECORD]` IS `all[not GLOBAL, not RECORD]`, and §1 says a
        // conjunction that mixes granularities is fine — the broadcast side merely gates. Flagging
        // it would fail the build on a correctly-authored rule.
        assertTrue(MixedGranularityLint.scan(new Expr.Not(cg0273Shape())).isEmpty());
        // ...and the polarity flips back under a second Not.
        assertEquals(1,
                MixedGranularityLint.scan(new Expr.Not(new Expr.Not(cg0273Shape()))).size());
    }


    @Test
    void flagsAMixedConjunctionReadUnderANot()
    {
        // The mirror image: `not all[GLOBAL, RECORD]` is a disjunction, so it carries the defect.
        Expr mixedAnd = new Expr.And(List.of(notContainsAll("TSPARMCD", "TDIGRP"), empty("TSVAL")));
        assertEquals(1, MixedGranularityLint.scan(new Expr.Not(mixedAnd)).size());
    }


    @Test
    void aSiblingThatCanItselfSaturateIsNotARecordGate()
    {
        // all[ any[G1,R1], any[G2,R2] ]: each `any` would gate the other under a naive
        // "any sibling contains a record leaf" rule, and the pair would report nothing — yet when
        // G1 AND G2 the mask saturates on every row, which is exactly the defect. A sibling only
        // gates when it varies per row on EVERY path through it.
        Expr left = new Expr.Or(List.of(notContainsAll("TSPARMCD", "TDIGRP"), empty("TSVAL")));
        Expr right = new Expr.Or(List.of(notContainsAll("TSPARMCD", "CURTRT"), empty("TSVALX")));
        assertEquals(2, MixedGranularityLint.scan(new Expr.And(List.of(left, right))).size());
    }


    @Test
    void anUnconditionalRecordSiblingStillGates()
    {
        // The FDA-SE2244 property must survive the stricter gate: a plain record leaf beside the
        // mixed `any` is unconditional, so it gates and the rule stays clean.
        Expr gated = new Expr.And(List.of(empty("STRESC"), cg0273Shape()));
        assertTrue(MixedGranularityLint.scan(gated).isEmpty());
        // An all-record disjunction is unconditional too, so it gates as well.
        Expr recordOr = new Expr.Or(List.of(empty("A"), empty("B")));
        assertTrue(MixedGranularityLint.scan(new Expr.And(List.of(recordOr, cg0273Shape())))
                .isEmpty());
    }


    @Test
    void classifiesLeafKinds()
    {
        assertEquals(MixedGranularityLint.Kind.NONE, MixedGranularityLint.kind(null));
        assertEquals(MixedGranularityLint.Kind.GLOBAL, MixedGranularityLint.kind(op("$N")));
        assertEquals(MixedGranularityLint.Kind.GLOBAL, MixedGranularityLint.kind(lit(1)));
        assertEquals(MixedGranularityLint.Kind.RECORD, MixedGranularityLint.kind(col("TSVAL")));
        assertEquals(MixedGranularityLint.Kind.RECORD, MixedGranularityLint.kind(empty("TSVAL")));
        assertEquals(MixedGranularityLint.Kind.GLOBAL,
                MixedGranularityLint.kind(notExists("EPOCH")));
        assertEquals(MixedGranularityLint.Kind.NONE,
                MixedGranularityLint.kind(new Expr.And(List.of())));
        // An AND is record-level as soon as one conjunct is.
        assertEquals(MixedGranularityLint.Kind.RECORD,
                MixedGranularityLint.kind(new Expr.And(List.of(op("$N"), empty("TSVAL")))));
    }


    /**
     * The third consumer of {@code BroadcastFold}'s vocabulary must agree with the two routers
     * about {@code var_is_empty}.
     *
     * <p>
     * &#9888;&#9888; <b>Nothing in the corpus scan would red if this wiring were missing.</b> The
     * lint fires only on {@code Or}, and all five {@code var_is_empty} rules are conjunctions — so
     * the lint would have gone on calling the call a RECORD leaf while {@code RuleClassifier} and
     * {@code DomainScan} call it a dataset fact, silently. These hand-built trees are the only
     * thing that holds the three consumers together, which is why they assert the disagreement
     * shape directly rather than relying on the corpus.
     * </p>
     */
    @Test
    void aBroadcastColumnPredicateIsAGlobalLeafLikeTheRoutersSay()
    {
        assertEquals(MixedGranularityLint.Kind.GLOBAL,
                MixedGranularityLint.kind(call("var_is_empty", ref("LBORRES"))));
        assertEquals(MixedGranularityLint.Kind.GLOBAL,
                MixedGranularityLint.kind(new Expr.Not(call("var_is_empty", ref("LBORRES")))));
        // The SD9714 shape itself: two broadcast leaves, one verdict per dataset, nothing mixed.
        Expr sd9714 = new Expr.And(
                List.of(new Expr.Not(call("var_is_empty", ref("LBORRES"))), notExists("LBORRESU")));
        assertEquals(MixedGranularityLint.Kind.GLOBAL, MixedGranularityLint.kind(sd9714));
        assertTrue(MixedGranularityLint.scan(sd9714).isEmpty());
        // The shape that WOULD have been flagged while the lint disagreed with the routers:
        // any[ var_is_empty(X), empty(Y) ] is mixed only if var_is_empty reads a row.
        assertTrue(
                MixedGranularityLint.scan(new Expr.Or(List.of(call("var_is_empty", ref("LBORRES")),
                        call("var_is_empty", ref("LBORRESU"))))).isEmpty());
        assertEquals(1, MixedGranularityLint
                .scan(new Expr.Or(List.of(call("var_is_empty", ref("LBORRES")), empty("LBSTRESC"))))
                .size());
        // A shape compileVarIsNull cannot compile stays a RECORD leaf: the guard is exact, so an
        // unsupported argument keeps reading per row rather than being waved through as global.
        assertEquals(MixedGranularityLint.Kind.RECORD,
                MixedGranularityLint.kind(call("var_is_empty", call("upper", ref("LBORRES")))));
    }

    // ------------------------------------------------------------------
    // Tree builders
    // ------------------------------------------------------------------


    /** {@code not_contains_all(TSPARMCD,[TDIGRP]) OR (TSPARMCD == TDIGRP AND TSVAL empty)}. */
    private static Expr cg0273Shape()
    {
        return new Expr.Or(List.of(notContainsAll("TSPARMCD", "TDIGRP"),
                new Expr.And(List.of(binary(col("TSPARMCD"), lit("TDIGRP")), empty("TSVAL")))));
    }


    private static Expr unpopulated(String name)
    {
        return new Expr.Or(List.of(notExists(name), empty(name)));
    }


    private static Expr notContainsAll(String column, String required)
    {
        return new Expr.Not(new Expr.Call("contains_all",
                List.of(ref(column), new Expr.Lit(Expr.LitKind.STRING, required)), Map.of()));
    }


    private static Expr notExists(String name)
    {
        return call("var_not_exists", ref(name));
    }


    private static Expr empty(String name)
    {
        return call("empty", ref(name));
    }


    private static Expr call(String name, Expr arg)
    {
        return new Expr.Call(name, List.of(arg), Map.of());
    }


    private static Expr binary(Expr left, Expr right)
    {
        return new Expr.Binary(Expr.BinOp.EQ, left, right);
    }


    private static Expr ref(String name)
    {
        return new Expr.Ref(name, OperandKind.COLUMN);
    }


    private static Expr col(String name)
    {
        return ref(name);
    }


    private static Expr op(String name)
    {
        return new Expr.Ref(name, OperandKind.OPERATION_REF);
    }


    private static Expr lit(Object value)
    {
        return value instanceof String s ? new Expr.Lit(Expr.LitKind.STRING, s)
                : new Expr.Lit(Expr.LitKind.NUMBER, ((Number) value).doubleValue());
    }
}
