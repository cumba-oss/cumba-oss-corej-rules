package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link AbsentTrueLeafDetector}'s own proof ({@code PLAN-requirements-doctrine} row 8): the
 * per-leaf fold agrees with the runner, the five planted controls of the plan classify as stated,
 * and the census over the shipped corpus is written to {@code target/atl-census.tsv} so its
 * population is auditable.
 *
 * <h2>Why the runner-agreement test exists</h2>
 * {@link net.cumba.corej.core.expr.eval.BroadcastFold} is written for whole Checks; the detector
 * folds ONE leaf. The plan's §9 risk *"using the fold API off its intended grain"* is answered by
 * measurement, not by argument: every shape below is run through {@link RuleRunner} as
 * {@code <leaf> and ZZROW == "a"} on a two-row table with the leaf's column absent (the rule does
 * not declare {@code Message_Folded}, so the runner evaluates the absent column per row as its type
 * default — Q6) — exactly one finding iff the leaf is absent-true — and the detector must say the
 * same. ({@code AbsentColumnTypeDefaultTest} in the engine is the fixture shape followed.)
 */
class AbsentTrueLeafDetectorTest
{

    /** A two-row table: the row sibling column only; every other column is absent. */
    private static IDataTable rows()
    {
        return MockTable.of().name("LB").col("ZZROW", "a", "b").build();
    }


