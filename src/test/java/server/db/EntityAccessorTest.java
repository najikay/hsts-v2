package server.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.entities.AttemptAnswer;
import server.db.entities.Bot;
import server.db.entities.BotMessage;
import server.db.entities.BotSession;
import server.db.entities.BotSource;
import server.db.entities.BotSourceType;
import server.db.entities.BotTranscript;
import server.db.entities.ColumnSizes;
import server.db.entities.Coordinator;
import server.db.entities.Course;
import server.db.entities.CourseTeacher;
import server.db.entities.Difficulty;
import server.db.entities.Enrollment;
import server.db.entities.Exam;
import server.db.entities.ExamAttempt;
import server.db.entities.ExamExecution;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionQuestion;
import server.db.entities.ExamVersionStatus;
import server.db.entities.ExecutionStats;
import server.db.entities.ExecutionStatus;
import server.db.entities.Grade;
import server.db.entities.Notification;
import server.db.entities.Participation;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.entities.Subject;
import server.db.entities.User;
import server.db.entities.UserRole;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that each entity's constructor puts every value in the field it belongs to, and
 * that the accessors hand the same value back (E2.9). No database needed.
 *
 * <p>The reason this is worth writing out rather than trusting: {@link QuestionVersion}
 * takes thirteen constructor arguments, four of them adjacent strings of the same type.
 * Swapping {@code a2} and {@code a3} compiles, persists, validates against the schema,
 * and produces an exam where two answers are in the wrong order — visible only to a
 * student sitting it. Distinct values per field is what turns that into a failing test.
 */
class EntityAccessorTest {

    private static final Instant T = Instant.parse("2026-08-19T09:00:00Z");

    @Test
    @DisplayName("reference data and users")
    void coreEntities() {
        Subject subject = new Subject("10", "מתמטיקה");
        assertThat(subject.getCode()).isEqualTo("10");
        assertThat(subject.getName()).isEqualTo("מתמטיקה");

        Course course = new Course("11", "10", "אלגברה");
        assertThat(course.getCode()).isEqualTo("11");
        assertThat(course.getSubjectCode()).isEqualTo("10");
        assertThat(course.getName()).isEqualTo("אלגברה");

        User user = new User("dana.cohen", "$2a$12$hash", "דנה כהן", UserRole.TEACHER, "123456789");
        assertThat(user.getUsername()).isEqualTo("dana.cohen");
        assertThat(user.getPasswordHash()).isEqualTo("$2a$12$hash");
        assertThat(user.getFullName()).isEqualTo("דנה כהן");
        assertThat(user.getRole()).isEqualTo(UserRole.TEACHER);
        assertThat(user.getNationalId()).isEqualTo("123456789");
        assertThat(user.getId()).isNull();
    }

    @Test
    @DisplayName("membership links")
    void membershipEntities() {
        CourseTeacher teaches = new CourseTeacher("11", 7L);
        assertThat(teaches.getCourseCode()).isEqualTo("11");
        assertThat(teaches.getTeacherId()).isEqualTo(7L);
        assertThat(teaches.getId().getCourseCode()).isEqualTo("11");

        Enrollment enrolled = new Enrollment("21", 42L);
        assertThat(enrolled.getCourseCode()).isEqualTo("21");
        assertThat(enrolled.getStudentId()).isEqualTo(42L);
        assertThat(enrolled.getId().getStudentId()).isEqualTo(42L);

        Coordinator coordinator = new Coordinator("10", 7L);
        assertThat(coordinator.getSubjectCode()).isEqualTo("10");
        assertThat(coordinator.getTeacherId()).isEqualTo(7L);
        coordinator.setTeacherId(8L);
        assertThat(coordinator.getTeacherId()).isEqualTo(8L);
    }

