package net.cumba.corej.core.rulespec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.cumba.corej.core.CorpusPaths;

/**
 * Loads a YAML spec from disk into {@link SpecSchema}, resolving inline {@code $ref} pointers and
 * applying schema rules:
 *
 * <ul>
 * <li>{@code $ref:} can appear at any object level. The referenced file is loaded with the reader
 * chosen by extension ({@code .yaml/.yml/.json}). Paths are resolved relative to the file
 * containing the {@code $ref}.</li>
 * <li>{@code coreId:} references the shipped rule by id — it resolves from the <b>native
 * {@code rules/} packages</b>, the corpus the production Java engine loads
 * ({@code plans/done/PLAN-parity-java-lane-native-corpus.md}); the Python lane mirrors with
 * {@code rules-legacy}, its own production corpus, so each lane loads what its engine ships. The
 * entry's {@code Authorities} membership block is dropped and the rule merges under the node
 * (siblings win, like {@code $ref}). An optional sibling {@code override:} object is deep-merged on
 * top (override wins) — but it must not touch {@code Check}: deep-merging a leaf-form override onto
 * an expression {@code Check} would leave both {@code expression} and {@code all} keys; pin the
 * whole rule inline instead (the D1 pattern, see {@code pinned_from}).</li>
 * <li>DM row-key defaulting (OD-2): {@code datasets.DM.row_key} defaults to
 * {@code [STUDYID, USUBJID]} when omitted. Setting it to anything else is a validation error.</li>
 * <li>{@code define_xml} contents validation ({@link SpecDefineXml}): each key must carry either an
 * array of {@code define_variable_*} records or an object of known {@code define_dataset_*}
 * attributes, and neither the block nor any key may be empty. Before
 * PLAN-parity-define-dataset-channel P1 the block's contents were unvalidated on both lanes, so a
 * typo — or a whole unrecognised value shape — was silently dropped and the spec "passed" while
 * supplying no Define metadata at all.</li>
 * </ul>
 */
// Test fixture / helper exposing LinkedHashMap/LinkedHashSet for ordered iteration.
@SuppressWarnings("NonApiType")
public final class SpecLoader
{

    private static final YAMLMapper YAML = (YAMLMapper) new YAMLMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final List<String> DM_DEFAULT_ROW_KEY = List.of("STUDYID", "USUBJID");

    /** Shared rule source, resolved against the module basedir (Surefire working directory). */
    // The shipped native corpus lives in the sibling corej-rules module
    // (PLAN-engine-rules-decoupling); ../ resolves from this module's basedir (surefire cwd).
    private static final Path RULES_NATIVE = CorpusPaths.rules();

    /**
     * Core.Id → rule node, indexed across every {@code rules/} package. A rule may appear in
     * several packages (one per standard/version it targets); the bodies must be identical apart
     * from package membership, and {@link #buildNativeIndex()} <b>asserts</b> that on every build
     * rather than trusting the one-time 14014/14014 verification (PLAN-classifier-redesign §5e) — a
     * regen bug producing per-package divergent bodies must fail the resolution loudly, not be
     * resolved by whichever package sorts first. Cached because the corpus is large and every spec
     * resolves through it — the same pattern as the Python lane's {@code loader.py} rules-legacy
     * index.
     */
    private static volatile Map<String, ObjectNode> nativeIndex;

    private SpecLoader()
    {
    }


    public static SpecSchema load(Path specFile) throws IOException
    {
        JsonNode root = readNode(specFile);
        if (!(root instanceof ObjectNode obj))
        {
            throw new IllegalArgumentException("Spec root must be a YAML mapping: " + specFile);
        }
        resolveRefs(obj, specFile.toAbsolutePath().getParent());
        applyDmRowKeyDefaultAndValidate(obj, specFile.toString());
        SpecDefineXml.validate(obj.get("define_xml"), specFile.toString());
        return YAML.treeToValue(obj, SpecSchema.class);
    }


    private static JsonNode readNode(Path file) throws IOException
    {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        ObjectMapper mapper = name.endsWith(".json") ? JSON : YAML;
        try (var in = Files.newInputStream(file))
        {
            return mapper.readTree(in);
        }
    }


