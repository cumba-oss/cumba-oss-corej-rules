package net.cumba.corej.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.expr.convert.DualFormCheckNode;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.CheckConditionNot;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import org.jspecify.annotations.Nullable;

/**
 * The <b>intent opt-out</b> predicate of {@code plans/PLAN-absent-required-dataset-skip.md} — step
 * 1 of OPT-OUT &rarr; COVERAGE &rarr; SKIP, and gate <b>K5c</b>.
 *
 * <p>
 * <b>What it identifies.</b> A rule whose Check declares <em>"this datum must exist somewhere"</em>
 * — a <b>disjunctive-availability</b> requirement, where a named foreign dataset is one of the
 * <em>disjuncts being tested</em> rather than a precondition of the test. For such a rule the
 * foreign dataset's absence is <b>intent, not a precondition</b>: it is exactly the case the rule
 * exists to catch, so the SKIP semantics of &sect;5 must never apply to it. Silencing one of these
 * destroys a finding nothing else in the run carries — a dataset-presence rule reports <em>"TS is
 * missing"</em>, which is a different (and less useful) fact than <em>"the species of the study
 * animals is unknown"</em>.
 * </p>
 *
 * <h2>The predicate</h2>
 *
 * <p>
 * A rule matches iff <b>all</b> of:
 * </p>
 * <ol>
 * <li><b>Top-level {@code all}.</b> {@code Check} is a {@link CheckConditionAll} with <b>&ge; 2</b>
 * conditions.</li>
 * <li><b>Flat.</b> Every condition is a {@link CheckConditionLeaf} with a non-null {@code name} —
 * no nested {@code all}/{@code any}/{@code not}, no composite {@code names:} target. A nested
 * branch means the shape is no longer a plain conjunction of availability probes. <b>One
 * exception</b>, added with {@code M3-D6} / {@code Fix #289}: the absent-column-tolerant emptiness
 * spelling {@code (not var_exists(X) or empty(X))}, which lowers to a two-member {@code any}
 * addressing one name and asserts exactly what {@code empty(X)} asserts — see
 * {@link #absentTolerantEmptinessName(CheckCondition)}.</li>
 * <li><b>Every conjunct asserts emptiness</b>, in one of exactly two forms:
 * <ul>
 * <li>a <b>direct</b> emptiness leaf — {@code operator: empty}; or</li>
 * <li>a <b>derived emptiness flag</b> — {@code operator: equal_to} with a literal boolean
 * {@code true} value, on a {@code $}-id whose {@code Operations} entry has
 * {@code operator: variable_is_null}.</li>
 * </ul>
 * </li>
 * <li><b>At least one conjunct is local and at least one is foreign</b>, where a conjunct's dataset
 * is resolved as: a {@code $}-id &rarr; <b>its {@code Operations} entry's {@code domain:}</b>
 * (absent/blank &rArr; local); a dotted {@code DOMAIN.VAR} &rarr; the prefix; anything else &rArr;
 * local. A conjunct is <b>foreign</b> iff its dataset is resolved and is not in the rule's own
 * {@code Scope.Domains.Include}.</li>
 * </ol>
 *
 * <h2>&#9888;&#9888; Two things the plan's own prose got wrong, and why they matter</h2>
 *
 * <p>
 * {@code PLAN-absent-required-dataset-skip.md} &sect;0.7 described this class as <em>"a top-level
 * {@code all} every conjunct of which is an emptiness test, with at least one local and at least
 * one foreign conjunct"</em>, and asserted it returns the four {@code CDISC-SEND-0105/-0105-1/
 * -0106/-0106-1} rules. <b>Implemented literally it returns none of them</b> (re-measured
 * 2026-08-10, {@code Fix #207}):
 * </p>
 * <ul>
 * <li><b>"Foreign" is not a dotted reference.</b> These rules reach TS/TX through
 * {@code $}-operation results ({@code $ts_species}) whose {@code Operations} entry carries
 * {@code domain: "TS"} — never through a {@code DM.RFSTDTC}-style dotted name. A sweep keyed on
 * <em>"the name contains a dot"</em> finds 21 rules and <b>not one of the four</b>. Resolving the
 * {@code $}-id back to its operation is the load-bearing step, and the description never mentions
 * it.</li>
 * <li><b>"Every conjunct is an emptiness test" is false for {@code -0105}/{@code -0106}</b> — their
 * first conjunct is {@code $species_null equal_to true}, a <em>derived</em> nullity flag rather
 * than an emptiness operator.</li>
 * </ul>
 *
 * <p>
 * The cost of getting it wrong is not a missing exemption but a <b>silent over-exemption</b>: the
 * naive readings would exempt 8 or 21 rules from SKIP — rules nobody adjudicated — instead of 4.
 * The three refuted readings are kept as live controls in {@link IntentAbsenceOptOutLintTest}.
 * </p>
 *
 * <h2>&#9888; This is an assertion about the AUTHORED view only</h2>
 *
 * <p>
 * Run this over {@code rules-src/checks/**}, never over the shipped {@code rules/} packages. The
 * shipped form lowers {@code Check} to an <b>expression string</b>
 * ({@code "$species_null == true and empty($ts_species)"}) that no structural walker sees, and the
 * generator additionally <b>injects an absent-column guard</b> from the variable requirement —
 * {@code CDISC-SEND-0105.1} ships as
 * {@code var_exists("SPECIES") and empty(SPECIES) and empty($ts_species) and empty($tx_species)},
 * whose first conjunct is not an emptiness assertion. A shipped-view implementation of this same
 * predicate therefore drops {@code -0105-1} and {@code -0106-1}. Step 3 must consume the id set
 * derived here rather than re-deriving it at runtime.
 * </p>
 */
