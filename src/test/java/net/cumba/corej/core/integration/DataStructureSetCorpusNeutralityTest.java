package net.cumba.corej.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.ScopeMatcher;
import net.cumba.corej.core.metadata.AdamDataStructureDetector;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * <b>Fix #179's acceptance criterion, measured against the shipped corpus.</b>
 *
 * <p>
 * Phase 1 of {@code plans/done/PLAN-adam-structure-set-valued.md} must be <em>invisible</em>:
 * making the detected ADaM data structure a set may not change which rules a dataset selects. The
 * argument is "by construction" — a medical-device dataset used to detect as the folded base token
 * and now detects as {@code [variant, base]}, and since {@code Include}/{@code Exclude} both match
 * on any member, the decision can only change for a rule that <em>names</em> a variant token. This
 * test turns that argument into a measurement over every shipped rule.
 * </p>
 *
 * <p>
 * ⚠ <b>It is deliberately phrased so that Phase 3 does not have to weaken it.</b> A rule that
 * legitimately starts scoping itself to a device specialisation is exempted <em>because it names
 * one</em>, not by being added to a list of allowed exceptions. The measurement of how many such
 * rules exist is reported by {@link #reportTheStructureScopeVocabulary()}, which fails only if the
 * corpus stops being readable at all.
 * </p>
 *
 * <p>
 * Companion unit-level pins: {@code AdamDataStructureDetectorTest} (the set and the
 * pre-Fix-#175-fold identity), {@code ScopeMatcherStructureSubclassTest} (the gate's asymmetry and
 * the {@code Exclude}-excludes-subtypes decision), {@code AdamSubclassDetectorStructureSetTest}
 * (the one branch in the engine that reads the structure).
 * </p>
 */
class DataStructureSetCorpusNeutralityTest
{

    private static final Path RULES_DIR = CorpusPaths.rules();

    /** Every shipped rule that carries a {@code Scope.Data_Structures} facet, by Core id. */
    private static Map<String, Rule> structureScopedRules;

    @BeforeAll
    static void loadCorpus() throws IOException
    {
        structureScopedRules = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(RULES_DIR))
        {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> p.getFileName().toString().startsWith("rules-")).sorted().toList())
            {
                RulePackage pkg = load(file);
                if (pkg.getRules() == null)
                {
                    continue;
                }
                for (Map.Entry<String, Rule> named : pkg.getRules().entrySet())
                {
                    Rule rule = named.getValue();
                    if (rule.getScope() != null && rule.getScope().getDataStructures() != null)
                    {
                        structureScopedRules.put(file.getFileName() + "#" + named.getKey(), rule);
                    }
                }
            }
        }
    }


    private static RulePackage load(Path file)
    {
        try
        {
            return RulePackageLoader.load(file);
        }
        catch (IOException ex)
        {
            throw new UncheckedIOException(ex);
        }
    }


    /**
     * The corpus really was read. Without this the two assertions below would iterate an empty map
     * and pass vacuously — the exact failure mode a corpus-driven test invites.
     */
    @Test
    void theCorpusWasRead()
    {
        assertTrue(structureScopedRules.size() >= 50,
                "only " + structureScopedRules.size()
                        + " shipped rules with a Scope.Data_Structures" + " facet were found under "
                        + RULES_DIR + " — the corpus was not read, so"
                        + " every other assertion in this class is vacuous");
    }


    /**
     * ⚑⚑ <b>The acceptance criterion.</b> For every shipped structure-scoped rule and every device
     * specialisation, the rule's verdict on a dataset carrying that specialisation's <em>set</em>
     * is the same as its verdict on a dataset carrying the folded base token alone — which is
     * precisely what such a dataset presented before Fix #179. A rule that names a variant token in
     * its own scope is exempt, because for it the change is the intended one.
     */
    @Test
    void everyShippedRuleDecidesTheSameOnASpecialisationAsOnItsPreFix175FoldedBase()
    {
        // Hand-transcribed pre-Fix-#175 fold — NOT read back out of AdamDataStructureDetector, so a
        // change to the hierarchy cannot move both sides of the comparison at once.
        List<Map.Entry<String, String>> foldedBy = List.of(
                Map.entry("MEDICAL DEVICE BASIC DATA STRUCTURE", "BASIC DATA STRUCTURE"),
                Map.entry("MEDICAL DEVICE OCCURRENCE DATA STRUCTURE", "OCCURRENCE DATA STRUCTURE"),
                Map.entry("DEVICE LEVEL ANALYSIS DATASET", "ADAM OTHER"));

        List<String> divergences = new ArrayList<>();
        int compared = 0;
        for (Map.Entry<String, Rule> entry : structureScopedRules.entrySet())
        {
            Rule rule = entry.getValue();
            for (Map.Entry<String, String> pair : foldedBy)
            {
                if (namesToken(rule, pair.getKey()))
                {
                    // Phase 3 exemption: the rule opted into the specialisation, so a different
                    // verdict is the whole point.
                    continue;
                }
                compared++;
                boolean before = ScopeMatcher.describeDataStructureMismatch(rule,
                        List.of(pair.getValue())) == null;
                boolean after = ScopeMatcher.describeDataStructureMismatch(rule,
                        AdamDataStructureDetector.structureSet(pair.getKey())) == null;
                if (before != after)
                {
                    divergences.add(entry.getKey() + ": " + pair.getKey() + " matched=" + after
                            + " but the pre-Fix-#175 fold to " + pair.getValue() + " matched="
                            + before);
                }
            }
        }
        assertTrue(compared > 0, "nothing was compared — the exemption swallowed the population");
        assertEquals(List.of(), divergences,
                "Fix #179 must be invisible: these shipped rules change their verdict on a"
                        + " medical-device dataset, which is a DEFECT and not churn. "
                        + divergences);
    }


    /**
     * The measurement behind the "by construction" argument, and the number Phase 3 will move: how
     * many shipped {@code Scope.Data_Structures} entries name each token. Reported rather than
     * ratcheted — a count pinned here would fail the moment Phase 3 migrates the corpus, and this
     * class's real ratchet is
     * {@link #everyShippedRuleDecidesTheSameOnASpecialisationAsOnItsPreFix175FoldedBase()}, which
     * survives the migration.
     */
    @Test
    void reportTheStructureScopeVocabulary()
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Rule rule : structureScopedRules.values())
        {
            for (String token : tokensOf(rule))
            {
                counts.merge(token, 1, Integer::sum);
            }
        }
        assertTrue(!counts.isEmpty(), "no Scope.Data_Structures tokens found at all");
        System.out.println("Fix #179 — shipped Scope.Data_Structures token counts: " + counts);
        // Every authored token must be in the vocabulary the loader validates against; otherwise
        // the
        // rule carries a loadError and never matches, which the corpus must never ship.
        for (String token : counts.keySet())
        {
            assertTrue(
                    "ALL".equals(token)
                            || AdamDataStructureDetector.STRUCTURE_TOKENS.contains(token),
                    "shipped Scope.Data_Structures token outside the vocabulary: " + token);
        }
    }


    private static boolean namesToken(Rule rule, String token)
    {
        return tokensOf(rule).contains(token);
    }


    private static List<String> tokensOf(Rule rule)
    {
        List<String> tokens = new ArrayList<>();
        var scope = rule.getScope().getDataStructures();
        if (scope.getInclude() != null)
        {
            tokens.addAll(scope.getInclude());
        }
        if (scope.getExclude() != null)
        {
            tokens.addAll(scope.getExclude());
        }
        return tokens;
    }
}
