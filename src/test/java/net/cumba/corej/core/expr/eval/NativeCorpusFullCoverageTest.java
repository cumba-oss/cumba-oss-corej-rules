package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.exec.ScopeMatcher;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.testkit.SyntheticDataTable;
import org.junit.jupiter.api.Test;

/**
 * The corpus-wide EVERY-RULE-IS-NATIVE gate. The legacy engine is gone: a rule with no native form
 * is an {@code ERROR} at execution, so this is a hard requirement rather than a migration tracker.
 *
 * <p>
 * <b>Static gate:</b> every rule in every shipped {@code rules/rules-*.json} package must be (a)
 * native-dispatched (non-null {@code checkExpr}), (b) a wildcard/GEN TEMPLATE — never executed
 * directly; its per-dataset expansions receive a {@code checkExpr} at the generation seam (P5) — or
 * (c) a {@code loadError} (it never evaluates). Tier-B metadata operands
 * ({@code define_variable_ccode}, {@code dataset_metadata}; deliberately unmapped, see
 * {@code MetadataOperandMapping}) and user-authored additions with unconventional shapes — which
 * may only SHRINK.
 * </p>
 *
 * <p>
 * <b>Dynamic gate:</b> every native-dispatched rule with plain-column operands is EXECUTED through
 * {@link RuleRunner} over a synthetic table and must come back with a verdict or a scope SKIP.
 * There is no second engine to fall back to, so <em>"it did not ERROR"</em> IS the native-execution
 * evidence: a rule that fails to dispatch natively returns an ERROR result, and every channel that
 * can carry one is pinned empty below.
 * </p>
 *
 * <h2>⛔ What replaced the recorder pin</h2>
 *
 * <p>
 * This gate used to end in {@code legacyRuns.isEmpty()} over {@code NativeExecutionRecorder}'s
 * {@code ruleId -> Backend} map. That class was the legacy engine's decommissioning instrument and
 * was retired with it (owner, 2026-09-18) — and the pin it carried was <b>unsatisfiable</b>:
 * {@code Backend} has exactly one constant and {@code ConcurrentHashMap} forbids null values, so no
 * recorded value could ever differ from {@code NATIVE}. It had been dead since the legacy
 * retirement, not merely quiet. The {@code unrecorded} list went with it for the same reason: every
 * {@code RuleExecutionResult} built before dispatch in {@code RuleRunner.execute} is
 * {@code SKIPPED} or {@code ERROR}, so {@code EXECUTED ⇒ recorded} held by construction of the
 * engine rather than by luck.
 * </p>
 *
 * <p>
 * What carries the gate now is the CENSUS — which is the half that was doing the work all along. An
 * emptiness assertion is most comfortable when nothing was observed at all, so each way a rule can
 * leave the covered population is pinned separately, and the population itself is floored:
 * </p>
 * <ul>
 * <li>{@code unreadable} — the column-type gate rejected every table the harness could build
 * ({@code 77c95e7d}, after that gate silently took 78 rules out of coverage);</li>
 * <li>{@code errored} — the run returned an ERROR that is <em>not</em> the column-type gate, so it
 * never dispatched either. Before this list existed the retry loop reported such a rule
 * <em>readable</em> and counted it as executed;</li>
 * <li>{@code threw} — the run threw. {@code executed++} and the {@code unreadable} pin both used to
 * sit inside the {@code try}, so a throwing rule vanished from the count <em>and</em> from the pin
 * at once — one exception away from the exact hole {@code unreadable} was added to close;</li>
 * <li>{@link #KNOWN_UNDISPATCHABLE} — the named exceptions to the line above, each pinned to the
 * population it belongs to and asserted to have been HIT, so an entry cannot quietly become dead
 * weight when its rule leaves the corpus.</li>
 * </ul>
 *
 * <p>
 * ⛔ <b>The floors are sized on the CORE-free corpus and BOTH counters are asserted.</b> Round 1
 * measured what asserting only the with-CORE pair is worth: four shipped non-CORE packages
 * ({@code rules-fda-sdtmig-3-1-3}, {@code -3-2}, {@code -3-3}, {@code -3-4} — 2124 rules, 16 % of
 * the corpus) were deleted from the corpus the gate read, and it stayed GREEN, because the CORE
 * family was pure slack against a CORE-free-sized floor. {@code ranCoreFree <= ran} by
 * construction, so the CORE-free assertion is unconditionally the stronger one.
 * </p>
 *
 * <p>
 * The census is printed to stdout on a PASSING run (the surefire report captures it), so "how much
 * headroom is left" is answerable without a build.
 * </p>
 */
class NativeCorpusFullCoverageTest
{

    /**
     * Floor on the number of record-data rules ({@code {ROW}} ∪ {@code {}}) this gate hands to
     * {@link RuleRunner} and gets a result — a verdict or a scope SKIP — back from. The corpus
     * population, in effect: with the not-reached-dispatch channels pinned empty, every candidate
     * but the {@link #KNOWN_UNDISPATCHABLE} ones lands here.
     */
    // ⭐ Every floor in this class is sized on the CORE-free corpus — the smallest shipped
    // distribution, and the shape of the OSS twin, which is to be kept in step with this file.
    // ⛔ Both the CORE-free and the with-CORE counter are asserted against it; see assertFloors.
    // Measured 2026-09-18 on the post-recorder counters: 13067 ran of 13070 candidates (the 3
    // KNOWN_UNDISPATCHABLE throwers are the remainder), 10986 of them CORE-free.
    // Floor = CORE-free − 2.6 %.
    private static final int RECORD_DATA_RAN_FLOOR = 10700;

