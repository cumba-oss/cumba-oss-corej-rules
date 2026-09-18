package net.cumba.corej.core.ruletestsuites;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.AdamDataStructureDetector;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.metadata.ICodeList;
import org.jspecify.annotations.Nullable;

/**
 * Carries a scenario's {@code structure=} / {@code subclass=} declaration into
 * {@link net.cumba.corej.core.exec.RuleRunner}'s ADaM scope gates (Fix #117/#118/#119).
 *
 * <h2>Why this exists</h2>
 *
 * <p>
 * {@code CdtLoader} stores the two {@code .cdt} dataset attributes as the table-level metadata keys
 * {@code dataset_structure} / {@code dataset_subclass}, and
 * {@code AbstractRuleTestSuitesFactory.describeScopeMismatch} reads them for the scenario pre-gate.
 * The engine knows nothing about those keys: {@code RuleRunner} re-applies the same two axes at
 * execution time and consults the <em>declared</em> tier through a {@link MetadataProvider} —
 * {@link MetadataProvider#getDeclaredDatasetClass(String)} and
 * {@link MetadataProvider#getDeclaredSubClasses(String)}, the Define-XML {@code def:Class} /
 * {@code def:SubClass} channel. Without this decorator a fixture declaring {@code structure=OCCDS}
 * but carrying no qualifying column cleared the pre-gate and was then SKIPPED mid-execution.
 * </p>
 *
 * <p>
 * The decorator answers those two accessors for the scenario's primary dataset (matched
 * case-insensitively on the dataset name) and delegates <em>everything</em> else — including every
 * {@code default} method — to the wrapped provider, so wrapping is otherwise invisible.
 * </p>
 *
 * <h2>Which provider gets decorated, and why a carrier block is mandatory</h2>
 *
 * <p>
 * {@code RuleRunner} reads the declared tier from the define provider first and falls through to
 * the library provider when the define declares nothing (Fix #154 chained the two; before that it
 * was {@code defineProvider != null ? defineProvider : libraryProvider}), so {@link #install}
 * decorates whichever of those two slots is occupied — the define slot first. It <b>refuses</b> a
 * scenario that declares an axis while both slots are empty (no {@code #library} /
 * {@code #library-ref} / {@code #define} / {@code #define-xml} block) rather than materialising a
 * provider the scenario never asked for.
 * </p>
 *
 * <p>
 * <b>Why refusal and not a transparent decorator (review finding F-R8).</b> Provider availability
 * is not an accessor this class could answer "absent" for — it is a bare nullness test on the
 * reference, in every gate:
 * </p>
 *
 * <pre>
 * BuiltinFunctions   library_available()  -&gt; OperationExecutor.libraryAnswerable(provider)
 * (Fix #369: was `getLibraryProvider() != null`, which a DEGRADED provider passed.)
 * RuleRunner:439     libraryProvider == null &amp;&amp; referencesOperandPrefix(check, "library_")
 * RuleRunner:537     libraryProvider == null &amp;&amp; OperationExecutor.isLibraryDependent(op)
 * OperationExecutor  libraryProvider == null -&gt; LIBRARY_NOT_AVAILABLE
 * RuleRunner:430     defineProvider  == null &amp;&amp; referencesOperandPrefix(check, "define_")
 * </pre>
 *
 * <p>
 * A decorator is a non-null reference by construction, so occupying an empty slot silently switches
 * the corresponding availability self-skips <em>off</em>: a library-dependent rule that should
 * report "no Library access" would start executing instead. There is no slot to borrow — moving the
 * decorator to the define slot only trades the library gates for the {@code define_*} ones. So the
 * carrier is the author's explicit choice, declared in the {@code .cdt}: add
 * {@code #define standard=… version=…} (preferred — the declared tier <em>is</em> the Define-XML
 * {@code def:Class} / {@code def:SubClass} channel, and the define slot is the one
 * {@code RuleRunner} consults first) or {@code #library …} when the rule needs Library metadata
 * anyway.
 * </p>
 *
 * <h2>Precedence</h2>
 *
 * <p>
 * The declaration is a <em>declared-tier</em> input: {@code AdamDataStructureDetector
 * .detect(name, columns, declaredClass, defineFirst)} and {@code AdamSubclassDetector.resolve(...)}
 * use it as an <b>override</b> of the column heuristic by default — {@code corej.defineFirst} is
 * {@code true} since Fix #154 — and as a mere <b>fallback</b> (when the columns yield
 * {@code ADAM OTHER} / no subclass) only under {@code -Dcorej.defineFirst=false}. A declaration
 * that contradicts a confident column detection therefore wins by default, same as a real
 * Define-XML declaration.
 * </p>
 */
