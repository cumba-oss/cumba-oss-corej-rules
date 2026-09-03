package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Ratchet over the guards the R-4.11a carve-out <b>still hides</b> — the set
 * {@link SilencingGuardScan#carveOutHiddenVars} returns, i.e. a top-level {@code exists} guard
 * whose variable feeds a {@code is_unique_set} / {@code is_not_unique_set} /
 * {@code is_inconsistent_across_dataset} leaf in its <b>comparator (grouping-key)</b> position.
 *
 * <p>
 * These guards are <b>correct</b>: the fork drops an absent grouping column from the key and
 * over-fires, and it reads no part of {@code Scope.Variables}, so the Check leaf is the only guard
 * it sees. Nothing here is a defect to fix. What was wrong is that the set was <b>invisible</b> —
 * {@link SilencingGuardHoistGateTest} never listed it and no other artifact recorded it, so it
 * could grow, shrink or change shape without anyone seeing (measured 2026-08-07 at the
 * operator-wide carve-out: 699 package entries / 193 distinct {@code Core.Id}s, against a 37-line
 * visible baseline).
 * </p>
 *
 * <p>
 * The ratchet is therefore a <b>visibility</b> gate, not a debt gate:
 * </p>
 * <ul>
 * <li>a new entry fails — authoring a comparator-position guard is legitimate, so the fix is
 * normally to <em>add the line</em>; the point is that it lands as a reviewed diff;</li>
 * <li>a vanished entry fails — the guard was hoisted or the rule rewritten, which changes the
 * fork's behaviour on absent grouping columns and must be a deliberate act.</li>
 * </ul>
 *
 * <p>
 * ⚠ Not to be confused with the two sets {@link SilencingGuardHoistGateTest} gates: those are the
 * class-V TS rules and the R-4.11 backlog the position-aware narrowing exposed. Three files, three
 * warrants (S4, {@code plans/done/PLAN-silencing-guard-carveout-s4.md} §2.3).
 * </p>
 */
class SilencingGuardCarveOutGateTest
{

    static final String BASELINE_RESOURCE = "silencing-guard-carveout-grouping-key-baseline.txt";

    @Test
    void carveOutHiddenSetMatchesBaseline() throws Exception
    {
        Set<String> baseline = SilencingGuardCorpusScan.loadBaseline(BASELINE_RESOURCE);
        Set<String> current = SilencingGuardCorpusScan.scan(SilencingGuardScan::carveOutHiddenVars);

        List<String> fresh = new ArrayList<>(current);
        fresh.removeAll(baseline);
        List<String> stale = new ArrayList<>(baseline);
        stale.removeAll(current);

        if (!fresh.isEmpty())
        {
            fail("R-4.11a: guard(s) newly hidden by the PYTHON_GUARD_REQUIRED_OPERATORS carve-out. "
                    + "A comparator-position guard IS correct authoring — record it by adding "
                    + "the line(s) to " + BASELINE_RESOURCE + ", so the hidden set stays visible ("
                    + fresh.size() + " new):\n" + String.join("\n", fresh)
                    + "\nfull recomputed set: "
                    + SilencingGuardCorpusScan.writeActual(BASELINE_RESOURCE, current));
        }
        assertTrue(stale.isEmpty(),
                "R-4.11a carve-out baseline is stale — these guards are no longer hidden (hoisted, "
                        + "or the leaf's operand moved out of the grouping key). Confirm the fork "
                        + "no longer needs them and delete the line(s) from " + BASELINE_RESOURCE
                        + " (" + stale.size() + " stale):\n" + String.join("\n", stale));
    }
}
