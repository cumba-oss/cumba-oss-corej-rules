package net.cumba.cdisc.core.expr.convert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.cumba.cdisc.core.expr.convert.OriginalTextBackfill.CgParse;
import net.cumba.cdisc.core.expr.convert.OriginalTextBackfill.Header;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.jspecify.annotations.Nullable;

/**
 * Offline developer tool — stage 2 of {@code scripts/backfill-rules.sh} (plan
 * {@code plans/done/PLAN-source-block-migration.md} §5.5 / Phase 2b). Injects and overwrites the
 * CORE-style cross-family {@code References[]} linkage inside every check's {@code Standards[]}
 * block, expressing "derived-from" provenance structurally rather than in the verbatim
 * {@code Source} block (which {@link OriginalTextBackfill} writes as own-sheet text only).
 *
 * <p>
 * Three linkage kinds are handled; the rule's own identity reference ({@code FDA Validator Rules} /
 * {@code PMDA Validation Rules}) is <strong>never</strong> touched:
 * </p>
 * <ol>
 * <li><strong>FDA {@code Publisher=CDISC} → CG.</strong> The FDA {@code Publisher ID} is parsed
 * (via {@link OriginalTextBackfill#parseCgIds}) into a {@code CG####} list; a whole
 * {@code Organization: "CDISC"} block is built that mirrors the identity
 * {@code Organization: "FDA"} block's {@code SDTMIG}-named standard entries, each carrying one
 * {@code SDTM and SDTMIG Conformance Rules} reference per CG id (rule version from the CG sheet, or
 * {@code "1"} for an id absent from the sheet). Overwrite replaces any existing {@code CDISC}
 * block.</li>
 * <li><strong>FDA {@code Publisher=FDA} → FDA-Business.</strong> The verbatim {@code FDAB###}
 * tokens in the {@code Publisher ID} (sheet-authoritative, checked for definedness against
 * {@code FDA-business-rules-v1-5.xlsx}) each become one {@code FDA Business Rules} reference added
 * into each identity-FDA standard entry's {@code References} list (a 2-origin list). Overwrite
 * removes any existing {@code FDA Business Rules} reference first.</li>
 * <li><strong>PMDA-SD/CT → FDA (one hop).</strong> If the PMDA rule's native id exists in the FDA
 * validator sheet, a new {@code Organization: "FDA"} block mirrors the identity
 * {@code Organization: "PMDA"} block's standard entries, each carrying one {@code FDA Validator
 * Rules} reference to that native id. Overwrite replaces any existing linkage {@code FDA}
 * block.</li>
 * </ol>
 *
 * <p>
 * {@code TCG …} / {@code eCTD …} / blank / unparseable {@code Publisher ID} tokens yield no
 * reference and are logged to {@code documentation/rule-review-results/CG-linkage-gaps.md}; CG ids
 * the sheet asserts but the CG corpus lacks are still injected (as-asserted) and logged.
 * </p>
 *
 * <p>
 * <strong>Conformance oracle.</strong> Before writing, every FDA rule that already carries an
 * {@code Organization: "CDISC"} block is a built-in regression oracle: its existing CG references
 * are parsed and compared (as a per-standard set) to what this tool computes from the sheets. The
 * count is reported as {@code oracle: N/M}. Residual mismatches are classified — a
 * <em>monotonic</em> mismatch (the sheet asserts more ids / higher versions than the stale corpus)
 * is a corpus-staleness difference, not a tool bug; a non-monotonic mismatch (the corpus asserts
 * something the sheet does not) is a suspect and is printed.
 * </p>
 *
 * <p>
 * <strong>ADaM Standards oracle.</strong> A second, independent oracle covers what the linkage
 * oracle above cannot see: the CDISC ADaM family identifies itself in {@code Standards[]} (it is
 * not linked <em>to</em> from anywhere), so its per-version standard entries were never checked
 * against a sheet at all. {@link #adamOracle} compares every rule that cites an {@code ADaM
 * Conformance Rules} reference against the catalogue's {@code Rule Set} column — the only
 * per-version column in the Rules Catalogue, one row per rule <em>per IG version</em> — and
 * classifies each rule as {@code MATCH} / {@code EXEMPT} / {@code MONOTONIC} / {@code SUSPECT} /
 * {@code UNRESOLVED}. The catalogue projection is committed as
 * {@code documentation/rule-review-results/adam-rule-sets.tsv} so the classification is a build
 * check ({@code StandardsLinkageBackfillTest}) and not only an offline report; this run rewrites it
 * from the workbook.
 * </p>
 *
 * <p>
 * The write is a <em>minimal-diff textual splice</em>, never a whole-file reserialize: the corpus
 * mixes YAML quoting (PMDA unquoted, some FDA unquoted), so identity and other lines are left
 * byte-for-byte and only the new linkage content is emitted (double-quoted, via
 * {@link OriginalTextBackfill#YAML_WRITE}). The rewrite is idempotent.
 * </p>
 *
 * <pre>{@code
 * cd lib/corej-cdisc-core
 * mvn -P main -q org.codehaus.mojo:exec-maven-plugin:3.6.1:java \
 *     -Dexec.classpathScope=test \
 *     -Dexec.mainClass=net.cumba.cdisc.core.expr.convert.StandardsLinkageBackfill
 * }</pre>
 *
 * <p>
 * System properties: {@code -Dcdisc.docs.dir} (default {@code /data/cdisc-docs}),
 * {@code -Dbackfill.checksRoot} (default {@code rules-src/checks}), {@code -Dbackfill.dryRun=true}
 * (validate + report, no write), {@code -Dbackfill.gapLog} (default
 * {@code documentation/rule-review-results/CG-linkage-gaps.md}), {@code -Dbackfill.adamBaseline}
 * (default {@code documentation/rule-review-results/adam-rule-sets.tsv}).
 * </p>
 */
public final class StandardsLinkageBackfill
{

    private static final ObjectMapper YAML_READ = new ObjectMapper(new YAMLFactory());

    private static final Path SRC_CHECKS = Path.of("rules-src", "checks");

    private static final Path GAP_LOG = Path.of("documentation", "rule-review-results",
            "CG-linkage-gaps.md");

    private static final String CG_ORIGIN = "SDTM and SDTMIG Conformance Rules";

