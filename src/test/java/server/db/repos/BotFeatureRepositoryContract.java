package server.db.repos;

import common.dto.bot.BotSourceKind;
import common.dto.bot.BotTurn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.Bot;
import server.db.entities.BotMessage;
import server.db.entities.BotSource;
import server.db.entities.BotSourceType;
import server.db.entities.Difficulty;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.projections.BotActivityCount;
import server.db.projections.BotBankQuestion;
import server.db.projections.BotSourceInfo;
import server.db.projections.BotSourceText;
import server.features.bot.BotData;
import server.features.bot.JpaBotStore;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queries and the store the study bot runs on (E16, added under TEAM_SPLIT
 * rule 5).
 *
 * <p>Everything here is exercised on both engines by the two subclasses. That
 * matters more than usual for three of these: the activity aggregate uses
 * {@code year()/month()/day()}, the sources projection uses {@code length()}, and
 * the bank read uses a correlated subquery for "latest version" — all three are
 * the kind of HQL that compiles happily and then behaves differently underneath.
 *
 * <p>The dual write of F12.9 is tested here rather than in the service tests for
 * the same reason: "both rows land, or neither" is a claim about a transaction,
 * and an in-memory store cannot make it.
 */
abstract class BotFeatureRepositoryContract extends RepositoryTestBase {

    private static final Instant WHEN = Instant.parse("2026-08-20T09:00:00Z");

    private final BotRepository bots = new BotRepository();
    private final CourseRepository courses = new CourseRepository();
    private final QuestionRepository questions = new QuestionRepository();

    // ===================== Sources =======================================

    @Test
    @DisplayName("the sources projection carries the text length and never the bytes")
    void sourceInfosCarryNoBlob() {
        long botId = persistBot(COURSE_ALGEBRA, "עוזר אלגברה");
        persistSource(botId, "סיכום ראשון", "A foreign key points at a primary key.");

        List<BotSourceInfo> infos = inTx(session -> bots.findSourceInfos(session, botId));

        assertThat(infos).hasSize(1);
        assertThat(infos.get(0).title()).isEqualTo("סיכום ראשון");
        assertThat(infos.get(0).botId()).isEqualTo(botId);
        assertThat(infos.get(0).addedBy()).isEqualTo(danaId);
        assertThat(infos.get(0).characters()).isEqualTo(38);
        assertThat(infos.get(0).version()).isEqualTo(1);
        assertThat(Arrays.stream(BotSourceInfo.class.getRecordComponents())
                .map(RecordComponent::getName))
                .as("no component could hold the blob even if the query selected it")
                .doesNotContain("raw", "extractedText");
    }

