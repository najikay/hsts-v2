package server.db;

import jakarta.persistence.OptimisticLockException;
import org.hibernate.StaleStateException;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import server.db.entities.AttemptAnswer;
import server.db.entities.Bot;
import server.db.entities.BotMessage;
import server.db.entities.BotSession;
import server.db.entities.BotSource;
import server.db.entities.BotSourceType;
import server.db.entities.BotTranscript;
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
import server.db.entities.GradeStatus;
import server.db.entities.Notification;
import server.db.entities.Participation;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.entities.Subject;
import server.db.entities.User;
import server.db.entities.UserRole;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trips every entity through a real database and back (E2.9).
 *
 * <p>Reading a field you have just written proves more than it looks like: it exercises
 * the column mapping, the enum and JSON converters, the composite-key equality, and the
 * {@code Instant} handling all at once. A getter that returns the wrong field, or a
 * converter that loses a millisecond, fails here.
 *
 * <p>This suite runs on H2 and therefore says nothing about whether the mapping matches
 * the real migrations — {@link EntityMappingValidationTest} owns that question.
 *
 * <h2>A database per test, on purpose</h2>
 *
 * <p>These tests mutate the graph they assert on — approving a grade, re-uploading a
 * source, soft-deleting a question — and they commit. Sharing one seeded database across
 * the class made them pass only because JUnit's default method order happened to be
 * favourable: under a randomised order, {@code botEntitiesRoundTrip} and
 * {@code gradeApproval} both fail, because another test has already moved the row they
 * assert is pristine.
 *
 * <p>The fix is structural rather than a rule to remember. Each test gets its own H2
 * database and its own seed, so no test can see another's writes and none of them needs
 * to be careful. {@link org.junit.jupiter.api.MethodOrderer.Random} is set deliberately
 * so that the day someone reintroduces shared state, it fails immediately rather than
 * whenever the order next changes.
 */
@TestMethodOrder(MethodOrderer.Random.class)
class EntityRoundTripTest {

    private static final Instant NOW = Instant.parse("2026-08-19T09:00:00Z");
    private static final String HEBREW = "מהי תוצאת הביטוי 2+2 במערכת בינארית?";

    private H2Support.H2Db db;
    private SessionFactory factory;

    @BeforeEach
    void startDatabase() {
        db = H2Support.fresh();
        factory = db.factory();
        seedGraph();
    }

    @AfterEach
    void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    /** One row per table, along the chain a real attempt needs. */
    private void seedGraph() {
        Transactions.runInTx(factory, session -> {
            session.persist(new Subject("10", "מתמטיקה"));
            session.persist(new Course("11", "10", "אלגברה"));
            session.persist(new User("teacher1", "$2a$12$hash", "שרה כהן", UserRole.TEACHER, "111111111"));
            session.persist(new User("student1", "$2a$12$hash", "דוד לוי", UserRole.STUDENT, "222222222"));
        });

        Transactions.runInTx(factory, session -> {
            session.persist(new CourseTeacher("11", 1L));
            session.persist(new Enrollment("11", 2L));
            session.persist(new Coordinator("10", 1L));

            session.persist(new Question("11", (short) 1, "11001"));
            session.persist(new QuestionVersion(1L, 1, HEBREW, "ארבע", "שלוש", "מאה", "אחת",
                    (byte) 1, "משוואות", Difficulty.EASY,
                    "png-bytes".getBytes(StandardCharsets.UTF_8), 1L, NOW));

            session.persist(new Exam("11", (byte) 1, "101101", 1L));
            session.persist(new ExamVersion(1L, 1, "מבחן באלגברה", 60,
                    "הוראות לתלמיד", "הערות למורה", ExamVersionStatus.APPROVED, NOW));
            session.persist(new ExamVersionQuestion(1L, 1L, 1L, 100, 1));

            session.persist(new ExamExecution(1L, "AB12", NOW, NOW.plus(2, ChronoUnit.HOURS),
                    ExecutionStatus.LIVE, 1L));
            session.persist(new ExamAttempt(1L, 2L, NOW));
            session.persist(new AttemptAnswer(1L, 1L, (byte) 3, NOW));
            session.persist(new Grade(1L, 88));

            session.persist(new Bot("11", "עוזר אלגברה"));
            session.persist(new BotSource(1L, BotSourceType.TEXT, "סיכום שיעור",
                    "raw".getBytes(StandardCharsets.UTF_8), "טקסט שחולץ", 1L, NOW));
            session.persist(new BotSession(1L, 2L, NOW));
            session.persist(new BotMessage(1L, 1L, 2L, "מה זה משתנה?", "תא בזיכרון", "deepseek-chat", NOW));

            session.persist(new Notification(2L, "GRADE_PUBLISHED", "הציון פורסם",
                    "המבחן באלגברה נבדק", "grade", 1L, NOW));
        });
    }

