package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Corpus lint for <b>R-7.9</b> — a {@code left} join followed by a <b>positive</b> test on a joined
 * column silently excludes the unmatched primary row: no finding, no {@code SKIPPED}, nothing in
 * the report to say the row was never checked
 * ({@code <meta>/plans/PLAN-left-join-silent-exclusion.md} §1).
 *
 * <h2>Why a lint and not a fix</h2> The owner ruled 2026-09-10 that the silence is <b>authored</b>,
 * because the unmatched row is already reported — by rules that read the joined dataset through a
 * {@code distinct(…, domain="D")} <em>operation</em> rather than a join, and are therefore immune
 * to the defect ({@code CDISC-CG0034}, {@code FDA}/{@code PMDA-SD0065} — the fourth,
 * {@code CORE-000168}, went with the CORE family's 2026-09 retirement). That ruling rests entirely
 * on those reporters continuing to exist: delete {@code CDISC-CG0034} and the documented division
 * of labour silently becomes real under-reporting, with nothing going red — which is the failure
 * mode this corpus has shipped seven times. So the pairing is pinned here.
 *
 * <h2>⛔ What this lint deliberately does NOT do</h2> It names neither the carriers nor the
 * reporters. Both populations are <b>derived</b>, so a rule that adopts the idiom tomorrow is
 * checked too, and a rule that is re-authored simply leaves. A hand-listed pair would rot on the
 * first rename.
 *
 * <h2>The view</h2> The <b>shipped</b> corpus, after {@code RulePackageLoader} has normalised it:
 * an operation's {@code domain="SV"} is a {@link Operation#getDomain()} field by then (not text in
 * an expression), and the {@code Check} is the parsed expression, read through
 * {@link CheckExprUnits}' v1-vocabulary units (phase 7 retired the operator-leaf view). ⚠ Reading
 * the authored YAML instead would miss nothing today but would test a different artefact from the
 * one a run executes.
 */
class LeftJoinPositiveTestCoverageLintTest
{

    /**
     * See the pinning comment beside the carrier walk (PLAN-unplanned-visit-cascade).
     *
     * <p>
     * ⛔ 4 &rarr; 2 on 2026-09-19 (review round 1): {@code CORE-000269} and {@code CORE-000270} were
     * the CORE twins of the two survivors and the CORE family is retired, so both entries had
     * become dead ids &mdash; matching no rule in any package and therefore pinning nothing. A
     * hand-pinned population is exactly where that rots first, which is why
     * {@link #everyCascadeCarrierStillShips} now asserts it instead of leaving it to the prose
     * below. That assertion is the point: it is what will red when the NEXT carrier is renamed or
     * retired, rather than the pin quietly covering fewer rules than it names.
     * </p>
     */
    private static final Set<String> CASCADE_CARRIERS = Set.of("CDISC-CG0031", "CDISC-CG0033");

    /**
     * Operators a <b>null</b> joined column can SATISFY — so they keep the unmatched row in play
     * and are not the R-7.9 idiom. For the thirteen rules of
     * {@code <meta>/plans/PLAN-left-join-silent-exclusion.md} §3 the unmatched row is the finding,
     * and this is the set that says so.
     *
     * <p>
     * ⭐ <b>The default is INVERTED deliberately</b> (review finding 3, 2026-09-10): every operator
     * not listed here counts as positive. The first draft tested for {@code equal_to} alone, which
     * is how all four known carriers happen to spell it — so it was complete on today's corpus and
     * blind to the next one. {@code not empty(SV.SVPRESP)} (operator {@code non_empty}) drops the
     * unmatched row exactly as {@code == "Y"} does, and would have entered no population at all. ⛔
     * When adding an operator here, the test is *"is this predicate TRUE on a null?"* — not whether
     * its name contains "not".
     * </p>
     */
    private static final Set<String> NULL_SATISFIED = Set.of("empty",
            "empty_within_except_last_row", "var_is_null", "var_not_exists", "ds_not_exists",
            "is_not_contained_by", "is_not_contained_by_case_insensitive", "not_equal_to",
            "not_equal_to_case_insensitive", "not_equal_to_divide", "not_equal_to_pctchg",
            "not_equal_to_subtract", "date_not_equal_to", "date_part_not_equal_to",
            "time_part_not_equal_to", "does_not_contain", "does_not_contain_case_insensitive",
            "does_not_equal_string_part", "not_matches_regex", "not_prefix_matches_regex",
            "not_suffix_matches_regex", "prefix_not_equal_to", "prefix_is_not_contained_by",
            "suffix_is_not_contained_by", "is_not_integer", "is_not_complete_date_part",
            "not_contains_all", "shares_no_elements_with");

