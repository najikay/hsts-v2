package server.db.repos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.Bot;
import server.db.entities.BotSource;
import server.db.entities.BotSourceType;
import server.db.entities.Notification;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code BotRepository} and {@code NotificationRepository} (E2.11). */
abstract class BotNotificationContract extends RepositoryTestBase {

    private static final Instant WHEN = Instant.parse("2026-08-20T09:00:00Z");

    private final BotRepository bots = new BotRepository();
    private final NotificationRepository notifications = new NotificationRepository();

    @Test
    @DisplayName("a course has at most one bot, found by course code")
    void findsBotByCourse() {
        persistBot(COURSE_ALGEBRA, "עוזר אלגברה");

        Optional<String> found = inTx(session ->
                bots.findByCourse(session, COURSE_ALGEBRA).map(Bot::getName));

        assertThat(found).contains("עוזר אלגברה");
    }

    @Test
    @DisplayName("a course with no bot is empty rather than an error")
    void courseWithoutABot() {
        Optional<Bot> none = inTx(session -> bots.findByCourse(session, COURSE_JAVA));

        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("a bot's sources come back oldest first")
    void findsSourcesInOrder() {
        long botId = persistBot(COURSE_ALGEBRA, "עוזר אלגברה");
        persistSource(botId, "סיכום ראשון");
        persistSource(botId, "סיכום שני");

        List<BotSource> sources = inTx(session -> bots.findSources(session, botId));

        assertThat(sources).extracting(BotSource::getTitle)
                .containsExactly("סיכום ראשון", "סיכום שני");
    }

    @Test
    @DisplayName("sources belong to their own bot only")
    void sourcesAreScopedToTheBot() {
        long algebra = persistBot(COURSE_ALGEBRA, "עוזר אלגברה");
        long java = persistBot(COURSE_JAVA, "עוזר Java");
        persistSource(algebra, "סיכום אלגברה");

        List<BotSource> javaSources = inTx(session -> bots.findSources(session, java));

        assertThat(javaSources).isEmpty();
    }

    @Test
    @DisplayName("unread notifications come back newest first")
    void unreadNewestFirst() {
        persistNotification("ציון ראשון", WHEN, false);
        persistNotification("ציון שני", WHEN.plusSeconds(60), false);

        List<Notification> unread = inTx(session -> notifications.findUnread(session, mayaId));

        assertThat(unread).extracting(Notification::getTitle)
                .containsExactly("ציון שני", "ציון ראשון");
    }

    @Test
    @DisplayName("a notification that has been read is not unread")
    void readOnesAreExcluded() {
        persistNotification("נקרא", WHEN, true);
        persistNotification("לא נקרא", WHEN, false);

        List<Notification> unread = inTx(session -> notifications.findUnread(session, mayaId));

        assertThat(unread).extracting(Notification::getTitle).containsExactly("לא נקרא");
    }

    @Test
    @DisplayName("the badge count matches the unread list")
    void countMatchesTheList() {
        persistNotification("אחת", WHEN, false);
        persistNotification("שתיים", WHEN, false);
        persistNotification("נקרא", WHEN, true);

        long count = inTx(session -> notifications.countUnread(session, mayaId));
        List<Notification> unread = inTx(session -> notifications.findUnread(session, mayaId));

        assertThat(count).isEqualTo(2);
        assertThat(unread).hasSize(2);
    }

    @Test
    @DisplayName("notifications are per user")
    void notificationsAreScopedToTheRecipient() {
        persistNotification("למאיה", WHEN, false);

        long danasCount = inTx(session -> notifications.countUnread(session, danaId));

        assertThat(danasCount).isZero();
    }

    private long persistBot(String courseCode, String name) {
        return inTx(session -> {
            Bot bot = new Bot(courseCode, name);
            session.persist(bot);
            session.flush();
            return bot.getId();
        });
    }

    private void persistSource(long botId, String title) {
        runInTx(session -> session.persist(new BotSource(botId, BotSourceType.TEXT, title,
                title.getBytes(StandardCharsets.UTF_8), title, danaId, WHEN)));
    }

    private void persistNotification(String title, Instant createdAt, boolean read) {
        runInTx(session -> {
            Notification notification = new Notification(mayaId, "GRADE_PUBLISHED", title,
                    "גוף ההודעה", "GRADE", 1L, createdAt);
            if (read) {
                notification.markRead(createdAt.plusSeconds(10));
            }
            session.persist(notification);
        });
    }
}
