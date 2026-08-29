package server.db.seed;

import org.hibernate.Session;
import server.db.entities.Grade;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Seed §9.1 and §9.2: sixteen grades in two deliberately different states (E2.15).
 *
 * <h2>The two executions are the two halves of the grading story</h2>
 *
 * <p>Execution 1's eight grades are <b>APPROVED</b>: the machine scored, a teacher approved, and
 * only then does a student see anything (S-24). Execution 2's eight are <b>AUTO</b> with
 * {@code final_score}, {@code override_reason}, {@code teacher_comment}, {@code approved_by} and
 * {@code approved_at} all null, which is what "awaiting grading" means and is the fixture T-8.2
 * is demonstrated on. Without the second set there is nothing for a teacher to approve during
 * the demo; without the first there is nothing for a student to look at.
 *
 * <h2>The override never overwrites the machine's score</h2>
 *
 * <p>{@code yael.azulay} scored 45 and was raised to 55. {@link Grade#override} writes
 * {@code final_score} and leaves {@code auto_score} untouched, per S-23 and the entity's own
 * contract, so "what the machine computed" stays answerable forever. That matters here more than
 * usually: 45 is a fail and 55 is a pass, so the override is the single row that moves execution
 * 1's pass rate from 6/8 to the 7/8 frozen in its statistics. A loader that wrote 55 into
 * {@code auto_score} would produce the same pass rate from data that no longer records a teacher
 * ever having intervened.
 *
 * <p>The reason is not decoration either: T-8.3 requires a change to carry an explanation, and
 * the seeded text is the one a reviewer will read on the grade-review screen.
 *
 * <h2>Four of the eight approved grades carry a comment, and four do not</h2>
 *
 * <p>Added 2026-08-29 (manual round 2). Until then the only {@code teacher_comment} in the
 * dataset was {@code yael.azulay}'s, which rides the override, so the demo student
 * {@code maya.levi} opened her Algebra midterm and saw the note line render as nothing: S-22 was
 * present in the schema, on the wire and on both screens, and absent from the one grade a
 * reviewer actually opens. Three comments without an override now sit beside it
 * ({@code lior.gabay}, {@code maya.levi}, {@code omer.katz}), which also separates the two
 * things that used to be welded together here: a comment is not a consequence of a change of
 * score. The other four stay empty on purpose, because "no note from the teacher" is a state
 * the card has to render too and one sitting has to show both.
 *
 * <p>Execution 2 stays comment-free. Nothing there is approved, and S-24 means no student can
 * read any of it; a comment on a grade nobody can open would be a row that contradicts the
 * fixture it lives in.
 *
 * <h2>Who approves, and why it is not the coordinator</h2>
 *
 * <p>§9's rules table names the <b>executing teacher</b>, {@code dana.cohen}, who released the
 * exam and owns its grades (T-8.2). The coordinator approves <em>exams</em>; the teacher approves
 * <em>grades</em>. Those are deliberately different people in this seed, and seeding the
 * coordinator here would quietly merge two roles the product keeps apart.
 */
final class GradesSection implements SeedSection {

    /** §9's rule: approved two days after the execution closed. */
    private static final Duration APPROVED_AFTER_CLOSE = Duration.ofDays(2);

    private record SeedGrade(String student, int auto, Integer overrideTo,
                             String overrideReason, String teacherComment) { }

    private record Sitting(String executionCode, boolean approved, String approver,
                           List<SeedGrade> grades) { }

    private static final List<Sitting> SITTINGS = List.of(
            new Sitting("4821", true, "dana.cohen", List.of(
                    // Three comments without an override, added 2026-08-29 (manual round 2).
                    // Transcribed from §9.1's comment table, not composed here.
                    new SeedGrade("lior.gabay", 100, null, null,
                            "Full marks with time to spare, so the harder practice set is the "
                                    + "natural next step."),
                    new SeedGrade("noa.friedman", 90, null, null, null),
                    new SeedGrade("shira.dahan", 85, null, null, null),
                    new SeedGrade("daniel.shapira", 75, null, null, null),
                    new SeedGrade("itay.regev", 70, null, null, null),
                    // The demo student (DEMO_DAY §2.3): hers is the grade a reviewer opens, and
                    // without a comment on it the checked form's note line renders as nothing.
                    new SeedGrade("maya.levi", 60, null, null,
                            "Solid on the basics, and the harder inequality questions are where "
                                    + "to put the next round of practice."),
                    // The one override in the seed. 45 fails, 55 passes: this row is what
                    // moves the frozen pass rate from 6/8 to 7/8.
                    new SeedGrade("yael.azulay", 45, 55,
                            // Transcribed from §9.1, not transformed. This said "Document writes
                            // an em dash; PRD 4.1 forbids it" until 2026-08-27: it was true, and
                            // B-13 fixed the document rather than the loader, so the deviation
                            // it described no longer exists. SeedLoadedDbContract.overrideTextMatches
                            // now holds the two together, which it did not while this comment was
                            // the only record that they differed.
                            "Question 11011 has a correct solution with a sign error on the last line, so partial credit was given.",
                            "A clear improvement on inequalities. Worth revising the domain of definition."),
                    new SeedGrade("omer.katz", 45, null, null,
                            "Everything reached was correct, so pacing rather than the algebra "
                                    + "is what to work on."))),

            new Sitting("7390", false, null, List.of(
                    new SeedGrade("maya.levi", 100, null, null, null),
                    new SeedGrade("eitan.solomon", 85, null, null, null),
                    new SeedGrade("noa.friedman", 75, null, null, null),
                    new SeedGrade("roni.malka", 70, null, null, null),
                    new SeedGrade("itay.regev", 60, null, null, null),
                    new SeedGrade("noam.peretz", 55, null, null, null),
                    new SeedGrade("omer.katz", 40, null, null, null),
                    new SeedGrade("daniel.shapira", 30, null, null, null))));

    @Override
    public String name() {
        return "9.1-9.2 grades";
    }

    @Override
    public void load(SeedContext context) {
        Session session = context.session();
        int inserted = 0;

        for (Sitting sitting : SITTINGS) {
            List<Long> executions = SeedLookup.findExecutionByCode(session, sitting.executionCode());
            SeedLookup.require(executions.size() == 1,
                    "execution " + sitting.executionCode() + " should exist exactly once");
            long executionId = executions.get(0);
            Instant approvedAt = SeedLookup.executionClosesAt(session, executionId)
                    .plus(APPROVED_AFTER_CLOSE);

            for (SeedGrade grade : sitting.grades()) {
                long studentId = SeedLookup.requireUserId(session, grade.student());
                long attemptId = SeedLookup.findAttemptId(session, executionId, studentId)
                        .orElseThrow(() -> new IllegalStateException("seed: no attempt for "
                                + grade.student() + " on execution " + sitting.executionCode()
                                + ". Grades load after attempts; the section order is wrong."));

                if (SeedLookup.findGradeId(session, attemptId).isPresent()) {
                    continue;
                }

                Grade row = new Grade(attemptId, grade.auto());
                if (grade.overrideTo() != null) {
                    row.override(grade.overrideTo(), grade.overrideReason());
                }
                if (grade.teacherComment() != null) {
                    row.setTeacherComment(grade.teacherComment());
                }
                if (sitting.approved()) {
                    row.approve(SeedLookup.requireUserId(session, sitting.approver()), approvedAt);
                }
                session.persist(row);
                inserted++;
            }
        }

        context.recordInserts("grades", inserted);
    }
}
