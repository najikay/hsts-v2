package server.features.exambuild;

import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.QuestionPin;

/**
 * Every sentence the exam builder sends a human (Logic tier, E7 - PRD §4.1).
 *
 * <p>One class, for the reasons {@code BankMessages} and {@code ExamMessages} exist: the copy
 * rules are checkable by one test rather than by reading a service, a refusal and the screen
 * that renders it cannot drift apart, and the wording is reviewed once instead of once per
 * handler.
 *
 * <p>Two rules bind every string here. <b>No em dashes</b> (PRD §4.1). And <b>every error says
 * what to do next</b>, which is the harder one: "invalid composition" is a dead end, "the points
 * add up to 96, add 4 more" is a next move.
 *
 * <h2>The numbers are interpolated, never typed</h2>
 *
 * <p>Every ceiling below reads its number from the constant on the wire record rather than
 * spelling it out. The lead's type-landing brief asks for this by name, and the reason is that a
 * sentence carrying a hand-typed number is a second statement of the rule: ruling 3 cut the
 * duration ceiling from 600 to 480, and a message that said "600" would have survived that change
 * looking correct. The rule that refused her and the sentence she reads cannot disagree if there
 * is only one number.
 *
 * <h2>Why the points sentence names the direction and the amount</h2>
 *
 * <p>Acceptance T-3.2 watches the live indicator go from wrong to right while she edits, so the
 * sentence has to say <em>which way</em> she is out and by how much. "The points must total 100"
 * is true and useless; she cannot tell from it whether to add or remove. Both directions are
 * spelled separately below rather than through a signed number, because "over by -4" is not a
 * sentence anybody writes.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p><b>No infeasibility sentence.</b> The contract's §7.2 property 3 puts F3.3's report on the
 * client, composed once from the {@code Shortfall} data, and ruling 4 settles it. A sentence here
 * would be a second author of the same words. That is also why this class is untouched by the
 * shortfall question still open with the lead.
 */
public final class ExamBuildMessages {

    private ExamBuildMessages() {
        // static copy - no instances
    }

    // ===================== Malformed or out of scope ======================

    /** The payload was not the type this verb expects, or failed its own well-formedness check. */
    public static final String MALFORMED_REQUEST =
            "That request did not arrive in a form the server could read. Please try again.";

    /**
     * The exam version is not one the caller may see or change (contract §2).
     *
     * <p>One sentence for unknown, and for an exam somebody else wrote. Section 2 scopes every
     * version verb to the author, and telling a caller "that exam belongs to Dana Cohen" would
     * confirm the exam exists and name a colleague, which is the disclosure the single answer
     * exists to prevent.
     */
    public static final String EXAM_NOT_FOUND =
            "That exam is not one of yours, or it is no longer there. Open your exam list to see "
                    + "the exams you can edit.";

    // ===================== Metadata (§5.3) ================================

    /** A version arrived with no name on it. */
    public static final String NAME_REQUIRED =
            "Give the exam a name before saving it.";

    /** F3.1: the name is longer than the column holds. */
    public static final String NAME_TOO_LONG =
            "That exam name is too long. Keep it to " + ExamCreateRequest.MAX_NAME_LENGTH
                    + " characters or fewer.";

    /**
     * The duration is outside 1..480 (ruling 3).
     *
     * <p>Names the ceiling because the mistake this rule exists to catch is a typo of 600 for 60,
     * and a teacher who typed 600 needs to know what the allowed range is rather than that she is
     * outside it.
     */
    public static final String DURATION_OUT_OF_RANGE =
            "An exam runs between " + ExamCreateRequest.MIN_DURATION_MINUTES + " and "
                    + ExamCreateRequest.MAX_DURATION_MINUTES + " minutes. Check the duration you "
                    + "typed.";

    /** The student-facing text is longer than the wire allows. */
    public static final String STUDENT_TEXT_TOO_LONG =
            "The instructions for students are too long. Keep them to "
                    + ExamCreateRequest.MAX_TEXT_LENGTH + " characters or fewer.";

    /** The teacher-facing text is longer than the wire allows. */
    public static final String TEACHER_TEXT_TOO_LONG =
            "The notes for teachers are too long. Keep them to "
                    + ExamCreateRequest.MAX_TEXT_LENGTH + " characters or fewer.";

    /** A create arrived with no course on it. */
    public static final String COURSE_REQUIRED =
            "Pick the course this exam belongs to before saving it.";

    // ===================== Points (§5.1) ==================================

    /** An exam with no questions cannot total 100. */
    public static final String NO_QUESTIONS =
            "An exam needs at least one question. Add questions from the bank, then set their "
                    + "points.";

    /**
     * The points are short of 100 (T-3.2).
     *
     * @param total what they currently add up to
     * @return the sentence, naming the gap
     */
    public static String pointsShort(int total) {
        return "The points add up to " + total + ". Add "
                + (ExamCreateRequest.POINTS_TOTAL - total) + " more to reach "
                + ExamCreateRequest.POINTS_TOTAL + ".";
    }

