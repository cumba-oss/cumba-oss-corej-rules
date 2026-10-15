package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * {@code plans/done/PLAN-dangling-operation-reference-load-check.md} phase 3 — the corpus ratchet
 * for {@code RulePackageLoader.validateOperationReferences}.
 *
 * <p>
 * <b>Errors ratchet at zero.</b> A {@code Check} that references a {@code $}-operand no
 * {@code Operations} entry defines loads cleanly and checks nothing, so a new one must fail the
 * build rather than ship silent. The shipped {@code rules/} corpus (expression form) is held to it.
 * </p>
 *
 * <p>
 * ⚠ <b>A second corpus used to be walked here, and dropping it narrowed the shape coverage.</b> The
 * operator-leaf folder reached the <em>leaf</em> arm of {@code validateOperationReferences} while
 * {@code rules/} reaches the <em>expression</em> arm, so a shape-specific hole in one arm showed up
 * as a disagreement between the two folders. That comparison is gone: this ratchet now exercises
 * the expression arm only. The leaf arm is still covered — but by the synthetic fixtures in
 * {@code DanglingOperationReferenceLoadTest} (corej-core), not by the corpus. The floors below are
 * what keeps <em>this</em> pass honest.
 * </p>
 *
 * <p>
 * <b>There is no longer a warning half — the channel itself is gone ({@code Fix #159}).</b> This
 * test used to also collect a {@code loadWarning} bucket against an {@code EXPECTED_WARNINGS} set,
 * because {@code validateOperationReferences} downgraded its finding to a warning for a rule
 * declaring {@code Executability: "Not Executable"}. That set held {@code CDISC-AD0591} and
 * {@code CDISC-AD0898} (the T3 ADaM engine gaps, the archived rule review
 * {@code engine-gap-consolidation.md} §T3), emptied legitimately when {@code Fix #147} made both
 * {@code Fully Executable}. {@code Fix #159} then made {@code Executability: "Not Executable"}
 * <em>park</em> a rule — it is dropped at load before this gate runs — so the downgrade branch was
 * removed and the pass writes to {@code loadError} only. <b>The warning bucket therefore became
 * unreachable by construction, not merely empty in practice, and collecting into it would have been
 * dead code that no corpus state could ever populate.</b> That is the wave-5 {@code M10} shape (a
 * guard that is both unreachable and untestable), so it was removed rather than left standing at
 * {@code Set.of()}. ⚠ Do not reinstate it without first reinstating a producer.
 * </p>
 *
 * <p>
 * ⚠ <b>What this test can and cannot prove.</b> It fails loudly the moment a dangling operand
 * appears anywhere in the shipped corpus — verified by pointing it at a copy of the corpus with one
 * such rule injected (see {@code plans/done/PLAN-not-executable-parks-a-rule.md} phase 3). With the
 * corpus clean, a neutered pass or a reworded loader message would still satisfy the corpus
 * assertions, so {@link #theRatchetIsLive_positiveControl} drives one planted rule through the same
 * loader and requires {@link #MARKER} to catch it (added 2026-10-15, after the marker had in fact
 * gone stale). The full proof of the pass's cases still <b>lives in
 * {@code DanglingOperationReferenceLoadTest}</b> (corej-core), whose cases drive synthetic fixtures
 * such as {@code $never_defined} through the error channel and the inliner lockstep, plus
 * {@code NotExecutableParksRuleLoadTest} for the parking itself. <b>Do not delete either sibling on
 * the grounds that this one covers the corpus.</b>
 * </p>
 */
class ShippedRulesDanglingOperandCorpusTest
{

    /**
     * The load-finding fragment {@code validateOperationReferences} emits. ⚠ It read
     * {@code "which no Operations entry defines"} until 2026-10-15, after the loader had been
     * reworded to {@code "which no binding defines"} (the Operations-to-Bindings move), so this
     * ratchet matched nothing and passed over any dangling operand. Found by finding A1 of
     * {@code FINDINGS-unowned-residuals}; {@link #theRatchetIsLive_positiveControl} now reds on the
     * next rewording.
     */
    private static final String MARKER = "which no binding defines";

    @Test
    void expressionCorpus_hasNoDanglingOperandErrors() throws IOException
    {
        assertFolder(CorpusPaths.rules());
    }


    /**
     * The positive control: the zero above is only evidence while {@link #MARKER} still matches
     * what the loader emits. A synthetic rule whose {@code Check} reads an operand no binding
     * defines goes through the same {@code RulePackageLoader} entry point and must be caught by the
     * same literal. If the loader's message is reworded, this test reds instead of the ratchet
     * silently matching nothing.
     *
     * @throws IOException
     *             never in practice: the probe is an in-memory string
     */
    @Test
    void theRatchetIsLive_positiveControl() throws IOException
    {
        RulePackage pkg = RulePackageLoader.loadFromString("""
                {"rules": {"probe": {
                  "Core": {"Id": "PROBE-DANGLING"},
                  "Scope": {"Domains": {"Include": ["AE"]}},
                  "Check": {"expression": "AETERM == $never_defined"}
                }}}
                """);
        Rule probe = pkg.getRules().values().iterator().next();
        assertNotNull(probe.getLoadError(),
                "the pass that produces the zero above must still detect a dangling operand");
        assertTrue(probe.getLoadError().contains(MARKER),
                "the loader's dangling-operand message no longer contains the ratchet's MARKER '"
                        + MARKER + "', so the corpus ratchet matches nothing. Message: "
                        + probe.getLoadError());
    }


    private static void assertFolder(Path folder) throws IOException
    {
        List<String> errors = new ArrayList<>();
        int packages = 0;
        int rules = 0;
        for (Path file : listRulePackages(folder))
        {
            packages++;
            RulePackage pkg = RulePackageLoader.load(file);
            for (Rule rule : pkg.getRules().values())
            {
                if (rule == null)
                {
                    continue;
                }
                rules++;
                String loadError = rule.getLoadError();
                if (loadError != null && loadError.contains(MARKER))
                {
                    errors.add(file.getFileName() + "/" + ruleId(rule) + ": " + loadError);
                }
            }
        }
        // Guard the guard: without these, an empty or half-read corpus would report a clean
        // ratchet — the exact failure mode this whole pass exists to prevent.
        // 56 -> 45 and 13900 -> 12700 (PLAN-retire-core-family phase 6): 58 -> 47 packages and
        // 14903 -> 12818 fan-out entries. ⚑ The prose at :103 said "14 035 entries today" against
        // a measured 14 903 even before this change — the stale-javadoc trap, in the same file as
        // the pin. Corrected below.
        assertTrue(packages >= 45, "expected the full shipped corpus in " + folder + "/, saw "
                + packages + " packages");
        // 14035 entries today; the floor matches CrossCorpusDerivationTest's rather than the
        // looser >= 3000 of the sibling enum gate, which a 78%-truncated corpus would still clear.
        // 12 690 on 2026-10-07 after PLAN-message-folded-corpus-alignment retired 42 absent-column
        // sisters (one package entry each per package they shipped in).
        assertTrue(rules > 12600,
                "expected the full shipped corpus in " + folder + "/, saw " + rules + " rules");
        // ⚠ Ratchets at zero, with no expected-set escape hatch. Since Fix #159 there is no
        // Executability that would move a finding off this channel: `Not Executable` parks the
        // rule instead, so a rule reaching here has claimed to be executable and is not. Fix the
        // rule (give it real operands, as Fix #147 did) or park it — do not re-add a baseline set.
        assertTrue(errors.isEmpty(), "shipped EXECUTABLE rules whose Check references an operand no"
                + " Operations entry defines — such a rule reports nothing whatever the data says"
                + " (" + errors.size() + "):\n" + String.join("\n", errors));
    }


    private static List<Path> listRulePackages(Path folder) throws IOException
    {
        Path dir = folder;
        try (Stream<Path> files = Files.list(dir))
        {
            return files.filter(p ->
            {
                String n = p.getFileName().toString();
                return n.startsWith("rules-") && n.endsWith(".json") && !n.endsWith("-expr.json");
            }).sorted().toList();
        }
    }


    private static String ruleId(Rule rule)
    {
        if (rule.getCore() != null && rule.getCore().getId() != null)
        {
            return rule.getCore().getId();
        }
        return rule.getId() != null ? rule.getId() : "<unknown>";
    }
}
