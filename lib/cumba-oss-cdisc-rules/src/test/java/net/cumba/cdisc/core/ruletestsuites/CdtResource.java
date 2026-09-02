package net.cumba.cdisc.core.ruletestsuites;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Declarative binding of a <code>.cdt</code> file to a test method.
 *
 * <p>
 * Use this annotation on a test method to automatically load a {@code .cdt} file into a
 * {@link OverlayDataTable} parameter, eliminating the string-path-based helper call:
 * </p>
 *
 * <pre>
 *
 * &#64;Test
 * &#64;CdtResource("CDISC-AD0117/CDISC-AD0117-invalid-ADLBC.cdt")
 * void CDISC_AD0117_invalid(OverlayDataTable table)
 * {
 *     assertViolation("CDISC-AD0117", table, "ADLBC");
 * }
 * </pre>
 *
 * <p>
 * Multiple resources can be loaded by declaring additional {@link OverlayDataTable} parameters,
 * with one {@code @CdtResource} annotation per parameter:
 * </p>
 *
 * <pre>
 *
 * &#64;Test
 * void CDISC_AD0204_invalid(
 *         &#64;CdtResource("CDISC-AD0204/CDISC-AD0204-invalid-ADSL.cdt") OverlayDataTable adsl,
 *         &#64;CdtResource("CDISC-AD0204/CDISC-AD0204-invalid-DM.cdt") OverlayDataTable dm)
 * {
 *     // ... use adsl and dm
 * }
 * </pre>
 *
 * <p>
 * The resource path is resolved relative to the base path provided by the annotation's {@code base}
 * attribute (default: {@code cdisc}). The full classpath location is
 * {@code net/cumba/cdisc/core/ruletestsuites/<base>/<value>} &mdash; i.e. {@code base} names the
 * rule <em>family</em> directory of the scenario corpus.
 * </p>
 *
 * <p>
 * This annotation requires {@link CdtResourceExtension} to be registered on the test class (or
 * inherited from a base class). Any test class that extends {@code AbstractAdamRuleTest} will have
 * it available automatically.
 * </p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(
{
        ElementType.METHOD, ElementType.PARAMETER
})
@ExtendWith(CdtResourceExtension.class)
public @interface CdtResource
{

    /**
     * The <code>.cdt</code> file path, relative to the base directory (which is {@link #base()}).
     */
    String value();


    /**
     * The rule-family directory under {@code net/cumba/cdisc/core/ruletestsuites/} where the
     * resource lives. Default: {@code cdisc}.
     *
     * <p>
     * The shipped families are {@code cdisc}, {@code core}, {@code fda}, {@code pmda} and
     * {@code draft}.
     * </p>
     */
    String base() default "cdisc";
}
