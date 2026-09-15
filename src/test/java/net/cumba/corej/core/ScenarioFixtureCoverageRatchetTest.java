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
 * catches a packaging change: a rule whose released form drifts fails its scenario. That net covers
 * <b>3,020 of 3,808 rules — 79.3%</b>. For the other <b>788</b> a packaging change ships with
 * nothing firing at all.
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
     * P4); 764 since 2026-09-14, re-derived against 3,930 shipped and 3,166 covered; <b>761 since
     * later that day</b>, when CDISC-SEND-0221, -0318 and -0388 gained their first scenarios
     * (PLAN-column-type-conformance carried-forward) — 3,930 shipped, 3,169 covered. <b>Shrink
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
    private static final int MAX_UNCOVERED = 761;

    private static final Path SCENARIOS = Path
            .of("src/test/resources/net/cumba/corej/core/ruletestsuites");

    private static final String TEST_DIRECTIVE = "#test";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theSetOfShippedRulesWithNoScenarioFixtureOnlyShrinks() throws IOException
    {
        NavigableSet<String> shipped = shippedRuleIds();
        NavigableSet<String> covered = fixtureRuleIds();

        // ⚠ Corpus-size floor removed on extraction: it asserted the FULL internal corpus, which
        // this subset deliberately is not — the CORE family is not redistributed here, so the
        // internal population can never be reached. Enforced internally over the whole corpus.

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
