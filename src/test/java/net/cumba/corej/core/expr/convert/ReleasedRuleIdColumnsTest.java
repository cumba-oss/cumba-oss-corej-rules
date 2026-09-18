package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.convert.RuleStorageAssembler.Triple;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.report.ReportAssembler;
import org.junit.jupiter.api.Test;

/**
 * §9c of {@code plans/done/PLAN-rules-corpus-build-integration.md} — <b>the guard that the R4 strip
 * rests on</b>.
 *
 * <h2>What R4 promises</h2>
 *
 * <p>
 * R4 replaces each released rule's {@code Authorities} tree —
 * {@code Authorities[] → Standards[] → References[] → Rule_Identifier.Id}, plus {@code Citations},
 * {@code Origin} and the per-reference versions — with the flat {@code [{Organization, Rule_Ids:
 * [...]}]}. That is 60.8 MiB of the 65.06 MiB it saves, and it is sound <em>only</em> because the
 * flat form still carries everything {@link ReportAssembler#collectRuleIds} reads. Those are the
 * {@code cdisc_rule_id} and {@code fda_rule_id} columns of the xlsx Rules Report, of the JSON
 * report's {@code Rules_Report}, and of {@code GET /api/checks/{id}/rules}.
 * </p>
 *
 * <p>
 * ⚠ For the {@code core} family this is the <b>only</b> place the published rule id exists.
 * {@code Core.Id} is opaque ({@code CORE-000001}); the id a regulatory reviewer recognises
 * ({@code CG0176}, {@code TIG0405}) lives nowhere else in the package.
 * </p>
 *
 * <h2>⛔ Why the claim needed a {@code src/main} edit before it was true</h2>
 *
 * <p>
 * {@code ReportAssembler.collectRuleIdsFromAuthority} used to {@code return} immediately when
 * {@code getStandards() == null}, which every R4 authority is. So the R4 corpus blanked both
 * columns for every rule — <b>with no error anywhere</b>: {@code FAIL_ON_UNKNOWN_PROPERTIES} is
 * disabled and an unbound {@code Rule_Ids} key is swallowed by {@code Rule}'s
 * {@code @JsonAnySetter}. Every test stayed green. This test is what makes that visible, and it was
 * written to fail <em>before</em> the reader was taught to read {@code Rule_Ids}.
 * </p>
 *
 * <h2>Why it compares against the assembler and not against a saved pre-R4 corpus</h2>
 *
 * <p>
 * The plan's §9c phrases this as "R4 output vs pre-R4 output", but the pre-R4 corpus stops existing
 * at §6 — nothing generated is committed any more, so a test written that way would be unrunnable
 * within days. {@link RuleStorageAssembler#assemblePackage} produces the canonical node
 * <em>before</em> {@link ReleaseShapeTrimmer} touches it, from {@code rules-src/}, which R4 never
 * modifies. Comparing the released package against that is the same assertion with a source of
 * truth that survives: <b>the report columns computed from what ships equal the report columns
 * computed from what was authored.</b>
 * </p>
 */
class ReleasedRuleIdColumnsTest
{

    /** The two organizations the report emits columns for. */
    private static final List<String> ORGANIZATIONS = List.of("CDISC", "FDA");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Floor, not an equality — the corpus grows. Guards against the loop silently visiting nothing,
     * which is how a comparison test becomes vacuous.
     */
    private static final int MIN_RULES_COMPARED = 3500;

    /**
     * At least this many rules must have a non-empty id in <em>some</em> column. Without it the
     * test would pass just as happily if R4 blanked every column and the assembler produced nothing
     * either.
     */
    private static final int MIN_NON_EMPTY = 3000;

    @Test
    void theReleasedCorpusReportsTheSameRuleIdColumnsAsTheAuthoredSource() throws Exception
    {
        RuleStorageAssembler assembler = new RuleStorageAssembler();
        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        int nonEmpty = 0;

        for (Triple t : assembler.discoverPackages())
        {
            String file = RuleStorageAssembler.packageFileName(t.family(), t.standard(),
                    t.version());
            RulePackage released = RulePackageLoader.load(CorpusPaths.rules(file));
            Map<String, Rule> releasedRules = released.getRules();
            assertNotNull(releasedRules, file + " has no rules");

            ObjectNode canonical = assembler.assemblePackage(t.family(), t.standard(), t.version());
            JsonNode authoredRules = canonical.get("rules");
            assertNotNull(authoredRules, file + ": the assembled node has no rules");

            for (Map.Entry<String, Rule> e : releasedRules.entrySet())
            {
                String coreId = e.getKey();
                JsonNode authoredNode = authoredRules.get(coreId);
                if (authoredNode == null)
                {
                    mismatches.add(file + " / " + coreId + ": released but not assembled");
                    continue;
                }
                Rule authored = MAPPER.treeToValue(authoredNode, Rule.class);
                compared++;

                for (String org : ORGANIZATIONS)
                {
                    String fromAuthored = ReportAssembler.collectRuleIds(authored, org);
                    String fromReleased = ReportAssembler.collectRuleIds(e.getValue(), org);
                    if (!fromAuthored.isEmpty())
                    {
                        nonEmpty++;
                    }
                    if (!fromAuthored.equals(fromReleased))
                    {
                        mismatches.add(file + " / " + coreId + " / " + org + ": authored ["
                                + fromAuthored + "] but released [" + fromReleased + "]");
                    }
                }
            }
        }

        assertTrue(compared >= MIN_RULES_COMPARED,
                "only " + compared + " rules compared; expected at least " + MIN_RULES_COMPARED
                        + ". A comparison over nothing is not a guard.");
        assertTrue(nonEmpty >= MIN_NON_EMPTY,
                "only " + nonEmpty + " (rule, organization) pairs carried any id at all; expected"
                        + " at least " + MIN_NON_EMPTY + ". Both sides being empty is not"
                        + " agreement — it is the exact failure this test exists to catch.");
        assertEquals(List.of(), mismatches.size() > 40 ? mismatches.subList(0, 40) : mismatches,
                mismatches.size() + " (rule, organization) report columns differ between the"
                        + " authored source and the released package. R4's whole justification is"
                        + " that cdisc_rule_id / fda_rule_id stay bit-identical across the strip;"
                        + " if this is red, they do not.");
    }
}
