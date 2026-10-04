package net.cumba.corej.core.doc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.BindingDomains;
import net.cumba.corej.core.expr.eval.DomainScan;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;

/**
 * Generates {@code generated/routing-census.tsv} — one row per shipped {@code Core.Id} carrying
 * that rule's <b>derived</b> {@code Sensitivity} and the evaluation <b>domain</b>
 * {@link DomainScan} infers from its Check ({@code PLAN-leaf-scope-domain-inference.md} §3.2 —
 * re-grounded 2026-08-22, owner ruling Q11: the census is that plan's phase-3 equivalence gate, so
 * a rule whose inferred domain moves is named here exactly as a re-typed rule once was), plus the
 * installed domain of <b>each binding</b> (combined review XCUT M3 — a binding is evaluated under
 * its own domain, so a per-binding re-routing must move the census even when the Check's join does
 * not).
 *
 * <h2>Why this exists — it is measured, not suspected</h2>
 * <p>
 * Neither column is authored: the shipped {@code rules/} corpus carries {@code Sensitivity} only on
 * the documented override set, and no rule field names the domain at all — {@code Sensitivity} is
 * produced by {@code RuleClassifier.deriveSensitivity} at load time and the domain by
 * {@link DomainScan} over the installed Check expression. The domain decides <b>which
 * {@code RuleRunner} branch a rule executes under</b> and, through
 * {@code ValidationReportBuilder.scopeOf}, the {@code FindingScope} of every finding the rule
 * emits; {@code Sensitivity} decides its multiplicity. A silent re-routing is therefore both an
 * execution change and an output change. (Until phase 7 of the leaf-scope plan the census carried a
 * derived {@code Rule_Type} column as the routing key; that field no longer exists.)
 * </p>
 * <p>
 * Wave 33 replaced the old derivation gate and per-rule derived-<em>value</em> coverage fell from
 * 14 067 compared entries to 3 {@code @CsvSource} cases. The defence was that the {@code .cdt}
 * scenarios would catch a re-routing indirectly. <b>Wave-34 lane B measured that defence and it
 * failed</b> ({@code plans/done/PLAN-derivation-routing-coverage.md} §7, Fix #231): re-routing all
 * 75 {@code Domain Presence Check} rules to {@code Record Data} produced <b>zero</b> red scenarios
 * out of 5 284, and 1 849 shipped rules carry no scenario at all. Scenarios assert
 * <em>violations</em>, not <em>framing</em>.
 * </p>
 *
 * <h2>Why a generated artifact and not a hand-checked baseline</h2>
 * <p>
 * A snapshot pins today's behaviour including any defect in it — the criticism that retired the old
 * Gate B, and it applies here too. What converts it from liability to asset is the generator
 * discipline this class mirrors from {@link RuleDocCensus}: the artifact is <b>rebuilt by a
 * committed script</b> ({@code scripts/gen-routing-census.sh}), so a legitimate derivation
 * improvement is a one-command, reviewable diff that <em>names every affected rule</em>, and an
 * illegitimate one is a diff nobody asked for. The old gate had no generator, which is exactly why
 * regenerating it was indistinguishable from accepting a regression.
 * </p>
 *
 * <h2>⚠ The view, and why it is the runtime one</h2>
 * <p>
 * The census reads {@code rules/rules-*.json} through {@link RulePackageLoader#load(Path)} — the
 * corpus the engine actually executes, completed by the same
 * {@code RulePackageLoader.deriveOmittedFields} pass that runs in production, and the same view
 * wave-34 lane B's probe measured. It is <b>not</b> the assembled
 * ({@code net.cumba.corej.core.expr.convert.RuleStorageAssembler}) view that {@link RuleDocCensus}
 * C.2 / C.3 tabulate, for two measured reasons:
 * </p>
 * <ol>
 * <li><b>Routing happens here.</b> {@code RuleRunner} branches on the evaluation domain the loader
 * installed; the assembled view is an in-memory documentation projection of {@code rules-src} that
 * nothing executes.</li>
 * <li>⚠⚠ <b>The assembled view cannot report an N3P-shaped re-routing at all.</b> Measured
 * 2026-08-12: with the {@code Domain Presence Check} → {@code Record Data} perturbation installed,
 * {@code RuleStorageAssembler.downgradePresenceOperators} throws {@code IllegalStateException: Rule
 * CDISC-AD0061: cannot lower 'ds_exists' under Rule_Type 'other'} (a guard since deleted) before a
 * single row is produced. A census built on it would fail with a stack trace naming <em>one</em>
 * rule instead of a diff naming all 75 — losing exactly the property this artifact exists for.</li>
 * </ol>
 * <p>
 * The assembled view is not left unguarded by that choice: {@code RuleDocCensus} C.3 pins its
 * {@code Sensitivity} <em>distribution</em> (and C.2 the {@code Variable_Universe} one). (The
 * lowering guard that once hard-failed assembly was deleted with the generic presence operators —
 * phase 1 of the leaf-scope plan.)
 * </p>
 *
 * <h2>Sizing</h2>
 * <p>
 * One row per distinct {@code Core.Id}, not one per {@code (package, rule)} entry: measured on the
 * unperturbed corpus, <b>0</b> ids derive a different {@code Sensitivity} in different packages and
 * <b>0</b> infer a different domain (asserted by
 * {@link RoutingCensusDriftTest#classifyingFieldsAreInvariantPerRuleId()}), so the per-id
 * projection is lossless and 3.8× smaller than the 14 076-entry fan-out.
 * </p>
 *
 * <h2>Regenerating</h2>
 * <p>
 * {@code scripts/gen-routing-census.sh}, or {@link #main(String[])} with the artifact path. It is
 * <b>step 5 of the five-step corpus regeneration sequence</b>
 * ({@code scripts/regen-corpus-all.sh}).
 * </p>
 */
