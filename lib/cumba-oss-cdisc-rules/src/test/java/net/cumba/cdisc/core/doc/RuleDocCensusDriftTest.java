package net.cumba.cdisc.core.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Predicate;
import net.cumba.cdisc.core.doc.RuleDocCensus.CensusRule;
import net.cumba.cdisc.core.doc.RuleDocCensus.Snapshot;
import net.cumba.cdisc.core.model.Executability;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.cdisc.core.model.VariableUniverse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code rule-doc-census} region of {@code documentation/CORE-RULES-SPECIFICATION.md} to
 * what {@link RuleDocCensus} generates from the code enums and the three corpus views.
 *
 * <p>
 * <b>Why this test is load-bearing.</b> Wave-13 lane E measured ~35 corpus counts in the two rule
 * documents and found ~28 of them drifted, against a control — the expression-language reference,
 * then pinned by its own coverage gate (today: {@code CORE-EXPRESSION-CHECK-SPECIFICATION.md},
 * gated by {@link ExpressionCheckSpecDriftTest}) — where every anchored number was exact. The
 * difference was not care; it was whether a build step disagreed. This is that build step. It also
 * makes the enum rosters self-maintaining: adding an
 * {@link net.cumba.cdisc.core.model.OperationType OperationType} constant or a
 * {@code BuiltinRegistry} name changes the rendered region and fails here until the document is
 * regenerated, so a new constant cannot ship undocumented.
 * </p>
 *
 * <p>
 * Same guard, same reasoning as {@code ParityDebtDocDriftTest} on {@code PARITY-DEBT.md} (and,
 * until the wave-43 flip froze its doc, {@code LegacyToExpressionDocDriftTest} on
 * {@code legacy-to-expression-map.md}).
 * </p>
 */
class RuleDocCensusDriftTest
{

    private static Snapshot snapshot;

    private static String document;

    @BeforeAll
    static void read() throws IOException
    {
        snapshot = RuleDocCensus.snapshot();
        document = Files.readString(RuleDocCensus.documentFile());
    }


    @Test
    void committedRegionMatchesGeneratorOutput()
    {
        String expected = RuleDocCensus.render(snapshot, RuleDocCensus.withoutRegion(document));
        assertEquals(expected, RuleDocCensus.region(document),
                "the rule-doc-census region of documentation/CORE-RULES-SPECIFICATION.md drifted"
                        + " from the code + corpus — regenerate it with"
                        + " lib/corej-cdisc-rules/scripts/gen-rule-doc-census.sh (or"
                        + " RuleDocCensus.main). A census nobody regenerates is worse than no"
                        + " census: the next reader trusts it.");
    }


    /**
     * The corpus is not empty and the three views really are three views.
     *
     * <p>
     * A generator that silently resolved the wrong directory would render a plausible all-zero
     * census and {@link #committedRegionMatchesGeneratorOutput()} would happily pin it.
     * </p>
     */
    @Test
    void theThreeViewsAreDistinctAndNonEmpty()
    {
        assertTrue(snapshot.srcFiles() > 0, "rules-src/checks resolved to nothing");
        assertTrue(snapshot.assembledEntries() > 0,
                "the assembled operator-leaf view resolved to nothing — the census walked a corpus"
                        + " shape it does not understand and every table below is empty");
        assertTrue(snapshot.nativeEntries() > 0, "rules resolved to nothing");
        assertNotEquals(snapshot.assembledEntries(), snapshot.assembledDistinct().size(),
                "assembled entries and distinct ids are equal — the fan-out disappeared, or the"
                        + " packages were read as one");
        assertTrue(snapshot.assembledEntries() > snapshot.srcFiles(),
                "the fan-out view must be larger than the authored view");
    }


    /**
     * Every {@code Variable_Universe} / {@code Sensitivity} / {@code Executability} string the
     * shipped corpus declares maps to a known enum constant.
     *
     * <p>
     * Without this the census would silently drop an unrecognised value into no row at all: the
     * per-constant rows are looked up <em>by</em> the enum, so a corpus value outside the enum is
     * invisible in the table and only shows up as a total that does not add up.
     * </p>
     */
    @Test
    void everyCorpusDeclaredValueIsAKnownEnumConstant()
    {
        for (CensusRule r : snapshot.assembledDistinct().values())
        {
            assertNotNull(VariableUniverse.fromJson(r.variableUniverse()),
                    r.id() + ": Variable_Universe \"" + r.variableUniverse()
                            + "\" is not a VariableUniverse constant");
            assertNotNull(Sensitivity.fromJson(r.sensitivity()), r.id() + ": Sensitivity \""
                    + r.sensitivity() + "\" is not a Sensitivity constant");
            assertNotNull(Executability.fromJson(r.executability()), r.id() + ": Executability \""
                    + r.executability() + "\" is not an Executability constant");
        }
        for (String authored : snapshot.srcExecutability().keySet())
        {
            assertNotNull(Executability.fromJson(authored),
                    "rules-src declares Executability \"" + authored + "\", not a known constant");
        }
    }