    /** A synthetic Record rule through the loader, so its Check is compiled as in the corpus. */
    static Rule rule(String id, String check, String requirements, String outcomeExtra)
    {
        String json = "{\"rules\":{\"R\":{\"Core\":{\"Id\":\"" + id + "\"},"
                + "\"Sensitivity\":\"Record\"" + requirements + ",\"Check\":{\"expression\":\""
                + esc(check) + "\"},\"Outcome\":{\"Message\":\"m\"" + outcomeExtra
                + ",\"Output_Variables\":[\"USUBJID\"]}}}}";
        try
        {
            Rule r = RulePackageLoader.loadFromString(json).getRules().get("R");
            assertTrue(r != null && r.getLoadError() == null,
                    () -> check + " → " + (r == null ? "no rule" : r.getLoadError()));
            return r;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }


    /** The shipped-body twin of {@link #rule} the detector reads its gates from. */
    static JsonNode body(String check, String requirements, String outcomeExtra)
    {
        try
        {
            return new ObjectMapper().readTree("{\"Core\":{\"Id\":\"T\"}" + requirements
                    + ",\"Check\":{\"expression\":\"" + esc(check)
                    + "\"},\"Outcome\":{\"Message\":\"m\"" + outcomeExtra + "}}");
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }


    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    /**
     * The runner's own answer on an absent column, one shape at a time, against the detector's. ⚠
     * The second column is the expected answer, written down from the rulings (R-5.22, D34 #3/#4,
     * D76), so a disagreement between engine and detector cannot pass by both being wrong the same
     * way.
     */
    @ParameterizedTest(name = "{0} → absent-true {1}")
    @CsvSource(delimiter = '|', value =
    {
            "empty(LBORRES)|true", "not empty(LBORRES)|false", "LBORRES != 'X'|true",
            "LBORRES == 'X'|false", "LBSTRESN != 5|true", "LBSTRESN > 5|false",
            "LBORRES not in ['A', 'B']|true", "LBORRES in ['A', 'B']|false",
            "not is_complete_date_part(LBDTC)|true", "upper(LBORRES) != 'X'|true",
            "LBORRES !~ /^X/|true", "LBORRES =~ /^X/|false",
    })
    void theLeafFoldAgreesWithTheRunner(String leaf, boolean expected)
    {
        String check = leaf.replace('\'', '"') + " and ZZROW == \"a\"";
        Rule r = rule("T-ATL-RUN", check, "", "");
        RuleExecutionResult result = RuleRunner.execute(r, rows(), resolver(), "LB", null, null,
                null, 50, null, null, null, Set.of(), Set.of(), Severity.INFO);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + ": " + result.getStatusMessage());
        boolean runnerFires = result.getViolationCount() == 1;
        List<AbsentTrueLeafDetector.Pair> pairs = AbsentTrueLeafDetector.pairs(r,
                body(check, "", ""));
        boolean detectorSays = pairs.stream().anyMatch(p -> !p.column().equals("ZZROW"));
        assertEquals(expected, runnerFires,
                () -> check + ": the runner fired " + result.getViolationCount() + " time(s)");
        assertEquals(runnerFires, detectorSays,
                () -> check + ": runner " + runnerFires + " but detector " + pairs);
    }


    private static DatasetResolver resolver()
    {
        return name -> null;
    }


    /**
     * The plan's five planted controls (row 8), each classified as the working definition says.
     */
    @Test
    void thePlantedControlsClassifyAsDefined()
    {
        // 1. plain empty(X) on a Message_Folded rule: a live trigger -> mf-discharged
        assertPair("empty(LBORRES) and ZZROW == \"a\"", "", ",\"Message_Folded\":\"f\"", "LBORRES",
                false, true);
        // 2. plain X != "lit": never a trigger -> flood, even on a rule with the key
        assertPair("LBORRES != \"X\" and ZZROW == \"a\"", "", ",\"Message_Folded\":\"f\"",
                "LBORRES", false, false);
        // 3. dotted DS.X: never a trigger -> flood
        assertPair("empty(DM.RFSTDTC) and ZZROW == \"a\"", "", "", "DM.RFSTDTC", true, false);
        // 4. a Keep_Row_Findings column is not a trigger -> flood (its verdict is Type 1)
        assertPair("empty(LBSTAT) and empty(LBORRES) and ZZROW == \"a\"", "",
                ",\"Message_Folded\":\"f\",\"Keep_Row_Findings\":[\"LBSTAT\"]", "LBSTAT", false,
                false);
        // 6. an ADaM marker template on a rule with the key: compiled per expansion only, folded
        // through the parsed form and the stand-in -> mf-discharged (CDISC-AD0377's shape)
        assertPair("not empty(TRTxxP) and empty(TRTxxPN)", "", ",\"Message_Folded\":\"f\"",
                "TRTxxPN", false, true);
        // 5. empty(X) on a rule WITHOUT the key -> flood (CDISC-SEND-0105.1 / SPECIES shape)
        assertPair("empty(LBORRES) and ZZROW == \"a\"", "", "", "LBORRES", false, false);
        // 7. a guard in ANOTHER disjunct guards nothing (review round 1, HIGH-1: guards are per
        // leaf, among its And-ancestors only) -> flood
        assertPair("(var_exists(\"LBORRES\") and LBORRES == \"0\" or empty(LBORRES))"
                + " and ZZROW == \"a\"", "", "", "LBORRES", false, false);
    }


    /**
     * The dotted half of the runner agreement (review round 1): {@code DM} present, its
     * {@code RFSTDTC} absent, read through a declared keyed join — the runner reads the absent
     * dotted column as its type default, and the detector's stand-in fold must say the same.
     */
    @ParameterizedTest(name = "{0} → absent-true {1}")
    @CsvSource(delimiter = '|', value =
    {
            "empty(DM.RFSTDTC)|true", "not empty(DM.RFSTDTC)|false", "DM.RFSTDTC != 'X'|true",
            "DM.RFSTDTC == 'X'|false"
    })
    void aDottedLeafAgreesWithTheRunner(String leaf, boolean expected)
    {
        String check = leaf.replace('\'', '"') + " and ZZROW == \"a\"";
        String req = ",\"Requirements\":{\"Variables\":{\"All\":[\"USUBJID\"],"
                + "\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]}},"
                + "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}]";
        Rule r = rule("T-ATL-DOT", check, req, "");
        IDataTable lb = MockTable.of().name("LB").col("USUBJID", "S1", "S2").col("ZZROW", "a", "b")
                .build();
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1", "S2").build();
        DatasetResolver resolver = new DatasetResolver.WithInventory()
        {

            @Override
            public @org.jspecify.annotations.Nullable IDataTable resolve(String name)
            {
                return "DM".equalsIgnoreCase(name) ? dm : "LB".equalsIgnoreCase(name) ? lb : null;
            }


            @Override
            public Set<String> availableDatasets()
            {
                return Set.of("LB", "DM");
            }
        };
        RuleExecutionResult result = RuleRunner.execute(r, lb, resolver, "LB", null, null, null, 50,
                null, null, null, Set.of(), Set.of(), Severity.INFO);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + ": " + result.getStatusMessage());
        boolean runnerFires = result.getViolationCount() == 1;
        boolean detectorSays = AbsentTrueLeafDetector.pairs(r, body(check, req, "")).stream()
                .anyMatch(p -> "DM.RFSTDTC".equals(p.column()));
        assertEquals(expected, runnerFires,
                () -> check + ": the runner fired " + result.getViolationCount() + " time(s)");
        assertEquals(runnerFires, detectorSays, check);
    }


