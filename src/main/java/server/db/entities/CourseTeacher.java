package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * Which teachers teach which course — {@code course_teachers} (V1, §5).
 *
 * <p>A course may be taught by more than one teacher, and F12.1 leans on that: a second
 * teacher of the same course extends the existing study bot rather than creating a
 * rival one.
 *
 * <p>Pure link table, so the whole row is the key.
 */
@Entity
@Table(name = "course_teachers")
public class CourseTeacher {

    @EmbeddedId
    private Id id;

    /** Required by JPA. */
    protected CourseTeacher() {
    }

    public CourseTeacher(String courseCode, long teacherId) {
        this.id = new Id(courseCode, teacherId);
    }

    public Id getId() {
        return id;
    }

    public String getCourseCode() {
        return id.getCourseCode();
    }

    public long getTeacherId() {
        return id.getTeacherId();
    }

    /** Composite primary key {@code (course, teacher)}. */
    @Embeddable
    public static class Id implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "course", columnDefinition = "char(2)", nullable = false)
        private String courseCode;

        @Column(name = "teacher", nullable = false)
        private long teacherId;

        /** Required by JPA. */
        protected Id() {
        }

        public Id(String courseCode, long teacherId) {
            this.courseCode = courseCode;
            this.teacherId = teacherId;
        }

        public String getCourseCode() {
            return courseCode;
        }

        public long getTeacherId() {
            return teacherId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Id that)) {
                return false;
            }
            return teacherId == that.teacherId && Objects.equals(courseCode, that.courseCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(courseCode, teacherId);
        }

        @Override
        public String toString() {
            return courseCode + "/" + teacherId;
        }
    }
}
