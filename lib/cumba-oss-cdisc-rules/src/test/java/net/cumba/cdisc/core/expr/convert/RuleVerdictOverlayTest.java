package net.cumba.cdisc.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The D36 verdict side-car, exercised on synthetic files.
 *
 * <p>
 * Synthetic on purpose. It was written when the <em>committed</em> side-car was still empty (D36
 * shipped the channel, not the adjudication), and it stays synthetic now that the side-car carries
 * 382 verdicts: the behaviours that matter — the key normalisation, the positional-id rejection,
 * the layer whitelist, the ruled-L2 allowlist, the unmatched report — need <em>malformed</em> and
 * near-miss inputs, and the committed file is (by construction) none of those. The merge into the
 * registry is proven separately against the real corpus by
 * {@code RuleEquivalenceInventoryTest.everyClassIsPendingUnlessTheSideCarSuppliesAVerdict}, and the
 * committed side-car is accounted for by
 * {@code RuleEquivalenceDriftTest.theCommittedSideCarIsFullyAccountedFor}.
 */
class RuleVerdictOverlayTest
{

    private static Path write(Path dir, String yaml) throws IOException
    {
        Path file = dir.resolve("verdicts.yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return file;
    }


    @Test
    void anAbsentSideCarIsAnEmptyOverlayNotAnError(@TempDir Path dir) throws IOException
    {
        RuleVerdictOverlay overlay = RuleVerdictOverlay.load(dir.resolve("does-not-exist.yaml"));
        assertEquals(0, overlay.size());
        assertEquals(Set.of(), overlay.keys());
        assertEquals(List.of(), overlay.report(Set.of(), 0).get("unmatched"));
    }


    /**
     * The three ways a side-car can legitimately say "nothing yet". All three must read as an empty
     * overlay rather than as an error, because the committed file is empty on purpose and a tree
     * that cannot regenerate its registry is worse than one with no verdicts.
     */
    @Test
    void anEmptyVerdictListIsAnEmptyOverlay(@TempDir Path dir) throws IOException
    {
        assertEquals(0, RuleVerdictOverlay.load(write(dir, "verdicts: []\n")).size());
        assertEquals(0, RuleVerdictOverlay.load(write(dir, "# only a comment\n")).size());
        assertEquals(0, RuleVerdictOverlay.load(write(dir, "verdicts:\n")).size(),
                "an explicit null `verdicts:` is empty, not malformed");
    }


    /** An absent or explicitly-null {@code sanctioned_layers} is the empty list, not an error. */
    @Test
    void anAbsentOrNullSanctionedLayersIsEmpty(@TempDir Path dir) throws IOException
    {
        assertEquals(List.of(), RuleVerdictOverlay.load(write(dir, """
                verdicts:
                  - members: [A-1, B-2]
                    verdict: sanctioned
                    sanctioned_layers:
                """)).find(List.of("A-1", "B-2")).orElseThrow().sanctionedLayers());
    }


    @Test
    void aVerdictIsReadWithItsSanctionedLayers(@TempDir Path dir) throws IOException
    {
        // ⚠ The class here is the one member set L2 may be sanctioned on
        // (RuleVerdictOverlay.L2_SANCTION_ALLOWLIST); any other set with L2 is fatal, which
        // anL2SanctionOutsideTheRuledAllowlistIsFatal() proves.
        RuleVerdictOverlay overlay = RuleVerdictOverlay.load(write(dir, """
                verdicts:
                  - members: [CDISC-AD0046, FDA-SD0038, PMDA-AD0046, PMDA-SD0038]
                    verdict: sanctioned
                    sanctioned_layers: [L2, L3]
                    note: free text, ignored by the merge
                  - members: [FDA-SD0007, PMDA-SD0007]
                    verdict: to-align
                """));
        assertEquals(2, overlay.size());
        RuleVerdictOverlay.Verdict cls = overlay
                .find(List.of("PMDA-SD0038", "CDISC-AD0046", "PMDA-AD0046", "FDA-SD0038"))
                .orElseThrow();
        assertEquals("sanctioned", cls.verdict());
        assertEquals(List.of("L2", "L3"), cls.sanctionedLayers());
        RuleVerdictOverlay.Verdict relation = overlay.find(List.of("FDA-SD0007", "PMDA-SD0007"))
                .orElseThrow();
        assertEquals("to-align", relation.verdict());
        assertEquals(List.of(), relation.sanctionedLayers(),
                "sanctioned_layers defaults to empty, which is the only legal value for a relation");
    }


    /**
     * ⚠⚠ The constraint the whole design hangs on. {@code EQ-}/{@code REL-} ids are positional and
     * renumber on every regeneration, so a side-car keyed on one silently re-attaches its verdict
     * to a different class — and nothing detects it, because the key still resolves. The loader
     * refuses the shape outright rather than trusting authors to know.
     */
    @Test
    void aPositionalRegistryIdIsRejectedAsAKey(@TempDir Path dir) throws IOException
    {
        Path eq = write(dir, """
                verdicts:
                  - members: [EQ-0470]
                    verdict: aligned
                """);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> RuleVerdictOverlay.load(eq));
        assertTrue(failure.getMessage().contains("EQ-0470"), failure.getMessage());
        assertTrue(failure.getMessage().contains("positional"), failure.getMessage());

        Path rel = write(dir, """
                verdicts:
                  - members: [REL-0001]
                    verdict: sanctioned
                """);
        assertThrows(IllegalStateException.class, () -> RuleVerdictOverlay.load(rel));
    }


