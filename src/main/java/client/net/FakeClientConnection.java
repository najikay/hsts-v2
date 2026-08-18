package client.net;

import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * In-memory {@link IClientConnection} for tests (E1.9) — the reason client
 * session classes reach 90% coverage without a socket or an FX toolkit.
 *
 * <p>It does three things:
 * <ul>
 *   <li><b>records</b> every message the code under test sends, so a test can
 *       assert the verb and payload that went out;</li>
 *   <li><b>answers</b> them from a per-verb script
 *       ({@link #replyOk}, {@link #replyError}, {@link #respondTo}) — the reply
 *       is correlated to the actual request, so the real
 *       {@link RequestDispatcher} completes the real future;</li>
 *   <li><b>injects</b> server pushes ({@link #pushToClient}) and failures
 *       ({@link #failConnectWith}, {@link #failSendsWith}) on demand.</li>
 * </ul>
 *
 * <p>It lives in {@code src/main} on purpose: {@code client.features.*} session
 * classes name it in their own test fixtures, and keeping one implementation
 * (rather than a copy per test source set) means the fake can never drift from
 * the {@link IClientConnection} contract it stands in for.
 */
public class FakeClientConnection implements IClientConnection {

    private final String host;
    private final int port;

    private final List<Message> sent = new CopyOnWriteArrayList<>();
    private final Map<Verb, UnaryOperator<Message>> responders = new ConcurrentHashMap<>();

    private volatile Consumer<Message> handler;
    private volatile boolean open;
    private volatile IOException connectFailure;
    private volatile IOException sendFailure;

    public FakeClientConnection() {
        this("fake-host", 5555);
    }

    public FakeClientConnection(String host, int port) {
        this.host = host;
        this.port = port;
    }

    // ===================== IClientConnection =============================

    @Override
    public void connect() throws IOException {
        if (connectFailure != null) {
            throw connectFailure;
        }
        open = true;
    }

    @Override
    public void send(Message msg) throws IOException {
        sent.add(msg);
        if (sendFailure != null) {
            throw sendFailure;
        }
        UnaryOperator<Message> responder = msg.getVerb() == null ? null : responders.get(msg.getVerb());
        if (responder != null) {
            deliver(responder.apply(msg));
        }
    }

    @Override
    public void disconnect() {
        open = false;
    }

    @Override
    public boolean isConnectionOpen() {
        return open;
    }

    @Override
    public String getHost() {
        return host;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public void setServerMessageHandler(Consumer<Message> handler) {
        this.handler = handler;
    }

    // ===================== Scripting =====================================

    /** Answers {@code verb} with whatever the function builds from the request. */
    public FakeClientConnection respondTo(Verb verb, UnaryOperator<Message> responder) {
        responders.put(Objects.requireNonNull(verb, "verb"), Objects.requireNonNull(responder, "responder"));
        return this;
    }

    /** Answers {@code verb} with a correlated OK carrying {@code payload}. */
    public FakeClientConnection replyOk(Verb verb, Object payload) {
        return respondTo(verb, request -> Message.ok(request, payload));
    }

    /** Answers {@code verb} with a correlated ERROR. */
    public FakeClientConnection replyError(Verb verb, ErrorCode code, String message) {
        return respondTo(verb, request -> Message.error(request, code, message));
    }

    /** Makes {@link #connect()} fail until cleared with {@code null}. */
    public FakeClientConnection failConnectWith(IOException failure) {
        this.connectFailure = failure;
        return this;
    }

    /** Makes {@link #send(Message)} fail (after recording) until cleared with {@code null}. */
    public FakeClientConnection failSendsWith(IOException failure) {
        this.sendFailure = failure;
        return this;
    }

    /** Injects a server-initiated push. */
    public void pushToClient(Verb verb, Object payload) {
        deliver(Message.push(verb, payload));
    }

    /** Hands an arbitrary message to the registered handler, as the socket would. */
    public void deliver(Message message) {
        Consumer<Message> target = this.handler;
        if (target != null) {
            target.accept(message);
        }
    }

    // ===================== Assertions ====================================

    /** @return every message sent so far, oldest first (snapshot). */
    public List<Message> sentMessages() {
        return List.copyOf(sent);
    }

    /** @return the most recently sent message, or {@code null} if nothing was sent. */
    public Message lastSent() {
        return sent.isEmpty() ? null : sent.get(sent.size() - 1);
    }

    /** @return how many messages have been sent. */
    public int sentCount() {
        return sent.size();
    }

    /** Forgets the recorded traffic (keeps the scripted responders). */
    public void clearSent() {
        sent.clear();
    }
}
