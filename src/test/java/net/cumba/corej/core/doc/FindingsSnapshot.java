package net.cumba.corej.core.doc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.corej.core.run.DatasetExecutionSummary;
import net.cumba.corej.core.run.StudyValidationParams;
import net.cumba.corej.core.run.StudyValidationResult;
import net.cumba.corej.core.run.StudyValidationService;
import net.cumba.datatable.manager.local.LocalDataTableManager;
import net.cumba.datatable.report.FindingKind;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationReportMember;
import org.jspecify.annotations.Nullable;

/**
 * Generates {@code generated/findings-snapshot.tsv} — one row per <b>finding occurrence</b> (and
 * one per rule × dataset execution status) of a full study-level run over {@code testdata/study},
 * the instrument {@code PLAN-leaf-scope-domain-inference.md} §7a gap 1 requires (owner ruling 13,
 * 2026-08-22).
 *
 * <h2>Why this exists — what the other instruments cannot see</h2>
 * <p>
 * The {@code .cdt} scenario suites assert verdicts and violation <em>counts</em>, and the rulespec
 * parity specs compare violation <em>sets</em>. Neither records finding <b>multiplicity</b> (a
 * dataset→per-variable granularity change whose projections coincide is invisible to a set) nor
 * <b>row numbers</b> outside the {@code #expectViolationAt} / {@code __row_number__} subsets. A
 * routing change that keeps every verdict but moves a finding from row 0 to the first firing row,
 * or splits one finding into one per variable, passes both. This artifact records exactly those two
 * things: one line per finding occurrence, with its row index, and the execution status of every
 * rule on every dataset.
 * </p>
 *
 * <h2>What is run</h2>
 * <p>
 * {@link StudyValidationService} — the production wiring the CLI and REST runs drive — over
 * {@code testdata/study} (the main-tree fixture: 22 Dataset-JSON datasets plus {@code define.xml})
 * at SDTMIG 3.4 with <b>every family</b> the manifest carries for that standard (CDISC, DRAFT, FDA,
 * PMDA), the Define-XML provider wired, and the CDISC Library served from the unified metadata
 * store {@link MetadataStoreFixture} seeds from the pickle corpus named by
 * {@code CDISC_PICKLE_CACHE_DIR} / {@code -Dcdisc.pickle.cache.dir}. One of those must be set or
 * the run is not comparable (a missing library provider flips every library-dependent rule to
 * SKIPPED). {@code CDISC_API_CACHE} is no longer read: the live API path was cut by cache P4a, so
 * the old both-caches requirement guarded a leg that no longer exists. The SEND and ADaM packages
 * are not exercised: the fixture has no SEND or ADaM data.
 * </p>
 *
 * <p>
 * ⭐ The run's severity threshold is pinned to {@link #SEVERITY_THRESHOLD Info} — <b>not</b> the
 * engine default ({@code Warning}) — since phase 5b (2026-08-26). The census is the only run-level
 * instrument, so its reach must cover <em>every</em> level a rule can declare: at the default
 * threshold an {@code INFO} check level is never evaluated (Plan C §3.4 step 2) and a whole rung of
 * shipped behaviour would be invisible to the only instrument that could see it. For every rule
 * with a single check level at {@code Warning} or stricter — the entire corpus before phase 5b —
 * the two thresholds produce identical rows, so the historical baselines remain comparable.
 * </p>
 *
 * <h2>Row key</h2>
 *
 * <pre>
 * Core.Id ⇥ dataset ⇥ row ⇥ variableNames ⇥ values ⇥ severity ⇥ status ⇥ scope ⇥ locationVariables
 * </pre>
 * <p>
 * A {@code finding} row carries the finding's row index ({@code -} for a row-less finding), the
 * finding's variable names (sorted), the {@code name=value} pairs of that row, the severity, the
 * finding kind, the reported {@code FindingScope} and the location's highlighted columns (sorted —
 * the one visible consumer of the scope label). A {@code status} row carries {@code rule × dataset}
 * execution status with its violation count in the {@code values} column. Rows are sorted, and
 * duplicates are <em>kept</em> (a {@code #n} suffix on the row column) — multiplicity is the whole
 * point.
 * </p>
 *
 * <h2>Regenerating</h2>
 * <p>
 * {@code scripts/gen-findings-snapshot.sh}. ⚠ A one-shot tool, <b>not</b> a step of the corpus
 * regeneration sequence — it censuses behaviour, not the corpus — and it is diffed, not
 * regenerated, at every finding-mover gate. {@link FindingsSnapshotDriftTest} reports a per-rule
 * diff and is opt-in ({@code -Dcorej.findingsSnapshot=true}) because the run takes minutes and
 * needs the library caches.
 * </p>
 */
