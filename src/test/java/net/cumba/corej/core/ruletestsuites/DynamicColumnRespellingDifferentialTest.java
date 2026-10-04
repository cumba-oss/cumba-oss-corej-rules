package net.cumba.corej.core.ruletestsuites;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.model.Binding;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.rulespec.SpecLoader;
import net.cumba.corej.core.rulespec.SpecRunner;
import net.cumba.corej.core.rulespec.SpecSchema;
import net.cumba.corej.ruletest.cdt.ruletest.ExecutionVerdictCheck;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.corej.ruletest.cdt.ruletest.ViolationLocationCheck;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

/**
 * The equivalence instrument of {@code PLAN-dynamic-column-corpus-respelling} (§2.8): every
 * scenario of a respelled rule is executed twice — once against the rule's <b>base</b> text and
 * once against the respelled text — and the <b>whole finding rows</b> are compared: row, reported
 * variable names and values <em>in the order the engine emits them</em>, USUBJID / SEQ, keys, group
 * key, unit, severity level, plus the execution state (executed / skipped / error with its reason),
 * the violation count and the truncation flag. The rows keep their emission order too.
 *
 * <p>
 * <b>Why it exists.</b> The findings snapshot never runs the 42 {@code ${…}} rules (it is an SDTM
 * study run), and the {@code .cdt} suites assert only part of each finding (several {@code invalid}
 * scenarios pin {@code USUBJID} alone). A respelling that reports one column fewer, in another
 * order, or at a different severity, would therefore pass every existing gate. This test is the
 * proof that the respelled rules answer <em>identically</em>, except where the plan lists a move.
 * </p>
 *
 * <p>
 * <b>Three failure classes, never a silent pass.</b> A scenario whose two outcomes differ only in
 * <em>order</em> — the same rows and the same reported values, emitted in another sequence — is its
 * own failure class ("ORDER-ONLY"), reported apart from a content difference, because the report
 * keeps the engine's order ({@code Violation#getValues()} is a {@code LinkedHashMap} in
 * {@code Output_Variables} order). A scenario the library-ref gate would <em>skip</em>
 * ({@code ScenarioLibrarySupport.resolveOrSkip} aborts through {@code Assumptions}) is a FAILURE
 * here: one {@code @Test} runs the whole population, so an abort would otherwise skip every
 * scenario after it. The summary line is printed whatever the outcome.
 * </p>
 *
 * <p>
 * <b>Opt-in.</b> It runs only with {@code -Dcorej.respell.base=DIR}, a corpus directory holding the
 * <b>base</b> packages ({@code rules-FAMILY-*.json}) generated from the base rule text. The base
 * packages of the plan were generated this way (internal base {@code 1451db90}, OSS base
 * {@code 45be095b}), and the same recipe regenerates them:
 * </p>
 *
 * <pre>
 * git -C REPO archive BASE rules-src | tar -x -C TREE      # TREE an empty directory
 * mvn -o -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=CP.txt
 * cd TREE &amp;&amp; java -cp "REPO/target/test-classes:REPO/target/classes:$(cat CP.txt)" \
 *     net.cumba.corej.core.expr.convert.RuleCorpusGenerator DIR DIR-release
 * </pre>
 *
 * <p>
 * ({@code RuleCorpusGenerator} reads {@code rules-src/} and
 * {@code rules-src/package-standards.json} relative to the working directory, which is why it runs
 * inside {@code TREE}.) Without the property the class is skipped — it is the plan's instrument,
 * not a permanent gate. The plan's S2 rules ({@code CDISC-AD0102 / 0103 / 0104 / 0498},
 * {@code PMDA-AD0102 / 0103 / 0104}) landed last (§6.2, after the engine fix of owner ruling (a),
 * 2026-10-04, which makes {@code var_exists($n)} read the bound name), so all 42 rules of the plan
 * are in the population (with {@code CDISC-AD0719}, joined by its listed moves). Nothing here
 * claims that the {@code ${…}} path has engine-test coverage of its own.
 * </p>
 *
 * <p>
 * <b>The contract is committed.</b> The plan's 26 expected moves are
 * {@code src/test/resources/respell-differential/expected-moves.txt} (verdict moves) and
 * {@code expected-report-moves.txt} (verdict-neutral row moves), read when the matching property is
 * absent. A default run (no {@code corej.respell.*} property besides the base) also pins its
 * population: the exact rule roster ({@code population.txt}), the scenario count
 * ({@value #EXPECTED_SCENARIOS}), the three rulespec fixtures and the {@code ${…}} rules that have
 * no scenario directory at all (none since §6.2 gave {@code CDISC-AD0104} its first scenarios). A
 * count pin alone is blind to a re-key, which is why the roster is a list of ids.
 * </p>
 *
 * <p>
 * Properties:
 * </p>
 * <ul>
 * <li>{@code corej.respell.rules} — comma-separated {@code Core.Id}s; default: every rule of the
 * {@code cdisc} and {@code pmda} families whose base Check, {@code Bindings} or
 * {@code Output_Variables} carries {@code ${}. The rule of every listed move joins the population
 * either way. An id found in neither family's scenario tree fails the run;</li>
 * <li>{@code corej.respell.expectedMoves} — comma-separated scenario paths relative to the scenario
 * root ({@code cdisc/CDISC-AD0592/CDISC-AD0592-…-ADLBC.cdt}) whose two outcomes are <b>expected</b>
 * to differ: the plan's listed Q1 / Q3 / AD0719 moves, each carried by a sensitivity scenario. ⚠
 * Every listed path MUST differ, MUST fail the scenario's own expectation on the base text
 * (red-first) and MUST pass it on the respelled text; an entry that does not move fails the test,
 * so the list cannot go stale;</li>
 * <li>{@code corej.respell.expectedReportMoves} — the same for a <b>verdict-neutral</b> move: the
 * finding rows differ (e.g. the Q3 narrowing of an {@code Output_Variables} template drops a
 * non-IG-width column from the report) while the scenario's own expectation holds on both texts.
 * Every listed path MUST differ and MUST pass on both;</li>
 * <li>{@code corej.respell.report} — a file to write every scenario's two outcomes to, row by row,
 * so the base rows can be read (the reported-variable set of §2.3 M1 is measured from it).</li>
 * </ul>
 *
 * <p>
 * ⚠ <b>What the verdict half judges, and what it leaves to the default build.</b> The verdicts
 * computed here are {@code ExecutionVerdictCheck} + {@code ViolationLocationCheck} over the outcome
 * — the two checks {@code AbstractRuleTestSuitesFactory.assertScenarioVerdict} makes, minus its
 * {@code #library-ref} degraded-store rescue. The respelled rule's own expectation therefore stays
 * the default build's ({@code RuleTestSuitesCdiscFactoryTest} / {@code
 * RuleTestSuitesPmdaFactoryTest}) to assert; this class asserts it for the listed moves only as a
 * cross-check. ⚠ <b>Scenario discovery is one level deep</b> ({@code Files.list} of {@code
 * FAMILY/CORE_ID/}), while the factory walks the whole subtree ({@code Files.walk}); a rule
 * directory with a subdirectory therefore fails the run here rather than being half-read.
 * </p>
 *
 * <p>
 * <b>The rulespec fixtures</b> {@code CDISC-AD0500 / AD0720 / AD0897} (plan §2.8) run through the
 * same comparison: the spec is executed by {@link SpecRunner#executeRaw} once with its {@code
 * coreId:} resolved from the base packages and once from the current corpus, and the raw engine
 * rows are compared. {@code RuleExecutionSpecTest} alone is not the same statement: its {@code
 * ViolationNormaliser} keeps neither level, keys, unit nor group key, reads {@code ""} as null and
 * sorts {@code $}-lists. A third run resolves the {@code coreId} from the current corpus by the
 * same substitution as the base run and must render identically to the loader's own resolution,
 * which proves the substitution faithful.
 * </p>
 */
