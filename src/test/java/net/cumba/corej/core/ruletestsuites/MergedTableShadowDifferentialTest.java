package net.cumba.corej.core.ruletestsuites;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.exec.MergedTableShadow;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.corej.core.run.StudyValidationParams;
import net.cumba.corej.core.run.StudyValidationService;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.manager.local.LocalDataTableManager;
import net.cumba.datatable.merge.KeyColumn;
import net.cumba.datatable.merge.MergeKind;
import net.cumba.datatable.merge.MergeMeta;
import net.cumba.datatable.merge.MergeRequest;
import net.cumba.datatable.merge.MergedPart;
import net.cumba.datatable.merge.MergedTable;
import net.cumba.datatable.merge.MergedTables;
import net.cumba.datatable.merge.SuppMergeSpec;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.opentest4j.TestAbortedException;

/**
 * The verdict instrument of {@code PLAN-merged-evaluation-table} W2: every {@code .cdt} scenario of
 * every family and the whole {@code testdata/study} run, executed with the engine's
 * {@link MergedTableShadow} switched on. The shadow builds the merged evaluation view (keyed and
 * SUPP parts, QUALIFIED naming) beside today's join pipeline and compares every read the rule makes
 * through a {@code JoinLookup} or the {@code SuppPivot} with the merged column at the same (real
 * row, part row). Every divergence must fall into a <b>ruled movement</b> ({@link Movement}); any
 * other is UNEXPLAINED and fails.
 *
 * <p>
 * <b>Opt-in</b>, a plan instrument: {@code -Dcorej.mergedShadow=true}. {@code
 * -Dcorej.mergedShadow.study=false} skips the study arm; without it a missing fixture or metadata
 * store is a failure. {@code -Dcorej.mergedShadow.report=FILE} writes every divergence with its
 * class.
 * </p>
 *
 * <p>
 * <b>Sensitivity.</b> {@code -Dcorej.mergedShadow.plant=keyed|supp} hands the shadow a value view
 * whose row map of every keyed (or SUPP) part is shifted by one merged row; the run must then fail
 * with UNEXPLAINED divergences of that part. A green plant is a failed experiment, never a pass.
 * </p>
 */
@EnabledIfSystemProperty(named = "corej.mergedShadow", matches = "true")
class MergedTableShadowDifferentialTest
{

    private static final Path PROJECT_DIR = Path.of(System.getProperty("projectBasedir"));

    private static final Path RESOURCE_BASE = PROJECT_DIR
            .resolve("src/test/resources/net/cumba/corej/core/ruletestsuites");

    private static final List<AbstractRuleTestSuitesFactory> FACTORIES = List.of(
            new RuleTestSuitesCdiscFactoryTest(), new RuleTestSuitesDraftFactoryTest(),
            new RuleTestSuitesFdaFactoryTest(), new RuleTestSuitesPmdaFactoryTest());

    private static final List<String> STUDY_PACKAGES = List.of("cdisc-sdtmig-3-4",
            "draft-sdtmig-3-4", "fda-sdtmig-3-4", "pmda-sdtmig-3-4");

    /** The ruled movements a divergence may be; anything else is unexplained. */
    enum Movement
    {
        /**
         * §3.5.3 cases 2, 3, 5, 6, 9 (MET #15, OD-1, OD-2): the merged qualifier column carries a
         * {@code MERGE_ERROR}; today the first row wins. Lands in W3a as ERROR (QNAM).
         */
        SUPP_CASE_TABLE_ERROR,
        /**
         * §3.5.1 (OD-1, MET #18): the IG key carries {@code STUDYID} and {@code RDOMAIN}, which
         * today's pivot never reads — proven by rebuilding the view without them, which then agrees
         * with today's value. Lands in W3a.
         */
        SUPP_IG_KEY,
        /** Not a ruled movement. */
        UNEXPLAINED
    }


    /** One classified divergence. */
    private record Row(String arm, String where, @Nullable String rule, String dataset,
            MergedTableShadow.Divergence d, Movement movement)
    {
    }


    /** Collects the reports of one arm. */
    private static final class Collector implements MergedTableShadow.Observer
    {

        private final @Nullable MergeKind plant;

        private volatile String arm = "";

        private volatile String where = "";

        private boolean study;

        private final List<Row> rows = new ArrayList<>();

        private final Map<String, Long> notShadowed = new TreeMap<>();

        private long shadowed;

        private long reads;

        private long uncompared;

        /** arm → {shadowed executions, keyed reads, SUPP reads}. */
        private final Map<String, long[]> perArm = new TreeMap<>();

        private Collector(@Nullable MergeKind aPlant)
        {
            plant = aPlant;
        }


