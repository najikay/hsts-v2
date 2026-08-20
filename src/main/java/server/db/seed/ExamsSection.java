package server.db.seed;

import org.hibernate.Session;
import server.db.entities.Exam;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionQuestion;
import server.db.entities.ExamVersionStatus;

import java.time.Instant;
import java.util.List;

/**
 * Seed §8: six exams in mixed states, their versions, and what each version contains (E2.15).
 *
 * <h2>Exam 1 is the versioning showpiece, and the pin is the point</h2>
 *
 * <p>Version 1 was rejected with a reason, version 2 fixed exactly what the reason named, five
 * questions at 20 points became seven at 15 and 10, and v2 is what got approved and released.
 * The rejected v1 stays queryable (C-2, ADR-011).
 *
 * <p><b>Both versions of exam 1 reference question 11005 at version 1, never the latest.</b>
 * 11005 has a v2 in the bank, so this is the row that proves a released exam is pinned to the
 * version it was built from (S-14, C-2) rather than drifting when the question is edited. It is
 * also the row that exercises the composite foreign key
 * {@code (question_version_id, question_id) -> question_versions(id, question_id)}, and the
 * reason 11005 v1 and v2 must never both land in one exam version, which
 * {@code uq_exam_version_questions_question} forbids.
 *
 * <p>Everywhere else the composition asks for the <em>latest</em> version and resolves it at
 * load time. Today that is version 1 for all of them, so hardcoding 1 would pass every test and
 * quietly stop matching seed §7.5 the day another question gains a second version. The two
 * forms are deliberately spelled differently below so the pin is visible to a reader.
 *
 * <h2>Transcription decisions, flagged in the PR report</h2>
 *
 * <ul>
 *   <li><b>Em dashes replaced</b> in three exam names and five teacher notes. PRD §4.1 forbids
 *       them in user-visible text, and an exam name is on every screen that lists it. Meaning
 *       unchanged, and each change is listed in the report.</li>
 *   <li><b>Seed §8.2 keys its texts by exam, but {@code student_text} and {@code teacher_text}
 *       are columns on {@code exam_versions}.</b> The only available reading is to apply an
 *       exam's texts to all of its versions, which is what happens here. It produces one
 *       known-wrong row: exam 1's teacher note is a rubric for "question 7", and v1 has only
 *       five questions. That is content for the owner to fix, not something to paper over
 *       here.</li>
 *   <li><b>{@code created_at} is derived</b>, as it is for questions: exam versions predate the
 *       T-14d execution, and exam 1 v2 is later than v1.</li>
 * </ul>
 */
final class ExamsSection implements SeedSection {

    private static final int V1_DAYS_BEFORE = -25;
    private static final int V2_DAYS_BEFORE = -18;

    /** A question in an exam version. A null version number means "resolve the latest". */
    private record Slot(String question, Integer questionVersion, int points) { }

    private record Version(int versionNo, int durationMinutes, ExamVersionStatus status,
                           String rejectedReason, List<Slot> slots) { }

    private record SeedExam(String displayId, String name, String author,
                            String studentText, String teacherText, List<Version> versions) { }

    private static Slot latest(String question, int points) {
        return new Slot(question, null, points);
    }

    private static Slot pinned(String question, int questionVersion, int points) {
        return new Slot(question, questionVersion, points);
    }

