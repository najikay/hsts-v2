package server.core;

import common.dto.bank.Question;
import common.protocol.Message;
import common.protocol.Message.Command;
import ocsf.server.AbstractServer;
import ocsf.server.ConnectionToClient;
import server.db.QuestionDAO;

import java.util.List;

/**
 * The HSTS Fat Server (Logic tier).
 *
 * <p>Extends the native OCSF {@link AbstractServer}. This is the single choke
 * point for all data access — the documented "secure gatekeeper": clients never
 * touch the database directly, they send {@link Message} requests which this
 * server validates, routes to {@link QuestionDAO}, and answers with a response
 * {@link Message}.
 */
public class HSTSServer extends AbstractServer {

    private final QuestionDAO questionDAO = new QuestionDAO();

    public HSTSServer(int port) {
        super(port);
    }

    @Override
    protected void handleMessageFromClient(Object msg, ConnectionToClient client) {
        log("Message received from " + client + ": " + msg);

        if (!(msg instanceof Message)) {
            log("  -> rejected: unrecognized message type "
                    + (msg == null ? "null" : msg.getClass().getName()));
            safeSend(client, new Message(Command.ERROR, "Unrecognized message type."));
            return;
        }

        Message request = (Message) msg;
        try {
            switch (request.getCommand()) {
                case GET_ALL_QUESTIONS:
                    handleGetAll(client);
                    break;
                case UPDATE_QUESTION:
                    handleUpdate(request, client);
                    break;
                default:
                    log("  -> unsupported command: " + request.getCommand());
                    safeSend(client, new Message(Command.ERROR,
                            "Unsupported command: " + request.getCommand()));
            }
        } catch (Exception e) {
            log("  -> handler threw: " + e.getMessage());
            safeSend(client, new Message(Command.ERROR,
                    "Server error: " + e.getMessage()));
        }
    }

    /** Returns the full question list as the payload of a SUCCESS response. */
    private void handleGetAll(ConnectionToClient client) {
        List<Question> all = questionDAO.getAll();
        log("  -> GET_ALL_QUESTIONS: returning " + all.size() + " questions");
        safeSend(client, new Message(Command.SUCCESS, (java.io.Serializable) all));
    }

    /**
     * Persists an updated question. On success, replies with the refreshed full
     * list so the client can re-render directly from the server's source of truth.
     */
    private void handleUpdate(Message request, ConnectionToClient client) {
        Object payload = request.getPayload();
        if (!(payload instanceof Question)) {
            log("  -> UPDATE_QUESTION: bad payload");
            safeSend(client, new Message(Command.ERROR,
                    "UPDATE_QUESTION requires a Question payload."));
            return;
        }

        Question q = (Question) payload;
        boolean ok = questionDAO.update(q);
        log("  -> UPDATE_QUESTION id=" + q.getId() + ": " + (ok ? "updated" : "no-op/failed"));

        if (ok) {
            // Reply with the fresh list so the client re-displays persisted state.
            List<Question> all = questionDAO.getAll();
            safeSend(client, new Message(Command.SUCCESS, (java.io.Serializable) all));
        } else {
            safeSend(client, new Message(Command.ERROR,
                    "Update failed for question id=" + q.getId()));
        }
    }

    // ===== OCSF lifecycle hooks (console logging) =========================

    @Override
    protected void serverStarted() {
        log("Server started, listening on port " + getPort());
    }

    @Override
    protected void serverStopped() {
        log("Server has stopped listening for connections.");
    }

    @Override
    protected void clientConnected(ConnectionToClient client) {
        log("Client connected: " + client);
    }

    @Override
    protected synchronized void clientDisconnected(ConnectionToClient client) {
        log("Client disconnected: " + client);
    }

    @Override
    protected synchronized void clientException(ConnectionToClient client, Throwable exception) {
        log("Client connection exception (" + client + "): " + exception.getMessage());
    }

    // ===== Helpers ========================================================

    private void safeSend(ConnectionToClient client, Message response) {
        try {
            client.sendToClient(response);
        } catch (Exception e) {
            log("  -> failed to send response to " + client + ": " + e.getMessage());
        }
    }

    private void log(String text) {
        System.out.println("[HSTSServer] " + text);
    }
}
