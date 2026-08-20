package server.db.repos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.AttemptAnswer;
import server.db.entities.Bot;
import server.db.entities.BotMessage;
import server.db.entities.BotSession;
import server.db.entities.BotSource;
import server.db.entities.BotSourceType;
import server.db.entities.Difficulty;
import server.db.entities.Exam;
import server.db.entities.ExamAttempt;
import server.db.entities.ExamExecution;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionQuestion;
import server.db.entities.ExamVersionStatus;
import server.db.entities.ExecutionStatus;
import server.db.entities.Grade;
import server.db.entities.Notification;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the E2.13 test base itself works, before nine repositories are built on it.
 *
 * <p>The wipe order is the part worth testing. It is a hand-maintained list of twenty table
 * names, and every other test in the repository suite silently depends on it: if it is
 * wrong, a foreign key refuses a delete and the failure surfaces in whichever repository
 * test happened to run next, pointing at the wrong thing entirely.
 */
abstract class RepositoryFixtureContract extends RepositoryTestBase {

    @Test
    @DisplayName("the fixture seeds the reference data every query needs")
    void fixtureIsSeeded() {
        assertThat(count("subjects")).isEqualTo(2);
        assertThat(count("courses")).isEqualTo(4);
        assertThat(count("users")).isEqualTo(4);
        assertThat(count("course_teachers")).isEqualTo(3);
        assertThat(count("enrollments")).isEqualTo(3);
        assertThat(count("coordinators")).isEqualTo(1);

        assertThat(danaId).isNotEqualTo(rinaId);
        assertThat(mayaId).isPositive();
        assertThat(principalId).isPositive();
    }

    @Test
    @DisplayName("dana is both a teacher of two courses and enrolled in a third")
    void teacherCanAlsoBeEnrolled() {
        // The case that separates "taught OR enrolled" from "taught AND enrolled". The user
        // directory has to surface both, so the fixture has to contain someone who is both.
        assertThat(count("course_teachers WHERE teacher = " + danaId)).isEqualTo(2);
        assertThat(count("enrollments WHERE student = " + danaId)).isEqualTo(1);
    }

    @Test
    @DisplayName("the wipe empties all twenty tables with every one of them populated")
    void wipeOrderSurvivesAFullGraph() {
        fillEveryTable();

        // Every table now holds at least one row, so each delete in WIPE_ORDER has to run
        // while something still points at it. A wrong order fails here with a foreign key
        // violation rather than in an unrelated repository test three files away.
        wipe();

        assertThat(nonEmptyTables()).isEmpty();
    }

    /** One row in each of the twenty tables, wired into a single connected graph. */
    private void fillEveryTable() {
        runInTx(session -> {
            Instant now = Instant.parse("2026-08-20T09:00:00Z");

            Question question = new Question(COURSE_ALGEBRA, (short) 1, COURSE_ALGEBRA + "001");
            session.persist(question);
            session.flush();

            QuestionVersion version = new QuestionVersion(question.getId(), 1, "מהם שורשי המשוואה?",
                    "1, 6", "2, 3", "-2, -3", "0, 5", (byte) 2, "פונקציות", Difficulty.EASY,
                    null, danaId, now);
            session.persist(version);

            Exam exam = new Exam(COURSE_ALGEBRA, (byte) 1, SUBJECT_MATH + COURSE_ALGEBRA + "01", danaId);
            session.persist(exam);
            session.flush();

            ExamVersion examVersion = new ExamVersion(exam.getId(), 1, "מבחן אמצע", 60,
                    null, null, ExamVersionStatus.APPROVED, now);
            session.persist(examVersion);
            session.flush();

            session.persist(new ExamVersionQuestion(
                    examVersion.getId(), version.getId(), question.getId(), 100, 1));

            ExamExecution execution = new ExamExecution(examVersion.getId(), "AB12",
                    now, now.plusSeconds(3600), ExecutionStatus.CLOSED, danaId);
            session.persist(execution);
            session.flush();

            ExamAttempt attempt = new ExamAttempt(execution.getId(), mayaId, now);
            session.persist(attempt);
            session.flush();

            session.persist(new AttemptAnswer(attempt.getId(), version.getId(), (byte) 2, now));
            session.persist(new Grade(attempt.getId(), 100));

            Bot bot = new Bot(COURSE_ALGEBRA, "עוזר אלגברה");
            session.persist(bot);
            session.flush();

            session.persist(new BotSource(bot.getId(), BotSourceType.TEXT, "סיכום",
                    "raw".getBytes(StandardCharsets.UTF_8), "extracted", danaId, now));

            BotSession botSession = new BotSession(bot.getId(), mayaId, now);
            session.persist(botSession);
            session.flush();

            session.persist(new BotMessage(bot.getId(), botSession.getId(), mayaId,
                    "שאלה", "תשובה", "deepseek", now));

            session.persist(new Notification(mayaId, "GRADE_PUBLISHED", "ציון פורסם",
                    "הציון שלך זמין", "GRADE", 1L, now));
        });
    }

    private long count(String fromClause) {
        return inTx(session -> session
                .createNativeQuery("SELECT COUNT(*) FROM " + fromClause, Long.class)
                .getSingleResult());
    }

    private List<String> nonEmptyTables() {
        return TABLES.stream().filter(table -> count(table) > 0).toList();
    }

    private static final List<String> TABLES = List.of(
            "subjects", "courses", "users", "course_teachers", "enrollments", "coordinators",
            "questions", "question_versions", "exams", "exam_versions", "exam_version_questions",
            "exam_executions", "exam_attempts", "attempt_answers", "grades",
            "bots", "bot_sources", "bot_sessions", "bot_messages", "notifications");
}
