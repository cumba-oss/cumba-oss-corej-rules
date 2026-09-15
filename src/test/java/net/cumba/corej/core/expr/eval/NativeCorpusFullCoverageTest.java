package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.NativeExecutionRecorder;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.testkit.SyntheticDataTable;
import org.junit.jupiter.api.Test;

/**
 * The corpus-wide EVERY-RULE-IS-NATIVE gate. The legacy engine is gone: a rule with no native form
 * is an {@code ERROR} at execution, so this is a hard requirement rather than a migration tracker.
 *
 * <p>
 * <b>Static gate:</b> every rule in every shipped {@code rules/rules-*.json} package must be (a)
 * native-dispatched (non-null {@code checkExpr}), (b) a wildcard/GEN TEMPLATE — never executed
 * directly; its per-dataset expansions receive a {@code checkExpr} at the generation seam (P5) — or
 * (c) a {@code loadError} (it never evaluates). Tier-B metadata operands
 * ({@code define_variable_ccode}, {@code dataset_metadata}; deliberately unmapped, see
 * {@code MetadataOperandMapping}) and user-authored additions with unconventional shapes — which
 * may only SHRINK.
 * </p>
 *
 * <p>
 * <b>Dynamic gate:</b> every native-dispatched {@code RECORD_DATA} rule with plain-column operands
 * is EXECUTED through {@link RuleRunner} over a synthetic table with the recorder on — none may
 * report {@code LEGACY} or {@code MIXED}.
 * </p>
 */
class NativeCorpusFullCoverageTest
{

