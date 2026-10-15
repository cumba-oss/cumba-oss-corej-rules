package net.cumba.corej.define.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.rulespec.MetadataStoreFixture;
import net.cumba.corej.core.run.DefineStoreBinding;
import net.cumba.corej.define.conformance.eval.RuleResult;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.RuleRepository;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code DEFINE-XML-9001} against the REAL CDISC Library labels: the metadata store bound through
 * {@link DefineStoreBinding}, as a validation run binds it ({@code PLAN-define-rule-library-label}
 * row 11). The rule runs ALONE, so every product on the library basis line is its own lookup (in a
 * whole-package run {@code DEFINE-XML-0097}–{@code 0099} look up the same keys).
 *
 * <p>
 * The two fixtures ({@code send-animal-labels-20.xml}: Define-XML 2.0, SEND-IG 3.1 on
 * MetaDataVersion; {@code -21.xml}: Define-XML 2.1, SENDIG 3.1.1 through def:StandardOID) carry the
 * four SENDIG labels with "Animal" (BWDTC, BWDY, BGDTC, BGENDTC: exempt) and five texts that fire:
 * BWTESTCD worded as another variable's label (its own is "Test Short Name"), BGDY worded with the
 * SENDIG 3.0 label (3.1 and 3.1.1 publish "Study Day of Start of Interval"), the sponsor variable
 * BWANCD, a comment about "animals" and a comment repeating a label word for word. The fixed list
 * this plan removed passed BWTESTCD, BGDY and the second comment.
 * </p>
 */
class DefineXml9001RealStoreTest
{

    private static final @Nullable Path STORE = MetadataStoreFixture.storeFile().orElse(null);

    private static final String ID = "DEFINE-XML-9001";

    private static final String DEFINE_20 = "send-animal-labels-20.xml";

    private static final String DEFINE_21 = "send-animal-labels-21.xml";

    private static final String TEXT = "/Description/TranslatedText = ";

    /** The five texts that fire with the exemption, in both fixtures. */
    private static final List<String> EXPECTED = List.of(//
            ID + " /CommentDef[@OID='COM.1']" + TEXT + "All animals were fasted overnight.",
            ID + " /CommentDef[@OID='COM.2']" + TEXT + "Date/Time Animal Weighed",
            ID + " /ItemDef[@OID='IT.BG.BGDY']" + TEXT + "Study Day Animal Weighed",
            ID + " /ItemDef[@OID='IT.BW.BWANCD']" + TEXT + "Animal Weight Code",
            ID + " /ItemDef[@OID='IT.BW.BWTESTCD']" + TEXT + "Date/Time Animal Weighed");

    /** The four SENDIG labels, which fire only where the exemption has no label to read. */
    private static final List<String> IG_LABELS = List.of(//
            ID + " /ItemDef[@OID='IT.BG.BGDTC']" + TEXT + "Date/Time Animal Weighed",
            ID + " /ItemDef[@OID='IT.BG.BGENDTC']" + TEXT + "End Date/Time Animal Weighed",
            ID + " /ItemDef[@OID='IT.BW.BWDTC']" + TEXT + "Date/Time Animal Weighed",
            ID + " /ItemDef[@OID='IT.BW.BWDY']" + TEXT + "Study Day Animal Weighed");

    @TempDir
    Path tempDir;

    private static Path store()
    {
        assumeTrue(STORE != null, "no metadata store seeded: export CDISC_PICKLE_CACHE_DIR"
                + " (the real-library oracle needs the pickle corpus)");
        return STORE;
    }


    private static ConformanceRule rule()
    {
        List<ConformanceRule> matches = TestRules.load(TestRules.CDISC).stream()
                .filter(r -> ID.equals(r.ruleId())).toList();
        assertEquals(1, matches.size(), ID + ".yaml present");
        return matches.get(0);
    }


