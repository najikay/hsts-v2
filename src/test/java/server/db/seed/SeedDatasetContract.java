package server.db.seed;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import server.db.TestDatabase;
import server.db.Transactions;
import server.db.entities.Difficulty;
import server.db.entities.QuestionVersion;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the dataset actually put in the database (E2.15).
 *
 * <p>Deliberately not "1543 rows exist". A count assertion alone passes against a loader that
 * inserted the right number of wrong things, which is the single most likely failure mode when
 * 40 questions are transcribed by hand from a markdown table. So the counts are here, but so
 * are the properties the document leans on: the version pin, the points totals, the
 * pairwise-distinct answers, the deliberately thin topic, and the password path.
 *
 * <h2>Why this does not extend RepositoryTestBase</h2>
 *
 * <p>Two reasons, and the second one was measured rather than predicted.
 *
 * <p>The base seeds a fixture containing users named {@code dana.cohen} and {@code rina.barak},
 * which are the same usernames the real seed uses. Leaving them in place would make every
 * section skip its rows and every count come out wrong.
 *
 * <p>More importantly, the base wipes and reseeds before <em>every test</em>, which would load
 * this dataset once per test method. That is not a style preference: measured against real
 * MySQL it took <b>290 seconds</b> for seventeen tests, against 31 on H2, because each run
 * pays for eighteen BCrypt hashes plus roughly 170 inserts and their idempotency lookups.
 * Every test here is a read, so the dataset is loaded <b>once per class</b> instead. The one
 * test that writes, {@link #theLoadIsIdempotent()}, is safe by construction: it inserts
 * nothing, which is the property it exists to prove.
 */
abstract class SeedDatasetContract extends SeedLoadedTestBase {

    /** Counts stated by SEED_CONTENT.md, section by section. */
    private static final Map<String, Long> EXPECTED_ROWS = Map.of(
            "subjects", 2L,
            "courses", 4L,
            "users", 18L,
            // Five, not six: the 2026-08-20 roster decision removed rina.barak from
            // Calculus, making her a pure coordinator with no course_teachers row.
            "course_teachers", 5L,
            "coordinators", 2L,
            // Seed 6 states no row total, only per-course figures: 11 -> 8, 12 -> 6,
            // 21 -> 8, 22 -> 7. Those sum to 29, and enrollmentTotalsMatch() asserts each
            // one separately so this number cannot be satisfied by the wrong distribution.
            "enrollments", 29L,
            "questions", 40L,
            "question_versions", 43L,
            "notifications", 8L);

    private SeedLoader loader() {
        return new SeedLoader(factory(), java.time.Clock.fixed(ANCHOR, java.time.ZoneOffset.UTC),
                SeedDataset.sections());
    }

    @Test
    @DisplayName("every table holds exactly the number of rows the document states")
    void rowCountsMatchTheDocument() {
        EXPECTED_ROWS.forEach((table, expected) ->
                assertThat(count(table)).as("%s", table).isEqualTo(expected));

        assertThat(count("exams")).isEqualTo(6);
        assertThat(count("exam_versions")).isEqualTo(7);
        assertThat(count("exam_version_questions")).isEqualTo(39);
    }

    @Test
    @DisplayName("the summary reports what was actually inserted")
    void summaryAgreesWithTheDatabase() {
        assertThat(summary().outcome()).isEqualTo(SeedOutcome.LOADED);
        assertThat(summary().rowsByTable()).containsEntry("users", 18);
        assertThat(summary().rowsByTable()).containsEntry("questions", 40);
        assertThat(summary().totalRows()).isEqualTo(count("users") + count("subjects")
                + count("courses") + count("course_teachers") + count("coordinators")
                + count("enrollments") + count("questions") + count("question_versions")
                + count("exams") + count("exam_versions") + count("exam_version_questions")
                + count("notifications"));
    }

    @Test
    @DisplayName("loading twice inserts nothing the second time")
    void theLoadIsIdempotent() {
        // Per-row, by natural key. The check that matters is the row counts afterwards: a
        // loader that reported UNCHANGED while quietly inserting duplicates would pass an
        // outcome assertion alone.
        SeedSummary second = loader().load(SeedMode.LOAD_IF_MISSING, Confirmation.refused());

        assertThat(second.outcome()).isEqualTo(SeedOutcome.UNCHANGED);
        assertThat(second.totalRows()).isZero();
        assertThat(count("users")).isEqualTo(18);
        assertThat(count("question_versions")).isEqualTo(43);
        assertThat(count("exam_version_questions")).isEqualTo(39);
        assertThat(count("notifications")).isEqualTo(8);
    }

    @Test
    @DisplayName("both versions of the Algebra Midterm pin question 11005 to version 1")
    void theAlgebraMidtermPinsQuestion11005ToVersionOne() {
        // The defence-critical row. 11005 has a v2 in the bank, so "latest" would be v2:
        // this is what proves a released exam is pinned to the version it was built from
        // (S-14, C-2) rather than drifting when the question is edited. Seed 8.1 requires it
        // of BOTH exam-1 versions, not only the released one.
        List<Integer> pinnedVersions = inTx(session -> session.createQuery("""
                        select qv.versionNo
                        from ExamVersionQuestion evq, QuestionVersion qv, Question q, ExamVersion ev, Exam e
                        where qv.id = evq.id.questionVersionId
                          and q.id = qv.questionId
                          and ev.id = evq.id.examVersionId
                          and e.id = ev.examId
                          and e.displayId = '101101'
                          and q.displayId = '11005'
                        order by ev.versionNo
                        """, Integer.class).getResultList());

        assertThat(pinnedVersions)
                .as("exam 101101 v1 and v2 must both reference question 11005 version 1")
                .containsExactly(1, 1);
    }

    @Test
    @DisplayName("no exam version holds two versions of the same question")
    void noExamVersionHoldsTwoVersionsOfOneQuestion() {
        // uq_exam_version_questions_question forbids it, and H2 does not reproduce that
        // constraint, so this asserts the property rather than trusting the engine.
        List<Long> offenders = inTx(session -> session.createQuery("""
                        select evq.id.examVersionId
                        from ExamVersionQuestion evq
                        group by evq.id.examVersionId, evq.questionId
                        having count(evq) > 1
                        """, Long.class).getResultList());

        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("question 11005 version 2 is in the bank but in no exam")
    void theSupersededVersionSurvivesOutsideAnyExam() {
        // C-2 / ADR-011: editing creates version n+1 and the old version stays queryable.
        // If v2 were also composed into an exam the pin above would be meaningless.
        assertThat(versionCountOf("11005")).isEqualTo(2);
        assertThat(examUsesOf("11005", 2)).isZero();
        assertThat(examUsesOf("11005", 1)).isEqualTo(2);
    }

    @Test
    @DisplayName("every exam version totals exactly 100 points")
    void everyExamVersionTotalsOneHundred() {
        // Read back from the database rather than summed from the constants that built it,
        // so a transcription slip in the points column is caught rather than echoed.
        List<Object[]> totals = inTx(session -> session.createQuery("""
                        select ev.id, sum(evq.points)
                        from ExamVersionQuestion evq, ExamVersion ev
                        where ev.id = evq.id.examVersionId
                        group by ev.id
                        """, Object[].class).getResultList());

        assertThat(totals).hasSize(7);
        totals.forEach(row -> assertThat(((Number) row[1]).intValue())
                .as("exam version %s", row[0]).isEqualTo(100));
    }

    @Test
    @DisplayName("every question has exactly one correct answer in 1..4, and four distinct options")
    void everyQuestionObeysTheAnswerRules() {
        // C-8 / ADR-016 as a property of the loaded rows. The DDL enforces both on MySQL and
        // neither on H2, so asserting it here is what makes the H2 leaf worth running.
        List<QuestionVersion> all = inTx(session -> session.createQuery(
                "select qv from QuestionVersion qv", QuestionVersion.class).getResultList());

        assertThat(all).hasSize(43);
        all.forEach(version -> {
            assertThat(version.getCorrectAnswer())
                    .as("question version %s", version.getId())
                    .isBetween((byte) 1, (byte) 4);
            assertThat(List.of(version.getA1(), version.getA2(),
                    version.getA3(), version.getA4()))
                    .as("answers of question version %s must be pairwise distinct", version.getId())
                    .doesNotHaveDuplicates();
        });
    }

    @Test
    @DisplayName("Recursion stays thin: two questions, none HARD")
    void theDeliberatelyThinTopicIsNotQuietlyFixed() {
        // Seed 7.3 is explicit that this must not be "fixed". It is the fixture that lets
        // F3.3 auto-generation be demonstrated failing live (T-3), without anyone editing the
        // database mid-defense. A well-meaning future contributor adding a third Recursion
        // question would silently remove the only way to demo that path.
        assertThat(questionsInTopic("Recursion")).isEqualTo(2);
        assertThat(hardQuestionsInTopic("Recursion")).isZero();
    }

    @Test
    @DisplayName("all ten illustrated questions load with a null image")
    void illustrationsAreNullForNow() {
        // The bytes arrive later under docs/seed/img/. Until then NULL is correct, and the
        // loader has to stay idempotent when they land.
        long withImage = inTx(session -> session.createQuery(
                "select count(qv) from QuestionVersion qv where qv.image is not null",
                Long.class).getSingleResult());

        assertThat(QuestionBankSection.illustratedCount()).isEqualTo(10);
        assertThat(count("question_versions")).isEqualTo(43);
        assertThat(withImage).isZero();
    }

    @Test
    @DisplayName("the demo password verifies against every seeded user, with distinct hashes")
    void everyUserSignsInWithTheDemoPassword() {
        List<String> hashes = inTx(session -> session.createQuery(
                "select u.passwordHash from User u", String.class).getResultList());

        assertThat(hashes).hasSize(18);
        hashes.forEach(hash -> assertThat(BCrypt.verifyer()
                .verify(UsersSection.SEED_PASSWORD.toCharArray(), hash).verified)
                .as("demo123 must verify against %s", hash)
                .isTrue());
        assertThat(hashes).as("hashed per user, so one password gives eighteen salts")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("no stored user-visible string contains an em dash")
    void noEmDashesReachTheDatabase() {
        // PRD 4.1, and the c2b9c0f sweep put log and user-facing strings in scope. These are
        // columns rendered on screen: exam names, question stems and options, notification
        // titles. The seed document itself uses em dashes freely in prose, so this is the
        // guard that stops them travelling into the product.
        assertThat(stringsContaining("—")).isEmpty();
    }

    @Test
    @DisplayName("per-course enrollment totals match the document")
    void enrollmentTotalsMatch() {
        // Course 11's eight is load-bearing: it is the roster of the fully graded execution,
        // and eight grades across five deciles is what makes the F9.3 histogram look real.
        assertThat(enrolledIn("11")).isEqualTo(8);
        assertThat(enrolledIn("12")).isEqualTo(6);
        assertThat(enrolledIn("21")).isEqualTo(8);
        assertThat(enrolledIn("22")).isEqualTo(7);
    }

    @Test
    @DisplayName("the pure coordinator teaches nothing, and the dual-hat one teaches")
    void bothCoordinatorShapesAreSeeded() {
        // Roster decision 2026-08-20. rina.barak coordinates subject 10 with zero
        // course_teachers rows; michal.sharon coordinates 20 and teaches Databases 22.
        //
        // Keeping both is what makes the derived wire role provable: coordinator-ness lives
        // only in the coordinators table, so a coordinator who teaches nothing must still
        // resolve to Role.COORDINATOR. An implementation that derived the role from
        // course_teachers instead would pass every test if every coordinator also taught.
        assertThat(coursesTaughtBy("rina.barak")).isZero();
        assertThat(coordinatedSubjectsOf("rina.barak")).isEqualTo(1);
        assertThat(coursesTaughtBy("michal.sharon")).isEqualTo(1);
        assertThat(coordinatedSubjectsOf("michal.sharon")).isEqualTo(1);
        assertThat(coursesTaughtBy("dana.cohen"))
                .as("dana teaches Calculus alone since the roster change")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("rina.barak is stored TEACHER with a coordinators row, never a stored COORDINATOR")
    void theCoordinatorIsDerivedNotStored() {
        // ARCHITECTURE section 5 round-2: users.role has no COORDINATOR member, and the wire
        // role is derived at login. DEMO_ACCOUNTS.md lists her as COORDINATOR, which is the
        // wire role; this is the assertion that the two documents describe one person.
        String storedRole = inTx(session -> session.createQuery(
                        "select cast(u.role as string) from User u where u.username = 'rina.barak'",
                        String.class).getSingleResult());

        long coordinatorRows = inTx(session -> session.createQuery("""
                select count(c) from Coordinator c
                where c.teacherId = (select u.id from User u where u.username = 'rina.barak')
                """, Long.class).getSingleResult());

        assertThat(storedRole).isEqualTo("TEACHER");
        assertThat(coordinatorRows).isEqualTo(1);
    }

    private long count(String table) {
        return inTx(session -> session
                .createNativeQuery("SELECT COUNT(*) FROM " + table, Long.class)
                .getSingleResult());
    }

    private long enrolledIn(String course) {
        return inTx(session -> session.createQuery(
                        "select count(e) from Enrollment e where e.id.courseCode = :course",
                        Long.class)
                .setParameter("course", course)
                .getSingleResult());
    }

    private long coursesTaughtBy(String username) {
        return inTx(session -> session.createQuery("""
                        select count(ct) from CourseTeacher ct
                        where ct.id.teacherId = (select u.id from User u where u.username = :username)
                        """, Long.class)
                .setParameter("username", username)
                .getSingleResult());
    }

    private long coordinatedSubjectsOf(String username) {
        return inTx(session -> session.createQuery("""
                        select count(c) from Coordinator c
                        where c.teacherId = (select u.id from User u where u.username = :username)
                        """, Long.class)
                .setParameter("username", username)
                .getSingleResult());
    }

    private long versionCountOf(String displayId) {
        return inTx(session -> session.createQuery("""
                        select count(qv) from QuestionVersion qv, Question q
                        where q.id = qv.questionId and q.displayId = :displayId
                        """, Long.class)
                .setParameter("displayId", displayId)
                .getSingleResult());
    }

    private long examUsesOf(String displayId, int versionNo) {
        return inTx(session -> session.createQuery("""
                        select count(evq) from ExamVersionQuestion evq, QuestionVersion qv, Question q
                        where qv.id = evq.id.questionVersionId
                          and q.id = qv.questionId
                          and q.displayId = :displayId
                          and qv.versionNo = :versionNo
                        """, Long.class)
                .setParameter("displayId", displayId)
                .setParameter("versionNo", versionNo)
                .getSingleResult());
    }

    private long questionsInTopic(String topic) {
        return inTx(session -> session.createQuery(
                        "select count(qv) from QuestionVersion qv where qv.topic = :topic "
                                + "and qv.versionNo = 1", Long.class)
                .setParameter("topic", topic)
                .getSingleResult());
    }

    private long hardQuestionsInTopic(String topic) {
        return inTx(session -> session.createQuery(
                        "select count(qv) from QuestionVersion qv where qv.topic = :topic "
                                + "and qv.difficulty = :hard", Long.class)
                .setParameter("topic", topic)
                .setParameter("hard", Difficulty.HARD)
                .getSingleResult());
    }

    /** @return every stored user-visible string containing the given text */
    private List<String> stringsContaining(String needle) {
        return inTx(session -> {
            List<String> found = new java.util.ArrayList<>();
            found.addAll(matching(session, "select ev.name from ExamVersion ev", needle));
            found.addAll(matching(session, "select ev.studentText from ExamVersion ev", needle));
            found.addAll(matching(session, "select ev.teacherText from ExamVersion ev", needle));
            found.addAll(matching(session,
                    "select ev.rejectedReason from ExamVersion ev where ev.rejectedReason is not null",
                    needle));
            found.addAll(matching(session, "select qv.text from QuestionVersion qv", needle));
            found.addAll(matching(session, "select qv.a1 from QuestionVersion qv", needle));
            found.addAll(matching(session, "select qv.a2 from QuestionVersion qv", needle));
            found.addAll(matching(session, "select qv.a3 from QuestionVersion qv", needle));
            found.addAll(matching(session, "select qv.a4 from QuestionVersion qv", needle));
            found.addAll(matching(session, "select qv.topic from QuestionVersion qv", needle));
            found.addAll(matching(session, "select n.title from Notification n", needle));
            found.addAll(matching(session, "select c.name from Course c", needle));
            found.addAll(matching(session, "select s.name from Subject s", needle));
            found.addAll(matching(session, "select u.fullName from User u", needle));
            return found;
        });
    }

    private static List<String> matching(org.hibernate.Session session, String query, String needle) {
        return session.createQuery(query, String.class).getResultList().stream()
                .filter(value -> value != null && value.contains(needle))
                .toList();
    }
}
