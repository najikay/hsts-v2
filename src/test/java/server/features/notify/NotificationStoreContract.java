package server.features.notify;

import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link NotificationStore} contract, written once and run against every implementation
 * (E17.1/E17.6 — E2 integration).
 *
 * <p>{@code InMemoryNotificationStoreTest}'s assertions used to live in that one class, above
 * a comment promising the JPA implementation could be "dropped into the same suite". This is
 * that promise cashed: the assertions moved here, and each implementation is a leaf that
 * supplies a store and two user ids. The interface's rules — ownership scoping, newest-first
 * ordering, idempotent marking — are now proved twice rather than once for the map and by
 * inspection for the database, which is the only way "the map behaves like MySQL" stops being
 * a hope.
 *
 * <p>Following the {@code …Contract} pattern of {@code server.db.repos}: the abstract class
 * holds the assertions, the leaves bind them to a backing store. The JPA leaves add a
 * database on top (see {@code JpaNotificationStoreContract}); behaviour that only one
 * implementation can have — {@code clear()}, {@code size()}, the concurrency probe — stays in
 * that implementation's own leaf, where it can actually fail.
 *
 * <p>User ids are hooks rather than constants because {@code notifications.user_id} is a
 * foreign key to {@code users}: the map accepts any number, the real schema does not.
 */
abstract class NotificationStoreContract {

    protected static final Instant T0 = Instant.parse("2026-08-19T09:00:00Z");

    /** @return the store under test; a fresh, empty one per test method */
    protected abstract NotificationStore store();

    /** @return a user id that exists wherever this leaf stores rows */
    protected abstract long userA();

    /** @return a second, different user id that exists wherever this leaf stores rows */
    protected abstract long userB();

