package net.cumba.corej.core.ruletestsuites;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * The {@code #library-ref} scenarios must RUN, not skip, whenever this build has a metadata store.
 *
 * <p>
 * The suite factories deliberately <em>skip</em> a {@code #library-ref} scenario when no store is
 * available, the store does not serve its product, or the rule did not execute against it
 * ({@link ScenarioLibrarySupport}). That is the right answer on a machine without the pickle corpus
 * — and exactly how both {@code CDISC-AD0200-*-libref} scenarios skipped on every run until
 * 2026-09-28 (PLAN-cdisc-library-access-cleanup D1): nothing set the store, every gate stayed
 * green, and a skip count is read by nobody. This test drives every {@code #library-ref} scenario
 * through the factory's own {@code runScenario} and turns an abort into a <b>failure</b> whenever
 * {@link MetadataStoreFixture#storeFile()} is present. (When it is absent this test skips;
 * {@code LibraryProviderGuardTest} is what fails loudly for a build without the corpus.)
 * </p>
 *
 * <p>
 * The population is discovered, not listed — every {@code .cdt} of every family subtree whose
 * prelude carries a {@code #library-ref} line — and floored by the two AD0200 scenarios, so the
 * test cannot pass by finding nothing.
 * </p>
 */
class LibraryRefScenariosExecuteTest
{

    /** The family subtrees and the factory that runs each; one per concrete factory class. */
    private static final Map<String, AbstractRuleTestSuitesFactory> FACTORIES = Map.of("cdisc",
            new RuleTestSuitesCdiscFactoryTest(), "draft", new RuleTestSuitesDraftFactoryTest(),
            "fda", new RuleTestSuitesFdaFactoryTest(), "pmda", new RuleTestSuitesPmdaFactoryTest());

    /** The floor: the scenarios D1 un-skipped. Both must be in the discovered population. */
    private static final List<String> REQUIRED = List.of("CDISC-AD0200-valid-ADSL-libref.cdt",
            "CDISC-AD0200-invalid-ADSL-libref.cdt");

    @Test
    void everyLibraryRefScenarioRunsWhenTheBuildHasAStore() throws IOException
    {
        List<Path> scenarios = new ArrayList<>();
        List<AbstractRuleTestSuitesFactory> runners = new ArrayList<>();
        for (Map.Entry<String, AbstractRuleTestSuitesFactory> e : FACTORIES.entrySet())
        {
            assertTrue(e.getKey().equals(e.getValue().family()),
                    "factory for " + e.getKey() + " reports family " + e.getValue().family());
            Path root = AbstractRuleTestSuitesFactory.familyRoot(e.getKey());
            if (!Files.isDirectory(root))
            {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root))
            {
                for (Path p : walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".cdt")).sorted().toList())
                {
                    if (hasLibraryRef(p))
                    {
                        scenarios.add(p);
                        runners.add(e.getValue());
                    }
                }
            }
        }
        List<String> names = scenarios.stream().map(p -> p.getFileName().toString()).toList();
        assertTrue(names.containsAll(REQUIRED),
                "the #library-ref population must contain " + REQUIRED + " — found " + names);

        Assumptions.assumeTrue(MetadataStoreFixture.storeFile().isPresent(),
                "no pickle corpus, so no fixture store — LibraryProviderGuardTest fails loudly for"
                        + " that case");

        List<String> skipped = new ArrayList<>();
        for (int i = 0; i < scenarios.size(); i++)
        {
            try
            {
                runners.get(i).runScenario(scenarios.get(i));
            }
            catch (TestAbortedException e)
            {
                skipped.add(scenarios.get(i).getFileName() + ": " + e.getMessage());
            }
        }
        if (!skipped.isEmpty())
        {
            fail("the build has a metadata store, yet " + skipped.size() + " of " + scenarios.size()
                    + " #library-ref scenario(s) did not run: " + skipped);
        }
    }


    /** A {@code #library-ref} directive anywhere in the scenario's {@code #} prelude. */
    private static boolean hasLibraryRef(Path aFile) throws IOException
    {
        for (String line : Files.readAllLines(aFile, UTF_8))
        {
            String t = line.strip();
            if (t.startsWith("#library-ref ") || "#library-ref".equals(t))
            {
                return true;
            }
            if (!t.isEmpty() && !t.startsWith("#"))
            {
                return false;
            }
        }
        return false;
    }
}
