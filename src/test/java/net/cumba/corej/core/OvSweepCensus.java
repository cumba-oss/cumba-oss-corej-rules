package net.cumba.corej.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.corej.core.exec.RuleSpecialiser;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.OutputVariableToken;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.jspecify.annotations.Nullable;

/**
 * Phase 0 of {@code PLAN-redundant-output-variables-sweep}: the census of every authored
 * {@code Output_Variables} entry the derivation produces anyway, computed through
 * {@link OvSweepClassifier} over every generated package, written as the plan's manifest and the
 * four committed contract files the lint and the differential read.
 *
 * <p>
 * Usage (test scope): {@code OvSweepCensus <manifest-out-dir> <contracts-dir>}. Reads the corpus at
 * {@link CorpusPaths#rules()} and the authored YAML under {@code rules-src/checks}. Prints the
 * figures of plan §7.1 to stdout. Read-only on the corpus.
 * </p>
 *
 * <h2>One rule, several packages</h2>
 * <p>
 * A rule ships in every package its {@code Standards} block names, and the assembled view can
 * differ per standard version. The strip set of a rule is therefore the <b>intersection</b> over
 * its packages (an entry is redundant only where every view derives it), and the manifest records a
 * disagreement count — measured 0 at the plan's HEADs, but asserted rather than assumed.
 * </p>
 */
public final class OvSweepCensus
{

    /** The manifest's file name (committed by the coordinator under {@code plans/findings/}). */
    public static final String MANIFEST_FILE = "MANIFEST-redundant-output-variables.tsv";

    /** The committed contract files, under {@code src/test/resources/ovsweep/}. */
    public static final String ROSTER_FILE = "manifest-roster.txt";

    public static final String ORDER_MOVES_FILE = "accepted-order-moves.tsv";

    public static final String RESIDUAL_FILE = "q2-residual.tsv";

    public static final String UNSHIPPED_FILE = "unshipped.txt";

    public static final String MANIFEST_HEADER = String.join("\t", "coreId", "family", "packages",
            "perVariable", "scope", "authored", "exclusions", "strip", "residual",
            "effectiveBefore", "effectiveAfter", "orderMoves", "firstColumnMoves", "keyDeleted",
            "keepsOnlyExclusions", "specialisedOrderMoveDomains", "specialisedBefore",
            "specialisedAfter");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final java.util.regex.Pattern DOMAIN_CODE = java.util.regex.Pattern
            .compile("[A-Z0-9]{2,8}");

    private OvSweepCensus()
    {
    }

    /** One rule's census row — the verdict agreed over every package carrying the rule. */
    public record Row(String coreId, String family, int packages, OvSweepClassifier.Verdict verdict,
            List<String> rawAuthored, boolean disagree, SpecialisedMoves specialised)
    {

        String scope()
        {
            if (!verdict.strip().isEmpty())
            {
                return "STRIP";
            }
            return verdict.residual().isEmpty() ? "NONE" : "RESIDUAL-ONLY";
        }


        String line()
        {
            return String.join("\t", coreId, family, Integer.toString(packages),
                    Boolean.toString(verdict.perVariable()), scope(), join(verdict.authored()),
                    join(verdict.exclusions()), join(verdict.strip()), join(verdict.residual()),
                    join(verdict.before()), join(verdict.after()),
                    Boolean.toString(verdict.orderMoves()),
                    Boolean.toString(verdict.firstColumnMoves()),
                    Boolean.toString(verdict.keyDeleted()),
                    Boolean.toString(verdict.keepsOnlyExclusions()),
                    String.join(",", specialised.domains()), join(specialised.before()),
                    join(specialised.after()));
        }
    }


