package net.cumba.corej.core.expr.convert;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.BroadcastFold;
import org.jspecify.annotations.Nullable;

/**
 * The granularity lint of {@code plans/done/PLAN-split-mixed-granularity-rules.md} (&sect;1,
 * &sect;4.0, &sect;4.2a): finds {@code Check} expressions that assert at <em>two</em> granularities
 * at once.
 *
 * <p>
 * <b>The defect.</b> Both engines evaluate a Check to a boolean mask over the rows of the frame and
 * materialise findings from the true rows; {@code Sensitivity} then chooses whether those rows are
 * reported individually ({@code Record}) or collapsed to one finding ({@code Dataset}). A
 * whole-column operator computes one boolean and <em>broadcasts</em> it to every row, so
 * {@code AND}-ing it with a per-record predicate is harmless (the broadcast side acts as a gate)
 * but {@code OR}-ing it saturates the mask and destroys the record-level information. No
 * {@code Sensitivity} is correct for such a rule: {@code Dataset} hides the offenders,
 * {@code Record} names innocent rows.
 * </p>
 *
 * <p>
 * <b>Two things keep this from over-reporting</b>, and both are load-bearing — without them the
 * lint flags 183 rules that are correct as authored:
 * </p>
 * <ol>
 * <li><b>Record-gating</b> (&sect;4.0). A mixed {@code Or} is only a defect when the broadcast
 * branch can saturate the mask <em>on its own</em>. If an enclosing {@code And} supplies a
 * record-level conjunct, that conjunct keeps the mask varying and every reported row satisfies a
 * genuine per-record predicate — coarse at worst, never misattributed.</li>
 * <li><b>The {@code unpopulated(X)} fold</b> (&sect;4.2a). {@code X not_exists OR X empty} is
 * <em>one</em> assertion — "X is not populated" — written in two leaves only because {@code empty}
 * cannot be evaluated against a column that is absent from the frame. The presence leaf is a guard
 * on the record assertion, not a coarser second assertion, so it folds away.</li>
 * </ol>
 *
 * <p>
 * <b>Expression-grounded, not YAML-grounded</b> (&sect;4.4): the walk runs over the raised
 * {@link Expr}, and every "is this dataset-level" question routes to {@link BroadcastFold}, which
 * is the same authority {@code RuleClassifier} derives {@code Sensitivity} from. A YAML
 * {@code Check}-tree walker would drift from the classifier, miss inlined operations that no longer
 * appear as {@code $}-references, and trip over spelling variants ({@code not} wrappers, nested
 * single-child {@code all}).
 * </p>
 */
final class MixedGranularityLint
{

    private MixedGranularityLint()
    {
    }

    /** How a subtree contributes to the row mask. */
    enum Kind
    {
        /** One verdict broadcast to every row — a whole-column or dataset fact. */
        GLOBAL,
        /** A genuinely varying, per-record verdict. */
        RECORD,
        /** No classifiable leaf (an empty branch); contributes nothing either way. */
        NONE
    }


    /**
     * One flagged {@code Or} node.
     *
     * @param globalBranches
     *            how many of its branches were purely broadcast
     * @param recordBranches
     *            how many carried a per-record predicate
     */
    record Finding(int globalBranches, int recordBranches)
    {
    }

    /**
     * Every ungated mixed-granularity disjunction in {@code check}, in encounter order. Empty when
     * the rule is single-granularity — which every rule in the corpus is expected to be once the
     * plan's six Tier-1 rules are split.
     *
     * @param check
     *            the raised Check expression, or {@code null} for a rule with no expression surface
     * @return the findings; empty means clean
     */
    static List<Finding> scan(@Nullable Expr check)
    {
        List<Finding> out = new ArrayList<>();
        walk(check, false, false, out);
        return out;
    }


    /**
     * @param negated
     *            whether an odd number of enclosing {@code Not}s applies. <b>Polarity is
     *            load-bearing</b>: under a {@code Not}, De Morgan turns a disjunction into a
     *            conjunction, and &sect;1 says a conjunction that mixes granularities is
     *            <em>fine</em> — the broadcast side merely gates. Flagging {@code not any[G, R]}
     *            would fail the build on a correctly-authored rule.
     * @param gated
     *            whether an enclosing conjunction already supplies a record-level conjunct, which
     *            keeps the mask varying no matter what a nested disjunction does
     */
    private static void walk(@Nullable Expr e, boolean negated, boolean gated, List<Finding> out)
    {
        switch (e)
        {
        case null ->
        {
            // no expression surface
        }
        case Expr.And and -> visit(and.parts(), negated, !negated, gated, out);
        case Expr.Or or -> visit(fold(or.parts()), negated, negated, gated, out);
        case Expr.Not not -> walk(not.inner(), !negated, gated, out);
        default ->
        {
            // a leaf: nothing to descend into
        }
        }
    }


