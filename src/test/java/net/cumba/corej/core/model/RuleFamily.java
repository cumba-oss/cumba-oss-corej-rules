package net.cumba.corej.core.model;

import java.util.Locale;

/**
 * The "family" of a rule — the leading alphabetic run of its {@code Core.Id} (e.g.
 * {@code CORE-000351 -> CORE}, {@code FDA-SD0001 -> FDA}, {@code PMDA-AD0001 -> PMDA}, and
 * {@code CDISC-CG0001} / {@code CDISC-SEND-0001} / {@code CDISC-AD0059} all -> {@code CDISC} — the
 * CDISC family spans SDTM, SEND, and ADaM). The family is the partition axis for the shipped
 * per-family rule packages ({@code rules-<family>-<standard>-<version>.json}).
 *
 * <p>
 * This is the shared definition used by the offline package assembler and the {@code rules-src/}
 * splitter, so a package's folder name, its filename stem and {@code family(Core.Id)} agree by
 * construction.
 * </p>
 *
 * <p>
 * <b>The engine does not derive the family.</b> {@code net.cumba.corej.core.RulePackageManifest}
 * reads {@code family} as a plain string field off {@code packages.json} and never calls this
 * method. The agreement above is therefore enforced on the <em>generator</em> side only — it is a
 * property of how the corpus is written, not something the reader re-checks.
 * </p>
 *
 * <p>
 * Corrected 2026-09-01 (PLAN-rules-module-consolidation D15): this paragraph previously named "the
 * {@code RulePackageManifest} readers" as a second deriver and called the agreement "guaranteed".
 * The engine half of that was never true — {@code corej-cdisc-core} contains no reference to this
 * class.
 * </p>
 */
public final class RuleFamily
{

    private RuleFamily()
    {
    }


    /**
     * The family token of a {@code Core.Id}: its leading run of ASCII letters, or {@code "OTHER"}
     * when the id does not start with a letter.
     *
     * @param coreId
     *            the rule's {@code Core.Id} (e.g. {@code CORE-000351})
     * @return the family token (e.g. {@code CORE}), never {@code null}
     */
    public static String of(String coreId)
    {
        int i = 0;
        while (i < coreId.length() && Character.isLetter(coreId.charAt(i)))
        {
            i++;
        }
        return i == 0 ? "OTHER" : coreId.substring(0, i);
    }


    /**
     * The lower-cased family token used in package file names ({@code CORE -> core}).
     *
     * @param coreId
     *            the rule's {@code Core.Id}
     * @return the lower-cased family token
     */
    public static String fileToken(String coreId)
    {
        return of(coreId).toLowerCase(Locale.ROOT);
    }
}
