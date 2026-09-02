package net.cumba.cdisc.core.expr.convert;

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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.cumba.cdisc.core.expr.convert.OriginalTextBackfill.Header;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.jspecify.annotations.Nullable;

/**
 * Package-membership oracle — the {@code M2-D17} instrument (owner ruling 2026-08-20/21): every
 * rule's {@code Standards} block is compared against the version columns of the workbook its
 * authority reference names, family by family, and classified {@code MATCH} / {@code EXEMPT} /
 * {@code SUSPECT} / {@code UNRESOLVED}. It generalises {@link StandardsLinkageBackfill#adamOracle}
 * (one binding, {@code CDISC-AD}) to the six comparable bindings.
 *
 * <p>
 * <strong>What is compared.</strong> Package membership is a faithful projection of the
 * {@code Standards} block: {@link RuleStorageAssembler} puts a rule into package
 * {@code (family, Name, Version)} for every distinct {@code (Name, Version)} its block names, over
 * <em>all</em> {@code Organization} entries ({@code declared ≡ shipped}, measured zero divergence
 * corpus-wide). So the corpus side of the comparison is the set of distinct {@code (Name,
 * Version)} keys across the whole block, and the sheet side is the set of {@code (Name, Version)}
 * keys the rule's own row(s) mark.
 * </p>
 *
 * <p>
 * <strong>Bindings.</strong> A rule's <em>authority</em> reference is the {@code References[]}
 * entry whose {@code Origin} is the family's own sheet — linkage references (the CG ids an FDA rule
 * also cites, the FDA one-hop block a PMDA rule carries) are provenance, not membership, and are
 * ignored here:
 * </p>
 * <ul>
 * <li>{@code FDA} — {@code FDA Validator Rules} → {@code FDA-validator-rules-v1-6.xlsx}, columns
 * {@code SDTMIG 3.1.2 … 3.3}, {@code SENDIG 3.0 … 3.1.1}, {@code SENDIG-AR 1.0},
 * {@code SENDIG-DART 1.1} marked {@code X};</li>
 * <li>{@code PMDA-AD} / {@code PMDA-SDTM} — {@code PMDA Validation Rules} →
 * {@code PMDA-ValidationRules-v6-0.xlsx}, {@code ADaM Rules} columns {@code 1.0 … 1.3} for
 * {@code AD####} ids and {@code SDTM Rules} columns {@code 3.1.2 … 3.4} for the rest;</li>
 * <li>{@code CDISC-SEND} — {@code SEND Conformance Rules} → one row per (rule, version), column
 * {@code SENDIG Version} ({@code SENDIG v3.1.1}, {@code SENDIG-GENETOX v1.0}, …);</li>
 * <li>{@code CDISC-CG} — {@code SDTM and SDTMIG Conformance Rules} → one row per (rule, version),
 * column {@code SDTMIG Version};</li>
 * <li>{@code CDISC-AD} — {@code ADaM Conformance Rules} → {@code Rule Set} column, the bare
 * {@code 1} spelled {@code 1.0} as in {@link StandardsLinkageBackfill#igVersion}.</li>
 * </ul>
 * <p>
 * A rule citing several authority ids (e.g. {@code CDISC-SEND-0268} citing {@code SEND268} for 3.0
 * and {@code SEND268.1} for the later versions) is compared against the <em>union</em> of their
 * rows. {@code CORE} rules are comparable where they cite a CDISC conformance id; the 82 that cite
 * only {@code FDA Business Rules} (a workbook with no version columns) and the {@code TIG}-only
 * rules are <em>unmeasurable, not measured clean</em>, and are reported as {@code EXEMPT} with that
 * reason code.
 * </p>
 *
 * <p>
 * <strong>Sheet-silent axes are retained, never judged.</strong> The comparison is confined to the
 * <em>universe</em> — the columns the cited workbook(s) actually carry. A declared key outside the
 * universe ({@code FDA} rules' {@code SDTMIG 3.4}: the v1.6 workbook stops at 3.3; {@code CORE}'s
 * {@code TIG 1.0}: no workbook exists; {@code CORE}/{@code DRAFT} {@code SENDIG-AR 1.0}; the four
 * {@code PMDA-SD} rules declaring {@code ADaMIG}) is listed as {@code retained} and neither counts
 * for nor against the verdict (owner scope amendment, 2026-08-20).
 * </p>
 *
 * <p>
 * <strong>The {@code -A} sibling partition convention.</strong> Several rules in one family may
 * cite the same sheet row and split its versions between them — {@code FDA-SD0095} carries the
 * row's nine versions and {@code FDA-SD0095-A} only {@code SDTMIG 3.4}; {@code CORE-000550}
 * ({@code 3.2}, {@code 3.4}) and {@code CORE-001079} ({@code 3.3}) partition {@code CG0013}/
 * {@code CG0351}; {@code CDISC-AD0039-A} omits {@code 1.0} while its parent carries the full range.
 * A sibling's declared set is its own: each rule is compared against its OWN block, and the group
 * is accepted when every member stays inside the row ({@code own ⊆ sheet}) and covers whatever its
 * siblings leave uncovered ({@code sheet − ⋃ siblings' own ⊆ own}). A member equal to the row is
 * {@code MATCH}; a strict subset that satisfies the cover rule is {@code EXEMPT:SPLIT_SIBLING}; a
 * member declaring a version the row does not mark is {@code SUSPECT} regardless of its siblings.
 * Siblings are found by the shared {@code (binding,
 * sheet id)} authority key within the family — never by id suffix, because PMDA's {@code AD0280B}
 * is a distinct sheet row, not a split of {@code AD0280}. ⚠ This is deliberately NOT the
 * {@link OriginalTextBackfill} parent-row join: a generator writing the row's full set onto every
 * rule citing it would give both siblings everything and double every finding.
 * </p>
 *
 * <p>
 * <strong>Verdicts.</strong> No backward version inheritance (owner, 2026-08-21): a rule is a
 * member of exactly the versions its row names, so an omission is as wrong as an addition —
 * {@code PMDA-AD0312} marked for four ADaMIG versions and shipped in two never ran against a 1.2 or
 * 1.3 submission. Both directions are therefore {@code SUSPECT}; the finding records the
 * {@code extra} and {@code missing} keys so the corpus fix can be derived from it.
 * </p>
 *
 * <p>
 * The workbook projection is committed as
 * {@code documentation/rule-review-results/standards-version-columns.tsv} so the classification is
 * a build check ({@code StandardsMembershipOracleTest}) and not only an offline report; this tool's
 * {@link #main} rewrites it from the workbooks.
 * </p>
 *
 * <pre>{@code
 * cd lib/corej-cdisc-rules
 * mvn -P main -q org.codehaus.mojo:exec-maven-plugin:3.6.1:java \
 *     -Dexec.classpathScope=test \
 *     -Dexec.mainClass=net.cumba.cdisc.core.expr.convert.StandardsMembershipOracle
 * }</pre>
 *
 * <p>
 * System properties: {@code -Dcdisc.docs.dir} (default {@code /data/cdisc-docs}),
 * {@code -Doracle.checksRoot} (default {@code rules-src/checks}), {@code -Doracle.baseline}
 * (default the committed TSV), {@code -Doracle.dryRun=true} (report only, no TSV write).
 * </p>
 */