public final class FindingsSnapshot
{

    /** The column header, recorded in the artifact's banner. */
    public static final String COLUMNS = "Core.Id\tdataset\trow\tvariableNames\tvalues\tseverity\tstatus\tscope\tlocationVariables";

    /** Prefix of the artifact's banner lines. Everything else is a data row. */
    public static final String COMMENT = "#";

    /** The standard / version the fixture study is validated against. */
    public static final String STANDARD = "sdtmig";

    /** The standard / version the fixture study is validated against. */
    public static final String VERSION = "3-4";

    /** Every family the manifest carries for {@link #STANDARD}. */
    public static final List<String> FAMILIES = List.of("CDISC", "DRAFT", "FDA", "PMDA");

    /**
     * The rule packages this snapshot runs, named explicitly now that Plan 2 removed the
     * conventional {@code (family, standard, version)} arm. Derived from the same three constants
     * the old {@code -s}/{@code -v}/{@code -f} triple used, so the selected package set — and hence
     * the snapshot — is unchanged: {@code rules-<family>-<standard>-<version>.json}.
     */
    public static final List<String> RULES_PACKAGES = FAMILIES.stream()
            .map(f -> f.toLowerCase(java.util.Locale.ROOT) + "-" + STANDARD + "-" + VERSION)
            .toList();

    /**
     * The run's severity threshold: the weakest rung, so the census sees every check level any rule
     * declares (see the class comment — at the engine default an {@code INFO} level is never
     * evaluated and would be invisible to the snapshot).
     */
    public static final net.cumba.datatable.report.Severity SEVERITY_THRESHOLD = net.cumba.datatable.report.Severity.INFO;

    /**
     * The per-(rule, dataset) materialisation cap handed to the run. The status row still carries
     * the <b>total</b> violation count (the engine counts every firing row; the cap only bounds
     * what is materialised), so multiplicity is exact and only the row numbers of a flood beyond
     * the cap are sampled away — measured 2026-08-22: uncapped, 133 (rule, dataset) pairs exceed 50
     * rows and the file is 12.7 MB; capped, the count column still names every one of them.
     */
    public static final int MAX_ERRORS_PER_RULE = 50;

    /**
     * The run's rule threads: the parallel path users run (owner ruling FGT-1,
     * {@code PLAN-fast-gate-tests}: the sequential production default is mostly overridden to 4 or
     * 8). The rows do not depend on it — a thread count that moved a row would be a nondeterminism
     * defect in the engine, and the drift test would name it.
     */
    public static final int RULE_THREADS = 4;

    /**
     * The JUnit tag of the tests that run the study ({@code FindingsSnapshotDriftTest},
     * {@code UseCaseFilterSnapshotTest}). Under {@code -Dcorej.findingsSnapshot=true} the pom's
     * {@code findings-snapshot} profile runs exactly this tag in a surefire execution of its own —
     * a fresh fork, both classes in it so they share {@link #census} — and removes it from the
     * default execution.
     */
    public static final String TAG = "findings-snapshot";

    /**
     * The census run ({@code useCase == null}), memoized for the JVM:
     * {@code FindingsSnapshotDriftTest} and {@code UseCaseFilterSnapshotTest} both need it, and the
     * pom runs the two in one dedicated fork so the second reads what the first ran. Same inputs,
     * same JVM, and both only read the result. Guarded by the class lock ({@link #censusRun()}).
     *
     * <p>
     * ⚠ Keyed on nothing: it holds the run over the inputs as they were at the <b>first</b> call —
     * the study fixture, the rule corpus, the seeded metadata store and the pickle cache directory
     * it was seeded from ({@code CDISC_PICKLE_CACHE_DIR} / {@code -Dcdisc.pickle.cache.dir}). A
     * caller that changes any of those inside the JVM and wants a run over the new inputs must not
     * go through {@link #run(String) run(null)}.
     * </p>
     */
    private static @Nullable StudyValidationResult census;

