package client.features.bot;

import common.dto.bot.BotSourceKind;

/**
 * Every word the study bot's screens put on a user's screen (Presentation tier,
 * E16.12-E16.15 — PRD §4.1).
 *
 * <p>One class, for the reason {@code LockCopy} and {@code ExamMessages} are one
 * class each: the copy rules are checkable by a single test instead of by reading
 * four views, and a sentence cannot quietly diverge between the screen that shows
 * it and the test that asserts it.
 *
 * <p>Two rules bind everything here, and {@code BotCopyTest} enforces both. <b>No
 * em dashes</b> (PRD §4.1). And <b>every message that reports a problem says what
 * to do next</b> — a dead end on a student's screen at eleven at night is the
 * difference between a feature and a complaint.
 *
 * <p>Server-produced sentences are not duplicated here. A refusal from
 * {@code BotMessages} is rendered as it arrives, because two copies of one
 * sentence is one copy too many and the server's is the one the requirement is
 * checked against.
 */
public final class BotCopy {

    private BotCopy() {
        // static copy - no instances
    }

    // ===================== Chat (E16.13) =================================

    /** The chat screen's title. */
    public static final String CHAT_TITLE = "Study bot";

    /**
     * The one line that says what this screen is (UI wave 1, F-14).
     *
     * <p>A student arriving here saw a title, a course name and an empty box, and
     * nothing on the screen said what the thing in the box knows or whether a
     * teacher reads it. Both are answered in one sentence, and the second half is
     * the one that changes behaviour: a student who suspects a teacher is reading
     * asks nothing.
     */
    public static final String CHAT_EXPLAINER =
            "Ask about this course and the bot answers from the material your teacher "
                    + "uploaded. Your questions are not shown to your teacher.";

    /** The input's placeholder. */
    public static final String ASK_PLACEHOLDER = "Ask about anything in this course";

    /** The send button. */
    public static final String SEND = "Send";

    /** The typing indicator's accessible label, and its tooltip. */
    public static final String THINKING = "The bot is thinking";

    /** The empty state on a brand new conversation. */
    public static final String CHAT_EMPTY_TITLE = "Ask your first question";

    /** Its hint, which doubles as an example of what this bot is for. */
    public static final String CHAT_EMPTY_HINT =
            "Type a question below and press send. The bot explains topics, works through "
                    + "an example and answers from your course material.";

    /** A network or server failure on an ask; the question is still in the box. */
    public static final String ASK_FAILED =
            "That question did not reach the server. Check your connection and send it again.";

    /** The banner title when the bot cannot be used at all right now. */
    public static final String UNAVAILABLE_TITLE = "This bot is not available";

    /** The button that opens the history screen from the chat. */
    public static final String OPEN_HISTORY = "Past conversations";

    /** The button that abandons the current conversation and starts a fresh one. */
    public static final String NEW_CONVERSATION = "New conversation";

    // ===================== The C-4 integrity notice (E16.13) =============

    /** The confirmation dialog's title; a statement of fact, not an accusation. */
    public static final String INTEGRITY_TITLE = "You are taking an exam";

    /** Its confirm button. */
    public static final String INTEGRITY_CONFIRM = "Continue and notify";

    /** Its cancel button. */
    public static final String INTEGRITY_CANCEL = "Not now";

    /** The line under the notice that says exactly what gets reported. */
    public static final String INTEGRITY_DETAIL =
            "Your exam's teacher will see that you used this bot, and when. "
                    + "Nothing you ask or the bot answers is shared.";

    // ===================== History (E16.14) ==============================

    /** The history screen's title. */
    public static final String HISTORY_TITLE = "Bot history";

    /** What the history screen is for (UI wave 1, F-14). */
    public static final String HISTORY_EXPLAINER =
            "Every conversation you have had with this course's bot. Reopen one to keep going.";

    /** Its empty state. */
    public static final String HISTORY_EMPTY_TITLE = "No conversations yet";

    /** Its empty-state hint. */
    public static final String HISTORY_EMPTY_HINT =
            "Conversations you have with this course's study bot are saved here, "
                    + "so you can pick one up where you left it.";

    /** The button on a history row. */
    public static final String REOPEN = "Reopen";

    /** Shown while a reopened conversation is being fetched. */
    public static final String REOPENING = "Opening that conversation";

    /** A failed history fetch. */
    public static final String HISTORY_FAILED =
            "Your conversations could not be loaded. Check your connection and try again.";

    // ===================== Manager (E16.12) ==============================

    /** The manager screen's title. */
    public static final String MANAGER_TITLE = "Bot manager";

    /**
     * What the manager screen is for (UI wave 1, F-14).
     *
     * <p>The screen showed a course name and a table of sources with no statement
     * of what a source does, so "add a file" read as an upload with no consequence.
     * Naming the consequence is what makes the table worth filling.
     */
    public static final String MANAGER_EXPLAINER =
            "Your course's study bot answers students from the material you add here. "
                    + "Switch it on when you are ready for students to use it.";

