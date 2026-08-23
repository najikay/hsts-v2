package client.features.data;

import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.bank.BankListRequest;
import common.dto.bank.BankPage;
import common.dto.bank.BankQuestionRow;
import common.dto.bank.Difficulty;
import common.dto.report.DataExamRow;
import common.dto.report.DataExams;
import common.dto.report.DataResults;
import common.dto.report.ReportRow;
import common.dto.results.ResultStatistics;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DataSession} — E15.2's screen behaviour, without a JavaFX toolkit.
 *
 * <p>The session talks to a {@link FakeClientConnection} through a real
 * {@link RequestDispatcher}, and the FX hop is a {@link DirectFxThreadPoster}, so every
 * transition settles synchronously (TEAM_SPLIT section 3.2).
 *
 * <p>The fixture is the seeded school seen from the principal's chair: four Algebra and Calculus
 * questions, two exams, and the closed Algebra sitting 4821 beside a quieter one. Those are what
 * the per-tab loading, the two filters, the derived course options and the two empty states are
 * about.
 */
class DataSessionTest {

    private static final Instant SPRING = Instant.parse("2026-03-10T07:00:00Z");
    private static final Instant SUMMER = Instant.parse("2026-08-07T06:00:00Z");

    private FakeClientConnection connection;
    private DataSession session;
    private int renders;

    @BeforeEach
    void setUp() {
        connection = new FakeClientConnection();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        session = new DataSession(dispatcher, new DirectFxThreadPoster())
                .onChange(() -> renders++);
    }

    // ===================== Fixture =======================================

    private static BankQuestionRow question(String id, String course, String courseName,
                                            String text, String topic, Difficulty difficulty) {
        return new BankQuestionRow(id, course, courseName, text, topic, difficulty, 1, false,
                SPRING);
    }

    private static final BankQuestionRow Q_LINEAR =
            question("11001", "11", "אלגברה", "Solve the linear equation", "Equations",
                    Difficulty.EASY);
    private static final BankQuestionRow Q_QUADRATIC =
            question("11002", "11", "אלגברה", "Factor the quadratic", "Equations",
                    Difficulty.MEDIUM);
    /** The one row with a null topic: the filter must survive it (real bank data does this). */
    private static final BankQuestionRow Q_LIMIT =
            question("12001", "12", "חדו\"א", "Evaluate the limit", null, Difficulty.HARD);

    private static final DataExamRow EXAM_ALGEBRA = new DataExamRow("101101",
            "מבחן אמצע: אלגברה", "11", "אלגברה", "דנה כהן", 2, SUMMER);
    private static final DataExamRow EXAM_CALCULUS = new DataExamRow("101201",
            "בוחן: גבולות", "12", "חדו\"א", "רינה ברק", 1, SPRING);

