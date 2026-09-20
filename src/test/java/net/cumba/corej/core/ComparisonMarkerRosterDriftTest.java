package net.cumba.corej.core;

import net.cumba.corej.core.expr.eval.ExprCompiler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * M4 (terminal review of {@code PLAN-typed-expression-engine}): the {@code time} conversion joined
 * {@code ExprCompiler}'s marker vocabulary in phase 3b, reached the corpus in phase 3c, and the
 * hand-written rosters in the test harness never heard of it — the census announced five markers
 * where the compiler had six, and nothing was red. The census now derives its roster; this gate
 * covers the classifier that deliberately CANNOT derive it ({@link CheckExprUnits} classifies
 * against the retired v1 vocabulary, so each marker needs an explicit decision, not a blind union).
 */
class ComparisonMarkerRosterDriftTest
{

    @Test
    void checkExprUnitsAccountsForExactlyTheCompilersMarkers()
    {
        // EQUALITY, not containment, in both directions: a marker added to the engine reds here
        // until CheckExprUnits records a decision for it; a marker removed from the engine reds
        // here until the stale acknowledgement is retired. Containment either way would let one
        // of the two drifts pass silently.
        Assertions.assertEquals(ExprCompiler.comparisonMarkers(),
                new java.util.TreeSet<>(CheckExprUnits.acknowledgedComparisonMarkers()),
                "ExprCompiler.comparisonMarkers() and CheckExprUnits' acknowledged set have "
                        + "drifted apart — record a decision for the new/removed marker in "
                        + "CheckExprUnits.acknowledgedComparisonMarkers() (see its javadoc)");
    }
}
