package net.cumba.corej.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.expr.MetadataOperandMapping;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.CheckConditionNot;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ <b>The corpus lints' structural view of a {@code Check} expression — the direct-{@code Expr}
 * successor of the retired operator-leaf view.</b>
 *
 * <p>
 * Until phase 7 of {@code PLAN-typed-expression-engine.md} the corpus instruments read the v1
 * {@code CheckConditionLeaf} AST, first as the deserializer's own output and then (829b4b89)
 * through the {@code LoadPathLowering} shim that replayed the retired strict
 * {@code ExprLowering.toCheckCondition} branch. D121 retires that model entirely —
 * {@code CheckConditionLeaf}, {@code CheckOperator}, {@code ExprLowering} and {@code CheckToExpr}'s
 * leaf half all go — so the instruments now walk the parsed {@link Expr} itself.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>The walk is written to be population-preserving, not merely equivalent.</b> Every pinned
 * lint population (guard surface 82, bucket-c 223, intent 4, refuted naive readings {10, 10, 10},
 * the left-join carriers) was measured against the leaf view, so each {@link Unit} reports the
 * <b>v1-vocabulary operator name</b> the strict lowering would have produced for its shape — the
 * classification half of the retired {@code ExprLowering.toCheckCondition}, ported here without the
 * {@code CheckConditionLeaf} reconstruction:
 * </p>
 * <ul>
 * <li>{@code not} folds to a negative operator exactly where the strict lowering folded it
 * ({@code not empty(X)} → {@code non_empty}, the Q1 group pairs, {@code not contains} →
 * {@code does_not_contain}, …) and stays a structural combinator everywhere else — so a
 * {@code not var_exists(X)} conjunct is still invisible to a top-level-conjunct consumer, exactly
 * as it was in the leaf view (that asymmetry is load-bearing for the refuted-naive-reading
 * control's pinned counts);</li>
 * <li>a bare positive group call ({@code is_unique_relationship(…)}) reports its negative operator
 * with {@code topLevel == false}, mirroring the {@code Not(<negative leaf>)} the strict lowering
 * built for it;</li>
 * <li>comparison units take their {@code name} from the <b>left</b> operand only (through the
 * {@code date}/{@code len}/{@code date_part}/{@code time_part}/{@code lowcase}/{@code upcase}
 * wrappers, and — tolerantly — through {@code num()}), never from the value side: the v1 leaf held
 * one {@code name}, and the dotted-prefix consumers' pinned sets depend on the value side NOT
 * counting as a read.</li>
 * </ul>
 *
 * <p>
 * A shape with no v1 classification (an unknown function, an arithmetic or call-typed left operand,
 * a bare boolean reference) is still emitted, as an <b>opaque</b> unit ({@code operator == null}) —
 * nothing is silently skipped — and {@link Unit#strict()} records whether the strict lowering would
 * have accepted the shape, which is what the instruments' corpus-blindness censuses count.
 * </p>
 */
public final class CheckExprUnits
{

    /** Predicates the strict lowering mapped 1:1 to a like-named leaf operator. */
    private static final Set<String> UNARY_PREDICATES = Set.of("ds_exists", "ds_not_exists",
            "var_exists", "var_not_exists", "var_is_null", "empty", "non_empty", "is_complete_date",
            "is_incomplete_date", "invalid_date", "invalid_duration", "is_integer",
            "is_not_integer", "is_complete_date_part", "is_not_complete_date_part");

    /** Function operators whose call form is the operator name verbatim. */
    private static final Set<String> FUNCTION_OPERATORS = Set.of("has_multiple_values_for",
            "is_not_unique_relationship", "is_not_unique_set", "is_unique_set",
            "is_inconsistent_across_dataset", "shares_no_elements_with", "not_contains_all",
            "has_same_values", "is_not_ordered_subset_of", "inconsistent_enumerated_columns",
            "has_not_equal_length", "has_equal_length", "is_not_unique_value",
            "present_on_multiple_rows_within", "not_present_on_multiple_rows_within",
            "empty_within_except_last_row", "does_not_have_next_corresponding_record",
            "does_not_equal_string_part", "has_next_corresponding_record");

    /** The uniqueness pair whose single list operand re-encodes as name + value in v1. */
    private static final Set<String> UNIQUE_SET_OPERATORS = Set.of("is_unique_set",
            "is_not_unique_set");

    /** Positive group call → the negative v1 operator the strict lowering reported for it. */
    private static final Map<String, String> POSITIVE_TO_NEGATIVE = Map.of("is_unique_relationship",
            "is_not_unique_relationship", "contains_all", "not_contains_all",
            "shares_elements_with", "shares_no_elements_with", "is_ordered_subset_of",
            "is_not_ordered_subset_of", "is_unique_value", "is_not_unique_value", "is_unique_set",
            "is_not_unique_set");

    private static final Set<String> SUBSTRING_PREDICATES = Set.of("contains", "does_not_contain",
            "starts_with", "ends_with");

    /**
     * Left-operand wrappers the v1 leaf held as an operator family rather than as a call. ⚑ The one
     * copy since {@code PLAN-retire-check-lowering} (2026-10): the two mirrors that
     * {@code UnaryWrapperRosterDriftTest} kept in step — the operator-leaf lowering
     * {@code DualFormCheckNode} and the Python {@code scripts/lib/check_expr.py} — were deleted
     * with the lowering.
     */
    private static final Set<String> UNARY_WRAPPERS = Set.of("date", "len", "date_part",
            "time_part", "lowcase", "upcase");

    /**
     * Every {@code ExprCompiler.comparisonMarkers()} member, each accounted for by an explicit
     * decision in this classifier (M4, terminal review of {@code PLAN-typed-expression-engine} —
     * the {@code time} conversion reached the corpus in phase 3c while this class still ignored
     * it). {@code ComparisonMarkerRosterDriftTest} asserts this set EQUALS the compiler's, so a
     * marker added to (or removed from) the engine reds a test here instead of silently vanishing
     * from the unit view:
     * <ul>
     * <li>{@code date}/{@code date_part}/{@code time_part} — v1 operator families,
     * {@link #UNARY_WRAPPERS};</li>
     * <li>{@code num}/{@code time} — conversions with no v1 surface, read through tolerantly in
     * {@code operand()} (name resolves, strictness lost);</li>
     * <li>{@code str} — ⚑ no longer a compiler marker (PLAN-dynamic-column-functions §2.1, owner
     * Q10, 2026-09-30: a registered conversion, {@code str(A) == str(B)} is plain string equality),
     * so it left this set with the compiler's roster. The type-insensitive shape
     * {@code str(X) ==/!= str(V)} is still classified below by its TEXT, unchanged, so no pinned
     * lint population moves.</li>
     * </ul>
     */
    static Set<String> acknowledgedComparisonMarkers()
    {
        return Set.of("date", "date_part", "time_part", "num", "time");
    }

    /**
     * Raise-side regex predicates ({@code CheckToExpr}'s phase-3 optimisation). No strict surface;
     * the conversion arm reported them as {@code matches_regex}, which the instruments keyed on.
     */
    private static final Set<String> REGEX_PREDICATES = Set.of("is_numeric", "has_alpha",
            "has_digit", "is_valid_name", "is_valid_testcd");

    private CheckExprUnits()
    {
    }

    /**
     * One structural unit of a {@code Check} expression — the successor of one
     * {@code CheckConditionLeaf}.
     *
     * @param operator
     *            the v1-vocabulary operator name for the unit's shape, or {@code null} for a shape
     *            with no v1 classification (opaque — a lint sees it exists but cannot key on it)
     * @param name
     *            the target ({@code name}-position) operand: a column, {@code $}-id, dotted
     *            {@code D.COL}, dataset name (for {@code ds_*}), or metadata-operand name; may be
     *            {@code null}
     * @param names
     *            the composite target for a {@code tuple(...)}-membership unit; empty otherwise
     * @param value
     *            the value-side expression (comparison right side / second argument / {@code keys=}
     *            operand), or {@code null}
     * @param negated
     *            whether an odd number of <i>structural</i> {@code not} nodes sits above the unit
     *            (a folded negative operator does not count, exactly as in the leaf view)
     * @param conjunctive
     *            whether the whole {@code Check} implies the unit (polarity-aware: {@code all}
     *            preserves implication in positive polarity, {@code any} under a {@code not})
     * @param topLevel
     *            whether the unit is a top-level conjunct — a direct child of the root or of
     *            root-level nested {@code all}s ({@code and} is associative)
     * @param path
     *            the combinator path from the root, {@code all}/{@code any}/{@code not} joined with
     *            {@code /}; empty for a bare root unit
     * @param strict
     *            whether the retired <b>strict</b> lowering accepted this shape — {@code false} for
     *            the tolerant extensions (a {@code num()} operand, a raise-side regex predicate, a
     *            {@code _matched_} flag) and for opaque units
     */
    public record Unit(@Nullable String operator, @Nullable String name, List<String> names,
            @Nullable Expr value, boolean negated, boolean conjunctive, boolean topLevel,
            String path, boolean strict, Expr node)
    {
    }

    /** Every unit of {@code root}, in document order. */
    public static List<Unit> units(Expr root)
    {
        List<Unit> out = new ArrayList<>();
        walk(root, true, true, true, "", out);
        return out;
    }


    /**
     * The expression of a rule's {@code Check}, reassembled across the structural wrappers the
     * model still has ({@code all:} / {@code any:} / {@code not:} of {@code expression:} nodes —
     * the shape synthetic harnesses build). {@code null} when the rule has no Check.
     */
    public static @Nullable Expr checkExpr(Rule rule)
    {
        return toExpr(rule.getCheck());
    }


    /**
     * Wave 0 ({@code PLAN-binding-expressions} C2–C5): the datasets the <b>compiled</b> binding
     * {@code name} of {@code rule} reads — every nested call's {@code domain=} string literal and,
     * when {@code dotted} is set, every dotted reference's qualifier — in first-seen order, as
     * authored. Empty when {@code name} is not a compiled binding (an operation binding keeps its
     * own {@code domain:} arm in every reader). The corpus readers that resolve a {@code $}-id to
     * the dataset it reads share this one walk.
     *
     * @param rule
     *            the (normalised) rule
     * @param name
     *            the {@code $}-name, may be {@code null}
     * @param dotted
     *            whether dotted references count ({@code false}: only the "read through an
     *            operation" surface, a nested call's {@code domain=})
     * @return the datasets read
     */
    public static java.util.SequencedSet<String> compiledBindingDatasets(Rule rule,
            @Nullable String name, boolean dotted)
    {
        java.util.SequencedSet<String> out = new java.util.LinkedHashSet<>();
        net.cumba.corej.core.model.CompiledBinding binding = rule.compiledBinding(name);
        if (binding != null)
        {
            collectDatasets(binding.expression(), dotted, out);
        }
        return out;
    }


    private static void collectDatasets(Expr e, boolean dotted, Set<String> out)
    {
        switch (e)
        {
        case Expr.Ref ref ->
        {
            int dot = ref.name().indexOf('.');
            if (dotted && dot > 0 && !ref.name().startsWith("$"))
            {
                out.add(ref.name().substring(0, dot));
            }
        }
        case Expr.Call call ->
        {
            if (call.kwargs().get("domain") instanceof Expr.Lit lit
                    && lit.kind() == Expr.LitKind.STRING)
            {
                out.add((String) lit.value());
            }
            else if (call.kwargs().get("domain") instanceof Expr.Ref ref
                    && ref.kind() == net.cumba.corej.core.expr.OperandKind.COLUMN)
            {
                // D10 (runbook W2a): `domain=TS` is the bare dataset reference — read_value's
                // authored form; the same dataset the quoted spelling names.
                out.add(ref.name());
            }
            call.args().forEach(a -> collectDatasets(a, dotted, out));
            call.kwargs().values().forEach(a -> collectDatasets(a, dotted, out));
        }
        case Expr.Binary binary ->
        {
            collectDatasets(binary.left(), dotted, out);
            collectDatasets(binary.right(), dotted, out);
        }
        case Expr.And and -> and.parts().forEach(p -> collectDatasets(p, dotted, out));
        case Expr.Or or -> or.parts().forEach(p -> collectDatasets(p, dotted, out));
        case Expr.Not not -> collectDatasets(not.inner(), dotted, out);
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> items = (List<Expr>) lit.value();
                items.forEach(item -> collectDatasets(item, dotted, out));
            }
        }
        }
    }


    /** {@link #checkExpr(Rule)} for a bare condition node. */
    public static @Nullable Expr toExpr(@Nullable CheckCondition condition)
    {
        return switch (condition)
        {
        case null -> null;
        case CheckConditionExpression e -> e.expr();
        case CheckConditionAll all -> new Expr.And(
                all.getConditions().stream().map(CheckExprUnits::toExpr).toList());
        case CheckConditionAny any -> new Expr.Or(
                any.getConditions().stream().map(CheckExprUnits::toExpr).toList());
        case CheckConditionNot not -> new Expr.Not(toExpr(not.getCondition()));
        default -> throw new IllegalStateException("Check shape " + condition.getClass()
                + " has no expression form (phase 7 retired the operator-leaf model)");
        };
    }


    /**
     * Whether the retired strict lowering would have accepted the whole {@code Check} — the
     * quantity the instruments' corpus-blindness censuses counted as "presents an operator-leaf
     * Check".
     */
    public static boolean strictlyClassified(Expr root)
    {
        List<Unit> units = units(root);
        // ⚠ An empty unit list from a non-null Check is a broken walker, not a classified rule —
        // the walk is total, so every non-null expression yields at least one unit. Without this
        // clause a dead walker would count the whole corpus as classified and the blindness
        // censuses would pass vacuously (proved by negative control, 2026-09-16).
        return !units.isEmpty() && units.stream().allMatch(Unit::strict);
    }

    // ------------------------------------------------------------------
    // The walk
    // ------------------------------------------------------------------


    private static void walk(Expr node, boolean positive, boolean conjunctive, boolean topLevel,
            String path, List<Unit> out)
    {
        switch (node)
        {
        case Expr.And a ->
        {
            boolean still = conjunctive && positive;
            // Top-level-conjunct status survives nested `all`s in positive polarity only — `and`
            // is associative there; under a `not` the conjunction is really a disjunction.
            boolean childTop = topLevel && positive;
            for (Expr child : a.parts())
            {
                walk(child, positive, still, childTop, join(path, "all"), out);
            }
        }
        case Expr.Or o ->
        {
            boolean still = conjunctive && !positive;
            for (Expr child : o.parts())
            {
                walk(child, positive, still, false, join(path, "any"), out);
            }
        }
        case Expr.Not n ->
        {
            Unit folded = foldedNegation(n.inner(), positive, conjunctive, topLevel, path);
            if (folded != null)
            {
                out.add(folded);
                return;
            }
            walk(n.inner(), !positive, conjunctive, false, join(path, "not"), out);
        }
        default -> out.add(classify(node, positive, conjunctive, topLevel, path));
        }
    }


    /**
     * The strict lowering's {@code lowerNot} fold table: the shapes where {@code not <call>} was a
     * single negative operator-leaf rather than a structural negation. Everything else returns
     * {@code null} and the walk keeps the structural {@code not}.
     */
    private static @Nullable Unit foldedNegation(Expr inner, boolean positive, boolean conjunctive,
            boolean topLevel, String path)
    {
        if (!(inner instanceof Expr.Call c))
        {
            return null;
        }
        boolean negated = !positive;
        return switch (c.name())
        {
        case "empty" -> unary(c, "non_empty", negated, conjunctive, topLevel, path);
        case "is_integer" -> unary(c, "is_not_integer", negated, conjunctive, topLevel, path);
        case "is_complete_date_part" -> unary(c, "is_not_complete_date_part", negated, conjunctive,
                topLevel, path);
        case "contains" -> twoArg(c, "does_not_contain", negated, conjunctive, topLevel, path);
        case "equalsIgnoreCase" -> twoArg(c, "not_equal_to_case_insensitive", negated, conjunctive,
                topLevel, path);
        case "is_sorted_by" -> functionUnit(c, "target_is_not_sorted_by", negated, conjunctive,
                topLevel, path);
        case "present_on_multiple_rows_within" -> functionUnit(c,
                "not_present_on_multiple_rows_within", negated, conjunctive, topLevel, path);
        case "has_next_corresponding_record" -> functionUnit(c,
                "does_not_have_next_corresponding_record", negated, conjunctive, topLevel, path);
        case "prefix_matches" -> affixRegexUnit(c, true, negated, conjunctive, topLevel, path);
        case "suffix_matches" -> affixRegexUnit(c, true, negated, conjunctive, topLevel, path);
        default ->
        {
            String negative = POSITIVE_TO_NEGATIVE.get(c.name());
            yield negative == null ? null
                    : functionUnit(c, negative, negated, conjunctive, topLevel, path);
        }
        };
    }


    private static Unit classify(Expr node, boolean positive, boolean conjunctive, boolean topLevel,
            String path)
    {
        boolean negated = !positive;
        return switch (node)
        {
        case Expr.Binary b -> classifyBinary(b, negated, conjunctive, topLevel, path);
        case Expr.Call c -> classifyCall(c, negated, conjunctive, topLevel, path);
        default -> opaque(negated, conjunctive, topLevel, path, node);
        };
    }


    private static Unit classifyBinary(Expr.Binary b, boolean negated, boolean conjunctive,
            boolean topLevel, String path)
    {
        // Regex matches, including the anchored affix-regex form over prefix()/suffix().
        if (b.op() == Expr.BinOp.MATCH || b.op() == Expr.BinOp.NMATCH)
        {
            boolean neg = b.op() == Expr.BinOp.NMATCH;
            Expr.Call affix = affixCall(b.left());
            if (affix != null)
            {
                String kind = affix.name();
                return withName(affix.args().get(0), (neg ? "not_" : "") + kind + "_matches_regex",
                        b.right(), negated, conjunctive, topLevel, path, b);
            }
            return withName(b.left(), neg ? "not_matches_regex" : "matches_regex", b.right(),
                    negated, conjunctive, topLevel, path, b);
        }
        // Affix comparisons/membership: prefix(X, n) op V.
        Expr.Call affix = affixCall(b.left());
        if (affix != null)
        {
            String kind = affix.name();
            String op = switch (b.op())
            {
            case EQ -> kind + "_equal_to";
            case NEQ -> kind + "_not_equal_to";
            case IN -> kind + "_is_contained_by";
            case NOT_IN -> kind + "_is_not_contained_by";
            default -> null;
            };
            return op == null ? opaque(negated, conjunctive, topLevel, path, b)
                    : withName(affix.args().get(0), op, b.right(), negated, conjunctive, topLevel,
                            path, b);
        }
        // Type-insensitive comparison: str(X) ==/!= str(V).
        if (isWrapperCall(b.left(), "str") && isWrapperCall(b.right(), "str"))
        {
            String op = b.op() == Expr.BinOp.EQ ? "equal_to"
                    : b.op() == Expr.BinOp.NEQ ? "not_equal_to" : null;
            return op == null ? opaque(negated, conjunctive, topLevel, path, b)
                    : withName(((Expr.Call) b.left()).args().get(0), op,
                            ((Expr.Call) b.right()).args().get(0), negated, conjunctive, topLevel,
                            path, b);
        }
        // Membership.
        if (b.op() == Expr.BinOp.IN || b.op() == Expr.BinOp.NOT_IN)
        {
            boolean not = b.op() == Expr.BinOp.NOT_IN;
            if (isWrapperCall(b.left(), "upper"))
            {
                return withName(((Expr.Call) b.left()).args().get(0),
                        not ? "is_not_contained_by_case_insensitive"
                                : "is_contained_by_case_insensitive",
                        b.right(), negated, conjunctive, topLevel, path, b);
            }
            String op = not ? "is_not_contained_by" : "is_contained_by";
            if (b.left() instanceof Expr.Call c && "tuple".equals(c.name()) && c.kwargs().isEmpty())
            {
                List<String> members = new ArrayList<>();
                boolean strict = true;
                for (Expr member : c.args())
                {
                    Operand o = operand(member);
                    if (o.name() == null)
                    {
                        return opaque(negated, conjunctive, topLevel, path, b);
                    }
                    members.add(o.name());
                    strict &= o.strict();
                }
                return new Unit(op, null, List.copyOf(members), b.right(), negated, conjunctive,
                        topLevel, path, strict, b);
            }
            return withName(b.left(), op, b.right(), negated, conjunctive, topLevel, path, b);
        }
        // The three fused arithmetic shapes: X != A / B, X != A - B, X != ((A - B) / B) * 100.
        if (b.right() instanceof Expr.Binary rb && isArith(rb.op()))
        {
            String op = b.op() != Expr.BinOp.NEQ ? null : switch (rb.op())
            {
            case DIV -> "not_equal_to_divide";
            case SUB -> "not_equal_to_subtract";
            case MUL -> "not_equal_to_pctchg";
            default -> null;
            };
            return op == null ? opaque(negated, conjunctive, topLevel, path, b)
                    : withName(b.left(), op, b.right(), negated, conjunctive, topLevel, path, b);
        }
        // Wrapper-family comparisons: plain / date / date_part / time_part / len /
        // case-insensitive.
        String lw = wrapperOf(b.left());
        String rw = wrapperOf(b.right());
        if ("len".equals(lw) || "len".equals(rw))
        {
            String op = switch (b.op())
            {
            case GT -> "longer_than";
            case GE -> "longer_than_or_equal_to";
            case LT -> "shorter_than";
            case LE -> "shorter_than_or_equal_to";
            case EQ -> "has_equal_length";
            case NEQ -> "has_not_equal_length";
            default -> null;
            };
            Expr target = "len".equals(lw) ? ((Expr.Call) b.left()).args().get(0) : b.left();
            Expr value = "len".equals(lw) ? b.right() : ((Expr.Call) b.right()).args().get(0);
            return op == null ? opaque(negated, conjunctive, topLevel, path, b)
                    : withName(target, op, value, negated, conjunctive, topLevel, path, b);
        }
        if (isCaseWrapper(lw))
        {
            String op = b.op() == Expr.BinOp.EQ ? "equal_to_case_insensitive"
                    : b.op() == Expr.BinOp.NEQ ? "not_equal_to_case_insensitive" : null;
            return op == null ? opaque(negated, conjunctive, topLevel, path, b)
                    : withName(((Expr.Call) b.left()).args().get(0), op, unwrap(b.right()), negated,
                            conjunctive, topLevel, path, b);
        }
        // ⚠ The LEFT wrapper decides the family, exactly as the retired lowering's
        // `lw != null ? lw : rw` did: `date_part(date(X)) != date(Y)` is a date_part comparison,
        // not a date one — preferring the right side's tag misclassified exactly that corpus
        // family (AD0045) and cost the ADaM suite its fixtures.
        String deciding = lw != null ? lw : rw;
        String family = switch (deciding)
        {
        case "date", "date_part", "time_part" -> deciding;
        case null, default -> "plain";
        };
        String op = comparisonOperator(family, b.op());
        return op == null ? opaque(negated, conjunctive, topLevel, path, b)
                : withName(unwrap(b.left()), op, b.right(), negated, conjunctive, topLevel, path,
                        b);
    }


    private static @Nullable String comparisonOperator(String family, Expr.BinOp op)
    {
        String suffix = switch (op)
        {
        case EQ -> "equal_to";
        case NEQ -> "not_equal_to";
        case LT -> "less_than";
        case GT -> "greater_than";
        case LE -> "less_than_or_equal_to";
        case GE -> "greater_than_or_equal_to";
        default -> null;
        };
        if (suffix == null)
        {
            return null;
        }
        return switch (family)
        {
        case "plain" -> suffix;
        case "date" -> "date_" + suffix;
        case "date_part", "time_part" -> (op == Expr.BinOp.EQ || op == Expr.BinOp.NEQ)
                ? family + "_" + suffix
                : null;
        default -> null;
        };
    }


    private static Unit classifyCall(Expr.Call c, boolean negated, boolean conjunctive,
            boolean topLevel, String path)
    {
        String n = c.name();
        if (UNARY_PREDICATES.contains(n))
        {
            return unary(c, n, negated, conjunctive, topLevel, path);
        }
        if (FUNCTION_OPERATORS.contains(n))
        {
            return functionUnit(c, n, negated, conjunctive, topLevel, path);
        }
        // A bare positive group call was Not(<negative leaf>) in the v1 view: report the negative
        // operator, never as a top-level conjunct (the leaf sat below a structural not there).
        String negative = POSITIVE_TO_NEGATIVE.get(n);
        if (negative != null)
        {
            Unit unit = functionUnit(c, negative, !negated, conjunctive, false, join(path, "not"));
            return unit;
        }
        if (SUBSTRING_PREDICATES.contains(n))
        {
            return twoArg(c, n, negated, conjunctive, topLevel, path);
        }
        if ("equalsIgnoreCase".equals(n))
        {
            return twoArg(c, "equal_to_case_insensitive", negated, conjunctive, topLevel, path);
        }
        if ("prefix_matches".equals(n) || "suffix_matches".equals(n))
        {
            return affixRegexUnit(c, false, negated, conjunctive, topLevel, path);
        }
        if (REGEX_PREDICATES.contains(n) && c.args().size() == 1 && c.kwargs().isEmpty())
        {
            // Raise-side regex predicate: no strict surface; the conversion arm the audits used
            // reported it as the matches_regex it was raised from.
            Operand o = operand(c.args().get(0));
            return new Unit("matches_regex", o.name(), List.of(), null, negated, conjunctive,
                    topLevel, path, false, c);
        }
        return opaque(negated, conjunctive, topLevel, path, c);
    }


    private static Unit unary(Expr.Call c, String operator, boolean negated, boolean conjunctive,
            boolean topLevel, String path)
    {
        if (c.args().size() != 1)
        {
            return opaque(negated, conjunctive, topLevel, path, c);
        }
        Operand o = operand(c.args().get(0));
        return new Unit(operator, o.name(), List.of(), null, negated, conjunctive, topLevel, path,
                o.strict(), c);
    }


    private static Unit twoArg(Expr.Call c, String operator, boolean negated, boolean conjunctive,
            boolean topLevel, String path)
    {
        if (c.args().size() != 2)
        {
            return opaque(negated, conjunctive, topLevel, path, c);
        }
        return withName(c.args().get(0), operator, c.args().get(1), negated, conjunctive, topLevel,
                path, c);
    }


    private static Unit affixRegexUnit(Expr.Call c, boolean negatedOperator, boolean negated,
            boolean conjunctive, boolean topLevel, String path)
    {
        if (c.args().size() < 2)
        {
            return opaque(negated, conjunctive, topLevel, path, c);
        }
        String kind = c.name().startsWith("prefix") ? "prefix" : "suffix";
        String operator = (negatedOperator ? "not_" : "") + kind + "_matches_regex";
        return withName(c.args().get(0), operator, c.args().get(1), negated, conjunctive, topLevel,
                path, c);
    }


    /**
     * A function-operator unit: first argument (or, for the uniqueness pair, the list operand's
     * first member) is the {@code name}; a second positional argument or {@code keys=} kwarg is the
     * value.
     */
    private static Unit functionUnit(Expr.Call c, String operator, boolean negated,
            boolean conjunctive, boolean topLevel, String path)
    {
        if (c.args().isEmpty())
        {
            return opaque(negated, conjunctive, topLevel, path, c);
        }
        if (UNIQUE_SET_OPERATORS.contains(operator))
        {
            if (c.args().get(0) instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST
                    && lit.value() instanceof List<?> items && !items.isEmpty()
                    && items.get(0) instanceof Expr first)
            {
                Operand o = operand(first);
                // The v1 wire form split member 0 into `name` and the rest into `value` (D-2);
                // consumers that read the comparator members (the silencing-guard carve-out)
                // depend on the remainder surviving here.
                @SuppressWarnings("unchecked")
                List<Expr> members = (List<Expr>) items;
                Expr rest = members.size() > 1
                        ? new Expr.Lit(Expr.LitKind.LIST, members.subList(1, members.size()))
                        : null;
                return new Unit(operator, o.name(), List.of(), rest, negated, conjunctive, topLevel,
                        path, o.strict(), c);
            }
            return opaque(negated, conjunctive, topLevel, path, c);
        }
        Operand o = operand(c.args().get(0));
        Expr value = c.args().size() >= 2 ? c.args().get(1) : c.kwargs().get("keys");
        return new Unit(operator, o.name(), List.of(), value, negated, conjunctive, topLevel, path,
                o.strict(), c);
    }


    private static Unit withName(Expr target, String operator, @Nullable Expr value,
            boolean negated, boolean conjunctive, boolean topLevel, String path, Expr node)
    {
        Operand o = operand(target);
        if (o.name() == null)
        {
            return new Unit(operator, null, List.of(), value, negated, conjunctive, topLevel, path,
                    false, node);
        }
        return new Unit(operator, o.name(), List.of(), value, negated, conjunctive, topLevel, path,
                o.strict(), node);
    }


    private static Unit opaque(boolean negated, boolean conjunctive, boolean topLevel, String path,
            Expr node)
    {
        return new Unit(null, null, List.of(), null, negated, conjunctive, topLevel, path, false,
                node);
    }

    // ------------------------------------------------------------------
    // Operand naming — the classification half of the strict lowering's nameOf
    // ------------------------------------------------------------------

    private record Operand(@Nullable String name, boolean strict)
    {
    }

    private static Operand operand(Expr e)
    {
        return operand(e, true);
    }


    private static Operand operand(Expr e, boolean strict)
    {
        // A num(...) conversion had no strict surface; the audits read through it via the
        // conversion arm's stripNumConversions, so the name resolves but strictness is lost.
        if (isWrapperCall(e, "num"))
        {
            return operand(((Expr.Call) e).args().get(0), false);
        }
        // time(...) (phase 3b, D71b) is the same shape of conversion with no v1 surface: read
        // through it so the column is visible to unit consumers, with strictness lost — it was
        // omitted here when phase 3c authored it into the corpus (M4, terminal review of
        // PLAN-typed-expression-engine). Population-neutral today: no shipped rule wraps a
        // NAME-side operand in time(); the corpus's two sites (CDISC/PMDA-AD0044) carry it on
        // the value side, which does not pass through here.
        if (isWrapperCall(e, "time"))
        {
            return operand(((Expr.Call) e).args().get(0), false);
        }
        Expr inner = e instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 1
                && UNARY_WRAPPERS.contains(c.name()) ? c.args().get(0) : e;
        if (inner != e)
        {
            return operand(inner, strict);
        }
        if (e instanceof Expr.Call c && c.args().isEmpty() && c.kwargs().isEmpty())
        {
            if ("varname".equals(c.name()))
            {
                return new Operand("variable_name", strict);
            }
            if ("value".equals(c.name()))
            {
                return new Operand("variable_value", strict);
            }
        }
        String metadata = MetadataOperandMapping.reverseToOperand(e);
        if (metadata != null)
        {
            return new Operand(metadata, strict);
        }
        if (e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING)
        {
            // The exists family's quoted-name operand; harmless for other units, whose v1 value
            // side is not consulted through this path.
            return new Operand((String) lit.value(), strict);
        }
        if (e instanceof Expr.Ref r)
        {
            // The _matched_ join flag had no v1 surface (the strict lowering rejected it).
            boolean matchedFlag = r.kind() == net.cumba.corej.core.expr.OperandKind.MATCHED_FLAG;
            return new Operand(r.name(), strict && !matchedFlag);
        }
        return new Operand(null, false);
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------


    private static Expr.@Nullable Call affixCall(Expr e)
    {
        if (e instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 2
                && ("prefix".equals(c.name()) || "suffix".equals(c.name())))
        {
            return c;
        }
        return null;
    }


    private static boolean isWrapperCall(Expr e, String name)
    {
        return e instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 1
                && name.equals(c.name());
    }


    private static @Nullable String wrapperOf(Expr e)
    {
        if (e instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 1
                && UNARY_WRAPPERS.contains(c.name()))
        {
            return c.name();
        }
        return null;
    }


    private static Expr unwrap(Expr e)
    {
        return wrapperOf(e) != null ? ((Expr.Call) e).args().get(0) : e;
    }


    private static boolean isCaseWrapper(@Nullable String w)
    {
        return "lowcase".equals(w) || "upcase".equals(w);
    }


    private static boolean isArith(Expr.BinOp op)
    {
        return op == Expr.BinOp.ADD || op == Expr.BinOp.SUB || op == Expr.BinOp.MUL
                || op == Expr.BinOp.DIV;
    }


    private static String join(String path, String node)
    {
        return path.isEmpty() ? node : path + '/' + node;
    }
}
