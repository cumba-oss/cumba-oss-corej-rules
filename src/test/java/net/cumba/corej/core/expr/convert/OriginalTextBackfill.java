package net.cumba.corej.core.expr.convert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.jspecify.annotations.Nullable;

/**
 * Offline developer tool — splices a single root-level {@code Source:} block onto every generated
 * check file under {@code rules-src/checks/} (plan
 * {@code plans/done/PLAN-source-block-migration.md}). The block holds the verbatim columns of the
 * CDISC / FDA / PMDA conformance-rule spreadsheet row the rule was generated from, so a reviewer
 * can review a rule in isolation without opening the Excel workbook. It supersedes the three
 * scattered legacy keys ({@code Original_Description} / {@code Original_Condition} at root and
 * {@code Outcome.Original_Message} at indent 2), which this same pass removes.
 *
 * <p>
 * The {@code Source} block is <strong>verbatim own-sheet text only</strong> — it carries no
 * cross-family linkage keys. Cross-family provenance (FDA→CG, FDA→FDA-Business, PMDA→FDA) is
 * expressed structurally as {@code References[]} under {@code Standards[]} by the sibling
 * {@link StandardsLinkageBackfill} tool (stage 2 of {@code scripts/backfill-rules.sh}).
 * </p>
 *
 * <p>
 * Sources are described by the {@link #REGISTRY}: each {@link SheetBinding} maps one <em>(workbook,
 * sheet)</em> to the {@code Core.Id} {@link SheetBinding#idPattern() pattern} it backfills and the
 * ordered {@link SourceField list of columns} it emits (keys mirror the sheet column labels in
 * {@code Pascal_Snake_Case}). A rule is matched to a sheet by its {@code Core.Id} —
 * <strong>not</strong> by its family folder — so several sheets (e.g. CDISC-CG, CDISC-AD and
 * CDISC-SEND) can share the one {@code checks/CDISC} folder, and one workbook can register several
 * sheets (PMDA's {@code SDTM Rules} and {@code ADaM Rules}). The sheet-native lookup key is each
 * rule's {@code Standards[].References[].Rule_Identifier.Id} under the binding's {@code Origin};
 * ADaM references carry a {@code CR} prefix that is stripped to match the numeric sheet Rule ID.
 * Which of an id's rows a rule transcribes is the binding's {@link RowPolicy}: {@code FIRST_ROW}
 * for the unversioned sheets, {@code HIGHEST_VERSION} for {@code CDISC-CG} / {@code CDISC-AD}, and
 * {@code OWN_PRIMARY_ROW} for {@code CDISC-SEND} — the lowest version cell the rule itself declares
 * ({@code M3-D24}, plan {@code plans/done/PLAN-send-source-backfill-reanchor.md}). Empty source
 * cells omit the key rather than writing an empty string; a blank cell on the chosen row is filled
 * from a sibling row of the same family and the same-or-lower version when one exists
 * ({@link #select}).
 * </p>
 *
 * <p>
 * ⭐ <strong>The one documented exception to verbatim fidelity: line breaks.</strong> Every
 * {@code \r} / {@code \n} run inside a source cell is transcribed as a <em>single space</em>
 * ({@link #transcribe}) — owner ruling of 2026-08-21, applied to <em>every</em> binding's
 * transcription path, not just SEND. Nothing else is normalised: inner runs of spaces, curly quotes
 * and sheet typos are all preserved exactly. The rule exists because a {@code Source} block is a
 * one-line-per-column reviewer aid and an embedded newline (the SEND workbook has one
 * {@code _x000D_} cell and ten more with an inner {@code \n}) round-trips through three YAML
 * writers. A CRLF is one line break and therefore yields one space, not two.
 * </p>
 *
 * <p>
 * The rewrite is a <em>textual</em> insertion: the {@code Source} block is spliced immediately
 * before the top-level {@code Standards:} key and every existing line is left byte-for-byte
 * untouched. This is deliberate — the corpus mixes two YAML styles (splitter-quoted and
 * hand-authored unquoted), so re-serialising a whole file would reflow quoting on the unquoted
 * files. The inserted block itself is formatted with the same Jackson YAML writer as
 * {@code RuleStorageSplitter} (deleted in wave 33) (double-quoted, folded).
 * </p>
 *
 * <pre>{@code
 * cd lib/corej-core
 * mvn -P main -q org.codehaus.mojo:exec-maven-plugin:3.6.1:java \
 *     -Dexec.classpathScope=test \
 *     -Dexec.mainClass=net.cumba.corej.core.expr.convert.OriginalTextBackfill
 * }</pre>
 *
 * <p>
 * The workbook directory is <b>required</b> and has no default — the conformance workbooks are a
 * licensed external document set, not part of this repository and with no location inside it, so
 * {@code -Dcdisc.docs.dir=...} must name one and the tool refuses to start without it. The checks
 * root defaults to {@code rules-src/checks} (relative to the process working directory); override
 * with {@code -Dbackfill.checksRoot=...}. Limit to one binding with
 * {@code -Dbackfill.binding=CDISC-AD} (a {@link SheetBinding#name()}). Compute and report without
 * writing with {@code -Dbackfill.dryRun=true}.
 * </p>
 *
 * <p>
 * ⛔ <b>{@code Source_Proposed} is OUT OF THIS TOOL'S SCOPE and must stay that way.</b> {@code
 * Source} is tool-written from a workbook and is verbatim by contract; {@code Source_Proposed} is
 * the row we ask the authority to publish, hand-authored via
 * {@code documentation/rule-review-results/sheet-proposals.yaml} and applied by
 * {@code SourceProposedBackfill} ({@code plans/done/PLAN-source-proposed-block.md} §2.3). This tool
 * neither reads nor writes it — {@link #stripSource} matches {@code "Source:"} at indent 0, which
 * {@code "Source_Proposed:"} does not start with, so the block survives every run untouched. ⚠
 * Consequently the three spellings {@code stripSource} deletes unconditionally —
 * {@code Original_Description:} / {@code Original_Condition:} / {@code Source:} at indent 0 and
 * {@code Original_Message:} at indent 2 — must <b>never</b> appear as keys inside a
 * {@code Source_Proposed} block: no family key set uses them, so the prohibition costs nothing, and
 * it is written down because the deletion would be silent.
 * </p>
 */
public final class OriginalTextBackfill
{

    private static final ObjectMapper YAML_READ = new ObjectMapper(new YAMLFactory());

