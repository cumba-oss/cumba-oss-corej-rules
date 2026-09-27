package net.cumba.corej.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.cumba.corej.core.exec.MetadataProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Fix #368 / Fix #369 — a {@link MetadataProvider} <b>decorator</b> must delegate every capability
 * method, and must be seen to do it.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>
 * {@code supportsStructureKeyedVariables()}, {@code getRequiredVariablesForStructure(..)},
 * {@code getExpectedVariablesForStructure(..)}, {@code isLibraryUnavailable()},
 * {@code getDefineVersion()} and {@code declaredStructureKeyedProducts()} are interface
 * {@code default} methods whose defaults mean <em>"I cannot answer"</em> / <em>"nothing is
 * wrong"</em>. That is right for a leaf implementation and <b>silently wrong</b> for a decorator: a
 * wrapper that forgets to delegate answers on behalf of a provider it is not entitled to speak for,
 * and the engine acts on the wrong answer. For the Fix #368 trio, {@code OperationExecutor} falls
 * back to the domain-keyed lookup Fix #368 removed and every ADaM {@code required_variables()} rule
 * goes green again. For Fix #369's {@code isLibraryUnavailable()}, a wrapper reports "library fine"
 * on behalf of a provider whose Library fetch threw and the degraded SKIP simply does not happen.
 * In both cases: no exception, no SKIP, no log line anywhere.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>This is not hypothetical.</b> It happened during Fix #368 itself. The engine change was
 * complete and its own unit tests passed, yet an end-to-end run was byte-identical to the broken
 * baseline, because {@code CompanionDomainsProvider} — which wraps the run provider on every
 * validation — inherited the three defaults. Nothing failed; the fix simply had no effect. Only
 * re-running the original experiment caught it. A test that asserts the mechanism works on a leaf
 * provider cannot see this class of defect, which is why the guard is structural.
 * </p>
 *
 * <h2>Why source text, and not reflection (review finding F4)</h2>
 *
 * <p>
 * A <b>behavioural</b> guard — construct every decorator over a recording delegate, invoke each
 * capability method, assert the delegate saw the same call with the same arguments — is strictly
 * harder to fool than any text scan, and it was the first thing tried. The reason given here for
 * not writing one was the <b>monorepo's</b>: this file and the corej-rules harnesses were one build
 * then, two of the four decorators in that population lived in modules depending on the guard's
 * own, so they were off its test classpath, and loading them out of a sibling module's
 * {@code target/test-classes} would have made the guard pass vacuously whenever that module
 * happened not to be built.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>That reason does not hold in this repo</b>, and the sentence sat here unchecked until
 * 2026-09-08 — the same prose-outlived-the-code defect as the ledger below. Both decorators
 * {@link #PINNED} names are in <em>this</em> module's own test sources
 * ({@code CompositeMetadataProvider} in this very package), so they are on the test classpath and a
 * behavioural guard is possible here. The source scan is kept anyway for a property a behavioural
 * one does not have: it derives the <b>population</b> from the tree rather than from a hand-kept
 * list of classes to instantiate, so a new {@code MetadataProvider} implementation appearing
 * anywhere under {@link #SCAN_ROOTS} reds
 * {@code theRoleOfEveryImplementationIsPinnedSoANewOneForcesADecision()} the day it lands. That is
 * what {@link #PINNED} and the floors rest on. And the scan is a <b>parsing</b> one rather than a
 * {@code contains(..)} one: comments and string literals are blanked before anything is matched,
 * the {@code @Override} must be present, the method body is extracted by brace matching, and the
 * body must forward to a delegate field passing <em>every</em> declared parameter through in order.
 * </p>
 *
 * <p>
 * That closes the four evasions the review demonstrated against the previous version, each of which
 * kept it green: an override that drops the subclass argument, a body of {@code return null;}, the
 * override commented out (the signature text still present), and a delegate field the field regex
 * stopped recognising — which made the class invisible to <em>all</em> checks, so the guard ran
 * zero of them and reported success.
 * </p>
 *
 * <h2>Fail loudly, never skip</h2>
 *
 * <p>
 * Every {@code MetadataProvider} implementation the scan finds is classified {@link Role#DECORATOR}
 * or {@link Role#LEAF} and the classification is <b>pinned</b> ({@link #PINNED}). A decorator whose
 * delegate field the scan can no longer see does not quietly become a leaf with nothing to check —
 * it reds, naming the class. The scan also asserts a floor on how many implementations it analysed
 * and how many individual checks it executed, so a refactor that empties the population reds
 * instead of passing.
 * </p>
 */
class MetadataProviderDecoratorDelegationGuardTest
{

    /**
     * The repository root, found by walking up from the module directory until a {@code rules-src}
     * directory appears beside a {@code pom.xml}.
     *
     * <p>
     * ⚠ <b>Not {@code lib}.</b> That is the monorepo copy's marker and this repo is flat, as
     * {@code findRepoRoot()}'s own comment records — the javadoc here said {@code lib} until
     * 2026-09-08, which is the same prose-outlives-the-code defect the ledger below had.
     * </p>
     *
     * <p>
     * ⚠ The scan is repo-wide, and that width is the whole point. The first version of this guard
     * scanned only this module's {@code src/main/java}. It passed — and the very next thing to
     * break was {@code ScenarioDeclaredScopeProvider}, the .cdt harness's own decorator, which
     * lives in another module's <b>test</b> sources and silently disabled the structure-keyed path
     * for every scenario. A guard that cannot see the decorator that breaks you is decoration.
     * </p>
     */
    private static final Path REPO_ROOT = findRepoRoot();

    /**
     * The source tree scanned: {@code src} — this flat repo's single source root, which holds the
     * spec / .cdt harnesses and every {@code MetadataProvider} implementation they hand to the
     * engine.
     *
     * <p>
     * ⚠ <b>Not {@code lib}</b>, which this javadoc claimed until 2026-09-08 while the constant had
     * long said {@code src}: {@code lib/<module>/} is the monorepo layout and does not exist here.
     * A reader who trusted the prose would have concluded the scan covered a tree that is not
     * present, i.e. that it was vacuous.
     * </p>
     *
     * <p>
     * ⚠ The coreJ monorepo also scanned {@code clients} (the CLI / REST / rule-editor front ends).
     * The split moved those to their own repos, which this repo's build never checks out, so the
     * root is gone rather than merely empty. Measured when it was dropped: no client implemented
     * {@code MetadataProvider}, so nothing stops being policed today — but a client that grows one
     * later is outside this guard, and needs its own copy in its own repo.
     * </p>
     *
     * <p>
     * Test sources are scanned too (only {@code *Test.java} is skipped, see
     * {@link #providerSources()}) — {@code CompositeMetadataProvider} and
     * {@code ScenarioDeclaredScopeProvider} are both production-shaped harness components living
     * under {@code src/test/java}, they are handed to the engine on every spec / .cdt run, and they
     * are the two that actually drifted.
     * </p>
     */
    private static final List<String> SCAN_ROOTS = List.of("src");

    private static Path findRepoRoot()
    {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null)
        {
            // ⚠ This repo is FLAT: rules-src/ sits directly beside the pom. The monorepo copy
            // of this finder looked for lib/<module>/, which no longer exists here.
            if (Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isDirectory(dir.resolve("rules-src")))
            {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "repository root not found from " + Path.of("").toAbsolutePath());
    }

    /** What an implementation is: a wrapper that must delegate, or a leaf that need not. */
    private enum Role
    {
        /** Holds another provider and must forward every capability method to it. */
        DECORATOR,
        /** Answers from its own state; the interface defaults are correct for it. */
        LEAF
    }

    /**
     * The pinned classification of every {@link MetadataProvider} implementation in the repository.
     *
     * <p>
     * ⚠⚠ <b>This map is the "fail loudly" half of the guard.</b> Classification is derived from the
     * source (does the class declare a field of provider type?), and the derived value is compared
     * against this pin. A decorator whose delegate field is renamed, retyped
     * ({@code MetadataLibraryProvider}, {@code List<MetadataProvider>}, …), made package-private or
     * given an initialiser used to fall out of the old field regex and take the whole class with
     * it: zero checks ran and the guard was green. Now the derived role flips to {@code LEAF}, the
     * pin disagrees, and the test names the class.
     * </p>
     *
     * <p>
     * When this fails, do not "fix" it by editing the pin. Either the class really did change role,
     * or — far more likely — the scan can no longer see its delegate field; make the field visible
     * (declare it directly in the class body, with a type naming a provider) or extend
     * {@link #delegateFields}.
     * </p>
     */
    private static final Map<String, Role> PINNED = Map.of(//
            // the rulespec harness
            "CompositeMetadataProvider.java", Role.DECORATOR, //
            "MockLibraryProvider.java", Role.LEAF, //
            // the .cdt scenario harness
            "ScenarioDeclaredScopeProvider.java", Role.DECORATOR, //
            // test double; a second copy of the same class also lives in the engine repo
            "StubMetadataProvider.java", Role.LEAF);

    /**
     * Floors, so a refactor that empties the population reds instead of passing vacuously.
     *
     * <p>
     * ⚠⚠ <b>This is the SIBLING half of the guard, and it exists because the split cut the
     * population in two.</b> In the coreJ monorepo one copy of this test covered all eight
     * {@code MetadataProvider} implementations. Five live in the engine repo and are policed by
     * that repo's copy; <b>four</b> are pinned above and live here, under {@code src/test} — the
     * monorepo's three harness classes plus {@code StubMetadataProvider}, of which each repo keeps
     * its own copy, which is why 5 + 4 still reconciles with eight distinct classes. No build ever
     * checks out both repos at once, so neither copy can see the other's.
     * </p>
     *
     * <p>
     * ⚠ <b>The two copies must be kept in step by hand.</b> A delegation rule added to one and not
     * the other silently stops applying to half the population. The counterpart is the engine
     * repository's {@code corej-core/src/test/java/net/cumba/corej/core/exec/
     * MetadataProviderDecoratorDelegationGuardTest.java}, whose floors are 5 / 2.
     * </p>
     *
     * <p>
     * Two of the four here are {@code DECORATOR}s — exactly the shape whose silent delegation gaps
     * this guard exists to catch, and precisely why the copy was not skipped.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>"In step" means the RULES, never the ledger rows.</b> The two populations differ, so
     * the two {@link #INHERITABLE_DEFAULTS} lists are <em>supposed</em> to differ; copying rows
     * across is what put twelve excuses about {@code DefineXmlMetadataProvider} into a repo that
     * has never contained it. Re-measure per repo — see that list's javadoc.
     * </p>
     */
    private static final int MIN_IMPLEMENTATIONS = 4;

    private static final int MIN_DECORATORS = 2;

    /** The two-arg primary: {@code getXForStructure(String token, List<String> subclasses)}. */
    private static final String TWO_ARG = "(?<![\\w.])%s\\s*\\(\\s*String\\s+(\\w+)\\s*,"
            + "\\s*List<String>\\s+(\\w+)\\s*\\)";

    /** {@code @Nullable String name(String x)} — the declared-class accessor's shape. */
    private static final String STRING_OF_STRING = "(?<![\\w.])String\\s+%s\\s*"
            + "\\(\\s*String\\s+(\\w+)\\s*\\)";

    /** {@code List<String> name(String x)} — the declared-subclasses accessor's shape. */
    private static final String LIST_OF_STRING = "(?<![\\w.])List<String>\\s+%s\\s*"
            + "\\(\\s*String\\s+(\\w+)\\s*\\)";

    /** {@code List<String> name()} — a no-arg published-list accessor. */
    private static final String LIST_NO_ARG = "(?<![\\w.])List<String>\\s+%s\\s*\\(\\s*\\)";

    /** {@code List<String> name(String a, String b)} — the CT-package attribute accessor. */
    private static final String LIST_OF_TWO_STRINGS = "(?<![\\w.])List<String>\\s+%s\\s*"
            + "\\(\\s*String\\s+(\\w+)\\s*,\\s*String\\s+(\\w+)\\s*\\)";

    /**
     * {@code Map<String, String> name(String a, String b)} — the codelist code-map accessor.
     * Whitespace-tolerant for the same reason as {@link #MAP_LIST}: a Spotless reflow must never be
     * able to turn a guard off by moving a line break.
     */
    private static final String MAP_OF_TWO_STRINGS = "(?<![\\w.])Map\\s*<\\s*String\\s*,"
            + "\\s*String\\s*>\\s+%s\\s*\\(\\s*String\\s+(\\w+)\\s*,\\s*String\\s+(\\w+)\\s*\\)";

    /**
     * {@code List<Map<String, String>>}, spelled whitespace-tolerantly on purpose: the four
     * declarations it has to match are long enough that both decorators already wrap them across
     * two lines, and a Spotless reflow must never be able to turn a guard off by moving a break.
     */
    private static final String MAP_LIST = "List\\s*<\\s*Map\\s*<\\s*String\\s*,"
            + "\\s*String\\s*>\\s*>";

    /** {@code List<Map<String, String>> name(String x)} — the model-variable accessors. */
    private static final String MAP_LIST_OF_STRING = "(?<![\\w.])" + MAP_LIST
            + "\\s+%s\\s*\\(\\s*String\\s+(\\w+)\\s*\\)";

    /** {@code List<String> name(IDataTable t, DatasetResolver r)}. */
    private static final String LIST_OF_TABLE = "(?<![\\w.])List<String>\\s+%s\\s*"
            + "\\(\\s*IDataTable\\s+(\\w+)\\s*,\\s*DatasetResolver\\s+(\\w+)\\s*\\)";

    /** {@code List<Map<String, String>> name(IDataTable t, DatasetResolver r)}. */
    private static final String MAP_LIST_OF_TABLE = "(?<![\\w.])" + MAP_LIST + "\\s+%s\\s*"
            + "\\(\\s*IDataTable\\s+(\\w+)\\s*,\\s*DatasetResolver\\s+(\\w+)\\s*\\)";

    /**
     * {@code Map<String, String> name(IDataTable t, DatasetResolver r, String a, String b)} — the
     * LIBRARY read's IG-absent tier.
     */
    private static final String MAP_OF_TABLE_TWO_STRINGS = "(?<![\\w.])Map\\s*<\\s*String\\s*,"
            + "\\s*String\\s*>\\s+%s\\s*\\(\\s*IDataTable\\s+(\\w+)\\s*,"
            + "\\s*DatasetResolver\\s+(\\w+)\\s*,\\s*String\\s+(\\w+)\\s*,"
            + "\\s*String\\s+(\\w+)\\s*\\)";

    /** {@code List<Map<String, String>> name(IDataTable t, DatasetResolver r, String c)}. */
    private static final String MAP_LIST_OF_TABLE_STRING = "(?<![\\w.])" + MAP_LIST
            + "\\s+%s\\s*\\(\\s*IDataTable\\s+(\\w+)\\s*,"
            + "\\s*DatasetResolver\\s+(\\w+)\\s*,\\s*String\\s+(\\w+)\\s*\\)";

    /**
     * Every {@code MetadataProvider} <b>capability</b> method — a {@code default} whose default
     * value means <em>"I cannot answer"</em> or <em>"nothing is wrong"</em>. Those are exactly the
     * defaults a decorator must never inherit.
     *
     * <p>
     * ⚠ <b>Add to this list whenever such a method is added to the interface</b> — and add the
     * <b>overload</b>, not just the name. It was named {@code STRUCTURE_KEYED_METHODS} and scoped
     * to Fix #368's trio; Fix #369 added {@code isLibraryUnavailable} and renamed it, because a
     * guard that does not grow with the interface passes while the newest method is undelegated —
     * the guard failing at the one job it exists for. Phase 3 of
     * {@code PLAN-metadata-product-selection} made each entry a signature <em>pattern</em> for the
     * same reason: the structure-keyed accessors then had a one-arg convenience beside the two-arg
     * primary, and a name-only guard could not tell them apart. (The one-arg conveniences were
     * retired 2026-09-25 — {@code PLAN-retire-dead-multi-match-lookup} U1 / A27 — but the pattern
     * discipline stays: an overload can come back.)
     * </p>
     *
     * <p>
     * The declaration pattern carries the return type as well as the parameter list. That is what
     * keeps a <em>call</em> inside some other method from being mistaken for a declaration, and its
     * capture groups are the declared parameter names — which the body check then demands to see
     * forwarded, in order.
     * </p>
     */
    private static final List<Capability> CAPABILITY_METHODS = List.of(//
            capability("supportsStructureKeyedVariables()",
                    "(?<![\\w.])boolean\\s+%s\\s*\\(\\s*\\)"),
            capability("isLibraryUnavailable()", "(?<![\\w.])boolean\\s+%s\\s*\\(\\s*\\)"),
            capability("getDefineVersion()", "(?<![\\w.])String\\s+%s\\s*\\(\\s*\\)"),
            // Review finding F6: log-only in effect, but the effect is on the Fix #369 SKIP
            // diagnostic, and the two decorators that had not delegated it were the rulespec and
            // .cdt harnesses — i.e. the diagnostic read "declared product(s) <unknown>" in exactly
            // the two places this area gets debugged from.
            capability("declaredStructureKeyedProducts()",
                    "(?<![\\w.])List<String>\\s+%s\\s*\\(\\s*\\)"),
            // ⚠⚠ Phase 3 of PLAN-metadata-product-selection: the TWO-ARG accessors carry the
            // dataset's subclass tokens, and a decorator that drops them silently strips the
            // subclass from every structure-keyed lookup: the governing structure is never
            // selected and the answer reverts to the base. These lose an ANSWER, not a log line,
            // so they are the strictest entries here. (Until 2026-09-25 each had a one-arg
            // convenience beside it that delegated with List.of(); those were retired as dead —
            // U1 / A27 — so the two-arg form is now the only one.)
            capability("getRequiredVariablesForStructure(String, List<String>)", TWO_ARG),
            capability("getExpectedVariablesForStructure(String, List<String>)", TWO_ARG),
            // ⛔⛔ Phase 11 finding F6b/F4b — the DECLARED tier (Fix #119), the OLDEST pair on the
            // interface and the last to be added here. Their defaults ("null" / "List.of()") mean
            // "the study metadata declares nothing", so a decorator that inherits them SPEAKS FOR
            // the sponsor's Define-XML def:Class / def:SubClass and says "undeclared".
            //
            // CompanionDomainsProvider inherited both. On the RuleRunner path the loss is usually
            // masked by tier 1 (the define provider answers first), but DatasetRuleResolver has NO
            // define
            // tier: LibraryValidator builds it with the wrapped provider and DatasetRuleResolver
            // reads
            // both accessors straight off it, so the generation-time Scope.Data_Structures /
            // Scope.Subclasses gate silently lost the declaration and reverted to the column
            // heuristic — the rule landed in skippedSourceRules and was never executed, so
            // RuleRunner's correctly-fed gate never got to disagree.
            //
            // ⚠ That this pair was missing for as long as the guard has existed is the same defect
            // this list keeps having: see everyInterfaceDefaultIsClassified(), which now derives
            // the population from the interface so the next one cannot be forgotten.
            capability("getDeclaredDatasetClass(String)", STRING_OF_STRING),
            capability("getDeclaredSubClasses(String)", LIST_OF_STRING),
            // Plan 2 R11 — the name-keyed SDTM carry-over lookup. A CAPABILITY, not an inheritable
            // default: the empty default means "this name is published nowhere", which the
            // carry-over rule reads as the NOT-APPLICABLE row and passes silently. A decorator that
            // inherits it therefore turns every carry-over check into a silent pass — the exact
            // shape this guard exists to catch, and indistinguishable from a correct answer.
            capability("getPublishedVariablesByName(String)",
                    "(?<![\\w.])List<PublishedVariable>\\s+%s\\s*\\(\\s*String\\s+(\\w+)\\s*\\)"),
            // ⭐ Review finding F2 — ENFORCED here, and only here. The interface default is
            // Optional.empty() ("no rich codelist view"), which is the same "I cannot answer"
            // shape as every other entry in this list: only the (term, value) shape of
            // codelist_terms is served through getCodelistTerms, and all five remaining
            // (level, returntype) shapes read getCodelist. A decorator that inherits the default
            // answers "no codelist" for a provider that has one, and the rule SKIPs — silently,
            // and green for an expect=noViolation scenario. ScenarioDeclaredScopeProvider did
            // exactly that: it sits on the LIBRARY slot for every ADaM structure=/subclass=
            // scenario without a define block.
            //
            // ⚠⚠ The engine-side copy of this guard must NOT copy this promotion. Its population
            // contains DefineXmlMetadataProvider, which does not delegate getCodelist, so the row
            // stays NOT_YET_ENFORCED there — that class is not in THIS repo's scan roots, and
            // neither is CompanionDomainsProvider. This is a real, measured divergence between
            // the two ledgers, not the copies drifting: the two populations differ. Do not
            // "re-sync" it in either direction without re-measuring the population.
            // ⚠ Whitespace-tolerant on purpose: CompositeMetadataProvider spells the return type
            // fully qualified and already wraps the declaration across two lines, so a Spotless
            // reflow must not be able to turn this guard off by moving a line break.
            capability("getCodelist(String)",
                    "(?<![\\w.])(?:java\\s*\\.\\s*util\\s*\\.\\s*)?Optional\\s*<[\\w.\\s]+>"
                            + "\\s+%s\\s*\\(\\s*String\\s+(\\w+)\\s*\\)"),
            // ⭐⭐ Re-derived 2026-09-08 — the eleven entries below were NOT_YET_ENFORCED rows
            // whose excuse read "not delegated by DefineXmlMetadataProvider", copied verbatim
            // from the engine-side ledger. That class is not in this repo's scanned population
            // and never has been, so each row excused a non-delegation that nothing here
            // performs: the guard had stopped guarding eleven methods, silently. Measured against
            // the source instead of the excuse, CompositeMetadataProvider and
            // ScenarioDeclaredScopeProvider already delegate every one of them, so there was
            // nothing left to excuse and each is promoted here. (A row cannot merely be deleted —
            // everyInterfaceDefaultIsClassified() reds an unclassified default.)
            //
            // ⚠ The asymmetry that decided the ambiguous ones: promoting wrongly reds loudly and
            // names the class, while keeping a wrong excuse is silent. Prefer promotion; the build
            // corrects you.
            //
            // ⛔ The engine-side copy must NOT copy these promotions. Its population really does
            // contain DefineXmlMetadataProvider, so its rows are correct as they stand and the two
            // ledgers are meant to differ — see INHERITABLE_DEFAULTS.
            //
            // The resolved dataset class. Its default is null ("I cannot say what class this
            // domain is"), and class is what selects the model variable set, so a decorator that
            // inherits it hands the engine an unclassified dataset rather than an error.
            capability("getDatasetClass(String)", STRING_OF_STRING),
            // The Library-flavoured "the standard publishes nothing" defaults — empty list or
            // null. A decorator that inherits one answers on behalf of a Library provider that
            // does publish the metadata, and the operation reading it degrades to a silent pass or
            // to LIBRARY_NOT_AVAILABLE ⇒ SKIP. Both decorators wrap exactly such a provider
            // (MetadataLibraryProvider, built from the pickle cache), which is what makes these
            // enforceable here.
            capability("getStandardVariableNames()", LIST_NO_ARG),
            capability("getStandardDatasetNames()", LIST_NO_ARG),
            capability("getPublishedCtPackages()", LIST_NO_ARG),
            capability("getModelVariables(String)", MAP_LIST_OF_STRING),
            capability("getModelVariablesForClass(String)", MAP_LIST_OF_STRING),
            capability("getCodelistAttribute(String, String)", LIST_OF_TWO_STRINGS),
            // The four (IDataTable, DatasetResolver) standard-model accessors. Same shape, and the
            // pair of them that returns List<Map<String, String>> is why MAP_LIST is
            // whitespace-tolerant: both decorators already wrap these declarations.
            capability("getStandardModelVariables(IDataTable, DatasetResolver)", LIST_OF_TABLE),
            capability("getStandardModelVariablesDetailed(IDataTable, DatasetResolver)",
                    MAP_LIST_OF_TABLE),
            capability("getStandardVariablesDetailed(IDataTable, DatasetResolver)",
                    MAP_LIST_OF_TABLE),
            capability("getStandardModelVariablesForClass(IDataTable, DatasetResolver, String)",
                    MAP_LIST_OF_TABLE_STRING),
            // ⭐ ENFORCED here, and only here. The last NOT_YET_ENFORCED row that had a live
            // delegate under it: the interface default is Map.of() ("no code map"), and
            // MetadataLibraryProvider — the pickle half every spec naming a standard/version
            // composes in — DOES implement it, so CompositeMetadataProvider inheriting the
            // default made library_variable_code_pair_matches (FDA-CT2003 / PMDA-CT2003) see no
            // concept ids on the rulespec path, silently. Delegated 2026-09-08; both decorators
            // here now declare it, so the excuse had nothing left to excuse.
            //
            // ⛔ The engine-side copy must NOT copy this promotion: its population contains
            // DefineXmlMetadataProvider, which does not delegate this method, and that class is
            // not under THIS repo's SCAN_ROOTS. The ledgers differ because the populations do.
            capability("getCodelistCodeMap(String, String)", MAP_OF_TWO_STRINGS),
            // PLAN-library-var-custom-domains: the last tier of the LIBRARY variable read. Its
            // {} default means "not served", which the var_*("LIBRARY") rules read as a silent
            // pass; a decorator inheriting it hides the tier of the provider it wraps.
            capability("getIgAbsentVariableMetadata(IDataTable, DatasetResolver, String, String)",
                    MAP_OF_TABLE_TWO_STRINGS));

    /** Floor on the capability set itself, so an accidental deletion reds. */
    private static final int MIN_CAPABILITIES = 23;

    /** Floor on the interface's own default count, so a truncated parse cannot pass. */
    private static final int MIN_INTERFACE_DEFAULTS = 26;

    /** Why a {@code default} is allowed to be inherited by a decorator. */
    private enum Why
    {
        /**
         * The interface's own body forwards to another form of the same accessor, so an inheriting
         * decorator still ends up in its own override of that other form. Overriding these
         * separately is actively wrong — it is how the subclass axis got stripped in Phase 3.
         */
        SELF_DELEGATING,
        /**
         * ⚠ Same "I cannot answer" shape as a {@link Capability}, and <b>not</b> established as
         * safe — merely not enforced yet. Promoting it today would red decorators this change does
         * not own, so the decision is recorded here rather than made silently. The note names the
         * decorators that do not delegate it as of Phase 11.
         */
        NOT_YET_ENFORCED
    }


    /** One interface {@code default} a decorator may inherit, with the reason it may. */
    private record InheritableDefault(String signature, Why why, String note)
    {
    }

    private static InheritableDefault inheritable(String aSignature, Why aWhy, String aNote)
    {
        return new InheritableDefault(aSignature, aWhy, aNote);
    }

    /**
     * The interface {@code default}s a decorator is <b>not</b> required to delegate.
     *
     * <p>
     * ⚠⚠ This list is not a blessing, it is a <b>ledger</b>. Every {@code default} on
     * {@link MetadataProvider} must appear either here or in {@link #CAPABILITY_METHODS}, and
     * {@link #everyInterfaceDefaultIsClassified()} derives that population from the interface's own
     * source. A {@code default} added to {@code MetadataProvider} tomorrow reds until a human puts
     * it in one list or the other. That test exists because the hand-maintained
     * {@code CAPABILITY_METHODS} had, since the day the guard was written, omitted the
     * <em>oldest</em> pair on the interface — {@code getDeclaredDatasetClass} /
     * {@code getDeclaredSubClasses} (Fix #119) — while its own javadoc warned that a guard which
     * does not grow with the interface passes while the newest method is undelegated.
     * </p>
     *
     * <p>
     * ⛔ The {@link Why#NOT_YET_ENFORCED} block is a <b>known, measured gap</b>, not a safety
     * argument: those defaults all return a constant "unknown", exactly like a capability method.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Every note here was re-derived over THIS repo's population on 2026-09-08</b> — the two
     * decorators {@link #PINNED} names, found under {@link #SCAN_ROOTS}. Until then the block was a
     * verbatim copy of the engine-side ledger: of its fourteen {@link Why#NOT_YET_ENFORCED} rows,
     * twelve named {@code DefineXmlMetadataProvider} — a class that has never existed in this repo
     * and that no scan here can reach — and eleven named nothing else. The excuses were true THERE
     * and vacuous HERE, so eleven capability-shaped defaults that both decorators <em>do</em>
     * delegate were excused by a sentence about nothing, and the guard had silently stopped
     * guarding them. They are now in {@link #CAPABILITY_METHODS}; {@code getCodelist(String)} went
     * the same way in 4b710bc2. The twelfth, {@code getCodelistCodeMap}, named
     * {@code CompositeMetadataProvider} too — that half was true, and is what kept the row until
     * 2026-09-08, when that decorator was given the missing delegation and the row was promoted
     * with the rest.
     * </p>
     *
     * <p>
     * The two that survive are one shape — {@code CompositeMetadataProvider} declaring no override
     * at all — and <b>neither has a delegate beneath it that could have answered</b>: only a Define
     * provider serves {@code getKeyVariables} / {@code getDatasetNames}, and the composite wraps a
     * spec mock over a pickle Library provider, neither of which is one. That is now the bar for
     * staying here — promote either the day a live delegate appears under it, as
     * {@code getCodelistCodeMap} was. Each note names the class in <b>this</b> repo that needs it;
     * a note naming a class outside {@link #PINNED} is by construction an excuse for nobody, and
     * must be re-measured rather than reworded.
     * </p>
     *
     * <p>
     * ⛔ <b>Do not re-sync this block with the engine-side copy in either direction.</b> The two
     * ledgers differ because the two populations differ; that is the fixed state, not drift.
     * </p>
     */
    private static final List<InheritableDefault> INHERITABLE_DEFAULTS = List.of(//
            inheritable("getDatasetClass(String, String)", Why.SELF_DELEGATING,
                    "the interface body is `return getDatasetClass(aCdiscDomain)`, so it lands in "
                            + "the one-arg form's override — and the one-arg form is itself a "
                            + "required capability as of 2026-09-08, so this excuse now leans on "
                            + "an enforced delegation rather than on another excuse"),
            inheritable("getDatasetClass(String, String, Set<String>)", Why.SELF_DELEGATING,
                    "the interface body is `return getDatasetClass(aMemberName, aCdiscDomain)`, "
                            + "which chains to the one-arg form"),
            inheritable("getKeyVariables(String)", Why.NOT_YET_ENFORCED,
                    "CompositeMetadataProvider declares no getKeyVariables override at all, so the "
                            + "rulespec harness inherits the interface's List.of() — \"this domain "
                            + "declares no key variables\". Nothing observable depends on it "
                            + "TODAY: only a Define provider serves a meaningful ordering (it "
                            + "backs define_key_variables), and neither half the composite wraps "
                            + "is one — MockLibraryProvider does not implement it, and nor does "
                            + "MetadataLibraryProvider, the pickle half. Promote it the day either "
                            + "does. ScenarioDeclaredScopeProvider DOES delegate it"),
            inheritable("getDatasetNames()", Why.NOT_YET_ENFORCED,
                    "CompositeMetadataProvider declares no getDatasetNames override at all, so it "
                            + "inherits List.of() and define_dataset_names reads \"the study "
                            + "declares no datasets\". Same shape as getKeyVariables above and "
                            + "equally inert today: only a Define provider answers it, and the "
                            + "composite wraps a spec mock over a pickle Library provider, neither "
                            + "of which is one. ScenarioDeclaredScopeProvider DOES delegate it"));

    /**
     * One capability method, as its human-readable signature plus the pattern that proves a
     * decorator <b>declared</b> it. Matching on the bare name is not enough once the interface
     * carries overloads: while the one-arg conveniences existed,
     * {@code contains("getRequiredVariablesForStructure(String ")} was satisfied by the one-arg
     * form <em>and</em> by the two-arg form, so a guard keyed on names alone would have passed
     * while the primary overload went undelegated — the guard failing at the one job it exists for,
     * in exactly the way Fix #369's rename was written to prevent.
     */
    private record Capability(String signature, String name, Pattern declaration)
    {
    }

    private static Capability capability(String aSignature, String aPatternTemplate)
    {
        String name = aSignature.substring(0, aSignature.indexOf('('));
        return new Capability(aSignature, name,
                Pattern.compile(String.format(aPatternTemplate, name)));
    }

    /**
     * A field declaration at class-body level: modifiers and annotations (in any order, any or none
     * — {@code package-private} included), a type, a name, an optional initialiser.
     *
     * <p>
     * ⚠ The old version of this pattern demanded {@code private|protected|public} and forbade an
     * {@code =}, which is why a package-private field, an initialised field, a
     * {@code MetadataLibraryProvider}-typed field or a {@code List<MetadataProvider>} field made
     * the whole class invisible. Whether a matched type actually names a provider is decided
     * separately ({@link #delegateFields}) against the set of provider type names discovered by the
     * scan, so {@code List<MetadataProvider>}, {@code MetadataProvider[]} and a concrete provider
     * type all count.
     * </p>
     */
    private static final Pattern FIELD = Pattern.compile("(?m)^[ \\t]+"
            + "(?:(?:@\\w+(?:\\([^()]*\\))?|private|protected|public|static|final|transient"
            + "|volatile)[ \\t\\n]+)*"
            + "([\\w.]+(?:[ \\t]*<[^;{}()]*>)?(?:[ \\t]*\\[[ \\t]*\\])*)[ \\t]+(\\w+)"
            + "[ \\t]*(?:=[^;{}]*)?;");

    /** One implementation of {@link MetadataProvider}, with its comment-free source. */
    private record Impl(Path path, String fileName, String stripped)
    {
    }

    // ---- the scan -----------------------------------------------------------------

    /**
     * Every implementation of {@link MetadataProvider} under {@link #SCAN_ROOTS}, with comments and
     * string literals already blanked.
     *
     * <p>
     * A provider declared inside a {@code *Test.java} is that one test's local double: it is
     * constructed and consumed in the same file, so an omission there cannot propagate. A provider
     * with its own file is a shared component — the engine's own, or one the .cdt harness and the
     * rulespec suites hand to the engine — and that is where a missing delegation silences other
     * people's tests.
     * </p>
     */
    private static List<Impl> providerSources() throws IOException
    {
        List<Impl> out = new ArrayList<>();
        for (String root : SCAN_ROOTS)
        {
            Path dir = REPO_ROOT.resolve(root);
            assertTrue(Files.isDirectory(dir), dir + " not found — the guard would pass vacuously");
            try (Stream<Path> files = Files.walk(dir))
            {
                for (Path p : files.filter(f -> f.toString().endsWith(".java"))
                        .filter(f -> !f.toString().contains("/target/"))
                        .filter(f -> !f.toString().contains("/node_modules/"))
                        .filter(f -> !f.getFileName().toString().endsWith("Test.java")).toList())
                {
                    String raw = Files.readString(p, StandardCharsets.UTF_8);
                    if (!raw.contains("implements MetadataProvider"))
                    {
                        continue;
                    }
                    // ⚠ A text block would defeat the literal blanker below and could corrupt the
                    // brace depths the field scan relies on. Refuse to analyse rather than analyse
                    // wrongly — silence is the failure mode this whole rewrite is about.
                    assertTrue(!raw.contains("\"\"\""),
                            p + " contains a text block; "
                                    + MetadataProviderDecoratorDelegationGuardTest.class
                                            .getSimpleName()
                                    + " cannot reliably blank one, so its brace analysis would be "
                                    + "unsound. Teach stripCommentsAndLiterals about text blocks "
                                    + "before landing this file.");
                    String stripped = stripCommentsAndLiterals(raw);
                    if (stripped.contains("implements MetadataProvider"))
                    {
                        out.add(new Impl(p, p.getFileName().toString(), stripped));
                    }
                }
            }
        }
        return out;
    }


    /**
     * Blanks comment bodies and string/char literal contents, preserving every offset and newline
     * so brace depths and match positions stay meaningful.
     *
     * <p>
     * ⚠ This is what kills the third evasion the review demonstrated: an override <b>commented
     * out</b>, whose signature text a {@code contains(..)} scan still happily found.
     * </p>
     */
    private static String stripCommentsAndLiterals(String aSource)
    {
        char[] out = aSource.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n)
        {
            char c = aSource.charAt(i);
            if (c == '/' && i + 1 < n && aSource.charAt(i + 1) == '/')
            {
                int end = aSource.indexOf('\n', i);
                end = end < 0 ? n : end;
                blank(out, i, end, false);
                i = end;
            }
            else if (c == '/' && i + 1 < n && aSource.charAt(i + 1) == '*')
            {
                int end = aSource.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                blank(out, i, end, true);
                i = end;
            }
            else if (c == '"' || c == '\'')
            {
                int end = literalEnd(aSource, i, c);
                blank(out, i + 1, end - 1, false);
                i = end;
            }
            else
            {
                i++;
            }
        }
        return new String(out);
    }


    private static void blank(char[] aOut, int aFrom, int aTo, boolean aKeepNewlines)
    {
        for (int k = Math.max(aFrom, 0); k < Math.min(aTo, aOut.length); k++)
        {
            if (!aKeepNewlines || aOut[k] != '\n')
            {
                aOut[k] = ' ';
            }
        }
    }


    /** Index one past the closing quote of the literal opening at {@code aStart}. */
    private static int literalEnd(String aSource, int aStart, char aQuote)
    {
        int j = aStart + 1;
        while (j < aSource.length() && aSource.charAt(j) != aQuote)
        {
            if (aSource.charAt(j) == '\\')
            {
                j++;
            }
            j++;
        }
        return Math.min(j + 1, aSource.length());
    }


    /**
     * Brace depth at every offset. An opening brace carries the depth <em>outside</em> it and a
     * closing brace the depth outside too, so a member of a top-level class body sits at depth 1
     * and a local inside one of its methods at depth 2 or more.
     */
    private static int[] braceDepths(String aStripped)
    {
        int[] depth = new int[aStripped.length()];
        int d = 0;
        for (int i = 0; i < aStripped.length(); i++)
        {
            char c = aStripped.charAt(i);
            if (c == '{')
            {
                depth[i] = d;
                d++;
            }
            else if (c == '}')
            {
                d--;
                depth[i] = d;
            }
            else
            {
                depth[i] = d;
            }
        }
        return depth;
    }


    /**
     * The names of the class's delegate fields — the class-body fields whose type names a provider.
     *
     * <p>
     * Restricting to depth 1 keeps method locals out. The consequence of a delegate field the scan
     * cannot see is <b>not</b> a silent skip: {@link #PINNED} turns it into a red naming the class.
     * </p>
     */
    private static List<String> delegateFields(Impl aImpl, Pattern aProviderTypeName)
    {
        int[] depth = braceDepths(aImpl.stripped());
        List<String> fields = new ArrayList<>();
        Matcher m = FIELD.matcher(aImpl.stripped());
        while (m.find())
        {
            if (depth[m.start()] == 1 && aProviderTypeName.matcher(m.group(1)).find())
            {
                fields.add(m.group(2));
            }
        }
        return fields;
    }


    /** A word-boundary alternation over {@code MetadataProvider} and every discovered impl name. */
    private static Pattern providerTypeNames(List<Impl> aImpls)
    {
        List<String> names = new ArrayList<>();
        names.add("MetadataProvider");
        for (Impl impl : aImpls)
        {
            names.add(impl.fileName().substring(0, impl.fileName().length() - ".java".length()));
        }
        // Longest first so an alternation never settles for a proper prefix.
        names.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return Pattern.compile("\\b(?:" + String.join("|", names) + ")\\b");
    }


    /** The body of the member whose declaration ends at {@code aFrom}, by brace matching. */
    private static @Nullable String bodyAfter(String aStripped, int aFrom)
    {
        int open = aStripped.indexOf('{', aFrom);
        if (open < 0)
        {
            return null;
        }
        int d = 0;
        for (int j = open; j < aStripped.length(); j++)
        {
            char c = aStripped.charAt(j);
            if (c == '{')
            {
                d++;
            }
            else if (c == '}')
            {
                d--;
                if (d == 0)
                {
                    return aStripped.substring(open + 1, j);
                }
            }
        }
        return null;
    }

    // ---- the checks ---------------------------------------------------------------


    /**
     * Whether {@code aBody} really forwards: it names at least one delegate field, and it either
     * invokes {@code aName} on some receiver passing exactly {@code aParams} in order, or hands the
     * method on as an unbound method reference (which forwards everything by construction).
     */
    private static boolean forwards(String aBody, String aName, List<String> aParams,
            List<String> aFields)
    {
        boolean namesDelegate = aFields.stream().anyMatch(
                f -> Pattern.compile("\\b" + Pattern.quote(f) + "\\b").matcher(aBody).find());
        if (!namesDelegate)
        {
            return false;
        }
        if (Pattern.compile("::\\s*" + Pattern.quote(aName) + "\\b").matcher(aBody).find())
        {
            return true;
        }
        StringBuilder args = new StringBuilder();
        for (String p : aParams)
        {
            if (args.length() > 0)
            {
                args.append("\\s*,\\s*");
            }
            args.append("\\b").append(Pattern.quote(p)).append("\\b");
        }
        String call = "(?<![\\w.])\\w+\\s*\\.\\s*" + Pattern.quote(aName) + "\\s*\\(\\s*" + args
                + "\\s*\\)";
        return Pattern.compile(call).matcher(aBody).find();
    }


    @Test
    void everyDecoratorDelegatesEveryCapabilityMethod() throws IOException
    {
        List<Impl> impls = providerSources();
        assertTrue(impls.size() >= MIN_IMPLEMENTATIONS,
                "expected at least " + MIN_IMPLEMENTATIONS + " MetadataProvider implementations, "
                        + "found " + impls.size() + " "
                        + impls.stream().map(Impl::fileName).toList()
                        + ". The scan has stopped seeing the population it exists to check — fix "
                        + "the scan, do not lower the floor.");
        Pattern providerTypeName = providerTypeNames(impls);

        List<String> offenders = new ArrayList<>();
        int decorators = 0;
        int checks = 0;
        for (Impl impl : impls)
        {
            List<String> fields = delegateFields(impl, providerTypeName);
            if (fields.isEmpty())
            {
                continue;
            }
            decorators++;
            String src = impl.stripped();
            for (Capability method : CAPABILITY_METHODS)
            {
                checks++;
                Matcher m = method.declaration().matcher(src);
                if (!m.find())
                {
                    offenders.add(impl.fileName() + " does not declare " + method.signature());
                    continue;
                }
                // @Override, in the run of tokens since the previous member ended. Comments are
                // already blanked, so nothing here can be prose.
                int prev = Math.max(
                        Math.max(src.lastIndexOf(';', m.start()), src.lastIndexOf('}', m.start())),
                        src.lastIndexOf('{', m.start()));
                if (!src.substring(prev + 1, m.start()).contains("@Override"))
                {
                    offenders.add(impl.fileName() + " declares " + method.signature()
                            + " without @Override — it is not overriding the interface default");
                    continue;
                }
                String body = bodyAfter(src, m.end());
                if (body == null)
                {
                    offenders.add(impl.fileName() + " declares " + method.signature()
                            + " with no body the guard can read");
                    continue;
                }
                List<String> params = new ArrayList<>();
                for (int g = 1; g <= m.groupCount(); g++)
                {
                    if (m.group(g) != null)
                    {
                        params.add(m.group(g));
                    }
                }
                if (!forwards(body, method.name(), params, fields))
                {
                    offenders.add(impl.fileName() + " declares " + method.signature()
                            + " but its body does not forward to one of " + fields + " passing "
                            + (params.isEmpty() ? "the call" : params + " through in order"));
                }
            }
        }

        assertEquals(List.of(), offenders,
                "a MetadataProvider decorator inherits a capability default, or overrides it "
                        + "without really delegating, and so answers on behalf of a provider it "
                        + "cannot speak for. Depending on the method, ADaM required_variables() "
                        + "silently falls back to domain keying (Fix #368), a degraded CDISC "
                        + "Library silently stops skipping (Fix #369), or the SKIP diagnostic "
                        + "reports 'declared product(s) <unknown>' — with no exception, no SKIP "
                        + "and no log line anywhere");
        assertEquals(MIN_DECORATORS, decorators,
                "the guard analysed " + decorators + " decorators; " + MIN_DECORATORS
                        + " are pinned in PINNED. A decorator that stops being recognised runs "
                        + "zero checks and would otherwise report green — see PINNED's javadoc.");
        assertEquals(MIN_DECORATORS * CAPABILITY_METHODS.size(), checks,
                "the guard executed " + checks + " delegation checks; it must execute one per "
                        + "(decorator × capability method)");
    }


    @Test
    void theRoleOfEveryImplementationIsPinnedSoANewOneForcesADecision() throws IOException
    {
        List<Impl> impls = providerSources();
        Pattern providerTypeName = providerTypeNames(impls);
        Map<String, Role> found = new LinkedHashMap<>();
        for (Impl impl : impls)
        {
            found.put(impl.fileName(),
                    delegateFields(impl, providerTypeName).isEmpty() ? Role.LEAF : Role.DECORATOR);
        }
        // If this fails, either a new MetadataProvider implementation appeared (decide whether it
        // is a decorator — delegate every capability method — or a leaf, then add it here), or an
        // existing one changed role. ⚠ A pinned DECORATOR that now reads LEAF almost never means
        // the class stopped wrapping: it means the scan can no longer see its delegate field, and
        // that class is now checked by nothing. Make the field visible or extend delegateFields();
        // do NOT edit the pin to match.
        assertEquals(new TreeMap<>(PINNED), new TreeMap<>(found),
                "the set or the role of the MetadataProvider implementations changed. A pinned "
                        + "DECORATOR reading LEAF means the guard lost sight of its delegate "
                        + "field, so every delegation check for that class silently stopped "
                        + "running — declare the field directly in the class body with a type "
                        + "naming a provider, or extend delegateFields(); do not edit PINNED to "
                        + "agree with the scan.");
    }

    // ---- deriving the population from the interface itself -------------------------


    /**
     * Where the required set is derived FROM, named for the assertion messages.
     *
     * <p>
     * ⚠ Engine-side this is a {@code Path} to {@code MetadataProvider.java}. Here it cannot be: the
     * interface belongs to {@code corej-core}, a different repository, and this repo's build never
     * checks that one out. The derivation reads the compiled interface off the test classpath
     * instead — see {@link #declaredDefaultSignatures()}.
     * </p>
     */
    private static String metadataProviderSource()
    {
        return MetadataProvider.class.getName() + " (compiled, from corej-core on the "
                + "test classpath)";
    }

    /**
     * Every {@code default} declared on {@link MetadataProvider}, as {@code name(Type, Type)}.
     *
     * <p>
     * ⚠ <b>Derived by REFLECTION here, where the engine-side copy parses the source file.</b> The
     * interface lives in another repository that this build never checks out, so there is no source
     * to read. {@code Method.isDefault()} answers the same question the source parse asks and
     * cannot be broken by a formatting change — but it sees only what the compiler kept, so the two
     * copies can disagree if the interface is ever changed without rebuilding this repo against the
     * new engine snapshot. The floor below is what catches that.
     * </p>
     */
    private static final Pattern PACKAGE_QUALIFIER = Pattern
            .compile("\\b[a-z][\\w]*(?:\\.[a-z][\\w]*)*\\.");

    private static List<String> declaredDefaultSignatures()
    {
        List<String> out = new ArrayList<>();
        for (java.lang.reflect.Method m : MetadataProvider.class.getDeclaredMethods())
        {
            if (!m.isDefault())
            {
                continue;
            }
            StringBuilder params = new StringBuilder();
            for (java.lang.reflect.Type t : m.getGenericParameterTypes())
            {
                if (params.length() > 0)
                {
                    params.append(", ");
                }
                // ⚠ GENERIC types, not getParameterTypes(). The signature keys the classification
                // lists use keep their type arguments -- "getDatasetClass(String, String, Set)"
                // and "…(String, String, Set<String>)" are different keys, and erasing them here
                // would make every parameterised default look unclassified.
                params.append(PACKAGE_QUALIFIER.matcher(t.getTypeName()).replaceAll(""));
            }
            out.add(m.getName() + "(" + params + ")");
        }
        return out;
    }


    /**
     * ⭐ Phase 11 finding F4b — the required set is <b>derived from the interface</b>, not
     * hand-maintained.
     *
     * <p>
     * {@link #CAPABILITY_METHODS} carried a javadoc warning to "add to this list whenever such a
     * method is added to the interface", and that warning did not work: the <em>oldest</em> pair on
     * the interface, {@code getDeclaredDatasetClass} / {@code getDeclaredSubClasses} (Fix #119),
     * had never been added, and {@code CompanionDomainsProvider} inherited both defaults on every
     * companion-wrapped ADaM run. A list that a human has to remember to grow is the same defect
     * class as a decorator that a human has to remember to update — which is the thing this whole
     * file exists to catch. So the population comes from {@code MetadataProvider.java} itself, and
     * every {@code default} on it must be classified: required to be delegated
     * ({@link #CAPABILITY_METHODS}) or explicitly, individually excused
     * ({@link #INHERITABLE_DEFAULTS}). A new {@code default} belongs to neither until someone says
     * so, and until then this reds.
     * </p>
     */
    @Test
    void everyInterfaceDefaultIsClassified()
    {
        List<String> declared = declaredDefaultSignatures();
        assertTrue(declared.size() >= MIN_INTERFACE_DEFAULTS, "found only " + declared.size()
                + " default methods on " + metadataProviderSource() + ", expected at least "
                + MIN_INTERFACE_DEFAULTS + " — the derivation has broken, and a broken derivation "
                + "classifies nothing while looking green. Fix it, do not lower the floor.");
        assertEquals(declared.size(), new TreeSet<>(declared).size(),
                "two interface defaults reduced to the same signature key, so one of them cannot "
                        + "be classified independently: " + declared);

        assertEquals(MIN_CAPABILITIES, CAPABILITY_METHODS.size(),
                "CAPABILITY_METHODS changed size; every entry is a delegation a decorator must "
                        + "make, so removing one silently stops enforcing it");

        Set<String> required = new TreeSet<>();
        for (Capability c : CAPABILITY_METHODS)
        {
            required.add(c.signature());
        }
        Set<String> excused = new TreeSet<>();
        for (InheritableDefault d : INHERITABLE_DEFAULTS)
        {
            excused.add(d.signature());
        }
        Set<String> both = new TreeSet<>(required);
        both.retainAll(excused);
        assertEquals(Set.of(), both,
                "a default cannot be both required and excused — decide which it is");

        Set<String> classified = new TreeSet<>(required);
        classified.addAll(excused);
        assertEquals(new TreeSet<>(declared), classified,
                "MetadataProvider's default methods and the guard's classification of them have "
                        + "diverged. A default present on the interface but absent here is "
                        + "UNCLASSIFIED: no decorator is required to delegate it and nobody has "
                        + "said that is safe — add it to CAPABILITY_METHODS (with a declaration "
                        + "pattern) or to INHERITABLE_DEFAULTS (with a reason). A signature listed "
                        + "here but absent from the interface is stale; delete it.");
    }
}
