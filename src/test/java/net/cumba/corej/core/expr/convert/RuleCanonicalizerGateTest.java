package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The gate {@link RuleCanonicalizer}'s own javadoc claimed to be and was not.
 *
 * <h2>⛔ Why this class exists</h2> Check mode was described as <em>"a Spotless-style CI gate"</em>,
 * but nothing in the build ran it: measured 2026-09-11, its only caller anywhere in the repository
 * was {@code scripts/backfill-rules.sh} stage 3. So <b>16 authored rules drifted out of canonical
 * form with nothing red</b> — 12 of them minted by the absent-column board itself, against a
 * documented baseline of zero — and one of them, {@code CDISC-CG0155}, carried a five-line
 * rationale that a corpus-wide run would have deleted <em>silently</em>. A guard that is documented
 * but not wired is not a guard; it is a claim.
 *
 * <p>
 * The whole pass costs ~1.3 s over 3 877 files including JVM start, so it rides the ordinary
 * surefire run rather than needing a pom binding.
 * </p>
 *
 * <h2>⚠⚠ The two floors, and why a bare {@code changed() == 0} would be worthless</h2>
 * <ul>
 * <li><b>The void-run trap</b>, which {@link RuleCanonicalizer}'s javadoc already documents: run
 * from the wrong working directory and it walks nothing, printing {@code files=0 non-canonical=0} —
 * indistinguishable from a clean corpus. The {@code files()} floor is what separates "checked
 * everything and found nothing" from "checked nothing".</li>
 * <li><b>The comment floor.</b> {@code comments()} is mostly a FALSE-POSITIVE counter: eight files
 * carry a {@code #} inside a quoted folded scalar and lose nothing, because {@code hasComment}
 * scans line by line and does not carry quote state across continuation lines. Eight is therefore
 * the all-false-positive floor, and a <b>rise above it means a real comment is about to be
 * eaten</b> — which is exactly what went unnoticed for {@code CDISC-CG0155}.</li>
 * </ul>
 */
class RuleCanonicalizerGateTest
{

    /** Corpus size at 2026-09-11; a floor, not a pin — the corpus grows. */
    // 3500 -> 3400 (PLAN-retire-core-family phase 6): rules-src/checks 3932 -> 3435. This was
    // the narrowest of the authored-corpus floors — 65 short, not the 86 the plan projected,
    // because phase 4 minted 21 rules after that projection was taken.
    private static final int MIN_FILES = 3400;

    /**
     * The all-false-positive level of {@code hasComment} at 2026-09-11: eight files whose {@code #}
     * sits inside a quoted value. ⛔ Raise this ONLY after diffing the new file and confirming it
     * loses nothing — a rise is the signal that a real comment is about to be deleted.
     */
    private static final int MAX_COMMENT_HITS = 8;

    /**
     * Every authored rule is a fixed point of the canonical writer.
     *
     * @throws IOException
     *             if the corpus cannot be read
     */
    @Test
    void theAuthoredCorpusIsCanonical() throws IOException
    {
        RuleCanonicalizer.Result r = RuleCanonicalizer.run(Path.of("rules-src", "checks"), true);

        assertTrue(r.files() >= MIN_FILES,
                "only " + r.files() + " rule file(s) were walked; " + MIN_FILES
                        + "+ existed 2026-09-11. This is the VOID RUN, not a clean corpus —"
                        + " fix the path, never the floor.");
        assertEquals(List.of(), r.failures(),
                "round-trip data-safety check failed — canonicalising these would change what the"
                        + " rule MEANS, not merely how it is spelled:\n" + r.failures());
        assertEquals(0, r.changed(),
                "rule file(s) are not canonical under RuleCanonicalizer. Run it without"
                        + " -Dcanonicalize.check to fix. ⚠ Check the comment count below first: a"
                        + " fix-up run DELETES YAML comments.");
        assertTrue(r.comments() <= MAX_COMMENT_HITS,
                "hasComment now trips on " + r.comments() + " file(s), above the all-false-positive"
                        + " floor of " + MAX_COMMENT_HITS + ". A REAL YAML comment has been added"
                        + " and a canonicalisation would delete it silently. Diff the new file:"
                        + " if its # is inside a quoted value, raise the floor; if it is a real"
                        + " comment, move the text to the guidelines or a scenario #note, where"
                        + " canonicalisation cannot reach it.");
    }

}
