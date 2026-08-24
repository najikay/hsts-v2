package server.db.repos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.hibernate.JDBCException;
import org.hibernate.exception.ConstraintViolationException;
import server.db.TestDatabase;
import server.db.TestDatabases;
import server.db.entities.Difficulty;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.projections.ExamCompositionHeader;
import server.db.projections.PinnedQuestion;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The exam builder's write surface on the real Flyway schema.
 *
 * <p>Only what H2 cannot reach lives here. The composite foreign key and the points {@code CHECK}
 * exist on this engine and nowhere else, because the entities map no association and declare no
 * {@code @Check}, so schema generation emits neither; in the contract those tests would pass
 * whatever the schema said, which is the failure mode the two-engine split exists to prevent. The
 * two Hebrew round trips are here for the collation and charset, which H2's MySQL mode does not
 * reproduce.
 *
 * <p><b>The two unique constraints are deliberately not here.</b> They are declared on
 * {@code ExamVersionQuestion}'s {@code @Table}, so H2 generates them too and their tests belong in
 * the contract where both engines run them. An earlier version of this class held them, which left
 * T-3.9's named acceptance rule guarded only when {@code HSTS_REQUIRE_MYSQL=true}.
 *
 * <p>The soft-delete test is the odd one out and is here for the opposite reason: it is the rule
 * that has <b>no</b> backstop, because a soft delete is an {@code UPDATE} and no foreign key fires
 * on an update. ARCHITECTURE §5 assigns it to the E7 validator by name, and this test is what
 * stands in for the constraint that cannot exist - so it is pinned against the real schema rather
 * than against H2's approximation of it.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class ExamBuildRepositoryMySqlTest extends ExamBuildRepositoryContract {

    private static final Instant WHEN =
            Instant.parse("2026-08-24T09:00:00Z").truncatedTo(ChronoUnit.MILLIS);

    private final ExamBuildRepository repository = new ExamBuildRepository();

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }


    @Test
    @DisplayName("a pin whose question_id does not match its version is refused by the composite key")
    void theDenormalisedQuestionIdIsPoliced() {
        // The composite FK on (question_version_id, question_id) is what stops question_id being
        // an unpoliced copy. Without it, writing the wrong one would make
        // uq_exam_version_questions_question - tested in the contract - guard nothing at all,
        // because it keys on the value that was written.
        long versionId = draftVersionHere();
        long real = questionWithTwoVersions();
        long other = questionWithTwoVersions();

        assertThatThrownBy(() -> runInTx(session -> repository.replaceComposition(session, versionId,
                List.of(new ExamBuildRepository.Pin(versionIdHere(session, real, 1), other, 100, 1)))))
                // Named rather than non-null: isNotNull() would also pass for an NPE out of the
                // fixture, which is the failure this test could not otherwise distinguish.
                .as("fk_evq_question_version refuses a (version, question) pair that is not real")
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("fk_evq_question_version");
    }

    @Test
    @DisplayName("points outside 1..100 are refused by the schema as well as by the validator")
    void pointsAreBoundedByTheSchemaToo() {
        long versionId = draftVersionHere();
        long questionId = questionWithTwoVersions();

        assertThatThrownBy(() -> runInTx(session -> repository.replaceComposition(session, versionId,
                List.of(new ExamBuildRepository.Pin(versionIdHere(session, questionId, 1), questionId, 0, 1)))))
                // JDBCException, not ConstraintViolationException, and that is measured rather
                // than sloppy: Hibernate's MySQL dialect maps unique and foreign-key errors to
                // ConstraintViolationException but leaves a CHECK violation as a
                // GenericJDBCException, so the tighter type would fail here for a reason that has
                // nothing to do with the schema. Asserting the shared supertype plus the
                // constraint name still separates this from a fixture NPE, which is the whole
                // point of not writing isNotNull().
                .as("ck_evq_points: the validator refuses this first, and the column refuses it too")
                .isInstanceOf(JDBCException.class)
                .hasMessageContaining("ck_evq_points");
    }

    @Test
    @DisplayName("a soft-deleted question is still pinnable by the schema, so the rule is the service's")
    void softDeleteHasNoDatabaseBackstop() {
        // Not a bug being pinned as behaviour: the point is that this WRITE SUCCEEDS. Soft delete
        // is an UPDATE and no foreign key fires on one, so nothing below the service refuses a
        // deleted question. That is precisely why ExamValidator has to, and why removing its check
        // would produce a paper carrying a question the bank considers gone with no test failing
        // anywhere near the database.
        long versionId = draftVersionHere();
        long questionId = questionWithTwoVersions();
        runInTx(session -> session.get(Question.class, questionId).setDeletedAt(WHEN));

        runInTx(session -> repository.replaceComposition(session, versionId,
                List.of(new ExamBuildRepository.Pin(versionIdHere(session, questionId, 1), questionId, 100, 1))));

        List<PinnedQuestion> pinnedAnyway = inTx(session -> repository.findComposition(session, versionId));
        assertThat(pinnedAnyway)
                .as("the database allowed it, so the refusal has to come from ExamValidator")
                .hasSize(1);
    }

    @Test
    @DisplayName("Hebrew survives the round trip through name and both text blocks")
    void hebrewRoundTrips() {
        long examId = inTx(session -> repository.insertExam(session, COURSE_ALGEBRA, danaId));
        long versionId = inTx(session -> repository.insertDraftVersion(session, examId, 1,
                "מבחן באלגברה - מחצית א׳", 90,
                "קראו את השאלות בעיון לפני שתתחילו.",
                "מחוון: 10 נקודות לכל סעיף.", WHEN));

        ExamCompositionHeader header = inTx(session ->
                repository.findCompositionHeader(session, versionId)).orElseThrow();

        assertThat(header.name()).isEqualTo("מבחן באלגברה - מחצית א׳");
        assertThat(header.studentText()).isEqualTo("קראו את השאלות בעיון לפני שתתחילו.");
        assertThat(header.teacherText()).isEqualTo("מחוון: 10 נקודות לכל סעיף.");
    }

    @Test
    @DisplayName("a Hebrew question stem reaches the paper unchanged")
    void hebrewReachesTheComposition() {
        long versionId = draftVersionHere();
        long questionId = questionWithTwoVersions();

        runInTx(session -> repository.replaceComposition(session, versionId,
                List.of(new ExamBuildRepository.Pin(versionIdHere(session, questionId, 1), questionId, 100, 1))));

        List<PinnedQuestion> paper = inTx(session -> repository.findComposition(session, versionId));

        assertThat(paper).singleElement().satisfies(pinned -> {
            assertThat(pinned.text()).isEqualTo("כמה פתרונות יש למשוואה?");
            assertThat(pinned.topic()).isEqualTo("פונקציות");
        });
    }

    // ===================== Fixture ========================================
    // Private copies rather than reaching into the contract's, because these tests need a
    // question with a known second version and the contract's helpers do not expose one.

    private long draftVersionHere() {
        long examId = inTx(session -> repository.insertExam(session, COURSE_ALGEBRA, danaId));
        return inTx(session -> repository.insertDraftVersion(session, examId, 1,
                "מבחן", 90, null, null, WHEN));
    }

    private long questionWithTwoVersions() {
        long questionId = inTx(session -> {
            short serial = (short) (session.createQuery("select count(q) from Question q", Long.class)
                    .uniqueResult() + 1);
            Question question = new Question(COURSE_ALGEBRA, serial,
                    COURSE_ALGEBRA + String.format("%03d", serial));
            session.persist(question);
            session.flush();
            return question.getId();
        });
        for (int versionNo = 1; versionNo <= 2; versionNo++) {
            int number = versionNo;
            runInTx(session -> session.persist(new QuestionVersion(questionId, number,
                    "כמה פתרונות יש למשוואה?", "אחד", "שניים", "אינסוף", "אף אחד",
                    (byte) 3, "פונקציות", Difficulty.MEDIUM, null, danaId, WHEN)));
        }
        return questionId;
    }

    private long versionIdHere(org.hibernate.Session session, long questionId, int versionNo) {
        return session.createQuery("""
                        select qv.id from QuestionVersion qv
                        where qv.questionId = :questionId and qv.versionNo = :versionNo
                        """, Long.class)
                .setParameter("questionId", questionId)
                .setParameter("versionNo", versionNo)
                .uniqueResult();
    }
}
