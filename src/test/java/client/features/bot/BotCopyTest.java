package client.features.bot;

import common.dto.bot.BotSourceKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The copy rules over every word the bot's screens show (E16.12-E16.15 —
 * PRD §4.1).
 *
 * <p>Scanning rather than naming, so a string added next month is covered the
 * moment it is written. That is the whole reason the copy lives in one class
 * instead of inline in four views.
 */
class BotCopyTest {

    /** @return every public static String constant on {@link BotCopy}. */
    private static List<Field> copyFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : BotCopy.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static String valueOf(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("the scan actually finds the copy")
    void theScanHasTeeth() {
        assertThat(copyFields()).hasSizeGreaterThan(25);
    }

    @Test
    @DisplayName("no em dash anywhere (PRD §4.1)")
    void noEmDashes() {
        for (Field field : copyFields()) {
            assertThat(valueOf(field))
                    .as("%s", field.getName())
                    .doesNotContain("—")
                    .doesNotContain("–");
        }
    }

    @Test
    @DisplayName("nothing is blank, and nothing has stray whitespace at either end")
    void nothingIsBlankOrRagged() {
        for (Field field : copyFields()) {
            String value = valueOf(field);
            assertThat(value).as("%s", field.getName()).isNotBlank();
            assertThat(value).as("%s", field.getName()).isEqualTo(value.trim());
        }
    }

    @Test
    @DisplayName("every message that reports a problem says what the user can do next")
    void everyFailureSaysWhatToDoNext() {
        List<String> failures = List.of(BotCopy.ASK_FAILED, BotCopy.HISTORY_FAILED,
                BotCopy.MANAGER_FAILED, BotCopy.ANALYTICS_FAILED);

        assertThat(failures).allSatisfy(message -> {
            assertThat(message).endsWith(".");
            assertThat(message)
                    .as("every one of these tells the user to check the connection, which is "
                            + "the only thing she can actually do about it")
                    .containsIgnoringCase("check your connection");
            assertThat(message.matches("(?s).*\\b(again|retry)\\b.*"))
                    .as("a dead end at eleven at night is the difference between a "
                            + "feature and a complaint: %s", message)
                    .isTrue();
        });
    }

    @Test
    @DisplayName("the C-4 confirmation is worded as a choice, never as an accusation (ADR-018)")
    void theIntegrityConfirmationIsCalm() {
        assertThat(BotCopy.INTEGRITY_TITLE).isEqualTo("You are taking an exam");
        assertThat(BotCopy.INTEGRITY_DETAIL)
                .containsIgnoringCase("teacher")
                .containsIgnoringCase("nothing you ask");
        assertThat(BotCopy.INTEGRITY_CONFIRM).isNotBlank();
        assertThat(BotCopy.INTEGRITY_CANCEL).isNotBlank();

        for (String word : List.of("cheat", "caught", "violation", "suspicious")) {
            assertThat(BotCopy.INTEGRITY_TITLE + BotCopy.INTEGRITY_DETAIL
                    + BotCopy.INTEGRITY_CONFIRM + BotCopy.INTEGRITY_CANCEL)
                    .as("the server cannot know intent, and neither can this dialog")
                    .doesNotContainIgnoringCase(word);
        }
    }

    @Test
    @DisplayName("the analytics screen states its own anonymity, where it applies (S-34)")
    void theAnonymityNoteIsOnTheScreen() {
        assertThat(BotCopy.ANONYMOUS_NOTE)
                .containsIgnoringCase("anonymous")
                .containsIgnoringCase("never shows who");
    }

    @Test
    @DisplayName("the empty states offer something rather than reporting a void")
    void emptyStatesOfferSomething() {
        assertThat(BotCopy.CHAT_EMPTY_HINT).containsIgnoringCase("try asking");
        assertThat(BotCopy.NO_BOT_HINT).containsIgnoringCase("create");
        assertThat(BotCopy.SOURCES_EMPTY_HINT).containsIgnoringCase("add");
        assertThat(BotCopy.HISTORY_EMPTY_HINT).containsIgnoringCase("saved here");
        assertThat(BotCopy.ANALYTICS_EMPTY_HINT).containsIgnoringCase("once students");
    }

    @Test
    @DisplayName("every source kind has an icon, and no two share one")
    void everyKindHasItsOwnIcon() {
        List<String> icons = new ArrayList<>();
        for (BotSourceKind kind : BotSourceKind.values()) {
            String icon = BotCopy.iconFor(kind);
            assertThat(icon).as("%s", kind).isNotBlank();
            icons.add(icon);
        }
        assertThat(icons).doesNotHaveDuplicates();
    }
}