public final class RoutingCensus
{

    /** The column header, recorded in the artifact's banner. */
    public static final String COLUMNS = "Core.Id\tSensitivity\tDomain\tBindings";

    /** Prefix of the artifact's banner lines. Everything else is a data row. */
    public static final String COMMENT = "#";

    /** Rendered in place of a field the derivation left unset. */
    public static final String ABSENT = "(absent)";

    /** The {@code Bindings} field of a rule that declares no binding. */
    public static final String NO_BINDINGS = "(none)";

    /** Separates the {@code $name=Domain} entries of the {@code Bindings} field. */
    public static final String BINDING_SEPARATOR = ";";

    /** How many tab-separated fields a data row carries — the length of {@link #COLUMNS}. */
    private static final int FIELDS = COLUMNS.split("\t", -1).length;

    private RoutingCensus()
    {
    }

    // ---------------------------------------------------------------- file locations


    /** The {@code corej-rules} module directory (Surefire's working directory). */
    public static Path moduleDir()
    {
        return Path.of(System.getProperty("projectBasedir", "."));
    }


    /** The committed artifact. */
    public static Path artifactFile()
    {
        return moduleDir().resolve("generated").resolve("routing-census.tsv");
    }


    /** The shipped native / expression corpus — the view the engine executes. */
    public static Path nativeCorpusDir()
    {
        return CorpusPaths.rules();
    }

    // ---------------------------------------------------------------- model

    /**
     * One rule's derived routing fields.
     *
     * @param sensitivity
     *            the derived {@code Sensitivity}
     * @param domain
     *            the evaluation domain {@link DomainScan} infers from the Check — {@code {}},
     *            {@code {VAR}}, {@code {ROW}} or {@code {VAR,ROW}} (§3.2 of
     *            {@code PLAN-leaf-scope-domain-inference.md}); {@link #ABSENT} when the Check has
     *            no expression surface at all
     * @param bindings
     *            every compiled binding's installed evaluation domain
     *            ({@code CompiledBinding.domain()}, what {@code RuleRunner} evaluates the binding
     *            under), rendered {@code $name={ROW}} in authored order and joined by
     *            {@link #BINDING_SEPARATOR}; {@link #NO_BINDINGS} for a rule that declares none. ⭐
     *            Combined review XCUT M3: the census recorded only the Check's joined domain, so a
     *            per-binding re-routing (XCUT H1's whole class — a binding moving between
     *            {@code {}} and {@code {ROW}} while the Check's join stays put) moved nothing here.
     */
    public record Row(String sensitivity, String domain, String bindings)
    {
    }


    /**
     * The corpus as {@link RulePackageLoader} leaves it.
     *
     * @param rules
     *            the census, projected to distinct {@code Core.Id}.
     * @param entries
     *            the un-projected fan-out size, so a collapse of the fan-out is visible.
     * @param packages
     *            how many {@code rules-*.json} packages were read.
     * @param inconsistentIds
     *            ids deriving different values in different packages. The per-id projection is only
     *            well defined while this is empty, so it is collected rather than assumed.
     */
    public record Snapshot(SortedMap<String, Row> rules, int entries, int packages,
            List<String> inconsistentIds)
    {
    }

    // ---------------------------------------------------------------- reading

