package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One question and answer, normalised for analytics — {@code bot_messages}
 * (V6, §5, S-34, ADR-018).
 *
 * <p>Written in the same transaction as the JSON transcript it duplicates. That
 * duplication is deliberate: aggregates over "how many questions this week, on what
 * topics" are a query over rows, not a scan of a thousand JSON blobs, and the teacher
 * view of S-34 has to be fast enough to open casually.
 *
 * <p><b>{@link #studentId} exists here but must never leave the server.</b> It is stored
 * so a student's own history can be reassembled and so C-4's integrity alert can name
 * the right person to their teacher — but the S-34 analytics DTOs carry no identity
 * fields at all, and the screen shows totals and topics, never names. Any projection
 * built over this table for a teacher-facing aggregate must omit this column
 * structurally, the same way the take-exam projection omits {@code correct_answer}.
 *
 * <p>{@link #provider} records which adapter actually answered, so the fallback chain of
 * ADR-009 is measurable after the fact rather than guessed at.
 */
@Entity
@Table(name = "bot_messages")
public class BotMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "bot_id", nullable = false)
    private long botId;

    @Column(name = "session_id", nullable = false)
    private long sessionId;

    @Column(name = "student_id", nullable = false)
    private long studentId;

    @Column(name = "question", length = ColumnSizes.TEXT, nullable = false)
    private String question;

    @Column(name = "answer", length = ColumnSizes.MEDIUM, nullable = false)
    private String answer;

    @Column(name = "provider", length = 40, nullable = false)
    private String provider;

    @Column(name = "asked_at", nullable = false, precision = 3)
    private Instant askedAt;

    /** Required by JPA. */
    protected BotMessage() {
    }

    public BotMessage(long botId, long sessionId, long studentId,
                      String question, String answer, String provider, Instant askedAt) {
        this.botId = botId;
        this.sessionId = sessionId;
        this.studentId = studentId;
        this.question = question;
        this.answer = answer;
        this.provider = provider;
        this.askedAt = askedAt;
    }

    public Long getId() {
        return id;
    }

    public long getBotId() {
        return botId;
    }

    public long getSessionId() {
        return sessionId;
    }

    /** Server-side only — see the class note before putting this in any DTO. */
    public long getStudentId() {
        return studentId;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
    }

    /** Which provider answered — DeepSeek, Anthropic, or the fallback (ADR-009). */
    public String getProvider() {
        return provider;
    }

    public Instant getAskedAt() {
        return askedAt;
    }
}
