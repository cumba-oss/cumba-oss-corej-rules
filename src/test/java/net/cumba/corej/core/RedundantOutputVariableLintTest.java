package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * Q4 of {@code PLAN-redundant-output-variables-sweep} (owner, 2026-10-04): the corpus build fails
 * on any authored {@code Output_Variables} entry the load-time derivation produces anyway.
 *
 * <h2>What is judged, and through what</h2>
 * <p>
 * Every rule of every generated package is classified by {@link OvSweepClassifier} — through
 * {@code OutputVariableDeriver.derivedSet} and {@code derive()} themselves, never a
 * reimplementation of them. An authored include entry that is in the derived set is
 * <b>redundant</b>: the engine guarantees it (R-9.3/9.4/9.5/9.7 are engine guarantees on the
 * effective list since the sweep), and a hand-written copy of a guarantee is a claim nothing checks
 * and that drifts. The offenders are reported as a <b>roster</b> of {@code (rule, entry)}, never a
 * count — a count is blind to a re-key.
 * </p>
 *
 * <h2>No allowance</h2>
 * <p>
 * Until {@code PLAN-output-variable-null-key-provenance} the per-variable projection paths kept a
 * {@code null}-valued key for an <em>authored</em> entry that resolved to nothing and omitted a
 * <em>derived</em> one, so stripping such an entry would have flipped the finding; the lint carried
 * those 295 entries of 221 rules as the committed allowance {@code q2-residual.tsv} (Q2 (a)). Since
 * that plan every entry projects identically whatever its provenance (an entry with no value
 * carries its typed default), its D3 stripped the allowance and retired the file: a derived
 * authored entry is an offence on every rule, per-variable or not.
 * </p>
 *
 * <h2>Population, non-vacuity, sensitivity</h2>
 * <p>
 * The population is every rule of every package named by {@code packages.json} (floored), and the
 * authored YAMLs that ship in no package ({@code Executability: Not Executable}) are asserted to be
 * exactly {@code unshipped.txt} — out of scope, but never silently so. Two synthetic rules prove on
 * every run that the classifier flags a redundant plain column, a redundant {@code variable_name}
 * and a redundant per-variable {@code variable_label}, and that it leaves a non-derived column
 * alone. The offender roster of each run is written to {@code target/ovsweep-offenders.tsv} so a
 * red run can be diffed against the plan's manifest.
 * </p>
 */
class RedundantOutputVariableLintTest
{

    static final Path CONTRACTS = CorpusPaths.moduleDir().resolve("src/test/resources/ovsweep");

    /** The committed out-of-scope roster. */
    static final String UNSHIPPED_FILE = OvSweepCensus.UNSHIPPED_FILE;

    /**
     * Floors, so a run that read nothing cannot pass: distinct shipped rules (3 438 measured
     * 2026-10-04) and rules that still author an Output_Variables list (1 278 after the sweep — the
     * 2 029 fully redundant lists were deleted, so the pre-sweep 3 307 is no longer the
     * population).
     */
    private static final int MIN_SHIPPED_RULES = 3300;

    private static final int MIN_RULES_WITH_OV = 1000;

    private static final int MIN_PACKAGES = 47;

