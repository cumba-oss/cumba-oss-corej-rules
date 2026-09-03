package net.cumba.corej.core.ruletestsuites.adam;

import java.util.Map;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for ADaM Variable Metadata Check rules.
 *
 * <p>
 * All 254 scenarios migrated to extended-CDT scenarios via the sibling-aware capture. Only
 * CDISC-AD0640 stays in Java because its columns include a name with {@code =}
 * ({@code SUPPAE.QNAM=AETRTEM}) that {@code CdtParser} currently rejects (key=value token in a
 * column line). Fixing the parser or the capture would recover both methods.
 * </p>
 *
 * <p>
 * Since the cross-dataset reference {@code SUPPAE.QNAM=AETRTEM} now resolves via the
 * {@code DatasetResolver} (per the dotted exists/not_exists support added in commit 60c36e0a), the
 * tests register a real {@code SUPPAE} table with the resolver instead of relying on a literal
 * column name.
 * </p>
 */
class AdamVarMetadataRuleTest extends AbstractAdamRuleTest // NOSONAR S3577 —
                                                           // Underscore-categorised name is the
                                                           // suite convention.
{

    private static IDataTable suppaeWithAetrtem()
    {
        OverlayDataTable suppae = OverlayDataTable.empty("SUPPAE", "SUPPAE", 1);
        suppae.addColumn("USUBJID");
        suppae.addColumn("QNAM");
        suppae.addColumn("QVAL");
        suppae.setValue(0, "USUBJID", "S01");
        suppae.setValue(0, "QNAM", "AETRTEM");
        suppae.setValue(0, "QVAL", "Y");
        return suppae;
    }


    /**
     * {@code AETERM} carries the scope signal, not the check: CDISC-AD0640 is scoped to
     * {@code Scope.Data_Structures OCCURRENCE DATA STRUCTURE} + {@code Scope.Subclasses ADVERSE
     * EVENT}, both of which are detected from the dataset's columns
     * ({@code AdamDataStructureDetector} / {@code AdamSubclassDetector}). Without it these tables
     * detect as {@code ADAM OTHER} and the rule is skipped — silently vacuous for the
     * {@code _valid} case and a hard failure for {@code _invalid}. {@code AETERM} is deliberate:
     * the rule's check is on {@code AETRTEM}, so the signal column cannot perturb the verdict.
     */
    @Test
    void CDISC_AD0640_valid()
    {
        var table = virtualTable("ADAE", 1, "USUBJID", "AESEQ", "AETERM", "AETRTEM");
        Map<String, IDataTable> overrides = Map.of("ADAE", table, "SUPPAE", suppaeWithAetrtem());
        assertNoViolation("CDISC-AD0640", table, "ADAE", resolverWith(overrides));
    }


    @Test
    void CDISC_AD0640_invalid()
    {
        var table = virtualTable("ADAE", 1, "USUBJID", "AESEQ", "AETERM");
        Map<String, IDataTable> overrides = Map.of("ADAE", table, "SUPPAE", suppaeWithAetrtem());
        assertViolation("CDISC-AD0640", table, "ADAE", resolverWith(overrides));
    }

    // ---- Regex-rewrite rules (post-2026-04-27) ----
    // CDISC-AD0041 / 0043 used to expand via the WildcardExpander (one rule per
    // *DT or *DTM column). They were rewritten to a single rule using
    // ``variable_name matches_regex .+DT$`` (resp. ``.+DTM$``) so the engine
    // iterates columns once and resolves the predicate per-column. Tests
    // here exercise the rewritten form directly.


    /**
     * Builds a single-row ADaM-style table whose only column has the requested name and metadata.
     * The row value stays null — VMC rules iterate over column metadata, not row values.
     */
    private OverlayDataTable vmcFixture(String colName,
            net.cumba.datatable.values.DataValueType type, String displayFormat)
    {
        OverlayDataTable t = virtualTable("ADSL", 1, colName);
        if (type != null)
        {
            t.setColumnType(colName, type);
        }
        if (displayFormat != null)
        {
            t.setColumnFormat(colName, displayFormat);
        }
        return t;
    }


    @Test
    void CDISC_AD0041_invalid_numericDtWithoutDateFormat()
    {
        // *DT column, Num type, non-date format → fires
        var table = vmcFixture("TRTSDT", net.cumba.datatable.values.DataValueType.DOUBLE,
                "BEST12.");
        assertViolation("CDISC-AD0041", table, "ADSL");
    }


    @Test
    void CDISC_AD0041_valid_numericDtWithDateFormat()
    {
        var table = vmcFixture("TRTSDT", net.cumba.datatable.values.DataValueType.DOUBLE, "DATE9.");
        assertNoViolation("CDISC-AD0041", table, "ADSL");
    }


    @Test
    void CDISC_AD0041_valid_charDtIgnored()
    {
        // Char type with a *DT name → variable_data_type != "Num" → no fire
        var table = vmcFixture("TRTSDT", net.cumba.datatable.values.DataValueType.STRING,
                "BEST12.");
        assertNoViolation("CDISC-AD0041", table, "ADSL");
    }


    @Test
    void CDISC_AD0041_valid_dtmColumnIgnored()
    {
        // *DTM column should not match ``.+DT$`` regex → no fire
        var table = vmcFixture("TRTSDTM", net.cumba.datatable.values.DataValueType.DOUBLE,
                "BEST12.");
        assertNoViolation("CDISC-AD0041", table, "ADSL");
    }


    @Test
    void CDISC_AD0043_invalid_numericDtmWithoutDatetimeFormat()
    {
        var table = vmcFixture("TRTSDTM", net.cumba.datatable.values.DataValueType.DOUBLE,
                "BEST12.");
        assertViolation("CDISC-AD0043", table, "ADSL");
    }


    @Test
    void CDISC_AD0043_valid_numericDtmWithDatetimeFormat()
    {
        var table = vmcFixture("TRTSDTM", net.cumba.datatable.values.DataValueType.DOUBLE,
                "DATETIME20.");
        assertNoViolation("CDISC-AD0043", table, "ADSL");
    }


    @Test
    void CDISC_AD0043_valid_dtColumnIgnored()
    {
        // *DT column should not match ``.+DTM$`` regex → no fire
        var table = vmcFixture("TRTSDT", net.cumba.datatable.values.DataValueType.DOUBLE,
                "BEST12.");
        assertNoViolation("CDISC-AD0043", table, "ADSL");
    }

    // ---- VCVM regex-rewrite rules (post-2026-04-27) ----
    // CDISC-AD0005 used to expand via WildcardExpander (one rule per *FL column).
    // Rewritten as ``Value Check with Variable Metadata`` with
    // ``variable_name matches_regex ^.+FL$`` + ``variable_value`` row-level
    // checks. Engine iterates each column; the regex folds to FALSE on
    // non-FL columns, the row checks fire on bad cell values.


    @Test
    void CDISC_AD0005_invalid_flWithBadValue()
    {
        // FL column with value other than Y/N/null → fires on that row.
        OverlayDataTable table = virtualTable("ADSL", 1, "USUBJID", "SAFFL");
        table.setValue(0, "USUBJID", "S01");
        table.setValue(0, "SAFFL", "X");
        assertViolation("CDISC-AD0005", table, "ADSL");
    }


    @ParameterizedTest(name = "CDISC_AD0005 valid: SAFFL={0}")
    @CsvSource(value =
    {
            "Y", "N", "null"
    }, nullValues = "null")
    void CDISC_AD0005_valid_flWithAllowedValue(String saffl)
    {
        // Allowed FL values: Y, N, or null (non_empty fails → no fire).
        OverlayDataTable table = virtualTable("ADSL", 1, "USUBJID", "SAFFL");
        table.setValue(0, "USUBJID", "S01");
        if (saffl != null)
        {
            table.setValue(0, "SAFFL", saffl);
        }
        assertNoViolation("CDISC-AD0005", table, "ADSL");
    }


    @Test
    void CDISC_AD0005_valid_nonFlColumnIgnored()
    {
        // Column not ending in FL with a bad value → regex doesn't match
        // → no fire.
        OverlayDataTable table = virtualTable("ADSL", 1, "USUBJID", "RACE");
        table.setValue(0, "USUBJID", "S01");
        table.setValue(0, "RACE", "X");
        assertNoViolation("CDISC-AD0005", table, "ADSL");
    }


    @Test
    void CDISC_AD0005_invalid_multipleFlColumnsOnePerViolation()
    {
        // Two FL columns both with bad values → engine iterates each column;
        // expects per-column violations (Sensitivity = Record).
        OverlayDataTable table = virtualTable("ADSL", 1, "USUBJID", "SAFFL", "EFFFL");
        table.setValue(0, "USUBJID", "S01");
        table.setValue(0, "SAFFL", "X");
        table.setValue(0, "EFFFL", "Q");
        assertViolation("CDISC-AD0005", table, "ADSL");
    }

}
