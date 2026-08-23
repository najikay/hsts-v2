package server.features.release;

import common.dto.auth.Role;
import common.dto.release.ReleaseActionRequest;
import common.dto.release.ReleaseCodeIssue;
import common.dto.release.ReleaseCreateRequest;
import common.dto.release.ReleaseList;
import common.dto.release.ReleaseOptions;
import common.dto.release.ReleaseRow;
import common.dto.release.ReleaseState;
import common.dto.release.ReleaseWindow;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import server.core.AuthorizationException;
import server.core.CallerContext;
import server.core.SessionManager;
import server.db.entities.ExamVersionStatus;
import server.db.entities.ExecutionStatus;
import server.features.exam.ExecutionCloseService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReleaseService} — E9.1, E9.3, E9.4 (F5.1, F5.2, F5.4, F5.5).
 *
 * <p>Five rules carry the weight here, and each is one a plausible implementation gets wrong
 * in a way that only shows up in an exam hall:
 *
 * <ol>
 *   <li>an unapproved version cannot be released, and the refusal is the F5.1 sentence ⚑;</li>
 *   <li>a code is unique among releases students could still enter, and free again once one
 *       is over — the partial rule §5 makes a service rule ⚑;</li>
 *   <li>a release that is not hers is indistinguishable from one that does not exist ⚑;</li>
 *   <li>cancelling and closing early are legal from exactly one state each, and each refusal
 *       names the other action;</li>
 *   <li>closing early is delegated whole to {@link ExecutionCloseService}, which is what makes
 *       "behaves exactly like time expiry" true rather than promised.</li>
 * </ol>
 *
 * <p>Everything runs against {@link InMemoryReleaseStore} and a clock the test moves by hand,
 * so each rule is exact and instant. The SQL half is proved separately by
 * {@code JpaReleaseStoreContract} on both engines, and the close-early behaviour end to end
 * by {@code ReleaseCloseIntegrationTest} against real MySQL with real attempts.
 */
class ReleaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final long DANA = 101;
    private static final long AUTHOR = 102;
    private static final long RINA = 103;
    private static final long MAYA = 201;
    private static final long NOAM = 202;
    private static final long VERSION_APPROVED = 7001;
    private static final long VERSION_DRAFT = 7002;
    private static final long VERSION_OTHER_COURSE = 7003;
    private static final String ALGEBRA = "11";
    private static final String JAVA = "21";

    private InMemoryReleaseStore store;
    private ExecutionCloseService closeService;
    private RecordingGateway gateway;
    private SessionManager sessions;
    private Map<Long, ConnectionToClient> sockets;
    private MutableClock clock;
    private ReleaseService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryReleaseStore();
        store.withTeacher(DANA, ALGEBRA);
        store.withTeacher(RINA, JAVA);
        store.enrols(ALGEBRA, MAYA, NOAM);
        // Dana teaches Algebra; the exam is somebody else's, which is the S-35 pair the
        // ownership rule has to admit on both sides.
        store.version(VERSION_APPROVED, 900, ALGEBRA, ExamVersionStatus.APPROVED, AUTHOR);
        store.version(VERSION_DRAFT, 901, ALGEBRA, ExamVersionStatus.DRAFT, AUTHOR);
        store.version(VERSION_OTHER_COURSE, 902, JAVA, ExamVersionStatus.APPROVED, RINA);

        closeService = Mockito.mock(ExecutionCloseService.class);
        sessions = new SessionManager();
        sockets = new HashMap<>();
        gateway = new RecordingGateway(sessions);
        for (long teacherId : new long[]{DANA, AUTHOR, RINA}) {
            ConnectionToClient socket = Mockito.mock(ConnectionToClient.class);
            sockets.put(teacherId, socket);
            sessions.attach(teacherId, Role.TEACHER, socket);
        }
        clock = new MutableClock(NOW);
        service = new ReleaseService(store, closeService, gateway, clock, new Random(42));
    }

    // ===================== The picker ====================================

    @Nested
    @DisplayName("what may be released (F5.1)")
    class Options {

        @Test
        @DisplayName("only approved versions of her own courses are offered ⚑")
        void onlyApprovedOfHerCourses() {
            ReleaseOptions options = payload(service.options(teacher(DANA),
                    Message.request(Verb.RELEASE_OPTIONS_GET, null)), ReleaseOptions.class);

            // The draft is hers to see elsewhere and not hers to release; the Java exam is
            // approved but belongs to a course she does not teach. PRD §6's "release
            // unapproved version -> impossible (not listed)" is this assertion.
            assertThat(options.versions()).extracting(v -> v.examVersionId())
                    .containsExactly(VERSION_APPROVED);
        }

        @Test
        @DisplayName("a teacher with exams but none approved is told which empty state she is in")
        void waitingOnApproval() {
            InMemoryReleaseStore empty = new InMemoryReleaseStore();
            empty.withTeacher(DANA, ALGEBRA);
            empty.version(VERSION_DRAFT, 901, ALGEBRA, ExamVersionStatus.DRAFT, DANA);
            ReleaseService lonely =
                    new ReleaseService(empty, closeService, gateway, clock, new Random(1));

            ReleaseOptions options = payload(lonely.options(teacher(DANA),
                    Message.request(Verb.RELEASE_OPTIONS_GET, null)), ReleaseOptions.class);

            assertThat(options.isEmpty()).isTrue();
            // The two empty states have different next steps: ask your coordinator, or write
            // an exam. A screen that could not tell them apart would send half its readers
            // the wrong way.
            assertThat(options.waitingOnApproval()).isTrue();
        }

        @Test
        @DisplayName("a teacher with no exams at all is in the other empty state")
        void nothingWritten() {
            InMemoryReleaseStore empty = new InMemoryReleaseStore().withTeacher(DANA, ALGEBRA);
            ReleaseService lonely =
                    new ReleaseService(empty, closeService, gateway, clock, new Random(1));

            ReleaseOptions options = payload(lonely.options(teacher(DANA),
                    Message.request(Verb.RELEASE_OPTIONS_GET, null)), ReleaseOptions.class);

            assertThat(options.waitingOnApproval()).isFalse();
        }

        @Test
        @DisplayName("a student may not ask at all")
        void studentsAreRefused() {
            assertThatThrownBy(() -> service.options(student(MAYA),
                    Message.request(Verb.RELEASE_OPTIONS_GET, null)))
                    .isInstanceOf(AuthorizationException.class);
        }
    }

    // ===================== Creating ======================================

    @Nested
    @DisplayName("taking an exam out of the drawer (F5.1, F5.2)")
    class Create {

        @Test
        @DisplayName("an approved version is released, scheduled, with a generated code")
        void happyPath() {
            ReleaseRow row = payload(create(DANA, VERSION_APPROVED,
                    NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2))), ReleaseRow.class);

            assertThat(row.state()).isEqualTo(ReleaseState.SCHEDULED);
            assertThat(row.code()).hasSize(4).matches("[A-Z0-9]{4}");
            assertThat(row.examName()).isNotBlank();
            assertThat(row.counts().started()).isZero();
        }

        @Test
        @DisplayName("an unapproved version is refused with the F5.1 sentence, and nothing is written ⚑")
        void unapprovedIsRefused() {
            int before = store.executionCount();

            Message response = create(DANA, VERSION_DRAFT,
                    NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage()).isEqualTo(ReleaseMessages.VERSION_NOT_APPROVED);
            // The refusal is not merely reported: no row exists to be entered with a code.
            assertThat(store.executionCount()).isEqualTo(before);
        }

        @Test
        @DisplayName("⚑ a version of somebody else's course is indistinguishable from one that does not exist")
        void foreignVersionLooksMissing() {
            Message foreign = create(DANA, VERSION_OTHER_COURSE,
                    NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2)));
            Message missing = create(DANA, 999_999,
                    NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2)));

            assertThat(foreign.getErrorCode()).isEqualTo(missing.getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(foreign.errorMessage()).isEqualTo(missing.errorMessage());
        }

        @Test
        @DisplayName("a close that is not after the open is refused, with the wire's own sentence")
        void closeMustBeAfterOpen() {
            Message response = create(DANA, VERSION_APPROVED,
                    NOW.plus(Duration.ofHours(2)), NOW.plus(Duration.ofHours(1)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            // The same string the create dialog shows inline, because both tiers read it
            // off the same enum.
            assertThat(response.errorMessage())
                    .isEqualTo(ReleaseWindow.CLOSE_NOT_AFTER_OPEN.sentence());
        }

        @Test
        @DisplayName("an opening moment well in the past is refused")
        void openInThePastIsRefused() {
            Message response = create(DANA, VERSION_APPROVED,
                    NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)));

            assertThat(response.errorMessage()).isEqualTo(ReleaseWindow.IN_THE_PAST.sentence());
        }

        @Test
        @DisplayName("but 'now', a minute ago, is accepted: that is how this screen is used")
        void graceCoversTheObviousCase() {
            Message response = create(DANA, VERSION_APPROVED,
                    NOW.minus(Duration.ofMinutes(1)), NOW.plus(Duration.ofHours(1)));

            assertThat(response.isOk()).as("%s", response.errorMessage()).isTrue();
        }

        @Test
        @DisplayName("a malformed payload is a sentence, never an exception")
        void malformedPayload() {
            Message response = service.create(teacher(DANA),
                    Message.request(Verb.RELEASE_CREATE, "not a request"));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage()).isEqualTo(ReleaseMessages.MALFORMED_REQUEST);
        }

        @Test
        @DisplayName("both owners are told about the new release, live")
        void ownersArePushedTo() {
            create(DANA, VERSION_APPROVED, NOW.plus(Duration.ofHours(1)),
                    NOW.plus(Duration.ofHours(2)));

            // Dana released it; AUTHOR wrote the exam. Both may act on it (S-35), so both
            // are told about it.
            assertThat(gateway.recipientsOf(Verb.PUSH_EXECUTION_STATUS))
                    .containsExactlyInAnyOrder(DANA, AUTHOR);
        }
    }

    // ===================== Codes =========================================

    @Nested
    @DisplayName("the 4-character code (C-1, §5)")
    class Codes {

        @Test
        @DisplayName("⚑ a code held by a scheduled release is not handed out again")
        void collisionIsRerolled() {
            // The first roll of Random(42) is deterministic; park it on a live release so
            // the generator has to try again.
            String firstRoll = ExecutionCodes.roll(new Random(42));
            store.execution(firstRoll, ExecutionStatus.LIVE, NOW, NOW.plus(Duration.ofHours(1)),
                    DANA, VERSION_APPROVED);

            ReleaseRow row = payload(create(DANA, VERSION_APPROVED,
                    NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2))), ReleaseRow.class);

            assertThat(row.code()).isNotEqualTo(firstRoll);
        }

        @Test
        @DisplayName("⚑ a code is free again once its release is over (the seed's reuse case)")
        void codeIsReusableAfterClose() {
            String firstRoll = ExecutionCodes.roll(new Random(42));
            // Seed §5: execution 4 reuses exam 1's shape. Uniqueness is partial on purpose,
            // and a closed release must not hold its code hostage for the rest of the year.
            store.execution(firstRoll, ExecutionStatus.CLOSED,
                    NOW.minus(Duration.ofDays(7)), NOW.minus(Duration.ofDays(7)), DANA,
                    VERSION_APPROVED);

            ReleaseRow row = payload(create(DANA, VERSION_APPROVED,
                    NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2))), ReleaseRow.class);

            assertThat(row.code()).isEqualTo(firstRoll);
        }

        @Test
        @DisplayName("a cancelled release does not hold its code either")
        void cancelledDoesNotHoldTheCode() {
            String firstRoll = ExecutionCodes.roll(new Random(42));
            store.execution(firstRoll, ExecutionStatus.CANCELLED, NOW.plus(Duration.ofDays(1)),
                    NOW.plus(Duration.ofDays(1)), DANA, VERSION_APPROVED);

            ReleaseRow row = payload(create(DANA, VERSION_APPROVED,
                    NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2))), ReleaseRow.class);

            assertThat(row.code()).isEqualTo(firstRoll);
        }

        @Test
        @DisplayName("⚑ a code the teacher typed is used as she typed it (F5.3, §4)")
        void suppliedCodeIsHonoured() {
            ReleaseRow row = payload(createWithCode(DANA, VERSION_APPROVED, "4821"),
                    ReleaseRow.class);

            // Acceptance case 5.3's own code. The spec says the teacher defines it, so a
            // well-formed free code is hers and the generator is not consulted at all.
            assertThat(row.code()).isEqualTo("4821");
        }

        @Test
        @DisplayName("a typed code is stored upper case, whatever case she used (C-1)")
        void suppliedCodeIsNormalised() {
            ReleaseRow row = payload(createWithCode(DANA, VERSION_APPROVED, " ab7q "),
                    ReleaseRow.class);

            // Case is not part of a code's identity: students type them, and there is one
            // stored form so the join lookup cannot depend on how the teacher shifted.
            assertThat(row.code()).isEqualTo("AB7Q");
        }

        @Test
        @DisplayName("⚑ a code already in use by a live sitting is refused, by name")
        void suppliedCodeMustBeFree() {
            store.execution("4821", ExecutionStatus.LIVE, NOW, NOW.plus(Duration.ofHours(1)),
                    DANA, VERSION_APPROVED);
            int before = store.executionCount();

            Message response = createWithCode(DANA, VERSION_APPROVED, "4821");

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage()).isEqualTo(ReleaseCodeIssue.TAKEN.sentence());
            // The refusal is not merely reported: no second sitting exists on that code.
            assertThat(store.executionCount()).isEqualTo(before);
        }

        @Test
        @DisplayName("a scheduled sitting holds its code against a typed one too")
        void scheduledAlsoHoldsTheCode() {
            store.execution("4821", ExecutionStatus.SCHEDULED, NOW.plus(Duration.ofDays(1)),
                    NOW.plus(Duration.ofDays(1)).plus(Duration.ofHours(1)), DANA,
                    VERSION_APPROVED);

            Message response = createWithCode(DANA, VERSION_APPROVED, "4821");

            assertThat(response.errorMessage()).isEqualTo(ReleaseCodeIssue.TAKEN.sentence());
        }

        @Test
        @DisplayName("⚑ the uniqueness check ignores case, because the lookup does (C-1)")
        void suppliedCodeClashIsCaseInsensitive() {
            store.execution("AB7Q", ExecutionStatus.LIVE, NOW, NOW.plus(Duration.ofHours(1)),
                    DANA, VERSION_APPROVED);

            // A student typing "ab7q" would reach the live sitting, so "ab7q" is taken.
            Message response = createWithCode(DANA, VERSION_APPROVED, "ab7q");

            assertThat(response.errorMessage()).isEqualTo(ReleaseCodeIssue.TAKEN.sentence());
        }

        @Test
        @DisplayName("a code freed by a closed sitting may be typed again (the seed's reuse case)")
        void suppliedCodeMayReuseAClosedOne() {
            store.execution("4821", ExecutionStatus.CLOSED, NOW.minus(Duration.ofDays(7)),
                    NOW.minus(Duration.ofDays(7)), DANA, VERSION_APPROVED);

            ReleaseRow row = payload(createWithCode(DANA, VERSION_APPROVED, "4821"),
                    ReleaseRow.class);

            assertThat(row.code()).isEqualTo("4821");
        }

        @ParameterizedTest
        @ValueSource(strings = {"12", "ABCDE", "AB C", "AB-1", "אבגד"})
        @DisplayName("⚑ acceptance case 5.3's refusals: too short, too long, not alphanumeric")
        void malformedCodesAreRefused(String typed) {
            int before = store.executionCount();

            Message response = createWithCode(DANA, VERSION_APPROVED, typed);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage()).isEqualTo(ReleaseCodeIssue.MALFORMED.sentence());
            assertThat(store.executionCount()).isEqualTo(before);
        }

        @Test
        @DisplayName("a shape refusal costs no database read: it is a rule about a string")
        void malformedCodeIsRefusedBeforeAnythingIsRead() {
            int transactionsBefore = store.transactions;

            createWithCode(DANA, VERSION_APPROVED, "12");

            assertThat(store.transactions).isEqualTo(transactionsBefore);
        }

        @Test
        @DisplayName("⚑ a blank code means 'you pick one', and the server does")
        void blankCodeIsGenerated() {
            String expected = ExecutionCodes.roll(new Random(42));

            ReleaseRow typedNothing = payload(createWithCode(DANA, VERSION_APPROVED, "   "),
                    ReleaseRow.class);

            // Blank and absent are the same request: the compact constructor collapses them,
            // so there is one representation of "generate one for me".
            assertThat(typedNothing.code()).isEqualTo(expected);
        }

        @Test
        @DisplayName("a generator that can find no free code answers a sentence, not a stack trace")
        void exhaustionIsASentence() {
            // A generator with no variety left: every roll is the same code, and that code
            // is already live. Twenty attempts later it has to give up, and how it gives up
            // is the thing being tested.
            String onlyCode = ExecutionCodes.ALPHABET.substring(0, 1).repeat(ExecutionCodes.LENGTH);
            store.execution(onlyCode, ExecutionStatus.LIVE, NOW, NOW.plus(Duration.ofHours(1)),
                    DANA, VERSION_APPROVED);
            ReleaseService cornered =
                    new ReleaseService(store, closeService, gateway, clock, new FixedRandom());

            Message response = cornered.create(teacher(DANA),
                    Message.request(Verb.RELEASE_CREATE, new ReleaseCreateRequest(
                            VERSION_APPROVED, NOW.plus(Duration.ofHours(1)),
                            NOW.plus(Duration.ofHours(2)))));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(response.errorMessage()).isEqualTo(ReleaseMessages.CODE_EXHAUSTED);
        }
    }

    // ===================== The list ======================================

    @Nested
    @DisplayName("her releases, with live status (F5.4)")
    class Listing {

        @Test
        @DisplayName("she sees the ones she released and the ones of exams she wrote (S-35)")
        void scopedToBothKindsOfOwnership() {
            long hers = store.execution("AAAA", ExecutionStatus.SCHEDULED,
                    NOW.plus(Duration.ofHours(2)), NOW.plus(Duration.ofHours(3)),
                    DANA, VERSION_APPROVED);
            long colleagues = store.execution("BBBB", ExecutionStatus.SCHEDULED,
                    NOW.plus(Duration.ofHours(4)), NOW.plus(Duration.ofHours(5)),
                    RINA, VERSION_OTHER_COURSE);

            ReleaseList list = service.listFor(DANA);

            assertThat(list.rows()).extracting(ReleaseRow::executionId).contains(hers);
            assertThat(list.rows()).extracting(ReleaseRow::executionId)
                    .doesNotContain(colleagues);
        }

        @Test
        @DisplayName("participation is on every row, and a release nobody joined counts zero")
        void participationIsCarried() {
            long busy = store.execution("AAAA", ExecutionStatus.LIVE, NOW.minus(Duration.ofMinutes(10)),
                    NOW.plus(Duration.ofHours(1)), DANA, VERSION_APPROVED);
            store.counts(busy, 12, 4, 1);
            long quiet = store.execution("BBBB", ExecutionStatus.SCHEDULED,
                    NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(1).plusHours(1)),
                    DANA, VERSION_APPROVED);

            ReleaseList list = service.listFor(DANA);

            assertThat(rowOf(list, busy).counts().started()).isEqualTo(12);
            assertThat(rowOf(list, busy).counts().inProgress()).isEqualTo(7);
            assertThat(rowOf(list, quiet).counts().started()).isZero();
        }

        @Test
        @DisplayName("a scheduled release whose moment has passed still reads Scheduled ⚑")
        void neverOptimistic() {
            long due = store.execution("AAAA", ExecutionStatus.SCHEDULED,
                    NOW.minus(Duration.ofMinutes(1)), NOW.plus(Duration.ofHours(1)),
                    DANA, VERSION_APPROVED);

            // Students cannot enter until the column says LIVE, so the screen must not tell
            // a teacher to read the code out to a room that would be refused.
            assertThat(rowOf(service.listFor(DANA), due).state())
                    .isEqualTo(ReleaseState.SCHEDULED);
        }

        @Test
        @DisplayName("a live release whose window has ended reads Closed before the sweep gets to it")
        void liveButOver() {
            long over = store.execution("AAAA", ExecutionStatus.LIVE,
                    NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofMinutes(1)),
                    DANA, VERSION_APPROVED);

            assertThat(rowOf(service.listFor(DANA), over).state()).isEqualTo(ReleaseState.CLOSED);
        }

        @Test
        @DisplayName("an extension keeps it live for exactly the minutes that were added (S-20)")
        void extensionKeepsItLive() {
            long extended = store.execution("AAAA", ExecutionStatus.LIVE,
                    NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofMinutes(1)),
                    DANA, VERSION_APPROVED);
            store.extend(extended, 15);

            assertThat(rowOf(service.listFor(DANA), extended).state())
                    .isEqualTo(ReleaseState.LIVE);
        }
    }

    // ===================== Cancel ========================================

    @Nested
    @DisplayName("calling a release off (F5.5)")
    class Cancel {

        @Test
        @DisplayName("a scheduled release is cancelled and its owners are told")
        void cancelsAScheduledRelease() {
            long executionId = scheduled();

            ReleaseRow row = payload(service.cancel(teacher(DANA),
                    Message.request(Verb.RELEASE_CANCEL, new ReleaseActionRequest(executionId))),
                    ReleaseRow.class);

            assertThat(row.state()).isEqualTo(ReleaseState.CANCELLED);
            assertThat(store.statusOf(executionId)).isEqualTo(ExecutionStatus.CANCELLED);
            assertThat(gateway.recipientsOf(Verb.PUSH_EXECUTION_STATUS))
                    .containsExactlyInAnyOrder(DANA, AUTHOR);
        }

        @Test
        @DisplayName("a live release cannot be cancelled, and the refusal names the other button")
        void aLiveReleaseIsNotCancellable() {
            long executionId = store.execution("AAAA", ExecutionStatus.LIVE, NOW,
                    NOW.plus(Duration.ofHours(1)), DANA, VERSION_APPROVED);

            Message response = service.cancel(teacher(DANA),
                    Message.request(Verb.RELEASE_CANCEL, new ReleaseActionRequest(executionId)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(response.errorMessage()).isEqualTo(ReleaseMessages.CANCEL_NOT_SCHEDULED);
            assertThat(store.statusOf(executionId)).isEqualTo(ExecutionStatus.LIVE);
        }

        @Test
        @DisplayName("a finished release has nothing to cancel, and is told so differently")
        void anOverReleaseIsNotCancellable() {
            long executionId = store.execution("AAAA", ExecutionStatus.CLOSED,
                    NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofHours(23)),
                    DANA, VERSION_APPROVED);

            Message response = service.cancel(teacher(DANA),
                    Message.request(Verb.RELEASE_CANCEL, new ReleaseActionRequest(executionId)));

            assertThat(response.errorMessage()).isEqualTo(ReleaseMessages.CANCEL_ALREADY_OVER);
        }

        @Test
        @DisplayName("⚑ somebody else's release is indistinguishable from one that does not exist")
        void foreignReleaseLooksMissing() {
            long colleagues = store.execution("BBBB", ExecutionStatus.SCHEDULED,
                    NOW.plus(Duration.ofHours(4)), NOW.plus(Duration.ofHours(5)),
                    RINA, VERSION_OTHER_COURSE);

            Message foreign = service.cancel(teacher(DANA),
                    Message.request(Verb.RELEASE_CANCEL, new ReleaseActionRequest(colleagues)));
            Message missing = service.cancel(teacher(DANA),
                    Message.request(Verb.RELEASE_CANCEL, new ReleaseActionRequest(999_999)));

            assertThat(foreign.getErrorCode()).isEqualTo(missing.getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(foreign.errorMessage()).isEqualTo(missing.errorMessage())
                    .isEqualTo(ReleaseMessages.RELEASE_UNKNOWN);
            // And it really is untouched, not merely reported as missing.
            assertThat(store.statusOf(colleagues)).isEqualTo(ExecutionStatus.SCHEDULED);
        }

        @Test
        @DisplayName("the exam's author may cancel a colleague's release of it (S-35)")
        void theAuthorIsAnOwnerToo() {
            long executionId = scheduled();

            Message response = service.cancel(teacher(AUTHOR),
                    Message.request(Verb.RELEASE_CANCEL, new ReleaseActionRequest(executionId)));

            assertThat(response.isOk()).as("%s", response.errorMessage()).isTrue();
        }
    }

    // ===================== Close early ===================================

    @Nested
    @DisplayName("ending a live release now (F5.5)")
    class CloseEarly {

        @Test
        @DisplayName("closing early is the close seam, called once ⚑")
        void delegatesToTheCloseSeam() {
            long executionId = store.execution("AAAA", ExecutionStatus.LIVE,
                    NOW.minus(Duration.ofMinutes(20)), NOW.plus(Duration.ofHours(1)),
                    DANA, VERSION_APPROVED);
            // The seam force-submits the stragglers through the expiry path and sets CLOSED;
            // the double stands in for that so this test stays about the verb's rules.
            Mockito.doAnswer(invocation -> {
                store.markClosed(executionId);
                return java.util.Optional.empty();
            }).when(closeService).close(executionId);

            ReleaseRow row = payload(service.closeEarly(teacher(DANA),
                    Message.request(Verb.RELEASE_CLOSE_EARLY,
                            new ReleaseActionRequest(executionId))), ReleaseRow.class);

            verify(closeService).close(executionId);
            assertThat(row.state()).isEqualTo(ReleaseState.CLOSED);
        }

        @Test
        @DisplayName("a scheduled release cannot be closed early, and the refusal names cancel")
        void notLiveIsRefused() {
            long executionId = scheduled();

            Message response = service.closeEarly(teacher(DANA),
                    Message.request(Verb.RELEASE_CLOSE_EARLY,
                            new ReleaseActionRequest(executionId)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(response.errorMessage()).isEqualTo(ReleaseMessages.CLOSE_NOT_LIVE);
            verify(closeService, never()).close(anyLong());
        }

        @Test
        @DisplayName("⚑ somebody else's live release is not closable, and nothing is force-submitted")
        void foreignReleaseIsNotClosable() {
            long colleagues = store.execution("BBBB", ExecutionStatus.LIVE, NOW,
                    NOW.plus(Duration.ofHours(1)), RINA, VERSION_OTHER_COURSE);

            Message response = service.closeEarly(teacher(DANA),
                    Message.request(Verb.RELEASE_CLOSE_EARLY,
                            new ReleaseActionRequest(colleagues)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(response.errorMessage()).isEqualTo(ReleaseMessages.RELEASE_UNKNOWN);
            // The one that matters: a stranger's request must not hand in a class.
            verify(closeService, never()).close(anyLong());
        }

        @Test
        @DisplayName("a student may not close anything")
        void studentsAreRefused() {
            assertThatThrownBy(() -> service.closeEarly(student(MAYA),
                    Message.request(Verb.RELEASE_CLOSE_EARLY, new ReleaseActionRequest(1))))
                    .isInstanceOf(AuthorizationException.class);
        }
    }

    // ===================== Announcing ====================================

    @Test
    @DisplayName("announcing a release nobody has does not throw at the timer thread")
    void announcingAGhostIsSafe() {
        service.executionChanged(999_999);

        assertThat(gateway.of(Verb.PUSH_EXECUTION_STATUS)).isEmpty();
    }

    @Test
    @DisplayName("announcing rebuilds the row from the database, counts and all")
    void announceRebuildsTheRow() {
        long executionId = store.execution("AAAA", ExecutionStatus.LIVE,
                NOW.minus(Duration.ofMinutes(5)), NOW.plus(Duration.ofHours(1)),
                DANA, VERSION_APPROVED);
        store.counts(executionId, 9, 2, 0);

        service.executionChanged(executionId);

        assertThat(gateway.payloadsOf(Verb.PUSH_EXECUTION_STATUS, ReleaseRow.class))
                .isNotEmpty()
                .allSatisfy(row -> {
                    assertThat(row.executionId()).isEqualTo(executionId);
                    assertThat(row.counts().started()).isEqualTo(9);
                    assertThat(row.state()).isEqualTo(ReleaseState.LIVE);
                });
    }

    // ===================== Fixture =======================================

    private long scheduled() {
        return store.execution("AAAA", ExecutionStatus.SCHEDULED,
                NOW.plus(Duration.ofHours(2)), NOW.plus(Duration.ofHours(3)),
                DANA, VERSION_APPROVED);
    }

    private Message createWithCode(long teacherId, long examVersionId, String code) {
        return service.create(teacher(teacherId), Message.request(Verb.RELEASE_CREATE,
                new ReleaseCreateRequest(examVersionId, NOW.plus(Duration.ofHours(1)),
                        NOW.plus(Duration.ofHours(2)), code)));
    }

    private Message create(long teacherId, long examVersionId, Instant openAt, Instant closeAt) {
        return service.create(teacher(teacherId), Message.request(Verb.RELEASE_CREATE,
                new ReleaseCreateRequest(examVersionId, openAt, closeAt)));
    }

    private static ReleaseRow rowOf(ReleaseList list, long executionId) {
        return list.rows().stream()
                .filter(row -> row.executionId() == executionId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for release " + executionId));
    }

    private static <T> T payload(Message response, Class<T> type) {
        assertThat(response.isOk()).as("%s", response.errorMessage()).isTrue();
        assertThat(response.getPayload()).isInstanceOf(type);
        return type.cast(response.getPayload());
    }

    private CallerContext teacher(long userId) {
        return CallerContext.authenticated(sockets.get(userId), userId, Role.TEACHER);
    }

    private CallerContext student(long userId) {
        return CallerContext.authenticated(Mockito.mock(ConnectionToClient.class),
                userId, Role.STUDENT);
    }

    /** A clock a test moves by hand, so "the window has ended" is exact. */
    private static final class MutableClock extends Clock {

        private final Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A {@link Random} with no variety: every roll is the same code. */
    private static final class FixedRandom extends Random {

        private static final long serialVersionUID = 1L;

        @Override
        public int nextInt(int bound) {
            // Always the first symbol, so every attempt produces the identical code and the
            // "no free code anywhere" branch is actually reachable.
            return 0;
        }
    }
}