public final class IntentAbsenceOptOut
{

    /** The one direct emptiness leaf operator in the shape. */
    private static final String EMPTY = "empty";

    /** The leaf operator carrying a derived nullity flag ({@code $flag equal_to true}). */
    private static final String EQUAL_TO = "equal_to";

    /**
     * {@code Operations} operators that yield a <b>nullity boolean</b>, so that
     * {@code $id equal_to true} is an emptiness assertion about the operation's operand rather than
     * an ordinary value comparison. Deliberately a closed, tiny set: widening it silently widens
     * the opt-out, and every id exempted here escapes the SKIP semantics unadjudicated. Only
     * {@code variable_is_null} qualifies today (2 occurrences corpus-wide).
     */
    private static final Set<String> NULL_FLAG_OPERATIONS = Set.of("variable_is_null");

    /**
     * The flat negative-presence leaves the writer produces for {@code not var_exists(X)} /
     * {@code not exists(X)} &mdash; see {@code DualFormCheckNode.NEGATIVE_BY_POSITIVE_EXISTS}. Used
     * only inside {@link #absentTolerantEmptinessName(CheckCondition)}; a bare negative presence
     * leaf on its own is <b>not</b> an emptiness assertion and never matches.
     */
    private static final Set<String> ABSENT_LEAVES = Set.of("var_not_exists");

    /**
     * The positive presence leaves, recognised under a structural {@code not}. &#9888; The strict
     * load-path lowering keeps {@code not var_exists(X)} as {@code {not: {operator: var_exists}}}
     * &mdash; only the conversion-mode lowering folds it to the flat negative leaf &mdash; so both
     * spellings reach {@link #absentTolerantEmptinessName(CheckCondition)} and both must be
     * accepted. Measured on {@code rules-src/checks/CDISC/CDISC-SEND-0105.1.yaml}, 2026-08-21.
     */
    private static final Set<String> PRESENT_LEAVES = Set.of("var_exists");

    private IntentAbsenceOptOut()
    {
    }

    /**
     * A matching rule and the foreign datasets its disjunctive-availability shape names. SKIP must
     * not be applied to {@link #ruleId()} for any dataset in {@link #foreignDatasets()}.
     *
     * @param ruleId
     *            {@code Core.Id} of the matching rule
     * @param foreignDatasets
     *            the foreign datasets named by the conjuncts, sorted and de-duplicated
     */
    public record Match(String ruleId, List<String> foreignDatasets)
    {
    }

