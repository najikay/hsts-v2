# Connect wire contract — `HELLO`

**Status: amendment, 2026-08-30.** Raised by B-49 (`docs/ACCEPTANCE_TESTS.md`). Additive: one new
verb, no change to any existing message. Nothing here is renamed, retyped or removed.

Package: none (the verb carries no payload). Verb: `common/protocol/Verb.java`, in the
`Connection & session (E1/E5)` group. Handler: `server/core/HelloResponder`.
Client: `client/features/connect/ConnectHandshake`.

---

## 1. Why the protocol grew a verb for this

A TCP connect is not evidence that a server is there. The operating system completes the handshake
into a listening socket's accept backlog on the server's behalf, so a server that is bound but no
longer accepting hands a connecting client a healthy socket that nobody will ever read from. Every
check the client can make locally says the connection is fine.

That is not a hypothetical shape. It is what the server console's **Stop listening** left behind
until B-49: OCSF's `stopListening()` raised a flag to end the accept loop and left the `ServerSocket`
bound. A client dialling that server sat on `Connecting...` with no timeout on it at all, and the
only thing that ever released it was an operator closing the console window, which closed the socket
properly and finally produced an error the client could report.

The server side of B-49 closes the socket, so the ordinary case is now a refusal the client sees
immediately. `HELLO` covers the cases closing a socket cannot: a server that accepted the connection
and then wedged, a port forwarded to nothing that still completes handshakes, a proxy that dials
through and holds the connection open. For those, the only proof is a round trip — something on the
far end must read a request and write a response, which no kernel does on a server's behalf.

## 2. The message

| direction | verb | status | payload |
| --- | --- | --- | --- |
| client → server | `HELLO` | `REQUEST` | `null` |
| server → client | `HELLO` | `OK` | `null` |

Correlated by `requestId` like every other request/response pair, so the standard
`RequestDispatcher` completes the standard future.

**The response payload is `null`, and that is a rule rather than an omission.** `HELLO` is
registered with `MessageRouter.registerOpen`, making it the second verb after `LOGIN` that an
unauthenticated connection can reach. An anonymous caller therefore learns exactly one fact from it,
and it is the fact the socket already told them: this port belongs to an HSTS server that is
running. No version, no server name, no user counts, no uptime. If a later change wants any of
those on the connect screen, it needs its own decision about what an anonymous stranger may read,
recorded here.

## 3. Any answer proves the server, including a refusal ⚑

`ConnectHandshake.prove` does not inspect the response. An `OK` proves a server is alive; so does
`BAD_REQUEST: unsupported verb`, which is what a server built before this amendment answers; so
would an `UNAUTHORIZED`. The client is not asking the server a question. It is asking *whether there
is a server*, and every one of those answers says yes.

Only silence fails. This is what keeps the amendment compatible in the direction that matters: a new
client against an old server jar works, because the old server's "I do not know that verb" is itself
the proof being asked for. An old client against a new server is unaffected, since it never sends
`HELLO`.

## 4. Timing

The client allows **5 seconds** (`ConnectHandshake.TIMEOUT`) for an answer, with a two second
backstop above the dispatcher's own timer. On expiry the connect fails with
`ConnectFlow.UNREACHABLE_TIMEOUT` — *"That address did not answer."* — and the socket is closed
through `ConnectWiring.abandon`. No exception type or class name reaches the screen (B-37).

The socket open is bounded independently, and its two halves are bounded **separately**
(amended 2026-08-31, after the two-machine regression): `AbstractClient.openConnection` applies
`DEFAULT_DIAL_TIMEOUT_MS` (15 s) to the TCP dial and `DEFAULT_HANDSHAKE_TIMEOUT_MS` (5 s) to the
serialization stream handshake. The first bound is generous because a first connect across a real
LAN is routinely slow for reasons that end well — a firewall prompt, a scanned jar, a retransmitted
SYN landing at seven seconds — and the original single 5 s bound killed exactly those connects.
The second stays tight because it is the half that detects a stopped server: the kernel completed
the handshake into the backlog for free, and a live server writes its header in milliseconds. A
client that never gets that far fails with a `SocketTimeoutException` and the same sentence. While
a long dial is in progress the connect button reads "Still trying" so the wait shows as progress.

The server bounds its half of the stream handshake with the same 5 s
(`AbstractServer.DEFAULT_HANDSHAKE_TIMEOUT_MS`), so a socket that connects and never writes a
header costs the accept loop seconds, not forever — and it no longer blocks other clients while it
waits, because the handshake happens outside the connections lock.

After the connection is up, a request that times out triggers one `HELLO` liveness probe
(`ConnectWiring.silenceProbe`, 3 s): any answer keeps the connection, silence condemns it through
the same fan-out a read failure uses, so the reconnect banner engages even when the socket died
without the read thread noticing (sleep and wake).

## 5. Who asks it

Both connect paths, and no others:

* `ConnectView` — the first connect of a session, whether typed by hand or chosen from discovery.
* `Reconnector` — every re-dial behind the shell's reconnect banner. A re-dial that landed in a
  stopped server's backlog would otherwise turn the banner green and strand every screen behind it.

`FakeClientConnection` answers `HELLO` from construction, because it stands in for a connection to a
server that is up. A test that wants a different answer overrides it with `respondTo`.