public final class StandardsMembershipOracle
{

    private static final ObjectMapper YAML_READ = new ObjectMapper(new YAMLFactory());

    private static final Path SRC_CHECKS = Path.of("rules-src", "checks");

    static final Path BASELINE = Path.of("documentation", "rule-review-results",
            "standards-version-columns.tsv");

    /** The TSV row id that carries a binding's column universe. */
    static final String UNIVERSE_ID = "*";

    static final String FDA_ORIGIN = "FDA Validator Rules";

    static final String PMDA_ORIGIN = "PMDA Validation Rules";

    static final String SEND_ORIGIN = "SEND Conformance Rules";

    static final String CG_ORIGIN = "SDTM and SDTMIG Conformance Rules";

    static final String ADAM_ORIGIN = "ADaM Conformance Rules";

    static final String FDA_BUSINESS_ORIGIN = "FDA Business Rules";

    static final String TIG_ORIGIN = "TIG Conformance Rules";

    private static final Pattern COMMA = Pattern.compile(",");

    private static final Pattern SPACE = Pattern.compile(" ");

    private static final Pattern TAB = Pattern.compile("\t");

    /** {@code NAME<newline>VERSION} — the FDA workbook's version-column labels. */
    private static final Pattern FDA_COLUMN = Pattern.compile("^([A-Z][A-Z-]*)\\s*\n\\s*(\\S+)$");

    /** A bare version label ({@code 3.1.2}, {@code 1.0}) — the PMDA workbooks' version columns. */
    private static final Pattern VERSION_COLUMN = Pattern.compile("^\\d+(\\.\\d+)*$");