public final class ScenarioDeclaredScopeProvider implements MetadataProvider
{

    /** Table-level metadata key {@code CdtLoader} stores the {@code structure=} attribute under. */
    public static final String STRUCTURE_KEY = "dataset_structure";

    /** Table-level metadata key {@code CdtLoader} stores the {@code subclass=} attribute under. */
    static final String SUBCLASS_KEY = "dataset_subclass";

    /** Table-level metadata key {@code CdtLoader} stores the {@code class=} attribute under. */
    static final String CLASS_KEY = "dataset_class";

    private final MetadataProvider base;

    private final String datasetName;

    private final @Nullable String declaredClass;

    private final List<String> declaredSubClasses;

    private ScenarioDeclaredScopeProvider(String aDatasetName, @Nullable String aDeclaredClass,
            List<String> aDeclaredSubClasses, MetadataProvider aDelegate)
    {
        datasetName = aDatasetName;
        declaredClass = aDeclaredClass;
        declaredSubClasses = aDeclaredSubClasses;
        base = aDelegate;
    }

    /**
     * The two {@code RuleRunner} metadata slots after {@link #install} has placed the decorator, in
     * the order the factory passes them on. Either may still be {@code null} — {@code install}
     * never fills an empty slot, it only decorates one that the scenario already declared.
     *
     * @param library
     *            the {@code #library} / {@code #library-ref} slot
     * @param define
     *            the {@code #define} / {@code #define-xml} slot
     */
    record Slots(@Nullable MetadataProvider library, @Nullable MetadataProvider define)
    {
    }

    // ---- scenario-facing factory --------------------------------------------------

    /** The scenario's {@code structure=} declaration (shortcut already expanded), or null. */
    static @Nullable String declaredStructure(OverlayDataTable aPrimary)
    {
        Object declared = aPrimary.getMetaData().getMetaData(STRUCTURE_KEY);
        return declared != null ? declared.toString() : null;
    }


    /**
     * <b>Phase 3a.3 — the harness/production divergence, closed.</b> The scenario's effective
     * declared ADaM structure: the explicit {@code structure=} when present, otherwise the
     * {@code class=} declaration <em>if it names an ADaM data structure</em>
     * ({@link AdamDataStructureDetector#structureTokenFromDeclaredClass}, which returns
     * {@code null} for the eight SDTM {@code ItemGroupClass} values and for anything unrecognised).
     *
     * <h4>Why {@code class=} feeds the structure gate at all</h4>
     *
     * <p>
     * The harness used to treat {@code class=} and {@code structure=} as two independent axes: the
     * class axis judged only when {@code class=} was declared, the structure axis always detected
     * from the columns. <b>Production has no such split.</b> Define-XML carries the ADaM data
     * structure in the very same {@code def:Class} element that carries an SDTM observation class
     * (there is no separate ADaM {@code Class} — see
     * {@code plans/done/PLAN-adam-structure-set-valued.md} §7.1), and one value feeds both gates:
     * {@code LibraryValidator} resolves the class name from {@code getDatasetClass} while
     * {@code DatasetRuleResolver} / {@code RuleRunner} read the same declaration through
     * {@code getDeclaredDatasetClass}. A {@code .cdt} {@code class="BASIC DATA STRUCTURE"} is
     * therefore a structure declaration whether or not the author also wrote {@code structure=},
     * and treating it as one is what makes the fixture mean in the harness what it would mean in a
     * real study.
     * </p>
     *
     * <p>
     * ⚠ An explicit {@code structure=} still wins: it is the more specific statement, and the ten
     * fixtures that carry one were written precisely to exercise the declared tier.
     * </p>
     *
     * @return the declared structure token, canonicalised when derived from {@code class=} and
     *         verbatim when it came from {@code structure=}, or {@code null} when the scenario
     *         declares no ADaM structure by either route
     */
    static @Nullable String effectiveDeclaredStructure(OverlayDataTable aPrimary)
    {
        String explicit = declaredStructure(aPrimary);
        if (explicit != null)
        {
            return explicit;
        }
        Object declaredClass = aPrimary.getMetaData().getMetaData(CLASS_KEY);
        return declaredClass == null ? null
                : AdamDataStructureDetector
                        .structureTokenFromDeclaredClass(declaredClass.toString());
    }


