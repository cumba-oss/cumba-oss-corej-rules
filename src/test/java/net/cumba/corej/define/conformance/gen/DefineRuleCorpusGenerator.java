package net.cumba.corej.define.conformance.gen;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.TreeSet;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.DefineRulePackage;
import net.cumba.corej.define.conformance.rule.DefineRulePackageManifest;
import net.cumba.corej.define.conformance.rule.RuleRepository;
import net.cumba.corej.define.conformance.rule.RuleSet;

/**
 * Offline developer tool — the Define-XML counterpart of
 * {@code net.cumba.corej.core.expr.convert.RuleCorpusGenerator}. Assembles the authored per-rule
 * YAML under {@code rules-define-src/checks/<FAMILY>/} into the shipped packages under
 * {@code rules-define/}, plus their {@code packages.json}.
 *
 * <pre>
 * rules-define-src/checks/{CDISC,PMDA}/&lt;Rule_Id&gt;.yaml
 *      │
 *      └── RuleRepository.parse ──&gt; ConformanceRule
 *            │
 *            └── group by (Rule_Set × each Applicable_Version)
 *                  │
 *                  └── rules-define/rules-define-&lt;family&gt;-&lt;version&gt;.json + packages.json
 * </pre>
 *
 * <p>
 * <b>A rule is published once per version it declares.</b> 292 of the 409 authored rules apply to
 * both 2.0 and 2.1, so 409 sources become 701 package entries. That mirrors the CDISC data corpus,
 * where a rule ships in every standard/version package it applies to, and it is the reason exactly
 * one version may be loaded at a time.
 * </p>
 *
 * <p>
 * ⚠ Serialisation goes through {@link DefineRulePackage} and Jackson's own annotations, never a
 * hand-rolled writer and never a naming strategy. {@link ConformanceRule}'s fields are explicitly
 * named ({@code Rule_Id}, {@code Applicable_Versions}, …) while everything inside
 * {@code CheckDefinition} uses raw component names ({@code kind}, {@code className}, …); a uniform
 * renaming would break custom-check resolution silently, at evaluation time.
 * </p>
 *
 * <p>
 * ⚠ Enum values change spelling between source and package: the YAML's {@code Severity: "Error"}
 * serialises as {@code "ERROR"}, because {@code Severity} and {@code Requires} declare a
 * {@code @JsonCreator} but no {@code @JsonValue}. This round-trips only because those creators
 * upper-case before {@code valueOf}. So the package is <em>not</em> a verbatim echo of the YAML —
 * compare objects, not text.
 * </p>
 *
 * <p>
 * The <em>source</em> path resolves against the module base directory, so the working directory
 * must be the module root — the build's exec binding sets it, and
 * {@code scripts/regen-rules-define-corpus.sh} {@code cd}s there. The <em>output</em> directory is
 * {@link #target()}; it is {@code target/rules-define/} under the build, and nothing generated is
 * committed any more ({@code plans/PLAN-rules-corpus-build-integration.md}).
 * </p>
 */
public final class DefineRuleCorpusGenerator
{

    /** The authored source, module-relative. */
    public static final Path SOURCE = Path.of("rules-define-src", "checks");

    private DefineRuleCorpusGenerator()
    {
    }


    /**
     * The generated corpus. <b>No longer a module-relative constant</b>: since
     * {@code plans/PLAN-rules-corpus-build-integration.md} the corpus is generated into
     * {@code target/rules-define/} by the build rather than committed under the module directory.
     * {@link CorpusPaths#defineRules()} is the single place that resolves it, and this accessor
     * exists so the round-trip and equivalence tests keep one name for the destination.
     *
     * @return the generated Define-XML corpus directory
     */
    public static Path target()
    {
        return CorpusPaths.defineRules();
    }


