package client.features.exambuild;

import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.approval.ApprovalState;
import common.dto.authoring.ComposedQuestion;
import common.dto.authoring.ExamComposition;
import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.ExamVersionRequest;
import common.dto.authoring.ExamVersionSave;
import common.dto.authoring.QuestionPin;
import common.dto.bank.Difficulty;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExamBuilderSession} — E7.11 and E7.12's behaviour, without a JavaFX toolkit.
 *
 * <h2>The nested class that matters</h2>
 *
 * <p>{@link Modes}. The expensive defect available on this screen is a save that goes to the
 * wrong verb, and the mode is what decides. Every other group here is downstream of it being
 * right.
 */
class ExamBuilderSessionTest {

    private static final Instant WHEN = Instant.parse("2026-08-24T09:00:00Z");
    private static final long VERSION_ID = 7001L;
    private static final long EXAM_ID = 700L;

    private FakeClientConnection connection;
    private ExamBuilderSession session;

    @BeforeEach
    void setUp() {
        connection = new FakeClientConnection();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        session = new ExamBuilderSession(dispatcher, new DirectFxThreadPoster());
    }

    // ===================== Fixture ========================================

    private static ComposedQuestion question(long versionId, String displayId, int ord,
                                             int points, int pinned, int latest) {
        return new ComposedQuestion(versionId, displayId, ord, points, "What is recursion?",
                "Recursion", Difficulty.MEDIUM, false, pinned, latest);
    }

    /** A three-question draft summing to 100, which is what a stored version always does. */
    private static ExamComposition stored(ApprovalState state, int lockVersion) {
        return new ExamComposition(EXAM_ID, "110101", "11", "אלגברה", VERSION_ID, 2, state,
                "Algebra midterm", 90, "Good luck", "Marking notes", "דנה כהן", WHEN, "",
                List.of(question(9001L, "11001", 1, 50, 1, 1),
                        question(9002L, "11002", 2, 30, 2, 4),
                        question(9003L, "11003", 3, 20, 1, 1)),
                lockVersion);
    }

    /** A different exam, so a stale answer can be told apart from the one being waited for. */
    private static ExamComposition other(long versionId, String name) {
        return new ExamComposition(701L, "120101", "12", "חדו\"א", versionId, 1,
                ApprovalState.DRAFT, name, 120, null, null, "דנה כהן", WHEN, "",
                List.of(question(9101L, "12001", 1, 100, 1, 1)), 1);
    }

    private void serverHas(ApprovalState state, int lockVersion) {
        connection.respondTo(Verb.EXAM_VERSION_GET, request ->
                Message.ok(request, stored(state, lockVersion)));
    }

    private void openDraft() {
        serverHas(ApprovalState.DRAFT, 3);
        session.open(VERSION_ID);
    }

    private Message lastSent(Verb verb) {
        return connection.sentMessages().stream()
                .filter(message -> message.getVerb() == verb)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no " + verb + " was sent"));
    }

    private long countSent(Verb verb) {
        return connection.sentMessages().stream()
                .filter(message -> message.getVerb() == verb)
                .count();
    }

    // ===================== The three modes ================================

    @Nested
    @DisplayName("which of three things the screen is doing")
    class Modes {

        @Test
        @DisplayName("no version id is CREATE, whatever else is true")
        void noVersionIsCreate() {
            session.openNew("11");

            assertThat(session.mode()).isEqualTo(ExamBuilderSession.Mode.CREATE);
            assertThat(session.isEditable()).isTrue();
        }

        @Test
        @DisplayName("a version that came back a DRAFT is EDIT")
        void draftIsEdit() {
            openDraft();

            assertThat(session.mode()).isEqualTo(ExamBuilderSession.Mode.EDIT);
            assertThat(session.isEditable()).isTrue();
        }

