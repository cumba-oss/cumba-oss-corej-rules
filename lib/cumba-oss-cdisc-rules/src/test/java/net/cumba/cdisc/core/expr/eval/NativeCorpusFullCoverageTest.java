package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.cumba.cdisc.core.CorpusPaths;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.NativeExecutionRecorder;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.gen.WildcardExpander;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
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
                    SyntheticStringTable table = new SyntheticStringTable("DM", List.copyOf(cols),
                            new String[]
                            {
                                    "A", "", "1", "2024-01-01", "Y"
                            }, 5);
                    NativeExecutionRecorder.enable();
                    try
                    {
                        RuleRunner.execute(rule, table, _ -> null, "DM", null, null, null);
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
        net.cumba.cdisc.core.exec.MetadataProvider stub = stubProvider();
        List<String> legacyRuns = new ArrayList<>();
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
                    SyntheticStringTable table = new SyntheticStringTable("DM", List.copyOf(cols),
                            new String[]
                            {
                                    "A", "", "1", "2024-01-01", "Y"
                            }, 5);
                    NativeExecutionRecorder.enable();
                    try
                    {
                        RuleRunner.execute(rule, table, _ -> null, "DM", stub, null, stub);
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
        assertTrue(legacyRuns.isEmpty(),
                "metadata-family rules recorded a LEGACY/MIXED execution (" + legacyRuns.size()
                        + "):\n" + String.join("\n", legacyRuns.stream().limit(40).toList()));
    }


    /** Minimal provider stub so library_* / define_* rules execute instead of SKIPPING. */
    private static net.cumba.cdisc.core.exec.MetadataProvider stubProvider()
    {
        return new net.cumba.cdisc.core.exec.MetadataProvider()
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
            public boolean isCodelistExtensible(String aCodelistName)
            {
                return true;
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

        static void columns(net.cumba.cdisc.core.expr.ast.Expr e, Set<String> out)
        {
            switch (e)
            {
            case net.cumba.cdisc.core.expr.ast.Expr.And a -> a.parts()
                    .forEach(p -> columns(p, out));
            case net.cumba.cdisc.core.expr.ast.Expr.Or o -> o.parts().forEach(p -> columns(p, out));
            case net.cumba.cdisc.core.expr.ast.Expr.Not n -> columns(n.inner(), out);
            case net.cumba.cdisc.core.expr.ast.Expr.Binary b ->
            {
                columns(b.left(), out);
                columns(b.right(), out);
            }
            case net.cumba.cdisc.core.expr.ast.Expr.Call c ->
            {
                c.args().forEach(p -> columns(p, out));
                c.kwargs().values().forEach(p -> columns(p, out));
            }
            case net.cumba.cdisc.core.expr.ast.Expr.Ref r ->
            {
                if (r.kind() == net.cumba.cdisc.core.expr.OperandKind.COLUMN
                        && !r.name().contains("."))
                {
                    out.add(r.name());
                }
            }
            case net.cumba.cdisc.core.expr.ast.Expr.Lit lit ->
            {
                if (lit.kind() == net.cumba.cdisc.core.expr.ast.Expr.LitKind.LIST)
                {
                    @SuppressWarnings("unchecked")
                    List<net.cumba.cdisc.core.expr.ast.Expr> items = (List<net.cumba.cdisc.core.expr.ast.Expr>) lit
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

}
