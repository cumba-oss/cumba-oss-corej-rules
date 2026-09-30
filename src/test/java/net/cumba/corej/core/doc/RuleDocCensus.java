package net.cumba.corej.core.doc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.exec.RuleClassifier;
import net.cumba.corej.core.expr.BuiltinRegistry;
import net.cumba.corej.core.expr.convert.DualFormCheckNode;
import net.cumba.corej.core.expr.convert.RuleStorageAssembler;
import net.cumba.corej.core.model.Executability;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.corej.core.model.VariableUniverse;

/**
 * Generates the {@code rule-doc-census} region of {@code documentation/CORE-RULES-SPECIFICATION.md}
 * — the machine-derived half of that document.
 *
 * <p>
 * <b>Why this exists.</b> Wave-13 lane E measured the two rule documents as <em>append-only</em>:
 * churn {@code +2199/-32} on the specification and {@code +1554/-46} on the authoring guidelines,
 * because when behaviour changes the practice is to append a note rather than correct the sentence.
 * The consequence is arithmetic: nearly every count in the spec that is <b>not</b> anchored by a CI
 * ratchet has drifted, while every count that <b>is</b> anchored is exact. This class moves the
 * counts onto the anchored side of that line. {@link RuleDocCensusDriftTest} fails the build when
 * the committed region stops matching what this generator produces, exactly as
 * {@code ParityDebtDocDriftTest} did for the archived {@code PARITY-DEBT.md} until that doc was
 * frozen and the test deleted with the Python-parity mechanism (2026-09-17).
 * </p>
 *
 * <h2>⚠ Corpus views — the part that is easy to get wrong</h2>
 * <p>
 * The repository holds <b>three</b> distinct views of the rule corpus, and their leaf counts differ
 * by a factor of ~3.8. A census that silently picks the wrong one is worse than no census, so every
 * number this generator emits names its view:
 * </p>
 * <ul>
 * <li>{@code rules-src/checks/**.yaml} — the <b>authored source</b>, one file per {@code Core.Id}.
 * This is the only view a human edits. It carries {@code Executability} and
 * {@code Variable_Universe} but {@code Sensitivity} only on the documented override set: that field
 * is derived, so no Sensitivity census can be taken here.</li>
 * <li>the <b>assembled</b> packages — {@link RuleStorageAssembler}'s canonical output, built in
 * memory from {@code rules-src/}, fanned out one entry per (rule × standard-version package). ⚑
 * <b>This view replaced the deleted {@code rules-legacy/} corpus in wave 33</b>, and it is a strict
 * improvement: it is the same tree {@code rules-legacy} was written from, without the five
 * Python-facing degradations, and it is built rather than read, so it cannot go stale against
 * {@code rules-src}. Every Variable_Universe and Sensitivity count below is taken here, and so is
 * the Check-operator census — with {@code Sensitivity} derived by this census itself
 * ({@link RuleClassifier#deriveSensitivity}) wherever it is not authored: since
 * {@code PLAN-retire-corpus-transforms.md} phase 1 the assembler only <em>validates</em> the
 * derivation and no longer writes the derived value into the node.</li>
 * <li>{@code rules/rules-*.json} — the <b>native / expression</b> corpus, the same fan-out with the
 * Check lowered to an expression string. It carries {@code Sensitivity} only on the override set,
 * so it can only contribute view sizes. It carries the same {@code Bindings:} as the assembled view
 * (byte-aligned on {@code Check} since {@code PLAN-retire-corpus-transforms.md}, still differing in
 * assembly), which is why the census still names its view rather than merging them.</li>
 * </ul>
 * <p>
 * A "distinct" count in this census means distinct {@code Core.Id} across all packages of a view; a
 * "entries" count means package rows, i.e. the fan-out. {@code Sensitivity} is invariant per id
 * (the census asserts this — see {@link RuleDocCensusDriftTest}), so the distinct-id projection is
 * well-defined.
 * </p>
 *
 * <h2>Regenerating</h2>
 * <p>
 * {@code scripts/gen-rule-doc-census.sh}, or {@code main(args)} with the document path.
 * </p>
 */