    /** The scenario's {@code subclass=} declaration as a one-element list, or empty. */
    static List<String> declaredSubclasses(OverlayDataTable aPrimary)
    {
        Object declared = aPrimary.getMetaData().getMetaData(SUBCLASS_KEY);
        return declared != null ? List.of(declared.toString()) : List.of();
    }


    /** Whether the scenario declares either ADaM scope axis explicitly. */
    static boolean declaresAdamScope(OverlayDataTable aPrimary)
    {
        return declaredStructure(aPrimary) != null || !declaredSubclasses(aPrimary).isEmpty();
    }


    /**
     * Phase 3a.3: whether a {@code class=}-derived structure declaration has to be routed into a
     * provider slot — i.e. it exists, a gate would read it, and it disagrees with the columns. See
     * {@link #install} for why all three conditions are required.
     */
    static boolean needsClassDerivedDelivery(OverlayDataTable aPrimary, boolean aAdamScopedRule)
    {
        if (!aAdamScopedRule || declaresAdamScope(aPrimary))
        {
            return false;
        }
        String derived = effectiveDeclaredStructure(aPrimary);
        return derived != null && !classDerivedDeclarationIsRedundant(aPrimary, derived);
    }


    /**
     * Wraps {@code aDelegate} so the declared tier of {@code aPrimary} reaches {@code RuleRunner}.
     * The delegate is mandatory: there is no empty stand-in, because an empty stand-in is exactly
     * the silent availability switch-off F-R8 is about. Prefer {@link #install}, which picks the
     * slot and enforces that one exists.
     *
     * <h4>Fix #227 — why this is {@code public}</h4>
     *
     * <p>
     * The ADaM <em>template</em> suites ({@code …ruletestsuites.adam.AbstractAdamTemplateTest}) sit
     * in a sub-package and declare their data structure as a {@code CLASS_NAME} constant that,
     * until Fix #227, reached only the {@code DatasetRuleResolver}. They now set
     * {@link #STRUCTURE_KEY} on the synthesised fixture and wrap their <b>already-occupied library
     * slot</b> with this decorator — the same channel {@code CdtLoader} uses, so the harness
     * declares the way the rest of the system declares. {@link #install} is deliberately
     * <em>not</em> what they call: those suites always supply a library provider, so there is no
     * empty slot to refuse over.
     * </p>
     */
    public static MetadataProvider wrap(OverlayDataTable aPrimary, MetadataProvider aDelegate)
    {
        return new ScenarioDeclaredScopeProvider(aPrimary.getMetaData().getName(),
                effectiveDeclaredStructure(aPrimary), declaredSubclasses(aPrimary), aDelegate);
    }


    /**
     * Phase 3a.3: whether the {@code class=}-derived structure declaration would leave the ADaM
     * structure set exactly as the columns alone determine it. When it would, the declaration is
     * <em>redundant</em>: routing it to {@code RuleRunner} cannot change any verdict, so the
     * carrier-block requirement below must not be imposed for it. That matters — 543 of the 580
     * scenarios that declare an ADaM {@code class=} carry no provider block at all, and they were
     * authored for the class gate, not for this one.
     */
    private static boolean classDerivedDeclarationIsRedundant(OverlayDataTable aPrimary,
            String aDerived)
    {
        DataTableMeta meta = aPrimary.getMetaData();
        List<String> columns = new ArrayList<>(meta.getColumnCount());
        for (int i = 0; i < meta.getColumnCount(); i++)
        {
            columns.add(meta.getColumn(i).getName());
        }
        boolean defineFirst = AdamDataStructureDetector.defineFirstPreference();
        return AdamDataStructureDetector.detectAll(meta.getName(), columns, null, defineFirst)
                .equals(AdamDataStructureDetector.detectAll(meta.getName(), columns, aDerived,
                        defineFirst));
    }


