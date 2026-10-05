package net.cumba.corej.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import net.cumba.corej.core.exec.OutputEntryKind;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.expr.eval.BindingDomains;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import org.jspecify.annotations.Nullable;

/**
 * The finding-map null guard of {@code PLAN-output-variable-null-key-provenance} (§2.1, owner
 * 2026-10-04: <i>"nothing can be null"</i>): no {@link Violation#getValues()} entry of any rule
 * executed by the corpus suites carries a {@code null} value — <b>none</b>, with no allowance.
 *
 * <p>
 * <b>The name-only channel</b> ({@code PLAN-finding-values-record-level-only} V8 (b)) is judged
 * too: {@link Violation#getNamedColumns()} holds raw column <em>names</em> and nothing else — no
 * {@code null} element, no blank, no {@code $} binding or metadata / virtual name (those always
 * carry a value), and no name that is also a key of the values map (a column is valued OR listed,
 * never both). The state that used to be a {@code null} key — a present raw column on a
 * column-level finding, 5b's hand-over list {@code nullkey/present-column-omitted.tsv} (retired
 * with this plan) — is listed by name there now.
 * </p>
 *
 * <p>
 * <b>Where it runs.</b> Inside the harnesses, on every execution: the {@code .cdt} scenario
 * factories ({@code AbstractRuleTestSuitesFactory.execute}), the rulespec runner
 * ({@code SpecRunner.executeAssert}), the pilot-data suites ({@code AbstractAdamRuleTest} /
 * {@code AbstractSdtmRuleTest}) and the {@code testdata/study} run ({@code FindingsSnapshot},
 * through the engine's {@code RuntimeListener.onRuleResult}). Each call is counted under its
 * harness ({@link #reach}), and each harness asserts at its end that the guard was reached on at
 * least a floor of its population (T2 MED-3) — a guard whose hook is bypassed would otherwise pass
 * in silence.
 * </p>
 */
public final class FindingValuesNullGuard
{

    /** Harness tag of the {@code .cdt} scenario factories. */
    public static final String CDT = "cdt";

    /** Harness tag of the rulespec runner. */
    public static final String SPEC = "spec";

    /** Harness tag of the pilot-data suites. */
    public static final String PILOT = "pilot";

    /** Harness tag of the {@code testdata/study} run. */
    public static final String STUDY = "study";

    private static final Map<String, AtomicLong> REACH = new ConcurrentHashMap<>();

    /** The engine-error sentinel key ({@code RuleRunner}): no projected entry, never judged. */
    private static final String SENTINEL = "__error__";

    private FindingValuesNullGuard()
    {
    }


    /**
     * Fails (an {@link AssertionError} naming the place, the rule and the key) when any violation
     * offends ({@link #offenders}); counts the call under {@code harness} either way.
     */
    public static void requireNullFree(String harness, String where, Rule rule,
            @Nullable List<Violation> violations)
    {
        List<String> offenders = judge(harness, rule, violations);
        if (!offenders.isEmpty())
        {
            throw new AssertionError(where + ": " + offenders.size() + " offending finding"
                    + " entr" + (offenders.size() == 1 ? "y" : "ies")
                    + " (PLAN-output-variable-null-key-provenance §2.1 — nothing may be null; an"
                    + " entry with no value is omitted, carries its typed default, or is listed"
                    + " by name — PLAN-finding-values-record-level-only V1 / V8 (b) / Q-C):\n  "
                    + String.join("\n  ", offenders));
        }
    }


    /**
     * The judgement without the throw, for a caller that fails later (the study run collects during
     * the run and judges after it, when the rules are known): counts the call under {@code harness}
     * and answers the offenders.
     */
    public static List<String> judge(String harness, Rule rule,
            @Nullable List<Violation> violations)
    {
        REACH.computeIfAbsent(harness, _ -> new AtomicLong()).incrementAndGet();
        return offenders(rule, violations);
    }


    /**
     * How often the guard ran under {@code harness} in this JVM — the reach a harness asserts a
     * floor on.
     */
    public static long reach(String harness)
    {
        AtomicLong n = REACH.get(harness);
        return n == null ? 0 : n.get();
    }


    /**
     * Fails unless the guard ran at least {@code floor} times under {@code harness} since
     * {@code start} (a {@link #reach} reading taken when the harness began).
     */
    public static void requireReach(String harness, long start, long floor, String population)
    {
        long reached = reach(harness) - start;
        System.out.println("[nullkey-guard] " + harness + ": reached " + reached + " time(s) for "
                + population + " (floor " + floor + ")");
        if (reached < floor)
        {
            throw new AssertionError("the finding-map null guard ran " + reached + " time(s) under "
                    + harness + ", below its floor " + floor + " for " + population
                    + " — its hook is bypassed (PLAN-output-variable-null-key-provenance T2"
                    + " MED-3)");
        }
    }


    /**
     * The offending entries of {@code violations} of {@code rule} — the pure judgement, for the
     * sensitivity control. Per violation, {@link #entryOffenders}.
     */
    public static List<String> offenders(Rule rule, @Nullable List<Violation> violations)
    {
        List<String> out = new ArrayList<>();
        if (violations == null || violations.isEmpty())
        {
            return out;
        }
        String coreId = coreId(rule);
        BindingDomains bindings = BindingDomains.forRule(rule);
        Sensitivity sensitivity = rule.getSensitivity();
        Domain domain = rule.getEvaluationDomain();
        for (Violation v : violations)
        {
            boolean nonRecord = isNonRecord(sensitivity, domain, v.getGroupKey(), v.getUnit(),
                    v.getNamedColumns());
            out.addAll(entryOffenders(coreId, v.getRow(), v.getValues(), v.getNamedColumns(),
                    nonRecord, ref -> bindings.domainOf(ref).rowCursor()));
        }
        return out;
    }


