package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.cumba.corej.core.expr.typed.StageAChecker;
import net.cumba.corej.core.expr.typed.StageAErrorKind;
import net.cumba.corej.core.expr.typed.StageAFinding;
import net.cumba.corej.core.expr.typed.StageAReport;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-merged-evaluation-table} W3d (P-19 / MET #6) — the corpus census of the naming lint
 * {@link StageAErrorKind#QUALIFIER_CASE}: a dotted operand, a {@code _matched_} flag, a
 * {@code colref} / {@code find_vars} literal or an {@code Output_Variables} entry whose qualifier
 * names its {@code Match_Datasets} entry by letter case only ({@code dm.ARM} against
 * {@code Name: DM}). The read resolves — a dataset name is case-insensitive, upper case expected —
 * so the kind is observe-only; this census is what makes a lower-case spelling visible in the
 * authored corpus.
 *
 * <p>
 * The census is an EXACT ratchet over {@code (rule, name)} across every generated package: a new
 * pair reds until the rule is respelled or the pair is recorded here with its reason; a fixed one
 * reds until its row is removed.
 * </p>
 *
 * <p>
 * Non-vacuity, on every run: a synthetic rule spelling {@code dm.ARM} against an entry {@code DM}
 * must be seen on the Check arm and the Output_Variables arm, every package must load, and the
 * number of rule checks observed must clear a floor (the corpus was actually walked).
 * </p>
 */
class QualifierCaseCorpusCensusTest
{

    /** The recorded pairs — EMPTY: the authored corpus spells every qualifier in upper case. */
    private static final Set<String> EXPECTED = Set.of();

    /**
     * The generated corpus has EXACTLY this many packages (as the undeclared-qualifier census pins
     * it: a floor let a corpus that lost a third of its packages pass).
     */
    private static final int PACKAGE_COUNT = 47;

    /**
     * Floor of rule checks the observer must see over the whole corpus — one per loaded rule;
     * measured 12 852 at corej a4a8711 / rules c5363e27, the floor just below it (T2 r1).
     */
    private static final int OBSERVED_FLOOR = 12_000;

    private static final Pattern NAME = Pattern.compile("^(\\S+) spells the qualifier ");

    /** The (rule, name) of every DOTTED_REF_UNDECLARED finding of {@code finding}'s rule. */
    private static String key(String ruleId, StageAFinding finding)
    {
        Matcher m = NAME.matcher(finding.message());
        return ruleId + " | " + (m.find() ? m.group(1) : finding.message());
    }


    @Test
    void theCorpusCarriesExactlyTheRecordedUndeclaredQualifiers() throws Exception
    {
        List<Path> packages;
        try (Stream<Path> files = Files.list(CorpusPaths.rules()))
        {
            packages = files.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.toString().endsWith(".json")).sorted().toList();
        }
        assertEquals(PACKAGE_COUNT, packages.size(), "the generated corpus is complete");
        Set<String> found = new TreeSet<>();
        AtomicInteger observed = new AtomicInteger();
        Thread self = Thread.currentThread();
        java.util.function.BiConsumer<Rule, StageAReport> previous = StageAChecker
                .setObserver((rule, report) ->
                {
                    if (Thread.currentThread() != self)
                    {
                        return; // another test class loading rules in parallel
                    }
                    observed.incrementAndGet();
                    for (StageAFinding f : report.findings())
                    {
                        if (f.kind() == StageAErrorKind.QUALIFIER_CASE)
                        {
                            found.add(key(String.valueOf(rule.effectiveId()), f));
                        }
                    }
                });
        try
        {
            // NON-VACUITY: the collector sees a known case-only qualifier on BOTH arms — the
            // Check (dm.ARM) and an Output_Variables entry (dm.SEX) — against the entry DM.
            RulePackageLoader.loadFromString("{\"rules\":{\"R\":{\"Core\":{\"Id\":\"T-QC-CTRL\"},"
                    + "\"Sensitivity\":\"Record\",\"Requirements\":{\"Variables\":{\"All\":"
                    + "[\"USUBJID\"],\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]}},"
                    + "\"Match_Datasets\":[{\"Name\":\"DM\","
                    + "\"Keys\":[\"USUBJID\"],\"Join_Type\":\"left\"}],"
                    + "\"Check\":{\"expression\":\"dm.ARM == \\\"x\\\"\"},"
                    + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"STUDYID\",\"dm.SEX\"]}}}}");
            assertEquals(Set.of("T-QC-CTRL | dm.ARM", "T-QC-CTRL | dm.SEX"), found,
                    "the control must be seen on both arms — else the census below proves nothing");
            found.clear();
            int controlObserved = observed.getAndSet(0);
            assertEquals(1, controlObserved, "one rule, one observation");
            List<String> loadErrors = new ArrayList<>();
            for (Path pkg : packages)
            {
                for (var rule : RulePackageLoader.load(pkg).getRules().values())
                {
                    if (rule.getLoadError() != null)
                    {
                        loadErrors.add(rule.effectiveId() + ": " + rule.getLoadError());
                    }
                }
            }
            assertEquals(List.of(), loadErrors,
                    "a rule parked with a load error is never Stage-A observed, so the census"
                            + " would silently skip it");
        }
        finally
        {
            // restore, never clear: a reset blinds every later observer in the JVM
            // (PLAN-stage-a-parameter-type-arming review r1 tests 5 — it blinded that plan's probe)
            StageAChecker.setObserver(previous);
        }
        assertTrue(observed.get() >= OBSERVED_FLOOR,
                "rule checks observed over the corpus: " + observed.get());
        assertEquals(new TreeSet<>(EXPECTED), found,
                "QUALIFIER_CASE over the corpus must equal the recorded set exactly — respell the"
                        + " qualifier as the entry's Name (upper case) or record the pair with its"
                        + " reason; remove a fixed pair");
    }
}
