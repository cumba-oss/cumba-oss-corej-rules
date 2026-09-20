package net.cumba.corej.core.rulespec;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Censuses the <b>rulespec population</b> — every spec file under {@code rulespec/specs}, the kind
 * it declares and the corpus rule it is bound to — and renders it as
 * {@code documentation/rulespec-census.tsv}.
 *
 * <h2>Why this exists</h2>
 *
 * <p>
 * {@link RuleExecutionSpecTest} is a bare {@code @ParameterizedTest @MethodSource}: it discovers
 * its cases from the filesystem and asserts each one, but nothing anywhere asserts <b>how many
 * there are</b>. Its only defence is a non-empty check, so the corpus could fall from its full
 * population to <b>one</b> spec with every gate green. The {@code .cdt} half of the same corpus is
 * instrumented ({@code ScenarioFixtureCoverageRatchetTest.MIN_SHIPPED}) and would hard-fail; that
 * asymmetry is the defect this census closes, and the owner made the census a hard precondition of
 * retiring any spec ({@code plans/PLAN-cdt-coverage-for-spec-only-rules.md} §6,
 * {@code plans/PLAN-retire-core-family.md} D4 — both of which delete or re-key specs in bulk).
 * </p>
 *
 * <h2>The census is per-file, and the rule key is part of the row</h2>
 *
 * <p>
 * ⛔ A count alone is not enough, and that was <b>measured</b> on a sibling instrument the day
 * before this one was written: the engine repo's {@code TargetInvariantMarkerCensusTest} asserted a
 * total only, so deleting a statement from one file while adding one to another kept the total
 * intact and the census green ({@code 8f7eb53}). The same hole applies here in three shapes a total
 * cannot see — a spec deleted while another is added, a spec renamed, and a spec <b>re-keyed onto a
 * different rule</b>, which is precisely the bulk edit {@code PLAN-retire-core-family} D4 performs.
 * So a row is {@code path → (kind, rule)} and the comparison is an exact equality over the whole
 * set.
 * </p>
 *
 * <h2>⚠ How the rule key is derived, and how it is NOT</h2>
 *
 * <p>
 * From the spec's <b>own declaration</b>, in the four shapes the corpus actually uses — never from
 * the file name. Filenames agree with the key for the overwhelming majority, which is exactly what
 * makes a filename regex tempting and wrong: {@code EC-variable-count-split-family-dedup.yaml}
 * reaches its rule through a {@code $ref} to a sibling fixture, and a spec whose name and key
 * disagree (a re-key that forgot to rename, or a rename that forgot to re-key) is one of the things
 * being pinned.
 * </p>
 *
 * <ol>
 * <li>{@code rule.$ref} — the referenced file's top-level {@code Core.Id};</li>
 * <li>{@code rule.coreId} — the redirect {@link SpecLoader#resolveCoreId} resolves against the
 * shipped corpus;</li>
 * <li>{@code rule.Core.Id} — a spec that pins the whole rule inline;</li>
 * <li>no {@code rule:} block at all — a {@code check_operator} / {@code operation} micro-spec,
 * which is bound to no rule and is recorded as {@link #NO_RULE}.</li>
 * </ol>
 *
 * <p>
 * Any other shape is an {@link IOException}, never a skipped row: a spec whose binding cannot be
 * read is a spec whose deletion this census could not notice.
 * </p>
 *
 * <p>
 * ⚠ The key is read <b>without</b> consulting the generated corpus. That is deliberate: the
 * population being instrumented is the spec tree, and making its census depend on corpus generation
 * would mean a failed regen could report a plausible, empty population. Whether a key actually
 * resolves to a shipped rule is already asserted, loudly, by {@link SpecLoader#resolveCoreId} on
 * every {@link RuleExecutionSpecTest} case.
 * </p>
 *
 * <h2>Regenerating</h2>
 *
 * <p>
 * ⚠ <b>There is no generator script</b>, unlike {@code RoutingCensus} and {@code RuleDocCensus} — a
 * deliberate divergence. Those two are transcripts of the <em>engine's derivation</em>, so their
 * scripts exist to force the generator onto this tree's classes rather than an installed jar
 * ({@code scripts/lib/corpus-gen-env.sh}). This census transcribes the filesystem; it has no
 * derivation to get wrong and no classpath hazard to guard. So
 * {@link SpecCorpusPopulationRatchetTest} writes the freshly rendered artifact to
 * {@code target/rulespec-census.tsv} on <b>every</b> run and names it in the failure message:
 * review the diff it reports, then copy that file over the committed one. The regeneration path is
 * therefore exercised by every run of the test, which a script nobody runs is not.
 * </p>
 */
public final class SpecCorpusCensus
{

    /** The column header, recorded in the artifact's banner. */
    public static final String COLUMNS = "Spec\tKind\tRule";

    /** Prefix of the artifact's banner lines. Everything else is a data row. */
    public static final String COMMENT = "#";

    /** Recorded in the {@code Rule} column of a micro-spec, which is bound to no rule. */
    public static final String NO_RULE = "(none)";

    /** Directory name whose contents are authoring templates rather than specs. */
    public static final String TEMPLATE_DIR = "_templates";

    /**
     * The spec kinds {@link SpecSchema.Kind} accepts. A spec declaring anything else is an error.
     */
    public static final Set<String> KINDS = Set.of("rule", "check_operator", "operation");

    private static final YAMLMapper YAML = (YAMLMapper) new YAMLMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private SpecCorpusCensus()
    {
    }

    // ---------------------------------------------------------------- file locations


    /** The module directory (Surefire's working directory here — see the pom's override). */
    public static Path moduleDir()
    {
        return Path.of(System.getProperty("projectBasedir", "."));
    }


    /** The spec library root. */
    public static Path specsRoot()
    {
        return moduleDir().resolve("rulespec").resolve("specs");
    }


    /** The committed artifact. */
    public static Path artifactFile()
    {
        return moduleDir().resolve("documentation").resolve("rulespec-census.tsv");
    }


    /** Where {@link #render} is dropped on every run, for copying over {@link #artifactFile}. */
    public static Path regeneratedFile()
    {
        return moduleDir().resolve("target").resolve("rulespec-census.tsv");
    }

    // ---------------------------------------------------------------- model

    /**
     * One spec's census row.
     *
     * @param kind
     *            the spec's declared {@code kind}
     * @param rule
     *            the {@code Core.Id} the spec is bound to, or {@link #NO_RULE} for a micro-spec
     */
    public record Row(String kind, String rule)
    {
    }


    /**
     * The spec tree as the filesystem holds it.
     *
     * @param specs
     *            one entry per spec, keyed by its path relative to {@link #specsRoot}, using
     *            {@code /} separators so the artifact does not depend on the host separator.
     * @param templateFiles
     *            files under {@link #TEMPLATE_DIR}, which are authoring templates and not specs.
     * @param nonSpecFiles
     *            ⭐ every other file living among the specs that {@link SpecLoader#findSpecs} does
     *            <b>not</b> pick up. This list is the census's inverse-polarity arm: the spec
     *            predicate and {@code findSpecs}' filter are the same rule, so comparing the two
     *            lists cannot catch a file that <em>both</em> skip. Pinning the skipped files
     *            exactly is what makes a spec renamed to {@code .yaml.bak}, or moved under
     *            {@code fixtures/} with its extension changed, red rather than invisible.
     * @param filesWalked
     *            how many regular files the walk visited in total, so a walk that saw almost
     *            nothing is distinguishable from a tree that genuinely holds almost nothing.
     */
    public record Snapshot(SortedMap<String, Row> specs, List<String> templateFiles,
            List<String> nonSpecFiles, int filesWalked)
    {

        /** Specs that name a rule, i.e. everything but the micro-specs. */
        public int specsNamingARule()
        {
            return (int) specs.values().stream().filter(r -> !NO_RULE.equals(r.rule())).count();
        }


        /** Micro-specs — {@code check_operator} / {@code operation}, bound to no rule. */
        public int microSpecs()
        {
            return specs.size() - specsNamingARule();
        }


        /** {@code Core.Id} → how many specs are bound to it. Micro-specs are excluded. */
        public SortedMap<String, Integer> specsPerRule()
        {
            SortedMap<String, Integer> counts = new TreeMap<>();
            for (Row row : specs.values())
            {
                if (!NO_RULE.equals(row.rule()))
                {
                    counts.merge(row.rule(), 1, Integer::sum);
                }
            }
            return counts;
        }


        /**
         * The rules carrying more than one spec, rendered {@code "<id> ×<n>"}.
         *
         * <p>
         * ⭐ Pinned separately from the per-file rows on purpose. The rows determine these numbers,
         * so as an assertion it is redundant — but the rows live in a regenerable file and these
         * numbers live in Java source, and a lane that regenerates the artifact without reading it
         * has not yet touched the source. That is the layer which makes a blind regeneration red.
         * </p>
         */
        public List<String> multiSpecRules()
        {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, Integer> e : specsPerRule().entrySet())
            {
                if (e.getValue() > 1)
                {
                    out.add(e.getKey() + " ×" + e.getValue());
                }
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- reading

    /**
     * Walks {@link #specsRoot} and reads every spec's declared kind and rule binding.
     *
     * @throws IllegalStateException
     *             when the root is missing, is not a directory, or holds no spec at all. ⛔ A
     *             vacuous pass is the failure mode this whole census exists to remove, so an absent
     *             or empty input is an error and never a skip — the trap {@code check_versions.sh}
     *             CHECK 4 learned the hard way.
     * @throws IOException
     *             when a spec cannot be read, cannot be parsed, declares an unknown {@code kind},
     *             or carries a rule binding in no recognised shape.
     */
    public static Snapshot snapshot() throws IOException
    {
        return snapshot(specsRoot());
    }


    /** {@link #snapshot()} against an explicit root — the sensitivity arm plants its own tree. */
    public static Snapshot snapshot(Path root) throws IOException
    {
        if (!Files.isDirectory(root))
        {
            throw new IllegalStateException("the rulespec root " + root.toAbsolutePath()
                    + " is not a directory; a census over a missing population would report a"
                    + " comfortable zero, so this is an error rather than a skip");
        }
        SortedMap<String, Row> specs = new TreeMap<>();
        List<String> templates = new ArrayList<>();
        List<String> others = new ArrayList<>();
        int walked = 0;
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root))
        {
            files = stream.filter(Files::isRegularFile).toList();
        }
        for (Path file : files)
        {
            walked++;
            String relative = relativize(root, file);
            if (isTemplate(relative))
            {
                templates.add(relative);
            }
            else if (isSpecFileName(file))
            {
                specs.put(relative, rowOf(file));
            }
            else
            {
                others.add(relative);
            }
        }
        if (specs.isEmpty())
        {
            throw new IllegalStateException("no specs found under " + root.toAbsolutePath() + " ("
                    + walked + " file(s) walked); an empty population is an error here,"
                    + " never a green census");
        }
        templates.sort(null);
        others.sort(null);
        return new Snapshot(specs, List.copyOf(templates), List.copyOf(others), walked);
    }


    /** Repository-independent, separator-independent row key. */
    static String relativize(Path root, Path file)
    {
        return root.relativize(file).toString().replace('\\', '/');
    }


    /**
     * Whether the path sits under {@link #TEMPLATE_DIR} — the one directory {@code findSpecs}
     * skips.
     */
    static boolean isTemplate(String relative)
    {
        return relative.equals(TEMPLATE_DIR) || relative.startsWith(TEMPLATE_DIR + "/");
    }


    /**
     * The extension test {@link SpecLoader#findSpecs} applies. ⚠ Deliberately the same rule, not an
     * independent one — see {@link Snapshot#nonSpecFiles()} for where the independence lives.
     */
    static boolean isSpecFileName(Path file)
    {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }


    /** Reads one spec's declared kind and rule binding. */
    static Row rowOf(Path specFile) throws IOException
    {
        JsonNode root = readNode(specFile);
        if (!(root instanceof ObjectNode obj))
        {
            throw new IOException(specFile + ": a spec must be a YAML mapping, found "
                    + (root == null ? "nothing" : root.getNodeType()));
        }
        JsonNode kindNode = obj.get("kind");
        if (kindNode == null || !kindNode.isTextual() || !KINDS.contains(kindNode.asText()))
        {
            throw new IOException(specFile + ": kind: must be one of " + new TreeSet<>(KINDS)
                    + ", found " + kindNode);
        }
        return new Row(kindNode.asText(), ruleKeyOf(obj, kindNode.asText(), specFile));
    }


    /** The rule binding, in the four shapes documented on the class. */
    private static String ruleKeyOf(ObjectNode spec, String kind, Path specFile) throws IOException
    {
        JsonNode ruleNode = spec.get("rule");
        if (ruleNode == null || ruleNode.isNull())
        {
            if ("rule".equals(kind))
            {
                throw new IOException(specFile + ": kind: rule with no rule: block — the spec is"
                        + " bound to nothing this census could notice the deletion of");
            }
            return NO_RULE;
        }
        if (!(ruleNode instanceof ObjectNode rule))
        {
            throw new IOException(
                    specFile + ": rule: must be a mapping, found " + ruleNode.getNodeType());
        }
        String key = declaredKey(rule, specFile);
        JsonNode pinned = spec.path("pinned_from").path("core_id");
        if (pinned.isTextual() && !pinned.asText().equals(key))
        {
            throw new IOException(specFile + ": pinned_from.core_id is " + pinned.asText()
                    + " but the rule resolves to " + key
                    + " — a pinned spec whose provenance disagrees with its own rule is bound to"
                    + " one rule and documented against another");
        }
        return key;
    }


    private static String declaredKey(ObjectNode rule, Path specFile) throws IOException
    {
        JsonNode ref = rule.get("$ref");
        if (ref != null && ref.isTextual())
        {
            Path target = specFile.toAbsolutePath().getParent().resolve(ref.asText()).normalize();
            JsonNode referenced = readNode(target);
            return require(referenced == null ? null : referenced.path("Core").path("Id"),
                    specFile + ": rule.$ref -> " + target + " carries no top-level Core.Id");
        }
        JsonNode coreId = rule.get("coreId");
        if (coreId != null && coreId.isTextual())
        {
            return require(coreId, specFile + ": rule.coreId is blank");
        }
        JsonNode inline = rule.path("Core").path("Id");
        if (inline.isTextual())
        {
            return require(inline, specFile + ": rule.Core.Id is blank");
        }
        throw new IOException(specFile + ": cannot derive the rule binding — a spec's rule: block"
                + " must carry $ref, coreId or Core.Id. An unreadable binding is an error, not an"
                + " unrecorded row: the whole point of this census is that a spec cannot leave the"
                + " population unnoticed.");
    }


    private static String require(JsonNode node, String message) throws IOException
    {
        if (node == null || !node.isTextual() || node.asText().isBlank())
        {
            throw new IOException(message);
        }
        return node.asText();
    }


    /** Reads YAML or JSON, chosen by extension — the same dispatch {@link SpecLoader} uses. */
    private static JsonNode readNode(Path file) throws IOException
    {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        ObjectMapper mapper = name.endsWith(".json") ? JSON : YAML;
        try (var in = Files.newInputStream(file))
        {
            return mapper.readTree(in);
        }
    }

    // ---------------------------------------------------------------- diffing


    /**
     * A per-spec diff of two censuses — <b>the artifact's payload</b>.
     *
     * <p>
     * A byte-level assertion over a thousand rows names one test and prints an unreadable dump. The
     * bulk edits this census gates ({@code PLAN-retire-core-family} D4 re-keys hundreds of specs)
     * need to be told <em>which</em> rows moved, and in which of the four ways.
     * </p>
     *
     * @return one line per differing spec, sorted by path; empty when the two agree.
     */
    public static List<String> diff(Map<String, Row> expected, Map<String, Row> actual)
    {
        List<String> out = new ArrayList<>();
        Set<String> paths = new TreeSet<>(expected.keySet());
        paths.addAll(actual.keySet());
        for (String path : paths)
        {
            Row e = expected.get(path);
            Row a = actual.get(path);
            if (e == null)
            {
                out.add(path + ": NEW — kind " + a.kind() + ", rule " + a.rule());
            }
            else if (a == null)
            {
                out.add(path + ": GONE — the committed census has it as kind " + e.kind()
                        + ", rule " + e.rule());
            }
            else if (!e.equals(a))
            {
                out.add(path + ":" + field("rule", e.rule(), a.rule())
                        + field("kind", e.kind(), a.kind()));
            }
        }
        return out;
    }


    private static String field(String name, String expected, String actual)
    {
        return expected.equals(actual) ? ""
                : " " + name + " \"" + expected + "\" → \"" + actual + "\"";
    }

    // ---------------------------------------------------------------- rendering


    /** Renders the whole artifact. */
    public static String render(Snapshot s)
    {
        StringBuilder b = new StringBuilder(96 * (s.specs().size() + 32));
        b.append("# Generated by SpecCorpusCensus. DO NOT HAND-EDIT.\n");
        b.append("# SpecCorpusPopulationRatchetTest writes a fresh copy to\n");
        b.append("#     target/rulespec-census.tsv\n");
        b.append(
                "# on every run and fails the build when this file stops matching the spec tree,\n");
        b.append(
                "# reporting the difference as a per-spec diff rather than a byte dump. To accept\n");
        b.append(
                "# a deliberate change: read that diff, then copy the target/ file over this one.\n");
        b.append("#\n");
        b.append(
                "# WHY IT EXISTS. RuleExecutionSpecTest discovers its cases from the filesystem\n");
        b.append(
                "# and asserts every one of them, but nothing asserted HOW MANY there were: its\n");
        b.append(
                "# only defence was a non-empty check, so the corpus could fall to a single spec\n");
        b.append(
                "# with every gate green, while the .cdt half of the same corpus is instrumented\n");
        b.append("# and would hard-fail. The owner made this census a hard precondition of\n");
        b.append("# retiring any spec (PLAN-cdt-coverage-for-spec-only-rules.md section 6).\n");
        b.append("#\n");
        b.append(
                "# Rule is the spec's OWN declared binding — rule.$ref -> Core.Id, rule.coreId,\n");
        b.append("# rule.Core.Id, or ").append(NO_RULE)
                .append(" for a micro-spec bound to no rule. Never the\n");
        b.append(
                "# file name: EC-variable-count-split-family-dedup.yaml reaches its rule through a\n"
                        + "# $ref, and a spec whose\n");
        b.append("# name and key disagree is one of the things being pinned.\n");
        b.append("#\n");
        b.append("# View: every file under rulespec/specs, walked; paths are relative to it.\n");
        b.append("#\n");
        b.append("# Specs: ").append(s.specs().size()).append('\n');
        b.append("# Specs naming a rule: ").append(s.specsNamingARule()).append('\n');
        b.append("# Micro-specs: ").append(s.microSpecs()).append('\n');
        b.append("# Distinct rule ids: ").append(s.specsPerRule().size()).append('\n');
        b.append("# Rules carrying more than one spec: ").append(s.multiSpecRules().size())
                .append('\n');
        b.append("# Template files (not specs): ").append(s.templateFiles().size()).append('\n');
        b.append("# Other files among the specs (not specs): ").append(s.nonSpecFiles().size())
                .append('\n');
        b.append("# Columns: ").append(COLUMNS).append('\n');
        for (Map.Entry<String, Row> e : s.specs().entrySet())
        {
            b.append(e.getKey()).append('\t').append(e.getValue().kind()).append('\t')
                    .append(e.getValue().rule()).append('\n');
        }
        return b.toString();
    }


    /**
     * Parses a rendered artifact back into rows.
     *
     * @throws IllegalArgumentException
     *             on a row that is not exactly three tab-separated fields, or on a duplicate path —
     *             a truncated or hand-merged artifact must fail loudly rather than pin half a
     *             population.
     */
    public static SortedMap<String, Row> parse(String text)
    {
        SortedMap<String, Row> rows = new TreeMap<>();
        int lineNumber = 0;
        for (String line : text.split("\n", -1))
        {
            lineNumber++;
            if (line.isEmpty() || line.startsWith(COMMENT))
            {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 3)
            {
                throw new IllegalArgumentException("rulespec-census.tsv line " + lineNumber
                        + ": expected 3 tab-separated fields (" + COLUMNS + "), found "
                        + fields.length + ": " + line);
            }
            if (rows.put(fields[0], new Row(fields[1], fields[2])) != null)
            {
                throw new IllegalArgumentException("rulespec-census.tsv line " + lineNumber
                        + ": duplicate spec path " + fields[0]);
            }
        }
        return rows;
    }


    /** Writes {@link #render} where {@link #regeneratedFile} says, creating {@code target/}. */
    public static Path writeRegenerated(Snapshot s) throws IOException
    {
        Path out = regeneratedFile();
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, render(s), StandardCharsets.UTF_8);
        return out;
    }
}
