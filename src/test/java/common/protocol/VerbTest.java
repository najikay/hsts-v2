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
    @DisplayName("the five approval verbs exist, spelled as the draft contract spells them")
    void approvalVerbsExist() {
        // docs/contracts/APPROVAL_WIRE_CONTRACT.md. Same reasoning as the two checks below:
        // a verb travels by name between two separately-shipped JARs, so valueOf is the
        // spelling assertion — referring to the constant would survive a rename.
        assertThat(Verb.values()).contains(
                Verb.APPROVALS_QUEUE_GET, Verb.EXAM_PREVIEW_GET,
                Verb.EXAM_APPROVE, Verb.EXAM_REJECT, Verb.MY_APPROVALS_GET);

        assertThat(Verb.valueOf("APPROVALS_QUEUE_GET")).isEqualTo(Verb.APPROVALS_QUEUE_GET);
        assertThat(Verb.valueOf("EXAM_PREVIEW_GET")).isEqualTo(Verb.EXAM_PREVIEW_GET);
        assertThat(Verb.valueOf("EXAM_APPROVE")).isEqualTo(Verb.EXAM_APPROVE);
        assertThat(Verb.valueOf("EXAM_REJECT")).isEqualTo(Verb.EXAM_REJECT);
        assertThat(Verb.valueOf("MY_APPROVALS_GET")).isEqualTo(Verb.MY_APPROVALS_GET);
    }

    @Test
    @DisplayName("the seven take-exam and monitoring verbs exist, spelled as the contract spells them")
    void examVerbsExist() {
        // docs/contracts/EXAM_WIRE_CONTRACT.md. Same reasoning as the grading check above:
        // a verb travels by name between two separately-shipped JARs, so valueOf is the
        // spelling assertion — referring to the constant would survive a rename.
        assertThat(Verb.values()).contains(
                Verb.EXAM_JOIN, Verb.ATTEMPT_START, Verb.ATTEMPT_RESUME,
                Verb.ANSWER_SAVE, Verb.ATTEMPT_SUBMIT,
                Verb.EXECUTION_EXTEND, Verb.EXECUTION_MONITOR_GET);

        assertThat(Verb.valueOf("EXAM_JOIN")).isEqualTo(Verb.EXAM_JOIN);
        assertThat(Verb.valueOf("ATTEMPT_START")).isEqualTo(Verb.ATTEMPT_START);
        assertThat(Verb.valueOf("ATTEMPT_RESUME")).isEqualTo(Verb.ATTEMPT_RESUME);
        assertThat(Verb.valueOf("ANSWER_SAVE")).isEqualTo(Verb.ANSWER_SAVE);
        assertThat(Verb.valueOf("ATTEMPT_SUBMIT")).isEqualTo(Verb.ATTEMPT_SUBMIT);
        assertThat(Verb.valueOf("EXECUTION_EXTEND")).isEqualTo(Verb.EXECUTION_EXTEND);
        assertThat(Verb.valueOf("EXECUTION_MONITOR_GET")).isEqualTo(Verb.EXECUTION_MONITOR_GET);
        assertThat(Verb.valueOf("PUSH_MONITOR_UPDATED")).isEqualTo(Verb.PUSH_MONITOR_UPDATED);
    }

    @Test
    @DisplayName("ATTEMPT_ATTENTION exists, spelled as the contract's additive amendment spells it")
    void attentionVerbExists() {
        // E11.7 / F7.1b, added under the freeze's additive-only rule. Same valueOf spelling
        // check as the seven above: it travels by name between two shipped JARs.
        assertThat(Verb.valueOf("ATTEMPT_ATTENTION")).isEqualTo(Verb.ATTEMPT_ATTENTION);
        assertThat(Verb.ATTEMPT_ATTENTION.isPush())
                .as("it is a request the student's client sends, not a push")
                .isFalse();
    }

    @Test
    @DisplayName("exactly seven push verbs are defined (adding one is a deliberate act)")
    void pushVerbCount() {
        assertThat(java.util.Arrays.stream(Verb.values()).filter(Verb::isPush).count()).isEqualTo(7);
    }
}
