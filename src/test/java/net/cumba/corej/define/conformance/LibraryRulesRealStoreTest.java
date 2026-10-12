package net.cumba.corej.define.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.corej.core.run.DefineStoreBinding;
import net.cumba.corej.define.conformance.eval.RuleResult;
import net.cumba.corej.define.conformance.library.LibraryProvider;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.Requires;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code Requires: library} Define-XML rules against the REAL CDISC Library content — the
 * pickle-seeded unified store's IG and model products, bound through {@link DefineStoreBinding}
 * (PLAN-define-ct-evaluation §6 row N3L). {@link CdiscLibraryRulesTest} and
 * {@link PmdaLibraryRulesTest} never met a real IG; this class does.
 *
 * <p>
 * <b>Every expected set was ADJUDICATED before it was written</b> by
 * {@code scripts/define-real-store-oracle.py library}, which reads {@code standards_details.pkl}
 * and {@code standards_models.pkl} directly (is the label really different, the codelist really
 * assigned, the Core really {@code Req}, the version really unpublished?) and marks every engine
 * finding TRUE or FALSE-POSITIVE, every expected-but-absent one MISSED. Both cases came back
 * all-TRUE, none MISSED. Re-adjudicate with {@code --engine target/define-real-store/<case>.tsv}
 * (add {@code --replace 'Name="STDTMIG"->Name="SDTMIG"'} for the corrected case).
 * </p>
 *
 * <h2>(i) The CDISC MSG v2 example as published: STDTMIG 3.3 + SDTMIG-MD 1.1</h2>
 * <p>
 * All 8 CDISC-2.1 and 5 PMDA-2.1 library rules EXECUTE, with <b>no findings</b> — and the reason is
 * the DOCUMENT's defect, not the engine's: its IG standard is spelled {@code STDTMIG} (CDISC's own
 * typo for {@code SDTMIG}), and all 31 datasets reference it. The provider folds hyphens and case
 * ({@code SDTM-IG} = {@code SDTMIG}) but {@code STDTMIG} is a different word, so every
 * {@code STD.1}-governed dataset, variable and codelist lookup is out of reach, and those rules
 * find nothing to compare. (PMDA-DD0021-B, a CT rule, reports the typo itself — see
 * {@link CtRulesRealCtTest}.) The probes below prove that out-of-reach is what happens, rather than
 * assuming it.
 * </p>
 * <p>
 * <b>D-11 variant findings, listed separately.</b> {@code SDTMIG-MD 1.1} maps to
 * {@code standards/sdtmig/md-1-1}; the API path never reached it. No dataset uses it, so it is
 * reached by exactly two rules: DEFINE-XML-0263 (1.1 IS a published SDTMIG-MD version — no finding)
 * and PMDA-DD0116, which takes the document's first SDTMIG-family standard, follows md-1-1's model
 * link to {@code models/sdtm/1-7} and compares the FATESTCD codelist's decodes: {@code OCCUR} =
 * "Occurrence Indicator" and {@code SEV} = "Severity/Intensity", both equal to the model's
 * {@code --OCCUR} / {@code --SEV} labels — no finding, and a proved agreement, not an unreached
 * lookup ({@link #msgExampleAsPublished} probes both labels). <b>Variant findings: none.</b>
 * </p>
 *
 * <h2>(ii) The same document with the typo corrected ({@code STDTMIG} → {@code SDTMIG})</h2>
 * <p>
 * Not in the plan's row; added because case (i) leaves 11 of the 13 rules executing over nothing.
 * With the one attribute corrected, SDTMIG 3.3 is in reach for every dataset, and the adjudicated
 * findings are the example's real departures from the published IG:
 * </p>
 * <ul>
 * <li>0265 / DD0136: CM's label "Concomitant Medications" is "Concomitant/Prior Medications" in
 * SDTMIG 3.3, TS's "Trial Summary" is "Trial Summary Information";</li>
 * <li>DD0118 ×2: RSTESTCD / RSTEST reference the HAMD-17 codelists {@code C100138} /
 * {@code C100137}, while the Library assigns RS only {@code C96782} / {@code C96781}.</li>
 * </ul>
 * <p>
 * 0149 / DD0124 raise nothing since rule round 2026-10-12 (owner, DCE-1: "a value-level codelist
 * satisfies"): RACE, DSDECOD, LBORRESU, LBSTRESC, LBSTRESU, RSSTRESC, VSORRESU and VSSTRESU have an
 * IG codelist but no {@code CodeListRef} of their own — the example attaches their CT at value
 * level ({@code def:ValueListRef} to value-level ItemDefs carrying one), which the rules now
 * accept; they fired 8 times each before.
 * </p>
 * <p>
 * 0067, 0097, 0098, 0099, 0263, 0266, DD0116 and DD0137 raise nothing here (the Req variables are
 * all {@code Mandatory="Yes"}; every CT-required codelist and item carries its alias or is a marked
 * extension; 3.3 is published; every variable label matches).
 * </p>
 * <p>
 * 2026-10-03 store format 5: 0266 / DD0137 used to fire once each on LBTESTCD, because SDTMIG 3.3
 * published its label with a trailing period ("Lab Test or Examination Short Name."). That period
 * is a Library defect SDTMIG 3.4 (and 3.2) corrects; the seeder's errata register removes it
 * (metadata issue {@code #282}.2), so the example's label now matches.
 * </p>
 */
class LibraryRulesRealStoreTest
{

    private static final @Nullable Path STORE = MetadataStoreFixture.storeFile().orElse(null);

    private static final String MSG = "define-v21-msg-sdtm.xml";

    /** The 8 CDISC Define-XML 2.1 library rules (0263-B is the 2.0 leg). */
    private static final Set<String> CDISC_21 = Set.of("DEFINE-XML-0067", "DEFINE-XML-0097",
            "DEFINE-XML-0098", "DEFINE-XML-0099", "DEFINE-XML-0149", "DEFINE-XML-0263",
            "DEFINE-XML-0265", "DEFINE-XML-0266");

    /** The 5 PMDA Define-XML 2.1 library rules. */
    private static final Set<String> PMDA_21 = Set.of("PMDA-DD0116", "PMDA-DD0118", "PMDA-DD0124",
            "PMDA-DD0136", "PMDA-DD0137");

    /** (ii) Adjudicated: the MSG example with its IG name corrected, against SDTMIG 3.3. */
    private static final List<String> CORRECTED_EXPECTED = List.of(//
            "DEFINE-XML-0265 /ItemGroupDef[@OID='IG.CM']"
                    + " = Concomitant Medications vs Concomitant/Prior Medications",
            "DEFINE-XML-0265 /ItemGroupDef[@OID='IG.TS']"
                    + " = Trial Summary vs Trial Summary Information",
            "PMDA-DD0118 /ItemGroupDef[@OID='IG.RS']/ItemRef[5] = RSTESTCD: C100138 vs C96782",
            "PMDA-DD0118 /ItemGroupDef[@OID='IG.RS']/ItemRef[6] = RSTEST: C100137 vs C96781",
            "PMDA-DD0136 /ItemGroupDef[@OID='IG.CM']"
                    + " = Concomitant Medications vs Concomitant/Prior Medications",
            "PMDA-DD0136 /ItemGroupDef[@OID='IG.TS']"
                    + " = Trial Summary vs Trial Summary Information");

    @TempDir
    Path tempDir;

    private static Path store()
    {
        assumeTrue(STORE != null, "no metadata store seeded: export CDISC_PICKLE_CACHE_DIR"
                + " (the real-library oracle needs the pickle corpus)");
        return STORE;
    }


    private static Map<String, ConformanceRule> roster()
    {
        Map<String, ConformanceRule> rules = RealStoreDefineRun.corpusRules(Requires.LIBRARY,
                "2.1");
        Set<String> expected = new TreeSet<>(CDISC_21);
        expected.addAll(PMDA_21);
        assertEquals(expected, rules.keySet(), "Requires: library rules applicable at 2.1");
        assertEquals(13, rules.size(), "8 CDISC + 5 PMDA");
        return rules;
    }


    @Test
    void msgExampleAsPublished()
    {
        Path define = RealStoreDefineRun.fixture(MSG);
        Map<String, ConformanceRule> rules = roster();
        try (DefineStoreBinding binding = DefineStoreBinding.resolve(define, List.of(), store()))
        {
            LibraryProvider library = binding.libraryProvider().orElseThrow();
            // The typo is out of reach; the spelling it stands for is not.
            assertEquals(Optional.empty(), library.datasetLabel("STDTMIG", "3.3", "DM"));
            assertEquals(List.of(), library.publishedStandardVersions("STDTMIG"));
            assertEquals(Optional.of("Demographics"), library.datasetLabel("SDTMIG", "3.3", "DM"));
            // D-11: the variant resolves, so 0263's and DD0116's silence is agreement.
            assertTrue(library.publishedStandardVersions("SDTMIG-MD").contains("1.1"),
                    library.publishedStandardVersions("SDTMIG-MD").toString());
            assertEquals(Optional.of("Occurrence Indicator"),
                    library.qualifierVariableLabel("SDTMIG-MD", "1.1", "OCCUR"));
            assertEquals(Optional.of("Severity/Intensity"),
                    library.qualifierVariableLabel("SDTMIG-MD", "1.1", "SEV"));

            Map<String, RuleResult> results = RealStoreDefineRun.evaluate(define, "2.1", binding,
                    rules);
            RealStoreDefineRun.dump("msg-library-21", results);
            RealStoreDefineRun.assertAllExecuted(results);
            assertEquals(List.of(), RealStoreDefineRun.findingLines(results));
            assertFalse(binding.libraryBasis().contains("products not held"),
                    binding.libraryBasis());
        }
    }


    @Test
    void msgExampleWithItsIgNameCorrected()
    {
        Path define = RealStoreDefineRun.variant(MSG, tempDir, "Name=\"STDTMIG\"",
                "Name=\"SDTMIG\"");
        Map<String, ConformanceRule> rules = roster();
        try (DefineStoreBinding binding = DefineStoreBinding.resolve(define, List.of(), store()))
        {
            Map<String, RuleResult> results = RealStoreDefineRun.evaluate(define, "2.1", binding,
                    rules);
            RealStoreDefineRun.dump("msg-library-21-corrected", results);
            RealStoreDefineRun.assertAllExecuted(results);
            assertEquals(CORRECTED_EXPECTED, RealStoreDefineRun.findingLines(results));
            assertFalse(binding.libraryBasis().contains("products not held"),
                    binding.libraryBasis());
        }
    }
}