        /**
         * §8's read path, ruled 2026-08-25.
         *
         * <p>Every non-draft state, because the rule is "not a draft" rather than a list of three
         * states somebody has to keep in step with the enum. A fourth state added later is
         * read-only here by construction.
         */
        @ParameterizedTest
        @EnumSource(value = ApprovalState.class, names = {"PENDING", "APPROVED", "REJECTED"})
        @DisplayName("⚑ any version that is not a draft is READ_ONLY")
        void nonDraftIsReadOnly(ApprovalState state) {
            serverHas(state, 3);
            session.open(VERSION_ID);

            assertThat(session.mode()).isEqualTo(ExamBuilderSession.Mode.READ_ONLY);
            assertThat(session.isEditable()).isFalse();
        }

        /**
         * The mode rule as a pure function, so the table is readable in one place.
         *
         * <p>The session tests above prove it is <em>wired</em>; this proves the rule itself,
         * including the case a screen can be in before any answer has arrived.
         */
        @Test
        @DisplayName("the mode rule, as a table")
        void theRuleItself() {
            assertThat(ExamBuilderSession.modeFor(0, null))
                    .isEqualTo(ExamBuilderSession.Mode.CREATE);
            assertThat(ExamBuilderSession.modeFor(0, ApprovalState.DRAFT))
                    .as("a create stays a create even if a state somehow arrives")
                    .isEqualTo(ExamBuilderSession.Mode.CREATE);
            assertThat(ExamBuilderSession.modeFor(VERSION_ID, ApprovalState.DRAFT))
                    .isEqualTo(ExamBuilderSession.Mode.EDIT);
            assertThat(ExamBuilderSession.modeFor(VERSION_ID, null))
                    .as("before the answer lands, an opened version is not yet editable")
                    .isEqualTo(ExamBuilderSession.Mode.READ_ONLY);
        }
    }

    // ===================== Opening ========================================

    @Nested
    @DisplayName("opening")
    class Opening {

        @Test
        @DisplayName("a new exam starts empty, on the course it was opened for")
        void openNewStartsEmpty() {
            session.openNew("11");

            assertThat(session.lines()).isEmpty();
            assertThat(session.name()).isEmpty();
            assertThat(session.courseCode()).isEqualTo("11");
            assertThat(session.durationMinutes())
                    .isEqualTo(ExamBuildCopy.DEFAULT_DURATION_MINUTES);
            assertThat(countSent(Verb.EXAM_VERSION_GET))
                    .as("a new exam has nothing to read back")
                    .isZero();
        }

        @Test
        @DisplayName("opening a version asks for it and adopts every field")
        void openLoads() {
            openDraft();

            assertThat(lastSent(Verb.EXAM_VERSION_GET).getPayload())
                    .isEqualTo(new ExamVersionRequest(VERSION_ID));
            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.name()).isEqualTo("Algebra midterm");
            assertThat(session.durationMinutes()).isEqualTo(90);
            assertThat(session.studentText()).isEqualTo("Good luck");
            assertThat(session.teacherText()).isEqualTo("Marking notes");
            assertThat(session.courseCode()).isEqualTo("11");
            assertThat(session.displayId6()).isEqualTo("110101");
            assertThat(session.lines()).hasSize(3);
        }

        @Test
        @DisplayName("null texts arrive as empty strings, never as the word null on a form")
        void nullTextsBecomeEmpty() {
            connection.respondTo(Verb.EXAM_VERSION_GET, request -> Message.ok(request,
                    new ExamComposition(EXAM_ID, "110101", "11", "אלגברה", VERSION_ID, 1,
                            ApprovalState.DRAFT, "Algebra midterm", 90, null, null, "דנה כהן",
                            WHEN, "", List.of(question(9001L, "11001", 1, 100, 1, 1)), 1)));
            session.open(VERSION_ID);

            assertThat(session.studentText()).isEmpty();
            assertThat(session.teacherText()).isEmpty();
        }

