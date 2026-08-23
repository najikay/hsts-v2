package client.features.reports;

import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import client.ui.components.logic.StatChartData;
import common.dto.report.ReportDimension;
import common.dto.report.ReportRequest;
import common.dto.report.ReportResult;
import common.dto.report.ReportRow;
import common.dto.report.ReportSubject;
import common.dto.report.ReportSubjects;
import common.dto.report.ReportSubjectsRequest;
import common.dto.report.ReportSummary;
import common.dto.results.ResultStatistics;
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
import static org.assertj.core.api.Assertions.within;

/**
 * {@link ReportsSession} — E15.4's screen behaviour, without a JavaFX toolkit.
 *
 * <p>The session talks to a {@link FakeClientConnection} through a real
 * {@link RequestDispatcher}, and the FX hop is a {@link DirectFxThreadPoster}, so every
 * transition settles synchronously (TEAM_SPLIT section 3.2).
 *
 * <p>The fixture is the seeded world seen from the principal's chair: Dana with the closed,
 * fully graded Algebra sitting 4821 (mean 72.5, median 72.5, σ 17.5, 7 of 8 passed) and a
 * second, quieter sitting beside it, plus Rina, who has nothing to report. Those two together
 * are what the default-selection, empty-state and row-to-chart rules are about.
 */
class ReportsSessionTest {

    private static final Instant SPRING = Instant.parse("2026-03-10T07:00:00Z");
    private static final Instant SUMMER = Instant.parse("2026-08-07T06:00:00Z");

    private FakeClientConnection connection;
    private ReportsSession session;
    private int renders;

    @BeforeEach
    void setUp() {
        connection = new FakeClientConnection();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        session = new ReportsSession(dispatcher, new DirectFxThreadPoster())
                .onChange(() -> renders++);
    }

    // ===================== Fixture =======================================

