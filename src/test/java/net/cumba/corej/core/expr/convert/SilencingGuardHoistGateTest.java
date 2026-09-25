package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Validate-mode ratchet gate from {@code plans/done/PLAN-generator-silencing-guard-hoist.md} (end
 * state (i)): recompute the silencing-guard set over {@code rules-src/checks} — the authored Check
 * surface, before any generator pass — and compare it against the recorded baselines.
 *
 * <p>
 * ⚑ <b>Wave 33 moved this scan's input, deliberately preserving its warrant.</b> It used to read
 * the assembled {@code rules-legacy/} corpus, justified by that corpus being byte-pinned against
 * {@code rules-src} by {@code RuleStorageRoundTripTest}: the point was always to measure what an
 * <em>author</em> wrote, not what a generator emitted. With the corpus and its round-trip pin
 * deleted, {@code rules-src/checks} is the direct expression of the same intent. ⛔ It must
 * <b>not</b> be re-pointed at {@code rules/}: that is expression carriage lowered by
 * {@code ExprLowering} with {@code NonEmptyGuardInliner}-injected guards (the injector was deleted
 * 2026-08-26; guards are authored), and a gate whose failure message tells an author to change
 * their authoring must not measure generator output.
 * </p>
 *
 * <ul>
 * <li>A scan hit <b>not in either baseline</b> fails: a rule was (re-)authored in the old guard
 * form — per R-4.11 ({@code CORE-RULES-AUTHORING-GUIDELINES.md}) new rules express the gate as
 * {@code Requirements.Variables.All}, unless the guard is deliberately Python-load-bearing (see
 * below), in which case it is added to the baseline with that justification.</li>
 * <li>A baseline entry <b>no longer found</b> fails: the guard was hoisted/removed — delete the
 * stale line so the baseline only shrinks deliberately.</li>
 * </ul>
 *
 * <p>
 * Why a ratchet and not a zero gate: the completed hand migration
 * ({@code PLAN-scope-variables-guard-migration.md}) covered the CORE-family SDTM/SEND packages; the
 * FDA / PMDA / CDISC / DRAFT families were never migrated, and — the premise correction this gate
 * records — a blanket hoist is <b>not</b> parity-neutral: the Python engine ignores
 * {@code Scope.Variables}, so a Check-level {@code exists} guard can be load-bearing for the Python
 * lane (CORE-000145/000213/001034 had their guards deliberately re-added by the 2026-06-27 parity
 * work for exactly that reason). Migrating a baselined family is therefore a per-family decision
 * with Python-lane analysis, tracked by the baseline size shrinking — never an automatic rewrite.
 * </p>
 *
 * <p>
 * ⚑ <b>Two baseline files, two different warrants</b> (S4, 2026-08-07 — do not merge them):
 * </p>
 * <ul>
 * <li>{@value #BASELINE_RESOURCE} — the <b>class-V TS</b> rules, whose negated leaf
 * ({@code not is_integer(TSVAL)}) fires on an absent column in <em>both</em> engines. Nothing to do
 * with the R-4.11a operator carve-out.</li>
 * <li>{@value #TARGET_POSITION_RESOURCE} — guards that the R-4.11a carve-out used to hide because
 * it was applied operator-wide. Narrowing it to the comparator position
 * ({@code SilencingGuardScan.PYTHON_GUARD_REQUIRED_OPERATORS}) made them visible; they are genuine
 * R-4.11 backlog, recorded so the change surfaced them <em>somewhere actionable</em> rather than as
 * a wall of new violations.</li>
 * </ul>
 */
class SilencingGuardHoistGateTest
{

    static final String BASELINE_RESOURCE = "silencing-guard-baseline.txt";

    static final String TARGET_POSITION_RESOURCE = "silencing-guard-carveout-target-baseline.txt";

    @Test
    void noNewAuthoredSilencingExistsGuards() throws Exception
    {
        Set<String> classV = SilencingGuardCorpusScan.loadBaseline(BASELINE_RESOURCE);
        Set<String> targetPosition = SilencingGuardCorpusScan
                .loadBaseline(TARGET_POSITION_RESOURCE);
        Set<String> baseline = new LinkedHashSet<>(classV);
        baseline.addAll(targetPosition);

        Set<String> current = SilencingGuardCorpusScan.scan(SilencingGuardScan::hoistableVars);

        List<String> fresh = new ArrayList<>(current);
        fresh.removeAll(baseline);
        List<String> staleClassV = new ArrayList<>(classV);
        staleClassV.removeAll(current);
        List<String> staleTargetPosition = new ArrayList<>(targetPosition);
        staleTargetPosition.removeAll(current);

        if (!fresh.isEmpty())
        {
            fail("R-4.11: new silencing `exists` guard(s) authored — express the gate as "
                    + "Requirements.Variables.All and drop the Check leaf (see "
                    + "CORE-RULES-AUTHORING-GUIDELINES.md and "
                    + "plans/done/PLAN-generator-silencing-guard-hoist.md), or — ONLY if the guard "
                    + "is deliberately load-bearing in the Check — add it to " + BASELINE_RESOURCE
                    + " with that justification (" + fresh.size() + " new):\n"
                    + String.join("\n", fresh) + "\nfull recomputed set: "
                    + SilencingGuardCorpusScan.writeActual(BASELINE_RESOURCE, current));
        }
        assertStale(staleClassV, BASELINE_RESOURCE);
        assertStale(staleTargetPosition, TARGET_POSITION_RESOURCE);
    }


    private static void assertStale(List<String> stale, String resource)
    {
        assertTrue(stale.isEmpty(),
                "silencing-guard baseline is stale — these guards were hoisted or removed; "
                        + "delete the line(s) from " + resource
                        + " so the ratchet only shrinks deliberately (" + stale.size()
                        + " stale):\n" + String.join("\n", stale));
    }
}
