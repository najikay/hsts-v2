package server.features.bot;

/**
 * Every sentence the study bot's server sends a human (Logic tier, E16.8/E16.9 —
 * PRD §4.1).
 *
 * <p>One class for the same three reasons {@code ExamMessages} and
 * {@code NotificationCatalog} exist: the copy rules are checkable by one test
 * rather than by reading eight services, a refusal and the screen that renders it
 * cannot drift apart, and the wording is reviewed once instead of once per
 * handler.
 *
 * <p>Two rules bind every string here. <b>No em dashes</b>, because PRD §4.1 says
 * they read unnatural in an app. And <b>every error says what to do next</b> —
 * which is the harder one, and the reason several of these sentences are longer
 * than they would otherwise be. "You cannot use this bot" is a dead end; "This
 * study bot is switched off right now. Ask your teacher when it will be back."
 * tells a student what her next move is.
 *
 * <h2>The lockout sentence, and the clock it deliberately does not show</h2>
 *
 * <p>{@link #lockedOut} names the exam and says the lock lifts when she hands that
 * exam in. It does <em>not</em> print a wall-clock unlock time, and that is a
 * decision rather than an omission: the only deadline this feature could reach is
 * the one captured when the attempt started, and a teacher granting extra time
 * (F7.1, S-20) moves the real one without moving that copy. A stale time on this
 * screen would be worse than no time, because a student would plan around it, come
 * back, and find herself still locked with no explanation. "As soon as you hand
 * that exam in" is true at every moment of the attempt, extension or not.
 */
public final class BotMessages {

    private BotMessages() {
        // static copy - no instances
    }

    // ===================== Student refusals ==============================

    /** Not enrolled in the course whose bot she asked for (S-31). */
    public static final String NOT_ENROLLED =
            "You are not enrolled in this course, so you cannot use its study bot. "
                    + "Check that you opened the right course, or ask the school office.";

    /** The course has no bot at all yet (F12.1). */
    public static final String NO_BOT =
            "This course does not have a study bot yet. Your teacher can add one, "
                    + "so it is worth asking.";

    /** The bot exists but the teacher has switched it off (F12.4, S-31). */
    public static final String BOT_INACTIVE =
            "This study bot is switched off right now. Ask your teacher when it will be back on.";

    /** The student sent an empty question. */
    public static final String QUESTION_EMPTY =
            "Type a question first, then send it.";

    /** The student sent something far too long to be a question. */
    public static final String QUESTION_TOO_LONG =
            "That message is too long for the bot to read. Ask one shorter question at a time.";

    /** The per-minute rate limit tripped (E16.8). */
    public static final String TOO_FAST =
            "You are sending questions faster than the bot can answer them. "
                    + "Wait a moment and send that one again.";

    /** A session id that is not the caller's, or does not exist (F12.10). */
    public static final String SESSION_NOT_FOUND =
            "That conversation could not be found. Open one from your bot history instead.";

    /** A malformed payload, which is a client bug rather than a user mistake. */
    public static final String MALFORMED_REQUEST =
            "The app sent something the server did not understand. Try again, "
                    + "and tell your teacher if it keeps happening.";

    // ===================== Teacher refusals ==============================

    /** The caller is a teacher, but not of this course (P-5). */
    public static final String NOT_YOUR_COURSE =
            "You do not teach this course, so you cannot manage its study bot. "
                    + "Open a course you teach.";

    /** A teacher verb naming a course that does not exist. */
    public static final String NO_SUCH_COURSE =
            "That course could not be found. Choose one of your courses and try again.";

    /** A management verb on a course whose bot has not been created (F12.1). */
    public static final String BOT_NOT_CREATED =
            "This course has no study bot yet. Create one, then add its information sources.";

    /** A source add with nothing in it. */
    public static final String SOURCE_INCOMPLETE =
            "Give the source a title and choose a file or type some text, then add it again.";

    /** A source add over the size ceiling. */
    public static final String SOURCE_TOO_LARGE =
            "That file is too large to upload. Split it into smaller files, or paste the part "
                    + "the students need as free text.";

    /** A source id that does not belong to this course's bot. */
    public static final String SOURCE_NOT_FOUND =
            "That source could not be found. It may already have been removed by a colleague. "
                    + "Reload the page to see the current list.";

    /** Somebody else holds the advisory edit lock on this source (F10.4, E18.5). */
    public static final String SOURCE_LOCKED =
            "Another teacher is editing this source right now. Wait for them to finish, "
                    + "or take over the edit from the banner.";

    // ===================== Composed sentences ============================

    /**
     * The same refusal, naming the colleague who is holding it (F10.4, B-21).
     *
     * <p>Used by {@code BOT_SOURCE_UPDATE}, where the lock is consulted after the scope check
     * and the caller is therefore already known to teach the course — so telling her whose
     * edit is in the way gives away nothing she could not see on the page, and turns a wall
     * into a person she can go and ask. {@link #SOURCE_LOCKED} is the fallback for the case
     * where the lock service cannot say who it is.
     *
     * @param holderName the other teacher's display name
     * @return the sentence
     */
    public static String sourceLockedBy(String holderName) {
        return blankTo(holderName, "Another teacher") + " is editing this source right now. "
                + "Wait for them to finish, or take over the edit from the banner.";
    }

    /**
     * The C-4 same-course lockout (F6.8, ADR-018).
     *
     * @param courseName the course whose bot she asked for
     * @param examName   the exam she is sitting right now
     * @return a sentence naming both, and when the lock lifts
     */
    public static String lockedOut(String courseName, String examName) {
        String course = blankTo(courseName, "This course");
        String exam = blankTo(examName, "your exam");
        return "The " + course + " study bot is locked while you are taking " + exam + ". "
                + "It unlocks as soon as you hand that exam in.";
    }

    /**
     * The C-4 cross-course integrity notice, shown once per attempt before the ask
     * proceeds (F6.8, ADR-018).
     *
     * <p>Worded as a fact and a choice, not as an accusation. Using another
     * course's bot mid-exam is allowed — the specification does not forbid it and
     * neither do we — so the sentence says plainly what will happen if she
     * continues and leaves the decision with her. The teacher gets a
     * "check an attempt" notification, not a cheating report, for the same reason.
     *
     * @param courseName the course whose bot she is opening
     * @return the notice
     */
    public static String integrityNotice(String courseName) {
        String course = blankTo(courseName, "another course");
        return "You are taking an exam right now. You can still use the " + course
                + " study bot, but the teacher running your exam will be told that you did. "
                + "Continue only if you meant to.";
    }

    /**
     * The confirmation the sources table shows after a successful upload.
     *
     * @param title      what the source was called
     * @param characters how much text came out of it
     * @return one short line
     */
    public static String sourceAdded(String title, int characters) {
        return "Added " + title + " with " + characters + " characters of text.";
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
