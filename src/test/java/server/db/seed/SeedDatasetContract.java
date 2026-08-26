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
import server.features.bank.QuestionValidator;

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
 * pairwise-distinct answers <em>as the application's own comparison defines them</em> (P-12),
 * the deliberately thin topic, and the password path.
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
            // 9 since 2026-08-26: B-25 added N-GRADE-MAYA, so DEMO_DAY's sign-in account
            // has a bell at all.
            "notifications", 9L);

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
        // Summed over the tables the summary itself names, rather than a list written here.
        // The hardcoded version went stale the moment §9 added four tables, and a total that
        // silently stops covering part of the load is worse than no total at all.
        long inDatabase = summary().rowsByTable().keySet().stream()
                .mapToLong(this::count)
                .sum();

        assertThat(summary().totalRows()).isEqualTo(inDatabase);
        assertThat(summary().rowsByTable().keySet())
                .as("every table the loader writes must appear in the summary the console shows")
                .contains("subjects", "courses", "users", "course_teachers", "coordinators",
                        "enrollments", "questions", "question_versions", "exams", "exam_versions",
                        "exam_version_questions", "exam_executions", "exam_attempts",
                        "attempt_answers", "grades", "notifications");
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
        assertThat(count("notifications")).isEqualTo(9);
    }

    // ===== B-24: the loader can see dataset drift ==========================

    @Test
    @DisplayName("a freshly seeded database shows no dataset drift ⚑ (B-24)")
    void theFingerprintMatchesAFreshlySeededDatabase() {
        // The tripwire under SeedFingerprint's hardcoded expectations. They restate numbers
        // that live in SEED_CONTENT.md, which is a second place for them to drift; this is
        // what turns that drift into a build failure instead of a warning every operator sees
        // forever and learns to ignore.
        SeedFingerprint.Drift drift = inTx(SeedFingerprint::compare);

        assertThat(drift.differences())
                .as("the fingerprint's expectations have gone stale against the real dataset")
                .isEmpty();
        assertThat(drift.isClean()).isTrue();
        assertThat(drift.expectedDigest()).isEqualTo(drift.actualDigest());
        assertThat(drift.warning()).isEmpty();
    }

    @Test
    @DisplayName("a second LOAD_IF_MISSING on this database warns about nothing")
    void aCleanDatabaseIsNotWarnedAbout() {
        SeedSummary second = loader().load(SeedMode.LOAD_IF_MISSING, Confirmation.refused());

        assertThat(second.hasWarning()).isFalse();
        assertThat(second.toText()).doesNotContain("WARNING");
    }

    @Test
    @DisplayName("content that drifted under an unchanged natural key is caught ⚑ (B-24)")
    void driftedContentIsCaughtAndNothingIsDeleted() {
        // 17.2's exact situation, reproduced: the username is untouched, so every row-level
        // idempotency check still matches and the loader still reports UNCHANGED. What moved
        // is the content, which is what the pre-translation hsts_db looked like (B-19).
        long usersBefore = count("users");
        Transactions.runInTx(factory(), session -> session
                .createMutationQuery(
                        "update User u set u.fullName = 'דנה כהן' where u.username = 'dana.cohen'")
                .executeUpdate());
        try {
            SeedSummary summary = loader().load(SeedMode.LOAD_IF_MISSING, Confirmation.refused());

            assertThat(summary.outcome())
                    .as("the row-level check still matches on the username, which is the gap")
                    .isEqualTo(SeedOutcome.UNCHANGED);
            assertThat(summary.hasWarning()).isTrue();
            assertThat(summary.warning())
                    .contains("does not look like it was seeded by this build's dataset")
                    .contains("Nothing has been deleted")
                    .contains("Reload demo data")
                    .contains("dana.cohen's display name");
            assertThat(summary.toText())
                    .as("the console result panel is where an operator actually reads this")
                    .contains(summary.warning());
            assertThat(count("users"))
                    .as("a warning deletes nothing and inserts nothing")
                    .isEqualTo(usersBefore);
        } finally {
            Transactions.runInTx(factory(), session -> session
                    .createMutationQuery(
                            "update User u set u.fullName = 'Dana Cohen' "
                                    + "where u.username = 'dana.cohen'")
                    .executeUpdate());
        }
    }

    @Test
    @DisplayName("a row added beside the seed moves the count and is reported")
    void extraRowsAreCaught() {
        // Resolved rather than hardcoded: users.id is AUTO_INCREMENT, so the seed's "user 1" is
        // only user 1 on a schema that has been loaded once. This class's schema is reused.
        long recipient = inTx(session -> session
                .createQuery("select u.id from User u where u.username = 'maya.levi'", Long.class)
                .getSingleResult());
        Transactions.runInTx(factory(), session -> session.persist(
                new server.db.entities.Notification(recipient, "GRADE_PUBLISHED",
                        "Something a person did", null, null, null, ANCHOR)));
        try {
            SeedSummary summary = loader().load(SeedMode.LOAD_IF_MISSING, Confirmation.refused());

            assertThat(summary.warning())
                    .contains("notifications: this dataset says 9, the database says 10");
        } finally {
            Transactions.runInTx(factory(), session -> session
                    .createMutationQuery(
                            "delete from Notification n where n.title = 'Something a person did'")
                    .executeUpdate());
        }
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
    @DisplayName("⚑ every question has one correct answer in 1..4, and four options the validator "
            + "itself calls distinct")
    void everyQuestionObeysTheAnswerRules() {
        // C-8 / ADR-016 as a property of the loaded rows. The DDL enforces both on MySQL and
        // neither on H2, so asserting it here is what makes the H2 leaf worth running.
        //
        // Distinctness is asserted with QuestionValidator.sameAnswer and deliberately NOT with
        // equals (P-12, 2026-08-26). This line read doesNotHaveDuplicates() until then, which
        // compares with Object.equals while the running application compares with a collator
        // fold. Two rules for one invariant, either side of one seam: the seed passed here and
        // was refused by the validator, so five questions the loader had itself written could
        // not be written back through QUESTION_UPDATE, and 6365 green tests said nothing. It
        // took an acceptance walk to find, which is P-8's shape in this file's own assertion.
        //
        // Pointing it at the real rule makes the dataset a tripwire for the validator: a fold
        // that grows stricter now fails here, on the very rows the system stored itself.
        //
        // Its reach is exactly the distinctions THESE rows exercise, and not one character more.
        // A fold that started ignoring niqqud, or a punctuation class no seeded question happens
        // to contain, would be stricter than the collation and leave this green. Said plainly
        // because an earlier draft of this comment claimed the general property, which is P-6's
        // shape aimed at a comment: the next reader trusts the sentence and stops looking. The
        // general guarantee is BankRoundTripIntegrationTest's bidirectional assertion against
        // real MySQL; this is the cheap standing check that the shipped dataset stays saveable.
        List<QuestionVersion> all = inTx(session -> session.createQuery(
                "select qv from QuestionVersion qv", QuestionVersion.class).getResultList());

        assertThat(all).hasSize(43);
        all.forEach(version -> {
            assertThat(version.getCorrectAnswer())
                    .as("question version %s", version.getId())
                    .isBetween((byte) 1, (byte) 4);

            List<String> answers = List.of(version.getA1(), version.getA2(),
                    version.getA3(), version.getA4());
            for (int first = 0; first < answers.size(); first++) {
                for (int second = first + 1; second < answers.size(); second++) {
                    assertThat(QuestionValidator.sameAnswer(answers.get(first), answers.get(second)))
                            .as("question version %s: answers %d and %d are one answer to "
                                    + "QuestionValidator.sameAnswer, which is the rule "
                                    + "QUESTION_UPDATE enforces, so this row cannot be saved "
                                    + "from the editor: '%s' and '%s'",
                                    version.getId(), first + 1, second + 1,
                                    answers.get(first), answers.get(second))
                            .isFalse();
                }
            }
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
    @DisplayName("every illustrated question loads its bytes, on every version it has")
    void illustrationsLoadTheirBytes() {
        // Was "all ten illustrated questions load with a null image" until 2026-08-26, when B-8
        // supplied the assets. The inversion is the point of the ticket: this test held the known
        // gap open and is what proves it closed.
        long withImage = inTx(session -> session.createQuery(
                "select count(qv) from QuestionVersion qv where qv.image is not null",
                Long.class).getSingleResult());

        assertThat(QuestionBankSection.illustratedCount()).isEqualTo(10);
        assertThat(count("question_versions")).isEqualTo(43);

        // ELEVEN rows, not ten. 11005 is illustrated and has a second version, and the bytes go
        // on both: case 6.1's demo paper pins v1 while case 2.6's bank list shows the latest, so
        // an image on only one row leaves one of the two acceptance cases still unwalkable. If
        // this number ever reads 10 again, that is the regression, not an off-by-one.
        assertThat(withImage)
                .as("ten first versions plus 11005 v2")
                .isEqualTo(11);
    }

    @Test
    @DisplayName("11005 carries its illustration on both of its versions")
    void theRewordedQuestionKeepsItsPicture() {
        List<byte[]> images = inTx(session -> session.createQuery(
                "select qv.image from QuestionVersion qv join Question q on q.id = qv.questionId "
                        + "where q.displayId = '11005' order by qv.versionNo",
                byte[].class).getResultList());

        assertThat(images).hasSize(2);
        assertThat(images.get(0)).isNotNull().isNotEmpty();
        assertThat(images.get(1))
                .as("the reword changed the stem, not the subject, so v2 keeps the drawing")
                .isNotNull()
                .isEqualTo(images.get(0));
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
