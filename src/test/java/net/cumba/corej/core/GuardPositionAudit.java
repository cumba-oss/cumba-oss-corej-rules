package net.cumba.corej.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.expr.ExprLowering;
import net.cumba.corej.core.expr.convert.DualFormCheckNode;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.CheckConditionNot;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import org.jspecify.annotations.Nullable;

/**
 * The <b>guard-position</b> predicate of {@code plans/done/PLAN-guard-position-lint.md} &mdash;
 * {@code Fix #237}.
 *
 * <h2>The authoring rule it enforces</h2>
 *
 * <p>
 * {@code STANDING-RULINGS.md} &sect;1, decided 2026-08-07: <em>"A check below an {@code any} is not
 * to be seen as a guard."</em> A presence leaf inside an {@code any} <b>cannot</b> guard, because a
 * sibling disjunct can still satisfy the branch; only a leaf that the whole {@code Check} implies
 * &mdash; a conjunct &mdash; guards. Nothing in the engine enforces this, so a guard authored into
 * an {@code any} is silently inert. {@code CORE-000269} / {@code CORE-000270} are the first
 * measured instances, and there the misplaced guard is <b>worse than inert</b>: on an absent
 * {@code SV}, {@code not_exists(SV.SVPRESP)} is TRUE, so the disjunct is satisfied and the rule
 * proceeds into a vacuous {@code $tv_visit} membership test and floods.
 * </p>
 *
 * <h2>The predicate</h2>
 *
 * <p>
 * A <b>foreign presence leaf</b> is a {@link CheckConditionLeaf} whose operator is one of
 * </p>
 * <ul>
 * <li><b>column presence</b> &mdash; {@code exists}, {@code not_exists}, {@code var_exists},
 * {@code var_not_exists}; the {@code name} is a column, so the dataset comes from a dotted
 * {@code DOMAIN.VAR} prefix or, for a {@code $}-id, from its {@code Operations} entry's
 * {@code domain:}; or</li>
 * <li><b>dataset presence</b> &mdash; {@code ds_exists}, {@code ds_not_exists}; the {@code name}
 * <b>is</b> the dataset.</li>
 * </ul>
 * <p>
 * &hellip; whose resolved dataset is <b>not</b> in the rule's own {@code Scope.Domains.Include}.
 * &#9888; A {@code Match_Datasets} entry is deliberately <b>not</b> "own": a joined dataset is
 * exactly the cross-dataset surface a guard exists to protect, and {@code CORE-000269} reaches
 * {@code SV} that way.
 * </p>
 *
 * <p>
 * Each such leaf is placed in one of two {@linkplain Position positions} by a polarity-aware walk
 * from the {@code Check} root:
 * </p>
 * <ul>
 * <li>{@link Position#GUARD} &mdash; the whole {@code Check} implies the leaf, so it really does
 * gate every other conjunct: <b>(a)</b>.</li>
 * <li>{@link Position#DISJUNCT} &mdash; anywhere else; a sibling branch can satisfy the check
 * without it: <b>(b)</b>, a candidate defect.</li>
 * </ul>
 *
 * <h2>&#9888; Why the walk is polarity-aware rather than a "direct child of the root {@code all}"
 * test</h2>
 *
 * <p>
 * Two shapes a naive positional test gets wrong, in opposite directions:
 * </p>
 * <ul>
 * <li><b>Nested {@code all}.</b> Conjunction is associative, so a leaf in
 * {@code all[ all[ G, X ], Y ]} guards exactly as well as one in {@code all[ G, X, Y ]}. A "direct
 * child of the root" test <b>false-positives</b> on it.</li>
 * <li><b>{@code not} is not {@code any}.</b> De Morgan: under an odd number of {@code not}s an
 * {@code any} becomes a conjunction and an {@code all} becomes a disjunction. So a leaf in
 * {@code all[ not[ any[ G, X ] ], Y ]} <b>is</b> implied by the check (in negated form) and does
 * guard, while a leaf in {@code not[ all[ G, X ] ]} does not. The walk therefore tracks negation
 * parity and reports it via {@link PresenceLeaf#negated()} rather than folding {@code not} into
 * either bucket. &#9888; The authored corpus contains <b>zero</b> {@code not:} nodes today, so this
 * arm is exercised only by the synthetics in {@code GuardPositionLintTest} &mdash; it is kept
 * because the alternative is an arm that rots silently and then mis-classifies the first rule
 * authored with one.</li>
 * </ul>
 *
 * <h2>&#9888;&#9888; Bucket (c) &mdash; the surface no <em>column</em> guard can reach</h2>
 *
 * <p>
 * A rule can reach a foreign dataset without ever naming one of its columns, through an
 * {@code Operations} entry's {@code domain:} &mdash; {@code $ta_epoch = distinct(EPOCH,
 * domain: "TA")}. {@code exists} / {@code var_exists} take a <b>column</b> name, so no
 * column-presence guard is writable for such a surface. Those rules are enumerated by
 * {@link #operationOnlyDatasets(Rule)} and are <b>never</b> flagged: they carry no presence leaf at
 * all, so they are structurally outside the (a)/(b) population, and flagging them would put ~40
 * false positives on the lint's first run.
 * </p>
 *
 * <p>
 * &#9888; <b>But "no guard is expressible" is too strong</b>, and this class is the place that
 * records why: {@code ds_exists} / {@code ds_not_exists} take a <b>plain dataset name</b>
 * ({@code OperandSubstitutor} line ~453; {@code RulePackageLoader} Review F2), and the corpus
 * already uses them that way 90 times &mdash; {@code CDISC-CG0107} ships
 * {@code all[ $exvamtu_exists == true, EC ds_exists ]}. A <em>dataset-level</em> guard is therefore
 * writable for every bucket-(c) surface; what is missing is only a <em>column-level</em> one. The
 * count is reported so the owner can decide, with the number in hand, whether the gap is worth
 * closing.
 * </p>
 *
 * <h2>Mitigation &mdash; {@code Fix #246}</h2>
 *
 * <p>
 * The five CG0031/CG0033-family defects were closed by the <b>dotted-{@code exists} idiom</b> (plan
 * &sect;9a, re-taken 2026-08-13): a top-level {@code all} conjunct {@code TV.VISIT exists} (resp.
 * {@code TV.VISITNUM}) &mdash; not {@code ds_exists}, which the corpus assembler rejects under a
 * non-presence {@code Rule_Type} (since {@code Fix #255} the engine itself no longer re-types such
 * a guard &mdash; shape B demotes to Record Data and executes &mdash; but the authoring-side
 * rejection stands, so the dotted idiom remains the authorable form), and not on {@code SV}, whose
 * absence is the Description's documented fallback rather than the flood. The misplaced {@code SV}
 * leaf itself stays where it was authored, so it stays in bucket (b); what changes is that its
 * flood route is now structurally closed. {@link PresenceLeaf#mitigated()} derives exactly that
 * condition, cross-dataset and with <b>no exemption list</b>: every dataset whose vacuous
 * {@code $}-operation the {@code Check} tests with a {@linkplain #VACUOUS_ON_EMPTY_MEMBERSHIP
 * vacuous-on-empty} membership operator must carry a presence-implying leaf in guard position.
 * </p>
 */