    /** The key is a SET: order and repetition in the file must not change what it addresses. */
    @Test
    void theKeyIsNormalisedSortedAndDeduplicated(@TempDir Path dir) throws IOException
    {
        RuleVerdictOverlay overlay = RuleVerdictOverlay.load(write(dir, """
                verdicts:
                  - members: [PMDA-SD0035, CDISC-CG0114, CORE-000093, CDISC-CG0114]
                    verdict: aligned
                """));
        assertEquals(Set.of(List.of("CDISC-CG0114", "CORE-000093", "PMDA-SD0035")), overlay.keys());
        assertTrue(overlay.find(List.of("CORE-000093", "PMDA-SD0035", "CDISC-CG0114")).isPresent());
        assertEquals(List.of("CDISC-CG0114", "CORE-000093"),
                RuleVerdictOverlay.key(List.of("CORE-000093", "CDISC-CG0114", "CORE-000093")));
    }


    /**
     * ⚠ A near-miss key must MISS. The layer above reports it; nothing here may fuzzily re-attach a
     * judgement to the class it nearly names.
     */
    @Test
    void aMemberSetThatDiffersByOneRuleDoesNotMatch(@TempDir Path dir) throws IOException
    {
        RuleVerdictOverlay overlay = RuleVerdictOverlay.load(write(dir, """
                verdicts:
                  - members: [CDISC-CG0114, CORE-000093, FDA-SD0035]
                    verdict: aligned
                """));
        assertTrue(overlay.find(List.of("CDISC-CG0114", "CORE-000093")).isEmpty(), "one fewer");
        assertTrue(overlay.find(List.of("CDISC-CG0114", "CORE-000093", "FDA-SD0035", "PMDA-SD0035"))
                .isEmpty(), "one more");
    }


    /** A malformed entry is fatal: a silently dropped verdict still looks authored. */
    @Test
    void malformedEntriesAreFatal(@TempDir Path dir)
    {
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("no members", "verdicts:\n  - verdict: aligned\n");
        bad.put("empty members", "verdicts:\n  - members: []\n    verdict: aligned\n");
        bad.put("no verdict", "verdicts:\n  - members: [A-1, B-2]\n");
        bad.put("unknown verdict", "verdicts:\n  - members: [A-1, B-2]\n    verdict: alligned\n");
        bad.put("verdicts not a sequence", "verdicts: aligned\n");
        bad.put("sanctioned_layers not a sequence",
                "verdicts:\n  - members: [A-1, B-2]\n    verdict: sanctioned\n"
                        + "    sanctioned_layers: L2\n");
        bad.put("duplicate key", "verdicts:\n  - members: [A-1, B-2]\n    verdict: aligned\n"
                + "  - members: [B-2, A-1]\n    verdict: dissolved\n");
        bad.forEach((why, yaml) -> assertThrows(IllegalStateException.class,
                () -> RuleVerdictOverlay.load(write(dir, yaml)), why));
    }


    /**
     * The sanctionable-layer whitelist, applied at the authoring boundary. These are the exact
     * inputs the {@code != "L1"} blacklist this project used to run accepted — measured, all of
     * them passed the build green.
     */
    @Test
    void onlyL2L3L4CanBeSanctionedAndTheCheckIsCaseSensitive(@TempDir Path dir) throws IOException
    {
        for (String layer : List.of("L1", "l1", "l2", "L9", "banana"))
        {
            String yaml = "verdicts:\n  - members: [A-1, B-2]\n    verdict: sanctioned\n"
                    + "    sanctioned_layers: [" + layer + "]\n";
            assertThrows(IllegalStateException.class,
                    () -> RuleVerdictOverlay.load(write(dir, yaml)),
                    layer + " must not be sanctionable");
        }
        assertEquals(List.of("L2", "L3", "L4"), RuleVerdictOverlay.load(write(dir, """
                verdicts:
                  - members: [CDISC-AD0046, FDA-SD0038, PMDA-AD0046, PMDA-SD0038]
                    verdict: sanctioned
                    sanctioned_layers: [L4, L2, L3, L2]
                    note: CLF R-2 — the one ruled L2 sanction; needed here to exercise L2 at all.
                """)).find(List.of("CDISC-AD0046", "FDA-SD0038", "PMDA-AD0046", "PMDA-SD0038"))
                .orElseThrow().sanctionedLayers(),
                "declared layers are de-duplicated and reported in whitelist order");
    }


