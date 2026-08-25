package client.features.bank;

import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.events.PushEventBridge;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.auth.CourseRef;
import common.dto.bank.BankListRequest;
import common.dto.bank.BankPage;
import common.dto.bank.BankQuestionRow;
import common.dto.bank.BlockingExam;
import common.dto.bank.DeleteOutcome;
import common.dto.bank.Difficulty;
import common.dto.bank.QuestionDeleteRequest;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionImage;
import common.dto.bank.QuestionImageRequest;
import common.dto.bank.QuestionRequest;
import common.dto.bank.QuestionVersionDetail;
import common.dto.bank.VersionHistory;
import common.dto.lock.EntityRef;
import common.dto.lock.LockChange;
import common.dto.lock.LockHolder;
import common.dto.lock.LockRequest;
import common.dto.lock.LocksSnapshot;
import common.dto.lock.LocksSnapshotRequest;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import server.features.bank.QuestionLockKey;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BankSession} — E6's browse behaviour, without a JavaFX toolkit.
 *
 * <p>The session talks to a {@link FakeClientConnection} through a real
 * {@link RequestDispatcher}, and the FX hop is a {@link DirectFxThreadPoster}, so every
 * transition settles synchronously (TEAM_SPLIT section 3.2).
 *
 * <p>The fixture is Dana Cohen's chair: Algebra and Calculus, four questions across them, one of
 * them illustrated, and question 11005 the one three exams are built from (T-2.7).
 *
 * <h2>The nested class that matters</h2>
 *
 * <p>{@link LateAnswers}. The fake answers synchronously by default, which is the wrong shape
 * for testing what happens when answers arrive in an order the user's clicking did not. Those
 * tests register <b>no</b> responder, read the requests out of {@code sentMessages()} and
 * deliver correlated answers by hand, in the order the test chooses. That is the only way to
 * reach the defect and it is why the session carries a generation counter at all.
 */
class BankSessionTest {

    private static final Instant SPRING = Instant.parse("2026-03-10T07:00:00Z");
    private static final Instant SUMMER = Instant.parse("2026-08-07T06:00:00Z");

    /**
     * A non-breaking space, built from its code point rather than typed.
     *
     * <p>An invisible character in a source literal is fragile in exactly the way this test is
     * about: it survives no copy-paste reliably, and a tool that "helpfully" normalises it turns
     * the assertion into one about an ordinary space that passes for the wrong reason. This test
     * has already been wrong twice about which character it was passing.
     */
    private static final String NBSP = Character.toString(0x00A0);

    private static final CourseRef ALGEBRA = new CourseRef("11", "אלגברה");
    private static final CourseRef CALCULUS = new CourseRef("12", "חדו\"א");

    /** Dana Cohen, whose chair this fixture is. Her own id, so a row of hers can say "you". */
    private static final long DANA = 501L;

    /** A colleague, so a row of his says his name. */
    private static final long RON = 502L;

    private FakeClientConnection connection;
    private BankSession session;
    private ClientEventBus eventBus;
    private int renders;

