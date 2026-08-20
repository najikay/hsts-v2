package server.features.exam;

import common.protocol.Verb;
import server.core.SessionManager;
import server.realtime.PushGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link PushGateway} that remembers what it sent and to whom.
 *
 * <p>A subclass over a real gateway and a real {@link SessionManager} rather than a mock,
 * for the same reason the edit-lock tests do it: "who was told" is then decided by exactly
 * the code the running server uses, including the part where an offline user is skipped
 * silently. A mock would let a test pass while the real delivery rule was wrong.
 *
 * <p>That matters most for {@code PUSH_FORCE_SUBMITTED}, which is deliberately best-effort:
 * the attempt is closed in the database whether or not the push lands, and these tests need
 * to be able to assert the "she was offline and it happened anyway" case (E10.5 ⚑) as well
 * as the delivered one.
 */
final class RecordingGateway extends PushGateway {

    /** One delivered push. */
    record Sent(long userId, Verb verb, Object payload) {
    }

    private final List<Sent> delivered = new ArrayList<>();
    private final Map<Long, List<Sent>> byUser = new ConcurrentHashMap<>();

    RecordingGateway(SessionManager sessions) {
        super(sessions);
    }

    @Override
    public boolean toUser(long userId, Verb verb, Object payload) {
        boolean sent = super.toUser(userId, verb, payload);
        if (sent) {
            Sent record = new Sent(userId, verb, payload);
            delivered.add(record);
            byUser.computeIfAbsent(userId, key -> new ArrayList<>()).add(record);
        }
        return sent;
    }

    /** @return every push that actually reached a socket. */
    List<Sent> sent() {
        return List.copyOf(delivered);
    }

    /** @return the pushes of one verb, in order. */
    List<Sent> of(Verb verb) {
        return delivered.stream().filter(sent -> sent.verb() == verb).toList();
    }

    /** @return the first payload of this verb sent to this user. */
    <T> Optional<T> firstPayload(long userId, Verb verb, Class<T> type) {
        return byUser.getOrDefault(userId, List.of()).stream()
                .filter(sent -> sent.verb() == verb)
                .map(Sent::payload)
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst();
    }

    /** @return how many pushes of this verb reached this user. */
    long countFor(long userId, Verb verb) {
        return byUser.getOrDefault(userId, List.of()).stream()
                .filter(sent -> sent.verb() == verb)
                .count();
    }

    void clear() {
        delivered.clear();
        byUser.clear();
    }
}