    /** Loads every shipped package and projects it to one row per {@code Core.Id}. */
    public static Snapshot snapshot() throws IOException
    {
        SortedMap<String, Row> census = new TreeMap<>();
        Set<String> inconsistent = new TreeSet<>();
        int entries = 0;
        List<Path> packages = nativePackages();
        for (Path pkg : packages)
        {
            RulePackage loaded = RulePackageLoader.load(pkg);
            Map<String, Rule> rules = loaded.getRules();
            if (rules == null)
            {
                continue;
            }
            entries += rules.size();
            for (Map.Entry<String, Rule> e : rules.entrySet())
            {
                Row row = toRow(e.getValue());
                Row prior = census.putIfAbsent(e.getKey(), row);
                if (prior != null && !prior.equals(row))
                {
                    inconsistent.add(e.getKey());
                }
            }
        }
        return new Snapshot(census, entries, packages.size(), List.copyOf(inconsistent));
    }


    /** The shipped native packages, sorted. {@code packages.json} is an index, not a package. */
    static List<Path> nativePackages() throws IOException
    {
        try (Stream<Path> files = Files.list(nativeCorpusDir()))
        {
            return files.filter(p ->
            {
                String n = p.getFileName().toString();
                return n.startsWith("rules-") && n.endsWith(".json");
            }).sorted().toList();
        }
    }


    private static Row toRow(Rule rule)
    {
        return new Row(
                rule.getSensitivity() == null ? ABSENT : rule.getSensitivity().getJsonValue(),
                domainOf(rule), bindingsOf(rule));
    }


    /**
     * The {@code Bindings} field of a loaded rule: each compiled binding's installed domain, in
     * authored order ({@link Rule#bindingOrder()}), e.g. {@code $a={};$b={ROW}} — or
     * {@link #NO_BINDINGS}. A binding the loader left without a domain renders {@link #ABSENT},
     * never a guess.
     */
    static String bindingsOf(Rule rule)
    {
        List<CompiledBinding> bindings = rule.bindingOrder();
        if (bindings.isEmpty())
        {
            return NO_BINDINGS;
        }
        StringJoiner joined = new StringJoiner(BINDING_SEPARATOR);
        for (CompiledBinding binding : bindings)
        {
            net.cumba.corej.core.expr.eval.Domain domain = binding.domain();
            joined.add(binding.name() + "=" + (domain == null ? ABSENT : domain.label()));
        }
        return joined.toString();
    }


    /**
     * The domain of a loaded rule: the one the loader <b>cached</b> on the rule — the value
     * {@code RuleRunner} and {@code ValidationReportBuilder.scopeOf} actually route on, so a change
     * at the install site (not only in the inference) moves this census — else, for a rule the
     * native backend declined (a {@code *}/{@code y}-wildcard template, which
     * {@code DatasetRuleResolver} expands per dataset), the inference over the plain raise of the
     * Check, whose cursor demand is nevertheless readable from the template.
     */
    static String domainOf(Rule rule)
    {
        net.cumba.corej.core.expr.eval.Domain cached = rule.getEvaluationDomain();
        if (cached != null)
        {
            return cached.label();
        }
        Expr expr = rule.getCheckExpr();
        if (expr == null && rule.getCheck() != null)
        {
            try
            {
                expr = net.cumba.corej.core.CheckExprUnits.toExpr(rule.getCheck());
            }
            catch (net.cumba.corej.core.expr.ExpressionException _)
            {
                expr = null;
            }
        }
        return expr == null ? ABSENT : DomainScan.infer(expr, BindingDomains.forRule(rule)).label();
    }

    // ---------------------------------------------------------------- diffing


    /**
     * A human-readable, per-rule diff of two censuses — <b>the artifact's real payload</b>.
     *
     * <p>
     * A byte-level assertion on a 3 700-line file names one test and prints an unreadable dump.
     * Wave-34 lane B's {@code N3P} perturbation moved 75 rules; the value of the pin is that it
     * says <em>which</em> 75.
     * </p>
     *
     * @param expected
     *            the committed census.
     * @param actual
     *            what the generator produces now.
     * @return one line per differing id, sorted by id; empty when the two agree.
     */
    public static List<String> diff(Map<String, Row> expected, Map<String, Row> actual)
    {
        List<String> out = new ArrayList<>();
        for (String id : union(expected, actual))
        {
            Row e = expected.get(id);
            Row a = actual.get(id);
            if (e == null)
            {
                out.add(id + ": NEW — derives Sensitivity \"" + a.sensitivity() + "\", Domain "
                        + a.domain() + ", Bindings " + a.bindings());
            }
            else if (a == null)
            {
                out.add(id + ": GONE — the committed census has it as Sensitivity \""
                        + e.sensitivity() + "\", Domain " + e.domain() + ", Bindings "
                        + e.bindings());
            }
            else if (!e.equals(a))
            {
                out.add(id + ":" + field("Sensitivity", e.sensitivity(), a.sensitivity())
                        + field("Domain", e.domain(), a.domain())
                        + field("Bindings", e.bindings(), a.bindings()));
            }
        }
        return out;
    }