    /**
     * The {@code --} specialisation level of one rule (arm (b) of the static differential, folded
     * into the census so the contract carries it): the domains for which the specialised copy's
     * effective order moves with the strip although — or in addition to — the package level, and
     * the before / after lists of a representative domain ({@code AE} when it is one of them, else
     * the first). Why it exists: a rule that pins no domain and reads {@code --SEQ} has the
     * location variable removed from its derived set at the package level (D5 keys on the literal
     * {@code --SEQ}) but derives the concrete {@code AESEQ} once specialised, so its per-dataset
     * order can move where the package-level prediction says it does not (measured: 4 rules,
     * {@code CDISC-CG0028}, {@code CDISC-SEND-0138}, {@code FDA-SD0005}, {@code PMDA-SD0005}).
     */
    public record SpecialisedMoves(List<String> domains, List<String> before, List<String> after)
    {

        static final SpecialisedMoves NONE = new SpecialisedMoves(List.of(), List.of(), List.of());
    }


    /** The whole census. */
    public record Census(Map<String, Row> rows, List<String> unshipped, List<String> notes)
    {
    }

    public static void main(String[] args) throws IOException
    {
        if (args.length != 2)
        {
            System.err.println("usage: OvSweepCensus <manifest-out-dir> <contracts-dir>");
            System.exit(2);
        }
        Path manifestDir = Path.of(args[0]);
        Path contractsDir = Path.of(args[1]);
        Files.createDirectories(manifestDir);
        Files.createDirectories(contractsDir);
        Census census = census(CorpusPaths.rules(),
                CorpusPaths.moduleDir().resolve("rules-src").resolve("checks"));
        write(census, manifestDir.resolve(MANIFEST_FILE), contractsDir);
        System.out.println(summary(census));
    }


    /** Runs the census over {@code rulesDir}'s packages and {@code checksDir}'s YAML. */
    public static Census census(Path rulesDir, Path checksDir) throws IOException
    {
        List<String> notes = new ArrayList<>();
        Map<String, List<OvSweepClassifier.Verdict>> perRule = new TreeMap<>();
        Map<String, List<String>> rawAuthored = new TreeMap<>();
        Map<String, Rule> firstRule = new TreeMap<>();
        Set<String> universe = new TreeSet<>();
        Set<String> shipped = new TreeSet<>();
        int entries = 0;
        for (Path pkg : packages(rulesDir))
        {
            RulePackage loaded = RulePackageLoader.load(pkg);
            for (Map.Entry<String, Rule> e : loaded.getRules().entrySet())
            {
                entries++;
                Rule rule = e.getValue();
                String coreId = e.getKey();
                shipped.add(coreId);
                universe.addAll(concreteDomains(rule));
                if (rule.getLoadError() != null)
                {
                    notes.add("load error: " + pkg.getFileName() + " / " + coreId + ": "
                            + rule.getLoadError());
                    continue;
                }
                if (rule.getOutcome() == null || rule.getOutcome().getOutputVariables() == null
                        || rule.getOutcome().getOutputVariables().isEmpty())
                {
                    continue;
                }
                perRule.computeIfAbsent(coreId, _ -> new ArrayList<>())
                        .add(OvSweepClassifier.classify(rule));
                rawAuthored.putIfAbsent(coreId,
                        List.copyOf(rule.getOutcome().getOutputVariables()));
                firstRule.putIfAbsent(coreId, rule);
            }
        }
        notes.add("package entries read: " + entries + "; distinct shipped ids: " + shipped.size()
                + "; rules with authored Output_Variables: " + perRule.size());

        Map<String, String> families = families(checksDir);
        Map<String, Row> rows = new TreeMap<>();
        int disagreements = 0;
        for (Map.Entry<String, List<OvSweepClassifier.Verdict>> e : perRule.entrySet())
        {
            List<OvSweepClassifier.Verdict> views = e.getValue();
            OvSweepClassifier.Verdict agreed = agree(views);
            boolean disagree = views.stream().anyMatch(v -> !v.strip().equals(agreed.strip())
                    || !v.before().equals(agreed.before()) || !v.after().equals(agreed.after())
                    || v.perVariable() != agreed.perVariable());
            if (disagree)
            {
                disagreements++;
                notes.add("packages disagree on " + e.getKey() + ": "
                        + views.stream()
                                .map(v -> v.strip() + "/" + v.before() + "->" + v.after()
                                        + (v.perVariable() ? " perVariable" : ""))
                                .distinct().toList());
            }
            String family = families.getOrDefault(e.getKey(), "?");
            SpecialisedMoves specialised = agreed.strip().isEmpty() ? SpecialisedMoves.NONE
                    : specialisedMoves(firstRule.get(e.getKey()), agreed, universe, notes);
            rows.put(e.getKey(), new Row(e.getKey(), family, views.size(), agreed,
                    rawAuthored.get(e.getKey()), disagree, specialised));
        }
        notes.add("cross-package disagreements: " + disagreements);
        notes.add("specialisation domain universe: " + universe.size() + " codes");

        List<String> unshipped = new ArrayList<>();
        for (Map.Entry<String, String> f : families.entrySet())
        {
            if (!shipped.contains(f.getKey()) && carriesOutputVariables(checksDir, f))
            {
                unshipped.add(f.getKey());
            }
        }
        return new Census(rows, unshipped, notes);
    }


