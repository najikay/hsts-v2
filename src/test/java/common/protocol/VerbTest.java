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
    @DisplayName("the seven frozen grading verbs exist, spelled exactly as the contract spells them")
    void gradingVerbsExist() {
        // docs/contracts/GRADING_WIRE_CONTRACT.md is frozen, and a verb is serialized by
        // name, so a rename here is a protocol break between two shipped JARs. valueOf is
        // the spelling check: referring to the constant would compile after a rename.
        assertThat(Verb.values()).contains(
                Verb.GRADING_QUEUE_GET, Verb.GRADING_EXECUTION_GET, Verb.GRADE_REVIEW_GET,
                Verb.GRADE_OVERRIDE, Verb.GRADES_APPROVE,
                Verb.MY_GRADES_GET, Verb.CHECKED_FORM_GET);

        assertThat(Verb.valueOf("GRADING_QUEUE_GET")).isEqualTo(Verb.GRADING_QUEUE_GET);
        assertThat(Verb.valueOf("GRADING_EXECUTION_GET")).isEqualTo(Verb.GRADING_EXECUTION_GET);
        assertThat(Verb.valueOf("GRADE_REVIEW_GET")).isEqualTo(Verb.GRADE_REVIEW_GET);
        assertThat(Verb.valueOf("GRADE_OVERRIDE")).isEqualTo(Verb.GRADE_OVERRIDE);
        assertThat(Verb.valueOf("GRADES_APPROVE")).isEqualTo(Verb.GRADES_APPROVE);
        assertThat(Verb.valueOf("MY_GRADES_GET")).isEqualTo(Verb.MY_GRADES_GET);
        assertThat(Verb.valueOf("CHECKED_FORM_GET")).isEqualTo(Verb.CHECKED_FORM_GET);
    }

    @Test
    @DisplayName("exactly six push verbs are defined (adding one is a deliberate act)")
    void pushVerbCount() {
        assertThat(java.util.Arrays.stream(Verb.values()).filter(Verb::isPush).count()).isEqualTo(6);
    }
}