public final class GuardPositionAudit
{

    /** Presence operators whose {@code name} is a <b>column</b>. */
    private static final Set<String> COLUMN_PRESENCE = Set.of("var_exists", "var_not_exists");

    /** Presence operators whose {@code name} is a <b>dataset</b>. */
    private static final Set<String> DATASET_PRESENCE = Set.of("ds_exists", "ds_not_exists");

    /**
     * Presence operators that assert presence in their authored polarity ({@code var_exists} rather
     * than {@code var_not_exists}). Used by the {@linkplain PresenceLeaf#mitigated() mitigation}
     * derivation: only a leaf that <em>implies presence</em> when the whole {@code Check} implies
     * it can suppress a vacuous-set flood.
     */
    private static final Set<String> POSITIVE_PRESENCE = Set.of("var_exists", "ds_exists");

    /**
     * Membership operators that are vacuously <b>true</b> against an empty {@code $}-set. This is
     * the flood mechanism of the CG0031/CG0033 family: {@code distinct(VISIT, domain: "TV")} over
     * an absent {@code TV} yields the empty set ({@code OperationExecutor}'s declared
     * {@code EmptyResult.SET}), and {@code x is_not_contained_by {}} is then true on <b>every</b>
     * row. An equality against an absent column is false and a {@code contains_all}-shaped
     * containment passes on an empty required set, so neither can flood this way &mdash; the set is
     * deliberately this narrow, and widening it is a finding, not a tweak.
     *
     * <p>
     * &#9888; Three shipped cousins are equally vacuous-on-empty and deliberately <b>not</b>
     * modelled: {@code is_not_contained_by_case_insensitive} (14 uses),
     * {@code prefix_is_not_contained_by} (4) and {@code suffix_is_not_contained_by} (4). Measured
     * 2026-08-13: none of their {@code $}-ref uses carries an {@code Operations[].domain:}, so no
     * rule can currently reach a foreign vacuous set through them and modelling them would be an
     * untestable arm. A rule that ever combines a modelled-and-guarded flood with an unmodelled
     * cousin flood would derive {@code mitigated == true} with an open route &mdash; if one of the
     * cousins ever gains a {@code domain:}-carrying {@code $}-ref, add it here in the same change.
     * The {@code collectDangerous} walk is likewise polarity-blind by choice: a {@code not} above a
     * <em>modelled</em> leaf only makes this derivation <em>under</em>-mitigate (the safe
     * direction), while the mirror shape &mdash; {@code not} over a positive containment, a real
     * flood &mdash; is unmodelled and behaves exactly like the cousins above. The corpus ships zero
     * {@code not:} nodes, so both arms are synthetic-only today.
     */
    private static final Set<String> VACUOUS_ON_EMPTY_MEMBERSHIP = Set.of("is_not_contained_by");