    /**
     * The verdict agreed across a rule's package views: the strip set is the intersection; when
     * every view agrees (the measured case) it is the first view verbatim.
     */
    private static OvSweepClassifier.Verdict agree(List<OvSweepClassifier.Verdict> views)
    {
        OvSweepClassifier.Verdict first = views.get(0);
        if (views.size() == 1)
        {
            return first;
        }
        List<String> strip = new ArrayList<>(first.strip());
        boolean perVariable = first.perVariable();
        for (OvSweepClassifier.Verdict v : views.subList(1, views.size()))
        {
            strip.retainAll(v.strip());
            perVariable |= v.perVariable();
        }
        if (strip.equals(first.strip()) && perVariable == first.perVariable())
        {
            return first;
        }
        // A genuinely disagreeing rule: keep the first view's lists, the agreed strip set. The
        // manifest flags it (disagree = true) so a reader never takes before/after at face value.
        List<String> residual = new ArrayList<>(first.authored());
        residual.retainAll(first.derived());
        residual.removeAll(strip);
        return new OvSweepClassifier.Verdict(first.coreId(), perVariable, first.authored(),
                first.exclusions(), first.derived(), List.copyOf(strip), List.copyOf(residual),
                first.before(), first.after());
    }


    /** Writes the manifest and the four contract files. */
    public static void write(Census census, Path manifest, Path contractsDir) throws IOException
    {
        StringBuilder m = new StringBuilder();
        m.append("# ").append(MANIFEST_FILE).append(" — PLAN-redundant-output-variables-sweep P0,")
                .append(" generated by OvSweepCensus (cumba-corej-rules, test scope) through")
                .append(" OutputVariableDeriver.derive()/derivedSet(); lists are |-joined;")
                .append(" scope STRIP = entries removed, RESIDUAL-ONLY = Q2 (a) kept entries only,")
                .append(" NONE = nothing derived.\n");
        for (String note : census.notes())
        {
            m.append("# ").append(note).append('\n');
        }
        m.append(MANIFEST_HEADER).append('\n');
        StringBuilder roster = new StringBuilder();
        roster.append("# manifest-roster.txt — the rules whose authored Output_Variables entries")
                .append(" the sweep strips (PLAN-redundant-output-variables-sweep P0, one Core.Id")
                .append(" per line). Read by RedundantOvSweepDifferentialTest as its population.\n");
        StringBuilder moves = new StringBuilder();
        moves.append("# accepted-order-moves.tsv — Q1 (owner 2026-10-04): rules whose effective")
                .append(" Output_Variables ORDER moves with the strip (members unchanged).")
                .append(" coreId<TAB>level<TAB>domains<TAB>before<TAB>after, lists |-joined; level")
                .append(" `package` (domains `*`) is the loaded rule, level `specialised` the")
                .append(" --specialised copy for the listed domains (before / after shown for the")
                .append(" first listed, AE when present). The report column order of these rules")
                .append(" follows the derivation (walk order, variable_name hoisted). Read by")
                .append(" RedundantOvSweepDifferentialTest: an ORDER-ONLY difference passes only")
                .append(" for a listed rule, as the derivation's own order.\n");
        StringBuilder residual = new StringBuilder();
        residual.append("# q2-residual.tsv — Q2 (a): derived authored entries KEPT on per-variable")
                .append(" rules (an authored entry keeps a null-valued key where a derived one is")
                .append(" omitted — RuleRunner.putUnlessDerivedNull). coreId<TAB>entry.")
                .append(" The allowance roster of RedundantOutputVariableLintTest; owned and retired")
                .append(" by PLAN-output-variable-null-key-provenance (its D3 phase strips them).\n");
        StringBuilder unshipped = new StringBuilder();
        unshipped.append(
                "# unshipped.txt — authored rules-src YAMLs that carry Output_Variables but")
                .append(" ship in no package (Executability: Not Executable), one Core.Id per line.")
                .append(" Out of the sweep's scope (neither stripped nor linted); asserted exactly by")
                .append(" RedundantOutputVariableLintTest so a rule cannot hide here.\n");
        for (Row row : census.rows().values())
        {
            m.append(row.line()).append('\n');
            OvSweepClassifier.Verdict v = row.verdict();
            if (!v.strip().isEmpty())
            {
                roster.append(row.coreId()).append('\n');
            }
            if (v.orderMoves())
            {
                moves.append(row.coreId()).append("\tpackage\t*\t").append(join(v.before()))
                        .append('\t').append(join(v.after())).append('\n');
            }
            if (!row.specialised().domains().isEmpty())
            {
                moves.append(row.coreId()).append("\tspecialised\t")
                        .append(String.join(",", row.specialised().domains())).append('\t')
                        .append(join(row.specialised().before())).append('\t')
                        .append(join(row.specialised().after())).append('\n');
            }
            for (String entry : v.residual())
            {
                residual.append(row.coreId()).append('\t').append(entry).append('\n');
            }
        }
        for (String id : census.unshipped())
        {
            unshipped.append(id).append('\n');
        }
        Files.writeString(manifest, m.toString(), StandardCharsets.UTF_8);
        Files.writeString(contractsDir.resolve(ROSTER_FILE), roster.toString(),
                StandardCharsets.UTF_8);
        Files.writeString(contractsDir.resolve(ORDER_MOVES_FILE), moves.toString(),
                StandardCharsets.UTF_8);
        Files.writeString(contractsDir.resolve(RESIDUAL_FILE), residual.toString(),
                StandardCharsets.UTF_8);
        Files.writeString(contractsDir.resolve(UNSHIPPED_FILE), unshipped.toString(),
                StandardCharsets.UTF_8);
    }


