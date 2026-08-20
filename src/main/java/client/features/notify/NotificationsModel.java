package client.features.notify;

import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationsGetRequest;
import common.dto.notify.NotificationsPage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything the bell knows (Presentation tier, E17.4 — F11.2).
 *
 * <p>The badge, the panel list and the unread highlighting are three views of
 * one state, and that state changes from three different directions: the count
 * that arrives with {@code LoginResult}, the page fetched when the panel opens,
 * and pushes that land at any moment, panel open or closed. Keeping it in a
 * plain class with no FX types means every one of those interleavings is a unit
 * test rather than something to eyeball in a running app.
 *
 * <p>Two rules live here and nowhere else:
 *
 * <ul>
 *   <li><b>the server owns the unread count.</b> A push bumps it by one, but any
 *       page from the server replaces it outright: the server counts every
 *       unread row, the client only ever holds the newest few, so a count
 *       derived from this list would drift the moment a user had more than a
 *       page of them.</li>
 *   <li><b>a push that duplicates a row already listed is dropped.</b> Opening
 *       the panel at the same moment a push arrives is an ordinary race, and it
 *       must not show the same notification twice or count it twice.</li>
 * </ul>
 *
 * <p>Not thread-safe by design: like every client model here it is touched only
 * on the JavaFX Application Thread, which the single EventBus hop guarantees
 * (ARCHITECTURE §6).
 */
public final class NotificationsModel {

    /**
     * How many rows the model keeps. A session left open all day would otherwise
     * grow without bound, and the panel never shows more than a screenful.
     */
    public static final int MAX_ITEMS = NotificationsGetRequest.MAX_LIMIT;

    private final List<NotificationDto> items = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();

    private int unreadCount;
    private boolean loaded;

    // ===================== State in ======================================

    /**
     * Seeds the badge from the sign-in answer (E17.5), before any list exists.
     *
     * @param count unread notifications; negatives are treated as none
     */
    public void setUnreadCount(int count) {
        int safe = Math.max(0, count);
        if (safe != unreadCount) {
            unreadCount = safe;
            notifyChanged();
        }
    }

    /**
     * Replaces the list and the count with a server page — the answer to
     * {@code NOTIFICATIONS_GET} and to every mark-read.
     */
    public void apply(NotificationsPage page) {
        Objects.requireNonNull(page, "page");
        items.clear();
        items.addAll(page.items());
        trim();
        unreadCount = page.unreadCount();
        loaded = true;
        notifyChanged();
    }

    /**
     * Applies a live push (F11.1). Newest first, so it goes on top.
     *
     * @return {@code true} when it was new; {@code false} when a row with this id
     *         is already listed (a push that raced the fetch that included it)
     */
    public boolean receive(NotificationDto notification) {
        Objects.requireNonNull(notification, "notification");
        if (contains(notification.id())) {
            return false;
        }
        items.add(0, notification);
        trim();
        if (notification.isUnread()) {
            unreadCount++;
        }
        notifyChanged();
        return true;
    }

    /** Forgets everything. Called on sign-out, so the next user starts blank. */
    public void clear() {
        items.clear();
        unreadCount = 0;
        loaded = false;
        notifyChanged();
    }

    // ===================== State out =====================================

    /** @return the notifications to render, newest first. */
    public List<NotificationDto> items() {
        return List.copyOf(items);
    }

    /** @return the server's unread count, which may exceed {@link #items()}. */
    public int unreadCount() {
        return unreadCount;
    }

    public boolean hasUnread() {
        return unreadCount > 0;
    }

    /** @return {@code true} once a server page has been applied at least once. */
    public boolean isLoaded() {
        return loaded;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    /** @return {@code true} when a notification with this id is listed. */
    public boolean contains(long id) {
        return items.stream().anyMatch(item -> item.id() == id);
    }

    // ===================== Listeners =====================================

    /** Subscribes to "something changed, re-read me". */
    public void onChange(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** @return the number of subscribers (leak checks in tests). */
    public int listenerCount() {
        return listeners.size();
    }

    /** Drops every subscriber; the panel calls this when the shell is torn down. */
    public void clearListeners() {
        listeners.clear();
    }

    private void notifyChanged() {
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }

    private void trim() {
        while (items.size() > MAX_ITEMS) {
            items.remove(items.size() - 1);
        }
    }
}
