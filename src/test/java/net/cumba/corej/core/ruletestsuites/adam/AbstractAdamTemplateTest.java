package net.cumba.corej.core.ruletestsuites.adam;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.cumba.corej.core.CheckExprUnits;
import net.cumba.corej.core.exec.DatasetRuleResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.gen.GeneratedRuleInfo;
import net.cumba.corej.core.gen.GeneratedRulePackage;
import net.cumba.corej.core.gen.RuleCategory;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.ruletestsuites.ScenarioDeclaredScopeProvider;
import net.cumba.corej.ruletest.cdt.ruletest.MapBackedLibraryMetadataProvider;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;

/**
 * Shared base class for ADaM template rule tests (Phase 5).
 * <p>
 * Uses {@link DatasetRuleResolver} to expand template rules (those whose Check tree carries
 * wildcard variables) into concrete rules by matching wildcard variable names against real dataset
 * columns. Each expanded rule is tested dynamically via JUnit 5 {@code @TestFactory}.
 *
 * <p>
 * ⚑ This used to pass {@code EnumSet.of(RuleCategory.WILDCARD_EXPANSION)} to select one of the
 * generator's categories. {@code plans/PLAN-remove-rule-generator.md} deleted the in-Java
 * generators and with them the category set, so there is nothing left to select — the class now
 * does only wildcard expansion and {@code --}-prefix delivery.
 * </p>
 */
abstract class AbstractAdamTemplateTest extends AbstractAdamRuleTest
{

    /** Expanded rules produced by the DatasetRuleResolver for the target dataset. */
    List<Rule> expandedRules = List.of();

    /**
     * Runs the DatasetRuleResolver wildcard expansion for the given domain. Call this from
     * {@code @BeforeAll} in subclasses.
     *
     * <p>
     * Uses {@link #getLibraryMock()} so wildcard expansion sees a populated library — both for
     * {@code libraryDefinedVars} filtering inside {@code DatasetRuleResolver} and for the runtime
     * resolution of {@code library_variable_*} fields against ADaM-IG canonical labels.
     * </p>
     */
    static List<Rule> expandRules(String domainName, String className, IDataTable table)
    {
        DatasetRuleResolver gen = new DatasetRuleResolver(getLibraryMock());
        gen.setStaticRules(ownedTemplateRules());
        gen.setDomainName(domainName);
        gen.setClassName(className);
        gen.setDatasetResolver(resolver);
        GeneratedRulePackage pkg = gen.generate(table);

        // ⚠ ALIASING, newly reachable here and deliberately accepted. expandSdtmPrefixRules adds
        // non-`--` rules BY REFERENCE, and doGenerate's tail then calls installNativeExpr and
        // deriveOutputVariables on every delivered rule — so those calls now land on the very Rule
        // objects held in AbstractAdamRuleTest.rulesByCoreId, a static map shared by every ADaM
        // suite in the JVM. Before PLAN-remove-rule-generator.md this harness passed
        // EnumSet.of(WILDCARD_EXPANSION), which kept the pass-through rules out entirely.
        // Both calls are idempotent — installNativeExpr is guarded by `getCheckExpr() == null` and
        // deriveOutputVariables documents idempotent re-derivation — and PRODUCTION has always run
        // this tail over the same pass-through rules, so the harness is now more faithful to the
        // engine, not less. Recorded because the aliasing itself has bitten before: see
        // AdamTemplateDeclaredStructureProbeTest's warning about an un-restored setScope leaking
        // into rulesByCoreId.

        // ⚠⚠ Keep ONLY the wildcard expansion children. This suite is tuned per template: each
        // expanded rule gets a fixture built for its wildcard shape, so a rule that never carried a
        // wildcard has no fixture and reads as "should fire but found no violations".
        //
        // Until plans/PLAN-remove-rule-generator.md this filtering was implicit and accidental: the
        // harness passed EnumSet.of(RuleCategory.WILDCARD_EXPANSION), which ALSO switched off
        // SDTM_PREFIX_EXPANSION — and that category is what copies the scoped concrete rules into
        // the executed set at all. With the category set gone (the resolver always does both
        // halves, as production always did), the concrete rules arrive too and must be filtered
        // out explicitly. 94 of this suite's cases failed exactly that way.
        Set<String> expansionIds = pkg.getReport().getGeneratedRules().stream()
                .filter(g -> g.category() == RuleCategory.WILDCARD_EXPANSION)
                .map(GeneratedRuleInfo::ruleId).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // ⚠⚠ The report is ALMOST a faithful index of the expansions, but not quite:
        // DatasetRuleResolver.applyTemplatePostFilters keeps an expansion WITHOUT recording a
        // report entry when `expCoreId == null || origCoreId == null`. Such an expansion is dropped
        // by the filter below, shrinking this suite's coverage with nothing going red.
        //
        // ⭐ Assert the PREMISE, not the symptom. The first half of that condition is unreachable:
        // WildcardExpander builds the child id as `origCoreId + "-" + primaryCol`, a string
        // concatenation, so a null template id yields the literal "null-<COL>" — non-null. Only
        // `origCoreId == null` can fire, and it produces a child whose own core id IS non-null.
        // So asserting on the delivered rules' ids cannot detect it; the real premise is that every
        // TEMPLATE handed in has an effectiveId(). ownedTemplateRules() enforces that today by
        // construction, which makes this a ratchet on that filter, not a restatement of it.
        assertTrue(ownedTemplateRules().stream().allMatch(r -> r.effectiveId() != null),
                "a template with no effectiveId() reached the resolver: its expansions are recorded"
                        + " under no report entry and would be filtered out of this suite silently");

        return pkg.getRules().stream().filter(r -> expansionIds.contains(r.getCore().getId()))
                .toList();
    }


    /**
     * The CDISC ADaM Conformance Rules ({@code CDISC-AD*}) — the family this template suite owns
     * and whose Check shapes the synthetic valid/invalid fixture builders here are tuned for.
     *
     * <p>
     * The shared {@code rules-adamig-1-3.json} corpus also ships the PMDA ADaM validation rules
     * ({@code PMDA-AD*}, {@code Organization: "PMDA"}), added after this suite without their own
     * {@code .cdt} scenario fixtures. Their paired-stem wildcard / guardless Check shapes (e.g.
     * {@code PMDA-AD0010}: {@code *FL == "Y" and *FN != 1}, with no
     * {@code non_empty}/{@code exists} guard) are not modelled by {@code buildValidTable}, so the
     * synthetic "valid" fixture cannot represent a conformant record and the rule spuriously fires.
     * Those rules still ship and run in production — they are simply out of scope for this
     * heuristic smoke-test until they gain their own fixtures (or the fixture heuristics are
     * extended). Filter by Core.Id family so only the owned rules are expanded and exercised here.
     * </p>
     */
    private static List<Rule> ownedTemplateRules()
    {
        List<Rule> owned = new ArrayList<>();
        for (Rule rule : rulesByCoreId.values())
        {
            String coreId = rule.getCore() != null ? rule.getCore().getId() : null;
            if (coreId != null && coreId.startsWith("CDISC-AD"))
            {
                owned.add(rule);
            }
        }
        return owned;
    }

    // ---- Library mock ----

    private static volatile MetadataProvider libraryMock;

    /**
     * Returns a {@link MapBackedLibraryMetadataProvider} populated from the loaded ADaM and SDTM
     * domain tables (parent's {@code @BeforeAll}). Each variable's library label is taken from the
     * source dataset's column label, so rules like CDISC-AD0709
     * ({@code variable_label not_equal_to library_variable_label}) have a canonical reference value
     * at runtime. Cached after the first call.
     *
     * <p>
     * The fixture's ADAE/ADLBC/ADSL labels come from the CDISC pilot study's define.xml, which
     * carries IG-canonical labels — so this is "ADaM IG truth" for the variables we actually
     * exercise. Variables outside the loaded domains have no library entry and the engine treats
     * {@code library_variable_label} as empty (the rule then folds at the {@code non_empty} guard,
     * mirroring an unknown-variable scenario).
     * </p>
     */
    static MetadataProvider getLibraryMock()
    {
        MetadataProvider local = libraryMock;
        if (local != null)
        {
            return local;
        }
        synchronized (AbstractAdamTemplateTest.class)
        {
            if (libraryMock != null)
            {
                return libraryMock;
            }
            MapBackedLibraryMetadataProvider.Builder b = MapBackedLibraryMetadataProvider.builder()
                    .standard("adamig").version("1-3");
            populateLibraryFromDomains(b, adamDomains);
            populateLibraryFromDomains(b, sdtmDomains);
            libraryMock = b.build();
            return libraryMock;
        }
    }


