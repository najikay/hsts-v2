package common.dto;

import java.io.Serializable;

/**
 * The payload of every {@link common.protocol.Status#ERROR} message: one
 * human-readable sentence for the UI (Common tier).
 *
 * <p>The machine-readable half lives in {@link common.protocol.ErrorCode} on the
 * envelope. This text is written for the person in front of the screen — it
 * never carries exception types, stack traces, SQL or class names.
 *
 * @param message user-facing explanation; normalised to {@code ""} when absent
 *                so client code never has to null-check before rendering it
 */
public record ErrorPayload(String message) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ErrorPayload {
        message = message == null ? "" : message;
    }
}
