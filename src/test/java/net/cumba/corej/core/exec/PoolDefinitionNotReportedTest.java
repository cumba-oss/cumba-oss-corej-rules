package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.RulePackageManifest;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.StandardRef;
import org.junit.jupiter.api.Test;

/**
 * The precondition under which the four pool-reading SEND rules keep running on the common study
 * without POOLDEF ({@code PLAN-scalar-date-extremes} §2 / §5.3): {@code CDISC-SEND-0204} /
 * {@code -0205} / {@code -0305} / {@code -0306} read {@code domain="POOLDEF"} for a subject's
 * pooled doses, and {@link AbsentDatasetSkip} would rewrite those reads to <i>false</i> (K5b) on
 * every study without POOLDEF — silencing the four rules there — the moment a shipped package of
 * the same run carried a rule whose <b>whole</b> Check is a bare presence test on POOLDEF
 * ({@code not ds_exists("POOLDEF")}). No shipped rule tests POOLDEF that way today (the Checks
 * naming it are membership checks); this test keeps it so, for every package of every family whose
 * primary standard is SEND — not only the {@code sendig}-named ones, since the DART, AR and GENETOX
 * packages carry the rules too.
 *
 * <p>
 * ⚠ <b>One exclusion, deliberate:</b> the DRAFT TIG package ({@code rules-draft-tig-1-0}) lists
 * {@code tig/1-0/send} among its primary standards but is <b>not</b> read — the predicate is a
 * {@code sendig*} primary standard, and TIG's is {@code tig/…}. It is safe to leave out: its seven
 * rules are DRAFT (generated and tested, never released), none of the four pool readers is among
 * them, and none reads or tests POOLDEF (measured 2026-09-30 over {@code target/rules}). A TIG
 * package that ever ships SEND rules re-opens this; the population ratchet below stays 18.
 * </p>
 *
 * <p>
 * Two guards keep the assertion from passing vacuously: a <b>positive control</b> — a synthetic
 * package holding one bare-presence rule on POOLDEF must put POOLDEF into
 * {@link AbsentDatasetSkip#reportedDatasets} (else the instrument is blind and the corpus assertion
 * says nothing) — and an <b>exact</b> population ratchet on the number of SEND packages read (the
 * house ratchet: a manifest that stopped listing a family would otherwise shrink the population
 * silently).
 * </p>
 */
class PoolDefinitionNotReportedTest
{

    /** The rules that read POOLDEF for a subject's pooled doses — named in the failure. */
    private static final List<String> POOL_READERS = List.of("CDISC-SEND-0204", "CDISC-SEND-0205",
            "CDISC-SEND-0305", "CDISC-SEND-0306");

    /**
     * ⛔ Exact, not a floor: the SEND-standard packages of {@code target/rules} — CDISC 6 (sendig
     * 3.0 / 3.1 / 3.1.1, sendig-dart 1.1 / 1.2, sendig-genetox 1.0), FDA 5 (sendig 3.0 / 3.1 /
     * 3.1.1, sendig-ar 1.0, sendig-dart 1.1) and DRAFT 7 (one per SEND standard version). A new
     * SEND package moves this by one, with the reason in the same commit.
     */
    private static final int EXPECTED_SEND_PACKAGES = 18;

    private static boolean isSend(RulePackageManifest.Entry entry)
    {
        for (StandardRef ref : entry.standards())
        {
            if (ref.role() == StandardRef.Role.PRIMARY && ref.id().startsWith("sendig"))
            {
                return true;
            }
        }
        return false;
    }


    @Test
    void noShippedSendPackageCarriesABarePresenceRuleOnPooldef() throws IOException
    {
        List<String> read = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (RulePackageManifest.Entry entry : RulePackageManifest.load(CorpusPaths.rules())
                .packages())
        {
            if (!isSend(entry))
            {
                continue;
            }
            RulePackage pkg = RulePackageLoader.load(CorpusPaths.rules(entry.file()));
            read.add(entry.file());
            Set<String> reported = AbsentDatasetSkip.reportedDatasets(pkg.getRules().values());
            if (reported.contains("POOLDEF"))
            {
                offenders.add(entry.file() + " " + reported);
            }
        }
        assertEquals(List.of(), offenders, "a shipped SEND package carries a rule whose whole Check"
                + " is a bare presence test on POOLDEF. With such a rule in the run,"
                + " AbsentDatasetSkip (K5b) rewrites every domain=\"POOLDEF\" read to false on a"
                + " study without POOLDEF — which SKIPs " + POOL_READERS + " there instead of"
                + " judging the subjects' own doses. Either drop the presence rule, or re-open"
                + " the four rules' pool reading with that suppression in mind");
        assertEquals(EXPECTED_SEND_PACKAGES, read.size(),
                "the number of SEND-standard packages read MOVED (the population ratchet):"
                        + " re-derive it in the same commit. Read: " + read);
    }


    @Test
    void theInstrumentSeesABarePresenceRuleOnPooldef() throws IOException
    {
        // The positive control: a package whose one rule's whole Check is the presence test, in
        // the shipped shape of every bare presence rule (study-wide, unrestricted scope).
        RulePackage pkg = RulePackageLoader.loadFromString("""
                {"rules": {"CTL-1": {
                  "Core": {"Id": "CTL-1"},
                  "Sensitivity": "Study",
                  "Scope": {"Domains": {"Include": ["ALL"]}},
                  "Check": {"expression": "not ds_exists(\\"POOLDEF\\")"},
                  "Outcome": {"Message": "POOLDEF is absent"}
                }}}
                """);
        assertEquals(null, pkg.getRules().get("CTL-1").getLoadError(),
                "the positive control must load clean, or it proves nothing");
        Set<String> reported = AbsentDatasetSkip.reportedDatasets(pkg.getRules().values());
        assertTrue(reported.contains("POOLDEF"),
                "the instrument must see a bare presence rule on POOLDEF; it reported: " + reported
                        + (pkg.getRules().get("CTL-1").getLoadError() != null ? " (load error: "
                                + pkg.getRules().get("CTL-1").getLoadError() + ")" : ""));
        // …and a membership-shaped Check naming POOLDEF (the shipped shape) is NOT one.
        RulePackage membership = RulePackageLoader.loadFromString("""
                {"rules": {"CTL-2": {
                  "Core": {"Id": "CTL-2"},
                  "Bindings": [{"name": "$pooldef_poolid",
                                "expression": "distinct(POOLID, domain=\\"POOLDEF\\")"}],
                  "Check": {"expression": "not empty(POOLID) and POOLID not in $pooldef_poolid"},
                  "Outcome": {"Message": "m", "Output_Variables": ["POOLID"]}
                }}}
                """);
        Set<String> notReported = new TreeSet<>(
                AbsentDatasetSkip.reportedDatasets(membership.getRules().values()));
        assertFalse(notReported.contains("POOLDEF"), "a membership check is not a presence rule");
    }
}