    private GuardPositionAudit()
    {
    }

    /** Where a presence leaf sits in the {@code Check} tree, after De Morgan. */
    public enum Position
    {
        /** The whole {@code Check} implies the leaf &mdash; it really guards. Bucket (a). */
        GUARD,
        /**
         * A sibling branch can satisfy the check without it. Bucket (b) &mdash; candidate defect.
         */
        DISJUNCT
    }


    /**
     * One foreign presence leaf and where it sits.
     *
     * @param ruleId
     *            {@code Core.Id} of the rule carrying the leaf
     * @param dataset
     *            the foreign dataset the leaf probes
     * @param operator
     *            the leaf's presence operator, as authored
     * @param position
     *            {@link Position#GUARD} (bucket a) or {@link Position#DISJUNCT} (bucket b)
     * @param negated
     *            whether an odd number of {@code not} nodes sits between the root and the leaf, so
     *            the leaf's authored polarity is inverted in the check's meaning
     * @param mitigated
     *            {@code Fix #246}: for a {@link Position#DISJUNCT} leaf only &mdash; whether the
     *            flood the misplaced guard could enable is <b>structurally suppressed</b>, because
     *            every dataset whose vacuous {@code $}-operation makes the disjunct dangerous (a
     *            {@linkplain #VACUOUS_ON_EMPTY_MEMBERSHIP vacuous-on-empty} membership test against
     *            a foreign {@code domain:}) carries a presence-<em>implying</em> guard in
     *            {@linkplain Position#GUARD guard position}. &#9888; Cross-dataset by design: on
     *            the CG0031/CG0033 family the flagged leaf is on {@code SV} while the mitigating
     *            guard is {@code TV.VISIT exists} &mdash; the guard sits on the dataset that
     *            floods, not on the dataset the misplaced leaf names. Derived structurally from the
     *            {@code Check}; there is <b>no exemption list</b>. A rule with no vacuous
     *            {@code $}-membership at all has nothing to mitigate and reports {@code false}.
     * @param path
     *            the combinator path from the {@code Check} root, e.g. {@code all/any}, for the
     *            report
     */
    public record PresenceLeaf(String ruleId, String dataset, String operator, Position position,
            boolean negated, boolean mitigated, String path)
    {
    }

