package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * Which students are enrolled in which course — {@code enrollments} (V1, §5).
 *
 * <p>Enrolment is a gate, not decoration: a student may only start an attempt on a
 * course they are enrolled in (S-18), and may only use that course's study bot
 * (F12.4). Both checks read this table.
 */
@Entity
@Table(name = "enrollments")
public class Enrollment {

    @EmbeddedId
    private Id id;

    /** Required by JPA. */
    protected Enrollment() {
    }

    public Enrollment(String courseCode, long studentId) {
        this.id = new Id(courseCode, studentId);
    }

    public Id getId() {
        return id;
    }

    public String getCourseCode() {
        return id.getCourseCode();
    }

    public long getStudentId() {
        return id.getStudentId();
    }

    /** Composite primary key {@code (course, student)}. */
    @Embeddable
    public static class Id implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "course", columnDefinition = "char(2)", nullable = false)
        private String courseCode;

        @Column(name = "student", nullable = false)
        private long studentId;

        /** Required by JPA. */
        protected Id() {
        }

        public Id(String courseCode, long studentId) {
            this.courseCode = courseCode;
            this.studentId = studentId;
        }

        public String getCourseCode() {
            return courseCode;
        }

        public long getStudentId() {
            return studentId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Id that)) {
                return false;
            }
            return studentId == that.studentId && Objects.equals(courseCode, that.courseCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(courseCode, studentId);
        }

        @Override
        public String toString() {
            return courseCode + "/" + studentId;
        }
    }
}