    /**
     * Walk the tree replacing every object that contains a {@code $ref} key with the contents of
     * the referenced file. {@code $ref} may coexist with sibling keys, in which case the siblings
     * are merged on top of the referenced content (sibling wins).
     */
    private static void resolveRefs(JsonNode node, Path baseDir) throws IOException
    {
        if (node instanceof ObjectNode obj)
        {
            JsonNode coreIdNode = obj.get("coreId");
            if (coreIdNode != null && coreIdNode.isTextual())
            {
                resolveCoreId(obj, coreIdNode.asText());
                // Resolve any nested $ref/coreId in the merged rule or override, mirroring
                // loader.py's post-merge recursion so the two loaders stay exact mirrors.
                resolveRefs(obj, baseDir);
                return;
            }
            JsonNode refNode = obj.get("$ref");
            if (refNode != null && refNode.isTextual())
            {
                Path target = baseDir.resolve(refNode.asText()).normalize();
                JsonNode loaded = readNode(target);
                obj.remove("$ref");
                // If the referenced file is an object, merge it under the current node:
                // existing siblings on `obj` win (per common $ref convention).
                if (loaded instanceof ObjectNode loadedObj)
                {
                    for (Map.Entry<String, JsonNode> e : loadedObj.properties())
                    {
                        if (!obj.has(e.getKey()))
                        {
                            obj.set(e.getKey(), e.getValue());
                        }
                    }
                    // Recurse into the merged result
                    resolveRefs(obj, target.getParent());
                }
                else
                {
                    // Non-object refs would require a parent-replacement we can't do here.
                    // Limit support to object refs in M1; arrays/scalars come later if needed.
                    throw new IOException(
                            "$ref to non-object content not supported (yet): " + target);
                }
                return;
            }
            // No $ref on this node — recurse into children.
            for (Map.Entry<String, JsonNode> e : obj.properties())
            {
                resolveRefs(e.getValue(), baseDir);
            }
        }
        else if (node != null && node.isArray())
        {
            for (JsonNode child : node)
            {
                resolveRefs(child, baseDir);
            }
        }
    }


    /**
     * Resolves a {@code coreId} rule reference from the shipped native corpus: takes the
     * {@code rules/} package entry, drops its {@code Authorities} membership block, merges it under
     * {@code obj} (existing siblings win), then deep-merges an optional sibling {@code override}
     * object on top (override wins). An {@code override} touching {@code Check} is rejected — on an
     * expression {@code Check} the deep merge would leave both {@code expression} and the leaf
     * keys; pin the whole rule inline instead (the D1 pattern,
     * {@code PLAN-parity-java-lane-native-corpus}).
     */
    private static void resolveCoreId(ObjectNode obj, String coreId) throws IOException
    {
        ObjectNode entry = nativeRuleOrNull(coreId);
        if (entry == null)
        {
            throw new IOException("coreId " + coreId + " is not in any rules/ package ("
                    + RULES_NATIVE + ") — the Java lane loads the shipped native rule whole, so a"
                    + " rule that no package ships cannot be spec'd on this lane");
        }
        ObjectNode rule = entry.deepCopy();
        rule.remove("Authorities");
        obj.remove("coreId");
        JsonNode override = obj.remove("override");
        if (override instanceof ObjectNode overrideObj && overrideObj.has("Check"))
        {
            throw new IOException("coreId " + coreId + ": an override: must not touch Check —"
                    + " deep-merging a leaf-form override onto the native expression Check leaves"
                    + " both `expression` and leaf keys; embed the full pinned rule inline instead"
                    + " (PLAN-parity-java-lane-native-corpus, D1)");
        }
        for (Map.Entry<String, JsonNode> e : rule.properties())
        {
            if (!obj.has(e.getKey()))
            {
                obj.set(e.getKey(), e.getValue());
            }
        }
        if (override instanceof ObjectNode overrideObj)
        {
            deepMerge(obj, overrideObj);
        }
    }


    /**
     * The shipped {@code rules/} entry for {@code coreId}, or {@code null} when no package carries
     * it. Package-private so {@code NoStaleFixtureGuardTest} compares fixtures against exactly what
     * a {@code coreId:} redirect would yield.
     */
    static ObjectNode nativeRuleOrNull(String coreId) throws IOException
    {
        Map<String, ObjectNode> index = nativeIndex;
        if (index == null)
        {
            synchronized (SpecLoader.class)
            {
                index = nativeIndex;
                if (index == null)
                {
                    index = buildNativeIndex();
                    nativeIndex = index;
                }
            }
        }
        return index.get(coreId);
    }