public final class RuleDocCensus
{

    /** Opening marker of the generated region. Must appear exactly once in the document. */
    public static final String BEGIN_MARKER = "<!-- BEGIN GENERATED: rule-doc-census -->";

    /** Closing marker of the generated region. Must appear exactly once in the document. */
    public static final String END_MARKER = "<!-- END GENERATED: rule-doc-census -->";

    /**
     * The package whose numbers the specification's prose means when it says "in SDTMIG 3.4".
     *
     * <p>
     * There are four SDTMIG 3.4 packages (CDISC / DRAFT / FDA / PMDA families) and their
     * distributions differ widely, so "SDTMIG 3.4" on its own does not identify a number. One
     * package has to be named, and it is the <b>CDISC</b> one: it is the primary family, and it is
     * the closest survivor in size to the package this constant used to name.
     * </p>
     *
     * <p>
     * ⛔⛔ <b>The old argument is gone, not merely restated — read this before quoting C.5.</b> Until
     * the 2026-09 CORE-family retirement this was {@code rules-core-sdtmig-3-4.json}, and the
     * reason given was evidential: the CORE package's measured Sensitivity and Executability
     * distributions were <em>the only ones anywhere near the specification's prose values</em>
     * (prose Record/Dataset/Group = 321/108/1), the CDISC, FDA and PMDA packages being close on
     * neither field. That package no longer exists, so <b>no surviving package reproduces the prose
     * figures</b> and C.5's numbers moved by design. ⇒ The prose values are now historical: quote
     * C.5, never the prose, and never treat the two as agreeing.
     * </p>
     *
     * <p>
     * ⚠⚠ Sizes measured 2026-09-19, before the deletion: CDISC <b>394</b> · CORE 441 · DRAFT 15 ·
     * FDA <b>539</b> · PMDA <b>533</b>. FDA and PMDA were defensible alternatives; CDISC was chosen
     * for primacy and proximity. ⛔ A package that resolves to <b>nothing</b> renders this section
     * as "Rules in package: 0" with every row zero and the generator stays <em>green</em> — which
     * is why {@code RuleDocCensusDriftTest} now asserts this view is non-empty alongside the other
     * three.
     * </p>
     */
    public static final String REFERENCE_PACKAGE = "rules-cdisc-sdtmig-3-4.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Binds an assembled rule node to {@link Rule} for the Sensitivity derivation. */
    private static final ObjectMapper LENIENT = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private RuleDocCensus()
    {
    }

    // ---------------------------------------------------------------- file locations


    /** The {@code corej-rules} module directory (Surefire's working directory). */
    public static Path moduleDir()
    {
        return Path.of(System.getProperty("projectBasedir", "."));
    }


    /** The document carrying the generated region. */
    public static Path documentFile()
    {
        return moduleDir().resolve("documentation").resolve("CORE-RULES-SPECIFICATION.md");
    }

    // ---------------------------------------------------------------- region surgery


    /**
     * The generated region of {@code doc}, markers included.
     *
     * @throws IllegalArgumentException
     *             if the markers are missing, duplicated or out of order.
     */
    public static String region(String doc)
    {
        int begin = doc.indexOf(BEGIN_MARKER);
        int end = doc.indexOf(END_MARKER);
        if (begin < 0 || end < 0)
        {
            throw new IllegalArgumentException(
                    "the document is missing the generated-region markers " + BEGIN_MARKER + " / "
                            + END_MARKER);
        }
        if (doc.indexOf(BEGIN_MARKER, begin + 1) >= 0 || doc.indexOf(END_MARKER, end + 1) >= 0)
        {
            throw new IllegalArgumentException("the generated-region markers appear more than once"
                    + " — the region is no longer well defined");
        }
        if (end < begin)
        {
            throw new IllegalArgumentException("the generated-region END marker precedes BEGIN");
        }
        return doc.substring(begin, end + END_MARKER.length());
    }


    /**
     * {@code doc} with the generated region removed.
     *
     * <p>
     * This is what "the hand-written half of the document" means, and it is the text every pin that
     * must <em>not</em> be satisfiable by the generator's own output is evaluated against — see
     * {@link RuleDocEnumTableCoverageTest}, and the "in prose" column of the built-in operand
     * roster below.
     * </p>
     */
    public static String withoutRegion(String doc)
    {
        String reg = region(doc);
        return doc.replace(reg, "");
    }


