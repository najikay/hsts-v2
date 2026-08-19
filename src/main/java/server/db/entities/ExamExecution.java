package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import server.db.converters.ExecutionStatsConverter;
import server.db.converters.ParticipationConverter;

import java.time.Instant;

/**
 * One occasion of an exam being taken — {@code exam_executions} (V4, §5, C-1, C-6).
 *
 * <p>The counterpart to {@link ExamVersion}: the version is the definition, this is one
 * sitting of it, and an approved version may have many (S-2). Each carries its own
 * window, its own 4-character entry code, and — once closed — its own frozen statistics.
 *
 * <p><b>There are no participation counter columns, on purpose.</b> While the execution
 * is live, the started/finished/timed-out counts are derived by counting
 * {@link ExamAttempt} rows by status; nothing increments a column, so nothing can race
 * on submit or drift after a crash. {@link #participation} is written once, at close,
 * to freeze those numbers into the documentation record (S-21).
 *
 * <p>All timing is server-authoritative (ADR-010): the client countdown is cosmetic, and
 * these two timestamps are what timers are re-armed from after a server restart (E3.9).
 */
@Entity
@Table(name = "exam_executions")
public class ExamExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "exam_version_id", nullable = false)
    private long examVersionId;

    @Column(name = "code", columnDefinition = "char(4)", nullable = false)
    private String code;

    @Column(name = "open_at", nullable = false, precision = 3)
    private Instant openAt;

    @Column(name = "close_at", nullable = false, precision = 3)
    private Instant closeAt;

    @Column(name = "extra_minutes", nullable = false)
    private int extraMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExecutionStatus status;

    @Column(name = "created_by", nullable = false)
    private long createdBy;

    @Convert(converter = ExecutionStatsConverter.class)
    @Column(name = "stats", length = ColumnSizes.MEDIUM)
    private ExecutionStats stats;

    @Convert(converter = ParticipationConverter.class)
    @Column(name = "participation", length = ColumnSizes.MEDIUM)
    private Participation participation;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    /** Required by JPA. */
    protected ExamExecution() {
    }

    public ExamExecution(long examVersionId, String code, Instant openAt, Instant closeAt,
                         ExecutionStatus status, long createdBy) {
        this.examVersionId = examVersionId;
        this.code = code;
        this.openAt = openAt;
        this.closeAt = closeAt;
        this.status = status;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public long getExamVersionId() {
        return examVersionId;
    }

    public String getCode() {
        return code;
    }

    public Instant getOpenAt() {
        return openAt;
    }

    public Instant getCloseAt() {
        return closeAt;
    }

    public int getExtraMinutes() {
        return extraMinutes;
    }

    /**
     * The moment this execution actually ends, extensions included (S-20).
     *
     * <p>An extension applies to the execution rather than to one student, so every
     * active attempt gains the same minutes and every timer is re-armed from here.
     *
     * @return close time plus any granted extension
     */
    public Instant getEffectiveCloseAt() {
        return closeAt.plusSeconds(extraMinutes * 60L);
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public long getCreatedBy() {
        return createdBy;
    }

    public ExecutionStats getStats() {
        return stats;
    }

    public Participation getParticipation() {
        return participation;
    }

    public int getLockVersion() {
        return lockVersion;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
    }

    public void setCloseAt(Instant closeAt) {
        this.closeAt = closeAt;
    }

    /** Grants extra minutes to everyone sitting this execution (F7.1, S-20). */
    public void addExtraMinutes(int minutes) {
        this.extraMinutes += minutes;
    }

    public void setStats(ExecutionStats stats) {
        this.stats = stats;
    }

    /** Freezes the derived counts at close (S-21). */
    public void setParticipation(Participation participation) {
        this.participation = participation;
    }
}