    @Test
    void everyShippedRuleIsNativeTemplateOrLoadError() throws Exception
    {
        Path rulesDir = CorpusPaths.rules();
        List<String> unexplained = new ArrayList<>();
        try (Stream<Path> files = Files.list(rulesDir))
        {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.toString().endsWith(".json")).sorted().toList())
            {
                RulePackage pkg = RulePackageLoader.load(file);
                for (Rule rule : pkg.getRules().values())
                {
                    if (rule == null || rule.getCheckExpr() != null || rule.getLoadError() != null
                            || rule.getCheck() == null)
                    {
                        continue; // native, load-error, or no Check (never evaluates)
                    }
                    if (WildcardExpander.containsWildcards(rule))
                    {
                        continue; // template — expansions are nativized at the generation seam
                    }
                    String id = rule.getCore() != null && rule.getCore().getId() != null
                            ? rule.getCore().getId()
                            : rule.getId();
                    unexplained.add(file.getFileName() + "/" + id);
                }
            }
        }
        assertTrue(unexplained.isEmpty(),
                "EVERY-RULE-IS-NATIVE gate: rule(s) have no native form and would be reported as "
                        + "an ERROR at execution (" + unexplained.size() + "):\n"
                        + String.join("\n", unexplained));
    }


    @Test
    void nativeDispatchedRecordDataRulesNeverRecordLegacy() throws Exception
    {
        // Execute every plain-operand RECORD_DATA rule with a checkExpr end-to-end through
        // RuleRunner over a synthetic table; the recorder must never report LEGACY or MIXED.
        Path rulesDir = CorpusPaths.rules();
        List<String> legacyRuns = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        int executed = 0;
        try (Stream<Path> files = Files.list(rulesDir))
        {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.toString().endsWith(".json")).sorted().toList())
            {
                RulePackage pkg = RulePackageLoader.load(file);
                for (Rule rule : pkg.getRules().values())
                {
                    // row-data rules: no VAR cursor (the former Record Data population)
                    if (rule == null || rule.getCheckExpr() == null
                            || rule.getEvaluationDomain() == null
                            || rule.getEvaluationDomain().varCursor())
                    {
                        continue;
                    }
                    Set<String> cols = new LinkedHashSet<>();
                    Scan.columns(rule.getCheckExpr(), cols);
                    if (cols.isEmpty())
                    {
                        cols.add("X");
                    }
                    NativeExecutionRecorder.enable();
                    try
                    {
                        if (!runAgainstATableTheRuleCanRead(rule, cols))
                        {
                            unreadable.add(file.getFileName() + "/" + ruleIdOf(rule));
                        }
                        executed++;
                    }
                    catch (RuntimeException _)
                    {
                        // A synthetic-context error (missing operation/provider) is not a legacy
                        // run; the recorder map below stays authoritative.
                    }
                    Map<String, NativeExecutionRecorder.Backend> rec = NativeExecutionRecorder
                            .disable();
                    for (Map.Entry<String, NativeExecutionRecorder.Backend> e : rec.entrySet())
                    {
                        if (e.getValue() != NativeExecutionRecorder.Backend.NATIVE)
                        {
                            legacyRuns.add(
                                    file.getFileName() + "/" + e.getKey() + " -> " + e.getValue());
                        }
                    }
                }
            }
        }
        assertTrue(executed > 1500, "expected a substantial executed count, got " + executed);
        // ⛔ A rule that cannot be given a readable table never reaches NativeExecutionRecorder,
        // so legacyRuns below would not cover it — and an emptiness assertion is most comfortable
        // when nothing was observed. Pinned explicitly so that erosion cannot be silent again.
        assertTrue(unreadable.isEmpty(),
                "rule(s) could not be handed a table whose column types they can read, so their "
                        + "dispatch was never observed (" + unreadable.size() + "):\n"
                        + String.join("\n", unreadable.stream().limit(20).toList()));
        assertTrue(legacyRuns.isEmpty(),
                "native-dispatched rules recorded a LEGACY/MIXED execution (" + legacyRuns.size()
                        + "):\n" + String.join("\n", legacyRuns.stream().limit(40).toList()));
    }


    @Test
    void nativeDispatchedMetadataFamilyRulesNeverRecordLegacy() throws Exception
    {
        // Guard-residual P4 (PLAN-native-runtime-guard-residual): widen the dynamic recorder gate
        // beyond RECORD_DATA — every metadata / value-check / presence-family rule with a
        // checkExpr is EXECUTED through RuleRunner over a synthetic table with simple library /
        // define provider stubs; none may report LEGACY or MIXED. With the D5 recorder wiring
        // complete (Step-1 fold, Step-3/4 cascade, precondition fold), the absence of a
        // LEGACY/MIXED entry PROVES native verdict production for these families too.
        Path rulesDir = CorpusPaths.rules();
        net.cumba.corej.core.exec.MetadataProvider stub = stubProvider();
        List<String> legacyRuns = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        int executed = 0;
        try (Stream<Path> files = Files.list(rulesDir))
        {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.toString().endsWith(".json")).sorted().toList())
            {
                RulePackage pkg = RulePackageLoader.load(file);
                for (Rule rule : pkg.getRules().values())
                {
                    // per-variable rules: a VAR cursor (the former metadata-check population)
                    if (rule == null || rule.getCheckExpr() == null
                            || rule.getEvaluationDomain() == null
                            || !rule.getEvaluationDomain().varCursor())
                    {
                        continue;
                    }
                    Set<String> cols = new LinkedHashSet<>();
                    Scan.columns(rule.getCheckExpr(), cols);
                    if (cols.isEmpty())
                    {
                        cols.add("STUDYID");
                    }
                    NativeExecutionRecorder.enable();
                    try
                    {
                        if (!runAgainstATableTheRuleCanRead(rule, cols, stub))
                        {
                            unreadable.add(file.getFileName() + "/" + ruleIdOf(rule));
                        }
                        executed++;
                    }
                    catch (RuntimeException _)
                    {
                        // A synthetic-context error is not a legacy run; the recorder map below
                        // stays authoritative.
                    }
                    Map<String, NativeExecutionRecorder.Backend> rec = NativeExecutionRecorder
                            .disable();
                    for (Map.Entry<String, NativeExecutionRecorder.Backend> e : rec.entrySet())
                    {
                        if (e.getValue() != NativeExecutionRecorder.Backend.NATIVE)
                        {
                            legacyRuns.add(
                                    file.getFileName() + "/" + e.getKey() + " -> " + e.getValue());
                        }
                    }
                }
            }
        }
        assertTrue(executed > 700, "expected a substantial executed count, got " + executed);
        // ⛔ A rule that cannot be given a readable table never reaches NativeExecutionRecorder,
        // so legacyRuns below would not cover it — and an emptiness assertion is most comfortable
        // when nothing was observed. Pinned explicitly so that erosion cannot be silent again.
        assertTrue(unreadable.isEmpty(),
                "rule(s) could not be handed a table whose column types they can read, so their "
                        + "dispatch was never observed (" + unreadable.size() + "):\n"
                        + String.join("\n", unreadable.stream().limit(20).toList()));
        assertTrue(legacyRuns.isEmpty(),
                "metadata-family rules recorded a LEGACY/MIXED execution (" + legacyRuns.size()
                        + "):\n" + String.join("\n", legacyRuns.stream().limit(40).toList()));
    }


    /** Minimal provider stub so library_* / define_* rules execute instead of SKIPPING. */
    private static net.cumba.corej.core.exec.MetadataProvider stubProvider()
    {
        return new net.cumba.corej.core.exec.MetadataProvider()
        {

            @Override
            public List<String> getRequiredVariables(String aDomain)
            {
                return List.of("STUDYID");
            }


            @Override
            public List<String> getExpectedVariables(String aDomain)
            {
                return List.of("STUDYID");
            }


            @Override
            public List<String> getColumnOrder(String aDomain)
            {
                return List.of("STUDYID");
            }


            @Override
            public List<String> getModelColumnOrder(String aDomain)
            {
                return List.of("STUDYID");
            }


            @Override
            public boolean isDomainCustom(String aDomain)
            {
                return false;
            }


            @Override
            public List<String> getCodelistTerms(String aCodelistCode)
            {
                return List.of();
            }


            @Override
            public Map<String, String> getVariableMetadata(String aDomain, String aVariable)
            {
                return Map.of("name", aVariable, "label", "Label", "simpleDatatype", "Char", "role",
                        "Identifier", "core", "Req");
            }


            @Override
            public List<Map<String, String>> getDomainVariables(String aDomain)
            {
                return List.of(getVariableMetadata("DM", "STUDYID"));
            }


            @Override
            public Map<String, String> getDatasetMetadata(String aDomain)
            {
                return Map.of("name", "DM", "label", "Demographics", "className",
                        "SPECIAL PURPOSE");
            }


            @Override
            public java.util.Optional<Boolean> isCodelistExtensible(String aCodelistName)
            {
                return java.util.Optional.of(true);
            }


            @Override
            public Map<String, String> getCodelistTermMappings(String aCodelistName)
            {
                return Map.of();
            }


            @Override
            public String getStandard()
            {
                return "sdtmig";
            }


            @Override
            public String getVersion()
            {
                return "3-4";
            }
        };
    }

    /** Minimal column scan over the Expr tree (mirrors the corpus-parity harness). */
    private static final class Scan
    {

        static void columns(net.cumba.corej.core.expr.ast.Expr e, Set<String> out)
        {
            switch (e)
            {
            case net.cumba.corej.core.expr.ast.Expr.And a -> a.parts()
                    .forEach(p -> columns(p, out));
            case net.cumba.corej.core.expr.ast.Expr.Or o -> o.parts().forEach(p -> columns(p, out));
            case net.cumba.corej.core.expr.ast.Expr.Not n -> columns(n.inner(), out);
            case net.cumba.corej.core.expr.ast.Expr.Binary b ->
            {
                columns(b.left(), out);
                columns(b.right(), out);
            }
            case net.cumba.corej.core.expr.ast.Expr.Call c ->
            {
                c.args().forEach(p -> columns(p, out));
                c.kwargs().values().forEach(p -> columns(p, out));
            }
            case net.cumba.corej.core.expr.ast.Expr.Ref r ->
            {
                if (r.kind() == net.cumba.corej.core.expr.OperandKind.COLUMN
                        && !r.name().contains("."))
                {
                    out.add(r.name());
                }
            }
            case net.cumba.corej.core.expr.ast.Expr.Lit lit ->
            {
                if (lit.kind() == net.cumba.corej.core.expr.ast.Expr.LitKind.LIST)
                {
                    @SuppressWarnings("unchecked")
                    List<net.cumba.corej.core.expr.ast.Expr> items = (List<net.cumba.corej.core.expr.ast.Expr>) lit
                            .value();
                    items.forEach(p -> columns(p, out));
                }
            }
            }
        }


        private Scan()
        {
        }

    }

    /**
     * The rule's reportable id — {@code Core.Id} where present, else the internal id.
     *
     * @param aRule
     *            the rule.
     * @return the id for a failure message.
     */
    private static String ruleIdOf(Rule aRule)
    {
        return aRule.getCore() != null && aRule.getCore().getId() != null ? aRule.getCore().getId()
                : String.valueOf(aRule.getId());
    }

    /**
     * The column-type gate's message prefix. Pinned engine-side by
     * {@code EngineErrorMessageContractTest} at its three builder sites, which is what makes
     * matching on it a contract rather than a guess — the same basis {@code ViolationNormaliser}
     * uses to classify this ERROR.
     */
    private static final String TYPE_MISMATCH = "column-type mismatch: ";

    /**
     * Executes {@code rule} against a synthetic table whose column TYPES the rule can actually
     * read, retrying with one more column declared numeric each time the column-type gate rejects
     * the table.
     *
     * <p>
     * ⚠⚠ Why this exists. The table used to declare <b>every</b> column {@code STRING}. Once
     * {@code PLAN-column-type-conformance}'s gate landed, 78 correctly-authored rules — reading
     * {@code AGE}, {@code EXDOSE}, {@code PCLLOQ}, {@code ASTDT} and friends — errored on the
     * fixture rather than executing. ⛔ And the damage was <em>not</em> to the {@code executed}
     * count, which is incremented for any call that does not throw and so never noticed:
     * {@code RuleRunner} returns the mismatch as an ERROR result. The damage was to
     * {@link NativeExecutionRecorder}, which records nothing for a rule that never dispatches — so
     * this class's real assertion, {@code legacyRuns.isEmpty()}, silently stopped covering those 78
     * rules. An emptiness assertion passes most comfortably when nothing was observed at all.
     * </p>
     * <p>
     * Declaring a column numeric here is not a claim about the standard — it is the harness
     * adapting its fixture to the rule under test, which is the right behaviour for a
     * <em>dispatch</em> harness. Type correctness is asserted by the {@code .cdt} corpus scenarios,
     * against real declared types.
     * </p>
     *
     * @param aRule
     *            the rule to execute.
     * @param aColumns
     *            the columns its Check reads.
     */
    private static boolean runAgainstATableTheRuleCanRead(Rule aRule, Set<String> aColumns)
    {
        return runAgainstATableTheRuleCanRead(aRule, aColumns, null);
    }


    /**
     * As above, with a metadata provider for the {@code library_*} / {@code define_*} rules.
     *
     * @param aRule
     *            the rule to execute.
     * @param aColumns
     *            the columns its Check reads.
     * @param aProvider
     *            the metadata provider stub, or {@code null}.
     */
    private static boolean runAgainstATableTheRuleCanRead(Rule aRule, Set<String> aColumns,
            net.cumba.corej.core.exec.@org.jspecify.annotations.Nullable MetadataProvider aProvider)
    {
        Set<String> numeric = new LinkedHashSet<>();
        // Bounded by the column count: each retry declares one more column numeric, so the loop
        // cannot outlive the columns it has to convert.
        for (int attempt = 0; attempt <= aColumns.size(); attempt++)
        {
            SyntheticDataTable table = SyntheticDataTable.withNumeric("DM", List.copyOf(aColumns),
                    numeric, 5);
            RuleExecutionResult res = RuleRunner.execute(aRule, table, _ -> null, "DM", aProvider,
                    null, aProvider);
            String msg = res.getStatusMessage();
            if (!res.isError() || msg == null || !msg.startsWith(TYPE_MISMATCH))
            {
                return true;
            }
            String offender = msg.substring(TYPE_MISMATCH.length()).split(" ", 2)[0];
            if (!numeric.add(offender))
            {
                return false; // already flipped and still rejected — not fixable here
            }
        }
        return false;
    }
}
