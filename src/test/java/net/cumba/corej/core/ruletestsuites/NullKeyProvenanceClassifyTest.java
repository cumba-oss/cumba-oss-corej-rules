package net.cumba.corej.core.ruletestsuites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.ruletestsuites.NullKeyProvenanceDifferentialTest.MoveClass;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * The expected-movement contract of {@link NullKeyProvenanceDifferentialTest}, judged cell by cell
 * on every build ({@code PLAN-output-variable-null-key-provenance} T2): the differential itself is
 * opt-in (it needs a base dump), so its classifier is pinned here — each allowed class accepts its
 * movement and nothing beside it, so a widened class reds without a dump.
 */
class NullKeyProvenanceClassifyTest
{

    private static final String MIS = MissingValue.MIS.toString();

    private static final Set<String> COLS = cols("DOMAIN", "AETERM");

    private static Set<String> cols(String... names)
    {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        out.addAll(Set.of(names));
        return out;
    }


    private static MoveClass classify(String key, boolean inB, String vb, boolean inC, String vc,
            ExprType bindingType)
    {
        return NullKeyProvenanceDifferentialTest.classify(key, inB, vb, inC, vc, COLS, "cdt",
                bindingType);
    }


    @Test
    void aBindingDefaultIsJudgedByItsStaticType()
    {
        // T2 LOW-7: "" only for a string binding, "." only for a numeric / boolean / list one.
        assertEquals(MoveClass.CHAR_NULL_TO_EMPTY,
                classify("$s", true, null, true, "", ExprType.Primitive.STRING));
        assertNull(classify("$n", true, null, true, "", ExprType.Primitive.NUMBER),
                "a numeric binding printing \"\" is CONTENT");
        assertEquals(MoveClass.NONCHAR_NULL_TO_MIS,
                classify("$n", true, null, true, MIS, ExprType.Primitive.NUMBER));
        assertNull(classify("$s", true, null, true, MIS, ExprType.Primitive.STRING),
                "a string binding printing \".\" is CONTENT");
        assertEquals(MoveClass.RECORD_EMPTY_TO_MIS,
                classify("$b", true, "", true, MIS, ExprType.Primitive.BOOLEAN));
        assertNull(classify("$s", true, "", true, MIS, ExprType.Primitive.STRING),
                "a string binding moving \"\" -> \".\" is CONTENT");
        // OPEN-T: a binding stage A cannot type may print either — its evaluated type decides.
        assertEquals(MoveClass.CHAR_NULL_TO_EMPTY, classify("$u", true, null, true, "", null));
        assertEquals(MoveClass.NONCHAR_NULL_TO_MIS, classify("$u", true, null, true, MIS, null));
    }


    @Test
    void aColumnEntryWithANullKeyIsOmittedAndADerivedOmissionDoesNotMove()
    {
        assertEquals(MoveClass.PRESENT_COLUMN_NULL_OMITTED,
                classify("DOMAIN", true, null, false, null, null));
        assertEquals(MoveClass.ABSENT_COLUMN_NULL_OMITTED,
                classify("NOPE", true, null, false, null, null));
        assertNull(classify("DOMAIN", false, null, true, null, null),
                "an omitted column entry that turns into a null key is CONTENT");
        assertNull(classify("variable_label", true, null, false, null, null),
                "a metadata entry is never omitted");
    }


    @Test
    void anUnspecifiedLengthMovesOnlyFromZeroToTheMissingMarker()
    {
        assertEquals(MoveClass.UNSPECIFIED_LENGTH_TO_MIS,
                classify("variable_length", true, "0", true, MIS, null));
        assertNull(classify("variable_length", true, "8", true, MIS, null),
                "a declared length that turns into \".\" is CONTENT");
        assertNull(classify("library_variable_length", true, "0", true, MIS, null),
                "only the data-level length had the unspecified-length 0");
    }


    @Test
    void aProjectionGapResolvesOnlyToARealValue()
    {
        assertEquals(MoveClass.PROJECTION_GAP_RESOLVED,
                classify("variable_size", true, null, true, "8", null));
        assertEquals(MoveClass.PROJECTION_GAP_RESOLVED,
                classify("define_vlm_length", false, null, true, "3", null));
        assertEquals(MoveClass.NONCHAR_NULL_TO_MIS,
                classify("variable_size", true, null, true, MIS, null),
                "a gap with no value is the typed default, not a resolution");
        assertNull(classify("variable_size", true, "8", true, "9", null),
                "a value that changes is CONTENT");
    }
}
