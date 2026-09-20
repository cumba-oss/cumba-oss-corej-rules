package net.cumba.corej.core.expr.convert;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /** Floor, not an equality: the corpus grows (3 781 at authoring time). */
    // 3700 -> 3400 (PLAN-retire-core-family phase 6): rules-src/checks 3932 -> 3435.
    private static final int MIN_AUTHORED = 3400;

    /**
     * The six parked rules (§2): converted in place, still parked. {@code CDISC-SEND-0202} &ndash;
     * {@code 0205} are parked on EC-47; {@code DRAFT-900027} was authored
     * {@code Executability: "Not Executable"} with an {@code ExecutabilityHint} (no operator
     * detects an imputed date) &mdash; added 2026-08-22. {@code CDISC-CG0328} is parked as the
     * {@code R-5.26} duplicate of {@code CDISC-CG0329} (owner ruling D-8, 2026-09-12): both ship
     * the identical check into the same three packages, so one is parked and the lower-numbered
     * sheet row stays documented in {@code rules-src}.
     */
    // ⚑ 6 -> 26 on 2026-09-14 (F-1, owner rulings D-8 + D-18): twenty R-5.26 duplicates
    // parked across twenty pairs. ⛔ In NINE of them the survivor is the LOWER number, because
    // D-18 makes COVERAGE the invariant and the id only a tie-break — parking the higher id
    // would have cut the standards only it reaches. Six further pairs were deliberately left
    // BOTH ACTIVE (partial overlap): they double-report in exactly one package each, which is
    // the accepted cost of keeping every standard covered.
    // ⛔ 26 -> 24 on 2026-09-19 (PLAN-retire-core-family phase 6): CORE-000029 and CORE-000067
    // went with the family. ⭐ These two were NOT R-5.26 duplicates like the other twenty-four —
    // they are the pair that is AUTHORED but in no generated package, and the cause is
    // `Executability: "Not Executable"`, which parks a rule. That is exactly why §5 of the plan
    // could say "authored CORE 518, shipped CORE 516". An EXACT set equality, so no headroom
    // applied.
    private static final Set<String> PARKED = Set.of("CDISC-CG0091", "CDISC-CG0120", "CDISC-CG0126",
            "CDISC-CG0158", "CDISC-CG0254", "CDISC-CG0328", "CDISC-CG0361", "CDISC-CG0622",
            "CDISC-CG0626", "CDISC-CG0630", "CDISC-CG0635", "CDISC-CG0636", "CDISC-CG0640",
            "CDISC-CG0642", "CDISC-CG0643", "CDISC-CG0644", "CDISC-SEND-0202", "CDISC-SEND-0203",
            "CDISC-SEND-0204", "CDISC-SEND-0205", "CDISC-SEND-0268", "DRAFT-900027", "FDA-SD0063",
            "PMDA-SD0063");

    /**
     * Ordinary authored-text pins for the three formerly-curated expressions. ⚠ These are, and
     * always were, a hand-copy of the intended texts — never a literal read of the retired
     * {@code RulePackageConverter.SPECIAL_CASE_BY_CORE_ID} registry (only the key set ever flowed
     * from the converter). The registry was an accidental safety net: reverting one of these files
     * to the broken upstream regex used to be silently repaired by the Java constant. Since
     * {@code PLAN-retire-corpus-transforms.md} phase 1 the registry is gone and this pin is the
     * deliberate replacement — an edit to one of these files that moves the text reds this run and
     * must be accompanied by moving the pin.
     *
     * <p>
     * ⛔⛔ <b>RE-POINTED 2026-09-19 (review round 1): all three keys had become DEAD IDS.</b> The map
     * was keyed on {@code CORE-000335} / {@code -000539} / {@code -000540}, and the CORE family was
     * retired; the lookup {@code CURATED_BY_ID.get(id)} therefore returned {@code null} for every
     * one of the 3 435 authored files, so <b>the byte-match assertion below fired for no rule at
     * all</b> while the constant still read as a live pin. Measured: the three texts survive
     * byte-identically on live twins — {@code CDISC-CG0280}, {@code FDA-SD1269} and
     * {@code PMDA-SD1269} all carry the {@code RANDQT} text, {@code CDISC-CG0332} the
     * {@code AP}/{@code FA}-exclusion text and {@code CDISC-CG0333} the {@code FA} one — so the pin
     * is re-pointed rather than retired, and now covers five rules instead of nought.
     * {@code CDISC-CG0281} is NOT a carrier: it is the {@code RANDQT} family's count-based split
     * sibling ({@code $trt_count == 1 and $randqt_count < 1}), a different claim.
     * </p>
     *
     * <p>
     * ⚑ The {@code curatedHits} / {@code leafHits} equalities at the end of
     * {@link #theWriterConvertsTheWholeAuthoredCorpusCleanly} are the guard that makes this
     * impossible to repeat. A map-keyed pin over a corpus that can rename or retire its keys is
     * inert-by-default, and nothing in the assertion's own shape says whether it ran.
     * </p>
     */
    private static final Map<String, String> CURATED_BY_ID = Map.of("CDISC-CG0280",
            "TSPARMCD == \"RANDQT\" and not empty(TSVAL) "
                    + "and (num(TSVAL) <= 0 or num(TSVAL) > 1 or TSVAL !~ /^[+-]?([0-9]+(\\\\.[0-9]+)?|\\\\.[0-9]+)$/)",
            "FDA-SD1269",
            "TSPARMCD == \"RANDQT\" and not empty(TSVAL) "
                    + "and (num(TSVAL) <= 0 or num(TSVAL) > 1 or TSVAL !~ /^[+-]?([0-9]+(\\\\.[0-9]+)?|\\\\.[0-9]+)$/)",
            "PMDA-SD1269",
            "TSPARMCD == \"RANDQT\" and not empty(TSVAL) "
                    + "and (num(TSVAL) <= 0 or num(TSVAL) > 1 or TSVAL !~ /^[+-]?([0-9]+(\\\\.[0-9]+)?|\\\\.[0-9]+)$/)",
            "CDISC-CG0332",
            "ds_name(\"DATA\") =~ /^[A-Za-z]{2}[A-Za-z0-9]{1,2}$/ "
                    + "and prefix(dataset_name, 2) not in [\"AP\", \"FA\"] "
                    + "and prefix(dataset_name, 2) not in $list_dataset_names",
            "CDISC-CG0333",
            "ds_name(\"DATA\") =~ /^[A-Za-z]{2}[A-Za-z]{1,2}$/ "
                    + "and prefix(dataset_name, 2) == \"FA\" "
                    + "and suffix(dataset_name, 2) not in $list_dataset_names");

    /**
     * The carriers of the retired {@code does_not_equal_string_part} leaf-signature rewrite; their
     * authored texts are pinned below as ordinary authored-text pins.
     *
     * <p>
     * ⚑ 2 &rarr; 1 on 2026-09-19 (review round 1): {@code CORE-000538} went with the CORE family.
     * Unlike {@link #CURATED_BY_ID} this set was never fully inert — {@code CDISC-CG0334} is live
     * and kept the pin firing — which is precisely why the dead half was invisible.
     * </p>
     */
    private static final Set<String> CURATED_BY_LEAF = Set.of("CDISC-CG0334");

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

        assertTrue(r.filesIn() >= MIN_AUTHORED, "found only " + r.filesIn() + " rules-src checks");
        assertEquals(r.filesIn(), r.filesOut(), "every authored file must convert");
        assertEquals(List.of(), r.unraisable(), "an authored Check failed to raise");
        assertEquals(List.of(), r.genericResidue(),
                "an authored Check carries the retired generic exists/not_exists call — the"
                        + " loader rejects it; spell var_exists / ds_exists");
        assertEquals(PARKED, r.parked(), "the parked set moved");

        List<Path> files = sortedYamlFiles(checksRoot);
        assertEquals(r.filesIn(), files.size());
        int checked = 0;
        // ⛔ Non-vacuity counters for the two curated pins below, added 2026-09-19 (review round
        // 1) after all three CURATED_BY_ID keys had silently become dead ids and the byte-match
        // assertion fired for no rule. A Map.get() that returns null is not a failure — it is the
        // pin not existing — so the only way to know the pin ran is to count.
        int curatedHits = 0;
        int leafHits = 0;
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
                curatedHits++;
                assertEquals(curated, expression,
                        id + ": the written expression must byte-match the curated one");
            }
            if (CURATED_BY_LEAF.contains(id))
            {
                leafHits++;
                // The flip-day re-base of SpecialCaseLeafSignatureTest: the leaf-signature
                // rewrite's product (chars 5-6 of $dataset_name) is pinned as authored text —
                // the raw-leaf signature itself retired with the authored leaf corpus.
                assertEquals("RDOMAIN != substring($dataset_name, 5, 2)", expression,
                        id + ": the leaf-signature carrier's authored text moved");
            }
            checked++;
        }
        assertEquals(r.filesOut(), checked);
        assertEquals(CURATED_BY_ID.size(), curatedHits,
                "every CURATED_BY_ID key must name a rule the authored corpus still carries; "
                        + curatedHits + " of " + CURATED_BY_ID.size() + " were reached, so the"
                        + " byte-match pin above ran for fewer rules than it names. A key the"
                        + " corpus renamed or retired makes the pin inert WITHOUT failing — that is"
                        + " what happened to all three CORE-* keys in 2026-09. Re-point the key to"
                        + " the live carrier of the same text, or delete it deliberately.");
        assertEquals(CURATED_BY_LEAF.size(), leafHits,
                "every CURATED_BY_LEAF id must name a rule the authored corpus still carries; "
                        + leafHits + " of " + CURATED_BY_LEAF.size() + " were reached");

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