    /**
     * Every foreign presence leaf of one <b>authored</b> rule, in document order.
     *
     * @param rule
     *            a rule deserialised from {@code rules-src/checks/**}
     * @return the leaves; empty when the rule probes no foreign dataset's presence
     */
    public static List<PresenceLeaf> presenceLeaves(Rule rule)
    {
        return presenceLeaves(rule, true);
    }


    /**
     * The <b>refuted naive reading</b>, kept live as a control: position and foreignness only, with
     * no {@linkplain #guardable reads-elsewhere} clause. It is not the predicate; it is the proof
     * that the clause is load-bearing rather than decorative. Measured 2026-08-12 it returned
     * <b>129</b> leaves against the real predicate's <b>49</b> (re-derived 2026-08-13 after
     * {@code Fix #246}: <b>134</b> against <b>54</b> &mdash; the five new {@code TV} guards count
     * in both readings), and its three extra {@code CDISC-SEND-0400} disjuncts are pure false
     * positives.
     *
     * @param rule
     *            a rule deserialised from {@code rules-src/checks/**}
     * @return every foreign presence leaf, guardable or not
     */
    public static List<PresenceLeaf> presenceLeavesIgnoringReadsElsewhere(Rule rule)
    {
        return presenceLeaves(rule, false);
    }


    /**
     * The operator-leaf view of an {@code {expression}} Check subtree — {@code Fix #256}, the
     * pre-flip re-point of {@code plans/done/PLAN-rules-src-expression-flip.md} §7b. All three of
     * this audit's walks ({@link #walk}, {@link #collectDangerous}, {@link #collectDottedPrefixes})
     * are shape-total over the operator-leaf AST; a Check arrives as a
     * {@link CheckConditionExpression} only when the strict load-path lowering declined it, so the
     * raised {@code Expr} is lowered through the engine's own conversion-mode machinery
     * ({@link ExprLowering#toCheckConditionForConversion}) and walked as exactly the operator-leaf
     * tree it is equivalent to — both authoring forms audit identically by construction.
     *
     * <p>
     * A native-only expression (no operator-leaf surface even in conversion mode) fails LOUDLY
     * rather than being skipped: a silent skip would let a misplaced guard hide inside an
     * expression and would let the reads-elsewhere and mitigation derivations under-count.
     * </p>
     */
    private static CheckCondition lowered(CheckConditionExpression expression)
    {
        CheckCondition condition = DualFormCheckNode.lowerConditionOrNull(expression.expr());
        if (condition != null)
        {
            return condition;
        }
        // A native-only expression (no operator-leaf surface even with the dual-form inverses).
        // The audit classifies presence leaves; when the expression provably carries no
        // exists-family call, an empty contribution loses nothing this audit counts. A native-only
        // expression that DOES carry one still fails loudly — that is the case where a skip would
        // hide a misplaced guard (plans/done/PLAN-rules-src-expression-flip.md §7b).
        if (!DualFormCheckNode.carriesPresenceCall(expression.expr()))
        {
            return new CheckConditionAll(List.of());
        }
        throw new IllegalStateException("GuardPositionAudit cannot audit the expression-form"
                + " Check \"" + expression.source() + "\": it has no operator-leaf equivalent"
                + " even in conversion mode, and it carries a presence call. Add a native Expr"
                + " arm to the audit instead of skipping the Check"
                + " (plans/done/PLAN-rules-src-expression-flip.md §7b).");
    }


