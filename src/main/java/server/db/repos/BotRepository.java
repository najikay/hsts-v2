package server.db.repos;

import org.hibernate.Session;
import server.db.entities.Bot;
import server.db.entities.BotSession;
import server.db.entities.BotSource;
import server.db.entities.BotSourceType;
import server.db.projections.BotActivityCount;
import server.db.projections.BotSourceInfo;
import server.db.projections.BotSourceText;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads over the bot tables (E2.11, extended in E16 under TEAM_SPLIT rule 5).
 *
 * <h2>Three shapes of source read, and why there are three</h2>
 *
 * <p>{@code bot_sources} carries a {@code MEDIUMBLOB} and a {@code MEDIUMTEXT} on
 * every row, and {@code @Basic(fetch = LAZY)} does nothing without bytecode
 * enhancement, which this build does not run. So the read has to choose its
 * columns rather than its fetch plan:
 *
 * <ul>
 *   <li>{@link #findSourceInfos} — no blob, no text, one length; the manager's
 *       table;</li>
 *   <li>{@link #findSourceTexts} — the text but not the bytes; the prompt context
 *       builder;</li>
 *   <li>{@link #findSources} — whole entities, bytes included; only for a caller
 *       that genuinely needs the original file.</li>
 * </ul>
 *
 * <h2>S-34 and the analytics reads ⚑</h2>
 *
 * <p>{@link #countMessages}, {@link #findActivity} and {@link #findRecentQuestions}
 * feed the teacher's anonymised aggregate, and none of them selects
 * {@code bot_messages.student_id} — not in the projection, not in a
 * {@code group by}, not in a distinct count. The column exists (a student's own
 * history is reassembled from it, and a C-4 alert names the right person to the
 * right teacher), it simply never travels on this path. Anonymity is a property of
 * the query, in the same way that {@code correct_answer} not being in the
 * take-exam SELECT is a property of that one (E2.12).
 */
public final class BotRepository {

    /**
     * The bot for a course.
     *
     * <p>{@code bots.course} is unique — one bot per course (S-30) — so this is at most one
     * row.
     *
     * <p>Consumers: E16 bot chat and bot manager; the E2.15 seed loader.
     *
     * @param session    the current session
     * @param courseCode the 2-character course code
     * @return the bot, or empty when the course has none
     */
    public Optional<Bot> findByCourse(Session session, String courseCode) {
        return session.createQuery("from Bot where courseCode = :courseCode", Bot.class)
                .setParameter("courseCode", courseCode)
                .uniqueResultOptional();
    }

    /**
     * A bot's knowledge sources, without their raw bytes.
     *
     * <p>Deliberately returns whole entities and is therefore <b>not</b> for listing screens:
     * {@code raw} is a {@code MEDIUMBLOB} and lazy loading of basic attributes does not work
     * without bytecode enhancement, which this build does not run. F12.3's source list must
     * use a scalar projection that never names the blob; this method is for the context
     * builder, which needs the extracted text anyway.
     *
     * <p>Consumer: E14's prompt context builder.
     *
     * @param session the current session
     * @param botId   the bot
     * @return its sources, oldest first
     */
    public List<BotSource> findSources(Session session, long botId) {
        return session.createQuery(
                        "from BotSource where botId = :botId order by id", BotSource.class)
                .setParameter("botId", botId)
                .getResultList();
    }

    /**
     * The sources table of the Bot Manager (E16.9, F12.3).
     *
     * <p>A constructor expression that names neither {@code raw} nor
     * {@code extracted_text}: the megabytes stay in the database and the row carries
     * the one number the screen wants from the text, computed by the engine. A ten
     * source bot therefore costs a small result set rather than the whole library.
     *
     * <p>Consumer: E16's bot management service.
     *
     * @param session the current session
     * @param botId   the bot
     * @return its sources, oldest first
     */
    public List<BotSourceInfo> findSourceInfos(Session session, long botId) {
        return session.createQuery("""
                        select new server.db.projections.BotSourceInfo(
                            s.id, s.botId, s.type, s.title, s.addedBy, s.updatedAt,
                            s.version, length(s.extractedText))
                        from BotSource s
                        where s.botId = :botId
                        order by s.id
                        """, BotSourceInfo.class)
                .setParameter("botId", botId)
                .getResultList();
    }

    /**
     * The material the prompt context builder scores (E16.6).
     *
     * <p>Text and title, never bytes. This is the read that decides what a model is
     * allowed to see, so it is worth being explicit about what it cannot reach: it
     * selects from {@code bot_sources} and joins to nothing, so no exam, execution
     * or grade column is reachable from it even by a future edit that added one to
     * the projection.
     *
     * <p>Consumer: E16's {@code ContextBuilder}, through {@code JpaBotStore}.
     *
     * @param session the current session
     * @param botId   the bot
     * @return its material, oldest first, so context selection is deterministic
     */
    public List<BotSourceText> findSourceTexts(Session session, long botId) {
        return session.createQuery("""
                        select new server.db.projections.BotSourceText(s.id, s.title, s.extractedText)
                        from BotSource s
                        where s.botId = :botId
                        order by s.id
                        """, BotSourceText.class)
                .setParameter("botId", botId)
                .getResultList();
    }

    /**
     * One source, checked against the bot that is supposed to own it (E16.9).
     *
     * <p>The {@code botId} is part of the query rather than something the caller
     * compares afterwards. A source id from another course's bot therefore returns
     * empty — which the service turns into {@code NOT_FOUND} — instead of returning
     * a row that a caller might forget to check.
     *
     * @param session  the current session
     * @param botId    the bot the caller is authorised for
     * @param sourceId the source
     * @return the source, or empty when it does not exist or belongs elsewhere
     */
    public Optional<BotSource> findSourceOfBot(Session session, long botId, long sourceId) {
        return session.createQuery("""
                        from BotSource where id = :sourceId and botId = :botId
                        """, BotSource.class)
                .setParameter("sourceId", sourceId)
                .setParameter("botId", botId)
                .uniqueResultOptional();
    }

    /**
     * The pasted bodies of one bot's free-text sources (B-21, F12.3 ⚑).
     *
     * <p>What lets the manager's Edit dialog open on what is actually stored rather than on an
     * empty box. A scalar projection and not the entity, for the reason {@code BotSource}'s own
     * javadoc gives: {@code raw} is a {@code MEDIUMBLOB} that loads with its row, so reading
     * entities here would drag every uploaded PDF across to draw a dialog.
     *
     * <p><b>Filtered in the query, to {@code TEXT} only.</b> Not a convenience: a PDF's
     * extracted text is a parse artefact of a document the teacher cannot edit here, and it is
     * measured in hundreds of kilobytes. Selecting it and discarding it afterwards would put
     * exactly that on the wire's critical path before anybody noticed.
     *
     * @param session the current session
     * @param botId   the bot
     * @return source id → its pasted text, for the TEXT sources of this bot
     */
    public Map<Long, String> findTextSourceBodies(Session session, long botId) {
        Map<Long, String> bodies = new LinkedHashMap<>();
        for (Object[] row : session.createQuery("""
                        select s.id, s.extractedText from BotSource s
                        where s.botId = :botId and s.type = :type
                        order by s.id
                        """, Object[].class)
                .setParameter("botId", botId)
                .setParameter("type", BotSourceType.TEXT)
                .getResultList()) {
            bodies.put((Long) row[0], (String) row[1]);
        }
        return bodies;
    }

    /**
     * Deletes every source of one bot ⚑ (E16.9, U-39).
     *
     * <p>A bulk delete rather than a load-and-remove loop, and the reason is the same one that
     * gives this class three shapes of source read: {@code raw} is a {@code MEDIUMBLOB} that
     * loads with its row, so removing entities would drag every uploaded PDF into memory to
     * throw it away again. Nothing here needs to see a byte of what it deletes.
     *
     * <p>V6 would also do this by cascade, and that is deliberately not what is relied on:
     * {@code bot_sources} and {@code bots} are not mapped as an association, so the cascade is
     * the engine's and not Hibernate's. Issuing the delete makes it one behaviour on both
     * engines and one that {@code BotFeatureRepositoryContract} can watch.
     *
     * <p>Consumer: {@code BOT_DELETE}, through {@code JpaBotStore.deleteBot}.
     *
     * @param session the current session
     * @param botId   the bot whose material is going
     * @return how many source rows were deleted
     */
    public int deleteSourcesOf(Session session, long botId) {
        return session.createMutationQuery("delete from BotSource where botId = :botId")
                .setParameter("botId", botId)
                .executeUpdate();
    }

    /**
     * How many conversations students have had with one bot (E16.10, S-33 ⚑, U-39).
     *
     * <p>A count over {@code bot_sessions} and not over {@code bot_messages}: the question
     * {@code BOT_DELETE} asks is how many students' <em>records</em> a delete would take, and a
     * record is a conversation. Counting messages would answer a different question and put a
     * number in the refusal that no screen anywhere shows.
     *
     * @param session the current session
     * @param botId   the bot
     * @return the number of stored conversations; zero for a bot nobody has used
     */
    public long countSessions(Session session, long botId) {
        return session.createQuery(
                        "select count(s) from BotSession s where s.botId = :botId", Long.class)
                .setParameter("botId", botId)
                .getSingleResult();
    }

    /**
     * One student's conversations with one bot, newest first (E16.10, F12.10).
     *
     * <p><b>Scoped in the query, not by the caller.</b> {@code student_id} is a
     * parameter of the read, so there is no result set here that a filtering
     * mistake could leak — the same silent scoping the notifications feature uses,
     * and the reason a classmate's session id can only ever answer "not found".
     *
     * <p>Returns entities, transcripts and all. That is a considered choice: a
     * student has a handful of conversations per course, the alternative is three
     * queries to rebuild counts and previews that the transcript already contains,
     * and the JSON column is the authoritative copy of her history (S-33).
     *
     * @param session   the current session
     * @param botId     the bot
     * @param studentId the caller
     * @return her conversations, most recently used first
     */
    public List<BotSession> findSessionsOf(Session session, long botId, long studentId) {
        return session.createQuery("""
                        from BotSession
                        where botId = :botId and studentId = :studentId
                        order by updatedAt desc, id desc
                        """, BotSession.class)
                .setParameter("botId", botId)
                .setParameter("studentId", studentId)
                .getResultList();
    }

    /**
     * One of the caller's own conversations (E16.10, F12.10).
     *
     * @param session   the current session
     * @param sessionId the conversation
     * @param studentId the caller; part of the query, so somebody else's id is empty
     * @return the conversation, or empty when it is not hers or does not exist
     */
    public Optional<BotSession> findOwnSession(Session session, long sessionId, long studentId) {
        return session.createQuery("""
                        from BotSession where id = :sessionId and studentId = :studentId
                        """, BotSession.class)
                .setParameter("sessionId", sessionId)
                .setParameter("studentId", studentId)
                .uniqueResultOptional();
    }

    /**
     * How many questions a bot has been asked (E16.10, S-34 ⚑).
     *
     * @param session the current session
     * @param botId   the bot
     * @return the total; zero for a bot nobody has used
     */
    public long countMessages(Session session, long botId) {
        return session.createQuery(
                        "select count(m) from BotMessage m where m.botId = :botId", Long.class)
                .setParameter("botId", botId)
                .getSingleResult();
    }

    /**
     * Questions per day, for the teacher's activity chart (E16.10, S-34 ⚑).
     *
     * <p>Bucketed with {@code year()/month()/day()} because those three are the
     * portable way to group a timestamp across both engines this project tests on;
     * a {@code DATE_FORMAT} or a {@code date_trunc} would pin the query to one of
     * them and quietly fail on the other engine's contract run.
     *
     * <p>No identifying column is selected or grouped on, which is what makes the
     * aggregate anonymous at the level of the SQL rather than at the level of the
     * mapper.
     *
     * @param session the current session
     * @param botId   the bot
     * @param since   the earliest instant to count from
     * @return one row per day that had activity, oldest first
     */
    public List<BotActivityCount> findActivity(Session session, long botId, Instant since) {
        return session.createQuery("""
                        select new server.db.projections.BotActivityCount(
                            year(m.askedAt), month(m.askedAt), day(m.askedAt), count(m))
                        from BotMessage m
                        where m.botId = :botId and m.askedAt >= :since
                        group by year(m.askedAt), month(m.askedAt), day(m.askedAt)
                        order by year(m.askedAt), month(m.askedAt), day(m.askedAt)
                        """, BotActivityCount.class)
                .setParameter("botId", botId)
                .setParameter("since", since)
                .getResultList();
    }

    /**
     * The recent question texts a bot was asked (E16.10, S-34 ⚑).
     *
     * <p>Only the {@code question} column, and deliberately nothing else. The
     * grouping into "frequent questions" happens in Java because the normalisation
     * that makes two spellings of the same question one row is ours
     * ({@code TextNormaliser.groupingKey}) and neither engine can express it — so
     * the alternative would be a per-engine SQL fold that drifts from what the
     * screen claims to be showing.
     *
     * <p>Bounded by {@code limit} so a busy course cannot turn one screen into a
     * full-table scan; newest first, because a teacher looking at this screen is
     * asking what students are struggling with <em>now</em>.
     *
     * @param session the current session
     * @param botId   the bot
     * @param limit   the most rows to read
     * @return the question texts, newest first
     */
    public List<String> findRecentQuestions(Session session, long botId, int limit) {
        return session.createQuery("""
                        select m.question from BotMessage m
                        where m.botId = :botId
                        order by m.askedAt desc, m.id desc
                        """, String.class)
                .setParameter("botId", botId)
                .setMaxResults(Math.max(1, limit))
                .getResultList();
    }
}
