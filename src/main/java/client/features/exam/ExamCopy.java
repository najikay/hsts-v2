package client.features.exam;

import common.dto.exam.AttemptOutcome;
import common.dto.exam.AttemptState;
import common.dto.exam.TimerExtended;
import common.protocol.ErrorCode;

import java.time.Duration;
import java.time.Instant;

/**
 * Every sentence the take-exam screens say (Presentation tier, E10.9–E10.14 — PRD §4.1).
 *
 * <p>In one class for the same reason {@code NotificationCatalog} is: one test can then
 * check the whole feature against the copy rules — <b>no em dashes anywhere</b>, sentence
 * case, and every message says what to do next. A student sitting an exam is the least
 * patient reader in the product and the one with the most to lose from a dead end.
 *
 * <h2>Server sentences are used, not restated</h2>
 *
 * <p>The four entry errors (wrong code, not open, not enrolled, wrong id) are decided by
 * the server and arrive with their own text. This class does <b>not</b> keep a second copy
 * of them: {@link #serverMessage(ErrorCode, String, String)} prefers what the server said
 * and falls back only when there is nothing usable, which happens when the connection
 * failed rather than the request. Two copies of the same sentence in two tiers is exactly
 * how one of them ends up wrong.
 *
 * <p>What lives here is what only the client knows: local validation before anything is
 * sent, the autosave indicator, the submit confirmation, and the two ending screens.
 */
public final class ExamCopy {

    private ExamCopy() {
    }

    // ===================== Entry (E10.9) =================================

    /** Heading of the code screen. */
    public static final String CODE_TITLE = "Enter your exam code";

    /** Explanation under it. */
    public static final String CODE_SUBTITLE =
            "Your teacher reads the code out at the start of the exam.";

    /** Label of the code field. */
    public static final String CODE_LABEL = "Exam code";

    /** The helper line under the code field; hidden when the step is confirming a code. */
    public static final String CODE_HINT = "Your teacher reads it out at the start of the exam.";

    /** Local rule, checked before anything is sent. */
    public static final String CODE_INVALID = "Codes are 4 letters or digits.";

    /**
     * Heading of the code step when the dashboard already handed one over ⚑.
     *
     * <p>2026-08-28, manual round 1, lead's ruling: a student who pressed her own exam card
     * was then asked to type the code printed on it. The step is not removed, because the
     * join still has to happen and its refusals still have to land somewhere, but it becomes
     * what it actually is: a confirmation of the code she chose, not a question.
     */
    public static final String CONFIRM_TITLE = "Confirm your exam";

    /** The confirming button, which sends the same join as Continue does. */
    public static final String CONFIRM_BUTTON = "Confirm and continue";

    /** The way out of the confirmation, for the card that was pressed by mistake. */
    public static final String DIFFERENT_CODE = "Use a different code";

    /**
     * What the identity step's back control returns to, named in its tooltip ⚑.
     *
     * <p>2026-08-29, manual round 2, lead's ruling: every child step of Take Exam owes a way
     * back, and this one is the only step that has somewhere to go back <em>to</em> rather
     * than out of. The control itself reads {@code BackLink.LABEL}, which is the one word the
     * convention allows; this is the destination the tooltip names, so it is discoverable
     * without the label promising it.
     */
    public static final String BACK_TO_CODE_TARGET = "the code step";

    /** Heading of the identity screen. */
    public static final String ID_TITLE = "Confirm it is you";

    /** Explanation under it, which is also the warning that the clock is about to start. */
    public static final String ID_SUBTITLE =
            "Enter your own ID number. Your time starts as soon as you continue.";

    /** Label of the identity field. */
    public static final String ID_LABEL = "ID number";

    /** Local rule: nothing typed. */
    public static final String ID_REQUIRED = "Enter your ID number to continue.";

    /** Button that starts the attempt. */
    public static final String START_BUTTON = "Start exam";

    /** Button that fetches the header. */
    public static final String CODE_BUTTON = "Continue";

    /** Shown when the round trip itself failed rather than the request being refused. */
    public static final String OFFLINE =
            "Could not reach the server. Check your connection and try again.";

    // ===================== The form (E10.10/E10.11) ======================

    /** The autosave indicator at rest, when everything is stored. */
    public static final String SAVED_INDICATOR = "All changes saved";

    /** The autosave indicator while a write is in flight. */
    public static final String SAVING_INDICATOR = "Saving";

    /** The autosave indicator after a failed write; it retries on the next change. */
    public static final String SAVE_FAILED_INDICATOR = "Not saved yet, retrying";

