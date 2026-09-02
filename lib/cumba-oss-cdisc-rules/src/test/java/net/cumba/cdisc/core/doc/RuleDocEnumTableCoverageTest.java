package net.cumba.cdisc.core.doc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import net.cumba.cdisc.core.model.Executability;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.cdisc.core.model.VariableUniverse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Keeps the specification's hand-written enumerated-field tables in sync with the code enums — the
 * {@code ExpressionCheckSpecDriftTest} pattern, applied to the three fields whose documentation
 * carries prose a generator cannot write.
 *
 * <p>
 * <b>Why a second mechanism, next to {@link RuleDocCensusDriftTest}?</b> The census region already
 * lists every constant, so a new constant already fails <em>that</em> test. But the fix there is
 * one command — regenerate — and the result is a row of numbers. {@code Variable_Universe},
 * {@code Sensitivity} and {@code Executability} are the three fields a rule author chooses by hand,
 * from a table whose value is its <em>description</em> column. This test makes adding a constant
 * require writing that description: it evaluates containment against the document with the
 * generated region **removed**, so the generator's own roster cannot satisfy it.
 * </p>
 *
 * <p>
 * The check is deliberately backtick-delimited (`` `Record Data` ``, the form the tables use)
 * rather than bare-substring. Bare substrings would let the longer constant
 * {@code Dataset Metadata Check against Define XML} satisfy the shorter
 * {@code Dataset Metadata Check}, and would count an incidental mention in narrative prose as
 * documentation.
 * </p>
 */
class RuleDocEnumTableCoverageTest
{

    /** The specification with its generated census region removed. */
    private static String handWritten;

    @BeforeAll
    static void read() throws IOException
    {
        handWritten = RuleDocCensus.withoutRegion(Files.readString(RuleDocCensus.documentFile()));
    }


    @Test
    void everyVariableUniverseIsDocumented()
    {
        for (VariableUniverse u : VariableUniverse.values())
        {
            assertDocumented(u.getJsonValue(), "Variable_Universe", "Field: `Variable_Universe`");
        }
    }


    @Test
    void everySensitivityIsDocumented()
    {
        for (Sensitivity v : Sensitivity.values())
        {
            assertDocumented(v.getJsonValue(), "Sensitivity", "Field: `Sensitivity`");
        }
    }


    @Test
    void everyExecutabilityIsDocumented()
    {
        for (Executability v : Executability.values())
        {
            assertDocumented(v.getJsonValue(), "Executability", "Field: `Executability`");
        }
    }


    /**
     * The generated region really is being stripped.
     *
     * <p>
     * If {@code withoutRegion} ever became a no-op the three tests above would still pass — the
     * census lists every constant — and the pin would be silently vacuous. This is the
     * neuter-and-watch guard baked in: the census's own marker comment must be gone, and the
     * hand-written half must still be most of the document.
     * </p>
     */
    @Test
    void theGeneratedRegionIsExcludedFromTheCheck()
    {
        assertTrue(!handWritten.contains(RuleDocCensus.BEGIN_MARKER),
                "the generated region was not stripped — these pins would be self-satisfying");
        assertTrue(!handWritten.contains("### C.7 `BuiltinRegistry`"),
                "the generated region's body survived the strip");
        assertTrue(handWritten.contains("## Field: `Variable_Universe`"),
                "the strip removed too much — the hand-written field sections are gone");
    }


    private static void assertDocumented(String jsonValue, String field, String section)
    {
        assertTrue(handWritten.contains("`" + jsonValue + "`"),
                "undocumented " + field + " value in CORE-RULES-SPECIFICATION.md: `" + jsonValue
                        + "`. Add a row to the \"" + section + "\" table, in backticks, with a"
                        + " description of what the value means — the generated census region does"
                        + " not count, deliberately: this field is chosen by a human from a table"
                        + " whose value is its description column.");
    }

}
