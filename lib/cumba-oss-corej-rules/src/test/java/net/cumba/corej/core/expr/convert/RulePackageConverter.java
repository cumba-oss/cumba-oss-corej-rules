package net.cumba.corej.core.expr.convert;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Offline {@code Check} / {@code Precondition} carrier for the corpus generators. Since the wave-43
 * flip the authored corpus is <strong>expression-form throughout</strong> (3 799 / 3 799 shipped
 * {@code Check}s are a single {@code {"expression": "..."}} leaf, or a level-keyed map of such
 * leaves), so this class no longer converts anything: an expression leaf is carried through
 * verbatim, a level-keyed {@code Check} is carried per level with each level's {@code Message}
 * preserved, and any other shape — a legacy operator-leaf or an {@code all}/{@code any}/{@code not}
 * composite, which can still reach {@link RulePackageExpressionJson} from foreign rules (rule
 * editor, CDISC-Library import) — is kept <strong>byte-faithfully</strong> as-is, which is always
 * loadable.
 *
 * <p>
 * The old-to-new <em>conversion</em> machinery this class carried (the {@code CheckToExpr} leaf
 * raise, the composite combining/partial-fold, the per-rule curated table for
 * {@code CORE-000335}/{@code 000539}/{@code 000540} and the {@code does_not_equal_string_part}
 * leaf-signature rewrite) was retired by {@code plans/done/PLAN-retire-corpus-transforms.md} phase
 * 1: with zero old-style leaves in {@code rules-src} and zero in {@code rules/} it had nothing left
 * to convert, and the three curated texts are authored byte-identically in their source files
 * (pinned by {@code RuleSrcExpressionWriterCorpusTest}).
 * </p>
 *
 * <p>
 * The converter operates on Jackson {@link JsonNode} trees so that every non-{@code Check} field
 * ({@code Operations}, {@code Scope}, {@code Outcome}, ...) is carried through verbatim.
 * </p>
 */
public final class RulePackageConverter
{

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private RulePackageConverter()
    {
    }

    /**
     * Mutable per-conversion tally: the number of {@code Check} leaves visited and how many of them
     * were expression-form carriers. Since the flip the two figures are equal for the shipped
     * corpus; a non-expression shape is carried verbatim and counted as visited only.
     */
    public static final class ConversionReport
    {

        private int totalLeaves;

        private int convertedLeaves;

        /**
         * Returns the total number of {@code Check} leaves visited.
         *
         * @return the leaf count
         */
        public int getTotalLeaves()
        {
            return totalLeaves;
        }


        /**
         * Returns the number of leaves carried in expression form.
         *
         * @return the expression-leaf count
         */
        public int getConvertedLeaves()
        {
            return convertedLeaves;
        }


        private void recordConverted()
        {
            totalLeaves++;
            convertedLeaves++;
        }


        private void recordKept()
        {
            totalLeaves++;
        }


        @Override
        public String toString()
        {
            return "ConversionReport[total=" + totalLeaves + ", converted=" + convertedLeaves + "]";
        }

    }

    /**
     * Carries a {@code Check} subtree through: an expression leaf verbatim (counted as converted),
     * a level-keyed map per level with each level's {@code Message} preserved, and any other shape
     * byte-faithfully as-is (counted as visited only).
     *
     * @param check
     *            the {@code Check} subtree node (an {@code {"expression":...}} leaf, a level map, a
     *            legacy shape, or {@code null})
     * @param report
     *            the tally to update; never {@code null}
     * @return the carried subtree (a fresh node; the input is not mutated)
     */
    public static JsonNode convertCheck(JsonNode check, ConversionReport report)
    {
        if (check == null || check.isNull())
        {
            return check;
        }
        // Plan C §3.3 — a level-keyed Check is carried PER LEVEL, each level's own Message
        // preserved (a level map has no all/any/not/expression key).
        if (CheckLevelNodes.isLevelMap(check))
        {
            return convertLevelMap(check, report);
        }
        if (check.has("expression"))
        {
            report.recordConverted();
            return check.deepCopy();
        }
        // A legacy operator-leaf or composite — possible only on a foreign rule (rule editor,
        // CDISC-Library import); the shipped corpus has none. Kept byte-faithfully: the old-style
        // form is always loadable, and the machinery that used to raise it is retired.
        report.recordKept();
        return check.deepCopy();
    }


