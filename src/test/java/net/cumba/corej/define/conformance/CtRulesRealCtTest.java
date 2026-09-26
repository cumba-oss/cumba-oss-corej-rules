package net.cumba.corej.define.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.corej.core.run.CtSelection;
import net.cumba.corej.core.run.DefineStoreBinding;
import net.cumba.corej.define.conformance.eval.RuleResult;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.Requires;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code Requires: ct} Define-XML rules against the REAL Controlled Terminology — the
 * pickle-seeded unified store ({@link MetadataStoreFixture}), bound through
 * {@link DefineStoreBinding} exactly as the CLI's {@code -vx} and the data browser's define check
 * bind it (PLAN-define-ct-evaluation §6 row N3).
 *
 * <p>
 * <b>Why it exists.</b> {@link CtRulesTest} and {@link PmdaResidualRulesTest} run the same rules
 * against {@code StubCtProvider}, a four-codelist fixture nobody verified against a CT package.
 * This class is the external oracle for that stub: the codelists, extensible flags, submission
 * values, term c-codes and codelist names come from the CDISC packages themselves.
 * </p>
 *
 * <p>
 * <b>Every expected set below was ADJUDICATED before it was written</b> — by
 * {@code scripts/define-real-store-oracle.py}, which reads the CT pickles directly (never the
 * store, never the engine), recomputes each rule's findings from its definition, and marks every
 * engine finding TRUE or FALSE-POSITIVE (and every finding it expects that the engine missed as
 * MISSED). All four cases came back all-TRUE, none MISSED. Re-adjudicate after any change: run this
 * class, then
 * {@code python3 scripts/define-real-store-oracle.py ct --define <fixture> --version <v> --ct
 * <ids> --engine target/define-real-store/<case>.tsv}.
 * </p>
 *
 * <h2>(i) The CDISC MSG v2 example define, bound by its own declaration</h2>
 * <p>
 * {@code define-v21-msg-sdtm.xml} declares {@code CDISC/NCI} CT for {@code SDTM} and
 * {@code DEFINE-XML}, both {@code 2020-12-18}; with a blank user list the binding takes exactly
 * those two packages (D1, source DEFINE), and all 7 CDISC-2.1 and 17 PMDA-2.1 CT rules EXECUTE. A
 * CDISC-authored example firing CT rules is plausible, not proof of a defect — each finding was
 * read, and each is a real defect of the example against its own declared CT:
 * </p>
 * <ul>
 * <li>{@code STDTMIG} (the example's own typo for {@code SDTMIG}) is no Standard Name term
 * (DD0021-B);</li>
 * <li>{@code Anisocytes; Anisocytosis} is no {@code C67154} LBTEST submission value and is not
 * marked as an extension (0179 / 0186 / DD0029), and its alias {@code C74797} stands for
 * {@code Anisocytes} (DD0028);</li>
 * <li>seven CodeListItems carry the c-code of ANOTHER term (DD0028-B): {@code NV} with {@code LB}'s
 * {@code C49592}; {@code NOT HISPANIC OR LATINO} with {@code HISPANIC OR LATINO}'s {@code C17459};
 * {@code mL} with {@code %}'s {@code C25613}; and two swapped pairs, {@code fmol}/{@code g/L} and
 * {@code mU/L}/{@code umol/L};</li>
 * <li>five term aliases name a c-code that is no term of the codelist (DD0034 / DD0034-B):
 * {@code C25206} under VSTEST/VSTESTCD (in 2020-12-18 it is a Device/Biospecimen Properties term),
 * {@code C48251} under NCOMPLT and {@code C78728} under RELTYPE (neither in the package at all),
 * {@code C42998} under UNIT (it is {@code TABLET} of Pharmaceutical Dosage Form).</li>
 * </ul>
 * <p>
 * The example's four {@code def:ExtendedValue="Yes"} items (NVTEST/NVTESTCD, OETEST/OETESTCD) are
 * correctly exempt from 0179/0192/0186/0201/DD0029: they are marked extensions of extensible
 * codelists.
 * </p>
 *
 * <h2>(ii) {@code ct-violations-21.xml} against sdtmct/define-xmlct 2024-03-29</h2>
 * <p>
 * Reproduces {@code CtRulesTest.EXPECTED_21} exactly for every rule of that batch (restated here as
 * {@link #STUB_EXPECTED_21}; {@code DEFINE-XML-0153} is the batch's one non-CT rule and is not run
 * here). The real CT additionally executes the CT rules outside that batch: DD0029 / DD0029-B fire
 * twice each on the fixture's unmarked extensible-codelist non-members, and DD0132 / DD0132-B once
 * each on the marked items of non-extensible Sex (0187 / 0202's twins). <b>Divergences of the stub
 * from the real 2024-03-29 CT, none of which moves a finding:</b>
 * </p>
 * <ul>
 * <li>STDNAM {@code C170452} is {@code extensible=true} in the stub, {@code false} in
 * define-xmlct-2024-03-29 (it was {@code true} in 2020-12-18). DD0021-B has no
 * {@code nonExtensibleOnly}, so {@code FOOIG} fires either way;</li>
 * <li>Sex {@code C66731} has {@code F, M, U} in the stub, {@code F, INTERSEX, M, U} in 2024-03-29;
 * the fixture's non-members {@code X} / {@code Y} are neither;</li>
 * <li>Unit {@code C71620} has 2 terms in the stub, 901 in the package; GNRLOBSC {@code C103329} has
 * 4 in the stub, 16 in the package — {@code furlong}, {@code stone}, {@code WEIRD CLASS} are in
 * none of them.</li>
 * </ul>
 *
 * <h2>(iii) {@code ct-violations-20.xml} at 2.0, same packages (T1-3 b)</h2>
 * <p>
 * All 14 PMDA-2.0 CT rules EXECUTE, PMDA-DD0031 among them: its codelist-level name lookup is
 * served by the store's format-3 codelist names. {@code "Sex"} IS the {@code name} of
 * {@code C66731} in sdtmct-2024-03-29 (adjudicated), so DD0031 fires on {@code CL.SEX20}, which has
 * no {@code nci:ExtCodeID} alias. The sponsor-named {@code CL.SEX20B} ({@code "Sex Assignment"}, no
 * such CT name) does not — proved in isolation by a variant with its alias removed, where DD0031
 * still fires only on {@code CL.SEX20} and DD0024 loses its {@code Z} (an alias-less codelist
 * resolves to no CT codelist).
 * </p>
 */
class CtRulesRealCtTest
{

    private static final @Nullable Path STORE = MetadataStoreFixture.storeFile().orElse(null);

    private static final List<String> CT_2024 = List.of("sdtmct-2024-03-29",
            "define-xmlct-2024-03-29");

    /** The 7 CDISC Define-XML 2.1 CT rules. */
    private static final Set<String> CDISC_21 = Set.of("DEFINE-XML-0132", "DEFINE-XML-0179",
            "DEFINE-XML-0186", "DEFINE-XML-0187", "DEFINE-XML-0192", "DEFINE-XML-0201",
            "DEFINE-XML-0202");

    /** The 17 PMDA Define-XML 2.1 CT rules. */
    private static final Set<String> PMDA_21 = Set.of("PMDA-DD0021-B", "PMDA-DD0024",
            "PMDA-DD0024-B", "PMDA-DD0028", "PMDA-DD0028-B", "PMDA-DD0029", "PMDA-DD0029-B",
            "PMDA-DD0032", "PMDA-DD0032-B", "PMDA-DD0033", "PMDA-DD0034", "PMDA-DD0034-B",
            "PMDA-DD0055-C", "PMDA-DD0099", "PMDA-DD0100", "PMDA-DD0132", "PMDA-DD0132-B");

    /** The 14 PMDA Define-XML 2.0 CT rules — DD0031 is the 2.0-only one. */
    private static final Set<String> PMDA_20 = Set.of("PMDA-DD0024", "PMDA-DD0024-B", "PMDA-DD0028",
            "PMDA-DD0028-B", "PMDA-DD0029", "PMDA-DD0029-B", "PMDA-DD0031", "PMDA-DD0032",
            "PMDA-DD0032-B", "PMDA-DD0033", "PMDA-DD0034", "PMDA-DD0034-B", "PMDA-DD0099",
            "PMDA-DD0100");

    /** (i) Adjudicated: the MSG example against its declared 2020-12-18 CT. */
    private static final List<String> MSG_EXPECTED = List.of(//
            "DEFINE-XML-0179 /CodeList[@OID='CL.LBTEST']/EnumeratedItem[4] = Anisocytes; Anisocytosis",
            "DEFINE-XML-0186 /CodeList[@OID='CL.LBTEST']/EnumeratedItem[4] = Anisocytes; Anisocytosis",
            "PMDA-DD0021-B /Standards/Standard[@OID='STD.1'] = STDTMIG",
            "PMDA-DD0028 /CodeList[@OID='CL.LBTEST']/EnumeratedItem[4] = Anisocytes; Anisocytosis",
            "PMDA-DD0028-B /CodeList[@OID='CL.DOMAIN_NV']/CodeListItem = NV",
            "PMDA-DD0028-B /CodeList[@OID='CL.ETHNIC']/CodeListItem[2] = NOT HISPANIC OR LATINO",
            "PMDA-DD0028-B /CodeList[@OID='CL.UNIT_ECDOSU']/CodeListItem = mL",
            "PMDA-DD0028-B /CodeList[@OID='CL.UNIT_LB_fmol']/CodeListItem = fmol",
            "PMDA-DD0028-B /CodeList[@OID='CL.UNIT_LB_g/L']/CodeListItem = g/L",
            "PMDA-DD0028-B /CodeList[@OID='CL.UNIT_LB_mU/L']/CodeListItem = mU/L",
            "PMDA-DD0028-B /CodeList[@OID='CL.UNIT_LB_umol/L']/CodeListItem = umol/L",
            "PMDA-DD0029 /CodeList[@OID='CL.LBTEST']/EnumeratedItem[4] = Anisocytes; Anisocytosis",
            "PMDA-DD0034 /CodeList[@OID='CL.VSTEST']/EnumeratedItem[5]/Alias = C25206",
            "PMDA-DD0034-B /CodeList[@OID='CL.NCOMPLT']/CodeListItem[9]/Alias = C48251",
            "PMDA-DD0034-B /CodeList[@OID='CL.RELTYPE']/CodeListItem[1]/Alias = C78728",
            "PMDA-DD0034-B /CodeList[@OID='CL.UNIT_CM']/CodeListItem[3]/Alias = C42998",
            "PMDA-DD0034-B /CodeList[@OID='CL.VSTESTCD']/CodeListItem[5]/Alias = C25206");

    /** (ii) Adjudicated: ct-violations-21.xml against the 2024-03-29 CT. */
    private static final List<String> V21_EXPECTED = List.of(//
            // 0132 / DD0055-C: the Message has no ${value}, so the line carries it whole; the
            // offending def:Class is IG.XX's "WEIRD CLASS".
            "DEFINE-XML-0132 /ItemGroupDef[@OID='IG.XX']/Class = The value of the dataset's class"
                    + " name [ItemGroupDef/def:Class/@Name] is not in the CDISC/NCI GNRLOBSC"
                    + " General Observation Class codelist with C-Code C103329.",
            "DEFINE-XML-0179 /CodeList[@OID='CL.SEX']/EnumeratedItem[2] = X",
            "DEFINE-XML-0179 /CodeList[@OID='CL.UNIT']/EnumeratedItem = furlong",
            "DEFINE-XML-0186 /CodeList[@OID='CL.UNIT']/EnumeratedItem = furlong",
            "DEFINE-XML-0187 /CodeList[@OID='CL.SEX2']/EnumeratedItem = F",
            "DEFINE-XML-0192 /CodeList[@OID='CL.SEXITEMS']/CodeListItem = Y",
            "DEFINE-XML-0192 /CodeList[@OID='CL.UNITB']/CodeListItem = stone",
            "DEFINE-XML-0201 /CodeList[@OID='CL.UNITB']/CodeListItem = stone",
            "DEFINE-XML-0202 /CodeList[@OID='CL.SEX3']/CodeListItem = M",
            "PMDA-DD0021-B /Standards/Standard[@OID='STD.BAD'] = FOOIG",
            "PMDA-DD0024 /CodeList[@OID='CL.SEX']/EnumeratedItem[2] = X",
            "PMDA-DD0024-B /CodeList[@OID='CL.SEXITEMS']/CodeListItem = Y",
            "PMDA-DD0028 /CodeList[@OID='CL.UNIT2']/EnumeratedItem = milligram",
            "PMDA-DD0028-B /CodeList[@OID='CL.UNIT3']/CodeListItem = millilitre",
            "PMDA-DD0029 /CodeList[@OID='CL.UNIT']/EnumeratedItem = furlong",
            "PMDA-DD0029 /CodeList[@OID='CL.UNIT2']/EnumeratedItem = milligram",
            "PMDA-DD0029-B /CodeList[@OID='CL.UNIT3']/CodeListItem = millilitre",
            "PMDA-DD0029-B /CodeList[@OID='CL.UNITB']/CodeListItem = stone",
            "PMDA-DD0032 /CodeList[@OID='CL.SEX5']/EnumeratedItem = M",
            "PMDA-DD0032-B /CodeList[@OID='CL.SEX6']/CodeListItem = F",
            "PMDA-DD0033 /CodeList[@OID='CL.BAD']/Alias = C99999",
            "PMDA-DD0034 /CodeList[@OID='CL.SEX7']/EnumeratedItem/Alias = C99998",
            "PMDA-DD0034-B /CodeList[@OID='CL.SEX8']/CodeListItem/Alias = C99997",
            "PMDA-DD0055-C /ItemGroupDef[@OID='IG.XX']/Class = Invalid Class value",
            "PMDA-DD0132 /CodeList[@OID='CL.SEX2']/EnumeratedItem = F",
            "PMDA-DD0132-B /CodeList[@OID='CL.SEX3']/CodeListItem = M");

    /**
     * {@code CtRulesTest.EXPECTED_21} verbatim, minus {@code DEFINE-XML-0153} (the batch's one rule
     * without {@code Requires: ct}) — the stub's per-rule counts the real CT must reproduce.
     */
    private static final Map<String, Integer> STUB_EXPECTED_21 = Map.ofEntries(//
            Map.entry("DEFINE-XML-0132", 1), Map.entry("DEFINE-XML-0179", 2),
            Map.entry("DEFINE-XML-0186", 1), Map.entry("DEFINE-XML-0187", 1),
            Map.entry("DEFINE-XML-0192", 2), Map.entry("DEFINE-XML-0201", 1),
            Map.entry("DEFINE-XML-0202", 1), Map.entry("PMDA-DD0021-B", 1),
            Map.entry("PMDA-DD0024", 1), Map.entry("PMDA-DD0024-B", 1), Map.entry("PMDA-DD0028", 1),
            Map.entry("PMDA-DD0028-B", 1), Map.entry("PMDA-DD0032", 1),
            Map.entry("PMDA-DD0032-B", 1), Map.entry("PMDA-DD0033", 1), Map.entry("PMDA-DD0034", 1),
            Map.entry("PMDA-DD0034-B", 1), Map.entry("PMDA-DD0055-C", 1));

    /**
     * (iii) Adjudicated: ct-violations-20.xml against the 2024-03-29 CT — the same two findings, on
     * the same nodes, as {@code CtRulesTest.EXPECTED_20} ({@code DD0031} ×1, {@code DD0024} ×1).
     */
    private static final List<String> V20_EXPECTED = List.of(//
            "PMDA-DD0024 /CodeList[@OID='CL.SEX20B']/EnumeratedItem = Z",
            "PMDA-DD0031 /CodeList[@OID='CL.SEX20'] = Sex");

    @TempDir
    Path tempDir;

    private static Path store()
    {
        assumeTrue(STORE != null, "no metadata store seeded: export CDISC_PICKLE_CACHE_DIR"
                + " (the real-CT oracle needs the pickle corpus)");
        return STORE;
    }


    private static Map<String, ConformanceRule> roster(String aVersion, Set<String> aExpected)
    {
        Map<String, ConformanceRule> rules = RealStoreDefineRun.corpusRules(Requires.CT, aVersion);
        assertEquals(new TreeSet<>(aExpected), rules.keySet(),
                "Requires: ct rules applicable at " + aVersion);
        assertEquals(aExpected.size(), rules.size());
        return rules;
    }


    private static Set<String> union(Set<String> aLeft, Set<String> aRight)
    {
        Set<String> all = new TreeSet<>(aLeft);
        all.addAll(aRight);
        return all;
    }


    @Test
    void msgExampleBoundByItsOwnDeclaration()
    {
        Path define = RealStoreDefineRun.fixture("define-v21-msg-sdtm.xml");
        Map<String, ConformanceRule> rules = roster("2.1", union(CDISC_21, PMDA_21));
        assertEquals(24, rules.size(), "7 CDISC + 17 PMDA");
        try (DefineStoreBinding binding = DefineStoreBinding.resolve(define, List.of(), store()))
        {
            assertEquals(CtSelection.Source.DEFINE, binding.selection().source());
            assertEquals(Set.of("sdtmct-2020-12-18", "define-xmlct-2020-12-18"),
                    Set.copyOf(binding.selection().packageIds()), binding.basis());
            assertTrue(binding.ctProvider().isPresent(), binding.basis());
            Map<String, RuleResult> results = RealStoreDefineRun.evaluate(define, "2.1", binding,
                    rules);
            RealStoreDefineRun.dump("msg-ct-21", results);
            RealStoreDefineRun.assertAllExecuted(results);
            assertEquals(MSG_EXPECTED, RealStoreDefineRun.findingLines(results));
        }
    }


    @Test
    void violations21ReproduceTheStubAgainstRealCt()
    {
        Path define = RealStoreDefineRun.fixture("ct-violations-21.xml");
        Map<String, ConformanceRule> rules = roster("2.1", union(CDISC_21, PMDA_21));
        try (DefineStoreBinding binding = DefineStoreBinding.resolve(define, CT_2024, store()))
        {
            assertEquals(CtSelection.Source.USER, binding.selection().source());
            Map<String, RuleResult> results = RealStoreDefineRun.evaluate(define, "2.1", binding,
                    rules);
            RealStoreDefineRun.dump("ct-violations-21", results);
            RealStoreDefineRun.assertAllExecuted(results);
            List<String> lines = RealStoreDefineRun.findingLines(results);
            assertEquals(V21_EXPECTED, lines);
            Map<String, Integer> batchCounts = new TreeMap<>();
            for (String id : STUB_EXPECTED_21.keySet())
            {
                batchCounts.put(id, results.get(id).findings().size());
            }
            assertEquals(new TreeMap<>(STUB_EXPECTED_21), batchCounts,
                    "CtRulesTest.EXPECTED_21 against the real CT");
        }
    }


    @Test
    void violations20ExecuteAllFourteenIncludingDd0031()
    {
        Path define = RealStoreDefineRun.fixture("ct-violations-20.xml");
        Map<String, ConformanceRule> rules = roster("2.0", PMDA_20);
        assertEquals(14, rules.size());
        try (DefineStoreBinding binding = DefineStoreBinding.resolve(define, CT_2024, store()))
        {
            Map<String, RuleResult> results = RealStoreDefineRun.evaluate(define, "2.0", binding,
                    rules);
            RealStoreDefineRun.dump("ct-violations-20", results);
            RealStoreDefineRun.assertAllExecuted(results);
            assertEquals(V20_EXPECTED, RealStoreDefineRun.findingLines(results));
        }
    }


    @Test
    void dd0031IgnoresASponsorNamedCodelistWithoutAlias()
    {
        Path define = RealStoreDefineRun.variant("ct-violations-20.xml", tempDir,
                "<Alias Context=\"nci:ExtCodeID\" Name=\"C66731\"/>", "");
        Map<String, ConformanceRule> rules = roster("2.0", PMDA_20);
        try (DefineStoreBinding binding = DefineStoreBinding.resolve(define, CT_2024, store()))
        {
            Map<String, RuleResult> results = RealStoreDefineRun.evaluate(define, "2.0", binding,
                    rules);
            RealStoreDefineRun.dump("ct-violations-20-sponsor-named", results);
            RealStoreDefineRun.assertAllExecuted(results);
            assertEquals(List.of("PMDA-DD0031 /CodeList[@OID='CL.SEX20'] = Sex"),
                    RealStoreDefineRun.findingLines(results));
        }
    }
}
