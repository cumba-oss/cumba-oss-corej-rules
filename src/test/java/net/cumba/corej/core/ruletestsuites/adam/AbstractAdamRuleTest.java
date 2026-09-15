package net.cumba.corej.core.ruletestsuites.adam;

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
 * Base class for ADaM Conformance Rule v5.0 (ADaMIG v1.3) tests.
 * <p>
 * Loads all 699 ADaM rules and 10 ADaM datasets + 22 SDTM datasets once per test suite. Provides
 * the same helper methods as the SDTM test base class.
 * <p>
 * The {@link CdtResourceExtension} is registered so subclasses can use {@code @CdtResource("...")}
 * on test parameters to declaratively load {@code .cdt} files.
 */
@ExtendWith(CdtResourceExtension.class)
abstract class AbstractAdamRuleTest
{

    private static final String RULES_PATH = CorpusPaths.rules("rules-adamig-1-3.json").toString();

    // Trimmed test fixtures shipped as module-local resources so tests are not
    // coupled to the full upstream study at ../../testdata/study/. See
    // PruneDefineXmlDriver for regeneration. ADaM domains present: ADSL, ADAE,
    // ADLBC. SDTM domains present: AE, DM, LB, RELREC, TA.
    private static final String ADAM_DEFINE_PATH = System.getProperty("projectBasedir")
            + "/src/test/resources/testdata_study/adam/define.xml";

    private static final String SDTM_DEFINE_PATH = System.getProperty("projectBasedir")
            + "/src/test/resources/testdata_study/sdtm/define.xml";

    /**
     * ADaM class mapping: dataset define.xml class → ADaM rule scope class name.
     */
    static final Map<String, String> CLASS_MAP = Map.of("ADSL", "SUBJECT LEVEL ANALYSIS DATASET",
            "ADAE", "OCCURRENCE DATA STRUCTURE", "BDS", "BASIC DATA STRUCTURE");

    /**
     * Dataset-to-class mapping for the 10 ADaM test datasets in the original Pilot-01 study. The
     * shipped fixture only carries ADSL, ADAE and ADLBC; the remaining entries are kept for
     * reference and for any future test that reintroduces those domains into the fixture.
     */
    static final Map<String, String> DOMAIN_CLASS_MAP = Map.ofEntries(
            Map.entry("ADSL", "SUBJECT LEVEL ANALYSIS DATASET"),
            Map.entry("ADAE", "OCCURRENCE DATA STRUCTURE"),
            Map.entry("ADLBC", "BASIC DATA STRUCTURE"), Map.entry("ADLBH", "BASIC DATA STRUCTURE"),
            Map.entry("ADLBHY", "BASIC DATA STRUCTURE"),
            Map.entry("ADQSADAS", "BASIC DATA STRUCTURE"),
            Map.entry("ADQSCIBC", "BASIC DATA STRUCTURE"),
            Map.entry("ADQSNPIX", "BASIC DATA STRUCTURE"),
            Map.entry("ADTTE", "BASIC DATA STRUCTURE"), Map.entry("ADVS", "BASIC DATA STRUCTURE"));

    /** All ADaM conformance rules, keyed by Core.Id (e.g., "CDISC-AD0001"). */
    static Map<String, Rule> rulesByCoreId;

    /** ADaM domain tables, keyed by dataset name (uppercase). */
    static Map<String, IDataTable> adamDomains;

    /** SDTM domain tables for cross-dataset rules. */
    static Map<String, IDataTable> sdtmDomains;

    /** Combined resolver: ADaM + SDTM domains. */
    static DatasetResolver resolver;

    @BeforeAll
    static void loadRulesAndData() throws IOException
    {
        // Load ADaM rules
        RulePackage pkg = RulePackageLoader.loadCombined(Path.of(RULES_PATH));
        rulesByCoreId = new HashMap<>();
        for (Rule rule : pkg.getRules().values())
        {
            String coreId = rule.getCore() != null ? rule.getCore().getId() : rule.getId();
            rulesByCoreId.put(coreId, rule);
        }

        // Load ADaM domains
        adamDomains = loadDomains(ADAM_DEFINE_PATH);

        // Load SDTM domains (for cross-dataset rules like USUBJID in DM)
        sdtmDomains = loadDomains(SDTM_DEFINE_PATH);

        // Build combined resolver with inventory
        Map<String, IDataTable> allDomains = new HashMap<>();
        allDomains.putAll(sdtmDomains);
        allDomains.putAll(adamDomains); // ADaM overrides SDTM if same name
        resolver = new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String name)
            {
                return allDomains.get(name.toUpperCase(Locale.ROOT));
            }