    /**
     * Places the declared-tier decorator into the slot {@code RuleRunner} will consult, or returns
     * the two slots untouched when {@code aPrimary} declares no ADaM axis.
     *
     * <p>
     * The define slot wins when both are occupied, mirroring
     * {@code defineProvider != null ? defineProvider : libraryProvider}. When neither is occupied
     * the scenario is <b>rejected</b>: see the class javadoc for why the alternative — quietly
     * materialising a provider — cannot be made transparent.
     * </p>
     *
     * @param aPrimary
     *            the scenario's primary dataset, carrying the {@code structure=} /
     *            {@code subclass=} declaration
     * @param aLibrary
     *            the resolved {@code #library} / {@code #library-ref} provider, or {@code null}
     * @param aDefine
     *            the resolved {@code #define} / {@code #define-xml} provider, or {@code null}
     *            <h4>Phase 3a.3 — the {@code class=}-derived declaration</h4>
     *
     *            <p>
     *            A scenario that declares no {@code structure=} but whose {@code class=} names an
     *            ADaM data structure carries a declaration too (see
     *            {@link #effectiveDeclaredStructure}). It is routed through the same slot, but only
     *            when it can actually be observed, which needs <b>both</b>:
     *            </p>
     *            <ul>
     *            <li>{@code aAdamScopedRule} — {@code RuleRunner} consults the declared tier only
     *            for a rule carrying {@code Scope.Data_Structures} or {@code Scope.Subclasses}
     *            ({@code RuleRunner}'s structure gate is wrapped in exactly that test), so for
     *            every other rule the decoration is unobservable;</li>
     *            <li>the declaration is not redundant —
     *            {@link #classDerivedDeclarationIsRedundant}.</li>
     *            </ul>
     *
     *            <p>
     *            ⚠ Without both conditions the carrier-block refusal below would fire on 543
     *            long-standing fixtures that declare an ADaM {@code class=} purely for the class
     *            gate — a refusal over an inference the author never asked for. With them, the
     *            refusal fires only where the scenario's own declaration would otherwise reach the
     *            pre-gate and <em>not</em> the engine, which is the divergence this method exists
     *            to prevent.
     *            </p>
     *
     * @param aSource
     *            the scenario's source label, for the error message
     * @param aAdamScopedRule
     *            whether the scenario's rule carries {@code Scope.Data_Structures} /
     *            {@code Scope.Subclasses} — the only gates that read the declared tier
     * @return the slots to hand to {@code RuleRunner}
     * @throws IllegalStateException
     *             when the scenario declares an axis but carries no provider block to deliver it
     */
    static Slots install(OverlayDataTable aPrimary, @Nullable MetadataProvider aLibrary,
            @Nullable MetadataProvider aDefine, String aSource, boolean aAdamScopedRule)
    {
        if (!declaresAdamScope(aPrimary) && !needsClassDerivedDelivery(aPrimary, aAdamScopedRule))
        {
            return new Slots(aLibrary, aDefine);
        }
        if (aDefine != null)
        {
            return new Slots(aLibrary, wrap(aPrimary, aDefine));
        }
        if (aLibrary != null)
        {
            return new Slots(wrap(aPrimary, aLibrary), null);
        }
        throw new IllegalStateException(aSource + ": dataset " + aPrimary.getMetaData().getName()
                + " declares structure= / subclass= (or a class= that names an ADaM data structure"
                + " and contradicts the columns) but the"
                + " scenario has no #define, #define-xml, #library or #library-ref block to carry"
                + " it. RuleRunner reads the declared tier from"
                + " `defineProvider != null ? defineProvider : libraryProvider`, so the declaration"
                + " can only be delivered by decorating one of those two slots — and occupying an"
                + " empty slot would switch that level's availability self-skips off (a"
                + " library-dependent rule would execute instead of reporting \"no Library"
                + " access\"). Add a carrier block: '#define standard=<std> version=<ver>' is the"
                + " right one for a pure declaration (the declared tier is the Define-XML"
                + " def:Class / def:SubClass channel), or '#library …' when the rule needs Library"
                + " metadata anyway.");
    }

    // ---- the two declared-tier accessors this class exists for ---------------------


