package net.cumba.cdisc.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BaselineManifestTest
{

    @Test
    void loadsEmptyManifestWhenFileMissing() throws IOException
    {
        var m = BaselineManifest.load(Path.of("/nonexistent/baseline.json"));
        assertEquals(0, m.knownDivergences().size());
        assertFalse(m.contains("anything"));
    }


    @Test
    void loadsKnownDivergencesFromDisk(@TempDir Path tmp) throws IOException
    {
        Path file = tmp.resolve("baseline.json");
        Files.writeString(file, """
                {
                  "baseline_date": "2026-04-20",
                  "known_divergences": [
                    {
                      "spec": "CORE-000358",
                      "fix_ref": "Fix #1",
                      "description": "variable_count dedup"
                    },
                    {
                      "spec": "CORE-000012",
                      "fix_ref": "Wave 2 Finding #9",
                      "description": "Variable Metadata Check semantics",
                      "notes": "Deferred pending adapter extension"
                    }
                  ]
                }
                """);
        var m = BaselineManifest.load(file);
        assertEquals("2026-04-20", m.baselineDate());
        assertEquals(2, m.knownDivergences().size());
        assertTrue(m.contains("CORE-000358"));
        assertTrue(m.contains("CORE-000012"));
        assertFalse(m.contains("CORE-999999"));
        assertEquals("Fix #1", m.lookup("CORE-000358").get().fixRef());
        assertEquals("Deferred pending adapter extension", m.lookup("CORE-000012").get().notes());
    }


    @Test
    void unknownJsonFieldsAreIgnored(@TempDir Path tmp) throws IOException
    {
        Path file = tmp.resolve("baseline.json");
        // The real baseline file carries an underscore-prefixed "_comment" — make sure the
        // loader doesn't choke on it or on any other unknown top-level/entry field.
        Files.writeString(file, """
                {
                  "_comment": "free-form explanation",
                  "baseline_date": "2026-04-20",
                  "future_field": 42,
                  "known_divergences": [
                    {
                      "spec": "X",
                      "fix_ref": "Fix #N",
                      "extra_meta": "ignored"
                    }
                  ]
                }
                """);
        var m = BaselineManifest.load(file);
        assertEquals(1, m.knownDivergences().size());
        assertEquals("X", m.knownDivergences().get(0).spec());
    }


    @Test
    void realBaselineFileLoads() throws IOException
    {
        var m = BaselineManifest.loadDefault();
        // The committed file exists; should load without error. Initial state: empty list.
        // Future entries added by Wave 3 ports will bump this count — that's expected.
        assertNotNull(m.knownDivergences());
    }


    @Test
    void laneDefaultsToJavaWhenAbsent(@TempDir Path tmp) throws IOException
    {
        Path file = tmp.resolve("baseline.json");
        Files.writeString(file, """
                {
                  "baseline_date": "2026-04-21",
                  "known_divergences": [
                    {"spec": "A", "fix_ref": "x"},
                    {"spec": "B", "fix_ref": "y", "lane": "python"},
                    {"spec": "C", "fix_ref": "z", "lane": "both"},
                    {"spec": "D", "fix_ref": "w", "lane": "java"}
                  ]
                }
                """);
        var m = BaselineManifest.load(file);
        // A: implicit java
        var a = m.lookup("A").orElseThrow();
        assertEquals("java", a.laneOrDefault());
        assertTrue(a.appliesToJavaLane());
        assertFalse(a.appliesToPythonLane());
        // B: python only
        var b = m.lookup("B").orElseThrow();
        assertEquals("python", b.laneOrDefault());
        assertFalse(b.appliesToJavaLane());
        assertTrue(b.appliesToPythonLane());
        // C: both
        var c = m.lookup("C").orElseThrow();
        assertEquals("both", c.laneOrDefault());
        assertTrue(c.appliesToJavaLane());
        assertTrue(c.appliesToPythonLane());
        // D: explicit java
        var d = m.lookup("D").orElseThrow();
        assertEquals("java", d.laneOrDefault());
        assertTrue(d.appliesToJavaLane());
        assertFalse(d.appliesToPythonLane());
    }

    // --- channels discriminator (PLAN-parity-expected-status D1') --------------------
    // Mirror of test_baseline.py's channel cases — keep the two in lock step.


    @Test
    void channelsDefaultToViolationsWhenAbsent(@TempDir Path tmp) throws IOException
    {
        // Back-compat: every entry filed before the status channel existed was about the
        // violation diff. Defaulting to ["violations"] rather than to both is what keeps those
        // entries subject to the status assertion instead of permanently exempt from it.
        Path file = tmp.resolve("baseline.json");
        Files.writeString(file, """
                {
                  "known_divergences": [
                    {"spec": "A", "fix_ref": "x"},
                    {"spec": "B", "fix_ref": "y", "channels": []}
                  ]
                }
                """);
        var m = BaselineManifest.load(file);
        for (String name : new String[]
        {
                "A", "B"
        })
        {
            var e = m.lookup(name).orElseThrow();
            assertEquals(List.of("violations"), e.effectiveChannels());
            assertTrue(e.divergesOn(BaselineManifest.VIOLATIONS_CHANNEL));
            assertFalse(e.divergesOn(BaselineManifest.STATUS_CHANNEL));
        }
    }


    @Test
    void channelsAreParsedAndNormalised(@TempDir Path tmp) throws IOException
    {
        Path file = tmp.resolve("baseline.json");
        Files.writeString(file, """
                {
                  "known_divergences": [
                    {"spec": "S", "lane": "java", "channels": ["status"]},
                    {"spec": "V", "lane": "java", "channels": ["violations"]},
                    {"spec": "BOTH", "lane": "java", "channels": ["violations", "status"]},
                    {"spec": "CASE", "lane": "java", "channels": ["  STATUS "]}
                  ]
                }
                """);
        var m = BaselineManifest.load(file);
        assertEquals(List.of("status"), m.lookup("S").orElseThrow().effectiveChannels());
        assertFalse(m.lookup("S").orElseThrow().divergesOn(BaselineManifest.VIOLATIONS_CHANNEL));
        assertEquals(List.of("violations"), m.lookup("V").orElseThrow().effectiveChannels());
        assertFalse(m.lookup("V").orElseThrow().divergesOn(BaselineManifest.STATUS_CHANNEL));
        assertEquals(List.of("violations", "status"),
                m.lookup("BOTH").orElseThrow().effectiveChannels());
        assertEquals(List.of("status"), m.lookup("CASE").orElseThrow().effectiveChannels());
    }


    @Test
    void unknownChannelNamesAreReported(@TempDir Path tmp) throws IOException
    {
        Path file = tmp.resolve("baseline.json");
        Files.writeString(file, """
                {
                  "known_divergences": [
                    {"spec": "OK", "channels": ["status"]},
                    {"spec": "BAD", "channels": ["violations", "typo"]}
                  ]
                }
                """);
        var m = BaselineManifest.load(file);
        assertEquals(List.of(), m.lookup("OK").orElseThrow().unknownChannels());
        assertEquals(List.of("typo"), m.lookup("BAD").orElseThrow().unknownChannels());
        assertEquals(java.util.Map.of("BAD", List.of("typo")), m.entriesWithUnknownChannels());
    }


    @Test
    void duplicateSpecEntriesAreDetectable(@TempDir Path tmp) throws IOException
    {
        // `lookup` is first-match on BOTH lanes, so a second entry for the same spec is a silent
        // no-op — exactly the trap of "appending" a status entry next to an existing one. Edit
        // the existing entry instead.
        Path file = tmp.resolve("baseline.json");
        Files.writeString(file, """
                {
                  "known_divergences": [
                    {"spec": "DUP", "fix_ref": "first", "channels": ["violations"]},
                    {"spec": "DUP", "fix_ref": "second - dead", "channels": ["status"]},
                    {"spec": "UNIQUE", "fix_ref": "x"}
                  ]
                }
                """);
        var m = BaselineManifest.load(file);
        assertEquals("first", m.lookup("DUP").orElseThrow().fixRef());
        assertEquals(List.of("DUP"), m.duplicateSpecs());
    }


    @Test
    void committedBaselineHasNoDuplicatesOrUnknownChannels() throws IOException
    {
        var m = BaselineManifest.loadDefault();
        assertEquals(List.of(), m.duplicateSpecs());
        assertEquals(java.util.Map.of(), m.entriesWithUnknownChannels());
    }
}
