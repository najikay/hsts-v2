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
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * The mark for one attempt — {@code grades} (V5, §5, C-3, F8, S-22..S-24).
 *
 * <p>Two stages, in a fixed order: the machine scores, a teacher approves, and only then
 * may the student see anything (C-3). A grade still sitting at {@link GradeStatus#AUTO}
 * is invisible outside the staff room, and E13.2 filters on exactly that.
 *
 * <p><b>{@link #autoScore} is never overwritten.</b> An override writes
 * {@link #finalScore} and leaves the machine's number beside it, so "the teacher changed
 * this, from that, for this reason" stays answerable forever — which is the whole point
 * of S-23. A reason is required whenever the two differ; that is a cross-column rule and
 * lives in the service (F8).
 */
@Entity
@Table(name = "grades", uniqueConstraints =
        @UniqueConstraint(name = "uq_grades_attempt", columnNames = "attempt_id"))
public class Grade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private long attemptId;

    @Column(name = "auto_score", nullable = false)
    private int autoScore;

    @Column(name = "final_score")
    private Integer finalScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private GradeStatus status;

    @Column(name = "override_reason", length = ColumnSizes.TEXT)
    private String overrideReason;

    @Column(name = "teacher_comment", length = ColumnSizes.TEXT)
    private String teacherComment;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at", precision = 3)
    private Instant approvedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    /** Required by JPA. */
    protected Grade() {
    }

    public Grade(long attemptId, int autoScore) {
        this.attemptId = attemptId;
        this.autoScore = autoScore;
        this.status = GradeStatus.AUTO;
    }

    public Long getId() {
        return id;
    }

    public long getAttemptId() {
        return attemptId;
    }

    /** What the machine computed. Never changes, even after an override (S-23). */
    public int getAutoScore() {
        return autoScore;
    }

    public Integer getFinalScore() {
        return finalScore;
    }

    /**
     * The score that counts: the override if there is one, otherwise the machine's.
     *
     * @return the effective score out of 100
     */
    public int getEffectiveScore() {
        return finalScore == null ? autoScore : finalScore;
    }

    public GradeStatus getStatus() {
        return status;
    }

    public String getOverrideReason() {
        return overrideReason;
    }

    public String getTeacherComment() {
        return teacherComment;
    }

    public Long getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public int getLockVersion() {
        return lockVersion;
    }

    /** True once the student is allowed to see this (C-3). */
    public boolean isVisibleToStudent() {
        return status == GradeStatus.APPROVED;
    }

    /**
     * Replaces the machine's score, keeping it for the audit trail (S-23).
     *
     * @param score  the teacher's score
     * @param reason why — required by F8 whenever it differs from the auto score
     */
    public void override(int score, String reason) {
        this.finalScore = score;
        this.overrideReason = reason;
    }

    public void setTeacherComment(String teacherComment) {
        this.teacherComment = teacherComment;
    }

    /** Releases the grade to the student (C-3, S-24). */
    public void approve(long approverId, Instant at) {
        this.status = GradeStatus.APPROVED;
        this.approvedBy = approverId;
        this.approvedAt = at;
        if (this.finalScore == null) {
            this.finalScore = this.autoScore;
        }
    }
}
