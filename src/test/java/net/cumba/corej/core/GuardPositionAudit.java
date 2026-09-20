package net.cumba.corej.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.expr.ast.Expr;
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
 * to be seen as a guard."</em> A presence probe inside an {@code or} <b>cannot</b> guard, because a
 * sibling disjunct can still satisfy the branch; only a probe that the whole {@code Check} implies
 * &mdash; a conjunct &mdash; guards. Nothing in the engine enforces this, so a guard authored into
 * a disjunct is silently inert. {@code CORE-000269} / {@code CORE-000270} are the first measured
 * instances, and there the misplaced guard is <b>worse than inert</b>: on an absent {@code SV},
 * {@code var_not_exists(SV.SVPRESP)} is TRUE, so the disjunct is satisfied and the rule proceeds
 * into a vacuous {@code $tv_visit} membership test and floods.
 * </p>
 *
 * <h2>The predicate</h2>
 *
 * <p>
 * A <b>foreign presence unit</b> is a {@link CheckExprUnits.Unit} of the {@code Check} expression
 * whose operator is one of
 * </p>
 * <ul>
 * <li><b>column presence</b> &mdash; {@code var_exists}, {@code var_not_exists}; the target is a
 * column, so the dataset comes from a dotted {@code DOMAIN.VAR} prefix or, for a {@code $}-id, from
 * its {@code Operations} entry's {@code domain:}; or</li>
 * <li><b>dataset presence</b> &mdash; {@code ds_exists}, {@code ds_not_exists}; the target
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
 * Each such unit is placed in one of two {@linkplain Position positions} by {@link CheckExprUnits}'
 * polarity-aware walk from the {@code Check} root:
 * </p>
 * <ul>
 * <li>{@link Position#GUARD} &mdash; the whole {@code Check} implies the unit, so it really does
 * gate every other conjunct: <b>(a)</b>.</li>
 * <li>{@link Position#DISJUNCT} &mdash; anywhere else; a sibling branch can satisfy the check
 * without it: <b>(b)</b>, a candidate defect.</li>
 * </ul>
 *
 * <h2>&#9888; Why the walk is polarity-aware rather than a "direct child of the root {@code and}"
 * test</h2>
 *
 * <p>
 * Two shapes a naive positional test gets wrong, in opposite directions:
 * </p>
 * <ul>
 * <li><b>Nested conjunction.</b> {@code and} is associative, so a probe in {@code (G and X) and Y}
 * guards exactly as well as one in {@code G and X and Y}. A "direct child of the root" test
 * <b>false-positives</b> on it.</li>
 * <li><b>{@code not} is not {@code or}.</b> De Morgan: under an odd number of {@code not}s an
 * {@code or} becomes a conjunction and an {@code and} becomes a disjunction. So a probe in
 * {@code not (G or X) and Y} <b>is</b> implied by the check (in negated form) and does guard, while
 * one in {@code not (G and X)} does not. The walk therefore tracks negation parity and reports it
 * via {@link PresenceLeaf#negated()} rather than folding {@code not} into either bucket. &#9888;
 * The authored corpus contains <b>zero</b> structural {@code not}-over-presence shapes today, so
 * this arm is exercised only by the synthetics in {@code GuardPositionLintTest} &mdash; it is kept
 * because the alternative is an arm that rots silently and then mis-classifies the first rule
 * authored with one.</li>
 * </ul>
 *
 * <h2>&#9888;&#9888; Bucket (c) &mdash; the surface no <em>column</em> guard can reach</h2>
 *
 * <p>
 * A rule can reach a foreign dataset without ever naming one of its columns, through an
 * {@code Operations} entry's {@code domain:} &mdash; {@code $ta_epoch = distinct(EPOCH,
 * domain: "TA")}. {@code var_exists} takes a <b>column</b> name, so no column-presence guard is
 * writable for such a surface. Those rules are enumerated by {@link #operationOnlyDatasets(Rule)}
 * and are <b>never</b> flagged: they carry no presence unit at all, so they are structurally
 * outside the (a)/(b) population, and flagging them would put ~40 false positives on the lint's
 * first run.
 * </p>
 *
 * <p>
 * &#9888; <b>But "no guard is expressible" is too strong</b>, and this class is the place that
 * records why: {@code ds_exists} / {@code ds_not_exists} take a <b>plain dataset name</b>, and the
 * corpus already uses them that way 90 times &mdash; {@code CDISC-CG0107} ships
 * {@code $exvamtu_exists == true and ds_exists("EC")}. A <em>dataset-level</em> guard is therefore
 * writable for every bucket-(c) surface; what is missing is only a <em>column-level</em> one. The
 * count is reported so the owner can decide, with the number in hand, whether the gap is worth
 * closing.
 * </p>
 *
 * <h2>Mitigation &mdash; {@code Fix #246}</h2>
 *
 * <p>
 * The five CG0031/CG0033-family defects were closed by the <b>dotted-{@code exists} idiom</b> (plan
 * &sect;9a, re-taken 2026-08-13): a top-level conjunct {@code var_exists(TV.VISIT)} (resp.
 * {@code TV.VISITNUM}) &mdash; not {@code ds_exists}, which the corpus assembler rejects under a
 * non-presence {@code Rule_Type} (since {@code Fix #255} the engine itself no longer re-types such
 * a guard &mdash; shape B demotes to Record Data and executes &mdash; but the authoring-side
 * rejection stands, so the dotted idiom remains the authorable form), and not on {@code SV}, whose
 * absence is the Description's documented fallback rather than the flood. The misplaced {@code SV}
 * probe itself stays where it was authored, so it stays in bucket (b); what changes is that its
 * flood route is now structurally closed. {@link PresenceLeaf#mitigated()} derives exactly that
 * condition, cross-dataset and with <b>no exemption list</b>: every dataset whose vacuous
 * {@code $}-operation the {@code Check} tests with a {@linkplain #VACUOUS_ON_EMPTY_MEMBERSHIP
 * vacuous-on-empty} membership operator must carry a presence-implying unit in guard position.
 * </p>
 */
public final class GuardPositionAudit
{

    /** Presence operators whose target is a <b>column</b>. */
    private static final Set<String> COLUMN_PRESENCE = Set.of("var_exists", "var_not_exists");

    /** Presence operators whose target is a <b>dataset</b>. */
    private static final Set<String> DATASET_PRESENCE = Set.of("ds_exists", "ds_not_exists");

    /**
     * Presence operators that assert presence in their authored polarity ({@code var_exists} rather
     * than {@code var_not_exists}). Used by the {@linkplain PresenceLeaf#mitigated() mitigation}
     * derivation: only a unit that <em>implies presence</em> when the whole {@code Check} implies
     * it can suppress a vacuous-set flood.
     */
    private static final Set<String> POSITIVE_PRESENCE = Set.of("var_exists", "ds_exists");

    /**
     * Membership operators that are vacuously <b>true</b> against an empty {@code $}-set. This is
     * the flood mechanism of the CG0031/CG0033 family: {@code distinct(VISIT, domain: "TV")} over
     * an absent {@code TV} yields the empty set ({@code OperationExecutor}'s declared
     * {@code EmptyResult.SET}), and {@code x not in {}} is then true on <b>every</b> row. An
     * equality against an absent column is false and a {@code contains_all}-shaped containment
     * passes on an empty required set, so neither can flood this way &mdash; the set is
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
     * The dangerous-dataset collection is likewise polarity-blind by choice: a {@code not} above a
     * <em>modelled</em> unit only makes this derivation <em>under</em>-mitigate (the safe
     * direction), while the mirror shape &mdash; {@code not} over a positive containment, a real
     * flood &mdash; is unmodelled and behaves exactly like the cousins above. The corpus ships zero
     * such shapes, so both arms are synthetic-only today.
     */
    private static final Set<String> VACUOUS_ON_EMPTY_MEMBERSHIP = Set.of("is_not_contained_by");

    private GuardPositionAudit()
    {
    }

    /** Where a presence unit sits in the {@code Check} tree, after De Morgan. */
    public enum Position
    {
        /** The whole {@code Check} implies the unit &mdash; it really guards. Bucket (a). */
        GUARD,
        /**
         * A sibling branch can satisfy the check without it. Bucket (b) &mdash; candidate defect.
         */
        DISJUNCT
    }


    /**
     * One foreign presence unit and where it sits.
     *
     * @param ruleId
     *            {@code Core.Id} of the rule carrying the unit
     * @param dataset
     *            the foreign dataset the unit probes
     * @param operator
     *            the unit's presence operator, in the v1 vocabulary the reports pin
     * @param position
     *            {@link Position#GUARD} (bucket a) or {@link Position#DISJUNCT} (bucket b)
     * @param negated
     *            whether an odd number of structural {@code not} nodes sits between the root and
     *            the unit, so the unit's authored polarity is inverted in the check's meaning
     * @param mitigated
     *            {@code Fix #246}: for a {@link Position#DISJUNCT} unit only &mdash; whether the
     *            flood the misplaced guard could enable is <b>structurally suppressed</b>, because
     *            every dataset whose vacuous {@code $}-operation makes the disjunct dangerous (a
     *            {@linkplain #VACUOUS_ON_EMPTY_MEMBERSHIP vacuous-on-empty} membership test against
     *            a foreign {@code domain:}) carries a presence-<em>implying</em> guard in
     *            {@linkplain Position#GUARD guard position}. &#9888; Cross-dataset by design: on
     *            the CG0031/CG0033 family the flagged unit is on {@code SV} while the mitigating
     *            guard is {@code var_exists(TV.VISIT)} &mdash; the guard sits on the dataset that
     *            floods, not on the dataset the misplaced unit names. Derived structurally from the
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
     * Every foreign presence unit of one <b>authored</b> rule, in document order.
     *
     * @param rule
     *            a rule deserialised from {@code rules-src/checks/**}
     * @return the units; empty when the rule probes no foreign dataset's presence
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
     * @return every foreign presence unit, guardable or not
     */
    public static List<PresenceLeaf> presenceLeavesIgnoringReadsElsewhere(Rule rule)
    {
        return presenceLeaves(rule, false);
    }


    private static List<PresenceLeaf> presenceLeaves(Rule rule, boolean requireRead)
    {
        Expr check = CheckExprUnits.checkExpr(rule);
        if (check == null)
        {
            return List.of();
        }
        List<CheckExprUnits.Unit> units = CheckExprUnits.units(check);
        Map<String, Operation> operations = operationsById(rule);
        Set<String> own = ownDomains(rule);
        Set<String> guardable = requireRead ? guardable(rule, operations, units) : null;
        List<PresenceLeaf> out = new ArrayList<>();
        String ruleId = ruleId(rule);
        for (CheckExprUnits.Unit unit : units)
        {
            record(unit, ruleId, operations, own, guardable, out);
        }
        return mitigate(units, operations, own, out);
    }


    private static void record(CheckExprUnits.Unit unit, String ruleId,
            Map<String, Operation> operations, Set<String> own, @Nullable Set<String> guardable,
            List<PresenceLeaf> out)
    {
        String operator = unit.operator();
        String name = unit.name();
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
            dataset = datasetOf(name, operations);
        }
        else
        {
            return;
        }
        if (dataset == null || own.contains(dataset)
                || (guardable != null && !guardable.contains(dataset)))
        {
            return;
        }
        Position position = unit.conjunctive() ? Position.GUARD : Position.DISJUNCT;
        out.add(new PresenceLeaf(ruleId, dataset, operator, position, unit.negated(), false,
                unit.path().isEmpty() ? "leaf" : unit.path()));
    }


    /**
     * The {@code Fix #246} mitigation pass: stamps {@link PresenceLeaf#mitigated()} on the
     * {@link Position#DISJUNCT} units of one rule.
     *
     * <p>
     * A misplaced guard's danger is not the unit itself &mdash; it is that the disjunct passes
     * vacuously (its dataset absent &#8658; {@code var_not_exists} true) and evaluation then
     * proceeds into a {@linkplain #VACUOUS_ON_EMPTY_MEMBERSHIP vacuous-on-empty} membership test
     * whose {@code $}-operation reads a foreign {@code domain:} that may itself be absent. The
     * flood is cut by guarding <em>that</em> dataset: when every such dangerous dataset carries a
     * presence-implying unit in {@linkplain Position#GUARD guard position}, the whole {@code Check}
     * implies the dataset is present and the vacuous-set route is closed &mdash; regardless of the
     * dataset the misplaced unit itself names.
     * </p>
     */
    private static List<PresenceLeaf> mitigate(List<CheckExprUnits.Unit> units,
            Map<String, Operation> operations, Set<String> own, List<PresenceLeaf> leaves)
    {
        Set<String> dangerous = dangerousDatasets(units, operations, own);
        if (dangerous.isEmpty())
        {
            return List.copyOf(leaves);
        }
        Set<String> guarded = new TreeSet<>();
        for (PresenceLeaf leaf : leaves)
        {
            // A unit implies presence when the check implies it (GUARD) and its effective
            // polarity is positive: an authored `var_exists`, or a `var_not_exists` under an odd
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
     * {@code domain:} of a {@code $}-operation used as the value of a
     * {@linkplain #VACUOUS_ON_EMPTY_MEMBERSHIP vacuous-on-empty} membership unit. An absent dataset
     * there yields an empty set and the membership test fires on every row.
     */
    private static Set<String> dangerousDatasets(List<CheckExprUnits.Unit> units,
            Map<String, Operation> operations, Set<String> own)
    {
        Set<String> dangerous = new TreeSet<>();
        for (CheckExprUnits.Unit unit : units)
        {
            if (unit.operator() == null || !VACUOUS_ON_EMPTY_MEMBERSHIP.contains(unit.operator()))
            {
                continue;
            }
            for (String ref : valueRefs(unit.value()))
            {
                if (!ref.startsWith("$"))
                {
                    continue;
                }
                Operation operation = operations.get(ref);
                String domain = operation == null ? null : trimToNull(operation.getDomain());
                if (domain != null && !"*".equals(domain) && !own.contains(domain))
                {
                    dangerous.add(domain);
                }
            }
        }
        return dangerous;
    }


    /**
     * The textual references of a membership unit's value side: a single reference, a string
     * literal (the array-membership encoding keeps {@code $}-refs as strings), or a list of either.
     */
    private static List<String> valueRefs(@Nullable Expr value)
    {
        List<String> refs = new ArrayList<>();
        collectValueRefs(value, refs);
        return refs;
    }


    private static void collectValueRefs(@Nullable Expr value, List<String> into)
    {
        switch (value)
        {
        case null ->
        {
            // No value side at all.
        }
        case Expr.Ref r -> into.add(r.name());
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.STRING)
            {
                into.add((String) lit.value());
            }
            else if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof Expr e)
                    {
                        collectValueRefs(e, into);
                    }
                }
            }
        }
        default ->
        {
            // Calls (accessors) carry no textual $-ref.
        }
        }
    }


    /**
     * The datasets this rule <b>reads</b>, i.e. the ones a guard could have something to protect: a
     * {@code Match_Datasets} join, a dotted {@code D.COL} reference in target position, or an
     * {@code Operations} entry's {@code domain:}.
     *
     * <h4>&#9888;&#9888; Why the population is narrowed by this, and not only by position</h4>
     *
     * <p>
     * A presence unit on a dataset the rule never otherwise touches is not a misplaced guard
     * &mdash; it is <b>the rule's own assertion</b>. {@code CDISC-SEND-0400} was
     * {@code ds_not_exists("TT") or ds_not_exists("TP") or ds_not_exists("SJ")} and its whole
     * purpose is to report a missing DART domain; every one of its three probes sat under a
     * top-level disjunction and none of them was a guard in any sense (since the 2026-09-12 R-5.25
     * wave-3 split each probe is the whole {@code Check} of its own rule, still no guard). Position
     * alone puts all three in bucket (b) &mdash; <b>3 false positives out of 9</b>, measured
     * 2026-08-12 before this clause existed. A guard protects a <em>read</em>, so requiring the
     * read is what separates the two.
     * </p>
     */
    private static Set<String> guardable(Rule rule, Map<String, Operation> operations,
            List<CheckExprUnits.Unit> units)
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
        collectDottedPrefixes(units, read, true);
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

        // A dataset whose columns the rule already names — through a dotted target reference or a
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
        Expr check = CheckExprUnits.checkExpr(rule);
        if (check != null)
        {
            collectDottedPrefixes(CheckExprUnits.units(check), addressable, false);
        }

        viaOperations.removeAll(addressable);
        return List.copyOf(viaOperations);
    }


    /**
     * Every dataset whose <em>columns</em> the {@code Check} names with a dotted {@code D.COL} in
     * target position. ⚠ Target position only, exactly as the v1 leaf's {@code name}/{@code names}
     * slots were the only ones read: a dotted reference on the <em>value</em> side of a comparison
     * never counted as a read, and the pinned populations depend on that asymmetry.
     *
     * @param skipPresenceLeaves
     *            when {@code true}, a presence unit's own target does not count. &#9888; This is
     *            the difference between the two callers and it is load-bearing: for
     *            {@link #guardable} a presence unit must not make its own dataset "read", or every
     *            dotted presence probe would vouch for itself and the clause would be vacuous; for
     *            {@link #operationOnlyDatasets} it must count, because naming {@code D.COL} at all
     *            — presence or not — proves {@code D} is column-addressable.
     */
    private static void collectDottedPrefixes(List<CheckExprUnits.Unit> units, Set<String> into,
            boolean skipPresenceLeaves)
    {
        for (CheckExprUnits.Unit unit : units)
        {
            String operator = unit.operator();
            if (skipPresenceLeaves && operator != null
                    && (COLUMN_PRESENCE.contains(operator) || DATASET_PRESENCE.contains(operator)))
            {
                continue;
            }
            addDotted(unit.name(), into);
            for (String n : unit.names())
            {
                addDotted(n, into);
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
