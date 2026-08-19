package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * The identity row of a bank question — {@code questions} (V2, §5, C-2 / ADR-011).
 *
 * <p>This row never changes once created: the text, answers and metadata all live in
 * {@link QuestionVersion}, and editing a question inserts version n+1 rather than
 * touching version n. Past exams, attempts and grades therefore keep pointing at the
 * exact wording that was answered.
 *
 * <p><b>Deletion is soft (F2.5) and the database enforces it</b> — every foreign key
 * into the version history is {@code RESTRICT}, so a hard delete cannot succeed even
 * for a question no exam references. {@link #deletedAt} is the only way out, and the
 * serial and display id stay taken so an id is never recycled onto a different
 * question. That in turn means the E2.14 allocator must take {@code MAX(serial3) + 1}
 * and never a count.
 */
@Entity
@Table(name = "questions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_questions_display_id", columnNames = "display_id5"),
        @UniqueConstraint(name = "uq_questions_course_serial", columnNames = {"course", "serial3"})
})
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "course", columnDefinition = "char(2)", nullable = false)
    private String courseCode;

    @Column(name = "serial3", nullable = false)
    private short serial;

    @Column(name = "display_id5", columnDefinition = "char(5)", nullable = false)
    private String displayId;

    @Column(name = "deleted_at", precision = 3)
    private Instant deletedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    /** Required by JPA. */
    protected Question() {
    }

    public Question(String courseCode, short serial, String displayId) {
        this.courseCode = courseCode;
        this.serial = serial;
        this.displayId = displayId;
    }

    public Long getId() {
        return id;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public short getSerial() {
        return serial;
    }

    public String getDisplayId() {
        return displayId;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    /** True once the question has been soft-deleted and should leave every listing. */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public int getLockVersion() {
        return lockVersion;
    }
}
