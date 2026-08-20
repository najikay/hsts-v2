package server.features.exam;

import server.db.entities.ExecutionStatus;

/**
 * Every sentence take-exam is allowed to say to a student (Logic tier, E10.9 — PRD §4.1).
 *
 * <p>Written once, here, for the same three reasons {@code NotificationCatalog} exists:
 * the copy rules are then enforceable by one test (no em dashes, sentence case, and
 * <b>every error says what to do next</b>), the wording is reviewed once rather than eight
 * times, and the client's four distinct entry errors have a single source of truth on the
 * side that decides them.
 *
 * <h2>The four entry errors, and why they are four</h2>
 *
 * <p>PRD §6 asks for distinct messages for "wrong code", "before open", "after close",
 * "not enrolled" and "wrong id", and v1's single "cannot start exam" is exactly the sort of
 * dead end §4.1 forbids. A student in an exam hall with a wrong message loses minutes she
 * cannot get back, so each of these names the actual problem and the actual next step:
 * check the code, wait, ask the teacher, or try her own number.
 *
 * <p>They ride on distinct {@code ErrorCode}s too ({@code NOT_FOUND} / {@code CONFLICT} /
 * {@code FORBIDDEN} / {@code VALIDATION}), so a client can branch on the code and does not
 * have to match on text.
 */
public final class ExamMessages {

    private ExamMessages() {
    }

    // ===================== Entry: the code screen ========================

    /** Malformed input, caught before any read. */
    public static final String CODE_MALFORMED =
            "An exam code is 4 letters or digits. Check the code your teacher read out and try again.";

    /** No execution has ever used this code. */
    public static final String CODE_UNKNOWN =
            "No exam is using that code. Check the code with your teacher and try again.";

    /** The code is real, but its execution has not opened yet. */
    public static final String CODE_NOT_OPEN_YET =
            "That exam has not started yet. Wait for your teacher to tell you it is open, "
                    + "then enter the code again.";

    /** The code is real, but its execution has closed or was cancelled. */
    public static final String CODE_CLOSED =
            "That exam is no longer open. If you think this is wrong, speak to your teacher.";

    /** The code is live, but this student is not on the course. */
    public static final String NOT_ENROLLED =
            "You are not enrolled in this course, so you cannot sit this exam. "
                    + "Speak to your teacher if that is wrong.";

    // ===================== Entry: the identity screen ====================

    /** Nothing typed. */
    public static final String ID_MISSING =
            "Enter your ID number to start the exam.";

    /** Typed, but not this student's own. */
    public static final String ID_MISMATCH =
            "That ID number is not yours. Enter your own ID number and try again.";

    // ===================== During the exam ===============================

    /** An attempt id that is not the caller's, or does not exist. */
    public static final String ATTEMPT_UNKNOWN =
            "That exam is not open for you. Go back to your dashboard and enter the code again.";

    /** A save or submit for an attempt the student already handed in. */
    public static final String ALREADY_SUBMITTED =
            "You have already handed this exam in. Your teacher will publish the grade.";

    /** A save arriving after the deadline (E10.8 ⚑). */
    public static final String TIME_IS_UP =
            "Time is up. Your exam was handed in automatically with the answers you had saved.";

    /** A selection outside 1..4, or a question that is not on this paper. */
    public static final String ANSWER_INVALID =
            "That answer could not be saved. Pick one of the four options and try again.";

    /** A question id that is not on this paper. */
    public static final String QUESTION_NOT_ON_PAPER =
            "That question is not part of this exam. Reload the exam and try again.";

    /** Payload of the wrong type, i.e. a client bug or a hostile peer. */
    public static final String MALFORMED_REQUEST =
            "That request could not be read. Reload the exam and try again.";

    // ===================== Extension and monitoring ======================

    /** Zero, negative or absurd extension. */
    public static final String EXTENSION_INVALID =
            "Enter how many minutes to add, between 1 and "
                    + common.dto.exam.ExtendTimeRequest.MAX_MINUTES + ".";

    /** Extending something that is not running (§6: "extend after close blocked"). */
    public static final String EXTENSION_NOT_LIVE =
            "Only a live exam can be extended. This one is not running.";

    /** The caller teaches, but not this execution. */
    public static final String NOT_YOUR_EXECUTION =
            "This exam is not yours to manage. Ask the teacher who released it.";

    /** Two teachers extending at the same moment; the stale one is told to look again. */
    public static final String EXTENSION_RACED =
            "Someone else changed this exam a moment ago. Open it again to see the current time.";

    /** An execution id nobody has. */
    public static final String EXECUTION_UNKNOWN =
            "That exam could not be found. Go back to your releases and open it again.";

    // ===================== Helpers =======================================

    /**
     * The right entry message for an execution that exists but cannot be joined.
     *
     * @param status what state it is in
     * @param openYet whether its opening moment has passed
     * @return the sentence to send
     */
    public static String notJoinable(ExecutionStatus status, boolean openYet) {
        if (status == ExecutionStatus.SCHEDULED || !openYet) {
            return CODE_NOT_OPEN_YET;
        }
        return CODE_CLOSED;
    }
}