    /** {@code SENDIG-DART v1.2} — the SEND workbook's {@code SENDIG Version} cell. */
    private static final Pattern SEND_VERSION = Pattern.compile("^(\\S+) v(\\S+)$");

    private static final Pattern PMDA_AD_ID = Pattern.compile("^AD\\d");

    /**
     * The authority origins per family directory. {@code FDA}'s CG references and {@code PMDA}'s
     * FDA one-hop block are linkage, not membership; {@code CORE} is comparable only through the
     * CDISC conformance sheets it cites; {@code DRAFT} (a staging family) may cite any of them.
     */
    static final Map<String, Set<String>> AUTHORITY_ORIGINS = Map.of("FDA", Set.of(FDA_ORIGIN),
            "PMDA", Set.of(PMDA_ORIGIN), "CDISC", Set.of(SEND_ORIGIN, CG_ORIGIN, ADAM_ORIGIN),
            "CORE", Set.of(SEND_ORIGIN, CG_ORIGIN, ADAM_ORIGIN), "DRAFT",
            Set.of(FDA_ORIGIN, PMDA_ORIGIN, SEND_ORIGIN, CG_ORIGIN, ADAM_ORIGIN));

    private static final Set<String> ALL_ORIGINS = Set.of(FDA_ORIGIN, PMDA_ORIGIN, SEND_ORIGIN,
            CG_ORIGIN, ADAM_ORIGIN);

    private StandardsMembershipOracle()
    {
    }

    // ---- data model -------------------------------------------------------

    /** A package key: a {@code Standards[].Standards[]} entry's {@code (Name, Version)}. */
    record Key(String name, String version) implements Comparable<Key>
    {

        private static final Comparator<Key> ORDER = Comparator.comparing(Key::name)
                .thenComparing(Key::version);

        /** Parses the TSV spelling {@code Name Version}. */
        static Key parse(String text)
        {
            String[] parts = SPACE.split(text.strip(), 2);
            if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty())
            {
                throw new IllegalArgumentException("not a 'Name Version' key: '" + text + "'");
            }
            return new Key(parts[0], parts[1]);
        }


        @Override
        public int compareTo(Key o)
        {
            return ORDER.compare(this, o);
        }