    private static void populateLibraryFromDomains(
            MapBackedLibraryMetadataProvider.Builder aBuilder, Map<String, IDataTable> aDomains)
    {
        if (aDomains == null)
        {
            return;
        }
        for (Map.Entry<String, IDataTable> e : aDomains.entrySet())
        {
            String domain = e.getKey();
            IDataTable table = e.getValue();
            if (table == null)
            {
                continue;
            }
            DataTableMeta meta = table.getMetaData();
            List<Map<String, String>> vars = new ArrayList<>(meta.getColumnCount());
            for (int i = 0; i < meta.getColumnCount(); i++)
            {
                DataTableColumnMeta col = meta.getColumn(i);
                Map<String, String> vm = new LinkedHashMap<>();
                vm.put("name", col.getName());
                if (col.getLabel() != null)
                {
                    vm.put("label", col.getLabel());
                }
                aBuilder.variableMetadata(domain, col.getName(), vm);
                vars.add(vm);
            }
            aBuilder.domainVariables(domain, vars);
        }
    }

    // ---- Dynamic test factories ----


    /**
     * The leaf name that makes a rule <b>unrepresentable</b> in this harness, or {@code null}.
     * <p>
     * ⚠⚠ Every builder here produces a <b>single</b> {@link OverlayDataTable} via
     * {@code virtualTable(domainName, …)}. A rule whose Check reads a column through a
     * {@code Match_Datasets} join — {@code CDISC-AD0591}'s {@code ADSL.&VAR&} against {@code &VAR&}
     * — therefore has <b>no representable invalid case</b>: the joined side cannot be populated,
     * the comparison never has two operands, and the {@code _invalid} test fails while its
     * {@code _valid} twin passes <b>vacuously</b>. The vacuous pass is the worse half.
     * </p>
     * <p>
     * ⚑ Deliberately narrow. Declaring {@code Match_Datasets} is <b>not</b> the disqualifier — 46
     * rules in {@code rules-cdisc-adamig-1-3} declare one and are represented fine. The
     * disqualifier is a leaf <b>name</b> qualified by a joined dataset, which is what the
     * single-table builder cannot fill.
     * </p>
     * <p>
     * ⚠ Skipping loses no coverage: such rules are covered by the {@code .cdt} scenario mechanism,
     * which <i>can</i> express a join — {@code CDISC-AD0591} has three ({@code invalid} /
     * {@code valid} / {@code skipped-no-ADSL}). Retire this skip when the generator learns to build
     * the secondary dataset — {@code
     * plans/done/PLAN-adam-template-secondary-dataset.md} (filed, no go).
     * </p>
     */
    private String unrepresentableJoinLeaf(Rule rule)
    {
        if (rule.getMatchDatasets() == null || rule.getMatchDatasets().isEmpty())
        {
            return null;
        }
        Set<String> joined = new LinkedHashSet<>();
        for (MatchDataset md : rule.getMatchDatasets())
        {
            if (md != null && md.getName() != null && !md.getName().isEmpty())
            {
                joined.add(md.getName().toUpperCase(Locale.ROOT));
            }
        }
        for (LeafInfo leaf : collectLeaves(rule.getCheck()))
        {
            String name = leaf.name;
            int dot = name == null ? -1 : name.indexOf('.');
            if (dot > 0 && joined.contains(name.substring(0, dot).toUpperCase(Locale.ROOT)))
            {
                return name;
            }
        }
        return null;
    }


    /** Aborts (visibly) when {@link #unrepresentableJoinLeaf} disqualifies the rule. */
    private void skipIfJoinUnrepresentable(Rule rule, String ruleId)
    {
        String leaf = unrepresentableJoinLeaf(rule);
        Assumptions.assumeTrue(leaf == null,
                () -> ruleId + ": the template harness builds ONE table and cannot populate '"
                        + leaf
                        + "', which reads a Match_Datasets join. Not a gap in coverage — the .cdt "
                        + "scenarios cover this rule and can express the join. Retire this skip "
                        + "when the generator can build the secondary dataset.");
    }


    /**
     * <b>Fix #227 — the declare channel.</b> Returns the {@link MetadataProvider} to hand
     * {@code RuleRunner} for {@code aTable}: the shared {@link #getLibraryMock() library mock},
     * decorated so it answers {@link MetadataProvider#getDeclaredDatasetClass(String)} with the
     * data structure the concrete suite declares.
     *
     * <h4>Why this exists</h4>
     *
     * <p>
     * Each suite already states its structure — {@code AdamTemplatesBdsRuleTest}'s
     * {@code CLASS_NAME = "BASIC DATA STRUCTURE"} and its two siblings — but before Fix #227 that
     * constant was handed to the {@link DatasetRuleResolver} alone. Execution ran on the
     * six-argument {@code RuleRunner.execute}, so at execution time <em>nobody had said what
     * structure the table was</em>, and {@code RuleRunner}'s {@code Scope.Data_Structures} gate
     * fell back to the column heuristic. The fixtures here are synthesised by
     * {@link #buildInvalidTable} / {@code buildValidTable} and carry only the rule's own leaf
     * columns, so that heuristic answers {@code ADAM OTHER} for almost all of them — which is why
     * 15 rules are recorded {@code INSTRUMENT-HELD} in
     * {@code adam-classes-to-structures-instrument-holds.tsv} and could not migrate from
     * {@code Scope.Classes} to {@code Scope.Data_Structures}.
     * </p>
     *
     * <h4>⚠ Why the LIBRARY slot and not a {@code defineProvider}</h4>
     *
     * <p>
     * {@code RuleRunner.declaredClassOf} chains the two tiers — the define provider first, the
     * library provider when the define declares nothing — so <em>either</em> slot can carry the
     * declaration. The library slot is the correct one <b>here</b> because it is already occupied
     * ({@code getLibraryMock()}), so decorating it is invisible to everything else. Filling the
     * currently-empty define slot would not be: {@code defineProvider == null} is a bare nullness
     * test in three further places —
     * </p>
     *
     * <pre>
     * RuleRunner:499  defineProvider == null &amp;&amp; referencesOperandPrefix(check, "define_")  -&gt; SKIPPED
     * RuleRunner:624  defineProvider == null &amp;&amp; OperationExecutor.isDefineDependent(op) -&gt; SKIPPED
     * RuleRunner:809  injectDatasetLevel(enriched, "define_dataset_", defineProvider, …)
     * </pre>
     *
     * <p>
     * — so a non-null define provider silently switches the first two self-skips <em>off</em> and
     * injects a third level of dataset metadata. That is exactly review finding F-R8, recorded on
     * {@link ScenarioDeclaredScopeProvider}: a decorator is a non-null reference by construction,
     * and occupying an empty slot changes availability, not just the declared tier. Declaring
     * through the slot that is already there changes one accessor and nothing else.
     * </p>
     *
     * <p>
     * ⚑ Setting {@link ScenarioDeclaredScopeProvider#STRUCTURE_KEY} on the fixture and letting the
     * decorator read it is deliberate: it is the same table-metadata channel {@code CdtLoader}
     * fills from a {@code .cdt} {@code structure=} attribute, so this harness declares the way the
     * rest of the system declares. ⚠ The key alone is inert — the engine never reads table metadata
     * for the structure gate; the decorator is what carries it to {@code RuleRunner}.
     * </p>
     *
     * @param aTable
     *            the synthesised fixture the rule will execute against
     * @param aDeclaredStructure
     *            the ADaM data structure the concrete suite declares
     * @return the library provider, decorated with the declaration
     */
    static MetadataProvider declaringLibrary(OverlayDataTable aTable, String aDeclaredStructure)
    {
        aTable.setTableMetaData(ScenarioDeclaredScopeProvider.STRUCTURE_KEY, aDeclaredStructure);
        return ScenarioDeclaredScopeProvider.wrap(aTable, getLibraryMock());
    }


