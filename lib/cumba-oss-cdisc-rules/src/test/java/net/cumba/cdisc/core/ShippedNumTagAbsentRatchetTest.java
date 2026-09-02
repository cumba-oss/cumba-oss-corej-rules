package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Ratchet for the {@code Fix #149} lowering asymmetry. The decision it pins was recorded in the
 * retired {@code CORE-RULES-JAVA-EXTENSIONS.md} §28; that document was dissolved into the two
 * specifications and {@code documentation/expression-docs-disposition.md} §A is the index of where
 * each entry went (§28 → the {@code num()} marker, check-spec §4).
 *
 * <p>
 * The native evaluator honours a {@code num()} tag on an affix comparison; the expr→legacy-leaf
 * lowering silently drops it, because {@code ExprLowering.UNARY_WRAPPERS} contains {@code num} and
 * {@code unwrap} strips it before the operand reaches the leaf. Two coreJ surfaces therefore
 * disagree on {@code prefix(X, 2) != num(Y)}. That was <strong>accepted</strong>, on the ground
 * that the blast radius is nil and latent: no shipped rule authors the idiom.
 * </p>
 *
 * <p>
 * "No shipped rule authors it" is an assertion about the corpus, and assertions about the corpus
 * rot. This test pins it so the day someone authors the idiom the build says so, and §28 gets
 * re-decided rather than rediscovered.
 * </p>
 *
 * <p>
 * ⚠⚠ <strong>A zero-assertion is vacuous without a positive control.</strong> A scan that looks in
 * the wrong directory, or reads nothing, or matches nothing, returns zero exactly as happily as a
 * clean corpus does — so a bare "{@code num(} is absent" proves nothing at all. This test therefore
 * runs the <em>identical</em> scan for the tags that <em>are</em> present and pins their counts.
 * Break the scan and the controls fail first.
 * </p>
 */
class ShippedNumTagAbsentRatchetTest
{

    /** The shipped legacy-format packages. Their expression strings are what carries the tags. */
    private static final Path RULES = CorpusPaths.rules();

    /**
     * The tag under ratchet. Deliberately a <strong>raw substring</strong>, matching
     * {@code grep -ooF}, not a token-aware search: an over-strict ratchet (one that would also fire
     * on a hypothetical {@code enum(}) is safe, and using one scan for the ratchet and a different
     * one for the controls would defeat the point of having controls.
     */
    private static final String RATCHET = "num(";

    /**
     * Tags that are present, with the counts re-measured on 2026-08-07 (wave 9, the combined corpus
     * lane). Substring counts, so {@code date(} legitimately includes {@code max_date(} and
     * {@code is_complete_date(} — the control's job is to prove the scan finds text, not to be a
     * semantic census.
     * <p>
     * Movement since the 2026-08-05 measurement, all of it accounted for by that lane:
     * {@code date(} 1292 → 1119 and {@code date_part(} 8 → 157 because the 19
     * {@code is_incomplete_date} rules were rewritten onto {@code is_not_complete_date_part}
     * ({@code Fix #157}'s operator), which lowers to {@code not is_complete_date_part(X)};
     * {@code prefix(} 77 → 111 because the five {@code prefix: 2} rules gained an AP-scoped
     * {@code prefix: 4} sibling. {@code suffix(} and {@code time_part(} are untouched.
     * </p>
     * <p>
     * Wave 37 (lane A, {@code Fix #239}): {@code date_part(} 157 → 198. The nine minted
     * completeness siblings ({@code -A} suffix, one per parent family across the four {@code TS}
     * date parameters) are the corpus's only new {@code is_not_complete_date_part} leaves, and they
     * reach 41 package entries. Re-derived by run and reported by the lane; applied here by the
     * coordinator, per the standing rule that a ratchet is <em>measured</em>, never bumped by
     * arithmetic — the measured value and the lane's prediction agreed exactly.
     * </p>
     * <p>
     * Wave TA-4 (CL-C, 2026-08-18): {@code prefix(} 111 → 114. CORE-000761's dead
     * {@code TSVCDREF == "CDISC"} literal became the {@code CDISC-CG0289} prefix mechanism
     * {@code prefix(TSVCDREF, 5) == "CDISC"}; the one authored leaf fans out into the three
     * {@code rules-core-sdtmig-*.json} packages. Re-measured by run (the control's own failure
     * message), not bumped by arithmetic — measurement and prediction agreed exactly.
     * </p>
     * <p>
     * 2026-08-21 (decision-lane apply session): {@code date(} 1119 → 989 and {@code date_part(} 198
     * → 279. Three causes, all accounted for: {@code Fix #303} ({@code M2-P2}) moved six study-day
     * rules from {@code is_complete_date} onto {@code is_complete_date_part} — 41 package entries ×
     * 2 leaves = −82 / +82; the owner's retirement of the {@code DRAFT-1100xx} /
     * {@code DRAFT-900020} rules removed 50 {@code date(} leaves; {@code Fix #313} (membership
     * re-derivation) moved {@code CDISC-SEND-0248.1} into three more packages (+3 {@code date(})
     * and {@code CORE-000505} / its {@code -A} sibling out of one each (−1 / −1). Re-measured by
     * run ({@code grep -rooF}) by the coordinator; 1119 − 82 − 50 + 3 − 1 = 989 and 198 + 82 − 1 =
     * 279 — measurement and the lanes' predictions agreed exactly.
     * </p>
     * <p>
     * 2026-08-26 (plan C phase 5b): {@code date(} 989 → 1043. The nine date-ordering rules
     * rewritten to a two-level {@code Check} (ruling (a)) each add an {@code INFO} leg whose
     * comparison is {@code date()}-tagged, and four of them ({@code CDISC-CG0233},
     * {@code CDISC-CG0235}, {@code CORE-000572}, {@code CORE-000710}) also duplicate an
     * {@code is_complete_date(} guard into that leg (substring-counted as {@code date(}). Summed
     * over every package entry the nine rules ship in: +54. Re-measured by
     * {@code grep -rooF 'date(' rules/} — 1043 exactly; the other four tags did not move.
     * </p>
     * <p>
     * 2026-08-31: these became <b>FLOORS, not equalities</b>, and were re-sized on the smallest
     * shipped corpus — the one WITHOUT the CORE family, which the OSS distribution ships (66 / 36 /
     * 785 / 222 / 8). A tree that also ships CORE simply exceeds them (114 / 52 / 1043 / 279 / 8,
     * the previous equality values). Floors keep the control's purpose — a scan that reads nothing,
     * or looks in the wrong directory, still fails — while letting one baseline serve every
     * distribution.
     * </p>
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
     * corpus. If any of these moves, the ratchet below is not to be trusted — re-measure with
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
     * The ratchet itself. Zero is a recorded *decision* (see the class javadoc), not an invariant
     * of the engine — if this fires, a rule now authors the idiom and the accepted asymmetry has a
     * live blast radius.
     */
    @Test
    void noShippedRuleAuthorsTheNumTagIdiom() throws IOException
    {
        List<String> corpus = corpus();
        // Re-assert the control in this test too: read together, the pair is self-validating.
        assertTrue(occurrences(corpus, "prefix(") > 0, "control: the scan must find prefix(");

        assertEquals(0, occurrences(corpus, RATCHET),
                "A shipped rule now authors a `num()` tag. Fix #149's recorded decision"
                        + " accepts that the native evaluator honours it while"
                        + " ExprLowering silently drops it on the `prefix(X,2) != num(Y)` route —"
                        + " accepted only because the blast radius was nil. It no longer is."
                        + " Re-decide §28 before re-pinning this number.");
    }
}
