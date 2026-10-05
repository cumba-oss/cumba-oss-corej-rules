package net.cumba.corej.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.cumba.corej.core.exec.OutputEntryKind;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.model.Rule;
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

    private FindingValuesNullGuard()
    {
    }


    /**
     * Fails (an {@link AssertionError} naming the place, the rule and the key) when any value of
     * any violation is {@code null}, or its name-only list holds anything but a raw column name;
     * counts the call under {@code harness} either way.
     */
    public static void requireNullFree(String harness, String where, Rule rule,
            @Nullable List<Violation> violations)
    {
        List<String> offenders = judge(harness, coreId(rule), violations);
        if (!offenders.isEmpty())
        {
            throw new AssertionError(where + ": " + offenders.size() + " offending finding"
                    + " entr" + (offenders.size() == 1 ? "y" : "ies")
                    + " (PLAN-output-variable-null-key-provenance §2.1 — nothing may be null; an"
                    + " entry with no value is omitted, carries its typed default, or is listed"
                    + " by name — PLAN-finding-values-record-level-only V8 (b)):\n  "
                    + String.join("\n  ", offenders));
        }
    }


    /**
     * The judgement without the throw, for a caller on a worker thread (the study run's runtime
     * listener collects and fails after the run): counts the call under {@code harness} and answers
     * the offenders.
     */
    public static List<String> judge(String harness, String coreId,
            @Nullable List<Violation> violations)
    {
        REACH.computeIfAbsent(harness, _ -> new AtomicLong()).incrementAndGet();
        return offenders(coreId, violations);
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
     * The {@code rule/key} pairs of {@code violations} whose value is {@code null}, and the
     * name-only entries that are not a raw column name — the pure judgement, for the sensitivity
     * control.
     */
    public static List<String> offenders(String coreId, @Nullable List<Violation> violations)
    {
        List<String> out = new ArrayList<>();
        if (violations == null)
        {
            return out;
        }
        for (Violation v : violations)
        {
            Map<String, String> values = v.getValues();
            if (values != null)
            {
                for (Map.Entry<String, String> e : values.entrySet())
                {
                    if (e.getValue() == null)
                    {
                        out.add(coreId + "/" + e.getKey() + " (row " + v.getRow() + ", values "
                                + values + ")");
                    }
                }
            }
            List<String> named = v.getNamedColumns();
            if (named == null)
            {
                out.add(coreId + "/<namedColumns> is null (row " + v.getRow() + ")");
                continue;
            }
            for (String name : named)
            {
                String why = nameOnlyOffence(name, values);
                if (why != null)
                {
                    out.add(coreId + "/" + name + " listed by name " + why + " (row " + v.getRow()
                            + ", values " + values + ", named " + named + ")");
                }
            }
        }
        return out;
    }


    /**
     * Why {@code name} may not be in a finding's name-only list, or {@code null} when it may: the
     * list holds raw column names only (V8 (b)) — a binding, a metadata attribute and a dataset
     * virtual always carry a value, and a listed name has no value, so it is never a key of the
     * values map too.
     */
    static @Nullable String nameOnlyOffence(@Nullable String name,
            @Nullable Map<String, String> values)
    {
        if (name == null)
        {
            return "is null";
        }
        if (name.isBlank())
        {
            return "is blank";
        }
        if (name.startsWith("$"))
        {
            return "is a binding, which always carries a value";
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