    /** {@code doc} with its generated region replaced by {@code block} (markers included). */
    public static String replaceRegion(String doc, String block)
    {
        return doc.replace(region(doc), block);
    }

    // ---------------------------------------------------------------- corpus model

    /**
     * One rule as {@link RuleStorageAssembler}'s canonical package presents it: expression-form
     * {@code Check} (lowered via {@link DualFormCheckNode} for the operator census), with
     * {@code Sensitivity} taken from the authored value or derived here. {@code variableUniverse}
     * is the authored {@code Variable_Universe}, rendered as {@code Data} when absent — absent
     * means {@code Data}, full stop ({@link VariableUniverse}).
     */
    public record CensusRule(String id, String variableUniverse, String sensitivity,
            String executability, Set<String> checkOperators)
    {
    }


    /**
     * Everything the census measures, gathered once.
     *
     * @param inconsistentIds
     *            rule ids whose {@code Variable_Universe}, {@code Sensitivity} or
     *            {@code Executability} differs between the packages they are fanned out into. The
     *            distinct-id projection every table below uses is only well defined while this is
     *            empty, so it is collected here rather than being assumed.
     */
    public record Snapshot(Map<String, Integer> srcFilesByFamily, int srcFiles,
            Map<String, Integer> srcExecutability, List<String> srcParkedIds, int assembledPackages,
            int assembledEntries, Map<String, CensusRule> assembledDistinct,
            List<String> inconsistentIds, int nativePackages, int nativeEntries, int nativeDistinct,
            Map<String, CensusRule> referencePackage)
    {
    }

    /** Reads all three corpus views. */
    public static Snapshot snapshot() throws IOException
    {
        Path module = moduleDir();

        Map<String, Integer> srcByFamily = new TreeMap<>();
        Map<String, Integer> srcExec = new TreeMap<>();
        List<String> parked = new ArrayList<>();
        int srcFiles = 0;
        Path checks = module.resolve("rules-src").resolve("checks");
        try (Stream<Path> files = Files.walk(checks))
        {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted()
                    .toList())
            {
                srcFiles++;
                srcByFamily.merge(f.getParent().getFileName().toString(), 1, Integer::sum);
                JsonNode rule = YAML.readTree(Files.readString(f));
                String exec = text(rule, "Executability");
                srcExec.merge(exec, 1, Integer::sum);
                if (Executability.NOT_EXECUTABLE.getJsonValue().equals(exec))
                {
                    parked.add(f.getFileName().toString().replace(".yaml", ""));
                }
            }
        }

        // The assembled canonical packages replace the deleted rules-legacy corpus as this
        // census's operator-leaf view. They are the same tree that corpus was written from,
        // minus the five Python-facing degradations. Sensitivity is derived per rule below
        // (toCensusRule) — the assembler validates the derivation but no longer materialises
        // the value, and no shipped corpus carries the field outside the authored override set.
        Map<String, CensusRule> assembled = new TreeMap<>();
        Map<String, CensusRule> reference = new TreeMap<>();
        Set<String> inconsistent = new TreeSet<>();
        int assembledEntries = 0;
        int assembledPackages = 0;
        RuleStorageAssembler assembler = new RuleStorageAssembler();
        for (RuleStorageAssembler.Triple t : assembler.discoverPackages())
        {
            assembledPackages++;
            String file = RuleStorageAssembler.packageFileName(t.family(), t.standard(),
                    t.version());
            boolean isReference = REFERENCE_PACKAGE.equals(file);
            JsonNode rules = assembler.assemblePackage(t.family(), t.standard(), t.version())
                    .path("rules");
            assembledEntries += rules.size();
            for (Map.Entry<String, JsonNode> e : rules.properties())
            {
                CensusRule r = toCensusRule(e.getKey(), e.getValue());
                CensusRule prior = assembled.putIfAbsent(r.id(), r);
                if (prior != null && !(prior.variableUniverse().equals(r.variableUniverse())
                        && prior.sensitivity().equals(r.sensitivity())
                        && prior.executability().equals(r.executability())))
                {
                    inconsistent.add(r.id());
                }
                if (isReference)
                {
                    reference.put(r.id(), r);
                }
            }
        }

