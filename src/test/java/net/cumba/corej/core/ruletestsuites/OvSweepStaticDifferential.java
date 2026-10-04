package net.cumba.corej.core.ruletestsuites;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.OvSweepCensus;
import net.cumba.corej.core.OvSweepClassifier;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.RuleSpecialiser;
import net.cumba.corej.core.exec.ScopeVariableSource;
import net.cumba.corej.core.gen.TokenExpander;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.manager.IDataTableLibraryRef;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.manager.IDataTableRef;
import net.cumba.datatable.manager.ILibraryMemberRef;
import net.cumba.datatable.manager.local.LocalDataTableManager;
import org.jspecify.annotations.Nullable;

/**
 * Phase 0 of {@code PLAN-redundant-output-variables-sweep}: the <b>static differential</b> in three
 * arms, run against the manifest {@link OvSweepCensus} wrote — every arm asserts that, for every
 * rule the manifest strips, the effective {@code Output_Variables} after the strip has the same
 * <em>members</em> as before, that the E-3 exclusion validation stays clean, and that the
 * <em>order</em> is exactly what the manifest predicts.
 *
 * <ol>
 * <li><b>(a) package level</b> — every generated package is rewritten in memory with the manifest's
 * strip applied (the key deleted when nothing authored remains, Q5) and re-loaded through
 * {@link RulePackageLoader#loadFromString}: the loader's own effective list must equal the
 * manifest's prediction exactly, and its member set the pre-strip list's. This is the end-to-end
 * proof that the prediction ({@code derive()} over the stripped authored list) is what the loader
 * installs.</li>
 * <li><b>(b) {@code --} specialisation</b> — every stripped rule {@link RuleSpecialiser} rewrites
 * for a domain is specialised before and after the strip, for every domain its scope pins (else
 * every domain code the corpus pins anywhere), and re-derived as {@code DatasetRuleResolver} does:
 * same members, E-3 clean, and a specialised-level order move only where the package level predicts
 * one.</li>
 * <li><b>(c) expansion</b> — every stripped template ({@link WildcardExpander} wildcards or an
 * {@code Expansion:} block) is expanded before and after the strip against the metadata of each of
 * its own {@code .cdt} scenarios' primary datasets (all four families) and, for the SDTM templates
 * that run over the study fixture, against every {@code testdata/study} dataset: pairwise the same
 * members, E-3 clean, order moves only where predicted.</li>
 * </ol>
 *
 * <p>
 * Before the arms, the manifest is checked against a <b>fresh classification</b> of the corpus
 * ({@link OvSweepClassifier}): a strip entry the classifier would not strip (a non-derived entry,
 * or a Q2 (a) residual entry) is reported as an unlisted strip. That is what makes the
 * <b>sensitivity plants</b> visible: {@code --plant i} strips a non-derived entry (members lost),
 * {@code --plant ii} strips a per-variable residual {@code variable_value} (unlisted strip),
 * {@code --plant iii} applies one rule's strip with the remaining entries reversed (an unlisted
 * reorder: the loader's order differs from the prediction). Each plant must make the run exit 1
 * naming the planted rule.
 * </p>
 *
 * <p>
 * Usage (test scope): {@code OvSweepStaticDifferential <manifest.tsv> [--plant i|ii|iii]}. Reads
 * the corpus at {@link CorpusPaths#rules()} and the scenario trees of this module. Exit 0 iff no
 * arm reports a difference. Read-only.
 * </p>
 */
public final class OvSweepStaticDifferential
{

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Path PROJECT_DIR = Path.of(System.getProperty("projectBasedir", "."));

    private static final Path SCENARIOS = PROJECT_DIR
            .resolve("src/test/resources/net/cumba/corej/core/ruletestsuites");

    private static final List<String> FAMILIES = List.of("cdisc", "draft", "fda", "pmda");

    /** The SDTM templates the plan names for the study arm of (c). */
    private static final Set<String> STUDY_TEMPLATES = Set.of("CDISC-CG0031", "CDISC-CG0033",
            "FDA-SD1023", "PMDA-SD1023");