    @Test
    @DisplayName("reference data and users come back with their Hebrew names")
    void coreEntitiesRoundTrip() {
        Transactions.runInTx(factory, session -> {
            assertThat(session.find(Subject.class, "10").getName()).isEqualTo("מתמטיקה");

            Course course = session.find(Course.class, "11");
            assertThat(course.getSubjectCode()).isEqualTo("10");
            assertThat(course.getName()).isEqualTo("אלגברה");

            User teacher = session.find(User.class, 1L);
            assertThat(teacher.getUsername()).isEqualTo("teacher1");
            assertThat(teacher.getFullName()).isEqualTo("שרה כהן");
            assertThat(teacher.getRole()).isEqualTo(UserRole.TEACHER);
            assertThat(teacher.getNationalId()).isEqualTo("111111111");
            assertThat(teacher.getPasswordHash()).isEqualTo("$2a$12$hash");
        });
    }

    @Test
    @DisplayName("a user's toString never leaks the password hash")
    void userToStringHidesTheHash() {
        Transactions.runInTx(factory, session -> {
            String rendered = session.find(User.class, 1L).toString();

            assertThat(rendered).doesNotContain("$2a$12$hash").contains("***");
        });
    }

    @Test
    @DisplayName("composite keys round-trip and compare by value")
    void compositeKeysRoundTrip() {
        Transactions.runInTx(factory, session -> {
            CourseTeacher link = session.find(CourseTeacher.class, new CourseTeacher.Id("11", 1L));
            assertThat(link).isNotNull();
            assertThat(link.getCourseCode()).isEqualTo("11");
            assertThat(link.getTeacherId()).isEqualTo(1L);
            assertThat(link.getId()).isEqualTo(new CourseTeacher.Id("11", 1L));
            assertThat(link.getId()).hasSameHashCodeAs(new CourseTeacher.Id("11", 1L));

            assertThat(session.find(Enrollment.class, new Enrollment.Id("11", 2L))).isNotNull();
            assertThat(session.find(Coordinator.class, "10").getTeacherId()).isEqualTo(1L);
        });
    }

    @Test
    @DisplayName("a question version keeps its Hebrew text, enum, image and timestamp")
    void questionVersionRoundTrips() {
        Transactions.runInTx(factory, session -> {
            QuestionVersion version = session.find(QuestionVersion.class, 1L);

            assertThat(version.getText()).isEqualTo(HEBREW);
            assertThat(version.getA1()).isEqualTo("ארבע");
            assertThat(version.getA4()).isEqualTo("אחת");
            assertThat(version.getCorrectAnswer()).isEqualTo((byte) 1);
            assertThat(version.getDifficulty()).isEqualTo(Difficulty.EASY);
            assertThat(version.getTopic()).isEqualTo("משוואות");
            assertThat(version.getVersionNo()).isOne();
            assertThat(version.getCreatedBy()).isEqualTo(1L);
            assertThat(version.getCreatedAt()).isEqualTo(NOW);
            assertThat(version.hasImage()).isTrue();
            assertThat(new String(version.getImage(), StandardCharsets.UTF_8)).isEqualTo("png-bytes");
        });
    }

    @Test
    @DisplayName("getImage hands out a copy, so a caller cannot corrupt the loaded row")
    void imageIsDefensivelyCopied() {
        Transactions.runInTx(factory, session -> {
            QuestionVersion version = session.find(QuestionVersion.class, 1L);

            version.getImage()[0] = 0;

            assertThat(new String(version.getImage(), StandardCharsets.UTF_8)).isEqualTo("png-bytes");
        });
    }

