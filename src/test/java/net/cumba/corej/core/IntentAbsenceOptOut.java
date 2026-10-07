package net.cumba.corej.core;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import org.jspecify.annotations.Nullable;

/**
 * The <b>intent opt-out</b> predicate of {@code plans/done/PLAN-absent-required-dataset-skip.md} —
 * step 1 of OPT-OUT &rarr; COVERAGE &rarr; SKIP, and gate <b>K5c</b>.
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
 * <li><b>Top-level conjunction.</b> The Check expression is an {@code and} with <b>&ge; 2</b>
 * conjuncts.</li>
 * <li><b>Flat.</b> Every conjunct is a single emptiness probe — no nested {@code and}/{@code or}/
 * {@code not} branch, no composite target. A nested branch means the shape is no longer a plain
 * conjunction of availability probes. <b>One exception</b>, added with {@code M3-D6} /
 * {@code Fix #289}: the absent-column-tolerant emptiness spelling
 * {@code (not var_exists(X) or empty(X))} — a two-member disjunction addressing one name that
 * asserts exactly what {@code empty(X)} asserts; see {@link #absentTolerantEmptinessName}.</li>
 * <li><b>Every conjunct asserts emptiness</b>, in one of exactly two forms:
 * <ul>
 * <li>a <b>direct</b> emptiness probe — {@code empty(X)}; or</li>
 * <li>a <b>derived emptiness flag</b> — {@code $flag == true} on a {@code $}-id whose
 * {@code Operations} entry has {@code operator: variable_is_null}.</li>
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
 * conjunction every conjunct of which is an emptiness test, with at least one local and at least
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
 * first conjunct is {@code $species_null == true}, a <em>derived</em> nullity flag rather than an
 * emptiness operator.</li>
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
 * Run this over {@code rules-src/checks/**}, never over the shipped {@code rules/} packages: the
 * generator <b>injects an absent-column guard</b> from the variable requirement —
 * {@code CDISC-SEND-0105.1} ships as
 * {@code var_exists("SPECIES") and empty(SPECIES) and empty($ts_species) and empty($tx_species)},
 * whose first conjunct is not an emptiness assertion. A shipped-view implementation of this same
 * predicate therefore drops {@code -0105-1} and {@code -0106-1}. Step 3 must consume the id set
 * derived here rather than re-deriving it at runtime.
 * </p>
 */
public final class IntentAbsenceOptOut
{

    /**
     * The registry function that yields a <b>nullity boolean</b>, so that {@code $id == true} is an
     * emptiness assertion about its operand rather than an ordinary value comparison (runbook W2a
     * re-spelled the two {@code variable_is_null} operation sites as {@code var_is_empty(X)}
     * compiled bindings; the operation set went with the carrier in W8). Deliberately a closed,
     * tiny set: widening it silently widens the opt-out, and every id exempted here escapes the
     * SKIP semantics unadjudicated.
     */
    private static final Set<String> NULL_FLAG_FUNCTIONS = Set.of("var_is_empty");

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
        Expr check = CheckExprUnits.checkExpr(rule);
        if (!(check instanceof Expr.And and) || and.parts().size() < 2)
        {
            return null;
        }

        Set<String> own = ownDomains(rule);

