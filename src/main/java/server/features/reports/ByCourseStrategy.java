package server.features.reports;

import common.dto.report.ReportDimension;
import common.dto.report.ReportSubject;
import server.db.projections.CourseSummary;
import server.db.projections.ExecutionReport;
import server.db.repos.ExecutionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Compare the sittings of one course's exams" (Logic tier, E15.3 — F9.4).
 *
 * <p>The dimension a principal actually opens first in practice: it answers "is this course
 * getting easier or harder", across every teacher who wrote for it and every year it ran.
 *
 * <p>Keyed on the exam's course rather than the version's, because a course belongs to the exam
 * identity row and does not move between versions (section 5). A course with no exam at all is
 * still listed, with a count of zero, so the picker distinguishes "this course has never had an
 * exam close" from "this course does not exist".
 *
 * <p>Codes are stripped before they are used. {@code courses.code2} is {@code CHAR(2)} under a
 * PAD SPACE collation, so a padded code matches the row in SQL while failing Java equality
 * against the id the picker issued, which would resolve a subject whose label then disagreed
 * with its rows.
 */
public final class ByCourseStrategy implements DimensionStrategy {

    @Override
    public ReportDimension dimension() {
        return ReportDimension.BY_COURSE;
    }

    @Override
    public List<ReportSubject> subjects(ReportData data) {
        Map<String, Integer> counts =
                data.reportableCounts(ExecutionRepository.ReportGrouping.COURSE);
        List<CourseSummary> courses = data.courses();
        List<ReportSubject> subjects = new ArrayList<>(courses.size());
        for (CourseSummary course : courses) {
            subjects.add(toSubject(course, counts));
        }
        return List.copyOf(subjects);
    }

    @Override
    public Optional<ReportSubject> subject(ReportData data, String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            return Optional.empty();
        }
        return data.course(subjectId.strip())
                .map(course -> toSubject(course,
                        data.reportableCounts(ExecutionRepository.ReportGrouping.COURSE)));
    }

    @Override
    public List<ExecutionReport> executionsOf(ReportData data, String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            return List.of();
        }
        return data.executionsByCourse(subjectId.strip());
    }

    private static ReportSubject toSubject(CourseSummary course, Map<String, Integer> counts) {
        String code = course.code().strip();
        return new ReportSubject(code, course.name(), "Course " + code,
                counts.getOrDefault(code, 0));
    }
}
