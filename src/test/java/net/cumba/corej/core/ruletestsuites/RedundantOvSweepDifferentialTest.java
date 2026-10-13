package net.cumba.corej.core.ruletestsuites;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleSpecialiser;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.corej.core.run.DatasetExecutionSummary;
import net.cumba.corej.core.run.StudyValidationParams;
import net.cumba.corej.core.run.StudyValidationResult;
import net.cumba.corej.core.run.StudyValidationService;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRunner;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.opentest4j.TestAbortedException;

/**
 * The run-time verdict instrument of {@code PLAN-redundant-output-variables-sweep} (§7.2 H1): every
 * scenario of every stripped rule, in all four families, and the whole {@code testdata/study} run,
 * are executed against the <b>base</b> packages (the corpus before the strip) and the
 * <b>current</b> packages (after it), and the <b>whole finding rows</b> are compared — row,
 * reported variable names and values <em>in the order the engine emits them</em>, a {@code null}
 * value explicit ({@code ␀}), USUBJID / SEQ, keys, group key, unit, level, plus the execution state
 * and the violation count.
 *
 * <p>
 * ⚠ <b>{@code k=␀} against an absent key is distinguishable on the scenario arm only.</b> There a
 * row is the {@code Violation}'s own values map, so a key the engine omitted is absent and a key it
 * kept with a null value reads {@code ␀}. The study arm reads the report: a finding carries one
 * {@code getVariableNames()} list for all its rows, and {@code getRowValues(pos)} renders every row
 * over that list, so a key absent from one row and a key present with a null value both read
 * {@code ␀} there. On the study arm only a name missing from (or added to) the finding's whole
 * variable-name list is visible (T2 LOW-3).
 * </p>
 *
 * <p>
 * <b>Why it exists.</b> The findings snapshot is order-blind ({@code FindingsSnapshot} sorts
 * {@code variableNames} and renders {@code values} through a {@code TreeMap}), the {@code .cdt}
 * suites assert verdicts and counts, and the static differential proves member sets on the
 * <em>rule</em>. None of them can see the two ways the strip could change a <em>finding</em>: a key
 * flipping between {@code k=␀} and absent (the Q2 null-key provenance; scenario arm only, see
 * above), and a report column order moving where the manifest did not predict it (Q1). This test is
 * the proof that, <em>on the rows the fixtures reach</em>, the stripped corpus answers identically
 * except for the listed, accepted order moves.
 * </p>
 *
 * <p>
 * <b>Three classes, never a silent pass.</b> A scenario (or a rule of the study run) whose two
 * outcomes differ only in the order of the reported keys — the same rows and values in another
 * sequence — is <b>ORDER-ONLY</b>; it passes <em>only</em> when the rule is listed in
 * {@code src/test/resources/ovsweep/accepted-order-moves.tsv} <b>and</b> its rows pass two order
 * checks (T2 LOW-4):
 * </p>
 * <ol>
 * <li><b>the contract</b> — the base row's keys follow the contract's {@code before} column and the
 * current row's its {@code after} column. This is the independent check: those columns were
 * computed at P0, before the strip, and committed. It applies where the contract states the order
 * for the judged dataset: the {@code package} row for a domain the rule's {@code specialised} row
 * does not list, the {@code specialised} row for its representative domain ({@code AE} when listed,
 * else the first). For the other listed domains of a {@code specialised} row the contract shows no
 * list and only the second check applies; the summary counts both ({@code contract-judged} /
 * {@code contract-n/a}).</li>
 * <li><b>the derivation</b> — each row's keys follow its own side's effective list as the engine
 * projects it ({@code RuleSpecialiser} on the scenario path, re-derived as
 * {@code DatasetRuleResolver} does on the study path). This is self-referential by construction
 * (the rule's list judged against the rule's projection) and proves only that the projection
 * honours the effective list, not that the list is the right one.</li>
 * </ol>
 * <p>
 * Both checks compare only the keys the list carries: a key it does not name (the injected
 * identity, a {@code --} name the contract leaves unresolved) is skipped. An <b>expanded
 * template</b> (wildcard or {@code Expansion:} token) is therefore not order-judged at all — its
 * rows carry the concrete names the expansion produced and its lists the template's, so neither
 * check would compare anything; such outcomes are counted ({@code template-unjudged}), never
 * silently passed as judged, and their order rests on the static differential's arm (c). Any other
 * difference is <b>CONTENT</b> and fails — a key lost or added, {@code k=␀} against absent
 * (scenario arm), a value, a state, a count. A scenario the library gate would skip is a failure
 * here, as in {@code DynamicColumnRespellingDifferentialTest}. The summary line and the per-rule
 * <b>reach</b> roster (rules with at least one finding row in either arm) are printed whatever the
 * outcome.
 * </p>
 *
 * <p>
 * <b>What this proves, and what it leaves to the static arms.</b> It proves, for the rules and rows
 * the fixtures reach, that no finding changed beyond a listed reorder, and that each observed
 * reorder is the committed one. It proves nothing for a rule without reach, for a dataset no
 * fixture holds, for a template's order, or for the effective <em>list</em> itself: those rest on
 * {@code OvSweepStaticDifferential} (member sets and order at package level, through
 * {@code RuleSpecialiser} × every scope domain, and through the expanders), and the lists are
 * pinned from then on by {@code EffectiveOutputVariablesDriftTest}'s golden file.
 * </p>
 *
 * <p>
 * <b>Opt-in.</b> It runs only with {@code -Dcorej.ovsweep.base=DIR}, a corpus directory holding the
 * base packages generated from the pre-strip rule text ({@code git archive <P1 HEAD> rules-src},
 * then {@code RuleCorpusGenerator DIR DIR-release} inside that tree — the recipe of the respelling
 * differential). The population is the committed {@code manifest-roster.txt}, or
 * {@code -Dcorej.ovsweep.rules=A,B} for a subset (every id must have scenarios in some family).
 * {@code -Dcorej.ovsweep.study=false} skips the study arm (the OSS corpus has no fixture); without
 * it a missing fixture is a failure, never a skip. {@code -Dcorej.ovsweep.report=FILE} writes every
 * compared outcome. Not a permanent gate: the plan's instrument, run at P2 / P2b.
 * </p>
 */
