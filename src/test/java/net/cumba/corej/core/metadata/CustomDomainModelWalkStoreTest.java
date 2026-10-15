package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.FindingKeyMode;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.RecordKeyResolver;
import net.cumba.corej.core.exec.RecordKeyResolver.KeySource;
import net.cumba.corej.core.exec.RecordKeyResolver.RowKeySpec;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-custom-domain-model-walk} §8 E5 — the walks and the shipped rules on a domain the
 * run's IG does <b>not</b> define, over the <b>real</b> library-metadata provider.
 *
 * <p>
 * Such a domain is a sponsor's own ({@code XX}), or a standard one the run's IG leaves out: at
 * SENDIG-AR 1.0 every SEND domain but {@code DM}, {@code CM}, {@code FA} and {@code MH}, at
 * SENDIG-DART 1.1 {@code EG PM TF VS CV RE}. The provider's metadata library is the IG product's
 * table view, which has no table for it, so before the fix both SDTM walks answered {@code []}: the
 * model-order rules SKIPPED, and the rules reading the total operations
 * ({@code get_model_filtered_variables}, {@code natural_key_variables},
 * {@code get_dataset_filtered_variables}) ran against an empty list and false-fired. The walk now
 * places the dataset in the class the scope matcher gives it, from its own columns.
 * </p>
 *
 * <p>
 * A {@code .cdt} scenario cannot see any of this: the map-backed test provider serves the declared
 * lists and never executes {@link MetadataLibraryProvider}. Every provider here is built by the
 * production factory ({@link StoreMetadataProviderFactory#forSdtm}) over the store
 * {@link MetadataStoreFixture} seeds into {@code target/}, and every rule is the shipped one,
 * loaded from its generated package.
 * </p>
 *
 * <p>
 * ⛔ No assumption skips: without a pickle corpus the store cannot be seeded and every test here
 * FAILS, naming the variable to set.
 * </p>
 */
class CustomDomainModelWalkStoreTest
{

    private static final String CDISC_34 = "rules-cdisc-sdtmig-3-4.json";

    private static final String FDA_34 = "rules-fda-sdtmig-3-4.json";

    private static final String PMDA_34 = "rules-pmda-sdtmig-3-4.json";

    private static final String SDTMIG_34 = "sdtmig 3-4";

    /** Generated packages already loaded, by file name (loading one is the expensive part). */
    private static final Map<String, Map<String, Rule>> PACKAGES = new ConcurrentHashMap<>();

    /** The sponsor-domain class shapes: the columns → the class the scope ladder gives them. */
    private static final Map<String, List<String>> SHAPES = shapes();

    /** The standard SEND domains SENDIG-AR 1.0 leaves out, relative to SENDIG 3.1.1. */
    private static final Set<String> AR_ABSENT = Set.of("BG", "BW", "CL", "CO", "CV", "DD", "DS",
            "EG", "EX", "FW", "LB", "MA", "MI", "OM", "PC", "PM", "POOLDEF", "PP", "RE", "RELREC",
            "SC", "SE", "TA", "TE", "TF", "TS", "TX", "VS");

    /** The standard SEND domains SENDIG-DART 1.1 leaves out, relative to SENDIG 3.1.1. */
    private static final Set<String> DART_ABSENT = Set.of("CV", "EG", "PM", "RE", "TF", "VS");

    /** Natural-key roles plus Topic — the EC-40 {@code NATURAL} tier's selection. */
    private static final Set<String> NATURAL_TIER_ROLES = Set.of("Topic", "Timing",
            "Record Qualifier", "Variable Qualifier", "Result Qualifier", "Synonym Qualifier",
            "Grouping Qualifier");

    private static Map<String, List<String>> shapes()
    {
        Map<String, List<String>> s = new LinkedHashMap<>();
        s.put("EVENTS", List.of("STUDYID", "USUBJID", "XXSEQ", "XXTERM", "XXSTDTC"));
        s.put("INTERVENTIONS", List.of("STUDYID", "USUBJID", "XXSEQ", "XXTRT", "XXSTDTC"));
        s.put("FINDINGS", List.of("STUDYID", "USUBJID", "XXSEQ", "XXTESTCD", "XXORRES", "XXDTC"));
        s.put("FINDINGS ABOUT",
                List.of("STUDYID", "USUBJID", "XXSEQ", "XXTESTCD", "XXOBJ", "XXORRES", "XXDTC"));
        return s;
    }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------


    private static StoreMetadataProviderFactory factory()
    {
        return MetadataStoreFixture.factory().orElseThrow(() -> new IllegalStateException(
                "No metadata store: the walks are a property of the CDISC Library products, not of"
                        + " this repository. Set CDISC_PICKLE_CACHE_DIR (or"
                        + " -Dcdisc.pickle.cache.dir) to a pickle cache directory —"
                        + " MetadataStoreFixture seeds the store from it."));
    }


    private static MetadataProvider provider(String aPackage)
    {
        String[] parts = aPackage.split(" ", -1);
        return factory().forSdtm(parts[0], parts[1], List.of()).orElseThrow(
                () -> new AssertionError("the seeded store lacks the IG product " + aPackage));
    }


    /**
     * A dataset named {@code aName}, its columns in the given order, one row per value array. A
     * {@code DOMAIN} column is never added implicitly.
     */
    private static IDataTable table(String aName, List<String> aColumns, String[]... aRows)
    {
        MockTable t = MockTable.of().name(aName);
        for (int c = 0; c < aColumns.size(); c++)
        {
            String[] values = new String[aRows.length];
            for (int r = 0; r < aRows.length; r++)
            {
                values[r] = aRows[r][c];
            }
            t.col(aColumns.get(c), values);
        }
        return t.build();
    }


    /** A one-row dataset carrying {@code DOMAIN} and the given columns, every cell {@code X}. */
    private static IDataTable dataset(String aName, List<String> aColumns)
    {
        MockTable t = MockTable.of().name(aName).col("DOMAIN", aName);
        for (String column : aColumns)
        {
            if (!"DOMAIN".equals(column))
            {
                t.col(column, "X");
            }
        }
        return t.build();
    }


    private static Set<String> columnsOf(IDataTable aTable)
    {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < aTable.getMetaData().getColumnCount(); i++)
        {
            out.add(aTable.getMetaData().getColumn(i).getName());
        }
        return out;
    }


    private static List<String> names(List<Map<String, String>> aRows)
    {
        return aRows.stream().map(r -> r.get("name")).toList();
    }


    private static Rule shipped(String aPackageFile, String aRuleId) throws IOException
    {
        Map<String, Rule> rules = PACKAGES.get(aPackageFile);
        if (rules == null)
        {
            rules = RulePackageLoader.load(CorpusPaths.rules(aPackageFile)).getRules();
            PACKAGES.put(aPackageFile, rules);
        }
        Rule rule = rules.get(aRuleId);
        assertNotNull(rule, aRuleId + " must ship in " + aPackageFile);
        assertNull(rule.getLoadError(), aRuleId + " must load clean: " + rule.getLoadError());
        return rule;
    }


    /** Runs a shipped rule on {@code aTable}; {@code aOthers} are resolvable by name. */
    private static RuleExecutionResult run(String aPackageFile, String aStorePackage,
            String aRuleId, IDataTable aTable, IDataTable... aOthers)
        throws IOException
    {
        Map<String, IDataTable> byName = new HashMap<>();
        byName.put(aTable.getMetaData().getName(), aTable);
        for (IDataTable other : aOthers)
        {
            byName.put(other.getMetaData().getName(), other);
        }
        return RuleRunnerCalls.execute(shipped(aPackageFile, aRuleId), aTable, byName::get,
                aTable.getMetaData().getName(), provider(aStorePackage));
    }


    private static RuleExecutionResult executed(RuleExecutionResult aResult, String aWhat)
    {
        assertFalse(aResult.isSkipped(), aWhat + " must execute: " + aResult.getStatusMessage());
        assertFalse(aResult.isError(), aWhat + " must not error: " + aResult.getStatusMessage());
        return aResult;
    }


    /** variable name → level, for a rule reporting one finding per variable. */
    private static Map<String, Severity> byVariable(RuleExecutionResult aResult, String aWhat)
    {
        Map<String, Severity> out = new TreeMap<>();
        for (Violation v : executed(aResult, aWhat).getViolations())
        {
            String variable = v.getValues().get("variable_name");
            assertNotNull(variable, aWhat + ": every finding names its variable: " + v);
            assertNull(out.put(variable, v.getLevel()), aWhat + ": one finding per variable");
        }
        return out;
    }


    private static int count(RuleExecutionResult aResult, String aWhat)
    {
        return executed(aResult, aWhat).getViolations().size();
    }


    /**
     * The packages the generated corpus ships {@code aRuleId} in, as store keys
     * ({@code rules-fda-sendig-dart-1-1.json} → {@code "sendig dart-1-1"}) — the corpus's
     * population, not one written into this class.
     */
    private static Set<String> shippingPackages(String aRuleId) throws IOException
    {
        return new TreeSet<>(shippingFiles(aRuleId).keySet());
    }


    /** {@link #shippingPackages}, each store key mapped to the generated package file. */
    private static Map<String, String> shippingFiles(String aRuleId) throws IOException
    {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(CorpusPaths.rules()))
        {
            for (Path file : files.sorted().toList())
            {
                String name = file.getFileName().toString();
                if (name.startsWith("rules-") && name.endsWith(".json")
                        && RulePackageLoader.load(file).getRules().containsKey(aRuleId))
                {
                    String[] parts = name.substring(0, name.length() - ".json".length()).split("-",
                            4);
                    assertNull(out.put(parts[2] + " " + parts[3], name),
                            aRuleId + " ships twice for " + parts[2] + " " + parts[3]);
                }
            }
        }
        assertFalse(out.isEmpty(), aRuleId + " ships in no generated package");
        return out;
    }


    /**
     * The walk class is the scope class, and all three table-taking walks answer the forced-class
     * walk for it (the §2.1 oracle), which is not empty. An IG-absent domain has no IG dataset to
     * merge, so algorithm B is algorithm A — which is why {@code FDA-SD1076} stays silent.
     */
    private static void assertWalksTheScopeClass(MetadataProvider aProvider, IDataTable aTable,
            String aExpectedClass, String aWhat)
    {
        String member = aTable.getMetaData().getName();
        // The scope matcher's own ladder (LibraryValidator calls the same method).
        String scope = ScopeClassLadder.classOf(aProvider, member,
                CdiscDomainResolver.cdiscDomainOf(aTable), aTable, _ -> null);
        assertEquals(aExpectedClass, scope, aWhat + ": scope class");
        List<Map<String, String>> forced = aProvider.getStandardModelVariablesForClass(aTable, null,
                scope);
        assertNotNull(forced, aWhat + ": the forced walk serves " + scope);
        assertFalse(forced.isEmpty(), aWhat + ": the forced walk serves " + scope);
        assertEquals(names(forced), aProvider.getStandardModelVariables(aTable, null),
                aWhat + ": algorithm A walks the scope class");
        assertEquals(forced, aProvider.getStandardVariablesDetailed(aTable, null),
                aWhat + ": algorithm B walks the scope class");
    }

    // ------------------------------------------------------------------
    // 1. The walks — a sponsor domain at every package
    // ------------------------------------------------------------------


    @Test
    void aSponsorDomainWalksItsScopeClassAtEveryPackage() throws IOException
    {
        Set<String> packages = shippingPackages("FDA-SD0058");
        int checked = 0;
        for (String pkg : packages)
        {
            MetadataProvider provider = provider(pkg);
            for (Map.Entry<String, List<String>> shape : SHAPES.entrySet())
            {
                assertWalksTheScopeClass(provider, dataset("XX", shape.getValue()), shape.getKey(),
                        pkg + " XX " + shape.getKey());
                checked++;
            }
        }
        assertEquals(10, packages.size(), "FDA-SD0058's packages: " + packages);
        assertEquals(40, checked);
    }

    // ------------------------------------------------------------------
    // 2. The walks — the standard domains SENDIG-AR 1.0 and SENDIG-DART 1.1 leave out
    // ------------------------------------------------------------------


    /** Standard SEND domains SENDIG 3.1.1 defines and {@code aPackage} does not. */
    private static Set<String> igAbsentRelativeTo311(String aPackage)
    {
        MetadataProvider reference = provider("sendig 3-1-1");
        MetadataProvider target = provider(aPackage);
        List<String> datasets = reference.getStandardDatasetNames();
        assertNotNull(datasets);
        Set<String> out = new TreeSet<>();
        for (String name : datasets)
        {
            if (!reference.getColumnOrder(name).isEmpty() && target.getColumnOrder(name).isEmpty())
            {
                out.add(name);
            }
        }
        return out;
    }


    @Test
    void theIgAbsentStandardSendDomainsAreExactlyTheMeasuredSets()
    {
        // Derived from the STORED products, so a store change that moves either set reds here.
        // AR prints no SUPPQUAL either (the base-IG view brings it in, B-SUPP).
        Set<String> ar = new TreeSet<>(AR_ABSENT);
        ar.add("SUPPQUAL");
        assertEquals(ar, StoredSendRosters.storedAbsentRelativeTo311("sendig ar-1-0"));
        assertEquals(new TreeSet<>(DART_ABSENT),
                StoredSendRosters.storedAbsentRelativeTo311("sendig dart-1-1"));
        // Through the base-IG view (PLAN-send-base-ig-view) the provider leaves none out.
        assertEquals(Set.of(), igAbsentRelativeTo311("sendig ar-1-0"));
        assertEquals(Set.of(), igAbsentRelativeTo311("sendig dart-1-1"));
    }


    @Test
    void everyIgAbsentGeneralObservationDomainReadsTheBaseSendigRows()
    {
        // PLAN-send-base-ig-view: the general-observation domains AR / DART leave out are no
        // longer walked as IG-absent (their scope class's model rows): the base-IG view gives the
        // run the base SENDIG's dataset, so every IG variable reads the base's label, core, type
        // and role (B-BASE: AR 1.0 and DART 1.1 name SENDIG v3.1).
        MetadataProvider base = provider("sendig 3-1");
        int checked = 0;
        for (String pkg : List.of("sendig ar-1-0", "sendig dart-1-1"))
        {
            MetadataProvider provider = provider(pkg);
            for (String domain : StoredSendRosters.storedAbsentRelativeTo311(pkg))
            {
                IDataTable t = dataset(domain, base.getColumnOrder(domain));
                String scope = base.getDatasetClass(domain, domain, columnsOf(t));
                if (!Set.of("Events", "Interventions", "Findings").contains(scope))
                {
                    continue;
                }
                assertFalse(provider.isDomainCustom(domain), pkg + " " + domain);
                assertEquals(scope, provider.getDatasetClass(domain, domain, columnsOf(t)),
                        pkg + " " + domain);
                // The base IG's own variables (the walk also lists the run's model class
                // variables, whose rows are the run's model's: AR names SDTM 1.8).
                for (String variable : StoredSendRosters.storedVariables("sendig 3-1", domain))
                {
                    Map<String, String> want = base.getVariableMetadata(domain, variable);
                    Map<String, String> got = provider.getVariableMetadata(domain, variable);
                    for (String key : List.of("label", "core", "simpleDatatype", "role"))
                    {
                        assertEquals(want.get(key), got.get(key),
                                pkg + " " + domain + "." + variable + " " + key);
                    }
                }
                checked++;
            }
        }
        // AR: DS, EX and 18 Findings domains; DART: its 6, all Findings.
        assertEquals(26, checked);
    }


    /** One row carrying exactly {@code aColumns}, in that order ({@code DOMAIN} = the name). */
    private static IDataTable igShaped(String aName, List<String> aColumns)
    {
        MockTable t = MockTable.of().name(aName);
        for (String column : aColumns)
        {
            t.col(column, "DOMAIN".equals(column) ? aName : "X");
        }
        return t.build();
    }


    @Test
    void theOtherDomainsSendigAr10LeavesOutAreJudgedByTheBaseSendig() throws IOException
    {
        // AR's special-purpose (CO, SE), trial-design (TA TE TS TX) and relationship (POOLDEF,
        // RELREC) datasets: before PLAN-send-base-ig-view algorithm B answered [] for them (no
        // domain-keyed model tier). The base-IG view gives the AR run SENDIG 3.1's datasets, so
        // algorithm B now answers exactly what a SENDIG 3.1 run answers, and FDA-SD0058 /
        // FDA-SD1079 judge them on the IG shape: executed, clean.
        MetadataProvider base = provider("sendig 3-1");
        MetadataProvider ar = provider("sendig ar-1-0");
        String pkg = "rules-fda-sendig-ar-1-0.json";
        Set<String> judged = new TreeSet<>();
        for (String domain : StoredSendRosters.storedAbsentRelativeTo311("sendig ar-1-0"))
        {
            List<String> ig = base.getColumnOrder(domain);
            IDataTable t = igShaped(domain, ig);
            String scope = base.getDatasetClass(domain, domain, columnsOf(t));
            if (Set.of("Events", "Interventions", "Findings").contains(scope)
                    || "SUPPQUAL".equals(domain))
            {
                continue;
            }
            List<Map<String, String>> detailed = ar.getStandardVariablesDetailed(t, null);
            assertFalse(detailed.isEmpty(), domain + ": algorithm B answers the base dataset");
            assertEquals(base.getStandardVariablesDetailed(t, null).stream().map(m -> m.get("name"))
                    .toList(), detailed.stream().map(m -> m.get("name")).toList(), domain);
            assertEquals(Map.of(), byVariable(run(pkg, "sendig ar-1-0", "FDA-SD0058", t), domain));
            assertEquals(0, count(run(pkg, "sendig ar-1-0", "FDA-SD1079", t), domain), domain);
            judged.add(domain);
        }
        assertEquals(Set.of("CO", "POOLDEF", "RELREC", "SE", "TA", "TE", "TS", "TX"), judged);
    }


    @Test
    void anAssociatedPersonsDatasetIsJudgedExactlyWhereScopePlacesIt() throws IOException
    {
        // Review round 1, LOW-2: APXX carries no APXXTERM, so scope inherits the parent's class —
        // and places APXX nowhere when the parent XX is absent. The walk takes the same answer.
        IDataTable apxx = table("APXX",
                List.of("STUDYID", "DOMAIN", "APID", "RSUBJID", "SREL", "XXSEQ", "XXTERM", "XXFOO"),
                new String[]
                {
                        "S", "APXX", "A1", "U1", "MOTHER", "1", "t", "f"
                });
        IDataTable xx = dataset("XX", SHAPES.get("EVENTS"));
        RuleExecutionResult withParent = run(FDA_34, SDTMIG_34, "FDA-SD0058", apxx, xx);
        assertEquals(Map.of("XXFOO", Severity.ERROR), byVariable(withParent, "APXX with XX"));
        RuleExecutionResult orphan = run(FDA_34, SDTMIG_34, "FDA-SD0058", apxx);
        assertTrue(orphan.isSkipped(), "no parent, no class, no model list: " + orphan);
        MetadataProvider provider = provider(SDTMIG_34);
        assertNull(ScopeClassLadder.classOf(provider, "APXX", "APXX", apxx, _ -> null));
        assertEquals("EVENTS", ScopeClassLadder.classOf(provider, "APXX", "APXX", apxx,
                name -> "XX".equals(name) ? xx : null));
    }

    // ------------------------------------------------------------------
    // 3. The shipped rules — SDTMIG 3.4 on a sponsor Findings domain
    // ------------------------------------------------------------------


    /** Out of model order ({@code XXREPNUM} after the timing variables) and a sponsor variable. */
    private static IDataTable outOfOrder(String aDomain)
    {
        String p = aDomain;
        return table(
                aDomain, List.of("STUDYID", "DOMAIN", "USUBJID", p + "SEQ", p + "TESTCD",
                        p + "TEST", p + "ORRES", "VISITNUM", p + "DTC", p + "REPNUM", p + "FOO"),
                new String[]
                {
                        "S", aDomain, "U1", "1", "T1", "Test", "1", "1", "2020-01-01", "1", "f"
                }, new String[]
                {
                        "S", aDomain, "U1", "2", "T1", "Test", "2", "2", "2020-01-02", "1", "f"
                }, new String[]
                {
                        "S", aDomain, "U1", "3", "T1", "Test", "3", "3", "2020-01-03", "1", "f"
                });
    }


    /** In model order, no sponsor variable. {@code aDuplicate}: rows 1 and 2 are one record. */
    private static IDataTable ordered(String aDomain, boolean aDuplicate)
    {
        String p = aDomain;
        String secondVisit = aDuplicate ? "1" : "2";
        String secondDate = aDuplicate ? "2020-01-01" : "2020-01-02";
        String secondResult = aDuplicate ? "1" : "2";
        return table(aDomain, List.of("STUDYID", "DOMAIN", "USUBJID", p + "SEQ", p + "TESTCD",
                p + "TEST", p + "ORRES", p + "REPNUM", "VISITNUM", p + "DTC"), new String[]
        {
                "S", aDomain, "U1", "1", "T1", "Test", "1", "1", "1", "2020-01-01"
        }, new String[]
        {
                "S", aDomain, "U1", "2", "T1", "Test", secondResult, "1", secondVisit, secondDate
        }, new String[]
        {
                "S", aDomain, "U1", "3", "T1", "Test", "3", "1", "3", "2020-01-03"
        });
    }


    @Test
    void theAllowedVariableRulesReportOnlyTheSponsorVariable() throws IOException
    {
        IDataTable xx = outOfOrder("XX");
        assertEquals(Map.of("XXFOO", Severity.ERROR),
                byVariable(run(FDA_34, SDTMIG_34, "FDA-SD0058", xx), "FDA-SD0058"));
        for (String[] rule : new String[][]
        {
                {
                        PMDA_34, "PMDA-SD0058"
                },
                {
                        CDISC_34, "CDISC-CG0013"
                },
                {
                        CDISC_34, "CDISC-CG0351"
                }
        })
        {
            assertEquals(Set.of("XXFOO"),
                    byVariable(run(rule[0], SDTMIG_34, rule[1], xx), rule[1]).keySet(), rule[1]);
        }
    }


    @Test
    void theOrderRulesJudgeTheSponsorDomainsOrder() throws IOException
    {
        String[][] rules =
        {
                {
                        CDISC_34, SDTMIG_34, "CDISC-CG0664"
                },
                {
                        FDA_34, SDTMIG_34, "FDA-SD1079"
                },
                {
                        PMDA_34, SDTMIG_34, "PMDA-SD1079"
                },
                {
                        "rules-cdisc-sdtmig-3-3.json", "sdtmig 3-3", "CDISC-CG0330"
                },
                {
                        "rules-cdisc-sendig-dart-1-1.json", "sendig dart-1-1", "CDISC-SEND-0393"
                }
        };
        for (String[] rule : rules)
        {
            assertEquals(1, count(run(rule[0], rule[1], rule[2], outOfOrder("XX")), rule[2]),
                    rule[2] + " reports the out-of-order sponsor domain");
            assertEquals(0, count(run(rule[0], rule[1], rule[2], ordered("XX", false)), rule[2]),
                    rule[2] + " is silent on the model-ordered one");
        }
    }


    @Test
    void theTimingRulesSeeTheSponsorDomainsTimingVariables() throws IOException
    {
        String[][] rules =
        {
                {
                        CDISC_34, "CDISC-CG0219"
                },
                {
                        FDA_34, "FDA-SD1299"
                },
                {
                        PMDA_34, "PMDA-SD1299"
                }
        };
        IDataTable noTiming = table("XX",
                List.of("STUDYID", "DOMAIN", "USUBJID", "XXSEQ", "XXTESTCD", "XXORRES"),
                new String[]
                {
                        "S", "XX", "U1", "1", "T1", "1"
                });
        for (String[] rule : rules)
        {
            // VISITNUM and XXDTC are present: the pre-fix "no timing variables" finding is gone.
            assertEquals(0, count(run(rule[0], SDTMIG_34, rule[1], outOfOrder("XX")), rule[1]),
                    rule[1]);
            // ... and the rule still has teeth on a sponsor domain that really has none.
            assertEquals(1, count(run(rule[0], SDTMIG_34, rule[1], noTiming), rule[1]),
                    rule[1] + " on a dataset with no timing variable");
        }
    }


    @Test
    void theDuplicateAndRepeatRulesKeyOnTheSponsorDomainsTiming() throws IOException
    {
        for (String[] rule : new String[][]
        {
                {
                        FDA_34, "FDA-SD1117"
                },
                {
                        PMDA_34, "PMDA-SD1117"
                },
                {
                        CDISC_34, "CDISC-CG0562"
                }
        })
        {
            // Three visits, three dates: before the fix the key was USUBJID + XXTESTCD (+ an
            // empty timing list) and all three rows collided.
            assertEquals(0, count(run(rule[0], SDTMIG_34, rule[1], outOfOrder("XX")), rule[1]),
                    rule[1]);
            // A real duplicate — two rows one record — is still reported, on both rows.
            assertEquals(2, count(run(rule[0], SDTMIG_34, rule[1], ordered("XX", true)), rule[1]),
                    rule[1] + " on a real duplicate");
        }
    }


    @Test
    void theParentModelRulesJudgeASponsorSuppQnam() throws IOException
    {
        IDataTable xx = outOfOrder("XX");
        for (String[] rule : new String[][]
        {
                {
                        CDISC_34, SDTMIG_34, "CDISC-CG0314"
                },
                {
                        "rules-cdisc-sendig-3-0.json", "sendig 3-0", "CDISC-SEND-0274"
                }
        })
        {
            for (String qnam : List.of("XXSTRESC", "XXFOO"))
            {
                IDataTable supp = table("SUPPXX", List.of("STUDYID", "RDOMAIN", "USUBJID", "IDVAR",
                        "IDVARVAL", "QNAM", "QLABEL", "QVAL"), new String[]
                {
                        "S", "XX", "U1", "XXSEQ", "1", qnam, "L", "v"
                });
                // XXSTRESC is a FINDINGS model variable under the parent's prefix; XXFOO is not.
                assertEquals("XXSTRESC".equals(qnam) ? 1 : 0,
                        count(run(rule[0], rule[1], rule[2], supp, xx), rule[2] + " " + qnam),
                        rule[2] + " QNAM=" + qnam);
            }
        }
    }


    @Test
    void theSendClassTableRulesJudgeASponsorDomainAfterTheRebind() throws IOException
    {
        // CDW-D2 (b): $library_order binds get_dataset_filtered_variables(), which answers on an
        // IG-absent domain; get_column_order_from_library() would still SKIP both rules.
        IDataTable xx = table("XX", List.of("STUDYID", "DOMAIN", "USUBJID", "XXSEQ", "XXTESTCD",
                "XXTEST", "XXORRES", "XXDOSE", "XXFOO", "XXDTC"), new String[]
        {
                "S", "XX", "U1", "1", "T1", "Test", "1", "5", "f", "2020-01-01"
        });
        String pkg = "rules-cdisc-sendig-3-1-1.json";
        // XXDOSE belongs to INTERVENTIONS, not to FINDINGS: another class's variable.
        assertEquals(Set.of("XXDOSE"),
                byVariable(run(pkg, "sendig 3-1-1", "CDISC-SEND-0269", xx), "SEND-0269").keySet());
        // XXFOO is in no class at all.
        assertEquals(Set.of("XXFOO"),
                byVariable(run(pkg, "sendig 3-1-1", "CDISC-SEND-0270", xx), "SEND-0270").keySet());
        // §3d: the rules whose subject is the IG's own presentation keep skipping.
        assertTrue(run(pkg, "sendig 3-1-1", "CDISC-SEND-0048", xx).isSkipped(), "SEND-0048");
        assertTrue(run("rules-fda-sendig-3-1-1.json", "sendig 3-1-1", "FDA-SD1076", xx).isSkipped(),
                "FDA-SD1076");
    }

    // ------------------------------------------------------------------
    // 3. The shipped rules — standard domains the IG leaves out
    // ------------------------------------------------------------------


    /** The §2.3 shape: one subject, one test, three dates. */
    private static IDataTable standardFindings(String aDomain)
    {
        String p = aDomain;
        return table(aDomain, List.of("STUDYID", "DOMAIN", "USUBJID", p + "SEQ", p + "TESTCD",
                p + "TEST", p + "ORRES", p + "DTC", p + "DY"), new String[]
        {
                "S", aDomain, "U1", "1", "T1", "Test", "1", "2020-01-01", "1"
        }, new String[]
        {
                "S", aDomain, "U1", "2", "T1", "Test", "2", "2020-01-02", "2"
        }, new String[]
        {
                "S", aDomain, "U1", "3", "T1", "Test", "3", "2020-01-03", "3"
        });
    }


    @Test
    void theFalsePositivesOnIgAbsentStandardFindingsAreGone() throws IOException
    {
        String[][] runs =
        {
                {
                        "rules-fda-sendig-ar-1-0.json", "sendig ar-1-0", "LB"
                },
                {
                        "rules-fda-sendig-ar-1-0.json", "sendig ar-1-0", "CL"
                },
                {
                        "rules-fda-sendig-dart-1-1.json", "sendig dart-1-1", "EG"
                },
                {
                        "rules-fda-sendig-dart-1-1.json", "sendig dart-1-1", "VS"
                }
        };
        for (String[] r : runs)
        {
            IDataTable t = standardFindings(r[2]);
            String what = r[1] + " " + r[2];
            // Before the fix: FDA-SD1117 3 and FDA-SD1299 1 on each (§2.3).
            assertEquals(0, count(run(r[0], r[1], "FDA-SD1117", t), what + " FDA-SD1117"));
            assertEquals(0, count(run(r[0], r[1], "FDA-SD1299", t), what + " FDA-SD1299"));
            // Every column is a model variable of the class: judged, and clean.
            assertEquals(Map.of(), byVariable(run(r[0], r[1], "FDA-SD0058", t), what));
        }
    }


    @Test
    void anExposureMethodIsModelAllowedAndASponsorVariableAnErrorWhereTheIgLeavesExposureOut()
        throws IOException
    {
        // P10: EX is IG-absent at SENDIG-AR 1.0, so its IG list is its model walk; a variable in
        // neither list is the ruled ladder's ERROR rung (EXFOO).
        // 2026-10-03 store format 5: EXMETHOD used to be that ERROR here, and a WARNING (IG-only)
        // at SENDIG 3.1.1 and DART 1.1. The errata register adds the printed domain-specific
        // tables to SDTM 1.5-1.8 (metadata issue #283.1-283.4), where EXMETHOD is an Interventions
        // root restricted to EX ("Not in human clinical trials; EX domain only"). SENDIG-AR 1.0
        // links SDTM 1.8, SENDIG 3.1.1 SDTM 1.5, DART 1.1 SDTM 1.6, so EXMETHOD is now a model
        // variable of EX in all three and is silent.
        IDataTable ex = table("EX", List.of("STUDYID", "DOMAIN", "USUBJID", "EXSEQ", "EXTRT",
                "EXMETHOD", "EXSTDTC", "EXFOO"), new String[]
        {
                "S", "EX", "U1", "1", "T", "M", "2020-01-01", "X"
        });
        assertEquals(Map.of("EXFOO", Severity.ERROR), byVariable(
                run("rules-fda-sendig-ar-1-0.json", "sendig ar-1-0", "FDA-SD0058", ex), "AR"));
        assertEquals(Map.of("EXFOO", Severity.ERROR), byVariable(
                run("rules-fda-sendig-3-1-1.json", "sendig 3-1-1", "FDA-SD0058", ex), "3.1.1"));
        assertEquals(Map.of("EXFOO", Severity.ERROR),
                byVariable(
                        run("rules-fda-sendig-dart-1-1.json", "sendig dart-1-1", "FDA-SD0058", ex),
                        "DART 1.1"));
    }

    // ------------------------------------------------------------------
    // 4. The EC-40 record key follows the walk
    // ------------------------------------------------------------------


    /** The NATURAL tier's expected columns, read off the provider's own walk rows. */
    private static List<String> naturalTierColumns(MetadataProvider aProvider, IDataTable aTable)
    {
        Set<String> columns = columnsOf(aTable);
        String seq = aTable.getMetaData().getName() + "SEQ";
        List<String> out = new ArrayList<>();
        for (Map<String, String> row : aProvider.getStandardVariablesDetailed(aTable, null))
        {
            String name = row.get("name");
            if (NATURAL_TIER_ROLES.contains(row.get("role")) && columns.contains(name)
                    && !List.of("STUDYID", "DOMAIN", "USUBJID", seq).contains(name))
            {
                out.add(name);
            }
        }
        return out;
    }


    @Test
    void theRecordKeyOfAnIgAbsentDomainIsItsNaturalKey()
    {
        String[][] cases =
        {
                {
                        SDTMIG_34, "XX"
                },
                {
                        "sendig ar-1-0", "LB"
                }
        };
        for (String[] c : cases)
        {
            MetadataProvider provider = provider(c[0]);
            IDataTable t = "XX".equals(c[1]) ? outOfOrder("XX") : standardFindings(c[1]);
            RowKeySpec spec = RecordKeyResolver.resolve(t, c[1], FindingKeyMode.FULL, null,
                    provider, _ -> null, "R1");
            List<String> expected = naturalTierColumns(provider, t);
            assertTrue(expected.contains(c[1] + "TESTCD"), c[0] + " " + c[1] + ": " + expected);
            assertEquals(KeySource.NATURAL, spec.source(), c[0] + " " + c[1]);
            assertEquals(expected,
                    spec.columns().stream().map(RecordKeyResolver.KeyColumn::name).toList(),
                    c[0] + " " + c[1]);
        }
    }

    // ------------------------------------------------------------------
    // 5. An unserved key_name SKIPs the rule (CDW-F1, PLAN-unserved-key-name-skip)
    // ------------------------------------------------------------------


    private static void assertSkipsUnserved(RuleExecutionResult aResult, String aWhat)
    {
        assertTrue(aResult.isSkipped(), aWhat + " must SKIP: " + aResult.getStatusMessage());
        // RuleRunner's generic text (U-HOW): the key and the dataset are in the log line.
        assertEquals("Rule skipped — library returned no data for get_dataset_filtered_variables",
                aResult.getStatusMessage(), aWhat);
    }


    @Test
    void theCoreFilterRulesSkipWhereTheLibraryRowsCarryNoCore() throws IOException
    {
        // The walk serves an IG-absent domain its class's SDTM Model rows, which carry no `core`:
        // get_dataset_filtered_variables(key_name="core", key_value=…) can never match there, so
        // the three core carriers SKIP instead of reporting "executed, 0 findings".
        IDataTable xx = outOfOrder("XX");
        for (String[] rule : new String[][]
        {
                {
                        FDA_34, "FDA-SD1078"
                },
                {
                        PMDA_34, "PMDA-SD1078"
                },
                {
                        FDA_34, "FDA-SD1149"
                }
        })
        {
            assertSkipsUnserved(run(rule[0], SDTMIG_34, rule[1], xx), rule[1] + " SDTMIG 3.4 XX");
        }
        // A standard domain an older IG does not define is the same case (P0 census: 3.3 lacks
        // BE BS CP GF).
        IDataTable gf = igShaped("GF", provider(SDTMIG_34).getColumnOrder("GF"));
        assertSkipsUnserved(run("rules-fda-sdtmig-3-3.json", "sdtmig 3-3", "FDA-SD1078", gf),
                "FDA-SD1078 SDTMIG 3.3 GF");
        // SEND: SENDIG 3.0 leaves CV out and has no base-IG view (B-SCOPE) — the control. The
        // AR / DART populations execute now (theCoreFilterRulesExecuteOnTheBaseIgView...).
        IDataTable cv = igShaped("CV", provider("sendig 3-1-1").getColumnOrder("CV"));
        assertSkipsUnserved(run("rules-fda-sendig-3-0.json", "sendig 3-0", "FDA-SD1149", cv),
                "FDA-SD1149 SENDIG 3.0 CV");
    }


    @Test
    void theCoreFilterRulesExecuteOnTheBaseIgViewExactlyAsTheBaseJudges() throws IOException
    {
        // PLAN-send-base-ig-view, the G 5 populations: SKIPPED (no `core` on the model rows) ->
        // executed, with the base SENDIG's `core`. The oracle is the same shipped rule over the
        // base's own provider (SENDIG 3.1; FDA-SD1078 ships at SENDIG-AR only). count() asserts
        // the run EXECUTED — the move itself.
        String ar = "rules-fda-sendig-ar-1-0.json";
        String dart = "rules-fda-sendig-dart-1-1.json";
        for (String id : List.of("FDA-SD1078", "FDA-SD1149"))
        {
            IDataTable lb = standardFindings("LB");
            assertEquals(count(run(ar, "sendig 3-1", id, lb), id + " SENDIG 3.1 LB"),
                    count(run(ar, "sendig ar-1-0", id, lb), id + " SENDIG-AR 1.0 LB"), id);
        }
        IDataTable eg = standardFindings("EG");
        assertEquals(count(run(dart, "sendig 3-1", "FDA-SD1149", eg), "FDA-SD1149 SENDIG 3.1 EG"),
                count(run(dart, "sendig dart-1-1", "FDA-SD1149", eg),
                        "FDA-SD1149 SENDIG-DART 1.1 EG"));
    }


    @Test
    void theCoreFilterRulesStillJudgeWhereTheKeyIsServed() throws IOException
    {
        // The controls: an IG-defined domain serves `core` (no SKIP, and the rule has teeth), and
        // an AR-absent dataset whose walk resolves nothing keeps its empty answer (U-DEF).
        IDataTable lb = table("LB", List.of("STUDYID", "DOMAIN", "USUBJID", "LBSEQ", "LBTESTCD",
                "LBTEST", "LBORRES", "LBSPEC"), new String[]
        {
                "S", "LB", "U1", "1", "T1", "Test", "1", ""
        });
        // LBSPEC is Permissible in SDTMIG 3.4 LB and empty on every record.
        assertEquals(1, count(run(FDA_34, SDTMIG_34, "FDA-SD1078", lb), "FDA-SD1078 LB"));
        assertEquals(1, count(run(PMDA_34, SDTMIG_34, "PMDA-SD1078", lb), "PMDA-SD1078 LB"));
        assertEquals(0, count(run(FDA_34, SDTMIG_34, "FDA-SD1149", lb), "FDA-SD1149 LB"));
        MetadataProvider reference = provider("sendig 3-1");
        IDataTable ts = igShaped("TS", reference.getColumnOrder("TS"));
        // The AR TS control, re-read against the base-IG view (PLAN-send-base-ig-view): the walk
        // now resolves SENDIG 3.1's TS rows, and the rules answer what a SENDIG 3.1 run answers.
        assertFalse(provider("sendig ar-1-0").getStandardVariablesDetailed(ts, _ -> null).isEmpty(),
                "AR TS: the walk resolves the base's TS");
        for (String id : List.of("FDA-SD1078", "FDA-SD1149"))
        {
            assertEquals(
                    count(run("rules-fda-sendig-ar-1-0.json", "sendig 3-1", id, ts),
                            id + " 3.1 TS"),
                    count(run("rules-fda-sendig-ar-1-0.json", "sendig ar-1-0", id, ts),
                            id + " AR TS"),
                    id + " AR TS: the base's answer");
        }
    }


    /**
     * PACK G fold (G5 LOW5): at <b>every</b> package the corpus ships a core carrier in, one domain
     * that package's IG defines serves {@code core}, so the rule executes — an IG-defined domain
     * never SKIPs as unserved. The packages are the corpus's population ({@link #shippingFiles}),
     * not a list written here; the domain is {@code LB}, else {@code DM} (SENDIG-AR 1.0 leaves
     * {@code LB} out), chosen by the IG's own dataset table, never by whether its rows carry
     * {@code core}.
     */
    @Test
    void theCoreFilterRulesExecuteOnAnIgDefinedDomainAtEveryShippingPackage() throws IOException
    {
        int runs = 0;
        for (String id : List.of("FDA-SD1078", "PMDA-SD1078", "FDA-SD1149"))
        {
            for (Map.Entry<String, String> pkg : shippingFiles(id).entrySet())
            {
                MetadataProvider provider = provider(pkg.getKey());
                String domain = Stream.of("LB", "DM")
                        .filter(d -> !provider.getColumnOrder(d).isEmpty()).findFirst()
                        .orElseThrow(() -> new AssertionError(
                                pkg.getKey() + " defines neither LB nor DM"));
                String what = id + " " + pkg.getKey() + " " + domain;
                executed(run(pkg.getValue(), pkg.getKey(), id,
                        igShaped(domain, provider.getColumnOrder(domain))), what);
                runs++;
            }
        }
        assertTrue(runs >= 6, "the shipping population must not go dark: " + runs + " runs");
    }

    // ------------------------------------------------------------------
    // 6. Sniffer miss — unchanged (CDW-D3 (b))
    // ------------------------------------------------------------------


    @Test
    void aSponsorDomainNoTierPlacesKeepsTodaysVerdicts() throws IOException
    {
        IDataTable miss = table("XX",
                List.of("STUDYID", "DOMAIN", "USUBJID", "XXSEQ", "XXVAL", "VISITNUM", "XXDTC"),
                new String[]
                {
                        "S", "XX", "U1", "1", "v", "1", "2020-01-01"
                });
        MetadataProvider provider = provider(SDTMIG_34);
        assertEquals(List.of(), provider.getStandardModelVariables(miss, null));
        for (String[] rule : new String[][]
        {
                {
                        FDA_34, "FDA-SD0058"
                },
                {
                        FDA_34, "FDA-SD1079"
                },
                {
                        CDISC_34, "CDISC-CG0013"
                }
        })
        {
            assertTrue(run(rule[0], SDTMIG_34, rule[1], miss).isSkipped(), rule[1] + " SKIPS");
        }
        for (String id : List.of("FDA-SD1078", "FDA-SD1149"))
        {
            assertEquals(0, count(run(FDA_34, SDTMIG_34, id, miss), id), id);
        }
    }
}