    /** The §7.1 figures. */
    public static String summary(Census census)
    {
        int rulesWithOv = census.rows().size();
        int authoredEntries = 0;
        int exclusionEntries = 0;
        int derivedAuthored = 0;
        int stripEntries = 0;
        int stripRules = 0;
        int keyDeleted = 0;
        int onlyExclusions = 0;
        int orderMoves = 0;
        int firstColumn = 0;
        int perVariableRules = 0;
        int perVariableNameStripped = 0;
        int residualEntries = 0;
        int residualRules = 0;
        int emptyAfter = 0;
        int specialisedMoves = 0;
        int specialisedOnly = 0;
        Map<String, Integer> byFamily = new TreeMap<>();
        Map<String, Integer> residualByEntry = new TreeMap<>();
        for (Row row : census.rows().values())
        {
            OvSweepClassifier.Verdict v = row.verdict();
            authoredEntries += v.authored().size();
            exclusionEntries += v.exclusions().size();
            derivedAuthored += v.strip().size() + v.residual().size();
            stripEntries += v.strip().size();
            if (!v.strip().isEmpty())
            {
                stripRules++;
                byFamily.merge(row.family(), 1, Integer::sum);
            }
            if (v.keyDeleted())
            {
                keyDeleted++;
            }
            if (v.keepsOnlyExclusions())
            {
                onlyExclusions++;
            }
            if (v.orderMoves())
            {
                orderMoves++;
            }
            if (v.firstColumnMoves())
            {
                firstColumn++;
            }
            if (v.perVariable())
            {
                perVariableRules++;
                if (v.strip().contains(OvSweepClassifier.VARIABLE_NAME))
                {
                    perVariableNameStripped++;
                }
            }
            if (!v.residual().isEmpty())
            {
                residualRules++;
                residualEntries += v.residual().size();
                for (String r : v.residual())
                {
                    residualByEntry.merge(r, 1, Integer::sum);
                }
            }
            if (!v.strip().isEmpty() && v.after().isEmpty())
            {
                emptyAfter++;
            }
            if (!row.specialised().domains().isEmpty())
            {
                specialisedMoves++;
                if (!v.orderMoves())
                {
                    specialisedOnly++;
                }
            }
        }
        return String.format(Locale.ROOT,
                "[ovsweep-census] rules with OV=%d authored include entries=%d !X=%d%n"
                        + "  derived authored entries=%d%n"
                        + "  strip set=%d entries / %d rules; key deleted=%d; keep only !X=%d%n"
                        + "  order moves=%d (first column changes in %d); specialised-level"
                        + " moves=%d rules (%d of them with no package-level move)%n"
                        + "  per-variable rules=%d; variable_name stripped on %d%n"
                        + "  Q2 residual=%d entries / %d rules; by entry=%s%n"
                        + "  strip rules by family=%s%n" + "  predicted EMPTY effective list=%d%n"
                        + "  unshipped YAMLs with OV=%d%n" + "  notes=%s",
                rulesWithOv, authoredEntries, exclusionEntries, derivedAuthored, stripEntries,
                stripRules, keyDeleted, onlyExclusions, orderMoves, firstColumn, specialisedMoves,
                specialisedOnly, perVariableRules, perVariableNameStripped, residualEntries,
                residualRules, residualByEntry, byFamily, emptyAfter, census.unshipped().size(),
                census.notes());
    }