    /**
     * The system property the pom's {@code findings-snapshot} execution sets on its fork (its
     * {@code argLine}), and only there. It arms {@link #requireNoJacocoAgent()}.
     */
    static final String FORK_MARKER = "corej.findingsSnapshot.fork";

    private FindingsSnapshot()
    {
    }

    // ---------------------------------------------------------------- file locations


    /** The {@code corej-rules} module directory (Surefire's working directory). */
    public static Path moduleDir()
    {
        return RoutingCensus.moduleDir();
    }


    /** The committed artifact. */
    public static Path artifactFile()
    {
        return moduleDir().resolve("generated").resolve("findings-snapshot.tsv");
    }


    /** The main-tree study fixture ({@code testdata/study}, untracked — main tree only). */
    public static Path studyDir()
    {
        // ⚠ Was moduleDir()/../../testdata/study in the monorepo, where this module sat at
        // lib/corej-rules/ and had to climb two levels to the repo root. This repo is FLAT, so
        // moduleDir() IS the repo root and the climb is gone. Keeping it would have pointed two
        // directories ABOVE the checkout.
        return moduleDir().resolve("testdata").resolve("study").normalize();
    }

    // ---------------------------------------------------------------- model

    /**
     * One artifact row, ordered by its key columns.
     *
     * @param locationVariables
     *            the finding's {@code location.variableNames} — the highlighted columns, which
     *            {@code FindingLocations.columnsFor} prunes to nothing under {@code DATASET} scope;
     *            the one visible consumer of a scope-label move
     */
    public record Row(String coreId, String dataset, String row, String variableNames,
            String values, String severity, String status, String scope,
            String locationVariables) implements Comparable<Row>
    {

        @Override
        public int compareTo(Row o)
        {
            return line().compareTo(o.line());
        }


        /** The tab-separated rendering. */
        public String line()
        {
            return String.join("\t", coreId, dataset, row, variableNames, values, severity, status,
                    scope, locationVariables);
        }
    }


    /**
     * The run, projected to its rows.
     *
     * @param rows
     *            every row, sorted, duplicates suffixed
     * @param datasets
     *            how many target datasets the run saw
     * @param rules
     *            how many rules the run selected
     */
    public record Snapshot(List<Row> rows, int datasets, int rules)
    {
    }

    // ---------------------------------------------------------------- running

    /** Runs the study validation and projects it to rows. */
    public static Snapshot snapshot() throws IOException
    {
        return project(run(null));
    }


    /**
     * Runs the study validation this snapshot censuses — optionally under a run use case, which the
     * committed artifact never uses ({@code UseCaseFilterSnapshotTest} is the one caller that
     * passes one, to prove the use-case filter against the same study and corpus).
     *
     * <p>
     * The census run itself ({@code null}) is run once per JVM and memoized ({@link #census}); a
     * use-case run is run afresh on every call. ⚠ The memo reflects the inputs at the first call
     * (study, corpus, metadata store, pickle cache directory) — a caller that changes an input
     * within the JVM must not use {@code run(null)}.
     * </p>
     *
     * @param useCase
     *            the run's use case, or {@code null} for the census run itself
     * @return the raw result
     * @throws IOException
     *             when the untracked study fixture or the pickle corpus is missing
     */
    public static StudyValidationResult run(@Nullable String useCase) throws IOException
    {
        return useCase == null ? censusRun() : execute(useCase);
    }


    /**
     * The memoized census run. A failed run is not memoized: the next caller runs it again and sees
     * the same failure.
     */
    private static synchronized StudyValidationResult censusRun() throws IOException
    {
        StudyValidationResult r = census;
        if (r == null)
        {
            r = execute(null);
            census = r;
        }
        return r;
    }


