package net.cumba.corej.core.ruletestsuites;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.cumba.corej.core.doc.FindingsSnapshot;
import net.cumba.corej.core.exec.MergedEvaluation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.merge.MergeKind;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.opentest4j.TestAbortedException;

/**
 * The verdict instrument of {@code PLAN-merged-evaluation-table} from W3a on: every {@code .cdt}
 * scenario of every family, and the whole {@code testdata/study} findings snapshot, executed twice
 * — on the engine's <b>legacy arm</b> (the key-match expander, the first-wins lookups, the SUPP
 * pivot; {@link MergedEvaluation#setLegacyForTests}) and on the <b>merged view</b> — and compared
 * outcome for outcome (execution state, findings, counts, the error message). It succeeds W2's
 * read-level shadow, whose reads all moved to the view in W3a: the comparison is now of what the
 * rule <em>answers</em>, which is what the legacy arm is kept for until W6 deletes it.
 *
 * <p>
 * Every difference must be a <b>ruled movement</b>: a scenario of the §3.5.3 SUPP case table (the
 * {@code _supp_case} scenarios of the two rules that read a qualifier bare, landed in W3a) whose
 * note says it moves — and each of those <em>must</em> move (non-vacuity). Any other difference is
 * UNEXPLAINED and fails; the study snapshot must agree row for row.
 * </p>
 *
 * <p>
 * <b>Opt-in</b>, a plan instrument: {@code -Dcorej.mergedView.differential=true}.
 * {@code -Dcorej.mergedView.study=false} skips the study arm; without it a missing fixture or
 * metadata store is a failure. {@code -Dcorej.mergedView.report=FILE} writes every difference with
 * its class.
 * </p>
 *
 * <p>
 * <b>Sensitivity.</b> {@code -Dcorej.mergedView.plant=keyed|supp} makes every read of the view's
 * keyed (or SUPP) parts answer the next merged row's cell ({@link MergedEvaluation#plantForTests});
 * the run must then fail with UNEXPLAINED differences. A green plant is a failed experiment, never
 * a pass.
 * </p>
 */
@EnabledIfSystemProperty(named = "corej.mergedView.differential", matches = "true")
class MergedViewDifferentialTest
{

    private static final Path PROJECT_DIR = Path.of(System.getProperty("projectBasedir"));

    private static final Path RESOURCE_BASE = PROJECT_DIR
            .resolve("src/test/resources/net/cumba/corej/core/ruletestsuites");

    private static final List<AbstractRuleTestSuitesFactory> FACTORIES = List.of(
            new RuleTestSuitesCdiscFactoryTest(), new RuleTestSuitesDraftFactoryTest(),
            new RuleTestSuitesFdaFactoryTest(), new RuleTestSuitesPmdaFactoryTest());

    /** The ruled movements a difference may be; anything else is unexplained. */
    enum Movement
    {
        /**
         * §3.5.3 cases 2, 3, 5, 6, 7, 9 and 9-overlap (MET #15, #18, OD-1, OD-2, OD-7): the SUPP
         * case-table scenarios of W3a whose note records the legacy verdict as different.
         */
        SUPP_CASE_TABLE,
        /** Not a ruled movement. */
        UNEXPLAINED
    }


    /** One scenario's outcome on both arms. */
    private record Row(String where, @Nullable String rule, String legacy, String view,
            Movement movement)
    {
    }

    /**
     * Whether a scenario is a W3a SUPP case-table scenario that moves between the arms: the
     * {@code _supp_case} scenarios, bar cases 1, 4 and 8, whose verdict is identical on both.
     */
    static boolean expectedToMove(String rel)
    {
        String name = rel.toLowerCase(Locale.ROOT);
        return name.contains("_supp_case") && !name.contains("_supp_case1_")
                && !name.contains("_supp_case4_") && !name.contains("_supp_case8_");
    }


