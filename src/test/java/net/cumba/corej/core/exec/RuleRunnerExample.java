package net.cumba.corej.core.exec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;

/**
 * Simple examples showing how to run CDISC CORE rules against data tables.
 *
 * <p>
 * These examples assume you already have {@link IDataTable} instances (e.g. loaded via one of the
 * datatable provider modules such as XPT or SAS7BDAT) and a rule package JSON file.
 */
public final class RuleRunnerExample
{

    private static final String RULES_SDTMIG_3_4_PATH = CorpusPaths.rules("rules-sdtmig-3-4.json")
            .toString();

    private RuleRunnerExample()
    {
    }


    /**
     * Run a single rule against a single table.
     *
     * <p>
     * This is the simplest case — no cross-domain operations are needed. Suitable for rules that
     * only reference columns in the target domain (e.g. "SEX must be in {M, F}").
     */
    public static void singleTableExample(IDataTable aeTable) throws IOException
    {
        // 1. Load the rule package
        RulePackage pkg = RulePackageLoader.loadCombined(Path.of(RULES_SDTMIG_3_4_PATH));

        // 2. Pick a rule by its UUID key
        Rule rule = Objects.requireNonNull(pkg.getRules(), "rule package has no rules").values()
                .iterator().next();

        // 3. Execute — no DatasetResolver needed for single-table rules
        RuleExecutionResult result = Objects.requireNonNull(RuleRunner.execute(rule, aeTable),
                "single-table execute returned no result");

        // 4. Inspect results
        System.out.println("Rule:       " + result.getRuleId());
        System.out.println("Message:    " + result.getMessage());
        System.out.println("Total rows: " + result.getTotalRows());
        System.out.println("Violations: " + result.getViolationCount());

        for (Violation v : result.getViolations())
        {
            System.out.printf("  Row %d: %s%n", v.getRowNumber(), v.getValues());
        }
    }


    /**
     * Run all applicable rules against a single table, filtering by domain scope and ignoring
     * cross-domain operations.
     *
     * <p>
     * Rules whose scope does not include the given domain are skipped. Rules whose operations
     * reference other domains will still execute, but their operation variables will be absent —
     * the check conditions referencing those variables will return no violations (graceful
     * degradation).
     *
     * @param table
     *            the data table to evaluate
     * @param domainName
     *            the domain name of the table (e.g. "DM", "AE")
     */
    public static void allRulesSingleTable(IDataTable table, String domainName) throws IOException
    {
        RulePackage pkg = RulePackageLoader.loadCombined(Path.of(RULES_SDTMIG_3_4_PATH));

        for (Map.Entry<String, Rule> entry : Objects
                .requireNonNull(pkg.getRules(), "rule package has no rules").entrySet())
        {
            Rule rule = entry.getValue();
            if (rule.getCheck() == null)
            {
                continue; // skip rules without a check condition
            }
            // ⚠ The two-argument matcher is the table-less API: it derives the split base from
            // the name, so a SUPP/AP letter-suffix split such as SUPPLBHM resolves to SUPPLBH
            // (7 characters) instead of the data-derived SUPPLB (6), and a rule scoped
            // Include: ["SUPP--"] is then silently skipped for it. The engine's own path
            // (DatasetRuleResolver) passes OperationExecutor.unsplitNameFromData(table) to the
            // three-argument ScopeMatcher.describeDomainMismatch and does not have this gap;
            // real callers with a table in hand should do the same.
            if (!ScopeMatcher.matchesDomain(rule, domainName))
            {
                continue; // skip rules not applicable to this domain
            }

            // D77: pass the domain so the bind-time specialisation stage can resolve `--`
            // templates — a `--` rule executed with NO domain context now errors loudly instead
            // of silently reading nothing.
            RuleExecutionResult result = Objects.requireNonNull(
                    RuleRunner.execute(rule, table, _ -> null, domainName),
                    "single-table execute returned no result");

            if (result.hasViolations())
            {
                System.out.printf("[%s] %d violation(s): %s%n", result.getRuleId(),
                        result.getViolationCount(), result.getMessage());
            }
        }
    }


    /**
     * Run rules with cross-domain support using a {@link DatasetResolver}.
     *
     * <p>
     * Some rules define operations that reference other domains. For example, a rule on the AE
     * domain may include a {@code distinct} operation on DM.USUBJID to collect all known subject
     * IDs, then check that AE.USUBJID values are contained in that set.
     *
     * <p>
     * The {@link DatasetResolver} maps domain names (e.g. "DM", "AE") to their corresponding
     * {@link IDataTable} instances.
     */
    public static void multipleTablesExample(IDataTable aeTable, IDataTable dmTable,
            IDataTable vsTable)
        throws IOException
    {

        // 1. Build a resolver from domain name → table
        Map<String, IDataTable> datasets = new HashMap<>();
        datasets.put("AE", aeTable);
        datasets.put("DM", dmTable);
        datasets.put("VS", vsTable);
        DatasetResolver resolver = datasets::get;

        // 2. Load rules
        RulePackage pkg = RulePackageLoader.loadCombined(Path.of(RULES_SDTMIG_3_4_PATH));

        // 3. Run each rule against the AE table with the resolver
        for (Rule rule : Objects.requireNonNull(pkg.getRules(), "rule package has no rules")
                .values())
        {
            if (rule.getCheck() == null)
            {
                continue;
            }

            // D77: the primary is AE — hand the domain to the specialisation stage.
            RuleExecutionResult result = RuleRunner.execute(rule, aeTable, resolver, "AE");

            if (result.hasViolations())
            {
                System.out.printf("[%s] %d violation(s): %s%n", result.getRuleId(),
                        result.getViolationCount(), result.getMessage());

                for (Violation v : result.getViolations())
                {
                    System.out.printf("  Row %d: %s%n", v.getRowNumber(), v.getValues());
                }
            }
        }
    }

}
