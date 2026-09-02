package net.cumba.cdisc.core.ruletestsuites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.exec.Violation;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.dataviewer.examples.cdt.ruletest.RuleTestCdt;
import net.cumba.dataviewer.examples.cdt.ruletest.RuleTestScenario;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Twin replay for the seven {@code Child: true} rules (Fix #358, ruling 3 of
 * {@code plans/done/PLAN-match-datasets-split-union.md}): the {@code CORE-000206} and
 * {@code CORE-000597} scenario directories are authored <b>once</b> and replayed here under every
 * twin id, asserting <em>identical findings</em> for each scenario. The two twin classes are
 * byte-identical in {@code Check} <b>and</b> {@code Match_Datasets} (measured — canonical-JSON
 * sha1): the SUPP--/CO/RELREC class ({@code CORE-000206}, {@code CDISC-CG0371}, {@code FDA-SD0077},
 * {@code PMDA-SD0077}) and the SUPPAE→AE class ({@code CORE-000597}, {@code FDA-SD1143},
 * {@code CDISC-CG0043} — which has no scenario directory of its own, which is exactly why the
 * replay exists; the {@code .cdt} files are never copied).
 *
 * <p>
 * The scenario files themselves also run under their own family factory
 * ({@code CoreRuleTestSuitesTest}) with the full location assertions; this class adds the
 * cross-family identity contract, including the split-LB scenarios whose parent resolves through
 * the {@code SplitDomainResolution} member union.
 * </p>
 */
class SplitUnionTwinScenarioTest
{

    private record TwinClass(String scenarioDir, List<String> twinIds)
    {
    }

    private static final List<TwinClass> TWIN_CLASSES = List.of(
            new TwinClass("core/CORE-000206",
                    List.of("CORE-000206", "CDISC-CG0371", "FDA-SD0077", "PMDA-SD0077")),
            new TwinClass("core/CORE-000597",
                    List.of("CORE-000597", "FDA-SD1143", "CDISC-CG0043")));

    private static final Path RESOURCE_BASE = Path.of(System.getProperty("projectBasedir"),
            "src/test/resources/net/cumba/cdisc/core/ruletestsuites");

    /** The package view each twin executes under — the family's SDTMIG 3.4 assembly. */
    private static final String PREFERRED_STANDARD = "sdtmig-3-4";

    private static final Map<String, Map<String, Map<String, Rule>>> FAMILIES = new ConcurrentHashMap<>();

    @TestFactory
    Stream<DynamicTest> twinScenarios() throws IOException
    {
        List<DynamicTest> tests = new ArrayList<>();
        for (TwinClass tc : TWIN_CLASSES)
        {
            Path dir = RESOURCE_BASE.resolve(tc.scenarioDir());
            List<Path> files;
            try (Stream<Path> walk = Files.walk(dir))
            {
                files = walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".cdt")).sorted().toList();
            }
            assertFalse(files.isEmpty(), "no scenarios under " + dir);
            String baseId = tc.twinIds().get(0);
            for (Path f : files)
            {
                for (String id : tc.twinIds())
                {
                    tests.add(DynamicTest.dynamicTest(
                            tc.scenarioDir() + "/" + f.getFileName() + " as " + id, f.toUri(),
                            () -> runTwin(f, id, baseId)));
                }
            }
        }
        return tests.stream();
    }


    private static void runTwin(Path aFile, String aTwinId, String aBaseId) throws IOException
    {
        RuleTestScenario s = RuleTestCdt.load(aFile);
        OverlayDataTable primary = s.primaryTable();
        assertNotNull(primary, aFile + " declares domain=" + s.getDomain()
                + " but no such dataset is present in the file");

        RuleExecutionResult twinRes = execute(resolveTwin(aTwinId), primary, s);
        RuleExecutionResult baseRes = execute(resolveTwin(aBaseId), primary, s);

        // The identity contract: identical Checks + identical Match_Datasets ⇒ identical results,
        // per scenario, whatever the family packaging.
        assertEquals(baseRes.getStatus(), twinRes.getStatus(),
                aTwinId + " and " + aBaseId + " must agree on the execution status for " + aFile);
        assertEquals(rows(baseRes), rows(twinRes),
                aTwinId + " and " + aBaseId + " must agree on the violation rows for " + aFile);

        // And the scenario's own verdict, so the replay cannot rot into comparing two wrong
        // results ([[simulation-tests-empty-on-success]] — assert the shipped contract, not just
        // mutual equality).
        assertFalse(twinRes.isError(),
                aTwinId + " ERRORED on " + aFile.getFileName() + ": " + twinRes.getStatusMessage());
        switch (s.getExpect())
        {
        case VIOLATION ->
        {
            assertFalse(twinRes.isSkipped(), aTwinId + " skipped on " + aFile.getFileName() + ": "
                    + twinRes.getStatusMessage());
            assertTrue(twinRes.hasViolations(),
                    aTwinId + " expected a violation on " + aFile.getFileName() + " but got none");
        }
        case NO_VIOLATION ->
        {
            assertFalse(twinRes.isSkipped(), aTwinId + " skipped on " + aFile.getFileName() + ": "
                    + twinRes.getStatusMessage());
            assertFalse(twinRes.hasViolations(), aTwinId + " expected no violation on "
                    + aFile.getFileName() + " but got " + twinRes.getViolationCount());
        }
        case SKIPPED -> assertTrue(twinRes.isSkipped(),
                aTwinId + " expected a skip on " + aFile.getFileName());
        }
    }


    private static RuleExecutionResult execute(Rule aRule, OverlayDataTable aPrimary,
            RuleTestScenario aScenario)
    {
        return RuleRunner.execute(aRule, aPrimary, aScenario.resolver(), aScenario.getDomain(),
                null, null, null, Integer.MAX_VALUE, null, null, null);
    }


    private static List<Long> rows(RuleExecutionResult aResult)
    {
        return aResult.getViolations() == null ? List.of()
                : aResult.getViolations().stream().map(Violation::getRow).sorted().toList();
    }


    /** Resolves a twin id from its own family's packages (SDTMIG 3.4 view; see the class doc). */
    private static Rule resolveTwin(String aCoreId)
    {
        String family = familyOf(aCoreId);
        Map<String, Map<String, Rule>> packages = FAMILIES.computeIfAbsent(family,
                AbstractRuleTestSuitesFactory::loadFamily);
        Map<String, Rule> preferred = packages.get(PREFERRED_STANDARD);
        Rule rule = preferred != null ? preferred.get(aCoreId) : null;
        if (rule == null)
        {
            // Sorted, LAST wins — deliberately the same deterministic fallback as
            // AbstractRuleTestSuitesFactory.resolveRule (no break).
            for (String standardVersion : new java.util.TreeSet<>(packages.keySet()))
            {
                Rule candidate = packages.get(standardVersion).get(aCoreId);
                if (candidate != null)
                {
                    rule = candidate;
                }
            }
        }
        assertNotNull(rule, aCoreId + " not found in the " + family + " family's rule packages");
        return rule;
    }


    private static String familyOf(String aCoreId)
    {
        if (aCoreId.startsWith("CORE-"))
        {
            return "core";
        }
        if (aCoreId.startsWith("CDISC-"))
        {
            return "cdisc";
        }
        if (aCoreId.startsWith("FDA-"))
        {
            return "fda";
        }
        if (aCoreId.startsWith("PMDA-"))
        {
            return "pmda";
        }
        throw new IllegalArgumentException("unmapped rule-id family: " + aCoreId);
    }

}
