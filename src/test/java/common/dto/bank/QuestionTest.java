package common.dto.bank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shared {@link Question} DTO.
 *
 * <p>Question travels inside {@code Message} payloads in both directions, so the
 * contract is: full-args construction, independent mutation of every field, a
 * readable {@code toString}, and a lossless serialization round-trip (including
 * nulls and non-ASCII text).
 */
class QuestionTest {

    @Test
    @DisplayName("no-arg constructor yields an empty question")
    void defaultConstructorLeavesFieldsEmpty() {
        Question q = new Question();

        assertThat(q.getId()).isZero();
        assertThat(q.getQuestionText()).isNull();
        assertThat(q.getAnswer()).isNull();
    }

    @Test
    @DisplayName("all-args constructor stores id, text and answer")
    void allArgsConstructor() {
        Question q = new Question(42, "What is OCSF?", "A client-server framework");

        assertThat(q.getId()).isEqualTo(42);
        assertThat(q.getQuestionText()).isEqualTo("What is OCSF?");
        assertThat(q.getAnswer()).isEqualTo("A client-server framework");
    }

    @Test
    @DisplayName("each setter changes only its own field")
    void settersAreIndependent() {
        Question q = new Question(1, "old text", "old answer");

        q.setId(9);
        assertThat(q.getId()).isEqualTo(9);
        assertThat(q.getQuestionText()).isEqualTo("old text");

        q.setQuestionText("new text");
        assertThat(q.getQuestionText()).isEqualTo("new text");
        assertThat(q.getAnswer()).isEqualTo("old answer");

        q.setAnswer("new answer");
        assertThat(q.getAnswer()).isEqualTo("new answer");
        assertThat(q.getId()).isEqualTo(9);
    }

    @Test
    @DisplayName("setters accept null (an answer may legitimately be unset)")
    void settersAcceptNull() {
        Question q = new Question(3, "text", "answer");

        q.setQuestionText(null);
        q.setAnswer(null);

        assertThat(q.getQuestionText()).isNull();
        assertThat(q.getAnswer()).isNull();
    }

    @Test
    @DisplayName("toString exposes all three fields for logs")
    void toStringContainsAllFields() {
        Question q = new Question(5, "Why?", "Because");

        assertThat(q.toString())
                .startsWith("Question{")
                .contains("id=5")
                .contains("questionText='Why?'")
                .contains("answer='Because'")
                .endsWith("}");
    }

    @Test
    @DisplayName("serialization round-trip preserves every field")
    void roundTripPreservesFields() throws Exception {
        Question restored = roundTrip(new Question(101, "1 + 1 = ?", "2"));

        assertThat(restored.getId()).isEqualTo(101);
        assertThat(restored.getQuestionText()).isEqualTo("1 + 1 = ?");
        assertThat(restored.getAnswer()).isEqualTo("2");
    }

    @Test
    @DisplayName("serialization round-trip preserves nulls and Hebrew text")
    void roundTripPreservesNullsAndUnicode() throws Exception {
        Question q = new Question();
        q.setId(-1);
        q.setQuestionText("מהי בירת צרפת?");

        Question restored = roundTrip(q);

        assertThat(restored.getId()).isEqualTo(-1);
        assertThat(restored.getQuestionText()).isEqualTo("מהי בירת צרפת?");
        assertThat(restored.getAnswer()).isNull();
    }

    @Test
    @DisplayName("a deserialized question is a distinct object, not a shared reference")
    void roundTripProducesACopy() throws Exception {
        Question original = new Question(7, "text", "answer");

        Question restored = roundTrip(original);

        assertThat(restored).isNotSameAs(original);
        assertThat(restored.toString()).isEqualTo(original.toString());
    }

    private static <T extends Serializable> T roundTrip(T original) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            @SuppressWarnings("unchecked")
            T restored = (T) in.readObject();
            return restored;
        }
    }
}