    @Test
    @DisplayName("a question and its version, every field distinct")
    void bankEntities() {
        Question question = new Question("11", (short) 7, "11007");
        assertThat(question.getCourseCode()).isEqualTo("11");
        assertThat(question.getSerial()).isEqualTo((short) 7);
        assertThat(question.getDisplayId()).isEqualTo("11007");
        assertThat(question.isDeleted()).isFalse();
        assertThat(question.getDeletedAt()).isNull();
        assertThat(question.getLockVersion()).isZero();
        assertThat(question.getId()).isNull();

        // Four different answers, in order, so a swapped pair cannot pass.
        QuestionVersion version = new QuestionVersion(5L, 3, "מה השאלה?",
                "ראשונה", "שנייה", "שלישית", "רביעית",
                (byte) 2, "פונקציות", Difficulty.HARD,
                "img".getBytes(StandardCharsets.UTF_8), 9L, T);

        assertThat(version.getQuestionId()).isEqualTo(5L);
        assertThat(version.getVersionNo()).isEqualTo(3);
        assertThat(version.getText()).isEqualTo("מה השאלה?");
        assertThat(version.getA1()).isEqualTo("ראשונה");
        assertThat(version.getA2()).isEqualTo("שנייה");
        assertThat(version.getA3()).isEqualTo("שלישית");
        assertThat(version.getA4()).isEqualTo("רביעית");
        assertThat(version.getCorrectAnswer()).isEqualTo((byte) 2);
        assertThat(version.getTopic()).isEqualTo("פונקציות");
        assertThat(version.getDifficulty()).isEqualTo(Difficulty.HARD);
        assertThat(version.getCreatedBy()).isEqualTo(9L);
        assertThat(version.getCreatedAt()).isEqualTo(T);
        assertThat(version.hasImage()).isTrue();
    }

    @Test
    @DisplayName("a question version without an illustration reports no image")
    void questionVersionWithoutImage() {
        QuestionVersion version = new QuestionVersion(1L, 1, "טקסט", "א", "ב", "ג", "ד",
                (byte) 1, "נושא", Difficulty.MEDIUM, null, 1L, T);

        assertThat(version.getImage()).isNull();
        assertThat(version.hasImage()).isFalse();
    }

    @Test
    @DisplayName("an empty image array is not an image")
    void emptyImageIsNotAnImage() {
        QuestionVersion version = new QuestionVersion(1L, 1, "טקסט", "א", "ב", "ג", "ד",
                (byte) 1, "נושא", Difficulty.MEDIUM, new byte[0], 1L, T);

        assertThat(version.hasImage()).isFalse();
    }

    @Test
    @DisplayName("exams, versions and composition")
    void examEntities() {
        Exam exam = new Exam("11", (byte) 4, "101104", 9L);
        assertThat(exam.getCourseCode()).isEqualTo("11");
        assertThat(exam.getSerial()).isEqualTo((byte) 4);
        assertThat(exam.getDisplayId()).isEqualTo("101104");
        assertThat(exam.getAuthorId()).isEqualTo(9L);
        assertThat(exam.getLockVersion()).isZero();

        ExamVersion version = new ExamVersion(3L, 2, "מבחן אמצע", 90,
                "לתלמיד", "למורה", ExamVersionStatus.DRAFT, T);
        assertThat(version.getExamId()).isEqualTo(3L);
        assertThat(version.getVersionNo()).isEqualTo(2);
        assertThat(version.getName()).isEqualTo("מבחן אמצע");
        assertThat(version.getDurationMinutes()).isEqualTo(90);
        assertThat(version.getStudentText()).isEqualTo("לתלמיד");
        assertThat(version.getTeacherText()).isEqualTo("למורה");
        assertThat(version.getStatus()).isEqualTo(ExamVersionStatus.DRAFT);
        assertThat(version.getCreatedAt()).isEqualTo(T);
        assertThat(version.getLockVersion()).isZero();

        ExamVersionQuestion link = new ExamVersionQuestion(3L, 12L, 5L, 25, 4);
        assertThat(link.getExamVersionId()).isEqualTo(3L);
        assertThat(link.getQuestionVersionId()).isEqualTo(12L);
        assertThat(link.getQuestionId()).isEqualTo(5L);
        assertThat(link.getPoints()).isEqualTo(25);
        assertThat(link.getOrdinal()).isEqualTo(4);
    }

