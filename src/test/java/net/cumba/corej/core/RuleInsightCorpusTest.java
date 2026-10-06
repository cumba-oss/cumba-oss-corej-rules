package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.cumba.corej.authoring.AuthoredRuleAssembler;
import net.cumba.corej.authoring.CorpusIndex;
import net.cumba.corej.authoring.RuleInsight;
import net.cumba.corej.authoring.RuleInsight.RelatedRule;
import net.cumba.corej.authoring.RuleInsight.ScopeEntry;
import net.cumba.corej.authoring.RuleInsight.ScopeUniverse;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import org.junit.jupiter.api.Test;

/**
 * {@code RuleInsight} over the real corpus ({@code PLAN-rule-authoring-api} phase 2: the engine
 * repository cannot reach the corpus, so the real-data proof lives here).
 *
 * <ul>
 * <li>every rule's derived Output_Variables, read through {@code RuleInsight.derived}, equal the
 * committed golden {@code ovsweep/effective-output-variables.tsv} — the same list
 * {@link EffectiveOutputVariablesDriftTest} pins, so the insight reads what the engine derives and
 * nothing adjacent;</li>
 * <li>the scope universe over our metadata store admits what the production predicates admit: a
 * {@code Domains: ALL} rule admits every SDTMIG dataset, a {@code Classes: FINDINGS} rule admits LB
 * and not DM;</li>
 * <li>related rules come from the corpus's own references and, where the repository carries it, the
 * equivalence register.</li>
 * </ul>
 */
class RuleInsightCorpusTest
{

    /** A floor so a walk that matches nothing cannot pass vacuously. */
    private static final int MIN_RULES = 3_000;

    @Test
    void everyRulesDerivedOutputVariablesEqualTheGolden() throws IOException
    {
        Path golden = CorpusPaths.moduleDir().resolve("src").resolve("test").resolve("resources")
                .resolve("ovsweep").resolve(EffectiveOutputVariablesDriftTest.GOLDEN_FILE);
        Map<String, String> committed = EffectiveOutputVariablesDriftTest
                .parse(Files.readAllLines(golden, StandardCharsets.UTF_8));
        RulePackageManifest manifest = RulePackageManifest.load(CorpusPaths.rules());
        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        for (RulePackageManifest.Entry e : manifest.packages())
        {
            Map<String, Rule> rules = RulePackageLoader.load(CorpusPaths.rules(e.file()))
                    .getRules();
            assertNotNull(rules);
            for (Map.Entry<String, Rule> r : rules.entrySet())
            {
                List<String> effective = RuleInsight.derived(r.getValue()).outputVariables()
                        .effective();
                String rendered = effective.isEmpty() ? EffectiveOutputVariablesDriftTest.EMPTY
                        : String.join("|", effective);
                String expected = committed.containsKey(r.getKey() + "@" + e.file())
                        ? committed.get(r.getKey() + "@" + e.file())
                        : committed.get(r.getKey());
                if (expected == null)
                {
                    mismatches.add(e.file() + " " + r.getKey() + ": not in the golden");
                }
                else if (!expected.equals(rendered))
                {
                    mismatches.add(e.file() + " " + r.getKey() + ": golden " + expected
                            + " insight " + rendered);
                }
                compared++;
            }
        }
        assertTrue(compared >= MIN_RULES, "compared " + compared + ", floor " + MIN_RULES);
        assertEquals(List.of(), mismatches, mismatches.size() + " mismatch(es) of " + compared);
    }


