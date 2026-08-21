package server.core;

import common.dto.auth.Role;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

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
    // Two of the three have now landed: requireCoordinatorOf with E8, and
    // requireTeachesCourse with E6, each at the moment its epic needed real data.
    // requireEnrolled is still a stub and still refuses; it is E10's, and
    // implementing it here while nothing calls it would be shipping an untested
    // guard for somebody else's epic.

    /** Requires the caller to be enrolled in {@code courseCode} (needs {@code CourseRepo}). */
    public static void requireEnrolled(CallerContext caller, String courseCode) {
        requireAuthenticated(caller);
        throw notImplemented("requireEnrolled", courseCode);
    }

    // ===================== requireTeachesCourse: implemented (E6) ==========

    /**
     * Answers "does this teacher teach this course?" from {@code course_teachers}.
     *
     * <p>The sibling of {@link SubjectCoordinators} one level down the tree, and an interface for
     * the same reason: a feature's unit tests hand in a two-line lambda and assert the refusal
     * path without a database, and the production implementation is bound once where the server
     * is assembled.
     */
    @FunctionalInterface
    public interface CourseTeachers {

        /**
         * @param teacherId  the caller's user id
         * @param courseCode the 2-character course code
         * @return {@code true} when a {@code course_teachers} row binds the two
         */
        boolean teaches(long teacherId, String courseCode);

        /**
         * The answer before anybody wires a real one: no.
         *
         * <p>Fail closed. A guard whose data source is missing must refuse rather than wave the
         * caller through, which is the direction this mistake usually goes.
         */
        CourseTeachers UNWIRED = (teacherId, courseCode) -> false;
    }

    /**
     * The process-wide directory the two-argument guard reads.
     *
     * <p>An {@link AtomicReference} rather than a {@code volatile} field, which is the one place
     * this deliberately does not mirror {@link #useSubjectCoordinators}. A volatile read-then-write
     * is not atomic: two installers race, both see the same previous value, and one write is lost,
     * so "put the old one back" restores something that was never there. {@code getAndSet} is the
     * two-word fix and costs nothing. Publication is equally safe either way.
     */
    private static final AtomicReference<CourseTeachers> COURSE_TEACHERS =
            new AtomicReference<>(CourseTeachers.UNWIRED);

    /**
     * Binds the directory the two-argument {@link #requireTeachesCourse} reads.
     *
     * <p>Meant to be called once, from {@code HSTSServer}'s assembly, with a lambda over
     * {@code CourseRepository.teaches}, exactly as {@link #useSubjectCoordinators} already is at
     * that file's line 215.
     *
     * <p><b>It is not called yet, and saying otherwise would be the defect P-6 is about.</b>
     * {@code HSTSServer} is the lead's file and outside this epic's scope, so the one-line wiring
     * is handed to him with this PR. Until it lands, the two-argument
     * {@link #requireTeachesCourse(CallerContext, String)} refuses everyone, which is the correct
     * behaviour for an unwired guard and is pinned by a test. Services should call the
     * three-argument overload regardless, which depends on nothing global.
     *
     * <p>Returns the previous value so a caller can restore it, which is the reason this returns
     * anything at all.
     *
     * <p><b>Named as production API that exists partly for tests</b>, because it is. The
     * independent justification is the same one {@link #useSubjectCoordinators} has: the binding
     * has to happen somewhere at assembly, and a static guard cannot take a constructor argument.
     *
     * @param directory the lookup, or {@code null} to go back to refusing
     * @return whatever was installed before
     */
    public static CourseTeachers useCourseTeachers(CourseTeachers directory) {
        return COURSE_TEACHERS.getAndSet(directory == null ? CourseTeachers.UNWIRED : directory);
    }

    /**
     * Requires the caller to teach {@code courseCode} (E6, S-5, F2.1).
     *
     * <p>Two things have to hold and both are checked here: the caller is staff at all, and there
     * is a {@code course_teachers} row binding her to <em>this</em> course. The first without the
     * second is what would let one teacher write questions into another's bank.
     *
     * <h2>This guard means exactly what its name says, and that is a decision</h2>
     *
     * <p>It answers "does she teach this course", nothing wider. It is deliberately <b>not</b> the
     * whole of the question bank's authorization rule, because that rule is role-dependent: a
     * teacher reaches courses she teaches (S-5), while a coordinator reaches every course of the
     * subject she coordinates, which is the lead's ruling of 2026-08-21 and is why
     * {@code rina.barak}, who teaches nothing at all, must still see a full bank.
     *
     * <p>So {@code QuestionService} composes: {@code TEACHER} goes through this guard,
     * {@code COORDINATOR} through {@link #requireCoordinatorOf} on the course's subject. The
     * alternative, one wider guard that quietly means "teaches, or coordinates the subject
     * containing", would be a guard whose name lies in the one place a reader cannot afford it.
     * Asking the wide question and filtering afterwards is how the wrong answer eventually gets
     * used.
     *
     * <p>Uses the directory installed by {@link #useCourseTeachers}; with none installed it
     * refuses, because a guard that cannot check has not checked. Services already holding a
     * repository should call
     * {@link #requireTeachesCourse(CallerContext, String, CourseTeachers)} and depend on nothing
     * global.
     *
     * @throws AuthorizationException {@code UNAUTHORIZED} when not signed in, {@code FORBIDDEN}
     *                                when this is not one of her courses
     */
    public static void requireTeachesCourse(CallerContext caller, String courseCode) {
        requireTeachesCourse(caller, courseCode, COURSE_TEACHERS.get());
    }

    /**
     * The same guard against an explicitly supplied directory.
     *
     * <p>What a service actually calls: it already has a transactional repository open, so
     * reading the row through the process-wide seam would mean a second connection answering a
     * question the one in hand could answer, and possibly answering it about a slightly different
     * moment.
     *
     * @param caller     the session's caller
     * @param courseCode the 2-character course code
     * @param directory  how to look the binding up; {@code null} refuses
     * @throws AuthorizationException {@code UNAUTHORIZED} when not signed in, {@code FORBIDDEN}
     *                                when this is not one of her courses
     */
    public static void requireTeachesCourse(CallerContext caller, String courseCode,
                                            CourseTeachers directory) {
        requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (courseCode == null || courseCode.isBlank()) {
            // No course means nothing to teach. Refusing beats treating a missing scope as a
            // wildcard, which is the direction this mistake usually goes.
            throw AuthorizationException.forbidden(
                    "That question is not linked to a course, so it cannot be changed. "
                            + "Pick a course and try again.");
        }
        if (!teachesCourse(caller, courseCode, directory)) {
            throw AuthorizationException.forbidden(
                    "You do not teach course " + courseCode.strip()
                            + ", so its question bank is not yours to change. "
                            + "Ask the coordinator to add you to that course.");
        }
    }

    /**
     * The same question, answered rather than thrown (E6).
     *
     * <h2>Why a boolean sibling exists, and when a service must use it instead</h2>
     *
     * <p>{@link #requireTeachesCourse} refuses with {@code FORBIDDEN} and names the course. That
     * is correct on {@code QUESTION_CREATE}, where the caller supplied the course code herself and
     * learns nothing she did not already know. It is <b>wrong</b> on {@code QUESTION_UPDATE} and
     * {@code QUESTION_DELETE}, where the service resolves the course from the stored question: a
     * caller probing question ids would get {@code FORBIDDEN "You do not teach course 21"} for a
     * question that exists and {@code NOT_FOUND} for one that does not, which distinguishes the
     * two and discloses the course. BANK_WIRE_CONTRACT §2 forbids exactly that:
     * <em>"NOT_FOUND is the only answer for anything the caller cannot reach… FORBIDDEN is for the
     * role check alone and never for scope"</em>, because the alternative is an existence oracle,
     * which is the P-5 shape.
     *
     * <p>So a service on those two paths calls this, and answers {@code NOT_FOUND} itself. The
     * throwing form stays for the create path. Both exist so the correct one is available rather
     * than the convenient one being the only one.
     *
     * <p>Whitespace is stripped before the lookup. The column is {@code CHAR(2)} under a PAD SPACE
     * collation, so {@code "11 "} matches the row in SQL while failing Java equality against the
     * reachable-set list, which is two authorization answers for one input.
     *
     * @param caller     the session's caller, already role-checked by the caller of this method
     * @param courseCode the 2-character course code
     * @param directory  how to look the binding up; {@code null} answers false
     * @return whether a {@code course_teachers} row binds the two
     */
    public static boolean teachesCourse(CallerContext caller, String courseCode,
                                        CourseTeachers directory) {
        if (caller == null || !caller.isAuthenticated()
                || courseCode == null || courseCode.isBlank() || directory == null) {
            return false;
        }
        return directory.teaches(caller.userId(), courseCode.strip());
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