    private static final String FB_ORIGIN = "FDA Business Rules";

    private static final String FDA_ORIGIN = "FDA Validator Rules";

    private static final Pattern DOT = Pattern.compile("\\.");

    private static final Pattern PUBLISHER_TOKEN = Pattern.compile("[,/;]|\\s+and\\s+");

    private static final Pattern FDAB_TOKEN = Pattern.compile("^FDAB\\d+$");

    private static final String ADAM_ORIGIN = "ADaM Conformance Rules";

    private static final String ADAM_WORKBOOK = "ADaM Conformance Rules v5.0.xlsx";

    private static final String ADAM_CATALOGUE = "Rules Catalogue";

    private static final String ADAM_RULE_ID = "Rule ID";

    private static final String ADAM_RULE_SET = "Rule Set (Generally IG Version, OCCDS v1.0, ADNCA v1.0)";

    private static final Path ADAM_BASELINE = Path.of("documentation", "rule-review-results",
            "adam-rule-sets.tsv");

    private static final Pattern COMMA = Pattern.compile(",");

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private StandardsLinkageBackfill()
    {
    }

    // ---- data model -------------------------------------------------------

    /** One injected reference: an origin, a target rule id + version, and the source version. */
    record Ref(String origin, String id, String riVersion, String refVersion)
    {
    }


    /**
     * A {@code Standards[].Standards[]} entry key: {@code Name} / optional {@code Substandard} /
     * {@code Version}.
     */
    record StdKey(String name, @Nullable String substandard, String version)
    {
    }


    /**
     * The linkage sheets: FDA publisher lookup + CG rule-version lookup + the set of {@code FDAB}
     * ids defined in the FDA business-rules sheet (for the definedness conformance check).
     */
    record Sheets(Map<String, String[]> fdaPublisher, Map<String, String> cgVersion,
            java.util.Set<String> fdabIds)
    {

        boolean isFdaId(String id)
        {
            return fdaPublisher.containsKey(id);
        }
    }


    /** Per-run tallies. */
    record Result(int fdaFiles, int pmdaFiles, int cdiscBlocks, int fbBlocks, int pmdaHops,
            int oracleMatch, int oracleTotal, int oracleSuspect, int gaps)
    {
    }

    // ---- entry point ------------------------------------------------------

    /**
     * Runs the linkage backfill.
     *
     * @param args
     *            ignored (configuration is via system properties)
     * @throws IOException
     *             if a workbook or check file cannot be read or written
     */
    public static void main(String[] args) throws IOException
    {
        Path docsDir = Path.of(System.getProperty("cdisc.docs.dir", "/data/cdisc-docs"));
        Path checksRoot = Path.of(System.getProperty("backfill.checksRoot", SRC_CHECKS.toString()));
        boolean dryRun = Boolean.getBoolean("backfill.dryRun");
        Path gapLog = Path.of(System.getProperty("backfill.gapLog", GAP_LOG.toString()));

        Sheets sheets = loadSheets(docsDir);
        List<String> gaps = new ArrayList<>();
        Result r = process(sheets, checksRoot, dryRun, gaps);

        System.out.printf(Locale.ROOT,
                "[linkage] ORACLE: %d/%d existing CDISC blocks reproduced exactly "
                        + "(%d suspect non-monotonic mismatch)%n",
                r.oracleMatch(), r.oracleTotal(), r.oracleSuspect());
        System.out.printf(Locale.ROOT,
                "[linkage] FDA files=%d  CDISC blocks written=%d  FDA-Business inserts=%d%n",
                r.fdaFiles(), r.cdiscBlocks(), r.fbBlocks());
        System.out.printf(Locale.ROOT, "[linkage] PMDA files=%d  FDA one-hop blocks written=%d%n",
                r.pmdaFiles(), r.pmdaHops());
        System.out.printf(Locale.ROOT, "[linkage] CG-linkage gaps: %d row(s)%s%n", r.gaps(),
                dryRun ? " (dry-run, not written)" : " -> " + gapLog);

        // The ADaM oracle is report-only here: this tool does not author CDISC ADaM Standards
        // blocks, so a SUSPECT is a corpus defect it cannot repair and must not abort the backfill
        // pipeline over. StandardsLinkageBackfillTest is where SUSPECT fails a build.
        Path adamBaseline = Path
                .of(System.getProperty("backfill.adamBaseline", ADAM_BASELINE.toString()));
        AdamSheet adam = loadAdamSheet(docsDir);
        reportAdamOracle(adamOracle(checksRoot, adam));
        System.out.printf(Locale.ROOT, "[linkage] ADaM catalogue projection: %d CR id(s)%s%n",
                adam.versionsById().size(),
                dryRun ? " (dry-run, not written)" : " -> " + adamBaseline);

        if (!dryRun)
        {
            writeGapLog(gapLog, gaps);
            writeAdamBaseline(adamBaseline, adam);
        }
    }

    // ---- sheet loading ----------------------------------------------------


