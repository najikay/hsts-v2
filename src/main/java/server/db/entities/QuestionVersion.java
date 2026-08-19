package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One immutable wording of a question — {@code question_versions} (V2, §5, C-2, C-8).
 *
 * <p>Immutable by decision, not merely by habit (ADR-011): an edit inserts a new row
 * with {@code versionNo + 1}, and nothing rewrites an existing one, because an exam
 * that was sat last month must still render exactly as it was sat. Only Hibernate may
 * construct one from the database; the class exposes no setters.
 *
 * <p><b>{@link #correctAnswer} is the most sensitive field in the schema.</b> It is
 * 1..4 — exactly one correct answer (C-8 / ADR-016) — and it must never reach a
 * student. That is guaranteed structurally rather than by care: the take-exam path
 * (E2.12) uses a separate projection type with no field it could be written into, so
 * the mapper that builds the student's form cannot see this column at all. Do not add
 * a convenience method here that hands it out.
 *
 * <p><b>{@link #image} loads with the row.</b> Worth stating plainly, because the obvious
 * annotation for avoiding that — {@code @Basic(fetch = LAZY)} — is a no-op here: lazy
 * <em>basic</em> attributes need build-time bytecode enhancement, this build has no
 * enhancement plugin, and JPA is free to ignore the hint. It was written that way first,
 * and the persister duly reported the attribute as eager. An annotation that reads as a
 * solved problem is worse than no annotation at all.
 *
 * <p>So the bank listing of E6.9 must not load this entity. It selects a scalar
 * projection that never names the {@code image} column — the same structural move E2.12
 * makes for {@code correct_answer}, and for the same reason: a query cannot drag what it
 * does not mention. E6.6 fetches the bytes on demand through its own verb.
 */
@Entity
@Table(name = "question_versions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_question_versions_no", columnNames = {"question_id", "version_no"}),
        @UniqueConstraint(name = "uq_question_versions_identity", columnNames = {"id", "question_id"})
})
public class QuestionVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "question_id", nullable = false)
    private long questionId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "text", length = ColumnSizes.TEXT, nullable = false)
    private String text;

    @Column(name = "a1", length = 500, nullable = false)
    private String a1;

    @Column(name = "a2", length = 500, nullable = false)
    private String a2;

    @Column(name = "a3", length = 500, nullable = false)
    private String a3;

    @Column(name = "a4", length = 500, nullable = false)
    private String a4;

    @Column(name = "correct_answer", nullable = false)
    private byte correctAnswer;

    @Column(name = "topic", length = 100, nullable = false)
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false)
    private Difficulty difficulty;

    @Lob
    @Column(name = "image", length = ColumnSizes.MEDIUM)
    private byte[] image;

    @Column(name = "created_by", nullable = false)
    private long createdBy;

    @Column(name = "created_at", nullable = false, precision = 3)
    private Instant createdAt;

    /** Required by JPA. */
    protected QuestionVersion() {
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public QuestionVersion(long questionId, int versionNo, String text,
                           String a1, String a2, String a3, String a4,
                           byte correctAnswer, String topic, Difficulty difficulty,
                           byte[] image, long createdBy, Instant createdAt) {
        this.questionId = questionId;
        this.versionNo = versionNo;
        this.text = text;
        this.a1 = a1;
        this.a2 = a2;
        this.a3 = a3;
        this.a4 = a4;
        this.correctAnswer = correctAnswer;
        this.topic = topic;
        this.difficulty = difficulty;
        this.image = image == null ? null : image.clone();
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public long getQuestionId() {
        return questionId;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public String getText() {
        return text;
    }

    public String getA1() {
        return a1;
    }

    public String getA2() {
        return a2;
    }

    public String getA3() {
        return a3;
    }

    public String getA4() {
        return a4;
    }

    /**
     * The 1-based index of the single correct answer.
     *
     * <p><b>Server-side only.</b> Grading (E12.1) reads this; nothing that builds a
     * student-facing payload may. See the class note.
     *
     * @return 1, 2, 3 or 4
     */
    public byte getCorrectAnswer() {
        return correctAnswer;
    }

    public String getTopic() {
        return topic;
    }

    public Difficulty getDifficulty() {
        return difficulty;
    }

    /** @return a defensive copy, or {@code null} when the question has no illustration */
    public byte[] getImage() {
        return image == null ? null : image.clone();
    }

    public boolean hasImage() {
        return image != null && image.length > 0;
    }

    public long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
