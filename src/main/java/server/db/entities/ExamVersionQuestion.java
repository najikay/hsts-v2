package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.io.Serializable;
import java.util.Objects;

/**
 * One question's place in one exam version — {@code exam_version_questions} (V3, §5).
 *
 * <p>Points across a version must sum to 100. That is a cross-row rule, so it lives in
 * the service plus a test rather than in a {@code CHECK} (§5).
 *
 * <p><b>{@link #questionId} is denormalised on purpose and is not free-floating.</b> It
 * exists so {@code UNIQUE(exam_version_id, question_id)} can forbid the same question
 * appearing twice through two different versions of itself (PRD §6) — something a table
 * keyed only on the version id cannot express. The pair is held honest by a composite
 * foreign key onto {@code question_versions (id, question_id)}, so this field cannot
 * disagree with {@link #getQuestionVersionId()}: the database refuses the row. Write
 * both from the same source and never derive one from stale state.
 *
 * <p>{@code ord} is unique within a version, which makes reordering a full replace of
 * the composition rather than an in-place swap — MySQL checks unique indexes row by
 * row, so two updates trading places fail on the first. The lead settled this in the
 * E2 PR 1 review: the constraint stays and E7 replaces.
 */
@Entity
@Table(name = "exam_version_questions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_exam_version_questions_question",
                columnNames = {"exam_version_id", "question_id"}),
        @UniqueConstraint(name = "uq_exam_version_questions_ord",
                columnNames = {"exam_version_id", "ord"})
})
public class ExamVersionQuestion {

    @EmbeddedId
    private Id id;

    @Column(name = "question_id", nullable = false)
    private long questionId;

    @Column(name = "points", nullable = false)
    private int points;

    @Column(name = "ord", nullable = false)
    private int ordinal;

    /** Required by JPA. */
    protected ExamVersionQuestion() {
    }

    public ExamVersionQuestion(long examVersionId, long questionVersionId, long questionId,
                               int points, int ordinal) {
        this.id = new Id(examVersionId, questionVersionId);
        this.questionId = questionId;
        this.points = points;
        this.ordinal = ordinal;
    }

    public Id getId() {
        return id;
    }

    public long getExamVersionId() {
        return id.getExamVersionId();
    }

    public long getQuestionVersionId() {
        return id.getQuestionVersionId();
    }

    public long getQuestionId() {
        return questionId;
    }

    public int getPoints() {
        return points;
    }

    public int getOrdinal() {
        return ordinal;
    }

    /** Composite primary key {@code (exam_version_id, question_version_id)}. */
    @Embeddable
    public static class Id implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "exam_version_id", nullable = false)
        private long examVersionId;

        @Column(name = "question_version_id", nullable = false)
        private long questionVersionId;

        /** Required by JPA. */
        protected Id() {
        }

        public Id(long examVersionId, long questionVersionId) {
            this.examVersionId = examVersionId;
            this.questionVersionId = questionVersionId;
        }

        public long getExamVersionId() {
            return examVersionId;
        }

        public long getQuestionVersionId() {
            return questionVersionId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Id that)) {
                return false;
            }
            return examVersionId == that.examVersionId && questionVersionId == that.questionVersionId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(examVersionId, questionVersionId);
        }

        @Override
        public String toString() {
            return examVersionId + "/" + questionVersionId;
        }
    }
}
