package server.db.projections;

/**
 * One study-bot source's extracted text, without its bytes (E2.11 / E16.6).
 *
 * <p>What the prompt context builder reads. It needs the text and the title (the
 * title labels the fenced block the model sees) and nothing else — in particular
 * not the original PDF, which is several megabytes of no use to a prompt.
 *
 * <p>Deliberately not {@code Serializable}: extracted course material is server
 * -side working data. The student sees the bot's answer, not the library.
 *
 * @param sourceId the {@code bot_sources} row, for logging which material was used
 * @param title    what the teacher called it
 * @param text     the extracted, normalised text
 */
public record BotSourceText(long sourceId, String title, String text) {

    public BotSourceText {
        title = title == null ? "" : title;
        text = text == null ? "" : text;
    }
}
