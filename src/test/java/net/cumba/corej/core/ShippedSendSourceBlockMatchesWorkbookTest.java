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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
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
 * Standing drift guard for the SEND {@code Source:} transcription — phase 2b of
 * {@code plans/done/PLAN-send-source-backfill-reanchor.md} ({@code M3-D24}).
 *
 * <p>
 * Sibling of {@link ShippedSourceBlockMatchesWorkbookTest}, which guards the PMDA {@code ADaM
 * Rules} tab and whose own Javadoc named {@code CDISC-SEND} among the five bindings it does
 * <em>not</em> cover. Nothing kept the SEND transcription true, which is how it came to transcribe
 * the wrong workbook row for 285 of its 323 joined rules without a single test noticing.
 * </p>
 *
 * <h2>What it asserts</h2>
 *
 * <p>
 * For every shipped {@code CDISC-SEND*} rule: that the rule's own <strong>primary row</strong> —
 * re-derived here from the sheet plus the check's own {@code Standards[].Standards[]} block — is
 * transcribed into {@code Source} byte-for-byte on all six guarded keys, modulo the one documented
 * exception (line breaks, below). Plus five structural pins: every rule resolves to a sheet row (or
 * is one of the nine minted rules that have none); the split-suffix lookup fallback fires for
 * nothing; no rule's primary row falls outside its declared versions; no {@code Source} key is
 * unguarded; and every SEND sheet id is claimed by a shipped rule or listed as knowingly
 * unimplemented.
 * </p>
 *
 * <h2>⚠⚠ Why it re-derives the row instead of asking the tool</h2>
 *
 * <p>
 * A verifier that replicates the tool's own join replicates the tool's blind spot. On 2026-08-14 a
 * verifier built that way passed all twelve wrong {@code -A} sibling overwrites and only the
 * owner's content knowledge caught them. So this test reads the workbook with its own POI reader,
 * parses the version cells with its own parser, and applies the {@code M3-D24} row policy from its
 * written definition — never by calling into {@code OriginalTextBackfill}. The cost is a duplicated
 * cell-coercion switch and a duplicated version parser; that duplication is the point.
 * </p>
 *
 * <h2>The row policy, restated from the plan (§5.1)</h2>
 *
 * <ol>
 * <li><b>Candidates</b> — every sheet row whose {@code CDISC SEND Rule ID} is the rule's cited id
 * (after the split-suffix fallback), in sheet order.</li>
 * <li><b>Declared</b> — {@code Name + " v" + Version} over every {@code Standards[].Standards[]}
 * entry of the check, e.g. {@code SENDIG v3.1.1}.</li>
 * <li><b>Eligible</b> — candidates whose version cell is declared; if none, all candidates (and
 * {@link #EXPECTED_UNDECLARED} must then name the rule).</li>
 * <li><b>Primary</b> — the eligible row lowest by (family rank, family name, numeric parts), the
 * lower sheet row breaking a tie. Family order: {@code SENDIG} &lt; {@code SENDIG-DART} &lt;
 * {@code SENDIG-GENETOX} &lt; {@code SENDIG-AR}.</li>
 * </ol>
 *
 * <h2>The one fidelity exception: line breaks</h2>
 *
 * <p>
 * Owner ruling of 2026-08-21: every run of {@code \r} / {@code \n} inside a cell is transcribed as
 * a single space. This guard applies the same rule in its own {@link #transcribe} — it must, or the
 * nine inner-line-break cells of this sheet would all read as mismatches. Nothing else is
 * normalised: curly quotes, double spaces and sheet typos are compared exactly as they stand.
 * </p>
 *
 * <h2>Absent workbook</h2>
 *
 * <p>
 * The workbook belongs to a licensed external document set that is not part of this repository and
 * has no location inside it, so this guard is told where it is by
 * {@code -D}{@value #DOCS_DIR_PROPERTY} and has <b>no default</b>. Property unset, or no readable
 * workbook under it ⇒ a reported {@link Assumptions#abort} skip naming the property (and the
 * resolved path when there is one), never a silent pass; {@code -Dcdisc.docs.required=true}
 * promotes that one condition to a failure. Every other problem (missing sheet, missing column, too
 * few rows, a mismatched cell) is hard.
 * </p>
 *
 * <h2>Census — measured 2026-08-22, at the re-anchored corpus</h2>
 *
 * <table>
 * <caption>SEND {@code _GENETOXv1.0_DARTv1.2} census</caption>
 * <tr>
 * <td>sheet data rows / distinct {@code CDISC SEND Rule ID}</td>
 * <td>1928 / 435 (header on row 1, 0-based index 0)</td>
 * </tr>
 * <tr>
 * <td>{@code CDISC-SEND*} rules in {@code rules-src/checks}</td>
 * <td>332</td>
 * </tr>
 * <tr>
 * <td>resolved to a sheet row / minted without one</td>
 * <td>323 / 9</td>
 * </tr>
 * <tr>
 * <td>split-suffix fallbacks</td>
 * <td>0 — both {@code -A} siblings cite the parent's verbatim id</td>
 * </tr>
 * <tr>
 * <td>cell comparisons / mismatches</td>
 * <td>1938 / <b>0</b></td>
 * </tr>
 * <tr>
 * <td>ids with two rows sharing one version cell</td>
 * <td>0 — and the tool's (b1) blank-cell fallback, widened on 2026-08-22 ({@code O-2},
 * {@code Fix #335}) to a same-family same-or-lower-version donor, still fires 0 times on the
 * shipped corpus (stage-1 dry run: {@code fallbacks=0}). ⚠ The day it does fire, this guard — which
 * reads the primary row only and requires a blank primary cell to leave the key ABSENT — goes red
 * on the transcribed block and STAYS red until a human records the borrowed cell in
 * {@code EXPECTED_VARIANTS}; that standing red is the intended protection, not a transient</td>
 * </tr>
 * </table>
 */
class ShippedSendSourceBlockMatchesWorkbookTest
{

    /**
     * System property naming the directory that holds the licensed conformance-document set.
     * Deliberately un-defaulted: the documents are not in this repository, and a path outside it is
     * not something this repository may assume exists.
     */
    static final String DOCS_DIR_PROPERTY = "cdisc.docs.dir";

    /** The workbook under guard. Named in every failure and skip message on purpose. */
    static final String WORKBOOK = "SEND_Conformance_Rules_v5.0.xlsx";

    /** The sheet under guard. */
    static final String SHEET = "_GENETOXv1.0_DARTv1.2";

    private static final String ID_COLUMN = "CDISC SEND Rule ID";

    private static final String VERSION_COLUMN = "SENDIG Version";

    /** {@code Core.Id} prefix selecting the guarded population. */
    private static final Pattern ID_PATTERN = Pattern.compile("^CDISC-SEND");

    /** The {@code References[].Origin} the sheet-native id is read from. */
    private static final String ORIGIN = "SEND Conformance Rules";

    /** The generated-check root, resolved against the module basedir (surefire's working dir). */
    private static final Path SRC_CHECKS = Path.of("rules-src", "checks");

    /**
     * The six {@code Source} keys this guard compares, mapped to their sheet column label — exactly
     * the fields {@code OriginalTextBackfill.REGISTRY}'s {@code CDISC-SEND} binding emits.
     * {@link #everySourceKeyOnASendRuleIsGuarded()} fails if the corpus ever grows a seventh.
     */
    private static final SequencedMap<String, String> FIELDS = fields();

    /** The SEND version families in ascending order. Duplicated from the tool, deliberately. */
    private static final List<String> FAMILY_ORDER = List.of("SENDIG", "SENDIG-DART",
            "SENDIG-GENETOX", "SENDIG-AR");

    /**
     * Plausibility floors, well under the 2026-08-22 census (435 / 332 / 1938). Floors rather than
     * exact pins: the SEND corpus is under active review and a guard that has to be re-pinned for
     * every rule edit gets re-pinned without being read. Their only job is to make "the scan found
     * nothing" impossible to confuse with "the corpus is clean".
     */
    private static final int MIN_SHEET_IDS = 400;

    private static final int MIN_RULES = 300;

    private static final int MIN_COMPARISONS = 1800;

    /**
     * The complete set of shipped {@code CDISC-SEND*} rules that resolve to <em>no</em> sheet row.
     * All nine are coreJ-minted {@code 94xx} rules with no CDISC SEND row behind them; they carry
     * no {@code Source} block and none is owed. Pinned exactly (not as a floor) so a rule that
     * <em>should</em> resolve and silently stops cannot hide among them.
     */
    private static final List<String> MINTED_WITHOUT_A_SHEET_ROW = List.of("CDISC-SEND-9423",
            "CDISC-SEND-9424", "CDISC-SEND-9425");
    // ⭐ Re-derived 2026-08-26, 9 -> 3 (plans/PLAN-source-proposed-block.md ruling I). The six
    // CDISC-SEND-943x rules LEFT this set: they operationalise the generic published row SEND12
    // ("Variable Core Status = Required" / "Variable present in dataset and ^= null"), every
    // variable they name is Core=Req, and their citation was repaired so they now resolve to it.
    // ⚑ The three that remain are the genuinely rowless ones - DM / TS / EX dataset-PRESENCE rules,
    // for which the SEND catalogue publishes no row of any kind (sheet issue #101). That this set
    // shrank to exactly the presence rules is an independent confirmation of the ruling: the
    // workbook join agrees, rule for rule, with a taxonomy that never consults a workbook.

    /**
     * Rules for which the split-suffix lookup fallback fires — <b>empty</b>: SEND's only two
     * letter-suffixed siblings ({@code CDISC-SEND-0001-A}, {@code CDISC-SEND-0012-A}) cite the
     * parent's verbatim sheet id. ⚠ An empty pin alone is vacuous, so
     * {@link #theSplitSuffixFallbackFiresForNoRule()} asserts both siblings' verbatim resolution
     * positively.
     */
    private static final List<String> EXPECTED_FALLBACKS = List.of();

    /**
     * Rules whose primary row falls outside their declared versions — <b>empty</b>, measured
     * 2026-08-22 over all 323 resolved rules. A new entry means either a rule's {@code Standards}
     * block was narrowed away from the row it transcribes, or the sheet retired a version.
     */
    private static final List<String> EXPECTED_UNDECLARED = List.of();

    /**
     * Rules whose {@code Source} text deliberately differs from their primary row — <b>empty</b>.
     * SEND has no known deliberate-variant file today (the twelve of
     * {@code backfill-overwrites-split-sibling-source} are AD / CG / FDA / PMDA), which is exactly
     * why {@link #theScanActuallyReadsTheWorkbookAndTheCorpus()} pins the comparison count: an
     * empty variant list is only meaningful beside a non-zero comparison count.
     */
    private static final List<String> EXPECTED_VARIANTS = List.of();

    /**
     * Rules that declare a version spelling <em>no</em> row of their cited sheet id carries, while
     * at least one other declared spelling does match — so the row choice silently <b>narrows</b>
     * rather than falling back. {@link #EXPECTED_UNDECLARED} cannot see these: it fires only when
     * NO declared spelling matches.
     *
     * <p>
     * ⚑ Measured 2026-08-22 — five rules, all declaring {@code SENDIG v3.0} while citing a
     * {@code .1} sheet id that only exists from {@code SENDIG v3.1} onwards. Their
     * {@code Standards[]} block asserts the rule ships in 3.0; the sheet says the row does not
     * exist there. That was cosmetic before {@code M3-D24} and is load-bearing after it, because
     * the declared set now chooses the transcribed row: the day the workbook grows a
     * {@code SENDIG v3.0} row for one of these ids, its anchor moves without anyone deciding it.
     * Recorded rather than fixed — the citation half belongs to the review lane, not to this plan.
     * </p>
     */
    private static final List<String> EXPECTED_PARTIALLY_UNDECLARED = List.of("CDISC-SEND-0056",
            "CDISC-SEND-0057", "CDISC-SEND-0268", "CDISC-SEND-0269", "CDISC-SEND-0270");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static SequencedMap<String, String> fields()
    {
        SequencedMap<String, String> map = new LinkedHashMap<>();
        map.put("Class", "Class");
        map.put("Domain", "Domain");
        map.put("Variable", "Variable");
        map.put("Plain_Text_Explanation", "Plain Text Explanation of Condition/Rule");
        map.put("Condition", "Condition");
        map.put("Rule", "Rule");
        return map;
    }

    // ---- outcome model ----------------------------------------------------

    /** One workbook row of one sheet id: its 1-based row number, version cell and guarded cells. */
    private record SheetRow(int sheetRow, String versionCell, Map<String, String> cells)
    {
    }


    /** One guarded rule, resolved against the sheet. */
    private record ResolvedRule(String coreId, @Nullable String sheetId,
            @Nullable String resolvedId, @Nullable SheetRow primary,
            SequencedMap<String, String> source, boolean viaFallback)
    {
    }


    /** Everything one scan produced, so each test asserts a different facet of the same read. */
    private record Scan(Map<String, List<SheetRow>> sheet, List<ResolvedRule> rules,
            List<String> unresolved, List<String> fallbacks, List<String> undeclared,
            List<String> partiallyUndeclared, List<String> mismatches, int comparisons)
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
        assertTrue(scan.sheet().size() >= MIN_SHEET_IDS,
                "positive control: '" + SHEET + "' in " + WORKBOOK + " must yield at least "
                        + MIN_SHEET_IDS + " distinct '" + ID_COLUMN + "' values (2026-08-22: 435);"
                        + " found " + scan.sheet().size() + ". A shrunken read means the header row"
                        + " or the id column moved — fix the read before trusting any comparison.");
        assertTrue(scan.rules().size() >= MIN_RULES,
                "positive control: at least " + MIN_RULES + " rules matching " + ID_PATTERN
                        + " must be found under " + SRC_CHECKS.toAbsolutePath()
                        + " (2026-08-22: 332); found " + scan.rules().size());
        assertTrue(scan.comparisons() >= MIN_COMPARISONS,
                "positive control: at least " + MIN_COMPARISONS + " cell comparisons must actually"
                        + " have been performed (2026-08-22: 1938); performed " + scan.comparisons()
                        + ". Zero mismatches out of zero comparisons is a"
                        + " broken scan, not a clean corpus.");
    }


    /**
     * The guard proper: every guarded {@code Source} key of every {@code CDISC-SEND*} rule is
     * byte-identical to the rule's own primary row, line breaks excepted.
     */
    @Test
    void everySendSourceBlockMatchesItsOwnPrimaryRow() throws IOException
    {
        Scan scan = scan();
        assertTrue(scan.comparisons() >= MIN_COMPARISONS, "control: only " + scan.comparisons()
                + " comparisons were performed; the scan is broken, not the corpus clean");

        assertEquals(EXPECTED_VARIANTS, scan.mismatches(),
                scan.mismatches().size() + " of " + scan.comparisons() + " Source cells no longer"
                        + " match the rule's own primary row on '" + SHEET + "' in " + WORKBOOK
                        + ". The Source block is a transcription written by the offline tool"
                        + " OriginalTextBackfill and must NEVER be hand-edited — re-run"
                        + " scripts/backfill-rules.sh --stage 1 -b CDISC-SEND instead. If the row"
                        + " policy itself is what changed, re-read"
                        + " plans/done/PLAN-send-source-backfill-reanchor.md §5.1 before re-pinning"
                        + " anything.");
    }


    /**
     * Every {@code CDISC-SEND*} rule resolves to a sheet row, except the nine minted ones that have
     * none. Pinned exactly, so a rule that stops resolving cannot hide.
     */
    @Test
    void everySendRuleResolvesToASheetRowOrIsAKnownMintedRule() throws IOException
    {
        Scan scan = scan();
        assertTrue(scan.rules().size() >= MIN_RULES,
                "control: only " + scan.rules().size() + " rules were scanned");
        assertEquals(MINTED_WITHOUT_A_SHEET_ROW, scan.unresolved(),
                "the set of SEND rules with no resolvable '" + SHEET + "' row moved. A rule whose"
                        + " provenance cannot be resolved has an unverifiable Source block; a"
                        + " coreJ-minted rule legitimately has none. Re-derive before re-pinning.");
        for (String coreId : MINTED_WITHOUT_A_SHEET_ROW)
        {
            ResolvedRule rule = rule(scan, coreId);
            assertTrue(rule.source().isEmpty(),
                    coreId + " has no sheet row yet carries a Source block — orphaned provenance");
        }
    }


    /**
     * The split-suffix fallback ({@code SEND1-A} → {@code SEND1}) fires for no SEND rule. ⚠ An
     * empty pin alone would be vacuous — a broken scan also reports no fallbacks — so both
     * letter-suffixed siblings' verbatim resolution is asserted positively.
     */
    @Test
    void theSplitSuffixFallbackFiresForNoRule() throws IOException
    {
        Scan scan = scan();
        assertEquals(EXPECTED_FALLBACKS, scan.fallbacks(),
                "the split-suffix lookup fallback now fires for a SEND rule. It is a lookup"
                        + " relaxation: every id it rescues is an id that did NOT match the sheet"
                        + " verbatim. Confirm any new entry is a genuine split before re-pinning.");
        for (Map.Entry<String, String> split : Map
                .of("CDISC-SEND-0001-A", "SEND1", "CDISC-SEND-0012-A", "SEND12").entrySet())
        {
            ResolvedRule rule = rule(scan, split.getKey());
            assertEquals(split.getValue(), rule.sheetId(),
                    split.getKey() + " must cite the parent's verbatim sheet id");
            assertFalse(rule.viaFallback(), split.getKey()
                    + " must resolve verbatim — a fallback here means the citation regressed");
        }
    }


    /**
     * {@code M3-D24}'s policy is only well-defined while every rule's primary row is one of the
     * versions the rule itself declares. Measured empty over all 323 resolved rules.
     */
    @Test
    void noRulesPrimaryRowFallsOutsideItsDeclaredVersions() throws IOException
    {
        Scan scan = scan();
        assertTrue(scan.rules().size() >= MIN_RULES,
                "control: only " + scan.rules().size() + " rules were scanned");
        assertEquals(EXPECTED_UNDECLARED, scan.undeclared(),
                "these rules cite a sheet id none of whose rows carry a version the rule declares,"
                        + " so the row choice fell back to the lowest candidate row. That is"
                        + " reported rather than fatal in the tool, but it means the Source block"
                        + " transcribes a version the rule does not ship in.");
    }


    /**
     * The narrowing half of the declared-version check: a rule declaring a spelling no row of its
     * cited id carries. Five today, pinned by name — a sixth is a finding, not a re-pin.
     */
    @Test
    void theRulesDeclaringAVersionTheirSheetIdDoesNotHaveAreExactlyTheFiveKnownOnes()
        throws IOException
    {
        Scan scan = scan();
        assertTrue(scan.rules().size() >= MIN_RULES,
                "control: only " + scan.rules().size() + " rules were scanned");
        assertEquals(EXPECTED_PARTIALLY_UNDECLARED, scan.partiallyUndeclared(),
                "the set of SEND rules whose Standards[] declares a version their cited sheet id"
                        + " does not carry has moved. Since M3-D24 the declared set CHOOSES the"
                        + " transcribed row, so a spelling that matches nothing silently narrows"
                        + " the eligible pool — and would move the anchor the day the workbook"
                        + " grows that row. Re-derive before re-pinning.");
    }


    /**
     * The {@code Source} block's key order is the registry's field order — a block carrying the
     * right values in the wrong order is still drift, and stage 3's canonicaliser is not a reason
     * to leave it uncompared.
     */
    @Test
    void everySendSourceBlockKeepsTheRegistrysFieldOrder() throws IOException
    {
        Scan scan = scan();
        assertTrue(scan.rules().size() >= MIN_RULES,
                "control: only " + scan.rules().size() + " rules were scanned");
        List<String> offenders = new ArrayList<>();
        int compared = 0;
        for (ResolvedRule rule : scan.rules())
        {
            if (rule.source().isEmpty())
            {
                continue;
            }
            compared++;
            List<String> expected = FIELDS.keySet().stream()
                    .filter(k -> rule.source().containsKey(k)).toList();
            if (!expected.equals(new ArrayList<>(rule.source().keySet())))
            {
                offenders.add(rule.coreId() + ": " + rule.source().keySet() + " != " + expected);
            }
        }
        assertTrue(compared >= MIN_RULES - MINTED_WITHOUT_A_SHEET_ROW.size(),
                "control: only " + compared + " Source blocks were compared");
        assertEquals(List.of(), offenders, "SEND Source key order drifted from the registry order");
    }


    /**
     * Guards the guard's own coverage: a SEND {@code Source} block may hold only the keys
     * {@link #FIELDS} compares. A seventh registry field would otherwise sit untranscribed-and-
     * unchecked forever.
     */
    @Test
    void everySourceKeyOnASendRuleIsGuarded() throws IOException
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
                "SEND Source blocks now carry key(s) this guard does not compare. Add them to"
                        + " FIELDS with their sheet column label — an unguarded Source key is an"
                        + " untested transcription.");
    }

    // ---- scan -------------------------------------------------------------


    private static ResolvedRule rule(Scan scan, String coreId)
    {
        return scan.rules().stream().filter(r -> coreId.equals(r.coreId())).findFirst()
                .orElseThrow(() -> new AssertionError(coreId + " was not scanned at all — the pins"
                        + " above are only meaningful while it is in the population"));
    }

    /** Memoised {@link #readAll} — the tests assert different facets of one read. */
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
        String absent = aWhy + " — the SEND Source-block drift guard cannot run. The conformance"
                + " workbooks are a licensed external document set, not part of this repository"
                + " and with no location inside it; point -D" + DOCS_DIR_PROPERTY
                + " at a directory holding " + WORKBOOK + ".";
        if (Boolean.getBoolean("cdisc.docs.required"))
        {
            throw new IllegalStateException(
                    absent + " FAILING because -Dcdisc.docs.required=true was passed.");
        }
        Assumptions.abort(absent + " SKIPPED rather than passed; pass -Dcdisc.docs.required=true"
                + " to make this absence a build failure instead.");
    }


    /** Reads the workbook and the corpus and resolves one against the other. */
    private static Scan readAll(Path workbook) throws IOException
    {
        Map<String, List<SheetRow>> sheet = readSheet(workbook);

        List<ResolvedRule> rules = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        List<String> fallbacks = new ArrayList<>();
        List<String> undeclared = new ArrayList<>();
        List<String> partiallyUndeclared = new ArrayList<>();
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
            SequencedMap<String, String> source = sourceBlock(root);
            if (viaFallback)
            {
                fallbacks.add(coreId);
            }
            if (resolvedId == null)
            {
                unresolved.add(coreId);
                rules.add(new ResolvedRule(coreId, sheetId, null, null, source, viaFallback));
                continue;
            }
            List<SheetRow> candidates = sheet.get(resolvedId);
            Set<String> declared = declaredVersions(root);
            List<SheetRow> eligible = candidates.stream()
                    .filter(r -> declared.contains(r.versionCell())).toList();
            boolean noneDeclared = eligible.isEmpty();
            if (noneDeclared)
            {
                undeclared.add(coreId);
                eligible = candidates;
            }
            else if (eligible.size() < declared.size())
            {
                // At least one declared spelling matches no row of this id: the eligible pool is
                // narrower than the rule's own Standards block claims. See the field javadoc.
                partiallyUndeclared.add(coreId);
            }
            SheetRow primary = eligible.stream().min(ROW_ORDER).orElseThrow();
            rules.add(new ResolvedRule(coreId, sheetId, resolvedId, primary, source, viaFallback));

            for (var field : FIELDS.entrySet())
            {
                comparisons++;
                String expected = primary.cells().getOrDefault(field.getValue(), "");
                String actual = source.get(field.getKey());
                // A blank sheet cell must leave the key absent, not present-and-empty.
                boolean ok = expected.isEmpty() ? actual == null : expected.equals(actual);
                if (!ok)
                {
                    mismatches.add(String.format(Locale.ROOT,
                            "%s Source.%s: sheet row %d (%s) =%s corpus=%s", coreId, field.getKey(),
                            primary.sheetRow(), primary.versionCell(),
                            quote(expected.isEmpty() ? null : expected), quote(actual)));
                }
            }
        }
        rules.sort(Comparator.comparing(ResolvedRule::coreId));
        unresolved.sort(String::compareTo);
        fallbacks.sort(String::compareTo);
        undeclared.sort(String::compareTo);
        partiallyUndeclared.sort(String::compareTo);
        mismatches.sort(String::compareTo);
        return new Scan(sheet, rules, unresolved, fallbacks, undeclared, partiallyUndeclared,
                mismatches, comparisons);
    }

    /**
     * The {@code M3-D24} row order: family rank, then family name, then the numeric parts
     * element-wise (a missing part reads as 0), then the lower sheet row.
     */
    private static final Comparator<SheetRow> ROW_ORDER = (a, b) ->
    {
        int compared = compareVersions(a.versionCell(), b.versionCell());
        return compared != 0 ? compared : Integer.compare(a.sheetRow(), b.sheetRow());
    };

    private static final Pattern VERSION_CELL = Pattern
            .compile("^(?:([A-Za-z][A-Za-z0-9 _.-]*?)\\s+)?[vV]?(\\d+(?:\\.\\d+)*)$");

    private static final Pattern DOT = Pattern.compile("\\.");

    /** Orders two version cells. Deliberately a second implementation, not a delegation. */
    static int compareVersions(String a, String b)
    {
        Matcher ma = VERSION_CELL.matcher(a.strip());
        Matcher mb = VERSION_CELL.matcher(b.strip());
        boolean pa = ma.matches();
        boolean pb = mb.matches();
        String familyA = pa && ma.group(1) != null ? ma.group(1).strip() : pa ? "" : a.strip();
        String familyB = pb && mb.group(1) != null ? mb.group(1).strip() : pb ? "" : b.strip();
        int rankA = rank(familyA);
        int rankB = rank(familyB);
        if (rankA != rankB)
        {
            return Integer.compare(rankA, rankB);
        }
        int byName = familyA.compareTo(familyB);
        if (byName != 0)
        {
            return byName;
        }
        List<Integer> numbersA = pa ? numbers(ma.group(2)) : List.of();
        List<Integer> numbersB = pb ? numbers(mb.group(2)) : List.of();
        for (int i = 0; i < Math.max(numbersA.size(), numbersB.size()); i++)
        {
            int ai = i < numbersA.size() ? numbersA.get(i) : 0;
            int bi = i < numbersB.size() ? numbersB.get(i) : 0;
            if (ai != bi)
            {
                return Integer.compare(ai, bi);
            }
        }
        return 0;
    }


    private static List<Integer> numbers(String parts)
    {
        List<Integer> out = new ArrayList<>();
        for (String part : DOT.split(parts, -1))
        {
            out.add(Integer.valueOf(part));
        }
        return out;
    }


    private static int rank(String family)
    {
        int i = FAMILY_ORDER.indexOf(family);
        return i < 0 ? FAMILY_ORDER.size() : i;
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
     * Reads {@link #SHEET} into {@code CDISC SEND Rule ID -> every row}, in sheet order. A missing
     * sheet, a missing required column, or an unfindable header row is a hard failure.
     */
    private static Map<String, List<SheetRow>> readSheet(Path workbook) throws IOException
    {
        Map<String, List<SheetRow>> out = new HashMap<>();
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
                    values.put(column, transcribe(cell(row, columns.get(column))));
                }
                out.computeIfAbsent(id, _ -> new ArrayList<>())
                        .add(new SheetRow(r + 1, cell(row, columns.get(VERSION_COLUMN)), values));
            }
        }
        return out;
    }

    private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n]+");

    /**
     * The one documented exception to verbatim transcription: a run of {@code \r} / {@code \n}
     * inside an already-trimmed cell becomes a single space (owner ruling 2026-08-21). Duplicated
     * from {@code OriginalTextBackfill.transcribe} on purpose — see the class Javadoc.
     */
    private static String transcribe(String cellText)
    {
        return LINE_BREAKS.matcher(cellText).replaceAll(" ");
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
        req.add(VERSION_COLUMN);
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
     * The version cells the check declares, spelled as the sheet's version column spells them:
     * {@code Name + " v" + Version} over every {@code Standards[].Standards[]} entry.
     */
    private static Set<String> declaredVersions(JsonNode root)
    {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode authority : orEmpty(root.get("Standards")))
        {
            for (JsonNode std : orEmpty(authority.get("Standards")))
            {
                String name = text(std.get("Name"));
                String version = text(std.get("Version"));
                if (name != null && version != null)
                {
                    out.add(name + " v" + version);
                }
            }
        }
        return out;
    }


    /**
     * The first {@code Standards[].Standards[].References[].Rule_Identifier.Id} whose
     * {@code Origin} is {@link #ORIGIN} — the sheet-native lookup key.
     */
    private static @Nullable String referenceId(JsonNode root)
    {
        for (JsonNode authority : orEmpty(root.get("Standards")))
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
     * Strips ONE trailing uppercase letter — directly after a digit or hyphen-joined after a digit
     * — mirroring {@code OriginalTextBackfill.stripSplitSuffix}. Applied only after a verbatim
     * lookup miss. Deliberately a second implementation, not a delegation.
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
