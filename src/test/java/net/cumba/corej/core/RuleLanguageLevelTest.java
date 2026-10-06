package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The corpus pins the rule-language level it was written for — {@code rules-src/LANGUAGE-LEVEL} —
 * and it must equal the engine's {@link RuleLanguage#LEVEL} ({@code PLAN-rule-authoring-api} §3.2).
 *
 * <p>
 * The engine side ties its level to the measured language surface ({@code language-surface.txt} in
 * {@code cumba-corej-core}'s tests, with a regenerator that refuses a surface change without a
 * bump); this side only has to say which level the corpus was gated against, so an editor can tell
 * an author <em>"this branch was gated against engine language level N; this instance runs level
 * M"</em>. A mismatch here means one side was bumped without the other.
 * </p>
 */
class RuleLanguageLevelTest
{

    static final Path FILE = CorpusPaths.moduleDir().resolve("rules-src").resolve("LANGUAGE-LEVEL");

    @Test
    void theCorpusLevelEqualsTheEngineLevel() throws IOException
    {
        assertTrue(Files.isRegularFile(FILE), FILE + " is missing");
        String text = Files.readString(FILE, StandardCharsets.UTF_8).strip();
        assertTrue(text.matches("[0-9]+"), "LANGUAGE-LEVEL holds one integer, got '" + text + "'");
        assertEquals(RuleLanguage.LEVEL, Integer.parseInt(text),
                "rules-src/LANGUAGE-LEVEL and RuleLanguage.LEVEL disagree — the engine's language"
                        + " surface changed (or one side was bumped alone): regenerate the"
                        + " engine golden and raise this file in the same round");
    }
}