    private static StudyValidationResult execute(@Nullable String useCase) throws IOException
    {
        requireNoJacocoAgent();
        Path study = studyDir();
        if (!Files.isDirectory(study))
        {
            throw new IOException("study fixture not found: " + study
                    + " (testdata/ is untracked — run from the main tree)");
        }
        Path store = MetadataStoreFixture.storeFile().orElseThrow(() -> new IOException(
                "CDISC_PICKLE_CACHE_DIR / -Dcdisc.pickle.cache.dir must name the pickle corpus — "
                        + "the snapshot seeds the unified metadata store from it, and without one "
                        + "library-dependent rules SKIP and the snapshot is not comparable"));
        StudyValidationParams params = StudyValidationParams.builder()
                .manager(new LocalDataTableManager()).dataLibrary(study.toString())
                .defineXmlPath(study.resolve("define.xml").toString()).rulesPackages(RULES_PACKAGES)
                .rulesDir(CorpusPaths.rules().toString()).ruleThreads(RULE_THREADS)
                .maxErrorsPerRule(MAX_ERRORS_PER_RULE).severityThreshold(SEVERITY_THRESHOLD)
                .useCase(useCase).build();
        // tryStoreProvider reads CDISC_METADATA_STORE / cdisc.metadata.store, not a params field —
        // point it at the seeded store for the duration of the run and restore afterwards.
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


    /**
     * In the pom's dedicated snapshot fork (marked by {@link #FORK_MARKER}), refuses to run with
     * the JaCoCo agent attached. The runs use {@link #RULE_THREADS} threads, and every thread then
     * writes the agent's shared probe arrays: measured 2026-09-28, four threads with the agent ran
     * about twice as slow as one thread, and several times slower than four threads without it. The
     * fork drops the agent on purpose; this makes its return loud instead of silently slow. Outside
     * that fork (an IDE run, the default execution) it checks nothing.
     */
    static void requireNoJacocoAgent()
    {
        if (!Boolean.getBoolean(FORK_MARKER))
        {
            return;
        }
        List<String> agents = java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getInputArguments().stream().filter(a -> a.startsWith("-javaagent:")
                        && a.toLowerCase(java.util.Locale.ROOT).contains("jacoco"))
                .toList();
        if (!agents.isEmpty())
        {
            throw new IllegalStateException("the findings-snapshot fork (-D" + FORK_MARKER
                    + "=true) runs with the JaCoCo agent attached: " + agents + ". With "
                    + RULE_THREADS + " rule threads the agent's probe contention makes"
                    + " the study run about twice as slow as a sequential one. The pom's"
                    + " findings-snapshot execution must keep its argLine free of @{argLine} and"
                    + " non-empty (an empty argLine falls back to the one jacoco sets).");
        }
    }


    /** Projects a run to its rows — separated so a unit test can feed a synthetic result. */
    public static Snapshot project(StudyValidationResult result)
    {
        List<Row> rows = new ArrayList<>();
        for (ValidationReportMember m : result.report().getMembers())
        {
            for (ValidationFinding f : m.getFindings())
            {
                rows.addAll(findingRows(m.getDomain(), f));
            }
        }
        for (DatasetExecutionSummary s : result.executionSummaries())
        {
            for (DatasetExecutionSummary.RuleExecution e : s.ruleExecutions())
            {
                // The TOTAL count (RuleExecutionResult.getViolationCount counts every firing
                // row, beyond the materialisation cap) — exact multiplicity per rule x dataset.
                rows.add(new Row(nz(e.coreId()), s.domain(), "-", "-",
                        Integer.toString(e.violations()), "-", "status:" + e.status(), "-", "-"));
            }
        }
        return new Snapshot(suffixDuplicates(rows), result.datasets().size(),
                result.rules().size());
    }


    private static List<Row> findingRows(String dataset, ValidationFinding f)
    {
        String id = nz(f.getRuleId());
        // Sorted: the engine's output-variable order is not deterministic across JVM runs for
        // names that come from an Expr.Call kwargs map (Map.copyOf randomises iteration order per
        // JVM — measured 2026-08-22 on FDA-SD2216 / PMDA-SD2216, whose filter keys TSPARMCD /
        // TSVAL swapped between two runs). Order is not what this instrument pins.
        String vars = sorted(f.getVariableNames());
        String locationVars = f.getLocation() == null || f.getLocation().getVariableNames() == null
                ? "-"
                : sorted(f.getLocation().getVariableNames());
        String severity = f.getSeverity() == null ? "-" : f.getSeverity().name();
        String kind = f.getKind() == null ? "-" : f.getKind().name();
        String scope = f.getScope() == null ? "-" : f.getScope().name();
        List<Row> out = new ArrayList<>();
        if (f.getKind() != FindingKind.RULE_VIOLATION || !f.hasRows())
        {
            out.add(new Row(id, dataset, "-", vars, renderValues(f.getFirstRowValues()), severity,
                    kind, scope, locationVars));
            return out;
        }
        int n = f.getRowCount();
        for (int pos = 0; pos < n; pos++)
        {
            out.add(new Row(id, dataset, Integer.toString(f.getRows().rowIndexAt(pos)), vars,
                    renderValues(f.getRowValues(pos)), severity, kind, scope, locationVars));
        }
        return out;
    }


    private static String sorted(List<String> names)
    {
        if (names.isEmpty())
        {
            return "";
        }
        List<String> copy = new ArrayList<>(names);
        java.util.Collections.sort(copy);
        return String.join(",", copy);
    }


    private static String renderValues(@Nullable Map<String, @Nullable String> values)
    {
        if (values == null || values.isEmpty())
        {
            return "-";
        }
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, @Nullable String> e : new TreeMap<>(values).entrySet())
        {
            if (b.length() > 0)
            {
                b.append('|');
            }
            b.append(e.getKey()).append('=').append(escape(e.getValue()));
        }
        return b.toString();
    }


