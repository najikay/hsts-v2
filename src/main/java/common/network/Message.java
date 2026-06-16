package common.network;

import java.io.Serializable;

/**
 * Wire protocol envelope exchanged between the JavaFX client and the Fat Server
 * over OCSF (Common tier).
 *
 * <p>Implements {@link Serializable} so it (and its payload) can travel across
 * the OCSF object streams. A message carries a {@link Command} verb plus an
 * arbitrary {@code payload} — e.g. a {@code List<Question>} on a
 * {@code GET_ALL_QUESTIONS} response, or a single {@code Question} on an
 * {@code UPDATE_QUESTION} request. Whatever is placed in the payload MUST itself
 * be {@code Serializable}.
 */
public class Message implements Serializable {

    /** Keep stable so client and server stay wire-compatible. */
    private static final long serialVersionUID = 1L;

    /** The protocol verbs understood by both tiers. */
    public enum Command {
        // Client -> Server requests
        GET_ALL_QUESTIONS,
        UPDATE_QUESTION,

        // Server -> Client responses
        SUCCESS,
        ERROR
    }

    private Command command;
    private Object payload;

    public Message() {
    }

    public Message(Command command) {
        this.command = command;
    }

    public Message(Command command, Object payload) {
        this.command = command;
        this.payload = payload;
    }

    public Command getCommand() {
        return command;
    }

    public void setCommand(Command command) {
        this.command = command;
    }

    public Object getPayload() {
        return payload;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }

    @Override
    public String toString() {
        return "Message{command=" + command
                + ", payload=" + payload + '}';
    }
}
