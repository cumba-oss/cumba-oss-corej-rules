package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Corpus-wide restoration of the guard that
 * {@code RuleLoadValidationNonExecutableOperatorTest.shippedCorpusRulesUsingOperatorLoadClean} lost
 * when the engine moved to trimmed fixture packages (PLAN-engine-rules-decoupling, review finding
 * #3): shipped rules using {@code is_not_contained_by_case_insensitive} must load WITHOUT a
 * loadError — the operator is implemented on both engines, so a regression re-tagging it would
 * silently turn those rules into no-ops. Runs over every shipped package in {@code rules/}.
 */
class ShippedRulesOperatorLoadCleanTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void noShippedRuleTagsImplementedOperatorAsNonExecutable() throws IOException
    {
        List<String> offenders = new ArrayList<>();
        var packagesJson = MAPPER.readTree(Files.readString(CorpusPaths.rules("packages.json")));
        for (var entry : packagesJson.get("packages"))
        {
            Path file = CorpusPaths.rules(entry.get("file").asText());
            RulePackage pkg = RulePackageLoader.load(file);
            for (Rule rule : pkg.getRules().values())
            {
                if (rule != null && rule.getLoadError() != null
                        && rule.getLoadError().contains("is_not_contained_by_case_insensitive"))
                {
                    offenders.add(file.getFileName() + ": "
                            + (rule.getCore() != null ? rule.getCore().getId() : rule.getId()));
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "is_not_contained_by_case_insensitive is implemented and must not tag a"
                        + " loadError, but these shipped rules carry one: " + offenders);
    }
}
