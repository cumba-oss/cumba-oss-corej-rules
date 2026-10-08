package net.cumba.corej.core.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import org.junit.jupiter.api.Test;

/**
 * ⭐ The function reference ({@code rule-guide/reference/functions.md}) is held to the engine's
 * {@link FunctionRegistry}: every registered name must have its own {@code ### name} section — or
 * be one of the {@link #ALIASES} (a negated or alternative spelling), each of which must be named
 * in backticks ({@code `name`} / {@code `name(}) INSIDE its primary's section. The page is
 * maintained by hand, so without this a newly registered function is invisible to authors while
 * every gate stays green — {@code PLAN-dynamic-column-functions} review round 1 (lane C F7) found
 * {@code date}, {@code time} and two metadata accessors undocumented that way. Review round 2 (L1):
 * "a backticked mention anywhere" was too weak — {@code length} was "documented" by a parameter row
 * of {@code substring} — so a mention counts only for a listed alias, only in its primary's
 * section.
 *
 * <p>
 * Non-vacuity, checked on every run: the registry and the page each carry a floor of names, every
 * alias entry is itself registered and its primary has a section (a stale map cannot pass), a
 * fabricated name is reported, and the check run over the page with one real function's heading
 * removed — or one alias's mention removed from its primary's section — reports exactly that name.
 * </p>
 */
class FunctionReferenceRosterTest
{

    /** A floor, not a count: the registry held 164 names on 2026-09-30. */
    private static final int MIN_REGISTERED = 150;

    /**
     * A floor on the page's {@code ### name} sections: 167 measured 2026-09-30, the floor a little
     * below it so an edit that merges a couple of sections does not red, while a page read as
     * mostly empty (or a heading pattern that stopped matching) does.
     */
    private static final int MIN_SECTIONS = 160;