        @Override
        public synchronized void onExecution(MergedTableShadow.Report r)
        {
            if (r.notShadowed() != null)
            {
                notShadowed.merge(r.notShadowed(), 1L, Long::sum);
                return;
            }
            shadowed++;
            reads += r.readsCompared();
            long[] a = perArm.computeIfAbsent(arm, _ -> new long[3]);
            a[0]++;
            a[1] += r.keyedReads();
            a[2] += r.suppReads();
            uncompared += r.readsUncompared();
            for (MergedTableShadow.Divergence d : r.examples())
            {
                rows.add(new Row(arm, where, r.ruleId(), r.dataset(), d, classify(r, d)));
            }
        }


        @Override
        public IDataTable valueView(MergedTable merged)
        {
            MergeKind k = plant;
            return k == null ? merged : shifted(merged, k);
        }
    }

    /** The class of one divergence. */
    static Movement classify(MergedTableShadow.Report r, MergedTableShadow.Divergence d)
    {
        return switch (d.kind())
        {
        // W1-H1 (row mapping decided on identity, MergedTable.isRowMapped) was fixed by the W1
        // review fold and measured at 0 (W3a step 0): a keyed divergence is never a ruled movement.
        case ROW_SET, ROW_ABSENT, KEYED_VALUE, KEYED_TEXT, KEYED_MATCHED -> Movement.UNEXPLAINED;
        case SUPP_MERGE_ERROR -> Movement.SUPP_CASE_TABLE_ERROR;
        case SUPP_VALUE -> withoutStudyidAndRdomain(r, d) ? Movement.SUPP_IG_KEY
                : Movement.UNEXPLAINED;
        default -> Movement.UNEXPLAINED;
        };
    }


    /**
     * Whether the divergence is the IG key's doing: the view rebuilt from the data with the IG
     * default key ({@code STUDYID}, {@code RDOMAIN} included) still disagrees with today's value,
     * and rebuilt without {@code STUDYID} and {@code RDOMAIN} it agrees — the evidence of
     * {@link Movement#SUPP_IG_KEY}. Both rebuilds read the data, never the shadow's (possibly
     * sabotaged) view, so a corrupted row map cannot pass as this movement.
     */
    private static boolean withoutStudyidAndRdomain(MergedTableShadow.Report r,
            MergedTableShadow.Divergence d)
    {
        String strict = suppCell(r, d, SuppMergeSpec.DEFAULT_KEYS);
        String relaxed = suppCell(r, d, List.of(KeyColumn.of("USUBJID"), KeyColumn.of("POOLID"),
                KeyColumn.of("IDVAR"), KeyColumn.of("IDVARVAL")));
        return strict != null && relaxed != null && !strict.equals(d.today())
                && relaxed.equals(d.today());
    }


    /** The qualifier cell at the divergence's real row in a view rebuilt with {@code keys}. */
    private static @Nullable String suppCell(MergedTableShadow.Report r,
            MergedTableShadow.Divergence d, List<KeyColumn> keys)
    {
        MergedTable view;
        try
        {
            view = MergedTables.build(MergeRequest.builder().primary(r.primary())
                    .supp(SuppMergeSpec.builder().keys(keys).build())
                    .resolver(name -> r.resolver().resolve(name)).build());
        }
        catch (RuntimeException e)
        {
            return null;
        }
        int idx = view.getColumnIndex(d.column());
        if (idx < 0 || d.realRow() < 0 || d.realRow() >= view.getRowCount()
                || view.getMetaData().getColumn(idx).getMetaData(MergeMeta.MERGE_ERROR) != null)
        {
            return null;
        }
        IDataValue v = view.isSupplied(d.realRow(), idx) ? view.getDataValue(d.realRow(), idx)
                : DataValueSupport.getAsDataValue("", DataValueType.STRING);
        return v.getType() + ":" + v.getValue();
    }


