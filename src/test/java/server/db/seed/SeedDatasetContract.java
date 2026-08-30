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
import server.features.grading.AutoGrader;
import server.features.grading.ScoreStatistics;

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
            // Five subjects and seven courses since U-42 (2026-08-30, live session): Biology 30,
            // Chemistry 40 and Physics 50, one course each.
            "subjects", 5L,
            "courses", 7L,
            // 21 since U-42: one teacher per new subject.
            "users", 21L,
            // Eight. It was five for a while: the 2026-08-20 roster decision removed rina.barak
            // from Calculus, making her a pure coordinator with no course_teachers row, and
            // U-42 then added one teacher to each of the three new courses.
            "course_teachers", 8L,
            // One per subject (S-1), and U-42's three teachers each coordinate their own.
            "coordinators", 5L,
            // Seed 6 states no row total, only per-course figures: 11 -> 8, 12 -> 6,
            // 21 -> 8, 22 -> 7, and U-42's 31 -> 6, 41 -> 6, 51 -> 6. Those sum to 47, and
            // enrollmentTotalsMatch() asserts each one separately so this number cannot be
            // satisfied by the wrong distribution.
            "enrollments", 47L,
            // 58 and 61 since U-42: six questions in each new course, one version each.
            "questions", 58L,
            "question_versions", 61L,
            // 9 since 2026-08-26: B-25 added N-GRADE-MAYA, so DEMO_DAY's sign-in account
            // has a bell at all. 10 since 2026-08-29: U-34 added N-GRADING-DUE-ALG, so the
            // teacher the demo signs in as is told her own sitting is waiting. U-43 added two
            // sittings and eleven grades and left this at 10 on purpose: seed 11 explains that
            // the idempotency key is recipient + type + title, and eight of those eleven grades
            // belong to students who already hold a GRADE_PUBLISHED row.
            "notifications", 10L);

    private SeedLoader loader() {
        return new SeedLoader(factory(), java.time.Clock.fixed(ANCHOR, java.time.ZoneOffset.UTC),
                SeedDataset.sections());
    }

    @Test
    @DisplayName("every table holds exactly the number of rows the document states")
    void rowCountsMatchTheDocument() {
        EXPECTED_ROWS.forEach((table, expected) ->
                assertThat(count(table)).as("%s", table).isEqualTo(expected));

        // §8, moved by U-43: the Biology exam is one exam, one version and five slots.
        assertThat(count("exams")).isEqualTo(7);
        assertThat(count("exam_versions")).isEqualTo(8);
        assertThat(count("exam_version_questions")).isEqualTo(44);

        // §9 and §9.4, moved by U-34 (2026-08-29, manual round 3): execution 5 is one sitting,
        // four attempts, twenty-eight answers and four grades. These four counts were not
        // asserted anywhere until then, which is how the seed grew a sitting with nothing
        // watching the totals. §9.5 and §9.6 move them again with U-43 (2026-08-30): two
        // sittings, eleven attempts, 6x7 + 5x5 = 67 answers and eleven grades.
        assertThat(count("exam_executions")).isEqualTo(7);
        assertThat(count("exam_attempts")).isEqualTo(31);
        assertThat(count("attempt_answers")).isEqualTo(203);
        assertThat(count("grades")).isEqualTo(31);
    }

    @Test
    @DisplayName("the summary reports what was actually inserted")
    void summaryAgreesWithTheDatabase() {
        assertThat(summary().outcome()).isEqualTo(SeedOutcome.LOADED);
        assertThat(summary().rowsByTable()).containsEntry("users", 21);
        assertThat(summary().rowsByTable()).containsEntry("questions", 58);
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
        assertThat(count("users")).isEqualTo(21);
        assertThat(count("question_versions")).isEqualTo(61);
        assertThat(count("exam_version_questions")).isEqualTo(44);
        assertThat(count("notifications")).isEqualTo(10);
        assertThat(count("exam_executions")).isEqualTo(7);
        assertThat(count("exam_attempts")).isEqualTo(31);
        assertThat(count("grades")).isEqualTo(31);
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
                    .contains("notifications: this dataset says 10, the database says 11");
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

        assertThat(totals).hasSize(8);
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

        assertThat(all).hasSize(61);
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
        // 61 since U-42, and the illustrated count deliberately did not move with it: the three
        // new courses carry no pictures, so eighteen more versions is eighteen more null blobs.
        assertThat(count("question_versions")).isEqualTo(61);

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

        assertThat(hashes).hasSize(21);
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
        // PRD 4.1, and the c2b9c0f sweep put log and user-facing strings in scope. The seed
        // document itself uses em dashes freely in prose, so this is the guard that stops them
        // travelling into the product.
        //
        // What it reads is stringsContaining's list, not every string column in the schema. The
        // list was exam, question, notification title, course, subject and user text until
        // 2026-08-27, when B-13 added the bot surface, the two grade comments and the
        // notification body: the name said "no stored user-visible string" while the whole bot
        // feature, the comment a student reads on their own grade, and the sentence under every
        // notification title were outside it. The body was missed on the first pass of that same
        // fix and found by the cold read, which is the argument for the cold read: the list was
        // widened by someone who had just convinced himself he knew what was on it.
        //
        // Still not covered: bot_sessions.transcript, because it is a JSON document rather than
        // a mapped column and no HQL projection reaches its turns. A dash could enter the product
        // there without failing this test. BotSource.raw is excluded for a different reason: it
        // is byte[], the pre-extraction upload, and nothing renders it.
        //
        // This list is columns, not screens. It says nothing about text the client composes at
        // render time, which is where PRD 4.1's other half lives.
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
        // ⚑ U-42. Six each, and Biology's six is load-bearing in the other direction from
        // Algebra's eight: five of the six sat execution 7, so the roster and the attempt list
        // deliberately differ where §9.1's are identical.
        assertThat(enrolledIn("31")).isEqualTo(6);
        assertThat(enrolledIn("41")).isEqualTo(6);
        assertThat(enrolledIn("51")).isEqualTo(6);
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

        // ⚑ U-42. Three more dual-hat rows, and the consequence FacultySection records rather
        // than leaves to be discovered: each of these three is the only teacher in her subject
        // and its coordinator, so she is the approver of her own exams. That is legal and it is
        // the opposite of the demo's approval story, which stays on rina.barak and
        // michal.sharon. Asserted rather than described, so removing the shape fails here.
        for (String teacher : List.of("galit.stern", "orly.navon", "sivan.adler")) {
            assertThat(coursesTaughtBy(teacher)).as("%s teaches one course", teacher).isEqualTo(1);
            assertThat(coordinatedSubjectsOf(teacher))
                    .as("%s coordinates one subject", teacher).isEqualTo(1);
        }
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

    /**
     * ⚑ <b>Every seeded {@code auto_score} is recomputed, by the product's own grader.</b>
     *
     * <p>This is the check §9.1's own warning asks for. An earlier draft of that section used
     * scores like 92 and 78, which no combination of a 6x15 + 10 paper can produce: invisible
     * while the seed was only demo data, and wrong the first time {@code AutoGrader} recomputed
     * one. {@code SeedLoadedDbContract} cannot catch it, because it compares the loaded score
     * against the document's and both would carry the same impossible number.
     *
     * <p>So this recomputes rather than compares, and it recomputes by <b>calling
     * {@link AutoGrader#grade} itself</b> with the exam version's pinned questions and the
     * attempt's saved answers, exactly as {@code GradingService} does. A score the seed states
     * that the grader would not produce fails here, and so does an answer grid quietly edited
     * without its total.
     *
     * <p><b>It is driven by what is in the database, not by a list of sittings written here.</b>
     * Every grade is recomputed, so a new execution is covered the day it is seeded rather than
     * the day somebody remembers to add its number to a loop. The sittings it found are asserted
     * afterwards, so "covered everything" cannot be satisfied by covering nothing.
     */
    @Test
    @DisplayName("⚑ every seeded auto score is what AutoGrader produces from the seeded answers")
    void everyAutoScoreIsWhatTheGraderProduces() {
        Map<Long, List<AutoGrader.PinnedQuestion>> papers = inTx(session -> session.createQuery("""
                        select evq.id.examVersionId, evq.id.questionVersionId, evq.points,
                               qv.correctAnswer
                        from ExamVersionQuestion evq, QuestionVersion qv
                        where qv.id = evq.id.questionVersionId
                        order by evq.id.examVersionId, evq.ordinal
                        """, Object[].class).getResultList()).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        row -> ((Number) row[0]).longValue(),
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(
                                row -> new AutoGrader.PinnedQuestion(((Number) row[1]).longValue(),
                                        ((Number) row[2]).intValue(),
                                        ((Number) row[3]).byteValue()),
                                java.util.stream.Collectors.toList())));

        Map<Long, Map<Long, Byte>> chosen = new java.util.HashMap<>();
        for (Object[] row : inTx(session -> session.createQuery("""
                select aa.id.attemptId, aa.id.questionVersionId, aa.selected
                from AttemptAnswer aa
                """, Object[].class).getResultList())) {
            chosen.computeIfAbsent(((Number) row[0]).longValue(), key -> new java.util.HashMap<>())
                    .put(((Number) row[1]).longValue(), (Byte) row[2]);
        }

        List<Object[]> graded = inTx(session -> session.createQuery("""
                select g.attemptId, g.autoScore, x.code, u.username, x.examVersionId
                from Grade g, ExamAttempt a, ExamExecution x, User u
                where a.id = g.attemptId and x.id = a.executionId and u.id = a.studentId
                """, Object[].class).getResultList());

        assertThat(graded).as("every seeded attempt carries a grade").hasSize(31);

        for (Object[] row : graded) {
            long attemptId = ((Number) row[0]).longValue();
            int stored = ((Number) row[1]).intValue();
            String code = (String) row[2];
            String student = (String) row[3];
            List<AutoGrader.PinnedQuestion> paper = papers.get(((Number) row[4]).longValue());

            // A question with no attempt_answers row is simply absent from the map, which is
            // how the grader is told "unanswered" and why omer.katz's four dashes are rows
            // that do not exist rather than rows holding a null.
            int recomputed = AutoGrader.grade(paper,
                    chosen.getOrDefault(attemptId, Map.of())).score();

            assertThat(recomputed)
                    .as("sitting %s, %s: the seeded auto score is not what AutoGrader produces "
                            + "from the seeded answers", code, student)
                    .isEqualTo(stored);
        }

        // U-34 added sitting 3318 and U-43 added 6120 and 7745, and this is the assertion that
        // says so: the loop above is data-driven, so without this it would still pass over three
        // sittings, or two.
        assertThat(graded).extracting(row -> row[2]).as("every graded sitting is recomputed")
                .containsOnly("4821", "7390", "3318", "6120", "7745");
        assertThat(graded).filteredOn(row -> row[2].equals("3318"))
                .as("execution 5's four AUTO grades, dana.cohen's awaiting-grading queue (U-34)")
                .hasSize(4);
        assertThat(graded).filteredOn(row -> row[2].equals("6120"))
                .as("execution 6's six approved grades (U-43)").hasSize(6);
        assertThat(graded).filteredOn(row -> row[2].equals("7745"))
                .as("execution 7's five approved grades on the non-flat paper (U-43)").hasSize(5);
    }

    /**
     * ⚑ <b>Every frozen statistics record is recomputed, by the product's own calculator.</b>
     *
     * <p>The sibling of {@link #everyAutoScoreIsWhatTheGraderProduces()}, and it exists for the
     * same reason: {@code SeedLoadedDbContract} compares the loaded statistics against the
     * document's, and a wrong mean written into both agrees with itself. Seed 9.1's own note asks
     * for exactly this - "every figure in this table is hand-checkable, which is what E12.4's
     * unit-tested against hand-computed fixtures asks for" - and until U-43 nothing recomputed
     * any of them. Three sittings carry frozen columns now, hand-written into
     * {@code ExecutionsSection} from three tables in the document, which is three places for an
     * arithmetic slip to live.
     *
     * <p>So this reads the <b>final</b> scores out of the database, runs {@link ScoreStatistics}
     * over them exactly as the grading service does at the moment a sitting's last grade is
     * approved, and compares component by component. It also asserts <b>which</b> sittings are
     * frozen, in both directions: a sitting whose grading has not finished must carry nulls, or
     * its numbers would say the opposite of what the fixture is for.
     */
    @Test
    @DisplayName("⚑ every frozen statistic is what ScoreStatistics produces from the finals")
    void everyFrozenStatisticIsRecomputed() {
        List<Object[]> frozen = inTx(session -> session.createQuery("""
                select x.code, x.stats, x.participation
                from ExamExecution x
                where x.stats is not null or x.participation is not null
                order by x.code
                """, Object[].class).getResultList());

        assertThat(frozen).extracting(row -> row[0])
                .as("only sittings whose grading is finished carry frozen columns (S-21, S-25)")
                .containsExactly("4821", "6120", "7745");

        for (Object[] row : frozen) {
            String code = (String) row[0];
            server.db.entities.ExecutionStats stored = (server.db.entities.ExecutionStats) row[1];
            server.db.entities.Participation participation =
                    (server.db.entities.Participation) row[2];

            assertThat(stored).as("sitting %s stats", code).isNotNull();
            assertThat(participation).as("sitting %s participation", code).isNotNull();

            List<Integer> finals = inTx(session -> session.createQuery("""
                    select coalesce(g.finalScore, g.autoScore)
                    from Grade g, ExamAttempt a, ExamExecution x
                    where a.id = g.attemptId and x.id = a.executionId and x.code = :code
                    """, Integer.class).setParameter("code", code).getResultList());

            ScoreStatistics recomputed = ScoreStatistics.of(finals).orElseThrow();

            assertThat(stored.average()).as("sitting %s mean", code)
                    .isEqualTo(recomputed.mean());
            assertThat(stored.median()).as("sitting %s median", code)
                    .isEqualTo(recomputed.median());
            assertThat(stored.stdDev()).as("sitting %s population sigma, divisor n", code)
                    .isEqualTo(recomputed.standardDeviation());
            assertThat(stored.min()).as("sitting %s min", code).isEqualTo(recomputed.min());
            assertThat(stored.max()).as("sitting %s max", code).isEqualTo(recomputed.max());
            assertThat(stored.passRate()).as("sitting %s pass rate, mark %d", code,
                            ScoreStatistics.PASS_MARK)
                    .isEqualTo(recomputed.passRate());
            assertThat(stored.deciles()).as("sitting %s deciles", code)
                    .isEqualTo(recomputed.deciles());

            // Participation is not a statistic and ScoreStatistics knows nothing about it, so it
            // is recomputed from the attempt rows themselves: started is every attempt, finished
            // is the SUBMITTED ones and timed_out the rest (S-21).
            List<Object[]> counts = inTx(session -> session.createQuery("""
                    select cast(a.status as string), count(a)
                    from ExamAttempt a, ExamExecution x
                    where x.id = a.executionId and x.code = :code
                    group by a.status
                    """, Object[].class).setParameter("code", code).getResultList());

            long submitted = counts.stream().filter(c -> c[0].equals("SUBMITTED"))
                    .mapToLong(c -> ((Number) c[1]).longValue()).sum();
            long timedOut = counts.stream().filter(c -> c[0].equals("TIMED_OUT"))
                    .mapToLong(c -> ((Number) c[1]).longValue()).sum();

            assertThat(participation.started()).as("sitting %s started", code)
                    .isEqualTo((int) (submitted + timedOut));
            assertThat(participation.finished()).as("sitting %s finished", code)
                    .isEqualTo((int) submitted);
            assertThat(participation.timedOut()).as("sitting %s timed out", code)
                    .isEqualTo((int) timedOut);
            assertThat(participation.started()).as("sitting %s covers every graded attempt", code)
                    .isEqualTo(finals.size());
        }
    }

    /**
     * ⚑ <b>U-43's own tripwire: the three frozen sittings do not look like each other.</b>
     *
     * <p>The point of adding two was that a report comparing sittings had one row to compare. Two
     * more rows that happened to carry the same mean, the same sigma or the same participant
     * count would satisfy every count assertion above and still leave the screen unable to show a
     * difference, so the property worth asserting is that the three are pairwise distinct on
     * every figure a report prints.
     */
    @Test
    @DisplayName("⚑ no two frozen sittings share a participant count, mean, sigma or pass rate")
    void theThreeFrozenSittingsAreDistinguishable() {
        List<Object[]> frozen = inTx(session -> session.createQuery("""
                select x.code, x.stats, x.participation
                from ExamExecution x where x.stats is not null
                """, Object[].class).getResultList());

        assertThat(frozen).hasSize(3);

        assertThat(frozen).extracting(row ->
                        ((server.db.entities.Participation) row[2]).started())
                .as("participant counts").doesNotHaveDuplicates();
        assertThat(frozen).extracting(row ->
                        ((server.db.entities.ExecutionStats) row[1]).average())
                .as("means").doesNotHaveDuplicates();
        assertThat(frozen).extracting(row ->
                        ((server.db.entities.ExecutionStats) row[1]).stdDev())
                .as("standard deviations").doesNotHaveDuplicates();
        assertThat(frozen).extracting(row ->
                        ((server.db.entities.ExecutionStats) row[1]).passRate())
                .as("pass rates").doesNotHaveDuplicates();
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
            found.addAll(matching(session,
                    "select n.body from Notification n where n.body is not null", needle));
            found.addAll(matching(session, "select c.name from Course c", needle));
            found.addAll(matching(session, "select s.name from Subject s", needle));
            found.addAll(matching(session, "select u.fullName from User u", needle));
            // The bot surface, added 2026-08-27 with B-13. It was outside this sweep while the
            // method's own name said "no stored user-visible string", and the bot chat and the
            // bot manager render every one of these four.
            found.addAll(matching(session, "select b.name from Bot b", needle));
            found.addAll(matching(session, "select bs.title from BotSource bs", needle));
            found.addAll(matching(session, "select bs.extractedText from BotSource bs", needle));
            found.addAll(matching(session, "select bm.question from BotMessage bm", needle));
            found.addAll(matching(session, "select bm.answer from BotMessage bm", needle));
            found.addAll(matching(session,
                    "select g.overrideReason from Grade g where g.overrideReason is not null",
                    needle));
            found.addAll(matching(session,
                    "select g.teacherComment from Grade g where g.teacherComment is not null",
                    needle));
            return found;
        });
    }

    private static List<String> matching(org.hibernate.Session session, String query, String needle) {
        return session.createQuery(query, String.class).getResultList().stream()
                .filter(value -> value != null && value.contains(needle))
                .toList();
    }
}
