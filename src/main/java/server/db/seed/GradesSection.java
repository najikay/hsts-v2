package server.db.seed;

import org.hibernate.Session;
import server.db.entities.Grade;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Seed §9.1 to §9.6: thirty-one grades in two deliberately different states (E2.15).
 *
 * <h2>⚑ U-43: two more approved sittings, and a second approver</h2>
 *
 * <p>2026-08-30, live session. {@code 6120}'s six grades are approved by {@code avi.mizrahi} and
 * {@code 7745}'s five by {@code galit.stern}, each of them the teacher who released the sitting
 * (T-8.2). Until this round {@code dana.cohen} was the only {@code approved_by} value in the
 * dataset, so a query that had accidentally hardcoded her would have passed everything.
 *
 * <p><b>Neither carries a comment or an override</b>, and both are approved with
 * {@code final_score} equal to {@code auto_score}. S-22 and S-23 are demonstrated on
 * {@code 4821}, where four commented grades sit beside four uncommented ones and one override
 * moves a real student across the pass mark; repeating either here would add rows to those
 * sweeps without adding a state. What these two sittings add is a second and a third <b>frozen
 * statistics record</b>, which is the whole of what U-43 was for.
 *
 * <h2>Three executions, and still the two halves of the grading story</h2>
 *
 * <p>Execution 1's eight grades are <b>APPROVED</b>: the machine scored, a teacher approved, and
 * only then does a student see anything (S-24). Execution 2's eight and execution 5's four are
 * <b>AUTO</b> with {@code final_score}, {@code override_reason}, {@code teacher_comment},
 * {@code approved_by} and {@code approved_at} all null, which is what "awaiting grading" means
 * and is the fixture T-8.2 is demonstrated on. Without the second set there is nothing for a
 * teacher to approve during the demo; without the first there is nothing for a student to look
 * at.
 *
 * <p><b>⚑ U-34: the awaiting-grading set had to exist twice, once per teacher.</b> Execution 2
 * belongs to {@code avi.mizrahi}, and the grading queue is scoped to the teacher who released
 * the sitting, so {@code dana.cohen} - the account the demo signs in as on the teacher side -
 * opened Grading and read "Nothing to grade". Correct, and empty. Execution 5's four AUTO
 * grades (§9.4) are the same fixture released by her, so both teachers have one sitting to
 * approve and neither screen is blank on day one.
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
 * <p>Executions 2 and 5 stay comment-free. Nothing there is approved, and S-24 means no student
 * can read any of it; a comment on a grade nobody can open would be a row that contradicts the
 * fixture it lives in. Executions 6 and 7 are comment-free for the opposite reason: everything
 * there <em>is</em> approved and readable, and eleven more commented grades would drown the four
 * on {@code 4821} that the comment sweep is written against.
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
                    new SeedGrade("daniel.shapira", 30, null, null, null))),

            // ⚑ U-34, §9.4. dana.cohen's own awaiting-grading sitting. AUTO, no final, no
            // override, no comment, nothing approved: comment-free for §9.2's reason, that
            // S-24 means no student can open any of these yet.
            new Sitting("3318", false, null, List.of(
                    new SeedGrade("noa.friedman", 85, null, null, null),
                    new SeedGrade("shira.dahan", 75, null, null, null),
                    new SeedGrade("daniel.shapira", 60, null, null, null),
                    new SeedGrade("itay.regev", 45, null, null, null))),

            // ⚑ U-43, §9.5. Approved by avi.mizrahi, who released it. Finals equal autos: no
            // override here, so the frozen mean of exactly 55 is the mean of the machine's own
            // scores. 30, 40, 45, 55, 70, 90 sum to 330.
            new Sitting("6120", true, "avi.mizrahi", List.of(
                    new SeedGrade("eitan.solomon", 90, null, null, null),
                    new SeedGrade("noa.friedman", 70, null, null, null),
                    new SeedGrade("roni.malka", 55, null, null, null),
                    new SeedGrade("itay.regev", 45, null, null, null),
                    new SeedGrade("noam.peretz", 40, null, null, null),
                    new SeedGrade("omer.katz", 30, null, null, null))),

            // ⚑ U-43, §9.6. Approved by galit.stern, who wrote the paper and released it. She is
            // also the coordinator of subject 30, which FacultySection records as the one
            // consequence of the dual-hat shape being taken to its limit. 50, 55, 70, 80, 100.
            new Sitting("7745", true, "galit.stern", List.of(
                    new SeedGrade("tal.harari", 100, null, null, null),
                    new SeedGrade("lior.gabay", 80, null, null, null),
                    new SeedGrade("noa.friedman", 70, null, null, null),
                    new SeedGrade("shira.dahan", 55, null, null, null),
                    new SeedGrade("omer.katz", 50, null, null, null))));

    @Override
    public String name() {
        return "9.1-9.4 grades";
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
