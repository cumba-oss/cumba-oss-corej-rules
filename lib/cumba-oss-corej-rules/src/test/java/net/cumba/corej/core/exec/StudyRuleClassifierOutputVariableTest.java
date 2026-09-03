package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Phase-2.5 regression guard of {@code plans/done/PLAN-auto-output-variables.md}: because D4a puts
 * derived {@code $op}s into the effective Output_Variables and
 * {@link StudyRuleClassifier#readsPrimaryDataset} gates study-anchor eligibility on OV-named
 * {@code $op}s, the study lane can shrink. That shrink is <em>correct</em> (the operation really
 * does read the dataset and really is now rendered) but must be measured, not discovered — this
 * test counts eligibility across the shipped packages under the authored list vs the effective list
 * and writes the shift to {@code target/study-anchor-ov-shift.md}.
 *
 * <p>
 * ⚠ <b>It lives in {@code corej-cdisc-rules}, not beside {@link StudyRuleClassifier}.</b> It is
 * corpus-driven, and the corpus is generated into {@code corej-cdisc-rules/target/} during that
 * module's build ({@code plans/PLAN-rules-corpus-build-integration.md}); {@code corej-cdisc-core}
 * builds <em>before</em> it, so a copy left there would assert on a directory that does not exist
 * yet. The reverse dependency cannot fix that — it would be a reactor cycle, which is the same
 * reason the corpus-driven Define-XML tests live here (PLAN-rules-module-consolidation D12/D13).
 * The engine-side unit test of the classifier, {@code StudyRuleClassifierTest}, stays in
 * {@code corej-cdisc-core}.
 * </p>
 */
class StudyRuleClassifierOutputVariableTest
{

    private static final Path RULES_DIR = CorpusPaths.rules();

    private static final Path REPORT = Path.of("target", "study-anchor-ov-shift.md");

    @Test
    void studyEligibilityShiftIsMeasuredAndShrinkOnly() throws IOException
    {
        assertTrue(Files.isDirectory(RULES_DIR),
                "shipped packages not found at " + RULES_DIR.toAbsolutePath());
        Set<String> seen = new LinkedHashSet<>();
        int eligibleAuthored = 0;
        int eligibleEffective = 0;
        List<String> shifted = new ArrayList<>();
        List<Path> packages;
        try (Stream<Path> s = Files.list(RULES_DIR))
        {
            packages = s.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        for (Path pkgPath : packages)
        {
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
                if (!seen.add(id))
                {
                    continue;
                }
                boolean effectiveEligible = !StudyRuleClassifier.readsPrimaryDataset(rule);
                List<String> saved = rule.getEffectiveOutputVariables();
                rule.setEffectiveOutputVariables(null);
                boolean authoredEligible = !StudyRuleClassifier.readsPrimaryDataset(rule);
                rule.setEffectiveOutputVariables(saved);
                if (authoredEligible)
                {
                    eligibleAuthored++;
                }
                if (effectiveEligible)
                {
                    eligibleEffective++;
                }
                if (authoredEligible != effectiveEligible)
                {
                    shifted.add(id + (authoredEligible ? " (eligible -> ineligible)"
                            : " (ineligible -> ELIGIBLE?!)"));
                }
            }
        }
        assertTrue(seen.size() > 3000, "corpus glob broke: only " + seen.size() + " rules read");

        StringBuilder sb = new StringBuilder();
        sb.append("# Study-anchor eligibility shift from the OV derivation (EC-37)\n\n");
        sb.append("| metric | count |\n|---|---:|\n");
        sb.append("| unique rules | ").append(seen.size()).append(" |\n");
        sb.append("| study-anchor eligible, authored OV | ").append(eligibleAuthored)
                .append(" |\n");
        sb.append("| study-anchor eligible, effective OV | ").append(eligibleEffective)
                .append(" |\n\n");
        for (String line : shifted)
        {
            sb.append("- ").append(line).append('\n');
        }
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, sb.toString(), StandardCharsets.UTF_8);

        // A derived $op can only ADD a reason to read the primary dataset — eligibility must
        // never grow. Every shifted rule must be a shrink.
        assertTrue(eligibleEffective <= eligibleAuthored,
                "study lane GREW under the effective list: " + shifted);
        for (String line : shifted)
        {
            assertTrue(line.endsWith("(eligible -> ineligible)"),
                    "unexpected eligibility growth: " + line);
        }
    }
}
