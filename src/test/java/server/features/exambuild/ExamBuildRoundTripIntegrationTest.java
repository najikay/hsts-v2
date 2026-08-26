package server.features.exambuild;

import common.dto.approval.ApprovalState;
import common.dto.auth.Role;
import common.dto.authoring.AutoComposeRequest;
import common.dto.authoring.AutoComposeResult;
import common.dto.authoring.ComposedQuestion;
import common.dto.authoring.ExamComposition;
import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.ExamVersionAction;
import common.dto.authoring.ExamVersionRequest;
import common.dto.authoring.ExamVersionSave;
import common.dto.authoring.QuestionPin;
import common.dto.authoring.TopicQuota;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import server.core.CallerContext;
import server.core.SessionManager;
import server.db.RepositoryTestBase;
import server.db.TestDatabase;
import server.db.TestDatabases;
import server.db.entities.Difficulty;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.repos.CourseRepository;
import server.db.repos.ExamBuildRepository;
import server.db.repos.ExamRepository;
import server.features.approval.ApprovalService;
import server.features.locks.DisplayNames;
import server.features.locks.EditLockGuard;
import server.features.locks.EditLockService;
import server.realtime.PushGateway;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exam builder, assembled, against a real MySQL (E7.16).
 *
 * <h2>What this class claims that nothing else does</h2>
 *
 * <p>Every layer of E7 is already proven alone. {@code ExamBuildRepositoryContract} runs the
 * queries on two engines, {@code ExamServiceTest} and {@code ExamHandlersTest} run the rules
 * against mocks, {@code AutoComposerTest} runs the composer over generated shapes, and
 * {@code ExamBuilderSessionTest} runs the screen with no toolkit. <b>The seam between them is
 * proven nowhere</b>, and E7.16 asks for exactly the five journeys that cross it: a manual
 * composition, an automatic one, an infeasible one, a save that does not total 100, and an edit
 * that makes a version.
 *
 * <p>So everything here is a property of the <em>composition</em>. Anything provable against one
 * layer belongs in that layer's test and is deliberately not repeated.
 *
 * <h2>MySQL only, for {@code BankRoundTripIntegrationTest}'s reasons</h2>
 *
 * <p>{@code TestDatabases} generates the H2 schema from the entities, so it reproduces no foreign
 * keys and no CHECK constraints. Three of the five journeys below lean on one:
 * {@code ck_evq_points} behind the points rule, {@code uq_exam_version_questions_question} behind
 * the duplicate rule, and {@code uq_exam_versions_no} behind versioning. An H2 leaf would pass
 * for reasons unrelated to what this class asserts, which is worse than not running.
 *
 * <h2>The rule every assertion here follows</h2>
 *
 * <p><b>Nothing is asserted against the value a write returned.</b> A write answers a composition
 * assembled partly from objects it just built, so asserting on it proves little about the INSERT.
 * Every check is a <em>second call, in a new transaction</em>, through {@code EXAM_VERSION_GET}.
 * The handler opens one {@code Transactions.inTx} per call, which is what makes the re-read see
 * committed state rather than Hibernate's first-level cache.
 *
 * <h2>Why the questions are seeded and the exams are not</h2>
 *
 * <p>The bank is the <em>input</em> here, not the subject, and its own write path is proven end to
 * end by {@code BankRoundTripIntegrationTest}. Driving {@code QUESTION_CREATE} for each fixture
 * row would make this class that one with longer imports. Every exam, by contrast, arrives
 * through its real verb, because the exam stack is the thing under test.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class ExamBuildRoundTripIntegrationTest extends RepositoryTestBase {

    private static final Instant NOW = Instant.parse("2026-08-26T09:00:00Z");

    /** Hebrew throughout: the language this system is written in and demoed in. */
    private static final String TOPIC_ROOTS = "שורשים";
    private static final String TOPIC_FUNCTIONS = "פונקציות";

    private ExamHandlers handlers;

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }

    @BeforeEach
    void buildTheStack() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DisplayNames names = userId -> Optional.of("someone");
        EditLockService locks =
                new EditLockService(new PushGateway(new SessionManager()), names, clock);

        // The real objects in the arrangement HSTSServer.defaultRouter builds, assembled by hand
        // so the test can call one verb at a time and read the answer.
        handlers = new ExamHandlers(factory(),
                new ExamService(new ExamBuildRepository(), new ExamRepository(),
                        new CourseRepository(), new EditLockGuard(locks), clock),
                Mockito.mock(ApprovalService.class));
    }

    // ===================== Fixtures =======================================

    private CallerContext dana() {
        return CallerContext.authenticated(Mockito.mock(ConnectionToClient.class), danaId,
                Role.TEACHER);
    }

    /**
     * One bank question, at one version, in Algebra.
     *
     * @param topic      the topic the composer buckets on
     * @param difficulty its grade
     * @return the {@code question_versions} row id, which is what a pin carries
     */
    private long bankQuestion(String topic, Difficulty difficulty) {
        return inTx(session -> {
            short serial = (short) (countQuestions(session) + 1);
            Question question = new Question(COURSE_ALGEBRA, serial,
                    COURSE_ALGEBRA + String.format("%03d", serial));
            session.persist(question);
            session.flush();

            QuestionVersion version = new QuestionVersion(question.getId(), 1,
                    "מהו השורש הריבועי של שמונים ואחת?", "תשע", "שמונה", "שבע", "שש",
                    (byte) 1, topic, difficulty, null, danaId, NOW);
            session.persist(version);
            session.flush();
            return version.getId();
        });
    }

    private long countQuestions(Session session) {
        return session.createQuery("select count(q) from Question q", Long.class).uniqueResult();
    }

    private Message send(Verb verb, Object payload) {
        Message request = Message.request(verb, payload);
        return switch (verb) {
            case EXAM_CREATE -> handlers.create(dana(), request);
            case EXAM_VERSION_GET -> handlers.get(dana(), request);
            case EXAM_VERSION_SAVE -> handlers.save(dana(), request);
            case EXAM_VERSION_REVISE -> handlers.revise(dana(), request);
            case EXAM_AUTO_COMPOSE -> handlers.autoCompose(dana(), request);
            default -> throw new IllegalArgumentException("not routed here: " + verb);
        };
    }

    /** Re-reads a version in a new transaction, which is the only thing this class trusts. */
    private ExamComposition reread(long examVersionId) {
        Message answer = send(Verb.EXAM_VERSION_GET, new ExamVersionRequest(examVersionId));
        assertThat(answer.isError()).as("re-read of %s failed: %s", examVersionId,
                answer.errorMessage()).isFalse();
        return (ExamComposition) answer.getPayload();
    }

    private static ExamCreateRequest exam(String name, List<QuestionPin> pins) {
        return new ExamCreateRequest(COURSE_ALGEBRA, name, 90, "בהצלחה", "הערות למורה", pins);
    }

    // ===================== 1. The manual journey ==========================

    @Nested
    @DisplayName("composing by hand (E7.12, T-3.1 to T-3.3)")
    class Manual {

        @Test
        @DisplayName("⚑ a hand-picked paper reaches the database and reads back identically")
        void aManualCompositionRoundTrips() {
            long first = bankQuestion(TOPIC_ROOTS, Difficulty.EASY);
            long second = bankQuestion(TOPIC_FUNCTIONS, Difficulty.HARD);

            Message created = send(Verb.EXAM_CREATE, exam("מבחן אלגברה",
                    List.of(new QuestionPin(first, 60), new QuestionPin(second, 40))));
            assertThat(created.isError()).isFalse();

            ExamComposition stored = reread(((ExamComposition) created.getPayload())
                    .examVersionId());

            assertThat(stored.state()).isEqualTo(ApprovalState.DRAFT);
            assertThat(stored.name())
                    .as("Hebrew survives the round trip, which is what utf8mb4 is for")
                    .isEqualTo("מבחן אלגברה");
            assertThat(stored.questions())
                    .extracting(ComposedQuestion::questionVersionId, ComposedQuestion::points,
                            ComposedQuestion::ord)
                    .as("both pins, their points, and the order she put them in")
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(first, 60, 1),
                            org.assertj.core.groups.Tuple.tuple(second, 40, 2));
            assertThat(stored.questions())
                    .allSatisfy(question -> assertThat(question.latestVersionId())
                            .as("nothing has moved in the bank, so §4-A1's id is the pin itself")
                            .isEqualTo(question.questionVersionId()));
        }

        /**
         * T-3.9 and §5.2 against the constraint that backs it ⚑.
         *
         * <p>The client refuses this on the click and the service refuses it before the insert.
         * What only this class shows is that the third line of defence is real: the request goes
         * through the assembled stack and is refused with a sentence, not with a constraint
         * violation surfacing as {@code INTERNAL}.
         */
        @Test
        @DisplayName("⚑ one question through two of its versions is refused, in words")
        void aDuplicateThroughVersionsIsRefused() {
            long versionOne = bankQuestion(TOPIC_ROOTS, Difficulty.EASY);
            long versionTwo = inTx(session -> {
                long questionId = session.createQuery(
                                "select qv.questionId from QuestionVersion qv where qv.id = :id",
                                Long.class)
                        .setParameter("id", versionOne).uniqueResult();
                QuestionVersion second = new QuestionVersion(questionId, 2, "נוסח מתוקן",
                        "תשע", "שמונה", "שבע", "שש", (byte) 1, TOPIC_ROOTS,
                        Difficulty.EASY, null, danaId, NOW);
                session.persist(second);
                session.flush();
                return second.getId();
            });

            Message answer = send(Verb.EXAM_CREATE, exam("כפילות",
                    List.of(new QuestionPin(versionOne, 50), new QuestionPin(versionTwo, 50))));

            assertThat(answer.isError()).isTrue();
            assertThat(answer.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(answer.errorMessage())
                    .as("uq_exam_version_questions_question would refuse it too, but its message "
                            + "is not a sentence a teacher can act on")
                    .isNotBlank()
                    .doesNotContain("Duplicate entry");
        }
    }

    // ===================== 2. The points rule =============================

    @Nested
    @DisplayName("the points rule against ck_evq_points (E7.3, S-11)")
    class Points {

        @Test
        @DisplayName("⚑ a paper that does not total 100 is refused, and nothing is written")
        void aPaperThatDoesNotTotalIsRefused() {
            long first = bankQuestion(TOPIC_ROOTS, Difficulty.EASY);
            long second = bankQuestion(TOPIC_FUNCTIONS, Difficulty.MEDIUM);

            Message answer = send(Verb.EXAM_CREATE, exam("תשעים ותשע",
                    List.of(new QuestionPin(first, 60), new QuestionPin(second, 39))));

            assertThat(answer.isError()).isTrue();
            assertThat(answer.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(answer.errorMessage())
                    .as("§5.1: the sentence names the shortfall in both directions, so T-3.2's "
                            + "indicator has something true to echo")
                    .isNotBlank();

            // The half-written exam is the failure §5.6's one-transaction rule exists to prevent:
            // an exams row with no version, or a version whose questions never landed, is a row
            // that violates the sum-to-100 invariant while looking valid.
            long exams = inTx(session -> session.createQuery(
                    "select count(e) from Exam e", Long.class).uniqueResult());
            assertThat(exams)
                    .as("a refused create leaves no exam behind at all")
                    .isZero();
        }
    }

    // ===================== 3 and 4. The automatic journeys ================

    @Nested
    @DisplayName("composing automatically (E7.4/E7.13, T-3.4 to T-3.6)")
    class Automatic {

        @Test
        @DisplayName("⚑ a feasible proposal is savable in one click, unedited")
        void aProposalIsSavableAsItStands() {
            bankQuestion(TOPIC_ROOTS, Difficulty.EASY);
            bankQuestion(TOPIC_ROOTS, Difficulty.MEDIUM);
            bankQuestion(TOPIC_FUNCTIONS, Difficulty.HARD);

            Message composed = send(Verb.EXAM_AUTO_COMPOSE, new AutoComposeRequest(
                    COURSE_ALGEBRA, List.of(new TopicQuota(null, 0, 0, 0, 2)), 42L));
            assertThat(composed.isError()).isFalse();

            AutoComposeResult proposal = (AutoComposeResult) composed.getPayload();
            assertThat(proposal.feasible()).isTrue();
            assertThat(proposal.questions()).hasSize(2);

            // §7.4's even split means the proposal already satisfies §5.1, so the whole journey
            // from criteria to a stored draft is two verbs and no arithmetic on the client.
            List<QuestionPin> pins = proposal.questions().stream()
                    .map(question -> new QuestionPin(question.questionVersionId(),
                            question.points()))
                    .toList();

            Message created = send(Verb.EXAM_CREATE, exam("הרכבה אוטומטית", pins));
            assertThat(created.isError())
                    .as("T-3.4: the proposal is savable as it stands, which is the claim")
                    .isFalse();

            ExamComposition stored = reread(((ExamComposition) created.getPayload())
                    .examVersionId());
            assertThat(stored.questions()).hasSize(2);
            assertThat(stored.questions().stream().mapToInt(ComposedQuestion::points).sum())
                    .isEqualTo(ExamCreateRequest.POINTS_TOTAL);
        }

        /**
         * T-3.5 and T-3.6: refused, with a report, and no exam ⚑.
         *
         * <p>F3.3's headline is "no exam is created". The report is checked against the bank this
         * transaction can actually see, which is what §7.2 property 2 promises the teacher when it
         * invites her to go and count for herself.
         */
        @Test
        @DisplayName("⚑ an infeasible request creates nothing and names what is missing")
        void anInfeasibleRequestCreatesNothing() {
            bankQuestion(TOPIC_ROOTS, Difficulty.EASY);
            bankQuestion(TOPIC_ROOTS, Difficulty.MEDIUM);

            Message composed = send(Verb.EXAM_AUTO_COMPOSE, new AutoComposeRequest(
                    COURSE_ALGEBRA, List.of(new TopicQuota(TOPIC_ROOTS, 0, 0, 1, 0)), 42L));
            assertThat(composed.isError())
                    .as("infeasible is an ANSWER, not an error: the report is the useful outcome")
                    .isFalse();

            AutoComposeResult report = (AutoComposeResult) composed.getPayload();
            assertThat(report.feasible()).isFalse();
            assertThat(report.questions()).isEmpty();
            assertThat(report.shortfalls()).singleElement().satisfies(shortfall -> {
                assertThat(shortfall.topic()).isEqualTo(TOPIC_ROOTS);
                assertThat(shortfall.difficulty()).isEqualTo(common.dto.bank.Difficulty.HARD);
                assertThat(shortfall.requested()).isEqualTo(1);
                assertThat(shortfall.available())
                        .as("the raw count in her own bank: two roots questions, neither Hard")
                        .isZero();
            });

            long exams = inTx(session -> session.createQuery(
                    "select count(e) from Exam e", Long.class).uniqueResult());
            assertThat(exams).as("F3.3: no exam is created").isZero();
        }
    }

    // ===================== 5. Versioning ==================================

    @Nested
    @DisplayName("editing makes a version and keeps the old one (E7.5, C-2, T-3.7)")
    class Versioning {

        @Test
        @DisplayName("⚑ a revise writes version 2 and leaves version 1 queryable and unchanged")
        void reviseKeepsThePredecessor() {
            long first = bankQuestion(TOPIC_ROOTS, Difficulty.EASY);
            long second = bankQuestion(TOPIC_FUNCTIONS, Difficulty.MEDIUM);

            Message created = send(Verb.EXAM_CREATE, exam("גרסה ראשונה",
                    List.of(new QuestionPin(first, 50), new QuestionPin(second, 50))));
            ExamComposition versionOne = (ExamComposition) created.getPayload();

            // A draft is revised by saving it; §5.4 makes a NEW version only from a finished one,
            // so the fixture takes version 1 out of DRAFT the way the database sees it.
            runInTx(session -> session.createMutationQuery(
                            "update ExamVersion v set v.status = :status where v.id = :id")
                    .setParameter("status", server.db.entities.ExamVersionStatus.APPROVED)
                    .setParameter("id", versionOne.examVersionId())
                    .executeUpdate());

            Message revised = send(Verb.EXAM_VERSION_REVISE,
                    new ExamVersionAction(versionOne.examVersionId(), versionOne.lockVersion()));
            assertThat(revised.isError()).as(revised.errorMessage()).isFalse();

            ExamComposition versionTwo = (ExamComposition) revised.getPayload();
            assertThat(versionTwo.versionNo())
                    .as("uq_exam_versions_no allocates it; nothing predicts it in advance")
                    .isEqualTo(2);
            assertThat(versionTwo.state()).isEqualTo(ApprovalState.DRAFT);

            // The new draft must actually CARRY the paper forward, and this assertion was missing
            // until a plant found the hole: writing the carried composition onto the predecessor
            // instead of the new version left version 1 looking untouched (it already held those
            // rows) and version 2 empty, and every other assertion here still passed. Re-read,
            // because the value revise returned was assembled in the same transaction.
            ExamComposition carried = reread(versionTwo.examVersionId());
            assertThat(carried.questions())
                    .extracting(ComposedQuestion::questionVersionId, ComposedQuestion::points)
                    .as("F3.5: she revises to edit the paper she had, not to start from nothing")
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(first, 50),
                            org.assertj.core.groups.Tuple.tuple(second, 50));

            ExamComposition original = reread(versionOne.examVersionId());
            assertThat(original.versionNo()).isEqualTo(1);
            assertThat(original.state())
                    .as("C-2 / ADR-011: old versions stay queryable and stay as they were")
                    .isEqualTo(ApprovalState.APPROVED);
            assertThat(original.questions())
                    .extracting(ComposedQuestion::questionVersionId)
                    .containsExactly(first, second);
        }

        /**
         * The other half of C-2: editing the new draft must not touch the old version ⚑.
         *
         * <p>An exam version is immutable once it is finished. A save that reached back into
         * version 1's rows would be invisible on the builder, which only ever shows one version,
         * and would silently rewrite an exam somebody has already approved.
         */
        @Test
        @DisplayName("⚑ saving the new draft leaves the finished version's paper untouched")
        void savingTheDraftDoesNotReachBack() {
            long first = bankQuestion(TOPIC_ROOTS, Difficulty.EASY);
            long second = bankQuestion(TOPIC_FUNCTIONS, Difficulty.MEDIUM);
            long third = bankQuestion(TOPIC_FUNCTIONS, Difficulty.HARD);

            ExamComposition versionOne = (ExamComposition) send(Verb.EXAM_CREATE,
                    exam("גרסה ראשונה", List.of(new QuestionPin(first, 50),
                            new QuestionPin(second, 50)))).getPayload();
            runInTx(session -> session.createMutationQuery(
                            "update ExamVersion v set v.status = :status where v.id = :id")
                    .setParameter("status", server.db.entities.ExamVersionStatus.APPROVED)
                    .setParameter("id", versionOne.examVersionId())
                    .executeUpdate());

            ExamComposition draft = (ExamComposition) send(Verb.EXAM_VERSION_REVISE,
                    new ExamVersionAction(versionOne.examVersionId(), versionOne.lockVersion())).getPayload();

            Message saved = send(Verb.EXAM_VERSION_SAVE, new ExamVersionSave(
                    draft.examVersionId(), draft.lockVersion(), "גרסה שנייה", 120,
                    "בהצלחה", "הערות", List.of(new QuestionPin(third, 100))));
            assertThat(saved.isError()).as(saved.errorMessage()).isFalse();

            ExamComposition untouched = reread(versionOne.examVersionId());
            assertThat(untouched.name()).isEqualTo("גרסה ראשונה");
            assertThat(untouched.durationMinutes()).isEqualTo(90);
            assertThat(untouched.questions())
                    .extracting(ComposedQuestion::questionVersionId)
                    .as("version 1's paper is exactly what it was before version 2 was written")
                    .containsExactly(first, second);

            ExamComposition rewritten = reread(draft.examVersionId());
            assertThat(rewritten.questions())
                    .extracting(ComposedQuestion::questionVersionId)
                    .containsExactly(third);
        }
    }
}
