package net.cumba.corej.core.rulespec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The {@code errors} assertion channel — Java lane (PLAN-parity-expected-errors).
 *
 * <p>
 * Formerly hand-mirrored with {@code test_expected_errors.py}; the Python twin was cut in wave 33,
 * this lane is the only lane, and the lock-step duty is void. The Shared / Java-only / Python-only
 * breakdown below is retained deliberately — it records why these scenarios exist and is the only
 * surviving description of the lane split. The two files covered the same contract, but they were
 * NOT the same scenarios:
 *
 * <ul>
 * <li><b>Shared:</b> the normalisation contract (sort + de-duplicate), the {@code errorsMatch()} /
 * {@code errors_match} comparison, the empty-vs-empty default, and the "reasons non-empty ⟺ status
 * {@code ERROR}" biconditional.</li>
 * <li><b>Java-only:</b> the {@link ViolationNormaliser.ErrorReason} classification of engine status
 * messages, and the engine-contract pins below — the Python fork hands its reason class over
 * directly, so it has no classifier to test.</li>
 * <li><b>Python-only:</b> harvesting {@code FailedValidationEntity.error} out of the fork's result
 * dicts, and the rule that a {@code skipped} entry's {@code errors} list must not be
 * harvested.</li>
 * </ul>
 */
class ExpectedErrorsTest
{

    /** Builds a SpecResult whose only interesting axis is the errors channel. */
    private static SpecRunner.SpecResult resultWith(List<String> observedReasons,
            List<String> expectedErrors)
    {
        return new SpecRunner.SpecResult("synthetic", List.of(), List.of(), List.of(),
                expectedErrors, java.util.Set.of(), java.util.Set.of(),
                RuleExecutionStatus.EXECUTED, null, null, null, observedReasons);
    }

    @Nested
    class Comparison
    {

        @Test
        void emptyVersusEmptyMatches()
        {
            Assertions.assertTrue(resultWith(List.of(), List.of()).errorsMatch());
        }


        @Test
        void observedReasonAgainstEmptyExpectationDiverges()
        {
            Assertions.assertFalse(resultWith(List.of("LOAD_ERROR"), List.of()).errorsMatch());
        }


        @Test
        void expectedReasonWithNoObservationDiverges()
        {
            // The direction that catches a healed divergence: the spec still pins a reason the
            // engine no longer produces.
            Assertions.assertFalse(resultWith(List.of(), List.of("LOAD_ERROR")).errorsMatch());
        }


        @Test
        void matchingReasonPasses()
        {
            Assertions.assertTrue(
                    resultWith(List.of("LOAD_ERROR"), List.of("LOAD_ERROR")).errorsMatch());
        }


        @Test
        void comparisonIsOrderInsensitive()
        {
            Assertions.assertTrue(resultWith(List.of("B", "A"), List.of("A", "B")).errorsMatch());
        }


        @Test
        void comparisonIsDuplicateInsensitive()
        {
            Assertions.assertTrue(resultWith(List.of("A", "A"), List.of("A")).errorsMatch());
        }


        @Test
        void aDifferentReasonDivergesEvenAtTheSameCardinality()
        {
            // The whole point of Option B over Option A: same number of errors, different cause.
            // A channel keyed on cardinality — or on the fork's near-constant container message —
            // would call this a match.
            Assertions
                    .assertFalse(resultWith(List.of("NO_NATIVE_CHECK_EXPR"), List.of("LOAD_ERROR"))
                            .errorsMatch());
        }


        @Test
        void nullListsAreTreatedAsEmpty()
        {
            Assertions.assertTrue(resultWith(null, null).errorsMatch());
        }


        @Test
        void passedRequiresTheErrorsChannel()
        {
            SpecRunner.SpecResult result = resultWith(List.of("LOAD_ERROR"), List.of());
            Assertions.assertTrue(result.violationsMatch());
            Assertions.assertTrue(result.statusMatches());
            Assertions.assertFalse(result.passed(),
                    "a spec that agrees on violations and status but errored for an unexpected "
                            + "reason must not pass");
        }


        @Test
        void formatDiffNamesBothSidesWhenTheChannelDiverges()
        {
            String text = resultWith(List.of("NO_NATIVE_CHECK_EXPR"), List.of("LOAD_ERROR"))
                    .formatDiff();
            Assertions.assertTrue(text.contains("errors:"), text);
            Assertions.assertTrue(text.contains("LOAD_ERROR"), text);
            Assertions.assertTrue(text.contains("NO_NATIVE_CHECK_EXPR"), text);
        }


        @Test
        void formatDiffStaysQuietWhenTheChannelAgrees()
        {
            Assertions
                    .assertFalse(resultWith(List.of(), List.of()).formatDiff().contains("errors:"));
        }
    }


