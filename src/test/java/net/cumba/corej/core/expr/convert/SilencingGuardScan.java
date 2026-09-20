package net.cumba.corej.core.expr.convert;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.CheckExprUnits;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CheckCondition;

/**
 * Detector for <em>silencing {@code exists} guards</em> in an authored {@code Check} — the
 * validate-mode port of the classifier from
 * {@code plans/done/PLAN-generator-silencing-guard-hoist.md} (validated by the completed
 * {@code PLAN-scope-variables-guard-migration.md} hand migration: 0 false positives across the
 * migrated rules and the borderline mixed rules CORE-000685/686/689).
 *
 * <p>
 * A silencing guard for X is a top-level-{@code all} {@code exists X} leaf (every ancestor from the
 * leaf to the Check root is an {@code all}) where X is <b>non-dotted</b> and is <b>also used
 * substantively</b> elsewhere in the same Check — by a non-{@code exists}-family operator, in name
 * position (incl. the composite {@code names} list) or in a non-literal value position. Per
 * authoring rule R-4.11 such a guard must instead be expressed as a
 * {@code Requirements.Variables.All} gate. Guards inside an {@code any}/{@code not}, dotted
 * ({@code DOMAIN.COL}) guards, and {@code not_exists} leaves are not silencing guards and are left
 * alone.
 * </p>
 */
public final class SilencingGuardScan
{

    private SilencingGuardScan()
    {
    }

    /**
     * Operators whose Python implementation silently drops an absent referenced column from its
     * <b>grouping key</b> and then over-fires — R-4.11a: a presence guard for a variable these
     * operators use <b>in the comparator (grouping-key) position</b> is REQUIRED in the
     * {@code Check} (the Python engine ignores {@code Scope.Variables}, so hoisting would remove
     * the only guard Python sees) and is never flagged.
     *
     * <p>
     * ⚠ <b>The carve-out is POSITION-AWARE (2026-08-07), not operator-wide.</b> It was originally
     * applied to every operand of these operators, which also hid ~584 package entries / 154
     * {@code Core.Id}s whose guard sits in the <b>target</b> position — where the warrant does not
     * hold. Verified in the fork's {@code check_operators/dataframe_operators.py}:
     * </p>
     * <ul>
     * <li><b>target</b> — {@code is_unique_set} / {@code is_not_unique_set} short-circuit to
     * all-False via {@code _absent_unique_set_target} (:1490-1496, :1504, :1545);
     * {@code is_inconsistent_across_dataset} is not in {@code ABSENT_TARGET_AWARE_OPERATORS}, so
     * {@code _absent_target_as_missing} (:144) substitutes an all-blank column and
     * {@code _check_inconsistency} then finds no non-blank value to disagree about — also
     * all-False. <b>Nothing fires, so no guard is needed.</b></li>
     * <li><b>comparator / grouping key</b> — the absent column is filtered out of the key
     * ({@code is_inconsistent_across_dataset} :1418-1425, {@code is_unique_set} :1511-1515) and the
     * grouping proceeds on the remaining columns, <b>which over-fires</b>. The guard is
     * load-bearing.</li>
     * </ul>
     *
     * <p>
     * The one exception is a <b>negated</b> use: an all-False verdict under a {@code not} becomes
     * all-True, so a target-position operand below a {@code not} is treated as required too. The
     * branch is defensive — no rule has that shape today (measured 2026-08-07: 0 of 1418 carve-out
     * leaves in {@code rules-legacy/}) and none can arrive through the expression corpus either,
     * because the unit walk ({@code CheckExprUnits}) folds {@code not is_unique_set(…)} to the
     * {@code is_not_unique_set} unit rather than a structural {@code not} (all 708 occurrences in
     * {@code rules/} + the since-retired {@code rules-drafted/} sketches took that path). It exists
     * so that a hand-authored {@code not} — the one shape that would silently invert the reasoning
     * above — cannot strip the guard.
     * </p>
     *
     * <p>
     * ⭐ <b>2026-08-23 (Plan A D-8, ruling 3):</b> the target/comparator distinction described above
     * is <b>retired for {@code is_unique_set} / {@code is_not_unique_set}</b> — see
     * {@link #POSITION_BLIND_OPERATORS}. The Python fork that warranted it was cut in wave 33; the
     * engine that ships regroups on the surviving members whichever one is absent, so the
     * {@code name} position is class <b>D</b> like every other member. The paragraphs above remain
     * as the record of why the distinction once existed; they describe the
     * {@code is_inconsistent_across_dataset} arm only from here on.
     * </p>
     *
     * <p>
     * ⚠ <b>OPEN (S4 Q2, owner call):</b> whether {@code is_unique_set} should stay in this set. It
     * has <b>0</b> leaves in {@code rules-legacy/} and 0 occurrences in {@code rules-src/checks} —
     * but that measures the <em>legacy operator spelling</em>, not the operator: the expression
     * corpus carries 708 {@code not is_unique_set(…)} calls, which the unit walk folds to an
     * {@code is_not_unique_set} unit, and {@code is_not_unique_set} is itself
     * {@code ~is_unique_set(…)} in the fork ({@code dataframe_operators.py:1549}) over the same key
     * filter. A bare {@code is_unique_set(…)} <em>is</em> authorable and would then need this
     * entry.
     * </p>
     */
    static final Set<String> PYTHON_GUARD_REQUIRED_OPERATORS = Set.of("is_unique_set",
            "is_not_unique_set", "is_inconsistent_across_dataset");

