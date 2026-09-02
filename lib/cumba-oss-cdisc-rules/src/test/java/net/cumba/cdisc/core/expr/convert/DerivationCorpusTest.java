package net.cumba.cdisc.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.cumba.cdisc.core.exec.RuleClassifier;
import net.cumba.cdisc.core.exec.StudyRuleClassifier;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.Sensitivity;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 + 2 of {@code plans/done/PLAN-derive-rule-type-sensitivity.md}: runs
 * {@link RuleClassifier} over every rule in {@code rules-src/checks} and writes the
 * derived-vs-authored {@code Sensitivity} diff to {@code documentation/derivation/}. (The
 * {@code Rule_Type} half of the report retired with the field —
 * {@code PLAN-leaf-scope-domain-inference.md} phase 7.)
 *
 * <p>
 * <b>Report-only.</b> The plan's exit criterion for these phases is a reviewed diff, not a green
 * assertion — the corpus contains rules that are correct as authored but not derivable (the
 * assertion-split shape, plan &sect;3.4), and the operation-scope table is still being grounded.
 * The only thing asserted here is that the corpus was actually read, so a silent path/glob breakage
 * cannot masquerade as "no diff".
 * </p>
 */
class DerivationCorpusTest
{

    private static final Path CHECKS_DIR = Path.of("rules-src", "checks");

    private static final Path REPORT_DIR = Path.of("documentation", "derivation");

