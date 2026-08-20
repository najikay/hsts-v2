package server.features.bot;

import common.dto.notify.NavRef;
import common.dto.notify.NotificationType;
import server.features.notify.Notifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A {@link Notifier} that remembers who was told what (E16.9 — F12.3).
 *
 * <p>The seam being an interface is what makes this three lines rather than a
 * notification store, a push gateway and a session map; the assertion a test
 * actually wants is "these ids, this type", and that is all this records.
 */
final class RecordingNotifier implements Notifier {

    /** One entry per {@code notify} call. */
    record Sent(List<Long> userIds, NotificationType type, String title, String body, NavRef ref) {
    }

    final List<Sent> sent = new ArrayList<>();

    @Override
    public Outcome notify(Collection<Long> userIds, NotificationType type,
                          String title, String body, NavRef ref) {
        List<Long> recipients = userIds == null ? List.of() : List.copyOf(userIds);
        sent.add(new Sent(recipients, type, title, body, ref));
        return new Outcome(recipients.size(), 0);
    }

    /** @return every user id that was told anything. */
    List<Long> recipients() {
        return sent.stream().flatMap(entry -> entry.userIds().stream()).toList();
    }
}
