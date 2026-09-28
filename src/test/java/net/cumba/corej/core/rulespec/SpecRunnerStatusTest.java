package net.cumba.corej.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Rule-execution-status channel — Java lane (PLAN-parity-expected-status §2.1/§2.4).
 *
 * <p>
 * Formerly one half of a hand-mirrored pair with {@code test_spec_runner_status.py}. The Python
 * twin was cut in wave 33; this lane is the only lane, and the lock-step duty the old header
 * imposed is void. The scenarios below are retained as coreJ contract coverage.
 *
 * <p>
 * While the pair existed, "mirrored" meant every scenario that CAN be expressed on both lanes was,
 * with the same expectation. A few were lane-specific by nature and had no twin: {@code
 * absentObservationAlsoMeansExecuted} (a null observation was unrepresentable in Python, where the
 * fold always returned a token), and on the Python side the {@code NOT_RUN} sentinel and the
 * mistyped-key case (this lane covers the latter in {@link SpecTopLevelKeysTest} instead, because
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} is off here).
 *
 * <p>
 * Covers: the default (absent ⇒ EXECUTED), the comparison, the fact that the channel is
 * unconditionally folded into {@code passed()} (P4 removed the opt-in gate), and the vocabulary
 * (uppercase only, bad values rejected at load, a bare key accepted).
 */
class SpecRunnerStatusTest
{

    private static final Set<String> NO_DIFF = Set.of();

    private static final Set<String> SOME_DIFF = Set.of("{\"rule_id\":\"X\"}");

    private static SpecRunner.SpecResult result(RuleExecutionStatus aEngineStatus,
            RuleExecutionStatus aExpectedStatus, Set<String> aMissing)
    {
        // Trailing List.of() = observedReasons: this fixture exercises the STATUS channel, so the
        // errors channel is held empty-vs-empty (matching) and never masks a status assertion.
        return new SpecRunner.SpecResult("unit", List.of(), List.of(), List.of(), List.of(),
                aMissing, NO_DIFF, aEngineStatus, null, aExpectedStatus, null, List.of());
    }


    private static SpecRunner.SpecResult messageResult(String aEngineMessage,
            String aExpectedMessage)
    {
        return new SpecRunner.SpecResult("unit", List.of(), List.of(), List.of(), List.of(),
                NO_DIFF, NO_DIFF, RuleExecutionStatus.SKIPPED, aEngineMessage,
                RuleExecutionStatus.SKIPPED, aExpectedMessage, List.of());
    }


    private static String specText(String aStatusLine)
    {
        return """
                name: status_unit
                kind: check_operator
                check:
                  name: SEX
                  operator: equal_to
                  value: "M"
                datasets:
                  AE:
                    row_key: [STUDYID, USUBJID, AESEQ]
                    rows:
                      - {STUDYID: "S1", USUBJID: "001", AESEQ: 1, SEX: "M"}
                config:
                  standard: sdtmig
                  standard_version: "3.4"
                """ + aStatusLine + """
                expected_violations: []
                expected_errors: []
                """;
    }


    private static SpecSchema load(Path aTmp, String aStatusLine) throws IOException
    {
        Path file = aTmp.resolve("s.yaml");
        Files.writeString(file, specText(aStatusLine));
        return SpecLoader.load(file);
    }

    // --- the comparison --------------------------------------------------------------


    @Test
    void absentExpectationMeansExecuted()
    {
        assertTrue(result(RuleExecutionStatus.EXECUTED, null, NO_DIFF).statusMatches());
        assertFalse(result(RuleExecutionStatus.SKIPPED, null, NO_DIFF).statusMatches());
        assertFalse(result(RuleExecutionStatus.ERROR, null, NO_DIFF).statusMatches());
    }


    @Test
    void absentObservationAlsoMeansExecuted()
    {
        // engineStatus is never null in practice, but the record allows it — treat it as EXECUTED
        // rather than throwing, symmetrically with the expectation side.
        assertTrue(result(null, null, NO_DIFF).statusMatches());
        assertTrue(result(null, RuleExecutionStatus.EXECUTED, NO_DIFF).statusMatches());
        assertFalse(result(null, RuleExecutionStatus.SKIPPED, NO_DIFF).statusMatches());
    }


