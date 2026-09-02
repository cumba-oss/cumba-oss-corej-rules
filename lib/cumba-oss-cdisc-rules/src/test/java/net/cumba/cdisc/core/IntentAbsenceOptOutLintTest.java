package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionExpression;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * <b>Step 1 of {@code plans/PLAN-absent-required-dataset-skip.md}</b> — the <b>intent opt-out
 * surface</b>, asserted in the build. Gate <b>K5c</b>. {@code Fix #207}.
 *
 * <p>
 * {@link IntentAbsenceOptOut} derives the set of rules for which an absent foreign dataset is
 * <em>intent</em> rather than a precondition. This test pins that derivation two ways:
 * </p>
 * <ol>
 * <li><b>the ratchet</b> — the corpus population is exactly the four
 * {@code CDISC-SEND-0105/-0105-1/-0106/-0106-1} rules, written to
 * {@code documentation/derivation/intent-absence-opt-out.tsv}; and</li>
 * <li><b>the positive controls</b> — synthetic fixtures that fail the moment any single clause of
 * the predicate is neutered, plus corpus controls that fail the moment the sweep stops reading real
 * rules.</li>
 * </ol>
 *
 * <h2>&#9888;&#9888; Why the controls are not optional ({@code Fix #191})</h2>
 *
 * <p>
 * A guard that cannot see what it guards is worse than none. Two independent ways this lint could
 * lie:
 * </p>
 * <ul>
 * <li><b>Widening.</b> Relax any clause and the population silently grows from 4 to 8 or 21 — the
 * three readings the plan's own prose implied, every one of which <b>excludes all four rules it
 * claimed to return</b>. Those readings are kept live in
 * {@link #theRefutedNaiveReadingsStillMissAllFourIntentRules()}: they are not the predicate, they
 * are the proof that resolving a {@code $}-id's operation {@code domain:} is load-bearing rather
 * than decorative.</li>
 * <li><b>Blindness.</b> A broken path or a deserialisation regression returns "no matches" exactly
 * as happily as a clean corpus. The clause-by-clause synthetics can only pass if the predicate is
 * actually discriminating, and the corpus controls assert a floor on files read and rules whose
 * {@code Check} raised to a structural {@code all}.</li>
 * </ul>
 *
 * <h2>&#9888; What this step does NOT do</h2>
 *
 * <p>
 * Step 1 delivers the opt-out surface only. It does not author the presence rules (step 2) and it
 * does not implement the SKIP semantics (step 3). Nothing in the engine consumes
 * {@link IntentAbsenceOptOut} yet — this test is the whole of its blast radius today.
 * </p>
 */
class IntentAbsenceOptOutLintTest
{

    /** The <b>authored</b> view. &#9888; Never the shipped {@code rules/} view — see the helper. */
    private static final Path CHECKS_DIR = Path.of("rules-src", "checks");

    private static final Path REPORT = Path.of("documentation", "derivation",
            "intent-absence-opt-out.tsv");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * The population, re-derived 2026-08-10. Both {@code -1} variants additionally name {@code TX},
     * which is why a naive <em>"any foreign dataset absent &rArr; skip"</em> rule is wrong twice
     * over: it would silence them on a missing {@code TX}, and TX-absent-together-with-TS-absent is
     * precisely the case they exist to catch.
     */
    private static final Map<String, List<String>> EXPECTED = Map.of("CDISC-SEND-0105",
            List.of("TS"), "CDISC-SEND-0105.1", List.of("TS", "TX"), "CDISC-SEND-0106",
            List.of("TS"), "CDISC-SEND-0106.1", List.of("TS", "TX"));

    // ------------------------------------------------------------------
    // Corpus plumbing
    // ------------------------------------------------------------------

    private static List<Path> ruleFiles() throws IOException
    {
        try (Stream<Path> walk = Files.walk(CHECKS_DIR))
        {
            return walk.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
    }


    private static Rule read(Path file)
    {
        try
        {
            Rule rule = YAML.readValue(file.toFile(), Rule.class);
            // Form-B operations (PLAN-retire-corpus-transforms phase 8) carry no operator or
            // domain until normalized — the same pass the loader runs.
            RulePackageLoader.normalizeOperations(rule);
            return rule;
        }
        catch (IOException _)
        {
            throw new UncheckedIOException(new IOException("cannot read " + file));
        }
    }


    private static Rule parse(String yaml)
    {
        try
        {
            Rule rule = YAML.readValue(yaml, Rule.class);
            RulePackageLoader.normalizeOperations(rule);
            return rule;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------
    // The ratchet
    // ------------------------------------------------------------------


    /**
     * The deliverable. The opt-out's population is derived, not hand-maintained, and it is pinned
     * so that widening the predicate — or authoring a fifth disjunctive-availability rule — cannot
     * happen silently.
     */
    @Test
    void theIntentOptOutPopulationIsExactlyTheFourDisjunctiveAvailabilityRules() throws IOException
    {
        List<Path> files = ruleFiles();
        assertTrue(files.size() >= 3700, "the authored corpus must actually have been read; found "
                + files.size() + " YAML files under " + CHECKS_DIR.toAbsolutePath());

        Map<String, List<String>> actual = new LinkedHashMap<>();
        List<String> rows = new ArrayList<>();
        int structuralAll = 0;
        for (Path file : files)
        {
            Rule rule = read(file);
            // Any structural root counts since the flip: the writer collapsed singleton `all:`
            // wrappers, so a one-conjunct rule deserialises to a bare leaf/not root.
            if (rule.getCheck() != null && !(rule.getCheck() instanceof CheckConditionExpression))
            {
                structuralAll++;
            }
            IntentAbsenceOptOut.Match match = IntentAbsenceOptOut.classify(rule);
            if (match != null)
            {
                actual.put(match.ruleId(), match.foreignDatasets());
                rows.add(match.ruleId() + '\t' + file.getParent().getFileName() + '\t'
                        + String.join(",", match.foreignDatasets()));
            }
        }

        // A Check that does not deserialise to a structural `all` can never match, so a model or
        // Jackson regression would turn this ratchet into a vacuous zero. Assert the floor.
        assertTrue(structuralAll >= 3000,
                "only " + structuralAll + " of " + files.size() + " rules deserialised to a"
                        + " structural Check; the sweep is not seeing the corpus");

        Files.createDirectories(REPORT.getParent());
        List<String> report = new ArrayList<>();
        report.add("# derived by IntentAbsenceOptOutLintTest (Fix #207) — do not hand-edit");
        report.add("# rule_id\torg\tforeign_datasets");
        report.addAll(rows.stream().sorted().toList());
        Files.write(REPORT, report, StandardCharsets.UTF_8);

        assertEquals(new TreeSet<>(EXPECTED.keySet()), new TreeSet<>(actual.keySet()),
                "the intent opt-out population moved. It is the set of rules SKIP must never be"
                        + " applied to (PLAN-absent-required-dataset-skip.md K5c). A rule joining"
                        + " it escapes the SKIP semantics; a rule leaving it becomes silenceable."
                        + " Re-adjudicate before re-pinning — see " + REPORT);
        EXPECTED.forEach((id, datasets) -> assertEquals(datasets, actual.get(id),
                id + " names different foreign datasets than recorded"));
    }


    /**
     * <b>K5c's second half.</b> A manual {@code Absence_Semantics: assert} override is permitted as
     * the exception, but must be <b>counted and reported</b>, never silently honoured. There are
     * none today.
     *
     * <p>
     * &#9888; This is deliberately a <b>raw-text</b> scan. {@code Absence_Semantics} is not a field
     * on {@link Rule}, and the corpus mapper disables {@code FAIL_ON_UNKNOWN_PROPERTIES} — so a
     * model-based scan for it would return zero <em>vacuously</em>, whether or not a rule declared
     * one.
     * </p>
     */
    @Test
    void manualAbsenceSemanticsOverridesAreCountedNotSilentlyHonoured() throws IOException
    {
        List<String> declaring = new ArrayList<>();
        for (Path file : ruleFiles())
        {
            if (Files.readString(file, StandardCharsets.UTF_8).contains("Absence_Semantics"))
            {
                declaring.add(file.getFileName().toString());
            }
        }
        assertEquals(List.of(), declaring,
                "rules now declare a manual Absence_Semantics override. K5c requires every such"
                        + " override to be counted and reported rather than silently honoured:"
                        + " list them here and adjudicate each one, do not just widen the"
                        + " assertion.");
    }

    // ------------------------------------------------------------------
    // Corpus positive control — the three refuted readings
    // ------------------------------------------------------------------


    /**
     * The readings {@code PLAN-absent-required-dataset-skip.md} &sect;0.7 implied, re-run here as a
     * live control. Each takes "foreign" to mean <em>a dotted name</em> and "emptiness test" to
     * mean <em>a fixed operator set</em>; each returns a non-empty population; and <b>not one of
     * them contains a single one of the four rules it was said to return</b>.
     *
     * <p>
     * Counts as measured 2026-08-10: {@code empty/non_empty} &rarr; 8, {@code + is_missing} &rarr;
     * 8 (the operator does not occur in this corpus), {@code + exists/not_exists} &rarr; 21. They
     * are asserted, not merely documented, because they are what proves the sweep reads real rules:
     * a broken walker returns 0 here and the control fires before the ratchet can lie.
     * </p>
     */
    @Test
    void theRefutedNaiveReadingsStillMissAllFourIntentRules() throws IOException
    {
        List<Rule> corpus = ruleFiles().stream().map(IntentAbsenceOptOutLintTest::read).toList();

        // Re-derived by run 2026-08-24, PLAN-scope-requirements-split phase 4d′ (owner ruling
        // B-Q13): 8 → 10 in all three readings. The wipe strips the dead `not var_exists(X) or`
        // disjunct, which turns `(guard or empty(X)) and (guard or empty(Y))` into a FLAT
        // top-level `all` of `empty` leaves — precisely the shape this naive reading keys on. The
        // two rules that joined are FDA-SD1018 and PMDA-SD1018 (measured by run; the other eight
        // are unchanged: CDISC-CG0226, CDISC-CG0227, CORE-000177, CORE-000262, FDA-SD1030,
        // FDA-SD1031, PMDA-SD1030, PMDA-SD1031). ⭐ The invariant this control actually guards is
        // untouched — none of the four intent rules is returned by any reading, which the
        // per-reading assertion below still checks rule by rule.
        Map<String, Integer> expectedCounts = new LinkedHashMap<>();
        expectedCounts.put("empty/non_empty", 10);
        expectedCounts.put("+is_missing", 10);
        // 21 → 8 at the flip (re-measured): the writer spells absence as `not var_exists(...)`,
        // which the model keeps as a structural `not`, so the all-flat-leaves shape this naive
        // reading keys on no longer matches the former exists-family carriers. The reading is a
        // refuted control; what it must keep proving is only that it misses the four intent rules
        // while still reading real ones.
        expectedCounts.put("+var_exists/var_not_exists", 10);

        Map<Set<String>, String> readings = new LinkedHashMap<>();
        readings.put(Set.of("empty", "non_empty"), "empty/non_empty");
        readings.put(Set.of("empty", "non_empty", "is_missing"), "+is_missing");
        // The concrete var_* spellings joined at the flip (the writer disambiguates every
        // generic exists/not_exists; the dual-form lowering spells them concretely).
        readings.put(Set.of("empty", "non_empty", "is_missing", "var_exists", "var_not_exists"),
                "+var_exists/var_not_exists");

        Map<String, Integer> actualCounts = new LinkedHashMap<>();
        readings.forEach((operators, label) ->
        {
            List<String> hits = naiveDottedReading(corpus, operators);
            actualCounts.put(label, hits.size());
            EXPECTED.keySet().forEach(id -> assertFalse(hits.contains(id),
                    "the naive '" + label + "' reading now returns " + id + ". It never did, and"
                            + " the whole point of IntentAbsenceOptOut is that only resolving a"
                            + " $-id's operation domain: finds these four."));
        });

        assertEquals(expectedCounts, actualCounts,
                "the refuted naive readings moved. They are a control, not a target — if the"
                        + " corpus legitimately changed, re-measure and re-pin; if they went to"
                        + " zero, the sweep has stopped reading rules and the ratchet above is"
                        + " no longer trustworthy.");
    }


    /**
     * The predicate as the plan's prose described it: a flat top-level {@code all} whose every leaf
     * operator is in {@code operators}, with at least one dotted-foreign and one non-dotted leaf.
     * No {@code Operations} resolution — that omission is the defect being controlled for.
     */
    private static List<String> naiveDottedReading(List<Rule> corpus, Set<String> operators)
    {
        List<String> hits = new ArrayList<>();
        for (Rule rule : corpus)
        {
            List<CheckConditionLeaf> leaves = flatLeaves(rule);
            if (leaves.size() < 2
                    || !leaves.stream().allMatch(l -> operators.contains(l.getOperator())))
            {
                continue;
            }
            Set<String> own = rule.getScope() != null && rule.getScope().getDomains() != null
                    && rule.getScope().getDomains().getInclude() != null
                            ? Set.copyOf(rule.getScope().getDomains().getInclude())
                            : Set.of();
            boolean local = false;
            boolean foreign = false;
            for (CheckConditionLeaf leaf : leaves)
            {
                String name = leaf.getName();
                int dot = name == null ? -1 : name.indexOf('.');
                if (dot > 0 && !own.contains(name.substring(0, dot)))
                {
                    foreign = true;
                }
                else
                {
                    local = true;
                }
            }
            if (local && foreign && rule.getCore() != null && rule.getCore().getId() != null)
            {
                hits.add(rule.getCore().getId());
            }
        }
        return hits;
    }


    /** The flat leaves of a top-level {@code all}, or the empty list for any other shape. */
    private static List<CheckConditionLeaf> flatLeaves(Rule rule)
    {
        if (!(rule.getCheck() instanceof CheckConditionAll all))
        {
            return List.of();
        }
        List<CheckConditionLeaf> leaves = new ArrayList<>();
        for (CheckCondition condition : all.getConditions())
        {
            if (!(condition instanceof CheckConditionLeaf leaf))
            {
                return List.of();
            }
            leaves.add(leaf);
        }
        return leaves;
    }

    // ------------------------------------------------------------------
    // Synthetic positive controls — one per clause of the predicate
    // ------------------------------------------------------------------


    /**
     * The canonical shape, in both spellings the corpus uses: {@code -0105}'s derived nullity flag
     * and {@code -0105-1}'s plain local {@code empty}. If either stops matching, the predicate has
     * been narrowed and the ratchet's "4" is no longer the set it names.
     */
    @Test
    void theCanonicalDisjunctiveAvailabilityShapesMatch()
    {
        IntentAbsenceOptOut.Match derivedFlag = IntentAbsenceOptOut.classify(parse(rule("""
                Operations:
                - id: "$species_null"
                  operator: "variable_is_null"
                  name: "SPECIES"
                - id: "$ts_species"
                  operator: "ts_parameter_value"
                  domain: "TS"
                  name: "TSVAL"
                Check:
                  all:
                  - name: "$species_null"
                    operator: "equal_to"
                    value: true
                    value_is_literal: true
                  - name: "$ts_species"
                    operator: "empty"
                """)));
        assertNotNull(derivedFlag, "clause 3b (derived nullity flag) no longer matches");
        assertEquals(List.of("TS"), derivedFlag.foreignDatasets());

        IntentAbsenceOptOut.Match plainLocal = IntentAbsenceOptOut.classify(parse(rule("""
                Operations:
                - id: "$ts_species"
                  operator: "ts_parameter_value"
                  domain: "TS"
                  name: "TSVAL"
                - id: "$tx_species"
                  operator: "ts_parameter_value"
                  domain: "TX"
                  name: "TXVAL"
                Check:
                  all:
                  - name: "SPECIES"
                    operator: "empty"
                  - name: "$ts_species"
                    operator: "empty"
                  - name: "$tx_species"
                    operator: "empty"
                """)));
        assertNotNull(plainLocal, "clause 3a (direct empty) no longer matches");
        assertEquals(List.of("TS", "TX"), plainLocal.foreignDatasets());
    }


    /**
     * <b>{@code M3-D6} / {@code Fix #289}</b> — the absent-column-tolerant emptiness arm. The two
     * {@code .1} rules were re-authored as
     * {@code (not var_exists(SPECIES) or empty(SPECIES)) and empty($ts_…) and empty($tx_…)} so that
     * a DM carrying no {@code SPECIES} column at all is reported rather than skipped. That makes
     * the first conjunct a two-member {@code any} instead of a bare leaf, and without the dedicated
     * arm both rules fall out of the opt-out on a shape technicality — handing the SKIP semantics
     * exactly the finding they exist to carry. Pinned in both directions: the tolerant shape
     * matches, and the same {@code any} on two DIFFERENT names does not.
     */
    @Test
    void theAbsentTolerantEmptinessConjunctMatches()
    {
        IntentAbsenceOptOut.Match tolerant = IntentAbsenceOptOut.classify(parse(rule("""
                Operations:
                - id: "$ts_species"
                  operator: "ts_parameter_value"
                  domain: "TS"
                  name: "TSVAL"
                - id: "$tx_species"
                  operator: "ts_parameter_value"
                  domain: "TX"
                  name: "TXVAL"
                Check:
                  all:
                  - any:
                    - name: "SPECIES"
                      operator: "var_not_exists"
                    - name: "SPECIES"
                      operator: "empty"
                  - name: "$ts_species"
                    operator: "empty"
                  - name: "$tx_species"
                    operator: "empty"
                """)));
        assertNotNull(tolerant,
                "the absent-tolerant emptiness conjunct (flat negative leaf) no longer matches");
        assertEquals(List.of("TS", "TX"), tolerant.foreignDatasets());

        // The spelling the STRICT load-path lowering actually produces for the two shipped
        // rules: `not var_exists(X)` stays a structural `not` around the POSITIVE leaf. Only the
        // conversion-mode lowering folds it to `var_not_exists`, so both must be accepted or the
        // corpus arm is dead while the synthetic above passes.
        IntentAbsenceOptOut.Match structuralNot = IntentAbsenceOptOut.classify(parse(rule("""
                Operations:
                - id: "$ts_species"
                  operator: "ts_parameter_value"
                  domain: "TS"
                  name: "TSVAL"
                Check:
                  all:
                  - any:
                    - not:
                        name: "SPECIES"
                        operator: "var_exists"
                    - name: "SPECIES"
                      operator: "empty"
                  - name: "$ts_species"
                    operator: "empty"
                """)));
        assertNotNull(structuralNot,
                "the absent-tolerant emptiness conjunct (structural not) no longer matches");
        assertEquals(List.of("TS"), structuralNot.foreignDatasets());

        assertNull(IntentAbsenceOptOut.classify(parse(rule("""
                Operations:
                - id: "$ts_species"
                  operator: "ts_parameter_value"
                  domain: "TS"
                  name: "TSVAL"
                Check:
                  all:
                  - any:
                    - name: "SPECIES"
                      operator: "var_not_exists"
                    - name: "STRAIN"
                      operator: "empty"
                  - name: "$ts_species"
                    operator: "empty"
                """))), "the arm must require the SAME name on both members of the any");
    }


    /**
     * The <b>dotted</b> foreign arm. &#9888; No shipped rule exercises it — variant C of the
     * 2026-08-10 sweep (dotted-only foreignness) returns <b>0</b> matches — so without this
     * synthetic the arm would be dead code that could rot unnoticed and then silently widen the
     * opt-out the day a rule is authored that way.
     */
    @Test
    void theDottedForeignArmMatchesEvenThoughNoShippedRuleUsesIt()
    {
        assertNotNull(IntentAbsenceOptOut.classify(parse(rule("""
                Check:
                  all:
                  - name: "SPECIES"
                    operator: "empty"
                  - name: "TS.TSVAL"
                    operator: "empty"
                """))), "the dotted foreign arm no longer matches");
    }

    // ------------------------------------------------------------------
    // Expression-form Checks — Fix #256 (pre-flip re-point, PLAN-rules-src-expression-flip §7b)
    // ------------------------------------------------------------------


    /**
     * <b>{@code Fix #256}</b> — the tolerant/explicit expression arm. The canonical derived-flag
     * shape, arriving as the {@code CheckConditionExpression} the deserializer keeps when the
     * strict lowering declines an expression (modelled by direct construction: today's corpus
     * authors zero expression Checks, and the four EXPECTED rules strict-lower to a structural
     * {@code all} on load), must classify exactly as its legacy form — not fall silently out of the
     * opt-out through the {@code instanceof CheckConditionAll} shape test, which is how the flip
     * would hand these four rules to the SKIP semantics unadjudicated.
     */
    @Test
    void anExpressionFormCheckClassifiesIdenticallyToItsLegacyForm()
    {
        Rule rule = parse(rule("""
                Operations:
                - id: "$species_null"
                  operator: "variable_is_null"
                  name: "SPECIES"
                - id: "$ts_species"
                  operator: "ts_parameter_value"
                  domain: "TS"
                  name: "TSVAL"
                Check:
                  all:
                  - name: "$species_null"
                    operator: "equal_to"
                    value: true
                    value_is_literal: true
                  - name: "$ts_species"
                    operator: "empty"
                """));
        IntentAbsenceOptOut.Match legacy = IntentAbsenceOptOut.classify(rule);
        assertNotNull(legacy, "baseline: the legacy form of the canonical shape must match");

        String source = "$species_null == true and empty($ts_species)";
        rule.setCheck(new CheckConditionExpression(CheckExpressionParser.parse(source), source));
        IntentAbsenceOptOut.Match viaExpression = IntentAbsenceOptOut.classify(rule);
        assertNotNull(viaExpression,
                "the expression form of the same Check must not fall out of the opt-out — a null"
                        + " here is the silent over-application of SKIP that Fix #256 closes");
        assertEquals(legacy.ruleId(), viaExpression.ruleId());
        assertEquals(legacy.foreignDatasets(), viaExpression.foreignDatasets());
    }


    /**
     * The fail-loud half of the same arm: a native-only expression — non-canonical
     * {@code between(char(X), char("B"), char("Z"))} bounds have no operator-leaf surface even in
     * conversion mode — must throw rather than classify {@code null}, so the EXPECTED pin reds
     * loudly instead of vacuously if such a rule ever carries the disjunctive-availability intent.
     */
    @Test
    void aNativeOnlyExpressionCheckFailsLoudRatherThanClassifyingNull()
    {
        Rule rule = parse(rule("""
                Check:
                  all:
                  - name: "SPECIES"
                    operator: "empty"
                  - name: "TS.TSVAL"
                    operator: "empty"
                """));
        // Carries a presence call AND a native-only construct: the skip that would silence a
        // finding must stay loud.
        String source = "var_exists(\"TS.TSVAL\") and between(char(SPECIES), char(\"B\"),"
                + " char(\"Z\"))";
        rule.setCheck(new CheckConditionExpression(CheckExpressionParser.parse(source), source));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> IntentAbsenceOptOut.classify(rule));
        assertTrue(ex.getMessage().contains("PLAN-rules-src-expression-flip"),
                "the failure must name the flip plan: " + ex.getMessage());

        // Without a presence call the rule provably cannot be an intent-absence carrier, so a
        // native-only expression classifies as not-matching instead of failing the sweep (the
        // conversion-unlowerable corpus rules — plan §2 — hit exactly this path).
        String inert = "between(char(SPECIES), char(\"B\"), char(\"Z\"))";
        rule.setCheck(new CheckConditionExpression(CheckExpressionParser.parse(inert), inert));
        assertNull(IntentAbsenceOptOut.classify(rule),
                "a native-only Check with no presence call must classify as not-matching");
    }


    /**
     * One mutation per clause, each of which must be <b>rejected</b>. Neuter any clause of
     * {@link IntentAbsenceOptOut} and at least one of these flips to a match — verified by
     * neuter-and-watch, not by assertion (see {@code ## Results — Step 1} in the plan).
     */
    @Test
    void everySingleClauseMutationOfTheShapeIsRejected()
    {
        // Clause 1 — top-level `any`, not `all`: "empty here OR empty there" is not a conjunction
        // of availability probes.
        assertNull(IntentAbsenceOptOut.classify(parse(rule("""
                Operations:
                - id: "$ts_species"
                  operator: "ts_parameter_value"
                  domain: "TS"
                  name: "TSVAL"
                Check:
                  any:
                  - name: "SPECIES"
                    operator: "empty"
                  - name: "$ts_species"
                    operator: "empty"
                """))), "clause 1: a top-level `any` must not match");

        // Clause 1 — a single conjunct cannot be a disjunctive-availability requirement.
        assertNull(IntentAbsenceOptOut.classify(parse(rule("""
                Operations:
                - id: "$ts_species"
                  operator: "ts_parameter_value"
                  domain: "TS"
                  name: "TSVAL"
                Check:
                  all:
                  - name: "$ts_species"
                    operator: "empty"
                """))), "clause 1: a one-conjunct `all` must not match");

        // Clause 2 — a nested branch. The local half sits under an `any`, so the rule is no longer
        // asserting that every listed home is empty.
        assertNull(IntentAbsenceOptOut.classify(parse(rule("""
                Operations:
                - id: "$ts_species"
                  operator: "ts_parameter_value"
                  domain: "TS"
                  name: "TSVAL"
                Check:
                  all:
                  - any:
                    - name: "SPECIES"
                      operator: "empty"
                    - name: "STRAIN"
                      operator: "empty"
                  - name: "$ts_species"
                    operator: "empty"
                """))), "clause 2: a nested any/all branch must not match");

        // Clause 3 — a `non_empty` conjunct. This is the shape of CDISC-CG0226 / FDA-SD1030 et al.
        // ("X is populated but the foreign datum is empty"), which is a DEFECT rule, not intent:
        // 8 rules corpus-wide, and admitting them doubles the opt-out.
        assertNull(IntentAbsenceOptOut.classify(parse(rule("""
                Operations:
                - id: "$ts_species"
                  operator: "ts_parameter_value"
                  domain: "TS"
                  name: "TSVAL"
                Check:
                  all:
                  - name: "SPECIES"
                    operator: "non_empty"
                  - name: "$ts_species"
                    operator: "empty"
                """))), "clause 3: a non_empty conjunct must not match");

        // Clause 3b — `equal_to true` on an operation that is NOT a nullity flag is an ordinary
        // value comparison, not an emptiness assertion.
        assertNull(IntentAbsenceOptOut.classify(parse(rule("""
                Operations:
                - id: "$species_seen"
                  operator: "distinct"
                  name: "SPECIES"
                - id: "$ts_species"
                  operator: "ts_parameter_value"
                  domain: "TS"
                  name: "TSVAL"
                Check:
                  all:
                  - name: "$species_seen"
                    operator: "equal_to"
                    value: true
                    value_is_literal: true
                  - name: "$ts_species"
                    operator: "empty"
                """))), "clause 3b: equal_to true on a non-nullity operation must not match");

        // Clause 3b — `equal_to false` inverts the polarity: it asserts the flag is NOT set, i.e.
        // that the variable is populated.
        assertNull(IntentAbsenceOptOut.classify(parse(rule("""
                Operations:
                - id: "$species_null"
                  operator: "variable_is_null"
                  name: "SPECIES"
                - id: "$ts_species"
                  operator: "ts_parameter_value"
                  domain: "TS"
                  name: "TSVAL"
                Check:
                  all:
                  - name: "$species_null"
                    operator: "equal_to"
                    value: false
                    value_is_literal: true
                  - name: "$ts_species"
                    operator: "empty"
                """))), "clause 3b: equal_to false must not match");

        // Clause 4 — no foreign conjunct. The operation carries no domain:, so both halves are
        // local and nothing about the rule concerns another dataset. THIS is the clause the whole
        // correction turns on: drop the Operations lookup and `$ts_species` reads as local here
        // and as local in CDISC-SEND-0105 too, which is why the plan's own sweep found none of
        // the four.
        assertNull(IntentAbsenceOptOut.classify(parse(rule("""
                Operations:
                - id: "$ts_species"
                  operator: "ts_parameter_value"
                  name: "TSVAL"
                Check:
                  all:
                  - name: "SPECIES"
                    operator: "empty"
                  - name: "$ts_species"
                    operator: "empty"
                """))), "clause 4: a rule with no foreign conjunct must not match");

        // Clause 4 — the operation's domain: IS the rule's own scope domain, so it is not foreign.
        assertNull(IntentAbsenceOptOut.classify(parse(rule("""
                Operations:
                - id: "$dm_species"
                  operator: "ts_parameter_value"
                  domain: "DM"
                  name: "SPECIES"
                Check:
                  all:
                  - name: "SPECIES"
                    operator: "empty"
                  - name: "$dm_species"
                    operator: "empty"
                """))), "clause 4: an operation scoped to the rule's own domain is not foreign");

        // Clause 4 — no local conjunct. ⚠ This clause is INERT against today's corpus (the
        // 2026-08-10 sweep finds 0 rules that are all-emptiness with a foreign conjunct and no
        // local one), so this synthetic is the only thing keeping it honest.
        assertNull(IntentAbsenceOptOut.classify(parse(rule("""
                Operations:
                - id: "$ts_species"
                  operator: "ts_parameter_value"
                  domain: "TS"
                  name: "TSVAL"
                - id: "$tx_species"
                  operator: "ts_parameter_value"
                  domain: "TX"
                  name: "TXVAL"
                Check:
                  all:
                  - name: "$ts_species"
                    operator: "empty"
                  - name: "$tx_species"
                    operator: "empty"
                """))), "clause 4: a rule with no local conjunct must not match");
    }


    /** Wraps a {@code Check}/{@code Operations} fragment in a minimal DM-scoped rule. */
    private static String rule(String body)
    {
        return """
                Core:
                  Id: "SYNTHETIC-0001"
                Scope:
                  Domains:
                    Include:
                    - "DM"
                """ + body;
    }
}