    /**
     * The points are over 100 (T-3.2).
     *
     * @param total what they currently add up to
     * @return the sentence, naming the excess
     */
    public static String pointsOver(int total) {
        return "The points add up to " + total + ". Remove "
                + (total - ExamCreateRequest.POINTS_TOTAL) + " to reach "
                + ExamCreateRequest.POINTS_TOTAL + ".";
    }

    /**
     * One question's points are outside 1..100.
     *
     * @param position the question's 1-based position in the list
     * @return the sentence, naming which question
     */
    public static String pointsOutOfRange(int position) {
        return "Question " + position + " is worth an impossible number of points. Each question "
                + "is worth between " + QuestionPin.MIN_POINTS + " and " + QuestionPin.MAX_POINTS
                + ".";
    }

    // ===================== Composition (§5.2) =============================

    /**
     * A null element survived deserialization (the lead's §4.2, item 1).
     *
     * <p>Names the position rather than the question, because there is no question to name: the
     * slot is empty. {@code VALIDATION} rather than {@code NOT_FOUND} for the reason the contract
     * gives, that she is describing a composition and the thing missing is a field of her request.
     *
     * @param position the empty slot's 1-based position
     * @return the sentence
     */
    public static String questionMissingAt(int position) {
        return "Question " + position + " did not arrive with the exam. Remove it and add it "
                + "again.";
    }

    /**
     * A pinned question version does not exist (§5.2).
     *
     * @param position the pin's 1-based position
     * @return the sentence
     */
    public static String questionUnknownAt(int position) {
        return "Question " + position + " is not in the bank any more. Remove it from the exam "
                + "and pick another.";
    }

    /**
     * The same question appears twice, possibly through two different versions of it (T-3.9).
     *
     * <p>Names the question by its display id, which is what she sees on the bank screen and on
     * the row she has to remove. The database refuses this too, and its message names a
     * constraint: this sentence exists because that one is not a next move.
     *
     * @param questionDisplayId5 the five-digit id of the question pinned twice
     * @return the sentence
     */
    public static String questionPinnedTwice(String questionDisplayId5) {
        return "Question " + questionDisplayId5 + " is in this exam twice. An exam can use a "
                + "question once, even through two versions of it. Remove one of them.";
    }

    /**
     * A pinned question belongs to another course (§5.2).
     *
     * @param questionDisplayId5 the five-digit id of the question
     * @return the sentence
     */
    public static String questionFromAnotherCourse(String questionDisplayId5) {
        return "Question " + questionDisplayId5 + " belongs to a different course, so it cannot "
                + "go in this exam. Remove it and pick one from this course.";
    }

    /**
     * A pinned question has been deleted from the bank (§5.2, ARCHITECTURE §5 round 2).
     *
     * <p>The rule with no database backstop. Soft delete is an {@code UPDATE} and no foreign key
     * fires on an update, so if this sentence never reaches a teacher it is because this check
     * ran, not because the schema helped.
     *
     * @param questionDisplayId5 the five-digit id of the deleted question
     * @return the sentence
     */
    public static String questionDeleted(String questionDisplayId5) {
        return "Question " + questionDisplayId5 + " has been deleted from the bank, so it cannot "
                + "go in a new exam. Remove it and pick another.";
    }

    // ===================== State (§5.4) ===================================

    /**
     * A save landed on a version that is no longer a draft (§5.4).
     *
     * <p>{@code CONFLICT}, not {@code VALIDATION}: her request was well formed and the world
     * moved underneath it. The sentence says what to do about it, which is to make a new version
     * rather than to fix a field.
     */
    public static final String NOT_A_DRAFT =
            "This version has been submitted already, so it cannot be edited. Make a new version "
                    + "from it to keep working.";

    /**
     * A revise landed on a draft (§5.4).
     *
     * <p>The version she addressed is already the thing revise would make her.
     */
    public static final String ALREADY_A_DRAFT =
            "This version is still a draft, so there is nothing to revise. Edit it and save.";

    /**
     * A revise landed on a finished version while the exam already had an open draft
     * (§5.4 as amended 2026-08-25: <b>one open draft per exam</b>).
     *
     * <p>The rule and this sentence are the lead's ruling. It exists because the exam list made
     * the case reachable for the first time: the screen renders a card per version and offered
     * Revise on every non-draft, so revising an approved v1 while v3 sat unfinished produced two
     * drafts of one exam. {@code ALREADY_A_DRAFT} did not catch it, because that check is on the
     * version she addressed and this one is a fact about the exam.
     *
     * <p><b>It names the draft rather than only refusing.</b> "A draft already exists" leaves her
     * hunting for it in a version list; naming the number and telling her to open it is the
     * difference between a refusal and an instruction. That was the lead's condition on the rule.
     *
     * @param versionNo the open draft's version number
     * @return the sentence for the teacher
     */
    public static String draftAlreadyOpen(int versionNo) {
        return "Version " + versionNo + " of this exam is already an open draft. Open that one "
                + "and make your changes there.";
    }