    /**
     * <strong>L2 is not sanctionable by default, and this is the assertion that makes the doctrine
     * real.</strong> Plan §13.2: prose (L4) and OV-context / {@code Sensitivity} /
     * {@code Executability} (L3) are valid family-sheet variants, but targeting (L2 = {@code Scope}
     * + {@code Match_Datasets}) is behavioural and in scope for realignment — so a class still
     * diverging there is recorded as {@code pending} with its reason. Until 2026-08-19 nothing
     * enforced that: {@code sanctioned_layers: [L2]} on any class loaded green, and the file's own
     * documented example showed exactly that shape on a class that diverges at L2 and nowhere else.
     *
     * <p>
     * The allowlist is keyed on the member set — the same key the rest of the class uses — rather
     * than on a pattern over the free-text {@code note}, which the phrase "per the ruling" would
     * defeat.
     */
    @Test
    void anL2SanctionOutsideTheRuledAllowlistIsFatal(@TempDir Path dir)
    {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> RuleVerdictOverlay.load(write(dir, """
                        verdicts:
                          - members: [CDISC-CG0114, CORE-000093, FDA-SD0035, PMDA-SD0035]
                            verdict: sanctioned
                            sanctioned_layers: [L2]
                            note: CORE narrows the scope to its own Use_Case.
                        """)));
        assertTrue(failure.getMessage().contains("L2 is not"), failure.getMessage());
        assertTrue(failure.getMessage().contains("L2_SANCTION_ALLOWLIST"), failure.getMessage());

        // A near-miss of the allowlisted set is a different class and inherits nothing.
        assertThrows(IllegalStateException.class, () -> RuleVerdictOverlay.load(write(dir, """
                verdicts:
                  - members: [CDISC-AD0046, FDA-SD0038, PMDA-AD0046]
                    verdict: sanctioned
                    sanctioned_layers: [L2]
                    note: a subset of the ruled class.
                """)), "a subset of the allowlisted member set must not inherit the sanction");

        // L3/L4 on the same non-allowlisted class stay perfectly legal.
        assertDoesNotThrow(() -> RuleVerdictOverlay.load(write(dir, """
                verdicts:
                  - members: [CDISC-CG0114, CORE-000093, FDA-SD0035, PMDA-SD0035]
                    verdict: aligned
                    sanctioned_layers: [L3, L4]
                """)));
    }


    /**
     * The allowlisted entry still has to explain itself: the registry carries only the
     * machine-readable outcome, so the ruling has to travel in the side-car's {@code note}.
     */
    @Test
    void anAllowlistedL2SanctionStillNeedsItsNote(@TempDir Path dir)
    {
        for (String note : List.of("", "\n    note: \"\"", "\n    note: \"   \""))
        {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> RuleVerdictOverlay.load(write(dir,
                            "verdicts:\n" + "  - members: [CDISC-AD0046, FDA-SD0038, PMDA-AD0046,"
                                    + " PMDA-SD0038]\n    verdict: sanctioned\n"
                                    + "    sanctioned_layers: [L2]" + note + "\n")),
                    "an L2 sanction with note '" + note + "' must be rejected");
            assertTrue(failure.getMessage().contains("no `note`"), failure.getMessage());
        }
    }


    /**
     * ⚑ The visibility half of D36. A key that matched nothing is <em>reported</em>, with the
     * verdict it was carrying, so a judgement orphaned by a corpus edit cannot revert to
     * {@code pending} in silence.
     */
    @Test
    void aKeyThatMatchedNothingIsReported(@TempDir Path dir) throws IOException
    {
        RuleVerdictOverlay overlay = RuleVerdictOverlay.load(write(dir, """
                verdicts:
                  - members: [A-1, B-2]
                    verdict: aligned
                  - members: [C-3, D-4]
                    verdict: dissolved
                """));
        Map<String, Object> report = overlay.report(Set.of(List.of("A-1", "B-2")), 1);
        assertEquals(2, report.get("entries"));
        assertEquals(1, report.get("applied"));
        assertEquals(
                List.of(new LinkedHashMap<>(
                        Map.of("members", List.of("C-3", "D-4"), "verdict", "dissolved"))),
                report.get("unmatched"));
        assertTrue(String.valueOf(report.get("source")).endsWith("verdicts.yaml"),
                "the report names the file it came from: " + report.get("source"));

        assertEquals(List.of(), overlay
                .report(Set.of(List.of("A-1", "B-2"), List.of("C-3", "D-4")), 2).get("unmatched"));
    }


    /**
     * {@code Verdict} defends its own list: a caller's mutable list cannot leak into the overlay.
     */
    @Test
    void aVerdictCopiesItsSanctionedLayers()
    {
        List<String> layers = new ArrayList<>(List.of("L2"));
        RuleVerdictOverlay.Verdict verdict = new RuleVerdictOverlay.Verdict("sanctioned", layers);
        layers.add("L3");
        assertEquals(List.of("L2"), verdict.sanctionedLayers());
    }
}