    /**
     * Produces dynamic tests asserting that each expanded rule does NOT fire on valid data.
     *
     * <p>
     * ⚠⚠ This assertion shape is <b>structurally blind</b> to the {@code Scope.Data_Structures}
     * gate: a rule that is SKIPPED produces no findings, and {@code assertFalse(hasViolations())}
     * cannot tell that from a rule that ran and stayed quiet. Wave 31 measured the same thing
     * across 8 probes — not one {@code noViolation}-shaped assertion moved when the declare channel
     * was neutered. Only {@link #templateTests_invalid} can observe Fix #227.
     * </p>
     *
     * @param domainName
     *            the dataset under test
     * @param declaredStructure
     *            the ADaM data structure the concrete suite declares — see
     *            {@link #declaringLibrary}
     * @return the dynamic tests
     */
    Stream<DynamicTest> templateTests_valid(String domainName, String declaredStructure)
    {
        return withVacuityGuard(expandedRules.stream().map(rule ->
        {
            String ruleId = rule.getCore().getId();
            return DynamicTest.dynamicTest(ruleId + "_valid", () ->
            {
                skipIfJoinUnrepresentable(rule, ruleId);
                OverlayDataTable table = buildValidTable(rule, domainName);
                if (table == null)
                {
                    return; // cannot build valid data for this rule pattern
                }
                RuleExecutionResult result = RuleRunnerCalls.execute(rule, table,
                        resolverWith(domainName, table), domainName,
                        declaringLibrary(table, declaredStructure), null);
                // An ERROR also carries the "__error__" sentinel, so without this the failure
                // message below would blame a phantom "1 violation(s)" for what is really an
                // execution failure — loud either way, but pointing at the wrong thing.
                assertFalse(result.isError(),
                        ruleId + " ERRORED on valid data: " + result.getStatusMessage());
                assertFalse(result.hasViolations(),
                        ruleId + " should NOT fire on valid data but found "
                                + result.getViolationCount() + " violation(s)");
            });
        }));
    }


    /**
     * Appends the vacuity ratchet as the stream's last dynamic test, so it runs once every rule
     * above it has had its fixture built. ⚠ A {@code DynamicTest} stream is consumed lazily and in
     * order, which is what makes "last in the stream" mean "after the rest" — the guard would
     * measure nothing if it were an ordinary {@code @Test}.
     */
    private static Stream<DynamicTest> withVacuityGuard(Stream<DynamicTest> tests)
    {
        return Stream.concat(tests,
                Stream.of(DynamicTest.dynamicTest("__fixture_builders_are_not_vacuous",
                        AbstractAdamTemplateTest::assertFixtureBuildersAreNotVacuous)));
    }


    /**
     * Produces dynamic tests asserting that each expanded rule DOES fire on invalid data.
     *
     * @param domainName
     *            the dataset under test
     * @param declaredStructure
     *            the ADaM data structure the concrete suite declares — see
     *            {@link #declaringLibrary}
     * @return the dynamic tests
     */
    Stream<DynamicTest> templateTests_invalid(String domainName, String declaredStructure)
    {
        return withVacuityGuard(expandedRules.stream().map(rule ->
        {
            String ruleId = rule.getCore().getId();
            return DynamicTest.dynamicTest(ruleId + "_invalid", () ->
            {
                skipIfJoinUnrepresentable(rule, ruleId);
                OverlayDataTable table = buildInvalidTable(rule, domainName);
                if (table == null)
                {
                    return; // cannot build invalid data for this rule pattern
                }
                RuleExecutionResult result = RuleRunnerCalls.execute(rule, table,
                        resolverWith(domainName, table), domainName,
                        declaringLibrary(table, declaredStructure), null);
                // ⚠⚠ The not-errored assertion comes FIRST and is not decoration. RuleRunner
                // emits an "__error__" sentinel Violation on every ERROR path, so
                // hasViolations() is TRUE for a rule that errored — and this assertTrue would
                // pass VACUOUSLY, reporting a detection that never happened. That went from
                // theoretical to reachable when PLAN-column-type-conformance's ColumnTypeGate
                // added a new ERROR path these all-Char harness tables walk straight into.
                assertFalse(result.isError(),
                        ruleId + " ERRORED on invalid data rather than firing: "
                                + result.getStatusMessage()
                                + " — an error emits the __error__ sentinel, so asserting"
                                + " hasViolations() alone would have passed on it");
                assertTrue(result.hasViolations(),
                        ruleId + " should fire on invalid data but found " + "no violations");
            });
        }));
    }

    // ---- Table builders ----


    /**
     * Builds a virtual table with VALID data that should NOT trigger the rule. Returns null if the
     * rule pattern is not supported.
     */
    private OverlayDataTable buildValidTable(Rule rule, String domainName)
    {
        if (rule.getCheck() == null)
        {
            return null;
        }

        List<LeafInfo> leaves = collectLeaves(rule.getCheck());
        if (leaves.isEmpty())
        {
            return null;
        }

        // Collect all real column names referenced in the rule
        Set<String> allColumns = new LinkedHashSet<>();
        for (LeafInfo leaf : leaves)
        {
            if (leaf.name != null && !isEngineMetaName(leaf.name))
            {
                allColumns.add(leaf.name);
            }
            // Also add value-referenced columns (not literals, except
            // for "variable_name equal_to X" which references target columns)
            if (leaf.valueText != null && !isEngineMetaName(leaf.valueText)
                    && isColumnName(leaf.valueText))
            {
                boolean isVarNameCheck = "variable_name".equals(leaf.name)
                        && leaf.op == Op.EQUAL_TO;
                if (isVarNameCheck || !leaf.isLiteral)
                {
                    allColumns.add(leaf.valueText);
                }
            }
        }

        if (allColumns.isEmpty())
        {
            return null;
        }

        boolean hasNonEmpty = leaves.stream().anyMatch(l -> l.op == Op.NON_EMPTY);
        boolean hasNotExists = leaves.stream().anyMatch(l -> l.op == Op.VAR_NOT_EXISTS);
        boolean hasIsNotUniqueRelationship = leaves.stream().anyMatch(
                l -> l.op == Op.IS_NOT_UNIQUE_RELATIONSHIP || l.op == Op.HAS_MULTIPLE_VALUES_FOR);

        // Rules whose Check uses `library_variable_*` leaves (e.g. CDISC-AD0709 —
        // `variable_label not_equal_to library_variable_label`) need a valid case where
        // the dataset's column metadata matches the library-canonical metadata. Without
        // this, expanding the rule per variable produces fixtures whose default column
        // labels (= column name) differ from the library label — and the rule fires.
        // Build a 1-row virtual table with the target column and pin its label to the
        // library's, so `not_equal_to` evaluates FALSE.
        boolean hasLibraryMetadataLeaf = leaves.stream()
                .anyMatch(l -> l.name != null && l.name.startsWith("library_variable_"));
        if (hasLibraryMetadataLeaf)
        {
            String target = inferTargetColumnFromRule(rule, leaves, domainName);
            if (target != null)
            {
                String libLabel = getLibraryMock().getVariableMetadata(domainName, target)
                        .get("label");
                if (libLabel != null && !libLabel.isEmpty() && !libLabel.contains("{")
                        && !libLabel.contains("*") && !libLabel.contains("..."))
                {
                    OverlayDataTable table = virtualTable(domainName, 1, target);
                    table.setColumnLabel(target, libLabel);
                    return table;
                }
            }
            // Target unknown to the library, or its label has the special chars that
            // would themselves fold the rule's `does_not_contain` guards to FALSE.
            // In either case the rule cannot fire on a target whose library entry is
            // empty / dirty — UNRELATED_COL is safe.
            return virtualTable(domainName, 1, target != null ? target : "UNRELATED_COL");
        }

        // For Variable Metadata Check rules:
        if (isVariableMetadataCheck(rule))
        {
            // Determine if the rule has not_exists checks
            boolean ruleHasNotExists = leaves.stream().anyMatch(l -> l.op == Op.VAR_NOT_EXISTS);
            if (ruleHasNotExists)
            {
                // Include ALL referenced columns (including not_exists targets)
                // so that not_exists checks are FALSE => no violation.
                OverlayDataTable table = virtualTable(domainName, 1,
                        allColumns.toArray(String[]::new));
                for (String col : allColumns)
                {
                    table.setValue(0, col, "VALUE");
                }
                return table;
            }
            else
            {
                // For rules without not_exists (e.g., exists + metadata checks):
                // Use a table WITHOUT the target column. "variable_name == X"
                // never matches, so no violation occurs regardless of metadata.
                return virtualTable(domainName, 1, "UNRELATED_COL");
            }
        }

        // For record-data rules: leave columns null/empty so non_empty fails
        // This breaks the "all" conjunction => no violation
        if (hasNonEmpty)
        {
            // Include all columns but leave values null
            return virtualTable(domainName, 1, allColumns.toArray(String[]::new));
        }

        // For is_not_unique_relationship: use consistent 1:1 mapping => valid
        if (hasIsNotUniqueRelationship)
        {
            OverlayDataTable table = virtualTable(domainName, 2, allColumns.toArray(String[]::new));
            int idx = 0;
            for (String col : allColumns)
            {
                table.setValue(0, col, "A" + idx);
                table.setValue(1, col, "B" + idx);
                idx++;
            }
            return table;
        }

        // For exists + not_exists combos (Record Data): include all columns
        if (hasNotExists)
        {
            OverlayDataTable table = virtualTable(domainName, 1, allColumns.toArray(String[]::new));
            for (String col : allColumns)
            {
                table.setValue(0, col, "VALUE");
            }
            return table;
        }

        // Default: include all columns, leave values null
        return virtualTable(domainName, 1, allColumns.toArray(String[]::new));
    }


