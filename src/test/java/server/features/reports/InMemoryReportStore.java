package server.features.reports;

import server.db.entities.ExecutionStats;
import server.db.entities.ExecutionStatus;
import server.db.projections.CourseSummary;
import server.db.projections.ExecutionReport;
import server.db.projections.PersonRef;
import server.db.projections.SchoolExam;
import server.db.repos.ExecutionRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A {@link ReportStore} in a {@code HashMap}, for the unit tests of every E15 rule.
 *
 * <p>{@link ReportEngine} and the strategies hold all the rules and reach the database only
 * through {@link ReportData}, so this fixture is what lets the population rules, the H15.2
 * exclusion and the frozen-statistics pin be proven in milliseconds and without a schema.
 *
 * <p>It is deliberately faithful about the four things those rules depend on:
 *
 * <ul>
 *   <li><b>Only CLOSED sittings with frozen statistics are ever returned</b>, exactly as the
 *       {@code REPORTABLE} clause does. A fixture that returned a live or cancelled sitting and
 *       let the engine filter would let a broken query pass its own test.</li>
 *   <li><b>The counts are built from the same filter</b>, so a fixture cannot claim a teacher
 *       has three sittings and then hand back two.</li>
 *   <li><b>Rows come back oldest first</b>, which is what the report's ordering promise
 *       depends on.</li>
 *   <li><b>The participant map omits zeros.</b> The real grouped query returns no row for a
 *       sitting nobody joined, and a fixture that helpfully inserted a zero would hide a
 *       {@code getOrDefault} that was never written.</li>
 * </ul>
 */
final class InMemoryReportStore implements ReportStore, ReportData {

    /** One seeded sitting, before the reportable filter is applied to it. */
    private record Sitting(long executionId, String code, Instant openAt, String examName,
                           String courseCode, String courseName, long authorId,
                           ExecutionStatus status, ExecutionStats stats) { }

    private final Map<Long, PersonRef> teachers = new LinkedHashMap<>();
    private final Map<Long, PersonRef> students = new LinkedHashMap<>();
    private final Map<String, CourseSummary> courses = new LinkedHashMap<>();
    private final Map<Long, Sitting> sittings = new LinkedHashMap<>();
    private final Map<Long, Set<Long>> satBy = new LinkedHashMap<>();
    private final Map<Long, Map<Long, Integer>> approvedScores = new LinkedHashMap<>();
    private final Map<Long, Integer> participants = new LinkedHashMap<>();
    private final Map<String, SchoolExam> examCatalogue = new LinkedHashMap<>();

    // ===================== Building the world ============================

    InMemoryReportStore teacher(long userId, String fullName, String username) {
        teachers.put(userId, new PersonRef(userId, fullName, username));
        return this;
    }

    InMemoryReportStore student(long userId, String fullName, String username) {
        students.put(userId, new PersonRef(userId, fullName, username));
        return this;
    }

    InMemoryReportStore course(String code, String name) {
        courses.put(code, new CourseSummary(code, name));
        return this;
    }

    /**
     * Adds one sitting.
     *
     * @param status the stored status; anything but {@code CLOSED} makes it unreportable, which
     *               is how the H15.2 exclusion is exercised rather than assumed
     * @param stats  the frozen statistics, or {@code null} for a sitting whose grading never
     *               finished
     */
    InMemoryReportStore sitting(long executionId, String code, Instant openAt, String examName,
                                String courseCode, long authorId, ExecutionStatus status,
                                ExecutionStats stats) {
        CourseSummary course = courses.get(courseCode);
        if (course == null) {
            throw new IllegalStateException("Seed the course before its sittings: " + courseCode);
        }
        sittings.put(executionId, new Sitting(executionId, code, openAt, examName, courseCode,
                course.name(), authorId, status, stats));
        return this;
    }

    /**
     * Adds one exam to the school's catalogue (E15.2).
     *
     * <p>Deliberately independent of {@link #sitting}: the browse's Exams tab lists exams that
     * have never been released as readily as ones that have, and a fixture that derived the
     * catalogue from the sittings could not show that.
     */
    InMemoryReportStore exam(String displayId, String courseCode, String examName,
                             String authorName, int versions, Instant lastVersionAt) {
        return exam(displayId, courseCode, examName, authorName, versions, lastVersionAt,
                Long.parseLong(displayId));
    }

    /**
     * The same, naming the latest version's id (2026-08-30, live session, U-44).
     *
     * <p>The convenience above derives one from the display id, because most cases do not care
     * which id it is and only that the row carries one it can be opened by. A case about the
     * opening itself names it.
     */
    InMemoryReportStore exam(String displayId, String courseCode, String examName,
                             String authorName, int versions, Instant lastVersionAt,
                             long latestVersionId) {
        CourseSummary course = courses.get(courseCode);
        if (course == null) {
            throw new IllegalStateException("Seed the course before its exams: " + courseCode);
        }
        examCatalogue.put(displayId, new SchoolExam(displayId, courseCode, course.name(),
                examName, authorName, versions, lastVersionAt, latestVersionId));
        return this;
    }

