package net.cumba.corej.core.ruletestsuites;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * The population is discovered, not listed — every {@code .cdt} the suite admits (the factory's own
 * shebang predicate) under every family of {@link RuleTestSuitesFamilyHomeTest#FACTORIES}, whose
 * parsed scenario carries a {@code #library-ref} ({@link RuleTestCdt#load(Path)}, so a tab, a BOM
 * or any other spelling the format accepts is discovered too) — and floored by the two AD0200
 * scenarios, so the test cannot pass by finding nothing. A permanent negative control
 * ({@link #theDetectorReportsAnUnresolvableRef(Path)}) feeds the detector a synthetic ref the store
 * cannot serve and requires it to be reported (LAC review round 2, L-2 / L-3 / optional control).
 * </p>
 */
class LibraryRefScenariosExecuteTest
{

    /** The floor: the scenarios D1 un-skipped. Both must be in the discovered population. */
    private static final List<String> REQUIRED = List.of("CDISC-AD0200-valid-ADSL-libref.cdt",
            "CDISC-AD0200-invalid-ADSL-libref.cdt");

    /** Runs one scenario the way a factory does; an abort is a skip. */
    @FunctionalInterface
    interface ScenarioRunner
    {

        void run(Path aScenario) throws IOException;
    }

    @Test
    void everyLibraryRefScenarioRunsWhenTheBuildHasAStore() throws IOException
    {
        List<String> names = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<Runnable> runs = new ArrayList<>();
        int population = 0;
        for (AbstractRuleTestSuitesFactory factory : RuleTestSuitesFamilyHomeTest.FACTORIES)
        {
            List<Path> scenarios = discover(
                    AbstractRuleTestSuitesFactory.familyRoot(factory.family()));
            population += scenarios.size();
            scenarios.forEach(p -> names.add(p.getFileName().toString()));
            runs.add(() -> skipped.addAll(notRun(scenarios, factory::runScenario)));
        }
        assertTrue(names.containsAll(REQUIRED),
                "the #library-ref population must contain " + REQUIRED + " — found " + names);

        Assumptions.assumeTrue(MetadataStoreFixture.storeFile().isPresent(),
                "no pickle corpus, so no fixture store — LibraryProviderGuardTest fails loudly for"
                        + " that case");

        runs.forEach(Runnable::run);
        if (!skipped.isEmpty())
        {
            fail("the build has a metadata store, yet " + skipped.size() + " of " + population
                    + " #library-ref scenario(s) did not run: " + skipped);
        }
    }


    /**
     * The detector's negative control: a scenario whose ref names a product the store does not hold
     * — written with a BOM and tab separators, which a hand-rolled prelude scan missed — must be
     * DISCOVERED and REPORTED. It also pins a CT package no store holds, so it is reported (a skip)
     * only while the product gate runs before the pinned-CT gate (L-4); with the old order it fails
     * here instead.
     */
    @Test
    void theDetectorReportsAnUnresolvableRef(@TempDir Path aDir) throws IOException
    {
        Path scenario = aDir.resolve("CDISC-AD0200-synthetic-unresolvable-libref.cdt");
        Files.writeString(scenario,
                "\uFEFF#!RuleTest\n" + "#test CDISC-AD0200 expect=noViolation domain=ADSL\n"
                        + "#library-ref\tstandard=adamig\tversion=9-9\tct=adamct-2099-01-01\n"
                        + "dataset ADSL\ncol STUDYID type=Char\n---\nCDISCPILOT01\n---\n",
                UTF_8);
        assertEquals(List.of(scenario), discover(aDir),
                "the synthetic #library-ref scenario was not discovered");

        Assumptions.assumeTrue(MetadataStoreFixture.storeFile().isPresent(),
                "no pickle corpus, so no fixture store");
        List<String> reported = notRun(List.of(scenario),
                p -> ScenarioLibrarySupport.resolveOrSkip(RuleTestCdt.load(p)));
        assertEquals(1, reported.size(), "the unresolvable ref was not reported: " + reported);
        assertTrue(reported.get(0).contains("does not serve adamig 9-9"),
                "reported for the wrong reason: " + reported);
    }


    /** Every {@code .cdt} under {@code aRoot} the suite admits whose scenario has a library ref. */
    static List<Path> discover(Path aRoot) throws IOException
    {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(aRoot))
        {
            return found;
        }
        try (Stream<Path> walk = Files.walk(aRoot))
        {
            for (Path p : walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".cdt"))
                    .filter(AbstractRuleTestSuitesFactory::hasShebang).sorted().toList())
            {
                if (RuleTestCdt.load(p).getLibraryRef() != null)
                {
                    found.add(p);
                }
            }
        }
        return found;
    }


    /** The scenarios that aborted (skipped) instead of running, with the abort message. */
    static List<String> notRun(List<Path> aScenarios, ScenarioRunner aRunner)
    {
        List<String> skipped = new ArrayList<>();
        for (Path p : aScenarios)
        {
            try
            {
                aRunner.run(p);
            }
            catch (TestAbortedException e)
            {
                skipped.add(p.getFileName() + ": " + e.getMessage());
            }
            catch (IOException e)
            {
                throw new IllegalStateException(p + " could not be read", e);
            }
        }
        return skipped;
    }
}
