package net.cumba.cdisc.core.doc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Generates {@code documentation/zero-row-fire-census.tsv} — one row per shipped {@code Core.Id}
 * recording what {@link RuleRunner#execute(Rule, IDataTable)} answers on a <b>zero-row</b> table
 * that declares the rule's Check-leaf columns ({@code PLAN-zero-row-routing-rekey.md} phase 0 (b);
 * the measurement behind {@code GROUNDING-zero-row-rekey-2026-08-23.md} §3).
 *
 * <h2>What the columns mean</h2>
 * <p>
 * {@code Core.Id · Sensitivity · Domain · status · violations · totalViolationCount · message}. ⚠
 * <b>{@code totalViolationCount} is the load-bearing column.</b>
 * {@code RuleExecutionResult.totalViolationCount} defaults to {@code -1} and is set only by the row
 * path of {@code RuleRunner.executeUnified}, by {@code evaluateVariableValueNative} and by
 * {@code executeGrouped}. A {@code -1} therefore <em>proves</em> the verdict came from a site
 * upstream of the row path — {@code BroadcastFold}'s {@code datasetBroadcastResult} or
 * {@code evaluateMetadataNative} — and a {@code >= 0} proves the rule evaluated rows. That is the
 * discriminator that separates "fires on an empty dataset because it can decide without reading a
 * row" (the 29 pinned metadata firers, the five {@code $records_in_dataset == 0} twins) from "fired
 * only because of the synthetic 1-row zero-column table" (the 13 {@code DOMAIN}-column rules the
 * plan retired). A census without it cannot tell the two apart.
 * </p>
 *
 * <h2>Conditions, stated</h2>
 * <p>
 * No {@code DatasetResolver}, no Define / Library providers — library-dependent rules report
 * {@code SKIPPED} or {@code ERROR} — and the table declares the Check's leaf columns (the column
 * arguments of the {@code var_exists} family included), the non-{@code $} {@code Output_Variables}
 * and the {@code Requirements.Variables} columns ({@code --} resolved against the table's domain
 * prefix), not a real domain's column set. The table is named after the first concrete
 * {@code Scope.Domains.Include} entry when there is one, else after the first
 * {@code Scope.Data_Structures.Include} structure ({@code ADSL}; {@code ADLB} with {@code PARAMCD}
 * / {@code AVAL} for BDS; {@code ADAE} with {@code AETERM} for OCCDS), else {@code DM} — so the
 * {@code Requirements.Variables} and ADaM-structure gates do not park a rule before routing. The
 * banner counts every status and every {@code SKIPPED} cause, so a rule parked before routing is
 * visible in the artifact, not only in a message string. The <em>absolute</em> firing counts are
 * still not study-real; the return-site discriminator is structural and unaffected by any of that.
 * </p>
 *
 * <h2>Running</h2>
 * <p>
 * Disabled by default (it executes 3 800 rules). Run with
 * {@code mvn -P main -pl lib/corej-cdisc-rules -am test -Dtest=ZeroRowFireCensus
 * -Dcorej.zeroRowFireCensus=true -Dsurefire.failIfNoSpecifiedTests=false}, or
 * {@link #main(String[])} with the artifact path. Not part of the six-step corpus regeneration.
 * </p>
 */
public final class ZeroRowFireCensus
{

    /** The column header, recorded in the artifact's banner. */
    public static final String COLUMNS = "Core.Id\tSensitivity\tDomain\ttable\tstatus\tviolations\ttotalViolationCount\tmessage";

    private ZeroRowFireCensus()
    {
    }


    /** The committed artifact. */
    public static Path artifactFile()
    {
        return RoutingCensus.moduleDir().resolve("documentation")
                .resolve("zero-row-fire-census.tsv");
    }

    /**
     * One rule's zero-row verdict.
     *
     * @param sensitivity
     *            the derived {@code Sensitivity}
     * @param domain
     *            the cached evaluation domain ({@code {}}, {@code {VAR}}, {@code {ROW}},
     *            {@code {VAR,ROW}}) or {@link RoutingCensus#ABSENT}
     * @param table
     *            the name of the zero-row table the rule was executed against
     * @param status
     *            the execution status, {@code NULL} when the runner returned nothing, or
     *            {@code THROW} when execution threw
     * @param violations
     *            the materialised violation count
     * @param totalViolationCount
     *            the result's {@code totalViolationCount} — {@code -1} when the verdict was decided
     *            upstream of the row path
     * @param message
     *            the status message (or the exception) with tabs and newlines flattened
     */
    public record Row(String sensitivity, String domain, String table, String status,
            int violations, long totalViolationCount, String message)
    {
    }

    /** Loads every shipped package and executes each distinct {@code Core.Id} once. */
    public static SortedMap<String, Row> census() throws IOException
    {
        Map<String, Rule> byId = new TreeMap<>();
        for (Path pkg : RoutingCensus.nativePackages())
        {
            RulePackage loaded = RulePackageLoader.load(pkg);
            Map<String, Rule> rules = loaded.getRules();
            if (rules == null)
            {
                continue;
            }
            for (Map.Entry<String, Rule> e : rules.entrySet())
            {
                byId.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        SortedMap<String, Row> out = new TreeMap<>();
        for (Map.Entry<String, Rule> e : byId.entrySet())
        {
            out.put(e.getKey(), execute(e.getValue()));
        }
        return out;
    }


    /** Executes one rule against a zero-row table declaring its Check-leaf columns. */
    public static Row execute(Rule rule)
    {
        String sensitivity = rule.getSensitivity() == null ? RoutingCensus.ABSENT
                : rule.getSensitivity().getJsonValue();
        String domain = RoutingCensus.domainOf(rule);
        String table = "?";
        try
        {
            IDataTable t = zeroRowTable(rule);
            table = t.getMetaData().getName();
            RuleExecutionResult r = RuleRunner.execute(rule, t, _ -> null, variablePrefix(table));
            if (r == null)
            {
                return new Row(sensitivity, domain, table, "NULL", 0, Long.MIN_VALUE, "");
            }
            return new Row(sensitivity, domain, table, String.valueOf(r.getStatus()),
                    r.getViolations() == null ? 0 : r.getViolations().size(),
                    r.getTotalViolationCount(), flatten(r.getStatusMessage()));
        }
        catch (Throwable ex)
        {
            return new Row(sensitivity, domain, table, "THROW", 0, Long.MIN_VALUE,
                    flatten(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
    }

    /** The {@code var_exists} family — the only calls whose string argument names a column. */
    private static final Set<String> COLUMN_NAMING_CALLS = Set.of("var_exists", "var_not_exists");

    /**
     * A zero-row table declaring every plain column the Check's leaves name (the column argument of
     * a {@code var_exists}-family call included), every non-{@code $} output variable and every
     * {@code Requirements.Variables} {@code All}/{@code Any} entry; {@code STUDYID} alone when none
     * of those names a column. Named and, for ADaM, marked so the scope gates that run before
     * routing admit it.
     */
    static IDataTable zeroRowTable(Rule rule)
    {
        String name = tableName(rule);
        String prefix = variablePrefix(name);
        Set<String> cols = new LinkedHashSet<>();
        leaves(rule.getCheckExpr(), cols);
        if (rule.getOutcome() != null && rule.getOutcome().getOutputVariables() != null)
        {
            for (String v : rule.getOutcome().getOutputVariables())
            {
                if (!v.startsWith("$"))
                {
                    cols.add(v);
                }
            }
        }
        // Requirements.Variables — All AND Any. Both are "the rule cannot answer without this",
        // so a zero-row table that omits them is skipped by the requirement gate before it ever
        // reaches the fire question this census asks. ⛔ None is excluded: declaring one of those
        // columns is precisely what makes the rule skip.
        net.cumba.cdisc.core.model.VariableRequirement required = rule
                .effectiveVariableRequirement();
        if (required != null)
        {
            for (List<String> facet : java.util.Arrays.asList(required.getAll(), required.getAny()))
            {
                if (facet == null)
                {
                    continue;
                }
                for (String v : facet)
                {
                    cols.add(v.startsWith("--") ? prefix + v.substring(2) : v);
                }
            }
        }
        cols.addAll(structureMarkers(rule));
        MockTable mt = MockTable.of().name(name);
        int declared = 0;
        for (String c : cols)
        {
            if (c.matches("[A-Za-z_][A-Za-z0-9_]{0,31}"))
            {
                mt = mt.col(c);
                declared++;
            }
        }
        if (declared == 0)
        {
            mt = mt.col("STUDYID");
        }
        return mt.build();
    }


    private static String tableName(Rule rule)
    {
        if (rule.getScope() == null)
        {
            return "DM";
        }
        if (rule.getScope().getDomains() != null
                && rule.getScope().getDomains().getInclude() != null)
        {
            for (String d : rule.getScope().getDomains().getInclude())
            {
                if (d.matches("[A-Z][A-Z0-9]{1,7}") && !"ALL".equals(d))
                {
                    return d;
                }
            }
        }
        if (rule.getScope().getDataStructures() != null
                && rule.getScope().getDataStructures().getInclude() != null)
        {
            for (String st : rule.getScope().getDataStructures().getInclude())
            {
                String u = st.toUpperCase(java.util.Locale.ROOT);
                if (u.contains("SUBJECT LEVEL") || "ADSL".equals(u))
                {
                    return "ADSL";
                }
                if (u.contains("BASIC DATA") || "BDS".equals(u))
                {
                    return "ADLB";
                }
                if (u.contains("OCCURRENCE") || "OCCDS".equals(u))
                {
                    return "ADAE";
                }
            }
        }
        return "DM";
    }


    private static Set<String> structureMarkers(Rule rule)
    {
        return switch (tableName(rule))
        {
        case "ADLB" -> Set.of("PARAMCD", "AVAL");
        case "ADAE" -> Set.of("AETERM");
        default -> Set.of();
        };
    }


    /** The {@code --} prefix: the AP parent code for {@code APxx}, else the two-letter domain. */
    private static String variablePrefix(String name)
    {
        if (name.startsWith("AP") && name.length() == 4)
        {
            return name.substring(2);
        }
        return name.length() >= 2 ? name.substring(0, 2) : name;
    }


    private static void leaves(Expr e, Set<String> out)
    {
        if (e == null)
        {
            return;
        }
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> leaves(p, out));
        case Expr.Or o -> o.parts().forEach(p -> leaves(p, out));
        case Expr.Not n -> leaves(n.inner(), out);
        case Expr.Binary b ->
        {
            leaves(b.left(), out);
            leaves(b.right(), out);
        }
        case Expr.Call c ->
        {
            c.args().forEach(x -> leaves(x, out));
            c.kwargs().values().forEach(x -> leaves(x, out));
            if (COLUMN_NAMING_CALLS.contains(c.name()))
            {
                for (Expr x : c.args())
                {
                    if (x instanceof Expr.Lit l && l.kind() == Expr.LitKind.STRING
                            && l.value() != null)
                    {
                        out.add(String.valueOf(l.value()));
                    }
                }
            }
        }
        case Expr.Ref r ->
        {
            if (!r.name().startsWith("$"))
            {
                out.add(r.name());
            }
        }
        case Expr.Lit _ ->
                {
                }
        }
    }


    private static String flatten(String s)
    {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }


    /** Renders the whole artifact. */
    public static String render(SortedMap<String, Row> rows)
    {
        StringBuilder b = new StringBuilder();
        b.append("# Generated by ZeroRowFireCensus. DO NOT HAND-EDIT: regenerate with\n");
        b.append("#     mvn -P main -pl lib/corej-cdisc-rules -am test -Dtest=ZeroRowFireCensus"
                + " -Dcorej.zeroRowFireCensus=true -Dsurefire.failIfNoSpecifiedTests=false\n");
        b.append("# Every shipped Core.Id executed once through RuleRunner.execute(rule, table)\n");
        b.append(
                "# against a ZERO-ROW table declaring the rule's Check-leaf columns (no dataset\n");
        b.append(
                "# resolver, no Define / Library providers — absolute counts are not study-real).\n");
        b.append("# totalViolationCount == -1 proves the verdict was decided UPSTREAM of the\n");
        b.append("# row / {VAR,ROW} / grouped paths (BroadcastFold, evaluateMetadataNative);\n");
        b.append("# >= 0 proves it was decided on one of them.\n");
        b.append("# Rules: ").append(rows.size()).append('\n');
        SortedMap<String, Integer> byStatus = new TreeMap<>();
        SortedMap<String, Integer> skipCauses = new TreeMap<>();
        for (Row r : rows.values())
        {
            byStatus.merge(r.status(), 1, Integer::sum);
            if ("SKIPPED".equals(r.status()))
            {
                skipCauses.merge(skipCause(r.message()), 1, Integer::sum);
            }
        }
        b.append("# Status counts: ").append(byStatus).append('\n');
        b.append("# SKIPPED causes (rules parked before or at routing): ").append(skipCauses)
                .append('\n');
        b.append("# Columns: ").append(COLUMNS).append('\n');
        for (Map.Entry<String, Row> e : rows.entrySet())
        {
            Row r = e.getValue();
            b.append(e.getKey()).append('\t').append(r.sensitivity()).append('\t')
                    .append(r.domain()).append('\t').append(r.table()).append('\t')
                    .append(r.status()).append('\t').append(r.violations()).append('\t')
                    .append(r.totalViolationCount()).append('\t').append(r.message()).append('\n');
        }
        return b.toString();
    }


    /** The cause class of a SKIPPED message — its text up to the first name-bearing token. */
    private static String skipCause(String message)
    {
        String m = message.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("scope.data_structures"))
        {
            return "Scope.Data_Structures";
        }
        if (m.contains("requirements.variables"))
        {
            return "Requirements.Variables";
        }
        if (m.contains("library"))
        {
            return "no Library";
        }
        if (m.contains("define"))
        {
            return "no Define";
        }
        if (m.contains("dictionar"))
        {
            return "no dictionary";
        }
        return "other";
    }


    /** Rewrites the committed artifact; enabled with {@code -Dcorej.zeroRowFireCensus=true}. */
    @Test
    @EnabledIfSystemProperty(named = "corej.zeroRowFireCensus", matches = "true")
    void regenerate() throws IOException
    {
        write(artifactFile());
    }


    private static void write(Path out) throws IOException
    {
        SortedMap<String, Row> rows = census();
        Files.writeString(out, render(rows), StandardCharsets.UTF_8);
        List<String> rowPathFirers = new ArrayList<>();
        int fired = 0;
        for (Map.Entry<String, Row> e : rows.entrySet())
        {
            if (e.getValue().violations() > 0)
            {
                fired++;
                if (e.getValue().totalViolationCount() >= 0)
                {
                    rowPathFirers.add(e.getKey());
                }
            }
        }
        System.err.printf(
                "[zero-row-fire-census] %d rules, %d fire, %d decided downstream of the folds: %s%n",
                rows.size(), fired, rowPathFirers.size(), rowPathFirers);
    }


    /**
     * Rewrites the artifact.
     *
     * @param args
     *            optional single argument: the artifact path. Defaults to {@link #artifactFile()}.
     */
    public static void main(String[] args) throws IOException
    {
        write(args.length > 0 ? Path.of(args[0]) : artifactFile());
    }
}