    /**
     * Builds a virtual table with INVALID data that SHOULD trigger the rule. Returns null if the
     * rule pattern is not supported.
     */
    OverlayDataTable buildInvalidTable(Rule rule, String domainName)
    {
        if (rule.getCheck() == null)
        {
            return null;
        }

        List<LeafInfo> leaves = collectLeaves(rule.getCheck());
        if (leaves.isEmpty())
        {
            return null;
        }

        boolean hasIsNotUniqueRelationship = leaves.stream().anyMatch(
                l -> l.op == Op.IS_NOT_UNIQUE_RELATIONSHIP || l.op == Op.HAS_MULTIPLE_VALUES_FOR);
        boolean hasNotExists = leaves.stream().anyMatch(l -> l.op == Op.VAR_NOT_EXISTS);
        boolean isVarMetadata = isVariableMetadataCheck(rule);

        // Collect columns to include/exclude
        Set<String> columnsToAdd = new LinkedHashSet<>();
        Set<String> columnsToOmit = new LinkedHashSet<>();

        for (LeafInfo leaf : leaves)
        {
            // Collect value-referenced columns first (before name guard)
            if (leaf.valueText != null && !isEngineMetaName(leaf.valueText)
                    && isColumnName(leaf.valueText))
            {
                // For "variable_name equal_to X" leaves (from WildcardExpander
                // transformation), X is the target column name even though
                // valueIsLiteral is set. Always add it.
                boolean isVarNameCheck = "variable_name".equals(leaf.name)
                        && leaf.op == Op.EQUAL_TO;
                if ((isVarNameCheck || !leaf.isLiteral) && leaf.op != Op.VAR_NOT_EXISTS)
                {
                    columnsToAdd.add(leaf.valueText);
                }
            }
            // Include every within grouping column so scoped operators (like
            // has_multiple_values_for) have all partition columns available in
            // the fixture. Multi-column within (Fix #25) means iterating over
            // each column rather than treating ``within`` as a single string.
            for (String wcol : leaf.within)
            {
                if (!isEngineMetaName(wcol) && isColumnName(wcol))
                {
                    columnsToAdd.add(wcol);
                }
            }
            if (leaf.name == null || isEngineMetaName(leaf.name))
            {
                continue;
            }
            // A leaf that requires its column ABSENT (bare not-exists, or an exists under a `not` —
            // e.g. CDISC-AD0007 `var_exists(*FN) and not var_exists(*FL)`) must omit the column, so
            // the negated term holds and the rule actually fires on the invalid fixture.
            if (requiresColumnAbsent(leaf))
            {
                columnsToOmit.add(leaf.name);
            }
            else
            {
                columnsToAdd.add(leaf.name);
            }
        }
        columnsToAdd.removeAll(columnsToOmit);

        if (columnsToAdd.isEmpty() && columnsToOmit.isEmpty())
        {
            return null;
        }

        // --- Detect contradictory Variable Metadata Check rules ---
        // When a template has "exists" + "not_exists" for the same wildcard
        // pattern, expansion gives the same concrete name to both. The expanded
        // Check becomes "variable_name == X AND X not_exists" which can never
        // fire (column must be present for iteration but absent for not_exists).
        if (isVarMetadata && !columnsToOmit.isEmpty())
        {
            for (LeafInfo leaf : leaves)
            {
                if (leaf.op == Op.EQUAL_TO && "variable_name".equals(leaf.name)
                        && leaf.valueText != null && columnsToOmit.contains(leaf.valueText))
                {
                    return null; // contradictory rule; skip
                }
            }
        }

        // --- Special handling: only not_exists columns ---
        // For Variable Metadata Check: the engine needs at least one column to
        // iterate over, but the target column must be absent.
        // For Record Data: not_exists checks column presence in the dataset.
        if (columnsToAdd.isEmpty() && !columnsToOmit.isEmpty())
        {
            // Add a dummy column so the table is not empty
            OverlayDataTable table = virtualTable(domainName, 1, "DUMMY_COL");
            table.setValue(0, "DUMMY_COL", "VALUE");
            return table;
        }

        // --- Special handling: is_not_unique_relationship ---
        // Need 2 rows where the key column has different values but the
        // value column has the same value (breaking 1:1 relationship)
        if (hasIsNotUniqueRelationship)
        {
            OverlayDataTable table = virtualTable(domainName, 2,
                    columnsToAdd.toArray(String[]::new));
            // First, ensure all non_empty columns have values
            for (LeafInfo leaf : leaves)
            {
                if (leaf.op == Op.NON_EMPTY && leaf.name != null && !isEngineMetaName(leaf.name)
                        && columnsToAdd.contains(leaf.name))
                {
                    table.setValue(0, leaf.name, "VALUE");
                    table.setValue(1, leaf.name, "VALUE");
                }
            }
            // Then, set up the relationship-breaking values
            for (LeafInfo leaf : leaves)
            {
                if (leaf.op == Op.IS_NOT_UNIQUE_RELATIONSHIP)
                {
                    String keyCol = leaf.name;
                    String valCol = leaf.valueText;
                    if (keyCol != null && !isEngineMetaName(keyCol)
                            && columnsToAdd.contains(keyCol))
                    {
                        // Two different key values...
                        table.setValue(0, keyCol, "KEY_A");
                        table.setValue(1, keyCol, "KEY_B");
                    }
                    if (valCol != null && !isEngineMetaName(valCol)
                            && columnsToAdd.contains(valCol))
                    {
                        // ...mapping to the same value => breaks 1:1
                        table.setValue(0, valCol, "SAME_VAL");
                        table.setValue(1, valCol, "SAME_VAL");
                    }
                }
                else if (leaf.op == Op.HAS_MULTIPLE_VALUES_FOR)
                {
                    // name is dependent, value is key.
                    // Invalid: same key value with different dependent values.
                    String depCol = leaf.name;
                    String keyCol = leaf.valueText;
                    if (keyCol != null && !isEngineMetaName(keyCol)
                            && columnsToAdd.contains(keyCol))
                    {
                        // Same key on both rows
                        table.setValue(0, keyCol, "SAME_KEY");
                        table.setValue(1, keyCol, "SAME_KEY");
                    }
                    if (depCol != null && !isEngineMetaName(depCol)
                            && columnsToAdd.contains(depCol))
                    {
                        // Two different dependent values
                        table.setValue(0, depCol, "DEP_A");
                        table.setValue(1, depCol, "DEP_B");
                    }
                    // within grouping columns: set the same value on both rows
                    // for every partition column so the rows fall into one
                    // group for evaluation. Multi-column within (Fix #25)
                    // requires every column to share its value across the two
                    // rows; otherwise the rows land in different partitions.
                    for (String wcol : leaf.within)
                    {
                        if (!isEngineMetaName(wcol) && columnsToAdd.contains(wcol))
                        {
                            table.setValue(0, wcol, "GROUP_SAME");
                            table.setValue(1, wcol, "GROUP_SAME");
                        }
                    }
                }
            }
            return table;
        }

        // --- Special handling: Variable Metadata Check with exists/not_exists ---
        // The "exists" was transformed to "variable_name == colName".
        // The engine iterates over columns; for the matching column,
        // not_exists must be true (column absent from table).
        if (isVarMetadata && hasNotExists)
        {
            // Include only the columns that should exist, omit the rest
            if (columnsToAdd.isEmpty())
            {
                // All columns are not_exists; table needs at least one column
                // to be iterated but none of the target columns present.
                // For variable_name == X checks, the column X should be present
                // so the equality matches, but the not_exists column is absent.
                // Look for equal_to leaves that reference variable_name
                for (LeafInfo leaf : leaves)
                {
                    if (leaf.op == Op.EQUAL_TO && "variable_name".equals(leaf.name)
                            && leaf.valueText != null)
                    {
                        columnsToAdd.add(leaf.valueText);
                    }
                }
            }
            OverlayDataTable table = virtualTable(domainName, 1,
                    columnsToAdd.toArray(String[]::new));
            for (String col : columnsToAdd)
            {
                table.setValue(0, col, "VALUE");
            }
            return table;
        }

        // --- Special handling: Variable Metadata Check without not_exists ---
        // Templates like CDISC-AD0041 ("variable_name == *DT AND
        // variable_data_type == Num AND variable_format not_matches_regex DATE")
        // iterate over columns and inspect engine-resolved metadata
        // (variable_data_type, variable_format). For the rule to fire on
        // invalid data, the target column must be created with a type and
        // format that satisfy the metadata predicates.
        if (isVarMetadata)
        {
            OverlayDataTable table = virtualTable(domainName, 1,
                    columnsToAdd.toArray(String[]::new));
            // Apply variable_data_type constraints (literal "Num"/"Char")
            // to every target column referenced by a "variable_name == X" leaf.
            Set<String> targets = new LinkedHashSet<>();
            for (LeafInfo leaf : leaves)
            {
                if (leaf.op == Op.EQUAL_TO && "variable_name".equals(leaf.name)
                        && leaf.valueText != null && columnsToAdd.contains(leaf.valueText))
                {
                    targets.add(leaf.valueText);
                }
            }
            net.cumba.datatable.values.DataValueType requiredType = null;
            for (LeafInfo leaf : leaves)
            {
                if (leaf.op == Op.EQUAL_TO && "variable_data_type".equals(leaf.name)
                        && leaf.isLiteral && leaf.valueText != null)
                {
                    if ("Num".equalsIgnoreCase(leaf.valueText))
                    {
                        requiredType = net.cumba.datatable.values.DataValueType.DOUBLE;
                    }
                    else if ("Char".equalsIgnoreCase(leaf.valueText))
                    {
                        requiredType = net.cumba.datatable.values.DataValueType.STRING;
                    }
                }
            }
            if (requiredType != null)
            {
                for (String t : targets)
                {
                    table.setColumnType(t, requiredType);
                }
            }
            // Set a sensible default display format on every target column.
            // Variable Metadata Check rules fold their leaves through
            // `evaluateLeafAgainstMetadata`, which returns false on
            // `not_matches_regex` when the format string is null or empty —
            // the opposite of the row-level operator. A non-date format
            // satisfies the `variable_format not_matches_regex DATE…` family
            // (CDISC-AD0041 / 0043) without breaking peers that ignore
            // format (CDISC-AD0058, 0503-0520, …).
            for (String t : targets)
            {
                table.setColumnFormat(t, "BEST12.");
            }
            // Populate non-target columns so iteration has data, but skip the
            // target columns when the rule type is numeric — leaving them
            // missing keeps the metadata predicates clean.
            for (String col : columnsToAdd)
            {
                if (!targets.contains(col))
                {
                    table.setValue(0, col, "VALUE");
                }
            }
            return table;
        }

        // --- Detect columns that need numeric type for comparisons ---
        Set<String> numericCols = new LinkedHashSet<>();
        for (LeafInfo leaf : leaves)
        {
            if (isNumericComparisonOp(leaf.op) && leaf.valueText != null && !leaf.isLiteral
                    && isColumnName(leaf.valueText) && columnsToAdd.contains(leaf.valueText))
            {
                numericCols.add(leaf.name);
                numericCols.add(leaf.valueText);
            }
            // Also detect literal numeric comparisons
            if (isNumericComparisonOp(leaf.op) && leaf.valueText != null && leaf.isLiteral)
            {
                try
                {
                    Double.parseDouble(leaf.valueText);
                    if (leaf.name != null && !isEngineMetaName(leaf.name))
                    {
                        numericCols.add(leaf.name);
                    }
                }
                catch (NumberFormatException ignored)
                {
                    // not numeric literal
                }
            }
            // not_equal_to with numeric literal (e.g. not_equal_to 0)
            if (leaf.op == Op.NOT_EQUAL_TO && leaf.valueText != null && leaf.isLiteral)
            {
                try
                {
                    Double.parseDouble(leaf.valueText);
                    if (leaf.name != null && !isEngineMetaName(leaf.name))
                    {
                        numericCols.add(leaf.name);
                    }
                }
                catch (NumberFormatException ignored)
                {
                    // not numeric literal
                }
            }
            // not_equal_to_divide: name and both operands in valueArray are numeric
            if (leaf.op == Op.NOT_EQUAL_TO_DIVIDE)
            {
                if (leaf.name != null && !isEngineMetaName(leaf.name))
                {
                    numericCols.add(leaf.name);
                }
                if (leaf.valueArray != null)
                {
                    for (String v : leaf.valueArray)
                    {
                        if (v != null && !isEngineMetaName(v) && columnsToAdd.contains(v))
                        {
                            numericCols.add(v);
                        }
                    }
                }
            }
        }

        // --- Default: single-row table with invalid values ---
        // Create columns with appropriate types
        Set<String> stringCols = new LinkedHashSet<>(columnsToAdd);
        stringCols.removeAll(numericCols);
        OverlayDataTable table = virtualTable(domainName, 1, stringCols.toArray(String[]::new));
        for (String col : numericCols)
        {
            if (columnsToAdd.contains(col))
            {
                table.addColumn(col, net.cumba.datatable.values.DataValueType.DOUBLE, col);
            }
        }

        // Detect columns participating in date_part / time_part comparisons.
        // These need ISO date-string values, not the generic "VALUE", so the
        // engine's date-part comparator can extract and compare date / time
        // portions deterministically.
        Set<String> datePartCols = new LinkedHashSet<>();
        for (LeafInfo leaf : leaves)
        {
            if (isDatePartOp(leaf.op) && leaf.name != null && !isEngineMetaName(leaf.name)
                    && columnsToAdd.contains(leaf.name))
            {
                datePartCols.add(leaf.name);
                if (leaf.valueText != null && !leaf.isLiteral && !isEngineMetaName(leaf.valueText)
                        && columnsToAdd.contains(leaf.valueText))
                {
                    datePartCols.add(leaf.valueText);
                }
            }
        }

        // Columns that carry an `empty` leaf must stay null in the invalid
        // table even when a sibling `exists` leaf on the same column would
        // otherwise populate them. CDISC-AD0012 (`*FL exists` + `*FL empty` +
        // `*FN non_empty`) is the canonical case: a populated `*FL` makes the
        // `empty` leaf false and the rule cannot fire. A null value satisfies
        // both `exists` (the column is present in the table) and `empty`.
        Set<String> columnsWithEmptyLeaf = new LinkedHashSet<>();
        for (LeafInfo leaf : leaves)
        {
            if (leaf.op == Op.EMPTY && leaf.name != null)
            {
                columnsWithEmptyLeaf.add(leaf.name);
            }
        }

        // First pass: set all non_empty columns to a base value
        for (LeafInfo leaf : leaves)
        {
            if (leaf.name == null || isEngineMetaName(leaf.name)
                    || columnsToOmit.contains(leaf.name))
            {
                continue;
            }
            if (leaf.op == Op.NON_EMPTY)
            {
                if (datePartCols.contains(leaf.name))
                {
                    // Placeholder ISO date — overwritten in pass 2 by the
                    // date_part comparator with values that satisfy the rule.
                    table.setValue(0, leaf.name, "2023-01-01");
                }
                else if (numericCols.contains(leaf.name))
                {
                    table.setValue(0, leaf.name, 1.0);
                }
                else
                {
                    table.setValue(0, leaf.name, "VALUE");
                }
            }
        }

        // Second pass: set comparison/operator-specific values
        for (LeafInfo leaf : leaves)
        {
            if (leaf.name == null || isEngineMetaName(leaf.name)
                    || columnsToOmit.contains(leaf.name))
            {
                continue;
            }
            if (leaf.op == Op.NON_EMPTY)
            {
                continue; // already handled
            }
            // Handle date_part / time_part column-reference comparisons
            // (CDISC-AD0045 etc.): set ISO date strings whose date parts (or
            // time parts) differ or agree according to the operator. The LHS
            // is conventionally the *DTM (full datetime) and the RHS the *DT
            // (date-only) or *TM (time-only) column.
            if (isDatePartOp(leaf.op) && leaf.valueText != null && !leaf.isLiteral
                    && !isEngineMetaName(leaf.valueText) && columnsToAdd.contains(leaf.valueText))
            {
                setDatePartValues(table, 0, leaf.name, leaf.valueText, leaf.op);
            }
            // Handle column-reference comparisons (greater_than, less_than, etc.)
            else if (isComparisonOp(leaf.op) && leaf.valueText != null && !leaf.isLiteral
                    && !isEngineMetaName(leaf.valueText) && columnsToAdd.contains(leaf.valueText))
            {
                // Value is a column reference — set both columns appropriately
                setComparisonValues(table, 0, leaf.name, leaf.valueText, leaf.op);
            }
            else if (leaf.op == Op.NOT_EQUAL_TO_DIVIDE && leaf.valueArray != null
                    && leaf.valueArray.size() == 2)
            {
                // value is [numerator, denominator]; set them and make name
                // unequal to the quotient.
                String num = leaf.valueArray.get(0);
                String den = leaf.valueArray.get(1);
                if (num != null && !isEngineMetaName(num) && columnsToAdd.contains(num))
                {
                    table.setValue(0, num, 10.0);
                }
                if (den != null && !isEngineMetaName(den) && columnsToAdd.contains(den))
                {
                    table.setValue(0, den, 2.0);
                }
                // Quotient would be 5.0 — set name to something else
                table.setValue(0, leaf.name, 999.0);
            }
            else if (leaf.op == Op.NOT_EQUAL_TO && leaf.valueText != null && leaf.isLiteral
                    && numericCols.contains(leaf.name))
            {
                // numeric literal comparison, e.g. not_equal_to 0
                try
                {
                    double n = Double.parseDouble(leaf.valueText);
                    table.setValue(0, leaf.name, n + 99.0);
                }
                catch (NumberFormatException _)
                {
                    table.setValue(0, leaf.name, 99.0);
                }
            }
            else
            {
                // An `exists` leaf on a column that also has an `empty` leaf
                // (e.g. CDISC-AD0012's `*FL exists` + `*FL empty`) needs the
                // column present-but-empty: set an explicit empty string so the
                // column is registered in the table metadata (satisfies
                // `exists`) yet carries no value (satisfies `empty`). A bare
                // null would let an all-null column be pruned, making `exists`
                // false; a non-empty value would make `empty` false. Either way
                // the rule could not fire.
                boolean existsWithEmptySibling = leaf.op == Op.VAR_EXISTS
                        && columnsWithEmptyLeaf.contains(leaf.name);
                String val = existsWithEmptySibling ? "" : invalidValueForLeaf(leaf);
                if (val != null)
                {
                    table.setValue(0, leaf.name, val);
                }
            }
        }

        return table;
    }

