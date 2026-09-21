package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Corpus presence floor for {@code num(} — the INVERSION of the ratchet this class was born as, and
 * the class name is kept because three historical documents cite it by name
 * ({@code expression-docs-disposition.md}, the archived rule review
 * {@code core-engine-update-status.md}, the R3 handover) and history cites history.
 *
 * <p>
 * <b>The recorded decision this class used to pin is re-decided.</b> Until 2026-09-13 this test
 * asserted {@code num(} was ABSENT from the shipped corpus: the {@code Fix #149} lowering asymmetry
 * (the expr→legacy-leaf lowering dropped a {@code num()} tag that the native evaluator honoured)
 * was accepted only because no shipped rule authored the idiom, and the zero here was the proof
 * that the blast radius stayed nil.
 * </p>
 *
 * <p>
 * {@code PLAN-column-type-conformance} (owner rulings R2/R3/R10, 2026-09-13) re-decided exactly
 * that question, the way this class's own failure message demanded: {@code num()} is now a
 * <em>conversion function</em>, not a tag — honoured identically by {@code ExprCompiler} and
 * {@code ExprLowering} (the §10 F7/F8 design keeps every routing set intact and mirrors the value
 * plan into lowering, so the two engines cannot drift) — and R3 makes {@code num(X)} the REQUIRED
 * authoring for every numeric read of a character column. The Phase 6 corpus migration authored it
 * into the TSVAL / PCORRES / --STRESC / --TOXGR / --ORNR* families. Absence is no longer the
 * invariant; presence is.
 * </p>
 *
 * <p>
 * So the ratchet inverts: the same raw-substring scan now asserts {@code num(} is PRESENT, with a
 * floor sized like every other floor here — on the smallest shipped corpus, the one WITHOUT the
 * CORE family (measured 2026-09-13: 63 without CORE, 98 with). A corpus that lost its {@code num(}
 * conversions would mean the Phase 6 migration was reverted — silently un-authoring the ruled
 * spelling — and that must red the build exactly as loudly as authoring it used to.
 * </p>
 *
 * <p>
 * ⚠⚠ <strong>A floor is vacuous without a positive control.</strong> A scan that looks in the wrong
 * directory, or reads nothing, returns zero as happily as an empty corpus does — and a floor of 63
 * against zero would fail, but the CONTROLS failing first is what tells a broken scan from a
 * regressed corpus. This test therefore runs the <em>identical</em> scan for the other tags and
 * pins their floors, unchanged from the ratchet era.
 * </p>
 */
class ShippedNumTagAbsentRatchetTest
{

    /** The shipped legacy-format packages. Their expression strings are what carries the tags. */
    private static final Path RULES = CorpusPaths.rules();

    /**
     * The conversion under floor. Deliberately a <strong>raw substring</strong>, matching
     * {@code grep -ooF}, not a token-aware search: one scan for the floor and the controls alike.
     * ({@code is_numeric(} cannot collide — its {@code num} is followed by {@code e}.)
     */
    private static final String CONVERSION = "num(";

    /**
     * Floor for {@code num(}, sized on the smallest shipped corpus (without the CORE family).
     * Measured 2026-09-13 after the Phase 6 migration: {@code grep -rooF 'num(' rules/ | wc -l} →
     * 98 on the internal corpus, 63 excluding {@code rules-core-*.json}. Re-measure, never
     * arithmetic, before moving it.
     * <p>
     * &#11088; <b>63 &rarr; 83, re-derived 2026-09-19</b> by {@code PLAN-retire-core-family.md}
     * phase 8f, which found it from the OSS side. The 63 was <i>"the corpus without the CORE
     * family"</i> sized while this corpus still HAD that family; retiring it makes the exclusion
     * vacuous, so the floor should have moved with the deletion and did not &mdash; &#167;13's five
     * greps do see this constant, but a floor that is merely SLACK reds nothing, so nothing asked.
     * Measured 83 here and 83 in {@code cumba-oss-corej-rules}, with the documented command against
     * each {@code target/rules}; the OSS side read the same 83 back out of this assertion's own
     * failure message under a deliberately impossible floor.
     * </p>
     */
    private static final int CONVERSION_FLOOR = 83;

    /**
     * Tags that are present, with floors re-sized 2026-08-31 on the smallest shipped corpus — the
     * one WITHOUT the CORE family, which the OSS distribution ships (66 / 36 / 785 / 222 / 8). A
     * tree that also ships CORE simply exceeds them. Floors keep the control's purpose — a scan
     * that reads nothing, or looks in the wrong directory, still fails — while letting one baseline
     * serve every distribution. (The measurement history of these numbers is in this file's git
     * history and the archived rule review {@code core-engine-update-status.md}.)
     */
    private static final Map<String, Integer> PRESENT = Map.of("prefix(", 66, "suffix(", 36,
            "date(", 785, "date_part(", 222, "time_part(", 8);

    /** Every file under {@code rules/}, read as one string each. */
    private static List<String> corpus() throws IOException
    {
        try (Stream<Path> files = Files.walk(RULES))
        {
            List<Path> paths = files.filter(Files::isRegularFile).sorted().toList();
            List<String> contents = new ArrayList<>(paths.size());
            for (Path path : paths)
            {
                contents.add(Files.readString(path, StandardCharsets.UTF_8));
            }
            return contents;
        }
    }


    /** Non-overlapping occurrences of {@code needle}, the same count {@code grep -ooF} reports. */
    private static int occurrences(List<String> corpus, String needle)
    {
        int total = 0;
        for (String content : corpus)
        {
            int at = content.indexOf(needle);
            while (at >= 0)
            {
                total++;
                at = content.indexOf(needle, at + needle.length());
            }
        }
        return total;
    }


    /**
     * The control half, asserted <em>first</em> so a broken scan can never masquerade as a clean
     * corpus. If any of these moves, the floor below is not to be trusted — re-measure with
     * {@code grep -rooF '<tag>' rules/ | wc -l} before touching it.
     */
    @Test
    void theScanFindsTheTagsThatArePresent() throws IOException
    {
        List<String> corpus = corpus();
        assertTrue(corpus.size() >= 45, "the shipped corpus must actually have been read; found "
                + corpus.size() + " files under " + RULES.toAbsolutePath());

        Map<String, Integer> actual = new TreeMap<>();
        PRESENT.keySet().forEach(tag -> actual.put(tag, occurrences(corpus, tag)));
        PRESENT.forEach((tag, floor) -> assertTrue(actual.get(tag) >= floor.intValue(),
                "positive control: these tags are present in rules/ and the scan must find them —"
                        + " `" + tag + "` seen " + actual.get(tag) + " time(s), floor " + floor
                        + "; saw " + actual));
    }


    /**
     * The floor itself. Presence is a recorded *decision* (see the class javadoc), not an invariant
     * of the engine — if this fires, the corpus has LOST the {@code num()} conversions that rulings
     * R3/R10 made the required authoring for numeric reads of character columns (TSVAL, PCORRES,
     * {@code --STRESC}, {@code --TOXGR}, {@code --ORNR*}), which is the shape of a reverted or
     * re-generated-from-stale-source Phase 6 migration.
     */
    @Test
    void theShippedCorpusAuthorsTheNumConversion() throws IOException
    {
        List<String> corpus = corpus();
        // Re-assert the control in this test too: read together, the pair is self-validating.
        assertTrue(occurrences(corpus, "prefix(") > 0, "control: the scan must find prefix(");

        int seen = occurrences(corpus, CONVERSION);
        assertTrue(seen >= CONVERSION_FLOOR,
                "`num(` seen " + seen + " time(s), floor " + CONVERSION_FLOOR + ". The corpus has"
                        + " lost the num() conversions that PLAN-column-type-conformance R3/R10"
                        + " made the required authoring for a numeric read of a character column."
                        + " Under the Phase 3 type gate those rules now ERROR instead of running,"
                        + " so this is a regression, not a cleanup. Re-measure with"
                        + " `grep -rooF 'num(' rules/ | wc -l` and re-read the plan before"
                        + " touching this floor.");
    }
}
