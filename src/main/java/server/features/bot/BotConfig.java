package server.features.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.ServerConfig;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;

/**
 * Where the bot's provider settings come from (Logic tier, E16.1 — F12.6/F12.8 ⚑).
 *
 * <p>Two sources, and the order between them is the point:
 *
 * <ol>
 *   <li><b>environment variables</b> {@code HSTS_DEEPSEEK_KEY} and
 *       {@code HSTS_ANTHROPIC_KEY} win, because that is how a key gets onto a
 *       demo machine or a CI runner without ever touching a file somebody might
 *       commit;</li>
 *   <li><b>{@code server.properties}</b> keys {@code bot.deepseek.key} and
 *       {@code bot.anthropic.key} otherwise. That file is gitignored and ships as
 *       {@code server.properties.example} with the keys present and blank, so the
 *       shape is discoverable and the secret is not.</li>
 * </ol>
 *
 * <p><b>Keys live on the server and nowhere else.</b> No key is ever put in a
 * DTO, a log line, an exception message or a client JAR — the client shade
 * config is an allow-list (ADR-017), so this class cannot even be packaged into
 * it. {@link #toString()} below prints whether each key is <em>present</em> and
 * never what it is, because this record travels through boot logging.
 *
 * <p>A missing key is a supported state, not a crash: that provider reports
 * itself unconfigured, {@link #logSummary()} says so once at boot, and the chain
 * skips it. A server with neither key still runs, still serves every other
 * feature, and answers every question with the S-32 sentence — which is a
 * demonstrably better failure than refusing to start.
 *
 * @param deepSeekKey     the DeepSeek API key, or empty when unset
 * @param deepSeekBaseUrl the DeepSeek base URL, configurable so tests can point at
 *                        a local stub and so a proxy is a config change
 * @param deepSeekModel   the DeepSeek model id
 * @param anthropicKey    the Anthropic API key, or empty when unset
 * @param anthropicModel  the Anthropic model id
 * @param requestTimeout  how long one provider call may take before it is a
 *                        {@link BotProviderException.Kind#TIMEOUT}
 * @param asksPerMinute   the per-student rate limit (E16.8)
 */
