package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

        // get_codelist_attributes left the operation surface in wave 0 (PLAN-binding-expressions):
        // it is a registry function now, and its binding is a compiled binding, printed by the
        // expression printer the Check is printed by. Its round-trip lives on that surface.
        String attrs = "get_codelist_attributes(TSVCDREF, TSVCDVER, ct_attribute=\"Term CCODE\")";
        assertEquals(attrs, net.cumba.corej.core.expr.ExpressionPrinter
                .print(net.cumba.corej.core.expr.CheckExpressionParser.parse(attrs)));
        assertFalse(BindingRouting.routesToOperation(attrs),
                "no longer an operation call — the compiled-binding path");

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
