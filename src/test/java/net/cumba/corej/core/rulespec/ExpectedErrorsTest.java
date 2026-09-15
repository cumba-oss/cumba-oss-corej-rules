package net.cumba.corej.core.rulespec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
                RuleExecutionStatus.EXECUTED, null, Map.of(), null, observedReasons);
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
                "LOAD_ERROR", "RELREC_KEY_EXPANSION", "NO_NATIVE_CHECK_EXPR",
                "INVALID_SPLIT_DOMAIN", "OTHER"
        })
        void everyTokenNameIsAValidEnumConstant(String token)
        {
            // Pins the vocabulary the corpus and the docs quote. Renaming a constant without
            // updating them would otherwise pass silently.
            Assertions.assertNotNull(ViolationNormaliser.ErrorReason.valueOf(token));
        }


        @Test
        void theVocabularyHasExactlySixTokens()
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
            Assertions.assertEquals(6, ViolationNormaliser.ErrorReason.values().length,
                    "A new ErrorReason means a new classification branch and, most likely, a new "
                            + "RuleRunner ERROR site — update EngineContract below before widening "
                            + "this, and add a classify() case or it silently buckets as OTHER.");
        }


        private static RuleExecutionResult error(String message)
        {
            return RuleExecutionResult.builder().ruleId("R").violations(List.of()).totalRows(0L)
                    .status(RuleExecutionStatus.ERROR).statusMessage(message).build();
        }
    }


    /** Mirrors the "baseline wiring" section of {@code test_expected_errors.py}. */
    @Nested
    class BaselineWiring
    {

        private static BaselineManifest.KnownDivergence entry(List<String> channels)
        {
            return new BaselineManifest.KnownDivergence("x", "java", channels, "ref", null, null);
        }


        @Test
        void errorsIsAKnownChannel()
        {
            Assertions.assertTrue(
                    BaselineManifest.CHANNELS.contains(BaselineManifest.ERRORS_CHANNEL));
        }


        @Test
        void entryCanClaimTheErrorsChannel()
        {
            BaselineManifest.KnownDivergence e = entry(
                    List.of(BaselineManifest.STATUS_CHANNEL, BaselineManifest.ERRORS_CHANNEL));
            Assertions.assertTrue(e.divergesOn(BaselineManifest.ERRORS_CHANNEL));
            Assertions.assertFalse(e.divergesOn(BaselineManifest.VIOLATIONS_CHANNEL));
        }


        @Test
        void absentChannelsStillDefaultsToViolationsOnly()
        {
            // The discriminator was built to be extensible; adding a third channel must not
            // retroactively widen the 200-odd entries that predate it.
            BaselineManifest.KnownDivergence e = entry(null);
            Assertions.assertFalse(e.divergesOn(BaselineManifest.ERRORS_CHANNEL));
            Assertions.assertTrue(e.divergesOn(BaselineManifest.VIOLATIONS_CHANNEL));
        }


        @Test
        void unknownChannelIsStillRejected()
        {
            Assertions.assertEquals(List.of("bogus"),
                    entry(List.of(BaselineManifest.ERRORS_CHANNEL, "bogus")).unknownChannels());
        }

        // --- per-channel anti-rot (D3) ---------------------------------------------------
        //
        // These pin the actual behaviour change, not just its description. The old rule was
        // "at least one claimed channel still diverges"; the new one is "every claimed channel
        // still diverges". threeClaimedTwoHealed is the case that distinguishes them, and it is
        // exactly the scenario a third channel makes reachable.

        private static final List<String> ALL_THREE = List.of(BaselineManifest.VIOLATIONS_CHANNEL,
                BaselineManifest.STATUS_CHANNEL, BaselineManifest.ERRORS_CHANNEL);

        private static Map<String, Boolean> diverged(boolean aViolations, boolean aStatus,
                boolean aErrors)
        {
            Map<String, Boolean> map = new java.util.LinkedHashMap<>();
            map.put(BaselineManifest.VIOLATIONS_CHANNEL, aViolations);
            map.put(BaselineManifest.STATUS_CHANNEL, aStatus);
            map.put(BaselineManifest.ERRORS_CHANNEL, aErrors);
            return map;
        }


        @Test
        void noHealedChannelsWhenEveryClaimStillDiverges()
        {
            Assertions.assertEquals(List.of(),
                    entry(ALL_THREE).healedChannels(diverged(true, true, true)));
        }


        @Test
        void threeClaimedTwoHealedIsNowReported()
        {
            // Under the OLD per-entry rule this passed silently — `violations` still diverged, so
            // the entry counted as "still valid" and the two landed fixes stayed invisible.
            Assertions.assertEquals(
                    List.of(BaselineManifest.STATUS_CHANNEL, BaselineManifest.ERRORS_CHANNEL),
                    entry(ALL_THREE).healedChannels(diverged(true, false, false)));
        }


        @Test
        void allClaimsHealedIsReported()
        {
            Assertions.assertEquals(ALL_THREE,
                    entry(ALL_THREE).healedChannels(diverged(false, false, false)));
        }


        @Test
        void healedIgnoresChannelsTheEntryDoesNotClaim()
        {
            // A matching channel the entry never claimed is the normal case, not a rotted claim.
            Assertions.assertEquals(List.of(), entry(List.of(BaselineManifest.ERRORS_CHANNEL))
                    .healedChannels(diverged(false, false, true)));
        }


        @Test
        void unclaimedDivergenceIsReportedSeparately()
        {
            Assertions.assertEquals(List.of(BaselineManifest.VIOLATIONS_CHANNEL),
                    entry(List.of(BaselineManifest.ERRORS_CHANNEL))
                            .unclaimedDivergences(diverged(true, false, true)));
        }


        @Test
        void theTwoHalvesComposeIntoChannelsEqualsDivergences()
        {
            // Together the two checks assert a biconditional: an entry's `channels` list is
            // exactly the set of channels it diverges on. That is what makes adding a channel safe.
            BaselineManifest.KnownDivergence e = entry(
                    List.of(BaselineManifest.STATUS_CHANNEL, BaselineManifest.ERRORS_CHANNEL));
            Map<String, Boolean> exact = diverged(false, true, true);
            Assertions.assertEquals(List.of(), e.healedChannels(exact));
            Assertions.assertEquals(List.of(), e.unclaimedDivergences(exact));
        }


        @Test
        void theCommittedBaselineClaimsErrorsOnExactlyTheMeasuredCohort() throws IOException
        {
            // P1 measured 14 specs whose Python run folds to ERROR, plus one authored afterwards
            // whose divergence runs the OTHER way. Pinning the cohort by name means a later edit
            // that adds `errors` to an entry which does not actually error — or drops it from one
            // that does — is caught here, not just by a red parity run.
            //
            // CDISC-AD0019-absent and the two *-absent-* variable-requirement entries are
            // deliberately absent: they are status-channel entries (EXECUTED vs SKIPPED)
            // producing no error. (The requirement was spelled Scope.Variables when those entries
            // were written; PLAN-scope-requirements-split moved it to Requirements.Variables.)
            //
            // OPGAP-shares-at-least-one-element-with is the odd one out and the important one: it
            // was the only entry where JAVA produces the reason and Python does not — the only
            // spec exercising this channel on the Java lane — until PLAN-column-type-conformance
            // (2026-09-13) added CDISC-AD0361/-AD0362: their fixtures ship ASTDT(M)/AENDT(M) as
            // Char date strings on purpose, and the column-type gate (R4/R5) now ERRORs a numeric
            // order comparison over a Char-declared column, so coreJ reports a rule error where
            // the Python oracle parsed both sides and executed to zero findings.
            List<String> claiming = BaselineManifest.loadDefault().knownDivergences().stream()
                    .filter(d -> d.divergesOn(BaselineManifest.ERRORS_CHANNEL))
                    .map(BaselineManifest.KnownDivergence::spec).sorted().toList();
            // FDA-SD1063-no-define left this cohort when EC-42 was fixed in the fork: it no
            // longer produces a Python reason (the rule is SKIPPED, matching coreJ), so its
            // baseline entry was retired and the Python-side MEASURED_PYTHON_REASONS row with
            // it. Three independent guards caught the retirement — this one, the spec's own
            // anti-rot check, and test_expected_errors.py's cohort test.
            // CDISC-AD0497 left this cohort when the fork gained dataset_class_from_library
            // (EC-33 Q-C residual, 2026-08-02) — same three-guard retirement as FDA-SD1063.
            Assertions.assertEquals(List.of("CDISC-AD0132", "CDISC-AD0223", "CDISC-AD0225",
                    "CDISC-AD0361", "CDISC-AD0362", "CDISC-AD0500", "CDISC-AD0582", "CDISC-AD0586",
                    "CDISC-AD0700", "CDISC-AD0701", "CDISC-AD0704", "CDISC-AD0705", "CDISC-AD0720",
                    "CDISC-AD0897", "OPGAP-shares-at-least-one-element-with"), claiming);
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
         * jar is not an option either: cumba-corej attaches it on the deploy path only, by an
         * explicit decision in its root pom, and a test convenience is not a reason to reverse it.
         *
         * → They live in cumba-corej now, beside the source they read, as
         *   cumba-corej-core/src/test/java/net/cumba/corej/core/exec/EngineErrorMessageContractTest.
         *   That copy carries its own copies of the two message literals; ViolationNormaliser here
         *   is the other half of the pair, and the two MUST be changed together. A reworded engine
         *   message that this classifier no longer recognises degrades every affected ERROR to
         *   OTHER -- silently, which is exactly what the guards exist to prevent.
         */

        @Test
        void theCorpusErrorPinObservesExactlyTheTokenItClaims() throws Exception
        {
            // OPGAP-shares-at-least-one-element-with is the ONLY spec in the corpus that makes the
            // Java lane produce an ERROR, and therefore the only one whose `expected_errors` is
            // non-empty. The parity sweep already covers it, but a sweep failure says only "spec X
            // diverged"; this names the contract directly and fails with the reason in the message.
            //
            // It also proves the pin is not vacuous: the spec passes the sweep only because the
            // engine really emits NO_NATIVE_CHECK_EXPR here. Were the Check to become natively
            // compilable, observedReasons would be empty, expectedErrors would not, and both this
            // test and the sweep would go red — which is the intended signal, not a nuisance.
            Path spec = Path.of("rulespec", "specs", "OPGAP-shares-at-least-one-element-with.yaml");
            Assertions.assertTrue(Files.isRegularFile(spec),
                    "the corpus's only Java-ERROR pin is missing: " + spec.toAbsolutePath());

            SpecRunner.SpecResult result = SpecRunner.run(spec, SpecRunner.Mode.ASSERT);

            Assertions.assertEquals(RuleExecutionStatus.ERROR, result.engineStatus(),
                    "the operator gained a native form, or the spec stopped reaching it");
            Assertions.assertEquals(
                    List.of(ViolationNormaliser.ErrorReason.NO_NATIVE_CHECK_EXPR.name()),
                    result.observedReasons());
            Assertions.assertTrue(result.errorsMatch(),
                    () -> "the spec's expected_errors no longer matches what the engine produces: "
                            + result.formatDiff());
            Assertions.assertTrue(result.passed(),
                    () -> "the Java lane must pass this spec outright — it is Java-canonical and "
                            + "baselined only on the Python lane: " + result.formatDiff());
        }

    }
}
