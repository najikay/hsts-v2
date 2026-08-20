package server.db.repos;

import org.hibernate.Session;
import server.db.entities.Bot;
import server.db.entities.BotSource;

import java.util.List;
import java.util.Optional;

/** Reads over the bot tables (E2.11). */
public final class BotRepository {

    /**
     * The bot for a course.
     *
     * <p>{@code bots.course} is unique — one bot per course (S-30) — so this is at most one
     * row.
     *
     * <p>Consumers: E14 bot chat; E14 bot manager; the E2.15 seed loader.
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
}