    private static String escape(@Nullable String v)
    {
        if (v == null)
        {
            return "\u2400"; // ␀ — a null cell, distinct from the empty string
        }
        return v.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
                .replace("\r", "\\r").replace("|", "\\|");
    }


    private static String nz(@Nullable String s)
    {
        return s == null ? "-" : s;
    }


    /**
     * Sorts the rows and suffixes every repeated line with {@code #2}, {@code #3}, … on its row
     * column, so identical occurrences stay distinct lines (multiplicity) while the file stays a
     * set the drift test can diff line by line.
     */
    static List<Row> suffixDuplicates(List<Row> rows)
    {
        List<Row> sorted = new ArrayList<>(rows);
        java.util.Collections.sort(sorted);
        List<Row> out = new ArrayList<>(sorted.size());
        Row prev = null;
        int run = 1;
        for (Row r : sorted)
        {
            if (prev != null && prev.compareTo(r) == 0)
            {
                run++;
                out.add(new Row(r.coreId(), r.dataset(), r.row() + "#" + run, r.variableNames(),
                        r.values(), r.severity(), r.status(), r.scope(), r.locationVariables()));
            }
            else
            {
                run = 1;
                out.add(r);
            }
            prev = r;
        }
        return out;
    }

    // ---------------------------------------------------------------- diffing


    /**
     * A per-rule diff of two snapshots — <b>the artifact's real payload</b>: for every
     * {@code Core.Id} whose row set differs, how many rows it gained and lost and the first few of
     * each, so a finding-mover names every rule it moved.
     */
    public static List<String> diff(List<String> expected, List<String> actual)
    {
        SortedMap<String, SortedSet<String>> e = byRule(expected);
        SortedMap<String, SortedSet<String>> a = byRule(actual);
        Set<String> ids = new TreeSet<>(e.keySet());
        ids.addAll(a.keySet());
        List<String> out = new ArrayList<>();
        for (String id : ids)
        {
            SortedSet<String> er = e.getOrDefault(id, new TreeSet<>());
            SortedSet<String> ar = a.getOrDefault(id, new TreeSet<>());
            if (er.equals(ar))
            {
                continue;
            }
            List<String> lost = new ArrayList<>(er);
            lost.removeAll(ar);
            List<String> gained = new ArrayList<>(ar);
            gained.removeAll(er);
            StringBuilder b = new StringBuilder(id).append(": -").append(lost.size()).append(" +")
                    .append(gained.size());
            for (String l : lost.subList(0, Math.min(3, lost.size())))
            {
                b.append("\n    - ").append(l);
            }
            for (String g : gained.subList(0, Math.min(3, gained.size())))
            {
                b.append("\n    + ").append(g);
            }
            out.add(b.toString());
        }
        return out;
    }