    // ---- Value generation ----


    /**
     * Returns a value that makes the leaf condition TRUE (for invalid scenario).
     */
    private String invalidValueForLeaf(LeafInfo leaf)
    {
        if (leaf.op == null)
        {
            return "VALUE";
        }
        return switch (leaf.op)
        {
        case NON_EMPTY -> "VALUE";
        case EMPTY -> null;
        case IS_NOT_CONTAINED_BY -> "INVALID_VALUE_NOT_IN_LIST";
        case IS_CONTAINED_BY ->
        {
            if (leaf.valueArray != null && !leaf.valueArray.isEmpty())
            {
                yield leaf.valueArray.get(0);
            }
            yield "Y";
        }
        case NOT_EQUAL_TO ->
        {
            if (leaf.valueText != null)
            {
                // For numeric values, yield a different number
                try
                {
                    int n = Integer.parseInt(leaf.valueText);
                    yield String.valueOf(n + 99);
                }
                catch (NumberFormatException ignored)
                {
                    // Non-numeric: append suffix
                }
                yield leaf.valueText + "_DIFFERENT";
            }
            yield "DIFFERENT";
        }
        case EQUAL_TO ->
        {
            if (leaf.valueText != null)
            {
                yield leaf.valueText;
            }
            yield "MATCH";
        }
        case VAR_EXISTS -> "VALUE";
        case VAR_NOT_EXISTS -> null; // handled by omitting column
        case GREATER_THAN ->
        {
            if (leaf.valueText != null)
            {
                try
                {
                    yield String.valueOf(Integer.parseInt(leaf.valueText) + 1);
                }
                catch (NumberFormatException _)
                {
                    yield "999";
                }
            }
            yield "999";
        }
        case LESS_THAN ->
        {
            if (leaf.valueText != null)
            {
                try
                {
                    yield String.valueOf(Integer.parseInt(leaf.valueText) - 1);
                }
                catch (NumberFormatException _)
                {
                    yield "0";
                }
            }
            yield "0";
        }
        case DOES_NOT_CONTAIN -> "NO_MATCH_HERE";
        case CONTAINS ->
        {
            if (leaf.valueText != null)
            {
                yield "PREFIX_" + leaf.valueText + "_SUFFIX";
            }
            yield "VALUE";
        }
        case NOT_MATCHES_REGEX -> "!!!INVALID!!!";
        case IS_NOT_UNIQUE_RELATIONSHIP -> "DUPLICATE";
        case STARTS_WITH ->
        {
            if (leaf.valueText != null)
            {
                yield leaf.valueText + "_REST";
            }
            yield "VALUE";
        }
        case ENDS_WITH ->
        {
            if (leaf.valueText != null)
            {
                yield "PREFIX_" + leaf.valueText;
            }
            yield "VALUE";
        }
        default -> "VALUE";
        };
    }

