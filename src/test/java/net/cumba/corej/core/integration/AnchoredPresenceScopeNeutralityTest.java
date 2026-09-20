package net.cumba.corej.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.MetadataKeys;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.corej.core.model.DataStructureScope;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.report.LibraryValidator;
import net.cumba.corej.core.run.DatasetExecutionSummary;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Phase 2.a of {@code plans/done/PLAN-group-b-rule-rescope.md}: proves that scoping an <em>anchored
 * presence</em> rule to its anchor domain, rather than to {@code [ALL]} or to "every domain the
 * rule mentions", is <b>finding-neutral for every run in which the anchor is a validation
 * target</b> — and pins the single configuration in which it is not (see
 * {@link #anchorPresentOnlyAsAReferenceDatasetIsTheOneLossyConfiguration}).
 *
 * <p>
 * Each asks "X is present but Y is missing" — {@code ds_exists(anchor) and
 * ds_not_exists(partner)} — and reads nothing about the dataset it runs on:
 * </p>
 *
 * <table border="1">
 * <caption>The re-scoped rules</caption>
 * <tr>
 * <th>Rule</th>
 * <th>Check</th>
 * <th>Scope today</th>
 * <th>Was</th>
 * </tr>
 * <tr>
 * <td>{@code CDISC-CG0407}</td>
 * <td>{@code ds_exists(TA) and ds_not_exists(EX)}</td>
 * <td>{@code [TA]}</td>
 * <td>{@code [ALL]}</td>
 * </tr>
 * <tr>
 * <td>{@code PMDA-AD1024} / {@code AD1025} / {@code AD1026}</td>
 * <td>{@code ds_exists(ADSL) and ds_not_exists(DM/AE/EX)}</td>
 * <td>{@code Data_Structures [SUBJECT LEVEL ANALYSIS DATASET]} — the ADaM spelling of the
 * {@code ADSL} anchor, see {@link #assertAnchorScope}</td>
 * <td>{@code [ALL]}</td>
 * </tr>
 * <tr>
 * <td>{@code CDISC-CG0318} / {@code FDA-SD1270} / {@code PMDA-SD1270}</td>
 * <td>{@code ds_exists(PP) and ds_not_exists(PC)}</td>
 * <td>{@code [PP]}</td>
 * <td>{@code [PC, PP]}</td>
 * </tr>
 * </table>
 *
 * <p>
 * The last family is why the "was" column matters: {@code [PC, PP]} was already narrow, so its
 * extra executions could never fire (on PC the guard leaf {@code ds_exists(PP)} decides the
 * verdict, not the dataset). Narrowing it to the anchor removes executions, not findings — the same
 * claim as for the {@code [ALL]} rows, and it is tested identically.
 * </p>
 *
 * <p>
 * The rules are read from the <b>shipped</b> corpus ({@code rules/rules-<family>-*.json}), so a
 * regression in the authored scope fails here rather than in a hand-written copy of it. Every
 * scenario is driven through {@link LibraryValidator} — the full per-dataset loop, including the
 * production scope gate — rather than {@code RuleRunner} directly, because the property under test
 * <em>is</em> which datasets the rule is offered to.
 * </p>
 *
 * <p>
 * Three configurations per rule, plus two differential runs against a clone carrying the rule's
 * <b>actual pre-fix scope</b> (the "was" column). The clone is what makes this a neutrality proof
 * rather than a behaviour snapshot: where the property holds it fires on every dataset it was in
 * scope for while the shipped rule fires once, and the two agree on the verdict and the message.
 * Where it does not — the anchor present only as a <em>reference</em> dataset — the pre-fix form
 * reports a finding the narrowed rule loses, and that divergence is asserted rather than left to be
 * rediscovered. Comparing against the real previous scope rather than a blanket {@code [ALL]} is
 * what keeps the second claim honest: the {@code [PC, PP]} family never ran on a bystander, so for
 * it that configuration loses nothing, and the test says so.
 * </p>
 */
class AnchoredPresenceScopeNeutralityTest
{

    private static final Path RULES_DIR = CorpusPaths.rules();

    /** Shipped packages are ~1–2 MB each; parse each one once per JVM for the read-only cases. */
    private static final Map<String, RulePackage> PACKAGES = new ConcurrentHashMap<>();

    /** Pre-fix scope of the rules that were authored unscoped. */
    private static final List<String> ALL = List.of("ALL");

    /**
     * Pre-fix scope of the PP/PC family: the anchor <em>plus</em> the dataset whose absence it
     * reports. Listed in the corpus as {@code [PC, PP]} — the order is irrelevant, the fingerprint
     * sorts it.
     */
    private static final List<String> ANCHOR_PLUS_PARTNER = List.of("PC", "PP");

    /**
     * Anchors an ADaM rule may express as a {@code Scope.Data_Structures} entry instead of a
     * {@code Scope.Domains} one, and the entry it must then carry. See {@link #assertAnchorScope}
     * for why the two are equivalent for {@code ADSL}; the SDTM anchors ({@code TA}, {@code PP})
     * are deliberately absent, so they keep naming their dataset.
     */
    private static final Map<String, List<String>> STRUCTURE_ANCHORS = Map.of("ADSL",
            List.of("SUBJECT LEVEL ANALYSIS DATASET"));

    /**
     * One re-scoped rule and the three-dataset study it is exercised against.
     *
     * @param coreId
     *            the rule's {@code Core.Id}
     * @param packageFile
     *            the shipped package the rule is read from
     * @param standard
     *            standard name the fixture library declares
     * @param version
     *            standard version the fixture library declares
     * @param anchor
     *            the dataset the check is gated on — the rule's new scope, and where the finding
     *            attaches
     * @param partner
     *            the dataset whose <em>absence</em> the rule reports
     * @param bystander
     *            an unrelated dataset, present in every configuration
     * @param previousInclude
     *            the rule's {@code Scope.Domains.Include} <em>before</em> the re-scope. The
     *            differential runs are built from this, not from a blanket {@code [ALL]}: the
     *            {@code [PC, PP]} family never ran on a bystander in the first place, and
     *            pretending it did would overstate what the narrowing changed
     */
    record AnchoredRule(String coreId, String packageFile, String standard, String version,
            String anchor, String partner, String bystander, List<String> previousInclude)
    {

        /** Whether the pre-fix scope offered the rule to {@code aDataset}. */
        boolean previouslyCovered(String aDataset)
        {
            return previousInclude.contains("ALL") || previousInclude.contains(aDataset);
        }


        /** The datasets of {@code aStudy} the pre-fix form would have reported on, sorted. */
        List<String> previouslyFiredOn(List<String> aStudy)
        {
            return aStudy.stream().filter(this::previouslyCovered).sorted().toList();
        }
    }

    static Stream<AnchoredRule> rescopedRules()
    {
        return Stream.of(
                new AnchoredRule("CDISC-CG0407", "rules-cdisc-sdtmig-3-4.json", "sdtmig", "3-4",
                        "TA", "EX", "DM", ALL),
                new AnchoredRule("PMDA-AD1024", "rules-pmda-adamig-1-3.json", "adamig", "1-3",
                        "ADSL", "DM", "ADAE", ALL),
                new AnchoredRule("PMDA-AD1025", "rules-pmda-adamig-1-3.json", "adamig", "1-3",
                        "ADSL", "AE", "ADAE", ALL),
                new AnchoredRule("PMDA-AD1026", "rules-pmda-adamig-1-3.json", "adamig", "1-3",
                        "ADSL", "EX", "ADAE", ALL),
                // The PP/PC family, narrowed [PC, PP] -> [PP] on 2026-07-31 (plan §8.1). Same
                // shape, same argument — it just took a second pass to adopt the convention.
                new AnchoredRule("CDISC-CG0318", "rules-cdisc-sdtmig-3-4.json", "sdtmig", "3-4",
                        "PP", "PC", "DM", ANCHOR_PLUS_PARTNER),
                new AnchoredRule("FDA-SD1270", "rules-fda-sdtmig-3-4.json", "sdtmig", "3-4", "PP",
                        "PC", "DM", ANCHOR_PLUS_PARTNER),
                new AnchoredRule("PMDA-SD1270", "rules-pmda-sdtmig-3-4.json", "sdtmig", "3-4", "PP",
                        "PC", "DM", ANCHOR_PLUS_PARTNER));
    }

    // ---- the three configurations, on the shipped (anchor-scoped) rule ----


    /**
     * Configuration 1 — anchor present, partner absent. The rule fires, exactly once, and the
     * finding is attached to the anchor; on the bystander it is not executed at all.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rescopedRules")
    void anchorPresentAndPartnerAbsentFiresExactlyOnceOnTheAnchor(AnchoredRule aCase)
    {
        LibraryValidator validator = validator(aCase, shipped(aCase),
                List.of(aCase.anchor(), aCase.bystander()));
        ValidationReport report = validator.validate();

        assertEquals(List.of(aCase.anchor()), firedDomains(report, aCase.coreId()), aCase.coreId()
                + " must produce exactly one finding, attached to its anchor " + aCase.anchor());
        assertEquals("EXECUTED", statusOn(validator, aCase, aCase.anchor()),
                aCase.coreId() + " must execute on its anchor");
        assertEquals("SKIPPED", statusOn(validator, aCase, aCase.bystander()),
                aCase.coreId() + " must not execute on the unrelated dataset " + aCase.bystander());
    }


    /** Configuration 2 — anchor present and partner present: the second conjunct is false. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rescopedRules")
    void partnerPresentYieldsNoFinding(AnchoredRule aCase)
    {
        LibraryValidator validator = validator(aCase, shipped(aCase),
                List.of(aCase.anchor(), aCase.partner(), aCase.bystander()));
        ValidationReport report = validator.validate();

        assertEquals(List.of(), firedDomains(report, aCase.coreId()),
                aCase.coreId() + " must stay silent while " + aCase.partner() + " is present");
        assertEquals("EXECUTED", statusOn(validator, aCase, aCase.anchor()),
                aCase.coreId() + " must still be exercised on its anchor — a silent rule that never"
                        + " ran would prove nothing");
        // The partner is now out of scope too. For the [PC, PP] family this is the whole delta of
        // the 2026-07-31 narrowing: the rule used to run on PC and report nothing (the guard leaf
        // decided the verdict, not the dataset), and now it is simply not offered to PC.
        assertEquals("SKIPPED", statusOn(validator, aCase, aCase.partner()),
                aCase.coreId() + " must not execute on " + aCase.partner() + " — the dataset whose"
                        + " absence it reports is not the dataset the finding belongs to");
    }


    /**
     * Configuration 3 — anchor absent. This is the only configuration whose <em>observable</em>
     * behaviour changed: the rule now produces a scope-skip audit row instead of a no-violation
     * execution row. The verdict is unchanged (see
     * {@link #narrowingIsFindingNeutralVersusThePreviousScope}), so pin the skip explicitly.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rescopedRules")
    void anchorAbsentMeansScopeSkippedNotExecuted(AnchoredRule aCase)
    {
        LibraryValidator validator = validator(aCase, shipped(aCase), List.of(aCase.bystander()));
        ValidationReport report = validator.validate();

        assertEquals(List.of(), firedDomains(report, aCase.coreId()),
                aCase.coreId() + " cannot fire when its anchor " + aCase.anchor() + " is absent");
        assertEquals("SKIPPED", statusOn(validator, aCase, aCase.bystander()),
                aCase.coreId() + " must report a scope skip, not an execution, when the study does"
                        + " not contain its anchor");
        assertFalse(
                validator.getExecutionSummaries().stream().flatMap(s -> s.ruleExecutions().stream())
                        .anyMatch(rx -> aCase.coreId().equals(rx.coreId())
                                && "EXECUTED".equals(rx.status())),
                aCase.coreId() + " must not execute on any dataset of an anchor-less study");
    }

    // ---- the load-bearing one: shipped rule vs its actual pre-fix form ----


    /**
     * The neutrality proof. Against the same studies, the shipped anchor-scoped rule and a clone
     * carrying the rule's <b>actual pre-fix scope</b> reach the <b>same verdict and the same
     * message</b>; the only difference is how often the finding is repeated.
     *
     * <ul>
     * <li>Anchor present, partner absent — the pre-fix form emits one finding per <em>in-scope</em>
     * dataset (it is {@code Sensitivity: Dataset}); the shipped form emits exactly one, on the
     * anchor. Same message, no finding lost. For the {@code [ALL]} rules that is a real reduction
     * (2 findings → 1); for the {@code [PC, PP]} family both forms already emitted one, and the
     * narrowing only removes an execution that could never have fired.</li>
     * <li>Anchor absent — both are silent. The pre-fix form ran and found nothing; the shipped form
     * did not run. Nothing is lost by not running.</li>
     * </ul>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rescopedRules")
    void narrowingIsFindingNeutralVersusThePreviousScope(AnchoredRule aCase)
    {
        List<String> firingStudy = List.of(aCase.anchor(), aCase.bystander());

        ValidationReport narrowed = validator(aCase, shipped(aCase), firingStudy).validate();
        ValidationReport previous = validator(aCase, previousFormClone(aCase), firingStudy)
                .validate();

        assertEquals(messages(narrowed, aCase.coreId()), messages(previous, aCase.coreId()),
                aCase.coreId() + ": narrowing the scope must not change what is reported");
        assertFalse(messages(narrowed, aCase.coreId()).isEmpty(),
                aCase.coreId() + ": the firing configuration must actually report something —"
                        + " otherwise the comparison above is vacuous");
        assertEquals(List.of(aCase.anchor()), firedDomains(narrowed, aCase.coreId()),
                aCase.coreId() + ": the narrowed rule attaches its finding to the anchor only");
        assertEquals(aCase.previouslyFiredOn(firingStudy),
                firedDomains(previous, aCase.coreId()).stream().sorted().toList(),
                aCase.coreId() + ": the pre-fix form " + aCase.previousInclude() + " reported on"
                        + " every dataset it was in scope for — the surplus is what the re-scope"
                        + " removes");

        // Anchor absent: the pre-fix form ran wherever it was in scope and stayed silent, so the
        // narrowed form's non-execution loses nothing.
        List<String> anchorlessStudy = List.of(aCase.bystander());
        assertEquals(List.of(),
                firedDomains(validator(aCase, shipped(aCase), anchorlessStudy).validate(),
                        aCase.coreId()),
                aCase.coreId() + ": narrowed form is silent without its anchor");
        assertEquals(List.of(),
                firedDomains(validator(aCase, previousFormClone(aCase), anchorlessStudy).validate(),
                        aCase.coreId()),
                aCase.coreId() + ": the pre-fix form was silent without its anchor too — the"
                        + " re-scope removes executions, never findings");
    }


    /**
     * The <b>boundary of the neutrality claim</b>, pinned so it cannot be forgotten: narrowing is
     * finding-neutral for every run in which the anchor is a <em>validation target</em>, and only
     * then.
     *
     * <p>
     * The engine keeps two dataset sets. {@code ds_exists} reads the study <b>inventory</b> —
     * targets <em>and</em> references ({@code LibraryValidator.buildResolver}) — while the
     * per-dataset loop that offers rules to datasets iterates <b>targets only</b>. A run with a
     * {@code --dataset} filter demotes every unselected dataset to a reference
     * ({@code StudyValidationService}: <i>"same soft cache, but never iterated as a validation
     * target"</i>), and the web UI always sends that filter.
     * </p>
     *
     * <p>
     * So for a study of {@code {TA, DM}} validated as {@code --dataset DM}: the {@code [ALL]} form
     * was offered to DM, saw TA in the inventory, and reported the finding; the anchor-scoped form
     * is offered to nothing at all. <b>The finding is lost</b> — and, because the rule was never
     * generated for DM either, there is not even a scope-skip audit row for it.
     * </p>
     *
     * <p>
     * That is defensible (the user asked to validate DM, not TA) and it is the behaviour the
     * re-scope chose, but it is <em>not</em> neutrality — so it is asserted here rather than left
     * to be rediscovered. If this test ever fails because the narrowed rule started firing again,
     * the engine's target/reference split changed and the docs in {@code rule-review-manual.md}
     * need revisiting.
     * </p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rescopedRules")
    void anchorPresentOnlyAsAReferenceDatasetIsTheOneLossyConfiguration(AnchoredRule aCase)
    {
        List<String> targets = List.of(aCase.bystander());
        List<String> references = List.of(aCase.anchor());

        ValidationReport narrowed = validator(aCase, shipped(aCase), targets, references)
                .validate();
        ValidationReport previous = validator(aCase, previousFormClone(aCase), targets, references)
                .validate();

        assertEquals(List.of(), firedDomains(narrowed, aCase.coreId()),
                aCase.coreId() + ": scoped to " + aCase.anchor() + ", the rule is offered to no"
                        + " target at all when its anchor is only a reference dataset");

        // Whether anything was LOST depends on the pre-fix scope, not on the narrowing: only a form
        // that covered the surviving target could have reported here. So the [ALL] rules lose a
        // finding and the [PC, PP] family loses nothing — it never ran on a bystander either.
        List<String> previouslyFired = aCase.previouslyFiredOn(targets);
        assertEquals(previouslyFired, firedDomains(previous, aCase.coreId()),
                aCase.coreId() + ": pre-fix scope " + aCase.previousInclude()
                        + (previouslyFired.isEmpty()
                                ? " did not cover " + aCase.bystander() + " either, so this"
                                        + " configuration is NOT lossy for this rule"
                                : " DID report here — " + aCase.anchor() + " is in the inventory"
                                        + " ds_exists reads even though it is not validated, so"
                                        + " this is the configuration in which narrowing loses a"
                                        + " finding"));
    }

    // ---- fixture plumbing ----


    /**
     * The shipped rule, exactly as authored — a regression in its {@code Scope} fails these tests.
     * The returned instance is the one from {@link AnchoredRule#packageFile()}, but the anchor
     * scope is asserted across <em>every</em> shipped package the rule appears in: each family
     * publishes the same rule under several standard versions, and a partial regeneration could
     * leave them disagreeing.
     */
    private static Rule shipped(AnchoredRule aCase)
    {
        Rule rule = byCoreId(cached(aCase.packageFile()), aCase.coreId());
        assertAnchorScope(rule, aCase,
                aCase.coreId() + " is expected to be scoped to its anchor in the shipped corpus");
        for (String pkgFile : shippedPackagesOf(aCase))
        {
            assertAnchorScope(byCoreId(cached(pkgFile), aCase.coreId()), aCase,
                    aCase.coreId() + " must carry the same anchor scope in " + pkgFile);
        }
        return rule;
    }


    /**
     * Asserts that the rule expresses its anchor — in either of the <b>two equivalent spellings</b>
     * the corpus uses.
     *
     * <p>
     * The SDTM rules name the anchor as a dataset: {@code Scope.Domains.Include: [TA]}. The ADaM
     * rules cannot, because ADaM dataset names are <em>sponsor-defined</em> — so they scope on the
     * column-derived data structure instead
     * ({@code plans/done/PLAN-adam-structure-subclass-scope.md} phase 8):
     * {@code Scope.Data_Structures.Include: [SUBJECT LEVEL ANALYSIS DATASET]}, with no
     * {@code Scope.Domains} at all.
     * </p>
     *
     * <p>
     * For the {@code ADSL} anchor the two are equivalent, and that is why the substitution is
     * accepted here rather than treated as a lost anchor: {@code AdamDataStructureDetector.detect}
     * resolves {@code SUBJECT LEVEL ANALYSIS DATASET} for any dataset <em>named</em> {@code ADSL}
     * (case-insensitively) as well as for one whose Define-XML {@code def:Class} declares it. The
     * structure form is therefore a superset of the name test, never narrower — the contract this
     * class defends (the rule is offered exactly to the dataset it reads, so a {@code --dataset}
     * filter cannot leave it silently evaluating nothing) holds identically in both spellings.
     * </p>
     *
     * <p>
     * The equivalence is registered per anchor in {@link #STRUCTURE_ANCHORS} and only consulted
     * when the rule carries no {@code Scope.Domains}: every other anchor still has to name its
     * dataset, exactly as before.
     * </p>
     */
    private static void assertAnchorScope(Rule aRule, AnchoredRule aCase, String aMessage)
    {
        List<String> structure = STRUCTURE_ANCHORS.get(aCase.anchor());
        Scope scope = aRule.getScope();
        if (structure != null && scope != null && scope.getDomains() == null)
        {
            assertEquals(structure, includedStructures(aRule),
                    aMessage + " — it carries no Scope.Domains, so it must express the "
                            + aCase.anchor() + " anchor as Scope.Data_Structures " + structure);
            return;
        }
        assertEquals(List.of(aCase.anchor()), includedDomains(aRule), aMessage);
    }


    /** Every {@code rules-<family>-*.json} that publishes this rule, sorted. */
    private static List<String> shippedPackagesOf(AnchoredRule aCase)
    {
        String family = aCase.packageFile().substring(0, aCase.packageFile().indexOf('-', 6));
        try (Stream<Path> files = Files.list(RULES_DIR))
        {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(family + "-") && n.endsWith(".json"))
                    .filter(n -> byCoreIdOrNull(cached(n), aCase.coreId()) != null).sorted()
                    .toList();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("could not list " + RULES_DIR, e);
        }
    }


    private static RulePackage cached(String aFileName)
    {
        return PACKAGES.computeIfAbsent(aFileName,
                AnchoredPresenceScopeNeutralityTest::loadPackage);
    }


    /**
     * The pre-fix form: the same rule carrying {@link AnchoredRule#previousInclude()} again. Loaded
     * from a <em>fresh</em> package rather than the cache, so the mutation cannot leak into
     * {@link #shipped}.
     *
     * <p>
     * For a rule whose anchor is spelled as a data structure (see {@link #assertAnchorScope})
     * restoring the pre-fix form is two edits, not one: put the {@code Scope.Domains} list back
     * <em>and</em> drop the structure gate that replaced it. Keeping the gate would leave the
     * "previous" clone narrowed by the very scope the comparison exists to measure, and the
     * neutrality proof would compare the fix against itself.
     * </p>
     */
    private static Rule previousFormClone(AnchoredRule aCase)
    {
        Rule rule = byCoreId(loadPackage(aCase.packageFile()), aCase.coreId());
        Scope scope = rule.getScope();
        assertNotNull(scope, aCase.coreId() + " must declare a Scope");
        DomainScope domains = scope.getDomains();
        if (domains == null)
        {
            assertEquals(STRUCTURE_ANCHORS.get(aCase.anchor()), includedStructures(rule),
                    aCase.coreId() + " must declare Scope.Domains or the equivalent"
                            + " Scope.Data_Structures anchor");
            scope.setDataStructures(null);
            domains = new DomainScope();
            scope.setDomains(domains);
        }
        domains.setInclude(aCase.previousInclude());
        return rule;
    }


    private static RulePackage loadPackage(String aFileName)
    {
        try
        {
            return RulePackageLoader.load(RULES_DIR.resolve(aFileName));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("could not load " + RULES_DIR.resolve(aFileName), e);
        }
    }


    private static Rule byCoreId(RulePackage aPackage, String aCoreId)
    {
        Rule rule = byCoreIdOrNull(aPackage, aCoreId);
        if (rule == null)
        {
            throw new AssertionError("rule not in package: " + aCoreId);
        }
        return rule;
    }


    private static @Nullable Rule byCoreIdOrNull(RulePackage aPackage, String aCoreId)
    {
        return aPackage.getRules().values().stream()
                .filter(r -> r.getCore() != null && aCoreId.equals(r.getCore().getId())).findFirst()
                .orElse(null);
    }


    private static List<String> includedDomains(Rule aRule)
    {
        Scope scope = aRule.getScope();
        DomainScope domains = scope != null ? scope.getDomains() : null;
        List<String> include = domains != null ? domains.getInclude() : null;
        return include != null ? include : List.of();
    }


    private static List<String> includedStructures(Rule aRule)
    {
        Scope scope = aRule.getScope();
        DataStructureScope structures = scope != null ? scope.getDataStructures() : null;
        List<String> include = structures != null ? structures.getInclude() : null;
        return include != null ? include : List.of();
    }


    /** A validator over exactly the named datasets, running only the rule under test. */
    private static LibraryValidator validator(AnchoredRule aCase, Rule aRule,
            List<String> aDatasets)
    {
        return validator(aCase, aRule, aDatasets, List.of());
    }


    /**
     * A validator whose study splits into <b>targets</b> (iterated by the per-dataset loop, and the
     * only datasets a rule is ever offered to) and <b>references</b> (in the inventory that
     * {@code ds_exists} reads, but never validated). The split is not exotic: any run with a
     * {@code --dataset} filter demotes every unselected dataset to a reference
     * ({@code StudyValidationService}), which the web UI always does.
     */
    private static LibraryValidator validator(AnchoredRule aCase, Rule aRule, List<String> aTargets,
            List<String> aReferences)
    {
        List<String> known = new ArrayList<>(aTargets);
        known.addAll(aReferences);
        LibraryValidator.Builder builder = LibraryValidator.builder()
                .provider(provider(aCase, known)).rules(List.of(aRule))
                .libraryUri("file:///study/" + aCase.coreId().toLowerCase(Locale.ROOT));
        for (String dataset : aTargets)
        {
            builder = builder.targetDataset(dataset, dataset.toLowerCase(Locale.ROOT) + ".xpt",
                    table(dataset));
        }
        for (String dataset : aReferences)
        {
            builder = builder.referenceDataset(dataset, table(dataset));
        }
        return builder.build();
    }


    /** A library that knows every dataset of the configuration, so none is skipped for metadata. */
    private static MetadataProvider provider(AnchoredRule aCase, List<String> aDatasets)
    {
        TestMetadataFixtures.LibBuilder lib = TestMetadataFixtures.lib("study")
                .meta(MetadataKeys.STANDARD_NAME, aCase.standard())
                .meta(MetadataKeys.STANDARD_VERSION, aCase.version());
        for (String dataset : aDatasets)
        {
            lib = lib.table(TestMetadataFixtures.table(dataset).label(dataset)
                    .column(TestMetadataFixtures.column("STUDYID", 0, DataValueType.STRING)
                            .label("Study Identifier").core("Req").role("Identifier").build())
                    .build());
        }
        IMetadataLibrary built = lib.build();
        return new MetadataLibraryProvider(built);
    }


    private static IDataTable table(String aDataset)
    {
        return MockTable.of().name(aDataset).col("STUDYID", "S1").build();
    }

    // ---- report readers ----


    /**
     * One entry per finding <em>of the rule under test</em>, naming the dataset it is attached to.
     * Filtered by rule id because a report also carries findings the engine raises about the run
     * itself (library warnings, metadata notices), which say nothing about the rule's scope.
     */
    private static List<String> firedDomains(ValidationReport aReport, String aCoreId)
    {
        List<String> domains = new ArrayList<>();
        for (ValidationReportMember member : aReport.getMembers())
        {
            for (ValidationFinding finding : member.getFindings())
            {
                if (aCoreId.equals(finding.getRuleId()))
                {
                    domains.add(member.getDomain());
                }
            }
        }
        return domains;
    }


    /** The distinct finding messages of a run — the "what is reported" half of neutrality. */
    private static Set<String> messages(ValidationReport aReport, String aCoreId)
    {
        Set<String> messages = new LinkedHashSet<>();
        for (ValidationReportMember member : aReport.getMembers())
        {
            for (ValidationFinding finding : member.getFindings())
            {
                if (aCoreId.equals(finding.getRuleId()))
                {
                    messages.add(String.valueOf(finding.getMessage()));
                }
            }
        }
        return messages;
    }


    /** The rule's execution status against one dataset ({@code EXECUTED} / {@code SKIPPED}). */
    private static String statusOn(LibraryValidator aValidator, AnchoredRule aCase, String aDataset)
    {
        for (DatasetExecutionSummary summary : aValidator.getExecutionSummaries())
        {
            if (!aDataset.equals(summary.domain()))
            {
                continue;
            }
            for (DatasetExecutionSummary.RuleExecution execution : summary.ruleExecutions())
            {
                if (aCase.coreId().equals(execution.coreId()))
                {
                    return execution.status();
                }
            }
        }
        throw new AssertionError(aCase.coreId() + " is not accounted for on dataset " + aDataset
                + " — the execution summary should carry every selected rule");
    }
}
