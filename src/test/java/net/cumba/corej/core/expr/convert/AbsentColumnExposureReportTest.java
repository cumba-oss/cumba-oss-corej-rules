package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.VariableRequirement;
import org.junit.jupiter.api.Test;

/**
 * Census driver for {@link AbsentColumnExposureScan} — the in-repo replacement for the scratch
 * driver that produced the {@code 235 rules / 265 leaves} snapshot recorded in
 * {@code plans/done/PLAN-ec43-corpus-response.md} §2.
 *
 * <p>
 * The scanner itself decides nothing and has no entry point; without a committed driver the
 * snapshot cannot be re-measured, and the corpus moves under it (Fix #139, #142, #143 all landed
 * after the snapshot was taken).
 * </p>
 *
 * <p>
 * <b>The census measures {@code rules/} — Java's residual exposure — and nothing else.</b> That is
 * the corpus the engine executes: {@code RuleExprGenerator} output, additionally carrying the
 * {@code var_exists(...)} terms {@code NonEmptyGuardInliner} injects.
 * </p>
 *
 * <p>
 * ⚠ <b>A second corpus used to be scanned beside it, and the difference was the point.</b> The
 * authored Check surface answered a different question — <em>"should this rule be guarded?"</em> —
 * against Java's <em>"is it still exposed after the injector ran?"</em>, and the gap between the
 * two numbers was the measure of what the injector was doing. Only the second question is answered
 * now. Two things follow. (1) The injector's effect is no longer visible as a delta here; its own
 * coverage lived in {@code NonEmptyGuardInlinerTest} (deleted with the injector, 2026-08-26). (2)
 * The two censuses agreeing on the scan machinery was a free cross-check on the walk — so the
 * floors in {@link #writeExposureReport} (rules scanned, zero unconvertible Checks, a non-empty
 * leaf set) are now the whole of the anti-vacuity argument and must not be relaxed.
 * </p>
 *
 * <p>
 * Guard shape (e) is supplied as empty, and that is correct rather than a simplification: every
 * {@code Precondition} in {@code rules/} is an availability gate ({@code dictionary_available(...)}
 * / {@code library_available()} / {@code available(...)}) — none guards a column's presence.
 * </p>
 *
 * <p>
 * <b>Report-only.</b> The census is a measurement, not a contract: the corpus legitimately gains
 * and loses exposed leaves as rules are authored, and EC-43's resolved default is that an exposed
 * leaf <em>fires</em>. Only the sanity floor is asserted — the corpus was read, every Check
 * converted, and the scan found something — so a silent path/glob/conversion breakage cannot
 * masquerade as "no exposure".
 * </p>
 */
class AbsentColumnExposureReportTest
{

    /**
     * Java's executed corpus. Kept as a list — the census emits a {@code corpus} column and the
     * report format is unchanged — but there is only one corpus left to scan.
     */
    private static final List<Path> CORPORA = List.of(CorpusPaths.rules());

    private static final Path REPORT = Path.of("target", "absent-column-exposure-report.tsv");

    private record Census(List<String> rows, Set<String> rules, Set<String> leaves,
            Set<String> columns, int rulesScanned, int unconvertible)
    {
    }

    /**
     * Fix #157 — the scan classifies by <em>operator name</em>, so a predicate missing from its
     * {@code BOOLEAN_PREDICATES} set is invisible to the census rather than reported as unexposed.
     * No shipped rule uses the date-portion pair yet (the corpus migration is a later effort), so
     * the census itself cannot show the registration works: this asserts it on a synthetic Check.
     *
     * <p>
     * The controls are the point. {@code is_incomplete_date} is an already-classified sibling
     * (proves the probe finds anything at all) and {@code totally_unregistered} proves the probe
     * can also come back empty — without both, a green here would mean nothing.
     * </p>
     */
    @Test
    void scanClassifiesTheDatePortionPair()
    {
        assertEquals(1, unguardedLeafCount("is_incomplete_date"), "positive control");
        assertEquals(0, unguardedLeafCount("totally_unregistered"), "negative control");
        assertEquals(1, unguardedLeafCount("is_complete_date_part"));
        assertEquals(1, unguardedLeafCount("is_not_complete_date_part"));
    }


    /** Leaves the scan collects for a single unguarded {@code <op>(TSVAL)} Check. */
    private static int unguardedLeafCount(String operator)
    {
        Expr expr = new Expr.And(List.of(CheckExpressionParser.parse("is_incomplete_date(TSVAL)")));
        // Swap the call name in place: the parser accepts any call spelling, and the point here
        // is the SCAN's name-keyed classification, not the grammar's.
        Expr probe = renameSingleCall(expr, operator);
        return AbsentColumnExposureScan.scan(probe, List.of(), List.of()).size();
    }