    @BeforeEach
    void setUp() {
        connection = new FakeClientConnection();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        eventBus = new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster());
        dispatcher.setPushListener(new PushEventBridge(eventBus));
        session = new BankSession(dispatcher, new DirectFxThreadPoster(),
                List.of(ALGEBRA, CALCULUS), eventBus, DANA).onChange(() -> renders++);
    }

    /** A session with no courses and its own wire, for the cases that need a second chair. */
    private static BankSession sessionOn(RequestDispatcher dispatcher) {
        return new BankSession(dispatcher, new DirectFxThreadPoster(), List.of(),
                new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster()), RON);
    }

    // ===================== Fixture ========================================

    private static BankQuestionRow row(String id, String course, String courseName, String text,
                                       String topic, Difficulty difficulty, boolean hasImage) {
        return new BankQuestionRow(id, course, courseName, text, topic, difficulty, 1, hasImage,
                SPRING);
    }

    private static final BankQuestionRow ROW_LINEAR =
            row("11001", "11", "אלגברה", "Solve the linear equation", "Equations",
                    Difficulty.EASY, false);
    private static final BankQuestionRow ROW_QUADRATIC =
            row("11002", "11", "אלגברה", "Factor the quadratic", "Equations",
                    Difficulty.MEDIUM, false);
    private static final BankQuestionRow ROW_GEOMETRY =
            row("11005", "11", "אלגברה", "Read the diagram", "Geometry", Difficulty.HARD, true);
    private static final BankQuestionRow ROW_LIMIT =
            row("12001", "12", "חדו\"א", "Evaluate the limit", null, Difficulty.HARD, false);

    private static QuestionDetail detail(String id, boolean hasImage, int versionNo, int latest) {
        return new QuestionDetail(id, "11", "אלגברה", versionNo, latest, "Read the diagram",
                List.of("One", "Two", "Three", "Four"), 2, "Geometry", Difficulty.HARD, hasImage,
                "דנה כהן", SPRING);
    }

    private static QuestionVersionDetail version(int no, String text, int correct, String topic,
                                                 Difficulty difficulty, boolean hasImage) {
        return new QuestionVersionDetail(no, text, List.of("One", "Two", "Three", "Four"),
                correct, topic, difficulty, hasImage, "דנה כהן", no == 1 ? SPRING : SUMMER);
    }

    private BankPage page(List<BankQuestionRow> rows, int pageNo, long total, int totalPages) {
        return new BankPage(rows, pageNo, BankListRequest.DEFAULT_PAGE_SIZE, total, totalPages);
    }

    private void serverHasTheBank() {
        connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request,
                page(List.of(ROW_LINEAR, ROW_QUADRATIC, ROW_GEOMETRY, ROW_LIMIT), 0, 4, 1)));
    }

    private BankListRequest lastListRequest() {
        return connection.sentMessages().stream()
                .filter(message -> message.getVerb() == Verb.BANK_LIST)
                .reduce((first, second) -> second)
                .map(message -> (BankListRequest) message.getPayload())
                .orElseThrow();
    }

    // ===================== Loading ========================================

    @Nested
    @DisplayName("loading the list")
    class Loading {

        @Test
        @DisplayName("opens on the first page, unfiltered, at the contract's default size")
        void opensUnfiltered() {
            serverHasTheBank();

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.rows())
                    .containsExactly(ROW_LINEAR, ROW_QUADRATIC, ROW_GEOMETRY, ROW_LIMIT);
            BankListRequest sent = lastListRequest();
            assertThat(sent.isUnfiltered()).isTrue();
            assertThat(sent.page()).isZero();
            assertThat(sent.size()).isEqualTo(BankListRequest.DEFAULT_PAGE_SIZE);
        }

        @Test
        @DisplayName("an empty bank and an empty filter result are different panels")
        void twoEmptyPanels() {
            connection.replyOk(Verb.BANK_LIST, page(List.of(), 0, 0, 0));

            session.load();
            assertThat(session.state()).isEqualTo(AsyncViewState.EMPTY);
            assertThat(session.emptyPanel()).contains(BankCopy.NO_QUESTIONS);

            session.setSearch("nothing matches this");
            assertThat(session.emptyPanel())
                    .as("a teacher told her bank is empty when it is merely filtered will go and "
                            + "write a question she already has")
                    .contains(BankCopy.NO_MATCHES);
        }

        @Test
        @DisplayName("a failed list says so and holds no stale rows")
        void failureClearsRows() {
            serverHasTheBank();
            session.load();

            connection.replyError(Verb.BANK_LIST, ErrorCode.INTERNAL, "boom");
            session.reload();

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.error()).isEqualTo(BankCopy.LIST_FAILED);
            assertThat(session.rows())
                    .as("rows from before the failure would be a screen describing a bank the "
                            + "server just refused to describe")
                    .isEmpty();
        }

        @Test
        @DisplayName("an OK carrying the wrong payload type is a failure, not a crash")
        void wrongPayloadType() {
            connection.replyOk(Verb.BANK_LIST, "not a page");

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
        }
    }

    // ===================== Filters ========================================

    @Nested
    @DisplayName("filters, all of which travel")
    class Filters {

        @BeforeEach
        void serverAnswers() {
            serverHasTheBank();
            session.load();
        }

        @Test
        @DisplayName("the course filter travels and resets to the first page")
        void courseTravels() {
            session.selectCourse("12");

            assertThat(lastListRequest().courseCode()).isEqualTo("12");
            assertThat(lastListRequest().page()).isZero();
            assertThat(session.isFiltered()).isTrue();
        }

        @Test
        @DisplayName("the difficulty filter travels")
        void difficultyTravels() {
            session.selectDifficulty(Difficulty.HARD);

            assertThat(lastListRequest().difficulty()).isEqualTo(Difficulty.HARD);
        }

        @Test
        @DisplayName("the topic filter travels, so the picker will not need a new verb to filter")
        void topicTravels() {
            session.selectTopic("Geometry");

            assertThat(lastListRequest().topic()).isEqualTo("Geometry");
        }

        @Test
        @DisplayName("the search travels, stripped")
        void searchTravels() {
            session.setSearch("   quadratic  ");

            assertThat(lastListRequest().search()).isEqualTo("quadratic");
            assertThat(session.search()).isEqualTo("quadratic");
        }

        @Test
        @DisplayName("re-setting a filter to what it already is costs no round trip")
        void idempotent() {
            session.selectCourse("11");
            connection.clearSent();

            session.selectCourse("11");
            session.setSearch("");
            session.selectDifficulty(null);

            assertThat(connection.sentCount()).isZero();
        }

        @Test
        @DisplayName("blank and null mean the same thing on every filter")
        void blankIsNull() {
            session.selectCourse("   ");
            session.selectTopic("");

            assertThat(session.selectedCourse()).isNull();
            assertThat(session.selectedTopic()).isNull();
            assertThat(session.isFiltered()).isFalse();
        }

        @Test
        @DisplayName("clearing puts every filter back in one round trip, and is a no-op when clean")
        void clearing() {
            session.selectCourse("12");
            session.selectDifficulty(Difficulty.HARD);
            session.setSearch("limit");
            connection.clearSent();

            session.clearFilters();

            // Counted by verb rather than by message. What this asserts is that clearing three
            // filters is ONE list request and not three; a raw message count also swept up
            // whatever else a settled page sends, which since E6.14 includes the locks snapshot
            // behind the Editing column. Narrowing it to BANK_LIST keeps the property and stops
            // the test failing on traffic it was never about.
            assertThat(connection.sentMessages()).extracting(Message::getVerb)
                    .filteredOn(verb -> verb == Verb.BANK_LIST)
                    .hasSize(1);
            assertThat(lastListRequest().isUnfiltered()).isTrue();
            assertThat(session.isFiltered()).isFalse();

            connection.clearSent();
            session.clearFilters();
            assertThat(connection.sentCount()).isZero();
        }
    }

    // ===================== Paging =========================================

    @Nested
    @DisplayName("paging")
    class Paging {

        @Test
        @DisplayName("next and previous move within the bounds the server reported")
        void bounds() {
            connection.respondTo(Verb.BANK_LIST, request -> {
                BankListRequest asked = (BankListRequest) request.getPayload();
                return Message.ok(request,
                        page(List.of(ROW_LINEAR), asked.page(), 90, 3));
            });
            session.load();

            assertThat(session.hasPreviousPage()).isFalse();
            assertThat(session.hasNextPage()).isTrue();

            session.nextPage();
            assertThat(session.page()).isEqualTo(1);
            session.nextPage();
            assertThat(session.page()).isEqualTo(2);
            assertThat(session.hasNextPage()).isFalse();

            connection.clearSent();
            session.nextPage();
            assertThat(connection.sentCount())
                    .as("the end of the list is not a request")
                    .isZero();

            session.previousPage();
            assertThat(session.page()).isEqualTo(1);
        }

        @Test
        @DisplayName("a single page offers no paging at all")
        void singlePage() {
            serverHasTheBank();
            session.load();

            assertThat(session.hasNextPage()).isFalse();
            assertThat(session.hasPreviousPage()).isFalse();
        }
    }

    // ===================== Selection and the illustration =================

    @Nested
    @DisplayName("opening a question")
    class Selecting {

        @BeforeEach
        void serverAnswers() {
            serverHasTheBank();
            session.load();
        }

        @Test
        @DisplayName("selecting sends QUESTION_GET and shows what came back")
        void opensTheQuestion() {
            connection.replyOk(Verb.QUESTION_GET, detail("11001", false, 1, 1));

            session.select("11001");

            assertThat(session.detailState()).isEqualTo(AsyncViewState.READY);
            assertThat(session.detail().displayId5()).isEqualTo("11001");
            assertThat(connection.lastSent().getPayload())
                    .isEqualTo(new QuestionRequest("11001"));
        }

        @Test
        @DisplayName("an illustrated question fetches its picture, addressed by version")
        void fetchesTheImage() {
            connection.replyOk(Verb.QUESTION_GET, detail("11005", true, 2, 3));
            connection.replyOk(Verb.QUESTION_IMAGE_GET,
                    new QuestionImage("11005", 2, "image/png", new byte[] {1, 2, 3}));

            session.select("11005");

            assertThat(session.imageState()).isEqualTo(AsyncViewState.READY);
            assertThat(session.image()).containsExactly(1, 2, 3);
            assertThat(connection.lastSent().getPayload())
                    .as("versions are immutable, so an image belongs to the version that is on "
                            + "screen and not to the question")
                    .isEqualTo(new QuestionImageRequest("11005", 2));
        }

        @Test
        @DisplayName("a question with no illustration costs no second round trip")
        void noImageNoRequest() {
            connection.replyOk(Verb.QUESTION_GET, detail("11001", false, 1, 1));

            session.select("11001");

            assertThat(connection.sentMessages()).extracting(Message::getVerb)
                    .doesNotContain(Verb.QUESTION_IMAGE_GET);
        }

        @Test
        @DisplayName("a picture that fails leaves the question readable and says so separately")
        void imageFailureIsItsOwnSentence() {
            connection.replyOk(Verb.QUESTION_GET, detail("11005", true, 1, 1));
            connection.replyError(Verb.QUESTION_IMAGE_GET, ErrorCode.INTERNAL, "boom");

            session.select("11005");

            assertThat(session.detailState())
                    .as("telling her the question failed when only its picture did would send "
                            + "her away from a question she can use")
                    .isEqualTo(AsyncViewState.READY);
            assertThat(session.imageState()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.imageError()).isEqualTo(BankCopy.IMAGE_FAILED);
        }

        @Test
        @DisplayName("selecting null clears the pane")
        void clearing() {
            connection.replyOk(Verb.QUESTION_GET, detail("11001", false, 1, 1));
            session.select("11001");

            session.select(null);

            assertThat(session.selectedId()).isNull();
            assertThat(session.detail()).isNull();
            assertThat(session.detailState()).isEqualTo(AsyncViewState.IDLE);
        }

        @Test
        @DisplayName("a selection the new page no longer holds stops being the selection")
        void selectionSurvivesOnlyWhileItIsOnScreen() {
            connection.replyOk(Verb.QUESTION_GET, detail("11005", false, 1, 1));
            session.select("11005");
            assertThat(session.selectedId()).isEqualTo("11005");

            connection.replyOk(Verb.BANK_LIST, page(List.of(ROW_LINEAR), 0, 1, 1));
            session.reload();

            assertThat(session.selectedId())
                    .as("a detail pane describing a question the list is not showing is a screen "
                            + "contradicting itself")
                    .isNull();
            assertThat(session.detail()).isNull();
        }

        @Test
        @DisplayName("a question that will not open says so and holds no stale detail")
        void detailFailure() {
            connection.replyOk(Verb.QUESTION_GET, detail("11001", false, 1, 1));
            session.select("11001");

            connection.replyError(Verb.QUESTION_GET, ErrorCode.NOT_FOUND, "gone");
            session.select("11002");

            assertThat(session.detailState()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.detailError()).isEqualTo(BankCopy.DETAIL_FAILED);
            assertThat(session.detail()).isNull();
        }
    }

    // ===================== The teeth ======================================

    @Nested
    @DisplayName("late answers are dropped, not applied")
    class LateAnswers {

        /** Answers the request that was sent at {@code index}, whenever the test likes. */
        private void answer(int index, Object payload) {
            connection.deliver(Message.ok(connection.sentMessages().get(index), payload));
        }

        @Test
        @DisplayName("a list answer from a filter that has been replaced never lands ⚑")
        void staleListIsDropped() {
            // No responder: nothing is answered until the test says so.
            session.load();                       // request 0, unfiltered
            session.selectCourse("12");           // request 1, course 12

            answer(1, page(List.of(ROW_LIMIT), 0, 1, 1));
            answer(0, page(List.of(ROW_LINEAR, ROW_QUADRATIC, ROW_GEOMETRY), 0, 3, 1));

            assertThat(session.rows())
                    .as("the unfiltered answer arrived last. Applying it would put Algebra rows "
                            + "under a filter that says Calculus.")
                    .containsExactly(ROW_LIMIT);
            assertThat(session.totalRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("a detail answer for a question she has already clicked past never lands ⚑")
        void staleDetailIsDropped() {
            serverHasTheBank();
            session.load();
            connection.clearSent();

            session.select("11001");              // request 0
            session.select("11002");              // request 1

            answer(1, detail("11002", false, 1, 1));
            answer(0, detail("11001", false, 1, 1));

            assertThat(session.detail().displayId5())
                    .as("the pane must describe the row that is highlighted, not the last answer "
                            + "the network happened to deliver")
                    .isEqualTo("11002");
        }

        @Test
        @DisplayName("an illustration for the previous question never attaches to this one ⚑")
        void staleImageIsDropped() {
            serverHasTheBank();
            session.load();
            connection.clearSent();

            session.select("11005");                                  // request 0: QUESTION_GET
            answer(0, detail("11005", true, 1, 1));                   // request 1: image
            session.select("11001");                                  // request 2: QUESTION_GET
            answer(2, detail("11001", false, 1, 1));

            answer(1, new QuestionImage("11005", 1, "image/png", new byte[] {9, 9}));

            assertThat(session.image())
                    .as("11001 has no illustration at all. Attaching 11005's would show her a "
                            + "diagram belonging to a different question.")
                    .isNull();
            assertThat(session.imageState()).isEqualTo(AsyncViewState.IDLE);
        }

        @Test
        @DisplayName("a stale failure does not overwrite a good screen either")
        void staleFailureIsDropped() {
            session.load();                       // request 0
            session.selectCourse("12");           // request 1

            answer(1, page(List.of(ROW_LIMIT), 0, 1, 1));
            connection.deliver(Message.error(connection.sentMessages().get(0),
                    ErrorCode.INTERNAL, "boom"));

            assertThat(session.state())
                    .as("dropping only the successes would let an old failure blank a screen "
                            + "that is currently correct")
                    .isEqualTo(AsyncViewState.READY);
            assertThat(session.rows()).containsExactly(ROW_LIMIT);
        }
    }

    // ===================== Version history (E6.12) ========================

    @Nested
    @DisplayName("version history")
    class History {

        @BeforeEach
        void openAQuestion() {
            serverHasTheBank();
            session.load();
            connection.replyOk(Verb.QUESTION_GET, detail("11005", false, 3, 3));
            session.select("11005");
            connection.clearSent();
        }

        @Test
        @DisplayName("it is fetched when it is opened, and not before")
        void lazy() {
            assertThat(connection.sentCount()).isZero();

            connection.replyOk(Verb.QUESTION_VERSIONS, new VersionHistory("11005",
                    List.of(version(2, "Read the diagram", 2, "Geometry", Difficulty.HARD, true),
                            version(1, "Read it", 1, "Geometry", Difficulty.MEDIUM, false))));
            session.toggleHistory();

            assertThat(session.isHistoryOpen()).isTrue();
            assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.QUESTION_VERSIONS);
            assertThat(session.historyState()).isEqualTo(AsyncViewState.READY);
        }

        @Test
        @DisplayName("closing and reopening the same question's history costs no round trip")
        void cachedPerQuestion() {
            connection.replyOk(Verb.QUESTION_VERSIONS, new VersionHistory("11005",
                    List.of(version(1, "Read it", 1, "Geometry", Difficulty.HARD, false))));
            session.toggleHistory();
            connection.clearSent();

            session.toggleHistory();
            session.toggleHistory();

            assertThat(connection.sentCount()).isZero();
        }

        @Test
        @DisplayName("the timeline names what changed between versions, and the first one")
        void diffSummary() {
            connection.replyOk(Verb.QUESTION_VERSIONS, new VersionHistory("11005",
                    List.of(version(2, "Read the diagram", 3, "Geometry", Difficulty.HARD, true),
                            version(1, "Read the diagram", 3, "Geometry", Difficulty.HARD,
                                    false))));
            session.toggleHistory();

            List<BankSession.HistoryEntry> entries = session.historyEntries();
            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).isCurrent()).isTrue();
            assertThat(entries.get(0).changes()).contains("an illustration was added");
            assertThat(entries.get(1).isCurrent()).isFalse();
            assertThat(entries.get(1).changes()).isEqualTo("The first version.");
        }

        @Test
        @DisplayName("a history that will not load says so and does not close the panel")
        void failure() {
            connection.replyError(Verb.QUESTION_VERSIONS, ErrorCode.INTERNAL, "boom");

            session.toggleHistory();

            assertThat(session.isHistoryOpen()).isTrue();
            assertThat(session.historyState()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.historyError()).isEqualTo(BankCopy.VERSIONS_FAILED);
            assertThat(session.historyEntries()).isEmpty();
        }

        @Test
        @DisplayName("moving to another question drops the history that was open")
        void resetsOnSelection() {
            connection.replyOk(Verb.QUESTION_VERSIONS, new VersionHistory("11005",
                    List.of(version(1, "Read it", 1, "Geometry", Difficulty.HARD, false))));
            session.toggleHistory();

            connection.replyOk(Verb.QUESTION_GET, detail("11001", false, 1, 1));
            connection.replyOk(Verb.QUESTION_VERSIONS, new VersionHistory("11001",
                    List.of(version(1, "Solve it", 1, "Equations", Difficulty.EASY, false))));
            session.select("11001");

            assertThat(session.history().displayId5())
                    .as("the panel stays open across a selection, so it has to re-read, or it "
                            + "would show one question's history under another's text")
                    .isEqualTo("11001");
        }
    }

    // ===================== Delete (E6.13, T-2.7) ==========================

    @Nested
    @DisplayName("delete")
    class Delete {

        @BeforeEach
        void openTheUsedQuestion() {
            serverHasTheBank();
            session.load();
            // versionNo != latestVersionNo deliberately: with them equal, swapping
            // detail.versionNo() for detail.latestVersionNo() would leave this green, and which
            // token travels is the whole subject of carriesTheBaseVersion.
            connection.replyOk(Verb.QUESTION_GET, detail("11005", false, 2, 3));
            session.select("11005");
            connection.clearSent();
        }

        @Test
        @DisplayName("it carries the version on screen, so a delete racing an edit is a conflict")
        void carriesTheBaseVersion() {
            connection.replyOk(Verb.QUESTION_DELETE, new DeleteOutcome(true, List.of()));

            session.deleteSelected();

            assertThat(connection.sentMessages().get(0).getPayload())
                    .isEqualTo(new QuestionDeleteRequest("11005", 2));
        }

        @Test
        @DisplayName("a question three exams use is refused, and the exams are named (T-2.7)")
        void blockedNamesTheExams() {
            List<BlockingExam> exams = List.of(new BlockingExam("101101", "מבחן אמצע: אלגברה"),
                    new BlockingExam("101102", "בוחן: משוואות"));
            connection.replyOk(Verb.QUESTION_DELETE, new DeleteOutcome(false, exams));

            session.deleteSelected();

            assertThat(session.blockingExams()).isEqualTo(exams);
            assertThat(session.justDeleted()).isNull();
            assertThat(session.selectedId())
                    .as("a refused delete changes nothing, including what she was looking at")
                    .isEqualTo("11005");
            assertThat(BankCopy.deleteBlockedBody(session.blockedQuestion(), session.blockingExams()))
                    .contains("מבחן אמצע: אלגברה", "101101", "בוחן: משוואות");
        }

        @Test
        @DisplayName("a delete that went through clears the pane and re-reads the list")
        void deletedReloads() {
            connection.replyOk(Verb.QUESTION_DELETE, new DeleteOutcome(true, List.of()));
            connection.replyOk(Verb.BANK_LIST,
                    page(List.of(ROW_LINEAR, ROW_QUADRATIC, ROW_LIMIT), 0, 3, 1));

            session.deleteSelected();

            assertThat(session.justDeleted()).isEqualTo("11005");
            assertThat(session.selectedId()).isNull();
            assertThat(session.rows()).doesNotContain(ROW_GEOMETRY);
            // The delete, then exactly one re-read, in that order. Filtered to those two verbs
            // rather than asserting the whole traffic: the reloaded page also asks who is
            // editing its rows (E6.14), which is not what this test is about and would make it
            // fail every time a later epic adds anything to a settled page.
            assertThat(connection.sentMessages()).extracting(Message::getVerb)
                    .filteredOn(verb -> verb == Verb.QUESTION_DELETE || verb == Verb.BANK_LIST)
                    .containsExactly(Verb.QUESTION_DELETE, Verb.BANK_LIST);
        }

        @Test
        @DisplayName("a delete that never reached a decision changes nothing and says so")
        void failure() {
            connection.replyError(Verb.QUESTION_DELETE, ErrorCode.INTERNAL, "boom");

            session.deleteSelected();

            assertThat(session.deleteError()).isEqualTo(BankCopy.DELETE_FAILED);
            assertThat(session.justDeleted()).isNull();
            assertThat(session.blockingExams()).isEmpty();
            assertThat(session.selectedId()).isEqualTo("11005");
        }

        @Test
        @DisplayName("both notices are dismissible, and dismissing twice is a no-op")
        void dismissing() {
            connection.replyOk(Verb.QUESTION_DELETE,
                    new DeleteOutcome(false, List.of(new BlockingExam("101101", "Midterm"))));
            session.deleteSelected();

            session.dismissBlocked();
            assertThat(session.blockingExams()).isEmpty();

            int before = renders;
            session.dismissBlocked();
            session.dismissDeleted();
            assertThat(renders)
                    .as("a dismissal with nothing to dismiss must not churn the screen")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("with nothing selected there is nothing to delete")
        void needsASelection() {
            session.select(null);
            connection.clearSent();

            session.deleteSelected();

            assertThat(connection.sentCount()).isZero();
        }
    }

    // ===================== What the cold audit found ======================

    @Nested
    @DisplayName("findings from the cold audit of this PR")
    class AuditFindings {

        @Test
        @DisplayName("a failed delete says so once, not on every render afterwards")
        void deleteErrorIsDismissible() {
            serverHasTheBank();
            session.load();
            connection.replyOk(Verb.QUESTION_GET, detail("11005", false, 2, 3));
            session.select("11005");
            connection.replyError(Verb.QUESTION_DELETE, ErrorCode.INTERNAL, "boom");
            session.deleteSelected();
            assertThat(session.deleteError()).isEqualTo(BankCopy.DELETE_FAILED);

            session.dismissDeleteError();

            assertThat(session.deleteError())
                    .as("onChange fires on every settle, filter and selection, so a sentence "
                            + "nothing clears is a toast she gets for the rest of the session")
                    .isNull();
        }

        @Test
        @DisplayName("a delete that lands after she moved on does not close the question she opened")
        void lateDeleteLeavesTheNewSelectionAlone() {
            serverHasTheBank();
            session.load();
            connection.replyOk(Verb.QUESTION_GET, detail("11005", false, 2, 3));
            session.select("11005");
            connection.clearSent();

            // The delete goes out with no responder, so it is still in flight.
            session.delete("11005", 2);
            connection.replyOk(Verb.QUESTION_GET, detail("11001", false, 1, 1));
            session.select("11001");
            connection.replyOk(Verb.BANK_LIST,
                    page(List.of(ROW_LINEAR, ROW_QUADRATIC, ROW_LIMIT), 0, 3, 1));

            connection.deliver(Message.ok(connection.sentMessages().get(0),
                    new DeleteOutcome(true, List.of())));

            assertThat(session.selectedId())
                    .as("she deleted 11005 and then opened 11001. Clearing the selection here "
                            + "closes the pane she just opened, with no indication why.")
                    .isEqualTo("11001");
            assertThat(session.justDeleted())
                    .as("the delete really happened, so the toast still names the right question")
                    .isEqualTo("11005");
        }

        @Test
        @DisplayName("a refusal names the question it is about, not just the exams")
        void refusalNamesItsQuestion() {
            serverHasTheBank();
            session.load();
            connection.replyOk(Verb.QUESTION_GET, detail("11005", false, 2, 3));
            session.select("11005");
            connection.replyOk(Verb.QUESTION_DELETE,
                    new DeleteOutcome(false, List.of(new BlockingExam("101101", "Midterm"))));

            session.delete("11005", 2);

            assertThat(session.blockedQuestion())
                    .as("the dialog is modal and runs a nested event loop, so the pane behind it "
                            + "can change while she reads it")
                    .isEqualTo("11005");
            assertThat(BankCopy.deleteBlockedBody("11005", session.blockingExams()))
                    .contains("#11005");
        }

        @Test
        @DisplayName("the page showing is the one the answer reports, not the one that was asked")
        void pageIsAdoptedFromThePayload() {
            // A server that answers a page other than the one asked for. Today's does not: it
            // clamps a negative page and echoes everything else. The property is worth pinning
            // anyway, because the whole of finding 3 was the client keeping its own account of
            // which page is on screen, and this is that account's one remaining copy.
            connection.respondTo(Verb.BANK_LIST, request ->
                    Message.ok(request, page(List.of(ROW_LINEAR), 0, 100, 5)));
            session.load();

            session.nextPage();

            assertThat(session.page())
                    .as("page 1 was asked for and page 0 came back; the answer wins, or the "
                            + "pager and the rows describe two different pages")
                    .isZero();
        }

        @Test
        @DisplayName("deleting the last row of the last page steps back rather than saying the "
                + "bank is empty ⚑")
        void deletingTheLastRowStepsBack() {
            // 41 questions over two pages. She is on page 2, which holds one row.
            connection.respondTo(Verb.BANK_LIST, request -> {
                BankListRequest asked = (BankListRequest) request.getPayload();
                return asked.page() == 0
                        ? Message.ok(request, page(List.of(ROW_LINEAR), 0, 41, 2))
                        : Message.ok(request, page(List.of(ROW_GEOMETRY), 1, 41, 2));
            });
            session.load();
            session.nextPage();
            assertThat(session.page()).isEqualTo(1);

            // The row goes, and now the bank fits on one page.
            connection.respondTo(Verb.BANK_LIST, request -> {
                BankListRequest asked = (BankListRequest) request.getPayload();
                return asked.page() == 0
                        ? Message.ok(request, page(List.of(ROW_LINEAR), 0, 40, 1))
                        : Message.ok(request, page(List.of(), 1, 40, 1));
            });
            connection.replyOk(Verb.QUESTION_GET, detail("11005", false, 1, 1));
            session.select("11005");
            connection.replyOk(Verb.QUESTION_DELETE, new DeleteOutcome(true, List.of()));
            session.deleteSelected();

            assertThat(session.page())
                    .as("the reload asked for page 2 of a one-page bank and the server answered "
                            + "it empty, because it clamps only negatives")
                    .isZero();
            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.emptyPanel())
                    .as("without the step back, a teacher with forty questions is told she has "
                            + "none, which is the one sentence NO_QUESTIONS must never say")
                    .isEmpty();
        }

        @Test
        @DisplayName("the course picker offers what she can see, not only what she teaches ⚑")
        void courseOptionsCoverTheReadScope() {
            // rina.barak: a coordinators row for subject 10 and zero course_teachers rows, so
            // her sign-in payload carries no courses at all.
            FakeClientConnection hers = new FakeClientConnection();
            RequestDispatcher dispatcher = new RequestDispatcher(hers);
            hers.setServerMessageHandler(dispatcher::dispatchIncoming);
            hers.replyOk(Verb.BANK_LIST, page(List.of(ROW_LINEAR, ROW_LIMIT), 0, 2, 1));

            BankSession coordinator =
                    sessionOn(dispatcher);
            coordinator.load();

            assertThat(coordinator.courseOptions()).extracting(CourseRef::code)
                    .as("her read scope is every course of her subject, and the sign-in payload "
                            + "is taught union enrolled. Offering only the payload leaves the "
                            + "starred demo approver a picker with nothing in it.")
                    .containsExactly("11", "12");
        }

        @Test
        @DisplayName("writing is narrower than seeing, which is the coordinator's whole case ⚑")
        void canWriteOnlyWhereSheTeaches() {
            // rina.barak reads every course of subject 10 and teaches none of them.
            FakeClientConnection hers = new FakeClientConnection();
            RequestDispatcher dispatcher = new RequestDispatcher(hers);
            hers.setServerMessageHandler(dispatcher::dispatchIncoming);
            BankSession coordinator =
                    sessionOn(dispatcher);

            assertThat(coordinator.canWriteIn("11"))
                    .as("the bank shows her the course; QUESTION_DELETE would refuse her. "
                            + "Offering the control is offering a trip with one outcome.")
                    .isFalse();

            assertThat(session.canWriteIn("11"))
                    .as("Dana teaches Algebra, so the same row is hers to change")
                    .isTrue();
        }

        @Test
        @DisplayName("a course code that is blank, null or unknown is never writable")
        void writeScopeIsClosed() {
            assertThat(session.canWriteIn(null)).isFalse();
            assertThat(session.canWriteIn("   ")).isFalse();
            assertThat(session.canWriteIn("99")).isFalse();
        }

        @Test
        @DisplayName("an ordinary-space padded course code still matches, and a NBSP one does not")
        void writeScopeStrips() {
            assertThat(session.canWriteIn(" 11 "))
                    .as("strip() handles the ordinary spaces, which is what the contract's "
                            + "section 5 asks for")
                    .isTrue();

            assertThat(session.canWriteIn(NBSP + "11"))
                    .as("and it does NOT reach U+00A0: Character.isWhitespace rejects it, so the "
                            + "padded code equals no member of the set and the write is refused. "
                            + "That fails closed, which is the safe direction, and it is the "
                            + "same measured limit E7-TYPES section 4.3 pins. This test was "
                            + "named for the non-breaking case while passing ordinary spaces, "
                            + "so it reported green for a case it never ran.")
                    .isFalse();
        }

        @Test
        @DisplayName("a question that would not open can be asked for again")
        void retryAfterAFailedDetail() {
            serverHasTheBank();
            session.load();
            connection.replyError(Verb.QUESTION_GET, ErrorCode.INTERNAL, "boom");
            session.select("11005");
            assertThat(session.detailState()).isEqualTo(AsyncViewState.ERROR);
            connection.clearSent();

            connection.replyOk(Verb.QUESTION_GET, detail("11005", false, 1, 1));
            session.retrySelected();

            assertThat(session.detailState())
                    .as("the row stays highlighted after a failure, so clicking it again fires "
                            + "no selection change and the list cannot offer the retry itself")
                    .isEqualTo(AsyncViewState.READY);
            assertThat(session.detail().displayId5()).isEqualTo("11005");
        }

        @Test
        @DisplayName("retrying is only offered where it means something")
        void retryIsANoOpWhenThereIsNothingToRetry() {
            serverHasTheBank();
            session.load();
            connection.replyOk(Verb.QUESTION_GET, detail("11005", false, 1, 1));
            session.select("11005");
            connection.clearSent();

            session.retrySelected();

            assertThat(connection.sentCount()).isZero();
        }
    }

    // ===================== E6.14, the Editing column ======================

    /**
     * The live "Editing · Ron Levi" column (E6.14 — F10.3, E18.8).
     *
     * <p>Two verbs feed it and the third one must never be sent. {@code LOCKS_SNAPSHOT} is the
     * state at first paint, {@code LOCK_WATCH} registers for the news afterwards, and
     * {@code LOCK_RELEASE} would drop this user's own editing lock, because the server's release
     * unwatches and un-holds in one call and the list keys on the same reference the editor
     * does. {@link #neverReleasesAnything} is the test that guard rests on.
     */
    @Nested
    @DisplayName("who is editing each row (E6.14)")
    class EditingColumn {

        private EntityRef ref(String displayId5) {
            return QuestionLockKey.of(displayId5);
        }

        private LocksSnapshot heldBy(String displayId5, long userId, String name) {
            return new LocksSnapshot(EntityRef.QUESTION,
                    java.util.Map.of(ref(displayId5).entityId(), new LockHolder(userId, name)));
        }

        private List<EntityRef> watchedRefs() {
            return connection.sentMessages().stream()
                    .filter(message -> message.getVerb() == Verb.LOCK_WATCH)
                    .map(message -> ((LockRequest) message.getPayload()).entity())
                    .toList();
        }

        @Test
        @DisplayName("the snapshot at load is what fills the column")
        void snapshotFillsTheColumn() {
            serverHasTheBank();
            connection.replyOk(Verb.LOCKS_SNAPSHOT, heldBy("11005", RON, "Ron Levi"));

            session.load();

            assertThat(session.editorOf("11005"))
                    .as("pushes carry news and not state, so a question locked before this "
                            + "screen opened is only ever known through the snapshot")
                    .isPresent()
                    .get()
                    .extracting(LockHolder::displayName)
                    .isEqualTo("Ron Levi");
            assertThat(session.editorOf("11001"))
                    .as("a row nobody holds is absent from the map, not mapped to null")
                    .isEmpty();
        }

        @Test
        @DisplayName("every row on the page is watched, and none of them twice")
        void watchesEachRowOnce() {
            serverHasTheBank();
            connection.replyOk(Verb.LOCKS_SNAPSHOT, LocksSnapshot.empty(EntityRef.QUESTION));

            session.load();

            assertThat(watchedRefs())
                    .as("watch registers interest and takes nothing; acquiring here would put "
                            + "four colleagues into read-only mode by the act of looking")
                    .containsExactlyInAnyOrder(ref("11001"), ref("11002"), ref("11005"),
                            ref("12001"));

            connection.clearSent();
            session.reload();

            assertThat(watchedRefs())
                    .as("the same page re-watched sends nothing: the registration is already "
                            + "held and re-watching every refresh is how a list produces traffic "
                            + "proportional to how long it is left open")
                    .isEmpty();
        }

        /**
         * The watch is registered before the snapshot is asked for ⚑
         *
         * <p>Not a preference. {@code EditLockService.publish} resolves recipients by reading
         * its watcher set at the instant the lock changes, so asking for the snapshot first
         * opens a window: the server answers "free", a colleague acquires before this client's
         * {@code LOCK_WATCH} is handled, and {@code publish} finds a set this client is not in.
         * No push is ever written, the snapshot already said free, and the row shows free for
         * the whole time he is editing it - the one case the column exists to prevent.
         *
         * <p>Found by an adversarial read, not by the suite, which is why the order is now
         * asserted rather than left to whoever edits {@code showing} next.
         */
        @Test
        @DisplayName("every row is watched before the snapshot is asked for ⚑")
        void watchesBeforeAskingWhoHolds() {
            serverHasTheBank();
            connection.replyOk(Verb.LOCKS_SNAPSHOT, LocksSnapshot.empty(EntityRef.QUESTION));

            session.load();

            List<Verb> lockTraffic = connection.sentMessages().stream()
                    .map(Message::getVerb)
                    .filter(verb -> verb == Verb.LOCK_WATCH || verb == Verb.LOCKS_SNAPSHOT)
                    .toList();
            assertThat(lockTraffic)
                    .as("the snapshot must be the LAST of the two, or a lock taken between the "
                            + "server answering it and the watch registering is invisible for "
                            + "the rest of the session")
                    .endsWith(Verb.LOCKS_SNAPSHOT);
            assertThat(lockTraffic.subList(0, lockTraffic.size() - 1))
                    .as("and everything before it is the watches")
                    .containsOnly(Verb.LOCK_WATCH);
        }

        /**
         * The snapshot's own repaint, which nothing pinned ⚑
         *
         * <p>Every other test in this class settles through {@code DirectFxThreadPoster}, so
         * {@code settleSnapshot} runs <em>inline</em> inside {@code settleList} and the chips are
         * already in place when {@code settleList} renders. In production the poster is
         * {@code Platform.runLater}: {@code settleList} renders on this pulse with no holders
         * yet, and {@code settleSnapshot}'s own {@code onChange.run()} on a later pulse is the
         * <b>only</b> thing that paints the column on first load. Deleting it left every test
         * green and would have shipped a column blank until a colleague happened to open
         * something.
         */
        @Test
        @DisplayName("the snapshot landing is itself a repaint ⚑")
        void theSnapshotRepaintsOnItsOwn() {
            // No responder, so the snapshot is delivered by hand AFTER the page has settled and
            // rendered. That is the production ordering, which the direct poster otherwise hides.
            session.load();
            Message list = connection.sentMessages().stream()
                    .filter(message -> message.getVerb() == Verb.BANK_LIST)
                    .findFirst().orElseThrow();
            connection.deliver(Message.ok(list,
                    page(List.of(ROW_LINEAR, ROW_GEOMETRY), 0, 2, 1)));
            Message snapshot = connection.sentMessages().stream()
                    .filter(message -> message.getVerb() == Verb.LOCKS_SNAPSHOT)
                    .findFirst().orElseThrow();
            int afterThePageRendered = renders;

            connection.deliver(Message.ok(snapshot, heldBy("11005", RON, "Ron Levi")));

            assertThat(renders)
                    .as("the page had already been drawn with no chips on it, so the snapshot "
                            + "has to ask for a redraw of its own or the column stays blank "
                            + "until something unrelated repaints the table")
                    .isGreaterThan(afterThePageRendered);
            assertThat(session.editorOf("11005")).isPresent();
        }

        @Test
        @DisplayName("a colleague opening a question shows up without a refresh")
        void pushArrivesLive() {
            serverHasTheBank();
            connection.replyOk(Verb.LOCKS_SNAPSHOT, LocksSnapshot.empty(EntityRef.QUESTION));
            session.load();
            assertThat(session.editorOf("11001")).isEmpty();

            connection.pushToClient(Verb.PUSH_LOCK_CHANGED,
                    LockChange.acquired(ref("11001"), new LockHolder(RON, "Ron Levi")));

            assertThat(session.editorOf("11001"))
                    .as("NFR-18 forbids asking her to press refresh, and this is the column "
                            + "that would need one most")
                    .isPresent()
                    .get()
                    .extracting(LockHolder::displayName)
                    .isEqualTo("Ron Levi");
        }

        @Test
        @DisplayName("releasing and expiring both free the row")
        void releaseAndExpiryBothFreeIt() {
            serverHasTheBank();
            connection.replyOk(Verb.LOCKS_SNAPSHOT, heldBy("11005", RON, "Ron Levi"));
            session.load();

            connection.pushToClient(Verb.PUSH_LOCK_CHANGED, LockChange.released(ref("11005")));
            assertThat(session.editorOf("11005")).isEmpty();

            connection.pushToClient(Verb.PUSH_LOCK_CHANGED,
                    LockChange.acquired(ref("11005"), new LockHolder(RON, "Ron Levi")));
            connection.pushToClient(Verb.PUSH_LOCK_CHANGED, LockChange.expired(ref("11005")));

            assertThat(session.editorOf("11005"))
                    .as("a holder whose client died stops blocking the row, or a crash would "
                            + "park a question for the rest of the day")
                    .isEmpty();
        }

        @Test
        @DisplayName("a push about a question on another page changes nothing here")
        void pushForARowNotOnScreenIsIgnored() {
            serverHasTheBank();
            connection.replyOk(Verb.LOCKS_SNAPSHOT, LocksSnapshot.empty(EntityRef.QUESTION));
            session.load();
            int before = renders;

            connection.pushToClient(Verb.PUSH_LOCK_CHANGED,
                    LockChange.acquired(ref("99999"), new LockHolder(RON, "Ron Levi")));

            assertThat(session.editorOf("99999")).isEmpty();
            assertThat(renders)
                    .as("one bus carries every push, so a screen that re-rendered on all of "
                            + "them would repaint on traffic that cannot change it")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("her own lock is hers, not a colleague's name shown back at her")
        void herOwnLockReadsAsHers() {
            serverHasTheBank();
            connection.replyOk(Verb.LOCKS_SNAPSHOT, heldBy("11005", DANA, "Dana Cohen"));

            session.load();

            LockHolder holder = session.editorOf("11005").orElseThrow();
            assertThat(session.isSelf(holder))
                    .as("a name against a row is the shape that means somebody else has it")
                    .isTrue();
            assertThat(BankCopy.editing(holder, true)).isEqualTo("Editing · you");
        }

        /**
         * The stale snapshot that names a row still on screen ⚑
         *
         * <p><b>The row the two pages have in common is the whole test.</b> A stale answer about
         * a question that paged away is already refused by the forward map, which holds only the
         * rows showing — so a test built on one of those passes with the generation check
         * deleted, and pins nothing. Measured: planting that mutation left the first version of
         * this test green.
         *
         * <p>What the generation check alone catches is an answer that is stale and still
         * <em>relevant</em>: she narrows the search, a question matches both the old filter and
         * the new one, and the older snapshot lands last carrying who was editing it a moment
         * ago. Without the check that older truth wins, and the column shows a colleague in a
         * question he has already closed, until something else happens to repaint it.
         */
        @Test
        @DisplayName("a snapshot that outlived its page loses to the current one ⚑")
        void staleSnapshotIsDiscarded() {
            // No responder: the answers are handed over by hand, in the order this test wants.
            // The LateAnswers shape, for the same reason that class exists.
            session.load();
            Message firstList = connection.sentMessages().stream()
                    .filter(message -> message.getVerb() == Verb.BANK_LIST)
                    .findFirst().orElseThrow();
            connection.deliver(Message.ok(firstList,
                    page(List.of(ROW_LINEAR, ROW_GEOMETRY), 0, 2, 1)));

            Message firstSnapshot = connection.sentMessages().stream()
                    .filter(message -> message.getVerb() == Verb.LOCKS_SNAPSHOT)
                    .findFirst().orElseThrow();

            // She types into the search box before that snapshot comes back. 11005 matches the
            // new filter too, so it is on screen under both generations.
            connection.clearSent();
            session.setSearch("diagram");
            Message secondList = connection.sentMessages().stream()
                    .filter(message -> message.getVerb() == Verb.BANK_LIST)
                    .findFirst().orElseThrow();
            connection.deliver(Message.ok(secondList,
                    page(List.of(ROW_GEOMETRY, ROW_QUADRATIC), 0, 2, 1)));

            Message secondSnapshot = connection.sentMessages().stream()
                    .filter(message -> message.getVerb() == Verb.LOCKS_SNAPSHOT)
                    .reduce((first, second) -> second).orElseThrow();
            connection.deliver(Message.ok(secondSnapshot,
                    LocksSnapshot.empty(EntityRef.QUESTION)));
            assertThat(session.editorOf("11005"))
                    .as("the current answer: Ron has closed it")
                    .isEmpty();

            // And now the older one lands, still naming him.
            connection.deliver(Message.ok(firstSnapshot, heldBy("11005", RON, "Ron Levi")));

            assertThat(session.editorOf("11005"))
                    .as("the row is still on screen, so nothing but the generation stops this "
                            + "older truth from overwriting the newer one and parking a "
                            + "colleague's name on a question he has already let go")
                    .isEmpty();
        }

        @Test
        @DisplayName("a snapshot that failed leaves the column empty rather than saying so")
        void aFailedSnapshotIsSilent() {
            serverHasTheBank();
            connection.replyError(Verb.LOCKS_SNAPSHOT, ErrorCode.INTERNAL, "no");

            session.load();

            assertThat(session.state())
                    .as("the browse worked; a decoration that did not must not take the screen "
                            + "down with it")
                    .isEqualTo(AsyncViewState.READY);
            assertThat(session.error()).isNull();
            assertThat(session.editorOf("11005")).isEmpty();
        }

        @Test
        @DisplayName("a failed page clears the chips with the rows")
        void aFailedPageClearsTheChips() {
            serverHasTheBank();
            connection.replyOk(Verb.LOCKS_SNAPSHOT, heldBy("11005", RON, "Ron Levi"));
            session.load();
            assertThat(session.editorOf("11005")).isPresent();

            connection.replyError(Verb.BANK_LIST, ErrorCode.INTERNAL, "no");
            session.reload();

            assertThat(session.editorOf("11005"))
                    .as("holders left over from the last good page would sit against an error "
                            + "panel showing nothing to edit")
                    .isEmpty();
        }

        /**
         * The guard the whole design rests on (E6.14 ⚑).
         *
         * <p>{@code EditLockService.release} drops the caller's hold <b>and</b> unwatches, in one
         * call, with no watcher-only form. The bank list and the question editor key on the
         * identical reference, so one release sent from this class for a question this user has
         * open would hand her own work to whoever asks next. {@code Verb.LOCK_WATCH}'s contract
         * text tells list screens to send exactly that, which is why this is a test and not a
         * comment.
         *
         * <p>It walks every path that could plausibly grow one: a load, a re-page, a filter
         * change, a selection, a failed page, and leaving the screen.
         */
        @Test
        @DisplayName("no path on this screen ever sends LOCK_RELEASE ⚑")
        void neverReleasesAnything() {
            serverHasTheBank();
            connection.replyOk(Verb.LOCKS_SNAPSHOT, heldBy("11005", DANA, "Dana Cohen"));
            connection.replyOk(Verb.QUESTION_GET, detail("11005", true, 1, 1));

            session.load();
            session.nextPage();
            session.previousPage();
            session.setSearch("limit");
            session.selectCourse("11");
            session.selectDifficulty(Difficulty.HARD);
            session.clearFilters();
            session.select("11005");
            session.select(null);
            session.reload();
            session.stop();

            assertThat(connection.sentMessages()).extracting(Message::getVerb)
                    .as("the one verb that could withdraw a watch also releases a held lock, so "
                            + "this screen has no code path that sends it and leaving the screen "
                            + "is not one either")
                    .doesNotContain(Verb.LOCK_RELEASE);
        }

        /**
         * Leaving really unsubscribes, asserted where it can fail ⚑
         *
         * <p>The obvious version of this test - stop, push, assert nothing arrived - <b>cannot
         * fail for the thing it names</b>, because {@code stop()} also empties the rows on
         * screen and {@code onServerPush} bails on an unknown reference anyway. Delete the
         * {@code eventBus.unregister} and it stays green.
         *
         * <p>So it is asserted through the consequence instead: greenrobot throws
         * {@code EventBusException: Subscriber already registered} on a second {@code register},
         * which is what a teacher leaving the bank and coming back would hit. Re-entering the
         * screen has to work, and the pushes have to work again after it.
         */
        @Test
        @DisplayName("leaving the screen unsubscribes, so coming back does not throw ⚑")
        void stopUnsubscribes() {
            serverHasTheBank();
            connection.replyOk(Verb.LOCKS_SNAPSHOT, heldBy("11005", RON, "Ron Levi"));
            session.load();

            session.stop();

            assertThat(session.editorOf("11005"))
                    .as("the chips are forgotten with the screen")
                    .isEmpty();
            connection.pushToClient(Verb.PUSH_LOCK_CHANGED,
                    LockChange.acquired(ref("11001"), new LockHolder(RON, "Ron Levi")));
            assertThat(session.editorOf("11001"))
                    .as("a screen that is not showing must not keep painting")
                    .isEmpty();

            // The half that can actually fail: a second onShow after a stop that never
            // unregistered takes the screen out with an EventBusException.
            session.load();

            assertThat(session.editorOf("11005"))
                    .as("re-entering the bank works, and its chips are live again")
                    .isPresent();
            connection.pushToClient(Verb.PUSH_LOCK_CHANGED, LockChange.released(ref("11005")));
            assertThat(session.editorOf("11005"))
                    .as("and the re-registered subscriber is receiving, not a stale one")
                    .isEmpty();
        }

        @Test
        @DisplayName("a row whose id is not five digits is skipped, not fatal to the page ⚑")
        void aMalformedIdDoesNotTakeThePageDown() {
            BankQuestionRow malformed = row("7", "11", "אלגברה", "A row the wire got wrong",
                    "Equations", Difficulty.EASY, false);
            connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request,
                    page(List.of(ROW_LINEAR, malformed, ROW_GEOMETRY), 0, 3, 1)));
            connection.replyOk(Verb.LOCKS_SNAPSHOT, heldBy("11005", RON, "Ron Levi"));

            session.load();

            assertThat(session.state())
                    .as("a throw here runs inside whenComplete, which swallows it, so settleList "
                            + "would never reach its own render and the teacher would sit on the "
                            + "spinner forever with no error and nothing to retry")
                    .isEqualTo(AsyncViewState.READY);
            assertThat(session.rows()).hasSize(3);
            assertThat(session.editorOf("11005"))
                    .as("and the rows that do key still get their chips")
                    .isPresent();
            assertThat(session.editorOf("7")).isEmpty();
        }

        /**
         * The leading-zero case, which is the one a reverse mapping loses (E6.14 ⚑).
         *
         * <p>A course code may lead with a zero, so display id {@code 01003} keys
         * {@code question#1003} and formatting that id back gives {@code "1003"} — a different
         * row, or no row at all. {@code QuestionLockKeyTest.leadingZeroIsNotLost} pins the
         * forward direction; this pins that the column never walks it backwards, which is the
         * shape that looks correct against every fixture whose course code starts with a 1.
         */
        @Test
        @DisplayName("a display id with a leading zero still finds its own row ⚑")
        void leadingZeroSurvivesTheRoundTrip() {
            BankQuestionRow leadingZero = row("01003", "01", "מבוא", "The zeroth course",
                    "Basics", Difficulty.EASY, false);
            connection.respondTo(Verb.BANK_LIST, request ->
                    Message.ok(request, page(List.of(leadingZero), 0, 1, 1)));
            connection.replyOk(Verb.LOCKS_SNAPSHOT, heldBy("01003", RON, "Ron Levi"));

            session.load();

            assertThat(session.editorOf("01003"))
                    .as("the id is only ever mapped forwards; formatting 1003 back to five "
                            + "digits is the mutation that passes on every 11xxx fixture")
                    .isPresent();

            connection.pushToClient(Verb.PUSH_LOCK_CHANGED, LockChange.released(ref("01003")));
            assertThat(session.editorOf("01003"))
                    .as("and the push matches the same way")
                    .isEmpty();
        }

        @Test
        @DisplayName("a push that is not a lock change is not this class's business")
        void otherPushesPassThrough() {
            serverHasTheBank();
            connection.replyOk(Verb.LOCKS_SNAPSHOT, heldBy("11005", RON, "Ron Levi"));
            session.load();
            int before = renders;

            connection.pushToClient(Verb.PUSH_EXECUTION_STATUS, "not a lock change");
            connection.pushToClient(Verb.PUSH_LOCK_CHANGED, "not a LockChange either");

            assertThat(session.editorOf("11005"))
                    .as("one bus carries every push, so a malformed one and somebody else's "
                            + "verb both have to leave this column exactly as it was")
                    .isPresent();
            assertThat(renders).isEqualTo(before);
        }

        /**
         * The snapshot's id cap, and why it truncates rather than throws.
         *
         * <p>{@code LocksSnapshotRequest.MAX_IDS} is 500 and the bank contract clamps a page far
         * below it, so this is unreachable today and exists for the day somebody raises the page
         * size and not the cap. An oversized request comes back a refusal, which would blank the
         * column on the whole page instead of on the rows past the cap.
         *
         * <p>It was a thrown {@code IllegalStateException} first, and <b>the throw did nothing</b>:
         * this path runs inside the dispatcher's {@code whenComplete}, which captures a throwable
         * into the future rather than propagating it, so "fails loudly" was a dead page render
         * and a green test. Found by writing this test and watching it not throw.
         */
        @Test
        @DisplayName("a page bigger than the snapshot cap asks about as many as it may ⚑")
        void anOversizedPageIsTruncatedNotRefused() {
            // Course 11 plus a three-digit serial (S-8), so one more row than the cap allows and
            // every id distinct.
            List<BankQuestionRow> tooMany = new java.util.ArrayList<>();
            for (int serial = 0; serial <= LocksSnapshotRequest.MAX_IDS; serial++) {
                tooMany.add(row(String.format("11%03d", serial), "11", "אלגברה",
                        "Question " + serial, "Equations", Difficulty.EASY, false));
            }
            connection.respondTo(Verb.BANK_LIST, request ->
                    Message.ok(request, page(tooMany, 0, tooMany.size(), 1)));

            session.load();

            LocksSnapshotRequest asked = connection.sentMessages().stream()
                    .filter(message -> message.getVerb() == Verb.LOCKS_SNAPSHOT)
                    .map(message -> (LocksSnapshotRequest) message.getPayload())
                    .reduce((first, second) -> second).orElseThrow();
            assertThat(asked.isOversized())
                    .as("an oversized request is refused whole, so the column would go blank on "
                            + "every row rather than on the ones past the cap")
                    .isFalse();
            assertThat(asked.entityIds()).hasSize(LocksSnapshotRequest.MAX_IDS);
            assertThat(session.state())
                    .as("and the browse itself is untouched by a decoration hitting its limit")
                    .isEqualTo(AsyncViewState.READY);
        }

        @Test
        @DisplayName("an empty page asks nobody who is editing nothing")
        void anEmptyPageAsksNothing() {
            connection.replyOk(Verb.BANK_LIST, page(List.of(), 0, 0, 0));

            session.load();

            assertThat(connection.sentMessages()).extracting(Message::getVerb)
                    .doesNotContain(Verb.LOCKS_SNAPSHOT, Verb.LOCK_WATCH);
        }
    }

    // ===================== The small surfaces =============================

    @Nested
    @DisplayName("readers and guards the screen leans on")
    class Surfaces {

        @Test
        @DisplayName("the toast flag really clears once it has been shown")
        void deletedIsCleared() {
            serverHasTheBank();
            session.load();
            connection.replyOk(Verb.QUESTION_GET, detail("11005", false, 2, 3));
            session.select("11005");
            connection.replyOk(Verb.QUESTION_DELETE, new DeleteOutcome(true, List.of()));
            session.deleteSelected();
            assertThat(session.justDeleted()).isEqualTo("11005");

            session.dismissDeleted();

            assertThat(session.justDeleted())
                    .as("the same reason dismissDeleteError exists: render fires on everything")
                    .isNull();
        }

        @Test
        @DisplayName("re-selecting the question already open costs no round trip")
        void reselectingIsIgnored() {
            serverHasTheBank();
            session.load();
            connection.replyOk(Verb.QUESTION_GET, detail("11005", false, 2, 3));
            session.select("11005");
            connection.clearSent();

            session.select("11005");

            assertThat(connection.sentCount()).isZero();
        }

        @Test
        @DisplayName("a second delete while one is in flight is refused, and so is a null id")
        void deleteGuards() {
            serverHasTheBank();
            session.load();
            connection.replyOk(Verb.QUESTION_GET, detail("11005", false, 2, 3));
            session.select("11005");
            connection.clearSent();

            session.delete("11005", 2);          // no responder: still in flight
            session.delete("11005", 2);
            session.delete(null, 2);

            assertThat(connection.sentCount())
                    .as("two deletes of one question would race each other to a CONFLICT")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a null course list and a null search are the empty ones")
        void nullsAreEmpties() {
            BankSession bare = new BankSession(new RequestDispatcher(new FakeClientConnection()),
                    new DirectFxThreadPoster(), null,
                    new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster()), DANA);

            assertThat(bare.courses()).isEmpty();
            assertThat(bare.courseOptions()).isEmpty();

            serverHasTheBank();
            session.load();
            session.setSearch("limit");
            session.setSearch(null);

            assertThat(session.search()).isEmpty();
            assertThat(session.isFiltered()).isFalse();
        }

        @Test
        @DisplayName("the pickers report back what is set on them")
        void readersReportTheFilters() {
            serverHasTheBank();
            session.load();

            session.selectDifficulty(Difficulty.MEDIUM);

            assertThat(session.selectedDifficulty()).isEqualTo(Difficulty.MEDIUM);
            assertThat(session.courses()).containsExactly(ALGEBRA, CALCULUS);
        }
    }

    // ===================== The topic lookup's one place ===================

    @Test
    @DisplayName("the topic picker is empty until BANK_TOPICS exists, and the filter still works")
    void topicLookupIsBoundInOnePlace() {
        serverHasTheBank();
        session.load();

        assertThat(session.availableTopics())
                .as("ruling 7.6 replaced the typed topic with a picker fed by a verb that does "
                        + "not exist yet. Until it does this is the single place that answers, "
                        + "and the view hides the picker rather than offering an empty one.")
                .isEmpty();

        session.selectTopic("Geometry");
        assertThat(lastListRequest().topic())
                .as("the filter itself has been on the wire since the read-verbs PR, so the "
                        + "lookup landing changes this method and nothing else")
                .isEqualTo("Geometry");
    }
}