    private static List<PresenceLeaf> presenceLeaves(Rule rule, boolean requireRead)
    {
        CheckCondition check = rule.getCheck();
        if (check == null)
        {
            return List.of();
        }
        Map<String, Operation> operations = operationsById(rule);
        Set<String> own = ownDomains(rule);
        Set<String> guardable = requireRead ? guardable(rule, operations) : null;
        List<PresenceLeaf> out = new ArrayList<>();
        walk(check, true, true, "", new Context(ruleId(rule), operations, own, guardable), out);
        return mitigate(check, operations, own, out);
    }


    /**
     * The {@code Fix #246} mitigation pass: stamps {@link PresenceLeaf#mitigated()} on the
     * {@link Position#DISJUNCT} leaves of one rule.
     *
     * <p>
     * A misplaced guard's danger is not the leaf itself &mdash; it is that the disjunct passes
     * vacuously (its dataset absent &#8658; {@code not_exists} true) and evaluation then proceeds
     * into a {@linkplain #VACUOUS_ON_EMPTY_MEMBERSHIP vacuous-on-empty} membership test whose
     * {@code $}-operation reads a foreign {@code domain:} that may itself be absent. The flood is
     * cut by guarding <em>that</em> dataset: when every such dangerous dataset carries a
     * presence-implying leaf in {@linkplain Position#GUARD guard position}, the whole {@code Check}
     * implies the dataset is present and the vacuous-set route is closed &mdash; regardless of the
     * dataset the misplaced leaf itself names.
     * </p>
     */
    private static List<PresenceLeaf> mitigate(CheckCondition check,
            Map<String, Operation> operations, Set<String> own, List<PresenceLeaf> leaves)
    {
        Set<String> dangerous = dangerousDatasets(check, operations, own);
        if (dangerous.isEmpty())
        {
            return List.copyOf(leaves);
        }
        Set<String> guarded = new TreeSet<>();
        for (PresenceLeaf leaf : leaves)
        {
            // A leaf implies presence when the check implies it (GUARD) and its effective
            // polarity is positive: an authored `exists`, or a `not_exists` under an odd
            // number of `not` nodes (De Morgan turns it back into a presence assertion).
            if (leaf.position() == Position.GUARD
                    && POSITIVE_PRESENCE.contains(leaf.operator()) != leaf.negated())
            {
                guarded.add(leaf.dataset());
            }
        }
        if (!guarded.containsAll(dangerous))
        {
            return List.copyOf(leaves);
        }
        List<PresenceLeaf> out = new ArrayList<>(leaves.size());
        for (PresenceLeaf leaf : leaves)
        {
            out.add(leaf.position() == Position.DISJUNCT
                    ? new PresenceLeaf(leaf.ruleId(), leaf.dataset(), leaf.operator(),
                            leaf.position(), leaf.negated(), true, leaf.path())
                    : leaf);
        }
        return List.copyOf(out);
    }


    /**
     * The foreign datasets whose absence makes this {@code Check} flood: each is the
     * {@code domain:} of a {@code $}-operation used as the {@code value} of a
     * {@linkplain #VACUOUS_ON_EMPTY_MEMBERSHIP vacuous-on-empty} membership leaf. An absent dataset
     * there yields an empty set and the membership test fires on every row.
     */
    private static Set<String> dangerousDatasets(@Nullable CheckCondition node,
            Map<String, Operation> operations, Set<String> own)
    {
        Set<String> dangerous = new TreeSet<>();
        collectDangerous(node, operations, own, dangerous);
        return dangerous;
    }


