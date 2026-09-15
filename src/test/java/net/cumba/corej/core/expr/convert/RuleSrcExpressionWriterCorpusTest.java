package net.cumba.corej.core.expr.convert;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageManifest;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.convert.RuleSrcExpressionWriter.Result;
import net.cumba.corej.core.expr.convert.RuleStorageAssembler.Triple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The standing corpus gate of {@code plans/done/PLAN-rules-src-expression-flip.md} §6, re-shaped at
 * the flip (wave 43) from the wave-42 dry-run proof it grew out of. On the flipped
 * (expression-form) corpus the writer ({@link RuleSrcExpressionWriter}) must be <b>idempotent</b>,
 * so this test now pins, all measured <em>by run</em>:
 *
 * <ul>
 * <li>3 802 in / 3 802 out, 0 unraisable (floor-asserted; exact counts printed; re-measured by run
 * 2026-08-22 &mdash; 3 781 at the flip);</li>
 * <li><b>writer idempotence, byte-level:</b> re-running the writer over {@code rules-src/checks}
 * reproduces every authored file <em>byte-for-byte</em> — the corpus is writer-canonical (D27
 * layout included), so a canonicalising rewrite is provably a no-op;</li>
 * <li>0 generic {@code exists}/{@code not_exists} calls survive (§5's writer guarantee, now an
 * authored-corpus invariant);</li>
 * <li>the 5 parked rules stay parked; the formerly-curated authored texts — the three
 * by-{@code Core.Id} strings AND the two leaf-signature carriers' texts — hold against the authored
 * corpus as ordinary authored-text pins (the converter registries that once enforced them retired
 * with {@code PLAN-retire-corpus-transforms.md} phase 1; these pins are their replacement, so a
 * revert to the broken upstream shape is still caught);</li>
 * <li><b>the round-trip sanity:</b> assembling the writer's output through the production pipeline
 * ({@link RuleStorageAssembler} &rarr; {@link RuleExprGenerator#generate}) reproduces the committed
 * {@code rules/} <b>byte-for-byte</b> — the §6 invariant, proven pre-flip on the legacy corpus and
 * standing here on the flipped one.</li>
 * </ul>
 */
class RuleSrcExpressionWriterCorpusTest
{

    private static final Path SRC_DIR = Path.of("rules-src");

    private static final Path RULES_DIR = CorpusPaths.rules();

    // ⚠ Corpus-size floor dropped on extraction: it asserted the FULL internal corpus,
    // which this subset deliberately is not. Enforced internally over the whole corpus.

    /**
     * ⚠ CORE-000029 and CORE-000067 are in the internal parked set and are NOT here: this
     * repository does not redistribute the CORE rule family, so those checks never reach the
     * writer. Otherwise identical to internal.
     */
    private static final Set<String> PARKED = Set.of("CDISC-CG0091", "CDISC-CG0120", "CDISC-CG0126",
            "CDISC-CG0158", "CDISC-CG0254", "CDISC-CG0328", "CDISC-CG0361", "CDISC-CG0622",
            "CDISC-CG0626", "CDISC-CG0630", "CDISC-CG0635", "CDISC-CG0636", "CDISC-CG0640",
            "CDISC-CG0642", "CDISC-CG0643", "CDISC-CG0644", "CDISC-SEND-0202", "CDISC-SEND-0203",
            "CDISC-SEND-0204", "CDISC-SEND-0205", "CDISC-SEND-0268", "DRAFT-900027", "FDA-SD0063",
            "PMDA-SD0063");

    /**
     * Ordinary authored-text pins for the three formerly-curated rules. ⚠ These are, and always
     * were, a hand-copy of the intended texts — never a literal read of the retired
     * {@code RulePackageConverter.SPECIAL_CASE_BY_CORE_ID} registry (only the key set ever flowed
     * from the converter). The registry was an accidental safety net: reverting one of these three
     * files to the broken upstream regex used to be silently repaired by the Java constant. Since
     * {@code PLAN-retire-corpus-transforms.md} phase 1 the registry is gone and this pin is the
     * deliberate replacement — an edit to one of these files that moves the text reds this run and
     * must be accompanied by moving the pin.
     */
    private static final Map<String, String> CURATED_BY_ID = Map.of("CORE-000335",
            "TSPARMCD == \"RANDQT\" and not empty(TSVAL) "
                    + "and (num(TSVAL) <= 0 or num(TSVAL) > 1 or TSVAL !~ /^[+-]?([0-9]+(\\\\.[0-9]+)?|\\\\.[0-9]+)$/)",
            "CORE-000539",
            "ds_name(\"DATA\") =~ /^[A-Za-z]{2}[A-Za-z0-9]{1,2}$/ "
                    + "and prefix(dataset_name, 2) not in [\"AP\", \"FA\"] "
                    + "and prefix(dataset_name, 2) not in $list_dataset_names",
            "CORE-000540",
            "ds_name(\"DATA\") =~ /^[A-Za-z]{2}[A-Za-z]{1,2}$/ "
                    + "and prefix(dataset_name, 2) == \"FA\" "
                    + "and suffix(dataset_name, 2) not in $list_dataset_names");

    /**
     * The two carriers of the retired {@code does_not_equal_string_part} leaf-signature rewrite;
     * their authored texts are pinned below as ordinary authored-text pins.
     */
    private static final Set<String> CURATED_BY_LEAF = Set.of("CDISC-CG0334", "CORE-000538");

    @Test
    void theWriterConvertsTheWholeAuthoredCorpusCleanly(@TempDir Path tempOut) throws IOException
    {
        // Opt-in: -Dflip.dryrun.keep=<dir> redirects the converted corpus to a directory that
        // survives the run, so the flip wave (and the D27 sample) can inspect real output. The
        // gate never sets it; the default stays a JUnit temp dir and zero repo bytes move.
        String keep = System.getProperty("flip.dryrun.keep");
        Path out = keep == null || keep.isBlank() ? tempOut
                : Files.createDirectories(Path.of(keep));
        Path checksRoot = SRC_DIR.resolve("checks");
        Result r = RuleSrcExpressionWriter.run(checksRoot, out);
        System.out.printf(Locale.ROOT,
                "[flip-dry-run] %d in, %d out, %d unraisable, %d generic-presence residue,"
                        + " parked %s, divergent from the plain raise %s%n",
                r.filesIn(), r.filesOut(), r.unraisable().size(), r.genericResidue().size(),
                r.parked(), r.divergentFromPlainRaise());

        // ⚠ Corpus-size floor removed on extraction: it asserted the FULL internal corpus,
        // which this subset deliberately is not. Enforced internally over the whole corpus.
        assertEquals(r.filesIn(), r.filesOut(), "every authored file must convert");
        assertEquals(List.of(), r.unraisable(), "an authored Check failed to raise");
        assertEquals(List.of(), r.genericResidue(),
                "an authored Check carries the retired generic exists/not_exists call — the"
                        + " loader rejects it; spell var_exists / ds_exists");
        assertEquals(PARKED, r.parked(), "the parked set moved");

        List<Path> files = sortedYamlFiles(checksRoot);
        assertEquals(r.filesIn(), files.size());
        int checked = 0;
        for (Path file : files)
        {
            Path rel = checksRoot.relativize(file);
            String original = Files.readString(file, UTF_8);
            String rewritten = Files.readString(out.resolve(rel), UTF_8);

            // Writer idempotence on the flipped corpus: the authored file IS the writer's
            // canonical output, byte for byte (D27 layout included).
            assertEquals(original, rewritten,
                    rel + ": the writer is not idempotent on the authored corpus");

            // The §6 text gate: every carried expression is a print ∘ parse fixed point. A
            // plain Check carries one; a level-keyed Check (Plan C §3.3) carries one per level.
            com.fasterxml.jackson.databind.JsonNode check = RuleSrcExpressionWriter.YAML
                    .readTree(rewritten).path("Check");
            java.util.SequencedMap<String, com.fasterxml.jackson.databind.JsonNode> levels = RuleFingerprint
                    .levels(check);
            List<String> expressions = levels == null ? List.of(check.path("expression").asText())
                    : levels.values().stream().map(n -> n.path("expression").asText()).toList();
            for (String levelExpression : expressions)
            {
                assertFalse(levelExpression.isBlank(), rel + ": no expression carried");
                assertEquals(levelExpression,
                        ExpressionPrinter.print(CheckExpressionParser.parse(levelExpression)),
                        rel + ": the written expression is not a print∘parse fixed point");
            }
            String expression = expressions.getFirst();

            String id = rel.getFileName().toString().replace(".yaml", "");
            String curated = CURATED_BY_ID.get(id);
            if (curated != null)
            {
                assertEquals(curated, expression,
                        id + ": the written expression must byte-match the curated one");
            }
            if (CURATED_BY_LEAF.contains(id))
            {
                // The flip-day re-base of SpecialCaseLeafSignatureTest: the leaf-signature
                // rewrite's product (chars 5-6 of $dataset_name) is pinned as authored text —
                // the raw-leaf signature itself retired with the authored leaf corpus.
                assertEquals("RDOMAIN != substring($dataset_name, 5, 2)", expression,
                        id + ": the leaf-signature carrier's authored text moved");
            }
            checked++;
        }
        assertEquals(r.filesOut(), checked);

        // On the writer-canonical corpus nothing may diverge from the plain typed raise except
        // the shapes the plain raise cannot reproduce (strict-lowering asymmetries of restored
        // forms; historically the curated specials, whose texts are authored directly since the
        // registries retired). Anything outside that reads as writer drift.
        Set<String> unexplained = new java.util.TreeSet<>(r.divergentFromPlainRaise());
        unexplained.removeAll(CURATED_BY_LEAF);
        unexplained.removeAll(CURATED_BY_ID.keySet());
        assertEquals(Set.of(), unexplained,
                "the writer's text drifted from the plain typed raise outside the formerly-"
                        + "curated authored texts on an already-canonical corpus");
    }


    /**
     * The §6 round-trip sanity, run pre-flip: the temp-converted corpus, fed through the same
     * pipeline {@code NativeCorpusRoundTripTest} replays, must reproduce the committed
     * {@code rules/} byte-for-byte. This is the whole-corpus generalisation of
     * {@link ExpressionFormAssemblyTest}'s two-rule proof — the {@code Sensitivity}
     * derive-and-validate gate, the Authorities join, the expression carry-through, the §9.D stage
     * and the Form-B rewrite all included.
     */
    @Test
    void aTempConvertedCorpusAssemblesTheCommittedRulesByteForByte(@TempDir Path staging)
        throws IOException
    {
        Path srcDir = staging.resolve("rules-src");
        Path checksOut = srcDir.resolve("checks");
        Result r = RuleSrcExpressionWriter.run(SRC_DIR.resolve("checks"), checksOut);
        assertEquals(List.of(), r.unraisable());
        assertEquals(r.filesIn(), r.filesOut());
        copyTree(SRC_DIR.resolve("docs"), srcDir.resolve("docs"));

        RuleStorageAssembler assembler = new RuleStorageAssembler(srcDir);
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
        assertFalse(nativeShaped.isEmpty(), "no packages discovered from the converted corpus");
        entries.sort(Comparator.comparing(RulePackageManifest.Entry::file));

        Path emitted = staging.resolve("native");
        RuleExprGenerator.generate(nativeShaped, new RulePackageManifest("rules-src", entries),
                emitted);

        List<String> files = new ArrayList<>(nativeShaped.keySet());
        files.add(RulePackageManifest.FILE_NAME);
        for (String file : files)
        {
            assertSameBytes(RULES_DIR.resolve(file), emitted.resolve(file));
        }
        System.out.printf(Locale.ROOT,
                "[flip-dry-run] converted corpus assembled %d packages byte-identical to rules/%n",
                nativeShaped.size());
    }


    private static List<Path> sortedYamlFiles(Path root) throws IOException
    {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root))
        {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted()
                    .forEach(out::add);
        }
        return out;
    }


    private static void copyTree(Path from, Path to) throws IOException
    {
        try (Stream<Path> stream = Files.walk(from))
        {
            for (Path source : stream.toList())
            {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source))
                {
                    Files.createDirectories(target);
                }
                else
                {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                }
            }
        }
    }


    /** Byte comparison with a compact failure, mirroring {@code NativeCorpusRoundTripTest}. */
    private static void assertSameBytes(Path committed, Path regenerated) throws IOException
    {
        String expected = Files.readString(committed, UTF_8);
        String actual = Files.readString(regenerated, UTF_8);
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
        fail(committed + ": the converted corpus does not reproduce the committed rules/ corpus."
                + " First difference at line " + (line + 1) + " of " + want.length
                + " (converted-assembly has " + got.length + "):\n" + "  committed: "
                + at(want, line) + "\n  converted: " + at(got, line));
    }


    private static String at(String[] lines, int index)
    {
        return index < lines.length ? lines[index] : "<end of file>";
    }
}