    /**
     * Applies the predicate to one <b>authored</b> rule.
     *
     * @param rule
     *            a rule deserialised from {@code rules-src/checks/**}
     * @return the match, or {@code null} when the rule is not a disjunctive-availability rule
     */
    public static @Nullable Match classify(Rule rule)
    {
        if (!(structural(rule.getCheck()) instanceof CheckConditionAll all))
        {
            return null;
        }
        List<CheckCondition> conditions = all.getConditions();
        if (conditions.size() < 2)
        {
            return null;
        }

        Map<String, Operation> operations = operationsById(rule);
        Set<String> own = ownDomains(rule);

        Set<String> foreign = new TreeSet<>();
        int local = 0;
        for (CheckCondition condition : conditions)
        {
            CheckCondition node = structural(condition);
            String name = absentTolerantEmptinessName(node);
            if (name == null)
            {
                // Flatness: a nested all/any/not is a different shape, not a conjunction of
                // probes. The ONE exception is the absent-tolerant emptiness spelling handled
                // above.
                if (!(node instanceof CheckConditionLeaf leaf))
                {
                    return null;
                }
                name = leaf.getName();
                if (name == null || !assertsEmptiness(leaf, operations))
                {
                    return null;
                }
            }
            String dataset = datasetOf(name, operations);
            if (dataset != null && !own.contains(dataset))
            {
                foreign.add(dataset);
            }
            else
            {
                local++;
            }
        }

        if (local == 0 || foreign.isEmpty())
        {
            return null;
        }
        return new Match(ruleId(rule), List.copyOf(foreign));
    }


    /**
     * The structural (operator-leaf) view of one node — {@code Fix #256}, the tolerant/explicit
     * expression arm of {@code plans/done/PLAN-rules-src-expression-flip.md} §7b. An
     * {@code {expression}} Check that the strict load-path lowering declines arrives as a
     * {@link CheckConditionExpression}; lowering it here through the engine's conversion-mode
     * machinery makes the predicate read identically across both authoring forms instead of
     * silently failing the {@code instanceof} shape tests. A native-only expression (no
     * operator-leaf surface even in conversion mode) fails LOUDLY — a silent {@code null} would
     * drop the rule out of the opt-out, and the SKIP semantics would then silence exactly the
     * finding the rule exists to carry.
     */
    private static @Nullable CheckCondition structural(@Nullable CheckCondition node)
    {
        if (!(node instanceof CheckConditionExpression expression))
        {
            return node;
        }
        CheckCondition lowered = DualFormCheckNode.lowerConditionOrNull(expression.expr());
        if (lowered == null)
        {
            // Retry with num() conversions erased — a num(X) read is transparent to this
            // instrument's classification; see DualFormCheckNode.stripNumConversions.
            lowered = DualFormCheckNode
                    .lowerConditionOrNull(DualFormCheckNode.stripNumConversions(expression.expr()));
        }
        if (lowered != null)
        {
            return lowered;
        }
        // A native-only expression. The opt-out predicate keys on disjunctive-availability
        // shapes built from exists-family leaves; when the expression provably carries no such
        // call, it cannot be an intent-absence carrier and the original node classifies as
        // not-matching. One that DOES carry a presence call still fails loudly — a silent
        // fall-through would drop the rule out of the opt-out and silence its finding
        // (plans/done/PLAN-rules-src-expression-flip.md §7b).
        if (!DualFormCheckNode.carriesPresenceCall(expression.expr()))
        {
            return node;
        }
        throw new IllegalStateException("IntentAbsenceOptOut cannot classify the"
                + " expression-form Check \"" + expression.source() + "\": it has no"
                + " operator-leaf equivalent even in conversion mode, and it carries a presence"
                + " call. Add a native Expr arm to the predicate instead of letting the rule fall"
                + " out of the opt-out (plans/done/PLAN-rules-src-expression-flip.md §7b).");
    }


    /** {@code Core.Id}, falling back to the flat {@code id} and then to a placeholder. */
    private static String ruleId(Rule rule)
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


    /** {@code Scope.Domains.Include}, or the empty set when the rule declares no domain scope. */
    private static Set<String> ownDomains(Rule rule)
    {
        Scope scope = rule.getScope();
        DomainScope domains = scope == null ? null : scope.getDomains();
        List<String> include = domains == null ? null : domains.getInclude();
        return include == null ? Set.of() : Set.copyOf(include);
    }