    private static void collectDangerous(@Nullable CheckCondition node,
            Map<String, Operation> operations, Set<String> own, Set<String> into)
    {
        switch (node)
        {
        case null ->
        {
            // No Check at all.
        }
        case CheckConditionAll all -> all.getConditions()
                .forEach(c -> collectDangerous(c, operations, own, into));
        case CheckConditionAny any -> any.getConditions()
                .forEach(c -> collectDangerous(c, operations, own, into));
        case CheckConditionNot not -> collectDangerous(not.getCondition(), operations, own, into);
        case CheckConditionLeaf leaf ->
        {
            if (!VACUOUS_ON_EMPTY_MEMBERSHIP.contains(leaf.getOperator()))
            {
                return;
            }
            // The membership target is a JsonNode: a textual `$`-ref, or an array whose
            // elements may themselves be `$`-refs (an array value IS a ref — the
            // dangling-$-operand load check exists because missing that shape silently
            // over-reports).
            JsonNode value = leaf.getValue();
            if (value == null)
            {
                return;
            }
            List<String> refs = new ArrayList<>();
            if (value.isTextual())
            {
                refs.add(value.textValue());
            }
            else if (value.isArray())
            {
                value.forEach(element ->
                {
                    if (element.isTextual())
                    {
                        refs.add(element.textValue());
                    }
                });
            }
            for (String ref : refs)
            {
                if (ref == null || !ref.startsWith("$"))
                {
                    continue;
                }
                Operation operation = operations.get(ref);
                String domain = operation == null ? null : trimToNull(operation.getDomain());
                if (domain != null && !"*".equals(domain) && !own.contains(domain))
                {
                    into.add(domain);
                }
            }
        }
        case CheckConditionExpression expression -> collectDangerous(lowered(expression),
                operations, own, into);
        default ->
        {
            // Constants carry no membership test.
        }
        }
    }


    /**
     * The datasets this rule <b>reads</b>, i.e. the ones a guard could have something to protect: a
     * {@code Match_Datasets} join, a dotted {@code D.COL} reference, or an {@code Operations}
     * entry's {@code domain:}.
     *
     * <h4>&#9888;&#9888; Why the population is narrowed by this, and not only by position</h4>
     *
     * <p>
     * A presence leaf on a dataset the rule never otherwise touches is not a misplaced guard
     * &mdash; it is <b>the rule's own assertion</b>. {@code CDISC-SEND-0400} is
     * {@code any[ TT ds_not_exists, TP ds_not_exists, SJ ds_not_exists ]} and its whole purpose is
     * to report a missing DART domain; every one of its three leaves sits under a top-level
     * {@code any} and none of them is a guard in any sense. Position alone puts all three in bucket
     * (b) &mdash; <b>3 false positives out of 9</b>, measured 2026-08-12 before this clause
     * existed. A guard protects a <em>read</em>, so requiring the read is what separates the two.
     * </p>
     */
    private static Set<String> guardable(Rule rule, Map<String, Operation> operations)
    {
        Set<String> read = new TreeSet<>();
        List<MatchDataset> matches = rule.getMatchDatasets();
        if (matches != null)
        {
            for (MatchDataset match : matches)
            {
                String name = trimToNull(match.getName());
                if (name != null)
                {
                    read.add(name);
                }
            }
        }
        for (Operation operation : operations.values())
        {
            String domain = trimToNull(operation.getDomain());
            if (domain != null)
            {
                read.add(domain);
            }
        }
        collectDottedPrefixes(rule.getCheck(), operations, read, true);
        return read;
    }


