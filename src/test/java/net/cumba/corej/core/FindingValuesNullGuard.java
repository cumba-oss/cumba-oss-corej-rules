package net.cumba.corej.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * The finding-map null guard of {@code PLAN-output-variable-null-key-provenance} (§2.1, owner
 * 2026-10-04: <i>"nothing can be null"</i>): no {@link Violation#getValues()} entry of any rule
 * executed by the corpus suites carries a {@code null} value — <b>none</b>, with no allowance.
 *
 * <p>
 * <b>The omitted present-column entries.</b> The one state that used to keep a {@code null} key — a
 * raw column name, or {@code variable_value}, on a <b>column-level</b> finding, where the column is
 * present but the finding has no row to read a cell from (§6.2 H2) — is <em>omitted</em> from the
 * finding since T2 (coordinator decision 2026-10-04, under the owner's ruling), for both
 * provenances, until {@code PLAN-finding-values-record-level-only} (V8) gives it a name-only form.
 * The committed file {@value #ROSTER_FILE} lists every such {@code (rule, entry)} as that plan's
 * hand-over list; {@link FindingValuesNullGuardTest} asserts it equals its static derivation in
 * both directions. It allows nothing here.
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

    /**
     * The committed hand-over list of the omitted present-column entries, under
     * {@code src/test/resources/nullkey/}.
     */
    public static final String ROSTER_FILE = "present-column-omitted.tsv";

    /** Harness tag of the {@code .cdt} scenario factories. */
    public static final String CDT = "cdt";

    /** Harness tag of the rulespec runner. */
    public static final String SPEC = "spec";

    /** Harness tag of the pilot-data suites. */
    public static final String PILOT = "pilot";

    /** Harness tag of the {@code testdata/study} run. */
    public static final String STUDY = "study";

    private static final Map<String, AtomicLong> REACH = new ConcurrentHashMap<>();

    private static @Nullable Set<String> roster;

    private FindingValuesNullGuard()
    {
    }


    /** The roster file's path in this module. */
    public static Path rosterPath()
    {
        return CorpusPaths.moduleDir().resolve("src/test/resources/nullkey").resolve(ROSTER_FILE);
    }


    /**
     * Fails (an {@link AssertionError} naming the place, the rule and the key) when any value of
     * any violation is {@code null}; counts the call under {@code harness} either way.
     */
    public static void requireNullFree(String harness, String where, Rule rule,
            @Nullable List<Violation> violations)
    {
        List<String> offenders = judge(harness, coreId(rule), violations);
        if (!offenders.isEmpty())
        {
            throw new AssertionError(where + ": " + offenders.size() + " null-valued finding"
                    + " entr" + (offenders.size() == 1 ? "y" : "ies")
                    + " (PLAN-output-variable-null-key-provenance §2.1 — nothing may be null; an"
                    + " entry with no value is omitted or carries its typed default):\n  "
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
     * The {@code rule/key} pairs of {@code violations} whose value is {@code null} — the pure
     * judgement, for the sensitivity control.
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
            if (values == null)
            {
                continue;
            }
            for (Map.Entry<String, String> e : values.entrySet())
            {
                if (e.getValue() == null)
                {
                    out.add(coreId + "/" + e.getKey() + " (row " + v.getRow() + ", values " + values
                            + ")");
                }
            }
        }
        return out;
    }


    /** The rule's {@code Core.Id} (its {@code id} when the core is absent). */
    public static String coreId(Rule rule)
    {
        return rule.getCore() != null && rule.getCore().getId() != null ? rule.getCore().getId()
                : String.valueOf(rule.getId());
    }


    /** The committed hand-over list as {@code coreId<TAB>entry} rows; loaded once. */
    public static synchronized Set<String> roster()
    {
        Set<String> r = roster;
        if (r == null)
        {
            r = read(rosterPath());
            roster = r;
        }
        return r;
    }


    /**
     * Reads a roster file: {@code coreId<TAB>entry[<TAB>…]}, {@code #} comments and blanks skipped.
     */
    public static Set<String> read(Path file)
    {
        if (!Files.isRegularFile(file))
        {
            throw new IllegalStateException(file + " is missing — the omitted present-column"
                    + " hand-over list; regenerate it with -D"
                    + FindingValuesNullGuardTest.REGENERATE + "=true and commit it");
        }
        Set<String> out = new TreeSet<>();
        try
        {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
            {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#"))
                {
                    continue;
                }
                String[] c = t.split("\t", -1);
                if (c.length < 2)
                {
                    throw new IllegalStateException(file + ": malformed roster row: " + t);
                }
                out.add(c[0] + "\t" + c[1]);
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
