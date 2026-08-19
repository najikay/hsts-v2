package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The teacher who coordinates one subject — {@code coordinators} (V1, §5, S-1).
 *
 * <p><b>The primary key is the subject alone</b>, which is how "one coordinator per
 * subject" is enforced rather than merely intended. The reverse is not constrained: a
 * teacher may coordinate several subjects while merely teaching others.
 *
 * <p>This table is also the whole of the {@code COORDINATOR} role. There is no fourth
 * value in {@link UserRole}; the presence of a row here is what turns a stored
 * {@code TEACHER} into a {@code common.dto.auth.Role#COORDINATOR} at login.
 */
@Entity
@Table(name = "coordinators")
public class Coordinator {

    @Id
    @Column(name = "subject_code", columnDefinition = "char(2)", nullable = false)
    private String subjectCode;

    @Column(name = "teacher", nullable = false)
    private long teacherId;

    /** Required by JPA. */
    protected Coordinator() {
    }

    public Coordinator(String subjectCode, long teacherId) {
        this.subjectCode = subjectCode;
        this.teacherId = teacherId;
    }

    public String getSubjectCode() {
        return subjectCode;
    }

    public long getTeacherId() {
        return teacherId;
    }

    public void setTeacherId(long teacherId) {
        this.teacherId = teacherId;
    }
}
