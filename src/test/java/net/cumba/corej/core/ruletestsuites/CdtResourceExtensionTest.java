package net.cumba.corej.core.ruletestsuites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.cumba.datatable.impl.support.OverlayDataTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for {@link CdtResource} + {@link CdtResourceExtension}.
 *
 * <p>
 * These tests verify the annotation-driven parameter injection. They use existing {@code .cdt}
 * files from the shipped CDISC-family scenario corpus (CDISC-AD0117) as test fixtures.
 * </p>
 */
@ExtendWith(CdtResourceExtension.class)
class CdtResourceExtensionTest
{

    @Test
    void parameterAnnotation_loadsResource(
            @CdtResource("CDISC-AD0117/CDISC-AD0117-valid-ADLBC.cdt") OverlayDataTable table)
    {
        assertNotNull(table, "table should be injected");
        assertEquals("ADLBC", table.getMetaData().getName());
        assertEquals(3, table.getRowCount());
    }


    @Test
    @CdtResource("CDISC-AD0117/CDISC-AD0117-invalid-ADLBC.cdt")
    void methodAnnotation_loadsResource(OverlayDataTable table)
    {
        assertNotNull(table, "table should be injected");
        assertEquals("ADLBC", table.getMetaData().getName());
        assertEquals(3, table.getRowCount());
    }


    @Test
    void multipleParameters_eachWithOwnResource(
            @CdtResource("CDISC-AD0117/CDISC-AD0117-valid-ADLBC.cdt") OverlayDataTable validTable,
            @CdtResource("CDISC-AD0117/CDISC-AD0117-invalid-ADLBC.cdt") OverlayDataTable invalidTable)
    {
        assertNotNull(validTable);
        assertNotNull(invalidTable);
        assertEquals(3, validTable.getRowCount());
        assertEquals(3, invalidTable.getRowCount());

        // Both tables should have the same schema but distinct data
        assertEquals("ADLBC", validTable.getMetaData().getName());
        assertEquals("ADLBC", invalidTable.getMetaData().getName());

        // Invalid table has "Different Label" on row 1 col 2 (ATPT)
        assertEquals("Different Label", invalidTable.getValue(1, 2));
        // Valid table has "Baseline" there
        assertEquals("Baseline", validTable.getValue(0, 2));
    }


    @Test
    @CdtResource(value = "CDISC-AD0117/CDISC-AD0117-valid-ADLBC.cdt", base = "cdisc")
    void explicitBaseAttribute(OverlayDataTable table)
    {
        assertNotNull(table);
        assertEquals("ADLBC", table.getMetaData().getName());
    }
}