@EnabledIfSystemProperty(named = RedundantOvSweepDifferentialTest.BASE_PROPERTY, matches = ".+")
class RedundantOvSweepDifferentialTest
{

    static final String BASE_PROPERTY = "corej.ovsweep.base";

    private static final String RULES_PROPERTY = "corej.ovsweep.rules";

    private static final String STUDY_PROPERTY = "corej.ovsweep.study";

    private static final String REPORT_PROPERTY = "corej.ovsweep.report";

    private static final Path PROJECT_DIR = Path.of(System.getProperty("projectBasedir"));

    private static final Path RESOURCE_BASE = PROJECT_DIR
            .resolve("src/test/resources/net/cumba/corej/core/ruletestsuites");

    private static final Path CONTRACT_DIR = PROJECT_DIR.resolve("src/test/resources/ovsweep");

    private static final List<AbstractRuleTestSuitesFactory> FACTORIES = List.of(
            new RuleTestSuitesCdiscFactoryTest(), new RuleTestSuitesDraftFactoryTest(),
            new RuleTestSuitesFdaFactoryTest(), new RuleTestSuitesPmdaFactoryTest());

    /** The study run's packages — the snapshot's selection ({@code FindingsSnapshot}). */
    private static final List<String> STUDY_PACKAGES = List.of("cdisc-sdtmig-3-4",
            "draft-sdtmig-3-4", "fda-sdtmig-3-4", "pmda-sdtmig-3-4");

    private static final String NULL_MARK = "␀";

    private static final Pattern COMMA = Pattern.compile(",");

    /** Everything one run accumulates; printed and judged after the loops. */
    private static final class Ledger
    {

        final Set<String> accepted;

        final Map<String, List<Move>> contract;

        final StringBuilder report = new StringBuilder();

        final List<String> contentDiffs = new ArrayList<>();