    /** The exclusions: a gate, a {@code var_exists} guard, the {@code not empty(X)} opt-out. */
    @Test
    void gatedAndGuardedPairsAreMarked()
    {
        List<AbsentTrueLeafDetector.Pair> gated = AbsentTrueLeafDetector.pairs(
                rule("T", "empty(LBORRES) and ZZROW == \"a\"",
                        ",\"Requirements\":{\"Variables\":{\"All\":[\"LBORRES\"]}}", ""),
                body("empty(LBORRES) and ZZROW == \"a\"",
                        ",\"Requirements\":{\"Variables\":{\"All\":[\"LBORRES\"]}}", ""));
        assertTrue(gated.stream().noneMatch(p -> p.column().equals("LBORRES") && p.open()),
                gated.toString());
        String guarded = "not empty(LBORRES) and LBORRES != \"X\" and ZZROW == \"a\"";
        List<AbsentTrueLeafDetector.Pair> g = AbsentTrueLeafDetector
                .pairs(rule("T", guarded, "", ""), body(guarded, "", ""));
        assertTrue(g.stream().noneMatch(p -> p.column().equals("LBORRES") && p.open()),
                g.toString());
        String bare = "empty(LBORRES)";
        assertEquals(List.of(),
                AbsentTrueLeafDetector.pairs(rule("T", bare, "", ""), body(bare, "", "")),
                "a bare leaf keeps its broadcast verdict: one finding, never a flood");
    }


    private static void assertPair(String check, String requirements, String outcome, String column,
            boolean dotted, boolean mfDischarged)
    {
        List<AbsentTrueLeafDetector.Pair> pairs = AbsentTrueLeafDetector.pairs(
                rule("T-ATL-CTL", check, requirements, outcome),
                body(check, requirements, outcome));
        AbsentTrueLeafDetector.Pair p = pairs.stream().filter(x -> x.column().equals(column))
                .findFirst().orElseThrow(
                        () -> new AssertionError(check + ": no pair " + column + " in " + pairs));
        assertTrue(p.open(), p.toString());
        assertEquals(dotted, p.dotted(), p.toString());
        assertEquals(mfDischarged, p.mfDischarged(), check + " → " + p);
    }


    /** Writes the shipped corpus census to {@code target/atl-census.tsv} (auditable population). */
    @Test
    void theCensusIsWritten() throws IOException
    {
        AbsentTrueLeafDetector.Census census = AbsentTrueLeafDetector.census();
        assertTrue(census.rules() > 3000, "only " + census.rules() + " shipped rules visited");
        StringBuilder sb = new StringBuilder(
                "rule\tcolumn\tshape\tdotted\tgated\tdatasetGated\tguarded\tmfDischarged"
                        + "\tdatasetSensitivity\n");
        for (AbsentTrueLeafDetector.Pair p : census.pairs())
        {
            sb.append(p.rule()).append('\t').append(p.column()).append('\t').append(p.shape())
                    .append('\t').append(p.dotted()).append('\t').append(p.gated()).append('\t')
                    .append(p.datasetGated()).append('\t').append(p.guarded()).append('\t')
                    .append(p.mfDischarged()).append('\t')
                    .append(census.datasetSensitivityRules().contains(p.rule())).append('\n');
        }
        Path out = CorpusPaths.moduleDir().resolve("target").resolve("atl-census.tsv");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }
}
