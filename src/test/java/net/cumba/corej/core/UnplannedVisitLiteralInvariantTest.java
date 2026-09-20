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
                String residue = e.replace(CANON_UNSCHEDUL, "").replace(CANON_UNPLAN, "");
                if (residue.contains("UNSCHEDUL") || residue.contains("\"UNPLAN\""))
                {
                    offences.add(p + ": non-canonical literal spelling; Check after stripping the"
                            + " canonical calls still carries a name literal: " + residue);
                }
                if (!e.contains(CANON_UNPLAN))
                {
                    offences.add(p + ": carries the UNSCHEDUL test but not the canonical UNPLAN"
                            + " twin — the two literals travel together in the cascade");
                }
            }
        }
        assertEquals(List.of(), offences,
                "every copy of the unplanned-visit name test must be byte-identical to '"
                        + CANON_UNSCHEDUL + "' / '" + CANON_UNPLAN + "' (D3 + Q-C)");
        assertTrue(population.size() >= FILE_FLOOR,
                "only " + population.size() + " authored Checks carry the visit-name literals"
                        + " (floor " + FILE_FLOOR + ") — either the walk is broken or the"
                        + " literals migrated out of the Checks, which D3 forbids");
    }
}
