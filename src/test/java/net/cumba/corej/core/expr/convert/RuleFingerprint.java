package net.cumba.corej.core.expr.convert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeMap;
import net.cumba.corej.core.CheckExprUnits;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.MetadataOperandMapping;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import org.jspecify.annotations.Nullable;

/**
 * Layered, canonical fingerprint of a rule — the comparison primitive behind
 * {@code plans/done/PLAN-rule-inventory.md}.
 *
 * <p>
 * A single hash over a rule file conflates "these rules behave differently" with "these rules are
 * worded differently", so the rule is split into four independent layers:
 * </p>
 *
 * <ul>
 * <li><strong>L1</strong> — {@code Operations} + {@code Check}: what the engine executes. Two rules
 * with equal L1 <em>are</em> the same rule. For a <b>level-keyed</b> {@code Check} (Plan C
 * &#167;3.3) this is computed <b>per declared level</b> — see {@link #l1(JsonNode, boolean)}.</li>
 * <li><strong>L2</strong> — {@code Scope} + {@code Match_Datasets}: what it runs against. A
 * {@code Join_Type} difference is behavioural (R-7.6), so {@code Match_Datasets} belongs here and
 * must never fall through to a prose layer.</li>
 * <li><strong>L3</strong> — {@code Outcome.Message}, {@code Outcome.Output_Variables},
 * {@code Sensitivity}, {@code Executability} and any per-level {@code Message} override: what gets
 * reported.</li>
 * <li><strong>L4</strong> — {@code Description}: prose.</li>
 * </ul>
 *
 * <p>
 * <strong>Canonicalisation is mandatory.</strong> A naive comparison overstates divergence by
 * roughly 50%. Before hashing:
 * </p>
 *
 * <ol>
 * <li><strong>α-rename {@code $}-operation ids.</strong> R-6.1/R-6.2 require only a {@code $}
 * prefix and uniqueness; the name is free. The rename is substituted through {@code Check} — on the
 * parsed expression's {@link OperandKind#OPERATION_REF} nodes, so a string or regex literal that
 * happens to spell an id is never touched — <em>and</em> {@code Outcome.Output_Variables} —
 * renaming only one side charges the pair a phantom OV divergence (FDA-SD1307's {@code $HLTSUBJI_N}
 * vs PMDA-SD1307's {@code $HLTSUBJI_N_COUNT}).</li>
 * <li><strong>Sort {@code and}/{@code or} operand lists.</strong> Safe because the engine does not
 * short-circuit a conjunction into a different verdict: every operand is evaluated and combined.
 * R-7.8's "guard placed first" is therefore a readability SHOULD, not an execution requirement.
 * <b>If the engine ever starts short-circuiting, this step becomes unsound.</b></li>
 * <li><strong>Flatten nested conjunctions / disjunctions and collapse singletons</strong>
 * ({@code a and (b and c)} ⇒ {@code a and b and c}), applied to the <em>base</em> L1 — not only the
 * guard-stripped variant. Otherwise a grouping-only difference lands in the logic bucket and is
 * then mislabelled guard-only.</li>
 * <li><strong>Sort {@code Scope} include/exclude lists.</strong> {@code Match_Datasets} entry order
 * is <em>not</em> sorted — the join list is not established to be commutative.</li>
 * <li><strong>Compare {@code Output_Variables} as an α-renamed set.</strong> Its order is
 * prescribed by R-9.7/R-9.7b, so an ordering difference is a lint, not a divergence. A drift gate
 * must therefore assert OV <em>ordering</em> separately from L3 equality.</li>
 * </ol>
 *
 * <p>
 * ⭐ <b>L1 is computed on the parsed expression</b> ({@code PLAN-retire-check-lowering}, 2026-10):
 * each condition's text is parsed with the engine's {@link CheckExpressionParser}, canonicalised on
 * the {@link Expr} ({@link #canonical}), optionally guard-stripped, and printed back with
 * {@link ExpressionPrinter}. Until then the instruments hashed a lowering of the expression to the
 * retired operator-leaf JSON, which refused 81 rules outright (hashed as raw text) and read only
 * each leaf's left operand. The guard classes below are read off {@link CheckExprUnits}.
 * </p>
 *
 * <p>
 * {@link #l1(JsonNode, boolean) Guard stripping} is <strong>class-aware</strong>: an {@code exists}
 * guard is inert only for class-V targets. For class E ({@code empty}) it converts Java's
 * fire-on-absent into a no-fire; for class D it suppresses the intended coarser drop-and-regroup
 * (see {@code documentation/CORE-RULES-GUARD-MATRIX.md}). Such a guard is never stripped, and a
 * guard under a {@code not} is never stripped either — an {@code exists} under {@code not} is
 * {@code not_exists}, a deliberate fire-when-absent predicate.
 * </p>
 *
 * <p>
 * &#9733; <b>Level-keyed Checks (Plan C &#167;3.3).</b> A rule's {@code Check:} is either one
 * condition or a map from ladder level to that level's condition. The layering is unchanged by it:
 * {@link #l1(JsonNode, boolean)} hashes <b>every declared level's condition</b>, in ladder order,
 * and a level's optional {@code Message} joins {@link #l3(JsonNode)} — prose about what is
 * reported, never part of what is executed. ⛔ {@code Severity} itself is <b>not</b> hashed at any
 * layer and there is no {@code L5}: owner ruling {@code O-A} (2026-08-25) took the "hundreds"
 * branch of the plan's §4.7 decision rule — five measurements put the severity-divergent twin
 * classes between 278 and 284, and we do not pin hundreds of twins. Those classes are explained by
 * the verdict side-car's note (§4.8), not by a hash layer.
 * </p>
 *
 * <p>
 * ⚑ The corpus authors level maps (the {@code FDA}/{@code PMDA-SD0058} family and a handful of
 * {@code ERROR}/{@code INFO} rules), so the per-level paths below are exercised by the corpus as
 * well as by the synthetic input of their tests. The alternative — a fingerprint that reads
 * {@code Check} as one condition — would hash a level map as an opaque object and bucket two
 * genuinely different multi-level rules together, failing nothing.
 * </p>
 */
