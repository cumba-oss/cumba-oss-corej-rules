package net.cumba.corej.core.rulespec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Canonical, snake_case violation record of the rulespec suite. {@link SpecRunner} normalises the
 * engine's output to this shape so violations can be diffed as a pure value comparison without
 * dragging engine internals into the contract (while the Python lane existed, both runners
 * normalised to this same shape).
 *
 * <p>
 * Equality is structural: two violations are "the same" iff all fields match per
 * {@link ViolationNormaliser}'s normalisation rules (snake_case keys, sorted output_variables,
 * coerced row_key value types, missing → null, sorted name_only or none).
 * </p>
 *
 * <p>
 * {@code name_only} ({@code PLAN-finding-values-record-level-only} V8 (b)) is the raw columns the
 * finding lists <em>by name without a value</em> — a non-record finding's raw columns
 * ({@code Violation.getNamedColumns()}). It is a state of its own, never spelled as a {@code null}
 * / {@code ""} entry of {@code output_variables}; a spec that omits it expects none.
 * </p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonPropertyOrder(
{
        "rule_id", "dataset", "row_key", "status", "output_variables", "name_only", "error_message"
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CanonicalViolation(String ruleId, String dataset, Map<String, Object> rowKey,
        Status status, Map<String, Object> outputVariables,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> nameOnly, String errorMessage)
{

    /** The pre-V8 shape: no name-only columns. */
    public CanonicalViolation(String ruleId, String dataset, Map<String, Object> rowKey,
            Status status, Map<String, Object> outputVariables, String errorMessage)
    {
        this(ruleId, dataset, rowKey, status, outputVariables, null, errorMessage);
    }


    /**
     * Normalising canonical constructor: an empty {@code name_only} is {@code null} (a spec that
     * omits the field and a finding that lists nothing compare equal), a non-empty one is sorted.
     */
    public CanonicalViolation
    {
        nameOnly = nameOnly == null || nameOnly.isEmpty() ? null
                : nameOnly.stream().sorted().toList();
    }

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
        return of(ruleId, dataset, rowKey, outputVariables, null);
    }


    /**
     * {@link #of(String, String, Map, Map)} with the finding's name-only columns ({@code null} or
     * empty when it lists none).
     */
    public static CanonicalViolation of(String ruleId, String dataset, Map<String, Object> rowKey,
            Map<String, Object> outputVariables, @Nullable List<String> nameOnly)
    {
        return new CanonicalViolation(ruleId, dataset, sorted(rowKey), Status.SUCCESS,
                sorted(outputVariables), nameOnly, null);
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
