package net.cumba.corej.core.ruletestsuites;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.exec.ScopeMatcher;
import net.cumba.corej.core.exec.ScopeVariableSource;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.gen.TokenExpander;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.metadata.AdamDataStructureDetector;
import net.cumba.corej.core.metadata.AdamSubclassDetector;
import net.cumba.corej.core.metadata.DefineXmlMetadataProvider;
import net.cumba.corej.core.metadata.OdmDefineXMLProvider;
import net.cumba.corej.core.metadata.RuntimeDictionaryProvider;
import net.cumba.corej.core.metadata.VlmResolver;
import net.cumba.corej.core.model.ClassScope;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.ruletest.cdt.ruletest.ExecutionVerdictCheck;
import net.cumba.corej.ruletest.cdt.ruletest.LibraryRef;
import net.cumba.corej.ruletest.cdt.ruletest.MapBackedLibraryMetadataProvider;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioLocationBackfill;
import net.cumba.corej.ruletest.cdt.ruletest.ViolationLocationCheck;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Base for the per-family {@code @TestFactory} classes that replay the {@code .cdt} rule-test
 * scenarios shipped in this suites module. The scenario tree is <em>family-based</em>: each rule
 * family (cdisc / draft / fda / pmda) owns one resource subtree named after it, and its factory
 * resolves rules from that family's assembled packages
 * ({@code target/rules/rules-<family>-*.json}), loaded per standard+version: the executed view is
 * the declared standard's package when the scenario declares one, else a deterministic canonical
 * fallback (see {@code resolveRule}).
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
 * external-dictionary bundle ({@code src/test/resources/dictionaries}) wired into the engine via
 * {@link RuntimeDictionaryProvider}; without the directive no provider is supplied and
 * dictionary-dependent rules SKIP — the declared ({@code $}-ref) form every shipped rule uses via
 * {@code RuleRunner}'s eager dictionary arm ({@code Fix #268}), an inlined call via its injected
 * {@code dictionary_available} gate. The {@code *-skipped_no_dictionary-*.cdt} scenarios pin that.
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
     * Fallback resolution order for scenarios that declare no standard+version: the historic
     * canonical packages of the pre-family factories (ADaM suite → {@code adamig-1-3}, SDTM suite →
     * {@code sdtmig-3-4}), then SEND's shipping version, then any package containing the rule
     * (sorted, last wins — deterministic).
     */
    private static final List<String> DEFAULT_PACKAGE_PREFERENCE = List.of("adamig-1-3",
            "sdtmig-3-4", "sendig-3-1-1");

    /** The default standard/version of a {@code #library} block with no explicit declaration. */
    private static final String UNDECLARED_STANDARD = "test";

    private static volatile @Nullable RuntimeDictionaryProvider dummyDictionaries;

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
        return scenarioFiles.stream().map(p -> toDynamicTest(root, p));
    }


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


    private DynamicTest toDynamicTest(Path aRoot, Path aFile)
    {
        String display = aRoot.relativize(aFile).toString().replace('\\', '/');
        return DynamicTest.dynamicTest(display, aFile.toUri(), () -> runScenario(aFile));
    }


    // Package-private, not private, since 2026-09-28: LibraryRefScenariosExecuteTest drives the
    // #library-ref scenarios through THIS method, so that a skip there is judged on the same path
    // the factories run (LAC review round 1, L2).
    void runScenario(Path aFile) throws IOException
    {
        RuleTestScenario s = RuleTestCdt.load(aFile);

        checkDeclaredStandardMembership(s);

        Rule rule = resolveRule(s);
        assertNotNull(rule, "Rule " + s.getCoreId() + " not found in the " + family()
                + " family's rule packages");

        OverlayDataTable primary = s.primaryTable();
        assertNotNull(primary, "Scenario " + s.getCoreId() + " declares domain=" + s.getDomain()
                + " but no such dataset is present in the file");

        // Production enforces the Domains/Classes scope axes upstream of RuleRunner
        // (DatasetRuleResolver -> ScopeMatcher), so mirror that gate here: an out-of-scope primary
        // dataset means the rule never runs -> "executed, zero violations". This makes Scope
        // fixes testable (an expect=noViolation scenario on out-of-scope data). The class axis
        // is only judged when the scenario declares a dataset class (`dataset NAME class=...`).
        String scopeMismatch = describeScopeMismatch(rule, primary);
        if (scopeMismatch != null)
        {
            // Review F2: an out-of-scope primary is a NON-EXECUTION, exactly like a
            // Requirements.Variables
            // skip — in production DatasetRuleResolver never even emits the rule for this dataset.
            // It
            // must therefore be declared, not silently accepted: an `expect=noViolation` scenario
            // here passes while exercising nothing, which is the same rot the verdict switch below
            // exists to prevent. `expect=skipped` is the honest contract.
            assertFalse(s.getExpect() == RuleTestScenario.Verdict.VIOLATION,
                    s.getCoreId() + " expected a violation but the primary dataset is outside "
                            + "the rule's scope: " + scopeMismatch);
            assertFalse(s.getExpect() == RuleTestScenario.Verdict.NO_VIOLATION,
                    s.getCoreId() + " declares expect=noViolation but the rule never runs on this"
                            + " dataset: " + scopeMismatch
                            + " — the scenario is not exercising the rule; declare"
                            + " 'expect=skipped', which is what being out of scope means.");
            return;
        }

        DatasetResolver.WithInventory resolver = s.resolver();
        // Inline provider for normal scenarios; a real CDISC-Library provider for #library-ref
        // scenarios (skips the test when no Library is configured).
        MetadataProvider library = ScenarioLibrarySupport.resolveOrSkip(s);

        // Define axis: a #define-xml sidecar yields the production wiring (define provider + VLM
        // resolver from the same parsed model); else the synthetic map-backed #define double.
        MetadataProvider defineProvider = s.getDefine();
        VlmResolver vlm = null;
        if (s.getDefineXml() != null)
        {
            OdmDefineXMLProvider parsed = parseDefineXml(aFile, s.getDefineXml());
            defineProvider = new DefineXmlMetadataProvider(parsed, null);
            vlm = VlmResolver.from(parsed.metaDataVersion());
        }

        // Declared ADaM scope axis (structure= / subclass=). RuleRunner re-applies the
        // Data_Structures / Subclasses gates at execution time and reads the DECLARED tier from
        // `defineProvider != null ? defineProvider : libraryProvider` — it knows nothing about the
        // two table-metadata keys CdtLoader stores. Route the declaration into whichever of those
        // two slots RuleRunner will consult, so the pre-gate above and the engine see the same
        // declared value. A scenario that declares an axis with neither block is refused here (a
        // decorator over an empty slot would switch that level's availability self-skips off) —
        // see ScenarioDeclaredScopeProvider for the full argument.
        ScenarioDeclaredScopeProvider.Slots slots = ScenarioDeclaredScopeProvider.install(primary,
                library, defineProvider, s.getSource(), readsDeclaredAdamTier(rule));
        library = slots.library();
        defineProvider = slots.define();

        EvalOutcome outcome = evaluate(rule, primary, resolver, s.getDomain(), library,
                defineProvider, resolveDictionaries(s), vlm, s.getRunLevel());

        if (ScenarioLocationBackfill.isEnabled())
        {
            boolean valueBased = rule.getEvaluationDomain() == null
                    || rule.getEvaluationDomain().rowCursor();
            ScenarioLocationBackfill.run(aFile, s, outcome.violations(), outcome.totalCount(),
                    outcome.truncated(), valueBased);
            return;
        }

        // #library-ref only: a configured-but-degraded Library leaves every (sub-)rule
        // SKIPPED/ERROR — skip rather than fail the verdict assertion.
        // Review F6: a scenario that DECLARES expect=skipped must be asserted, not aborted. The
        // rescue below exists for a #library-ref scenario degraded by a missing Library; applying
        // it to a deliberate skip would silently abort the very contract under test.
        if (s.getExpect() != RuleTestScenario.Verdict.SKIPPED)
        {
            ScenarioLibrarySupport.skipRefIfNotExecuted(s, outcome.executed(), "");
        }

        // Assert the observed EXECUTION STATE against the declared one before judging violations.
        // A SKIPPED / ERROR rule reports zero violations, so without this an `expect=noViolation`
        // scenario passes whether or not it exercised the rule at all — which is how a fixture
        // rots: tighten a rule's scope, or trim away the column that scope needs, and the scenario
        // keeps passing while testing nothing. `expect=skipped` is the positive form: the
        // deliberate absent-optional-variable scenarios assert the skip instead of borrowing
        // `noViolation`.
        // Phase 4b: the verdict judgement moved to ExecutionVerdictCheck (the ruletest module),
        // so the parse side and the assert side can no longer disagree — they used to live in two
        // repos, and an `expect=` constant added on one side fell through this switch SILENTLY.
        // ⚠ Java does NOT require an arrow switch STATEMENT over an enum to be exhaustive, so
        // adding `executionError` compiled clean here and a scenario written with it would have
        // passed while asserting nothing. That is the exact fixture-rot the execution-state check
        // above exists to prevent, reintroduced one level up.
        // ⚠ notExecutedReason is `status + ": " + statusMessage`, so the engine's own status word
        // rides along in the detail. Deliberate: in a failure message that word is the first thing
        // the reader needs.
        ExecutionVerdictCheck.Result verdict = ExecutionVerdictCheck.verify(s,
                new ExecutionVerdictCheck.Outcome(outcome.executed(), outcome.errored(),
                        outcome.violated(), outcome.totalCount(), outcome.notExecutedReason()));
        assertTrue(verdict.pass(), verdict.detail());

        // Optional location assertions (#expectViolationCount / #expectViolationAt); a no-op for
        // verdict-only scenarios. Violations are aggregated across wildcard expansions.
        ViolationLocationCheck.Result loc = ViolationLocationCheck.verify(s, outcome.violations(),
                outcome.totalCount(), outcome.truncated(), primary);
        assertTrue(loc.pass(), loc.detail());
    }

    /**
     * @param executed
     *            the rule ran — neither SKIPPED nor ERROR
     * @param errored
     *            it did not run <em>because it failed</em>. Kept apart from {@code executed}
     *            because {@code expect=skipped} asserts a <b>deliberate</b> non-execution: an ERROR
     *            must fail such a scenario, not satisfy it, or a rule broken into unconditional
     *            ERROR would keep every skip fixture green (review F1).
     */
    private record EvalOutcome(boolean violated, boolean executed, boolean errored,
            List<Violation> violations, long totalCount, boolean truncated,
            @Nullable String notExecutedReason)
    {
    }

    /**
     * Evaluates a rule against the scenario's primary dataset. {@code wildcards} template rules are
     * expanded against the present columns ({@link WildcardExpander}) and every concrete expansion
     * is executed; results aggregate across expansions.
     */
    private static EvalOutcome evaluate(Rule aRule, OverlayDataTable aPrimary,
            DatasetResolver.WithInventory aResolver, String aDomain,
            @Nullable MetadataProvider aLibrary, @Nullable MetadataProvider aDefine,
            @Nullable RuntimeDictionaryProvider aDictionaries, @Nullable VlmResolver aVlm,
            net.cumba.datatable.report.@Nullable Severity aRunLevel)
    {
        // nativeEval=true runs the native expression engine — the engine production uses
        // (StudyValidationParams / LibraryValidator both default nativeEval=true; the legacy
        // engine has been removed). Mirrors AbstractSdtmRuleTest.execute.
        // Fix #147: the three-arg entry point, so a declared-token `Expansion:` template expands
        // here exactly as it does in DatasetRuleResolver. With the two-arg form an
        // Expansion-bearing
        // rule falls through as NotApplicable and the scenario silently executes the TEMPLATE —
        // testing a column literally named '&VAR', which no fixture can have.
        TokenExpander.Context expansionContext = new TokenExpander.Context(
                ScopeVariableSource.of(aResolver, aPrimary), aLibrary, aDomain);
        return switch (WildcardExpander.tryExpand(aRule, aPrimary.getMetaData(), expansionContext))
        {
        case WildcardExpander.ExpansionResult.NotApplicable _ ->
        {
            RuleExecutionResult r = execute(aRule, aPrimary, aResolver, aDomain, aLibrary, aDefine,
                    aDictionaries, aVlm, aRunLevel);
            List<Violation> v = r.getViolations() != null ? r.getViolations() : List.of();
            boolean ran = !(r.isSkipped() || r.isError());
            yield new EvalOutcome(r.hasViolations(), ran, r.isError(), v, r.getViolationCount(),
                    r.isTruncated(), ran ? null : r.getStatus() + ": " + r.getStatusMessage());
        }
        // No matching column: the rule does not apply to this fixture, so nothing was exercised.
        // Reported as not-executed so an expect=noViolation scenario cannot pass vacuously when
        // its fixture lost every wildcard-matching column — declare expect=skipped if the
        // non-applicability IS the contract under test (same discipline as the scope gate).
        // Fix #147 — carry the expander's own reason through. A declared-token template states
        // exactly why it could not bind ("dataset 'ADSL' is not among the loaded datasets"),
        // and that sentence is the whole point of the mechanism: never a silent zero-expansion.
        case WildcardExpander.ExpansionResult.NoMatch(String reason) -> new EvalOutcome(false,
                false, false, List.of(), 0, false, "SKIPPED: " + reason);
        case WildcardExpander.ExpansionResult.Expanded(List<Rule> concrete) ->
        {
            boolean violated = false;
            boolean executed = false;
            boolean errored = false;
            String notExecuted = null;
            List<Violation> all = new ArrayList<>();
            long total = 0;
            boolean truncated = false;
            for (Rule r : concrete)
            {
                RuleExecutionResult res = execute(r, aPrimary, aResolver, aDomain, aLibrary,
                        aDefine, aDictionaries, aVlm, aRunLevel);
                if (!(res.isSkipped() || res.isError()))
                {
                    executed = true;
                }
                else
                {
                    // Any errored expansion taints the set: expect=skipped must not be satisfied
                    // by a rule that is broken for some tuples (review F1).
                    errored |= res.isError();
                    if (notExecuted == null)
                    {
                        // First non-executing expansion explains the set when NONE executes.
                        notExecuted = res.getStatus() + ": " + res.getStatusMessage();
                    }
                }
                if (res.hasViolations())
                {
                    violated = true;
                }
                if (res.getViolations() != null)
                {
                    all.addAll(res.getViolations());
                }
                total += res.getViolationCount();
                truncated |= res.isTruncated();
            }
            yield new EvalOutcome(violated, executed, errored, all, total, truncated,
                    executed ? null : notExecuted);
        }
        };
    }


    private static RuleExecutionResult execute(Rule aRule, OverlayDataTable aPrimary,
            DatasetResolver.WithInventory aResolver, String aDomain,
            @Nullable MetadataProvider aLibrary, @Nullable MetadataProvider aDefine,
            @Nullable RuntimeDictionaryProvider aDictionaries, @Nullable VlmResolver aVlm,
            net.cumba.datatable.report.@Nullable Severity aRunLevel)
    {
        // Plan C §3.4: the scenario's `#runLevel`, or the engine default (Warning) when it
        // declares none — so every scenario in this suite that says nothing is a
        // default-threshold baseline, exactly as it was before per-level Checks existed.
        return RuleRunnerCalls.execute(aRule, aPrimary, aResolver, aDomain, aLibrary, null, aDefine,
                Integer.MAX_VALUE, null, aDictionaries, aVlm, java.util.Set.of(),
                java.util.Set.of(),
                net.cumba.corej.core.exec.EngineLimits.resolveSeverityThreshold(aRunLevel));
    }


    /**
     * The production scope verdict for the scenario's primary dataset, or {@code null} when in
     * scope. Five axes, in the production order — Domains, <b>Datasets</b>, Classes,
     * Data_Structures, Subclasses — matching {@code DatasetRuleResolver.describeScopeSkip} call for
     * call. ⚠ An axis missing from this list is an axis the pre-gate does not judge, and a scenario
     * would then pass because the gate was off for it rather than because the rule was in scope;
     * {@code Scope.Datasets} was exactly that hole until phase 5 of
     * {@code plans/done/PLAN-scope-requirements-split.md}.
     *
     * <p>
     * The two axes differ in how an undeclared value is treated, deliberately:
     * </p>
     * <ul>
     * <li>the <b>class</b> axis reads the scenario's {@code class=} when it declares one. ⚠ Since
     * <b>Fix #206</b> an absent {@code class=} no longer switches the axis off for an <em>ADaM</em>
     * class scope: ADaM has no observation class, so those tokens are structure tokens and are
     * judged from the detected structure set below — see {@link #describeAdamClassMismatch}, which
     * also documents why an SDTM class scope stays undetermined;</li>
     * <li>the <b>ADaM structure / subclass</b> axes <b>detect</b> from the fixture's columns when
     * the scenario declares no {@code structure=} / {@code subclass=}. A {@code .cdt} fixture
     * always carries real columns, so detection is meaningful and a structure- or subclass-scoped
     * rule cannot be satisfied by omission. An explicit declaration is the <em>declared</em>
     * (Define-XML {@code def:Class} / {@code def:SubClass}) tier, which since <b>Fix #154</b>
     * <em>overrides</em> the columns by default; {@code -Dcorej.defineFirst=false} restores the old
     * columns-first order, where a declaration is only a fallback.</li>
     * </ul>
     *
     * <p>
     * ⚠⚠ <b>Phase 3a.3 — the two are no longer independent.</b> The bullets above described the
     * harness's own split, not production's: Define-XML has a single {@code def:Class} element,
     * which is the SDTM observation class for an SDTM dataset and the ADaM <em>data structure</em>
     * for an ADaM one (ADaM has no class of its own), and one value therefore feeds both gates in a
     * real run. A scenario's {@code class=} is now read as a structure declaration too when it
     * names one — {@link ScenarioDeclaredScopeProvider#effectiveDeclaredStructure} — so a fixture
     * saying {@code class="BASIC DATA STRUCTURE"} means in the harness what it would mean in a
     * study. An SDTM class value is not an ADaM structure and is unaffected.
     * </p>
     *
     * <p>
     * {@link net.cumba.corej.core.exec.RuleRunner} applies the same two ADaM axes at execution
     * time, from the same detectors with the same declared tier — the declaration reaches it via
     * {@link ScenarioDeclaredScopeProvider}, which the runner sees as an ordinary
     * {@link MetadataProvider}. This pre-gate therefore does not change <em>whether</em> a scenario
     * is in scope; it attributes the verdict to the scope layer that owns it, which is what makes
     * {@code expect=skipped} the honest contract rather than a mid-execution SKIPPED.
     * </p>
     */
    // Package-private (not private) so AdamClassScopeGateTest can pin the WIRING end-to-end, not
    // just describeAdamClassMismatch in isolation: with 0 verdict changes across the 494 newly
    // judged scenarios (Fix #206), deleting the call below would otherwise redden nothing.
    static @Nullable String describeScopeMismatch(Rule aRule, OverlayDataTable aPrimary)
    {
        // Same inputs as production (DatasetRuleResolver.describeScopeSkip): the dataset name plus
        // the
        // DOMAIN/RDOMAIN-derived unsplit name, so split datasets (LBHEMAT with DOMAIN=LB) match
        // their parent domain's scope.
        String mismatch = ScopeMatcher.describeDomainMismatch(aRule,
                aPrimary.getMetaData().getName(),
                net.cumba.corej.core.exec.OperationExecutor.unsplitNameFromData(aPrimary));
        if (mismatch != null)
        {
            return mismatch;
        }
        // Scope.Datasets (owner requirement #5, PLAN-scope-requirements-split phase 5) —
        // immediately after Domains, exactly where DatasetRuleResolver.describeScopeSkip puts it,
        // and on
        // the MEMBER file name with no split-base re-test. Without this leg every .cdt scenario on
        // a Datasets-scoped rule is pre-gated wrong, and silently: the axis simply would not be
        // judged, so a fixture would pass because the gate was off for it. Inert while the corpus
        // ships zero carriers — which is precisely why it has to be wired before one appears.
        mismatch = ScopeMatcher.describeDatasetMismatch(aRule, aPrimary.getMetaData().getName());
        if (mismatch != null)
        {
            return mismatch;
        }
        // Exactly the inputs and precedence RuleRunner uses (Fix #117/#118/#119): the column
        // heuristic plus the DECLARED tier, which here is the scenario's structure= / subclass=
        // rather than a Define-XML def:Class / def:SubClass. Same call, same
        // corej.defineFirst preference — so this pre-gate cannot disagree with the engine.
        // Computed BEFORE the class gate since Fix #206: an ADaM Classes scope is judged from this
        // same structure set (see describeAdamClassMismatch).
        List<String> columns = scopeColumnNames(aPrimary);
        boolean defineFirst = AdamDataStructureDetector.defineFirstPreference();
        String name = aPrimary.getMetaData().getName();
        // Phase 3a.3: the declared tier is `structure=` OR an ADaM-structure `class=`. Production
        // has one def:Class element feeding both the class gate and the structure gate; the
        // harness's independent-axes treatment was the divergence.
        List<String> structures = AdamDataStructureDetector.detectAll(name, columns,
                ScenarioDeclaredScopeProvider.effectiveDeclaredStructure(aPrimary), defineFirst);
        Object datasetClass = aPrimary.getMetaData().getMetaData("dataset_class");
        mismatch = datasetClass != null
                ? ScopeMatcher.describeClassMismatch(aRule, datasetClass.toString())
                : describeAdamClassMismatch(aRule, structures);
        if (mismatch != null)
        {
            return mismatch;
        }
        mismatch = ScopeMatcher.describeDataStructureMismatch(aRule, structures);
        if (mismatch != null)
        {
            return mismatch;
        }
        return ScopeMatcher.describeSubclassMismatch(aRule,
                AdamSubclassDetector.resolve(name, structures, columns,
                        ScenarioDeclaredScopeProvider.declaredSubclasses(aPrimary), defineFirst));
    }


    /**
     * <b>Fix #206 — the ADaM arm of the {@code Scope.Classes} gate.</b> Returns {@code null} when
     * the rule's {@code Scope.Classes} block does not apply here or is satisfied by
     * {@code aStructures}, else the mismatch description.
     *
     * <p>
     * Reached only when the scenario declares no {@code class=}. Until Fix #206 that meant the
     * class axis was <em>not judged at all</em>, which is the harness/production divergence wave 27
     * recorded as {@code R-d}: a fixture passed because the gate was off for it, not because the
     * rule was in scope. Production has no such hole — {@code DatasetRuleResolver} judges
     * {@code Scope.Classes} for every dataset, strict-on-null (Fix #41).
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Production's chain cannot be reproduced literally here, and reproducing it partially
     * would be worse than the hole.</b> {@code LibraryValidator.classNameFor} resolves the class
     * through {@code MetadataProvider.getDatasetClass(member, domain, columns)}, whose decisive
     * ADaM tier is the CDISC Library <em>product reverse-walk</em> ({@code AdamProduct}). No
     * {@code .cdt} scenario has a Library — {@code MapBackedLibraryMetadataProvider} does not
     * override {@code getDatasetClass} at all, so it returns {@code null} — and the curated offline
     * fallback ({@code DomainClassMap}) has <b>zero</b> {@code adam} entries. A literal
     * transcription would therefore resolve {@code null} (or the FU-4 {@code ADAM OTHER} sentinel)
     * for essentially every ADaM fixture and skip them all, which is the opposite of what a
     * Library-backed production run does.
     * </p>
     *
     * <p>
     * ⚑ What <em>is</em> reproducible is the semantics: <b>ADaM has no observation class</b>. The
     * single Define-XML {@code def:Class} element carries the ADaM <em>data structure</em>
     * (`PLAN-adam-structure-set-valued.md` §7.1 — identity, no mapping), so an ADaM
     * {@code Scope.Classes} token is a structure token and is judged from exactly the set the
     * {@code Scope.Data_Structures} gate uses, lifted over the is-a set the same way
     * ({@code Include} is satisfied when <b>any</b> detected token matches). The per-token verdict
     * comes from {@link ScopeMatcher#describeClassMismatch} itself, so normalisation and the
     * {@code ALL} wildcard cannot drift from the class matcher.
     * </p>
     *
     * <p>
     * <b>Three deliberate refusals</b>, each returning {@code null} (i.e. leaving the pre-Fix-#206
     * behaviour) rather than guessing:
     * </p>
     * <ul>
     * <li><b>SDTM class scopes.</b> Judged only when the block names at least one ADaM structure
     * token ({@link AdamDataStructureDetector#structureTokenFromDeclaredClass}). An SDTM class
     * genuinely is undetermined without a Library, and the wave-27 census found <b>0</b> files
     * mixing an SDTM class with an ADaM structure, so the two populations do not overlap.</li>
     * <li><b>{@code Exclude}.</b> Lifting an Exclude list over a set is not expressible through
     * {@link ScopeMatcher#describeClassMismatch} per token (that method folds Include and Exclude
     * into one verdict), and the corpus has <b>0</b> ADaM {@code Classes.Exclude} entries — its
     * only four are {@code RELATIONSHIP}, which is SDTM. Reimplementing the lift here would put
     * matcher semantics in the harness for a population of zero.</li>
     * <li><b>An empty/absent {@code Include}.</b> Nothing to judge.</li>
     * </ul>
     */
    // Package-private (not private) so AdamClassScopeGateTest can drive it directly with a
    // synthetic Rule + structure set; every branch below is otherwise reachable only through a
    // .cdt fixture, and adding fixtures would move the migration ratchet's scenario counts.
    static @Nullable String describeAdamClassMismatch(Rule aRule, List<String> aStructures)
    {
        if (aRule.getScope() == null || aRule.getScope().getClasses() == null)
        {
            return null;
        }
        ClassScope classes = aRule.getScope().getClasses();
        List<String> include = classes.getInclude();
        if (include == null || include.isEmpty()
                || (classes.getExclude() != null && !classes.getExclude().isEmpty()))
        {
            return null;
        }
        if (include.stream().noneMatch(
                t -> AdamDataStructureDetector.structureTokenFromDeclaredClass(t) != null))
        {
            return null;
        }
        for (String structure : aStructures)
        {
            if (ScopeMatcher.describeClassMismatch(aRule, structure) == null)
            {
                return null;
            }
        }
        // Names the MOST SPECIFIC detected token first, like describeDataStructureMismatch, and
        // appends the full set when there is an is-a chain so the reader can see why a
        // supertype-scoped rule would have matched.
        return "data structure " + aStructures.getFirst() + " not in Scope.Classes.Include "
                + include + (aStructures.size() > 1 ? " (detected " + aStructures + ")" : "");
    }


    /**
     * Phase 3a.3: whether {@code aRule} carries one of the two gates that read the declared ADaM
     * tier. Mirrors {@code RuleRunner}'s own guard exactly — the runner computes the structure set
     * (and therefore consults {@code getDeclaredDatasetClass}) only inside
     * {@code rule.getScope() != null && (getDataStructures() != null || getSubclasses() != null)}.
     * For every other rule a declared-tier decoration is unobservable, which is what lets
     * {@link ScenarioDeclaredScopeProvider#install} leave the 543 {@code class=}-only fixtures that
     * carry no provider block alone.
     */
    private static boolean readsDeclaredAdamTier(Rule aRule)
    {
        return aRule.getScope() != null && (aRule.getScope().getDataStructures() != null
                || aRule.getScope().getSubclasses() != null);
    }


    /** Column names in declared order — mirrors {@code DatasetRuleResolver.doGenerate}. */
    private static List<String> scopeColumnNames(OverlayDataTable aPrimary)
    {
        DataTableMeta meta = aPrimary.getMetaData();
        List<String> names = new ArrayList<>(meta.getColumnCount());
        for (int i = 0; i < meta.getColumnCount(); i++)
        {
            names.add(meta.getColumn(i).getName());
        }
        return names;
    }


    /** Parse a {@code #define-xml} sidecar, resolved against the scenario file's directory. */
    private static OdmDefineXMLProvider parseDefineXml(Path aScenarioFile, String aRelative)
    {
        Path file = aScenarioFile.resolveSibling(aRelative);
        try
        {
            return new OdmDefineXMLProvider(new DefineXmlParser().parse(file.toFile()));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("could not parse #define-xml sidecar " + file, e);
        }
    }


    /**
     * The declared-standard membership gate: resolve the scenario's declared standard+version (if
     * any) and assert the rule is a member of this family's package for it. Families with
     * {@link #requireDeclaredStandard()} additionally fail scenarios that declare nothing.
     */
    private void checkDeclaredStandardMembership(RuleTestScenario aScenario)
    {
        String declared = declaredStandardVersion(aScenario);
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
     * declares one, else the first canonical package containing the rule
     * ({@link #DEFAULT_PACKAGE_PREFERENCE}), else — deterministically — the last sorted package
     * that contains it.
     */
    private @Nullable Rule resolveRule(RuleTestScenario aScenario)
    {
        Map<String, Map<String, Rule>> packages = familyPackages();
        String declared = declaredStandardVersion(aScenario);
        if (declared != null)
        {
            Map<String, Rule> pkg = packages.get(declared);
            return pkg != null ? pkg.get(aScenario.getCoreId()) : null;
        }
        for (String preferred : DEFAULT_PACKAGE_PREFERENCE)
        {
            Map<String, Rule> pkg = packages.get(preferred);
            if (pkg != null && pkg.containsKey(aScenario.getCoreId()))
            {
                return pkg.get(aScenario.getCoreId());
            }
        }
        Rule found = null;
        for (String standardVersion : new java.util.TreeSet<>(packages.keySet()))
        {
            Rule candidate = packages.get(standardVersion).get(aScenario.getCoreId());
            if (candidate != null)
            {
                found = candidate;
            }
        }
        return found;
    }


    /** The scenario's declared {@code <standard>-<version>}, or {@code null} if undeclared. */
    private static @Nullable String declaredStandardVersion(RuleTestScenario aScenario)
    {
        LibraryRef ref = aScenario.getLibraryRef();
        if (ref != null)
        {
            return ref.getStandard() + "-" + ref.getVersion();
        }
        MapBackedLibraryMetadataProvider lib = aScenario.getLibrary();
        if (lib != null && !UNDECLARED_STANDARD.equals(lib.getStandard()))
        {
            return lib.getStandard() + "-" + lib.getVersion();
        }
        return null;
    }


    private static @Nullable RuntimeDictionaryProvider resolveDictionaries(
            RuleTestScenario aScenario)
    {
        if (aScenario.getDictionaries() == null)
        {
            return null;
        }
        // Parser guarantees the only accepted value is "dummy".
        RuntimeDictionaryProvider provider = dummyDictionaries;
        if (provider == null)
        {
            try
            {
                provider = RuntimeDictionaryProvider.loadDirectory(DUMMY_DICTIONARIES_DIR);
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(
                        "could not load the dummy dictionaries from " + DUMMY_DICTIONARIES_DIR, e);
            }
            dummyDictionaries = provider;
        }
        return provider;
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
        Map<String, Map<String, Rule>> packages = new HashMap<>();
        String prefix = "rules-" + aFamily + "-";
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(RULES_DIR, prefix + "*.json"))
        {
            for (Path p : dir)
            {
                String name = p.getFileName().toString();
                String standardVersion = name.substring(prefix.length(),
                        name.length() - ".json".length());
                RulePackage pkg = RulePackageLoader.load(p);
                Map<String, Rule> byCoreId = new HashMap<>();
                for (Rule rule : pkg.getRules().values())
                {
                    String coreId = rule.getCore() != null ? rule.getCore().getId() : rule.getId();
                    byCoreId.put(coreId, rule);
                }
                packages.put(standardVersion, byCoreId);
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("could not load rule packages for family " + aFamily, e);
        }
        if (packages.isEmpty())
        {
            throw new IllegalStateException(
                    "no rule packages found for family " + aFamily + " under " + RULES_DIR);
        }
        return packages;
    }
}
