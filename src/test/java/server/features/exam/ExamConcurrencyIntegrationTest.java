package server.features.exam;

import common.dto.auth.Role;
import common.dto.exam.AttemptForm;
import common.dto.exam.AttemptOutcome;
import common.dto.exam.AttemptResumeRequest;
import common.dto.exam.AttemptStartRequest;
import common.dto.exam.AttemptState;
import common.dto.exam.ExamJoinRequest;
import common.dto.exam.SaveAnswerRequest;
import common.dto.exam.SubmitAttemptRequest;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import server.core.CallerContext;
import server.core.SessionManager;
import server.db.RepositoryTestBase;
import server.db.TestDatabase;
import server.db.TestDatabases;
import server.db.entities.AttemptStatus;
import server.db.entities.Difficulty;
import server.db.entities.Exam;
import server.db.entities.ExamExecution;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionQuestion;
import server.db.entities.ExamVersionStatus;
import server.db.entities.ExecutionStatus;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.projections.AttemptRecord;
import server.db.repos.AttemptRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The E10.8 scenarios, against a real MySQL (E10.8 ⚑).
 *
 * <h2>Why these cannot be unit tests</h2>
 *
 * <p>{@code AttemptServiceTest} proves every rule against an in-memory store, exactly and
 * instantly. What it cannot prove is that the rules survive the database: that
 * {@code UNIQUE(execution_id, student_id)} really refuses a second insert when two threads
 * arrive together, that the status-guarded UPDATE really is one atomic statement under
 * InnoDB, and that two students' transactions really do not see each other's rows.
 *
 * <p>H2 is no help here either: its in-memory engine will not reproduce the row locking
 * these depend on ({@code TestDatabases}' javadoc says so). So this suite runs against the
 * migrated MySQL schema and is skipped when there is no server, like every other MySQL
 * leaf.
 *
 * <p>Everything else stays honest: the {@link TestClock} is still injected, so "her answer
 * arrives one second after the bell" is exact rather than a forty-five-minute wait, while
 * the concurrency is genuinely concurrent.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class ExamConcurrencyIntegrationTest extends RepositoryTestBase {

    private static final Instant T0 = Instant.parse("2026-08-20T09:00:00Z");
    private static final int DURATION = 45;
    private static final String CODE = "4B7Q";

    private final AttemptRepository attemptRepo = new AttemptRepository();

    private TestClock clock;
    private ManualScheduler scheduler;
    private SessionManager sessions;
    private RecordingGateway gateway;
    private RecordingNotifier notifier;
    private List<AttemptFinalizedListener.FinalizedAttempt> graded;
    private AttemptService service;

    private long executionId;
    private List<Long> paper;

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }

    @BeforeEach
    void buildServer() {
        clock = new TestClock(T0);
        scheduler = new ManualScheduler();
        sessions = new SessionManager();
        gateway = new RecordingGateway(sessions);
        notifier = new RecordingNotifier();
        graded = java.util.Collections.synchronizedList(new ArrayList<>());

        service = new AttemptService(new JpaExamStore(factory()), clock, scheduler,
                gateway, notifier, graded::add);

        executionId = seedExecution();
        paper = questionVersionIds();
        // Maya and Rina both sit it; Dana is the teacher who released it. Rina is a teacher
        // in the shared fixture, and enrolling her is exactly the "a teacher may be enrolled
        // in another teacher's course" case CourseRepository's javadoc calls out.
        runInTx(session -> session.persist(
                new server.db.entities.Enrollment(COURSE_ALGEBRA, rinaId)));

        for (long studentId : List.of(mayaId, rinaId)) {
            ConnectionToClient socket = Mockito.mock(ConnectionToClient.class);
            sockets.put(studentId, socket);
            sessions.attach(studentId, Role.STUDENT, socket);
        }
    }

    // ===================== Two students in parallel ======================

    @Test
    @DisplayName("two students sitting at once keep completely separate papers ⚑")
    void twoStudentsInParallel() throws Exception {
        long mayas = start(mayaId, "374301851");
        long rinas = start(rinaId, "248190639");

        List<Boolean> saved = inParallel(
                () -> save(mayaId, mayas, paper.get(0), 1).isOk(),
                () -> save(rinaId, rinas, paper.get(0), 4).isOk());

        assertThat(saved).containsExactly(true, true);
        assertThat(mayas).isNotEqualTo(rinas);
        assertThat(answerOf(mayas, paper.get(0))).isEqualTo((byte) 1);
        assertThat(answerOf(rinas, paper.get(0))).isEqualTo((byte) 4);
    }

    @Test
    @DisplayName("one student submitting leaves the other's exam running")
    void oneSubmitDoesNotEndTheOther() {
        long mayas = start(mayaId, "374301851");
        long rinas = start(rinaId, "248190639");

        submit(mayaId, mayas);

        assertThat(statusOf(rinas)).isEqualTo(AttemptStatus.IN_PROGRESS);
        assertThat(save(rinaId, rinas, paper.get(1), 2).isOk()).isTrue();
    }

    // ===================== Double start ==================================

    @Test
    @DisplayName("two simultaneous starts produce one attempt, and neither student errors ⚑")
    void doubleStartIsBlockedByTheConstraint() throws Exception {
        List<Message> responses = inParallel(
                () -> startResponse(mayaId, "374301851"),
                () -> startResponse(mayaId, "374301851"));

        // Both must succeed from the student's side: one of them created the attempt, the
        // other lost to the unique key, rolled back and re-read the winner (F6.7).
        assertThat(responses).allSatisfy(response ->
                assertThat(response.isOk()).as("%s", response.errorMessage()).isTrue());
        long first = ((AttemptForm) responses.get(0).getPayload()).attemptId();
        long second = ((AttemptForm) responses.get(1).getPayload()).attemptId();
        assertThat(first).isEqualTo(second);
        assertThat(countAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("re-entering the code after submitting says 'already handed in' (F6.7)")
    void reEnteringAfterSubmit() {
        long attemptId = start(mayaId, "374301851");
        submit(mayaId, attemptId);

        Message response = service.join(caller(mayaId),
                Message.request(Verb.EXAM_JOIN, new ExamJoinRequest(CODE)));

        assertThat(response.isOk()).isTrue();
        assertThat(((common.dto.exam.ExamHeader) response.getPayload()).attemptState())
                .isEqualTo(AttemptState.SUBMITTED);
    }

    // ===================== Answer after expiry ===========================

    @Test
    @DisplayName("an answer arriving after the bell is rejected and never stored ⚑")
    void answerAfterExpiryIsRejected() {
        long attemptId = start(mayaId, "374301851");
        save(mayaId, attemptId, paper.get(0), 2);
        clock.advance(Duration.ofMinutes(DURATION + 1));

        Message late = save(mayaId, attemptId, paper.get(1), 3);

        assertThat(late.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(answerOf(attemptId, paper.get(1))).isNull();
        assertThat(answerOf(attemptId, paper.get(0)))
                .as("and what she saved in time is untouched")
                .isEqualTo((byte) 2);
        assertThat(statusOf(attemptId))
                .as("the late answer also closed the attempt rather than leaving it open")
                .isEqualTo(AttemptStatus.TIMED_OUT);
    }

    // ===================== Resume after a client kill ====================

    @Test
    @DisplayName("a killed client resumes with its answers and the real remaining time ⚑")
    void resumeAfterClientKill() {
        long attemptId = start(mayaId, "374301851");
        save(mayaId, attemptId, paper.get(0), 1);
        save(mayaId, attemptId, paper.get(2), 4);

        // The client is gone: no session, no socket, nothing in memory. Twelve minutes pass.
        sessions.detach(socketOf(mayaId));
        clock.advance(Duration.ofMinutes(12));

        AttemptForm resumed = (AttemptForm) service.resume(caller(mayaId),
                        Message.request(Verb.ATTEMPT_RESUME, new AttemptResumeRequest(executionId)))
                .getPayload();

        assertThat(resumed.attemptId()).isEqualTo(attemptId);
        assertThat(resumed.state()).isEqualTo(AttemptState.IN_PROGRESS);
        assertThat(resumed.savedAnswers()).hasSize(2);
        assertThat(resumed.timing().remainingMillis())
                .isEqualTo(Duration.ofMinutes(DURATION - 12).toMillis());
    }

    @Test
    @DisplayName("a client that was away when time ran out comes back to the takeover ⚑")
    void resumeIntoTheTakeover() {
        long attemptId = start(mayaId, "374301851");
        save(mayaId, attemptId, paper.get(0), 1);
        // Nobody was running to fire the timer: the process restarted, or the task was lost.
        clock.advance(Duration.ofHours(2));

        AttemptForm resumed = (AttemptForm) service.resume(caller(mayaId),
                        Message.request(Verb.ATTEMPT_RESUME, new AttemptResumeRequest(executionId)))
                .getPayload();

        assertThat(resumed.state()).isEqualTo(AttemptState.TIMED_OUT);
        assertThat(resumed.outcome().answeredCount()).isEqualTo(1);
        assertThat(statusOf(attemptId)).isEqualTo(AttemptStatus.TIMED_OUT);
    }

    // ===================== Force-submit with the client gone =============

    @Test
    @DisplayName("the server force-submits with nobody connected at all (E10.5 ⚑)")
    void forceSubmitWithNoClient() {
        long attemptId = start(mayaId, "374301851");
        save(mayaId, attemptId, paper.get(1), 3);
        sessions.detach(socketOf(mayaId));
        clock.advance(Duration.ofMinutes(DURATION));

        scheduler.runAll();

        AttemptRecord stored = inTx(session -> attemptRepo.findRecordById(session, attemptId))
                .orElseThrow();
        assertThat(stored.status()).isEqualTo(AttemptStatus.TIMED_OUT);
        assertThat(stored.actualMinutes()).isEqualTo(DURATION);
        assertThat(stored.endedAt()).isEqualTo(T0.plus(Duration.ofMinutes(DURATION)));
        assertThat(answerOf(attemptId, paper.get(1)))
                .as("with the answers she had saved")
                .isEqualTo((byte) 3);
        assertThat(graded).hasSize(1);
    }

    @Test
    @DisplayName("the derived counters move without any counter column being written (S-21)")
    void countersAreDerived() {
        long mayas = start(mayaId, "374301851");
        long rinas = start(rinaId, "248190639");
        submit(mayaId, mayas);
        clock.advance(Duration.ofMinutes(DURATION));
        scheduler.runAll();

        var counts = inTx(session -> attemptRepo.countParticipation(session, executionId));

        assertThat(counts.started()).isEqualTo(2);
        assertThat(counts.finished()).isEqualTo(1);
        assertThat(counts.timedOut()).isEqualTo(1);
        assertThat(statusOf(rinas)).isEqualTo(AttemptStatus.TIMED_OUT);
    }

    // ===================== Submit versus expiry ==========================

    @Test
    @DisplayName("the timer wins: her submit answers TIMED_OUT rather than an error ⚑")
    void expiryBeatsSubmit() {
        long attemptId = start(mayaId, "374301851");
        clock.advance(Duration.ofMinutes(DURATION + 1));
        scheduler.runAll();

        Message response = submit(mayaId, attemptId);

        assertThat(response.isOk())
                .as("she pressed submit and did nothing wrong")
                .isTrue();
        assertThat(((AttemptOutcome) response.getPayload()).state()).isEqualTo(AttemptState.TIMED_OUT);
        assertThat(graded).as("and only one of the two writers told the grader").hasSize(1);
    }

    @Test
    @DisplayName("her submit wins: the timer that fires afterwards changes nothing ⚑")
    void submitBeatsExpiry() {
        long attemptId = start(mayaId, "374301851");
        clock.advance(Duration.ofMinutes(30));
        submit(mayaId, attemptId);

        clock.advance(Duration.ofMinutes(20));
        scheduler.runAll();

        AttemptRecord stored = inTx(session -> attemptRepo.findRecordById(session, attemptId))
                .orElseThrow();
        assertThat(stored.status()).isEqualTo(AttemptStatus.SUBMITTED);
        assertThat(stored.actualMinutes()).isEqualTo(30);
        assertThat(graded).hasSize(1);
    }

    @Test
    @DisplayName("both writers arriving together still produce exactly one winner ⚑")
    void submitAndExpiryAtTheSameInstant() throws Exception {
        long attemptId = start(mayaId, "374301851");
        clock.advance(Duration.ofMinutes(DURATION));

        // Genuinely concurrent, against a real InnoDB row: the status-guarded UPDATE is what
        // decides this, and this is the only place that can be proved.
        AtomicInteger successes = new AtomicInteger();
        inParallel(
                () -> {
                    Message response = submit(mayaId, attemptId);
                    if (response.isOk()) {
                        successes.incrementAndGet();
                    }
                    return response.isOk();
                },
                () -> {
                    service.expire(attemptId);
                    return true;
                });

        assertThat(successes.get())
                .as("the student's request always answers, whichever writer won")
                .isEqualTo(1);
        assertThat(statusOf(attemptId)).isIn(AttemptStatus.SUBMITTED, AttemptStatus.TIMED_OUT);
        assertThat(graded)
                .as("exactly one of the two triggered grading")
                .hasSize(1);
    }

    // ===================== Fixture =======================================

    /** Runs both tasks at once, releasing them from the same latch. */
    private <T> List<T> inParallel(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<T> a = pool.submit(() -> {
                go.await();
                return first.call();
            });
            Future<T> b = pool.submit(() -> {
                go.await();
                return second.call();
            });
            go.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * One mock socket per student, created up front in {@link #buildServer()}.
     *
     * <p>Created eagerly rather than on demand because two of these tests really do call
     * {@code caller(...)} from two threads at once, and a lazily-populated map would be
     * measuring the fixture's thread safety rather than the server's.
     */
    private final java.util.Map<Long, ConnectionToClient> sockets = new java.util.concurrent.ConcurrentHashMap<>();

    private ConnectionToClient socketOf(long userId) {
        return sockets.get(userId);
    }

    private CallerContext caller(long userId) {
        return CallerContext.authenticated(socketOf(userId), userId, Role.STUDENT);
    }

    private Message startResponse(long studentId, String nationalId) {
        return service.start(caller(studentId),
                Message.request(Verb.ATTEMPT_START, new AttemptStartRequest(executionId, nationalId)));
    }

    private long start(long studentId, String nationalId) {
        Message response = startResponse(studentId, nationalId);
        assertThat(response.isOk()).as("fixture start: %s", response.errorMessage()).isTrue();
        return ((AttemptForm) response.getPayload()).attemptId();
    }

    private Message save(long studentId, long attemptId, long questionVersionId, int option) {
        return service.saveAnswer(caller(studentId), Message.request(Verb.ANSWER_SAVE,
                new SaveAnswerRequest(attemptId, questionVersionId, option)));
    }

    private Message submit(long studentId, long attemptId) {
        return service.submit(caller(studentId),
                Message.request(Verb.ATTEMPT_SUBMIT, new SubmitAttemptRequest(attemptId)));
    }

    private AttemptStatus statusOf(long attemptId) {
        return inTx(session -> attemptRepo.findRecordById(session, attemptId))
                .orElseThrow()
                .status();
    }

    private Byte answerOf(long attemptId, long questionVersionId) {
        return inTx(session -> session.createQuery("""
                        select a.selected from AttemptAnswer a
                        where a.id.attemptId = :attemptId
                          and a.id.questionVersionId = :questionVersionId
                        """, Byte.class)
                .setParameter("attemptId", attemptId)
                .setParameter("questionVersionId", questionVersionId)
                .uniqueResult());
    }

    private long countAttempts() {
        return inTx(session -> session.createQuery(
                        "select count(a) from ExamAttempt a where a.executionId = :executionId", Long.class)
                .setParameter("executionId", executionId)
                .getSingleResult());
    }

    private List<Long> questionVersionIds() {
        return inTx(session -> session.createQuery(
                "select qv.id from QuestionVersion qv order by qv.id", Long.class).getResultList());
    }

    /** One live Algebra execution with a three-question paper, open now, run by Dana. */
    private long seedExecution() {
        return inTx(session -> {
            Exam exam = new Exam(COURSE_ALGEBRA, (byte) 1, "101101", danaId);
            session.persist(exam);
            session.flush();

            ExamVersion version = new ExamVersion(exam.getId(), 1, "מבחן אמצע", DURATION,
                    "ענו על כל השאלות.", null, ExamVersionStatus.APPROVED, T0);
            session.persist(version);
            session.flush();

            for (int index = 0; index < 3; index++) {
                short serial = (short) (index + 1);
                Question question = new Question(COURSE_ALGEBRA, serial,
                        COURSE_ALGEBRA + String.format("%03d", serial));
                session.persist(question);
                session.flush();

                QuestionVersion qv = new QuestionVersion(question.getId(), 1,
                        "שאלה " + serial, "1, 6", "2, 3", "-2, -3", "0, 5",
                        (byte) 2, "פונקציות", Difficulty.EASY, null, danaId, T0);
                session.persist(qv);
                session.flush();

                session.persist(new ExamVersionQuestion(version.getId(), qv.getId(),
                        question.getId(), index == 2 ? 20 : 40, index + 1));
            }

            ExamExecution execution = new ExamExecution(version.getId(), CODE,
                    T0.minus(Duration.ofMinutes(5)), T0.plus(Duration.ofHours(3)),
                    ExecutionStatus.LIVE, danaId);
            session.persist(execution);
            session.flush();
            return execution.getId();
        });
    }
}