    @Test
    @DisplayName("a saved notification comes back with every field intact")
    void persistenceRoundTrip() {
        long id = store().save(userA(), NotificationType.APPROVAL_APPROVED, "Exam approved",
                "Rina Barak approved Midterm.", NavRef.to("exams", 55L), T0);

        List<NotificationDto> rows = store().listRecent(userA(), 10);

        assertThat(rows).hasSize(1);
        NotificationDto row = rows.get(0);
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.type()).isEqualTo(NotificationType.APPROVAL_APPROVED);
        assertThat(row.title()).isEqualTo("Exam approved");
        assertThat(row.body()).isEqualTo("Rina Barak approved Midterm.");
        assertThat(row.ref()).isEqualTo(NavRef.to("exams", 55L));
        assertThat(row.createdAt()).isEqualTo(T0);
        assertThat(row.isUnread()).isTrue();
    }

    @Test
    @DisplayName("a notification with nowhere to go keeps both halves of its reference null")
    void aReferenceToNothingSurvives() {
        // NavRef.none() is two null columns in the table. A store that turned them into
        // empty strings would make every informational row falsely navigable.
        store().save(userA(), NotificationType.INTEGRITY_ALERT, "Bot use flagged", null,
                NavRef.none(), T0);

        NotificationDto row = store().listRecent(userA(), 10).get(0);

        assertThat(row.ref()).isEqualTo(NavRef.none());
        assertThat(row.ref().isNavigable()).isFalse();
        assertThat(row.body()).as("a null body is empty on the wire, never null").isEmpty();
    }

    @Test
    @DisplayName("a route with no entity id round-trips as a route with no entity id")
    void aRouteWithoutAnEntitySurvives() {
        store().save(userA(), NotificationType.RELEASE_OPENING_SOON, "Opening soon", "",
                NavRef.to("executions"), T0);

        NotificationDto row = store().listRecent(userA(), 10).get(0);

        assertThat(row.ref().route()).isEqualTo("executions");
        assertThat(row.ref().entityId()).isNull();
        assertThat(row.ref().isNavigable()).isTrue();
    }

    @Test
    @DisplayName("Hebrew titles and bodies survive the store (X-I18N)")
    void hebrewSurvives() {
        store().save(userA(), NotificationType.GRADE_PUBLISHED, "הציון פורסם",
                "רינה ברק אישרה את המבחן.", NavRef.none(), T0);

        NotificationDto row = store().listRecent(userA(), 10).get(0);

        assertThat(row.title()).isEqualTo("הציון פורסם");
        assertThat(row.body()).isEqualTo("רינה ברק אישרה את המבחן.");
    }

    @Test
    @DisplayName("every notification type survives being stored and read back")
    void everyTypeSurvives() {
        // The type is stored as its name(), so a renamed constant would be an unreadable
        // row rather than a compile error. This is what would catch it.
        for (NotificationType type : NotificationType.values()) {
            store().save(userA(), type, type.name(), "", NavRef.none(), T0);
        }

        assertThat(store().listRecent(userA(), 100))
                .extracting(NotificationDto::type)
                .containsExactlyInAnyOrder(NotificationType.values());
    }

    @Test
    @DisplayName("ids are unique across users, so a mark-read can never be ambiguous")
    void idsAreGloballyUnique() {
        long first = store().save(userA(), NotificationType.TIME_EXTENDED, "a", "", NavRef.none(), T0);
        long second = store().save(userB(), NotificationType.TIME_EXTENDED, "b", "", NavRef.none(), T0);

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("the list is newest first")
    void newestFirst() {
        store().save(userA(), NotificationType.TIME_EXTENDED, "oldest", "", NavRef.none(), T0);
        store().save(userA(), NotificationType.TIME_EXTENDED, "middle", "", NavRef.none(), T0.plusSeconds(60));
        store().save(userA(), NotificationType.TIME_EXTENDED, "newest", "", NavRef.none(), T0.plusSeconds(120));

        assertThat(store().listRecent(userA(), 10))
                .extracting(NotificationDto::title)
                .containsExactly("newest", "middle", "oldest");
    }

    @Test
    @DisplayName("rows created in the same millisecond still have a stable order")
    void tiesBreakOnId() {
        store().save(userA(), NotificationType.TIME_EXTENDED, "first", "", NavRef.none(), T0);
        store().save(userA(), NotificationType.TIME_EXTENDED, "second", "", NavRef.none(), T0);

        assertThat(store().listRecent(userA(), 10))
                .extracting(NotificationDto::title)
                .containsExactly("second", "first");
    }

    @Test
    @DisplayName("the limit truncates the list but never the unread count")
    void limitTruncatesTheListOnly() {
        for (int i = 0; i < 5; i++) {
            store().save(userA(), NotificationType.TIME_EXTENDED, "n" + i, "", NavRef.none(), T0.plusSeconds(i));
        }

        assertThat(store().listRecent(userA(), 2)).hasSize(2);
        assertThat(store().unreadCount(userA())).isEqualTo(5);
        assertThat(store().listRecent(userA(), 0)).isEmpty();
    }

    @Test
    @DisplayName("a user with nothing gets an empty list and a zero count, not a failure")
    void unknownUserIsEmpty() {
        assertThat(store().listRecent(userB(), 10)).isEmpty();
        assertThat(store().unreadCount(userB())).isZero();
        assertThat(store().markAllRead(userB(), T0)).isZero();
        assertThat(store().markRead(userB(), 1L, T0)).isFalse();
    }

    @Test
    @DisplayName("a list is scoped to its owner, so one user never sees another's rows")
    void listsAreScopedToTheirOwner() {
        store().save(userA(), NotificationType.TIME_EXTENDED, "hers", "", NavRef.none(), T0);
        store().save(userB(), NotificationType.TIME_EXTENDED, "his", "", NavRef.none(), T0);

        assertThat(store().listRecent(userA(), 10)).extracting(NotificationDto::title)
                .containsExactly("hers");
        assertThat(store().listRecent(userB(), 10)).extracting(NotificationDto::title)
                .containsExactly("his");
    }

    @Test
    @DisplayName("marking read is scoped to the owner and is idempotent")
    void markReadSemantics() {
        long id = store().save(userA(), NotificationType.GRADE_PUBLISHED, "yours", "", NavRef.none(), T0);

        assertThat(store().markRead(userA(), id, T0.plusSeconds(1))).isTrue();
        assertThat(store().unreadCount(userA())).isZero();
        assertThat(store().listRecent(userA(), 10).get(0).readAt()).isEqualTo(T0.plusSeconds(1));
        // Second time changes nothing: a double-click, or two windows open.
        assertThat(store().markRead(userA(), id, T0.plusSeconds(2))).isFalse();
        assertThat(store().listRecent(userA(), 10).get(0).readAt()).isEqualTo(T0.plusSeconds(1));
    }

    @Test
    @DisplayName("one user cannot mark another user's notification read (E17.6)")
    void markReadCannotCrossUsers() {
        long hers = store().save(userA(), NotificationType.GRADE_PUBLISHED, "yours", "", NavRef.none(), T0);
        store().save(userB(), NotificationType.GRADE_PUBLISHED, "hers", "", NavRef.none(), T0);

        assertThat(store().markRead(userB(), hers, T0.plusSeconds(1))).isFalse();
        assertThat(store().unreadCount(userA()))
                .as("the row is untouched by somebody else naming its id")
                .isEqualTo(1);
        assertThat(store().unreadCount(userB())).isEqualTo(1);
    }

    @Test
    @DisplayName("an id that exists for nobody is refused rather than treated as a miss to hide")
    void markReadOfAnUnknownIdIsFalse() {
        store().save(userA(), NotificationType.GRADE_PUBLISHED, "yours", "", NavRef.none(), T0);

        assertThat(store().markRead(userA(), 987654321L, T0)).isFalse();
        assertThat(store().unreadCount(userA())).isEqualTo(1);
    }

    @Test
    @DisplayName("mark-all touches only the caller's unread rows")
    void markAllIsScopedAndCountsChanges() {
        store().save(userA(), NotificationType.TIME_EXTENDED, "a", "", NavRef.none(), T0);
        long read = store().save(userA(), NotificationType.TIME_EXTENDED, "b", "", NavRef.none(), T0);
        store().save(userB(), NotificationType.TIME_EXTENDED, "c", "", NavRef.none(), T0);
        store().markRead(userA(), read, T0);

        assertThat(store().markAllRead(userA(), T0.plusSeconds(5)))
                .as("only the still-unread row is changed")
                .isEqualTo(1);
        assertThat(store().unreadCount(userA())).isZero();
        assertThat(store().unreadCount(userB())).isEqualTo(1);
        assertThat(store().markAllRead(userA(), T0.plusSeconds(6))).isZero();
    }

    @Test
    @DisplayName("a read row stays in the list, it is not archived away")
    void readRowsRemainListed() {
        long id = store().save(userA(), NotificationType.GRADE_PUBLISHED, "yours", "", NavRef.none(), T0);
        store().markRead(userA(), id, T0.plusSeconds(1));

        assertThat(store().listRecent(userA(), 10)).hasSize(1);
        assertThat(store().listRecent(userA(), 10).get(0).isUnread()).isFalse();
    }
}
