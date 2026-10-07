package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * §9a and §9b of {@code plans/done/PLAN-rules-corpus-build-integration.md} — the fields the
 * <b>report</b> reads, which no scenario fixture can protect.
 *
 * <h2>Why these two need their own guard</h2>
 *
 * <p>
 * The {@code .cdt} scenario net is <em>behavioural</em>: it asserts verdicts. A field that only
 * reaches the report has no verdict to change, so a packaging regression in it is invisible to all
 * 3,020 fixtures — and to the 788 rules with no fixture at all, doubly so. These are the fields §10
 * keeps <em>for reporting reasons</em>, so they are exactly the ones whose survival has to be
 * asserted rather than assumed.
 * </p>
 *
 * <h2>⚠ Genuinely uncovered today, despite appearances</h2>
 *
 * <p>
 * {@code OutputVariableDeriverTest} (in {@code corej-core}) unit-tests the derivation
 * <em>logic</em> on hand-built rules, and the rulespec suite uses {@code Outcome} only as fixture
 * input with no derivation assertions. {@code OutputVariableDerivationReportTest} looks like the
 * missing coverage but is a <b>report generator</b>: its own javadoc says "nothing about the delta
 * is asserted beyond 'the corpus was actually read'", and its only assertion is
 * {@code byRule.size() > 3000}. Do not mistake it for a guard.
 * </p>
 */
class ReleasedRuleFieldFidelityTest
{

    private static final Path CHECKS = Path.of("rules-src", "checks");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Floor on the population, so a run that read nothing cannot pass. */
    // 3700 -> 3400 (PLAN-retire-core-family phase 6): distinct shipped ids 3906 -> 3411.
    // ⚠ TWO ARMS, TWO POPULATIONS, one constant: :138 counts DISTINCT shipped ids (3411, the
    // arm that failed) and :207 counts FAN-OUT entries (14903 -> 12818, which never came close).
    // Lowering it for the first arm therefore LOOSENS the second by the same amount — said here
    // because nothing else records it.
    // 3400 -> 3390 (PLAN-message-folded-corpus-alignment, 2026-10-07): 42 absent-column sisters
    // retired, 3 396 distinct shipped ids.
    private static final int MIN_RULES = 3390;

    /*
     * ⚑ The absent-OV CEILING that used to sit here ({@code MAX_DERIVED_OUTPUT_VARIABLE_ENTRIES},
     * 499 → 513 → 519 → 556 → 572 → 523, re-measured at every wave) was RETIRED by
     * {@code PLAN-redundant-output-variables-sweep} Q5 (owner, 2026-10-04): its doctrine — "a rule
     * acquiring an explicit list is progress" — is the reverse of the sweep, which deletes every
     * authored list the derivation reproduces (2 029 keys; ~8 300 absent-OV package entries after
     * it). The population is now watched from the other side:
     * {@code RedundantOutputVariableLintTest} fails on any authored entry the engine derives
     * anyway, so growth of this population is the intended state, not a surface to re-measure.
     */

    /**
     * ⚠ A FLOOR. The derivation arm below only asserts anything about entries it actually
     * classifies as absent-or-empty, so a change that broke the raw-node lookup would classify
     * none, assert nothing, and stay green — a bound with no floor is how a guard becomes vacuous
     * without anyone noticing. (Its ceiling sibling is retired — see above.)
     */
    private static final int MIN_DERIVED_OUTPUT_VARIABLE_ENTRIES = 400;

    /**
     * §9a — every shipped {@code Description} is the one the rule was authored with.
     *
     * <p>
     * Compared against {@code rules-src/checks/<family>/<Core.Id>.yaml} directly, not against an
     * intermediate: dropping, truncating or re-encoding the field anywhere between the authored
     * YAML and the released JSON shows up here.
     * </p>
     */
    @Test
    void everyShippedDescriptionMatchesItsAuthoredSource() throws IOException
    {
        Map<String, String> authored = authoredDescriptions();
        List<String> offenders = new ArrayList<>();
        int checked = 0;

        for (Map.Entry<String, Rule> e : shippedRules().entrySet())
        {
            String coreId = e.getKey();
            if (!authored.containsKey(coreId))
            {
                offenders.add(coreId + ": shipped but has no rules-src/checks source file");
                continue;
            }
            checked++;
            String want = authored.get(coreId);
            String got = e.getValue().getDescription();
            if (want == null ? got != null : !want.equals(got))
            {
                offenders.add(coreId + ": authored [" + want + "] but shipped [" + got + "]");
            }
        }

        assertTrue(checked >= MIN_RULES, "only " + checked + " descriptions compared (expected at"
                + " least " + MIN_RULES + "); a comparison over nothing is not a guard");
        assertEquals(List.of(), offenders.size() > 25 ? offenders.subList(0, 25) : offenders,
                offenders.size() + " shipped rules do not carry their authored Description. It is"
                        + " report content and no scenario fixture asserts on it, so a"
                        + " packaging regression here reaches users silently.");
    }


