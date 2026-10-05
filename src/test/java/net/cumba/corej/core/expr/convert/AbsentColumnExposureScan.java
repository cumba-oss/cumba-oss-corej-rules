package net.cumba.corej.core.expr.convert;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;

/**
 * EC-43 Phase 0 — exposure scanner for leaves that change verdict when an absent column is folded
 * to all-missing.
 *
 * <p>
 * This is the <b>AST</b> scanner the plan mandates in place of the text heuristic that produced the
 * 513/37 upper bound. A text test asks "does this expression mention a guard for the column"; that
 * calls {@code CORE-000587} guarded, because its {@code var_exists("TSVAL")} dominates only the
 * <em>first</em> disjunct while the {@code !~} leaf sits unguarded in the second. This scanner asks
 * the structural question instead: <b>does a guard dominate <em>this leaf</em></b>.
 * </p>
 *
 * <p>
 * <b>Method.</b> The expression is first converted to negation-normal form — {@code Not} is pushed
 * through {@code And}/{@code Or} by De Morgan and comparison operators are flipped to their negated
 * form — so "dominates" reduces to "is an {@code And}-sibling on the root&rarr;leaf path". A
 * disjunct never dominates its siblings. The five guard shapes the shipped corpus actually uses are
 * recognised (see {@link GuardShape}); shapes (d) and (e) are rule-level and supplied by the caller
 * because they live outside the expression.
 * </p>
 *
 * <p>
 * <b>Scope is Q1 = uniform.</b> Positive comparison leaves are collected alongside negative ones:
 * the fold reaches both polarities, and the newly-observable positive class is real (a
 * {@code len(X) < n} fires once {@code len(«missing»)} is {@code 0}).
 * </p>
 *
 * <p>
 * The scanner reports; it decides nothing. A leaf is emitted with the guard shape that dominates it
 * (possibly {@link GuardShape#NONE}), and the caller intersects with the library {@code core}
 * classification and buckets the results.
 * </p>
 */
public final class AbsentColumnExposureScan
{

    private AbsentColumnExposureScan()
    {
    }

    /** How (or whether) a presence guard dominates a leaf. */
    public enum GuardShape
    {
        /** No guard dominates the leaf — it is exposed. */
        NONE,
        /** (a) {@code var_exists("X")} / {@code exists("X")} as a dominating conjunct. */
        VAR_EXISTS_CONJUNCT,
        /**
         * (b) the leaf sits in an {@code or} whose sibling is {@code not var_exists("X")} — the
         * FDA/CORE authoring idiom. Fold-neutral by construction: with X absent the sibling is
         * already true, so the leaf's verdict cannot matter.
         */
        NOT_VAR_EXISTS_DISJUNCT,
        /**
         * (c) {@code not empty(X)} / {@code non_empty(X)} as a dominating conjunct. A guard only
         * because {@code empty} fires on an absent column ({@code FIRES_ON_ABSENT_COLUMN}) — i.e.
         * its guard status is coupled to a decision this plan may change. Reported separately.
         */
        NOT_EMPTY_CONJUNCT,
        /**
         * (d) the column is named by {@code Requirements.Variables.All} (rule-level, Java-only) —
         * the rule cannot run at all unless the column is present.
         *
         * <p>
         * ⛔ {@code All} <b>only</b>. Under {@code Any} being named does not imply being present
         * (the facet is met as long as <em>one</em> entry is), and under {@code None} being named
         * implies the column is guaranteed <b>absent</b> — feeding either here would mark exposed
         * leaves as guarded and shrink the census silently, which is the failure this whole report
         * exists to detect. The constant keeps its name: it is a stable report token.
         * </p>
         */
        SCOPE_VARIABLES,
        /** (e) the column is guarded by the rule's {@code Precondition} block (rule-level). */
        PRECONDITION
    }