    @Override
    public @Nullable String getDeclaredDatasetClass(String aDatasetName)
    {
        if (declaredClass != null && datasetName.equalsIgnoreCase(aDatasetName))
        {
            return declaredClass;
        }
        return base.getDeclaredDatasetClass(aDatasetName);
    }


    @Override
    public List<String> getDeclaredSubClasses(String aDatasetName)
    {
        if (!declaredSubClasses.isEmpty() && datasetName.equalsIgnoreCase(aDatasetName))
        {
            return declaredSubClasses;
        }
        return base.getDeclaredSubClasses(aDatasetName);
    }

    // ---- pure delegation ----------------------------------------------------------


    @Override
    public List<String> getRequiredVariables(String aDomain)
    {
        return base.getRequiredVariables(aDomain);
    }


    @Override
    public List<String> getExpectedVariables(String aDomain)
    {
        return base.getExpectedVariables(aDomain);
    }

    // ⚠⚠ Fix #368: the structure-keyed trio must be delegated. Their interface defaults say "I
    // cannot answer", so omitting them here makes EVERY .cdt scenario fall back to the domain-keyed
    // lookup — which is how the first re-armed PMDA-AD0047 fixtures reported "expected a violation
    // but got none" against an engine that was already fixed. A scenario harness that silently
    // disables the code path under test is worse than no fixture.


    @Override
    public boolean supportsStructureKeyedVariables()
    {
        return base.supportsStructureKeyedVariables();
    }


    /**
     * Fix #369 — delegated to {@code base}. ⚠ This decorator is exactly where Fix #368 lost its
     * second cycle: it inherited the new defaults, every {@code .cdt} scenario silently took the
     * pre-fix path, and nothing failed. Do not let a capability method be added to
     * {@code MetadataProvider} without a line here.
     */
    @Override
    public boolean isLibraryUnavailable()
    {
        return base.isLibraryUnavailable();
    }

    // ⚠ Phase 3 of PLAN-metadata-product-selection: the TWO-ARG form is the primary one. Forwarding
    // only the one-arg convenience would silently strip the subclass from every .cdt scenario.


    @Override
    public @Nullable List<String> getRequiredVariablesForStructure(String aStructureToken,
            List<String> aSubclassTokens)
    {
        return base.getRequiredVariablesForStructure(aStructureToken, aSubclassTokens);
    }


    @Override
    public @Nullable List<String> getExpectedVariablesForStructure(String aStructureToken,
            List<String> aSubclassTokens)
    {
        return base.getExpectedVariablesForStructure(aStructureToken, aSubclassTokens);
    }


    /**
     * Review finding F6 — the structure-keyed provenance, delegated. Log-only in effect, but the
     * log it feeds is {@code OperationExecutor}'s Fix #369 SKIP diagnostic: without this override
     * every {@code .cdt} scenario reported {@code declared product(s) <unknown>}, in the very
     * harness this area is debugged from.
     */
    @Override
    public List<String> declaredStructureKeyedProducts()
    {
        return base.declaredStructureKeyedProducts();
    }


    @Override
    public List<String> getColumnOrder(String aDomain)
    {
        return base.getColumnOrder(aDomain);
    }


    @Override
    public List<String> getKeyVariables(String aDomain)
    {
        return base.getKeyVariables(aDomain);
    }


    @Override
    public List<String> getDatasetNames()
    {
        return base.getDatasetNames();
    }


    @Override
    public @Nullable List<String> getStandardVariableNames()
    {
        return base.getStandardVariableNames();
    }


    @Override
    public @Nullable List<String> getStandardDatasetNames()
    {
        return base.getStandardDatasetNames();
    }


    /** <b>R11</b> — delegated: this harness overrides scope declarations only. */
    @Override
    public List<PublishedVariable> getPublishedVariablesByName(String variableName)
    {
        return base.getPublishedVariablesByName(variableName);
    }


    @Override
    public List<String> getModelColumnOrder(String aDomain)
    {
        return base.getModelColumnOrder(aDomain);
    }


    @Override
    public boolean isDomainCustom(String aDomain)
    {
        return base.isDomainCustom(aDomain);
    }


    @Override
    public List<String> getCodelistTerms(String aCodelistCode)
    {
        return base.getCodelistTerms(aCodelistCode);
    }


    @Override
    public Map<String, String> getVariableMetadata(String aDomain, String aVariable)
    {
        return base.getVariableMetadata(aDomain, aVariable);
    }


