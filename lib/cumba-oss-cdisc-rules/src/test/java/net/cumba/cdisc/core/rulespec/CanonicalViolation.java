package net.cumba.cdisc.core.rulespec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical, snake_case violation record of the rulespec suite. {@link SpecRunner} normalises the
 * engine's output to this shape so violations can be diffed as a pure value comparison without
 * dragging engine internals into the contract (while the Python lane existed, both runners
 * normalised to this same shape).
 *
 * <p>
 * Equality is structural: two violations are "the same" iff all five fields match per
 * {@link ViolationNormaliser}'s normalisation rules (snake_case keys, sorted output_variables,
 * coerced row_key value types, missing → null).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonPropertyOrder(
{
        "rule_id", "dataset", "row_key", "status", "output_variables", "error_message"
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CanonicalViolation(String ruleId, String dataset, Map<String, Object> rowKey,
        Status status, Map<String, Object> outputVariables, String errorMessage)
{

    public enum Status
    {
        SUCCESS, SKIPPED, ERROR
    }

    /**
     * Convenience constructor for the common case (executed rule, one violation row).
     */
    public static CanonicalViolation of(String ruleId, String dataset, Map<String, Object> rowKey,
            Map<String, Object> outputVariables)
    {
        return new CanonicalViolation(ruleId, dataset, sorted(rowKey), Status.SUCCESS,
                sorted(outputVariables), null);
    }


    /**
     * Returns a new map with keys sorted alphabetically (insertion-order preserved by
     * {@link LinkedHashMap}). Stable JSON output requires this; Java {@code Map} is unordered.
     */
    static Map<String, Object> sorted(Map<String, Object> source)
    {
        if (source == null || source.isEmpty())
        {
            return Map.of();
        }
        Map<String, Object> sorted = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEachOrdered(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }
}
