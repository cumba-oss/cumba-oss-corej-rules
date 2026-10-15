package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.VariableUniverse;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 of {@code plans/done/PLAN-extend-expression-engine.md} — corpus regression: every shipped
 * rule package loads without any present-but-unrecognized {@code Rule_Type} / {@code Sensitivity} /
 * {@code Executability} value. The 2026-06-12 corpus audit found exactly two offenders
 * (CDISC-AD0018 / CDISC-AD0708, {@code "Sensitivity": "Variable"}), fixed to {@code "Dataset"} in
 * the same change that introduced the fail-loud validation — so the corpus must be fully clean.
 * Other load errors (non-executable operators etc.) are intentional and out of scope here.
 *
 * <p>
 * ⚠ A second shipped folder used to be held to the same enum-strict schema, and its agreement with
 * this one was a free cross-check on the walk itself. It is gone; the non-zero floors in
 * {@link #assertFolderClean} are what stands in its place.
 * </p>
 */
class ShippedRulesEnumCorpusTest
{

    /**
     * The load-error fragments the ratchet treats as an invalid enum value, each keyed by the field
     * it polices. ⚠ Each is a literal copied from {@code RulePackageLoader}; if the loader rewords
     * one, the ratchet silently matches nothing for that field. That is why every entry has a
     * planted probe in {@link #PROBES}, and why {@link #theRatchetIsLive_positiveControl} requires
     * the two key sets to be equal.
     */
    private static final Map<String, String> MARKERS = Map.of("Rule_Type",
            "is no longer a rule field", "Sensitivity", "Invalid Sensitivity", "Executability",
            "Invalid Executability",
            // Fix #236 — Join_Type joined the same fail-loud family. The corpus was parsed before
            // the gate shipped and carries exactly two states (`left` and absent), so this arm
            // must stay at zero: a hit means either a new authored value or a regression in the
            // gate.
            "Join_Type", "Invalid Join_Type",
            // The Variable_Universe vocabulary (Data / Define) is closed like the others ...
            "Variable_Universe", "Invalid Variable_Universe",
            // ... and `Define` is only legal on a Check that iterates the variable cursor
            // (PLAN-leaf-scope-domain-inference §3.7, RulePackageLoader.checkVariableUniverse).
            "Variable_Universe:Define", "Variable_Universe: Define on a Check whose");

    /** One synthetic rule per {@link #MARKERS} key that the loader must reject with that marker. */
    private static final Map<String, String> PROBES = Map.of("Rule_Type",
            "\"Rule_Type\": \"Record Data\", \"Check\": {\"expression\": \"AESEQ == 1\"}",
            "Sensitivity",
            "\"Sensitivity\": \"Bogus\", \"Check\": {\"expression\": \"AESEQ == 1\"}",
            "Executability",
            "\"Executability\": \"Bogus\", \"Check\": {\"expression\": \"AESEQ == 1\"}",
            "Join_Type",
            "\"Match_Datasets\": [{\"Name\": \"DM\", \"Keys\": [\"USUBJID\"],"
                    + " \"Join_Type\": \"sideways\"}], \"Check\": {\"expression\": \"DM.AGE == 1\"}",
            "Variable_Universe",
            "\"Variable_Universe\": \"Bogus\", \"Check\": {\"expression\": \"AESEQ == 1\"}",
            "Variable_Universe:Define",
            "\"Variable_Universe\": \"Define\", \"Check\": {\"expression\": \"AESEQ == 1\"}");

    /**
     * The positive control (finding A3 of {@code FINDINGS-unowned-residuals}): the zero the corpus
     * ratchet below reports is evidence only while each {@link #MARKERS} literal still matches what
     * the loader emits. Every marker gets a planted rule through the same {@code RulePackageLoader}
     * entry point, and the planted rule must be caught by exactly that marker. Reword a loader
     * message and this reds, instead of the ratchet passing over nothing.
     *
     * @throws IOException
     *             never in practice: the probes are in-memory strings
     */
    @Test
    void theRatchetIsLive_positiveControl() throws IOException
    {
        assertEquals(MARKERS.keySet(), PROBES.keySet(),
                "every ratchet marker needs a planted probe, and every probe a marker");
        for (Map.Entry<String, String> probe : new java.util.TreeMap<>(PROBES).entrySet())
        {
            RulePackage pkg = RulePackageLoader.loadFromString(
                    "{\"rules\": {\"probe\": {" + "\"Core\": {\"Id\": \"PROBE-ENUM\"},"
                            + " \"Scope\": {\"Domains\": {\"Include\": [\"AE\"]}}, "
                            + probe.getValue() + "}}}");
            Rule rule = pkg.getRules().values().iterator().next();
            String marker = MARKERS.get(probe.getKey());
            assertNotNull(rule.getLoadError(),
                    probe.getKey() + ": the loader no longer rejects the planted value at all");
            assertTrue(rule.getLoadError().contains(marker), probe.getKey()
                    + ": the loader's message no longer contains the ratchet's marker '" + marker
                    + "', so the corpus ratchet matches nothing for this field. Message: "
                    + rule.getLoadError());
        }
    }


    @Test
    void expressionCorpus_hasNoInvalidEnumValues() throws IOException
    {
        // rules/ ships the family-scoped expression-syntax packages the manifest declares,
        // generated from rules-src/ by RuleExprGenerator.
        assertFolderClean(CorpusPaths.rules(), manifestPackages(CorpusPaths.rules()));
    }


    /**
     * {@code Fix #236} / acceptance {@code A3} — the shipped {@code Join_Type} vocabulary is
     * <b>closed</b>, so making an unrecognised value a load error costs the corpus nothing.
     *
     * <p>
     * ⚠⚠ <b>Two different units, and both prior figures confused them.</b> A rule ships into
     * several packages, so counting {@code Match_Datasets} <em>entries</em> multiplies the rules.
     * Wave-34 lane D reported <i>"158 distinct rules"</i> and
     * {@code plans/done/PLAN-expired-justifications-triage.md} §S2 said <i>"157"</i> — both were
     * entry counts wearing a rule label. Measured 2026-08-12 over {@code rules/}: <b>158
     * entries</b> carrying {@code left}, across <b>38 distinct rules</b>; the remaining entries
     * author nothing.
     * </p>
     *
     * <p>
     * ⚠ <b>Re-based:</b> the 158 / 38 pair is the pre-alignment measurement. Twin-alignment wave
     * TA-2 ({@code plans/done/PLAN-twin-alignment-completion.md} §12, decisions {@code D-TA-2} /
     * {@code D-TA-6a}) removed 73 of the entries and 20 of the rules — 19 rules lost the key and
     * {@code FDA-SD1023} lost its {@code Match_Datasets} outright — leaving <b>85 entries across 18
     * distinct rules</b>, which is what the floors below are sized against.
     * </p>
     *
     * <p>
     * ⚑ The counts are asserted as floors, not equalities — the corpus is regenerated by other
     * lanes and an exact figure would red on unrelated authoring. The floors are {@code >= 80}
     * entries and {@code >= 15} rules, re-based by TA-2 from the {@code >= 100} / {@code >= 30}
     * pair that had been sized against 158 / 38 and would now both fail. The <b>closed
     * vocabulary</b> assertion is the one that carries the acceptance, and it is exact.
     * </p>
     *
     * <p>
     * ⚠ This walks the loaded model, so the values it sees are
     * <b>post-{@code normalizeJoinTypes}</b> — i.e. an omitted {@code Join_Type} reads as
     * {@code inner} here, not as {@code null}. That is deliberate: what matters is that no authored
     * value falls outside the vocabulary, and the loader's own stamped default is by construction
     * inside it.
     * </p>
     */
    @Test
    void shippedJoinTypeVocabulary_isClosed() throws IOException
    {
        java.util.Map<String, Integer> byValue = new java.util.TreeMap<>();
        java.util.Set<String> rulesWithLeft = new java.util.TreeSet<>();
        for (Path file : listRulePackages(CorpusPaths.rules()))
        {
            for (Rule rule : RulePackageLoader.load(file).getRules().values())
            {
                if (rule == null || rule.getMatchDatasets() == null)
                {
                    continue;
                }
                for (net.cumba.corej.core.model.MatchDataset md : rule.getMatchDatasets())
                {
                    // Absence is legal (it is what normalizeJoinTypes stamps `inner` onto) and is
                    // not part of the vocabulary question, so it is not counted here.
                    if (md == null
                            || net.cumba.corej.core.model.JoinType.isAbsent(md.getJoinType()))
                    {
                        continue;
                    }
                    String value = java.util.Objects.requireNonNull(md.getJoinType());
                    byValue.merge(value, 1, Integer::sum);
                    if ("left".equals(value))
                    {
                        rulesWithLeft.add(ruleId(rule));
                    }
                }
            }
        }
        assertTrue(
                byValue.keySet().stream()
                        .allMatch(v -> net.cumba.corej.core.model.JoinType.fromJson(v) != null),
                "every shipped Join_Type must be inside the JoinType vocabulary — otherwise "
                        + "Fix #236's gate turns the corpus red. Saw: " + byValue);
        // ⚠ Re-based 2026-08-17 under D-TA-2 (`Join_Type: left` is over-fire wherever the
        // unmatched-row case is already reported by a referential-integrity rule): 19 rules lost
        // the key and FDA-SD1023 lost its Match_Datasets entirely under D-TA-6a, taking the
        // population from 158 entries / 38 rules to 85 entries / 18 rules. These stay FLOORS, not
        // equalities — the corpus is regenerated by other lanes — but the old 100/30 pair was
        // sized against 158/38 and both would now fail.
        // Floor sized on the CORE-free corpus (76 entries); a CORE-bearing tree exceeds it.
        assertTrue(byValue.getOrDefault("left", 0) >= 70,
                "expected the `left` entry population (85 as at 2026-08-17, D-TA-2; 76 on the"
                        + " CORE-free corpus, 2026-09-19); saw " + byValue);
        // ⚠⚠ ZERO HEADROOM, re-derived 2026-09-19 (review round 1). Measured over target/rules:
        // 76 `left` entries across exactly 15 distinct rules, so this floor now sits ON the value
        // and the next carrier the corpus loses reds it. That is not what the paragraph above
        // promises ("these stay FLOORS, not equalities — the corpus is regenerated by other
        // lanes"), and it is recorded rather than lowered: 15 is the truth, and a floor slackened
        // below the truth to buy comfort is the erosion this file's own history is about.
        // ⛔ §13 of PLAN-retire-core-family cleared this file on the strength of the sibling
        // comment above, which covers the ENTRY floor (85 -> 76, six of headroom). The RULE floor
        // was never re-measured in that pass: the CORE family carried three of the 18, so the
        // retirement took it 18 -> 15 silently, straight onto the pin.
        assertTrue(rulesWithLeft.size() >= 15,
                "expected the `left` RULE population (18 distinct rules as at 2026-08-17, D-TA-2;"
                        + " 15 on the CORE-free corpus, 2026-09-19 — this floor has NO headroom);"
                        + " saw " + rulesWithLeft.size() + " — ⚠ entries ("
                        + byValue.getOrDefault("left", 0) + ") and rules are different units");
        assertTrue(byValue.getOrDefault("left", 0) > rulesWithLeft.size(),
                "entries must outnumber rules — a rule ships into several packages; if these are "
                        + "equal the walk is measuring one unit twice");
    }


    /** The number of packages the folder's {@code packages.json} manifest declares. */
    private static int manifestPackages(Path folder) throws IOException
    {
        return RulePackageManifest.load(folder).packages().size();
    }


    private static void assertFolderClean(Path folder, int expectedPackages) throws IOException
    {
        List<String> offenders = new ArrayList<>();
        java.util.Set<String> defineUniverseRules = new java.util.TreeSet<>();
        int packages = 0;
        int rules = 0;
        for (Path file : listRulePackages(folder))
        {
            packages++;
            RulePackage pkg = RulePackageLoader.load(file);
            for (Rule rule : pkg.getRules().values())
            {
                if (rule == null)
                {
                    continue;
                }
                rules++;
                if (rule.getVariableUniverse() == VariableUniverse.DEFINE)
                {
                    defineUniverseRules.add(ruleId(rule));
                }
                String loadError = rule.getLoadError();
                if (loadError != null && MARKERS.values().stream().anyMatch(loadError::contains))
                {
                    offenders.add(file.getFileName() + "/" + ruleId(rule) + ": " + loadError);
                }
            }
        }
        // `packages >= expectedPackages` is satisfied by 0 >= 0, so pin the manifest itself: with
        // only one shipped folder left there is no second corpus to disagree with a broken walk.
        // ⚠ The message used to read "expected at least 56", which was neither the floor (45) nor
        // the census: 56 was a pre-retirement package count. Measured 2026-09-19: the manifest
        // declares 47 (rules-release declares 32, but this walk only ever reads rules/).
        assertTrue(expectedPackages >= 45, "the " + folder + "/ manifest declares only "
                + expectedPackages + " packages — expected at least 45 (47 as at 2026-09-19)");
        assertTrue(packages >= expectedPackages, "expected the full shipped corpus in " + folder
                + "/, saw " + packages + " packages (expected >= " + expectedPackages + ")");
        assertTrue(rules >= 3000,
                "expected the full shipped corpus in " + folder + "/, saw " + rules + " rules");
        // The Variable_Universe arms (both MARKERS entries) are only evidence while the corpus
        // still ships the value they police: 14 distinct rules declared `Define` on 2026-10-15
        // (10 CDISC + 4 DRAFT). Without this presence half, a corpus that dropped the field would
        // keep both arms green over nothing.
        assertTrue(defineUniverseRules.size() >= 10,
                "expected the `Variable_Universe: Define` population (14 distinct rules as at"
                        + " 2026-10-15); saw " + defineUniverseRules);
        assertTrue(offenders.isEmpty(), "shipped rules with invalid enum values ("
                + offenders.size() + "):\n" + String.join("\n", offenders));
    }


    private static List<Path> listRulePackages(Path folder) throws IOException
    {
        Path dir = folder;
        try (Stream<Path> files = Files.list(dir))
        {
            return files.filter(p ->
            {
                String n = p.getFileName().toString();
                return n.startsWith("rules-") && n.endsWith(".json") && !n.endsWith("-expr.json");
            }).sorted().toList();
        }
    }


    private static String ruleId(Rule rule)
    {
        if (rule.getCore() != null && rule.getCore().getId() != null)
        {
            return rule.getCore().getId();
        }
        return rule.getId() != null ? rule.getId() : "<unknown>";
    }
}