    private static String field(String name, String expected, String actual)
    {
        return expected.equals(actual) ? ""
                : " " + name + " \"" + expected + "\" → \"" + actual + "\"";
    }


    private static Set<String> union(Map<String, Row> a, Map<String, Row> b)
    {
        Set<String> ids = new TreeSet<>(a.keySet());
        ids.addAll(b.keySet());
        return ids;
    }

    // ---------------------------------------------------------------- rendering


    /** Renders the whole artifact. */
    public static String render(Snapshot s)
    {
        StringBuilder b = new StringBuilder();
        b.append("# Generated by RoutingCensus. DO NOT HAND-EDIT: regenerate with\n");
        b.append("#     scripts/gen-routing-census.sh\n");
        b.append("# — step 5 of the five-step corpus regeneration sequence.\n");
        b.append(
                "# RoutingCensusDriftTest fails the build when this file drifts from the corpus,\n");
        b.append("# and reports the difference as a per-rule diff rather than a byte dump.\n");
        b.append("#\n");
        b.append(
                "# Sensitivity and Domain are DERIVED, never authored. Domain is the evaluation\n");
        b.append(
                "# domain DomainScan infers from the Check alone — the join of its leaves' cursor\n");
        b.append(
                "# demands ({}, {VAR}, {ROW}, {VAR,ROW}) — and decides which RuleRunner branch a\n");
        b.append("# rule executes under and, through ValidationReportBuilder.scopeOf, the\n");
        b.append(
                "# FindingScope of every finding it emits (PLAN-leaf-scope-domain-inference.md).\n");
        b.append("# Sensitivity decides the finding multiplicity.\n");
        b.append(
                "# Bindings is each compiled binding's installed domain, $name={ROW} in authored\n");
        b.append(
                "# order joined by ';' — (none) without bindings — which decides the cursor the\n");
        b.append("# binding is evaluated under, independently of the Check's joined Domain.\n");
        b.append(
                "# The .cdt scenarios do not cover that routing — wave-34 lane B measured 75 of\n");
        b.append("# 75 re-routed rules producing zero reds (Fix #231). This file is the pin.\n");
        b.append("#\n");
        b.append(
                "# View: rules/rules-*.json as RulePackageLoader.load leaves it — the corpus the\n");
        b.append("#       engine executes and routes on — projected to distinct Core.Id.\n");
        b.append("#\n");
        b.append("# Rules: ").append(s.rules().size()).append('\n');
        b.append("# Packages: ").append(s.packages()).append('\n');
        b.append("# Entries before the per-id projection: ").append(s.entries()).append('\n');
        b.append("# Columns: ").append(COLUMNS).append('\n');
        for (Map.Entry<String, Row> e : s.rules().entrySet())
        {
            b.append(e.getKey()).append('\t').append(e.getValue().sensitivity()).append('\t')
                    .append(e.getValue().domain()).append('\t').append(e.getValue().bindings())
                    .append('\n');
        }
        return b.toString();
    }


    /**
     * Parses a rendered artifact back into rows.
     *
     * @throws IllegalArgumentException
     *             on a row that is not exactly four tab-separated fields, or on a duplicate id — a
     *             truncated or hand-merged artifact must fail loudly rather than pin half a corpus.
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
            if (fields.length != FIELDS)
            {
                throw new IllegalArgumentException("routing-census.tsv line " + lineNumber + ": "
                        + "expected " + FIELDS + " tab-separated fields (" + COLUMNS + "), found "
                        + fields.length + ": " + line);
            }
            if (rows.put(fields[0], new Row(fields[1], fields[2], fields[3])) != null)
            {
                throw new IllegalArgumentException("routing-census.tsv line " + lineNumber + ": "
                        + "duplicate Core.Id " + fields[0]);
            }
        }
        return rows;
    }

    // ---------------------------------------------------------------- entry point


    /**
     * Rewrites the artifact.
     *
     * @param args
     *            optional single argument: the artifact path. Defaults to {@link #artifactFile()}.
     */
    public static void main(String[] args)
    {
        try
        {
            Path out = args.length > 0 ? Path.of(args[0]) : artifactFile();
            Snapshot s = snapshot();
            Files.writeString(out, render(s), StandardCharsets.UTF_8);
            System.err.printf("[routing-census] %d rules (%d entries over %d packages)%n",
                    s.rules().size(), s.entries(), s.packages());
            if (!s.inconsistentIds().isEmpty())
            {
                System.err.println("[routing-census] ⚠ ids inconsistent across packages: "
                        + s.inconsistentIds());
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

}