@EnabledIfSystemProperty(named = DynamicColumnRespellingDifferentialTest.BASE_PROPERTY,
        matches = ".+")
class DynamicColumnRespellingDifferentialTest
{

    static final String BASE_PROPERTY = "corej.respell.base";

    private static final String RULES_PROPERTY = "corej.respell.rules";

    private static final String MOVES_PROPERTY = "corej.respell.expectedMoves";

    private static final String REPORT_MOVES_PROPERTY = "corej.respell.expectedReportMoves";

    private static final String REPORT_PROPERTY = "corej.respell.report";

    private static final Path PROJECT_DIR = Path.of(System.getProperty("projectBasedir"));

    private static final Path RESOURCE_BASE = PROJECT_DIR
            .resolve("src/test/resources/net/cumba/corej/core/ruletestsuites");

    /** The committed contract: expected moves and the default run's population roster. */
    private static final Path CONTRACT_DIR = PROJECT_DIR
            .resolve("src/test/resources/respell-differential");

    private static final Path SPECS_DIR = PROJECT_DIR.resolve("rulespec/specs");

    /** The rulespec fixtures of plan §2.8, run through the same comparison. */
    private static final List<String> RULESPECS = List.of("CDISC-AD0500", "CDISC-AD0720",
            "CDISC-AD0897");

