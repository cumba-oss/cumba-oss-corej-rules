package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every authored key has help text ({@code PLAN-rule-authoring-api} phase 4): each property of
 * {@code documentation/rule-check.schema.json} and {@code documentation/rule-doc.schema.json} —
 * nested, in {@code $defs}, under {@code items} / {@code oneOf} / {@code additionalProperties} —
 * carries a non-blank {@code description}. The rule editor's tooltips read these; a key without one
 * is a key the author gets no help on.
 */
class SchemaDescriptionsGuardTest
{

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Floors so a schema that lost its properties cannot pass vacuously. */
    private static final int MIN_CHECK_PROPERTIES = 90;

    private static final int MIN_DOC_PROPERTIES = 6;

    private static Path schema(String aName)
    {
        // documentation/ in the authoring repository, schema/ in the published one
        return AuthoredCorpusSchemaTest.schemaFile(aName);
    }


    static List<String> undescribed(JsonNode aRoot)
    {
        List<String> out = new ArrayList<>();
        walk(aRoot, "", out, new int[1]);
        return out;
    }


    static int count(JsonNode aRoot)
    {
        int[] n = new int[1];
        walk(aRoot, "", new ArrayList<>(), n);
        return n[0];
    }


    private static void walk(JsonNode aNode, String aPath, List<String> aOut, int[] aCount)
    {
        if (aNode == null || !aNode.isObject())
        {
            return;
        }
        JsonNode props = aNode.get("properties");
        if (props != null)
        {
            for (Map.Entry<String, JsonNode> e : props.properties())
            {
                String path = aPath + "/" + e.getKey();
                aCount[0]++;
                JsonNode d = e.getValue().get("description");
                if (d == null || !d.isTextual() || d.asText().isBlank())
                {
                    aOut.add(path);
                }
                walk(e.getValue(), path, aOut, aCount);
            }
        }
        walk(aNode.get("items"), aPath + "/[]", aOut, aCount);
        for (String k : List.of("oneOf", "anyOf", "allOf"))
        {
            JsonNode list = aNode.get(k);
            if (list != null)
            {
                for (int i = 0; i < list.size(); i++)
                {
                    walk(list.get(i), aPath + "/" + k + i, aOut, aCount);
                }
            }
        }
        JsonNode additional = aNode.get("additionalProperties");
        if (additional != null && additional.isObject())
        {
            walk(additional, aPath + "/*", aOut, aCount);
        }
        JsonNode defs = aNode.get("$defs");
        if (defs != null)
        {
            for (Map.Entry<String, JsonNode> e : defs.properties())
            {
                walk(e.getValue(), aPath + "/$defs/" + e.getKey(), aOut, aCount);
            }
        }
    }


    @Test
    void everyCheckSchemaPropertyIsDescribed() throws IOException
    {
        JsonNode root = JSON.readTree(
                Files.readString(schema("rule-check.schema.json"), StandardCharsets.UTF_8));
        assertTrue(count(root) >= MIN_CHECK_PROPERTIES, "properties: " + count(root));
        assertEquals(List.of(), undescribed(root), "undescribed properties");
    }


    @Test
    void everyDocSchemaPropertyIsDescribed() throws IOException
    {
        JsonNode root = JSON
                .readTree(Files.readString(schema("rule-doc.schema.json"), StandardCharsets.UTF_8));
        assertTrue(count(root) >= MIN_DOC_PROPERTIES, "properties: " + count(root));
        assertEquals(List.of(), undescribed(root), "undescribed properties");
    }


    /** The walk must SEE a missing description, or the two tests above are a tautology. */
    @Test
    void theWalkSeesAMissingDescription() throws IOException
    {
        ObjectNode root = (ObjectNode) JSON.readTree(
                Files.readString(schema("rule-check.schema.json"), StandardCharsets.UTF_8));
        ((ObjectNode) root.get("properties").get("Core").get("properties").get("Id"))
                .remove("description");
        ((ObjectNode) root.get("$defs").get("match").get("properties").get("Keys"))
                .put("description", "   ");
        assertEquals(List.of("/Core/Id", "/$defs/match/Keys"), undescribed(root));
    }
}