    @Test
    void theScopeUniverseOverTheStoreAdmitsWhatProductionAdmits() throws IOException
    {
        // the corpus gate requires the store (CDISC_PICKLE_CACHE_DIR is exported before every run,
        // and the store-backed lint tests already fail without it): an absent store is a red run
        // here too, never a silent skip of the only check that the scope universe matches
        // production
        Path storeFile = MetadataStoreFixture.storeFile()
                .orElseThrow(() -> new IllegalStateException(
                        "No metadata store: the scope universe is read from the CDISC Library products."
                                + " Set CDISC_PICKLE_CACHE_DIR (or -Dcdisc.pickle.cache.dir) to a pickle"
                                + " cache directory - MetadataStoreFixture seeds the store from it."));
        try (MetadataStore store = MetadataStore.open(storeFile))
        {
            // Domains: ALL — every SDTMIG 3.4 dataset is in scope.
            Rule cg0006 = rule("rules-cdisc-sdtmig-3-4.json", "CDISC-CG0006");
            ScopeUniverse all = RuleInsight.scopeUniverse(cg0006, store,
                    List.of("standards/sdtmig/3-4"));
            assertEquals(List.of(), all.missingProducts());
            assertTrue(all.entries().size() > 40, "SDTMIG 3.4 datasets: " + all.entries().size());
            List<ScopeEntry> out = all.entries().stream().filter(x -> !x.inScope()).toList();
            assertEquals(List.of(), out, "Domains: ALL admits every dataset");
            assertTrue(all.variableNames().contains("USUBJID"));
            assertTrue(all.variableNames().stream().anyMatch(n -> n.startsWith("--")),
                    "the class model variables keep their -- form");

            // Classes: FINDINGS, FINDINGS ABOUT — LB in, DM out, exactly as the gate judges.
            Rule sd1212 = rule("rules-fda-sdtmig-3-4.json", "FDA-SD1212");
            ScopeUniverse findings = RuleInsight.scopeUniverse(sd1212, store,
                    List.of("standards/sdtmig/3-4"));
            Map<String, ScopeEntry> byName = new HashMap<>();
            findings.entries().forEach(x -> byName.put(x.dataset(), x));
            assertTrue(byName.get("LB").inScope(), String.valueOf(byName.get("LB")));
            assertFalse(byName.get("DM").inScope(), String.valueOf(byName.get("DM")));
            assertTrue(byName.get("DM").mismatch().contains("Scope.Classes"),
                    byName.get("DM").mismatch());
            // the store's own spelling of the class is kept; the matcher normalises
            assertTrue("FINDINGS".equalsIgnoreCase(byName.get("LB").datasetClass()),
                    String.valueOf(byName.get("LB").datasetClass()));
            assertFalse(findings.variableNames().contains("RFSTDTC"),
                    "an out-of-scope dataset's variables are not offered");

            // ADaM: Data_Structures: BASIC DATA STRUCTURE — the IG names structures, not
            // datasets; BDS is in, ADSL (SUBJECT LEVEL ANALYSIS DATASET) is out.
            Rule ad0033 = rule("rules-cdisc-adamig-1-1.json", "CDISC-AD0033");
            ScopeUniverse bds = RuleInsight.scopeUniverse(ad0033, store,
                    List.of("standards/adam/adamig-1-1"));
            assertEquals(List.of(), bds.missingProducts());
            Map<String, ScopeEntry> structures = new HashMap<>();
            bds.entries().forEach(x -> structures.put(x.dataset(), x));
            assertTrue(structures.get("BDS").inScope(), String.valueOf(structures.get("BDS")));
            assertFalse(structures.get("ADSL").inScope(), String.valueOf(structures.get("ADSL")));
            assertEquals("BASIC DATA STRUCTURE", structures.get("BDS").dataStructure());
            assertTrue(bds.variableNames().contains("PARAMCD"), bds.variableNames().toString());
        }
    }


    @Test
    void relatedRulesComeFromTheCorpusAndTheRegister() throws IOException
    {
        Map<String, JsonNode> checks = new HashMap<>();
        Path checksDir = CorpusPaths.moduleDir().resolve("rules-src").resolve("checks");
        try (Stream<Path> walk = Files.walk(checksDir))
        {
            for (Path p : walk.filter(p -> p.toString().endsWith(".yaml")).toList())
            {
                JsonNode check = AuthoredRuleAssembler
                        .readYamlTree(Files.readString(p, StandardCharsets.UTF_8));
                checks.put(check.path("Core").path("Id").asText(), check);
            }
        }
        assertTrue(checks.size() >= MIN_RULES, "checks: " + checks.size());
        CorpusIndex index = CorpusIndex.of(checks);
        // The FDA / PMDA twins share their published reference ids: a large population.
        int withTwin = 0;
        for (String id : checks.keySet())
        {
            if (RuleInsight.related(id, index.referencesOf(id), index).stream()
                    .anyMatch(r -> "shared-reference".equals(r.relation())))
            {
                withTwin++;
            }
        }
        assertTrue(withTwin >= 500, "rules with a shared-reference relation: " + withTwin);

        Path register = CorpusPaths.moduleDir().resolve("registers").resolve("rule-inventory")
                .resolve("rule-equivalence.yaml");
        Path parentage = CorpusPaths.moduleDir().resolve("registers").resolve("derivation")
                .resolve("minted-rule-parentage.tsv");
        if (Files.isRegularFile(register) && Files.isRegularFile(parentage))
        {
            CorpusIndex full = index
                    .withEquivalenceRegister(Files.readString(register, StandardCharsets.UTF_8))
                    .withParentage(Files.readString(parentage, StandardCharsets.UTF_8));
            List<RelatedRule> fda = RuleInsight.related("FDA-SD0047",
                    full.referencesOf("FDA-SD0047"), full);
            assertTrue(
                    fda.stream().anyMatch(r -> "PMDA-SD0047".equals(r.coreId())
                            && "equivalence".equals(r.relation()) && r.via().startsWith("EQ-")),
                    fda.toString());
            List<RelatedRule> minted = RuleInsight.related("CDISC-CG9672",
                    full.referencesOf("CDISC-CG9672"), full);
            assertTrue(minted.stream().anyMatch(
                    r -> "CDISC-CG0425".equals(r.coreId()) && "minted-from".equals(r.relation())),
                    minted.toString());
        }
    }


    private static Rule rule(String aPackage, String aCoreId) throws IOException
    {
        Rule rule = RulePackageLoader.load(CorpusPaths.rules(aPackage)).getRules().get(aCoreId);
        assertNotNull(rule, aCoreId + " in " + aPackage);
        return rule;
    }
}
