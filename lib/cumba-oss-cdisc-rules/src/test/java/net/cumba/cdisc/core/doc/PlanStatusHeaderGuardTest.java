package net.cumba.cdisc.core.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Structural guard on the status header of every {@code plans/*.md} file.
 *
 * <p>
 * <b>Why this exists.</b> {@code STANDING-RULINGS.md} §"Who updates a PLAN's status header? Nobody
 * — and it has cost four times" adopted the rule <i>"the lane that implements a plan updates that
 * plan's own status header, in the same change"</i>. The rule was not holding: the wave-30 audit
 * ({@code plans/done/PLAN-plans-audit.md}) found <b>42–43</b> wrong headers out of 224 claims, and
 * the understating direction — shipped work still advertising itself as <i>proposal / ready to cut
 * / not started</i> — dominated 8:1. Eight of those were <b>wave-manifest plans consumed by the
 * very wave they name</b>. The cause is not carelessness: <b>nothing checked it</b>. This is that
 * check.
 * </p>
 *
 * <p>
 * <b>Design constraint — it must not become the next stale artifact.</b> A guard carrying a
 * hand-maintained allowlist of today's 42 exceptions would rot exactly as the headers did. So every
 * assertion here is either <em>structural</em> (a file contradicting itself, or contradicting the
 * fix ledger) or a <em>single integer ratchet</em>. There is no per-file allowlist anywhere in this
 * class, and adding one would defeat its purpose.
 * </p>
 *
 * <h2>What the four checks catch</h2>
 * <ol>
 * <li><b>Presence ratchet</b> — every plan must carry a status statement in the first
 * {@value #HEADER_WINDOW} lines. Shrink-only; a <em>new</em> plan filed without a header fails
 * immediately.</li>
 * <li><b>Self-contradiction</b> — a plan whose status region claims a pre-execution state
 * (<i>proposal / ready to cut / not started / no go / scheduled / design only …</i>) and carries
 * <b>no</b> shipped marker at all, while the same file holds its own {@code # Results} section.
 * Zero tolerance: the file already contains the evidence that its own header is false.</li>
 * <li><b>Spent wave manifests</b> — {@code PLAN-wave<N>-*.md} for a wave the fix ledger already
 * records as <em>landed</em> cannot still say "ready to cut". Zero tolerance; the ledger is the
 * oracle, so this self-updates as waves land. ⚠ <b>"records as landed" is not "mentions"</b>: a
 * bare mention scrape marked a wave spent on a <em>forward</em> reference and reddened two waves'
 * builds with no lane able to clear it. {@link #spentWaves(List)} therefore requires a shipped
 * context and vetoes any forward-looking line — see {@link #LEDGER_FORWARD}.</li>
 * <li><b>Transient tree-state markers ratchet</b> — {@code UNCOMMITTED} / <i>in a worktree</i> /
 * <i>awaiting patch-back</i> in a status region is by construction a statement with a short
 * half-life. Shrink-only.</li>
 * </ol>
 *
 * <h2>⚠ What this guard structurally CANNOT catch — stated plainly</h2>
 * <ul>
 * <li><b>Overstates.</b> A header claiming SHIPPED for work that shipped nothing leaves no
 * structural trace — absence of evidence is not evidence. All three overstates the wave-30 audit
 * found ({@code PLAN-adam-structure-set-valued.md}, {@code PLAN-rule-inventory.md},
 * {@code PLAN-generator-silencing-guard-hoist.md}) are invisible here. The guard is deliberately
 * asymmetric, in the direction that dominates 8:1.</li>
 * <li><b>Stale counts.</b> "787 rules migrated", "26 injectors", "34 {@code Sensitivity: Study}
 * rules" — a number decays silently and only a generator per number would notice. See
 * {@link RuleDocCensusDriftTest} for the shape that <em>does</em> pin numbers, and note it takes a
 * whole generator to do it for one document.</li>
 * <li><b>Dead file-path and symbol citations.</b> Deliberately not attempted: plan prose cites
 * paths that were <em>correctly</em> deleted, paths inside quoted history, and paths in other
 * repositories, so a path-existence sweep is dominated by false positives.</li>
 * <li><b>A stale header on a plan that has no {@code # Results} section and is not a wave
 * manifest.</b> Nothing in the tree contradicts it. Roughly half of the wave-30 audit's understates
 * are of this shape.</li>
 * <li><b>A ratchet number that stays put while its membership churns.</b> Both ratchets are counts,
 * not sets: one plan gaining a header while another loses one is invisible.</li>
 * </ul>
 *
 * <p>
 * Measured at {@code 43531d6bf} (before the wave-31 lane G corrections), the four checks flagged
 * <b>12</b> of the wave-30 audit's 43 rows — <b>plus 2 the audit itself missed</b>
 * ({@code PLAN-adam-standards-linkage-oracle.md}, {@code PLAN-guard-emission-relocation.md}).
 * </p>
 *
 * @see RuleDocCensusDriftTest
 */
class PlanStatusHeaderGuardTest
{

    /** A reader looks at the top of the file. A status statement below this line is not found. */
    private static final int HEADER_WINDOW = 20;

    /**
     * Shrink-only ratchet: plans with no status statement in the first {@value #HEADER_WINDOW}
     * lines. Measured 2026-08-11 (wave-31 lane G). <b>Lower it when you add a header; never raise
     * it.</b>
     */
    private static final int MAX_PLANS_WITHOUT_HEADER = 2;

    /*
     * ⚑ 26 → 5 on 2026-08-31. The plans-housekeeping pass found that most "statusless" plans were
     * not statusless at all — they carried a status statement BELOW this 20-line window, so the
     * guard could not see it and neither could any sweep. Nine were hoisted, six more were marked
     * honestly as UNSTATED with the shipping evidence found for them, and the rest gained a real
     * header. Never raise this back.
     */

    /**
     * Shrink-only ratchet: status regions still carrying a transient tree-state marker
     * ({@code UNCOMMITTED}, <i>in a worktree</i>, <i>awaiting patch-back</i>). Measured 2026-08-11
     * (wave-31 lane G). The target is <b>0</b> — every one of these is a claim with a half-life of
     * days. <b>Lower it when you land one; never raise it.</b>
     */
    private static final int MAX_PLANS_WITH_TRANSIENT_TREE_STATE = 4;

    /**
     * ⚠ Sanity floor. If the {@code plans/} directory failed to resolve, every assertion below
     * would pass vacuously over an empty list — the classic way a guard becomes a zero-assert.
     */
    private static final int MIN_PLAN_FILES = 200;

    /**
     * The four spellings in use, unified. {@code lead} is any run of non-alphanumerics — this is
     * what covers {@code > **Status:}, {@code ## Status}, {@code > # ✅ **Status:} and
     * {@code > ## ✅ STATUS:} without enumerating emoji. The optional qualifier word covers
     * {@code ## Implementation status — COMPLETE}.
     *
     * <p>
     * ⚠ A remembered spelling always undercounts here: the corpus has <b>no enforced spelling</b>,
     * and the wave-30 audit moved its own "no header" count 36 → 28 → 21 across three regex
     * iterations, every intermediate number stated confidently.
     * </p>
     */
    private static final Pattern STATUS_LINE = Pattern.compile("(?<lead>[^A-Za-z0-9]*)"
            + "(?:(?:implementation|current|overall|plan|execution|wave|final|revised|updated)"
            + "[ \\t]+)?" + "\\**[ \\t]*status\\b\\**(?<after>.*)", Pattern.CASE_INSENSITIVE);

    /**
     * What must follow the word for an undecorated line to be a status statement rather than prose
     * that merely starts with "status" ({@code status, violations, expandedFor} — a DTO field list
     * — and {@code status with a growing progress view} are both real lines in this corpus).
     */
    private static final Pattern SEPARATOR = Pattern.compile("^[ \\t]*[:\u2012\u2013\u2014-]");

    /** Any first-window line mentioning the word joins the status region. */
    private static final Pattern STATUS_WORD = Pattern.compile("\\bstatus\\b",
            Pattern.CASE_INSENSITIVE);

    /** States that assert the work has <b>not</b> run. */
    private static final Pattern PRE_EXECUTION = Pattern.compile(
            "NOT\\s+(?:YET\\s+)?STARTED" + "|READY\\s+TO\\s+CUT" + "|READY\\s+FOR\\s+A\\s+LANE"
                    + "|LANE-READY" + "|\\bPROPOSAL\\b" + "|\\bTASK\\s+LIST\\b" + "|\\bMANIFEST\\b"
                    + "|\\bSCHEDULED\\b" + "|\\bNO\\s+GO\\b"
                    + "|AWAITING\\s+(?:AN?\\s+|THE\\s+)?(?:EXPLICIT\\s+)?(?:GO|CUT)"
                    + "|NOTHING\\s+IMPLEMENTED" + "|\\bDESIGN\\s+ONLY\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * ⚠ Stripped before {@link #SHIPPED} is applied. Without this, <i>"Nothing implemented"</i> —
     * the most emphatic pre-execution phrase in the corpus — reads as a shipped marker and silences
     * the whole check for that file. Same family as the repo's standing warning that
     * {@code is_complete_date} is a substring of {@code is_not_complete_date_part}.
     */
    private static final Pattern NEGATED_SHIPPED = Pattern.compile(
            "\\b(?:nothing|not(?:\\s+yet)?)\\s+(?:implemented|shipped|executed|done|started)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * States that assert something ran. A status region carrying any of these is <b>not</b>
     * flagged, even alongside a pre-execution word — that is the ordinary shape of a
     * partially-shipped plan ("Phase 0 COMPLETE, phase 1 LANE-READY"), which is an honest header,
     * not a stale one.
     */
    private static final Pattern SHIPPED = Pattern.compile("\\bIMPLEMENTED\\b" + "|\\bSHIPPED\\b"
            + "|\\bSPENT\\b" + "|\\bCOMPLETE" + "|\\bEXECUTED\\b" + "|\\bDONE\\b" + "|\\bCLOSED\\b"
            + "|\\bLANDED\\b" + "|\\bMEASURED\\b" + "|\\bSUPERSEDED\\b" + "|\\bWITHDRAWN\\b"
            + "|\\bOBSOLETE\\b" + "|\\bRETIRED\\b" + "|\\bDELIVERED\\b" + "|\\bREVERTED\\b"
            + "|\\bHISTORY\\b" + "|\\bHISTORICAL\\b", Pattern.CASE_INSENSITIVE);

    /** A plan's own record that it ran. Decoration between the hashes and the word is common. */
    private static final Pattern RESULTS_HEADING = Pattern
            .compile("#{1,4}[^A-Za-z0-9]*results\\b.*", Pattern.CASE_INSENSITIVE);

    /** Tree-state claims that are true for days, then quietly false forever. */
    private static final Pattern TRANSIENT_TREE_STATE = Pattern.compile(
            "\\bUNCOMMITTED\\b" + "|\\bin (?:a|the) [^.]{0,40}worktree\\b"
                    + "|awaits?(?:ing)? patch-back" + "|not patched back",
            Pattern.CASE_INSENSITIVE);

    /**
     * Wave-manifest plans name their wave in the filename; that is the whole oracle for check 3.
     */
    private static final Pattern WAVE_MANIFEST = Pattern.compile("PLAN-wave(\\d+)-.*\\.md");

    /**
     * A wave mention in the fix ledger. &#9888; On its own this is <b>not</b> an oracle for "spent"
     * &mdash; see {@link #isShippedContext(String)}.
     */
    private static final Pattern LEDGER_WAVE = Pattern.compile("\\bwave[- ](\\d{1,2})\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * A ledger entry heading. Every one of the 225 headings documents work that <b>landed</b>; the
     * provenance parenthetical (<i>"&mdash; Implemented (wave 34 lane B, 2026-08-11)"</i>) is
     * written when the fix is appended.
     */
    private static final Pattern LEDGER_HEADING = Pattern.compile("^#{2,4}\\s.*");

    /**
     * The lane-provenance form, which predates the heading convention: <i>"Wave-17 lane <b>B</b>,
     * Phases 1+2 of &hellip;"</i>. A lane is named only once it has run.
     */
    private static final Pattern LEDGER_PROVENANCE = Pattern
            .compile("\\bwave[- ]\\d{1,2}\\s+lane\\b", Pattern.CASE_INSENSITIVE);

    /** A past-tense claim that something was done, and the {@code Fix #} back-reference. */
    private static final Pattern LEDGER_PAST = Pattern.compile("\\bFix\\s*#\\s*\\d+"
            + "|\\b(?:implement(?:ed|s)|shipped|landed|spent|executed|committed|delivered"
            + "|complete[d]?|ran|measured|found|fixed|added|corrected|reverted|refused|verified"
            + "|audited|authored|migrated)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * &#9888;&#9888; <b>The forward-reference veto &mdash; the whole point of this oracle.</b>
     *
     * <p>
     * A ledger entry routinely names a wave that has <em>not</em> run: the wave it hands work off
     * to, the wave a hold is scheduled for, the wave a plan is composed against. A bare
     * {@code \bwave[- ](\d{1,2})\b} scrape marks every one of them spent, and check 3 then reds a
     * manifest for a wave nobody has cut &mdash; <b>with no lane able to fix it</b>, because the
     * only honest header for an uncut wave is a pre-execution one. That has cost two waves: a
     * wave-32 manifest reddened before wave 32 was cut, and in wave 35 the first lane to append its
     * ledger entry reddened <em>every</em> lane's build.
     * </p>
     *
     * <p>
     * &#9776; The veto is per <b>line</b>, and deliberately biased: a line carrying any
     * forward-looking modality contributes nothing, even if it also carries a past-tense marker.
     * That can only ever <em>shrink</em> the spent set, i.e. loosen check 3 &mdash; the safe
     * direction. The failure this exists to stop is a false <b>red</b> that no lane can clear.
     * </p>
     */
    private static final Pattern LEDGER_FORWARD = Pattern.compile("\\b(?:will|would|shall|next"
            + "|upcoming|forthcoming|planned|proposed|scheduled|composed|candidate|earmarked"
            + "|pending|awaiting|future|to\\s+be\\s+cut|not\\s+(?:yet\\s+)?cut|when\\s+wave"
            + "|once\\s+wave|if\\s+wave)\\b", Pattern.CASE_INSENSITIVE);

    private static List<PlanFile> plans;

    private static Set<Integer> spentWaves;

    /** One plan file, reduced to the four things the checks need. */
    private record PlanFile(String name, String header, String statusRegion, String resultsHeading)
    {

        boolean claimsPreExecutionOnly()
        {
            return PRE_EXECUTION.matcher(statusRegion).find() && !SHIPPED
                    .matcher(NEGATED_SHIPPED.matcher(statusRegion).replaceAll("")).find();
        }
    }

    static Path plansDirectory()
    {
        return Path.of(System.getProperty("repoRoot", "..")).resolve("plans");
    }


    static Path fixLedger()
    {
        return Path.of(System.getProperty("projectBasedir", ".")).resolve("documentation")
                .resolve("core-engine-update-status.md");
    }


    @BeforeAll
    static void read() throws IOException
    {
        plans = new ArrayList<>();
        // ⚑ WALK, not list — `plans/done/` holds the shipped plans (retired-board cleanup,
        // 2026-08-31) and they stay under this guard on purpose. A completed plan is exactly the
        // population this test was built for: the audit that motivated it found "complete but
        // claims not started" outnumbering every other error 8:1. Scoping the guard to the top
        // level would drop ~280 files out of it AND force all three ratchets to be re-derived
        // downward, i.e. hand back a measured gain — which #planStatusHeaderRatchet's own failure
        // message forbids.
        try (Stream<Path> files = Files.walk(plansDirectory()))
        {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !"README.md".equals(p.getFileName().toString())).sorted().toList())
            {
                plans.add(parse(file.getFileName().toString(), Files.readAllLines(file)));
            }
        }
        spentWaves = spentWaves(Files.readAllLines(fixLedger()));
    }


    /**
     * The check-3 oracle: every wave the fix ledger names <b>in a shipped context</b>.
     *
     * <p>
     * Package-private and line-based so
     * {@link #aForwardReferenceDoesNotMarkAWaveSpentButARealOneDoes()} can drive it with synthetic
     * ledger text &mdash; an oracle nobody can watch decide is the first thing to rot.
     * </p>
     */
    static Set<Integer> spentWaves(List<String> ledgerLines)
    {
        Set<Integer> spent = new TreeSet<>();
        for (String line : ledgerLines)
        {
            if (!isShippedContext(line))
            {
                continue;
            }
            Matcher waves = LEDGER_WAVE.matcher(line);
            while (waves.find())
            {
                spent.add(Integer.valueOf(waves.group(1)));
            }
        }
        return spent;
    }


    /**
     * Does this ledger line assert that the wave it names has <b>run</b>? A heading, a lane
     * provenance or a past-tense/{@code Fix #}-adjacent claim says yes; any forward-looking
     * modality on the same line vetoes it outright.
     */
    static boolean isShippedContext(String aLine)
    {
        if (LEDGER_FORWARD.matcher(aLine).find())
        {
            return false;
        }
        return LEDGER_HEADING.matcher(aLine).matches() || LEDGER_PROVENANCE.matcher(aLine).find()
                || LEDGER_PAST.matcher(aLine).find();
    }


    private static PlanFile parse(String aName, List<String> aLines)
    {
        List<String> window = aLines.subList(0, Math.min(HEADER_WINDOW, aLines.size()));
        String header = null;
        StringBuilder region = new StringBuilder();
        for (String line : window)
        {
            if (header == null && isStatusStatement(line))
            {
                header = line.strip();
            }
            if (STATUS_WORD.matcher(line).find())
            {
                region.append(line).append('\n');
            }
        }
        String results = null;
        for (String line : aLines)
        {
            if (RESULTS_HEADING.matcher(line).matches())
            {
                results = line.strip();
                break;
            }
        }
        return new PlanFile(aName, header, region.toString(), results);
    }


    /**
     * The detector: is this line a status statement?
     *
     * <p>
     * Package-private so {@link #theDetectorFiresOnEverySpellingInUseAndOnNothingElse()} can neuter
     * it — a detector nobody can watch fail is the first thing to rot.
     * </p>
     */
    static boolean isStatusStatement(String aLine)
    {
        Matcher m = STATUS_LINE.matcher(aLine);
        if (!m.matches())
        {
            return false;
        }
        return SEPARATOR.matcher(m.group("after")).find() || m.group("lead").indexOf('#') >= 0;
    }


    /**
     * ⚠ Without this, every other assertion in the class passes over an empty list.
     */
    @Test
    void thePlansDirectoryAndTheLedgerBothResolved()
    {
        assertTrue(plans.size() >= MIN_PLAN_FILES,
                "plans/ resolved to " + plans.size() + " files (< " + MIN_PLAN_FILES
                        + ") — the directory did not resolve and every check below is vacuous."
                        + " Expected at " + plansDirectory().toAbsolutePath());
        assertFalse(spentWaves.isEmpty(), "the fix ledger named no wave at all — "
                + fixLedger().toAbsolutePath() + " did not resolve, so check 3 is a zero-assert");
        assertTrue(spentWaves.size() >= 20,
                "the ledger yielded only " + spentWaves.size() + " spent wave(s): " + spentWaves
                        + ". More than 30 waves have landed, so the shipped-context oracle has"
                        + " stopped recognising them and check 3 is close to vacuous.");
    }


    /**
     * &#9776; <b>Check 3's oracle, both directions.</b> A forward reference must not mark a wave
     * spent; a real ledger entry must. Asserting only the first would let the oracle degenerate to
     * "nothing is ever spent", which passes check 3 for free.
     *
     * <p>
     * Every line below is the real shape it imitates: the positive is the provenance parenthetical
     * the ledger has used since {@code Fix #217}, and the negatives are the hand-off and
     * composition sentences that reddened the wave-32 and wave-35 manifests.
     * </p>
     */
    @Test
    void aForwardReferenceDoesNotMarkAWaveSpentButARealOneDoes()
    {
        List<String> forward = List.of(
                "⏳ **wave 41 COMPOSED, NOT CUT** — four lanes, five plans, all grounded.",
                "The grouping half is held for wave 42, which will take it alone.",
                "⛔ wave 43 = `compareIso`; scheduled behind the ADaM release.",
                "This is the evidence wave 44's retirement decision is waiting on.",
                "⚠ the remaining 24 are held pending a wave-45 audit of the Java probes.");
        assertEquals(Set.of(), spentWaves(forward),
                "a forward reference marked a wave spent. Check 3 then reds that wave's manifest"
                        + " before the wave is cut, and no lane can clear it: the only honest"
                        + " header for an uncut wave IS a pre-execution one.");
        for (String line : forward)
        {
            assertFalse(isShippedContext(line), "forward-looking line read as shipped: " + line);
        }

        List<String> real = List.of(
                "### Fix #999 — a thing that happened — Implemented (wave 41 lane A, 2026-08-12)",
                "Wave-42 lane **B**, Phases 1+2 of `plans/PLAN-something.md`.",
                "⚑ The three sisters shipped in wave 43 and the ratchet moved +18.",
                "`Fix #1000` (wave-44 lane C) corrected the count from 38 to 34.");
        assertEquals(Set.of(41, 42, 43, 44), spentWaves(real),
                "a real ledger entry stopped marking its wave spent — check 3 has gone loose and a"
                        + " stale \"ready to cut\" manifest can now ship unnoticed");

        // ⚠ The veto is per line and wins over a past-tense marker on the same line. That is the
        // deliberate bias: under-reporting only loosens check 3, over-reporting reds a build that
        // nobody can fix.
        assertEquals(Set.of(),
                spentWaves(List.of(
                        "`Fix #1001` shipped in wave 45; the follow-up is scheduled for wave 46.")),
                "the forward veto must apply to the whole line, both mentions");
    }


    /** Check 1 — presence, shrink-only. */
    @Test
    void everyPlanCarriesAStatusStatementWhereAReaderLooks()
    {
        List<String> without = plans.stream().filter(p -> p.header() == null).map(PlanFile::name)
                .toList();
        assertTrue(without.size() <= MAX_PLANS_WITHOUT_HEADER,
                without.size() + " plans carry no status statement in the first " + HEADER_WINDOW
                        + " lines; the ratchet allows " + MAX_PLANS_WITHOUT_HEADER
                        + ". A plan with no header is indistinguishable from an unstarted proposal."
                        + " Add one — do not raise the ratchet. Offenders: " + without);
        assertEquals(MAX_PLANS_WITHOUT_HEADER, without.size(),
                "the ratchet is now loose: only " + without.size() + " plans lack a status header"
                        + " but MAX_PLANS_WITHOUT_HEADER still says " + MAX_PLANS_WITHOUT_HEADER
                        + ". Lower the constant so the gain cannot be given back silently.");
    }


    /**
     * Check 2 — the file contradicts itself: a pre-execution header over its own results.
     *
     * <p>
     * Zero tolerance, and the remedy is always one line: if a phase shipped, say so in the status
     * region. A genuinely part-shipped plan ("Phase 1 SHIPPED, Phase 2 READY TO CUT") carries a
     * shipped marker and is not flagged.
     * </p>
     */
    @Test
    void noPlanClaimsItHasNotRunWhileCarryingItsOwnResultsSection()
    {
        List<String> offenders = new ArrayList<>();
        for (PlanFile plan : plans)
        {
            if (plan.header() != null && plan.claimsPreExecutionOnly()
                    && plan.resultsHeading() != null)
            {
                offenders.add(plan.name() + "\n      header : " + plan.header()
                        + "\n      but has: " + plan.resultsHeading());
            }
        }
        assertTrue(offenders.isEmpty(), offenders.size()
                + " plan(s) claim they have not run, over their own results"
                + " section. The lane that implements a plan updates that plan's own status"
                + " header, in the same change (STANDING-RULINGS.md). Fix the header — there is"
                + " deliberately no allowlist here.\n   " + String.join("\n   ", offenders));
    }


    /**
     * Check 3 — a wave manifest for a wave the ledger already records cannot still be "ready to
     * cut". Eight of the wave-30 audit's 24 understates were exactly this.
     */
    @Test
    void noSpentWaveManifestStillClaimsItIsWaitingToBeCut()
    {
        List<String> offenders = new ArrayList<>();
        for (PlanFile plan : plans)
        {
            Matcher m = WAVE_MANIFEST.matcher(plan.name());
            if (!m.matches() || plan.header() == null || !plan.claimsPreExecutionOnly())
            {
                continue;
            }
            Integer wave = Integer.valueOf(m.group(1));
            if (spentWaves.contains(wave))
            {
                offenders.add(plan.name() + " (the fix ledger records wave " + wave + ")\n      "
                        + plan.header());
            }
        }
        assertTrue(offenders.isEmpty(),
                offenders.size() + " wave-manifest plan(s) still advertise a wave that the fix"
                        + " ledger already records as landed. Restate as"
                        + " \"SPENT — wave N, `<sha>`\" (see PLAN-wave9-corpus-manifest.md), and"
                        + " say what the wave refused if it was only partly spent.\n   "
                        + String.join("\n   ", offenders));
    }


    /** Check 4 — transient tree-state markers, shrink-only, target zero. */
    @Test
    void transientTreeStateMarkersOnlyEverShrink()
    {
        List<String> stale = plans.stream().filter(
                p -> p.header() != null && TRANSIENT_TREE_STATE.matcher(p.statusRegion()).find())
                .map(PlanFile::name).toList();
        assertTrue(stale.size() <= MAX_PLANS_WITH_TRANSIENT_TREE_STATE,
                stale.size() + " plan headers carry a transient tree-state marker (UNCOMMITTED /"
                        + " in a worktree / awaiting patch-back); the ratchet allows "
                        + MAX_PLANS_WITH_TRANSIENT_TREE_STATE + ". These read as \"not in the tree"
                        + " yet\" long after they landed — delete the parenthetical and cite the"
                        + " sha. Offenders: " + stale);
        assertEquals(MAX_PLANS_WITH_TRANSIENT_TREE_STATE, stale.size(),
                "only " + stale.size() + " plan headers still carry a transient tree-state marker"
                        + " but MAX_PLANS_WITH_TRANSIENT_TREE_STATE still says "
                        + MAX_PLANS_WITH_TRANSIENT_TREE_STATE + ". Lower the constant.");
    }


    /**
     * ⚑ Neuter-and-watch, made permanent.
     *
     * <p>
     * Every spelling below is taken verbatim from a real plan in this corpus, and every negative is
     * a real line that an earlier iteration of the detector matched by accident. A guard that
     * cannot fail is worse than no guard: this is the test that proves the detector still can.
     * </p>
     */
    @Test
    void theDetectorFiresOnEverySpellingInUseAndOnNothingElse()
    {
        for (String positive : List.of("> **Status: ✅ SHIPPED — wave-16 lane B.**",
                "**Status:** IMPLEMENTED — 790 CDISC-AD* rule files", "## Status",
                "## Implementation status — COMPLETE (2026-05-29)",
                "## Implementation status (in progress)",
                "Status: **COMPLETE (implemented + verified 2026-06-12).**",
                "> # ✅ **Status: IMPLEMENTED — wave-14 lane F, 2026-08-07.**",
                "> ## STATUS: ✅ IMPLEMENTED 2026-08-04 as **`Fix #148`**",
                "> Status: **PROPOSAL — awaiting go.** Nothing implemented.",
                "> **Status — re-verified against the code and the corpus 2026-08-11:**"))
        {
            assertTrue(isStatusStatement(positive),
                    "detector went blind to a spelling in use: " + positive);
        }
        for (String negative : List.of(
                "status with a growing progress view, page through findings, and clean",
                "Status lifecycle: PENDING → RUNNING → SUCCEEDED | FAILED | CANCELLED",
                "status, violations, expandedFor, notExecutedReason, description` — **no",
                "- `run/CheckRun.java` — holds `id`, `sessionId`, `createdAt`, status,",
                "`git status --porcelain -uall` at start: **empty** (clean tree).",
                "- The full session → upload → check → status → results workflow.",
                "- `ExecutionStatus`: add `SKIPPED_MISSING_LIBRARY`.",
                "**error message** for `status === \"ERROR\"` rows. The message is **not** in",
                "### 2. Rules table columns → Rule · Description · Status · Violations · Note"))
        {
            assertFalse(isStatusStatement(negative),
                    "detector matched prose that is not a status statement: " + negative);
        }
    }


    /**
     * ⚑ Neuter-and-watch for the classifier — the half that decides <em>wrong</em>, not
     * <em>absent</em>.
     *
     * <p>
     * Each stale shape below is a real header this guard was built to catch; each honest shape is a
     * real header it must leave alone. In particular the last honest case is the one that makes an
     * allowlist unnecessary: a part-shipped plan states both halves and passes.
     * </p>
     */
    @Test
    void theClassifierSeparatesAStaleHeaderFromAnHonestOne()
    {
        for (String stale : List.of("> **Status: READY TO CUT, 2026-08-08.** Decision-free.",
                "> **Status: READY TO CUT, 2026-08-08.** Two edit sets, verified disjoint.",
                "> **Status: PROPOSAL. No go. Written 2026-08-10 at the owner's request**",
                "> **Status: ⛔ NOT STARTED — no go for the code.**",
                "> **Status: DESIGN ONLY — no go, no lane, nothing authorised.**",
                "> **Status: READY FOR A LANE, 2026-08-10. Decision-free.**",
                "> **Status: GO GIVEN — SCHEDULED as wave-22 lane B.**",
                "> **Status: MANIFEST, 2026-08-08 — ready to cut.**",
                "> Status: **PROPOSAL — awaiting go.** Nothing implemented."))
        {
            assertTrue(
                    new PlanFile("synthetic.md", stale, stale, "# Results")
                            .claimsPreExecutionOnly(),
                    "classifier missed a stale header: " + stale);
        }
        for (String honest : List.of("> **Status: ✅ SPENT — wave 9 shipped and committed.**",
                "> **Status: ✅ IMPLEMENTED — wave-25 lane A, 2026-08-10.**",
                "> ⛔⛔ **STATUS SUPERSEDED — see `## Results` at the foot of this file.**",
                "> **Status: ✅ MEASURED — wave-25 lane B; no code or rule change proposed.**",
                "> **Status: ✅ LANE-READY. PHASE 0 COMPLETE (wave 25, lane C); step 2 open.**",
                "> **Status: ⚑ PARTIALLY SHIPPED — wave-29 lane B, as `Fix #206`; Set C refused.**"))
        {
            assertFalse(
                    new PlanFile("synthetic.md", honest, honest, "# Results")
                            .claimsPreExecutionOnly(),
                    "classifier flagged an honest header: " + honest);
        }
    }


    /** The two field accessors the checks depend on are wired to the file, not to a constant. */
    @Test
    void everyParsedPlanKeepsItsOwnHeaderAndRegion()
    {
        PlanFile withHeader = plans.stream().filter(p -> p.header() != null).findFirst()
                .orElseThrow();
        assertNotNull(withHeader.statusRegion());
        assertTrue(withHeader.statusRegion().contains(withHeader.header().strip().substring(0, 8)),
                "the status region of " + withHeader.name() + " does not contain its own header —"
                        + " the region builder and the detector disagree");
        assertNull(parse("synthetic.md", List.of("# PLAN — a plan with no header", "", "body"))
                .header());
    }
}