    /** SEED_CONTENT section 9.1's frozen record. */
    private static ResultStatistics seeded() {
        return new ResultStatistics(8, 72.5, 72.5, 17.5, 45, 100, 7, 0.875,
                List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));
    }

    /** Scores 50, 60, 70, 80. */
    private static ResultStatistics quiet() {
        return new ResultStatistics(4, 65, 65, Math.sqrt(125), 50, 80, 3, 0.75,
                List.of(0, 0, 0, 0, 0, 1, 1, 1, 1, 0));
    }

    private static ReportRow older() {
        return new ReportRow(1, "4821", "מבחן אמצע: אלגברה", "11", "אלגברה", SPRING,
                SPRING.plusSeconds(7200), 8, seeded());
    }

    private static ReportRow newer() {
        return new ReportRow(2, "5150", "בוחן: אי-שוויונות", "11", "אלגברה", SUMMER,
                SUMMER.plusSeconds(7200), 4, quiet());
    }

    private static final ReportSubject DANA = new ReportSubject("2", "דנה כהן", "dana.cohen", 2);
    private static final ReportSubject RINA = new ReportSubject("3", "רינה ברק", "rina.barak", 0);
    private static final ReportSubject ALGEBRA = new ReportSubject("11", "אלגברה", "Course 11", 2);

    private static ReportResult danaReport() {
        List<ReportRow> rows = List.of(older(), newer());
        return new ReportResult(ReportDimension.BY_TEACHER, DANA, rows,
                ReportSummary.across(rows));
    }

    /**
     * An answer the dispatcher cannot correlate, which leaves the request pending.
     *
     * <p>The way to say "this one never comes back" while another request on the same verb does
     * come back. Returning {@code null} from a responder would hand a null message to the
     * dispatcher instead, which is a different situation from a slow server.
     */
    private static Message neverArrives(Message request) {
        return Message.ok(request.getVerb(), "a-request-nobody-made", null);
    }

    private void serverHasEverything() {
        connection.respondTo(Verb.REPORT_SUBJECTS_GET, request -> {
            ReportSubjectsRequest ask = (ReportSubjectsRequest) request.getPayload();
            return Message.ok(request, new ReportSubjects(ask.dimension(),
                    ask.dimension() == ReportDimension.BY_COURSE
                            ? List.of(ALGEBRA)
                            // Rina first, and empty: the picker must skip past her.
                            : List.of(RINA, DANA)));
        });
        connection.respondTo(Verb.REPORT_GET, request -> {
            ReportRequest ask = (ReportRequest) request.getPayload();
            if (ask.dimension() == ReportDimension.BY_COURSE) {
                List<ReportRow> rows = List.of(older(), newer());
                return Message.ok(request, new ReportResult(ReportDimension.BY_COURSE, ALGEBRA,
                        rows, ReportSummary.across(rows)));
            }
            if (ask.subjectId().equals(RINA.id())) {
                return Message.ok(request, new ReportResult(ReportDimension.BY_TEACHER, RINA,
                        List.of(), ReportSummary.EMPTY));
            }
            return Message.ok(request, danaReport());
        });
    }

    // ===================== Loading =======================================

    @Nested
    @DisplayName("loading")
    class Loading {

        @Test
        @DisplayName("the screen opens on the first dimension and loads its subjects")
        void loadsSubjects() {
            serverHasEverything();

            session.load();

            assertThat(session.dimension()).isEqualTo(ReportDimension.BY_TEACHER);
            assertThat(session.subjectsState()).isEqualTo(AsyncViewState.READY);
            assertThat(session.subjects()).containsExactly(RINA, DANA);
            assertThat(session.subjectsError()).isEmpty();
        }

        @Test
        @DisplayName("it opens on the first subject that has something to compare (E15.5)")
        void opensOnASubjectWithRows() {
            serverHasEverything();

            session.load();

            assertThat(session.selectedSubject()).contains(DANA);
            assertThat(session.rows()).hasSize(2);
            assertThat(session.reportState()).isEqualTo(AsyncViewState.READY);
        }

        @Test
        @DisplayName("a skeleton is shown while the request is in flight")
        void skeletonWhileLoading() {
            // No canned reply: the future stays pending.
            session.load();

            assertThat(session.subjectsState()).isEqualTo(AsyncViewState.LOADING);
            assertThat(session.isLoading()).isTrue();
            assertThat(session.rows()).isEmpty();
        }

        @Test
        @DisplayName("a failed subject list is a sentence, never a stack trace")
        void subjectsFailure() {
            connection.replyError(Verb.REPORT_SUBJECTS_GET, ErrorCode.INTERNAL, "boom");

            session.load();

            assertThat(session.subjectsState()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.subjectsError()).contains(ReportsCopy.SUBJECTS_FAILED);
            assertThat(session.subjects()).isEmpty();
        }

        @Test
        @DisplayName("an OK carrying the wrong type is treated as a failure, not rendered")
        void wrongPayloadType() {
            connection.replyOk(Verb.REPORT_SUBJECTS_GET, "not a subject list");

            session.load();

            assertThat(session.subjectsState()).isEqualTo(AsyncViewState.ERROR);
        }

        @Test
        @DisplayName("an answer about another dimension is discarded rather than rendered")
        void mismatchedDimensionIsDiscarded() {
            connection.replyOk(Verb.REPORT_SUBJECTS_GET,
                    new ReportSubjects(ReportDimension.BY_STUDENT, List.of(DANA)));

            session.load();

            assertThat(session.subjectsState()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.subjects()).isEmpty();
        }

        @Test
        @DisplayName("a dimension with no subjects at all is an explained empty, not an error")
        void noSubjects() {
            connection.replyOk(Verb.REPORT_SUBJECTS_GET,
                    new ReportSubjects(ReportDimension.BY_TEACHER, List.of()));

            session.load();

            assertThat(session.subjectsState()).isEqualTo(AsyncViewState.EMPTY);
            assertThat(session.emptyPanel()).isEqualTo(ReportsCopy.NO_SUBJECTS);
        }
    }

    // ===================== Switching dimension ===========================

    @Nested
    @DisplayName("switching dimension")
    class Dimensions {

        @Test
        @DisplayName("it reloads the subjects and runs the new dimension's report")
        void switching() {
            serverHasEverything();
            session.load();

            session.selectDimension(ReportDimension.BY_COURSE);

            assertThat(session.dimension()).isEqualTo(ReportDimension.BY_COURSE);
            assertThat(session.subjects()).containsExactly(ALGEBRA);
            assertThat(session.selectedSubject()).contains(ALGEBRA);
            assertThat(session.rows()).hasSize(2);
        }

        @Test
        @DisplayName("the previous subject and its report are dropped immediately")
        void previousSubjectIsDropped() {
            serverHasEverything();
            session.load();
            connection.clearSent();
            // A dimension whose subject list never arrives: the screen must not still be
            // showing a teacher's name under a heading that now says "by student".
            connection.respondTo(Verb.REPORT_SUBJECTS_GET,
                    ReportsSessionTest::neverArrives);

            session.selectDimension(ReportDimension.BY_STUDENT);

            assertThat(session.selectedSubject()).isEmpty();
            assertThat(session.rows()).isEmpty();
            assertThat(session.summary()).isEqualTo(ReportSummary.EMPTY);
            assertThat(session.heading()).isEmpty();
        }

        @Test
        @DisplayName("selecting the dimension already showing costs no round trip")
        void reselectingIsIgnored() {
            serverHasEverything();
            session.load();
            connection.clearSent();

            session.selectDimension(ReportDimension.BY_TEACHER);

            assertThat(connection.sentCount()).isZero();
        }

        @Test
        @DisplayName("a subject list that lands after she has moved on is discarded")
        void staleSubjectsAreDiscarded() {
            // BY_TEACHER never answers; BY_STUDENT does. The stale BY_TEACHER answer is then
            // delivered by hand and must not repaint the picker.
            connection.respondTo(Verb.REPORT_SUBJECTS_GET, request ->
                    ((ReportSubjectsRequest) request.getPayload()).dimension()
                            == ReportDimension.BY_STUDENT
                            ? Message.ok(request, new ReportSubjects(ReportDimension.BY_STUDENT,
                                    List.of(new ReportSubject("11", "מאיה לוי", "maya.levi", 0))))
                            : neverArrives(request));
            session.load();

            session.selectDimension(ReportDimension.BY_STUDENT);

            assertThat(session.dimension()).isEqualTo(ReportDimension.BY_STUDENT);
            assertThat(session.subjects()).extracting(ReportSubject::label)
                    .containsExactly("מאיה לוי");
        }
    }

    // ===================== Running a report ==============================

    @Nested
    @DisplayName("running a report")
    class Running {

        @Test
        @DisplayName("picking a subject sends the dimension and its id, and nothing else")
        void picking() {
            serverHasEverything();
            session.load();
            connection.clearSent();

            session.selectSubject(RINA);

            ReportRequest sent = (ReportRequest) connection.lastSent().getPayload();
            assertThat(sent.dimension()).isEqualTo(ReportDimension.BY_TEACHER);
            assertThat(sent.subjectId()).isEqualTo("3");
        }

        @Test
        @DisplayName("a subject with nothing to compare is explained, not shown as an error")
        void emptySubject() {
            serverHasEverything();
            session.load();

            session.selectSubject(RINA);

            assertThat(session.reportState()).isEqualTo(AsyncViewState.EMPTY);
            assertThat(session.reportError()).isEmpty();
            assertThat(session.emptyPanel()).isEqualTo(ReportsCopy.NO_EXECUTIONS);
            assertThat(session.selectedRow()).isEmpty();
        }

        @Test
        @DisplayName("a failed report is a sentence, and the rows are cleared with it")
        void reportFailure() {
            connection.replyOk(Verb.REPORT_SUBJECTS_GET,
                    new ReportSubjects(ReportDimension.BY_TEACHER, List.of(DANA)));
            connection.replyError(Verb.REPORT_GET, ErrorCode.NOT_FOUND, "gone");

            session.load();

            assertThat(session.reportState()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.reportError()).contains(ReportsCopy.REPORT_FAILED);
            assertThat(session.rows()).isEmpty();
        }

        @Test
        @DisplayName("an answer about a subject she has moved off is discarded")
        void staleReportIsDiscarded() {
            connection.replyOk(Verb.REPORT_SUBJECTS_GET,
                    new ReportSubjects(ReportDimension.BY_TEACHER, List.of(DANA, RINA)));
            connection.respondTo(Verb.REPORT_GET, request ->
                    ((ReportRequest) request.getPayload()).subjectId().equals(RINA.id())
                            ? Message.ok(request, new ReportResult(ReportDimension.BY_TEACHER,
                                    RINA, List.of(), ReportSummary.EMPTY))
                            : neverArrives(request));
            session.load();

            session.selectSubject(RINA);

            assertThat(session.selectedSubject()).contains(RINA);
            assertThat(session.rows())
                    .as("Dana's rows must not appear under Rina's heading")
                    .isEmpty();
        }

        @Test
        @DisplayName("a null subject is ignored rather than clearing the screen")
        void nullSubjectIsIgnored() {
            serverHasEverything();
            session.load();
            connection.clearSent();

            session.selectSubject(null);

            assertThat(session.selectedSubject()).contains(DANA);
            assertThat(connection.sentCount()).isZero();
        }
    }

    // ===================== The row-to-chart flow =========================

    @Nested
    @DisplayName("the row selection drives the chart ⚑")
    class RowSelection {

        @Test
        @DisplayName("the report opens on its most recent sitting")
        void opensOnTheNewest() {
            serverHasEverything();

            session.load();

            assertThat(session.selectedRow()).isPresent();
            assertThat(session.selectedRow().orElseThrow().code4()).isEqualTo("5150");
            assertThat(session.chartHeading()).isEqualTo("בוחן: אי-שוויונות · 5150");
        }

        @Test
        @DisplayName("⚑ selecting a row repoints the chart at that sitting's stored figures")
        void selectingARowMovesTheChart() {
            serverHasEverything();
            session.load();

            session.selectRow(older());

            StatChartData data = session.chartData();
            assertThat(session.selectedRow().orElseThrow().code4()).isEqualTo("4821");
            assertThat(data.mean()).isEqualTo(72.5);
            assertThat(data.median()).isEqualTo(72.5);
            assertThat(data.standardDeviation())
                    .as("the stored population sigma, straight across")
                    .isEqualTo(17.5);
            assertThat(data.buckets()).containsExactly(0, 0, 0, 0, 1, 1, 1, 2, 1, 2);
            assertThat(data.participantCount()).isEqualTo(8);
            assertThat(session.chartHeading()).isEqualTo("מבחן אמצע: אלגברה · 4821");
        }

        @Test
        @DisplayName("selecting the row already showing costs no re-render")
        void reselectingIsIgnored() {
            serverHasEverything();
            session.load();
            int before = renders;

            session.selectRow(newer());

            assertThat(renders).isEqualTo(before);
        }

        @Test
        @DisplayName("a row that is not in the current report is ignored")
        void foreignRowIsIgnored() {
            serverHasEverything();
            session.load();

            session.selectRow(new ReportRow(99, "9999", "אחר", "12", "חדו\"א", SUMMER,
                    SUMMER.plusSeconds(7200), 3, quiet()));
            session.selectRow(null);

            assertThat(session.selectedRow().orElseThrow().code4()).isEqualTo("5150");
        }

        @Test
        @DisplayName("no rows means an empty chart, never a record of zeros")
        void noRowsIsAnEmptyChart() {
            serverHasEverything();
            session.load();

            session.selectSubject(RINA);

            assertThat(session.chartData()).isEqualTo(StatChartData.empty());
            assertThat(session.chartHeading()).isEmpty();
        }
    }

    // ===================== The summary ===================================

    @Nested
    @DisplayName("the summary the screen prints")
    class Summary {

        @Test
        @DisplayName("⚑ it is the server's aggregate, carried through and not recomputed here")
        void summaryIsCarried() {
            serverHasEverything();

            session.load();

            ReportSummary summary = session.summary();
            assertThat(summary.executions()).isEqualTo(2);
            assertThat(summary.scored()).isEqualTo(12);
            assertThat(summary.mean()).isEqualTo(70.0);
            assertThat(summary.standardDeviation())
                    .isCloseTo(Math.sqrt(3100.0 / 12), within(1e-9));
            assertThat(summary.passCount()).isEqualTo(10);
            assertThat(summary.deciles()).containsExactly(0, 0, 0, 0, 1, 2, 2, 3, 2, 2);
        }

        @Test
        @DisplayName("with nothing loaded the summary is EMPTY, so no card prints a zero")
        void emptyBeforeAnythingLoads() {
            assertThat(session.summary()).isEqualTo(ReportSummary.EMPTY);
            assertThat(session.emptyPanel()).isEqualTo(ReportsCopy.NOTHING_PICKED);
        }

        @Test
        @DisplayName("the heading names the subject and the dimension it was compared as")
        void heading() {
            serverHasEverything();
            session.load();

            assertThat(session.heading()).isEqualTo("דנה כהן · by teacher");

            session.selectDimension(ReportDimension.BY_COURSE);

            assertThat(session.heading()).isEqualTo("אלגברה · by course");
        }
    }

    // ===================== Print layout ==================================

    @Test
    @DisplayName("the print layout toggles, and toggling it twice is one change each way")
    void printLayout() {
        assertThat(session.isPrintLayout()).isFalse();
        int before = renders;

        session.setPrintLayout(true);
        session.setPrintLayout(true);

        assertThat(session.isPrintLayout()).isTrue();
        assertThat(renders).isEqualTo(before + 1);

        session.setPrintLayout(false);

        assertThat(session.isPrintLayout()).isFalse();
        assertThat(renders).isEqualTo(before + 2);
    }
}
