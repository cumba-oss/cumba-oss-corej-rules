package net.cumba.corej.core.expr.convert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.MetadataOperandMapping;
import net.cumba.corej.core.expr.ast.Expr;
import org.jspecify.annotations.Nullable;

/**
 * Dual-form {@code Check} support for the raw-{@link JsonNode} corpus instruments
 * ({@code RuleEquivalenceInventory}, {@code RuleDocCensus}, {@code RuleFingerprint}): the Java
 * counterpart of {@code scripts/lib/check_expr.py}'s {@code dual_form_check}. A legacy Check node
 * passes through unchanged; an {@code {expression}} Check is parsed with the shared parser and
 * lowered back to the legacy {@code all}/{@code any}/{@code not}/leaf JSON shape those instruments'
 * operator tables key on.
 *
 * <p>
 * ⭐ <b>The lowering is self-contained since D121</b> (phase 7 of
 * {@code PLAN-typed-expression-engine.md}): the engine's {@code ExprLowering} and the v1
 * {@code CheckConditionLeaf} model are retired, so the conversion-mode table lives HERE, as a
 * direct {@code Expr → JsonNode} emitter — a faithful port of the retired
 * {@code ExprLowering.toCheckConditionForConversion} (the legacy-form restoration pre-pass
 * included) plus the two writer-image inverses this class always added (mirroring
 * {@code check_expr.py}): {@code not <exists-family>(X)} is reconstructed as the flat negative
 * leaf, and {@code tuple(c1, …) [not] in <ref>} as the T3 composite {@code names:} membership leaf.
 * The instruments' canonical basis — and the committed {@code pairs-index.tsv} it feeds — is
 * therefore unchanged: their own committed-artifact tests are the byte-parity oracle for this port.
 * A construct with no legacy surface even then (the conversion-unlowerable rules, e.g.
 * {@code char(value()) <= 32}) yields {@code null} — callers keep the expression node and report,
 * or fall back to {@link #carriesPresenceCall} to prove the skip loses nothing they classify.
 * </p>
 */
public final class DualFormCheckNode
{

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final Map<String, String> NEGATIVE_BY_POSITIVE_EXISTS = Map.of("var_exists",
            "var_not_exists", "ds_exists", "ds_not_exists");

    private DualFormCheckNode()
    {
    }


    /** Whether {@code check} is the expression form ({@code expression} key, no composites). */
    public static boolean isExpressionCheck(JsonNode check)
    {
        return check != null && check.isObject() && check.has("expression") && !check.has("all")
                && !check.has("any") && !check.has("not");
    }


    /**
     * The legacy Check node for either authored form: a legacy node is returned unchanged (the same
     * object); an expression node is lowered, or {@code null} if it has no legacy surface (the
     * conversion-unlowerable rules — the caller keeps the expression node and reports).
     *
     * <p>
     * ⭐ <b>A level-keyed Check (Plan C &#167;3.3) is lowered LEVEL BY LEVEL</b>, onto a copy, and
     * the copy is returned. Without this arm a level map fell straight through the
     * {@link #isExpressionCheck} test — it has no {@code expression} key — and every caller got the
     * authored level map back, whose values are {@code {expression: …}} objects carrying no
     * {@code operator} at all. That is why {@code RuleDocCensus}, the only caller, counted <b>zero
     * Check operators</b> for the 9 level-keyed rules while counting them for every single-level
     * rule: a fabricated zero, not a measurement. Only {@link #lowerCheckInPlace} had the arm.
     * </p>
     *
     * <p>
     * ⚑ A level with no legacy surface is left as authored rather than collapsing the whole map to
     * {@code null} — the same partial treatment {@link #lowerCheckInPlace} gives, and strictly more
     * informative than {@code null}, whose callers fall back to the wholly-unlowered node anyway.
     * Use {@link #lowerCheckInPlace}'s boolean when "did every level lower?" is the question.
     * </p>
     *
     * @param check
     *            the authored Check node (either form, or a level map)
     * @return the legacy-shaped node, or {@code null} for an unlowerable expression
     */
    public static JsonNode lowerOrNull(JsonNode check)
    {
        java.util.SequencedMap<String, JsonNode> levels = RuleFingerprint.levels(check);
        if (levels != null)
        {
            ObjectNode copy = ((ObjectNode) check).deepCopy();
            lowerLevelsInPlace(copy, levels.keySet());
            return copy;
        }
        if (!isExpressionCheck(check))
        {
            return check;
        }
        try
        {
            Expr parsed = CheckExpressionParser.parse(check.path("expression").asText());
            return lowerNode(restore(parsed));
        }
        catch (ExpressionException _)
        {
            return null;
        }
    }


