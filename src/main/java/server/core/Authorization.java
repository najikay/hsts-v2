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
    //
    // requireCoordinatorOf is no longer one of them: E8 implemented it against
    // the coordinators table, which is the moment this comment promised. The
    // other two are still stubs and still refuse.

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

    // ===================== requireCoordinatorOf: implemented (E8) =========

    /**
     * Answers "does this teacher coordinate this subject?" from the
     * {@code coordinators} table.
     *
     * <p>The seam that lets {@link Authorization} stay a static guard while
     * reaching real data. It is an interface rather than a repository reference
     * for the reason {@code Notifier} is one: a feature's unit tests can hand in
     * a two-line lambda and assert the refusal path without a database, and the
     * production implementation is bound once, where the server is assembled.
     */
    @FunctionalInterface
    public interface SubjectCoordinators {

        /**
         * @param teacherId   the caller's user id
         * @param subjectCode the 2-character subject code
         * @return {@code true} when a {@code coordinators} row binds the two
         */
        boolean coordinates(long teacherId, String subjectCode);

        /**
         * The answer before anybody wires a real one: no.
         *
         * <p>Fail closed, and loudly enough to find. A guard whose data source is
         * missing must refuse rather than wave the caller through, and the
         * message says which of the two possible faults it is — an unwired
         * server, not a permission the caller lacks.
         */
        SubjectCoordinators UNWIRED = (teacherId, subjectCode) -> false;
    }

    /**
     * The process-wide directory the two-argument guard reads.
     *
     * <p>Volatile because it is written once during assembly, on the main thread,
     * and read afterwards from every OCSF read thread. Fail-closed until then.
     */
    private static volatile SubjectCoordinators subjectCoordinators = SubjectCoordinators.UNWIRED;

    /**
     * Binds the directory the two-argument {@link #requireCoordinatorOf} reads.
     *
     * <p>Called once, from {@code HSTSServer}'s assembly, with a lambda over
     * {@code CourseRepository.coordinates}. Returns the previous value so a test
     * that installs a double can put the old one back and not leak into the next
     * one — the reason this returns anything at all.
     *
     * @param directory the lookup, or {@code null} to go back to refusing
     * @return whatever was installed before
     */
    public static SubjectCoordinators useSubjectCoordinators(SubjectCoordinators directory) {
        SubjectCoordinators previous = subjectCoordinators;
        subjectCoordinators = directory == null ? SubjectCoordinators.UNWIRED : directory;
        return previous;
    }

    /**
     * Requires the caller to coordinate {@code subjectCode} (E8, S-1, F4.1).
     *
     * <p>The E2-repositories-have-arrived implementation of what was a fail-closed
     * stub. Two things have to hold and both are checked here: the caller is a
     * {@code COORDINATOR} at all, and there is a {@code coordinators} row binding
     * her to <em>this</em> subject. The first without the second is what would let
     * the Mathematics coordinator approve a Computer Science exam.
     *
     * <p>The role check is deliberately included rather than left to a separate
     * {@code requireRole} at the call site. Coordinator-ness is not a stored role
     * (§5): it <b>is</b> a row in that table, so a caller who has one and whose
     * session says otherwise is a session bug, and a guard that accepted her would
     * be trusting two sources that can disagree.
     *
     * <p>Uses the directory installed by {@link #useSubjectCoordinators}; with
     * none installed it refuses, because a guard that cannot check has not
     * checked. Services that already hold a repository should call
     * {@link #requireCoordinatorOf(CallerContext, String, SubjectCoordinators)}
     * instead and depend on nothing global.
     *
     * @throws AuthorizationException {@code UNAUTHORIZED} when not signed in,
     *                                {@code FORBIDDEN} when this is not her subject
     */
    public static void requireCoordinatorOf(CallerContext caller, String subjectCode) {
        requireCoordinatorOf(caller, subjectCode, subjectCoordinators);
    }

    /**
     * The same guard against an explicitly supplied directory.
     *
     * <p>What every service in E8 actually calls: it already has a transactional
     * repository open, so reading the row through the process-wide seam would mean
     * a second connection answering a question the one in hand could answer, and
     * possibly answering it about a slightly different moment.
     *
     * @param caller      the session's caller
     * @param subjectCode the 2-character subject code
     * @param directory   how to look the binding up; {@code null} refuses
     * @throws AuthorizationException {@code UNAUTHORIZED} when not signed in,
     *                                {@code FORBIDDEN} when this is not her subject
     */
    public static void requireCoordinatorOf(CallerContext caller, String subjectCode,
                                            SubjectCoordinators directory) {
        requireRole(caller, Role.COORDINATOR);
        if (subjectCode == null || subjectCode.isBlank()) {
            // No subject means nothing to be the coordinator of. Refusing beats treating a
            // missing scope as a wildcard, which is the direction this mistake usually goes.
            throw AuthorizationException.forbidden(
                    "That exam is not linked to a subject, so it cannot be approved. "
                            + "Ask the teacher who wrote it to check the course it belongs to.");
        }
        if (directory == null || !directory.coordinates(caller.userId(), subjectCode)) {
            throw AuthorizationException.forbidden(
                    "You do not coordinate subject " + subjectCode
                            + ", so this exam is not yours to approve. "
                            + "Ask that subject's coordinator to look at it.");
        }
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
