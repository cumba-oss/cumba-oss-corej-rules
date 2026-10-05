package net.cumba.corej.core.ruletestsuites;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.rulespec.CanonicalJson;
import net.cumba.corej.core.rulespec.CanonicalViolation;
import net.cumba.corej.core.rulespec.SpecLoader;
import net.cumba.corej.core.rulespec.SpecRunner;
import net.cumba.corej.core.rulespec.SpecSchema;
import net.cumba.corej.core.rulespec.ViolationNormaliser;
import net.cumba.corej.ruletest.cdt.ruletest.ExpectedViolation;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.corej.ruletest.cdt.ruletest.ViolationLocationCheck;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The re-pin tool of {@code PLAN-finding-values-record-level-only} P1 (review H2): every
 * {@code .cdt} scenario and every rulespec spec that pins a <b>value</b> for a raw column the
 * engine now lists <em>by name without a value</em> ({@code Violation.getNamedColumns()}) is
 * rewritten to pin the name-only state — {@code COL=value} → {@code nameOnly=COL} in a
 * {@code #expectViolationAt} line, an {@code output_variables} entry → a {@code name_only} member
 * in a spec's expected violation — and <b>nothing else</b>: a pin on a column the finding still
 * values stays, a {@code row=} / {@code severity=} / {@code $} pin stays, the fixture is untouched.
 *
 * <p>
 * <b>Verified, never trusted.</b> A rewrite is kept only when the rewritten expectation passes the
 * real checker against the engine's real output ({@link ViolationLocationCheck#verify} for a
 * scenario, the canonical set comparison for a spec); otherwise the file is restored byte for byte
 * and named as MANUAL in the summary. A scenario or spec that is green before the rewrite is left
 * alone — so the tool is idempotent and moves exactly the pins the plan moves (H2's ~185 lines).
 * </p>
 *
 * <p>
 * Opt-in, one-shot: {@code -Dcorej.nameonly.repin=true}; it ends with an abort naming the counts (a
 * rewrite is never a green). Shares the harnesses' evaluation path
 * ({@link AbstractRuleTestSuitesFactory#evaluateScenario}, {@link SpecRunner#executeRawWithTable}),
 * so it needs the metadata store ({@code CDISC_PICKLE_CACHE_DIR}) like they do.
 * </p>
 */
@EnabledIfSystemProperty(named = NameOnlyRepinTool.PROPERTY, matches = "true")
class NameOnlyRepinTool
{

    static final String PROPERTY = "corej.nameonly.repin";

    private static final Path PROJECT_DIR = Path.of(System.getProperty("projectBasedir"));

    private static final Path RESOURCE_BASE = PROJECT_DIR
            .resolve("src/test/resources/net/cumba/corej/core/ruletestsuites");

    private static final List<AbstractRuleTestSuitesFactory> FACTORIES = List.of(
            new RuleTestSuitesCdiscFactoryTest(), new RuleTestSuitesDraftFactoryTest(),
            new RuleTestSuitesFdaFactoryTest(), new RuleTestSuitesPmdaFactoryTest());

    private final List<String> rewritten = new ArrayList<>();

    private final List<String> manual = new ArrayList<>();

    private int green;

    @Test
    void repinValuePinsOnNameOnlyColumns() throws IOException
    {
        for (AbstractRuleTestSuitesFactory factory : FACTORIES)
        {
            String family = factory.family();
            Map<String, Map<String, Rule>> packages = AbstractRuleTestSuitesFactory
                    .loadFamily(family);
            Path familyDir = RESOURCE_BASE.resolve(family);
            try (Stream<Path> dirs = Files.list(familyDir))
            {
                for (Path ruleDir : dirs.filter(Files::isDirectory).sorted().toList())
                {
                    try (Stream<Path> files = Files.list(ruleDir))
                    {
                        for (Path file : files.sorted()
                                .filter(p -> p.getFileName().toString().endsWith(".cdt"))
                                .filter(AbstractRuleTestSuitesFactory::hasShebang).toList())
                        {
                            scenario(factory, file, packages);
                        }
                    }
                }
            }
        }
        for (Path spec : SpecLoader.findSpecs(CorpusPaths.moduleDir().resolve("rulespec/specs")))
        {
            spec(spec);
        }
        String summary = "[nameonly-repin] green=" + green + " rewritten=" + rewritten.size()
                + " manual=" + manual.size();
        System.out.println(summary);
        for (String r : rewritten)
        {
            System.out.println("[nameonly-repin] REWROTE " + r);
        }
        for (String m : manual)
        {
            System.out.println("[nameonly-repin] MANUAL " + m);
        }
        assertTrue(green + rewritten.size() + manual.size() > 0, "nothing was visited");
        Assumptions.abort(summary + " — review the diff and commit it; a rewrite is never a green");
    }

    // ------------------------------------------------------------------ .cdt


    private void scenario(AbstractRuleTestSuitesFactory factory, Path file,
            Map<String, Map<String, Rule>> packages)
        throws IOException
    {
        String rel = RESOURCE_BASE.relativize(file).toString().replace('\\', '/');
        RuleTestScenario s = RuleTestCdt.load(file);
        if (s.getExpectedViolations() == null || s.getExpectedViolations().isEmpty())
        {
            green++;
            return;
        }
        Rule rule = AbstractRuleTestSuitesFactory.resolveIn(packages, s);
        OverlayDataTable primary = s.primaryTable();
        if (rule == null || primary == null)
        {
            manual.add(rel + ": no rule / primary");
            return;
        }
        AbstractRuleTestSuitesFactory.EvalOutcome o = factory.evaluateScenario(file, s, rule,
                primary);
        if (o == null)
        {
            green++;
            return;
        }
        if (ViolationLocationCheck.verify(s, o.violations(), o.totalCount(), o.truncated(), primary)
                .pass())
        {
            green++;
            return;
        }
        Set<String> named = new TreeSet<>();
        for (Violation v : o.violations())
        {
            named.addAll(v.getNamedColumns());
        }
        if (named.isEmpty())
        {
            manual.add(rel + ": red, and no violation lists a column by name");
            return;
        }
        List<ExpectedViolation> moved = new ArrayList<>();
        boolean changed = false;
        for (ExpectedViolation e : s.getExpectedViolations())
        {
            Map<String, String> kept = new LinkedHashMap<>();
            List<String> nameOnly = new ArrayList<>(e.getNameOnly());
            for (Map.Entry<String, String> c : e.getConstraints().entrySet())
            {
                if (named.contains(c.getKey()) && !nameOnly.contains(c.getKey()))
                {
                    nameOnly.add(c.getKey());
                    changed = true;
                }
                else
                {
                    kept.put(c.getKey(), c.getValue());
                }
            }
            moved.add(ExpectedViolation.builder().row(e.getRow()).severity(e.getSeverity())
                    .constraints(kept).nameOnly(nameOnly).build());
        }
        if (!changed)
        {
            manual.add(rel + ": red, but no value pin names a name-only column " + named);
            return;
        }
        RuleTestScenario candidate = s.toBuilder().clearExpectedViolations()
                .expectedViolations(moved).build();
        if (!ViolationLocationCheck
                .verify(candidate, o.violations(), o.totalCount(), o.truncated(), primary).pass())
        {
            manual.add(rel + ": still red after moving " + named + " to nameOnly=");
            return;
        }
        String original = Files.readString(file, UTF_8);
        String updated = replaceAtLines(original, directiveLines(candidate));
        Files.writeString(file, updated, UTF_8);
        RuleTestScenario reparsed = RuleTestCdt.load(file);
        if (!ViolationLocationCheck
                .verify(reparsed, o.violations(), o.totalCount(), o.truncated(), primary).pass())
        {
            Files.writeString(file, original, UTF_8);
            manual.add(rel + ": the rewritten file does not re-parse to a green check");
            return;
        }
        rewritten.add(rel + " " + named);
    }


    /** The {@code #expectViolationAt} lines of {@code scenario}, as the writer spells them. */
    private static List<String> directiveLines(RuleTestScenario scenario)
    {
        List<String> out = new ArrayList<>();
        for (String line : RuleTestCdt.toString(scenario).split("\n", -1))
        {
            if (line.startsWith("#expectViolationAt"))
            {
                out.add(line);
            }
        }
        return out;
    }


    /**
     * {@code original} with every {@code #expectViolationAt} line removed and {@code lines}
     * inserted where the first of them stood. Everything else — the count directive, notes, the
     * fixture — is byte-identical.
     */
    static String replaceAtLines(String original, List<String> lines)
    {
        List<String> out = new ArrayList<>();
        int insertAt = -1;
        for (String line : original.split("\n", -1))
        {
            if (line.startsWith("#expectViolationAt"))
            {
                if (insertAt < 0)
                {
                    insertAt = out.size();
                }
                continue;
            }
            out.add(line);
        }
        if (insertAt < 0)
        {
            throw new IllegalStateException("no #expectViolationAt line to replace");
        }
        out.addAll(insertAt, lines);
        return String.join("\n", out);
    }

    // ------------------------------------------------------------------ specs


    private void spec(Path file) throws IOException
    {
        String rel = CorpusPaths.moduleDir().relativize(file).toString().replace('\\', '/');
        SpecSchema schema = SpecLoader.load(file);
        List<CanonicalViolation> expected = schema.expectedViolations() == null ? List.of()
                : schema.expectedViolations();
        if (expected.isEmpty())
        {
            green++;
            return;
        }
        List<CanonicalViolation> observed = observed(schema);
        if (observed == null || sameSet(expected, observed))
        {
            green++;
            return;
        }
        // Per expected violation: the output_variables keys the observed counterpart lists by
        // name. The counterpart is the observed violation with the same rule / dataset / row key
        // whose values equal the expectation's minus exactly those keys.
        List<Set<String>> movedPerExpectation = new ArrayList<>();
        boolean changed = false;
        for (CanonicalViolation e : expected)
        {
            Set<String> moved = null;
            for (CanonicalViolation o : observed)
            {
                // Compared as SpecRunner compares: through the canonical JSON, where a YAML `1`
                // and an engine `1.0` row key agree.
                if (!Objects.equals(e.ruleId(), o.ruleId())
                        || !Objects.equals(e.dataset(), o.dataset())
                        || !json(e.rowKey()).equals(json(o.rowKey())) || o.nameOnly() == null)
                {
                    continue;
                }
                Map<String, Object> remaining = new LinkedHashMap<>(e.outputVariables());
                Set<String> candidates = new TreeSet<>();
                for (String name : o.nameOnly())
                {
                    if (remaining.containsKey(name))
                    {
                        remaining.remove(name);
                        candidates.add(name);
                    }
                }
                Set<String> wantNamed = new TreeSet<>(candidates);
                if (e.nameOnly() != null)
                {
                    wantNamed.addAll(e.nameOnly());
                }
                if (json(new java.util.TreeMap<>(remaining))
                        .equals(json(new java.util.TreeMap<>(o.outputVariables())))
                        && wantNamed.equals(new TreeSet<>(o.nameOnly())))
                {
                    moved = candidates;
                    break;
                }
            }
            if (moved == null)
            {
                manual.add(rel + ": no name-only counterpart for " + e);
                return;
            }
            changed |= !moved.isEmpty();
            movedPerExpectation.add(moved);
        }
        if (!changed)
        {
            manual.add(rel + ": red for another reason than name-only columns");
            return;
        }
        String original = Files.readString(file, UTF_8);
        String updated;
        try
        {
            updated = rewriteSpec(original, movedPerExpectation);
        }
        catch (IllegalStateException ex)
        {
            manual.add(rel + ": " + ex.getMessage());
            return;
        }
        Files.writeString(file, updated, UTF_8);
        SpecSchema reloaded = SpecLoader.load(file);
        List<CanonicalViolation> reExpected = reloaded.expectedViolations() == null ? List.of()
                : reloaded.expectedViolations();
        if (!sameSet(reExpected, observed))
        {
            Files.writeString(file, original, UTF_8);
            manual.add(rel + ": the rewritten spec does not equal the observed set");
            return;
        }
        Set<String> all = new TreeSet<>();
        movedPerExpectation.forEach(all::addAll);
        rewritten.add(rel + " " + all);
    }


    private static @Nullable List<CanonicalViolation> observed(SpecSchema schema) throws IOException
    {
        SpecRunner.RawExecution raw = SpecRunner.executeRawWithTable(schema);
        RuleExecutionResult r = raw.result();
        if (r == null)
        {
            return null;
        }
        String primaryDomain = raw.primaryTable().getMetaData().getName();
        SpecSchema.SpecDataset ds = schema.datasets() == null ? null
                : schema.datasets().get(primaryDomain);
        List<String> rowKey = ds == null || ds.rowKey() == null ? List.of() : ds.rowKey();
        return ViolationNormaliser.normalise(r, raw.primaryTable(), primaryDomain, rowKey);
    }


    /** The comparison SpecRunner makes: the canonical JSON set of each side. */
    private static boolean sameSet(List<CanonicalViolation> a, List<CanonicalViolation> b)
    {
        Set<String> ja = new LinkedHashSet<>();
        a.forEach(v -> ja.add(json(v)));
        Set<String> jb = new LinkedHashSet<>();
        b.forEach(v -> jb.add(json(v)));
        return ja.equals(jb) && a.size() == b.size();
    }


    private static String json(Object value)
    {
        return CanonicalJson.toJson(value);
    }


    /**
     * The spec text with, for the i-th expected violation, the {@code output_variables} entries
     * named by {@code moved.get(i)} removed and a {@code name_only: [..]} line added (merged into
     * an existing one). Block style ({@code key: value} children) and the one-line flow style
     * ({@code output_variables: {A: x, B: y}}) are handled; anything else throws and the file is
     * left alone.
     */
    static String rewriteSpec(String original, List<Set<String>> moved)
    {
        List<String> lines = new ArrayList<>(List.of(original.split("\n", -1)));
        int start = indexOfLine(lines, "expected_violations:");
        if (start < 0)
        {
            throw new IllegalStateException("no expected_violations block");
        }
        // The violation blocks start at every "- rule_id:" line after the block header; the
        // block ends at the next top-level key or the end of the file.
        List<Integer> blockStarts = new ArrayList<>();
        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++)
        {
            String l = lines.get(i);
            if (!l.isBlank() && !l.startsWith(" ") && !l.startsWith("-") && !l.startsWith("#"))
            {
                end = i;
                break;
            }
            if (l.stripLeading().startsWith("- rule_id:"))
            {
                blockStarts.add(i);
            }
        }
        if (blockStarts.size() != moved.size())
        {
            throw new IllegalStateException(
                    blockStarts.size() + " violation blocks for " + moved.size() + " expectations");
        }
        // Rewrite back to front so earlier indices stay valid.
        for (int b = blockStarts.size() - 1; b >= 0; b--)
        {
            Set<String> names = moved.get(b);
            if (names.isEmpty())
            {
                continue;
            }
            int blockStart = blockStarts.get(b);
            int blockEnd = b + 1 < blockStarts.size() ? blockStarts.get(b + 1) : end;
            rewriteBlock(lines, blockStart, blockEnd, names);
        }
        return String.join("\n", lines);
    }


    private static void rewriteBlock(List<String> lines, int blockStart, int blockEnd,
            Set<String> names)
    {
        int ovLine = -1;
        for (int i = blockStart; i < blockEnd; i++)
        {
            if (lines.get(i).stripLeading().startsWith("output_variables:"))
            {
                ovLine = i;
                break;
            }
        }
        if (ovLine < 0)
        {
            throw new IllegalStateException("expected violation without output_variables");
        }
        String ov = lines.get(ovLine);
        String indent = ov.substring(0, ov.indexOf("output_variables:"));
        String rest = ov.substring(ov.indexOf("output_variables:") + "output_variables:".length())
                .strip();
        Set<String> remainingNames = new TreeSet<>(names);
        int insertAt;
        if (rest.isEmpty())
        {
            // Block style: children are the following lines indented deeper than `indent`.
            int i = ovLine + 1;
            List<Integer> toRemove = new ArrayList<>();
            while (i < blockEnd && (lines.get(i).isBlank() || deeperThan(lines.get(i), indent)))
            {
                String child = lines.get(i).strip();
                if (!child.startsWith("#") && !child.isEmpty())
                {
                    String key = unquote(child.substring(0, child.indexOf(':')).strip());
                    if (remainingNames.remove(key))
                    {
                        toRemove.add(i);
                    }
                }
                i++;
            }
            int childrenEnd = i;
            // trailing blank lines belong to the next block, not to the mapping
            while (childrenEnd > ovLine + 1 && lines.get(childrenEnd - 1).isBlank())
            {
                childrenEnd--;
            }
            for (int r = toRemove.size() - 1; r >= 0; r--)
            {
                lines.remove((int) toRemove.get(r));
            }
            childrenEnd -= toRemove.size();
            boolean anyChildLeft = false;
            for (int j = ovLine + 1; j < childrenEnd; j++)
            {
                String c = lines.get(j).strip();
                if (!c.isEmpty() && !c.startsWith("#"))
                {
                    anyChildLeft = true;
                }
            }
            if (!anyChildLeft)
            {
                lines.set(ovLine, indent + "output_variables: {}");
            }
            insertAt = childrenEnd;
        }
        else if (rest.startsWith("{") && rest.endsWith("}"))
        {
            String inner = rest.substring(1, rest.length() - 1).strip();
            List<String> kept = new ArrayList<>();
            if (!inner.isEmpty())
            {
                for (String item : inner.split(",\\s*", -1))
                {
                    String key = unquote(item.substring(0, item.indexOf(':')).strip());
                    if (!remainingNames.remove(key))
                    {
                        kept.add(item.strip());
                    }
                }
            }
            lines.set(ovLine, indent + "output_variables: {" + String.join(", ", kept) + "}");
            insertAt = ovLine + 1;
        }
        else
        {
            throw new IllegalStateException("output_variables spelled as '" + rest + "'");
        }
        if (!remainingNames.isEmpty())
        {
            throw new IllegalStateException("output_variables has no entry for " + remainingNames);
        }
        // An existing name_only line in the block is merged; else one is added after the map.
        for (int i = blockStart; i < insertAt; i++)
        {
            String l = lines.get(i).strip();
            if (l.startsWith("name_only:"))
            {
                String existing = l.substring("name_only:".length()).strip();
                Set<String> merged = new TreeSet<>(names);
                if (existing.startsWith("[") && existing.endsWith("]") && existing.length() > 2)
                {
                    for (String n : existing.substring(1, existing.length() - 1).split(",\\s*", -1))
                    {
                        merged.add(unquote(n.strip()));
                    }
                }
                lines.set(i, indent + "name_only: " + renderList(merged));
                return;
            }
        }
        lines.add(insertAt, indent + "name_only: " + renderList(new TreeSet<>(names)));
    }


    private static String renderList(Set<String> names)
    {
        List<String> out = new ArrayList<>();
        for (String n : names)
        {
            out.add(n.startsWith("$") || n.contains(".") || n.contains("-") ? "'" + n + "'" : n);
        }
        return "[" + String.join(", ", out) + "]";
    }


    private static boolean deeperThan(String line, String indent)
    {
        return line.startsWith(indent + " ") || line.startsWith(indent + "\t");
    }


    private static String unquote(String key)
    {
        boolean single = key.startsWith("'") && key.endsWith("'");
        boolean doubled = key.startsWith("\"") && key.endsWith("\"");
        if (key.length() >= 2 && (single || doubled))
        {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }


    private static int indexOfLine(List<String> lines, String prefix)
    {
        for (int i = 0; i < lines.size(); i++)
        {
            if (lines.get(i).startsWith(prefix))
            {
                return i;
            }
        }
        return -1;
    }
}
