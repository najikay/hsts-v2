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
 * @param userId      internal user id, echoed back by nothing — the server always
 *                    resolves the caller from the connection, never from a payload
 * @param username    login name
 * @param displayName full name for the avatar/greeting
 * @param role        the single role driving the shell layout (T-1)
 * @param courses     courses taught or enrolled in; never {@code null}, defensively copied
 */
public record LoginResult(long userId,
                          String username,
                          String displayName,
                          Role role,
                          List<CourseRef> courses) implements Serializable {

    private static final long serialVersionUID = 1L;

    public LoginResult {
        // List.copyOf yields an immutable, Serializable list — safe to put on the wire
        // and impossible for a screen to mutate behind the session's back.
        courses = courses == null ? List.of() : List.copyOf(courses);
    }
}
