package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * What one student chose for one question — {@code attempt_answers} (V4, §5, F6).
 *
 * <p>Written by autosave as the student moves through the form, one row per question, so
 * a disconnect mid-exam loses nothing: on reconnect the saved rows come straight back
 * (F6.8 / E10.6).
 *
 * <p>{@link #selected} is null while the question is untouched — deliberately distinct
 * from "answered wrongly", because the two mean different things to the student and to
 * the grader.
 *
 * <p>The row keys on the question <em>version</em>, not the question, so a grade computed
 * next year still marks against the exact wording that was on the screen.
 */
@Entity
@Table(name = "attempt_answers")
public class AttemptAnswer {

    @EmbeddedId
    private Id id;

    @Column(name = "selected")
    private Byte selected;

    @Column(name = "saved_at", nullable = false, precision = 3)
    private Instant savedAt;

    /** Required by JPA. */
    protected AttemptAnswer() {
    }

    public AttemptAnswer(long attemptId, long questionVersionId, Byte selected, Instant savedAt) {
        this.id = new Id(attemptId, questionVersionId);
        this.selected = selected;
        this.savedAt = savedAt;
    }

    public Id getId() {
        return id;
    }

    public long getAttemptId() {
        return id.getAttemptId();
    }

    public long getQuestionVersionId() {
        return id.getQuestionVersionId();
    }

    /** @return 1..4, or {@code null} if the student has not answered this question */
    public Byte getSelected() {
        return selected;
    }

    public boolean isAnswered() {
        return selected != null;
    }

    public Instant getSavedAt() {
        return savedAt;
    }

    public void select(Byte selected, Instant savedAt) {
        this.selected = selected;
        this.savedAt = savedAt;
    }

    /** Composite primary key {@code (attempt_id, question_version_id)}. */
    @Embeddable
    public static class Id implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "attempt_id", nullable = false)
        private long attemptId;

        @Column(name = "question_version_id", nullable = false)
        private long questionVersionId;

        /** Required by JPA. */
        protected Id() {
        }

        public Id(long attemptId, long questionVersionId) {
            this.attemptId = attemptId;
            this.questionVersionId = questionVersionId;
        }

        public long getAttemptId() {
            return attemptId;
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
            return attemptId == that.attemptId && questionVersionId == that.questionVersionId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(attemptId, questionVersionId);
        }

        @Override
        public String toString() {
            return attemptId + "/" + questionVersionId;
        }
    }
}