    /** The default run's scenario count; the rule roster is {@code population.txt}. */
    static final int EXPECTED_SCENARIOS = 173;

    /** {@code ${…}} rules of the default population with no scenario directory in either family. */
    private static final Set<String> EXPECTED_WITHOUT_SCENARIOS = Set.of();

    /** The families the {@code ${…}} rules live in; a factory per family runs the scenarios. */
    private static final List<AbstractRuleTestSuitesFactory> FACTORIES = List
            .of(new RuleTestSuitesCdiscFactoryTest(), new RuleTestSuitesPmdaFactoryTest());

    private static final Pattern COMMA = Pattern.compile(",");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final YAMLMapper YAML = new YAMLMapper();

    @TempDir
    Path tmp;

    /** Everything one run accumulates; printed and judged after the loops. */
    private static final class Ledger
    {

        final Set<String> expectedMoves;

        final Set<String> expectedReportMoves;

        final StringBuilder report = new StringBuilder();

        final List<String> unexpectedDiffs = new ArrayList<>();

        final List<String> orderOnlyDiffs = new ArrayList<>();

        final List<String> aborted = new ArrayList<>();

        final Set<String> moved = new LinkedHashSet<>();

        final List<String> verdictFailures = new ArrayList<>();

        final Set<String> rulesSeen = new TreeSet<>();

        final Set<String> withoutScenarios = new TreeSet<>();

        int scenarios;

        int specs;

        int identical;

        Ledger(Set<String> aExpectedMoves, Set<String> aExpectedReportMoves)
        {
            expectedMoves = aExpectedMoves;
            expectedReportMoves = aExpectedReportMoves;
        }


        String summary()
        {
            return "[respell-differential] rules=" + rulesSeen.size() + " scenarios=" + scenarios
                    + " rulespecs=" + specs + " identical=" + identical + " moved=" + moved.size()
                    + " order-only-unlisted=" + orderOnlyDiffs.size() + " content-unlisted="
                    + unexpectedDiffs.size() + " aborted=" + aborted.size() + " verdict-failures="
                    + verdictFailures.size() + " moved-set=" + moved;
        }
    }

