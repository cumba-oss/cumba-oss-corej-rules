package net.cumba.corej.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The golden file of every shipped rule's <b>effective</b> {@code Output_Variables} — the report
 * columns a finding carries, in order — asserted exactly, in both directions (T2 MED-1 of
 * {@code PLAN-redundant-output-variables-sweep}).
 *
 * <h2>Why it exists</h2>
 * <p>
 * The sweep deleted every authored {@code Output_Variables} entry the engine derives anyway (4 893
 * entries in 2 631 rules), so for those rules the report columns now come from
 * {@code OutputVariableDeriver} alone. Nothing else pins them: the findings snapshot is order-blind
 * and covers only the rules {@code testdata/study} reaches, the {@code .cdt} suites assert verdicts
 * and counts, and about 330 stripped rules have no fixture reach at all. A later deriver change
 * could therefore drop, rename or reorder a report column of any rule with every other gate green.
 * This test makes any such change a red that names the rule, the committed list and the derived
 * one.
 * </p>
 *
 * <h2>Population — taken + why</h2>
 * <p>
 * <b>Every shipped rule</b> of the full generated corpus ({@link CorpusPaths#rules()}, DRAFT
 * included, resolved through {@code packages.json}), not only the 2 631 stripped ones and not only
 * the rules with a non-empty list. Why: a deriver change does not stop at the stripped rules — it
 * appends to the derived tail of every rule that still authors a list, and it can start (or stop)
 * deriving columns for the no-witness rules whose list is empty today, which is a report change
 * too. Pinning the whole population costs one line per rule and leaves no class unwatched. An empty
 * effective list is written as {@value #EMPTY}. That every rule of {@code manifest-roster.txt} (the
 * stripped set) is present with a non-empty list is asserted separately, so the population cannot
 * shrink away from the rules this guard exists for.
 * </p>
 *
 * <h2>Format</h2>
 * <p>
 * {@code coreId<TAB>A|B|C}, sorted by key, {@code #} lines are comments. A rule ships in every
 * package its {@code Standards} block names, and its package-level effective list could differ per
 * package. When every package agrees (measured at P0: all of them), the key is the bare
 * {@code Core.Id}. When they disagree, the rule is written as one line <b>per package</b> under the
 * key {@code coreId@<package file name>} and no bare line — so a future disagreement is a visible
 * diff (one key lost, several gained) rather than an arbitrary pick.
 * </p>
 *
 * <h2>Regenerating</h2>
 * <p>
 * {@code mvn -o test -Dtest=EffectiveOutputVariablesDriftTest -Dcorej.ovsweep.regenerate=true}
 * rewrites {@code src/test/resources/ovsweep/effective-output-variables.tsv} from
 * {@code target/rules} and then <em>aborts</em> the test (a regeneration is never a green). Review
 * the diff: every changed line is a report-column change a user will see, and belongs in the
 * release notes. The file and this test are byte-identical in {@code cumba-oss-corej-rules}; the
 * OSS file holds the OSS corpus' lists.
 * </p>
 */
class EffectiveOutputVariablesDriftTest
{

    /** The committed golden file, under {@code src/test/resources/ovsweep/}. */
    static final String GOLDEN_FILE = "effective-output-variables.tsv";

    /** {@code -Dcorej.ovsweep.regenerate=true} rewrites the golden file instead of comparing. */
    static final String REGENERATE_PROPERTY = "corej.ovsweep.regenerate";

    /** The rendering of an empty effective list. */
    static final String EMPTY = "-";

    /** The separator inside a rendered list (no variable name contains it). */
    private static final String SEP = "|";

    /**
     * Floor on the rules read, so a run that read nothing cannot pass: 3 438 distinct shipped ids
     * at the sweep's P2; the floor is a policy, not the measured figure.
     */
    private static final int MIN_RULES = 3300;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void everyShippedRuleDerivesExactlyTheCommittedEffectiveOutputVariables() throws IOException
    {
        Map<String, String> derived = derivedRows();
        Path golden = contractDir().resolve(GOLDEN_FILE);
        if (Boolean.getBoolean(REGENERATE_PROPERTY))
        {
            Files.writeString(golden, render(derived), UTF_8);
            Assumptions.abort("regenerated " + derived.size() + " rows into " + golden
                    + " — review the diff (every changed line is a report-column change) and"
                    + " commit it; a regeneration is never a green");
        }

        assertTrue(derived.size() >= MIN_RULES, "only " + derived.size() + " shipped rules read"
                + " (expected at least " + MIN_RULES + "); a comparison over nothing is no guard");
        assertTrue(Files.isRegularFile(golden), golden + " is missing — regenerate it with -D"
                + REGENERATE_PROPERTY + "=true and commit it");
        Map<String, String> committed = parse(Files.readAllLines(golden, UTF_8));

        List<String> drift = diff(committed, derived);
        assertEquals(List.of(), drift.size() > 40 ? drift.subList(0, 40) : drift, drift.size()
                + " rule(s) no longer derive the committed effective Output_Variables — a report"
                + " column was added, dropped, renamed or reordered, or a rule appeared or"
                + " disappeared. If the change is intended, regenerate with -D"
                + REGENERATE_PROPERTY + "=true, review the diff and name it in the release notes.");
    }


    /**
     * The stripped rules — the population this guard was written for — are all pinned, each with a
     * non-empty list (a stripped rule had authored entries the engine derives, so its effective
     * list cannot be empty).
     */
    @Test
    void everyStrippedRuleIsPinnedWithANonEmptyList() throws IOException
    {
        Map<String, String> committed = parse(
                Files.readAllLines(contractDir().resolve(GOLDEN_FILE), UTF_8));
        Set<String> roster = new TreeSet<>();
        for (String line : Files.readAllLines(contractDir().resolve(OvSweepCensus.ROSTER_FILE),
                UTF_8))
        {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#"))
            {
                roster.add(t);
            }
        }
        assertTrue(roster.size() >= 2600, "the stripped roster holds only " + roster.size()
                + " rules; it is the sweep's committed contract (2 631)");
        List<String> unpinned = new ArrayList<>();
        for (String id : roster)
        {
            List<String> lists = committed.entrySet().stream()
                    .filter(e -> e.getKey().equals(id) || e.getKey().startsWith(id + "@"))
                    .map(Map.Entry::getValue).toList();
            if (lists.isEmpty())
            {
                unpinned.add(id + ": not in " + GOLDEN_FILE);
            }
            else if (lists.contains(EMPTY))
            {
                unpinned.add(id + ": pinned with an EMPTY list");
            }
        }
        assertEquals(List.of(), unpinned, unpinned.size() + " stripped rule(s) of "
                + OvSweepCensus.ROSTER_FILE + " are not pinned with a non-empty effective list");
    }


    /**
     * Non-vacuity control: the comparator must report each of the four drifts it exists for,
     * planted into a copy of the derived rows — a reordered list, a dropped column, a lost rule and
     * an extra rule. Without it a comparator that always answered "no drift" would green the guard.
     */
    @Test
    void theComparatorReportsEveryPlantedDrift() throws IOException
    {
        Map<String, String> derived = derivedRows();
        String target = derived.entrySet().stream()
                .filter(e -> e.getValue().split("\\" + SEP, -1).length >= 2).map(Map.Entry::getKey)
                .findFirst().orElse(null);
        assertNotNull(target, "no shipped rule has two or more effective columns");
        String other = derived.keySet().stream().filter(k -> !k.equals(target)).findFirst()
                .orElseThrow();
        assertEquals(List.of(), diff(derived, new TreeMap<>(derived)), "a copy must not drift");

        String[] cols = Objects.requireNonNull(derived.get(target)).split("\\" + SEP, -1);
        List<String> swapped = new ArrayList<>(List.of(cols));
        java.util.Collections.swap(swapped, 0, 1);
        assertPlanted(derived, target, String.join(SEP, swapped), "reordered");
        List<String> dropped = new ArrayList<>(List.of(cols));
        dropped.remove(dropped.size() - 1);
        assertPlanted(derived, target, String.join(SEP, dropped), "dropped column");

        Map<String, String> lost = new TreeMap<>(derived);
        lost.remove(other);
        assertEquals(1, diff(derived, lost).size(), "a lost rule must be reported");
        Map<String, String> extra = new TreeMap<>(derived);
        extra.put("ZZZ-PLANTED-0000", "USUBJID");
        assertEquals(1, diff(derived, extra).size(), "an extra rule must be reported");

        // the file round trip is lossless, so what the comparison reads is what was written
        assertEquals(derived, parse(List.of(render(derived).split("\n", -1))),
                "render/parse is not a lossless round trip");
    }


    private static void assertPlanted(Map<String, String> derived, String id, String planted,
            String what)
    {
        Map<String, String> sabotaged = new TreeMap<>(derived);
        sabotaged.put(id, planted);
        List<String> drift = diff(derived, sabotaged);
        assertEquals(1, drift.size(), "a " + what + " list of " + id + " must be reported");
        assertFalse(Objects.equals(derived.get(id), planted), "the plant changed nothing");
    }

    // ------------------------------------------------------------------ the derivation side


    /**
     * Key → rendered package-level effective list over every shipped package: the bare
     * {@code Core.Id} when its packages agree, else one {@code coreId@package} key per package.
     */
    static Map<String, String> derivedRows() throws IOException
    {
        Map<String, Map<String, String>> perPackage = new TreeMap<>();
        List<String> notInstalled = new ArrayList<>();
        JsonNode manifest = JSON
                .readTree(Files.readString(CorpusPaths.rules(RulePackageManifest.FILE_NAME)));
        for (JsonNode entry : manifest.get("packages"))
        {
            String file = entry.get("file").asText();
            Map<String, Rule> rules = RulePackageLoader.load(CorpusPaths.rules(file)).getRules();
            assertNotNull(rules, file + " has no rules");
            for (Map.Entry<String, Rule> e : rules.entrySet())
            {
                List<String> effective = e.getValue().getEffectiveOutputVariables();
                if (effective == null)
                {
                    notInstalled.add(file + " / " + e.getKey());
                    continue;
                }
                perPackage.computeIfAbsent(e.getKey(), _ -> new LinkedHashMap<>()).put(file,
                        effective.isEmpty() ? EMPTY : String.join(SEP, effective));
            }
        }
        assertEquals(List.of(), notInstalled, "the loader installed no effective list for these"
                + " shipped rules (a load error?)");

        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Map<String, String>> e : perPackage.entrySet())
        {
            Set<String> distinct = new TreeSet<>(e.getValue().values());
            if (distinct.size() == 1)
            {
                out.put(e.getKey(), distinct.iterator().next());
            }
            else
            {
                e.getValue().forEach((pkg, list) -> out.put(e.getKey() + "@" + pkg, list));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the file side


    /** Every key both sides do not agree on, as one line each: missing, extra or changed. */
    static List<String> diff(Map<String, String> committed, Map<String, String> derived)
    {
        List<String> out = new ArrayList<>();
        Set<String> keys = new TreeSet<>(committed.keySet());
        keys.addAll(derived.keySet());
        for (String key : keys)
        {
            String want = committed.get(key);
            String got = derived.get(key);
            if (want == null)
            {
                out.add(key + ": not committed, derives [" + got + "]");
            }
            else if (got == null)
            {
                out.add(key + ": committed [" + want + "], no longer shipped under this key");
            }
            else if (!want.equals(got))
            {
                out.add(key + ": committed [" + want + "] but derives [" + got + "]");
            }
        }
        return out;
    }


    static Map<String, String> parse(List<String> lines)
    {
        Map<String, String> out = new TreeMap<>();
        for (String line : lines)
        {
            if (line.isBlank() || line.startsWith("#"))
            {
                continue;
            }
            String[] cols = line.split("\t", -1);
            assertEquals(2, cols.length, "malformed line (expected coreId<TAB>list): " + line);
            assertTrue(out.put(cols[0], cols[1]) == null, "duplicate key " + cols[0]);
        }
        return out;
    }


    static String render(Map<String, String> rows)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(GOLDEN_FILE).append(" — every shipped rule's effective")
                .append(" Output_Variables (the report columns, in order), package level, from")
                .append(" target/rules. coreId<TAB>A|B|C; ").append(EMPTY)
                .append(" = an empty list; a rule whose packages disagree is written once per")
                .append(" package as coreId@<package file>. Asserted exactly, both directions, by")
                .append(" EffectiveOutputVariablesDriftTest (PLAN-redundant-output-variables-sweep")
                .append(" T2 MED-1); regenerate with -D").append(REGENERATE_PROPERTY)
                .append("=true and review the diff.\n");
        new TreeMap<>(rows).forEach((k, v) -> sb.append(k).append('\t').append(v).append('\n'));
        return sb.toString();
    }


    private static Path contractDir()
    {
        return CorpusPaths.moduleDir().resolve("src").resolve("test").resolve("resources")
                .resolve("ovsweep");
    }
}
