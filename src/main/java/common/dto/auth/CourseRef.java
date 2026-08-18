package common.dto.auth;

import java.io.Serializable;

/**
 * A course the logged-in user is attached to — taught (teacher) or enrolled in
 * (student) — as shown in menus and course pickers (Common tier).
 *
 * <p>Reference data only: no enrolment counts, no exam data, nothing the client
 * has not earned by logging in.
 *
 * @param code the 2-character course code (schema {@code courses.code2})
 * @param name the display name
 */
public record CourseRef(String code, String name) implements Serializable {

    private static final long serialVersionUID = 1L;
}