    /**
     * Operators that ask about the <b>schema</b>, not the row: a joined column exists as a column
     * whether or not <em>this</em> row found a match, so an unmatched row cannot defeat them and
     * they are not the idiom either. Measured 2026-09-10: without this, `CDISC-AD0898`'s
     * {@code var_exists(`&DOM.&DOMSEQ`) and empty(…)} was reported as a carrier — a template whose
     * own Check is the {@code empty} shape the §3 list sanctions.
     */
    private static final Set<String> ROW_INSENSITIVE = Set.of("var_exists", "ds_exists");

    /** The operator that reports "this value is in no row of the operation's result". */
    private static final String NOT_CONTAINED = "is_not_contained_by";

    @Test
    void everyLeftJoinPositiveTestHasAnUnmatchedRowReporter() throws Exception
    {
        Path rulesDir = CorpusPaths.rules();
        List<Path> packages;
        try (Stream<Path> files = Files.list(rulesDir))
        {
            packages = files.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.toString().endsWith(".json")).sorted().toList();
        }

        // ⚠⚠ Keyed "<package>|<DATASET>", not by dataset alone (review finding 4, 2026-09-10). A
        // validation run selects PACKAGES, so a reporter in `sdtmig-3-4` does not cover a carrier
        // executing from `sdtmig-3-2`: that run has the carrier's silence and no reporter, which is
        // exactly the under-reporting the ruling rests on not happening. A corpus-wide map reported
        // it covered.
        Map<String, Set<String>> reportersOf = new TreeMap<>();
        Map<String, String> carriers = new TreeMap<>();
        int rulesScanned = 0;
        int strictlyClassifiedRules = 0;
        int tolerantOnlyRules = 0;

        for (Path file : packages)
        {
            String pkgName = file.getFileName().toString();
            RulePackage pkg = RulePackageLoader.load(file);
            for (Rule rule : pkg.getRules().values())
            {
                if (rule == null || rule.getCheck() == null)
                {
                    continue;
                }
                rulesScanned++;
                // ⚠⚠ Phase 7 of PLAN-typed-expression-engine retired the v1 operator-leaf view,
                // so both walkers read the parsed expression through CheckExprUnits' v1-vocabulary
                // units. The strict-classification census below is the successor of the
                // "presents an operator-leaf Check" count that caught this lint going blind on
                // the phase-7 bump: a unit the classifier cannot name is emitted as opaque, and a
                // corpus that classifies mostly-opaque means the classifier has stopped speaking
                // the corpus's language, not that the corpus changed.
                Expr check = CheckExprUnits.checkExpr(rule);
                List<CheckExprUnits.Unit> units = CheckExprUnits.units(check);
                if (units.stream().allMatch(CheckExprUnits.Unit::strict))
                {
                    strictlyClassifiedRules++;
                }
                else
                {
                    tolerantOnlyRules++;
                }
                String id = rule.getCore() != null && rule.getCore().getId() != null
                        ? rule.getCore().getId()
                        : rule.getId();

                for (String dataset : reportedUnmatchedRowDatasets(rule, units))
                {
                    reportersOf.computeIfAbsent(pkgName + "|" + dataset, _ -> new LinkedHashSet<>())
                            .add(id);
                }
                for (String dataset : leftJoinedDatasetsTestedPositively(rule, units))
                {
                    carriers.put(pkgName + "|" + id + "|" + dataset, id);
                }
                // ⚠⚠ 2026-09-18 (PLAN-unplanned-visit-cascade): the four known carriers moved
                // their positive joined test (SV.SVPRESP == "Y") from a top-level conjunct into
                // tier 1 of the parenthesised cascade, so the top-level discriminator above no
                // longer sees them — but the HAZARD PERSISTS: on a study whose SV carries
                // SVPRESP, an unmatched primary row has tier 1 false (missing != "Y") and tier 2
                // false (its not var_exists(SV.SVPRESP) discriminator is a BROADCAST
                // column-presence fact, invisible to unit-level analysis), so the row is still
                // silently excluded and still needs its reporter. The carrier set is therefore
                // PINNED for the cascade family rather than derived. FDA/PMDA-SD1023 are not
                // members: they briefly carried the cascade (plan phase 3, with an additional
                // empty(SV.USUBJID) join-miss discriminator) and the phase was REVERTED the same
                // day for flooding VISIT-less domains, so those two rules have no SV join at all.
                // ⚠⚠ THE OSS TWIN HAS NOT TAKEN THIS CASCADE, and a passing fixture suite does
                // not say otherwise. Recorded 2026-09-19 (review round 1) as a record-accuracy
                // point; the divergence itself is owned by PLAN-typed-engine-oss-port row 8, NOT by
                // this file. Measured: cumba-oss-corej-rules carries 10 SVPRESP rules, among them
                // CDISC-CG0031-A and CDISC-CG0033-A — the two -A siblings PLAN-unplanned-visit-
                // cascade RETIRED here by absorbing them into their parents' cascade — while this
                // repo carries 18. The 8 ids the two repos share (CDISC-CG0031, -CG0033 and
                // -CG0653..-CG0658) execute their ported fixtures green on both sides, because no
                // fixture in that set reaches the branch the two spellings disagree on. ⇒ the OSS
                // port's "proven by execution, therefore semantics-preserving" argument holds for
                // the RENAME only; it carries no weight for the cascade.
                // ⛔ A future cascade adopter with no such discriminator belongs on this list.
                if (CASCADE_CARRIERS.contains(id))
                {
                    carriers.put(pkgName + "|" + id + "|SV", id);
                }
            }
        }

