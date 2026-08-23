package server.features.release;

import common.dto.notify.NavRef;
import common.dto.notify.NotificationType;
import server.features.notify.Notifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A {@link Notifier} that keeps what it was asked to send (E9 test fixture).
 *
 * <p>{@code Notifier} is an interface precisely so a feature's tests can assert "these users
 * were notified, with this type" without a store, a socket or a session. The rule this epic
 * needs it for is the "opens soon" notice: it must reach every enrolled student and the
 * releasing teacher, it must say how many minutes away the exam is, and it must go out
 * <b>once</b> even though the check that sends it runs every thirty seconds for half an
 * hour.
 */
final class RecordingNotifier implements Notifier {

    /** One notify call. */
    record Sent(List<Long> userIds, NotificationType type, String title, String body, NavRef ref) {
    }

    private final List<Sent> sent = new ArrayList<>();

    @Override
    public Outcome notify(Collection<Long> userIds, NotificationType type,
                          String title, String body, NavRef ref) {
        if (userIds == null || userIds.isEmpty()) {
            return Outcome.NONE;
        }
        List<Long> recipients = userIds.stream().distinct().toList();
        sent.add(new Sent(recipients, type, title, body, ref));
        return new Outcome(recipients.size(), 0);
    }

    /** @return every notify call, in order. */
    List<Sent> all() {
        return List.copyOf(sent);
    }

    /** @return the calls of one type. */
    List<Sent> of(NotificationType type) {
        return sent.stream().filter(entry -> entry.type() == type).toList();
    }

    void clear() {
        sent.clear();
    }
}