    @Test
    void respelledRulesAnswerIdenticallyExceptTheListedMoves() throws IOException
    {
        Path baseDir = Path.of(System.getProperty(BASE_PROPERTY));
        assertTrue(Files.isDirectory(baseDir), BASE_PROPERTY + " is not a directory: " + baseDir);
        String rulesRaw = System.getProperty(RULES_PROPERTY);
        String movesRaw = System.getProperty(MOVES_PROPERTY);
        String reportMovesRaw = System.getProperty(REPORT_MOVES_PROPERTY);
        boolean defaultRun = rulesRaw == null && movesRaw == null && reportMovesRaw == null;
        Set<String> expectedMoves = movesRaw != null ? csv(movesRaw)
                : contract("expected-moves.txt");
        Set<String> expectedReportMoves = reportMovesRaw != null ? csv(reportMovesRaw)
                : contract("expected-report-moves.txt");
        Set<String> onlyRules = csv(rulesRaw == null ? "" : rulesRaw);
        Set<String> allExpected = new LinkedHashSet<>(expectedMoves);
        allExpected.addAll(expectedReportMoves);
        assertFalse(defaultRun && allExpected.isEmpty(), "the committed contract lists no moves");

        Ledger ledger = new Ledger(expectedMoves, expectedReportMoves);
        try
        {
            runScenarios(baseDir, onlyRules, allExpected, ledger);
            runRulespecs(baseDir, ledger);
        }
        finally
        {
            String summary = ledger.summary();
            System.out.println(summary);
            String reportPath = System.getProperty(REPORT_PROPERTY, "");
            if (!reportPath.isBlank())
            {
                Files.writeString(Path.of(reportPath), summary + "\n" + ledger.report, UTF_8);
            }
        }

        assertFalse(ledger.rulesSeen.isEmpty(), "no rule of the population has scenarios");
        assertTrue(ledger.aborted.isEmpty(),
                () -> ledger.aborted.size() + " scenario(s) ABORTED (a skip is a failure in the"
                        + " differential — is the metadata store available?):\n"
                        + String.join("\n", ledger.aborted));
        assertTrue(ledger.orderOnlyDiffs.isEmpty(),
                () -> ledger.orderOnlyDiffs.size() + " scenario(s) differ in ORDER ONLY (the same"
                        + " rows and values, emitted in another sequence) and are not listed"
                        + " moves:\n" + String.join("\n", ledger.orderOnlyDiffs));
        assertTrue(ledger.unexpectedDiffs.isEmpty(),
                () -> ledger.unexpectedDiffs.size() + " scenario(s) moved that are not listed"
                        + " moves:\n" + String.join("\n", ledger.unexpectedDiffs));
        Set<String> stale = new LinkedHashSet<>(allExpected);
        stale.removeAll(ledger.moved);
        assertTrue(stale.isEmpty(),
                () -> "listed moves that did NOT move (stale list, or the base and respelled text"
                        + " agree): " + stale);
        assertTrue(ledger.verdictFailures.isEmpty(),
                () -> String.join("\n", ledger.verdictFailures));
        assertEquals(allExpected, ledger.moved,
                () -> "the moved set must be exactly the listed moves; " + ledger.summary());
        if (defaultRun)
        {
            assertEquals(contract("population.txt"), ledger.rulesSeen,
                    "the default population's rule roster (population.txt)");
            assertEquals(EXPECTED_SCENARIOS, ledger.scenarios, "the default scenario count");
            assertEquals(EXPECTED_WITHOUT_SCENARIOS, ledger.withoutScenarios,
                    "the ${…} rules with no scenario directory");
            assertEquals(RULESPECS.size(), ledger.specs, "the rulespec fixtures run");
        }
    }


    private static void runScenarios(Path baseDir, Set<String> onlyRules, Set<String> allExpected,
            Ledger ledger)
        throws IOException
    {
        Set<String> foundInAFamily = new TreeSet<>();
        Set<String> candidates = new TreeSet<>();
        for (AbstractRuleTestSuitesFactory factory : FACTORIES)
        {
            String family = factory.family();
            Map<String, Map<String, Rule>> basePackages = AbstractRuleTestSuitesFactory
                    .loadFamily(baseDir, family);
            Map<String, Map<String, Rule>> currentPackages = AbstractRuleTestSuitesFactory
                    .loadFamily(family);
            Set<String> population = new TreeSet<>(
                    onlyRules.isEmpty() ? dynamicRules(basePackages) : onlyRules);
            // A listed move names its rule in its path (<family>/<Core.Id>/<file>), and that rule
            // joins the population whatever its base text — the None-template census rules
            // (CDISC-AD0719) carry no `${` and would otherwise be invisible to the instrument.
            for (String path : allExpected)
            {
                String[] parts = path.split("/", -1);
                if (parts.length == 3 && parts[0].equals(family))
                {
                    population.add(parts[1]);
                }
            }
            candidates.addAll(population);
            for (String coreId : population)
            {
                Path ruleDir = RESOURCE_BASE.resolve(family).resolve(coreId);
                if (!Files.isDirectory(ruleDir))
                {
                    continue; // the other family's rule, or a rule without scenarios (below)
                }
                foundInAFamily.add(coreId);
                ledger.rulesSeen.add(coreId);
                for (Path file : scenarioFiles(ruleDir))
                {
                    compareScenario(factory, file, coreId, basePackages, currentPackages, ledger);
                }
            }
        }
        Set<String> nowhere = new TreeSet<>(candidates);
        nowhere.removeAll(foundInAFamily);
        if (!onlyRules.isEmpty())
        {
            Set<String> unknown = new TreeSet<>(onlyRules);
            unknown.retainAll(nowhere);
            assertTrue(unknown.isEmpty(), () -> RULES_PROPERTY
                    + " names id(s) with no scenario directory in either family: " + unknown);
        }
        ledger.withoutScenarios.addAll(nowhere);
    }