    private OvSweepStaticDifferential()
    {
    }

    /** One manifest row (the columns this differential reads). */
    record Row(String coreId, boolean perVariable, List<String> authored, List<String> strip,
            List<String> residual, List<String> before, List<String> after, boolean orderMoves,
            Set<String> specialisedMoveDomains)
    {
    }


    /** Everything one run accumulates. */
    static final class Ledger
    {

        final List<String> problems = new ArrayList<>();

        int packageRules;

        int specialisedCopies;

        int specialisedOrderMoves;

        int expandedCopies;

        int expansionScenarios;

        int expansionNoMatch;

        int templates;

        int studyTables;

        final Set<String> templatesCompared = new TreeSet<>();

        void problem(String s)
        {
            problems.add(s);
        }
    }

    public static void main(String[] args) throws IOException
    {
        if (args.length < 1)
        {
            System.err
                    .println("usage: OvSweepStaticDifferential <manifest.tsv> [--plant i|ii|iii]");
            System.exit(2);
        }
        String plant = args.length >= 3 && "--plant".equals(args[1]) ? args[2] : null;
        Map<String, Row> manifest = readManifest(Path.of(args[0]));
        Ledger ledger = new Ledger();
        Map<String, Rule> loadedBefore = new TreeMap<>();
        Map<String, Rule> loadedAfter = new TreeMap<>();

        List<Path> packages = OvSweepCensus.packages(CorpusPaths.rules());
        String planted = plant(manifest, plant);

        consistency(manifest, packages, ledger);
        packageArm(manifest, packages, planted, "iii".equals(plant), ledger, loadedBefore,
                loadedAfter);
        specialisationArm(manifest, loadedBefore, loadedAfter, ledger);
        expansionArm(manifest, loadedBefore, loadedAfter, ledger);

        System.out.println(String.format(Locale.ROOT,
                "[ovsweep-static] plant=%s manifest rules=%d strip rules=%d | (a) package rules"
                        + " compared=%d | (b) specialised copies=%d order moves=%d | (c)"
                        + " templates=%d (compared %d) scenarios=%d expanded copies=%d no-match=%d"
                        + " study tables=%d | problems=%d",
                plant, manifest.size(),
                manifest.values().stream().filter(r -> !r.strip.isEmpty()).count(),
                ledger.packageRules, ledger.specialisedCopies, ledger.specialisedOrderMoves,
                ledger.templates, ledger.templatesCompared.size(), ledger.expansionScenarios,
                ledger.expandedCopies, ledger.expansionNoMatch, ledger.studyTables,
                ledger.problems.size()));
        System.out.println("  templates compared: " + ledger.templatesCompared);
        for (String p : ledger.problems)
        {
            System.out.println("  PROBLEM " + p);
        }
        if (planted != null)
        {
            boolean seen = ledger.problems.stream().anyMatch(p -> p.contains(planted));
            System.out.println("  plant " + plant + " on " + planted + ": "
                    + (seen ? "REPORTED" : "NOT REPORTED"));
            System.exit(seen ? 1 : 3);
        }
        System.exit(ledger.problems.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------------ manifest


    static Map<String, Row> readManifest(Path manifest) throws IOException
    {
        Map<String, Row> rows = new TreeMap<>();
        List<String> header = null;
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8))
        {
            if (line.isBlank() || line.startsWith("#"))
            {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (header == null)
            {
                header = List.of(f);
                if (!String.join("\t", header).equals(OvSweepCensus.MANIFEST_HEADER))
                {
                    throw new IOException("unexpected manifest header: " + line);
                }
                continue;
            }
            rows.put(f[0],
                    new Row(f[0], Boolean.parseBoolean(f[3]), split(f[5]), split(f[7]), split(f[8]),
                            split(f[9]), split(f[10]), Boolean.parseBoolean(f[11]),
                            f[15].isEmpty() ? Set.of() : Set.of(f[15].split(",", -1))));
        }
        return rows;
    }


    private static List<String> split(String joined)
    {
        return joined.isEmpty() ? List.of() : List.of(joined.split("\\|", -1));
    }

    // ------------------------------------------------------------------ plants


    /**
     * Mutates the in-memory manifest for the requested plant and returns the planted rule id, or
     * {@code null} when no plant was requested. The corpus on disk is never touched.
     */
    private static @Nullable String plant(Map<String, Row> manifest, @Nullable String plant)
    {
        if (plant == null)
        {
            return null;
        }
        switch (plant)
        {
        case "i" ->
        {
            // strip a NON-derived authored entry: a stripped rule that keeps at least one entry
            for (Row r : manifest.values())
            {
                List<String> kept = new ArrayList<>(r.authored);
                kept.removeAll(r.strip);
                kept.removeAll(r.residual);
                if (!r.strip.isEmpty() && !kept.isEmpty())
                {
                    List<String> strip = new ArrayList<>(r.strip);
                    strip.add(kept.get(0));
                    List<String> after = new ArrayList<>(r.after);
                    after.remove(kept.get(0));
                    manifest.put(r.coreId, new Row(r.coreId, r.perVariable, r.authored, strip,
                            r.residual, r.before, after, r.orderMoves, r.specialisedMoveDomains));
                    System.out.println(
                            "[plant i] " + r.coreId + " strips non-derived " + kept.get(0));
                    return r.coreId;
                }
            }
        }
        case "ii" ->
        {
            // strip a per-variable residual variable_value
            for (Row r : manifest.values())
            {
                if (r.perVariable && r.residual.contains("variable_value"))
                {
                    List<String> strip = new ArrayList<>(r.strip);
                    strip.add("variable_value");
                    manifest.put(r.coreId, new Row(r.coreId, r.perVariable, r.authored, strip,
                            r.residual, r.before, r.after, r.orderMoves, r.specialisedMoveDomains));
                    System.out
                            .println("[plant ii] " + r.coreId + " strips residual variable_value");
                    return r.coreId;
                }
            }
        }
        case "iii" ->
        {
            // an unlisted reorder: the first stripped rule that keeps >= 2 authored entries has
            // them reversed when the package arm applies the strip
            for (Row r : manifest.values())
            {
                List<String> kept = new ArrayList<>(r.authored);
                kept.removeAll(r.strip);
                if (!r.strip.isEmpty() && kept.size() >= 2)
                {
                    System.out.println("[plant iii] " + r.coreId + " keeps " + kept
                            + " reversed in the package arm");
                    return r.coreId;
                }
            }
        }
        default -> throw new IllegalArgumentException("unknown plant " + plant);
        }
        throw new IllegalStateException("no rule qualifies for plant " + plant);
    }

    // ------------------------------------------------------------------ consistency


    /** The manifest against a fresh classification of every package rule. */
    private static void consistency(Map<String, Row> manifest, List<Path> packages, Ledger ledger)
        throws IOException
    {
        Set<String> seen = new HashSet<>();
        for (Path pkg : packages)
        {
            for (Map.Entry<String, Rule> e : RulePackageLoader.load(pkg).getRules().entrySet())
            {
                Rule rule = e.getValue();
                if (rule.getLoadError() != null || rule.getOutcome() == null
                        || rule.getOutcome().getOutputVariables() == null
                        || rule.getOutcome().getOutputVariables().isEmpty())
                {
                    continue;
                }
                OvSweepClassifier.Verdict v = OvSweepClassifier.classify(rule);
                Row row = manifest.get(e.getKey());
                if (row == null)
                {
                    ledger.problem("consistency: " + e.getKey() + " carries Output_Variables but"
                            + " has no manifest row");
                    continue;
                }
                seen.add(e.getKey());
                Set<String> extra = new LinkedHashSet<>(row.strip);
                v.strip().forEach(extra::remove);
                if (!extra.isEmpty())
                {
                    ledger.problem("consistency: " + e.getKey()
                            + " manifest strips entries the classifier does not (unlisted strip): "
                            + extra + " (classifier strip " + v.strip() + ", residual "
                            + v.residual() + ")");
                }
                Set<String> missing = new LinkedHashSet<>(v.strip());
                row.strip.forEach(missing::remove);
                if (!missing.isEmpty())
                {
                    ledger.problem("consistency: " + e.getKey()
                            + " classifier strips entries the manifest does not: " + missing);
                }
            }
        }
        for (String id : manifest.keySet())
        {
            if (!seen.contains(id))
            {
                ledger.problem("consistency: manifest row " + id + " is in no package");
            }
        }
    }

    // ------------------------------------------------------------------ arm (a)


    private static void packageArm(Map<String, Row> manifest, List<Path> packages,
            @Nullable String planted, boolean reversePlant, Ledger ledger,
            Map<String, Rule> loadedBefore, Map<String, Rule> loadedAfter)
        throws IOException
    {
        for (Path pkg : packages)
        {
            ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(pkg));
            ObjectNode rules = (ObjectNode) root.get("rules");
            Map<String, Rule> before = RulePackageLoader.load(pkg).getRules();
            List<String> touched = new ArrayList<>();
            for (Map.Entry<String, Row> e : manifest.entrySet())
            {
                Row row = e.getValue();
                JsonNode ruleNode = rules.get(row.coreId);
                if (ruleNode == null || row.strip.isEmpty())
                {
                    continue;
                }
                ObjectNode outcome = (ObjectNode) ruleNode.get("Outcome");
                ArrayNode ov = (ArrayNode) outcome.get("Output_Variables");
                List<String> remaining = new ArrayList<>();
                for (JsonNode entry : ov)
                {
                    String text = entry.asText();
                    if (text.startsWith("!") || !row.strip.contains(text))
                    {
                        remaining.add(text);
                    }
                }
                if (reversePlant && row.coreId.equals(planted))
                {
                    Collections.reverse(remaining);
                }
                if (remaining.isEmpty())
                {
                    outcome.remove("Output_Variables");
                }
                else
                {
                    ArrayNode replaced = outcome.putArray("Output_Variables");
                    remaining.forEach(replaced::add);
                }
                touched.add(row.coreId);
            }
            Map<String, Rule> after = RulePackageLoader
                    .loadFromString(JSON.writeValueAsString(root)).getRules();
            for (String id : touched)
            {
                Rule b = before.get(id);
                Rule a = after.get(id);
                Row row = manifest.get(id);
                ledger.packageRules++;
                loadedBefore.putIfAbsent(id, b);
                loadedAfter.putIfAbsent(id, a);
                if (a.getLoadError() != null)
                {
                    ledger.problem("(a) " + pkg.getFileName() + " " + id + " load error after"
                            + " strip: " + a.getLoadError());
                    continue;
                }
                List<String> effA = a.getEffectiveOutputVariables();
                List<String> effB = b.getEffectiveOutputVariables();
                if (effA == null || effB == null)
                {
                    ledger.problem(
                            "(a) " + id + " has no effective list (" + effB + " -> " + effA + ")");
                    continue;
                }
                if (!new HashSet<>(effA).equals(new HashSet<>(effB)))
                {
                    ledger.problem("(a) " + pkg.getFileName() + " " + id + " MEMBERS differ: "
                            + effB + " -> " + effA);
                }
                else if (!effA.equals(row.after))
                {
                    ledger.problem("(a) " + pkg.getFileName() + " " + id + " ORDER differs from"
                            + " the manifest prediction: loader " + effA + ", manifest "
                            + row.after);
                }
                if (!effB.equals(row.before))
                {
                    ledger.problem("(a) " + pkg.getFileName() + " " + id + " pre-strip list " + effB
                            + " differs from the manifest's before " + row.before);
                }
            }
        }
    }

