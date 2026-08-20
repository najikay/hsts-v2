package client.features.notify;

import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import common.dto.notify.NotificationsPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the bell's state (E17.4/E17.6).
 *
 * <p>The three interleavings this model exists to survive are all here: a count
 * arriving before any list (sign-in), a push landing while the panel is open,
 * and a push racing the fetch that already contained it. None needs a toolkit,
 * which is the point of the model being FX-free.
 */
class NotificationsModelTest {

    private static final Instant T0 = Instant.parse("2026-08-19T09:00:00Z");

    private NotificationsModel model;
    private AtomicInteger changes;

    @BeforeEach
    void setUp() {
        model = new NotificationsModel();
        changes = new AtomicInteger();
        model.onChange(changes::incrementAndGet);
    }

    @Test
    @DisplayName("a fresh model is empty and unloaded")
    void startsEmpty() {
        assertThat(model.items()).isEmpty();
        assertThat(model.unreadCount()).isZero();
        assertThat(model.hasUnread()).isFalse();
        assertThat(model.isLoaded()).isFalse();
        assertThat(model.isEmpty()).isTrue();
        assertThat(model.size()).isZero();
    }

    @Test
    @DisplayName("the sign-in count seeds the badge before any list exists (E17.5)")
    void seedsFromLogin() {
        model.setUnreadCount(3);

        assertThat(model.unreadCount()).isEqualTo(3);
        assertThat(model.hasUnread()).isTrue();
        assertThat(model.isLoaded())
                .as("a count is not a list; the panel still has to fetch")
                .isFalse();
        assertThat(changes.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("setting the same count again notifies nobody")
    void idempotentCount() {
        model.setUnreadCount(3);
        model.setUnreadCount(3);

        assertThat(changes.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a negative count is treated as none")
    void negativeCountIsClamped() {
        model.setUnreadCount(-5);

        assertThat(model.unreadCount()).isZero();
    }

    @Test
    @DisplayName("a server page replaces both the list and the count")
    void applyReplacesEverything() {
        model.setUnreadCount(99);

        model.apply(new NotificationsPage(List.of(row(2, false), row(1, true)), 1));

        assertThat(model.items()).extracting(NotificationDto::id).containsExactly(2L, 1L);
        assertThat(model.unreadCount())
                .as("the server's count wins over anything the client counted")
                .isEqualTo(1);
        assertThat(model.isLoaded()).isTrue();
    }

    @Test
    @DisplayName("a push goes on top and bumps the count")
    void pushPrepends() {
        model.apply(new NotificationsPage(List.of(row(1, true)), 1));
        changes.set(0);

        assertThat(model.receive(row(2, true))).isTrue();

        assertThat(model.items()).extracting(NotificationDto::id).containsExactly(2L, 1L);
        assertThat(model.unreadCount()).isEqualTo(2);
        assertThat(changes.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a push that duplicates a listed row is dropped, and counted once")
    void duplicatePushIsDropped() {
        model.apply(new NotificationsPage(List.of(row(7, true)), 1));
        changes.set(0);

        assertThat(model.receive(row(7, true))).isFalse();

        assertThat(model.size()).isEqualTo(1);
        assertThat(model.unreadCount()).isEqualTo(1);
        assertThat(changes.get())
                .as("nothing changed, so no view needs to re-render")
                .isZero();
    }

    @Test
    @DisplayName("an already-read push does not bump the badge")
    void readPushDoesNotCount() {
        model.receive(new NotificationDto(3L, NotificationType.TIME_EXTENDED, "t", "",
                NavRef.none(), T0, T0));

        assertThat(model.size()).isEqualTo(1);
        assertThat(model.unreadCount()).isZero();
    }

    @Test
    @DisplayName("the list is capped, so a day-long session cannot grow without bound")
    void listIsCapped() {
        List<NotificationDto> many = IntStream.rangeClosed(1, NotificationsModel.MAX_ITEMS + 20)
                .mapToObj(i -> row(i, true))
                .toList();

        model.apply(new NotificationsPage(many, many.size()));

        assertThat(model.size()).isEqualTo(NotificationsModel.MAX_ITEMS);
        assertThat(model.unreadCount())
                .as("the count is still the server's, not the capped list's")
                .isEqualTo(many.size());
    }

    @Test
    @DisplayName("pushes past the cap drop the oldest row, not the newest")
    void pushesEvictTheOldest() {
        List<NotificationDto> full = IntStream.rangeClosed(1, NotificationsModel.MAX_ITEMS)
                .mapToObj(i -> row(i, false))
                .toList();
        model.apply(new NotificationsPage(full, 0));

        model.receive(row(99_999, true));

        assertThat(model.size()).isEqualTo(NotificationsModel.MAX_ITEMS);
        assertThat(model.items().get(0).id()).isEqualTo(99_999L);
        assertThat(model.contains(1L)).isTrue();
    }

    @Test
    @DisplayName("clear() empties everything, so the next user starts blank")
    void clearResets() {
        model.apply(new NotificationsPage(List.of(row(1, true)), 1));

        model.clear();

        assertThat(model.items()).isEmpty();
        assertThat(model.unreadCount()).isZero();
        assertThat(model.isLoaded()).isFalse();
    }

    @Test
    @DisplayName("items() hands out a copy nobody can mutate")
    void itemsAreDefensive() {
        model.apply(new NotificationsPage(new ArrayList<>(List.of(row(1, true))), 1));

        assertThatThrownBy(() -> model.items().add(row(2, true)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("listeners can be counted and dropped")
    void listenerBookkeeping() {
        assertThat(model.listenerCount()).isEqualTo(1);

        model.clearListeners();
        model.setUnreadCount(4);

        assertThat(model.listenerCount()).isZero();
        assertThat(changes.get()).isZero();
    }

    @Test
    @DisplayName("null inputs are refused rather than half-applied")
    void nullsAreRefused() {
        assertThatNullPointerException().isThrownBy(() -> model.apply(null));
        assertThatNullPointerException().isThrownBy(() -> model.receive(null));
        assertThatNullPointerException().isThrownBy(() -> model.onChange(null));
    }

    private static NotificationDto row(long id, boolean unread) {
        return new NotificationDto(id, NotificationType.APPROVAL_REQUESTED, "Exam waiting", "body",
                NavRef.to("approvals", id), T0.plusSeconds(id), unread ? null : T0.plusSeconds(id + 1));
    }
}
