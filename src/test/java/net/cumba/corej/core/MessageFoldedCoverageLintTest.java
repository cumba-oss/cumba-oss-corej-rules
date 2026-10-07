package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.RuleSpecialiser;
import net.cumba.corej.core.expr.eval.FoldCapability;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-message-folded-corpus-alignment} row 14 — the coverage lint, and after Q6 <b>the only
 * thing that keeps a corpus rule folding on an absent column</b>: every record-level rule the
 * absent-column fold can decide TRUE ({@link FoldCapability#absentTrueCapable}, P5) or the
 * present-but-empty collapse can collapse ({@link FoldCapability#collapsible}, C7 R2) must declare
 * {@code Outcome.Message_Folded}. A rule that loses the key reports <b>per row</b> on an absent
 * column — no load error, no rule ERROR, nothing red anywhere else — which is why this lint is
 * permanent and why it runs in both corpora.
 *
 * <p>
 * <b>The census, not a grep.</b> Every shipped package is loaded with the engine's own loader
 * (Sensitivity derived, native expression installed), every rule is specialised under one synthetic
 * prefix, and the fold is evaluated with exactly the columns the rule's
 * {@code Requirements.Variables} gate guarantees present ({@link FoldCapability#guaranteedPresent})
 * and every other column absent — the one evaluation that bounds every run-time absent fold (Kleene
 * monotonicity, P5). The census is written to {@code target/fold-census.tsv} on every run (one row
 * per shipped rule: Sensitivity, collapsible, may-absent-fold, absent-TRUE-capable, the
 * enumerated-leaf count, whether the key is declared) so the population is auditable.
 * </p>
 *
 * <p>
 * ⛔ <b>Non-vacuity.</b> The population is floored ({@link #MIN_POPULATION}: a loader that stops
 * installing native expressions, or a classifier that stops matching, would otherwise pass over an
 * empty roster), and {@link #theLintRedsARuleStrippedOfItsKey} is the planted control: a census
 * rule with its key removed in memory must appear in the offender roster.
 * </p>
 */
class MessageFoldedCoverageLintTest
{

    /** The synthetic variable wildcard prefix the census specialises under. */
    static final String PREFIX = "XX";

    /** The anchor column of the census table — a table needs one, and no rule reads this name. */
    static final String ANCHOR = "ZZZCENSUS";

    /**
     * The population floor: record-level rules the fold or the collapse can decide. Measured
     * 2026-10-07 (phase 2 of the plan, after the R-4.11c sisters were retired): 666 demanded of 3
     * 396 shipped rules; a drop below the floor means the census stopped seeing a class (a trigger
     * polarity, the absent arm, the Type 1 subtraction), not that the corpus shrank by that much.
     */
    static final int MIN_POPULATION = 600;

    /** One census row. */
    record Row(String id, String file, Sensitivity sensitivity, boolean collapsible,
            boolean mayAbsentFold, boolean capable, boolean collapsibleByAbsence,
            String absentColumns, int enumerated, boolean capped, boolean declaresKey,
            String leaves, boolean residual, String blankTriggers, Rule rule)
    {

        boolean record()
        {
            return sensitivity == Sensitivity.RECORD;
        }


        /**
         * Whether the key is demanded: a record-level rule the absent fold can decide TRUE, or the
         * present-but-empty collapse can collapse (a polarity-live trigger). An absent column is
         * never a collapse trigger (PACK 4 review HIGH-1, per Q3): on its own it is the absent
         * fold's, censused by {@code capable}.
         */
        boolean demanded()
        {
            return record() && (capable || !blankTriggers.isEmpty());
        }
    }

    /** The census over every shipped rule, deduplicated by id, in package order. */
    static List<Row> census() throws IOException
    {
        Map<String, Row> rows = new LinkedHashMap<>();
        List<Path> packages;
        try (Stream<Path> s = Files.list(CorpusPaths.rules()))
        {
            packages = s.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        assertFalse(packages.isEmpty(),
                "no shipped package under " + CorpusPaths.rules() + " (target/rules generated?)");
        for (Path p : packages)
        {
            for (Rule rule : RulePackageLoader.load(p).getRules().values())
            {
                String id = Objects.requireNonNull(rule.getCore()).getId();
                if (rows.containsKey(id))
                {
                    continue;
                }
                assertTrue(rule.getLoadError() == null, id + ": " + rule.getLoadError());
                rows.put(id, classify(id, p.getFileName().toString(), rule));
            }
        }
        return new ArrayList<>(rows.values());
    }


    private static Row classify(String id, String file, Rule rule)
    {
        Rule spec = Objects.requireNonNull(RuleSpecialiser.specialise(rule, null, PREFIX));
        MockTable table = MockTable.of().name(PREFIX).col(ANCHOR, "v");
        for (String gated : FoldCapability.guaranteedPresent(spec, PREFIX))
        {
            if (!gated.equalsIgnoreCase(ANCHOR) && gated.indexOf('.') < 0)
            {
                table.col(gated, "v");
            }
        }
        EvaluationContext ctx = FoldCapability.censusContext(spec, table.build(), PREFIX);
        FoldCapability.AbsentFoldCensus c = FoldCapability.absentTrueCapable(spec, ctx);
        // The keep set is the rule's own Keep_Row_Findings, with or without Message_Folded: a
        // rule whose ONLY trigger is a Type 1 column (R-4.11b's verdict 1, kept per row) carries
        // the list alone, and the census honours it — the key would be inert there, and without
        // the list the census would demand it (CDISC-SEND-0330 / FDA-SE2234 on PCCALCN,
        // FDA/PMDA-SD0065 on --STAT, FDA/PMDA-SD1247 on ECDOSTXT, 2026-10-07).
        FoldCapability.Triggers t = FoldCapability.triggers(spec, ctx,
                net.cumba.corej.core.expr.eval.EmptyColumnCollapse
                        .resolveKeepRowFindings(spec.getOutcome(), ctx));
        boolean key = rule.getOutcome() != null && rule.getOutcome().getMessageFolded() != null;
        // The collapse triggers (plain empty-family leaves, as written) and whether any other
        // column read remains once they are folded — the S1 / S2 shape the message generator
        // phrases (T2 / T3).
        java.util.Set<String> leaves = new java.util.LinkedHashSet<>();
        java.util.Set<String> reads = new java.util.LinkedHashSet<>();
        if (rule.getCheck() instanceof net.cumba.corej.core.model.CheckConditionExpression ce)
        {
            leaves.addAll(FoldCapability.plainColumnLeafNames(ce.expr()));
            reads.addAll(FoldCapability.readColumnNames(ce.expr()));
        }
        boolean residual = reads.stream()
                .anyMatch(r -> leaves.stream().noneMatch(r::equalsIgnoreCase));
        // The absent columns as the authored Check names them: the census prefix back to `--`.
        java.util.Set<String> absent = new java.util.TreeSet<>();
        for (String name : c.absentLeafColumns())
        {
            absent.add(name.startsWith(PREFIX) ? "--" + name.substring(PREFIX.length()) : name);
        }
        return new Row(id, file, rule.getSensitivity(), FoldCapability.collapsible(rule),
                FoldCapability.mayAbsentFold(rule), c.capable(), c.collapsibleByAbsence(),
                String.join(",", absent), c.enumeratedLeaves(), c.capped(), key,
                String.join(",", leaves), residual, triggerText(t.blank()), rule);
    }


    /** {@code X=TRUE,Y=UNKNOWN} with the census prefix spelled back as {@code --}. */
    private static String triggerText(
            Map<String, net.cumba.corej.core.expr.eval.BroadcastFold.Verdict> triggers)
    {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, net.cumba.corej.core.expr.eval.BroadcastFold.Verdict> e : triggers
                .entrySet())
        {
            String name = e.getKey().startsWith(PREFIX)
                    ? "--" + e.getKey().substring(PREFIX.length())
                    : e.getKey();
            out.add(name + "=" + e.getValue());
        }
        return String.join(",", out);
    }


    static List<String> offenders(List<Row> rows)
    {
        List<String> out = new ArrayList<>();
        for (Row r : rows)
        {
            if (r.demanded() && !r.declaresKey())
            {
                List<String> why = new ArrayList<>();
                if (r.capable())
                {
                    why.add("absent-fold");
                }
                if (!r.blankTriggers().isEmpty())
                {
                    why.add("collapse:" + r.blankTriggers());
                }
                out.add(r.id() + " (" + String.join("+", why) + ")");
            }
        }
        return out;
    }


    private static void writeCensus(List<Row> rows) throws IOException
    {
        Path out = CorpusPaths.moduleDir().resolve("target").resolve("fold-census.tsv");
        Files.createDirectories(out.getParent());
        StringBuilder sb = new StringBuilder(
                "id\tfile\tsensitivity\tcollapsible\tmayAbsentFold\tabsentTrueCapable"
                        + "\tcollapsibleByAbsence\tabsentColumns\tenumerated\tcapped"
                        + "\tdeclaresKey\tdemanded\tleaves\tresidual\tblankTriggers\n");
        for (Row r : rows)
        {
            sb.append(r.id()).append('\t').append(r.file()).append('\t')
                    .append(r.sensitivity() == null ? "-" : r.sensitivity().getJsonValue())
                    .append('\t').append(r.collapsible()).append('\t').append(r.mayAbsentFold())
                    .append('\t').append(r.capable()).append('\t').append(r.collapsibleByAbsence())
                    .append('\t').append(r.absentColumns()).append('\t').append(r.enumerated())
                    .append('\t').append(r.capped()).append('\t').append(r.declaresKey())
                    .append('\t').append(r.demanded()).append('\t').append(r.leaves()).append('\t')
                    .append(r.residual()).append('\t').append(r.blankTriggers()).append('\n');
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }


    @Test
    void everyFoldableRecordRuleDeclaresMessageFolded() throws IOException
    {
        List<Row> rows = census();
        writeCensus(rows);
        long population = rows.stream().filter(Row::demanded).count();
        assertTrue(population >= MIN_POPULATION, "the census population collapsed: " + population
                + " < " + MIN_POPULATION + " (a classifier or the loader stopped matching)");
        List<String> offenders = offenders(rows);
        assertEquals(List.of(), offenders,
                "record-level rules the absent fold or the collapse can decide, without"
                        + " Outcome.Message_Folded — after Q6 they report PER ROW on an absent"
                        + " column, silently (" + offenders.size() + " of " + population + "): ");
    }


    @Test
    void theLintRedsARuleStrippedOfItsKey() throws IOException
    {
        List<Row> rows = census();
        Row victim = rows.stream().filter(r -> r.demanded() && r.declaresKey()).findFirst()
                .orElseThrow(() -> new AssertionError("no demanded rule declares the key"));
        List<Row> stripped = new ArrayList<>();
        for (Row r : rows)
        {
            stripped.add(
                    r == victim
                            ? new Row(r.id(), r.file(), r.sensitivity(), r.collapsible(),
                                    r.mayAbsentFold(), r.capable(), r.collapsibleByAbsence(),
                                    r.absentColumns(), r.enumerated(), r.capped(), false,
                                    r.leaves(), r.residual(), r.blankTriggers(), r.rule())
                            : r);
        }
        List<String> offenders = offenders(stripped);
        assertEquals(1, offenders.size(), offenders.toString());
        assertTrue(offenders.getFirst().startsWith(victim.id() + " "), offenders.getFirst());
        assertEquals(List.of(), offenders(rows), "the unstripped census is clean (control)");
    }


    @Test
    void aNonRecordRuleNeverCarriesTheKey() throws IOException
    {
        // P1: a Sensitivity Dataset / Group / Study rule keeps Message; the loader refuses the key
        // on it (C7 R2 b), so this is the corpus-side pin of the same fact.
        List<String> wrong = new ArrayList<>();
        for (Row r : census())
        {
            if (!r.record() && r.declaresKey())
            {
                wrong.add(r.id());
            }
        }
        assertEquals(List.of(), wrong);
    }
}
