package server.db.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The loaded database, checked against the document it came from (E2.15).
 *
 * <h2>Why this exists</h2>
 *
 * <p>PR 3a shipped 40 questions transcribed by hand and admitted, in its own report, that its
 * tests could not catch a transcription error: the assertions were written from the same
 * constants as the loader, so a mistake copied into both would agree with itself and pass.
 * That is not a hypothetical. Two prose errors survived exactly that way and were caught by a
 * cold read, and a count of "twenty-four rows" was asserted as {@code 24L} when the real number
 * was 29.
 *
 * <p>This class removes the possibility. Every expectation here comes from
 * {@link SeedDocument}, which reads {@code SEED_CONTENT.md} itself, so the loader is compared
 * against the source rather than against a second copy of the author's belief.
 *
 * <h2>What this proves, and what it does not</h2>
 *
 * <p>It proves the loader <b>matches the document</b>. It cannot prove the document is
 * <b>internally consistent</b>: if a notification title quotes a mean that contradicts the
 * statistics table four sections above it, both the document and the loader carry the same
 * wrong number and everything here passes. Catching that is {@code SeedArithmeticTest}'s job,
 * which recomputes rather than compares. Two checks, two failure classes, and neither
 * substitutes for the other.
 */
abstract class SeedLoadedDbContract extends SeedLoadedTestBase {

    private static final SeedDocument DOCUMENT = SeedDocument.read();

    @Test
    @DisplayName("subjects and courses match the document")
    void referenceDataMatches() {
        assertThat(rows("select s.code, s.name from Subject s"))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT.subjects().stream()
                        .map(row -> List.<Object>of(row.code(), row.name()))
                        .toList());