    /**
     * Bucket <b>(c)</b> &mdash; the foreign datasets this rule reaches <b>only</b> through an
     * {@code Operations} entry's {@code domain:}, so that no <em>column</em>-presence guard is
     * writable for them. Never a defect; counted and named, never flagged.
     *
     * @param rule
     *            a rule deserialised from {@code rules-src/checks/**}
     * @return the operation-only foreign datasets, sorted and de-duplicated; empty for most rules
     */
    public static List<String> operationOnlyDatasets(Rule rule)
    {
        Set<String> own = ownDomains(rule);
        Map<String, Operation> operations = operationsById(rule);

        Set<String> viaOperations = new TreeSet<>();
        for (Operation operation : operations.values())
        {
            String domain = trimToNull(operation.getDomain());
            // ⚠ `domain: "*"` is the every-dataset wildcard of cross_dataset_variable_metadata,
            // not a dataset name. It cannot be absent, so it is not a guardable surface at all.
            if (domain != null && !"*".equals(domain) && !own.contains(domain))
            {
                viaOperations.add(domain);
            }
        }
        if (viaOperations.isEmpty())
        {
            return List.of();
        }

        // A dataset whose columns the rule already names — through a dotted Check reference or a
        // Match_Datasets join — is column-addressable, so a guard IS writable for it and it is not
        // part of the expressiveness gap.
        Set<String> addressable = new TreeSet<>();
        List<MatchDataset> matches = rule.getMatchDatasets();
        if (matches != null)
        {
            for (MatchDataset match : matches)
            {
                String name = trimToNull(match.getName());
                if (name != null)
                {
                    addressable.add(name);
                }
            }
        }
        collectDottedPrefixes(rule.getCheck(), operations, addressable, false);

        viaOperations.removeAll(addressable);
        return List.copyOf(viaOperations);
    }

    // ------------------------------------------------------------------
    // The walk
    // ------------------------------------------------------------------

    /** Per-rule facts the walk needs at every leaf. */
    private record Context(String ruleId, Map<String, Operation> operations, Set<String> own,
            @Nullable Set<String> guardable)
    {
    }

    /**
     * @param positive
     *            polarity: {@code false} under an odd number of {@code not} nodes
     * @param conjunctive
     *            whether every combinator seen so far keeps the leaf implied by the whole check
     */
    private static void walk(CheckCondition node, boolean positive, boolean conjunctive,
            String path, Context ctx, List<PresenceLeaf> out)
    {
        switch (node)
        {
        case CheckConditionAll all ->
        {
            // `all` preserves implication in positive polarity; under a `not` it becomes an
            // `any` (De Morgan) and stops preserving it.
            boolean still = conjunctive && positive;
            for (CheckCondition child : all.getConditions())
            {
                walk(child, positive, still, join(path, "all"), ctx, out);
            }
        }
        case CheckConditionAny any ->
        {
            // Mirror image: `any` breaks implication in positive polarity and preserves it
            // under a `not`.
            boolean still = conjunctive && !positive;
            for (CheckCondition child : any.getConditions())
            {
                walk(child, positive, still, join(path, "any"), ctx, out);
            }
        }
        case CheckConditionNot not ->
        {
            CheckCondition child = not.getCondition();
            if (child != null)
            {
                walk(child, !positive, conjunctive, join(path, "not"), ctx, out);
            }
        }
        case CheckConditionLeaf leaf -> record(leaf, positive, conjunctive, path, ctx, out);
        case CheckConditionExpression expression -> walk(lowered(expression), positive, conjunctive,
                path, ctx, out);
        default ->
        {
            // A constant carries no structural leaf.
        }
        }
    }


    private static void record(CheckConditionLeaf leaf, boolean positive, boolean conjunctive,
            String path, Context ctx, List<PresenceLeaf> out)
    {
        String operator = leaf.getOperator();
        String name = leaf.getName();
        if (operator == null || name == null)
        {
            return;
        }
        String dataset;
        if (DATASET_PRESENCE.contains(operator))
        {
            dataset = trimToNull(name);
        }
        else if (COLUMN_PRESENCE.contains(operator))
        {
            dataset = datasetOf(name, ctx.operations());
        }
        else
        {
            return;
        }
        Set<String> guardable = ctx.guardable();
        if (dataset == null || ctx.own().contains(dataset)
                || (guardable != null && !guardable.contains(dataset)))
        {
            return;
        }
        Position position = conjunctive ? Position.GUARD : Position.DISJUNCT;
        out.add(new PresenceLeaf(ctx.ruleId(), dataset, operator, position, !positive, false,
                path.isEmpty() ? "leaf" : path));
    }

    // ------------------------------------------------------------------
    // Resolution helpers
    // ------------------------------------------------------------------


