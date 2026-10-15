package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-library-var-custom-domains} §8 E8 — {@code var_*("LIBRARY")} and the shipped rules
 * that read it, on a dataset whose domain the run's IG does <b>not</b> define, over the <b>real</b>
 * library-metadata provider and a real Define-XML.
 *
 * <p>
 * Such a domain is a sponsor's own ({@code XX}), or a standard one the run's IG leaves out: at
 * SENDIG-AR 1.0 every SEND domain but {@code DM}, at SENDIG-DART 1.1 {@code EG PM TF VS CV RE}. The
 * read is keyed by {@code (domain, variable)} and the provider's metadata library is the IG
 * product's table view, which has no table for it — so before the fix every read was {@code {}}:
 * the type and role rules passed silently, and {@code CDISC-SEND-0005}
 * ({@code var_name("DEFINE") != var_name("LIBRARY")}, unguarded) reported <b>every</b>
 * Define-declared variable. The read's last tier now serves the model row of the dataset's scope
 * class: name, type and role, never a label, codelist or core ({@code LVC-D1} (a)).
 * </p>
 *
 * <p>
 * A {@code .cdt} scenario cannot see any of this: the map-backed test provider serves declared
 * metadata and never executes {@link MetadataLibraryProvider}. Every provider here is built by the
 * production factory ({@link StoreMetadataProviderFactory#forSdtm}) over the store
 * {@link MetadataStoreFixture} seeds into {@code target/}; every rule is the shipped one, loaded
 * from its generated package; the packages a rule is run at are the corpus's own, never a list
 * written here. The Define-XML fixture declares the standard SEND domains with SENDIG 3.1.1's roles
 * (a conformant Define) and the sponsor {@code XX} with the model's roles, except {@code XXTEST}
 * (deliberately {@code Topic}).
 * </p>
 *
 * <p>
 * ⛔ No assumption skips: without a pickle corpus the store cannot be seeded and every test here
 * FAILS, naming the variable to set.
 * </p>
 */
class LibraryVarCustomDomainStoreTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    /** The only attributes the IG-absent tier may serve ({@code LVC-D1} (a)). */
    private static final Set<String> SERVED = Set.of("name", "simpleDatatype", "role");

    /** §3a/§3b: the rules that read a LIBRARY type. */
    private static final List<String> TYPE_RULES = List.of("CDISC-CG0012", "FDA-SD0055",
            "PMDA-SD0055", "FDA-SE0055", "CDISC-SEND-0271");

    /** §3a/§3b: the rules that compare a Define role with the LIBRARY role. */
    private static final List<String> ROLE_RULES = List.of("CDISC-CG0010", "CDISC-SEND-0009.1");

    /** §3b: the unguarded Define-name rule. */
    private static final String SEND_0005 = "CDISC-SEND-0005";

    /** §3c: the shipped label rules — withheld, so silent on an IG-absent domain. */
    private static final List<String> LABEL_RULES = List.of("FDA-SD0063A", "PMDA-SD0063A",
            "FDA-SE0063", "CDISC-CG0303", "CDISC-SEND-0276");

    /** §3d and the DRAFT codelist rules: IG attributes the model does not carry — inert. */
    private static final List<String> INERT_RULES = List.of("CDISC-SEND-0007", "CDISC-SEND-0014",
            "CDISC-SEND-0040", "CDISC-SEND-0049", "CDISC-SEND-0050", "CDISC-SEND-0052",
            "CDISC-SEND-0055", "CDISC-SEND-0296", "CDISC-SEND-0340", "FDA-CT2001", "FDA-CT2002",
            "PMDA-CT2001", "PMDA-CT2002", "DRAFT-900021", "DRAFT-900022", "DRAFT-900023",
            "DRAFT-900024");

    /** The general-observation SEND domains SENDIG-AR 1.0 leaves out (plan §2.4). */
    private static final Set<String> AR_GO_ABSENT = Set.of("DS", "EX", "PC", "PP", "BW", "BG", "CL",
            "DD", "EG", "FW", "LB", "MA", "MI", "OM", "PM", "SC", "TF", "VS", "CV", "RE");

    /** The special-purpose, trial-design and relationship SEND domains SENDIG-AR 1.0 leaves out. */
    private static final Set<String> AR_NON_GO_ABSENT = Set.of("CO", "SE", "TA", "TE", "TX", "TS",
            "POOLDEF", "RELREC");

    /** The SEND domains SENDIG-DART 1.1 and 1.2 leave out, all general-observation (Findings). */
    private static final Set<String> DART_ABSENT = Set.of("EG", "PM", "TF", "VS", "CV", "RE");

    /** The Define-XML fixture, parsed once. */
    private static final MetadataProvider DEFINE = define();

    /** Generated packages already loaded, by file name (loading one is the expensive part). */
    private static final Map<String, Map<String, Rule>> PACKAGES = new ConcurrentHashMap<>();

    /** The sponsor-domain class shapes: the columns → the class the scope ladder gives them. */
    private static final Map<String, List<String>> SHAPES = shapes();

    private static Map<String, List<String>> shapes()
    {
        Map<String, List<String>> s = new LinkedHashMap<>();
        s.put("EVENTS", List.of("STUDYID", "USUBJID", "XXSEQ", "XXTERM", "XXSTDTC", "XXFOO"));
        s.put("INTERVENTIONS", List.of("STUDYID", "USUBJID", "XXSEQ", "XXTRT", "XXSTDTC", "XXFOO"));
        s.put("FINDINGS",
                List.of("STUDYID", "USUBJID", "XXSEQ", "XXTESTCD", "XXORRES", "XXDTC", "XXFOO"));
        s.put("FINDINGS ABOUT", List.of("STUDYID", "USUBJID", "XXSEQ", "XXTESTCD", "XXOBJ",
                "XXORRES", "XXDTC", "XXFOO"));
        return s;
    }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------


    private static StoreMetadataProviderFactory factory()
    {
        return MetadataStoreFixture.factory().orElseThrow(() -> new IllegalStateException(
                "No metadata store: the LIBRARY read is a property of the CDISC Library products,"
                        + " not of this repository. Set CDISC_PICKLE_CACHE_DIR (or"
                        + " -Dcdisc.pickle.cache.dir) to a pickle cache directory —"
                        + " MetadataStoreFixture seeds the store from it."));
    }


    private static MetadataProvider provider(String aPackage)
    {
        String[] parts = aPackage.split(" ", -1);
        return factory().forSdtm(parts[0], parts[1], List.of()).orElseThrow(
                () -> new AssertionError("the seeded store lacks the IG product " + aPackage));
    }


    private static boolean storeServes(String aPackage)
    {
        String[] parts = aPackage.split(" ", -1);
        return factory().forSdtm(parts[0], parts[1], List.of()).isPresent();
    }


    private static MetadataProvider define()
    {
        try
        {
            Path file = Path.of(LibraryVarCustomDomainStoreTest.class
                    .getResource("LibraryVarCustomDomainStoreTest-define.xml").toURI());
            return new DefineXmlMetadataProvider(
                    new OdmDefineXMLProvider(new DefineXmlParser().parse(file.toFile())), null);
        }
        catch (IOException | URISyntaxException e)
        {
            throw new IllegalStateException("the Define-XML fixture does not parse", e);
        }
    }


    /** The LIBRARY variable read exactly as the Check and the finding enrichment make it. */
    private static Map<String, String> read(MetadataProvider aProvider, IDataTable aTable,
            String aVariable)
    {
        return ExprCompiler.libraryVariableMetadata(aProvider, aTable, NO_RESOLVER,
                aTable.getMetaData().getName(), aVariable);
    }


    /**
     * The same provider with the IG-absent tier switched off — today's read. The method is looked
     * up by signature, so a rename fails here instead of silently disabling nothing.
     */
    private static MetadataProvider withoutTheTier(MetadataProvider aReal)
    {
        Method tier;
        try
        {
            tier = MetadataProvider.class.getMethod("getIgAbsentVariableMetadata", IDataTable.class,
                    DatasetResolver.class, String.class, String.class);
        }
        catch (NoSuchMethodException e)
        {
            throw new AssertionError("the tier's signature changed", e);
        }
        return (MetadataProvider) Proxy.newProxyInstance(MetadataProvider.class.getClassLoader(),
                new Class<?>[]
                {
                        MetadataProvider.class
                }, (proxy, method, args) ->
                {
                    if (method.equals(tier))
                    {
                        return Map.of();
                    }
                    try
                    {
                        return method.invoke(aReal, args);
                    }
                    catch (InvocationTargetException e)
                    {
                        throw e.getCause();
                    }
                });
    }


    private static List<String> columnsOf(IDataTable aTable)
    {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < aTable.getMetaData().getColumnCount(); i++)
        {
            out.add(aTable.getMetaData().getColumn(i).getName());
        }
        return out;
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


    /**
     * The Findings shape of plan §2.2, as the Define-XML fixture declares it for {@code aDomain}.
     */
    private static List<String> findingsColumns(String aDomain)
    {
        return List.of("STUDYID", "DOMAIN", "USUBJID", aDomain + "SEQ", aDomain + "TESTCD",
                aDomain + "TEST", aDomain + "ORRES", aDomain + "STRESC", "VISITNUM",
                aDomain + "DTC", aDomain + "FOO");
    }


    /**
     * A two-row dataset with the given columns and labels. {@code --SEQ} is numeric, or character
     * when {@code aSeqChar} (a genuine type error); {@code VISITNUM} and {@code EXDOSE} are
     * numeric, as the model types them; everything else character.
     */
    private static IDataTable labelled(String aName, List<String> aColumns,
            Map<String, String> aLabels, boolean aSeqChar)
    {
        MockTable t = MockTable.of().name(aName);
        for (String column : aColumns)
        {
            if ("DOMAIN".equals(column))
            {
                t.col(column, aName, aName);
            }
            else if (column.equals(aName + "SEQ") && !aSeqChar)
            {
                t.colLong(column, 1L, 2L);
            }
            else if (column.equals(aName + "SEQ"))
            {
                t.col(column, "1", "2");
            }
            else if ("VISITNUM".equals(column) || "EXDOSE".equals(column))
            {
                t.colDouble(column, 1.0, 2.0);
            }
            else
            {
                t.col(column, "a", "b");
            }
            t.colMeta(column, aLabels.getOrDefault(column, column), 0, null);
        }
        return t.build();
    }


    /**
     * The sponsor Findings dataset of plan §2.2: model labels, except three a sponsor adjusted, as
     * SDTMIG 3.4 §2.6 step 3h allows on a new domain.
     */
    private static IDataTable sponsorFindings(boolean aSeqChar)
    {
        Map<String, String> labels = new HashMap<>();
        labels.put("STUDYID", "Study Identifier");
        labels.put("DOMAIN", "Domain Abbreviation");
        labels.put("USUBJID", "Unique Subject Identifier");
        labels.put("XXSEQ", "Sequence Number");
        labels.put("XXTESTCD", "Sponsor Test Short Name");
        labels.put("XXTEST", "Sponsor Test Name");
        labels.put("XXORRES", "Result or Finding in Original Units");
        labels.put("XXSTRESC", "Character Result/Finding in Std Format");
        labels.put("VISITNUM", "Visit Number");
        labels.put("XXDTC", "Date/Time of Collection");
        labels.put("XXFOO", "Sponsor Variable");
        return labelled("XX", findingsColumns("XX"), labels, aSeqChar);
    }


    /** A standard SEND dataset carrying SENDIG 3.1.1's own labels — a conformant dataset. */
    private static IDataTable conformantSend(String aDomain, List<String> aColumns,
            boolean aSeqChar)
    {
        MetadataProvider reference = provider("sendig 3-1-1");
        Map<String, String> labels = new HashMap<>();
        for (String column : aColumns)
        {
            String label = reference.getVariableMetadata(aDomain, column).get("label");
            labels.put(column, label != null ? label : "Sponsor Variable");
        }
        return labelled(aDomain, aColumns, labels, aSeqChar);
    }


    /** The rules of one generated package, loaded once. */
    private static Map<String, Rule> rulesOf(String aPackageFile) throws IOException
    {
        Map<String, Rule> rules = PACKAGES.get(aPackageFile);
        if (rules == null)
        {
            rules = RulePackageLoader.load(CorpusPaths.rules(aPackageFile)).getRules();
            PACKAGES.put(aPackageFile, rules);
        }
        return rules;
    }


    /**
     * The same standard SEND dataset with a label no standard gives any variable — the dataset a
     * SENDIG label rule exists to report.
     */
    private static IDataTable wronglyLabelledSend(String aDomain, List<String> aColumns)
    {
        Map<String, String> labels = new HashMap<>();
        for (String column : aColumns)
        {
            labels.put(column, "Not The Standard Label");
        }
        return labelled(aDomain, aColumns, labels, false);
    }


    private static Rule shipped(String aPackageFile, String aRuleId) throws IOException
    {
        Rule rule = rulesOf(aPackageFile).get(aRuleId);
        assertNotNull(rule, aRuleId + " must ship in " + aPackageFile);
        assertNull(rule.getLoadError(), aRuleId + " must load clean: " + rule.getLoadError());
        return rule;
    }


    /**
     * The generated packages {@code aRuleId} ships in that the store serves, as package file →
     * store key ({@code rules-cdisc-sendig-dart-1-1.json} → {@code "sendig dart-1-1"}) — the
     * corpus's population, not one written into this class.
     */
    private static Map<String, String> servedPackages(String aRuleId) throws IOException
    {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(CorpusPaths.rules()))
        {
            for (Path file : files.sorted().toList())
            {
                String name = file.getFileName().toString();
                if (!name.startsWith("rules-") || !name.endsWith(".json"))
                {
                    continue;
                }
                String[] parts = name.substring(0, name.length() - ".json".length()).split("-", 4);
                String store = parts[2] + " " + parts[3];
                if (("sdtmig".equals(parts[2]) || "sendig".equals(parts[2]))
                        && rulesOf(name).containsKey(aRuleId) && storeServes(store))
                {
                    out.put(name, store);
                }
            }
        }
        assertFalse(out.isEmpty(), aRuleId + " ships in no package the store serves");
        return out;
    }


    /** Runs a shipped rule on {@code aTable} with the fixture Define-XML. */
    private static RuleExecutionResult run(String aPackageFile, MetadataProvider aLibrary,
            String aRuleId, IDataTable aTable)
        throws IOException
    {
        return RuleRunnerCalls.execute(shipped(aPackageFile, aRuleId), aTable,
                name -> name.equals(aTable.getMetaData().getName()) ? aTable : null,
                aTable.getMetaData().getName(), aLibrary, null, DEFINE);
    }


    /** The variables an executed rule reports, by whichever name cell its universe carries. */
    private static Set<String> reported(RuleExecutionResult aResult, String aWhat)
    {
        assertFalse(aResult.isSkipped(), aWhat + " must execute: " + aResult.getStatusMessage());
        assertFalse(aResult.isError(), aWhat + " must not error: " + aResult.getStatusMessage());
        Set<String> out = new TreeSet<>();
        for (Violation v : aResult.getViolations())
        {
            String variable = v.getValues().get("variable_name");
            if (variable == null)
            {
                variable = v.getValues().get("define_variable_name");
            }
            assertNotNull(variable, aWhat + ": every finding names its variable: " + v);
            out.add(variable);
        }
        return out;
    }


    private static Violation findingOn(RuleExecutionResult aResult, String aVariable)
    {
        for (Violation v : aResult.getViolations())
        {
            if (aVariable.equals(v.getValues().get("variable_name"))
                    || aVariable.equals(v.getValues().get("define_variable_name")))
            {
                return v;
            }
        }
        throw new AssertionError("no finding on " + aVariable + ": " + aResult.getViolations());
    }


    /** The columns minus the sponsor {@code --FOO} variable no standard defines. */
    private static Set<String> standardColumns(List<String> aColumns)
    {
        Set<String> out = new TreeSet<>(aColumns);
        out.removeIf(c -> c.endsWith("FOO"));
        return out;
    }


    /** The stored model dataset's rows by variable name. */
    private static Map<String, StoredVariable> modelDatasetRows(String aModelKey, String aDataset)
    {
        Map<String, StoredVariable> out = new LinkedHashMap<>();
        for (StoredDataset ds : StoredSendRosters.stored(aModelKey).datasets())
        {
            if (aDataset.equals(ds.name()))
            {
                ds.variables().forEach(v -> out.put(v.name(), v));
            }
        }
        assertFalse(out.isEmpty(), aModelKey + " defines " + aDataset);
        return out;
    }


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

    // ------------------------------------------------------------------
    // 1. The read — the scope class's model row, name / type / role only
    // ------------------------------------------------------------------


    @Test
    void aSponsorDomainReadsItsScopeClassRowAtEveryPackage() throws IOException
    {
        Set<String> packages = new TreeSet<>();
        for (String rule : List.of("CDISC-CG0012", "FDA-SD0055", "FDA-SE0055", "CDISC-SEND-0271",
                "CDISC-CG0010", "CDISC-SEND-0009.1", SEND_0005))
        {
            packages.addAll(servedPackages(rule).values());
        }
        assertEquals(
                new TreeSet<>(List.of("sdtmig 3-1-2", "sdtmig 3-1-3", "sdtmig 3-2", "sdtmig 3-3",
                        "sdtmig 3-4", "sendig 3-0", "sendig 3-1", "sendig 3-1-1", "sendig ar-1-0",
                        "sendig dart-1-1", "sendig dart-1-2", "sendig genetox-1-0")),
                packages, "sendig dart-1-2 since 2026-10-10: the E14 seed supplement"
                        + " (PLAN-seed-sendig-dart-1-2)");
        int checked = 0;
        for (String pkg : packages)
        {
            MetadataProvider provider = provider(pkg);
            for (Map.Entry<String, List<String>> shape : SHAPES.entrySet())
            {
                IDataTable xx = dataset("XX", shape.getValue());
                String scope = ScopeClassLadder.classOf(provider, "XX", "XX", xx, NO_RESOLVER);
                assertEquals(shape.getKey(), scope, pkg + " XX scope class");
                List<Map<String, String>> forced = provider.getStandardModelVariablesForClass(xx,
                        null, scope);
                assertNotNull(forced);
                Map<String, Map<String, String>> rows = new HashMap<>();
                forced.forEach(r -> rows.put(r.get("name"), r));
                int served = 0;
                for (String column : columnsOf(xx))
                {
                    Map<String, String> row = rows.get(column);
                    Map<String, String> expected = new LinkedHashMap<>();
                    if (row != null)
                    {
                        for (String key : SERVED)
                        {
                            if (row.get(key) != null && !row.get(key).isEmpty())
                            {
                                expected.put(key, row.get(key));
                            }
                        }
                    }
                    Map<String, String> actual = read(provider, xx, column);
                    assertEquals(expected, actual, pkg + " " + shape.getKey() + " " + column);
                    if (!actual.isEmpty())
                    {
                        assertEquals(SERVED, actual.keySet(), pkg + " " + column);
                        served++;
                    }
                }
                // Every column but the sponsor's own XXFOO is a model variable of the class.
                assertEquals(shape.getValue().size(), served, pkg + " " + shape.getKey());
                checked++;
            }
        }
        // 4 shapes × 12 packages; 44 -> 48 on 2026-10-10 with sendig dart-1-2 (the E14 seed
        // supplement, PLAN-seed-sendig-dart-1-2 P3).
        assertEquals(48, checked);
    }


    @Test
    void theSdtmigMdDeviceDomainsWhoseClassResolvesAreServed()
    {
        // DU / DX are IG-absent in an SDTMIG 3.4 run (SDTMIG-MD defines them) and their class
        // resolves from their own topic columns.
        MetadataProvider sdtmig34 = provider("sdtmig 3-4");
        IDataTable du = dataset("DU",
                List.of("STUDYID", "USUBJID", "SPDEVID", "DUSEQ", "DUTESTCD", "DUTEST", "DUORRES"));
        IDataTable dx = dataset("DX", List.of("STUDYID", "USUBJID", "SPDEVID", "DXSEQ", "DXTRT"));
        assertEquals("FINDINGS", ScopeClassLadder.classOf(sdtmig34, "DU", "DU", du, NO_RESOLVER));
        assertEquals("INTERVENTIONS",
                ScopeClassLadder.classOf(sdtmig34, "DX", "DX", dx, NO_RESOLVER));
        assertEquals(Map.of("name", "DUSEQ", "simpleDatatype", "Num", "role", "Identifier"),
                read(sdtmig34, du, "DUSEQ"));
        assertEquals(Map.of("name", "DXTRT", "simpleDatatype", "Char", "role", "Topic"),
                read(sdtmig34, dx, "DXTRT"));
    }

    // ------------------------------------------------------------------
    // 2. The rules on a sponsor Findings domain (plan §2.2)
    // ------------------------------------------------------------------


    @Test
    void theTypeRulesJudgeASponsorDomainByItsModelType() throws IOException
    {
        int runs = 0;
        for (String ruleId : TYPE_RULES)
        {
            for (Map.Entry<String, String> pkg : servedPackages(ruleId).entrySet())
            {
                MetadataProvider library = provider(pkg.getValue());
                String what = ruleId + " @ " + pkg.getValue();
                assertEquals(Set.of(),
                        reported(run(pkg.getKey(), library, ruleId, sponsorFindings(false)), what),
                        what + ": every type is the model's");
                IDataTable seqChar = sponsorFindings(true);
                RuleExecutionResult fired = run(pkg.getKey(), library, ruleId, seqChar);
                assertEquals(Set.of("XXSEQ"), reported(fired, what), what + ": --SEQ is Char");
                // EC-37: the reported cell is the value the Check compared against — the model's.
                assertEquals("Num", read(library, seqChar, "XXSEQ").get("simpleDatatype"));
                assertEquals("Num",
                        findingOn(fired, "XXSEQ").getValues().get("library_variable_data_type"),
                        what);
                runs++;
            }
        }
        // CG0012 3, FDA-SD0055 6, PMDA-SD0055 5, FDA-SE0055 5, SEND-0271 6. 24 -> 25 on 2026-10-10
        // (PLAN-seed-sendig-dart-1-2 P3): of these five only SEND-0271 ships in
        // rules-cdisc-sendig-dart-1-2.json, which runs now that the store holds that product.
        assertEquals(25, runs);
    }


    @Test
    void theRoleRulesJudgeASponsorDomainByItsModelRole() throws IOException
    {
        int runs = 0;
        for (String ruleId : ROLE_RULES)
        {
            for (Map.Entry<String, String> pkg : servedPackages(ruleId).entrySet())
            {
                MetadataProvider library = provider(pkg.getValue());
                String what = ruleId + " @ " + pkg.getValue();
                IDataTable xx = sponsorFindings(false);
                RuleExecutionResult result = run(pkg.getKey(), library, ruleId, xx);
                assertEquals(Set.of("XXTEST"), reported(result, what),
                        what + ": only the Define role that differs from the model's");
                Violation finding = findingOn(result, "XXTEST");
                assertEquals("Topic", finding.getValues().get("define_variable_role"), what);
                assertEquals(read(library, xx, "XXTEST").get("role"),
                        finding.getValues().get("library_variable_role"), what);
                assertEquals("Synonym Qualifier", finding.getValues().get("library_variable_role"),
                        what);
                runs++;
            }
        }
        // CG0010 at SDTMIG 3.2/3.3/3.4; SEND-0009.1 at SENDIG 3.0/3.1/3.1.1/DART 1.1/DART 1.2/
        // GENETOX 1.0 (DART 1.2 since 2026-10-10, PLAN-seed-sendig-dart-1-2: 8 -> 9).
        assertEquals(9, runs);
    }


    @Test
    void send0005ReportsOnlyTheVariableTheModelDoesNotDefine() throws IOException
    {
        // Before: every one of the 11 Define-declared variables, identifiers included.
        int runs = 0;
        for (Map.Entry<String, String> pkg : servedPackages(SEND_0005).entrySet())
        {
            String what = SEND_0005 + " @ " + pkg.getValue();
            RuleExecutionResult result = run(pkg.getKey(), provider(pkg.getValue()), SEND_0005,
                    sponsorFindings(false));
            assertEquals(Set.of("XXFOO"), reported(result, what), what);
            assertEquals("XXFOO",
                    result.getViolations().get(0).getValues().get("define_variable_name"), what);
            runs++;
        }
        // 5 -> 6 on 2026-10-10 (PLAN-seed-sendig-dart-1-2 P3): SEND-0005 ships in
        // rules-cdisc-sendig-dart-1-2.json, whose product the store now holds.
        assertEquals(6, runs);
    }


    @Test
    void theLabelRulesStaySilentOnASponsorDomainsAdjustedLabels() throws IOException
    {
        // LVC-D1 (a): the label is never served, so every label rule's own guard
        // (not empty(var_label("LIBRARY"))) keeps it silent — the sponsor may adjust labels.
        int runs = 0;
        for (String ruleId : LABEL_RULES)
        {
            for (Map.Entry<String, String> pkg : servedPackages(ruleId).entrySet())
            {
                String what = ruleId + " @ " + pkg.getValue();
                assertEquals(Set.of(), reported(
                        run(pkg.getKey(), provider(pkg.getValue()), ruleId, sponsorFindings(false)),
                        what), what);
                runs++;
            }
        }
        assertTrue(runs >= 13, "label-rule runs: " + runs);
    }


    @Test
    void theCodelistAndCoreRulesStayInertOnASponsorDomain() throws IOException
    {
        // §3d: a codelist or core designation is an IG attribute; the model row has none.
        int runs = 0;
        for (String ruleId : INERT_RULES)
        {
            for (Map.Entry<String, String> pkg : servedPackages(ruleId).entrySet())
            {
                String what = ruleId + " @ " + pkg.getValue();
                assertEquals(Set.of(), reported(
                        run(pkg.getKey(), provider(pkg.getValue()), ruleId, sponsorFindings(true)),
                        what), what);
                runs++;
            }
        }
        assertTrue(runs >= 60, "inert-rule runs: " + runs);
    }

    // ------------------------------------------------------------------
    // 3. The standard SEND domains SENDIG-AR 1.0 and SENDIG-DART 1.1 leave out (plan §2.4)
    // ------------------------------------------------------------------


    @Test
    void theIgAbsentStandardSendPopulationsHoldEveryMeasuredDomain()
    {
        // Derived from the STORED products (the population the base-IG view fills); a store
        // change that shrinks either set reds here.
        Set<String> ar = StoredSendRosters.storedAbsentRelativeTo311("sendig ar-1-0");
        assertTrue(ar.containsAll(AR_GO_ABSENT), "AR 1.0: " + ar);
        assertTrue(ar.containsAll(AR_NON_GO_ABSENT), "AR 1.0: " + ar);
        assertTrue(ar.contains("SUPPQUAL"), "AR 1.0 prints no SUPPQUAL: " + ar);
        Set<String> dart = StoredSendRosters.storedAbsentRelativeTo311("sendig dart-1-1");
        assertTrue(dart.containsAll(DART_ABSENT), "DART 1.1: " + dart);
        // PLAN-seed-sendig-dart-1-2 D4: the E14 supplement follows the Library's DART 1.1
        // inclusion rule, so DART 1.2 leaves out exactly what DART 1.1 does (VS, CV, RE and EG
        // stay IG-absent although §2.2.2 lets a juvenile study submit them).
        Set<String> dart12 = StoredSendRosters.storedAbsentRelativeTo311("sendig dart-1-2");
        assertTrue(dart12.containsAll(DART_ABSENT), "DART 1.2: " + dart12);
        assertEquals(dart, dart12, "DART 1.2 leaves out what DART 1.1 does");
        // PLAN-send-base-ig-view: the provider answers the composed product, which leaves none of
        // them out.
        for (String pkg : List.of("sendig ar-1-0", "sendig dart-1-1", "sendig dart-1-2"))
        {
            assertEquals(Set.of(), igAbsentRelativeTo311(pkg), pkg + " through the base-IG view");
        }
    }


    /** Runs {@code aRuleId} on {@code aDomain} at {@code aStorePackage}, correct and --SEQ-Char. */
    private static void assertOnlyTheTypeErrorFires(String aPackageFile, String aStorePackage,
            String aRuleId, String aDomain, List<String> aColumns)
        throws IOException
    {
        MetadataProvider library = provider(aStorePackage);
        String what = aRuleId + " @ " + aStorePackage + " " + aDomain;
        assertEquals(Set.of(), reported(
                run(aPackageFile, library, aRuleId, conformantSend(aDomain, aColumns, false)),
                what), what);
        assertEquals(Set.of(aDomain + "SEQ"), reported(
                run(aPackageFile, library, aRuleId, conformantSend(aDomain, aColumns, true)), what),
                what);
    }


    private static Set<String> reportedOn(String aPackageFile, String aStorePackage, String aRuleId,
            IDataTable aTable)
        throws IOException
    {
        return reported(run(aPackageFile, provider(aStorePackage), aRuleId, aTable),
                aRuleId + " @ " + aStorePackage + " " + aTable.getMetaData().getName());
    }


    @Test
    void arsLeftOutStandardDomainsAreJudgedForTypeAndLabelByTheBaseSendig() throws IOException
    {
        String ar = "sendig ar-1-0";
        String fda = "rules-fda-sendig-ar-1-0.json";
        Map<String, List<String>> domains = new LinkedHashMap<>();
        domains.put("LB", findingsColumns("LB"));
        domains.put("CL", findingsColumns("CL"));
        domains.put("EX", List.of("STUDYID", "DOMAIN", "USUBJID", "EXSEQ", "EXTRT", "EXDOSE",
                "EXDOSU", "EXSTDTC", "EXFOO"));
        domains.put("DS", List.of("STUDYID", "DOMAIN", "USUBJID", "DSSEQ", "DSTERM", "DSDECOD",
                "DSSTDTC", "DSFOO"));
        for (Map.Entry<String, List<String>> d : domains.entrySet())
        {
            assertOnlyTheTypeErrorFires(fda, ar, "FDA-SE0055", d.getKey(), d.getValue());
            assertOnlyTheTypeErrorFires(fda, ar, "FDA-SD0055", d.getKey(), d.getValue());
            // LVC-F1 (b), PLAN-send-base-ig-view: the base SENDIG's labels are served, so the
            // label rule is silent on a conformant dataset and reports every standard variable of
            // a wrongly labelled one (the sponsor --FOO stays unserved).
            assertEquals(Set.of(), reportedOn(fda, ar, "FDA-SE0063",
                    conformantSend(d.getKey(), d.getValue(), false)), d.getKey());
            assertEquals(standardColumns(d.getValue()), reportedOn(fda, ar, "FDA-SE0063",
                    wronglyLabelledSend(d.getKey(), d.getValue())), d.getKey());
        }
    }


    @Test
    void dartsLeftOutStandardDomainsAreJudgedByTheBaseSendig() throws IOException
    {
        String dart = "sendig dart-1-1";
        String cdisc = "rules-cdisc-sendig-dart-1-1.json";
        String fda = "rules-fda-sendig-dart-1-1.json";
        for (String domain : List.of("VS", "EG"))
        {
            List<String> columns = findingsColumns(domain);
            assertOnlyTheTypeErrorFires(fda, dart, "FDA-SE0055", domain, columns);
            assertOnlyTheTypeErrorFires(cdisc, dart, "CDISC-SEND-0271", domain, columns);
            IDataTable conformant = conformantSend(domain, columns, false);
            // The Define is SENDIG 3.1.1-conformant: only the sponsor variable is not a model one,
            // and every declared role equals the model's.
            assertEquals(Set.of(domain + "FOO"), reportedOn(cdisc, dart, SEND_0005, conformant),
                    domain);
            assertEquals(Set.of(), reportedOn(cdisc, dart, "CDISC-SEND-0009.1", conformant),
                    domain);
            // LVC-F1 (b), PLAN-send-base-ig-view: the base SENDIG's labels are served — silent on
            // a conformant dataset, every standard variable of a wrongly labelled one reported.
            IDataTable wrong = wronglyLabelledSend(domain, columns);
            assertEquals(Set.of(), reportedOn(fda, dart, "FDA-SE0063", conformant), domain);
            assertEquals(Set.of(), reportedOn(cdisc, dart, "CDISC-SEND-0276", conformant), domain);
            assertEquals(standardColumns(columns), reportedOn(fda, dart, "FDA-SE0063", wrong),
                    domain);
            assertEquals(standardColumns(columns),
                    reportedOn(cdisc, dart, "CDISC-SEND-0276", wrong), domain);
        }
    }


    @Test
    void dartsPmorresNoLongerGetsASend00091FindingOnAConformantDefine() throws IOException
    {
        // The named consequence of LVC-D2 (a) — SENDIG gives PMORRES the role "Variable
        // Qualifier", the SDTM model's Findings class gives --ORRES "Result Qualifier", so
        // SEND-0009.1 reported PMORRES at DART 1.1 on a conformant Define — is removed by the
        // base-IG view (PLAN-send-base-ig-view): DART 1.1 now reads PM from SENDIG 3.1.
        MetadataProvider dart = provider("sendig dart-1-1");
        IDataTable pm = conformantSend("PM", findingsColumns("PM"), false);
        assertEquals("Variable Qualifier",
                provider("sendig 3-1").getVariableMetadata("PM", "PMORRES").get("role"));
        assertEquals("Variable Qualifier", dart.getVariableMetadata("PM", "PMORRES").get("role"));
        RuleExecutionResult result = run("rules-cdisc-sendig-dart-1-1.json", dart,
                "CDISC-SEND-0009.1", pm);
        assertEquals(Set.of(), reported(result, "SEND-0009.1 @ DART 1.1 PM"));
    }


    @Test
    void arsSpecialPurposeTrialDesignAndRelationshipDomainsReadTheBaseSendig()
    {
        // Before PLAN-send-base-ig-view these eight (and SUPPQUAL) read nothing: algorithm B has
        // no domain-keyed model tier for a non-general-observation class. The base-IG view gives
        // the AR run SENDIG 3.1's datasets, so every column reads exactly what a SENDIG 3.1 run
        // reads (B-BASE: AR names SENDIG v3.1).
        MetadataProvider base = provider("sendig 3-1");
        MetadataProvider ar = provider("sendig ar-1-0");
        int served = 0;
        for (String domain : new TreeSet<>(AR_NON_GO_ABSENT))
        {
            IDataTable t = dataset(domain, base.getColumnOrder(domain));
            for (String column : columnsOf(t))
            {
                // (The helper adds a DOMAIN column POOLDEF and RELREC do not define.)
                Map<String, String> expected = read(base, t, column);
                assertEquals(expected, read(ar, t, column), domain + "." + column);
                served += expected.isEmpty() ? 0 : 1;
            }
        }
        assertTrue(served >= 60, "reads " + served);
    }

    // ------------------------------------------------------------------
    // 4. Non-regression: every shape tiers 1-3 answer reads exactly as without the tier
    // ------------------------------------------------------------------


    @Test
    void anApMemberWhoseDomainCellNamesTheParentIsReadAsScopeClassifiesIt() throws IOException
    {
        // Review round 1, P1: member APXX, DOMAIN cell XX, no XX dataset. Scope classifies it by
        // its own code XX and columns (XXTESTCD: FINDINGS); the read's domain tier re-adds the AP
        // prefix (APXX) for the associated-persons shim, and the class used to be keyed by that
        // key too — the ladder then sniffed APXXTESTCD, missed, fell through to the absent parent
        // and served nothing, so SEND-0005 kept reporting every Define-declared variable.
        IDataTable apxx = MockTable.of().name("APXX").col("DOMAIN", "XX").col("STUDYID", "S")
                .col("APID", "A").col("XXSEQ", "1").col("XXTESTCD", "T").col("XXORRES", "r")
                .col("XXFOO", "x").build();
        for (String pkg : List.of("sdtmig 3-4", "sendig 3-1-1", "sendig ar-1-0"))
        {
            MetadataProvider provider = provider(pkg);
            assertEquals("FINDINGS",
                    ScopeClassLadder.classOf(provider, "APXX", "XX", apxx, NO_RESOLVER), pkg);
            assertEquals("Topic", read(provider, apxx, "XXTESTCD").get("role"), pkg);
            assertEquals("Identifier", read(provider, apxx, "APID").get("role"),
                    pkg + ": the AP shim rides the domain tier's AP-prefixed key");
            assertEquals(Map.of(), read(provider, apxx, "XXFOO"), pkg);
        }
    }


    @Test
    void theTierItselfAnswersNothingForADomainTheIgDefines()
    {
        // S3.3's early return, pinned where it is observable. Through the READ it changes no
        // answer: the read's domain tier has already walked the same merged IG + model list for
        // an IG-defined domain and missed, so a second walk misses too — the return only saves
        // that walk (S5). The provider's own contract ("a domain the IG does not define") is what
        // a direct call sees: an IG variable, a variable the MODEL defines and the IG does not
        // (--BODSYS is a Findings-class model variable none of these IGs lists for LB — measured
        // 2026-09-27), and a sponsor variable all answer {}. Without the early return the walk
        // would serve the first two.
        int asked = 0;
        for (String pkg : List.of("sdtmig 3-2", "sdtmig 3-4", "sendig 3-1-1", "sendig dart-1-1"))
        {
            MetadataProvider provider = provider(pkg);
            IDataTable lb = dataset("LB", List.of("STUDYID", "USUBJID", "LBSEQ", "LBTESTCD",
                    "LBORRES", "LBBODSYS", "LBFOO"));
            for (String column : columnsOf(lb))
            {
                assertEquals(Map.of(),
                        provider.getIgAbsentVariableMetadata(lb, NO_RESOLVER, "LB", column),
                        pkg + " LB." + column);
                asked++;
            }
            assertFalse(read(provider, lb, "LBTESTCD").isEmpty(),
                    pkg + ": the read answers LBTESTCD from the domain tier");
            assertTrue(read(provider, lb, "LBBODSYS").containsKey("label"),
                    pkg + ": the read answers the model-only LBBODSYS from the domain tier's merged"
                            + " walk, label included — not from the IG-absent tier");
        }
        assertEquals(32, asked);
    }


    @Test
    void theShapesTiersOneToThreeAnswerReadExactlyAsWithoutTheTier()
    {
        // P6: the tier is last, and it answers nothing for a domain the run's IG defines — not
        // even for a sponsor variable of it. Compared against the same provider with the tier
        // switched off, for every column, at the packages where the IG defines these domains
        // (LB is IG-absent at SENDIG-AR 1.0, MH in every SENDIG: those are movers, not controls).
        IDataTable lb = dataset("LB",
                List.of("STUDYID", "USUBJID", "LBSEQ", "LBTESTCD", "LBORRES", "LBFOO"));
        IDataTable lb1 = MockTable.of().name("LB1").col("DOMAIN", "LB").col("LBSEQ", "1")
                .col("LBTESTCD", "T").col("LBFOO", "x").build();
        IDataTable suppae = MockTable.of().name("SUPPAE").col("STUDYID", "S").col("RDOMAIN", "AE")
                .col("USUBJID", "U").col("QNAM", "Q").col("QVAL", "V").col("QFOO", "x").build();
        IDataTable apmh = MockTable.of().name("APMH").col("STUDYID", "S").col("DOMAIN", "APMH")
                .col("APID", "A").col("MHTERM", "t").col("MHFOO", "x").build();
        IDataTable apmh1 = MockTable.of().name("APMH1").col("STUDYID", "S").col("DOMAIN", "MH")
                .col("APID", "A").col("MHTERM", "t").build();
        Map<String, List<IDataTable>> controls = new LinkedHashMap<>();
        controls.put("sdtmig 3-2", List.of(lb, lb1, suppae, apmh, apmh1));
        controls.put("sdtmig 3-4", List.of(lb, lb1, suppae, apmh, apmh1));
        controls.put("sendig 3-1-1", List.of(lb, lb1, suppae));
        controls.put("sendig dart-1-1", List.of(lb, lb1, suppae));
        controls.put("sendig ar-1-0", List.of(suppae));
        int compared = 0;
        int answered = 0;
        for (Map.Entry<String, List<IDataTable>> e : controls.entrySet())
        {
            MetadataProvider real = provider(e.getKey());
            MetadataProvider today = withoutTheTier(real);
            for (IDataTable t : e.getValue())
            {
                for (String column : columnsOf(t))
                {
                    Map<String, String> expected = read(today, t, column);
                    assertEquals(expected, read(real, t, column),
                            e.getKey() + " " + t.getMetaData().getName() + "." + column);
                    compared++;
                    if (!expected.isEmpty())
                    {
                        answered++;
                    }
                }
            }
        }
        assertEquals(92, compared);
        // Non-vacuity: tiers 1-3 answer most of these reads, so the comparison is not {} == {}.
        assertTrue(answered >= 60, "reads tiers 1-3 answered: " + answered);
    }


    @Test
    void anIgDefinedLbKeepsItsFindings() throws IOException
    {
        // The §2.2 control on an IG-defined domain, through the rules: SEND-0005 reports only the
        // sponsor variable, the type rule only the type error, and a conformant Define's roles
        // raise nothing.
        String cdisc = "rules-cdisc-sendig-3-1-1.json";
        String pkg = "sendig 3-1-1";
        List<String> columns = findingsColumns("LB");
        assertEquals(Set.of("LBFOO"),
                reportedOn(cdisc, pkg, SEND_0005, conformantSend("LB", columns, false)));
        assertEquals(Set.of(),
                reportedOn(cdisc, pkg, "CDISC-SEND-0009.1", conformantSend("LB", columns, false)));
        assertEquals(Set.of("LBSEQ"),
                reportedOn(cdisc, pkg, "CDISC-SEND-0271", conformantSend("LB", columns, true)));
    }

    // ------------------------------------------------------------------
    // 5. No class, no answer — today's behaviour (LVC-D3 (a))
    // ------------------------------------------------------------------


    @Test
    void aSnifferMissReadsNothingAndSkipsNothing() throws IOException
    {
        Map<String, List<IDataTable>> misses = new LinkedHashMap<>();
        IDataTable noTopic = dataset("XX", List.of("STUDYID", "USUBJID", "XXSEQ", "XXFOO"));
        IDataTable noDomain = MockTable.of().name("XXNODOM").col("STUDYID", "S").col("USUBJID", "U")
                .col("XXSEQ", "1").col("XXTESTCD", "T").col("XXTEST", "t").col("XXORRES", "r")
                .build();
        IDataTable di = dataset("DI",
                List.of("STUDYID", "SPDEVID", "DISEQ", "DIPARMCD", "DIPARM", "DIVAL"));
        IDataTable dr = dataset("DR", List.of("STUDYID", "USUBJID", "SPDEVID", "DRSEQ"));
        IDataTable oi = dataset("OI",
                List.of("STUDYID", "NHOID", "OISEQ", "OIPARMCD", "OIPARM", "OIVAL"));
        IDataTable relspec = MockTable.of().name("RELSPEC").col("STUDYID", "S").col("USUBJID", "U")
                .col("REFID", "R").col("SPEC", "s").col("PARENT", "p").col("LEVEL", "1").build();
        misses.put("sdtmig 3-4", List.of(noTopic, noDomain));
        misses.put("sdtmig 3-2", List.of(noTopic, noDomain, di, dr, oi, relspec));
        misses.put("sendig 3-1-1", List.of(noTopic, noDomain));
        int reads = 0;
        for (Map.Entry<String, List<IDataTable>> e : misses.entrySet())
        {
            MetadataProvider provider = provider(e.getKey());
            for (IDataTable t : e.getValue())
            {
                String member = t.getMetaData().getName();
                assertNull(
                        ScopeClassLadder.classOf(provider, member,
                                CdiscDomainResolver.cdiscDomainOf(t), t, NO_RESOLVER),
                        e.getKey() + " " + member + " is a sniffer miss");
                for (String column : columnsOf(t))
                {
                    assertEquals(Map.of(), read(provider, t, column),
                            e.getKey() + " " + member + "." + column);
                    reads++;
                }
            }
        }
        assertTrue(reads >= 40, "reads " + reads);
        // PLAN-send-base-ig-view B-MD: at SDTMIG 3.4 the SDTM 2.0 model defines DI and DR, so a
        // column the model dataset defines reads its row (name, type, role — LVC-D1 (a)), and
        // one it does not define reads nothing. At SDTMIG 3.2 (SDTM 1.4: no DI / DR / OI /
        // RELSPEC) they stay sniffer misses that read nothing (above).
        MetadataProvider sdtmig34 = provider("sdtmig 3-4");
        int modelReads = 0;
        for (IDataTable t : List.of(di, dr))
        {
            String member = t.getMetaData().getName();
            Map<String, StoredVariable> model = modelDatasetRows("models/sdtm/2-0", member);
            for (String column : columnsOf(t))
            {
                StoredVariable row = model.get(column);
                Map<String, String> expected = row == null ? Map.of()
                        : Map.of("name", row.name(), "simpleDatatype", row.simpleDatatype(), "role",
                                row.role());
                assertEquals(expected, read(sdtmig34, t, column),
                        "sdtmig 3-4 " + member + "." + column);
                modelReads += row == null ? 0 : 1;
            }
        }
        assertEquals(11, modelReads, "DI: 7 model columns incl. DOMAIN; DR: STUDYID USUBJID "
                + "SPDEVID DOMAIN — DRSEQ is not a model variable");
        // No SKIP: the type and role rules execute on DI. Since B-MD the type rules judge it by
        // the SDTM 2.0 model's DI: DISEQ is Num there and character here (the helper's cells),
        // so both type rules report it; the role rule needs a Define and stays silent.
        IDataTable diRun = dataset("DI", List.of("STUDYID", "SPDEVID", "DISEQ", "DIPARMCD"));
        Map<String, Set<String>> expected = Map.of("CDISC-CG0012", Set.of("DISEQ"), "CDISC-CG0010",
                Set.of(), "FDA-SD0055", Set.of("DISEQ"));
        for (Map.Entry<String, Set<String>> e : expected.entrySet())
        {
            String ruleId = e.getKey();
            String file = "rules-" + (ruleId.startsWith("FDA") ? "fda" : "cdisc")
                    + "-sdtmig-3-4.json";
            assertEquals(e.getValue(), reportedOn(file, "sdtmig 3-4", ruleId, diRun), ruleId);
        }
    }
}