        final List<String> unlistedOrderDiffs = new ArrayList<>();

        final List<String> unpredictedOrderDiffs = new ArrayList<>();

        final List<String> aborted = new ArrayList<>();

        final Set<String> rulesSeen = new TreeSet<>();

        final Set<String> reach = new TreeSet<>();

        final Set<String> orderMovesObserved = new TreeSet<>();

        final Set<String> studyRules = new TreeSet<>();

        int scenarios;

        int identical;

        int orderOnly;

        int contractJudged;

        int contractNotApplicable;

        int templateUnjudged;

        boolean studyRan;

        Ledger(Map<String, List<Move>> aContract)
        {
            contract = aContract;
            accepted = aContract.keySet();
        }


        String summary()
        {
            Set<String> listedWithReachUnobserved = new TreeSet<>(accepted);
            listedWithReachUnobserved.retainAll(reach);
            listedWithReachUnobserved.removeAll(orderMovesObserved);
            return "[ovsweep-differential] rules=" + rulesSeen.size() + " scenarios=" + scenarios
                    + " identical=" + identical + " order-only=" + orderOnly + " (contract-judged="
                    + contractJudged + " contract-n/a=" + contractNotApplicable
                    + " template-unjudged=" + templateUnjudged + ") content=" + contentDiffs.size()
                    + " unlisted-order=" + unlistedOrderDiffs.size() + " unpredicted-order="
                    + unpredictedOrderDiffs.size() + " aborted=" + aborted.size() + " study="
                    + (studyRan ? "ran" : "skipped") + " study-rules=" + studyRules.size()
                    + " reach=" + reach.size() + " listed-moves=" + accepted.size()
                    + " moves-observed=" + orderMovesObserved.size()
                    + " listed-with-reach-unobserved=" + listedWithReachUnobserved.size() + " "
                    + listedWithReachUnobserved;
        }
    }

    @Test
    void strippedRulesAnswerIdenticallyExceptTheAcceptedOrderMoves() throws IOException
    {
        Path baseDir = Path.of(System.getProperty(BASE_PROPERTY));
        assertTrue(Files.isDirectory(baseDir), BASE_PROPERTY + " is not a directory: " + baseDir);
        String rulesRaw = System.getProperty(RULES_PROPERTY);
        Set<String> population = rulesRaw != null ? csv(rulesRaw)
                : contractColumn("manifest-roster.txt", 0);
        assertFalse(population.isEmpty(), "the population is empty");
        Map<String, List<Move>> contract = orderMoves();
        assertFalse(contract.isEmpty(), "accepted-order-moves.tsv lists no rule");
        boolean study = !"false".equalsIgnoreCase(System.getProperty(STUDY_PROPERTY));

        Ledger ledger = new Ledger(contract);
        try
        {
            runScenarios(baseDir, population, rulesRaw != null, ledger);
            if (study)
            {
                runStudy(baseDir, population, ledger);
            }
        }
        finally
        {
            String summary = ledger.summary();
            System.out.println(summary);
            System.out.println("[ovsweep-differential] reach roster: " + ledger.reach);
            String reportPath = System.getProperty(REPORT_PROPERTY, "");
            if (!reportPath.isBlank())
            {
                Files.writeString(Path.of(reportPath),
                        summary + "\nreach: " + ledger.reach + "\norder moves observed: "
                                + ledger.orderMovesObserved + "\n" + ledger.report,
                        UTF_8);
            }
        }

        assertFalse(ledger.rulesSeen.isEmpty(), "no rule of the population has scenarios");
        assertTrue(ledger.aborted.isEmpty(),
                () -> ledger.aborted.size() + " scenario(s) ABORTED (a skip is a failure in the"
                        + " differential — is the metadata store available?):\n"
                        + String.join("\n", ledger.aborted));
        assertTrue(ledger.contentDiffs.isEmpty(),
                () -> ledger.contentDiffs.size() + " outcome(s) differ in CONTENT (a key lost or"
                        + " added, k=␀ against absent, a value, a state or a count):\n"
                        + String.join("\n", ledger.contentDiffs));
        assertTrue(ledger.unlistedOrderDiffs.isEmpty(),
                () -> ledger.unlistedOrderDiffs.size() + " outcome(s) differ in ORDER ONLY for a"
                        + " rule that accepted-order-moves.tsv does not list:\n"
                        + String.join("\n", ledger.unlistedOrderDiffs));
        assertTrue(ledger.unpredictedOrderDiffs.isEmpty(),
                () -> ledger.unpredictedOrderDiffs.size() + " ORDER-ONLY outcome(s) of a listed"
                        + " rule whose key order is NOT the derivation's own order for that"
                        + " dataset:\n" + String.join("\n", ledger.unpredictedOrderDiffs));
        if (study)
        {
            assertTrue(ledger.studyRan, "the study arm did not run");
        }
    }