        assertTrue(rulesScanned > 3000,
                "corpus scan looks broken: only " + rulesScanned + " rules loaded");

        // ⛔ The SECOND blindness control, added when phase 7 proved the first one insufficient.
        // `rulesScanned` counts rules that LOADED; it stayed at its full value while both walkers
        // below saw nothing at all, because a Check the walkers cannot read is still a Check.
        // Count what they can actually read instead. ⚠ Not a floor on the carriers alone: the
        // coverage half (`reportersOf`) can go dark while the four carriers still match, and that
        // failure reports "all covered".
        // ⚠ A FRACTION, not a count. The corpus is 14 937 rules; an absolute floor of a few
        // thousand would be met by a lint that had gone blind to four rules in five.
        assertTrue(strictlyClassifiedRules > rulesScanned / 2,
                "only " + strictlyClassifiedRules + " of " + rulesScanned + " loaded rules"
                        + " classify fully into CheckExprUnits' v1-vocabulary units ("
                        + tolerantOnlyRules + " carry a tolerant-only or opaque unit) — the"
                        + " walkers below key on those units, so BOTH halves of this lint"
                        + " are blind and it would report full coverage. A collapse here means"
                        + " the classifier has stopped speaking the corpus's language, not that"
                        + " the corpus changed.");

        // ⛔ Non-vacuity, and it has already earned its keep: it caught this lint's FIRST detector,
        // which read the pre-load JSON shape and matched nothing at all. Both halves are derived,
        // so
        // a detector that stops matching reports "all covered" exactly as happily as a covered
        // corpus. The idiom has four known carriers (CDISC-CG0033, CG0031, CORE-000269, CORE-000270
        // — PLAN-left-join-silent-exclusion §2). If they are ever all legitimately re-authored
        // away,
        // RETIRE this lint in the same change and say so in R-7.9; do NOT lower this floor to keep
        // a
        // detector that no longer detects.
        // ⚠ On the DISTINCT RULE IDS, not on the key count. Since finding 4 the keys are
        // per-package (14 of them across 7 packages today), and that number moves whenever a
        // package
        // is added or retired — a floor on it would red for a reason that has nothing to do with
        // blindness. The rule population is the stable quantity the plan actually names.
        Set<String> carrierRules = new LinkedHashSet<>(carriers.values());
        // ⛔ 4 -> 2 on 2026-09-19 (PLAN-retire-core-family phase 6), and the comment above says
        // "do NOT lower this floor to keep a detector that no longer detects", so the reason has
        // to be on the record: the detector still detects BOTH surviving carriers. The population
        // named by PLAN-left-join-silent-exclusion §2 was CDISC-CG0031, CDISC-CG0033,
        // CORE-000269 and CORE-000270 — two CDISC arms and their two CORE copies — and the CORE
        // half went with the family. ⇒ 2 is the whole surviving population, not a degraded sweep.
        // ⚑ The same four leaves are GuardPositionLintTest's EXPECTED_DEFECTS, which went 4 keys
        // -> 2 in the same commit; if that map is ever two again while this floor is not, one of
        // the two instruments has stopped matching.
        assertTrue(carrierRules.size() >= 2,
                "only " + carrierRules.size() + " left-join-plus-positive-test carrier RULES found"
                        + " (expected at least CDISC-CG0031 and CDISC-CG0033 —"
                        + " PLAN-left-join-silent-exclusion §2, whose CORE-000269 / CORE-000270"
                        + " arms were retired with the family) — the detector has stopped"
                        + " matching, so the coverage assertion below is vacuous: " + carrierRules);

