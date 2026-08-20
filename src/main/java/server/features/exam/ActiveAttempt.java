package server.features.exam;

import java.time.Instant;

/**
 * One attempt that is happening right now (Logic tier, E10.7 — C-4).
 *
 * <p>Held in memory by {@link AttemptRegistry} for the length of a sitting. It exists for
 * the study bot: C-4 locks a course's own bot while a student is sitting an exam of that
 * course, and asking the database on every chat message for something that changes twice
 * per student per exam would be a query per keystroke-ish.
 *
 * <p>It carries the course <em>and</em> the teacher, because the two C-4 branches need
 * different halves: the lockout needs to know which course she is sitting, and the
 * cross-course integrity alert needs to know whom to tell.
 *
 * <p><b>The registry is a cache of a fact the database owns.</b> After a server restart it
 * is empty until attempts are re-armed from the database, and the authoritative answer to
 * "is this attempt live" is always the row. Nothing security-relevant is decided from this
 * alone.
 *
 * @param attemptId          the attempt
 * @param executionId        the execution being sat
 * @param studentId          who is sitting it
 * @param courseCode         the exam's course, which is the course whose bot is locked (C-4)
 * @param courseName         that course's display name, for the lockout message
 * @param examName           the exam's name, for the messages
 * @param executingTeacherId who released the execution; the recipient of an integrity alert
 * @param startedAt          when the sitting began
 */
public record ActiveAttempt(long attemptId,
                            long executionId,
                            long studentId,
                            String courseCode,
                            String courseName,
                            String examName,
                            long executingTeacherId,
                            Instant startedAt) {

    public ActiveAttempt {
        courseCode = courseCode == null ? "" : courseCode;
        courseName = courseName == null || courseName.isBlank() ? courseCode : courseName;
        examName = examName == null ? "" : examName;
    }

    /**
     * @param otherCourseCode a course a student is trying to open a bot for
     * @return {@code true} when that is this exam's own course, i.e. the locked case (C-4)
     */
    public boolean isSameCourseAs(String otherCourseCode) {
        return otherCourseCode != null && courseCode.equalsIgnoreCase(otherCourseCode.trim());
    }
}
