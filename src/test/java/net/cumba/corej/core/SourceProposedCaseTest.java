package net.cumba.corej.core;

import static net.cumba.corej.core.SourceProposedCase.AS_DESCRIBED;
import static net.cumba.corej.core.SourceProposedCase.CORRECTION;
import static net.cumba.corej.core.SourceProposedCase.MISSING_ROW;
import static net.cumba.corej.core.SourceProposedCase.OUT_OF_SCOPE;
import static net.cumba.corej.core.SourceProposedCase.SPLIT;
import static net.cumba.corej.core.SourceProposedCase.classify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the plan's one decision function ({@code plans/done/PLAN-source-proposed-block.md}
 * §4 item 8).
 *
 * <p>
 * <b>Synthetic ids on purpose.</b> The corpus scan that consumes this classifier ships in phase 3;
 * it cannot establish that the classifier is <em>right</em>, because whatever answer the classifier
 * gives is the answer the corpus census reports. So the edge cases are exercised here, on
 * hand-built nodes, <b>in both directions</b> — each trap gets a case that must classify one way
 * and a near-twin that must classify the other.
 * </p>
 */
class SourceProposedCaseTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static JsonNode withSource(String id) throws IOException
    {
        return YAML.readTree("Core:\n  Id: \"" + id + "\"\nSource:\n  Domains: \"ALL\"\n");
    }


    private static JsonNode withoutSource(String id) throws IOException
    {
        return YAML.readTree("Core:\n  Id: \"" + id + "\"\n");
    }

    // ---------------------------------------------------------------- trap 1: the hyphen


    /**
     * ⭐ {@code -A} is minted, {@code A} is published. 85 corpus rules carry the published shape and
     * must not be read as splits; 54 carry the minted one.
     */
    @ParameterizedTest
    @CsvSource(
    {
            // minted sibling -> SPLIT
            "CDISC-AD0039-A, true", "FDA-SD2247-A, true", "CDISC-CG0014-B, true",
            "PMDA-AD0252-D, true", "FDA-SE2280-B, true",
            // published id ending in a letter -> NOT a sibling
            "PMDA-SD2247A, false", "FDA-SD0008C, false", "PMDA-AD0005B, false",
            "FDA-SD0063A, false", "CDISC-AD0537, false",
    })
    void onlyAHyphenatedSingleCapitalIsAMintedSibling(String id, boolean minted)
    {
        assertEquals(minted, SourceProposedCase.isMintedSibling(id), id);
    }


    @Test
    void aPublishedLetterSuffixedIdIsClassifiedByItsRowNotByItsSuffix() throws IOException
    {
        // PMDA-SD2247A is PMDA's own published id. Its near-twin FDA-SD2247-A is minted here.
        assertEquals(AS_DESCRIBED, classify(withSource("PMDA-SD2247A"), "PMDA-SD2247A"));
        assertEquals(SPLIT, classify(withoutSource("FDA-SD2247-A"), "FDA-SD2247-A"));
    }


    @Test
    void theParentOfASiblingIsItsIdLessTheSuffixAndNullOtherwise()
    {
        assertEquals("CDISC-AD0039", SourceProposedCase.parentOf("CDISC-AD0039-A"));
        assertEquals("FDA-SE2280", SourceProposedCase.parentOf("FDA-SE2280-B"));
        assertNull(SourceProposedCase.parentOf("PMDA-SD2247A"));
        assertNull(SourceProposedCase.parentOf("CDISC-SEND-0243.1"));
    }

    // ---------------------------------------------------------------- trap 2: the dot


    /**
     * ⚠ {@code CDISC-SEND-0243.1} is a SENDIG id fragment, not a coreJ suffix — 38 corpus rules
     * carry the shape. The register spells it {@code CDISC-SEND-0243-1} and is <b>wrong</b>; that
     * hyphenated spelling exists in no corpus file, so reading the register's spelling as a sibling
     * would invent a split that has no rule.
     */
    @ParameterizedTest
    @ValueSource(strings =
    {
            "CDISC-SEND-0243.1", "CDISC-SEND-0240.1", "CDISC-SEND-0353.1"
    })
    void aDottedSendIdIsNotASibling(String id) throws IOException
    {
        assertFalse(SourceProposedCase.isMintedSibling(id), id);
        assertEquals(AS_DESCRIBED, classify(withSource(id), id));
        // and the tail it yields cannot reach the minted band, which is the only use of the value
        assertTrue(SourceProposedCase.numericTail(id) < SourceProposedCase.MINTED_BAND_FLOOR, id);
    }

    // ---------------------------------------------------------------- trap 3: Core.Status


    /**
     * ⭐⭐ {@code Core.Status} is NOT consulted, and these three rules are why (plan §3.1 finding
     * {@code P0-F3}). The prefix count is 31 — the ruled figure; the {@code Core.Status} count is
     * 32. Classifying on status would take two rules that carry real published rows out of scope
     * and pull one draft in.
     */
    @Test
    void coreStatusIsIgnoredWhereItDisagreesWithTheIdPrefix() throws IOException
    {
        // Status: "Draft" but in a sheet-bearing family AND carrying a verbatim Source.
        JsonNode ad0898 = YAML.readTree("Core:\n  Id: \"CDISC-AD0898\"\n  Status: \"Draft\"\n"
                + "Source:\n  Class: \"ALL\"\n");
        assertEquals(AS_DESCRIBED, classify(ad0898, "CDISC-AD0898"));
        JsonNode pmdaAd0304 = YAML.readTree("Core:\n  Id: \"PMDA-AD0304\"\n  Status: \"Draft\"\n"
                + "Source:\n  Message: \"m\"\n");
        assertEquals(AS_DESCRIBED, classify(pmdaAd0304, "PMDA-AD0304"));

        // DRAFT- prefixed but Status: "Published" — still out of scope, because no sheet exists.
        JsonNode draft = YAML.readTree("Core:\n  Id: \"DRAFT-110004\"\n  Status: \"Published\"\n");
        assertEquals(OUT_OF_SCOPE, classify(draft, "DRAFT-110004"));
    }

    // ---------------------------------------------------------------- the minted band


    /**
     * ⛔ The three published {@code 9999}s are the reason the band test reads the {@code Source} and
     * not a name list. Owner ruling A (2026-08-25) WITHDREW the ruling that had called their
     * {@code Source} blocks fabricated: {@code UNRECOGNIZED} occurs in two workbooks in exactly
     * those cells, and {@code SD9999} / {@code AD9999} are published ids. They keep their
     * {@code Source}, owe no proposal and get no entry — which falls out of "has a Source" without
     * anyone maintaining an exception list.
     */
    @ParameterizedTest
    @ValueSource(strings =
    {
            "FDA-SD9999", "PMDA-SD9999", "PMDA-AD9999"
    })
    void aBandIdThatCarriesAPublishedRowIsNotMissingItsRow(String id) throws IOException
    {
        assertTrue(SourceProposedCase.numericTail(id) >= SourceProposedCase.MINTED_BAND_FLOOR, id);
        assertEquals(AS_DESCRIBED, classify(withSource(id), id));
    }


    /**
     * ⭐ Only the NINE dataset/record-presence rules are genuinely missing their row (ruling I).
     * Every one of them is a "this dataset must exist" check, and the catalogue publishes that
     * shape for MB / PC / DM / SUPP-- / RELREC / TM — just not for TS, EX or TV.
     */
    @ParameterizedTest
    @ValueSource(strings =
    {
            "FDA-SD9713", "PMDA-SD9713", "CDISC-CG9671", "CDISC-CG9667", "CDISC-CG9668",
            "CDISC-SEND-9423", "CDISC-SEND-9424", "CDISC-SEND-9425", "CDISC-AD9701"
    })
    void onlyAPresenceRuleWithNoPublishedRowIsAMissingRow(String id) throws IOException
    {
        assertEquals(MISSING_ROW, classify(withoutSource(id), id));
    }


    /**
     * ⛔⛔ The 31 variable-population rules in the same band are <b>splits</b>, not missing rows —
     * the ruling that stopped 31 escalations against rows that exist. Each operationalises a
     * generic published row so it can run without the CDISC Library.
     *
     * <p>
     * ⚠ Both directions matter here, which is why this test asserts against the near-twin: {@code
     * FDA-SD9713} sits in the same band, in the same family, with the same absent {@code Source} —
     * and is a MISSING_ROW, because it is a dataset-presence rule with no published counterpart.
     * </p>
     */
    @ParameterizedTest
    @ValueSource(strings =
    {
            "FDA-SD9701", "FDA-SD9712", "FDA-SE9702", "PMDA-SD9701", "PMDA-SD9712", "CDISC-CG9670",
            "CDISC-SEND-9430", "CDISC-SEND-9436"
    })
    void aBandRuleOperationalisingAGenericRowIsASplit(String id) throws IOException
    {
        assertEquals(SPLIT, classify(withoutSource(id), id));
        assertNotNull(SourceProposedCase.parentOf(id), id + " must name the row it splits");
    }


    @Test
    void theTwoBandPopulationsPartitionTheFortyAndNeitherIsEmpty()
    {
        assertEquals(31, SourceProposedCase.GENERIC_ROW_SPLITS.size());
        assertEquals(9, SourceProposedCase.MISSING_ROW_RULES.size());
        for (String id : SourceProposedCase.MISSING_ROW_RULES)
        {
            assertFalse(SourceProposedCase.GENERIC_ROW_SPLITS.containsKey(id),
                    id + " is in both band populations");
        }
        // every generic-row split names one of the four published generic rows, and no other
        assertEquals(java.util.Set.of("SD0002", "CG0014", "SEND12"),
                java.util.Set.copyOf(SourceProposedCase.GENERIC_ROW_SPLITS.values()));
    }


    @ParameterizedTest
    @CsvSource(
    {
            "FDA-SD9701, 9701", "CDISC-SEND-9436, 9436", "FDA-SD0008C, 8", "FDA-SD2247-A, 2247",
            "CDISC-AD0039, 39", "PMDA-SD2247A, 2247"
    })
    void theNumericTailReadsPastBothSuffixShapes(String id, int tail)
    {
        assertEquals(tail, SourceProposedCase.numericTail(id), id);
    }


    /** A rule just below the band floor is an ordinary published rule, not a minted one. */
    @Test
    void theBandFloorIsExclusiveBelow() throws IOException
    {
        assertEquals(AS_DESCRIBED, classify(withoutSource("FDA-SD8999"), "FDA-SD8999"));
        assertEquals(MISSING_ROW, classify(withoutSource("FDA-SD9000"), "FDA-SD9000"));
    }

    // ---------------------------------------------------------------- out of scope, and precedence


    @ParameterizedTest
    @ValueSource(strings =
    {
            "CORE-000097", "CORE-009706", "CORE-000505-A", "DRAFT-900026", "DRAFT-110004"
    })
    void coreAndDraftAreOutOfScopeWhateverElseTheyLookLike(String id) throws IOException
    {
        assertEquals(OUT_OF_SCOPE, classify(withoutSource(id), id));
    }


    /**
     * ⭐ Precedence is load-bearing, not incidental. {@code CORE-000505-A} is BOTH a minted sibling
     * and a {@code CORE} rule, and {@code CORE-009706} is BOTH in the band and named by a register
     * correction proposal — ruling F puts {@code CORE} first in both cases, which is what removes
     * 536 rules "in one sentence" and is why the 9 {@code CORE} siblings and 6 {@code CORE-0097xx}
     * band members never reach the seeding population.
     */
    @Test
    void outOfScopeOutranksEveryOtherCase() throws IOException
    {
        assertTrue(SourceProposedCase.isMintedSibling("CORE-000505-A"));
        assertEquals(OUT_OF_SCOPE, classify(withoutSource("CORE-000505-A"), "CORE-000505-A"));

        assertTrue(
                SourceProposedCase.CORRECTION_RULES.stream()
                        .noneMatch(SourceProposedCase::isOutOfScope),
                "ruling F: no CORE/DRAFT id may be seeded");
        assertEquals(OUT_OF_SCOPE, classify(withoutSource("CORE-009706"), "CORE-009706"));
    }


    /**
     * A split child keeps its case whether or not it has been given the parent's row yet: 36 of the
     * 45 carry a {@code Source} today and 9 do not, and phase 3b restores 7 of the 9 by tool.
     */
    @Test
    void aSplitIsASplitWithOrWithoutARestoredSource() throws IOException
    {
        assertEquals(SPLIT, classify(withSource("CDISC-AD0039-A"), "CDISC-AD0039-A"));
        assertEquals(SPLIT, classify(withoutSource("CDISC-CG0285-A"), "CDISC-CG0285-A"));
    }

    // ---------------------------------------------------------------- the correction population


    /**
     * ⭐ 25 — 29 less the seven ruling J removed, plus three whose proposal sits in a Status cell.
     * owner ruling J removed the seven rules whose register entry raised a <em>question</em> rather
     * than naming a corrected cell, each measured against its own {@code Source} and found to
     * implement it. Membership is <b>rule-first</b>: an entry existing does not put a rule in this
     * population; the rule must actually diverge from its row.
     */
    @Test
    void theCorrectionPopulationIsTwentyFiveNamedRulesAndNoneOverlapsAnotherCase()
        throws IOException
    {
        assertEquals(25, SourceProposedCase.CORRECTION_RULES.size());
        for (String id : SourceProposedCase.CORRECTION_RULES)
        {
            assertFalse(SourceProposedCase.isMintedSibling(id), id + " would classify as SPLIT");
            assertTrue(SourceProposedCase.numericTail(id) < SourceProposedCase.MINTED_BAND_FLOOR,
                    id + " would classify as MISSING_ROW");
            assertEquals(CORRECTION, classify(withSource(id), id), id);
        }
    }


    /**
     * ⚠ The one shape the enumeration cannot express, stated so it is a known limit rather than a
     * surprise: a rule whose sheet row is wrong but which <em>no</em> register entry has a written
     * proposal for classifies {@code AS_DESCRIBED}. That is the ruled boundary (open question 3) —
     * the discovery hunt that would find such rules is a separate effort, and the population grows
     * when it runs, not before.
     */
    @Test
    void anUnreportedSheetDefectClassifiesAsDescribed() throws IOException
    {
        assertEquals(AS_DESCRIBED, classify(withSource("CDISC-CG0001"), "CDISC-CG0001"));
    }


    /**
     * ⛔ The seven ruling-J removals, pinned BY NAME so the population cannot drift back.
     *
     * <p>
     * Six implement their sheet row outright; {@code PMDA-SD1029} fails the test in the other
     * direction (it asserts <em>less</em> than its row) and is out because {@code Source_Proposed}
     * records a row we wish were different, not a rule we know is incomplete.
     * </p>
     */
    @ParameterizedTest
    @ValueSource(strings =
    {
            "FDA-SD1135", "FDA-SD1340", "PMDA-SD1340", "FDA-SD1029", "PMDA-SD1029", "FDA-SD1023",
            "CDISC-SEND-0103"
    })
    void aRuleThatAlreadyImplementsItsSheetRowIsNotACorrection(String id) throws IOException
    {
        assertFalse(SourceProposedCase.CORRECTION_RULES.contains(id), id);
        assertEquals(AS_DESCRIBED, classify(withSource(id), id));
    }
}
