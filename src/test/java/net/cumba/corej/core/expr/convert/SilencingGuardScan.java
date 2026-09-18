package net.cumba.corej.core.expr.convert;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.ExprLowering;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.CheckConditionNot;

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
     * because {@code ExprLowering:113-116} rewrites {@code not is_unique_set(…)} to a plain
     * {@code is_not_unique_set} leaf rather than a structural {@code CheckConditionNot} (all 708
     * occurrences in {@code rules/} + the since-retired {@code rules-drafted/} sketches took that
     * path). It exists so that a hand-authored {@code not} — the one shape that would silently
     * invert the reasoning above — cannot strip the guard.
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
     * corpus carries 708 {@code not is_unique_set(…)} calls, which {@code ExprLowering:113-116}
     * rewrites to an {@code is_not_unique_set} leaf, and {@code is_not_unique_set} is itself
     * {@code ~is_unique_set(…)} in the fork ({@code dataframe_operators.py:1549}) over the same key
     * filter. A bare {@code is_unique_set(…)} <em>is</em> authorable
     * ({@code ExprLowering.FUNCTION_OPERATORS}) and would then need this entry.
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


    /**
     * The operator-leaf view of an {@code {expression}} Check — {@code Fix #256}, the pre-flip
     * re-point of {@code plans/done/PLAN-rules-src-expression-flip.md} §7b. A Check reaches the
     * scan as a {@link CheckConditionExpression} only when the strict load-path lowering declined
     * it ({@code CheckConditionDeserializer} keeps it native), so the raised {@code Expr} is
     * lowered here through the engine's own <b>conversion-mode</b> machinery
     * ({@link ExprLowering#toCheckConditionForConversion}) — the offline direction that restores
     * the raise-side rewrites — and the scan then walks exactly the operator-leaf tree the
     * expression is equivalent to. Both authoring forms therefore scan with identical semantics by
     * construction.
     *
     * <p>
     * A native-only expression (no operator-leaf surface even in conversion mode) fails LOUDLY
     * rather than being skipped: a silent skip is exactly the flip-day vacuity the pre-migration
     * lane exists to prevent — give the scan a native arm before such a rule is authored.
     * </p>
     */
    private static @org.jspecify.annotations.Nullable CheckCondition loweredOrNull(
            CheckConditionExpression expression)
    {
        CheckCondition condition = DualFormCheckNode.lowerConditionOrNull(expression.expr());
        if (condition == null)
        {
            // Retry with num() conversions erased — a num(X) read is transparent to this
            // instrument's classification; see DualFormCheckNode.stripNumConversions.
            condition = DualFormCheckNode
                    .lowerConditionOrNull(DualFormCheckNode.stripNumConversions(expression.expr()));
        }
        if (condition != null)
        {
            return condition;
        }
        // A native-only expression (no operator-leaf surface even with the dual-form inverses).
        // When it provably carries no exists-family call it can hide no guard, and the caller
        // decides what its non-guard content contributes (the substantive walk still reads its
        // Ref names). One that DOES carry a presence call fails loudly — a silent skip there is
        // exactly the flip-day vacuity the pre-migration lane exists to prevent
        // (plans/done/PLAN-rules-src-expression-flip.md §7b).
        if (!DualFormCheckNode.carriesPresenceCall(expression.expr()))
        {
            return null;
        }
        throw new IllegalStateException("SilencingGuardScan cannot scan the expression-form"
                + " Check \"" + expression.source() + "\": it has no operator-leaf equivalent"
                + " even in conversion mode, and it carries a presence call. Add a native Expr"
                + " arm to the scan instead of skipping the Check"
                + " (plans/done/PLAN-rules-src-expression-flip.md §7b).");
    }


    private static List<String> hoistable(CheckCondition root, boolean applyCarveOut)
    {
        Set<String> substantive = new LinkedHashSet<>();
        Set<String> pythonRequired = new LinkedHashSet<>();
        collectSubstantive(root, substantive, pythonRequired, applyCarveOut, false);
        substantive.removeAll(pythonRequired);
        Set<String> out = new LinkedHashSet<>();
        collectTopLevelSilencing(root, substantive, out);
        return new ArrayList<>(out);
    }


    /** DFS following only {@code all} edges — a leaf qualifies iff no any/not ancestor. */
    private static void collectTopLevelSilencing(CheckCondition node, Set<String> substantive,
            Set<String> out)
    {
        switch (node)
        {
        case CheckConditionAll all ->
        {
            for (CheckCondition c : all.getConditions())
            {
                collectTopLevelSilencing(c, substantive, out);
            }
        }
        case CheckConditionLeaf leaf ->
        {
            String name = leaf.getName();
            // A '*'-wildcard guard (e.g. the ADaM *FL/*FN pair templates) binds PER EXPANSION
            // PAIR (WildcardExpander): a dataset may carry some pairs complete and others
            // without the guarded twin, and only the incomplete pairs are silenced.
            // A variable requirement gates the whole rule per dataset and cannot express that,
            // so such
            // guards are required in the Check and never flagged. ('--' domain-prefix names
            // resolve to ONE column per dataset and remain hoistable.)
            // `var_exists` joined at the flip: the writer disambiguates every generic `exists`,
            // so the model view spells a column-presence guard concretely.
            String operator = leaf.getOperator();
            if ("var_exists".equals(operator) && name != null && name.indexOf('.') < 0
                    && name.indexOf('*') < 0 && substantive.contains(name))
            {
                out.add(name);
            }
        }
        case CheckConditionExpression expression ->
        {
            CheckCondition loweredView = loweredOrNull(expression);
            if (loweredView != null)
            {
                collectTopLevelSilencing(loweredView, substantive, out);
            }
            // A presence-free native-only expression can carry no exists guard — nothing to add.
        }
        default ->
        {
            // any / not / constant subtrees never contribute top-level guards.
        }
        }
    }


    /**
     * Collects every substantive variable use in the whole tree — the name(s) of each
     * non-{@code exists}-family leaf plus its value tokens when {@code value_is_literal} is not set
     * (a literal {@code "SNOMED"}-style value is not a variable use) — and, separately, the
     * variables used by a Python-guard-required operator (R-4.11a) in the position its warrant
     * covers.
     *
     * <p>
     * {@code name}/{@code names} is the operator's <b>target</b> and {@code value} its
     * <b>comparator</b> — that is the fork's own mapping ({@code models/rule.py:160-172}:
     * {@code value["target"] = condition["name"]},
     * {@code value["comparator"] = condition["value"]}). Only the comparator side of a carve-out
     * operator is Python-guard-required; see {@link #PYTHON_GUARD_REQUIRED_OPERATORS}.
     * </p>
     *
     * @param negated
     *            whether an odd number of {@code not} ancestors sits above this node — a
     *            target-position all-False becomes an all-True over-fire there, so the target is
     *            required too
     */
    private static void collectSubstantive(CheckCondition node, Set<String> out,
            Set<String> pythonRequired, boolean applyCarveOut, boolean negated)
    {
        switch (node)
        {
        case CheckConditionAll all -> all.getConditions()
                .forEach(c -> collectSubstantive(c, out, pythonRequired, applyCarveOut, negated));
        case CheckConditionAny any -> any.getConditions()
                .forEach(c -> collectSubstantive(c, out, pythonRequired, applyCarveOut, negated));
        case CheckConditionNot not -> collectSubstantive(not.getCondition(), out, pythonRequired,
                applyCarveOut, !negated);
        case CheckConditionLeaf leaf ->
        {
            String op = leaf.getOperator();
            if ("var_exists".equals(op) || "var_not_exists".equals(op))
            {
                return;
            }
            boolean carved = applyCarveOut && PYTHON_GUARD_REQUIRED_OPERATORS.contains(op);
            // D-8: for the uniqueness pair the `name` is member 0 of one undifferentiated key
            // tuple, so it takes the comparator verdict in every polarity; only
            // is_inconsistent_across_dataset still distinguishes its target.
            boolean positionBlind = carved && POSITION_BLIND_OPERATORS.contains(op);
            Set<String> targetPosition = carved && (negated || positionBlind) ? pythonRequired
                    : out;
            Set<String> comparatorPosition = carved ? pythonRequired : out;
            if (leaf.getName() != null)
            {
                targetPosition.add(leaf.getName());
            }
            if (leaf.getNames() != null)
            {
                targetPosition.addAll(leaf.getNames());
            }
            if (!Boolean.TRUE.equals(leaf.getValueIsLiteral()))
            {
                collectValueTokens(leaf.getValue(), comparatorPosition);
            }
        }
        case CheckConditionExpression expression ->
        {
            CheckCondition loweredView = loweredOrNull(expression);
            if (loweredView != null)
            {
                collectSubstantive(loweredView, out, pythonRequired, applyCarveOut, negated);
            }
            else
            {
                // Native-only, presence-free — but its variable reads are still SUBSTANTIVE
                // uses: collect the Ref names from the raised Expr directly, so a sibling
                // guard in a mixed Check can never silently read as non-silencing (wave-43
                // review finding; over-approximation is safe — it can only surface more
                // guards, never hide one).
                DualFormCheckNode.collectRefNames(expression.expr(), out);
            }
        }
        default ->
        {
            // A constant carries no leaf operands to scan.
        }
        }
    }


    private static void collectValueTokens(JsonNode value, Set<String> out)
    {
        if (value == null)
        {
            return;
        }
        if (value.isTextual())
        {
            out.add(value.asText());
        }
        else if (value.isArray())
        {
            for (JsonNode item : value)
            {
                if (item.isTextual())
                {
                    out.add(item.asText());
                }
            }
        }
    }
}