    @Override
    public List<Map<String, String>> getDomainVariables(String aDomain)
    {
        return base.getDomainVariables(aDomain);
    }


    @Override
    public List<Map<String, String>> getModelVariables(String aDomain)
    {
        return base.getModelVariables(aDomain);
    }


    @Override
    public List<Map<String, String>> getModelVariablesForClass(String aModelClass)
    {
        return base.getModelVariablesForClass(aModelClass);
    }


    @Override
    public List<String> getPublishedCtPackages()
    {
        return base.getPublishedCtPackages();
    }


    @Override
    public List<String> getCodelistAttribute(String aCtPackageId, String aCtAttribute)
    {
        return base.getCodelistAttribute(aCtPackageId, aCtAttribute);
    }


    @Override
    public Map<String, String> getDatasetMetadata(String aDomain)
    {
        return base.getDatasetMetadata(aDomain);
    }


    @Override
    public java.util.Optional<Boolean> isCodelistExtensible(String aCodelistName)
    {
        return base.isCodelistExtensible(aCodelistName);
    }


    @Override
    public Map<String, String> getCodelistTermMappings(String aCodelistName)
    {
        return base.getCodelistTermMappings(aCodelistName);
    }


    /**
     * ⚠⚠ Delegated — the sibling of the four codelist accessors around it, and the one that was
     * missed. Its interface default is {@code Optional.empty()} ("I have no rich codelist view"),
     * which is honest for a leaf and silently wrong here: only the {@code (term, value)} shape of
     * {@code codelist_terms} is served through {@link MetadataProvider#getCodelistTerms}; every
     * other {@code (level, returntype)} shape reads this accessor, and an empty answer becomes
     * {@code LIBRARY_NOT_AVAILABLE} ⇒ a SKIP. {@link #install} puts this decorator on the
     * <b>library</b> slot for every ADaM {@code structure=} / {@code subclass=} scenario that has
     * no define block, so inheriting the default made those scenarios SKIP the shape under test
     * while an {@code expect=noViolation} scenario went green anyway — the exact Fix #368 failure
     * mode recorded above, one accessor later.
     */
    @Override
    public Optional<ICodeList> getCodelist(String aCodelistName)
    {
        return base.getCodelist(aCodelistName);
    }


    @Override
    public Map<String, String> getCodelistCodeMap(String aDomain, String aVariable)
    {
        return base.getCodelistCodeMap(aDomain, aVariable);
    }


    @Override
    public @Nullable String getStandard()
    {
        return base.getStandard();
    }


    @Override
    public @Nullable String getVersion()
    {
        return base.getVersion();
    }


    @Override
    public @Nullable String getDefineVersion()
    {
        return base.getDefineVersion();
    }


    @Override
    public @Nullable List<String> getStandardModelVariables(IDataTable aTable,
            DatasetResolver aResolver)
    {
        return base.getStandardModelVariables(aTable, aResolver);
    }


    @Override
    public @Nullable List<Map<String, String>> getStandardModelVariablesDetailed(IDataTable aTable,
            DatasetResolver aResolver)
    {
        return base.getStandardModelVariablesDetailed(aTable, aResolver);
    }


    @Override
    public @Nullable List<Map<String, String>> getStandardModelVariablesForClass(IDataTable aTable,
            DatasetResolver aResolver, String aModelClass)
    {
        return base.getStandardModelVariablesForClass(aTable, aResolver, aModelClass);
    }


    @Override
    public @Nullable List<Map<String, String>> getStandardVariablesDetailed(IDataTable aTable,
            DatasetResolver aResolver)
    {
        return base.getStandardVariablesDetailed(aTable, aResolver);
    }


    @Override
    public @Nullable String getDatasetClass(String aDomain)
    {
        return base.getDatasetClass(aDomain);
    }


    @Override
    public @Nullable String getDatasetClass(@Nullable String aMemberName, String aCdiscDomain)
    {
        return base.getDatasetClass(aMemberName, aCdiscDomain);
    }


    @Override
    public @Nullable String getDatasetClass(@Nullable String aMemberName, String aCdiscDomain,
            @Nullable Set<String> aActualColumns)
    {
        return base.getDatasetClass(aMemberName, aCdiscDomain, aActualColumns);
    }

}