    @Nested
    class Classification
    {

        @Test
        void aNonErrorResultHasNoReasons()
        {
            RuleExecutionResult executed = RuleExecutionResult.builder().ruleId("R")
                    .violations(List.of()).totalRows(0L).status(RuleExecutionStatus.EXECUTED)
                    .build();
            Assertions.assertEquals(List.of(), ViolationNormaliser.reasonsFrom(executed, null));
        }


        @Test
        void aSkippedResultHasNoReasonsEvenWithAMessage()
        {
            RuleExecutionResult skipped = RuleExecutionResult.builder().ruleId("R")
                    .violations(List.of()).totalRows(0L).status(RuleExecutionStatus.SKIPPED)
                    .statusMessage("Rule skipped — Domains scope does not match").build();
            Assertions.assertEquals(List.of(), ViolationNormaliser.reasonsFrom(skipped, null));
        }


        @Test
        void aNullResultHasNoReasons()
        {
            Assertions.assertEquals(List.of(), ViolationNormaliser.reasonsFrom(null, null));
        }


        @Test
        void relrecKeyExpansionMessageClassifies()
        {
            Assertions.assertEquals(
                    List.of(ViolationNormaliser.ErrorReason.RELREC_KEY_EXPANSION.name()),
                    ViolationNormaliser.reasonsFrom(
                            error(ViolationNormaliser.RELREC_KEY_EXPANSION_MESSAGE), null));
        }


        @Test
        void noNativeCheckExprMessageClassifies()
        {
            Assertions.assertEquals(
                    List.of(ViolationNormaliser.ErrorReason.NO_NATIVE_CHECK_EXPR.name()),
                    ViolationNormaliser.reasonsFrom(
                            error(ViolationNormaliser.NO_NATIVE_CHECK_EXPR_MESSAGE), null));
        }


        @Test
        void aLoadErrorIsRecognisedStructurally()
        {
            // LOAD_ERROR's text is authored per-rule, so it can only be recognised by matching the
            // rule's own loadError — never by a constant.
            Rule rule = new Rule();
            rule.setLoadError("operand substitution '${' is not closed");
            Assertions.assertEquals(List.of(ViolationNormaliser.ErrorReason.LOAD_ERROR.name()),
                    ViolationNormaliser
                            .reasonsFrom(error("operand substitution '${' is not closed"), rule));
        }


        @Test
        void aLoadErrorWithoutTheRuleDegradesToOther()
        {
            Assertions.assertEquals(List.of(ViolationNormaliser.ErrorReason.OTHER.name()),
                    ViolationNormaliser.reasonsFrom(error("some authored load error"), null));
        }


        @Test
        void aSplitDomainUnionFailureIsRecognisedByItsFixedTail()
        {
            // Fix #358 (ruling 1): the head is data-dependent (domain, column, members), so the
            // classification matches the engine-fixed tail.
            Assertions.assertEquals(
                    List.of(ViolationNormaliser.ErrorReason.INVALID_SPLIT_DOMAIN.name()),
                    ViolationNormaliser.reasonsFrom(error(
                            "'LB' resolves to a split domain whose members disagree on column "
                                    + "LBSTRESN (lbch:LONG vs lbhe:STRING); the domain cannot be "
                                    + "joined"),
                            null));
        }


        @Test
        void aColumnTypeMismatchIsRecognisedByItsFixedHead()
        {
            // PLAN-column-type-conformance Phase 3. The TAIL is data-dependent -- it names the
            // variable and both types -- so unlike the split-domain case the classification
            // matches the engine-fixed HEAD. Both directions produce the same prefix.
            Assertions.assertEquals(
                    List.of(ViolationNormaliser.ErrorReason.COLUMN_TYPE_MISMATCH.name()),
                    ViolationNormaliser.reasonsFrom(error("column-type mismatch: column DOSE is "
                            + "declared STRING but a numeric operand is required here; author "
                            + "num(DOSE)"), null));
            Assertions.assertEquals(
                    List.of(ViolationNormaliser.ErrorReason.COLUMN_TYPE_MISMATCH.name()),
                    ViolationNormaliser.reasonsFrom(
                            error("column-type mismatch: column CNSR is declared LONG but a "
                                    + "character operand is required here"),
                            null));
        }


        @Test
        void aRewordedColumnTypeMismatchDegradesToOtherRatherThanMisclassifying()
        {
            // The prefix match is the whole contract: if the engine rewords the head, this must
            // become a VISIBLE divergence, never a silent mis-bucketing into the wrong reason.
            Assertions.assertEquals(List.of(ViolationNormaliser.ErrorReason.OTHER.name()),
                    ViolationNormaliser.reasonsFrom(
                            error("column type mismatch: column DOSE is declared STRING"), null));
        }


