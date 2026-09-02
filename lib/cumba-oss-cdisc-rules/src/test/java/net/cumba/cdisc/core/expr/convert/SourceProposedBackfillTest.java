package net.cumba.cdisc.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the side-car applier ({@code plans/PLAN-source-proposed-block.md} §6.2).
 *
 * <p>
 * ⭐ <b>The load-bearing property is MUTUAL non-interference</b>, and it is asserted in both
 * directions: {@link OriginalTextBackfill} must never touch a {@code Source_Proposed} block, and
 * this tool must never touch a {@code Source} block. The whole design rests on the two keys being
 * independently addressable across a one-character difference, so neither direction is assumed.
 * </p>
 */
class SourceProposedBackfillTest
{

    private static SequencedMap<String, String> fields(String... kv)
    {
        SequencedMap<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }


    private static List<String> rule(String... lines)
    {
        return List.of(lines);
    }


    @Test
    void splicesTheBlockImmediatelyBeforeSource()
    {
        List<String> out = SourceProposedBackfill.apply(rule("Core:", "  Id: \"FDA-SD9701\"",
                "Source:", "  Domains: \"ALL\"", "Standards:"),
                fields("Sheet_Issue", "#160", "Domains", "FA, SR"));
        assertEquals(List.of("Core:", "  Id: \"FDA-SD9701\"", "Source_Proposed:",
                "  Sheet_Issue: \"#160\"", "  Domains: \"FA, SR\"", "Source:", "  Domains: \"ALL\"",
                "Standards:"), out);
    }


    /**
     * A {@code missing-row} rule has no {@code Source} to sit in front of — the block goes before
     * {@code Standards}, which is the same slot the canonical order puts it in either way.
     */
    @Test
    void fallsBackToStandardsWhenTheRuleHasNoSourceYet()
    {
        List<String> out = SourceProposedBackfill.apply(
                rule("Core:", "  Id: \"FDA-SD9713\"", "Standards:", "- Organization: \"FDA\""),
                fields("Sheet_Issue", "#160", "Domains", "SV"));
        assertEquals(List.of("Core:", "  Id: \"FDA-SD9713\"", "Source_Proposed:",
                "  Sheet_Issue: \"#160\"", "  Domains: \"SV\"", "Standards:",
                "- Organization: \"FDA\""), out);
    }


    @Test
    void aSecondRunIsAByteForByteNoOp()
    {
        List<String> in = rule("Core:", "  Id: \"X\"", "Source:", "  Domains: \"ALL\"",
                "Standards:");
        SequencedMap<String, String> f = fields("Sheet_Issue", "#160", "Domains", "AE");
        List<String> once = SourceProposedBackfill.apply(in, f);
        assertEquals(once, SourceProposedBackfill.apply(once, f));
    }


    @Test
    void aStaleBlockIsReplacedNotDuplicated()
    {
        List<String> in = rule("Core:", "  Id: \"X\"", "Source_Proposed:", "  Domains: \"STALE\"",
                "Source:", "  Domains: \"ALL\"", "Standards:");
        List<String> out = SourceProposedBackfill.apply(in, fields("Domains", "AE"));
        assertEquals(1, out.stream().filter(l -> l.equals("Source_Proposed:")).count(), out + "");
        assertTrue(out.contains("  Domains: \"AE\""), out + "");
        assertFalse(out.contains("  Domains: \"STALE\""), out + "");
    }


    /** An empty field map retires the block — the adoption path, once a proposal is accepted. */
    @Test
    void anEmptyFieldMapRetiresTheBlockAndLeavesSourceAlone()
    {
        List<String> out = SourceProposedBackfill
                .apply(rule("Core:", "  Id: \"X\"", "Source_Proposed:", "  Domains: \"AE\"",
                        "Source:", "  Domains: \"ALL\"", "Standards:"), fields());
        assertEquals(List.of("Core:", "  Id: \"X\"", "Source:", "  Domains: \"ALL\"", "Standards:"),
                out);
    }

    // ------------------------------------------------------------------ mutual non-interference


    /**
     * ⛔⛔ This tool must never eat a {@code Source} block. {@code "Source:"} is a prefix of nothing
     * here because the match is on the exact line {@code "Source_Proposed:"} — a {@code startsWith}
     * would have deleted the verbatim transcription the proposal exists to sit beside.
     */
    @Test
    void stripNeverTouchesSourceOrAnyOtherKey()
    {
        List<String> in = rule("Core:", "  Id: \"X\"", "Source_Proposed:", "  Domains: \"AE\"",
                "Source:", "  Domains: \"ALL\"", "Standards:", "- Organization: \"FDA\"");
        assertEquals(List.of("Core:", "  Id: \"X\"", "Source:", "  Domains: \"ALL\"", "Standards:",
                "- Organization: \"FDA\""), SourceProposedBackfill.strip(in));
    }


