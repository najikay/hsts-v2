package server.features.bot;

import common.dto.exam.IntegrityFlag;
import server.features.exam.ActiveAttempt;
import server.features.exam.AttemptTracker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The C-4 seam, scripted (E16.8 — ADR-018).
 *
 * <p>{@code AttemptTracker} is the whole of what the study bot knows about exams,
 * so faking it is the whole of what a bot test needs in order to drive both C-4
 * branches. That is the payoff of the seam being three methods wide rather than a
 * reference to {@code AttemptService}: no execution, no timer, no database, and
 * still the real interface the production code calls.
 */
final class FakeAttemptTracker implements AttemptTracker {

    private final List<ActiveAttempt> live = new ArrayList<>();

    /** Every {@code reportCrossCourseBotUse} call, so a test can assert it happened once. */
    final List<String> reports = new ArrayList<>();

    /** What the next report should answer; {@code true} means an alert was raised. */
    boolean reportRaisesAlert = true;

    /** Puts the student mid-attempt in a course. */
    FakeAttemptTracker sitting(long studentId, String courseCode, String examName) {
        live.add(new ActiveAttempt(live.size() + 1L, 5000L + live.size(), studentId,
                courseCode, courseCode + " course", examName, 1001L,
                Instant.parse("2026-08-20T09:00:00Z")));
        return this;
    }

    @Override
    public Set<String> coursesInProgressFor(long studentId) {
        Set<String> codes = new LinkedHashSet<>();
        for (ActiveAttempt attempt : activeAttemptsFor(studentId)) {
            codes.add(attempt.courseCode());
        }
        return codes;
    }

    @Override
    public List<ActiveAttempt> activeAttemptsFor(long studentId) {
        return live.stream().filter(attempt -> attempt.studentId() == studentId).toList();
    }

    @Override
    public Optional<ActiveAttempt> activeAttemptFor(long studentId, String courseCode) {
        return activeAttemptsFor(studentId).stream()
                .filter(attempt -> attempt.isSameCourseAs(courseCode))
                .findFirst();
    }

    @Override
    public boolean reportCrossCourseBotUse(long studentId, String courseCode, String courseName) {
        reports.add(studentId + ":" + courseCode);
        return reportRaisesAlert;
    }

    @Override
    public Optional<IntegrityFlag> flagOf(long attemptId) {
        return Optional.empty();
    }

    @Override
    public void addListener(Listener listener) {
        // Nothing in these tests subscribes; the production wiring does.
    }
}
