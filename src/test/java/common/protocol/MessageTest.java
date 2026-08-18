package common.protocol;

import common.dto.bank.Question;
import common.protocol.Message.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the wire envelope {@link Message}.
 *
 * <p>The envelope is the one type both tiers must agree on, so the contract under
 * test is: construction/mutation semantics, a {@code toString} usable in logs,
 * and — most importantly — that it survives a Java serialization round-trip with
 * its payload intact (this is exactly what OCSF does on the socket).
 */
class MessageTest {

    @Test
    @DisplayName("no-arg constructor leaves command and payload unset")
    void defaultConstructorLeavesFieldsNull() {
        Message msg = new Message();

        assertThat(msg.getCommand()).isNull();
        assertThat(msg.getPayload()).isNull();
    }

    @Test
    @DisplayName("verb-only constructor sets the command and no payload")
    void commandOnlyConstructor() {
        Message msg = new Message(Command.GET_ALL_QUESTIONS);

        assertThat(msg.getCommand()).isEqualTo(Command.GET_ALL_QUESTIONS);
        assertThat(msg.getPayload()).isNull();
    }

    @Test
    @DisplayName("verb+payload constructor keeps both")
    void commandAndPayloadConstructor() {
        Question payload = new Question(7, "2 + 2 = ?", "4");

        Message msg = new Message(Command.UPDATE_QUESTION, payload);

        assertThat(msg.getCommand()).isEqualTo(Command.UPDATE_QUESTION);
        assertThat(msg.getPayload()).isSameAs(payload);
    }

    @Test
    @DisplayName("setters replace command and payload")
    void settersMutateBothFields() {
        Message msg = new Message(Command.GET_ALL_QUESTIONS, "first");

        msg.setCommand(Command.ERROR);
        msg.setPayload("boom");

        assertThat(msg.getCommand()).isEqualTo(Command.ERROR);
        assertThat(msg.getPayload()).isEqualTo("boom");

        msg.setPayload(null);
        assertThat(msg.getPayload()).isNull();
    }

    @Test
    @DisplayName("toString exposes command and payload for logs")
    void toStringContainsCommandAndPayload() {
        Message msg = new Message(Command.SUCCESS, "42 questions");

        assertThat(msg.toString())
                .startsWith("Message{")
                .contains("command=SUCCESS")
                .contains("payload=42 questions")
                .endsWith("}");
    }

    @Test
    @DisplayName("toString is null-safe on an empty envelope")
    void toStringHandlesNulls() {
        assertThat(new Message().toString()).isEqualTo("Message{command=null, payload=null}");
    }

    @ParameterizedTest
    @EnumSource(Command.class)
    @DisplayName("every verb survives a serialization round-trip")
    void everyCommandRoundTrips(Command command) throws Exception {
        Message restored = roundTrip(new Message(command));

        assertThat(restored.getCommand()).isEqualTo(command);
        assertThat(restored.getPayload()).isNull();
    }

    @Test
    @DisplayName("Command enum is addressable by name (protocol stability)")
    void commandEnumNamesAreStable() {
        assertThat(Command.values())
                .containsExactly(Command.GET_ALL_QUESTIONS, Command.UPDATE_QUESTION,
                        Command.SUCCESS, Command.ERROR);
        assertThat(Command.valueOf("SUCCESS")).isEqualTo(Command.SUCCESS);
    }

    @Test
    @DisplayName("a list payload survives the OCSF object-stream round-trip")
    void listPayloadRoundTrips() throws Exception {
        List<Question> questions = List.of(
                new Question(1, "Capital of France?", "Paris"),
                new Question(2, "√81 = ?", "9"));

        Message restored = roundTrip(new Message(Command.SUCCESS, questions));

        assertThat(restored.getCommand()).isEqualTo(Command.SUCCESS);
        assertThat(restored.getPayload()).isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        List<Question> payload = (List<Question>) restored.getPayload();
        assertThat(payload).hasSize(2);
        assertThat(payload.get(0).getQuestionText()).isEqualTo("Capital of France?");
        assertThat(payload.get(1).getAnswer()).isEqualTo("9");
    }

    @Test
    @DisplayName("non-ASCII payload text survives the round-trip (Hebrew UI requirement)")
    void unicodePayloadRoundTrips() throws Exception {
        Message restored = roundTrip(new Message(Command.ERROR, "שגיאת שרת"));

        assertThat(restored.getPayload()).isEqualTo("שגיאת שרת");
    }

    /** Serializes and deserializes through real object streams, exactly as OCSF does. */
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
