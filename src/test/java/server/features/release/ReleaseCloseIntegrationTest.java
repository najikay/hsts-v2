package server.features.release;

import common.dto.auth.Role;
import common.dto.exam.AttemptOutcome;
import common.dto.exam.AttemptState;
import common.dto.release.ReleaseActionRequest;
import common.dto.release.ReleaseRow;
import common.dto.release.ReleaseState;
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
import server.db.entities.Enrollment;
import server.db.entities.Exam;
import server.db.entities.ExamAttempt;
import server.db.entities.ExamExecution;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionStatus;
import server.db.entities.ExecutionStatus;
import server.db.entities.Participation;
import server.features.exam.AttemptFinalizedListener;
import server.features.exam.AttemptService;
import server.features.exam.ExamStore;
import server.features.exam.ExecutionCloseService;
import server.features.exam.JpaExamStore;
import server.features.exam.MonitorService;
import server.features.exam.TimerService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closing a release early, end to end, against real MySQL (E9.3 ⚑ — F5.5).
 *
 * <h2>Why this cannot be a unit test</h2>
 *
 * <p>{@code ReleaseServiceTest} proves that the close-early verb calls the close seam once
 * and only for a release the caller owns. What it deliberately does not prove — because it
 * uses a double for the seam — is the sentence F5.5 actually makes: <b>"behaves exactly like
 * time expiry for active students"</b>. That is a claim about rows, and it is only true if
 * the whole chain is real: the verb, {@code ExecutionCloseService}, {@code AttemptService}'s
 * force-submit, the status-guarded UPDATE underneath it, and the participation frozen
 * afterwards.
 *
 * <p>So this test wires the production objects over the migrated schema and asserts the
 * things a student and a grader would see:
 *
 * <ol>
 *   <li>every attempt that was in progress ends {@code TIMED_OUT} — not {@code SUBMITTED},
 *       because she did not hand it in, and that distinction decides which of F6.4's and
 *       F6.10's screens she gets;</li>
 *   <li>each of them reaches the <b>grading seam</b>, which is the proof that they went
 *       through the expiry path rather than a bespoke UPDATE somewhere in the release
 *       feature: a hand-rolled close would leave papers unmarked for ever;</li>
 *   <li>each student who was online receives {@code PUSH_FORCE_SUBMITTED}, the same push her
 *       own timer would have sent;</li>
 *   <li>an attempt that was already handed in is left exactly as it was;</li>
 *   <li>the execution ends {@code CLOSED} with its participation frozen (S-21), and the row
 *       the teacher gets back carries those numbers;</li>
 *   <li>closing twice changes nothing and errors at nobody.</li>
 * </ol>
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class ReleaseCloseIntegrationTest extends RepositoryTestBase {

    private static final Instant T0 = Instant.parse("2026-08-20T09:00:00Z");
    private static final int DURATION = 45;
    private static final String CODE = "4B7Q";

    private FrozenClock clock;
    private SessionManager sessions;
    private RecordingGateway gateway;
    private Map<Long, ConnectionToClient> sockets;
    private List<AttemptFinalizedListener.FinalizedAttempt> graded;
    private ReleaseService releases;

    private long executionId;

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }

    @BeforeEach
    void buildTheServer() {
        clock = new FrozenClock(T0.plus(Duration.ofMinutes(20)));
        sessions = new SessionManager();
        gateway = new RecordingGateway(sessions);
        sockets = new HashMap<>();
        graded = new ArrayList<>();

        // rina is a teacher in the shared fixture; enrolling her is the "a teacher may be
        // enrolled in a colleague's course" case, and it gives this execution two sitters.
        runInTx(session -> session.persist(new Enrollment(COURSE_ALGEBRA, rinaId)));
        for (long studentId : List.of(mayaId, rinaId)) {
            ConnectionToClient socket = Mockito.mock(ConnectionToClient.class);
            sockets.put(studentId, socket);
            sessions.attach(studentId, Role.STUDENT, socket);
        }
        ConnectionToClient teacherSocket = Mockito.mock(ConnectionToClient.class);
        sockets.put(danaId, teacherSocket);
        sessions.attach(danaId, Role.TEACHER, teacherSocket);

        // The production wiring, in the order HSTSServer assembles it.
        ExamStore examStore = new JpaExamStore(factory());
        AttemptService attempts = new AttemptService(examStore, clock,
                // No timer ever fires: whatever ends these attempts, it is not a countdown.
                (task, delay) -> () -> { },
                gateway, (userIds, type, title, body, ref) -> null, graded::add);
        MonitorService monitors = new MonitorService(examStore, attempts, gateway, clock);
        attempts.publishTo(monitors);
        ExecutionCloseService closeService =
                new ExecutionCloseService(examStore, attempts, monitors);

        releases = new ReleaseService(new JpaReleaseStore(factory()), closeService,
                gateway, clock, new Random(3));

        executionId = seedLiveExecution();
    }

    @Test
    @DisplayName("⚑ closing early hands in everyone still working, exactly as time expiry would")
    void stragglersAreForceSubmitted() {
        long mayas = attempt(mayaId, AttemptStatus.IN_PROGRESS);
        long rinas = attempt(rinaId, AttemptStatus.IN_PROGRESS);

        Message response = closeEarly();

        assertThat(response.isOk()).as("%s", response.errorMessage()).isTrue();
        // TIMED_OUT and not SUBMITTED: she did not hand it in, and that distinction is what
        // decides which of F6.4's and F6.10's screens she sees.
        assertThat(statusOf(mayas)).isEqualTo(AttemptStatus.TIMED_OUT);
        assertThat(statusOf(rinas)).isEqualTo(AttemptStatus.TIMED_OUT);
        assertThat(endedAtOf(mayas)).isNotNull();
        assertThat(minutesOf(mayas))
                .as("solving time is recorded whichever way an attempt ends (S-19)")
                .isNotNull();
    }

    @Test
    @DisplayName("⚑ each force-submitted paper reaches the grading seam, so none is left unmarked")
    void everyClosedAttemptIsHandedToGrading() {
        long mayas = attempt(mayaId, AttemptStatus.IN_PROGRESS);
        long rinas = attempt(rinaId, AttemptStatus.IN_PROGRESS);

        closeEarly();

        // The strongest evidence that this went through the expiry path rather than a
        // bespoke UPDATE inside the release feature: only that path notifies E12.
        assertThat(graded).extracting(AttemptFinalizedListener.FinalizedAttempt::attemptId)
                .containsExactlyInAnyOrder(mayas, rinas);
        assertThat(graded).allSatisfy(finalized ->
                assertThat(finalized.state()).isEqualTo(AttemptState.TIMED_OUT));
    }

    @Test
    @DisplayName("every student who is online gets the same push her own timer would have sent")
    void studentsArePushedTo() {
        attempt(mayaId, AttemptStatus.IN_PROGRESS);
        attempt(rinaId, AttemptStatus.IN_PROGRESS);

        closeEarly();

        assertThat(gateway.recipientsOf(Verb.PUSH_FORCE_SUBMITTED))
                .containsExactlyInAnyOrder(mayaId, rinaId);
        assertThat(gateway.payloadsOf(Verb.PUSH_FORCE_SUBMITTED, AttemptOutcome.class))
                .allSatisfy(outcome ->
                        assertThat(outcome.state()).isEqualTo(AttemptState.TIMED_OUT));
    }

    @Test
    @DisplayName("a student who had already handed in is left exactly as she was")
    void alreadySubmittedIsUntouched() {
        long mayas = attempt(mayaId, AttemptStatus.SUBMITTED);
        attempt(rinaId, AttemptStatus.IN_PROGRESS);

        closeEarly();

        assertThat(statusOf(mayas)).isEqualTo(AttemptStatus.SUBMITTED);
        assertThat(graded).extracting(AttemptFinalizedListener.FinalizedAttempt::attemptId)
                .doesNotContain(mayas);
    }

    @Test
    @DisplayName("the execution ends closed, with its participation frozen into the record (S-21)")
    void participationIsFrozen() {
        attempt(mayaId, AttemptStatus.IN_PROGRESS);
        attempt(rinaId, AttemptStatus.SUBMITTED);

        ReleaseRow row = (ReleaseRow) closeEarly().getPayload();

        ExamExecution stored = inTx(session -> session.find(ExamExecution.class, executionId));
        assertThat(stored.getStatus()).isEqualTo(ExecutionStatus.CLOSED);
        Participation frozen = stored.getParticipation();
        assertThat(frozen).isNotNull();
        assertThat(frozen.started()).isEqualTo(2);
        assertThat(frozen.finished()).isEqualTo(1);
        assertThat(frozen.timedOut()).isEqualTo(1);
        // And the row the teacher's screen is repainted from carries the same three numbers.
        assertThat(row.state()).isEqualTo(ReleaseState.CLOSED);
        assertThat(row.counts().started()).isEqualTo(2);
        assertThat(row.counts().finished()).isEqualTo(1);
        assertThat(row.counts().timedOut()).isEqualTo(1);
    }

    @Test
    @DisplayName("closing a release twice is refused the second time, and changes nothing")
    void closingTwice() {
        long mayas = attempt(mayaId, AttemptStatus.IN_PROGRESS);
        closeEarly();

        Message second = closeEarly();

        // The first close set CLOSED, so the second is not live any more. The refusal names
        // the state rather than pretending it worked, and no paper is touched again.
        assertThat(second.isOk()).isFalse();
        assertThat(second.errorMessage()).isEqualTo(ReleaseMessages.CLOSE_NOT_LIVE);
        assertThat(graded).hasSize(1);
        assertThat(statusOf(mayas)).isEqualTo(AttemptStatus.TIMED_OUT);
    }

    @Test
    @DisplayName("closing a release nobody joined works and freezes three zeros")
    void closingAnEmptyRelease() {
        Message response = closeEarly();

        assertThat(response.isOk()).as("%s", response.errorMessage()).isTrue();
        assertThat(graded).isEmpty();
        ExamExecution stored = inTx(session -> session.find(ExamExecution.class, executionId));
        assertThat(stored.getParticipation().started()).isZero();
    }

    // ===================== Fixture =======================================

    private Message closeEarly() {
        return releases.closeEarly(
                CallerContext.authenticated(sockets.get(danaId), danaId, Role.TEACHER),
                Message.request(Verb.RELEASE_CLOSE_EARLY, new ReleaseActionRequest(executionId)));
    }

    /**
     * One attempt, in the state a student would have left it in.
     *
     * <p>Persisted rather than driven through {@code AttemptService}'s verbs, because those
     * are package-private to the take-exam feature and this test is the release manager's.
     * The rows are identical either way: an in-progress attempt is a row with a start time
     * and no end, which is exactly what {@code ATTEMPT_START} writes. What the test is about
     * is what happens to it <em>next</em>, and that is entirely production code.
     */
    private long attempt(long studentId, AttemptStatus status) {
        return inTx(session -> {
            ExamAttempt attempt = new ExamAttempt(executionId, studentId, T0);
            session.persist(attempt);
            session.flush();
            if (status != AttemptStatus.IN_PROGRESS) {
                session.createMutationQuery("""
                                update ExamAttempt set status = :status, endedAt = :endedAt,
                                    actualMinutes = 18
                                where id = :id and status = :inProgress
                                """)
                        .setParameter("status", status)
                        .setParameter("endedAt", T0.plus(Duration.ofMinutes(18)))
                        .setParameter("id", attempt.getId())
                        .setParameter("inProgress", AttemptStatus.IN_PROGRESS)
                        .executeUpdate();
            }
            return attempt.getId();
        });
    }

    private long seedLiveExecution() {
        return inTx(session -> {
            Exam exam = new Exam(COURSE_ALGEBRA, (byte) 1, "101101", danaId);
            session.persist(exam);
            session.flush();

            ExamVersion version = new ExamVersion(exam.getId(), 1, "מבחן אמצע", DURATION,
                    "ענו על כל השאלות.", null, ExamVersionStatus.APPROVED, T0);
            session.persist(version);
            session.flush();

            ExamExecution execution = new ExamExecution(version.getId(), CODE,
                    T0.minus(Duration.ofMinutes(5)), T0.plus(Duration.ofHours(3)),
                    ExecutionStatus.LIVE, danaId);
            session.persist(execution);
            session.flush();
            return execution.getId();
        });
    }

    private AttemptStatus statusOf(long attemptId) {
        return inTx(session -> session.find(ExamAttempt.class, attemptId).getStatus());
    }

    private Instant endedAtOf(long attemptId) {
        return inTx(session -> session.find(ExamAttempt.class, attemptId).getEndedAt());
    }

    private Integer minutesOf(long attemptId) {
        return inTx(session -> session.find(ExamAttempt.class, attemptId).getActualMinutes());
    }

    /** The server clock, stopped, so "how many minutes did she sit" is exact. */
    private static final class FrozenClock extends Clock {

        private final Instant now;

        private FrozenClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
