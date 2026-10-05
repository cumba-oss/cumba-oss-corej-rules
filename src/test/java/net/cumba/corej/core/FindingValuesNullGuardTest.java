package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * The sensitivity of {@link FindingValuesNullGuard}
 * ({@code PLAN-output-variable-null-key-provenance} §2.1 / T2;
 * {@code PLAN-finding-values-record-level-only} V8 (b)): the guard's judgement is proven on every
 * run — a synthetic violation with null values is reported key by key; a {@code ""} and a {@code .}
 * are values and never reported; a name-only list that holds a null, a blank, a binding, a metadata
 * name or a name the values map also carries is reported entry by entry; the reach counter counts
 * every call.
 *
 * <p>
 * ⚑ Until {@code PLAN-finding-values-record-level-only} this class also asserted 5b's hand-over
 * list {@code nullkey/present-column-omitted.tsv} against its static derivation. That state — a
 * present raw column on a column-level finding — is listed by name now
 * ({@code Violation.getNamedColumns()}), the list is retired, and
 * {@code RuleRunnerNameOnlyProjectionTest} in {@code cumba-corej} pins the behaviour on
 * CDISC-CG0349's shape.
 * </p>
 */
class FindingValuesNullGuardTest
{

    @Test
    void theGuardReportsEveryNullAndAcceptsValues()
    {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("variable_name", "AETERM");
        values.put("variable_label", "");
        values.put("variable_length", ".");
        values.put("DOMAIN", null);
        values.put("variable_value", null);
        List<Violation> violations = List
                .of(new Violation(0, values, null, null, Map.of(), null, null));

        List<String> offenders = FindingValuesNullGuard.offenders("CDISC-CG0349", violations);

        assertEquals(2, offenders.size(), offenders.toString());
        assertTrue(offenders.get(0).startsWith("CDISC-CG0349/DOMAIN "), offenders.toString());
        assertTrue(offenders.get(1).startsWith("CDISC-CG0349/variable_value "),
                offenders.toString());
        assertTrue(FindingValuesNullGuard.offenders("R-1", null).isEmpty());
        assertTrue(FindingValuesNullGuard.offenders("R-1", List.of()).isEmpty());

        long before = FindingValuesNullGuard.reach("sensitivity");
        AssertionError red = assertThrows(AssertionError.class, () -> FindingValuesNullGuard
                .requireNullFree("sensitivity", "plant", rule("CDISC-CG0349"), violations));
        assertTrue(red.getMessage().contains("CDISC-CG0349/DOMAIN"), red.getMessage());
        Map<String, String> clean = new LinkedHashMap<>(values);
        clean.remove("DOMAIN");
        clean.remove("variable_value");
        FindingValuesNullGuard.requireNullFree("sensitivity", "clean", rule("CDISC-CG0349"),
                List.of(new Violation(0, clean, null, null, Map.of(), null, null)));
        assertEquals(before + 2, FindingValuesNullGuard.reach("sensitivity"),
                "every call is counted, a red one too");
        assertThrows(AssertionError.class, () -> FindingValuesNullGuard.requireReach("sensitivity",
                before, 3, "the sensitivity control"));
        FindingValuesNullGuard.requireReach("sensitivity", before, 2, "the sensitivity control");
    }


    @Test
    void theGuardJudgesTheNameOnlyListEntryByEntry()
    {
        // The real state CDISC-CG0349 reaches: variable_name valued, DOMAIN listed by name.
        Map<String, String> values = new LinkedHashMap<>();
        values.put("variable_name", "AETERM");
        Violation listed = new Violation(0, values, null, null, Map.of(), null, null, null,
                List.of("DOMAIN"));
        assertTrue(FindingValuesNullGuard.offenders("CDISC-CG0349", List.of(listed)).isEmpty(),
                "a present raw column listed by name is the V8 (b) state");

        // Every way the list can be wrong, each named.
        assertEquals("is null", FindingValuesNullGuard.nameOnlyOffence(null, values));
        assertEquals("is blank", FindingValuesNullGuard.nameOnlyOffence(" ", values));
        assertTrue(FindingValuesNullGuard.nameOnlyOffence("$n", values).contains("binding"));
        assertTrue(FindingValuesNullGuard.nameOnlyOffence("variable_label", values)
                .contains("metadata"));
        assertTrue(
                FindingValuesNullGuard.nameOnlyOffence("record_count", values).contains("virtual"));
        assertTrue(FindingValuesNullGuard.nameOnlyOffence("variable_name", values)
                .contains("metadata"));
        assertEquals("and valued at once",
                FindingValuesNullGuard.nameOnlyOffence("AESEV", Map.of("AESEV", "MILD")));
        assertNull(FindingValuesNullGuard.nameOnlyOffence("DM.SEX", values),
                "a dotted joined column is a raw column");

        Map<String, String> both = new LinkedHashMap<>(values);
        both.put("AESEV", "MILD");
        Violation planted = new Violation(0, both, null, null, Map.of(), null, null, null,
                Arrays.asList("AESEV", "$n", null, "DOMAIN"));
        List<String> offenders = FindingValuesNullGuard.offenders("R-1", List.of(planted));
        assertEquals(3, offenders.size(), offenders.toString());
        assertTrue(offenders.get(0).startsWith("R-1/AESEV listed by name and valued at once"),
                offenders.toString());
        assertTrue(offenders.get(1).startsWith("R-1/$n listed by name is a binding"),
                offenders.toString());
        assertTrue(offenders.get(2).startsWith("R-1/null listed by name is null"),
                offenders.toString());
        AssertionError red = assertThrows(AssertionError.class, () -> FindingValuesNullGuard
                .requireNullFree("sensitivity", "plant", rule("R-1"), List.of(planted)));
        assertTrue(red.getMessage().contains("R-1/AESEV"), red.getMessage());
    }


    private static Rule rule(String coreId)
    {
        Rule r = new Rule();
        net.cumba.corej.core.model.RuleCore core = new net.cumba.corej.core.model.RuleCore();
        core.setId(coreId);
        r.setCore(core);
        return r;
    }
}