    /**
     * §9b — the {@code Outcome} invariants the report depends on.
     *
     * <p>
     * Two claims. First, every shipped rule has an {@code Outcome} with a non-empty {@code Message}
     * — currently true for all 14,451 entries, so this is a free invariant worth pinning. Second,
     * {@code Output_Variables} is <em>allowed</em> to be absent because
     * {@code OutputVariableDeriver} supplies it on load; that licence is only safe if the loader
     * actually ran the derivation, so every absent-or-empty entry must come back with an effective
     * list that is <b>installed (non-null)</b> — the arm is a deriver-installation guard. It does
     * not compare the list with {@code OutputVariableDeriver.derive(rule)}: the loader installs
     * exactly that call's answer, so the comparison was a tautology (T2 LOW-2 of
     * {@code PLAN-redundant-output-variables-sweep}). <em>What</em> each rule derives is pinned by
     * {@code EffectiveOutputVariablesDriftTest}'s golden file.
     * </p>
     *
     * <p>
     * ⚠ It does NOT assert the effective list is non-empty. Measured 2026-10-04 (the sweep's P0):
     * 505 absent-OV package entries — 126 rules — derive an EMPTY list, every one of the no-witness
     * shape ({@code not ds_exists("ADSL")}, {@code not var_exists("DOMAIN")}, {@code CDISC-AD0001},
     * {@code CDISC-CG0313-A}, …): every name the Check reads is under an absence predicate, D3
     * suppresses it, and the run-time projection falls back to the Check's leaf columns
     * ({@code RuleRunner.projectedOutputVariables}). An empty derived list is that shape, not a
     * defect; the count is printed, not pinned.
     * </p>
     */
    @Test
    void everyShippedOutcomeCarriesAMessageAndDerivesItsOutputVariables() throws IOException
    {
        List<String> noMessage = new ArrayList<>();
        List<String> notDerived = new ArrayList<>();
        int entries = 0;
        int derivedEntries = 0;
        int emptyEffective = 0;

        for (Path pkg : shippedPackages())
        {
            JsonNode raw = JSON.readTree(Files.readString(pkg)).get("rules");
            RulePackage loaded = RulePackageLoader.load(pkg);
            Map<String, Rule> rules = loaded.getRules();
            assertNotNull(rules, pkg.getFileName() + " has no rules");

            for (Map.Entry<String, Rule> e : rules.entrySet())
            {
                entries++;
                String coreId = e.getKey();
                Rule rule = e.getValue();
                String where = pkg.getFileName() + " / " + coreId;

                if (rule.getOutcome() == null || rule.getOutcome().getMessage() == null
                        || rule.getOutcome().getMessage().isBlank())
                {
                    noMessage.add(where);
                    continue;
                }

                // The RAW node decides whether the field was authored: by the time the loader is
                // done, the derived list has already been installed, so the loaded rule can no
                // longer tell "absent" from "derived".
                JsonNode outcome = raw.get(coreId) == null ? null : raw.get(coreId).get("Outcome");
                JsonNode ov = outcome == null ? null : outcome.get("Output_Variables");
                if (ov != null && ov.isArray() && !ov.isEmpty())
                {
                    continue;
                }
                derivedEntries++;
                List<String> effective = rule.getEffectiveOutputVariables();
                if (effective == null)
                {
                    notDerived.add(
                            where + ": Output_Variables is " + (ov == null ? "absent" : "empty")
                                    + " and the loader installed no effective list");
                }
                else if (effective.isEmpty())
                {
                    emptyEffective++;
                }
            }
        }
        System.out.println("[fidelity] absent-OV entries relying on derivation: " + derivedEntries
                + ", of which " + emptyEffective + " derive an empty list (no-witness shape)");

        assertTrue(entries >= MIN_RULES, "only " + entries + " rule entries read");
        assertEquals(List.of(), noMessage.size() > 25 ? noMessage.subList(0, 25) : noMessage,
                noMessage.size() + " shipped rules have no Outcome.Message. Every row of the"
                        + " Rules Report falls back to it, so a rule without one reports blank.");
        assertEquals(List.of(), notDerived.size() > 25 ? notDerived.subList(0, 25) : notDerived,
                notDerived.size() + " rules ship with Output_Variables absent or empty AND the"
                        + " loader installed no effective list. Omitting the field is only"
                        + " safe because OutputVariableDeriver fills it in at load; where it does"
                        + " not, the report loses the columns that say WHICH variables the finding"
                        + " is about.");
        assertTrue(derivedEntries >= MIN_DERIVED_OUTPUT_VARIABLE_ENTRIES,
                "only " + derivedEntries + " entries were classified as relying on derivation"
                        + " (expected at least " + MIN_DERIVED_OUTPUT_VARIABLE_ENTRIES
                        + "). The classification broke, so the derivation assertion above ran over"
                        + " nothing.");
    }


