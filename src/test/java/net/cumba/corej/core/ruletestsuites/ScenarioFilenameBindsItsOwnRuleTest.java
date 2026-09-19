package net.cumba.corej.core.ruletestsuites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ⭐⭐ A {@code .cdt} scenario's <b>filename, directory and family subtree</b> must all agree with
 * the rule its {@code #test} directive <b>binds</b>.
 *
 * <p>
 * ⛔⛔ <b>Why this exists: a within-family name/id disagreement runs GREEN against the wrong rule and
 * MIS-ATTRIBUTES coverage, and until 2026-09-19 nothing in this repository checked it.</b>
 * {@code rulespec/} has had {@link net.cumba.corej.core.rulespec.SpecFilenameBindsItsOwnRuleTest}
 * since a re-key landed {@code CORE-000852}'s spec as {@code CDISC-CG0330.yaml} while it bound
 * {@code CDISC-CG0664} with nothing reddening. The {@code .cdt} corpus — <b>8 564 files here</b>,
 * an order of magnitude larger — had <em>no analogue</em>. The invariant held tree-wide anyway, but
 * <b>by convention only</b>, and convention is not a check: this tree's signature defect is a
 * population discovered by a convention rather than asserted.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>A CROSS-family mismatch already reds; a WITHIN-family one does not — that asymmetry is the
 * whole gap.</b> {@link AbstractRuleTestSuitesFactory} resolves the {@code #test} id against
 * {@code rules-<family>-*.json}, so {@code fda/FDA-SD0080/CDISC-CG0001-valid-AE.cdt} fails
 * {@code assertNotNull(rule, …)} — the id is not in the FDA packages. But
 * {@code fda/FDA-SD0080/FDA-SD0082-valid-AE.cdt} resolves perfectly: the scenario executes
 * {@code FDA-SD0082} while every human reading the tree, and every path-based citation, believes it
 * is {@code FDA-SD0080}'s. ⛔ And {@link net.cumba.corej.core.ScenarioFixtureCoverageRatchetTest}
 * cannot see it either, because it counts coverage <b>by the {@code #test} id</b> and never looks
 * at the name — so the ratchet reports the wrong rule covered and the right one uncovered, at the
 * same total.
 * </p>
 *
 * <p>
 * ⭐ <b>The immediate reason it was built:</b> {@code PLAN-retire-core-family} phase 5 rewrote
 * exactly that pair — filename <em>and</em> {@code #test} directive — <b>399 times</b> in one
 * change, migrating CORE-family fixtures onto surviving twins. A single mismatched pair among 399
 * would have been silent. (It also, in phase 6, migrated five more: three onto {@code CDISC-CG0370}
 * and two onto {@code CDISC-CG0211}.)
 * </p>
 *
 * <h2>The four invariants</h2>
 *
 * <p>
 * For every {@code .cdt} under {@code src/test/resources/net/cumba/corej/core/ruletestsuites/}:
 * </p>
 *
 * <ol>
 * <li>it carries the {@code #!RuleTest} shebang — without it
 * {@link AbstractRuleTestSuitesFactory#scenarios()} <em>silently skips the file</em> (its own
 * javadoc says so), so an unshebanged scenario is an unexercised one;</li>
 * <li>it carries a {@code #test <id>} directive at all;</li>
 * <li>its <b>parent directory</b> is named exactly {@code <id>};</li>
 * <li>its <b>filename</b> begins {@code <id>-} — a prefix test, because the suffix is the scenario
 * name ({@code -valid-AE}, {@code -invalid_no_epoch-DS-1});</li>
 * <li>its <b>family subtree</b> matches {@code <id>}'s family token.</li>
 * </ol>
 *
 * <p>
 * ⚠ Invariant 4 is a <b>prefix</b> test and invariant 3 an <b>equality</b>, and that combination is
 * what makes the letter-suffixed ids safe: {@code cdisc/CDISC-SEND-0324-A/} holds
 * {@code CDISC-SEND-0324-A-*.cdt} and never {@code CDISC-SEND-0324-*.cdt}, because the directory
 * equality pins which of the two rules owns the file. A prefix test alone would let
 * {@code CDISC-SEND-0324}'s scenarios sit in its sibling's directory.
 * </p>
 *
 * <h2>⛔ Exceptions are an EXACT SET, and today it is EMPTY</h2>
 *
 * <p>
 * Measured 2026-09-19 over all <b>8 564</b> scenarios in THIS repository: <b>zero</b> violations of
 * any of the five checks. So {@link #EXPECTED_EXCEPTIONS} is empty, and it is a {@code Map} rather
 * than a count so that an added exception, a removed one, <em>and</em> an existing one whose
 * binding moves are all red. ⛔ Never widen this to a prefix allowlist or a tolerated count — a
 * blind allowlist is precisely the defect class this guard was added to close. An empty exact set
 * is the strongest form the pin can take, and {@link #MIN_SCENARIOS} plus
 * {@link #theDetectorCatchesEveryShapeItClaimsTo(Path)} are what stop it passing vacuously.
 * </p>
 *
 * <p>
 * ⚑ There is exactly <b>one</b> {@code #test} parser in this package and this guard uses it:
 * {@link AbstractRuleTestSuitesFactory#peekCoreId} itself, made package-visible for the purpose. A
 * local re-implementation is what the H-5 finding on the internal twin's
 * {@code OrderFlipGuardCoverageRatchetTest} cost — two predicates, and the guard used the one the
 * factory did not. (That ratchet is internal-only; the lesson is not.)
 * </p>
 *
 * <p>
 * ⭐ <b>PORTED from the internal twin 2026-09-19</b>, with its exception set and its population
 * floor RE-DERIVED against this corpus rather than copied. Review round 1 recorded that this
 * repository had neither this guard nor its {@code rulespec} sibling while holding 8 563
 * {@code .cdt} and 971 rulespecs — so a name/binding disagreement here ran green against the wrong
 * rule with nothing reporting it. Both guards now exist on both sides. ⛔ Do not "align" the
 * constants below to the internal twin's: the two corpora are different populations, and an
 * exception set copied rather than measured is an allowlist.
 * </p>
 */
class ScenarioFilenameBindsItsOwnRuleTest
{

    /**
     * ⛔ Path (relative to {@code ruletestsuites/}) → what is wrong with it, for every scenario
     * whose name, directory or subtree deliberately disagrees with the rule it binds. <b>Exact set.
     * Empty today.</b> See the class javadoc for why it must not become an allowlist.
     */
    private static final Map<String, String> EXPECTED_EXCEPTIONS = Map.of();

    /**
     * Family subtree → the id prefix a scenario in it must carry.
     *
     * <p>
     * ⚑ A fifth entry, {@code "core" -> "CORE-"}, went with the family in 2026-09
     * ({@code PLAN-retire-core-family} §12.2). ⛔ The map is compared against the subtrees actually
     * on disk by {@link #everyFamilySubtreeOnDiskIsDeclaredHere()}, so a new family subtree that is
     * not declared here reds rather than being skipped — the alternative (iterate this map) would
     * have made an undeclared subtree INVISIBLE, which is the same blindness in a new place.
     * </p>
     */
    private static final Map<String, String> FAMILY_PREFIX = Map.of("cdisc", "CDISC-", "draft",
            "DRAFT-", "fda", "FDA-", "pmda", "PMDA-");

    /**
     * ⛔ Non-vacuity floor on the population inspected. <b>8 564 scenarios in this repository on
     * 2026-09-19</b>, against the internal twin's 8 661. A run that inspects fewer has read the
     * wrong root or a truncated tree and would pass over anything — fix the path, never the floor.
     *
     * <p>
     * ⚑ The two corpora differ by construction and are expected to: this repository does not
     * redistribute the CORE rule family, and it lacks the internal-only scenarios other plans
     * authored (their OSS port is owed by {@code PLAN-typed-engine-oss-port.md}), while carrying
     * six {@code -A} siblings the internal tree retired. ⛔ So the gap is not drift to be closed by
     * editing this number towards the internal one.
     * </p>
     *
     * <p>
     * ⚠ It is a floor and not a pin on purpose: the exact scenario count is
     * {@link net.cumba.corej.core.ScenarioFixtureCoverageRatchetTest}'s business, and duplicating
     * it here would make every fixture addition two edits. What this guard owns is the
     * <em>shape</em> of every file it sees, not how many there are.
     * </p>
     */
    private static final int MIN_SCENARIOS = 8000;

    private static Path root()
    {
        return Path.of(System.getProperty("projectBasedir"),
                "src/test/resources/net/cumba/corej/core/ruletestsuites");
    }


    /**
     * Every scenario's filename, directory and family subtree agree with the rule its {@code #test}
     * directive binds — with the exceptions pinned as an exact set.
     */
    @Test
    void everyScenarioFilenameAndDirectoryBindTheRuleItsTestDirectiveNames() throws IOException
    {
        Path base = root();
        List<String> inspected = new ArrayList<>();
        List<String> disagreeing = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base))
        {
            List<Path> files = walk.filter(p -> p.getFileName().toString().endsWith(".cdt"))
                    .sorted().toList();
            for (Path p : files)
            {
                String rel = base.relativize(p).toString().replace('\\', '/');
                inspected.add(rel);
                disagreeing.addAll(problemsWith(base, p, rel));
            }
        }

        assertTrue(inspected.size() >= MIN_SCENARIOS,
                "only " + inspected.size() + " scenario(s) were inspected under " + base
                        + ", below the floor of " + MIN_SCENARIOS + " — the walk is not seeing the"
                        + " corpus, so a pass here would mean nothing. Fix the path, never the"
                        + " floor.");

        List<String> expected = new ArrayList<>();
        EXPECTED_EXCEPTIONS.forEach((path, why) -> expected.add(path + ": " + why));
        expected.sort(null);
        disagreeing.sort(null);

        assertEquals(expected, disagreeing,
                () -> "a .cdt scenario's filename, directory and family subtree must agree with the"
                        + " rule its #test directive BINDS, and the exceptions are pinned as an"
                        + " exact set.\n"
                        + "⛔ A line here that is not in EXPECTED_EXCEPTIONS is almost always a"
                        + " migration whose filename was computed before the #test id was"
                        + " retargeted (or the reverse). Within one family that is INVISIBLE to"
                        + " every other instrument: AbstractRuleTestSuitesFactory resolves the id"
                        + " and runs the OTHER rule green, and"
                        + " ScenarioFixtureCoverageRatchetTest counts coverage by the id, so it"
                        + " credits the wrong rule at an unchanged total.\n"
                        + "⇒ Fix the FILE (rename it, or move it into its own rule's directory)."
                        + " Adding an EXPECTED_EXCEPTIONS entry is almost never the repair.\n"
                        + "A line MISSING is an exception that no longer holds: delete its entry"
                        + " rather than restoring the old name.");
    }


    /** Every family subtree on disk is declared in {@link #FAMILY_PREFIX}. */
    @Test
    void everyFamilySubtreeOnDiskIsDeclaredHere() throws IOException
    {
        List<String> onDisk;
        try (Stream<Path> children = Files.list(root()))
        {
            onDisk = children.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .sorted().toList();
        }
        assertEquals(FAMILY_PREFIX.keySet().stream().sorted().toList(), onDisk,
                "the family subtrees under ruletestsuites/ and FAMILY_PREFIX have diverged. An"
                        + " UNDECLARED subtree is the dangerous direction: this guard would have"
                        + " no prefix to check its scenarios against, so every file in it would"
                        + " skip invariant 5 silently. A declared-but-absent one means a family"
                        + " was retired without its entry — as 'core' was in 2026-09.");
    }


    /**
     * ⛔ Sensitivity arm — it runs on EVERY invocation, because the assertion above is worth its
     * greenness only if the detector is proved to detect. Each of the five shapes is built as a
     * real file in a temporary tree and must be reported; two conformant shapes must not be.
     *
     * <p>
     * ⚠ The <b>within-family</b> case is the one that matters: {@code fda/FDA-SD0080/} holding
     * {@code FDA-SD0082-valid-AE.cdt} is exactly the mismatch no other instrument sees.
     * </p>
     */
    @Test
    void theDetectorCatchesEveryShapeItClaimsTo(@TempDir Path aTmp) throws IOException
    {
        // conformant — must be silent
        assertNoProblem(aTmp, "fda/FDA-SD0080/FDA-SD0080-valid-AE.cdt", "FDA-SD0080");
        assertNoProblem(aTmp, "cdisc/CDISC-SEND-0324-A/CDISC-SEND-0324-A-valid-BW.cdt",
                "CDISC-SEND-0324-A");

        // 1. no shebang — the factory silently skips it, so the scenario is unexercised
        Path unshebanged = write(aTmp, "fda/FDA-SD0080/FDA-SD0080-valid-AE.cdt",
                "#test FDA-SD0080 expect=noViolation domain=AE\n");
        assertTrue(
                problemsWith(aTmp, unshebanged, "x").stream().anyMatch(s -> s.contains("shebang")),
                "SENSITIVITY FAILED: a .cdt without the " + RuleTestCdt.SHEBANG
                        + " shebang must be reported — the factory skips it silently");

        // 2. no #test directive at all
        Path untested = write(aTmp, "fda/FDA-SD0081/FDA-SD0081-valid-AE.cdt",
                RuleTestCdt.SHEBANG + "\n#note \"no test directive\"\n");
        assertTrue(problemsWith(aTmp, untested, "x").stream().anyMatch(s -> s.contains("#test")),
                "SENSITIVITY FAILED: a .cdt with no #test directive must be reported");

        // 3. ⭐⭐ THE WITHIN-FAMILY MISMATCH THIS GUARD EXISTS FOR, in its real shape: a migration
        // retargeted the #test directive and did NOT rename the file or move it. The scenario
        // resolves perfectly against the FDA packages and executes FDA-SD0082, while its name and
        // its directory both still say FDA-SD0080. BOTH invariants must fire.
        Path wrongRule = write(aTmp, "fda/FDA-SD0080/FDA-SD0080-valid-AE.cdt",
                RuleTestCdt.SHEBANG + "\n#test FDA-SD0082 expect=noViolation domain=AE\n");
        List<String> both = problemsWith(aTmp, wrongRule, "x");
        assertEquals(2, both.size(),
                () -> "SENSITIVITY FAILED: a scenario BINDING FDA-SD0082 while its name and its"
                        + " directory both say FDA-SD0080 must be reported on BOTH the directory"
                        + " and the filename invariant — this is the mismatch that runs green"
                        + " against the wrong rule and mis-attributes its coverage. Got: " + both);

        // 4. ⚠ The complement, and it is NOT symmetric: the file was renamed onto the new id but
        // left in the old rule's directory. Only the DIRECTORY invariant can catch this one — the
        // filename prefix agrees with the binding — which is why invariant 3 is an equality
        // rather than a second prefix test.
        Path wrongDir = write(aTmp, "fda/FDA-SD0080/FDA-SD0082-valid-AE.cdt",
                RuleTestCdt.SHEBANG + "\n#test FDA-SD0082 expect=noViolation domain=AE\n");
        List<String> dirOnly = problemsWith(aTmp, wrongDir, "x");
        assertEquals(1, dirOnly.size(),
                () -> "SENSITIVITY FAILED: expected exactly the directory invariant to fire for a"
                        + " correctly-named file in the wrong rule's directory. Got: " + dirOnly);
        assertTrue(dirOnly.get(0).contains("directory"),
                () -> "SENSITIVITY FAILED: the one problem must be the DIRECTORY one. Got: "
                        + dirOnly);

        // 5. wrong family subtree — this one DOES red at resolution time too, but the guard must
        // name it as a naming defect rather than leaving it to a resolution failure
        Path wrongFamily = write(aTmp, "fda/CDISC-CG0001/CDISC-CG0001-valid-AE.cdt",
                RuleTestCdt.SHEBANG + "\n#test CDISC-CG0001 expect=noViolation domain=AE\n");
        assertTrue(
                problemsWith(aTmp, wrongFamily, "x").stream().anyMatch(s -> s.contains("family")),
                "SENSITIVITY FAILED: a CDISC id under fda/ must be reported");
    }


    private static void assertNoProblem(Path aBase, String aRel, String aId) throws IOException
    {
        Path p = write(aBase, aRel,
                RuleTestCdt.SHEBANG + "\n#test " + aId + " expect=noViolation domain=AE\n");
        assertEquals(List.of(), problemsWith(aBase, p, aRel),
                "SENSITIVITY FAILED: " + aRel + " is conformant and must NOT be reported — a"
                        + " detector that flags everything proves nothing");
    }


    private static Path write(Path aBase, String aRel, String aContent) throws IOException
    {
        Path p = aBase.resolve(aRel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, aContent, StandardCharsets.UTF_8);
        return p;
    }


    /**
     * Every invariant {@code aFile} breaks, as one message each; empty when it is conformant.
     *
     * @param aBase
     *            the {@code ruletestsuites/} root {@code aFile} lives under
     * @param aFile
     *            the scenario
     * @param aRel
     *            {@code aFile} relative to {@code aBase}, for the messages
     * @return the problems, possibly empty
     */
    private static List<String> problemsWith(Path aBase, Path aFile, String aRel) throws IOException
    {
        List<String> out = new ArrayList<>();
        if (!RuleTestCdt.hasShebang(Files.readString(aFile)))
        {
            out.add(aRel + ": no " + RuleTestCdt.SHEBANG + " shebang — the factory SILENTLY skips"
                    + " it, so this scenario is never executed");
            return out;
        }
        String id = AbstractRuleTestSuitesFactory.peekCoreId(aFile);
        if (id == null)
        {
            out.add(aRel + ": no '#test <id>' directive — the factory cannot bind it to a rule");
            return out;
        }
        String dir = aFile.getParent().getFileName().toString();
        if (!dir.equals(id))
        {
            out.add(aRel + ": directory '" + dir + "' but #test binds '" + id + "'");
        }
        String name = aFile.getFileName().toString();
        if (!name.startsWith(id + "-"))
        {
            out.add(aRel + ": filename does not begin '" + id + "-' but #test binds '" + id + "'");
        }
        String family = aBase.relativize(aFile).getName(0).toString();
        String prefix = FAMILY_PREFIX.get(family);
        if (prefix != null && !id.startsWith(prefix))
        {
            out.add(aRel + ": family subtree '" + family + "' requires an id starting '" + prefix
                    + "' but #test binds '" + id + "'");
        }
        return out;
    }
}