    private static Map<String, ObjectNode> buildNativeIndex() throws IOException
    {
        Map<String, ObjectNode> index = new LinkedHashMap<>();
        List<Path> packages;
        try (var files = Files.list(RULES_NATIVE))
        {
            packages = files.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path pkg : packages)
        {
            JsonNode rules = readNode(pkg).get("rules");
            if (!(rules instanceof ObjectNode rulesObj))
            {
                continue;
            }
            for (Map.Entry<String, JsonNode> e : rulesObj.properties())
            {
                if (!(e.getValue() instanceof ObjectNode rule))
                {
                    continue;
                }
                ObjectNode prior = index.putIfAbsent(e.getKey(), rule);
                if (prior != null && !prior.equals(rule))
                {
                    throw new IOException("rules/ package " + pkg.getFileName() + " carries "
                            + e.getKey() + " with a body different from an earlier package —"
                            + " per-id cross-package identity is the premise of coreId"
                            + " resolution; re-run the corpus regen and investigate");
                }
            }
        }
        return index;
    }


    /**
     * Recursively merges {@code source} into {@code target}; scalar/array values from source win.
     */
    private static void deepMerge(ObjectNode target, ObjectNode source)
    {
        for (Map.Entry<String, JsonNode> e : source.properties())
        {
            JsonNode existing = target.get(e.getKey());
            if (existing instanceof ObjectNode existingObj
                    && e.getValue() instanceof ObjectNode src)
            {
                deepMerge(existingObj, src);
            }
            else
            {
                target.set(e.getKey(), e.getValue());
            }
        }
    }


    /**
     * Enforces {@code OD-2} for the DM dataset:
     * <ul>
     * <li>If {@code datasets.DM} exists with no {@code row_key}, default it to
     * {@code [STUDYID, USUBJID]}.</li>
     * <li>If {@code datasets.DM.row_key} is set to anything other than the default, fail.</li>
     * </ul>
     */
    private static void applyDmRowKeyDefaultAndValidate(ObjectNode root, String specFile)
    {
        JsonNode datasets = root.get("datasets");
        if (!(datasets instanceof ObjectNode dsMap))
        {
            return;
        }
        JsonNode dm = dsMap.get("DM");
        if (!(dm instanceof ObjectNode dmObj))
        {
            return;
        }
        JsonNode rowKey = dmObj.get("row_key");
        if (rowKey == null || rowKey.isNull())
        {
            // Apply default
            dmObj.set("row_key", JSON.valueToTree(DM_DEFAULT_ROW_KEY));
            return;
        }
        // Compare with default (order-sensitive — the convention is exactly [STUDYID, USUBJID]).
        try
        {
            List<String> declared = JSON.convertValue(rowKey, new TypeReference<List<String>>()
            {
            });
            if (!DM_DEFAULT_ROW_KEY.equals(declared))
            {
                throw new IllegalArgumentException(
                        "Spec " + specFile + ": DM row_key must be [STUDYID, USUBJID] "
                                + "(default is non-negotiable per OD-2). Got: " + declared);
            }
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (RuntimeException e)
        {
            throw new IllegalArgumentException("Spec " + specFile + ": invalid datasets.DM.row_key",
                    e);
        }
    }

    // ---- Test/CLI helpers ----


    /** Parse a YAML string directly (no $ref resolution since there's no base dir). */
    public static SpecSchema loadFromString(String yaml) throws IOException
    {
        ObjectNode root = (ObjectNode) YAML.readTree(yaml);
        applyDmRowKeyDefaultAndValidate(root, "<inline>");
        SpecDefineXml.validate(root.get("define_xml"), "<inline>");
        return YAML.treeToValue(root, SpecSchema.class);
    }


    /**
     * Walk a directory and return all {@code *.yaml} / {@code *.yml} files outside of
     * {@code _templates/}. Used by the parametric JUnit runner.
     */
    public static List<Path> findSpecs(Path specsRoot) throws IOException
    {
        List<Path> out = new ArrayList<>();
        try (var stream = Files.walk(specsRoot))
        {
            stream.filter(Files::isRegularFile).filter(p ->
            {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".yaml") || n.endsWith(".yml");
            }).filter(p -> !p.toString()
                    .contains(java.io.File.separator + "_templates" + java.io.File.separator))
                    .sorted().forEach(out::add);
        }
        return out;
    }


    // Internal use only — kept around for potential debug introspection of the resolved tree.
    static LinkedHashMap<String, Object> dump(SpecSchema schema)
    {
        return YAML.convertValue(schema, new TypeReference<LinkedHashMap<String, Object>>()
        {
        });
    }
}
