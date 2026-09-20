package net.cumba.corej.core.rulespec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.OperationExecutor;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.exec.ScopeMatcher;
import net.cumba.corej.core.exec.StubMetadataProvider;
import net.cumba.corej.core.metadata.AdamDataStructureDetector;
import net.cumba.corej.core.metadata.AdamSubclassDetector;
import net.cumba.corej.core.metadata.CdiscDomainResolver;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * End-to-end rulespec executor for the Java engine: loads a spec YAML, runs the rule / check
 * against its fixture datasets, and asserts the three channels (violations, status, errors) against
 * the spec's Java-canonical expectations.
 *
 * <p>
 * Only {@link Mode#ASSERT} is implemented; {@link #run(Path, Mode)} rejects any other mode.
 * {@code CAPTURE} (writing observed results back into the spec YAML) and {@code DIFF} (cross-engine
 * diffing, to be orchestrated from the Python side) were milestones of the fork-era parity plan;
 * the fork and its lane were removed in wave 33 and neither mode was ever implemented on the Java
 * lane. The enum constants remain as no-op vocabulary — passing them throws
 * {@link UnsupportedOperationException}.
 */
public final class SpecRunner
{

    public enum Mode
    {
        ASSERT, CAPTURE, DIFF
    }


    /**
     * Outcome of running one spec against the Java engine.
     *
     * <p>
     * Three assertion channels, <b>all always on</b>: {@link SpecResult#violationsMatch()},
     * {@link SpecResult#statusMatches()} and {@link SpecResult#errorsMatch()}. The status channel
     * had an opt-in gate ({@code -Dparity.assertStatus} / {@code PARITY_ASSERT_STATUS}) while the
     * corpus was being triaged; PLAN-parity-expected-status P4 removed it deliberately, because a
     * field that can be switched off is a field that dies again — and being switchable-off is
     * precisely how {@code expected_status} came to be declared-but-never-read for the whole of M1.
     * The errors channel (PLAN-parity-expected-errors) is on for the same reason and never had a
     * toggle at all. Do not reintroduce one.
     */
    public record SpecResult(String specName, List<CanonicalViolation> observed,
            List<String> observedErrors, List<CanonicalViolation> expected,
            List<String> expectedErrors, Set<String> diffMissingFromObserved,
            Set<String> diffUnexpectedInObserved, RuleExecutionStatus engineStatus,
            String engineStatusMessage,
            /* The spec's declared expectation; null ⇒ EXECUTED. */
            @Nullable RuleExecutionStatus expectedStatus,
            /*
             * Classified failure reasons (ViolationNormaliser.ErrorReason names), sorted and
             * de-duplicated. Compared against expectedErrors by errorsMatch(); NOT the same thing
             * as observedErrors, which is engine prose kept only for display.
             */
            List<String> observedReasons)
    {

        /** The violation channel — what {@link #passed()} used to mean on its own. */
        public boolean violationsMatch()
        {
            return diffMissingFromObserved.isEmpty() && diffUnexpectedInObserved.isEmpty();
        }


        /**
         * The status channel: did the rule run the way the spec says it should? Absent expectation
         * and absent observation both mean {@link RuleExecutionStatus#EXECUTED}.
         */
        public boolean statusMatches()
        {
            return orExecuted(expectedStatus) == orExecuted(engineStatus);
        }


        /**
         * The errors channel: did the rule fail for the reason the spec says it should?
         *
         * <p>
         * Compares classified reason <b>tokens</b>, never engine prose — see
         * {@link ViolationNormaliser#reasonsFrom}. Both sides are sorted and de-duplicated, so this
         * asserts <i>which</i> reasons occurred rather than how many datasets produced each.
         *
         * <p>
         * This is not redundant with {@link #statusMatches()} even though a non-empty reason list
         * implies {@code ERROR}: status says only <i>that</i> the rule errored, this says
         * <i>why</i>. A spec that starts erroring for a different cause keeps its status pin green
         * and turns this one red.
         */
        public boolean errorsMatch()
        {
            return normalise(observedReasons).equals(normalise(expectedErrors));
        }


        private static List<String> normalise(@Nullable List<String> values)
        {
            return values == null ? List.of() : new TreeSet<>(values).stream().toList();
        }


        public boolean passed()
        {
            return violationsMatch() && statusMatches() && errorsMatch();
        }


        private static RuleExecutionStatus orExecuted(@Nullable RuleExecutionStatus aStatus)
        {
            return aStatus == null ? RuleExecutionStatus.EXECUTED : aStatus;
        }


        public String formatDiff()
        {
            StringBuilder sb = new StringBuilder("Spec '").append(specName).append("' diff:");
            if (engineStatus != null && engineStatus != RuleExecutionStatus.EXECUTED)
            {
                sb.append("\n  engineStatus=").append(engineStatus);
                if (engineStatusMessage != null)
                {
                    sb.append(" (").append(engineStatusMessage).append(')');
                }
            }
            if (!statusMatches())
            {
                // Worded identically to the Python lane's format_diff.
                sb.append("\n  status: expected=").append(orExecuted(expectedStatus))
                        .append(" observed=").append(orExecuted(engineStatus));
            }
            if (!errorsMatch())
            {
                sb.append("\n  errors: expected=").append(normalise(expectedErrors))
                        .append(" observed=").append(normalise(observedReasons));
                if (observedErrors != null && !observedErrors.isEmpty())
                {
                    // The prose, shown only here and only on a divergence. The token says which
                    // CLASS of failure occurred; this says what the engine actually reported, which
                    // is what a human needs to triage it. Keeping prose out of the assertion but in
                    // the diagnostic is the whole point of Option B.
                    sb.append("\n    engine message(s): ").append(observedErrors);
                }
            }
            for (String v : diffMissingFromObserved)
            {
                sb.append("\n  - missing: ").append(v);
            }
            for (String v : diffUnexpectedInObserved)
            {
                sb.append("\n  + unexpected: ").append(v);
            }
            return sb.toString();
        }
    }

    private SpecRunner()
    {
    }


    public static SpecResult run(Path specFile, Mode mode) throws IOException
    {
        if (mode != Mode.ASSERT)
        {
            throw new UnsupportedOperationException("M1 supports ASSERT only; mode=" + mode);
        }
        SpecSchema spec = SpecLoader.load(specFile);
        return executeAssert(spec);
    }


    /** Test-side entry point that takes a parsed spec directly. Avoids file I/O for unit tests. */
    public static SpecResult runSchema(SpecSchema spec) throws IOException
    {
        return executeAssert(spec);
    }


    private static SpecResult executeAssert(SpecSchema spec) throws IOException
    {
        Map<String, IDataTable> tables = FixtureLoader.build(spec.datasets());

        // Resolve primary domain. For kind=rule, prefer the rule's own scope; for
        // kind=check_operator/operation use scope_override or the first declared dataset.
        String primaryDomain = pickPrimary(spec, tables);

        Rule rule = RuleScaffold.build(spec, primaryDomain);

        // For kind=rule, the explicit scope wins; re-derive primary from it.
        if (spec.kind() == SpecSchema.Kind.RULE)
        {
            String fromRule = RuleScaffold.primaryDomainOf(rule);
            if (fromRule != null && tables.containsKey(fromRule))
            {
                primaryDomain = fromRule;
            }
        }

        IDataTable primaryTable = tables.get(primaryDomain);
        if (primaryTable == null)
        {
            throw new IllegalStateException("Primary domain '" + primaryDomain
                    + "' not declared in spec.datasets for spec '" + spec.name() + "'");
        }

        DatasetResolver resolver = inventoryResolver(tables);
        MetadataProvider mock = MockLibraryProvider.of(spec.libraryMocks(), spec.config());
        MetadataProvider stored = libraryProviderFor(spec.config());
        MetadataProvider library = stored == null ? mock
                : new CompositeMetadataProvider(mock, stored);

        List<String> rowKey = spec.datasets().get(primaryDomain).rowKey();
        if (rowKey == null)
        {
            rowKey = List.of();
        }

        // Scope check, mirroring Python's `is_suitable_for_validation`. The Java engine's
        // production rule-execution path (DatasetRuleResolver) consults ScopeMatcher upstream of
        // RuleRunner; the parity-test framework used to bypass that filter and call
        // RuleRunner directly, which made rules with `Domains.Exclude` /
        // `include_split_datasets` constraints fire in tests on datasets that wouldn't be
        // in scope in production. Apply the same gate here so observed parity-test
        // behaviour matches both production Java AND Python's parity framework
        // (validate_single_rule → is_suitable_for_validation → rule_applies_to_domain).
        // For RULE-kind specs only — operator/operation specs intentionally bypass scope.
        // Split detection is data-driven (mirrors Python SDTMDatasetMetadata.is_split): pass the
        // primary dataset's canonical base name, read from its DOMAIN/RDOMAIN columns, so a
        // letter-suffix split like FAAE (DOMAIN=FA) is recognised as a split of FA exactly as the
        // production DatasetRuleResolver path now does.
        String primaryUnsplit = OperationExecutor.unsplitNameFromData(primaryTable);
        // ⭐ The CDISC DOMAIN CODE of the primary dataset — NOT its member name. Production derives
        // it once per dataset in LibraryValidator.validateDataset
        // (`CdiscDomainResolver.cdiscDomainOf(table)`, LibraryValidator.java:1063) and then uses
        // that one value twice: as the resolver's domain name for scope matching
        // (`generator.setDomainName(cdiscDomain)`, :1080) and as the execution-time `--`
        // substitution prefix (`String domainPrefix = cdiscDomain;`, :1125 → `RuleRunner.execute(
        // rule, table, resolver, domainPrefix, …)`, :1228). The resolver reads the row-0 DOMAIN
        // value first and falls back to `SplitDatasetUtil.unsplitName(memberName)`.
        //
        // ⚠ For a SPLIT dataset the member name and the domain code DIFFER — FAAE carrying
        // DOMAIN=FA has code FA — and this harness used to hand the member name (the spec's
        // dataset KEY) to `RuleRunner.execute`. Every `--` in the rule then specialised to the
        // member name, so `var_exists("--LNKGRP")` on FAAE asked for FAAELNKGRP: permanently
        // false, and any conjunct behind it permanently unreachable. Measured on
        // EC-variable-count-split-family-dedup,
        // which was green with `$VARIABLE_COUNT == 2` AND with `== 1`, i.e. green with its entire
        // subject masked. The dataset KEY remains the lookup key into `tables`, the row-key
        // source and the canonical violation's `dataset` field (production reports the member
        // name there too); only the DOMAIN handed to the engine changes.
        String cdiscDomain = CdiscDomainResolver.cdiscDomainOf(primaryTable);
        if (cdiscDomain.isEmpty())
        {
            // cdiscDomainOf answers "" only for a nameless, DOMAIN-less table. The engine treats a
            // blank prefix as "substitute nothing", which would silently disarm `--` resolution;
            // the member name is what this harness passed before, so fall back to it rather than
            // change behaviour for such a fixture.
            cdiscDomain = primaryDomain;
        }
        // ⭐ The scope gate below takes the MEMBER name, never `cdiscDomain`, and since 2026-09-17
        // that is exactly what production does too — this axis is a mirror, not a divergence.
        // `ScopeMatcher.describeDomainMismatch(rule, domainName, unsplitName)` derives
        // `isSplit = !domainName.equals(unsplitName)`: its `domainName` parameter means the MEMBER
        // name (its javadoc: "the dataset name (e.g. \"DM\", \"FAAE\")"), while `unsplitName` is
        // `OperationExecutor.unsplitNameFromData`.
        //
        // ⚠ History worth keeping, because the conflation is easy to reintroduce: production USED
        // to hand `LibraryValidator`'s `cdiscDomain` (via `setDomainName`) to BOTH parameters.
        // Both derivations read the row-0 DOMAIN value first, so for every dataset carrying a
        // DOMAIN column they were equal by construction — split detection was dead,
        // `Include_Split_Datasets: true` could never match, and the member-name leg of the
        // Include/Exclude test (`AP--` against `APAE`) was gone. Measured 2026-09-16: mirroring
        // that conflation here turned four live specs (CORE-000181 / -000510 / -000540 / -000778)
        // into vacuous SKIPs, so this harness kept the member name and the defect was routed to
        // the engine repo instead. It was fixed there on 2026-09-17 —
        // `DatasetRuleResolver.describeScopeSkip` now passes (member name, canonical base), guarded
        // by `net.cumba.corej.core.report.SplitScopeProductionPathTest` (D125–D125e,
        // PLAN-typed-expression-engine phase 7c). The four specs above are unchanged and green;
        // they are now evidence that the two lanes agree.
        String scopeSkipReason = spec.kind() == SpecSchema.Kind.RULE
                ? describeSpecScopeMismatch(rule, primaryDomain, primaryUnsplit, primaryTable,
                        library)
                : null;
        if (scopeSkipReason != null)
        {
            List<CanonicalViolation> emptyObserved = List.of();
            List<String> emptyErrors = List.of();
            List<CanonicalViolation> expectedSkip = spec.expectedViolations() != null
                    ? spec.expectedViolations()
                    : List.of();
            List<String> expectedErrorsSkip = spec.expectedErrors() != null ? spec.expectedErrors()
                    : List.of();
            Set<String> obsSkip = toJsonSet(emptyObserved);
            Set<String> expSkip = toJsonSet(expectedSkip);
            Set<String> missingSkip = new TreeSet<>(expSkip);
            missingSkip.removeAll(obsSkip);
            Set<String> unexpectedSkip = new TreeSet<>(obsSkip);
            unexpectedSkip.removeAll(expSkip);
            return new SpecResult(spec.name(), emptyObserved, emptyErrors, expectedSkip,
                    expectedErrorsSkip, missingSkip, unexpectedSkip, RuleExecutionStatus.SKIPPED,
                    "Rule skipped — " + scopeSkipReason + " (primary dataset '" + primaryDomain
                            + "')",
                    spec.expectedStatus(),
                    // ...and no Check evaluated means no failure reason either. A scope skip is
                    // SKIPPED, never ERROR, so the errors channel is empty by construction here.
                    List.of());
        }

        // (see describeSpecScopeMismatch below for the axes this pre-gate covers)

        // Value resolution is strict: a bareword value operand is a COLUMN reference, and one
        // that resolves to nothing yields null rather than falling back to a literal string.
        // Python's rule processor is permissive here, so specs must declare literals explicitly
        // (value_is_literal: true) to line the two engines up — see
        // documentation/parity-native-verdict-divergences.md.
        // ⛔ The NativeExecutionRecorder session that used to wrap this call is GONE (owner,
        // 2026-09-18): it was the legacy engine's decommissioning instrument, its Backend enum had
        // exactly one constant left, and the pin it fed (RuleExecutionSpecTest's allMatch(NATIVE))
        // could not fail. There is no fallback engine to detect any more — a rule with no native
        // checkExpr surfaces as a loadError ERROR through the normal expected/observed diff.
        // Define-XML mock (PLAN-define-item-metadata-parity-929-1081): a spec may declare a
        // `define_xml` block (per-dataset define_variable_* record arrays, or — since
        // PLAN-parity-define-dataset-channel P1 — a define_dataset_* attribute object) for the
        // Define-Item-Metadata / Dataset-Metadata-against-Define rules and the
        // define_dataset_names operation. Build a define-level provider from it and pass it on the
        // 9-arg execute; absent ⇒ null (every other spec is unaffected).
        net.cumba.corej.core.exec.MetadataProvider defineProvider = defineProviderFrom(spec.name(),
                spec.defineXml());
        RuleExecutionResult result = RuleRunner.execute(rule, primaryTable, resolver, cdiscDomain,
                library, /*joinCache=*/null, defineProvider);

        List<CanonicalViolation> observed = ViolationNormaliser.normalise(result, primaryTable,
                primaryDomain, rowKey);
        List<String> observedErrors = ViolationNormaliser.errorsFrom(result);
        List<String> observedReasons = ViolationNormaliser.reasonsFrom(result, rule);

        List<CanonicalViolation> expected = spec.expectedViolations() != null
                ? spec.expectedViolations()
                : List.of();
        List<String> expectedErrors = spec.expectedErrors() != null ? spec.expectedErrors()
                : List.of();

        Set<String> obsJson = toJsonSet(observed);
        Set<String> expJson = toJsonSet(expected);

        Set<String> missing = new TreeSet<>(expJson);
        missing.removeAll(obsJson);
        Set<String> unexpected = new TreeSet<>(obsJson);
        unexpected.removeAll(expJson);

        // `expectedErrors` is compared against `observedReasons` (classified tokens), NOT against
        // `observedErrors` (engine prose) — PLAN-parity-expected-errors D1/Option B. The raw
        // messages ride along only so formatDiff can show them.
        return new SpecResult(spec.name(), observed, observedErrors, expected, expectedErrors,
                missing, unexpected, result.getStatus(), result.getStatusMessage(),
                spec.expectedStatus(), observedReasons);
    }


    /**
     * The scope verdict for a {@code kind: rule} spec's primary dataset, or {@code null} when in
     * scope. Mirrors Python's {@code is_suitable_for_validation} chain:
     * {@code rule_applies_to_domain} → {@code rule_applies_to_data_structure} →
     * {@code rule_applies_to_subclass}, plus the Java-only {@code Scope.Datasets} axis, which
     * {@code DatasetRuleResolver.describeScopeSkip} evaluates immediately after {@code Domains}.
     *
     * <p>
     * <b>Fix #235 — the declared tier is now consulted, exactly as production does it.</b> This
     * gate used to call the detectors through their column-only overloads, on the rationale that
     * {@code MockLibraryProvider} fed {@code getDatasetClass} and not
     * {@code getDeclaredDatasetClass}, "so both lanes see exactly the same evidence and the
     * Java-only declared-tier divergence (EC-48) stays outside the parity contract". <b>That
     * rationale has lapsed twice over.</b> Wave 33 removed the Python lane (its {@code py/} tree
     * and the {@code PyParity} profile are both gone), so there is no second lane to hold
     * evidence-identical; and {@code MockLibraryProvider} now <em>does</em> answer
     * {@code getDeclaredDatasetClass}, from the {@code library_mocks.classes} block the specs
     * already carry.
     * </p>
     *
     * <p>
     * ⚠ <b>The two gates must agree or the declaration is inert.</b>
     * {@link net.cumba.corej.core.exec.RuleRunner} re-applies these axes at execution time through
     * {@code detectAll(name, columns, declaredClass, defineFirst)} /
     * {@code AdamSubclassDetector.resolve(…)}. Had this pre-gate stayed column-only it would have
     * rejected on {@code ADAM OTHER} before {@code RuleRunner} ever saw the declaration — the rule
     * would still be held, just with a different message. So it mirrors the production call pair
     * verbatim, including the <b>set</b>-valued {@code detectAll} (Fix #179): a declared
     * {@code MEDICAL DEVICE BASIC DATA STRUCTURE} must keep matching a rule scoped to the base
     * {@code BASIC DATA STRUCTURE}, which the previous single-token {@code detect} could not
     * express. With no declaration present {@code detectAll} is the heuristic token plus its
     * (empty) supertype chain, so specs that declare no ADaM structure are unaffected.
     * </p>
     *
     * <p>
     * {@link net.cumba.corej.core.exec.RuleRunner} applies the same two ADaM axes at execution
     * time, so this pre-gate does not change <em>whether</em> a spec is skipped — it names the axis
     * in the {@link SpecResult} instead of surfacing a mid-execution SKIPPED.
     * </p>
     */
    private static @Nullable String describeSpecScopeMismatch(Rule rule, String primaryDomain,
            String primaryUnsplit, IDataTable primaryTable,
            @Nullable MetadataProvider libraryProvider)
    {
        // ⚠ `primaryDomain` here is the MEMBER name, which is what this three-argument describer's
        // first parameter means (`isSplit = !domainName.equals(unsplitName)`), NOT the CDISC domain
        // code the `--` prefix uses. See the note at the call site for why this deliberately does
        // not follow production's `setDomainName(cdiscDomain)`.
        String mismatch = ScopeMatcher.describeDomainMismatch(rule, primaryDomain, primaryUnsplit);
        if (mismatch != null)
        {
            return mismatch;
        }
        // Scope.Datasets (owner requirement #5, PLAN-scope-requirements-split phase 5) —
        // immediately after Domains, exactly where DatasetRuleResolver.describeScopeSkip puts it,
        // and on
        // the MEMBER name with no split-base re-test. This mirror is "call-for-call" (Fix #235) or
        // it is not a mirror: an axis missing here is an axis this pre-gate does not judge, and a
        // spec would then pass because the gate was off for it rather than because the rule was in
        // scope. Inert while the corpus ships zero Scope.Datasets carriers — which is exactly why
        // it must be wired before the first one appears.
        String primaryName = primaryTable.getMetaData().getName() != null
                ? primaryTable.getMetaData().getName()
                : primaryDomain;
        mismatch = ScopeMatcher.describeDatasetMismatch(rule, primaryName);
        if (mismatch != null)
        {
            return mismatch;
        }
        List<String> columns = scopeColumnNames(primaryTable);
        // Fix #235: mirrors RuleRunner's structure/subclass gate call-for-call — the declared
        // def:Class tier (from library_mocks.classes, when it names an ADaM data structure) plus
        // the Fix #179 set-valued detectAll, under the same corej.defineFirst preference.
        boolean defineFirst = AdamDataStructureDetector.defineFirstPreference();
        String declaredClass = libraryProvider == null ? null
                : libraryProvider.getDeclaredDatasetClass(primaryDomain);
        List<String> declaredSubClasses = libraryProvider == null ? List.of()
                : libraryProvider.getDeclaredSubClasses(primaryDomain);
        List<String> structures = AdamDataStructureDetector.detectAll(primaryDomain, columns,
                declaredClass, defineFirst);
        mismatch = ScopeMatcher.describeDataStructureMismatch(rule, structures);
        if (mismatch != null)
        {
            return mismatch;
        }
        return ScopeMatcher.describeSubclassMismatch(rule, AdamSubclassDetector
                .resolve(primaryDomain, structures, columns, declaredSubClasses, defineFirst));
    }


    /** Column names in declared order — mirrors {@code DatasetRuleResolver.doGenerate}. */
    private static List<String> scopeColumnNames(IDataTable table)
    {
        var meta = table.getMetaData();
        List<String> names = new ArrayList<>(meta.getColumnCount());
        for (int i = 0; i < meta.getColumnCount(); i++)
        {
            names.add(meta.getColumn(i).getName());
        }
        return names;
    }


    private static String pickPrimary(SpecSchema spec, Map<String, IDataTable> tables)
    {
        if (spec.kind() != SpecSchema.Kind.RULE && spec.scopeOverride() != null
                && spec.scopeOverride().domainsInclude() != null
                && !spec.scopeOverride().domainsInclude().isEmpty())
        {
            String preferred = spec.scopeOverride().domainsInclude().get(0);
            if (tables.containsKey(preferred))
            {
                return preferred;
            }
        }
        if (spec.kind() == SpecSchema.Kind.RULE && spec.rule() != null)
        {
            // Sniff Scope.Domains.Include from the raw rule node before deserialisation.
            var node = spec.rule().path("Scope").path("Domains").path("Include");
            if (node.isArray())
            {
                for (var element : node)
                {
                    String s = element.asText();
                    if (tables.containsKey(s))
                    {
                        return s;
                    }
                }
            }
        }
        if (tables.isEmpty())
        {
            throw new IllegalArgumentException("Spec '" + spec.name() + "' declares no datasets");
        }
        return tables.keySet().iterator().next();
    }


    /**
     * Builds an offline store-backed library provider for the spec's standard/version, or
     * {@code null} when unavailable (no seeded store, unsupported standard) — callers then fall
     * back to the mock alone. The store is seeded once per JVM from the pickle corpus by
     * {@link MetadataStoreFixture} (cache plan step 8g — this harness no longer reads pickles
     * directly). SDTM-family only, exactly as the pickle-backed provider it replaced (the ADaM path
     * is not wired in the harness).
     */
    // Package-private (was private) so LibraryProviderGuardTest can assert the provider actually
    // resolves: 61 specs declare no `library_mocks` and are SDTM-family, so for exactly those the
    // seeded store is the ONLY library provider — without it RuleRunner's library gates flip them
    // EXECUTED → SKIPPED and every expected_status pin silently becomes invalid.
    static @Nullable MetadataProvider libraryProviderFor(SpecSchema.SpecConfig aConfig)
    {
        if (aConfig == null || aConfig.standard() == null || aConfig.standardVersion() == null)
        {
            return null;
        }
        String lower = aConfig.standard().toLowerCase(Locale.ROOT);
        if (!lower.equals("sdtmig") && !lower.equals("sendig") && !lower.equals("sdtm"))
        {
            return null;
        }
        StoreMetadataProviderFactory factory = MetadataStoreFixture.factory().orElse(null);
        if (factory == null)
        {
            return null;
        }
        // The pickle-era harness passed only the FIRST declared CT package; kept identical so no
        // spec's codelist environment changes with the store migration.
        List<String> ctPackages = aConfig.ctPackages();
        String ctPackage = ctPackages != null && !ctPackages.isEmpty() ? ctPackages.get(0) : null;
        return factory.forSdtm(aConfig.standard(), aConfig.standardVersion(),
                ctPackage == null ? List.<String> of() : List.of(ctPackage)).orElse(null);
    }


    /**
     * {@link DatasetResolver.WithInventory} backed by the loaded fixture map. Required by the
     * engine's {@code RuleRunner} for Domain Presence Check rules and any cross-dataset Operation
     * that enumerates the study (e.g. {@code variable_count}).
     */
    private static DatasetResolver inventoryResolver(Map<String, IDataTable> tables)
    {
        return new DatasetResolver.WithInventory()
        {

            private final Set<String> available = new LinkedHashSet<>(tables.keySet());

            @Override
            public IDataTable resolve(String name)
            {
                return tables.get(name);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return available;
            }
        };
    }


    private static Set<String> toJsonSet(List<CanonicalViolation> list)
    {
        Set<String> out = new LinkedHashSet<>();
        if (list == null)
        {
            return out;
        }
        for (CanonicalViolation v : list)
        {
            out.add(CanonicalJson.toJson(v));
        }
        return out;
    }


    /** Extra accessor for tests that want to peek at the rule mid-flight. Currently unused. */
    static List<CanonicalViolation> sortedSet(List<CanonicalViolation> list)
    {
        List<CanonicalViolation> sorted = new ArrayList<>(list);
        sorted.sort(java.util.Comparator.comparing(CanonicalJson::toJson));
        return sorted;
    }


    // Placeholder for future use; unused in M1.
    @SuppressWarnings("unused")
    private static Map<String, Object> emptyMap()
    {
        return new LinkedHashMap<>();
    }


    /**
     * Builds a define-level {@link MetadataProvider} from a spec's {@code define_xml} block: an
     * object keyed by <b>dataset name</b>, each value either
     *
     * <ul>
     * <li>an ARRAY of {@code {define_variable_*: ...}} maps — the variable level, whose key is
     * resolved as a <em>domain</em> (unchanged legacy behaviour). The {@code define_variable_*}
     * keys are translated to the engine's provider-key channel
     * ({@code define_variable_name}&rarr;{@code name},
     * {@code define_variable_data_type}&rarr;{@code simpleDatatype},
     * {@code define_variable_codelist_coded_codes} (a JSON array) carried verbatim, …); or</li>
     * <li>an OBJECT of {@code define_dataset_*} attributes — the dataset level, keyed by dataset
     * NAME (so a {@code SUPPAE} key, where the name deliberately differs from the domain, works),
     * translated through {@link SpecDefineXml#datasetAttrs}.</li>
     * </ul>
     *
     * <p>
     * <b>Every</b> key is registered on the provider's Define dataset-name list <em>before</em> the
     * shape branch (D3/D6): a variable-only block must still yield a non-empty
     * {@code getDatasetNames()}, or {@code define_dataset_names} would report an empty list as a
     * successful execution and {@code FDA-SD1063} would fire on every dataset. Returns {@code null}
     * when the spec declares no define block; an unusable block throws rather than being skipped
     * silently (the pre-P1 code returned from the lambda, which dropped one key without a word).
     */
    static net.cumba.corej.core.exec.@org.jspecify.annotations.Nullable MetadataProvider defineProviderFrom(
            @Nullable String specName,
            com.fasterxml.jackson.databind.@org.jspecify.annotations.Nullable JsonNode defineXml)
    {
        SpecDefineXml.validate(defineXml, specName);
        if (defineXml == null || !defineXml.isObject())
        {
            return null;
        }
        StubMetadataProvider provider = new StubMetadataProvider();
        defineXml.properties().forEach(domainEntry ->
        {
            String domain = domainEntry.getKey();
            com.fasterxml.jackson.databind.JsonNode vars = domainEntry.getValue();
            // D3 — unconditional, before the shape branch. Deriving the name list from the
            // dataset-attribute map instead would re-arm §3.5's trap for a variable-only block
            // (StubMetadataProviderDefineDatasetTest pins that at the core level, where a name can
            // still be registered without attributes).
            provider.defineDataset(domain);
            // D4 — also unconditional, and also before the branch: every key of the block declares
            // a dataset, so define_dataset_name is factually true for a variable-level key too and
            // the Python lane serves it. Registering it for shape B only left the Java lane
            // answering "no such attribute" where the fork answered with the key.
            provider.datasetMeta(domain, SpecDefineXml.datasetAttrs(domain, vars));
            if (SpecDefineXml.isDatasetLevel(vars))
            {
                return;
            }
            for (com.fasterxml.jackson.databind.JsonNode v : vars)
            {
                Map<String, String> m = new LinkedHashMap<>();
                copyText(v, "define_variable_name", m, "name");
                copyText(v, "define_variable_label", m, "label");
                copyText(v, "define_variable_role", m, "role");
                copyText(v, "define_variable_data_type", m, "simpleDatatype");
                copyText(v, "define_variable_ccode", m, "ccode");
                com.fasterxml.jackson.databind.JsonNode codes = v
                        .get("define_variable_codelist_coded_codes");
                if (codes != null)
                {
                    // A JSON array is carried verbatim (the engine decodes it); a scalar is
                    // wrapped.
                    m.put("codelist_coded_codes",
                            codes.isArray() ? codes.toString() : codes.asText());
                }
                com.fasterxml.jackson.databind.JsonNode values = v
                        .get("define_variable_codelist_coded_values");
                if (values != null)
                {
                    // CT-004: the define-enumerated submission values, same verbatim-array
                    // channel as the coded codes.
                    m.put("codelist_coded_values",
                            values.isArray() ? values.toString() : values.asText());
                }
                com.fasterxml.jackson.databind.JsonNode extended = v
                        .get("define_variable_codelist_extended_values");
                if (extended != null)
                {
                    // GLOB-CT-005 variant: the sponsor-extended subset, same channel.
                    m.put("codelist_extended_values",
                            extended.isArray() ? extended.toString() : extended.asText());
                }
                provider.variable(domain, m);
            }
        });
        return provider;
    }


    private static void copyText(com.fasterxml.jackson.databind.JsonNode src, String srcKey,
            Map<String, String> dst, String dstKey)
    {
        com.fasterxml.jackson.databind.JsonNode n = src.get(srcKey);
        if (n != null && !n.isNull())
        {
            dst.put(dstKey, n.asText());
        }
    }
}
