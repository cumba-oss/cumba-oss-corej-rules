package net.cumba.corej.core.ruletestsuites;

import java.io.IOException;
import net.cumba.corej.ruletest.cdt.CdtLoader;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit 5 extension that resolves test method parameters of type {@link OverlayDataTable} annotated
 * with {@link CdtResource} by loading the referenced {@code .cdt} file from the classpath.
 *
 * <p>
 * Usage — annotate a parameter with {@code @CdtResource}:
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
 * Multiple parameters can each carry their own annotation:
 * </p>
 *
 * <pre>
 *
 * &#64;Test
 * void cross_dataset_test(
 *         &#64;CdtResource("CDISC-AD0204/CDISC-AD0204-invalid-ADSL.cdt") OverlayDataTable adsl,
 *         &#64;CdtResource("CDISC-AD0204/CDISC-AD0204-invalid-DM.cdt") OverlayDataTable dm)
 * {
 *     // ...
 * }
 * </pre>
 */
public class CdtResourceExtension implements ParameterResolver
{

    @Override
    public boolean supportsParameter(ParameterContext aParameterContext,
            ExtensionContext aExtensionContext)
        throws ParameterResolutionException
    {
        if (aParameterContext.getParameter().getType() != OverlayDataTable.class)
        {
            return false;
        }
        // Parameter-level annotation, else method-level (single-parameter convenience case)
        return aParameterContext.isAnnotated(CdtResource.class) || aExtensionContext.getTestMethod()
                .filter(m -> m.isAnnotationPresent(CdtResource.class)).isPresent();
    }


    @Override
    public Object resolveParameter(ParameterContext aParameterContext,
            ExtensionContext aExtensionContext)
        throws ParameterResolutionException
    {
        CdtResource annotation = aParameterContext.findAnnotation(CdtResource.class)
                .orElseGet(() -> aExtensionContext.getTestMethod()
                        .map(m -> m.getAnnotation(CdtResource.class))
                        .orElseThrow(() -> new ParameterResolutionException(
                                "Parameter of type OverlayDataTable has no @CdtResource annotation "
                                        + "(neither on the parameter nor on the test method)")));

        String fullPath = "net/cumba/corej/core/ruletestsuites/" + annotation.base() + "/"
                + annotation.value();
        try
        {
            return CdtLoader.loadResource(fullPath);
        }
        catch (IOException e)
        {
            throw new ParameterResolutionException("Failed to load CDT resource: " + fullPath, e);
        }
    }
}
