package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
     * The recorded pairs. ⚠ CDISC-AD0640–0645 (QNU N17, measured 2026-10-01): a dataset-level
     * {@code not var_exists("X")} whose {@code Output_Variables} names {@code AE.X} with no
     * {@code Match_Datasets} — the entry is omitted from every finding at run time. Recorded, not
     * fixed: the respelling (drop the entry, or report the variable another way) is a corpus edit
     * outside the plan that filed it.
     */
    private static final Set<String> EXPECTED = Set.of("CDISC-AD0640 | AE.AETRTEM",
            "CDISC-AD0641 | AE.AESTDY", "CDISC-AD0642 | AE.AEENDY", "CDISC-AD0643 | AE.AEDUR",
            "CDISC-AD0644 | AE.AESEV", "CDISC-AD0645 | AE.AETOXGR");

    /** Floor of rule checks the observer must see over the whole corpus (measured far above). */
    private static final int OBSERVED_FLOOR = 3000;

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
        assertTrue(packages.size() >= 30, "the generated corpus is present: " + packages.size());
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
            // NON-VACUITY: the collector sees a known undeclared qualifier.
            RulePackageLoader.loadFromString("{\"rules\":{\"R\":{\"Core\":{\"Id\":\"T-N25-CTRL\"},"
                    + "\"Sensitivity\":\"Record\",\"Check\":{\"expression\":\"DM.AGE > 30\"},"
                    + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"STUDYID\"]}}}}");
            assertEquals(Set.of("T-N25-CTRL | DM.AGE"), found,
                    "the control must be seen — else the census below proves nothing");
            found.clear();
            int controlObserved = observed.getAndSet(0);
            assertEquals(1, controlObserved, "one rule, one observation");
            for (Path pkg : packages)
            {
                RulePackageLoader.load(pkg);
            }
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
