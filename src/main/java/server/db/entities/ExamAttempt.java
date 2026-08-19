package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One student's sitting of one execution — {@code exam_attempts} (V4, §5, S-18, S-19).
 *
 * <p>A student gets exactly one attempt per execution, and that is enforced by a unique
 * key on {@code (execution_id, student_id)} rather than by a check in the service: two
 * simultaneous starts cannot both succeed at the storage level, whatever the UI does.
 *
 * <p><b>This entity deliberately has no {@code @Version} field</b>, and it is the one
 * table §5 excludes from optimistic locking. The race here is real — the server's
 * time-up force-submit (ADR-010, F6.4) can fire at the same instant the student presses
 * submit (F6.9) — but a version column is the wrong tool for it, because both writers
 * are legitimate and one must simply lose quietly. The lead settled it in the E2 PR 1
 * review: transitions out of {@link AttemptStatus#IN_PROGRESS} go through a
 * status-guarded atomic update
 * ({@code UPDATE … SET status = ? WHERE id = ? AND status = 'IN_PROGRESS'}). Whoever
 * changes a row wins; the other sees zero rows affected and stops. That also gives
 * PRD §6's "answer arriving after expiry → rejected server-side" for free.
 *
 * <p>So: do not add {@code @Version} here, and do not close an attempt by loading it,
 * mutating {@link #status} and flushing — that reintroduces the last-write-wins bug the
 * guard exists to prevent.
 */
@Entity
@Table(name = "exam_attempts", uniqueConstraints =
        @UniqueConstraint(name = "uq_exam_attempts_student", columnNames = {"execution_id", "student_id"}))
public class ExamAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "execution_id", nullable = false)
    private long executionId;

    @Column(name = "student_id", nullable = false)
    private long studentId;

    @Column(name = "started_at", nullable = false, precision = 3)
    private Instant startedAt;

    @Column(name = "ended_at", precision = 3)
    private Instant endedAt;

    @Column(name = "actual_minutes")
    private Integer actualMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AttemptStatus status;

    /** Required by JPA. */
    protected ExamAttempt() {
    }

    public ExamAttempt(long executionId, long studentId, Instant startedAt) {
        this.executionId = executionId;
        this.studentId = studentId;
        this.startedAt = startedAt;
        this.status = AttemptStatus.IN_PROGRESS;
    }

    public Long getId() {
        return id;
    }

    public long getExecutionId() {
        return executionId;
    }

    public long getStudentId() {
        return studentId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    /** How long the student actually took, recorded on submit (S-19). */
    public Integer getActualMinutes() {
        return actualMinutes;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    /** True while answers may still be saved. */
    public boolean isInProgress() {
        return status == AttemptStatus.IN_PROGRESS;
    }
}