    /** A submit landed on something other than a draft (§5.4). */
    public static final String NOT_SUBMITTABLE =
            "Only a draft can be sent for approval. This version has been sent already.";

    /**
     * The exam version is edit-locked by another teacher (E18.5, ruled 2026-08-24).
     *
     * <p>The bank's twin, and the wording follows it deliberately: a teacher who meets this on one
     * screen and then the other should not have to work out that they are the same refusal.
     *
     * @param editorName who holds it
     * @return the sentence
     */
    public static String lockedBy(String editorName) {
        return "This exam is being edited by " + editorName + " right now. You can read it, and "
                + "it becomes editable as soon as that editor closes it.";
    }

    /**
     * The optimistic token did not match (§3, mirroring the approval wire).
     *
     * <p>Distinct from {@link #NOT_A_DRAFT} on purpose: this one means somebody wrote to the row
     * while she had it open, and reopening is the whole fix.
     */
    public static final String STALE_VERSION =
            "Someone else changed this exam while you had it open. Reopen it to see the newest "
                    + "version.";

    // ===================== Auto-compose criteria (§5.3) ===================

    /**
     * Two quotas name one topic (§5.3, added at type-landing).
     *
     * <p>Refused rather than merged because two buckets over one candidate pool break the
     * disjointness §7.4's selection relies on, and the report could then name a shortfall the
     * teacher can disprove against her own bank.
     *
     * @param topic the topic named twice, or {@code null} for the course-wide bucket
     * @return the sentence
     */
    public static String topicRequestedTwice(String topic) {
        return topic == null
                ? "The criteria ask for questions from the whole course twice. Combine those two "
                        + "rows into one."
                : "The criteria ask for topic " + topic + " twice. Combine those two rows into "
                        + "one.";
    }

    /**
     * A null quota survived deserialization, as its own constructor comment says it must.
     *
     * @param position the empty row's 1-based position
     * @return the sentence
     */
    public static String quotaMissingAt(int position) {
        return "Row " + position + " of the criteria did not arrive. Remove it and add it again.";
    }

    /** A quota bucket is negative. */
    public static final String QUOTA_NEGATIVE =
            "A row of the criteria asks for a negative number of questions. Every row asks for "
                    + "none or more.";

    /** Every bucket is zero, so the criteria ask for nothing. */
    public static final String QUOTA_EMPTY =
            "The criteria ask for no questions at all. Set how many questions you want before "
                    + "generating.";

    /**
     * The two shapes of criteria the builder can answer (contract §7.3a, ruled 2026-08-24).
     *
     * <p><b>It names both legal shapes, and that was the lead's condition on accepting the
     * rule.</b> A sentence saying only "that combination is not allowed" leaves her holding a
     * screen with two halves and no way to tell which one to delete, on the verb whose whole
     * point is telling her exactly what is wrong.
     *
     * <p>Why the combination is refused at all, since the sentence cannot carry it: a topic
     * quota drawing on {@code any} and a course-wide quota drawing on {@code hard} cross rather
     * than nest, so neither contains the other, no single bucket is short, and the request is
     * still impossible. §7.3 then names no row to emit, {@code AutoComposeResult} refuses a
     * report with nothing in it, and she gets an internal error on the one verb F3.3 exists to
     * make helpful. Refusing the shape is what keeps every pool nested, which is what makes the
     * bucket comparisons exact rather than approximate.
     */
    /**
     * More questions asked for than 100 points can be spread across (E7.4).
     *
     * <p><b>Forced by two frozen constants rather than chosen.</b> {@code QuestionPin.MIN_POINTS}
     * is 1 and {@code ExamCreateRequest.POINTS_TOTAL} is 100, so 101 questions cannot each be
     * worth at least one point and still total exactly 100. §7.4 requires a proposal to arrive
     * already summing to 100, which for such a request is unsatisfiable.
     *
     * <p>Refused here rather than proposed and rejected later. The alternative is a proposal that
     * looks fine on screen, gets one click, and then violates {@code ck_evq_points} as an
     * internal error on save - which is P-9's shape exactly: a service producing something the
     * database refuses.
     *
     * <p>The ceiling reads from the constant, never typed, for the reason the class javadoc
     * gives.
     */
    public static String quotaOverPointsCeiling(int requested) {
        return "The criteria ask for " + requested + " questions, and an exam can hold at most "
                + ExamCreateRequest.POINTS_TOTAL + " because every question is worth at least "
                + QuestionPin.MIN_POINTS + " point out of " + ExamCreateRequest.POINTS_TOTAL
                + ". Ask for fewer.";
    }

    public static final String QUOTA_SHAPE_MIXED =
            "The criteria mix two ways of asking. Either give a row per topic and a total for "
                    + "the whole course, or split the whole course by difficulty on its own "
                    + "with no topic rows.";
}
