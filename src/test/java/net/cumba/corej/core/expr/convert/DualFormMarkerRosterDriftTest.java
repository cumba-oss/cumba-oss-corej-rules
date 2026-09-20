package net.cumba.corej.core.expr.convert;

import net.cumba.corej.core.expr.eval.ExprCompiler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * M4 (terminal review of {@code PLAN-typed-expression-engine}): {@link DualFormCheckNode} lowers
 * expression Checks back to the LEGACY v1 JSON shape, byte-mirrored with
 * {@code scripts/lib/check_expr.py}, and both mirrors' wrapper rosters are hand-written v1
 * vocabulary — they cannot be derived from the engine, because the referent is the retired model,
 * not the live compiler. This gate makes the non-derivability safe: every live compiler marker must
 * carry an explicit decision (handled family, restored conversion, or deliberate no-legacy-surface
 * exclusion) in {@code DualFormCheckNode.acknowledgedComparisonMarkers()}.
 */
class DualFormMarkerRosterDriftTest
{

    @Test
    void dualFormAccountsForExactlyTheCompilersMarkers()
    {
        Assertions.assertEquals(ExprCompiler.comparisonMarkers(),
                new java.util.TreeSet<>(DualFormCheckNode.acknowledgedComparisonMarkers()),
                "ExprCompiler.comparisonMarkers() and DualFormCheckNode's acknowledged set have "
                        + "drifted apart — record a decision for the new/removed marker in "
                        + "DualFormCheckNode.acknowledgedComparisonMarkers(). The WRAPPER "
                        + "rosters are gated separately by UnaryWrapperRosterDriftTest — do not "
                        + "hand-mirror scripts/lib/check_expr.py's _UNARY_WRAPPERS, change it and "
                        + "let that test agree");
    }
}