    @Test
    void explicitExpectationIsComparedExactly()
    {
        assertTrue(result(RuleExecutionStatus.SKIPPED, RuleExecutionStatus.SKIPPED, NO_DIFF)
                .statusMatches());
        assertTrue(result(RuleExecutionStatus.ERROR, RuleExecutionStatus.ERROR, NO_DIFF)
                .statusMatches());
        assertFalse(result(RuleExecutionStatus.EXECUTED, RuleExecutionStatus.SKIPPED, NO_DIFF)
                .statusMatches());
        assertFalse(result(RuleExecutionStatus.SKIPPED, RuleExecutionStatus.ERROR, NO_DIFF)
                .statusMatches());
    }


    @Test
    void statusChannelIsIndependentOfTheViolationChannel()
    {
        var bothOk = result(RuleExecutionStatus.EXECUTED, RuleExecutionStatus.EXECUTED, NO_DIFF);
        assertTrue(bothOk.violationsMatch());
        assertTrue(bothOk.statusMatches());

        var onlyStatusBad = result(RuleExecutionStatus.SKIPPED, RuleExecutionStatus.EXECUTED,
                NO_DIFF);
        assertTrue(onlyStatusBad.violationsMatch());
        assertFalse(onlyStatusBad.statusMatches());

        var onlyViolationsBad = result(RuleExecutionStatus.EXECUTED, RuleExecutionStatus.EXECUTED,
                SOME_DIFF);
        assertFalse(onlyViolationsBad.violationsMatch());
        assertTrue(onlyViolationsBad.statusMatches());
    }

    // --- both channels are folded into passed(), unconditionally ---------------------


    @Test
    void statusIsAlwaysFoldedIntoPassed()
    {
        // P4 removed the opt-in gate on purpose: there must be NO way to run the suite with the
        // status channel silently disabled. If someone reintroduces a toggle, this test is where
        // it should be caught — a status mismatch fails passed() with no configuration involved.
        assertFalse(result(RuleExecutionStatus.SKIPPED, null, NO_DIFF).passed());
        assertFalse(result(RuleExecutionStatus.EXECUTED, RuleExecutionStatus.SKIPPED, NO_DIFF)
                .passed());
        assertTrue(
                result(RuleExecutionStatus.SKIPPED, RuleExecutionStatus.SKIPPED, NO_DIFF).passed());
        assertTrue(result(RuleExecutionStatus.EXECUTED, null, NO_DIFF).passed());
    }


    @Test
    void aViolationDiffStillFailsRegardlessOfTheStatusChannel()
    {
        assertFalse(result(RuleExecutionStatus.EXECUTED, RuleExecutionStatus.EXECUTED, SOME_DIFF)
                .passed());
        assertFalse(result(RuleExecutionStatus.SKIPPED, RuleExecutionStatus.SKIPPED, SOME_DIFF)
                .passed());
    }

    /** Substrings that would betray a reintroduced status-assertion toggle. */
    private static final List<String> TOGGLE_MARKERS = List.of("assertstatus", "statusassert",
            "assertionenabled", "assertion_enabled", "skipstatus", "statusenabled");

    @Test
    void noStatusAssertionToggleSurvives()
    {
        // Explicit anti-regression: the P2-era property and env var must be gone. A grep-able
        // assertion is worth more than a comment, because reintroducing the toggle is the one
        // change that would quietly undo this whole plan.
        //
        // EVERY marker is applied to EVERY member kind, on the runner AND on the driver — an
        // earlier version matched one substring against fields and a different one against
        // methods, which let `assertStatusEnabled()` (a method containing "assertstatus", not
        // "assertionenabled") slip through both loops.
        for (Class<?> type : List.of(SpecRunner.class, SpecRunner.SpecResult.class,
                RuleExecutionSpecTest.class))
        {
            for (java.lang.reflect.Field f : type.getDeclaredFields())
            {
                assertNoToggleMarker(type, "field", f.getName());
            }
            for (java.lang.reflect.Method m : type.getDeclaredMethods())
            {
                assertNoToggleMarker(type, "method", m.getName());
            }
        }
    }


    private static void assertNoToggleMarker(Class<?> aType, String aKind, String aName)
    {
        String lower = aName.toLowerCase(java.util.Locale.ROOT);
        for (String marker : TOGGLE_MARKERS)
        {
            assertFalse(lower.contains(marker),
                    () -> aType.getSimpleName() + " reintroduced a status-assertion toggle ("
                            + aKind + " '" + aName + "' matches '" + marker + "'). The status "
                            + "channel is asserted unconditionally by design — see "
                            + "PLAN-parity-expected-status P4.");
        }
    }


