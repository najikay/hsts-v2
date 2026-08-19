package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A person who can sign in — {@code users} (V1, §5, S-4 / ADR-005).
 *
 * <p>Seeded only; there is no in-app user management. {@code passwordHash} is BCrypt
 * and never leaves the server: {@code AuthService} maps this to a
 * {@code common.dto.auth.LoginResult}, which has no hash field at all.
 *
 * <p><b>{@code role} is the three-value {@link UserRole}, not the four-value wire
 * role.</b> {@code COORDINATOR} is not stored — it is derived at login from a
 * {@code coordinators} row, in {@code server.db.repos.RepositoryUserDirectory} and
 * nowhere else. See {@link UserRole} for why the schema models it that way.
 *
 * <p>{@code nationalId} is unique: S-18 identifies the student by it when starting an
 * attempt, so two people sharing one would make that lookup ambiguous.
 */
@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uq_users_username", columnNames = "username"),
        @UniqueConstraint(name = "uq_users_national_id", columnNames = "national_id")
})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "username", length = 50, nullable = false)
    private String username;

    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    @Column(name = "full_name", length = 120, nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private UserRole role;

    @Column(name = "national_id", length = 20, nullable = false)
    private String nationalId;

    /** Required by JPA. */
    protected User() {
    }

    public User(String username, String passwordHash, String fullName, UserRole role, String nationalId) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.nationalId = nationalId;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public UserRole getRole() {
        return role;
    }

    public String getNationalId() {
        return nationalId;
    }

    /** Keeps the BCrypt hash out of every log line this entity could land in. */
    @Override
    public String toString() {
        return "User{id=" + id + ", username=" + username + ", role=" + role + ", hash=***}";
    }
}