    @Test
    void everyJoinAndSuppReadAgreesWithTheMergedView() throws IOException
    {
        String plantName = System.getProperty("corej.mergedShadow.plant");
        MergeKind plant = plantName == null ? null
                : MergeKind.valueOf(plantName.toUpperCase(java.util.Locale.ROOT));
        Collector c = new Collector(plant);
        MergedTableShadow.install(c);
        try
        {
            scenarios(c);
            c.study = !"false".equals(System.getProperty("corej.mergedShadow.study"));
            if (c.study)
            {
                study(c);
            }
        }
        finally
        {
            MergedTableShadow.install(null);
        }
        Map<MergedTableShadow.Kind, Map<Movement, Integer>> byKind = new EnumMap<>(
                MergedTableShadow.Kind.class);
        Set<String> unexplainedRules = new TreeSet<>();
        List<String> unexplained = new ArrayList<>();
        for (Row row : c.rows)
        {
            byKind.computeIfAbsent(row.d().kind(), _ -> new EnumMap<>(Movement.class))
                    .merge(row.movement(), 1, Integer::sum);
            if (row.movement() == Movement.UNEXPLAINED)
            {
                unexplainedRules.add(row.rule() + "|" + row.dataset());
                if (unexplained.size() < 40)
                {
                    unexplained.add(render(row));
                }
            }
        }
        StringBuilder arms = new StringBuilder();
        c.perArm.forEach((arm, a) -> arms.append(' ').append(arm).append("{shadowed=").append(a[0])
                .append(" keyed=").append(a[1]).append(" supp=").append(a[2]).append('}'));
        String summary = "[merged-shadow]" + arms + " shadowed=" + c.shadowed + " reads=" + c.reads
                + " uncompared=" + c.uncompared + " notShadowed=" + c.notShadowed + " divergences="
                + byKind + " unexplained rule×dataset=" + unexplainedRules.size();
        System.out.println(summary);
        String report = System.getProperty("corej.mergedShadow.report");
        if (report != null)
        {
            try (BufferedWriter w = Files.newBufferedWriter(Path.of(report), UTF_8))
            {
                for (Row row : c.rows)
                {
                    w.write(row.movement() + "\t" + render(row));
                    w.newLine();
                }
            }
        }
        // Non-vacuity floors: each arm must have shadowed executions and compared reads of BOTH
        // readers — a shadow that wrapped nothing agrees with everything.
        for (String arm : c.study ? List.of("cdt", "study") : List.of("cdt"))
        {
            long[] a = c.perArm.get(arm);
            assertTrue(a != null && a[0] > 0 && a[1] > 0 && a[2] > 0,
                    "arm " + arm + " compared no keyed or no SUPP read: " + summary);
        }
        assertTrue(unexplained.isEmpty(), () -> summary + "\n" + String.join("\n", unexplained));
    }


    private static String render(Row row)
    {
        MergedTableShadow.Divergence d = row.d();
        return row.arm() + "\t" + row.where() + "\t" + row.rule() + "\t" + row.dataset() + "\t"
                + d.kind() + "\t" + d.part() + "\t" + d.column() + "\treal=" + d.realRow()
                + "\tpart=" + d.partRow() + "\ttoday=" + d.today() + "\tmerged=" + d.merged();
    }


    private static void scenarios(Collector c) throws IOException
    {
        List<String> aborted = new ArrayList<>();
        int scenarios = 0;
        c.arm = "cdt";
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
                        c.where = rel;
                        scenarios++;
                        try
                        {
                            factory.evaluateScenario(file, s, rule, primary);
                        }
                        catch (TestAbortedException e)
                        {
                            aborted.add(rel + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
        assertTrue(scenarios > 9000, "only " + scenarios + " scenarios ran");
        assertTrue(aborted.isEmpty(),
                () -> aborted.size() + " scenario(s) ABORTED:\n" + String.join("\n", aborted));
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


    private static void study(Collector c) throws IOException
    {
        Path study = PROJECT_DIR.resolve("testdata").resolve("study");
        assertTrue(Files.isDirectory(study), "testdata/study is missing — pass"
                + " -Dcorej.mergedShadow.study=false to skip the study arm deliberately");
        Path store = MetadataStoreFixture.storeFile().orElseThrow(() -> new IOException(
                "CDISC_PICKLE_CACHE_DIR / -Dcdisc.pickle.cache.dir must name the pickle corpus"));
        c.arm = "study";
        c.where = "testdata/study";
        StudyValidationParams params = StudyValidationParams.builder()
                .manager(new LocalDataTableManager()).dataLibrary(study.toString())
                .defineXmlPath(study.resolve("define.xml").toString()).rulesPackages(STUDY_PACKAGES)
                .rulesDir(CorpusPaths.rules().toString()).ruleThreads(4).maxErrorsPerRule(50)
                .severityThreshold(net.cumba.datatable.report.Severity.INFO).build();
        String prev = System.getProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, store.toString());
        try
        {
            new StudyValidationService().validate(params);
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
     * The sensitivity control: the merged view with the row map of every part of {@code kind}
     * shifted by one merged row — the cell read for row {@code m} is row {@code m + 1}'s.
     */
    static IDataTable shifted(MergedTable merged, MergeKind kind)
    {
        boolean[] planted = new boolean[merged.getColumnCount()];
        for (MergedPart p : merged.parts())
        {
            if (p.kind() == kind)
            {
                for (int i = 0; i < p.columnCount(); i++)
                {
                    planted[p.firstColumn() + i] = true;
                }
            }
        }
        long rows = merged.getRowCount();
        return (IDataTable) Proxy.newProxyInstance(IDataTable.class.getClassLoader(), new Class<?>[]
        {
                IDataTable.class
        }, (proxy, method, args) ->
        {
            String n = method.getName();
            Object[] a = args;
            if (a != null && a.length == 2 && a[0] instanceof Long row
                    && a[1] instanceof Integer col && planted[col] && rows > 1
                    && ("getDataValue".equals(n) || "getValue".equals(n) || "isSupplied".equals(n)))
            {
                a = new Object[]
                {
                        (row + 1) % rows, col
                };
            }
            try
            {
                return method.invoke(merged, a);
            }
            catch (InvocationTargetException e)
            {
                throw e.getCause();
            }
        });
    }
}