    /**
     * The expression with every {@code num(...)} conversion erased: {@code num(X)} is a numeric
     * READ of column {@code X} ({@code PLAN-column-type-conformance} R3/R10), and the legacy
     * operator-leaf vocabulary these instruments key on has no conversion spelling — the
     * pre-migration corpus authored the very same comparison bare. For a scan that classifies
     * presence guards, guard positions or column reads the conversion is therefore transparent, and
     * stripping it restores exactly the operator-leaf surface the corpus carried before the Phase 6
     * migration authored {@code num()} in.
     *
     * <p>
     * ⚠ Deliberately NOT wired into {@link #lowerOrNull} itself: the equivalence and census
     * instruments mirror {@code scripts/lib/check_expr.py}'s {@code dual_form_check}, and a
     * Java-only strip inside the shared lowering would make the Java and Python fingerprint halves
     * bucket differently — the exact defect {@code RuleEquivalenceDriftTest}'s diagnosis note warns
     * regeneration would hide.
     * </p>
     *
     * @param e
     *            the raised expression
     * @return the expression with {@code num(X)} rewritten to {@code X}, recursively
     */
    public static Expr stripNumConversions(Expr e)
    {
        return switch (e)
        {
        case Expr.Call c when "num".equals(c.name()) && c.kwargs().isEmpty()
                && c.args().size() == 1 -> stripNumConversions(c.args().get(0));
        case Expr.Call c -> new Expr.Call(c.name(),
                c.args().stream().map(DualFormCheckNode::stripNumConversions).toList(),
                mapKwargs(c.kwargs(), DualFormCheckNode::stripNumConversions));
        case Expr.And a -> new Expr.And(
                a.parts().stream().map(DualFormCheckNode::stripNumConversions).toList());
        case Expr.Or o -> new Expr.Or(
                o.parts().stream().map(DualFormCheckNode::stripNumConversions).toList());
        case Expr.Not n -> new Expr.Not(stripNumConversions(n.inner()));
        case Expr.Binary b -> new Expr.Binary(b.op(), stripNumConversions(b.left()),
                stripNumConversions(b.right()));
        case Expr.Lit(Expr.LitKind kind, Object value) when kind == Expr.LitKind.LIST ->
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) value;
            yield new Expr.Lit(Expr.LitKind.LIST,
                    items.stream().map(DualFormCheckNode::stripNumConversions).toList());
        }
        default -> e;
        };
    }


    /** Whether any {@code exists}-family call appears anywhere in the expression. */
    public static boolean carriesPresenceCall(Expr e)
    {
        return switch (e)
        {
        case Expr.Call c -> NEGATIVE_BY_POSITIVE_EXISTS.containsKey(c.name())
                || NEGATIVE_BY_POSITIVE_EXISTS.containsValue(c.name())
                || c.args().stream().anyMatch(DualFormCheckNode::carriesPresenceCall)
                || c.kwargs().values().stream().anyMatch(DualFormCheckNode::carriesPresenceCall);
        case Expr.And a -> a.parts().stream().anyMatch(DualFormCheckNode::carriesPresenceCall);
        case Expr.Or o -> o.parts().stream().anyMatch(DualFormCheckNode::carriesPresenceCall);
        case Expr.Not n -> carriesPresenceCall(n.inner());
        case Expr.Binary b -> carriesPresenceCall(b.left()) || carriesPresenceCall(b.right());
        // LIST literals carry Expr items — no presence call can reach one today, but the walk
        // must miss no call position (same defensive arm the wave-41 review added to
        // RuleSrcExpressionWriter.carriesGenericPresenceCall).
        case Expr.Lit(Expr.LitKind kind, Object value) when kind == Expr.LitKind.LIST ->
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) value;
            yield items.stream().anyMatch(DualFormCheckNode::carriesPresenceCall);
        }
        default -> false;
        };
    }


    /**
     * Collects every {@link Expr.Ref} name in the expression — the conservative native-arm view of
     * "variables this Check reads" for an instrument that cannot lower it (may over-approximate:
     * operation {@code $}-refs and builtins are included).
     *
     * @param e
     *            the raised expression
     * @param out
     *            receives the names
     */
    public static void collectRefNames(Expr e, Set<String> out)
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
        case Expr.Lit(Expr.LitKind kind, Object value) when kind == Expr.LitKind.LIST ->
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) value;
            items.forEach(i -> collectRefNames(i, out));
        }
        default ->
        {
            // scalar literals carry no reference
        }
        }
    }


    /**
     * Replaces an expression {@code Check} on {@code rule} with its lowered legacy node, in place.
     * An unlowerable expression Check is left as authored.
     *
     * <p>
     * ⭐ <b>Level-keyed Checks (Plan C §3.3) are lowered level by level.</b> A level map is not
     * itself an expression Check — it has no {@code expression} key — so without this arm it would
     * pass through untouched and every level's condition would reach the leaf-keyed instruments in
     * expression form while every single-level rule reached them lowered. The two corpora would
     * then be compared on different bases and nothing would say so.
     * </p>
     *
     * <p>
     * ⚑ A level's optional {@code Message} is lifted off before the lowering and put back on the
     * lowered node, because it is not part of the condition grammar and {@link #lowerOrNull} would
     * otherwise have to carry an unbound property through the parser.
     * </p>
     *
     * @param rule
     *            the full rule node
     * @return {@code true} if the Check was lowered (or needed no lowering), {@code false} if any
     *         part of it was left as authored
     */
    public static boolean lowerCheckInPlace(JsonNode rule)
    {
        JsonNode check = rule.path("Check");
        java.util.SequencedMap<String, JsonNode> levels = RuleFingerprint.levels(check);
        if (levels != null)
        {
            return lowerLevelsInPlace((ObjectNode) check, levels.keySet());
        }
        if (!isExpressionCheck(check))
        {
            return true;
        }
        if (!(rule instanceof ObjectNode object))
        {
            // An expression Check on a non-object rule node cannot be replaced in place;
            // report it like an unlowerable rather than claiming the lowering happened.
            return false;
        }
        JsonNode lowered = lowerOrNull(check);
        if (lowered == null)
        {
            return false;
        }
        object.set("Check", lowered);
        return true;
    }


    /**
     * {@link #lowerCheckInPlace}'s level-map arm: every level lowered, its {@code Message} kept.
     */
    private static boolean lowerLevelsInPlace(ObjectNode check, Set<String> levels)
    {
        boolean allLowered = true;
        for (String level : levels)
        {
            JsonNode value = check.get(level);
            if (!value.isObject())
            {
                allLowered = false;
                continue;
            }
            JsonNode message = value.get(RuleFingerprint.MESSAGE_KEY);
            ObjectNode condition = ((ObjectNode) value).deepCopy();
            condition.remove(RuleFingerprint.MESSAGE_KEY);
            if (!isExpressionCheck(condition))
            {
                continue;
            }
            JsonNode lowered = lowerOrNull(condition);
            if (lowered == null)
            {
                allLowered = false;
                continue;
            }
            if (message != null && lowered instanceof ObjectNode loweredObject)
            {
                loweredObject.set(RuleFingerprint.MESSAGE_KEY, message);
            }
            check.set(level, lowered);
        }
        return allLowered;
    }

    // ======================================================================
    // The conversion-mode lowering, ported from the retired ExprLowering.
    // ======================================================================

    /** Raise-side regex predicates and the pattern each was raised from. */
    private static final Map<String, String> PREDICATE_TO_REGEX = Map.of("is_numeric",
            "^-?(0|[1-9]\\d*)(\\.\\d+)?$", "has_alpha", ".*[a-zA-Z].*", "has_digit", ".*[0-9].*",
            "is_valid_name", "^[A-Z_][A-Z0-9_]{0,7}$", "is_valid_testcd",
            "^[a-zA-Z_][a-zA-Z0-9_]{0,7}$");

    /** The first-letter range test the raise emitted {@code ^[A-Z]} for (negated sense). */
    private static final String FIRST_LETTER_REGEX = "^[A-Z]";

    /** Left-operand wrappers the v1 leaf held as an operator family rather than as a call. */
    private static final Set<String> UNARY_WRAPPERS = Set.of("date", "len", "date_part",
            "time_part", "lowcase", "upcase");

    /**
     * The v1 wrapper roster, exposed for {@code UnaryWrapperRosterDriftTest} (review round 2,
     * R2-14). The Python mirror carries this set PLUS {@code num}, which the Java side restores in
     * {@link #stripNumConversions} instead.
     *
     * @return the wrapper names
     */
    public static Set<String> unaryWrappers()
    {
        return UNARY_WRAPPERS;
    }


    /**
     * Every {@code ExprCompiler.comparisonMarkers()} member, each accounted for by an explicit
     * decision in this emitter (M4, terminal review of {@code PLAN-typed-expression-engine}).
     * {@code DualFormMarkerRosterDriftTest} asserts this set EQUALS the compiler's, so a new marker
     * reds a test here instead of silently becoming unlowerable with no record of the decision:
     * <ul>
     * <li>{@code date}/{@code date_part}/{@code time_part} — v1 operator families,
     * {@link #UNARY_WRAPPERS};</li>
     * <li>{@code num} — restored by the legacy-form pre-pass ({@link #stripNumConversions} /
     * {@code check_expr.py}'s {@code num} membership in {@code _UNARY_WRAPPERS});</li>
     * <li>{@code time} — deliberately WITHOUT a legacy surface: the v1 vocabulary has no time
     * operator family, so an expression carrying it (e.g. {@code CDISC-AD0044}'s {@code time(*TM)}
     * value side) yields {@code null} — unlowerable, visibly, exactly as
     * {@code scripts/lib/check_expr.py} answers; adding it here without the Python mirror would
     * fork the committed pairs-index basis.</li>
     * <li>{@code str} — ⚑ no longer a compiler marker (PLAN-dynamic-column-functions §2.1, owner
     * Q10, 2026-09-30: a registered conversion), so it left this set with the compiler's roster;
     * its TEXT shape {@code str(X) ==/!= str(V)} is still handled below exactly as before (still
     * without a legacy surface), so the pairs-index basis does not move.</li>
     * </ul>
     */
    static Set<String> acknowledgedComparisonMarkers()
    {
        return Set.of("date", "date_part", "time_part", "num", "time");
    }

    private static final Set<String> FUNCTION_OPERATORS = Set.of("has_multiple_values_for",
            "is_not_unique_relationship", "is_not_unique_set", "is_unique_set",
            "is_inconsistent_across_dataset", "shares_no_elements_with", "not_contains_all",
            "has_same_values", "is_not_ordered_subset_of", "inconsistent_enumerated_columns",
            "has_not_equal_length", "has_equal_length", "is_not_unique_value",
            "present_on_multiple_rows_within", "not_present_on_multiple_rows_within",
            "empty_within_except_last_row", "does_not_have_next_corresponding_record",
            "does_not_equal_string_part");

    private static final Set<String> UNIQUE_SET_OPERATORS = Set.of("is_unique_set",
            "is_not_unique_set");

    private static final Map<String, String> POSITIVE_TO_NEGATIVE = Map.of("is_unique_relationship",
            "is_not_unique_relationship", "contains_all", "not_contains_all",
            "shares_elements_with", "shares_no_elements_with", "is_ordered_subset_of",
            "is_not_ordered_subset_of", "is_unique_value", "is_not_unique_value", "is_unique_set",
            "is_not_unique_set");

    private static final Set<String> UNARY_PREDICATES = Set.of("ds_exists", "ds_not_exists",
            "var_exists", "var_not_exists", "var_is_null", "empty", "non_empty", "is_complete_date",
            "is_incomplete_date", "invalid_date", "invalid_duration", "is_integer",
            "is_not_integer", "is_complete_date_part", "is_not_complete_date_part");

    private static final Set<String> EXISTS_PREDICATES = Set.of("ds_exists", "ds_not_exists",
            "var_exists", "var_not_exists");

    private static final Set<String> SUBSTRING_PREDICATES = Set.of("contains", "does_not_contain",
            "starts_with", "ends_with");

    /** The case folds a rule wraps around a column NAME it tests as a string (CIT &#167;3). */
    private static final Set<String> CASE_FOLDS = Set.of("upper", "lower");

    /**
     * The operands a column-NAME source lowers to: {@code varname()} and the three levels of
     * {@code var_name(...)}.
     */
    private static final Set<String> NAME_OPERANDS = Set.of("variable_name", "define_variable_name",
            "library_variable_name");

    /**
     * The legacy-form restoration pre-pass (the retired {@code ExprLowering.restoreLegacyForms}):
     * raise-side-only constructs are rewritten back to shapes the operator-leaf vocabulary can
     * express — the regex predicates ({@code is_numeric(X)} et al. and the first-letter
     * {@code between(char(X), char("A"), char("Z"))}) back to their {@code =~}/{@code !~} form, and
     * a per-record accessor call only the wide accessor mapping can name back to its bare metadata
     * operand reference.
     */
    private static Expr restore(Expr e)
    {
        return switch (e)
        {
        case Expr.Not n ->
        {
            Expr restored = regexForm(n.inner(), true);
            yield restored != null ? restored : new Expr.Not(restore(n.inner()));
        }
        case Expr.Call c ->
        {
            Expr restored = regexForm(c, false);
            if (restored != null)
            {
                yield restored;
            }
            String operand = demotedOperand(c);
            yield operand != null
                    ? new Expr.Ref(operand, net.cumba.corej.core.expr.OperandKind.BUILTIN)
                    : new Expr.Call(c.name(),
                            c.args().stream().map(DualFormCheckNode::restore).toList(),
                            mapKwargs(c.kwargs(), DualFormCheckNode::restore));
        }
        case Expr.Binary b -> new Expr.Binary(b.op(), restore(b.left()), restore(b.right()));
        case Expr.And a -> new Expr.And(
                a.parts().stream().map(DualFormCheckNode::restore).toList());
        case Expr.Or o -> new Expr.Or(o.parts().stream().map(DualFormCheckNode::restore).toList());
        case Expr.Lit(Expr.LitKind kind, Object value) when kind == Expr.LitKind.LIST ->
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) value;
            yield new Expr.Lit(Expr.LitKind.LIST,
                    items.stream().map(DualFormCheckNode::restore).toList());
        }
        default -> e;
        };
    }


    private static @Nullable Expr regexForm(Expr e, boolean negated)
    {
        if (!(e instanceof Expr.Call c) || !c.kwargs().isEmpty())
        {
            return null;
        }
        Expr.BinOp op = negated ? Expr.BinOp.NMATCH : Expr.BinOp.MATCH;
        String pattern = PREDICATE_TO_REGEX.get(c.name());
        if (pattern != null && c.args().size() == 1)
        {
            return new Expr.Binary(op, restore(c.args().get(0)),
                    new Expr.Lit(Expr.LitKind.REGEX, pattern));
        }
        Expr firstLetterOperand = firstLetterRangeOperand(c);
        if (firstLetterOperand != null)
        {
            return new Expr.Binary(op, restore(firstLetterOperand),
                    new Expr.Lit(Expr.LitKind.REGEX, FIRST_LETTER_REGEX));
        }
        return null;
    }


    private static @Nullable Expr firstLetterRangeOperand(Expr.Call c)
    {
        if (!"between".equals(c.name()) || c.args().size() != 3)
        {
            return null;
        }
        Expr subject = charArgument(c.args().get(0));
        if (subject == null || !isCharLiteral(c.args().get(1), "A")
                || !isCharLiteral(c.args().get(2), "Z"))
        {
            return null;
        }
        return subject;
    }


    private static @Nullable Expr charArgument(Expr e)
    {
        return e instanceof Expr.Call c && "char".equals(c.name()) && c.args().size() == 1
                && c.kwargs().isEmpty() ? c.args().get(0) : null;
    }


    private static boolean isCharLiteral(Expr e, String letter)
    {
        Expr arg = charArgument(e);
        return arg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING
                && letter.equals(lit.value());
    }


    private static @Nullable String demotedOperand(Expr.Call c)
    {
        if (MetadataOperandMapping.reverseToOperand(c) != null)
        {
            return null;
        }
        return MetadataOperandMapping.reverseAnyAccessor(c);
    }


    private static Map<String, Expr> mapKwargs(Map<String, Expr> kwargs,
            java.util.function.UnaryOperator<Expr> f)
    {
        if (kwargs.isEmpty())
        {
            return kwargs;
        }
        java.util.LinkedHashMap<String, Expr> out = new java.util.LinkedHashMap<>();
        kwargs.forEach((k, v) -> out.put(k, f.apply(v)));
        return out;
    }

    // ---- boolean lowering ------------------------------------------------


    private static JsonNode lowerNode(Expr e)
    {
        return switch (e)
        {
        case Expr.And a ->
        {
            ArrayNode parts = NODES.arrayNode(a.parts().size());
            a.parts().forEach(p -> parts.add(lowerNode(p)));
            yield NODES.objectNode().set("all", parts);
        }
        case Expr.Or o ->
        {
            ArrayNode parts = NODES.arrayNode(o.parts().size());
            o.parts().forEach(p -> parts.add(lowerNode(p)));
            yield NODES.objectNode().set("any", parts);
        }
        case Expr.Not n -> lowerNot(n.inner());
        case Expr.Binary b -> lowerBinary(b);
        case Expr.Call c -> lowerPredicate(c);
        case Expr.Ref r -> throw unsupported(
                "bare reference '" + r.name() + "' is not a boolean condition");
        case Expr.Lit _ -> throw unsupported("a literal is not a boolean condition");
        };
    }


    private static JsonNode lowerNot(Expr inner)
    {
        if (inner instanceof Expr.Call c)
        {
            // Writer-image inverse (check_expr.py's extra): not <exists-family>(X) is the flat
            // negative presence leaf, never a structural {not: …} the pre-flip corpus never
            // authored.
            if (c.kwargs().isEmpty() && c.args().size() == 1
                    && NEGATIVE_BY_POSITIVE_EXISTS.containsKey(c.name()))
            {
                return lowerPredicate(new Expr.Call(NEGATIVE_BY_POSITIVE_EXISTS.get(c.name()),
                        c.args(), Map.of()));
            }
            if ("prefix_matches".equals(c.name()) || "suffix_matches".equals(c.name()))
            {
                return affixRegexLeaf(c, true);
            }
            if ("equalsIgnoreCase".equals(c.name()))
            {
                return twoArg(c, "not_equal_to_case_insensitive");
            }
            if ("is_sorted_by".equals(c.name()))
            {
                return sortedByLeaf(c);
            }
            if ("present_on_multiple_rows_within".equals(c.name()))
            {
                return functionOperatorLeaf(c, "not_present_on_multiple_rows_within");
            }
            if ("has_next_corresponding_record".equals(c.name()))
            {
                return functionOperatorLeaf(c, "does_not_have_next_corresponding_record");
            }
            String negativeOperator = POSITIVE_TO_NEGATIVE.get(c.name());
            if (negativeOperator != null)
            {
                return functionOperatorLeaf(c, negativeOperator);
            }
            if ("empty".equals(c.name()))
            {
                return unaryNegationLeaf(c, "non_empty");
            }
            if ("is_integer".equals(c.name()))
            {
                return unaryNegationLeaf(c, "is_not_integer");
            }
            if ("is_complete_date_part".equals(c.name()))
            {
                return unaryNegationLeaf(c, "is_not_complete_date_part");
            }
            if ("contains".equals(c.name()))
            {
                // The case-insensitive-contains fold first (check_expr.py's
                // _case_insensitive_contains): `not contains(upper(X), upper("v"))` was raised
                // from a does_not_contain_case_insensitive leaf.
                JsonNode folded = caseInsensitiveContainsLeaf(c);
                if (folded != null)
                {
                    return folded;
                }
                requireArgs(c.name(), c.args().size(), 2);
                return leaf(nameOf(caseFoldedNameSubject(c.args().get(0))), "does_not_contain",
                        substringLiteral(c.args().get(1)));
            }
        }
        return NODES.objectNode().set("not", lowerNode(inner));
    }


    private static JsonNode unaryNegationLeaf(Expr.Call c, String negativeOperator)
    {
        requireArgs(c.name(), c.args().size(), 1);
        ObjectNode out = NODES.objectNode();
        out.put("name", nameOf(c.args().get(0)));
        out.put("operator", negativeOperator);
        return out;
    }


    /**
     * {@code count(find_vars("<entry>")) == 0} (and {@code != 0} / {@code > 0}) as the legacy
     * presence leaf over the entry — the Java mirror of {@code check_expr.py}'s
     * {@code _find_vars_presence_leaf} ({@code PLAN-dynamic-column-functions} §2.8, owner Q13:
     * {@code CDISC-AD0581} asks "is no {@code TRTxxP} present?" with {@code find_vars} instead of a
     * {@code Requirements.Variables.None} entry). A lint VIEW of the name read and its polarity, so
     * the leaf-derived lints stay sighted on the rule; anything else — a qualified or a
     * {@code /regex/} entry included ({@link #unqualifiedName}) — stays unlowerable.
     *
     * @return the leaf, or {@code null} when {@code b} is not that shape
     */
    private static @Nullable JsonNode findVarsPresenceLeaf(Expr.Binary b)
    {
        if (!(b.left() instanceof Expr.Call count) || !"count".equals(count.name())
                || count.args().size() != 1 || !count.kwargs().isEmpty()
                || !(count.args().get(0) instanceof Expr.Call fv) || !"find_vars".equals(fv.name())
                || fv.args().size() != 1 || !fv.kwargs().isEmpty()
                || !(fv.args().get(0) instanceof Expr.Lit entry)
                || entry.kind() != Expr.LitKind.STRING || !unqualifiedName(entry)
                || !(b.right() instanceof Expr.Lit zero) || zero.kind() != Expr.LitKind.NUMBER
                || Double.compare(((Number) zero.value()).doubleValue(), 0.0d) != 0)
        {
            return null;
        }
        String op = switch (b.op())
        {
        case EQ -> "var_not_exists";
        case NEQ, GT -> "var_exists";
        default -> null;
        };
        if (op == null)
        {
            return null;
        }
        ObjectNode out = NODES.objectNode();
        out.put("name", String.valueOf(entry.value()));
        out.put("operator", op);
        return out;
    }


    /**
     * Whether a {@code find_vars} entry is an UNQUALIFIED, non-regex name or template — the only
     * entries a legacy presence leaf could spell (review round 1, lane B L3): a qualified
     * {@code DS.X} entry or a {@code /regex/} has no {@code var_exists} leaf equivalent, so it
     * stays unlowerable, loudly, like every other unmodelled shape. Mirrors {@code check_expr.py}'s
     * {@code _find_vars_presence_leaf}.
     */
    private static boolean unqualifiedName(Expr.Lit entry)
    {
        String text = String.valueOf(entry.value());
        return !text.isEmpty() && text.indexOf('.') < 0 && text.charAt(0) != '/';
    }


    private static JsonNode lowerBinary(Expr.Binary b)
    {
        JsonNode presence = findVarsPresenceLeaf(b);
        if (presence != null)
        {
            return presence;
        }
        if (b.op() == Expr.BinOp.MATCH || b.op() == Expr.BinOp.NMATCH)
        {
            Expr.Call affixOperand = affixCall(b.left());
            if (affixOperand != null)
            {
                return affixRegexMatchLeaf(b, affixOperand);
            }
            return regexLeaf(b);
        }
        Expr.Call affix = affixCall(b.left());
        if (affix != null)
        {
            return affixCompareLeaf(b, affix);
        }
        if (isWrapper(b.left(), "str") && isWrapper(b.right(), "str"))
        {
            return typeInsensitiveLeaf(b);
        }
        if (b.op() == Expr.BinOp.IN || b.op() == Expr.BinOp.NOT_IN)
        {
            // Writer-image inverse (check_expr.py's extra): the T3 composite `names:` leaf.
            if (isTupleCall(b.left()))
            {
                return tupleMembershipLeaf(b);
            }
            if (isWrapper(b.left(), "upper"))
            {
                return caseInsensitiveMembershipLeaf(b);
            }
            String op = b.op() == Expr.BinOp.IN ? "is_contained_by" : "is_not_contained_by";
            return leaf(nameOf(b.left()), op, b.right());
        }
        if (b.right() instanceof Expr.Binary rb && isArith(rb.op()))
        {
            return arithmeticLeaf(b, rb);
        }

        String lw = wrapperOf(b.left());
        String rw = wrapperOf(b.right());
        Expr li = unwrap(b.left());
        Expr ri = unwrap(b.right());

        if ("len".equals(lw) || "len".equals(rw))
        {
            return lengthLeaf(b, lw, li, ri, rw);
        }
        if (isCaseWrapper(lw) || isCaseWrapper(rw))
        {
            if (!isCaseWrapper(lw))
            {
                throw unsupported("a case-insensitive comparison needs lowcase/upcase on the left "
                        + "(column) operand");
            }
            boolean rightOk = isCaseWrapper(rw) || b.right() instanceof Expr.Lit;
            if (!rightOk)
            {
                throw unsupported("one-sided lowcase/upcase against a bare reference is ambiguous; "
                        + "wrap both sides or use equalsIgnoreCase(...)");
            }
            String operator = switch (b.op())
            {
            case EQ -> "equal_to_case_insensitive";
            case NEQ -> "not_equal_to_case_insensitive";
            default -> throw unsupported("lowcase/upcase comparisons support only == and !=");
            };
            return leaf(nameOf(li), operator, ri);
        }
        String family = comparisonFamily(lw, rw);
        return leaf(nameOf(li), comparisonOperator(family, b.op()), ri);
    }


    private static String comparisonFamily(@Nullable String lw, @Nullable String rw)
    {
        String w = lw != null ? lw : rw;
        if ("date".equals(w) || "date_part".equals(w) || "time_part".equals(w))
        {
            return w;
        }
        return "plain";
    }


    private static String comparisonOperator(String family, Expr.BinOp op)
    {
        return switch (family)
        {
        case "date" -> switch (op)
        {
        case EQ -> "date_equal_to";
        case NEQ -> "date_not_equal_to";
        case LT -> "date_less_than";
        case GT -> "date_greater_than";
        case LE -> "date_less_than_or_equal_to";
        case GE -> "date_greater_than_or_equal_to";
        default -> throw unsupported("unsupported date comparison");
        };
        case "date_part" -> switch (op)
        {
        case EQ -> "date_part_equal_to";
        case NEQ -> "date_part_not_equal_to";
        default -> throw unsupported("date_part supports only == and !=");
        };
        case "time_part" -> switch (op)
        {
        case EQ -> "time_part_equal_to";
        case NEQ -> "time_part_not_equal_to";
        default -> throw unsupported("time_part supports only == and !=");
        };
        default -> switch (op)
        {
        case EQ -> "equal_to";
        case NEQ -> "not_equal_to";
        case LT -> "less_than";
        case GT -> "greater_than";
        case LE -> "less_than_or_equal_to";
        case GE -> "greater_than_or_equal_to";
        default -> throw unsupported("unsupported comparison");
        };
        };
    }


    private static JsonNode regexLeaf(Expr.Binary b)
    {
        if (!(b.right() instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.REGEX)
        {
            throw unsupported("the right-hand side of =~ / !~ must be a /regex/ literal");
        }
        String op = b.op() == Expr.BinOp.MATCH ? "matches_regex" : "not_matches_regex";
        ObjectNode out = NODES.objectNode();
        out.put("name", nameOf(caseFoldedNameSubject(b.left())));
        out.put("operator", op);
        out.set("value", NODES.textNode((String) lit.value()));
        out.put("value_is_literal", true);
        return out;
    }


    private static JsonNode lengthLeaf(Expr.Binary b, @Nullable String lw, Expr li, Expr ri,
            @Nullable String rw)
    {
        if (!"len".equals(lw) || "len".equals(rw))
        {
            throw unsupported("len(...) must be the left operand of a length comparison");
        }
        String op = switch (b.op())
        {
        case GT -> "longer_than";
        case GE -> "longer_than_or_equal_to";
        case LT -> "shorter_than";
        case LE -> "shorter_than_or_equal_to";
        case EQ -> "has_equal_length";
        case NEQ -> "has_not_equal_length";
        default -> throw unsupported("unsupported length comparison");
        };
        ObjectNode out = NODES.objectNode();
        out.put("name", nameOf(li));
        out.put("operator", op);
        out.set("value", literalNode(ri));
        out.put("value_is_literal", true);
        return out;
    }


    private static JsonNode arithmeticLeaf(Expr.Binary b, Expr.Binary rhs)
    {
        if (b.op() != Expr.BinOp.NEQ)
        {
            throw unsupported("arithmetic comparison supports only != (not_equal_to_*)");
        }
        String name = nameOf(b.left());
        if (rhs.op() == Expr.BinOp.DIV && rhs.left() instanceof Expr.Ref a
                && rhs.right() instanceof Expr.Ref bb)
        {
            return arithLeaf(name, "not_equal_to_divide", a.name(), bb.name());
        }
        if (rhs.op() == Expr.BinOp.SUB && rhs.left() instanceof Expr.Ref a
                && rhs.right() instanceof Expr.Ref bb)
        {
            return arithLeaf(name, "not_equal_to_subtract", a.name(), bb.name());
        }
        if (rhs.op() == Expr.BinOp.MUL && isHundred(rhs.right())
                && rhs.left() instanceof Expr.Binary div && div.op() == Expr.BinOp.DIV
                && div.left() instanceof Expr.Binary sub && sub.op() == Expr.BinOp.SUB
                && sub.left() instanceof Expr.Ref a && sub.right() instanceof Expr.Ref b1
                && div.right() instanceof Expr.Ref b2 && b1.name().equals(b2.name()))
        {
            return arithLeaf(name, "not_equal_to_pctchg", a.name(), b1.name());
        }
        throw unsupported("unrecognised arithmetic shape (native-only, no legacy operator)");
    }


    private static JsonNode arithLeaf(String name, String operator, String a, String b)
    {
        ArrayNode arr = NODES.arrayNode(2);
        arr.add(NODES.textNode(a));
        arr.add(NODES.textNode(b));
        ObjectNode out = NODES.objectNode();
        out.put("name", name);
        out.put("operator", operator);
        out.set("value", arr);
        return out;
    }


    private static boolean isHundred(Expr e)
    {
        return e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.NUMBER
                && Double.compare((Double) lit.value(), 100.0) == 0;
    }


    private static JsonNode typeInsensitiveLeaf(Expr.Binary b)
    {
        String operator = switch (b.op())
        {
        case EQ -> "equal_to";
        case NEQ -> "not_equal_to";
        default -> throw unsupported("str(...) comparison supports only == and !=");
        };
        Expr lhs = ((Expr.Call) b.left()).args().get(0);
        Expr rhs = ((Expr.Call) b.right()).args().get(0);
        ObjectNode out = leaf(nameOf(lhs), operator, rhs);
        out.put("type_insensitive", true);
        return out;
    }


    private static JsonNode caseInsensitiveMembershipLeaf(Expr.Binary b)
    {
        String op = b.op() == Expr.BinOp.IN ? "is_contained_by_case_insensitive"
                : "is_not_contained_by_case_insensitive";
        String name = nameOf(((Expr.Call) b.left()).args().get(0));
        if (b.right() instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            ArrayNode arr = NODES.arrayNode(items.size());
            for (Expr item : items)
            {
                arr.add(NODES.textNode(membershipTerm(item)));
            }
            ObjectNode out = NODES.objectNode();
            out.put("name", name);
            out.put("operator", op);
            out.set("value", arr);
            return out;
        }
        return leaf(name, op, b.right());
    }


    private static String membershipTerm(Expr item)
    {
        Expr lit = isWrapper(item, "upper") ? ((Expr.Call) item).args().get(0) : item;
        if (lit instanceof Expr.Lit l && l.kind() == Expr.LitKind.STRING)
        {
            return (String) l.value();
        }
        throw unsupported("case-insensitive membership term must be a string or upper(\"…\")");
    }


    private static @Nullable JsonNode caseInsensitiveContainsLeaf(Expr.Call c)
    {
        if (!("contains".equals(c.name()) && c.kwargs().isEmpty() && c.args().size() == 2
                && isWrapper(c.args().get(0), "upper") && isWrapper(c.args().get(1), "upper")))
        {
            return null;
        }
        Expr subject = ((Expr.Call) c.args().get(0)).args().get(0);
        Expr literal = ((Expr.Call) c.args().get(1)).args().get(0);
        if (!(subject instanceof Expr.Ref ref)
                || !(literal instanceof Expr.Lit(Expr.LitKind kind, Object value))
                || kind != Expr.LitKind.STRING)
        {
            return null;
        }
        ObjectNode out = NODES.objectNode();
        out.put("name", ref.name());
        out.put("operator", "does_not_contain_case_insensitive");
        out.set("value", NODES.textNode((String) value));
        out.put("value_is_literal", true);
        return out;
    }

    // ---- predicates ------------------------------------------------------


    private static JsonNode lowerPredicate(Expr.Call c)
    {
        String n = c.name();
        int argc = c.args().size();
        if (FUNCTION_OPERATORS.contains(n))
        {
            return functionOperatorLeaf(c, n);
        }
        String negativeOperator = POSITIVE_TO_NEGATIVE.get(n);
        if (negativeOperator != null)
        {
            return NODES.objectNode().set("not", functionOperatorLeaf(c, negativeOperator));
        }
        if ("invalid_duration".equals(n) && c.kwargs().containsKey("negative"))
        {
            requireArgs(n, argc, 1);
            Expr neg = c.kwargs().get("negative");
            if (!(neg instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.BOOL)
            {
                throw unsupported("invalid_duration negative= must be a boolean literal");
            }
            ObjectNode out = NODES.objectNode();
            out.put("name", nameOf(c.args().get(0)));
            out.put("operator", n);
            out.put("negative", (Boolean) lit.value());
            return out;
        }
        if (UNARY_PREDICATES.contains(n))
        {
            requireArgs(n, argc, 1);
            String name = EXISTS_PREDICATES.contains(n) ? existsArgName(n, c.args().get(0))
                    : nameOf(c.args().get(0));
            ObjectNode out = NODES.objectNode();
            out.put("name", name);
            out.put("operator", n);
            return out;
        }
        if (SUBSTRING_PREDICATES.contains(n))
        {
            requireArgs(n, argc, 2);
            return leaf(nameOf(caseFoldedNameSubject(c.args().get(0))), n,
                    substringLiteral(c.args().get(1)));
        }
        return switch (n)
        {
        case "equalsIgnoreCase" -> twoArg(c, "equal_to_case_insensitive");
        case "prefix_matches", "suffix_matches" -> affixRegexLeaf(c, false);
        default -> throw unsupported("function '" + n + "' has no v1 lowering");
        };
    }


    /** A substring predicate's value stays a literal in the leaf, whatever its shape. */
    private static Expr substringLiteral(Expr value)
    {
        return value;
    }


    private static String existsArgName(String predicate, Expr arg)
    {
        // The varname() cursor — or its bareword twin `variable_name`, the same cursor read to
        // the compiler — is the variable-universe discriminator, with no operator-leaf surface.
        boolean cursor = (arg instanceof Expr.Call c && c.args().isEmpty() && c.kwargs().isEmpty()
                && "varname".equals(c.name()))
                || (arg instanceof Expr.Ref r && "variable_name".equals(r.name()));
        if (cursor)
        {
            throw unsupported("function '" + predicate + "' over the varname() cursor has no v1"
                    + " lowering (needs the native evaluator)");
        }
        String name = arg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING
                ? (String) lit.value()
                : nameOf(arg);
        if (("ds_exists".equals(predicate) || "ds_not_exists".equals(predicate))
                && (name.indexOf('.') >= 0 || name.indexOf('=') >= 0 || name.contains("${")
                        || name.contains("--")))
        {
            throw unsupported(predicate + " expects a plain dataset name");
        }
        return name;
    }


    private static JsonNode twoArg(Expr.Call c, String operator)
    {
        requireArgs(c.name(), c.args().size(), 2);
        return leaf(nameOf(c.args().get(0)), operator, c.args().get(1));
    }


    private static JsonNode affixRegexLeaf(Expr.Call c, boolean negated)
    {
        String kind = c.name().startsWith("prefix") ? "prefix" : "suffix";
        int argc = c.args().size();
        if (argc != 2 && argc != 3)
        {
            throw unsupported(c.name() + "(...) expects 2 or 3 arguments but got " + argc);
        }
        Expr pat = c.args().get(1);
        if (!(pat instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.REGEX)
        {
            throw unsupported(c.name() + "(...) requires a /regex/ literal as its second argument");
        }
        String operator = (negated ? "not_" : "") + kind + "_matches_regex";
        ObjectNode out = NODES.objectNode();
        out.put("name", nameOf(c.args().get(0)));
        out.put("operator", operator);
        out.set("value", NODES.textNode((String) lit.value()));
        out.put("value_is_literal", true);
        if (argc == 3)
        {
            out.put(kind, asInt(c.args().get(2)));
        }
        return out;
    }


    private static JsonNode affixRegexMatchLeaf(Expr.Binary b, Expr.Call affix)
    {
        String kind = affix.name();
        if (!(b.right() instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.REGEX)
        {
            throw unsupported("the right-hand side of =~ / !~ must be a /regex/ literal");
        }
        boolean negated = b.op() == Expr.BinOp.NMATCH;
        String operator = (negated ? "not_" : "") + kind + "_matches_regex";
        ObjectNode out = NODES.objectNode();
        out.put("name", ((Expr.Ref) affix.args().get(0)).name());
        out.put("operator", operator);
        out.set("value", NODES.textNode((String) lit.value()));
        out.put("value_is_literal", true);
        out.put(kind, asInt(affix.args().get(1)));
        return out;
    }


    private static JsonNode affixCompareLeaf(Expr.Binary b, Expr.Call affix)
    {
        String kind = affix.name();
        String name = ((Expr.Ref) affix.args().get(0)).name();
        int len = asInt(affix.args().get(1));
        String operator = switch (b.op())
        {
        case EQ -> kind + "_equal_to";
        case NEQ -> kind + "_not_equal_to";
        case IN -> kind + "_is_contained_by";
        case NOT_IN -> kind + "_is_not_contained_by";
        default -> throw unsupported(
                kind + "(...) supports only ==, !=, in and 'not in' comparisons");
        };
        ObjectNode out = leaf(name, operator, b.right());
        out.put(kind, len);
        return out;
    }


    private static JsonNode sortedByLeaf(Expr.Call c)
    {
        if (c.args().isEmpty())
        {
            throw unsupported("is_sorted_by(...) requires the column argument");
        }
        Expr by = c.kwargs().get("by");
        if (!(by instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.LIST)
        {
            throw unsupported("is_sorted_by requires a by=[…] descriptor list");
        }
        @SuppressWarnings("unchecked")
        List<Expr> descs = (List<Expr>) lit.value();
        ArrayNode arr = NODES.arrayNode(descs.size());
        for (Expr d : descs)
        {
            if (!(d instanceof Expr.Call dc)
                    || !("asc".equals(dc.name()) || "desc".equals(dc.name()))
                    || dc.args().size() != 1)
            {
                throw unsupported("sort descriptor must be asc(col) or desc(col)");
            }
            Expr key = dc.args().get(0);
            if (key instanceof Expr.Lit keyLit && keyLit.kind() == Expr.LitKind.STRING)
            {
                // Runbook W8 (PLAN-retire-operation-surface D-W8-6, runbook R1): the descriptor
                // used to REQUIRE the quoted spelling; a quoted name is a string, never a column
                // (mirrors the engine's ExprCompiler.groupOperandName / compileTargetIsNotSortedBy,
                // which refuses the same shape with the same reasoning).
                throw unsupported("sort descriptor " + dc.name() + "(\"" + keyLit.value()
                        + "\") takes a column reference, not a string literal — write " + dc.name()
                        + "(" + keyLit.value() + "); a quoted name is a string, never a column");
            }
            ObjectNode o = NODES.objectNode();
            o.put("name", sortDescriptorColumnName(key));
            o.put("sort_order", dc.name());
            Expr nulls = dc.kwargs().get("nulls");
            o.put("null_position",
                    nulls instanceof Expr.Lit nl && nl.kind() == Expr.LitKind.STRING
                            ? (String) nl.value()
                            : "last");
            arr.add(o);
        }
        ObjectNode out = NODES.objectNode();
        out.put("name", nameOf(c.args().get(0)));
        out.put("operator", "target_is_not_sorted_by");
        out.set("value", arr);
        Expr within = c.kwargs().get("within");
        if (within != null)
        {
            out.set("within", withinNode(within));
        }
        Expr keep = c.kwargs().get("keep_missings");
        if (keep != null)
        {
            if (!(keep instanceof Expr.Lit kl) || kl.kind() != Expr.LitKind.BOOL)
            {
                throw unsupported("keep_missings= must be a boolean literal");
            }
            out.put("keep_missings", (Boolean) kl.value());
        }
        return out;
    }


    /**
     * The raw name of an {@code is_sorted_by} sort-descriptor operand: a plain column reference, or
     * a {@code --}-prefix domain wildcard — the same two shapes the engine's
     * {@code ExprCompiler.groupOperandName} accepts for a group-operator operand. A string literal
     * is refused above this call, with its own message; anything else declines here.
     */
    private static String sortDescriptorColumnName(Expr e)
    {
        if (e instanceof Expr.Ref r && (r.kind() == net.cumba.corej.core.expr.OperandKind.COLUMN
                || (r.kind() == net.cumba.corej.core.expr.OperandKind.WILDCARD_COLUMN
                        && r.name().startsWith("--") && r.name().indexOf('.') < 0)))
        {
            return r.name();
        }
        throw unsupported("sort descriptor operand must be a plain column or --prefix reference"
                + " (got " + e.getClass().getSimpleName() + ")");
    }


    private static JsonNode functionOperatorLeaf(Expr.Call c, String operator)
    {
        if (c.args().isEmpty())
        {
            throw unsupported(c.name() + "(...) requires the column argument");
        }
        ObjectNode out = NODES.objectNode();
        Map<String, Expr> kw = c.kwargs();
        if (UNIQUE_SET_OPERATORS.contains(operator))
        {
            // Owner requirement #1 (2026-08-23): is_(not_)unique_set carries ONE list operand;
            // the LEAF wire form keeps its name/value split (D-2). The retired keys=/multi-arg
            // spellings are REFUSED, exactly as the engine's own lowering refused them.
            if (kw.containsKey("keys"))
            {
                throw unsupported(operator + " no longer takes keys= — write " + operator
                        + "([A, B, …]), one list operand");
            }
            if (c.args().size() != 1)
            {
                throw unsupported(operator + " takes exactly one list operand");
            }
            if (!(c.args().get(0) instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.LIST)
            {
                throw unsupported(operator + "'s operand must be a list literal: " + operator
                        + "([A, B, …])");
            }
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            if (items.isEmpty())
            {
                throw unsupported(operator + "([]) has no members");
            }
            out.put("name", referenceName(items.get(0)));
            out.put("operator", operator);
            if (items.size() > 1)
            {
                out.set("value", operandArray(
                        new Expr.Lit(Expr.LitKind.LIST, items.subList(1, items.size()))));
            }
        }
        else
        {
            out.put("name", nameOf(c.args().get(0)));
            out.put("operator", operator);
            if (c.args().size() >= 2)
            {
                Expr v = c.args().get(1);
                if (v instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.NUMBER)
                {
                    out.set("value", numberNode((Double) lit.value()));
                }
                else
                {
                    out.set("value", NODES.textNode(referenceName(v)));
                }
            }
            if (kw.containsKey("keys"))
            {
                out.set("value", operandArray(kw.get("keys")));
            }
        }
        if (kw.containsKey("within"))
        {
            out.set("within", withinNode(kw.get("within")));
        }
        if (kw.containsKey("ordering"))
        {
            out.put("ordering", referenceName(kw.get("ordering")));
        }
        if (kw.containsKey("regex"))
        {
            Expr r = kw.get("regex");
            if (!(r instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.STRING)
            {
                throw unsupported("regex= must be a string literal");
            }
            out.put("regex", (String) lit.value());
        }
        if (kw.containsKey("include_empty"))
        {
            Expr ie = kw.get("include_empty");
            if (!(ie instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.BOOL)
            {
                throw unsupported("include_empty= must be a boolean literal");
            }
            out.put("include_empty", (Boolean) lit.value());
        }
        if (kw.containsKey("keep_missings"))
        {
            Expr km = kw.get("keep_missings");
            if (!(km instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.BOOL)
            {
                throw unsupported("keep_missings= must be a boolean literal");
            }
            out.put("keep_missings", (Boolean) lit.value());
        }
        if (kw.containsKey("relation"))
        {
            Expr rel = kw.get("relation");
            if (!(rel instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.STRING)
            {
                throw unsupported("relation= must be a string literal");
            }
            out.put("relation", (String) lit.value());
        }
        return out;
    }


    private static boolean isTupleCall(Expr e)
    {
        return e instanceof Expr.Call c && "tuple".equals(c.name()) && c.kwargs().isEmpty()
                && !c.args().isEmpty() && c.args().stream().allMatch(a -> a instanceof Expr.Ref);
    }


    /** Inverse of the raise's composite-membership: the T3 {@code names:} leaf. */
    private static JsonNode tupleMembershipLeaf(Expr.Binary b)
    {
        if (!(b.right() instanceof Expr.Ref ref))
        {
            throw unsupported("tuple membership against a non-reference right side"
                    + " has no legacy surface");
        }
        ArrayNode names = NODES.arrayNode();
        ((Expr.Call) b.left()).args().forEach(a -> names.add(((Expr.Ref) a).name()));
        ObjectNode out = NODES.objectNode();
        out.set("names", names);
        out.put("operator", b.op() == Expr.BinOp.IN ? "is_contained_by" : "is_not_contained_by");
        out.set("value", NODES.textNode(ref.name()));
        return out;
    }

    // ---- operand helpers -------------------------------------------------


    private static Expr.@Nullable Call affixCall(Expr e)
    {
        if (e instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 2
                && ("prefix".equals(c.name()) || "suffix".equals(c.name()))
                && c.args().get(1) instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.NUMBER)
        {
            Expr subject = c.args().get(0);
            if (subject instanceof Expr.Ref)
            {
                return c;
            }
            Expr folded = caseFoldedNameSubject(subject);
            if (folded != subject && folded instanceof Expr.Ref)
            {
                return new Expr.Call(c.name(), List.of(folded, c.args().get(1)), Map.of());
            }
        }
        return null;
    }


    /**
     * {@code X} for {@code upper(X)} / {@code lower(X)} over a column-NAME source, else {@code e}
     * unchanged — the Java mirror of {@code check_expr.py}'s {@code _case_folded_subject}.
     *
     * <p>
     * CIT &#167;3, phase 3c of {@code PLAN-case-insensitive-templates} (2026-09-28): a rule tests a
     * column name as a string with the dataset side case-folded ({@code ends_with(upper(varname()),
     * "FL")}, {@code upper(varname()) =~ /^ANL/}, {@code prefix(upper(varname()), 2) not in $x}) so
     * the test also holds on lowercase-named data. The fold changes the letter case compared, not
     * what the rule reads or tests, so it lowers to the SAME leaf as the unwrapped form; without
     * this every folded rule would be unlowerable and drop out of every leaf-derived instrument.
     * Deliberately narrow: only the subject of a regex match, a substring predicate and a
     * prefix/suffix affix, and only over a NAME source. A folded CELL value
     * ({@code not contains(upper(VISIT), "UNSCHEDUL")}) is not unwrapped: the v1 vocabulary has a
     * case-insensitive leaf for a value, so a plain leaf would misstate it.
     * </p>
     */
    private static Expr caseFoldedNameSubject(Expr e)
    {
        if (e instanceof Expr.Call c && CASE_FOLDS.contains(c.name()) && c.args().size() == 1
                && c.kwargs().isEmpty() && isNameSubject(c.args().get(0)))
        {
            return c.args().get(0);
        }
        return e;
    }


    private static boolean isNameSubject(Expr e)
    {
        if (e instanceof Expr.Ref r)
        {
            return NAME_OPERANDS.contains(r.name());
        }
        if ("variable_name".equals(currentVariableOperand(e)))
        {
            return true;
        }
        String operand = MetadataOperandMapping.reverseToOperand(e);
        return operand != null && NAME_OPERANDS.contains(operand);
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


    private static boolean isWrapper(Expr e, String name)
    {
        return e instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 1
                && name.equals(c.name());
    }


    private static boolean isArith(Expr.BinOp op)
    {
        return op == Expr.BinOp.ADD || op == Expr.BinOp.SUB || op == Expr.BinOp.MUL
                || op == Expr.BinOp.DIV;
    }


    /** The general value leaf: reference values verbatim, literals with the literal flag. */
    private static ObjectNode leaf(String name, String operator, Expr value)
    {
        ObjectNode out = NODES.objectNode();
        out.put("name", name);
        out.put("operator", operator);
        if (isColRef(value))
        {
            Expr inner = ((Expr.Call) value).args().get(0);
            out.set("value", NODES.textNode(referenceName(inner)));
            out.put("value_is_reference", true);
        }
        else if (value instanceof Expr.Lit lit)
        {
            out.set("value", literalNode(lit));
            out.put("value_is_literal", true);
        }
        else
        {
            out.set("value", NODES.textNode(referenceName(value)));
        }
        return out;
    }


    private static boolean isColRef(Expr e)
    {
        return e instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 1
                && "colref".equals(c.name());
    }


    private static void rejectConversion(Expr e)
    {
        if (e instanceof Expr.Call c && "num".equals(c.name()) && c.args().size() == 1
                && c.kwargs().isEmpty())
        {
            throw unsupported("num(...) is a value conversion with no v1 operator surface; the "
                    + "rule is evaluated natively");
        }
    }


    private static String referenceName(Expr e)
    {
        rejectConversion(e);
        Expr inner = unwrap(e);
        String current = currentVariableOperand(inner);
        if (current != null)
        {
            return current;
        }
        String operand = MetadataOperandMapping.reverseToOperand(inner);
        if (operand != null)
        {
            return operand;
        }
        if (inner instanceof Expr.Ref r)
        {
            return r.name();
        }
        throw unsupported(
                "expected a column/reference operand but found " + e.getClass().getSimpleName());
    }


    private static String nameOf(Expr e)
    {
        rejectConversion(e);
        Expr inner = unwrap(e);
        String current = currentVariableOperand(inner);
        if (current != null)
        {
            return current;
        }
        String operand = MetadataOperandMapping.reverseToOperand(inner);
        if (operand != null)
        {
            return operand;
        }
        if (inner instanceof Expr.Ref r)
        {
            if (r.kind() == net.cumba.corej.core.expr.OperandKind.MATCHED_FLAG)
            {
                throw unsupported("the _matched_ join flag ('" + r.name()
                        + "') is a native evaluator construct with no v1 lowering");
            }
            return r.name();
        }
        throw unsupported("the left side of a comparison must be a column reference, found "
                + e.getClass().getSimpleName());
    }


    private static @Nullable String currentVariableOperand(Expr e)
    {
        if (e instanceof Expr.Call c && c.args().isEmpty() && c.kwargs().isEmpty())
        {
            if ("varname".equals(c.name()))
            {
                return "variable_name";
            }
            if ("value".equals(c.name()))
            {
                return "variable_value";
            }
        }
        return null;
    }


    private static JsonNode literalNode(Expr e)
    {
        if (!(e instanceof Expr.Lit lit))
        {
            throw unsupported("expected a literal but found " + e.getClass().getSimpleName());
        }
        return literalNode(lit);
    }


    private static JsonNode literalNode(Expr.Lit lit)
    {
        return switch (lit.kind())
        {
        case STRING, REGEX -> NODES.textNode((String) lit.value());
        case BOOL -> NODES.booleanNode((Boolean) lit.value());
        case NUMBER -> numberNode((Double) lit.value());
        case LIST ->
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            ArrayNode arr = NODES.arrayNode(items.size());
            for (Expr item : items)
            {
                if (item instanceof Expr.Lit il)
                {
                    arr.add(literalNode(il));
                }
                else
                {
                    throw unsupported("list elements must be literals");
                }
            }
            yield arr;
        }
        };
    }


    private static JsonNode numberNode(Double d)
    {
        if (!d.isInfinite() && Double.compare(d, Math.rint(d)) == 0)
        {
            return NODES.numberNode(d.longValue());
        }
        return NODES.numberNode(d);
    }


    private static ArrayNode operandArray(Expr e)
    {
        if (!(e instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.LIST)
        {
            throw unsupported(
                    "expected a [list] operand but found " + e.getClass().getSimpleName());
        }
        @SuppressWarnings("unchecked")
        List<Expr> items = (List<Expr>) lit.value();
        ArrayNode arr = NODES.arrayNode(items.size());
        for (Expr item : items)
        {
            if (item instanceof Expr.Lit il)
            {
                arr.add(literalNode(il));
            }
            else
            {
                arr.add(NODES.textNode(referenceName(item)));
            }
        }
        return arr;
    }


    private static JsonNode withinNode(Expr within)
    {
        if (!(within instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.LIST)
        {
            return NODES.textNode(referenceName(within));
        }
        @SuppressWarnings("unchecked")
        List<Expr> items = (List<Expr>) lit.value();
        ArrayNode arr = NODES.arrayNode(items.size());
        for (Expr item : items)
        {
            if (item instanceof Expr.Lit il && il.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> group = (List<Expr>) il.value();
                ArrayNode inner = NODES.arrayNode(group.size());
                for (Expr g : group)
                {
                    inner.add(NODES.textNode(referenceName(g)));
                }
                arr.add(inner);
            }
            else
            {
                arr.add(NODES.textNode(referenceName(item)));
            }
        }
        return arr;
    }


    private static int asInt(Expr e)
    {
        if (e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.NUMBER)
        {
            return (int) Math.rint((Double) lit.value());
        }
        throw unsupported("expected a numeric length but found " + e.getClass().getSimpleName());
    }


    private static void requireArgs(String fn, int actual, int expected)
    {
        if (actual != expected)
        {
            throw unsupported(fn + "(...) expects " + expected + " argument(s) but got " + actual);
        }
    }


    private static ExpressionException unsupported(String detail)
    {
        return new ExpressionException("Expression construct not supported by the v1 lowering "
                + "(needs the native evaluator): " + detail);
    }
}
