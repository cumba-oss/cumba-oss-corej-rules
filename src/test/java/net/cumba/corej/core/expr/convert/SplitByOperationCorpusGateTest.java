package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * #14 of {@code plans/done/PLAN-retire-corpus-transforms.md}: {@code split_by} is <b>not an
 * operation</b> — it is a per-row value function, authored inline in the {@code Check} as
 * {@code split_by(<col>, "<delim>")} ({@code CDISC-SEND-0049} is the pattern; {@code FDA-CT2002}
 * likewise). An operation result is broadcast to every row ({@code ExprCompiler.operationCallPlan}
 * &rarr; {@code ConstVector}), while a {@code split_by} result is a per-row-<em>varying</em> token
 * list, so the operator has no {@code OperationType} <b>by architecture</b> — a rule declaring one
 * cannot load ({@code normalizeOperations} &rarr; {@code unknown operation function}), and until
 * phase 3 the corpus pipeline silently repaired that by lowering the declaration into the Check.
 * That lowering is retired; this gate makes the loader's implicit rejection an explicit,
 * at-the-source hard failure.
 *
 * <p>
 * A JUnit corpus gate rather than a {@code lint-rules.py} rule: the lint is propose-only / INFO,
 * and "disallow" wants a red build.
 * </p>
 */
class SplitByOperationCorpusGateTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final Path SRC_CHECKS = Path.of("rules-src", "checks");

    // ⚠ Corpus-size floor dropped on extraction: it asserted the FULL internal corpus,
    // which this subset deliberately is not. Enforced internally over the whole corpus.

    @Test
    void noRuleDeclaresASplitByOperation() throws IOException
    {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC_CHECKS))
        {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .sorted().toList())
            {
                JsonNode rule = YAML.readTree(Files.readString(file));
                JsonNode ops = rule.get("Operations");
                if (ops == null || !ops.isArray())
                {
                    continue;
                }
                for (JsonNode op : ops)
                {
                    String operator = op.path("operator").asText("");
                    String expression = op.path("expression").asText("");
                    if ("split_by".equals(operator) || expression.startsWith("split_by("))
                    {
                        offenders.add(rule.path("Core").path("Id").asText(file.toString()));
                    }
                }
            }
        }
        // ⚠ Corpus-size floor removed on extraction: it asserted the FULL internal corpus,
        // which this subset deliberately is not. Enforced internally over the whole corpus.
        assertEquals(List.of(), offenders,
                "`split_by` is not an operation (it is a per-row value function with no"
                        + " OperationType by architecture — a declaration cannot load). Author"
                        + " the call inline in the Check instead: `split_by(<col>, \"<delim>\")`,"
                        + " as CDISC-SEND-0049 does. Offending rules: " + offenders);
    }
}
