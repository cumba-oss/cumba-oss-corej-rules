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
import net.cumba.corej.core.exec.DatasetIdentity;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.ScopeMatcher;
import net.cumba.corej.core.exec.ScopeVariableEntry;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.BroadcastFold;
import net.cumba.corej.core.expr.typed.StageBChecker;
import net.cumba.corej.core.expr.typed.TypeExpectations;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.metadata.AdamDataStructureDetector;
import net.cumba.corej.core.metadata.AdamStructureClassification;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.CheckConditionNot;
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
 * <li>{@code J} is an entry whose target defines {@code X} (S5) — since W4 of
 * {@code PLAN-merged-evaluation-table} (MQ1) a {@code Child: true} entry's parent too, named by the
 * entry ({@code Name: AE} under a {@code SUPPAE} scope): its columns are qualified
 * ({@code AE.AESMIE}), never merged bare, so the D2 carve-out is gone. A {@code Child} entry that
 * names the child itself (a {@code --} template, or a dataset of the rule's scope — the per-row
 * pointer form of {@code CDISC-CG0371}) has no static parent: its parent reads are data-derived
 * ({@code colref(concat(RDOMAIN, ".", IDVAR))}) and it is counted in R-CHILD instead;</li>
 * <li>{@code X} is not declared: bare in {@code All}, bare in any {@code Any} group (D3), or an
 * {@code All_Or_None} group holding both {@code X} and {@code J.X}. {@code None} and a
 * {@code J.X}-only declaration do not count.</li>
 * </ul>
 *
 * <h2>Where the library comes from</h2> The project's own metadata store
 * ({@link MetadataStoreFixture}), never the pickle index: SDTM/SEND datasets through
 * {@code StoreMetadataProviderFactory.forSdtm(…).getDomainVariables} with the engine's own dataset
 * class ({@link MetadataLibraryProvider#getDatasetClass(String)}); ADaM data structures read off
 * the {@link StoredProduct} records (the provider serves only the Req core for ADaM, plan §2.5),
 * each classified by the ENGINE — structure token and subclass through
 * {@link AdamStructureClassification}, which calls {@code MetadataLibraryProvider}'s own
 * (package-private / private) mapping rather than copying its alias and override maps.
 *
 * <p>
 * An ADaM rule's scope is matched against its primary IG product's structures and, for a structure
 * token that IG product publishes <b>no</b> structure for, the ADaM supplement products' structures
 * of that token ({@code adam-occds}/{@code adam-adae} for OCCDS on an IG that has none, the
 * {@code adam-md} device tokens). A supplement structure of a token the IG does publish
 * ({@code adam-poppk}'s and {@code adam-nca}'s BDS, {@code adam-tte}'s {@code BDS for TTE}) is left
 * out: those carry copied ADSL variables ({@code AGE}, {@code SEX}, {@code TRTxxP} …), and letting
 * them define a BDS rule's scope would hide exactly the bare {@code AGE} meaning {@code ADSL.AGE}
 * this lint exists to find. ADaM joins to SDTM ({@code DM}, {@code AE}) resolve against the union
 * of every SDTMIG product in the store (S5).
 * </p>
 *
 * <h2>Nothing examined is dropped silently</h2> A (rule, package) whose scope selects no library
 * dataset is named in R-EMPTY; a join target no product defines is counted in R-UNRES; a read no
 * static census resolves — an ADaM capture template inside an expression such as {@code TRTxxP}, a
 * {@code *} root, an {@code Output_Variables} template no library dataset publishes or the rule's
 * own Check binds, and an {@code Expansion} token read bare ({@code &VAR&}, {@code &DOM&SEQ}) — is
 * named in R-CAPTURE (S2); a per-row pointer {@code Child: true} entry is named in R-CHILD; a
 * joined read the rule declares for the primary is named in R-DECLARED. The three rule registers
 * are keyed by (rule, name) with the number of packages each was seen in, and every one is pinned
 * for exact equality. So are the reach per family (joined (rule, package) pairs examined) and the
 * CANDIDATES per family — (rule, package, name) bare reads no scoped dataset defines, counted
 * before any join or declaration is consulted (S8): a family whose library answered nothing, or
 * whose scope stopped resolving, moves one of them even when the flags stay at zero. An
 * {@code Output_Variables} template the rule's Check does NOT itself read ({@code APxxSDT} beside a
 * Check over {@code APERIOD}) is judged by LITERAL equality against the template names the library
 * publishes — the engine reports such an entry only when a column of exactly that name exists,
 * which on the primary it never does. One the Check DOES read ({@code CDISC-AD0231}: Check and
 * {@code Output_Variables} both {@code TRTxxP}) is not: the rule is a template, and
 * {@code WildcardExpander.expandRule} renames every {@code Output_Variables} entry through the
 * Check's bound wildcards, so the entry names the concrete column the expansion read. It is counted
 * in R-CAPTURE under the same (rule, name) as the Check's read, never judged literally.
 *
 * <p>
 * ⭐ <b>Non-vacuity, twice</b> (S10): the predicate runs over synthetic rules of every exemption and
 * non-exemption on every build, and the live sabotage recorded in the plan's status file (a
 * {@code rules-src} rule edited to a bare joined read) turned the armed test red.
 * </p>
 */
class UnqualifiedJoinedReadLintTest
{

    private static final String SUPPQUAL = "SUPPQUAL";

    // Measured 2026-09-28 at cumba-corej-rules 0f3869f2 (PLAN-unqualified-joined-column-gate
    // phase 2c), re-measured in its review-fix round (lane GF) after CDISC-AD0592's
    // Output_Variables fix. EXACT — see the assertion messages for what may move each one.

    // SEND 142 -> 152 on 2026-09-30 (PLAN-send-tfdetect-pathology-unpark): CDISC-SEND-0204 and
    // CDISC-SEND-0205 were un-parked and join DS on USUBJID; each is examined in five of its six
    // packages (sendig 3.0 / 3.1 / 3.1.1, genetox-1-0, dart-1-2). Their bare reads are TF's own
    // (USUBJID, TFSPID, TFDETECT); the one DS read is qualified (DS.DSSTDTC). FLAGS stayed 0 and
    // CANDIDATES did not move. The sixth package, dart-1-1, selects no library dataset for TF and
    // is named in R-EMPTY; under dart-1-2 the DS join is counted in R-UNRES (DS 1 -> 3).
    private static final Map<String, Integer> EXPECTED_REACH = Map.of("ADaM", 187, "SDTM", 421,
            "SEND", 152);

    // SEND 6 -> 9 -> 6 on 2026-09-30 (PLAN-rprfdy-offset-tp-join). Phase 4 gave CDISC-SEND-0401/
    // 0402/0403 read_value(RPRFDY, domain="TP", group=[DM.RPATHCD, RPHASE], mode="MAX"), and the
    // engine's TypeExpectations walk recorded the call's TARGET, RPRFDY, as a bare read of the
    // primary (probed: undefined=[RPRFDY] per rule, in the one dart package whose scope resolves).
    // The defect was the engine's, not this collector's: TypeExpectations now reads a domain=-
    // pinned call's target, filter= and domain= as the PINNED dataset's (W2a D10 / D13 Q4 /
    // D-W7-16; a plain group= member stays a primary read), so the three rows left (review round
    // 1, lane 3 M1 — cumba-corej 4c5743a). Probed after: the six SEND rows are FDA-SD0077
    // [IDVARVAL, IDVAR, RDOMAIN], FDA-SD1012 [ETCD], FDA-SD1023 [VISIT, VISITNUM] (sendig/ar-1-0);
    // ADaM and SDTM did not move. FLAGS stayed 0 and every register below is unchanged.
    // SDTM 21 -> 14 on 2026-10-05 (PLAN-merged-evaluation-table W4, MQ1): the seven bare AESMIE
    // reads of CDISC-CG0043 (3 packages) and FDA-SD1143 (4) are respelled AE.AESMIE, a qualified
    // read no bare census counts. FLAGS stayed 0.
    private static final Map<String, Integer> EXPECTED_CANDIDATES = Map.of("ADaM", 5, "SDTM", 14,
            "SEND", 6);

    private static final Map<String, Integer> EXPECTED_UNRESOLVED = Map.ofEntries(
            Map.entry("adam/adamig-1-0\t&DOM&", 1), Map.entry("adam/adamig-1-1\t&DOM&", 1),
            Map.entry("adam/adamig-1-2\t&DOM&", 1), Map.entry("adam/adamig-1-3\t&DOM&", 1),
            Map.entry("sendig/ar-1-0\tRELREC (no variables)", 1),
            Map.entry("sendig/ar-1-0\tSV (no variables)", 1),
            Map.entry("sendig/ar-1-0\tTA (no variables)", 1),
            Map.entry("sendig/ar-1-0\tTE (no variables)", 1),
            // sendig/dart-1-2 DM 2 -> 5 and SJ 3 -> gone on 2026-09-30 (PLAN-rprfdy-offset-tp-join
            // phase 4, D-SJ): CDISC-SEND-0401/0402/0403 replaced their SJ Match_Datasets entry by a
            // DM entry (Keys [USUBJID]). The store has no SENDIG-DART 1.2 product, so under that
            // package the lint cannot judge those three joins (as it could not judge the three SJ
            // joins before) — they are examined under dart-1-1, where FLAGS stayed 0.
            // DS 1 -> 3 on 2026-09-30 (PLAN-send-tfdetect-pathology-unpark): the un-parked
            // CDISC-SEND-0204 / 0205 join DS, and under this package the join cannot be judged.
            Map.entry("sendig/dart-1-2\tDM", 5), Map.entry("sendig/dart-1-2\tDS", 3),
            Map.entry("sendig/dart-1-2\tRELREC", 2));

    // + the two dart-1-1 rows on 2026-09-30 (PLAN-send-tfdetect-pathology-unpark): the un-parked
    // CDISC-SEND-0204 / 0205 are scoped to TF, which the SENDIG-DART 1.1 library does not define.
    private static final Set<String> EXPECTED_EMPTY = Set.of(
            "rules-cdisc-sendig-dart-1-1.json\tCDISC-SEND-0204",
            "rules-cdisc-sendig-dart-1-1.json\tCDISC-SEND-0205",
            "rules-fda-sendig-ar-1-0.json\tFDA-SD1205", "rules-fda-sendig-ar-1-0.json\tFDA-SD1206",
            "rules-fda-sendig-ar-1-0.json\tFDA-SD1207", "rules-fda-sendig-ar-1-0.json\tFDA-SD9747",
            "rules-fda-sendig-ar-1-0.json\tFDA-SE1152");

    private static final Map<String, Integer> EXPECTED_CAPTURES = Map.of("CDISC-AD0591\t&VAR&", 2,
            "CDISC-AD0898\t&DOM&SEQ", 4);

    // W4 of PLAN-merged-evaluation-table: the bare AESMIE reads (CG0043 x3, SD1143 x4) were
    // respelled AE.AESMIE (MQ1) and the carve-out that excluded them is gone; R-CHILD now counts
    // the per-row pointer Child entries, whose parent reads no static census can see.
    private static final Map<String, Integer> EXPECTED_CHILD = Map.of("CDISC-CG0371", 3,
            "FDA-SD0077", 10, "PMDA-SD0077", 5);

    private static final Map<String, Integer> EXPECTED_DECLARED = Map.of(
            "CDISC-AD0899\tRFSTDTC\tDM\tAll", 4, "CDISC-CG0217\tTAETORD\tSE\tAll", 3,
            "CDISC-CG0218\tEPOCH\tSE\tAll", 3, "PMDA-AD0367\tACTARM\tDM\tAll", 1);

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
     * @param templateNames
     *            the ADaM wildcard variable templates it defines, as published ({@code TRTxxP} …)
     * @param templates
     *            the same templates, compiled
     */
    record LibraryDataset(String name, @Nullable String datasetClass, List<String> structureTokens,
            List<String> subclasses, Set<String> variables, Set<String> templateNames,
            List<Pattern> templates)
    {

        /** The store publishes the dataset but no variable of it. */
        boolean isEmpty()
        {
            return variables.isEmpty() && templates.isEmpty();
        }


        /** A concrete name the dataset defines, or one one of its templates matches. */
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


        /**
         * Whether the dataset publishes exactly this name — a concrete variable or, verbatim, a
         * template ({@code APxxSDT}). How an {@code Output_Variables} template the rule's Check
         * does not itself read is judged: the engine reports it only for a column of exactly that
         * name, so a template that merely MATCHES it ({@code *SDT} matches {@code APxxSDT}) says
         * nothing about it. (One the Check reads is renamed by the expansion and never reaches here
         * — see the class javadoc.)
         */
        boolean publishes(String aName)
        {
            return variables.contains(aName) || templateNames.contains(aName);
        }


        static LibraryDataset of(String aName, @Nullable String aClass, List<String> aStructures,
                List<String> aSubclasses, Iterable<String> aNames)
        {
            Set<String> concrete = new TreeSet<>();
            Set<String> templateNames = new TreeSet<>();
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
                    templateNames.add(n);
                    templates.add(template);
                }
            }
            return new LibraryDataset(aName, aClass, aStructures, aSubclasses, concrete,
                    templateNames, templates);
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

        /**
         * family → CANDIDATES: (rule, package, name) bare reads no scoped dataset defines, counted
         * before any join, {@code Child} or declaration test — the population the predicate is then
         * asked about.
         */
        final Map<String, Integer> candidates = new TreeMap<>();

        /** {@code ruleId \t X \t J} → the packages the flag was raised in. */
        final Map<String, Set<String>> flags = new TreeMap<>();

        /** R-EMPTY: {@code package \t ruleId} whose scope selects no library dataset. */
        final Set<String> empty = new TreeSet<>();

        /** R-UNRES: {@code standard \t target} → (rule, package) references. */
        final Map<String, Integer> unresolved = new TreeMap<>();

        /**
         * R-CAPTURE: {@code ruleId \t name} of a read no static census resolves → the packages it
         * was seen in.
         */
        final Map<String, Set<String>> captures = new TreeMap<>();

        /**
         * R-CHILD: {@code ruleId} whose {@code Child: true} entry is a per-row pointer (it names
         * the child itself, so its parent is the data's {@code RDOMAIN}) → packages.
         */
        final Map<String, Set<String>> childPointer = new TreeMap<>();

        /**
         * R-DECLARED: {@code ruleId \t X \t J \t how} a join carries, no scoped dataset defines,
         * and the rule declares → packages. The exempted half of the population, whose size is what
         * makes a zero flag count mean something.
         */
        final Map<String, Set<String>> declared = new TreeMap<>();

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
                    if (md != null)
                    {
                        unresolved.merge(aStandard + "\t" + md.getName(), 1, Integer::sum);
                    }
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
            for (MatchDataset md : joins)
            {
                if (md == null)
                {
                    continue;
                }
                if (Boolean.TRUE.equals(md.getChild()) && namesTheChild(aRule, md))
                {
                    // A per-row pointer: the parent is the data's RDOMAIN (MQ1), read through
                    // colref(concat(RDOMAIN, ".", IDVAR)) — no static target. Counted, never
                    // skipped unseen.
                    childPointer.computeIfAbsent(id, k -> new TreeSet<>()).add(aPackage);
                    continue;
                }
                String name = md.getName() == null ? "" : md.getName().toUpperCase(Locale.ROOT);
                LibraryDataset target = aUniverse.joinTargets().get(name);
                if (target == null || target.isEmpty())
                {
                    // A target the store publishes with NO variables (sendig/ar-1-0's companion
                    // TA, SV, …) judges nothing, exactly like one it does not publish: counted.
                    unresolved.merge(aUniverse.standard() + "\t" + md.getName()
                            + (target == null ? "" : " (no variables)"), 1, Integer::sum);
                    continue;
                }
                targets.putIfAbsent(name, target);
            }
            List<Expr> roots = expressions(aRule);
            Set<String> reads = new LinkedHashSet<>(TypeExpectations.of(roots).valueReadColumns());
            Set<String> literal = new TreeSet<>();
            // The templates the engine binds per expansion — the key set of expandRule's name map
            // (the Check's, every level, and its compiled bindings').
            Set<String> bound = WildcardExpander.collectWildcardNames(aRule);
            List<String> outputs = aRule.getOutcome() == null ? null
                    : aRule.getOutcome().getOutputVariables();
            for (String output : outputs == null ? List.<String> of() : outputs)
            {
                if (BroadcastFold.isFoldableColumnReference(output))
                {
                    reads.add(output);
                }
                else if (isExpansionToken(output))
                {
                    captures.computeIfAbsent(id + "\t" + output, k -> new TreeSet<>())
                            .add(aPackage);
                }
                else if (isCaptureWildcard(output))
                {
                    // A template the Check binds is renamed with the expansion (expandRule's
                    // nameMap), so it names the column the Check read: counted, never judged —
                    // and counted rather than skipped, so the entry stays SEEN in R-CAPTURE.
                    // Only an Output_Variables template the Check does NOT read is reported
                    // solely for a column of exactly that name — so that one is judged by literal
                    // equality against the template names the library publishes, and counted
                    // when none publishes it.
                    if (bound.contains(output))
                    {
                        captures.computeIfAbsent(id + "\t" + output, k -> new TreeSet<>())
                                .add(aPackage);
                    }
                    else if (publishedAnywhere(aUniverse, output))
                    {
                        reads.add(output);
                        literal.add(output);
                    }
                    else
                    {
                        captures.computeIfAbsent(id + "\t" + output, k -> new TreeSet<>())
                                .add(aPackage);
                    }
                }
            }
            Set<String> captured = new TreeSet<>();
            for (Expr root : roots)
            {
                collectCaptures(root, captured);
            }
            captured.forEach(c -> captures.computeIfAbsent(id + "\t" + c, k -> new TreeSet<>())
                    .add(aPackage));
            if (scope.isEmpty())
            {
                empty.add(aPackage + "\t" + id);
                return;
            }
            List<String> undefined = new ArrayList<>();
            for (String x : reads)
            {
                if (!definedByAny(scope, x, literal.contains(x)))
                {
                    undefined.add(x);
                }
            }
            candidates.merge(aUniverse.family(), undefined.size(), Integer::sum);
            if (targets.isEmpty())
            {
                return;
            }
            VariableRequirement vars = aRule.effectiveVariableRequirement();
            for (String x : undefined)
            {
                String bare = bareDeclaration(vars, x, scope);
                boolean exact = literal.contains(x);
                for (Map.Entry<String, LibraryDataset> target : targets.entrySet())
                {
                    LibraryDataset joined = target.getValue();
                    if (!(exact ? joined.publishes(x) : joined.defines(x)))
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
                        declared.computeIfAbsent(key + "\t" + declaration, k -> new TreeSet<>())
                                .add(aPackage);
                    }
                }
            }
        }


        void print(PrintStream aOut)
        {
            aOut.println(
                    "[UnqualifiedJoinedReadLint] reach (joined rule x package examined): " + reach);
            aOut.println("[UnqualifiedJoinedReadLint] candidates (rule x package x bare read no"
                    + " scoped dataset defines): " + candidates);
            aOut.println("[UnqualifiedJoinedReadLint] FLAGS: " + flags.size());
            flags.forEach((k, v) -> aOut.println("  FLAG\t" + k + "\t" + v));
            aOut.println("[UnqualifiedJoinedReadLint] R-EMPTY: " + empty.size());
            empty.forEach(e -> aOut.println("  EMPTY\t" + e));
            aOut.println("[UnqualifiedJoinedReadLint] R-UNRES: " + unresolved.size());
            unresolved.forEach((k, v) -> aOut.println("  UNRES\t" + k + "\t" + v));
            aOut.println("[UnqualifiedJoinedReadLint] R-CAPTURE: " + captures.size());
            captures.forEach((k, v) -> aOut.println("  CAPTURE\t" + k + "\t" + v));
            aOut.println("[UnqualifiedJoinedReadLint] R-DECLARED: " + declared.size());
            declared.forEach((k, v) -> aOut.println("  DECLARED\t" + k + "\t" + v));
            aOut.println("[UnqualifiedJoinedReadLint] R-CHILD: " + childPointer.size());
            childPointer.forEach((k, v) -> aOut.println("  CHILD\t" + k + "\t" + v));
            aOut.println("[UnqualifiedJoinedReadLint] load errors: " + loadErrors.size());
            loadErrors.forEach(c -> aOut.println("  LOADERR\t" + c));
        }


        /** A register keyed by (rule, name …) → the number of packages each entry was seen in. */
        static Map<String, Integer> packageCounts(Map<String, Set<String>> aRegister)
        {
            Map<String, Integer> counts = new TreeMap<>();
            aRegister.forEach((k, v) -> counts.put(k, v.size()));
            return counts;
        }
    }

    /**
     * The expressions a rule evaluates: the engine's own roots
     * ({@link StageBChecker#expressionRoots}) and, for a Check with no compiled form — a template
     * the engine expands per dataset (S2) — its parsed expression, level by level.
     */
    static List<Expr> expressions(Rule aRule)
    {
        List<Expr> roots = new ArrayList<>(StageBChecker.expressionRoots(aRule));
        if (aRule.getCheckExpr() == null && aRule.getCheckLevelExprs() == null)
        {
            for (CheckCondition condition : aRule.checkConditions())
            {
                collectExpressions(condition, roots);
            }
        }
        return roots;
    }


    private static void collectExpressions(@Nullable CheckCondition aCondition, List<Expr> aOut)
    {
        switch (aCondition)
        {
        case null ->
        {
            // an absent sub-condition names nothing
        }
        case CheckConditionExpression e -> aOut.add(e.expr());
        case CheckConditionAll all -> all.getConditions().forEach(c -> collectExpressions(c, aOut));
        case CheckConditionAny any -> any.getConditions().forEach(c -> collectExpressions(c, aOut));
        case CheckConditionNot not -> collectExpressions(not.getCondition(), aOut);
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
                + " silently. Qualify the read as J.X; or, when the primary really is meant,"
                + " declare X in Requirements.Variables — bare in All, bare in an Any group, or"
                + " the pair [X, J.X] in an All_Or_None group (rule guide, authoring/joins.md,"
                + " \"A bare name only the join carries fails the build\")."
                + " ruleId / X / J -> packages:\n  "
                + String.join("\n  ", census.flags.entrySet().stream()
                        .map(e -> e.getKey().replace('\t', ' ') + " " + e.getValue()).toList()));
        // ⛔ The ratchets below are EXACT (S8): a census that stopped seeing a family, a package or
        // a rule would still report zero flags, so the reach and every register are pinned. Moving
        // a pin is legitimate only with the reason in the same commit.
        assertEquals(EXPECTED_REACH, census.reach, "joined (rule, package) pairs examined per"
                + " family — a new joined rule moves this (confirm it reads no bare joined column,"
                + " then move the pin); a family that DROPPED means the library or the corpus went"
                + " short and the zero above is no longer evidence");
        assertEquals(EXPECTED_CANDIDATES, census.candidates, "CANDIDATES per family: (rule,"
                + " package, bare read) no scoped dataset defines, before any join or declaration"
                + " test. A family whose library answered nothing, or whose scope stopped"
                + " resolving, moves this even while the flags stay at zero");
        assertEquals(EXPECTED_UNRESOLVED, census.unresolved, "R-UNRES: join targets no store"
                + " product defines, (standard, target) -> references. None of these joins is"
                + " examined; a new entry is a join the lint cannot judge — say so in the commit");
        assertEquals(EXPECTED_EMPTY, census.empty, "R-EMPTY: (package, rule) whose scope selects"
                + " no library dataset, so none of its reads is examined");
        assertEquals(EXPECTED_CAPTURES, Census.packageCounts(census.captures), "R-CAPTURE: reads"
                + " no static census resolves (an ADaM capture template, a * root, an Output_Variables"
                + " template no library publishes or the Check binds, an Expansion token; S2),"
                + " (rule, name) -> packages");
        assertEquals(EXPECTED_CHILD, Census.packageCounts(census.childPointer), "R-CHILD: the"
                + " per-row pointer Child: true entries, whose parent reads are data-derived and"
                + " judged by no static census — counted so they stay SEEN; rule -> packages");
        assertEquals(EXPECTED_DECLARED, Census.packageCounts(census.declared), "R-DECLARED: bare"
                + " reads a join carries that the rule declares for the primary — the exempted"
                + " population; (rule, name, join, how) -> packages");
    }


    @Test
    void adamStructuresAreClassifiedByTheEngineOverridesIncluded() throws IOException
    {
        // The lint reads token and subclass through the engine (AdamStructureClassification), not
        // a copy of its maps: the two structures whose published record is incomplete must come
        // back with the engine's override, or a scope over them is judged on the wrong class.
        Path storeFile = MetadataStoreFixture.storeFile().orElseThrow(
                () -> new AssertionError("No metadata store: set CDISC_PICKLE_CACHE_DIR"));
        Map<String, String> seen = new TreeMap<>();
        try (MetadataStore store = MetadataStore.open(storeFile))
        {
            for (String key : List.of("standards/adam/adam-adae-1-0",
                    "standards/adam/adam-tte-1-0"))
            {
                for (StoredDataStructure s : store.product(key).orElseThrow().dataStructures())
                {
                    seen.put(key + "\t" + s.name(),
                            AdamStructureClassification.structureToken(key, s) + " / "
                                    + AdamStructureClassification.subClass(key, s));
                }
            }
        }
        assertEquals(Map.of("standards/adam/adam-adae-1-0\tADAE",
                AdamDataStructureDetector.OCCDS + " / ADVERSE EVENT",
                "standards/adam/adam-tte-1-0\tBDS for TTE",
                AdamDataStructureDetector.BDS + " / TIME-TO-EVENT"), seen);
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

    private static final String READ_AESTDTC_EMPTY = "AESTDTC == \\\"\\\"";

    @Test
    void thePredicateFlagsAnUndeclaredBareJoinedReadAndHonoursEveryExemption() throws IOException
    {
        // the positive: RFSTDTC is in no scoped dataset (AE), the DM join carries it, undeclared
        assertFlags(rule("T-POS", "[\"AE\"]", KEYED_DM, USUBJID, null, null, DM_KEY, READ_RFSTDTC,
                null), "T-POS\tRFSTDTC\tDM");
        // Output_Variables is the same mistake where a user sees it
        assertFlags(rule("T-OV", "[\"AE\"]", KEYED_DM, USUBJID, null, null, DM_KEY,
                READ_AESTDTC_EMPTY, "[\"AESTDTC\", \"RFSTDTC\"]"), "T-OV\tRFSTDTC\tDM");
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
        // "J defines X": a read defined NOWHERE — not in scope, not by the join — is no flag
        // (a sponsor variable); only a column the join carries is
        assertFlags(rule("T-SPONSOR", "[\"AE\"]", KEYED_DM, USUBJID, null, null, DM_KEY,
                "SPONSVAR != AESTDTC", null));
        // a pair naming a DIFFERENT variable of the same join does not declare RFSTDTC
        assertFlags(
                rule("T-PAIR-VAR", "[\"AE\"]", KEYED_DM, USUBJID, null, null,
                        DM_KEY + ", [\"RFSTDTC\", \"DM.USUBJID\"]", READ_RFSTDTC, null),
                "T-PAIR-VAR\tRFSTDTC\tDM");
    }


    @Test
    void aDashDashOrTemplateDeclarationDeclaresExactlyWhatItExpandsTo() throws IOException
    {
        // `--STDTC` on AE expands to AESTDTC — not to RFSTDTC, though RFSTDTC ends in STDTC
        assertFlags(rule("T-DASH", "[\"AE\"]", KEYED_DM, "[\"USUBJID\", \"--STDTC\"]", null, null,
                DM_KEY, READ_RFSTDTC, null), "T-DASH\tRFSTDTC\tDM");
        // ... and where the expansion IS the read, it declares it: on a SUPP-- dataset the
        // variable prefix is "" (DatasetIdentity.variableWildcardPrefix), so `--RFSTDTC` is
        // RFSTDTC
        String suppKeys = "[\"QNAM\", \"USUBJID\", \"--RFSTDTC\"]";
        Census dash = census(rule("T-DASH-SUPP", "[\"SUPPAE\"]", KEYED_DM, suppKeys, null, null,
                DM_KEY, "QNAM == \\\"AESOSP\\\" and RFSTDTC != \\\"\\\"", null));
        assertEquals(Map.of(), dash.flags);
        assertEquals(Set.of("T-DASH-SUPP\tRFSTDTC\tDM\tAll"), dash.declared.keySet());

        // an ADaM template (WildcardExpander.scopeVariableWildcardPattern) declares every
        // concrete name it matches — TRTxxP declares TRT01P — and nothing else
        String keyedAdsl = """
                "Match_Datasets": [{"Name": "ADSL", "Keys": ["USUBJID"]}],
                """;
        String adslKey = "[\"USUBJID\", \"ADSL.USUBJID\"]";
        assertFlags(rule("T-TPL-UNDECLARED", "[\"AE\"]", keyedAdsl, USUBJID, null, null, adslKey,
                "TRT01P != AESTDTC", null), "T-TPL-UNDECLARED\tTRT01P\tADSL");
        Census template = census(rule("T-TPL", "[\"AE\"]", keyedAdsl, "[\"USUBJID\", \"TRTxxP\"]",
                null, null, adslKey, "TRT01P != AESTDTC", null));
        assertEquals(Map.of(), template.flags);
        assertEquals(Set.of("T-TPL\tTRT01P\tADSL\tAll"), template.declared.keySet());
        assertFlags(rule("T-TPL-OTHER", "[\"AE\"]", keyedAdsl, "[\"USUBJID\", \"TRTxxA\"]", null,
                null, adslKey, "TRT01P != AESTDTC", null), "T-TPL-OTHER\tTRT01P\tADSL");
        // an Output_Variables template the join publishes LITERALLY (the CDISC-AD0592 shape) is
        // the same defect where a user sees it: the engine reports no column of that name
        assertFlags(rule("T-OV-TPL", "[\"AE\"]", keyedAdsl, USUBJID, null, null, adslKey,
                READ_AESTDTC_EMPTY, "[\"AESTDTC\", \"TRTxxP\"]"), "T-OV-TPL\tTRTxxP\tADSL");
        // ... and the scope side is judged literally too: XP's template *P MATCHES the string
        // TRTxxP but does not publish it, so it does not define the entry, and the ADSL join's
        // verbatim TRTxxP is still the defect
        assertFlags(
                rule("T-OV-TPL-SCOPE", "[\"XP\"]", keyedAdsl, USUBJID, null, null, adslKey,
                        "USUBJID == \\\"\\\"", "[\"TRTxxP\"]"),
                TEMPLATE_SCOPE, "T-OV-TPL-SCOPE\tTRTxxP\tADSL");
        // ... but an Output_Variables template the rule's own Check reads (the CDISC-AD0231 shape:
        // Check and Output_Variables both TRTxxP) is renamed through the Check's bound wildcards
        // (WildcardExpander.expandRule), so it names the column the Check read: counted with the
        // Check's read, never judged literally — no flag, though ADSL publishes TRTxxP verbatim
        Census bound = census(rule("T-OV-BOUND", "[\"AE\"]", keyedAdsl, USUBJID, null, null,
                adslKey, "TRTxxP != AESTDTC", "[\"AESTDTC\", \"TRTxxP\"]"));
        assertEquals(Map.of(), bound.flags);
        assertEquals(Set.of("T-OV-BOUND\tTRTxxP"), bound.captures.keySet());
    }


    @Test
    void aChildParentsBareReadIsFlaggedSinceItsColumnsAreQualified() throws IOException
    {
        // W4 (MQ1): the parent AE's columns are AE.<column> — a bare AESMIE on SUPPAE reads the
        // primary's absent column, silently, so it is the defect (the D2 carve-out excluded it).
        String suppKeys = "[\"QNAM\", \"USUBJID\", \"IDVAR\", \"IDVARVAL\"]";
        String child = """
                "Match_Datasets": [{"Name": "AE", "Child": true,
                    "Keys": ["USUBJID", "IDVAR", "IDVARVAL"]}],
                """;
        String readAesmie = "QNAM == \\\"AESOSP\\\" and AESMIE != \\\"Y\\\"";
        assertFlags(rule("T-CHILD", "[\"SUPPAE\"]", child, suppKeys, null, null, null, readAesmie,
                null), "T-CHILD\tAESMIE\tAE");
        // ... and the respelled read is clean
        Census respelled = census(rule("T-CHILD-Q", "[\"SUPPAE\"]", child, suppKeys, null, null,
                null, "QNAM == \\\"AESOSP\\\" and AE.AESMIE != \\\"Y\\\"", null));
        assertEquals(Map.of(), respelled.flags);
        assertEquals(Map.of(), respelled.childPointer);
        // a per-row pointer entry (it names the child itself) has no static parent: counted in
        // R-CHILD, never judged — even for a read only a library parent carries
        String pointer = """
                "Match_Datasets": [{"Name": "SUPP--", "Child": true,
                    "Keys": ["USUBJID", "IDVAR", "IDVARVAL"]}],
                """;
        Census perRow = census(
                rule("T-CHILD-ROW", "[\"SUPP--\"]", pointer,
                        "[\"USUBJID\", \"IDVAR\", \"IDVARVAL\"]", null, null, null,
                        "str(IDVARVAL) != str(colref(concat(RDOMAIN, \\\".\\\", IDVAR)))", null),
                CHILD_SPLIT);
        assertEquals(Map.of(), perRow.flags);
        assertEquals(Set.of("T-CHILD-ROW"), perRow.childPointer.keySet());
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
        assertEquals(Set.of("T-CAP\tTRTxxP"), capture.captures.keySet());

        // an Output_Variables template no library dataset publishes is counted, not judged ...
        Census unpublished = census(rule("T-OVCAP", "[\"AE\"]", KEYED_DM, USUBJID, null, null,
                DM_KEY, READ_AESTDTC_EMPTY, "[\"AESTDTC\", \"QQxxZ\"]"));
        assertEquals(Set.of("T-OVCAP\tQQxxZ"), unpublished.captures.keySet());
        assertEquals(Map.of(), unpublished.flags);
        // ... and an Expansion token read bare is counted wherever it is read, never dropped:
        // &DOM&SEQ only in the Check, &VAR& only in Output_Variables, so each path has its own
        // entry. Both tokens are declared: an undeclared `&X&` is a G2 load error since
        // PLAN-expansion-token-delimiters, and the loader is what this fixture goes through.
        Census expansion = census(rule("T-EXP", "[\"AE\"]", KEYED_DM, USUBJID, null, null, DM_KEY,
                "not empty(&DOM&SEQ) and &DOM&SEQ != DM.&DOM&SEQ", "[\"&VAR&\", \"DM.&VAR&\"]",
                "[{\"token\": \"&DOM&\", \"over\": \"domain_from_variable\","
                        + " \"pattern\": \"&DOM&SEQ\"},"
                        + " {\"token\": \"&VAR&\", \"over\": \"shared_variables\", \"with\": \"DM\"}]"));
        assertEquals(Set.of("T-EXP\t&DOM&SEQ", "T-EXP\t&VAR&"), expansion.captures.keySet());
        assertEquals(Set.of("synthetic"), expansion.captures.get("T-EXP\t&VAR&"));
    }


    private static void assertFlags(Rule aRule, String... aExpected)
    {
        assertFlags(aRule, SYNTHETIC, aExpected);
    }


    private static void assertFlags(Rule aRule, Universe aUniverse, String... aExpected)
    {
        Census c = census(aRule, aUniverse);
        assertEquals(Set.of(aExpected), c.flags.keySet(),
                () -> "synthetic rule " + aRule.effectiveId() + ": " + c.flags);
        assertEquals(Set.of(), c.empty, "the synthetic scope must resolve");
    }


    private static Census census(Rule aRule)
    {
        return census(aRule, SYNTHETIC);
    }


    private static Census census(Rule aRule, Universe aUniverse)
    {
        Census c = new Census();
        c.examine("synthetic", "synthetic", aUniverse, aRule);
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
        return rule(aId, aDomains, aJoins, aAll, aAny, aNone, aAllOrNone, aCheck, aOutputs, null);
    }


    /** As above, with an {@code Expansion:} block (a JSON array) for the template arms. */
    private static Rule rule(String aId, String aDomains, String aJoins, String aAll,
            @Nullable String aAny, @Nullable String aNone, @Nullable String aAllOrNone,
            String aCheck, @Nullable String aOutputs, @Nullable String aExpansion)
        throws IOException
    {
        String json = "{\"rules\": {\"r\": {\"Core\": {\"Id\": \"" + aId + "\"},"
                + (aExpansion == null ? "" : "\"Expansion\": " + aExpansion + ",")
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

    /**
     * An SDTM-shaped library for the synthetic controls, plus an {@code ADSL} publishing one ADaM
     * template ({@code TRTxxP}) for the template arms.
     */
    private static final Universe SYNTHETIC;

    /**
     * Scope candidates and join targets that DIFFER, as an ADaM package's do (the SDTM union is a
     * join target only): {@code MH} is a scope candidate only, {@code DM} a join target only.
     */
    private static final Universe CHILD_SPLIT;

    /** A scoped {@code XP} whose template {@code *P} matches the string {@code TRTxxP}. */
    private static final Universe TEMPLATE_SCOPE;

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
                LibraryDataset.of(SUPPQUAL, "RELATIONSHIP", List.of(), List.of(),
                        List.of("STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "QNAM",
                                "QVAL")),
                LibraryDataset.of("ADSL", null, List.of(), List.of(),
                        List.of("STUDYID", "USUBJID", "TRTxxP")));
        SYNTHETIC = universe(datasets, datasets);
        Map<String, LibraryDataset> byName = SYNTHETIC.joinTargets();
        CHILD_SPLIT = universe(List.of(byName.get(SUPPQUAL), byName.get("AE"), byName.get("MH")),
                List.of(byName.get("AE"), byName.get("DM")));
        TEMPLATE_SCOPE = universe(List.of(LibraryDataset.of("XP", "FINDINGS", List.of(), List.of(),
                List.of("STUDYID", "USUBJID", "*P"))), List.of(byName.get("ADSL")));
    }

    private static Universe universe(List<LibraryDataset> aScope, List<LibraryDataset> aTargets)
    {
        Map<String, LibraryDataset> targets = new LinkedHashMap<>();
        aTargets.forEach(d -> targets.put(d.name(), d));
        return new Universe("synthetic", "SDTM", aScope, targets);
    }

    // ------------------------------------------------------------------
    // Scope and declarations
    // ------------------------------------------------------------------


    /**
     * Whether a {@code Child: true} entry names the child itself — a {@code --} template
     * ({@code SUPP--}) or a dataset the rule's scope includes ({@code CO}, {@code RELREC}) — rather
     * than its parent ({@code Name: AE} under a {@code SUPPAE} scope).
     */
    static boolean namesTheChild(Rule aRule, MatchDataset aEntry)
    {
        String name = aEntry.getName();
        if (name == null || name.contains("--"))
        {
            return true;
        }
        List<String> include = aRule.getScope() == null || aRule.getScope().getDomains() == null
                ? null
                : aRule.getScope().getDomains().getInclude();
        return include != null && include.stream().anyMatch(name::equalsIgnoreCase);
    }


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


    /**
     * Whether any of {@code aDatasets} defines {@code aVariable} — by
     * {@link LibraryDataset#publishes} when {@code aExact} (an {@code Output_Variables} template),
     * else by {@link LibraryDataset#defines}.
     */
    private static boolean definedByAny(List<LibraryDataset> aDatasets, String aVariable,
            boolean aExact)
    {
        for (LibraryDataset d : aDatasets)
        {
            if (aExact ? d.publishes(aVariable) : d.defines(aVariable))
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
                    String prefix = DatasetIdentity.variableWildcardPrefix(null, d.name());
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


    /**
     * A bare {@code Expansion} token read ({@code &VAR&}, {@code &DOM&SEQ}): the engine substitutes
     * it per expansion, so no static census resolves it (counted in R-CAPTURE). A qualified
     * {@code ADSL.&VAR&} is not a bare read and is not counted.
     */
    private static boolean isExpansionToken(String aName)
    {
        return aName.startsWith("&") && aName.indexOf('.') < 0;
    }


    /** Whether any dataset of the library publishes exactly {@code aName}. */
    private static boolean publishedAnywhere(Universe aUniverse, String aName)
    {
        return definedByAny(aUniverse.scopeCandidates(), aName, true)
                || definedByAny(List.copyOf(aUniverse.joinTargets().values()), aName, true);
    }


    /** S2: a wildcard column read no static census can resolve (an ADaM template, a * root). */
    private static boolean isCaptureWildcard(String aName)
    {
        return !aName.contains("--") && !aName.contains(".") && !aName.startsWith("$")
                && !aName.isEmpty() && Character.isUpperCase(aName.charAt(0))
                && (aName.contains("*") || !aName.equals(aName.toUpperCase(Locale.ROOT)));
    }


    private static void collectCaptures(Expr aExpr, Set<String> aOut)
    {
        switch (aExpr)
        {
        case Expr.And a -> a.parts().forEach(p -> collectCaptures(p, aOut));
        case Expr.Or o -> o.parts().forEach(p -> collectCaptures(p, aOut));
        case Expr.Not n -> collectCaptures(n.inner(), aOut);
        case Expr.Binary b ->
        {
            collectCaptures(b.left(), aOut);
            collectCaptures(b.right(), aOut);
        }
        case Expr.Call c ->
        {
            c.args().forEach(p -> collectCaptures(p, aOut));
            c.kwargs().values().forEach(p -> collectCaptures(p, aOut));
        }
        case Expr.Ref r ->
        {
            if ((r.kind() == OperandKind.WILDCARD_COLUMN && isCaptureWildcard(r.name()))
                    || isExpansionToken(r.name()))
            {
                aOut.add(r.name());
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
                        collectCaptures(e, aOut);
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
                // A supplement structure joins the scope only for a structure token the IG product
                // publishes NO structure for (OCCDS before adamig-1-3's era, the device tokens).
                // One of a token the IG does publish (adam-poppk / adam-nca BDS, adam-tte's BDS
                // for TTE) carries copied ADSL variables, and would hide a bare AGE meaning
                // ADSL.AGE on every BDS rule.
                Set<String> igTokens = new TreeSet<>();
                scope.forEach(d -> igTokens.add(String.valueOf(d.datasetClass())));
                for (String key : store.productCatalogue())
                {
                    if (key.startsWith("standards/adam/")
                            && !key.startsWith("standards/adam/adamig-"))
                    {
                        for (LibraryDataset d : adamStructures(key))
                        {
                            if (d.datasetClass() != null && !igTokens.contains(d.datasetClass()))
                            {
                                scope.add(d);
                            }
                        }
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
                String token = AdamStructureClassification.structureToken(aKey, s);
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
                String subclass = AdamStructureClassification.subClass(aKey, s);
                List<String> subclasses = subclass == null ? List.of() : List.of(subclass);
                out.add(LibraryDataset.of(name, token, structures, subclasses, vars));
            }
            return out;
        }
    }
}
