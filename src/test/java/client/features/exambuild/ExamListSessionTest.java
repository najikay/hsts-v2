package client.features.exambuild;

import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.events.PushEventBridge;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.approval.ApprovalState;
import common.dto.authoring.ExamComposition;
import common.dto.authoring.ExamList;
import common.dto.authoring.ExamListRow;
import common.dto.authoring.ExamVersionAction;
import common.dto.authoring.ExamVersionRow;
import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExamListSession} — E7.10's behaviour, without a JavaFX toolkit.
 *
 * <p>The session talks to a {@link FakeClientConnection} through a real
 * {@link RequestDispatcher}, and the FX hop is a {@link DirectFxThreadPoster}, so every
 * transition settles synchronously (TEAM_SPLIT section 3.2).
 *
 * <p>The fixture is Dana Cohen's chair: an algebra midterm she has revised twice, whose v2 her
 * coordinator sent back, and a calculus final sitting with him now. That shape is chosen so the
 * three states this screen has actions for are all on screen at once.
 *
 * <h2>The nested classes that matter</h2>
 *
 * <p>{@link Retirement} pins the two behaviours inherited from the screen this one replaces,
 * because contract section 8 proves the DTO fields cross over and says nothing about these.
 * {@link ActionTokens} pins the thing the audit would go looking for: that a button sends the
 * token of the row it was pressed on. {@link LateAnswers} registers no responder and delivers
 * correlated answers by hand, which is the only way to reach the generation counter at all.
 */
class ExamListSessionTest {

    private static final Instant SPRING = Instant.parse("2026-03-10T07:00:00Z");
    private static final Instant SUMMER = Instant.parse("2026-08-07T06:00:00Z");

    private static final long MIDTERM = 900L;
    private static final long FINAL_EXAM = 901L;
    private static final long GEOMETRY = 902L;

    // Version ids, kept distinct from version NUMBERS on purpose: a session that confused the
    // two would still pass every assertion if they were 1, 2, 3 here.
    private static final long MIDTERM_V1 = 9001L;
    private static final long MIDTERM_V2 = 9002L;
    private static final long MIDTERM_V3 = 9003L;
    private static final long FINAL_V1 = 9101L;
    private static final long GEOMETRY_V1 = 9201L;
    private static final long GEOMETRY_V2 = 9202L;
    private static final long GEOMETRY_V3 = 9203L;

    private static final String SENT_BACK = "Question 4 has two correct answers.";

    private FakeClientConnection connection;
    private ExamListSession session;
    private int renders;

