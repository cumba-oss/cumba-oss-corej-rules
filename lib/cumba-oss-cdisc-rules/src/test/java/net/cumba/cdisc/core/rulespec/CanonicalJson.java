package net.cumba.cdisc.core.rulespec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Single source of truth for the canonical-violation {@link ObjectMapper} configuration. Kept off
 * the engine's own Jackson stack so changes here do not perturb production serialisation.
 *
 * <p>
 * Configuration intentionally minimal:
 * <ul>
 * <li>Snake_case property naming via the
 * {@link com.fasterxml.jackson.databind.annotation.JsonNaming} annotations on
 * {@link CanonicalViolation} (per-class, not mapper-wide).</li>
 * <li>{@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS} — defence-in-depth even though the
 * normaliser pre-sorts maps; eliminates any HashMap order surprises in tests that hand-build
 * fixtures.</li>
 * <li>No pretty-printing — canonical JSON is single-line for byte-stable hashing/diffing.</li>
 * </ul>
 */
public final class CanonicalJson
{

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private CanonicalJson()
    {
    }


    public static ObjectMapper mapper()
    {
        return MAPPER;
    }


    public static String toJson(Object value)
    {
        try
        {
            return MAPPER.writeValueAsString(value);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException("Canonical JSON serialisation failed", e);
        }
    }
}