    /**
     * Arm (b) of the static differential for one rule: specialise before and after the strip for
     * every domain the scope pins (else the whole universe), re-derive as
     * {@code DatasetRuleResolver.specialiseStaticRules} + {@code deriveOutputVariables} do, and
     * record the domains whose effective order moves. A member-set difference or a load error at
     * this level is a defect and goes to {@code notes} (the static differential fails on it too).
     */
    static SpecialisedMoves specialisedMoves(Rule rule, OvSweepClassifier.Verdict verdict,
            Set<String> universe, List<String> notes)
    {
        if (rule.getCheck() == null)
        {
            return SpecialisedMoves.NONE;
        }
        List<String> domains = pinnedDomains(rule);
        if (domains.isEmpty())
        {
            domains = new ArrayList<>(universe);
        }
        Outcome outcome = rule.getOutcome();
        List<String> original = outcome.getOutputVariables();
        List<String> stripped = new ArrayList<>();
        for (String entry : original)
        {
            if (OutputVariableToken.isExclusion(entry) || !verdict.strip().contains(entry))
            {
                stripped.add(entry);
            }
        }
        List<String> moved = new ArrayList<>();
        List<String> before = List.of();
        List<String> after = List.of();
        for (String domain : domains)
        {
            Rule sb = RuleSpecialiser.specialise(rule, null, domain);
            if (sb == rule)
            {
                continue;
            }
            RulePackageLoader.deriveOutputVariables(sb);
            Rule sa;
            outcome.setOutputVariables(stripped.isEmpty() ? null : stripped);
            try
            {
                sa = RuleSpecialiser.specialise(rule, null, domain);
                if (sa != rule)
                {
                    RulePackageLoader.deriveOutputVariables(sa);
                }
            }
            finally
            {
                outcome.setOutputVariables(original);
            }
            if (sa == rule)
            {
                notes.add("specialisation: " + verdict.coreId() + " @" + domain
                        + " resolves before the strip but not after");
                continue;
            }
            List<String> effB = sb.getEffectiveOutputVariables();
            List<String> effA = sa.getEffectiveOutputVariables();
            if (sa.getLoadError() != null || effA == null || effB == null
                    || !new java.util.HashSet<>(effA).equals(new java.util.HashSet<>(effB)))
            {
                notes.add("specialisation DEFECT: " + verdict.coreId() + " @" + domain + ": " + effB
                        + " -> " + effA + " loadError=" + sa.getLoadError());
                continue;
            }
            if (!effA.equals(effB))
            {
                if (moved.isEmpty() || "AE".equals(domain))
                {
                    before = effB;
                    after = effA;
                }
                moved.add(domain);
            }
        }
        return moved.isEmpty() ? SpecialisedMoves.NONE
                : new SpecialisedMoves(List.copyOf(moved), before, after);
    }


