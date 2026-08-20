package common.dto.auth;

import java.io.Serializable;
import java.util.List;

/**
 * The {@link common.protocol.Verb#LOGIN} success payload — everything the shell
 * needs to boot the right role's home screen (Common tier, E5.2).
 *
 * <p>No password hash, no permissions matrix: the client renders menus from the
 * role, and the server re-checks every single request anyway.
 *
 * <p>The unread notification count rides along (E17.5) rather than costing a
 * second round trip: the bell badge is part of the very first frame the user
 * sees, and a shell that painted a count of zero and corrected itself a moment
 * later would read as a bug. It is a snapshot at sign-in; every change after
 * that arrives as a push (F11.1), never as a poll (NFR-18).
 *
 * @param userId      internal user id, echoed back by nothing — the server always
 *                    resolves the caller from the connection, never from a payload
 * @param username    login name
 * @param displayName full name for the avatar/greeting
 * @param role        the single role driving the shell layout (T-1)
 * @param courses     courses taught or enrolled in; never {@code null}, defensively copied
 * @param unreadNotifications unread notifications waiting for this user, for the
 *                    navbar bell badge; never negative
 */
public record LoginResult(long userId,
                          String username,
                          String displayName,
                          Role role,
                          List<CourseRef> courses,
                          int unreadNotifications) implements Serializable {

    private static final long serialVersionUID = 1L;

    public LoginResult {
        // List.copyOf yields an immutable, Serializable list — safe to put on the wire
        // and impossible for a screen to mutate behind the session's back.
        courses = courses == null ? List.of() : List.copyOf(courses);
        // A negative count could only come from a bug or a hostile peer; the shell
        // must not have to defend against it before it can render a badge.
        unreadNotifications = Math.max(0, unreadNotifications);
    }

    /**
     * The pre-E17 shape, kept so every existing caller and test compiles
     * unchanged and any code path that has no count to report says so explicitly
     * rather than guessing one.
     */
    public LoginResult(long userId, String username, String displayName, Role role, List<CourseRef> courses) {
        this(userId, username, displayName, role, courses, 0);
    }

    /** @return the same result carrying {@code count} unread notifications (E17.5). */
    public LoginResult withUnreadNotifications(int count) {
        return new LoginResult(userId, username, displayName, role, courses, count);
    }
}