    /** The empty state for a course with no bot. */
    public static final String NO_BOT_TITLE = "This course has no study bot";

    /** Its hint. */
    public static final String NO_BOT_HINT =
            "Use \"Create the study bot\" below, then add the material it should answer "
                    + "from. Students see it as soon as it is switched on.";

    /** The create button. */
    public static final String CREATE_BOT = "Create the study bot";

    /** The active toggle's label. */
    public static final String ACTIVE_LABEL = "Students can use this bot";

    /** The sources table's title. */
    public static final String SOURCES_TITLE = "Information sources";

    /** The sources table's empty state. */
    public static final String SOURCES_EMPTY_TITLE = "No sources yet";

    /** Its hint, which names the three kinds F12.2 allows plus the bank. */
    public static final String SOURCES_EMPTY_HINT =
            "Add a PDF, a Word file or some typed text. The bot also uses this "
                    + "course's question bank without you adding anything.";

    /** The add-file button. */
    public static final String ADD_FILE = "Add a file";

    /** The add-text button. */
    public static final String ADD_TEXT = "Add text";

    /**
     * The edit button on a free-text source row ⚑ (F12.3, B-21).
     *
     * <p>Offered on typed sources only. A PDF or a Word row has no body this screen can open —
     * what it holds is the parse, not the document — so those keep Remove and Add rather than
     * an affordance that would promise in-place editing and then hand over a file chooser.
     */
    public static final String EDIT = "Edit";

    /** The edit dialog's title. */
    public static final String EDIT_TEXT_TITLE = "Edit this source";

    /**
     * Its explanation.
     *
     * <p>Says the two things that make an edit different from a delete and a re-add, which is
     * exactly what a teacher who has been doing the latter needs to know.
     */
    public static final String EDIT_TEXT_EXPLANATION =
            "Change the text the bot answers from. The source keeps its place in the list and "
                    + "your co-teachers will be told it changed.";

    /** Its confirm button. */
    public static final String EDIT_CONFIRM = "Save changes";

    /** The remove button on a source row. */
    public static final String REMOVE = "Remove";

    /** The remove confirmation's title. */
    public static final String REMOVE_TITLE = "Remove this source?";

    /** Its explanation. */
    public static final String REMOVE_EXPLANATION =
            "The bot will stop answering from this material. Your co-teachers will be told.";

    /** Its confirm button. */
    public static final String REMOVE_CONFIRM = "Remove it";

    /** Its cancel button. */
    public static final String REMOVE_CANCEL = "Keep it";

    /** Shown while an upload is being parsed on the server. */
    public static final String UPLOADING = "Reading that file on the server";

    /** A failed manager fetch. */
    public static final String MANAGER_FAILED =
            "The bot could not be loaded. Check your connection and try again.";

    /** What the lock banner calls the thing being edited (E18.5). */
    public static final String SOURCE_NOUN = "bot source";

    // ===================== Analytics (E16.15) ============================

    /** The analytics screen's title. */
    public static final String ANALYTICS_TITLE = "Bot activity";

    /**
     * What the analytics screen is for (UI wave 1, F-14).
     *
     * <p>Says what the numbers are <i>about</i> before the anonymity note says what
     * they are not. A teacher who reads "anonymous" first spends a moment working
     * out what was being claimed.
     */
    public static final String ANALYTICS_EXPLAINER =
            "What students are asking this course's bot, so you can see which topics "
                    + "they are stuck on.";

    /**
     * The line that tells the teacher, unprompted, that this view is anonymous.
     *
     * <p>Shown always, not only when there is data. S-34 is a promise to students,
     * and a promise nobody is told about is worth less than one that is stated on
     * the screen where it applies.
     */
    public static final String ANONYMOUS_NOTE =
            "These numbers are anonymous. This view never shows who asked what.";

    /** The total-questions stat card. */
    public static final String TOTAL_QUESTIONS = "Questions asked";

    /** The busiest-day stat card. */
    public static final String BUSIEST_DAY = "Busiest day";

    /** The frequent-questions list's heading. */
    public static final String FREQUENT_TITLE = "Asked most often";

    /** The activity chart's heading. */
    public static final String ACTIVITY_TITLE = "Questions over the last 30 days";

    /** The analytics empty state. */
    public static final String ANALYTICS_EMPTY_TITLE = "Nobody has used this bot yet";

    /** Its hint. */
    public static final String ANALYTICS_EMPTY_HINT =
            "Once students start asking questions, you will see how many and about what.";

    /** A failed analytics fetch. */
    public static final String ANALYTICS_FAILED =
            "The activity could not be loaded. Check your connection and try again.";

    // ===================== Shared ========================================

    /**
     * @param kind a source kind
     * @return the Ikonli icon literal the sources table shows for it
     */
    public static String iconFor(BotSourceKind kind) {
        return switch (kind) {
            case PDF -> "mdoal-picture_as_pdf";
            case DOCX -> "mdoal-description";
            case TEXT -> "mdoal-short_text";
        };
    }
}