    @Test
    void everyScenarioAndTheStudyAgreeBetweenTheLegacyArmAndTheView() throws IOException
    {
        String plantName = System.getProperty("corej.mergedView.plant");
        MergeKind plant = plantName == null ? null
                : MergeKind.valueOf(plantName.toUpperCase(Locale.ROOT));
        List<Row> rows = new ArrayList<>();
        List<String> mustMove = new ArrayList<>();
        int scenarios = scenarios(rows, mustMove, plant);
        boolean study = !"false".equals(System.getProperty("corej.mergedView.study"));
        List<String> studyDiff = study ? study(plant) : List.of();

        Map<Movement, Integer> byMovement = new TreeMap<>();
        List<String> unexplained = new ArrayList<>();
        for (Row row : rows)
        {
            byMovement.merge(row.movement(), 1, Integer::sum);
            if (row.movement() == Movement.UNEXPLAINED && unexplained.size() < 40)
            {
                unexplained.add(render(row));
            }
        }
        String summary = "[merged-view] scenarios=" + scenarios + " differences=" + byMovement
                + " expected-moving=" + mustMove.size() + " study-rows-moved=" + studyDiff.size()
                + (plant != null ? " plant=" + plant : "");
        System.out.println(summary);
        String report = System.getProperty("corej.mergedView.report");
        if (report != null)
        {
            try (BufferedWriter w = Files.newBufferedWriter(Path.of(report), UTF_8))
            {
                for (Row row : rows)
                {
                    w.write(row.movement() + "\t" + render(row));
                    w.newLine();
                }
                for (String line : studyDiff)
                {
                    w.write("STUDY\t" + line);
                    w.newLine();
                }
            }
        }
        assertTrue(scenarios > 9000, "only " + scenarios + " scenarios ran");
        if (plant != null)
        {
            assertFalse(unexplained.isEmpty(), "the plant went unseen — the " + plant
                    + " reads are not served from the" + " view: " + summary);
            return;
        }
        // Non-vacuity: every SUPP case-table scenario that moves between the arms must move.
        assertTrue(mustMove.isEmpty(), () -> "SUPP case-table scenarios that did NOT move"
                + " between the legacy arm and the view (the view is not read, or the case is not"
                + " what its note says):\n" + String.join("\n", mustMove));
        assertTrue(byMovement.getOrDefault(Movement.SUPP_CASE_TABLE, 0) > 0,
                "no SUPP case-table movement seen: " + summary);
        assertTrue(unexplained.isEmpty(), () -> summary + "\n" + String.join("\n", unexplained));
        assertTrue(studyDiff.isEmpty(), () -> summary + "\n"
                + String.join("\n", studyDiff.subList(0, Math.min(40, studyDiff.size()))));
    }


    private static String render(Row row)
    {
        return row.where() + "\t" + row.rule() + "\n\tlegacy: " + row.legacy() + "\n\tview:   "
                + row.view();
    }


