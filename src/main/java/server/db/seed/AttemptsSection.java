package server.db.seed;

import org.hibernate.Session;
import server.db.entities.AttemptAnswer;
import server.db.entities.AttemptStatus;
import server.db.entities.ExamAttempt;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Seed §9.1, §9.1.1, §9.2 and §9.2.1: sixteen attempts and their saved answers (E2.15).
 *
 * <h2>A dash means no row, not a null selection</h2>
 *
 * <p>§9.1.1 shows {@code omer.katz} reaching only the first three questions before his time ran
 * out. The other four are <b>absent from {@code attempt_answers} entirely</b>, not present with a
 * null. That distinction is the fixture for H12.4 and F6.9: the grader scores an unreached
 * question as zero, and "never answered" has to be tellable from "answered wrongly" for the
 * checked form to mean anything. His is the only attempt in the seed that distinguishes them, so
 * collapsing the two would quietly remove the only evidence the difference is handled.
 *
 * <h2>Finalisation goes through the same update the service uses</h2>
 *
 * <p>{@link ExamAttempt} has a constructor and getters and <b>no mutators at all</b>. That is
 * deliberate: ARCHITECTURE §5 makes attempt finalisation a status-guarded atomic update, so the
 * submit-versus-expiry race is resolved by compare-and-set on the state machine rather than by a
 * lock version. The seed honours that rather than working around it: an attempt is persisted
 * IN_PROGRESS and then finalised with the same {@code where … and status = 'IN_PROGRESS'} update
 * E10 issues. Inserting the final state directly with native SQL would have been shorter and
 * would have meant the seed never exercised the path the product uses.
 *
 * <h2>Timestamps are derived from the document's rule, not chosen</h2>
 *
 * <p>§9's rules table states it: {@code started_at} is the window start plus a small stagger such
 * that start plus solving time lands inside the window, and {@code ended_at} follows from start
 * plus solving time for SUBMITTED attempts and equals the window close for TIMED_OUT ones.
 *
 * <p>That last clause is what makes {@code omer.katz}'s 75 minutes meaningful rather than
 * arbitrary: exam 1 v2 runs 75 minutes, so working backwards from the window close gives him a
 * start of 09:45 and a deadline landing exactly at 11:00. He had the full allotted duration and
 * still did not finish, which is precisely the S-19 story, and the number is a consequence of the
 * rule rather than something anybody picked.
 */
final class AttemptsSection implements SeedSection {

    /** One student's paper: the selections in question order, {@code null} where unreached. */
    private record Paper(String student, int solvingMinutes, AttemptStatus status,
                         List<Integer> selections) { }

    /** One execution's papers, against the question list the document gives for it. */
    private record Sitting(String executionCode, List<String> questions,
                           List<Integer> pinnedVersions, List<Paper> papers) { }

    /** §9.1.1: exam 1 v2's seven questions. 11005 is pinned to v1; the rest take their only one. */
    private static final List<String> EXECUTION_1_QUESTIONS =
            List.of("11001", "11002", "11005", "11007", "11009", "11010", "11011");
    private static final List<Integer> EXECUTION_1_VERSIONS = List.of(1, 1, 1, 1, 1, 1, 1);

    /** §9.2.1: exam 4 v1's seven questions. */
    private static final List<String> EXECUTION_2_QUESTIONS =
            List.of("21001", "21002", "21005", "21006", "21009", "21010", "21011");
    private static final List<Integer> EXECUTION_2_VERSIONS = List.of(1, 1, 1, 1, 1, 1, 1);

    private static final List<Sitting> SITTINGS = List.of(
            new Sitting("4821", EXECUTION_1_QUESTIONS, EXECUTION_1_VERSIONS, List.of(
                    new Paper("lior.gabay", 45, AttemptStatus.SUBMITTED,
                            List.of(1, 2, 1, 3, 1, 2, 3)),
                    new Paper("noa.friedman", 52, AttemptStatus.SUBMITTED,
                            List.of(1, 2, 1, 3, 1, 2, 1)),
                    new Paper("shira.dahan", 61, AttemptStatus.SUBMITTED,
                            List.of(1, 2, 1, 3, 1, 4, 3)),
                    new Paper("daniel.shapira", 58, AttemptStatus.SUBMITTED,
                            List.of(1, 2, 1, 1, 1, 2, 2)),
                    new Paper("itay.regev", 68, AttemptStatus.SUBMITTED,
                            List.of(1, 2, 1, 3, 3, 1, 3)),
                    new Paper("maya.levi", 70, AttemptStatus.SUBMITTED,
                            List.of(1, 2, 1, 2, 1, 3, 1)),
                    new Paper("yael.azulay", 73, AttemptStatus.SUBMITTED,
                            List.of(1, 4, 1, 1, 1, 4, 2)),
                    // The four nulls are the four dashes: no attempt_answers row at all.
                    new Paper("omer.katz", 75, AttemptStatus.TIMED_OUT,
                            java.util.Arrays.asList(1, 2, 1, null, null, null, null)))),

            new Sitting("7390", EXECUTION_2_QUESTIONS, EXECUTION_2_VERSIONS, List.of(
                    new Paper("maya.levi", 41, AttemptStatus.SUBMITTED,
                            List.of(1, 2, 1, 2, 2, 3, 4)),
                    new Paper("eitan.solomon", 47, AttemptStatus.SUBMITTED,
                            List.of(1, 2, 1, 2, 2, 1, 4)),
                    new Paper("noa.friedman", 52, AttemptStatus.SUBMITTED,
                            List.of(1, 2, 1, 2, 2, 1, 1)),
                    new Paper("roni.malka", 44, AttemptStatus.SUBMITTED,
                            List.of(1, 2, 1, 2, 1, 1, 4)),
                    new Paper("itay.regev", 55, AttemptStatus.SUBMITTED,
                            List.of(1, 2, 1, 1, 2, 1, 1)),
                    new Paper("noam.peretz", 38, AttemptStatus.SUBMITTED,
                            List.of(1, 2, 2, 2, 1, 1, 4)),
                    new Paper("omer.katz", 59, AttemptStatus.SUBMITTED,
                            List.of(1, 1, 2, 1, 1, 3, 4)),
                    new Paper("daniel.shapira", 58, AttemptStatus.SUBMITTED,
                            List.of(1, 1, 2, 1, 1, 3, 1)))));

