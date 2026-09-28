package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.OperationExecutor;
import net.cumba.corej.core.exec.ScopeMatcher;
import net.cumba.corej.core.exec.ScopeVariableEntry;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.BroadcastFold;
import net.cumba.corej.core.expr.typed.StageBChecker;
import net.cumba.corej.core.expr.typed.TypeExpectations;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.metadata.AdamDataStructureDetector;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The authoring gate for an <b>unqualified read of a column only a joined dataset carries</b>
 * ({@code PLAN-unqualified-joined-column-gate}, owner ruling D1 = C): a rule that joins dataset
 * {@code J} through {@code Match_Datasets} and reads a bare {@code X} meaning {@code J.X} reads the
 * primary's D76 default instead (ruling {@code UVC}: an unqualified name always means the primary),
 * silently. Authoring rule {@code R-7.7} says a joined read is qualified; this lint enforces it in
 * the corpus build, before release. The engine's evaluation is unchanged.
 *
 * <h2>The predicate (plan §4 S6)</h2> {@code (rule, package, X, J)} is flagged iff
 * <ul>
 * <li>{@code X} is read bare — the engine's own absent-column fold set,
 * {@link TypeExpectations#valueReadColumns()} over {@link StageBChecker#expressionRoots}, plus the
 * bare {@code Outcome.Output_Variables} names (S1). {@code --} names specialise to the primary's
 * prefix and are out by construction;</li>
 * <li>no library dataset (SDTM/SEND) or data structure (ADaM) the rule's scope selects defines
 * {@code X} (S3/S4);</li>
 * <li>{@code J} is a non-{@code Child} entry whose target defines {@code X} (S5; D2 — a
 * {@code Child: true} entry reads its parent under the bare name by design);</li>
 * <li>no {@code Child: true} parent of the rule supplies {@code X} (the tie-break);</li>
 * <li>{@code X} is not declared: bare in {@code All}, bare in any {@code Any} group (D3), or an
 * {@code All_Or_None} group holding both {@code X} and {@code J.X}. {@code None} and a
 * {@code J.X}-only declaration do not count.</li>
 * </ul>
 *
 * <h2>Where the library comes from</h2> The project's own metadata store
 * ({@link MetadataStoreFixture}), never the pickle index: SDTM/SEND datasets through
 * {@code StoreMetadataProviderFactory.forSdtm(…).getDomainVariables} with the engine's own dataset
 * class ({@link MetadataLibraryProvider#getDatasetClass(String)}); ADaM data structures read off
 * the {@link StoredProduct} records (the provider serves only the Req core for ADaM, plan §2.5).
 * ADaM {@code class} strings are canonicalised exactly as the engine does
 * ({@code MetadataLibraryProvider.ADAM_CLASS_ALIASES} / {@code STRUCTURE_CLASS_OVERRIDES}, which
 * are package-private — copied, not widened, because the plan allows no engine API change).
 *
 * <p>
 * An ADaM rule's scope is matched against its primary product's structures plus the ADaM supplement
 * products ({@code adam-occds}, {@code adam-adae}, {@code adam-md}, …), which publish the OCCDS and
 * device structures the IG products do not. ADaM joins to SDTM ({@code DM}, {@code AE}) resolve
 * against the union of every SDTMIG product in the store (S5).
 * </p>
 *
 * <h2>Nothing is dropped silently</h2> A (rule, package) whose scope selects no library dataset is
 * named in R-EMPTY; a join target no product defines is counted in R-UNRES; a statically
 * unresolvable wildcard read (an ADaM capture template such as {@code TRTxxP}, or a {@code *} root)
 * is named in R-CAPTURE (S2); a read the {@code Child: true} exclusion takes out is named in
 * R-CHILD; a joined read the rule declares for the primary is named in R-DECLARED. Each is pinned
 * for exact equality, and the reach per family is pinned too (S8) — a census that went blind would
 * still report zero flags.
 *
 * <p>
 * ⭐ <b>Non-vacuity, twice</b> (S10): the predicate runs over synthetic rules of every exemption and
 * non-exemption on every build, and the live sabotage recorded in the plan's status file (a
 * {@code rules-src} rule edited to a bare joined read) turned this test red before it was armed.
 * </p>
 */
class UnqualifiedJoinedReadLintTest
{

    private static final String SUPPQUAL = "SUPPQUAL";

    /**
     * {@code MetadataLibraryProvider.ADAM_CLASS_ALIASES}: the pre-1.3 ADaM {@code class} spellings.
     */
    private static final Map<String, String> ADAM_CLASS_ALIASES = Map.of("ADSL",
            AdamDataStructureDetector.ADSL, "BDS", AdamDataStructureDetector.BDS, "OCCDS",
            AdamDataStructureDetector.OCCDS, "ADAE", AdamDataStructureDetector.OCCDS);

    /** {@code MetadataLibraryProvider.STRUCTURE_CLASS_OVERRIDES}: a structure with no class. */
    private static final Map<String, String> STRUCTURE_CLASS_OVERRIDES = Map
            .of("standards/adam/adam-tte-1-0\tBDS for TTE", AdamDataStructureDetector.BDS);

    // Measured 2026-09-28 at cumba-corej-rules 0f3869f2 (PLAN-unqualified-joined-column-gate
    // phase 2c). EXACT — see the assertion messages for what may move each one.

    private static final Map<String, Integer> EXPECTED_REACH = Map.of("ADaM", 187, "SDTM", 421,
            "SEND", 142);

    private static final Map<String, Integer> EXPECTED_UNRESOLVED = Map.of("adam/adamig-1-0\t&DOM",
            1, "adam/adamig-1-1\t&DOM", 1, "adam/adamig-1-2\t&DOM", 1, "adam/adamig-1-3\t&DOM", 1,
            "sendig/dart-1-2\tDM", 2, "sendig/dart-1-2\tDS", 1, "sendig/dart-1-2\tRELREC", 2,
            "sendig/dart-1-2\tSJ", 3);

    private static final Set<String> EXPECTED_EMPTY = Set.of(
            "rules-fda-sendig-ar-1-0.json\tFDA-SD1205", "rules-fda-sendig-ar-1-0.json\tFDA-SD1206",
            "rules-fda-sendig-ar-1-0.json\tFDA-SD1207", "rules-fda-sendig-ar-1-0.json\tFDA-SD9747",
            "rules-fda-sendig-ar-1-0.json\tFDA-SE1152");

    private static final Set<String> EXPECTED_CAPTURES = Set.of("CDISC-AD0592\tAPxxSDT");

    private static final Set<String> EXPECTED_CHILD = Set.of("CDISC-CG0043\tAESMIE",
            "FDA-SD1143\tAESMIE");

    private static final Map<String, String> EXPECTED_DECLARED = Map.of("CDISC-AD0899\tRFSTDTC\tDM",
            "All", "CDISC-CG0217\tTAETORD\tSE", "All", "CDISC-CG0218\tEPOCH\tSE", "All",
            "PMDA-AD0258\tAESEQ\tAE", "All_Or_None pair", "PMDA-AD0367\tACTARM\tDM", "All");

    /**
     * One library dataset (SDTM/SEND) or data structure (ADaM) a rule's scope may select, or a join
     * may target.
     *
     * @param name
     *            the dataset / structure name
     * @param datasetClass
     *            the observation class (SDTM/SEND) or the structure token (ADaM); {@code null} when
     *            the library publishes none
     * @param structureTokens
     *            ADaM only: the structure set ({@link AdamDataStructureDetector#structureSet});
     *            empty for SDTM/SEND
     * @param subclasses
     *            ADaM only: the published subclass, if any
     * @param variables
     *            the concrete variable names it defines
     * @param templates
     *            the ADaM wildcard variable templates it defines ({@code TRTxxP} …), compiled
     */
    record LibraryDataset(String name, @Nullable String datasetClass, List<String> structureTokens,
            List<String> subclasses, Set<String> variables, List<Pattern> templates)
    {

        boolean defines(String aVariable)
        {
            if (variables.contains(aVariable))
            {
                return true;
            }
            for (Pattern template : templates)
            {
                if (template.matcher(aVariable).matches())
                {
                    return true;
                }
            }
            return false;
        }


        static LibraryDataset of(String aName, @Nullable String aClass, List<String> aStructures,
                List<String> aSubclasses, Iterable<String> aNames)
        {
            Set<String> concrete = new TreeSet<>();
            List<Pattern> templates = new ArrayList<>();
            for (String n : aNames)
            {
                Pattern template = WildcardExpander.scopeVariableWildcardPattern(n);
                if (template == null)
                {
                    concrete.add(n);
                }
                else
                {
                    templates.add(template);
                }
            }
            return new LibraryDataset(aName, aClass, aStructures, aSubclasses, concrete, templates);
        }
    }


    /**
     * The library one package is judged against.
     *
     * @param standard
     *            the package's primary standard id(s), for the registers
     * @param family
     *            SDTM, SEND, ADaM or TIG — the reach bucket
     * @param scopeCandidates
     *            every dataset / structure a rule's scope may select
     * @param joinTargets
     *            upper-cased name → the dataset / structure a join of that name reads
     */
    record Universe(String standard, String family, List<LibraryDataset> scopeCandidates,
            Map<String, LibraryDataset> joinTargets)
    {
    }


    /** Everything one census found, and how far it reached. */
    static final class Census
    {

        /** family → joined (rule, package) pairs examined against a resolved library. */
        final Map<String, Integer> reach = new TreeMap<>();

        /** {@code ruleId \t X \t J} → the packages the flag was raised in. */
        final Map<String, Set<String>> flags = new TreeMap<>();

        /** R-EMPTY: {@code package \t ruleId} whose scope selects no library dataset. */
        final Set<String> empty = new TreeSet<>();

        /** R-UNRES: {@code standard \t target} → (rule, package) references. */
        final Map<String, Integer> unresolved = new TreeMap<>();

        /** R-CAPTURE: {@code ruleId \t name} of a statically unresolvable wildcard read. */
        final Set<String> captures = new TreeSet<>();

        /** R-CHILD: {@code ruleId \t X} a {@code Child: true} parent supplies (D2). */
        final Set<String> childExcluded = new TreeSet<>();

        /**
         * R-DECLARED: {@code ruleId \t X \t J} a join carries, no scoped dataset defines, and the
         * rule declares — the exempted half of the population, whose size is what makes a zero flag
         * count mean something.
         */
        final Map<String, String> declared = new TreeMap<>();

        /** Rules that carry a load error — examined by nothing, so they must not exist. */
        final Set<String> loadErrors = new TreeSet<>();

        /** Examines one rule of one package; {@code aUniverse} null ⇒ the product is absent. */
        void examine(String aPackage, String aStandard, @Nullable Universe aUniverse, Rule aRule)
        {
            List<MatchDataset> joins = aRule.getMatchDatasets();
            if (joins == null || joins.isEmpty())
            {
                return;
            }
            String id = String.valueOf(aRule.effectiveId());
            if (aRule.getLoadError() != null)
            {
                loadErrors.add(aPackage + "\t" + id + "\t" + aRule.getLoadError());
                return;
            }
            if (aUniverse == null)
            {
                for (MatchDataset md : joins)
                {
                    unresolved.merge(aStandard + "\t" + md.getName(), 1, Integer::sum);
                }
                return;
            }
            List<LibraryDataset> scope = new ArrayList<>();
            for (LibraryDataset candidate : aUniverse.scopeCandidates())
            {
                if (inScope(aRule, candidate))
                {
                    scope.add(candidate);
                }
            }
            reach.merge(aUniverse.family(), 1, Integer::sum);
            Map<String, LibraryDataset> targets = new LinkedHashMap<>();
            List<@Nullable LibraryDataset> childParents = new ArrayList<>();
            for (MatchDataset md : joins)
            {
                String name = md.getName() == null ? "" : md.getName().toUpperCase(Locale.ROOT);
                LibraryDataset target = aUniverse.joinTargets().get(name);
                if (Boolean.TRUE.equals(md.getChild()))
                {
                    // A Child parent is resolved per row (RDOMAIN), so an entry whose name is not
                    // a concrete dataset (SUPP--, a pattern) may supply any column: null here.
                    childParents.add(target);
                    continue;
                }
                if (target == null)
                {
                    unresolved.merge(aUniverse.standard() + "\t" + md.getName(), 1, Integer::sum);
                    continue;
                }
                targets.putIfAbsent(name, target);
            }
            Set<String> reads = new LinkedHashSet<>();
            List<Expr> roots = StageBChecker.expressionRoots(aRule);
            if (roots.isEmpty() && aRule.getCheck() != null)
            {
                // A Check with no compiled form — a wildcard template the engine expands per
                // dataset (S2). Its parsed expression still names every bare read.
                if (aRule.getCheck() instanceof CheckConditionExpression expression)
                {
                    roots = List.of(expression.expr());
                }
                else
                {
                    captures.add(id + "\t(uncompiled Check)");
                }
            }
            reads.addAll(TypeExpectations.of(roots).valueReadColumns());
            List<String> outputs = aRule.getOutcome() == null ? null
                    : aRule.getOutcome().getOutputVariables();
            for (String output : outputs == null ? List.<String> of() : outputs)
            {
                if (BroadcastFold.isFoldableColumnReference(output))
                {
                    reads.add(output);
                }
                else if (isCaptureWildcard(output))
                {
                    captures.add(id + "\t" + output);
                }
            }
            for (Expr root : roots)
            {
                collectCaptures(root, id, captures);
            }
            if (scope.isEmpty())
            {
                empty.add(aPackage + "\t" + id);
                return;
            }
            if (childParents.isEmpty() && targets.isEmpty())
            {
                return;
            }
            VariableRequirement vars = aRule.effectiveVariableRequirement();
            for (String x : reads)
            {
                if (definedByAny(scope, x))
                {
                    continue;
                }
                String bare = bareDeclaration(vars, x, scope);
                boolean childSupplies = false;
                for (LibraryDataset parent : childParents)
                {
                    childSupplies |= parent == null ? definedByAny(aUniverse.scopeCandidates(), x)
                            : parent.defines(x);
                }
                if (childSupplies && bare == null)
                {
                    childExcluded.add(id + "\t" + x);
                    continue;
                }
                for (Map.Entry<String, LibraryDataset> target : targets.entrySet())
                {
                    if (!target.getValue().defines(x) || childSupplies)
                    {
                        continue;
                    }
                    String key = id + "\t" + x + "\t" + target.getKey();
                    String declaration = bare != null ? bare
                            : declaredAsPair(vars, x, target.getKey(), scope) ? "All_Or_None pair"
                                    : null;
                    if (declaration == null)
                    {
                        flags.computeIfAbsent(key, k -> new TreeSet<>()).add(aPackage);
                    }
                    else
                    {
                        declared.put(key, declaration);
                    }
                }
            }
        }


        void print(PrintStream aOut)
        {
            aOut.println(
                    "[UnqualifiedJoinedReadLint] reach (joined rule x package examined): " + reach);
            aOut.println("[UnqualifiedJoinedReadLint] FLAGS: " + flags.size());
            flags.forEach((k, v) -> aOut.println("  FLAG\t" + k + "\t" + v));
            aOut.println("[UnqualifiedJoinedReadLint] R-EMPTY: " + empty.size());
            empty.forEach(e -> aOut.println("  EMPTY\t" + e));
            aOut.println("[UnqualifiedJoinedReadLint] R-UNRES: " + unresolved.size());
            unresolved.forEach((k, v) -> aOut.println("  UNRES\t" + k + "\t" + v));
            aOut.println("[UnqualifiedJoinedReadLint] R-CAPTURE: " + captures.size());
            captures.forEach(c -> aOut.println("  CAPTURE\t" + c));
            aOut.println("[UnqualifiedJoinedReadLint] R-DECLARED: " + declared.size());
            declared.forEach((k, v) -> aOut.println("  DECLARED\t" + k + "\t" + v));
            aOut.println("[UnqualifiedJoinedReadLint] R-CHILD: " + childExcluded.size());
            childExcluded.forEach(c -> aOut.println("  CHILD\t" + c));
            aOut.println("[UnqualifiedJoinedReadLint] load errors: " + loadErrors.size());
            loadErrors.forEach(c -> aOut.println("  LOADERR\t" + c));
        }
    }

    @Test
    void noRuleReadsABareNameOnlyAJoinCarries() throws IOException
    {
        Path storeFile = MetadataStoreFixture.storeFile()
                .orElseThrow(() -> new AssertionError("No metadata store: this lint decides"
                        + " which library dataset defines a column, which is not derivable from"
                        + " this repository. Set CDISC_PICKLE_CACHE_DIR (or"
                        + " -Dcdisc.pickle.cache.dir) to a pickle cache directory —"
                        + " MetadataStoreFixture seeds the store from it. Failing rather than"
                        + " skipping is deliberate: a skipped corpus lint and a clean one look"
                        + " identical in a log."));
        StoreMetadataProviderFactory factory = MetadataStoreFixture.factory()
                .orElseThrow(() -> new AssertionError("no metadata store provider factory"));
        Census census = new Census();
        try (MetadataStore store = MetadataStore.open(storeFile))
        {
            Libraries libraries = new Libraries(store, factory);
            JsonNode manifest = new ObjectMapper()
                    .readTree(CorpusPaths.rules("packages.json").toFile());
            for (JsonNode pkg : manifest.path("packages"))
            {
                String file = pkg.path("file").asText();
                List<String> primaries = new ArrayList<>();
                for (JsonNode s : pkg.path("standards"))
                {
                    if ("primary".equals(s.path("role").asText()))
                    {
                        primaries.add(s.path("id").asText());
                    }
                }
                String standard = String.join("+", primaries);
                Universe universe = libraries.universe(primaries);
                RulePackage rules = RulePackageLoader.load(CorpusPaths.rules(file));
                for (Rule rule : rules.getRules().values())
                {
                    if (rule != null)
                    {
                        census.examine(file, standard, universe, rule);
                    }
                }
            }
        }
        census.print(System.out);
        assertTrue(census.loadErrors.isEmpty(),
                "rules with a load error are examined by nothing: " + census.loadErrors);
        assertTrue(census.flags.isEmpty(), census.flags.size() + " bare read(s) of a column only"
                + " a joined dataset carries. An unqualified name always means the PRIMARY's"
                + " column (ruling UVC), so each of these reads the D76 default, never the join —"
                + " silently. Qualify the read as J.X (authoring rule R-7.7), or, when the primary"
                + " really is meant, declare X bare in Requirements.Variables All or an Any group"
                + " (rule-guide authoring/joins.md). ruleId / X / J -> packages:\n  "
                + String.join("\n  ", census.flags.entrySet().stream()
                        .map(e -> e.getKey().replace('\t', ' ') + " " + e.getValue()).toList()));
        // ⛔ The ratchets below are EXACT (S8): a census that stopped seeing a family, a package or
        // a rule would still report zero flags, so the reach and every register are pinned. Moving
        // a pin is legitimate only with the reason in the same commit.
        assertEquals(EXPECTED_REACH, census.reach, "joined (rule, package) pairs examined per"
                + " family — a new joined rule moves this (confirm it reads no bare joined column,"
                + " then move the pin); a family that DROPPED means the library or the corpus went"
                + " short and the zero above is no longer evidence");
        assertEquals(EXPECTED_UNRESOLVED, census.unresolved, "R-UNRES: join targets no store"
                + " product defines, (standard, target) -> references. None of these joins is"
                + " examined; a new entry is a join the lint cannot judge — say so in the commit");
        assertEquals(EXPECTED_EMPTY, census.empty, "R-EMPTY: (package, rule) whose scope selects"
                + " no library dataset, so none of its reads is examined");
        assertEquals(EXPECTED_CAPTURES, census.captures, "R-CAPTURE: wildcard reads (an ADaM"
                + " capture template, a * root) no static census resolves (S2)");
        assertEquals(EXPECTED_CHILD, census.childExcluded, "R-CHILD: bare reads a Child: true"
                + " parent supplies (D2) — excluded, and counted so they stay SEEN");
        assertEquals(EXPECTED_DECLARED, census.declared, "R-DECLARED: bare reads a join carries"
                + " that the rule declares for the primary — the exempted population");
    }

    // ------------------------------------------------------------------
    // The predicate against synthetic rules (S10 i) — permanent controls
    // ------------------------------------------------------------------

    private static final String KEYED_DM = """
            "Match_Datasets": [{"Name": "DM", "Keys": ["USUBJID"]}],
            """;

    private static final String DM_KEY = "[\"USUBJID\", \"DM.USUBJID\"]";

    private static final String USUBJID = "[\"USUBJID\"]";

    private static final String READ_RFSTDTC = "RFSTDTC != AESTDTC";

    @Test
    void thePredicateFlagsAnUndeclaredBareJoinedReadAndHonoursEveryExemption() throws IOException
    {
        // the positive: RFSTDTC is in no scoped dataset (AE), the DM join carries it, undeclared
        assertFlags(rule("T-POS", "[\"AE\"]", KEYED_DM, USUBJID, null, null, DM_KEY, READ_RFSTDTC,
                null), "T-POS\tRFSTDTC\tDM");
        // Output_Variables is the same mistake where a user sees it
        assertFlags(rule("T-OV", "[\"AE\"]", KEYED_DM, USUBJID, null, null, DM_KEY,
                "AESTDTC == \\\"\\\"", "[\"AESTDTC\", \"RFSTDTC\"]"), "T-OV\tRFSTDTC\tDM");
        // None is not a declaration
        assertFlags(rule("T-NONE", "[\"AE\"]", KEYED_DM, USUBJID, null, "[\"RFSTDTC\"]", DM_KEY,
                READ_RFSTDTC, null), "T-NONE\tRFSTDTC\tDM");
        // a qualified-only declaration does not say which side the BARE name means
        assertFlags(rule("T-QUAL", "[\"AE\"]", KEYED_DM, "[\"USUBJID\", \"DM.RFSTDTC\"]", null,
                null, DM_KEY, READ_RFSTDTC, null), "T-QUAL\tRFSTDTC\tDM");
        // a pair naming ANOTHER join does not declare this one
        assertFlags(
                rule("T-PAIR-OTHER", "[\"AE\"]", KEYED_DM, USUBJID, null, null,
                        DM_KEY + ", [\"RFSTDTC\", \"SV.RFSTDTC\"]", READ_RFSTDTC, null),
                "T-PAIR-OTHER\tRFSTDTC\tDM");

        // bare in All: the author states the primary is meant
        assertFlags(rule("T-ALL", "[\"AE\"]", KEYED_DM, "[\"USUBJID\", \"RFSTDTC\"]", null, null,
                DM_KEY, READ_RFSTDTC, null));
        // bare in an Any group (D3)
        assertFlags(rule("T-ANY", "[\"AE\"]", KEYED_DM, USUBJID, "[\"RFSTDTC\", \"AESEQ\"]", null,
                DM_KEY, READ_RFSTDTC, null));
        // a pattern entry declares what it matches
        assertFlags(rule("T-GLOB", "[\"AE\"]", KEYED_DM, USUBJID, "[\"RF*\", \"AGE\"]", null,
                DM_KEY, READ_RFSTDTC, null));
        // the pairing spelling [X, J.X] for THIS join
        assertFlags(rule("T-PAIR", "[\"AE\"]", KEYED_DM, USUBJID, null, null,
                DM_KEY + ", [\"RFSTDTC\", \"DM.RFSTDTC\"]", READ_RFSTDTC, null));
        // a scoped dataset that defines the name (the CDISC-CG0031 shape) is no flag
        assertFlags(rule("T-BROAD", "[\"AE\", \"DM\"]", KEYED_DM, USUBJID, null, null, DM_KEY,
                READ_RFSTDTC, null));
    }


    @Test
    void aChildParentsBareReadIsExcludedAndCountedNeverUnseen() throws IOException
    {
        String suppKeys = "[\"QNAM\", \"USUBJID\", \"IDVAR\", \"IDVARVAL\"]";
        String child = """
                "Match_Datasets": [{"Name": "AE", "Child": true,
                    "Keys": ["USUBJID", "IDVAR", "IDVARVAL"]}],
                """;
        String readAesmie = "QNAM == \\\"AESOSP\\\" and AESMIE != \\\"Y\\\"";
        Census c = census(rule("T-CHILD", "[\"SUPPAE\"]", child, suppKeys, null, null, null,
                readAesmie, null));
        assertEquals(Map.of(), c.flags);
        assertEquals(Set.of("T-CHILD\tAESMIE"), c.childExcluded);

        // the tie-break: an ordinary MH entry carries EPOCH, and so does the Child parent AE —
        // the parent supplies it under the bare name, so it is excluded, not flagged
        String both = """
                "Match_Datasets": [{"Name": "AE", "Child": true,
                    "Keys": ["USUBJID", "IDVAR", "IDVARVAL"]},
                  {"Name": "MH", "Keys": ["USUBJID"]}],
                """;
        String mhKey = "[\"USUBJID\", \"MH.USUBJID\"]";
        String readEpoch = "QNAM == \\\"AESOSP\\\" and EPOCH != \\\"\\\"";
        Census tie = census(
                rule("T-TIE", "[\"SUPPAE\"]", both, suppKeys, null, null, mhKey, readEpoch, null));
        assertEquals(Map.of(), tie.flags);
        assertEquals(Set.of("T-TIE\tEPOCH"), tie.childExcluded);
        // ... and without the Child entry the same read IS the defect
        String plain = """
                "Match_Datasets": [{"Name": "MH", "Keys": ["USUBJID"]}],
                """;
        assertFlags(rule("T-TIE-PLAIN", "[\"SUPPAE\"]", plain, suppKeys, null, null, mhKey,
                readEpoch, null), "T-TIE-PLAIN\tEPOCH\tMH");
    }


    @Test
    void anUnresolvedTargetAnEmptyScopeAndACaptureAreCountedNeverSkipped() throws IOException
    {
        String unknown = """
                "Match_Datasets": [{"Name": "ZZ", "Keys": ["USUBJID"]}],
                """;
        Census c = census(rule("T-UNRES", "[\"AE\"]", unknown, USUBJID, null, null,
                "[\"USUBJID\", \"ZZ.USUBJID\"]", "ZZVAL != AESTDTC", null));
        assertEquals(Map.of("synthetic\tZZ", 1), c.unresolved);
        assertEquals(Map.of(), c.flags);

        Census empty = census(rule("T-EMPTY", "[\"QQ\"]", KEYED_DM, USUBJID, null, null, DM_KEY,
                READ_RFSTDTC, null));
        assertEquals(Set.of("synthetic\tT-EMPTY"), empty.empty);
        assertEquals(Map.of(), empty.flags);

        Census capture = census(rule("T-CAP", "[\"AE\"]", KEYED_DM, USUBJID, null, null, DM_KEY,
                "TRTxxP != AESTDTC", null));
        assertEquals(Set.of("T-CAP\tTRTxxP"), capture.captures);
    }


    private static void assertFlags(Rule aRule, String... aExpected)
    {
        Census c = census(aRule);
        assertEquals(Set.of(aExpected), c.flags.keySet(),
                () -> "synthetic rule " + aRule.effectiveId() + ": " + c.flags);
        assertEquals(Set.of(), c.empty, "the synthetic scope must resolve");
    }


    private static Census census(Rule aRule)
    {
        Census c = new Census();
        c.examine("synthetic", "synthetic", SYNTHETIC, aRule);
        assertEquals(Set.of(), c.loadErrors, "a synthetic fixture must load cleanly");
        assertEquals(1, c.reach.values().stream().mapToInt(Integer::intValue).sum(),
                "the synthetic rule was not examined");
        return c;
    }


    /**
     * A synthetic joined rule loaded through {@link RulePackageLoader}, so its expressions are
     * compiled exactly as a shipped rule's are. Every facet is a JSON array literal or
     * {@code null}; {@code aAllOrNone} is the group list's content.
     */
    private static Rule rule(String aId, String aDomains, String aJoins, String aAll,
            @Nullable String aAny, @Nullable String aNone, @Nullable String aAllOrNone,
            String aCheck, @Nullable String aOutputs)
        throws IOException
    {
        String json = "{\"rules\": {\"r\": {\"Core\": {\"Id\": \"" + aId + "\"},"
                + "\"Scope\": {\"Domains\": {\"Include\": " + aDomains + "}},"
                + "\"Requirements\": {\"Variables\": {\"All\": " + aAll
                + (aAny == null ? "" : ", \"Any\": " + aAny)
                + (aNone == null ? "" : ", \"None\": " + aNone)
                + (aAllOrNone == null ? "" : ", \"All_Or_None\": [" + aAllOrNone + "]") + "}},"
                + aJoins + "\"Check\": {\"expression\": \"" + aCheck + "\"}"
                + (aOutputs == null ? ""
                        : ", \"Outcome\": {\"Message\": \"m\", \"Output_Variables\": " + aOutputs
                                + "}")
                + "}}}";
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        Rule rule = pkg.getRules().values().iterator().next();
        assertNull(rule.getLoadError(), () -> aId + " did not load: " + json);
        return rule;
    }

    /** A four-dataset SDTM-shaped library for the synthetic controls. */
    private static final Universe SYNTHETIC;
    static
    {
        List<LibraryDataset> datasets = List.of(
                LibraryDataset.of("AE", "EVENTS", List.of(), List.of(),
                        List.of("STUDYID", "USUBJID", "AESEQ", "AETERM", "AESTDTC", "AESMIE",
                                "EPOCH")),
                LibraryDataset.of("MH", "EVENTS", List.of(), List.of(),
                        List.of("STUDYID", "USUBJID", "MHTERM", "EPOCH")),
                LibraryDataset.of("DM", "SPECIAL PURPOSE", List.of(), List.of(),
                        List.of("STUDYID", "USUBJID", "RFSTDTC", "AGE")),
                LibraryDataset.of("SV", "SPECIAL PURPOSE", List.of(), List.of(),
                        List.of("STUDYID", "USUBJID", "VISIT", "VISITNUM")),
                LibraryDataset.of(SUPPQUAL, "RELATIONSHIP", List.of(), List.of(), List.of("STUDYID",
                        "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "QNAM", "QVAL")));
        Map<String, LibraryDataset> targets = new LinkedHashMap<>();
        datasets.forEach(d -> targets.put(d.name(), d));
        SYNTHETIC = new Universe("synthetic", "SDTM", datasets, targets);
    }

    // ------------------------------------------------------------------
    // Scope and declarations
    // ------------------------------------------------------------------

    /** S3: whether the rule's scope selects this library dataset / structure. */
    static boolean inScope(Rule aRule, LibraryDataset aDataset)
    {
        if (ScopeMatcher.describeClassMismatch(aRule, aDataset.datasetClass()) != null)
        {
            return false;
        }
        if (ScopeMatcher.describeDataStructureMismatch(aRule, aDataset.structureTokens()) != null
                || ScopeMatcher.describeSubclassMismatch(aRule, aDataset.subclasses()) != null)
        {
            return false;
        }
        for (String member : memberNames(aRule, aDataset))
        {
            if (ScopeMatcher.describeDomainMismatch(aRule, member, member) == null
                    && ScopeMatcher.describeDatasetMismatch(aRule, member) == null)
            {
                return true;
            }
        }
        return false;
    }


    /**
     * The member names a library dataset stands for: its own name, except {@code SUPPQUAL}, which
     * stands for every {@code SUPP--} / {@code SQ--} dataset a study may carry — so a rule scoped
     * to {@code SUPP--} or to a literal {@code SUPPAE} selects it (S3).
     */
    private static Set<String> memberNames(Rule aRule, LibraryDataset aDataset)
    {
        if (!SUPPQUAL.equals(aDataset.name()))
        {
            return Set.of(aDataset.name());
        }
        Set<String> names = new LinkedHashSet<>(List.of("SUPPXX", "SQXX"));
        if (aRule.getScope() != null && aRule.getScope().getDomains() != null
                && aRule.getScope().getDomains().getInclude() != null)
        {
            for (String entry : aRule.getScope().getDomains().getInclude())
            {
                String upper = entry.toUpperCase(Locale.ROOT);
                if ((upper.startsWith("SUPP") || upper.startsWith("SQ")) && !upper.contains("-")
                        && ScopeMatcher.scopePattern(entry) == null)
                {
                    names.add(upper);
                }
            }
        }
        return names;
    }


    private static boolean definedByAny(List<LibraryDataset> aDatasets, String aVariable)
    {
        for (LibraryDataset d : aDatasets)
        {
            if (d.defines(aVariable))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Declared bare in {@code All} or in any {@code Any} group (S6, D3): the facet's name, or
     * {@code null} when neither declares it.
     */
    private static @Nullable String bareDeclaration(@Nullable VariableRequirement aVars,
            String aVariable, List<LibraryDataset> aScope)
    {
        if (aVars == null)
        {
            return null;
        }
        if (anyBareMatch(aVars.getAll(), aVariable, aScope))
        {
            return "All";
        }
        List<List<String>> any = aVars.getAnyGroups();
        for (List<String> group : any == null ? List.<List<String>> of() : any)
        {
            if (anyBareMatch(group, aVariable, aScope))
            {
                return "Any";
            }
        }
        return null;
    }


    /** An {@code All_Or_None} group holding both {@code X} and {@code J.X} (S6). */
    private static boolean declaredAsPair(@Nullable VariableRequirement aVars, String aVariable,
            String aJoin, List<LibraryDataset> aScope)
    {
        List<List<String>> groups = aVars == null ? null : aVars.getAllOrNoneGroups();
        for (List<String> group : groups == null ? List.<List<String>> of() : groups)
        {
            boolean qualified = false;
            for (String entry : group)
            {
                ScopeVariableEntry parsed = ScopeVariableEntry.parse(entry);
                qualified |= parsed.isQualified()
                        && aJoin.equalsIgnoreCase(String.valueOf(parsed.qualifier()))
                        && aVariable.equals(parsed.variable());
            }
            if (qualified && anyBareMatch(group, aVariable, aScope))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Whether an unqualified entry of {@code aEntries} names {@code aVariable}: literally, through
     * a {@code --} prefix resolved against a scoped dataset ({@link OperationExecutor}'s variable
     * prefix — {@code ""} for SUPP/SQ), or as a pattern (glob, {@code /regex/}, ADaM template).
     */
    private static boolean anyBareMatch(@Nullable List<String> aEntries, String aVariable,
            List<LibraryDataset> aScope)
    {
        for (String entry : aEntries == null ? List.<String> of() : aEntries)
        {
            ScopeVariableEntry parsed = ScopeVariableEntry.parse(entry);
            if (parsed.isQualified())
            {
                continue;
            }
            String variable = parsed.variable();
            if (variable.equals(aVariable))
            {
                return true;
            }
            if (variable.startsWith("--"))
            {
                for (LibraryDataset d : aScope)
                {
                    String prefix = OperationExecutor.variableWildcardPrefix(null, d.name());
                    if ((prefix + variable.substring(2)).equals(aVariable))
                    {
                        return true;
                    }
                }
                continue;
            }
            Pattern pattern = ScopeMatcher.isPatternEntry(variable) ? patternOf(variable) : null;
            if (pattern != null && pattern.matcher(aVariable).matches())
            {
                return true;
            }
        }
        return false;
    }


    private static @Nullable Pattern patternOf(String aVariable)
    {
        Pattern glob = ScopeMatcher.scopePattern(aVariable);
        return glob != null ? glob : WildcardExpander.scopeVariableWildcardPattern(aVariable);
    }


    /** S2: a wildcard column read no static census can resolve (an ADaM template, a * root). */
    private static boolean isCaptureWildcard(String aName)
    {
        return !aName.contains("--") && !aName.contains(".") && !aName.startsWith("$")
                && !aName.isEmpty() && Character.isUpperCase(aName.charAt(0))
                && (aName.contains("*") || !aName.equals(aName.toUpperCase(Locale.ROOT)));
    }


    private static void collectCaptures(Expr aExpr, String aId, Set<String> aOut)
    {
        switch (aExpr)
        {
        case Expr.And a -> a.parts().forEach(p -> collectCaptures(p, aId, aOut));
        case Expr.Or o -> o.parts().forEach(p -> collectCaptures(p, aId, aOut));
        case Expr.Not n -> collectCaptures(n.inner(), aId, aOut);
        case Expr.Binary b ->
        {
            collectCaptures(b.left(), aId, aOut);
            collectCaptures(b.right(), aId, aOut);
        }
        case Expr.Call c ->
        {
            c.args().forEach(p -> collectCaptures(p, aId, aOut));
            c.kwargs().values().forEach(p -> collectCaptures(p, aId, aOut));
        }
        case Expr.Ref r ->
        {
            if (r.kind() == OperandKind.WILDCARD_COLUMN && isCaptureWildcard(r.name()))
            {
                aOut.add(aId + "\t" + r.name());
            }
        }
        case Expr.Lit l ->
        {
            if (l.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof Expr e)
                    {
                        collectCaptures(e, aId, aOut);
                    }
                }
            }
        }
        }
    }

    // ------------------------------------------------------------------
    // The library, from the project's metadata store
    // ------------------------------------------------------------------

    /** Builds (and caches) the {@link Universe} for a package's primary standards. */
    static final class Libraries
    {

        private final MetadataStore store;

        private final StoreMetadataProviderFactory factory;

        private final Map<String, Optional<Universe>> cache = new LinkedHashMap<>();

        private @Nullable Map<String, LibraryDataset> sdtmUnion;

        Libraries(MetadataStore aStore, StoreMetadataProviderFactory aFactory)
        {
            store = aStore;
            factory = aFactory;
        }


        @Nullable
        Universe universe(List<String> aPrimaries)
        {
            return cache.computeIfAbsent(String.join("+", aPrimaries), k -> build(aPrimaries))
                    .orElse(null);
        }


        private Optional<Universe> build(List<String> aPrimaries)
        {
            List<LibraryDataset> scope = new ArrayList<>();
            Map<String, LibraryDataset> targets = new LinkedHashMap<>();
            boolean adam = false;
            for (String primary : aPrimaries)
            {
                if (store.product("standards/" + primary).isEmpty())
                {
                    return Optional.empty();
                }
                if (primary.startsWith("adam/") || primary.endsWith("/adam"))
                {
                    adam = true;
                    List<LibraryDataset> own = adamStructures("standards/" + primary);
                    scope.addAll(own);
                    own.forEach(d -> targets.putIfAbsent(d.name().toUpperCase(Locale.ROOT), d));
                }
                else
                {
                    List<LibraryDataset> own = sdtmDatasets(primary);
                    scope.addAll(own);
                    own.forEach(d -> targets.putIfAbsent(d.name(), d));
                }
            }
            if (adam)
            {
                for (String key : store.productCatalogue())
                {
                    if (key.startsWith("standards/adam/")
                            && !key.startsWith("standards/adam/adamig-"))
                    {
                        scope.addAll(adamStructures(key));
                    }
                }
                sdtmUnion().forEach(targets::putIfAbsent);
            }
            String first = aPrimaries.get(0);
            String family = aPrimaries.size() > 1 ? "TIG"
                    : first.startsWith("adam/") ? "ADaM"
                            : first.startsWith("sendig/") ? "SEND" : "SDTM";
            return Optional.of(new Universe(String.join("+", aPrimaries), family, scope, targets));
        }


        /** S5: every SDTMIG product's datasets, unioned by name. */
        private Map<String, LibraryDataset> sdtmUnion()
        {
            if (sdtmUnion == null)
            {
                Map<String, Set<String>> names = new TreeMap<>();
                for (String key : store.productCatalogue())
                {
                    if (key.startsWith("standards/sdtmig/"))
                    {
                        for (LibraryDataset d : sdtmDatasets(key.substring("standards/".length())))
                        {
                            names.computeIfAbsent(d.name(), k -> new TreeSet<>())
                                    .addAll(d.variables());
                        }
                    }
                }
                Map<String, LibraryDataset> union = new LinkedHashMap<>();
                names.forEach((n, v) -> union.put(n,
                        LibraryDataset.of(n, null, List.of(), List.of(), v)));
                sdtmUnion = union;
            }
            return sdtmUnion;
        }


        /** S4: SDTM/SEND datasets through the store provider's {@code getDomainVariables}. */
        private List<LibraryDataset> sdtmDatasets(String aPrimary)
        {
            int slash = aPrimary.indexOf('/');
            MetadataProvider provider = factory
                    .forSdtm(aPrimary.substring(0, slash), aPrimary.substring(slash + 1), List.of())
                    .orElse(null);
            List<LibraryDataset> out = new ArrayList<>();
            List<String> names = provider == null ? null : provider.getStandardDatasetNames();
            if (provider == null || names == null)
            {
                return out;
            }
            for (String name : names)
            {
                List<String> vars = new ArrayList<>();
                for (Map<String, String> v : provider.getDomainVariables(name))
                {
                    if (v.get("name") != null)
                    {
                        vars.add(v.get("name"));
                    }
                }
                String cls = provider instanceof MetadataLibraryProvider library
                        ? library.getDatasetClass(name)
                        : null;
                out.add(LibraryDataset.of(name, cls, List.of(), List.of(), vars));
            }
            return out;
        }


        /** S4: ADaM data structures read off the store record, every core. */
        private List<LibraryDataset> adamStructures(String aKey)
        {
            List<LibraryDataset> out = new ArrayList<>();
            StoredProduct product = store.product(aKey).orElse(null);
            if (product == null)
            {
                return out;
            }
            for (StoredDataStructure s : product.dataStructures())
            {
                String name = String.valueOf(s.name());
                String token = STRUCTURE_CLASS_OVERRIDES.get(aKey + "\t" + name);
                if (token == null && s.className() != null)
                {
                    String upper = s.className().trim().toUpperCase(Locale.ROOT);
                    String canonical = ADAM_CLASS_ALIASES.getOrDefault(upper, upper);
                    token = AdamDataStructureDetector.STRUCTURE_TOKENS.contains(canonical)
                            ? canonical
                            : null;
                }
                List<String> vars = new ArrayList<>();
                for (StoredVariableSet set : s.variableSets())
                {
                    for (StoredVariable v : set.variables())
                    {
                        if (v.name() != null)
                        {
                            vars.add(v.name());
                        }
                    }
                }
                List<String> structures = token == null ? List.of()
                        : AdamDataStructureDetector.structureSet(token);
                List<String> subclasses = s.subClass() == null || s.subClass().isBlank() ? List.of()
                        : List.of(s.subClass().trim().toUpperCase(Locale.ROOT));
                out.add(LibraryDataset.of(name, token, structures, subclasses, vars));
            }
            return out;
        }
    }
}