    /** Runs every scenario on both arms; answers the scenario count. */
    private static int scenarios(List<Row> rows, List<String> mustMove, @Nullable MergeKind plant)
        throws IOException
    {
        List<String> aborted = new ArrayList<>();
        int scenarios = 0;
        for (AbstractRuleTestSuitesFactory factory : FACTORIES)
        {
            Map<String, Map<String, Rule>> packages = AbstractRuleTestSuitesFactory
                    .loadFamily(factory.family());
            try (Stream<Path> dirs = Files.list(RESOURCE_BASE.resolve(factory.family())))
            {
                for (Path ruleDir : dirs.filter(Files::isDirectory).sorted().toList())
                {
                    for (Path file : scenarioFiles(ruleDir))
                    {
                        String rel = RESOURCE_BASE.relativize(file).toString().replace('\\', '/');
                        RuleTestScenario s = RuleTestCdt.load(file);
                        Rule rule = AbstractRuleTestSuitesFactory.resolveIn(packages, s);
                        if (rule == null)
                        {
                            continue;
                        }
                        OverlayDataTable primary = s.primaryTable();
                        assertNotNull(primary, rel + " names no primary dataset");
                        scenarios++;
                        String legacy;
                        String view;
                        try
                        {
                            legacy = describe(onArm(true, null, factory, file, s, rule, primary));
                            view = describe(onArm(false, plant, factory, file, s, rule, primary));
                        }
                        catch (TestAbortedException e)
                        {
                            aborted.add(rel + ": " + e.getMessage());
                            continue;
                        }
                        boolean moved = !legacy.equals(view);
                        boolean expected = expectedToMove(rel);
                        if (expected && !moved)
                        {
                            mustMove.add(rel + " (" + view + ")");
                        }
                        if (moved)
                        {
                            rows.add(new Row(rel, s.getCoreId(), legacy, view,
                                    expected ? Movement.SUPP_CASE_TABLE : Movement.UNEXPLAINED));
                        }
                    }
                }
            }
        }
        assertTrue(aborted.isEmpty(),
                () -> aborted.size() + " scenario(s) ABORTED:\n" + String.join("\n", aborted));
        return scenarios;
    }


    private static AbstractRuleTestSuitesFactory.@Nullable EvalOutcome onArm(boolean legacy,
            @Nullable MergeKind plant, AbstractRuleTestSuitesFactory factory, Path file,
            RuleTestScenario s, Rule rule, OverlayDataTable primary)
        throws IOException
    {
        MergedEvaluation.setLegacyForTests(legacy);
        MergedEvaluation.plantForTests(legacy ? null : plant);
        try
        {
            return factory.evaluateScenario(file, s, rule, primary);
        }
        finally
        {
            MergedEvaluation.setLegacyForTests(false);
            MergedEvaluation.plantForTests(null);
        }
    }


    /** One arm's outcome as comparable text: state, counts, every finding, the error reason. */
    private static String describe(AbstractRuleTestSuitesFactory.@Nullable EvalOutcome o)
    {
        if (o == null)
        {
            return "out of scope";
        }
        return "executed=" + o.executed() + " errored=" + o.errored() + " violated=" + o.violated()
                + " total=" + o.totalCount() + " truncated=" + o.truncated() + " reason="
                + o.notExecutedReason() + " findings=" + Objects.toString(o.violations());
    }


    private static List<Path> scenarioFiles(Path ruleDir) throws IOException
    {
        try (Stream<Path> list = Files.list(ruleDir))
        {
            List<Path> entries = list.sorted().toList();
            assertFalse(entries.stream().anyMatch(Files::isDirectory),
                    () -> ruleDir + " has subdirectories");
            return entries.stream().filter(p -> p.getFileName().toString().endsWith(".cdt"))
                    .filter(AbstractRuleTestSuitesFactory::hasShebang).toList();
        }
    }


    /** The study findings snapshot on both arms; answers the rows that differ. */
    private static List<String> study(@Nullable MergeKind plant) throws IOException
    {
        MergedEvaluation.setLegacyForTests(true);
        FindingsSnapshot.Snapshot legacy;
        try
        {
            legacy = FindingsSnapshot.snapshot();
        }
        finally
        {
            MergedEvaluation.setLegacyForTests(false);
        }
        MergedEvaluation.plantForTests(plant);
        FindingsSnapshot.Snapshot view;
        try
        {
            view = FindingsSnapshot.snapshot();
        }
        finally
        {
            MergedEvaluation.plantForTests(null);
        }
        assertEquals(legacy.datasets(), view.datasets(), "dataset count");
        assertEquals(legacy.rules(), view.rules(), "rule count");
        List<String> expected = legacy.rows().stream().map(FindingsSnapshot.Row::line).toList();
        List<String> actual = view.rows().stream().map(FindingsSnapshot.Row::line).toList();
        return FindingsSnapshot.diff(expected, actual);
    }
}
