package server.core;

import common.dto.auth.Role;

import java.util.Arrays;

/**
 * The permission guards every service calls before touching data (Logic tier,
 * E3.5).
 *
 * <p>Guards read identity from the {@link CallerContext} the router resolved
 * from the connection, so "am I allowed?" is answered from the session, never
 * from the request. Each guard either returns quietly or throws an
 * {@link AuthorizationException} the router maps to a single {@code ERROR}
 * response — services contain no {@code if (role == ...)} branches of their own.
 *
 * <p>Course-scoped guards need the repositories that arrive with E2; they are
 * declared here so the call sites can already be written, and they
 * <b>fail closed</b> — an unimplemented guard refuses rather than waves the
 * caller through.
 */
public final class Authorization {

    private Authorization() {
    }

    /** Requires any authenticated session. */
    public static void requireAuthenticated(CallerContext caller) {
        if (caller == null || !caller.isAuthenticated()) {
            throw AuthorizationException.unauthorized("You must be signed in to do that.");
        }
    }

    /**
     * Requires an authenticated caller holding one of {@code allowed}.
     *
     * @throws AuthorizationException {@code UNAUTHORIZED} when not signed in,
     *                                {@code FORBIDDEN} when the role does not match
     */
    public static void requireRole(CallerContext caller, Role... allowed) {
        requireAuthenticated(caller);
        if (allowed == null || allowed.length == 0) {
            throw AuthorizationException.forbidden("This action is not available.");
        }
        if (!caller.hasAnyRole(allowed)) {
            throw AuthorizationException.forbidden(
                    "This action requires " + describe(allowed) + '.');
        }
    }

    /**
     * Requires the caller to be acting on their own data.
     *
     * @throws AuthorizationException {@code FORBIDDEN} when ids differ
     */
    public static void requireSelf(CallerContext caller, long userId) {
        requireAuthenticated(caller);
        if (caller.userId() != userId) {
            throw AuthorizationException.forbidden("You can only access your own data.");
        }
    }

    // ===================== TODO(E2): course-scoped guards =================
    // Signatures frozen now so E6–E11 call sites compile against the final API;
    // bodies land with the repositories (E2.11). Fail closed until then.

    /** Requires the caller to teach {@code courseCode} (needs {@code CourseRepo}). */
    public static void requireTeachesCourse(CallerContext caller, String courseCode) {
        requireAuthenticated(caller);
        throw notImplemented("requireTeachesCourse", courseCode);
    }

    /** Requires the caller to be enrolled in {@code courseCode} (needs {@code CourseRepo}). */
    public static void requireEnrolled(CallerContext caller, String courseCode) {
        requireAuthenticated(caller);
        throw notImplemented("requireEnrolled", courseCode);
    }

    /** Requires the caller to coordinate {@code subjectCode} (needs {@code CourseRepo}). */
    public static void requireCoordinatorOf(CallerContext caller, String subjectCode) {
        requireAuthenticated(caller);
        throw notImplemented("requireCoordinatorOf", subjectCode);
    }

    private static UnsupportedOperationException notImplemented(String guard, String scope) {
        return new UnsupportedOperationException(
                "TODO(E2): " + guard + "(" + scope + ") needs the course repositories; "
                        + "until then it refuses rather than permits.");
    }

    private static String describe(Role... allowed) {
        return allowed.length == 1
                ? "the " + allowed[0] + " role"
                : "one of the roles " + Arrays.toString(allowed);
    }
}
