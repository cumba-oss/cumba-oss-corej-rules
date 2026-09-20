package net.cumba.corej.core.expr.convert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.cumba.corej.core.RulePackageManifest;
import net.cumba.corej.core.exec.RuleClassifier;
import net.cumba.corej.core.model.Executability;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleFamily;
import net.cumba.corej.core.model.Sensitivity;
import org.jspecify.annotations.Nullable;

/**
 * Offline developer tool — the <em>assemble</em> half of the split-storage migration
 * ({@code plans/done/PLAN-rule-storage-split.md}): reads the per-rule YAML under {@code rules-src/}
 * and rebuilds the combined JSON packages. ⚠ This line said "operator-leaf JSON packages" until
 * 2026-09-17: phase 7d of {@code PLAN-typed-expression-engine} deleted the v1 operator-leaf model,
 * and what is assembled is — and for some time already was — expression form.
 *
 * <p>
 * ⚑ <b>It writes nothing.</b> {@link #assemblePackage} returns the <em>canonical</em> node — the
 * {@code Authorities} join, the {@code Sensitivity} derive-and-validate gate (validation only since
 * {@code PLAN-retire-corpus-transforms.md} phase 1: the derived value is discarded, the shipped
 * corpus carries the field only where authored) and the materialised provider {@code Requirements}
 * (the presence-operator normalisation it once ran moved to the writer, {@code PresenceSpelling}).
 * {@link RuleCorpusGenerator} is the driver: it hands that node to {@link RuleExprGenerator} to
 * produce the shipped {@code rules/} corpus.
 * </p>
 *
 * <p>
 * Packages are split by <strong>family</strong> as well as {@code (Standard, Version)}: one file
 * per {@code (family, standard, version)} tuple, named
 * {@code rules-<family>-<standard>-<version>.json} (see
 * {@code plans/done/PLAN-family-scoped-rule-packages.md}). The tuples are
 * <strong>auto-discovered</strong> from the corpus — every {@code (Name, Version)} a rule's
 * {@code Standards} block names, crossed with the rule's family ({@link RuleFamily#of} of its
 * {@code Core.Id}). An authoritative {@code packages.json} manifest ({@link RulePackageManifest})
 * is emitted alongside, so readers never parse file names. Each package's rule map is keyed by
 * {@code Core.Id} and sorted; family gates membership, {@code (Name, Version)} still selects the
 * standard tuple.
 * </p>
 *
 * <p>
 * Run it through {@code scripts/regen-rules-corpus.sh}, which drives {@link RuleCorpusGenerator} on
 * a classpath guarded against a stale, installed engine ({@code scripts/lib/corpus-gen-env.sh}).
 * There is deliberately no standalone entry point here.
 * </p>
 */
