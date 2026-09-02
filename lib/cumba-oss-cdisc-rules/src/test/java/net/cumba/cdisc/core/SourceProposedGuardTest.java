package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The corpus and the register side-car must agree, and the taxonomy of
 * {@code plans/PLAN-source-proposed-block.md} §0.1 must hold over every rule.
 *
 * <p>
 * ⭐ <b>Reads YAML only — never a markdown table.</b> That is what collapsed four designed guards
 * and two ratchets into one class: none of the register's six table shapes, its pipe-embedding rows
 * ({@code #8}, {@code #96}, {@code #112}), its header-less orphan rows ({@code #103}, {@code #107},
 * {@code #108}) or its right-to-left status tokens can break a scan that never parses them. The
 * register is read as a {@code #N} <b>map</b> and nothing more.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>Both floors are mandatory and both are equalities on the seeded cases.</b> {@code Fix #191}
 * shipped a ratchet reporting <i>"0 declaring rules"</i> on a corpus with 11 and stayed green
 * through an entire migration. When a population moves, Phase 0's manifest is re-derived — the
 * number is not nudged. This build has already collected on that: ruling J moved {@code CORRECTION}
 * 29 → 22 and the equality is what said so.
 * </p>
 *
 * <p>
 * ⭐ Neither test needs a workbook, so unlike {@code ShippedSourceBlockMatchesWorkbookTest} and
 * {@code ShippedSendSourceBlockMatchesWorkbookTest} neither can degrade into an {@code assumeTrue}
 * skip when {@code /data/cdisc-docs} is unmounted.
 * </p>
 */
class SourceProposedGuardTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final Path SRC_CHECKS = Path.of("rules-src", "checks");

    private static final Path SIDECAR = Path.of("documentation", "rule-review-results",
            "sheet-proposals.yaml");

    private static final Path REGISTER_DIR = Path.of("documentation", "rule-review-results");

    /**
     * The seeded populations, re-derived by Phase 0 and carried here as EQUALITIES.
     * {@code documentation/rule-inventory/source-proposed-manifest.tsv} is the list behind them.
     * <p>
     * ⚑ Moved 2026-08-27 by the guard-hoist review: SPLIT 76 &rarr; 78 and MISSING_ROW 9 &rarr; 13,
     * from six minted presence rules. Two are minted <em>siblings</em> ({@code PMDA-AD0647-A},
     * {@code PMDA-AD0649-A}) carrying their parents' published rows &mdash; PMDA's own
     * {@code Source.Description} states the presence clause, so nothing is proposed and they land
     * in SPLIT. Four are genuinely unpublished ({@code CDISC-AD9702}, {@code CDISC-AD9703},
     * {@code FDA-SD9714}, {@code PMDA-SD9714}) and land in MISSING_ROW behind sheet issues
     * {@code #164}, {@code #166}, {@code #168}, {@code #169}. Re-derived by
     * {@code scripts/gen-source-proposed-manifest.py}, not nudged.
     * <p>
     * ⚑ Moved again 2026-08-27 by {@code Fix #368}: MISSING_ROW 13 &rarr; 14, from the single
     * minted {@code CDISC-AD9704} &mdash; the CDISC twin of {@code PMDA-AD0047}, behind sheet issue
     * {@code #170}. CDISC publishes no generic Required-variable ADaM row in any of its 1 963
     * catalogue rows, so there is nothing to cite and the rule is genuinely {@code missing-row}.
     * Re-derived by the same script; the whole census moved by exactly this one rule (3 158 / 80 /
     * 25 / 14 / 536 = 3 813).
     */
    private static final Map<SourceProposedCase, Integer> FLOORS = Map.of(SourceProposedCase.SPLIT,
            80, SourceProposedCase.MISSING_ROW, 14, SourceProposedCase.CORRECTION, 25);

    private static final int SIDECAR_FLOOR = 110;

    /**
     * The register's distinct-entry count; ≥, because filing more entries must not red the build.
     *
     * <p>
     * ⚑ Raised 159 -> 162 once the scan was restricted to the real register documents; the old
     * value was reachable from cluster-row numbering alone and measured nothing.
     * </p>
     */
    private static final int REGISTER_FLOOR = 162;

    /**
     * Rules carrying BOTH blocks: all 76 {@code split} + 25 {@code correction}. The 9
     * {@code missing-row} rules have no {@code Source} to compare against.
     */
    private static final int ADOPTION_FLOOR = 101;

    private static final List<String> REGISTER_DOCS = List.of("SHEET-ISSUES.md",
            "SHEET-ISSUES-CDISC.md", "SHEET-ISSUES-CORE.md", "SHEET-ISSUES-FDA.md",
            "SHEET-ISSUES-PMDA.md", "CDISC-METADATA-ISSUES.md");

    private static final Pattern ENTRY_ROW = Pattern.compile("^\\|\\s*(\\d+)\\s*\\|");

    private static final Pattern PROSE_ENTRY = Pattern.compile("^##\\s*`?#(\\d+)`?",
            Pattern.MULTILINE);

    private static List<Path> ruleFiles() throws IOException
    {
        try (Stream<Path> walk = Files.walk(SRC_CHECKS))
        {
            return walk.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
        }
    }


    private static String coreId(Path file)
    {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".yaml".length());
    }


    /**
     * The register, read as a {@code #N} set — the document each entry lives in is irrelevant here.
     */
    private static Set<Integer> registerNumbers() throws IOException
    {
        Set<Integer> out = new TreeSet<>();
        {
            // ⛔⛔ BY NAME, never "every .md in the directory". The first version walked the whole
            // directory, and the predicate "a table row whose first cell is a number" then swept in
            // the CLUSTER ROW numbering of INFO-review-intent.md (1..113), WARNING-CLUSTERS.md
            // (1..27), GROUP-B-FOLLOWUPS-FINAL-REPORT.md (1..29) and more. Measured consequences:
            // REGISTER_FLOOR was satisfied by non-register documents alone, and the
            // "cites #N, unfiled" check was VACUOUS for 32 of the 41 numbers the corpus cites -
            // every one <= 113 resolved against a cluster row whether filed or not, so a side-car
            // citing "#3" would have passed. Same list as gen-source-proposed-manifest.py's DOCS;
            // they are two readers of one register and must stay in step.
            for (Path doc : REGISTER_DOCS.stream().map(REGISTER_DIR::resolve)
                    .filter(Files::isRegularFile).toList())
            {
                String text = Files.readString(doc, StandardCharsets.UTF_8);
                // String.lines(), not String.split("\n") - Error Prone's StringSplitter is at
                // ERROR here and the single-argument split has surprising trailing-empty behaviour.
                text.lines().forEach(line ->
                {
                    Matcher m = ENTRY_ROW.matcher(line);
                    if (m.find())
                    {
                        out.add(Integer.parseInt(m.group(1)));
                    }
                });
                Matcher prose = PROSE_ENTRY.matcher(text);
                while (prose.find())
                {
                    out.add(Integer.parseInt(prose.group(1)));
                }
            }
        }
        return out;
    }


    /**
     * The side-car, as {@code rule -> ordered block contents} (Sheet_Issue first, then the Row).
     */
    private static Map<String, Map<String, String>> sidecar() throws IOException
    {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        JsonNode root = YAML.readTree(SIDECAR.toFile());
        for (JsonNode rec : root.path("proposals"))
        {
            Map<String, String> fields = new LinkedHashMap<>();
            String issue = rec.path("Sheet_Issue").asText("");
            if (!issue.isEmpty())
            {
                fields.put("Sheet_Issue", issue);
            }
            JsonNode row = rec.path("Row");
            row.fieldNames().forEachRemaining(k -> fields.put(k, row.path(k).asText("")));
            out.put(rec.path("Rule").asText(), fields);
        }
        return out;
    }


    private static void require(List<String> problems, boolean ok, String message)
    {
        if (!ok)
        {
            problems.add(message);
        }
    }


    private static List<Integer> sheetIssues(JsonNode proposed)
    {
        List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("#(\\d+)").matcher(proposed.path("Sheet_Issue").asText(""));
        while (m.find())
        {
            out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }


    @Test
    void everyRuleMatchesTheSourceProposedTaxonomy() throws IOException
    {
        Map<String, Map<String, String>> sidecar = sidecar();
        Set<Integer> register = registerNumbers();

        assertTrue(sidecar.size() >= SIDECAR_FLOOR, "side-car scan saw " + sidecar.size());
        assertTrue(register.size() >= REGISTER_FLOOR, "register scan saw " + register.size());

        List<String> problems = new ArrayList<>();
        EnumMap<SourceProposedCase, Integer> seen = new EnumMap<>(SourceProposedCase.class);
        for (Path file : ruleFiles())
        {
            JsonNode rule = YAML.readTree(file.toFile());
            String id = coreId(file);
            SourceProposedCase kind = SourceProposedCase.classify(rule, id);
            seen.merge(kind, 1, Integer::sum);
            JsonNode proposed = rule.path("Source_Proposed");

            // (1) the corpus block exists exactly when the side-car proposes a row, in EVERY case.
            require(problems, !proposed.isMissingNode() == sidecar.containsKey(id), id + " (" + kind
                    + "): Source_Proposed present iff the side-car proposes a row");

            // (2) the case's own obligation.
            switch (kind)
            {
            case OUT_OF_SCOPE -> require(problems, !sidecar.containsKey(id),
                    id + ": CORE/DRAFT has no sheet, so no row can be proposed for it");
            case AS_DESCRIBED ->
            {
                // nothing owed - (1) already holds it honest
            }
            case SPLIT, MISSING_ROW, CORRECTION ->
            {
                require(problems, sidecar.containsKey(id),
                        id + ": " + kind + " owes a proposed row");
                if (kind != SourceProposedCase.SPLIT) // ruling G: a split files nothing
                {
                    require(problems, !proposed.path("Sheet_Issue").isMissingNode(),
                            id + ": " + kind + " requires a Sheet_Issue");
                }
            }
            }

            // (3) the block agrees with the side-car key for key, and every cited entry is filed.
            if (!proposed.isMissingNode())
            {
                Map<String, String> want = sidecar.get(id);
                if (want != null)
                {
                    Map<String, String> have = new LinkedHashMap<>();
                    proposed.fieldNames()
                            .forEachRemaining(k -> have.put(k, proposed.path(k).asText("")));
                    require(problems, want.equals(have),
                            id + ": drifted from the side-car - re-run"
                                    + " scripts/apply-sheet-proposals.sh\n    side-car: " + want
                                    + "\n    corpus  : " + have);
                }
                for (int issue : sheetIssues(proposed))
                {
                    require(problems, register.contains(issue),
                            id + " cites #" + issue + ", unfiled");
                }

                // (4) §2.5 - a rule's Severity follows Source, NEVER the proposal.
                String sourceSeverity = rule.path("Source").path("Severity").asText("Error");
                String proposedSeverity = proposed.path("Severity").asText(sourceSeverity);
                if (!proposedSeverity.equalsIgnoreCase(sourceSeverity))
                {
                    require(problems,
                            rule.path("Severity").asText("Error").equalsIgnoreCase(sourceSeverity),
                            id + ": Severity follows Source (" + sourceSeverity + "), never"
                                    + " Source_Proposed (" + proposedSeverity + ")");
                }
            }
        }
        System.out.println("Source_Proposed taxonomy: " + seen);
        for (SourceProposedCase c : List.of(SourceProposedCase.SPLIT,
                SourceProposedCase.MISSING_ROW, SourceProposedCase.CORRECTION))
        {
            assertEquals(FLOORS.get(c), seen.get(c),
                    c + " population moved - re-derive Phase 0 (scripts/"
                            + "gen-source-proposed-manifest.py), do not nudge this number");
        }
        assertEquals(List.of(), problems, String.join("\n", problems));
    }


    /**
     * Adoption: a proposal whose every key equals the corresponding {@code Source} key means the
     * author ADOPTED it — the block has done its job and must be retired with its entry closed.
     * Needs no workbook: it compares two blocks of one file.
     */
    @Test
    void noAdoptedProposalIsStillCarried() throws IOException
    {
        List<String> adopted = new ArrayList<>();
        int compared = 0;
        for (Path file : ruleFiles())
        {
            JsonNode rule = YAML.readTree(file.toFile());
            JsonNode proposed = rule.path("Source_Proposed");
            JsonNode source = rule.path("Source");
            if (proposed.isMissingNode() || source.isMissingNode())
            {
                continue; // missing-row: no row exists yet, nothing to adopt
            }
            compared++;
            Set<String> differing = differingKeys(source, proposed);
            String id = coreId(file);
            if (differing.isEmpty())
            {
                adopted.add(id);
            }
            else
            {
                System.out.println(id + " proposes: " + differing);
            }
        }
        assertTrue(compared >= ADOPTION_FLOOR,
                "compared " + compared + "; floor guards a broken scan");
        assertEquals(List.of(), adopted,
                "the sheet adopted these - retire the block, close the entry"
                        + " (⚠ first confirm Source's workbook/sheet/row is unchanged since the"
                        + " proposal was seeded; a re-anchor can forge adoption):\n"
                        + String.join("\n", adopted));
    }


    /**
     * Keys where the two blocks differ.
     *
     * <p>
     * {@code Sheet_Issue} is ignored — {@code Source} never has one. Key-absent counts as a
     * difference in BOTH directions: {@code Source} omits blank cells, so <i>"fill this empty
     * cell"</i> is spelled by the key existing in the proposal and not in {@code Source}.
     * </p>
     */
    private static Set<String> differingKeys(JsonNode source, JsonNode proposed)
    {
        Set<String> keys = new TreeSet<>();
        source.fieldNames().forEachRemaining(keys::add);
        proposed.fieldNames().forEachRemaining(keys::add);
        keys.remove("Sheet_Issue");
        Set<String> differing = new TreeSet<>();
        for (String k : keys)
        {
            if (!source.path(k).asText("").equals(proposed.path(k).asText("")))
            {
                differing.add(k);
            }
        }
        return differing;
    }
}
