package net.cumba.corej.core.ruletestsuites;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.FindingValuesNullGuard;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.ruletest.cdt.ruletest.ExecutionObserver;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.corej.ruletest.cdt.ruletest.RunEnvironment;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioLocationBackfill;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRuleSelector;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRunner;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRunner.EvalOutcome;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRunner.ScenarioEvaluation;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRunner.ScenarioRunResult;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioScopeGate;
import net.cumba.corej.ruletest.cdt.ruletest.SidecarResolver;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Family-scoped {@code .cdt} rule-test factory: one {@link DynamicTest} per scenario of the
 * family's subtree, each run through the engine's {@link ScenarioRunner} and judged by it — the
 * harness that used to live in this class was lifted into {@code cumba-corej-ruletest} main scope
 * by {@code PLAN-rule-authoring-api} phase 3, so the corpus gate and the rule editor run <b>one
 * implementation</b>. What stays here is the corpus: which packages, which scenario files, the
 * declared-standard membership gate, the location back-fill mode, the finding-map null guard and
 * the opt-in outcome dump.
 *
 * <p>
 * Each scenario resolves its rule from <em>this family's</em> assembled packages
 * ({@code rules-<family>-<standard>-<version>.json}, see {@code RuleCorpusGenerator}): the declared
 * standard's package when the scenario declares one, else a deterministic canonical fallback
 * ({@link ScenarioRuleSelector#resolveIn}).
 * </p>
 *
 * <p>
 * Every {@code .cdt} file under the family subtree that carries a {@code #!RuleTest} shebang
 * becomes one {@link DynamicTest}; plain fixtures (no shebang) are silently skipped. The optional
 * system property {@code scenario.filter} (comma-separated {@code Core.Id} prefixes) restricts the
 * run to matching scenarios — used by batch agents to execute only their own rules' scenarios.
 * </p>
 *
 * <p>
 * A scenario that declares an explicit standard+version (via {@code #library standard=… version=…}
 * or {@code #library-ref}) is additionally checked against the family package for that
 * standard+version: the rule must be a member, so a wrong {@code Standards} block in the rule (or a
 * wrong declaration in the scenario) fails the scenario. Families may make the declaration
 * mandatory via {@link #requireDeclaredStandard()} (the FDA authoring convention).
 * </p>
 *
 * <p>
 * A scenario carrying {@code #dictionaries dummy} runs with the checked-in dummy
 * external-dictionary bundle ({@code src/test/resources/dictionaries}) wired into the engine;
 * without the directive no provider is supplied and dictionary-dependent rules SKIP — the declared
 * ({@code $}-ref) form every shipped rule uses via {@code RuleRunner}'s eager dictionary arm
 * ({@code Fix #268}), an inlined call via its injected {@code dictionary_available} gate. The
 * {@code *-skipped_no_dictionary-*.cdt} scenarios pin that.
 * </p>
 *
 * <h2>The JUnit face of the three outcomes</h2>
 *
 * <p>
 * {@link ScenarioRunner.Outcome#PASS} passes, {@link ScenarioRunner.Outcome#FAIL} fails with the
 * runner's detail (the text the old assertions produced), {@link ScenarioRunner.Outcome#ABORTED}
 * aborts through {@link Assumptions} — exactly the states the old harness reached through
 * {@code Assertions} / {@code Assumptions} (the mapping table is on {@link ScenarioRunner}).
 * </p>
 */
abstract class AbstractRuleTestSuitesFactory
{

    private static final Path RULES_DIR = CorpusPaths.rules();

    private static final Path RESOURCE_BASE = Path.of(System.getProperty("projectBasedir"),
            "src/test/resources/net/cumba/corej/core/ruletestsuites");

    /**
     * The checked-in dummy external-dictionary bundle.
     *
     * <p>
     * ⚠ In the coreJ monorepo this resolved a SIBLING MODULE's directory,
     * {@code <repoRoot>/lib/corej-core/dictionaries}. The split put corej-core in the separate
     * engine repository, which this repo's build never checks out, so the bundle is carried here as
     * a test resource instead — byte-identical, 7 files, 60 KB.
     * </p>
     *
     * <p>
     * ⚠⚠ Its absence is NOT silent in the sense that matters, but it is misleading: a rule needing
     * an uninstalled dictionary is SKIPPED, and a scenario declaring {@code expect=violation} then
     * fails with "the rule never executed" rather than with anything naming the dictionaries. 187
     * of the 8 824 scenarios failed exactly that way while this path was still pointing at the
     * other repo.
     * </p>
     */
    private static final Path DUMMY_DICTIONARIES_DIR = Path.of(System.getProperty("projectBasedir"),
            "src/test/resources/dictionaries");

    /** Comma-separated {@code Core.Id} prefixes; unset/blank runs every scenario. */
    private static final String FILTER_PROPERTY = "scenario.filter";

    private static final java.util.regex.Pattern COMMA = java.util.regex.Pattern.compile(",");

    /**
     * Per-family package maps, loaded once per JVM: {@code standard-version} → ({@code Core.Id} →
     * rule). Kept per package (not merged) because the assembled view of the same rule differs
     * across standard versions — a scenario must execute the view of the standard it declares.
     */
    private static final Map<String, Map<String, Map<String, Rule>>> PACKAGES_BY_FAMILY = new ConcurrentHashMap<>();

    /**
     * The corpus's run environment, built once: this JVM's store ({@link ScenarioLibrarySupport}),
     * the dummy dictionary bundle, a sidecar resolver confined to the suites tree (every corpus
     * sidecar sits beside its scenario, so nothing changes for the corpus; the confinement is the
     * editor's requirement).
     */
    private static final class Environment
    {

        static final RunEnvironment VALUE = build();

        private static RunEnvironment build()
        {
            try
            {
                return new RunEnvironment(ScenarioLibrarySupport.store(), DUMMY_DICTIONARIES_DIR,
                        SidecarResolver.rootedAt(RESOURCE_BASE));
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(
                        "the suites tree is not a directory: " + RESOURCE_BASE, e);
            }
        }
    }

    /**
     * The finding-map null guard as the runner's observer: judged on every scenario execution, and
     * counted (the factory's last test asserts the reach).
     */
    private static final ExecutionObserver NULL_GUARD = (context, rule,
            result) -> FindingValuesNullGuard.requireNullFree(FindingValuesNullGuard.CDT, context,
                    rule, result.getViolations());

    /**
     * The family key — simultaneously the resource-subtree name under the suites scenario root and
     * the assembled-package prefix ({@code rules-<family>-*.json}): {@code "cdisc"},
     * {@code "draft"}, {@code "fda"} or {@code "pmda"}.
     *
     * <p>
     * ⚠ The rule-id prefix does <b>not</b> name the family: the CDISC-SEND rules are
     * {@code CDISC-SEND-*} ids assembled into {@code rules-cdisc-sendig-*.json}, so they belong to
     * the {@code cdisc} family and declare {@code #library standard=sendig version=…}. The family
     * is the {@code rules-src/checks/<FAMILY>/} directory the rule is authored in, lower-cased.
     * </p>
     */
    abstract String family();


    /**
     * The family's scenario resource subtree. Not guaranteed to exist: {@link #scenarios()}
     * tolerates a missing directory (a family whose sweep has not started yet contributes zero
     * dynamic tests rather than failing).
     */
    // Package-private so RuleTestSuitesFamilyHomeTest can assert the home of every declared family
    // exists. A factory over a missing subtree is silently EMPTY here by design, so without that
    // guard a mistyped family() -- or a subtree renamed underneath it -- would be a vacuous green.
    static Path familyRoot(String aFamily)
    {
        return RESOURCE_BASE.resolve(aFamily);
    }


    /** The corpus's run environment (package-private for the tests that run scenarios directly). */
    static RunEnvironment environment()
    {
        return Environment.VALUE;
    }


    /**
     * When {@code true}, every scenario of this family must declare an explicit standard+version
     * ({@code #library standard=… version=…} or {@code #library-ref}); the membership check then
     * always runs. Default {@code false}: migrated scenarios keep their historic semantics and the
     * membership check only fires when a declaration is present.
     */
    boolean requireDeclaredStandard()
    {
        return false;
    }


    // A @TestFactory: each scenario's DynamicTest runs its .cdt and asserts the outcome.
    @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
    @TestFactory
    Stream<DynamicTest> scenarios() throws IOException
    {
        Path root = RESOURCE_BASE.resolve(family());
        if (!Files.isDirectory(root))
        {
            return Stream.empty();
        }
        List<String> filter = parseFilter();
        List<Path> scenarioFiles;
        try (Stream<Path> walk = Files.walk(root))
        {
            scenarioFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".cdt"))
                    .filter(AbstractRuleTestSuitesFactory::hasShebang)
                    .filter(p -> matchesFilter(p, filter)).sorted().toList();
        }
        // T2 MED-3 of PLAN-output-variable-null-key-provenance: the finding-map null guard sits
        // inside the runner's execute(); the last dynamic test proves it was REACHED on at least a
        // floor of the scenarios this factory ran (dynamic tests run in stream order, so it runs
        // last).
        long reachAtStart = FindingValuesNullGuard.reach(FindingValuesNullGuard.CDT);
        java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
        return Stream.concat(scenarioFiles.stream().map(p -> toDynamicTest(root, p, ran)),
                Stream.of(DynamicTest.dynamicTest("finding-map null guard reached",
                        () -> FindingValuesNullGuard.requireReach(FindingValuesNullGuard.CDT,
                                reachAtStart,
                                (long) Math.floor(ran.get()
                                        * (filter.isEmpty() ? NULL_GUARD_REACH_FLOOR : 0)),
                                ran.get() + " " + family() + " scenario(s)"))));
    }

    /**
     * The share of a factory's scenarios on which the finding-map null guard must have run: every
     * scenario that executes the engine reaches it, and only an out-of-scope primary dataset, a
     * {@code #library-ref} scenario without a library and the like never execute. A policy floor,
     * not a measured count; a {@code scenario.filter} run reports its reach without a floor (a
     * filtered rule may hold only non-executing scenarios).
     */
    private static final double NULL_GUARD_REACH_FLOOR = 0.8;

    private static List<String> parseFilter()
    {
        String raw = System.getProperty(FILTER_PROPERTY, "").trim();
        if (raw.isEmpty())
        {
            return List.of();
        }
        return COMMA.splitAsStream(raw).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }


    /** With no filter every scenario matches; else the scenario's coreId must match a prefix. */
    private static boolean matchesFilter(Path aFile, List<String> aFilter)
    {
        if (aFilter.isEmpty())
        {
            return true;
        }
        String coreId = peekCoreId(aFile);
        return coreId != null && aFilter.stream().anyMatch(coreId::startsWith);
    }


    /** Cheap prelude scan for the {@code #test <coreId> …} directive; null if absent. */
    // ⛔ Package-visible, NOT private, since 2026-09-19: ScenarioFilenameBindsItsOwnRuleTest
    // asserts the filename/directory/family invariant against the id THIS method reads, so that
    // there is ONE `#test` parser and not two. The H-5 finding on
    // OrderFlipGuardCoverageRatchetTest is exactly what a second copy costs: that ratchet called
    // RuleTestCdt#hasShebang while this class filtered with a private re-implementation of its
    // own, so the guard used the predicate the factory did not.
    static @Nullable String peekCoreId(Path aFile)
    {
        try (BufferedReader r = Files.newBufferedReader(aFile, UTF_8))
        {
            String line;
            while ((line = r.readLine()) != null)
            {
                String t = line.replace("﻿", "").trim();
                if (t.isEmpty() || t.startsWith("#!"))
                {
                    continue;
                }
                if (t.charAt(0) != '#')
                {
                    return null;
                }
                if (t.startsWith("#test") && t.length() > 5 && Character.isWhitespace(t.charAt(5)))
                {
                    String payload = t.substring(5).trim();
                    int end = 0;
                    while (end < payload.length() && !Character.isWhitespace(payload.charAt(end)))
                    {
                        end++;
                    }
                    return end > 0 ? payload.substring(0, end) : null;
                }
            }
            return null;
        }
        catch (IOException _)
        {
            return null;
        }
    }


    /**
     * Whether the file is a {@code #!RuleTest} scenario — <b>the format's own predicate</b>.
     *
     * <p>
     * ⚠ This DELEGATES to {@link RuleTestCdt#hasShebang(String)} on purpose (review round 3, H-5).
     * It used to re-implement it over a {@link BufferedReader}, which left TWO predicates deciding
     * the same question: {@code OrderFlipGuardCoverageRatchetTest} gated its guard fixtures through
     * {@code RuleTestCdt#hasShebang} — and said so in its javadoc — while the suite this factory
     * builds admitted files through the local copy. The two disagreed on BOM placement and on
     * U+2028 / U+0085, which {@code String.split("\\R")} honours and {@code readLine()} does not;
     * every known divergence happened to land in the safe direction (ratchet red, factory running),
     * but "happens to be safe" is not a property anyone can rely on. The DECISION is now made in
     * exactly one place.
     * </p>
     *
     * <p>
     * ⚠ Only the leading blank lines plus the first non-empty one are read, rather than
     * {@code Files.readString}. Whole-file reading would have been shorter, but it widens the I/O
     * failure surface: a malformed UTF-8 byte ANYWHERE in a scenario would then throw, be caught
     * here, and SILENTLY drop that scenario from the suite — the very "counted a file the suite
     * never executes" hazard the ratchet exists to catch, inverted. Reading only the head keeps the
     * failure surface exactly where it was before the delegation.
     * </p>
     *
     * <p>
     * Package-private since 2026-09-28 so that {@code LibraryRefScenariosExecuteTest} admits
     * exactly the files the suite admits (LAC review round 2, L-3).
     * </p>
     */
    static boolean hasShebang(Path aPath)
    {
        try (BufferedReader r = Files.newBufferedReader(aPath, UTF_8))
        {
            StringBuilder head = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null)
            {
                head.append(line).append('\n');
                if (!line.isBlank())
                {
                    break; // the first non-empty line is all the predicate can need
                }
            }
            return RuleTestCdt.hasShebang(head.toString());
        }
        catch (IOException _)
        {
            return false;
        }
    }


    private DynamicTest toDynamicTest(Path aRoot, Path aFile,
            java.util.concurrent.atomic.AtomicInteger aRan)
    {
        String display = aRoot.relativize(aFile).toString().replace('\\', '/');
        return DynamicTest.dynamicTest(display, aFile.toUri(), () ->
        {
            aRan.incrementAndGet();
            // PLAN-rule-authoring-api phase 3: the opt-in outcome dump records the verdict path
            // of every scenario (ScenarioOutcomeDump); off, this is the plain call it was.
            ScenarioOutcomeDump.Entry entry = ScenarioOutcomeDump.begin(family() + "/" + display);
            try
            {
                runScenario(aFile, entry);
                entry.junit("PASS", null);
            }
            catch (org.opentest4j.TestAbortedException e)
            {
                entry.junit("ABORTED", e.getMessage());
                throw e;
            }
            catch (AssertionError e)
            {
                entry.junit("FAIL", e.getMessage());
                throw e;
            }
            catch (RuntimeException | IOException e)
            {
                entry.junit("ERROR", e.toString());
                throw e;
            }
            finally
            {
                ScenarioOutcomeDump.record(entry);
            }
        });
    }


    // Package-private, not private, since 2026-09-28: LibraryRefScenariosExecuteTest drives the
    // #library-ref scenarios through THIS method, so that a skip there is judged on the same path
    // the factories run (LAC review round 1, L2).
    void runScenario(Path aFile) throws IOException
    {
        runScenario(aFile, ScenarioOutcomeDump.begin(aFile.toString()));
    }


    /** {@link #runScenario(Path)} recording its verdict path into the outcome dump entry. */
    void runScenario(Path aFile, ScenarioOutcomeDump.Entry aDump) throws IOException
    {
        RuleTestScenario s = load(aFile);

        checkDeclaredStandardMembership(s);

        Rule rule = resolveRule(s);
        assertNotNull(rule, "Rule " + s.getCoreId() + " not found in the " + family()
                + " family's rule packages");

        OverlayDataTable primary = s.primaryTable();
        assertNotNull(primary, "Scenario " + s.getCoreId() + " declares domain=" + s.getDomain()
                + " but no such dataset is present in the file");

        if (ScenarioOutcomeDump.enabled())
        {
            // Recorded before the gate judges it, so a rejected expectation still shows the axis.
            aDump.scope(ScenarioScopeGate.describeScopeMismatch(rule, primary));
        }
        ScenarioEvaluation evaluation = ScenarioRunner.evaluate(rule, s, primary, aFile,
                environment(), NULL_GUARD);
        EvalOutcome outcome = evaluation.outcome();
        if (outcome == null)
        {
            // out of scope (the gate's refusals are mapped below), or a stop before execution
            judged(ScenarioRunner.judge(s, evaluation, primary));
            return;
        }
        aDump.outcome(outcome.violated(), outcome.executed(), outcome.errored(),
                outcome.violations(), outcome.totalCount(), outcome.truncated(),
                outcome.notExecutedReason());

        if (ScenarioLocationBackfill.isEnabled())
        {
            boolean valueBased = rule.getEvaluationDomain() == null
                    || rule.getEvaluationDomain().rowCursor();
            ScenarioLocationBackfill.run(aFile, s, outcome.violations(), outcome.totalCount(),
                    outcome.truncated(), valueBased);
            return;
        }

        judged(ScenarioRunner.judge(s, evaluation, primary));
    }


    /** The runner's three outcomes onto JUnit's states. */
    static void judged(ScenarioRunResult aResult)
    {
        switch (aResult.outcome())
        {
        case PASS ->
        {
            // the scenario's declaration holds
        }
        case ABORTED -> Assumptions.abort(aResult.detail());
        case FAIL -> fail(aResult.detail());
        }
    }


    /** Load a scenario with the corpus's confined sidecar resolver. */
    static RuleTestScenario load(Path aFile) throws IOException
    {
        String content = Files.readString(aFile, UTF_8);
        SidecarResolver sidecars = environment().sidecars();
        assertNotNull(sidecars, "the corpus environment has a sidecar resolver");
        return RuleTestCdt.parse(content, aFile.toString(), sidecars.includeResolver(aFile));
    }


    /**
     * The outcome {@link #runScenario} judges, unjudged: the scenario's rule resolved through this
     * family's packages and executed exactly as the factory executes it. Package-private for
     * {@code MergeRefusedPopulationTest}, which classifies the engine messages of the
     * {@code expect=executionError} population.
     *
     * @return the outcome, or {@code null} when the primary dataset is outside the rule's scope
     */
    @Nullable
    EvalOutcome outcomeOf(Path aFile) throws IOException
    {
        RuleTestScenario s = load(aFile);
        Rule rule = resolveRule(s);
        assertNotNull(rule, "Rule " + s.getCoreId() + " not found in the " + family()
                + " family's rule packages");
        OverlayDataTable primary = s.primaryTable();
        assertNotNull(primary, "Scenario " + s.getCoreId() + " declares domain=" + s.getDomain()
                + " but no such dataset is present in the file");
        return evaluateScenario(aFile, s, rule, primary);
    }


    /**
     * Everything {@link #runScenario} does between resolving the rule and judging the verdict —
     * {@link ScenarioRunner#evaluate} with the corpus's environment and null guard — for the rule
     * handed in, which need not be the one the family packages resolve. A stop before execution (a
     * refused scope expectation, no carrier block, an unreadable sidecar) is mapped onto JUnit
     * here, as the old in-class harness asserted it.
     *
     * <p>
     * Package-private seam for {@code DynamicColumnRespellingDifferentialTest}
     * ({@code PLAN-dynamic-column-corpus-respelling} §2.8): the differential executes every
     * scenario of a respelled rule twice — once with the rule's <b>base</b> text (loaded from a
     * second package directory) and once with the respelled one — and compares the whole finding
     * rows. Keeping the preparation on the lifted runner, rather than copying it, is what makes the
     * differential judge exactly the execution the factories run.
     * </p>
     *
     * @return the outcome, or {@code null} when the primary dataset is outside the rule's scope
     *         (the scenario's declaration is asserted here, as before)
     */
    @Nullable
    static EvalOutcome evaluateScenario(Path aFile, RuleTestScenario s, Rule rule,
            OverlayDataTable primary)
    {
        ScenarioEvaluation evaluation = ScenarioRunner.evaluate(rule, s, primary, aFile,
                environment(), NULL_GUARD);
        if (evaluation.stop() != null)
        {
            judged(ScenarioRunner.judge(s, evaluation, primary));
        }
        return evaluation.outcome();
    }


    /**
     * The verdict half of {@link #runScenario}: the scenario's declared execution state and its
     * optional location assertions, judged against an outcome — {@link ScenarioRunner#judge} mapped
     * onto JUnit. Package-private for the same differential as {@link #evaluateScenario}, which
     * records whether the <b>base</b> text already satisfies a scenario (a sensitivity scenario
     * must be red there before it is green on the respelled rule — memory
     * {@code findings-snapshot-blind-on-conformant-data}).
     */
    static void assertScenarioVerdict(RuleTestScenario s, EvalOutcome outcome,
            OverlayDataTable primary)
    {
        judged(ScenarioRunner.judge(s,
                new ScenarioEvaluation(ScenarioRunner.ScopeGate.IN, outcome, List.of(), null),
                primary));
    }


    /** {@link ScenarioScopeGate#describeScopeMismatch}, the name the corpus tests cite. */
    static @Nullable String describeScopeMismatch(Rule aRule, OverlayDataTable aPrimary)
    {
        return ScenarioScopeGate.describeScopeMismatch(aRule, aPrimary);
    }


    /** {@link ScenarioScopeGate#describeAdamClassMismatch}, the name the corpus tests cite. */
    static @Nullable String describeAdamClassMismatch(Rule aRule, List<String> aStructures)
    {
        return ScenarioScopeGate.describeAdamClassMismatch(aRule, aStructures);
    }


    /**
     * The declared-standard membership gate: resolve the scenario's declared standard+version (if
     * any) and assert the rule is a member of this family's package for it. Families with
     * {@link #requireDeclaredStandard()} additionally fail scenarios that declare nothing.
     */
    private void checkDeclaredStandardMembership(RuleTestScenario aScenario)
    {
        String declared = ScenarioRuleSelector.declaredStandardVersion(aScenario);
        if (declared == null)
        {
            assertFalse(requireDeclaredStandard(),
                    "Scenario " + aScenario.getCoreId() + " (" + aScenario.getSource()
                            + ") must declare its standard+version via "
                            + "'#library standard=… version=…' or '#library-ref' — the " + family()
                            + " family requires it");
            return;
        }
        Map<String, Rule> pkg = familyPackages().get(declared);
        assertNotNull(pkg,
                "Scenario " + aScenario.getCoreId() + " declares standard+version '" + declared
                        + "' but no package file rules-" + family() + "-" + declared
                        + ".json exists");
        assertTrue(pkg.containsKey(aScenario.getCoreId()),
                "Rule " + aScenario.getCoreId() + " is not a member of the " + family()
                        + " family's '" + declared + "' package (check the rule's Standards block "
                        + "or the scenario's declared version)");
    }


    /**
     * Resolve the rule to execute: the declared standard+version's assembled view when the scenario
     * declares one, else the canonical fallback ({@link ScenarioRuleSelector#resolveIn}).
     */
    private @Nullable Rule resolveRule(RuleTestScenario aScenario)
    {
        return resolveIn(familyPackages(), aScenario);
    }


    /**
     * {@link ScenarioRuleSelector#resolveIn} under the name the respelling differential cites: the
     * view of the scenario's declared standard+version, else the canonical fallback, over an
     * explicit package map ({@link #loadFamily(Path, String)}).
     */
    static @Nullable Rule resolveIn(Map<String, Map<String, Rule>> packages,
            RuleTestScenario aScenario)
    {
        return ScenarioRuleSelector.resolveIn(packages, aScenario);
    }


    private Map<String, Map<String, Rule>> familyPackages()
    {
        return PACKAGES_BY_FAMILY.computeIfAbsent(family(),
                AbstractRuleTestSuitesFactory::loadFamily);
    }


    /**
     * Load every {@code rules-<family>-*.json} package, keyed by its {@code standard-version} stem.
     * Views are kept per package — the assembled form of the same rule differs across standard
     * versions, so {@link #resolveRule} picks the view matching the scenario.
     */
    // Package-private so RuleTestSuitesFamilyHomeTest can prove family() names a real package
    // prefix even for a family with no scenarios yet: this method is otherwise reached only from a
    // running scenario, so `rules-<family>-*.json` would go unverified for an empty subtree.
    static Map<String, Map<String, Rule>> loadFamily(String aFamily)
    {
        return loadFamily(RULES_DIR, aFamily);
    }


    /**
     * {@link #loadFamily(String)} over an explicit corpus directory — the respelling differential's
     * base packages, generated from the base rule text into a directory of its own
     * ({@code RuleCorpusGenerator <out> <release-out>} with the base tree as the working
     * directory). {@link ScenarioRuleSelector#loadPackages}.
     */
    static Map<String, Map<String, Rule>> loadFamily(Path aRulesDir, String aFamily)
    {
        return ScenarioRuleSelector.loadPackages(aRulesDir, aFamily);
    }
}