    @Test
    @DisplayName("executions, attempts and answers")
    void executionEntities() {
        ExamExecution execution = new ExamExecution(3L, "XY99", T, T.plusSeconds(3600),
                ExecutionStatus.SCHEDULED, 9L);
        assertThat(execution.getExamVersionId()).isEqualTo(3L);
        assertThat(execution.getCode()).isEqualTo("XY99");
        assertThat(execution.getOpenAt()).isEqualTo(T);
        assertThat(execution.getCloseAt()).isEqualTo(T.plusSeconds(3600));
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.SCHEDULED);
        assertThat(execution.getCreatedBy()).isEqualTo(9L);
        assertThat(execution.getStats()).isNull();
        assertThat(execution.getParticipation()).isNull();
        assertThat(execution.getLockVersion()).isZero();

        execution.setCloseAt(T.plusSeconds(7200));
        assertThat(execution.getCloseAt()).isEqualTo(T.plusSeconds(7200));

        execution.setStats(new ExecutionStats(1, 2, 3, 4, 5, 0.5, List.of(1)));
        execution.setParticipation(new Participation(3, 2, 1));
        assertThat(execution.getStats().max()).isEqualTo(5);
        assertThat(execution.getParticipation().timedOut()).isEqualTo(1);

        ExamAttempt attempt = new ExamAttempt(4L, 8L, T);
        assertThat(attempt.getExecutionId()).isEqualTo(4L);
        assertThat(attempt.getStudentId()).isEqualTo(8L);
        assertThat(attempt.getStartedAt()).isEqualTo(T);
        assertThat(attempt.isInProgress()).isTrue();

        AttemptAnswer answer = new AttemptAnswer(4L, 12L, (byte) 2, T);
        assertThat(answer.getAttemptId()).isEqualTo(4L);
        assertThat(answer.getQuestionVersionId()).isEqualTo(12L);
        assertThat(answer.getSelected()).isEqualTo((byte) 2);
        assertThat(answer.getSavedAt()).isEqualTo(T);
        assertThat(answer.isAnswered()).isTrue();

