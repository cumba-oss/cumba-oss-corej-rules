package net.cumba.corej.core;

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
 * {@code DanglingOperationReferenceLoadTest} (corej-cdisc-core), not by the corpus. The floors
 * below are what keeps <em>this</em> pass honest.
 * </p>
 *
 * <p>
 * <b>There is no longer a warning half — the channel itself is gone ({@code Fix #159}).</b> This
 * test used to also collect a {@code loadWarning} bucket against an {@code EXPECTED_WARNINGS} set,
 * because {@code validateOperationReferences} downgraded its finding to a warning for a rule
 * declaring {@code Executability: "Not Executable"}. That set held {@code CDISC-AD0591} and
 * {@code CDISC-AD0898} (the T3 ADaM engine gaps, {@code engine-gap-consolidation.md} §T3), emptied
 * legitimately when {@code Fix #147} made both {@code Fully Executable}. {@code Fix #159} then made
 * {@code Executability: "Not Executable"} <em>park</em> a rule — it is dropped at load before this
 * gate runs — so the downgrade branch was removed and the pass writes to {@code loadError} only.
 * <b>The warning bucket therefore became unreachable by construction, not merely empty in practice,
 * and collecting into it would have been dead code that no corpus state could ever populate.</b>
 * That is the wave-5 {@code M10} shape (a guard that is both unreachable and untestable), so it was
 * removed rather than left standing at {@code Set.of()}. ⚠ Do not reinstate it without first
 * reinstating a producer.
 * </p>
 *
 * <p>
 * ⚠ <b>What this test can and cannot prove.</b> It fails loudly the moment a dangling operand
 * appears anywhere in the shipped corpus — verified by pointing it at a copy of the corpus with one
 * such rule injected (see {@code plans/done/PLAN-not-executable-parks-a-rule.md} phase 3). What it
 * cannot do is demonstrate that {@code validateOperationReferences} detects anything at all: with
 * the corpus clean, a fully neutered pass would still satisfy every assertion below. <b>That proof
 * lives exclusively in {@code DanglingOperationReferenceLoadTest}</b> (corej-cdisc-core), whose
 * cases drive synthetic fixtures such as {@code $never_defined} through the error channel and the
 * inliner lockstep, plus {@code NotExecutableParksRuleLoadTest} for the parking itself. <b>Do not
 * delete either sibling on the grounds that this one covers the corpus.</b>
 * </p>
 */
class ShippedRulesDanglingOperandCorpusTest
{

    /** The load-finding fragment {@code validateOperationReferences} emits. */
    private static final String MARKER = "which no Operations entry defines";

    @Test
    void expressionCorpus_hasNoDanglingOperandErrors() throws IOException
    {
        assertFolder(CorpusPaths.rules());
    }


    private static void assertFolder(Path folder) throws IOException
    {
        List<String> errors = new ArrayList<>();
        int packages = 0;
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
                String loadError = rule.getLoadError();
                if (loadError != null && loadError.contains(MARKER))
                {
                    errors.add(file.getFileName() + "/" + ruleId(rule) + ": " + loadError);
                }
            }
        }
        // ⚠ The corpus-SIZE floor that stood here was removed when this repository was
        // extracted: it asserted the FULL internal corpus (>= 56 packages), which this
        // subset deliberately is not. The internal repository still enforces the size.
        // What is kept is the non-vacuity half, which is subset-safe: an empty or
        // mis-resolved corpus must red rather than report a clean pass over nothing.
        assertTrue(packages > 0, "no rule packages found in " + folder + "/ —"
                + " the corpus did not generate; a pass over nothing is not a check");
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