    // ------------------------------------------------------------------ scenario arm


    private static void runScenarios(Path baseDir, Set<String> population, boolean explicit,
            Ledger ledger)
        throws IOException
    {
        Set<String> found = new TreeSet<>();
        for (AbstractRuleTestSuitesFactory factory : FACTORIES)
        {
            String family = factory.family();
            Map<String, Map<String, Rule>> basePackages = AbstractRuleTestSuitesFactory
                    .loadFamily(baseDir, family);
            Map<String, Map<String, Rule>> currentPackages = AbstractRuleTestSuitesFactory
                    .loadFamily(family);
            for (String coreId : population)
            {
                Path ruleDir = RESOURCE_BASE.resolve(family).resolve(coreId);
                if (!Files.isDirectory(ruleDir))
                {
                    continue;
                }
                found.add(coreId);
                ledger.rulesSeen.add(coreId);
                for (Path file : scenarioFiles(ruleDir))
                {
                    compareScenario(file, coreId, basePackages, currentPackages, ledger);
                }
            }
        }
        if (explicit)
        {
            Set<String> unknown = new TreeSet<>(population);
            unknown.removeAll(found);
            assertTrue(unknown.isEmpty(), () -> RULES_PROPERTY
                    + " names id(s) with no scenario directory in any" + " family: " + unknown);
        }
    }


    /** The rule directory's scenarios — one level; a subdirectory would be half-read, so fail. */
    private static List<Path> scenarioFiles(Path ruleDir) throws IOException
    {
        try (Stream<Path> list = Files.list(ruleDir))
        {
            List<Path> entries = list.sorted().toList();
            List<Path> dirs = entries.stream().filter(Files::isDirectory).toList();
            assertTrue(dirs.isEmpty(), () -> ruleDir + " has subdirectories " + dirs
                    + " — discovery here is one level (Files.list), the factory's is Files.walk");
            return entries.stream().filter(p -> p.getFileName().toString().endsWith(".cdt"))
                    .filter(AbstractRuleTestSuitesFactory::hasShebang).toList();
        }
    }


    private static void compareScenario(Path file, String coreId,
            Map<String, Map<String, Rule>> basePackages,
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

        ScenarioRunner.EvalOutcome b;
        ScenarioRunner.EvalOutcome c;
        try
        {
            b = AbstractRuleTestSuitesFactory.evaluateScenario(file, s, base, primary);
            c = AbstractRuleTestSuitesFactory.evaluateScenario(file, s, current, primary);
        }
        catch (TestAbortedException e)
        {
            ledger.aborted.add(rel + ": " + e.getMessage());
            return;
        }
        List<Row> rowsB = rows(b);
        List<Row> rowsC = rows(c);
        if ((rowsB != null && !rowsB.isEmpty()) || (rowsC != null && !rowsC.isEmpty()))
        {
            ledger.reach.add(coreId);
        }
        // The scenario path runs the rule as RuleRunner.execute does: specialised for the
        // primary, the effective list carried over with its names resolved (no re-derivation).
        // A template is expanded inside the factory and is not order-judged (class javadoc).
        judge(rel, coreId, s.getDomain(), state(b), state(c), rowsB, rowsC,
                scenarioEffectiveList(base, primary, s.getDomain()),
                scenarioEffectiveList(current, primary, s.getDomain()), ledger);
    }


