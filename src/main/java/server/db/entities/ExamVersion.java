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
 * One version of an exam definition — {@code exam_versions} (V3, §5, C-2, F4).
 *
 * <p>Approval and scheduling bind to a specific <em>version</em> (S-14), so an approved
 * exam can never be quietly edited underneath a release: editing an approved or pending
 * exam produces version n+1 in {@link ExamVersionStatus#DRAFT}, and the old version
 * stays exactly as it was.
 *
 * <p>{@link #status} is the one field on this row that legitimately changes, which is
 * why the table carries {@code lock_version}: two coordinators racing to approve and
 * reject the same submission both write here, and the stale one is rejected with
 * {@code CONFLICT} rather than silently winning.
 *
 * <p>{@link #studentText} and {@link #teacherText} are nullable by decision (lead, E2
 * PR 1 review round 2) — an exam may carry instructions for neither audience.
 */
@Entity
@Table(name = "exam_versions", uniqueConstraints =
        @UniqueConstraint(name = "uq_exam_versions_no", columnNames = {"exam_id", "version_no"}))
public class ExamVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "exam_id", nullable = false)
    private long examId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "name", length = 150, nullable = false)
    private String name;

    @Column(name = "duration_min", nullable = false)
    private int durationMinutes;

    @Column(name = "student_text", length = ColumnSizes.TEXT)
    private String studentText;

    @Column(name = "teacher_text", length = ColumnSizes.TEXT)
    private String teacherText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExamVersionStatus status;

    @Column(name = "rejected_reason", length = ColumnSizes.TEXT)
    private String rejectedReason;

    @Column(name = "created_at", nullable = false, precision = 3)
    private Instant createdAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    /** Required by JPA. */
    protected ExamVersion() {
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public ExamVersion(long examId, int versionNo, String name, int durationMinutes,
                       String studentText, String teacherText,
                       ExamVersionStatus status, Instant createdAt) {
        this.examId = examId;
        this.versionNo = versionNo;
        this.name = name;
        this.durationMinutes = durationMinutes;
        this.studentText = studentText;
        this.teacherText = teacherText;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public long getExamId() {
        return examId;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public String getName() {
        return name;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public String getStudentText() {
        return studentText;
    }

    public String getTeacherText() {
        return teacherText;
    }

    public ExamVersionStatus getStatus() {
        return status;
    }

    public String getRejectedReason() {
        return rejectedReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getLockVersion() {
        return lockVersion;
    }

    /** Approves this version; only an approved version may be released (E9.1). */
    public void approve() {
        this.status = ExamVersionStatus.APPROVED;
        this.rejectedReason = null;
    }

    /**
     * Rejects this version with the reason F4 requires, which travels back to the
     * author (S-14).
     *
     * @param reason why it was rejected; the service enforces that it is present
     */
    public void reject(String reason) {
        this.status = ExamVersionStatus.REJECTED;
        this.rejectedReason = reason;
    }

    /** Moves a draft into the coordinator's queue (F4). */
    public void submitForApproval() {
        this.status = ExamVersionStatus.PENDING;
    }

    /**
     * Rewrites the metadata of a draft in place (E7.2 — {@code EXAM_VERSION_SAVE}).
     *
     * <p>One method rather than four setters, because these four fields are always written
     * together by the one verb that writes them: a save carries the whole form. Setters would
     * also make the row look editable from anywhere, and the contract's §5.4 says only a
     * {@code DRAFT} may be saved.
     *
     * <p><b>This method does not check that status.</b> {@code ExamService} does, and answers
     * {@code CONFLICT} rather than {@code VALIDATION} when the world has moved underneath the
     * form. Putting the check here as well would put one rule in two places, and the entity is
     * the copy with no way to produce that answer.
     *
     * <p>{@code status}, {@code versionNo} and {@code rejectedReason} are deliberately untouched.
     * A save never changes what a version <em>is</em>; editing an exam that is no longer a draft
     * is {@code EXAM_VERSION_REVISE}, which creates a new row.
     *
     * @param name            the exam's name on this version
     * @param durationMinutes the sitting length
     * @param studentText     the student-facing block, or {@code null}
     * @param teacherText     the teacher-only block, or {@code null}
     */
    public void editDraft(String name, int durationMinutes, String studentText,
                          String teacherText) {
        this.name = name;
        this.durationMinutes = durationMinutes;
        this.studentText = studentText;
        this.teacherText = teacherText;
    }
}