    /** The rule directory's scenarios — one level; a subdirectory would be half-read, so fail. */
    private static List<Path> scenarioFiles(Path ruleDir) throws IOException
    {
        List<Path> files;
        try (Stream<Path> list = Files.list(ruleDir))
        {
            List<Path> entries = list.sorted().toList();
            List<Path> dirs = entries.stream().filter(Files::isDirectory).toList();
            assertTrue(dirs.isEmpty(), () -> ruleDir + " has subdirectories " + dirs
                    + " — discovery here is one level (Files.list), the factory's is Files.walk");
            files = entries.stream().filter(p -> p.getFileName().toString().endsWith(".cdt"))
                    .filter(AbstractRuleTestSuitesFactory::hasShebang).toList();
        }
        assertFalse(files.isEmpty(), ruleDir + " has no scenarios");
        return files;
    }


    private static void compareScenario(AbstractRuleTestSuitesFactory factory, Path file,
            String coreId, Map<String, Map<String, Rule>> basePackages,
            Map<String, Map<String, Rule>> currentPackages, Ledger ledger)
        throws IOException
    {
        ledger.scenarios++;
        String rel = RESOURCE_BASE.relativize(file).toString().replace('\\', '/');
        RuleTestScenario s = RuleTestCdt.load(file);
        Rule base = AbstractRuleTestSuitesFactory.resolveIn(basePackages, s);
        assertNotNull(base, "base packages carry no " + coreId + " for " + rel);
        Rule current = AbstractRuleTestSuitesFactory.resolveIn(currentPackages, s);
        assertNotNull(current, "current packages carry no " + coreId + " for " + rel);
        OverlayDataTable primary = s.primaryTable();
        assertNotNull(primary, rel + " names no primary dataset");

        AbstractRuleTestSuitesFactory.EvalOutcome b;
        AbstractRuleTestSuitesFactory.EvalOutcome c;
        try
        {
            b = factory.evaluateScenario(file, s, base, primary);
            c = factory.evaluateScenario(file, s, current, primary);
        }
        catch (TestAbortedException e)
        {
            ledger.aborted.add(rel + ": " + e.getMessage());
            return;
        }
        judge(new Comparison(rel, render(b), render(c), renderCanonical(b), renderCanonical(c),
                passes(s, b, primary), passes(s, c, primary)), ledger);
    }

    /** One scenario's two outcomes: exact and order-insensitive renderings, and both verdicts. */
    private record Comparison(String rel, String baseText, String currentText, String baseCanonical,
            String currentCanonical, boolean basePasses, boolean currentPasses)
    {
    }

    /** Classifies one comparison and records it; shared by the scenarios and the rulespecs. */
    private static void judge(Comparison cmp, Ledger ledger)
    {
        String rel = cmp.rel();
        String baseText = cmp.baseText();
        String currentText = cmp.currentText();
        boolean basePasses = cmp.basePasses();
        boolean currentPasses = cmp.currentPasses();
        boolean listedMove = ledger.expectedMoves.contains(rel);
        boolean listedReportMove = ledger.expectedReportMoves.contains(rel);
        boolean differs = !baseText.equals(currentText);
        boolean orderOnly = differs && cmp.baseCanonical().equals(cmp.currentCanonical());
        ledger.report.append("== ").append(rel).append('\n');
        ledger.report.append("base-verdict: ").append(basePasses ? "pass" : "FAIL")
                .append("  respelled-verdict: ").append(currentPasses ? "pass" : "FAIL")
                .append("  class: ")
                .append(!differs ? "IDENTICAL" : orderOnly ? "ORDER-ONLY" : "CONTENT").append('\n');
        ledger.report.append("-- base\n").append(baseText);
        if (differs)
        {
            ledger.report.append("-- respelled\n").append(currentText);
            ledger.moved.add(rel);
        }
        else
        {
            ledger.report.append("-- respelled: IDENTICAL\n");
            ledger.identical++;
        }
        if (differs && !listedMove && !listedReportMove)
        {
            String detail = rel + "\n-- base\n" + baseText + "-- respelled\n" + currentText;
            (orderOnly ? ledger.orderOnlyDiffs : ledger.unexpectedDiffs).add(detail);
        }
        if (listedMove && basePasses)
        {
            ledger.verdictFailures.add(rel + " is a listed move but the BASE text already"
                    + " satisfies it — the sensitivity scenario is not red-first");
        }
        if (listedMove && !currentPasses)
        {
            ledger.verdictFailures
                    .add(rel + " is a listed move but the RESPELLED text does not" + " satisfy it");
        }
        if (listedReportMove && !(basePasses && currentPasses))
        {
            ledger.verdictFailures.add(rel + " is a listed report move but its own expectation"
                    + " does not hold on both texts (base " + basePasses + ", respelled "
                    + currentPasses + ")");
        }
        if (!listedMove && !basePasses)
        {
            ledger.verdictFailures.add(
                    rel + " fails its own expectation on the BASE text but is not a listed move");
        }
    }