    /**
     * The subset of {@link #PYTHON_GUARD_REQUIRED_OPERATORS} for which the carve-out is <b>not</b>
     * position-aware — Plan A D-8 (ruling 3, 2026-08-23,
     * {@code plans/done/PLAN-authoring-grammar-unique-set-and-output-exclusion.md} §3.8): the
     * engine builds ONE key tuple from every member of {@code is_(not_)unique_set} and drops an
     * absent member uniformly wherever it sits (EC-53 / Fix #143 — the guard matrix's former
     * {@code D-target} / {@code D-key} rows, "one contract since EC-53", now a single class
     * {@code D}), so a guard on the leaf's {@code name} is exactly as load-bearing as one on a
     * {@code value} member. {@code is_inconsistent_across_dataset} keeps the target/comparator
     * distinction: its {@code name} is the checked value, not a key member (§3.6 D-7).
     */
    static final Set<String> POSITION_BLIND_OPERATORS = Set.of("is_unique_set",
            "is_not_unique_set");

    /**
     * The variables whose top-level {@code exists} guards qualify as silencing guards, in Check
     * order (deduped). Empty when the Check follows R-4.11 — including the R-4.11a carve-out: a
     * guard whose variable is used by a {@link #PYTHON_GUARD_REQUIRED_OPERATORS} leaf <b>in the
     * comparator (grouping-key) position</b> is required, not silencing.
     */
    public static List<String> hoistableVars(CheckCondition root)
    {
        return hoistable(root, true);
    }


    /**
     * The guard variables that the R-4.11a carve-out <em>hides</em> from {@link #hoistableVars}:
     * everything that would be reported if {@link #PYTHON_GUARD_REQUIRED_OPERATORS} were empty,
     * minus what is reported today. These guards are not defects — the carve-out's warrant holds
     * for them — but nothing surfaced them anywhere, so the set could only grow silently.
     */
    public static List<String> carveOutHiddenVars(CheckCondition root)
    {
        List<String> hidden = hoistable(root, false);
        hidden.removeAll(hoistable(root, true));
        return hidden;
    }


    private static List<String> hoistable(CheckCondition root, boolean applyCarveOut)
    {
        Expr check = CheckExprUnits.toExpr(root);
        if (check == null)
        {
            return List.of();
        }
        List<CheckExprUnits.Unit> units = CheckExprUnits.units(check);
        Set<String> substantive = new LinkedHashSet<>();
        Set<String> pythonRequired = new LinkedHashSet<>();
        for (CheckExprUnits.Unit unit : units)
        {
            collectSubstantive(unit, substantive, pythonRequired, applyCarveOut);
        }
        substantive.removeAll(pythonRequired);
        Set<String> out = new LinkedHashSet<>();
        for (CheckExprUnits.Unit unit : units)
        {
            // A silencing guard is a top-level-conjunction `var_exists` whose variable is used
            // substantively elsewhere. A '*'-wildcard guard (e.g. the ADaM *FL/*FN pair
            // templates) binds PER EXPANSION PAIR (WildcardExpander): a dataset may carry some
            // pairs complete and others without the guarded twin, and only the incomplete pairs
            // are silenced. A variable requirement gates the whole rule per dataset and cannot
            // express that, so such guards are required in the Check and never flagged.
            // ('--' domain-prefix names resolve to ONE column per dataset and stay hoistable.)
            String name = unit.name();
            if (unit.topLevel() && "var_exists".equals(unit.operator()) && name != null
                    && name.indexOf('.') < 0 && name.indexOf('*') < 0 && substantive.contains(name))
            {
                out.add(name);
            }
        }
        return new ArrayList<>(out);
    }


