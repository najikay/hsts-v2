package server.features.bot;

import common.dto.bot.BotSourceKind;
import common.dto.bot.BotTurn;
import server.db.projections.BotActivityCount;
import server.db.projections.BotBankQuestion;
import server.db.projections.BotSourceInfo;
import server.db.projections.BotSourceText;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the study bot does to the database, <b>inside one transaction</b>
 * (Logic tier, E16.8/E16.9/E16.10).
 *
 * <p>Handed to a unit of work by {@link BotStore#inTx}; valid only for that call
 * and never stored. The shape is E10's {@code ExamData}, for the same two reasons:
 * a rule reads the current truth and acts on it without a gap, and every rule
 * becomes testable twice — once against a fast in-memory implementation and once
 * against {@link JpaBotStore} on both engines.
 *
 * <h2>The F12.8 boundary, expressed as an interface ⚑</h2>
 *
 * <p><b>Read the method list as a security statement.</b> There is no method here
 * that returns an exam, an exam version, an execution, an execution code, an
 * attempt, an answer or a grade — and there is no method that could be given one
 * without editing this file. The bot's whole reach into the database is: bot rows,
 * bot sources, bot sessions, bot messages, course membership, user display names,
 * and a bank read that carries the four options with no correctness data (S-28,
 * and the lead's ruling recorded on {@link BotBankQuestion}).
 *
 * <p>That is what makes "the bot cannot leak exam contents" a compile-time fact
 * rather than a prompt-time hope. {@code BotIsolationGuardTest} enforces the same
 * statement from the other side, by scanning the compiled feature package for any
 * reference to the exam and grading repositories.
 *
 * <h2>One method that is not a read or a write</h2>
 *
 * <p>{@link #appendExchange} is both, deliberately and atomically. F12.9 requires
 * the JSON transcript and the normalised {@code bot_messages} row to be written
 * together; splitting them into two calls would hand a caller the opportunity to
 * write one without the other, and a student's history disagreeing with her
 * teacher's analytics is the kind of divergence nobody notices until it is a
 * month old.
 */
public interface BotData {

    /**
     * A course's bot, flattened.
     *
     * @param botId      the row id
     * @param courseCode the course it belongs to
     * @param courseName that course's display name
     * @param name       the bot's name
     * @param active     whether students may use it (F12.4)
     */
    record BotRecord(long botId, String courseCode, String courseName, String name, boolean active) {

        public BotRecord {
            Objects.requireNonNull(courseCode, "courseCode");
            courseName = courseName == null || courseName.isBlank() ? courseCode : courseName;
            name = name == null || name.isBlank() ? courseName + " study bot" : name;
        }
    }

    /**
     * One stored conversation, with its transcript already decoded.
     *
     * @param sessionId  the row id
     * @param botId      the bot it belongs to
     * @param courseCode that bot's course
     * @param startedAt  when it began, UTC
     * @param updatedAt  its last exchange, UTC
     * @param turns      the transcript, oldest first
     */
    record StoredSession(long sessionId, long botId, String courseCode,
                         Instant startedAt, Instant updatedAt, List<BotTurn> turns) {

        public StoredSession {
            Objects.requireNonNull(startedAt, "startedAt");
            courseCode = courseCode == null ? "" : courseCode;
            updatedAt = updatedAt == null ? startedAt : updatedAt;
            turns = turns == null ? List.of() : List.copyOf(turns);
        }
    }

    // ===================== Courses and people ============================

    /**
     * @param studentId  the student
     * @param courseCode the course
     * @return {@code true} when she is enrolled (S-31)
     */
    boolean isEnrolled(long studentId, String courseCode);

    /**
     * @param teacherId  the caller
     * @param courseCode the course
     * @return {@code true} when he teaches it — the ownership half of P-5
     */
    boolean teaches(long teacherId, String courseCode);

    /**
     * @param courseCode the course
     * @return its display name, or empty when there is no such course
     */
    Optional<String> courseName(String courseCode);

    /**
     * @param courseCode the course
     * @param excluding  the teacher who made the change
     * @return the other teachers of that course, for the F12.3 notification
     */
    List<Long> otherTeachersOf(String courseCode, long excluding);

    /**
     * @param userIds the users to name
     * @return their display names by id; ids with no user are simply absent, so a
     *         source added by a since-removed teacher still renders as a row
     */
    Map<Long, String> displayNames(Collection<Long> userIds);

    // ===================== The bot and its material ======================

    /**
     * @param courseCode the course
     * @return its bot, or empty when it has none yet
     */
    Optional<BotRecord> botForCourse(String courseCode);

    /**
     * Creates the bot for a course, or returns the one that already exists (S-30).
     *
     * @param courseCode the course
     * @param name       what to call it
     * @return the course's bot, new or existing
     */
    BotRecord createBot(String courseCode, String name);

    /**
     * @param botId  the bot
     * @param active the state to put it in (F12.4)
     */
    void setActive(long botId, boolean active);

    /**
     * @param botId the bot
     * @return its sources for the manager's table, oldest first; no bytes
     */
    List<BotSourceInfo> sourceInfos(long botId);

    /**
     * @param botId the bot
     * @return its material for the prompt, oldest first; no bytes
     */
    List<BotSourceText> sourceTexts(long botId);

    /**
     * Stores one successfully extracted source (F12.2).
     *
     * <p>Called only after {@link SourceExtractor} has produced text, which is what
     * keeps {@code bot_sources}' two NOT NULL columns honest: a row exists because a
     * parse succeeded, never before.
     *
     * @param botId     the bot
     * @param kind      what the upload was
     * @param title     what the teacher called it
     * @param raw       the original bytes
     * @param text      the extracted text
     * @param addedBy   the teacher
     * @param at        now, UTC
     * @return the new source's id
     */
    long addSource(long botId, BotSourceKind kind, String title,
                   byte[] raw, String text, long addedBy, Instant at);

    /**
     * Removes one source, checked against its bot.
     *
     * @param botId    the bot the caller is authorised for
     * @param sourceId the source
     * @return {@code true} when a row was removed; {@code false} when the source
     *         does not exist or belongs to another course's bot
     */
    boolean removeSource(long botId, long sourceId);

    /**
     * @param courseCode the course
     * @param limit      the most questions to read
     * @return its bank questions with their four options and <b>no</b> correctness
     *         data (S-28, F12.8 ⚑)
     */
    List<BotBankQuestion> bankQuestions(String courseCode, int limit);

    // ===================== Conversations =================================

    /**
     * @param sessionId the conversation
     * @param studentId the caller; part of the query, so another student's id is empty
     * @return her conversation, or empty
     */
    Optional<StoredSession> ownSession(long sessionId, long studentId);

    /**
     * @param botId     the bot
     * @param studentId the caller
     * @return her conversations with it, most recently used first
     */
    List<StoredSession> ownSessions(long botId, long studentId);

    /**
     * Persists one question and its answer, atomically, both ways (F12.9).
     *
     * <p>Appends the pair to the session's JSON transcript <b>and</b> inserts the
     * normalised {@code bot_messages} row, in the caller's transaction. Creates the
     * session first when {@code sessionId} is {@code null}.
     *
     * @param sessionId the conversation to append to, or {@code null} to start one
     * @param botId     the bot
     * @param studentId the student
     * @param question  what she asked
     * @param answer    what the bot said
     * @param provider  which adapter answered, for ADR-009's after-the-fact numbers
     * @param at        now, UTC
     * @return the conversation's id, which is the new one when this started it
     */
    long appendExchange(Long sessionId, long botId, long studentId,
                        String question, String answer, String provider, Instant at);

    // ===================== Analytics (S-34 ⚑) ============================

    /**
     * @param botId the bot
     * @return how many questions it has been asked
     */
    long countMessages(long botId);

    /**
     * @param botId the bot
     * @param since the earliest instant to count from
     * @return questions per day, oldest first; no identifying column is read
     */
    List<BotActivityCount> activity(long botId, Instant since);

    /**
     * @param botId the bot
     * @param limit the most rows to read
     * @return the recent question texts, newest first, for the in-Java grouping
     */
    List<String> recentQuestions(long botId, int limit);
}
