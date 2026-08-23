package common.dto.authoring;

import common.dto.approval.ApprovalState;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One exam version, whole: what every verb on this wire but the auto-composer answers with
 * (Common tier, E7 — F3.1, F3.5, F3.6).
 *
 * <h2>One payload, one path after every write</h2>
 *
 * <p>{@code EXAM_VERSION_GET}, {@code EXAM_CREATE}, {@code EXAM_VERSION_SAVE},
 * {@code EXAM_VERSION_REVISE} and {@code EXAM_SUBMIT} all answer with this record, so the client
 * has exactly one thing to do after a save, a revise and a submit: render what came back. A
 * screen with five response shapes would have five ways of being subtly out of date.
 *
 * <p>It is <b>re-read from the database after the write</b> rather than patched together from
 * the request, for the same reason {@code ApprovalDecision} carries a re-read row: a client
 * assembling its own new state is guessing at {@link #versionNo()} and {@link #lockVersion()},
 * and it will guess wrong exactly once — the once that matters, because the next optimistic
 * write will then be refused with a {@code CONFLICT} nobody can explain.
 *
 * <h2>{@code state} is what makes the read-only case work</h2>
 *
 * <p>The builder opens a {@code DRAFT} with this and the history panel renders a past version
 * read-only with the same record (E7.14). The client decides what is editable from
 * {@link #state()} — {@link #isEditable()} is that decision, written once here so a past version
 * and a live draft can never render from two shapes that drift.
 *
 * <h2>No lock-holder field</h2>
 *
 * <p>The live "being edited by" state rides E18.8's {@code LOCK_WATCH} / {@code LOCKS_SNAPSHOT}
 * under the existing {@code EntityRef.EXAM_VERSION} constant (F10.0). Two expressions of one
 * fact drift, and viewing a list should never contend for a lock. {@link #lockVersion()} is a
 * different thing entirely: it is the optimistic token, not a claim about who has the editor
 * open.
 *
 * @param examId          the exam this version belongs to
 * @param displayId6      the 6-digit id staff quote when they talk about an exam (S-10)
 * @param courseCode      the owning course's code
 * @param courseName      the owning course's name, so the header is readable without a lookup
 * @param examVersionId   this version's id, and what {@link ExamVersionAction} addresses
 * @param versionNo       which version this is, 1-based, backed by {@code uq_exam_versions_no}
 * @param state           where it sits in F3.6's lifecycle, reusing E8's enum rather than a
 *                        second bridge over one column
 * @param name            the exam's name <em>as of this version</em>: F3.5's edit-makes-a-version
 *                        means a rename is a version, so a v2 name and a v3 name may differ
 * @param durationMinutes how long students get
 * @param studentText     the instructions printed on the paper, or {@code null}
 * @param teacherText     the notes only staff ever read, or {@code null}
 * @param authorName      who wrote it, resolved server-side from the recorded author (S-12);
 *                        never a caller id, which is why no request on this wire carries one
 * @param createdAt       when this version was written, UTC (ADR-010)
 * @param rejectedReason  <b>{@code ""} unless {@code state} is {@code REJECTED}</b>, never
 *                        {@code null}: F4.2 requires the reason to be visible ON the exam, and a
 *                        nullable field would have the screen guessing which empty it is looking
 *                        at. It carries E8's superseded sentence too
 * @param questions       the composition in paper order; never {@code null}
 * @param lockVersion     the optimistic token to send back on the next write
 */
public record ExamComposition(long examId,
                              String displayId6,
                              String courseCode,
                              String courseName,
                              long examVersionId,
                              int versionNo,
                              ApprovalState state,
                              String name,
                              int durationMinutes,
                              String studentText,
                              String teacherText,
                              String authorName,
                              Instant createdAt,
                              String rejectedReason,
                              List<ComposedQuestion> questions,
                              int lockVersion) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Null-checks everything the server always knows, and copies the composition.
     *
     * <p>Outbound, so it throws: a null {@code state} or a null {@code questions} here is a
     * server bug, and surfacing it at build time is cheaper than a screen that renders an exam
     * with no status chip. The two texts are genuinely optional and are the only strings not
     * checked; {@code rejectedReason} is <b>not</b> among them, because {@code ""} is its empty
     * and the contract fixes that.
     *
     * <p>{@link List#copyOf} rather than the tolerant copy the request records use — and
     * deliberately the strict one. This list is assembled by the server from rows it just read,
     * so a null element is not a malformed payload to be refused politely, it is a defect in the
     * assembler.
     */
    public ExamComposition {
        Objects.requireNonNull(displayId6, "displayId6");
        Objects.requireNonNull(courseCode, "courseCode");
        Objects.requireNonNull(courseName, "courseName");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(authorName, "authorName");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(rejectedReason, "rejectedReason");
        questions = List.copyOf(Objects.requireNonNull(questions, "questions"));
    }

    /** @return how many questions are on the paper. */
    public int questionCount() {
        return questions.size();
    }

    /**
     * @return the points on the paper, which contract section 1 guarantees is
     *         {@link ExamCreateRequest#POINTS_TOTAL} for every <em>stored</em> version. Summed
     *         here so the builder's live indicator (E7.3) and a test of the invariant count the
     *         same way
     */
    public int totalPoints() {
        int total = 0;
        for (ComposedQuestion question : questions) {
            total += question.points();
        }
        return total;
    }

    /**
     * @return {@code true} when this version may still be changed, which is {@code DRAFT} and
     *         nothing else (F3.6, section 5.4). The client's read-only decision, in one place
     */
    public boolean isEditable() {
        return state == ApprovalState.DRAFT;
    }

    /**
     * @return {@code true} when there is a rejection sentence worth showing on the exam (F4.2).
     *         A {@code REJECTED} version whose reason is somehow blank still answers
     *         {@code false}, because the screen has nothing to draw either way
     */
    public boolean hasRejectedReason() {
        return !rejectedReason.isBlank();
    }

    /** @return {@code true} when any question on the paper has been superseded in the bank
     *          (E7.7), which is what puts the "questions have newer versions" banner on the
     *          builder rather than only a badge on a row. */
    public boolean hasStaleQuestion() {
        return questions.stream().anyMatch(ComposedQuestion::hasNewerVersion);
    }
}
