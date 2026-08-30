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
 * Seed §9.1 to §9.6.1: thirty-one attempts and their saved answers (E2.15).
 *
 * <h2>⚑ U-43: two fully graded sittings</h2>
 *
 * <p>2026-08-30, live session. {@code 6120} is exam 4 released a week before {@code 7390}, six of
 * its eight Java students; {@code 7745} is the Biology paper, five of its six students. Both are
 * closed, approved and frozen (§9.5, §9.6), and both leave {@code maya.levi} out for the reason
 * §9.4 leaves her out of {@code 3318}, only harder: her My Grades holds <b>exactly one row</b> on
 * a freshly seeded database, which cases 8.2, 9.1 and 17.3 all read, and these two sittings are
 * approved <em>in the seed</em>, so putting her on one would change that count on load with
 * nobody having pressed anything.
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

    /**
     * §9.1.1: exam 1 v2's seven questions. 11005 is pinned to v1; the rest take their only one.
     *
     * <p>Execution 5 uses this same list, because it releases the same exam version (§9.4.1).
     * One constant rather than a copy: the paper is one paper, and two lists that had to agree
     * would be one more place for them to stop agreeing.
     */
    private static final List<String> EXECUTION_1_QUESTIONS =
            List.of("11001", "11002", "11005", "11007", "11009", "11010", "11011");
    private static final List<Integer> EXECUTION_1_VERSIONS = List.of(1, 1, 1, 1, 1, 1, 1);

    /**
     * §9.2.1: exam 4 v1's seven questions.
     *
     * <p>Execution 6 uses this same list, because it releases the same exam version (§9.5.1) -
     * the same reason executions 1 and 5 share {@link #EXECUTION_1_QUESTIONS}.
     */
    private static final List<String> EXECUTION_2_QUESTIONS =
            List.of("21001", "21002", "21005", "21006", "21009", "21010", "21011");
    private static final List<Integer> EXECUTION_2_VERSIONS = List.of(1, 1, 1, 1, 1, 1, 1);

    /**
     * §9.6.1: exam 7 v1's five questions, in the order {@code ExamsSection} composes them ⚑
     * (U-43).
     *
     * <p><b>Five, not seven, and the order is easy-easy-medium-medium-hard rather than the bank's
     * display-id order.</b> {@code attempt_answers} rows are matched to the paper by question
     * version id, so this list has to be the exam version's composition and not the bank's
     * sorting: 31001, 31004, 31002, 31005, 31003 is what §8.1 lists, and it is what the points
     * 15, 15, 20, 20, 30 line up against.
     */
    private static final List<String> EXECUTION_7_QUESTIONS =
            List.of("31001", "31004", "31002", "31005", "31003");
    private static final List<Integer> EXECUTION_7_VERSIONS = List.of(1, 1, 1, 1, 1);

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
                            List.of(1, 1, 2, 1, 1, 3, 1)))),

            // ⚑ U-34, §9.4.1. Exam 1 v2 again, so the same question list and the same key.
            // Four Algebra students, deliberately not maya.levi: her My Grades holds exactly
            // one row on a fresh seed and cases 8.2, 9.1 and 17.3 all read that count.
            // Nobody timed out, so every one of the twenty-eight cells is a row.
            new Sitting("3318", EXECUTION_1_QUESTIONS, EXECUTION_1_VERSIONS, List.of(
                    new Paper("noa.friedman", 49, AttemptStatus.SUBMITTED,
                            List.of(1, 2, 1, 2, 1, 2, 3)),
                    new Paper("shira.dahan", 57, AttemptStatus.SUBMITTED,
                            List.of(1, 2, 1, 3, 2, 2, 1)),
                    new Paper("daniel.shapira", 63, AttemptStatus.SUBMITTED,
                            List.of(3, 2, 1, 1, 1, 2, 1)),
                    new Paper("itay.regev", 71, AttemptStatus.SUBMITTED,
                            List.of(1, 4, 1, 3, 3, 1, 2)))),

            // ⚑ U-43, §9.5.1. Exam 4 v1 again, a week before 7390, so the same question list and
            // the same key. Six Java students, deliberately not maya.levi and not
            // daniel.shapira, so the roster is visibly unlike 7390's eight. Nobody timed out, so
            // every one of the forty-two cells is a row. No row here repeats the same student's
            // row in §9.2.1, and all six of them sat that paper too.
            new Sitting("6120", EXECUTION_2_QUESTIONS, EXECUTION_2_VERSIONS, List.of(
                    new Paper("eitan.solomon", 38, AttemptStatus.SUBMITTED,
                            List.of(1, 2, 1, 2, 2, 3, 1)),
                    new Paper("noa.friedman", 44, AttemptStatus.SUBMITTED,
                            List.of(1, 2, 1, 2, 1, 1, 4)),
                    new Paper("roni.malka", 47, AttemptStatus.SUBMITTED,
                            List.of(1, 1, 1, 1, 2, 1, 4)),
                    new Paper("itay.regev", 51, AttemptStatus.SUBMITTED,
                            List.of(2, 2, 2, 2, 1, 3, 1)),
                    new Paper("noam.peretz", 42, AttemptStatus.SUBMITTED,
                            List.of(1, 3, 3, 3, 2, 2, 4)),
                    new Paper("omer.katz", 52, AttemptStatus.SUBMITTED,
                            List.of(2, 1, 1, 1, 1, 3, 2)))),

            // ⚑ U-43, §9.6.1. The Biology paper: five questions, five students, twenty-five
            // rows. Its points are 15, 15, 20, 20, 30, so the totals these selections produce
            // (100, 80, 70, 55, 50) include two - 80 and 50 - that no 6x15 + 10 paper in this
            // dataset can reach at all.
            new Sitting("7745", EXECUTION_7_QUESTIONS, EXECUTION_7_VERSIONS, List.of(
                    new Paper("tal.harari", 31, AttemptStatus.SUBMITTED,
                            List.of(1, 4, 2, 1, 3)),
                    new Paper("lior.gabay", 36, AttemptStatus.SUBMITTED,
                            List.of(1, 4, 2, 3, 3)),
                    new Paper("noa.friedman", 38, AttemptStatus.SUBMITTED,
                            List.of(1, 4, 2, 1, 1)),
                    new Paper("shira.dahan", 41, AttemptStatus.SUBMITTED,
                            List.of(1, 1, 2, 1, 2)),
                    new Paper("omer.katz", 44, AttemptStatus.SUBMITTED,
                            List.of(2, 1, 2, 4, 3)))));

    /** Minutes between one student starting and the next, so starts are not identical. */
    private static final int STAGGER_MINUTES = 1;

    /** Where each answer's save time sits inside the attempt, evenly across the paper. */
    private static final int SAVE_SPREAD_DIVISOR = 8;

    @Override
    public String name() {
        return "9.1-9.4 attempts and answers";
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
