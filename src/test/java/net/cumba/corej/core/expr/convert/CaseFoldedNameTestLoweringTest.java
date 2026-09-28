package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * CIT &#167;3, phase 3c of {@code PLAN-case-insensitive-templates} (2026-09-28): a rule tests a
 * column NAME as a string with the dataset side folded ({@code ends_with(upper(varname()), "FL")},
 * {@code upper(varname()) =~ /^ANL/}, {@code prefix(upper(varname()), 2) not in $x}), and
 * {@link DualFormCheckNode} lowers the folded subject to the SAME leaf as the unwrapped form — the
 * Java mirror of {@code scripts/lib/check_expr.py}'s {@code _case_folded_subject}, whose own suite
 * is {@code generated/rule-inventory/test_case_folded_name_tests.py}.
 *
 * <p>
 * Without that arm every folded rule lowered to {@code null} and silently left the instruments
 * built on this class ({@code RuleDocCensus}, {@code RuleFingerprint}, the equivalence inventory).
 * The corpus test below compares every folded rule's lowering with that of its textually unfolded
 * twin, so the claim "the fold is invisible to the leaf-derived instruments" is measured on the
 * shipped rules, not argued; the population floor keeps it from passing over an empty corpus.
 * </p>
 */
class CaseFoldedNameTestLoweringTest
{

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** Folded rules measured 2026-09-28 (both corpora); a floor, the corpus may grow. */
    private static final int MIN_FOLDED_RULES = 112;

    private static final Pattern FOLDED_TEST = Pattern.compile(
            "upper\\(varname\\(\\)\\)\\s*[=!]~|(ends_with|starts_with)\\(upper\\(varname\\(\\)\\),"
                    + "|prefix\\(upper\\(varname\\(\\)\\), ");

    private static JsonNode check(String expression)
    {
        ObjectNode node = NODES.objectNode();
        node.put("expression", expression);
        return node;
    }


    @ParameterizedTest
    @CsvSource(delimiter = '|', value =
    {
            "ends_with(upper(varname()), \"FL\")|ends_with(varname(), \"FL\")",
            "starts_with(lower(varname()), \"AE\")|starts_with(varname(), \"AE\")",
            "not contains(upper(varname()), \"DT\")|not contains(varname(), \"DT\")",
            "upper(varname()) =~ /^ANL[0-9]{2}FL$/|varname() =~ /^ANL[0-9]{2}FL$/",
            "upper(varname()) !~ /^TRTSTMF$/|varname() !~ /^TRTSTMF$/",
            "upper(var_name(\"DATA\")) =~ /DTC$/|var_name(\"DATA\") =~ /DTC$/",
            "prefix(upper(varname()), 2) not in $domain_list|prefix(varname(), 2) not in $domain_list"
    })
    void aFoldedNameSubjectLowersLikeTheUnwrappedForm(String folded, String plain)
    {
        JsonNode expected = DualFormCheckNode.lowerOrNull(check(plain));
        assertNotNull(expected, plain);
        assertEquals(expected, DualFormCheckNode.lowerOrNull(check(folded)), folded);
    }


    @Test
    void aFoldedCellValueAndOtherShapesStayUnlowerable()
    {
        // A folded CELL has its own case-insensitive v1 leaf family; a plain leaf would misstate
        // it. Both shapes must lower exactly as before the name arm existed: not at all.
        assertNull(
                DualFormCheckNode.lowerOrNull(check("not contains(upper(VISIT), \"UNSCHEDUL\")")));
        assertNull(DualFormCheckNode.lowerOrNull(check("upper(varname()) == \"AESEQ\"")));
    }


    @Test
    void everyFoldedRuleLowersLikeItsUnfoldedTwin() throws IOException
    {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of("rules-src", "checks")))
        {
            files = walk.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        assertTrue(files.size() > 1000, "void run: rules-src/checks not found");
        List<String> folded = new ArrayList<>();
        for (Path file : files)
        {
            JsonNode checkNode = RuleCanonicalizer.YAML_READ.readTree(file.toFile()).get("Check");
            if (checkNode == null || !checkNode.has("expression"))
            {
                continue;
            }
            String expression = checkNode.get("expression").asText();
            if (!FOLDED_TEST.matcher(expression).find())
            {
                continue;
            }
            String unfolded = expression
                    .replaceAll("upper\\(varname\\(\\)\\)(\\s*)([=!]~)", "varname()$1$2")
                    .replaceAll("(ends_with|starts_with)\\(upper\\(varname\\(\\)\\),",
                            "$1(varname(),")
                    .replaceAll("prefix\\(upper\\(varname\\(\\)\\), ", "prefix(varname(), ");
            assertEquals(DualFormCheckNode.lowerOrNull(check(unfolded)),
                    DualFormCheckNode.lowerOrNull(check(expression)), file.toString());
            folded.add(file.getFileName().toString());
        }
        assertTrue(folded.size() >= MIN_FOLDED_RULES, "only " + folded.size()
                + " folded name-test rule(s) found; " + MIN_FOLDED_RULES + " measured 2026-09-28");
    }
}
