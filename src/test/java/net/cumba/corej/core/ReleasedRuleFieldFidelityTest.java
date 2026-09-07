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
import java.util.stream.Stream;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * §9a and §9b of {@code plans/PLAN-rules-corpus-build-integration.md} — the fields the
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
 * {@code OutputVariableDeriverTest} (in {@code corej-cdisc-core}) unit-tests the derivation
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

    /**
     * Entries whose {@code Outcome.Output_Variables} is absent or empty, measured 2026-09-02:
     * <b>493 absent + 6 empty = 499</b>, across 123 distinct rule ids.
     *
     * <p>
     * ⚠ A test written on "absent" alone finds 493 and its author then chases a phantom 6.
     * {@code OutputVariableDeriver.derive} treats absent and empty alike, so this test must too.
     * The number is an upper bound, not an equality: rules acquiring an explicit list is progress.
     * </p>
     */
    private static final int MAX_DERIVED_OUTPUT_VARIABLE_ENTRIES = 499;

    /**
     * ⚠ A FLOOR as well as a ceiling. The derivation arm below only asserts anything about entries
     * it actually classifies as absent-or-empty, so a change that broke the raw-node lookup would
     * classify none, assert nothing, and stay green — a bound with no floor is how a guard becomes
     * vacuous without anyone noticing.
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

        for (Map.Entry<String, Rule> e : shippedRules().entrySet())
        {
            String coreId = e.getKey();
            if (!authored.containsKey(coreId))
            {
                offenders.add(coreId + ": shipped but has no rules-src/checks source file");
                continue;
            }
            String want = authored.get(coreId);
            String got = e.getValue().getDescription();
            if (want == null ? got != null : !want.equals(got))
            {
                offenders.add(coreId + ": authored [" + want + "] but shipped [" + got + "]");
            }
        }

        // ⚠ Corpus-size floor removed on extraction: it asserted the FULL internal corpus,
        // which this subset deliberately is not. Enforced internally over the whole corpus.
        // The MIN_RULES constant and the population counter it compared against were removed
        // with it (2026-09-07) -- they had been left behind, unread, by that extraction.
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
     * {@code OutputVariableDeriver} supplies it on load; that licence is only safe if the
     * derivation actually produces something, so every absent-or-empty entry must come back with a
     * non-null effective list after {@code RulePackageLoader} has run.
     * </p>
     */
    @Test
    void everyShippedOutcomeCarriesAMessageAndDerivesItsOutputVariables() throws IOException
    {
        List<String> noMessage = new ArrayList<>();
        List<String> notDerived = new ArrayList<>();
        int derivedEntries = 0;

        for (Path pkg : shippedPackages())
        {
            JsonNode raw = JSON.readTree(Files.readString(pkg)).get("rules");
            RulePackage loaded = RulePackageLoader.load(pkg);
            Map<String, Rule> rules = loaded.getRules();
            assertNotNull(rules, pkg.getFileName() + " has no rules");

            for (Map.Entry<String, Rule> e : rules.entrySet())
            {
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
                if (rule.getEffectiveOutputVariables() == null)
                {
                    notDerived.add(
                            where + ": Output_Variables is " + (ov == null ? "absent" : "empty")
                                    + " and the loader derived nothing");
                }
            }
        }

        // ⚠ Corpus-size floor removed on extraction: it asserted the FULL internal corpus,
        // which this subset deliberately is not. Enforced internally over the whole corpus.
        // The MIN_RULES constant and the population counter it compared against were removed
        // with it (2026-09-07) -- they had been left behind, unread, by that extraction.
        assertEquals(List.of(), noMessage.size() > 25 ? noMessage.subList(0, 25) : noMessage,
                noMessage.size() + " shipped rules have no Outcome.Message. Every row of the"
                        + " Rules Report falls back to it, so a rule without one reports blank.");
        assertEquals(List.of(), notDerived.size() > 25 ? notDerived.subList(0, 25) : notDerived,
                notDerived.size() + " rules ship with Output_Variables absent or empty AND derive"
                        + " nothing on load. Omitting the field is only safe because"
                        + " OutputVariableDeriver fills it in; where it does not, the report loses"
                        + " the columns that say WHICH variables the finding is about.");
        assertTrue(derivedEntries >= MIN_DERIVED_OUTPUT_VARIABLE_ENTRIES,
                "only " + derivedEntries + " entries were classified as relying on derivation"
                        + " (expected at least " + MIN_DERIVED_OUTPUT_VARIABLE_ENTRIES
                        + "). The classification broke, so the derivation assertion above ran over"
                        + " nothing.");
        assertTrue(derivedEntries <= MAX_DERIVED_OUTPUT_VARIABLE_ENTRIES,
                derivedEntries + " entries rely on derivation; the recorded population is "
                        + MAX_DERIVED_OUTPUT_VARIABLE_ENTRIES + ". Growth is not wrong, but it"
                        + " widens a surface nothing else measures — re-measure and update"
                        + " deliberately.");
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
