package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Corpus guard for the domain-scope exact-match contract. The SEND {@code RE} (Respiratory) domain
 * is a proper prefix of the RELREC / RELSUB / RELSPEC / RELREF relationship datasets, and SDTM
 * {@code SU} (Substance Use) is a proper prefix of every {@code SUPPxx}. Under the retired Fix #38
 * prefix semantics the rules below were selected for — and CDISC-SEND-0289(-1), CDISC-SEND-0338 and
 * FDA-SE2306 fired on — datasets they were never scoped to. These assertions read the shipped rule
 * bodies so a future scope-matcher change cannot silently reintroduce the false findings.
 *
 * <p>
 * Lives in {@code corej-rules} rather than next to {@link ScopeMatcher} in {@code corej-core}: the
 * rule corpus ships in this module, and the dependency runs rules -&gt; core, so core cannot reach
 * it.
 * </p>
 *
 * <h2>⭐ The base is DERIVED, never handed in (review round 2, R2-3)</h2>
 *
 * <p>
 * Every call below passes {@link #engineBase(String)}, which builds the dataset and runs
 * {@link OperationExecutor#unsplitNameFromData} on it — the same expression
 * {@code DatasetRuleResolver} evaluates before calling the three-argument describer. The first
 * re-point of this guard passed the dataset name as its <em>own</em> unsplit base
 * ({@code describeDomainMismatch(rule, domain, domain)}), which hard-codes {@code isSplit = false}
 * and so reached <em>less</em> of {@code ScopeMatcher} than the two-argument API it replaced: the
 * two-argument form derives the base from the name and therefore did run the split legs for
 * {@code SUPPQUAL}. Supplying the answer to the question under test is not a re-point.
 * </p>
 *
 * <p>
 * ⚠ The premise <i>"none of these domains is a split"</i> is false. {@code SUPPQUAL} is a split on
 * <b>both</b> derivations and they disagree on the base — the name heuristic
 * ({@code SplitDatasetUtil.unsplitName}) reads it as a SUPP letter split and answers
 * {@code SUPPQUA}; the data answers {@code SUPP} + row-0 {@code RDOMAIN}. That disagreement is the
 * defect class this guard exists for, so the data-derived answer is the one asserted.
 * </p>
 *
 * <p>
 * The two {@code splitReTest*} tests below are the non-vacuity controls: each pins the verdict
 * <em>both</em> ways round, so the split re-test legs of {@code ScopeMatcher}
 * ({@code matchedInclude}'s second operand, and the {@code excludeEntry == null && isSplit} branch)
 * are proven to execute by the verdict CHANGING when the derived base is withheld. A test that only
 * asserts today's values cannot tell the two code paths apart, which is exactly how the first
 * re-point lost coverage with nothing red.
 * </p>
 *
 * <p>
 * ⚑ {@code matchesDomain} was at first kept, against L3's proposal to delete it, as a one-line
 * delegate for callers that have only a name. It was retired from {@code src/main} on 2026-09-25
 * ({@code PLAN-retire-dead-multi-match-lookup} U1): no production path called it. The engine tests
 * that read through it now use the test-side forwarder {@code ScopeMatcherCalls}; this class calls
 * the production {@code describeDomainMismatch} directly.
 * </p>
 */
class ScopeMatcherShippedRuleScopeTest
{

    /**
     * Row-0 {@code DOMAIN} value of each fixture that carries the column. A domain dataset and its
     * digit splits share one value — that is what makes {@code RE1} a split of {@code RE}.
     */
    private static final Map<String, String> DOMAIN_COLUMN = Map.of("RE", "RE", "RE1", "RE", "SU",
            "SU", "SV1", "SV");

    /**
     * Row-0 {@code RDOMAIN} value of each fixture that carries the column. {@code SUPPQUAL} is the
     * general supplemental-qualifiers dataset: its parent is named per ROW, so row 0 decides the
     * canonical base exactly as it does in the engine.
     */
    private static final Map<String, String> RDOMAIN_COLUMN = Map.of("SUPPAE", "AE", "SUPPQUAL",
            "AE", "RELREC", "AE");

    @ParameterizedTest
    @CsvSource(
    {
            "rules-cdisc-sendig-3-1-1.json, CDISC-SEND-0338, RE",
            "rules-cdisc-sendig-3-1-1.json, CDISC-SEND-0289.1, RE",
            "rules-cdisc-sendig-3-1.json,   CDISC-SEND-0289,   RE",
            "rules-fda-sendig-3-0.json,     FDA-SE2306,        RE",
            // Re-pointed 2026-09-19 (PLAN-retire-core-family phase 6): CORE-000792 -> its
            // phase-4 mint FDA-SD9737. Verified, not assumed: measured over the whole authored
            // corpus, FDA-SD9737 is the ONLY surviving rule whose Domains.Include contains SU,
            // and it carries CORE-000792's scope ["AG","CM","SU"] and Check
            // (not is_unique_relationship(--CLAS, --CLASCD)) verbatim, in sdtmig 3.2/3.3/3.4.
            // ⇒ the SU case this row exists for is preserved, not dropped.
            "rules-fda-sdtmig-3-4.json,     FDA-SD9737,        SU"
    })
    void shippedRule_selectsOnlyItsOwnDomain(String pkgFile, String coreId, String domain)
        throws IOException
    {
        Rule rule = load(pkgFile, coreId);
        assertNull(ScopeMatcher.describeDomainMismatch(rule, domain, engineBase(domain)),
                coreId + " must still apply to " + domain);
        for (String other : new String[]
        {
                "RELREC", "RELSUB", "RELSPEC", "RELREF", "SUPPAE", "SUPPQUAL"
        })
        {
            if (other.equals(domain))
            {
                continue;
            }
            assertNotNull(ScopeMatcher.describeDomainMismatch(rule, other, engineBase(other)),
                    coreId + " must NOT be selected for " + other);
        }
    }


    @Test
    void relrecIsNotASplitOfRE() throws IOException
    {
        // The unsplit-name re-test must not resurrect the collision either: RELREC carries no
        // DOMAIN=RE, so its canonical base is itself.
        Rule rule = load("rules-cdisc-sendig-3-1-1.json", "CDISC-SEND-0338");
        assertEquals("RELREC", engineBase("RELREC"), "RELREC must not canonicalise to RE");
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "RELREC", engineBase("RELREC")));
    }


    /**
     * {@code SUPPQUAL} IS a split on the engine's path, and the two derivations disagree about its
     * base. Pinning the data-derived answer here is what keeps the roster above running the split
     * legs rather than the {@code isSplit = false} shortcut.
     */
    @Test
    void suppqualIsASplitOnTheEnginePath()
    {
        assertEquals("SUPPAE", engineBase("SUPPQUAL"),
                "the data-derived base is SUPP + row-0 RDOMAIN");
        assertNotEquals("SUPPQUAL", engineBase("SUPPQUAL"), "so SUPPQUAL is a split dataset");
        assertEquals("SUPPQUA", SplitDatasetUtil.unsplitName("SUPPQUAL"),
                "and the NAME heuristic answers something else again — the disagreement is the "
                        + "reason this guard must ask on the data-derived path");
        // SUPPAE is not a split on either derivation: RDOMAIN=AE reproduces the name.
        assertEquals("SUPPAE", engineBase("SUPPAE"));
    }


    /**
     * Non-vacuity control for the Include split re-test. {@code RE1}'s NAME is not in
     * {@code Include: [RE]}; only its data-derived base is. So a matching verdict is reachable ONLY
     * through the split leg, and withholding the base flips it.
     */
    @Test
    void splitReTestRunsOnInclude() throws IOException
    {
        Rule rule = load("rules-cdisc-sendig-3-1-1.json", "CDISC-SEND-0338");
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "RE1", "RE1"),
                "control: with the base withheld, RE1 must NOT be selected");
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "RE1", engineBase("RE1")),
                "with the data-derived base RE, the Include split re-test selects RE1");
    }


    /**
     * Non-vacuity control for the Exclude split re-test. {@code CDISC-CG0034} carries
     * {@code Exclude: [SV, TV]} and no Include, so {@code SV1} is excluded only when the split
     * re-test matches its base.
     */
    @Test
    void splitReTestRunsOnExclude() throws IOException
    {
        Rule rule = load("rules-cdisc-sdtmig-3-2.json", "CDISC-CG0034");
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "SV1", "SV1"),
                "control: with the base withheld, SV1 is not excluded");
        String mismatch = ScopeMatcher.describeDomainMismatch(rule, "SV1", engineBase("SV1"));
        assertNotNull(mismatch, "with the data-derived base SV, the Exclude split re-test fires");
        assertTrue(mismatch.contains("Exclude"),
                "and the reason names the Exclude entry: " + mismatch);
    }


    /**
     * The base, derived the way {@code DatasetRuleResolver} derives it.
     *
     * @param datasetName
     *            the dataset's member name
     * @return {@link OperationExecutor#unsplitNameFromData} of the corresponding fixture
     */
    private static String engineBase(String datasetName)
    {
        return OperationExecutor.unsplitNameFromData(dataset(datasetName));
    }


    private static IDataTable dataset(String datasetName)
    {
        MockTable mt = MockTable.of().name(datasetName).caseInsensitiveColumnNames();
        mt.col("USUBJID", "S1");
        String domain = DOMAIN_COLUMN.get(datasetName);
        if (domain != null)
        {
            mt.col("DOMAIN", domain);
        }
        String rdomain = RDOMAIN_COLUMN.get(datasetName);
        if (rdomain != null)
        {
            mt.col("RDOMAIN", rdomain);
        }
        return mt.build();
    }


    private static Rule load(String pkgFile, String coreId) throws IOException
    {
        RulePackage pkg = RulePackageLoader.load(CorpusPaths.rules(pkgFile));
        Map<String, Rule> rules = pkg.getRules();
        assertNotNull(rules, pkgFile + " has no rules");
        Rule rule = rules.get(coreId);
        assertNotNull(rule, coreId + " not found in " + pkgFile);
        return rule;
    }
}
