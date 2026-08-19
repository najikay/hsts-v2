package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A school subject — {@code subjects} (V1, §5, S-3).
 *
 * <p>No setters: §5 calls this seeded, read-only reference data (S-3), and an edit path
 * that does not exist should not be reachable from Java either.
 *
 * <p>Seeded reference data with no in-app CRUD: the two-character code is the natural
 * primary key, and it is the first half of every 6-digit exam display id (S-10).
 */
@Entity
@Table(name = "subjects")
public class Subject {

    @Id
    @Column(name = "code2", columnDefinition = "char(2)", nullable = false)
    private String code;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    /** Required by JPA. */
    protected Subject() {
    }

    public Subject(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