    /**
     * Same config as {@code RuleStorageSplitter} (deleted in wave 33): default (non-minimised)
     * quoting.
     */
    static final ObjectMapper YAML_WRITE = new ObjectMapper(
            YAMLFactory.builder().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).build());

    private static final Path SRC_CHECKS = Path.of("rules-src", "checks");

    /**
     * System property naming the directory that holds the licensed conformance workbooks. Required,
     * with no default: the workbooks are not in this repository, and a path outside it is not
     * something this repository may assume exists.
     */
    private static final String DOCS_DIR_PROPERTY = "cdisc.docs.dir";

    private static final Pattern DOT = Pattern.compile("\\.");

    // ---- data model -------------------------------------------------------

    /** One emitted {@code Source} key mapped to its sheet column label. */
    record SourceField(String yamlKey, String sheetColumn)
    {
    }


    /** How a binding picks the one row of a sheet id that a rule's {@code Source} transcribes. */
    enum RowPolicy
    {
        /** No version column: the first row per id wins (FDA, PMDA-SDTM, PMDA-AD). */
        FIRST_ROW,
        /**
         * The highest-version row per id wins (CDISC-CG, CDISC-AD) — the pre-{@code M3-D24}
         * behaviour, tie included: scanning in sheet order and keeping only a strictly greater
         * version leaves the <em>earlier</em> row in place on a tie.
         */
        HIGHEST_VERSION,
        /**
         * The rule's OWN primary row: of the rows whose version cell the check itself declares in
         * its {@code Standards[].Standards[]} block, the lowest under
         * {@link #compareVersions(SheetBinding, VersionKey, VersionKey)}, the lower sheet row
         * breaking a tie. CDISC-SEND only ({@code M3-D24}, plan
         * {@code plans/done/PLAN-send-source-backfill-reanchor.md}).
         */
        OWN_PRIMARY_ROW
    }


    /** One workbook row for a sheet id, carrying its 1-based sheet row number. */
    record SourceRow(int sheetRow, String versionCell, VersionKey version,
            SequencedMap<String, String> fields)
    {
    }


    /**
     * The chosen row, the {@code Source} field map built from it, and every field taken from a
     * sibling row because the chosen cell was blank.
     *
     * @param undeclared
     *            {@code true} when no candidate row's version cell is one the check declares, so
     *            the lowest candidate row was taken instead (reported, never fatal)
     */
    record Selection(SourceRow row, SequencedMap<String, String> fields, List<Fallback> fallbacks,
            boolean undeclared)
    {
    }


    /** One (b1) per-field fallback, for the run report. Never written into the Source block. */
    record Fallback(String yamlKey, int fromSheetRow, String fromVersionCell)
    {
    }


    /**
     * A parsed version cell: an optional alphabetic family head plus its numeric parts.
     * {@code SENDIG-DART v1.2} → family {@code SENDIG-DART}, numbers {@code [1, 2]}; {@code 3.4} →
     * family {@code ""}, numbers {@code [3, 4]}.
     *
     * <p>
     * ⭐ The predecessor ({@code parseVersion}, deleted by {@code M3-D24}) split on {@code .} and
     * mapped every non-numeric part to {@code 0}, so the whole alphabetic head of a SEND cell
     * collapsed and {@code SENDIG-DART v1.2} → {@code [0, 2]} outranked {@code SENDIG v3.1.1} →
     * {@code [0, 1, 1]} family-wide — 285 of the 323 joined SEND rules transcribed the DART-v1.2
     * row (re-measured 2026-08-22; the dossier's figure was 286 of 326 before three rules were
     * retired).
     * </p>
     *
     * <p>
     * The family head must be followed by whitespace, so a bare {@code v1.2} parses as family
     * {@code ""} rather than family {@code "v"}. An unparseable cell keeps its raw text as the
     * family and an empty number list, which sorts after every ranked family; it is never silently
     * treated as version zero (except the empty cell, which is exactly "no version" and compares
     * equal to {@code 0} — the unversioned bindings rely on that).
     * </p>
     */
    record VersionKey(String family, List<Integer> numbers)
    {

        private static final Pattern CELL = Pattern.compile(
                "^(?:(?<family>[A-Za-z][A-Za-z0-9 _.-]*?)\\s+)?[vV]?(?<numbers>\\d+(?:\\.\\d+)*)$");

        static VersionKey parse(String cell)
        {
            String raw = cell.strip();
            Matcher m = CELL.matcher(raw);
            if (!m.matches())
            {
                return new VersionKey(raw, List.of());
            }
            String family = m.group("family");
            List<Integer> nums = new ArrayList<>();
            for (String part : DOT.split(m.group("numbers"), -1))
            {
                nums.add(Integer.valueOf(part));
            }
            return new VersionKey(family == null ? "" : family.strip(), List.copyOf(nums));
        }
    }


    /**
     * One {@code (workbook, sheet)} source bound to the {@code Core.Id} pattern it backfills.
     * Selection is by {@code idPattern} (matched against {@code Core.Id}), so several sheets can
     * share one family folder; the sheet-native lookup key still comes from the rule's
     * {@code References} under {@code origin} (optionally passed through {@code idNormalizer}).
     *
     * @param name
     *            registry key (also the {@code -Dbackfill.binding} filter value)
     * @param workbook
     *            source workbook file name under the docs dir
     * @param sheet
     *            sheet name within the workbook
     * @param idPattern
     *            {@code Core.Id} prefix pattern this binding backfills
     * @param origin
     *            the {@code References[].Origin} the sheet id is read from
     * @param idColumn
     *            the sheet's native id column label
     * @param versionColumn
     *            the sheet's version column ({@code null} ⇒ every row's version key is blank)
     * @param rowPolicy
     *            how one of an id's rows is picked for the {@code Source} block
     * @param familyOrder
     *            the version-cell family heads in ascending order (empty ⇒ the sheet's version
     *            cells are bare numerics, so there is no family to rank)
     * @param fields
     *            the ordered {@code Source} keys emitted for this sheet
     * @param idNormalizer
     *            maps a raw reference id to the sheet key ({@code null} ⇒ identity)
     */
    record SheetBinding(String name, String workbook, String sheet, Pattern idPattern,
            String origin, String idColumn, @Nullable String versionColumn, RowPolicy rowPolicy,
            List<String> familyOrder, List<SourceField> fields,
            @Nullable UnaryOperator<String> idNormalizer)
    {

        /**
         * The sheet key for a raw reference id: trimmed, then the binding's normaliser (if any).
         */
        String normalize(String id)
        {
            String trimmed = id.strip();
            return idNormalizer == null ? trimmed : idNormalizer.apply(trimmed);
        }
    }

    /**
     * A field of one {@code (yamlKey, sheetColumn)} pair — spelled out for registry readability.
     */
    private static SourceField f(String yamlKey, String sheetColumn)
    {
        return new SourceField(yamlKey, sheetColumn);
    }

    /**
     * The SEND version families in ascending order. ⚑ {@code SENDIG-AR} does <em>not</em> occur in
     * {@code SEND_Conformance_Rules_v5.0.xlsx} (its {@code SENDIG Version} column holds exactly six
     * distinct values, measured 2026-08-22); it is listed defensively for the next workbook
     * revision and is pinned by a unit test, not by corpus data.
     */
    private static final List<String> SEND_FAMILY_ORDER = List.of("SENDIG", "SENDIG-DART",
            "SENDIG-GENETOX", "SENDIG-AR");

    /**
     * The shipped sheet registry. Each entry binds a workbook sheet to the {@code Core.Id} prefix
     * pattern it backfills. The PMDA {@code SDTM Rules} sheet holds both {@code SD####} and
     * {@code CT####} native ids, so it backs the {@code PMDA-SD} <em>and</em> {@code PMDA-CT}
     * families (pattern {@code ^PMDA-(SD|CT)}).
     */
    static final List<SheetBinding> REGISTRY = List.of(
            new SheetBinding("FDA", "FDA-validator-rules-v1-6.xlsx", "FDA Validator Rules v1.6",
                    Pattern.compile("^FDA-"), "FDA Validator Rules", "FDA Validator Rule ID", null,
                    RowPolicy.FIRST_ROW, List.of(),
                    List.of(f("FDA_Validator_Rule_Message", "FDA Validator Rule Message"),
                            f("FDA_Validator_Rule_Description", "FDA Validator Rule Description"),
                            f("Domains", "Domains")),
                    null),
            new SheetBinding("CDISC-CG", "SDTM_and_SDTMIG_Conformance_Rules_v2.0.xlsx",
                    "SDTMIG Conformance Rules v2.0", Pattern.compile("^CDISC-CG"),
                    "SDTM and SDTMIG Conformance Rules", "Rule ID", "SDTMIG Version",
                    RowPolicy.HIGHEST_VERSION, List.of(),
                    List.of(f("Class", "Class"), f("Domain", "Domain"), f("Variable", "Variable"),
                            f("Condition", "Condition"), f("Rule", "Rule")),
                    null),
            new SheetBinding("CDISC-SEND", "SEND_Conformance_Rules_v5.0.xlsx",
                    "_GENETOXv1.0_DARTv1.2", Pattern.compile("^CDISC-SEND"),
                    "SEND Conformance Rules", "CDISC SEND Rule ID", "SENDIG Version",
                    RowPolicy.OWN_PRIMARY_ROW, SEND_FAMILY_ORDER,
                    List.of(f("Class", "Class"), f("Domain", "Domain"), f("Variable", "Variable"),
                            f("Plain_Text_Explanation", "Plain Text Explanation of Condition/Rule"),
                            f("Condition", "Condition"), f("Rule", "Rule")),
                    null),
            new SheetBinding("CDISC-AD", "ADaM Conformance Rules v5.0.xlsx", "Rules Catalogue",
                    Pattern.compile("^CDISC-AD"), "ADaM Conformance Rules", "Rule ID",
                    "Rule Set (Generally IG Version, OCCDS v1.0, ADNCA v1.0)",
                    RowPolicy.HIGHEST_VERSION, List.of(),
                    List.of(f("Class", "Class"), f("Subclass", "Subclass"),
                            f("SEND_SDTM_Domain", "SEND/SDTM Domain"),
                            f("Variable_or_Item", "Variable or Item"),
                            f("Natural_Language_Rule_Failure_Criteria",
                                    "Natural Language Rule (Failure Criteria)"),
                            f("Rule_Failure_Criteria", "Rule (Failure Criteria)"),
                            f("Condition_Failure", "Condition (Failure)")),
                    OriginalTextBackfill::stripCrPrefix),
            new SheetBinding("PMDA-SDTM", "PMDA-ValidationRules-v6-0.xlsx", "SDTM Rules",
                    Pattern.compile("^PMDA-(SD|CT)"), "PMDA Validation Rules", "RULE ID", null,
                    RowPolicy.FIRST_ROW, List.of(),
                    List.of(f("Message", "MESSAGE"), f("Description", "DESCRIPTION"),
                            f("Domains", "DOMAINS"), f("Severity", "PMDA Severity")),
                    null),
            new SheetBinding("PMDA-AD", "PMDA-ValidationRules-v6-0.xlsx", "ADaM Rules",
                    Pattern.compile("^PMDA-AD"), "PMDA Validation Rules", "RULE ID", null,
                    RowPolicy.FIRST_ROW, List.of(),
                    List.of(f("Message", "MESSAGE"), f("Description", "DESCRIPTION"),
                            f("Domains", "DOMAINS"), f("Severity", "PMDA Severity")),
                    null));

    private OriginalTextBackfill()
    {
    }


    /**
     * Drops a leading {@code CR} / {@code cr} from an already-trimmed ADaM reference id
     * ({@code CR42 -> 42}).
     */
    static String stripCrPrefix(String id)
    {
        return id.length() > 2 && (id.charAt(0) == 'C' || id.charAt(0) == 'c')
                && (id.charAt(1) == 'R' || id.charAt(1) == 'r') ? id.substring(2) : id;
    }


    /** The registry binding with the given {@link SheetBinding#name()} (test + CLI helper). */
    static SheetBinding binding(String name)
    {
        for (SheetBinding b : REGISTRY)
        {
            if (b.name().equals(name))
            {
                return b;
            }
        }
        throw new IllegalArgumentException("unknown binding: " + name);
    }


    /**
     * Backfills the corpus in place.
     *
     * @param args
     *            ignored (configuration is via system properties)
     * @throws IOException
     *             if a workbook or check file cannot be read or written
     */
    public static void main(String[] args) throws IOException
    {
        String configuredDocs = System.getProperty(DOCS_DIR_PROPERTY, "");
        if (configuredDocs.isBlank())
        {
            throw new IllegalStateException("-D" + DOCS_DIR_PROPERTY + " is not set. The"
                    + " conformance workbooks this tool transcribes are a licensed external"
                    + " document set, not part of this repository and with no location inside it,"
                    + " so there is no default to fall back to: point -D" + DOCS_DIR_PROPERTY
                    + " at a directory holding them.");
        }
        Path docsDir = Path.of(configuredDocs);
        Path checksRoot = Path.of(System.getProperty("backfill.checksRoot", SRC_CHECKS.toString()));
        boolean dryRun = Boolean.getBoolean("backfill.dryRun");
        String only = System.getProperty("backfill.binding");

        Map<String, Map<String, List<SourceRow>>> index = loadAll(docsDir);
        for (SheetBinding b : REGISTRY)
        {
            if (only != null && !only.equalsIgnoreCase(b.name()))
            {
                continue;
            }
            Result result = process(b, index.get(b.name()), checksRoot, dryRun);
            System.out.print(result.report(b.name(), dryRun));
        }
    }

    /**
     * Per-binding run counters and residual lists.
     *
     * @param fallbacks
     *            one line per (b1) blank-cell fallback — a field taken from a same-family,
     *            same-or-lower-version sibling row because the chosen row's cell was blank. ⚑
     *            Nothing is written into the {@code Source} block to mark it ({@code M3-D24}
     *            authorises no new key there), so this report is the only record that a block was
     *            assembled from two rows.
     * @param undeclared
     *            rules under {@code OWN_PRIMARY_ROW} that cite a sheet id none of whose rows carry
     *            a version the rule declares; the lowest candidate row is used and the rule is
     *            named here. Reported, never fatal.
     */
    record Result(int files, int changed, List<String> noId, List<String> noRow,
            List<String> fallbacks, List<String> undeclared)
    {

        String report(String binding, boolean dryRun)
        {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT,
                    "[backfill] %-10s files=%d changed=%d | no-id=%d no-row=%d"
                            + " | fallbacks=%d undeclared=%d%s%n",
                    binding, files, changed, noId.size(), noRow.size(), fallbacks.size(),
                    undeclared.size(), dryRun ? " (dry-run)" : ""));
            if (!noId.isEmpty())
            {
                sb.append(String.format(Locale.ROOT, "           no-id : %s%n", noId));
            }
            if (!noRow.isEmpty())
            {
                sb.append(String.format(Locale.ROOT, "           no-row: %s%n", noRow));
            }
            for (String fallback : fallbacks)
            {
                sb.append(String.format(Locale.ROOT, "           fallback: %s%n", fallback));
            }
            if (!undeclared.isEmpty())
            {
                sb.append(String.format(Locale.ROOT, "           undeclared-primary: %s%n",
                        undeclared));
            }
            return sb.toString();
        }
    }

    // ---- load -------------------------------------------------------------

    /** Loads every binding's raw source index ({@code binding name -> id -> rows}). */
    static Map<String, Map<String, List<SourceRow>>> loadAll(Path docsDir) throws IOException
    {
        Map<String, Map<String, List<SourceRow>>> index = new HashMap<>();
        for (SheetBinding b : REGISTRY)
        {
            index.put(b.name(), loadSheet(docsDir.resolve(b.workbook()), b));
        }
        return index;
    }

    // ---- CG Publisher-ID parser (reused by StandardsLinkageBackfill) ------

    /** Result of {@link #parseCgIds}: the resolved ids and any tokens that did not parse. */
    record CgParse(List<String> ids, List<String> unparseable)
    {
    }

    private static final Pattern CG_TOKEN_SPLIT = Pattern.compile("[,/;]|\\s+and\\s+");

    private static final Pattern CG_RANGE = Pattern.compile("^CG(\\d+)-CG(\\d+)$");

    private static final Pattern CG_FULL = Pattern.compile("^CG\\d+(?:\\.\\d+)?$");

    private static final Pattern CG_BARE = Pattern.compile("^(\\d+)(\\.\\d+)?$");

    /**
     * Parses an FDA {@code Publisher ID} cell into a list of {@code CG####} ids. Splits on
     * {@code , / ;} and the word {@code and}; expands {@code CGnnnn-CGmmmm} ranges; keeps full
     * {@code CG…} ids verbatim; pads bare numbers to {@code CG%04d} (keeping any decimal sub-id).
     * Any other token is returned in {@link CgParse#unparseable()}.
     */
    static CgParse parseCgIds(String raw)
    {
        List<String> ids = new ArrayList<>();
        List<String> unparseable = new ArrayList<>();
        for (String token : CG_TOKEN_SPLIT.split(raw, -1))
        {
            String t = token.strip();
            if (t.isEmpty())
            {
                continue;
            }
            Matcher range = CG_RANGE.matcher(t);
            if (range.matches())
            {
                int lo = Integer.parseInt(range.group(1));
                int hi = Integer.parseInt(range.group(2));
                for (int n = lo; n <= hi; n++)
                {
                    ids.add(String.format(Locale.ROOT, "CG%04d", n));
                }
                continue;
            }
            if (CG_FULL.matcher(t).matches())
            {
                ids.add(t);
                continue;
            }
            Matcher bare = CG_BARE.matcher(t);
            if (bare.matches())
            {
                int n = Integer.parseInt(bare.group(1));
                String frac = bare.group(2);
                ids.add(String.format(Locale.ROOT, "CG%04d", n) + (frac == null ? "" : frac));
                continue;
            }
            unparseable.add(t);
        }
        return new CgParse(ids, unparseable);
    }

    // ---- workbook reader --------------------------------------------------


    /**
     * Loads one sheet into a {@code sheetId -> rows} map, <strong>every</strong> row per id, in
     * sheet order. Each non-blank cell becomes a value keyed by its {@code yamlKey}, in
     * sheet-column order (line breaks collapsed to one space — see {@link #transcribe}); blank
     * cells omit the key.
     *
     * <p>
     * ⭐ Row <em>selection</em> deliberately does NOT happen here any more. {@code OWN_PRIMARY_ROW}
     * needs the check's own {@code Standards} block, which the loader never sees, so the pick moved
     * to {@link #select} — called per rule from {@link #process} ({@code M3-D24}).
     * </p>
     */
    static Map<String, List<SourceRow>> loadSheet(Path file, SheetBinding binding)
        throws IOException
    {
        Map<String, List<SourceRow>> out = new HashMap<>();
        // ⛔⛔ READ-ONLY, and the third argument is the whole point (finding P0-F1,
        // plans/done/PLAN-source-proposed-block.md §3.1). WorkbookFactory.create(File) opens
        // READ_WRITE, so POI touches the workbook on close: measured 2026-08-26, a --dry-run
        // census - a run that writes nothing by design - moved the mtime of all five workbooks it
        // read. That destroyed the only signal STANDING-RULINGS.md §1 baseline condition 4 has (an
        // upstream refresh became indistinguishable from a census run), and it opened the only
        // copy of an UNVERSIONED, un-upstream-pinned input in write mode.
        try (Workbook wb = WorkbookFactory.create(file.toFile(), null, true))
        {
            Sheet sheet = wb.getSheet(binding.sheet());
            if (sheet == null)
            {
                throw new IllegalStateException(
                        "missing sheet '" + binding.sheet() + "' in " + file.getFileName());
            }
            Header h = header(sheet, requiredLabels(binding));
            for (int r = h.dataStart(); r <= sheet.getLastRowNum(); r++)
            {
                Row row = sheet.getRow(r);
                String id = cell(row, h.col(binding.idColumn()));
                if (id.isEmpty())
                {
                    continue;
                }
                SequencedMap<String, String> fields = new LinkedHashMap<>();
                for (SourceField sf : binding.fields())
                {
                    String value = blankToNull(transcribe(cell(row, h.col(sf.sheetColumn()))));
                    if (value != null)
                    {
                        fields.put(sf.yamlKey(), value);
                    }
                }
                String versionCell = binding.versionColumn() == null ? ""
                        : cell(row, h.col(binding.versionColumn()));
                VersionKey version = VersionKey.parse(versionCell);
                if (binding.versionColumn() != null)
                {
                    assertKnownVersion(binding, version, versionCell, r + 1, file);
                }
                out.computeIfAbsent(id, _ -> new ArrayList<>())
                        .add(new SourceRow(r + 1, versionCell, version, fields));
            }
        }
        return out;
    }


    /**
     * Rejects a version cell outside the binding's declared vocabulary — a hard failure at load
     * time, before a single {@code Source} block is written.
     *
     * <p>
     * ⚠⚠ <strong>Why this is an exception and not a shrug.</strong> An unparseable or family-headed
     * cell yields a {@link VersionKey} that ranks <em>after</em> every known family
     * ({@link #familyRank}). Under {@code OWN_PRIMARY_ROW} that is harmless — such a row is simply
     * never the lowest. Under {@code HIGHEST_VERSION} it is the exact opposite: the stray row wins
     * and silently becomes the transcribed one. The realistic trigger is the {@code CDISC-AD}
     * column, whose own header advertises {@code OCCDS v1.0} / {@code ADNCA v1.0} spellings that do
     * not (yet) occur in its data — the day one lands, {@code ("OCCDS", [1,0])} would out-rank
     * {@code ("", [1,3])} and re-anchor an ADaM rule nobody decided to move. The same asymmetry
     * would bite SEND from the other side: a bare numeric there has family {@code ""}, which is not
     * in the SEND family order and so ranks last. Rather than encode a per-policy tie-break, the
     * loader refuses the input. Verified 2026-08-22: <strong>0</strong> of the 1 928 SEND / 1 315
     * CG / 1 963 ADaM id-bearing rows trip it.
     * </p>
     */
    private static void assertKnownVersion(SheetBinding binding, VersionKey version, String cell,
            int sheetRow, Path file)
    {
        boolean knownFamily = binding.familyOrder().isEmpty() ? version.family().isEmpty()
                : binding.familyOrder().contains(version.family());
        if (!knownFamily || version.numbers().isEmpty())
        {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "binding %s, %s row %d: version cell '%s' is outside the binding's declared"
                            + " vocabulary (families %s, then dotted numbers). A cell the version"
                            + " parser cannot rank sorts after every known family, which under"
                            + " HIGHEST_VERSION makes it WIN the row pick. Widen"
                            + " SheetBinding.familyOrder deliberately, or fix the workbook.",
                    binding.name(), file.getFileName(), sheetRow, cell,
                    binding.familyOrder().isEmpty() ? "<bare numerics only>"
                            : binding.familyOrder()));
        }
    }


    /**
     * The sheet's required header labels: the id column, the version column (if any), and every
     * field's sheet column.
     */
    private static String[] requiredLabels(SheetBinding binding)
    {
        List<String> req = new ArrayList<>();
        req.add(binding.idColumn());
        if (binding.versionColumn() != null)
        {
            req.add(binding.versionColumn());
        }
        for (SourceField sf : binding.fields())
        {
            req.add(sf.sheetColumn());
        }
        return req.toArray(new String[0]);
    }

    // ---- per-binding corpus rewrite --------------------------------------


    /**
     * Backfills every check whose {@code Core.Id} matches {@code binding.idPattern} (searched
     * recursively under {@code checksRoot}, family-folder-agnostic). The scan filters on the file
     * name, which is the {@code Core.Id} by the split-storage naming convention.
     */
    static Result process(SheetBinding binding, Map<String, List<SourceRow>> src, Path checksRoot,
            boolean dryRun)
        throws IOException
    {
        List<String> noId = new ArrayList<>();
        List<String> noRow = new ArrayList<>();
        List<String> fallbacks = new ArrayList<>();
        List<String> undeclared = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(checksRoot))
        {
            try (Stream<Path> stream = Files.walk(checksRoot))
            {
                stream.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                        .filter(p -> binding.idPattern().matcher(name(p)).find()).sorted()
                        .forEach(files::add);
            }
        }
        int changed = 0;
        for (Path file : files)
        {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode root = YAML_READ.readTree(text);
            String id = joinId(binding, root);
            List<SourceRow> rows = null;
            if (id == null)
            {
                noId.add(name(file));
            }
            else
            {
                rows = src.get(id);
                if (rows == null)
                {
                    String base = stripSplitSuffix(id);
                    rows = base.equals(id) ? null : src.get(base);
                }
                if (rows == null)
                {
                    noRow.add(name(file));
                }
            }
            // Strip every matched file, even a draft/unlisted rule with no source row: an empty
            // field map removes the legacy Original_* keys (and any stale Source block) without
            // adding a new block, so no rule keeps orphaned provenance text.
            SequencedMap<String, String> effective = new LinkedHashMap<>();
            if (rows != null)
            {
                Selection selection = select(binding, rows,
                        binding.rowPolicy() == RowPolicy.OWN_PRIMARY_ROW ? declaredVersions(root)
                                : Set.of());
                effective = selection.fields();
                for (Fallback fb : selection.fallbacks())
                {
                    fallbacks.add(String.format(Locale.ROOT, "%s.%s <- sheet row %d (%s)",
                            name(file), fb.yamlKey(), fb.fromSheetRow(), fb.fromVersionCell()));
                }
                if (selection.undeclared())
                {
                    undeclared.add(name(file));
                }
            }
            List<String> input = text.lines().toList();
            List<String> output = backfill(input, effective);
            if (!output.equals(input))
            {
                changed++;
                if (!dryRun)
                {
                    // Preserve the file's original trailing-newline state so the only diff is the
                    // inserted lines (part of the corpus lacks a final newline).
                    String tail = text.endsWith("\n") ? "\n" : "";
                    Files.writeString(file, String.join("\n", output) + tail,
                            StandardCharsets.UTF_8);
                }
            }
        }
        return new Result(files.size(), changed, noId, noRow, List.copyOf(fallbacks),
                List.copyOf(undeclared));
    }


    /**
     * Picks the one row a rule's {@code Source} block transcribes, then fills any blank cell from a
     * sibling row of the <em>same family and the same-or-lower version</em> (b1).
     *
     * <p>
     * The emitted key order is always {@code binding.fields()} order, never the order values were
     * discovered in, so a block whose blank cell was filled from a sibling is byte-identical to one
     * whose chosen row carried it.
     * </p>
     *
     * <p>
     * ⚑ <strong>The (b1) fallback never crosses a family boundary and never reads a higher
     * version</strong> (owner ruling of 2026-08-21, question 2, as widened by {@code O-2} on
     * 2026-08-22): a sibling row is eligible only when its version cell names the chosen row's
     * family ({@code SENDIG}, {@code SENDIG-DART}, …) and orders same-or-lower under
     * {@link #compareVersions(SheetBinding, VersionKey, VersionKey)} — the v3.0 row may fill a
     * blank v3.1 cell, a DART row never fills a SENDIG one, and a later version never fills an
     * earlier one. Donors are tried nearest version first (a same-version sibling before the next
     * lower one), the lower sheet row breaking a tie. The first cut of {@code M3-D24} admitted only
     * an <em>equal</em> version cell, which on the shipped workbook made (b1) structurally inert
     * (no SEND sheet id has two rows sharing a version cell) and left the blanking class that lost
     * {@code CDISC-SEND-0138.Rule} open for any future rule whose primary row is the blank one.
     * Under {@code OWN_PRIMARY_ROW} the chosen row is the lowest version the rule declares, so a
     * lower donor is by construction a version the rule does <em>not</em> declare — that is the
     * point: the sheet's own earlier text is the only verbatim cell left to transcribe. Measured
     * 2026-08-22 on the shipped workbook and corpus: the widened (b1) still fires
     * <strong>0</strong> times (see {@code Fix #335}). ⚠ When it does fire,
     * {@code ShippedSendSourceBlockMatchesWorkbookTest} — which reads the primary row only and
     * requires a blank primary cell to leave the key absent — goes red and stays red until the
     * borrowed cell is recorded in its {@code EXPECTED_VARIANTS}; that is the intended second pair
     * of eyes on every borrowed cell.
     * </p>
     *
     * <p>
     * ⚠ <strong>(b1) runs under {@code OWN_PRIMARY_ROW} only.</strong> {@code M3-D24} scoped it to
     * SEND and its open question 3 ruled the other families explicitly out of scope, so
     * {@code FIRST_ROW} and {@code HIGHEST_VERSION} keep the pre-{@code M3-D24} blank-cell
     * semantics <em>by construction</em>: a blank cell omits the key, full stop. Without the gate
     * they would keep them only by measurement — 448 of 534 {@code CDISC-CG} ids and 580 of 790
     * {@code CDISC-AD} ids have several rows, and (b1) happens to fire for none of them today
     * (measured 2026-08-22, all five non-SEND bindings: 0). A future workbook revision must not be
     * able to move a corpus nobody ruled on.
     * </p>
     *
     * @param binding
     *            the sheet binding (supplies the policy and the family order)
     * @param rows
     *            every workbook row for this sheet id, in sheet order (never empty)
     * @param declared
     *            the version cells the check declares ({@code Name + " v" + Version}); empty for a
     *            binding whose policy does not read them
     * @return the chosen row, its field map and any fallbacks taken
     */
    static Selection select(SheetBinding binding, List<SourceRow> rows, Set<String> declared)
    {
        List<SourceRow> ordered = new ArrayList<>(rows);
        ordered.sort((x, y) ->
        {
            int c = compareVersions(binding, x.version(), y.version());
            return c != 0 ? c : Integer.compare(x.sheetRow(), y.sheetRow());
        });
        SourceRow chosen = switch (binding.rowPolicy())
        {
        case FIRST_ROW -> rows.getFirst();
        case HIGHEST_VERSION -> highestVersion(binding, rows);
        case OWN_PRIMARY_ROW -> ordered.stream().filter(r -> declared.contains(r.versionCell()))
                .findFirst().orElseGet(ordered::getFirst);
        };
        boolean undeclared = binding.rowPolicy() == RowPolicy.OWN_PRIMARY_ROW
                && ordered.stream().noneMatch(r -> declared.contains(r.versionCell()));
        SequencedMap<String, String> fields = new LinkedHashMap<>();
        List<Fallback> fallbacks = new ArrayList<>();
        boolean fillBlanks = binding.rowPolicy() == RowPolicy.OWN_PRIMARY_ROW;
        for (SourceField sf : binding.fields())
        {
            String own = chosen.fields().get(sf.yamlKey());
            if (own != null)
            {
                fields.put(sf.yamlKey(), own);
                continue;
            }
            if (!fillBlanks)
            {
                // (b1) is OWN_PRIMARY_ROW-only — M3-D24 scoped it to SEND (see the javadoc).
                continue;
            }
            for (SourceRow sibling : donorsNearestFirst(binding, ordered, chosen))
            {
                String value = sibling.fields().get(sf.yamlKey());
                if (value != null)
                {
                    fields.put(sf.yamlKey(), value);
                    fallbacks.add(
                            new Fallback(sf.yamlKey(), sibling.sheetRow(), sibling.versionCell()));
                    break;
                }
            }
        }
        return new Selection(chosen, fields, List.copyOf(fallbacks), undeclared);
    }


    /**
     * The (b1) donor rows for a chosen row: every other row of the same family whose version is the
     * same or lower, ordered nearest version first and then by ascending sheet row.
     *
     * @param binding
     *            the sheet binding (supplies the family order for the comparison)
     * @param rows
     *            every row for the sheet id, in any order
     * @param chosen
     *            the row whose blank cells are being filled
     * @return the eligible donors, nearest version first; never contains {@code chosen}
     */
    static List<SourceRow> donorsNearestFirst(SheetBinding binding, List<SourceRow> rows,
            SourceRow chosen)
    {
        List<SourceRow> donors = new ArrayList<>();
        for (SourceRow row : rows)
        {
            if (row.sheetRow() != chosen.sheetRow()
                    && row.version().family().equals(chosen.version().family())
                    && compareVersions(binding, row.version(), chosen.version()) <= 0)
            {
                donors.add(row);
            }
        }
        donors.sort((x, y) ->
        {
            int c = compareVersions(binding, y.version(), x.version());
            return c != 0 ? c : Integer.compare(x.sheetRow(), y.sheetRow());
        });
        return donors;
    }


    /**
     * The highest-version row, reproducing the pre-{@code M3-D24} pick exactly: a sheet-order scan
     * keeping only a <em>strictly</em> greater version, so the earlier row survives a tie.
     */
    private static SourceRow highestVersion(SheetBinding binding, List<SourceRow> rows)
    {
        SourceRow best = rows.getFirst();
        for (SourceRow row : rows)
        {
            if (compareVersions(binding, row.version(), best.version()) > 0)
            {
                best = row;
            }
        }
        return best;
    }


    /**
     * The version cells a check declares, spelled exactly as the sheet's version column spells them
     * ({@code Name: "SENDIG", Version: "3.1.1"} ⇒ {@code SENDIG v3.1.1}). Insertion-ordered so a
     * failure message reads in declaration order.
     */
    static Set<String> declaredVersions(JsonNode check)
    {
        Set<String> out = new LinkedHashSet<>();
        JsonNode standards = check.get("Standards");
        if (standards == null)
        {
            return out;
        }
        for (JsonNode authority : standards)
        {
            JsonNode stds = authority.get("Standards");
            if (stds == null)
            {
                continue;
            }
            for (JsonNode std : stds)
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
     * Strips ONE trailing uppercase letter — either directly after a digit ({@code AD0252A} ->
     * {@code AD0252}) or hyphen-joined after a digit ({@code CG0285-A} -> {@code CG0285}, the
     * coreJ-minted spelling ruled the target convention on 2026-08-12) — yielding the base id of a
     * split rule. Used only as a lookup-fallback after a verbatim {@code src.get(id)} miss: a split
     * rule carries the suffixed id verbatim but the sheet only holds the base row. Returns the id
     * unchanged when it is not a split-shaped id (so a genuinely letter-terminated id such as
     * {@code …-DRAFT} is never mis-stripped — the hyphen arm requires a digit before the hyphen).
     * {@code Fix #248} widened this copy; it must stay in step with the identical predicate in
     * {@code ShippedSourceBlockMatchesWorkbookTest} and with
     * {@code RuleEquivalenceInventory.splitBases} / {@code generate-inventory.py}'s
     * {@code build_split_bases}.
     */
    static String stripSplitSuffix(String id)
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


    /**
     * Strips any legacy provenance keys and prior {@code Source} block, then splices a fresh
     * {@code Source} block immediately before the top-level {@code Standards:} key (or at end of
     * file if absent), leaving every other line byte-for-byte untouched. An empty {@code fields}
     * map emits no block. Re-running is idempotent.
     *
     * @param input
     *            the file's lines (no terminators)
     * @param fields
     *            the ordered {@code Source} key → value map for this rule
     * @return the rewritten lines (equal to {@code input} when nothing changed)
     */
    static List<String> backfill(List<String> input, SequencedMap<String, String> fields)
    {
        List<String> lines = stripSource(new ArrayList<>(input));
        if (fields.isEmpty())
        {
            return lines;
        }
        List<String> block = sourceBlock(fields);
        int anchor = indexOfTopKey(lines, "Standards");
        if (anchor < 0)
        {
            lines.addAll(block);
        }
        else
        {
            lines.addAll(anchor, block);
        }
        return lines;
    }


    /**
     * Drops the root-level legacy keys {@code Original_Description} / {@code Original_Condition}
     * and any prior root-level {@code Source:} block, plus the indent-2 {@code Original_Message}
     * block, so the splice is idempotent and performs the legacy-key drop in the same pass.
     */
    static List<String> stripSource(List<String> lines)
    {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size())
        {
            String line = lines.get(i);
            int indent = indentOf(line);
            String trimmed = line.strip();
            boolean drop = (indent == 0 && (trimmed.startsWith("Original_Description:")
                    || trimmed.startsWith("Original_Condition:") || trimmed.startsWith("Source:")))
                    || (indent == 2 && trimmed.startsWith("Original_Message:"));
            if (drop)
            {
                i = valueEnd(lines, i);
            }
            else
            {
                out.add(line);
                i++;
            }
        }
        return out;
    }


    /**
     * Serialises {@code {"Source": {...fields...}}} with the splitter-style YAML writer. Every
     * value is a verbatim double-quoted scalar.
     */
    static List<String> sourceBlock(SequencedMap<String, String> fields)
    {
        return block("Source", fields);
    }


    /**
     * Serialises {@code {<key>: {...fields...}}} with the splitter-style YAML writer. Every value
     * is a verbatim double-quoted scalar.
     *
     * <p>
     * Generalised out of {@link #sourceBlock} so {@code SourceProposedBackfill} emits its
     * {@code Source_Proposed} block through the <em>same</em> writer, at the same fold points and
     * in the same quoting style, rather than through a second one that would drift from it
     * ({@code plans/done/PLAN-source-proposed-block.md} §6.2).
     * </p>
     */
    static List<String> block(String key, SequencedMap<String, String> fields)
    {
        ObjectNode root = YAML_WRITE.createObjectNode();
        ObjectNode source = root.putObject(key);
        for (var entry : fields.entrySet())
        {
            source.put(entry.getKey(), entry.getValue());
        }
        String yaml;
        try
        {
            yaml = YAML_WRITE.writeValueAsString(root);
        }
        catch (JsonProcessingException e)
        {
            throw new UncheckedIOException(e);
        }
        return new ArrayList<>(yaml.lines().toList());
    }


    /** Index just past the value block of the key at {@code keyIdx} (the insertion point). */
    static int valueEnd(List<String> lines, int keyIdx)
    {
        int indent = indentOf(lines.get(keyIdx));
        int j = keyIdx + 1;
        while (j < lines.size())
        {
            String line = lines.get(j);
            if (line.isBlank())
            {
                j++;
                continue;
            }
            if (indentOf(line) <= indent)
            {
                break;
            }
            j++;
        }
        return j;
    }


    /** The index of the first top-level (indent-0) line whose key is {@code key}, or -1. */
    static int indexOfTopKey(List<String> lines, String key)
    {
        String prefix = key + ":";
        for (int i = 0; i < lines.size(); i++)
        {
            String line = lines.get(i);
            if (indentOf(line) == 0 && line.startsWith(prefix))
            {
                return i;
            }
        }
        return -1;
    }


    /** The count of leading spaces on {@code line}. */
    static int indentOf(String line)
    {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ')
        {
            i++;
        }
        return i;
    }


    /**
     * The sheet Rule ID for {@code check}: the first reference under the binding's {@code Origin},
     * passed through the binding's normaliser.
     */
    static @Nullable String joinId(SheetBinding binding, JsonNode check)
    {
        JsonNode standards = check.get("Standards");
        if (standards == null)
        {
            return null;
        }
        for (JsonNode authority : standards)
        {
            JsonNode stds = authority.get("Standards");
            if (stds == null)
            {
                continue;
            }
            for (JsonNode std : stds)
            {
                JsonNode refs = std.get("References");
                if (refs == null)
                {
                    continue;
                }
                for (JsonNode ref : refs)
                {
                    if (!binding.origin().equals(text(ref.get("Origin"))))
                    {
                        continue;
                    }
                    JsonNode ri = ref.get("Rule_Identifier");
                    String id = ri == null ? null : text(ri.get("Id"));
                    if (id != null)
                    {
                        return binding.normalize(id);
                    }
                }
            }
        }
        return null;
    }

    // ---- workbook helpers -------------------------------------------------

    /**
     * Column-label → index map plus the first data row index, resolved from a marker-bearing row.
     */
    record Header(Map<String, Integer> columns, int dataStart)
    {

        int col(String label)
        {
            Integer i = columns.get(label);
            if (i == null)
            {
                throw new IllegalStateException("missing column '" + label + "'");
            }
            return i;
        }
    }

    /** Finds the header row (the first row carrying every {@code required} label) and maps it. */
    static Header header(Sheet sheet, String... required)
    {
        for (int r = 0; r <= sheet.getLastRowNum(); r++)
        {
            Row row = sheet.getRow(r);
            if (row == null)
            {
                continue;
            }
            Map<String, Integer> columns = new HashMap<>();
            for (int c = 0; c < row.getLastCellNum(); c++)
            {
                String label = cell(row, c);
                if (!label.isEmpty())
                {
                    columns.putIfAbsent(label, c);
                }
            }
            boolean all = true;
            for (String label : required)
            {
                if (!columns.containsKey(label))
                {
                    all = false;
                    break;
                }
            }
            if (all)
            {
                return new Header(columns, r + 1);
            }
        }
        throw new IllegalStateException(
                "header row not found in sheet '" + sheet.getSheetName() + "'");
    }


    /** The trimmed string value of a cell, integers rendered without a trailing {@code .0}. */
    static String cell(@Nullable Row row, int col)
    {
        if (row == null || col < 0)
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


    static @Nullable String blankToNull(String value)
    {
        return value.isEmpty() ? null : value;
    }

    private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n]+");

    /**
     * The one documented exception to verbatim transcription: every run of {@code \r} / {@code \n}
     * inside an already-trimmed cell becomes a <strong>single space</strong> (owner ruling
     * 2026-08-21, question 5). A run, not a character — a CRLF is one line break and yields one
     * space. Nothing else is touched: surrounding spaces, inner double spaces, curly quotes and
     * sheet typos all survive. ⚑ That asymmetry is deliberate and is <em>not</em> a bug to fix: a
     * break adjacent to an existing space ({@code "word \n word"}) yields a double space, because
     * only the break itself is replaced. No cell in any shipped workbook has that shape (checked
     * 2026-08-22).
     *
     * <p>
     * Applied to every binding's transcription path. Measured 2026-08-22 over all 1 928 id-bearing
     * SEND rows: <strong>only</strong> the SEND workbook has line breaks in a
     * {@code Source}-bearing column — 21 cells, <strong>11 with an inner break</strong> (rows
     * 1035–1040, 1476, 1854–1857) and 10 with a trailing one that {@link #cell}'s {@code strip()}
     * already removes. The PMDA, FDA, CG and ADaM sheets have none, so this rule is a no-op for
     * every other family.
     * </p>
     */
    static String transcribe(String cellText)
    {
        return LINE_BREAKS.matcher(cellText).replaceAll(" ");
    }

    // ---- json / version helpers ------------------------------------------


    private static @Nullable String text(@Nullable JsonNode node)
    {
        return node == null || node.isNull() ? null : node.asText();
    }


    private static String name(Path file)
    {
        return file.getFileName().toString().replace(".yaml", "");
    }


    /**
     * Orders two version cells within one binding: the binding's declared family order first (an
     * unlisted family sorts after every listed one, then alphabetically), then the numeric parts
     * element-wise with a missing part read as {@code 0} ({@code 3.1} &lt; {@code 3.1.1}).
     */
    static int compareVersions(SheetBinding binding, VersionKey a, VersionKey b)
    {
        int fa = familyRank(binding, a.family());
        int fb = familyRank(binding, b.family());
        if (fa != fb)
        {
            return Integer.compare(fa, fb);
        }
        int byName = a.family().compareTo(b.family());
        if (byName != 0)
        {
            return byName;
        }
        int size = Math.max(a.numbers().size(), b.numbers().size());
        for (int i = 0; i < size; i++)
        {
            int ai = i < a.numbers().size() ? a.numbers().get(i) : 0;
            int bi = i < b.numbers().size() ? b.numbers().get(i) : 0;
            if (ai != bi)
            {
                return Integer.compare(ai, bi);
            }
        }
        return 0;
    }


    /** The family's rank in the binding's declared order; an unlisted family ranks last. */
    private static int familyRank(SheetBinding binding, String family)
    {
        int i = binding.familyOrder().indexOf(family);
        return i < 0 ? binding.familyOrder().size() : i;
    }
}