    /**
     * The planted control: the corpus rule with its {@code exemptLibraryLabel} line removed, read
     * into memory - the corpus file is never edited.
     */
    private static ConformanceRule withoutTheExemption()
    {
        try
        {
            String yaml = Files.readString(TestRules.CDISC.resolve(ID + ".yaml"),
                    StandardCharsets.UTF_8);
            String control = yaml.replace("  exemptLibraryLabel: true\n", "");
            assertNotEquals(yaml, control, "the rule carries the exemption line");
            return RuleRepository.parse(control, ID + " control");
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }


    /** The rule's finding lines; the binding is closed after the run, its basis line kept. */
    private static List<String> run(Path aDefine, String aVersion, ConformanceRule aRule,
            List<String> aBasis)
    {
        try (DefineStoreBinding binding = DefineStoreBinding.resolve(aDefine, List.of(), store()))
        {
            assertTrue(binding.libraryProvider().isPresent(), binding.libraryBasis());
            Map<String, RuleResult> results = RealStoreDefineRun.evaluate(aDefine, aVersion,
                    binding, Map.of(ID, aRule));
            RealStoreDefineRun.dump("define-xml-9001-" + aDefine.getFileName(), results);
            RealStoreDefineRun.assertAllExecuted(results);
            aBasis.add(binding.libraryBasis());
            aBasis.addAll(binding.missedProducts());
            return RealStoreDefineRun.findingLines(results);
        }
    }


    private static List<String> withIgLabels()
    {
        List<String> all = new ArrayList<>(EXPECTED);
        all.addAll(IG_LABELS);
        all.sort(null);
        return all;
    }


    @Test
    void define21ExemptsExactlyTheLabelsOfTheTextsOwnVariables()
    {
        List<String> basis = new ArrayList<>();
        assertEquals(EXPECTED, run(RealStoreDefineRun.fixture(DEFINE_21), "2.1", rule(), basis));
        assertEquals(1, basis.size(), "no product missed: " + basis);
        assertFalse(basis.get(0).contains("products not held"), basis.get(0));
    }


    @Test
    void define20ReadsTheStandardFromMetaDataVersion()
    {
        List<String> basis = new ArrayList<>();
        assertEquals(EXPECTED, run(RealStoreDefineRun.fixture(DEFINE_20), "2.0", rule(), basis));
        assertEquals(1, basis.size(), "no product missed: " + basis);
    }


    @Test
    void aDatasetWithoutDefStandardOidHasNoLabelToExempt()
    {
        // LL4 (a) pinned: no fallback to the define's single declared SENDIG
        Path define = RealStoreDefineRun.variant(DEFINE_21, tempDir, " def:StandardOID=\"STD.IG\"",
                "");
        assertEquals(withIgLabels(), run(define, "2.1", rule(), new ArrayList<>()));
    }


    @Test
    void aVersionTheStoreDoesNotHoldHasNoLabelToExemptAndIsNamed()
    {
        // LL3 (a) pinned: SENDIG 3.2 is not in the store; the IG labels fail the pattern, are
        // looked up, find nothing and fire, and the basis line names the product
        Path define = RealStoreDefineRun.variant(DEFINE_21, tempDir, "Version=\"3.1.1\"",
                "Version=\"3.2\"");
        List<String> basis = new ArrayList<>();
        assertEquals(withIgLabels(), run(define, "2.1", rule(), basis));
        assertEquals(List.of("standards/sendig/3-2"), basis.subList(1, basis.size()));
        assertTrue(basis.get(0).contains("products not held: standards/sendig/3-2"), basis.get(0));
    }


    @Test
    void anSdtmDefineLooksNothingUp()
    {
        // review M1: the rule's when: drops every node of an SDTM define, so nothing fails the
        // pattern and nothing is looked up - an eager scan would record standards/sdtmig/3-1-1,
        // which the store does not hold, on the basis line
        Path define = RealStoreDefineRun.variant(DEFINE_20, tempDir,
                "def:StandardName=\"SEND-IG\" def:StandardVersion=\"3.1\"",
                "def:StandardName=\"SDTM-IG\" def:StandardVersion=\"3.1.1\"");
        List<String> basis = new ArrayList<>();
        assertEquals(List.of(), run(define, "2.0", rule(), basis));
        assertEquals(1, basis.size(), "no product missed: " + basis);
        assertFalse(basis.get(0).contains("products not held"), basis.get(0));
    }


    @Test
    void withoutTheExemptionTheFourIgLabelsFire()
    {
        // the planted control: the same rule without exemptLibraryLabel reports the four SENDIG
        // labels of each fixture, so the green cases above are the exemption's doing
        assertEquals(withIgLabels(), run(RealStoreDefineRun.fixture(DEFINE_21), "2.1",
                withoutTheExemption(), new ArrayList<>()));
        assertEquals(withIgLabels(), run(RealStoreDefineRun.fixture(DEFINE_20), "2.0",
                withoutTheExemption(), new ArrayList<>()));
    }
}