final class RuleFingerprint
{

    /**
     * The authorable check levels, <b>strictest first</b> — Plan C §3.4's evaluation order, and
     * therefore the order every per-level hash below concatenates in. Literals rather than a read
     * of {@code net.cumba.datatable.report.Severity} so that enum's report-only {@code NOTICE}
     * constant, which no rule may author, can never become a level key here.
     */
    private static final List<String> LADDER = List.of("REJECT", "ERROR", "WARNING", "INFO");

    /** The optional per-level message key; mirrors {@code RuleCheckDeserializer.MESSAGE_KEY}. */
    static final String MESSAGE_KEY = "Message";

    /**
     * Operators for which an {@code exists} guard on the same target is <em>not</em> inert: class E
     * ({@code empty} — Java fires on an absent column, Python skips) and class D (drop-and-regroup
     * — both engines fire the coarser check on an absent column). Stripping a guard on such a
     * target changes meaning.
     *
     * <p>
     * ⚠ <b>Under-inclusive since EC-43 / Fix #139, deliberately left as-is.</b> An absent column
     * now evaluates as all-missing, so a <em>class-V</em> leaf fires on it too — which means an
     * {@code exists} guard on almost any negative target has stopped being inert, not just on the
     * five operators listed here. The list was NOT widened, because widening it to every
     * fold-affected operator would make
     * {@link #l1(com.fasterxml.jackson.databind.JsonNode, boolean) l1NoGuards} nearly identical to
     * {@code l1} and the guard-stripping layer would lose its purpose entirely. That is the real
     * signal: <b>the layer's premise — "which guards are semantically inert" — is exactly what
     * EC-43 changed</b>, and re-deciding it is a rule-equivalence policy question owned by
     * {@code PLAN-polarity-aware-guard-classification}, which is sequenced to run after EC-43 for
     * this reason.
     * </p>
     *
     * <p>
     * <b>Consequence to be aware of when reading inventory output:</b> {@code guardEquivalent} /
     * {@code guardOnly} verdicts computed from {@code l1NoGuards} may now call two rules equivalent
     * when the guard is in fact load-bearing. Treat them as triage, not truth, until that plan
     * lands. No shipped behaviour depends on this set — it is a test-tree inventory tool.
     * </p>
     */
    private static final Set<String> GUARD_SENSITIVE_OPS = Set.of("empty",
            "additional_columns_empty", "is_unique_set", "is_not_unique_set",
            "is_inconsistent_across_dataset");

    /** The presence family: never a "use" of the name it tests. */
    private static final Set<String> PRESENCE_CALLS = Set.of("var_exists", "var_not_exists",
            "ds_exists", "ds_not_exists");

    private RuleFingerprint()
    {
    }

    /** The four layers of one rule. Equal {@link #l1()} ⇒ the same executable check. */
    record Fingerprint(String l1, String l1NoGuards, String l2, String l3, String l4)
    {
    }

    static Fingerprint of(JsonNode rule)
    {
        return new Fingerprint(l1(rule, false), l1(rule, true), l2(rule), l3(rule), l4(rule));
    }

    // ------------------------------------------------------------------ layers


    /**
     * What the engine executes. {@code stripGuards} removes inert {@code exists} guards.
     *
     * <p>
     * <b>Per level (Plan C §3.3).</b> {@code Operations} are shared by every level of a rule, so
     * they are hashed once; the {@code Check} half is the level's condition for a plain
     * {@code Check:} and the ladder-ordered map {@code {LEVEL=<canonical condition>, …}} for a
     * level map. Two rules are the same rule iff they declare the same levels <em>and</em> each
     * level's condition matches — which is what falls out of hashing the map rather than its
     * strictest entry alone.
     * </p>
     *
     * <p>
     * ⚑ The {@code Bindings} half is the canonical JSON of each (renamed) binding; the
     * {@code Check} half is the canonical expression text ({@link #canonicalExpression}). A level's
     * {@code Message} is stripped before hashing — it is L3's, not L1's.
     * </p>
     */
    static String l1(JsonNode rule, boolean stripGuards)
    {
        Renamed renamed = alphaRename(rule);
        List<String> ops = new ArrayList<>();
        for (JsonNode op : renamed.operations())
        {
            ops.add(canon(op));
        }
        ops.sort(Comparator.naturalOrder());
        SequencedMap<String, JsonNode> levels = renamed.levels();
        if (levels == null)
        {
            return ops + "|" + canonicalCheck(renamed.check(), stripGuards, renamed.mapping());
        }
        StringBuilder rendered = new StringBuilder("{");
        for (Map.Entry<String, JsonNode> level : levels.entrySet())
        {
            if (rendered.length() > 1)
            {
                rendered.append(", ");
            }
            rendered.append(level.getKey()).append('=')
                    .append(canonicalCheck(level.getValue(), stripGuards, renamed.mapping()));
        }
        return ops + "|" + rendered.append('}');
    }