    @Test
    @DisplayName("the context read carries the text and never the bytes")
    void sourceTextsCarryTheText() {
        long botId = persistBot(COURSE_ALGEBRA, "עוזר");
        persistSource(botId, "Handout", "Referential integrity.");

        List<BotSourceText> texts = inTx(session -> bots.findSourceTexts(session, botId));

        assertThat(texts).hasSize(1);
        assertThat(texts.get(0).text()).isEqualTo("Referential integrity.");
        assertThat(Arrays.stream(BotSourceText.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("raw");
    }

    @Test
    @DisplayName("a source is found only through the bot that owns it")
    void sourceIsScopedToItsBot() {
        long algebra = persistBot(COURSE_ALGEBRA, "עוזר אלגברה");
        long java = persistBot(COURSE_JAVA, "עוזר Java");
        long sourceId = persistSource(algebra, "Handout", "Some material.");

        Optional<BotSource> own = inTx(session -> bots.findSourceOfBot(session, algebra, sourceId));
        Optional<BotSource> foreign = inTx(session -> bots.findSourceOfBot(session, java, sourceId));

        assertThat(own).isPresent();
        assertThat(foreign)
                .as("another course's bot cannot address this row at all")
                .isEmpty();
    }

    // ===================== Course membership =============================

    @Test
    @DisplayName("teaching a course is a narrower question than being attached to it")
    void teachesIsNarrow() {
        boolean teachesAlgebra = inTx(session -> courses.teaches(session, danaId, COURSE_ALGEBRA));
        boolean teachesDatabases = inTx(session -> courses.teaches(session, danaId, COURSE_DATABASES));
        boolean studentTeaches = inTx(session -> courses.teaches(session, mayaId, COURSE_ALGEBRA));
        boolean nullCourse = inTx(session -> courses.teaches(session, danaId, null));
        boolean blankCourse = inTx(session -> courses.teaches(session, danaId, "  "));

        assertThat(teachesAlgebra).isTrue();
        assertThat(teachesDatabases)
                .as("dana is enrolled in Databases, which is not the same as teaching it")
                .isFalse();
        assertThat(studentTeaches).isFalse();
        assertThat(nullCourse).isFalse();
        assertThat(blankCourse).isFalse();
    }

    @Test
    @DisplayName("the co-teacher list excludes whoever made the change")
    void otherTeachersExcludeTheEditor() {
        List<Long> others = inTx(session ->
                courses.findOtherTeachers(session, COURSE_CALCULUS, danaId));

        List<Long> solo = inTx(session ->
                courses.findOtherTeachers(session, COURSE_ALGEBRA, danaId));
        List<Long> noCourse = inTx(session -> courses.findOtherTeachers(session, null, danaId));

        assertThat(others).containsExactly(rinaId);
        assertThat(solo).as("a solo-taught course has nobody else to tell").isEmpty();
        assertThat(noCourse).isEmpty();
    }

    @Test
    @DisplayName("a course's name is one column, and an unknown code is empty")
    void findsCourseName() {
        Optional<String> algebra = inTx(session -> courses.findName(session, COURSE_ALGEBRA));
        Optional<String> unknown = inTx(session -> courses.findName(session, "99"));
        Optional<String> nullCode = inTx(session -> courses.findName(session, null));

        assertThat(algebra).contains("אלגברה");
        assertThat(unknown).isEmpty();
        assertThat(nullCode).isEmpty();
    }

    // ===================== The bank read (F12.8 ⚑) =======================

    @Test
    @DisplayName("the bot's bank read returns the latest version with four answers and no key ⚑")
    void bankReadCarriesNoCorrectness() {
        long questionId = persistQuestion(COURSE_ALGEBRA, (short) 5);
        persistVersion(questionId, 1, "ניסוח ישן");
        persistVersion(questionId, 2, "מהו מפתח זר?");

        List<BotBankQuestion> bank = inTx(session ->
                questions.findBankForBot(session, COURSE_ALGEBRA, 50));

        assertThat(bank).hasSize(1);
        assertThat(bank.get(0).text())
                .as("the latest version, not the first one inserted")
                .isEqualTo("מהו מפתח זר?");
        assertThat(bank.get(0).displayId()).isEqualTo("11005");
        assertThat(bank.get(0).answer1()).isNotBlank();
        assertThat(bank.get(0).answer4()).isNotBlank();
        assertThat(Arrays.stream(BotBankQuestion.class.getRecordComponents())
                .map(RecordComponent::getName))
                .as("F12.8, lead's ruling: text and four answers, no marking of which is right")
                .containsExactly("displayId", "text", "answer1", "answer2", "answer3", "answer4");
        assertThat(bank.get(0).asStudyMaterial().toLowerCase(Locale.ROOT))
                .doesNotContain("correct");
    }

    @Test
    @DisplayName("a soft-deleted question is not taught by the bot")
    void softDeletedQuestionsAreExcluded() {
        long questionId = persistQuestion(COURSE_ALGEBRA, (short) 6);
        persistVersion(questionId, 1, "שאלה שנמחקה");
        runInTx(session -> session.get(Question.class, questionId).setDeletedAt(WHEN));

        List<BotBankQuestion> bank = inTx(session ->
                questions.findBankForBot(session, COURSE_ALGEBRA, 50));

        assertThat(bank).isEmpty();
    }

    @Test
    @DisplayName("the bank read is bounded and course-scoped")
    void bankReadIsBoundedAndScoped() {
        long algebra = persistQuestion(COURSE_ALGEBRA, (short) 7);
        persistVersion(algebra, 1, "שאלת אלגברה");
        long java = persistQuestion(COURSE_JAVA, (short) 1);
        persistVersion(java, 1, "שאלת Java");

        List<BotBankQuestion> javaBank =
                inTx(session -> questions.findBankForBot(session, COURSE_JAVA, 50));
        List<BotBankQuestion> clamped =
                inTx(session -> questions.findBankForBot(session, COURSE_ALGEBRA, 0));
        List<BotBankQuestion> noCourse =
                inTx(session -> questions.findBankForBot(session, null, 10));

        assertThat(javaBank).extracting(BotBankQuestion::text).containsExactly("שאלת Java");
        assertThat(clamped)
                .as("a limit below one is clamped, never turned into an unbounded scan")
                .hasSize(1);
        assertThat(noCourse).isEmpty();
    }

    // ===================== Conversations and the dual write ==============

    @Test
    @DisplayName("one exchange writes the transcript and the analytics row together (F12.9)")
    void dualWriteIsOneTransaction() {
        long botId = persistBot(COURSE_ALGEBRA, "עוזר");
        JpaBotStore store = new JpaBotStore(factory());

        long sessionId = store.inTx(data -> data.appendExchange(null, botId, mayaId,
                "מהו מפתח זר?", "מפתח זר מצביע על מפתח ראשי.", "deepseek", WHEN));

        Optional<BotData.StoredSession> stored =
                store.inTx(data -> data.ownSession(sessionId, mayaId));
        assertThat(stored).isPresent();
        assertThat(stored.orElseThrow().turns()).hasSize(2);
        assertThat(stored.orElseThrow().turns().get(0).text()).isEqualTo("מהו מפתח זר?");
        assertThat(stored.orElseThrow().turns().get(0).isFromStudent()).isTrue();
        assertThat(stored.orElseThrow().courseCode()).isEqualTo(COURSE_ALGEBRA);

        long messages = inTx(session -> bots.countMessages(session, botId));
        assertThat(messages)
                .as("the normalised copy landed in the same transaction")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a follow-up appends to the same conversation rather than starting one")
    void followUpAppends() {
        long botId = persistBot(COURSE_ALGEBRA, "עוזר");
        JpaBotStore store = new JpaBotStore(factory());

        long sessionId = store.inTx(data ->
                data.appendExchange(null, botId, mayaId, "first", "answer one", "deepseek", WHEN));
        long same = store.inTx(data -> data.appendExchange(sessionId, botId, mayaId,
                "second", "answer two", "anthropic", WHEN.plusSeconds(60)));

        assertThat(same).isEqualTo(sessionId);
        List<BotTurn> turns = store.inTx(data ->
                data.ownSession(sessionId, mayaId).orElseThrow().turns());
        long rows = inTx(session -> bots.countMessages(session, botId));

        assertThat(turns).hasSize(4);
        assertThat(rows).isEqualTo(2);
    }

    @Test
    @DisplayName("a conversation is only ever found by the student who had it")
    void sessionsAreScopedToTheStudent() {
        long botId = persistBot(COURSE_ALGEBRA, "עוזר");
        JpaBotStore store = new JpaBotStore(factory());
        long sessionId = store.inTx(data ->
                data.appendExchange(null, botId, mayaId, "mine", "answer", "deepseek", WHEN));

        Optional<BotData.StoredSession> hers = store.inTx(data -> data.ownSession(sessionId, mayaId));
        Optional<BotData.StoredSession> theirs = store.inTx(data -> data.ownSession(sessionId, danaId));
        List<BotData.StoredSession> theirList = store.inTx(data -> data.ownSessions(botId, danaId));
        List<BotData.StoredSession> herList = store.inTx(data -> data.ownSessions(botId, mayaId));

        assertThat(hers).isPresent();
        assertThat(theirs)
                .as("scoped in the query, so another student's id can only ever be empty")
                .isEmpty();
        assertThat(theirList).isEmpty();
        assertThat(herList).hasSize(1);
    }

    @Test
    @DisplayName("her conversations come back most recently used first")
    void sessionsAreNewestFirst() {
        long botId = persistBot(COURSE_ALGEBRA, "עוזר");
        JpaBotStore store = new JpaBotStore(factory());
        long older = store.inTx(data ->
                data.appendExchange(null, botId, mayaId, "older", "a", "deepseek", WHEN));
        long newer = store.inTx(data -> data.appendExchange(null, botId, mayaId,
                "newer", "a", "deepseek", WHEN.plusSeconds(3600)));

        List<BotData.StoredSession> sessions = store.inTx(data -> data.ownSessions(botId, mayaId));

        assertThat(sessions).extracting(BotData.StoredSession::sessionId)
                .containsExactly(newer, older);
    }

    // ===================== Analytics (S-34 ⚑) ============================

    @Test
    @DisplayName("activity is bucketed by day, and reads no identifying column ⚑")
    void activityIsBucketedByDay() {
        long botId = persistBot(COURSE_ALGEBRA, "עוזר");
        persistMessage(botId, "q1", WHEN);
        persistMessage(botId, "q2", WHEN.plusSeconds(3600));
        persistMessage(botId, "q3", WHEN.plusSeconds(86_400));

        List<BotActivityCount> activity =
                inTx(session -> bots.findActivity(session, botId, WHEN.minusSeconds(86_400)));

        assertThat(activity).hasSize(2);
        assertThat(activity.get(0).count()).isEqualTo(2);
        assertThat(activity.get(0).day()).isEqualTo(java.time.LocalDate.of(2026, 8, 20));
        assertThat(activity.get(1).count()).isEqualTo(1);
        assertThat(Arrays.stream(BotActivityCount.class.getRecordComponents())
                .map(RecordComponent::getName))
                .as("S-34: nowhere to put a student, and the query never selects one")
                .containsExactly("year", "month", "dayOfMonth", "questions");
    }

    @Test
    @DisplayName("activity before the window is not counted")
    void activityRespectsTheWindow() {
        long botId = persistBot(COURSE_ALGEBRA, "עוזר");
        persistMessage(botId, "old", WHEN.minusSeconds(90 * 86_400L));
        persistMessage(botId, "recent", WHEN);

        List<BotActivityCount> activity =
                inTx(session -> bots.findActivity(session, botId, WHEN.minusSeconds(86_400)));

        assertThat(activity).hasSize(1);
    }

    @Test
    @DisplayName("the recent-questions read returns texts only, newest first, bounded")
    void recentQuestions() {
        long botId = persistBot(COURSE_ALGEBRA, "עוזר");
        persistMessage(botId, "first", WHEN);
        persistMessage(botId, "second", WHEN.plusSeconds(60));
        persistMessage(botId, "third", WHEN.plusSeconds(120));

        List<String> recent = inTx(session -> bots.findRecentQuestions(session, botId, 2));

        List<String> clamped = inTx(session -> bots.findRecentQuestions(session, botId, 0));

        assertThat(recent).containsExactly("third", "second");
        assertThat(clamped)
                .as("a limit below one is clamped rather than turned into a full scan")
                .hasSize(1);
    }

    @Test
    @DisplayName("counts and activity are per bot, never across the school")
    void analyticsAreScopedToTheBot() {
        long algebra = persistBot(COURSE_ALGEBRA, "עוזר אלגברה");
        long java = persistBot(COURSE_JAVA, "עוזר Java");
        persistMessage(algebra, "algebra question", WHEN);

        long javaMessages = inTx(session -> bots.countMessages(session, java));
        List<BotActivityCount> javaActivity =
                inTx(session -> bots.findActivity(session, java, WHEN.minusSeconds(86_400)));
        List<String> javaQuestions = inTx(session -> bots.findRecentQuestions(session, java, 10));

        assertThat(javaMessages).isZero();
        assertThat(javaActivity).isEmpty();
        assertThat(javaQuestions).isEmpty();
    }

    // ===================== The store's writes ============================

    @Test
    @DisplayName("creating a bot twice for one course joins the first rather than failing (S-30)")
    void createBotIsIdempotent() {
        JpaBotStore store = new JpaBotStore(factory());

        BotData.BotRecord first = store.inTx(data -> data.createBot(COURSE_ALGEBRA, "Dana's bot"));
        BotData.BotRecord second = store.inTx(data -> data.createBot(COURSE_ALGEBRA, "Rina's bot"));

        assertThat(second.botId()).isEqualTo(first.botId());
        assertThat(second.name()).isEqualTo("Dana's bot");
        assertThat(second.courseName()).isEqualTo("אלגברה");
    }

    @Test
    @DisplayName("switching a bot off is persisted")
    void setActiveIsPersisted() {
        JpaBotStore store = new JpaBotStore(factory());
        long botId = store.inTx(data -> data.createBot(COURSE_ALGEBRA, "bot").botId());

        store.runInTx(data -> data.setActive(botId, false));

        boolean active = store.inTx(data -> data.botForCourse(COURSE_ALGEBRA).orElseThrow().active());
        assertThat(active).isFalse();
        // A bot id that does not exist is a no-op rather than an exception: the
        // service has already checked, and a second failure mode helps nobody.
        store.runInTx(data -> data.setActive(999999L, false));
    }

    @Test
    @DisplayName("a text source stores the pasted text as its bytes too")
    void textSourceStoresBothColumns() {
        JpaBotStore store = new JpaBotStore(factory());
        long botId = store.inTx(data -> data.createBot(COURSE_ALGEBRA, "bot").botId());

        long sourceId = store.inTx(data -> data.addSource(botId, BotSourceKind.TEXT,
                "Pasted", new byte[0], "Some course material.", danaId, WHEN));

        BotSource source = inTx(session ->
                bots.findSourceOfBot(session, botId, sourceId).orElseThrow());
        assertThat(source.getExtractedText()).isEqualTo("Some course material.");
        assertThat(new String(source.getRaw(), StandardCharsets.UTF_8))
                .isEqualTo("Some course material.");
        assertThat(source.getType()).isEqualTo(BotSourceType.TEXT);
    }

    @Test
    @DisplayName("removing a source needs the right bot, and says whether it removed anything")
    void removeSourceIsScoped() {
        JpaBotStore store = new JpaBotStore(factory());
        long algebra = store.inTx(data -> data.createBot(COURSE_ALGEBRA, "bot").botId());
        long java = store.inTx(data -> data.createBot(COURSE_JAVA, "bot").botId());
        long sourceId = store.inTx(data -> data.addSource(algebra, BotSourceKind.TEXT,
                "Pasted", new byte[0], "Material.", danaId, WHEN));

        boolean removedFromWrongBot = store.inTx(data -> data.removeSource(java, sourceId));
        boolean removedFromRightBot = store.inTx(data -> data.removeSource(algebra, sourceId));

        List<BotSourceInfo> remaining = store.inTx(data -> data.sourceInfos(algebra));

        assertThat(removedFromWrongBot).isFalse();
        assertThat(removedFromRightBot).isTrue();
        assertThat(remaining).isEmpty();
    }

    @Test
    @DisplayName("display names come back per id, and unknown ids are simply absent")
    void displayNames() {
        JpaBotStore store = new JpaBotStore(factory());

        var names = store.inTx(data -> data.displayNames(List.of(danaId, 999999L, danaId)));

        java.util.Map<Long, String> none = store.inTx(data -> data.displayNames(List.of()));
        java.util.Map<Long, String> nullIds = store.inTx(data -> data.displayNames(null));

        assertThat(names).containsOnlyKeys(danaId);
        assertThat(names.get(danaId)).isEqualTo("דנה כהן");
        assertThat(none).isEmpty();
        assertThat(nullIds).isEmpty();
    }

    @Test
    @DisplayName("the store's enrolment, teaching and course reads agree with the repositories")
    void storeDelegatesTheMembershipReads() {
        JpaBotStore store = new JpaBotStore(factory());

        boolean enrolledInAlgebra = store.inTx(data -> data.isEnrolled(mayaId, COURSE_ALGEBRA));
        boolean enrolledInCalculus = store.inTx(data -> data.isEnrolled(mayaId, COURSE_CALCULUS));
        boolean teachesAlgebra = store.inTx(data -> data.teaches(danaId, COURSE_ALGEBRA));

        assertThat(enrolledInAlgebra).isTrue();
        assertThat(enrolledInCalculus).isFalse();
        assertThat(teachesAlgebra).isTrue();
        Optional<String> name = store.inTx(data -> data.courseName(COURSE_ALGEBRA));
        List<Long> others = store.inTx(data -> data.otherTeachersOf(COURSE_CALCULUS, danaId));
        List<BotBankQuestion> bank = store.inTx(data -> data.bankQuestions(COURSE_ALGEBRA, 10));

        assertThat(name).contains("אלגברה");
        assertThat(others).containsExactly(rinaId);
        assertThat(bank).isEmpty();
    }

    // ===================== Fixtures ======================================

    private long persistBot(String courseCode, String name) {
        return inTx(session -> {
            Bot bot = new Bot(courseCode, name);
            session.persist(bot);
            session.flush();
            return bot.getId();
        });
    }

    private long persistSource(long botId, String title, String text) {
        return inTx(session -> {
            BotSource source = new BotSource(botId, BotSourceType.TEXT, title,
                    text.getBytes(StandardCharsets.UTF_8), text, danaId, WHEN);
            session.persist(source);
            session.flush();
            return source.getId();
        });
    }

    private void persistMessage(long botId, String question, Instant at) {
        runInTx(session -> {
            server.db.entities.BotSession conversation =
                    new server.db.entities.BotSession(botId, mayaId, at);
            session.persist(conversation);
            session.flush();
            session.persist(new BotMessage(botId, conversation.getId(), mayaId,
                    question, "an answer", "deepseek", at));
        });
    }

    private long persistQuestion(String courseCode, short serial) {
        return inTx(session -> {
            Question question = new Question(courseCode, serial,
                    courseCode + String.format("%03d", serial));
            session.persist(question);
            session.flush();
            return question.getId();
        });
    }

    private void persistVersion(long questionId, int versionNo, String text) {
        runInTx(session -> session.persist(new QuestionVersion(questionId, versionNo, text,
                "מצביע על מפתח ראשי", "מאיץ קריאות", "מקטין שורות", "שם ייחודי",
                (byte) 1, "בסיסי נתונים", Difficulty.EASY, null, danaId, WHEN)));
    }
}
