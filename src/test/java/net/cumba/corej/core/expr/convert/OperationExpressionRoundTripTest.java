package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.model.Operation;
import org.junit.jupiter.api.Test;

/**
 * Proves the Form-B corpus rewrite is loss-free: every field-form {@link Operation} in the shipped
 * {@code rules/} corpus, when serialised by {@link OperationExpressionPrinter} and parsed back by
 * {@link OperationExpressionParser}, reproduces the original operation exactly. Historically the
 * safety net for the retired {@code OperationFormRewriter} / corpus Form-B rewrite; since
 * {@code PLAN-retire-corpus-transforms.md} phase 8 the corpus authors Form B directly and this pins
 * the printer/parser pair the loader and the display serializer still rely on.
 */
class OperationExpressionRoundTripTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Wave 0 ({@code PLAN-binding-expressions} C1): the shipped {@code Bindings} entries that are
     * NOT a single top-level operation call — compiled bindings, which the operation printer/parser
     * pair has nothing to say about — counted across every generated package (a rule ships in one
     * package per standard version it declares). An exact pin, both directions: a new compiled
     * binding must be counted here, and a compiled binding the routing stopped seeing reds too.
     */
    private static final int EXPECTED_COMPILED_BINDINGS = 120;

    /**
     * The rules those compiled bindings belong to. 3 → 120 on 2026-09-28
     * ({@code PLAN-case-insensitive-templates} phase 3b, register CIT §3): the 23 name-list rules
     * gained a compiled sibling {@code $x_upper: upper($x)} that folds the dataset side of the name
     * comparison, one per package the rule ships in (117 instances), beside CG0288's three.
     */
    private static final Set<String> EXPECTED_COMPILED_RULES = Set.of("CDISC-CG0288",
            "CDISC-AD9704", "CDISC-CG0014", "CDISC-CG0016", "CDISC-CG0219", "CDISC-CG0330",
            "CDISC-CG0664", "CDISC-SEND-0012", "CDISC-SEND-0013", "CDISC-SEND-0048",
            "CDISC-SEND-0393", "FDA-SD0054", "FDA-SD0056", "FDA-SD0057", "FDA-SD1079", "FDA-SD1299",
            "FDA-SE0056", "FDA-SE0057", "PMDA-AD0047", "PMDA-SD0054", "PMDA-SD0056", "PMDA-SD0057",
            "PMDA-SD1079", "PMDA-SD1299");

    @Test
    void everyShippedOperationRoundTrips() throws Exception
    {
        Path rulesDir = CorpusPaths.rules();
        List<Operation> all = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        try (Stream<Path> files = Files.list(rulesDir))
        {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted().toList())
            {
                collectOperations(MAPPER.readTree(Files.readString(file)), all, owners);
            }
        }
        assertTrue(all.size() >= 20,
                "expected the shipped corpus to carry operations, saw " + all.size());

        int fieldForm = 0;
        int expressionForm = 0;
        int compiled = 0;
        Set<String> compiledRules = new java.util.TreeSet<>();
        for (int i = 0; i < all.size(); i++)
        {
            Operation orig = all.get(i);
            // ⭐ Wave 0: the population is exactly the bindings the ENGINE routes to the operation
            // path — the one predicate (BindingRouting), never a copy of it. A compiled binding is
            // counted and skipped: pushing it through OperationExpressionParser.normalize would be
            // the "unknown operation function" load error it no longer is.
            if (orig.getOperator() == null
                    && !BindingRouting.routesToOperation(orig.getExpression()))
            {
                compiled++;
                compiledRules.add(owners.get(i));
                continue;
            }
            if (orig.getOperator() == null)
            {
                // EC-51 Half B: this branch used to `continue`, and since the corpus rewrite every
                // shipped operation lands in it — so the guard whose name promises full coverage
                // was asserting nothing at all. An expression-form operation is normalised first,
                // then held to the identical print/parse contract.
                expressionForm++;
                Operation normalizedOnce = OperationExpressionParser.normalize(orig);
                String reprinted = OperationExpressionPrinter.print(normalizedOnce);
                Operation reauthored = new Operation();
                reauthored.setId(normalizedOnce.getId());
                reauthored.setExpression(reprinted);
                assertEquals(normalizedOnce, OperationExpressionParser.normalize(reauthored),
                        "expression-form round-trip mismatch for `" + orig.getExpression() + "`");
                continue;
            }
            fieldForm++;
            String expression = OperationExpressionPrinter.print(orig);
            Operation reauthored = new Operation();
            reauthored.setId(orig.getId());
            reauthored.setExpression(expression);
            Operation normalized = OperationExpressionParser.normalize(reauthored);
            assertEquals(orig, normalized, "round-trip mismatch for `" + expression + "` (operator "
                    + orig.getOperator() + ")");
        }
        assertTrue(expressionForm > 0,
                "the shipped corpus is stored in expression form, so the branch that used to skip"
                        + " it must now be the one doing the work; saw " + fieldForm
                        + " field-form and " + expressionForm + " expression-form");
        assertEquals(EXPECTED_COMPILED_BINDINGS, compiled,
                "compiled (non-operation-call) bindings across the generated packages");
        assertEquals(EXPECTED_COMPILED_RULES, compiledRules);
        assertEquals(all.size() - compiled, fieldForm + expressionForm,
                "every operation-call binding was round-tripped, none skipped");
    }


    @Test
    void listMembershipFilterDeserializesAndRoundTrips() throws Exception
    {
        // E5 (SEND-0167): a field-form operation whose filter carries a JSON array value must
        // deserialize (previously a MismatchedInputException against Map<String,String>) and
        // survive the expression print/parse round-trip.
        String json = """
                {
                  "id": "$scheduled_subjects",
                  "domain": "DS",
                  "name": "USUBJID",
                  "operator": "distinct",
                  "filter": { "DSDECOD": ["TERMINAL SACRIFICE", "INTERIM SACRIFICE"] }
                }""";
        Operation orig = MAPPER.treeToValue(MAPPER.readTree(json), Operation.class);
        assertTrue(orig.getFilter().get("DSDECOD") instanceof List<?>,
                "array filter value must deserialize to a List");
        assertEquals(List.of("TERMINAL SACRIFICE", "INTERIM SACRIFICE"),
                orig.getFilter().get("DSDECOD"));

        String expression = OperationExpressionPrinter.print(orig);
        Operation reauthored = new Operation();
        reauthored.setId(orig.getId());
        reauthored.setExpression(expression);
        Operation normalized = OperationExpressionParser.normalize(reauthored);
        assertEquals(orig, normalized, "list-filter round-trip mismatch for `" + expression + "`");
    }


    @Test
    void ec23_qualifyingAnyPopulatedRoundTrips()
    {
        // EC-23: the has_mixed_emptiness_within_group row-qualifier kwarg must survive the
        // print/parse round-trip (printed as a bareword list `qualifying_any_populated=[BASE,
        // BASEC]`, parsed back to the same List).
        Operation orig = new Operation();
        orig.setId("$mixed");
        orig.setOperator("has_mixed_emptiness_within_group");
        orig.setName("SHIFT1");
        orig.setGroup(List.of("USUBJID", "PARAMCD"));
        orig.setQualifyingAnyPopulated(List.of("BASE", "BASEC"));

        String expression = OperationExpressionPrinter.print(orig);
        assertTrue(expression.contains("qualifying_any_populated=[BASE, BASEC]"),
                "printed form must carry the qualifier list, got `" + expression + "`");

        Operation reauthored = new Operation();
        reauthored.setId(orig.getId());
        reauthored.setExpression(expression);
        Operation normalized = OperationExpressionParser.normalize(reauthored);
        assertEquals(orig, normalized, "qualifier round-trip mismatch for `" + expression + "`");
    }


    @Test
    void ec7_minusLiteralValueRoundTrips()
    {
        // EC-7: the minus literal `value` list minuend must round-trip as a kwarg
        // `value=[AGEU, SDESIGN]` (a positional list is the T3 composite `names` form).
        Operation orig = new Operation();
        orig.setId("$missing");
        orig.setOperator("minus");
        orig.setValue(List.of("AGEU", "SDESIGN"));
        orig.setSubtract("$present");

        String expression = OperationExpressionPrinter.print(orig);
        assertEquals("minus(subtract=$present, value=[AGEU, SDESIGN])", expression);

        Operation reauthored = new Operation();
        reauthored.setId(orig.getId());
        reauthored.setExpression(expression);
        Operation normalized = OperationExpressionParser.normalize(reauthored);
        assertEquals(orig, normalized, "minus value round-trip mismatch for `" + expression + "`");
    }


    @Test
    void ec18_dateDiffDaysMode3MinuendFieldsRoundTrip()
    {
        // EC-18 / P5c: date_diff_days Mode 3 carries minuend_domain + the sided minuend_match key
        // list (with a `--SPID` token). Both must survive the native print/parse round-trip.
        Operation orig = new Operation();
        orig.setId("$days");
        orig.setOperator("date_diff_days");
        orig.setName("PMDTC");
        orig.setMinuendDomain("PM");
        orig.setMinuendMatch(List.of("USUBJID", "--SPID"));
        orig.setDomain("EX");
        orig.setReference("EXSTDTC");
        orig.setGroup(List.of("USUBJID"));
        orig.setOffset("1");

        String expression = OperationExpressionPrinter.print(orig);
        assertTrue(expression.contains("minuend_domain=\"PM\""),
                "printed form must carry minuend_domain, got `" + expression + "`");
        assertTrue(expression.contains("minuend_match=[USUBJID, --SPID]"),
                "printed form must carry the minuend_match list, got `" + expression + "`");

        Operation reauthored = new Operation();
        reauthored.setId(orig.getId());
        reauthored.setExpression(expression);
        Operation normalized = OperationExpressionParser.normalize(reauthored);
        assertEquals(orig, normalized,
                "date_diff_days Mode 3 round-trip mismatch for `" + expression + "`");
    }


    @Test
    void ec51_missingValuesRoundTrips()
    {
        // EC-51 Half B: the missing-candidate disposition must survive the print/parse round-trip.
        // Omitted when null, so a corpus that declares it nowhere prints byte-identically.
        Operation orig = new Operation();
        orig.setId("$min_ex");
        orig.setOperator("min_date");
        orig.setName("EXSTDTC");
        orig.setDomain("EX");
        orig.setGroup(List.of("USUBJID"));
        orig.setMissingValues(Operation.MISSING_VALUES_INDETERMINATE);

        String expression = OperationExpressionPrinter.print(orig);
        assertTrue(expression.contains("missing_values=\"indeterminate\""),
                "printed form must carry the disposition, got `" + expression + "`");

        Operation reauthored = new Operation();
        reauthored.setId(orig.getId());
        reauthored.setExpression(expression);
        assertEquals(orig, OperationExpressionParser.normalize(reauthored),
                "missing_values round-trip mismatch for `" + expression + "`");

        Operation undeclared = new Operation();
        undeclared.setOperator("min_date");
        undeclared.setName("EXSTDTC");
        assertEquals("min_date(EXSTDTC)", OperationExpressionPrinter.print(undeclared),
                "an undeclared disposition must print nothing at all");
    }


    /**
     * <b>The printer half of the reflective registration guard</b> (its parser, copy-routine and
     * Jackson halves live in {@code OperationFieldRegistrationTest}, in {@code corej-core}, which
     * cannot see this printer).
     *
     * <p>
     * {@link OperationExpressionPrinter#print} is a hand-written field-by-field emitter, and the
     * corpus in {@code rules/} is stored <em>entirely</em> in the form it produces — so a field it
     * forgets is erased from every rule the next {@code regen-rules-corpus.sh} run touches, with no
     * error anywhere. This enumerates {@link Operation}'s declared fields reflectively and asserts
     * each one reaches the printed string, so the next field cannot be lost the way {@code offset}
     * was.
     * </p>
     */
    @Test
    void everyOperationFieldIsEmittedByThePrinter() throws Exception
    {
        // `name` and `names` are mutually exclusive by construction — the printer emits whichever
        // is set into the single positional slot — so the sweep runs twice, once per shape.
        assertEveryFieldPrinted(false);
        assertEveryFieldPrinted(true);
    }


    private static void assertEveryFieldPrinted(boolean composite) throws Exception
    {
        Operation op = new Operation();
        op.setOperator("date_diff_days");
        List<java.lang.reflect.Field> fields = new ArrayList<>();
        for (java.lang.reflect.Field f : Operation.class.getDeclaredFields())
        {
            if (f.isSynthetic() || java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    || f.getAnnotation(com.fasterxml.jackson.annotation.JsonIgnore.class) != null
                    // Not printed by design: `operator` IS the function name, `id` is carried
                    // alongside the call, and `expression` is the very text being produced.
                    || List.of("operator", "id", "expression").contains(f.getName())
                    || (composite ? "name" : "names").equals(f.getName()))
            {
                continue;
            }
            f.setAccessible(true);
            f.set(op, sentinelFor(f));
            fields.add(f);
        }
        assertTrue(fields.size() >= 30,
                "expected the full Operation field set, saw " + fields.size());

        String printed = OperationExpressionPrinter.print(op);
        List<String> dropped = new ArrayList<>();
        for (java.lang.reflect.Field f : fields)
        {
            String key = jsonKey(f);
            // ⚠ Anchored on the argument separator, never a bare `contains(key + "=")`: the
            // printer joins parts with ", " after a leading "(", and a plain substring test would
            // let `key_value="…"` stand in for a dropped `value=` (and `name_pattern=` for a
            // dropped `name=`). Sentinels are suffixed `_END` for the same reason — `SENTINEL_name`
            // is a prefix of `SENTINEL_namePattern`, so the positional check below was passing on
            // an unrelated kwarg.
            boolean emitted = List.of("name", "names").contains(f.getName())
                    // The positional slot carries no `key=` prefix.
                    ? printed.contains("\"SENTINEL_" + f.getName() + "_END\"") || printed
                            .contains(f.getName().toUpperCase(java.util.Locale.ROOT) + "1")
                    : printed.contains("(" + key + "=") || printed.contains(", " + key + "=");
            if (!emitted)
            {
                dropped.add(key);
            }
        }
        assertTrue(dropped.isEmpty(), "OperationExpressionPrinter.print omitted " + dropped
                + " — the shipped rules/ corpus is stored in this form, so a field it drops is"
                + " erased from every rule on the next corpus regeneration. Printed: " + printed);
    }


    private static String jsonKey(java.lang.reflect.Field f)
    {
        com.fasterxml.jackson.annotation.JsonProperty annotation = f
                .getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
        return annotation != null && !annotation.value().isEmpty() ? annotation.value()
                : f.getName();
    }


    private static Object sentinelFor(java.lang.reflect.Field f)
    {
        Class<?> type = f.getType();
        if (type == Integer.class)
        {
            return 200;
        }
        if (type == Boolean.class)
        {
            return true;
        }
        if (type == List.class)
        {
            String upper = f.getName().toUpperCase(java.util.Locale.ROOT);
            return List.of(upper + "1", upper + "2");
        }
        if (type == java.util.Map.class)
        {
            return java.util.Map.of("FILTERCOL", "FILTERVAL");
        }
        return "missingValues".equals(f.getName()) ? Operation.MISSING_VALUES_INDETERMINATE
                : "SENTINEL_" + f.getName() + "_END";
    }


    private static void collectOperations(JsonNode root, List<Operation> out, List<String> owners)
        throws Exception
    {
        JsonNode rules = root.get("rules");
        if (rules == null || !rules.isObject())
        {
            return;
        }
        for (java.util.Map.Entry<String, JsonNode> entry : rules.properties())
        {
            JsonNode ops = entry.getValue().get("Bindings");
            if (ops == null || !ops.isArray())
            {
                continue;
            }
            for (JsonNode opNode : ops)
            {
                // 7b: a shipped Bindings entry is {name, expression} — `name` is the binding's
                // $-variable (Operation.id, the internal carrier's historical field name), NOT
                // the Operation.name target parameter, so it must not bind via treeToValue.
                Operation op = new Operation();
                op.setId(opNode.path("name").asText(null));
                op.setExpression(opNode.path("expression").asText(null));
                out.add(op);
                owners.add(entry.getValue().path("Core").path("Id").asText(entry.getKey()));
            }
        }
    }

}