    /** Records who sat one sitting, and therefore how many participants it has. */
    InMemoryReportStore sat(long executionId, long... studentIds) {
        Set<Long> who = satBy.computeIfAbsent(executionId, key -> new LinkedHashSet<>());
        for (long studentId : studentIds) {
            who.add(studentId);
        }
        participants.put(executionId, who.size());
        return this;
    }

    /** Overrides the participant count, for the "somebody's paper was never marked" case. */
    InMemoryReportStore participants(long executionId, int count) {
        participants.put(executionId, count);
        return this;
    }

    // ===================== The seam ======================================

    @Override
    public <T> T inTx(Function<ReportData, T> work) {
        return work.apply(this);
    }

    @Override
    public List<PersonRef> teachers() {
        return byName(teachers.values());
    }

    @Override
    public Optional<PersonRef> teacher(long teacherId) {
        return Optional.ofNullable(teachers.get(teacherId));
    }

    @Override
    public List<ExecutionReport> executionsByAuthor(long teacherId) {
        return reportable(sitting -> sitting.authorId() == teacherId);
    }

    @Override
    public List<CourseSummary> courses() {
        return List.copyOf(courses.values());
    }

    @Override
    public Optional<CourseSummary> course(String courseCode) {
        return Optional.ofNullable(courses.get(courseCode));
    }

    @Override
    public List<ExecutionReport> executionsByCourse(String courseCode) {
        return reportable(sitting -> sitting.courseCode().equals(courseCode));
    }

    @Override
    public List<PersonRef> students() {
        return byName(students.values());
    }

    @Override
    public Optional<PersonRef> student(long studentId) {
        return Optional.ofNullable(students.get(studentId));
    }

    @Override
    public Map<Long, Integer> approvedScoresByStudent(long studentId) {
        return approvedScores.getOrDefault(studentId, Map.of());
    }

    /** Fixture: records her approved effective score on one sitting (REPORTS A2). */
    void approvedScore(long executionId, long studentId, int score) {
        approvedScores.computeIfAbsent(studentId, id -> new LinkedHashMap<>())
                .put(executionId, score);
    }

    @Override
    public List<ExecutionReport> executionsByStudent(long studentId) {
        return reportable(sitting -> satBy.getOrDefault(sitting.executionId(), Set.of())
                .contains(studentId));
    }

    @Override
    public Map<String, Integer> reportableCounts(ExecutionRepository.ReportGrouping grouping) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Sitting sitting : sittings.values()) {
            if (!isReportable(sitting)) {
                continue;
            }
            switch (grouping) {
                case AUTHOR -> bump(counts, String.valueOf(sitting.authorId()));
                case COURSE -> bump(counts, sitting.courseCode());
                case STUDENT -> {
                    for (Long studentId : satBy.getOrDefault(sitting.executionId(), Set.of())) {
                        bump(counts, String.valueOf(studentId));
                    }
                }
            }
        }
        return counts;
    }

    @Override
    public Map<Long, Integer> participantsByExecution(Collection<Long> executionIds) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Long id : executionIds) {
            Integer count = participants.get(id);
            if (count != null && count > 0) {
                counts.put(id, count);
            }
        }
        return counts;
    }

    // ===================== The data browser (E15.2) ======================

    @Override
    public List<SchoolExam> allExams() {
        List<SchoolExam> sorted = new ArrayList<>(examCatalogue.values());
        sorted.sort(Comparator.comparing(SchoolExam::displayId));
        return List.copyOf(sorted);
    }

    /**
     * Every reportable sitting, <b>newest first</b>.
     *
     * <p>Faithful about the ordering as well as about the filter, because the browse's order is
     * the opposite of the reports' and a fixture that returned them oldest first would let a
     * query with the wrong {@code order by} pass its own test.
     */
    @Override
    public List<ExecutionReport> allClosedSittings() {
        List<ExecutionReport> rows = new ArrayList<>(reportable(sitting -> true));
        java.util.Collections.reverse(rows);
        return List.copyOf(rows);
    }

    // ===================== The reportable filter =========================

    /** The fixture's copy of the repository's {@code REPORTABLE} clause, and its only one. */
    private static boolean isReportable(Sitting sitting) {
        return sitting.status() == ExecutionStatus.CLOSED && sitting.stats() != null;
    }

    private List<ExecutionReport> reportable(java.util.function.Predicate<Sitting> matches) {
        List<Sitting> found = new ArrayList<>();
        for (Sitting sitting : sittings.values()) {
            if (isReportable(sitting) && matches.test(sitting)) {
                found.add(sitting);
            }
        }
        found.sort(Comparator.comparing(Sitting::openAt)
                .thenComparingLong(Sitting::executionId));
        List<ExecutionReport> rows = new ArrayList<>(found.size());
        for (Sitting sitting : found) {
            rows.add(new ExecutionReport(sitting.executionId(), sitting.code(), sitting.openAt(),
                    sitting.openAt().plusSeconds(7200), sitting.examName(), sitting.courseCode(),
                    sitting.courseName(), sitting.stats()));
        }
        return List.copyOf(rows);
    }

    private static List<PersonRef> byName(Collection<PersonRef> people) {
        List<PersonRef> sorted = new ArrayList<>(people);
        sorted.sort(Comparator.comparing(PersonRef::fullName)
                .thenComparingLong(PersonRef::userId));
        return List.copyOf(sorted);
    }

    private static void bump(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }
}
