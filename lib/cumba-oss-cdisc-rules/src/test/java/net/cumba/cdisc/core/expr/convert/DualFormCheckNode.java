package net.cumba.cdisc.core.expr.convert;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.ExprLowering;
import net.cumba.cdisc.core.expr.ExpressionException;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionAny;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.CheckConditionNot;
import org.jspecify.annotations.Nullable;

/**
 * Dual-form {@code Check} support for the raw-{@link JsonNode} corpus instruments
 * ({@code RuleEquivalenceInventory}, {@code RuleDocCensus}): the Java counterpart of
 * {@code scripts/lib/check_expr.py}'s {@code dual_form_check}. A legacy Check node passes through
 * unchanged; an {@code {expression}} Check is parsed with the shared parser and lowered back to the
 * legacy {@code all}/{@code any}/{@code not}/leaf shape those instruments' operator tables key on.
 *
 * <p>
 * The lowering targets the image of the flip writer (plan §5, the RuleType-aware raise), so it is
 * {@link ExprLowering#toCheckConditionForConversion} plus the two inverses that forward half keeps
 * in {@code CheckToExpr} rather than in the strict surface (mirroring {@code check_expr.py}):
 * {@code not <exists-family>(X)} is reconstructed as the flat negative leaf ({@code var_not_exists}
 * / {@code ds_not_exists} / {@code not_exists}) — without it every negative presence rule would
 * lower to a structural {@code {not: …}} the pre-flip corpus never authored, shifting the
 * equivalence canonical basis — and {@code tuple(c1, …) [not] in <ref>} is reconstructed as the T3
 * composite {@code names:} membership leaf. A construct with no legacy surface even then (the
 * conversion-unlowerable rules, e.g. {@code char(value()) <= 32}) yields {@code null} — callers
 * keep the expression node and report, or fall back to {@link #carriesPresenceCall} to prove the
 * skip loses nothing they classify.
 * </p>
 */
public final class DualFormCheckNode
{

    /** Nulls omitted so a lowered leaf carries exactly the fields an authored leaf carries. */
    private static final ObjectMapper JSON = JsonMapper.builder().defaultPropertyInclusion(
            JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .build();

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
        Expr parsed;
        try
        {
            parsed = CheckExpressionParser.parse(check.path("expression").asText());
        }
        catch (ExpressionException _)
        {
            return null;
        }
        CheckCondition lowered = lowerConditionOrNull(parsed);
        return lowered == null ? null : JSON.valueToTree(lowered);
    }


    /**
     * The dual-form lowering over a raised {@code Expr}: {@link ExprLowering}'s conversion mode
     * plus the two writer-image inverses ({@code check_expr.py}'s extras) — flat negative presence
     * leaves and the T3 {@code tuple(...) [not] in} composite membership leaf.
     *
     * @param parsed
     *            the raised expression
     * @return the operator-leaf tree, or {@code null} when a construct has no legacy surface
     */
    public static @Nullable CheckCondition lowerConditionOrNull(Expr parsed)
    {
        try
        {
            return lowerCondition(parsed);
        }
        catch (ExpressionException _)
        {
            return null;
        }
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
    public static void collectRefNames(Expr e, java.util.Set<String> out)
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


    private static CheckCondition lowerCondition(Expr e)
    {
        return switch (e)
        {
        case Expr.And a -> new CheckConditionAll(
                a.parts().stream().map(DualFormCheckNode::lowerCondition).toList());
        case Expr.Or o -> new CheckConditionAny(
                o.parts().stream().map(DualFormCheckNode::lowerCondition).toList());
        case Expr.Not n ->
        {
            if (n.inner() instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 1
                    && NEGATIVE_BY_POSITIVE_EXISTS.containsKey(c.name()))
            {
                yield ExprLowering.toCheckConditionForConversion(new Expr.Call(
                        NEGATIVE_BY_POSITIVE_EXISTS.get(c.name()), c.args(), Map.of()));
            }
            // Inverse of CheckToExpr's case-insensitive-contains fold (mirrors
            // check_expr.py's _case_insensitive_contains): `not contains(upper(X), upper("v"))`
            // was raised from a does_not_contain_case_insensitive leaf — ExprLowering's own
            // contains branch would demand a bare reference subject and throw.
            CheckConditionLeaf folded = caseInsensitiveContainsLeaf(n.inner());
            if (folded != null)
            {
                yield folded;
            }
            // A negated call or comparison must go through ExprLowering as the WHOLE Not node:
            // lowerNot's inverse table (not empty → non_empty, not contains → does_not_contain,
            // the POSITIVE_TO_NEGATIVE group operators, …) and the negated regexForm restores
            // both dispatch on the Not — handing over only the inner would lose every one of
            // them and reconstruct a structural {not: …} the pre-flip corpus never authored
            // (found by the wave-43 review: 2 064 rules diverged from check_expr.py's view).
            if (n.inner() instanceof Expr.Call
                    || (n.inner() instanceof Expr.Binary b && !isTupleCall(b.left())))
            {
                yield ExprLowering.toCheckConditionForConversion(e);
            }
            yield new CheckConditionNot(lowerCondition(n.inner()));
        }
        case Expr.Binary b when (b.op() == Expr.BinOp.IN || b.op() == Expr.BinOp.NOT_IN)
                && isTupleCall(b.left()) -> tupleMembershipLeaf(b);
        default -> ExprLowering.toCheckConditionForConversion(e);
        };
    }


    /**
     * The {@code does_not_contain_case_insensitive} leaf for a
     * {@code contains(upper(X), upper("v"))} inner, or {@code null} when the shape does not match.
     */
    private static @Nullable CheckConditionLeaf caseInsensitiveContainsLeaf(Expr inner)
    {
        if (!(inner instanceof Expr.Call c && "contains".equals(c.name()) && c.kwargs().isEmpty()
                && c.args().size() == 2 && isUpperCall(c.args().get(0))
                && isUpperCall(c.args().get(1))))
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
        return CheckConditionLeaf.builder().name(ref.name())
                .operator("does_not_contain_case_insensitive")
                .value(JSON.getNodeFactory().textNode((String) value)).valueIsLiteral(true).build();
    }


    private static boolean isUpperCall(Expr e)
    {
        return e instanceof Expr.Call c && "upper".equals(c.name()) && c.kwargs().isEmpty()
                && c.args().size() == 1;
    }


    /** The T3 composite-key {@code tuple(c1, c2, …)} value function (all-Ref args, no kwargs). */
    private static boolean isTupleCall(Expr e)
    {
        return e instanceof Expr.Call c && "tuple".equals(c.name()) && c.kwargs().isEmpty()
                && !c.args().isEmpty() && c.args().stream().allMatch(a -> a instanceof Expr.Ref);
    }


    /** Inverse of {@code CheckToExpr.compositeMembershipLeaf}: the {@code names:} leaf. */
    private static CheckCondition tupleMembershipLeaf(Expr.Binary b)
    {
        List<String> names = ((Expr.Call) b.left()).args().stream().map(a -> ((Expr.Ref) a).name())
                .toList();
        if (!(b.right() instanceof Expr.Ref ref))
        {
            throw new ExpressionException("tuple membership against a non-reference right side"
                    + " has no legacy surface");
        }
        return CheckConditionLeaf.builder().names(names)
                .operator(b.op() == Expr.BinOp.IN ? "is_contained_by" : "is_not_contained_by")
                .value(JSON.getNodeFactory().textNode(ref.name())).build();
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
    private static boolean lowerLevelsInPlace(ObjectNode check, java.util.Set<String> levels)
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

}