    /** The specialised rule's effective list; {@link Basis#TEMPLATE} for an expanded template. */
    private static Basis scenarioEffectiveList(Rule rule, OverlayDataTable primary, String domain)
    {
        if (isTemplate(rule))
        {
            return Basis.TEMPLATE;
        }
        Rule specialised = RuleSpecialiser.specialise(rule, primary, domain);
        return new Basis(
                specialised == null ? null : specialised.effectiveOutputVariablesOrAuthored());
    }


    private static boolean isTemplate(Rule rule)
    {
        return !WildcardExpander.collectWildcardNames(rule).isEmpty()
                || (rule.getExpansion() != null && !rule.getExpansion().isEmpty());
    }

    // ------------------------------------------------------------------ study arm


    private static void runStudy(Path baseDir, Set<String> population, Ledger ledger)
        throws IOException
    {
        Path study = PROJECT_DIR.resolve("testdata").resolve("study");
        assertTrue(Files.isDirectory(study), "testdata/study is missing — pass -D" + STUDY_PROPERTY
                + "=false to skip the study arm deliberately");
        Map<String, IDataTable> tables = loadStudy(study);
        StudyValidationResult b = execute(study, baseDir);
        StudyValidationResult c = execute(study, CorpusPaths.rules());
        ledger.studyRan = true;
        Map<String, Rule> baseRules = studyRules(baseDir);
        Map<String, Rule> currentRules = studyRules(CorpusPaths.rules());

        Map<String, Map<String, List<Row>>> rowsB = studyRows(b);
        Map<String, Map<String, List<Row>>> rowsC = studyRows(c);
        Map<String, Map<String, String>> statesB = studyStates(b);
        Map<String, Map<String, String>> statesC = studyStates(c);
        Set<String> rules = new TreeSet<>(rowsB.keySet());
        rules.addAll(rowsC.keySet());
        rules.addAll(statesB.keySet());
        rules.addAll(statesC.keySet());
        for (String coreId : rules)
        {
            if (!population.contains(coreId))
            {
                continue; // an untouched rule: the findings snapshot judges it
            }
            ledger.studyRules.add(coreId);
            Set<String> datasets = new TreeSet<>();
            datasets.addAll(rowsB.getOrDefault(coreId, Map.of()).keySet());
            datasets.addAll(rowsC.getOrDefault(coreId, Map.of()).keySet());
            datasets.addAll(statesB.getOrDefault(coreId, Map.of()).keySet());
            datasets.addAll(statesC.getOrDefault(coreId, Map.of()).keySet());
            for (String dataset : datasets)
            {
                List<Row> rb = rowsB.getOrDefault(coreId, Map.of()).getOrDefault(dataset,
                        List.of());
                List<Row> rc = rowsC.getOrDefault(coreId, Map.of()).getOrDefault(dataset,
                        List.of());
                if (!rb.isEmpty() || !rc.isEmpty())
                {
                    ledger.reach.add(coreId);
                }
                IDataTable table = tables.get(dataset.toUpperCase(Locale.ROOT));
                judge("study/" + dataset + "/" + coreId, coreId, dataset,
                        statesB.getOrDefault(coreId, Map.of()).getOrDefault(dataset, "-"),
                        statesC.getOrDefault(coreId, Map.of()).getOrDefault(dataset, "-"), rb, rc,
                        productionEffectiveLists(baseRules.get(coreId), table, dataset),
                        productionEffectiveLists(currentRules.get(coreId), table, dataset), ledger);
            }
        }
    }