    /**
     * No rule the sweep stripped ({@code src/test/resources/ovsweep/manifest-roster.txt}) ships
     * with an empty effective {@code Output_Variables} in any package (T2 LOW-2 of
     * {@code PLAN-redundant-output-variables-sweep}).
     *
     * <p>
     * A stripped rule authored entries the engine derives, so its effective list cannot be empty
     * unless the derivation stopped producing them. The general arm above deliberately allows an
     * empty derived list (the no-witness shape); this one closes that licence for the population
     * whose report columns now come from the derivation alone. Every roster rule must also be found
     * shipped, so the arm cannot pass over a roster the corpus no longer carries.
     * </p>
     */
    @Test
    void noStrippedRuleShipsAnEmptyEffectiveList() throws IOException
    {
        Set<String> roster = new TreeSet<>();
        for (String line : Files.readAllLines(CorpusPaths.moduleDir()
                .resolve("src/test/resources/ovsweep").resolve(OvSweepCensus.ROSTER_FILE)))
        {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#"))
            {
                roster.add(t);
            }
        }
        // 2 631 -> 2 599 on 2026-10-07: 32 retired absent-column sisters left the roster.
        assertTrue(roster.size() >= 2590,
                "the stripped roster holds only " + roster.size() + " rules (committed: 2 631)");

        Set<String> found = new TreeSet<>();
        List<String> empty = new ArrayList<>();
        for (Path pkg : shippedPackages())
        {
            Map<String, Rule> rules = RulePackageLoader.load(pkg).getRules();
            assertNotNull(rules, pkg.getFileName() + " has no rules");
            for (Map.Entry<String, Rule> e : rules.entrySet())
            {
                if (!roster.contains(e.getKey()))
                {
                    continue;
                }
                found.add(e.getKey());
                List<String> effective = e.getValue().getEffectiveOutputVariables();
                if (effective == null || effective.isEmpty())
                {
                    empty.add(pkg.getFileName() + " / " + e.getKey() + ": " + effective);
                }
            }
        }
        Set<String> missing = new TreeSet<>(roster);
        missing.removeAll(found);
        assertEquals(Set.of(), missing, missing.size() + " stripped rule(s) are not shipped");
        assertEquals(List.of(), empty.size() > 25 ? empty.subList(0, 25) : empty,
                empty.size() + " package entries of stripped rules carry no effective"
                        + " Output_Variables: their report columns came from the authored list"
                        + " the sweep deleted, so the derivation must reproduce them.");
    }


    /** {@code Core.Id} &rarr; authored {@code Description}, from the YAML source. */
    private static Map<String, String> authoredDescriptions() throws IOException
    {
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(CHECKS))
        {
            for (Path yaml : files.filter(
                    p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yaml"))
                    .sorted().toList())
            {
                JsonNode node = YAML.readTree(Files.readString(yaml));
                JsonNode core = node.get("Core");
                if (core == null || core.get("Id") == null)
                {
                    continue;
                }
                JsonNode description = node.get("Description");
                out.put(core.get("Id").asText(),
                        description == null || description.isNull() ? null : description.asText());
            }
        }
        return out;
    }


    /** Every distinct shipped rule, first occurrence wins (ids are cross-package identical). */
    private static Map<String, Rule> shippedRules() throws IOException
    {
        Map<String, Rule> out = new LinkedHashMap<>();
        for (Path pkg : shippedPackages())
        {
            Map<String, Rule> rules = RulePackageLoader.load(pkg).getRules();
            if (rules != null)
            {
                rules.forEach(out::putIfAbsent);
            }
        }
        return out;
    }


    /** The generated packages, resolved through the manifest rather than by globbing. */
    private static List<Path> shippedPackages() throws IOException
    {
        List<Path> out = new ArrayList<>();
        JsonNode manifest = JSON
                .readTree(Files.readString(CorpusPaths.rules(RulePackageManifest.FILE_NAME)));
        for (JsonNode entry : manifest.get("packages"))
        {
            out.add(CorpusPaths.rules(entry.get("file").asText()));
        }
        return out;
    }
}
