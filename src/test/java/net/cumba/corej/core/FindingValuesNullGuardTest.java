package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import org.junit.jupiter.api.Test;

/**
 * The sensitivity of {@link FindingValuesNullGuard}
 * ({@code PLAN-output-variable-null-key-provenance} §2.1 / T2;
 * {@code PLAN-finding-values-record-level-only} V8 (b)): the guard's judgement is proven on every
 * run — a synthetic violation with null values is reported key by key; a {@code ""} and a {@code .}
 * are values and never reported; a name-only list that holds a null, a blank, a dataset-scope
 * binding, a metadata name, a name twice or a name the values map also carries is reported entry by
 * entry; a raw column or a per-row binding VALUED on a non-record finding is reported (P1 review
 * M-1 (a), owner Q-C); the reach counter counts every call.
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

    /** No binding is per-row: a {@code $} reference is never empty, so this answers false. */
    private static boolean noPerRow(String ref)
    {
        return ref.isEmpty();
    }


    @Test
    void theGuardReportsEveryNullAndAcceptsValues()
    {
        // Since P2 review MED-1 (b) a Violation cannot hold a null value (its constructor refuses
        // it), so the null half is planted on the per-violation judgement directly.
        Map<String, String> values = new LinkedHashMap<>();
        values.put("variable_name", "AETERM");
        values.put("variable_label", "");
        values.put("variable_length", ".");
        values.put("DOMAIN", null);
        values.put("variable_value", null);

        List<String> offenders = FindingValuesNullGuard.entryOffenders("CDISC-CG0349", 0, values,
                List.of(), false, FindingValuesNullGuardTest::noPerRow);

        assertEquals(2, offenders.size(), offenders.toString());
        assertTrue(offenders.get(0).startsWith("CDISC-CG0349/DOMAIN "), offenders.toString());
        assertTrue(offenders.get(1).startsWith("CDISC-CG0349/variable_value "),
                offenders.toString());
        assertThrows(NullPointerException.class,
                () -> new Violation(0, values, null, null, Map.of(), null, null),
                "the producer refuses the null itself (MED-1 (b))");
        assertTrue(
                FindingValuesNullGuard.offenders(rule("R-1", Sensitivity.RECORD), null).isEmpty());
        assertTrue(FindingValuesNullGuard.offenders(rule("R-1", Sensitivity.RECORD), List.of())
                .isEmpty());

        long before = FindingValuesNullGuard.reach("sensitivity");
        Violation recordFinding = new Violation(0, Map.of("AETERM", "H"), "S1", "1", Map.of(), null,
                new Violation.Unit.Row(0));
        AssertionError red = assertThrows(AssertionError.class,
                () -> FindingValuesNullGuard.requireNullFree("sensitivity", "plant",
                        rule("CDISC-CG0349", Sensitivity.DATASET), List.of(recordFinding)));
        assertTrue(red.getMessage().contains("CDISC-CG0349/AETERM valued on a non-record"),
                red.getMessage());
        FindingValuesNullGuard.requireNullFree("sensitivity", "clean",
                rule("CDISC-CG0349", Sensitivity.RECORD), List.of(recordFinding));
        assertEquals(before + 2, FindingValuesNullGuard.reach("sensitivity"),
                "every call is counted, a red one too");
        assertThrows(AssertionError.class, () -> FindingValuesNullGuard.requireReach("sensitivity",
                before, 3, "the sensitivity control"));
        FindingValuesNullGuard.requireReach("sensitivity", before, 2, "the sensitivity control");
    }


    /**
     * ⛔⛔ PLANTED SENSITIVITY TEST (P1 review M-1 (a)): a raw column VALUED on a non-record finding
     * — what P1's projection must never produce — is reported, on each way the guard recognises
     * such a finding; a record finding, a Group finding (D94c's distinct sets, Q-A), the
     * {@code __error__} sentinel and the finding's own values (a dataset-scope binding, a dataset
     * fact, metadata) are not.
     */
    @Test
    void aRawColumnValuedOnANonRecordFindingIsReported()
    {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("record_count", "2");
        values.put("$n", "2");
        values.put("AETERM", "Headache");
        Rule dataset = rule("R-DS", Sensitivity.DATASET);
        Rule study = rule("R-ST", Sensitivity.STUDY);
        Rule record = rule("R-REC", Sensitivity.RECORD);
        Violation plain = new Violation(0, values, null, null, Map.of(), null, null);

        // Dataset / Study sensitivity, no group key.
        assertEquals(1, FindingValuesNullGuard.offenders(dataset, List.of(plain)).size());
        assertEquals(1, FindingValuesNullGuard.offenders(study, List.of(plain)).size());
        // A Record rule's finding stamped as the dataset unit (the fold, M1).
        Violation folded = new Violation(0, values, null, null, Map.of(), null,
                Violation.Unit.DATASET, null, List.of());
        assertEquals(1, FindingValuesNullGuard.offenders(record, List.of(folded)).size());
        // A Record rule's finding that lists something by name.
        Map<String, String> partly = new LinkedHashMap<>(values);
        Violation listing = new Violation(0, partly, null, null, Map.of(), null, null, null,
                List.of("AESEV"));
        List<String> offenders = FindingValuesNullGuard.offenders(record, List.of(listing));
        assertEquals(1, offenders.size(), offenders.toString());
        assertTrue(offenders.get(0).startsWith("R-REC/AETERM valued on a non-record finding"),
                offenders.toString());

        // Controls.
        assertTrue(FindingValuesNullGuard.offenders(record, List.of(plain)).isEmpty(),
                "a record finding prints its row's values");
        Violation grouped = new Violation(0, values, "S1", "1", Map.of(), null, null,
                Map.of("USUBJID", "S1"));
        assertTrue(FindingValuesNullGuard.offenders(dataset, List.of(grouped)).isEmpty(),
                "a Group finding keeps D94c's distinct sets (Q-A)");
        assertTrue(FindingValuesNullGuard
                .offenders(dataset, List.of(new Violation(0, Map.of("__error__", "boom"))))
                .isEmpty(), "the engine-error sentinel is no projected entry");
        Map<String, String> own = new LinkedHashMap<>(values);
        own.remove("AETERM");
        assertTrue(
                FindingValuesNullGuard.offenders(dataset,
                        List.of(new Violation(0, own, null, null, Map.of(), null, null, null,
                                List.of("AETERM"))))
                        .isEmpty(),
                "the finding's own values and the raw column by name: the V1 state");
    }


    @Test
    void aPerRowBindingIsNameOnlyOnANonRecordFindingQc() throws Exception
    {
        // Owner Q-C: a per-row binding is listed by name on a non-record finding, never valued; a
        // dataset-scope binding keeps its value and is never listed.
        Rule rule = RulePackageLoader.loadFromString("""
                {"rules":{"R1":{"Core":{"Id":"R-QC"},"Sensitivity":"Dataset",
                 "Bindings":[{"name":"$end","expression":"AEENDTC"},
                             {"name":"$n","expression":"record_count()"}],
                 "Check":{"expression":"not var_exists(\\"AESTDTC\\")"},
                 "Outcome":{"Message":"m","Output_Variables":["$end","$n"]}}}}""").getRules()
                .get("R1");
        assertNull(rule.getLoadError(), rule.getLoadError());

        Violation listed = new Violation(0, Map.of("$n", "2"), null, null, Map.of(), null, null,
                null, List.of("$end"));
        assertTrue(FindingValuesNullGuard.offenders(rule, List.of(listed)).isEmpty(),
                "the Q-C state");
        Violation valued = new Violation(0, Map.of("$end", "2020-01-01", "$n", "2"), null, null,
                Map.of(), null, null);
        List<String> offenders = FindingValuesNullGuard.offenders(rule, List.of(valued));
        assertEquals(1, offenders.size(), offenders.toString());
        assertTrue(
                offenders.get(0).startsWith(
                        "R-QC/$end valued on a non-record finding — a" + " per-row binding (Q-C)"),
                offenders.toString());
        Violation datasetScopeListed = new Violation(0, Map.of(), null, null, Map.of(), null, null,
                null, List.of("$n"));
        offenders = FindingValuesNullGuard.offenders(rule, List.of(datasetScopeListed));
        assertEquals(1, offenders.size(), offenders.toString());
        assertTrue(offenders.get(0).contains("$n listed by name is a dataset-scope binding"),
                offenders.toString());
    }


    /**
     * ⛔⛔ PLANTED SENSITIVITY TEST (Q-C on the column-level path): a {@code {VAR}} Record rule's
     * finding has no row, so a per-row binding VALUED on it — what {@code buildVariableViolation}
     * printed before the follow-up (the binding's {@code Vector}) — is reported even when nothing
     * else is listed by name, the case the named / unit / sensitivity arms cannot see. The same
     * finding of a {@code {VAR,ROW}} rule is a (column, row) record finding and is not reported.
     */
    @Test
    void aPerRowBindingValuedOnAColumnLevelFindingIsReportedQc() throws Exception
    {
        Rule columnLevel = RulePackageLoader.loadFromString("""
                {"rules":{"R1":{"Core":{"Id":"R-VAR"},"Sensitivity":"Record",
                 "Bindings":[{"name":"$end","expression":"AEENDY"}],
                 "Check":{"expression":"empty(var_format(\\"DATA\\"))"},
                 "Outcome":{"Message":"m","Output_Variables":["variable_name","$end"]}}}}""")
                .getRules().get("R1");
        Rule perCell = RulePackageLoader.loadFromString("""
                {"rules":{"R1":{"Core":{"Id":"R-CELL"},"Sensitivity":"Record",
                 "Bindings":[{"name":"$end","expression":"AEENDY"}],
                 "Check":{"expression":"empty(var_format(\\"DATA\\")) and len(value()) > 3"},
                 "Outcome":{"Message":"m","Output_Variables":["variable_name","$end"]}}}}""")
                .getRules().get("R1");
        assertNull(columnLevel.getLoadError(), columnLevel.getLoadError());
        assertNull(perCell.getLoadError(), perCell.getLoadError());
        assertEquals(net.cumba.corej.core.expr.eval.Domain.VARIABLE,
                columnLevel.getEvaluationDomain());
        assertTrue(perCell.getEvaluationDomain().rowCursor(), "the control is row-bearing");

        Violation valued = new Violation(0, Map.of("variable_name", "AETERM", "$end", "2"));
        List<String> offenders = FindingValuesNullGuard.offenders(columnLevel, List.of(valued));
        assertEquals(1, offenders.size(), offenders.toString());
        assertTrue(offenders.get(0).startsWith("R-VAR/$end valued on a non-record finding"),
                offenders.toString());
        Violation rawValued = new Violation(0, Map.of("variable_name", "AETERM", "DOMAIN", "AE"));
        assertEquals(1, FindingValuesNullGuard.offenders(columnLevel, List.of(rawValued)).size(),
                "a raw column valued on a column-level finding (V1 / V4)");

        // Controls.
        Violation listed = new Violation(0, Map.of("variable_name", "AETERM"), null, null, Map.of(),
                null, null, null, List.of("$end"));
        assertTrue(FindingValuesNullGuard.offenders(columnLevel, List.of(listed)).isEmpty(),
                "the Q-C state on the column-level path");
        assertTrue(FindingValuesNullGuard.offenders(perCell, List.of(valued)).isEmpty(),
                "a (column, row) finding prints its row's values");
        assertTrue(FindingValuesNullGuard.isNonRecord(Sensitivity.RECORD,
                net.cumba.corej.core.expr.eval.Domain.VARIABLE, null, null, List.of()));
        assertFalse(FindingValuesNullGuard.isNonRecord(Sensitivity.RECORD,
                net.cumba.corej.core.expr.eval.Domain.VARIABLE, Map.of("USUBJID", "S1"), null,
                List.of()), "a Group finding keeps its distinct sets (Q-A)");
    }


    @Test
    void theGuardJudgesTheNameOnlyListEntryByEntry()
    {
        // The real state CDISC-CG0349 reaches: variable_name valued, DOMAIN listed by name.
        Map<String, String> values = new LinkedHashMap<>();
        values.put("variable_name", "AETERM");
        Violation listed = new Violation(0, values, null, null, Map.of(), null, null, null,
                List.of("DOMAIN"));
        assertTrue(FindingValuesNullGuard
                .offenders(rule("CDISC-CG0349", Sensitivity.RECORD), List.of(listed)).isEmpty(),
                "a present raw column listed by name is the V8 (b) state");

        // Every way the list can be wrong, each named.
        assertEquals("is null", FindingValuesNullGuard.nameOnlyOffence(null, values,
                FindingValuesNullGuardTest::noPerRow));
        assertEquals("is blank", FindingValuesNullGuard.nameOnlyOffence(" ", values,
                FindingValuesNullGuardTest::noPerRow));
        assertTrue(FindingValuesNullGuard
                .nameOnlyOffence("$n", values, FindingValuesNullGuardTest::noPerRow)
                .contains("binding"));
        assertNull(FindingValuesNullGuard.nameOnlyOffence("$n", values, _ -> true),
                "a per-row binding may be listed (Q-C)");
        assertTrue(FindingValuesNullGuard
                .nameOnlyOffence("variable_label", values, FindingValuesNullGuardTest::noPerRow)
                .contains("metadata"));
        assertTrue(FindingValuesNullGuard
                .nameOnlyOffence("record_count", values, FindingValuesNullGuardTest::noPerRow)
                .contains("virtual"));
        assertTrue(FindingValuesNullGuard
                .nameOnlyOffence("variable_name", values, FindingValuesNullGuardTest::noPerRow)
                .contains("metadata"));
        assertEquals("and valued at once", FindingValuesNullGuard.nameOnlyOffence("AESEV",
                Map.of("AESEV", "MILD"), FindingValuesNullGuardTest::noPerRow));
        assertNull(
                FindingValuesNullGuard.nameOnlyOffence("DM.SEX", values,
                        FindingValuesNullGuardTest::noPerRow),
                "a dotted joined column is a raw column");

        // A Violation refuses a null element (L-4), so the list is planted on the judgement.
        Map<String, String> both = new LinkedHashMap<>(values);
        both.put("AESEV", "MILD");
        List<String> offenders = FindingValuesNullGuard.entryOffenders("R-1", 0, both,
                Arrays.asList("AESEV", "$n", null, "DOMAIN", "DOMAIN"), false,
                FindingValuesNullGuardTest::noPerRow);
        assertEquals(4, offenders.size(), offenders.toString());
        assertTrue(offenders.get(0).startsWith("R-1/AESEV listed by name and valued at once"),
                offenders.toString());
        assertTrue(offenders.get(1).startsWith("R-1/$n listed by name is a dataset-scope binding"),
                offenders.toString());
        assertTrue(offenders.get(2).startsWith("R-1/null listed by name is null"),
                offenders.toString());
        assertTrue(offenders.get(3).startsWith("R-1/DOMAIN listed by name twice"),
                offenders.toString());
        Violation planted = new Violation(0, both, null, null, Map.of(), null, null, null,
                List.of("AESEV"));
        AssertionError red = assertThrows(AssertionError.class,
                () -> FindingValuesNullGuard.requireNullFree("sensitivity", "plant",
                        rule("R-1", Sensitivity.RECORD), List.of(planted)));
        assertTrue(red.getMessage().contains("R-1/AESEV"), red.getMessage());
    }


    private static Rule rule(String coreId, Sensitivity sensitivity)
    {
        Rule r = new Rule();
        net.cumba.corej.core.model.RuleCore core = new net.cumba.corej.core.model.RuleCore();
        core.setId(coreId);
        r.setCore(core);
        r.setSensitivity(sensitivity);
        return r;
    }
}
