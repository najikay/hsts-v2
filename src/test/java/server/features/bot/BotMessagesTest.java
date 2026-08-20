package server.features.bot;

import common.dto.bot.BotAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The copy rules, checked over every sentence the bot's server can send (E16.8 —
 * PRD §4.1).
 *
 * <p>Scanning rather than naming: a sentence added next month is covered by these
 * the moment it is written, which is the whole reason the strings live in one
 * class instead of inline in eight handlers.
 */
class BotMessagesTest {

    /** @return every public static String constant on {@link BotMessages}. */
    private static List<String> sentences() {
        List<String> values = new ArrayList<>();
        for (Field field : BotMessages.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {
                try {
                    values.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return values;
    }

    @Test
    @DisplayName("the scan actually finds the sentences")
    void theScanHasTeeth() {
        assertThat(sentences()).hasSizeGreaterThan(8);
    }

    @Test
    @DisplayName("no em dash anywhere, including in the composed sentences (PRD §4.1)")
    void noEmDashes() {
        List<String> all = new ArrayList<>(sentences());
        all.add(BotMessages.lockedOut("Databases", "Databases Midterm"));
        all.add(BotMessages.integrityNotice("Databases"));
        all.add(BotMessages.sourceAdded("Week 3 handout", 4200));

        assertThat(all).allSatisfy(sentence ->
                assertThat(sentence).doesNotContain("—").doesNotContain("–"));
    }

    @Test
    @DisplayName("every refusal ends a sentence, so none of them trails off")
    void everySentenceIsASentence() {
        assertThat(sentences()).allSatisfy(sentence -> {
            assertThat(sentence).isNotBlank();
            assertThat(sentence.trim()).endsWith(".");
        });
    }

    @Test
    @DisplayName("every refusal says what to do next, not only what went wrong")
    void everyRefusalSaysWhatToDoNext() {
        // "What to do next" is a judgement, so the check is a proxy for it: a
        // refusal that offers a next step has more than one sentence, or contains
        // an instruction verb. Both are cheap to satisfy honestly and hard to
        // satisfy by accident.
        assertThat(sentences()).allSatisfy(sentence -> {
            boolean hasSecondSentence = sentence.trim().split("[.!?]\\s+").length > 1;
            boolean tellsThemWhatToDo = sentence.matches("(?s).*\\b(Ask|Check|Open|Try|Wait|"
                    + "Create|Choose|Reload|Split|Give|Type|Tell|Save|Upload|Paste)\\b.*");
            assertThat(hasSecondSentence || tellsThemWhatToDo)
                    .as("this refusal is a dead end: %s", sentence)
                    .isTrue();
        });
    }

    @Test
    @DisplayName("the lockout names the course and the exam, and when the lock lifts")
    void lockoutSentence() {
        String message = BotMessages.lockedOut("Databases 22", "Databases Midterm");

        assertThat(message)
                .contains("Databases 22")
                .contains("Databases Midterm")
                .contains("unlocks");
    }

    @Test
    @DisplayName("the lockout still reads as a sentence when a name is missing")
    void lockoutWithoutNames() {
        assertThat(BotMessages.lockedOut(null, null))
                .isNotBlank()
                .contains("your exam");
        assertThat(BotMessages.lockedOut("  ", "  ")).contains("This course");
    }

    @Test
    @DisplayName("the integrity notice states the consequence and leaves the choice with her")
    void integrityNotice() {
        String message = BotMessages.integrityNotice("Algebra 11");

        assertThat(message)
                .contains("Algebra 11")
                .containsIgnoringCase("teacher")
                .containsIgnoringCase("Continue only if");
        assertThat(message)
                .as("worded as a fact, never as an accusation")
                .doesNotContainIgnoringCase("cheat");
    }

    @Test
    @DisplayName("the integrity notice tolerates a missing course name")
    void integrityNoticeWithoutACourse() {
        assertThat(BotMessages.integrityNotice(null)).contains("another course");
    }

    @Test
    @DisplayName("the source-added confirmation names the source and its size")
    void sourceAdded() {
        assertThat(BotMessages.sourceAdded("Week 3 handout", 4200))
                .contains("Week 3 handout")
                .contains("4200");
    }

    @Test
    @DisplayName("the S-32 sentence is the one the specification asks for, word for word")
    void s32Sentence() {
        assertThat(BotAnswer.S32_FALLBACK)
                .isEqualTo("The bot could not answer that. Try rephrasing, or ask your teacher.");
        assertThat(BotAnswer.S32_FALLBACK).doesNotContain("—");
    }
}
