package common.dto.bank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Tests for the optimistic-update payload (E18.4).
 *
 * <p>The whole value of this DTO is that it carries <b>both</b> versions: what
 * the user typed and what they were looking at when they typed it. A round-trip
 * that lost the second half would silently turn every guarded save back into an
 * unguarded one, so that is what is asserted here.
 */
class QuestionUpdateTest {

    @Test
    @DisplayName("both the edit and the baseline survive the wire")
    void roundTrips() throws Exception {
        QuestionUpdate original = new QuestionUpdate(
                new Question(7, "What is 2 + 2?", "4"), "What is 2+2?", "four");

        QuestionUpdate restored = roundTrip(original);

        assertThat(restored.id()).isEqualTo(7);
        assertThat(restored.edited().getQuestionText()).isEqualTo("What is 2 + 2?");
        assertThat(restored.expectedText()).isEqualTo("What is 2+2?");
        assertThat(restored.expectedAnswer()).isEqualTo("four");
    }

    @Test
    @DisplayName("a question that never had an answer still has a comparable baseline")
    void normalisesNullBaseline() throws Exception {
        QuestionUpdate update = new QuestionUpdate(new Question(1, "q", "a"), null, null);

        assertThat(update.expectedText()).isEmpty();
        assertThat(update.expectedAnswer()).isEmpty();
        assertThat(roundTrip(update).expectedAnswer()).isEmpty();
    }

    @Test
    @DisplayName("there is nothing to save without a question")
    void requiresTheEdit() {
        assertThatNullPointerException().isThrownBy(() -> new QuestionUpdate(null, "", ""));
    }

    @Test
    @DisplayName("Hebrew round-trips in both halves (X-I18N)")
    void hebrewSurvives() throws Exception {
        QuestionUpdate restored = roundTrip(new QuestionUpdate(
                new Question(2, "מהי בירת צרפת?", "פריז"), "מהי בירת צרפת", "פריס"));

        assertThat(restored.edited().getAnswer()).isEqualTo("פריז");
        assertThat(restored.expectedAnswer()).isEqualTo("פריס");
    }

    private static QuestionUpdate roundTrip(QuestionUpdate original) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (QuestionUpdate) in.readObject();
        }
    }
}
