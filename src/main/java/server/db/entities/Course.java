package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A course within a subject — {@code courses} (V1, §5, S-3).
 *
 * <p>Seeded reference data. {@code subjectCode} is a scalar rather than an association
 * (convention 2): the subject is looked up when it is wanted, and courses are read far
 * more often than their parent subject is needed.
 */
@Entity
@Table(name = "courses")
public class Course {

    @Id
    @Column(name = "code2", columnDefinition = "char(2)", nullable = false)
    private String code;

    @Column(name = "subject_code", columnDefinition = "char(2)", nullable = false)
    private String subjectCode;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    /** Required by JPA. */
    protected Course() {
    }

    public Course(String code, String subjectCode, String name) {
        this.code = code;
        this.subjectCode = subjectCode;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getSubjectCode() {
        return subjectCode;
    }

    public String getName() {
        return name;
    }
}
