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
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * The finding-map null guard of {@code PLAN-output-variable-null-key-provenance} (§2.1, owner
 * 2026-10-04: <i>"nothing can be null"</i>): no {@link Violation#getValues()} entry of any rule
 * executed by the corpus suites carries a {@code null} value — <b>except</b> the committed roster
 * {@value #ROSTER_FILE} of the deferred <em>present-column / no-cell</em> entries (§6.2 H2): a raw
 * column name, or {@code variable_value}, on a <b>column-level</b> finding, where the column is
 * present but the finding has no row to read a cell from. That state is neither §2.1 case (2) nor
 * case (3); {@code PLAN-finding-values-record-level-only} (V8) carries it as a name-only entry and
 * retires the roster.
 *
 * <p>
 * <b>Where it runs.</b> Inside the harnesses, on every execution: the {@code .cdt} scenario
 * factories ({@code AbstractRuleTestSuitesFactory.execute}) and the rulespec runner
 * ({@code SpecRunner.executeAssert}) hand every result here, so the population is every scenario of
 * every family and every spec — at no extra cost, and a new fixture is covered the moment it is
 * added. The {@code testdata/study} run reads the report, where an absent key and a null value
 * render alike; it is judged by the findings snapshot's classified diff, not here.
 * </p>
 *
 * <p>
 * <b>The roster is static, not observed.</b> {@link FindingValuesNullGuardTest} derives it from the
 * generated corpus (every per-variable rule whose Check takes the column-level path × every
 * effective entry of kind {@code COLUMN}) and asserts the committed file equals that derivation in
 * both directions, so a row cannot erode and a new candidate cannot hide. A {@code --} entry of the
 * roster allows its specialised spellings ({@code --CAT} allows {@code AECAT}).
 * </p>
 */
public final class FindingValuesNullGuard
{

    /** The committed roster, under {@code src/test/resources/nullkey/}. */
    public static final String ROSTER_FILE = "present-column-no-cell.tsv";

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
     * any violation is {@code null} and {@code (rule, key)} is not on the roster.
     */
    public static void requireNullFree(String where, Rule rule,
            @Nullable List<Violation> violations)
    {
        List<String> offenders = offenders(coreId(rule), violations, roster());
        if (!offenders.isEmpty())
        {
            throw new AssertionError(where + ": " + offenders.size() + " null-valued finding"
                    + " entr" + (offenders.size() == 1 ? "y" : "ies") + " outside the roster "
                    + ROSTER_FILE + " (PLAN-output-variable-null-key-provenance §2.1 — nothing"
                    + " may be null; a present-column / no-cell entry belongs on the roster):\n  "
                    + String.join("\n  ", offenders));
        }
    }


    /**
     * The {@code rule/key} pairs of {@code violations} whose value is {@code null} and that
     * {@code allowed} does not list — the pure judgement, for the sensitivity control.
     */
    public static List<String> offenders(String coreId, @Nullable List<Violation> violations,
            Set<String> allowed)
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
                if (e.getValue() == null && !isAllowed(coreId, e.getKey(), allowed))
                {
                    out.add(coreId + "/" + e.getKey() + " (row " + v.getRow() + ", values " + values
                            + ")");
                }
            }
        }
        return out;
    }


    /** Exact {@code coreId<TAB>key}, or a {@code --} roster entry the key specialises. */
    static boolean isAllowed(String coreId, String key, Set<String> allowed)
    {
        if (allowed.contains(coreId + "\t" + key))
        {
            return true;
        }
        String prefix = coreId + "\t--";
        for (String row : allowed)
        {
            if (row.startsWith(prefix) && key.endsWith(row.substring(prefix.length())))
            {
                return true;
            }
        }
        return false;
    }


    /** The rule's {@code Core.Id} (its {@code id} when the core is absent). */
    public static String coreId(Rule rule)
    {
        return rule.getCore() != null && rule.getCore().getId() != null ? rule.getCore().getId()
                : String.valueOf(rule.getId());
    }


    /** The committed roster as {@code coreId<TAB>entry} rows; loaded once. */
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
            throw new IllegalStateException(file + " is missing — the finding-map null guard has"
                    + " no roster; regenerate it with -D" + FindingValuesNullGuardTest.REGENERATE
                    + "=true and commit it");
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
