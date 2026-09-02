package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.cdisc.core.CorpusPaths;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code AbsentDatasetSkip.resolvesExactOnly} asserted over the <b>rule the product ships</b>, not
 * over a rule the test authored.
 *
 * <p>
 * <b>Why this class exists.</b> {@code CORE-000208} is the sole live carrier of the exact-name
 * exception: its entire {@code TA} dependency is {@code Operations[].domain}, a surface that still
 * resolves by exact name downstream, so its {@code Requirements.Datasets: ["TA"]} must be checked
 * with the exact-name predicate rather than the split-widened {@code isPresentAsDomain}. Getting
 * that backwards un-skips the rule on a split submission and evaluates it against an empty operand
 * — the {@code W34-C1} flood shape.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>Every pre-existing fixture for this partition hand-authors the wrong form.</b>
 * {@code RuleRunnerRequirementsDatasetsTest.operationsDomainOnlyEntryUsesExactName} builds its rule
 * in operation <b>Form A</b> ({@code "operator":"distinct"}, {@code "domain":"TA"}), while the
 * shipped record carries <b>Form B</b> — {@code expression: distinct(ARMCD, domain="TA")} with no
 * {@code operator} and no {@code domain} field at all. The two are equivalent only because
 * {@code RulePackageLoader.normalizeOperations} runs first and rewrites Form B into the field form
 * the partition reads. Nothing pinned that: re-authoring this rule onto a dotted read, or moving
 * {@code normalizeOperations} later in {@code finishLoad}, flips {@code TA} to the widened
 * predicate with a green suite. This test closes that by loading the shipped package through the
 * production loader and asserting on the record that comes out.
 * </p>
 */
class ShippedExactNameRequirementTest
{

    private static final Path SHIPPED_CORE_SDTMIG_3_3 = CorpusPaths
            .rules("rules-core-sdtmig-3-3.json");

    private static final String RULE_ID = "CORE-000208";

    private static final String DATASET = "TA";

    private static Rule shippedRule() throws Exception
    {
        RulePackage pkg = RulePackageLoader.load(SHIPPED_CORE_SDTMIG_3_3);
        Rule rule = pkg.getRules().get(RULE_ID);
        assertNotNull(rule, RULE_ID + " is not in " + SHIPPED_CORE_SDTMIG_3_3);
        assertNull(rule.getLoadError(),
                () -> "shipped rule carries a load error: " + rule.getLoadError());
        return rule;
    }


    /**
     * ⭐ The behaviour pin: the shipped rule's declared secondary resolves by <b>exact</b> name.
     */
    @Test
    @DisplayName("the SHIPPED CORE-000208 resolves TA exact-only, not split-widened")
    void theShippedRuleResolvesItsDeclaredDatasetByExactName() throws Exception
    {
        Rule rule = shippedRule();
        assertEquals(List.of(DATASET), rule.getRequirements().getDatasets(),
                "the fixture's premise is that this rule declares Requirements.Datasets: [TA]");
        assertTrue(AbsentDatasetSkip.resolvesExactOnly(rule, DATASET),
                "CORE-000208 reaches TA only through Operations[].domain, which still resolves by"
                        + " exact name downstream — checking the requirement with the widened"
                        + " isPresentAsDomain would un-skip the rule on a split submission and"
                        + " evaluate it against an empty operand");
    }


    /**
     * ⚠ The half that stops the test above going vacuous. It asserts the rule reaches {@code TA}
     * through the {@code Operations[].domain} surface <em>and no other</em> — no
     * {@code Match_Datasets}, no dotted {@code TA.…} read in the Check. Add either surface and
     * {@code resolvesExactOnly} correctly answers {@code false}; the assertion above would then be
     * testing a rule that no longer has the shape it exists to protect.
     */
    @Test
    @DisplayName("⚠ …and it is exact-only for the right reason — one surface, no join, no dotted"
            + " read")
    void itIsExactOnlyBecauseOperationsDomainIsItsOnlySurface() throws Exception
    {
        Rule rule = shippedRule();
        assertNull(rule.getMatchDatasets(),
                "a join site would widen TA and this rule would stop being the exception");
        List<Operation> operations = rule.getOperations();
        assertNotNull(operations);
        assertEquals(1, operations.size());
        assertEquals(DATASET, operations.get(0).getDomain(),
                "Operations[0].domain is the surface the partition reads");
        assertNotNull(rule.getCheckExpr(), "the dotted walk needs a raised Check");
        assertFalse(rule.getCheck().toString().contains(DATASET + "."),
                "a dotted TA.<column> read would widen the name too");
    }


    /**
     * ⚠⚠ <b>The trap this class was written for.</b> The shipped record authors the operation in
     * <b>Form B</b> — an {@code expression} with no {@code operator} and no {@code domain} field.
     * {@code Operation.getDomain()} is {@code null} on the raw document and only becomes {@code TA}
     * because {@code RulePackageLoader.normalizeOperations} rewrites it at {@code finishLoad}.
     * Every other fixture for this partition authors Form A and therefore proves nothing about the
     * shipped shape. This reads the file as JSON, before any loader pass, so the dependency is
     * stated rather than assumed.
     */
    @Test
    @DisplayName("⚠ the shipped operation is Form B — the exact-name answer depends on"
            + " normalizeOperations")
    void theShippedOperationIsExpressionFormAndNeedsNormalisation() throws Exception
    {
        JsonNode raw = new ObjectMapper().readTree(Files.readString(SHIPPED_CORE_SDTMIG_3_3))
                .get("rules").get(RULE_ID).get("Operations").get(0);
        assertTrue(raw.has("expression"), "shipped operation is expression-form (Form B): " + raw);
        assertFalse(raw.has("operator"),
                "Form B carries no operator until normalizeOperations runs: " + raw);
        assertFalse(raw.has("domain"),
                "⚠ and no domain field either — so the exact-name partition can only see TA after"
                        + " normalizeOperations. A fixture that hand-authors \"domain\":\"TA\""
                        + " cannot detect that dependency breaking: " + raw);
        assertTrue(raw.get("expression").asText().contains("domain=\"" + DATASET + "\""),
                "TA lives inside the expression's kwarg: " + raw);
    }
}