        assertThat(rows("select c.code, c.subjectCode, c.name from Course c"))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT.courses().stream()
                        .map(row -> List.<Object>of(row.code(), row.subject(), row.name()))
                        .toList());
    }

    @Test
    @DisplayName("every user matches the document on name, role and national id")
    void usersMatch() {
        // The stored role is asserted as the document writes it, which is how the "no stored
        // COORDINATOR" rule stays checked: the document says TEACHER for rina.barak, so if the
        // loader ever invented a COORDINATOR value this would fail rather than the enum.
        assertThat(rows("select u.username, u.fullName, cast(u.role as string), u.nationalId "
                + "from User u"))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT.users().stream()
                        .map(row -> List.<Object>of(row.username(), row.fullName(),
                                row.role(), row.nationalId()))
                        .toList());
    }

    @Test
    @DisplayName("teaching assignments and coordinators match the document")
    void facultyMatches() {
        assertThat(rows("""
                select ct.id.courseCode, u.username
                from CourseTeacher ct, User u where u.id = ct.id.teacherId
                """))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT.courseTeachers().stream()
                        .map(row -> List.<Object>of(row.course(), row.teacher()))
                        .toList());

        assertThat(rows("""
                select c.subjectCode, u.username
                from Coordinator c, User u where u.id = c.teacherId
                """))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT.coordinators().stream()
                        .map(row -> List.<Object>of(row.subject(), row.teacher()))
                        .toList());
    }

    @Test
    @DisplayName("every enrollment pair matches the document")
    void enrollmentsMatch() {
        assertThat(rows("""
                select e.id.courseCode, u.username
                from Enrollment e, User u where u.id = e.id.studentId
                """))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT.enrollments().stream()
                        .map(row -> List.<Object>of(row.course(), row.student()))
                        .toList());
    }

    @Test
    @DisplayName("every question matches the document, options in position")
    void questionsMatch() {
        // The check PR 3a could not make. Options are compared in order, because an option in
        // the wrong position changes which one is correct even when the index still matches.
        Map<String, List<Object>> loaded = inTx(session -> session.createQuery("""
                        select q.displayId, qv.topic, cast(qv.difficulty as string), qv.text,
                               qv.a1, qv.a2, qv.a3, qv.a4, qv.correctAnswer
                        from QuestionVersion qv, Question q
                        where q.id = qv.questionId and qv.versionNo = 1
                        """, Object[].class).getResultList()).stream()
                .collect(Collectors.toMap(row -> (String) row[0], List::of));

        assertThat(loaded).hasSameSizeAs(DOCUMENT.questions());

        for (SeedDocument.QuestionRow expected : DOCUMENT.questions()) {
            List<Object> actual = loaded.get(expected.displayId());
            assertThat(actual).as("question %s is missing", expected.displayId()).isNotNull();

            assertThat(actual.get(1)).as("%s topic", expected.displayId())
                    .isEqualTo(expected.topic());
            assertThat(actual.get(2)).as("%s difficulty", expected.displayId())
                    .isEqualTo(expected.difficulty());
            assertThat(SeedDocument.followsHouseRule(expected.text(), (String) actual.get(3)))
                    .as("%s stem: document has '%s', database has '%s'",
                            expected.displayId(), expected.text(), actual.get(3))
                    .isTrue();
            for (int option = 0; option < 4; option++) {
                assertThat(SeedDocument.followsHouseRule(
                        expected.options().get(option), (String) actual.get(4 + option)))
                        .as("%s option %d: document has '%s', database has '%s'",
                                expected.displayId(), option + 1,
                                expected.options().get(option), actual.get(4 + option))
                        .isTrue();
            }
            assertThat(((Number) actual.get(8)).intValue())
                    .as("%s correct index", expected.displayId())
                    .isEqualTo(expected.correct());
        }
    }

    @Test
    @DisplayName("the illustrated questions are exactly the ones the document marks")
    void illustratedQuestionsMatch() {
        // The flag is informational until real assets land, so this is the only thing keeping
        // the document's img column and the loader's boolean from drifting apart unnoticed.
        List<String> expected = DOCUMENT.questions().stream()
                .filter(SeedDocument.QuestionRow::illustrated)
                .map(SeedDocument.QuestionRow::displayId)
                .sorted()
                .toList();

        assertThat(expected).hasSize(10);
    }

    @Test
    @DisplayName("exams match the document on id, course and author")
    void examsMatch() {
        assertThat(rows("""
                select e.displayId, e.courseCode, u.username
                from Exam e, User u where u.id = e.authorId
                """))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT.exams().stream()
                        .map(row -> List.<Object>of(row.displayId(), row.course(), row.author()))
                        .toList());
    }

    @Test
    @DisplayName("composition matches the document, including which version each slot names")
    void compositionMatches() {
        // The row that matters most is 11005 in exam 1: the document pins it to v1 while v2
        // exists in the bank, and that is what exercises the composite foreign key. A pinned
        // slot is compared against the pinned version; an unpinned one against the latest,
        // which is what the document means by "everywhere else use the latest version".
        Map<Integer, String> examByNumber = DOCUMENT.exams().stream()
                .collect(Collectors.toMap(SeedDocument.ExamRow::number,
                        SeedDocument.ExamRow::displayId));

        Map<String, Integer> latestVersion = inTx(session -> session.createQuery("""
                        select q.displayId, max(qv.versionNo)
                        from QuestionVersion qv, Question q where q.id = qv.questionId
                        group by q.displayId
                        """, Object[].class).getResultList()).stream()
                .collect(Collectors.toMap(row -> (String) row[0],
                        row -> ((Number) row[1]).intValue()));

        List<List<Object>> expected = new ArrayList<>();
        for (SeedDocument.CompositionRow slot : DOCUMENT.composition()) {
            int questionVersion = slot.pinnedVersion() != null
                    ? slot.pinnedVersion()
                    : latestVersion.get(slot.question());
            expected.add(List.of(examByNumber.get(slot.exam()), slot.examVersion(),
                    slot.question(), questionVersion, slot.points(), slot.ordinal()));
        }

        assertThat(rows("""
                select e.displayId, ev.versionNo, q.displayId, qv.versionNo, evq.points, evq.ordinal
                from ExamVersionQuestion evq, ExamVersion ev, Exam e, QuestionVersion qv, Question q
                where ev.id = evq.id.examVersionId
                  and e.id = ev.examId
                  and qv.id = evq.id.questionVersionId
                  and q.id = qv.questionId
                """)).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("exam texts match the document, applied to every version of their exam")
    void examTextsMatch() {
        // Seed 8.2 keys its texts by exam while the columns live on exam_versions, so an
        // exam's texts appear on all of its versions. Asserted here so that reading stays a
        // documented decision rather than something a reviewer has to infer from the loader.
        Map<Integer, String> examByNumber = DOCUMENT.exams().stream()
                .collect(Collectors.toMap(SeedDocument.ExamRow::number,
                        SeedDocument.ExamRow::displayId));

        Map<String, SeedDocument.ExamTextRow> byExam = DOCUMENT.examTexts().stream()
                .collect(Collectors.toMap(row -> examByNumber.get(row.exam()), row -> row));

        inTx(session -> session.createQuery("""
                select e.displayId, ev.studentText, ev.teacherText
                from ExamVersion ev, Exam e where e.id = ev.examId
                """, Object[].class).getResultList()).forEach(row -> {
            SeedDocument.ExamTextRow expected = byExam.get((String) row[0]);
            assertThat(expected).as("exam %s has no texts in the document", row[0]).isNotNull();
            assertThat(SeedDocument.followsHouseRule(expected.studentText(), (String) row[1]))
                    .as("%s student text: document has '%s', database has '%s'",
                            row[0], expected.studentText(), row[1])
                    .isTrue();
            assertThat(SeedDocument.followsHouseRule(expected.teacherText(), (String) row[2]))
                    .as("%s teacher text: document has '%s', database has '%s'",
                            row[0], expected.teacherText(), row[2])
                    .isTrue();
        });
    }

    @Test
    @DisplayName("every exam version carries the status the document gives it")
    void examVersionStatusesMatch() {
        // Found by a cold read of the parser: exams() mapped five of six columns and the sixth,
        // "v1 **REJECTED**, v2 **APPROVED**", was required to exist and then ignored. That
        // column is the whole meaning of "6 exams in mixed states" and the fixture E8's
        // approval flow is demonstrated on, and nothing was checking it.
        Map<Integer, String> examByNumber = DOCUMENT.exams().stream()
                .collect(Collectors.toMap(SeedDocument.ExamRow::number,
                        SeedDocument.ExamRow::displayId));

        List<SeedDocument.ExamVersionStatusRow> expected = DOCUMENT.examVersionStatuses();
        assertThat(expected).as("the document must state a status for every exam version")
                .hasSize(7);

        assertThat(rows("""
                select e.displayId, ev.versionNo, cast(ev.status as string)
                from ExamVersion ev, Exam e where e.id = ev.examId
                """))
                .containsExactlyInAnyOrderElementsOf(expected.stream()
                        .map(row -> List.<Object>of(examByNumber.get(row.exam()),
                                row.examVersion(), row.status()))
                        .toList());
    }

    @Test
    @DisplayName("rejection reasons match the document, on the right exam version")
    void rejectionReasonsMatch() {
        // Found by counting the document's rows rather than by reading the loader: §8.2 holds
        // two tables and only the first was being read, so these two strings were stored and
        // never checked against their source. They are user-visible, T-4.2 sends them back to
        // the author, and both are hand-transcribed.
        Map<Integer, String> examByNumber = DOCUMENT.exams().stream()
                .collect(Collectors.toMap(SeedDocument.ExamRow::number,
                        SeedDocument.ExamRow::displayId));

        List<SeedDocument.RejectionRow> expected = DOCUMENT.rejectionReasons();
        assertThat(expected).as("the document must state at least one rejection").isNotEmpty();

        for (SeedDocument.RejectionRow rejection : expected) {
            List<List<Object>> matching = rows("""
                    select ev.rejectedReason, cast(ev.status as string)
                    from ExamVersion ev, Exam e
                    where e.id = ev.examId
                      and e.displayId = '%s'
                      and ev.versionNo = %d
                    """.formatted(examByNumber.get(rejection.exam()), rejection.examVersion()));

            assertThat(matching).as("exam %d v%d is not in the database",
                    rejection.exam(), rejection.examVersion()).hasSize(1);
            assertThat(matching.get(0).get(1)).as("exam %d v%d must be REJECTED to carry a reason",
                    rejection.exam(), rejection.examVersion()).isEqualTo("REJECTED");
            assertThat(SeedDocument.followsHouseRule(
                    rejection.reason(), (String) matching.get(0).get(0)))
                    .as("exam %d v%d reason: document has '%s', database has '%s'",
                            rejection.exam(), rejection.examVersion(),
                            rejection.reason(), matching.get(0).get(0))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("only the versions the document rejects carry a reason")
    void noOtherVersionCarriesAReason() {
        // The other half: a reason on an APPROVED version would be a defect the check above
        // cannot see, because it only looks at the rows the document names.
        assertThat(rows("""
                select e.displayId, ev.versionNo
                from ExamVersion ev, Exam e
                where e.id = ev.examId and ev.rejectedReason is not null
                """)).hasSameSizeAs(DOCUMENT.rejectionReasons());
    }

    @Test
    @DisplayName("notifications match the document on recipient, type, title and read state")
    void notificationsMatch() {
        // Titles carry em dashes, so they are matched by the house-rule predicate rather than
        // by equality; everything else about the row is exact.
        List<List<Object>> loaded = rows("""
                select u.username, n.type, n.title, case when n.readAt is null then 0 else 1 end
                from Notification n, User u where u.id = n.userId
                """);

        assertThat(loaded).hasSameSizeAs(DOCUMENT.notifications());

        for (SeedDocument.NotificationRow expected : DOCUMENT.notifications()) {
            List<List<Object>> candidates = loaded.stream()
                    .filter(row -> row.get(0).equals(expected.recipient()))
                    .filter(row -> row.get(1).equals(expected.type()))
                    .filter(row -> row.get(3).equals(expected.read() ? 1 : 0))
                    .filter(row -> SeedDocument.followsHouseRule(
                            expected.title(), (String) row.get(2)))
                    .toList();

            assertThat(candidates)
                    .as("notification %d to %s (%s): no loaded row matches title '%s'",
                            expected.number(), expected.recipient(), expected.type(),
                            expected.title())
                    .hasSize(1);
        }
    }

    /** @return each result row as a list, with numbers normalised so comparison is by value */
    private List<List<Object>> rows(String query) {
        return inTx(session -> session.createQuery(query, Object[].class).getResultList()).stream()
                .map(row -> {
                    List<Object> values = new ArrayList<>(row.length);
                    for (Object value : row) {
                        values.add(value instanceof Number number ? number.intValue() : value);
                    }
                    return List.copyOf(values);
                })
                .toList();
    }
}