    /** Minutes between one student starting and the next, so starts are not identical. */
    private static final int STAGGER_MINUTES = 1;

    /** Where each answer's save time sits inside the attempt, evenly across the paper. */
    private static final int SAVE_SPREAD_DIVISOR = 8;

    @Override
    public String name() {
        return "9.1-9.2 attempts and answers";
    }

    @Override
    public void load(SeedContext context) {
        Session session = context.session();
        int attempts = 0;
        int answers = 0;

        for (Sitting sitting : SITTINGS) {
            List<Long> executions = SeedLookup.findExecutionByCode(session, sitting.executionCode());
            SeedLookup.require(executions.size() == 1,
                    "execution " + sitting.executionCode() + " should exist exactly once");
            long executionId = executions.get(0);

            Instant opens = SeedLookup.executionOpensAt(session, executionId);
            Instant closes = SeedLookup.executionClosesAt(session, executionId);

            Map<String, Long> questionVersions =
                    versionIds(session, sitting.questions(), sitting.pinnedVersions());

            int index = 0;
            for (Paper paper : sitting.papers()) {
                long studentId = SeedLookup.requireUserId(session, paper.student());
                if (SeedLookup.findAttemptId(session, executionId, studentId).isPresent()) {
                    index++;
                    continue;
                }

                Duration solving = Duration.ofMinutes(paper.solvingMinutes());
                Instant startedAt = paper.status() == AttemptStatus.TIMED_OUT
                        // Work backwards from the close, so the solving time IS the allotted
                        // duration rather than a number someone chose. See the class javadoc.
                        ? closes.minus(solving)
                        : opens.plus(Duration.ofMinutes((long) index * STAGGER_MINUTES));
                Instant endedAt = paper.status() == AttemptStatus.TIMED_OUT
                        ? closes
                        : startedAt.plus(solving);

                ExamAttempt attempt = new ExamAttempt(executionId, studentId, startedAt);
                session.persist(attempt);
                session.flush();
                finalise(session, attempt.getId(), paper.status(), endedAt, paper.solvingMinutes());
                attempts++;

                answers += saveAnswers(session, attempt.getId(), paper, sitting.questions(),
                        questionVersions, startedAt, solving);
                index++;
            }
        }

        context.recordInserts("exam_attempts", attempts);
        context.recordInserts("attempt_answers", answers);
    }

    /**
     * Finalises an attempt the way E10 does: compare-and-set on the state machine.
     *
     * <p>The {@code and status = IN_PROGRESS} clause is not decoration here even though the seed
     * is single-threaded. It is the same statement the service issues, so if that contract ever
     * changes the seed stops working rather than silently diverging from the product.
     */
    private static void finalise(Session session, long attemptId, AttemptStatus status,
                                 Instant endedAt, int actualMinutes) {
        int updated = session.createMutationQuery("""
                        update ExamAttempt a
                           set a.status = :status, a.endedAt = :endedAt,
                               a.actualMinutes = :actualMinutes
                         where a.id = :id and a.status = :inProgress
                        """)
                .setParameter("status", status)
                .setParameter("endedAt", endedAt)
                .setParameter("actualMinutes", actualMinutes)
                .setParameter("id", attemptId)
                .setParameter("inProgress", AttemptStatus.IN_PROGRESS)
                .executeUpdate();

        SeedLookup.require(updated == 1, "finalising attempt " + attemptId + " changed "
                + updated + " rows. The status-guarded update found no IN_PROGRESS attempt, "
                + "which means the attempt was already finalised or never inserted.");
    }

    private static int saveAnswers(Session session, long attemptId, Paper paper,
                                   List<String> questions, Map<String, Long> questionVersions,
                                   Instant startedAt, Duration solving) {
        int saved = 0;
        for (int position = 0; position < questions.size(); position++) {
            Integer selected = paper.selections().get(position);
            if (selected == null) {
                // A dash in the document: no row. Not a row with a null selection.
                continue;
            }
            Instant savedAt = startedAt.plus(solving.multipliedBy(position + 1)
                    .dividedBy(SAVE_SPREAD_DIVISOR));
            session.persist(new AttemptAnswer(attemptId,
                    questionVersions.get(questions.get(position)),
                    selected.byteValue(), savedAt));
            saved++;
        }
        return saved;
    }

    /** Resolves each question to the version this execution's exam actually pinned. */
    private static Map<String, Long> versionIds(Session session, List<String> questions,
                                                List<Integer> versions) {
        Map<String, Long> ids = new java.util.LinkedHashMap<>();
        for (int i = 0; i < questions.size(); i++) {
            ids.put(questions.get(i),
                    SeedLookup.requireQuestionVersionId(session, questions.get(i), versions.get(i)));
        }
        return ids;
    }
}