    /** Title of the question navigator strip. */
    public static final String NAVIGATOR_TITLE = "Questions";

    /** Empty state, for the paper that somehow has no questions. */
    public static final String NO_QUESTIONS =
            "This exam has no questions yet. Tell your teacher before the time runs out.";

    // ===================== Submit (E10.13) ===============================

    /** Title of the submit confirmation (F6.9). */
    public static final String SUBMIT_TITLE = "Hand this exam in?";

    /** The confirming button; a verb, never "OK". */
    public static final String SUBMIT_CONFIRM = "Hand in";

    /** The dismissing button. */
    public static final String SUBMIT_CANCEL = "Keep working";

    /** The consequence line for a paper with blanks. */
    public static final String SUBMIT_UNANSWERED_NOTE =
            "Unanswered questions score 0. You cannot change your answers after handing in.";

    /** The consequence line for a complete paper. */
    public static final String SUBMIT_COMPLETE_NOTE =
            "You have answered every question. You cannot change your answers after handing in.";

    // ===================== Endings (E10.13/E10.14) =======================

    /** Title of the Submitted screen (F6.10). */
    public static final String SUBMITTED_TITLE = "Handed in";

    /** Title of the Time Up takeover (F6.4). */
    public static final String TIMED_OUT_TITLE = "Time is up";

    /** What the takeover explains: it already happened, there is nothing to confirm. */
    public static final String TIMED_OUT_SUBTITLE =
            "Your exam was handed in automatically with the answers you had saved.";

    /** What the Submitted screen explains. */
    public static final String SUBMITTED_SUBTITLE =
            "Your answers are with your teacher. You will be told when the grade is ready.";

    /**
     * The way off this screen entirely: the single action on both ending screens, and, since
     * 2026-08-29 (manual round 2), the way out of the code step and the handed-in dead end.
     *
     * <p>One sentence for all four, because it is one destination and a second wording would
     * only make the reader wonder what the difference was.
     */
    public static final String BACK_TO_DASHBOARD = "Back to my dashboard";

    /** Shown if a student navigates back to a finished exam (the route guard, E10.14). */
    public static final String EXAM_CLOSED_FOR_YOU =
            "That exam is finished. Your grade appears on your dashboard once it is approved.";

    // ===================== Reconnect (E10.15) ============================

    /** The reconnect banner's line while the socket is down mid-exam. */
    public static final String RECONNECTING =
            "Reconnecting. Your saved answers are safe and your time is still running.";

    /** Toast raised once the connection is back and the paper has been resynced. */
    public static final String RECONNECTED = "Back online, your answers are up to date";

    // ===================== Monitor (E11.2, U-1) ==========================

    /**
     * The monitor's title when it is opened without a sitting (U-1).
     *
     * <p>The rail item that arrived in U-1 has nothing to hand this screen: a rail is a list of
     * places, not a list of sittings, and the sitting is chosen on Releases. So the paramless
     * entry is a designed state rather than a request for execution zero, which would have put
     * the server's "no such execution" refusal on screen as the first thing a teacher saw after
     * clicking her own menu.
     */
    public static final String MONITOR_NO_SITTING_TITLE = "Pick a sitting to watch";

    /** Its hint, which names the place the choice is actually made. */
    public static final String MONITOR_NO_SITTING_HINT =
            "Releases lists every exam you have scheduled. Open one that is running and its "
                    + "Monitor button brings you back here with it.";

    /** The action on that state; the one thing to do about it. */
    public static final String MONITOR_NO_SITTING_ACTION = "Open Releases";

    // ===================== Composed sentences ============================

    /**
     * The progress line under the header (E10.10).
     *
     * @param answered how many carry a choice
     * @param total    the paper's length
     * @return "Answered 7 of 20"
     */
    public static String progress(int answered, int total) {
        return "Answered " + answered + " of " + total;
    }

    /**
     * The confirmation sentence under {@link #CONFIRM_TITLE} ⚑.
     *
     * <p>Says the code back to her, because the whole point of the confirming variant is that
     * she can see she is about to enter the exam she meant to. The code is the only thing she
     * could have got wrong at this step, so it is the only thing the sentence carries.
     *
     * @param code the code the dashboard handed over
     * @return "You are about to enter the exam with code 2075."
     */
    public static String confirmSubtitle(String code) {
        return "You are about to enter the exam with code " + (code == null ? "" : code.trim())
                + ".";
    }