public final class RuleStorageAssembler
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Binds a {@code rules-src} check node to {@link Rule} for derivation. Lenient because the
     * source YAML carries authoring-only blocks ({@code Standards}, {@code Source}) that the
     * runtime model does not model.
     */
    private static final ObjectMapper LENIENT = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * The {@code Executability} value that parks a rule — {@link Executability#NOT_EXECUTABLE}'s
     * authored form. Taken from the enum rather than re-typed, so a rename cannot silently turn the
     * skip into a no-op.
     */
    private static final String NOT_EXECUTABLE = Executability.NOT_EXECUTABLE.getJsonValue();

    private static final Path SRC_DIR = Path.of("rules-src");

    private final Map<String, JsonNode> checks;

    private final Map<String, JsonNode> docs;

    /**
     * The packaging-time standards map (R6/R9). Resolved against the <b>module</b> base directory,
     * not {@code srcDir}: it describes the shipped corpus, so a synthetic fixture directory in a
     * test neither needs nor supplies one (it then declares nothing).
     */
    private final PackageStandardsMap standardsMap;

    /**
     * Loads the {@code rules-src/} corpus into memory.
     *
     * @throws IOException
     *             if the YAML cannot be read
     */
    public RuleStorageAssembler() throws IOException
    {
        this(SRC_DIR);
    }


    /**
     * Loads an arbitrary {@code rules-src}-shaped directory. Package-private so a test can drive
     * the assembler over a synthetic two-rule corpus and observe {@link #dropParkedRules}
     * end-to-end — through {@link #discoverPackages()} and {@link #assemblePackage} both, which is
     * the claim that matters (the skip is in ONE place and every output inherits it).
     *
     * @param srcDir
     *            a directory holding {@code checks/} and (optionally) {@code docs/}
     * @throws IOException
     *             if the YAML cannot be read
     */
    RuleStorageAssembler(Path srcDir) throws IOException
    {
        this.checks = dropParkedRules(loadTree(srcDir.resolve("checks"), "Core/Id"));
        this.docs = loadTree(srcDir.resolve("docs"), "Core");
        this.standardsMap = PackageStandardsMap.loadOrEmpty(Path.of(""));
    }


    /**
     * Removes every {@code rules-src/} check declaring {@code Executability: "Not Executable"} —
     * {@code Fix #159}, {@code plans/done/PLAN-not-executable-parks-a-rule.md}.
     *
     * <p>
     * <b>ONE place, deliberately.</b> The filter sits on {@link #checks} rather than on either
     * emission path, so <em>both</em> shipped corpora inherit it from a single decision:
     * {@link #discoverPackages()} never derives a {@code (family, standard, version)} tuple from a
     * parked rule and {@link #assemblePackage} never selects one, so the rule reaches neither
     * branch of {@code RuleCorpusGenerator} — the legacy copy and the native conversion are both
     * built from the assembled node. A per-branch skip would be two things to keep in step.
     * </p>
     *
     * <p>
     * ⚠ Until 2026-08-06 the reason given here was different: {@code rules/} was derived
     * <em>from</em> {@code rules-legacy/}, so a rule the assembler omitted was absent from
     * {@code rules/} by transitivity. The two corpora now derive independently, and the conclusion
     * survives only because the filter sits <em>upstream of the fan-out</em>, on the loaded
     * {@code checks} map. Move it downstream and it stops covering both.
     * </p>
     *
     * <p>
     * ⚑ <b>That is what parks the rule in the Python fork.</b> {@code rules-legacy/} is the fork's
     * corpus and the fork has no {@code Executability}-driven load behaviour at all — it reads the
     * field only to echo it into its report. Omitting the rule from the corpus is therefore the
     * whole mechanism on that side: the engine change stays Java-only while the parking is
     * universal. Skipping only {@code rules/} would park the rule in Java and leave the fork
     * over-reporting, which is backwards.
     * </p>
     *
     * <p>
     * ⚠ <b>This does NOT make {@code RulePackageLoader.removeParkedRules} redundant, or the
     * reverse. Do not delete either as duplication.</b> The rule editor, the CDISC-Library API path
     * and any hand-authored package bypass this tool entirely, so a {@code Not Executable} rule can
     * still reach the engine without ever passing through here; equally, the loader-side skip
     * cannot keep the rule out of {@code rules-legacy/}, which the fork loads with no such guard.
     * </p>
     *
     * @param loaded
     *            the freshly-loaded {@code rules-src/checks} tree
     * @return the same tree minus every parked rule
     */
    private static Map<String, JsonNode> dropParkedRules(Map<String, JsonNode> loaded)
    {
        Map<String, JsonNode> out = new java.util.LinkedHashMap<>();
        List<String> parked = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : loaded.entrySet())
        {
            if (NOT_EXECUTABLE.equals(textOrNull(entry.getValue().get("Executability"))))
            {
                parked.add(entry.getKey());
            }
            else
            {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        if (!parked.isEmpty())
        {
            System.out.printf(Locale.ROOT,
                    "[assemble] %d rule(s) parked by Executability: \"Not Executable\","
                            + " omitted from BOTH corpora: %s%n",
                    parked.size(), String.join(", ", parked.stream().sorted().toList()));
        }
        return out;
    }

    /** A distinct shipped package identity: {@code (family, standard Name, version)}. */
    public record Triple(String family, String standard, String version)
    {
    }

    /**
     * Every distinct {@code (family, standard, version)} tuple the corpus targets — the rule's
     * family ({@link RuleFamily#of} of its {@code Core.Id}) crossed with each
     * {@code (Name, Version)} its {@code Standards} block names. Sorted by the resulting package
     * file name for a deterministic emission order.
     *
     * @return the discovered package identities
     */
    public List<Triple> discoverPackages()
    {
        Set<Triple> triples = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> entry : checks.entrySet())
        {
            String family = RuleFamily.of(entry.getKey());
            for (String[] sv : standardsOf(entry.getValue()))
            {
                triples.add(new Triple(family, sv[0], sv[1]));
            }
        }
        List<Triple> out = new ArrayList<>(triples);
        out.sort(Comparator.comparing(t -> packageFileName(t.family(), t.standard(), t.version())));
        return out;
    }


    /**
     * Assembles one family-scoped package: every rule of {@code family} whose {@code Standards}
     * names {@code (name, version)}, keyed by {@code Core.Id}, sorted.
     *
     * @param family
     *            the rule family (e.g. {@code CDISC}); gates membership
     * @param name
     *            standard name (e.g. {@code SDTMIG})
     * @param version
     *            standard version (e.g. {@code 3.3})
     * @return the package object {@code { "rules": { <Core.Id>: <rule> } }}
     */
    public ObjectNode assemblePackage(String family, String name, String version)
    {
        Map<String, JsonNode> selected = new TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : checks.entrySet())
        {
            if (RuleFamily.of(entry.getKey()).equals(family)
                    && belongs(entry.getValue(), name, version))
            {
                selected.put(entry.getKey(),
                        rebuildRule(entry.getValue(), docs.get(entry.getKey())));
            }
        }
        ObjectNode rules = JSON.createObjectNode();
        selected.forEach(rules::set);
        ObjectNode pkg = JSON.createObjectNode();
        // Plan 2 R6 — the declaration is written HERE, the single choke point every regeneration
        // path shares (RuleCorpusGenerator and both corpus round-trip tests each drive their own
        // loop around this method). Declaration first, then the rules: a package's header reads
        // before its body, and Jackson serialises in insertion order.
        java.util.List<net.cumba.corej.core.model.StandardRef> declared = declaredStandards(name,
                version);
        if (!declared.isEmpty())
        {
            pkg.set("standards", jsonOf(declared));
        }
        pkg.set("rules", rules);
        return pkg;
    }


    /**
     * The library standards a package at {@code (name, version)} declares, from
     * {@code rules-src/package-standards.json} (R6/R9).
     *
     * @param name
     *            the standard display name
     * @param version
     *            the package version
     * @return the declared standards, empty when the standard is unmapped
     */
    java.util.List<net.cumba.corej.core.model.StandardRef> declaredStandards(String name,
            String version)
    {
        return standardsMap.forPackage(name, version);
    }


    /** The distinct {@code (Name, Version)} pairs a check's {@code Standards} block names. */
    private static List<String[]> standardsOf(JsonNode check)
    {
        List<String[]> out = new ArrayList<>();
        JsonNode standards = check.get("Standards");
        if (standards == null)
        {
            return out;
        }
        for (JsonNode authority : standards)
        {
            JsonNode stds = authority.get("Standards");
            if (stds == null)
            {
                continue;
            }
            for (JsonNode std : stds)
            {
                String name = textOrNull(std.get("Name"));
                String version = textOrNull(std.get("Version"));
                if (name != null && version != null)
                {
                    out.add(new String[]
                    {
                            name, version
                    });
                }
            }
        }
        return out;
    }


    /** True iff the check's {@code Standards} block names the given {@code (name, version)}. */
    private static boolean belongs(JsonNode check, String name, String version)
    {
        JsonNode standards = check.get("Standards");
        if (standards == null)
        {
            return false;
        }
        for (JsonNode authority : standards)
        {
            JsonNode stds = authority.get("Standards");
            if (stds == null)
            {
                continue;
            }
            for (JsonNode std : stds)
            {
                if (name.equals(textOrNull(std.get("Name")))
                        && version.equals(textOrNull(std.get("Version"))))
                {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * Rebuilds a legacy rule node: check fields minus {@code Standards}, plus rejoined
     * {@code Authorities} and the materialised provider requirements.
     */
    private static ObjectNode rebuildRule(JsonNode check, @Nullable JsonNode doc)
    {
        ObjectNode rule = JSON.createObjectNode();
        JsonNode standards = check.get("Standards");
        rule.set("Authorities",
                buildAuthorities(standards, doc == null ? null : doc.get("Citations")));
        if (!check.hasNonNull("Sensitivity"))
        {
            // Validation only, result discarded — the THROW is the point: a rule whose
            // Sensitivity is absent and underivable must abort the regen here rather than ship
            // unloadable (RuleClassifier.Confidence.NONE ⇒ IllegalStateException in derive()).
            // The derived value itself is no longer written into the assembled node: shipping
            // without the field is safe because RulePackageLoader.finishLoad re-derives it via
            // deriveOmittedFields, and the 12 authored values are copied through in authored
            // position like any other field (PLAN-retire-corpus-transforms.md phase 1, #10).
            derive(check);
        }
        // ⚑ PROVIDER REQUIREMENTS ARE NO LONGER MATERIALISED (owner ruling 2026-09-08).
        // Requirements.Library / .Define / .Dictionary DOCUMENT a fact the engine computes
        // (ProviderRequirements); they never declare one, and RulePackageLoader's gate R5 says so
        // in terms: "Omitting it is always legal ... the runtime arms read the derivation
        // regardless". Materialising them was the last step that made the SHIPPED BYTES depend on
        // the engine's semantics -- the same YAML through a different engine produced a different
        // corpus, silently. They are now passed through exactly like Sensitivity: authored ships,
        // absent stays absent. rules-src authors none today (measured 2026-09-08: 0 files), so the
        // shipped corpus simply stops carrying the 1 366 derived flag instances it used to.
        // Gate R5 still validates an AUTHORED value against the derivation, so a rule whose
        // declaration lies about what it does is still a load error.
        for (Map.Entry<String, JsonNode> field : check.properties())
        {
            if ("Standards".equals(field.getKey()))
            {
                continue;
            }
            rule.set(field.getKey(), field.getValue().deepCopy());
        }
        return rule;
    }


    /**
     * A build gate wearing a derivation's clothes: verifies the {@code Sensitivity} a source rule
     * omits is derivable ({@code PLAN-derive-rule-type-sensitivity} phase 5;
     * {@code PLAN-retire-corpus-transforms.md} phase 1 stopped writing the derived value into the
     * assembled node — the shipped corpus carries {@code Sensitivity} only where authored, and
     * {@code RulePackageLoader.finishLoad} re-derives the rest via {@code deriveOmittedFields}).
     * {@code Rule_Type} is no longer a rule field ({@code PLAN-leaf-scope-domain-inference} phase
     * 7): the engine infers the evaluation domain from the Check at load, so nothing is derived for
     * it here.
     *
     * @throws IllegalStateException
     *             when the rule body gives no basis for the value; a silent omission would ship an
     *             unloadable rule
     */
    private static void derive(JsonNode check)
    {
        Rule rule;
        try
        {
            rule = LENIENT.treeToValue(check, Rule.class);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException("cannot bind rule for derivation: " + check.get("Core"),
                    e);
        }
        // 7b: materialise the Bindings before deriving — an external binder must run this itself
        // (RulePackageLoader.normalizeOperations javadoc); without it the classifier derives over
        // a rule with no operations and the gate would judge a different rule than ships.
        net.cumba.corej.core.RulePackageLoader.normalizeOperations(rule);
        String coreId = check.path("Core").path("Id").asText("<unknown>");
        RuleClassifier.Derived<Sensitivity> sensitivity = RuleClassifier.deriveSensitivity(rule);
        if (sensitivity.confidence() == RuleClassifier.Confidence.NONE)
        {
            throw new IllegalStateException("Rule " + coreId + ": Sensitivity is absent and cannot"
                    + " be derived (" + sensitivity.rationale() + ") — author it explicitly");
        }
    }


    /**
     * Re-joins citations onto the {@code Standards} tree, producing the legacy {@code Authorities}.
     */
    private static ArrayNode buildAuthorities(@Nullable JsonNode standards,
            @Nullable JsonNode citations)
    {
        ArrayNode out = JSON.createArrayNode();
        if (standards == null || !standards.isArray())
        {
            return out;
        }
        List<String> keys = referenceKeys(standards);
        int idx = 0;
        for (JsonNode authority : standards)
        {
            ObjectNode a = JSON.createObjectNode();
            a.set("Organization", authority.get("Organization").deepCopy());
            ArrayNode stds = JSON.createArrayNode();
            for (JsonNode std : authority.get("Standards"))
            {
                ObjectNode s = JSON.createObjectNode();
                for (Map.Entry<String, JsonNode> f : ((ObjectNode) std).properties())
                {
                    if (!"References".equals(f.getKey()))
                    {
                        s.set(f.getKey(), f.getValue().deepCopy());
                    }
                }
                ArrayNode refs = JSON.createArrayNode();
                JsonNode refList = std.get("References");
                if (refList != null)
                {
                    for (JsonNode ref : refList)
                    {
                        ObjectNode r = (ObjectNode) ref.deepCopy();
                        String key = keys.get(idx++);
                        if (citations != null && citations.has(key))
                        {
                            r.set("Citations", citations.get(key).deepCopy());
                        }
                        refs.add(r);
                    }
                }
                s.set("References", refs);
                stds.add(s);
            }
            a.set("Standards", stds);
            out.add(a);
        }
        return out;
    }

    // ---- loading & helpers ----------------------------------------------


    private static Map<String, JsonNode> loadTree(Path dir, String keyPath) throws IOException
    {
        Map<String, JsonNode> out = new java.util.HashMap<>();
        if (!Files.isDirectory(dir))
        {
            return out;
        }
        try (Stream<Path> files = Files.walk(dir))
        {
            files.filter(p -> p.toString().endsWith(".yaml")).forEach(p ->
            {
                try
                {
                    JsonNode node = YAML.readTree(p.toFile());
                    String key = "Core/Id".equals(keyPath) ? node.get("Core").get("Id").asText()
                            : node.get("Core").asText();
                    out.put(key, node);
                }
                catch (IOException e)
                {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return out;
    }


    private static @Nullable String textOrNull(@Nullable JsonNode node)
    {
        return node == null || node.isNull() ? null : node.asText();
    }


    // Canonical package serialization (indented JSON + trailing LF). Shared by main and tests.
    /**
     * Serialises declared standards to the array a package file carries (Plan 2, R6).
     *
     * @param refs
     *            the declared standards
     * @return the JSON array node
     */
    static com.fasterxml.jackson.databind.node.ArrayNode jsonOf(
            java.util.List<net.cumba.corej.core.model.StandardRef> refs)
    {
        return JSON.valueToTree(refs);
    }


    static String serialize(ObjectNode pkg)
        throws com.fasterxml.jackson.core.JsonProcessingException
    {
        return JSON.writeValueAsString(pkg) + "\n";
    }


    /**
     * {@code (CDISC, SDTMIG, 3.4) -> rules-cdisc-sdtmig-3-4.json},
     * {@code (CDISC, SENDIG-DART, 1.2) -> rules-cdisc-sendig-dart-1-2.json}. Each axis is encoded
     * via {@link RulePackageManifest#enc} (lower-cased, dots/spaces to dashes) — the same encoding
     * the manifest records, so the two never drift.
     */
    public static String packageFileName(String family, String name, String version)
    {
        return "rules-" + RulePackageManifest.enc(family) + "-" + RulePackageManifest.enc(name)
                + "-" + RulePackageManifest.enc(version) + ".json";
    }


    /** Read-only view of the loaded check files, for tests. */
    Map<String, JsonNode> checks()
    {
        return checks;
    }

    /** One {@code Reference} of an {@code Authorities} tree, under its stable locator key. */
    record RefCitations(String key, @Nullable JsonNode citations)
    {
    }

    /**
     * The ordered reference locator keys of an {@code Authorities}/{@code Standards} tree, used to
     * re-join doc citations: the keys are identical whether or not the {@code Citations} are
     * present, since the key derives only from organization / standard / rule-identifier.
     *
     * <p>
     * ⚑ Moved here from {@code RuleStorageSplitter} in wave 33. The splitter was the one-shot
     * {@code rules-legacy} &rarr; {@code rules-src} migration tool and went with that corpus; these
     * two helpers were the only part of it anything else used, so they moved rather than died.
     * </p>
     *
     * @param tree
     *            an {@code Authorities}-shaped array (the check file's {@code Standards} block)
     * @return the reference keys in stable walk order
     */
    static List<String> referenceKeys(@Nullable JsonNode tree)
    {
        List<String> keys = new ArrayList<>();
        for (RefCitations rc : walkReferences(tree))
        {
            keys.add(rc.key());
        }
        return keys;
    }


    /**
     * Walks every {@code Reference} of an {@code Authorities} tree in stable order, yielding a
     * deterministic locator key. The key is content-derived (organization, standard,
     * rule-identifier) with a positional {@code #n} tiebreak for the rare duplicate.
     */
    static List<RefCitations> walkReferences(@Nullable JsonNode authorities)
    {
        List<RefCitations> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        if (authorities == null || !authorities.isArray())
        {
            return out;
        }
        for (JsonNode authority : authorities)
        {
            String org = refText(authority.get("Organization"));
            JsonNode stds = authority.get("Standards");
            if (stds == null)
            {
                continue;
            }
            for (JsonNode std : stds)
            {
                JsonNode refList = std.get("References");
                if (refList == null)
                {
                    continue;
                }
                for (JsonNode ref : refList)
                {
                    JsonNode rid = ref.get("Rule_Identifier");
                    String base = String.join("|", org, refText(std.get("Name")),
                            refText(std.get("Version")), refText(std.get("Substandard")),
                            rid == null ? "" : refText(rid.get("Id")),
                            rid == null ? "" : refText(rid.get("Version")));
                    int n = seen.merge(base, 1, Integer::sum) - 1;
                    String key = n == 0 ? base : base + "#" + n;
                    out.add(new RefCitations(key, ref.get("Citations")));
                }
            }
        }
        return out;
    }


    /** Empty string for a missing or explicitly-null node, so a key never contains "null". */
    private static String refText(@Nullable JsonNode node)
    {
        return node == null || node.isNull() ? "" : node.asText();
    }
}