    /**
     * One condition's canonical L1 text: the parsed expression, α-renamed ({@link #renameRefs}),
     * optionally guard-stripped, flattened and sorted, printed. A node that is not an expression
     * Check (no {@code Check} at all, or a shape the loader rejects) is hashed as its canonical
     * JSON, renamed generically, so a rule without a Check still fingerprints; an unparseable
     * expression propagates — the loader rejects it too, and hashing its text would bucket a load
     * error as a rule.
     */
    private static String canonicalCheck(@Nullable JsonNode condition, boolean stripGuards,
            Map<String, String> mapping)
    {
        JsonNode text = condition == null ? null : condition.get("expression");
        if (text == null || !text.isTextual())
        {
            return canon(substitute(condition, mapping));
        }
        return canonicalExpression(renameRefs(CheckExpressionParser.parse(text.asText()), mapping),
                stripGuards);
    }


    /**
     * α-renames a parsed condition: every {@link OperandKind#OPERATION_REF} reference whose id
     * {@code mapping} names is replaced by its synthetic {@code $__n}; nothing else moves. Renaming
     * on the {@link Expr} rather than on the {@code Check} text (T2 review L4,
     * {@code PLAN-retire-check-lowering}) is what keeps a string or regex literal that spells an id
     * — {@code "$x"}, {@code /^$x/} — out of the rename: a text substitution cannot tell a token
     * from a literal, and a renamed literal is a changed rule.
     */
    static Expr renameRefs(Expr e, Map<String, String> mapping)
    {
        return switch (e)
        {
        case Expr.Ref r when r.kind() == OperandKind.OPERATION_REF
                && mapping.containsKey(r.name()) -> new Expr.Ref(mapping.get(r.name()), r.kind());
        case Expr.Ref r -> r;
        case Expr.Lit l when l.kind() == Expr.LitKind.LIST -> new Expr.Lit(l.kind(),
                ((List<?>) l.value()).stream().map(i -> (Object) renameRefs((Expr) i, mapping))
                        .toList());
        case Expr.Lit l -> l;
        case Expr.Not n -> new Expr.Not(renameRefs(n.inner(), mapping));
        case Expr.And a -> new Expr.And(
                a.parts().stream().map(p -> renameRefs(p, mapping)).toList());
        case Expr.Or o -> new Expr.Or(o.parts().stream().map(p -> renameRefs(p, mapping)).toList());
        case Expr.Binary b -> new Expr.Binary(b.op(), renameRefs(b.left(), mapping),
                renameRefs(b.right(), mapping));
        case Expr.Call c ->
        {
            Map<String, Expr> kwargs = new LinkedHashMap<>();
            c.kwargs().forEach((k, v) -> kwargs.put(k, renameRefs(v, mapping)));
            yield new Expr.Call(c.name(),
                    c.args().stream().map(a -> renameRefs(a, mapping)).toList(), kwargs);
        }
        };
    }


    /**
     * The declared levels of a {@code Check:} node, strictest first and with each level's
     * {@code Message} stripped — or {@code null} when the node is a plain condition (Plan C §3.3's
     * two-way grammar dispatch, mirroring {@code RuleCheckDeserializer.bind}).
     *
     * <p>
     * A node is a level map only when <b>every</b> key is an authorable level name. That mirrors
     * the loader exactly: a mixed map and an unknown level name are load errors there, and a rule
     * carrying one never reaches an inventory run — so treating anything else as a plain condition
     * reproduces today's behaviour for every well-formed rule and cannot mis-hash a malformed one
     * into a level map.
     * </p>
     *
     * @param check
     *            the rule's {@code Check:} node, may be {@code null}
     * @return the level → condition map in ladder order, or {@code null} for a plain condition
     */
    static @Nullable SequencedMap<String, JsonNode> levels(@Nullable JsonNode check)
    {
        if (check == null || !check.isObject() || check.isEmpty())
        {
            return null;
        }
        for (Iterator<String> names = check.fieldNames(); names.hasNext();)
        {
            if (!LADDER.contains(names.next()))
            {
                return null;
            }
        }
        SequencedMap<String, JsonNode> out = new LinkedHashMap<>();
        for (String level : LADDER)
        {
            JsonNode value = check.get(level);
            if (value == null)
            {
                continue;
            }
            if (value.isObject() && value.has(MESSAGE_KEY))
            {
                ObjectNode stripped = ((ObjectNode) value).deepCopy();
                stripped.remove(MESSAGE_KEY);
                out.put(level, stripped);
            }
            else
            {
                out.put(level, value);
            }
        }
        return out;
    }


