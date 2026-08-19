package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import server.db.converters.BotTranscriptConverter;

import java.time.Instant;

/**
 * One student's conversation with one bot — {@code bot_sessions} (V6, §5, S-33, F12.9).
 *
 * <p>The transcript here is the student's own history, which they can reopen and
 * continue (F12.10). Analytics never read it: every turn is dual-written as a
 * {@link BotMessage} row in the same transaction, and the teacher aggregates of S-34
 * query that table instead. The split is what lets the analytics stay genuinely
 * anonymous while the student still gets their conversation back word for word.
 */
@Entity
@Table(name = "bot_sessions")
public class BotSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "bot_id", nullable = false)
    private long botId;

    @Column(name = "student_id", nullable = false)
    private long studentId;

    @Column(name = "started_at", nullable = false, precision = 3)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false, precision = 3)
    private Instant updatedAt;

    @Convert(converter = BotTranscriptConverter.class)
    @Column(name = "transcript", length = ColumnSizes.MEDIUM, nullable = false)
    private BotTranscript transcript;

    /** Required by JPA. */
    protected BotSession() {
    }

    public BotSession(long botId, long studentId, Instant startedAt) {
        this.botId = botId;
        this.studentId = studentId;
        this.startedAt = startedAt;
        this.updatedAt = startedAt;
        this.transcript = BotTranscript.empty();
    }

    public Long getId() {
        return id;
    }

    public long getBotId() {
        return botId;
    }

    public long getStudentId() {
        return studentId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public BotTranscript getTranscript() {
        return transcript;
    }

    public void setTranscript(BotTranscript transcript, Instant updatedAt) {
        this.transcript = transcript;
        this.updatedAt = updatedAt;
    }
}
