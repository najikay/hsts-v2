package common.dto.bot;

/**
 * Who said one line of a study-bot conversation (Common tier, E16.11 — S-33).
 *
 * <p>An enum rather than the raw {@code "student"} / {@code "bot"} strings the
 * {@code bot_sessions.transcript} JSON stores. The column is a JSON document that
 * has to stay readable to anything that opens the database, so it keeps the
 * strings; the wire is a typed contract between two of our own JARs, so it gets a
 * type the compiler can check. {@link #wireName()} is the one place the two
 * vocabularies meet.
 */
public enum BotSpeaker {

    /** The student asking. */
    STUDENT("student"),

    /** The bot answering. */
    BOT("bot");

    private final String wireName;

    BotSpeaker(String wireName) {
        this.wireName = wireName;
    }

    /** @return the lower-case name used inside the stored transcript JSON. */
    public String wireName() {
        return wireName;
    }

    /**
     * @param wireName a role as stored in a transcript
     * @return the matching speaker, defaulting to {@link #BOT} for anything
     *         unrecognised. A transcript row written by an older build must render
     *         as somebody rather than break a student's history screen
     */
    public static BotSpeaker fromWireName(String wireName) {
        return STUDENT.wireName.equalsIgnoreCase(wireName) ? STUDENT : BOT;
    }
}
