package server.features.exam;

import java.util.Locale;
import java.util.Objects;

/**
 * The two facts the identity gate needs about a user (Logic tier, E10.1 — S-18).
 *
 * <p>Read from {@code users} and compared against what the student typed. It is
 * deliberately a comparison against the <b>caller's own</b> row rather than a lookup by
 * national id: the caller is already identified by the session bound to her socket, so
 * S-18's ת"ז entry confirms that the person at the keyboard is that student, and typing a
 * classmate's number identifies nobody.
 *
 * @param userId    the internal id
 * @param fullName  her display name, which the monitor and the notifications use
 * @param nationalId her stored national id
 */
public record StudentIdentity(long userId, String fullName, String nationalId) {

    public StudentIdentity {
        fullName = fullName == null ? "" : fullName;
        nationalId = nationalId == null ? "" : nationalId;
    }

    /**
     * Whether what was typed is this person's own number.
     *
     * <p>Compared after trimming and case folding. National ids are digits in practice, so
     * the case fold costs nothing and removes a class of "it works on my machine" report
     * from a demo where somebody pastes a value out of a spreadsheet. Whitespace, on the
     * other hand, is a real and constant nuisance and is stripped throughout, not only at
     * the ends: a number typed as three groups is the same number.
     *
     * @param typed what the student entered
     * @return {@code true} when it matches
     */
    public boolean matches(String typed) {
        String candidate = normalise(typed);
        return !candidate.isEmpty() && candidate.equals(normalise(nationalId));
    }

    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    /** @return the name to show, never blank, so a row can always be labelled. */
    public String displayName() {
        return fullName.isBlank() ? "Student " + userId : fullName;
    }

    @Override
    public String toString() {
        // The national id is personal data and this record travels through log-adjacent
        // code paths; the name and id are enough to follow a request through a log.
        return "StudentIdentity{userId=" + userId + ", name=" + fullName + ", nationalId=***}";
    }

    /** Value equality ignoring nothing — spelled out because {@link #toString} is custom. */
    @Override
    public boolean equals(Object other) {
        return other instanceof StudentIdentity that
                && userId == that.userId
                && Objects.equals(fullName, that.fullName)
                && Objects.equals(nationalId, that.nationalId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, fullName, nationalId);
    }
}
