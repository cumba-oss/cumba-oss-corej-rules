package net.cumba.corej.core.expr.convert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import net.cumba.corej.authoring.AuthoredRuleAssembler;
import net.cumba.corej.authoring.AuthoredRuleAssembler.StandardVersion;
import net.cumba.corej.authoring.PackageStandardsMap;
import net.cumba.corej.authoring.RuleFamily;
import net.cumba.corej.core.RulePackageManifest;
import org.jspecify.annotations.Nullable;

/**
 * Offline developer tool — the <em>assemble</em> half of the split-storage migration
 * ({@code plans/done/PLAN-rule-storage-split.md}): reads the per-rule YAML under {@code rules-src/}
 * and rebuilds the combined JSON packages.
 *
 * <p>
 * ⭐ <b>Since {@code PLAN-rule-authoring-api} (2026-10-06) this class is the corpus-wide driver
 * only.</b> The per-rule half — the {@code Authorities} join, the {@code Sensitivity}
 * derive-and-validate gate, the reference keys shared with the docs sidecar — lives in the engine's
 * {@code cumba-corej-authoring} module ({@link AuthoredRuleAssembler}), where the rule editor runs
 * the same code: one implementation, two consumers, and this corpus's regeneration is the proof
 * that the lifted code assembles every rule byte-identically. ⛔ Do not re-grow a per-rule transform
 * here; it belongs in the engine module or nowhere.
 * </p>
 *
 * <p>
 * ⚑ <b>It writes nothing.</b> {@link #assemblePackage} returns the <em>canonical</em> node.
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
 * the test classpath ({@code scripts/lib/corpus-gen-env.sh}). There is deliberately no standalone
 * entry point here.
 * </p>
 */
public final class RuleStorageAssembler
{

    /**
     * The {@code Executability} value that parks a rule —
     * {@link AuthoredRuleAssembler#NOT_EXECUTABLE}.
     */
    private static final String NOT_EXECUTABLE = AuthoredRuleAssembler.NOT_EXECUTABLE;

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
     * <b>ONE place, deliberately.</b> The filter sits on {@link #checks} rather than on the
     * emission path: {@link #discoverPackages()} never derives a
     * {@code (family, standard, version)} tuple from a parked rule and {@link #assemblePackage}
     * never selects one, so the rule reaches no package that {@code RuleCorpusGenerator} writes,
     * whether {@code rules/} or {@code rules-release/}. ⚑ Until wave 33 the generator fanned out
     * into a second, Python-facing {@code rules-legacy/} corpus, and the filter's position upstream
     * of that fan-out is what kept a parked rule out of both. That branch and the Python parity
     * lane are gone ({@code PLAN-cut-rules-legacy}), so there is one branch today.
     * </p>
     *
     * <p>
     * ⚠ <b>This does NOT make {@code RulePackageLoader.removeParkedRules} redundant, or the
     * reverse. Do not delete either as duplication.</b> A package that did not come out of this
     * tool (hand-authored, or handed to {@code RulePackageLoader.load} / {@code loadFromString}
     * from outside the shipped corpus) bypasses it, so a {@code Not Executable} rule can still
     * reach the engine; the loader-side skip is what stops it there. The editor's single-rule path
     * ({@code AuthoredRuleAssembler.assemble}) reports the same parking as an {@code INFO}
     * diagnostic and loads nothing.
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
            if (AuthoredRuleAssembler.isParked(entry.getValue()))
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
                    "[assemble] %d rule(s) parked by Executability: \"%s\","
                            + " omitted from BOTH corpora: %s%n",
                    parked.size(), NOT_EXECUTABLE,
                    String.join(", ", parked.stream().sorted().toList()));
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
            for (StandardVersion sv : AuthoredRuleAssembler.standardsOf(entry.getValue()))
            {
                triples.add(new Triple(family, sv.name(), sv.version()));
            }
        }
        List<Triple> out = new ArrayList<>(triples);
        out.sort(Comparator.comparing(t -> packageFileName(t.family(), t.standard(), t.version())));
        return out;
    }


    /**
     * Assembles one family-scoped package: every rule of {@code family} whose {@code Standards}
     * names {@code (name, version)}, keyed by {@code Core.Id}, sorted. Each rule node is
     * {@link AuthoredRuleAssembler#rebuildRule} — the editor's per-rule assembly, unchanged.
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
                    && AuthoredRuleAssembler.belongs(entry.getValue(), name, version))
            {
                selected.put(entry.getKey(), AuthoredRuleAssembler.rebuildRule(entry.getValue(),
                        docs.get(entry.getKey())));
            }
        }
        ObjectNode rules = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .objectNode();
        selected.forEach(rules::set);
        ObjectNode pkg = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        // Plan 2 R6 — the declaration is written HERE, the single choke point every regeneration
        // path shares (RuleCorpusGenerator and both corpus round-trip tests each drive their own
        // loop around this method). Declaration first, then the rules: a package's header reads
        // before its body, and Jackson serialises in insertion order.
        List<net.cumba.corej.core.model.StandardRef> declared = declaredStandards(name, version);
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
    List<net.cumba.corej.core.model.StandardRef> declaredStandards(String name, String version)
    {
        return standardsMap.forPackage(name, version);
    }

    // ---- loading & helpers ----------------------------------------------


    private static Map<String, JsonNode> loadTree(Path dir, String keyPath) throws IOException
    {
        Map<String, JsonNode> out = new HashMap<>();
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
                    JsonNode node = AuthoredRuleAssembler.readYamlTree(
                            Files.readString(p, java.nio.charset.StandardCharsets.UTF_8));
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


    // Canonical package serialization (indented JSON + trailing LF). Shared by main and tests.
    /**
     * Serialises declared standards to the array a package file carries (Plan 2, R6).
     *
     * @param refs
     *            the declared standards
     * @return the JSON array node
     */
    static ArrayNode jsonOf(List<net.cumba.corej.core.model.StandardRef> refs)
    {
        return AuthoredRuleAssembler.standardsJson(refs);
    }


    static String serialize(ObjectNode pkg) throws JsonProcessingException
    {
        return AuthoredRuleAssembler.serialize(pkg);
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

    /**
     * One {@code Reference} of an {@code Authorities} tree, under its stable locator key — the
     * corpus-side name of {@link AuthoredRuleAssembler.RefCitations}.
     */
    record RefCitations(String key, @Nullable JsonNode citations)
    {
    }

    /**
     * The ordered reference locator keys of an {@code Authorities}/{@code Standards} tree
     * ({@link AuthoredRuleAssembler#referenceKeys}); kept here as the name the corpus tools cite.
     *
     * @param tree
     *            an {@code Authorities}-shaped array (the check file's {@code Standards} block)
     * @return the reference keys in stable walk order
     */
    static List<String> referenceKeys(@Nullable JsonNode tree)
    {
        return AuthoredRuleAssembler.referenceKeys(tree);
    }


    /**
     * Walks every {@code Reference} of an {@code Authorities} tree in stable order
     * ({@link AuthoredRuleAssembler#walkReferences}).
     */
    static List<RefCitations> walkReferences(@Nullable JsonNode authorities)
    {
        List<RefCitations> out = new ArrayList<>();
        for (AuthoredRuleAssembler.RefCitations rc : AuthoredRuleAssembler
                .walkReferences(authorities))
        {
            out.add(new RefCitations(rc.key(), rc.citations()));
        }
        return out;
    }
}
