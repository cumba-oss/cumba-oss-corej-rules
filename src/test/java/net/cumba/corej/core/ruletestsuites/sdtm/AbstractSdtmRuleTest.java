package net.cumba.corej.core.ruletestsuites.sdtm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.ruletestsuites.CdtResourceExtension;
import net.cumba.corej.ruletest.cdt.CdtLoader;
import net.cumba.corej.ruletest.cdt.ruletest.OverridingResolver;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario.Verdict;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioCapture;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.manager.IDataTableLibraryRef;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.manager.IDataTableRef;
import net.cumba.datatable.manager.ILibraryMemberRef;
import net.cumba.datatable.manager.local.LocalDataTableManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Base class for SDTM-IG 3.4 conformance rule tests.
 * <p>
 * Loads the trimmed SDTM study fixture from {@code src/test/resources/testdata_study/sdtm/} once
 * per test suite and provides helper methods for rule lookup, data modification via
 * {@link OverlayDataTable}, and rule execution with assertions. The fixture carries only the
 * domains consumed by the Java-resident test methods (AE, DM, DS, LB, RELREC, TA) — scenarios for
 * every other domain run from self-contained {@code .cdt} files via
 * {@code RuleTestSuitesCoreFactoryTest}.
 * <p>
 * The {@link CdtResourceExtension} is registered so subclasses can use
 * {@code @CdtResource("...", base = "core")} on test parameters to declaratively load {@code .cdt}
 * files.
 */
@ExtendWith(CdtResourceExtension.class)
abstract class AbstractSdtmRuleTest
{

    // ⚠ The GENERATED corpus, via CorpusPaths — never a hand-built string. This constant used
    // to concatenate projectBasedir + "/rules/...", a shape that the §2 migration's greps did not
    // match (they looked for Path.of / resolve / a quote immediately before `rules/`). It stayed
    // green throughout §1-§5 and §10 because the committed corpus was still on disk, and only
    // reded once §6 deleted it — which is exactly why §6 must be verified from a tree that no
    // longer has the directories.
    private static final String RULES_PATH = CorpusPaths.rules("rules-sdtmig-3-4.json").toString();

    // Trimmed test fixture shipped as module-local resources so tests are not
    // coupled to the full upstream study at ../../testdata/study/. See
    // PruneDefineXmlDriver for regeneration. Domains present: AE, DM, LB,
    // RELREC, TA — the set consumed by the Java-resident test methods.
    private static final String SDTM_DEFINE_PATH = System.getProperty("projectBasedir")
            + "/src/test/resources/testdata_study/sdtm/define.xml";

    /** All 430 SDTM-IG 3.4 rules, keyed by Core.Id. */
    static Map<String, Rule> rulesByCoreId;

    /** All available SDTM domain tables, keyed by domain name (uppercase). */
    static Map<String, IDataTable> domains;

    /** Resolves domain names to loaded tables. */
    static DatasetResolver resolver;

    @BeforeAll
    static void loadRulesAndData() throws IOException
    {
        // Load rules
        RulePackage pkg = RulePackageLoader.loadCombined(Path.of(RULES_PATH));
        rulesByCoreId = new HashMap<>();
        for (Rule rule : pkg.getRules().values())
        {
            String coreId = rule.getCore() != null ? rule.getCore().getId() : rule.getId();
            rulesByCoreId.put(coreId, rule);
        }

        // Load SDTM domains from define.xml library
        URI defineUri = Path.of(SDTM_DEFINE_PATH).toUri();
        IDataTableManager manager = new LocalDataTableManager();
        IDataTableLibraryRef library = manager.getLibraryRef(defineUri, null);

        domains = new HashMap<>();
        for (ILibraryMemberRef member : manager.getLibraryMembers(library).toList())
        {
            IDataTableRef tableRef = manager.getDataTableRef(member, null);
            IDataTable table = manager.getDataTable(tableRef);
            domains.put(member.getName().toUpperCase(Locale.ROOT), table);
        }

        // Build resolver with inventory support
        resolver = new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String name)
            {
                return domains.get(name.toUpperCase(Locale.ROOT));
            }


