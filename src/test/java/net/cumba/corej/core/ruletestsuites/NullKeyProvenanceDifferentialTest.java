package net.cumba.corej.core.ruletestsuites;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.exec.OutputEntryKind;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.corej.core.report.LibraryValidator;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.corej.core.rulespec.SpecLoader;
import net.cumba.corej.core.rulespec.SpecRunner;
import net.cumba.corej.core.run.DatasetExecutionSummary;
import net.cumba.corej.core.run.StudyValidationParams;
import net.cumba.corej.core.run.StudyValidationResult;
import net.cumba.corej.core.run.StudyValidationService;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.manager.IDataTableLibraryRef;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.manager.IDataTableRef;
import net.cumba.datatable.manager.ILibraryMemberRef;
import net.cumba.datatable.manager.local.LocalDataTableManager;
import net.cumba.datatable.report.FindingKind;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationReportMember;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.opentest4j.TestAbortedException;

/**
 * The run-time verdict instrument of {@code PLAN-output-variable-null-key-provenance} (§6.2 H1):
 * every {@code .cdt} scenario of every family, every rulespec spec and the whole
 * {@code testdata/study} run, executed by the <b>current</b> engine and compared — <em>whole
 * finding rows</em>, the reported keys in emission order, a {@code null} value explicit — against a
 * <b>base dump</b> written by the engine before the change, under an <b>expected-movement
 * contract</b>: every cell that differs must fall into one of the movement classes the plan's fold
 * names ({@link MoveClass}); any other difference is CONTENT and fails, and a key order that
 * changed is ORDER and fails — unless it is the move {@code ovsweep/accepted-order-moves.tsv}
 * records for that rule (P2's D3 strip reordered 70 rules after the P0 base was dumped), in which
 * case the row is counted as {@code orderAccepted} and its values are still judged cell by cell.
 *
 * <p>
 * <b>Value oracles (T2 MED-4).</b> A movement class says only that a cell moved the allowed way;
 * {@link #VERDICT_CHECKS} additionally re-reads, on every finding of a few rules, the condition the
 * Check fired on from the reported values ({@code variable_value_length > define_vlm_length} on
 * FDA/PMDA-SD1231, …), and {@code -Dcorej.nullkey.plant=verdict} proves that oracle red.
 * </p>
 *
 * <p>
 * <b>Why a dump, not a second corpus.</b> {@code RedundantOvSweepDifferentialTest} compares two
 * rule texts under one engine. Here the <em>engine</em> changes and the rules do not, so the base
 * side has to be materialised by the old engine: {@code -Dcorej.nullkey.dump=FILE} writes one JSON
 * line per outcome (arm, scenario / spec / study dataset, rule, execution state, the primary
 * dataset's column names, and every finding row with its values map in emission order, a null value
 * as JSON {@code null}). The same run under the new engine with {@code -Dcorej.nullkey.base=FILE}
 * compares.
 * </p>
 *
 * <p>
 * <b>Where a {@code null} is visible.</b> On the scenario and spec arms a row is the
 * {@code Violation}'s own values map, so a key the engine omitted is absent and a key it kept with
 * a null value reads {@code null}. The study arm reads the report ({@code ValidationFinding}): a
 * finding carries one variable-name list for all its rows and {@code getRowValues} renders every
 * row over it, so an absent key and a null value both read {@code null} there (the
 * {@code RedundantOvSweepDifferentialTest} T2 LOW-3 caveat). The classes below are judged with that
 * in mind: on the study arm an absent-or-null base cell that becomes a typed default is the same
 * movement either way.
 * </p>
 *
 * <p>
 * <b>Sensitivity.</b> {@code -Dcorej.nullkey.plant=content|order|count} mutates the base in memory
 * after reading it (one value changed to an unclassifiable text, two keys of one row swapped, one
 * row dropped) — each plant must make the run fail naming the planted outcome. A green plant is a
 * failed experiment, never a pass.
 * </p>
 *
 * <p>
 * <b>Opt-in</b>, a plan instrument: {@code -Dcorej.nullkey.study=false} skips the study arms (the
 * OSS corpus has no fixture); without it a missing fixture or an unavailable metadata store is a
 * failure, never a skip. {@code -Dcorej.nullkey.report=FILE} writes every classified movement.
 * </p>
 *
 * <p>
 * <b>The name-only channel ({@code PLAN-finding-values-record-level-only} §7 M5).</b> A row
 * carries, beside its values map, the columns the finding lists <em>by name without a value</em>
 * ({@code Violation.getNamedColumns()}, V8 (b)). A key that leaves the values map and appears in
 * the name-only list is one of {@link MoveClass#VALUE_TO_NAME_ONLY},
 * {@link MoveClass#RECORD_RULE_FOLDED_TO_DATASET}, {@link MoveClass#EMPTY_TABLE_EMPTY_TO_NAME_ONLY}
 * or {@link MoveClass#FIX18_NAME_AS_VALUE_TO_NAME_ONLY}; a present column the engine used to omit
 * and now lists is {@link MoveClass#OMITTED_TO_NAME_ONLY}. A key that leaves the values map and is
 * listed nowhere, a name listed in both channels, a listed name that is no column of the dataset,
 * or a record finding (one with a row identity) that loses a value are CONTENT. The study run has
 * two arms: {@code study} reads the report ({@code ValidationFinding}, which carries no name-only
 * list until the report model does) and {@code study-engine} reads the engine's own
 * {@code Violation}s through {@code RuntimeListener.onRuleResult}; a report-arm key that vanished
 * is accepted only when the engine arm lists it name-only for the same rule × dataset. Plants:
 * {@code -Dcorej.nullkey.plant=nameonly-both|nameonly-unlisted|nameonly-record|nameonly-foreign}
 * mutate the CURRENT side one row at a time and each must be reported.
 * </p>
 *
 * <p>
 * <b>The report surfaces ({@code PLAN-finding-values-record-level-only} P2).</b> Once the report
 * model carries the list, the {@code study} arm reads {@code ValidationFinding.getNamedColumns()}
 * and a third study arm, {@code study-v2}, reads the v2 {@code Findings} document
 * ({@code StudyValidationResult.sections().combinedFindings()}): one row per v2 row, its
 * {@code USUBJID} / {@code SEQ} and its positional {@code variables} / {@code values} as the values
 * map, the finding-level {@code columns} field as the name-only list (a padding {@code ["null"]} of
 * a row with no variables is not a variable and is not recorded). Three movements are the report
 * side's own: {@link MoveClass#S7_COLLAPSE_NULL_TO_NAME_ONLY} — the {@code variable_name}
 * collapse's partner-less column, a {@code null} cell in the base (the {@code "null"} token on the
 * v2 arm), is listed by name now; the engine arm never lists it, which is what tells it from the
 * next class — {@link MoveClass#REPORT_NAME_ONLY_RESTORED} — a column the engine arm lists
 * name-only that the P1 report dropped and the report lists again; and
 * {@link MoveClass#GROUP_ANCHOR_IDENTITY_DROPPED} — on the v2 arm only, the anchor row's
 * {@code USUBJID} / {@code SEQ} of a Group finding (the engine arm stamped a group key on it) read
 * {@code ""} now (owner Q-A, 2026-10-05). Plants: {@code nameonly-s7-both} (an S7 column valued and
 * listed at once), {@code nameonly-group-nongroup} (a non-group finding's {@code USUBJID} blanked
 * on the v2 arm), {@code nameonly-report-foreign} (a report-arm row listing a name no column
 * carries and no engine arm lists), {@code nameonly-s7-v2only} (P2 review LOW-2: the study arm's S7
 * undone, so the v2 arm's S7 — whose base "null" is a token, not a null — has no evidence and must
 * be CONTENT).
 * </p>
 */
@EnabledIf("net.cumba.corej.core.ruletestsuites.NullKeyProvenanceDifferentialTest#enabled")
class NullKeyProvenanceDifferentialTest
{

    static final String BASE_PROPERTY = "corej.nullkey.base";

    static final String DUMP_PROPERTY = "corej.nullkey.dump";

    private static final String STUDY_PROPERTY = "corej.nullkey.study";

    private static final String REPORT_PROPERTY = "corej.nullkey.report";

    private static final String PLANT_PROPERTY = "corej.nullkey.plant";

    private static final Path PROJECT_DIR = Path.of(System.getProperty("projectBasedir"));

    private static final Path RESOURCE_BASE = PROJECT_DIR
            .resolve("src/test/resources/net/cumba/corej/core/ruletestsuites");

    private static final List<AbstractRuleTestSuitesFactory> FACTORIES = List.of(
            new RuleTestSuitesCdiscFactoryTest(), new RuleTestSuitesDraftFactoryTest(),
            new RuleTestSuitesFdaFactoryTest(), new RuleTestSuitesPmdaFactoryTest());

    /** The study run's packages — the snapshot's selection ({@code FindingsSnapshot}). */
    private static final List<String> STUDY_PACKAGES = List.of("cdisc-sdtmig-3-4",
            "draft-sdtmig-3-4", "fda-sdtmig-3-4", "pmda-sdtmig-3-4");

    private static final String MIS = MissingValue.MIS.toString();

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Outcome key → the rule's binding static types ({@code Rule.staticBindingTypes}), filled by
     * the current run: the type source LOW-7 judges a binding's default by.
     */
    private static final Map<String, Map<String, ExprType>> BINDING_TYPES = new LinkedHashMap<>();

