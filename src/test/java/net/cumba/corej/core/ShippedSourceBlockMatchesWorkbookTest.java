package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Standing drift guard for the {@code Source:} transcription — phase 1 of
 * {@code plans/done/PLAN-adam-sheet-scope-realignment.md}, {@code Fix #208}.
 *
 * <p>
 * Every shipped {@code PMDA-AD*} check carries a root-level {@code Source:} block holding the
 * verbatim columns of the PMDA validation-rule workbook row it was generated from, so a reviewer
 * can review a rule without opening Excel. That block is written by
 * {@code net.cumba.corej.core.expr.convert.OriginalTextBackfill}, an <strong>offline developer
 * tool</strong> run on demand — nothing re-runs it, and until this test <strong>no test in the
 * repository opened a conformance workbook at all</strong>. The transcription was measured perfect
 * on 2026-08-10 and was completely unguarded: a true snapshot with nothing keeping it true. This
 * test turns it into a property.
 * </p>
 *
 * <h2>What it covers, and what it does not</h2>
 *
 * <p>
 * <strong>Covered:</strong> exactly one (workbook, sheet) pair — {@value #WORKBOOK} →
 * {@value #SHEET} — against the {@code PMDA-AD*} rules, on the four {@code Source} keys the
 * {@code OriginalTextBackfill} {@code REGISTRY} emits for the {@code PMDA-AD} binding:
 * {@code Message} ← {@code MESSAGE}, {@code Description} ← {@code DESCRIPTION}, {@code Severity} ←
 * {@code PMDA Severity} (Plan C phase 1), {@code Domains} ← {@code DOMAINS}.
 * </p>
 *
 * <p>
 * ⚠ <strong>Not covered here:</strong> {@code FDA}, {@code CDISC-CG}, {@code CDISC-AD} and
 * {@code PMDA-SDTM}. ({@code CDISC-SEND} <em>was</em> on this list until 2026-08-22; it now has its
 * own sibling guard, {@link ShippedSendSourceBlockMatchesWorkbookTest}, added by {@code M3-D24} —
 * whose row policy differs from this one's, which is why it is a separate class and not a
 * parameterisation.) The remaining four emit <em>different</em> field sets from different sheets
 * and none of them has been measured, so extending this guard to them is a measurement job, not a
 * copy-paste job (the {@code CDISC-AD} sheet in particular is scoped by
 * {@code Class}/{@code Subclass} and has <em>no</em> domain-scope column —
 * {@code SHEET-ISSUES-CDISC.md} #4 was retracted for assuming otherwise). Also not covered:
 * anything <em>derived</em> from the sheet. This is axis A1 (transcription) of the plan only;
 * {@code Scope.Classes} / {@code Scope.Domains} are authored, not transcribed, and axis A2's 80
 * genuine narrowings are deliberately out of scope here.
 * </p>
 *
 * <h2>Design constraints, both learned the hard way</h2>
 *
 * <ol>
 * <li><strong>The split-suffix fallback is honoured — and since {@code Fix #248} it fires for
 * nothing.</strong> A split rule used to carry a suffixed {@code Rule_Identifier.Id} verbatim
 * ({@code AD0252A}) while the sheet holds only the base row ({@code AD0252}), so
 * {@code OriginalTextBackfill.stripSplitSuffix} fell back one letter for exactly four rules.
 * {@code Fix #248} (wave 40) rewrote those four fabricated citations to the parent's real row, so
 * the fallback now resolves nothing — but it stays pinned, because it is a lookup
 * <em>relaxation</em>: see {@link #theSplitSuffixFallbackFiresForNoRule()}.</li>
 * <li><strong>It never passes vacuously.</strong> {@code Fix #191} shipped a ratchet that reported
 * "0 declaring rules" on a corpus with 11 and stayed green through an entire migration. This test
 * therefore asserts, before it asserts anything about equality, that it found the sheet, found the
 * header, read a plausible number of rows, found a plausible number of rules, and performed a
 * plausible number of cell comparisons. Break the scan and the controls fail first.</li>
 * <li><strong>An absent workbook is a reported skip, never a silent pass.</strong> See below.</li>
 * </ol>
 *
 * <h2>Why an independent reader</h2>
 *
 * <p>
 * This test deliberately does <em>not</em> call into {@code OriginalTextBackfill}. The corpus was
 * written by that tool; a guard sharing its reader would move with it and could only ever catch a
 * hand-edit. Reading the workbook a second time, independently, additionally catches the tool's own
 * reader drifting away from the corpus it once produced. The cost is one duplicated cell-coercion
 * switch, kept deliberately identical in semantics to {@code OriginalTextBackfill.cell}.
 * </p>
 *
 * <h2>Absent workbook</h2>
 *
 * <p>
 * The workbook belongs to a licensed external document set that is not part of this repository and
 * has no location inside it, so this guard is <em>told</em> where it is by
 * {@code -D}{@value #DOCS_DIR_PROPERTY} and has <strong>no default</strong>. No CI job mounts the
 * documents today, and making their absence a hard failure would newly couple every build to them.
 * So: <strong>property unset, or no readable workbook under it ⇒ {@link Assumptions#abort}, naming
 * the property and the resolved path when there is one</strong> — a skip that names itself in the
 * surefire report, never a green tick. Pass {@code -Dcdisc.docs.required=true} to promote that skip
 * to a failure (the setting a CI job that <em>does</em> mount the docs should use). ⚠ The skip is
 * available for that <em>one</em> condition only — no readable workbook. Once the file is readable
 * every other problem (missing sheet, missing header label, too few rows, an unresolvable rule, a
 * mismatched cell) is a hard failure.
 * </p>
 *
 * <h2>Measurements — re-derived 2026-08-10 (wave 29 lane D)</h2>
 *
 * <table>
 * <caption>PMDA {@code ADaM Rules} census</caption>
 * <tr>
 * <td>sheet rows / unique {@code RULE ID}</td>
 * <td>388 / 388 (header on the 7th row, 0-based index 6; {@code DOMAINS} is column D)</td>
 * </tr>
 * <tr>
 * <td>{@code PMDA-AD*} rules in {@code rules-src/checks}</td>
 * <td>370</td>
 * </tr>
 * <tr>
 * <td>rules with no resolvable sheet row</td>
 * <td>0</td>
 * </tr>
 * <tr>
 * <td>split-suffix fallbacks</td>
 * <td>4 — the {@code AD0252} splits (0 since {@code Fix #248} rewrote their fabricated
 * {@code AD0252A}…{@code D} citations to the parent row {@code AD0252}; the rules are now
 * {@code PMDA-AD0252-A}…{@code -D})</td>
 * </tr>
 * <tr>
 * <td>cell comparisons / mismatches</td>
 * <td>1110 / <strong>0</strong></td>
 * </tr>
 * </table>
 */
class ShippedSourceBlockMatchesWorkbookTest
{

    /**
     * System property naming the directory that holds the licensed conformance-document set.
     * Deliberately un-defaulted: the documents are not in this repository, and a path outside it is
     * not something this repository may assume exists.
     */
    static final String DOCS_DIR_PROPERTY = "cdisc.docs.dir";

    /** The workbook under guard. Named in every failure and skip message on purpose. */
    static final String WORKBOOK = "PMDA-ValidationRules-v6-0.xlsx";

    /** The sheet under guard. The same workbook's {@code SDTM Rules} sheet is NOT covered. */
    static final String SHEET = "ADaM Rules";

    /** The sheet's native rule-id column. */
    private static final String ID_COLUMN = "RULE ID";

    /**
     * The {@code Source} keys this guard compares, mapped to their sheet column label. These are
     * exactly the three fields {@code OriginalTextBackfill.REGISTRY}'s {@code PMDA-AD} binding
     * emits. {@link #everySourceKeyOnAnAdamRuleIsGuarded()} fails if the corpus ever grows a
     * fourth, so a registry extension cannot slip past unguarded.
     */
    private static final SequencedMap<String, String> FIELDS = fields();

    /** {@code Core.Id} prefix selecting the guarded population. */
    private static final Pattern ID_PATTERN = Pattern.compile("^PMDA-AD");

    /** The {@code References[].Origin} the sheet-native id is read from. */
    private static final String ORIGIN = "PMDA Validation Rules";

    /** The generated-check root, resolved against the module basedir (surefire's working dir). */
    private static final Path SRC_CHECKS = Path.of("rules-src", "checks");

    /**
     * Plausibility floors, well under the 2026-08-10 census (388 / 370 / 1110). Floors rather than
     * exact pins: phase 3 of the plan is expected to add and adjust rules, and a guard that has to
     * be re-pinned for every corpus edit gets re-pinned without being read. Their only job is to
     * make "the scan found nothing" impossible to confuse with "the corpus is clean".
     */
    private static final int MIN_SHEET_ROWS = 350;

    private static final int MIN_RULES = 350;

    private static final int MIN_COMPARISONS = 1000;

    /**
     * The complete set of rules for which the split-suffix fallback fires — <b>empty since
     * {@code Fix #248}</b> (wave 40), re-derived by run. The four {@code AD0252} splits were the
     * only members (re-derived 2026-08-10): each carried a <em>fabricated</em>
     * {@code Rule_Identifier.Id} ({@code AD0252A}…{@code D}) that no sheet publishes, which the
     * 2026-08-12 minting ruling forbids, so {@code Fix #248} rewrote all four citations to the
     * parent's real row {@code AD0252} — a verbatim sheet id needing no fallback. Still pinned
     * rather than deleted: the fallback is a lookup <em>relaxation</em>, and an unwatched
     * relaxation is how a genuinely unmatched rule gets silently absorbed. The four rules' verbatim
     * resolution is asserted positively in {@link #theSplitSuffixFallbackFiresForNoRule()}, so an
     * emptied pin cannot go vacuous.
     */
    private static final List<String> EXPECTED_FALLBACKS = List.of();

    /**
     * ⚑ Re-derived 2026-08-10, and <strong>not previously recorded anywhere</strong>: of the 388
     * rows on the {@value #SHEET} tab, <strong>22 do not carry an {@code AD} id at all</strong>.
     * All 22 also appear on the workbook's {@code SDTM Rules} tab, and in <strong>all 22 cases the
     * {@code DOMAINS} cell differs</strong> — {@code ALL} on the SDTM tab, an enumerated ADaM
     * structure list here (two of them, {@code CT2001} and {@code CT2002}, ending in
     * {@code ADAMOTHER}). {@code OriginalTextBackfill}'s {@code PMDA-SDTM} binding reads only the
     * {@code SDTM Rules} tab, so the corpus carries {@code Domains: "ALL"} for all 22 and the
     * ADaM-tab variant is transcribed nowhere. That is faithful to the tab coreJ declares, but it
     * is a real information loss and it is unguarded — filed for
     * {@code plans/done/PLAN-adam-sheet-scope-realignment.md}, not fixed here.
     */
    private static final TreeSet<String> NON_ADAM_SHEET_ROWS = new TreeSet<>(
            List.of("CT2001", "CT2002", "CT2003", "CT2004", "CT2005", "CT2006", "SD0001", "SD0037",
                    "SD0054", "SD0059", "SD0060", "SD0061", "SD0062", "SD1063", "SD1082", "SD1142",
                    "SD1228", "SD1229", "SD1230", "SD1231", "SD1324", "SD1325"));

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static SequencedMap<String, String> fields()
    {
        SequencedMap<String, String> map = new LinkedHashMap<>();
        map.put("Message", "MESSAGE");
        map.put("Description", "DESCRIPTION");
        map.put("Domains", "DOMAINS");
        map.put("Severity", "PMDA Severity");
        return map;
    }

    // ---- outcome model ----------------------------------------------------

    /**
     * One guarded rule, resolved against the sheet.
     *
     * @param coreId
     *            the rule's {@code Core.Id}
     * @param sheetId
     *            the raw {@code References[].Rule_Identifier.Id} under {@link #ORIGIN}
     * @param resolvedId
     *            the sheet row actually matched ({@code sheetId}, or its split base), or
     *            {@code null} when no row matched
     * @param source
     *            the rule's {@code Source} block as {@code key -> value} (empty when absent)
     * @param viaFallback
     *            whether the sheet row was found only after stripping a split suffix
     */
    private record ResolvedRule(String coreId, @Nullable String sheetId,
            @Nullable String resolvedId, SequencedMap<String, String> source, boolean viaFallback)
    {
    }


    /** Everything one scan produced, so each test asserts a different facet of the same read. */
    private record Scan(Map<String, Map<String, String>> sheet, List<ResolvedRule> rules,
            List<String> unresolved, List<String> fallbacks, List<String> mismatches,
            int comparisons)
    {
    }

    // ---- the guard --------------------------------------------------------

    /**
     * The control half, asserted on its own so a scan that reads nothing can never masquerade as a
     * clean corpus. Every number here is a floor under the recorded census.
     */
    @Test
    void theScanActuallyReadsTheWorkbookAndTheCorpus() throws IOException
    {
        Scan scan = scan();
        assertTrue(scan.sheet().size() >= MIN_SHEET_ROWS,
                "positive control: '" + SHEET + "' in " + WORKBOOK + " must yield at least "
                        + MIN_SHEET_ROWS + " rows keyed by '" + ID_COLUMN + "' (2026-08-10: 388);"
                        + " found " + scan.sheet().size() + ". A shrunken read means the header row"
                        + " or the id column moved — fix the read before trusting any comparison.");
        assertTrue(scan.rules().size() >= MIN_RULES,
                "positive control: at least " + MIN_RULES + " rules matching " + ID_PATTERN
                        + " must be found under " + SRC_CHECKS.toAbsolutePath()
                        + " (2026-08-10: 370); found " + scan.rules().size());
        assertTrue(scan.comparisons() >= MIN_COMPARISONS,
                "positive control: at least " + MIN_COMPARISONS + " cell comparisons must actually"
                        + " have been performed (2026-08-10: 1110); performed " + scan.comparisons()
                        + ". Zero mismatches out of zero comparisons is the"
                        + " Fix #191 failure mode, not a clean corpus.");
    }


    /**
     * Axis A1 of {@code PLAN-adam-sheet-scope-realignment.md}: every guarded {@code Source} key of
     * every {@code PMDA-AD*} rule is byte-identical to its workbook cell.
     */
    @Test
    void everyAdamSourceBlockMatchesTheWorkbookRowVerbatim() throws IOException
    {
        Scan scan = scan();
        // Re-assert the control here: read on its own, this test must not be able to pass
        // vacuously.
        assertTrue(scan.comparisons() >= MIN_COMPARISONS, "control: only " + scan.comparisons()
                + " comparisons were performed; the scan is broken, not the corpus clean");

        assertEquals(List.of(), scan.mismatches(),
                scan.mismatches().size() + " of " + scan.comparisons() + " Source cells no longer"
                        + " match '" + SHEET + "' in " + WORKBOOK + ". The Source block is a"
                        + " verbatim transcription of the workbook row; it is written by the"
                        + " offline tool OriginalTextBackfill and must never be hand-edited. If the"
                        + " workbook itself was upgraded, re-run scripts/backfill-rules.sh and"
                        + " re-read plans/PLAN-adam-sheet-scope-realignment.md before re-pinning"
                        + " anything.");
    }


    /** No {@code PMDA-AD*} rule may reference a sheet row that does not exist. */
    @Test
    void everyAdamRuleResolvesToASheetRow() throws IOException
    {
        Scan scan = scan();
        assertTrue(scan.rules().size() >= MIN_RULES,
                "control: only " + scan.rules().size() + " rules were scanned");
        assertEquals(List.of(), scan.unresolved(),
                "these rules carry no '" + ORIGIN + "' reference id, or an id that is absent from '"
                        + SHEET + "' in " + WORKBOOK + " even after the split-suffix fallback."
                        + " A rule whose provenance cannot be resolved has an unverifiable Source"
                        + " block.");
    }


    /**
     * The split-suffix fallback ({@code AD0252A} → {@code AD0252}) is deliberate and documented,
     * and pinned to the rules that need it — <b>none, since {@code Fix #248}</b> rewrote the four
     * fabricated {@code AD0252A}…{@code D} citations to the parent's real row. A new entry means
     * either a suffixed citation landed — re-derive and re-pin, quoting the sheet — or an id is
     * wrong and the fallback is hiding it.
     *
     * <p>
     * &#9888; An empty pin alone would be vacuous (a broken scan also reports no fallbacks), so the
     * four renamed splits' <em>verbatim</em> resolution is asserted positively: each must still be
     * scanned, cite {@code AD0252}, and resolve without the fallback.
     * </p>
     */
    @Test
    void theSplitSuffixFallbackFiresForNoRule() throws IOException
    {
        Scan scan = scan();
        assertTrue(scan.rules().size() >= MIN_RULES,
                "control: only " + scan.rules().size() + " rules were scanned");
        assertEquals(EXPECTED_FALLBACKS, scan.fallbacks(),
                "the split-suffix lookup fallback (OriginalTextBackfill.stripSplitSuffix) now fires"
                        + " for a rule. It exists because a split rule may keep a suffixed id while"
                        + " '" + SHEET + "' holds only the base row. It is a lookup relaxation:"
                        + " every id it rescues is an id that did NOT match the sheet verbatim."
                        + " Confirm any new entry is a genuine split before re-pinning this list.");

        for (String coreId : List.of("PMDA-AD0252-A", "PMDA-AD0252-B", "PMDA-AD0252-C",
                "PMDA-AD0252-D"))
        {
            ResolvedRule rule = scan.rules().stream().filter(r -> coreId.equals(r.coreId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(coreId
                            + " was not scanned at all — the empty fallback pin above is only"
                            + " meaningful while the four renamed AD0252 splits are in the"
                            + " population"));
            assertEquals("AD0252", rule.sheetId(),
                    coreId + " must cite the parent's real sheet row (Fix #248 rewrote the"
                            + " fabricated suffixed citation)");
            assertFalse(rule.viaFallback(),
                    coreId + " must resolve verbatim — a fallback here means the citation"
                            + " regressed to a suffixed form");
        }
    }


    /**
     * Guards the guard's own coverage: {@code Source} on a {@code PMDA-AD*} rule may hold only the
     * keys {@link #FIELDS} compares. If the {@code OriginalTextBackfill} registry ever emits a
     * fifth field for the {@code PMDA-AD} binding, this fails the moment it reaches the corpus —
     * rather than the new field sitting silently untranscribed-and-unchecked forever.
     */
    @Test
    void everySourceKeyOnAnAdamRuleIsGuarded() throws IOException
    {
        Scan scan = scan();
        assertTrue(scan.rules().size() >= MIN_RULES,
                "control: only " + scan.rules().size() + " rules were scanned");
        TreeSet<String> unguarded = new TreeSet<>();
        for (ResolvedRule rule : scan.rules())
        {
            for (String key : rule.source().keySet())
            {
                if (!FIELDS.containsKey(key))
                {
                    unguarded.add(key);
                }
            }
        }
        assertEquals(new TreeSet<String>(), unguarded,
                "PMDA-AD Source blocks now carry key(s) this guard does not compare. Add them to"
                        + " FIELDS with their sheet column label — an unguarded Source key is an"
                        + " untested transcription.");
    }


    /**
     * The other direction: every {@code AD}-prefixed row of the sheet is claimed by a shipped rule,
     * so a rule cannot be deleted (or an id retyped) without the sheet row it transcribed becoming
     * silently unrepresented. Re-derived 2026-08-10: 366 {@code AD} rows, 366 claimed, by 370 rules
     * (the four {@code AD0252} splits share one row).
     *
     * <p>
     * ⚠⚠ <strong>The other 22 rows of this sheet are NOT ADaM rules and are deliberately
     * excluded</strong> — see {@link #NON_ADAM_SHEET_ROWS}. They are {@code SD}/{@code CT}-native
     * ids that appear on <em>both</em> PMDA tabs, and coreJ sources them from the
     * {@code SDTM Rules} tab via the {@code PMDA-SDTM} binding. Asserting them here would be a
     * wrong-population error: they are in the corpus, just not under a {@code PMDA-AD} id.
     * </p>
     */
    @Test
    void everyAdamRowOfTheSheetIsClaimedByAShippedRule() throws IOException
    {
        Scan scan = scan();
        TreeSet<String> adamRows = new TreeSet<>();
        TreeSet<String> otherRows = new TreeSet<>();
        scan.sheet().keySet().forEach(id -> (id.startsWith("AD") ? adamRows : otherRows).add(id));
        assertTrue(adamRows.size() >= MIN_RULES, "control: only " + adamRows.size()
                + " AD-prefixed rows were read from '" + SHEET + "' (2026-08-10: 366)");

        TreeSet<String> claimed = new TreeSet<>();
        for (ResolvedRule rule : scan.rules())
        {
            String resolved = rule.resolvedId();
            if (resolved != null)
            {
                claimed.add(resolved);
            }
        }
        TreeSet<String> unclaimed = new TreeSet<>(adamRows);
        unclaimed.removeAll(claimed);
        assertEquals(new TreeSet<String>(), unclaimed,
                "these '" + SHEET + "' rows of " + WORKBOOK + " are no longer transcribed by any"
                        + " shipped PMDA-AD rule. A sheet row that lost its rule is a coverage"
                        + " regression, not a formatting one.");

        assertEquals(NON_ADAM_SHEET_ROWS, otherRows,
                "the set of non-ADaM ids on the '" + SHEET + "' tab changed. These 22 rows carry"
                        + " SDTM/CT-native ids and appear on the 'SDTM Rules' tab too — with a"
                        + " DIFFERENT DOMAINS cell in all 22 cases ('ALL' on the SDTM tab, an"
                        + " enumerated ADaM structure list here). coreJ transcribes only the SDTM"
                        + " tab for them. If this set moved, the workbook changed and"
                        + " plans/PLAN-adam-sheet-scope-realignment.md needs re-reading.");
    }

    // ---- scan -------------------------------------------------------------

    /** Memoised {@link #readAll} — the six tests assert different facets of one read. */
    private static @Nullable Scan cached;

    /**
     * The one scan, resolved lazily and shared. Skips — visibly, per test, with the resolved path
     * in the message — only when the workbook file itself is unreadable; every other failure is
     * hard.
     */
    private static Scan scan() throws IOException
    {
        String configured = System.getProperty(DOCS_DIR_PROPERTY, "");
        if (configured.isBlank())
        {
            unavailable("-D" + DOCS_DIR_PROPERTY + " is not set, so " + WORKBOOK
                    + " cannot be located");
        }
        Path workbook = Path.of(configured).resolve(WORKBOOK);
        if (!Files.isRegularFile(workbook))
        {
            unavailable("conformance workbook not found: " + workbook.toAbsolutePath());
        }
        Scan scan = cached;
        if (scan == null)
        {
            scan = readAll(workbook);
            cached = scan;
        }
        return scan;
    }


    /**
     * The one skippable condition — no readable workbook — reported as a visible skip naming the
     * property to set, or promoted to a failure by {@code -Dcdisc.docs.required=true}. Never
     * returns normally.
     */
    private static void unavailable(String aWhy)
    {
        String absent = aWhy + " — the Source-block drift guard cannot run. The conformance"
                + " workbooks are a licensed external document set, not part of this repository"
                + " and with no location inside it; point -D" + DOCS_DIR_PROPERTY
                + " at a directory holding " + WORKBOOK + ".";
        if (Boolean.getBoolean("cdisc.docs.required"))
        {
            throw new IllegalStateException(
                    absent + " FAILING because -Dcdisc.docs.required=true was passed.");
        }
        // Reported skip, never a silent pass: drop -Dcdisc.docs.required=true to see it fail here.
        Assumptions.abort(absent + " SKIPPED rather than passed; pass -Dcdisc.docs.required=true"
                + " to make this absence a build failure instead.");
    }


    /** Reads the workbook and the corpus and resolves one against the other. */
    private static Scan readAll(Path workbook) throws IOException
    {
        Map<String, Map<String, String>> sheet = readSheet(workbook);

        List<ResolvedRule> rules = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        List<String> fallbacks = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        int comparisons = 0;

        for (Path file : checkFiles())
        {
            JsonNode root = YAML.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String coreId = text(path(root, "Core", "Id"));
            if (coreId == null || !ID_PATTERN.matcher(coreId).find())
            {
                continue;
            }
            String sheetId = referenceId(root);
            String resolvedId = sheetId != null && sheet.containsKey(sheetId) ? sheetId : null;
            boolean viaFallback = false;
            if (resolvedId == null && sheetId != null)
            {
                String base = stripSplitSuffix(sheetId);
                if (!base.equals(sheetId) && sheet.containsKey(base))
                {
                    resolvedId = base;
                    viaFallback = true;
                }
            }
            Map<String, String> row = resolvedId == null ? null : sheet.get(resolvedId);
            SequencedMap<String, String> source = sourceBlock(root);
            rules.add(new ResolvedRule(coreId, sheetId, resolvedId, source, viaFallback));
            if (viaFallback)
            {
                fallbacks.add(coreId);
            }
            if (row == null)
            {
                unresolved.add(coreId + " (sheet id " + sheetId + ")");
                continue;
            }
            for (var field : FIELDS.entrySet())
            {
                comparisons++;
                String expected = row.getOrDefault(field.getValue(), "");
                String actual = source.get(field.getKey());
                // A blank sheet cell must leave the key absent, not present-and-empty: that is
                // exactly what OriginalTextBackfill's blankToNull does when it writes the block.
                boolean ok = expected.isEmpty() ? actual == null : expected.equals(actual);
                if (!ok)
                {
                    mismatches.add(String.format(Locale.ROOT, "%s Source.%s: sheet=%s corpus=%s",
                            coreId, field.getKey(), quote(expected.isEmpty() ? null : expected),
                            quote(actual)));
                }
            }
        }
        rules.sort((a, b) -> a.coreId().compareTo(b.coreId()));
        unresolved.sort(String::compareTo);
        fallbacks.sort(String::compareTo);
        mismatches.sort(String::compareTo);
        return new Scan(sheet, rules, unresolved, fallbacks, mismatches, comparisons);
    }


    /**
     * Every {@code .yaml} under the generated-check root, sorted. Missing root is a hard failure.
     */
    private static List<Path> checkFiles() throws IOException
    {
        assertTrue(Files.isDirectory(SRC_CHECKS),
                "the generated-check root is missing: " + SRC_CHECKS.toAbsolutePath()
                        + " — this test must run with the module basedir as"
                        + " its working directory (see the surefire configuration in pom.xml)");
        try (Stream<Path> stream = Files.walk(SRC_CHECKS))
        {
            return stream.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted()
                    .toList();
        }
    }


    /**
     * Reads {@link #SHEET} into {@code RULE ID -> (column label -> verbatim cell)}, first row per
     * id winning (the {@code PMDA-AD} binding declares no version column). A missing sheet, a
     * missing required column, or an unfindable header row is a hard failure — those are exactly
     * the shapes that would otherwise make the guard read nothing and report a clean corpus.
     */
    private static Map<String, Map<String, String>> readSheet(Path workbook) throws IOException
    {
        Map<String, Map<String, String>> out = new HashMap<>();
        try (Workbook wb = WorkbookFactory.create(workbook.toFile(), null, true))
        {
            Sheet sheet = wb.getSheet(SHEET);
            if (sheet == null)
            {
                throw new IllegalStateException("missing sheet '" + SHEET + "' in " + workbook
                        + " — the workbook is present but is not the one this guard was written"
                        + " against. Do not relax this into a skip.");
            }
            int headerRow = headerRow(sheet, workbook);
            Map<String, Integer> columns = labels(sheet.getRow(headerRow));
            for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++)
            {
                Row row = sheet.getRow(r);
                String id = cell(row, columns.get(ID_COLUMN));
                if (id.isEmpty())
                {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                for (String column : FIELDS.values())
                {
                    values.put(column, cell(row, columns.get(column)));
                }
                out.putIfAbsent(id, values);
            }
        }
        return out;
    }


    /** The 0-based index of the first row carrying every required label. */
    private static int headerRow(Sheet sheet, Path workbook)
    {
        for (int r = 0; r <= sheet.getLastRowNum(); r++)
        {
            if (labels(sheet.getRow(r)).keySet().containsAll(required()))
            {
                return r;
            }
        }
        throw new IllegalStateException("no row of '" + SHEET + "' in " + workbook + " carries all"
                + " of " + required() + " — the sheet layout changed. Do not relax this into a"
                + " skip: a guard that cannot find its columns must fail, not pass.");
    }


    private static List<String> required()
    {
        List<String> req = new ArrayList<>();
        req.add(ID_COLUMN);
        req.addAll(FIELDS.values());
        return req;
    }


    /**
     * Non-empty cell texts of {@code row} mapped to their column index, first occurrence winning.
     */
    private static Map<String, Integer> labels(@Nullable Row row)
    {
        Map<String, Integer> columns = new LinkedHashMap<>();
        if (row != null)
        {
            for (int c = 0; c < row.getLastCellNum(); c++)
            {
                String label = cell(row, c);
                if (!label.isEmpty())
                {
                    columns.putIfAbsent(label, c);
                }
            }
        }
        return columns;
    }


    /**
     * The trimmed text of a cell, integers rendered without a trailing {@code .0}. Deliberately the
     * same coercion as {@code OriginalTextBackfill.cell}: the corpus was written through that
     * switch, so a different one here would produce false mismatches rather than real ones.
     */
    private static String cell(@Nullable Row row, @Nullable Integer col)
    {
        if (row == null || col == null || col < 0)
        {
            return "";
        }
        Cell c = row.getCell(col);
        if (c == null)
        {
            return "";
        }
        return switch (c.getCellType())
        {
        case STRING -> c.getStringCellValue().strip();
        case NUMERIC -> numeric(c.getNumericCellValue());
        case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
        case FORMULA -> formula(c);
        default -> "";
        };
    }


    private static String formula(Cell c)
    {
        try
        {
            return c.getStringCellValue().strip();
        }
        catch (IllegalStateException _)
        {
            return numeric(c.getNumericCellValue());
        }
    }


    private static String numeric(double value)
    {
        if (value == Math.rint(value) && !Double.isInfinite(value))
        {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    // ---- rule readers -----------------------------------------------------


    /**
     * The rule's {@code Source} block as an ordered {@code key -> value} map (empty when absent).
     */
    private static SequencedMap<String, String> sourceBlock(JsonNode root)
    {
        SequencedMap<String, String> out = new LinkedHashMap<>();
        JsonNode source = root.get("Source");
        if (source != null)
        {
            source.properties().forEach(e -> out.put(e.getKey(), e.getValue().asText()));
        }
        return out;
    }


    /**
     * The first {@code Standards[].Standards[].References[].Rule_Identifier.Id} whose
     * {@code Origin} is {@link #ORIGIN} — the sheet-native lookup key, the same one
     * {@code OriginalTextBackfill.joinId} uses.
     */
    private static @Nullable String referenceId(JsonNode root)
    {
        JsonNode standards = root.get("Standards");
        if (standards == null)
        {
            return null;
        }
        for (JsonNode authority : standards)
        {
            for (JsonNode std : orEmpty(authority.get("Standards")))
            {
                for (JsonNode ref : orEmpty(std.get("References")))
                {
                    if (ORIGIN.equals(text(ref.get("Origin"))))
                    {
                        String id = text(path(ref, "Rule_Identifier", "Id"));
                        if (id != null)
                        {
                            return id.strip();
                        }
                    }
                }
            }
        }
        return null;
    }


    /**
     * Strips ONE trailing uppercase letter — directly after a digit ({@code AD0252A} →
     * {@code AD0252}) or hyphen-joined after a digit ({@code CG0285-A} → {@code CG0285}, the
     * coreJ-minted spelling; {@code Fix #248} widened this copy) — mirroring
     * {@code OriginalTextBackfill.stripSplitSuffix}. Applied only after a verbatim lookup miss.
     * Deliberately a second implementation, not a delegation (see the class Javadoc), so it must be
     * widened in step by hand.
     */
    private static String stripSplitSuffix(String id)
    {
        int n = id.length();
        if (n > 1 && Character.isUpperCase(id.charAt(n - 1)))
        {
            if (Character.isDigit(id.charAt(n - 2)))
            {
                return id.substring(0, n - 1);
            }
            if (n > 2 && id.charAt(n - 2) == '-' && Character.isDigit(id.charAt(n - 3)))
            {
                return id.substring(0, n - 2);
            }
        }
        return id;
    }

    // ---- json helpers -----------------------------------------------------


    private static Iterable<JsonNode> orEmpty(@Nullable JsonNode node)
    {
        return node == null ? List.of() : node;
    }


    private static @Nullable JsonNode path(@Nullable JsonNode node, String... keys)
    {
        JsonNode at = node;
        for (String key : keys)
        {
            if (at == null)
            {
                return null;
            }
            at = at.get(key);
        }
        return at;
    }


    private static @Nullable String text(@Nullable JsonNode node)
    {
        return node == null || node.isNull() ? null : node.asText();
    }


    /** A value rendered for a failure message — {@code <absent>} is not the same as {@code ""}. */
    private static String quote(@Nullable String value)
    {
        return value == null ? "<absent>" : "\"" + value + "\"";
    }
}
