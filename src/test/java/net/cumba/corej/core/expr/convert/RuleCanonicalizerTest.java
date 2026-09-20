package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.convert.RuleCanonicalizer.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleCanonicalizerTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static String canon(String text, List<String> warnings)
    {
        return RuleCanonicalizer.canonicalize(text, warnings, new LinkedHashSet<>());
    }

    // ---- Plan C §3.1 obligation 3: the redundant-Severity strip ----------

    private static final String SEV_BASE = """
            Core:
              Id: "T-0001"
            Description: "Raise an error when something is wrong."
            Check:
              expression: >-
                1 == 1
            """;

    /**
     * ⚠⚠ The load-bearing obligation of the three. The loader defaulting an absent field and the
     * writer never emitting one still leave a redundant {@code Severity: "Error"} reachable through
     * the rule-editor REST round-trip — and {@code RuleSrcExpressionWriterCorpusTest}'s
     * byte-exactness would then be enforcing two different spellings of the same rule.
     */
    @org.junit.jupiter.api.Test
    void severityErrorIsStrippedNotRejected() throws IOException
    {
        List<String> warnings = new ArrayList<>();
        String out = canon(SEV_BASE.replace("Description:", "Severity: \"Error\"\nDescription:"),
                warnings);
        assertFalse(topKeys(out).contains("Severity"),
                "a Severity that canonicalises to Error is DELETED, not merely reordered");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("stripped redundant Severity")),
                "and the strip is reported, not silent");
    }


    @org.junit.jupiter.api.Test
    void severityErrorIsStrippedCaseInsensitively() throws IOException
    {
        for (String spelling : List.of("Error", "error", "ERROR", " Error "))
        {
            String out = canon(SEV_BASE.replace("Description:",
                    "Severity: \"" + spelling + "\"\nDescription:"), new ArrayList<>());
            assertFalse(topKeys(out).contains("Severity"),
                    "'" + spelling + "' is the default in a different casing, so it strips too");
        }
    }


    @org.junit.jupiter.api.Test
    void aNonDefaultSeverityIsKeptAndPlacedThird() throws IOException
    {
        for (String value : List.of("Reject", "Warning", "Info"))
        {
            String out = canon(
                    SEV_BASE.replace("Description:", "Severity: \"" + value + "\"\nDescription:"),
                    new ArrayList<>());
            List<String> keys = topKeys(out);
            assertTrue(keys.contains("Severity"), value + " carries information and is kept");
            assertEquals(List.of("Core", "Description", "Severity"), keys.subList(0, 3),
                    "Severity is the third top-level key, right after the opener it agrees with");
        }
    }


    private static List<String> topKeys(String yaml) throws IOException
    {
        JsonNode n = YAML.readTree(yaml);
        List<String> ks = new ArrayList<>();
        n.fieldNames().forEachRemaining(ks::add);
        return ks;
    }

    // ---- top-level reorder ------------------------------------------------


    @Test
    void reorderPutsSourceSecondToLastBeforeStandardsAndOrdersTopLevel() throws IOException
    {
        // Deliberately scrambled top-level order.
        String in = """
                Standards:
                - Organization: "FDA"
                Source:
                  Domains: "ALL"
                Outcome:
                  Message: "m"
                Check:
                  all:
                  - name: "X"
                Scope:
                  Domains:
                    Include:
                    - "DM"
                Description: "d"
                Sensitivity: "Record"
                Executability: "Fully Executable"
                Core:
                  Id: "CORE-1"
                """;
        String out = canon(in, new ArrayList<>());
        assertEquals(List.of("Core", "Description", "Sensitivity", "Executability", "Scope",
                "Check", "Outcome", "Source", "Standards"), topKeys(out));
        // Source is immediately before Standards.
        assertTrue(out.indexOf("\nSource:") < out.indexOf("\nStandards:"));
        assertEquals(out.indexOf("\nStandards:"),
                out.indexOf("\nSource:") + "\nSource:\n  Domains: \"ALL\"".length());
    }


    /**
     * {@code Requirements} is a listed key and lands between {@code Scope} and
     * {@code Match_Datasets} — not relocated to just before {@code Source} as an unknown one
     * ({@code plans/done/PLAN-scope-requirements-split.md} phase 3 item 1).
     *
     * <p>
     * A separate fixture rather than an edit to
     * {@link #reorderPutsSourceSecondToLastBeforeStandardsAndOrdersTopLevel()}: that one pins the
     * ordering of a rule carrying <em>no</em> {@code Requirements}, which stays true and is the
     * shape of every corpus file until phase 4. The failure this guards against is silent — no
     * Maven gate runs the canonicalizer over the real corpus, so a missing key would surface only
     * as a warning at the next {@code scripts/backfill-rules.sh} run.
     * </p>
     */
    @Test
    void requirementsIsCanonicalAndSortsBetweenScopeAndMatchDatasets() throws IOException
    {
        String in = """
                Core:
                  Id: "CORE-1"
                Match_Datasets:
                - Name: "DM"
                  Keys:
                  - "USUBJID"
                Requirements:
                  Variables:
                    Any:
                    - "TEENRL"
                    - "TEDUR"
                Scope:
                  Domains:
                    Include:
                    - "TE"
                Source:
                  Domains: "ALL"
                Standards:
                - Organization: "FDA"
                """;
        List<String> warnings = new ArrayList<>();
        String out = canon(in, warnings);
        assertEquals(
                List.of("Core", "Scope", "Requirements", "Match_Datasets", "Source", "Standards"),
                topKeys(out));
        assertEquals(List.of(), warnings, "a listed key must not warn as drift");
    }


    @Test
    void unknownTopLevelKeyPlacedBeforeSourceAndWarned() throws IOException
    {
        String in = """
                Core:
                  Id: "CORE-1"
                Zebra: "drift"
                Source:
                  Domains: "ALL"
                Standards:
                - Organization: "FDA"
                """;
        List<String> warnings = new ArrayList<>();
        Set<String> unknown = new LinkedHashSet<>();
        String out = RuleCanonicalizer.canonicalize(in, warnings, unknown);
        assertEquals(List.of("Core", "Zebra", "Source", "Standards"), topKeys(out));
        assertTrue(unknown.contains("Zebra"));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Zebra")), warnings.toString());
    }


    /**
     * {@code Source_Proposed} is a KNOWN top-level key and canonicalises immediately before the
     * verbatim {@code Source} it proposes to replace
     * ({@code plans/done/PLAN-source-proposed-block.md} §2.1, phase 1 item 1).
     *
     * <p>
     * The input deliberately carries the block <em>after</em> {@code Standards} — the position a
     * hand-edit or a naive splice would leave it in — so the assertion is that canonicalisation
     * <em>moves</em> it, not merely that it survives.
     * </p>
     */
    @Test
    void sourceProposedIsKnownAndCanonicalisesImmediatelyBeforeSource() throws IOException
    {
        String in = """
                Core:
                  Id: "CORE-1"
                Source:
                  Domains: "ALL"
                Standards:
                - Organization: "FDA"
                Source_Proposed:
                  Sheet_Issue: "#160"
                  Domains: "AE"
                """;
        List<String> warnings = new ArrayList<>();
        Set<String> unknown = new LinkedHashSet<>();
        String out = RuleCanonicalizer.canonicalize(in, warnings, unknown);
        assertEquals(List.of("Core", "Source_Proposed", "Source", "Standards"), topKeys(out));
        assertFalse(unknown.contains("Source_Proposed"), unknown.toString());
        assertTrue(warnings.stream().noneMatch(w -> w.contains("Source_Proposed")),
                warnings.toString());
    }


    /**
     * ⭐ The migration property §2.1 item 3 rests on: a file seeded <em>before</em> this key entered
     * {@code TOP_ORDER} canonicalises byte-for-byte where one seeded after it lands, because the
     * unknown-key block is emitted at the point the {@code TOP_ORDER} loop reaches {@code "Source"}
     * — the same slot.
     *
     * <p>
     * ⚠ That equivalence is <b>conditional</b>, and this test pins the condition rather than the
     * happy case: it holds while every OTHER unknown key sorts <em>after</em> {@code
     * Source_Proposed} (or there is none). {@code CanonicalTopOrderCoverageTest} asserts the corpus
     * carries no other unknown top-level key at all, which is what makes the condition true in
     * practice. A hypothetical {@code Alpha} would sort before it and the two orders would differ —
     * asserted here so the limit is evidence, not a comment.
     * </p>
     */
    @Test
    void sourceProposedOutranksAnUnknownKeyThatSortsAfterIt() throws IOException
    {
        String in = """
                Core:
                  Id: "CORE-1"
                Zebra: "drift"
                Source_Proposed:
                  Sheet_Issue: "#160"
                Source:
                  Domains: "ALL"
                Standards:
                - Organization: "FDA"
                """;
        List<String> warnings = new ArrayList<>();
        Set<String> unknown = new LinkedHashSet<>();
        String out = RuleCanonicalizer.canonicalize(in, warnings, unknown);
        assertEquals(List.of("Core", "Source_Proposed", "Zebra", "Source", "Standards"),
                topKeys(out));
        assertEquals(Set.of("Zebra"), unknown);

        // ⚠ The LIMIT this test's name refers to is NOT demonstrated here, and saying so is the
        // point. Once "Source_Proposed" is in TOP_ORDER every unknown key lands after it, so
        // "Alpha" and "Zebra" behave identically — the assertion below is a second instance of the
        // post-migration order, not a counter-example. The pre-migration order it contrasts with
        // (Core, Alpha, Source_Proposed, Source, Standards, from the SORTED unknown-key block) is
        // unreachable without un-registering the key, which no test may do. The limit is real; the
        // guarantee that nothing depends on it is CanonicalTopOrderCoverageTest asserting the
        // corpus carries no unknown top-level key at all.
        String alpha = in.replace("Zebra: \"drift\"", "Alpha: \"drift\"");
        List<String> w2 = new ArrayList<>();
        Set<String> u2 = new LinkedHashSet<>();
        assertEquals(List.of("Core", "Source_Proposed", "Alpha", "Source", "Standards"),
                topKeys(RuleCanonicalizer.canonicalize(alpha, w2, u2)));
    }


    @Test
    void unknownKeyOutputVariablesSortedBeforeSourceButWildcardFieldsAreKnown() throws IOException
    {
        String in = """
                Core:
                  Id: "CORE-1"
                wildcardPairCatalogue: "cat"
                wildcardExclude:
                - "X"
                wildcards:
                - "Y"
                Output_Variables:
                - "Z"
                Standards:
                - Organization: "FDA"
                """;
        List<String> warnings = new ArrayList<>();
        Set<String> unknown = new LinkedHashSet<>();
        String out = RuleCanonicalizer.canonicalize(in, warnings, unknown);
        // The wildcard* directives sort into their TOP_ORDER block; only Output_Variables is
        // unknown (before the Source position, i.e. just before Standards here).
        assertEquals(List.of("Core", "wildcards", "wildcardExclude", "wildcardPairCatalogue",
                "Output_Variables", "Standards"), topKeys(out));
        assertEquals(Set.of("Output_Variables"), unknown);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Output_Variables")));
        assertFalse(warnings.stream().anyMatch(w -> w.contains("wildcardExclude")),
                "known wildcard directive not warned");
    }


    @Test
    void operationsItemKeysOrderedIdOperatorThenAlphabetical() throws IOException
    {
        // Authored fully alphabetically: domain, id, name, operator -> id, operator, domain, name.
        String in = """
                Core:
                  Id: "CORE-1"
                Bindings:
                - expression: 'distinct(ARM, domain="TA")'
                  name: "$ta_arm"
                - expression: 'codelist_terms(codelists=[B, A])'
                  name: "$ct"
                Standards: []
                """;
        String out = canon(in, new ArrayList<>());
        JsonNode ops = YAML.readTree(out).get("Bindings");
        List<String> first = new ArrayList<>();
        ops.get(0).fieldNames().forEachRemaining(first::add);
        assertEquals(List.of("name", "expression"), first);
        List<String> second = new ArrayList<>();
        ops.get(1).fieldNames().forEachRemaining(second::add);
        assertEquals(List.of("name", "expression"), second);
        // Array element order preserved (item 0 then item 1).
        assertEquals("$ta_arm", ops.get(0).get("name").asText());
        assertEquals("$ct", ops.get(1).get("name").asText());
    }

    // ---- style normalization ----------------------------------------------


    @Test
    void unquotedInputBecomesDoubleQuoted() throws IOException
    {
        String in = """
                Core:
                  Id: CORE-1
                Description: hello world
                Standards:
                - Organization: PMDA
                """;
        String out = canon(in, new ArrayList<>());
        assertTrue(out.contains("Description: \"hello world\""), out);
        assertTrue(out.contains("- Organization: \"PMDA\""), out);
    }


    @Test
    void commentsAreStrippedAndDetected()
    {
        String in = """
                # a leading rationale comment
                Core:
                  Id: "CORE-1"  # trailing comment
                Description: "d"
                Standards:
                - Organization: "FDA"
                """;
        assertTrue(RuleCanonicalizer.hasComment(in));
        String out = canon(in, new ArrayList<>());
        assertFalse(out.contains("rationale comment"), out);
        assertFalse(out.contains("trailing comment"), out);
    }


    @Test
    void hashInsideQuotedStringIsNotAComment()
    {
        String in = """
                Core:
                  Id: "CORE-1"
                Description: "value with a # inside quotes"
                Standards: []
                """;
        assertFalse(RuleCanonicalizer.hasComment(in), "quoted # is not a comment");
    }

    // ---- structure preservation -------------------------------------------


    @Test
    void arrayElementOrderIsPreserved() throws IOException
    {
        String in = """
                Core:
                  Id: "CORE-1"
                Check:
                  all:
                  - name: "B"
                    operator: "equal_to"
                    value: 1
                  - name: "A"
                    operator: "equal_to"
                    value: 2
                Standards:
                - Organization: "FDA"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                - Organization: "CDISC"
                """;
        String out = canon(in, new ArrayList<>());
        JsonNode root = YAML.readTree(out);
        // Check.all order B then A preserved.
        assertEquals("B", root.get("Check").get("all").get(0).get("name").asText());
        assertEquals("A", root.get("Check").get("all").get(1).get("name").asText());
        // Standards list order FDA then CDISC preserved.
        assertEquals("FDA", root.get("Standards").get(0).get("Organization").asText());
        assertEquals("CDISC", root.get("Standards").get(1).get("Organization").asText());
    }


    @Test
    void nestedObjectKeyOrderIsPreserved() throws IOException
    {
        // Outcome authored as Message then Output_Variables must stay in that order.
        String in = """
                Core:
                  Id: "CORE-1"
                Outcome:
                  Message: "m"
                  Output_Variables:
                  - "X"
                Standards: []
                """;
        String out = canon(in, new ArrayList<>());
        List<String> outcomeKeys = new ArrayList<>();
        YAML.readTree(out).get("Outcome").fieldNames().forEachRemaining(outcomeKeys::add);
        assertEquals(List.of("Message", "Output_Variables"), outcomeKeys);
    }


    @Test
    void roundTripDataEqualityHoldsAcrossReorderAndRequote() throws IOException
    {
        String in = """
                Standards:
                - Organization: PMDA
                  Standards:
                  - Name: SDTMIG
                    Version: '3.2'
                Source:
                  Domains: ALL
                Core:
                  Id: CORE-1
                Description: d
                """;
        String out = canon(in, new ArrayList<>());
        assertTrue(RuleCanonicalizer.dataEqual(YAML.readTree(in), YAML.readTree(out)),
                "canonical output is data-equal to the input");
    }

    // ---- straggler drop ---------------------------------------------------


    @Test
    void stragglerLegacyKeysDroppedAndWarned() throws IOException
    {
        String in = """
                Core:
                  Id: "CORE-1"
                Original_Description: "STALE"
                Original_Condition: "STALE"
                Outcome:
                  Message: "m"
                  Original_Message: "STALE"
                Standards: []
                """;
        List<String> warnings = new ArrayList<>();
        String out = canon(in, warnings);
        assertFalse(out.contains("Original_"), out);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Original_Description")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Outcome.Original_Message")));
        // Outcome.Message survives.
        assertEquals("m", YAML.readTree(out).get("Outcome").get("Message").asText());
    }

    // ---- run(): modes, idempotency, check ---------------------------------


    private static Path writeCheck(Path checks, String id, String body) throws IOException
    {
        Path fam = checks.resolve("CORE");
        Files.createDirectories(fam);
        Path f = fam.resolve(id + ".yaml");
        Files.writeString(f, body, StandardCharsets.UTF_8);
        return f;
    }


    @Test
    void checkModeDetectsNonCanonicalWithoutWriting(@TempDir Path dir) throws IOException
    {
        Path checks = dir.resolve("checks");
        String scrambled = """
                Standards: []
                Core:
                  Id: "CORE-1"
                Description: "d"
                """;
        Path f = writeCheck(checks, "CORE-1", scrambled);

        Result r = RuleCanonicalizer.run(checks, true);
        assertEquals(1, r.files());
        assertEquals(1, r.changed(), "detected as non-canonical");
        assertEquals(scrambled, Files.readString(f, StandardCharsets.UTF_8),
                "check mode does not write");
    }


    @Test
    void writeThenReCheckIsClean(@TempDir Path dir) throws IOException
    {
        Path checks = dir.resolve("checks");
        Path f = writeCheck(checks, "CORE-1", """
                Standards: []
                Description: "d"
                Core:
                  Id: "CORE-1"
                """);
        Result first = RuleCanonicalizer.run(checks, false);
        assertEquals(1, first.changed());
        String once = Files.readString(f, StandardCharsets.UTF_8);
        assertEquals("Core", topKeys(once).get(0), "reordered on write");

        // A re-check finds nothing to change (idempotent + canonical).
        Result recheck = RuleCanonicalizer.run(checks, true);
        assertEquals(0, recheck.changed());

        // A second write pass is byte-identical.
        RuleCanonicalizer.run(checks, false);
        assertEquals(once, Files.readString(f, StandardCharsets.UTF_8),
                "second pass byte-identical");
    }


    @Test
    void runCountsCommentsAndAlreadyCanonicalFile(@TempDir Path dir) throws IOException
    {
        Path checks = dir.resolve("checks");
        writeCheck(checks, "CORE-1", """
                # rationale
                Core:
                  Id: "CORE-1"
                Standards: []
                """);
        // Already-canonical file (Core then Standards, quoted, trailing newline).
        writeCheck(checks, "CORE-2", "Core:\n  Id: \"CORE-2\"\nStandards: []\n");

        Result r = RuleCanonicalizer.run(checks, true);
        assertEquals(2, r.files());
        assertEquals(1, r.comments(), "one file had a comment");
        assertEquals(1, r.changed(), "only the commented/uncanonical file differs");
    }

    // ---- D27: expression-form Check block layout --------------------------


    @Test
    void wholeFileModePreservesTheD27ExpressionCheckBlock()
    {
        // The flip-day wiring: whole-file canonicalisation must route an expression Check
        // through renderExpressionCheckBlock, not Jackson's double-quoted writer — otherwise a
        // canonicalizer run over the flipped corpus undoes the D27 layout.
        String in = """
                Core:
                  Id: "CDISC-AD0001"
                Description: "ADSL dataset does not exist"
                Check:
                  expression: >-
                    not ds_exists("ADSL")
                Outcome:
                  Message: "ADSL dataset does not exist"
                """;
        List<String> warnings = new ArrayList<>();
        String out = canon(in, warnings);
        assertTrue(out.contains("Check:\n  expression: >-\n    not ds_exists(\"ADSL\")\n"),
                "the D27 folded block must survive whole-file canonicalisation, got:\n" + out);
        assertEquals(out, canon(out, new ArrayList<>()), "whole-file mode must stay idempotent");
    }


    @Test
    void renderShortExpressionIsAThreeLineFoldedBlock() throws IOException
    {
        String rendered = RuleCanonicalizer.renderExpressionCheckBlock("ds_not_exists(\"ADSL\")");
        assertEquals("Check:\n  expression: >-\n    ds_not_exists(\"ADSL\")\n", rendered);
        assertEquals("ds_not_exists(\"ADSL\")",
                YAML.readTree(rendered).path("Check").path("expression").asText(),
                "YAML must reconstruct the expression exactly");
    }


    @Test
    void renderWrapsAtTokenBoundariesWithinTheWrapColumnAndFoldsBack() throws IOException
    {
        String expression = ("var_exists(\"TRT02P\") and not var_exists(\"TRT01P\")"
                + " or var_exists(\"TRT03P\") and not var_exists(\"TRT02P\")").repeat(3)
                        .replace(")v", ") or v");
        String rendered = RuleCanonicalizer.renderExpressionCheckBlock(expression);
        for (String line : rendered.split("\n", -1))
        {
            assertTrue(line.length() <= RuleCanonicalizer.EXPRESSION_WRAP_COLUMN,
                    "line exceeds the wrap column: <" + line + ">");
        }
        assertTrue(rendered.split("\n", -1).length > 3, "a long expression must wrap");
        assertEquals(expression, YAML.readTree(rendered).path("Check").path("expression").asText(),
                "folding must reconstruct the wrapped expression exactly");
    }


    @Test
    void renderNeverBreaksInsideQuotedStringsOrBacktickedOperands() throws IOException
    {
        // A quoted literal and a backticked operand, each with internal spaces, positioned so a
        // naive breaker would split them at the wrap column.
        String filler = "AAAAAAAAAA == \"" + "B ".repeat(35) + "B\" and CCC == `DD "
                + "E ".repeat(30) + "F` and not empty(GGGG)";
        String rendered = RuleCanonicalizer.renderExpressionCheckBlock(filler);
        for (String line : rendered.split("\n", -1))
        {
            long quotes = line.chars().filter(ch -> ch == '"').count();
            long ticks = line.chars().filter(ch -> ch == '`').count();
            assertEquals(0, quotes % 2, "a line split a double-quoted literal: <" + line + ">");
            assertEquals(0, ticks % 2, "a line split a backticked operand: <" + line + ">");
        }
        assertEquals(filler, YAML.readTree(rendered).path("Check").path("expression").asText());
    }


    @Test
    void renderEmitsAnOverLongTokenUnbrokenRatherThanSplitMidToken() throws IOException
    {
        String longToken = "X =~ /" + "a".repeat(150) + "/ and not empty(Y)";
        String rendered = RuleCanonicalizer.renderExpressionCheckBlock(longToken);
        assertTrue(rendered.contains("a".repeat(150)), "the over-long token must stay whole");
        assertEquals(longToken, YAML.readTree(rendered).path("Check").path("expression").asText());
    }


    @Test
    void renderRejectsTextTheLayoutCannotCarryCanonically()
    {
        assertThrows(IllegalArgumentException.class,
                () -> RuleCanonicalizer.renderExpressionCheckBlock(""));
        assertThrows(IllegalArgumentException.class,
                () -> RuleCanonicalizer.renderExpressionCheckBlock("  "));
        assertThrows(IllegalArgumentException.class,
                () -> RuleCanonicalizer.renderExpressionCheckBlock("a\nb"));
        assertThrows(IllegalArgumentException.class,
                () -> RuleCanonicalizer.renderExpressionCheckBlock("a\tb"));
        assertThrows(IllegalArgumentException.class,
                () -> RuleCanonicalizer.renderExpressionCheckBlock(" leading"));
        assertThrows(IllegalArgumentException.class,
                () -> RuleCanonicalizer.renderExpressionCheckBlock("trailing "));
    }


    @Test
    void wrapExpressionPacksGreedilyAndNeverBreaksBesideASecondSpace()
    {
        // Double spaces inside a (mis-)detected span must never become break points: a break
        // beside another space would make a folded line start or end with whitespace, which YAML
        // folding cannot undo.
        String text = "aaa \"bb  cc\" ddd eee";
        for (String line : RuleCanonicalizer.wrapExpression(text, 8))
        {
            assertFalse(line.startsWith(" ") || line.endsWith(" "),
                    "a wrapped line may never start or end with a space: <" + line + ">");
        }
        assertEquals(java.util.List.of("aaa", "\"bb  cc\"", "ddd eee"),
                RuleCanonicalizer.wrapExpression(text, 8));
        assertEquals(java.util.List.of(text), RuleCanonicalizer.wrapExpression(text, 200),
                "text within the width stays one line");
    }

    // ---- Output_Variables quoting (Fix #354, plan A §5.1) ------------------


    /**
     * In YAML an unquoted {@code - !X} is a <em>tag</em>, so the writer MUST emit every
     * {@code Output_Variables} entry double-quoted. Asserts the emitted TEXT — the value would
     * round-trip even if the quoting were dropped, right up until some other tool's parser sees the
     * tag — and that reloading yields the same token.
     */
    @Test
    void outputVariableExclusionTokenIsEmittedDoubleQuotedAndRoundTrips() throws IOException
    {
        String in = """
                Core:
                  Id: "CORE-1"
                Outcome:
                  Message: "m"
                  Output_Variables:
                  - "AETERM"
                  - "!AEDECOD"
                  - "--SEQ"
                  - "!--DTC"
                  - "!$cnt"
                  - "!DM.ARM"
                Source:
                  Domains: "ALL"
                Standards:
                - Organization: "FDA"
                """;
        String out = canon(in, new ArrayList<>());
        assertTrue(out.contains("\n  - \"AETERM\"\n"), out);
        assertTrue(out.contains("\n  - \"!AEDECOD\"\n"), out);
        assertTrue(out.contains("\n  - \"--SEQ\"\n"), out);
        assertTrue(out.contains("\n  - \"!--DTC\"\n"), out);
        assertTrue(out.contains("\n  - \"!$cnt\"\n"), out);
        assertTrue(out.contains("\n  - \"!DM.ARM\"\n"), out);
        assertFalse(out.contains("- !"), "an unquoted marker would be a YAML tag: " + out);
        JsonNode reloaded = YAML.readTree(out).get("Outcome").get("Output_Variables");
        List<String> values = new ArrayList<>();
        reloaded.forEach(n -> values.add(n.asText()));
        assertEquals(List.of("AETERM", "!AEDECOD", "--SEQ", "!--DTC", "!$cnt", "!DM.ARM"), values);
        // and a second pass is a no-op (idempotent)
        assertEquals(out, canon(out, new ArrayList<>()));
    }

    // ---- Plan C §3.3: the level-keyed Check block ------------------------

    private static final String LEVELLED = """
            Core:
              Id: "T-0002"
            Description: "Raise an error when start is after end."
            Check:
              INFO:
                expression: "date(latest_possible(RFSTDTC)) > earliest_possible(RFENDTC)"
                Message: "start may be after end"
              ERROR:
                expression: "date(RFSTDTC) > RFENDTC"
            Outcome:
              Message: "start is after end"
            """;

    @Test
    void aLevelKeyedCheckIsRenderedAsOneFoldedScalarPerLevelStrictestFirst()
    {
        String out = canon(LEVELLED, new ArrayList<>());
        assertTrue(out.contains("""
                Check:
                  ERROR:
                    expression: >-
                      date(RFSTDTC) > RFENDTC
                  INFO:
                    expression: >-
                      date(latest_possible(RFSTDTC)) > earliest_possible(RFENDTC)
                    Message: "start may be after end"
                """), "rendered block was:\n" + out);
    }


    @Test
    void theLevelKeyedLayoutIsIdempotentAndDataInert() throws IOException
    {
        String once = canon(LEVELLED, new ArrayList<>());
        assertEquals(once, canon(once, new ArrayList<>()), "a second run makes no change");
        JsonNode before = YAML.readTree(LEVELLED);
        JsonNode after = YAML.readTree(once);
        assertEquals(before.get("Check").get("ERROR").get("expression").asText(),
                after.get("Check").get("ERROR").get("expression").asText());
        assertEquals(before.get("Check").get("INFO").get("Message").asText(),
                after.get("Check").get("INFO").get("Message").asText());
    }


    @Test
    void aPlainCheckIsUnaffectedByTheLevelBranch()
    {
        // The whole shipped corpus takes this path; the level branch must not reach it.
        String plain = """
                Core:
                  Id: "T-0003"
                Check:
                  expression: "not empty(AETERM)"
                """;
        assertTrue(canon(plain, new ArrayList<>()).contains("""
                Check:
                  expression: >-
                    not empty(AETERM)
                """));
    }


    @Test
    void aLegacyShapedLevelHasNoCanonicalBlockAndIsLeftToJackson() throws IOException
    {
        JsonNode check = YAML.readTree("""
                ERROR:
                  all:
                  - name: "AETERM"
                    operator: "empty"
                """.getBytes(StandardCharsets.UTF_8));
        assertNull(RuleCanonicalizer.renderLevelCheckBlockOrNull(check));
    }


    @Test
    void aPlainCheckNodeIsNotALevelMap() throws IOException
    {
        assertNull(RuleCanonicalizer
                .renderLevelCheckBlockOrNull(YAML.readTree("expression: \"a > b\"\n")));
        assertNull(RuleCanonicalizer.renderLevelCheckBlockOrNull(null));
    }
}
