package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Gate on the generator scripts under {@code scripts/}.
 *
 * <p>
 * The generators write as they go, so running one against an engine resolved from {@code ~/.m2}
 * rewrites part of the corpus before anything notices. Measured 2026-08-04, regenerating after Fix
 * #145: {@code NoSuchMethodError Operation.getMissingValues()} after 54 files had already been
 * rewritten (+32,463 / -11,380 lines). {@code scripts/lib/corpus-gen-env.sh} removes the cause and
 * guards against its return.
 *
 * <p>
 * Two halves, and the second is the one that closes the historical hole:
 *
 * <ol>
 * <li>{@link #guardUnitSuitePasses()} runs {@code scripts/tests/test-corpus-gen-env.sh}, which
 * exercises the guard against synthetic classpaths. That suite touches no corpus file and runs no
 * Maven, which is why it is safe to gate — the real regeneration is never run from a test.
 * <li>{@link #everyGeneratorScriptSourcesTheSharedEnv()} and
 * {@link #noScriptCarriesTheDefectiveInvocationShape()} are text-level: a working guard cannot
 * catch a BYPASS — a generator invocation written <em>beside</em> a guarded one that never sources
 * the shared environment. That is exactly how {@code gen-legacy-expr-doc.sh} (since retired) and
 * {@code backfill-rules.sh} came to carry the same defect as {@code regen-rules-corpus.sh}. The
 * positive assertion alone is not enough: it would still allow a second, unguarded invocation to be
 * added next to a guarded one, so the defective shape is asserted absent as well.
 * </ol>
 */
class CorpusGenScriptGuardTest
{

    /** Surefire runs with workingDirectory = module basedir (see this module's pom). */
    private static final Path SCRIPTS = Path.of("scripts");

    private static final Path SHARED_ENV = SCRIPTS.resolve("lib").resolve("corpus-gen-env.sh");

    private static final Path GUARD_SUITE = SCRIPTS.resolve("tests")
            .resolve("test-corpus-gen-env.sh");

    /** Package prefix shared by every generator main the scripts invoke. */
    private static final String GENERATOR_PACKAGE = "net.cumba.cdisc.core.expr.convert.";

    /** The defective shape: a direct exec:java invocation, resolved from the local repository. */
    private static final String EXEC_JAVA = "exec:java";

    private static final Pattern MVN_INVOCATION = Pattern.compile("(^|\\s)mvn\\s");

    private static final Path BASH = Path.of("/bin/bash");

    @Test
    void guardUnitSuitePasses() throws IOException, InterruptedException
    {
        Assumptions.assumeTrue(Files.isExecutable(BASH),
                "no /bin/bash on this platform - the guard suite is exercised by CI on Linux");
        assertTrue(Files.isRegularFile(GUARD_SUITE), "missing guard suite: " + GUARD_SUITE);

        ProcessBuilder builder = new ProcessBuilder(BASH.toString(),
                GUARD_SUITE.toAbsolutePath().toString());
        builder.directory(Path.of(".").toAbsolutePath().toFile());
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output;
        try (var in = process.getInputStream())
        {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(3, TimeUnit.MINUTES))
        {
            process.destroyForcibly();
            fail("guard suite timed out:\n" + output);
        }
        assertEquals(0, process.exitValue(), "guard suite failed:\n" + output);
    }


    @Test
    void everyGeneratorScriptSourcesTheSharedEnv() throws IOException
    {
        assertTrue(Files.isRegularFile(SHARED_ENV), "missing shared env: " + SHARED_ENV);

        List<String> offenders = new ArrayList<>();
        for (Path script : generatorScripts())
        {
            String body = String.join("\n", effectiveLines(script));
            if (!body.contains("corpus-gen-env.sh"))
            {
                offenders.add(script + " invokes a generator without sourcing "
                        + "scripts/lib/corpus-gen-env.sh, so no stale-engine guard runs");
            }
        }
        assertTrue(offenders.isEmpty(), () -> String.join("\n", offenders));
    }


    @Test
    void noScriptCarriesTheDefectiveInvocationShape() throws IOException
    {
        List<String> offenders = new ArrayList<>();
        for (Path script : shellScripts())
        {
            for (String command : commands(script))
            {
                if (isOutputStatement(command))
                {
                    // The guard's own remedy text names the shapes it rejects.
                    continue;
                }
                if (command.contains(EXEC_JAVA))
                {
                    offenders.add(script + ": uses '" + EXEC_JAVA
                            + "'. The generator stages must run through cgen_run_main /"
                            + " cgen_run_main_test, whose classpath is guarded. -> " + command);
                }
                if (MVN_INVOCATION.matcher(command).find() && command.contains("-pl ")
                        && !command.contains("-am"))
                {
                    offenders.add(script + ": 'mvn -pl' without '-am' - reactor dependencies"
                            + " resolve from ~/.m2 instead of this tree. -> " + command);
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> String.join("\n", offenders));
    }


    /**
     * A command that only prints text. The guard prints the very shapes this test rejects, and a
     * documented shape is not an invocation.
     */
    private static boolean isOutputStatement(String command)
    {
        return command.startsWith("echo ") || command.startsWith("printf ")
                || command.startsWith("echo\"") || "echo".equals(command);
    }


    /** Scripts that invoke a generator main, excluding the shared env and its own test suite. */
    private static List<Path> generatorScripts() throws IOException
    {
        List<Path> found = new ArrayList<>();
        for (Path script : shellScripts())
        {
            if (script.equals(SHARED_ENV) || script.startsWith(SCRIPTS.resolve("tests")))
            {
                continue;
            }
            String body = String.join("\n", effectiveLines(script));
            if (body.contains(GENERATOR_PACKAGE) || body.contains("cgen_run_main"))
            {
                found.add(script);
            }
        }
        assertTrue(found.size() >= 3,
                "expected at least the three known generator scripts, found: " + found);
        return found;
    }


    private static List<Path> shellScripts() throws IOException
    {
        try (Stream<Path> files = Files.walk(SCRIPTS))
        {
            return files.filter(Files::isRegularFile).filter(
                    p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sh"))
                    .sorted().toList();
        }
    }


    /** Script lines with whole-line comments (and the shebang) removed. */
    private static List<String> effectiveLines(Path script) throws IOException
    {
        List<String> kept = new ArrayList<>();
        for (String raw : Files.readAllLines(script))
        {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#"))
            {
                continue;
            }
            kept.add(line);
        }
        return kept;
    }


    /** Effective lines with backslash continuations joined, so one command is one string. */
    private static List<String> commands(Path script) throws IOException
    {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : effectiveLines(script))
        {
            if (line.endsWith("\\"))
            {
                current.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            current.append(line);
            out.add(current.toString().strip());
            current.setLength(0);
        }
        if (current.length() > 0)
        {
            out.add(current.toString().strip());
        }
        return out;
    }
}
