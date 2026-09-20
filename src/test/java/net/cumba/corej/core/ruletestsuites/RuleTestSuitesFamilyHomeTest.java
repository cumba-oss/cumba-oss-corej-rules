package net.cumba.corej.core.ruletestsuites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the <b>family homes</b> the {@code @TestFactory} classes depend on, independently of whether
 * any scenario exists yet.
 *
 * <p>
 * <b>Why this test exists.</b> {@link AbstractRuleTestSuitesFactory#scenarios()} returns an empty
 * stream when the family's resource subtree is missing, and {@code loadFamily} — the only place
 * that touches {@code rules-<family>-*.json} — runs lazily, from a scenario. A family whose sweep
 * has not started (today: {@code draft}) therefore contributes <em>zero</em> dynamic tests, and a
 * mistyped {@code family()}, a renamed subtree or an absent package prefix would all be invisible:
 * a green build that asserted nothing. The checks below are what makes an empty family home a
 * <em>declared, verified</em> home rather than a vacuous one.
 * </p>
 */
class RuleTestSuitesFamilyHomeTest
{

    /**
     * Every scenario factory in this package, one instance each. The {@code family()} strings come
     * from the factories themselves rather than a literal list, so this test cannot drift from the
     * factories it guards; {@link #everyFactoryIsRegisteredHere()} pins the converse (a new factory
     * must be added here).
     */
    private static final List<AbstractRuleTestSuitesFactory> FACTORIES = List.of(
            new RuleTestSuitesCdiscFactoryTest(), new RuleTestSuitesDraftFactoryTest(),
            new RuleTestSuitesFdaFactoryTest(), new RuleTestSuitesPmdaFactoryTest());

    /** {@code Core.Id} prefix of the unpublished draft rules. */
    private static final String DRAFT_PREFIX = "DRAFT-";

    private static final String DRAFT_FAMILY = "draft";

    private static final Path FACTORY_SOURCE_DIR = Path.of(System.getProperty("projectBasedir"),
            "src/test/java/net/cumba/corej/core/ruletestsuites");

    static Stream<String> families()
    {
        return FACTORIES.stream().map(AbstractRuleTestSuitesFactory::family);
    }


    /** The family's resource subtree must exist — empty is fine, absent is not. */
    @ParameterizedTest
    @MethodSource("families")
    void familyResourceSubtreeExists(String aFamily)
    {
        Path root = AbstractRuleTestSuitesFactory.familyRoot(aFamily);
        assertTrue(Files.isDirectory(root),
                "family '" + aFamily + "' declares no scenario home: " + root + " is not a"
                        + " directory. The factory would silently contribute zero tests.");
    }


    /**
     * {@code family()} must name a real assembled-package prefix. {@code loadFamily} already throws
     * when {@code rules-<family>-*.json} matches nothing; this additionally refuses a prefix whose
     * packages are all empty, which would resolve every scenario to "rule not found".
     */
    @ParameterizedTest
    @MethodSource("families")
    void familyPackagePrefixResolvesToRules(String aFamily)
    {
        Map<String, Map<String, Rule>> packages = AbstractRuleTestSuitesFactory.loadFamily(aFamily);
        assertFalse(packages.isEmpty(), "no rules-" + aFamily + "-*.json packages");
        assertFalse(coreIds(packages).isEmpty(),
                "the rules-" + aFamily + "-*.json packages carry no rules at all");
    }


    /**
     * ⚠ The draft partition: {@code DRAFT-*} rules are unpublished and are assembled into the
     * {@code rules-draft-*} packages <b>only</b>. Both directions matter — a draft id leaking into
     * a production package would ship an unpublished rule, and a non-draft id inside a
     * {@code rules-draft-*} package would let a draft scenario execute a published rule's view.
     */
    @ParameterizedTest
    @MethodSource("families")
    void draftRulesShipOnlyIntoDraftPackages(String aFamily)
    {
        Set<String> coreIds = coreIds(AbstractRuleTestSuitesFactory.loadFamily(aFamily));
        List<String> draft = coreIds.stream().filter(id -> id.startsWith(DRAFT_PREFIX)).toList();
        if (DRAFT_FAMILY.equals(aFamily))
        {
            assertEquals(coreIds.size(), draft.size(),
                    "the rules-draft-*.json packages must carry draft rules only, but also carry "
                            + coreIds.stream().filter(id -> !id.startsWith(DRAFT_PREFIX)).sorted()
                                    .toList());
        }
        else
        {
            assertTrue(draft.isEmpty(),
                    "unpublished draft rules " + draft.stream().sorted().toList()
                            + " leaked into the production family '" + aFamily + "'");
        }
    }


    /**
     * The per-family declaration policy, pinned as one map so it cannot drift silently.
     * {@code fda}, {@code pmda} and {@code draft} are greenfield subtrees whose scenarios must
     * declare {@code #library standard=… version=…} (or {@code #library-ref}); {@code cdisc} makes
     * it optional <em>only</em> to keep its migrated scenarios working. ⚑ {@code core} was the
     * second such family and went with the 2026-09 CORE-family retirement; the scenarios it had
     * that survived were migrated into {@code cdisc}, {@code fda} and {@code pmda}, where the
     * stricter policy of the latter two now applies to them.
     *
     * <p>
     * Flipping a family to optional would switch off its membership assertion for every undeclared
     * scenario and silently re-route it through {@code DEFAULT_PACKAGE_PREFERENCE} — a change no
     * scenario would fail on, which is why it is asserted here rather than left to the factories'
     * javadoc. For {@code draft} it is also the only exercise {@code requireDeclaredStandard()}
     * gets until the first draft scenario lands.
     * </p>
     */
    @Test
    void declarationPolicyPerFamily()
    {
        Map<String, Boolean> actual = new TreeMap<>();
        for (AbstractRuleTestSuitesFactory factory : FACTORIES)
        {
            actual.put(factory.family(), Boolean.valueOf(factory.requireDeclaredStandard()));
        }
        assertEquals(
                Map.of("cdisc", Boolean.FALSE, DRAFT_FAMILY, Boolean.TRUE, "fda", Boolean.TRUE,
                        "pmda", Boolean.TRUE),
                actual, "the per-family requireDeclaredStandard() policy changed");
    }


    /**
     * The converse of {@link #FACTORIES}: a factory added without an entry above would not be
     * covered by any check in this class, which is exactly the vacuity this test exists to stop.
     */
    @Test
    void everyFactoryIsRegisteredHere() throws IOException
    {
        Set<String> onDisk = new TreeSet<>();
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(FACTORY_SOURCE_DIR,
                "*FactoryTest.java"))
        {
            for (Path p : dir)
            {
                String name = p.getFileName().toString();
                onDisk.add(name.substring(0, name.length() - ".java".length()));
            }
        }
        Set<String> registered = new TreeSet<>(
                FACTORIES.stream().map(f -> f.getClass().getSimpleName()).toList());
        assertEquals(onDisk, registered,
                "every *FactoryTest in " + FACTORY_SOURCE_DIR + " must be listed in FACTORIES");
    }


    private static Set<String> coreIds(Map<String, Map<String, Rule>> aPackages)
    {
        Set<String> ids = new TreeSet<>();
        for (Map<String, Rule> pkg : aPackages.values())
        {
            ids.addAll(pkg.keySet());
        }
        return ids;
    }
}
