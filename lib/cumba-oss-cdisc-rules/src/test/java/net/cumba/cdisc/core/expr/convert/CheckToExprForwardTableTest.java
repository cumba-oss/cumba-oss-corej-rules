package net.cumba.cdisc.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.List;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.expr.ExpressionPrinter;
import net.cumba.cdisc.core.model.CheckCondition;
import org.junit.jupiter.api.Test;

/**
 * Synthetic fixtures guarding {@code CheckToExpr}'s forward rewrite table through the production
 * raise ({@code CheckToExpr.toExpr}, the same raise {@code RulePackageLoader.tryRaiseToExpr} runs
 * on every loaded rule; until {@code PLAN-retire-corpus-transforms.md} phase 1 the fixtures were
 * routed through {@code RulePackageConverter.convertCheck}, whose leaf-conversion machinery is now
 * retired) — the flip-day re-base of the corpus-driven protection that retired with the flip (wave
 * 43, {@code PLAN-rules-src-expression-flip.md} §7b): {@code SrcFaithfulnessScan/Test}'s 99-id
 * REWRITTEN pin, {@code ConverterCoverageTest}'s kept-old-style scoreboard and
 * {@code SpecialCaseLeafSignatureTest}'s raw-text asserts all measured the raise over the
 * <em>authored operator-leaf corpus</em>, which no longer exists — {@code rules-src} authors
 * expressions now, and the production pipeline keeps them verbatim ({@code convertExisting}). These
 * fixtures are the surviving executable record of what the raise rewrites; each one is a real
 * pre-flip authored Check whose expected text is the expression the flip wrote for it
 * (byte-compared against {@code rules-src} at the flip).
 *
 * <p>
 * The families pinned: {@code variable_name}-regex → {@code varname()} affix predicates ·
 * per-record accessor demotions ({@code variable_value} → {@code value()}, {@code variable_size} →
 * {@code var_length("DATA")}, {@code variable_max_size} → {@code max_value_length(…)}) ·
 * {@code suffix_matches_regex} forms · the numeric-pattern restore ({@code not_matches_regex}
 * integer pattern → {@code not is_integer}) · case-insensitive membership folds (the retired
 * converter's {@code does_not_equal_string_part} leaf-signature rewrite is no longer pinned here —
 * its two carriers' authored texts are pinned in {@code RuleSrcExpressionWriterCorpusTest}, and
 * {@code CheckToExpr} raises the leaf to its like-named function call instead) · the explicit
 * presence family in leaf form (the generic {@code exists} is retired — phase 1 of
 * {@code PLAN-leaf-scope-domain-inference.md} — so a leaf spells {@code var_exists} /
 * {@code ds_exists} itself).
 * </p>
 *
 * <p>
 * ⚠ <b>The raise is HOT-PATH production code, not residue</b> — the wave-43 close's §8 grounding
 * measured it: {@code RulePackageLoader.tryRaiseToExpr} re-raises every loaded rule's deserialized
 * Check (and Preconditions, six call sites) to the {@code Expr} IR, and
 * {@code RuleClassifier.toExprOrNull} raises during every Rule_Type/Sensitivity derivation — so
 * these fixtures guard live load-path behaviour, and the plan's §8 "retire the leaf-input path"
 * premise is refuted (recorded in {@code PLAN-rules-src-expression-flip.md} §8). The wave-43
 * review's coverage gap was closed accordingly: the {@code prefix_matches_regex},
 * upper-equality-fold ({@code equalsIgnoreCase}), {@code has_equal_length} and positive
 * case-insensitive-membership families are pinned below. Still un-pinned, with no pre-flip corpus
 * instance to harvest: the first-letter range restore, {@code char()} demotions and the
 * {@code is_sorted_by} forms (synthetic-only territory — G0c's corpus covers their parse/print
 * side).
 * </p>
 */
class CheckToExprForwardTableTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** One forward-table pin: a legacy authored Check and the exact expression it raises to. */
    private record Fixture(String label, String legacyCheckYaml, String expected)
    {
    }

    /**
     * Real pre-flip corpus shapes (source id in each label); expected text is what the flip wrote.
     */
    private static final List<Fixture> FIXTURES = List.of(new Fixture(
            "variable_name-regex + accessor demotions (CDISC-AD0005)", """
                    all:
                    - name: "variable_name"
                      operator: "matches_regex"
                      value: "^.+FL$"
                    - name: "variable_value"
                      operator: "non_empty"
                    - name: "variable_value"
                      operator: "is_not_contained_by"
                      value:
                      - "Y"
                      - "N"
                    """,
            "ends_with(varname(), \"FL\") and not empty(value()) and value() not in [\"Y\", \"N\"]"),
            new Fixture("suffix_matches_regex forms (CDISC-CG0140)", """
                    all:
                    - name: "DM.RACE"
                      operator: "not_equal_to"
                      value: "MULTIPLE"
                      value_is_literal: true
                    - name: "QNAM"
                      operator: "starts_with"
                      value: "RACE"
                      value_is_literal: true
                    - name: "QNAM"
                      operator: "suffix_matches_regex"
                      suffix: 1
                      value: "^[A-Z0-9]"
                    - name: "QNAM"
                      operator: "not_suffix_matches_regex"
                      suffix: 3
                      value: "(OTH)"
                    """,
                    "DM.RACE != \"MULTIPLE\" and starts_with(QNAM, \"RACE\") and suffix(QNAM, 1)"
                            + " =~ /^[A-Z0-9]$/ and suffix(QNAM, 3) !~ /^(OTH)$/"),
            new Fixture("column-regex suffix + integer-pattern restore (DRAFT-900007)", """
                    all:
                    - name: "IDVAR"
                      operator: "matches_regex"
                      value: ".*SEQ$"
                    - name: "IDVARVAL"
                      operator: "non_empty"
                    - name: "IDVARVAL"
                      operator: "not_matches_regex"
                      value: "^\\\\s*[+-]?[0-9]+\\\\s*$"
                    """,
                    "suffix(IDVAR, 3) == \"SEQ\" and not empty(IDVARVAL)"
                            + " and not is_integer(IDVARVAL)"),
            new Fixture("case-insensitive membership fold (CORE-000041 shape)", """
                    all:
                    - name: "TSVAL"
                      operator: "non_empty"
                    - name: "TSVAL"
                      operator: "is_not_contained_by_case_insensitive"
                      value:
                      - "NI"
                      - "No information"
                    """, "not empty(TSVAL) and upper(TSVAL) not in [\"NI\", \"NO INFORMATION\"]"),
            new Fixture("variable_size demotion (FDA-SE2277)", """
                    all:
                    - name: "variable_size"
                      operator: "greater_than"
                      value: 200
                      value_is_literal: true
                    """, "var_length(\"DATA\") > 200"),
            new Fixture("variable_max_size demotion (FDA-SD1082)", """
                    all:
                    - name: "variable_data_type"
                      operator: "equal_to"
                      value: "Char"
                      value_is_literal: true
                    - name: "variable_size"
                      operator: "not_equal_to"
                      value: "variable_max_size"
                    """,
                    "var_type(\"DATA\") == \"Char\" and var_length(\"DATA\")"
                            + " != max_value_length(variable_name)"),
            new Fixture("a var_exists leaf raises to the call (§5)", """
                    all:
                    - name: "BASETYPE"
                      operator: "var_exists"
                    """, "var_exists(\"BASETYPE\")"),
            new Fixture("a var_not_exists leaf raises to its own call — the writer canonicalises"
                    + " it to `not var_exists` (PresenceSpelling), the raise does not", """
                            all:
                            - name: "BASETYPE"
                              operator: "var_not_exists"
                            """, "var_not_exists(\"BASETYPE\")"),
            new Fixture(
                    "prefix_matches_regex pair + presence leaves inside any-of-alls (CORE-000107)",
                    """
                            any:
                            - all:
                              - name: "DOMAIN"
                                operator: "prefix_matches_regex"
                                prefix: 2
                                value: "(AP|ap)"
                              - name: "APID"
                                operator: "var_not_exists"
                            - all:
                              - name: "DOMAIN"
                                operator: "not_prefix_matches_regex"
                                prefix: 2
                                value: "(AP|ap)"
                              - name: "STUDYID"
                                operator: "var_exists"
                              - name: "USUBJID"
                                operator: "var_not_exists"
                            """,
                    "prefix(DOMAIN, 2) =~ /^(AP|ap)$/ and var_not_exists(\"APID\")"
                            + " or prefix(DOMAIN, 2) !~ /^(AP|ap)$/ and var_exists(\"STUDYID\")"
                            + " and var_not_exists(\"USUBJID\")"),
            new Fixture("upper-equality fold (CDISC-CG0202)", """
                    all:
                    - name: "QORIG"
                      operator: "equal_to_case_insensitive"
                      value: "ASSIGNED"
                      value_is_literal: true
                    - name: "QEVAL"
                      operator: "empty"
                    """, "equalsIgnoreCase(QORIG, \"ASSIGNED\") and empty(QEVAL)"),
            new Fixture("has_equal_length becomes a len comparison (PMDA-SD1096)", """
                    all:
                    - name: "--TERM"
                      operator: "has_equal_length"
                      value: 200
                    """, "len(--TERM) == 200"),
            new Fixture("positive case-insensitive membership fold (CDISC-CG0138)", """
                    all:
                    - name: "QNAM"
                      operator: "is_contained_by_case_insensitive"
                      value:
                      - "COMPLT"
                      - "FULLSET"
                      - "ITT"
                      - "PPROT"
                      - "SAFETY"
                    """,
                    "upper(QNAM) in [\"COMPLT\", \"FULLSET\", \"ITT\", \"PPROT\", \"SAFETY\"]"));

    @Test
    void theForwardTableRaisesEachFixtureToExactlyTheRecordedExpression() throws Exception
    {
        for (Fixture fixture : FIXTURES)
        {
            JsonNode legacy = YAML.readTree(fixture.legacyCheckYaml());
            CheckCondition bound = YAML.treeToValue(legacy, CheckCondition.class);
            assertNotNull(bound, fixture.label());
            assertEquals(fixture.expected(), ExpressionPrinter.print(CheckToExpr.toExpr(bound)),
                    fixture.label());
        }
    }


    /** The pinned texts must be canonical, or the writer would have normalised them away. */
    @Test
    void everyExpectedExpressionIsAPrintParseFixedPoint()
    {
        for (Fixture fixture : FIXTURES)
        {
            assertEquals(fixture.expected(),
                    ExpressionPrinter.print(CheckExpressionParser.parse(fixture.expected())),
                    fixture.label() + ": expected text is not canonical");
        }
    }
}
