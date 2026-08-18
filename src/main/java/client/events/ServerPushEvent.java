package client.events;

import common.protocol.Verb;

/**
 * The generic EventBus event for a server push (E1.8).
 *
 * <p>One event type carries every {@code PUSH_*} verb: screens subscribe to it
 * and switch on {@link #verb()}, so a new push verb needs no new event class and
 * no change to the bus wiring. Typed convenience events (per feature) may be
 * layered on top later without disturbing this contract.
 *
 * @param verb    the push verb (never {@code null} — {@link PushEventBridge}
 *                drops verb-less pushes before they reach the bus)
 * @param payload the DTO the server sent, possibly {@code null}
 */
public record ServerPushEvent(Verb verb, Object payload) {
}
