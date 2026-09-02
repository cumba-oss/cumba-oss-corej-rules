package net.cumba.cdisc.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Locks the byte-stable form of {@link CanonicalViolation} JSON output.
 *
 * <p>
 * The Python-side {@code canonical.py} once had a mirror test emitting exactly the same strings for
 * the same logical inputs; it was cut with the Python lane in wave 33, and the update-both-fixtures
 * lock-step duty went with it. This test is the sole remaining pin.
 *
 * <p>
 * The byte-stable form still matters single-lane: every {@code expected_violations} entry in the
 * spec corpus is compared through this serialisation, so a change to the JSON shape (field order,
 * key style) re-keys every diff and must be treated as a corpus-wide contract change, not a
 * formatting tweak.
 */
class CanonicalViolationTest
{

    @Test
    void minimalViolation_serialisesInCanonicalFieldOrder()
    {
        CanonicalViolation v = CanonicalViolation.of("CORE-000208", "TS",
                Map.of("STUDYID", "CDISCPILOT01", "TSSEQ", 3, "TSPARMCD", "DOSE"),
                Map.of("TSSEQ", "3", "TSPARMCD", "DOSE", "TSVCDVER", "2099-01-01"));

        String expected = "{\"rule_id\":\"CORE-000208\"," + "\"dataset\":\"TS\","
                + "\"row_key\":{\"STUDYID\":\"CDISCPILOT01\",\"TSPARMCD\":\"DOSE\",\"TSSEQ\":3},"
                + "\"status\":\"SUCCESS\","
                + "\"output_variables\":{\"TSPARMCD\":\"DOSE\",\"TSSEQ\":\"3\",\"TSVCDVER\":\"2099-01-01\"}}";
        assertEquals(expected, CanonicalJson.toJson(v));
    }


    @Test
    void emptyMaps_serialiseAsEmptyObjects()
    {
        CanonicalViolation v = CanonicalViolation.of("R", "DM", Map.of(), Map.of());
        String expected = "{\"rule_id\":\"R\",\"dataset\":\"DM\",\"row_key\":{},"
                + "\"status\":\"SUCCESS\",\"output_variables\":{}}";
        assertEquals(expected, CanonicalJson.toJson(v));
    }


    @Test
    void errorMessage_includedWhenPresent_omittedWhenNull()
    {
        CanonicalViolation withMessage = new CanonicalViolation("R", "AE", Map.of(),
                CanonicalViolation.Status.ERROR, Map.of(), "operator unknown");
        String expectedWith = "{\"rule_id\":\"R\",\"dataset\":\"AE\",\"row_key\":{},"
                + "\"status\":\"ERROR\",\"output_variables\":{},"
                + "\"error_message\":\"operator unknown\"}";
        assertEquals(expectedWith, CanonicalJson.toJson(withMessage));

        CanonicalViolation withoutMessage = CanonicalViolation.of("R", "AE", Map.of(), Map.of());
        // status defaults to SUCCESS via .of()
        String expectedWithout = "{\"rule_id\":\"R\",\"dataset\":\"AE\",\"row_key\":{},"
                + "\"status\":\"SUCCESS\",\"output_variables\":{}}";
        assertEquals(expectedWithout, CanonicalJson.toJson(withoutMessage));
    }


    @Test
    void rowKey_keysAlwaysSortedAlphabetically()
    {
        // Insertion order shouldn't matter — output must be alphabetically ordered.
        java.util.LinkedHashMap<String, Object> rowKey = new java.util.LinkedHashMap<>();
        rowKey.put("ZZZ", "last");
        rowKey.put("AAA", "first");
        rowKey.put("MMM", "middle");

        CanonicalViolation v = CanonicalViolation.of("R", "X", rowKey, Map.of());
        String json = CanonicalJson.toJson(v);
        // Must contain row_key in alphabetical order, regardless of insertion order.
        org.junit.jupiter.api.Assertions.assertTrue(
                json.contains(
                        "\"row_key\":{\"AAA\":\"first\",\"MMM\":\"middle\",\"ZZZ\":\"last\"}"),
                () -> "Expected row_key alphabetical ordering, got: " + json);
    }
}