    /**
     * A spec outcome's key → its rule's {@code Core.Id} (a spec outcome is named after the spec
     * file, the order contract after the rule).
     */
    private static final Map<String, String> SPEC_RULE_IDS = new LinkedHashMap<>();

    /** Outcome key → the rule's {@link Sensitivity}, filled by the current run (M1's class). */
    private static final Map<String, Sensitivity> SENSITIVITIES = new LinkedHashMap<>();

    /**
     * {@code rule|dataset} → the names the engine arm of the study run lists name-only, the
     * evidence the report arm's vanished keys are judged against.
     */
    private static final Map<String, Set<String>> STUDY_ENGINE_NAMED = new LinkedHashMap<>();

    /**
     * {@code rule|dataset} of every study execution whose violations the engine stamped a group key
     * on ({@code Violation.isGroupFinding()}) — the evidence the v2 arm's blanked anchor identity
     * is judged against.
     */
    private static final Set<String> STUDY_GROUP_FINDINGS = new TreeSet<>();

    /**
     * {@code rule|dataset|column} of every S7 movement the {@code study} arm classified — the
     * evidence a {@code study-v2} S7 is judged against (P2 review LOW-2).
     */
    private static final Set<String> STUDY_S7 = new TreeSet<>();

    /** The arm reading the v2 {@code Findings} document of the study run. */
    private static final String ARM_STUDY_V2 = "study-v2";

    /** The arm reading the report model ({@code ValidationFinding}) of the study run. */
    private static final String ARM_STUDY = "study";

    /** Both modes run the same arms; exactly one of the two properties selects the mode. */
    static boolean enabled()
    {
        return System.getProperty(BASE_PROPERTY) != null
                || System.getProperty(DUMP_PROPERTY) != null;
    }

    /** The movement classes the contract allows (§6.2 H1, H3, M3, M4, H2). */
    enum MoveClass
    {
        /** An authored char entry that kept a null-valued key now carries {@code ""}. */
        CHAR_NULL_TO_EMPTY,
        /** An authored numeric / boolean / list entry that kept a null now carries {@code .}. */
        NONCHAR_NULL_TO_MIS,
        /** A derived entry that was omitted now carries its typed default. */
        OMITTED_TO_DEFAULT,
        /** An entry naming an absent column that kept a null-valued key is now omitted. */
        ABSENT_COLUMN_NULL_OMITTED,
        /**
         * A projection gap (M3: {@code variable_size} / {@code variable_max_size}) now resolves.
         */
        PROJECTION_GAP_RESOLVED,
        /**
         * H2 / T2: an AUTHORED raw-column / variable_value entry on a column-level finding — the
         * column present, no row to read — that kept a null-valued key is now omitted (the
         * hand-over list {@code nullkey/present-column-omitted.tsv}). A derived one was omitted
         * before and still is: no movement.
         */
        PRESENT_COLUMN_NULL_OMITTED,
        /**
         * T2 MED-1: {@code variable_length} of a column with an unspecified length printed the
         * column meta's {@code 0}; it is no value — the numeric default {@code .}, as the Check
         * reads it.
         */
        UNSPECIFIED_LENGTH_TO_MIS,
        /**
         * M4 (record path): a numeric binding or virtual that printed the untyped {@code ""} now
         * prints {@code .}.
         */
        RECORD_EMPTY_TO_MIS,
        /**
         * {@code PLAN-finding-values-record-level-only} V1 / V5: a raw column's value on a
         * non-record finding (a dataset verdict, a Dataset {@code {ROW}} rule's first firing row, a
         * Study rule) is now listed by name without a value.
         */
        VALUE_TO_NAME_ONLY,
        /**
         * §7 M1: a Record-sensitivity rule whose Check the missing-column fold decided at dataset
         * level — one finding, row 1, no row identity — printed row 0's value; it is name-only now.
         */
        RECORD_RULE_FOLDED_TO_DATASET,
        /**
         * §7 M6 / QNU N20: a present column on a dataset-level finding over an EMPTY primary
         * printed {@code ""} (there was no row to read); it is name-only now.
         */
        EMPTY_TABLE_EMPTY_TO_NAME_ONLY,
        /**
         * §7 M7: the Fix #18 identifier — a dotted entry no join serves on a {@code {VAR}} rule,
         * which printed its own name as its value — is listed by name now.
         */
        FIX18_NAME_AS_VALUE_TO_NAME_ONLY,
        /**
         * §7 M6: a present raw column on a column-level per-variable finding (5b's hand-over list
         * {@code nullkey/present-column-omitted.tsv}, now retired) was omitted; it is listed by
         * name now.
         */
        OMITTED_TO_NAME_ONLY,
        /**
         * {@code PLAN-finding-values-record-level-only} P2, 5b's S7: the report builder's
         * {@code variable_name} collapse put the named column into the finding's schema with a
         * {@code null} cell when the finding carried no partner value ({@code variable_label},
         * {@code variable_data_type}, …); the column is the finding's first name-only column now.
         * Report arms only; the engine arm never lists it (the collapse is the report's).
         */
        S7_COLLAPSE_NULL_TO_NAME_ONLY,
        /**
         * Owner Q-C (2026-10-05, <i>"agree, name only"</i>): a per-row binding ({@code $x} whose
         * value is a {@code Vector}) on a non-record finding printed its value at the finding's row
         * — a record's value through the binding channel; it is listed by name now. Never on a
         * record finding (a row identity carried).
         */
        PER_ROW_BINDING_TO_NAME_ONLY,
        /**
         * P2: a column the engine lists name-only, which the P1 report dropped from the finding
         * (its model had no field for it), is listed by the report again — on the engine arm's
         * evidence for the same rule × dataset.
         */
        REPORT_NAME_ONLY_RESTORED,
        /**
         * P2 (owner Q-A, 2026-10-05): the v2 document no longer prints the anchor row's
         * {@code USUBJID} / {@code SEQ} on a Group finding unless the column is a group key — they
         * describe the block's first flagged row, not the group. v2 arm only; v1 keeps them.
         */
        GROUP_ANCHOR_IDENTITY_DROPPED
    }


    /**
     * One finding row: its location text, its variable names, its values in emission order and the
     * columns it lists by name without a value (absent in a base dumped before the engine carried
     * the list, read as empty).
     */
    record Row(String where, List<String> names, Map<String, @Nullable String> values,
            @Nullable List<String> named)
    {

        List<String> namedColumns()
        {
            return named == null ? List.of() : named;
        }
    }


    /**
     * One compared unit: a scenario, a spec, or a (rule, dataset) of the study run (report arm
     * {@code study}, engine arm {@code study-engine}). {@code primaryRows} is the primary dataset's
     * row count ({@code -1} when unknown), the N20 empty-table discriminator.
     */
    record Outcome(String arm, String where, String rule, String domain, String state,
            List<String> cols, long primaryRows, List<Row> rows)
    {

        String key()
        {
            return arm + "|" + where + "|" + rule + "|" + domain;
        }
    }


    /** Everything one comparison accumulates. */
    private static final class Ledger
    {

        final Map<MoveClass, Integer> moves = new TreeMap<>();

        final Map<MoveClass, Set<String>> movedRules = new TreeMap<>();

        final List<String> content = new ArrayList<>();

        final List<String> order = new ArrayList<>();

        final StringBuilder report = new StringBuilder();

        int outcomes;

        /**
         * Rows whose key order moved exactly as {@code ovsweep/accepted-order-moves.tsv} records
         * (the D3 strip of P2 reordered 70 rules after the P0 base was dumped).
         */
        int orderAccepted;

        final Set<String> orderAcceptedRules = new TreeSet<>();

        int identical;

        int moved;

        void move(MoveClass c, String rule, String detail)
        {
            moves.merge(c, 1, Integer::sum);
            movedRules.computeIfAbsent(c, _ -> new TreeSet<>()).add(rule);
            report.append(c).append(' ').append(detail).append('\n');
        }