        @Test
        @DisplayName("a failed load is ERROR with the retry sentence")
        void failedLoad() {
            connection.replyError(Verb.EXAM_VERSION_GET, ErrorCode.NOT_FOUND, "gone");
            session.open(VERSION_ID);

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.loadError()).contains(ExamBuildCopy.LOAD_FAILED);
        }

        @Test
        @DisplayName("a payload of the wrong type is an error, not a class cast")
        void wrongPayload() {
            connection.replyOk(Verb.EXAM_VERSION_GET, "not a composition");
            session.open(VERSION_ID);

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
        }

        /**
         * Opening B while A is still in flight must not adopt A under B's identity.
         *
         * <p>Found by a cold read. The first version of {@code open} committed the new id and
         * then returned early while a load was running, so A's answer landed and was adopted:
         * the form showed A's paper, was editable if A was a draft, and Save wrote to A with A's
         * token, while she believed she was editing B. No error anywhere on that path, which is
         * what makes it a generation counter rather than an ordering rule.
         */
        @Test
        @DisplayName("⚑ a stale answer cannot land under a newer open")
        void staleAnswerIsDropped() {
            session.open(7001L);
            Message first = connection.sentMessages().get(0);
            session.open(7002L);
            Message second = connection.sentMessages().get(1);

            connection.deliver(Message.ok(second, other(7002L, "Calculus final")));
            connection.deliver(Message.ok(first, stored(ApprovalState.DRAFT, 3)));

            assertThat(session.name())
                    .as("the answer she is waiting for is 7002's, whatever order they arrive in")
                    .isEqualTo("Calculus final");
            assertThat(session.examVersionId()).isEqualTo(7002L);
        }

        @Test
        @DisplayName("⚑ a second open really asks again rather than dropping the request")
        void reopeningAsksAgain() {
            session.open(7001L);
            session.open(7002L);

            assertThat(countSent(Verb.EXAM_VERSION_GET))
                    .as("the early return that used to sit here left the screen waiting on an "
                            + "answer to a question it had stopped asking")
                    .isEqualTo(2);
        }

        /**
         * A new exam keeps nothing from whatever was open before.
         *
         * <p>{@code openNew} used to clear the form fields and leave {@code examVersionId},
         * {@code lockVersion}, {@code displayId6}, {@code courseName} and {@code saved} behind,
         * so the "New exam" heading carried the previous exam's 6-digit id and course.
         */
        @Test
        @DisplayName("⚑ a new exam carries nothing over from the version that was open")
        void openNewClearsEverything() {
            openDraft();
            assertThat(session.displayId6()).isEqualTo("110101");

            session.openNew("12");

            assertThat(session.displayId6()).isEmpty();
            assertThat(session.examVersionId()).isZero();
            assertThat(session.courseName()).isEmpty();
            assertThat(session.isSaved()).isFalse();
            assertThat(session.name()).isEmpty();
            assertThat(session.lines()).isEmpty();
            assertThat(session.mode()).isEqualTo(ExamBuilderSession.Mode.CREATE);
        }

        @Test
        @DisplayName("⚑ an answer in flight cannot land on a new-exam form either")
        void openNewRetiresAnInFlightLoad() {
            session.open(VERSION_ID);
            Message asked = connection.sentMessages().get(0);

            session.openNew("12");
            connection.deliver(Message.ok(asked, stored(ApprovalState.DRAFT, 3)));

            assertThat(session.lines())
                    .as("adopting here would offer Create on a duplicate of an existing exam")
                    .isEmpty();
            assertThat(session.name()).isEmpty();
        }

        @Test
        @DisplayName("the retry asks for the same version again, and does nothing on a new exam")
        void reopenRetries() {
            connection.replyError(Verb.EXAM_VERSION_GET, ErrorCode.INTERNAL, "boom");
            session.open(VERSION_ID);
            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);

            serverHas(ApprovalState.DRAFT, 3);
            session.reopen();

            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(countSent(Verb.EXAM_VERSION_GET)).isEqualTo(2);

            session.openNew("11");
            session.reopen();
            assertThat(countSent(Verb.EXAM_VERSION_GET))
                    .as("a new exam has no stored version to re-read")
                    .isEqualTo(2);
        }
    }

    // ===================== Read-only refuses everything ===================

    /**
     * A read-only version is inert, and every way in is closed.
     *
     * <p>One assertion per mutator rather than a spot check: they are separate methods and
     * closing four of five is the shape of defect that ships.
     */
    @Nested
    @DisplayName("a read-only version refuses every edit ⚑")
    class ReadOnly {

        @BeforeEach
        void openApproved() {
            serverHas(ApprovalState.APPROVED, 3);
            session.open(VERSION_ID);
        }

        @Test
        @DisplayName("the metadata cannot be changed")
        void metadataIsInert() {
            session.name("Something else");
            session.durationMinutes(45);
            session.studentText("changed");
            session.teacherText("changed");

            assertThat(session.name()).isEqualTo("Algebra midterm");
            assertThat(session.durationMinutes()).isEqualTo(90);
            assertThat(session.studentText()).isEqualTo("Good luck");
            assertThat(session.teacherText()).isEqualTo("Marking notes");
        }

        @Test
        @DisplayName("the paper cannot be reordered, repointed or shortened")
        void paperIsInert() {
            session.points(0, 1);
            session.moveDown(0);
            session.remove(2);

            assertThat(session.lines()).extracting(ExamBuilderSession.Line::displayId5)
                    .containsExactly("11001", "11002", "11003");
            assertThat(session.lines().get(0).points()).isEqualTo(50);
        }

        @Test
        @DisplayName("and save sends nothing at all")
        void saveIsInert() {
            session.save();

            assertThat(countSent(Verb.EXAM_VERSION_SAVE)).isZero();
            assertThat(countSent(Verb.EXAM_CREATE)).isZero();
        }
    }

    // ===================== The paper ======================================

    @Nested
    @DisplayName("editing the paper")
    class Paper {

        @Test
        @DisplayName("points can be changed on one question without touching the others")
        void changePoints() {
            openDraft();

            session.points(1, 35);

            assertThat(session.lines()).extracting(ExamBuilderSession.Line::points)
                    .containsExactly(50, 35, 20);
        }

        @Test
        @DisplayName("a question moves up and down, and the ends are walls rather than wraps")
        void reorder() {
            openDraft();

            session.moveDown(0);
            assertThat(session.lines()).extracting(ExamBuilderSession.Line::displayId5)
                    .containsExactly("11002", "11001", "11003");

            session.moveUp(1);
            assertThat(session.lines()).extracting(ExamBuilderSession.Line::displayId5)
                    .containsExactly("11001", "11002", "11003");

            session.moveUp(0);
            session.moveDown(2);
            assertThat(session.lines()).extracting(ExamBuilderSession.Line::displayId5)
                    .as("moving off either end does nothing rather than wrapping around")
                    .containsExactly("11001", "11002", "11003");
        }

        @Test
        @DisplayName("out-of-range indexes do nothing rather than throwing on a write path")
        void boundsAreSafe() {
            openDraft();

            session.points(-1, 10);
            session.points(9, 10);
            session.remove(9);
            session.moveUp(-3);

            assertThat(session.lines()).hasSize(3);
        }

        @Test
        @DisplayName("a question can be taken off the paper")
        void remove() {
            openDraft();

            session.remove(1);

            assertThat(session.lines()).extracting(ExamBuilderSession.Line::displayId5)
                    .containsExactly("11001", "11003");
        }

        @Test
        @DisplayName("a line knows when the bank has moved on from what it pins (E7.7)")
        void newerVersionBadge() {
            openDraft();

            assertThat(session.lines().get(0).hasNewerVersion()).isFalse();  // pinned 1, latest 1
            assertThat(session.lines().get(1).hasNewerVersion()).isTrue();   // pinned 2, latest 4
        }

        @Test
        @DisplayName("⚑ the picker's add path refuses rather than guessing at a version id")
        void addIsNotAvailableYet() {
            openDraft();

            assertThat(session.canAddFromBank())
                    .as("BankQuestionRow carries no questionVersionId, so a pin cannot be built "
                            + "from a picker row; raised with the lead as a contract gap")
                    .isFalse();
            assertThat(session.addFromBank()).isFalse();
            assertThat(session.lines()).hasSize(3);
        }
    }

    // ===================== The live points rule ===========================

    @Nested
    @DisplayName("the live points total (E7.3, S-11)")
    class Points {

        @Test
        @DisplayName("a stored version already sums to 100, because the server refuses otherwise")
        void storedIsAlreadyRight() {
            openDraft();

            assertThat(session.pointsTotal()).isEqualTo(100);
            assertThat(session.pointsAreRight()).isTrue();
            assertThat(session.pointsProblem()).isEmpty();
        }

        /**
         * The sentence is the server's own, not a second opinion.
         *
         * <p>Asserted against {@code ExamValidator} rather than against a literal: a literal here
         * would be exactly the second copy this design exists to avoid, and it would keep passing
         * after the server's wording changed.
         */
        @Test
        @DisplayName("⚑ short of 100 reports the SERVER's sentence, by calling its validator")
        void shortOfTarget() {
            openDraft();

            session.points(0, 10);

            assertThat(session.pointsTotal()).isEqualTo(60);
            assertThat(session.pointsAreRight()).isFalse();
            assertThat(session.pointsProblem()).contains(
                    server.features.exambuild.ExamBuildMessages.pointsShort(60));
        }

        @Test
        @DisplayName("over 100 reports the server's other sentence")
        void overTarget() {
            openDraft();

            session.points(0, 90);

            assertThat(session.pointsTotal()).isEqualTo(140);
            assertThat(session.pointsProblem()).contains(
                    server.features.exambuild.ExamBuildMessages.pointsOver(140));
        }

        @Test
        @DisplayName("an empty paper is refused as having no questions, not as summing to zero")
        void emptyPaper() {
            session.openNew("11");

            assertThat(session.pointsAreRight()).isFalse();
            assertThat(session.pointsProblem())
                    .contains(server.features.exambuild.ExamBuildMessages.NO_QUESTIONS);
        }
    }

    // ===================== Saving =========================================

    @Nested
    @DisplayName("saving")
    class Saving {

        @Test
        @DisplayName("⚑ CREATE mode sends EXAM_CREATE carrying the whole composition")
        void createSendsCreate() {
            connection.respondTo(Verb.EXAM_CREATE, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT, 1)));
            session.openNew("11");
            session.name("Algebra midterm");
            session.durationMinutes(90);

            session.save();

            assertThat(countSent(Verb.EXAM_VERSION_SAVE)).isZero();
            assertThat(lastSent(Verb.EXAM_CREATE).getPayload())
                    .isInstanceOf(ExamCreateRequest.class);
            ExamCreateRequest sent = (ExamCreateRequest) lastSent(Verb.EXAM_CREATE).getPayload();
            assertThat(sent.courseCode()).isEqualTo("11");
            assertThat(sent.name()).isEqualTo("Algebra midterm");
            assertThat(sent.durationMinutes()).isEqualTo(90);
        }

        @Test
        @DisplayName("⚑ EDIT mode sends EXAM_VERSION_SAVE with the token it loaded against")
        void editSendsSave() {
            openDraft();
            connection.respondTo(Verb.EXAM_VERSION_SAVE, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT, 4)));

            session.save();

            assertThat(countSent(Verb.EXAM_CREATE)).isZero();
            ExamVersionSave sent =
                    (ExamVersionSave) lastSent(Verb.EXAM_VERSION_SAVE).getPayload();
            assertThat(sent.examVersionId()).isEqualTo(VERSION_ID);
            assertThat(sent.expectedLockVersion())
                    .as("the token the server answered with, not one this screen invented")
                    .isEqualTo(3);
        }

        /**
         * {@code ord} is the list index and has no other home.
         *
         * <p>Reordering and then reading the payload is the only way to see it: the ordinals are
         * written on the way out, so a session that kept a stale {@code ord} on each row would
         * send a paper whose order disagrees with the screen she was looking at.
         */
        @Test
        @DisplayName("⚑ the pins go out in screen order, with points attached to the right rows")
        void pinsFollowScreenOrder() {
            openDraft();
            connection.respondTo(Verb.EXAM_VERSION_SAVE, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT, 4)));

            session.moveDown(0);

            session.save();

            ExamVersionSave sent =
                    (ExamVersionSave) lastSent(Verb.EXAM_VERSION_SAVE).getPayload();
            assertThat(sent.questions())
                    .extracting(QuestionPin::questionVersionId)
                    .containsExactly(9002L, 9001L, 9003L);
            assertThat(sent.questions()).extracting(QuestionPin::points)
                    .as("points travel with their own question, not with the position")
                    .containsExactly(30, 50, 20);
        }

        /**
         * Blank texts arrive as null, and the record is what makes that true.
         *
         * <p>The session hands both texts over exactly as typed. A mutation round found that a
         * local blank-fold here changed nothing when broken, because {@code ExamVersionSave}'s
         * compact constructor already calls {@code ExamCreateRequest.blankToNull} under §4's
         * inbound rule. The duplicate was deleted rather than defended with a test, and this case
         * now pins the property where it actually lives.
         */
        @Test
        @DisplayName("blank texts travel as null, which is what the contract calls empty")
        void blankTextsBecomeNull() {
            openDraft();
            connection.respondTo(Verb.EXAM_VERSION_SAVE, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT, 4)));
            session.studentText("   ");
            session.teacherText("");

            session.save();

            ExamVersionSave sent =
                    (ExamVersionSave) lastSent(Verb.EXAM_VERSION_SAVE).getPayload();
            assertThat(sent.studentText()).isNull();
            assertThat(sent.teacherText()).isNull();
        }

        /**
         * A save adopts the server's re-read, token included.
         *
         * <p>Without it the second save sends the first save's token and answers CONFLICT, which
         * reads to a teacher as "somebody else edited this" when nobody did.
         */
        @Test
        @DisplayName("⚑ a second save sends the token the first save came back with")
        void tokenAdvancesAfterSaving() {
            openDraft();
            connection.respondTo(Verb.EXAM_VERSION_SAVE, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT, 4)));

            session.save();
            session.save();

            ExamVersionSave second =
                    (ExamVersionSave) lastSent(Verb.EXAM_VERSION_SAVE).getPayload();
            assertThat(second.expectedLockVersion()).isEqualTo(4);
        }

        @Test
        @DisplayName("a create becomes an edit once it has landed, so the next save updates it")
        void createBecomesEditAfterSaving() {
            connection.respondTo(Verb.EXAM_CREATE, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT, 1)));
            session.openNew("11");
            session.name("Algebra midterm");

            session.save();

            assertThat(session.mode())
                    .as("a second press must not make a second exam")
                    .isEqualTo(ExamBuilderSession.Mode.EDIT);
            assertThat(session.examVersionId()).isEqualTo(VERSION_ID);
            assertThat(session.isSaved()).isTrue();
        }

        @Test
        @DisplayName("⚑ a refusal keeps the server's sentence, whatever the code")
        void refusalKeepsTheSentence() {
            openDraft();
            connection.replyError(Verb.EXAM_VERSION_SAVE, ErrorCode.VALIDATION,
                    "Question 11005 is on this paper twice.");

            session.save();

            assertThat(session.saveError()).contains("Question 11005 is on this paper twice.");
            assertThat(session.saveNotice()).isEmpty();
        }

        @Test
        @DisplayName("a CONFLICT with no sentence still says something useful")
        void conflictWithNoSentence() {
            openDraft();
            connection.replyError(Verb.EXAM_VERSION_SAVE, ErrorCode.CONFLICT, "  ");

            session.save();

            assertThat(session.saveError()).contains(ExamBuildCopy.STALE_NOTICE);
        }

        @Test
        @DisplayName("a second save is refused while one is in flight")
        void oneSaveAtATime() {
            openDraft();

            session.save();
            session.save();

            assertThat(countSent(Verb.EXAM_VERSION_SAVE)).isEqualTo(1);
            assertThat(session.isSaving()).isTrue();
        }

        @Test
        @DisplayName("both notices can be dismissed, so they do not reappear on every render")
        void noticesDismiss() {
            openDraft();
            connection.respondTo(Verb.EXAM_VERSION_SAVE, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT, 4)));

            session.save();
            assertThat(session.saveNotice()).contains(ExamBuildCopy.SAVED_NOTICE);
            session.dismissNotice();
            assertThat(session.saveNotice()).isEmpty();

            connection.replyError(Verb.EXAM_VERSION_SAVE, ErrorCode.CONFLICT, "moved");
            session.save();
            assertThat(session.saveError()).isPresent();
            session.dismissSaveError();
            assertThat(session.saveError()).isEmpty();
        }
    }
}
