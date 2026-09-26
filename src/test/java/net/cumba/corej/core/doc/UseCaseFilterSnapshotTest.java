package net.cumba.corej.core.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import net.cumba.corej.core.exec.ScopeMatcher;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.run.StudyValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The run's use case over the <b>real corpus and study</b> — the DRAFT {@code Use_Case} carriers of
 * the full (not release) {@code target/rules} tree, run exactly as {@link FindingsSnapshot} runs
 * them (owner ruling X1, {@code PLAN-use-case-scope-filter}).
 *
 * <p>
 * The committed snapshot passes no use case, so its byte-identity after the plan proves only that
 * the no-use-case arm did not move. This test is the other half, the sensitivity arm: the same run
 * under {@code NONCLIN} must move <b>exactly</b> the rules whose {@code Scope.Use_Case} names only
 * other use cases — every row of every other rule stays byte-identical, and every excluded rule is
 * reported {@code SKIPPED} on every target dataset with the exact reason — and the same run under
 * {@code INDH} (the one code the corpus uses) must not move anything.
 * </p>
 *
 * <p>
 * <b>Opt-in</b>, like {@link FindingsSnapshotDriftTest} ({@code -Dcorej.findingsSnapshot=true}): it
 * runs the study three times and needs the untracked {@code testdata/study} fixture and the pickle
 * corpus. ⚠ A {@code .cdt} scenario cannot carry this proof — the {@code .cdt} harness accepts no
 * use case ({@code README-CDT.md}).
 * </p>
 */
@EnabledIfSystemProperty(named = "corej.findingsSnapshot", matches = "true")
class UseCaseFilterSnapshotTest
{

    private static final String NONCLIN = "NONCLIN";

    private static final String INDH = "INDH";

    /**
     * The corpus's {@code Use_Case} carriers in the snapshot's DRAFT SDTMIG 3.4 package, measured
     * 2026-09-26 — all {@code "INDH"}. A roster, not a count, so a re-key cannot pass unnoticed; if
     * the corpus changes, re-measure ({@code rg -l Use_Case rules-src}) and update it.
     */
    private static final Set<String> KNOWN_CARRIERS = Set.of("DRAFT-900001", "DRAFT-900002",
            "DRAFT-900003", "DRAFT-900004", "DRAFT-900005", "DRAFT-900043", "DRAFT-900045");

    @Test
    void theRunsUseCaseSkipsExactlyTheRulesThatNameAnotherOne() throws IOException
    {
        StudyValidationResult none = FindingsSnapshot.run(null);
        StudyValidationResult nonclin = FindingsSnapshot.run(NONCLIN);
        StudyValidationResult indh = FindingsSnapshot.run(INDH);

        // ---- the population: who declares a use case, and whom NONCLIN excludes
        Map<String, Rule> carriers = new LinkedHashMap<>();
        for (Rule r : none.rules())
        {
            Scope scope = r.getScope();
            if (r.effectiveId() != null && scope != null && scope.getUseCase() != null
                    && !scope.getUseCase().isBlank())
            {
                carriers.put(r.effectiveId(), r);
            }
        }
        assertEquals(KNOWN_CARRIERS, carriers.keySet(),
                "the snapshot's Use_Case carriers moved — re-measure and update the roster");
        Set<String> excluded = new TreeSet<>();
        carriers.forEach((id, r) ->
        {
            if (!ScopeMatcher.matchesUseCase(r, NONCLIN))
            {
                excluded.add(id);
            }
        });
        assertEquals(KNOWN_CARRIERS, excluded, "every carrier is INDH-only today");

        List<FindingsSnapshot.Row> noneRows = FindingsSnapshot.project(none).rows();
        List<FindingsSnapshot.Row> nonclinRows = FindingsSnapshot.project(nonclin).rows();

        // ---- non-vacuity: without a use case the excluded rules really run and fire somewhere
        List<FindingsSnapshot.Row> excludedBefore = rows(noneRows,
                r -> excluded.contains(r.coreId()));
        assertTrue(excludedBefore.stream().anyMatch(r -> !"status:SKIPPED".equals(r.status())),
                "without a use case the carriers must run somewhere, or the skip below proves"
                        + " nothing");
        assertTrue(excludedBefore.stream().anyMatch(r -> !r.status().startsWith("status:")),
                "and at least one carrier must fire a finding on the study");

        // ---- every OTHER rule is byte-identical under NONCLIN
        Predicate<FindingsSnapshot.Row> other = r -> !excluded.contains(r.coreId());
        List<String> otherBefore = lines(rows(noneRows, other));
        List<String> otherAfter = lines(rows(nonclinRows, other));
        assertEquals(otherBefore.size(), otherAfter.size(), "row count of the other rules");
        assertEquals(otherBefore, otherAfter, "the use case moved a rule that declares none");

        // ---- every excluded rule: no finding, SKIPPED on every dataset, the exact reason
        List<FindingsSnapshot.Row> excludedAfter = rows(nonclinRows,
                r -> excluded.contains(r.coreId()));
        List<FindingsSnapshot.Row> notSkipped = excludedAfter.stream()
                .filter(r -> !"status:SKIPPED".equals(r.status()) || !"0".equals(r.values()))
                .toList();
        assertTrue(notSkipped.isEmpty(), () -> "an excluded rule still ran: " + notSkipped);
        int datasets = nonclin.datasets().size();
        String reason = "use case NONCLIN not in Scope.Use_Case [INDH]";
        for (String id : excluded)
        {
            List<Map<String, Object>> skips = nonclin.sections().skippedRules().stream()
                    .filter(row -> id.equals(row.get("core_id"))).toList();
            assertEquals(datasets, skips.size(),
                    () -> id + ": one Skipped_Rules row per target dataset: " + skips);
            assertTrue(skips.stream().allMatch(row -> reason.equals(row.get("reason"))),
                    () -> id + ": " + skips);
            assertEquals(datasets, rows(excludedAfter, r -> id.equals(r.coreId())).size(),
                    id + ": one SKIPPED status row per target dataset");
            Map<String, Object> report = nonclin.sections().rulesReport().stream()
                    .filter(row -> id.equals(row.get("core_id"))).findFirst().orElseThrow();
            assertEquals("SKIPPED", report.get("status"), id);
        }
        assertEquals(NONCLIN, nonclin.sections().conformanceDetails().get("TIG_Use_Case"));

        // ---- INDH is the carriers' own use case: nothing moves at all
        List<String> indhLines = lines(FindingsSnapshot.project(indh).rows());
        assertFalse(indhLines.isEmpty());
        assertEquals(lines(noneRows), indhLines, "a run in the carriers' own use case moved rows");
    }


    private static List<FindingsSnapshot.Row> rows(List<FindingsSnapshot.Row> all,
            Predicate<FindingsSnapshot.Row> keep)
    {
        return all.stream().filter(keep).toList();
    }


    private static List<String> lines(List<FindingsSnapshot.Row> rows)
    {
        List<String> out = new ArrayList<>(rows.size());
        for (FindingsSnapshot.Row r : rows)
        {
            out.add(r.line());
        }
        return out;
    }
}
