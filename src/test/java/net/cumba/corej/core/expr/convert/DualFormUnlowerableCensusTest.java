package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the Java-side count of conversion-unlowerable rules ({@link DualFormCheckNode}) — the count
 * the corpus documentation census ({@code RuleDocCensus}, {@code CORE-RULES-SPECIFICATION.md} §C.2)
 * is derived from.
 *
 * <p>
 * {@link DualFormCheckNode} and {@code scripts/lib/check_expr.py}'s {@code dual_form_check} are
 * independent implementations of the same lowering, and nothing forces them to agree on
 * <em>which</em> rules stay in expression form. Runbook W8 M2 (combined review) measured one such
 * gap: the corpus's {@code is_sorted_by} authors were taught the bare {@code asc(col)} spelling
 * (matching the engine's {@code ExprCompiler.groupOperandName}, which refuses a quoted column as a
 * string literal), and {@code check_expr.py} was updated to match, but {@code sortedByLeaf} here
 * still required the OLD quoted {@code asc("col")}. Effect: the 5 {@code is_sorted_by} rules
 * ({@code CDISC-CG0620}, {@code CDISC-CG0662}, {@code CDISC-SEND-0130}, {@code CDISC-SEND-0130.1},
 * {@code CDISC-SEND-0354}) silently dropped out of the Java view, {@code target_is_not_sorted_by}
 * left the distinct check-operator roster and §C.2's {@code DATA} row moved 68 → 67 — with every
 * other gate green. Measured on the fix round: <b>84 unlowerable before, 79 after</b>, the
 * difference exactly those 5.
 * </p>
 *
 * <p>
 * ⚠ <b>79 is NOT the Python pin (66 in {@code generated/rule-inventory/lint-rules.py}).</b> The two
 * lowerings disagree on 13 further rules that Python lowers and this class refuses
 * ({@code CDISC-AD0169, CG0280, CG0284, SEND-0194, SEND-0324, SEND-0324-A; FDA-SD1037, SD1212,
 * SD1269, SD9746; PMDA-SD1037, SD1212, SD1269} — measured 2026-09-30, filed with the combined
 * review; corrected in its round 2 from "18", which had counted {@code CG0078}, {@code CG0218},
 * {@code CG0233}, {@code CG0235} and {@code SEND-0283}, all five in Python's own unlowerable set);
 * the Python set is a strict subset of this one, 66 + 13 = 79. This pin is the JAVA count: it moves
 * in lockstep with {@link DualFormCheckNode}, and it must be re-derived from a run, never adjusted
 * by hand to make a red build pass. The failure message lists the current unlowerable ids so a
 * drift is diagnosable without re-deriving blind.
 * </p>
 *
 * <p>
 * Scope: the {@code CDISC}/{@code FDA}/{@code PMDA} families under {@code rules-src/checks}
 * ({@code DRAFT} is out of scope, as for the Python pin).
 * </p>
 */
class DualFormUnlowerableCensusTest
{

    private static final int EXPECTED_UNLOWERABLE = 79;

    private static final List<String> FAMILIES = List.of("CDISC", "FDA", "PMDA");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void theJavaUnlowerableCountIsPinned() throws IOException
    {
        Path checksRoot = Path.of(System.getProperty("projectBasedir")).resolve("rules-src")
                .resolve("checks");
        List<String> unlowerable = new ArrayList<>();
        for (String family : FAMILIES)
        {
            Path dir = checksRoot.resolve(family);
            if (!Files.isDirectory(dir))
            {
                continue;
            }
            try (Stream<Path> stream = Files.list(dir))
            {
                for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                        .sorted().toList())
                {
                    JsonNode node = YAML.readTree(Files.readString(file, StandardCharsets.UTF_8));
                    String id = node.path("Core").path("Id").asText();
                    if (!DualFormCheckNode.lowerCheckInPlace(node))
                    {
                        unlowerable.add(family + "/" + id);
                    }
                }
            }
        }
        assertEquals(EXPECTED_UNLOWERABLE, unlowerable.size(),
                "the Java unlowerable rule count drifted from its pin (84 → 79 on the W8 M2 fix;"
                        + " the Python pin is 66, a different implementation) — re-derive"
                        + " deliberately, current set: " + unlowerable);
    }
}