    // ---- Helpers ----


    private static boolean isVariableMetadataCheck(Rule rule)
    {
        return Domain.VARIABLE.equals(rule.getEvaluationDomain());
    }


    /**
     * Identifies the target column for a per-variable rule. Looks at:
     * <ol>
     * <li>{@code variable_name == X} leaf — the explicit per-column gate.</li>
     * <li>The rule's Core ID suffix — when the WildcardExpander has emitted
     * {@code <origCoreId>-<COLUMN>}, the suffix is the target. Validates against the library mock
     * so we only accept suffixes that actually correspond to a known variable in the domain (skips
     * numeric/freeform suffixes).</li>
     * </ol>
     * Returns {@code null} when no target can be inferred.
     */
    private static String inferTargetColumnFromRule(Rule rule, List<LeafInfo> leaves,
            String domainName)
    {
        for (LeafInfo leaf : leaves)
        {
            if (leaf.op == Op.EQUAL_TO && "variable_name".equals(leaf.name)
                    && leaf.valueText != null && !leaf.valueText.isEmpty())
            {
                return leaf.valueText;
            }
        }
        String coreId = rule.getCore() != null ? rule.getCore().getId() : null;
        if (coreId != null)
        {
            int dash = coreId.lastIndexOf('-');
            if (dash > 0 && dash < coreId.length() - 1)
            {
                String suffix = coreId.substring(dash + 1);
                if (!getLibraryMock().getVariableMetadata(domainName, suffix).isEmpty())
                {
                    return suffix;
                }
            }
        }
        return null;
    }


    private static boolean isComparisonOp(Op op)
    {
        return op == Op.GREATER_THAN || op == Op.GREATER_THAN_OR_EQUAL_TO || op == Op.LESS_THAN
                || op == Op.LESS_THAN_OR_EQUAL_TO || op == Op.EQUAL_TO || op == Op.NOT_EQUAL_TO;
    }


    private static boolean isNumericComparisonOp(Op op)
    {
        return op == Op.GREATER_THAN || op == Op.GREATER_THAN_OR_EQUAL_TO || op == Op.LESS_THAN
                || op == Op.LESS_THAN_OR_EQUAL_TO;
    }


    private static boolean isDatePartOp(Op op)
    {
        return op == Op.DATE_PART_EQUAL_TO || op == Op.DATE_PART_NOT_EQUAL_TO
                || op == Op.TIME_PART_EQUAL_TO || op == Op.TIME_PART_NOT_EQUAL_TO;
    }