        int nativeEntries = 0;
        Set<String> nativeIds = new TreeSet<>();
        List<Path> nativePkgs = packages(CorpusPaths.rules());
        for (Path pkg : nativePkgs)
        {
            JsonNode rules = JSON.readTree(Files.readString(pkg)).path("rules");
            nativeEntries += rules.size();
            for (Map.Entry<String, JsonNode> e : rules.properties())
            {
                nativeIds.add(e.getKey());
            }
        }

        return new Snapshot(srcByFamily, srcFiles, srcExec, parked, assembledPackages,
                assembledEntries, assembled, List.copyOf(inconsistent), nativePkgs.size(),
                nativeEntries, nativeIds.size(), reference);
    }


    private static List<Path> packages(Path dir) throws IOException
    {
        try (Stream<Path> files = Files.list(dir))
        {
            return files.filter(p ->
            {
                String n = p.getFileName().toString();
                return n.startsWith("rules-") && n.endsWith(".json");
            }).sorted().toList();
        }
    }


    private static CensusRule toCensusRule(String id, JsonNode rule)
    {
        Set<String> checkOps = new TreeSet<>();
        // Dual-form: an expression Check carries no operator keys, so the raw walk below would
        // go quietly empty (measured at the flip: every distinct-operator count dropped to 0).
        // Lower it to the legacy shape first; a conversion-unlowerable Check stays in
        // expression form and contributes no operators.
        JsonNode check = DualFormCheckNode.lowerOrNull(rule.path("Check"));
        collectCheckOperators(check != null ? check : rule.path("Check"), checkOps);
        String universe = rule.hasNonNull("Variable_Universe") ? text(rule, "Variable_Universe")
                : VariableUniverse.DATA.getJsonValue();
        String sensitivity = rule.hasNonNull("Sensitivity") ? text(rule, "Sensitivity")
                : derivedSensitivity(rule);
        return new CensusRule(id, universe, sensitivity, text(rule, "Executability"), checkOps);
    }


    /**
     * The derived {@code Sensitivity} of an assembled rule node — the census's own run of the same
     * {@link RuleClassifier#deriveSensitivity} the assembler validates with. Until
     * {@code PLAN-retire-corpus-transforms.md} phase 1 the assembler wrote this value into the node
     * and the census read it back; the value is identical, only the place it is computed moved.
     */
    private static String derivedSensitivity(JsonNode rule)
    {
        Rule bound;
        try
        {
            bound = LENIENT.treeToValue(rule, Rule.class);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException e)
        {
            throw new UncheckedIOException(e);
        }
        // 7b: an external binder materialises the Bindings itself, or the classifier derives
        // over a rule with no operations (a `$`-consuming Check then reads RECORD).
        net.cumba.corej.core.RulePackageLoader.materialiseBindings(bound);
        RuleClassifier.Derived<Sensitivity> derived = RuleClassifier.deriveSensitivity(bound);
        Sensitivity value = derived.value();
        return value != null ? value.getJsonValue() : "(absent)";
    }


    /**
     * Collects every leaf {@code operator} under a {@code Check} tree.
     *
     * <p>
     * Deliberately restricted to the {@code Check} subtree: a {@code Bindings} entry is an
     * expression whose leading function name is a registry function, not a Check operator, and
     * folding the two together is exactly the kind of silent mis-attribution this census exists to
     * stop. (Until runbook W8 the bindings' operation vocabulary was a census section of its own,
     * C.6; there is no operation vocabulary any more.)
     * </p>
     */
    private static void collectCheckOperators(JsonNode node, Set<String> out)
    {
        if (node.isArray())
        {
            node.forEach(child -> collectCheckOperators(child, out));
            return;
        }
        if (!node.isObject())
        {
            return;
        }
        if (node.has("name") && node.has("operator"))
        {
            out.add(node.path("operator").asText(""));
        }
        node.properties().forEach(e -> collectCheckOperators(e.getValue(), out));
    }


    private static String text(JsonNode node, String field)
    {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : "(absent)";
    }

    // ---------------------------------------------------------------- rendering


    /** Renders the generated region (markers included) for the committed document. */
    public static String render() throws IOException
    {
        return render(snapshot(), withoutRegion(Files.readString(documentFile())));
    }


    /**
     * Renders the generated region.
     *
     * @param s
     *            the corpus + code snapshot.
     * @param handWritten
     *            the document with its generated region removed — the text the "in prose" column is
     *            evaluated against. Passing the whole document here would make that column
     *            trivially self-satisfying.
     */
    public static String render(Snapshot s, String handWritten)
    {
        StringBuilder b = new StringBuilder(BEGIN_MARKER).append('\n');
        b.append("<!-- Generated by RuleDocCensus. DO NOT HAND-EDIT: regenerate with\n")
                .append("     scripts/gen-rule-doc-census.sh\n")
                .append("     RuleDocCensusDriftTest fails the build when this region drifts. -->\n")
                .append('\n');

        renderViews(b, s);
        renderVariableUniverse(b, s);
        renderSensitivity(b, s);
        renderExecutability(b, s);
        renderReferencePackage(b, s);
        renderBuiltins(b, handWritten);

        return b.append(END_MARKER).toString();
    }


    private static void renderViews(StringBuilder b, Snapshot s)
    {
        b.append("### C.1 Corpus views\n\n");
        b.append("Three views, three different leaf counts. **Always name the view** when quoting a"
                + " number.\n\n");
        b.append("| View | Form | Packages | Entries | Distinct `Core.Id` |\n");
        b.append("|------|------|---------:|--------:|-------------------:|\n");
        b.append("| `rules-src/checks/` | authored YAML, one file per rule | — | ")
                .append(s.srcFiles()).append(" files | ").append(s.srcFiles()).append(" |\n");
        b.append("| assembled (`RuleStorageAssembler`) | canonical expression-form packages,"
                + " in memory | ").append(s.assembledPackages()).append(" | ")
                .append(s.assembledEntries()).append(" | ").append(s.assembledDistinct().size())
                .append(" |\n");
        b.append("| `rules/` | native / expression packages | ").append(s.nativePackages())
                .append(" | ").append(s.nativeEntries()).append(" | ").append(s.nativeDistinct())
                .append(" |\n\n");
        b.append("Fan-out factor (assembled entries ÷ distinct ids): **")
                .append(ratio(s.assembledEntries(), s.assembledDistinct().size()))
                .append("×**.\n\n");
        b.append("Authored files by family: ");
        b.append(String.join(", ", s.srcFilesByFamily().entrySet().stream()
                .map(e -> "`" + e.getKey() + "` " + e.getValue()).toList()));
        b.append(".\n\n");
        b.append("Authored but **not shipped** (").append(s.srcParkedIds().size())
                .append(") — `Executability: \"Not Executable\"` parks a rule upstream of the"
                        + " fan-out, so it is absent from *both* shipped corpora: ");
        b.append(String.join(", ", s.srcParkedIds().stream().map(i -> "`" + i + "`").toList()));
        b.append(".\n\n");
        b.append("⚠ `rules-src` carries `Sensitivity` only on the documented override set — it is"
                + " derived at load time — and so does `rules/`. Every Variable_Universe /"
                + " Sensitivity count below is therefore taken from the"
                + " **assembled** packages, projected to distinct `Core.Id`, with the census"
                + " running the same `Sensitivity` derivation the assembler validates.\n\n");
    }


    private static void renderVariableUniverse(StringBuilder b, Snapshot s)
    {
        b.append("### C.2 `Variable_Universe` — enum roster and corpus distribution\n\n");
        b.append("View: **assembled packages, distinct `Core.Id`**. An absent field"
                + " counts as `Data` — absent means `Data`, never a derivation. \"Check operators\""
                + " is the number of distinct Check operators used by the rules of that"
                + " universe, counted on the instruments' lowered legacy view"
                + " (`DualFormCheckNode`).\n\n");
        b.append("| Constant | JSON value | Rules | Check operators |\n");
        b.append("|----------|------------|------:|----------------:|\n");
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Set<String>> ops = new TreeMap<>();
        for (CensusRule r : s.assembledDistinct().values())
        {
            counts.merge(r.variableUniverse(), 1, Integer::sum);
            ops.computeIfAbsent(r.variableUniverse(), _ -> new TreeSet<>())
                    .addAll(r.checkOperators());
        }
        int total = 0;
        Set<String> allOps = new TreeSet<>();
        for (VariableUniverse u : VariableUniverse.values())
        {
            int n = counts.getOrDefault(u.getJsonValue(), 0);
            Set<String> o = ops.getOrDefault(u.getJsonValue(), Set.of());
            allOps.addAll(o);
            total += n;
            b.append("| `").append(u.name()).append("` | `").append(u.getJsonValue()).append("` | ")
                    .append(n).append(" | ").append(o.size()).append(" |\n");
        }
        b.append("| **total** | | **").append(total).append("** | **").append(allOps.size())
                .append("** |\n\n");
        b.append("A rule's `Variable_Universe` is invariant across the packages it is fanned out"
                + " into, so the distinct-id projection is well defined (asserted by"
                + " `RuleDocCensusDriftTest.classifyingFieldsAreInvariantPerRuleId`). The **total**"
                + " row's operator count is the size of the union, not the sum of the column."
                + " (Until PLAN-leaf-scope-domain-inference phase 7 this section tabulated the"
                + " derived `Rule_Type`; the engine now infers the evaluation domain from the"
                + " Check and the universe is the one authored bit that taxonomy carried.)\n\n");
    }


    private static void renderSensitivity(StringBuilder b, Snapshot s)
    {
        b.append("### C.3 `Sensitivity` — enum roster and corpus distribution\n\n");
        b.append("View: **assembled packages, distinct `Core.Id`**.\n\n");
        b.append("| Constant | JSON value | Rules |\n");
        b.append("|----------|------------|------:|\n");
        Map<String, Integer> counts = new TreeMap<>();
        s.assembledDistinct().values().forEach(r -> counts.merge(r.sensitivity(), 1, Integer::sum));
        int total = 0;
        for (Sensitivity v : Sensitivity.values())
        {
            int n = counts.getOrDefault(v.getJsonValue(), 0);
            total += n;
            b.append("| `").append(v.name()).append("` | `").append(v.getJsonValue()).append("` | ")
                    .append(n).append(" |\n");
        }
        b.append("| **total** | | **").append(total).append("** |\n\n");
    }


    private static void renderExecutability(StringBuilder b, Snapshot s)
    {
        b.append("### C.4 `Executability` — enum roster and corpus distribution\n\n");
        b.append("Two views side by side, because they legitimately disagree: `Not Executable`"
                + " parks a rule, so an authored value that exists in `rules-src` has **no**"
                + " shipped counterpart.\n\n");
        b.append("| Constant | JSON value | `rules-src` (authored) | assembled"
                + " (shipped, distinct ids) |\n");
        b.append("|----------|------------|-----------------------:|"
                + "-------------------------------------:|\n");
        Map<String, Integer> shipped = new TreeMap<>();
        s.assembledDistinct().values()
                .forEach(r -> shipped.merge(r.executability(), 1, Integer::sum));
        int srcTotal = 0;
        int shippedTotal = 0;
        for (Executability v : Executability.values())
        {
            int a = s.srcExecutability().getOrDefault(v.getJsonValue(), 0);
            int c = shipped.getOrDefault(v.getJsonValue(), 0);
            srcTotal += a;
            shippedTotal += c;
            b.append("| `").append(v.name()).append("` | `").append(v.getJsonValue()).append("` | ")
                    .append(a).append(" | ").append(c).append(" |\n");
        }
        b.append("| **total** | | **").append(srcTotal).append("** | **").append(shippedTotal)
                .append("** |\n\n");
    }


    private static void renderReferencePackage(StringBuilder b, Snapshot s)
    {
        b.append("### C.5 Reference package — assembled `").append(REFERENCE_PACKAGE)
                .append("`\n\n");
        b.append("The specification's prose says *\"in SDTMIG 3.4\"*, but **four** packages target"
                + " SDTMIG 3.4 (CDISC / DRAFT / FDA / PMDA families) and their distributions differ"
                + " widely, so one has to be named: the **CDISC-family** package, the primary"
                + " family. Quote a number from this table only with the package name"
                + " attached.\n\n");
        b.append("⛔⛔ **The prose figures are HISTORICAL and no surviving package reproduces"
                + " them.** Until the 2026-09 CORE-family retirement this section tabulated"
                + " `rules-core-sdtmig-3-4.json`, chosen precisely because its measured"
                + " distributions were the only ones near the prose values (prose"
                + " Record/Dataset/Group = 321/108/1). That package is gone, so the numbers below"
                + " moved **by design** — they are the CDISC package's, not the prose's. Do not"
                + " reconcile the two.\n\n");
        b.append("Rules in package: **").append(s.referencePackage().size()).append("**.\n\n");
        b.append("| Field | Value | Rules |\n");
        b.append("|-------|-------|------:|\n");
        Map<String, Integer> sens = new TreeMap<>();
        Map<String, Integer> exec = new TreeMap<>();
        for (CensusRule r : s.referencePackage().values())
        {
            sens.merge(r.sensitivity(), 1, Integer::sum);
            exec.merge(r.executability(), 1, Integer::sum);
        }
        for (Sensitivity v : Sensitivity.values())
        {
            b.append("| `Sensitivity` | `").append(v.getJsonValue()).append("` | ")
                    .append(sens.getOrDefault(v.getJsonValue(), 0)).append(" |\n");
        }
        for (Executability v : Executability.values())
        {
            b.append("| `Executability` | `").append(v.getJsonValue()).append("` | ")
                    .append(exec.getOrDefault(v.getJsonValue(), 0)).append(" |\n");
        }
        b.append('\n');
    }


    private static void renderBuiltins(StringBuilder b, String handWritten)
    {
        b.append("### C.7 `BuiltinRegistry` — the built-in operand surface\n\n");
        b.append("Source: `net.cumba.corej.core.expr.BuiltinRegistry` (code, not corpus). The"
                + " **in prose** column records whether the operand is named in backticks anywhere"
                + " in the *hand-written* half of this document — i.e. this document with the"
                + " generated region removed, so the roster cannot satisfy itself. A `no` is a"
                + " documentation gap, not an engine defect.\n\n");
        List<String> names = new ArrayList<>(BuiltinRegistry.names());
        names.sort(null);
        int documented = 0;
        b.append("| Operand | Level | In prose |\n");
        b.append("|---------|-------|----------|\n");
        for (String n : names)
        {
            boolean inProse = handWritten.contains("`" + n + "`");
            if (inProse)
            {
                documented++;
            }
            b.append("| `").append(n).append("` | ").append(level(n)).append(" | ")
                    .append(inProse ? "yes" : "**no**").append(" |\n");
        }
        b.append("| **total** | | **").append(documented).append(" of ").append(names.size())
                .append("** |\n\n");
    }


    private static String level(String name)
    {
        if (name.startsWith("library_"))
        {
            return "library";
        }
        if (name.startsWith("define_vlm_"))
        {
            return "define (value-level)";
        }
        if (name.startsWith("define_"))
        {
            return "define";
        }
        return "data";
    }


    private static String ratio(int numerator, int denominator)
    {
        return denominator == 0 ? "n/a"
                : String.format(Locale.ROOT, "%.2f", (double) numerator / denominator);
    }

    // ---------------------------------------------------------------- entry point


    /**
     * Rewrites the generated region of the document in place.
     *
     * @param args
     *            optional single argument: the document path. Defaults to {@link #documentFile()}.
     */
    public static void main(String[] args)
    {
        try
        {
            Path doc = args.length > 0 ? Path.of(args[0]) : documentFile();
            String text = Files.readString(doc);
            String updated = replaceRegion(text, render(snapshot(), withoutRegion(text)));
            Files.writeString(doc, updated, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

}