            @Override
            public java.util.Set<String> availableDatasets()
            {
                return domains.keySet();
            }
        };
    }

    // ---- Rule lookup ----


    /**
     * Returns the rule with the given Core ID.
     *
     * @throws AssertionError
     *             if the rule does not exist
     */
    Rule getRule(String coreId)
    {
        Rule rule = rulesByCoreId.get(coreId);
        assertNotNull(rule, "Rule " + coreId + " not found in rule package");
        return rule;
    }

    // ---- Data helpers ----


    /**
     * Returns a {@link OverlayDataTable} wrapping the real SDTM data for the given domain.
     * Modifications via {@code setValue()}, {@code removeColumn()}, etc. do not affect the
     * underlying data.
     *
     * @throws AssertionError
     *             if the domain is not available in test data
     */
    OverlayDataTable testTable(String domain)
    {
        IDataTable table = domains.get(domain.toUpperCase(Locale.ROOT));
        assertNotNull(table, "Domain " + domain + " not available in test data");
        return new OverlayDataTable(table);
    }


    /**
     * Creates a virtual empty domain table not backed by any test data file. Use
     * {@link OverlayDataTable#addColumn} and {@link OverlayDataTable#setValue} to define its schema
     * and data.
     *
     * @param domain
     *            the domain name (e.g. "SS")
     * @param rowCount
     *            the number of rows
     * @param columns
     *            column names to pre-create
     * @return a new virtual OverlayDataTable
     */
    OverlayDataTable virtualTable(String domain, int rowCount, String... columns)
    {
        var table = OverlayDataTable.empty(domain, domain, rowCount);
        for (String col : columns)
        {
            table.addColumn(col);
        }
        return table;
    }


    /**
     * Loads a {@code .cdt} test data file from the classpath under
     * {@code src/test/resources/net/cumba/corej/core/ruletestsuites/core/}.
     *
     * @param relativePath
     *            path relative to the sdtm resource root (e.g.
     *            {@code "SDTMIG001/SDTMIG001-valid-DM.cdt"})
     * @return the loaded {@link OverlayDataTable}
     */
    OverlayDataTable loadCdt(String relativePath)
    {
        String fullPath = "net/cumba/corej/core/ruletestsuites/core/" + relativePath;
        try
        {
            return CdtLoader.loadResource(fullPath);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to load CDT resource: " + fullPath, e);
        }
    }


    /**
     * Loads a {@code .cdt} file and asserts the given rule does NOT fire. The domain name is
     * inferred from the dataset metadata of the loaded file.
     */
    void assertNoViolationCdt(String coreId, String relativePath)
    {
        OverlayDataTable table = loadCdt(relativePath);
        assertNoViolation(coreId, table, table.getMetaData().getName());
    }


    /**
     * Loads a {@code .cdt} file and asserts the given rule DOES fire. The domain name is inferred
     * from the dataset metadata of the loaded file.
     */
    void assertViolationCdt(String coreId, String relativePath)
    {
        OverlayDataTable table = loadCdt(relativePath);
        assertViolation(coreId, table, table.getMetaData().getName());
    }


    /**
     * Loads a {@code .cdt} file and asserts the given rule does NOT fire, with the loaded table
     * registered in the resolver under its domain name. Use for rules whose Operations look up the
     * current dataset by name.
     */
    void assertNoViolationCdtSelfResolver(String coreId, String relativePath)
    {
        OverlayDataTable table = loadCdt(relativePath);
        String domain = table.getMetaData().getName();
        assertNoViolation(coreId, table, domain, resolverWith(domain, table));
    }


    /**
     * Loads a {@code .cdt} file and asserts the given rule DOES fire, with the loaded table
     * registered in the resolver under its domain name.
     */
    void assertViolationCdtSelfResolver(String coreId, String relativePath)
    {
        OverlayDataTable table = loadCdt(relativePath);
        String domain = table.getMetaData().getName();
        assertViolation(coreId, table, domain, resolverWith(domain, table));
    }


    /**
     * Loads multiple {@code .cdt} files and asserts the given rule does NOT fire on the primary
     * table, with all loaded tables registered in the resolver under their respective domain names.
     */
    void assertNoViolationCdtMulti(String coreId, String primaryPath, String... additionalPaths)
    {
        OverlayDataTable primary = loadCdt(primaryPath);
        Map<String, IDataTable> overrides = new HashMap<>();
        overrides.put(primary.getMetaData().getName().toUpperCase(Locale.ROOT), primary);
        for (String path : additionalPaths)
        {
            OverlayDataTable extra = loadCdt(path);
            overrides.put(extra.getMetaData().getName().toUpperCase(Locale.ROOT), extra);
        }
        assertNoViolation(coreId, primary, primary.getMetaData().getName(),
                resolverWith(overrides));
    }


    /**
     * Loads multiple {@code .cdt} files and asserts the given rule DOES fire on the primary table,
     * with all loaded tables registered in the resolver.
     */
    void assertViolationCdtMulti(String coreId, String primaryPath, String... additionalPaths)
    {
        OverlayDataTable primary = loadCdt(primaryPath);
        Map<String, IDataTable> overrides = new HashMap<>();
        overrides.put(primary.getMetaData().getName().toUpperCase(Locale.ROOT), primary);
        for (String path : additionalPaths)
        {
            OverlayDataTable extra = loadCdt(path);
            overrides.put(extra.getMetaData().getName().toUpperCase(Locale.ROOT), extra);
        }
        assertViolation(coreId, primary, primary.getMetaData().getName(), resolverWith(overrides));
    }

    // ---- Resolver helpers ----


    /**
     * Returns a resolver that overrides a single domain with a modified table, falling back to the
     * shared resolver for all other domains. Preserves the inventory semantics of the shared
     * resolver so operations like {@code variable_count} that iterate every study dataset still see
     * the full domain inventory.
     */
    OverridingResolver resolverWith(String domain, IDataTable table)
    {
        return OverridingResolver.override(resolver, domain, table);
    }


    /**
     * Returns a resolver that overrides multiple domains with modified tables. Preserves inventory
     * semantics (see {@link #resolverWith(String, IDataTable)}).
     */
    OverridingResolver resolverWith(java.util.Map<String, IDataTable> overrides)
    {
        return OverridingResolver.overrides(resolver, overrides);
    }


    /**
     * Returns a resolver where the specified domains are absent (return null). Preserves inventory
     * semantics, omitting the removed domains from the listing.
     */
    OverridingResolver resolverWithout(String... removedDomains)
    {
        return OverridingResolver.without(resolver, removedDomains);
    }

    // ---- Execution helpers ----


    /**
     * Executes a rule against a table with the shared resolver and domain prefix derived from the
     * table name.
     * <p>
     * Literal fallback is enabled: string values that cannot be resolved as column references are
     * treated as literal strings. This handles CDISC CORE rules that omit
     * {@code value_is_literal: true}.
     */
    RuleExecutionResult execute(Rule rule, IDataTable table, String domainPrefix)
    {
        return RuleRunner.execute(rule, table, resolver, domainPrefix, null, null);
    }


    /**
     * Executes a rule with a custom resolver (for cross-dataset tests that need modified tables in
     * the resolver).
     */
    RuleExecutionResult execute(Rule rule, IDataTable table, String domainPrefix,
            DatasetResolver customResolver)
    {
        return RuleRunner.execute(rule, table, customResolver, domainPrefix, null, null);
    }


    /**
     * Executes a rule with a custom resolver and a {@link MetadataProvider}. Use for
     * Library-dependent operations (e.g. {@code get_dataset_filtered_variables},
     * {@code get_model_filtered_variables}, {@code valid_codelist_dates}).
     */
    RuleExecutionResult execute(Rule rule, IDataTable table, String domainPrefix,
            DatasetResolver customResolver, MetadataProvider libraryProvider)
    {
        return RuleRunner.execute(rule, table, customResolver, domainPrefix, libraryProvider, null);
    }

    // ---- Assertion helpers ----


    /**
     * Asserts that the rule does NOT fire (valid data scenario).
     */
    void assertNoViolation(String coreId, IDataTable table, String domainPrefix)
    {
        Rule rule = getRule(coreId);
        RuleExecutionResult result = execute(rule, table, domainPrefix);
        assertFalse(result.hasViolations(), coreId + " should not fire on valid data but found "
                + result.getViolationCount() + " violation(s)");
        if (ScenarioCapture.isEnabled() && table instanceof OverlayDataTable t)
        {
            ScenarioCapture.captureWithSiblings(coreId, Verdict.NO_VIOLATION, domainPrefix, t, rule,
                    resolver);
        }
    }


    /**
     * Asserts that the rule DOES fire (invalid data scenario).
     */
    void assertViolation(String coreId, IDataTable table, String domainPrefix)
    {
        Rule rule = getRule(coreId);
        RuleExecutionResult result = execute(rule, table, domainPrefix);
        // ⚠ An ERROR carries RuleRunner's "__error__" sentinel Violation, so hasViolations()
        // is TRUE for a rule that errored and the assertion below would pass VACUOUSLY.
        // Reachable since PLAN-column-type-conformance's ColumnTypeGate. Same discipline as
        // ScenarioDeclaredScopeProviderTest, which already guarded this way.
        assertFalse(result.isError(), "ERRORED rather than firing: " + result.getStatusMessage());
        assertTrue(result.hasViolations(),
                coreId + " should fire on invalid data but found no violations");
        if (ScenarioCapture.isEnabled() && table instanceof OverlayDataTable t)
        {
            ScenarioCapture.captureWithSiblings(coreId, Verdict.VIOLATION, domainPrefix, t, rule,
                    resolver);
        }
    }


    /**
     * Asserts that the rule does NOT fire, using a custom resolver.
     */
    void assertNoViolation(String coreId, IDataTable table, String domainPrefix,
            DatasetResolver customResolver)
    {
        Rule rule = getRule(coreId);
        RuleExecutionResult result = execute(rule, table, domainPrefix, customResolver);
        assertFalse(result.hasViolations(), coreId + " should not fire on valid data but found "
                + result.getViolationCount() + " violation(s)");
        if (ScenarioCapture.isEnabled() && table instanceof OverlayDataTable t)
        {
            ScenarioCapture.captureWithSiblings(coreId, Verdict.NO_VIOLATION, domainPrefix, t, rule,
                    customResolver);
        }
    }


    /**
     * Asserts that the rule DOES fire, using a custom resolver.
     */
    void assertViolation(String coreId, IDataTable table, String domainPrefix,
            DatasetResolver customResolver)
    {
        Rule rule = getRule(coreId);
        RuleExecutionResult result = execute(rule, table, domainPrefix, customResolver);
        // ⚠ An ERROR carries RuleRunner's "__error__" sentinel Violation, so hasViolations()
        // is TRUE for a rule that errored and the assertion below would pass VACUOUSLY.
        // Reachable since PLAN-column-type-conformance's ColumnTypeGate. Same discipline as
        // ScenarioDeclaredScopeProviderTest, which already guarded this way.
        assertFalse(result.isError(), "ERRORED rather than firing: " + result.getStatusMessage());
        assertTrue(result.hasViolations(),
                coreId + " should fire on invalid data but found no violations");
        if (ScenarioCapture.isEnabled() && table instanceof OverlayDataTable t)
        {
            ScenarioCapture.captureWithSiblings(coreId, Verdict.VIOLATION, domainPrefix, t, rule,
                    customResolver);
        }
    }


    /**
     * Asserts that the rule does NOT fire, with custom resolver and library provider.
     */
    void assertNoViolation(String coreId, IDataTable table, String domainPrefix,
            DatasetResolver customResolver, MetadataProvider libraryProvider)
    {
        Rule rule = getRule(coreId);
        RuleExecutionResult result = execute(rule, table, domainPrefix, customResolver,
                libraryProvider);
        assertFalse(result.hasViolations(), coreId + " should not fire on valid data but found "
                + result.getViolationCount() + " violation(s)");
        if (ScenarioCapture.isEnabled() && table instanceof OverlayDataTable t)
        {
            ScenarioCapture.captureWithSiblings(coreId, Verdict.NO_VIOLATION, domainPrefix, t, rule,
                    customResolver, libraryProvider);
        }
    }


    /**
     * Asserts that the rule DOES fire, with custom resolver and library provider.
     */
    void assertViolation(String coreId, IDataTable table, String domainPrefix,
            DatasetResolver customResolver, MetadataProvider libraryProvider)
    {
        Rule rule = getRule(coreId);
        RuleExecutionResult result = execute(rule, table, domainPrefix, customResolver,
                libraryProvider);
        // ⚠ An ERROR carries RuleRunner's "__error__" sentinel Violation, so hasViolations()
        // is TRUE for a rule that errored and the assertion below would pass VACUOUSLY.
        // Reachable since PLAN-column-type-conformance's ColumnTypeGate. Same discipline as
        // ScenarioDeclaredScopeProviderTest, which already guarded this way.
        assertFalse(result.isError(), "ERRORED rather than firing: " + result.getStatusMessage());
        assertTrue(result.hasViolations(),
                coreId + " should fire on invalid data but found no violations");
        if (ScenarioCapture.isEnabled() && table instanceof OverlayDataTable t)
        {
            ScenarioCapture.captureWithSiblings(coreId, Verdict.VIOLATION, domainPrefix, t, rule,
                    customResolver, libraryProvider);
        }
    }

}