    /**
     * Sets ISO-string values for a date_part / time_part column comparison such that the predicate
     * is TRUE on the resulting row. The LHS column is the *DTM full datetime; RHS is the *DT
     * date-only or *TM time-only.
     */
    private static void setDatePartValues(OverlayDataTable table, int row, String dtmCol,
            String dtCol, Op op)
    {
        switch (op)
        {
        case DATE_PART_NOT_EQUAL_TO ->
        {
            // Different date parts → predicate fires.
            table.setValue(row, dtmCol, "2023-02-01T12:00:00");
            table.setValue(row, dtCol, "2023-01-01");
        }
        case DATE_PART_EQUAL_TO ->
        {
            // Same date part on both sides.
            table.setValue(row, dtmCol, "2023-01-01T12:00:00");
            table.setValue(row, dtCol, "2023-01-01");
        }
        case TIME_PART_NOT_EQUAL_TO ->
        {
            table.setValue(row, dtmCol, "2023-01-01T12:00:00");
            table.setValue(row, dtCol, "T08:00:00");
        }
        case TIME_PART_EQUAL_TO ->
        {
            table.setValue(row, dtmCol, "2023-01-01T12:00:00");
            table.setValue(row, dtCol, "T12:00:00");
        }
        default ->
        {
            table.setValue(row, dtmCol, "2023-02-01T12:00:00");
            table.setValue(row, dtCol, "2023-01-01");
        }
        }
    }


    /**
     * Sets values in two columns so that the comparison condition is TRUE.
     */
    private static void setComparisonValues(OverlayDataTable table, int row, String nameCol,
            String valueCol, Op op)
    {
        // Use numeric values (Double) for comparison operators
        switch (op)
        {
        case GREATER_THAN, GREATER_THAN_OR_EQUAL_TO ->
        {
            table.setValue(row, nameCol, 100.0);
            table.setValue(row, valueCol, 50.0);
        }
        case LESS_THAN, LESS_THAN_OR_EQUAL_TO ->
        {
            table.setValue(row, nameCol, 50.0);
            table.setValue(row, valueCol, 100.0);
        }
        case NOT_EQUAL_TO ->
        {
            table.setValue(row, nameCol, 100.0);
            table.setValue(row, valueCol, 50.0);
        }
        case EQUAL_TO ->
        {
            table.setValue(row, nameCol, 100.0);
            table.setValue(row, valueCol, 100.0);
        }
        default ->
        {
            table.setValue(row, nameCol, 100.0);
            table.setValue(row, valueCol, 100.0);
        }
        }
    }

    // ---- Check condition traversal ----


    /**
     * ⚠⚠ <b>An empty result from this walk is what "this suite silently stops testing" looks like —
     * see {@link #assertFixtureBuildersAreNotVacuous}.</b>
     *
     * <p>
     * History, because this is the suite's second brush with the silent-disarm shape: phase 7a of
     * {@code PLAN-typed-expression-engine} stopped lowering an {@code expression:} Check into the
     * v1 leaf AST at load, the then-leaf-keyed walker returned an EMPTY list for every rule, and
     * {@code buildValidTable} / {@code buildInvalidTable} read that as <i>"cannot build data for
     * this rule pattern"</i> — 498 calls, 498 empty, 150+ green tests asserting nothing. The
     * vacuity ratchet below was built from that incident.
     * </p>
     *
     * <p>
     * D121 then retired the leaf model entirely, so the walk now reads {@link CheckExprUnits}'
     * v1-vocabulary units — <b>all-or-nothing, exactly like the strict lowering it replaces</b>: a
     * Check carrying any unit without a strict classification yields no leaves at all, exactly as
     * such an expression yielded none before phase 7. The builders' ~50 operator comparisons keep
     * their vocabulary through the local {@link Op} enum.
     * </p>
     *
     * @see #assertFixtureBuildersAreNotVacuous
     */
    private List<LeafInfo> collectLeaves(CheckCondition condition)
    {
        List<LeafInfo> leaves = new ArrayList<>();
        Expr check = CheckExprUnits.toExpr(condition);
        if (check != null)
        {
            List<CheckExprUnits.Unit> units = CheckExprUnits.units(check);
            if (!units.isEmpty() && units.stream().allMatch(CheckExprUnits.Unit::strict))
            {
                for (CheckExprUnits.Unit unit : units)
                {
                    leaves.add(new LeafInfo(unit));
                }
            }
        }
        LEAF_WALKS.incrementAndGet();
        if (leaves.isEmpty())
        {
            EMPTY_LEAF_WALKS.incrementAndGet();
        }
        return leaves;
    }

    /** Total {@link #collectLeaves} calls in this JVM — the denominator of the vacuity ratchet. */
    static final java.util.concurrent.atomic.AtomicLong LEAF_WALKS = new java.util.concurrent.atomic.AtomicLong();

    /** Calls that produced NO leaves, i.e. a rule this suite silently declines to test. */
    static final java.util.concurrent.atomic.AtomicLong EMPTY_LEAF_WALKS = new java.util.concurrent.atomic.AtomicLong();

    /**
     * ⭐ <b>The ratchet that makes the disarm above impossible to repeat.</b> A fixture generator
     * whose every builder returns {@code null} is indistinguishable from one that passes, because
     * "cannot build data for this rule pattern" is a legitimate per-rule outcome and the tests
     * simply return. This asserts the population-level property instead: most rules must still be
     * buildable.
     *
     * <p>
     * ⚠ Deliberately a FRACTION and not a count — the expanded rule set grows, and a floor stated
     * in rules would be met by a suite that had stopped testing two thirds of them.
     * </p>
     */
    static void assertFixtureBuildersAreNotVacuous()
    {
        long walks = LEAF_WALKS.get();
        long empty = EMPTY_LEAF_WALKS.get();
        assertTrue(walks > 0, "the fixture builders were never invoked — the suite tested nothing");
        assertTrue(empty * 2 < walks,
                "the ADaM template fixture builders have gone VACUOUS: " + empty + " of " + walks
                        + " rules yielded no leaves, so their dynamic tests returned without"
                        + " asserting anything. The unit walk no longer classifies the Check"
                        + " shape the loader produces — see collectLeaves.");
    }


    /**
     * Whether a leaf requires its column to be <em>absent</em> for the rule to fire — a bare
     * {@code (var_|ds_)not_exists} operator, or an {@code (var_|ds_)exists}/{@code exists} leaf
     * under a {@code not}. The fixture builders omit such columns from the invalid table (and
     * include them in the valid table).
     */
    private static boolean requiresColumnAbsent(LeafInfo leaf)
    {
        if (leaf.op == Op.VAR_NOT_EXISTS || leaf.op == Op.DS_NOT_EXISTS)
        {
            return true;
        }
        return leaf.negated && (leaf.op == Op.VAR_EXISTS || leaf.op == Op.DS_EXISTS);
    }


    /**
     * Returns {@code true} if the string looks like a CDISC column name: at least 2 characters, all
     * uppercase letters/digits/underscores, starts with a letter.
     */
    private static boolean isColumnName(String name)
    {
        if (name == null || name.length() < 2)
        {
            return false;
        }
        if (!Character.isUpperCase(name.charAt(0)))
        {
            return false;
        }
        for (int i = 1; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if (!Character.isUpperCase(c) && !Character.isDigit(c) && c != '_')
            {
                return false;
            }
        }
        return true;
    }


