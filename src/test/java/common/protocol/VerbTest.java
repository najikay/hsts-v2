package common.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link Verb} vocabulary.
 *
 * <p>Verbs are the contract between two separately-built JARs, so the things
 * worth asserting are the ones a careless refactor would silently break: the
 * push/request split that {@code PushGateway} and {@code PushEventBridge} both
 * key off, and the presence of the verbs the current epics rely on.
 */
class VerbTest {

    @ParameterizedTest
    @EnumSource(value = Verb.class, names = "PUSH_.*", mode = EnumSource.Mode.MATCH_ALL)
    @DisplayName("every PUSH_* verb reports isPush()")
    void pushVerbsAreRecognised(Verb verb) {
        assertThat(verb.isPush()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Verb.class, names = "PUSH_.*", mode = EnumSource.Mode.MATCH_NONE)
    @DisplayName("no request verb pretends to be a push")
    void requestVerbsAreNotPushes(Verb verb) {
        assertThat(verb.isPush()).isFalse();
    }

    @Test
    @DisplayName("the verbs the current epics depend on all exist")
    void expectedVerbsExist() {
        assertThat(Verb.values()).contains(
                Verb.LOGIN, Verb.LOGOUT,
                Verb.GET_ALL_QUESTIONS, Verb.UPDATE_QUESTION,
                Verb.PUSH_NOTIFICATION, Verb.PUSH_LOCK_CHANGED, Verb.PUSH_TIMER_EXTENDED,
                Verb.PUSH_FORCE_SUBMITTED, Verb.PUSH_EXECUTION_STATUS, Verb.PUSH_GRADE_PUBLISHED);
        assertThat(Verb.valueOf("GET_ALL_QUESTIONS")).isEqualTo(Verb.GET_ALL_QUESTIONS);
    }

    @Test
    @DisplayName("exactly six push verbs are defined (adding one is a deliberate act)")
    void pushVerbCount() {
        assertThat(java.util.Arrays.stream(Verb.values()).filter(Verb::isPush).count()).isEqualTo(6);
    }
}
