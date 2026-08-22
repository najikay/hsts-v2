package server.core;

import common.dto.auth.Role;

import java.util.Arrays;
import java.util.Set;
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
     * <p>Called once from {@code HSTSServer}'s assembly, with a lambda over
     * {@code CourseRepository.teaches}, beside {@link #useSubjectCoordinators}. <b>Wired
     * 2026-08-21</b>; this javadoc said "not called yet" until it was, because claiming a wiring
     * that does not exist is the defect P-6 is about and the claim had to expire when the fact
     * did.
     *
     * <p><b>The read scope has no equivalent installer and that is deliberate.</b>
     * {@link #reachesCourse} takes its directory per call, so there is nothing process-wide to
     * bind. One existed until 2026-08-22 and was deleted: it was written to, never read, and its
     * javadoc said {@code reachesCourse} read it. Two tests appeared to cover it and asserted only
     * that a constant lambda returns an empty set, so both would have passed with the field and
     * the setter deleted, which is what a cold audit pointed out and what happened next.
     *
     * <p>Services should still call the three-argument overload, which depends on nothing global
     * and can be handed a double without touching process state.
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
     * <p>So {@code QuestionService} composes nothing: <b>this is the write gate and
     * {@link #reachesCourse} is the read scope</b>, and the verb decides which applies (contract
     * §3's table). The alternative, one wider guard quietly meaning "teaches, or coordinates the
     * subject containing", would be a guard whose name lies in the one place a reader cannot
     * afford it. Asking the wide question and filtering afterwards is how the wrong answer
     * eventually gets used.
     *
     * <p>An earlier draft of this javadoc said the service branches on role between this guard and
     * {@link #requireCoordinatorOf}. That was the shape before the lead's two-scope ruling, and it
     * described a composition no handler now performs.
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

    // ===================== bank read scope: implemented (E6) ==============

    /**
     * Every course a caller may <b>read</b> the question bank of (E6, §7.3).
     *
     * <p>The third directory seam, and it answers a different question from the other two on
     * purpose. {@link CourseTeachers} asks "does she teach this one course"; this returns the whole
     * reachable set, because the bank browse filters by the set while the single-question reads
     * check membership in it, and those two must never disagree about what a caller reaches.
     *
     * <h2>Why this one takes the whole caller and its siblings take an id</h2>
     *
     * <p>Because it is the only one of the three whose answer <b>depends on the role</b>.
     * "Does she teach course 11" and "does she coordinate subject 10" are facts about rows, true
     * or false regardless of what the session says she is. "Which courses may she read" is not:
     * a {@code PRINCIPAL} reaches every course (F9.3), and she has no {@code course_teachers} rows
     * and no {@code coordinators} rows, so the union this seam is otherwise built from returns her
     * <b>nothing</b>. An id-only signature makes F9.3 unimplementable without re-deriving the role
     * from the database, which is a second source of truth for a value the session already carries
     * authoritatively, and {@link #requireCoordinatorOf} argues at length against exactly that.
     *
     * <p><b>So the role branch is here, in {@link #reachesCourse}, and not in each
     * implementation.</b> An implementation answers the membership question only: which courses
     * this caller's rows put in front of her. {@code reachesCourse} short-circuits the principal
     * before the directory is consulted, through {@link #reachesEveryCourse}.
     *
     * <p>An earlier version of this sentence said the implementation switched on the role, and the
     * only implementation did not. That is not a documentation slip, it is the shape of the E7 and
     * E9 bug it would have caused: an author writing the obvious union directory, reading this
     * javadoc, believing the principal was handled, and shipping her an empty question picker that
     * fails to a blank screen rather than to an error. The rule now lives in one place and every
     * directory inherits it by not being asked.
     *
     * <p><b>Not {@code CourseRepository.findForUser}.</b> That method merges taught courses with
     * <em>enrolled</em> ones, so under it a student's reachable set would be her enrolments,
     * {@link #reachesCourse} would answer true, and the only thing between her and a
     * {@code QuestionDetail}'s answer key would be a {@code requireRole} call in each handler.
     * The write-side sibling carries the same warning for the same reason.
     */
    @FunctionalInterface
    public interface ReachableCourses {

        /**
         * @param caller the session's caller, whose rows are the answer; the role branch is
         *               {@link #reachesCourse}'s and not this method's
         * @return the course codes her rows put in front of her, never null
         */
        Set<String> forCaller(CallerContext caller);

        /** Fail closed: an absent directory reaches nothing. */
        ReachableCourses UNWIRED = caller -> Set.of();
    }

    /**
     * Whether this caller reaches every course in the school, by role (F9.3).
     *
     * <p><b>The single expression of the principal's read scope.</b> {@link #reachesCourse} calls
     * it before consulting any directory, and the bank browse calls it to choose between an
     * unrestricted query and a scoped one. One predicate rather than two, because the contract's
     * §2 table is only worth anything while the browse and the guard cannot disagree about who
     * sees what, and two copies of "is she the principal" is exactly how they start to.
     *
     * <p>By role rather than by membership, deliberately: she holds no {@code course_teachers} and
     * no {@code coordinators} rows, so every table-derived answer for her is "nothing".
     *
     * @param caller the session's caller
     * @return whether her scope is the whole school
     */
    public static boolean reachesEveryCourse(CallerContext caller) {
        return caller != null
                && caller.isAuthenticated()
                && caller.role().orElse(null) == Role.PRINCIPAL;
    }

    /**
     * Whether the caller may read this course's bank, answered rather than thrown (E6, §7.3).
     *
     * <p><b>The form every bank read actually uses.</b> `QUESTION_GET`, `QUESTION_VERSIONS` and
     * `QUESTION_IMAGE_GET` resolve the course from a <em>stored</em> question, so a `FORBIDDEN`
     * naming that course would tell a caller probing ids both that the question exists and which
     * course it belongs to. The contract forbids exactly that: {@code NOT_FOUND} is the only answer
     * for anything out of reach, indistinguishable from a question that does not exist. So these
     * verbs ask, and answer {@code NOT_FOUND} themselves.
     *
     * <p>Stripped, not trimmed, and the difference is not pedantry: {@code courses.code2} is
     * {@code CHAR(2)} under a PAD SPACE collation, so a code carrying a Unicode space matches the
     * row in SQL while failing Java equality against this set. {@code trim()} cuts only characters
     * at or below U+0020 and would leave that gap open.
     *
     * <p><b>How far strip actually gets, measured rather than assumed.</b> It removes what
     * {@code Character.isWhitespace()} accepts, which excludes the non-breaking spaces U+00A0,
     * U+2007 and U+202F. A code padded with one of those reaches {@code contains} unchanged and
     * is refused, because it equals no member of the set. That is the safe direction and it is
     * the reason this is documented rather than widened: the hazard above is a value the database
     * accepts and this guard does not <em>admit</em>, never one it wrongly admits.
     *
     * <p><b>This answers the membership question and not the role question.</b> It returns true for
     * a student whose reachable set contains the course, because deciding who may call a verb is
     * the handler's job and mixing the two would make this unusable for the one thing it is for.
     * Every caller of this method must have run {@link #requireRole} first. The write-side sibling
     * says the same and it matters more here: a bank read that skipped the role check hands back a
     * {@code QuestionDetail}, which carries the answer key, and no structural guard in this
     * repository can see a missing call.
     *
     * @param caller     the session's caller, <b>already role-checked by the caller of this
     *                   method</b>
     * @param courseCode the 2-character course code
     * @param directory  how to look the reachable set up; {@code null} answers false
     * @return whether the course is in her reachable set
     */
    public static boolean reachesCourse(CallerContext caller, String courseCode,
                                        ReachableCourses directory) {
        if (caller == null || !caller.isAuthenticated()
                || courseCode == null || courseCode.isBlank()) {
            return false;
        }
        // The principal before the directory, never through it: she has no rows in either table
        // the union is built from, so any membership answer for her is "nothing" (F9.3).
        if (reachesEveryCourse(caller)) {
            return true;
        }
        if (directory == null) {
            return false;
        }
        Set<String> reachable = directory.forCaller(caller);
        return reachable != null && reachable.contains(courseCode.strip());
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