            @Override
            public java.util.Set<String> availableDatasets()
            {
                return allDomains.keySet();
            }
        };
    }


    private static Map<String, IDataTable> loadDomains(String definePath) throws IOException
    {
        URI defineUri = Path.of(definePath).toUri();
        IDataTableManager manager = new LocalDataTableManager();
        IDataTableLibraryRef library = manager.getLibraryRef(defineUri, null);
        Map<String, IDataTable> domains = new HashMap<>();
        for (ILibraryMemberRef member : manager.getLibraryMembers(library).toList())
        {
            IDataTableRef tableRef = manager.getDataTableRef(member, null);
            IDataTable table = manager.getDataTable(tableRef);
            domains.put(member.getName().toUpperCase(Locale.ROOT), table);
        }
        return domains;
    }

    // ---- Rule lookup ----


    Rule getRule(String coreId)
    {
        Rule rule = rulesByCoreId.get(coreId);
        assertNotNull(rule, "Rule " + coreId + " not found in rule package");
        return rule;
    }

    // ---- Data helpers ----


    OverlayDataTable testTable(String domain)
    {
        IDataTable table = adamDomains.get(domain.toUpperCase(Locale.ROOT));
        if (table == null)
        {
            table = sdtmDomains.get(domain.toUpperCase(Locale.ROOT));
        }
        assertNotNull(table, "Domain " + domain + " not available in test data");
        return new OverlayDataTable(table);
    }


    OverlayDataTable virtualTable(String domain, int rowCount, String... columns)
    {
        var table = OverlayDataTable.empty(domain, domain, rowCount);
        for (String col : columns)
        {
            // ⚠⚠ addColumn's default is STRING, and a synthetic table that declares EVERY column
            // Char is a fixture that lies about the standard. ADaM's convention pairs a character
            // flag `xxxFL` with a NUMERIC `xxxFN` (0/1) — CDISC-AD0006's own message says so
            // ("a variable with a suffix of FN is populated with a value other than 0 or 1").
            // Measured 2026-09-14: with everything Char, 24 `CDISC-AD0006-*FN` template tests were
            // passing VACUOUSLY — the rule ERRORED on the column-type gate, RuleRunner's
            // "__error__" sentinel made hasViolations() true, and `assertTrue(hasViolations())`
            // read that as a detection. The guard in AbstractAdamTemplateTest catches it now; this
            // stops manufacturing it.
            if (isAdamNumericFlag(col))
            {
                table.addColumn(col, net.cumba.datatable.values.DataValueType.DOUBLE, col);
            }
            else
            {
                table.addColumn(col);
            }
        }
        return table;
    }


    /**
     * Whether {@code column} is an ADaM numeric flag — the {@code xxxFN} counterpart of a character
     * {@code xxxFL}. Deliberately narrow: it types only the family this harness was measured to
     * mis-declare, rather than inventing a general type oracle the tests would then silently depend
     * on. A broader fix reads the CDISC Library metadata.
     *
     * @param column
     *            the column name.
     * @return {@code true} when the name follows the numeric-flag convention.
     */
    private static boolean isAdamNumericFlag(String column)
    {
        return column.length() > 2 && column.endsWith("FN")
                && !SDTM_EXTERNAL_FILE_NAMES.contains(column)
                && column.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
    }

    /**
     * SDTM's {@code --XFN} "External File Name" family — the one {@code FN}-suffixed group that is
     * <b>Char</b>, not a numeric flag.
     * <p>
     * ⚠⚠ Without this exclusion the suffix test would manufacture the inverse of the defect it was
     * written to remove: a fixture declaring a Char variable numeric. Measured against the CDISC
     * Library cache, {@code FN}-suffixed variables split 21 Num / 7 Char, and the seven are exactly
     * these. ⚠ A suffix test cannot separate them on shape — ADaM's own {@code NCAXFN} and
     * {@code PKSUMXFN} are also {@code …XFN} and genuinely Num — so the separation has to be by
     * name. None of the seven appears in any authored check today; the exclusion exists so the
     * first ADaM rule to reference one does not get a silently mistyped fixture.
     * </p>
     */
    private static final java.util.Set<String> SDTM_EXTERNAL_FILE_NAMES = java.util.Set.of("EGXFN",
            "FTXFN", "GFXFN", "MOXFN", "MSXFN", "OEXFN", "PTXFN");

    /**
     * Loads a {@code .cdt} test data file from the classpath. The file is expected under
     * {@code src/test/resources/net/cumba/corej/core/ruletestsuites/cdisc/}.
     *
     * @param relativePath
     *            path relative to the adam resource root (e.g.
     *            {@code "CDISC-AD0117/CDISC-AD0117-valid-ADLBC.cdt"})
     * @return the loaded {@link OverlayDataTable}
     */
    OverlayDataTable loadCdt(String relativePath)
    {
        String fullPath = "net/cumba/corej/core/ruletestsuites/cdisc/" + relativePath;
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
     * Loads a {@code .cdt} file and asserts the given rule does NOT fire, using a resolver where
     * the loaded table is registered under its own domain name. Use this for rules whose Operations
     * look up the current dataset by name (e.g., baseline-lookup rules like CDISC-AD0181).
     */
    void assertNoViolationCdtSelfResolver(String coreId, String relativePath)
    {
        OverlayDataTable table = loadCdt(relativePath);
        String domain = table.getMetaData().getName();
        assertNoViolation(coreId, table, domain, resolverWith(domain, table));
    }


    /**
     * Loads a {@code .cdt} file and asserts the given rule DOES fire, using a resolver where the
     * loaded table is registered under its own domain name. Use this for rules whose Operations
     * look up the current dataset by name (e.g., baseline-lookup rules like CDISC-AD0181).
     */
    void assertViolationCdtSelfResolver(String coreId, String relativePath)
    {
        OverlayDataTable table = loadCdt(relativePath);
        String domain = table.getMetaData().getName();
        assertViolation(coreId, table, domain, resolverWith(domain, table));
    }


    /**
     * Loads multiple {@code .cdt} files and asserts the given rule does NOT fire on the primary
     * table, using a resolver that registers all loaded tables under their respective domain names.
     * Use this for cross-dataset tests that need to inject multiple test tables.
     *
     * @param coreId
     *            rule ID
     * @param primaryPath
     *            path to the primary {@code .cdt} file (rule executed on this table)
     * @param additionalPaths
     *            paths to additional {@code .cdt} files loaded into the resolver
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


    /**
     * Returns the ADaM class name for the given domain.
     */
    String getClassName(String domain)
    {
        return DOMAIN_CLASS_MAP.getOrDefault(domain.toUpperCase(Locale.ROOT),
                "BASIC DATA STRUCTURE");
    }

    // ---- Resolver helpers ----


    OverridingResolver resolverWith(String domain, IDataTable table)
    {
        return OverridingResolver.override(resolver, domain, table);
    }


    OverridingResolver resolverWith(Map<String, IDataTable> overrides)
    {
        return OverridingResolver.overrides(resolver, overrides);
    }


    OverridingResolver resolverWithout(String... removedDomains)
    {
        return OverridingResolver.without(resolver, removedDomains);
    }

    // ---- Execution helpers ----


    RuleExecutionResult execute(Rule rule, IDataTable table, String domainName)
    {
        return RuleRunner.execute(rule, table, resolver, domainName, null, null);
    }


    RuleExecutionResult execute(Rule rule, IDataTable table, String domainName,
            DatasetResolver customResolver)
    {
        return RuleRunner.execute(rule, table, customResolver, domainName, null, null);
    }


    /**
     * Executes a rule with a custom resolver and a {@link MetadataProvider}. Use for rules whose
     * Operations need CDISC Library metadata (e.g. {@code dataset_class_from_library},
     * {@code required_variables}, {@code codelist_terms}).
     */
    RuleExecutionResult execute(Rule rule, IDataTable table, String domainName,
            DatasetResolver customResolver, MetadataProvider libraryProvider)
    {
        return RuleRunner.execute(rule, table, customResolver, domainName, libraryProvider, null);
    }

    // ---- Assertion helpers ----


    void assertNoViolation(String coreId, IDataTable table, String domainName)
    {
        Rule rule = getRule(coreId);
        RuleExecutionResult result = execute(rule, table, domainName);
        assertFalse(result.hasViolations(), coreId + " should not fire on valid data but found "
                + result.getViolationCount() + " violation(s)");
        if (ScenarioCapture.isEnabled() && table instanceof OverlayDataTable t)
        {
            ScenarioCapture.captureWithSiblings(coreId, Verdict.NO_VIOLATION, domainName, t, rule,
                    resolver);
        }
    }


    void assertViolation(String coreId, IDataTable table, String domainName)
    {
        Rule rule = getRule(coreId);
        RuleExecutionResult result = execute(rule, table, domainName);
        // ⚠ An ERROR carries RuleRunner's "__error__" sentinel Violation, so hasViolations()
        // is TRUE for a rule that errored and the assertion below would pass VACUOUSLY.
        // Reachable since PLAN-column-type-conformance's ColumnTypeGate. Same discipline as
        // ScenarioDeclaredScopeProviderTest, which already guarded this way.
        assertFalse(result.isError(), "ERRORED rather than firing: " + result.getStatusMessage());
        assertTrue(result.hasViolations(),
                coreId + " should fire on invalid data but found no violations");
        if (ScenarioCapture.isEnabled() && table instanceof OverlayDataTable t)
        {
            ScenarioCapture.captureWithSiblings(coreId, Verdict.VIOLATION, domainName, t, rule,
                    resolver);
        }
    }


    void assertNoViolation(String coreId, IDataTable table, String domainName,
            DatasetResolver customResolver)
    {
        Rule rule = getRule(coreId);
        RuleExecutionResult result = execute(rule, table, domainName, customResolver);
        assertFalse(result.hasViolations(), coreId + " should not fire on valid data but found "
                + result.getViolationCount() + " violation(s)");
        if (ScenarioCapture.isEnabled() && table instanceof OverlayDataTable t)
        {
            ScenarioCapture.captureWithSiblings(coreId, Verdict.NO_VIOLATION, domainName, t, rule,
                    customResolver);
        }
    }


    void assertViolation(String coreId, IDataTable table, String domainName,
            DatasetResolver customResolver)
    {
        Rule rule = getRule(coreId);
        RuleExecutionResult result = execute(rule, table, domainName, customResolver);
        // ⚠ An ERROR carries RuleRunner's "__error__" sentinel Violation, so hasViolations()
        // is TRUE for a rule that errored and the assertion below would pass VACUOUSLY.
        // Reachable since PLAN-column-type-conformance's ColumnTypeGate. Same discipline as
        // ScenarioDeclaredScopeProviderTest, which already guarded this way.
        assertFalse(result.isError(), "ERRORED rather than firing: " + result.getStatusMessage());
        assertTrue(result.hasViolations(),
                coreId + " should fire on invalid data but found no violations");
        if (ScenarioCapture.isEnabled() && table instanceof OverlayDataTable t)
        {
            ScenarioCapture.captureWithSiblings(coreId, Verdict.VIOLATION, domainName, t, rule,
                    customResolver);
        }
    }


    /**
     * Asserts that the rule does NOT fire, with custom resolver and library provider.
     */
    void assertNoViolation(String coreId, IDataTable table, String domainName,
            DatasetResolver customResolver, MetadataProvider libraryProvider)
    {
        Rule rule = getRule(coreId);
        RuleExecutionResult result = execute(rule, table, domainName, customResolver,
                libraryProvider);
        assertFalse(result.hasViolations(), coreId + " should not fire on valid data but found "
                + result.getViolationCount() + " violation(s)");
        if (ScenarioCapture.isEnabled() && table instanceof OverlayDataTable t)
        {
            ScenarioCapture.captureWithSiblings(coreId, Verdict.NO_VIOLATION, domainName, t, rule,
                    customResolver, libraryProvider);
        }
    }


    /**
     * Asserts that the rule DOES fire, with custom resolver and library provider.
     */
    void assertViolation(String coreId, IDataTable table, String domainName,
            DatasetResolver customResolver, MetadataProvider libraryProvider)
    {
        Rule rule = getRule(coreId);
        RuleExecutionResult result = execute(rule, table, domainName, customResolver,
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
            ScenarioCapture.captureWithSiblings(coreId, Verdict.VIOLATION, domainName, t, rule,
                    customResolver, libraryProvider);
        }
    }


    /**
     * Loads a {@code .cdt} file and asserts the given rule does NOT fire, using the table's
     * self-scope resolver plus a {@link MetadataProvider}.
     */
    void assertNoViolationCdtWithLibrary(String coreId, String relativePath,
            MetadataProvider libraryProvider)
    {
        OverlayDataTable table = loadCdt(relativePath);
        String domain = table.getMetaData().getName();
        assertNoViolation(coreId, table, domain, resolverWith(domain, table), libraryProvider);
    }


    /**
     * Loads a {@code .cdt} file and asserts the given rule DOES fire, using the table's self-scope
     * resolver plus a {@link MetadataProvider}.
     */
    void assertViolationCdtWithLibrary(String coreId, String relativePath,
            MetadataProvider libraryProvider)
    {
        OverlayDataTable table = loadCdt(relativePath);
        String domain = table.getMetaData().getName();
        assertViolation(coreId, table, domain, resolverWith(domain, table), libraryProvider);
    }

}