    /**
     * The rulespec fixtures of §2.8: the spec run with its {@code coreId:} resolved from the base
     * packages against the spec as the loader resolves it from the current corpus, raw rows.
     */
    private void runRulespecs(Path baseDir, Ledger ledger) throws IOException
    {
        for (String id : RULESPECS)
        {
            Path specFile = SPECS_DIR.resolve(id + ".yaml");
            assertTrue(Files.isRegularFile(specFile), "no rulespec " + specFile);
            ledger.specs++;
            String rel = "rulespec/" + id + ".yaml";
            SpecSchema current = SpecLoader.load(specFile);
            SpecSchema base = substituted(specFile, baseDir, "base");
            SpecSchema control = substituted(specFile, CorpusPaths.rules(), "control");
            AbstractRuleTestSuitesFactory.EvalOutcome c = outcomeOf(SpecRunner.executeRaw(current));
            AbstractRuleTestSuitesFactory.EvalOutcome b = outcomeOf(SpecRunner.executeRaw(base));
            AbstractRuleTestSuitesFactory.EvalOutcome k = outcomeOf(SpecRunner.executeRaw(control));
            assertEquals(render(c), render(k), () -> rel + ": the coreId substitution is not"
                    + " faithful — resolving from the current corpus by substitution renders"
                    + " differently from the loader's own resolution");
            judge(new Comparison(rel, render(b), render(c), renderCanonical(b), renderCanonical(c),
                    SpecRunner.runSchema(base).passed(), SpecRunner.runSchema(current).passed()),
                    ledger);
        }
    }