        @Test
        void anUnrecognisedMessageIsOther()
        {
            Assertions.assertEquals(List.of(ViolationNormaliser.ErrorReason.OTHER.name()),
                    ViolationNormaliser.reasonsFrom(error("brand new engine wording"), null));
        }


        @Test
        void anErrorWithANullMessageIsStillReported()
        {
            // This is the edge `errorsFrom` cannot express: it returns an EMPTY list for an ERROR
            // with a null message, so message-cardinality is not a faithful proxy for "did this
            // error". The reason channel is total over ERROR-ness, which is exactly why it can
            // carry information the status channel's own predicate does not.
            Assertions.assertEquals(List.of(ViolationNormaliser.ErrorReason.OTHER.name()),
                    ViolationNormaliser.reasonsFrom(error(null), null));
            Assertions.assertEquals(List.of(), ViolationNormaliser.errorsFrom(error(null)));
        }


        @Test
        void aFixedEngineMessageWinsOverAColludingLoadError()
        {
            // Defensive: a rule whose authored loadError happens to equal a fixed engine message
            // must still classify as the engine reason, so classification stays deterministic.
            Rule rule = new Rule();
            rule.setLoadError(ViolationNormaliser.NO_NATIVE_CHECK_EXPR_MESSAGE);
            Assertions.assertEquals(
                    List.of(ViolationNormaliser.ErrorReason.NO_NATIVE_CHECK_EXPR.name()),
                    ViolationNormaliser.reasonsFrom(
                            error(ViolationNormaliser.NO_NATIVE_CHECK_EXPR_MESSAGE), rule));
        }


        @ParameterizedTest
        @ValueSource(strings =
        {
                // ⚠ COLUMN_TYPE_MISMATCH was missing from this list until 2026-09-17 — it was
                // added to the enum by PLAN-column-type-conformance Phase 3 and never added here,
                // which is precisely the silent gap this test exists to close. STAGE_B arrives
                // with D104e.
                "LOAD_ERROR", "RELREC_KEY_EXPANSION", "NO_NATIVE_CHECK_EXPR",
                "INVALID_SPLIT_DOMAIN", "COLUMN_TYPE_MISMATCH", "STAGE_B", "OTHER"
        })
        void everyTokenNameIsAValidEnumConstant(String token)
        {
            // Pins the vocabulary the corpus and the docs quote. Renaming a constant without
            // updating them would otherwise pass silently.
            Assertions.assertNotNull(ViolationNormaliser.ErrorReason.valueOf(token));
        }


        @Test
        void theVocabularyHasExactlySevenTokens()
        {
            // Widened 4 → 5 by Fix #358: INVALID_SPLIT_DOMAIN covers the split-domain
            // union-failure ERROR (ruling 1 of PLAN-match-datasets-split-union.md).
            // Widened 5 → 6 on 2026-09-13 by PLAN-column-type-conformance Phase 3:
            // COLUMN_TYPE_MISMATCH covers the new ColumnTypeGate ERROR site in RuleRunner.
            // ⭐ This ratchet did its job — it caught the widening in the same run that first
            // exercised the gate, which is exactly the point of an exact-equality census.
            // ⚑ Note for whoever follows the message's advice: PLAN-match-datasets-split-union.md
            // is NOT in <meta>/plans/ or plans/done/ — a dangling citation, like
            // PLAN-date-diff-foreign-offset.md. The vocabulary that is actually authoritative is
            // this enum plus ViolationNormaliser.classify.
            // Widened 6 -> 7 on 2026-09-17 by PLAN-typed-expression-engine D104e: STAGE_B covers
            // RuleRunner's seventh ERROR site, the phase-4 stage-B bind gate. Its population is
            // zero today; the branch is proved reachable by StageBErrorReasonReachabilityTest
            // rather than justified by a comment claiming it cannot happen.
            Assertions.assertEquals(7, ViolationNormaliser.ErrorReason.values().length,
                    "A new ErrorReason means a new classification branch and, most likely, a new "
                            + "RuleRunner ERROR site — update EngineContract below before widening "
                            + "this, and add a classify() case or it silently buckets as OTHER.");
        }


        @Test
        void aStageBBindErrorIsRecognisedByItsFixedHead()
        {
            // D104e. Same prefix shape as the column-type gate: the tail is the joined toString()
            // of the armed findings, so only the head is fixed. ⚑ The message text here is a
            // hand-written sample; the assertion that it is the ENGINE's real wording lives in
            // StageBErrorReasonReachabilityTest, which drives RuleRunner.stageBGate itself.
            Assertions
                    .assertEquals(List.of(ViolationNormaliser.ErrorReason.STAGE_B.name()),
                            ViolationNormaliser.reasonsFrom(
                                    error("stage B: UNRESOLVED_WILDCARD [--OCCUR]: '--OCCUR' still "
                                            + "carries a `--` wildcard after specialisation"),
                                    null));
        }


