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
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-qualified-name-uniformity-review} N25 — the corpus census of the OBSERVE-ONLY Stage A
 * kind {@link StageAErrorKind#DOTTED_REF_UNDECLARED}: a dotted read (Check, Bindings, a colref /
 * find_vars name, a qualified template operand, an {@code Output_Variables} entry) whose qualifier
 * names no {@code Match_Datasets} entry. Observe-only means the engine only LOGS it (DEBUG) and
 * offers it to {@link StageAChecker#setObserver}; until this test nothing in either repository read
 * it, so a new undeclared qualifier in the corpus — a rule that silently reads its type default on
 * every row, or drops an output column from every finding — reddened nothing.
 *
 * <p>
 * The census is an EXACT ratchet over {@code (rule, name)} across every generated package (DRAFT
 * included — a parked candidate is still authored text): a new pair reds until the rule is fixed or
 * the pair is recorded here with its reason; a fixed one reds until its row is removed.
 * </p>
 *
 * <p>
 * Non-vacuity, on every run: a synthetic rule with an undeclared {@code DM.AGE} must be seen by the
 * same collector (the kind and the observer still fire), every package must load, and the number of
 * rule checks observed must clear a floor (the corpus was actually walked).
 * </p>
 */
class DottedRefUndeclaredCorpusCensusTest
{

    /**
     * The recorded pairs — EMPTY since T2 r1: CDISC-AD0640–0645 (QNU N17, measured 2026-10-01)
     * named {@code AE.X} in {@code Output_Variables} with no {@code Match_Datasets}, an entry
     * omitted from every finding at run time; the six lines were deleted (the bare {@code X} stays,
     * and reported nothing less — no finding moved). The Output_Variables arm is kept covered by
     * the control below.
     */
    private static final Set<String> EXPECTED = Set.of();

    /**
     * The generated corpus has EXACTLY this many packages (T2 r1: a floor of 30 let a corpus that
     * lost a third of its packages pass).
     */
    private static final int PACKAGE_COUNT = 47;

    /**
     * Floor of rule checks the observer must see over the whole corpus — one per loaded rule;
     * measured 12 852 at corej a4a8711 / rules c5363e27, the floor just below it (T2 r1).
     */
    private static final int OBSERVED_FLOOR = 12_000;

    private static final Pattern NAME = Pattern
            .compile("^(?:Output_Variables entry |the column name )?(\\S+) ");

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
        StageAChecker.setObserver((rule, report) ->
        {
            if (Thread.currentThread() != self)
            {
                return; // another test class loading rules in parallel
            }
            observed.incrementAndGet();
            for (StageAFinding f : report.findings())
            {
                if (f.kind() == StageAErrorKind.DOTTED_REF_UNDECLARED)
                {
                    found.add(key(String.valueOf(rule.effectiveId()), f));
                }
            }
        });
        try
        {
            // NON-VACUITY: the collector sees a known undeclared qualifier on BOTH arms — the
            // Check (DM.AGE) and an Output_Variables entry (Q.S); with the recorded set empty the
            // output arm would otherwise be covered by nothing (T2 r1).
            RulePackageLoader.loadFromString("{\"rules\":{\"R\":{\"Core\":{\"Id\":\"T-N25-CTRL\"},"
                    + "\"Sensitivity\":\"Record\",\"Check\":{\"expression\":\"DM.AGE > 30\"},"
                    + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"STUDYID\",\"Q.S\"]}}}}");
            assertEquals(Set.of("T-N25-CTRL | DM.AGE", "T-N25-CTRL | Q.S"), found,
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
            StageAChecker.setObserver(null);
        }
        assertTrue(observed.get() >= OBSERVED_FLOOR,
                "rule checks observed over the corpus: " + observed.get());
        assertEquals(new TreeSet<>(EXPECTED), found,
                "DOTTED_REF_UNDECLARED over the corpus must equal the recorded set exactly — fix"
                        + " the rule (declare the join, or qualify / drop the name) or record the"
                        + " pair with its reason; remove a fixed pair");
    }
}