    /**
     * The census tables add up.
     *
     * <p>
     * This is the assertion {@link #everyCorpusDeclaredValueIsAKnownEnumConstant()} does not give
     * you: it proves the per-constant rows partition the population rather than merely drawing from
     * a known vocabulary. A published census that does not add up is worse than no census.
     * </p>
     */
    @Test
    void theCensusAddsUp()
    {
        int byType = 0;
        int bySensitivity = 0;
        int byExecutability = 0;
        for (VariableUniverse u : VariableUniverse.values())
        {
            byType += count(r -> u.getJsonValue().equals(r.variableUniverse()));
        }
        for (Sensitivity v : Sensitivity.values())
        {
            bySensitivity += count(r -> v.getJsonValue().equals(r.sensitivity()));
        }
        for (Executability v : Executability.values())
        {
            byExecutability += count(r -> v.getJsonValue().equals(r.executability()));
        }
        int distinct = snapshot.assembledDistinct().size();
        assertEquals(distinct, byType, "the C.2 Variable_Universe census does not add up");
        assertEquals(distinct, bySensitivity, "the C.3 Sensitivity census does not add up");
        assertEquals(distinct, byExecutability, "the C.4 Executability census does not add up");
        assertEquals(snapshot.srcFiles(),
                snapshot.srcExecutability().values().stream().mapToInt(Integer::intValue).sum(),
                "the C.4 authored Executability census does not add up");
    }


    /**
     * The distinct-id projection the census publishes is only well defined if this holds.
     *
     * <p>
     * Every table in C.2–C.4 collapses the assembled fan-out (one entry per rule × package) down to
     * one row per {@code Core.Id}. If a rule declared, say, two different {@code Sensitivity}s in
     * two packages, the collapse would keep whichever package sorted first and the published number
     * would depend on file ordering.
     * </p>
     */
    @Test
    void classifyingFieldsAreInvariantPerRuleId()
    {
        assertEquals(List.of(), snapshot.inconsistentIds(),
                "these rule ids carry different Variable_Universe / Sensitivity / Executability"
                        + " values in"
                        + " different packages, so the census's distinct-id projection is"
                        + " ambiguous and its numbers depend on package ordering");
    }


    /**
     * The parked population really is absent from both shipped corpora.
     *
     * <p>
     * The C.1 line that reports it is the census's only claim about the {@code Not Executable}
     * parking mechanism (Fix #159); if the filter regressed, the line would keep asserting a
     * parking that no longer happens.
     * </p>
     */
    @Test
    void parkedRulesAreAbsentFromBothShippedCorpora()
    {
        assertFalse(snapshot.srcParkedIds().isEmpty(),
                "no authored rule is parked — the C.1 parking line would be vacuous; if this is"
                        + " intentional, delete the assertion rather than weakening it");
        for (String id : snapshot.srcParkedIds())
        {
            assertFalse(snapshot.assembledDistinct().containsKey(id), id
                    + " declares Executability \"Not Executable\" but still ships in"
                    + " the assembled view — the RuleStorageAssembler park filter regressed");
        }
        assertEquals(0,
                count(r -> Executability.NOT_EXECUTABLE.getJsonValue().equals(r.executability())),
                "a shipped rule declares \"Not Executable\"");
    }


    /** A malformed region must fail loudly, not silently pin half a document. */
    @Test
    void regionMarkersAreValidated()
    {
        assertThrows(IllegalArgumentException.class, () -> RuleDocCensus.region("no markers here"));
        assertThrows(IllegalArgumentException.class,
                () -> RuleDocCensus.region(RuleDocCensus.BEGIN_MARKER + "\nbody\n"));
        assertThrows(IllegalArgumentException.class,
                () -> RuleDocCensus.region(
                        RuleDocCensus.BEGIN_MARKER + "\na\n" + RuleDocCensus.END_MARKER + "\n"
                                + RuleDocCensus.BEGIN_MARKER + "\nb\n" + RuleDocCensus.END_MARKER));
        assertEquals(RuleDocCensus.BEGIN_MARKER + "\nx\n" + RuleDocCensus.END_MARKER,
                RuleDocCensus.region("intro\n" + RuleDocCensus.BEGIN_MARKER + "\nx\n"
                        + RuleDocCensus.END_MARKER + "\noutro"));
    }


    private static int count(Predicate<CensusRule> p)
    {
        return (int) snapshot.assembledDistinct().values().stream().filter(p).count();
    }

}
