package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.convert.BindingInliner;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-binding-substitutability} §6 (1), runbook W1a BS0 — the <b>corpus census</b>: every
 * shipped rule with bindings is loaded a second time with every binding inlined by
 * {@link BindingInliner#inline(Expr, Rule)} into the Check, the Precondition and the later bindings
 * (the bindings themselves stay declared, so {@code Output_Variables} keep their names), and the
 * inlined rule must load exactly as the original does — clean. D3 / D16 (G1): a binding is only a
 * name for its expression. A rule whose inlined form earns a load error is a row of
 * {@link #EXPECTED}, an EXACT roster by rule id and reason (memory
 * {@code count-pin-blind-to-rekey}): the declared exceptions stay (C15, a per-variable binding is a
 * cursor, not substitutable by construction); the gaps the W1a units BS1–BS3 widen leave with those
 * units; a new row is a new candidate for the plan's §3 table.
 *
 * <p>
 * Non-vacuity, on every run: a synthetic rule whose binding is a per-variable call is seen by the
 * same collector (its inlined form is the C15 refusal), every package is walked
 * ({@link #PACKAGE_COUNT} as the sibling censuses) and the number of rules with bindings clears a
 * floor.
 * </p>
 */
class BindingSubstitutabilityCorpusCensusTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The generated corpus has EXACTLY this many packages (as the sibling censuses pin). */
    private static final int PACKAGE_COUNT = 47;

    /** Floor of rules with bindings the census must have inlined — the corpus was walked. */
    private static final int BOUND_RULES_FLOOR = 500;

    /**
     * The recorded rows {@code <rule id> | <reason>} — measured at clone HEAD after W1a U3
     * (2026-10-07), classified against {@code PLAN-binding-substitutability} §3:
     * <ul>
     * <li><b>C15 — declared exception (Q5):</b> CDISC-AD0002 / AD0085 / AD0086 / AD0199 / AD0590
     * bind {@code cross_dataset_variable_metadata(…)}, a per-variable map that IS the rule's
     * cursor; inline it is refused by {@code rejectInlinePerVariableCalls}. Stays.</li>
     * <li><b>C4 — LEFT with W1a BS2 (splice slots, ruled Q1 / Q2):</b> CDISC-CG0562 ({@code
     * record_count(group=[…, $TIMING_VARIABLES])}), FDA-SD1117 / PMDA-SD1117 ({@code
     * is_unique_set([…, $natural_key])}), PMDA-SD1152 ({@code is_unique_set([STUDYID,
     * $define_key_variables])}) — until BS2 the {@code $} member spliced while its expression
     * written as the member was {@code PARAMETER_TYPE} (G3 arm) or the {@code record_count}
     * reader's "plain column reference"; since BS2 a dataset-level expression of names splices
     * exactly as the {@code $} member does ({@code GroupSplice.Member}).</li>
     * <li><b>NEW candidate (not in §3), reported by BS0:</b> CDISC-CG0022 — {@code
     * shares_no_elements_with} takes its operand as a {@code $}-reference only ("requires a
     * reference name operand"), so the binding {@code $relrec_links_lnkid} has no native form once
     * its operands are inlined. A shape refusal of the C1 kind on another reader; its fix is a plan
     * decision, not taken here.</li>
     * </ul>
     */
    private static final Set<String> EXPECTED = new TreeSet<>(Set.of(
            "CDISC-AD0002 | cross_dataset_variable_metadata answers one value per variable and is read only through a binding: that binding is the rule's variable cursor",
            "CDISC-AD0085 | cross_dataset_variable_metadata answers one value per variable and is read only through a binding: that binding is the rule's variable cursor",
            "CDISC-AD0086 | cross_dataset_variable_metadata answers one value per variable and is read only through a binding: that binding is the rule's variable cursor",
            "CDISC-AD0199 | cross_dataset_variable_metadata answers one value per variable and is read only through a binding: that binding is the rule's variable cursor",
            "CDISC-AD0590 | cross_dataset_variable_metadata answers one value per variable and is read only through a binding: that binding is the rule's variable cursor",
            "CDISC-CG0022 | binding `$relrec_links_lnkid` has no native expression form: Expression construct not supported by the native evaluator: shares_no_elements_with requires a reference name operand"));

    @Test
    void everyShippedRuleLoadsAlikeWithItsBindingsInlined() throws Exception
    {
        // NON-VACUITY: a per-variable binding (C15) inlined is the cursor refusal.
        Set<String> control = new TreeSet<>();
        ObjectNode controlRule = (ObjectNode) MAPPER.readTree("{\"Core\":{\"Id\":\"BS0-CTRL\"},"
                + "\"Bindings\":[{\"name\":\"$v\",\"expression\":"
                + "\"cross_dataset_variable_metadata(\\\"label\\\", domain=\\\"DM\\\")\"}],"
                + "\"Check\":{\"expression\":\"not empty($v)\"},\"Outcome\":{\"Message\":\"m\"}}");
        Rule original = load("c", controlRule);
        assertNull(original.getLoadError(),
                "the control's bound form loads: " + original.getLoadError());
        census("c", controlRule, original, control);
        assertEquals(1, control.size(),
                "the control must be seen — else the census proves nothing: " + control);
        assertTrue(control.iterator().next().startsWith("BS0-CTRL | "), control.toString());

        List<Path> packages;
        try (Stream<Path> files = Files.list(CorpusPaths.rules()))
        {
            packages = files.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.toString().endsWith(".json")).sorted().toList();
        }
        assertEquals(PACKAGE_COUNT, packages.size(), "the generated corpus is complete");
        Set<String> rows = new TreeSet<>();
        int bound = 0;
        for (Path pkg : packages)
        {
            Map<String, Rule> loaded = RulePackageLoader.load(pkg).getRules();
            JsonNode rules = MAPPER.readTree(pkg.toFile()).get("rules");
            for (Iterator<Map.Entry<String, JsonNode>> it = rules.fields(); it.hasNext();)
            {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode bindings = e.getValue().get("Bindings");
                if (bindings == null || !bindings.isArray() || bindings.isEmpty())
                {
                    continue;
                }
                Rule rule = loaded.get(e.getKey());
                assertNotNull(rule, e.getKey() + " in " + pkg);
                if (rule.getLoadError() != null)
                {
                    continue; // the sibling census asserts there is none
                }
                bound++;
                census(e.getKey(), (ObjectNode) e.getValue(), rule, rows);
            }
        }
        assertTrue(bound >= BOUND_RULES_FLOOR, "rules with bindings inlined: " + bound);
        assertEquals(EXPECTED, rows, () -> "the corpus substitutability census moved (D3 / D16):\n"
                + String.join("\n", rows));
    }


    /** Loads {@code node} with every binding inlined and records a load error as a row. */
    private static void census(String key, ObjectNode node, Rule rule, Set<String> rows)
        throws Exception
    {
        ObjectNode inlined = node.deepCopy();
        for (String section : List.of("Check", "Precondition"))
        {
            JsonNode s = inlined.get(section);
            if (s != null)
            {
                rewrite(s, rule);
            }
        }
        for (JsonNode b : (ArrayNode) inlined.get("Bindings"))
        {
            rewrite(b, rule);
        }
        Rule reloaded = load(key, inlined);
        String error = reloaded.getLoadError();
        if (error != null)
        {
            rows.add(rule.effectiveId() + " | " + reason(error));
        }
    }


    /** Every {@code "expression"} string under {@code node}, replaced by its inlined print. */
    private static void rewrite(JsonNode node, Rule rule)
    {
        if (node instanceof ObjectNode o)
        {
            JsonNode expression = o.get("expression");
            if (expression != null && expression.isTextual())
            {
                Expr parsed = CheckExpressionParser.parse(expression.asText());
                Expr inlined = BindingInliner.inline(parsed, rule);
                if (inlined != null && inlined != parsed)
                {
                    o.put("expression", ExpressionPrinter.print(inlined));
                }
            }
            for (Iterator<JsonNode> it = o.elements(); it.hasNext();)
            {
                rewrite(it.next(), rule);
            }
        }
        else if (node instanceof ArrayNode a)
        {
            for (JsonNode item : a)
            {
                rewrite(item, rule);
            }
        }
    }


    /** The error's reason without the printed expression — the roster key stays stable. */
    private static String reason(String error)
    {
        String head = error.split("\n", 2)[0];
        int cut = head.length();
        for (String opener : List.of("`", " ("))
        {
            int at = head.indexOf(opener, 40);
            if (at > 0)
            {
                cut = Math.min(cut, at);
            }
        }
        return head.substring(0, Math.min(cut, 200)).strip();
    }


    private static Rule load(String key, ObjectNode node) throws Exception
    {
        ObjectNode rules = MAPPER.createObjectNode();
        rules.set(key, node);
        ObjectNode pkg = MAPPER.createObjectNode();
        // through set(…): the corpus path guard reads it as the rules MAP, not a directory
        pkg.set("rules", rules);
        Rule loaded = RulePackageLoader.loadFromString(MAPPER.writeValueAsString(pkg)).getRules()
                .get(key);
        assertNotNull(loaded, "the rule parses: " + key);
        return loaded;
    }
}