    /**
     * The spec with its {@code rule: {coreId: X}} replaced by X's body from {@code aRulesDir}'s
     * first package carrying it ({@code Authorities} dropped) — exactly what
     * {@code SpecLoader.resolveCoreId} does against the shipped corpus.
     */
    private SpecSchema substituted(Path aSpecFile, Path aRulesDir, String aTag) throws IOException
    {
        JsonNode root = YAML.readTree(aSpecFile.toFile());
        JsonNode ruleNode = root.get("rule");
        assertTrue(
                ruleNode instanceof ObjectNode && ruleNode.size() == 1
                        && ruleNode.hasNonNull("coreId"),
                aSpecFile + ": the substitution handles a bare `rule: {coreId: …}` only");
        String coreId = ruleNode.get("coreId").asText();
        ObjectNode body = null;
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(aRulesDir, "rules-*.json"))
        {
            List<Path> packages = new ArrayList<>();
            dir.forEach(packages::add);
            packages.sort(null);
            for (Path pkg : packages)
            {
                JsonNode entry = JSON.readTree(pkg.toFile()).path("rules").get(coreId);
                if (entry instanceof ObjectNode obj)
                {
                    body = obj.deepCopy();
                    break;
                }
            }
        }
        assertNotNull(body, coreId + " is in no package of " + aRulesDir);
        body.remove("Authorities");
        ((ObjectNode) root).set("rule", body);
        Path out = tmp
                .resolve(aTag + "-" + aSpecFile.getFileName().toString().replace(".yaml", ".json"));
        Files.writeString(out, JSON.writeValueAsString(root), UTF_8);
        return SpecLoader.load(out);
    }


    private static AbstractRuleTestSuitesFactory.@Nullable EvalOutcome outcomeOf(
            @Nullable RuleExecutionResult r)
    {
        if (r == null)
        {
            return null;
        }
        List<Violation> v = r.getViolations() != null ? r.getViolations() : List.of();
        boolean ran = !(r.isSkipped() || r.isError());
        return new AbstractRuleTestSuitesFactory.EvalOutcome(r.hasViolations(), ran, r.isError(), v,
                r.getViolationCount(), r.isTruncated(),
                ran ? null : r.getStatus() + ": " + r.getStatusMessage());
    }


    /** The default population roster or a move list: one entry per line, {@code #} comments. */
    private static Set<String> contract(String aName) throws IOException
    {
        Set<String> out = new LinkedHashSet<>();
        for (String line : Files.readAllLines(CONTRACT_DIR.resolve(aName), UTF_8))
        {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#"))
            {
                out.add(t);
            }
        }
        return out;
    }


    /**
     * Rule ids whose base Check source, {@code Bindings} expressions or {@code Output_Variables}
     * carry {@code ${}.
     */
    private static Set<String> dynamicRules(Map<String, Map<String, Rule>> packages)
    {
        Set<String> ids = new TreeSet<>();
        for (Map<String, Rule> pkg : packages.values())
        {
            for (Map.Entry<String, Rule> e : pkg.entrySet())
            {
                Rule r = e.getValue();
                boolean dynamic = checkSource(r).contains("${");
                if (!dynamic && r.getBindings() != null)
                {
                    dynamic = r.getBindings().stream().map(Binding::getExpression)
                            .anyMatch(x -> x != null && x.contains("${"));
                }
                if (!dynamic && r.getOutcome() != null
                        && r.getOutcome().getOutputVariables() != null)
                {
                    dynamic = r.getOutcome().getOutputVariables().stream()
                            .anyMatch(v -> v.contains("${"));
                }
                if (dynamic)
                {
                    ids.add(e.getKey());
                }
            }
        }
        return ids;
    }


    private static String checkSource(Rule r)
    {
        CheckCondition check = r.getCheck();
        if (check instanceof CheckConditionExpression e)
        {
            return e.source();
        }
        Object json = r.getCheckJson();
        return json == null ? "" : json.toString();
    }


    private static boolean passes(RuleTestScenario s,
            AbstractRuleTestSuitesFactory.@Nullable EvalOutcome o, OverlayDataTable primary)
    {
        if (o == null)
        {
            return true; // out of scope on both sides; the factory asserts the declaration
        }
        ExecutionVerdictCheck.Result verdict = ExecutionVerdictCheck.verify(s,
                new ExecutionVerdictCheck.Outcome(o.executed(), o.errored(), o.violated(),
                        o.totalCount(), o.notExecutedReason()));
        return verdict.pass() && ViolationLocationCheck
                .verify(s, o.violations(), o.totalCount(), o.truncated(), primary).pass();
    }


    /**
     * The whole outcome as text, in the engine's order: state, then one line per finding row in
     * emission order, each row's maps in their iteration order.
     */
    static String render(AbstractRuleTestSuitesFactory.@Nullable EvalOutcome o)
    {
        return render(o, false);
    }


    /** The same, order-insensitive: rows sorted, maps sorted — the ORDER-ONLY classifier. */
    static String renderCanonical(AbstractRuleTestSuitesFactory.@Nullable EvalOutcome o)
    {
        return render(o, true);
    }


    private static String render(AbstractRuleTestSuitesFactory.@Nullable EvalOutcome o,
            boolean aSorted)
    {
        if (o == null)
        {
            return "OUT-OF-SCOPE\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(
                String.format(Locale.ROOT,
                        "state executed=%s errored=%s violated=%s count=%d"
                                + " truncated=%s reason=%s%n",
                        o.executed(), o.errored(), o.violated(), o.totalCount(), o.truncated(),
                        o.notExecutedReason()));
        List<String> rows = new ArrayList<>();
        for (Violation v : o.violations())
        {
            rows.add(renderRow(v, aSorted));
        }
        if (aSorted)
        {
            rows.sort(null);
        }
        for (String row : rows)
        {
            sb.append(row).append('\n');
        }
        return sb.toString();
    }


    private static String renderRow(Violation v, boolean aSorted)
    {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("row", v.getRowNumber());
        row.put("usubjid", v.getUsubjid());
        row.put("seq", v.getSeq());
        row.put("level", v.getLevel());
        row.put("unit", v.getUnit());
        row.put("keys", ordered(v.getKeys(), aSorted));
        row.put("group", ordered(v.getGroupKey(), aSorted));
        row.put("values", ordered(v.getValues(), aSorted));
        return row.toString();
    }


    /** A copy that keeps the source's iteration order, or a sorted one for the canonical form. */
    private static Map<String, String> ordered(@Nullable Map<String, String> aMap, boolean aSorted)
    {
        if (aMap == null)
        {
            return Map.of();
        }
        return aSorted ? new TreeMap<>(aMap) : new LinkedHashMap<>(aMap);
    }


    private static Set<String> csv(String raw)
    {
        Set<String> out = new LinkedHashSet<>();
        COMMA.splitAsStream(raw).map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
        return out;
    }
}
