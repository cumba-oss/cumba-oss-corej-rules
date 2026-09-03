package net.cumba.corej.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * The total taxonomy of {@code plans/PLAN-source-proposed-block.md} §0.1: which of the five cases a
 * rule falls into, and therefore whether it owes a {@code Source_Proposed} block.
 *
 * <p>
 * <b>Total and disjoint.</b> Every rule in {@code rules-src/checks/**} lands in exactly one case.
 * Measured by run at HEAD {@code 59a74dee1} (plan §3.1): {@code AS_DESCRIBED} 3 158 + {@code SPLIT}
 * 76 + {@code MISSING_ROW} 9 + {@code CORRECTION} 25 + {@code OUT_OF_SCOPE} 536 = <b>3 804</b>, the
 * whole corpus, with all three pairwise intersections of the seeded cases empty.
 * </p>
 *
 * <p>
 * <b>This is the plan's ONE decision function.</b> Phase 1 ships it with the unit tests below;
 * phase 3 ships the corpus assertions that consume it ({@code SourceProposedGuardTest}). Splitting
 * it that way is deliberate: a classifier that is only ever exercised by the corpus scan cannot be
 * shown to be right about the edge cases, because the corpus contains whichever answer it gives.
 * </p>
 *
 * <h2>⛔ Three traps this classifier is built around, each measured in the corpus</h2>
 * <ol>
 * <li><b>A published {@code nnnnA} id is NOT a minted sibling.</b> {@code -A} (hyphen, one capital)
 * is coreJ's minted-sibling suffix; {@code A} welded straight onto the digits is an id the
 * authority itself publishes. <b>85 corpus rules</b> carry the published shape — {@code
 * FDA-SD0008C}, {@code PMDA-SD2247A}, {@code PMDA-AD0005B} … — against 54 that carry the minted
 * one. Reading the published shape as a split would put 85 rules into a seeding population they do
 * not belong to.</li>
 * <li><b>A dotted {@code CDISC-SEND-0243.1} is NOT a sibling either.</b> <b>38 corpus rules</b>
 * carry a {@code .1} tail; it is a SENDIG rule-id fragment, not a coreJ suffix. ⚠ The register
 * spells this id with a hyphen ({@code CDISC-SEND-0243-1}) and is <em>wrong</em> — the corpus
 * filename and {@code Core.Id} both use the dot.</li>
 * <li>⭐ <b>{@code Core.Status} is deliberately NOT consulted.</b> It disagrees with the {@code
 * DRAFT-} id prefix on <b>three</b> rules (plan §3.1 finding {@code P0-F3}): {@code DRAFT-110004}
 * is {@code Status: "Published"}, while {@code CDISC-AD0898} and {@code PMDA-AD0304} are
 * {@code Status: "Draft"} in sheet-bearing families <em>and both carry a verbatim {@code
 * Source}</em>. The ruled figure is <b>31</b> DRAFT rules, which is the id-prefix count (the
 * {@code Core.Status} count is 32) — so the <b>prefix is the key</b>. Classifying on status would
 * strip two rules with real sheet rows of their obligations and hand one draft an obligation it
 * cannot meet.</li>
 * </ol>
 */
public enum SourceProposedCase
{

    /** Implemented as the sheet row describes it: verbatim {@code Source}, no proposal owed. */
    AS_DESCRIBED,

    /**
     * One sheet row expressed as several rules. Each child carries the parent's verbatim row as its
     * {@code Source} and its own precised row as {@code Source_Proposed}. ⛔ <b>No sheet issue is
     * filed for a split</b> (owner ruling G, 2026-08-25) — a split is a coreJ implementation
     * detail, not an ask of the authority — so {@code Sheet_Issue} is optional here and required
     * everywhere else.
     */
    SPLIT,

    /** A minted rule in the {@code 9###} band: the catalogue publishes no row for it at all. */
    MISSING_ROW,

    /**
     * The sheet row is wrong and a register entry already carries a written correction proposal.
     */
    CORRECTION,

    /** {@code CORE} and {@code DRAFT}: no sheet exists, so no row can be proposed (ruling F). */
    OUT_OF_SCOPE;

    /**
     * The {@code correction} population: the rules a register entry already carries a written
     * {@code Correction proposal} for.
     *
     * <p>
     * ⛔ <b>Enumerated, not derived, and that is the ruled boundary</b> (open question 3, confirmed
     * 2026-08-26). Membership is decided by a human having written a proposal in the register — it
     * is not inferrable from the rule. The systematic {@code Check}-vs-{@code Source} discovery
     * hunt that would grow this population is deliberately a separate effort, so on the day this
     * plan lands a rule is a {@code CORRECTION} <em>only</em> if it is named here.
     * </p>
     *
     * <p>
     * Re-derived by run 2026-08-26: 42 non-empty {@code Correction proposal} cells − 12 reading
     * <i>"Nothing to ask"</i> / <i>"Send nothing"</i> / <i>"No sheet author exists"</i> = 30 rows
     * naming 31 distinct ids − <b>2</b> {@code CORE} (ruling F: {@code CORE-000579},
     * {@code CORE-009706}) = 29 − <b>7</b> (ruling J: {@code FDA-SD1135}, {@code FDA-SD1340},
     * {@code PMDA-SD1340}, {@code FDA-SD1029}, {@code PMDA-SD1029}, {@code FDA-SD1023},
     * {@code CDISC-SEND-0103}) = 22 + <b>3</b> found only once the extraction was widened past the
     * {@code Correction proposal} column header ({@code CDISC-AD0794}, {@code CDISC-SEND-0293},
     * {@code PMDA-SD1086}) = <b>25</b>.
     * </p>
     *
     * <p>
     * ⚠ This derivation read <i>"= 29"</i> above a {@code Set.of} holding 22, and then 25, for two
     * review cycles — the retracted pre-ruling-J arithmetic left in the <b>shipped class
     * javadoc</b>, which is the first thing the next reader trusts. Restated in full so each step
     * is checkable rather than merely arriving at the right total.
     * </p>
     */
    public static final Set<String> CORRECTION_RULES = Set.of("CDISC-AD0167", "CDISC-AD0222",
            "CDISC-AD0346", "CDISC-AD0552", "CDISC-AD0565", "CDISC-AD0566", "CDISC-AD0567",
            "CDISC-AD0568", "CDISC-AD0569", "CDISC-AD0583", "CDISC-AD0587", "CDISC-AD0749",
            "CDISC-AD0794", "CDISC-CG0348", "CDISC-CG0625", "CDISC-CG0634", "CDISC-CG0637",
            "CDISC-CG0647", "CDISC-SEND-0293", "PMDA-AD0201", "PMDA-AD0482", "PMDA-AD0483",
            "PMDA-AD0484", "PMDA-AD0485", "PMDA-SD1086");

    /** The lowest numeric tail that counts as a coreJ-minted id rather than a published one. */
    public static final int MINTED_BAND_FLOOR = 9000;

    /**
     * ⭐ The 31 minted-band rules that operationalise a <b>generic</b> published row, mapped to the
     * row each one cites (owner ruling I, 2026-08-26 — plan §5.1, reading 1).
     *
     * <p>
     * <b>Why these are splits and not missing rows.</b> Each authority publishes exactly one
     * <em>"required variables cannot be null"</em> row — FDA {@code SD0002}, PMDA {@code SD0002},
     * SDTMIG {@code CG0014}, SEND {@code SEND12} — and <b>every</b> variable these rules name is
     * {@code Core = Req}, so the requirement <em>is</em> published; it is written once,
     * generically, rather than once per variable. Measured against the four workbooks 2026-08-26.
     * </p>
     *
     * <p>
     * ⛔ <b>Filing them as "the catalogue publishes no such row" would have been 31 escalations
     * against rows that exist</b> — withdrawn ruling 6's failure at ten times the scale.
     * </p>
     *
     * <p>
     * ⚑ <b>The house had already split this very row twice.</b> {@code CDISC-CG0014-B} and
     * {@code CDISC-SEND-0012-A} are minted siblings of the same generic rules: the row's
     * {@code Rule} cell reads <i>"Variable present in dataset and ^= null"</i> — two conjuncts —
     * and the corpus expresses them as two rules. These 31 continue that pattern, narrowed to one
     * named variable so they can run <b>without the CDISC Library</b>: the generic rule is
     * {@code varname() in $required_variables and empty(value())}, and {@code required_variables}
     * is library-gated ({@code OperationInliner} wraps it in {@code library_available() and
     * available(…)}), so it SKIPs offline where these do not.
     * </p>
     *
     * <p>
     * ⚠ <b>Enumerated rather than derived, deliberately.</b> The mechanical proxy that would derive
     * it — "is in the band and cites an id other than its own" — cannot be written honestly for the
     * SEND family, whose corpus ids ({@code CDISC-SEND-0012}) and sheet ids ({@code SEND12}) do not
     * correspond by prefix. A list that a reviewer can check beats a heuristic that looks general
     * and is not.
     * </p>
     */
    public static final java.util.Map<String, String> GENERIC_ROW_SPLITS = java.util.Map.ofEntries(
            java.util.Map.entry("FDA-SD9701", "SD0002"),
            java.util.Map.entry("FDA-SD9702", "SD0002"),
            java.util.Map.entry("FDA-SD9703", "SD0002"),
            java.util.Map.entry("FDA-SD9704", "SD0002"),
            java.util.Map.entry("FDA-SD9705", "SD0002"),
            java.util.Map.entry("FDA-SD9707", "SD0002"),
            java.util.Map.entry("FDA-SD9708", "SD0002"),
            java.util.Map.entry("FDA-SD9709", "SD0002"),
            java.util.Map.entry("FDA-SD9710", "SD0002"),
            java.util.Map.entry("FDA-SD9711", "SD0002"),
            java.util.Map.entry("FDA-SD9712", "SD0002"),
            java.util.Map.entry("FDA-SE9701", "SD0002"),
            java.util.Map.entry("FDA-SE9702", "SD0002"),
            java.util.Map.entry("PMDA-SD9701", "SD0002"),
            java.util.Map.entry("PMDA-SD9702", "SD0002"),
            java.util.Map.entry("PMDA-SD9703", "SD0002"),
            java.util.Map.entry("PMDA-SD9704", "SD0002"),
            java.util.Map.entry("PMDA-SD9705", "SD0002"),
            java.util.Map.entry("PMDA-SD9707", "SD0002"),
            java.util.Map.entry("PMDA-SD9708", "SD0002"),
            java.util.Map.entry("PMDA-SD9709", "SD0002"),
            java.util.Map.entry("PMDA-SD9710", "SD0002"),
            java.util.Map.entry("PMDA-SD9711", "SD0002"),
            java.util.Map.entry("PMDA-SD9712", "SD0002"),
            java.util.Map.entry("CDISC-CG9670", "CG0014"),
            java.util.Map.entry("CDISC-SEND-9430", "SEND12"),
            java.util.Map.entry("CDISC-SEND-9431", "SEND12"),
            java.util.Map.entry("CDISC-SEND-9433", "SEND12"),
            java.util.Map.entry("CDISC-SEND-9434", "SEND12"),
            java.util.Map.entry("CDISC-SEND-9435", "SEND12"),
            java.util.Map.entry("CDISC-SEND-9436", "SEND12"));

    /**
     * The nine rules for which the catalogue genuinely publishes nothing — all of them
     * <b>dataset-</b> or <b>record-presence</b> rules, not variable-population ones.
     *
     * <p>
     * Evidence, by run over {@code SDTMIG Conformance Rules v2.0} (1 315 rows): the sheet publishes
     * dataset-presence rows for <b>MB</b> ({@code CG0191}), <b>PC</b> ({@code CG0318}), <b>DM</b>
     * ({@code CG0368}), <b>SUPP--</b> ({@code CG0373}), <b>RELREC</b> ({@code CG0374}) and
     * <b>TM</b> ({@code CG0501}/{@code CG0502}) — and <b>none for TS, EX or TV</b>. Its
     * TV-referencing rows ({@code CG0031}/{@code CG0032}/{@code CG0033},
     * {@code CG0213}/{@code CG0215}/{@code CG0655}/ {@code CG0657}) all <em>presuppose</em> TV
     * rather than requiring it. SEND publishes no dataset-presence rule at all, for any domain
     * ({@code #101}).
     * </p>
     */
    public static final Set<String> MISSING_ROW_RULES = Set.of("FDA-SD9713", "PMDA-SD9713",
            "CDISC-CG9671", "CDISC-CG9667", "CDISC-CG9668", "CDISC-SEND-9423", "CDISC-SEND-9424",
            "CDISC-SEND-9425", "CDISC-AD9701");

    /**
     * Classifies one rule. {@code rule} is the parsed {@code rules-src} node; {@code id} is its
     * {@code Core.Id}, which is identical to the filename stem on all 3 804 corpus files (verified
     * by run — zero differ, zero repeat).
     *
     * @param rule
     *            the parsed rule node, read only for the presence of a {@code Source} block
     * @param id
     *            the rule's {@code Core.Id}
     * @return the one case it falls into
     */
    public static SourceProposedCase classify(JsonNode rule, String id)
    {
        if (isOutOfScope(id))
        {
            return OUT_OF_SCOPE;
        }
        if (isMintedSibling(id))
        {
            return SPLIT;
        }
        // ⭐ A minted-band rule that operationalises a GENERIC published row is a SPLIT, not a
        // missing row (owner ruling I, 2026-08-26). Checked before the band test, because the two
        // populations are both "9### with no Source today" and only the cited row tells them apart.
        if (GENERIC_ROW_SPLITS.containsKey(id))
        {
            return SPLIT;
        }
        // MISSING_ROW is the minted band MINUS the three ids whose rows the authority does publish
        // (FDA-SD9999, PMDA-SD9999, PMDA-AD9999 — owner ruling A, 2026-08-25, which WITHDREW the
        // ruling that had called their Source blocks fabricated) and MINUS the 31 above. Carrying a
        // Source is what excludes the three, so that test is never a name list.
        if (numericTail(id) >= MINTED_BAND_FLOOR && !rule.hasNonNull("Source"))
        {
            return MISSING_ROW;
        }
        if (CORRECTION_RULES.contains(id))
        {
            return CORRECTION;
        }
        return AS_DESCRIBED;
    }


    /**
     * {@code CORE} and {@code DRAFT} publish no sheet. ⚠ By id prefix — see trap 3 in the class
     * doc.
     */
    public static boolean isOutOfScope(String id)
    {
        return id.startsWith("CORE-") || id.startsWith("DRAFT-");
    }


    /**
     * A coreJ-minted sibling: {@code -} followed by exactly one capital at the end of the id.
     *
     * <p>
     * ⚠ The hyphen is the whole distinction (trap 1). {@code PMDA-SD2247A} is published by PMDA and
     * is not a sibling; {@code FDA-SD2247-A} is minted here and is.
     * </p>
     */
    public static boolean isMintedSibling(String id)
    {
        int n = id.length();
        return n >= 3 && id.charAt(n - 2) == '-' && id.charAt(n - 1) >= 'A'
                && id.charAt(n - 1) <= 'Z';
    }


    /**
     * The row a split was split out of: the parent rule's {@code Core.Id} for a minted {@code -A}
     * sibling, and the cited <b>sheet</b> id for one of the 31 generic-row splits. {@code null} for
     * every other id.
     */
    public static String parentOf(String id)
    {
        if (isMintedSibling(id))
        {
            return id.substring(0, id.length() - 2);
        }
        return GENERIC_ROW_SPLITS.get(id);
    }


    /**
     * The id's numeric tail, or {@code -1} when it does not end in one.
     *
     * <p>
     * Reads back from the end over the sibling suffix ({@code -A}) and a published letter suffix
     * ({@code C} in {@code FDA-SD0008C}), then over the digits. ⚠ A dotted {@code
     * CDISC-SEND-0243.1} therefore yields {@code 1}, not {@code 243} — harmless, because the only
     * question asked of this value is whether it reaches {@link #MINTED_BAND_FLOOR}, and {@code 1}
     * does not. Trap 2.
     * </p>
     */
    public static int numericTail(String id)
    {
        int end = id.length();
        if (isMintedSibling(id))
        {
            end -= 2;
        }
        if (end > 0 && id.charAt(end - 1) >= 'A' && id.charAt(end - 1) <= 'Z')
        {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isDigit(id.charAt(start - 1)))
        {
            start--;
        }
        return start == end ? -1 : Integer.parseInt(id.substring(start, end));
    }
}