    /**
     * Whether a finding is <b>non-record</b> (V1): its producing site listed a column by name, or
     * stamped the dataset unit, or the rule is a Dataset / Study rule and the finding is no Group
     * finding (a Group finding keeps D94c's distinct sets, Q-A), or the rule's evaluation domain is
     * {@code {VAR}} — the variable cursor without the row cursor, the domain the report projects as
     * {@code FindingScope.VARIABLE}: every finding of such a rule is <b>column-level</b>
     * ({@code RuleRunner.buildVariableViolation} / {@code buildDefineVariableViolation}) or a
     * dataset verdict, and has no row (owner Q-C on the column-level path, 2026-10-05). That arm
     * judges a column-level finding whose name-only list is empty — before it a per-row binding
     * VALUED there passed unseen whenever no raw column was listed beside it. A Record rule folded
     * to a dataset verdict with nothing to list stamps no unit on the single-level path and is not
     * recognised — it carries no raw column then, so there is nothing to judge.
     */
    static boolean isNonRecord(@Nullable Sensitivity sensitivity, @Nullable Domain domain,
            @Nullable Map<String, String> groupKey, Violation.@Nullable Unit unit,
            @Nullable List<String> named)
    {
        return (named != null && !named.isEmpty()) || unit instanceof Violation.Unit.Dataset
                || (groupKey == null
                        && (sensitivity == Sensitivity.DATASET || sensitivity == Sensitivity.STUDY))
                || (groupKey == null && domain != null && domain.varCursor()
                        && !domain.rowCursor());
    }


    /**
     * One violation's offending entries ({@code rule/key …} lines):
     * <ul>
     * <li>a {@code null} value (NK §1 — unreachable through {@code Violation}'s constructor since
     * P2 review MED-1 (b), kept so the guard does not depend on it);</li>
     * <li>on a <b>non-record</b> finding (P1 review M-1 (a); a column-level one included,
     * {@link #isNonRecord}): a valued raw column ({@code OutputEntryKind.COLUMN}; the
     * {@code __error__} sentinel exempt) — its value would belong to an arbitrary record — and a
     * valued <b>per-row binding</b> (owner Q-C);</li>
     * <li>a name-only entry that is {@link #nameOnlyOffence wrong}, or listed twice (L-1).</li>
     * </ul>
     *
     * @param perRowBinding
     *            whether a {@code $} name is a per-row (row-cursor) binding of the rule
     */
    static List<String> entryOffenders(String coreId, long row,
            @Nullable Map<String, String> values, @Nullable List<String> named, boolean nonRecord,
            Predicate<String> perRowBinding)
    {
        List<String> out = new ArrayList<>();
        if (values != null)
        {
            for (Map.Entry<String, String> e : values.entrySet())
            {
                String key = e.getKey();
                if (e.getValue() == null)
                {
                    out.add(coreId + "/" + key + " (row " + row + ", values " + values + ")");
                }
                else if (nonRecord && !SENTINEL.equals(key)
                        && (OutputEntryKind.of(key) == OutputEntryKind.COLUMN
                                || (key.startsWith("$") && perRowBinding.test(key))))
                {
                    out.add(coreId + "/" + key + " valued on a non-record finding — "
                            + (key.startsWith("$") ? "a per-row binding (Q-C)"
                                    : "a raw column (V1)")
                            + " is listed by name there (row " + row + ", values " + values
                            + ", named " + named + ")");
                }
            }
        }
        if (named == null)
        {
            out.add(coreId + "/<namedColumns> is null (row " + row + ")");
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (String name : named)
        {
            String why = nameOnlyOffence(name, values, perRowBinding);
            if (why == null && !seen.add(name))
            {
                why = "twice";
            }
            if (why != null)
            {
                out.add(coreId + "/" + name + " listed by name " + why + " (row " + row
                        + ", values " + values + ", named " + named + ")");
            }
        }
        return out;
    }


    /**
     * Why {@code name} may not be in a finding's name-only list, or {@code null} when it may: the
     * list holds raw column names (V8 (b)) and per-row bindings (owner Q-C) — a dataset-scope
     * binding, a metadata attribute and a dataset virtual always carry a value, and a listed name
     * has no value, so it is never a key of the values map too.
     */
    static @Nullable String nameOnlyOffence(@Nullable String name,
            @Nullable Map<String, String> values, Predicate<String> perRowBinding)
    {
        if (name == null)
        {
            return "is null";
        }
        if (name.isBlank())
        {
            return "is blank";
        }
        if (name.startsWith("$") && !perRowBinding.test(name))
        {
            return "is a dataset-scope binding, which always carries its value";
        }
        if (OutputEntryKind.isMetadataOrVirtual(name) || OutputEntryKind.VARIABLE_NAME.equals(name)
                || OutputEntryKind.VARIABLE_VALUE.equals(name))
        {
            return "is a metadata / virtual entry, which always carries a value";
        }
        if (values != null && values.containsKey(name))
        {
            return "and valued at once";
        }
        return null;
    }


    /** The rule's {@code Core.Id} (its {@code id} when the core is absent). */
    public static String coreId(Rule rule)
    {
        return rule.getCore() != null && rule.getCore().getId() != null ? rule.getCore().getId()
                : String.valueOf(rule.getId());
    }
}