    /**
     * Every condition a rule's {@code Check:} declares, strictest level first — one element for a
     * plain {@code Check:}, one per declared level for a level map.
     *
     * <p>
     * ⚠⚠ The read surface for any walker that must see <b>every</b> level (the R-9.7 ordering gate,
     * a leaf sweep, a lint). Reading {@code rule.get("Check")} directly sees a level map as an
     * object with no {@code name} and no composite key, so such a walker silently answers "nothing
     * here" instead of walking the levels — the same trap {@code Rule.effectiveCheckLevels()}
     * exists to close on the engine side.
     * </p>
     *
     * @param rule
     *            the whole rule node
     * @return the declared conditions, ladder order; empty when the rule declares no {@code Check}
     */
    static List<JsonNode> checkConditions(JsonNode rule)
    {
        JsonNode check = rule.get("Check");
        if (check == null || check.isNull())
        {
            return List.of();
        }
        SequencedMap<String, JsonNode> declared = levels(check);
        return declared == null ? List.of(check) : List.copyOf(declared.values());
    }


    /**
     * What the rule runs against: {@code Scope} (lists sorted), {@code Match_Datasets} (raw) and
     * {@code Requirements} (facet lists sorted).
     *
     * <p>
     * ⚠ {@code Requirements} is a <b>third term</b>, not a re-point of the first: neither this
     * method nor its Python twin ever named {@code Scope.Variables} — both walk {@code Scope}
     * generically, so {@code Scope.Datasets} needs no edit at all and joined L2 the day the axis
     * existed. What does need saying is that the variable requirement <em>leaves</em> {@code Scope}
     * in the migration, and without this term two rules differing only in their required variables
     * would collapse into one equivalence class
     * ({@code plans/done/PLAN-scope-requirements-split.md} phase 1 item 6).
     * </p>
     *
     * <p>
     * ⚑ When the term was added (phase 1) no rule authored the block, so it shifted every L2 string
     * by the same constant and the <em>relative</em> fact the registry records — which rules agree
     * on L2 — was unchanged. That is no longer the situation: measured 2026-08-24, <b>5 733</b> of
     * 14 416 shipped rule records carry {@code Requirements}, so two rules that agreed on L2 before
     * the migration and differ in their required variables now correctly fall into different
     * classes. The registry moved with phase 4, not with this method.
     * </p>
     *
     * <p>
     * ⚑ Until {@code PLAN-retire-check-lowering} (2026-10) a Python twin ({@code
     * generate-inventory.py}'s {@code l2}) had to be kept in lockstep with this method, and the
     * depths at which the two sorted lists differed are recorded in
     * {@link #sortArraysWithin(JsonNode)}. The twin is retired; this is the one L2.
     * </p>
     */
    static String l2(JsonNode rule)
    {
        JsonNode scope = rule.get("Scope");
        StringBuilder sb = new StringBuilder();
        if (scope != null && scope.isObject())
        {
            Map<String, String> canonical = new TreeMap<>();
            Iterator<String> names = scope.fieldNames();
            while (names.hasNext())
            {
                String key = names.next();
                canonical.put(key, canon(sortArraysWithin(scope.get(key))));
            }
            sb.append(canonical);
        }
        else
        {
            sb.append(canon(scope));
        }
        sb.append('|').append(canon(rule.get("Match_Datasets")));
        return sb.append('|').append(canon(sortArraysWithin(rule.get("Requirements")))).toString();
    }


    /**
     * What gets reported. {@code Output_Variables} is compared as an α-renamed <em>set</em>;
     * {@code Executability} belongs here or it is invisible to the drift gate.
     *
     * <p>
     * <strong>{@code Rule_Type} is deliberately not a member, and must not be re-derived into
     * one.</strong> {@code PLAN-derive-rule-type-sensitivity.md} ({@code 7eb9ca270}) removed the
     * authored field — it is derived at load time now — so hashing it here contributed a constant
     * {@code null}: a silent no-op that also made the plan's §2.1 field list untrue. Re-deriving it
     * would be a no-op too, and provably so: {@link net.cumba.corej.core.exec.RuleClassifier} reads
     * only {@code Operations} + {@code Check}, through an <em>id-free</em> operation-usage view, so
     * the derived value is a function of L1's own inputs and is invariant under α-rename. L3 is
     * only ever consulted where L1 is already equal — {@code bucket} reaches {@code sameL3} only
     * after {@code sameL1}, and the registry's divergence flags are computed inside one L1 class —
     * so a derived {@code Rule_Type} can never separate two rules L1 has not already separated.
     * Removing it changes no bucket and no divergence flag; the committed registry is unaffected.
     * </p>
     */
    static String l3(JsonNode rule)
    {
        JsonNode outcome = rule.get("Outcome");
        JsonNode message = outcome == null ? null : outcome.get("Message");
        List<String> authored = new ArrayList<>();
        for (JsonNode entry : alphaRename(rule).outputVariables())
        {
            authored.add(entry.asText());
        }
        // Ruling 6 (2026-08-23, PLAN-authoring-grammar-unique-set-and-output-exclusion §5.7): OV
        // is fingerprinted AFTER exclusions are applied — `!X` removes X, and the token itself is
        // never a member. L3 asks "do these two rules report the same thing?", and two rules
        // that report the same set answer YES however they spelled it. (alphaRename renames
        // THROUGH the marker:
        // substitute() strips the `!`, α-renames the inner id and re-prefixes it, so an `!$id`
        // token arrives here as `!$__n` and cancels exactly the renamed member it names.)
        List<String> outputVariables = new ArrayList<>(
                net.cumba.corej.core.model.OutputVariableToken.applyExclusions(authored));
        outputVariables.sort(Comparator.naturalOrder());
        return canon(message) + "|" + outputVariables + "|" + canon(rule.get("Sensitivity")) + "|"
                + canon(rule.get("Executability")) + levelMessages(rule);
    }


