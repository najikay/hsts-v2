/**
 * Server-side LAN discovery (E19.8/E19.9, F13.3).
 *
 * <p>A package of its own rather than a corner of {@code server.console}, and the
 * reason is that it is not a console feature. The responder answers broadcasts
 * whether or not a window is open, it runs in {@code --headless} mode exactly as
 * it does with the console up, and its lifetime belongs to the server rather than
 * to the UI. The console owns a <em>toggle</em> for it (F13.3) and shows the
 * fingerprint beside the address; that is the whole relationship, and it points
 * this way, not back.
 *
 * <p>Two classes carry the feature. {@link server.discovery.ServerFingerprint} is
 * this installation's identity, generated on first boot and persisted beside
 * {@code server.properties}; its javadoc states plainly what the identity does
 * and does not prove, and that wording is the one the defence uses.
 * {@link server.discovery.DiscoveryResponder} is the UDP loop, which treats
 * every datagram as hostile until it turns out to be the magic string.
 *
 * <p>The wire format itself lives in {@code common.dto.discovery} because the
 * client parses it too, and it is JSON rather than a serialized
 * {@code common.protocol.Message} for a reason stated there: this is the one
 * socket in the system that unauthenticated strangers can write to.
 *
 * <p>ARCHITECTURE §2 lists {@code server/console/} as "server console UI +
 * NetworkDetector"; this package is the addition E19 made to that list.
 */
package server.discovery;