    /**
     * The effective list the production path projects for {@code rule} on {@code dataset}: the
     * {@code --} specialisation followed by the re-derivation {@code DatasetRuleResolver} applies
     * to every rule it emits. A template (expanded per dataset) is {@link Basis#TEMPLATE}, not
     * order-judged (class javadoc).
     */
    private static Basis productionEffectiveLists(@Nullable Rule rule, @Nullable IDataTable table,
            String dataset)
    {
        if (rule == null)
        {
            return new Basis(null);
        }
        if (isTemplate(rule))
        {
            return Basis.TEMPLATE;
        }
        Rule specialised = RuleSpecialiser.specialise(rule, table,
                dataset.toUpperCase(Locale.ROOT));
        if (specialised == null)
        {
            return new Basis(null);
        }
        if (specialised != rule)
        {
            RulePackageLoader.deriveOutputVariables(specialised);
        }
        return new Basis(specialised.effectiveOutputVariablesOrAuthored());
    }


    private static Map<String, Rule> studyRules(Path rulesDir) throws IOException
    {
        Map<String, Rule> out = new TreeMap<>();
        for (String pkg : STUDY_PACKAGES)
        {
            Path file = rulesDir.resolve("rules-" + pkg + ".json");
            for (Rule rule : RulePackageLoader.load(file).getRules().values())
            {
                String id = rule.getCore() != null ? rule.getCore().getId() : rule.getId();
                out.putIfAbsent(id, rule);
            }
        }
        return out;
    }


