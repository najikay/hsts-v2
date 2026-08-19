package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * The identity row of an exam — {@code exams} (V3, §5, C-2, C-6).
 *
 * <p>An Exam is a <em>definition</em> that lives in the drawer. Taking it out to be sat
 * is an {@link ExamExecution}, and one approved version may be taken out many times
 * (S-2). Keeping those two ideas in separate tables is C-6, and it is the modelling
 * mistake v1 made.
 *
 * <p>{@code displayId} is subject(2) + course(2) + serial(2), allocated in E2.14 (S-10).
 */
@Entity
@Table(name = "exams", uniqueConstraints = {
        @UniqueConstraint(name = "uq_exams_display_id", columnNames = "display_id6"),
        @UniqueConstraint(name = "uq_exams_course_serial", columnNames = {"course", "serial2"})
})
public class Exam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "course", columnDefinition = "char(2)", nullable = false)
    private String courseCode;

    @Column(name = "serial2", nullable = false)
    private byte serial;

    @Column(name = "display_id6", columnDefinition = "char(6)", nullable = false)
    private String displayId;

    @Column(name = "author", nullable = false)
    private long authorId;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    /** Required by JPA. */
    protected Exam() {
    }

    public Exam(String courseCode, byte serial, String displayId, long authorId) {
        this.courseCode = courseCode;
        this.serial = serial;
        this.displayId = displayId;
        this.authorId = authorId;
    }

    public Long getId() {
        return id;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public byte getSerial() {
        return serial;
    }

    public String getDisplayId() {
        return displayId;
    }

    public long getAuthorId() {
        return authorId;
    }

    public int getLockVersion() {
        return lockVersion;
    }
}
