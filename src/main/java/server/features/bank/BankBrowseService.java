package server.features.bank;

import common.dto.auth.Role;
import common.dto.bank.BankListRequest;
import common.dto.bank.BankPage;
import common.dto.bank.BankQuestionRow;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionImage;
import common.dto.bank.QuestionVersionDetail;
import common.dto.bank.VersionHistory;
import org.hibernate.Session;
import server.core.Authorization;
import server.core.CallerContext;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.projections.BankQuestionSummary;
import server.db.repos.BankQuery;
import server.db.repos.CourseRepository;
import server.db.repos.QuestionRepository;
import server.db.repos.UserRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The four question bank read verbs (Logic tier, E6.3/E6.5/E6.6).
 *
 * <p>Reads only, and separate from {@link QuestionService} for the reason that class's javadoc
 * gives from the other side: it writes, and its scope guard is the throwing
 * {@code requireTeachesCourse} or its boolean sibling {@code teachesCourse}. Everything here uses
 * {@code reachesCourse}, which is a wider set (contract section 7.3: a coordinator reaches a
 * course she does not teach, and the principal reaches every course in the school). One class
 * holding both guards is how the wrong one eventually gets called.
 *
 * <h2>One reachable set per request, and why it is a lookup rather than a set</h2>
 *
 * <p>Contract section 3 requires the {@code BANK_LIST} filter and the three single-question
 * guards to agree about what a caller reaches, by <b>sharing the query rather than restating the
 * rule</b>. Two expressions of one rule, checked against each other nowhere, is how a browse that
 * hides a question and a guard that admits it end up in the same build.
 *
 * <p>{@link Scope} is that shared answer. It computes the union of taught and coordinated codes
 * at most once per call and <em>is</em> the {@link Authorization.ReachableCourses} the guard
 * receives, so the guard is still handed a lookup rather than an answer. The distinction is not
 * ceremony: a guard given the computed set cannot tell whether the caller computed it correctly,
 * so a handler passing the wrong set would pass the guard.
 *
 * <h2>The principal, who reads everything and writes nothing</h2>
 *
 * <p>F9.3 gives the principal the whole school's bank, read-only. She appears in neither
 * {@code course_teachers} nor {@code coordinators}, so a union over those tables answers
 * "nothing" for the one caller entitled to everything. {@link BankQuery#everyCourse} is her
 * branch for the browse and {@link #reachesEverything} is her branch for the guards, both keyed
 * on role rather than membership.
 *
 * <p><b>The matching hole on the write side is closed elsewhere, deliberately.</b>
 * {@code BankHandlers.asAuthor} admits only TEACHER and COORDINATOR and
 * {@code refusesThePrincipal} is what holds that. Nothing here may be reused to build a write
 * path.
 *
 * <h2>NOT_FOUND, three ways, indistinguishable</h2>
 *
 * <p>The three single-question reads answer {@link Optional#empty()} for a question that does not
 * exist, one that is soft-deleted, and one in a course the caller cannot reach. The handler turns
 * all three into the same {@code NOT_FOUND} carrying the same sentence, because naming which it
 * was tells a caller probing display ids both that a question exists and which course owns it
 * (contract section 6).
 */
public class BankBrowseService {

    private final QuestionRepository questions;
    private final CourseRepository courses;
    private final BankDetails details;

    public BankBrowseService(QuestionRepository questions,
                             CourseRepository courses,
                             UserRepository users) {
        this.questions = Objects.requireNonNull(questions, "questions");
        this.courses = Objects.requireNonNull(courses, "courses");
        this.details = new BankDetails(courses, Objects.requireNonNull(users, "users"));
    }

    // ===================== BANK_LIST (E6.5) ===============================

    /**
     * One page of the bank browse, scoped to what the caller reaches (E6.5, F2.4, T-2.6).
     *
     * <p><b>Scope is not a filter, and the two fail differently.</b> A filter naming a course the
     * caller cannot reach is not a refusal: it intersects with her scope and matches nothing, so
     * the client's course dropdown stays a convenience rather than a boundary (contract section
     * 8). {@link BankQuery} keeps the two in separate fields for exactly that reason.
     *
     * @param session the open session
     * @param caller  the authenticated caller, already role-checked by the handler
     * @param ask     the filters and the page wanted
     * @return the page; empty of rows when she reaches nothing, which is not an error
     */
    public BankPage list(Session session, CallerContext caller, BankListRequest ask) {
        int page = Math.max(ask.page(), 0);
        int size = clampSize(ask.size());

        BankQuery query = queryFor(new Scope(courses, session), caller, ask);
        long totalRows = questions.countBank(session, query);
        List<BankQuestionSummary> found =
                questions.findBankPage(session, query, page * size, size);

        List<BankQuestionRow> rows = new ArrayList<>(found.size());
        for (BankQuestionSummary summary : found) {
            rows.add(rowOf(summary));
        }
        return new BankPage(rows, page, size, totalRows, totalPages(totalRows, size));
    }

    // ===================== QUESTION_GET (E6.3) ============================

    /**
     * One question at its latest version (E6.3, F2.3).
     *
     * @param session   the open session
     * @param caller    the authenticated caller, already role-checked by the handler
     * @param displayId the 5-character display id
     * @return the detail, or empty when unknown, deleted or out of reach
     */
    public Optional<QuestionDetail> get(Session session, CallerContext caller, String displayId) {
        return readable(session, caller, displayId).flatMap(question ->
                questions.findLatestVersionForAuthoring(session, question.getId())
                        .map(latest -> details.detail(
                                session, question, latest, latest.getVersionNo())));
    }

    // ===================== QUESTION_VERSIONS (E6.3) =======================

    /**
     * Every version of one question, newest first (E6.3, T-2.4).
     *
     * <p>Answers empty for a question carrying no versions at all, rather than a history with
     * none in it. A question row always has at least one version by construction, so an empty
     * list here means the identity row is orphaned, and {@code NOT_FOUND} says that more
     * honestly than an empty timeline a teacher would read as "nothing has changed".
     *
     * @param session   the open session
     * @param caller    the authenticated caller, already role-checked by the handler
     * @param displayId the 5-character display id
     * @return the history, or empty when unknown, deleted or out of reach
     */
    public Optional<VersionHistory> versions(Session session, CallerContext caller,
                                             String displayId) {
        return readable(session, caller, displayId).flatMap(question -> {
            List<QuestionVersion> stored =
                    questions.findVersionsForAuthoring(session, question.getId());
            if (stored.isEmpty()) {
                return Optional.empty();
            }
            List<QuestionVersionDetail> rendered = new ArrayList<>(stored.size());
            for (QuestionVersion version : stored) {
                rendered.add(details.versionDetail(session, version));
            }
            return Optional.of(new VersionHistory(question.getDisplayId(), rendered));
        });
    }

    // ===================== QUESTION_IMAGE_GET (E6.6) ======================

    /**
     * The illustration on one version, fetched on demand (E6.6, F2.2).
     *
     * <p>Addressed by version rather than by question because versions are immutable (C-2 /
     * ADR-011): an exam sat last month renders the image its version carried, not whatever the
     * teacher replaced it with since.
     *
     * <p>The content type is sniffed from the bytes with {@link QuestionImages#sniff}, the same
     * magic-number check the write path validates with, rather than stored beside them. One
     * source for the answer, and a byte sequence cannot lie about what it is the way a stored
     * label could.
     *
     * @param session   the open session
     * @param caller    the authenticated caller, already role-checked by the handler
     * @param displayId the 5-character display id
     * @param versionNo which version's image
     * @return the image, or empty when the question is unknown, deleted or out of reach, when
     *         that version does not exist, or when it carries no illustration
     */
    public Optional<QuestionImage> image(Session session, CallerContext caller,
                                         String displayId, int versionNo) {
        return readable(session, caller, displayId)
                .flatMap(question ->
                        questions.findVersionForAuthoring(session, question.getId(), versionNo))
                .filter(QuestionVersion::hasImage)
                .flatMap(version -> QuestionImages.sniff(version.getImage())
                        .map(contentType -> new QuestionImage(
                                strip(displayId), versionNo, contentType, version.getImage())));
    }

    // ===================== scope =========================================

    /**
     * One request's answer to "what does this caller reach", computed at most once.
     *
     * <p><b>A local object rather than a field on the service, and that is the point.</b>
     * Memoizing on the service would be correct only while somebody remembered to construct a new
     * service per request, and a stale memo there would serve one teacher's bank to the next
     * caller. A rule the next person has to remember is not a guarantee; a scope that cannot
     * outlive the call which created it is. The service stays stateless and is assembled once.
     *
     * <p>It <em>is</em> the {@link Authorization.ReachableCourses} handed to the guard, so the
     * guard looks the answer up rather than being given it.
     *
     * <p>It answers from the caller it is <em>handed</em> rather than one captured at
     * construction. An earlier version kept the opener's id and refused anyone else, which read
     * as defensive and was in fact unreachable: a scope built inside one call is only ever handed
     * that call's caller, so the branch could not be covered by any test, and an uncoverable
     * branch is a claim nothing checks.
     *
     * <p><b>Static and package-private rather than a private inner class, and that is for the
     * memo.</b> Every current caller asks it exactly once, so the caching the contract's section 3
     * promises was real code that no test could reach. Widening this enough to construct one
     * directly is what lets {@code asksTheDatabaseOnceHoweverOftenItIsAsked} exist, and that test
     * is the only thing standing between "memoized" and a sentence in a document. Named as
     * production API added for testability, which it is.
     */
    static final class Scope implements Authorization.ReachableCourses {

        private final CourseRepository courses;
        private final Session session;
        private Set<String> memo;

        Scope(CourseRepository courses, Session session) {
            this.courses = courses;
            this.session = session;
        }

        @Override
        public Set<String> forCaller(CallerContext caller) {
            if (caller == null || !caller.isAuthenticated()) {
                return Set.of();
            }
            if (memo == null) {
                // A LinkedHashSet so the order the codes reach BankQuery is stable, which keeps
                // the generated parameter list stable and query plans comparable between runs.
                Set<String> union = new LinkedHashSet<>(
                        courses.findTaughtCourseCodes(session, caller.userId()));
                union.addAll(courses.findCoordinatedCourseCodes(session, caller.userId()));
                memo = Collections.unmodifiableSet(union);
            }
            return memo;
        }
    }

    /**
     * The question, if the caller may read it at all.
     *
     * <p>Collapses "no such question", "soft-deleted" and "out of your reach" into one empty
     * answer on purpose. {@code findActiveByDisplayId} covers the first two, the guard covers the
     * third, and the handler cannot tell them apart because it must not be able to.
     */
    private Optional<Question> readable(Session session, CallerContext caller, String displayId) {
        Optional<Question> found = questions.findActiveByDisplayId(session, strip(displayId));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (reachesEverything(caller)) {
            return found;
        }
        boolean reaches = Authorization.reachesCourse(
                caller, found.get().getCourseCode(), new Scope(courses, session));
        return reaches ? found : Optional.empty();
    }

    /**
     * Scope and filters for one browse.
     *
     * <p>The principal gets her own branch rather than "her reachable set happens to be
     * everything", because {@link BankQuery} deliberately distinguishes reaching nothing from
     * reaching everything. Collapsing those two turns a scoping bug into a data leak instead of
     * an empty screen.
     */
    private BankQuery queryFor(Scope scope, CallerContext caller, BankListRequest ask) {
        server.db.entities.Difficulty difficulty =
                QuestionService.entityDifficulty(ask.difficulty());
        if (reachesEverything(caller)) {
            return BankQuery.everyCourse(
                    strip(ask.courseCode()), strip(ask.topic()), difficulty, ask.search());
        }
        return BankQuery.scopedTo(List.copyOf(scope.forCaller(caller)),
                strip(ask.courseCode()), strip(ask.topic()), difficulty, ask.search());
    }

    /**
     * Whether this caller's scope is the whole school (F9.3).
     *
     * <p>Delegated to {@link Authorization#reachesEveryCourse} rather than decided here. It was a
     * private method of this class until a cold audit pointed out what that meant: the PRINCIPAL
     * row of the contract's §2 table would have been expressed only inside a service, where §2
     * says both scopes live in {@code Authorization} and are "the only place the table is
     * expressed". Two copies of the rule, one of them invisible to every other feature, is how
     * E7's question picker ends up showing her an empty screen.
     */
    private static boolean reachesEverything(CallerContext caller) {
        return Authorization.reachesEveryCourse(caller);
    }

    // ===================== mapping =======================================

    private static BankQuestionRow rowOf(BankQuestionSummary summary) {
        return new BankQuestionRow(
                summary.displayId(),
                summary.courseCode(),
                summary.courseName(),
                summary.text(),
                summary.topic(),
                common.dto.bank.Difficulty.valueOf(summary.difficulty().name()),
                summary.versionNo(),
                summary.hasImage(),
                summary.lastVersionAt());
    }

    /**
     * The page size actually served, whatever was asked for.
     *
     * <p>Clamped rather than trusted: {@code size} arrives from a client, and an unbounded one is
     * a request to load the whole bank into a single message.
     *
     * @param asked the requested size
     * @return the size within {@code MIN_PAGE_SIZE..MAX_PAGE_SIZE}, defaulted when unusable
     */
    private static int clampSize(int asked) {
        if (asked < BankListRequest.MIN_PAGE_SIZE) {
            return BankListRequest.DEFAULT_PAGE_SIZE;
        }
        return Math.min(asked, BankListRequest.MAX_PAGE_SIZE);
    }

    /** Ceiling division, so a pager never offers a last page with nothing on it. */
    private static int totalPages(long totalRows, int size) {
        if (totalRows <= 0) {
            return 0;
        }
        return (int) ((totalRows + size - 1) / size);
    }

    /**
     * {@code strip()}, never {@code trim()}, for the reason {@link QuestionService} gives:
     * {@code trim()} cuts only characters at or below U+0020, so a course code padded with a
     * Unicode space survives it and then fails Java equality against the reachable set.
     *
     * <p><b>It closes most of that gap and not all of it, which is worth knowing before trusting
     * it.</b> {@code String.strip()} removes what {@code Character.isWhitespace()} accepts, and
     * the non-breaking spaces (U+00A0, U+2007, U+202F) are exactly the ones it rejects. So a
     * code padded with U+00A0 arrives here unchanged. That fails <em>closed</em>: the padded
     * value equals no member of the reachable set, so the guard refuses and the filter matches
     * nothing. The dangerous direction would be a value the database matches while the guard
     * does not, and this is its opposite. Pinned by
     * {@code BankBrowseServiceTest.nonBreakingSpacesSurviveStrip} so the limit is a documented
     * fact rather than a surprise.
     */
    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