public record BotConfig(String deepSeekKey,
                        String deepSeekBaseUrl,
                        String deepSeekModel,
                        String anthropicKey,
                        String anthropicModel,
                        Duration requestTimeout,
                        int asksPerMinute) {

    private static final Logger log = LoggerFactory.getLogger(BotConfig.class);

    /** Property key for the DeepSeek API key. */
    public static final String KEY_DEEPSEEK = "bot.deepseek.key";

    /** Property key for the Anthropic API key. */
    public static final String KEY_ANTHROPIC = "bot.anthropic.key";

    /** Property key for the DeepSeek base URL. */
    public static final String KEY_DEEPSEEK_URL = "bot.deepseek.url";

    /** Property key for the DeepSeek model id. */
    public static final String KEY_DEEPSEEK_MODEL = "bot.deepseek.model";

    /** Property key for the Anthropic model id. */
    public static final String KEY_ANTHROPIC_MODEL = "bot.anthropic.model";

    /** Property key for the per-provider request timeout, in seconds. */
    public static final String KEY_TIMEOUT_SECONDS = "bot.timeout.seconds";

    /** Property key for the per-student rate limit, in asks per minute. */
    public static final String KEY_ASKS_PER_MINUTE = "bot.rate.per.minute";

    /** Environment variable that overrides {@link #KEY_DEEPSEEK}. */
    public static final String ENV_DEEPSEEK = "HSTS_DEEPSEEK_KEY";

    /** Environment variable that overrides {@link #KEY_ANTHROPIC}. */
    public static final String ENV_ANTHROPIC = "HSTS_ANTHROPIC_KEY";

    /** DeepSeek's public endpoint (OpenAI-compatible). */
    public static final String DEFAULT_DEEPSEEK_URL = "https://api.deepseek.com";

    /** The DeepSeek model the PRD names (F12.6). */
    public static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-chat";

    /** The Anthropic model the PRD names, configurable per ADR-009. */
    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-opus-5";

    /** Long enough for a considered answer, short enough that a student waits rather than wonders. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    /** Ten questions a minute is a fast reader; anything above it is a script (E16.8). */
    public static final int DEFAULT_ASKS_PER_MINUTE = 10;

    public BotConfig {
        deepSeekKey = trimOrEmpty(deepSeekKey);
        anthropicKey = trimOrEmpty(anthropicKey);
        deepSeekBaseUrl = blankTo(deepSeekBaseUrl, DEFAULT_DEEPSEEK_URL);
        deepSeekModel = blankTo(deepSeekModel, DEFAULT_DEEPSEEK_MODEL);
        anthropicModel = blankTo(anthropicModel, DEFAULT_ANTHROPIC_MODEL);
        requestTimeout = requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                ? DEFAULT_TIMEOUT : requestTimeout;
        asksPerMinute = asksPerMinute <= 0 ? DEFAULT_ASKS_PER_MINUTE : asksPerMinute;
        // A base URL with a trailing slash and a path that starts with one produce
        // "//chat/completions", which some gateways answer with a 404 that looks
        // like a wrong endpoint rather than a wrong string.
        while (deepSeekBaseUrl.endsWith("/")) {
            deepSeekBaseUrl = deepSeekBaseUrl.substring(0, deepSeekBaseUrl.length() - 1);
        }
    }

    /** Reads the real {@code server.properties} and the real environment. */
    public static BotConfig load() {
        return from(ServerConfig.loadProperties(), System.getenv());
    }

    /**
     * Resolution core with both sources injected — the seam every test uses, so
     * the precedence rule is provable without setting an environment variable.
     *
     * @param props the properties file's contents; {@code null} is treated as empty
     * @param env   the environment; {@code null} is treated as empty
     */
    public static BotConfig from(Properties props, Map<String, String> env) {
        Properties p = props == null ? new Properties() : props;
        Map<String, String> e = env == null ? Map.of() : env;
        return new BotConfig(
                pick(e.get(ENV_DEEPSEEK), p.getProperty(KEY_DEEPSEEK)),
                p.getProperty(KEY_DEEPSEEK_URL),
                p.getProperty(KEY_DEEPSEEK_MODEL),
                pick(e.get(ENV_ANTHROPIC), p.getProperty(KEY_ANTHROPIC)),
                p.getProperty(KEY_ANTHROPIC_MODEL),
                seconds(p.getProperty(KEY_TIMEOUT_SECONDS)),
                positiveInt(p.getProperty(KEY_ASKS_PER_MINUTE)));
    }

    /** @return a configuration with no keys at all; every ask answers S-32. */
    public static BotConfig unconfigured() {
        return new BotConfig("", null, null, "", null, null, 0);
    }

    /** @return {@code true} when DeepSeek has a key and can be tried. */
    public boolean hasDeepSeekKey() {
        return !deepSeekKey.isEmpty();
    }

    /** @return {@code true} when Anthropic has a key and can be tried. */
    public boolean hasAnthropicKey() {
        return !anthropicKey.isEmpty();
    }

    /** @return {@code true} when at least one provider could answer anything. */
    public boolean hasAnyKey() {
        return hasDeepSeekKey() || hasAnthropicKey();
    }

    /**
     * Says once, at boot, which providers exist — the F12.8 requirement that a
     * missing key is "one clear log line" rather than a surprise at demo time.
     *
     * <p>Each line names the property and the environment variable that would fix
     * it, because a log line that only reports a problem makes somebody go and
     * look up how to solve it.
     */
    public void logSummary() {
        if (hasDeepSeekKey()) {
            log.info("Study bot: DeepSeek configured ({}, model {})", deepSeekBaseUrl, deepSeekModel);
        } else {
            log.warn("Study bot: DeepSeek has no API key and will be skipped. "
                    + "Set {} in server.properties or the {} environment variable.",
                    KEY_DEEPSEEK, ENV_DEEPSEEK);
        }
        if (hasAnthropicKey()) {
            log.info("Study bot: Anthropic configured (model {})", anthropicModel);
        } else {
            log.warn("Study bot: Anthropic has no API key and will be skipped. "
                    + "Set {} in server.properties or the {} environment variable.",
                    KEY_ANTHROPIC, ENV_ANTHROPIC);
        }
        if (!hasAnyKey()) {
            log.warn("Study bot: no provider is configured, so every question will "
                    + "answer with the no-answer message. The rest of the server is unaffected.");
        }
    }

    /** Never prints a key; this record is logged at boot. */
    @Override
    public String toString() {
        return "BotConfig{deepseek=" + (hasDeepSeekKey() ? "set" : "unset")
                + ", deepseekUrl=" + deepSeekBaseUrl
                + ", deepseekModel=" + deepSeekModel
                + ", anthropic=" + (hasAnthropicKey() ? "set" : "unset")
                + ", anthropicModel=" + anthropicModel
                + ", timeout=" + requestTimeout
                + ", asksPerMinute=" + asksPerMinute + '}';
    }

    private static String pick(String preferred, String fallback) {
        String chosen = trimOrEmpty(preferred);
        return chosen.isEmpty() ? trimOrEmpty(fallback) : chosen;
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankTo(String value, String fallback) {
        String trimmed = trimOrEmpty(value);
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    /** Unreadable numbers fall back to the default rather than failing the boot. */
    private static Duration seconds(String value) {
        int parsed = positiveInt(value);
        return parsed <= 0 ? DEFAULT_TIMEOUT : Duration.ofSeconds(parsed);
    }

    private static int positiveInt(String value) {
        String trimmed = trimOrEmpty(value);
        if (trimmed.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            log.warn("Study bot: ignoring unreadable numeric setting '{}'", trimmed);
            return 0;
        }
    }
}
