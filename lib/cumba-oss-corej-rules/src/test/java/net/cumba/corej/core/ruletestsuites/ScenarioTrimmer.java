package net.cumba.corej.core.ruletestsuites;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.exec.ScopeMatcher;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.CheckConditionNot;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario.Verdict;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * One-shot driver that row-trims single-domain scenario files to at most {@value #MAX_ROWS} rows
 * each, verifying after each trim that the rule's verdict still matches the scenario's declared
 * {@code expect=…}. Trims are reverted individually when verification fails, so a failed trim never
 * corrupts the tree.
 *
 * <p>
 * Activated only when {@code -Dtrim.scenarios=true} is passed to the test runner. The class name
 * intentionally does NOT match the default surefire pattern ({@code *Test.java}), so ordinary runs
 * skip it even when the flag happens to be set.
 * </p>
 *
 * <p>
 * Run with:
 *
 * <pre>
 * mvn -P main -pl lib/corej-cdisc-rules test \
 *     -Dtest=ScenarioTrimmer -Dtrim.scenarios=true
 * </pre>
 *
 * <p>
 * &#9888;&#9888; <b>This tool rewrites {@code .cdt} fixtures in place, across the whole
 * rule-scenario corpus.</b> Commit before running it &mdash; a clean tree is the only undo &mdash;
 * and diff the result before keeping anything.
 * </p>
 */
// Test fixture / helper exposing LinkedHashMap/LinkedHashSet for ordered iteration.
@SuppressWarnings("NonApiType")
@EnabledIfSystemProperty(named = "trim.scenarios", matches = "true")
class ScenarioTrimmer
{

    private static final int MAX_ROWS = 20;

    /*
     * Anchored on projectBasedir, never on the CWD: the root pom pins surefire's
     * <workingDirectory> to ${project.build.directory}/test-cwd for every module, and its own
     * comment states the convention -- a test that needs the module dir anchors on the
     * projectBasedir / repoRoot system properties instead. corej-cdisc-rules happens to override
     * workingDirectory back to ${project.basedir}, but that override is documented as being for
     * the corpus tests and could be removed; a bare relative path would then silently resolve to
     * an empty directory and this tool would reach zero files, which is exactly the state it was
     * found in before it moved here.
     */
    /*
     * ⚠ Resolved through a method, not a static initialiser. `projectBasedir` is supplied by
     * surefire (root pom systemPropertyVariables) and by pitest, but NOT by a bare IDE / JUnit
     * launcher run -- and Path.of(null, ...) throws. A static initialiser would turn that into
     * an ExceptionInInitializerError for the whole class, taking ScenarioTrimmerQualifiedScopeTest
     * (13 tests, unconditional, and the only reason this class is loaded at all under a normal
     * run) down with it, naming a class the developer never asked to run.
     */
    private static Path projectBasedir()
    {
        String base = System.getProperty("projectBasedir");
        if (base == null)
        {
            throw new IllegalStateException("The 'projectBasedir' system property is not set. "
                    + "ScenarioTrimmer resolves the corpus and the rule packages against the "
                    + "MODULE directory; surefire sets this property, a bare IDE run does not. "
                    + "Run it with: mvn -P main -pl lib/corej-cdisc-rules test "
                    + "-Dtest=ScenarioTrimmer -Dtrim.scenarios=true");
        }
        return Path.of(base);
    }


    private static Path resourceRoot()
    {
        return projectBasedir().resolve("src/test/resources/net/cumba/corej/core/ruletestsuites");
    }


    private static Path rulesDir()
    {
        return CorpusPaths.rules();
    }

    private static final Pattern FENCE = Pattern.compile("-{3,}");

    private static Map<String, Rule> rulesByCoreId;

    @BeforeAll
    static void loadRules() throws IOException
    {
        rulesByCoreId = new HashMap<>();
        mergeUnion("SDTMIG", "3.4");
        mergeUnion("ADaMIG", "1.3");
    }


    /**
     * Merges every family package shipped for one {@code (standard, version)} -- the union
     * {@code packages.json} resolves, i.e. {@code rules-{cdisc,core,fda,pmda,draft}-<std>-<ver>}.
     * Resolution is by manifest, never by file name: both standard names ({@code SENDIG-DART}) and
     * versions ({@code 3-1-1}) contain dashes, so a package file name is not reversibly parseable.
     */
    private static void mergeUnion(String aStandard, String aVersion) throws IOException
    {
        RulePackage pkg = RulePackageLoader.loadFamilyUnion(rulesDir(), aStandard, aVersion);
        Map<String, Rule> rules = pkg.getRules();
        if (rules == null)
        {
            return;
        }
        for (Rule r : rules.values())
        {
            String cid = r.effectiveId();
            rulesByCoreId.putIfAbsent(cid, r);
        }
    }


    @Test
    void trimSingleDomainScenariosToMaxRows() throws IOException
    {
        assertTrue(Files.isDirectory(resourceRoot()), "resource root missing: " + resourceRoot());

        int scanned = 0;
        int eligible = 0;
        int skippedNotSingleDomain = 0;
        int skippedMultiDataset = 0;
        int skippedRuleUnknown = 0;
        int skippedAlreadySmall = 0;
        int trimmed = 0;
        int reverted = 0;
        List<String> revertedFiles = new ArrayList<>();

        List<Path> files;
        try (Stream<Path> walk = Files.walk(resourceRoot()))
        {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".cdt"))
                    .filter(ScenarioTrimmer::hasShebang).sorted().toList();
        }

        for (Path file : files)
        {
            scanned++;
            RuleTestScenario scenario;
            try
            {
                scenario = RuleTestCdt.load(file);
            }
            catch (RuntimeException ex)
            {
                System.err.println("skip (parse failed): " + file + " — " + ex.getMessage());
                continue;
            }

            Rule rule = rulesByCoreId.get(scenario.getCoreId());
            if (rule == null)
            {
                skippedRuleUnknown++;
                continue;
            }
            if (!isSingleDatasetRule(rule))
            {
                skippedNotSingleDomain++;
                continue;
            }
            if (scenario.getDatasets().size() != 1)
            {
                skippedMultiDataset++;
                continue;
            }
            OverlayDataTable primary = scenario.getDatasets().get(0);
            if (primary.getRowCount() <= MAX_ROWS)
            {
                skippedAlreadySmall++;
                continue;
            }
            eligible++;

            // Build trimmed content in memory. Only commit to disk after verification.
            String original = Files.readString(file, UTF_8);
            String candidate = trimFirstDatasetRows(original, MAX_ROWS);
            if (candidate.equals(original))
            {
                // Defensive — shouldn't happen since primary.getRowCount() > MAX_ROWS.
                skippedAlreadySmall++;
                continue;
            }

            // Verify: parse the candidate, execute the rule, compare verdict.
            Verdict observed;
            try
            {
                RuleTestScenario trimmedScenario = RuleTestCdt.parse(candidate, file.toString());
                // A parsed scenario always has a primary table (RuleTestCdt.parse rejects a
                // domain that matches no dataset); if that invariant ever breaks, the NPE is
                // caught below and forces the revert path, which is the desired behaviour.
                OverlayDataTable primaryTbl = Objects.requireNonNull(trimmedScenario.primaryTable(),
                        "parsed scenario has no primary table");
                RuleExecutionResult result = RuleRunner.execute(rule, primaryTbl,
                        trimmedScenario.resolver(), trimmedScenario.getDomain(),
                        trimmedScenario.getLibrary());
                observed = verdictOf(result);
            }
            catch (RuntimeException ex)
            {
                observed = null; // treat as failure; forces revert path
                System.err.println("verify parse/exec failed: " + file + " — "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }

            if (observed == scenario.getExpect())
            {
                Files.writeString(file, candidate, UTF_8);
                trimmed++;
            }
            else
            {
                reverted++;
                revertedFiles.add(resourceRoot().relativize(file) + "  (verdict=" + observed
                        + " vs expected=" + scenario.getExpect() + ")");
            }
        }

        System.out.println();
        System.out.println("=== ScenarioTrimmer summary (MAX_ROWS=" + MAX_ROWS + ") ===");
        System.out.println("  scanned                : " + scanned);
        System.out.println("  rule unknown           : " + skippedRuleUnknown);
        System.out.println("  multi-include / join   : " + skippedNotSingleDomain);
        System.out.println("  multi-dataset file     : " + skippedMultiDataset);
        System.out.println("  already small (<=" + MAX_ROWS + ")    : " + skippedAlreadySmall);
        System.out.println("  eligible for trimming  : " + eligible);
        System.out.println("  trimmed + verified     : " + trimmed);
        System.out.println("  reverted on verify fail: " + reverted);
        if (!revertedFiles.isEmpty())
        {
            System.out.println("  --- reverted files ---");
            for (String f : revertedFiles)
            {
                System.out.println("    " + f);
            }
        }
    }

    // ---- helpers --------------------------------------------------------------


    @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop")
    private static boolean hasShebang(Path aPath)
    {
        try
        {
            for (String line : Files.readAllLines(aPath, UTF_8))
            {
                String t = line.replace("\uFEFF", "").trim();
                if (t.isEmpty())
                {
                    continue;
                }
                return RuleTestCdt.SHEBANG.equals(t);
            }
            return false;
        }
        catch (IOException ex)
        {
            return false;
        }
    }


    /**
     * True iff the rule evaluates against a single scenario-file dataset with no cross-dataset
     * joins — i.e. there is no {@code Match_Datasets} clause. The {@code Scope.Domains.Include}
     * size does not matter: multi-include just means the rule applies to any of several domain
     * names, but each invocation still runs against a single primary dataset. If such a rule
     * actually depended on sibling data (via Operations that peek at the resolver's inventory), the
     * verify step in the trimmer catches the mismatch and reverts.
     */
    private static boolean isSingleDatasetRule(Rule aRule)
    {
        return aRule.getMatchDatasets() == null || aRule.getMatchDatasets().isEmpty();
    }


    /**
     * Trim the first dataset in {@code aContent} to at most {@code aMaxRows} data rows, preserving
     * the directive prelude, dataset header, column declarations, fences, blank lines, and inline
     * comments. A "data row" is any line between the opening and closing fence that is not blank
     * and does not start with {@code #}.
     */
    static String trimFirstDatasetRows(String aContent, int aMaxRows)
    {
        return trimDatasetRows(aContent, aMaxRows, true);
    }


    /**
     * Like {@link #trimFirstDatasetRows} but trims <em>every</em> dataset block in the content to
     * at most {@code aMaxRows} data rows. Used by the multi-dataset trim pass so sibling blocks
     * captured from shared-study data (e.g. ~60k LB rows in a CORE-000358 scenario) get capped too.
     */
    static String trimAllDatasetsRows(String aContent, int aMaxRows)
    {
        return trimDatasetRows(aContent, aMaxRows, false);
    }


    @SuppressWarnings("PMD.EmptyControlStatement")
    private static String trimDatasetRows(String aContent, int aMaxRows, boolean aFirstOnly)
    {
        String[] lines = aContent.split("\\R", 0);
        StringBuilder out = new StringBuilder(aContent.length());
        boolean inData = false;
        boolean firstDatasetDone = false;
        String openFence = null;
        int rowCount = 0;

        for (int i = 0; i < lines.length; i++)
        {
            String line = lines[i];
            String t = line.trim();
            boolean keep = true;

            if (!inData)
            {
                if ((!aFirstOnly || !firstDatasetDone) && FENCE.matcher(t).matches())
                {
                    inData = true;
                    openFence = t;
                    rowCount = 0;
                }
            }
            else
            {
                if (t.equals(openFence))
                {
                    inData = false;
                    openFence = null;
                    firstDatasetDone = true;
                }
                else if (t.isEmpty() || t.startsWith("#"))
                {
                    // preserve blank / comment lines
                }
                else
                {
                    rowCount++;
                    if (rowCount > aMaxRows)
                    {
                        keep = false;
                    }
                }
            }

            if (keep)
            {
                out.append(line);
                if (i < lines.length - 1)
                {
                    out.append('\n');
                }
            }
        }
        return out.toString();
    }


    /**
     * Pass that caps every dataset block in a multi-dataset scenario to {@value #MAX_ROWS} rows.
     * Targets scenarios whose rule uses {@code Match_Datasets}, where sibling blocks captured from
     * the shared study can run to tens of thousands of rows (e.g. CORE-000358 with ~60k LB rows).
     * Single-dataset scenarios are handled by {@link #trimSingleDomainScenariosToMaxRows()} and
     * skipped here.
     */
    @Test
    void trimMultiDatasetScenariosToMaxRows() throws IOException
    {
        assertTrue(Files.isDirectory(resourceRoot()), "resource root missing: " + resourceRoot());

        int scanned = 0;
        int eligible = 0;
        int trimmed = 0;
        int reverted = 0;
        int skippedSingle = 0;
        int skippedSmall = 0;
        List<String> revertedFiles = new ArrayList<>();

        List<Path> files;
        try (Stream<Path> walk = Files.walk(resourceRoot()))
        {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".cdt"))
                    .filter(ScenarioTrimmer::hasShebang).sorted().toList();
        }

        for (Path file : files)
        {
            scanned++;
            RuleTestScenario scenario;
            try
            {
                scenario = RuleTestCdt.load(file);
            }
            catch (RuntimeException ex)
            {
                continue;
            }
            Rule rule = rulesByCoreId.get(scenario.getCoreId());
            if (rule == null || scenario.getDatasets().size() < 2)
            {
                skippedSingle++;
                continue;
            }
            long maxRows = scenario.getDatasets().stream().mapToLong(OverlayDataTable::getRowCount)
                    .max().orElse(0L);
            if (maxRows <= MAX_ROWS)
            {
                skippedSmall++;
                continue;
            }
            eligible++;

            String original = Files.readString(file, UTF_8);
            String candidate = trimAllDatasetsRows(original, MAX_ROWS);
            if (candidate.equals(original))
            {
                skippedSmall++;
                continue;
            }

            Verdict observed;
            try
            {
                RuleTestScenario trimmedScenario = RuleTestCdt.parse(candidate, file.toString());
                // A parsed scenario always has a primary table (RuleTestCdt.parse rejects a
                // domain that matches no dataset); if that invariant ever breaks, the NPE is
                // caught below and forces the revert path, which is the desired behaviour.
                OverlayDataTable primaryTbl = Objects.requireNonNull(trimmedScenario.primaryTable(),
                        "parsed scenario has no primary table");
                RuleExecutionResult result = RuleRunner.execute(rule, primaryTbl,
                        trimmedScenario.resolver(), trimmedScenario.getDomain(),
                        trimmedScenario.getLibrary());
                observed = verdictOf(result);
            }
            catch (RuntimeException ex)
            {
                observed = null;
                System.err.println("multi-trim verify failed: " + file + " — "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }

            if (observed == scenario.getExpect())
            {
                Files.writeString(file, candidate, UTF_8);
                trimmed++;
            }
            else
            {
                reverted++;
                revertedFiles.add(resourceRoot().relativize(file) + "  (verdict=" + observed
                        + " vs expected=" + scenario.getExpect() + ")");
            }
        }

        System.out.println();
        System.out.println("=== Multi-dataset trim summary (MAX_ROWS=" + MAX_ROWS + ") ===");
        System.out.println("  scanned                : " + scanned);
        System.out.println("  single-dataset (skip)  : " + skippedSingle);
        System.out.println("  already small (skip)   : " + skippedSmall);
        System.out.println("  eligible for trimming  : " + eligible);
        System.out.println("  trimmed + verified     : " + trimmed);
        System.out.println("  reverted on verify fail: " + reverted);
        if (!revertedFiles.isEmpty())
        {
            System.out.println("  --- reverted files ---");
            for (String f : revertedFiles)
            {
                System.out.println("    " + f);
            }
        }
    }

    // ---- Smart trim for invalid-scenario reverts -----------------------------

    /** Rows from the start of the dataset that are always kept (context / identity). */
    private static final int SMART_HEAD = 10;

    /** Context window around each violation row (on each side). */
    private static final int SMART_CONTEXT = 2;

    /**
     * Second pass, targeted at {@code expect=VIOLATION} scenarios that still have more than
     * {@value #MAX_ROWS} rows (i.e. the first-pass reverts whose violating row sits past index
     * {@value #MAX_ROWS}). Strategy:
     * <ol>
     * <li>Execute the rule on the full dataset.</li>
     * <li>Collect the 0-based row indexes the engine reported as violations.</li>
     * <li>Build the keep-set from rows {@code [0, SMART_HEAD)} plus a {@code ±SMART_CONTEXT}-row
     * window around each violation index.</li>
     * <li>Emit a trimmed file keeping only those rows. Verify the rule still fires, then commit;
     * otherwise revert.</li>
     * </ol>
     * <p>
     * Only touches single-dataset rules (same filter as the first pass) with
     * {@code expect=VIOLATION}. Valid scenarios remain untouched because the engine returns no row
     * indexes to anchor the trim on.
     * </p>
     */
    @Test
    void smartTrimInvalidRevertsFromViolationRows() throws IOException
    {
        assertTrue(Files.isDirectory(resourceRoot()), "resource root missing: " + resourceRoot());

        int scanned = 0;
        int eligible = 0;
        int trimmed = 0;
        int reverted = 0;
        int noViolations = 0;
        List<String> revertedFiles = new ArrayList<>();
        List<String> trimmedFiles = new ArrayList<>();

        List<Path> files;
        try (Stream<Path> walk = Files.walk(resourceRoot()))
        {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".cdt"))
                    .filter(ScenarioTrimmer::hasShebang).sorted().toList();
        }

        for (Path file : files)
        {
            scanned++;
            RuleTestScenario scenario;
            try
            {
                scenario = RuleTestCdt.load(file);
            }
            catch (RuntimeException ex)
            {
                continue;
            }

            if (scenario.getExpect() != Verdict.VIOLATION)
            {
                continue;
            }
            Rule rule = rulesByCoreId.get(scenario.getCoreId());
            if (rule == null || !isSingleDatasetRule(rule))
            {
                continue;
            }
            if (scenario.getDatasets().size() != 1)
            {
                continue;
            }
            OverlayDataTable primary = scenario.getDatasets().get(0);
            long rowCount = primary.getRowCount();
            if (rowCount <= MAX_ROWS)
            {
                continue;
            }
            eligible++;

            // Execute the rule on the full dataset; collect violation row indexes.
            RuleExecutionResult fullResult;
            try
            {
                fullResult = RuleRunner.execute(rule, primary, scenario.resolver(),
                        scenario.getDomain(), scenario.getLibrary());
            }
            catch (RuntimeException ex)
            {
                System.err.println("skip (full-exec failed): " + file + " — " + ex.getMessage());
                continue;
            }
            if (!fullResult.hasViolations())
            {
                noViolations++;
                continue;
            }

            // Build keep-set: rows [0..SMART_HEAD) ∪ {v - SMART_CONTEXT .. v + SMART_CONTEXT}.
            TreeSet<Long> keepSet = new TreeSet<>();
            for (long r = 0; r < SMART_HEAD && r < rowCount; r++)
            {
                keepSet.add(r);
            }
            for (Violation v : fullResult.getViolations())
            {
                long idx = v.getRow(); // 0-based
                long from = Math.max(0, idx - SMART_CONTEXT);
                long to = Math.min(rowCount - 1, idx + SMART_CONTEXT);
                for (long r = from; r <= to; r++)
                {
                    keepSet.add(r);
                }
            }

            String original = Files.readString(file, UTF_8);
            String candidate = keepSelectedRows(original, keepSet);
            if (candidate.equals(original))
            {
                // Keep-set covers every row; nothing to do.
                continue;
            }

            // Verify: parse + execute + compare verdict.
            Verdict observed;
            try
            {
                RuleTestScenario trimmedScenario = RuleTestCdt.parse(candidate, file.toString());
                // A parsed scenario always has a primary table (RuleTestCdt.parse rejects a
                // domain that matches no dataset); if that invariant ever breaks, the NPE is
                // caught below and forces the revert path, which is the desired behaviour.
                OverlayDataTable primaryTbl = Objects.requireNonNull(trimmedScenario.primaryTable(),
                        "parsed scenario has no primary table");
                RuleExecutionResult verifyResult = RuleRunner.execute(rule, primaryTbl,
                        trimmedScenario.resolver(), trimmedScenario.getDomain(),
                        trimmedScenario.getLibrary());
                observed = verdictOf(verifyResult);
            }
            catch (RuntimeException ex)
            {
                observed = null;
                System.err.println("verify parse/exec failed: " + file + " — " + ex.getMessage());
            }

            if (observed == Verdict.VIOLATION)
            {
                Files.writeString(file, candidate, UTF_8);
                trimmed++;
                trimmedFiles.add(resourceRoot().relativize(file) + "  (kept " + keepSet.size()
                        + " of " + rowCount + " rows, " + fullResult.getViolationCount()
                        + " violation(s))");
            }
            else
            {
                reverted++;
                revertedFiles
                        .add(resourceRoot().relativize(file) + "  (observed=" + observed + ")");
            }
        }

        System.out.println();
        System.out.println("=== Smart trim summary (HEAD=" + SMART_HEAD + ", CONTEXT=±"
                + SMART_CONTEXT + ") ===");
        System.out.println("  scanned                  : " + scanned);
        System.out.println("  eligible (invalid, >" + MAX_ROWS + " rows) : " + eligible);
        System.out.println("  no violations reported   : " + noViolations);
        System.out.println("  trimmed + verified       : " + trimmed);
        System.out.println("  reverted on verify fail  : " + reverted);
        if (!trimmedFiles.isEmpty())
        {
            System.out.println("  --- trimmed files ---");
            for (String f : trimmedFiles)
            {
                System.out.println("    " + f);
            }
        }
        if (!revertedFiles.isEmpty())
        {
            System.out.println("  --- reverted files ---");
            for (String f : revertedFiles)
            {
                System.out.println("    " + f);
            }
        }
    }


    /**
     * Emit {@code aContent} with only the data rows at the given 0-based indexes kept. Directive
     * prelude, dataset header, column declarations, fences, blank lines, and inline comments are
     * preserved as-is. Row counting increments only for lines between the opening and closing fence
     * of the first dataset that are non-blank and not comment lines.
     */
    @SuppressWarnings("PMD.EmptyControlStatement")
    static String keepSelectedRows(String aContent, TreeSet<Long> aKeepIndexes)
    {
        String[] lines = aContent.split("\\R", 0);
        StringBuilder out = new StringBuilder(aContent.length());
        boolean inData = false;
        boolean firstDatasetDone = false;
        String openFence = null;
        long rowIdx = -1;

        for (int i = 0; i < lines.length; i++)
        {
            String line = lines[i];
            String t = line.trim();
            boolean keep = true;

            if (!inData)
            {
                if (!firstDatasetDone && FENCE.matcher(t).matches())
                {
                    inData = true;
                    openFence = t;
                    rowIdx = -1;
                }
            }
            else
            {
                if (t.equals(openFence))
                {
                    inData = false;
                    openFence = null;
                    firstDatasetDone = true;
                }
                else if (t.isEmpty() || t.startsWith("#"))
                {
                    // keep comments / blanks inside the data region as-is
                }
                else
                {
                    rowIdx++;
                    if (!aKeepIndexes.contains(rowIdx))
                    {
                        keep = false;
                    }
                }
            }

            if (keep)
            {
                out.append(line);
                if (i < lines.length - 1)
                {
                    out.append('\n');
                }
            }
        }
        return out.toString();
    }

    // ---- Column trimming for readability -------------------------------------

    /** Always-kept identity columns if present in the table. */
    private static final Set<String> ALWAYS_KEEP = Set.of("STUDYID", "USUBJID", "DOMAIN");

    /**
     * Wildcard dataset bucket: reference could resolve against the primary table OR any sibling
     * that happens to carry the column. At emission time we keep the column in every dataset where
     * it exists.
     */
    private static final String WILDCARD_DS = "*";

    /**
     * Operation operators that return a dynamic list of column names at runtime. The result
     * {@code $var} may be used as a subsequent operation's {@code group} or as a list-valued
     * {@code value} in the Check — in either case the engine iterates columns whose names the
     * walker cannot enumerate statically.
     */
    private static final Set<String> DYNAMIC_COLUMN_ENUM_OPERATORS = Set.of(
            "get_dataset_filtered_variables", "get_column_order_from_dataset",
            "get_column_order_from_library", "variable_count");

    /**
     * Check operators that read a column family by suffix (e.g. {@code VAL, VAL1, VAL2,
     * …}) rather than the single column named in {@code leaf.name}. Scenarios hosting these cannot
     * be statically reasoned about.
     */
    private static final Set<String> DYNAMIC_COLUMN_FAMILY_OPERATORS = Set
            .of("inconsistent_enumerated_columns", "has_same_values");

    /**
     * Third pass: drop columns the rule never reads, in every dataset block (primary and any
     * {@code Match_Datasets} siblings). The rule's {@code Check}, {@code Precondition},
     * {@code Operations}, {@code Outcome.Output_Variables}, {@code Grouping_Variables},
     * {@code Match_Datasets.Keys}, and {@code Requirements.Variables} are walked for column
     * references. {@code --}/{@code **} wildcards expand against the scenario's domain prefix.
     * Identity columns ({@code STUDYID}, {@code USUBJID}, {@code DOMAIN}) are always kept when
     * present.
     *
     * <p>
     * Per-dataset keep sets: a dot-qualified reference like {@code DM.DTHDTC} keeps {@code DTHDTC}
     * in the {@code DM} block only; an unqualified reference (the common case) keeps the column in
     * every block where it exists, mirroring the engine's {@code resolveFromJoinedDatasets}
     * fallback.
     * </p>
     *
     * <p>
     * Rule types that iterate over every variable ({@code Variable Metadata Check},
     * {@code Value Check with Variable Metadata}, {@code Variable Metadata Check against
     * Library Metadata}, {@code Define Item Metadata Check against Library Metadata}) are skipped —
     * the walker can't see which columns drive the verdict.
     * </p>
     *
     * <p>
     * Unlike the row trims, this pass works via {@link OverlayDataTable#removeColumn} +
     * {@link RuleTestCdt#write}, so the emitted file is a normalised round-trip.
     * </p>
     */
    @Test
    void trimColumnsForScenarios() throws IOException
    {
        assertTrue(Files.isDirectory(resourceRoot()), "resource root missing: " + resourceRoot());

        int scanned = 0;
        int eligible = 0;
        int trimmed = 0;
        int reverted = 0;
        int nothingToTrim = 0;
        int skippedMetadataType = 0;
        int skippedHazard = 0;
        int skippedWouldLoseContent = 0;
        long totalRemoved = 0;
        List<String> revertedFiles = new ArrayList<>();

        List<Path> files;
        try (Stream<Path> walk = Files.walk(resourceRoot()))
        {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".cdt"))
                    .filter(ScenarioTrimmer::hasShebang).sorted().toList();
        }

        for (Path file : files)
        {
            scanned++;
            RuleTestScenario scenario;
            try
            {
                scenario = RuleTestCdt.load(file);
            }
            catch (RuntimeException ex)
            {
                continue;
            }

            // ⚠⚠ COMMENT-LOSS GUARD. Unlike the three ROW trims, which splice the original
            // text and write it back verbatim, this pass rewrites through
            // RuleTestCdt.write -- a NORMALISED round trip. RuleTestScenario has no field for
            // plain "# " comments (the parser at RuleTestCdt.parse treats a "#" followed by a
            // SPACE as a comment and skips it; "#" followed by a word is a directive), so any
            // such line is silently dropped on write.
            //
            // That matters now in a way it did not before 2026-09-01: this tool used to resolve
            // its resource root against a working directory that does not exist and reached ZERO
            // files. It now reaches the whole 8 883-file corpus, of which 2 669 carry a
            // "# Rule:Description:" line. Trimming a column out of one of those would delete
            // its description with no diagnostic.
            //
            // Skipping is the conservative half: a scenario that keeps an untrimmed column is
            // merely larger than necessary, whereas one that loses its description is damaged
            // and the loss is invisible in a green run. Lift this only together with comment
            // retention in RuleTestScenario / RuleTestCdt.writeTo.
            if (roundTripWouldLoseContent(file))
            {
                skippedWouldLoseContent++;
                continue;
            }

            Rule rule = rulesByCoreId.get(scenario.getCoreId());
            if (rule == null)
            {
                continue;
            }
            // A rule whose inferred evaluation domain carries the VAR cursor iterates every
            // variable (or every cell) of the dataset; the walker cannot tell which columns
            // actually drive the verdict for these, so the scenario is left alone.
            if (rule.getEvaluationDomain() != null && rule.getEvaluationDomain().varCursor())
            {
                skippedMetadataType++;
                continue;
            }
            if (hasDynamicColumnAccess(rule))
            {
                skippedHazard++;
                continue;
            }
            if (scenario.getDatasets().isEmpty())
            {
                continue;
            }

            String primaryDomain = scenario.getDomain();
            ColumnKeepSets buckets = collectReferencedColumns(rule, primaryDomain);

            // Per-dataset keep sets. Plan which columns to remove per block before
            // mutating anything.
            Map<OverlayDataTable, List<String>> removalsPerTable = new LinkedHashMap<>();
            int totalOrig = 0;
            int totalKeep = 0;
            for (OverlayDataTable t : scenario.getDatasets())
            {
                DataTableColumnMeta[] origCols = t.getMetaData().getColumns();
                if (origCols.length == 0)
                {
                    continue;
                }
                totalOrig += origCols.length;
                String dsName = t.getMetaData().getName();
                String dsUp = dsName != null ? dsName.toUpperCase(Locale.ROOT) : "";

                Set<String> keep = new LinkedHashSet<>();
                List<String> remove = new ArrayList<>();
                for (DataTableColumnMeta col : origCols)
                {
                    String name = col.getName();
                    if (ALWAYS_KEEP.contains(name.toUpperCase(Locale.ROOT))
                            || buckets.keeps(dsUp, name) || buckets.keeps(WILDCARD_DS, name))
                    {
                        keep.add(name);
                    }
                    else
                    {
                        remove.add(name);
                    }
                }
                totalKeep += keep.size();
                if (keep.isEmpty())
                {
                    // Leave this block alone — emitting a zero-column table wouldn't
                    // round-trip cleanly.
                    removalsPerTable.put(t, List.of());
                }
                else
                {
                    removalsPerTable.put(t, remove);
                }
            }

            if (totalOrig == 0 || removalsPerTable.values().stream().allMatch(List::isEmpty))
            {
                nothingToTrim++;
                continue;
            }
            eligible++;

            // Apply removals.
            for (Map.Entry<OverlayDataTable, List<String>> entry : removalsPerTable.entrySet())
            {
                OverlayDataTable t = entry.getKey();
                for (String c : entry.getValue())
                {
                    t.removeColumn(c);
                }
            }

            // Verify: execute the rule on the trimmed tables.
            Verdict observed;
            String serialized = null;
            try
            {
                // A loaded single-domain scenario always has a primary table (RuleTestCdt
                // validates the domain matches a dataset); if that ever fails, the NPE is
                // caught below and treated as a verification failure.
                // ⚠ Verify the SERIALISED form, not the in-memory scenario. This pass writes a
                // re-serialisation, so verifying the object in hand would attest to something
                // other than what lands on disk -- anything RuleTestCdt.write drops would be
                // invisible. Re-parsing first closes that gap for every field that affects the
                // verdict, present and future. (Fields that do NOT affect the verdict are the
                // guard's job; see roundTripWouldLoseContent.)
                serialized = RuleTestCdt.toString(scenario);
                RuleTestScenario reparsed = RuleTestCdt.parse(serialized, file.toString());
                OverlayDataTable primary = Objects.requireNonNull(reparsed.primaryTable(),
                        "scenario has no primary table");
                RuleExecutionResult result = RuleRunner.execute(rule, primary, reparsed.resolver(),
                        reparsed.getDomain(), reparsed.getLibrary());
                observed = verdictOf(result);
            }
            catch (RuntimeException ex)
            {
                observed = null;
            }

            long removedCount = removalsPerTable.values().stream().mapToLong(List::size).sum();
            if (observed == scenario.getExpect() && serialized != null)
            {
                // Write the exact text that was verified above.
                Files.writeString(file, serialized, UTF_8);
                trimmed++;
                totalRemoved += removedCount;
            }
            else
            {
                reverted++;
                revertedFiles.add(resourceRoot().relativize(file) + "  (removed=" + removedCount
                        + ", kept=" + totalKeep + ", observed=" + observed + ")");
            }
        }

        System.out.println();
        System.out.println("=== Column trim summary ===");
        System.out.println("  scanned                    : " + scanned);
        System.out.println("  skipped (metadata ruletype): " + skippedMetadataType);
        System.out.println("  skipped (dynamic columns)  : " + skippedHazard);
        System.out.println("  skipped (lossy round trip)  : " + skippedWouldLoseContent);
        System.out.println("  nothing to trim            : " + nothingToTrim);
        System.out.println("  eligible for trim          : " + eligible);
        System.out.println("  trimmed + verified         : " + trimmed);
        System.out.println("  reverted on verify fail    : " + reverted);
        System.out.println("  total columns removed      : " + totalRemoved);
        if (!revertedFiles.isEmpty())
        {
            System.out.println("  --- reverted files ---");
            for (String f : revertedFiles)
            {
                System.out.println("    " + f);
            }
        }
    }


    /**
     * The verdict a scenario would have to declare for this result. A rule that did not run maps to
     * {@link Verdict#SKIPPED} rather than to {@code NO_VIOLATION}: without that distinction a trim
     * that silently gated the rule off would still "verify" against an {@code expect=noViolation}
     * scenario and be written to disk — the exact fixture-rot path this trimmer is supposed to
     * protect against.
     */
    private static @Nullable Verdict verdictOf(RuleExecutionResult aResult)
    {
        if (aResult.isError())
        {
            // An ERROR matches NO declared verdict — returning SKIPPED here would let a trim
            // that breaks the rule "verify" against an expect=skipped scenario and be written
            // to disk (review F1). null forces the revert path, like a thrown exception does.
            return null;
        }
        if (aResult.isSkipped())
        {
            return Verdict.SKIPPED;
        }
        return aResult.hasViolations() ? Verdict.VIOLATION : Verdict.NO_VIOLATION;
    }


    /**
     * Walk every rule site that can name a column and return per-dataset keep sets.
     * <ul>
     * <li>Dot-qualified references ({@code DM.DTHDTC}) go to the named dataset bucket.</li>
     * <li>Unqualified references go to the wildcard bucket ({@link #WILDCARD_DS}) — the caller
     * keeps the column in every block where it exists, matching the engine's unqualified fallback
     * via {@code resolveFromJoinedDatasets}.</li>
     * <li>{@code --}/{@code **} wildcards expand against {@code aPrimaryDomain} on both the
     * dataset-name side ({@code SUPP--} → {@code SUPPAE}) and the column side ({@code --STDTC} →
     * {@code AESTDTC}).</li>
     * <li>{@code $}-prefixed operation results and built-in meta names ({@code dataset_name},
     * {@code variable_value}, …) are skipped.</li>
     * <li>A reference that is a <b>pattern</b> rather than a concrete name — a glob ({@code *DTC}),
     * a {@code /…/} regex, or an ADaM wildcard-marker template ({@code TRTxxP}) — is recorded as a
     * compiled pattern for its bucket instead of an exact name, and keeps <em>every</em> column it
     * matches. Before this, such a reference was either dropped by the identifier filter
     * ({@code *DTC}) or uppercased into a name no column can ever have ({@code TRTxxP} →
     * {@code TRTXXP}), so the columns it needs were treated as unreferenced and trimmed away —
     * silently turning the scenario into one that no longer exercises the rule.</li>
     * </ul>
     */
    static ColumnKeepSets collectReferencedColumns(Rule aRule, @Nullable String aPrimaryDomain)
    {
        @Nullable
        String primary = aPrimaryDomain != null ? aPrimaryDomain.toUpperCase(Locale.ROOT) : null;
        ColumnKeepSets buckets = new ColumnKeepSets();

        // Check tree — leaf.name / leaf.value / leaf.within / leaf.ordering. checkConditions()
        // walks EVERY declared level (Plan C §3.3), never getCheck() alone: a column named only by
        // a weaker level would otherwise be trimmed away, and the scenario would still pass
        // because its pin exercises the stricter level.
        for (CheckCondition levelCondition : aRule.checkConditions())
        {
            collectFromCheck(levelCondition, primary, buckets);
        }
        collectFromCheck(aRule.getPrecondition(), primary, buckets);

        // Operations — name + group + filter-keys, bucketed by op.domain (or primary).
        if (aRule.getOperations() != null)
        {
            for (Operation op : aRule.getOperations())
            {
                collectFromOperation(op, primary, buckets);
            }
        }

        // Outcome.Output_Variables — could name the primary or any sibling.
        Outcome outcome = aRule.getOutcome();
        if (outcome != null && outcome.getOutputVariables() != null)
        {
            for (String v : outcome.getOutputVariables())
            {
                addRef(v, primary, WILDCARD_DS, buckets);
            }
        }

        // The rule-level grouping key — primary-side group keys. Read through
        // effectiveGroupingVariables so a rule using the `Grouping:` block is covered too; the flat
        // getter alone would silently trim away the group columns of every migrated rule.
        if (aRule.effectiveGroupingVariables() != null)
        {
            for (String g : aRule.effectiveGroupingVariables())
            {
                addRef(g, primary, primary, buckets);
            }
        }

        // Match_Datasets.Keys — join keys exist on the primary AND the named sibling.
        if (aRule.getMatchDatasets() != null)
        {
            for (MatchDataset md : aRule.getMatchDatasets())
            {
                if (md.getKeys() == null)
                {
                    continue;
                }
                @Nullable
                String siblingName = resolveWildcardDataset(md.getName(), primary);
                @Nullable
                String siblingBucket = siblingName != null ? siblingName.toUpperCase(Locale.ROOT)
                        : null;
                for (String k : md.getKeys())
                {
                    addRef(k, primary, primary, buckets);
                    if (siblingBucket != null)
                    {
                        addRef(k, primary, siblingBucket, buckets);
                    }
                }
            }
        }

        // Requirements.Variables — the rule is only answerable when these cols are present. Keep
        // them on the primary so future ScopeMatcher-driven pipelines still see the scenario as
        // applicable. ⚠ All AND Any: an `Any` list is unmet only when EVERY entry is absent, so
        // trimming the whole list away would make the rule inapplicable exactly as trimming an
        // `All` entry would. ⛔ `None` is deliberately NOT kept — those columns must stay absent,
        // and adding one here would skip the rule it was trimmed for.
        VariableRequirement required = aRule.effectiveVariableRequirement();
        if (required != null)
        {
            for (List<String> facet : Arrays.asList(required.getAll(), required.getAny()))
            {
                if (facet == null)
                {
                    continue;
                }
                for (String v : facet)
                {
                    addRef(v, primary, primary, buckets);
                }
            }
        }

        return buckets;
    }


    private static void collectFromCheck(@Nullable CheckCondition aCond,
            @Nullable String aPrimaryDomain, ColumnKeepSets aBuckets)
    {
        if (aCond == null)
        {
            return;
        }
        if (aCond instanceof CheckConditionAll all)
        {
            for (CheckCondition c : all.getConditions())
            {
                collectFromCheck(c, aPrimaryDomain, aBuckets);
            }
        }
        else if (aCond instanceof CheckConditionAny any)
        {
            for (CheckCondition c : any.getConditions())
            {
                collectFromCheck(c, aPrimaryDomain, aBuckets);
            }
        }
        else if (aCond instanceof CheckConditionNot not)
        {
            collectFromCheck(not.getCondition(), aPrimaryDomain, aBuckets);
        }
        else if (aCond instanceof CheckConditionLeaf leaf)
        {
            // leaf.name — always a column reference (modulo $/built-ins filtered by addRef).
            addRef(leaf.getName(), aPrimaryDomain, WILDCARD_DS, aBuckets);

            // leaf.value — textual / array / sort-descriptor objects.
            // Unless `value_is_literal == true`, a textual element is (potentially) a
            // column reference. We intentionally include it even for operators whose
            // values are almost always literals (is_contained_by, equal_to): spurious
            // entries are harmless because unused names never match existing columns.
            if (!Boolean.TRUE.equals(leaf.getValueIsLiteral()))
            {
                collectFromValueNode(leaf.getValue(), aPrimaryDomain, aBuckets);
            }

            // leaf.within is polymorphic (string or list of strings) post-Fix #25;
            // ``getWithinColumns()`` normalises both shapes to a List<String>.
            // leaf.ordering remains a comma-split string.
            for (String col : leaf.getWithinColumns())
            {
                addRef(col, aPrimaryDomain, WILDCARD_DS, aBuckets);
            }
            addCommaSplit(leaf.getOrdering(), aPrimaryDomain, aBuckets);
        }
    }


    private static void collectFromValueNode(@Nullable JsonNode aValue,
            @Nullable String aPrimaryDomain, ColumnKeepSets aBuckets)
    {
        if (aValue == null || aValue.isNull())
        {
            return;
        }
        if (aValue.isTextual())
        {
            addRef(aValue.asText(), aPrimaryDomain, WILDCARD_DS, aBuckets);
            return;
        }
        if (aValue.isArray())
        {
            for (JsonNode el : aValue)
            {
                if (el.isTextual())
                {
                    addRef(el.asText(), aPrimaryDomain, WILDCARD_DS, aBuckets);
                }
                else if (el.isObject() && el.has("name"))
                {
                    // target_is_not_sorted_by: [{"name": "--STDTC", "sort_order": "asc"}, …]
                    addRef(el.get("name").asText(), aPrimaryDomain, WILDCARD_DS, aBuckets);
                }
            }
        }
        // Numbers, booleans, or objects without a "name" field carry no column reference.
    }


    private static void addCommaSplit(@Nullable String aCommaList, @Nullable String aPrimaryDomain,
            ColumnKeepSets aBuckets)
    {
        if (aCommaList == null || aCommaList.isBlank())
        {
            return;
        }
        for (String tok : aCommaList.split(",", 0))
        {
            addRef(tok.trim(), aPrimaryDomain, WILDCARD_DS, aBuckets);
        }
    }


    private static void collectFromOperation(@Nullable Operation aOp,
            @Nullable String aPrimaryDomain, ColumnKeepSets aBuckets)
    {
        if (aOp == null)
        {
            return;
        }
        // Operation's target dataset: explicit op.domain (after -- expansion) or primary.
        @Nullable
        String opDomain = resolveWildcardDataset(aOp.getDomain(), aPrimaryDomain);
        String bucket = opDomain != null ? opDomain.toUpperCase(Locale.ROOT)
                : aPrimaryDomain != null ? aPrimaryDomain : WILDCARD_DS;

        addRef(aOp.getName(), aPrimaryDomain, bucket, aBuckets);
        if (aOp.getGroup() != null)
        {
            for (String g : aOp.getGroup())
            {
                addRef(g, aPrimaryDomain, bucket, aBuckets);
            }
        }
        if (aOp.getFilter() != null)
        {
            // filter keys are column names on the operation's dataset.
            for (String key : aOp.getFilter().keySet())
            {
                addRef(key, aPrimaryDomain, bucket, aBuckets);
            }
        }
    }


    /**
     * Normalise a single reference into a (dataset, column) pair and add it to {@code aBuckets}.
     * Dot-qualified refs route to the named dataset; unqualified refs go to {@code aDefaultBucket}.
     */
    private static void addRef(@Nullable String aRef, @Nullable String aPrimaryDomain,
            @Nullable String aDefaultBucket, ColumnKeepSets aBuckets)
    {
        if (aRef == null || aRef.isBlank())
        {
            return;
        }
        // An `!X` exclusion token (OutputVariableToken) only withholds X from the FINDING; the
        // rule still reads X, so the column stays in the keep-set under its bare name.
        // ⚠ The strip sits in this SHARED funnel, not at the Outcome.Output_Variables call
        // site: Requirements.Variables, Grouping_Variables, Match_Datasets.Keys, the Check
        // leaves and the Operations fields all pass through here too. The `!` marker is
        // grammar in the OV list ALONE, and nothing validates it away elsewhere — so a `!X`
        // entry authored (or machine-written) into Requirements.Variables would be silently
        // rewritten
        // to X here rather than rejected. If `!` ever gains a meaning outside the OV list,
        // move this call to the OV caller before that meaning ships.
        String s = net.cumba.corej.core.model.OutputVariableToken.name(aRef.trim());
        if (s.isEmpty() || s.startsWith("$"))
        {
            return; // operation result reference (or a malformed bare `!`)
        }
        String lower = s.toLowerCase(Locale.ROOT);
        // Built-in meta names — never correspond to actual column names.
        if (lower.equals("dataset_name") || lower.equals("dataset_label")
                || lower.equals("record_count") || lower.equals("dataset_size")
                || lower.equals("dataset_class") || lower.equals("variable_name")
                || lower.equals("variable_label") || lower.equals("variable_data_type")
                || lower.equals("variable_value") || lower.startsWith("library_variable_")
                || lower.startsWith("define_variable_"))
        {
            return;
        }

        // A null default bucket means there was no primary domain to anchor an unqualified
        // reference; fall back to the wildcard bucket so the column is kept on every block
        // that has it (and so the bucket key below is never null).
        String bucket = aDefaultBucket != null ? aDefaultBucket : WILDCARD_DS;
        String col = s;
        int dot = s.indexOf('.');
        if (dot > 0)
        {
            String dsName = s.substring(0, dot);
            col = s.substring(dot + 1);
            // Dataset-side wildcard: SUPP-- → SUPPAE when primary is AE.
            @Nullable
            String resolvedDs = resolveWildcardDataset(dsName, aPrimaryDomain);
            if (resolvedDs != null)
            {
                bucket = resolvedDs.toUpperCase(Locale.ROOT);
            }
            else
            {
                // No primary prefix to expand against — fall back to wildcard so we don't
                // accidentally drop the column on any sibling that has it.
                bucket = WILDCARD_DS;
            }
        }

        // Column-side wildcard expansion (for leaves like --STDTC or RELREC.**TERM).
        if (aPrimaryDomain != null)
        {
            if (col.contains("--"))
            {
                col = col.replace("--", aPrimaryDomain);
            }
            if (col.contains("**"))
            {
                col = col.replace("**", aPrimaryDomain);
            }
        }

        if (col.isEmpty())
        {
            return;
        }

        // A pattern reference keeps every column it matches, rather than one exact name. The
        // precedence mirrors ScopeMatcher.scopeEntryPattern — glob / `/…/` first, wildcard
        // markers second — so the trimmer keeps exactly the columns the matcher will later look
        // for. Without this, `*DTC` was dropped by the identifier filter below and `TRTxxP` was
        // uppercased to the unmatchable `TRTXXP`, so their columns looked unreferenced.
        Pattern pattern = columnPattern(col);
        if (pattern != null)
        {
            aBuckets.addPattern(bucket, pattern);
            return;
        }

        if (!col.matches("[A-Za-z_][A-Za-z0-9_]*"))
        {
            return;
        }
        aBuckets.addLiteral(bucket, col.toUpperCase(Locale.ROOT));
    }


    /**
     * The compiled pattern for a column reference that is not a concrete name — a glob
     * ({@code *DTC}), a {@code /…/} regex, or an ADaM wildcard-marker template ({@code TRTxxP},
     * {@code ANLzzFL}) — or {@code null} when the reference is a plain name.
     * <p>
     * Both helpers come from the engine, so the trimmer and the runtime matcher agree by
     * construction rather than by a re-implementation that can drift. A malformed {@code /…/} would
     * have been rejected at rule load, but the compile is guarded so a hand-edited corpus can never
     * make the trimmer throw.
     * </p>
     */
    private static @Nullable Pattern columnPattern(String aColumn)
    {
        try
        {
            Pattern glob = ScopeMatcher.scopePattern(aColumn);
            return glob != null ? glob : WildcardExpander.scopeVariableWildcardPattern(aColumn);
        }
        catch (PatternSyntaxException _)
        {
            return null;
        }
    }

    /**
     * Per-dataset instructions for which columns a scenario must keep: exact names plus patterns
     * that keep any column they match. Two structures rather than one because they are compared
     * differently — names case-insensitively (they are stored upper-cased), patterns by their own
     * anchored matching, which for wildcard markers is deliberately case-sensitive.
     */
    static final class ColumnKeepSets
    {

        private final Map<String, Set<String>> literals = new LinkedHashMap<>();

        private final Map<String, List<Pattern>> patterns = new LinkedHashMap<>();

        void addLiteral(String aBucket, String aUpperName)
        {
            literals.computeIfAbsent(aBucket, _ -> new LinkedHashSet<>()).add(aUpperName);
        }


        void addPattern(String aBucket, Pattern aPattern)
        {
            patterns.computeIfAbsent(aBucket, _ -> new ArrayList<>()).add(aPattern);
        }


        /** The exact names recorded for a bucket (upper-cased); empty when none. */
        Set<String> literals(String aBucket)
        {
            return literals.getOrDefault(aBucket, Set.of());
        }


        /** Whether {@code aColumn} is kept by {@code aBucket} — by exact name or by pattern. */
        boolean keeps(String aBucket, String aColumn)
        {
            if (literals(aBucket).contains(aColumn.toUpperCase(Locale.ROOT)))
            {
                return true;
            }
            for (Pattern p : patterns.getOrDefault(aBucket, List.of()))
            {
                if (p.matcher(aColumn).matches())
                {
                    return true;
                }
            }
            return false;
        }


        /** Bucket names that carry at least one literal or pattern — diagnostics only. */
        Set<String> buckets()
        {
            Set<String> all = new LinkedHashSet<>(literals.keySet());
            all.addAll(patterns.keySet());
            return all;
        }
    }

    /**
     * True when the rule touches columns the walker cannot enumerate statically:
     * <ul>
     * <li>{@code Match_Datasets[].Child=true} or named {@code RELREC} — the engine pre-merges
     * parent columns whose names come from data (IDVAR values) or dereferences RELREC pointers at
     * runtime.</li>
     * <li>Operations that return a dynamic list of column names
     * ({@code get_dataset_filtered_variables}, {@code get_column_order_from_*},
     * {@code variable_count}). The result may drive subsequent group/value iteration over unknown
     * columns.</li>
     * <li>Check operators that read a suffixed column family
     * ({@code inconsistent_enumerated_columns}, {@code has_same_values}).</li>
     * <li>{@code value_is_reference=true} leaves — the value names a column whose content itself
     * names the actual target column.</li>
     * </ul>
     */
    static boolean hasDynamicColumnAccess(Rule aRule)
    {
        if (aRule.getMatchDatasets() != null)
        {
            for (MatchDataset md : aRule.getMatchDatasets())
            {
                if (Boolean.TRUE.equals(md.getChild()))
                {
                    return true;
                }
                if (md.getName() != null && md.getName().trim().equalsIgnoreCase("RELREC"))
                {
                    return true;
                }
            }
        }
        if (aRule.getOperations() != null)
        {
            for (Operation op : aRule.getOperations())
            {
                if (op.getOperator() != null
                        && DYNAMIC_COLUMN_ENUM_OPERATORS.contains(op.getOperator()))
                {
                    return true;
                }
            }
        }
        // Every declared level (Plan C §3.3), never getCheck() alone: a hazard in a weaker level
        // would otherwise read as "no hazard" and let the trimmer touch a scenario it must not.
        for (CheckCondition levelCondition : aRule.checkConditions())
        {
            if (checkHasHazard(levelCondition))
            {
                return true;
            }
        }
        return checkHasHazard(aRule.getPrecondition());
    }


    private static boolean checkHasHazard(@Nullable CheckCondition aCond)
    {
        if (aCond == null)
        {
            return false;
        }
        if (aCond instanceof CheckConditionAll all)
        {
            for (CheckCondition c : all.getConditions())
            {
                if (checkHasHazard(c))
                {
                    return true;
                }
            }
            return false;
        }
        if (aCond instanceof CheckConditionAny any)
        {
            for (CheckCondition c : any.getConditions())
            {
                if (checkHasHazard(c))
                {
                    return true;
                }
            }
            return false;
        }
        if (aCond instanceof CheckConditionNot not)
        {
            return checkHasHazard(not.getCondition());
        }
        if (aCond instanceof CheckConditionLeaf leaf)
        {
            if (Boolean.TRUE.equals(leaf.getValueIsReference()))
            {
                return true;
            }
            return leaf.getOperator() != null
                    && DYNAMIC_COLUMN_FAMILY_OPERATORS.contains(leaf.getOperator());
        }
        return false;
    }


    /**
     * Expand {@code --} / {@code **} in a dataset name against the primary domain. Returns
     * {@code null} when the input is blank or only a wildcard placeholder with no primary
     * available.
     */
    private static @Nullable String resolveWildcardDataset(@Nullable String aName,
            @Nullable String aPrimaryDomain)
    {
        if (aName == null || aName.isBlank())
        {
            return null;
        }
        String s = aName.trim();
        if (aPrimaryDomain == null)
        {
            return s.contains("--") || s.contains("**") ? null : s;
        }
        return s.replace("--", aPrimaryDomain).replace("**", aPrimaryDomain);
    }


    /**
     * True when {@link RuleTestCdt#write}'s normalised round trip would not reproduce something the
     * file currently carries, in which case this pass must leave the file alone.
     *
     * <p>
     * ⚠⚠ <b>Two independent loss channels, and the second is the dangerous one.</b>
     * {@code RuleTestScenario} is not a faithful model of a {@code .cdt} file, so {@code parse} →
     * {@code write} is lossy in both:
     * </p>
     * <ol>
     * <li><b>Plain comments.</b> The parser treats {@code '#'} + whitespace (and a bare
     * {@code '#'}) as a comment and discards it; there is no field to write back. <b>2 669</b> of
     * the 8 883 corpus files carry a {@code "# Rule:Description:"} line.</li>
     * <li><b>{@code #define} / {@code #define-include}.</b> These parse into
     * {@code RuleTestScenario.define}, and {@code writeTo} <em>never emits that field</em> — it
     * emits only the separate {@code #define-xml} channel. <b>605</b> corpus files carry one. This
     * deletes <em>semantics</em>, not prose.</li>
     * </ol>
     *
     * <p>
     * ⛔ <b>Why the trimmer's own verification cannot catch either.</b> The three ROW trims splice
     * the original text and re-parse the candidate, so a round-trip loss changes what they observe.
     * This pass verifies the <em>in-memory</em> scenario and then writes a re-serialisation of it —
     * so by construction it can never notice anything the writer drops. That is why this guard
     * exists rather than a smarter assertion. A round-trip verification is layered on at the write
     * site as a second net, but it only catches losses that change the rule's verdict; a file can
     * lose its {@code #define} lines with the verdict intact, and that is still corpus damage.
     * </p>
     *
     * <p>
     * ⚠ <b>This is a denylist and it must track {@code RuleTestCdt.writeTo}.</b> To re-derive it:
     * enumerate the fields of {@code RuleTestScenario} and check each has a corresponding
     * {@code aOut.write} in {@code writeTo}. At the time of writing 13 of 14 round-trip; only
     * {@code define} does not. Lift an entry only when the writer genuinely gained that channel.
     * </p>
     */
    private static boolean roundTripWouldLoseContent(Path aFile)
    {
        try (Stream<String> lines = Files.lines(aFile, UTF_8))
        {
            return lines.anyMatch(raw ->
            {
                // strip() first, exactly as RuleTestCdt.parse does before classifying a line.
                String t = raw.replace("\uFEFF", "").strip();
                if (!t.startsWith("#"))
                {
                    return false;
                }
                String afterHash = t.substring(1);
                // A comment is '#' + whitespace, or a bare '#'. A directive is '#' + a word.
                if (afterHash.isEmpty() || Character.isWhitespace(afterHash.charAt(0)))
                {
                    return true;
                }
                // The one directive the writer parses but never emits. NOT #define-xml, which it
                // does emit -- so match the exact tokens, never a startsWith("#define").
                String directive = afterHash.split("[ \t]", 2)[0];
                return "define".equals(directive) || "define-include".equals(directive);
            });
        }
        catch (IOException _)
        {
            // Unreadable here means unreadable for the trim too; treat as "do not touch".
            return true;
        }
    }

}
