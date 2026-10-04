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
 * <b>Opt-in</b>, a plan instrument: {@code -Dcorej.nullkey.study=false} skips the study arm (the
 * OSS corpus has no fixture); without it a missing fixture or an unavailable metadata store is a
 * failure, never a skip. {@code -Dcorej.nullkey.report=FILE} writes every classified movement.
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
        RECORD_EMPTY_TO_MIS
    }


    /** One finding row: its location text, its variable names and its values in emission order. */
    record Row(String where, List<String> names, Map<String, @Nullable String> values)
    {
    }


    /** One compared unit: a scenario, a spec, or a (rule, dataset) of the study run. */
    record Outcome(String arm, String where, String rule, String domain, String state,
            List<String> cols, List<Row> rows)
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
        String planted = "verdict".equals(plantKind) ? plantVerdict(current)
                : plant(baseline, plantKind);
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
            return new Outcome("cdt", rel, s.getCoreId(), s.getDomain(), "NO-RULE", List.of(),
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
                o == null ? List.of() : rows(o.violations()));
        BINDING_TYPES.put(out.key(), rule.staticBindingTypes());
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
                r == null || r.getViolations() == null ? List.of() : rows(r.getViolations()));
        BINDING_TYPES.put(out.key(), raw.rule().staticBindingTypes());
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
        StudyValidationResult result = execute(study);
        Map<String, Map<String, List<Row>>> rows = studyRows(result);
        Map<String, Map<String, String>> states = studyStates(result);
        Map<String, Map<String, ExprType>> typesByRule = new LinkedHashMap<>();
        for (Rule rule : result.rules())
        {
            typesByRule.putIfAbsent(net.cumba.corej.core.FindingValuesNullGuard.coreId(rule),
                    rule.staticBindingTypes());
        }
        for (Rule rule : result.generatedRules().values())
        {
            typesByRule.putIfAbsent(net.cumba.corej.core.FindingValuesNullGuard.coreId(rule),
                    rule.staticBindingTypes());
        }
        Set<String> rules = new TreeSet<>(rows.keySet());
        rules.addAll(states.keySet());
        List<Outcome> out = new ArrayList<>();
        for (String coreId : rules)
        {
            Set<String> datasets = new TreeSet<>();
            datasets.addAll(rows.getOrDefault(coreId, Map.of()).keySet());
            datasets.addAll(states.getOrDefault(coreId, Map.of()).keySet());
            for (String dataset : datasets)
            {
                IDataTable table = tables.get(dataset.toUpperCase(Locale.ROOT));
                Outcome o = new Outcome("study", dataset, coreId, dataset,
                        states.getOrDefault(coreId, Map.of()).getOrDefault(dataset, "-"),
                        table == null ? List.of() : cols(table),
                        rows.getOrDefault(coreId, Map.of()).getOrDefault(dataset, List.of()));
                BINDING_TYPES.put(o.key(), typesByRule.getOrDefault(coreId, Map.of()));
                out.add(o);
            }
        }
        return out;
    }


    private static StudyValidationResult execute(Path study) throws IOException
    {
        Path store = MetadataStoreFixture.storeFile().orElseThrow(() -> new IOException(
                "CDISC_PICKLE_CACHE_DIR / -Dcdisc.pickle.cache.dir must name the pickle corpus —"
                        + " the study arm seeds the unified metadata store from it"));
        StudyValidationParams params = StudyValidationParams.builder()
                .manager(new LocalDataTableManager()).dataLibrary(study.toString())
                .defineXmlPath(study.resolve("define.xml").toString()).rulesPackages(STUDY_PACKAGES)
                .rulesDir(CorpusPaths.rules().toString()).ruleThreads(4).maxErrorsPerRule(50)
                .severityThreshold(net.cumba.datatable.report.Severity.INFO).build();
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


    /** rule → dataset → the finding rows, in emission order (report level). */
    private static Map<String, Map<String, List<Row>>> studyRows(StudyValidationResult result)
    {
        Map<String, Map<String, List<Row>>> out = new TreeMap<>();
        for (ValidationReportMember m : result.report().getMembers())
        {
            for (ValidationFinding f : m.getFindings())
            {
                String id = f.getRuleId() == null ? "-" : f.getRuleId();
                List<Row> rows = out.computeIfAbsent(id, _ -> new TreeMap<>())
                        .computeIfAbsent(m.getDomain(), _ -> new ArrayList<>());
                String severity = f.getSeverity() == null ? "-" : f.getSeverity().name();
                String kind = f.getKind() == null ? "-" : f.getKind().name();
                if (f.getKind() != FindingKind.RULE_VIOLATION || !f.hasRows())
                {
                    rows.add(new Row("row=- " + severity + "/" + kind, f.getVariableNames(),
                            new LinkedHashMap<>(f.getFirstRowValues())));
                    continue;
                }
                for (int pos = 0; pos < f.getRowCount(); pos++)
                {
                    rows.add(new Row(
                            "row=" + f.getRows().rowIndexAt(pos) + " " + severity + "/" + kind,
                            f.getVariableNames(), new LinkedHashMap<>(f.getRowValues(pos))));
                }
            }
        }
        return out;
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
            out.add(new Row(where, List.of(), values));
        }
        return out;
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
            rows.set(0, new Row(first.where(), first.names(), v));
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
            rows.set(0, new Row(first.where(), first.names(), v));
        }
        case "count" -> rows.remove(0);
        default -> throw new IllegalArgumentException("unknown plant " + plant);
        }
        target.setValue(
                new Outcome(o.arm(), o.where(), o.rule(), o.domain(), o.state(), o.cols(), rows));
        System.out.println("[nullkey-differential] PLANTED " + plant + " into " + target.getKey());
        return target.getKey();
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
            for (String key : keys)
            {
                boolean inB = rb.values().containsKey(key);
                boolean inC = rc.values().containsKey(key);
                String vb = rb.values().get(key);
                String vc = rc.values().get(key);
                if (inB && inC && Objects.equals(vb, vc))
                {
                    continue;
                }
                MoveClass move = classify(key, inB, vb, inC, vc, cols, c.arm(),
                        BINDING_TYPES.getOrDefault(k, Map.of()).get(key));
                String detail = at + " " + key + ": " + (inB ? String.valueOf(vb) : "<absent>")
                        + " -> " + (inC ? String.valueOf(vc) : "<absent>");
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
                    if (!carried && "study".equals(o.arm()))
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
                rows.set(0, new Row(first.where(), first.names(), v));
                current.set(i, new Outcome(o.arm(), o.where(), o.rule(), o.domain(), o.state(),
                        o.cols(), rows));
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
