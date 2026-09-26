package net.cumba.corej.core.expr.convert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The last stage before a package is written: shapes the <b>released</b> form of each rule.
 *
 * <h2>⛔⛔ {@code rules-src/} is NOT touched. Ever.</h2>
 *
 * <p>
 * This is a <em>release-packaging</em> transform, not a content change. Every field it removes
 * stays in the authored source, in git, and available to the authoring tools; only the shipped
 * package is smaller. Option R4 of {@code plans/done/PLAN-rules-corpus-build-integration.md} §10,
 * decided 2026-09-02.
 * </p>
 *
 * <h2>What it does, and what each part is worth</h2>
 *
 * <table>
 * <caption>Measured over the 59 shipped packages</caption>
 * <tr>
 * <th>Change</th>
 * <th>Saving</th>
 * </tr>
 * <tr>
 * <td>{@code Authorities} collapsed to {@code [{Organization, Rule_Ids}]}</td>
 * <td>60.79 MiB</td>
 * </tr>
 * <tr>
 * <td>{@code Source} dropped</td>
 * <td>4.11 MiB</td>
 * </tr>
 * <tr>
 * <td>{@code Source_Proposed} dropped</td>
 * <td>0.16 MiB</td>
 * </tr>
 * <tr>
 * <td><b>total</b></td>
 * <td><b>65.06 MiB of 79.34 — 82.01%</b></td>
 * </tr>
 * </table>
 *
 * <h2>⚠⚠ Why {@code Authorities} is collapsed and not dropped</h2>
 *
 * <p>
 * It was first classified as pure documentation. That is wrong. {@code ReportAssembler} emits
 * {@code cdisc_rule_id} and {@code fda_rule_id} from it, and those are columns of the xlsx Rules
 * Report, of the JSON report's {@code Rules_Report}, and of {@code GET /api/checks/{id}/rules}. For
 * the {@code core} family it is the <b>only</b> place the published rule id exists at all:
 * {@code Core.Id} is opaque ({@code CORE-000001}), while the id a reviewer recognises
 * ({@code CG0176}, {@code TIG0405}) lives nowhere else in the package. R4 keeps exactly what the
 * report reads — {@code Organization} and every {@code Rule_Identifier.Id}, de-duplicated — so the
 * report output is unchanged. {@code ReleasedRuleIdColumnsTest} asserts that rule by rule against
 * the authored source; it is the guard the 82% rests on.
 * </p>
 *
 * <h2>⚠⚠ Delete whole keys; never rewrite a nested block and leave residue</h2>
 *
 * <p>
 * Since {@code PLAN-rule-unknown-keys-gate} an unknown key at <em>any</em> level of a rule is a
 * per-rule {@code loadError} — the top level included — so dropping {@code Source} /
 * {@code Source_Proposed} is not merely free, it is REQUIRED: a package that still carried them
 * would fail every rule at load. And a nested block rewritten with residue would be rejected the
 * same way ({@code RulePackageLoader.validateUnknownKeys}). Nothing here touches the nested blocks,
 * and nothing added here should.
 * </p>
 *
 * <h2>What is deliberately KEPT, and why</h2>
 *
 * <ul>
 * <li>{@code Description}, {@code Outcome} — report content.
 * <li>{@code Sensitivity} (1.7 KB) — dropping it hard-reds {@code CrossCorpusDerivationTest}
 * <em>and changes routing</em>; the carriers are deliberate overrides.
 * <li>{@code Executability} (0.63 MiB) — it is a loader <b>filter</b>; dropping it un-parks every
 * {@code Not Executable} rule.
 * <li>{@code Core.Version} — the rule-editor store's primary key; its absence {@code continue}s
 * with no diagnostic.
 * <li>{@code Requirements.*}, {@code Core.Status}, {@code ExecutabilityHint} — 0.90 MiB together,
 * 1.1%, for real risk. Not worth it.
 * </ul>
 */
public final class ReleaseShapeTrimmer
{

    /** Top-level rule keys that no {@code src/main} consumer reads. */
    private static final Set<String> DROPPED_KEYS = new LinkedHashSet<>(
            java.util.List.of("Source", "Source_Proposed"));

    private static final String AUTHORITIES = "Authorities";

    private static final String ORGANIZATION = "Organization";

