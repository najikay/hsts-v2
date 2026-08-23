package server.features.release;

import common.protocol.Verb;
import server.core.SessionManager;
import server.realtime.PushGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link PushGateway} that remembers what it delivered and to whom (E9 test fixture).
 *
 * <p>A subclass over a real gateway and a real {@link SessionManager} rather than a mock,
 * for the reason the take-exam and edit-lock tests do the same: "who was told" is then
 * decided by exactly the code the running server uses, including the part where a teacher
 * who is not signed in is skipped silently. A mock would let a test pass while the real
 * delivery rule was wrong, and the rule that matters here is that
 * {@code PUSH_EXECUTION_STATUS} reaches <b>both</b> owners of a release — the teacher who
 * released it and the author of the exam — because those are exactly the two people the
 * verbs will admit.
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

    /** @return the pushes of one verb, in order. */
    List<Sent> of(Verb verb) {
        return delivered.stream().filter(sent -> sent.verb() == verb).toList();
    }

    /** @return every user who received this verb, in delivery order and without duplicates. */
    List<Long> recipientsOf(Verb verb) {
        return of(verb).stream().map(Sent::userId).distinct().toList();
    }

    /** @return the payloads of this verb, in order. */
    <T> List<T> payloadsOf(Verb verb, Class<T> type) {
        return of(verb).stream()
                .map(Sent::payload)
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    void clear() {
        delivered.clear();
        byUser.clear();
    }
}