        String summary()
        {
            StringBuilder sb = new StringBuilder("[nullkey-differential] outcomes=")
                    .append(outcomes).append(" identical=").append(identical).append(" moved=")
                    .append(moved).append(" content=").append(content.size()).append(" order=")
                    .append(order.size()).append(" orderAccepted=").append(orderAccepted)
                    .append("/rules=").append(orderAcceptedRules.size());
            for (Map.Entry<MoveClass, Integer> e : moves.entrySet())
            {
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue()).append("/rules=")
                        .append(movedRules.get(e.getKey()).size());
            }
            return sb.toString();
        }
    }

    @Test
    void findingsMoveOnlyWithinTheExpectedMovementContract() throws IOException
    {
        String dump = System.getProperty(DUMP_PROPERTY);
        String base = System.getProperty(BASE_PROPERTY);
        assertTrue(dump == null || base == null,
                "set either " + DUMP_PROPERTY + " or " + BASE_PROPERTY + ", not both");
        boolean study = !"false".equalsIgnoreCase(System.getProperty(STUDY_PROPERTY));
        List<Outcome> current = run(study);
        assertFalse(current.isEmpty(), "no outcome was produced");
        if (dump != null)
        {
            write(Path.of(dump), current);
            System.out.println("[nullkey-differential] dumped " + current.size() + " outcomes to "
                    + dump + " (nulls: " + nullCensus(current) + ")");
            return;
        }
        Map<String, Outcome> baseline = read(Path.of(Objects.requireNonNull(base)));
        String plantKind = System.getProperty(PLANT_PROPERTY);
        String planted;
        if ("verdict".equals(plantKind))
        {
            planted = plantVerdict(current);
        }
        else if (plantKind != null && plantKind.startsWith("nameonly-"))
        {
            planted = plantNameOnly(current, baseline, plantKind);
        }
        else
        {
            planted = plant(baseline, plantKind);
        }
        Ledger ledger = new Ledger();
        Set<String> seen = new TreeSet<>();
        for (Outcome c : current)
        {
            ledger.outcomes++;
            seen.add(c.key());
            Outcome b = baseline.get(c.key());
            if (b == null)
            {
                ledger.content.add(c.key() + ": outcome has no base counterpart");
                continue;
            }
            judge(b, c, ledger);
        }
        for (String k : baseline.keySet())
        {
            if (!seen.contains(k))
            {
                ledger.content.add(k + ": base outcome is gone from the current run");
            }
        }
        List<String> verdict = verdictConsistency(current, ledger);
        String summary = ledger.summary();
        System.out.println(summary);
        String reportPath = System.getProperty(REPORT_PROPERTY, "");
        if (!reportPath.isBlank())
        {
            Files.writeString(Path.of(reportPath), summary + "\n" + ledger.report, UTF_8);
        }
        if (planted != null)
        {
            List<String> reported = Stream
                    .of(ledger.content.stream(), ledger.order.stream(), verdict.stream())
                    .flatMap(x -> x).toList();
            assertFalse(reported.isEmpty(),
                    "the plant " + planted + " was NOT reported — the instrument is blind");
            boolean named = reported.stream().anyMatch(s -> s.contains(planted));
            assertTrue(named, () -> "the plant " + planted + " was not the reported difference:\n"
                    + String.join("\n", reported));
            throw new AssertionError("PLANT REPORTED as intended (" + planted
                    + ") — this run is the sensitivity control and must be red:\n"
                    + String.join("\n", reported));
        }
        assertTrue(verdict.isEmpty(),
                () -> verdict.size() + " finding(s) whose reported values contradict the verdict:\n"
                        + String.join("\n", verdict.subList(0, Math.min(60, verdict.size()))));
        assertTrue(ledger.content.isEmpty(),
                () -> ledger.content.size() + " cell(s) moved OUTSIDE the contract (CONTENT):\n"
                        + String.join("\n",
                                ledger.content.subList(0, Math.min(60, ledger.content.size()))));
        assertTrue(ledger.order.isEmpty(), () -> ledger.order.size()
                + " row(s) changed their key ORDER:\n" + String.join("\n", ledger.order));
    }

    // ------------------------------------------------------------------ the arms


    private static List<Outcome> run(boolean study) throws IOException
    {
        List<Outcome> out = new ArrayList<>();
        List<String> aborted = new ArrayList<>();
        for (AbstractRuleTestSuitesFactory factory : FACTORIES)
        {
            String family = factory.family();
            Map<String, Map<String, Rule>> packages = AbstractRuleTestSuitesFactory
                    .loadFamily(family);
            Path familyDir = RESOURCE_BASE.resolve(family);
            try (Stream<Path> dirs = Files.list(familyDir))
            {
                for (Path ruleDir : dirs.filter(Files::isDirectory).sorted().toList())
                {
                    for (Path file : scenarioFiles(ruleDir))
                    {
                        Outcome o = scenario(factory, file, packages, aborted);
                        if (o != null)
                        {
                            out.add(o);
                        }
                    }
                }
            }
        }
        assertTrue(aborted.isEmpty(),
                () -> aborted.size()
                        + " scenario(s) ABORTED (a skip is a failure in the differential — is the"
                        + " metadata store available?):\n" + String.join("\n", aborted));
        for (Path spec : SpecLoader.findSpecs(CorpusPaths.moduleDir().resolve("rulespec/specs")))
        {
            out.add(spec(spec));
        }
        if (study)
        {
            out.addAll(study());
        }
        return out;
    }


    /** The rule directory's scenarios — one level; a subdirectory would be half-read, so fail. */
    private static List<Path> scenarioFiles(Path ruleDir) throws IOException
    {
        try (Stream<Path> list = Files.list(ruleDir))
        {
            List<Path> entries = list.sorted().toList();
            List<Path> dirs = entries.stream().filter(Files::isDirectory).toList();
            assertTrue(dirs.isEmpty(), () -> ruleDir + " has subdirectories " + dirs);
            return entries.stream().filter(p -> p.getFileName().toString().endsWith(".cdt"))
                    .filter(AbstractRuleTestSuitesFactory::hasShebang).toList();
        }
    }


    private static @Nullable Outcome scenario(AbstractRuleTestSuitesFactory factory, Path file,
            Map<String, Map<String, Rule>> packages, List<String> aborted)
        throws IOException
    {
        String rel = RESOURCE_BASE.relativize(file).toString().replace('\\', '/');
        RuleTestScenario s = RuleTestCdt.load(file);
        Rule rule = AbstractRuleTestSuitesFactory.resolveIn(packages, s);
        if (rule == null)
        {
            return new Outcome("cdt", rel, s.getCoreId(), s.getDomain(), "NO-RULE", List.of(), -1,
                    List.of());
        }
        OverlayDataTable primary = s.primaryTable();
        assertNotNull(primary, rel + " names no primary dataset");
        AbstractRuleTestSuitesFactory.EvalOutcome o;
        try
        {
            o = factory.evaluateScenario(file, s, rule, primary);
        }
        catch (TestAbortedException e)
        {
            aborted.add(rel + ": " + e.getMessage());
            return null;
        }
        Outcome out = new Outcome("cdt", rel, s.getCoreId(), s.getDomain(), state(o), cols(primary),
                primary.getRowCount(), o == null ? List.of() : rows(o.violations()));
        BINDING_TYPES.put(out.key(), rule.staticBindingTypes());
        SENSITIVITIES.put(out.key(), rule.getSensitivity());
        return out;
    }


    private static Outcome spec(Path file) throws IOException
    {
        String rel = CorpusPaths.moduleDir().relativize(file).toString().replace('\\', '/');
        var schema = SpecLoader.load(file);
        SpecRunner.RawExecution raw = SpecRunner.executeRawWithTable(schema);
        RuleExecutionResult r = raw.result();
        String state = r == null ? "SCOPE-SKIP"
                : "status=" + r.getStatus() + " count=" + r.getViolationCount() + " message="
                        + r.getStatusMessage();
        Outcome out = new Outcome("spec", rel, schema.name(),
                raw.primaryTable().getMetaData().getName(), state, cols(raw.primaryTable()),
                raw.primaryTable().getRowCount(),
                r == null || r.getViolations() == null ? List.of() : rows(r.getViolations()));
        BINDING_TYPES.put(out.key(), raw.rule().staticBindingTypes());
        SENSITIVITIES.put(out.key(), raw.rule().getSensitivity());
        SPEC_RULE_IDS.put(out.key(),
                net.cumba.corej.core.FindingValuesNullGuard.coreId(raw.rule()));
        return out;
    }


    private static List<Outcome> study() throws IOException
    {
        Path study = PROJECT_DIR.resolve("testdata").resolve("study");
        assertTrue(Files.isDirectory(study), "testdata/study is missing — pass -D" + STUDY_PROPERTY
                + "=false to skip the study arm deliberately");
        Map<String, IDataTable> tables = loadStudy(study);
        EngineRows engineRows = new EngineRows();
        StudyValidationResult result = execute(study, engineRows);
        Map<String, Map<String, List<Row>>> rows = studyRows(result);
        Map<String, Map<String, List<Row>>> v2Rows = studyV2Rows(result);
        Map<String, Map<String, String>> states = studyStates(result);
        Map<String, Map<String, ExprType>> typesByRule = new LinkedHashMap<>();
        Map<String, Sensitivity> sensitivityByRule = new LinkedHashMap<>();
        for (Rule rule : result.rules())
        {
            typesByRule.putIfAbsent(net.cumba.corej.core.FindingValuesNullGuard.coreId(rule),
                    rule.staticBindingTypes());
            sensitivityByRule.putIfAbsent(net.cumba.corej.core.FindingValuesNullGuard.coreId(rule),
                    rule.getSensitivity());
        }
        for (Rule rule : result.generatedRules().values())
        {
            typesByRule.putIfAbsent(net.cumba.corej.core.FindingValuesNullGuard.coreId(rule),
                    rule.staticBindingTypes());
            sensitivityByRule.putIfAbsent(net.cumba.corej.core.FindingValuesNullGuard.coreId(rule),
                    rule.getSensitivity());
        }
        Set<String> rules = new TreeSet<>(rows.keySet());
        rules.addAll(states.keySet());
        rules.addAll(v2Rows.keySet());
        List<Outcome> out = new ArrayList<>();
        for (String coreId : rules)
        {
            Set<String> datasets = new TreeSet<>();
            datasets.addAll(rows.getOrDefault(coreId, Map.of()).keySet());
            datasets.addAll(states.getOrDefault(coreId, Map.of()).keySet());
            datasets.addAll(v2Rows.getOrDefault(coreId, Map.of()).keySet());
            for (String dataset : datasets)
            {
                IDataTable table = tables.get(dataset.toUpperCase(Locale.ROOT));
                String state = states.getOrDefault(coreId, Map.of()).getOrDefault(dataset, "-");
                Outcome o = new Outcome("study", dataset, coreId, dataset, state,
                        table == null ? List.of() : cols(table),
                        table == null ? -1 : table.getRowCount(),
                        rows.getOrDefault(coreId, Map.of()).getOrDefault(dataset, List.of()));
                BINDING_TYPES.put(o.key(), typesByRule.getOrDefault(coreId, Map.of()));
                Sensitivity sensitivity = sensitivityByRule.get(coreId);
                if (sensitivity != null)
                {
                    SENSITIVITIES.put(o.key(), sensitivity);
                }
                out.add(o);
                // The engine arm: the same rule × dataset, read from the Violations the runtime
                // listener heard — the finding as the engine emitted it, name-only list included.
                List<Row> engine = engineRows.rows(coreId, dataset);
                Outcome e = new Outcome("study-engine", dataset, coreId, dataset, state, o.cols(),
                        o.primaryRows(), engine);
                BINDING_TYPES.put(e.key(), typesByRule.getOrDefault(coreId, Map.of()));
                if (sensitivity != null)
                {
                    SENSITIVITIES.put(e.key(), sensitivity);
                }
                Set<String> named = new TreeSet<>();
                for (Row r : engine)
                {
                    named.addAll(r.namedColumns());
                }
                STUDY_ENGINE_NAMED.put(coreId + "|" + dataset, named);
                if (engineRows.isGroupFinding(coreId, dataset))
                {
                    STUDY_GROUP_FINDINGS.add(coreId + "|" + dataset);
                }
                out.add(e);
                // The v2 arm: the same rule × dataset as the v2 Findings document prints it.
                Outcome v2 = new Outcome(ARM_STUDY_V2, dataset, coreId, dataset, state, o.cols(),
                        o.primaryRows(),
                        v2Rows.getOrDefault(coreId, Map.of()).getOrDefault(dataset, List.of()));
                BINDING_TYPES.put(v2.key(), typesByRule.getOrDefault(coreId, Map.of()));
                if (sensitivity != null)
                {
                    SENSITIVITIES.put(v2.key(), sensitivity);
                }
                out.add(v2);
            }
        }
        return out;
    }


    /**
     * rule → domain → the rows of the v2 {@code Findings} document, in document order: the row's
     * {@code USUBJID} and {@code SEQ} first, then {@code variables[i] → values[i]}; the
     * finding-level {@code columns} field is the row's name-only list (absent ⇒ empty). The
     * document is the one every v2 consumer reads (the REST findings page, the XLSX Issue Details
     * sheet), assembled by {@code StudyValidationResult.sections()}.
     */
    private static Map<String, Map<String, List<Row>>> studyV2Rows(StudyValidationResult result)
    {
        Map<String, Map<String, List<Row>>> out = new TreeMap<>();
        for (Map<String, Object> f : result.sections().combinedFindings())
        {
            String id = f.get("core_id") == null ? "-" : String.valueOf(f.get("core_id"));
            String domain = String.valueOf(f.get("domain"));
            List<Row> rows = out.computeIfAbsent(id, _ -> new TreeMap<>()).computeIfAbsent(domain,
                    _ -> new ArrayList<>());
            List<String> variables = strings(f.get("variables"));
            List<String> named = strings(f.get("columns"));
            for (Object o : (List<?>) Objects.requireNonNull(f.get("rows")))
            {
                Map<?, ?> r = (Map<?, ?>) o;
                List<String> values = strings(r.get("values"));
                Map<String, @Nullable String> cells = new LinkedHashMap<>();
                cells.put("USUBJID", String.valueOf(r.get("USUBJID")));
                cells.put("SEQ", String.valueOf(r.get("SEQ")));
                for (int i = 0; i < variables.size() && i < values.size(); i++)
                {
                    cells.put(variables.get(i), values.get(i));
                }
                rows.add(new Row("row=" + r.get("row") + " v2", variables, cells, named));
            }
        }
        return out;
    }


    /** A v2 document's string list, or empty when the field is absent. */
    private static List<String> strings(@Nullable Object list)
    {
        if (!(list instanceof List<?> l))
        {
            return List.of();
        }
        List<String> out = new ArrayList<>(l.size());
        for (Object o : l)
        {
            out.add(String.valueOf(o));
        }
        return out;
    }

    /**
     * The study run's engine-level rows: every rule × dataset result the engine hands to
     * {@code RuntimeListener.onRuleResult} (on the rule worker threads), keyed by Core.Id and
     * dataset, its violations projected exactly as the scenario and spec arms project theirs.
     */
    private static final class EngineRows implements LibraryValidator.RuntimeListener
    {

        private final Map<String, List<Row>> rows = new java.util.concurrent.ConcurrentHashMap<>();

        /** {@code rule|dataset} whose violations carry a group key (a Group finding). */
        private final Set<String> groupFindings = java.util.concurrent.ConcurrentHashMap
                .newKeySet();

        @Override
        public void onRuleExecuted(LibraryValidator.RuntimeEntry entry)
        {
            // the result arrives in onRuleResult
        }


        @Override
        public void onRuleResult(LibraryValidator.RuntimeEntry entry, RuleExecutionResult result)
        {
            if (entry.coreId() == null || result.getViolations() == null)
            {
                return;
            }
            String key = entry.coreId() + "|" + entry.domain();
            rows.merge(key, NullKeyProvenanceDifferentialTest.rows(result.getViolations()),
                    (a, b) ->
                    {
                        List<Row> both = new ArrayList<>(a);
                        both.addAll(b);
                        return both;
                    });
            for (Violation v : result.getViolations())
            {
                if (v.isGroupFinding())
                {
                    groupFindings.add(key);
                    break;
                }
            }
        }


        List<Row> rows(String coreId, String dataset)
        {
            return rows.getOrDefault(coreId + "|" + dataset, List.of());
        }


        boolean isGroupFinding(String coreId, String dataset)
        {
            return groupFindings.contains(coreId + "|" + dataset);
        }
    }

    private static StudyValidationResult execute(Path study, EngineRows engineRows)
        throws IOException
    {
        Path store = MetadataStoreFixture.storeFile().orElseThrow(() -> new IOException(
                "CDISC_PICKLE_CACHE_DIR / -Dcdisc.pickle.cache.dir must name the pickle corpus —"
                        + " the study arm seeds the unified metadata store from it"));
        StudyValidationParams params = StudyValidationParams.builder()
                .manager(new LocalDataTableManager()).dataLibrary(study.toString())
                .defineXmlPath(study.resolve("define.xml").toString()).rulesPackages(STUDY_PACKAGES)
                .rulesDir(CorpusPaths.rules().toString()).ruleThreads(4).maxErrorsPerRule(50)
                .severityThreshold(net.cumba.datatable.report.Severity.INFO)
                .runtimeListener(engineRows).build();
        String prev = System.getProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, store.toString());
        try
        {
            return new StudyValidationService().validate(params);
        }
        finally
        {
            if (prev == null)
            {
                System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
            }
            else
            {
                System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, prev);
            }
        }
    }


    private static Map<String, IDataTable> loadStudy(Path study) throws IOException
    {
        Map<String, IDataTable> out = new LinkedHashMap<>();
        URI defineUri = study.resolve("define.xml").toUri();
        IDataTableManager manager = new LocalDataTableManager();
        IDataTableLibraryRef library = manager.getLibraryRef(defineUri, null);
        for (ILibraryMemberRef member : manager.getLibraryMembers(library).toList())
        {
            IDataTableRef ref = manager.getDataTableRef(member, null);
            out.put(member.getName().toUpperCase(Locale.ROOT), manager.getDataTable(ref));
        }
        return out;
    }


    /**
     * rule → dataset → the finding rows, in emission order (report level). Beside the rows, the
     * report model's key slab is censused: a {@code null} key cell (5b's S8) is counted and, in
     * compare mode, forbidden — the engine's key maps carry a marker for a missing key cell, so a
     * {@code null} there would be a storage state the model invented.
     */
    private static Map<String, Map<String, List<Row>>> studyRows(StudyValidationResult result)
    {
        Map<String, Map<String, List<Row>>> out = new TreeMap<>();
        List<String> nullKeyCells = new ArrayList<>();
        for (ValidationReportMember m : result.report().getMembers())
        {
            for (ValidationFinding f : m.getFindings())
            {
                String id = f.getRuleId() == null ? "-" : f.getRuleId();
                List<Row> rows = out.computeIfAbsent(id, _ -> new TreeMap<>())
                        .computeIfAbsent(m.getDomain(), _ -> new ArrayList<>());
                String severity = f.getSeverity() == null ? "-" : f.getSeverity().name();
                String kind = f.getKind() == null ? "-" : f.getKind().name();
                List<String> named = reportNamedColumns(f);
                if (f.getKind() != FindingKind.RULE_VIOLATION || !f.hasRows())
                {
                    rows.add(new Row("row=- " + severity + "/" + kind, f.getVariableNames(),
                            new LinkedHashMap<>(f.getFirstRowValues()), named));
                    continue;
                }
                for (int pos = 0; pos < f.getRowCount(); pos++)
                {
                    rows.add(new Row(
                            "row=" + f.getRows().rowIndexAt(pos) + " " + severity + "/" + kind,
                            f.getVariableNames(), new LinkedHashMap<>(f.getRowValues(pos)), named));
                    for (Map.Entry<String, @Nullable String> k : f.getRowKeys(pos).entrySet())
                    {
                        if (k.getValue() == null)
                        {
                            nullKeyCells
                                    .add(id + "|" + m.getDomain() + " #" + pos + " " + k.getKey());
                        }
                    }
                }
            }
        }
        System.out.println("[nullkey-differential] study report: " + nullKeyCells.size()
                + " null key-slab cell(s)");
        if (System.getProperty(DUMP_PROPERTY) == null)
        {
            assertTrue(nullKeyCells.isEmpty(),
                    () -> nullKeyCells.size()
                            + " null key-slab cell(s) in the study report (5b S8 — the key channel"
                            + " stores no null):\n" + String.join("\n",
                                    nullKeyCells.subList(0, Math.min(40, nullKeyCells.size()))));
        }
        return out;
    }


    /**
     * The columns the report model lists by name without a value
     * ({@code ValidationFinding.getNamedColumns()}, P2) — constant across the finding's rows.
     */
    private static List<String> reportNamedColumns(ValidationFinding f)
    {
        return f.getNamedColumns();
    }


    /** rule → dataset → {@code status:count}. */
    private static Map<String, Map<String, String>> studyStates(StudyValidationResult result)
    {
        Map<String, Map<String, String>> out = new TreeMap<>();
        for (DatasetExecutionSummary s : result.executionSummaries())
        {
            for (DatasetExecutionSummary.RuleExecution e : s.ruleExecutions())
            {
                String id = e.coreId() == null ? "-" : e.coreId();
                out.computeIfAbsent(id, _ -> new TreeMap<>()).put(s.domain(),
                        e.status() + ":" + e.violations());
            }
        }
        return out;
    }


    private static List<Row> rows(List<Violation> violations)
    {
        List<Row> out = new ArrayList<>();
        for (Violation v : violations)
        {
            String where = "row=" + v.getRowNumber() + " usubjid=" + v.getUsubjid() + " seq="
                    + v.getSeq() + " level=" + v.getLevel() + " unit=" + v.getUnit() + " keys="
                    + (v.getKeys() == null ? "{}" : new TreeMap<>(v.getKeys())) + " group="
                    + (v.getGroupKey() == null ? "{}" : new TreeMap<>(v.getGroupKey()));
            Map<String, @Nullable String> values = v.getValues() == null ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(v.getValues());
            out.add(new Row(where, List.of(), values, namedColumnsOf(v)));
        }
        return out;
    }


    /** The columns a violation lists by name without a value (V8 (b)). */
    private static List<String> namedColumnsOf(Violation v)
    {
        return v.getNamedColumns();
    }


    private static String state(AbstractRuleTestSuitesFactory.@Nullable EvalOutcome o)
    {
        if (o == null)
        {
            return "OUT-OF-SCOPE";
        }
        return String.format(Locale.ROOT,
                "executed=%s errored=%s violated=%s count=%d truncated=%s reason=%s", o.executed(),
                o.errored(), o.violated(), o.totalCount(), o.truncated(), o.notExecutedReason());
    }


    private static List<String> cols(IDataTable table)
    {
        DataTableMeta meta = table.getMetaData();
        List<String> out = new ArrayList<>(meta.getColumnCount());
        for (int c = 0; c < meta.getColumnCount(); c++)
        {
            out.add(meta.getColumn(c).getName());
        }
        return out;
    }

    // ------------------------------------------------------------------ dump and read


    private static void write(Path file, List<Outcome> outcomes) throws IOException
    {
        try (BufferedWriter w = Files.newBufferedWriter(file, UTF_8))
        {
            for (Outcome o : outcomes)
            {
                w.write(JSON.writeValueAsString(o));
                w.newLine();
            }
        }
    }


    private static Map<String, Outcome> read(Path file) throws IOException
    {
        assertTrue(Files.isRegularFile(file), BASE_PROPERTY + " is not a file: " + file);
        Map<String, Outcome> out = new LinkedHashMap<>();
        TypeReference<Outcome> type = new TypeReference<>()
        {
        };
        for (String line : Files.readAllLines(file, UTF_8))
        {
            if (line.isBlank())
            {
                continue;
            }
            Outcome o = JSON.readValue(line, type);
            Outcome dup = out.put(o.key(), o);
            assertTrue(dup == null, "duplicate outcome key in the base: " + o.key());
        }
        assertFalse(out.isEmpty(), "the base dump is empty");
        return out;
    }


    /** The null census of a run: {@code rule/key → count}, printed with the dump. */
    private static Map<String, Integer> nullCensus(List<Outcome> outcomes)
    {
        Map<String, Integer> out = new TreeMap<>();
        for (Outcome o : outcomes)
        {
            for (Row r : o.rows())
            {
                for (Map.Entry<String, @Nullable String> e : r.values().entrySet())
                {
                    if (e.getValue() == null)
                    {
                        out.merge(o.arm() + ":" + o.rule() + "/" + e.getKey(), 1, Integer::sum);
                    }
                }
            }
        }
        return out;
    }


    /** Mutates the base for a sensitivity run; returns the planted outcome's key, or null. */
    private static @Nullable String plant(Map<String, Outcome> baseline, @Nullable String plant)
    {
        if (plant == null)
        {
            return null;
        }
        Map.Entry<String, Outcome> target = null;
        for (Map.Entry<String, Outcome> e : baseline.entrySet())
        {
            if (!e.getValue().rows().isEmpty() && e.getValue().rows().get(0).values().size() >= 2)
            {
                target = e;
                break;
            }
        }
        if (target == null)
        {
            throw new IllegalStateException("no outcome to plant into");
        }
        Outcome o = target.getValue();
        Row first = o.rows().get(0);
        List<Row> rows = new ArrayList<>(o.rows());
        switch (plant)
        {
        case "content" ->
        {
            Map<String, @Nullable String> v = new LinkedHashMap<>(first.values());
            String k = v.keySet().iterator().next();
            v.put(k, "PLANTED-" + v.get(k));
            rows.set(0, new Row(first.where(), first.names(), v, first.namedColumns()));
        }
        case "order" ->
        {
            List<String> keys = new ArrayList<>(first.values().keySet());
            Map<String, @Nullable String> v = new LinkedHashMap<>();
            v.put(keys.get(1), first.values().get(keys.get(1)));
            v.put(keys.get(0), first.values().get(keys.get(0)));
            for (int i = 2; i < keys.size(); i++)
            {
                v.put(keys.get(i), first.values().get(keys.get(i)));
            }
            rows.set(0, new Row(first.where(), first.names(), v, first.namedColumns()));
        }
        case "count" -> rows.remove(0);
        default -> throw new IllegalArgumentException("unknown plant " + plant);
        }
        target.setValue(new Outcome(o.arm(), o.where(), o.rule(), o.domain(), o.state(), o.cols(),
                o.primaryRows(), rows));
        System.out.println("[nullkey-differential] PLANTED " + plant + " into " + target.getKey());
        return target.getKey();
    }


    /**
     * The name-only plants ({@code PLAN-finding-values-record-level-only} M5), each a mutation of
     * the CURRENT side that the contract must reject: {@code nameonly-both} lists a name-only
     * column in the values map too; {@code nameonly-unlisted} drops a name-only column from the
     * list (the base value then vanished into nothing); {@code nameonly-foreign} lists a name no
     * column of the dataset carries; {@code nameonly-record} moves a raw column of a record finding
     * (one with a row identity) from its value to the list. Returns the planted outcome's key.
     */
    private static String plantNameOnly(List<Outcome> current, Map<String, Outcome> baseline,
            String plant)
    {
        for (int i = 0; i < current.size(); i++)
        {
            Outcome o = current.get(i);
            if (!plantArm(plant, o.arm()))
            {
                continue;
            }
            Outcome b = baseline.get(o.key());
            if (b == null || b.rows().size() != o.rows().size())
            {
                continue;
            }
            boolean groupFinding = STUDY_GROUP_FINDINGS.contains(o.rule() + "|" + o.domain());
            for (int r = 0; r < o.rows().size(); r++)
            {
                Row planted = plantedRow(plant, o.rows().get(r), b.rows().get(r), o.cols(),
                        groupFinding);
                if (planted != null)
                {
                    List<Row> rows = new ArrayList<>(o.rows());
                    rows.set(r, planted);
                    current.set(i, new Outcome(o.arm(), o.where(), o.rule(), o.domain(), o.state(),
                            o.cols(), o.primaryRows(), rows));
                    System.out.println("[nullkey-differential] PLANTED " + plant + " into "
                            + o.key() + " #" + r);
                    // The v2-only S7 plant mutates the study arm, and what must be reported is
                    // the v2 arm's movement of the same rule × dataset.
                    return "nameonly-s7-v2only".equals(plant)
                            ? ARM_STUDY_V2 + o.key().substring(o.arm().length())
                            : o.key();
                }
            }
        }
        throw new IllegalStateException("no outcome to plant " + plant + " into");
    }


    /** The arms a name-only plant mutates: the engine-side arms, or the report-side arms. */
    private static boolean plantArm(String plant, String arm)
    {
        return switch (plant)
        {
        case "nameonly-s7-both", "nameonly-report-foreign", "nameonly-s7-v2only" -> ARM_STUDY
                .equals(arm);
        case "nameonly-group-nongroup" -> ARM_STUDY_V2.equals(arm);
        default -> "cdt".equals(arm) || "spec".equals(arm);
        };
    }


    /** The plant's mutation of one current row, or {@code null} when the row offers no target. */
    private static @Nullable Row plantedRow(String plant, Row row, Row baseRow, List<String> cols,
            boolean groupFinding)
    {
        return switch (plant)
        {
        case "nameonly-both" -> row.namedColumns().isEmpty() ? null
                : withValue(row, row.namedColumns().get(0), "PLANTED");
        case "nameonly-unlisted" -> row.namedColumns().isEmpty() ? null
                : new Row(row.where(), row.names(), row.values(),
                        row.namedColumns().subList(1, row.namedColumns().size()));
        case "nameonly-foreign" -> row.namedColumns().isEmpty() ? null
                : new Row(row.where(), row.names(), row.values(),
                        concat(row.namedColumns(), "PLANTED_NO_SUCH_COLUMN"));
        case "nameonly-record" -> plantRecordRow(row, baseRow, cols);
        // P2 (report arm): an S7 column — listed now, a null cell in the base — valued as well.
        case "nameonly-s7-both" -> plantS7Both(row, baseRow);
        // P2 review LOW-2 (report arm): an S7 column put back to the base's null cell, so the
        // study arm reads it unchanged and records no S7 — the v2 arm's S7 for the same column
        // then has no evidence and must be CONTENT.
        case "nameonly-s7-v2only" -> plantS7Undone(row, baseRow);
        // P2 (report arm): a listed name no column carries and the engine arm never lists.
        case "nameonly-report-foreign" -> row.namedColumns().isEmpty() ? null
                : new Row(row.where(), row.names(), row.values(),
                        concat(row.namedColumns(), "PLANTED_NO_SUCH_COLUMN"));
        // P2 (v2 arm): a NON-group finding's anchor USUBJID blanked — the drop Q-A allows only on
        // a Group finding.
        case "nameonly-group-nongroup" -> groupFinding ? null : plantBlankUsubjid(row, baseRow);
        default -> throw new IllegalArgumentException("unknown plant " + plant);
        };
    }


    /**
     * A report-arm row listing a column the base carried with a {@code null} cell (S7), with that
     * column valued too — or {@code null} when the row has no such column.
     */
    private static @Nullable Row plantS7Both(Row row, Row baseRow)
    {
        for (String name : row.namedColumns())
        {
            if (baseRow.values().containsKey(name) && baseRow.values().get(name) == null)
            {
                return withValue(row, name, "PLANTED");
            }
        }
        return null;
    }


    /**
     * A report-arm row whose S7 column (listed now, a {@code null} cell in the base) is put back to
     * the base's {@code null} cell and dropped from the list — or {@code null} when the row has no
     * such column.
     */
    private static @Nullable Row plantS7Undone(Row row, Row baseRow)
    {
        for (String name : row.namedColumns())
        {
            if (baseRow.values().containsKey(name) && baseRow.values().get(name) == null)
            {
                Map<String, @Nullable String> values = new LinkedHashMap<>(row.values());
                values.put(name, null);
                List<String> named = new ArrayList<>(row.namedColumns());
                named.remove(name);
                return new Row(row.where(), row.names(), values, named);
            }
        }
        return null;
    }


    /**
     * A v2-arm row whose non-empty {@code USUBJID} (equal on both sides) is blanked — or
     * {@code null} when the row carries none.
     */
    private static @Nullable Row plantBlankUsubjid(Row row, Row baseRow)
    {
        String current = row.values().get("USUBJID");
        if (current == null || current.isEmpty()
                || !current.equals(baseRow.values().get("USUBJID")))
        {
            return null;
        }
        return withValue(row, "USUBJID", "");
    }


    /**
     * A record finding (row identity carried) whose raw-column value is moved to the name-only list
     * — the movement the engine must never make — or {@code null} when {@code row} is not such a
     * finding.
     */
    private static @Nullable Row plantRecordRow(Row row, Row baseRow, List<String> cols)
    {
        if (row.where().contains("usubjid=null"))
        {
            return null;
        }
        for (String key : row.values().keySet())
        {
            if (cols.contains(key) && OutputEntryKind.of(key) == OutputEntryKind.COLUMN
                    && Objects.equals(row.values().get(key), baseRow.values().get(key)))
            {
                Map<String, @Nullable String> values = new LinkedHashMap<>(row.values());
                values.remove(key);
                return new Row(row.where(), row.names(), values, concat(row.namedColumns(), key));
            }
        }
        return null;
    }


    private static Row withValue(Row row, String key, String value)
    {
        Map<String, @Nullable String> values = new LinkedHashMap<>(row.values());
        values.put(key, value);
        return new Row(row.where(), row.names(), values, row.namedColumns());
    }


    private static List<String> concat(List<String> list, String tail)
    {
        List<String> out = new ArrayList<>(list);
        out.add(tail);
        return out;
    }

    // ------------------------------------------------------------------ judgement


    private static void judge(Outcome b, Outcome c, Ledger ledger)
    {
        String k = c.key();
        if (!b.state().equals(c.state()))
        {
            ledger.content.add(k + ": state " + b.state() + " -> " + c.state());
            return;
        }
        if (b.rows().size() != c.rows().size())
        {
            ledger.content.add(k + ": " + b.rows().size() + " -> " + c.rows().size() + " rows");
            return;
        }
        Set<String> cols = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        cols.addAll(c.cols());
        boolean movedHere = false;
        boolean failedHere = false;
        for (int i = 0; i < b.rows().size(); i++)
        {
            Row rb = b.rows().get(i);
            Row rc = c.rows().get(i);
            String at = k + " #" + i + " " + rc.where();
            if (!rb.where().equals(rc.where()))
            {
                ledger.content.add(at + ": location " + rb.where() + " -> " + rc.where());
                failedHere = true;
                continue;
            }
            if (!consistent(new ArrayList<>(rc.values().keySet()),
                    new ArrayList<>(rb.values().keySet())))
            {
                if (!orderMoveAccepted(SPEC_RULE_IDS.getOrDefault(k, c.rule()),
                        new ArrayList<>(rb.values().keySet()),
                        new ArrayList<>(rc.values().keySet())))
                {
                    ledger.order.add(
                            at + ": keys " + rb.values().keySet() + " -> " + rc.values().keySet());
                    failedHere = true;
                    continue;
                }
                // P2 (D3) moved the effective ORDER of this rule after the base was dumped; the
                // move is the contract's, so the values are still judged cell by cell.
                ledger.orderAccepted++;
                ledger.orderAcceptedRules.add(c.rule());
            }
            Set<String> keys = new LinkedHashSet<>(rb.values().keySet());
            keys.addAll(rc.values().keySet());
            keys.addAll(rb.namedColumns());
            keys.addAll(rc.namedColumns());
            Set<String> namedElsewhere = reportArm(c.arm())
                    ? STUDY_ENGINE_NAMED.getOrDefault(c.rule() + "|" + c.domain(), Set.of())
                    : Set.of();
            boolean groupFinding = STUDY_GROUP_FINDINGS.contains(c.rule() + "|" + c.domain());
            for (String key : keys)
            {
                boolean inB = rb.values().containsKey(key);
                boolean inC = rc.values().containsKey(key);
                String vb = rb.values().get(key);
                String vc = rc.values().get(key);
                boolean namedB = rb.namedColumns().contains(key);
                boolean engineLists = namedElsewhere.contains(key);
                boolean namedC = rc.namedColumns().contains(key) || engineLists;
                if (inB && inC && Objects.equals(vb, vc) && !namedB && !namedC)
                {
                    continue;
                }
                if (!inB && !inC && namedB && namedC)
                {
                    continue; // listed by name on both sides
                }
                MoveClass move;
                if (namedB || namedC)
                {
                    move = classifyNameOnly(key, inB, vb, inC, vc, namedB, namedC, engineLists,
                            cols, c, rc, SENSITIVITIES.get(k));
                }
                else if (ARM_STUDY_V2.equals(c.arm()) && isAnchorIdentity(key))
                {
                    move = classifyAnchorIdentity(groupFinding, inB, vb, inC, vc);
                }
                else
                {
                    move = classify(key, inB, vb, inC, vc, cols, c.arm(),
                            BINDING_TYPES.getOrDefault(k, Map.of()).get(key));
                }
                String detail = at + " " + key + ": " + (inB ? String.valueOf(vb) : "<absent>")
                        + (namedB ? "+named" : "") + " -> "
                        + (inC ? String.valueOf(vc) : "<absent>") + (namedC ? "+named" : "");
                if (move == null)
                {
                    ledger.content.add(detail);
                    failedHere = true;
                }
                else
                {
                    ledger.move(move, c.rule(), detail);
                    movedHere = true;
                }
            }
        }
        if (failedHere)
        {
            return;
        }
        if (movedHere)
        {
            ledger.moved++;
        }
        else
        {
            ledger.identical++;
        }
    }


    /**
     * The movement class of a cell that touches the name-only channel on either side
     * ({@code PLAN-finding-values-record-level-only} M5), or {@code null} (CONTENT): a name listed
     * on both sides and valued on neither is identical (filtered by the caller); a name that leaves
     * the values map for the list is {@link MoveClass#VALUE_TO_NAME_ONLY} — or
     * {@link MoveClass#RECORD_RULE_FOLDED_TO_DATASET} on a Record-sensitivity rule whose finding is
     * the single, identity-less row-1 dataset verdict,
     * {@link MoveClass#EMPTY_TABLE_EMPTY_TO_NAME_ONLY} for a {@code ""} over an empty primary,
     * {@link MoveClass#FIX18_NAME_AS_VALUE_TO_NAME_ONLY} for a dotted entry that printed its own
     * name; a name valued nowhere that joins the list is {@link MoveClass#OMITTED_TO_NAME_ONLY}.
     * Everything else is CONTENT: a name in both channels at once, a name that left the list, a
     * listed name that is no column of the dataset (a dotted joined entry excepted — the primary
     * never carries it), or a raw column of a record finding (a row identity carried) that lost its
     * value.
     */
    static @Nullable MoveClass classifyNameOnly(String key, boolean inB, @Nullable String vb,
            boolean inC, @Nullable String vc, boolean namedB, boolean namedC, boolean engineLists,
            Set<String> cols, Outcome c, Row rc, @Nullable Sensitivity sensitivity)
    {
        if (namedC && inC)
        {
            return null; // both channels at once
        }
        if (namedB && !namedC)
        {
            return null; // left the list
        }
        OutputEntryKind kind = OutputEntryKind.of(key);
        boolean dotted = key.indexOf('.') > 0;
        // The report arms carry no row identity and number rows from 0; their vanished keys are
        // accepted on the engine arm's evidence for the same rule × dataset (STUDY_ENGINE_NAMED),
        // where the identity test below has already been applied.
        boolean report = reportArm(c.arm());
        // S7 (P2): the report's variable_name collapse — a bare column name the engine never
        // lists (the engine's entry is variable_name = X, the report re-keys it to X) that the
        // base carried with a null cell (the v2 document's "null" token) and the report lists by
        // name now. The variable the finding is about need not be a column of the dataset (a
        // finding about a variable that should exist names an absent one), so the column test
        // below does not apply; the null base cell is the discriminator.
        boolean nullBase = vb == null || (ARM_STUDY_V2.equals(c.arm()) && "null".equals(vb));
        if (report && !engineLists && !namedB && namedC && inB && !inC && nullBase
                && kind == OutputEntryKind.COLUMN)
        {
            // P2 review LOW-2: on the v2 arm the base's "null" is a TOKEN, which a genuine value
            // printed as null would spell the same way — so a v2 S7 is accepted only on the study
            // (report-model) arm's S7 for the same rule × dataset × column, judged first (the
            // arms are compared in that order), where the base cell is a real null. A v2-only
            // movement is CONTENT, never hidden as S7.
            String s7 = c.rule() + "|" + c.domain() + "|" + key;
            if (ARM_STUDY.equals(c.arm()))
            {
                STUDY_S7.add(s7);
                return MoveClass.S7_COLLAPSE_NULL_TO_NAME_ONLY;
            }
            return STUDY_S7.contains(s7) ? MoveClass.S7_COLLAPSE_NULL_TO_NAME_ONLY : null;
        }
        if (kind == OutputEntryKind.BINDING)
        {
            // Q-C: a binding is no column of the dataset; it may only move from its value to the
            // list, and never on a record finding.
            boolean identity = !report && !rc.where().contains("usubjid=null seq=null");
            return !namedB && namedC && inB && !inC && !identity
                    ? MoveClass.PER_ROW_BINDING_TO_NAME_ONLY
                    : null;
        }
        if (kind != OutputEntryKind.COLUMN || (!dotted && !cols.contains(key)))
        {
            return null; // not a raw column of the dataset
        }
        if (!namedB && namedC && inB && !inC)
        {
            // A value became a name. Never on a record finding: the row identity is the mark of
            // one (dataset verdicts and column-level findings pass none).
            boolean identity = !report && !rc.where().contains("usubjid=null seq=null");
            if (identity)
            {
                return null;
            }
            if ("".equals(vb) && c.primaryRows() == 0)
            {
                return MoveClass.EMPTY_TABLE_EMPTY_TO_NAME_ONLY;
            }
            if (dotted && key.equals(vb))
            {
                return MoveClass.FIX18_NAME_AS_VALUE_TO_NAME_ONLY;
            }
            if (sensitivity == Sensitivity.RECORD)
            {
                // M1: a Record rule decided at dataset level by the fold — one finding at row 1.
                boolean folded = c.rows().size() == 1
                        && (report || rc.where().startsWith("row=1 "));
                return folded ? MoveClass.RECORD_RULE_FOLDED_TO_DATASET : null;
            }
            return MoveClass.VALUE_TO_NAME_ONLY;
        }
        if (!namedB && namedC && !inB && !inC)
        {
            if (report)
            {
                // P2: the report lists a column again that the P1 report dropped — only on the
                // engine arm's evidence; a report listing a name the engine does not is CONTENT.
                return engineLists ? MoveClass.REPORT_NAME_ONLY_RESTORED : null;
            }
            return MoveClass.OMITTED_TO_NAME_ONLY;
        }
        return null;
    }


    /** The arms that read the report side of the study run (the model, the v2 document). */
    private static boolean reportArm(String arm)
    {
        return ARM_STUDY.equals(arm) || ARM_STUDY_V2.equals(arm);
    }


    /** The v2 row's two identity cells, as the document spells them. */
    private static boolean isAnchorIdentity(String key)
    {
        return "USUBJID".equals(key) || "SEQ".equals(key);
    }


    /**
     * The movement class of a v2 row's {@code USUBJID} / {@code SEQ} cell (P2, owner Q-A): on a
     * Group finding (the engine stamped a group key on the rule × dataset) the anchor row's
     * identity reads {@code ""} now — {@link MoveClass#GROUP_ANCHOR_IDENTITY_DROPPED}. Any other
     * movement of these cells — on a non-group finding, or to anything but {@code ""} — is CONTENT.
     */
    static @Nullable MoveClass classifyAnchorIdentity(boolean groupFinding, boolean inB,
            @Nullable String vb, boolean inC, @Nullable String vc)
    {
        if (groupFinding && inB && inC && vb != null && !vb.isEmpty() && "".equals(vc))
        {
            return MoveClass.GROUP_ANCHOR_IDENTITY_DROPPED;
        }
        return null;
    }


    /**
     * The movement class of one cell, or {@code null} when it is outside the contract. The entry's
     * type comes from the engine's own classification ({@link OutputEntryKind}); whether a raw
     * column name is present reads the current primary dataset's columns.
     */
    static @Nullable MoveClass classify(String key, boolean inB, @Nullable String vb, boolean inC,
            @Nullable String vc, Set<String> cols, String arm, @Nullable ExprType bindingType)
    {
        OutputEntryKind kind = OutputEntryKind.of(key);
        // T2 LOW-7: a binding's default is judged by its stage-A static type — "" only for a
        // string binding, "." only for a numeric / boolean / list one; a binding whose type stage
        // A does not know (OPEN-T) may print either, as its evaluated type decides.
        boolean charDefault = kind == OutputEntryKind.CHAR
                || (kind == OutputEntryKind.BINDING && !nonCharBinding(bindingType));
        boolean misDefault = kind == OutputEntryKind.NUM || kind == OutputEntryKind.BOOLEAN
                || kind == OutputEntryKind.LIST
                || (kind == OutputEntryKind.BINDING && !charBinding(bindingType));
        boolean present = "variable_value".equals(key) || cols.contains(key);
        // M3: a variable-metadata entry the old projection could not reach (variable_size,
        // variable_max_size, define_vlm_*, variable_value_length, the *_matches facts, a
        // library_variable_* / define_variable_* attribute the Check never read) now carries the
        // value the Check reads — a REAL value, not a default, is what distinguishes the class.
        boolean realValue = vc != null && !vc.isEmpty() && !MIS.equals(vc);
        boolean gap = OutputEntryKind.isVariableMetadata(key) && realValue
                && kind != OutputEntryKind.NAME && kind != OutputEntryKind.COLUMN;
        boolean nullOrAbsentBefore = !inB || vb == null;
        if (inB && vb == null)
        {
            if (!inC)
            {
                if (kind != OutputEntryKind.COLUMN)
                {
                    return null;
                }
                return present ? MoveClass.PRESENT_COLUMN_NULL_OMITTED
                        : MoveClass.ABSENT_COLUMN_NULL_OMITTED;
            }
            if (gap && vc != null)
            {
                return MoveClass.PROJECTION_GAP_RESOLVED;
            }
            if ("".equals(vc) && charDefault)
            {
                return MoveClass.CHAR_NULL_TO_EMPTY;
            }
            if (MIS.equals(vc) && misDefault)
            {
                return MoveClass.NONCHAR_NULL_TO_MIS;
            }
            return null;
        }
        if (!inB && inC)
        {
            if (gap && vc != null)
            {
                return MoveClass.PROJECTION_GAP_RESOLVED;
            }
            if ("".equals(vc) && kind == OutputEntryKind.CHAR)
            {
                return MoveClass.OMITTED_TO_DEFAULT;
            }
            if (MIS.equals(vc) && (kind == OutputEntryKind.NUM || kind == OutputEntryKind.BOOLEAN
                    || kind == OutputEntryKind.LIST))
            {
                return MoveClass.OMITTED_TO_DEFAULT;
            }
            return null;
        }
        if (inB && "".equals(vb) && inC && MIS.equals(vc)
                && (kind == OutputEntryKind.NUM || (kind == OutputEntryKind.BINDING && misDefault)))
        {
            return MoveClass.RECORD_EMPTY_TO_MIS;
        }
        if (inB && "0".equals(vb) && inC && MIS.equals(vc) && "variable_length".equals(key))
        {
            return MoveClass.UNSPECIFIED_LENGTH_TO_MIS;
        }
        // The study arm reads the report: an absent key and a null value both render null there,
        // so a base null that becomes a typed default is judged like the omitted case above.
        if ("study".equals(arm) && nullOrAbsentBefore && inC && vc != null)
        {
            if (gap)
            {
                return MoveClass.PROJECTION_GAP_RESOLVED;
            }
            if ("".equals(vc) && charDefault)
            {
                return MoveClass.CHAR_NULL_TO_EMPTY;
            }
            if (MIS.equals(vc) && misDefault)
            {
                return MoveClass.NONCHAR_NULL_TO_MIS;
            }
        }
        return null;
    }


    /** A binding stage A types as a string. */
    private static boolean charBinding(@Nullable ExprType type)
    {
        return type == ExprType.Primitive.STRING;
    }


    /** A binding stage A types as a number, a boolean or a list. */
    private static boolean nonCharBinding(@Nullable ExprType type)
    {
        return type == ExprType.Primitive.NUMBER || type == ExprType.Primitive.BOOLEAN
                || type instanceof ExprType.ListOf;
    }

    // ------------------------------------------------------------------ verdict consistency

    /**
     * One value oracle of T2 MED-4: the reported values of every finding of {@code rules} must
     * satisfy {@code holds} — the condition the Check fired on, read back from the projection. The
     * movement classes judge only that a cell moved the allowed way; this judges that the value it
     * moved to is the one the verdict was reached with.
     */
    private record VerdictCheck(Set<String> rules, List<String> keys, String condition,
            java.util.function.Predicate<Map<String, @Nullable String>> holds)
    {
    }

    private static final List<VerdictCheck> VERDICT_CHECKS = List.of(
            new VerdictCheck(Set.of("FDA-SD1231", "PMDA-SD1231"),
                    List.of("variable_value_length", "define_vlm_length"),
                    "variable_value_length > define_vlm_length",
                    v -> number(v.get("variable_value_length")) > number(
                            v.get("define_vlm_length"))),
            new VerdictCheck(Set.of("FDA-SD1082", "PMDA-SD1082"),
                    List.of("variable_size", "variable_max_size", "variable_length"),
                    "variable_size != variable_max_size and variable_size == variable_length",
                    v -> !Objects.equals(v.get("variable_size"), v.get("variable_max_size"))
                            && Objects.equals(v.get("variable_size"), v.get("variable_length"))),
            new VerdictCheck(Set.of("FDA-CT2003", "PMDA-CT2003"),
                    List.of("library_variable_code_pair_matches"),
                    "library_variable_code_pair_matches == false",
                    v -> "false".equals(v.get("library_variable_code_pair_matches"))),
            new VerdictCheck(Set.of("DRAFT-900025", "FDA-CT2006", "PMDA-CT2006"), List.of(),
                    "define_variable_decode_matches / define_vlm_decode_matches == false",
                    v -> "false".equals(v.containsKey("define_vlm_decode_matches")
                            ? v.get("define_vlm_decode_matches")
                            : v.get("define_variable_decode_matches"))));

    /**
     * Applies {@link #VERDICT_CHECKS} to every finding row of the current run. On the scenario and
     * spec arms every row of a checked rule must carry the keys; the study arm reads the report,
     * whose {@code variable_name} collapse re-keys a partner entry, so a row there is checked when
     * it carries them. Each check must have judged at least one row — a check over nothing fails.
     */
    private static List<String> verdictConsistency(List<Outcome> current, Ledger ledger)
    {
        List<String> out = new ArrayList<>();
        Map<String, Integer> judged = new TreeMap<>();
        for (Outcome o : current)
        {
            for (VerdictCheck check : VERDICT_CHECKS)
            {
                if (!check.rules().contains(o.rule()))
                {
                    continue;
                }
                for (int i = 0; i < o.rows().size(); i++)
                {
                    Map<String, @Nullable String> v = o.rows().get(i).values();
                    boolean carried = check.keys().stream().allMatch(v::containsKey);
                    if (!carried && reportArm(o.arm()))
                    {
                        continue;
                    }
                    judged.merge(check.condition(), 1, Integer::sum);
                    if (!carried || !check.holds().test(v))
                    {
                        out.add(o.key() + " #" + i + ": " + check.condition()
                                + " does not hold for " + v);
                    }
                }
            }
        }
        for (VerdictCheck check : VERDICT_CHECKS)
        {
            if (judged.getOrDefault(check.condition(), 0) == 0)
            {
                out.add("verdict check judged no finding: " + check.condition() + " over "
                        + check.rules());
            }
        }
        ledger.report.append("verdict-consistency ").append(judged).append('\n');
        System.out.println("[nullkey-differential] verdict consistency: " + judged);
        return out;
    }


    /** A reported number, NaN for a missing marker or a non-number (so a comparison fails). */
    private static double number(@Nullable String value)
    {
        if (value == null)
        {
            return Double.NaN;
        }
        try
        {
            return Double.parseDouble(value);
        }
        catch (NumberFormatException _)
        {
            return Double.NaN;
        }
    }


    /**
     * The {@code verdict} plant: the first current FDA-SD1231 finding reports a value length that
     * no longer exceeds the declared one — the oracle must name it.
     */
    private static String plantVerdict(List<Outcome> current)
    {
        for (int i = 0; i < current.size(); i++)
        {
            Outcome o = current.get(i);
            if ("FDA-SD1231".equals(o.rule()) && !o.rows().isEmpty())
            {
                Row first = o.rows().get(0);
                Map<String, @Nullable String> v = new LinkedHashMap<>(first.values());
                v.put("variable_value_length", "1");
                List<Row> rows = new ArrayList<>(o.rows());
                rows.set(0, new Row(first.where(), first.names(), v, first.namedColumns()));
                current.set(i, new Outcome(o.arm(), o.where(), o.rule(), o.domain(), o.state(),
                        o.cols(), o.primaryRows(), rows));
                System.out.println("[nullkey-differential] PLANTED verdict into " + o.key());
                return o.key();
            }
        }
        throw new IllegalStateException("no FDA-SD1231 finding to plant into");
    }

    /** {@code coreId → [before, after]} rows of {@code ovsweep/accepted-order-moves.tsv}. */
    private static @Nullable Map<String, List<List<List<String>>>> orderContract;

    /**
     * Whether a row's key order moved exactly as the order contract records for {@code rule}: the
     * base row's keys follow a contract row's {@code before} list and the current row's keys its
     * {@code after} list (members a list does not carry are ignored, as in {@link #consistent}).
     * The contract is the one {@code RedundantOvSweepDifferentialTest} judges the strip by; a
     * reorder of an unlisted rule, or one that is not the listed move, stays ORDER.
     */
    private static synchronized boolean orderMoveAccepted(String rule, List<String> baseKeys,
            List<String> currentKeys)
    {
        if (orderContract == null)
        {
            Map<String, List<List<List<String>>>> loaded = new LinkedHashMap<>();
            Path file = CorpusPaths.moduleDir()
                    .resolve("src/test/resources/ovsweep/accepted-order-moves.tsv");
            try
            {
                for (String line : Files.readAllLines(file, UTF_8))
                {
                    if (line.isBlank() || line.startsWith("#"))
                    {
                        continue;
                    }
                    String[] c = line.split("\t", -1);
                    assertTrue(c.length >= 5, "malformed order-contract row: " + line);
                    loaded.computeIfAbsent(c[0], _ -> new ArrayList<>())
                            .add(List.of(List.of(c[3].split("\\|")), List.of(c[4].split("\\|"))));
                }
            }
            catch (IOException e)
            {
                throw new java.io.UncheckedIOException(e);
            }
            assertFalse(loaded.isEmpty(), "the order contract is empty: " + file);
            orderContract = loaded;
        }
        for (List<List<String>> row : orderContract.getOrDefault(rule, List.of()))
        {
            if (consistent(baseKeys, row.get(0)) && consistent(currentKeys, row.get(1)))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * The members of {@code keys} that {@code order} carries appear in {@code order}'s sequence.
     */
    static boolean consistent(List<String> keys, List<String> order)
    {
        int last = -1;
        for (String key : keys)
        {
            int at = order.indexOf(key);
            if (at < 0)
            {
                continue;
            }
            if (at < last)
            {
                return false;
            }
            last = at;
        }
        return true;
    }
}