    private static final List<SeedExam> EXAMS = List.of(
            new SeedExam("101101", "מבחן אמצע: אלגברה", "dana.cohen",
                    "קראו כל שאלה עד הסוף. מותר השימוש במחשבון פשוט בלבד.",
                    "מחוון: שאלה 7, לקבל גם פתרון גרפי מנומק.",
                    List.of(
                            new Version(1, 60, ExamVersionStatus.REJECTED,
                                    "חמש שאלות בלבד ל-60 דקות, והציון לכל שאלה גבוה מדי. "
                                            + "נדרש פיזור רחב יותר.",
                                    List.of(
                                            latest("11001", 20),
                                            latest("11002", 20),
                                            // The pin. Never the latest, in either version.
                                            pinned("11005", 1, 20),
                                            latest("11009", 20),
                                            latest("11010", 20))),
                            new Version(2, 75, ExamVersionStatus.APPROVED, null,
                                    List.of(
                                            latest("11001", 15),
                                            latest("11002", 15),
                                            pinned("11005", 1, 15),
                                            latest("11007", 15),
                                            latest("11009", 15),
                                            latest("11010", 15),
                                            latest("11011", 10))))),

            new SeedExam("101102", "בוחן: אי-שוויונות", "dana.cohen",
                    "בוחן קצר. משך: 30 דקות.",
                    "טיוטה, טרם נבדק מול המחוון.",
                    List.of(new Version(1, 30, ExamVersionStatus.DRAFT, null,
                            List.of(
                                    latest("11009", 40),
                                    latest("11010", 30),
                                    latest("11011", 30))))),

            new SeedExam("101201", "מבחן אמצע: חדו\"א", "dana.cohen",
                    "יש לנמק כל שלב. תשובה ללא נימוק לא תזכה בניקוד מלא.",
                    "להזכיר לרינה: השאלות 12006 ו-12007 חדשות השנה.",
                    List.of(new Version(1, 90, ExamVersionStatus.PENDING, null,
                            List.of(
                                    latest("12001", 15),
                                    latest("12002", 15),
                                    latest("12004", 15),
                                    latest("12005", 15),
                                    latest("12006", 15),
                                    latest("12008", 15),
                                    latest("12009", 10))))),

            new SeedExam("202101", "Java Fundamentals Exam", "avi.mizrahi",
                    "Answer all questions. No IDE or documentation allowed.",
                    "Q21010 is the give-away question, keep it first.",
                    List.of(new Version(1, 60, ExamVersionStatus.APPROVED, null,
                            List.of(
                                    latest("21001", 15),
                                    latest("21002", 15),
                                    latest("21005", 15),
                                    latest("21006", 15),
                                    latest("21009", 15),
                                    latest("21010", 15),
                                    latest("21011", 10))))),

            new SeedExam("202102", "Collections Quiz", "tamar.shani",
                    "Short quiz on the Collections framework.",
                    "Draft, needs a fourth question before resubmitting.",
                    List.of(new Version(1, 30, ExamVersionStatus.REJECTED,
                            "Three questions is too few for a graded quiz, and all three are "
                                    + "from one topic. Add a fourth from Exceptions.",
                            List.of(
                                    latest("21005", 35),
                                    latest("21006", 35),
                                    latest("21007", 30))))),

            new SeedExam("202201", "Databases Final", "michal.sharon",
                    "Closed book. Write SQL keywords in uppercase.",
                    "Q22007 historically has the lowest success rate, expect a low mean.",
                    List.of(new Version(1, 90, ExamVersionStatus.APPROVED, null,
                            List.of(
                                    latest("22001", 15),
                                    latest("22002", 15),
                                    latest("22003", 15),
                                    latest("22005", 15),
                                    latest("22006", 15),
                                    latest("22008", 15),
                                    latest("22009", 10))))));

    /** Every version's points must total this; seed §8.1 and the E7 service rule agree. */
    static final int REQUIRED_POINTS = 100;

    @Override
    public String name() {
        return "8 exams, versions and composition";
    }

    @Override
    public void load(SeedContext context) {
        Session session = context.session();
        int exams = 0;
        int versions = 0;
        int slots = 0;

        for (SeedExam exam : EXAMS) {
            if (SeedLookup.findExamId(session, exam.displayId()).isPresent()) {
                continue;
            }
            String course = exam.displayId().substring(2, 4);
            byte serial = Byte.parseByte(exam.displayId().substring(4));

            Exam row = new Exam(course, serial, exam.displayId(),
                    SeedLookup.requireUserId(session, exam.author()));
            session.persist(row);
            exams++;

            for (Version version : exam.versions()) {
                ExamVersion versionRow = new ExamVersion(row.getId(), version.versionNo(),
                        exam.name(), version.durationMinutes(),
                        exam.studentText(), exam.teacherText(),
                        ExamVersionStatus.DRAFT, createdAt(context, version.versionNo()));
                applyStatus(versionRow, version);
                session.persist(versionRow);
                versions++;

                slots += composeVersion(session, versionRow, version);
            }
        }

        context.recordInserts("exams", exams);
        context.recordInserts("exam_versions", versions);
        context.recordInserts("exam_version_questions", slots);
    }

    /**
     * Reaches the seeded state through the entity's own transitions rather than the
     * constructor, so a rejected version carries its reason the same way E8 would set it,
     * and so these three methods stay exercised by something other than their unit tests.
     */
    private static void applyStatus(ExamVersion row, Version version) {
        switch (version.status()) {
            case DRAFT -> { }
            case PENDING -> row.submitForApproval();
            case APPROVED -> row.approve();
            case REJECTED -> row.reject(version.rejectedReason());
        }
    }

    private static int composeVersion(Session session, ExamVersion versionRow, Version version) {
        int ordinal = 1;
        int total = 0;

        for (Slot slot : version.slots()) {
            long questionId = SeedLookup.requireQuestionId(session, slot.question());
            int questionVersion = slot.questionVersion() == null
                    ? SeedLookup.latestQuestionVersionNo(session, questionId)
                    : slot.questionVersion();
            long questionVersionId =
                    SeedLookup.requireQuestionVersionId(session, slot.question(), questionVersion);

            session.persist(new ExamVersionQuestion(versionRow.getId(), questionVersionId,
                    questionId, slot.points(), ordinal));
            ordinal++;
            total += slot.points();
        }

        if (total != REQUIRED_POINTS) {
            throw new IllegalStateException("exam version " + versionRow.getName() + " v"
                    + versionRow.getVersionNo() + " totals " + total
                    + " points, the service rule requires " + REQUIRED_POINTS);
        }
        return version.slots().size();
    }

    private static Instant createdAt(SeedContext context, int versionNo) {
        int days = versionNo == 1 ? V1_DAYS_BEFORE : V2_DAYS_BEFORE;
        return context.times().dayOffsetAt(days, 10, 0);
    }
}
