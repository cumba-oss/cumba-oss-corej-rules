package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * D3 of {@code plans/PLAN-unplanned-visit-cascade.md} (owner, 2026-09-18): the two unplanned-visit
 * name literals stay <b>inline in every {@code Check}</b> — no named constant, no shared
 * {@code Bindings} entry — and every copy is <b>byte-identical</b>. With the cascade the literals
 * appear in ~28 files instead of 10, so drift between copies is now a real hazard; this lint pins
 * the spelling.
 *
 * <p>
 * The canonical spelling is {@code contains(upper(VISIT), "UNSCHEDUL")} /
 * {@code contains(upper(VISIT), "UNPLAN")} — <b>without</b> an inner {@code upper()} on the
 * constant. ⚠ That is ruling <b>Q-C</b> (2026-09-18), which OVERRULED the plan's own recommendation
 * to keep the corpus's historical {@code upper("UNSCHEDUL")} spelling: {@code upper()} of a string
 * constant is the constant, so the inner call was noise. This test pins the <b>absence</b> of the
 * inner {@code upper()} — a lint written to the superseded recommendation would red the whole
 * corpus.
 * </p>
 *
 * <p>
 * ⚠ Scoped to the {@code UNSCHEDUL}-carrying population on purpose: 41 further Checks use the
 * <em>exact string</em> {@code "UNPLAN"} as the controlled-terminology <b>ETCD/element value</b>
 * (D5's trial-element family — {@code ETCD == "UNPLAN"} and kin). Those key on CT, not on a visit
 * NAME substring, are internally consistent, and are explicitly out of the cascade's scope; only
 * files whose Check tests the visit name (recognisable by {@code UNSCHEDUL}, which no CT value
 * uses) are held to the canonical call.
 * </p>
 *
 * <p>
 * Reads the <b>authored</b> view ({@code rules-src/checks}), never the shipped {@code rules/} view
 * — the invariant is about what an author typed. Population floor per the house pattern
 * ({@code GuardWhitelistGateTest.ROW_FLOOR}): at least {@link #FILE_FLOOR} files must carry the
 * literals, so a broken walk or a mass migration into {@code Bindings} cannot pass vacuously.
 * </p>
 */
class UnplannedVisitLiteralInvariantTest
{

    /** The authored view. Never {@code CorpusPaths.rules()} — see the class javadoc. */
    private static final Path CHECKS_DIR = Path.of("rules-src", "checks");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final String CANON_UNSCHEDUL = "contains(upper(VISIT), \"UNSCHEDUL\")";

    private static final String CANON_UNPLAN = "contains(upper(VISIT), \"UNPLAN\")";

    /**
     * ⭐⭐ THE SECOND CANONICAL SPELLING, for the <b>joined</b> form only
     * (PLAN-unplanned-visit-name-fallback, 2026-09-22). A rule whose primary is NOT {@code SV}
     * reads the visit name from a column the primary may not carry — it is {@code Perm} in every
     * domain — and an absent char column folds to {@code ""} (D34 #3), so the name test used to see
     * a neutral name and judge the visit PLANNED <b>even when {@code SV} carried the name and it
     * said UNSCHEDULED</b>. Since {@code TV} holds only PLANNED visits, an unscheduled visit is
     * never in it, so that misjudgement reports a finding on every such visit.
     *
     * <p>
     * ⛔ {@code coalesce} cannot express the fallback: it returns the first <b>non-missing</b>
     * operand and the fold is {@code ""} — present, not missing — so it short-circuits. {@code
     * concat} works because {@code contains} distributes over concatenation and a {@code ""}
     * operand contributes nothing. ⚠ The {@code " "} separator is load-bearing: without it a marker
     * could be spelled across the seam ({@code "UNS"} + {@code "CHEDULED"}). Neither literal
     * contains a space.
     * </p>
     *
     * <p>
     * ⚠⚠ Which spelling a file must use is decided by <b>join shape, not by family</b>: a rule
     * scoped {@code Domains.Include: ["SV"]} has {@code SV} as its primary, so its bare
     * {@code VISIT} is already SV's own column and the joined spelling would be wrong there.
     * </p>
     */
    private static final String CANON_UNSCHEDUL_JOINED = "contains(upper(concat(VISIT, \" \","
            + " SV.VISIT)), \"UNSCHEDUL\")";

    /** @see #CANON_UNSCHEDUL_JOINED */
    private static final String CANON_UNPLAN_JOINED = "contains(upper(concat(VISIT, \" \","
            + " SV.VISIT)), \"UNPLAN\")";

    /**
     * ⛔ Non-vacuity floor for the joined spelling, and it is an EXACT count, not slack. Exactly one
     * rule carries it today — {@code CDISC-CG0033}, the only joined carrier that does not declare
     * {@code VISIT} (the other three — {@code CG0031}, {@code FDA}/{@code PMDA-SD1023} — have
     * {@code VISIT} in their subject, declare it and correctly SKIP when it is absent, per ruling
     * {@code VA1}). Without this floor the joined branch could stop matching anything and the test
     * would still be green, which is the failure mode the whole class exists to prevent.
     */
    private static final int JOINED_FLOOR = 1;

    /**
     * Measured 2026-09-18 at the cascade's landing: 28 files — the 16 SV-scoped rules, the 4 joined
     * CDISC/CORE rules, FDA/PMDA SD1017/SD1018/SD1019 and FDA/PMDA SD1023. A floor, not an
     * equality: a future family may adopt the cascade, but a single file quietly moving its literal
     * into a {@code Bindings} entry (against D3) already reds this.
     */
    // 28 -> 18 (PLAN-retire-core-family phase 6), measured: the population WAS exactly 28 and
    // is now exactly 18, so this floor had ZERO headroom. ⭐ The javadoc above is NOT wrong and
    // must not be "corrected": it decomposes 28 as 16 SV-scoped + the 4 joined + SD1017/1018/1019
    // x2 + SD1023 x2 and never attributes the 4 to CORE. The measured family split was
    // CDISC 10 · CORE 10 · FDA 4 · PMDA 4 (the "4 joined" being CDISC-CG0031, CG0033,
    // CORE-000269, CORE-000270; the 16 being 8 CDISC + 8 CORE) — that attribution is what was
    // missing, not the number.
    private static final int FILE_FLOOR = 18;

    @Test
    void everyVisitNameLiteralIsTheCanonicalCallAndNothingElse() throws IOException
    {
        Map<String, String> population = new TreeMap<>();
        List<String> joined = new ArrayList<>();
        List<String> offences = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(CHECKS_DIR))
        {
            for (Path p : walk.filter(f -> f.toString().endsWith(".yaml")).sorted().toList())
            {
                Map<?, ?> doc = YAML.readValue(p.toFile(), Map.class);
                Object check = doc.get("Check");
                if (!(check instanceof Map<?, ?> c) || !(c.get("expression") instanceof String e))
                {
                    continue;
                }
                // Q-C absence pin, corpus-wide: the inner upper() on a constant is retired.
                if (e.contains("upper(\"UNSCHEDUL") || e.contains("upper(\"UNPLAN"))
                {
                    offences.add(p + ": inner upper() on a name constant (retired by Q-C)");
                }
                if (!e.contains("UNSCHEDUL"))
                {
                    continue; // the ETCD/element "UNPLAN" CT family — out of scope (D5).
                }
                population.put(p.toString(), e);
                // Strip every canonical call; any surviving trace of either literal is a
                // divergent spelling (case-flip, whitespace, wrong column, inner upper, a
                // Bindings-bound remnant re-inlined wrong, ...).
                // ⛔ ORDER MATTERS: strip the JOINED spellings FIRST. They contain the bare
                // `VISIT` token but not the bare canonical CALL, so stripping is unambiguous —
                // but stripping in the other order would leave `contains(upper(concat(` debris
                // that no later replace removes, and the residue test below would red every
                // joined file. Measured while landing CDISC-CG0033.
                String residue = e.replace(CANON_UNSCHEDUL_JOINED, "")
                        .replace(CANON_UNPLAN_JOINED, "").replace(CANON_UNSCHEDUL, "")
                        .replace(CANON_UNPLAN, "");
                if (e.contains(CANON_UNSCHEDUL_JOINED))
                {
                    joined.add(p.toString());
                }
                if (residue.contains("UNSCHEDUL") || residue.contains("\"UNPLAN\""))
                {
                    offences.add(p + ": non-canonical literal spelling; Check after stripping the"
                            + " canonical calls still carries a name literal: " + residue);
                }
                // ⚑ The twin check is per-spelling: a file using the joined UNSCHEDUL call
                // owes the joined UNPLAN call, never the bare one.
                if (e.contains(CANON_UNSCHEDUL_JOINED) ? !e.contains(CANON_UNPLAN_JOINED)
                        : !e.contains(CANON_UNPLAN))
                {
                    offences.add(p + ": carries the UNSCHEDUL test but not the canonical UNPLAN"
                            + " twin — the two literals travel together in the cascade");
                }
            }
        }
        assertEquals(List.of(), offences,
                "every copy of the unplanned-visit name test must be byte-identical to '"
                        + CANON_UNSCHEDUL + "' / '" + CANON_UNPLAN + "' — or, for a rule whose"
                        + " primary is not SV, to '" + CANON_UNSCHEDUL_JOINED + "' / '"
                        + CANON_UNPLAN_JOINED + "' (D3 + Q-C + the 2026-09-22 joined variant)");
        // ⛔ The joined branch's own non-vacuity floor. Without it, a typo in
        // CANON_*_JOINED makes the branch match nothing: `joined` stays empty, the residue test
        // stops seeing those calls as canonical and reds — but if the rule ALSO reverted, both
        // go quiet together and the test is green over a corpus that lost the fallback. The
        // floor is what makes the second spelling a checked claim rather than a dead constant.
        assertTrue(joined.size() >= JOINED_FLOOR,
                "only " + joined.size() + " authored Check(s) carry the JOINED visit-name spelling"
                        + " (floor " + JOINED_FLOOR + "); CDISC-CG0033 carries it today. Either the"
                        + " constant no longer matches what is authored, or the fallback was"
                        + " reverted — see plans/PLAN-unplanned-visit-name-fallback.md");
        assertTrue(population.size() >= FILE_FLOOR,
                "only " + population.size() + " authored Checks carry the visit-name literals"
                        + " (floor " + FILE_FLOOR + ") — either the walk is broken or the"
                        + " literals migrated out of the Checks, which D3 forbids");
    }
}
