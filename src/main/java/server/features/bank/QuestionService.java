package server.features.bank;

import common.dto.bank.BlockingExam;
import common.dto.bank.DeleteOutcome;
import common.dto.bank.QuestionDeleteRequest;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionDraft;
import common.dto.bank.QuestionEdit;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.Authorization;
import server.core.CallerContext;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.ids.AllocatedId;
import server.db.ids.QuestionIdAllocator;
import server.db.projections.ReferencingExam;
import server.db.repos.CourseRepository;
import server.db.repos.QuestionRepository;
import server.db.repos.UserRepository;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The three question bank writes: create, edit and delete (E6.1, E6.3, E6.4).
 *
 * <p>Everything here happens inside a transaction the caller already opened, because all three
 * verbs read a row, decide something about it and write beside it, and splitting that across two
 * transactions is how a question gets edited twice from one stale editor.
 *
 * <h2>The scope guard, and why each verb uses the one it does</h2>
 *
 * <p>The wire contract's section 3 assigns a guard per verb and this class is where that table
 * becomes code. Nothing composes the two guards and nothing decides between them at runtime:
 *
 * <ul>
 *   <li>{@link #create} calls the <b>throwing</b> {@code requireTeachesCourse}. It is the one
 *       verb where the caller supplies the course, so a {@code FORBIDDEN} naming it tells her
 *       nothing she did not already type.</li>
 *   <li>{@link #update} and {@link #delete} call the <b>boolean</b> {@code teachesCourse} and
 *       answer {@code NOT_FOUND} themselves. They resolve the course from a <em>stored</em>
 *       question, so naming it would tell a caller probing display ids both that the question
 *       exists and which course it belongs to. Unknown, soft-deleted and out of scope are one
 *       answer on purpose.</li>
 * </ul>
 *
 * <p>The read guard {@code reachesCourse} is deliberately absent: this class writes, and section 3
 * gives the writes no coordinator-wide scope. A coordinator authors only in courses she teaches,
 * which is the specification's split between authoring and visibility and not an oversight.
 *
 * <p>Each guard is called against <b>this transaction's</b> data through a lambda over
 * {@link CourseRepository#teaches}, never through the process-wide directory
 * {@code Authorization.useCourseTeachers} installs. That is {@code ApprovalService}'s pattern and
 * it buys the same two things: the rule is testable with a two-line lambda, and the answer comes
 * from the same moment as the row it is about.
 *
 * <h2>The rule a caller must not break</h2>
 *
 * <p><b>{@link QuestionValidator#validate} must have passed before any method here is called.</b>
 * Not as a matter of tidiness: {@code QuestionDraft} and {@code QuestionEdit} deliberately let a
 * <em>null answer element</em> survive construction, so that a hostile payload is refused with a
 * named sentence instead of killing the socket read thread (E1.11). This class reads
 * {@code answers.get(0..3)} directly, which is only safe once the validator's structural rules
 * have run and short-circuited on a null. The handlers validate before they open a transaction,
 * which is both the right order and the cheap one.
 *
 * <h2>Editing never mutates (C-2, ADR-011)</h2>
 *
 * <p>{@link #update} inserts version n+1 and leaves version n exactly as it was, so an exam pinned
 * to version n keeps the paper it was approved with. The stale-editor race is caught by
 * {@code baseVersionNo} disagreeing with the current latest, not by {@code questions.lock_version}:
 * the identity row never changes on an edit, so its {@code @Version} column never increments and
 * an echoed token would match forever.
 */
public class QuestionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);

    /** Every question starts at version 1. Spelled out once so the two call sites agree. */
    public static final int FIRST_VERSION = 1;

    /** How {@link #update} finished. */
    public enum EditStatus {

        /** Version n+1 was written and is in the outcome. */
        UPDATED,

        /** Unknown, soft-deleted, or not in a course the caller teaches. One answer, on purpose. */
        NOT_FOUND,

        /** Somebody else wrote a version after this editor opened hers. */
        STALE
    }

    /** How {@link #delete} finished. */
    public enum DeleteStatus {

        /** The request was answerable; the {@link DeleteOutcome} says whether it went through. */
        RESOLVED,

        /** Unknown, soft-deleted, or not in a course the caller teaches. One answer, on purpose. */
        NOT_FOUND,

        /** Somebody else wrote a version after this editor opened hers. */
        STALE
    }

    /**
     * What {@link #update} did.
     *
     * @param status what happened
     * @param detail the new version, or {@code null} for anything other than {@link
     *               EditStatus#UPDATED}
     */
    public record EditOutcome(EditStatus status, QuestionDetail detail) {
    }

    /**
     * What {@link #delete} did.
     *
     * <p>Blocked and deleted are both {@link DeleteStatus#RESOLVED}: the contract answers both
     * with {@code OK} carrying a {@link DeleteOutcome}, because being told which exams use a
     * question is a successful answer to "may I delete this", not an error.
     *
     * @param status  what happened
     * @param outcome the wire answer, or {@code null} for anything other than {@link
     *                DeleteStatus#RESOLVED}
     */
    public record DeleteResolution(DeleteStatus status, DeleteOutcome outcome) {
    }

    private final QuestionRepository questions;
    private final CourseRepository courses;
    private final UserRepository users;
    private final QuestionIdAllocator ids;
    private final Clock clock;
    private final BankDetails details;

    public QuestionService(QuestionRepository questions,
                           CourseRepository courses,
                           UserRepository users,
                           QuestionIdAllocator ids,
                           Clock clock) {
        this.questions = Objects.requireNonNull(questions, "questions");
        this.courses = Objects.requireNonNull(courses, "courses");
        this.users = Objects.requireNonNull(users, "users");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        // Built here rather than injected: the constructor signature is what HSTSServer's
        // assembly line and every existing test call, and a shared mapper over two repositories
        // this class already holds is not a seam worth widening that for.
        this.details = new BankDetails(this.courses, this.users);
    }

    // ===================== QUESTION_CREATE (E6.1) =========================

    /**
     * Writes a new question and its first version (E6.1, S-8, F2.2).
     *
     * <p>The display id is allocated server-side under the course's row lock, so two teachers
     * adding to the same course at the same moment cannot be handed the same serial.
     *
     * @param session a session inside the caller's transaction
     * @param caller  the authenticated author, already role-checked
     * @param draft   the question, already through {@link QuestionValidator}
     * @return the question as it now stands, version 1 of 1
     * @throws server.core.AuthorizationException {@code FORBIDDEN} when this is not one of her
     *                                            courses
     */
    public QuestionDetail create(Session session, CallerContext caller, QuestionDraft draft) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(draft, "draft");
        String courseCode = strip(draft.courseCode());

        Authorization.requireTeachesCourse(caller, courseCode,
                (teacherId, code) -> courses.teaches(session, teacherId, code));

        AllocatedId allocated = ids.allocate(session, courseCode);
        Question question =
                new Question(courseCode, (short) allocated.serial(), allocated.displayId());
        session.persist(question);
        // The version row needs the identity row's generated id, and nothing else in this
        // transaction would force the insert before the version is built.
        session.flush();

        QuestionVersion version = versionOf(question.getId(), FIRST_VERSION, draft.text(),
                draft.answers(), draft.correctAnswer(), draft.topic(),
                entityDifficulty(draft.difficulty()), draft.image(), caller.userId());
        session.persist(version);

        log.debug("Question {} created in course {} by user {}",
                question.getDisplayId(), courseCode, caller.userId());
        return detailOf(session, question, version, FIRST_VERSION);
    }

    // ===================== QUESTION_UPDATE (E6.3) =========================

    /**
     * Writes version n+1 of an existing question (E6.3, C-2, ADR-011, F2.3).
     *
     * @param session a session inside the caller's transaction
     * @param caller  the authenticated author, already role-checked
     * @param edit    the edited question, already through {@link QuestionValidator}
     * @return the new version, or why it was refused
     */
    public EditOutcome update(Session session, CallerContext caller, QuestionEdit edit) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(edit, "edit");

        Optional<Question> found = reachableForWriting(session, caller, edit.displayId5());
        if (found.isEmpty()) {
            return new EditOutcome(EditStatus.NOT_FOUND, null);
        }
        Question question = found.get();

        Optional<QuestionVersion> latest =
                questions.findLatestVersionForAuthoring(session, question.getId());
        if (latest.isEmpty() || latest.get().getVersionNo() != edit.baseVersionNo()) {
            return new EditOutcome(EditStatus.STALE, null);
        }
        QuestionVersion previous = latest.get();

        int nextVersionNo = previous.getVersionNo() + 1;
        QuestionVersion version = versionOf(question.getId(), nextVersionNo, edit.text(),
                edit.answers(), edit.correctAnswer(), edit.topic(),
                entityDifficulty(edit.difficulty()), imageFor(edit, previous), caller.userId());
        session.persist(version);

        log.debug("Question {} edited to version {} by user {}",
                question.getDisplayId(), nextVersionNo, caller.userId());
        return new EditOutcome(EditStatus.UPDATED,
                detailOf(session, question, version, nextVersionNo));
    }

    /**
     * The bytes version n+1 carries, per the edit's {@code ImageAction}.
     *
     * <p>{@code KEEP} copies the previous version's blob rather than pointing at it, because
     * versions are immutable and independently readable: an illustrated question edited ten times
     * stores the image ten times, which is ADR-011's honest cost and the reason
     * {@code QUESTION_IMAGE_GET} is addressed by version rather than by question.
     *
     * @param edit     the edit as submitted
     * @param previous the version being branched from
     * @return the image for the new version, or {@code null} when it has none
     */
    private static byte[] imageFor(QuestionEdit edit, QuestionVersion previous) {
        return switch (edit.imageAction()) {
            case KEEP -> previous.getImage();
            case REPLACE -> edit.image();
            case REMOVE -> null;
        };
    }

    // ===================== QUESTION_DELETE (E6.4) =========================

    /**
     * Soft-deletes a question, or refuses and names the exams that use it (E6.4, F2.5, T-2.7).
     *
     * <p><b>The block is a service query with no database backstop.</b> Soft delete is an
     * {@code UPDATE} and no foreign key fires on an update: the schema's three {@code RESTRICT}s
     * prevent a <em>hard</em> delete, which is a different rule about a different statement. So if
     * {@link QuestionRepository#findReferencingExams} were wrong, a referenced question would
     * quietly leave the bank and nothing underneath would catch it. That is why the query carries
     * a two-engine repository test naming the constraint it stands in for.
     *
     * @param session a session inside the caller's transaction
     * @param caller  the authenticated author, already role-checked
     * @param ask     which question, and the version she was looking at
     * @return whether it was deleted, which exams blocked it, or why the request was refused
     */
    public DeleteResolution delete(Session session, CallerContext caller,
                                   QuestionDeleteRequest ask) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(ask, "ask");

        Optional<Question> found = reachableForWriting(session, caller, ask.displayId5());
        if (found.isEmpty()) {
            return new DeleteResolution(DeleteStatus.NOT_FOUND, null);
        }
        Question question = found.get();

        Optional<QuestionVersion> latest =
                questions.findLatestVersionForAuthoring(session, question.getId());
        if (latest.isEmpty() || latest.get().getVersionNo() != ask.baseVersionNo()) {
            return new DeleteResolution(DeleteStatus.STALE, null);
        }

        List<ReferencingExam> blocking = questions.findReferencingExams(session, question.getId());
        if (!blocking.isEmpty()) {
            log.debug("Delete of question {} blocked by {} exam(s)",
                    question.getDisplayId(), blocking.size());
            return new DeleteResolution(DeleteStatus.RESOLVED,
                    new DeleteOutcome(false, blocking.stream()
                            .map(exam -> new BlockingExam(exam.displayId(), exam.name()))
                            .toList()));
        }

        // A managed entity: the stamp is flushed with the transaction, and the serial stays
        // spoken for so a later question in this course never reuses it (T-2.8).
        question.setDeletedAt(clock.instant());
        log.debug("Question {} soft-deleted by user {}",
                question.getDisplayId(), caller.userId());
        return new DeleteResolution(DeleteStatus.RESOLVED, new DeleteOutcome(true, List.of()));
    }

    // ===================== Shared ========================================

    /**
     * The stored question a write may touch, or empty when the caller may not know it exists.
     *
     * <p>One method for both write verbs so the two cannot drift into different answers about the
     * same question. It folds three distinguishable conditions into one empty result on purpose,
     * per the contract's section 6: unknown display id, soft-deleted question, and a course the
     * caller does not teach are indistinguishable to her, so none of the three can be used to
     * enumerate the bank.
     *
     * <p>{@link QuestionRepository#findActiveByDisplayId} rather than {@code findByDisplayId}: the
     * latter deliberately still sees soft-deleted rows, because the seed loader's idempotency
     * check needs them to count as existing. Reusing it here is exactly the bug T-2.8 tests for.
     *
     * @param session   a session inside the caller's transaction
     * @param caller    the authenticated author, already role-checked
     * @param displayId the 5-character display id as submitted
     * @return the question, or empty
     */
    private Optional<Question> reachableForWriting(Session session, CallerContext caller,
                                                   String displayId) {
        Optional<Question> found = questions.findActiveByDisplayId(session, strip(displayId));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        boolean teaches = Authorization.teachesCourse(caller, found.get().getCourseCode(),
                (teacherId, code) -> courses.teaches(session, teacherId, code));
        return teaches ? found : Optional.empty();
    }

    /**
     * Builds one version row.
     *
     * <p><b>Reads {@code answers.get(0..3)} directly</b>, which is safe only because the
     * validator's structural rules have already run: see this class's note on E1.11.
     *
     * @param questionId    the identity row this version belongs to
     * @param versionNo     which version this is
     * @param text          the stem
     * @param answers       exactly four non-null answers, in display order
     * @param correctAnswer 1-based position of the correct one (C-8)
     * @param topic         the topic as typed
     * @param difficulty    the stored difficulty
     * @param image         the illustration, or null
     * @param authorId      who wrote this version
     * @return the unsaved version
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private QuestionVersion versionOf(long questionId, int versionNo, String text,
                                      List<String> answers, int correctAnswer, String topic,
                                      server.db.entities.Difficulty difficulty,
                                      byte[] image, long authorId) {
        return new QuestionVersion(questionId, versionNo, text,
                answers.get(0), answers.get(1), answers.get(2), answers.get(3),
                (byte) correctAnswer, topic, difficulty, image, authorId, clock.instant());
    }

    /**
     * The wire view of one version.
     *
     * <p>Staff-only by the contract's section 2, correct answer included: the principal reads all
     * data as entered and the correct answer is entered data. Nothing student-reachable calls
     * this.
     *
     * @param session         a session inside the caller's transaction
     * @param question        the identity row
     * @param version         the version to describe
     * @param latestVersionNo which version is currently newest, for the detail pane's "v2 of 3"
     * @return the detail payload
     */
    private QuestionDetail detailOf(Session session, Question question, QuestionVersion version,
                                    int latestVersionNo) {
        // Delegated rather than done here since the read half landed: QUESTION_GET answers with
        // the same QuestionDetail this method builds, and two mappers agreeing today is exactly
        // the "two expressions of one rule, checked against each other nowhere" the contract's
        // section 5 keeps closing. See BankDetails.
        return details.detail(session, question, version, latestVersionNo);
    }

    /**
     * Wire difficulty to stored difficulty.
     *
     * <p>{@code valueOf} over the name rather than a switch, because {@code BankDtoTest} asserts
     * the two enums are member-for-member identical, so a member added to one and not the other
     * fails a test rather than falling through a default nobody reads.
     *
     * <p>Null-tolerant and package-private because {@link BankHandlers} maps the same value one
     * step earlier, while building the validator's {@code Fields} from a payload whose difficulty
     * has not been checked yet. A missing difficulty is a violation with a sentence, not an
     * exception, so it has to survive the mapping to reach the rule that names it.
     *
     * @param difficulty the wire value, possibly null before validation
     * @return the stored value, or null
     */
    static server.db.entities.Difficulty entityDifficulty(
            common.dto.bank.Difficulty difficulty) {
        return difficulty == null ? null : server.db.entities.Difficulty.valueOf(difficulty.name());
    }

    /**
     * {@code strip()}, never {@code trim()}.
     *
     * <p>The two are different functions: {@code trim()} cuts only characters at or below U+0020,
     * so a course code carrying a Unicode space survives it. {@code courses.code2} is
     * {@code CHAR(2)} under a PAD SPACE collation, so such a code matches the row in SQL while
     * failing Java equality against the reachable set, which is two authorization answers for one
     * input. The service strips regardless of what any DTO does.
     *
     * @param value the value as submitted, possibly null
     * @return the stripped value, or null
     */
    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