    private static SortedMap<String, SortedSet<String>> byRule(List<String> lines)
    {
        SortedMap<String, SortedSet<String>> m = new TreeMap<>();
        for (String line : lines)
        {
            int tab = line.indexOf('\t');
            String id = tab < 0 ? line : line.substring(0, tab);
            m.computeIfAbsent(id, _ -> new TreeSet<>()).add(line);
        }
        return m;
    }

    // ---------------------------------------------------------------- rendering


    /** Renders the whole artifact. */
    public static String render(Snapshot s)
    {
        StringBuilder b = new StringBuilder();
        b.append("# Generated by FindingsSnapshot. DO NOT HAND-EDIT: regenerate with\n");
        b.append("#     scripts/gen-findings-snapshot.sh\n");
        b.append(
                "# — a one-shot behaviour census, NOT a step of the corpus regeneration sequence.\n");
        b.append(
                "# FindingsSnapshotDriftTest (opt-in: -Dcorej.findingsSnapshot=true) diffs this\n");
        b.append("# file against a fresh run and reports the difference per rule.\n");
        b.append("#\n");
        b.append(
                "# One line per finding OCCURRENCE (multiplicity kept, repeats suffixed #n on the\n");
        b.append(
                "# row column) plus one status line per rule x dataset execution. These are the\n");
        b.append(
                "# two things the .cdt count fixtures and the rulespec set comparison cannot see.\n");
        b.append("#\n");
        b.append("# Run: StudyValidationService over the MAIN-tree testdata/study (untracked; a\n");
        b.append("#      worktree sees a different testdata/), standard ").append(STANDARD)
                .append(' ').append(VERSION).append(", families ").append(FAMILIES).append(",\n");
        b.append("#      Define-XML wired, CDISC Library from the metadata store seeded off\n");
        b.append("#      CDISC_PICKLE_CACHE_DIR,\n");
        b.append("#      maxErrorsPerRule ").append(MAX_ERRORS_PER_RULE).append(
                " (status rows carry the exact total; rows beyond the cap are sampled away),\n");
        b.append("#      severityThreshold ").append(SEVERITY_THRESHOLD)
                .append(" (weakest rung - the census sees every check level a rule declares).\n");
        b.append("#\n");
        b.append("# Datasets: ").append(s.datasets()).append('\n');
        b.append("# Rules selected: ").append(s.rules()).append('\n');
        b.append("# Rows: ").append(s.rows().size()).append('\n');
        b.append("# Columns: ").append(COLUMNS).append('\n');
        for (Row r : s.rows())
        {
            b.append(r.line()).append('\n');
        }
        return b.toString();
    }


    /** The data lines of a rendered artifact (banner stripped), in file order. */
    public static List<String> parse(String text)
    {
        List<String> rows = new ArrayList<>();
        for (String line : text.split("\n", -1))
        {
            if (line.isEmpty() || line.startsWith(COMMENT))
            {
                continue;
            }
            rows.add(line);
        }
        return rows;
    }

    // ---------------------------------------------------------------- entry point


    /**
     * Rewrites the artifact.
     *
     * @param args
     *            optional single argument: the artifact path. Defaults to {@link #artifactFile()}.
     */
    public static void main(String[] args)
    {
        try
        {
            Path out = args.length > 0 ? Path.of(args[0]) : artifactFile();
            Snapshot s = snapshot();
            Files.writeString(out, render(s), StandardCharsets.UTF_8);
            System.err.printf("[findings-snapshot] %d rows (%d datasets, %d rules)%n",
                    s.rows().size(), s.datasets(), s.rules());
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

}