    /**
     * The entry sentence for a sitting the window cuts short ⚑ (B-14 — F6.1).
     *
     * <p>Two facts and no hedging: when this sitting ends, and how long that leaves her. It
     * is said <em>before</em> she confirms her identity, because that is the moment the clock
     * starts and the last moment the information is any use to her.
     *
     * <p>Before B-14 nothing said it at all. A student who joined legally, two minutes before
     * a window shut, was told she had the paper's full seventy-five minutes, and her attempt
     * was force-submitted at the bell with her own countdown still running — promised
     * seventy-five, given two, and told neither. The server now derives every deadline as the
     * earlier of the two clocks; this is the half that tells her.
     *
     * <p>The time is rendered in her own zone, like every other time on these screens.
     *
     * @param closesAt when the execution's window shuts
     * @param minutes  the minutes she really gets, already computed by the server
     * @return "This sitting closes at 13:00. You have 26 minutes."
     */
    public static String sittingShortened(Instant closesAt, int minutes) {
        return "This sitting closes at " + ExamClock.localTime(closesAt) + ". You have "
                + minutes(Math.max(0, minutes)) + ".";
    }

    /**
     * The remaining-time note in the submit dialog (F6.9).
     *
     * @param remaining what the countdown says
     * @return a sentence naming the time she is giving up
     */
    public static String remainingNote(Duration remaining) {
        return "You still have " + ExamClock.words(remaining) + " left.";
    }

    /**
     * The unanswered warning in the submit dialog.
     *
     * @param unanswered how many are blank
     * @return the consequence line for that paper
     */
    public static String submitNote(int unanswered) {
        return unanswered == 0 ? SUBMIT_COMPLETE_NOTE : SUBMIT_UNANSWERED_NOTE;
    }

    /**
     * The Time Extended toast (F7.1 ⚑).
     *
     * <p>Names the source and the consequence, which is the whole requirement: a student
     * must know that it happened, who did it, and when the exam now ends.
     *
     * @param extension what the server pushed
     * @return "Dana Cohen added 15 minutes · new end 11:45"
     */
    public static String extensionToast(TimerExtended extension) {
        return extension.teacherName() + " added " + minutes(extension.extraMinutes())
                + " · new end " + ExamClock.localTime(extension.timing().endsAt());
    }

    /** Title of the Time Extended toast. */
    public static final String EXTENSION_TOAST_TITLE = "Extra time added";

    /**
     * The summary line on both ending screens.
     *
     * @param outcome what the server handed in
     * @return "Handed in at 11:42 · 43 minutes · 18 of 20 answered"
     */
    public static String outcomeSummary(AttemptOutcome outcome) {
        return "Handed in at " + ExamClock.localTime(outcome.endedAt())
                + " · " + minutes(outcome.solvingMinutes())
                + " · " + outcome.answeredCount() + " of " + outcome.questionCount() + " answered";
    }

    /**
     * The heading for whichever ending happened.
     *
     * @param state the terminal state
     * @return the title
     */
    public static String endingTitle(AttemptState state) {
        return state == AttemptState.TIMED_OUT ? TIMED_OUT_TITLE : SUBMITTED_TITLE;
    }

    /**
     * The explanation for whichever ending happened.
     *
     * @param state the terminal state
     * @return the subtitle
     */
    public static String endingSubtitle(AttemptState state) {
        return state == AttemptState.TIMED_OUT ? TIMED_OUT_SUBTITLE : SUBMITTED_SUBTITLE;
    }

    /**
     * Picks the sentence to show for a refused or failed request.
     *
     * <p>The server's own text wins whenever there is any, because the server is the tier
     * that knows <em>why</em>: it can tell "no such code" from "that exam closed" and this
     * class cannot. The fallback is for the case where no answer arrived at all.
     *
     * @param code     the error code, or {@code null} when the round trip failed
     * @param message  the server's sentence, possibly {@code null} or blank
     * @param fallback what to say when there is nothing usable
     * @return the sentence to show
     */
    public static String serverMessage(ErrorCode code, String message, String fallback) {
        if (message != null && !message.isBlank()) {
            return message;
        }
        return code == null ? OFFLINE : fallback;
    }

    /** Keeps "1 minute" from reading as "1 minutes". */
    static String minutes(int count) {
        return count + (count == 1 ? " minute" : " minutes");
    }

    /**
     * @param at an instant
     * @return the time of day a student sees, in her own zone
     */
    public static String at(Instant at) {
        return ExamClock.localTime(at);
    }
}
