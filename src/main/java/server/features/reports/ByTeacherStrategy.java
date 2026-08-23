package server.features.reports;

import common.dto.report.ReportDimension;
import common.dto.report.ReportSubject;
import server.db.projections.ExecutionReport;
import server.db.projections.PersonRef;
import server.db.repos.ExecutionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Compare the sittings of one teacher's exams" (Logic tier, E15.3 — F9.4).
 *
 * <p>Forty lines, and that is the point of the pattern: everything a report does other than
 * choosing its rows is in {@link ReportEngine}, so a dimension really is this small.
 *
 * <h2>The teacher is the exam's author, not the sitting's runner</h2>
 *
 * <p>The two are different people often enough for it to matter (S-35), so the choice has to be
 * made deliberately. It is the <b>author</b>, on E14's precedent and for E14's reason: the
 * frozen statistics describe how a paper was answered, and the paper is the work of whoever
 * wrote it. A teacher who lent her room to a colleague's exam did not set those questions, and a
 * report that credited her with the result would be measuring the wrong person.
 *
 * <p>The other reading — "sittings she ran" — is a legitimate report and would be a legitimate
 * fourth strategy, keyed on {@code exam_executions.created_by}. It would be one class beside
 * this one, three methods on {@link ReportData}, and one line in {@link ReportStrategies}. That
 * is the whole cost, and it is worth stating because it is the concrete form of what S-37 asks
 * for. The wire contract records this as a decision for the lead to confirm at freeze.
 *
 * <p>A coordinator is returned by this dimension's subject list like any other teacher:
 * coordinator-ness is a row in {@code coordinators} rather than a stored role (section 5), and
 * she writes exams the same way.
 */
public final class ByTeacherStrategy implements DimensionStrategy {

    @Override
    public ReportDimension dimension() {
        return ReportDimension.BY_TEACHER;
    }

    @Override
    public List<ReportSubject> subjects(ReportData data) {
        Map<String, Integer> counts =
                data.reportableCounts(ExecutionRepository.ReportGrouping.AUTHOR);
        List<PersonRef> teachers = data.teachers();
        List<ReportSubject> subjects = new ArrayList<>(teachers.size());
        for (PersonRef teacher : teachers) {
            subjects.add(toSubject(teacher, counts));
        }
        return List.copyOf(subjects);
    }

    @Override
    public Optional<ReportSubject> subject(ReportData data, String subjectId) {
        return DimensionStrategy.asUserId(subjectId)
                .flatMap(data::teacher)
                .map(teacher -> toSubject(teacher,
                        data.reportableCounts(ExecutionRepository.ReportGrouping.AUTHOR)));
    }

    @Override
    public List<ExecutionReport> executionsOf(ReportData data, String subjectId) {
        return DimensionStrategy.asUserId(subjectId)
                .map(data::executionsByAuthor)
                .orElseGet(List::of);
    }

    private static ReportSubject toSubject(PersonRef teacher, Map<String, Integer> counts) {
        String id = String.valueOf(teacher.userId());
        return new ReportSubject(id, teacher.fullName(), teacher.username(),
                counts.getOrDefault(id, 0));
    }
}