        Set<String> foreign = new TreeSet<>();
        int local = 0;
        for (Expr conjunct : and.parts())
        {
            String name = absentTolerantEmptinessName(conjunct);
            if (name == null)
            {
                name = emptinessName(conjunct, rule);
            }
            if (name == null)
            {
                // Flatness: a nested and/or/not branch, or a non-emptiness probe, is a different
                // shape, not a conjunction of availability probes. The ONE exception is the
                // absent-tolerant emptiness spelling handled above.
                return null;
            }
            String dataset = datasetOf(name, rule);
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


    /** {@code Core.Id}, falling back to the flat {@code id} and then to a placeholder. */
    private static String ruleId(Rule rule)
    {
        if (rule.getCore() != null && rule.getCore().getId() != null)
        {
            return rule.getCore().getId();
        }
        return rule.getId() != null ? rule.getId() : "<no-id>";
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
     * — or its flat-negative twin {@code (var_not_exists(X) or empty(X))} — is a disjunction of
     * exactly two members, the negative-presence probe and the emptiness probe, <b>on the same
     * name</b>, and asserts precisely what a bare {@code empty(X)} asserts under the board's
     * absent-column doctrine (<i>absent column = all-missing column</i>), only without relying on
     * the guard the generator would otherwise inject.
     *
     * <p>
     * &#9888;&#9888; <b>Why this arm is not optional.</b> {@code CDISC-SEND-0105.1} and
     * {@code CDISC-SEND-0106.1} were authored in exactly this shape by {@code M3-D6} so that a DM
     * with no {@code SPECIES}/{@code STRAIN} column at all &mdash; the case their own sheet rows
     * lead with &mdash; is reported rather than skipped. Without this arm they fall out of the
     * opt-out purely because the conjunct is no longer a bare probe, and the SKIP semantics would
     * then silence the two rules on precisely the study that declares the species nowhere.
     * </p>
     *
     * <p>
     * The shape is deliberately exact: <b>two</b> members, <b>one</b> of each kind, and the
     * <b>same</b> name on both. A disjunction of two emptiness probes on <em>different</em> names
     * (<i>"SPECIES or STRAIN is empty"</i>) is a genuinely different requirement and is still
     * rejected &mdash; that is the clause-2 control in
     * {@code IntentAbsenceOptOutLintTest.everySingleClauseMutationOfTheShapeIsRejected}.
     * </p>
     *
     * @param conjunct
     *            one top-level conjunct
     * @return the name both members address, or {@code null} when the shape does not match
     */
    private static @Nullable String absentTolerantEmptinessName(Expr conjunct)
    {
        if (!(conjunct instanceof Expr.Or or) || or.parts().size() != 2)
        {
            return null;
        }
        String absentName = null;
        String emptyName = null;
        for (Expr member : or.parts())
        {
            String absent = absenceProbeName(member);
            if (absent != null)
            {
                absentName = absent;
            }
            else
            {
                String empty = directEmptyName(member);
                if (empty == null)
                {
                    return null;
                }
                emptyName = empty;
            }
        }
        return absentName != null && absentName.equals(emptyName) ? absentName : null;
    }


    /**
     * The name a negative-presence member probes: the flat {@code var_not_exists(X)} spelling or
     * the structural {@code not var_exists(X)} — both authorable, both accepted, exactly as both
     * leaf-era spellings were.
     */
    private static @Nullable String absenceProbeName(Expr member)
    {
        if (member instanceof Expr.Call c && "var_not_exists".equals(c.name()))
        {
            return singleArgName(c);
        }
        if (member instanceof Expr.Not not && not.inner() instanceof Expr.Call c
                && "var_exists".equals(c.name()))
        {
            return singleArgName(c);
        }
        return null;
    }


    /** Clause 3a — a direct {@code empty(X)} probe's name, or {@code null}. */
    private static @Nullable String directEmptyName(Expr node)
    {
        if (node instanceof Expr.Call c && "empty".equals(c.name()))
        {
            return singleArgName(c);
        }
        return null;
    }


    /**
     * Clause 3 — the name a conjunct asserts emptiness of: a direct {@code empty(X)}, or the
     * derived flag {@code $flag == true} where {@code $flag} is a {@code variable_is_null}
     * operation. {@code null} for every other shape.
     */
    private static @Nullable String emptinessName(Expr conjunct, Rule rule)
    {
        String direct = directEmptyName(conjunct);
        if (direct != null)
        {
            return direct;
        }
        // `$flag == true` — the equality must be against the literal boolean true; `== false`
        // inverts the polarity (asserts the variable is populated) and never matches.
        if (conjunct instanceof Expr.Binary b && b.op() == Expr.BinOp.EQ
                && b.left() instanceof Expr.Ref ref && ref.name().startsWith("$")
                && b.right() instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.BOOL
                && Boolean.TRUE.equals(lit.value()))
        {
            // PLAN-binding-expressions C4: a COMPILED flag answers through its expression — a
            // `$flag: empty(X)` binding read `$flag == true` is the emptiness probe of X.
            net.cumba.corej.core.model.CompiledBinding compiled = rule.compiledBinding(ref.name());
            if (compiled != null)
            {
                String compiledDirect = directEmptyName(compiled.expression());
                if (compiledDirect != null)
                {
                    return compiledDirect;
                }
                // Runbook W2a: `$flag: var_is_empty(X)` is the compiled form of the retired
                // variable_is_null operation (CDISC-SEND-0105 / -0106), the same nullity flag.
                if (compiled.expression() instanceof Expr.Call c
                        && NULL_FLAG_FUNCTIONS.contains(c.name()))
                {
                    return singleArgName(c);
                }
            }
        }
        return null;
    }


    /** The single argument's name: a reference, or the exists family's quoted string form. */
    private static @Nullable String singleArgName(Expr.Call c)
    {
        if (c.args().size() != 1 || !c.kwargs().isEmpty())
        {
            return null;
        }
        return switch (c.args().get(0))
        {
        case Expr.Ref r -> r.name();
        case Expr.Lit lit when lit.kind() == Expr.LitKind.STRING -> (String) lit.value();
        default -> null;
        };
    }


    /**
     * Clause 4's resolution step — the dataset a conjunct reads, or {@code null} for a local one.
     *
     * <p>
     * &#9888; The {@code $}-arm is the one the plan's description omitted, and it is the only arm
     * any of today's four matches uses.
     * </p>
     */
    private static @Nullable String datasetOf(String name, Rule rule)
    {
        if (name.startsWith("$") && rule.compiledBinding(name) != null)
        {
            // PLAN-binding-expressions C4: a COMPILED binding reads the dataset its expression
            // reads; one that reads none or several stays unresolved, as an unknown id always did.
            java.util.SequencedSet<String> read = CheckExprUnits.compiledBindingDatasets(rule, name,
                    true);
            return read.size() == 1 ? read.getFirst() : null;
        }
        if (name.startsWith("$"))
        {
            return null; // a `$`-name no compiled binding defines reads no dataset
        }
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : null;
    }
}
