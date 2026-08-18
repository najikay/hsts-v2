package client.events;

import client.net.RequestDispatcher;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Turns server pushes into EventBus events (E1.8).
 *
 * <p>Registered as the {@link RequestDispatcher.PushListener}, it is the seam
 * between "bytes off the socket" and "something a screen subscribes to". Unknown
 * or malformed pushes are logged and dropped, never thrown: a server that is one
 * version ahead must not be able to break an older client (ARCHITECTURE §3,
 * forward compatibility).
 */
public class PushEventBridge implements RequestDispatcher.PushListener {

    private static final Logger log = LoggerFactory.getLogger(PushEventBridge.class);

    private final ClientEventBus eventBus;

    public PushEventBridge(ClientEventBus eventBus) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    @Override
    public void onPush(Message push) {
        if (push == null) {
            log.warn("Ignoring null push");
            return;
        }
        Verb verb = push.getVerb();
        if (verb == null) {
            log.warn("Ignoring push with no verb: {}", push);
            return;
        }
        if (!verb.isPush()) {
            // A request verb arriving with PUSH status is a server bug or a spoof;
            // forward it anyway would let it masquerade as a response elsewhere.
            log.warn("Ignoring push carrying non-push verb {}", verb);
            return;
        }
        log.debug("push {} → event bus", verb);
        eventBus.post(new ServerPushEvent(verb, push.getPayload()));
    }
}
