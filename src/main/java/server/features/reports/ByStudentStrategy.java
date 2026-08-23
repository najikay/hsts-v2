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
 * "Compare the sittings one student sat" (Logic tier, E15.3 — F9.4).
 *
 * <h2>What this report is about, and what it deliberately is not</h2>
 *
 * <p>It compares the <b>classes</b> she sat with, not her own marks. Every row carries the
 * sitting's frozen statistics — the class mean, median, σ, distribution and pass rate — exactly
 * as the other two dimensions do, and none of them is her score. That is not an omission to be
 * filled in later: F9.4 asks for "avg/median/decile distribution compared across executions",
 * and the principal's role is a read of school-wide statistics (F9.3, spec 7.3.1), not a browse
 * of one child's grades. Her own marks are F9.1's screen, gated on being her.
 *
 * <p>What the dimension is genuinely for is the shape of a student's exam history: which
 * sittings she was in, how hard each of them turned out to be, and whether the sittings a
 * particular student keeps landing in are the difficult ones.
 *
 * <p>Membership is an <b>attempt</b>, not a grade, and the query says so. A paper that was never
 * marked still means she was in the room; making the grade the join would drop her from her own
 * report without anything on screen indicating that it had happened.
 */
public final class ByStudentStrategy implements DimensionStrategy {

    @Override
    public ReportDimension dimension() {
        return ReportDimension.BY_STUDENT;
    }

    @Override
    public List<ReportSubject> subjects(ReportData data) {
        Map<String, Integer> counts =
                data.reportableCounts(ExecutionRepository.ReportGrouping.STUDENT);
        List<PersonRef> students = data.students();
        List<ReportSubject> subjects = new ArrayList<>(students.size());
        for (PersonRef student : students) {
            subjects.add(toSubject(student, counts));
        }
        return List.copyOf(subjects);
    }

    @Override
    public Optional<ReportSubject> subject(ReportData data, String subjectId) {
        return DimensionStrategy.asUserId(subjectId)
                .flatMap(data::student)
                .map(student -> toSubject(student,
                        data.reportableCounts(ExecutionRepository.ReportGrouping.STUDENT)));
    }

    @Override
    public List<ExecutionReport> executionsOf(ReportData data, String subjectId) {
        return DimensionStrategy.asUserId(subjectId)
                .map(data::executionsByStudent)
                .orElseGet(List::of);
    }

    private static ReportSubject toSubject(PersonRef student, Map<String, Integer> counts) {
        String id = String.valueOf(student.userId());
        return new ReportSubject(id, student.fullName(), student.username(),
                counts.getOrDefault(id, 0));
    }
}
