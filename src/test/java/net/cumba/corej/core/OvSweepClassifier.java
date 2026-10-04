package net.cumba.corej.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.exec.BindingScope;
import net.cumba.corej.core.exec.OutputVariableDeriver;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.OutputVariableToken;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * The one classification of a rule's authored {@code Output_Variables} entries behind
 * {@code PLAN-redundant-output-variables-sweep} (Q1, Q2 (a), Q4): which authored include entries
 * the load-time derivation ({@link OutputVariableDeriver}) produces anyway, which of those may be
 * stripped, which must stay as the Q2 residual, and what the effective list is before and after the
 * strip.
 *
 * <h2>Computed through the deriver, never a reimplementation</h2>
 * <p>
 * Every set here comes from {@link OutputVariableDeriver#derivedSet} and
 * {@link OutputVariableDeriver#derive} on the <em>loaded</em> rule — the same calls
 * {@code RulePackageLoader.deriveOutputVariables} makes at load. The prediction of the effective
 * list after the strip is {@code derive()} itself, run over the rule with the stripped authored
 * list installed (and restored afterwards): the deriver never reads anything else of the authored
 * list, so this is the loader's own answer, not a model of it.
 * </p>
 *
 * <h2>Per-variable = the deriver's test ∪ the runtime {@link BindingScope}</h2>
 * <p>
 * On the per-variable projection paths ({@code RuleRunner.evaluateMetadataNative(perVariable)} and
 * {@code evaluateVariableValueNative}) a <em>derived</em> entry that resolves to nothing is omitted
 * while an <em>authored</em> one keeps a {@code null}-valued key
 * ({@code RuleRunner.putUnlessDerivedNull}). Stripping an entry there flips the key, so under Q2
 * (a) only an entry that can never resolve to null — {@code variable_name}, which both paths write
 * unconditionally — is stripped on such a rule; every other derived authored entry is the residual
 * (the committed allowance of {@code RedundantOutputVariableLintTest}, owned and retired by
 * {@code PLAN-output-variable-null-key-provenance}). A rule counts as per-variable when
 * <b>either</b> the deriver's own test fires (its D7 marker: {@code variable_name} is in
 * {@link OutputVariableDeriver#derivedSet}, which happens exactly on the deriver's per-variable /
 * VLM path) <b>or</b> the runtime dispatch {@link BindingScope#of} would route any declared level
 * to {@code PER_VARIABLE} / {@code PER_VARIABLE_ROW}. The union fails safe: should the two
 * predicates ever disagree, the rule is treated as per-variable and nothing but
 * {@code variable_name} is stripped.
 * </p>
 */
public final class OvSweepClassifier
{

    /** The one entry Q2 (a) strips on a per-variable rule — never {@code null} on either path. */
    public static final String VARIABLE_NAME = "variable_name";

    private OvSweepClassifier()
    {
    }

    /**
     * One rule's classification.
     *
     * @param coreId
     *            the rule id
     * @param perVariable
     *            whether the rule takes a per-variable projection path (deriver ∪ runtime)
     * @param authored
     *            the authored include entries, verbatim and in order ({@code !X} stripped)
     * @param exclusions
     *            the authored {@code !X} names, in order
     * @param derived
     *            {@link OutputVariableDeriver#derivedSet} — what the derivation adds
     * @param strip
     *            the authored entries to remove (authored ∩ derived, minus the residual)
     * @param residual
     *            the authored ∩ derived entries kept on a per-variable rule (Q2 (a))
     * @param before
     *            the effective list as loaded ({@link OutputVariableDeriver#derive})
     * @param after
     *            the effective list the deriver produces over the stripped authored list
     */
    public record Verdict(String coreId, boolean perVariable, List<String> authored,
            List<String> exclusions, Set<String> derived, List<String> strip, List<String> residual,
            List<String> before, List<String> after)
    {

        /** The authored include entries that remain after the strip, in order. */
        public List<String> authoredAfter()
        {
            List<String> out = new ArrayList<>(authored);
            out.removeAll(strip);
            return out;
        }


        /**
         * The full authored list after the strip — remaining includes and {@code !X} tokens in
         * their original relative order; empty when the key is deleted (Q5).
         */
        public List<String> authoredListAfter(List<String> rawAuthored)
        {
            List<String> out = new ArrayList<>();
            for (String entry : rawAuthored)
            {
                if (OutputVariableToken.isExclusion(entry) || !strip.contains(entry))
                {
                    out.add(entry);
                }
            }
            return out;
        }


        /** Q5: nothing authored remains (no include, no exclusion) — the key is deleted. */
        public boolean keyDeleted()
        {
            return !strip.isEmpty() && authoredAfter().isEmpty() && exclusions.isEmpty();
        }


        /** Q5 corollary: only {@code !X} tokens remain. */
        public boolean keepsOnlyExclusions()
        {
            return !strip.isEmpty() && authoredAfter().isEmpty() && !exclusions.isEmpty();
        }


        /** Q1: the strip moves the effective order (members unchanged by construction). */
        public boolean orderMoves()
        {
            return !strip.isEmpty() && !before.equals(after);
        }


        /** Whether the first reported column changes. */
        public boolean firstColumnMoves()
        {
            return orderMoves() && !before.isEmpty() && !after.isEmpty()
                    && !before.get(0).equals(after.get(0));
        }
    }

    /**
     * Classifies {@code rule} as loaded. The rule's authored list is mutated only for the duration
     * of the prediction and restored byte-identically; the loaded effective list is left alone.
     */
    public static Verdict classify(Rule rule)
    {
        String coreId = rule.getCore() != null && rule.getCore().getId() != null
                ? rule.getCore().getId()
                : String.valueOf(rule.getId());
        Outcome outcome = rule.getOutcome();
        List<String> raw = outcome == null || outcome.getOutputVariables() == null ? List.of()
                : List.copyOf(outcome.getOutputVariables());
        List<String> authored = OutputVariableToken.includes(raw);
        List<String> exclusions = new ArrayList<>(OutputVariableToken.exclusions(raw));
        Set<String> derived = new LinkedHashSet<>(OutputVariableDeriver.derivedSet(rule));
        boolean perVariable = perVariable(rule, derived);

        List<String> strip = new ArrayList<>();
        List<String> residual = new ArrayList<>();
        for (String entry : authored)
        {
            if (!derived.contains(entry))
            {
                continue;
            }
            if (perVariable && !VARIABLE_NAME.equals(entry))
            {
                residual.add(entry);
            }
            else
            {
                strip.add(entry);
            }
        }
        List<String> before = OutputVariableDeriver.derive(rule);
        List<String> after = before;
        if (!strip.isEmpty())
        {
            List<String> stripped = new ArrayList<>();
            for (String entry : raw)
            {
                if (OutputVariableToken.isExclusion(entry) || !strip.contains(entry))
                {
                    stripped.add(entry);
                }
            }
            after = predict(rule, stripped.isEmpty() ? null : stripped);
        }
        return new Verdict(coreId, perVariable, List.copyOf(authored), List.copyOf(exclusions),
                Set.copyOf(derived), List.copyOf(strip), List.copyOf(residual), before, after);
    }


    /**
     * {@link OutputVariableDeriver#derive} over {@code rule} with {@code authoredList} installed as
     * its authored {@code Output_Variables} ({@code null} = key absent), the original restored
     * afterwards. Single-threaded by contract: the caller owns the rule.
     */
    public static List<String> predict(Rule rule, @Nullable List<String> authoredList)
    {
        Outcome outcome = rule.getOutcome();
        if (outcome == null)
        {
            // No Outcome at all: nothing authored, nothing to strip, derive() is the answer.
            return OutputVariableDeriver.derive(rule);
        }
        List<String> original = outcome.getOutputVariables();
        outcome.setOutputVariables(authoredList);
        try
        {
            return OutputVariableDeriver.derive(rule);
        }
        finally
        {
            outcome.setOutputVariables(original);
        }
    }


    /** The per-variable predicate: the deriver's D7 marker ∪ the runtime {@link BindingScope}. */
    public static boolean perVariable(Rule rule)
    {
        return perVariable(rule, OutputVariableDeriver.derivedSet(rule));
    }


    private static boolean perVariable(Rule rule, Set<String> derived)
    {
        return derived.contains(VARIABLE_NAME) || runtimePerVariable(rule);
    }


    /**
     * Whether {@link BindingScope#of} routes any declared check level of {@code rule} to a
     * per-variable loop — the runtime half of the union, decided on the cached evaluation domain
     * (the join over the levels, which {@code RulePackageLoader.installCompiledLevels} installs on
     * every rule whose Check compiled) exactly as {@code RuleRunner.domainOf} reads it.
     *
     * @throws IllegalStateException
     *             when the rule carries a compiled Check but no cached domain — a loader shape this
     *             classifier does not know, and must not silently classify as row-path
     */
    public static boolean runtimePerVariable(Rule rule)
    {
        List<Expr> exprs = new ArrayList<>();
        if (rule.getCheckLevelExprs() != null && !rule.getCheckLevelExprs().isEmpty())
        {
            exprs.addAll(rule.getCheckLevelExprs().values());
        }
        else if (rule.getCheckExpr() != null)
        {
            exprs.add(rule.getCheckExpr());
        }
        if (exprs.isEmpty())
        {
            return false;
        }
        Domain domain = rule.getEvaluationDomain();
        if (domain == null)
        {
            throw new IllegalStateException(rule.effectiveId()
                    + ": compiled Check without a cached evaluation domain — the runtime"
                    + " per-variable test cannot be decided");
        }
        for (Expr expr : exprs)
        {
            BindingScope scope = BindingScope.of(domain, expr);
            if (scope == BindingScope.PER_VARIABLE || scope == BindingScope.PER_VARIABLE_ROW)
            {
                return true;
            }
        }
        return false;
    }
}
