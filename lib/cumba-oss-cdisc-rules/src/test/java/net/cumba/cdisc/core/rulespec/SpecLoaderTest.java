package net.cumba.cdisc.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpecLoaderTest
{

    @Test
    void parsesMinimalCheckOperatorSpec() throws Exception
    {
        String yaml = """
                name: op_equal_to
                kind: check_operator
                check:
                  name: SEX
                  operator: equal_to
                  value: "M"
                datasets:
                  AE:
                    row_key: [STUDYID, USUBJID, AESEQ]
                    rows:
                      - {STUDYID: "S1", USUBJID: "001", AESEQ: 1, SEX: "M"}
                      - {STUDYID: "S1", USUBJID: "002", AESEQ: 1, SEX: "F"}
                config:
                  standard: sdtmig
                  standard_version: "3.4"
                expected_violations: []
                expected_errors: []
                """;
        SpecSchema spec = SpecLoader.loadFromString(yaml);

        assertEquals("op_equal_to", spec.name());
        assertEquals(SpecSchema.Kind.CHECK_OPERATOR, spec.kind());
        assertNotNull(spec.check());
        assertEquals("SEX", spec.check().get("name").asText());
        assertEquals("equal_to", spec.check().get("operator").asText());
        assertEquals("M", spec.check().get("value").asText());

        SpecSchema.SpecDataset ae = spec.datasets().get("AE");
        assertEquals(List.of("STUDYID", "USUBJID", "AESEQ"), ae.rowKey());
        assertEquals(2, ae.rows().size());
        assertEquals("M", ae.rows().get(0).get("SEX"));

        assertEquals("sdtmig", spec.config().standard());
        assertEquals("3.4", spec.config().standardVersion());
        assertTrue(spec.expectedViolations().isEmpty());
    }


    @Test
    void dmRowKeyDefaultsToStudyidUsubjid_whenOmitted() throws Exception
    {
        String yaml = """
                name: dm_default
                kind: rule
                rule:
                  id: dummy
                  Core: {Id: AUTO-1}
                  Description: ""
                  Check: {all: []}
                  Outcome: {Message: ""}
                datasets:
                  DM:
                    rows:
                      - {STUDYID: "S1", USUBJID: "001"}
                config:
                  standard: sdtmig
                  standard_version: "3.4"
                expected_violations: []
                """;
        SpecSchema spec = SpecLoader.loadFromString(yaml);
        assertEquals(List.of("STUDYID", "USUBJID"), spec.datasets().get("DM").rowKey());
    }


    @Test
    void dmRowKeyExplicitNonDefault_isRejected()
    {
        String yaml = """
                name: dm_bad
                kind: rule
                rule: {id: x, Core: {Id: AUTO}, Description: "", Check: {all: []}, Outcome: {Message: ""}}
                datasets:
                  DM:
                    row_key: [USUBJID]
                    rows: []
                config: {standard: sdtmig, standard_version: "3.4"}
                expected_violations: []
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SpecLoader.loadFromString(yaml));
        assertTrue(ex.getMessage().contains("DM row_key"),
                () -> "Unexpected message: " + ex.getMessage());
    }


    @Test
    void dmRowKeyExplicitDefault_isAccepted() throws Exception
    {
        String yaml = """
                name: dm_ok
                kind: rule
                rule: {id: x, Core: {Id: AUTO}, Description: "", Check: {all: []}, Outcome: {Message: ""}}
                datasets:
                  DM:
                    row_key: [STUDYID, USUBJID]
                    rows: []
                config: {standard: sdtmig, standard_version: "3.4"}
                """;
        SpecSchema spec = SpecLoader.loadFromString(yaml);
        assertEquals(List.of("STUDYID", "USUBJID"), spec.datasets().get("DM").rowKey());
        // expected_violations defaults to null when absent (Jackson record default).
        assertNull(spec.expectedViolations());
    }
}