        @Test
        void aRewordedStageBPrefixDegradesToOtherRatherThanMisclassifying()
        {
            Assertions.assertEquals(List.of(ViolationNormaliser.ErrorReason.OTHER.name()),
                    ViolationNormaliser.reasonsFrom(error("stage-B: UNRESOLVED_WILDCARD [--OCCUR]"),
                            null));
        }


        private static RuleExecutionResult error(String message)
        {
            return RuleExecutionResult.builder().ruleId("R").violations(List.of()).totalRows(0L)
                    .status(RuleExecutionStatus.ERROR).statusMessage(message).build();
        }
    }


    /**
     * Drift guards. The classifier recognises two engine messages by exact match, which is a
     * deliberate choice — a reworded message degrades to {@code OTHER} (visible) rather than
     * mis-classifying (silent). But "visible" only helps if something looks, and no corpus spec
     * produces a Java {@code ERROR} today (measured in P1: 0 of 1028), so nothing would. These
     * tests are that something.
     */
    @Nested
    class EngineContract
    {

        /*
         * ⚠⚠ THREE DRIFT GUARDS MOVED OUT OF THIS CLASS.
         *
         * theEngineStillHasExactlyFiveErrorSites, theInvalidSplitDomainSuffixStillMatchesTheEngine
         * Source and theNoNativeCheckExprMessageConstantStillMatchesTheEngineSource all read the
         * ENGINE'S SOURCE TEXT -- RuleRunner.java and SplitDomainResolution.java -- because the
         * messages they pin are inline literals with no constant to import. In the coreJ monorepo
         * that was a relative path to a sibling module.
         *
         * corej-core is a separate repository now and this build never checks it out. The sources
         * jar is not an option either: the engine repo attaches it on the deploy path only, by an
         * explicit decision in its root pom, and a test convenience is not a reason to reverse it.
         *
         * → They live in the engine repository now, beside the source they read, as
         *   corej-core's src/test/java/net/cumba/corej/core/exec/EngineErrorMessageContractTest.
         *   That copy carries its own copies of the two message literals; ViolationNormaliser here
         *   is the other half of the pair, and the two MUST be changed together. A reworded engine
         *   message that this classifier no longer recognises degrades every affected ERROR to
         *   OTHER -- silently, which is exactly what the guards exist to prevent.
         */

        @Test
        void theCorpusErrorPinObservesExactlyTheTokenItClaims() throws Exception
        {
            // OPGAP-unknown-function-errors-loudly (D121b successor of the retired operator-leaf
            // pin OPGAP-shares-at-least-one-element-with) is the ONLY spec in the corpus that
            // makes the Java lane produce an ERROR, and therefore the only one whose
            // `expected_errors` is non-empty. The parity sweep already covers it, but a sweep
            // failure says only "spec X diverged"; this names the contract directly and fails
            // with the reason in the message.
            //
            // It also proves the pin is not vacuous: the spec passes the sweep only because the
            // engine really emits NO_NATIVE_CHECK_EXPR here — the expression parses (an ordinary
            // function call), the compiler declines the unknown function, no checkExpr is
            // installed, and RuleRunner errors rather than reporting zero findings. Were the
            // function to gain a builtin, observedReasons would be empty, expectedErrors would
            // not, and both this test and the sweep would go red — the intended signal.
            Path spec = Path.of("rulespec", "specs", "OPGAP-unknown-function-errors-loudly.yaml");
            Assertions.assertTrue(Files.isRegularFile(spec),
                    "the corpus's only Java-ERROR pin is missing: " + spec.toAbsolutePath());

            SpecRunner.SpecResult result = SpecRunner.run(spec, SpecRunner.Mode.ASSERT);

            Assertions.assertEquals(RuleExecutionStatus.ERROR, result.engineStatus(),
                    "the function gained a builtin, or the spec stopped reaching the compiler");
            Assertions.assertEquals(
                    List.of(ViolationNormaliser.ErrorReason.NO_NATIVE_CHECK_EXPR.name()),
                    result.observedReasons());
            Assertions.assertTrue(result.errorsMatch(),
                    () -> "the spec's expected_errors no longer matches what the engine produces: "
                            + result.formatDiff());
            Assertions.assertTrue(result.passed(),
                    () -> "this spec must pass outright — it is Java-canonical: "
                            + result.formatDiff());
        }

    }
}