    /**
     * Carries each level of a level-keyed {@code Check:} independently, in the authored level
     * order, re-attaching each level's own {@code Message} to its carried condition.
     */
    private static JsonNode convertLevelMap(JsonNode check, ConversionReport report)
    {
        ObjectNode out = MAPPER.createObjectNode();
        for (Map.Entry<String, JsonNode> level : check.properties())
        {
            JsonNode value = level.getValue();
            JsonNode message = value.isObject() ? value.get(CheckLevelNodes.MESSAGE_KEY) : null;
            JsonNode conditionNode = value;
            if (message != null && value instanceof ObjectNode obj)
            {
                // Strip the Message before carrying — it is not part of the condition grammar —
                // on a copy, so the input node is not mutated (the convertCheck contract).
                ObjectNode strippedCondition = obj.deepCopy();
                strippedCondition.remove(CheckLevelNodes.MESSAGE_KEY);
                conditionNode = strippedCondition;
            }
            JsonNode converted = convertCheck(conditionNode, report);
            if (message != null && converted instanceof ObjectNode convertedObj)
            {
                // Safe to mutate: `converted` is either a fresh deep copy or the stripped copy
                // made above — never the caller's input.
                convertedObj.set(CheckLevelNodes.MESSAGE_KEY, message);
            }
            out.set(level.getKey(), converted);
        }
        return out;
    }


    /**
     * Carries a whole rules-package JSON string through. Each rule's {@code Check} /
     * {@code Precondition} subtree is carried in place (see {@link #convertCheck}); every other
     * field (including {@code Operations}, {@code Scope}, {@code Outcome}, and any package-level
     * key the engine does not model) is copied through unchanged.
     *
     * @param packageJson
     *            the rules-package JSON
     * @param report
     *            the tally to update across all rules; never {@code null}
     * @return the carried package JSON
     * @throws IOException
     *             if the input is not valid JSON
     */
    public static String convertPackageString(String packageJson, ConversionReport report)
        throws IOException
    {
        JsonNode root = MAPPER.readTree(packageJson);
        JsonNode converted = convertPackageNode(root, report);
        return writePackage(converted);
    }


    /**
     * Serialises a package node the way the shipped corpus is written. <b>One authority for the
     * output formatting</b> — Jackson's default pretty printer, whose exact spacing the committed
     * corpus and every byte-identity gate over it depend on. A second caller configuring its own
     * mapper is how those gates start failing for reasons that have nothing to do with content.
     *
     * @param aPackage
     *            the package node
     * @return the serialised package JSON
     * @throws IOException
     *             if the node cannot be serialised
     */
    public static String writePackage(JsonNode aPackage) throws IOException
    {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(aPackage);
    }


    /**
     * Reads a package JSON string into a node using the same tolerant mapper the conversion uses.
     *
     * @param aPackageJson
     *            the package JSON
     * @return the parsed node
     * @throws IOException
     *             if the input is not valid JSON
     */
    public static JsonNode readPackage(String aPackageJson) throws IOException
    {
        return MAPPER.readTree(aPackageJson);
    }


    /**
     * Carries a parsed rules-package node through, returning a fresh node.
     *
     * @param root
     *            the package root node (object with a {@code rules} map)
     * @param report
     *            the tally to update; never {@code null}
     * @return the carried package node (the input is not mutated)
     */
    public static @Nullable JsonNode convertPackageNode(JsonNode root, ConversionReport report)
    {
        if (root == null || !root.isObject())
        {
            return root == null ? null : root.deepCopy();
        }
        ObjectNode out = root.deepCopy();
        JsonNode rules = out.get("rules");
        if (rules != null && rules.isObject())
        {
            ObjectNode rulesObj = (ObjectNode) rules;
            for (Map.Entry<String, JsonNode> entry : rulesObj.properties())
            {
                JsonNode rule = entry.getValue();
                if (rule == null || !rule.isObject())
                {
                    continue;
                }
                ObjectNode ruleObj = (ObjectNode) rule;
                if (ruleObj.has("Check"))
                {
                    ruleObj.set("Check", convertCheck(ruleObj.get("Check"), report));
                }
                if (ruleObj.has("Precondition"))
                {
                    ruleObj.set("Precondition", convertCheck(ruleObj.get("Precondition"), report));
                }
            }
        }
        return out;
    }

}