    /**
     * The {@code rules-src} YAML carries authoring-only blocks the runtime {@link Rule} model does
     * not bind ({@code Source}, {@code Standards}), so unknown properties are ignored rather than
     * fatal — this reader only needs the fields the derivation reads.
     */
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()).disable(
            com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * Triage already recorded in the plan, keyed by rule id, so a reviewer sees at a glance which
     * rows are settled and which are open. Kept here rather than in the rule YAML because the
     * corpus carries no change/history commentary.
     */
    private static final String GROUP_B = "GROUP B — reports define_variable_name; Dataset hides all but the first offending"
            + " ItemDef. PROPOSED Sensitivity -> Record (under review)";

    private static final String GROUP_B_OVERRIDE = "GROUP B override — finding is about the DOMAIN code, not a variable; Dataset is"
            + " correct as authored";

    /**
     * The DS assertion-split pair. {@code CDISC-CG0535} / {@code CORE-000212} assert the
     * present-DSSCAT half; {@code CDISC-CG0537} / {@code CORE-000374} are the explicit
     * absent-DSSCAT complement, so a study missing the column is still covered by the other half of
     * the pair.
     */
    private static final String ASSERTION_SPLIT_DS = "§3.4 CORRECT AS AUTHORED — assertion-split;"
            + " twins CDISC-CG0537 / CORE-000374";

    private static final Map<String, String> TRIAGE = Map.ofEntries(
            Map.entry("CDISC-AD0646", "§3.5 CORRECTION — Rule_Type → Record Data; no parity spec"),
            Map.entry("CDISC-AD0365", "§3.5 CORRECTION — Rule_Type → Record Data; no parity spec"),
            Map.entry("CDISC-AD0366",
                    "§3.5 CORRECTION — Rule_Type → Record Data; twin PMDA-AD0366 already correct"),
            Map.entry("CDISC-SEND-0083",
                    "§3.4 CORRECTION — Sensitivity → Record; user-confirmed 2026-07-28"),
            Map.entry("PMDA-AD0061", "§3.7 RE-AUTHOR — mixed dataset/variable presence"),
            Map.entry("PMDA-AD0061A", "§3.7 RE-AUTHOR — mixed dataset/variable presence"),
            Map.entry("PMDA-SD1468", "§3.7 RE-AUTHOR — mixed dataset/variable presence"),
            Map.entry("PMDA-SD1469", "§3.7 RE-AUTHOR — mixed dataset/variable presence"),
            // One shared note, not two family-specific ones: CG0535/CORE-000212 are a twin pair
            // and so are their complements, so naming both complements is correct for either
            // member — and it lets mergedTriage collapse the row's cell to a single note.
            Map.entry("CDISC-CG0535", ASSERTION_SPLIT_DS),
            Map.entry("CORE-000212", ASSERTION_SPLIT_DS),
            Map.entry("CDISC-SEND-0153",
                    "§3.4 CORRECT AS AUTHORED — assertion is a global invariant"),
            Map.entry("CDISC-SEND-0249.1",
                    "§3.4 CORRECT AS AUTHORED — assertion is a global invariant"),
            // Group B — Define Item Metadata Check against Library Metadata. Each reports
            // `define_variable_name`, so `Dataset` collapses to one finding and hides every
            // offending ItemDef but the first. PROPOSED Sensitivity -> Record, under review.
            Map.entry("CDISC-SEND-0005", GROUP_B), Map.entry("CDISC-SEND-0007", GROUP_B),
            Map.entry("CDISC-SEND-0009.1", GROUP_B), Map.entry("CDISC-SEND-0050", GROUP_B),
            Map.entry("CDISC-SEND-0052", GROUP_B), Map.entry("CDISC-CG0010", GROUP_B),
            Map.entry("CORE-001081", GROUP_B), Map.entry("DRAFT-900021", GROUP_B),
            Map.entry("DRAFT-900022", GROUP_B), Map.entry("DRAFT-900023", GROUP_B),
            Map.entry("DRAFT-900024", GROUP_B),
            // The two Define-Item rules whose finding is about the DOMAIN code, not a variable.
            Map.entry("CDISC-CG0001", GROUP_B_OVERRIDE),
            Map.entry("CORE-000929", GROUP_B_OVERRIDE));

    /**
     * One corpus rule with its authored and derived values; {@code checkKey} is the canonical
     * {@code Check} used to collapse twins.
     */
    private static final ObjectMapper CANON = new ObjectMapper().configure(
            com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private record Row(String id, String family, String authored, String derived, String confidence,
            String rationale, String checkKey)
    {

        String triage()
        {
            return TRIAGE.getOrDefault(id(), "");
        }
    }

    /**
     * Groups rows that are twins — the same transition, reached by a byte-identical {@code Check}.
     * Cross-family twins (a CDISC rule and its PMDA/FDA/CORE counterpart) otherwise repeat the same
     * finding several times and inflate a cluster.
     */
    private static List<List<Row>> groupTwins(List<Row> rows)
    {
        Map<String, List<Row>> byKey = new java.util.LinkedHashMap<>();
        for (Row r : rows)
        {
            byKey.computeIfAbsent(r.checkKey() + "|" + r.authored() + "|" + r.derived(),
                    _ -> new ArrayList<>()).add(r);
        }
        return new ArrayList<>(byKey.values());
    }


    @Test
    void writesDerivationDiffReports() throws IOException
    {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CHECKS_DIR))
        {
            files = walk.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "no rule YAML found under " + CHECKS_DIR.toAbsolutePath());

        List<Row> sensitivityRows = new ArrayList<>();
        List<String[]> unanchored = new ArrayList<>();
        int total = 0;
        for (Path file : files)
        {
            Rule rule = YAML.readValue(file.toFile(), Rule.class);
            total++;
            String id = rule.getCore() != null && rule.getCore().getId() != null
                    ? rule.getCore().getId()
                    : file.getFileName().toString();
            String family = file.getParent().getFileName().toString();

            // Sensitivity derivation is type-free (every presence leaf spells its own fact since
            // the generic `exists` was retired).
            RuleClassifier.Derived<Sensitivity> sens = RuleClassifier.deriveSensitivity(rule);
            if (differs(rule.getSensitivityJson(),
                    sens.value() == null ? null : sens.value().getJsonValue()))
            {
                sensitivityRows.add(new Row(id, family, String.valueOf(rule.getSensitivityJson()),
                        sens.value() == null ? "(none)" : sens.value().getJsonValue(),
                        sens.confidence().name(), sens.rationale(), checkKey(rule)));
            }
            // §3.9 lint — a rule whose verdict cannot vary per dataset, whose anchor names a
            // CONCRETE dataset, and whose Scope pins nothing, emits that same verdict once per
            // dataset in the study. LibraryValidator's cross-dataset collapse only fires for
            // Sensitivity: Study, so nothing dedupes them.
            List<String> anchors = RuleClassifier.datasetAnchors(rule);
            if (rule.getSensitivity() != Sensitivity.STUDY && !anchors.isEmpty()
                    && sens.value() == Sensitivity.DATASET
                    && StudyRuleClassifier.hasUnrestrictedScope(rule))
            {
                unanchored.add(new String[]
                {
                        id, family, String.join(",", anchors),
                        String.valueOf(rule.getSensitivityJson())
                });
            }
        }

        Files.createDirectories(REPORT_DIR);
        writeUnanchored(REPORT_DIR.resolve("unanchored-scope.tsv"), unanchored);
        writeTsv(REPORT_DIR.resolve("sensitivity-diff.tsv"), sensitivityRows);
        writeSummary(REPORT_DIR.resolve("DERIVATION-SUMMARY.md"), total, sensitivityRows);

        System.out.printf(Locale.ROOT,
                "[derivation] %d rules | Sensitivity diff %d | unanchored-scope %d%n", total,
                sensitivityRows.size(), unanchored.size());
    }


    /**
     * Writes the &sect;3.9 lint: rules that emit one identical finding per dataset because their
     * anchor names a concrete dataset their {@code Scope} does not pin.
     */
    private static void writeUnanchored(Path target, List<String[]> rows) throws IOException
    {
        StringBuilder sb = new StringBuilder("id\tfamily\tanchors\tsensitivity\n");
        for (String[] r : rows)
        {
            sb.append(String.join("\t", r)).append('\n');
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
    }


    /** A rule's {@code Check} serialized canonically, so twins collapse onto one key. */
    private static String checkKey(Rule rule)
    {
        try
        {
            return rule.getCheck() == null ? "" : CANON.writeValueAsString(rule.getCheck());
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException e)
        {
            throw new IllegalStateException("cannot serialize Check", e);
        }
    }


    private static boolean differs(String authored, String derived)
    {
        return authored != null && !authored.equals(derived);
    }


    private static void writeTsv(Path target, List<Row> rows) throws IOException
    {
        StringBuilder sb = new StringBuilder(
                "id\tfamily\tauthored\tderived\tconfidence\ttriage\trationale\n");
        for (Row r : rows)
        {
            sb.append(r.id()).append('\t').append(r.family()).append('\t').append(r.authored())
                    .append('\t').append(r.derived()).append('\t').append(r.confidence())
                    .append('\t').append(r.triage()).append('\t').append(r.rationale())
                    .append('\n');
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
    }


    private static void writeSummary(Path target, int total, List<Row> sensitivityRows)
        throws IOException
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# Derivation diff — `Sensitivity` derived vs authored\n\n")
                .append("Generated by `DerivationCorpusTest` from the Java `RuleClassifier` over ")
                .append("`rules-src/checks`. Report-only — see ")
                .append("`plans/done/PLAN-derive-rule-type-sensitivity.md`. `Rule_Type` is no longer ")
                .append("a rule field (`plans/PLAN-leaf-scope-domain-inference.md` phase 7): the ")
                .append("engine infers the evaluation domain from the Check, so that half of ")
                .append("this report is retired.\n\n").append("| | count |\n|---|---|\n")
                .append("| rules scanned | ").append(total).append(" |\n")
                .append("| `Sensitivity` differs | ").append(sensitivityRows.size())
                .append(" |\n\n")
                .append("Rows carrying a **triage** note are already settled in the plan; the rest ")
                .append("are open for review.\n");
        appendSection(sb, "Sensitivity", sensitivityRows);
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
    }


    private static void appendSection(StringBuilder sb, String field, List<Row> rows)
    {
        sb.append("\n## ").append(field).append(" (n=").append(rows.size()).append(")\n");
        Map<String, List<Row>> byTransition = new TreeMap<>();
        for (Row r : rows)
        {
            byTransition.computeIfAbsent(r.authored() + " → " + r.derived(), _ -> new ArrayList<>())
                    .add(r);
        }
        List<Map.Entry<String, List<Row>>> ordered = new ArrayList<>(byTransition.entrySet());
        ordered.sort(
                Comparator.comparingInt((Map.Entry<String, List<Row>> e) -> -e.getValue().size()));
        for (Map.Entry<String, List<Row>> e : ordered)
        {
            List<List<Row>> twins = groupTwins(e.getValue());
            sb.append("\n### `").append(e.getKey()).append("` — ").append(e.getValue().size())
                    .append(" rules in ").append(twins.size()).append(" twin group")
                    .append(twins.size() == 1 ? "" : "s")
                    .append("\n\n| rules | conf | triage | why derived |\n|---|---|---|---|\n");
            for (List<Row> group : twins)
            {
                Row first = group.get(0);
                StringBuilder ids = new StringBuilder();
                for (Row r : group)
                {
                    ids.append(ids.isEmpty() ? "" : "<br>").append('`').append(r.id()).append('`');
                }
                sb.append("| ").append(ids).append(" | ").append(first.confidence()).append(" | ")
                        .append(mergedTriage(group)).append(" | ").append(first.rationale())
                        .append(" |\n");
            }
        }
    }


    /**
     * The triage cell for a twin group: every <b>distinct</b> non-empty note in the group, in
     * member order, joined the same way the ids are.
     *
     * <p>
     * Twins share a {@code Check} but not necessarily a triage note, because a note may name the
     * rule's own family-specific counterpart — {@code CDISC-CG0535} points at {@code CDISC-CG0537}
     * where its twin {@code CORE-000212} points at {@code CORE-000374}. Emitting only
     * {@code group.get(0)}'s note silently dropped the other, which is how {@code CORE-000212}'s
     * pointer went missing from the committed report ({@code PLAN-absent-column-guard-backlog} P6
     * finding F12). The common case — every member carrying the identical note — still renders
     * exactly one note, so the rest of the report is unchanged.
     * </p>
     */
    private static String mergedTriage(List<Row> group)
    {
        List<String> notes = new ArrayList<>();
        for (Row r : group)
        {
            if (!r.triage().isEmpty() && !notes.contains(r.triage()))
            {
                notes.add(r.triage());
            }
        }
        return notes.isEmpty() ? "—" : String.join("<br>", notes);
    }

}