    /**
     * One boolean node, already resolved to what it <em>behaves</em> as at this polarity.
     *
     * @param conjunction
     *            true when the node acts as an AND (an {@code And} read positively, or an
     *            {@code Or} read under a {@code Not}) — it gates rather than mixes; false when it
     *            acts as an OR, which is the shape &sect;1 forbids mixing in
     */
    private static void visit(List<Expr> parts, boolean negated, boolean conjunction, boolean gated,
            List<Finding> out)
    {
        if (!conjunction)
        {
            int global = 0;
            int record = 0;
            for (Expr b : parts)
            {
                switch (kind(b))
                {
                case GLOBAL -> global++;
                case RECORD -> record++;
                case NONE ->
                {
                    // contributes to neither count
                }
                }
            }
            if (global > 0 && record > 0 && !gated)
            {
                out.add(new Finding(global, record));
            }
        }
        for (int i = 0; i < parts.size(); i++)
        {
            boolean childGated = gated || (conjunction && hasRecordSibling(parts, i));
            walk(parts.get(i), negated, childGated, out);
        }
    }


    /**
     * Whether any conjunct other than {@code self} is <b>unconditionally</b> record-level.
     *
     * <p>
     * "Unconditionally" is the strict reading and it matters: a sibling that is itself a
     * disjunction with a broadcast branch is <em>not</em> a gate, because that branch can saturate
     * the sibling's own mask. {@code all[ any[G1, R1], any[G2, R2] ]} would otherwise have each
     * {@code any} gate the other and report nothing, even though {@code G1 AND G2} saturates the
     * whole mask — precisely the defect this lint exists to find.
     * </p>
     */
    private static boolean hasRecordSibling(List<Expr> parts, int self)
    {
        for (int i = 0; i < parts.size(); i++)
        {
            if (i != self && isUnconditionallyRecord(parts.get(i)))
            {
                return true;
            }
        }
        return false;
    }


    /** Whether {@code e} varies per row on <em>every</em> path through it, not just on some. */
    private static boolean isUnconditionallyRecord(@Nullable Expr e)
    {
        return switch (e)
        {
        case null -> false;
        // A conjunction varies if ANY conjunct does — the others can only narrow the mask.
        case Expr.And and -> and.parts().stream()
                .anyMatch(MixedGranularityLint::isUnconditionallyRecord);
        // A disjunction varies only if EVERY branch does; one broadcast branch can saturate it.
        case Expr.Or or -> !or.parts().isEmpty() && fold(or.parts()).stream()
                .allMatch(MixedGranularityLint::isUnconditionallyRecord);
        case Expr.Not not -> isUnconditionallyRecord(not.inner());
        default -> !isGlobalLeaf(e);
        };
    }

    // ------------------------------------------------------------------
    // The unpopulated(X) fold — plan §4.2a
    // ------------------------------------------------------------------


    /**
     * Drops the presence guards of an {@code Or}: a {@code X not_exists} branch disappears when a
     * sibling branch is a record assertion about that same {@code X}, because together they are the
     * single assertion "X is not populated" (&sect;4.2a).
     *
     * <p>
     * <b>Keyed on the operator, never on the name.</b> Only the exists-family negatives qualify as
     * the foldable guard. Matching on name equality alone would silence the very rules this lint
     * exists to find: {@code CDISC-CG0273}'s broadcast leaf is
     * {@code TSPARMCD not_contains_all [TDIGRP]} and its record branch also names {@code TSPARMCD},
     * so a name-keyed fold would collapse a genuine mixed disjunction to "clean".
     * </p>
     *
     * <p>
     * Callers reach this bottom-up, because {@link #kind} folds nested {@code Or} nodes before
     * classifying them — which is what makes {@code FDA-SE2308}'s
     * {@code any[P_ne, P_e, all[any[V_ne, V_e], any[N_ne, N_e]]]} come out uniformly record-level.
     * A non-recursive fold would still flag it.
     * </p>
     */
    private static List<Expr> fold(List<Expr> branches)
    {
        List<Expr> kept = new ArrayList<>(branches.size());
        for (Expr b : branches)
        {
            String guarded = presenceGuardTarget(b);
            if (guarded == null || !isGuardedElsewhere(branches, b, guarded))
            {
                kept.add(b);
            }
        }
        return kept;
    }