    // ------------------------------------------------------------------ arm (b)


    private static void specialisationArm(Map<String, Row> manifest, Map<String, Rule> before,
            Map<String, Rule> after, Ledger ledger)
    {
        Set<String> universe = new TreeSet<>();
        for (Rule r : before.values())
        {
            universe.addAll(OvSweepCensus.concreteDomains(r));
        }
        for (Map.Entry<String, Rule> e : before.entrySet())
        {
            String id = e.getKey();
            Rule b = e.getValue();
            Rule a = after.get(id);
            if (a == null || b.getCheck() == null)
            {
                continue;
            }
            Row row = manifest.get(id);
            List<String> domains = OvSweepCensus.pinnedDomains(b);
            if (domains.isEmpty())
            {
                domains = new ArrayList<>(universe);
            }
            for (String domain : domains)
            {
                Rule sb = RuleSpecialiser.specialise(b, null, domain);
                Rule sa = RuleSpecialiser.specialise(a, null, domain);
                if (sb == b && sa == a)
                {
                    continue; // nothing resolved for this domain
                }
                if (sb == b || sa == a)
                {
                    ledger.problem("(b) " + id + " @" + domain + ": only one side specialised");
                    continue;
                }
                RulePackageLoader.deriveOutputVariables(sb);
                RulePackageLoader.deriveOutputVariables(sa);
                ledger.specialisedCopies++;
                if (sa.getLoadError() != null)
                {
                    ledger.problem("(b) " + id + " @" + domain + " load error after strip: "
                            + sa.getLoadError());
                    continue;
                }
                List<String> effB = sb.getEffectiveOutputVariables();
                List<String> effA = sa.getEffectiveOutputVariables();
                if (effA == null || effB == null)
                {
                    ledger.problem("(b) " + id + " @" + domain + " no effective list");
                    continue;
                }
                if (!new HashSet<>(effA).equals(new HashSet<>(effB)))
                {
                    ledger.problem("(b) " + id + " @" + domain + " MEMBERS differ: " + effB + " -> "
                            + effA);
                }
                else if (!effA.equals(effB))
                {
                    ledger.specialisedOrderMoves++;
                    if (!row.specialisedMoveDomains.contains(domain))
                    {
                        ledger.problem("(b) " + id + " @" + domain + " ORDER moves at the"
                                + " specialised level but not in the manifest: " + effB + " -> "
                                + effA);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ arm (c)


    private static void expansionArm(Map<String, Row> manifest, Map<String, Rule> before,
            Map<String, Rule> after, Ledger ledger)
        throws IOException
    {
        Map<String, IDataTable> study = loadStudy();
        DatasetResolver.WithInventory studyResolver = new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String domainName)
            {
                return study.get(domainName.toUpperCase(Locale.ROOT));
            }


            @Override
            public Set<String> availableDatasets()
            {
                return study.keySet();
            }
        };
        for (Map.Entry<String, Rule> e : before.entrySet())
        {
            String id = e.getKey();
            Rule b = e.getValue();
            Rule a = after.get(id);
            boolean template = !WildcardExpander.collectWildcardNames(b).isEmpty()
                    || (b.getExpansion() != null && !b.getExpansion().isEmpty());
            if (a == null || !template)
            {
                continue;
            }
            ledger.templates++;
            Row row = manifest.get(id);
            for (String family : FAMILIES)
            {
                Path dir = SCENARIOS.resolve(family).resolve(id);
                if (!Files.isDirectory(dir))
                {
                    continue;
                }
                List<Path> files;
                try (Stream<Path> list = Files.list(dir))
                {
                    files = list.filter(p -> p.toString().endsWith(".cdt"))
                            .filter(AbstractRuleTestSuitesFactory::hasShebang).sorted().toList();
                }
                for (Path file : files)
                {
                    RuleTestScenario s = RuleTestCdt.load(file);
                    OverlayDataTable primary = s.primaryTable();
                    if (primary == null)
                    {
                        continue;
                    }
                    ledger.expansionScenarios++;
                    TokenExpander.Context ctx = new TokenExpander.Context(
                            ScopeVariableSource.of(s.resolver(), primary), null, s.getDomain());
                    compareExpansion(id, file.getFileName().toString(),
                            WildcardExpander.tryExpand(b, primary.getMetaData(), ctx),
                            WildcardExpander.tryExpand(a, primary.getMetaData(), ctx), row, ledger);
                }
            }
            if (STUDY_TEMPLATES.contains(id))
            {
                for (Map.Entry<String, IDataTable> t : study.entrySet())
                {
                    ledger.studyTables++;
                    TokenExpander.Context ctx = new TokenExpander.Context(
                            ScopeVariableSource.of(studyResolver, t.getValue()), null, t.getKey());
                    compareExpansion(id, "study/" + t.getKey(),
                            WildcardExpander.tryExpand(b, t.getValue().getMetaData(), ctx),
                            WildcardExpander.tryExpand(a, t.getValue().getMetaData(), ctx), row,
                            ledger);
                }
            }
        }
    }


    private static void compareExpansion(String id, String where,
            WildcardExpander.ExpansionResult rb, WildcardExpander.ExpansionResult ra, Row row,
            Ledger ledger)
    {
        if (rb instanceof WildcardExpander.ExpansionResult.Expanded(List<Rule> eb, List<String> _)
                && ra instanceof WildcardExpander.ExpansionResult.Expanded(List<Rule> ea, List<String> _))
        {
            if (eb.size() != ea.size())
            {
                ledger.problem("(c) " + id + " " + where + ": " + eb.size() + " vs " + ea.size()
                        + " expansions");
                return;
            }
            ledger.templatesCompared.add(id);
            for (int i = 0; i < eb.size(); i++)
            {
                Rule cb = eb.get(i);
                Rule ca = ea.get(i);
                if (!String.valueOf(cb.effectiveId()).equals(String.valueOf(ca.effectiveId())))
                {
                    ledger.problem("(c) " + id + " " + where + ": expansion ids differ "
                            + cb.effectiveId() + " vs " + ca.effectiveId());
                    continue;
                }
                for (Rule c : List.of(cb, ca))
                {
                    if (c.getCheckExpr() == null)
                    {
                        RulePackageLoader.installNativeExpr(c);
                    }
                    RulePackageLoader.deriveOutputVariables(c);
                }
                ledger.expandedCopies++;
                if (ca.getLoadError() != null)
                {
                    ledger.problem("(c) " + id + " " + where + " " + ca.effectiveId()
                            + " load error after strip: " + ca.getLoadError());
                    continue;
                }
                List<String> effB = cb.getEffectiveOutputVariables();
                List<String> effA = ca.getEffectiveOutputVariables();
                if (effA == null || effB == null)
                {
                    ledger.problem("(c) " + id + " " + where + " no effective list");
                    continue;
                }
                if (!new HashSet<>(effA).equals(new HashSet<>(effB)))
                {
                    ledger.problem("(c) " + id + " " + where + " " + ca.effectiveId()
                            + " MEMBERS differ: " + effB + " -> " + effA);
                }
                else if (!effA.equals(effB) && !row.orderMoves)
                {
                    ledger.problem("(c) " + id + " " + where + " " + ca.effectiveId()
                            + " ORDER moves at the expansion level but not in the manifest: " + effB
                            + " -> " + effA);
                }
            }
        }
        else if (rb.getClass() != ra.getClass())
        {
            ledger.problem(
                    "(c) " + id + " " + where + ": expansion outcome differs " + rb + " vs " + ra);
        }
        else
        {
            ledger.expansionNoMatch++;
        }
    }


    /**
     * The {@code testdata/study} datasets by upper-cased name; empty when the fixture is absent.
     */
    private static Map<String, IDataTable> loadStudy() throws IOException
    {
        Path study = PROJECT_DIR.resolve("testdata").resolve("study");
        Map<String, IDataTable> out = new LinkedHashMap<>();
        if (!Files.isDirectory(study))
        {
            System.out.println(
                    "[ovsweep-static] no testdata/study — the study arm of (c) is" + " skipped");
            return out;
        }
        URI defineUri = study.resolve("define.xml").toUri();
        IDataTableManager manager = new LocalDataTableManager();
        IDataTableLibraryRef library = manager.getLibraryRef(defineUri, null);
        for (ILibraryMemberRef member : manager.getLibraryMembers(library).toList())
        {
            IDataTableRef ref = manager.getDataTableRef(member, null);
            out.put(member.getName().toUpperCase(Locale.ROOT), manager.getDataTable(ref));
        }
        return out;
    }
}