    /**
     * The per-level {@code Message} overrides (Plan C §3.3, ruling 6), ladder-ordered, as an L3
     * suffix — or the <b>empty string</b> when no level declares one.
     *
     * <p>
     * ⭐ Why L3 and not L1: a level's {@code Message} is what the finding <em>says</em>, exactly
     * like {@code Outcome.Message} beside it. Hashing it into L1 would call two rules that execute
     * identically "different rules" and split their equivalence class over prose.
     * </p>
     *
     * <p>
     * ⚑ Why the empty string rather than a constant {@code |[]}: almost every shipped rule authors
     * a plain {@code Check:} and therefore no per-level message, so an unconditional suffix would
     * shift thousands of L3 strings to record a fact they do not carry. Relative comparison would
     * survive it — L3 is only ever compared against another L3 — but a no-op that rewrites every
     * hash is a change to explain later for no gain today.
     * </p>
     */
    private static String levelMessages(JsonNode rule)
    {
        JsonNode check = rule.get("Check");
        if (check == null || levels(check) == null)
        {
            return "";
        }
        List<String> messages = new ArrayList<>();
        for (String level : LADDER)
        {
            JsonNode value = check.get(level);
            if (value != null && value.isObject() && value.hasNonNull(MESSAGE_KEY))
            {
                messages.add(level + "=" + canon(value.get(MESSAGE_KEY)));
            }
        }
        return messages.isEmpty() ? "" : "|" + messages;
    }


    static String l4(JsonNode rule)
    {
        return canon(rule.get("Description"));
    }

    // --------------------------------------------------------- canonicalisation

    /**
     * {@code Operations}, {@code Check} and {@code Output_Variables} after α-renaming.
     *
     * @param operations
     *            the renamed {@code Operations} entries, authored order
     * @param check
     *            the {@code Check:} node exactly as authored, <b>not</b> renamed — the plain
     *            condition, or the whole level map. Never {@code null}-checked here; {@link #l1}
     *            reads it only on the plain branch
     * @param levels
     *            the per-level conditions in ladder order, not renamed, {@code Message} stripped,
     *            or {@code null} when the {@code Check:} is a plain condition
     * @param outputVariables
     *            the renamed {@code Outcome.Output_Variables} entries
     * @param mapping
     *            authored id → synthetic {@code $__n}; the {@code Check} conditions are renamed
     *            with it on their parsed expression ({@link #renameRefs}), not here
     */
    record Renamed(List<JsonNode> operations, JsonNode check,
            @Nullable SequencedMap<String, JsonNode> levels, List<JsonNode> outputVariables,
            Map<String, String> mapping)
    {
    }

    /**
     * Sorts the operations by their content with {@code id} removed, renames the ids to
     * {@code $__0..$__n}, and substitutes the mapping through the operations and
     * {@code Outcome.Output_Variables}; the {@code Check} is renamed later, on its parsed
     * expression ({@link #renameRefs}). Two operations collapse onto the same synthetic id only
     * when their content is identical, i.e. when they compute the same value.
     */
    static Renamed alphaRename(JsonNode rule)
    {
        List<JsonNode> operations = new ArrayList<>();
        JsonNode declared = rule.get("Bindings");
        if (declared != null && declared.isArray())
        {
            declared.forEach(operations::add);
        }
        List<JsonNode> ordered = new ArrayList<>(operations);
        ordered.sort(Comparator.comparing(op -> canon(withoutId(op))));

        Map<String, String> mapping = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++)
        {
            JsonNode id = ordered.get(i).get("name");
            if (id != null && id.isTextual())
            {
                mapping.put(id.asText(), "$__" + i);
            }
        }

        List<JsonNode> renamedOps = new ArrayList<>();
        for (JsonNode op : operations)
        {
            renamedOps.add(substitute(op, mapping));
        }

        List<JsonNode> outputVariables = new ArrayList<>();
        JsonNode outcome = rule.get("Outcome");
        JsonNode declaredOv = outcome == null ? null : outcome.get("Output_Variables");
        if (declaredOv != null && declaredOv.isArray())
        {
            declaredOv.forEach(entry -> outputVariables.add(substitute(entry, mapping)));
        }

