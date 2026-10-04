package net.cumba.corej.core.ruletestsuites;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.ruletest.cdt.ruletest.ExecutionVerdictCheck;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.corej.ruletest.cdt.ruletest.ViolationLocationCheck;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The equivalence instrument of {@code PLAN-dynamic-column-corpus-respelling} (§2.8): every
 * scenario of a respelled rule is executed twice — once against the rule's <b>base</b> text and
 * once against the respelled text — and the <b>whole finding rows</b> are compared: row, reported
 * variable names and values, USUBJID / SEQ, keys, severity level, plus the execution state
 * (executed / skipped / error with its reason), the violation count and the truncation flag.
 *
 * <p>
 * <b>Why it exists.</b> The findings snapshot never runs the 42 {@code ${…}} rules (it is an SDTM
 * study run), and the {@code .cdt} suites assert only part of each finding (several {@code invalid}
 * scenarios pin {@code USUBJID} alone). A respelling that reports one column fewer, or at a
 * different severity, would therefore pass every existing gate. This test is the proof that the
 * respelled rules answer <em>identically</em>, except where the plan lists a verdict move.
 * </p>
 *
 * <p>
 * <b>Opt-in.</b> It runs only with {@code -Dcorej.respell.base=
 *
<dir>
 * }, a corpus directory holding the <b>base</b> packages ({@code rules-<family>-*.json}), generated
 * from the base rule text: {@code git archive <base> rules-src | tar -x -C <tree>}, then, with
 * {@code <tree>} as the working directory, {@code java -cp <test classpath>
 * net.cumba.corej.core.expr.convert.RuleCorpusGenerator
<dir>
 <release-dir>}. Without the property the class is skipped — this is the plan's instrument, not a
 * permanent gate: after the plan the {@code ${…}} path is covered by engine tests (register
 * {@code DCF Q8}).
 * </p>
 *
 * <p>
 * Other properties:
 * </p>
 * <ul>
 * <li>{@code corej.respell.rules} — comma-separated {@code Core.Id}s; default: every rule of the
 * {@code cdisc} and {@code pmda} families whose base Check or {@code Output_Variables} carries
 * {@code ${}. The rule of every listed move joins the population either way;</li>
 * <li>{@code corej.respell.expectedMoves} — comma-separated scenario paths relative to the scenario
 * root ({@code cdisc/CDISC-AD0592/CDISC-AD0592-…-ADLBC.cdt}) whose two outcomes are <b>expected</b>
 * to differ: the plan's listed Q1 / Q3 moves, each carried by a sensitivity scenario. ⚠ Every
 * listed path MUST differ and MUST fail the scenario's own expectation on the base text
 * (red-first); an entry that does not move fails the test, so the list cannot go stale;</li>
 * <li>{@code corej.respell.expectedReportMoves} — the same for a <b>verdict-neutral</b> move: the
 * finding rows differ (e.g. the Q3 narrowing of an {@code Output_Variables} template drops a
 * non-IG-width column from the report) while the scenario's own expectation holds on both texts.
 * Every listed path MUST differ and MUST pass on both;</li>
 * <li>{@code corej.respell.report} — a file to write every scenario's two outcomes to, row by row,
 * so the base rows can be read (the reported-variable set of §2.3 M1 is measured from it).</li>
 * </ul>
 *
 * <p>
 * The {@code rulespec} fixtures of {@code CDISC-AD0500 / AD0720 / AD0897} are not run here: their
 * {@code expected_violations} already pin the whole row ({@code row_key}, every {@code
 * output_variables} entry) and the fixtures are not touched by the respelling, so {@code
 * RuleExecutionSpecTest} green on the respelled rule is the same statement for them.
 * </p>
 */
@EnabledIfSystemProperty(named = DynamicColumnRespellingDifferentialTest.BASE_PROPERTY,
        matches = ".+")
class DynamicColumnRespellingDifferentialTest
{

    static final String BASE_PROPERTY = "corej.respell.base";

    private static final String RULES_PROPERTY = "corej.respell.rules";

    private static final String MOVES_PROPERTY = "corej.respell.expectedMoves";

    private static final String REPORT_MOVES_PROPERTY = "corej.respell.expectedReportMoves";

    private static final String REPORT_PROPERTY = "corej.respell.report";

    private static final Path RESOURCE_BASE = Path.of(System.getProperty("projectBasedir"),
            "src/test/resources/net/cumba/corej/core/ruletestsuites");

    /** The families the {@code ${…}} rules live in; a factory per family runs the scenarios. */
    private static final List<AbstractRuleTestSuitesFactory> FACTORIES = List
            .of(new RuleTestSuitesCdiscFactoryTest(), new RuleTestSuitesPmdaFactoryTest());

    private static final java.util.regex.Pattern COMMA = java.util.regex.Pattern.compile(",");

    @Test
    void respelledRulesAnswerIdenticallyExceptTheListedMoves() throws IOException
    {
        Path baseDir = Path.of(System.getProperty(BASE_PROPERTY));
        assertTrue(Files.isDirectory(baseDir), BASE_PROPERTY + " is not a directory: " + baseDir);
        Set<String> expectedMoves = csv(System.getProperty(MOVES_PROPERTY, ""));
        Set<String> expectedReportMoves = csv(System.getProperty(REPORT_MOVES_PROPERTY, ""));
        Set<String> onlyRules = csv(System.getProperty(RULES_PROPERTY, ""));
        Set<String> allExpected = new LinkedHashSet<>(expectedMoves);
        allExpected.addAll(expectedReportMoves);

        StringBuilder report = new StringBuilder();
        List<String> unexpectedDiffs = new ArrayList<>();
        Set<String> moved = new LinkedHashSet<>();
        List<String> baseVerdictFailures = new ArrayList<>();
        int scenarios = 0;
        int rulesSeen = 0;

        for (AbstractRuleTestSuitesFactory factory : FACTORIES)
        {
            String family = factory.family();
            Map<String, Map<String, Rule>> basePackages = AbstractRuleTestSuitesFactory
                    .loadFamily(baseDir, family);
            Map<String, Map<String, Rule>> currentPackages = AbstractRuleTestSuitesFactory
                    .loadFamily(family);
            Set<String> population = new java.util.TreeSet<>(
                    onlyRules.isEmpty() ? dynamicRules(basePackages) : onlyRules);
            // A listed move names its rule in its path (<family>/<Core.Id>/<file>), and that rule
            // joins the population whatever its base text — the None-template census rules
            // (CDISC-AD0719) carry no `${` and would otherwise be invisible to the instrument.
            for (String path : allExpected)
            {
                String[] parts = path.split("/", -1);
                if (parts.length == 3 && parts[0].equals(family))
                {
                    population.add(parts[1]);
                }
            }
            for (String coreId : new java.util.TreeSet<>(population))
            {
                Path ruleDir = RESOURCE_BASE.resolve(family).resolve(coreId);
                if (!Files.isDirectory(ruleDir))
                {
                    continue; // the other family's rule
                }
                rulesSeen++;
                List<Path> files;
                try (Stream<Path> walk = Files.list(ruleDir))
                {
                    files = walk.filter(p -> p.getFileName().toString().endsWith(".cdt"))
                            .filter(AbstractRuleTestSuitesFactory::hasShebang).sorted().toList();
                }
                assertFalse(files.isEmpty(), coreId + " has no scenarios under " + ruleDir);
                for (Path file : files)
                {
                    scenarios++;
                    String rel = RESOURCE_BASE.relativize(file).toString().replace('\\', '/');
                    RuleTestScenario s = RuleTestCdt.load(file);
                    Rule base = AbstractRuleTestSuitesFactory.resolveIn(basePackages, s);
                    assertNotNull(base, "base packages carry no " + coreId + " for " + rel);
                    Rule current = AbstractRuleTestSuitesFactory.resolveIn(currentPackages, s);
                    assertNotNull(current, "current packages carry no " + coreId + " for " + rel);

                    OverlayDataTable primary = s.primaryTable();
                    assertNotNull(primary, rel + " names no primary dataset");
                    AbstractRuleTestSuitesFactory.EvalOutcome b = factory.evaluateScenario(file, s,
                            base, primary);
                    AbstractRuleTestSuitesFactory.EvalOutcome c = factory.evaluateScenario(file, s,
                            current, primary);
                    String baseText = render(b);
                    String currentText = render(c);
                    boolean basePasses = passes(s, b, primary);
                    boolean currentPasses = passes(s, c, primary);

                    report.append("== ").append(rel).append('\n');
                    report.append("base-verdict: ").append(basePasses ? "pass" : "FAIL")
                            .append("  respelled-verdict: ").append(currentPasses ? "pass" : "FAIL")
                            .append('\n');
                    report.append("-- base\n").append(baseText);
                    if (baseText.equals(currentText))
                    {
                        report.append("-- respelled: IDENTICAL\n");
                    }
                    else
                    {
                        report.append("-- respelled\n").append(currentText);
                    }

                    boolean differs = !baseText.equals(currentText);
                    boolean listedMove = expectedMoves.contains(rel);
                    boolean listedReportMove = expectedReportMoves.contains(rel);
                    if (differs && !listedMove && !listedReportMove)
                    {
                        unexpectedDiffs.add(
                                rel + "\n-- base\n" + baseText + "-- respelled\n" + currentText);
                    }
                    if (differs)
                    {
                        moved.add(rel);
                    }
                    if (listedMove && basePasses)
                    {
                        baseVerdictFailures.add(rel
                                + " is a listed move but the BASE text already satisfies it — the"
                                + " sensitivity scenario is not red-first");
                    }
                    if (listedReportMove && !(basePasses && currentPasses))
                    {
                        baseVerdictFailures.add(rel + " is a listed report move but its own"
                                + " expectation does not hold on both texts (base " + basePasses
                                + ", respelled " + currentPasses + ")");
                    }
                    if (!listedMove && !basePasses)
                    {
                        baseVerdictFailures.add(rel + " fails its own expectation on the BASE text"
                                + " but is not a listed move");
                    }
                }
            }
        }

        String summary = "[respell-differential] rules=" + rulesSeen + " scenarios=" + scenarios
                + " moved=" + moved.size() + " expected=" + allExpected.size() + " moved-set="
                + moved;
        String reportPath = System.getProperty(REPORT_PROPERTY, "");
        if (!reportPath.isBlank())
        {
            Files.writeString(Path.of(reportPath), summary + "\n" + report, UTF_8);
        }

        assertTrue(rulesSeen > 0, "no rule of the population has scenarios");
        assertTrue(unexpectedDiffs.isEmpty(),
                () -> unexpectedDiffs.size() + " scenario(s) moved that are not listed moves:\n"
                        + String.join("\n", unexpectedDiffs));
        Set<String> stale = new LinkedHashSet<>(allExpected);
        stale.removeAll(moved);
        assertTrue(stale.isEmpty(),
                () -> "listed moves that did NOT move (stale list, or the base and respelled text"
                        + " agree): " + stale);
        assertTrue(baseVerdictFailures.isEmpty(), () -> String.join("\n", baseVerdictFailures));
        assertEquals(allExpected, moved,
                () -> "the moved set must be exactly the listed moves; " + summary);
    }


    /** Rule ids whose base Check source or {@code Output_Variables} carries {@code ${}. */
    private static Set<String> dynamicRules(Map<String, Map<String, Rule>> packages)
    {
        Set<String> ids = new java.util.TreeSet<>();
        for (Map<String, Rule> pkg : packages.values())
        {
            for (Map.Entry<String, Rule> e : pkg.entrySet())
            {
                Rule r = e.getValue();
                boolean dynamic = checkSource(r).contains("${");
                if (!dynamic && r.getOutcome() != null
                        && r.getOutcome().getOutputVariables() != null)
                {
                    dynamic = r.getOutcome().getOutputVariables().stream()
                            .anyMatch(v -> v.contains("${"));
                }
                if (dynamic)
                {
                    ids.add(e.getKey());
                }
            }
        }
        return ids;
    }


    private static String checkSource(Rule r)
    {
        CheckCondition check = r.getCheck();
        if (check instanceof CheckConditionExpression e)
        {
            return e.source();
        }
        Object json = r.getCheckJson();
        return json == null ? "" : json.toString();
    }


    private static boolean passes(RuleTestScenario s,
            AbstractRuleTestSuitesFactory.@Nullable EvalOutcome o, OverlayDataTable primary)
    {
        if (o == null)
        {
            return true; // out of scope on both sides; the factory asserts the declaration
        }
        ExecutionVerdictCheck.Result verdict = ExecutionVerdictCheck.verify(s,
                new ExecutionVerdictCheck.Outcome(o.executed(), o.errored(), o.violated(),
                        o.totalCount(), o.notExecutedReason()));
        return verdict.pass() && ViolationLocationCheck
                .verify(s, o.violations(), o.totalCount(), o.truncated(), primary).pass();
    }


    /** The whole outcome as text: state, then one line per finding row, rows sorted. */
    static String render(AbstractRuleTestSuitesFactory.@Nullable EvalOutcome o)
    {
        if (o == null)
        {
            return "OUT-OF-SCOPE\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(
                String.format(Locale.ROOT,
                        "state executed=%s errored=%s violated=%s count=%d"
                                + " truncated=%s reason=%s%n",
                        o.executed(), o.errored(), o.violated(), o.totalCount(), o.truncated(),
                        o.notExecutedReason()));
        List<String> rows = new ArrayList<>();
        for (Violation v : o.violations())
        {
            rows.add(renderRow(v));
        }
        rows.sort(null);
        for (String row : rows)
        {
            sb.append(row).append('\n');
        }
        return sb.toString();
    }


    private static String renderRow(Violation v)
    {
        Map<String, String> values = v.getValues() == null ? Map.of()
                : new TreeMap<>(v.getValues());
        Map<String, String> keys = v.getKeys() == null ? Map.of() : new TreeMap<>(v.getKeys());
        Map<String, String> group = v.getGroupKey() == null ? Map.of()
                : new TreeMap<>(v.getGroupKey());
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("row", v.getRowNumber());
        row.put("usubjid", v.getUsubjid());
        row.put("seq", v.getSeq());
        row.put("level", v.getLevel());
        row.put("unit", v.getUnit());
        row.put("keys", keys);
        row.put("group", group);
        row.put("values", values);
        return row.toString();
    }


    private static Set<String> csv(String raw)
    {
        Set<String> out = new LinkedHashSet<>();
        COMMA.splitAsStream(raw).map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
        return out;
    }
}