    /** The dataset a <b>column</b> name reads, or {@code null} when it is local. */
    private static @Nullable String datasetOf(String name, Map<String, Operation> operations)
    {
        if (name.startsWith("$"))
        {
            Operation operation = operations.get(name);
            return operation == null ? null : trimToNull(operation.getDomain());
        }
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : null;
    }


    /**
     * Every dataset whose <em>columns</em> the {@code Check} names with a dotted {@code D.COL}.
     *
     * @param skipPresenceLeaves
     *            when {@code true}, a presence leaf's own name does not count. &#9888; This is the
     *            difference between the two callers and it is load-bearing: for {@link #guardable}
     *            a presence leaf must not make its own dataset "read", or every dotted presence
     *            leaf would vouch for itself and the clause would be vacuous; for
     *            {@link #operationOnlyDatasets} it must count, because naming {@code D.COL} at all
     *            — presence or not — proves {@code D} is column-addressable.
     */
    private static void collectDottedPrefixes(@Nullable CheckCondition node,
            Map<String, Operation> operations, Set<String> into, boolean skipPresenceLeaves)
    {
        switch (node)
        {
        case null ->
        {
            // No Check at all.
        }
        case CheckConditionAll all -> all.getConditions()
                .forEach(c -> collectDottedPrefixes(c, operations, into, skipPresenceLeaves));
        case CheckConditionAny any -> any.getConditions()
                .forEach(c -> collectDottedPrefixes(c, operations, into, skipPresenceLeaves));
        case CheckConditionNot not -> collectDottedPrefixes(not.getCondition(), operations, into,
                skipPresenceLeaves);
        case CheckConditionLeaf leaf ->
        {
            String operator = leaf.getOperator();
            if (skipPresenceLeaves && operator != null
                    && (COLUMN_PRESENCE.contains(operator) || DATASET_PRESENCE.contains(operator)))
            {
                return;
            }
            addDotted(leaf.getName(), into);
            List<String> names = leaf.getNames();
            if (names != null)
            {
                names.forEach(n -> addDotted(n, into));
            }
        }
        case CheckConditionExpression expression -> collectDottedPrefixes(lowered(expression),
                operations, into, skipPresenceLeaves);
        default ->
        {
            // Constants carry no name.
        }
        }
    }


    private static void addDotted(@Nullable String name, Set<String> into)
    {
        if (name == null || name.startsWith("$"))
        {
            return;
        }
        int dot = name.indexOf('.');
        if (dot > 0)
        {
            into.add(name.substring(0, dot));
        }
    }


    private static String join(String path, String node)
    {
        return path.isEmpty() ? node : path + '/' + node;
    }


    private static @Nullable String trimToNull(@Nullable String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }


    /** {@code Core.Id}, falling back to the flat {@code id} and then to a placeholder. */
    static String ruleId(Rule rule)
    {
        if (rule.getCore() != null && rule.getCore().getId() != null)
        {
            return rule.getCore().getId();
        }
        return rule.getId() != null ? rule.getId() : "<no-id>";
    }


    /** {@code Operations} keyed by {@code $}-id; entries without an id are dropped. */
    private static Map<String, Operation> operationsById(Rule rule)
    {
        Map<String, Operation> byId = new LinkedHashMap<>();
        List<Operation> operations = rule.getOperations();
        if (operations != null)
        {
            for (Operation operation : operations)
            {
                if (operation.getId() != null)
                {
                    byId.put(operation.getId(), operation);
                }
            }
        }
        return byId;
    }


    /**
     * {@code Scope.Domains.Include}. &#9888; {@code Exclude}-only and class-scoped rules therefore
     * have an <b>empty</b> own-set, which is correct: nothing about such a rule pins a home
     * dataset, so every dotted reference in it really does cross a dataset boundary.
     */
    private static Set<String> ownDomains(Rule rule)
    {
        Scope scope = rule.getScope();
        DomainScope domains = scope == null ? null : scope.getDomains();
        List<String> include = domains == null ? null : domains.getInclude();
        return include == null ? Set.of() : Set.copyOf(include);
    }
}