        // ⚑ The Check is NOT text-substituted (T2 review L4): canonicalCheck renames each
        // condition on its parsed Expr, where a `$id` reference and a literal spelling one differ.
        JsonNode check = rule.get("Check");
        return new Renamed(renamedOps, check, levels(check), outputVariables, Map.copyOf(mapping));
    }

    // ------------------------------------------------- the Expr canonical form


    /**
     * The canonical text of one parsed condition: guard-stripped when asked, then {@link #canonical
     * flattened and sorted}, then printed. A condition that is nothing but inert guards strips to
     * the literal {@code true}.
     */
    static String canonicalExpression(Expr parsed, boolean stripGuards)
    {
        Expr e = parsed;
        if (stripGuards)
        {
            Expr stripped = dropGuards(e, namesUsed(e), guardSensitiveNames(e), true);
            e = stripped == null ? new Expr.Lit(Expr.LitKind.BOOL, true) : stripped;
        }
        return ExpressionPrinter.print(canonical(e));
    }


    /**
     * The canonical shape of an expression: nested {@code and}s / {@code or}s flattened into their
     * parent (associativity), singletons collapsed, and every {@code and} / {@code or} operand list
     * sorted by its printed text (commutativity). Operands of calls and comparisons are
     * canonicalised in place; their order is meaning.
     */
    static Expr canonical(Expr e)
    {
        return switch (e)
        {
        case Expr.And a -> composite(true, a.parts());
        case Expr.Or o -> composite(false, o.parts());
        case Expr.Not n -> new Expr.Not(canonical(n.inner()));
        case Expr.Binary b -> new Expr.Binary(b.op(), canonical(b.left()), canonical(b.right()));
        case Expr.Call c ->
        {
            Map<String, Expr> kwargs = new LinkedHashMap<>();
            c.kwargs().forEach((k, v) -> kwargs.put(k, canonical(v)));
            yield new Expr.Call(c.name(),
                    c.args().stream().map(RuleFingerprint::canonical).toList(), kwargs);
        }
        case Expr.Lit l when l.kind() == Expr.LitKind.LIST -> new Expr.Lit(Expr.LitKind.LIST,
                ((List<?>) l.value()).stream().map(i -> canonical((Expr) i)).toList());
        default -> e;
        };
    }


    private static Expr composite(boolean conjunction, List<Expr> parts)
    {
        List<Expr> flat = new ArrayList<>();
        for (Expr part : parts)
        {
            Expr c = canonical(part);
            if (conjunction && c instanceof Expr.And a)
            {
                flat.addAll(a.parts());
            }
            else if (!conjunction && c instanceof Expr.Or o)
            {
                flat.addAll(o.parts());
            }
            else
            {
                flat.add(c);
            }
        }
        if (flat.size() == 1)
        {
            return flat.get(0);
        }
        flat.sort(Comparator.comparing(ExpressionPrinter::print));
        return conjunction ? new Expr.And(flat) : new Expr.Or(flat);
    }

    // ----------------------------------------------------------------- guards


    /**
     * Every name the condition reads outside the presence family — column, {@code $}-binding,
     * dotted and built-in references, and the metadata operand a {@code var_*} accessor or the
     * {@code varname()} / {@code value()} cursor names — used to decide whether a
     * {@code var_exists} leaf guards a variable the Check otherwise reads. A string literal is a
     * value, never a name.
     */
    static Set<String> namesUsed(Expr e)
    {
        Set<String> used = new HashSet<>();
        collectNamesUsed(e, used);
        return used;
    }


    private static void collectNamesUsed(Expr e, Set<String> out)
    {
        switch (e)
        {
        case Expr.Ref r -> out.add(r.name());
        case Expr.Call c ->
        {
            if (PRESENCE_CALLS.contains(c.name()))
            {
                return;
            }
            String operand = metadataOperand(c);
            if (operand != null)
            {
                out.add(operand);
            }
            c.args().forEach(a -> collectNamesUsed(a, out));
            c.kwargs().values().forEach(a -> collectNamesUsed(a, out));
        }
        case Expr.Binary b ->
        {
            collectNamesUsed(b.left(), out);
            collectNamesUsed(b.right(), out);
        }
        case Expr.And a -> a.parts().forEach(p -> collectNamesUsed(p, out));
        case Expr.Or o -> o.parts().forEach(p -> collectNamesUsed(p, out));
        case Expr.Not n -> collectNamesUsed(n.inner(), out);
        case Expr.Lit l ->
        {
            if (l.kind() == Expr.LitKind.LIST)
            {
                ((List<?>) l.value()).forEach(i -> collectNamesUsed((Expr) i, out));
            }
        }
        }
    }


    private static @Nullable String metadataOperand(Expr.Call c)
    {
        if (c.args().isEmpty() && c.kwargs().isEmpty())
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
        return MetadataOperandMapping.reverseToOperand(c);
    }


    /**
     * Names read by a class-E or class-D operator anywhere in the condition — the target of every
     * {@link CheckExprUnits} unit whose operator is in {@link #GUARD_SENSITIVE_OPS} (a
     * {@code [not] is_unique_set([A, …])} names its first member, D-2).
     */
    static Set<String> guardSensitiveNames(Expr e)
    {
        Set<String> sensitive = new HashSet<>();
        for (CheckExprUnits.Unit unit : CheckExprUnits.units(e))
        {
            if (unit.name() != null && unit.operator() != null
                    && GUARD_SENSITIVE_OPS.contains(unit.operator()))
            {
                sensitive.add(unit.name());
            }
        }
        return sensitive;
    }


    /**
     * Removes inert {@code var_exists} guards from every conjunction in positive polarity. Never
     * strips under a {@code not} (there the conjunction is a disjunction, and an {@code exists}
     * under {@code not} is a fire-when-absent predicate), and never strips a guard whose target is
     * read by a class-E/D operator. A conjunction or disjunction emptied by stripping yields
     * {@code null}; one left with a single operand yields that operand.
     */
    static @Nullable Expr dropGuards(Expr e, Set<String> used, Set<String> sensitive,
            boolean positive)
    {
        return switch (e)
        {
        case Expr.And a ->
        {
            List<Expr> kept = new ArrayList<>();
            for (Expr part : a.parts())
            {
                Expr survivor = dropGuards(part, used, sensitive, positive);
                if (survivor == null || (positive && isStrippableGuard(survivor, used, sensitive)))
                {
                    continue;
                }
                kept.add(survivor);
            }
            yield kept.isEmpty() ? null : kept.size() == 1 ? kept.get(0) : new Expr.And(kept);
        }
        case Expr.Or o ->
        {
            List<Expr> kept = new ArrayList<>();
            for (Expr part : o.parts())
            {
                Expr survivor = dropGuards(part, used, sensitive, positive);
                if (survivor != null)
                {
                    kept.add(survivor);
                }
            }
            yield kept.isEmpty() ? null : kept.size() == 1 ? kept.get(0) : new Expr.Or(kept);
        }
        case Expr.Not n ->
        {
            Expr inner = dropGuards(n.inner(), used, sensitive, !positive);
            yield inner == null ? null : new Expr.Not(inner);
        }
        default -> e;
        };
    }


    private static boolean isStrippableGuard(Expr e, Set<String> used, Set<String> sensitive)
    {
        if (!(e instanceof Expr.Call c) || !"var_exists".equals(c.name()) || c.args().size() != 1
                || !c.kwargs().isEmpty())
        {
            return false;
        }
        String name = presenceName(c.args().get(0));
        return name != null && used.contains(name) && !sensitive.contains(name);
    }


    /** The name a presence call tests: its quoted or bare operand. */
    private static @Nullable String presenceName(Expr arg)
    {
        return switch (arg)
        {
        case Expr.Lit l when l.kind() == Expr.LitKind.STRING -> (String) l.value();
        case Expr.Ref r -> r.name();
        default -> null;
        };
    }

    // ------------------------------------------------------------- primitives

    /** {@code JsonNode.fields()} is deprecated; iterate by name instead. */
    @FunctionalInterface
    private interface FieldVisitor
    {

        void accept(String key, JsonNode value);
    }

    private static void forEachField(JsonNode node, FieldVisitor visitor)
    {
        Iterator<String> keys = node.fieldNames();
        while (keys.hasNext())
        {
            String key = keys.next();
            visitor.accept(key, node.get(key));
        }
    }


    private static JsonNode withoutId(JsonNode operation)
    {
        if (!operation.isObject())
        {
            return operation;
        }
        ObjectNode copy = ((ObjectNode) operation).deepCopy();
        copy.remove("name");
        return copy;
    }


    /** Whether {@code c} may continue a {@code $}-id token ({@code $}-ids are barewords). */
    private static boolean isIdentifierChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }


    /**
     * Recursively replaces any string equal to a mapped operation id — and, since Form B, any
     * {@code $}-id token embedded in a longer string (an operation {@code expression}).
     */
    private static JsonNode substitute(JsonNode node, Map<String, String> mapping)
    {
        if (node == null)
        {
            return null;
        }
        if (node.isTextual())
        {
            String text = node.asText();
            // An `!$id` exclusion token (OutputVariableToken) is renamed by its bare id, so
            // l3's apply-exclusions step can match it against the renamed `$__n` entry.
            if (net.cumba.corej.core.model.OutputVariableToken.isExclusion(text))
            {
                String inner = net.cumba.corej.core.model.OutputVariableToken.name(text);
                String renamed = mapping.get(inner);
                return renamed == null ? node
                        : TextNode.valueOf(
                                net.cumba.corej.core.model.OutputVariableToken.MARKER + renamed);
            }
            String replacement = mapping.get(text);
            if (replacement != null)
            {
                return TextNode.valueOf(replacement);
            }
            // Form B (PLAN-retire-corpus-transforms phase 8; T2 review M1): a cross-reference
            // to another operation's `$`-id now lives INSIDE an `expression:` string
            // (`subtract="$present"`, `group=[..., $TIMING_VARIABLES]`) rather than in its own
            // scalar. Rename such embedded tokens by word boundary, or L1 stops being
            // invariant under the R-6.1/R-6.2 free renaming of operation ids.
            if (text.indexOf('$') >= 0)
            {
                String rewritten = text;
                for (Map.Entry<String, String> entry : mapping.entrySet())
                {
                    String id = entry.getKey();
                    StringBuilder sb = new StringBuilder();
                    int from = 0;
                    int at;
                    while ((at = rewritten.indexOf(id, from)) >= 0)
                    {
                        int end = at + id.length();
                        boolean boundary = end >= rewritten.length()
                                || !isIdentifierChar(rewritten.charAt(end));
                        sb.append(rewritten, from, at).append(boundary ? entry.getValue() : id);
                        from = end;
                    }
                    sb.append(rewritten.substring(from));
                    rewritten = sb.toString();
                }
                if (!rewritten.equals(text))
                {
                    return TextNode.valueOf(rewritten);
                }
            }
            return node;
        }
        if (node.isObject())
        {
            ObjectNode out = ((ObjectNode) node).objectNode();
            forEachField(node, (key, value) -> out.set(key, substitute(value, mapping)));
            return out;
        }
        if (node.isArray())
        {
            ArrayNode out = ((ArrayNode) node).arrayNode();
            node.forEach(child -> out.add(substitute(child, mapping)));
            return out;
        }
        return node;
    }


    /**
     * Sorts every array reachable through objects, at <em>any</em> depth — {@code Scope}'s
     * include/exclude lists and {@code Requirements}' facet lists. See the {@code else} branch for
     * the depth at which this stops matching its Python twin.
     */
    private static JsonNode sortArraysWithin(JsonNode node)
    {
        if (node == null || !node.isObject())
        {
            return node;
        }
        ObjectNode out = ((ObjectNode) node).objectNode();
        forEachField(node, (key, value) ->
        {
            if (value.isArray())
            {
                List<JsonNode> entries = new ArrayList<>();
                value.forEach(entries::add);
                entries.sort(Comparator.comparing(RuleFingerprint::canon));
                ArrayNode sorted = out.arrayNode();
                entries.forEach(sorted::add);
                out.set(key, sorted);
            }
            else
            {
                // ⚠⚠ RECURSE. Until 2026-08-24 this branch copied a nested object verbatim, so the
                // method sorted arrays exactly ONE object level deep. That was invisible while its
                // only two callers were `Scope`'s per-key values ({Include: [...]}, already one
                // level) — but `l2` calls it on the WHOLE `Requirements` block, whose lists live
                // one level deeper ({Variables: {All: [...]}}), and the then Python twin
                // (`_sort_lists_within`, retired 2026-10) sorted TWO levels. The two halves
                // therefore disagreed the moment `Requirements` gained its first carriers
                // (PLAN-scope-requirements-split phase 4a): FDA-SD1047 and CDISC-CG0111 carry the
                // same requirement in opposite entry order, Python bucketed the pair 0-identical
                // and Java B-targeting, and RuleEquivalenceInventoryTest went red.
                //
                // BLAST RADIUS, measured 2026-08-25 by replaying the old and the new branch over
                // the corpus: 112 of the 3 804 rule files under `rules-src/checks` move their L2
                // string — 111 of the 3 773 in the four families RuleEquivalenceInventory loads,
                // plus DRAFT-900006, which it does not load; that is the whole of the 111-vs-112
                // discrepancy between the two figures in circulation. All 112 are attributable to
                // `Requirements`; ZERO to `Scope`, whose every corpus array sits at
                // `Scope.<key>.<facet>` and was already sorted. 24 L2 blocks fuse (1 009 → 985
                // distinct L2 strings over the whole tree; 1 000 → 976 over the inventory view),
                // every one of them order-only.
                //
                // ⚠⚠ This was NOT lockstep with the Python twin while it existed (a hard-coded
                // TWO-LEVEL dict comprehension; this is unbounded); kept as the record of where
                // the two sorted differently. Relative to the block root:
                // • depth 1 (`Requirements.Datasets: [...]`) — both sort;
                // • depth 2 (`Requirements.Variables.All: [...]`) — both sort. This is the fix;
                // • depth ≥ 3 — Java sorts, Python does NOT. A future three-level facet such as
                // `Requirements: {Variables: {All: {Any_Of: ["B", "A"]}}}` is sorted here and left
                // in authored order there, so the two lanes bucket the pair differently and
                // RuleEquivalenceInventoryTest reds with a symptom identical to the one this
                // recursion just fixed, one level deeper;
                // • and in the OTHER direction, a list authored directly under `Scope`
                // (`Scope.<key>: [...]`) is sorted by Python and NOT here, because `l2` hands this
                // method `Scope[key]` and a non-object is returned untouched. Zero carriers today.
                // Changing the reach on either side is a change to BOTH halves.
                out.set(key, sortArraysWithin(value));
            }
        });
        return out;
    }


    /** Deterministic serialisation: object keys sorted, array order preserved. */
    static String canon(JsonNode node)
    {
        StringBuilder sb = new StringBuilder();
        canon(node, sb);
        return sb.toString();
    }


    private static void canon(JsonNode node, StringBuilder sb)
    {
        if (node == null || node.isNull())
        {
            sb.append("null");
            return;
        }
        if (node.isObject())
        {
            sb.append('{');
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(Comparator.naturalOrder());
            for (int i = 0; i < keys.size(); i++)
            {
                if (i > 0)
                {
                    sb.append(',');
                }
                sb.append('"').append(keys.get(i)).append("\":");
                canon(node.get(keys.get(i)), sb);
            }
            sb.append('}');
            return;
        }
        if (node.isArray())
        {
            sb.append('[');
            for (int i = 0; i < node.size(); i++)
            {
                if (i > 0)
                {
                    sb.append(',');
                }
                canon(node.get(i), sb);
            }
            sb.append(']');
            return;
        }
        sb.append(node.toString());
    }
}
