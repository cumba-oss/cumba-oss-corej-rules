package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.cumba.corej.core.exec.ScopeMatcher;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.Scope;
import org.junit.jupiter.api.Test;

/**
 * The corpus's {@code Scope.Use_Case} carriers, guarded in ordinary CI
 * ({@code PLAN-use-case-scope-filter}, review round 1): since owner ruling X1 a run's use case
 * filters rules, so which rules carry the field — and that no <b>released</b> rule does — is a
 * behavioural fact, not a curiosity.
 *
 * <ul>
 * <li>Every generated package of the full tree ({@link CorpusPaths#rules()}) is loaded through the
 * production loader; the carriers must be exactly {@link #KNOWN_CARRIERS} — a roster, not a count,
 * so a re-key cannot pass unnoticed — each loads clean (the R-4.10 gate) and each is excluded under
 * {@code NONCLIN} with the exact reason the report prints.</li>
 * <li>The release tree ({@link CorpusPaths#releaseRules()}) carries <b>none</b>: a shipped bundle's
 * use case filters nothing, which is what "shipped behaviour unchanged" rests on.</li>
 * </ul>
 * <p>
 * Needs no study fixture; the opt-in {@code UseCaseFilterSnapshotTest} proves the filter's effect
 * on a real run.
 * </p>
 */
class UseCaseCarrierCorpusTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Measured 2026-09-26 ({@code rg -l Use_Case rules-src}): all six are DRAFT (DRAFT-900045, the
     * seventh, was an absent-column sister retired by PLAN-message-folded-corpus-alignment) and all
     * {@code "INDH"}. If the corpus changes, re-measure and update — never loosen to a count.
     */
    private static final Set<String> KNOWN_CARRIERS = Set.of("DRAFT-900001", "DRAFT-900002",
            "DRAFT-900003", "DRAFT-900004", "DRAFT-900005", "DRAFT-900043");

    /** Every rule of every package in {@code packages.json} under {@code dir}, keyed by id. */
    private static Map<String, List<Rule>> carriers(Path dir) throws IOException
    {
        Map<String, List<Rule>> out = new TreeMap<>();
        var manifest = MAPPER.readTree(Files.readString(dir.resolve("packages.json")));
        int packages = 0;
        for (var entry : manifest.get("packages"))
        {
            RulePackage pkg = RulePackageLoader.load(dir.resolve(entry.get("file").asText()));
            packages++;
            for (Rule rule : pkg.getRules().values())
            {
                Scope scope = rule == null ? null : rule.getScope();
                if (scope != null && scope.getUseCase() != null)
                {
                    out.computeIfAbsent(String.valueOf(rule.effectiveId()), k -> new ArrayList<>())
                            .add(rule);
                }
            }
        }
        assertTrue(packages >= 30, "population floor: " + packages + " packages under " + dir);
        return out;
    }


    @Test
    void theFullCorpusCarriesExactlyTheKnownCarriers_eachExcludedUnderAnotherUseCase()
        throws IOException
    {
        Map<String, List<Rule>> carriers = carriers(CorpusPaths.rules());

        assertEquals(new TreeSet<>(KNOWN_CARRIERS), carriers.keySet(),
                "the Use_Case carriers moved — re-measure and update the roster");
        for (Map.Entry<String, List<Rule>> e : carriers.entrySet())
        {
            for (Rule r : e.getValue())
            {
                assertNull(r.getLoadError(), e.getKey() + ": " + r.getLoadError());
                assertEquals("use case NONCLIN not in Scope.Use_Case [INDH]",
                        ScopeMatcher.describeUseCaseMismatch(r, "NONCLIN"), e.getKey());
                assertNull(ScopeMatcher.describeUseCaseMismatch(r, "INDH"), e.getKey());
            }
        }
    }


    @Test
    void noReleasedRuleCarriesAUseCase() throws IOException
    {
        Path release = CorpusPaths.releaseRules();
        assertFalse(carriers(CorpusPaths.rules()).isEmpty(),
                "non-vacuity: the full tree must still have carriers for this to mean anything");
        assertEquals(Map.of(), carriers(release),
                "a released rule carries Scope.Use_Case, so a shipped bundle's use case now"
                        + " filters rules — update the plan's premise and this guard together");
    }
}