    /**
     * The concrete domain codes {@code rule}'s scope pins — the specialisation universe is the
     * union of these over the corpus. A pinned pattern ({@code AP--}, {@code SUPP--},
     * {@code ADTTE*}) is not a dataset the specialiser could be handed and is left out.
     */
    public static List<String> concreteDomains(Rule rule)
    {
        List<String> out = new ArrayList<>();
        for (String d : pinnedDomains(rule))
        {
            if (DOMAIN_CODE.matcher(d).matches())
            {
                out.add(d);
            }
        }
        return out;
    }


    public static List<String> pinnedDomains(Rule rule)
    {
        List<String> out = new ArrayList<>();
        if (rule.getScope() != null && rule.getScope().getDomains() != null
                && rule.getScope().getDomains().getInclude() != null)
        {
            for (String d : rule.getScope().getDomains().getInclude())
            {
                if (d != null && !d.isBlank() && !"ALL".equalsIgnoreCase(d))
                {
                    out.add(d);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers


    /** The generated packages, resolved through {@code packages.json} rather than by globbing. */
    public static List<Path> packages(Path rulesDir) throws IOException
    {
        List<Path> out = new ArrayList<>();
        JsonNode manifest = JSON
                .readTree(Files.readString(rulesDir.resolve(RulePackageManifest.FILE_NAME)));
        for (JsonNode entry : manifest.get("packages"))
        {
            out.add(rulesDir.resolve(entry.get("file").asText()));
        }
        return out;
    }


    /**
     * {@code Core.Id} → family directory name, from {@code rules-src/checks/<FAMILY>/<id>.yaml}.
     */
    public static Map<String, String> families(Path checksDir) throws IOException
    {
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(checksDir))
        {
            for (Path yaml : files.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .sorted().toList())
            {
                String id = yaml.getFileName().toString();
                id = id.substring(0, id.length() - ".yaml".length());
                out.put(id, yaml.getParent().getFileName().toString());
            }
        }
        return out;
    }


    private static boolean carriesOutputVariables(Path checksDir, Map.Entry<String, String> f)
        throws IOException
    {
        Path yaml = checksDir.resolve(f.getValue()).resolve(f.getKey() + ".yaml");
        return Files.readString(yaml, StandardCharsets.UTF_8).contains("Output_Variables");
    }


    static String join(@Nullable List<String> list)
    {
        return list == null || list.isEmpty() ? "" : String.join("|", list);
    }
}