    /** Rewrites the one {@link Expr.Call} in {@code e} to carry {@code name}. */
    private static Expr renameSingleCall(Expr e, String name)
    {
        if (e instanceof Expr.Call c)
        {
            return new Expr.Call(name, c.args(), c.kwargs());
        }
        if (e instanceof Expr.And a)
        {
            return new Expr.And(a.parts().stream().map(p -> renameSingleCall(p, name)).toList());
        }
        throw new IllegalStateException("unexpected probe shape: " + e);
    }


    @Test
    void writeExposureReport() throws IOException
    {
        List<String> out = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        for (Path corpus : CORPORA)
        {
            Census census = scanCorpus(corpus);
            assertTrue(census.rulesScanned() > 3000, () -> "corpus scan looks broken: only "
                    + census.rulesScanned() + " rules had a Check in " + corpus);
            assertEquals(0, census.unconvertible(),
                    () -> "every Check must raise to an Expr; " + census.unconvertible()
                            + " failed in " + corpus + " — the census is silently under-reporting");
            assertTrue(!census.leaves().isEmpty(),
                    () -> "no exposed leaf found in " + corpus + " — the scan is a no-op");
            out.add("# " + corpus + ": rules scanned (package-instances) " + census.rulesScanned()
                    + "; distinct exposed rules " + census.rules().size() + "; distinct exposed "
                    + "leaves " + census.leaves().size() + "; distinct exposed columns "
                    + census.columns().size());
            rows.addAll(census.rows());
        }
        out.add("corpus\tpackage\trule\tcolumn\top\tpolarity\twrapper\tdomains");
        out.addAll(rows);
        Files.createDirectories(REPORT.getParent());
        Files.write(REPORT, out, StandardCharsets.UTF_8);
    }


    private static Census scanCorpus(Path corpus) throws IOException
    {
        Path dir = corpus;
        List<String> rows = new ArrayList<>();
        Set<String> exposedRules = new TreeSet<>();
        Set<String> exposedLeaves = new LinkedHashSet<>();
        Set<String> exposedColumns = new TreeSet<>();
        int rulesScanned = 0;
        int unconvertible = 0;

        List<Path> packages;
        try (Stream<Path> files = Files.list(dir))
        {
            packages = files.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path file : packages)
        {
            RulePackage pkg = RulePackageLoader.load(file);
            if (pkg.getRules() == null)
            {
                continue;
            }
            for (Rule rule : pkg.getRules().values())
            {
                if (rule == null || rule.getCheck() == null)
                {
                    continue;
                }
                rulesScanned++;
                Expr root;
                try
                {
                    root = net.cumba.corej.core.CheckExprUnits.toExpr(rule.getCheck());
                }
                catch (RuntimeException _)
                {
                    // Counted and asserted on, never swallowed: a Check with no expression form
                    // would silently shrink the census.
                    unconvertible++;
                    continue;
                }
                String id = String.valueOf(rule.effectiveId());
                String domains = String.join(",", scopeDomains(rule.getScope()));
                for (AbsentColumnExposureScan.Leaf leaf : AbsentColumnExposureScan.scan(root,
                        requiredVariables(rule), List.of()))
                {
                    if (leaf.guard() != AbsentColumnExposureScan.GuardShape.NONE)
                    {
                        continue;
                    }
                    exposedRules.add(id);
                    exposedColumns.add(leaf.column());
                    exposedLeaves.add(id + "|" + leaf.column() + "|" + leaf.op() + "|"
                            + leaf.positive() + "|" + leaf.wrapper());
                    rows.add(String.join("\t", corpus.getFileName().toString(),
                            file.getFileName().toString(), id, leaf.column(), leaf.op(),
                            leaf.positive() ? "positive" : "negative",
                            leaf.wrapper().isEmpty() ? "-" : leaf.wrapper(), domains));
                }
            }
        }
        return new Census(rows, exposedRules, exposedLeaves, exposedColumns, rulesScanned,
                unconvertible);
    }


    /**
     * The columns the rule's variable requirement guarantees are <b>present</b> — i.e.
     * {@code Requirements.Variables.All}, and nothing else.
     *
     * <p>
     * ⛔ A semantic re-ruling that came with {@code Requirements}
     * ({@code plans/done/PLAN-scope-requirements-split.md} &#167;5 phase 5, row 9): under the old
     * {@code Scope.Variables.Include} spelling "named in scope" implied "present", so the two were
     * the same set. They are not any more. {@code Any} is met when <em>one</em> entry is present,
     * so a named entry may well be absent; {@code None} guarantees the exact opposite. Feeding
     * either into the exposure scan would mark genuinely exposed leaves as guarded — a census that
     * shrinks without failing.
     * </p>
     */
    private static List<String> requiredVariables(Rule rule)
    {
        VariableRequirement required = rule.effectiveVariableRequirement();
        return required == null || required.getAll() == null ? List.of() : required.getAll();
    }


    private static List<String> scopeDomains(Scope scope)
    {
        if (scope == null || scope.getDomains() == null || scope.getDomains().getInclude() == null)
        {
            return List.of();
        }
        return scope.getDomains().getInclude();
    }
}