    @BeforeEach
    void setUp() {
        connection = new FakeClientConnection();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        // A real bus, so the push tests exercise the registration and not a method call.
        ClientEventBus eventBus =
                new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster());
        dispatcher.setPushListener(new PushEventBridge(eventBus));
        session = new ExamListSession(dispatcher, new DirectFxThreadPoster())
                .onChange(() -> renders++)
                .subscribeTo(eventBus);
    }

    /** One notification of the given type, pointing at the midterm's sent-back version. */
    private static NotificationDto notification(NotificationType type) {
        return new NotificationDto(1L, type, "Your exam was reviewed", "",
                NavRef.to("exams", MIDTERM_V2), SUMMER, null);
    }

    // ===================== Fixture ========================================

    private static ExamVersionRow version(long id, int no, ApprovalState state, String reason,
                                          int questions, int minutes, int lockVersion) {
        return new ExamVersionRow(id, no, state, reason, questions, minutes,
                no == 1 ? SPRING : SUMMER, lockVersion);
    }

    /** v3 draft, v2 sent back, v1 approved. Newest first, which is a contract term. */
    private static ExamListRow midterm() {
        return new ExamListRow(MIDTERM, "110101", "11", "אלגברה", "Algebra midterm", 3,
                List.of(version(MIDTERM_V3, 3, ApprovalState.DRAFT, "", 12, 90, 7),
                        version(MIDTERM_V2, 2, ApprovalState.REJECTED, SENT_BACK, 12, 90, 4),
                        version(MIDTERM_V1, 1, ApprovalState.APPROVED, "", 10, 60, 2)));
    }

    /** One version, waiting on the coordinator. */
    private static ExamListRow finalExam() {
        return new ExamListRow(FINAL_EXAM, "120101", "12", "חדו\"א", "Calculus final", 1,
                List.of(version(FINAL_V1, 1, ApprovalState.PENDING, "", 20, 120, 1)));
    }

    /**
     * An exam with several versions and <b>no open draft</b>, which is what makes it revisable.
     *
     * <p>Added when §5.4 was amended to one open draft per exam. {@link #midterm()} cannot serve
     * the revise cases any more and that is correct rather than inconvenient: it has a DRAFT at
     * v3, so under the amended rule <em>nothing</em> on it may be revised. This exam is the other
     * side of that rule, and it keeps two distinct non-draft versions with distinct lock tokens
     * so {@code reviseCarriesTheOlderToken} still has an older token to get wrong.
     */
    private static ExamListRow geometry() {
        return new ExamListRow(GEOMETRY, "110201", "11", "אלגברה", "Geometry quiz", 3,
                List.of(version(GEOMETRY_V3, 3, ApprovalState.REJECTED, SENT_BACK, 8, 45, 6),
                        version(GEOMETRY_V2, 2, ApprovalState.APPROVED, "", 8, 45, 3),
                        version(GEOMETRY_V1, 1, ApprovalState.APPROVED, "", 6, 30, 2)));
    }

    private void serverHasTheExams() {
        connection.respondTo(Verb.EXAM_LIST, request ->
                Message.ok(request, new ExamList(List.of(midterm(), finalExam(), geometry()))));
    }

    private static ExamComposition composition(long versionId, int versionNo,
                                               ApprovalState state) {
        return new ExamComposition(MIDTERM, "110101", "11", "אלגברה", versionId, versionNo,
                state, "Algebra midterm", 90, null, null, "דנה כהן", SUMMER, "",
                List.of(), 1);
    }

    private ExamVersionAction lastAction(Verb verb) {
        return connection.sentMessages().stream()
                .filter(message -> message.getVerb() == verb)
                .reduce((first, second) -> second)
                .map(message -> (ExamVersionAction) message.getPayload())
                .orElseThrow(() -> new AssertionError("no " + verb + " was sent"));
    }

    private long countSent(Verb verb) {
        return connection.sentMessages().stream()
                .filter(message -> message.getVerb() == verb)
                .count();
    }

    /**
     * The loaded geometry quiz, which is the fixture's only revisable exam.
     *
     * <p>Named rather than indexed at every call site: {@code rows().get(2)} says nothing about
     * <em>why</em> that row and not the first, and the reason is the whole amendment.
     */
    private ExamListRow geometryRow() {
        return session.rows().stream()
                .filter(row -> row.examId() == GEOMETRY)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the geometry quiz is not loaded"));
    }

    // ===================== Loading ========================================

    @Nested
    @DisplayName("loading the list")
    class Loading {

        @Test
        @DisplayName("a loaded list is READY and holds every exam, newest first")
        void loads() {
            serverHasTheExams();
            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.rows()).extracting(ExamListRow::name)
                    .containsExactly("Algebra midterm", "Calculus final", "Geometry quiz");
            assertThat(session.error()).isEmpty();
        }

        @Test
        @DisplayName("EXAM_LIST is sent with no payload, because whose exams these are is the "
                + "session's to know")
        void sendsNoPayload() {
            serverHasTheExams();
            session.load();

            assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.EXAM_LIST);
            assertThat(connection.lastSent().getPayload()).isNull();
        }

        @Test
        @DisplayName("an empty answer is EMPTY, not ERROR: a teacher with no exams is a panel")
        void emptyIsAnAnswer() {
            connection.replyOk(Verb.EXAM_LIST, ExamList.empty());
            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.EMPTY);
            assertThat(session.error()).isEmpty();
            assertThat(session.selectedExam()).isEmpty();
        }

        @Test
        @DisplayName("a refused load is ERROR with the retry sentence")
        void refusedLoad() {
            connection.replyError(Verb.EXAM_LIST, ErrorCode.FORBIDDEN, "no");
            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.error()).contains(ExamListCopy.LOAD_FAILED);
            assertThat(session.rows()).isEmpty();
        }

        @Test
        @DisplayName("a payload of the wrong type is an error, not a class cast")
        void wrongPayload() {
            connection.replyOk(Verb.EXAM_LIST, "not an exam list");
            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.error()).contains(ExamListCopy.LOAD_FAILED);
        }

        @Test
        @DisplayName("load() refuses a second read while one is in flight")
        void oneAtATime() {
            session.load();
            session.load();

            assertThat(countSent(Verb.EXAM_LIST)).isEqualTo(1);
        }

        @Test
        @DisplayName("every settle notifies the screen")
        void notifies() {
            serverHasTheExams();
            renders = 0;
            session.load();

            assertThat(renders).isGreaterThanOrEqualTo(2);
        }
    }

    // ===================== Selection ======================================

    @Nested
    @DisplayName("selection")
    class Selection {

        @Test
        @DisplayName("the first exam is open when the answer lands")
        void firstIsOpen() {
            serverHasTheExams();
            session.load();

            assertThat(session.selectedExam()).get()
                    .extracting(ExamListRow::examId).isEqualTo(MIDTERM);
            assertThat(session.versions()).hasSize(3);
        }

        @Test
        @DisplayName("selecting an exam opens its versions")
        void selects() {
            serverHasTheExams();
            session.load();
            session.select(FINAL_EXAM);

            assertThat(session.versions()).extracting(ExamVersionRow::examVersionId)
                    .containsExactly(FINAL_V1);
        }

        @Test
        @DisplayName("a reload keeps the exam she was looking at open ⚑")
        void reloadKeepsSelection() {
            serverHasTheExams();
            session.load();
            session.select(FINAL_EXAM);

            session.reload();

            assertThat(session.selectedExam()).get()
                    .extracting(ExamListRow::examId).isEqualTo(FINAL_EXAM);
        }

        @Test
        @DisplayName("an exam that vanished between reads gives way to the first one")
        void vanishedSelection() {
            serverHasTheExams();
            session.load();
            session.select(FINAL_EXAM);

            connection.respondTo(Verb.EXAM_LIST, request ->
                    Message.ok(request, new ExamList(List.of(midterm()))));
            session.reload();

            assertThat(session.selectedExam()).get()
                    .extracting(ExamListRow::examId).isEqualTo(MIDTERM);
        }

        @Test
        @DisplayName("nothing is selected when the list is empty")
        void emptyHasNoSelection() {
            connection.replyOk(Verb.EXAM_LIST, ExamList.empty());
            session.load();

            assertThat(session.selectedExam()).isEmpty();
            assertThat(session.versions()).isEmpty();
            assertThat(session.focusedVersion()).isEmpty();
        }

        @Test
        @DisplayName("selecting the exam already open changes nothing and does not re-render")
        void reselectIsQuiet() {
            serverHasTheExams();
            session.load();
            renders = 0;

            session.select(MIDTERM);

            assertThat(renders).isZero();
        }
    }

    // ===================== The focused version ============================

    @Nested
    @DisplayName("which version the panel describes")
    class Focus {

        @Test
        @DisplayName("the first sent-back version, because that is what she is most likely "
                + "here for")
        void prefersRejected() {
            serverHasTheExams();
            session.load();

            assertThat(session.focusedVersion()).get()
                    .extracting(ExamVersionRow::examVersionId).isEqualTo(MIDTERM_V2);
        }

        @Test
        @DisplayName("the newest, when nothing was sent back")
        void fallsBackToNewest() {
            serverHasTheExams();
            session.load();
            session.select(FINAL_EXAM);

            assertThat(session.focusedVersion()).get()
                    .extracting(ExamVersionRow::examVersionId).isEqualTo(FINAL_V1);
        }
    }

    // ===================== What the old screen did ========================

    /**
     * The two behaviours contract section 8's field-by-field table does not cover.
     *
     * <p>Both were on {@code MyApprovalsSession} and both are load-bearing for F4.2 and NFR-18.
     * A swap that kept every field and dropped these would read as a clean retirement in the
     * contract and as a regression on the screen.
     */
    @Nested
    @DisplayName("inherited from the screen this replaces")
    class Retirement {

        @Test
        @DisplayName("a notification's version opens the exam that owns it ⚑")
        void deepLinkSelectsTheOwningExam() {
            serverHasTheExams();
            session.selectedVersionId(FINAL_V1);
            session.load();

            assertThat(session.selectedExam()).get()
                    .extracting(ExamListRow::examId).isEqualTo(FINAL_EXAM);
            assertThat(session.focusedVersion()).get()
                    .extracting(ExamVersionRow::examVersionId).isEqualTo(FINAL_V1);
        }

        @Test
        @DisplayName("a deep link to a rejected version beats the first-rejected fallback")
        void deepLinkBeatsFallback() {
            serverHasTheExams();
            session.selectedVersionId(MIDTERM_V1);
            session.load();

            assertThat(session.focusedVersion()).get()
                    .extracting(ExamVersionRow::examVersionId).isEqualTo(MIDTERM_V1);
        }

        @Test
        @DisplayName("a dangling reference lands on the list rather than on an error")
        void danglingDeepLink() {
            serverHasTheExams();
            session.selectedVersionId(4242L);
            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.selectedExam()).get()
                    .extracting(ExamListRow::examId).isEqualTo(MIDTERM);
        }

        /**
         * Picking an exam herself clears the deep link.
         *
         * <p><b>The route here is deliberate and this test was decorative before it took it.</b>
         * The obvious scenario, deep-linking to the calculus final and then selecting the
         * midterm, cannot see this property at all: the stale id belongs to an exam that is no
         * longer selected, so {@code focusedVersion} does not find it among the midterm's
         * versions and falls through to the first sent-back one either way. Removing the clear
         * left all 43 tests green, which is what a mutation round is for.
         *
         * <p>What discriminates is a deep link to a version of the exam she comes <em>back</em>
         * to, and one the fallback would not have chosen: v1 is approved, so a stale link
         * pinning it is visible exactly where a stale link pinning v2 would not be.
         */
        @Test
        @DisplayName("picking an exam herself clears the deep link ⚑")
        void selectingClearsTheDeepLink() {
            serverHasTheExams();
            session.selectedVersionId(MIDTERM_V1);
            session.load();
            assertThat(session.focusedVersion()).get()
                    .as("the deep link is honoured first, or the rest of this proves nothing")
                    .extracting(ExamVersionRow::examVersionId).isEqualTo(MIDTERM_V1);

            session.select(FINAL_EXAM);
            session.select(MIDTERM);

            // Without the clear the focus is still pinned to v1, the version the notification
            // named, on a screen she has navigated away from and back to by hand.
            assertThat(session.focusedVersion()).get()
                    .extracting(ExamVersionRow::examVersionId).isEqualTo(MIDTERM_V2);
        }

        /**
         * The live re-read, driven through the REAL bus rather than by calling the method.
         *
         * <p>The first version of this test called {@code session.onDecisionArrived()} directly
         * and was named for a push. A cold read found what that could not: nothing in the client
         * called that method at all, on this session or on the one it inherited it from, so the
         * behaviour it was named for did not exist and deleting the wiring changed nothing about
         * the assertion. Pushing a real {@code NotificationDto} onto a real
         * {@code ClientEventBus} is the only shape that can fail.
         */
        @Test
        @DisplayName("an approval push re-reads the list without her pressing anything (NFR-18) ⚑")
        void anApprovalPushReloadsThroughTheRealBus() {
            serverHasTheExams();
            session.load();

            connection.pushToClient(Verb.PUSH_NOTIFICATION,
                    notification(NotificationType.APPROVAL_REJECTED));

            assertThat(countSent(Verb.EXAM_LIST)).isEqualTo(2);
        }

        @Test
        @DisplayName("a supersede is a decision too: it moves a version's state like the others")
        void supersedeReloads() {
            serverHasTheExams();
            session.load();

            connection.pushToClient(Verb.PUSH_NOTIFICATION,
                    notification(NotificationType.APPROVAL_SUPERSEDED));

            assertThat(countSent(Verb.EXAM_LIST)).isEqualTo(2);
        }

        @Test
        @DisplayName("a push about something else does not re-query an exam list ⚑")
        void unrelatedPushIsIgnored() {
            serverHasTheExams();
            session.load();

            connection.pushToClient(Verb.PUSH_NOTIFICATION,
                    notification(NotificationType.GRADE_PUBLISHED));
            connection.pushToClient(Verb.PUSH_LOCK_CHANGED, "not a notification");

            assertThat(countSent(Verb.EXAM_LIST))
                    .as("PUSH_NOTIFICATION carries every kind this app has; a published grade is "
                            + "not a reason to re-read her exams")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("drafts are on this screen, which is the whole point of the retirement")
        void draftsAreVisible() {
            serverHasTheExams();
            session.load();

            assertThat(session.versions()).extracting(ExamVersionRow::state)
                    .contains(ApprovalState.DRAFT);
        }
    }

    // ===================== What a state permits ===========================

    @Nested
    @DisplayName("what each state permits")
    class Permissions {

        @Test
        @DisplayName("only a draft may be submitted")
        void submitOnlyDraft() {
            serverHasTheExams();
            session.load();
            List<ExamVersionRow> versions = session.versions();

            assertThat(session.canSubmit(versions.get(0))).isTrue();   // v3 DRAFT
            assertThat(session.canSubmit(versions.get(1))).isFalse();  // v2 REJECTED
            assertThat(session.canSubmit(versions.get(2))).isFalse();  // v1 APPROVED
        }

        @Test
        @DisplayName("on an exam with no open draft, everything that is not a draft may be "
                + "revised")
        void reviseNeverDraft() {
            serverHasTheExams();
            session.load();
            session.select(GEOMETRY);
            ExamListRow exam = session.selectedExam().orElseThrow();
            List<ExamVersionRow> versions = session.versions();

            assertThat(session.canRevise(exam, versions.get(0))).isTrue();   // v3 REJECTED
            assertThat(session.canRevise(exam, versions.get(1))).isTrue();   // v2 APPROVED
            assertThat(session.canRevise(exam, versions.get(2))).isTrue();   // v1 APPROVED
        }

        /**
         * One open draft per exam, on the client (§5.4 as amended 2026-08-25).
         *
         * <p>The midterm has a DRAFT at v3, so <b>nothing</b> on it may be revised, including the
         * approved v1 that the old rule offered. This is the whole amendment: without it the list
         * offers a button whose only possible outcome is now a refusal.
         */
        @Test
        @DisplayName("⚑ an exam with an open draft offers Revise on none of its versions")
        void anOpenDraftBlocksEveryRevise() {
            serverHasTheExams();
            session.load();
            ExamListRow exam = session.selectedExam().orElseThrow();

            assertThat(exam.versions()).extracting(ExamVersionRow::state)
                    .as("guard against the guard: this exam really does have a draft")
                    .contains(ApprovalState.DRAFT);
            assertThat(session.canRevise(exam, exam.versions().get(0))).isFalse(); // v3 DRAFT
            assertThat(session.canRevise(exam, exam.versions().get(1))).isFalse(); // v2 REJECTED
            assertThat(session.canRevise(exam, exam.versions().get(2))).isFalse(); // v1 APPROVED
        }

        @Test
        @DisplayName("a pending version may be revised, which is E7.5's own wording")
        void pendingIsRevisable() {
            serverHasTheExams();
            session.load();
            session.select(FINAL_EXAM);
            ExamListRow exam = session.selectedExam().orElseThrow();

            assertThat(session.canRevise(exam, session.versions().get(0))).isTrue();
        }

        @Test
        @DisplayName("null is neither, rather than a null pointer on a write path")
        void nullPermitsNothing() {
            serverHasTheExams();
            session.load();
            ExamListRow exam = session.selectedExam().orElseThrow();

            assertThat(session.canSubmit(null)).isFalse();
            assertThat(session.canRevise(exam, null)).isFalse();
            assertThat(session.canRevise(null, exam.versions().get(2))).isFalse();
            assertThat(session.canRevise(null, null)).isFalse();
        }

        @Test
        @DisplayName("a refused combination sends nothing at all")
        void refusedCombinationSendsNothing() {
            serverHasTheExams();
            session.load();
            ExamListRow exam = session.rows().get(0);
            ExamVersionRow approved = exam.versions().get(2);

            session.submit(exam, approved);

            assertThat(countSent(Verb.EXAM_SUBMIT)).isZero();
        }
    }

    // ===================== The token an action carries ====================

    /**
     * The defect an independent reader would go looking for on this screen.
     *
     * <p>A list with per-row buttons and an optimistic token has exactly one interesting way to
     * be wrong: sending the token of the selected row with the id of the pressed row, or the
     * reverse. Both produce a CONFLICT most of the time, which reads as a race rather than as a
     * bug, and a write against the wrong version the rest of the time.
     */
    @Nested
    @DisplayName("an action carries the token of the row it was pressed on")
    class ActionTokens {

        @Test
        @DisplayName("submit sends the pressed version's id and its own lock token ⚑")
        void submitCarriesItsOwnToken() {
            serverHasTheExams();
            connection.respondTo(Verb.EXAM_SUBMIT, request ->
                    Message.ok(request, composition(MIDTERM_V3, 3, ApprovalState.PENDING)));
            session.load();
            ExamListRow exam = session.rows().get(0);
            ExamVersionRow draft = exam.versions().get(0);

            session.submit(exam, draft);

            assertThat(lastAction(Verb.EXAM_SUBMIT))
                    .isEqualTo(new ExamVersionAction(MIDTERM_V3, 7));
        }

        @Test
        @DisplayName("revise on an older version sends THAT version's token, not the latest's ⚑")
        void reviseCarriesTheOlderToken() {
            serverHasTheExams();
            connection.respondTo(Verb.EXAM_VERSION_REVISE, request ->
                    Message.ok(request, composition(9204L, 4, ApprovalState.DRAFT)));
            session.load();
            // The geometry quiz, because the midterm has an open draft and the amended §5.4
            // makes every version of it unrevisable.
            ExamListRow exam = geometryRow();
            ExamVersionRow approvedV1 = exam.versions().get(2);

            session.revise(exam, approvedV1);

            // v1's token is 2; the latest version's is 6. A session that looked the row up by
            // selection instead of using the object handed to it would send 6 here.
            assertThat(lastAction(Verb.EXAM_VERSION_REVISE))
                    .isEqualTo(new ExamVersionAction(GEOMETRY_V1, 2));
        }

        @Test
        @DisplayName("an action on one exam's version is unaffected by which exam is selected")
        void selectionDoesNotLeakIntoTheAction() {
            serverHasTheExams();
            connection.respondTo(Verb.EXAM_VERSION_REVISE, request ->
                    Message.ok(request, composition(9102L, 2, ApprovalState.DRAFT)));
            session.load();
            ExamListRow other = session.rows().get(1);
            ExamVersionRow pending = other.versions().get(0);
            session.select(MIDTERM);

            session.revise(other, pending);

            assertThat(lastAction(Verb.EXAM_VERSION_REVISE))
                    .isEqualTo(new ExamVersionAction(FINAL_V1, 1));
        }
    }

    // ===================== Submit and revise ==============================

    @Nested
    @DisplayName("submitting and revising")
    class Actions {

        @Test
        @DisplayName("a submit that lands says so and re-reads the list")
        void submitSucceeds() {
            serverHasTheExams();
            connection.respondTo(Verb.EXAM_SUBMIT, request ->
                    Message.ok(request, composition(MIDTERM_V3, 3, ApprovalState.PENDING)));
            session.load();
            ExamListRow exam = session.rows().get(0);

            session.submit(exam, exam.versions().get(0));

            assertThat(session.actionNotice()).contains(ExamListCopy.SUBMITTED_NOTICE);
            assertThat(session.actionError()).isEmpty();
            assertThat(countSent(Verb.EXAM_LIST)).isEqualTo(2);
            assertThat(session.isActing()).isFalse();
        }

        @Test
        @DisplayName("a revise names the version the SERVER made, never a predicted one ⚑")
        void reviseNamesTheServersVersion() {
            serverHasTheExams();
            // The server allocated 9, not the 4 a client counting from latestVersionNo would.
            connection.respondTo(Verb.EXAM_VERSION_REVISE, request ->
                    Message.ok(request, composition(9209L, 9, ApprovalState.DRAFT)));
            session.load();
            ExamListRow exam = geometryRow();

            session.revise(exam, exam.versions().get(1));

            assertThat(session.actionNotice()).contains(ExamListCopy.revisedNotice(9));
            assertThat(session.actionNotice()).contains("Version 9 is ready as a draft.");
        }

        @Test
        @DisplayName("after a revise she is looking at what she just made")
        void reviseFocusesTheNewDraft() {
            ExamListRow revised = new ExamListRow(GEOMETRY, "110201", "11", "אלגברה",
                    "Geometry quiz", 4,
                    List.of(version(9204L, 4, ApprovalState.DRAFT, "", 8, 45, 1),
                            version(GEOMETRY_V3, 3, ApprovalState.REJECTED, SENT_BACK, 8, 45, 6),
                            version(GEOMETRY_V2, 2, ApprovalState.APPROVED, "", 8, 45, 3),
                            version(GEOMETRY_V1, 1, ApprovalState.APPROVED, "", 6, 30, 2)));
            serverHasTheExams();
            connection.respondTo(Verb.EXAM_VERSION_REVISE, request ->
                    Message.ok(request, composition(9204L, 4, ApprovalState.DRAFT)));
            session.load();
            ExamListRow exam = geometryRow();
            connection.respondTo(Verb.EXAM_LIST, request ->
                    Message.ok(request, new ExamList(List.of(midterm(), finalExam(), revised))));

            session.revise(exam, exam.versions().get(2));

            assertThat(session.focusedVersion()).get()
                    .extracting(ExamVersionRow::examVersionId).isEqualTo(9204L);
        }

        @Test
        @DisplayName("a CONFLICT re-reads the list and reports nothing as a success ⚑")
        void conflictReloads() {
            serverHasTheExams();
            connection.replyError(Verb.EXAM_SUBMIT, ErrorCode.CONFLICT, "someone got there first");
            session.load();
            ExamListRow exam = session.rows().get(0);

            session.submit(exam, exam.versions().get(0));

            assertThat(session.actionError()).contains("someone got there first");
            assertThat(session.actionNotice())
                    .as("a refusal is never also a success notice")
                    .isEmpty();
            assertThat(countSent(Verb.EXAM_LIST))
                    .as("the row on screen may be stale, so it is re-read whatever the cause")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a NOT_FOUND says the version is gone and re-reads the list")
        void notFoundReloads() {
            serverHasTheExams();
            connection.replyError(Verb.EXAM_SUBMIT, ErrorCode.NOT_FOUND, "gone");
            session.load();
            ExamListRow exam = session.rows().get(0);

            session.submit(exam, exam.versions().get(0));

            assertThat(session.actionError()).contains(ExamListCopy.GONE_NOTICE);
            assertThat(countSent(Verb.EXAM_LIST)).isEqualTo(2);
        }

        @Test
        @DisplayName("a VALIDATION refusal shows the server's own sentence and leaves the list "
                + "alone ⚑")
        void validationKeepsTheServersSentence() {
            serverHasTheExams();
            connection.replyError(Verb.EXAM_VERSION_REVISE, ErrorCode.VALIDATION,
                    "Question 11005 was deleted from the bank.");
            session.load();
            ExamListRow exam = geometryRow();

            session.revise(exam, exam.versions().get(1));

            // The refusal is about the bank, not about this list, so re-reading would tell her
            // nothing and would throw away the only sentence that explains it.
            assertThat(session.actionError())
                    .contains("Question 11005 was deleted from the bank.");
            assertThat(countSent(Verb.EXAM_LIST)).isEqualTo(1);
        }

        @Test
        @DisplayName("a VALIDATION refusal with no sentence still says something")
        void validationWithNoSentence() {
            serverHasTheExams();
            connection.replyError(Verb.EXAM_VERSION_REVISE, ErrorCode.VALIDATION, "  ");
            session.load();
            ExamListRow exam = geometryRow();

            session.revise(exam, exam.versions().get(1));

            assertThat(session.actionError()).contains(ExamListCopy.ACTION_FAILED);
        }

        @Test
        @DisplayName("an OK carrying the wrong payload is a failure, not a class cast")
        void wrongActionPayload() {
            serverHasTheExams();
            connection.replyOk(Verb.EXAM_SUBMIT, "not a composition");
            session.load();
            ExamListRow exam = session.rows().get(0);

            session.submit(exam, exam.versions().get(0));

            assertThat(session.actionError()).contains(ExamListCopy.ACTION_FAILED);
            assertThat(session.actionNotice()).isEmpty();
        }

        /**
         * The window between a write landing and its re-read landing.
         *
         * <p>Found by a cold read, not by this suite. The buttons are gated on
         * {@code isActing()}, and clearing that flag when the WRITE answered re-enabled them
         * while {@code rows} was still the pre-action list: the card for the version she just
         * revised is on screen, unchanged, with a live Revise. The server has no idempotency to
         * fall back on, because its guard only refuses a version that is itself a draft and does
         * not touch the predecessor's row, so the second press passes the same lock-token check
         * and inserts again. One approved version becomes two drafts.
         */
        @Test
        @DisplayName("a second revise inside the post-write reload window is refused ⚑")
        void noSecondActionWhileTheReloadIsInFlight() {
            connection.respondTo(Verb.EXAM_VERSION_REVISE, request ->
                    Message.ok(request, composition(9204L, 4, ApprovalState.DRAFT)));
            serverHasTheExams();
            session.load();
            ExamListRow exam = geometryRow();
            ExamVersionRow approved = exam.versions().get(2);

            // No responder from here on, so the write settles and its re-read never does: that
            // gap is precisely the window.
            connection.respondTo(Verb.EXAM_LIST, request -> null);
            session.revise(exam, approved);
            assertThat(session.isActing())
                    .as("the buttons stay disabled until the list she is looking at is current")
                    .isTrue();

            session.revise(exam, approved);

            assertThat(countSent(Verb.EXAM_VERSION_REVISE))
                    .as("a second insert here is a second draft of one exam")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the buttons come back once the re-read lands")
        void actingClearsWhenTheListIsCurrent() {
            serverHasTheExams();
            connection.respondTo(Verb.EXAM_SUBMIT, request ->
                    Message.ok(request, composition(MIDTERM_V3, 3, ApprovalState.PENDING)));
            session.load();
            ExamListRow exam = session.rows().get(0);

            session.submit(exam, exam.versions().get(0));

            assertThat(session.isActing()).isFalse();
        }

        /**
         * CONFLICT has three causes and only two of them are staleness.
         *
         * <p>The third is an edit lock held by a colleague, and there the server's sentence names
         * him. Replacing it with "the list was reloaded, check the version" sends her to look at
         * a version with nothing wrong with it.
         */
        @Test
        @DisplayName("a CONFLICT keeps the server's sentence when it has one ⚑")
        void conflictKeepsTheLockSentence() {
            serverHasTheExams();
            connection.replyError(Verb.EXAM_SUBMIT, ErrorCode.CONFLICT,
                    "Ron Levi is editing this exam.");
            session.load();
            ExamListRow exam = session.rows().get(0);

            session.submit(exam, exam.versions().get(0));

            assertThat(session.actionError()).contains("Ron Levi is editing this exam.");
            assertThat(session.actionError().orElseThrow())
                    .as("and it is not overwritten by the generic staleness sentence")
                    .doesNotContain(ExamListCopy.STALE_NOTICE);
            assertThat(countSent(Verb.EXAM_LIST))
                    .as("the reload still happens: staleness is still one of the three causes")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a CONFLICT with no sentence still falls back to the staleness one")
        void conflictWithNoSentence() {
            serverHasTheExams();
            connection.replyError(Verb.EXAM_SUBMIT, ErrorCode.CONFLICT, "   ");
            session.load();
            ExamListRow exam = session.rows().get(0);

            session.submit(exam, exam.versions().get(0));

            assertThat(session.actionError()).contains(ExamListCopy.STALE_NOTICE);
        }

        @Test
        @DisplayName("a second action is refused while one is in flight")
        void oneActionAtATime() {
            serverHasTheExams();
            session.load();
            ExamListRow exam = session.rows().get(0);
            // No responder for EXAM_SUBMIT, so the first one never settles.
            session.submit(exam, exam.versions().get(0));
            session.submit(exam, exam.versions().get(0));

            assertThat(countSent(Verb.EXAM_SUBMIT)).isEqualTo(1);
            assertThat(session.isActing()).isTrue();
        }

        @Test
        @DisplayName("both notices can be dismissed, so they do not reappear on every render")
        void noticesDismiss() {
            serverHasTheExams();
            connection.respondTo(Verb.EXAM_SUBMIT, request ->
                    Message.ok(request, composition(MIDTERM_V3, 3, ApprovalState.PENDING)));
            session.load();
            ExamListRow exam = session.rows().get(0);
            session.submit(exam, exam.versions().get(0));

            session.dismissNotice();
            assertThat(session.actionNotice()).isEmpty();

            connection.replyError(Verb.EXAM_SUBMIT, ErrorCode.CONFLICT, "no");
            session.submit(exam, exam.versions().get(0));
            assertThat(session.actionError()).isPresent();
            session.dismissActionError();
            assertThat(session.actionError()).isEmpty();
        }

        @Test
        @DisplayName("a null exam or version sends nothing")
        void nullsSendNothing() {
            serverHasTheExams();
            session.load();
            ExamListRow exam = session.rows().get(0);

            session.submit(null, exam.versions().get(0));
            session.submit(exam, null);
            session.revise(null, null);

            assertThat(countSent(Verb.EXAM_SUBMIT)).isZero();
            assertThat(countSent(Verb.EXAM_VERSION_REVISE)).isZero();
        }
    }

    // ===================== Late answers ===================================

    /**
     * No responder is registered here. The requests are read out of {@code sentMessages()} and
     * answered by hand, in the order the test chooses, which is the only way to reach the
     * generation counter.
     */
    @Nested
    @DisplayName("late answers are dropped, not applied")
    class LateAnswers {

        @Test
        @DisplayName("a stale EXAM_LIST answer landing after a newer one is discarded ⚑")
        void staleListAnswerDiscarded() {
            session.load();
            Message first = connection.sentMessages().get(0);
            session.reload();
            Message second = connection.sentMessages().get(1);

            // The newer answer lands first, then the older one.
            connection.deliver(Message.ok(second, new ExamList(List.of(finalExam()))));
            connection.deliver(Message.ok(first, new ExamList(List.of(midterm(), finalExam()))));

            assertThat(session.rows()).extracting(ExamListRow::name)
                    .containsExactly("Calculus final");
        }

        @Test
        @DisplayName("a stale FAILURE landing after a good answer does not blank the screen")
        void staleFailureDiscarded() {
            session.load();
            Message first = connection.sentMessages().get(0);
            session.reload();
            Message second = connection.sentMessages().get(1);

            connection.deliver(Message.ok(second, new ExamList(List.of(midterm()))));
            connection.deliver(Message.error(first, ErrorCode.INTERNAL, "boom"));

            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.rows()).hasSize(1);
            assertThat(session.error()).isEmpty();
        }

        @Test
        @DisplayName("reload() starts a read even while one is in flight, which load() will not")
        void reloadIgnoresTheInFlightGuard() {
            session.load();
            session.reload();

            assertThat(countSent(Verb.EXAM_LIST)).isEqualTo(2);
        }
    }
}