    /**
     * Rebuilds the generated Define-XML corpus from {@code rules-define-src/}.
     *
     * @param args
     *            optionally the output directory; otherwise {@link #target()}
     * @throws IOException
     *             if the source cannot be read or the output cannot be written
     */
    public static void main(String[] args) throws IOException
    {
        Path target = args.length > 0 && !args[0].isBlank() ? Path.of(args[0]) : target();

        Files.createDirectories(target);
        Result result = generate(RuleRepository.loadDirectory(SOURCE));
        for (Map.Entry<String, String> file : result.files().entrySet())
        {
            Files.writeString(target.resolve(file.getKey()), file.getValue(),
                    StandardCharsets.UTF_8);
        }
        Files.writeString(target.resolve(DefineRulePackageManifest.FILE_NAME), result.manifest(),
                StandardCharsets.UTF_8);
        System.out.println(
                "[define-gen] " + result.files().size() + " package(s), " + result.placements()
                        + " placements from " + result.sourceCount() + " authored rules");
    }

    /**
     * The generated corpus as text, so a test can produce it into a temp directory and compare
     * against the committed bytes without the generator ever writing to the source tree.
     *
     * @param files
     *            package file name &rarr; serialized JSON, in ascending file-name order
     * @param manifest
     *            the serialized {@code packages.json}
     * @param sourceCount
     *            how many authored rules were read
     * @param placements
     *            how many (rule, package) placements resulted
     */
    public record Result(SequencedMap<String, String> files, String manifest, int sourceCount,
            int placements)
    {
    }

    /** Pure assembly: authored rules in, corpus text out. No filesystem writes. */
    public static Result generate(List<ConformanceRule> aAuthored) throws IOException
    {
        // Absent, not null. The CDISC corpus emits no nulls at all, and an absent field
        // deserializes to null anyway, so this keeps the two corpora stylistically identical
        // and the packaged rule a close mirror of its authored YAML.
        // Built through JsonMapper.builder() rather than the setters: both
        // ObjectMapper.setSerializationInclusion and the builder's serializationInclusion
        // shorthand are deprecated in Jackson 2.22, and this module compiles with -Werror.
        // defaultPropertyInclusion(Value) is what the shorthand delegates to; Value.construct
        // (NON_NULL, NON_NULL) reproduces its value/content pair exactly.
        ObjectMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
                .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                        JsonInclude.Include.NON_NULL))
                .build();

        // (family, version) -> rules. TreeSet on the key pair keeps the output order stable
        // regardless of the order the source directory happened to be walked in.
        SequencedMap<String, List<ConformanceRule>> buckets = new LinkedHashMap<>();
        SequencedMap<String, Key> keys = new LinkedHashMap<>();
        for (Key key : orderedKeys(aAuthored))
        {
            keys.put(key.id(), key);
            buckets.put(key.id(), new ArrayList<>());
        }
        int placements = 0;
        for (ConformanceRule rule : aAuthored)
        {
            for (String version : rule.applicableVersions())
            {
                buckets.get(new Key(rule.ruleSet(), version).id()).add(rule);
                placements++;
            }
        }

        SequencedMap<String, String> files = new LinkedHashMap<>();
        List<DefineRulePackageManifest.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, List<ConformanceRule>> bucket : buckets.entrySet())
        {
            Key key = keys.get(bucket.getKey());
            String name = DefineRulePackageManifest.packageFileName(key.family(), key.version());
            DefineRulePackage pkg = DefineRulePackage.of(key.family(), key.version(),
                    bucket.getValue());
            files.put(name, mapper.writeValueAsString(pkg) + "\n");
            entries.add(new DefineRulePackageManifest.Entry(name, key.family(), key.version(),
                    bucket.getValue().size()));
        }
        String manifest = mapper.writeValueAsString(
                new DefineRulePackageManifest("rules-define-src", entries)) + "\n";
        return new Result(files, manifest, aAuthored.size(), placements);
    }


    /** Every {@code (family, version)} the corpus declares, family-major then version-ascending. */
    private static List<Key> orderedKeys(List<ConformanceRule> aAuthored)
    {
        TreeSet<Key> keys = new TreeSet<>(
                Comparator.comparing((Key k) -> k.family().name()).thenComparing(Key::version));
        for (ConformanceRule rule : aAuthored)
        {
            for (String version : rule.applicableVersions())
            {
                keys.add(new Key(rule.ruleSet(), version));
            }
        }
        return List.copyOf(keys);
    }

    /** A package key. {@code id()} is its stable map key. */
    private record Key(RuleSet family, String version)
    {

        String id()
        {
            return family.name() + "/" + version;
        }
    }

}
