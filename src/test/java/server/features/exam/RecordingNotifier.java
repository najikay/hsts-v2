package server.features.exam;

import common.dto.notify.NavRef;
import common.dto.notify.NotificationType;
import server.features.notify.Notifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A {@link Notifier} that keeps what it was asked to send.
 *
 * <p>{@code Notifier} is an interface precisely so a feature's tests can assert "these
 * users were notified, with this type" without a store, a socket or a session
 * (see its javadoc). Two things in this epic depend on that: the C-4 integrity alert must
 * reach the executing teacher and nobody else, and an extension must reach every student
 * sitting the execution <em>whether or not they are online</em>, which is the half a push
 * cannot cover (E11.4).
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
        // Persisted for everyone, delivered live to nobody: the pessimistic answer, so a
        // caller that reads reachedAnyoneLive() is exercised on its false branch too.
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

    /** @return every user id that was notified at all. */
    List<Long> recipients() {
        return sent.stream().flatMap(entry -> entry.userIds().stream()).distinct().toList();
    }

    void clear() {
        sent.clear();
    }
}
