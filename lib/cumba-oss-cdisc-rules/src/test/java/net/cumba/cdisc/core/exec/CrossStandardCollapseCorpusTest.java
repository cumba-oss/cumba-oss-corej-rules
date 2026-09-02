package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.cumba.cdisc.core.CorpusPaths;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.expr.OperandKind;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Wave 34, lane C — {@code plans/done/PLAN-fix218-behavioural-verification.md}. Enumerates every
 * rule of every ADaM-family package that depends on a cross-standard (SDTM) dataset, classifies its
 * collapse shape (V1) and runs it twice through {@link RuleRunner} — once with an empty
 * {@code crossStandardDatasets} (the pre-{@code Fix #218} engine) and once with the derived set
 * (V2) — diffing the FINDINGS, not the statuses.
 *
 * <h2>What it asserts, and what it only reports</h2>
 * <ul>
 * <li><b>Asserted:</b> the ADaM-package half of the population changes <b>no findings</b> — every
 * such rule goes {@code EXECUTED}/0 findings → {@code SKIPPED}/0 findings, which is exactly the
 * guarantee {@link AbsentDatasetSkip}'s javadoc states. This is the half that holds, and the
 * assertion is what stops it rotting.</li>
 * <li><b>Reported only:</b> the {@code rules-core-tig-1-0.json} half, where 19 rules measurably DO
 * lose findings (see {@code AbsentDatasetSkipCrossStandardCollapseTest} for the characterisation
 * and the proof that every lost finding is an artefact of absence). ⚠ Deliberately <b>not</b>
 * ratcheted: that behaviour is under an open owner decision, and a count pinned here would turn the
 * decision into a red build rather than a choice.</li>
 * </ul>
 *
 * <p>
 * ⚠ Both runs carry the <b>same</b> {@code reportedDatasets} (the package's own {@code Fix #222}
 * coverage, derived from its rule list) so the diff is attributable to {@code Fix #218} alone.
 * Measured 2026-08-11: {@code reportedDatasets} is {@code [ADSL]} for every adamig package and
 * {@code [DM]} for the TIG package — which is why the four DM-only TIG losses are already silenced
 * by {@code Fix #222} and do not count against {@code #218}.
 * </p>
 */
class CrossStandardCollapseCorpusTest
{

    /**
     * The companion SDTM domain catalogues, union of sdtmig 3-1-2 / 3-2 / 3-3 / 3-4 as read from
     * {@code /data/cdisc.metadata.library-cache/api_mdr_sdtmig_<v>_datasets.json.gz} on 2026-08-11.
     * {@code CompanionSdtmDefaults} maps adamig 1-0/1-1/1-2/1-3 onto exactly those four.
     */
    private static final Set<String> SDTM = Set.of("AE", "AG", "BE", "BS", "CE", "CM", "CO", "CP",
            "CV", "DA", "DD", "DM", "DS", "DV", "EC", "EG", "EX", "FA", "FT", "GF", "HO", "IE",
            "IS", "LB", "MB", "MH", "MI", "MK", "ML", "MO", "MS", "NV", "OE", "OI", "PC", "PE",
            "PP", "PR", "QS", "RE", "RELREC", "RELSPEC", "RELSUB", "RP", "RS", "SC", "SE", "SM",
            "SR", "SS", "SU", "SUPPQUAL", "SV", "TA", "TD", "TE", "TI", "TM", "TR", "TS", "TU",
            "TV", "UR", "VS");

    /** Value cycles probed per rule; the first that makes the CONTROL run fire is the witness. */
    private static final String[][] CYCLES = new String[][]
    {
            {
                    "X"
            },
            {
                    ""
            },
            {
                    "2020-01-01"
            },
            {
                    "1"
            },
            {
                    "Y"
            },
            {
                    "X", ""
            },
            {
                    "2020-01-01", ""
            },
            {
                    "DEATH"
            },
            {
                    "FATAL"
            },
            {
                    "MULTIPLE"
            }
    };

    private static final String[] TABLE_NAMES =
    {
            "ADSL", "ADAE", "ADVS"
    };

    /**
     * Structure-signature columns injected per probe table so the {@code Scope.Data_Structures} /
     * {@code Scope.Subclasses} gate in {@link RuleRunner} lets the rule through — without them the
     * OCCDS-scoped ADaM rules ({@code CDISC-AD0640}-{@code AD0646}) report SKIPPED for structure
     * and the probe never reaches their Check at all.
     */
    private static final Map<String, List<String>> STRUCTURE_COLUMNS = Map.of("ADSL",
            List.of("USUBJID"), "ADAE", List.of("AEDECOD", "AETERM"), "ADVS",
            List.of("PARAMCD", "AVAL"));

    @Test
    void noAdamPackageRuleLosesAFindingToTheCrossStandardArm() throws Exception
    {
        Path rulesDir = CorpusPaths.rules();
        List<Path> pkgs;
        try (Stream<Path> files = Files.list(rulesDir))
        {
            pkgs = files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> p.getFileName().toString().contains("adamig")
                            || p.getFileName().toString().contains("tig-"))
                    .sorted().toList();
        }
        Map<String, Rule> byId = new TreeMap<>();
        Map<String, String> pkgOf = new LinkedHashMap<>();
        Map<String, Set<String>> reportedOfPkg = new LinkedHashMap<>();
        for (Path p : pkgs)
        {
            RulePackage pkg = RulePackageLoader.load(p);
            String file = p.getFileName().toString();
            reportedOfPkg.put(file, AbsentDatasetSkip.reportedDatasets(pkg.getRules().values()));
            for (Map.Entry<String, Rule> e : pkg.getRules().entrySet())
            {
                String id = e.getValue().effectiveId() == null ? e.getKey()
                        : e.getValue().effectiveId();
                if (byId.putIfAbsent(id, e.getValue()) == null)
                {
                    pkgOf.put(id, file);
                }
            }
        }
        System.out.println("### packages: " + pkgs.size() + ", distinct rule ids: " + byId.size());
        reportedOfPkg
                .forEach((f, r) -> System.out.println("### reportedDatasets[" + f + "] = " + r));

        System.out.println("### V1/V2 TABLE");
        System.out.println(String.join("\t", "rule", "pkg", "datasets", "class", "decider",
                "collapses", "ctlStatus", "ctlFindings", "newStatus", "newFindings", "witness",
                "domainScope", "expr"));
        int population = 0;
        List<String> lost = new ArrayList<>();
        List<String> adamLosses = new ArrayList<>();
        for (Map.Entry<String, Rule> e : byId.entrySet())
        {
            Rule rule = e.getValue();
            Expr check = rule.getCheckExpr();
            if (check == null || rule.getLoadError() != null)
            {
                continue;
            }
            Set<String> read = new LinkedHashSet<>();
            for (String d : AbsentDatasetSkip.referencedDatasets(rule, check))
            {
                if (SDTM.contains(d) && AbsentDatasetSkip.readsAny(check, rule, Set.of(d)))
                {
                    read.add(d);
                }
            }
            if (read.isEmpty())
            {
                continue;
            }
            population++;
            boolean collapses = isFalse(AbsentDatasetSkip.suppress(check, rule, read));
            Guard guard = classify(check, read);

            // ⚠ The control must carry the SAME Fix #222 coverage as the treated run, or the two
            // runs differ in two variables at once and #222's flood-suppression is attributed to
            // #218. `reported` is the run's own package coverage, derived from its rule list.
            Set<String> reported = reportedOfPkg.getOrDefault(pkgOf.get(e.getKey()), Set.of());
            Probe best = probe(rule, check, read, reported);
            String cls = guard.label();
            if (best.ctlFindings > 0 && best.newFindings == 0)
            {
                String entry = e.getKey() + " " + read + " ctl=" + best.ctlFindings + " new="
                        + best.newStatus;
                lost.add(entry);
                if (pkgOf.get(e.getKey()).contains("adamig"))
                {
                    adamLosses.add(entry);
                }
            }
            System.out.println(String.join("\t", e.getKey(), pkgOf.get(e.getKey()), read.toString(),
                    cls, guard.decider(), String.valueOf(collapses), best.ctlStatus,
                    String.valueOf(best.ctlFindings), best.newStatus,
                    String.valueOf(best.newFindings), best.witness, scopeOf(rule), exprOf(rule)));
        }
        System.out.println("### population = " + population);
        System.out.println("### FINDING-LOSS candidates = " + lost.size());
        lost.forEach(l -> System.out.println("### LOST " + l));

        assertFalse(population < 20, "the population collapsed to " + population
                + " — the corpus was not read, so " + "every assertion below is vacuous");
        assertEquals(List.of(), adamLosses,
                "no ADaM-package rule may lose a finding to the cross-standard arm — that is the "
                        + "half of Fix #218's guarantee that holds, verified by run");
    }

    // ------------------------------------------------------------------------------- V1 classify

    private record Guard(String label, String decider)
    {
    }

    /**
     * Guarded ⇔ every cross-standard dataset the Check reads is named by a positive
     * {@code exists}/{@code var_exists} call sitting DIRECTLY in the top-level {@code and}.
     */
    private static Guard classify(Expr check, Set<String> read)
    {
        List<Expr> top = check instanceof Expr.And and ? and.parts() : List.of(check);
        Map<String, String> guardOf = new LinkedHashMap<>();
        for (Expr part : top)
        {
            if (part instanceof Expr.Call call
                    && Set.of("var_exists", "ds_exists").contains(call.name())
                    && !call.args().isEmpty())
            {
                String name = nameOf(call.args().get(0));
                String qual = qualifier(name);
                if (qual != null && read.contains(qual))
                {
                    guardOf.put(qual, call.name() + "(" + name + ")@top");
                }
            }
        }
        long guarded = read.stream().filter(guardOf::containsKey).count();
        if (guarded == read.size())
        {
            return new Guard("guarded", String.join(",", guardOf.values()));
        }
        if (guarded == 0)
        {
            return new Guard("unguarded", innerGuard(check, read));
        }
        return new Guard("mixed", String.join(",", guardOf.values()) + " | missing "
                + read.stream().filter(d -> !guardOf.containsKey(d)).toList());
    }


    /**
     * Reports an exists-family call that exists but is NOT in the top-level and — an inert guard.
     */
    private static String innerGuard(Expr expr, Set<String> read)
    {
        List<String> found = new ArrayList<>();
        collectGuards(expr, read, found);
        return found.isEmpty() ? "(no exists-family call on the dataset at all)"
                : "INERT: " + found;
    }


    private static void collectGuards(Expr expr, Set<String> read, List<String> out)
    {
        switch (expr)
        {
        case Expr.Call call ->
        {
            if (Set.of("var_exists", "ds_exists").contains(call.name()) && !call.args().isEmpty())
            {
                String qual = qualifier(nameOf(call.args().get(0)));
                if (qual != null && read.contains(qual))
                {
                    out.add(call.name() + "(" + nameOf(call.args().get(0)) + ")");
                }
            }
            call.args().forEach(a -> collectGuards(a, read, out));
            call.kwargs().values().forEach(a -> collectGuards(a, read, out));
        }
        case Expr.And and -> and.parts().forEach(p -> collectGuards(p, read, out));
        case Expr.Or or -> or.parts().forEach(p -> collectGuards(p, read, out));
        case Expr.Not not -> collectGuards(not.inner(), read, out);
        case Expr.Binary b ->
        {
            collectGuards(b.left(), read, out);
            collectGuards(b.right(), read, out);
        }
        default ->
        {
            // leaf
        }
        }
    }

    // ----------------------------------------------------------------------------------- V2 run

    private record Probe(String ctlStatus, int ctlFindings, String newStatus, int newFindings,
            String witness)
    {
    }

    private static Probe probe(Rule rule, Expr check, Set<String> read, Set<String> reported)
    {
        List<String> cols = new ArrayList<>();
        for (String table : TABLE_NAMES)
        {
            cols = columnsOf(check, table, STRUCTURE_COLUMNS.get(table));
            if (cols.isEmpty())
            {
                cols = List.of("USUBJID");
            }
            Probe fallback = null;
            for (String[] cycle : CYCLES)
            {
                Probe p = runPair(rule, table, cols, cycle, read, reported);
                if (p == null)
                {
                    continue;
                }
                if (p.ctlFindings > 0)
                {
                    return p;
                }
                if (fallback == null || !"SKIPPED".equals(p.ctlStatus))
                {
                    fallback = p;
                }
            }
            if (fallback != null && !"SKIPPED".equals(fallback.ctlStatus))
            {
                return fallback;
            }
        }
        Probe p = runPair(rule, TABLE_NAMES[0], cols, CYCLES[0], read, reported);
        return p == null ? new Probe("ERROR", 0, "ERROR", 0, "-") : p;
    }


    private static Probe runPair(Rule rule, String tableName, List<String> cols, String[] cycle,
            Set<String> read, Set<String> reported)
    {
        try
        {
            IDataTable t1 = table(tableName, cols, cycle);
            IDataTable t2 = table(tableName, cols, cycle);
            RuleExecutionResult ctl = RuleRunner.execute(rule, t1, _ -> null, tableName, null, null,
                    null, Integer.MAX_VALUE, null, null, null, reported, Set.of());
            RuleExecutionResult now = RuleRunner.execute(rule, t2, _ -> null, tableName, null, null,
                    null, Integer.MAX_VALUE, null, null, null, reported, read);
            return new Probe(String.valueOf(ctl.getStatus()), ctl.getViolations().size(),
                    String.valueOf(now.getStatus()), now.getViolations().size(),
                    tableName + ":" + String.join("|", cycle));
        }
        catch (RuntimeException ex)
        {
            return new Probe("EXC:" + ex.getClass().getSimpleName(), 0,
                    "EXC:" + ex.getClass().getSimpleName(), 0, tableName);
        }
    }


    private static IDataTable table(String name, List<String> cols, String[] cycle)
    {
        MockTable mt = MockTable.of().name(name).caseInsensitiveColumnNames();
        int c = 0;
        for (String col : cols)
        {
            String[] vals = new String[]
            {
                    cycle[c % cycle.length], cycle[(c + 1) % cycle.length]
            };
            mt.col(col, vals);
            c++;
        }
        return mt.build();
    }


    /**
     * Local (unqualified) columns the Check reads, plus {@code USUBJID} and the structure columns.
     * ⚠ It does <b>not</b> read the rule's variable requirement — under either spelling. Recorded
     * because {@code plans/PLAN-scope-requirements-split.md} §5 phase 5 listed this method as a
     * {@code Scope.Variables} consumer; it never was one.
     */
    private static List<String> columnsOf(Expr check, String prefix, List<String> structure)
    {
        Set<String> out = new LinkedHashSet<>();
        out.add("USUBJID");
        out.addAll(structure);
        collectCols(check, prefix, out);
        return List.copyOf(out);
    }


    private static void collectCols(Expr expr, String prefix, Set<String> out)
    {
        switch (expr)
        {
        case Expr.Ref ref ->
        {
            if (ref.kind() == OperandKind.COLUMN)
            {
                out.add(ref.name());
            }
            else if (ref.kind() == OperandKind.WILDCARD_COLUMN)
            {
                out.add(ref.name().replace("--", prefix).replace("**", prefix));
            }
        }
        case Expr.Not not -> collectCols(not.inner(), prefix, out);
        case Expr.And and -> and.parts().forEach(p -> collectCols(p, prefix, out));
        case Expr.Or or -> or.parts().forEach(p -> collectCols(p, prefix, out));
        case Expr.Binary b ->
        {
            collectCols(b.left(), prefix, out);
            collectCols(b.right(), prefix, out);
        }
        case Expr.Call call ->
        {
            if (!call.args().isEmpty()
                    && Set.of("var_exists", "var_not_exists", "var_is_null").contains(call.name()))
            {
                String n = nameOf(call.args().get(0));
                if (n != null && n.indexOf('.') < 0 && !n.startsWith("$"))
                {
                    out.add(n.replace("--", prefix).replace("**", prefix));
                }
            }
            call.args().forEach(a -> collectCols(a, prefix, out));
            call.kwargs().values().forEach(a -> collectCols(a, prefix, out));
        }
        case Expr.Lit lit ->
        {
            if (lit.value() instanceof List<?> els)
            {
                els.forEach(x ->
                {
                    if (x instanceof Expr nested)
                    {
                        collectCols(nested, prefix, out);
                    }
                });
            }
        }
        }
    }

    // --------------------------------------------------------------------------------- helpers


    private static boolean isFalse(Expr expr)
    {
        return expr instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.BOOL
                && Boolean.FALSE.equals(lit.value());
    }


    private static String nameOf(Expr arg)
    {
        return switch (arg)
        {
        case Expr.Ref ref -> ref.name();
        case Expr.Lit lit when lit.kind() == Expr.LitKind.STRING -> String.valueOf(lit.value());
        default -> null;
        };
    }


    private static String qualifier(String name)
    {
        if (name == null || name.startsWith("$"))
        {
            return null;
        }
        int dot = name.indexOf('.');
        return dot <= 0 ? null : name.substring(0, dot).toUpperCase(Locale.ROOT);
    }


    private static String scopeOf(Rule rule)
    {
        return rule.getScope() == null ? "-" : String.valueOf(rule.getScope().getDomains());
    }


    private static String exprOf(Rule rule)
    {
        String s = rule.getCheck() == null ? "?" : String.valueOf(rule.getCheckExpr());
        return s.replace('\t', ' ').replace('\n', ' ');
    }

}
