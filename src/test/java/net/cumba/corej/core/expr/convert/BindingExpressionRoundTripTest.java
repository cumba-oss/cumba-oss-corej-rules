package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.ast.Expr;
import org.junit.jupiter.api.Test;

/**
 * Every shipped {@code Bindings:} expression round-trips on the expression surface: parsed by
 * {@link CheckExpressionParser}, printed by {@link ExpressionPrinter} and parsed again, it yields
 * the same tree. This is what the loader's display serializer ({@code RulePackageExpressionJson})
 * and every corpus re-spelling script rely on.
 *
 * <p>
 * Runbook W8 ({@code PLAN-retire-operation-surface} D-W8-13): the operation printer / parser pair
 * this test used to pin — and the compiled-binding count ratchet that measured the migration from
 * the operation path (890 → 3838 over W0–W7) — went with the carrier. Every binding is a compiled
 * binding by construction now, so the migration ratchet has no population left to count; the
 * round-trip is the invariant worth keeping. The population floor guards the walk, not a count.
 * </p>
 */
class BindingExpressionRoundTripTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Well below the shipped population (3 838 at W8's go) so a corpus edit never moves it. */
    private static final int MIN_BINDINGS = 3000;

    @Test
    void everyShippedBindingRoundTripsOnTheExpressionSurface() throws Exception
    {
        List<String> expressions = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        try (Stream<Path> files = Files.list(CorpusPaths.rules()))
        {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted().toList())
            {
                collectBindings(MAPPER.readTree(Files.readString(file)), expressions, owners);
            }
        }
        assertTrue(expressions.size() >= MIN_BINDINGS, "expected the shipped corpus to carry at"
                + " least " + MIN_BINDINGS + " bindings, saw " + expressions.size());
        for (int i = 0; i < expressions.size(); i++)
        {
            String expression = expressions.get(i);
            Expr parsed = CheckExpressionParser.parse(expression);
            String printed = ExpressionPrinter.print(parsed);
            assertEquals(parsed, CheckExpressionParser.parse(printed), "round-trip mismatch for `"
                    + expression + "` of " + owners.get(i) + " (printed `" + printed + "`)");
        }
    }


    /**
     * The spellings the ported callables' round-trip used to be pinned by (the retired
     * {@code OperationExpressionPrinterTest}): each prints back to its own text.
     */
    @Test
    void theRegistryCallSpellingsPrintBackToThemselves()
    {
        for (String call : List.of("distinct(--LNKGRP)",
                "distinct(TSVAL, filter=(TSPARMCD == \"INDIC\" and TSVAL == \"Y\"))",
                "distinct(USUBJID, domain=\"DS\", filter=(DSDECOD in [\"RANDOMIZED\", \"SCREEN FAILURE\"]))",
                "distinct([VISIT, VISITNUM], domain=\"TV\")",
                "distinct(VISITNUM, domain=SV, group=[USUBJID, --TESTCD], keep_missings=false)",
                "referenced_dataset_variables(RDOMAIN)",
                "record_count(domain=\"DS\", filter=(DSCAT == \"DISPOSITION EVENT\"))",
                "max_date(DSSTDTC, domain=\"DS\", group=[USUBJID])",
                "not is_sorted_by(--SEQ, by=[asc(--STDTC)], within=USUBJID)"))
        {
            assertEquals(call, ExpressionPrinter.print(CheckExpressionParser.parse(call)), call);
        }
    }


    private static void collectBindings(JsonNode root, List<String> out, List<String> owners)
    {
        JsonNode rules = root.get("rules");
        if (rules == null || !rules.isObject())
        {
            return;
        }
        for (Map.Entry<String, JsonNode> entry : rules.properties())
        {
            JsonNode bindings = entry.getValue().get("Bindings");
            if (bindings == null || !bindings.isArray())
            {
                continue;
            }
            for (JsonNode binding : bindings)
            {
                out.add(binding.path("expression").asText());
                owners.add(entry.getValue().path("Core").path("Id").asText(entry.getKey()));
            }
        }
    }

}