    /**
     * Floor on the number of record-data rules that ran to a VERDICT rather than to a scope SKIP —
     * the population this gate's dispatch evidence actually speaks for, which is much smaller than
     * the one above: a rule the synthetic DM table does not apply to is SKIPPED before it evaluates
     * anything. Floor only, may only rise.
     *
     * <p>
     * ⛔ This is the floor that catches a coverage loss the population floor cannot see: rules
     * sliding from "evaluated" to "skipped" leave the candidate count untouched.
     * </p>
     *
     * <p>
     * ⚠ It is <b>not</b> the number the retired {@code NativeExecutionRecorder} used to report.
     * That counter was {@code !recorder.isEmpty()}, and {@code RuleRunner} records the dispatch
     * BEFORE {@code evaluateMetadataNative} can still return {@code metadataSkipped(...)} on a
     * missing provider — so it silently absorbed a skip-after-dispatch population that this counter
     * excludes. Every floor below was RE-MEASURED for that reason; none was carried across.
     * </p>
     */
    // Measured 2026-09-18: 7564 verdicts, 6123 of them CORE-free (5503 candidates scope-SKIPped).
    // Floor = CORE-free − 2.0 %.
    // ⚠ The retired recorder's counter read 7584 / 6143 on the same corpus. The 20-rule difference
    // is the skip-after-dispatch population this counter excludes and that one absorbed — real,
    // and measured here for the first time. The constant is deliberately NOT lowered for it: 6000
    // still clears the stricter count with 123 to spare.
    private static final int RECORD_DATA_RECORDED_FLOOR = 6000;

    /**
     * Floor on the number of metadata-family rules ({@code {VAR}} ∪ {@code {VAR,ROW}}) that run to
     * a result. Floor only, may only rise.
     *
     * <p>
     * ⚠ This population is an order of magnitude smaller than the record-data one, so its floor is
     * sized with the same relative margin, not the same slack. The floor it replaces
     * ({@code executed > 700}) was within ~400 of binding and said so nowhere.
     * </p>
     */
    // Measured 2026-09-18: 1196 ran of 1196 candidates, 1105 of them CORE-free (nothing throws in
    // this population). Floor = CORE-free − 5.0 %.
    private static final int METADATA_RAN_FLOOR = 1050;

    /**
     * Floor on the number of metadata-family rules that ran to a VERDICT rather than to a SKIP.
     * Floor only, may only rise.
     */
    // Measured 2026-09-18: 974 verdicts, 897 of them CORE-free (222 candidates scope-SKIPped).
    // Floor = CORE-free − 5.2 %. Unchanged by the recorder's retirement: this population is handed
    // a provider stub, so nothing here dispatches and is then metadata-skipped.
    private static final int METADATA_RECORDED_FLOOR = 850;

    /*
     * ⭐⭐ WHAT TO DO WHEN A FLOOR BECOMES UNREACHABLE — read this before editing one downwards.
     *
     * "Floors may only rise" is the rule because a silently-lowered floor is the whole defect class
     * this file exists for. It is not "floors may never fall": the two metadata floors sit at only
     * ~5 % CORE-free margin, and a LEGITIMATE retirement of ~60 metadata rules would take the
     * population under them with nothing wrong.
     *
     * The legal move in that case, and the only one:
     *   1. establish that the loss is intended — name the plan / commit that removed the rules, and
     *      check the shrink against it rule for rule. A floor is never lowered to make a red go
     *      away before that question is answered;
     *   2. re-measure BOTH populations from a clean run (the census prints them);
     *   3. lower the constant ONCE, to the new measurement minus the SAME relative margin it
     *      carries today (~2.5 % record-data, ~5 % metadata) — never to just under the observed
     *      number, which leaves the next regression no room to be caught in;
     *   4. replace the "Measured …" comment with the new numbers and the date, and say in the
     *      commit message which retirement paid for it.
     *
     * ⛔ Never widen the margin instead of lowering the floor, and never make a floor conditional on
     * which packages are present — that is how a population check stops checking the population.
     */

    /**
     * ⛔ The known rules this harness structurally cannot dispatch: id → the population it belongs
     * to plus a substring its failure must still carry. Listed — with the reason — instead of being
     * swallowed by a bare {@code catch}, so a rule that starts throwing for any OTHER reason, or
     * any other rule that starts throwing, fails this gate.
     *
     * <p>
     * ✅ <b>Empty since 2026-09-25.</b> Its one entry was {@code CDISC-AD0720} (ADaM 1.1/1.2/1.3),
     * which reads a wildcard operand across a foreign dataset and threw <i>"requires a JoinLookup
     * for foreign dataset"</i> while the harness's resolver was {@code _ -> null}.
     * {@code PLAN-join-key-authoring-gate} gave the harness a {@link SyntheticInventory} serving
     * each joined dataset (the rule's pairing groups are undecidable without one), so the rule now
     * dispatches and the entry was REMOVED — the shrink this map is allowed. The machinery stays,
     * so a new structural thrower is named here rather than swallowed.
     * </p>
     *
     * <p>
     * ⚑ May only SHRINK: an entry is removed when its rule dispatches, never widened to quiet a new
     * failure. Give the harness what the rule needs instead.
     * </p>
     *
     * <p>
     * ⛔ Every entry is asserted to have been HIT in its own population
     * ({@link DispatchCensus#assertKnownUndispatchableStillLive()}). Without that pin the map is a
     * one-way allowlist: round 1 measured that deleting {@code CDISC-AD0720} from the corpus
     * altogether left this gate GREEN with {@code threw (KNOWN_UNDISPATCHABLE) = 0}, the entry
     * silently dead weight and the rule silently gone.
     * </p>
     *
     * <p>
     * ⚑ The required substring is ENGINE PROSE, and it IS pinned engine-side — the same way
     * {@link #TYPE_MISMATCH} is. {@code EngineErrorMessageContractTest} in the engine repository
     * holds it as {@code JOIN_LOOKUP_REQUIRED_MESSAGE_FRAGMENT}, and
     * {@code theJoinLookupRequiredFragmentIsStillTheEngineSWording} asserts it appears exactly ONCE
     * in {@code ValueResolver} — over the source with its comments blanked out, so a
     * cross-reference quoting the old wording cannot disarm the pin. A reword there therefore reds
     * a test in its own repository, whose failure message names this map by name, rather than only
     * redding this gate from another repo with nothing linking the two.
     * </p>
     *
     * <p>
     * ⛔ The pin is a tripwire, not a substitute: a reword still has to change BOTH sides in the
     * SAME wave — the constant over there and this entry's {@code messageSubstring} here.
     * </p>
     *
     * <p>
     * ⚠ Corrected 2026-09-18: this used to say the substring had "no contract test behind it" and
     * advised "better, pin the message engine-side the way {@code TYPE_MISMATCH} is". That pin was
     * written fifteen minutes later in the same wave, and this note was never updated.
     * </p>
     */
    private static final Map<String, Undispatchable> KNOWN_UNDISPATCHABLE = Map.of();