    /**
     * Returns {@code true} if the name is an engine metadata reference (starts with lowercase or
     * {@code $}), not a real column name.
     */
    private static boolean isEngineMetaName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return true;
        }
        char first = name.charAt(0);
        return first == '$' || Character.isLowerCase(first);
    }

    // ---- Leaf info ----

    private static class LeafInfo
    {

        final String name;

        final Op op;

        final String valueText;

        final List<String> valueArray;

        final boolean isLiteral;

        // True when the unit sits under an odd number of structural `not` wrappers, so the rule
        // requires the OPPOSITE verdict. A negated var_exists means "the column must be ABSENT
        // for the rule to fire" — the fixture builders use this to omit (invalid) / include
        // (valid) the column, the same way they treat a flat var_not_exists.
        final boolean negated;

        // Polymorphic post-Fix #25: ``within`` is either a single column name or a list of
        // column names on the wire; read from the unit's underlying call, textual members only,
        // mirroring the retired CheckConditionLeaf.getWithinColumns.
        final List<String> within;

        LeafInfo(CheckExprUnits.Unit unit)
        {
            this.name = unit.name();
            this.op = Op.from(unit.operator());
            this.negated = unit.negated();
            this.within = withinOf(unit.node());
            Expr value = stripValueTag(unit.value());
            if (value instanceof Expr.Binary arith)
            {
                // The fused arithmetic shapes (X != A / B): the v1 leaf carried [A, B].
                List<String> arr = new ArrayList<>();
                collectRefNames(arith, arr);
                this.valueText = null;
                this.valueArray = arr;
                this.isLiteral = false;
            }
            else if (value instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
            {
                List<String> arr = new ArrayList<>();
                if (lit.value() instanceof List<?> items)
                {
                    for (Object item : items)
                    {
                        if (item instanceof Expr e)
                        {
                            String text = operandText(e);
                            if (text != null)
                            {
                                arr.add(text);
                            }
                        }
                    }
                }
                this.valueText = null;
                this.valueArray = arr;
                this.isLiteral = false;
            }
            else if (value instanceof Expr.Lit lit)
            {
                this.valueText = literalText(lit);
                this.valueArray = null;
                this.isLiteral = true;
            }
            else if (value != null)
            {
                this.valueText = operandText(value);
                this.valueArray = null;
                this.isLiteral = false;
            }
            else
            {
                this.valueText = null;
                this.valueArray = null;
                this.isLiteral = false;
            }
        }

        /**
         * The transparent type tags / conversions a value operand is read through — the compiler's
         * own comparison-marker vocabulary, <b>derived</b> from
         * {@link net.cumba.corej.core.expr.eval.ExprCompiler#comparisonMarkers()} rather than
         * transcribed (M4, terminal review of {@code PLAN-typed-expression-engine}): the hand list
         * here omitted {@code time}, which D71b put into the corpus in phase 3c, so
         * {@code CDISC-AD0044}'s value side ({@code time(*TM)}) never resolved to its column and
         * {@link #setDatePartValues} never set the discriminating ISO values — the pair
         * discriminated only through the junk values the {@code non_empty} handler left behind (the
         * time_part comparator's unconditional mixed-pair FIRE), measured 2026-09-17.
         *
         * <p>
         * ⚠ Deriving widens the set by TWO, not one: the hand list was
         * {@code date/date_part/time_part/num}, and the compiler's vocabulary also carries
         * {@code time} and {@code str} (review round 2, R2-13 — the M4 commit message named only
         * {@code time}). {@code str} is kept, deliberately: the two GATED sites
         * ({@code CheckExprUnits}, {@code DualFormCheckNode}) exclude it because they translate to
         * the retired v1 vocabulary, which has no type-insensitive-equality family — an argument
         * about the LEGACY surface that says nothing about this harness, which only needs to see
         * through a transparent wrapper to the column beneath it. {@code str(X)} is exactly such a
         * wrapper. Measured harmless as well as sound: no {@code *-adamig-*} package contains the
         * token {@code str(}, so no ADaM template scenario reaches it today. ⚠ This sentence used
         * to enumerate which standards the four {@code str(} rules — CDISC-CG0371, CORE-000206,
         * FDA-SD0077, PMDA-SD0077 — belong to, and was corrected TWICE for getting that enumeration
         * wrong ("all SDTM" → "SDTM/SEND" → CORE-000206 is also published into
         * {@code rules-core-tig-1-0}). The enumeration was never the operative claim; the ADaM
         * absence is, and it is re-derived above. Do not put a standards list back.
         * </p>
         */
        private static final Set<String> VALUE_TAGS = Stream
                .concat(net.cumba.corej.core.expr.eval.ExprCompiler.comparisonMarkers().stream(),
                        // ⚑ str left the compiler's marker roster on 2026-09-30
                        // (PLAN-dynamic-column-functions §2.1: a registered conversion); it is
                        // still a transparent wrapper for this harness, so it is kept explicitly.
                        Stream.of("str"))
                .collect(Collectors.toUnmodifiableSet());

        /** The value operand with a transparent type tag / conversion removed. */
        private static Expr stripValueTag(Expr value)
        {
            if (value instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 1
                    && VALUE_TAGS.contains(c.name()))
            {
                return stripValueTag(c.args().get(0));
            }
            return value;
        }


        private static String operandText(Expr e)
        {
            return switch (stripValueTag(e))
            {
            case Expr.Ref r -> r.name();
            case Expr.Lit lit -> literalText(lit);
            case Expr.Call c when c.args().isEmpty()
                    && "varname".equals(c.name()) -> "variable_name";
            case Expr.Call c when c.args().isEmpty()
                    && "value".equals(c.name()) -> "variable_value";
            case Expr.Call c when "colref".equals(c.name()) && c.args().size() == 1 -> operandText(
                    c.args().get(0));
            default -> null;
            };
        }


        private static String literalText(Expr.Lit lit)
        {
            return switch (lit.kind())
            {
            case STRING, REGEX -> String.valueOf(lit.value());
            case BOOL -> String.valueOf(lit.value());
            case NUMBER ->
            {
                double d = (Double) lit.value();
                yield (!Double.isInfinite(d) && Double.compare(d, Math.rint(d)) == 0)
                        ? String.valueOf((long) d)
                        : String.valueOf(d);
            }
            case LIST -> null;
            };
        }


        private static void collectRefNames(Expr e, List<String> into)
        {
            switch (e)
            {
            case Expr.Ref r -> into.add(r.name());
            case Expr.Binary b ->
            {
                collectRefNames(b.left(), into);
                collectRefNames(b.right(), into);
            }
            default ->
            {
                // Literals (the pctchg 100) carry no name.
            }
            }
        }


        private static List<String> withinOf(Expr node)
        {
            if (!(node instanceof Expr.Call c))
            {
                return List.of();
            }
            Expr within = c.kwargs().get("within");
            return switch (within)
            {
            case null -> List.of();
            case Expr.Ref r -> List.of(r.name());
            case Expr.Lit lit when lit.kind() == Expr.LitKind.STRING -> List
                    .of((String) lit.value());
            case Expr.Lit lit when lit.kind() == Expr.LitKind.LIST ->
            {
                List<String> out = new ArrayList<>();
                if (lit.value() instanceof List<?> items)
                {
                    for (Object item : items)
                    {
                        // Textual members only — a nested coalesce-group is skipped, mirroring
                        // the retired getWithinColumns' tolerant read.
                        if (item instanceof Expr.Ref r)
                        {
                            out.add(r.name());
                        }
                        else if (item instanceof Expr.Lit il && il.kind() == Expr.LitKind.STRING)
                        {
                            out.add((String) il.value());
                        }
                    }
                }
                yield List.copyOf(out);
            }
            default -> List.of();
            };
        }
    }


    /**
     * The v1-vocabulary operators the fixture builders key on — the surviving half of the retired
     * engine {@code CheckOperator} enum, local to this suite. {@link #from} maps a
     * {@link CheckExprUnits.Unit#operator()} name; an operator outside this set maps to
     * {@code null}, and every comparison and switch below treats {@code null} as "no special
     * handling".
     */
    private enum Op
    {

        EQUAL_TO,
        NOT_EQUAL_TO,
        LESS_THAN,
        LESS_THAN_OR_EQUAL_TO,
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL_TO,
        EMPTY,
        NON_EMPTY,
        VAR_EXISTS,
        VAR_NOT_EXISTS,
        DS_EXISTS,
        DS_NOT_EXISTS,
        HAS_MULTIPLE_VALUES_FOR,
        IS_NOT_UNIQUE_RELATIONSHIP,
        NOT_EQUAL_TO_DIVIDE,
        DATE_PART_EQUAL_TO,
        DATE_PART_NOT_EQUAL_TO,
        TIME_PART_EQUAL_TO,
        TIME_PART_NOT_EQUAL_TO,
        IS_CONTAINED_BY,
        IS_NOT_CONTAINED_BY,
        CONTAINS,
        DOES_NOT_CONTAIN,
        STARTS_WITH,
        ENDS_WITH,
        NOT_MATCHES_REGEX;

        static Op from(String operator)
        {
            if (operator == null)
            {
                return null;
            }
            try
            {
                return valueOf(operator.toUpperCase(Locale.ROOT));
            }
            catch (IllegalArgumentException _)
            {
                return null;
            }
        }
    }

    // ---- No-op Library Provider ----


    static class NoOpLibraryMetadataProvider implements MetadataProvider
    {

        @Override
        public List<String> getRequiredVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getColumnOrder(String domain)
        {
            return List.of();
        }


        @Override
        public boolean isDomainCustom(String domain)
        {
            return false;
        }


        @Override
        public List<String> getCodelistTerms(String codelistCode)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getVariableMetadata(String domain, String variable)
        {
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String domain)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getDatasetMetadata(String domain)
        {
            return Map.of();
        }


        @Override
        public java.util.Optional<Boolean> isCodelistExtensible(String codelistName)
        {
            return java.util.Optional.of(true);
        }


        @Override
        public String getStandard()
        {
            return "ADaMIG";
        }

    }

}
