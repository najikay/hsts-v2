package client.features.bank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link QuestionEditorCopy} — the editor's own words.
 *
 * <p>Short on purpose. The sentences that matter most on this screen are the refusals, and they
 * are deliberately not in this class: they come from {@code BankMessages} so the live hint and
 * the server's answer cannot be worded differently. What is tested here is the framing, and the
 * one rule that no reviewer will catch by reading.
 */
class QuestionEditorCopyTest {

    @Test
    @DisplayName("the edit heading says which version saving will write, and which it keeps")
    void editSubtitleNamesBothVersions() {
        assertThat(QuestionEditorCopy.editSubtitle(2))
                .as("editing writing a NEW version is not what a teacher expects, and she has to "
                        + "know before she saves rather than when she goes looking for the old "
                        + "one (C-2, ADR-011)")
                .contains("version 3")
                .contains("Version 2 is kept");
        assertThat(QuestionEditorCopy.titleEdit("11005")).contains("#11005");
    }

    @Test
    @DisplayName("the toasts name the question, because a toast with no id names nothing")
    void toastsNameTheQuestion() {
        assertThat(QuestionEditorCopy.created("11009")).contains("#11009");
        assertThat(QuestionEditorCopy.versionWritten("11005", 3))
                .contains("#11005").contains("version 3");
    }

    @Test
    @DisplayName("the answer prompts are one-based, the same numbering as the wire (C-8)")
    void promptsAreOneBased() {
        assertThat(QuestionEditorCopy.answerPrompt(1)).isEqualTo("Answer 1");
        assertThat(QuestionEditorCopy.answerPrompt(4)).isEqualTo("Answer 4");
    }

    @Test
    @DisplayName("the stale dialog does not offer to overwrite")
    void staleOffersNoOverwrite() {
        assertThat(QuestionEditorCopy.STALE_BODY)
                .as("the other version is somebody's work and this editor has no idea what "
                        + "changed in it, so the only honest move is to go and read it")
                .contains("Close the editor")
                .doesNotContain("overwrite");
        assertThat(QuestionEditorCopy.STALE_CONFIRM).isEqualTo("Close the editor");
    }

    @Test
    @DisplayName("a refusal needs both a field and a sentence")
    void refusalRejectsNulls() {
        assertThatThrownBy(() -> new QuestionEditorCopy.Refusal(null, 0, "x"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new QuestionEditorCopy.Refusal(QuestionEditorCopy.Field.TEXT, 0, null))
                .isInstanceOf(NullPointerException.class);

        assertThat(QuestionEditorCopy.Refusal.form("x").field())
                .isEqualTo(QuestionEditorCopy.Field.FORM);
        assertThat(QuestionEditorCopy.Refusal.answer(3, "x").position()).isEqualTo(3);
        assertThat(QuestionEditorCopy.Refusal.of(QuestionEditorCopy.Field.TOPIC, "x").position())
                .as("a field with one box carries no position")
                .isZero();
    }

    @Test
    @DisplayName("no em dash anywhere in this screen's copy (PRD section 4.1)")
    void noEmDashes() throws IllegalAccessException {
        List<String> offenders = new ArrayList<>();
        for (Field field : QuestionEditorCopy.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !Modifier.isPublic(field.getModifiers())
                    || !(field.get(null) instanceof String text)) {
                continue;
            }
            if (text.indexOf('—') >= 0 || text.indexOf('–') >= 0) {
                offenders.add(field.getName());
            }
            assertThat(text).as("%s", field.getName()).isNotBlank();
        }
        assertThat(offenders).isEmpty();
    }
}
