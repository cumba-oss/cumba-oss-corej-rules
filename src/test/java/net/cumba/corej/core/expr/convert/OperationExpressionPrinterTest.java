package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.Operation;
import org.junit.jupiter.api.Test;

/**
 * {@link OperationExpressionPrinter} field rendering and the retired {@code OperationFormRewriter}
 * directory pass — exercises every keyword-argument kind (string, list, boolean, filter marker) and
 * the quoting of non-bareword names, asserting each prints to the expected call and round-trips
 * back to the original operation.
 */
class OperationExpressionPrinterTest
{

    private static void assertRoundTrip(Operation op, String expected)
    {
        String printed = OperationExpressionPrinter.print(op);
        assertEquals(expected, printed);
        Operation reauthored = new Operation();
        reauthored.setId(op.getId());
        reauthored.setExpression(printed);
        assertEquals(op, OperationExpressionParser.normalize(reauthored));
    }


    @Test
    void wildcardNameRaw()
    {
        Operation op = new Operation();
        op.setOperator("variable_count");
        op.setName("--LNKGRP");
        assertRoundTrip(op, "variable_count(--LNKGRP)");
    }


    @Test
    void minusSubtractRefRendered()
    {
        // The minus subtrahend is a $-ref to a prior operation result; it prints as a bare
        // $name kwarg and round-trips back through OperationExpressionParser.
        Operation op = new Operation();
        op.setOperator("minus");
        op.setName("$expected_variables");
        op.setSubtract("$dataset_variables");
        assertRoundTrip(op, "minus($expected_variables, subtract=$dataset_variables)");
    }


    @Test
    void dyReferenceKwargRendered()
    {
        // T6: a dy operation with a `reference` prints the reference-date column as a quoted kwarg
        // and round-trips back through the parser.
        Operation op = new Operation();
        op.setOperator("dy");
        op.setName("--DTC");
        op.setReference("RFXSTDTC");
        assertRoundTrip(op, "dy(--DTC, reference=\"RFXSTDTC\")");
    }


    @Test
    void dyBareOmitsReferenceKwarg()
    {
        // Regression guard: a dy operation with no reference must print without the kwarg, keeping
        // every pre-existing dy rule byte-identical.
        Operation op = new Operation();
        op.setOperator("dy");
        op.setName("--STDTC");
        assertRoundTrip(op, "dy(--STDTC)");
    }


    @Test
    void constantNameQuoted()
    {
        Operation op = new Operation();
        op.setOperator("constant");
        op.setName("Y");
        assertRoundTrip(op, "constant(\"Y\")");
    }


    @Test
    void lowercaseMetadataNameQuoted()
    {
        Operation op = new Operation();
        op.setOperator("extract_metadata");
        op.setName("dataset_name");
        assertRoundTrip(op, "extract_metadata(\"dataset_name\")");
    }


    @Test
    void filterMarkerRendered()
    {
        Operation op = new Operation();
        op.setOperator("record_count");
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("TSPARMCD", "INDIC");
        filter.put("TSVAL", "Y");
        op.setFilter(filter);
        assertRoundTrip(op, "record_count(filter=filter(TSPARMCD=\"INDIC\", TSVAL=\"Y\"))");
    }


    @Test
    void filterMarkerWithListMembershipRendered()
    {
        Operation op = new Operation();
        op.setOperator("record_count");
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("DSDECOD", List.of("RANDOMIZED", "SCREEN FAILURE"));
        op.setFilter(filter);
        assertRoundTrip(op,
                "record_count(filter=filter(DSDECOD=[\"RANDOMIZED\", \"SCREEN FAILURE\"]))");
    }


    @Test
    void groupAndDomainAndBoolean()
    {
        Operation op = new Operation();
        op.setOperator("distinct");
        op.setName("IDVAR");
        op.setDomain("SUPP--");
        op.setGroup(List.of("USUBJID", "--TESTCD"));
        op.setValueIsReference(true);
        assertRoundTrip(op, "distinct(IDVAR, domain=\"SUPP--\", group=[USUBJID, --TESTCD], "
                + "value_is_reference=true)");
    }


    @Test
    void libraryKeywordFields()
    {
        Operation op = new Operation();
        op.setOperator("get_dataset_filtered_variables");
        op.setKeyName("role");
        op.setKeyValue("Timing");
        assertRoundTrip(op,
                "get_dataset_filtered_variables(key_name=\"role\", key_value=\"Timing\")");
    }


    /**
     * Phase 6b: each field rides its CONSUMING operator — the descriptor gate
     * (OperationDescriptors) rejects a field the operation never reads, which is exactly the
     * silent-drop shape the old any-host round-trip smuggled past the loader.
     */
    @Test
    void codelistsAndCtFields()
    {
        Operation terms = new Operation();
        terms.setOperator("codelist_terms");
        terms.setCodelists(List.of("DOMAIN"));
        terms.setLevel("term");
        terms.setReturntype("code");
        assertRoundTrip(terms,
                "codelist_terms(codelists=[DOMAIN], level=\"term\", returntype=\"code\")");

        Operation attrs = new Operation();
        attrs.setOperator("get_codelist_attributes");
        attrs.setCtAttribute("Term CCODE");
        attrs.setVersion("TSVCDVER");
        assertRoundTrip(attrs,
                "get_codelist_attributes(ct_attribute=\"Term CCODE\", version=\"TSVCDVER\")");

        Operation dates = new Operation();
        dates.setOperator("valid_codelist_dates");
        dates.setCtPackageTypes(List.of("SDTM"));
        assertRoundTrip(dates, "valid_codelist_dates(ct_package_types=[SDTM])");
    }


    /** Phase 6b: regex is record_count's parameter; name_pattern variable_count's. */
    @Test
    void regexAndNamePattern()
    {
        Operation rc = new Operation();
        rc.setOperator("record_count");
        rc.setRegex("^\\d{4}");
        assertRoundTrip(rc, "record_count(regex=\"^\\\\d{4}\")");

        Operation vc = new Operation();
        vc.setOperator("variable_count");
        vc.setNamePattern(".+FL$");
        assertRoundTrip(vc, "variable_count(name_pattern=\".+FL$\")");
    }

}
