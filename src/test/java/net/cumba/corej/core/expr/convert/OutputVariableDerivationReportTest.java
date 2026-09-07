package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.OutputVariableDeriver;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 of {@code plans/done/PLAN-auto-output-variables.md}: runs
 * {@link OutputVariableDeriver#derivedOnly} over every rule of the shipped packages in
 * {@code rules/} and writes the authored-vs-derived delta to
 * {@code target/output-variable-derivation-report.md} — the in-repo replacement for the Phase-0b
 * scratch probe, since discarded.
 *
 * <p>
 * <b>Report-only.</b> The corpus legitimately omits derivable entries (that is what the derivation
 * is for), so nothing about the delta is asserted beyond "the corpus was actually read" — a silent
 * path/glob breakage must not masquerade as "no delta". The report includes the spec cross-check
 * (which {@code rulespec/specs} reference a rule with a non-empty delta) because that list is the
 * exact Phase-4 lane-C re-capture cost.
 * </p>
 */
class OutputVariableDerivationReportTest
{

    private static final Path RULES_DIR = CorpusPaths.rules();

    // ⚠ if this path moves, parityCrossCheck() below degrades SILENTLY (its isDirectory guard
    // returns an empty list and the test stays green) — see
    // PLAN-parity-suite-reclassification §7.2. Verified non-empty after the wave-41 rename.
    private static final Path SPECS_DIR = Path.of("rulespec", "specs");

    private static final Path REPORT = Path.of("target", "output-variable-derivation-report.md");

    private record FamilyDerivation(String family, List<String> authored, List<String> delta,
            List<String> pureDerived)
    {
    }

    @Test
    void writeDerivationReport() throws IOException
    {
        Map<String, FamilyDerivation> byRule = new LinkedHashMap<>();
        List<Path> packages;
        try (Stream<Path> s = Files.list(RULES_DIR))
        {
            packages = s.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        for (Path pkgPath : packages)
        {
            // rules-<family>-<standard>-<version>.json — the family is the second dash segment
            String fileName = pkgPath.getFileName().toString();
            int familyStart = fileName.indexOf('-') + 1;
            int familyEnd = fileName.indexOf('-', familyStart);
            String family = fileName
                    .substring(familyStart, familyEnd > 0 ? familyEnd : fileName.length())
                    .toUpperCase(Locale.ROOT);
            RulePackage pkg = RulePackageLoader.load(pkgPath);
            if (pkg.getRules() == null)
            {
                continue;
            }
            for (Map.Entry<String, Rule> e : pkg.getRules().entrySet())
            {
                Rule rule = e.getValue();
                if (rule == null || rule.getLoadError() != null)
                {
                    continue;
                }
                String id = rule.getCore() != null && rule.getCore().getId() != null
                        ? rule.getCore().getId()
                        : e.getKey();
                if (byRule.containsKey(id))
                {
                    continue; // dedupe across package versions
                }
                byRule.put(id, analyse(rule, family));
            }
        }
        assertTrue(byRule.size() > 3000,
                "corpus glob broke: only " + byRule.size() + " rules read");
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, render(byRule, parityCrossCheck(byRule)), StandardCharsets.UTF_8);
    }


    private static FamilyDerivation analyse(Rule rule, String family)
    {
        List<String> authored = rule.getOutcome() != null
                && rule.getOutcome().getOutputVariables() != null
                        ? List.copyOf(rule.getOutcome().getOutputVariables())
                        : List.of();
        List<String> delta = OutputVariableDeriver.derivedOnly(rule);
        // The pure-derived list (what the deriver yields with no authored list at all) feeds
        // the inverse lint; computed by temporarily detaching the authored list.
        Outcome outcome = rule.getOutcome();
        List<String> pureDerived;
        if (outcome == null)
        {
            pureDerived = OutputVariableDeriver.derive(rule);
        }
        else
        {
            List<String> saved = outcome.getOutputVariables();
            outcome.setOutputVariables(null);
            pureDerived = OutputVariableDeriver.derive(rule);
            outcome.setOutputVariables(saved);
        }
        return new FamilyDerivation(family, authored, delta, pureDerived);
    }


    /** Specs whose {@code coreId} names a rule with a non-empty delta — the lane-C cost. */
    private static List<String> parityCrossCheck(Map<String, FamilyDerivation> byRule)
        throws IOException
    {
        if (!Files.isDirectory(SPECS_DIR))
        {
            return List.of();
        }
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        List<String> affected = new ArrayList<>();
        List<Path> specs;
        try (Stream<Path> s = Files.list(SPECS_DIR))
        {
            specs = s.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
        }
        for (Path spec : specs)
        {
            JsonNode root = yaml.readTree(spec.toFile());
            JsonNode coreId = root.path("rule").path("coreId");
            if (coreId.isTextual())
            {
                FamilyDerivation info = byRule.get(coreId.asText());
                if (info != null && !info.delta().isEmpty())
                {
                    affected.add(spec.getFileName().toString() + " (" + coreId.asText() + ": "
                            + String.join(" ", info.delta()) + ")");
                }
            }
        }
        return affected;
    }


    private static String classify(String name)
    {
        if (name.startsWith("$"))
        {
            return "operation";
        }
        if (name.contains("."))
        {
            return "dotted";
        }
        if (name.startsWith("dataset_") || name.startsWith("library_dataset_")
                || name.startsWith("define_dataset_") || "record_count".equals(name))
        {
            return "ds-virtual";
        }
        if (name.startsWith("variable_") || name.startsWith("library_variable_")
                || name.startsWith("define_variable_") || name.startsWith("define_vlm_"))
        {
            return "var-virtual";
        }
        return "column";
    }


    private static String render(Map<String, FamilyDerivation> byRule, List<String> affectedSpecs)
    {
        int total = byRule.size();
        int noDelta = 0;
        int noAuthored = 0;
        int authoredTotal = 0;
        int authoredAlsoDerived = 0;
        int authoredNotDerivable = 0;
        Map<String, int[]> perFamily = new TreeMap<>(); // family -> [rules, withDelta, entries]
        Map<String, Integer> classCount = new TreeMap<>();
        Map<Integer, Integer> sizeHistogram = new TreeMap<>();
        for (FamilyDerivation info : byRule.values())
        {
            int[] fam = perFamily.computeIfAbsent(info.family(), _ -> new int[3]);
            fam[0]++;
            if (info.delta().isEmpty())
            {
                noDelta++;
            }
            else
            {
                fam[1]++;
                fam[2] += info.delta().size();
                sizeHistogram.merge(Math.min(info.delta().size(), 6), 1, Integer::sum);
            }
            for (String name : info.delta())
            {
                classCount.merge(classify(name), 1, Integer::sum);
            }
            if (info.authored().isEmpty())
            {
                noAuthored++;
            }
            authoredTotal += info.authored().size();
            for (String a : info.authored())
            {
                if (info.pureDerived().contains(a))
                {
                    authoredAlsoDerived++;
                }
                else
                {
                    authoredNotDerivable++;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Output_Variables derivation delta report\n\n");
        sb.append("Generated by `OutputVariableDerivationReportTest` (report-only) over the\n");
        sb.append("shipped packages in `rules/`, rules deduped by `Core.Id`. See\n");
        sb.append("`plans/done/PLAN-auto-output-variables.md` §4 for the derivation rules.\n\n");
        sb.append("## 1. Headline\n\n| metric | count | % |\n|---|---:|---:|\n");
        row(sb, "unique rules analysed", total, total);
        row(sb, "empty delta (derivation is a no-op)", noDelta, total);
        row(sb, "rules gaining at least one entry", total - noDelta, total);
        row(sb, "rules with no authored Output_Variables", noAuthored, total);
        sb.append("\n## 2. Delta size histogram\n\n| entries gained | rules |\n|---:|---:|\n");
        for (Map.Entry<Integer, Integer> e : sizeHistogram.entrySet())
        {
            sb.append("| ").append(e.getKey() == 6 ? "6+" : String.valueOf(e.getKey()))
                    .append(" | ").append(e.getValue()).append(" |\n");
        }
        sb.append("\n## 3. Delta by contribution class\n\n| class | entries |\n|---|---:|\n");
        for (Map.Entry<String, Integer> e : classCount.entrySet())
        {
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        sb.append("\n## 4. By family\n\n");
        sb.append("| family | rules | with delta | delta entries |\n|---|---:|---:|---:|\n");
        for (Map.Entry<String, int[]> e : perFamily.entrySet())
        {
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()[0]).append(" | ")
                    .append(e.getValue()[1]).append(" | ").append(e.getValue()[2]).append(" |\n");
        }
        sb.append("\n## 5. Inverse lint\n\n| metric | count |\n|---|---:|\n");
        sb.append("| authored entries, total | ").append(authoredTotal).append(" |\n");
        sb.append("| authored entries the deriver would produce anyway | ")
                .append(authoredAlsoDerived).append(" |\n");
        sb.append("| authored entries not derivable (extras; includes D3/D5/D6 exclusions) | ")
                .append(authoredNotDerivable).append(" |\n");
        sb.append("\n(The not-derivable bucket is the author's prerogative (D1) — context\n");
        sb.append("columns, `$`-ids on the §4.1 ignore list, and the rare D3-suppressed\n");
        sb.append("entries. Candidates for a later corpus lint, never touched by the engine.)\n");
        sb.append("\n## 6. Parity cross-check (Phase-4 lane C)\n\n");
        sb.append(affectedSpecs.size()).append(" spec(s) reference a rule with a non-empty ")
                .append("delta and need re-capture:\n\n");
        for (String line : affectedSpecs)
        {
            sb.append("- ").append(line).append('\n');
        }
        sb.append("\n## 7. Full delta list\n\n| rule | family | delta |\n|---|---|---|\n");
        for (Map.Entry<String, FamilyDerivation> e : byRule.entrySet())
        {
            if (!e.getValue().delta().isEmpty())
            {
                sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue().family())
                        .append(" | ").append(String.join(", ", e.getValue().delta()))
                        .append(" |\n");
            }
        }
        return sb.toString();
    }


    private static void row(StringBuilder sb, String label, int n, int total)
    {
        sb.append("| ").append(label).append(" | ").append(n).append(" | ")
                .append(total == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0 * n / total))
                .append(" |\n");
    }
}