    /**
     * ⭐ The other direction, and the reason the run order in {@code apply-sheet-proposals.sh} is
     * safe: applying one tool then the other, in EITHER order, leaves both blocks intact and yields
     * the same file.
     */
    @Test
    void theTwoToolsCommuteAndNeitherEatsTheOthersBlock()
    {
        List<String> bare = rule("Core:", "  Id: \"X\"", "Description: \"d\"", "Standards:",
                "- Organization: \"FDA\"");
        SequencedMap<String, String> source = fields("Domains", "ALL");
        SequencedMap<String, String> proposal = fields("Sheet_Issue", "#160", "Domains", "AE");

        List<String> sourceFirst = SourceProposedBackfill
                .apply(OriginalTextBackfill.backfill(bare, source), proposal);
        List<String> proposalFirst = OriginalTextBackfill
                .backfill(SourceProposedBackfill.apply(bare, proposal), source);

        assertEquals(sourceFirst, proposalFirst, "the two tools do not commute");
        assertTrue(sourceFirst.contains("Source:"), sourceFirst + "");
        assertTrue(sourceFirst.contains("Source_Proposed:"), sourceFirst + "");
        assertTrue(sourceFirst.indexOf("Source_Proposed:") < sourceFirst.indexOf("Source:"),
                "the proposal must precede the row it proposes to replace: " + sourceFirst);
    }

    // ------------------------------------------------------------------ the side-car reader


    @Test
    void readsTheSidecarWithSheetIssueFirstThenTheRowInOrder(@TempDir Path dir) throws IOException
    {
        Path sidecar = dir.resolve("sheet-proposals.yaml");
        Files.writeString(sidecar, """
                proposals:
                - Rule: "FDA-SD9701"
                  Case: split
                  Origin: generated
                  Sheet_Issue: "#160"
                  Row:
                    FDA_Validator_Rule_Message: "Missing value for --OBJ"
                    FDA_Validator_Rule_Description: "Objective (--OBJ) should be populated."
                    Domains: "FA, SR"
                """, StandardCharsets.UTF_8);
        List<SourceProposedBackfill.Proposal> read = SourceProposedBackfill.readSidecar(sidecar);
        assertEquals(1, read.size());
        assertEquals("FDA-SD9701", read.get(0).rule());
        assertEquals(
                List.of("Sheet_Issue", "FDA_Validator_Rule_Message",
                        "FDA_Validator_Rule_Description", "Domains"),
                List.copyOf(read.get(0).fields().keySet()));
    }


    /** A record with no {@code Sheet_Issue} — legal only on a split — emits no such key. */
    @Test
    void aSplitWithoutASheetIssueEmitsNoSheetIssueKey(@TempDir Path dir) throws IOException
    {
        Path sidecar = dir.resolve("sheet-proposals.yaml");
        Files.writeString(sidecar, """
                proposals:
                - Rule: "CDISC-CG0027-A"
                  Case: split
                  Origin: generated
                  Row:
                    Class: "ALL"
                """, StandardCharsets.UTF_8);
        SequencedMap<String, String> f = SourceProposedBackfill.readSidecar(sidecar).get(0)
                .fields();
        assertEquals(List.of("Class"), List.copyOf(f.keySet()));
    }


    @Test
    void processReportsMissingRulesRatherThanSilentlySkippingThem(@TempDir Path dir)
        throws IOException
    {
        Path checks = Files.createDirectories(dir.resolve("checks"));
        Files.writeString(checks.resolve("FDA-SD0001.yaml"),
                "Core:\n  Id: \"FDA-SD0001\"\nStandards:\n", StandardCharsets.UTF_8);
        var result = SourceProposedBackfill.process(
                List.of(new SourceProposedBackfill.Proposal("FDA-SD0001", fields("Domains", "AE")),
                        new SourceProposedBackfill.Proposal("FDA-NOPE", fields("Domains", "AE"))),
                checks, false);
        assertEquals(2, result.proposals());
        assertEquals(1, result.changed());
        assertEquals(List.of("FDA-NOPE"), result.missingRules());
        assertTrue(result.report(false).contains("missing-rule : [FDA-NOPE]"),
                result.report(false));
    }


    @Test
    void aDryRunReportsTheChangeAndWritesNothing(@TempDir Path dir) throws IOException
    {
        Path checks = Files.createDirectories(dir.resolve("checks"));
        Path file = checks.resolve("FDA-SD0001.yaml");
        String before = "Core:\n  Id: \"FDA-SD0001\"\nStandards:\n";
        Files.writeString(file, before, StandardCharsets.UTF_8);
        var result = SourceProposedBackfill.process(
                List.of(new SourceProposedBackfill.Proposal("FDA-SD0001", fields("Domains", "AE"))),
                checks, true);
        assertEquals(1, result.changed());
        assertEquals(before, Files.readString(file), "a dry run wrote to the corpus");
    }
}
