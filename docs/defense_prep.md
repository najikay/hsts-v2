# HSTS — Defense Preparation (הגנה)

Likely examiner questions on our architectural choices, with confident, senior-level
answers. The throughline of every answer: **we optimized the prototype for traceability
and correctness, on an architecture deliberately shaped to scale.**

---

### Q1. Why did you build a Singleton `ScreenManager` for an application that has only **one** screen?

**Answer.** Two reasons — one structural, one forward-looking.

First, even with one screen the `ScreenManager` solves a real problem *today*: it is the
single owner of the JavaFX `Stage` **and** the shared `IClientConnection`. Without it,
those would either be passed around manually (constructor-threading through every view) or
duplicated. The Singleton gives every screen a clean, global access point to navigation
and the network adapter.

Second, the pattern is chosen for the *documented* scalable design, not the current line
count. The whole point of a navigation controller is to centralize routing **before** you
have many screens, so that adding the second, third, and tenth screen is a one-line
`setScreen(...)` call against an established contract — not a refactor of how navigation
works. Introducing it now, while it's cheap, is precisely when you should: it costs one
small class today and saves an architectural retrofit later. Building it after the screens
multiply is the expensive path.

I'd also note it's a *true* prototype-appropriate Singleton — lazily initialized, single
responsibility — not a god object. It holds navigation state, nothing more.

---

### Q2. Your data flow crosses threads. Explain `Platform.runLater()` — what breaks without it?

**Answer.** OCSF reads from the socket on a **background thread** — that's how the client
receives a server response without freezing the UI. But JavaFX has a hard rule: the scene
graph may only be touched from the **JavaFX Application Thread**. If I called
`listView.getItems().setAll(...)` directly from the OCSF read thread, I'd get undefined
behavior — anything from a silent corruption to an `IllegalStateException`.

`Platform.runLater(runnable)` enqueues that work onto the FX thread's event queue, so it
runs safely in the UI's own thread. I centralized this in exactly one place:
`HSTSClient.handleMessageFromServer` wraps the hand-off to the UI controller in
`Platform.runLater`. The deliberate payoff is that `QuestionsView` is completely free of
threading code — it only ever executes on the FX thread, so the view stays simple and the
one threading concern in the whole client lives at the boundary where the threads actually meet.

---

### Q3. Why is there a separate `Launcher.java`? Why not just put `main` in the JavaFX `ClientApp`?

**Answer.** This bypasses a real JavaFX packaging restriction. Since JavaFX 11, the
framework is modular and is no longer part of the JDK. When you launch a shaded ("fat")
jar whose `Main-Class` is a subclass of `javafx.application.Application`, the JavaFX
runtime detects it's being launched without a proper module path and **refuses to start**,
with the classic *"JavaFX runtime components are missing"* error.

The fix is a plain launcher class that does **not** extend `Application`. The manifest
points `Main-Class` at `client.ui.Launcher`; the JVM enters there on the classpath (no
module check), and `Launcher` then calls into `ClientApp`. It's the standard, idiomatic
workaround for double-clickable JavaFX fat jars.

In our case `Launcher` does double duty: it also boots the Fat Server on a background
daemon thread and waits ~1s for the port to bind before starting the client — giving us
the single-click demo. I made the server thread a **daemon** specifically so closing the
window terminates the whole JVM cleanly, rather than leaving a non-daemon server thread
keeping the process alive.

---

### Q4. You wrote your own OCSF classes. How do you justify hiding them behind an Adapter, and isn't reimplementing a framework risky?

**Answer.** Two parts.

On the **Adapter**: the UI depends only on the `IClientConnection` interface, never on
OCSF types. `HSTSClient` is the OCSF-backed implementation. This isolates the entire
networking technology behind one seam. If we migrate to gRPC or REST — which is exactly
what a Kubernetes-fronted deployment would want — we write one new class implementing
`IClientConnection` and change nothing in the UI. That's the Dependency Inversion
principle paying off: the high-level UI policy doesn't depend on the low-level transport detail.