    private static final String STANDARDS = "Standards";

    private static final String REFERENCES = "References";

    private static final String RULE_IDENTIFIER = "Rule_Identifier";

    private static final String ID = "Id";

    /** The flat released key. Must match {@code Authority}'s {@code @JsonProperty}. */
    private static final String RULE_IDS = "Rule_Ids";

    private ReleaseShapeTrimmer()
    {
    }


    /**
     * Returns the released shape of a whole package node. The input is not mutated.
     *
     * @param aRoot
     *            the package root (an object with a {@code rules} map), or {@code null}
     * @return a fresh node in released shape, or {@code null} when the input was
     */
    public static JsonNode trim(JsonNode aRoot)
    {
        if (aRoot == null || !aRoot.isObject())
        {
            return aRoot == null ? null : aRoot.deepCopy();
        }
        ObjectNode out = aRoot.deepCopy();
        JsonNode rules = out.get("rules");
        if (rules == null || !rules.isObject())
        {
            return out;
        }
        for (Map.Entry<String, JsonNode> entry : ((ObjectNode) rules).properties())
        {
            if (entry.getValue() instanceof ObjectNode rule)
            {
                trimRule(rule);
            }
        }
        return out;
    }


    /** Trims one rule in place. The caller owns a deep copy. */
    private static void trimRule(ObjectNode aRule)
    {
        for (String key : DROPPED_KEYS)
        {
            aRule.remove(key);
        }
        JsonNode authorities = aRule.get(AUTHORITIES);
        if (authorities != null && authorities.isArray())
        {
            aRule.set(AUTHORITIES, collapseAuthorities((ArrayNode) authorities));
        }
    }


    /**
     * {@code [{Organization, Standards[{..., References[{..., Rule_Identifier{Id}}]}]}]} &rarr;
     * {@code [{Organization, Rule_Ids}]}.
     *
     * <p>
     * ⚠ De-duplicated but <b>not sorted</b>: {@code ReportAssembler.collectRuleIds} sorts into a
     * {@code TreeSet} of its own, so the released order does not reach any report, and preserving
     * the authored order keeps the diff of a regeneration readable.
     * </p>
     *
     * <p>
     * ⚠ Every authority keeps an entry, with {@code Rule_Ids: [ ]} when it has none. An authority
     * that publishes no id for a rule is still a statement that the authority covers it, and
     * dropping the entry would lose that.
     * </p>
     */
    private static ArrayNode collapseAuthorities(ArrayNode aAuthorities)
    {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (JsonNode authority : aAuthorities)
        {
            if (!authority.isObject())
            {
                out.add(authority.deepCopy());
                continue;
            }
            ObjectNode collapsed = out.addObject();
            collapsed.set(ORGANIZATION,
                    authority.get(ORGANIZATION) == null ? JsonNodeFactory.instance.nullNode()
                            : authority.get(ORGANIZATION).deepCopy());
            ArrayNode ids = collapsed.putArray(RULE_IDS);
            Set<String> seen = new LinkedHashSet<>();
            collectIds(authority.get(STANDARDS), seen);
            // Already-collapsed input (a re-run over a released package) must be idempotent.
            collectFlatIds(authority.get(RULE_IDS), seen);
            seen.forEach(ids::add);
        }
        return out;
    }


    private static void collectIds(JsonNode aStandards, Set<String> aInto)
    {
        if (aStandards == null || !aStandards.isArray())
        {
            return;
        }
        for (JsonNode standard : aStandards)
        {
            JsonNode references = standard.get(REFERENCES);
            if (references == null || !references.isArray())
            {
                continue;
            }
            for (JsonNode reference : references)
            {
                JsonNode identifier = reference.get(RULE_IDENTIFIER);
                if (identifier == null)
                {
                    continue;
                }
                JsonNode id = identifier.get(ID);
                if (id != null && id.isTextual())
                {
                    aInto.add(id.asText());
                }
            }
        }
    }


    private static void collectFlatIds(JsonNode aRuleIds, Set<String> aInto)
    {
        if (aRuleIds == null || !aRuleIds.isArray())
        {
            return;
        }
        for (JsonNode id : aRuleIds)
        {
            if (id.isTextual())
            {
                aInto.add(id.asText());
            }
        }
    }

}