    /**
     * One {@link #KNOWN_UNDISPATCHABLE} entry.
     *
     * @param varCursor
     *            the population the rule belongs to — {@code true} metadata-family, {@code false}
     *            record-data. Pins WHERE the entry must be hit, so the liveness assertion of one
     *            population is not satisfied by the other's throwers.
     * @param messageSubstring
     *            a substring the failure must still carry.
     */
    private record Undispatchable(boolean varCursor, String messageSubstring)
    {
    }

    @Test
    void everyShippedRuleIsNativeTemplateOrLoadError() throws Exception
    {
        Path rulesDir = CorpusPaths.rules();
        List<String> unexplained = new ArrayList<>();
        try (Stream<Path> files = Files.list(rulesDir))
        {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.toString().endsWith(".json")).sorted().toList())
            {
                RulePackage pkg = RulePackageLoader.load(file);
                for (Rule rule : pkg.getRules().values())
                {
                    if (rule == null || rule.getCheckExpr() != null || rule.getLoadError() != null
                            || rule.getCheck() == null)
                    {
                        continue; // native, load-error, or no Check (never evaluates)
                    }
                    if (WildcardExpander.containsWildcards(rule))
                    {
                        continue; // template — expansions are nativized at the generation seam
                    }
                    String id = rule.getCore() != null && rule.getCore().getId() != null
                            ? rule.getCore().getId()
                            : rule.getId();
                    unexplained.add(file.getFileName() + "/" + id);
                }
            }
        }
        assertTrue(unexplained.isEmpty(),
                "EVERY-RULE-IS-NATIVE gate: rule(s) have no native form and would be reported as "
                        + "an ERROR at execution (" + unexplained.size() + "):\n"
                        + String.join("\n", unexplained));
    }


    @Test
    void everyNativeDispatchedRecordDataRuleReachesAVerdict() throws Exception
    {
        // Execute every plain-operand RECORD_DATA rule with a checkExpr end-to-end through
        // RuleRunner over a synthetic table. With no fallback engine left, an ERROR is what a
        // failure to dispatch natively looks like — so the empty error channels ARE the gate.
        DispatchCensus census = dispatchCensus("record-data ({ROW} ∪ {})", false, "X", null);
        census.print(RECORD_DATA_RAN_FLOOR, RECORD_DATA_RECORDED_FLOOR);
        census.assertCensusAddsUp();
        census.assertEveryRuleReachedDispatch();
        census.assertKnownUndispatchableStillLive();
        census.assertFloors(RECORD_DATA_RAN_FLOOR, RECORD_DATA_RECORDED_FLOOR);
    }


    @Test
    void everyNativeDispatchedMetadataFamilyRuleReachesAVerdict() throws Exception
    {
        // Guard-residual P4 (PLAN-native-runtime-guard-residual): widen the dynamic gate beyond
        // RECORD_DATA — every metadata / value-check / presence-family rule with a checkExpr is
        // EXECUTED through RuleRunner over a synthetic table with simple library / define provider
        // stubs, and must reach a verdict or a scope SKIP. With the D5 wiring complete (Step-1
        // fold, Step-3/4 cascade, precondition fold), these families dispatch through the same
        // native path, so the same absence of ERRORs carries the same evidence here.
        DispatchCensus census = dispatchCensus("metadata family ({VAR} ∪ {VAR,ROW})", true,
                "STUDYID", stubProvider());
        census.print(METADATA_RAN_FLOOR, METADATA_RECORDED_FLOOR);
        census.assertCensusAddsUp();
        census.assertEveryRuleReachedDispatch();
        census.assertKnownUndispatchableStillLive();
        census.assertFloors(METADATA_RAN_FLOOR, METADATA_RECORDED_FLOOR);
    }

    // ------------------------------------------------------------------


    /**
     * Runs one population of the corpus through {@link RuleRunner} — the only engine, and with no
     * instrumentation attached — and returns the census: the counters, and every rule that did NOT
     * reach dispatch, split by the channel that took it out.
     *
     * @param aLabel
     *            the population's name, for the printed census.
     * @param aVarCursor
     *            {@code true} for the per-variable (metadata) population, {@code false} for the
     *            record-data one.
     * @param aFallbackColumn
     *            the column to declare when the rule's Check reads none.
     * @param aProvider
     *            the metadata provider stub, or {@code null}.
     */
    private static DispatchCensus dispatchCensus(String aLabel, boolean aVarCursor,
            String aFallbackColumn,
            net.cumba.corej.core.exec.@org.jspecify.annotations.Nullable MetadataProvider aProvider)
        throws IOException
    {
        DispatchCensus census = new DispatchCensus(aLabel, aVarCursor);
        Path rulesDir = CorpusPaths.rules();
        try (Stream<Path> files = Files.list(rulesDir))
        {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.toString().endsWith(".json")).sorted().toList())
            {
                RulePackage pkg = RulePackageLoader.load(file);
                for (Rule rule : pkg.getRules().values())
                {
                    if (rule == null || rule.getCheckExpr() == null
                            || rule.getEvaluationDomain() == null
                            || rule.getEvaluationDomain().varCursor() != aVarCursor)
                    {
                        continue;
                    }
                    census.candidates++;
                    Set<String> cols = new LinkedHashSet<>();
                    Scan.columns(rule.getCheckExpr(), cols);
                    if (cols.isEmpty())
                    {
                        cols.add(aFallbackColumn);
                    }
                    census.run(file, rule, cols, aProvider);
                }
            }
        }
        return census;
    }

    /**
     * The dispatch census for one population: the counters, the error channels kept distinct, and
     * the liveness pin on {@link #KNOWN_UNDISPATCHABLE}.
     *
     * <p>
     * ⛔ Everything here exists so that a rule LEAVING the covered population is loud. The gate's
     * substance is that every candidate reaches a verdict or a scope skip; a rule that quietly
     * stops being a candidate would satisfy that for free, which is what the floors are for.
     * </p>
     */
    private static final class DispatchCensus
    {

        /** Package-file prefix of the CORE family, absent from the OSS twin's corpus. */
        private static final String CORE_FAMILY = "rules-core-";

        private final String label;

        /**
         * The population this census covers — {@code true} metadata-family, {@code false}
         * record-data. Selects which {@link #KNOWN_UNDISPATCHABLE} entries must be hit here.
         */
        private final boolean varCursor;

        /** Rules the column-type gate rejected on every table this harness could build. */
        private final List<String> unreadable = new ArrayList<>();

        /** Rules whose run threw and are not named in {@link #KNOWN_UNDISPATCHABLE}. */
        private final List<String> threw = new ArrayList<>();

        /** Rules whose run ERRORed for a reason that is not the column-type gate. */
        private final List<String> errored = new ArrayList<>();

        /** Ids of the {@link #KNOWN_UNDISPATCHABLE} entries actually hit — the liveness pin. */
        private final Set<String> knownThrewIds = new TreeSet<>();

        /** Rules matching the population filter. */
        private int candidates;

        /** Rules that returned a result (verdict or SKIP) rather than throwing or ERRORing. */
        private int ran;

        /** Of {@link #ran}, those outside the CORE family — what the OSS twin measures. */
        private int ranCoreFree;

        /**
         * Of {@link #ran}, those that reached a VERDICT. Named for the retired
         * {@code NativeExecutionRecorder}, whose {@code ruleId -> Backend} map this counter
         * replaces — and deliberately NOT equal to it: the recorder was written to before
         * {@code evaluateMetadataNative} could still skip, so it over-counted by an unmeasured
         * skip-after-dispatch population. {@link #recorded} and {@link #skipped} partition
         * {@link #ran}.
         */
        private int recorded;

        /** Of {@link #recorded}, those outside the CORE family. */
        private int recordedCoreFree;

        /** Rules that ran but SKIPPED (the fixture is a DM table; the rule is for another). */
        private int skipped;

        /** Throwers named — with their population and reason — in {@link #KNOWN_UNDISPATCHABLE}. */
        private int knownThrew;

        DispatchCensus(String aLabel, boolean aVarCursor)
        {
            this.label = aLabel;
            this.varCursor = aVarCursor;
        }


        /**
         * Executes one rule and books the outcome into exactly one channel.
         */
        void run(Path aFile, Rule aRule, Set<String> aColumns,
                net.cumba.corej.core.exec.@org.jspecify.annotations.Nullable MetadataProvider aProvider)
        {
            String id = ruleIdOf(aRule);
            String who = aFile.getFileName() + "/" + id;
            Attempt attempt;
            try
            {
                attempt = runAgainstATableTheRuleCanRead(aRule, aColumns, aProvider);
            }
            catch (RuntimeException ex)
            {
                // ⛔ NOT swallowed. A synthetic-context error (missing operation/provider) is not a
                // dispatch failure of the rule, but it is not coverage either: the rule never
                // reached the evaluator, so it is pinned here instead of vanishing from both the
                // count and the pins.
                attempt = new Attempt(Channel.THREW,
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            boolean coreFree = !aFile.getFileName().toString().startsWith(CORE_FAMILY);
            switch (attempt.channel())
            {
            case RAN ->
            {
                ran++;
                recorded++;
                if (coreFree)
                {
                    ranCoreFree++;
                    recordedCoreFree++;
                }
            }
            case SKIPPED ->
            {
                ran++;
                skipped++;
                if (coreFree)
                {
                    ranCoreFree++;
                }
            }
            case TYPE_UNREADABLE -> unreadable.add(who + " (" + attempt.detail() + ")");
            case ERRORED -> errored.add(who + " -> " + attempt.detail());
            case THREW ->
            {
                Undispatchable known = KNOWN_UNDISPATCHABLE.get(id);
                if (known != null && known.varCursor() == varCursor
                        && attempt.detail().contains(known.messageSubstring()))
                {
                    knownThrew++;
                    knownThrewIds.add(id);
                }
                else
                {
                    threw.add(who + " -> " + attempt.detail());
                }
            }
            }
        }


        /**
         * ⛔ The three channels that take a rule out of this gate's reach, each pinned empty with
         * the offending rules named. Erosion through any of them must not be silent.
         */
        void assertEveryRuleReachedDispatch()
        {
            assertTrue(unreadable.isEmpty(),
                    label + ": rule(s) could not be handed a table whose column types they can "
                            + "read, so their dispatch was never observed (" + unreadable.size()
                            + "):\n" + String.join("\n", unreadable.stream().limit(20).toList()));
            assertTrue(errored.isEmpty(),
                    label + ": rule(s) returned an ERROR that is not the column-type gate, so they "
                            + "never dispatched and are outside this gate's reach ("
                            + errored.size() + "):\n"
                            + String.join("\n", errored.stream().limit(20).toList()));
            assertTrue(threw.isEmpty(),
                    label + ": rule(s) THREW instead of returning a verdict, so their dispatch was "
                            + "never observed (" + threw.size() + "):\n"
                            + String.join("\n", threw.stream().limit(20).toList()));
        }


        /**
         * ⛔ The liveness pin on {@link #KNOWN_UNDISPATCHABLE}: every entry declared for THIS
         * population was actually hit. Without it the map is a one-way allowlist — an entry whose
         * rule has left the corpus keeps the gate green while quietly covering nothing, which is
         * exactly what round 1 measured by deleting {@code CDISC-AD0720} from the corpus.
         *
         * <p>
         * The other direction needs no assertion of its own: a thrower that is not a declared entry
         * of this population lands in {@code threw}, which {@link #assertEveryRuleReachedDispatch}
         * pins empty.
         * </p>
         */
        void assertKnownUndispatchableStillLive()
        {
            Set<String> expected = new TreeSet<>(KNOWN_UNDISPATCHABLE.entrySet().stream()
                    .filter(e -> e.getValue().varCursor() == varCursor).map(Map.Entry::getKey)
                    .toList());
            assertEquals(expected, knownThrewIds, label
                    + ": KNOWN_UNDISPATCHABLE entry/entries declared for this population were "
                    + "never hit, so they cover nothing — the rule left the corpus, or it "
                    + "now dispatches and the entry must be REMOVED (expected " + expected
                    + ", hit " + knownThrewIds + ")");
        }


        /**
         * ⚑ The census adds up: every candidate landed in exactly one channel, and
         * {@link #recorded} + {@link #skipped} still partition {@link #ran}.
         *
         * <p>
         * Both hold by construction of {@link #run} today. They are asserted because the next
         * channel added here is one {@code case} away from being counted nowhere, and a census that
         * silently stops summing is indistinguishable from one that has nothing to report.
         * </p>
         */
        void assertCensusAddsUp()
        {
            assertEquals(candidates,
                    ran + knownThrew + threw.size() + errored.size() + unreadable.size(),
                    label + ": the census does not add up — " + candidates + " candidate rules but "
                            + "ran=" + ran + " knownThrew=" + knownThrew + " threw=" + threw.size()
                            + " errored=" + errored.size() + " unreadable=" + unreadable.size()
                            + "; a rule was counted in no channel (or in two)");
            assertEquals(ran, recorded + skipped,
                    label + ": ran does not partition into recorded + skipped — ran=" + ran
                            + " recorded=" + recorded + " skipped=" + skipped);
        }


        /**
         * ⛔ The population floors — measured, and they may only rise (see the note beside the
         * constants for the one legal way to lower one). {@code ran} pins that the corpus still HAS
         * this many candidate rules; {@code recorded} pins how many of them reached a verdict
         * rather than a scope skip, which no count in this class made visible before wave 2b.
         *
         * <p>
         * ⭐ The CORE-free rows are the BINDING pair: the floors are sized on the CORE-free corpus,
         * and {@code ranCoreFree <= ran} by construction, so asserting the CORE-free counter is
         * unconditionally the stronger of the two. Each pair therefore asserts the CORE-free row
         * <b>first</b> — a shrink that takes both below the floor must report the binding row, not
         * the subsumed one. The with-CORE assertions are kept beside them — subsumed on purpose —
         * so a failure names the row the reader is looking at, and so the printed census and the
         * assertions cover the same four numbers.
         * </p>
         *
         * <p>
         * ⛔ Asserting only the with-CORE pair is what round 1 measured and rejected: four shipped
         * non-CORE packages (2124 rules, 16 % of the corpus) were deleted and this gate stayed
         * green, the CORE family absorbing the whole loss as slack.
         * </p>
         */
        void assertFloors(int aRanFloor, int aRecordedFloor)
        {
            // ⭐ The CORE-free assertion of each pair fires FIRST: the floors are sized on the
            // CORE-free corpus and ranCoreFree <= ran, so the with-CORE assertion is subsumed —
            // firing it first would hand the reader the weaker message for the same regression.
            assertTrue(ranCoreFree >= aRanFloor,
                    label + ": the CORE-FREE executed population SHRANK — " + ranCoreFree
                            + " non-CORE rules ran, below the floor " + aRanFloor
                            + " (this is the BINDING row: the floors are sized on the CORE-free "
                            + "corpus and the OSS twin ships no CORE family, so a CORE-free loss "
                            + "cannot be absorbed as slack)");
            assertTrue(ran >= aRanFloor,
                    label + ": the executed population SHRANK — " + ran + " rules ran, below the "
                            + "floor " + aRanFloor
                            + " (floors may only rise — investigate the lost rules)");
            assertTrue(recordedCoreFree >= aRecordedFloor,
                    label + ": CORE-FREE dispatch coverage REGRESSED — " + recordedCoreFree
                            + " non-CORE rules reached a verdict, below the floor " + aRecordedFloor
                            + " (this is the BINDING row: the floors are sized on the CORE-free "
                            + "corpus and the OSS twin ships no CORE family, so a CORE-free loss "
                            + "cannot be absorbed as slack)");
            assertTrue(recorded >= aRecordedFloor,
                    label + ": dispatch coverage REGRESSED — " + recorded
                            + " rules reached a verdict, below the floor " + aRecordedFloor
                            + " (floors may only rise; a rule sliding from evaluated to SKIPPED "
                            + "lands here and nowhere else)");
        }


        /**
         * Prints the census to stdout — surefire captures it, so the headroom above the floor is
         * readable from a PASSING run's report rather than only from a failure message.
         */
        void print(int aRanFloor, int aRecordedFloor)
        {
            StringBuilder sb = new StringBuilder(512);
            sb.append("\n=== Native dispatch census: ").append(label).append(" ===\n");
            sb.append(String.format("%-34s %8d%n", "candidate rules", candidates));
            sb.append(String.format("%-34s %8d  (floor >= %d, headroom %d)%n",
                    "ran (verdict or SKIP)", ran, aRanFloor, ran - aRanFloor));
            sb.append(String.format("%-34s %8d  (floor >= %d, headroom %d)%n", "ran, CORE-free",
                    ranCoreFree, aRanFloor, ranCoreFree - aRanFloor));
            sb.append(String.format("%-34s %8d  (floor >= %d, headroom %d)%n", "recorded (verdict)",
                    recorded, aRecordedFloor, recorded - aRecordedFloor));
            sb.append(
                    String.format("%-34s %8d  (floor >= %d, headroom %d)%n", "recorded, CORE-free",
                            recordedCoreFree, aRecordedFloor, recordedCoreFree - aRecordedFloor));
            sb.append("⭐ the CORE-free rows are the binding case: the OSS twin, which is to be "
                    + "kept in step with this file, has no CORE family. All four are asserted.\n");
            sb.append(String.format("%-34s %8d%n", "unreadable (column-type gate)",
                    unreadable.size()));
            sb.append(String.format("%-34s %8d%n", "errored (other ERROR)", errored.size()));
            sb.append(String.format("%-34s %8d%n", "threw (unexplained)", threw.size()));
            sb.append(String.format("%-34s %8d  %s%n", "threw (KNOWN_UNDISPATCHABLE)", knownThrew,
                    knownThrewIds));
            sb.append(String.format("%-34s %8d%n", "skipped (not this fixture's domain)", skipped));
            sb.append("=======================================\n");
            System.out.print(sb);
        }

    }


    /** Which channel one dispatch attempt fell into. */
    private enum Channel
    {
        /** The rule ran to a verdict, so it reached the native evaluator. */
        RAN,
        /**
         * The rule ran but SKIPPED before evaluating anything — the fixture is a DM table, and a
         * rule scoped to another dataset does not apply to it. Counted, never pinned: it is the
         * harness's shape, not a regression. The recorded floor is what keeps it from growing
         * unnoticed.
         */
        SKIPPED,
        /** The column-type gate rejected every table the harness could build. */
        TYPE_UNREADABLE,
        /** The run returned an ERROR that is not the column-type gate. */
        ERRORED,
        /** The run threw. */
        THREW
    }


    /**
     * One dispatch attempt's outcome.
     *
     * @param channel
     *            the channel it fell into.
     * @param detail
     *            why, for the pin's failure message; empty for {@link Channel#RAN}.
     */
    private record Attempt(Channel channel, String detail)
    {

        /** Ran to a verdict. */
        static final Attempt RAN = new Attempt(Channel.RAN, "");

        /** Ran, but SKIPPED before evaluating anything (the fixture is not the rule's domain). */
        static final Attempt SKIPPED = new Attempt(Channel.SKIPPED, "");
    }

    /** Minimal provider stub so library_* / define_* rules execute instead of SKIPPING. */
    private static net.cumba.corej.core.exec.MetadataProvider stubProvider()
    {
        return new net.cumba.corej.core.exec.MetadataProvider()
        {

            @Override
            public List<String> getRequiredVariables(String aDomain)
            {
                return List.of("STUDYID");
            }


            @Override
            public List<String> getExpectedVariables(String aDomain)
            {
                return List.of("STUDYID");
            }


            @Override
            public List<String> getColumnOrder(String aDomain)
            {
                return List.of("STUDYID");
            }


            @Override
            public boolean isDomainCustom(String aDomain)
            {
                return false;
            }


            @Override
            public List<String> getCodelistTerms(String aCodelistCode)
            {
                return List.of();
            }


            @Override
            public Map<String, String> getVariableMetadata(String aDomain, String aVariable)
            {
                return Map.of("name", aVariable, "label", "Label", "simpleDatatype", "Char", "role",
                        "Identifier", "core", "Req");
            }


            @Override
            public List<Map<String, String>> getDomainVariables(String aDomain)
            {
                return List.of(getVariableMetadata("DM", "STUDYID"));
            }


            @Override
            public Map<String, String> getDatasetMetadata(String aDomain)
            {
                return Map.of("name", "DM", "label", "Demographics", "className",
                        "SPECIAL PURPOSE");
            }


            @Override
            public java.util.Optional<Boolean> isCodelistExtensible(String aCodelistName)
            {
                return java.util.Optional.of(true);
            }


            @Override
            public String getStandard()
            {
                return "sdtmig";
            }

        };
    }

    /** Minimal column scan over the Expr tree (mirrors the corpus-parity harness). */
    private static final class Scan
    {

        static void columns(net.cumba.corej.core.expr.ast.Expr e, Set<String> out)
        {
            switch (e)
            {
            case net.cumba.corej.core.expr.ast.Expr.And a -> a.parts()
                    .forEach(p -> columns(p, out));
            case net.cumba.corej.core.expr.ast.Expr.Or o -> o.parts().forEach(p -> columns(p, out));
            case net.cumba.corej.core.expr.ast.Expr.Not n -> columns(n.inner(), out);
            case net.cumba.corej.core.expr.ast.Expr.Binary b ->
            {
                columns(b.left(), out);
                columns(b.right(), out);
            }
            case net.cumba.corej.core.expr.ast.Expr.Call c ->
            {
                c.args().forEach(p -> columns(p, out));
                c.kwargs().values().forEach(p -> columns(p, out));
            }
            case net.cumba.corej.core.expr.ast.Expr.Ref r ->
            {
                if (r.kind() == net.cumba.corej.core.expr.OperandKind.COLUMN
                        && !r.name().contains("."))
                {
                    out.add(r.name());
                }
            }
            case net.cumba.corej.core.expr.ast.Expr.Lit lit ->
            {
                if (lit.kind() == net.cumba.corej.core.expr.ast.Expr.LitKind.LIST)
                {
                    @SuppressWarnings("unchecked")
                    List<net.cumba.corej.core.expr.ast.Expr> items = (List<net.cumba.corej.core.expr.ast.Expr>) lit
                            .value();
                    items.forEach(p -> columns(p, out));
                }
            }
            }
        }

    }

    /**
     * The rule's reportable id — {@code Core.Id} where present, else the internal id.
     *
     * @param aRule
     *            the rule.
     * @return the id for a failure message.
     */
    private static String ruleIdOf(Rule aRule)
    {
        return aRule.getCore() != null && aRule.getCore().getId() != null ? aRule.getCore().getId()
                : String.valueOf(aRule.getId());
    }

    /**
     * The column-type gate's message prefix. Pinned engine-side by
     * {@code EngineErrorMessageContractTest} at its three builder sites, which is what makes
     * matching on it a contract rather than a guess — the same basis {@code ViolationNormaliser}
     * uses to classify this ERROR.
     */
    private static final String TYPE_MISMATCH = "column-type mismatch: ";

    /**
     * Executes {@code rule} against a synthetic table whose column TYPES the rule can actually
     * read, retrying with one more column declared numeric each time the column-type gate rejects
     * the table.
     *
     * <p>
     * ⚠⚠ Why this exists. The table used to declare <b>every</b> column {@code STRING}. Once
     * {@code PLAN-column-type-conformance}'s gate landed, 78 correctly-authored rules — reading
     * {@code AGE}, {@code EXDOSE}, {@code PCLLOQ}, {@code ASTDT} and friends — errored on the
     * fixture rather than executing. ⛔ And the damage was <em>not</em> to the {@code executed}
     * count, which was incremented for any call that did not throw and so never noticed:
     * {@code RuleRunner} returns the mismatch as an ERROR result. The damage was to the gate's real
     * assertion — then an emptiness pin over the recorder's map, today {@link Channel#ERRORED} —
     * which says nothing about a rule that never dispatches. Either way it stopped covering those
     * 78 rules in silence: an emptiness assertion passes most comfortably when nothing was observed
     * at all.
     * </p>
     * <p>
     * ⛔ An ERROR that is NOT the column-type gate is reported as {@link Channel#ERRORED} rather
     * than as a successful run. It used to return "readable": the rule then dispatched nothing,
     * recorded nothing, and still counted as executed — the same hole as above, through a second
     * door.
     * </p>
     * <p>
     * Declaring a column numeric here is not a claim about the standard — it is the harness
     * adapting its fixture to the rule under test, which is the right behaviour for a
     * <em>dispatch</em> harness. Type correctness is asserted by the {@code .cdt} corpus scenarios,
     * against real declared types.
     * </p>
     *
     * @param aRule
     *            the rule to execute.
     * @param aColumns
     *            the columns its Check reads.
     * @param aProvider
     *            the metadata provider stub, or {@code null}.
     */
    private static Attempt runAgainstATableTheRuleCanRead(Rule aRule, Set<String> aColumns,
            net.cumba.corej.core.exec.@org.jspecify.annotations.Nullable MetadataProvider aProvider)
    {
        Set<String> numeric = new LinkedHashSet<>();
        Set<String> columns = new LinkedHashSet<>(aColumns);
        requiredBareColumns(aRule, columns, numeric);
        Map<String, Set<String>> foreign = foreignColumns(aRule);
        int columnCount = columns.size();
        for (Set<String> cols : foreign.values())
        {
            columnCount += cols.size();
        }
        // Bounded by the column count: each retry declares one more column numeric, so the loop
        // cannot outlive the columns it has to convert.
        for (int attempt = 0; attempt <= columnCount; attempt++)
        {
            SyntheticDataTable table = SyntheticDataTable.withNumeric("DM", List.copyOf(columns),
                    numeric, 5);
            DatasetResolver resolver = foreign.isEmpty() ? _ -> null
                    : new SyntheticInventory(foreign, numeric);
            RuleExecutionResult res = RuleRunnerCalls.execute(aRule, table, resolver, "DM",
                    aProvider, null, aProvider);
            String msg = res.getStatusMessage();
            if (!res.isError())
            {
                return res.isSkipped() ? Attempt.SKIPPED : Attempt.RAN;
            }
            if (msg == null || !msg.startsWith(TYPE_MISMATCH))
            {
                return new Attempt(Channel.ERRORED, String.valueOf(msg));
            }
            String offender = msg.substring(TYPE_MISMATCH.length()).split(" ", 2)[0];
            if (!numeric.add(offender))
            {
                // already flipped and still rejected — not fixable here
                return new Attempt(Channel.TYPE_UNREADABLE, "still rejected on " + offender);
            }
        }
        return new Attempt(Channel.TYPE_UNREADABLE, "retries exhausted");
    }


    /**
     * Adds to {@code aColumns} the literal, unqualified names the rule's
     * {@code Requirements.Variables} demand be present — every {@code All} entry and the bare
     * member of every {@code All_Or_None} group — and marks a {@code :N}-typed one numeric.
     *
     * <p>
     * ⭐ Added with {@code PLAN-join-key-authoring-gate} (2026-09-25). That sweep declared every
     * keyed join's first key bare in {@code All} (its Q9) and paired every key column with its
     * joined twin in an {@code All_Or_None} group; a key such as {@code USUBJID} is almost never
     * read by the {@code Check} itself, so a table built from the {@code Check}'s columns alone
     * made ~240 rules slide from "evaluated" to "skipped" on this harness — the coverage loss
     * {@link #RECORD_DATA_RECORDED_FLOOR} exists to catch. A dispatch harness gives the rule what
     * it declares it needs; whether the declaration is right is the {@code .cdt} corpus's question.
     * </p>
     *
     * <p>
     * Pattern entries (globs, {@code /regex/}, marker templates), {@code --} placeholders and
     * {@code Any} / {@code None} are left alone: the first two name no single column, and the last
     * two are satisfied — or unmet — by design of the rule, not by the harness.
     * </p>
     */
    private static void requiredBareColumns(Rule aRule, Set<String> aColumns, Set<String> aNumeric)
    {
        VariableRequirement vars = aRule.getRequirements() == null ? null
                : aRule.getRequirements().getVariables();
        if (vars == null)
        {
            return;
        }
        List<String> entries = new ArrayList<>();
        if (vars.getAll() != null)
        {
            entries.addAll(vars.getAll());
        }
        if (vars.getAllOrNoneGroups() != null)
        {
            vars.getAllOrNoneGroups().forEach(entries::addAll);
        }
        for (String raw : entries)
        {
            String entry = raw.trim();
            boolean numericTag = entry.endsWith(":N") || entry.endsWith(":Num");
            String name = TYPE_TAG.matcher(entry).replaceFirst("");
            if (name.contains(".") || name.startsWith("--") || ScopeMatcher.isPatternEntry(name))
            {
                continue;
            }
            aColumns.add(name);
            if (numericTag)
            {
                aNumeric.add(name);
            }
        }
    }

    /** A {@code :N} / {@code :C} (or {@code :Num} / {@code :Char}) type tag on a requirement. */
    private static final java.util.regex.Pattern TYPE_TAG = java.util.regex.Pattern
            .compile(":(N|C|Num|Char)$");

    /**
     * The foreign datasets a keyed rule joins, each with the columns the rule needs of it: the
     * joined side of every key, and every qualified {@code All} / {@code All_Or_None} entry naming
     * it. {@code Child} entries (their parent is resolved per row) and expansion templates
     * ({@code &}-names) are skipped.
     *
     * <p>
     * ⭐ Added with {@code PLAN-join-key-authoring-gate}, see {@link #requiredBareColumns}: a
     * qualified requirement entry is <em>undecidable</em> with the bare {@code _ -> null} resolver
     * (no foreign metadata source), and an undecidable entry SKIPS the rule by design — so without
     * these datasets every keyed rule's pairing group skipped it here.
     * </p>
     */
    private static Map<String, Set<String>> foreignColumns(Rule aRule)
    {
        Map<String, Set<String>> out = new java.util.LinkedHashMap<>();
        if (aRule.getMatchDatasets() != null)
        {
            for (MatchDataset md : aRule.getMatchDatasets())
            {
                String name = md.getName();
                List<String> right = md.getRightKeys();
                if (name == null || right == null || right.isEmpty() || name.startsWith("&")
                        || name.contains("--") || Boolean.TRUE.equals(md.getChild()))
                {
                    continue;
                }
                Set<String> cols = out.computeIfAbsent(name, _ -> new LinkedHashSet<>());
                right.stream().filter(k -> !k.startsWith("&")).forEach(cols::add);
            }
        }
        VariableRequirement vars = aRule.getRequirements() == null ? null
                : aRule.getRequirements().getVariables();
        if (vars != null)
        {
            List<String> entries = new ArrayList<>();
            if (vars.getAll() != null)
            {
                entries.addAll(vars.getAll());
            }
            if (vars.getAllOrNoneGroups() != null)
            {
                vars.getAllOrNoneGroups().forEach(entries::addAll);
            }
            for (String raw : entries)
            {
                String entry = TYPE_TAG.matcher(raw.trim()).replaceFirst("");
                int dot = entry.indexOf('.');
                if (dot <= 0 || entry.startsWith("/"))
                {
                    continue;
                }
                String dataset = entry.substring(0, dot);
                String column = entry.substring(dot + 1);
                if (out.containsKey(dataset) && !ScopeMatcher.isPatternEntry(column))
                {
                    out.get(dataset).add(column);
                }
            }
        }
        return out;
    }

    /**
     * A resolver WITH an inventory — the shape {@code ScopeVariableSource.of} needs to decide a
     * qualified requirement entry — serving one synthetic table per joined dataset. Columns named
     * in {@code numeric} (bare, or qualified with the dataset's name) are numeric, so a key the
     * primary had to declare numeric is numeric on the joined side too.
     */
    private static final class SyntheticInventory implements DatasetResolver.WithInventory
    {

        private final Map<String, Set<String>> columns;

        private final Set<String> numeric;

        SyntheticInventory(Map<String, Set<String>> aColumns, Set<String> aNumeric)
        {
            columns = aColumns;
            numeric = aNumeric;
        }


        @Override
        public Set<String> availableDatasets()
        {
            return columns.keySet();
        }


        @Override
        public net.cumba.datatable.@org.jspecify.annotations.Nullable IDataTable resolve(
                String aName)
        {
            Set<String> cols = columns.get(aName);
            if (cols == null)
            {
                return null;
            }
            Set<String> num = new LinkedHashSet<>();
            for (String c : cols)
            {
                if (numeric.contains(c) || numeric.contains(aName + "." + c))
                {
                    num.add(c);
                }
            }
            return SyntheticDataTable.withNumeric(aName, List.copyOf(cols), num, 5);
        }
    }
}