    @Test
    @DisplayName("a question is soft-deleted rather than removed")
    void softDeleteMarksTheRow() {
        Transactions.runInTx(factory, session -> {
            Question question = session.find(Question.class, 1L);
            assertThat(question.isDeleted()).isFalse();
            assertThat(question.getDisplayId()).isEqualTo("11001");
            assertThat(question.getSerial()).isEqualTo((short) 1);
            question.setDeletedAt(NOW);
        });

        Transactions.runInTx(factory, session -> {
            Question question = session.find(Question.class, 1L);
            assertThat(question.isDeleted()).isTrue();
            assertThat(question.getDeletedAt()).isEqualTo(NOW);
            // Still present, still holding its serial — ids are never recycled.
            assertThat(question.getDisplayId()).isEqualTo("11001");
        });
    }

    @Test
    @DisplayName("exam composition keeps the denormalised question id beside its version")
    void examCompositionRoundTrips() {
        Transactions.runInTx(factory, session -> {
            ExamVersion version = session.find(ExamVersion.class, 1L);
            assertThat(version.getName()).isEqualTo("מבחן באלגברה");
            assertThat(version.getDurationMinutes()).isEqualTo(60);
            assertThat(version.getStudentText()).isEqualTo("הוראות לתלמיד");
            assertThat(version.getTeacherText()).isEqualTo("הערות למורה");
            assertThat(version.getStatus()).isEqualTo(ExamVersionStatus.APPROVED);
            assertThat(version.getRejectedReason()).isNull();

            ExamVersionQuestion link = session.find(ExamVersionQuestion.class,
                    new ExamVersionQuestion.Id(1L, 1L));
            assertThat(link.getQuestionId()).isEqualTo(1L);
            assertThat(link.getPoints()).isEqualTo(100);
            assertThat(link.getOrdinal()).isOne();

            Exam exam = session.find(Exam.class, 1L);
            assertThat(exam.getDisplayId()).isEqualTo("101101");
            assertThat(exam.getAuthorId()).isEqualTo(1L);
            assertThat(exam.getSerial()).isEqualTo((byte) 1);
        });
    }

    @Test
    @DisplayName("approve and reject move an exam version and carry the reason")
    void examVersionTransitions() {
        Transactions.runInTx(factory, session -> {
            ExamVersion version = session.find(ExamVersion.class, 1L);

            version.submitForApproval();
            assertThat(version.getStatus()).isEqualTo(ExamVersionStatus.PENDING);

            version.reject("חסרות שאלות בנושא פונקציות");
            assertThat(version.getStatus()).isEqualTo(ExamVersionStatus.REJECTED);
            assertThat(version.getRejectedReason()).isEqualTo("חסרות שאלות בנושא פונקציות");

            version.approve();
            assertThat(version.getStatus()).isEqualTo(ExamVersionStatus.APPROVED);
            assertThat(version.getRejectedReason())
                    .as("an approved version must not still show why it was once rejected")
                    .isNull();
        });
    }

    @Test
    @DisplayName("an execution round-trips its window, JSON columns and extension maths")
    void executionRoundTrips() {
        Transactions.runInTx(factory, session -> {
            ExamExecution execution = session.find(ExamExecution.class, 1L);
            assertThat(execution.getCode()).isEqualTo("AB12");
            assertThat(execution.getOpenAt()).isEqualTo(NOW);
            assertThat(execution.getCloseAt()).isEqualTo(NOW.plus(2, ChronoUnit.HOURS));
            assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.LIVE);
            assertThat(execution.getExtraMinutes()).isZero();
            assertThat(execution.getEffectiveCloseAt()).isEqualTo(execution.getCloseAt());

            execution.addExtraMinutes(15);
            assertThat(execution.getEffectiveCloseAt())
                    .isEqualTo(NOW.plus(2, ChronoUnit.HOURS).plus(15, ChronoUnit.MINUTES));

            execution.setStats(new ExecutionStats(72.5, 74.0, 11.25, 41, 98, 0.85,
                    List.of(0, 0, 0, 0, 1, 2, 5, 8, 6, 3)));
            execution.setParticipation(new Participation(25, 23, 2));
            execution.setStatus(ExecutionStatus.CLOSED);
        });

