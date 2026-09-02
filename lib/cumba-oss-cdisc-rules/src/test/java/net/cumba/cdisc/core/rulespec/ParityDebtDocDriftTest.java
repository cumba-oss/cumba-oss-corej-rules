package net.cumba.cdisc.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code documentation/PARITY-DEBT.md} to what {@link ParityDebtDoc} generates from
 * {@code parity-diff-baseline.json}.
 *
 * <p>
 * <b>Why this test is load-bearing.</b> A generated file committed to the repo is exactly the shape
 * of derived output that nobody regenerates unless something makes them. ⚠ This paragraph used to
 * cite "this repo's own {@code rule-inventory/} artifacts are ~2900 lines adrift" as the example;
 * that claim is <b>stale and was refuted twice</b> (wave-16 lane C re-derived them
 * byte-identically, and wave 21 regenerated the whole tree in {@code 0be3dcba1}), so it is gone
 * rather than restated with a fresher number. The test itself is unchanged and still correct. A
 * debt list is worse than an inventory if it rots, because a release audit <i>trusts</i> it: an
 * entry that became debt after the last regeneration would simply not appear. Same guard, same
 * reasoning as {@code LegacyToExpressionDocDriftTest} once put on
 * {@code legacy-to-expression-map.md} (retired with that doc's freeze at the wave-43 flip).
 *
 * <p>
 * The test runs on the default {@code -P main} build, deliberately. It used to justify that by the
 * {@code PyParity} profile being opt-in; wave 33 removed that profile and the whole Python lane, so
 * the reason is now stronger — there is no other lane, and no other guard, at all.
 */
class ParityDebtDocDriftTest
{

    @Test
    void committedDebtListMatchesGeneratorOutput() throws IOException
    {
        String expected = ParityDebtDoc.render(BaselineManifest.load(ParityDebtDoc.baselineFile()));
        assertEquals(expected, Files.readString(ParityDebtDoc.documentFile()),
                "documentation/PARITY-DEBT.md drifted from parity-diff-baseline.json — regenerate"
                        + " it by running ParityDebtDoc.main(...) (or copy the expected text"
                        + " above). A stale debt list is worse than none: a release audit trusts"
                        + " it.");
    }


    /** The partition is total: every baseline entry is either classified or debt. */
    @Test
    void everyEntryIsEitherClassifiedOrDebt() throws IOException
    {
        BaselineManifest.Manifest manifest = BaselineManifest.load(ParityDebtDoc.baselineFile());
        List<BaselineManifest.KnownDivergence> all = manifest.knownDivergences();
        assertFalse(all.isEmpty(), "the baseline is empty — the wrong file was resolved");
        long classified = all.stream().filter(e -> !ParityDebtDoc.isDebt(e)).count();
        assertEquals(all.size(), classified + ParityDebtDoc.debt(manifest).size(),
                "an entry was counted both as classified and as debt");
    }


    /**
     * The census table's five numbers must add up to the entry count.
     *
     * <p>
     * This is the assertion the "every entry is classified or debt" test above does <b>not</b> give
     * you. {@code reporting} is derived by <b>subtraction</b>, so an entry carrying two markers —
     * or a {@code (reporting-surface)} qualifier spelled without its token — would put a row in the
     * table that does not belong there and leave the arithmetic silently wrong, with every other
     * test in this class still green. A published census that does not add up is worse than no
     * census.
     */
    @Test
    void theCensusAddsUp() throws IOException
    {
        BaselineManifest.Manifest manifest = BaselineManifest.load(ParityDebtDoc.baselineFile());
        ParityDebtDoc.Census c = ParityDebtDoc.census(manifest);
        assertEquals(c.total(), c.sum(), "the PARITY-DEBT.md census table does not add up: " + c);
    }


    /** No entry may carry two disposition markers — it would be counted in two census rows. */
    @Test
    void noEntryCarriesTwoDispositionMarkers() throws IOException
    {
        assertEquals(List.of(),
                ParityDebtDoc.multiMarkerSpecs(BaselineManifest.load(ParityDebtDoc.baselineFile())),
                "these entries carry more than one disposition marker, so they are counted twice"
                        + " in the census and their disposition is ambiguous");
    }


    /**
     * A {@code (reporting-surface)} entry must also carry the bare {@code java-only-accepted}
     * token.
     *
     * <p>
     * The qualifier was chosen (OQ-1(b)) precisely so that <b>one</b> grep finds every accepted
     * entry. If a future clause ever spelled the qualifier without the token, that grep would start
     * under-reporting silently — and the census table would double-count, since it derives the
     * plain count by subtracting the qualified one.
     */
    @Test
    void theReportingQualifierAlwaysCarriesTheAcceptedToken() throws IOException
    {
        for (BaselineManifest.KnownDivergence e : BaselineManifest
                .load(ParityDebtDoc.baselineFile()).knownDivergences())
        {
            String ref = e.fixRef() == null ? "" : e.fixRef();
            if (ref.contains(ParityDebtDoc.REPORTING_QUALIFIER))
            {
                assertTrue(ref.contains(ParityDebtDoc.ACCEPTED_MARKER),
                        e.spec() + ": the reporting-surface qualifier must be spelled as part of"
                                + " the java-only-accepted token, so one grep finds both");
            }
        }
    }
}
