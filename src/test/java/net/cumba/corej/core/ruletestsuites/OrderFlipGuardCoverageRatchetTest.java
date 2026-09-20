package net.cumba.corej.core.ruletestsuites;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * ⭐ The order-flip guard-coverage ratchet (H2, terminal review of
 * {@code PLAN-typed-expression-engine}).
 *
 * <p>
 * Phase 3c added 28 {@code not empty(<low operand>)} guard sites across 26 rules so that phase 6c's
 * total order semantics (D34 #5 positions a MISSING below every value) cannot turn a missing
 * reference operand into a false positive on every row. The classification lives in the meta repo —
 * {@code plans/findings/REVIEW1-order-flip.tsv} and {@code REVIEW1B-date-order-flip.tsv}
 * (dispositions {@code GUARD}) — and until this ratchet NOTHING keyed on it: deleting a guard
 * scenario (or never having one) left every test green.
 * </p>
 *
 * <p>
 * Each rule below owns at least the stated number of {@code <RULE>-guard_*.cdt} scenarios under its
 * family subtree, each declaring {@code expect=noViolation}: a fixture on which the guarded low
 * operand is GENUINELY missing, silent only because the guard fires. The negative controls
 * (2026-09-17, run 1) measured all 26 individually-deletable guards RED under source-level guard
 * deletion; the two {@code SVSTDTC} hull sites of {@code CDISC-CG0218} are provably redundant by
 * implication (their sibling window conjunct is false whenever their operand is missing) and carry
 * a scenario but cannot be redded by single deletion — the scenario notes prove it.
 * </p>
 *
 * <p>
 * ⭐⭐ <b>26 rules / 28 sites → 22 rules / 23 sites, 2026-09-19 (CORE-family retirement, §12.4(a)),
 * and the DROP-vs-RE-CITE question was SETTLED BY MEASUREMENT rather than assumed.</b> The plan
 * left it open: a re-cite would have kept the count at 28 and kept the classification exercised, a
 * drop loses it. The settling step it named is *"does the twin already carry the same
 * {@code <RULE>-guard_*.cdt} scenario?"*, and the answer is that <b>every twin that does is ALREADY
 * a separate roster entry of its own</b>:
 * </p>
 *
 * <ul>
 * <li>{@code CORE-000717} → {@code FDA-SD0080}, which already carries
 * {@code FDA-SD0080-guard_all_dsstdtc_blank-AE.cdt} and is already rostered (so does
 * {@code PMDA-SD0080});</li>
 * <li>{@code CORE-000250} → {@code CDISC-CG0078}, already carrying
 * {@code CDISC-CG0078-guard_blank_rfstdtc-MH.cdt} and already rostered;</li>
 * <li>{@code CORE-000097} (2 sites) → {@code CDISC-CG0218}, already carrying <em>both</em>
 * {@code -guard_blank_sestdtc-SV} and {@code -guard_blank_svstdtc-SV} and already rostered <em>with
 * the count 2</em>;</li>
 * <li>{@code CORE-000310} → ⛔ <b>no twin exists.</b> Its TSV row names {@code FDA-SD0084} as the
 * already-guarded relative, and measured that rule's Check is {@code not empty(AGE) and AGE <= 0} —
 * guarded <em>by construction</em>, with no order-flip to guard and therefore no
 * {@code -guard_*.cdt} of its own. Rostering it would demand a scenario it does not need, and a
 * rostered rule with no scenario REDS.</li>
 * </ul>
 *
 * <p>
 * ⇒ A re-cite would have been a {@code Map.ofEntries} duplicate key in three of the four cases and
 * an unsatisfiable demand in the fourth. <b>These are four genuine coverage losses, not a
 * bookkeeping move</b>, and the register of what went is this paragraph plus the five deleted
 * {@code GUARD} rows in the meta repo's {@code REVIEW1-order-flip.tsv:2} and
 * {@code REVIEW1B-date-order-flip.tsv:19,20,24,28}.
 * </p>
 *
 * <p>
 * ⚠ This roster is a transcription of the two TSVs' GUARD rows (they live in the meta repo, outside
 * this repo's checkout, so they cannot be read here). If a guard disposition is added or retired
 * there, update BOTH: the TSV and this roster. ⭐ The transcription itself is gated from the side
 * that CAN see both files: {@code check_guard_roster.py} in the meta repo diffs the TSVs' GUARD
 * rows against the {@code GUARDED} literal below, so a new GUARD disposition that never reaches
 * this roster now reds there instead of reddening nowhere (review round 2, R2-11).
 * </p>
 *
 * <h2>⚠ Counting the FILE is not counting the SCENARIO (review round 2, R2-10)</h2>
 *
 * <p>
 * A {@code .cdt} without the {@code #!RuleTest} shebang is SILENTLY skipped by
 * {@code AbstractRuleTestSuitesFactory.scenarios()} — its own javadoc says so ("plain fixtures (no
 * shebang) are silently skipped"). A ratchet that matched on filename alone therefore counted files
 * the suite never executes: strip line 1 of a guard fixture and the guard goes unexercised — the
 * exact state H2 was raised to end — with every gate green. So each file is checked to be a
 * scenario the factory will actually pick up, through {@link RuleTestCdt#hasShebang} itself rather
 * than a local re-implementation of it, and {@code expect=noViolation} is matched as a token of the
 * {@code #test} DIRECTIVE for this rule — a substring search also accepted the word inside a
 * {@code #note}.
 * </p>
 *
 * <p>
 * ⚠ That sentence was only half true until review round 3 (H-5): this ratchet did call
 * {@code RuleTestCdt#hasShebang}, but {@code AbstractRuleTestSuitesFactory} filtered with a private
 * {@code BufferedReader} re-implementation of its own, so there were two predicates and the ratchet
 * used the one the factory did not. The factory now delegates, so there is genuinely one.
 * </p>
 */
class OrderFlipGuardCoverageRatchetTest
{

    private static final Path RESOURCE_BASE = Path.of(System.getProperty("projectBasedir"),
            "src/test/resources/net/cumba/corej/core/ruletestsuites");

    /** family/RULE → required number of guard scenarios (sites with distinct fixtures). */
    private static final Map<String, Integer> GUARDED = new TreeMap<>(Map.ofEntries(
            // REVIEW1B shape 1 — OPVAR extremes ($max_*/$exp_end), 12 sites (was 13:
            // CORE-000717 went with the family)
            Map.entry("fda/FDA-SD0080", 1), Map.entry("pmda/PMDA-SD0080", 1),
            Map.entry("fda/FDA-SD0082", 1), Map.entry("pmda/PMDA-SD0082", 1),
            Map.entry("fda/FDA-SD1446", 1), Map.entry("pmda/PMDA-SD1446", 1),
            Map.entry("fda/FDA-SE2240", 1), Map.entry("fda/FDA-SE2241", 1),
            Map.entry("fda/FDA-SE2351", 1), Map.entry("fda/FDA-SE1149", 1),
            Map.entry("fda/FDA-SE1151", 1), Map.entry("fda/FDA-SE0083", 1),
            // REVIEW1B shape 2 — hull bounds (earliest_possible), 4 sites (was 7: CORE-000250 ×1
            // and CORE-000097 ×2 went with the family)
            Map.entry("cdisc/CDISC-CG0078", 1), Map.entry("cdisc/CDISC-CG0218", 2),
            Map.entry("cdisc/CDISC-SEND-0283", 1),
            // REVIEW1 shape 3 — blank numeric column, 3 sites (was 4: CORE-000310 went)
            Map.entry("cdisc/CDISC-AD0361", 1), Map.entry("cdisc/CDISC-AD0362", 1),
            Map.entry("cdisc/CDISC-SEND-0324-A", 1),
            // REVIEW1 shape 4 — num(), 2 sites
            Map.entry("cdisc/CDISC-SEND-0324", 1), Map.entry("cdisc/CDISC-SEND-0194", 1),
            // REVIEW1 shape 5 — vlm_length, 2 sites
            Map.entry("fda/FDA-SD1231", 1), Map.entry("pmda/PMDA-SD1231", 1)));

    @Test
    void everyGuardedRuleKeepsItsGuardScenarios() throws IOException
    {
        List<String> problems = new ArrayList<>();
        int found = 0;
        for (Map.Entry<String, Integer> e : GUARDED.entrySet())
        {
            String ruleId = e.getKey().substring(e.getKey().indexOf('/') + 1);
            Path dir = RESOURCE_BASE.resolve(e.getKey());
            List<Path> guards = new ArrayList<>();
            if (Files.isDirectory(dir))
            {
                // ⚠ "-A-" trap: CDISC-SEND-0324's glob must not swallow CDISC-SEND-0324-A's
                // scenarios, so match on the exact "<RULE>-guard_" prefix.
                try (Stream<Path> s = Files.list(dir))
                {
                    s.filter(p -> p.getFileName().toString().startsWith(ruleId + "-guard_")
                            && p.getFileName().toString().endsWith(".cdt")).forEach(guards::add);
                }
            }
            if (guards.size() < e.getValue())
            {
                problems.add(e.getKey() + ": " + guards.size() + " guard scenario(s), needs "
                        + e.getValue());
            }
            found += guards.size();
            for (Path g : guards)
            {
                String text = Files.readString(g);
                if (!RuleTestCdt.hasShebang(text))
                {
                    problems.add(g.getFileName() + " has no " + RuleTestCdt.SHEBANG
                            + " shebang — AbstractRuleTestSuitesFactory SILENTLY skips it, so the "
                            + "guard is unexercised while this ratchet still counts the file");
                }
                if (!declaresNoViolationTest(text, ruleId))
                {
                    problems.add(g.getFileName() + " has no '#test " + ruleId
                            + " … expect=noViolation' directive — a guard scenario asserts "
                            + "SILENCE on a missing low operand");
                }
            }
        }
        Assertions.assertEquals(List.of(), problems,
                "order-flip guard coverage regressed (WORKORDER-H2-guard-coverage.md; "
                        + "REVIEW1-order-flip.tsv / REVIEW1B-date-order-flip.tsv in the meta "
                        + "repo's plans/findings/)");
        // Self-reach, and EXACT on purpose: 22 rules, 23 GUARD rows in the TSVs, 23 scenarios
        // (26 / 28 / 28 until the 2026-09-19 CORE-family retirement — see the class javadoc).
        // The count is not a floor — it is the third leg of the transcription, so an extra
        // scenario is a red on purpose and bumping the number alone is the WRONG repair. Adding
        // a 29th means a 29th GUARD row exists: add it to the TSV and to GUARDED above, raise
        // this number to match, and re-run the meta repo's check_guard_roster.py. Loosening this
        // to `>=` would let the roster and the classification drift apart silently, which is the
        // one thing the ratchet is here to stop.
        Assertions.assertEquals(23, found,
                "expected exactly the 23 authored guard scenarios — this number, the GUARDED "
                        + "roster and the GUARD rows of REVIEW1-order-flip.tsv / "
                        + "REVIEW1B-date-order-flip.tsv (meta repo, plans/findings/) are ONE "
                        + "classification in three places; the meta repo's check_guard_roster.py "
                        + "diffs the first two. Do not just bump the number");
        Assertions.assertEquals(22, GUARDED.size(),
                "roster must carry the 22 guarded rules of the two REVIEW1*.tsv GUARD "
                        + "dispositions (meta repo, plans/findings/)");
    }


    /**
     * {@code true} when the file carries a {@code #test} DIRECTIVE for {@code ruleId} whose options
     * include {@code expect=noViolation}. Token-matched, not substring-matched: the word also
     * occurs in the {@code #note} prose of every one of these fixtures, so
     * {@code text.contains(...)} was satisfied by a file whose actual expectation was
     * {@code violation}.
     *
     * @param text
     *            the scenario's full text
     * @param ruleId
     *            the rule the directive must name
     * @return whether such a directive is present
     */
    private static boolean declaresNoViolationTest(String text, String ruleId)
    {
        for (String line : text.split("\\R", -1))
        {
            // split(…, -1) per [StringSplitter]; behaviour-identical here because the input is
            // trimmed, so no leading or trailing separator can produce an empty field.
            String[] tok = line.trim().split("\\s+", -1);
            if (tok.length < 3 || !"#test".equals(tok[0]) || !ruleId.equals(tok[1]))
            {
                continue;
            }
            for (int i = 2; i < tok.length; i++)
            {
                if ("expect=noViolation".equals(tok[i]))
                {
                    return true;
                }
            }
        }
        return false;
    }
}