    /** Whether some <em>other</em> branch is a record assertion about {@code target} alone. */
    private static boolean isGuardedElsewhere(List<Expr> branches, Expr self, String target)
    {
        for (Expr other : branches)
        {
            if (other != self && kind(other) == Kind.RECORD
                    && Set.of(target).equals(recordTargets(other)))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * The variable a branch asserts the <em>absence</em> of, or {@code null} when the branch is not
     * a bare absence check. Both spellings are recognised: the negative call
     * ({@code not_exists(X)}) and the negated positive ({@code Not(var_exists(X))}), because
     * {@code CheckToExpr} produces either depending on whether the rule type was known.
     *
     * <p>
     * {@code ds_exists} / {@code ds_not_exists} name a <em>dataset</em>, not a variable, so they
     * can never pair with a record assertion and are deliberately excluded.
     * </p>
     */
    private static @Nullable String presenceGuardTarget(Expr b)
    {
        return switch (b)
        {
        case Expr.Call c when BroadcastFold.isExistsCall(c)
                && isVariableAbsence(c.name()) -> argName(c);
        case Expr.Not n when n.inner() instanceof Expr.Call c && BroadcastFold.isExistsCall(c)
                && isVariablePresence(c.name()) -> argName(c);
        default -> null;
        };
    }


    private static boolean isVariableAbsence(String call)
    {
        return "var_not_exists".equals(call);
    }


    private static boolean isVariablePresence(String call)
    {
        return "var_exists".equals(call);
    }


    /** The single argument of an exists-family call, as a name. */
    private static @Nullable String argName(Expr.Call c)
    {
        return switch (c.args().get(0))
        {
        case Expr.Ref r -> r.name();
        case Expr.Lit lit when lit.value() instanceof String s -> s;
        default -> null;
        };
    }


    /** The column names every record-level leaf of {@code e} reads. */
    private static Set<String> recordTargets(Expr e)
    {
        Set<String> out = new LinkedHashSet<>();
        collectRecordTargets(e, out);
        return out;
    }


    private static void collectRecordTargets(@Nullable Expr e, Set<String> out)
    {
        switch (e)
        {
        case null ->
        {
            // nothing
        }
        case Expr.And and -> and.parts().forEach(p -> collectRecordTargets(p, out));
        case Expr.Or or -> or.parts().forEach(p -> collectRecordTargets(p, out));
        case Expr.Not not -> collectRecordTargets(not.inner(), out);
        default ->
        {
            if (kind(e) == Kind.RECORD)
            {
                collectColumnRefs(e, out);
            }
        }
        }
    }


    private static void collectColumnRefs(Expr e, Set<String> out)
    {
        switch (e)
        {
        case Expr.Ref r ->
        {
            if (r.kind() != OperandKind.OPERATION_REF)
            {
                out.add(r.name());
            }
        }
        case Expr.Call c ->
        {
            c.args().forEach(a -> collectColumnRefs(a, out));
            c.kwargs().values().forEach(a -> collectColumnRefs(a, out));
        }
        case Expr.Binary b ->
        {
            collectColumnRefs(b.left(), out);
            collectColumnRefs(b.right(), out);
        }
        case Expr.Not n -> collectColumnRefs(n.inner(), out);
        default ->
        {
            // literals and boolean nodes contribute no column name
        }
        }
    }

    // ------------------------------------------------------------------
    // Granularity classification
    // ------------------------------------------------------------------


    /**
     * How a subtree contributes to the mask. A boolean node is {@link Kind#RECORD} as soon as one
     * of its leaves is: a single varying leaf is enough to keep the whole subtree varying, under
     * either connective.
     */
    static Kind kind(@Nullable Expr e)
    {
        return switch (e)
        {
        case null -> Kind.NONE;
        case Expr.And and -> combine(and.parts());
        case Expr.Or or -> combine(fold(or.parts()));
        case Expr.Not not -> kind(not.inner());
        default -> isGlobalLeaf(e) ? Kind.GLOBAL : Kind.RECORD;
        };
    }


    private static Kind combine(List<Expr> parts)
    {
        Kind acc = Kind.NONE;
        for (Expr p : parts)
        {
            Kind k = kind(p);
            if (k == Kind.RECORD)
            {
                return Kind.RECORD;
            }
            if (k == Kind.GLOBAL)
            {
                acc = Kind.GLOBAL;
            }
        }
        return acc;
    }


    /**
     * Whether a leaf yields one verdict broadcast to every row. Everything here routes to
     * {@link BroadcastFold} so the lint and {@code RuleClassifier} share one vocabulary (&sect;4.4)
     * — the only judgement made locally is the structural one about comparisons.
     */
    private static boolean isGlobalLeaf(Expr e)
    {
        return switch (e)
        {
        case Expr.Call c -> BroadcastFold.isExistsCall(c)
                || BroadcastFold.isWholeColumnVerdictCall(c)
            // A broadcast column predicate (var_is_null) is one dataset-wide fact about a
            // named column. ⚠ Wiring it here is NOT optional bookkeeping: the lint fires only
            // on `Or`, and every var_is_null rule in the corpus is a conjunction, so leaving
            // it out would have left the lint calling it a RECORD leaf while both routers call
            // it a dataset fact — with nothing red. That silent disagreement is the exact bug
            // class the var_is_null registration cured.
                || BroadcastFold.isBroadcastColumnPredicate(c)
                || BroadcastFold.isDatasetFactBoolCall(c) || BroadcastFold.isLibraryGateCall(c);
        // A comparison is broadcast exactly when neither side reads a row.
        case Expr.Binary b -> BroadcastFold.isDatasetFactOperand(b.left())
                && BroadcastFold.isDatasetFactOperand(b.right());
        // A bare boolean reference is broadcast only as a $-operation result; a COLUMN / DOTTED /
        // WILDCARD reference reads per-row data.
        case Expr.Ref r -> r.kind() == OperandKind.OPERATION_REF;
        case Expr.Lit _ -> true;
        default -> false;
        };
    }
}