    /** SEED_CONTENT section 9.1's frozen record. */
    private static ResultStatistics seeded() {
        return new ResultStatistics(8, 72.5, 72.5, 17.5, 45, 100, 7, 0.875,
                List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));
    }

    private static ResultStatistics quiet() {
        return new ResultStatistics(4, 65, 65, Math.sqrt(125), 50, 80, 3, 0.75,
                List.of(0, 0, 0, 0, 0, 1, 1, 1, 1, 0));
    }

    private static final ReportRow SITTING_NEWER = new ReportRow(2, "5150",
            "בוחן: אי-שוויונות", "11", "אלגברה", SUMMER, SUMMER.plusSeconds(7200), 4, quiet());
    private static final ReportRow SITTING_OLDER = new ReportRow(1, "4821",
            "מבחן אמצע: אלגברה", "11", "אלגברה", SPRING, SPRING.plusSeconds(7200), 8, seeded());

    private void serverHasEverything() {
        connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request,
                new BankPage(List.of(Q_LINEAR, Q_QUADRATIC, Q_LIMIT), 0,
                        BankListRequest.MAX_PAGE_SIZE, 3, 1)));
        connection.replyOk(Verb.DATA_EXAMS_GET,
                new DataExams(List.of(EXAM_ALGEBRA, EXAM_CALCULUS)));
        connection.replyOk(Verb.DATA_RESULTS_GET,
                new DataResults(List.of(SITTING_NEWER, SITTING_OLDER)));
    }

    // ===================== Loading =======================================

    @Nested
    @DisplayName("loading, one tab at a time")
    class Loading {

        @Test
        @DisplayName("the screen opens on Questions and loads the bank, nothing else")
        void opensOnQuestions() {
            serverHasEverything();

            session.load();

            assertThat(session.tab()).isEqualTo(DataTab.QUESTIONS);
            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.questions()).containsExactly(Q_LINEAR, Q_QUADRATIC, Q_LIMIT);
            assertThat(connection.sentMessages()).extracting(Message::getVerb)
                    .as("a tab nobody has opened costs no round trip")
                    .containsExactly(Verb.BANK_LIST);
        }

        @Test
        @DisplayName("the bank is asked for unfiltered, so the screen holds the whole school's")
        void bankIsAskedForUnfiltered() {
            serverHasEverything();

            session.load();

            BankListRequest sent = (BankListRequest) connection.lastSent().getPayload();
            assertThat(sent.isUnfiltered())
                    .as("the filters are applied here; a filter on the wire would be a field a "
                            + "client could widen")
                    .isTrue();
            assertThat(sent.size()).isEqualTo(BankListRequest.MAX_PAGE_SIZE);
        }

        @Test
        @DisplayName("switching to a tab loads it, and switching back costs nothing")
        void eachTabLoadsOnce() {
            serverHasEverything();
            session.load();

            session.selectTab(DataTab.EXAMS);
            assertThat(session.exams()).containsExactly(EXAM_ALGEBRA, EXAM_CALCULUS);
            connection.clearSent();

            session.selectTab(DataTab.QUESTIONS);
            session.selectTab(DataTab.EXAMS);

            assertThat(connection.sentCount())
                    .as("a browser that re-fetched on every tab click would be a refresh button "
                            + "nobody pressed (NFR-18)")
                    .isZero();
        }

        @Test
        @DisplayName("selecting the tab already showing costs no round trip and no render")
        void reselectingIsIgnored() {
            serverHasEverything();
            session.load();
            connection.clearSent();
            int before = renders;

            session.selectTab(DataTab.QUESTIONS);

            assertThat(connection.sentCount()).isZero();
            assertThat(renders).isEqualTo(before);
        }

        @Test
        @DisplayName("a skeleton is shown while the request is in flight")
        void skeletonWhileLoading() {
            // No canned reply: the future stays pending.
            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.LOADING);
            assertThat(session.isLoading()).isTrue();
            assertThat(session.questions()).isEmpty();
        }

        @Test
        @DisplayName("the Results tab loads its own verb and holds the server's order")
        void resultsTab() {
            serverHasEverything();
            session.load();

            session.selectTab(DataTab.RESULTS);

            assertThat(session.sittings())
                    .as("newest first, as the wire delivered them; the screen does not re-sort")
                    .containsExactly(SITTING_NEWER, SITTING_OLDER);
            assertThat(connection.sentMessages()).extracting(Message::getVerb)
                    .contains(Verb.DATA_RESULTS_GET);
        }
    }

    // ===================== Failures ======================================

    @Nested
    @DisplayName("when a list does not arrive")
    class Failures {

        @Test
        @DisplayName("a failed tab is a sentence naming that tab, never a stack trace")
        void failureIsASentence() {
            connection.replyError(Verb.DATA_EXAMS_GET, ErrorCode.INTERNAL, "boom");
            connection.replyOk(Verb.BANK_LIST, new BankPage(List.of(), 0, 100, 0, 0));
            session.load();

            session.selectTab(DataTab.EXAMS);

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.error()).contains(DataCopy.loadFailed(DataTab.EXAMS));
            assertThat(session.error().orElseThrow())
                    .as("the sentence names which list failed, because three tabs share a screen")
                    .contains("exams");
        }

        @Test
        @DisplayName("an OK carrying the wrong type is treated as a failure, not rendered")
        void wrongPayloadType() {
            connection.replyOk(Verb.BANK_LIST, "not a page of questions");

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.questions()).isEmpty();
        }

        @Test
        @DisplayName("⚑ a tab that failed is asked again when it is next opened")
        void aFailedTabRecovers() {
            connection.replyOk(Verb.BANK_LIST, new BankPage(List.of(), 0, 100, 0, 0));
            connection.replyError(Verb.DATA_EXAMS_GET, ErrorCode.INTERNAL, "boom");
            session.load();
            session.selectTab(DataTab.EXAMS);
            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);

            connection.replyOk(Verb.DATA_EXAMS_GET, new DataExams(List.of(EXAM_ALGEBRA)));
            session.selectTab(DataTab.QUESTIONS);
            session.selectTab(DataTab.EXAMS);

            assertThat(session.state())
                    .as("with no reload button on the screen, coming back is the only way out "
                            + "of a dropped connection")
                    .isEqualTo(AsyncViewState.READY);
            assertThat(session.exams()).containsExactly(EXAM_ALGEBRA);
            assertThat(session.error()).isEmpty();
        }

        @Test
        @DisplayName("a failure part way through the bank drops the partial list")
        void partialBankIsNotShown() {
            connection.respondTo(Verb.BANK_LIST, request -> {
                BankListRequest ask = (BankListRequest) request.getPayload();
                return ask.page() == 0
                        ? Message.ok(request, new BankPage(List.of(Q_LINEAR), 0, 1, 2, 2))
                        : Message.error(request, ErrorCode.INTERNAL, "boom");
            });

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.questions())
                    .as("half a bank shown as a whole one is worse than a sentence saying it "
                            + "did not load")
                    .isEmpty();
        }
    }

    // ===================== The bank's pages ==============================

    @Nested
    @DisplayName("the bank arrives a page at a time and is shown as one list")
    class Paging {

        @Test
        @DisplayName("every page is fetched and appended, and the screen sees one list")
        void pagesAreJoined() {
            connection.respondTo(Verb.BANK_LIST, request -> {
                BankListRequest ask = (BankListRequest) request.getPayload();
                List<BankQuestionRow> page = switch (ask.page()) {
                    case 0 -> List.of(Q_LINEAR);
                    case 1 -> List.of(Q_QUADRATIC);
                    default -> List.of(Q_LIMIT);
                };
                return Message.ok(request, new BankPage(page, ask.page(), 1, 3, 3));
            });

            session.load();

            assertThat(session.questions()).containsExactly(Q_LINEAR, Q_QUADRATIC, Q_LIMIT);
            assertThat(session.isBankTruncated()).isFalse();
            assertThat(connection.sentCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("⚑ the loop is bounded, and says so rather than hanging on a bad server")
        void theLoopIsBounded() {
            // A server that always claims another page exists. Without the bound this never
            // returns.
            connection.respondTo(Verb.BANK_LIST, request -> {
                BankListRequest ask = (BankListRequest) request.getPayload();
                return Message.ok(request, new BankPage(List.of(Q_LINEAR), ask.page(), 1,
                        1_000_000L, Integer.MAX_VALUE));
            });

            session.load();

            assertThat(connection.sentCount()).isEqualTo(DataSession.MAX_BANK_PAGES);
            assertThat(session.isBankTruncated()).isTrue();
            assertThat(session.state())
                    .as("what did arrive is still shown; the note above it says it is not all")
                    .isEqualTo(AsyncViewState.READY);
        }
    }

    // ===================== The filters ===================================

    @Nested
    @DisplayName("the two filters")
    class Filters {

        @Test
        @DisplayName("the text filter is case-insensitive, stripped, and matches mid-word")
        void textFilter() {
            serverHasEverything();
            session.load();

            session.setFilter("  QUADratic  ");

            assertThat(session.questions()).containsExactly(Q_QUADRATIC);
            assertThat(session.isFiltered()).isTrue();
            assertThat(session.filter())
                    .as("stored as typed minus the padding, so the box does not fight her")
                    .isEqualTo("QUADratic");
        }

        @Test
        @DisplayName("it matches the id, the topic and the course as well as the text")
        void textFilterMatchesEveryColumn() {
            serverHasEverything();
            session.load();

            session.setFilter("11002");
            assertThat(session.questions()).containsExactly(Q_QUADRATIC);

            session.setFilter("equations");
            assertThat(session.questions()).containsExactly(Q_LINEAR, Q_QUADRATIC);

            session.setFilter("אלגברה");
            assertThat(session.questions()).containsExactly(Q_LINEAR, Q_QUADRATIC);
        }

        @Test
        @DisplayName("a row with no topic is filtered past rather than crashing the filter")
        void nullTopicIsSafe() {
            serverHasEverything();
            session.load();

            session.setFilter("limit");

            assertThat(session.questions()).containsExactly(Q_LIMIT);
        }

        @Test
        @DisplayName("the course filter narrows to one course, and clearing it restores the list")
        void courseFilter() {
            serverHasEverything();
            session.load();

            session.selectCourse("12");
            assertThat(session.questions()).containsExactly(Q_LIMIT);
            assertThat(session.selectedCourse()).contains("12");

            session.selectCourse(null);
            assertThat(session.questions()).hasSize(3);
            assertThat(session.selectedCourse()).isEmpty();
            assertThat(session.isFiltered()).isFalse();
        }

        @Test
        @DisplayName("both filters compose, and clearFilters drops both at once")
        void filtersCompose() {
            serverHasEverything();
            session.load();

            session.selectCourse("11");
            session.setFilter("linear");
            assertThat(session.questions()).containsExactly(Q_LINEAR);

            session.clearFilters();

            assertThat(session.questions()).hasSize(3);
            assertThat(session.filter()).isEmpty();
            assertThat(session.selectedCourse()).isEmpty();
        }

        @Test
        @DisplayName("filters are per tab: narrowing one list does not narrow another")
        void filtersArePerTab() {
            serverHasEverything();
            session.load();
            session.setFilter("linear");

            session.selectTab(DataTab.EXAMS);

            assertThat(session.filter()).isEmpty();
            assertThat(session.exams()).hasSize(2);

            session.selectTab(DataTab.QUESTIONS);

            assertThat(session.filter())
                    .as("coming back to a list to find your filter gone would undo a decision "
                            + "she made")
                    .isEqualTo("linear");
            assertThat(session.questions()).containsExactly(Q_LINEAR);
        }

        @Test
        @DisplayName("the exam filter matches its own columns, the author included")
        void examFilter() {
            serverHasEverything();
            session.load();
            session.selectTab(DataTab.EXAMS);

            session.setFilter("רינה");
            assertThat(session.exams()).containsExactly(EXAM_CALCULUS);

            session.setFilter("101101");
            assertThat(session.exams()).containsExactly(EXAM_ALGEBRA);
        }

        @Test
        @DisplayName("the sitting filter matches the code and the exam name")
        void sittingFilter() {
            serverHasEverything();
            session.load();
            session.selectTab(DataTab.RESULTS);

            session.setFilter("4821");

            assertThat(session.sittings()).containsExactly(SITTING_OLDER);
        }

        @Test
        @DisplayName("setting the filter to what it already is costs no re-render")
        void resettingTheSameFilterIsIgnored() {
            serverHasEverything();
            session.load();
            session.setFilter("linear");
            int before = renders;

            session.setFilter("linear");
            session.setFilter(" linear ");
            session.selectCourse(null);
            session.clearFilters();
            int afterClear = renders;
            session.clearFilters();

            assertThat(renders).isEqualTo(afterClear);
            assertThat(afterClear).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("a course code with trailing padding still matches (MySQL PAD SPACE)")
        void paddedCourseCodesMatch() {
            connection.replyOk(Verb.BANK_LIST, new BankPage(
                    List.of(question("11001", "11 ", "אלגברה", "Solve it", "Equations",
                            Difficulty.EASY)), 0, 100, 1, 1));

            session.load();
            session.selectCourse("11");

            assertThat(session.questions()).hasSize(1);
            assertThat(session.courseOptions()).extracting(DataSession.CourseOption::code)
                    .containsExactly("11");
        }
    }

    // ===================== The course options ============================

    @Nested
    @DisplayName("the course dropdown")
    class CourseOptions {

        @Test
        @DisplayName("it is built from the rows in hand, deduplicated and ordered by code")
        void derivedFromTheRows() {
            serverHasEverything();
            session.load();

            assertThat(session.courseOptions())
                    .extracting(DataSession.CourseOption::code)
                    .containsExactly("11", "12");
            assertThat(session.courseOptions().get(0).label()).isEqualTo("אלגברה (11)");
        }

        @Test
        @DisplayName("it cannot offer a course that would filter the list to nothing")
        void neverOffersADeadEnd() {
            serverHasEverything();
            session.load();
            session.selectTab(DataTab.RESULTS);

            assertThat(session.courseOptions())
                    .as("both sittings are Algebra's, so Calculus is not on this tab's picker "
                            + "even though the school has one")
                    .extracting(DataSession.CourseOption::code)
                    .containsExactly("11");
        }

        @Test
        @DisplayName("before a tab loads it has no options at all")
        void emptyBeforeLoading() {
            assertThat(session.courseOptions()).isEmpty();
        }
    }

    // ===================== The empty states ==============================

    @Nested
    @DisplayName("the two empty states ⚑")
    class Empties {

        @Test
        @DisplayName("⚑ a filter that matches nothing says so, not that the tab is empty")
        void filteredToNothing() {
            serverHasEverything();
            session.load();

            session.setFilter("nothing here matches this");

            assertThat(session.questions()).isEmpty();
            assertThat(session.emptyPanel())
                    .as("a principal who has typed something and is told the bank is empty will "
                            + "believe it")
                    .isEqualTo(DataCopy.NO_MATCHES);
        }

        @Test
        @DisplayName("a genuinely empty tab gets its own panel, one per tab")
        void genuinelyEmpty() {
            connection.replyOk(Verb.BANK_LIST, new BankPage(List.of(), 0, 100, 0, 0));
            connection.replyOk(Verb.DATA_EXAMS_GET, DataExams.EMPTY);
            connection.replyOk(Verb.DATA_RESULTS_GET, DataResults.EMPTY);
            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.EMPTY);
            assertThat(session.emptyPanel()).isEqualTo(DataCopy.NO_QUESTIONS);

            session.selectTab(DataTab.EXAMS);
            assertThat(session.emptyPanel()).isEqualTo(DataCopy.NO_EXAMS);

            session.selectTab(DataTab.RESULTS);
            assertThat(session.emptyPanel()).isEqualTo(DataCopy.NO_RESULTS);
        }

        @Test
        @DisplayName("before anything loads the panel is the tab's own, never a filter message")
        void beforeLoading() {
            assertThat(session.emptyPanel()).isEqualTo(DataCopy.NO_QUESTIONS);
        }
    }

    // ===================== The count line ================================

    @Test
    @DisplayName("the count line names the whole list, and both halves while a filter narrows it")
    void countLine() {
        serverHasEverything();
        session.load();

        assertThat(session.countLine()).isEqualTo("3 questions");
        assertThat(session.loadedCount()).isEqualTo(3);

        session.setFilter("equations");

        assertThat(session.countLine())
                .as("a filtered list must never be mistakable for a short one")
                .isEqualTo("2 of 3 questions");
        assertThat(session.shownCount()).isEqualTo(2);
    }

    // ===================== Read-only by construction ⚑ ===================

    @Test
    @DisplayName("⚑ the whole screen sends three verbs, and every one of them is a read (S-7)")
    void onlyReadsAreEverSent() {
        serverHasEverything();
        session.load();
        session.selectTab(DataTab.EXAMS);
        session.selectTab(DataTab.RESULTS);
        session.setFilter("anything");
        session.selectCourse("11");
        session.clearFilters();

        List<Verb> sent = new ArrayList<>(connection.sentMessages().stream()
                .map(Message::getVerb).distinct().toList());

        assertThat(sent)
                .as("S-7: the principal is authorized for literally nothing that writes, and "
                        + "this is the client end of the same rule")
                .containsExactlyInAnyOrder(Verb.BANK_LIST, Verb.DATA_EXAMS_GET,
                        Verb.DATA_RESULTS_GET);
    }

    @Test
    @DisplayName("a tab is required, and so is a listener")
    void nullsAreRefused() {
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> session.selectTab(null));
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> session.onChange(null));
    }
}
