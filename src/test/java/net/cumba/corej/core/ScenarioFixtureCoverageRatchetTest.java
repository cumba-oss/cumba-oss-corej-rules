package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * §8 of {@code plans/done/PLAN-rules-corpus-build-integration.md} — the ratchet on the safety net's
 * known hole.
 *
 * <h2>Why a ratchet and not a target</h2>
 *
 * <p>
 * Moving corpus generation into the build makes the {@code .cdt} scenario suites the thing that
 * catches a packaging change: a rule whose released form drifts fails its scenario. That net
 * covered <b>3,020 of 3,808 rules — 79.3%</b> when this test was written. ⚠ <b>Re-measured
 * 2026-09-19: 2,867 of 3,411 — 84.1%</b>, leaving <b>544</b> for which a packaging change ships
 * with nothing firing at all. The prose is dated on purpose; {@link #MAX_UNCOVERED} is the pin, and
 * it is the one to read.
 * </p>
 *
 * <p>
 * That hole is accepted, but it must not widen quietly. This test makes the number visible and
 * one-directional: new rules cannot be added without fixtures faster than old ones acquire them. It
 * is not a coverage goal, and {@link #MAX_UNCOVERED} is not a budget to spend.
 * </p>
 *
 * <h2>⚠⚠ How the covered set is counted, and how it is NOT</h2>
 *
 * <p>
 * By the {@code #test} directive inside each {@code .cdt}. <b>Never by parsing the file name.</b> A
 * filename regex truncates three-segment ids ({@code CDISC-SEND-0056} &rarr; {@code CDISC-SEND}),
 * collapsing every SEND rule into one and under-counting coverage by 133 — which, seeded into a
 * ratchet, silently licenses 133 new uncovered rules.
 * </p>
 *
 * <h2>⚠ What this does NOT measure</h2>
 *
 * <p>
 * Scenario coverage is <em>behavioural</em>. A rule with a fixture is protected against a change of
 * verdict, not against a change of message text, description, or any metadata no fixture asserts on
 * — users see those. {@code ReleasedRuleFieldFidelityTest} covers that separate gap.
 * </p>
 */
class ScenarioFixtureCoverageRatchetTest
{

    /**
     * The 788 measured on 2026-09-02 against 3,808 shipped ids; 787 since 2026-09-08, when
     * CDISC-CG0155 gained its four scenarios (PLAN-codelist-terms-returntype.md P1, folded into
     * P4); 764 since 2026-09-14, re-derived against 3,930 shipped and 3,166 covered; 761 since
     * later that day, when CDISC-SEND-0221, -0318 and -0388 gained their first scenarios
     * (PLAN-column-type-conformance carried-forward) — 3,930 shipped, 3,169 covered; <b>758 since
     * 2026-09-16</b>, when CDISC-CG0068, CDISC-CG0148 and CDISC-SEND-0305 gained their first
     * scenarios (the typed-equality probes of RUNBOOK-phase3c-corpus-rewrite.md §6.2). <b>Shrink
     * only.</b> If a change genuinely retires rules, lower this; never raise it.
     *
     * <p>
     * &#9888; <b>It had eroded by 23 and nothing said so.</b> This is a {@code <=} ceiling, so it
     * reds in the dangerous direction and drifts silently in the safe one: every suite added
     * between 2026-09-08 and 2026-09-14 widened the gap between the constant and the truth, and the
     * class javadoc's "not a budget to spend" became exactly that &mdash; 23 rules of budget nobody
     * granted. <b>Re-derive it after any wave that adds scenarios</b>, the way the other ratchets
     * are re-derived; it does not maintain itself.
     * </p>
     */
    // 758 -> 749 on 2026-09-18 (PLAN-unplanned-visit-cascade phase 4): nine edited rules gained
    // their first scenarios (CDISC-CG0214, CG0215, CG0655, CG0656, CG0657, CG0658, CORE-000333,
    // CORE-000501, CORE-000843). The eight retired -A siblings left shipped and covered together,
    // so they move nothing here. Re-derived, not adjusted by arithmetic.
    //
    // ⭐ 749 -> 544 on 2026-09-19 (PLAN-retire-core-family §13, phase 7 — the plan's LAST phase,
    // deliberately: re-deriving a shrink-only ceiling against a partly-migrated tree locks the loss
    // in permanently). Measured by replicating this test's own two predicates over target/rules:
    // 3 411 shipped · 2 867 covered · 544 uncovered · 0 fixture-only. The 544 are CDISC 294 +
    // CDISC-SEND 227 + DRAFT 23 — no FDA and no PMDA rule is uncovered. Set ON the achieved value,
    // with no headroom, because that is what this constant is for.
    //
    // ⚠⚠ THE RETIREMENT MADE THIS CEILING PASS MORE EASILY, WHICH IS WHY NOTHING WOULD HAVE ASKED.
    // The CORE family was BETTER covered than the corpus it left, so deleting it took more covered
    // rules out than uncovered ones and the count fell 718 -> 544 with not one fixture added. The
    // decomposition, measured rather than asserted (the 516 shipped CORE ids had 455 scenario-
    // covered between them — 88.2%, against the corpus's own 79-84%):
    // 718 - 61 (uncovered CORE ids leaving: 516 shipped - 455 covered)
    // - 113 (surviving rules that gained their FIRST fixture from phase 5's 399 migrations)
    // + 0 (the 21 phase-4 mints, every one of which landed with a fixture) = 544.
    // ⚑ It closes on the other axis too: shipped 3 906 - 516 + 21 = 3 411, covered 3 188 - 455 + 21
    // + 113 = 2 867. The 455 was counted from cd1f983b^ by the same #test-directive rule this class
    // uses — never from file names, see the class javadoc; independently, cd1f983b^ holds exactly
    // 455 core/<id>/ scenario directories. ⚠ The 3 906 / 718 baseline is PRE-phase-4, which is what
    // makes the mint term +0 rather than -21: at cd1f983b^ itself the 21 were already authored and
    // shipped but still fixture-less, so that baseline reads 3 927 / 739 and the same move
    // decomposes 739 - 61 - 113 - 21 = 544. Either baseline closes; do not mix them.
    //
    // ⛔ CORRECTED 2026-09-19 (review round 1). This block previously recorded the decomposition as
    // 718 - 78 - 96 + 0, from "438 CORE ids covered", and closed the sum anyway — the two wrong
    // terms are wrong by +17 and -17 and CANCEL, so nothing here asked. Measured now, three ways
    // over cd1f983b^ (git grep of the #test directive; the core/<id>/ directory count; and this
    // class's own two predicates replayed over target/rules): 455, not 438. The emphatic sentence
    // this replaces — "the plan's own 87% and a brief's ~88% do not reproduce; 438/516 is 84.9%" —
    // was itself THE error: 455/516 is 88.2%, so the plan and the brief were right and the
    // correction was not. ⚠ What 438 counted could not be identified from anything in the tree;
    // treat it as unsourced. The pin 544 was never affected and re-derives exact: shipped 3 411,
    // covered 2 867, uncovered 544, fixture-only 0, families CDISC 294 + CDISC-SEND 227 + DRAFT 23.
    // ⇒ Carried across at 749 the constant would have stayed green holding 205 rules of slack
    // nobody granted — an order of magnitude more than the 23 this javadoc records as the erosion
    // that already happened once.
    //
    // ⛔⛔ AND ON THE ACHIEVED VALUE NO INSTRUMENT HERE SEPARATES "uncovered" FROM "uncovered
    // because the retirement surrendered its fixture". So the surrender is NAMED here rather than
    // absorbed into a number. The register is the meta repo's
    // plans/findings/CENSUS-phase5-fixture-disposition.tsv — 546 rows, one per CORE scenario:
    // 399 MIGRATE, 3 HELD, 143 DROP (137 failed the owner's not-CLEAR test, 6 not-EASY). Of the
    // dropped scenarios:
    // · 28 CORE ids had EVERY scenario dropped for the reason "no replacement rule exists".
    // ⚠⚠ That reason is about the SCENARIO, not the claim, and reading it as "the claim
    // ceased" overstates the loss by 2.5x. Measured: 17 of the 28 are recorded COVERED with a NAMED
    // surviving rule in plans/findings/CENSUS-core-read-verdicts.tsv (CORE-000310 ->
    // FDA/PMDA-SD0084, CORE-000699 -> FDA-SD0007, CORE-001043 -> FDA-SD1121, and 14 more). The two
    // censuses answer different questions — "is the claim carried?" vs "can this .cdt be
    // migrated?" — and a twin can carry the claim while its scope leaves it unable to run the
    // fixture (worked case: CORE-000158 -> FDA-SD1072, expression-identical but domain-disjoint, 5
    // of its 6 fixtures failing "domain CO not in Scope.Domains.Include"). ⇒ ELEVEN ids have no
    // surviving rule named by ANY census, and those are the claims that actually ceased:
    // CORE-000302, -000303, -000318, -000726, -000728, -000743, -000746, -000747, -000791,
    // -000867, -000890;
    // · 20 surviving rules were named as a migration target and received NOTHING, every
    // candidate scenario for them having been dropped. Four of the 20 ship in no package
    // (CDISC-CG0091, -CG0622, -CG0635, -CG0636) and nine already had fixtures of their own,
    // which leaves SEVEN shipped rules sitting in the 544 above that a migratable scenario
    // would have covered: CDISC-CG0037, -CG0143, -CG0262, -CG0374, -CG0601, -CG0602, -CG0603.
    //
    // ⛔⛔ THE REGISTER IS NOT A PER-FILE DISPOSITION, AND ITS OWN SHAPE IS WHY. Recorded
    // 2026-09-19 (review round 1). Measured over cd1f983b^..HEAD: 862 .cdt files were DELETED
    // (395 more were MIGRATED, i.e. renamed out of core/ into cdisc/ and fda/, and 9 new ones were
    // authored, so the tree moved 9 513 -> 8 660 for a NET 853). The census above carries 545 data
    // rows, and of the 862 deletions only 153 have a row (143 DROP + 10 MIGRATE rows whose file
    // was deleted rather than renamed). ⇒ 709 deleted scenarios have NO per-file disposition at
    // all. That is not an omission but the census's criterion: it de-duplicated by
    // (verdict, dataset) SHAPE, not by claim identity, so a family of scenarios sharing a shape
    // contributed one row and the rest were covered by inference.
    // ⚠ What that does and does not license. The two largest guard-noted sub-families were checked
    // and are genuinely duplicated on surviving twins, so the inference held there. About sixty of
    // the 709 remain UNTRACED — neither confirmed duplicated nor confirmed lost — and no instrument
    // in this repo can distinguish them, because a scenario that was never migrated leaves no
    // artefact behind to interrogate. ⇒ "one verified casualty" is a floor on the loss, not a
    // measurement of it. The one verified casualty is CDISC-CG0554-D's discriminator (CORE-000107's
    // third fixture, on AE — the only production-reachable scenario on that rule, dropped with no
    // row in either census); review round 1 recovered it from cd1f983b^ and it is back in the tree.
    // ⛔ Do not re-file the 709 as "accounted for" on the strength of the two families that were.
    // Phase 4 surrendered nothing: 21 mints declared, 21 authored, and all 21 landed WITH fixtures
    // — none of them is in the 544. Two further losses are out of this ratchet's reach and live
    // only in their own instruments: ShippedExactNameRequirementTest was RETIRED, CORE-000208
    // having been the corpus's only rule reaching its Requirements.Datasets through an operation
    // domain alone (AbsentDatasetSkip.resolvesExactOnly now has no corpus carrier); and
    // OrderFlipGuardCoverageRatchetTest lost 5 guard sites across 4 rules (CORE-000097 ×2,
    // -000250, -000310, -000717) — 26 entries / 28 sites -> 22 / 23.
    // 544 -> 542 on 2026-09-22 (PLAN-join-shadowed-comparison-rules phase 6), and the figure is
    // MEASURED on THIS repo rather than copied from the internal twin. This phase newly covers
    // exactly ONE previously-uncovered rule -- CDISC-CG0032, which shipped with zero .cdt of any
    // kind -- so the obvious arithmetic is 544 - 1 = 543. It is wrong: measured here, uncovered was
    // 543 BEFORE the phase and 542 after, so 544 was already carrying one row of slack. Landing 543
    // would have banked that slack silently, which is what a `<=` ratchet does when nobody
    // re-measures; the other 18 scenarios attach to rules that already had fixtures.
    // ⚑ The internal twin measured the same 543 -> 542, but that is a COINCIDENCE of the two
    // corpora currently shipping the same 3 415 ids, not a reason to copy the number. Re-measure.
    private static final int MAX_UNCOVERED = 542;

    /**
     * Floor on the corpus, so a run that loaded nothing cannot report perfect coverage. The corpus
     * grows; this is not an equality.
     */
    // 3700 -> 3400 on 2026-09-19 (PLAN-retire-core-family §13, licensed row 1): distinct shipped
    // rule ids fell 3 906 -> 3 411 when the CORE family's 11 packages stopped being generated
    // (516 shipped CORE ids out, 21 phase-4 mints in). ⚠ A FLOOR, not a pin: set below the
    // measured 3 411 on purpose, because the corpus grows.
    // ⛔ MAX_UNCOVERED was NOT touched with it and needs nothing: measured 544 against 749.
    private static final int MIN_SHIPPED = 3400;

    private static final Path SCENARIOS = Path
            .of("src/test/resources/net/cumba/corej/core/ruletestsuites");

    private static final String TEST_DIRECTIVE = "#test";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theSetOfShippedRulesWithNoScenarioFixtureOnlyShrinks() throws IOException
    {
        NavigableSet<String> shipped = shippedRuleIds();
        NavigableSet<String> covered = fixtureRuleIds();

        assertTrue(shipped.size() >= MIN_SHIPPED, "only " + shipped.size()
                + " shipped rule ids found (expected at least " + MIN_SHIPPED
                + "). The corpus did not load — a ratchet over an empty set is not a ratchet.");

        // The fixtures are a strict subset today. A fixture naming an id that no package ships is
        // a dead scenario: it runs against nothing and its greenness means nothing.
        NavigableSet<String> fixtureOnly = new TreeSet<>(covered);
        fixtureOnly.removeAll(shipped);
        assertEquals(new TreeSet<String>(), fixtureOnly,
                fixtureOnly.size() + " scenario fixtures name a rule id that no shipped package"
                        + " contains, so they assert nothing");

        NavigableSet<String> uncovered = new TreeSet<>(shipped);
        uncovered.removeAll(covered);

        assertTrue(uncovered.size() <= MAX_UNCOVERED,
                uncovered.size() + " shipped rules have no .cdt scenario fixture; the ratchet"
                        + " allows " + MAX_UNCOVERED + ". A rule with no fixture can have its"
                        + " released form changed by a packaging change with nothing firing."
                        + " Add a scenario — do not raise the ratchet. New offenders would be"
                        + " among: " + uncovered.headSet(nth(uncovered, 20)));
    }


    /** Every {@code Core.Id} in the generated corpus, read through the manifest. */
    private static NavigableSet<String> shippedRuleIds() throws IOException
    {
        TreeSet<String> ids = new TreeSet<>();
        JsonNode manifest = MAPPER
                .readTree(Files.readString(CorpusPaths.rules(RulePackageManifest.FILE_NAME)));
        for (JsonNode entry : manifest.get("packages"))
        {
            JsonNode rules = MAPPER
                    .readTree(Files.readString(CorpusPaths.rules(entry.get("file").asText())))
                    .get("rules");
            rules.fieldNames().forEachRemaining(ids::add);
        }
        return ids;
    }


    /**
     * Every rule id named by a {@code #test} directive. Parsed from the directive, never from the
     * file name — see the class javadoc.
     */
    private static NavigableSet<String> fixtureRuleIds() throws IOException
    {
        TreeSet<String> ids = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SCENARIOS))
        {
            for (Path cdt : files.filter(
                    p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cdt"))
                    .toList())
            {
                for (String line : Files.readAllLines(cdt, StandardCharsets.UTF_8))
                {
                    if (!line.startsWith(TEST_DIRECTIVE) || line.length() <= TEST_DIRECTIVE.length()
                            || !Character.isWhitespace(line.charAt(TEST_DIRECTIVE.length())))
                    {
                        continue;
                    }
                    // The first whitespace-delimited token, taken by hand: the directive is
                    // "#test <id> [modifiers]" and only the id matters here.
                    String rest = line.substring(TEST_DIRECTIVE.length()).stripLeading();
                    int end = 0;
                    while (end < rest.length() && !Character.isWhitespace(rest.charAt(end)))
                    {
                        end++;
                    }
                    if (end > 0)
                    {
                        ids.add(rest.substring(0, end));
                    }
                }
            }
        }
        return ids;
    }


    /** The n-th element, or the last, so the failure message stays readable. */
    private static String nth(NavigableSet<String> set, int n)
    {
        int i = 0;
        String last = set.isEmpty() ? "" : set.last();
        for (String s : set)
        {
            if (i++ == n)
            {
                return s;
            }
        }
        return last;
    }
}
