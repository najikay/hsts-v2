package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A course's study bot — {@code bots} (V6, §5, S-30, F12.1).
 *
 * <p>One per course, enforced by a unique key on the course code: a second teacher of
 * the same course extends the existing bot's sources rather than creating a rival.
 *
 * <p><b>Bots are switched off, not deleted</b> (F12.4), and the schema takes that
 * literally — every foreign key from sessions and messages back to here is
 * {@code RESTRICT}, so deleting a bot that anyone has ever talked to fails rather than
 * taking the analytics corpus with it.
 */
@Entity
@Table(name = "bots", uniqueConstraints =
        @UniqueConstraint(name = "uq_bots_course", columnNames = "course"))
public class Bot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "course", columnDefinition = "char(2)", nullable = false)
    private String courseCode;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Required by JPA. */
    protected Bot() {
    }

    public Bot(String courseCode, String name) {
        this.courseCode = courseCode;
        this.name = name;
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public String getName() {
        return name;
    }

    /** Students may use the bot only while this is true (F12.4, C-4). */
    public boolean isActive() {
        return active;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