        AttemptAnswer untouched = new AttemptAnswer(4L, 13L, null, T);
        assertThat(untouched.isAnswered()).isFalse();
        assertThat(untouched.getSelected()).isNull();
    }

    @Test
    @DisplayName("a grade before anyone has approved it")
    void gradeEntity() {
        Grade grade = new Grade(6L, 74);

        assertThat(grade.getAttemptId()).isEqualTo(6L);
        assertThat(grade.getAutoScore()).isEqualTo(74);
        assertThat(grade.getFinalScore()).isNull();
        assertThat(grade.getEffectiveScore()).isEqualTo(74);
        assertThat(grade.getOverrideReason()).isNull();
        assertThat(grade.getTeacherComment()).isNull();
        assertThat(grade.getApprovedBy()).isNull();
        assertThat(grade.getApprovedAt()).isNull();
        assertThat(grade.isVisibleToStudent()).isFalse();
        assertThat(grade.getLockVersion()).isZero();
    }

    @Test
    @DisplayName("approving a grade that was already overridden keeps the override")
    void approvalDoesNotUndoAnOverride() {
        Grade grade = new Grade(6L, 74);
        grade.override(90, "השאלה הייתה שגויה");

        grade.approve(3L, T);

        assertThat(grade.getFinalScore())
                .as("approval must not reset the teacher's score to the machine's")
                .isEqualTo(90);
        assertThat(grade.getAutoScore()).isEqualTo(74);
    }

    @Test
    @DisplayName("bot, sources, sessions and messages")
    void botEntities() {
        Bot bot = new Bot("21", "עוזר ג'אווה");
        assertThat(bot.getCourseCode()).isEqualTo("21");
        assertThat(bot.getName()).isEqualTo("עוזר ג'אווה");
        assertThat(bot.isActive()).isTrue();
        bot.setName("עוזר תכנות");
        bot.setActive(false);
        assertThat(bot.getName()).isEqualTo("עוזר תכנות");
        assertThat(bot.isActive()).isFalse();

        BotSource source = new BotSource(2L, BotSourceType.PDF, "מצגת",
                "pdf".getBytes(StandardCharsets.UTF_8), "טקסט", 9L, T);
        assertThat(source.getBotId()).isEqualTo(2L);
        assertThat(source.getType()).isEqualTo(BotSourceType.PDF);
        assertThat(source.getTitle()).isEqualTo("מצגת");
        assertThat(new String(source.getRaw(), StandardCharsets.UTF_8)).isEqualTo("pdf");
        assertThat(source.getExtractedText()).isEqualTo("טקסט");
        assertThat(source.getAddedBy()).isEqualTo(9L);
        assertThat(source.getUpdatedAt()).isEqualTo(T);
        assertThat(source.getVersion()).isOne();
        assertThat(source.getLockVersion()).isZero();
        source.setTitle("מצגת מעודכנת");
        assertThat(source.getTitle()).isEqualTo("מצגת מעודכנת");

        BotSession session = new BotSession(2L, 8L, T);
        assertThat(session.getBotId()).isEqualTo(2L);
        assertThat(session.getStudentId()).isEqualTo(8L);
        assertThat(session.getStartedAt()).isEqualTo(T);
        assertThat(session.getUpdatedAt()).isEqualTo(T);
        assertThat(session.getTranscript().turns()).isEmpty();

        BotMessage message = new BotMessage(2L, 5L, 8L, "שאלה", "תשובה", "anthropic", T);
        assertThat(message.getBotId()).isEqualTo(2L);
        assertThat(message.getSessionId()).isEqualTo(5L);
        assertThat(message.getStudentId()).isEqualTo(8L);
        assertThat(message.getQuestion()).isEqualTo("שאלה");
        assertThat(message.getAnswer()).isEqualTo("תשובה");
        assertThat(message.getProvider()).isEqualTo("anthropic");
        assertThat(message.getAskedAt()).isEqualTo(T);
    }

    @Test
    @DisplayName("getRaw hands out a copy, so a caller cannot corrupt the source")
    void botSourceRawIsCopied() {
        BotSource source = new BotSource(1L, BotSourceType.TEXT, "כותרת",
                "abc".getBytes(StandardCharsets.UTF_8), "abc", 1L, T);

        source.getRaw()[0] = 'z';

        assertThat(new String(source.getRaw(), StandardCharsets.UTF_8)).isEqualTo("abc");
    }

    @Test
    @DisplayName("a notification carries its deep link")
    void notificationEntity() {
        Notification notification = new Notification(8L, "EXAM_APPROVED", "המבחן אושר",
                "מבחן 101104 אושר", "examVersion", 12L, T);

        assertThat(notification.getUserId()).isEqualTo(8L);
        assertThat(notification.getType()).isEqualTo("EXAM_APPROVED");
        assertThat(notification.getTitle()).isEqualTo("המבחן אושר");
        assertThat(notification.getBody()).isEqualTo("מבחן 101104 אושר");
        assertThat(notification.getRefType()).isEqualTo("examVersion");
        assertThat(notification.getRefId()).isEqualTo(12L);
        assertThat(notification.getCreatedAt()).isEqualTo(T);
        assertThat(notification.getReadAt()).isNull();
        assertThat(notification.isUnread()).isTrue();
    }

    @Test
    @DisplayName("a transcript built from null turns is empty rather than broken")
    void transcriptTolerantOfNull() {
        assertThat(new BotTranscript(null).turns()).isEmpty();
        assertThat(BotTranscript.empty().turns()).isEmpty();
    }

    @Test
    @DisplayName("statistics with no deciles are empty rather than null")
    void statsTolerantOfNull() {
        assertThat(new ExecutionStats(0, 0, 0, 0, 0, 0, null).deciles()).isEmpty();
    }

    @Test
    @DisplayName("the column sizes are MySQL's documented maxima")
    void columnSizesAreMySqlMaxima() {
        // Wrong by one and Hibernate picks the next type up, which the schema validation
        // catches — but only where a MySQL server is reachable. Pin them here too.
        assertThat(ColumnSizes.TEXT).isEqualTo(65_535);
        assertThat(ColumnSizes.MEDIUM).isEqualTo(16_777_215);
    }
}