    /**
     * Collects one unit's substantive variable uses — the target name(s) of each
     * non-{@code exists}-family unit plus its non-literal value tokens (a literal
     * {@code "SNOMED"}-style value is not a variable use) — and, separately, the variables used by
     * a Python-guard-required operator (R-4.11a) in the position its warrant covers.
     *
     * <p>
     * {@code name}/{@code names} is the operator's <b>target</b> and {@code value} its
     * <b>comparator</b> — the fork's own mapping ({@code models/rule.py:160-172}). Only the
     * comparator side of a carve-out operator is Python-guard-required; see
     * {@link #PYTHON_GUARD_REQUIRED_OPERATORS}. {@link CheckExprUnits.Unit#negated()} carries the
     * structural-{@code not} parity: a target-position all-False becomes an all-True over-fire
     * there, so the target is required too.
     * </p>
     *
     * <p>
     * ⭐ An <b>opaque</b> unit (no v1 classification — a native-only construct) still contributes
     * every reference it reads, collected from its underlying node: a sibling guard in a mixed
     * Check must never silently read as non-silencing (wave-43 review finding; over-approximation
     * is safe — it can only surface more guards, never hide one).
     * </p>
     */
    private static void collectSubstantive(CheckExprUnits.Unit unit, Set<String> out,
            Set<String> pythonRequired, boolean applyCarveOut)
    {
        String op = unit.operator();
        if ("var_exists".equals(op) || "var_not_exists".equals(op))
        {
            return;
        }
        if (op == null)
        {
            collectRefNames(unit.node(), out);
            return;
        }
        boolean carved = applyCarveOut && PYTHON_GUARD_REQUIRED_OPERATORS.contains(op);
        // D-8: for the uniqueness pair the `name` is member 0 of one undifferentiated key
        // tuple, so it takes the comparator verdict in every polarity; only
        // is_inconsistent_across_dataset still distinguishes its target.
        boolean positionBlind = carved && POSITION_BLIND_OPERATORS.contains(op);
        Set<String> targetPosition = carved && (unit.negated() || positionBlind) ? pythonRequired
                : out;
        Set<String> comparatorPosition = carved ? pythonRequired : out;
        if (unit.name() != null)
        {
            targetPosition.add(unit.name());
        }
        targetPosition.addAll(unit.names());
        collectValueTokens(unit.value(), comparatorPosition);
    }


    /**
     * The non-literal value tokens of a unit's comparator side: a reference's name, or the members
     * of a list operand (the v1 array form collected its textual members; the expression form
     * spells references as {@code Ref}s and keeps quoted members as string literals — both are
     * collected, matching the leaf-era over-approximation).
     */
    private static void collectValueTokens(@org.jspecify.annotations.Nullable Expr value,
            Set<String> out)
    {
        switch (value)
        {
        case null ->
        {
            // No comparator side.
        }
        case Expr.Ref r -> out.add(r.name());
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    switch (item)
                    {
                    case Expr.Ref r -> out.add(r.name());
                    case Expr.Lit il when il.kind() == Expr.LitKind.STRING -> out
                            .add((String) il.value());
                    default ->
                    {
                        // Non-textual members carry no variable use.
                    }
                    }
                }
            }
        }
        default ->
        {
            // Calls (accessors) in value position carry no plain variable token.
        }
        }
    }


    /** Every reference name in a subtree — the opaque-unit over-approximation. */
    private static void collectRefNames(Expr e, Set<String> out)
    {
        switch (e)
        {
        case Expr.Ref r -> out.add(r.name());
        case Expr.Call c ->
        {
            c.args().forEach(a -> collectRefNames(a, out));
            c.kwargs().values().forEach(v -> collectRefNames(v, out));
        }
        case Expr.And a -> a.parts().forEach(p -> collectRefNames(p, out));
        case Expr.Or o -> o.parts().forEach(p -> collectRefNames(p, out));
        case Expr.Not n -> collectRefNames(n.inner(), out);
        case Expr.Binary b ->
        {
            collectRefNames(b.left(), out);
            collectRefNames(b.right(), out);
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof Expr ie)
                    {
                        collectRefNames(ie, out);
                    }
                }
            }
        }
        }
    }
}