        Transactions.runInTx(factory, session -> {
            ExamExecution execution = session.find(ExamExecution.class, 1L);

            assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CLOSED);
            assertThat(execution.getExtraMinutes()).isEqualTo(15);
            assertThat(execution.getStats().average()).isEqualTo(72.5);
            assertThat(execution.getStats().deciles()).hasSize(10);
            assertThat(execution.getParticipation()).isEqualTo(new Participation(25, 23, 2));
        });
    }

    @Test
    @DisplayName("an attempt and its answers round-trip, with unanswered left null")
    void attemptRoundTrips() {
        Transactions.runInTx(factory, session -> {
            ExamAttempt attempt = session.find(ExamAttempt.class, 1L);
            assertThat(attempt.getExecutionId()).isEqualTo(1L);
            assertThat(attempt.getStudentId()).isEqualTo(2L);
            assertThat(attempt.getStartedAt()).isEqualTo(NOW);
            assertThat(attempt.isInProgress()).isTrue();
            assertThat(attempt.getEndedAt()).isNull();
            assertThat(attempt.getActualMinutes()).isNull();

            AttemptAnswer answered = session.find(AttemptAnswer.class, new AttemptAnswer.Id(1L, 1L));
            assertThat(answered.isAnswered()).isTrue();
            assertThat(answered.getSelected()).isEqualTo((byte) 3);
            assertThat(answered.getSavedAt()).isEqualTo(NOW);

            answered.select(null, NOW.plusSeconds(30));
            assertThat(answered.isAnswered())
                    .as("clearing a choice is different from never having answered — both are null")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("approving a grade fills the final score and keeps the machine's")
    void gradeApproval() {
        Transactions.runInTx(factory, session -> {
            Grade grade = session.find(Grade.class, 1L);
            assertThat(grade.getStatus()).isEqualTo(GradeStatus.AUTO);
            assertThat(grade.getAutoScore()).isEqualTo(88);
            assertThat(grade.getFinalScore()).isNull();
            assertThat(grade.getEffectiveScore()).isEqualTo(88);
            assertThat(grade.isVisibleToStudent()).isFalse();

            grade.setTeacherComment("עבודה יפה");
            grade.approve(1L, NOW);
        });

        Transactions.runInTx(factory, session -> {
            Grade grade = session.find(Grade.class, 1L);
            assertThat(grade.isVisibleToStudent()).isTrue();
            assertThat(grade.getFinalScore()).isEqualTo(88);
            assertThat(grade.getAutoScore()).isEqualTo(88);
            assertThat(grade.getApprovedBy()).isEqualTo(1L);
            assertThat(grade.getApprovedAt()).isEqualTo(NOW);
            assertThat(grade.getTeacherComment()).isEqualTo("עבודה יפה");
        });
    }

    @Test
    @DisplayName("an override keeps the machine's score for the audit trail")
    void gradeOverrideKeepsAutoScore() {
        Transactions.runInTx(factory, session -> {
            Grade grade = session.find(Grade.class, 1L);

            grade.override(95, "ניסוח השאלה היה דו-משמעי");

            assertThat(grade.getAutoScore()).isEqualTo(88);
            assertThat(grade.getFinalScore()).isEqualTo(95);
            assertThat(grade.getEffectiveScore()).isEqualTo(95);
            assertThat(grade.getOverrideReason()).isEqualTo("ניסוח השאלה היה דו-משמעי");
        });
    }

    @Test
    @DisplayName("bot entities round-trip, including the JSON transcript")
    void botEntitiesRoundTrip() {
        Transactions.runInTx(factory, session -> {
            Bot bot = session.find(Bot.class, 1L);
            assertThat(bot.getCourseCode()).isEqualTo("11");
            assertThat(bot.getName()).isEqualTo("עוזר אלגברה");
            assertThat(bot.isActive()).isTrue();

            BotSource source = session.find(BotSource.class, 1L);
            assertThat(source.getType()).isEqualTo(BotSourceType.TEXT);
            assertThat(source.getTitle()).isEqualTo("סיכום שיעור");
            assertThat(source.getExtractedText()).isEqualTo("טקסט שחולץ");
            assertThat(source.getVersion()).isOne();
            assertThat(source.getAddedBy()).isEqualTo(1L);

            BotSession botSession = session.find(BotSession.class, 1L);
            assertThat(botSession.getTranscript().turns()).isEmpty();
            assertThat(botSession.getStudentId()).isEqualTo(2L);
            assertThat(botSession.getStartedAt()).isEqualTo(NOW);

            BotMessage message = session.find(BotMessage.class, 1L);
            assertThat(message.getQuestion()).isEqualTo("מה זה משתנה?");
            assertThat(message.getAnswer()).isEqualTo("תא בזיכרון");
            assertThat(message.getProvider()).isEqualTo("deepseek-chat");
            assertThat(message.getAskedAt()).isEqualTo(NOW);
        });
    }

    @Test
    @DisplayName("re-uploading a source bumps the domain version, not the lock version")
    void sourceReuploadBumpsDomainVersion() {
        Transactions.runInTx(factory, session -> {
            BotSource source = session.find(BotSource.class, 1L);
            int before = source.getVersion();

            source.replaceContent("new".getBytes(StandardCharsets.UTF_8), "תוכן חדש", NOW.plusSeconds(60));

            assertThat(source.getVersion()).isEqualTo(before + 1);
            assertThat(source.getExtractedText()).isEqualTo("תוכן חדש");
            assertThat(source.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
        });
    }

    @Test
    @DisplayName("a transcript with turns survives storage")
    void transcriptWithTurnsRoundTrips() {
        Instant asked = NOW.plusSeconds(90);
        Transactions.runInTx(factory, session -> session.find(BotSession.class, 1L)
                .setTranscript(new BotTranscript(List.of(
                        new BotTranscript.Turn("student", "מה זה לולאה?", asked),
                        new BotTranscript.Turn("bot", "מבנה שחוזר על פעולה", asked.plusMillis(800)))),
                        asked));

        Transactions.runInTx(factory, session -> {
            BotTranscript transcript = session.find(BotSession.class, 1L).getTranscript();

            assertThat(transcript.turns()).hasSize(2);
            assertThat(transcript.turns().get(0).text()).isEqualTo("מה זה לולאה?");
            assertThat(transcript.turns().get(1).at()).isEqualTo(asked.plusMillis(800));
        });
    }

    @Test
    @DisplayName("a notification is unread until it is marked, and only once")
    void notificationReadState() {
        Transactions.runInTx(factory, session -> {
            Notification notification = session.find(Notification.class, 1L);
            assertThat(notification.isUnread()).isTrue();
            assertThat(notification.getTitle()).isEqualTo("הציון פורסם");
            assertThat(notification.getBody()).isEqualTo("המבחן באלגברה נבדק");
            assertThat(notification.getRefType()).isEqualTo("grade");
            assertThat(notification.getRefId()).isEqualTo(1L);
            assertThat(notification.getType()).isEqualTo("GRADE_PUBLISHED");

            notification.markRead(NOW);
            assertThat(notification.isUnread()).isFalse();

            // Reading it again must not move the timestamp.
            notification.markRead(NOW.plusSeconds(3600));
            assertThat(notification.getReadAt()).isEqualTo(NOW);
        });
    }

    @Test
    @DisplayName("the optimistic lock actually bites: a stale write is rejected")
    void staleWriteIsRejected() {
        // The point of every lock_version column (F10.3/F10.4, ADR-008): two coordinators
        // open the same pending exam version, one approves, and the other must be told
        // their copy is out of date rather than silently overwriting the decision.
        //
        // Its own row, deliberately — sharing the seeded one would make this test depend
        // on the order the others ran in, and the first version of it did exactly that:
        // approving an already-approved row changes nothing, so Hibernate never bumped
        // the version and there was no conflict to detect.
        Long versionId = Transactions.inTx(factory, session -> {
            ExamVersion fresh = new ExamVersion(1L, 99, "מבחן לבדיקת נעילה", 45,
                    null, null, ExamVersionStatus.PENDING, NOW);
            session.persist(fresh);
            return fresh.getId();
        });

        ExamVersion stale = Transactions.inTx(factory, session -> session.find(ExamVersion.class, versionId));
        assertThat(stale.getLockVersion()).isZero();

        Transactions.runInTx(factory, session -> session.find(ExamVersion.class, versionId).approve());

        assertThatThrownBy(() -> Transactions.runInTx(factory, session -> {
            stale.reject("מאוחר מדי");
            session.merge(stale);
        }))
                // Hibernate's native API raises StaleStateException; the JPA facade wraps
                // it as OptimisticLockException. Which one surfaces depends on where the
                // flush happens, and both mean the same thing to the caller.
                .isInstanceOfAny(OptimisticLockException.class, StaleStateException.class);

        Transactions.runInTx(factory, session -> {
            ExamVersion winner = session.find(ExamVersion.class, versionId);
            assertThat(winner.getStatus())
                    .as("the first writer's decision must stand")
                    .isEqualTo(ExamVersionStatus.APPROVED);
            assertThat(winner.getLockVersion()).isEqualTo(1);
        });
    }
}
