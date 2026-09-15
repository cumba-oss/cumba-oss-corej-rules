package net.cumba.corej.core.ruletestsuites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code Fix #355} half (i), plan A Phase 4 step 5 — the <b>positive</b> acceptance of the first
 * {@code !X} authoring pass, asserted against the <b>shipped</b> packages under
 * {@code target/rules}, not a hand-built rule.
 *
 * <p>
 * Three rules carry the five pure-gate {@code $}-operation exclusions (§5.9 item 9): the operation
 * result is read only by an {@code empty($x)} guard, so whenever the rule fires the value is empty
 * by construction and carries nothing a reviewer can act on. A green {@code .cdt} suite proves
 * nothing here (§4.6 hazard 1 — the table fallback), and the study-level findings snapshot has no
 * reach over SEND/ADaM rules; so each case runs the rule's own {@code expect=violation} fixture
 * through {@link RuleRunner} and asserts the excluded key is <b>absent</b> from
 * {@link Violation#getValues()} — {@code !containsKey}, never {@code get() == null}, which ruling 5
 * forbids.
 * </p>
 *
 * <p>
 * The control arm re-loads the same shipped package with the {@code !} stripped off exactly these
 * entries and runs the identical fixture: the key is then <em>present</em>. The two arms together
 * pin that the only delta the exclusion makes to the finding is the removal of that projected value
 * — no finding appears, disappears or moves row (count and row agree across the arms).
 * </p>
 */
class ExcludedOutputVariableShippedRuleTest
{

    private static final Path RULES_DIR = CorpusPaths.rules();

    private static final Path RESOURCE_BASE = Path.of(System.getProperty("projectBasedir"),
            "src/test/resources/net/cumba/corej/core/ruletestsuites");

    static Stream<Arguments> exclusions()
    {
        return Stream.of(
                Arguments.of("CDISC-AD0497", "rules-cdisc-adamig-1-3.json",
                        "cdisc/CDISC-AD0497/CDISC-AD0497-invalid-ADLBC.cdt",
                        List.of("dataset_name"), Set.of("$dataset_class")),
                Arguments.of("CDISC-SEND-0105.1", "rules-cdisc-sendig-3-1-1.json",
                        "cdisc/CDISC-SEND-0105.1/CDISC-SEND-0105.1-invalid_no_species_param-DM.cdt",
                        List.of("SPECIES"), Set.of("$ts_species", "$tx_species")),
                Arguments.of("CDISC-SEND-0106.1", "rules-cdisc-sendig-3-1-1.json",
                        "cdisc/CDISC-SEND-0106.1/CDISC-SEND-0106.1-invalid_no_strain_anywhere-DM.cdt",
                        List.of("STRAIN"), Set.of("$ts_strain", "$tx_strain")));
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("exclusions")
    void theExcludedOperationResultIsAbsentFromTheShippedRulesFindings(String aCoreId,
            String aPackage, String aFixture, List<String> aRetained, Set<String> aExcluded)
        throws IOException
    {
        String json = Files.readString(RULES_DIR.resolve(aPackage), StandardCharsets.UTF_8);
        Rule shipped = ruleOf(json, aCoreId);
        List<String> effective = shipped.getEffectiveOutputVariables();
        assertTrue(effective.containsAll(aRetained), "retained entries survive: " + effective);
        for (String id : aExcluded)
        {
            assertFalse(effective.contains(id), id + " still effective: " + effective);
        }
        assertEquals(aExcluded, shipped.excludedOutputVariablesOrAuthored());

        // Control: the same package with the `!` stripped off exactly these entries — the
        // pre-Fix-#355 authoring, which projected the (empty) operation result.
        String control = json;
        for (String id : aExcluded)
        {
            assertTrue(control.contains("\"!" + id + "\""), "shipped package carries !" + id);
            control = control.replace("\"!" + id + "\"", "\"" + id + "\"");
        }
        Rule authored = ruleOf(control, aCoreId);
        assertTrue(authored.getEffectiveOutputVariables().containsAll(aExcluded),
                "control projects the operation result: " + authored.getEffectiveOutputVariables());

        RuleTestScenario scenario = RuleTestCdt.load(RESOURCE_BASE.resolve(aFixture));
        assertEquals(aCoreId, scenario.getCoreId());
        OverlayDataTable primary = scenario.primaryTable();
        assertNotNull(primary);

        RuleExecutionResult after = run(shipped, scenario, primary);
        RuleExecutionResult before = run(authored, scenario, primary);

        // No finding appears, disappears or moves row between the two arms …
        assertTrue(after.getViolationCount() > 0, "status=" + after.getStatus());
        assertEquals(before.getViolationCount(), after.getViolationCount());
        assertEquals(rows(before), rows(after));
        // … and the only delta is the removal of the projected value: present (and empty — the
        // rule fires only when it is) on the control, ABSENT (no key) on the shipped rule.
        for (Violation v : after.getViolations())
        {
            for (String id : aExcluded)
            {
                assertFalse(v.getValues().containsKey(id), id + " in " + v.getValues());
            }
        }
        for (Violation v : before.getViolations())
        {
            for (String id : aExcluded)
            {
                assertTrue(v.getValues().containsKey(id), id + " not in " + v.getValues());
                String value = v.getValues().get(id);
                assertTrue(value == null || value.isEmpty(), id + "=" + value);
            }
        }
    }


    /**
     * {@code Fix #357} — the class-(c) apply: 109 guard-only {@code Output_Variables} entries over
     * 90 rules, ruling (A) of {@code rule-review-results-r3/PROPOSAL-plan-A-class-c-exclusions.md}
     * (owner 2026-08-24, Message-usage criterion). The three cases above cover {@code $}-operation
     * results; these cover the other two token shapes the ruling introduces — <b>plain columns</b>
     * and <b>{@code --} wildcard columns</b>, plus the two dotted {@code DM.ARM} references — where
     * the projection key is the <em>resolved</em> name, not the authored token.
     *
     * <p>
     * ⚠ The wildcard half of this ruling was blocked until {@code Fix #356}: the per-domain
     * expansion substituted the domain prefix into {@code Output_Variables} with a bare
     * {@code startsWith("--")} test that could not see through the {@code !}, so a concrete rule
     * carried a resolved Check against an unresolved {@code !--X} and failed E-3 check 1 with an
     * {@code ENGINE_ERROR}. {@code OutputVariableToken.mapName} closed it at all three generation
     * sites. These cases are the corpus-side regression pin for that.
     * </p>
     *
     * <p>
     * A sample of 11 rules spanning the three families shipped here (CDISC, FDA, PMDA) and all
     * three {@code kind}s of the proposal's TSV, each run on the rule's own
     * {@code expect=violation} fixture. Of the 90 edited rules, 72 have such a fixture; the other
     * 18 have none and are not assertable this way.
     *
     * <p>
     * ⚠ Four CORE-family rows (CORE-000018 / -000040 / -000095 / -001043) were dropped when this
     * repository was extracted: the CORE rule family is not redistributed here, so neither their
     * packages nor their {@code .cdt} fixtures exist. The internal corpus still covers them.
     * </p>
     */
    static Stream<Arguments> columnExclusions()
    {
        return Stream.of(
                Arguments.of("CDISC-SEND-0085.1", "rules-cdisc-sendig-3-1-1.json",
                        "cdisc/CDISC-SEND-0085.1/CDISC-SEND-0085.1-invalid_blank_stat-BW.cdt",
                        "BWSTAT", Set.of("--ORRES"), Set.of("BWORRES")),
                Arguments.of("FDA-SD0021", "rules-fda-sdtmig-3-4.json",
                        "fda/FDA-SD0021/FDA-SD0021-invalid-occur-CM.cdt", "CMOCCUR",
                        Set.of("--ENDTC", "--ENRF", "--ENRTPT", "--STAT", "--DUR"),
                        Set.of("CMENDTC", "CMENRTPT", "CMSTAT", "CMDUR")),
                Arguments.of("FDA-SD0079", "rules-fda-sdtmig-3-4.json",
                        "fda/FDA-SD0079/FDA-SD0079-invalid-EX.cdt", "EPOCH", Set.of("DM.ARM"),
                        Set.of("DM.ARM")),
                Arguments.of("FDA-SD1348", "rules-fda-sdtmig-3-4.json",
                        "fda/FDA-SD1348/FDA-SD1348-invalid-PP.cdt", "PPTESTCD",
                        Set.of("--STINT", "--ENINT"), Set.of("PPSTINT", "PPENINT")),
                Arguments.of("FDA-SE2242", "rules-fda-sendig-3-1-1.json",
                        "fda/FDA-SE2242/FDA-SE2242-invalid-MA.cdt", "MATESTCD", Set.of("--SPEC"),
                        Set.of("MASPEC")),
                Arguments.of("FDA-SD1396", "rules-fda-sendig-ar-1-0.json",
                        "fda/FDA-SD1396/FDA-SD1396-invalid-FA.cdt", "FADTC", Set.of("--CHDY"),
                        Set.of("FACHDY")),
                Arguments.of("PMDA-SD0079", "rules-pmda-sdtmig-3-4.json",
                        "pmda/PMDA-SD0079/PMDA-SD0079-invalid-EX.cdt", "EPOCH", Set.of("DM.ARM"),
                        Set.of("DM.ARM")),
                Arguments.of("PMDA-SD1017", "rules-pmda-sdtmig-3-4.json",
                        "pmda/PMDA-SD1017/PMDA-SD1017-invalid-SV.cdt", "VISITNUM",
                        Set.of("SVUPDES"), Set.of("SVUPDES")));
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("columnExclusions")
    void theExcludedColumnIsAbsentFromTheShippedRulesFindings(String aCoreId, String aPackage,
            String aFixture, String aRetainedKey, Set<String> aAuthored, Set<String> aProjected)
        throws IOException
    {
        String json = Files.readString(RULES_DIR.resolve(aPackage), StandardCharsets.UTF_8);
        Rule shipped = ruleOf(json, aCoreId);
        assertEquals(aAuthored, shipped.excludedOutputVariablesOrAuthored());
        List<String> effective = shipped.getEffectiveOutputVariables();
        for (String name : aAuthored)
        {
            assertFalse(effective.contains(name), name + " still effective: " + effective);
        }

        // Control: the same package with the `!` stripped off exactly these entries — the
        // pre-Fix-#357 authoring, which projected the guard-only column.
        String control = json;
        for (String name : aAuthored)
        {
            assertTrue(control.contains("\"!" + name + "\""), "shipped package carries !" + name);
            control = control.replace("\"!" + name + "\"", "\"" + name + "\"");
        }
        Rule authored = ruleOf(control, aCoreId);
        assertTrue(authored.getEffectiveOutputVariables().containsAll(aAuthored),
                "control projects the entry: " + authored.getEffectiveOutputVariables());

        RuleTestScenario scenario = RuleTestCdt.load(RESOURCE_BASE.resolve(aFixture));
        assertEquals(aCoreId, scenario.getCoreId());
        OverlayDataTable primary = scenario.primaryTable();
        assertNotNull(primary);

        RuleExecutionResult after = run(shipped, scenario, primary);
        RuleExecutionResult before = run(authored, scenario, primary);

        // No finding appears, disappears or moves row between the two arms …
        assertTrue(after.getViolationCount() > 0, "status=" + after.getStatus());
        assertEquals(before.getViolationCount(), after.getViolationCount());
        assertEquals(rows(before), rows(after));

        // … the excluded RESOLVED name is absent from the shipped finding and a retained sibling
        // is still projected …
        for (Violation v : after.getViolations())
        {
            for (String key : aProjected)
            {
                assertFalse(v.getValues().containsKey(key), key + " in " + v.getValues());
            }
            assertTrue(v.getValues().containsKey(aRetainedKey),
                    aRetainedKey + " missing from " + v.getValues());
        }
        // … it IS projected on the control …
        for (Violation v : before.getViolations())
        {
            for (String key : aProjected)
            {
                assertTrue(v.getValues().containsKey(key), key + " not in " + v.getValues());
            }
        }
        // … and the removal of those keys is the ONLY delta to the projected map.
        List<Violation> shippedViolations = after.getViolations();
        List<Violation> controlViolations = before.getViolations();
        for (int i = 0; i < shippedViolations.size(); i++)
        {
            Set<String> expected = new TreeSet<>(controlViolations.get(i).getValues().keySet());
            expected.removeAll(aProjected);
            assertEquals(expected, new TreeSet<>(shippedViolations.get(i).getValues().keySet()));
        }
    }


    private static Rule ruleOf(String aJson, String aCoreId) throws IOException
    {
        RulePackage pkg = RulePackageLoader.loadFromString(aJson);
        Map<String, Rule> rules = pkg.getRules();
        assertNotNull(rules);
        for (Rule rule : rules.values())
        {
            String coreId = rule.getCore() != null ? rule.getCore().getId() : rule.getId();
            if (aCoreId.equals(coreId))
            {
                assertNull(rule.getLoadError(), rule.getLoadError());
                return rule;
            }
        }
        throw new AssertionError(aCoreId + " not in the package");
    }


    private static RuleExecutionResult run(Rule aRule, RuleTestScenario aScenario,
            OverlayDataTable aPrimary)
    {
        return RuleRunner.execute(aRule, aPrimary, aScenario.resolver(), aScenario.getDomain(),
                ScenarioLibrarySupport.resolveOrSkip(aScenario), null, aScenario.getDefine(),
                Integer.MAX_VALUE, null, null, null);
    }


    private static List<Long> rows(RuleExecutionResult aResult)
    {
        return aResult.getViolations().stream().map(Violation::getRow).sorted().toList();
    }
}