    @Test
    void formatDiffReportsTheStatusLine()
    {
        String text = result(RuleExecutionStatus.SKIPPED, RuleExecutionStatus.EXECUTED, NO_DIFF)
                .formatDiff();
        assertTrue(text.contains("status: expected=EXECUTED observed=SKIPPED"), text);
        assertFalse(result(RuleExecutionStatus.EXECUTED, RuleExecutionStatus.EXECUTED, NO_DIFF)
                .formatDiff().contains("status: expected"));
    }

    // --- the load contract -----------------------------------------------------------

    // --- the status-message channel (PLAN-binding-expressions review round 1, T1) ----------


    @Test
    void anUndeclaredStatusMessageAssertsNothing()
    {
        assertTrue(messageResult("Rule skipped — no Library access", null).passed());
        assertTrue(messageResult(null, null).statusMessageMatches());
    }


    @Test
    void aDeclaredStatusMessageIsComparedExactly()
    {
        String gate = "Rule skipped — no Library access";
        String signal = "Rule skipped — library returned no data for get_codelist_attributes";
        assertTrue(messageResult(gate, gate).passed());
        SpecRunner.SpecResult other = messageResult(signal, gate);
        assertTrue(other.statusMatches(), "both SKIPPED — only the message tells them apart");
        assertFalse(other.statusMessageMatches());
        assertFalse(other.passed());
        assertTrue(other.formatDiff().contains("status message: expected=" + gate),
                other.formatDiff());
        assertFalse(messageResult(null, gate).statusMessageMatches());
    }


    @Test
    void theStatusMessageKeyLoads(@TempDir Path tmp) throws IOException
    {
        assertEquals("Rule skipped — no Library access", load(tmp,
                "expected_status: SKIPPED\nexpected_status_message: Rule skipped — no Library access\n")
                        .expectedStatusMessage());
        assertEquals(null, load(tmp, "").expectedStatusMessage());
    }


    @Test
    void absentKeyLoadsAsNull(@TempDir Path tmp) throws IOException
    {
        assertEquals(null, load(tmp, "").expectedStatus());
    }


    @Test
    void bareKeyLoadsAsNull(@TempDir Path tmp) throws IOException
    {
        // `expected_status:` with no value is null in YAML. Both lanes must accept it and treat
        // it as EXECUTED rather than one of them hard-failing.
        assertEquals(null, load(tmp, "expected_status:\n").expectedStatus());
    }


    @ParameterizedTest
    @ValueSource(strings =
    {
            "EXECUTED", "SKIPPED", "ERROR"
    })
    void uppercaseVocabularyLoads(String aValue, @TempDir Path tmp) throws IOException
    {
        assertEquals(RuleExecutionStatus.valueOf(aValue),
                load(tmp, "expected_status: " + aValue + "\n").expectedStatus());
    }


    @ParameterizedTest
    @ValueSource(strings =
    {
            "'  SKIPPED  '", "' SKIPPED'", "'SKIPPED '"
    })
    void whitespacePaddedValueIsAccepted(String aValue, @TempDir Path tmp) throws IOException
    {
        // Jackson's enum deserializer TRIMS — measured, not assumed: an earlier revision of this
        // pair asserted the opposite and the gated build proved otherwise. `schema.py` strips
        // before its Literal check so the Python lane agrees, rather than leaving a divergence
        // that a default `-P main` build could never see (that lane is opt-in). The twin case is
        // test_spec_runner_status.test_whitespace_padded_value_is_accepted_like_java.
        assertEquals(RuleExecutionStatus.SKIPPED,
                load(tmp, "expected_status: " + aValue + "\n").expectedStatus());
    }


    @ParameterizedTest
    @ValueSource(strings =
    {
            "skipped", "Executed", "SUCCESS", "NOT_RUN", "nonsense",
            // Padded LOWERCASE is still an error — trimming must not weaken the uppercase rule.
            "'  skipped  '"
    })
    void badValueFailsAtLoad(String aValue, @TempDir Path tmp)
    {
        // Lowercase is the likely author typo, because the vendored fork's own vocabulary is
        // lowercase — so it must be loud, not silently non-matching. ACCEPT_CASE_INSENSITIVE_ENUMS
        // is deliberately not enabled. SUCCESS is the dead default the field used to carry;
        // NOT_RUN is the Python adapter's internal sentinel and no spec may pin it.
        assertThrows(IOException.class, () -> load(tmp, "expected_status: " + aValue + "\n"));
    }
}