    private static StudyValidationResult execute(Path study, Path rulesDir) throws IOException
    {
        Path store = MetadataStoreFixture.storeFile().orElseThrow(() -> new IOException(
                "CDISC_PICKLE_CACHE_DIR / -Dcdisc.pickle.cache.dir must name the pickle corpus —"
                        + " the study arm seeds the unified metadata store from it"));
        StudyValidationParams params = StudyValidationParams.builder()
                .manager(new LocalDataTableManager()).dataLibrary(study.toString())
                .defineXmlPath(study.resolve("define.xml").toString()).rulesPackages(STUDY_PACKAGES)
                .rulesDir(rulesDir.toString()).ruleThreads(4).maxErrorsPerRule(50)
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


    /** rule → dataset → the finding rows, in emission order. */
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
                    rows.add(new Row("-", severity + "/" + kind, f.getVariableNames(),
                            f.getFirstRowValues()));
                    continue;
                }
                for (int pos = 0; pos < f.getRowCount(); pos++)
                {
                    rows.add(new Row(Integer.toString(f.getRows().rowIndexAt(pos)),
                            severity + "/" + kind, f.getVariableNames(), f.getRowValues(pos)));
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

    // ------------------------------------------------------------------ rows and judgement

    /**
     * One finding row: its location text, its variable names in emission order and its values in
     * emission order ({@code null} explicit). {@code names} may be empty for the scenario path,
     * where the values map alone carries the order.
     */
    private record Row(String where, String tag, List<String> names,
            Map<String, @Nullable String> values)
    {

        /** Order-preserving rendering. */
        String text()
        {
            StringBuilder sb = new StringBuilder(where).append(' ').append(tag);
            if (!names.isEmpty())
            {
                sb.append(" names=").append(names);
            }
            sb.append(" values={");
            boolean first = true;
            for (Map.Entry<String, @Nullable String> e : values.entrySet())
            {
                sb.append(first ? "" : ", ").append(e.getKey()).append('=')
                        .append(e.getValue() == null ? NULL_MARK : e.getValue());
                first = false;
            }
            return sb.append('}').toString();
        }


        /** Order-insensitive rendering: names and values sorted. */
        String canonical()
        {
            Map<String, String> sorted = new TreeMap<>();
            for (Map.Entry<String, @Nullable String> e : values.entrySet())
            {
                sorted.put(e.getKey(), e.getValue() == null ? NULL_MARK : e.getValue());
            }
            return where + " " + tag + " names=" + new TreeSet<>(names) + " values=" + sorted;
        }
    }

    private static @Nullable List<Row> rows(ScenarioRunner.@Nullable EvalOutcome o)
    {
        if (o == null)
        {
            return null;
        }
        List<Row> out = new ArrayList<>();
        for (Violation v : o.violations())
        {
            String where = "row=" + v.getRowNumber() + " usubjid=" + v.getUsubjid() + " seq="
                    + v.getSeq() + " level=" + v.getLevel() + " unit=" + v.getUnit() + " keys="
                    + (v.getKeys() == null ? "{}" : new TreeMap<>(v.getKeys())) + " group="
                    + (v.getGroupKey() == null ? "{}" : new TreeMap<>(v.getGroupKey()))
                    // PLAN-finding-values-record-level-only V8 (b): the name-only columns are part
                    // of the finding; a strip that moved one would otherwise pass unseen.
                    + " named=" + v.getNamedColumns();
            Map<String, @Nullable String> values = v.getValues() == null ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(v.getValues());
            out.add(new Row(where, "violation", List.of(), values));
        }
        return out;
    }


    private static String state(ScenarioRunner.@Nullable EvalOutcome o)
    {
        if (o == null)
        {
            return "OUT-OF-SCOPE";
        }
        return String.format(Locale.ROOT,
                "executed=%s errored=%s violated=%s count=%d" + " truncated=%s reason=%s",
                o.executed(), o.errored(), o.violated(), o.totalCount(), o.truncated(),
                o.notExecutedReason());
    }


    /**
     * Classifies one comparison: IDENTICAL, ORDER-ONLY (accepted iff the rule is listed and every
     * row's key order follows the contract's {@code before} / {@code after} list where the contract
     * states one for this domain, and its own side's effective list — class javadoc), else CONTENT.
     */
    private static void judge(String rel, String coreId, String domain, String stateB,
            String stateC, @Nullable List<Row> rowsB, @Nullable List<Row> rowsC, Basis effectiveB,
            Basis effectiveC, Ledger ledger)
    {
        List<String> textB = texts(rowsB, false);
        List<String> textC = texts(rowsC, false);
        boolean identical = stateB.equals(stateC) && textB.equals(textC);
        String cls;
        if (identical)
        {
            cls = "IDENTICAL";
            ledger.identical++;
        }
        else if (stateB.equals(stateC) && texts(rowsB, true).equals(texts(rowsC, true)))
        {
            cls = "ORDER-ONLY";
            ledger.orderOnly++;
            ledger.orderMovesObserved.add(coreId);
            if (!ledger.accepted.contains(coreId))
            {
                ledger.unlistedOrderDiffs.add(rel + "\n-- base\n" + String.join("\n", textB)
                        + "\n-- current\n" + String.join("\n", textC));
            }
            else if (effectiveB.template() || effectiveC.template())
            {
                ledger.templateUnjudged++;
            }
            else
            {
                Move move = contractMove(ledger.contract.getOrDefault(coreId, List.of()), domain);
                if (move == null)
                {
                    ledger.contractNotApplicable++;
                }
                else
                {
                    ledger.contractJudged++;
                }
                String why = unpredicted(rowsB, effectiveB, "base", "derivation");
                if (why == null)
                {
                    why = unpredicted(rowsC, effectiveC, "current", "derivation");
                }
                if (why == null && move != null)
                {
                    why = unpredicted(rowsB, new Basis(move.before()), "base", "contract before");
                }
                if (why == null && move != null)
                {
                    why = unpredicted(rowsC, new Basis(move.after()), "current", "contract after");
                }
                if (why != null)
                {
                    ledger.unpredictedOrderDiffs
                            .add(rel + ": " + why + "\n-- base\n" + String.join("\n", textB)
                                    + "\n-- current\n" + String.join("\n", textC));
                }
            }
        }
        else
        {
            cls = "CONTENT";
            ledger.contentDiffs.add(rel + "\n-- base " + stateB + "\n" + String.join("\n", textB)
                    + "\n-- current " + stateC + "\n" + String.join("\n", textC));
        }
        ledger.report.append("== ").append(rel).append(" class: ").append(cls).append('\n');
        ledger.report.append("-- base ").append(stateB).append('\n');
        textB.forEach(t -> ledger.report.append(t).append('\n'));
        if (identical)
        {
            ledger.report.append("-- current: IDENTICAL\n");
        }
        else
        {
            ledger.report.append("-- current ").append(stateC).append('\n');
            textC.forEach(t -> ledger.report.append(t).append('\n'));
        }
    }


    /**
     * {@code null} when every row's keys (and variable names) are ordered as {@code basis} orders
     * them — keys the list does not carry (the injected identity, an unresolved {@code --} name)
     * are ignored — else the first offending row with the expected order.
     */
    private static @Nullable String unpredicted(@Nullable List<Row> rows, Basis basis, String side,
            String what)
    {
        if (rows == null || rows.isEmpty())
        {
            return null;
        }
        List<String> order = basis.order();
        if (order == null)
        {
            return side + ": no " + what + " list to judge the order against";
        }
        for (Row row : rows)
        {
            if (!consistent(new ArrayList<>(row.values().keySet()), order)
                    || !consistent(row.names(), order))
            {
                return side + " row " + row.text() + " is not in the " + what + " order " + order;
            }
        }
        return null;
    }


    /**
     * The contract row that states the order for {@code domain}: the {@code specialised} row when
     * it lists the domain and the domain is its representative ({@code AE} when listed, else the
     * first — the domain its before / after columns show), {@code null} when it lists the domain
     * otherwise (no list is committed for it), else the {@code package} row, else {@code null}.
     */
    static @Nullable Move contractMove(List<Move> moves, String domain)
    {
        String d = domain.toUpperCase(Locale.ROOT);
        Move pkg = null;
        for (Move m : moves)
        {
            if ("specialised".equals(m.level()) && m.domains().contains(d))
            {
                String representative = m.domains().contains("AE") ? "AE" : m.domains().get(0);
                return representative.equals(d) ? m : null;
            }
            if ("package".equals(m.level()))
            {
                pkg = m;
            }
        }
        return pkg;
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


    private static List<String> texts(@Nullable List<Row> rows, boolean canonical)
    {
        if (rows == null)
        {
            return List.of("OUT-OF-SCOPE");
        }
        List<String> out = new ArrayList<>(rows.size());
        for (Row r : rows)
        {
            out.add(canonical ? r.canonical() : r.text());
        }
        if (canonical)
        {
            out.sort(null);
        }
        return out;
    }

    // ------------------------------------------------------------------ contracts

    /** What one side's key order is judged against: a list, nothing, or an expanded template. */
    private record Basis(@Nullable List<String> order, boolean template)
    {

        static final Basis TEMPLATE = new Basis(null, true);

        Basis(@Nullable List<String> order)
        {
            this(order, false);
        }
    }


    /** One row of {@code accepted-order-moves.tsv}. */
    record Move(String level, List<String> domains, List<String> before, List<String> after)
    {
    }

    /** {@code accepted-order-moves.tsv}: coreId → its package and / or specialised rows. */
    private static Map<String, List<Move>> orderMoves() throws IOException
    {
        Map<String, List<Move>> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(CONTRACT_DIR.resolve("accepted-order-moves.tsv"),
                UTF_8))
        {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#"))
            {
                continue;
            }
            String[] c = t.split("\t", -1);
            assertTrue(c.length == 5, "accepted-order-moves.tsv: expected 5 columns: " + t);
            assertTrue("package".equals(c[1]) || "specialised".equals(c[1]),
                    "accepted-order-moves.tsv: unknown level " + c[1]);
            out.computeIfAbsent(c[0], _ -> new ArrayList<>())
                    .add(new Move(c[1], "*".equals(c[2]) ? List.of() : List.of(c[2].split(",")),
                            List.of(c[3].split("\\|")), List.of(c[4].split("\\|"))));
        }
        return out;
    }


    /** Column {@code col} of a contract file, {@code #} comments and blanks skipped. */
    private static Set<String> contractColumn(String name, int col) throws IOException
    {
        Set<String> out = new LinkedHashSet<>();
        for (String line : Files.readAllLines(CONTRACT_DIR.resolve(name), UTF_8))
        {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#"))
            {
                out.add(t.split("\t", -1)[col]);
            }
        }
        return out;
    }


    private static Set<String> csv(String raw)
    {
        Set<String> out = new LinkedHashSet<>();
        COMMA.splitAsStream(raw).map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
        return out;
    }
}
