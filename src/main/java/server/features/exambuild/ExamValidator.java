package server.features.exambuild;

import common.dto.authoring.AutoComposeRequest;
import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.QuestionPin;
import common.dto.authoring.TopicQuota;
import server.db.projections.PinCandidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every rule the exam builder enforces before a write (Logic tier, E7.3 / E7.8 - contract §5).
 *
 * <p>Session-free on purpose, and that is what lets the same rule run in two places. E7.12's
 * builder screen shows a live points total while she drags questions around, and it reaches the
 * <em>same verdict</em> as the server by calling {@link #pointsProblem} rather than by carrying a
 * second copy of the arithmetic. That is the pattern the lead accepted twice for
 * {@code QuestionValidator}: one rule with one home, and a client that cannot drift from it.
 *
 * <h2>Why the composition rules take a list instead of a session</h2>
 *
 * <p>Four of the contract's §5.2 rules need to know things only the database knows: whether a
 * pinned version exists, which question owns it, which course that question is in, and whether it
 * has been soft-deleted. {@link #compositionProblem} therefore takes the
 * {@link PinCandidate} rows as a <b>parameter</b>. The service does the fetching, one query for
 * the whole composition, and this class stays a pure function of its inputs.
 *
 * <p>The gain is not tidiness. It is that every §5.2 rule becomes testable with a hand-built list
 * and no database at all, which is why the soft-delete rule - the one with no schema backstop
 * anywhere - can be planted and watched to fail in milliseconds.
 *
 * <h2>The tolerance boundary this class exists on</h2>
 *
 * <p>The lead's type-landing brief is explicit that <b>every inbound compact constructor in
 * {@code common/dto/authoring} normalises and never throws</b>, because a throw runs on the socket
 * read thread during deserialization and kills the connection (E1.11) instead of answering a
 * sentence. The list copies are {@code new ArrayList<>(...)} rather than {@code List.copyOf}
 * precisely so a <b>null element survives</b> to be refused here with a named position.
 *
 * <p>So everything below arrives possibly-hostile and is refused with a sentence, never with an
 * exception. Eight shapes are named in that brief and each has a rule here: null elements, a
 * points sum that is wrong in either direction, points outside 1..100, an empty list, a duplicate
 * question through two versions, two quotas naming one topic, a negative or all-zero quota, and a
 * name that is blank or over-long. <b>{@code name} is stripped but not folded to null</b>, so a
 * client that sent {@code "   "} arrives as {@code ""} and one that sent nothing arrives as
 * {@code null}: both are checked.
 *
 * <h2>One violation, not a list</h2>
 *
 * <p>{@link Violation} is returned singly, matching {@code QuestionValidator}. A teacher fixes one
 * thing at a time and T-3.2 watches a single indicator move; a list would also make the client
 * decide which of several sentences to show, which is a decision with no good answer.
 */
public final class ExamValidator {

    private ExamValidator() {
        // static rules - no instances
    }

    /**
     * A refused rule, and the field it is about.
     *
     * <p>The field name is for a client that wants to highlight a box. It does <b>not</b> reach
     * the wire today: like the bank's, the handler keeps only the message, and E7.12 maps a
     * refusal back onto a control by exact equality against {@link ExamBuildMessages}. Both tiers
     * ship in one artifact, which is what makes that sound. Saying so here rather than letting a
     * reader assume the field travels.
     *
     * @param field   the field the rule is about
     * @param message the sentence for the teacher
     */
    public record Violation(String field, String message) {
    }

    /** Field names, so a caller and a test cannot disagree about the spelling. */
    public static final String FIELD_NAME = "name";
    public static final String FIELD_DURATION = "durationMinutes";
    public static final String FIELD_STUDENT_TEXT = "studentText";
    public static final String FIELD_TEACHER_TEXT = "teacherText";
    public static final String FIELD_COURSE = "courseCode";
    public static final String FIELD_QUESTIONS = "questions";
    public static final String FIELD_QUOTAS = "quotas";

    // ===================== Metadata (§5.3) ================================

    /**
     * The metadata rules, shared by create and save (§5.3).
     *
     * <p>Takes the four fields rather than either record, because {@code ExamCreateRequest} and
     * {@code ExamVersionSave} carry the same four under the same constants and one rule must not
     * be written twice. The constants are aliases on the second record for exactly this reason.
     *
     * @param name           the exam name, already stripped by the record
     * @param durationMinutes how long the exam runs
     * @param studentText    instructions for students, or {@code null}
     * @param teacherText    notes for teachers, or {@code null}
     * @return the first rule broken, or empty when the metadata is acceptable
     */
    public static Optional<Violation> metadataProblem(String name, int durationMinutes,
                                                      String studentText, String teacherText) {
        // Both shapes of "no name". The record strips but does not blank-fold this field, so a
        // client that sent spaces arrives as "" and one that sent nothing arrives as null.
        if (name == null || name.isEmpty()) {
            return violation(FIELD_NAME, ExamBuildMessages.NAME_REQUIRED);
        }
        if (name.length() > ExamCreateRequest.MAX_NAME_LENGTH) {
            return violation(FIELD_NAME, ExamBuildMessages.NAME_TOO_LONG);
        }
        if (durationMinutes < ExamCreateRequest.MIN_DURATION_MINUTES
                || durationMinutes > ExamCreateRequest.MAX_DURATION_MINUTES) {
            return violation(FIELD_DURATION, ExamBuildMessages.DURATION_OUT_OF_RANGE);
        }
        if (studentText != null && studentText.length() > ExamCreateRequest.MAX_TEXT_LENGTH) {
            return violation(FIELD_STUDENT_TEXT, ExamBuildMessages.STUDENT_TEXT_TOO_LONG);
        }
        if (teacherText != null && teacherText.length() > ExamCreateRequest.MAX_TEXT_LENGTH) {
            return violation(FIELD_TEACHER_TEXT, ExamBuildMessages.TEACHER_TEXT_TOO_LONG);
        }
        return Optional.empty();
    }

    /**
     * The course code a create must carry (§5.3).
     *
     * <p>Separate from {@link #metadataProblem} because only {@code EXAM_CREATE} carries a course:
     * a save addresses a stored version whose course is already settled, and asking it for one
     * would invite a client to send a course that disagrees with the row.
     *
     * @param courseCode the code as it arrived, already {@code strip()}ped by the record
     * @return the violation, or empty
     */
    public static Optional<Violation> courseProblem(String courseCode) {
        if (courseCode == null || courseCode.isEmpty()) {
            return violation(FIELD_COURSE, ExamBuildMessages.COURSE_REQUIRED);
        }
        return Optional.empty();
    }

    // ===================== Points (§5.1) ==================================

    /**
     * The points rule, and the one method the client calls across the tier (§5.1, T-3.2).
     *
     * <p>Order matters and is deliberate: an empty list, then per-question range, then the sum.
     * Summing first would answer "the points add up to 0, add 100 more" for an exam with no
     * questions, which is arithmetically true and tells her to do the wrong thing.
     *
     * <p><b>The sum is computed in {@code long} and compared to 100.</b> A hundred questions at
     * {@code MAX_POINTS} is 10,000, nowhere near overflow, but the range check runs first anyway
     * so no unchecked value reaches the addition. Stated because "it cannot overflow" is a claim
     * about the checks above it, not about the arithmetic.
     *
     * @param questions the pins as they arrived, possibly containing nulls
     * @return the first rule broken, or empty when the points are acceptable
     */
    public static Optional<Violation> pointsProblem(List<QuestionPin> questions) {
        if (questions == null || questions.isEmpty()) {
            return violation(FIELD_QUESTIONS, ExamBuildMessages.NO_QUESTIONS);
        }
        long total = 0;
        for (int i = 0; i < questions.size(); i++) {
            QuestionPin pin = questions.get(i);
            if (pin == null) {
                // Survives the record's copy on purpose; see this class's header.
                return violation(FIELD_QUESTIONS, ExamBuildMessages.questionMissingAt(i + 1));
            }
            if (pin.points() < QuestionPin.MIN_POINTS || pin.points() > QuestionPin.MAX_POINTS) {
                return violation(FIELD_QUESTIONS, ExamBuildMessages.pointsOutOfRange(i + 1));
            }
            total += pin.points();
        }
        if (total < ExamCreateRequest.POINTS_TOTAL) {
            return violation(FIELD_QUESTIONS, ExamBuildMessages.pointsShort((int) total));
        }
        if (total > ExamCreateRequest.POINTS_TOTAL) {
            return violation(FIELD_QUESTIONS, ExamBuildMessages.pointsOver((int) total));
        }
        return Optional.empty();
    }

    // ===================== Composition (§5.2) =============================

    /**
     * The four rules that need the database, over rows the caller already fetched (§5.2).
     *
     * <p>Every one of them is a service rule standing in for a constraint that either does not
     * exist or cannot speak. The duplicate rule has a constraint behind it
     * ({@code uq_exam_version_questions_question}) whose message names a constraint rather than a
     * next move; the course rule and the existence rule have none; and <b>the soft-delete rule
     * has none and cannot have one</b>, because soft delete is an {@code UPDATE} and no foreign
     * key fires on an update. ARCHITECTURE §5 assigns that rule here by name.
     *
     * <p>Order is deliberate. Existence first, because the other three read fields off a row that
     * has to be there; then deleted, then course, then duplicate. A question that is both deleted
     * and from another course reports deleted, which is the more actionable of the two.
     *
     * <p><b>Duplicates are detected on {@code questionId}, never on {@code questionVersionId}.</b>
     * That is the whole of T-3.9: two <em>different</em> versions of one question are two distinct
     * version ids and one question, so comparing version ids would let the exam ask the same thing
     * twice while every id in the list was unique.
     *
     * @param questions  the pins as they arrived, already through {@link #pointsProblem}
     * @param candidates the rows the store returned for those pins, in any order
     * @param examCourse the course the exam belongs to, from the stored exam rather than the wire
     * @return the first rule broken, or empty when the composition is acceptable
     */
    public static Optional<Violation> compositionProblem(List<QuestionPin> questions,
                                                        List<PinCandidate> candidates,
                                                        String examCourse) {
        Map<Long, PinCandidate> byVersionId = new HashMap<>();
        for (PinCandidate candidate : candidates) {
            byVersionId.put(candidate.questionVersionId(), candidate);
        }

        Set<Long> seenQuestionIds = new HashSet<>();
        for (int i = 0; i < questions.size(); i++) {
            QuestionPin pin = questions.get(i);
            PinCandidate candidate = byVersionId.get(pin.questionVersionId());

            // A pin the store did not return is a version id that does not exist. VALIDATION
            // naming the position, not NOT_FOUND: she is describing a composition, and the thing
            // that is missing is a field of her request rather than the object she addressed.
            if (candidate == null) {
                return violation(FIELD_QUESTIONS, ExamBuildMessages.questionUnknownAt(i + 1));
            }
            if (candidate.deleted()) {
                return violation(FIELD_QUESTIONS,
                        ExamBuildMessages.questionDeleted(candidate.questionDisplayId5()));
            }
            // equalsIgnoreCase, not equals, and the reason is that the OTHER course check on
            // this path runs in SQL. Authorization.requireTeachesCourse compares through
            // CourseRepository.teaches against courses.code2 under utf8mb4_unicode_ci, which is
            // case-insensitive. With Java comparing case-sensitively, a caller sending "ma" for
            // a stored "MA" passes the scope guard and is then told every question in her own
            // course belongs to a different one - a sentence that is false and gives her nothing
            // to do. The two comparisons have to agree, and this is the cheaper half to move.
            //
            // Narrower than the collation still: _ci also folds accents, which this does not.
            // That fails CLOSED (a refusal, never a wrongly accepted question) and is the same
            // direction the bank contract's strip() note settles on.
            if (!candidate.courseCode().equalsIgnoreCase(examCourse)) {
                return violation(FIELD_QUESTIONS,
                        ExamBuildMessages.questionFromAnotherCourse(
                                candidate.questionDisplayId5()));
            }
            if (!seenQuestionIds.add(candidate.questionId())) {
                return violation(FIELD_QUESTIONS,
                        ExamBuildMessages.questionPinnedTwice(candidate.questionDisplayId5()));
            }
        }
        return Optional.empty();
    }

    /**
     * The version ids a composition refers to, for the caller's one fetch.
     *
     * <p>Here rather than in the service so that the set the store is asked about and the list the
     * rules run over are derived from one place. Nulls are skipped: {@link #pointsProblem} refuses
     * them and runs first, and a null-safe extraction means the order of the two calls cannot turn
     * a bad payload into a {@code NullPointerException}.
     *
     * @param questions the pins as they arrived
     * @return their version ids, duplicates included, nulls skipped
     */
    public static List<Long> pinnedVersionIds(List<QuestionPin> questions) {
        List<Long> ids = new ArrayList<>();
        if (questions == null) {
            return ids;
        }
        for (QuestionPin pin : questions) {
            if (pin != null) {
                ids.add(pin.questionVersionId());
            }
        }
        return ids;
    }

    // ===================== Auto-compose criteria (§5.3) ===================

    /**
     * The criteria rules, which are the validator's even though the generator is not (§5.3).
     *
     * <p>Two quotas naming one topic is refused rather than merged, and the reason is not tidiness:
     * two buckets drawing from one candidate pool break the disjointness §7.4's most-constrained
     * -first selection relies on, and the report could then name a shortfall <b>the teacher can
     * disprove</b> by filtering her own bank to that topic. §7.2 property 2 calls that the worst
     * failure available here.
     *
     * <p>Comparison is on the <b>normalised</b> topic. {@code TopicQuota} folds blank to null, so
     * {@code ""} and {@code null} are one bucket and not two, and a client sending both would
     * otherwise open exactly the hazard above while looking like two distinct rows.
     *
     * @param request the criteria as they arrived
     * @return the first rule broken, or empty when the criteria are acceptable
     */
    public static Optional<Violation> quotaProblem(AutoComposeRequest request) {
        List<TopicQuota> quotas = request.quotas();
        Set<String> seenTopics = new HashSet<>();
        boolean anyNullTopic = false;

        for (int i = 0; i < quotas.size(); i++) {
            TopicQuota quota = quotas.get(i);
            if (quota == null) {
                // Survives the record's copy for the same reason a null pin does; its own
                // constructor comment says so.
                return violation(FIELD_QUOTAS, ExamBuildMessages.quotaMissingAt(i + 1));
            }
            // All four buckets, not the derived total: two buckets of +3 and -3 sum to zero, so
            // a check on total() alone would admit a negative one and then let totalRequested()
            // report a number that is not what she asked for in any row.
            if (quota.easy() < 0 || quota.medium() < 0 || quota.hard() < 0 || quota.any() < 0) {
                return violation(FIELD_QUOTAS, ExamBuildMessages.QUOTA_NEGATIVE);
            }
            if (quota.isCourseWide()) {
                if (anyNullTopic) {
                    return violation(FIELD_QUOTAS, ExamBuildMessages.topicRequestedTwice(null));
                }
                anyNullTopic = true;
            } else if (!seenTopics.add(quota.topic())) {
                return violation(FIELD_QUOTAS,
                        ExamBuildMessages.topicRequestedTwice(quota.topic()));
            }
        }
        if (request.totalRequested() < 1) {
            return violation(FIELD_QUOTAS, ExamBuildMessages.QUOTA_EMPTY);
        }
        return Optional.empty();
    }

    private static Optional<Violation> violation(String field, String message) {
        return Optional.of(new Violation(field, message));
    }
}
