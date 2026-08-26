package client.features.exambuild;

import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.approval.ApprovalState;
import common.dto.authoring.AutoComposeRequest;
import common.dto.authoring.AutoComposeResult;
import common.dto.authoring.ComposedQuestion;
import common.dto.authoring.ExamComposition;
import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.ExamVersionRequest;
import common.dto.authoring.ExamVersionSave;
import common.dto.authoring.QuestionPin;
import common.dto.authoring.Shortfall;
import common.dto.authoring.TopicQuota;
import common.dto.bank.BankListRequest;
import common.dto.bank.BankPage;
import common.dto.bank.BankQuestionRow;
import common.dto.bank.Difficulty;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import server.features.exambuild.ExamBuildMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

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

    /** Added to a pinned version id to name the newer bank row that supersedes it. */
    private static final long NEWER_ID_BASE = 500_000L;

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

    /**
     * A composed row. When {@code latest} is ahead of {@code pinned} the newest version is a
     * <em>different row</em> and so carries a different id, which is the whole point of
     * {@code latestVersionId}: the badge needs the number, the update action needs the id.
     */
    private static ComposedQuestion question(long versionId, String displayId, int ord,
                                             int points, int pinned, int latest) {
        long latestId = latest == pinned ? versionId : NEWER_ID_BASE + versionId;
        return new ComposedQuestion(versionId, displayId, ord, points, "What is recursion?",
                "Recursion", Difficulty.MEDIUM, false, pinned, latest, latestId);
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

    /** A picker row. Topic is "Recursion" throughout, so the filter has something to match on. */
    private static BankQuestionRow bankRow(String displayId5, long versionId, int versionNo) {
        return new BankQuestionRow(displayId5, "11", "אלגברה", "What is recursion?", "Recursion",
                Difficulty.MEDIUM, versionId, versionNo, false, WHEN);
    }

    private static BankPage bankPage(List<BankQuestionRow> rows, int page, int totalPages) {
        return new BankPage(rows, page, BankListRequest.DEFAULT_PAGE_SIZE, rows.size(), totalPages);
    }

    private void bankHas(BankPage page) {
        connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request, page));
    }

    /** One question as the auto-composer proposes it: pinned at its own latest, by construction. */
    private static ComposedQuestion composed(long versionId, String displayId, int ord, int points) {
        return new ComposedQuestion(versionId, displayId, ord, points, "What is recursion?",
                "Recursion", Difficulty.MEDIUM, false, 1, 1, versionId);
    }

    /** §7.4's even split over two questions. */
    private static List<ComposedQuestion> proposal() {
        return List.of(composed(9201L, "11201", 1, 50), composed(9202L, "11202", 2, 50));
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

    }

    // ===================== The auto tab (E7.13, F3.3) =====================

    @Nested
    @DisplayName("composing automatically (E7.13, F3.3)")
    class Auto {

        private void criteria(int index, ExamBuilderSession.Bucket bucket, int value) {
            session.criterionCount(index, bucket, value);
        }

        @Test
        @DisplayName("the grid starts as one course-wide row, which is the only shape with no topic")
        void startsCourseWide() {
            openDraft();

            assertThat(session.criteria()).singleElement()
                    .extracting(ExamBuilderSession.Criterion::topic).isNull();
        }

        /**
         * The live rule is the server's own, exactly as the points indicator is ⚑.
         *
         * <p>{@code ExamValidator.quotaProblem} is the method the handler refuses with. Asking it
         * here means the sentence on her screen and the sentence that would come back over the
         * wire are one string, and the Generate button cannot offer a request the server will
         * reject.
         */
        @Test
        @DisplayName("⚑ an empty grid is refused by the server's own rule, not a copy of it")
        void anEmptyGridIsRefused() {
            openDraft();
            session.tab(ExamBuilderSession.Tab.AUTO);

            assertThat(session.criteriaProblem())
                    .contains(ExamBuildMessages.QUOTA_EMPTY);
            assertThat(session.canGenerate()).isFalse();
        }

        /**
         * §7.3a's shape rule, live on the form ⚑.
         *
         * <p>A graded course-wide quota beside a topic quota describes crossing pools, which is
         * the one shape §7 cannot report a shortfall for. The refusal must name both legal
         * shapes, and it does, because the wording is {@code ExamBuildMessages}' and this client
         * composes none of it (ruling 4).
         */
        @Test
        @DisplayName("⚑ a graded course-wide row beside a topic row is refused, naming both shapes")
        void theShapeRuleIsLive() {
            openDraft();
            criteria(0, ExamBuilderSession.Bucket.HARD, 3);
            session.addCriterion();
            session.criterionTopic(1, "Recursion");
            criteria(1, ExamBuilderSession.Bucket.ANY, 2);

            assertThat(session.criteriaProblem())
                    .as("§7.3a, and the sentence has to tell her which half to delete")
                    .isPresent();
            assertThat(session.canGenerate()).isFalse();

            // The legal version of what she was reaching for: topic rows plus a course-wide TOTAL.
            criteria(0, ExamBuilderSession.Bucket.HARD, 0);
            criteria(0, ExamBuilderSession.Bucket.ANY, 4);

            assertThat(session.criteriaProblem()).isEmpty();
            assertThat(session.canGenerate()).isTrue();
        }

        @Test
        @DisplayName("Generate sends the criteria for this exam's course, and no seed")
        void generateSendsTheCriteria() {
            openDraft();
            criteria(0, ExamBuilderSession.Bucket.ANY, 4);
            connection.respondTo(Verb.EXAM_AUTO_COMPOSE, request ->
                    Message.ok(request, new AutoComposeResult(true, proposal(), List.of())));

            session.generate();

            AutoComposeRequest sent = (AutoComposeRequest) lastSent(Verb.EXAM_AUTO_COMPOSE)
                    .getPayload();
            assertThat(sent.courseCode()).isEqualTo("11");
            assertThat(sent.seed())
                    .as("§7.5 keeps the seed for tests; a client asking the server to be "
                            + "predictable in front of a class has it backwards")
                    .isNull();
        }

        /**
         * F3.3's "auto-result is editable before save", and what makes it true ⚑.
         *
         * <p>The proposal goes into the same {@code lines} list the manual tab edits, and the
         * screen lands on that tab. There is no second composition anywhere in the session, so
         * "editable" is a property of where the questions went rather than a promise.
         */
        @Test
        @DisplayName("⚑ a feasible answer replaces the paper and drops her where she can edit it")
        void aFeasibleAnswerFillsThePaper() {
            openDraft();
            criteria(0, ExamBuilderSession.Bucket.ANY, 2);
            connection.respondTo(Verb.EXAM_AUTO_COMPOSE, request ->
                    Message.ok(request, new AutoComposeResult(true, proposal(), List.of())));

            session.tab(ExamBuilderSession.Tab.AUTO);
            session.generate();

            assertThat(session.lines()).hasSize(2);
            assertThat(session.tab()).isEqualTo(ExamBuilderSession.Tab.MANUAL);
            assertThat(session.pointsTotal()).isEqualTo(100);
            assertThat(session.composeNotice()).isPresent();
            assertThat(session.shortfalls()).isEmpty();
        }

        /**
         * §7.2: the report is the useful outcome, so nothing else may move ⚑.
         *
         * <p>An infeasible request creates no exam (F3.3) and must not quietly empty the paper
         * she already had. That is the difference between a refusal and a loss.
         */
        @Test
        @DisplayName("⚑ an infeasible answer reports, and leaves the paper exactly as it was")
        void anInfeasibleAnswerChangesNothing() {
            openDraft();
            criteria(0, ExamBuilderSession.Bucket.ANY, 40);
            List<ExamBuilderSession.Line> before = session.lines();
            connection.respondTo(Verb.EXAM_AUTO_COMPOSE, request ->
                    Message.ok(request, new AutoComposeResult(false, List.of(),
                            List.of(new Shortfall("Recursion", Difficulty.HARD, 1, 0)))));

            session.tab(ExamBuilderSession.Tab.AUTO);
            session.generate();

            assertThat(session.shortfalls()).singleElement()
                    .extracting(Shortfall::topic).isEqualTo("Recursion");
            assertThat(session.lines()).isEqualTo(before);
            assertThat(session.tab())
                    .as("she stays on the form that produced the report, beside her own numbers")
                    .isEqualTo(ExamBuilderSession.Tab.AUTO);
            assertThat(session.composeNotice()).isEmpty();
        }

        /**
         * The 4.1 shape again, on the compose ⚑.
         *
         * <p>Two composes in flight and the earlier one answering last would otherwise replace
         * the paper with a proposal for criteria she has already changed.
         */
        @Test
        @DisplayName("⚑ a stale compose answer cannot land under a newer one")
        void staleComposeIsDropped() {
            openDraft();
            criteria(0, ExamBuilderSession.Bucket.ANY, 2);

            session.generate();
            Message first = lastSent(Verb.EXAM_AUTO_COMPOSE);
            criteria(0, ExamBuilderSession.Bucket.ANY, 1);
            session.generate();
            Message second = lastSent(Verb.EXAM_AUTO_COMPOSE);

            connection.deliver(Message.ok(second, new AutoComposeResult(true,
                    List.of(composed(9301L, "11301", 1, 100)), List.of())));
            connection.deliver(Message.ok(first, new AutoComposeResult(true, proposal(), List.of())));

            assertThat(session.lines())
                    .as("the answer to the criteria she last sent, whatever order they arrive in")
                    .hasSize(1);
        }

        @Test
        @DisplayName("⚑ a read-only version has no criteria form to reach")
        void readOnlyHasNoAutoTab() {
            serverHas(ApprovalState.APPROVED, 3);
            session.open(VERSION_ID);

            session.tab(ExamBuilderSession.Tab.AUTO);

            assertThat(session.tab()).isEqualTo(ExamBuilderSession.Tab.MANUAL);
            assertThat(session.canGenerate()).isFalse();
        }

        /**
         * Adding a topic row must not refuse the criteria before she has typed in it ⚑.
         *
         * <p>Found by a cold read. A fresh row carries a blank topic, {@code TopicQuota} folds
         * blank to null, and a null topic <em>is</em> the course-wide bucket, so the request
         * briefly carried two course-wide quotas and {@code quotaProblem} refused it. She saw a
         * sentence telling her to combine two whole-course rows while looking at one labelled
         * row and one empty box, in the middle of T-3.4's own demo path.
         *
         * <p>{@code ExamValidator} already states the rule this broke - "only a row that ASKS for
         * something counts" - and implements it for named rows. The blank row went down the other
         * branch.
         */
        @Test
        @DisplayName("⚑ adding a topic row does not refuse the criteria before she types in it")
        void aBlankTopicRowIsNotASecondCourseWideQuota() {
            openDraft();
            criteria(0, ExamBuilderSession.Bucket.ANY, 10);
            assertThat(session.criteriaProblem()).isEmpty();

            session.addCriterion();

            assertThat(session.criteriaProblem())
                    .as("a row asking for nothing draws on nothing and crosses nothing")
                    .isEmpty();
            assertThat(session.canGenerate())
                    .as("and Compose stays live, because the request is still legal")
                    .isTrue();
        }

        /**
         * The re-pin notice must not outlive the rows it describes ⚑.
         *
         * <p>Found by a cold read. A compose replaces every line on the paper with server-proposed
         * rows, none of them re-pinned, and the notice explaining that updated questions show
         * stale wording stayed above them.
         */
        @Test
        @DisplayName("⚑ composing clears the re-pin notice, because none of those rows are re-pinned")
        void composingClearsTheRepinNotice() {
            openDraft();
            session.updateToLatest(1);
            assertThat(session.hasRepinned()).isTrue();

            criteria(0, ExamBuilderSession.Bucket.ANY, 2);
            connection.respondTo(Verb.EXAM_AUTO_COMPOSE, request ->
                    Message.ok(request, new AutoComposeResult(true, proposal(), List.of())));
            session.generate();

            assertThat(session.hasRepinned())
                    .as("every row on the paper is the server's, and none of them is stale")
                    .isFalse();
        }

        /**
         * The third in-flight answer, and the one a two-of-three reset missed ⚑.
         *
         * <p>Found by a second cold read. {@code resetLoaded} bumped the load and picker counters
         * and not the compose one, and {@code settleCompose} guards on that counter alone. The
         * screen is cached and reused across navigations, so a slow compose on one exam landed on
         * the next exam she opened: paper cleared, filled with the other exam's questions, and a
         * success toast over the top of it.
         */
        @Test
        @DisplayName("⚑ a compose in flight cannot land on the exam she opened next")
        void aStaleComposeCannotLandOnAnotherExam() {
            openDraft();
            criteria(0, ExamBuilderSession.Bucket.ANY, 2);
            session.generate();
            Message inFlight = lastSent(Verb.EXAM_AUTO_COMPOSE);

            connection.respondTo(Verb.EXAM_VERSION_GET, request ->
                    Message.ok(request, other(7002L, "Calculus final")));
            session.open(7002L);
            List<ExamBuilderSession.Line> calculusPaper = session.lines();

            connection.deliver(Message.ok(inFlight,
                    new AutoComposeResult(true, proposal(), List.of())));

            assertThat(session.lines())
                    .as("the Calculus paper is untouched by a proposal for the Algebra exam")
                    .isEqualTo(calculusPaper);
            assertThat(session.composeNotice())
                    .as("and nothing congratulates her on composing an exam she did not compose")
                    .isEmpty();
        }

        /**
         * The auto tab must not survive into a version that cannot show it ⚑.
         *
         * <p>Found by a second cold read, and it strands her: the manual pane is hidden because
         * the tab is AUTO, and the tab switch is hidden because a read-only version is not
         * editable. She opened the version to read the paper and the paper is not on screen, with
         * no control that brings it back. The existing read-only case opens the finished version
         * first, so it structurally cannot see this ordering.
         */
        @Test
        @DisplayName("⚑ opening a read-only version from the auto tab still shows its paper")
        void theAutoTabDoesNotStrandAReadOnlyVersion() {
            openDraft();
            session.tab(ExamBuilderSession.Tab.AUTO);
            assertThat(session.tab()).isEqualTo(ExamBuilderSession.Tab.AUTO);

            serverHas(ApprovalState.APPROVED, 3);
            session.open(VERSION_ID);

            assertThat(session.tab())
                    .as("the manual pane is the only one a read-only version can show")
                    .isEqualTo(ExamBuilderSession.Tab.MANUAL);
        }

        @Test
        @DisplayName("⚑ criteria built for one exam do not follow her into another")
        void criteriaDoNotFollowHerToAnotherExam() {
            openDraft();
            session.addCriterion();
            session.criterionTopic(1, "Recursion");
            criteria(1, ExamBuilderSession.Bucket.ANY, 3);

            connection.respondTo(Verb.EXAM_VERSION_GET, request ->
                    Message.ok(request, other(7002L, "Calculus final")));
            session.open(7002L);

            assertThat(session.criteria())
                    .as("§7.2 promises a number she can reproduce in HER bank; a topic from the "
                            + "exam she left is not in it")
                    .singleElement()
                    .extracting(ExamBuilderSession.Criterion::topic).isNull();
        }

        /**
         * The report must not outlive the criteria it answered ⚑.
         *
         * <p>Its heading says "the bank cannot satisfy these criteria" and points at the grid
         * above it. T-3.5 and T-3.6 are two shots with a criteria edit between them, so a stale
         * report is a red block naming three while the box beside it reads two.
         */
        @Test
        @DisplayName("⚑ editing the criteria retires the report that answered the old ones")
        void editingTheCriteriaRetiresTheReport() {
            openDraft();
            criteria(0, ExamBuilderSession.Bucket.ANY, 40);
            connection.respondTo(Verb.EXAM_AUTO_COMPOSE, request ->
                    Message.ok(request, new AutoComposeResult(false, List.of(),
                            List.of(new Shortfall("Recursion", Difficulty.HARD, 3, 2)))));
            session.generate();
            assertThat(session.shortfalls()).hasSize(1);

            criteria(0, ExamBuilderSession.Bucket.ANY, 2);

            assertThat(session.shortfalls())
                    .as("she has changed the question, so the old answer is not an answer")
                    .isEmpty();
        }

        /**
         * A row with counts and no topic is refused, and never sent ⚑.
         *
         * <p>The first fix for the blank-row defect skipped rows asking for nothing, which covers
         * typing the topic first and not typing a count first. This is the ordering it missed,
         * and the quiet variant is the dangerous one: with the course-wide row still at zero, an
         * unnamed row used to pass every rule as a GRADED COURSE-WIDE quota and compose from the
         * whole course while she believed she had named a topic.
         */
        @Test
        @DisplayName("⚑ a counted row with no topic is refused, not silently made course-wide")
        void aCountedRowWithNoTopicIsRefused() {
            openDraft();
            session.addCriterion();
            criteria(1, ExamBuilderSession.Bucket.HARD, 3);

            assertThat(session.criteriaProblem()).contains(ExamBuildCopy.TOPIC_REQUIRED);
            assertThat(session.canGenerate()).isFalse();

            session.criterionTopic(1, "Recursion");

            assertThat(session.criteriaProblem())
                    .as("naming it is all that was missing")
                    .isEmpty();
            assertThat(session.canGenerate()).isTrue();
        }

        /**
         * Why the refusal upstream is the only defence, recorded as a test ⚑.
         *
         * <p>A plant found this: reverting {@code toQuota} to "blank topic means course-wide"
         * changed nothing, because {@code TopicQuota}'s own constructor folds blank to null
         * anyway. An unnamed topic row is <b>not expressible</b> on this wire, and that record is
         * the lead's, not Member A's.
         *
         * <p>So this pins the constraint rather than a guarantee nobody can give here: whatever
         * the client passes, a blank topic arrives course-wide. That is exactly why
         * {@code criteriaProblem} refuses before such a row can be sent, and it is what a future
         * reader needs to know before deciding the guard is redundant.
         */
        @Test
        @DisplayName("⚑ a blank topic is course-wide on the wire whatever the client intends")
        void aBlankTopicIsUnavoidablyCourseWide() {
            ExamBuilderSession.Criterion blank = new ExamBuilderSession.Criterion("", 0, 0, 3, 0);

            assertThat(blank.toQuota(false).isCourseWide())
                    .as("TopicQuota folds blank to null; the client cannot say otherwise")
                    .isTrue();
            assertThat(blank.isUnnamed())
                    .as("which is why the row is refused before it is ever built into a request")
                    .isTrue();
            assertThat(new ExamBuilderSession.Criterion("Recursion", 0, 0, 3, 0).toQuota(false)
                    .isCourseWide())
                    .as("a named row is a topic row, which is the case that does work")
                    .isFalse();
        }

        @Test
        @DisplayName("a topic row asking for nothing is not sent at all")
        void anEmptyTopicRowIsNotSent() {
            openDraft();
            criteria(0, ExamBuilderSession.Bucket.ANY, 4);
            session.addCriterion();
            connection.respondTo(Verb.EXAM_AUTO_COMPOSE, request ->
                    Message.ok(request, new AutoComposeResult(true, proposal(), List.of())));

            session.generate();

            AutoComposeRequest sent = (AutoComposeRequest) lastSent(Verb.EXAM_AUTO_COMPOSE)
                    .getPayload();
            assertThat(sent.quotas())
                    .as("the request equals what she asked for, with no row asking for nothing")
                    .singleElement()
                    .extracting(TopicQuota::topic).isNull();
        }

        @Test
        @DisplayName("the picker's own retry really asks the bank again")
        void pickerRetryAsksAgain() {
            openDraft();
            bankHas(bankPage(List.of(bankRow("11007", 9107L, 1)), 0, 1));
            session.openPicker();

            session.retryPicker();

            assertThat(countSent(Verb.BANK_LIST))
                    .as("the control the copy offers beside a failed load actually reloads")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a compose that never answers is named, not left spinning")
        void composeTransportFailureIsNamed() {
            openDraft();
            criteria(0, ExamBuilderSession.Bucket.ANY, 2);
            session.generate();

            connection.deliver(Message.ok(lastSent(Verb.EXAM_AUTO_COMPOSE), "not a result"));

            assertThat(session.composeError()).contains(ExamBuildCopy.COMPOSE_FAILED);
            assertThat(session.isComposing()).isFalse();
        }

        @Test
        @DisplayName("the composed notice is dismissed once, which is what the screen does with it")
        void theComposeNoticeIsDismissed() {
            openDraft();
            criteria(0, ExamBuilderSession.Bucket.ANY, 2);
            connection.respondTo(Verb.EXAM_AUTO_COMPOSE, request ->
                    Message.ok(request, new AutoComposeResult(true, proposal(), List.of())));
            session.generate();
            assertThat(session.composeNotice()).isPresent();

            session.dismissComposeNotice();

            assertThat(session.composeNotice()).isEmpty();
        }

        @Test
        @DisplayName("every bucket on a row can be set, not just the two the other cases use")
        void everyBucketIsSettable() {
            openDraft();
            session.addCriterion();
            session.criterionTopic(1, "Recursion");
            criteria(1, ExamBuilderSession.Bucket.EASY, 1);
            criteria(1, ExamBuilderSession.Bucket.MEDIUM, 2);
            criteria(1, ExamBuilderSession.Bucket.HARD, 3);
            criteria(1, ExamBuilderSession.Bucket.ANY, 4);

            ExamBuilderSession.Criterion row = session.criteria().get(1);
            assertThat(row.easy()).isEqualTo(1);
            assertThat(row.medium()).isEqualTo(2);
            assertThat(row.hard()).isEqualTo(3);
            assertThat(row.any()).isEqualTo(4);
        }

        @Test
        @DisplayName("a topic row can be added and removed; the course-wide row cannot be removed")
        void theCourseWideRowIsFixed() {
            openDraft();
            session.addCriterion();
            assertThat(session.criteria()).hasSize(2);

            session.removeCriterion(1);
            assertThat(session.criteria()).hasSize(1);

            session.removeCriterion(0);
            assertThat(session.criteria())
                    .as("§7.3a is a rule about that row, so there is always exactly one of it")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a compose that fails outright says so rather than reporting an empty bank")
        void composeFailureIsNamed() {
            openDraft();
            criteria(0, ExamBuilderSession.Bucket.ANY, 2);
            connection.respondTo(Verb.EXAM_AUTO_COMPOSE, request ->
                    Message.error(request, ErrorCode.INTERNAL, ""));

            session.generate();

            assertThat(session.composeError()).contains(ExamBuildCopy.COMPOSE_FAILED);
            assertThat(session.shortfalls())
                    .as("no shortfalls means the bank was fine, which is not what happened")
                    .isEmpty();
        }
    }

    // ===================== The update action (E7.14) ======================

    @Nested
    @DisplayName("re-pinning a question to the bank's newest version (E7.14)")
    class UpdateToLatest {

        /**
         * The other half of E7.7 ⚑.
         *
         * <p>The fixture's second question is pinned at v2 while the bank holds v4, which is the
         * badge's own state. What the action must move is the <b>id</b>: a version number says
         * something newer exists and never says what to pin, which is why this could not be
         * written until {@code ComposedQuestion.latestVersionId} landed.
         */
        @Test
        @DisplayName("⚑ the pin moves to the newer version's id, and the badge goes with it")
        void thePinMoves() {
            openDraft();
            ExamBuilderSession.Line before = session.lines().get(1);
            assertThat(before.hasNewerVersion()).isTrue();

            assertThat(session.updateToLatest(1)).isTrue();

            ExamBuilderSession.Line after = session.lines().get(1);
            assertThat(after.questionVersionId())
                    .as("the id, not the number: QuestionPin keys on this and nothing else")
                    .isEqualTo(before.latestVersionId());
            assertThat(after.pinnedVersionNo()).isEqualTo(before.latestVersionNo());
            assertThat(after.hasNewerVersion())
                    .as("nothing is newer than the newest, so the badge has nothing to say")
                    .isFalse();
        }

        @Test
        @DisplayName("points and position survive, because only the pin was asked to move")
        void everythingElseSurvives() {
            openDraft();
            ExamBuilderSession.Line before = session.lines().get(1);

            session.updateToLatest(1);

            ExamBuilderSession.Line after = session.lines().get(1);
            assertThat(after.points()).isEqualTo(before.points());
            assertThat(after.displayId5()).isEqualTo(before.displayId5());
            assertThat(session.lines()).hasSize(3);
            assertThat(session.pointsTotal())
                    .as("a re-pin is not a repoint; the live total cannot move under her")
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("a question the bank has not moved past is left exactly alone")
        void anUpToDateQuestionIsUntouched() {
            openDraft();

            assertThat(session.updateToLatest(0))
                    .as("writing the pin it already holds would dirty the paper for nothing")
                    .isFalse();
            assertThat(session.hasRepinned()).isFalse();
        }

        @Test
        @DisplayName("⚑ a read-only version refuses, like every other edit on it")
        void readOnlyRefuses() {
            serverHas(ApprovalState.APPROVED, 3);
            session.open(VERSION_ID);
            long pinnedBefore = session.lines().get(1).questionVersionId();

            assertThat(session.updateToLatest(1)).isFalse();
            assertThat(session.lines().get(1).questionVersionId()).isEqualTo(pinnedBefore);
        }

        @Test
        @DisplayName("an index off the end is refused rather than thrown")
        void outOfRangeIsRefused() {
            openDraft();

            assertThat(session.updateToLatest(99)).isFalse();
            assertThat(session.updateToLatest(-1)).isFalse();
        }

        /**
         * The re-pinned row keeps the old wording, and the notice is what makes that honest ⚑.
         *
         * <p>This screen has never been sent the new version's text: the wire carries the newer
         * version's id and number and none of its content. So the row is <em>behind</em> rather
         * than wrong, and the difference between behind and lying is that somebody said so.
         */
        @Test
        @DisplayName("⚑ the notice stands from the re-pin until the server's re-read replaces it")
        void theNoticeLastsExactlyUntilTheSave() {
            openDraft();
            assertThat(session.hasRepinned()).isFalse();

            session.updateToLatest(1);
            assertThat(session.hasRepinned()).isTrue();
            assertThat(session.lines().get(1).text())
                    .as("still the old version's stem, which is why the notice exists")
                    .isEqualTo("What is recursion?");

            connection.respondTo(Verb.EXAM_VERSION_SAVE, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT, 4)));
            session.save();

            assertThat(session.hasRepinned())
                    .as("the save's answer is the server's own re-read, so the promise is kept")
                    .isFalse();
        }
    }

    // ===================== The bank picker (E7.12) ========================

    @Nested
    @DisplayName("the bank picker (E7.12, §3)")
    class Picker {

        @Test
        @DisplayName("opening it asks for THIS exam's course and nothing wider")
        void asksForTheExamsCourse() {
            openDraft();
            bankHas(bankPage(List.of(bankRow("11007", 9107L, 1)), 0, 1));

            session.openPicker();

            assertThat(session.isPickerOpen()).isTrue();
            assertThat(lastSent(Verb.BANK_LIST).getPayload())
                    .asInstanceOf(type(BankListRequest.class))
                    .extracting(BankListRequest::courseCode)
                    .as("§5.2 refuses a question from another course on save, so offering one "
                            + "here would be offering a click that cannot work")
                    .isEqualTo("11");
            assertThat(session.pickerState()).isEqualTo(AsyncViewState.READY);
            assertThat(session.pickerRows()).hasSize(1);
        }

        @Test
        @DisplayName("a picked row lands on the paper pinned at the version she was shown")
        void addPinsTheVersionShown() {
            openDraft();
            bankHas(bankPage(List.of(bankRow("11007", 9107L, 3)), 0, 1));
            session.openPicker();

            assertThat(session.addFromBank(session.pickerRows().get(0))).isTrue();

            ExamBuilderSession.Line added = session.lines().get(3);
            assertThat(added.questionVersionId())
                    .as("latestVersionId is the pin, which is what makes E7.7's badge a drift "
                            + "detector rather than a coincidence afterwards")
                    .isEqualTo(9107L);
            assertThat(added.points()).isEqualTo(QuestionPin.MIN_POINTS);
            assertThat(added.hasNewerVersion())
                    .as("pinned and latest are equal on the way in, so a question she just "
                            + "added never arrives already carrying a badge")
                    .isFalse();
        }

        /**
         * T-3.9 and §5.2, on the click rather than on the save ⚑.
         *
         * <p>The comparison is {@code displayId5} and never {@code questionVersionId}. A bank row
         * offering 11001 at version 4 while the paper pins 11001 at version 1 is the same
         * question twice, and version ids would call them different.
         */
        @Test
        @DisplayName("⚑ the same question through a NEWER version is still the same question")
        void aDifferentVersionIsStillADuplicate() {
            openDraft();   // pins 11001 at version 1
            bankHas(bankPage(List.of(bankRow("11001", 9999L, 4)), 0, 1));
            session.openPicker();

            BankQuestionRow row = session.pickerRows().get(0);
            assertThat(session.isOnPaper(row)).isTrue();
            assertThat(session.addFromBank(row))
                    .as("uq_exam_version_questions_question would refuse this on save, ten "
                            + "minutes after the click that caused it")
                    .isFalse();
            assertThat(session.lines()).hasSize(3);
        }

        @Test
        @DisplayName("the filter matches the id, the stem and the topic, and nothing else")
        void filterMatchesTheThreeVisibleThings() {
            openDraft();
            bankHas(bankPage(List.of(
                    bankRow("11007", 9107L, 1),
                    bankRow("11008", 9108L, 1)), 0, 1));
            session.openPicker();

            session.pickerSearch("11008");
            assertThat(session.pickerRows()).singleElement()
                    .extracting(BankQuestionRow::displayId5).isEqualTo("11008");

            session.pickerSearch("RECURSION");
            assertThat(session.pickerRows())
                    .as("the topic matches case-insensitively, like everything else she types")
                    .hasSize(2);

            session.pickerSearch("nothing like this");
            assertThat(session.pickerRows()).isEmpty();
        }

        @Test
        @DisplayName("a bank that spans pages is gathered into one list")
        void pagesAreGathered() {
            openDraft();
            connection.respondTo(Verb.BANK_LIST, request -> {
                BankListRequest asked = (BankListRequest) request.getPayload();
                return Message.ok(request, bankPage(
                        List.of(bankRow("1100" + asked.page(), 9200L + asked.page(), 1)),
                        asked.page(), 3));
            });

            session.openPicker();

            assertThat(session.pickerRows()).hasSize(3);
            assertThat(countSent(Verb.BANK_LIST)).isEqualTo(3);
        }

        /**
         * A page that is not the page that was asked for is a failed load ⚑.
         *
         * <p>Found while writing {@link #filterMatchesTheThreeVisibleThings}: a stub answering
         * every request with page 0 while claiming two pages exist made the picker show page 0
         * twice and page 1 never, with a full-looking list, no error and nothing failing. The
         * loop asked for page 1 and appended whatever came back.
         */
        @Test
        @DisplayName("⚑ a page the server did not send is refused, not appended")
        void aPageThatIsNotTheOneAskedForIsRefused() {
            openDraft();
            // Claims two pages and answers page 0 to everything, which is the shape.
            bankHas(bankPage(List.of(bankRow("11007", 9107L, 1)), 0, 2));

            session.openPicker();

            assertThat(session.pickerState()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.pickerRows())
                    .as("half a bank shown as if it were all of it is worse than a named failure")
                    .isEmpty();
        }

        @Test
        @DisplayName("a bank that cannot be read says so instead of showing an empty list")
        void loadFailureIsNamed() {
            openDraft();
            connection.respondTo(Verb.BANK_LIST, request ->
                    Message.error(request, ErrorCode.INTERNAL, "boom"));

            session.openPicker();

            assertThat(session.pickerState()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.pickerError()).contains(ExamBuildCopy.PICKER_LOAD_FAILED);
            assertThat(session.pickerRows())
                    .as("an empty list would read as an empty bank, which is a different fact")
                    .isEmpty();
        }

        /**
         * The 4.1 shape, on the picker's own load ⚑.
         *
         * <p>The same defect class the exam load carries a generation counter for. Closing the
         * picker and opening it again while a page is in flight must not append that page to the
         * new load: the list would then hold rows fetched for a paper she has moved on from, and
         * {@code isOnPaper} would be answering about the wrong one.
         */
        @Test
        @DisplayName("⚑ a page in flight cannot land in a picker that was closed and reopened")
        void staleBankPageIsDropped() {
            openDraft();
            session.openPicker();
            Message first = lastSent(Verb.BANK_LIST);

            session.closePicker();
            session.openPicker();
            Message second = lastSent(Verb.BANK_LIST);

            connection.deliver(Message.ok(second, bankPage(
                    List.of(bankRow("11009", 9109L, 1)), 0, 1)));
            connection.deliver(Message.ok(first, bankPage(
                    List.of(bankRow("11007", 9107L, 1)), 0, 1)));

            assertThat(session.pickerRows())
                    .singleElement()
                    .extracting(BankQuestionRow::displayId5)
                    .as("only the load she is actually waiting on may fill the list")
                    .isEqualTo("11009");
        }

        @Test
        @DisplayName("⚑ a version nothing can be changed on offers no picker at all")
        void readOnlyCannotPick() {
            serverHas(ApprovalState.APPROVED, 3);
            session.open(VERSION_ID);

            session.openPicker();

            assertThat(session.isPickerOpen()).isFalse();
            assertThat(session.canAddFromBank()).isFalse();
            assertThat(countSent(Verb.BANK_LIST))
                    .as("a read-only screen has no reason to read the bank")
                    .isZero();
        }

        /**
         * The picker belongs to the version that was open ⚑.
         *
         * <p>Its rows are that exam's course and {@code isOnPaper} reads that exam's paper. Left
         * standing across an open(), it would offer one course's bank against another's paper.
         */
        @Test
        @DisplayName("⚑ opening another version takes the picker down with the old paper")
        void openingAnotherVersionClosesThePicker() {
            openDraft();
            bankHas(bankPage(List.of(bankRow("11007", 9107L, 1)), 0, 1));
            session.openPicker();
            assertThat(session.isPickerOpen()).isTrue();

            connection.respondTo(Verb.EXAM_VERSION_GET, request ->
                    Message.ok(request, other(7002L, "Calculus final")));
            session.open(7002L);

            assertThat(session.isPickerOpen()).isFalse();
            assertThat(session.pickerRows()).isEmpty();
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