    /**
     * Reads the FDA validator sheet ({@code native id -> [Publisher, Publisher ID]}) and the CG
     * sheet ({@code CG id -> highest-version Rule Version}).
     */
    static Sheets loadSheets(Path docsDir) throws IOException
    {
        Map<String, String[]> fdaPublisher = new HashMap<>();
        // ⛔⛔ READ-ONLY - see the note at OriginalTextBackfill's WorkbookFactory call (P0-F1).
        try (Workbook wb = WorkbookFactory
                .create(docsDir.resolve("FDA-validator-rules-v1-6.xlsx").toFile(), null, true))
        {
            Sheet sheet = wb.getSheet("FDA Validator Rules v1.6");
            Header h = OriginalTextBackfill.header(sheet, "FDA Validator Rule ID", "Publisher",
                    "Publisher ID");
            for (int rr = h.dataStart(); rr <= sheet.getLastRowNum(); rr++)
            {
                Row row = sheet.getRow(rr);
                String id = OriginalTextBackfill.cell(row, h.col("FDA Validator Rule ID"));
                if (id.isEmpty())
                {
                    continue;
                }
                fdaPublisher.putIfAbsent(id, new String[]
                {
                        OriginalTextBackfill.cell(row, h.col("Publisher")),
                        OriginalTextBackfill.cell(row, h.col("Publisher ID"))
                });
            }
        }

        Map<String, String> cgVersion = new HashMap<>();
        Map<String, int[]> best = new HashMap<>();
        try (Workbook wb = WorkbookFactory
                .create(docsDir.resolve("SDTM_and_SDTMIG_Conformance_Rules_v2.0.xlsx").toFile()))
        {
            Sheet sheet = wb.getSheet("SDTMIG Conformance Rules v2.0");
            Header h = OriginalTextBackfill.header(sheet, "Rule ID", "Rule Version",
                    "SDTMIG Version");
            for (int rr = h.dataStart(); rr <= sheet.getLastRowNum(); rr++)
            {
                Row row = sheet.getRow(rr);
                String id = OriginalTextBackfill.cell(row, h.col("Rule ID"));
                if (id.isEmpty())
                {
                    continue;
                }
                int[] version = parseVersion(
                        OriginalTextBackfill.cell(row, h.col("SDTMIG Version")));
                int[] cur = best.get(id);
                if (cur == null || compareVersions(version, cur) > 0)
                {
                    best.put(id, version);
                    cgVersion.put(id, OriginalTextBackfill.cell(row, h.col("Rule Version")));
                }
            }
        }

        java.util.Set<String> fdabIds = new java.util.HashSet<>();
        try (Workbook wb = WorkbookFactory
                .create(docsDir.resolve("FDA-business-rules-v1-5.xlsx").toFile()))
        {
            Sheet sheet = wb.getSheet("FDA Business Rules v1.5");
            Header h = OriginalTextBackfill.header(sheet, "FDA Business Rule ID");
            for (int rr = h.dataStart(); rr <= sheet.getLastRowNum(); rr++)
            {
                String id = OriginalTextBackfill.cell(sheet.getRow(rr),
                        h.col("FDA Business Rule ID"));
                if (FDAB_TOKEN.matcher(id).matches())
                {
                    fdabIds.add(id);
                }
            }
        }
        return new Sheets(fdaPublisher, cgVersion, fdabIds);
    }

    // ---- driver -----------------------------------------------------------


    /** Scans the FDA + PMDA families, runs the oracle, and (unless {@code dryRun}) writes. */
    static Result process(Sheets sheets, Path checksRoot, boolean dryRun, List<String> gaps)
        throws IOException
    {
        int fdaFiles = 0;
        int pmdaFiles = 0;
        int cdiscBlocks = 0;
        int fbBlocks = 0;
        int pmdaHops = 0;
        int oracleMatch = 0;
        int oracleTotal = 0;
        int oracleSuspect = 0;

        for (Path file : familyFiles(checksRoot, "FDA"))
        {
            fdaFiles++;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode root = YAML_READ.readTree(text);
            String native0 = OriginalTextBackfill.joinId(OriginalTextBackfill.binding("FDA"), root);
            if (native0 == null)
            {
                continue;
            }
            String[] pub = sheets.fdaPublisher().get(native0);
            if (pub == null)
            {
                continue;
            }
            // Conformance oracle — only rules that ALREADY carry a CDISC block.
            SequencedMap<StdKey, List<Ref>> existing = existingBlock(root, "CDISC");
            if (!existing.isEmpty())
            {
                oracleTotal++;
                SequencedMap<StdKey, List<Ref>> computed = computeCdisc(root, pub, sheets,
                        new ArrayList<>());
                int cmp = oracleCompare(existing, computed);
                if (cmp == 0)
                {
                    oracleMatch++;
                }
                else if (cmp < 0)
                {
                    oracleSuspect++;
                    System.out.printf(Locale.ROOT, "[linkage] SUSPECT %s native=%s pubid=%s%n",
                            name(file), native0, pub[1]);
                }
            }

            List<String> in = text.lines().toList();
            List<String> out = in;
            if ("CDISC".equals(pub[0]))
            {
                SequencedMap<StdKey, List<Ref>> computed = computeCdisc(root, pub, sheets, gaps);
                List<Ref> refs = computed.isEmpty() ? List.of()
                        : computed.values().iterator().next();
                out = replaceBlock(new ArrayList<>(in), "FDA", "CDISC",
                        new ArrayList<>(computed.keySet()), refs);
                if (!out.equals(in))
                {
                    cdiscBlocks++;
                }
            }
            else if ("FDA".equals(pub[0]))
            {
                List<String> fdab = extractFdabIds(pub[1]);
                if (fdab.isEmpty())
                {
                    if (!pub[1].isBlank())
                    {
                        gaps.add(gapRow("FDA", native0, pub[1], "no FDA-Business (FDAB) token"));
                    }
                }
                else
                {
                    for (String id : fdab)
                    {
                        if (!sheets.fdabIds().contains(id))
                        {
                            gaps.add(gapRow("FDA", native0, pub[1],
                                    "FDAB id not defined in FDA-business-rules-v1-5.xlsx: " + id));
                        }
                    }
                    out = addFbRefs(new ArrayList<>(in), fdab);
                    if (!out.equals(in))
                    {
                        fbBlocks++;
                    }
                }
            }
            writeIfChanged(file, text, in, out, dryRun);
        }

        for (Path file : familyFiles(checksRoot, "PMDA"))
        {
            pmdaFiles++;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode root = YAML_READ.readTree(text);
            String native0 = OriginalTextBackfill.joinId(OriginalTextBackfill.binding("PMDA-SDTM"),
                    root);
            if (native0 == null || !sheets.isFdaId(native0))
            {
                continue;
            }
            List<Ref> refs = List.of(new Ref(FDA_ORIGIN, native0, "1", "1.6"));
            List<StdKey> keys = identityKeys(root, "PMDA", _ -> true);
            List<String> in = text.lines().toList();
            List<String> out = replaceBlock(new ArrayList<>(in), "PMDA", "FDA", keys, refs);
            if (!out.equals(in))
            {
                pmdaHops++;
            }
            writeIfChanged(file, text, in, out, dryRun);
        }

        return new Result(fdaFiles, pmdaFiles, cdiscBlocks, fbBlocks, pmdaHops, oracleMatch,
                oracleTotal, oracleSuspect, gaps.size());
    }


