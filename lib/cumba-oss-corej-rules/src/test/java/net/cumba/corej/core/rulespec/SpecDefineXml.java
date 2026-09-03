package net.cumba.corej.core.rulespec;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The spec schema's {@code define_xml} block: shape discrimination, contents validation and the
 * dataset-level attribute translation (PLAN-parity-define-dataset-channel §3.1/§3.3/§3.4/§3.5).
 *
 * <p>
 * The block maps a <b>dataset name</b> to one of two shapes:
 *
 * <pre>
 * define_xml:
 *   VS:                                             # SHAPE A — variable level, an ARRAY of records
 *     - {define_variable_name: VSPOS, define_variable_ccode: C99999}
 *   XY:                                             # SHAPE B — dataset level, an OBJECT of attrs
 *     define_dataset_class: TRIAL DESIGN
 *     define_dataset_label: Custom Dataset
 * </pre>
 *
 * <p>
 * Rules D2–D4 below were defined when the block had two consumers; the Python twin was cut with the
 * Python lane in wave 33, so this class is now their sole implementation:
 *
 * <ul>
 * <li><b>D2</b> — a key carries variable records <em>or</em> dataset attributes, never both. The
 * mixed shape {@code {variables: [...], dataset: {...}}} is rejected by the sub-key check below,
 * because neither {@code variables} nor {@code dataset} is an allowed dataset attribute.</li>
 * <li><b>D3</b> — the block's <b>key set</b> is the Define's dataset list, in declaration order,
 * whatever shape each key carries. It never feeds the study inventory (that stays
 * {@code datasets:}-only, or {@code FDA-SD0061} would stop being expressible).</li>
 * <li><b>D4</b> — {@code define_dataset_name} defaults to the key and
 * {@code define_dataset_location} to {@code <key.lower()>.xpt}, for <b>every</b> key of the block,
 * in <b>both</b> shapes. A variable-level key is still a dataset the Define declares, so the name
 * operand is factually true for it; registering it only for shape B made the Java lane serve no
 * {@code define_dataset_name} where the Python lane served the key. The Java engine has no location
 * sink, so that half is computed here for the {@link #defaultLocation(String)} contract and for
 * <b>R6</b> below — the fork joins the Define frame on name+location, so a divergent location makes
 * the Define side MISS entirely: every {@code define_dataset_*} cell comes back null and the rule
 * silently no-fires, which is indistinguishable from the Define genuinely not declaring the
 * dataset. (Before Fix #132 the merge was an OUTER join and the same mismatch SPLIT the row in two,
 * which is the artefact the EC-41 measurement had to tell apart from real duplication. The failure
 * mode changed with the fix; R6 matters more now, not less, because the new one is silent.)</li>
 * <li><b>D5</b> — a key with no {@code datasets:} entry is a <b>Define-only dataset</b>: legal, and
 * required by {@code FDA-SD0061}. Nothing here may reject it.</li>
 * <li><b>D6/§3.5</b> — an empty block, in either form, is rejected: it would leave
 * {@code getDatasetNames()} empty while still opening {@code RuleRunner}'s define skip gate, and
 * {@code define_dataset_names} has no empty&nbsp;&rArr;&nbsp;not-available demotion, so
 * {@code FDA-SD1063} would fire on every dataset.</li>
 * <li><b>D10</b> — the spec's YAML keys are <em>not</em> the provider map keys: the class attribute
 * lands under {@code className}. See {@link #JAVA_SINKS}.</li>
 * </ul>
 *
 * <p>
 * The numbered rejections (once mirrored 1:1 by the Python twin, now Java-only):
 *
 * <ul>
 * <li><b>R1</b> — {@code define_xml} present with zero keys.</li>
 * <li><b>R2</b> — a key's value is neither array nor object.</li>
 * <li><b>R3</b> — an empty array / empty object under a key.</li>
 * <li><b>R4</b> — a shape-B sub-key outside {@link #DATASET_ATTRIBUTE_KEYS}.</li>
 * <li><b>R5</b> — a shape-A element that is not a mapping.</li>
 * <li><b>R6</b> — an explicit sub-key contradicting its D4 default. The key <em>is</em> the dataset
 * identity (D3), but the fork reads the record's {@code define_dataset_name} as the identity
 * instead, so a mismatch diverges the lanes silently; a divergent {@code define_dataset_location}
 * additionally makes the fork's join miss, so every {@code define_dataset_*} cell is null and the
 * rule silently no-fires (pre-Fix-#132 that same mismatch split the outer-merged row instead).</li>
 * <li><b>R7</b> — a shape-B sub-key with a {@code null} value (a bare YAML key). Java used to skip
 * it and Python's {@code setdefault} used to keep it, so {@code define_dataset_name:} with nothing
 * after it yielded the key on one lane and {@code None} on the other — where the fork then calls
 * {@code None.upper()}.</li>
 * <li><b>R8</b> — {@code define_dataset_name} / {@code define_dataset_label} /
 * {@code define_dataset_class} must be textual. Java coerced with {@code asText()} while Python
 * kept the YAML type, so an unquoted {@code yes} or {@code 123} rendered differently per lane; all
 * three are strings in the fork's {@code ItemGroupDef} representation.</li>
 * </ul>
 */
final class SpecDefineXml
{

    /**
     * The allowed shape-B sub-keys — the fork's full dataset column set, in the fork's own order
     * ({@code dataset_metadata_define_dataset_builder.py}, {@code define_col_order}, lines 66-78).
     * Anything else is an author error: a typo like {@code define_dataset_clas} was silently
     * dropped by both lanes before this check existed, so the spec "passed" while testing nothing.
     *
     * <p>
     * <b>ELEVEN keys.</b> {@code define_dataset_domain} belongs here: the fork's
     * {@code define_col_order} carries it and {@code build()}'s docstring lists it, yet both lanes
     * used to reject it while claiming to hold "the fork's full column set". It has no Java sink,
     * so it is accepted and dropped (like the other keys only the Python lane ever consumed). The
     * list keeps the order the deleted Python twin used — R4's message prints it, and reordering
     * would churn every pinned R4 expectation for no semantic gain.
     */
    static final List<String> DATASET_ATTRIBUTE_KEYS = List.of("define_dataset_name",
            "define_dataset_label", "define_dataset_location", "define_dataset_domain",
            "define_dataset_class", "define_dataset_structure", "define_dataset_is_non_standard",
            "define_dataset_variables", "define_dataset_variable_order",
            "define_dataset_key_sequence", "define_dataset_has_no_data");

    /**
     * R8 — the sub-keys the fork's {@code ItemGroupDef} representation carries as plain strings.
     * They must be quoted in the spec: {@code asText()} would happily coerce {@code true} to
     * {@code "true"} and {@code 123} to {@code "123"} on this lane while the Python lane keeps the
     * YAML {@code bool} / {@code int}, so the same spec would render two different Define overlays.
     */
    private static final List<String> TEXTUAL_ATTRIBUTE_KEYS = List.of("define_dataset_name",
            "define_dataset_label", "define_dataset_class");

    /**
     * Spec YAML key &rarr; {@code MetadataProvider.getDatasetMetadata} map key (D10). Exactly these
     * three have a Java sink ({@code RuleRunner.injectDatasetLevel} for the legacy fold,
     * {@code MetadataAttribute.DS_NAME/DS_LABEL/DS_CLASS} for the native {@code ds_*} accessors) —
     * and the class attribute is spelled {@code className} in both. Copying
     * {@code define_dataset_class} literally would leave the operand undefined and
     * {@code FDA-SD9999} / {@code PMDA-SD9999} / {@code PMDA-AD9999} would silently stop firing.
     * Every other allowed sub-key is <b>Python-lane-only</b> by decision (D10) and is accepted here
     * without a sink.
     */
    private static final Map<String, String> JAVA_SINKS = Map.of("define_dataset_name", "name",
            "define_dataset_label", "label", "define_dataset_class", "className");

    private SpecDefineXml()
    {
    }


    /**
     * Validates a spec's {@code define_xml} block, throwing {@link IllegalArgumentException} with a
     * message naming the spec and the offending key. A {@code null} / absent / explicitly-null node
     * means "the spec declares no Define overlay" and is accepted.
     *
     * @param defineXml
     *            the raw {@code define_xml} node
     * @param specName
     *            the spec file or spec name, for the message
     */
    static void validate(@Nullable JsonNode defineXml, @Nullable String specName)
    {
        if (defineXml == null || defineXml.isNull() || defineXml.isMissingNode())
        {
            return;
        }
        String spec = specName == null ? "<unnamed>" : specName;
        if (!defineXml.isObject())
        {
            throw new IllegalArgumentException(prefix(spec)
                    + "define_xml must be a mapping of dataset name to either an array of"
                    + " define_variable_* records or an object of define_dataset_* attributes");
        }
        if (defineXml.isEmpty())
        {
            // §3.5 — the trap: an empty object still yields a non-null define provider, which opens
            // RuleRunner's define skip gate while getDatasetNames() stays empty.
            throw new IllegalArgumentException(prefix(spec)
                    + "define_xml declares no datasets. An empty block still installs a Define"
                    + " provider (so define-dependent rules execute instead of skipping) but leaves"
                    + " the Define dataset-name list empty, which makes define_dataset_names rules"
                    + " such as FDA-SD1063 fire on every dataset. Declare at least one dataset, or"
                    + " remove the key.");
        }
        for (Map.Entry<String, JsonNode> entry : defineXml.properties())
        {
            validateKey(spec, entry.getKey(), entry.getValue());
        }
    }


    private static void validateKey(String spec, String key, JsonNode value)
    {
        if (value.isArray())
        {
            if (value.isEmpty())
            {
                throw new IllegalArgumentException(keyPrefix(spec, key)
                        + "an empty variable-record array declares nothing. Give it at least one"
                        + " {define_variable_*: ...} record, or drop the key.");
            }
            for (JsonNode record : value)
            {
                if (!record.isObject())
                {
                    throw new IllegalArgumentException(keyPrefix(spec, key)
                            + "every variable-level entry must be a mapping of define_variable_*"
                            + " attributes; got " + describe(record));
                }
            }
            return;
        }
        if (value.isObject())
        {
            if (value.isEmpty())
            {
                throw new IllegalArgumentException(keyPrefix(spec, key)
                        + "an empty dataset-attribute object declares nothing. Give it at least one"
                        + " of " + DATASET_ATTRIBUTE_KEYS + ", or drop the key.");
            }
            for (Map.Entry<String, JsonNode> attr : value.properties())
            {
                validateDatasetAttribute(spec, key, attr.getKey(), attr.getValue());
            }
            return;
        }
        throw new IllegalArgumentException(keyPrefix(spec, key)
                + "expected an array of define_variable_* records (variable level) or an object of"
                + " define_dataset_* attributes (dataset level); got " + describe(value));
    }


    /**
     * R4 / R7 / R8 / R6, in that order — the order matters: an unknown key is reported before its
     * value is inspected, a bare key before its (absent) type, and the type before the
     * derived-default comparison that reads it as text.
     */
    private static void validateDatasetAttribute(String spec, String key, String attrKey,
            JsonNode attrValue)
    {
        if (!DATASET_ATTRIBUTE_KEYS.contains(attrKey))
        {
            // R4
            throw new IllegalArgumentException(keyPrefix(spec, key) + "unknown attribute '"
                    + attrKey + "'. A dataset-level entry carries only " + DATASET_ATTRIBUTE_KEYS
                    + "; variable records and dataset attributes cannot be mixed under one key"
                    + " (D2).");
        }
        if (attrValue.isNull())
        {
            // R7 — a bare YAML key. Java used to skip it, Python used to keep the null; the fork
            // then calls .upper() on it. Neither lane may paper over it.
            throw new IllegalArgumentException(keyPrefix(spec, key) + "attribute '" + attrKey
                    + "' has no value. A bare YAML key is a null, not an empty string: the Java"
                    + " lane would drop it (so the attribute falls back to its default) while the"
                    + " Python lane would carry None into the Define record. Give it a value, or"
                    + " remove the line.");
        }
        if (TEXTUAL_ATTRIBUTE_KEYS.contains(attrKey) && !attrValue.isTextual())
        {
            // R8
            throw new IllegalArgumentException(keyPrefix(spec, key) + "attribute '" + attrKey
                    + "' must be a string; got " + describe(attrValue)
                    + ". The fork's ItemGroupDef representation holds it as text, so an unquoted"
                    + " scalar renders as a YAML bool/number on the Python lane and as its"
                    + " asText() coercion here. Quote the value.");
        }
        if ("define_dataset_name".equals(attrKey) && !key.equals(attrValue.asText()))
        {
            // R6
            throw new IllegalArgumentException(keyPrefix(spec, key) + "define_dataset_name is '"
                    + attrValue.asText() + "' but the block key is '" + key
                    + "'. The key IS the dataset identity on this lane (D3) while the fork reads"
                    + " the record's define_dataset_name as the identity, so the two lanes would"
                    + " disagree silently. Declare the dataset under a '" + attrValue.asText()
                    + "' key instead, or drop the attribute (it defaults to the key).");
        }
        if ("define_dataset_location".equals(attrKey)
                && !defaultLocation(key).equals(attrValue.asText()))
        {
            // R6
            throw new IllegalArgumentException(keyPrefix(spec, key) + "define_dataset_location is '"
                    + attrValue.asText() + "' but the key '" + key + "' derives '"
                    + defaultLocation(key)
                    + "'. The fork joins the Define frame on define_dataset_name +"
                    + " define_dataset_location, so a divergent location makes the Define side miss"
                    + " entirely: every define_dataset_* cell is null and the rule silently"
                    + " no-fires, indistinguishable from the Define not declaring the dataset at"
                    + " all. Declare a second block key instead, or drop the attribute (it defaults"
                    + " to the key).");
        }
    }


    /** True for the dataset-level (shape B) value form. */
    static boolean isDatasetLevel(JsonNode value)
    {
        return value.isObject();
    }


    /**
     * Translates a block entry into the provider's dataset-attribute channel, applying D4's
     * {@code define_dataset_name} default (the key) and D10's key mapping. Sub-keys with no Java
     * sink are accepted and dropped — they are Python-lane-only by decision, not errors.
     *
     * <p>
     * <b>Both shapes.</b> For a shape-A (array) value the result is exactly D4's default,
     * {@code {name: key}}: the Define declares that dataset whatever the key carries, so the
     * {@code define_dataset_name} operand is factually true for it (the Python lane, while it
     * existed, served the key). Restricting the registration to shape B left the two lanes
     * disagreeing on every variable-level key.
     *
     * <p>
     * No {@code null} guard on the sub-key values: {@link #validate} rejects them (R7) before any
     * caller reaches here, and swallowing one silently is the divergence R7 exists to stop.
     */
    static Map<String, String> datasetAttrs(String key, JsonNode value)
    {
        Map<String, String> attrs = new LinkedHashMap<>();
        // D4: the key is the dataset name. R6 guarantees an explicit define_dataset_name agrees,
        // so the loop below can only re-assign the identical value.
        attrs.put("name", key);
        if (!value.isObject())
        {
            return attrs;
        }
        for (Map.Entry<String, JsonNode> attr : value.properties())
        {
            String sink = JAVA_SINKS.get(attr.getKey());
            if (sink != null)
            {
                attrs.put(sink, attr.getValue().asText());
            }
        }
        return attrs;
    }


    /**
     * D4's {@code define_dataset_location} default, the fork's {@code <name.lower()>.xpt}. The Java
     * engine exposes no location operand, so nothing <em>consumes</em> it here; it is the reference
     * value R6 measures an explicit {@code define_dataset_location} against, which keeps the two
     * lanes' defaults stated in one place.
     */
    static String defaultLocation(String key)
    {
        return key.toLowerCase(Locale.ROOT) + ".xpt";
    }


    private static String prefix(String spec)
    {
        return "Spec " + spec + ": ";
    }


    private static String keyPrefix(String spec, String key)
    {
        return prefix(spec) + "define_xml[" + key + "]: ";
    }


    private static String describe(JsonNode node)
    {
        return node.isNull() ? "null"
                : node.getNodeType().toString().toLowerCase(Locale.ROOT) + " " + node.toString();
    }
}