    /**
     * The registered names documented inside ANOTHER function's section — alias → primary. Measured
     * 2026-09-30: 23, every one a negated or alternative spelling its primary's section names; 22
     * since 2026-10-05, when {@code is_missing} stopped being {@code empty}'s alias (register D50)
     * and got its own section.
     */
    static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("does_not_contain", "contains"), Map.entry("ds_not_exists", "ds_exists"),
            Map.entry("has_not_equal_length", "has_equal_length"),
            Map.entry("invalid_date", "is_valid_date"),
            Map.entry("invalid_duration", "is_valid_duration"),
            Map.entry("is_incomplete_date", "is_partial_date"),
            Map.entry("is_not_complete_date_part", "is_complete_date_part"),
            Map.entry("is_not_integer", "is_integer"),
            Map.entry("is_not_ordered_subset_of", "is_ordered_subset_of"),
            // runbook W1b RO (PLAN-engine-gap-row-ordinal R2): the negated twin of is_prefix_of
            Map.entry("is_not_prefix_of", "is_prefix_of"),
            Map.entry("is_not_unique_relationship", "is_unique_relationship"),
            Map.entry("is_not_unique_set", "is_unique_set"),
            Map.entry("is_not_unique_value", "is_unique_value"), Map.entry("is_present", "empty"),
            Map.entry("length", "len"), Map.entry("lowcase", "lower"),
            Map.entry("non_empty", "empty"), Map.entry("not_contains_all", "contains_all"),
            Map.entry("present", "empty"),
            Map.entry("shares_no_elements_with", "shares_elements_with"),
            Map.entry("size", "count"), Map.entry("upcase", "upper"),
            Map.entry("var_not_exists", "var_exists"));

    private static Path page()
    {
        return CorpusPaths.moduleDir().resolve("rule-guide/reference/functions.md");
    }


    /** The page's {@code ### name} sections: name → the section's text (up to the next heading). */
    static Map<String, String> sections(String doc)
    {
        Map<String, String> out = new LinkedHashMap<>();
        String current = null;
        StringBuilder text = new StringBuilder();
        for (String line : doc.split("\n", -1))
        {
            java.util.regex.Matcher heading = HEADING.matcher(line);
            if (heading.matches() || line.startsWith("## "))
            {
                if (current != null)
                {
                    out.put(current, text.toString());
                }
                current = heading.matches() ? heading.group(1) : null;
                text.setLength(0);
            }
            else
            {
                text.append(line).append('\n');
            }
        }
        if (current != null)
        {
            out.put(current, text.toString());
        }
        return out;
    }


    private static Set<String> undocumented(String doc, List<String> names)
    {
        Map<String, String> sections = sections(doc);
        Set<String> missing = new TreeSet<>();
        for (String name : names)
        {
            if (sections.containsKey(name))
            {
                continue;
            }
            String primary = ALIASES.get(name);
            String home = primary == null ? null : sections.get(primary);
            if (home == null
                    || !Pattern.compile("`" + Pattern.quote(name) + "(`|\\()").matcher(home).find())
            {
                missing.add(name);
            }
        }
        return missing;
    }


    @Test
    void everyRegisteredFunctionIsDocumented() throws IOException
    {
        String doc = Files.readString(page());
        List<String> names = registered();
        assertTrue(names.size() >= MIN_REGISTERED, "the registry lists " + names.size()
                + " functions, below the floor " + MIN_REGISTERED);
        Map<String, String> sections = sections(doc);
        assertTrue(sections.size() >= MIN_SECTIONS, "functions.md has " + sections.size()
                + " ### sections, below the floor " + MIN_SECTIONS);
        assertEquals(Set.of(), undocumented(doc, names),
                "registered functions with neither their own `### name` section nor (as a listed"
                        + " alias) a backticked mention inside their primary's section in "
                        + page());
    }


    /**
     * The alias map stays true: every alias is registered, sectionless, with a documented primary.
     */
    @Test
    void theAliasMapNamesRegisteredSectionlessFunctions() throws IOException
    {
        Map<String, String> sections = sections(Files.readString(page()));
        List<String> names = registered();
        ALIASES.forEach((alias, primary) ->
        {
            assertTrue(names.contains(alias), "stale alias entry, not registered: " + alias);
            assertTrue(!sections.containsKey(alias),
                    alias + " now has its own section — drop it from ALIASES");
            assertTrue(sections.containsKey(primary),
                    alias + "'s primary has no section: " + primary);
        });
    }


    /** The instrument can fail: a fabricated name, a removed heading, a removed alias mention. */
    @Test
    void theRosterCheckSeesAMissingFunction() throws IOException
    {
        String doc = Files.readString(page());
        assertEquals(Set.of("zz_no_such_function"),
                undocumented(doc, List.of("zz_no_such_function", "printf")),
                "a fabricated name must be reported, a documented one not");
        String withoutLpad = doc.replaceAll("(?m)^### lpad\\s*$", "### removed");
        assertEquals(Set.of("lpad"), undocumented(withoutLpad, List.of("lpad", "printf")),
                "removing lpad's heading must make it undocumented — a mention elsewhere does not"
                        + " count for a non-alias");
        String withoutUpcase = doc.replace("`upcase`", "`removed`").replace("`upcase(",
                "`removed(");
        assertEquals(Set.of("upcase"), undocumented(withoutUpcase, List.of("upcase", "lower")),
                "removing an alias's mention from its primary's section must report it");
        // Section-scoped (review round 3): `length` is also named in other sections
        // (has_equal_length, substring, …); removing it from its PRIMARY's section alone must
        // report it — a mention elsewhere does not count for an alias.
        Map<String, String> sections = sections(doc);
        String lenSection = sections.get("len");
        String withoutLengthInLen = doc.replace(lenSection,
                lenSection.replace("`length`", "`removed`").replace("`length(", "`removed("));
        assertTrue(doc.replace(lenSection, "").contains("`length`"),
                "the control needs `length` named OUTSIDE len's section too");
        assertEquals(Set.of("length"), undocumented(withoutLengthInLen, List.of("length", "len")),
                "an alias mentioned only outside its primary's section is undocumented");
    }


    private static List<String> registered()
    {
        return FunctionRegistry.all().stream().map(FunctionDescriptor::name).distinct().sorted()
                .toList();
    }

    private static final Pattern HEADING = Pattern.compile("^### (\\S+)\\s*$");
}
