package server.features.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider settings, and the precedence rule that keeps keys out of git
 * (E16.1 — F12.8 ⚑).
 */
class BotConfigTest {

    private static Properties props(String... pairs) {
        Properties properties = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            properties.setProperty(pairs[i], pairs[i + 1]);
        }
        return properties;
    }

    @Nested
    @DisplayName("where a key comes from")
    class KeyResolution {

        @Test
        @DisplayName("the file supplies the keys when the environment does not")
        void fromFile() {
            BotConfig config = BotConfig.from(
                    props(BotConfig.KEY_DEEPSEEK, "ds-file", BotConfig.KEY_ANTHROPIC, "an-file"),
                    Map.of());

            assertThat(config.deepSeekKey()).isEqualTo("ds-file");
            assertThat(config.anthropicKey()).isEqualTo("an-file");
            assertThat(config.hasAnyKey()).isTrue();
        }

        @Test
        @DisplayName("the environment wins, which is how a demo machine gets a key without a file")
        void environmentWins() {
            BotConfig config = BotConfig.from(
                    props(BotConfig.KEY_DEEPSEEK, "ds-file", BotConfig.KEY_ANTHROPIC, "an-file"),
                    Map.of(BotConfig.ENV_DEEPSEEK, "ds-env",
                            BotConfig.ENV_ANTHROPIC, "an-env"));

            assertThat(config.deepSeekKey()).isEqualTo("ds-env");
            assertThat(config.anthropicKey()).isEqualTo("an-env");
        }

        @Test
        @DisplayName("a blank environment variable does not blank out a real key in the file")
        void blankEnvironmentIsNotAnOverride() {
            BotConfig config = BotConfig.from(
                    props(BotConfig.KEY_DEEPSEEK, "ds-file"),
                    Map.of(BotConfig.ENV_DEEPSEEK, "   "));

            assertThat(config.deepSeekKey()).isEqualTo("ds-file");
        }

        @Test
        @DisplayName("keys are trimmed, because a pasted key usually has a newline on it")
        void keysAreTrimmed() {
            BotConfig config = BotConfig.from(props(BotConfig.KEY_DEEPSEEK, "  ds  "), Map.of());

            assertThat(config.deepSeekKey()).isEqualTo("ds");
        }

        @Test
        @DisplayName("no key at all is a supported state, per provider")
        void missingKeysAreSupported() {
            BotConfig config = BotConfig.from(props(BotConfig.KEY_DEEPSEEK, "ds"), Map.of());

            assertThat(config.hasDeepSeekKey()).isTrue();
            assertThat(config.hasAnthropicKey()).isFalse();
            assertThat(config.hasAnyKey()).isTrue();
        }

        @Test
        @DisplayName("a configuration with nothing in it still builds and reports itself empty")
        void unconfigured() {
            BotConfig config = BotConfig.unconfigured();

            assertThat(config.hasAnyKey()).isFalse();
            assertThat(config.deepSeekBaseUrl()).isEqualTo(BotConfig.DEFAULT_DEEPSEEK_URL);
            assertThat(config.anthropicModel()).isEqualTo(BotConfig.DEFAULT_ANTHROPIC_MODEL);
        }

        @Test
        @DisplayName("null sources are treated as empty rather than thrown at")
        void nullSources() {
            BotConfig config = BotConfig.from(null, null);

            assertThat(config.hasAnyKey()).isFalse();
        }
    }

    @Nested
    @DisplayName("everything that is not a key")
    class OtherSettings {

        @Test
        @DisplayName("the defaults are the ones the PRD names")
        void defaults() {
            BotConfig config = BotConfig.from(new Properties(), Map.of());

            assertThat(config.deepSeekBaseUrl()).isEqualTo("https://api.deepseek.com");
            assertThat(config.deepSeekModel()).isEqualTo("deepseek-chat");
            assertThat(config.anthropicModel()).isEqualTo("claude-opus-5");
            assertThat(config.requestTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(config.asksPerMinute()).isEqualTo(10);
        }

        @Test
        @DisplayName("every default can be overridden from the file")
        void overrides() {
            BotConfig config = BotConfig.from(props(
                    BotConfig.KEY_DEEPSEEK_URL, "http://localhost:9999",
                    BotConfig.KEY_DEEPSEEK_MODEL, "deepseek-reasoner",
                    BotConfig.KEY_ANTHROPIC_MODEL, "claude-sonnet-5",
                    BotConfig.KEY_TIMEOUT_SECONDS, "5",
                    BotConfig.KEY_ASKS_PER_MINUTE, "3"), Map.of());

            assertThat(config.deepSeekBaseUrl()).isEqualTo("http://localhost:9999");
            assertThat(config.deepSeekModel()).isEqualTo("deepseek-reasoner");
            assertThat(config.anthropicModel()).isEqualTo("claude-sonnet-5");
            assertThat(config.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(config.asksPerMinute()).isEqualTo(3);
        }

        @Test
        @DisplayName("a trailing slash on the base URL cannot produce a double slash in the path")
        void trailingSlashIsRemoved() {
            BotConfig config = BotConfig.from(
                    props(BotConfig.KEY_DEEPSEEK_URL, "http://localhost:9999//"), Map.of());

            assertThat(config.deepSeekBaseUrl()).isEqualTo("http://localhost:9999");
        }

        @Test
        @DisplayName("an unreadable number falls back to the default rather than failing the boot")
        void unreadableNumbersFallBack() {
            BotConfig config = BotConfig.from(props(
                    BotConfig.KEY_TIMEOUT_SECONDS, "twenty",
                    BotConfig.KEY_ASKS_PER_MINUTE, "-4"), Map.of());

            assertThat(config.requestTimeout()).isEqualTo(BotConfig.DEFAULT_TIMEOUT);
            assertThat(config.asksPerMinute()).isEqualTo(BotConfig.DEFAULT_ASKS_PER_MINUTE);
        }
    }

    @Test
    @DisplayName("toString says whether a key is set and never what it is")
    void toStringHidesKeys() {
        BotConfig config = BotConfig.from(
                props(BotConfig.KEY_DEEPSEEK, "sk-super-secret-deepseek",
                        BotConfig.KEY_ANTHROPIC, "sk-ant-super-secret"), Map.of());

        String text = config.toString();

        assertThat(text).contains("deepseek=set").contains("anthropic=set");
        assertThat(text).doesNotContain("super-secret");
    }

    @Test
    @DisplayName("the boot summary runs in every configuration, including none at all")
    void summaryNeverThrows() {
        BotConfig.from(props(BotConfig.KEY_DEEPSEEK, "ds"), Map.of()).logSummary();
        BotConfig.from(props(BotConfig.KEY_ANTHROPIC, "an"), Map.of()).logSummary();
        BotConfig.unconfigured().logSummary();
    }

    @Test
    @DisplayName("loading from the real sources produces a usable configuration")
    void loadsFromTheRealSources() {
        // The repository's own server.properties has blank keys, so this asserts the
        // shape rather than a value: load() resolves, defaults apply, nothing throws.
        BotConfig config = BotConfig.load();

        assertThat(config.deepSeekBaseUrl()).isNotBlank();
        assertThat(config.asksPerMinute()).isPositive();
    }
}