        @Override
        public String toString()
        {
            return name + " " + version;
        }
    }


    /**
     * One workbook binding: which authority {@code Origin} it answers, and (for the two PMDA sheets
     * sharing one origin) which sheet ids it owns.
     */
    record Binding(String name, String origin, Predicate<String> ownsSheetId)
    {
    }


    /** One binding's projection: the columns it carries, and each sheet id's marked keys. */
    record Projection(String binding, Set<Key> columns, Map<String, Set<Key>> byId)
    {

        @Nullable
        Set<Key> keys(String sheetId)
        {
            return byId.get(sheetId);
        }
    }


    /** Every binding's projection, whether loaded from the workbooks or the committed TSV. */
    record Sheets(SequencedMap<String, Projection> byBinding)
    {

        /** The binding answering {@code (origin, sheetId)}, or {@code null} if none does. */
        static @Nullable Binding binding(String origin, String sheetId)
        {
            for (Binding b : BINDINGS)
            {
                if (b.origin().equals(origin) && b.ownsSheetId().test(sheetId))
                {
                    return b;
                }
            }
            return null;
        }


        @Nullable
        Projection projection(String binding)
        {
            return byBinding.get(binding);
        }
    }

    static final List<Binding> BINDINGS = List.of(new Binding("FDA", FDA_ORIGIN, _ -> true),
            new Binding("PMDA-AD", PMDA_ORIGIN, id -> PMDA_AD_ID.matcher(id).find()),
            new Binding("PMDA-SDTM", PMDA_ORIGIN, id -> !PMDA_AD_ID.matcher(id).find()),
            new Binding("CDISC-SEND", SEND_ORIGIN, _ -> true),
            new Binding("CDISC-CG", CG_ORIGIN, _ -> true),
            new Binding("CDISC-AD", ADAM_ORIGIN, _ -> true));

    /** How one rule's declared membership relates to its sheet row(s). */
    enum Verdict
    {
        /** The comparable part of the block equals the row's marked versions. */
        MATCH,
        /**
         * Not judged, with a reason code: {@code SPLIT_SIBLING} (an authored partition of a shared
         * row — see the class javadoc), {@code NO_AUTHORITY_REF} (a minted id with no sheet
         * reference), {@code FDA_BUSINESS_RULES_ONLY} / {@code TIG_ONLY} (the cited workbook has no
         * version columns, or does not exist — <em>unmeasurable</em>, not clean).
         */
        EXEMPT,
        /**
         * ⚠ The failing class: the block declares a version the row does not mark ({@code extra}),
         * omits one it marks ({@code missing}), or both.
         */
        SUSPECT,
        /** The rule cites an authority id the workbook does not carry. */
        UNRESOLVED
    }


    /** One rule's verdict with both sides of the comparison kept for the report. */
    record Finding(String ruleId, String family, List<String> cited, Verdict verdict, String reason,
            Set<Key> declared, Set<Key> sheet, Set<Key> retained, Set<Key> extra, Set<Key> missing)
    {

        String describe()
        {
            StringBuilder sb = new StringBuilder();
            sb.append(ruleId).append(' ').append(String.join("+", cited)).append(' ')
                    .append(verdict);
            if (!reason.isEmpty())
            {
                sb.append(':').append(reason);
            }
            sb.append(" declared=").append(declared).append(" sheet=").append(sheet);
            if (!retained.isEmpty())
            {
                sb.append(" retained=").append(retained);
            }
            if (!extra.isEmpty())
            {
                sb.append(" extra=").append(extra);
            }
            if (!missing.isEmpty())
            {
                sb.append(" missing=").append(missing);
            }
            return sb.toString();
        }
    }


    /** Every rule under the checks root, classified. */
    record Oracle(List<Finding> findings)
    {

        List<Finding> of(Verdict verdict)
        {
            return findings.stream().filter(f -> f.verdict() == verdict).toList();
        }


        /** The findings of {@code verdict} outside the {@code DRAFT} staging family. */
        List<Finding> shipped(Verdict verdict)
        {
            return of(verdict).stream().filter(f -> !"DRAFT".equals(f.family())).toList();
        }


        int count(Verdict verdict)
        {
            return of(verdict).size();
        }


        int total()
        {
            return findings.size();
        }


        /** {@code family -> count} of {@code verdict}, for the report. */
        SequencedMap<String, Integer> byFamily(Verdict verdict)
        {
            SequencedMap<String, Integer> out = new TreeMap<>();
            for (Finding f : of(verdict))
            {
                out.merge(f.family(), 1, Integer::sum);
            }
            return out;
        }
    }

    // ---- entry point ------------------------------------------------------

    /**
     * Loads the workbooks, classifies the corpus, prints the report, and (unless
     * {@code -Doracle.dryRun=true}) rewrites the committed projection.
     *
     * @param args
     *            ignored (configuration is via system properties)
     * @throws IOException
     *             if a workbook or check file cannot be read, or the TSV cannot be written
     */
    public static void main(String[] args) throws IOException
    {
        Path docsDir = Path.of(System.getProperty("cdisc.docs.dir", "/data/cdisc-docs"));
        Path checksRoot = Path.of(System.getProperty("oracle.checksRoot", SRC_CHECKS.toString()));
        Path baseline = Path.of(System.getProperty("oracle.baseline", BASELINE.toString()));
        boolean dryRun = Boolean.getBoolean("oracle.dryRun");

        Sheets sheets = loadSheets(docsDir);
        Oracle oracle = classify(checksRoot, sheets);
        report(oracle);
        if (!dryRun)
        {
            writeBaseline(baseline, sheets);
            System.out.printf(Locale.ROOT, "[oracle] projection -> %s%n", baseline);
        }
    }


    /** Prints the per-verdict tally, the per-family SUSPECT breakdown, and every non-match. */
    static void report(Oracle oracle)
    {
        System.out.printf(Locale.ROOT,
                "[oracle] %d rules: %d match, %d suspect, %d exempt, %d unresolved%n",
                oracle.total(), oracle.count(Verdict.MATCH), oracle.count(Verdict.SUSPECT),
                oracle.count(Verdict.EXEMPT), oracle.count(Verdict.UNRESOLVED));
        System.out.printf(Locale.ROOT, "[oracle] suspect by family: %s%n",
                oracle.byFamily(Verdict.SUSPECT));
        for (Finding f : oracle.findings())
        {
            if (f.verdict() != Verdict.MATCH)
            {
                System.out.printf(Locale.ROOT, "[oracle] %-10s %s%n", f.verdict(), f.describe());
            }
        }
    }

    // ---- workbook loading -------------------------------------------------


    /**
     * Reads all six projections from the workbooks under {@code docsDir}.
     *
     * @param docsDir
     *            the directory holding the five rule workbooks
     * @return the projections, in {@link #BINDINGS} order
     * @throws IOException
     *             if a workbook cannot be read
     */
    static Sheets loadSheets(Path docsDir) throws IOException
    {
        SequencedMap<String, Projection> out = new LinkedHashMap<>();
        try (Workbook wb = WorkbookFactory
                .create(docsDir.resolve("FDA-validator-rules-v1-6.xlsx").toFile()))
        {
            out.put("FDA", markedColumns("FDA", wb.getSheet("FDA Validator Rules v1.6"),
                    "FDA Validator Rule ID", StandardsMembershipOracle::fdaColumn));
        }
        try (Workbook wb = WorkbookFactory
                .create(docsDir.resolve("PMDA-ValidationRules-v6-0.xlsx").toFile()))
        {
            out.put("PMDA-AD", markedColumns("PMDA-AD", wb.getSheet("ADaM Rules"), "RULE ID",
                    label -> versionColumn("ADaMIG", label)));
            out.put("PMDA-SDTM", markedColumns("PMDA-SDTM", wb.getSheet("SDTM Rules"), "RULE ID",
                    label -> versionColumn("SDTMIG", label)));
        }
        try (Workbook wb = WorkbookFactory
                .create(docsDir.resolve("SEND_Conformance_Rules_v5.0.xlsx").toFile()))
        {
            out.put("CDISC-SEND",
                    rowPerVersion("CDISC-SEND", wb.getSheet("_GENETOXv1.0_DARTv1.2"),
                            "CDISC SEND Rule ID", "SENDIG Version",
                            StandardsMembershipOracle::sendKey, id -> id));
        }
        try (Workbook wb = WorkbookFactory
                .create(docsDir.resolve("SDTM_and_SDTMIG_Conformance_Rules_v2.0.xlsx").toFile()))
        {
            out.put("CDISC-CG",
                    rowPerVersion("CDISC-CG", wb.getSheet("SDTMIG Conformance Rules v2.0"),
                            "Rule ID", "SDTMIG Version",
                            v -> v.isEmpty() ? null : new Key("SDTMIG", v), id -> id));
        }
        try (Workbook wb = WorkbookFactory
                .create(docsDir.resolve("ADaM Conformance Rules v5.0.xlsx").toFile()))
        {
            out.put("CDISC-AD",
                    rowPerVersion("CDISC-AD", wb.getSheet("Rules Catalogue"), "Rule ID",
                            "Rule Set (Generally IG Version, OCCDS v1.0, ADNCA v1.0)",
                            v -> v.isEmpty() ? null
                                    : new Key("ADaMIG", StandardsLinkageBackfill.igVersion(v)),
                            id -> "CR" + id));
        }
        return new Sheets(out);
    }


    /** {@code SDTMIG\n3.1.2} → {@code (SDTMIG, 3.1.2)}; any other label → {@code null}. */
    static @Nullable Key fdaColumn(String label)
    {
        Matcher m = FDA_COLUMN.matcher(label);
        return m.matches() ? new Key(m.group(1), m.group(2)) : null;
    }


    /** {@code 3.2} → {@code (name, 3.2)}; any non-version label → {@code null}. */
    static @Nullable Key versionColumn(String name, String label)
    {
        return VERSION_COLUMN.matcher(label).matches() ? new Key(name, label) : null;
    }


    /** {@code SENDIG-DART v1.2} → {@code (SENDIG-DART, 1.2)}; blank/other → {@code null}. */
    static @Nullable Key sendKey(String cell)
    {
        Matcher m = SEND_VERSION.matcher(cell);
        return m.matches() ? new Key(m.group(1), m.group(2)) : null;
    }


    /**
     * A one-row-per-rule sheet whose version columns are marked {@code X}: the universe is the set
     * of column labels {@code column} recognises, and a row's keys are its marked columns. An id
     * with no mark at all is still present (with an empty set) so it resolves.
     */
    private static Projection markedColumns(String binding, Sheet sheet, String idColumn,
            java.util.function.Function<String, @Nullable Key> column)
    {
        Header h = OriginalTextBackfill.header(sheet, idColumn);
        SequencedMap<Integer, Key> versionColumns = new LinkedHashMap<>();
        for (var e : h.columns().entrySet())
        {
            Key key = column.apply(e.getKey());
            if (key != null)
            {
                versionColumns.put(e.getValue(), key);
            }
        }
        Map<String, Set<Key>> byId = new LinkedHashMap<>();
        for (int rr = h.dataStart(); rr <= sheet.getLastRowNum(); rr++)
        {
            Row row = sheet.getRow(rr);
            String id = OriginalTextBackfill.cell(row, h.col(idColumn));
            if (id.isEmpty())
            {
                continue;
            }
            Set<Key> keys = byId.computeIfAbsent(id, _ -> new TreeSet<>());
            for (var e : versionColumns.entrySet())
            {
                if ("X".equalsIgnoreCase(OriginalTextBackfill.cell(row, e.getKey())))
                {
                    keys.add(e.getValue());
                }
            }
        }
        return new Projection(binding, new TreeSet<>(versionColumns.values()), byId);
    }


    /**
     * A one-row-per-(rule, version) sheet: a row's {@code versionColumn} cell parses to one key;
     * the universe is every key any row produced.
     */
    private static Projection rowPerVersion(String binding, Sheet sheet, String idColumn,
            String versionColumn, java.util.function.Function<String, @Nullable Key> parse,
            java.util.function.UnaryOperator<String> idNormalizer)
    {
        Header h = OriginalTextBackfill.header(sheet, idColumn, versionColumn);
        Set<Key> universe = new TreeSet<>();
        Map<String, Set<Key>> byId = new LinkedHashMap<>();
        for (int rr = h.dataStart(); rr <= sheet.getLastRowNum(); rr++)
        {
            Row row = sheet.getRow(rr);
            String id = OriginalTextBackfill.cell(row, h.col(idColumn));
            if (id.isEmpty())
            {
                continue;
            }
            Key key = parse.apply(OriginalTextBackfill.cell(row, h.col(versionColumn)));
            Set<Key> keys = byId.computeIfAbsent(idNormalizer.apply(id), _ -> new TreeSet<>());
            if (key != null)
            {
                keys.add(key);
                universe.add(key);
            }
        }
        return new Projection(binding, universe, byId);
    }

    // ---- committed projection ---------------------------------------------


    /**
     * Rewrites the committed projection: one row per (binding, sheet id) plus one {@code *} row per
     * binding carrying its column universe.
     */
    static void writeBaseline(Path tsv, Sheets sheets) throws IOException
    {
        Path parent = tsv.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        StringBuilder sb = new StringBuilder("""
                # Sheet version-column projection for the package-membership oracle (M2-D17).
                # binding <TAB> sheet id <TAB> comma-separated `Name Version` keys the row marks.
                # The row with id `*` lists the columns the workbook carries: the comparable
                # universe — a declared key outside it is sheet-silent and retained, never judged.
                # Generated by StandardsMembershipOracle from the workbooks — do not hand-edit.
                """);
        for (Projection p : sheets.byBinding().values())
        {
            row(sb, p.binding(), UNIVERSE_ID, p.columns());
            List<String> ids = new ArrayList<>(p.byId().keySet());
            ids.sort(Comparator.naturalOrder());
            for (String id : ids)
            {
                row(sb, p.binding(), id, p.byId().get(id));
            }
        }
        Files.writeString(tsv, sb.toString(), StandardCharsets.UTF_8);
    }


    private static void row(StringBuilder sb, String binding, String id, Set<Key> keys)
    {
        sb.append(binding).append('\t').append(id).append('\t');
        boolean first = true;
        for (Key k : new TreeSet<>(keys))
        {
            if (!first)
            {
                sb.append(',');
            }
            sb.append(k);
            first = false;
        }
        sb.append('\n');
    }


    /** Reads the committed projection written by {@link #writeBaseline}. */
    static Sheets readBaseline(Path tsv) throws IOException
    {
        SequencedMap<String, Set<Key>> universes = new LinkedHashMap<>();
        SequencedMap<String, Map<String, Set<Key>>> rows = new LinkedHashMap<>();
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8))
        {
            if (line.isBlank() || line.charAt(0) == '#')
            {
                continue;
            }
            String[] cells = TAB.split(line, -1);
            if (cells.length != 3)
            {
                throw new IOException("malformed row in " + tsv + ": " + line);
            }
            Set<Key> keys = new TreeSet<>();
            if (!cells[2].isBlank())
            {
                for (String k : COMMA.split(cells[2], -1))
                {
                    keys.add(Key.parse(k));
                }
            }
            if (UNIVERSE_ID.equals(cells[1]))
            {
                universes.put(cells[0], keys);
            }
            else
            {
                rows.computeIfAbsent(cells[0], _ -> new LinkedHashMap<>()).put(cells[1], keys);
            }
        }
        SequencedMap<String, Projection> out = new LinkedHashMap<>();
        for (var e : universes.entrySet())
        {
            out.put(e.getKey(), new Projection(e.getKey(), e.getValue(),
                    rows.getOrDefault(e.getKey(), Map.of())));
        }
        for (String binding : rows.keySet())
        {
            if (!out.containsKey(binding))
            {
                throw new IOException(
                        "binding without a '*' universe row in " + tsv + ": " + binding);
            }
        }
        return new Sheets(out);
    }

    // ---- classification ---------------------------------------------------

    /** One check file as the oracle sees it. */
    record Rule(String id, String family, Set<Key> declared, Set<Ref> refs)
    {
    }


    /** One {@code References[]} entry's {@code (Origin, Rule_Identifier.Id)}. */
    record Ref(String origin, String id)
    {
    }


    /** A rule's resolved authority citation: the binding that answers it and the sheet id. */
    record Citation(Binding binding, String sheetId)
    {

        @Override
        public String toString()
        {
            return binding.name() + ":" + sheetId;
        }
    }

    /**
     * Classifies every check under {@code checksRoot} (all family directories, {@code DRAFT}
     * included — the test decides which families gate).
     *
     * @param checksRoot
     *            the {@code rules-src/checks} root
     * @param sheets
     *            the projection to compare against
     * @return one finding per rule, in family/file order
     * @throws IOException
     *             if a check file cannot be read
     */
    static Oracle classify(Path checksRoot, Sheets sheets) throws IOException
    {
        List<Rule> rules = load(checksRoot);
        // Sibling groups: rules of one family sharing an authority citation.
        Map<String, Map<Citation, List<Rule>>> groups = new HashMap<>();
        Map<Rule, List<Citation>> citations = new LinkedHashMap<>();
        for (Rule rule : rules)
        {
            List<Citation> cited = citations(rule);
            citations.put(rule, cited);
            for (Citation c : cited)
            {
                groups.computeIfAbsent(rule.family(), _ -> new HashMap<>())
                        .computeIfAbsent(c, _ -> new ArrayList<>()).add(rule);
            }
        }
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : rules)
        {
            findings.add(classify(rule, citations.get(rule), sheets,
                    groups.getOrDefault(rule.family(), Map.of())));
        }
        return new Oracle(findings);
    }


    /** The authority citations of a rule: its refs whose origin is the family's own sheet. */
    static List<Citation> citations(Rule rule)
    {
        Set<String> authority = AUTHORITY_ORIGINS.getOrDefault(rule.family(), ALL_ORIGINS);
        List<Citation> out = new ArrayList<>();
        for (Ref ref : rule.refs())
        {
            if (!authority.contains(ref.origin()))
            {
                continue;
            }
            Binding b = Sheets.binding(ref.origin(), ref.id());
            if (b != null)
            {
                Citation c = new Citation(b, ref.id());
                if (!out.contains(c))
                {
                    out.add(c);
                }
            }
        }
        out.sort(Comparator.comparing(Citation::toString));
        return out;
    }


    private static Finding classify(Rule rule, List<Citation> cited, Sheets sheets,
            Map<Citation, List<Rule>> groups)
    {
        List<String> citedText = cited.stream().map(Citation::toString).toList();
        Set<Key> declared = rule.declared();
        if (cited.isEmpty())
        {
            return new Finding(rule.id(), rule.family(), citedText, Verdict.EXEMPT,
                    unreachableReason(rule), declared, Set.of(), declared, Set.of(), Set.of());
        }
        Set<Key> universe = new TreeSet<>();
        Set<Key> sheet = new TreeSet<>();
        List<String> unresolved = new ArrayList<>();
        Set<Rule> siblings = new LinkedHashSet<>();
        for (Citation c : cited)
        {
            Projection p = sheets.projection(c.binding().name());
            Set<Key> keys = p == null ? null : p.keys(c.sheetId());
            if (keys == null)
            {
                unresolved.add(c.toString());
                continue;
            }
            universe.addAll(p.columns());
            sheet.addAll(keys);
            for (Rule s : groups.getOrDefault(c, List.of()))
            {
                if (!s.equals(rule))
                {
                    siblings.add(s);
                }
            }
        }
        Set<Key> retained = minus(declared, universe);
        if (!unresolved.isEmpty())
        {
            return new Finding(rule.id(), rule.family(), citedText, Verdict.UNRESOLVED,
                    String.join(",", unresolved), declared, sheet, retained, Set.of(), Set.of());
        }
        Set<Key> own = intersect(declared, universe);
        Set<Key> extra = minus(own, sheet);
        Set<Key> missing = minus(sheet, own);
        if (extra.isEmpty() && missing.isEmpty())
        {
            return new Finding(rule.id(), rule.family(), citedText, Verdict.MATCH, "", declared,
                    sheet, retained, extra, missing);
        }
        if (extra.isEmpty() && !siblings.isEmpty())
        {
            Set<Key> covered = new TreeSet<>();
            for (Rule s : siblings)
            {
                covered.addAll(intersect(s.declared(), universe));
            }
            if (own.containsAll(minus(sheet, covered)))
            {
                List<String> names = siblings.stream().map(Rule::id).sorted().toList();
                return new Finding(rule.id(), rule.family(), citedText, Verdict.EXEMPT,
                        "SPLIT_SIBLING:" + String.join(",", names), declared, sheet, retained,
                        extra, missing);
            }
        }
        String direction = extra.isEmpty() ? "UNDER" : missing.isEmpty() ? "OVER" : "BOTH";
        return new Finding(rule.id(), rule.family(), citedText, Verdict.SUSPECT, direction,
                declared, sheet, retained, extra, missing);
    }


    /** The reason code for a rule with no authority citation. */
    private static String unreachableReason(Rule rule)
    {
        Set<String> origins = new TreeSet<>();
        for (Ref ref : rule.refs())
        {
            origins.add(ref.origin());
        }
        if (origins.isEmpty())
        {
            return "NO_AUTHORITY_REF";
        }
        if (Set.of(FDA_BUSINESS_ORIGIN).containsAll(origins))
        {
            return "FDA_BUSINESS_RULES_ONLY";
        }
        if (Set.of(FDA_BUSINESS_ORIGIN, TIG_ORIGIN).containsAll(origins))
        {
            return "TIG_ONLY";
        }
        return "NO_AUTHORITY_REF:" + String.join("+", origins);
    }


    private static Set<Key> minus(Set<Key> a, Set<Key> b)
    {
        Set<Key> out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }


    private static Set<Key> intersect(Set<Key> a, Set<Key> b)
    {
        Set<Key> out = new TreeSet<>(a);
        out.retainAll(b);
        return out;
    }

    // ---- corpus loading ---------------------------------------------------


    /** Every {@code *.yaml} under every family directory of {@code checksRoot}, sorted. */
    static List<Rule> load(Path checksRoot) throws IOException
    {
        List<Rule> out = new ArrayList<>();
        List<Path> families;
        try (Stream<Path> stream = Files.list(checksRoot))
        {
            families = stream.filter(Files::isDirectory).sorted().toList();
        }
        for (Path dir : families)
        {
            List<Path> files;
            try (Stream<Path> stream = Files.list(dir))
            {
                files = stream.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted()
                        .toList();
            }
            for (Path file : files)
            {
                JsonNode node = YAML_READ.readTree(Files.readString(file, StandardCharsets.UTF_8));
                String id = node.path("Core").path("Id").asText();
                out.add(new Rule(id.isEmpty() ? file.getFileName().toString() : id,
                        dir.getFileName().toString(), declaredKeys(node), references(node)));
            }
        }
        return out;
    }


    /**
     * The distinct {@code (Name, Version)} keys a check's {@code Standards} block names over all
     * {@code Organization} entries — exactly the assembler's package projection.
     */
    static Set<Key> declaredKeys(JsonNode root)
    {
        Set<Key> out = new TreeSet<>();
        for (JsonNode authority : root.path("Standards"))
        {
            for (JsonNode std : authority.path("Standards"))
            {
                String name = text(std.get("Name"));
                String version = text(std.get("Version"));
                if (name != null && version != null)
                {
                    out.add(new Key(name, version));
                }
            }
        }
        return out;
    }


    static Set<Ref> references(JsonNode root)
    {
        Set<Ref> out = new LinkedHashSet<>();
        for (JsonNode authority : root.path("Standards"))
        {
            for (JsonNode std : authority.path("Standards"))
            {
                for (JsonNode ref : std.path("References"))
                {
                    String origin = text(ref.get("Origin"));
                    String id = text(ref.path("Rule_Identifier").get("Id"));
                    if (origin != null && id != null)
                    {
                        out.add(new Ref(origin, id));
                    }
                }
            }
        }
        return out;
    }


    private static @Nullable String text(@Nullable JsonNode node)
    {
        return node == null || node.isNull() ? null : node.asText();
    }
}
