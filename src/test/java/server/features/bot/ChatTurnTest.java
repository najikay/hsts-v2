package server.features.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The history type the providers are handed (E16.1).
 *
 * <p>Small, and worth its own test for one reason: {@link ChatTurn#role()} is the
 * only place this project translates its own vocabulary into the vendors'. Both
 * adapters depend on it answering exactly {@code "user"} and {@code "assistant"},
 * and a rename there would be a silently malformed request rather than a compile
 * error.
 */
class ChatTurnTest {

    @Test
    @DisplayName("the two roles are spelled the way both provider APIs spell them")
    void roleNames() {
        assertThat(ChatTurn.user("q").role()).isEqualTo("user");
        assertThat(ChatTurn.assistant("a").role()).isEqualTo("assistant");
    }

    @Test
    @DisplayName("a turn knows who spoke")
    void speaker() {
        assertThat(ChatTurn.user("q").fromStudent()).isTrue();
        assertThat(ChatTurn.assistant("a").fromStudent()).isFalse();
    }

    @Test
    @DisplayName("null text is empty, and empty text is blank")
    void blankTurns() {
        assertThat(new ChatTurn(true, null).text()).isEmpty();
        assertThat(new ChatTurn(true, null).isBlank()).isTrue();
        assertThat(ChatTurn.user("   ").isBlank()).isTrue();
        assertThat(ChatTurn.user("q").isBlank()).isFalse();
    }

    @Test
    @DisplayName("turns compare by value, so a test can assert on a history list")
    void valueEquality() {
        assertThat(ChatTurn.user("q")).isEqualTo(ChatTurn.user("q"))
                .hasSameHashCodeAs(ChatTurn.user("q"));
        assertThat(ChatTurn.user("q")).isNotEqualTo(ChatTurn.assistant("q"));
        assertThat(ChatTurn.user("q")).isNotEqualTo(ChatTurn.user("other"));
        assertThat(ChatTurn.user("q")).isNotEqualTo("q");
        assertThat(ChatTurn.user("q").toString()).contains("q");
    }

    @Test
    @DisplayName("a turn carries no timestamp, because a prompt has no use for one")
    void carriesNothingAPromptDoesNotNeed() {
        assertThat(Arrays.stream(ChatTurn.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("fromStudent", "text");
    }
}