    /**
     * One candidate leaf: a comparison whose name-position operand resolves to a bare primary
     * column, possibly through a tag / unary-transform wrapper.
     *
     * @param column
     *            the column the fold would materialise
     * @param op
     *            the operator as it appears after NNF (so a negated positive reads as its negative)
     * @param positive
     *            whether the leaf is a positive comparison (Q1 = uniform collects both)
     * @param wrapper
     *            the tag / transform wrapping the column ({@code ""} when the operand is a bare
     *            ref) — a wrapped leaf is unreachable by {@code NonEmptyGuardInliner}, so this
     *            column feeds the §2.8 census
     * @param guard
     *            the guard shape dominating this leaf
     */
    public record Leaf(String column, String op, boolean positive, String wrapper, GuardShape guard)
    {

    }

    /**
     * Tag markers and pure unary transforms whose operand is still the column under test. The
     * marker half is <b>derived</b> from
     * {@link net.cumba.corej.core.expr.eval.ExprCompiler#comparisonMarkers()} (M4, terminal review
     * of {@code PLAN-typed-expression-engine}): the transcription here had drifted — {@code time}
     * joined the compiler's conversions in phase 3b and never reached this list, so a
     * {@code time(X)}-wrapped read was invisible to this scan. Only the pure string transforms
     * remain hand-listed; they are not markers and the compiler cannot enumerate them.
     *
     * <p>
     * ⚑ The derivation changed exactly ONE member here — {@code time}. {@code str} was already in
     * the hand list and stays in, which is the answer to R2-13's question about it: a
     * {@code str(X)} wrapper leaves the operand the column under test, so the scan must see through
     * it. The opposite treatment at {@code CheckExprUnits} (and at the deleted operator-leaf
     * lowering) is about the retired v1 vocabulary having no type-insensitive-equality family, not
     * about what {@code str} wraps.
     * </p>
     *
     * <p>
     * ⚑ 2026-09-30 ({@code PLAN-dynamic-column-functions} §2.1): {@code str} left the compiler's
     * marker roster (it is a registered conversion now), so it is hand-listed here beside the other
     * pure transforms — the scan still sees through it, and its population does not move.
     * </p>
     */
    private static final Set<String> UNWRAPPABLE = java.util.stream.Stream
            .concat(net.cumba.corej.core.expr.eval.ExprCompiler.comparisonMarkers().stream(),
                    java.util.stream.Stream.of("str", "len", "length", "upper", "upcase", "lower",
                            "lowcase", "trim"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** The presence-test functions that can act as a dominating guard. */
    private static final Set<String> EXISTS_CALLS = Set.of("var_exists");

    /**
     * Registered BOOLEAN predicates that route through {@code ExprCompiler.compileBoolCall}'s
     * generic tail and therefore fold an absent operand. {@code empty} is excluded — it owns its
     * absent-column contract via {@code FIRES_ON_ABSENT_COLUMN}. {@code is_missing} left that set
     * with register D50 (owner 2026-10-05) and now takes the generic tail, so it is listed here.
     *
     * <p>
     * Added after the Phase 3 dynamic run: the first cut collected only {@link Expr.Binary} leaves
     * and so missed this whole surface, under-reporting the exposure.
     * {@code CDISC-CG0283/0285/0286} ({@code invalid_date(TSVAL)}) fired on the real fixture
     * without ever appearing in the work list.
     * </p>
     */
    private static final Set<String> BOOLEAN_PREDICATES = Set.of("is_integer", "is_not_integer",
            "contains", "does_not_contain", "starts_with", "ends_with", "equalsIgnoreCase",
            "prefix_matches", "suffix_matches", "imatches", "between", "invalid_date",
            "is_valid_date", "is_complete_date", "is_partial_date", "is_incomplete_date",
            "invalid_duration", "is_valid_duration", "present", "is_present",
            // Fix #157 — the date-portion pair. is_not_complete_date_part fires on an absent
            // column (its operand folds to ""), so it must be classified here or an audit that
            // keys on the operator name would score it as a safe positive leaf.
            "is_complete_date_part", "is_not_complete_date_part",
            // D50 (owner 2026-10-05): strict is_missing folds an absent operand to its type
            // default like any generic predicate ("" ⇒ false, numeric-expected MIS ⇒ true).
            "is_missing");

    /**
     * Scans one rule's Check expression.
     *
     * @param root
     *            the parsed Check expression
     * @param scopeVariables
     *            the rule's {@code Requirements.Variables.All} entries (upper-cased), or empty. ⛔
     *            {@code All} only — see {@link GuardShape#SCOPE_VARIABLES}
     * @param preconditionColumns
     *            columns guarded by the rule's {@code Precondition} block, or empty
     * @return every candidate leaf, each tagged with the guard shape that dominates it
     */
    public static List<Leaf> scan(Expr root, Collection<String> scopeVariables,
            Collection<String> preconditionColumns)
    {
        List<Leaf> out = new ArrayList<>();
        collect(nnf(root, false), Set.of(), Set.of(), Set.of(), out);
        Set<String> scope = upper(scopeVariables);
        Set<String> pre = upper(preconditionColumns);
        List<Leaf> resolved = new ArrayList<>(out.size());
        for (Leaf leaf : out)
        {
            GuardShape shape = leaf.guard();
            if (shape == GuardShape.NONE && scope.contains(leaf.column()))
            {
                shape = GuardShape.SCOPE_VARIABLES;
            }
            if (shape == GuardShape.NONE && pre.contains(leaf.column()))
            {
                shape = GuardShape.PRECONDITION;
            }
            resolved.add(
                    new Leaf(leaf.column(), leaf.op(), leaf.positive(), leaf.wrapper(), shape));
        }
        return resolved;
    }


    private static Set<String> upper(Collection<String> names)
    {
        Set<String> out = new LinkedHashSet<>();
        for (String n : names)
        {
            if (n != null)
            {
                out.add(n.toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Negation-normal form
    // ---------------------------------------------------------------------


    /**
     * Pushes {@code Not} inward so that "dominates" reduces to "is an {@code And}-sibling". A
     * {@link Expr.Binary} under a negation is rewritten to its flipped operator; a
     * {@link Expr.Call} has no negated form, so it keeps an explicit {@link Expr.Not} wrapper
     * (which is exactly what makes {@code not empty(X)} recognisable as guard shape (c)).
     */
    static Expr nnf(Expr e, boolean negated)
    {
        return switch (e)
        {
        case Expr.Not n -> nnf(n.inner(), !negated);
        case Expr.And a -> negated ? new Expr.Or(nnfAll(a.parts(), true))
                : new Expr.And(nnfAll(a.parts(), false));
        case Expr.Or o -> negated ? new Expr.And(nnfAll(o.parts(), true))
                : new Expr.Or(nnfAll(o.parts(), false));
        case Expr.Binary b -> negated ? new Expr.Binary(flip(b.op()), b.left(), b.right()) : b;
        default -> negated ? new Expr.Not(e) : e;
        };
    }


    private static List<Expr> nnfAll(List<Expr> parts, boolean negated)
    {
        List<Expr> out = new ArrayList<>(parts.size());
        for (Expr p : parts)
        {
            out.add(nnf(p, negated));
        }
        return out;
    }


    /**
     * The negated form of a comparison operator; arithmetic operators have none and are returned.
     */
    private static Expr.BinOp flip(Expr.BinOp op)
    {
        return switch (op)
        {
        case EQ -> Expr.BinOp.NEQ;
        case NEQ -> Expr.BinOp.EQ;
        case LT -> Expr.BinOp.GE;
        case GE -> Expr.BinOp.LT;
        case GT -> Expr.BinOp.LE;
        case LE -> Expr.BinOp.GT;
        case MATCH -> Expr.BinOp.NMATCH;
        case NMATCH -> Expr.BinOp.MATCH;
        case IN -> Expr.BinOp.NOT_IN;
        case NOT_IN -> Expr.BinOp.IN;
        default -> op;
        };
    }

    // ---------------------------------------------------------------------
    // Dominance walk
    // ---------------------------------------------------------------------


    private static void collect(Expr e, Set<String> conjunctGuards, Set<String> notEmptyGuards,
            Set<String> notExistsGuards, List<Leaf> out)
    {
        switch (e)
        {
        case Expr.And a ->
        {
            // Every conjunct dominates every other conjunct, so gather the guards first.
            Set<String> guards = new LinkedHashSet<>(conjunctGuards);
            Set<String> notEmpty = new LinkedHashSet<>();
            for (Expr part : a.parts())
            {
                addExistsGuard(part, guards);
                addNotEmptyGuard(part, notEmpty);
            }
            Set<String> inheritedNotEmpty = new LinkedHashSet<>(notEmptyGuards);
            inheritedNotEmpty.addAll(notEmpty);
            for (Expr part : a.parts())
            {
                collect(part, guards, inheritedNotEmpty, notExistsGuards, out);
            }
        }
        case Expr.Or o ->
        {
            // A disjunct does NOT dominate its siblings. The one exception that matters is the
            // `not var_exists(X) or <leaf on X>` idiom: with X absent the sibling is already true,
            // so the leaf is fold-neutral.
            for (Expr part : o.parts())
            {
                Set<String> siblingNotExists = new LinkedHashSet<>(notExistsGuards);
                for (Expr other : o.parts())
                {
                    if (other != part)
                    {
                        addNotExistsGuard(other, siblingNotExists);
                    }
                }
                collect(part, conjunctGuards, notEmptyGuards, siblingNotExists, out);
            }
        }
        case Expr.Binary b -> emit(b, conjunctGuards, notEmptyGuards, notExistsGuards, out);
        case Expr.Call c -> emitCall(c, true, conjunctGuards, notEmptyGuards, notExistsGuards, out);
        case Expr.Not n when n.inner() instanceof Expr.Call c -> emitCall(c, false, conjunctGuards,
                notEmptyGuards, notExistsGuards, out);
        default ->
        {
            // Ref / Lit — not a leaf that reads a column.
        }
        }
    }


    /** {@code var_exists("X")} / {@code exists("X")} contributes X. */
    private static void addExistsGuard(Expr part, Set<String> out)
    {
        if (part instanceof Expr.Call c && EXISTS_CALLS.contains(c.name()) && c.args().size() == 1)
        {
            String name = operandName(c.args().get(0));
            if (name != null)
            {
                out.add(name);
            }
        }
    }


    /**
     * {@code not empty(X)} (NNF of {@code non_empty}) contributes X. {@code not is_missing(X)} does
     * NOT (register D50, owner 2026-10-05): is_missing is strict, so over an absent character
     * column (default {@code ""}) {@code not is_missing(X)} is true on every row and guards
     * nothing.
     */
    private static void addNotEmptyGuard(Expr part, Set<String> out)
    {
        if (part instanceof Expr.Not n && n.inner() instanceof Expr.Call c
                && "empty".equals(c.name()) && c.args().size() == 1)
        {
            String name = operandName(c.args().get(0));
            if (name != null)
            {
                out.add(name);
            }
        }
    }


    /** {@code not var_exists("X")} contributes X (the OR-sibling idiom). */
    private static void addNotExistsGuard(Expr part, Set<String> out)
    {
        if (part instanceof Expr.Not n && n.inner() instanceof Expr.Call c
                && EXISTS_CALLS.contains(c.name()) && c.args().size() == 1)
        {
            String name = operandName(c.args().get(0));
            if (name != null)
            {
                out.add(name);
            }
        }
    }


    private static void emit(Expr.Binary b, Set<String> conjunctGuards, Set<String> notEmptyGuards,
            Set<String> notExistsGuards, List<Leaf> out)
    {
        Unwrapped lhs = unwrap(b.left());
        if (lhs == null)
        {
            return;
        }
        GuardShape shape;
        if (notExistsGuards.contains(lhs.column()))
        {
            shape = GuardShape.NOT_VAR_EXISTS_DISJUNCT;
        }
        else if (conjunctGuards.contains(lhs.column()))
        {
            shape = GuardShape.VAR_EXISTS_CONJUNCT;
        }
        else if (notEmptyGuards.contains(lhs.column()))
        {
            // (c) is reported separately from (a) on purpose: its guard status depends on
            // `empty` firing on an absent column (FIRES_ON_ABSENT_COLUMN), a decision this plan
            // may change — so these leaves must be re-checked if T1 is retired.
            shape = GuardShape.NOT_EMPTY_CONJUNCT;
        }
        else
        {
            shape = GuardShape.NONE;
        }
        out.add(new Leaf(lhs.column(), b.op().name(), isPositive(b.op()), lhs.wrapper(), shape));
    }


    /** A registered BOOLEAN predicate over a name-position column — the compileBoolCall surface. */
    private static void emitCall(Expr.Call c, boolean positive, Set<String> conjunctGuards,
            Set<String> notEmptyGuards, Set<String> notExistsGuards, List<Leaf> out)
    {
        if (!BOOLEAN_PREDICATES.contains(c.name()) || c.args().isEmpty())
        {
            return;
        }
        Unwrapped operand = unwrap(c.args().get(0));
        if (operand == null)
        {
            return;
        }
        GuardShape shape;
        if (notExistsGuards.contains(operand.column()))
        {
            shape = GuardShape.NOT_VAR_EXISTS_DISJUNCT;
        }
        else if (conjunctGuards.contains(operand.column()))
        {
            shape = GuardShape.VAR_EXISTS_CONJUNCT;
        }
        else if (notEmptyGuards.contains(operand.column()))
        {
            shape = GuardShape.NOT_EMPTY_CONJUNCT;
        }
        else
        {
            shape = GuardShape.NONE;
        }
        out.add(new Leaf(operand.column(), c.name(), positive, operand.wrapper(), shape));
    }


    private static boolean isPositive(Expr.BinOp op)
    {
        return switch (op)
        {
        case NEQ, NMATCH, NOT_IN -> false;
        default -> true;
        };
    }

    // ---------------------------------------------------------------------
    // Operand unwrapping
    // ---------------------------------------------------------------------

    /** A column reference and the tag / transform (if any) that wrapped it. */
    private record Unwrapped(String column, String wrapper)
    {

    }

    /**
     * Resolves a name-position operand to its primary column, seeing through a single tag / pure
     * unary transform ({@code date(X)}, {@code len(X)}, {@code upper(X)}, …). Multi-argument and
     * dereferencing calls ({@code colref}, {@code concat}, {@code coalesce}, {@code tuple},
     * aggregates, metadata accessors) are NOT unwrapped — their argument is not the column under
     * test. Returns {@code null} when the operand is not a primary column.
     */
    private static @org.jspecify.annotations.Nullable Unwrapped unwrap(Expr e)
    {
        if (e instanceof Expr.Call c && UNWRAPPABLE.contains(c.name()) && c.args().size() == 1
                && c.kwargs().isEmpty())
        {
            String name = columnName(c.args().get(0));
            return name == null ? null : new Unwrapped(name, c.name());
        }
        String name = columnName(e);
        return name == null ? null : new Unwrapped(name, "");
    }


    /**
     * The column name of a column ref — a bare primary column, or (W3d of
     * {@code PLAN-merged-evaluation-table}, N28-lint) a qualified {@code J.X}, which is a column of
     * the merged evaluation table and folds like a bare one when absent — or {@code null}. The name
     * is upper-cased whole, qualifier included, so a guard {@code var_exists("J.X")} matches it.
     */
    private static @org.jspecify.annotations.Nullable String columnName(Expr e)
    {
        if (e instanceof Expr.Ref r && (r.kind() == OperandKind.COLUMN
                || r.kind() == OperandKind.WILDCARD_COLUMN || r.kind() == OperandKind.DOTTED_REF)
                && !r.name().startsWith("$"))
        {
            return r.name().toUpperCase(Locale.ROOT);
        }
        return null;
    }


    /** A guard operand is written either quoted ({@link Expr.Lit}) or bare ({@link Expr.Ref}). */
    private static @org.jspecify.annotations.Nullable String operandName(Expr e)
    {
        if (e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING)
        {
            return ((String) lit.value()).toUpperCase(Locale.ROOT);
        }
        return columnName(e);
    }
}