    /**
     * <b>Clause 2's one exception, and clause 3's third form</b> ({@code M3-D6} / {@code Fix
     * #289}). The absent-column-tolerant emptiness spelling {@code (not var_exists(X) or empty(X))}
     * lowers to an {@code any} of exactly two flat leaves &mdash; the negative-presence leaf and
     * the emptiness leaf, <b>on the same name</b> &mdash; and asserts precisely what a bare
     * {@code empty(X)} leaf asserts under the board's absent-column doctrine (<i>absent column =
     * all-missing column</i>), only without relying on the guard the generator would otherwise
     * inject.
     *
     * <p>
     * &#9888;&#9888; <b>Why this arm is not optional.</b> {@code CDISC-SEND-0105.1} and
     * {@code CDISC-SEND-0106.1} were authored in exactly this shape by {@code M3-D6} so that a DM
     * with no {@code SPECIES}/{@code STRAIN} column at all &mdash; the case their own sheet rows
     * lead with &mdash; is reported rather than skipped. Without this arm they fall out of the
     * opt-out purely because the conjunct is no longer a bare leaf, and the SKIP semantics would
     * then silence the two rules on precisely the study that declares the species nowhere.
     * </p>
     *
     * <p>
     * The shape is deliberately exact: <b>two</b> members, <b>one</b> of each kind, and the
     * <b>same</b> name on both. An {@code any} of two emptiness leaves on <em>different</em> names
     * (<i>"SPECIES or STRAIN is empty"</i>) is a genuinely different requirement and is still
     * rejected &mdash; that is the clause-2 control in
     * {@code IntentAbsenceOptOutLintTest.everySingleClauseMutationOfTheShapeIsRejected}.
     * </p>
     *
     * @param condition
     *            one top-level conjunct, already resolved through {@link #structural}
     * @return the name both members address, or {@code null} when the shape does not match
     */
    private static @Nullable String absentTolerantEmptinessName(@Nullable CheckCondition condition)
    {
        if (!(condition instanceof CheckConditionAny any) || any.getConditions().size() != 2)
        {
            return null;
        }
        String absentName = null;
        String emptyName = null;
        for (CheckCondition member : any.getConditions())
        {
            CheckCondition node = structural(member);
            if (node instanceof CheckConditionNot negated
                    && structural(negated.getCondition()) instanceof CheckConditionLeaf inner
                    && inner.getName() != null && PRESENT_LEAVES.contains(inner.getOperator()))
            {
                absentName = inner.getName();
                continue;
            }
            if (!(node instanceof CheckConditionLeaf leaf) || leaf.getName() == null)
            {
                return null;
            }
            if (ABSENT_LEAVES.contains(leaf.getOperator()))
            {
                absentName = leaf.getName();
            }
            else if (EMPTY.equals(leaf.getOperator()))
            {
                emptyName = leaf.getName();
            }
            else
            {
                return null;
            }
        }
        return absentName != null && absentName.equals(emptyName) ? absentName : null;
    }


    /** Clause 3 — the conjunct asserts that the thing it names is empty. */
    private static boolean assertsEmptiness(CheckConditionLeaf leaf, Map<String, Operation> ops)
    {
        return EMPTY.equals(leaf.getOperator()) || isDerivedNullFlag(leaf, ops);
    }


    /** {@code $flag equal_to true} where {@code $flag} is a {@code variable_is_null} operation. */
    private static boolean isDerivedNullFlag(CheckConditionLeaf leaf, Map<String, Operation> ops)
    {
        if (!EQUAL_TO.equals(leaf.getOperator()))
        {
            return false;
        }
        JsonNode value = leaf.getValue();
        if (value == null || !value.isBoolean() || !value.booleanValue())
        {
            return false;
        }
        String name = leaf.getName();
        if (name == null || !name.startsWith("$"))
        {
            return false;
        }
        Operation operation = ops.get(name);
        return operation != null && NULL_FLAG_OPERATIONS.contains(operation.getOperator());
    }


    /**
     * Clause 4's resolution step — the dataset a conjunct reads, or {@code null} for a local one.
     *
     * <p>
     * &#9888; The {@code $}-arm is the one the plan's description omitted, and it is the only arm
     * any of today's four matches uses.
     * </p>
     */
    private static @Nullable String datasetOf(String name, Map<String, Operation> ops)
    {
        if (name.startsWith("$"))
        {
            Operation operation = ops.get(name);
            String domain = operation == null ? null : operation.getDomain();
            if (domain == null)
            {
                return null;
            }
            String trimmed = domain.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : null;
    }
}
