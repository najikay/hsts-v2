package server.features.reports;

import common.dto.auth.Role;
import common.dto.report.DataExamRow;
import common.dto.report.DataExams;
import common.dto.report.DataResults;
import common.dto.report.ReportRow;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.Authorization;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.db.projections.ExecutionReport;
import server.db.projections.SchoolExam;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The principal's two data-browser reads (Logic tier, E15.2 — F9.3, S-7, T-11).
 *
 * <p>{@code DATA_EXAMS_GET} answers "what exams does this school have"; {@code DATA_RESULTS_GET}
 * answers "what has been sat and marked". Together with the four bank read verbs she already
 * holds ({@code BankReadHandlers}, F9.3, BANK contract section 3) they are the whole of T-11's
 * "read-only browse of question bank, exams, results".
 *
 * <h2>Only two verbs were added, and the third tab needed none ⚑</h2>
 *
 * <p>The Questions tab of the screen calls {@code BANK_LIST}, unchanged and unwidened. The
 * principal has been on that verb's role list since E6 and reaches every course through it
 * (BANK contract section 2), so duplicating it here for her would have been a second answer to a
 * question that already has one. The two verbs below exist because there was no school-wide exam
 * listing and no school-wide results listing at all: {@code RESULTS_EXAMS_GET} and
 * {@code RESULTS_EXECUTION_GET} are scoped to the exams the caller <b>wrote</b> (S-35), which is
 * a scope the principal does not have and must not be given by loosening theirs.
 *
 * <h2>The role gate is the whole authorization, again</h2>
 *
 * <p>{@code requireRole(caller, PRINCIPAL)} and nothing else, exactly as {@link ReportService}
 * does and for the reason that service's javadoc gives: spec 7.3.1 and F9.3 give her the whole
 * school to read, so there is no slice to compute and a scope check here could only ever pass.
 * A teacher, a coordinator and a student get {@code FORBIDDEN} from the gate rather than an
 * empty list, because an empty list would read as "the school has no exams".
 *
 * <h2>Zero mutating verbs, structurally</h2>
 *
 * <p>Both verbs reach the database only through {@link ReportData}, whose every method is a read
 * (S-7). Adding a write for this role would mean adding a method to that interface first, in a
 * file whose javadoc says why it has none. Neither verb takes a payload, so there is not even a
 * field for a client to put an id in.
 *
 * <h2>What a sitting is, is defined once</h2>
 *
 * <p>The results list is built from the same {@code REPORTABLE} clause the reports use and
 * mapped through the same {@link ReportEngine#toRows} — the same frozen statistics, the same
 * participant count, the same unusable-column rule. A sitting therefore cannot read one way on
 * this screen and another way in a report, and cancelled runs are absent from both (H15.2 ⚑).
 */
public final class DataBrowseService {

    private static final Logger log = LoggerFactory.getLogger(DataBrowseService.class);

    private final ReportStore store;

    /** @param store the read-only transactional seam, shared with the report engine */
    public DataBrowseService(ReportStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Registers both browse verbs. Authenticated and role-gated inside. */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.DATA_EXAMS_GET, this::exams);
        router.register(Verb.DATA_RESULTS_GET, this::results);
    }

    // ===================== The verbs =====================================

    /**
     * {@code DATA_EXAMS_GET} — every exam in the school (T-11.2).
     *
     * <p>No payload, so there is no malformed-payload path and no {@code VALIDATION} answer: a
     * request that carries something anyway is answered rather than refused, because there is no
     * field whose contents could change what is read.
     *
     * @param caller  the authenticated principal
     * @param request the request; its payload is ignored
     * @return {@code OK} with a {@link DataExams}, empty when the school has written none
     */
    Message exams(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.PRINCIPAL);
        List<SchoolExam> found = store.inTx(ReportData::allExams);
        List<DataExamRow> rows = new ArrayList<>(found.size());
        for (SchoolExam exam : found) {
            rows.add(new DataExamRow(exam.displayId(), exam.examName(), exam.courseCode(),
                    exam.courseName(), exam.authorName(), exam.versions(), exam.lastVersionAt()));
        }
        log.debug("Data browser: {} exam(s) for {}", rows.size(), caller);
        return Message.ok(request, new DataExams(rows));
    }

    /**
     * {@code DATA_RESULTS_GET} — every closed sitting in the school (T-11.2).
     *
     * <p>Newest first, which is the opposite of a report's ordering: a browse is a filing
     * cabinet and the thing being looked for is usually the most recent, while a comparison is a
     * trend and reads from the oldest.
     *
     * @param caller  the authenticated principal
     * @param request the request; its payload is ignored
     * @return {@code OK} with a {@link DataResults}, empty when nothing has been fully marked
     */
    Message results(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.PRINCIPAL);
        List<ReportRow> rows = store.inTx(data -> {
            List<ExecutionReport> sittings = data.allClosedSittings();
            return ReportEngine.toRows(data, sittings);
        });
        log.debug("Data browser: {} closed sitting(s) for {}", rows.size(), caller);
        return Message.ok(request, new DataResults(rows));
    }
}
