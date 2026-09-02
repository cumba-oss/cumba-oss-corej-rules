package net.cumba.cdisc.define.conformance.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.cdisc.define.conformance.rule.DefineRulePackageManifest;
import net.cumba.cdisc.define.conformance.rule.RuleRepository;
import org.junit.jupiter.api.Test;

/**
 * The shipped Define-XML corpus must be exactly what {@link DefineRuleCorpusGenerator} produces
 * from {@code rules-define-src/} — the same contract {@code NativeCorpusRoundTripTest} enforces for
 * the CDISC corpus.
 *
 * <p>
 * This is what stops the corpus going stale. {@code scripts/regen-rules-define-corpus.sh} is a
 * developer tool nobody is obliged to remember; this test reds when a rule is authored, edited or
 * parked without regenerating, and it reds in the ordinary build.
 * </p>
 *
 * <p>
 * ⚠ A byte comparison alone is <b>not</b> sufficient evidence that the packaging is correct: both
 * sides come from the same generator, so a serialization bug would reproduce identically and pass.
 * {@link DefineRulePackageEquivalenceTest} is the other half — it compares packaged rules against
 * their authored source as <em>objects</em>, which is what catches a naming or type-mapping fault.
 * </p>
 */
class DefineRuleCorpusRoundTripTest
{

    @Test
    void regeneratingFromSourceReproducesTheCommittedCorpusByteForByte() throws IOException
    {
        DefineRuleCorpusGenerator.Result result = DefineRuleCorpusGenerator
                .generate(RuleRepository.loadDirectory(DefineRuleCorpusGenerator.SOURCE));

        assertFalse(result.files().isEmpty(),
                "no packages discovered from " + DefineRuleCorpusGenerator.SOURCE.toAbsolutePath()
                        + " — the round trip would" + " pass vacuously over an empty corpus");

        for (Map.Entry<String, String> file : result.files().entrySet())
        {
            assertSameText(DefineRuleCorpusGenerator.target().resolve(file.getKey()),
                    file.getValue());
        }
        assertSameText(
                DefineRuleCorpusGenerator.target().resolve(DefineRulePackageManifest.FILE_NAME),
                result.manifest());
    }


    /**
     * No orphans. A renamed or removed {@code (family, version)} must not leave its old package
     * behind, where a loader would still find it.
     */
    @Test
    void theCorpusDirectoryHoldsExactlyTheGeneratedFiles() throws IOException
    {
        DefineRuleCorpusGenerator.Result result = DefineRuleCorpusGenerator
                .generate(RuleRepository.loadDirectory(DefineRuleCorpusGenerator.SOURCE));

        Set<String> expected = new TreeSet<>(result.files().keySet());
        expected.add(DefineRulePackageManifest.FILE_NAME);

        Set<String> onDisk = new TreeSet<>();
        try (Stream<Path> files = Files.list(DefineRuleCorpusGenerator.target()))
        {
            files.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .forEach(onDisk::add);
        }
        assertEquals(expected, onDisk,
                "rules-define/ does not hold exactly the generated files — a stale package left"
                        + " behind is still loadable, so this is a correctness gate, not tidiness");
    }


    /** The manifest must describe the packages that are actually there, with the right counts. */
    @Test
    void theManifestAgreesWithThePackagesOnDisk() throws IOException
    {
        DefineRulePackageManifest manifest = DefineRulePackageManifest
                .load(DefineRuleCorpusGenerator.target());
        assertFalse(manifest.packages().isEmpty(), "empty manifest");

        for (DefineRulePackageManifest.Entry entry : manifest.packages())
        {
            Path file = DefineRuleCorpusGenerator.target().resolve(entry.file());
            assertTrue(Files.isRegularFile(file), "manifest names a missing package: " + file);
            var pkg = RuleRepository.loadPackage(file);
            assertEquals(entry.family(), pkg.family(), "family disagrees for " + entry.file());
            assertEquals(entry.version(), pkg.version(), "version disagrees for " + entry.file());
            assertEquals(entry.ruleCount(), pkg.rules().size(),
                    "ruleCount disagrees for " + entry.file());
        }

        List<String> named = manifest.packages().stream().map(DefineRulePackageManifest.Entry::file)
                .sorted().toList();
        assertEquals(named.size(), Set.copyOf(named).size(), "the manifest names a file twice");
    }


    private static void assertSameText(Path aCommitted, String aRegenerated) throws IOException
    {
        assertTrue(Files.isRegularFile(aCommitted),
                "the generator produced " + aCommitted.getFileName()
                        + " but it is not committed under " + DefineRuleCorpusGenerator.target()
                        + " — run scripts/regen-rules-define-corpus.sh");
        String committed = Files.readString(aCommitted, StandardCharsets.UTF_8);
        if (committed.equals(aRegenerated))
        {
            return;
        }
        // The packages run to hundreds of KB; dumping both sides would bury the one line that
        // matters, so report the first divergent line instead.
        String[] a = committed.split("\n", -1);
        String[] b = aRegenerated.split("\n", -1);
        for (int i = 0; i < Math.min(a.length, b.length); i++)
        {
            if (!a[i].equals(b[i]))
            {
                throw new AssertionError(aCommitted + " is stale at line " + (i + 1)
                        + " — run scripts/regen-rules-define-corpus.sh\n  committed:   " + a[i]
                        + "\n  regenerated: " + b[i]);
            }
        }
        throw new AssertionError(
                aCommitted + " is stale: committed has " + a.length + " lines, regenerated has "
                        + b.length + " — run scripts/regen-rules-define-corpus.sh");
    }

}