        System.out.println("### R-7.9 carriers: " + carriers.size());
        carriers.keySet().forEach(k -> System.out.println("###   " + k));

        List<String> uncovered = new ArrayList<>();
        for (String key : carriers.keySet())
        {
            String pkgName = key.substring(0, key.indexOf('|'));
            String dataset = key.substring(key.lastIndexOf('|') + 1);
            if (reportersOf.getOrDefault(pkgName + "|" + dataset, Set.of()).isEmpty())
            {
                uncovered.add(key);
            }
        }

        assertEquals(List.of(), uncovered,
                "R-7.9: a left join with a positive test on a joined column drops the unmatched"
                        + " primary row silently, and NO rule in the corpus reports that row for the"
                        + " dataset named. Either the reporter was deleted — for the SV family it is"
                        + " CDISC-CG0034 / FDA+PMDA-SD0065 — or this rule adopted the"
                        + " idiom without cover:\n" + String.join("\n", uncovered));
    }


    /**
     * ⛔ The hand-pinned {@link #CASCADE_CARRIERS} set must name only rules the corpus still ships.
     *
     * <p>
     * ⚠⚠ Everything else in this lint is <em>derived</em>, on purpose — see the class javadoc — and
     * the cascade set is the one exception, because the hazard it pins is invisible to unit-level
     * analysis. An exception to "derive it" needs its own guard, and until 2026-09-19 it had only a
     * prose note (<i>"a future cascade adopter belongs on this list"</i>). Prose does not red: two
     * of the four entries, {@code CORE-000269} and {@code CORE-000270}, had been dead ids since the
     * CORE family was retired, matching no rule in any package while the set still claimed to pin
     * four. A dead entry is not merely useless — it makes the set LOOK maintained, so the next
     * reader trusts a coverage claim that has quietly halved.
     * </p>
     *
     * @throws Exception
     *             when the shipped corpus cannot be read
     */
    @Test
    void everyCascadeCarrierStillShips() throws Exception
    {
        Path rulesDir = CorpusPaths.rules();
        Set<String> shipped = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(rulesDir))
        {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.toString().endsWith(".json")).sorted().toList())
            {
                shipped.addAll(RulePackageLoader.load(file).getRules().keySet());
            }
        }
        assertTrue(shipped.size() > 3000, "corpus scan looks broken: only " + shipped.size()
                + " shipped rule ids found, so" + " a pass here would say nothing about the pin");
        List<String> dead = CASCADE_CARRIERS.stream().filter(id -> !shipped.contains(id)).sorted()
                .toList();
        assertEquals(List.of(), dead,
                "CASCADE_CARRIERS names rule id(s) the corpus no longer ships, so the pin covers"
                        + " fewer rules than it claims and nothing says so: " + dead
                        + ". Remove a retired id; if the rule was RENAMED, put the new id in its"
                        + " place — the hazard travelled with it.");
    }


    /**
     * Datasets this rule reports an unmatched row for: it reads the dataset through an
     * <b>operation</b> (so an absent row is simply not in the result — no join, no null-filled
     * columns) and tests membership negatively against that operation's id.
     *
     * @param rule
     *            the shipped rule
     * @param units
     *            its Check's units, already collected by {@link CheckExprUnits#units}
     * @return the upper-cased dataset names it covers
     */
    private static Set<String> reportedUnmatchedRowDatasets(Rule rule,
            List<CheckExprUnits.Unit> units)
    {
        Set<String> covered = new LinkedHashSet<>();
        if (rule.getOperations() == null)
        {
            return covered;
        }
        for (Operation op : rule.getOperations())
        {
            if (op == null || op.getDomain() == null || op.getDomain().isEmpty()
                    || op.getId() == null)
            {
                continue;
            }
            for (CheckExprUnits.Unit unit : units)
            {
                if (NOT_CONTAINED.equals(unit.operator())
                        && op.getId().equals(valueText(unit.value())))
                {
                    covered.add(op.getDomain().toUpperCase(Locale.ROOT));
                }
            }
        }
        return covered;
    }


    /**
     * Datasets this rule joins {@code left} and then tests <b>positively</b> on one of their
     * columns — the R-7.9 idiom.
     *
     * <p>
     * ⚠⚠ <b>Top-level conjuncts only, and that is the discriminator — not the join type, and not
     * the polarity alone.</b> The unmatched row is excluded from the rule only when the positive
     * test sits on the rule's <em>only</em> path. {@code CORE-000726} tests
     * {@code SUPPDM.QNAM == "CETHNIC"} inside the <b>second disjunct</b> of a top-level {@code or}
     * whose first disjunct is entirely local, so an unmatched {@code SUPPDM} row is still checked
     * by that first branch and the rule is NOT a carrier. This lint's second draft read every depth
     * and reported it as one — i.e. as an under-reporting defect it does not have.
     * </p>
     *
     * @param rule
     *            the shipped rule
     * @param units
     *            its Check's units; only those with {@code topLevel()} are considered
     * @return the upper-cased dataset names carrying the idiom
     */
    private static Set<String> leftJoinedDatasetsTestedPositively(Rule rule,
            List<CheckExprUnits.Unit> units)
    {
        Set<String> carried = new LinkedHashSet<>();
        if (rule.getMatchDatasets() == null)
        {
            return carried;
        }
        for (MatchDataset md : rule.getMatchDatasets())
        {
            if (md == null || md.getName() == null || !"left".equalsIgnoreCase(md.getJoinType()))
            {
                continue;
            }
            // ⚠ An expansion token (`&DOM`) is not a dataset name — it is substituted before the
            // rule runs, and the expansions are linted on their own. Loader gate R6 keeps tokens
            // out
            // of Requirements.Variables for the same family of reasons.
            if (md.getName().indexOf('&') >= 0)
            {
                continue;
            }
            String prefix = md.getName().toUpperCase(Locale.ROOT) + ".";
            // ⚠⚠ Top-level conjuncts only — Unit.topLevel — and that is the discriminator: the
            // unmatched row is excluded from the rule only when the positive test sits on the
            // rule's ONLY path (see the method javadoc's CORE-000726 exhibit).
            for (CheckExprUnits.Unit unit : units)
            {
                if (unit.topLevel() && unit.operator() != null
                        && !NULL_SATISFIED.contains(unit.operator())
                        && !ROW_INSENSITIVE.contains(unit.operator()) && unit.name() != null
                        && unit.name().toUpperCase(Locale.ROOT).startsWith(prefix))
                {
                    carried.add(md.getName().toUpperCase(Locale.ROOT));
                }
            }
        }
        return carried;
    }


    /**
     * A membership unit's value side as plain text: a {@code $}-reference's name, or a string
     * literal's value. ⚠ The leaf-era predecessor compared a Jackson node's {@code toString}, whose
     * kept JSON quotes made every SV carrier read as uncovered while {@code CDISC-CG0034} sat right
     * there in the corpus — the same trap this helper exists to avoid.
     *
     * @param value
     *            the unit's value expression, possibly {@code null}
     * @return the reference/literal text, or {@code null}
     */
    private static @Nullable String valueText(@Nullable Expr value)
    {
        return switch (value)
        {
        case Expr.Ref r -> r.name();
        case Expr.Lit lit when lit.kind() == Expr.LitKind.STRING -> (String) lit.value();
        case null, default -> null;
        };
    }

}
