package net.cumba.cdisc.core.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import net.cumba.cdisc.core.doc.RoutingCensus.Row;
import net.cumba.cdisc.core.doc.RoutingCensus.Snapshot;
import net.cumba.cdisc.core.model.Sensitivity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code documentation/routing-census.tsv} to the {@code Sensitivity}
 * {@code RulePackageLoader} derives and the evaluation domain it infers for every shipped rule.
 *
 * <p>
 * <b>Why this test is load-bearing.</b> Wave-34 lane B measured what catches a re-routing and the
 * answer was <em>nothing</em>: perturbing all 75 {@code Domain Presence Check} rules to
 * {@code Record Data} left all 5 284 {@code .cdt} scenarios green, and 1 849 shipped rules carry no
 * scenario at all ({@code plans/done/PLAN-derivation-routing-coverage.md} §7, Fix #231). Scenarios
 * assert violations; the inferred domain decides <em>framing</em> — which {@code RuleRunner} branch
 * executes and, through {@code ValidationReportBuilder.scopeOf}, the {@code FindingScope} of every
 * emitted finding. This test is the only thing that fails when framing moves.
 * </p>
 *
 * <p>
 * ⚠ <b>The diff is the report.</b> {@link #committedCensusMatchesTheDerivation()} asserts a
 * per-rule diff <em>before</em> the byte-level equality, so a re-routing names the rules it moved
 * instead of dumping 3 700 lines. That ordering is deliberate and must not be swapped. Verified
 * 2026-08-12 by re-running lane B's {@code N3P} perturbation: this test reddens with a 75-line diff
 * naming every re-routed rule.
 * </p>
 *
 * <p>
 * Same guard, same reasoning as {@link RuleDocCensusDriftTest} on
 * {@code CORE-RULES-SPECIFICATION.md}.
 * </p>
 */
class RoutingCensusDriftTest
{

    /** How many diff lines a failure message prints before truncating. */
    private static final int MAX_REPORTED = 200;

    private static Snapshot snapshot;

    private static String committed;

    @BeforeAll
    static void read() throws IOException
    {
        snapshot = RoutingCensus.snapshot();
        committed = Files.readString(RoutingCensus.artifactFile());
    }


    /**
     * The pin itself.
     *
     * <p>
     * Two assertions, in this order on purpose: the per-rule diff is what a reader needs, and the
     * byte comparison additionally catches banner / row-count / ordering drift that a row-level
     * diff cannot see.
     * </p>
     */
    @Test
    void committedCensusMatchesTheDerivation()
    {
        List<String> diff = RoutingCensus.diff(RoutingCensus.parse(committed), snapshot.rules());
        assertTrue(diff.isEmpty(), () -> diff.size()
                + " rule(s) derive a different Sensitivity / Domain than"
                + " documentation/routing-census.tsv records. If the derivation change was"
                + " intended, regenerate with"
                + " lib/corej-cdisc-rules/scripts/gen-routing-census.sh and review the diff"
                + " — it names every rule whose RuleRunner branch and FindingScope moved."
                + " The .cdt scenarios do NOT cover this (wave-34 lane B: 75 of 75 re-routed"
                + " rules produced zero reds), so nothing else will tell you.\n" + report(diff));

        assertEquals(RoutingCensus.render(snapshot), committed,
                "documentation/routing-census.tsv has the same rows as the derivation but differs"
                        + " byte-for-byte — the banner, the row count, the package count or the"
                        + " ordering drifted. Regenerate with"
                        + " lib/corej-cdisc-rules/scripts/gen-routing-census.sh.");
    }


    /**
     * The census is not empty and the projection really did collapse a fan-out.
     *
     * <p>
     * A generator that silently resolved the wrong directory would render a plausible empty census
     * and {@link #committedCensusMatchesTheDerivation()} would happily pin it — the same vacuity
     * this whole artifact exists to remove.
     * </p>
     */
    @Test
    void theCensusIsNonEmptyAndFannedOut()
    {
        assertTrue(snapshot.packages() >= 56, "the loader found " + snapshot.packages()
                + " packages in rules/; the shipped corpus has at least 56");
        assertTrue(snapshot.rules().size() > 1000, "the corpus projected to "
                + snapshot.rules().size() + " ids — it cannot have shrunk that far");
        assertTrue(snapshot.entries() > snapshot.rules().size(),
                "entries and distinct ids are equal — the fan-out disappeared, or every package was"
                        + " read as one");
    }


    /**
     * Every value the census records maps to a known enum constant.
     *
     * <p>
     * {@link RoutingCensus#ABSENT} is the render for a field the derivation left unset. A rule
     * reaching the census with no {@code Sensitivity} would execute under an undefined
     * multiplicity, so it is an error here rather than a row.
     * </p>
     */
    @Test
    void everyRecordedValueIsAKnownEnumConstant()
    {
        for (Map.Entry<String, Row> e : snapshot.rules().entrySet())
        {
            assertNotNull(Sensitivity.fromJson(e.getValue().sensitivity()),
                    e.getKey() + ": Sensitivity \"" + e.getValue().sensitivity()
                            + "\" is not a Sensitivity constant");
        }
    }


    /**
     * The per-id projection the artifact publishes is only well defined if this holds.
     *
     * <p>
     * The artifact keeps one row per {@code Core.Id} rather than one per {@code (package, rule)}
     * entry — 3.8× smaller. If a rule derived two different {@code Sensitivity}s in two packages,
     * the collapse would keep whichever package sorted first and the pinned value would depend on
     * file ordering.
     * </p>
     */
    @Test
    void classifyingFieldsAreInvariantPerRuleId()
    {
        assertEquals(List.of(), snapshot.inconsistentIds(),
                "these rule ids derive different Sensitivity / Domain values in different"
                        + " packages, so the census's per-id projection is ambiguous and its rows"
                        + " depend on package ordering");
    }


    /** A round trip through the artifact format loses nothing. */
    @Test
    void renderAndParseRoundTrip()
    {
        assertEquals(snapshot.rules(), RoutingCensus.parse(RoutingCensus.render(snapshot)),
                "the artifact format is lossy");
        assertEquals(List.of(), RoutingCensus.diff(snapshot.rules(), snapshot.rules()));
    }


    /** A truncated or hand-merged artifact must fail loudly, not pin half a corpus. */
    @Test
    void parseRejectsMalformedRows()
    {
        assertEquals(Map.of(), RoutingCensus.parse("# banner only\n"));
        assertEquals(1, RoutingCensus.parse("#c\nA\tRecord\t{ROW}\n").size());
        assertThrows(IllegalArgumentException.class, () -> RoutingCensus.parse("A\tRecord\n"));
        assertThrows(IllegalArgumentException.class,
                () -> RoutingCensus.parse("A\tRecord\t{ROW}\textra\n"));
        assertThrows(IllegalArgumentException.class,
                () -> RoutingCensus.parse("A\tRecord\t{ROW}\nA\tRecord\t{ROW}\n"));
    }


    /** The diff names what moved, in both directions. */
    @Test
    void diffNamesEveryKindOfChange()
    {
        SortedMap<String, Row> before = RoutingCensus.parse(
                "A\tDataset\t{}\nB\tRecord\t{ROW}\n" + "C\tRecord\t{ROW}\nE\tRecord\t{ROW}\n");
        SortedMap<String, Row> after = RoutingCensus.parse("A\tDataset\t{ROW}\nB\tVariable\t{ROW}\n"
                + "D\tRecord\t{ROW}\nE\tRecord\t{VAR,ROW}\n");
        List<String> diff = RoutingCensus.diff(before, after);
        assertEquals(5, diff.size(), diff.toString());
        assertTrue(diff.get(0).contains("A: Domain \"{}\" → \"{ROW}\""), diff.get(0));
        assertTrue(diff.get(1).contains("B: Sensitivity \"Record\" → \"Variable\""), diff.get(1));
        assertTrue(diff.get(2).startsWith("C: GONE"), diff.get(2));
        assertTrue(diff.get(3).startsWith("D: NEW"), diff.get(3));
        assertTrue(diff.get(4).contains("E: Domain \"{ROW}\" → \"{VAR,ROW}\""), diff.get(4));
    }


    /** The census reads the corpus the engine ships, not some other directory. */
    @Test
    void theCensusReadsTheShippedCorpus() throws IOException
    {
        assertEquals(snapshot.packages(), RoutingCensus.nativePackages().size());
        assertTrue(Files.isDirectory(RoutingCensus.nativeCorpusDir()),
                "rules/ did not resolve to a directory: " + RoutingCensus.nativeCorpusDir());
        assertTrue(
                RoutingCensus.nativePackages().stream()
                        .noneMatch(p -> "packages.json".equals(p.getFileName().toString())),
                "packages.json is an index, not a rule package");
    }


    private static String report(List<String> lines)
    {
        if (lines.size() <= MAX_REPORTED)
        {
            return String.join("\n", lines);
        }
        return String.join("\n", lines.subList(0, MAX_REPORTED)) + "\n… and "
                + (lines.size() - MAX_REPORTED) + " more.";
    }

}
