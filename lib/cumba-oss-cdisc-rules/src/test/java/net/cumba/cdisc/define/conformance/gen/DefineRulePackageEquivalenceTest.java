package net.cumba.cdisc.define.conformance.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.cdisc.define.conformance.eval.CustomCheck;
import net.cumba.cdisc.define.conformance.rule.CheckDefinition;
import net.cumba.cdisc.define.conformance.rule.ConformanceRule;
import net.cumba.cdisc.define.conformance.rule.DefineRulePackage;
import net.cumba.cdisc.define.conformance.rule.DefineRulePackageManifest;
import net.cumba.cdisc.define.conformance.rule.RuleRepository;
import org.junit.jupiter.api.Test;

/**
 * The packaged corpus must carry the <b>same rules</b> as the authored YAML, compared as objects.
 *
 * <p>
 * This is the half of the acceptance that a byte round-trip cannot give.
 * {@link DefineRuleCorpusRoundTripTest} proves the committed bytes are what the generator emits —
 * but both sides of that comparison come from the same generator, so a serialization fault
 * reproduces identically and passes. Here the two sides have <em>different provenance</em>: YAML
 * parsed by the authoring path, versus JSON parsed by the runtime path. Only that catches a name,
 * type or enum mapping that silently changed meaning.
 * </p>
 *
 * <p>
 * The concrete hazard this exists for: {@link ConformanceRule}'s fields carry explicit
 * {@code @JsonProperty} names ({@code Rule_Id}, {@code Applicable_Versions}, …) while everything
 * inside {@link CheckDefinition} uses raw record-component names — most sharply
 * {@code CheckDefinition.Custom.className}, which names a class resolved by reflection at
 * evaluation time. Serialise that under the wrong key and nothing fails at load: the rule simply
 * stops firing, in production, silently. Record equality is structural, so a mis-mapped field
 * surfaces here as an inequality.
 * </p>
 */
class DefineRulePackageEquivalenceTest
{

    @Test
    void everyPackagedRuleIsIdenticalToItsAuthoredSource() throws IOException
    {
        Map<String, ConformanceRule> authored = authoredById();
        DefineRulePackageManifest manifest = DefineRulePackageManifest
                .load(DefineRuleCorpusGenerator.target());

        int compared = 0;
        for (DefineRulePackageManifest.Entry entry : manifest.packages())
        {
            DefineRulePackage pkg = RuleRepository
                    .loadPackage(DefineRuleCorpusGenerator.target().resolve(entry.file()));
            for (ConformanceRule packaged : pkg.rules().values())
            {
                ConformanceRule source = authored.get(packaged.ruleId());
                assertNotNull(source, packaged.ruleId() + " is in " + entry.file()
                        + " but has no authored source under rules-define-src/");
                assertEquals(source, packaged,
                        packaged.ruleId() + " differs between its authored YAML and " + entry.file()
                                + " — the packaging is lossy or renames a field");
                compared++;
            }
        }
        assertTrue(compared >= 700, "only " + compared + " rules compared; the corpus publishes a"
                + " rule once per version it declares, so this must cover every placement");
    }


    /** Nothing may be dropped: every authored rule reaches every package its versions imply. */
    @Test
    void everyAuthoredRuleReachesEveryPackageItDeclares() throws IOException
    {
        DefineRulePackageManifest manifest = DefineRulePackageManifest
                .load(DefineRuleCorpusGenerator.target());

        Set<String> expected = new TreeSet<>();
        for (ConformanceRule rule : authoredById().values())
        {
            for (String version : rule.applicableVersions())
            {
                expected.add(rule.ruleSet() + "/" + version + "/" + rule.ruleId());
            }
        }

        Set<String> published = new TreeSet<>();
        for (DefineRulePackageManifest.Entry entry : manifest.packages())
        {
            DefineRulePackage pkg = RuleRepository
                    .loadPackage(DefineRuleCorpusGenerator.target().resolve(entry.file()));
            for (ConformanceRule rule : pkg.rules().values())
            {
                published.add(entry.family() + "/" + entry.version() + "/" + rule.ruleId());
            }
        }

        assertEquals(expected, published,
                "the set of (family, version, rule) placements differs between rules-define-src/"
                        + " and rules-define/");
    }


    /**
     * The custom-check ratchet, re-asserted against the <b>packaged</b> corpus. Its sibling in
     * {@code corej-define-conformance} asserts the same over the YAML; this one proves the class
     * names survived packaging and still resolve, which is exactly the failure the equality test
     * above is designed around.
     */
    @Test
    void everyCustomCheckClassInThePackagedCorpusResolves() throws IOException
    {
        DefineRulePackageManifest manifest = DefineRulePackageManifest
                .load(DefineRuleCorpusGenerator.target());

        Set<String> classNames = new TreeSet<>();
        for (DefineRulePackageManifest.Entry entry : manifest.packages())
        {
            DefineRulePackage pkg = RuleRepository
                    .loadPackage(DefineRuleCorpusGenerator.target().resolve(entry.file()));
            for (ConformanceRule rule : pkg.rules().values())
            {
                if (rule.check() instanceof CheckDefinition.Custom custom)
                {
                    classNames.add(custom.className());
                }
            }
        }

        assertEquals(11, classNames.size(),
                "expected exactly 11 distinct CustomCheck classes in the packaged corpus, found "
                        + classNames.size() + ": " + classNames);

        List<String> unresolvable = new ArrayList<>();
        for (String name : classNames)
        {
            try
            {
                // Instantiate exactly as RuleEvaluator.instantiate does, not merely resolve: the
                // SPI requires a public no-arg constructor, and a class that resolves but cannot
                // be constructed fails at evaluation time, which is the failure this guards.
                CustomCheck impl = Class.forName(name).asSubclass(CustomCheck.class)
                        .getDeclaredConstructor().newInstance();
                assertNotNull(impl, name + " instantiated to null");
            }
            catch (ReflectiveOperationException | ClassCastException e)
            {
                unresolvable.add(name + " (" + e.getClass().getSimpleName() + ")");
            }
        }
        assertTrue(unresolvable.isEmpty(), "custom-check classes named by the packaged corpus do"
                + " not resolve — the rules would throw at evaluation time: " + unresolvable);
    }


    private static Map<String, ConformanceRule> authoredById()
    {
        Map<String, ConformanceRule> byId = new HashMap<>();
        List<ConformanceRule> authored = RuleRepository
                .loadDirectory(DefineRuleCorpusGenerator.SOURCE);
        assertFalse(authored.isEmpty(), "no authored rules found under "
                + DefineRuleCorpusGenerator.SOURCE.toAbsolutePath());
        for (ConformanceRule rule : authored)
        {
            byId.put(rule.ruleId(), rule);
        }
        return byId;
    }

}