    @Test
    void noAuthoredOutputVariableEntryIsDerivedAnyway() throws IOException
    {
        sensitivityControl();

        Path rulesDir = CorpusPaths.rules();
        assertTrue(OvSweepCensus.packages(rulesDir).size() >= MIN_PACKAGES,
                "the generated corpus is incomplete");
        OvSweepCensus.Census census = OvSweepCensus.census(rulesDir,
                CorpusPaths.moduleDir().resolve("rules-src").resolve("checks"));
        assertTrue(census.shippedIds() >= MIN_SHIPPED_RULES,
                "only " + census.shippedIds() + " distinct shipped rules read");
        assertTrue(census.rows().size() >= MIN_RULES_WITH_OV,
                "only " + census.rows().size() + " rules with Output_Variables read");
        List<String> defects = census.notes().stream()
                .filter(n -> n.contains("DEFECT") || n.startsWith("load error")).toList();
        assertEquals(List.of(), defects, "the census met a load error or a member-set defect");

        Set<String> offenders = new TreeSet<>();
        for (OvSweepCensus.Row row : census.rows().values())
        {
            for (String entry : row.verdict().strip())
            {
                offenders.add(row.coreId() + "\t" + entry);
            }
        }
        Path report = CorpusPaths.moduleDir().resolve("target").resolve("ovsweep-offenders.tsv");
        Files.createDirectories(report.getParent());
        Files.writeString(report, String.join("\n", offenders) + (offenders.isEmpty() ? "" : "\n"),
                StandardCharsets.UTF_8);

        assertEquals(contract(UNSHIPPED_FILE), new TreeSet<>(census.unshipped()),
                "the authored YAMLs that carry Output_Variables but ship in no package must be"
                        + " exactly unshipped.txt");

        List<String> shown = new ArrayList<>(offenders);
        assertEquals(List.of(), shown.size() > 25 ? shown.subList(0, 25) : shown,
                offenders.size() + " authored Output_Variables entries are derived by the engine"
                        + " anyway (full roster: " + report + "). Delete them — the effective"
                        + " list is unchanged — and delete the Output_Variables key when nothing"
                        + " authored remains (R-9.2).");
    }


    /**
     * The classifier must flag a redundant plain column, a redundant {@code variable_name} and a
     * redundant per-variable {@code variable_label}, and keep a non-derived column — else the
     * roster above proves nothing.
     */
    private static void sensitivityControl() throws IOException
    {
        Map<String, Rule> rules = RulePackageLoader.loadFromString("{\"rules\":{"
                + "\"T-OVSWEEP-CTRL-1\":{\"Core\":{\"Id\":\"T-OVSWEEP-CTRL-1\"},\"Sensitivity\":\"Record\","
                + "\"Check\":{\"expression\":\"AESEQ > 0 and not empty(AETERM)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AETERM\",\"STUDYID\"]}},"
                + "\"T-OVSWEEP-CTRL-2\":{\"Core\":{\"Id\":\"T-OVSWEEP-CTRL-2\"},\"Sensitivity\":\"Record\","
                + "\"Check\":{\"expression\":\"var_label(\\\"DATA\\\") == \\\"x\\\"\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":"
                + "[\"variable_name\",\"variable_label\",\"STUDYID\"]}}}}").getRules();
        Rule r1 = rules.get("T-OVSWEEP-CTRL-1");
        Rule r2 = rules.get("T-OVSWEEP-CTRL-2");
        assertTrue(r1 != null && r1.getLoadError() == null,
                "control rule 1 did not load: " + (r1 == null ? "absent" : r1.getLoadError()));
        assertTrue(r2 != null && r2.getLoadError() == null,
                "control rule 2 did not load: " + (r2 == null ? "absent" : r2.getLoadError()));
        OvSweepClassifier.Verdict v1 = OvSweepClassifier.classify(r1);
        assertEquals(List.of("AETERM"), v1.strip(),
                "control: a plain column the Check reads is redundant, STUDYID is not");
        assertTrue(!v1.perVariable(), "control 1 is a row-path rule");
        OvSweepClassifier.Verdict v2 = OvSweepClassifier.classify(r2);
        assertTrue(v2.perVariable(), "control 2 reads a var_* accessor, so it is per-variable");
        assertEquals(List.of(OvSweepClassifier.VARIABLE_NAME, "variable_label"), v2.strip(),
                "control: on a per-variable rule variable_name AND the derived variable_label are"
                        + " stripped (no residual since PLAN-output-variable-null-key-provenance)");
    }


    /** A contract file: one entry per line, {@code #} comments and blanks skipped. */
    static Set<String> contract(String name) throws IOException
    {
        Set<String> out = new TreeSet<>();
        for (String line : Files.readAllLines(CONTRACTS.resolve(name), StandardCharsets.UTF_8))
        {
            if (!line.isBlank() && !line.startsWith("#"))
            {
                out.add(line.strip());
            }
        }
        return out;
    }
}