    private static void writeIfChanged(Path file, String text, List<String> in, List<String> out,
            boolean dryRun)
        throws IOException
    {
        if (!dryRun && !out.equals(in))
        {
            String tail = text.endsWith("\n") ? "\n" : "";
            Files.writeString(file, String.join("\n", out) + tail, StandardCharsets.UTF_8);
        }
    }


    private static List<Path> familyFiles(Path checksRoot, String family) throws IOException
    {
        Path dir = checksRoot.resolve(family);
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir))
        {
            try (Stream<Path> stream = Files.walk(dir))
            {
                stream.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted()
                        .forEach(files::add);
            }
        }
        return files;
    }

    // ---- kind 1: FDA Publisher=CDISC -> CG --------------------------------


    /**
     * The computed {@code CDISC} block for an FDA rule: the identity {@code FDA} block's
     * {@code SDTMIG} standard entries, each mapped to one CG reference per parsed {@code Publisher
     * ID} id. Empty when the rule is not {@code Publisher=CDISC}. Unparseable tokens and dangling
     * (not-in-CG-sheet) ids are appended to {@code gaps}.
     */
    static SequencedMap<StdKey, List<Ref>> computeCdisc(JsonNode root, String[] pub, Sheets sheets,
            List<String> gaps)
    {
        SequencedMap<StdKey, List<Ref>> out = new LinkedHashMap<>();
        if (!"CDISC".equals(pub[0]))
        {
            return out;
        }
        String native0 = OriginalTextBackfill.joinId(OriginalTextBackfill.binding("FDA"), root);
        CgParse parse = OriginalTextBackfill.parseCgIds(pub[1]);
        if (!parse.unparseable().isEmpty())
        {
            gaps.add(gapRow("FDA", native0, pub[1],
                    "unparseable token(s): " + String.join(", ", parse.unparseable())));
        }
        List<Ref> refs = new ArrayList<>();
        List<String> dangling = new ArrayList<>();
        for (String cid : parse.ids())
        {
            String version = sheets.cgVersion().getOrDefault(cid, "1");
            if (!sheets.cgVersion().containsKey(cid))
            {
                dangling.add(cid);
            }
            refs.add(new Ref(CG_ORIGIN, cid, version, "2.0"));
        }
        if (!dangling.isEmpty())
        {
            gaps.add(gapRow("FDA", native0, pub[1],
                    "id(s) not in CDISC-CG corpus: " + String.join(", ", dangling)));
        }
        if (refs.isEmpty())
        {
            return out;
        }
        for (StdKey key : identityKeys(root, "FDA", k -> "SDTMIG".equals(k.name())))
        {
            out.put(key, refs);
        }
        return out;
    }

    // ---- kind 2: FDA Publisher=FDA -> FDA Business ------------------------


    /**
     * Extracts the verbatim {@code FDAB###} business-rule ids from an FDA {@code Publisher ID}
     * cell; {@code TCG} / {@code eCTD} / other tokens are ignored. Duplicates are collapsed, order
     * preserved. The sheet is authoritative — the id is emitted exactly as written (no
     * normalisation), so the reference stays checkable against
     * {@code FDA-business-rules-v1-5.xlsx}.
     */
    static List<String> extractFdabIds(String pubId)
    {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String token : PUBLISHER_TOKEN.split(pubId, -1))
        {
            String t = token.strip();
            if (FDAB_TOKEN.matcher(t).matches())
            {
                ids.add(t);
            }
        }
        return new ArrayList<>(ids);
    }

    // ---- textual splice ---------------------------------------------------


    /**
     * Replaces the {@code targetOrg} {@code Organization} block with a fresh one whose standard
     * entries are exactly {@code mirrorKeys} (parsed from the tree, so robust to the standard
     * entry's internal key order), each carrying {@code refs}. Any existing {@code targetOrg} block
     * is removed first; identity and other lines stay byte-for-byte. Returns {@code lines}
     * unchanged when there is no identity block or {@code mirrorKeys} is empty (and no stale target
     * block to drop).
     */
    static List<String> replaceBlock(List<String> lines, String identityOrg, String targetOrg,
            List<StdKey> mirrorKeys, List<Ref> refs)
    {
        int ss = OriginalTextBackfill.indexOfTopKey(lines, "Standards");
        if (ss < 0)
        {
            return lines;
        }
        int se = sectionEnd(lines, ss);
        if (orgBlock(lines, ss, se, identityOrg) == null)
        {
            return lines;
        }
        // Drop any existing target block.
        int[] tgt = orgBlock(lines, ss, se, targetOrg);
        if (tgt != null)
        {
            lines.subList(tgt[0], tgt[1]).clear();
        }
        if (mirrorKeys.isEmpty() || refs.isEmpty())
        {
            return lines;
        }
        // Re-locate the identity block after the removal and append the fresh target block.
        int ss2 = OriginalTextBackfill.indexOfTopKey(lines, "Standards");
        int se2 = sectionEnd(lines, ss2);
        int[] idb2 = orgBlock(lines, ss2, se2, identityOrg);
        List<String> block = serializeOrg(targetOrg, mirrorKeys, refs);
        lines.addAll(idb2[1], block);
        return lines;
    }


    /**
     * Adds one {@code FDA Business Rules} reference per {@code fbIds} into each standard entry of
     * the identity {@code FDA} block, after removing any existing {@code FDA Business Rules}
     * reference. Only the new reference lines are emitted; identity references stay byte-for-byte.
     */
    static List<String> addFbRefs(List<String> lines, List<String> fbIds)
    {
        int ss = OriginalTextBackfill.indexOfTopKey(lines, "Standards");
        if (ss < 0)
        {
            return lines;
        }
        int se = sectionEnd(lines, ss);
        int[] idb = orgBlock(lines, ss, se, "FDA");
        if (idb == null)
        {
            return lines;
        }
        List<Ref> fresh = new ArrayList<>();
        for (String id : fbIds)
        {
            fresh.add(new Ref(FB_ORIGIN, id, "1", "1.5"));
        }
        List<String> freshLines = serializeRefs(fresh, 4);
        // Rewrite each entry's reference list bottom-up so earlier indices stay valid.
        List<StdEntry> entries = stdEntries(lines, idb[0], idb[1]);
        for (int e = entries.size() - 1; e >= 0; e--)
        {
            StdEntry en = entries.get(e);
            if (en.refsLine() < 0)
            {
                continue;
            }
            // Only the contiguous run of reference items right after `References:` is the list;
            // a trailing standard-entry key (some unquoted files order Version AFTER References)
            // ends the run and must be preserved.
            int regionStart = en.refsLine() + 1;
            int regionEnd = regionStart;
            List<int[]> run = new ArrayList<>();
            for (int[] item : refItemRanges(lines, regionStart, en.end()))
            {
                if (item[0] != regionEnd)
                {
                    break;
                }
                run.add(item);
                regionEnd = item[1];
            }
            List<String> kept = new ArrayList<>();
            for (int[] item : run)
            {
                if (!FB_ORIGIN.equals(refOrigin(lines.get(item[0]))))
                {
                    kept.addAll(lines.subList(item[0], item[1]));
                }
            }
            lines.subList(regionStart, regionEnd).clear();
            List<String> ins = new ArrayList<>(kept);
            ins.addAll(freshLines);
            lines.addAll(regionStart, ins);
        }
        return lines;
    }

    // ---- Standards section navigation ------------------------------------

    /** One standard entry within an {@code Organization} block. */
    record StdEntry(StdKey key, int start, int end, int refsLine)
    {
    }

    private static int sectionEnd(List<String> lines, int start)
    {
        for (int i = start + 1; i < lines.size(); i++)
        {
            String l = lines.get(i);
            if (l.isBlank())
            {
                continue;
            }
            if (OriginalTextBackfill.indentOf(l) == 0 && !l.startsWith("- "))
            {
                return i;
            }
        }
        return lines.size();
    }


    /** The {@code [start, end)} of the {@code Organization: org} block, or {@code null}. */
    private static int @Nullable [] orgBlock(List<String> lines, int secStart, int secEnd,
            String org)
    {
        int start = -1;
        for (int i = secStart + 1; i < secEnd; i++)
        {
            String l = lines.get(i);
            if (OriginalTextBackfill.indentOf(l) == 0 && l.startsWith("- "))
            {
                String o = orgName(l);
                if (start < 0)
                {
                    if (org.equals(o))
                    {
                        start = i;
                    }
                }
                else
                {
                    return new int[]
                    {
                            start, i
                    };
                }
            }
        }
        return start < 0 ? null : new int[]
        {
                start, secEnd
        };
    }


    private static @Nullable String orgName(String line)
    {
        String t = line.strip();
        String key = "- Organization:";
        return t.startsWith(key) ? unquote(t.substring(key.length()).strip()) : null;
    }


    /** Parses the standard entries of an {@code Organization} block {@code [bs, be)}. */
    private static List<StdEntry> stdEntries(List<String> lines, int bs, int be)
    {
        List<StdEntry> out = new ArrayList<>();
        int sub = -1;
        for (int i = bs + 1; i < be; i++)
        {
            if (OriginalTextBackfill.indentOf(lines.get(i)) == 2
                    && lines.get(i).strip().equals("Standards:"))
            {
                sub = i;
                break;
            }
        }
        if (sub < 0)
        {
            return out;
        }
        int i = sub + 1;
        while (i < be)
        {
            String l = lines.get(i);
            if (OriginalTextBackfill.indentOf(l) == 2 && l.strip().startsWith("- "))
            {
                int es = i;
                int ee = i + 1;
                while (ee < be)
                {
                    String m = lines.get(ee);
                    int mi = OriginalTextBackfill.indentOf(m);
                    if (mi == 0 || (mi == 2 && m.strip().startsWith("- ")))
                    {
                        break;
                    }
                    ee++;
                }
                out.add(parseEntry(lines, es, ee));
                i = ee;
            }
            else
            {
                i++;
            }
        }
        return out;
    }


    private static StdEntry parseEntry(List<String> lines, int es, int ee)
    {
        String name = null;
        String substd = null;
        String version = null;
        int refs = -1;
        for (int i = es; i < ee; i++)
        {
            String t = lines.get(i).strip();
            String body = i == es && t.startsWith("- ") ? t.substring(2).strip() : t;
            if (body.equals("References:"))
            {
                refs = i;
                break;
            }
            if (body.startsWith("Name:"))
            {
                name = unquote(body.substring("Name:".length()).strip());
            }
            else if (body.startsWith("Substandard:"))
            {
                substd = unquote(body.substring("Substandard:".length()).strip());
            }
            else if (body.startsWith("Version:"))
            {
                version = unquote(body.substring("Version:".length()).strip());
            }
        }
        return new StdEntry(new StdKey(name, substd, version), es, ee, refs);
    }


    /** The {@code [start, end)} of each reference list item in {@code [from, end)}. */
    private static List<int[]> refItemRanges(List<String> lines, int from, int end)
    {
        List<int[]> out = new ArrayList<>();
        int i = from;
        while (i < end)
        {
            String l = lines.get(i);
            if (OriginalTextBackfill.indentOf(l) == 4 && l.strip().startsWith("- "))
            {
                int s = i;
                int j = i + 1;
                while (j < end)
                {
                    String m = lines.get(j);
                    if (m.isBlank())
                    {
                        j++;
                        continue;
                    }
                    // A reference item's continuation lines are indented deeper than 4; any line
                    // at indent <= 4 (the next item, or a sibling standard-entry key) ends it.
                    if (OriginalTextBackfill.indentOf(m) <= 4)
                    {
                        break;
                    }
                    j++;
                }
                out.add(new int[]
                {
                        s, j
                });
                i = j;
            }
            else
            {
                i++;
            }
        }
        return out;
    }


    private static @Nullable String refOrigin(String line)
    {
        String t = line.strip();
        String key = "- Origin:";
        return t.startsWith(key) ? unquote(t.substring(key.length()).strip()) : null;
    }


    private static String unquote(String s)
    {
        if (s.length() >= 2)
        {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\''))
            {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    // ---- identity-key / existing-ref extraction from JSON -----------------


    /** The identity {@code org} block's standard-entry keys (filtered) from the parsed tree. */
    private static List<StdKey> identityKeys(JsonNode root, String org, Predicate<StdKey> keep)
    {
        List<StdKey> out = new ArrayList<>();
        JsonNode block = orgNode(root, org);
        if (block == null)
        {
            return out;
        }
        for (JsonNode std : block.path("Standards"))
        {
            StdKey key = new StdKey(asText(std.get("Name")), asText(std.get("Substandard")),
                    asText(std.get("Version")));
            if (keep.test(key))
            {
                out.add(key);
            }
        }
        return out;
    }


    /**
     * The existing references of the {@code org} block, keyed by standard entry (for the oracle).
     */
    static SequencedMap<StdKey, List<Ref>> existingBlock(JsonNode root, String org)
    {
        SequencedMap<StdKey, List<Ref>> out = new LinkedHashMap<>();
        JsonNode block = orgNode(root, org);
        if (block == null)
        {
            return out;
        }
        for (JsonNode std : block.path("Standards"))
        {
            StdKey key = new StdKey(asText(std.get("Name")), asText(std.get("Substandard")),
                    asText(std.get("Version")));
            List<Ref> refs = new ArrayList<>();
            for (JsonNode ref : std.path("References"))
            {
                JsonNode ri = ref.path("Rule_Identifier");
                refs.add(new Ref(asText(ref.get("Origin")), asText(ri.get("Id")),
                        asText(ri.get("Version")), asText(ref.get("Version"))));
            }
            out.put(key, refs);
        }
        return out;
    }


    private static @Nullable JsonNode orgNode(JsonNode root, String org)
    {
        for (JsonNode authority : root.path("Standards"))
        {
            if (org.equals(asText(authority.get("Organization"))))
            {
                return authority;
            }
        }
        return null;
    }

    // ---- conformance oracle ----------------------------------------------


    /**
     * Compares an existing {@code CDISC} block to the computed one as a per-standard set. Returns
     * {@code 0} for an exact match, {@code +1} for a monotonic mismatch (the computed sheet set is
     * a strict superset — stale corpus), or {@code -1} for a suspect mismatch (the corpus asserts a
     * standard, id, or higher version the sheet does not — a possible tool bug).
     */
    static int oracleCompare(SequencedMap<StdKey, List<Ref>> existing,
            SequencedMap<StdKey, List<Ref>> computed)
    {
        boolean exact = existing.keySet().equals(computed.keySet());
        for (var entry : existing.entrySet())
        {
            List<Ref> want = computed.get(entry.getKey());
            if (want == null)
            {
                return -1; // corpus has a standard entry the sheet mirror does not
            }
            Map<String, String> have = versionById(entry.getValue());
            Map<String, String> sheet = versionById(want);
            for (var idv : have.entrySet())
            {
                String sv = sheet.get(idv.getKey());
                if (sv == null)
                {
                    return -1; // corpus id not in sheet
                }
                if (!sv.equals(idv.getValue()))
                {
                    exact = false;
                    if (numeric(sv) < numeric(idv.getValue()))
                    {
                        return -1; // sheet version older than corpus -> suspect
                    }
                }
            }
            if (!have.keySet().equals(sheet.keySet()))
            {
                exact = false;
            }
        }
        return exact ? 0 : 1;
    }


    private static Map<String, String> versionById(List<Ref> refs)
    {
        Map<String, String> m = new HashMap<>();
        for (Ref r : refs)
        {
            m.put(r.id(), r.riVersion());
        }
        return m;
    }


    private static long numeric(String v)
    {
        try
        {
            return Long.parseLong(v.strip());
        }
        catch (NumberFormatException _)
        {
            return -1;
        }
    }

    // ---- ADaM Standards oracle -------------------------------------------

    /**
     * The ADaM Conformance Rules catalogue projected onto its only per-version column:
     * {@code CR id -> the set of ADaMIG versions the Rule Set rows assert}. The catalogue carries
     * one row per rule <em>per IG version</em>, so the projection is a fold of those rows.
     *
     * <p>
     * ⚠ {@code Rule ID Version} is <strong>not</strong> a competitor for this role: it is the
     * rule's own revision counter and it is already carried by
     * {@code References[].Rule_Identifier.Version}, a different axis entirely.
     * </p>
     */
    record AdamSheet(Map<String, Set<String>> versionsById)
    {

        Set<String> versions(String crId)
        {
            return versionsById.get(crId);
        }
    }


    /** How one rule's ADaM {@code Standards} versions relate to the catalogue's. */
    enum AdamVerdict
    {
        /** The corpus asserts exactly the versions the catalogue does. */
        MATCH,
        /**
         * The corpus omits a version the catalogue asserts, and the omission is authored: the rule
         * is a split side rule ({@code <BASE><L>}) whose parent carries the catalogue's full range,
         * so the side rule's narrower range is the point of the split, not drift.
         */
        EXEMPT,
        /**
         * The catalogue asserts a version the corpus omits — corpus staleness. Reported, never
         * failed: the sheet legitimately moves ahead of the corpus.
         */
        MONOTONIC,
        /**
         * ⚠ The corpus asserts a version the catalogue does not. This is the failing class: it
         * manufactures coverage the authority never granted, and (as {@code CR714}/{@code CR715}
         * did) can duplicate a sibling rule that legitimately owns that version.
         */
        SUSPECT,
        /** The rule cites no single resolvable {@code CR} id, so nothing can be compared. */
        UNRESOLVED
    }


    /** One rule's ADaM verdict, with both sides of the comparison kept for the report. */
    record AdamFinding(String ruleId, String crId, Set<String> corpus, Set<String> sheet,
            AdamVerdict verdict, @Nullable String splitBase)
    {

        String describe()
        {
            return String.format(Locale.ROOT, "%s %s corpus=%s sheet=%s%s", ruleId, crId, corpus,
                    sheet, splitBase == null ? "" : " (side rule of " + splitBase + ")");
        }
    }


    /** Every rule that cites an {@code ADaM Conformance Rules} reference, classified. */
    record AdamOracle(List<AdamFinding> findings)
    {

        List<AdamFinding> of(AdamVerdict verdict)
        {
            return findings.stream().filter(f -> f.verdict() == verdict).toList();
        }


        int count(AdamVerdict verdict)
        {
            return of(verdict).size();
        }


        int total()
        {
            return findings.size();
        }
    }

    /**
     * Reads the ADaM catalogue's {@code Rule ID} x {@code Rule Set} projection from the workbook.
     *
     * @param docsDir
     *            the directory holding {@code ADaM Conformance Rules v5.0.xlsx}
     * @return the projection
     * @throws IOException
     *             if the workbook cannot be read
     */
    static AdamSheet loadAdamSheet(Path docsDir) throws IOException
    {
        Map<String, Set<String>> byId = new HashMap<>();
        // ⛔⛔ READ-ONLY - see the note at OriginalTextBackfill's WorkbookFactory call (P0-F1).
        try (Workbook wb = WorkbookFactory.create(docsDir.resolve(ADAM_WORKBOOK).toFile(), null,
                true))
        {
            Sheet sheet = wb.getSheet(ADAM_CATALOGUE);
            Header h = OriginalTextBackfill.header(sheet, ADAM_RULE_ID, ADAM_RULE_SET);
            for (int rr = h.dataStart(); rr <= sheet.getLastRowNum(); rr++)
            {
                Row row = sheet.getRow(rr);
                String id = OriginalTextBackfill.cell(row, h.col(ADAM_RULE_ID));
                String ruleSet = OriginalTextBackfill.cell(row, h.col(ADAM_RULE_SET));
                if (id.isEmpty() || ruleSet.isEmpty())
                {
                    continue;
                }
                byId.computeIfAbsent("CR" + id, _ -> new TreeSet<>()).add(igVersion(ruleSet));
            }
        }
        return new AdamSheet(byId);
    }


    /**
     * The catalogue spells ADaMIG 1.0 as the bare integer {@code 1}, while a {@code Standards}
     * entry spells it {@code 1.0}; {@code 1.1} / {@code 1.2} / {@code 1.3} already agree.
     */
    static String igVersion(String ruleSet)
    {
        return "1".equals(ruleSet) ? "1.0" : ruleSet;
    }


    /** Reads the committed catalogue projection ({@code CR id TAB comma-separated versions}). */
    static AdamSheet readAdamBaseline(Path tsv) throws IOException
    {
        Map<String, Set<String>> byId = new HashMap<>();
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8))
        {
            if (line.isBlank() || line.charAt(0) == '#')
            {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0)
            {
                throw new IOException("malformed row in " + tsv + ": " + line);
            }
            Set<String> versions = new TreeSet<>();
            for (String v : COMMA.split(line.substring(tab + 1).strip(), -1))
            {
                versions.add(v.strip());
            }
            byId.put(line.substring(0, tab).strip(), versions);
        }
        return new AdamSheet(byId);
    }


    /** Rewrites the committed catalogue projection, ordered by numeric {@code CR} id. */
    static void writeAdamBaseline(Path tsv, AdamSheet sheet) throws IOException
    {
        Path parent = tsv.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        StringBuilder sb = new StringBuilder("""
                # ADaM Conformance Rules v5.0, Rules Catalogue: `Rule ID` x `Rule Set` projection.
                # One row per CR id; the value is every ADaMIG version the catalogue's rows assert
                # for it, with the catalogue's bare `1` normalised to the corpus spelling `1.0`.
                # Generated by StandardsLinkageBackfill from the workbook — do not hand-edit.
                """);
        List<String> ids = new ArrayList<>(sheet.versionsById().keySet());
        ids.sort(java.util.Comparator.comparingLong(StandardsLinkageBackfill::crOrder)
                .thenComparing(java.util.Comparator.naturalOrder()));
        for (String id : ids)
        {
            sb.append(id).append('\t').append(String.join(",", sheet.versionsById().get(id)))
                    .append('\n');
        }
        Files.writeString(tsv, sb.toString(), StandardCharsets.UTF_8);
    }


    private static long crOrder(String crId)
    {
        var m = DIGITS.matcher(crId);
        return m.find() ? Long.parseLong(m.group()) : Long.MAX_VALUE;
    }


    /**
     * Classifies every rule under {@code checksRoot} that cites an {@code ADaM Conformance Rules}
     * reference against the catalogue projection.
     *
     * <p>
     * The population is content-derived, not family-derived: any rule whose {@code Standards[]}
     * carries such a reference is compared, so a future cross-family citation is not silently
     * skipped. The split-group exemption reuses {@link RuleEquivalenceInventory#splitBases}, which
     * is family-local and id-literal by design.
     * </p>
     *
     * @param checksRoot
     *            the {@code rules-src/checks} root
     * @param sheet
     *            the catalogue projection
     * @return one finding per compared rule, in corpus order
     * @throws IOException
     *             if a check file cannot be read
     */
    static AdamOracle adamOracle(Path checksRoot, AdamSheet sheet) throws IOException
    {
        List<RuleEquivalenceInventory.Rule> rules = RuleEquivalenceInventory.load(checksRoot);
        Map<String, String> splitBases = RuleEquivalenceInventory.splitBases(rules);
        SequencedMap<String, Set<String>> corpusVersions = new LinkedHashMap<>();
        SequencedMap<String, Set<String>> citedIds = new LinkedHashMap<>();
        for (RuleEquivalenceInventory.Rule rule : rules)
        {
            Set<String> versions = new TreeSet<>();
            Set<String> ids = new TreeSet<>();
            collectAdamCitations(rule.node(), versions, ids);
            if (!ids.isEmpty())
            {
                corpusVersions.put(rule.id(), versions);
                citedIds.put(rule.id(), ids);
            }
        }

        List<AdamFinding> findings = new ArrayList<>();
        for (var entry : corpusVersions.entrySet())
        {
            String ruleId = entry.getKey();
            Set<String> ours = entry.getValue();
            Set<String> ids = citedIds.get(ruleId);
            String crId = String.join("+", ids);
            Set<String> theirs = ids.size() == 1 ? sheet.versions(ids.iterator().next()) : null;
            if (theirs == null)
            {
                findings.add(new AdamFinding(ruleId, crId, ours, Set.of(), AdamVerdict.UNRESOLVED,
                        null));
                continue;
            }
            String base = splitBases.get(ruleId);
            AdamVerdict verdict;
            if (ours.equals(theirs))
            {
                verdict = AdamVerdict.MATCH;
                base = null;
            }
            else if (!theirs.containsAll(ours))
            {
                verdict = AdamVerdict.SUSPECT;
                base = null;
            }
            else if (base != null && theirs.equals(corpusVersions.get(base)))
            {
                verdict = AdamVerdict.EXEMPT;
            }
            else
            {
                verdict = AdamVerdict.MONOTONIC;
                base = null;
            }
            findings.add(new AdamFinding(ruleId, crId, ours, theirs, verdict, base));
        }
        return new AdamOracle(findings);
    }


    /**
     * Collects the {@code Standards[].Version}s of every standard entry carrying an {@code ADaM
     * Conformance Rules} reference, and the {@code CR} ids those references cite.
     */
    private static void collectAdamCitations(JsonNode root, Set<String> versions, Set<String> ids)
    {
        for (JsonNode authority : root.path("Standards"))
        {
            for (JsonNode std : authority.path("Standards"))
            {
                for (JsonNode ref : std.path("References"))
                {
                    if (!ADAM_ORIGIN.equals(asText(ref.get("Origin"))))
                    {
                        continue;
                    }
                    String version = asText(std.get("Version"));
                    if (version != null)
                    {
                        versions.add(version);
                    }
                    String id = asText(ref.path("Rule_Identifier").get("Id"));
                    if (id != null)
                    {
                        ids.add(id);
                    }
                }
            }
        }
    }


    /** Prints the ADaM oracle's tally and every finding that is not an exact match. */
    private static void reportAdamOracle(AdamOracle oracle)
    {
        System.out.printf(Locale.ROOT,
                "[linkage] ADaM ORACLE: %d/%d rules match the catalogue Rule Set column "
                        + "(%d suspect, %d stale, %d exempt side rule(s), %d unresolved)%n",
                oracle.count(AdamVerdict.MATCH), oracle.total(), oracle.count(AdamVerdict.SUSPECT),
                oracle.count(AdamVerdict.MONOTONIC), oracle.count(AdamVerdict.EXEMPT),
                oracle.count(AdamVerdict.UNRESOLVED));
        for (AdamFinding f : oracle.findings())
        {
            if (f.verdict() != AdamVerdict.MATCH)
            {
                System.out.printf(Locale.ROOT, "[linkage] ADaM %-10s %s%n", f.verdict(),
                        f.describe());
            }
        }
    }

    // ---- YAML serialization (new content only) ---------------------------


    /** Serialises one {@code Organization} block (indent 0) with a reference list per entry. */
    private static List<String> serializeOrg(String org, List<StdKey> keys, List<Ref> refs)
    {
        ArrayNode top = OriginalTextBackfill.YAML_WRITE.createArrayNode();
        ObjectNode block = top.addObject();
        block.put("Organization", org);
        ArrayNode stds = block.putArray("Standards");
        for (StdKey key : keys)
        {
            ObjectNode s = stds.addObject();
            s.put("Name", key.name());
            if (key.substandard() != null)
            {
                s.put("Substandard", key.substandard());
            }
            s.put("Version", key.version());
            addRefs(s.putArray("References"), refs);
        }
        return indent(top, 0);
    }


    /** Serialises a bare reference list (block sequence) at {@code indent} spaces. */
    private static List<String> serializeRefs(List<Ref> refs, int indent)
    {
        ArrayNode arr = OriginalTextBackfill.YAML_WRITE.createArrayNode();
        addRefs(arr, refs);
        return indent(arr, indent);
    }


    private static void addRefs(ArrayNode arr, List<Ref> refs)
    {
        for (Ref r : refs)
        {
            ObjectNode ro = arr.addObject();
            ro.put("Origin", r.origin());
            ObjectNode ri = ro.putObject("Rule_Identifier");
            ri.put("Id", r.id());
            ri.put("Version", r.riVersion());
            ro.put("Version", r.refVersion());
        }
    }


    private static List<String> indent(JsonNode node, int spaces)
    {
        String yaml;
        try
        {
            yaml = OriginalTextBackfill.YAML_WRITE.writeValueAsString(node);
        }
        catch (JsonProcessingException e)
        {
            throw new UncheckedIOException(e);
        }
        String pad = " ".repeat(spaces);
        List<String> out = new ArrayList<>();
        for (String line : yaml.lines().toList())
        {
            out.add(line.isEmpty() ? line : pad + line);
        }
        return out;
    }

    // ---- gap log ----------------------------------------------------------


    private static String gapRow(String binding, @Nullable String ruleId, String pubId,
            String problem)
    {
        return String.format(Locale.ROOT, "| %s | %s | `%s` | %s |", binding, ruleId, pubId,
                problem);
    }


    private static void writeGapLog(Path gapLog, List<String> gaps) throws IOException
    {
        Path parent = gapLog.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Cross-family linkage gaps\n\n");
        sb.append(
                "Rows where an FDA `Publisher ID` cited a rule the corpus does not carry, or a\n");
        sb.append("token that could not be parsed. Generated by `StandardsLinkageBackfill`;\n");
        sb.append(
                "surfaced for escalation rather than silently dropped. Dangling CG ids are still\n");
        sb.append("injected as-asserted.\n\n");
        sb.append("| Binding | Rule ID | Publisher ID | Problem |\n");
        sb.append("|---|---|---|---|\n");
        for (String row : gaps)
        {
            sb.append(row).append('\n');
        }
        Files.writeString(gapLog, sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- misc helpers -----------------------------------------------------


    private static @Nullable String asText(@Nullable JsonNode node)
    {
        return node == null || node.isNull() ? null : node.asText();
    }


    private static String name(Path file)
    {
        return file.getFileName().toString().replace(".yaml", "");
    }


    private static int[] parseVersion(String version)
    {
        String[] parts = DOT.split(version.strip(), -1);
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++)
        {
            try
            {
                nums[i] = Integer.parseInt(parts[i].strip());
            }
            catch (NumberFormatException _)
            {
                nums[i] = 0;
            }
        }
        return nums;
    }


    private static int compareVersions(int[] a, int[] b)
    {
        for (int i = 0; i < Math.max(a.length, b.length); i++)
        {
            int ai = i < a.length ? a[i] : 0;
            int bi = i < b.length ? b[i] : 0;
            if (ai != bi)
            {
                return Integer.compare(ai, bi);
            }
        }
        return 0;
    }
}
