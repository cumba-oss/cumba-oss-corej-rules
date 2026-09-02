package net.cumba.cdisc.core.expr.convert;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.CorpusPaths;
import net.cumba.cdisc.core.RulePackageManifest;
import net.cumba.cdisc.core.expr.convert.RuleStorageAssembler.Triple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drift guard for the <b>native</b> corpus: re-running the {@code rules/}-producing stages of
 * {@code scripts/regen-rules-corpus.sh} over the {@code rules-src/} YAML must reproduce the
 * committed {@code rules/} JSON (and its {@code packages.json} manifest) exactly. It fails the
 * moment someone hand-edits the generated corpus, or edits {@code rules-src/} without regenerating.
 *
 * <p>
 * ⚑ <b>Why this exists.</b> {@code RuleStorageRoundTripTest} (deleted in wave 33) has asserted this
 * contract for {@code rules-legacy/} since the split-storage migration, and its javadoc claimed it
 * was "the same contract that protects {@code rules/}". It was not: {@code rules/} had only an
 * <em>idempotence</em> check (the deleted {@code OperationInlinerCorpusTest}'s shipped-corpus fixed
 * point), which passes happily on a corpus that no longer matches its source. The claim is true for
 * the first time here.
 * </p>
 *
 * <p>
 * One pipeline produces {@code rules/} since {@code PLAN-retire-corpus-transforms.md} phase 8 —
 * assemble + write ({@link RuleStorageAssembler} &rarr; {@link RuleExprGenerator#generate}) — and
 * the guard replays exactly it, with the shipped tools rather than a re-implementation, which would
 * only prove the test and the tool agree with each other.
 * </p>
 *
 * <p>
 * ⚑ {@code rules/} is built straight from the <b>canonical</b> assembled node. Wave 33 deleted the
 * second branch — {@code RuleStorageAssembler.applyLegacyDegradations} and the Python-facing
 * {@code rules-legacy/} corpus it wrote — so there are no longer two outputs whose asymmetry has to
 * be pinned; there is one, and it inherits nothing.
 * </p>
 */
class NativeCorpusRoundTripTest
{

    private static final Path RULES_DIR = CorpusPaths.rules();

    @Test
    void regeneratingFromSourceReproducesTheCommittedNativeCorpusByteForByte(
            @TempDir Path aStagingDir)
        throws IOException
    {
        RuleStorageAssembler assembler = new RuleStorageAssembler();
        Map<String, String> nativeShaped = new LinkedHashMap<>();
        List<RulePackageManifest.Entry> entries = new ArrayList<>();
        for (Triple t : assembler.discoverPackages())
        {
            String file = RuleStorageAssembler.packageFileName(t.family(), t.standard(),
                    t.version());
            ObjectNode canonical = assembler.assemblePackage(t.family(), t.standard(), t.version());
            nativeShaped.put(file, RuleStorageAssembler.serialize(canonical));
            // Plan 2 R6 — packages.json caches each package's declared standards, so this
            // reproduction must carry them too or it drifts from the committed manifest.
            entries.add(new RulePackageManifest.Entry(file, t.family(), t.standard(), t.version(),
                    canonical.get("rules").size(),
                    assembler.declaredStandards(t.standard(), t.version())));
        }
        assertFalse(nativeShaped.isEmpty(), "no packages discovered from rules-src");
        entries.sort(Comparator.comparing(RulePackageManifest.Entry::file));

        RuleExprGenerator.generate(nativeShaped, new RulePackageManifest("rules-src", entries),
                aStagingDir);

        List<String> files = new ArrayList<>(nativeShaped.keySet());
        files.add(RulePackageManifest.FILE_NAME);
        for (String file : files)
        {
            assertSameBytes(RULES_DIR.resolve(file), aStagingDir.resolve(file));
        }
    }


    /**
     * Byte comparison with a <em>compact</em> failure: the packages run to megabytes, so dumping
     * both sides would bury the one line that matters.
     */
    private static void assertSameBytes(Path aCommitted, Path aRegenerated) throws IOException
    {
        String expected = Files.readString(aCommitted, UTF_8);
        String actual = Files.readString(aRegenerated, UTF_8);
        if (expected.equals(actual))
        {
            return;
        }
        String[] want = expected.split("\n", -1);
        String[] got = actual.split("\n", -1);
        int line = 0;
        while (line < want.length && line < got.length && want[line].equals(got[line]))
        {
            line++;
        }
        fail(aCommitted + ": regenerating rules/ from rules-src drifted from the committed corpus"
                + " — re-run scripts/regen-rules-corpus.sh. First difference at line " + (line + 1)
                + " of " + want.length + " (regenerated has " + got.length + "):\n"
                + "  committed  : " + at(want, line) + "\n  regenerated: " + at(got, line));
    }


    private static String at(String[] aLines, int aIndex)
    {
        return aIndex < aLines.length ? aLines[aIndex] : "<end of file>";
    }

}
