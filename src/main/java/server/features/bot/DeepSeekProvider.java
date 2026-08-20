package server.features.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * DeepSeek, over its OpenAI-compatible REST API (Logic tier, E16.2 — F12.6,
 * ADR-009).
 *
 * <p>The chain's primary provider, and the cheap one. It speaks
 * {@code POST /chat/completions} with a {@code messages} array, which is the same
 * shape a dozen other vendors expose — so pointing this adapter at a different
 * OpenAI-compatible endpoint is a URL in {@code server.properties} rather than a
 * new class. That is the NFR-19 "what if" answer for this feature.
 *
 * <h2>Why {@code java.net.http} and not the vendor's SDK</h2>
 *
 * <p>There is no official DeepSeek Java SDK worth shipping, the request is a small
 * JSON document, and the JDK client handles timeouts and redirects properly. The
 * alternative — a third OkHttp on the classpath — buys nothing and adds weight to
 * a server JAR that already carries Hibernate, POI and PDFBox.
 *
 * <h2>Retry discipline</h2>
 *
 * <p>Exactly one retry, and only for the two failures that are worth retrying: a
 * timeout and a 5xx ({@link BotProviderException.Kind#isWorthOneRetry()}). A 429
 * is not retried, because retrying a throttle immediately is how a throttle
 * becomes a ban; a 401 is not retried, because a wrong key stays wrong. Beyond
 * one retry the chain's job starts: the next provider is a better use of the
 * student's next second than a third attempt at this one.
 *
 * <h2>Testing</h2>
 *
 * <p>Every test of this class runs against a {@code com.sun.net.httpserver}
 * instance on loopback, so the build makes no network call and still exercises
 * the real {@code java.net.http} path, the real JSON, the real timeout and the
 * real retry. Live keys are E16.17, a manual pre-demo checklist, not a test.
 */
public final class DeepSeekProvider implements BotProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekProvider.class);

    /** The name stored in {@code bot_messages.provider} and printed in the log. */
    public static final String NAME = "deepseek";

    /** The OpenAI-compatible completion path, appended to the configured base URL. */
    public static final String PATH = "/chat/completions";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String apiKey;
    private final URI endpoint;
    private final String model;
    private final Duration timeout;

    /**
     * Production constructor: everything from {@link BotConfig}.
     *
     * @param config the resolved bot settings
     */
    public DeepSeekProvider(BotConfig config) {
        this(defaultClient(Objects.requireNonNull(config, "config").requestTimeout()),
                config.deepSeekKey(), config.deepSeekBaseUrl(), config.deepSeekModel(),
                config.requestTimeout());
    }

    /**
     * Full constructor — the seam the tests use to point at a loopback stub.
     *
     * @param http    the HTTP client; injected so a test can shorten its connect timeout
     * @param apiKey  the key; may be empty, in which case this provider is unconfigured
     * @param baseUrl the base URL, without {@link #PATH}
     * @param model   the model id
     * @param timeout the per-request timeout
     */
    public DeepSeekProvider(HttpClient http, String apiKey, String baseUrl, String model, Duration timeout) {
        this.http = Objects.requireNonNull(http, "http");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = Objects.requireNonNull(model, "model");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.endpoint = URI.create(Objects.requireNonNull(baseUrl, "baseUrl") + PATH);
    }

    private static HttpClient defaultClient(Duration timeout) {
        return HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    @Override
    public String ask(String systemPrompt, List<String> contextBlocks,
                      List<ChatTurn> history, String question) throws BotProviderException {
        if (!isConfigured()) {
            throw new BotProviderException(NAME, BotProviderException.Kind.AUTH,
                    "No DeepSeek API key is configured.");
        }
        String body = requestBody(systemPrompt, contextBlocks, history, question);

        BotProviderException first;
        try {
            return send(body);
        } catch (BotProviderException e) {
            if (!e.kind().isWorthOneRetry()) {
                throw e;
            }
            first = e;
        }
        // One retry, and one only. A provider that fails twice in a row is a
        // provider the chain should stop asking for a while, not one to keep at.
        log.info("DeepSeek {} on first attempt; retrying once", first.kind());
        try {
            return send(body);
        } catch (BotProviderException retryFailure) {
            retryFailure.addSuppressed(first);
            throw retryFailure;
        }
    }

    /** One attempt: build, send, classify, parse. */
    private String send(String body) throws BotProviderException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // The one place the key is used. It is never logged and never
                // put in an exception message; a 401 says "rejected the key",
                // not which key was rejected.
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new BotProviderException(NAME, BotProviderException.Kind.TIMEOUT,
                    "DeepSeek did not answer within " + timeout.toSeconds() + "s.", e);
        } catch (IOException e) {
            // A refused connection or a dropped socket is the provider being down
            // as far as this chain is concerned, and it is worth one retry for the
            // same reason a 502 is.
            throw new BotProviderException(NAME, BotProviderException.Kind.SERVER,
                    "DeepSeek could not be reached: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BotProviderException(NAME, BotProviderException.Kind.TIMEOUT,
                    "The DeepSeek call was interrupted.", e);
        }
        return parse(response);
    }

    /** Maps a status code to the taxonomy, then reads the answer out of the body. */
    private String parse(HttpResponse<String> response) throws BotProviderException {
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new BotProviderException(NAME, BotProviderException.Kind.AUTH,
                    "DeepSeek rejected the API key (HTTP " + status + ").");
        }
        if (status == 429) {
            throw new BotProviderException(NAME, BotProviderException.Kind.RATE_LIMITED,
                    "DeepSeek is rate limiting this key (HTTP 429).");
        }
        if (status >= 500) {
            throw new BotProviderException(NAME, BotProviderException.Kind.SERVER,
                    "DeepSeek failed with HTTP " + status + ".");
        }
        if (status != 200) {
            // 400 and friends: our request was wrong, which is a bug on this side.
            // It is MALFORMED rather than SERVER so it is never retried.
            throw new BotProviderException(NAME, BotProviderException.Kind.MALFORMED,
                    "DeepSeek answered HTTP " + status + ".");
        }
        String text = extractAnswer(response.body());
        if (text.isBlank()) {
            throw new BotProviderException(NAME, BotProviderException.Kind.MALFORMED,
                    "DeepSeek answered with no content.");
        }
        return text;
    }

    /**
     * Digs {@code choices[0].message.content} out of the response.
     *
     * <p>Every step is defensive because this is somebody else's JSON: a missing
     * field, a null, an empty array and a body that is not JSON at all all end up
     * as one {@link BotProviderException.Kind#MALFORMED}, which the chain treats
     * as "this provider is not answering" and moves on.
     */
    private String extractAnswer(String body) throws BotProviderException {
        try {
            JsonNode root = JSON.readTree(body == null ? "" : body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new BotProviderException(NAME, BotProviderException.Kind.MALFORMED,
                        "DeepSeek answered without any choices.");
            }
            return choices.get(0).path("message").path("content").asText("").trim();
        } catch (BotProviderException e) {
            throw e;
        } catch (RuntimeException | com.fasterxml.jackson.core.JacksonException e) {
            throw new BotProviderException(NAME, BotProviderException.Kind.MALFORMED,
                    "DeepSeek answered something this adapter could not read.", e);
        }
    }

    /**
     * Builds the request body.
     *
     * <p>The message order is the guardrail structure of E16.7 expressed in this
     * vendor's format: the system prompt first as a {@code system} message, then
     * the fenced course material as a second {@code system} message, then the
     * conversation, then the question. Course material is never appended to the
     * instruction string — it is its own message, so no uploaded document can
     * occupy the instruction slot however hostile its contents.
     *
     * <p>Built with Jackson rather than string concatenation. A teacher's PDF can
     * contain quotes, backslashes and control characters, and hand-built JSON is
     * how those become a parse error at the vendor or, worse, a structure change.
     */
    String requestBody(String systemPrompt, List<String> contextBlocks,
                       List<ChatTurn> history, String question) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", model);
        // Not streaming: our own wire has no token channel (the client reveals the
        // whole answer with an entrance animation instead), so streaming here would
        // add reassembly for no visible gain.
        root.put("stream", false);

        ArrayNode messages = root.putArray("messages");
        addMessage(messages, "system", systemPrompt);
        String context = Guardrails.renderContext(contextBlocks == null ? List.of() : contextBlocks);
        if (!context.isBlank()) {
            addMessage(messages, "system", context);
        }
        if (history != null) {
            for (ChatTurn turn : history) {
                if (!turn.isBlank()) {
                    addMessage(messages, turn.role(), turn.text());
                }
            }
        }
        addMessage(messages, "user", question == null ? "" : question);
        return root.toString();
    }

    private static void addMessage(ArrayNode messages, String role, String content) {
        ObjectNode message = messages.addObject();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
    }
}