On **reimplementing OCSF**: I didn't reinvent a protocol — I provided a streamlined,
contract-faithful implementation of the standard OCSF API (`listen`, `sendToClient`,
`sendToServer`, `handleMessageFromClient`, the lifecycle hooks). The benefit is the
project is **100% self-contained** — no fragile dependency on a local, non-Maven-Central
jar. The risk is bounded because the surface is small, well-understood (sockets +
`ObjectInputStream`/`ObjectOutputStream`), and — thanks to the Adapter — swappable. If the
native version ever proved insufficient, replacing it touches one tier, not the app.

---

### Q5. How does this architecture defend against SQL injection?

**Answer.** Every database query in `QuestionDAO` uses a parameterized
`PreparedStatement`. I never concatenate user input into SQL text. The query template
(with `?` placeholders) is sent to the database driver **separately** from the bound values
(`ps.setString`, `ps.setInt`). Because the SQL structure is fixed before the values are
attached, user input can never be parsed as SQL — a malicious string like
`'; DROP TABLE Questions; --` is stored or matched as a literal value, not executed.

That's the technical layer. The architectural layer reinforces it: the **Fat Server is a
gatekeeper**. Clients can't issue arbitrary SQL at all — they send a constrained `Message`
with a known `Command` enum, which the server validates before the DAO ever runs. So we
have defense in depth: a narrow, validated request protocol *and* parameterized queries
behind it.

---

### Q6. Why object serialization over the wire, and what are its risks? Why `Serializable` on `Question` and `Message`?

**Answer.** OCSF transports Java objects via `ObjectOutputStream`/`ObjectInputStream`, so
any object crossing the network must implement `java.io.Serializable` — that's why both the
`Message` envelope and its `Question` payload implement it, each with a fixed
`serialVersionUID` to keep client and server wire-compatible. The benefit for a prototype
is enormous: a strongly-typed `Message(Command, payload)` protocol with **zero**
manual parsing or JSON marshalling — the same classes exist on both ends.

The honest risk: Java deserialization is famously unsafe when the peer is **untrusted**,
because a crafted stream can instantiate unexpected types. We accept it here because both
ends are our own code on a trusted local boundary. At enterprise scale — when the server is
remote and multi-tenant — this is exactly the seam the **Adapter** lets us replace: we'd
move to a schema-based, validated transport (gRPC/Protobuf or JSON-over-HTTPS) without
touching the UI. So the prototype gets simplicity now, and the architecture keeps the
migration path open.

---

### Q7. This is a monolith. Isn't that an anti-pattern? How does it become enterprise-grade?

**Answer.** A monolith is the *correct* starting point, not an anti-pattern — premature
microservices are the anti-pattern. What matters is whether the monolith has **clean
internal seams**, and ours does: strict tier separation (presentation / logic / data),
patterns at each boundary (Adapter, DAO, Singleton, Template Method), and a server that's
already an independent process with no UI dependencies.

That's what makes the evolution mechanical rather than a rewrite:
- **Containerize** only the server (it's already standalone) → Docker image.
- **Orchestrate** with Kubernetes — run N stateless server replicas behind a load
  balancer; clients reconnect through the Adapter, a one-class host change.
- **Externalize** `DatabaseConfig`'s constants to environment variables (12-factor).
- **Grow** the gatekeeper into a multi-provider LLM router, logging token usage and
  provenance into the `ai_metadata` JSON column the schema already reserves.

So the defense is: we built a *well-factored* monolith whose seams are the exact fault
lines along which it splits and scales. We earned simplicity today without mortgaging
tomorrow.

---

## One-line summaries (rapid-fire recall)

- **Singleton ScreenManager:** central owner of Stage + connection; cheap now, avoids a
  navigation retrofit later.
- **Platform.runLater:** marshals OCSF's background-thread responses onto the JavaFX
  thread; centralized in `HSTSClient` so the view stays threading-free.
- **Launcher.java:** non-`Application` Main-Class to bypass JavaFX's modular fat-jar block;
  also daemon-boots the server for one-click demo.
- **Adapter (IClientConnection):** hides OCSF; future gRPC/REST swap = one class.
- **PreparedStatement:** query structure and values sent separately → injection neutralized.
- **Gatekeeper:** validated `Message`/`Command` protocol + server-side authority = defense
  in depth, and the template for the future LLM gatekeeper.
