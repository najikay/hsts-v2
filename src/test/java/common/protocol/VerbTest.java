package common.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                Verb.BANK_LIST, Verb.QUESTION_UPDATE,
                Verb.PUSH_NOTIFICATION, Verb.PUSH_LOCK_CHANGED, Verb.PUSH_TIMER_EXTENDED,
                Verb.PUSH_FORCE_SUBMITTED, Verb.PUSH_EXECUTION_STATUS, Verb.PUSH_GRADE_PUBLISHED);
        assertThat(Verb.valueOf("BANK_LIST")).isEqualTo(Verb.BANK_LIST);
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
    @DisplayName("the four approval verbs exist, spelled as the contract spells them")
    void approvalVerbsExist() {
        // docs/contracts/APPROVAL_WIRE_CONTRACT.md. Same reasoning as the two checks below:
        // a verb travels by name between two separately-shipped JARs, so valueOf is the
        // spelling assertion — referring to the constant would survive a rename.
        // Four, not five, since 2026-08-25: MY_APPROVALS_GET retired into EXAM_LIST under
        // ruling 1 and is asserted GONE by myApprovalsGetIsRetired below.
        assertThat(Verb.values()).contains(
                Verb.APPROVALS_QUEUE_GET, Verb.EXAM_PREVIEW_GET,
                Verb.EXAM_APPROVE, Verb.EXAM_REJECT);

        assertThat(Verb.valueOf("APPROVALS_QUEUE_GET")).isEqualTo(Verb.APPROVALS_QUEUE_GET);
        assertThat(Verb.valueOf("EXAM_PREVIEW_GET")).isEqualTo(Verb.EXAM_PREVIEW_GET);
        assertThat(Verb.valueOf("EXAM_APPROVE")).isEqualTo(Verb.EXAM_APPROVE);
        assertThat(Verb.valueOf("EXAM_REJECT")).isEqualTo(Verb.EXAM_REJECT);
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
    @DisplayName("the seven bank verbs exist, spelled as the contract's rulings spell them")
    void bankVerbsExist() {
        // docs/contracts/BANK_WIRE_CONTRACT.md plus the lead's rulings of 2026-08-21. Same
        // reasoning as the checks above: a verb travels by name between two separately-shipped
        // JARs, so valueOf is the spelling assertion — referring to the constant would survive
        // a rename.
        assertThat(Verb.values()).contains(
                Verb.BANK_LIST, Verb.QUESTION_GET, Verb.QUESTION_VERSIONS,
                Verb.QUESTION_IMAGE_GET, Verb.QUESTION_CREATE, Verb.QUESTION_UPDATE,
                Verb.QUESTION_DELETE);

        assertThat(Verb.valueOf("BANK_LIST")).isEqualTo(Verb.BANK_LIST);
        assertThat(Verb.valueOf("QUESTION_GET")).isEqualTo(Verb.QUESTION_GET);
        assertThat(Verb.valueOf("QUESTION_VERSIONS")).isEqualTo(Verb.QUESTION_VERSIONS);
        assertThat(Verb.valueOf("QUESTION_IMAGE_GET")).isEqualTo(Verb.QUESTION_IMAGE_GET);
        assertThat(Verb.valueOf("QUESTION_CREATE")).isEqualTo(Verb.QUESTION_CREATE);
        assertThat(Verb.valueOf("QUESTION_UPDATE")).isEqualTo(Verb.QUESTION_UPDATE);
        assertThat(Verb.valueOf("QUESTION_DELETE")).isEqualTo(Verb.QUESTION_DELETE);
    }

    @Test
    @DisplayName("the image verb is noun-first, and the legacy bank verbs are untouched beside it")
    void bankVerbNamingIsTheRuledOne() {
        // Lead's ruling of 2026-08-21: the noun-first convention wins over TODO E6.6's
        // GET_QUESTION_IMAGE, which matched the two legacy verbs instead. Asserting the
        // rejected spelling is absent is what stops it being reintroduced by a handler author
        // reading the older TODO.
        assertThat(Verb.QUESTION_IMAGE_GET.name()).isEqualTo("QUESTION_IMAGE_GET");
        assertThat(Arrays.stream(Verb.values()).map(Verb::name))
                .doesNotContain("GET_QUESTION_IMAGE");

        // The verb-first spellings the convention displaced. GET_ALL_QUESTIONS and
        // UPDATE_QUESTION were the prototype pair and retired with the legacy screen; this
        // used to assert UPDATE_QUESTION was a DIFFERENT verb from E6's QUESTION_UPDATE,
        // because the two spellings were one transposition apart and both were live. Only one
        // is live now, and asserting the retired names are absent is what stops a reader of the
        // old TODO reintroducing either as a synonym for a bank verb that already exists.
        assertThat(Arrays.stream(Verb.values()).map(Verb::name))
                .doesNotContain("GET_ALL_QUESTIONS", "UPDATE_QUESTION");
        assertThat(Verb.valueOf("QUESTION_UPDATE")).isEqualTo(Verb.QUESTION_UPDATE);
    }

    @Test
    @DisplayName("no bank REQUEST verb is a push, and lock truth still has one source")
    void noBankRequestVerbIsAPush() {
        // Retitled under U-63 (BANK amendment A3), because the old title said "the bank has
        // none, by contract" and the bank now has exactly one: PUSH_BANK_CHANGED, the notice
        // that lets a colleague's new question reach a screen that is already open.
        //
        // The invariant this test actually protects is untouched and is worth separating from
        // that sentence. The live "being edited by" badges on a bank list ride E18.8's
        // PUSH_LOCK_CHANGED, not a bank push (F10.0), and PUSH_BANK_CHANGED carries no lock
        // information whatsoever: a course code, a display id and one of three words. So there
        // is still exactly one source of lock truth. A PUSH_QUESTION_* would be the second one,
        // and its absence is asserted below rather than assumed.
        assertThat(List.of(Verb.BANK_LIST, Verb.QUESTION_GET, Verb.QUESTION_VERSIONS,
                        Verb.QUESTION_IMAGE_GET, Verb.QUESTION_CREATE, Verb.QUESTION_UPDATE,
                        Verb.QUESTION_DELETE))
                .allSatisfy(verb -> assertThat(verb.isPush()).isFalse());
        assertThat(Arrays.stream(Verb.values()).map(Verb::name))
                .as("a per-question push would be a second source of lock truth beside E18.8's")
                .noneMatch(name -> name.startsWith("PUSH_QUESTION"));
        assertThat(Verb.PUSH_BANK_CHANGED.isPush()).isTrue();
    }

    @Test
    @DisplayName("the seven exam-builder verbs exist, spelled as the E7 contract spells them")
    void examBuilderVerbsExist() {
        // docs/contracts/EXAM_BUILDER_WIRE_CONTRACT.md plus the lead's rulings of 2026-08-23.
        // Same reasoning as the checks above: a verb travels by name between two
        // separately-shipped JARs, so valueOf is the spelling assertion — referring to the
        // constant would survive a rename.
        assertThat(Verb.values()).contains(
                Verb.EXAM_LIST, Verb.EXAM_VERSION_GET, Verb.EXAM_CREATE,
                Verb.EXAM_VERSION_SAVE, Verb.EXAM_VERSION_REVISE, Verb.EXAM_SUBMIT,
                Verb.EXAM_AUTO_COMPOSE);

        assertThat(Verb.valueOf("EXAM_LIST")).isEqualTo(Verb.EXAM_LIST);
        assertThat(Verb.valueOf("EXAM_VERSION_GET")).isEqualTo(Verb.EXAM_VERSION_GET);
        assertThat(Verb.valueOf("EXAM_CREATE")).isEqualTo(Verb.EXAM_CREATE);
        assertThat(Verb.valueOf("EXAM_VERSION_SAVE")).isEqualTo(Verb.EXAM_VERSION_SAVE);
        assertThat(Verb.valueOf("EXAM_VERSION_REVISE")).isEqualTo(Verb.EXAM_VERSION_REVISE);
        assertThat(Verb.valueOf("EXAM_SUBMIT")).isEqualTo(Verb.EXAM_SUBMIT);
        assertThat(Verb.valueOf("EXAM_AUTO_COMPOSE")).isEqualTo(Verb.EXAM_AUTO_COMPOSE);
    }

    @Test
    @DisplayName("no exam-builder verb is a push: the builder has none, by contract")
    void noExamBuilderVerbIsAPush() {
        // The author learns her exam was approved or rejected through E8's durable
        // notification, which already points at route id `exams`; the builder's live "being
        // edited by" state rides E18.8's LOCK_WATCH / LOCKS_SNAPSHOT under the existing
        // EntityRef.EXAM_VERSION constant. A PUSH_EXAM_* here would be a second source of
        // either truth.
        assertThat(List.of(Verb.EXAM_LIST, Verb.EXAM_VERSION_GET, Verb.EXAM_CREATE,
                        Verb.EXAM_VERSION_SAVE, Verb.EXAM_VERSION_REVISE, Verb.EXAM_SUBMIT,
                        Verb.EXAM_AUTO_COMPOSE))
                .allSatisfy(verb -> assertThat(verb.isPush()).isFalse());
    }

    @Test
    @DisplayName("MY_APPROVALS_GET has retired into EXAM_LIST, and stays retired ⚑")
    void myApprovalsGetIsRetired() {
        // Was "MY_APPROVALS_GET is still live", and the flip is the point rather than a chore.
        // E7 contract section 8 / APPROVAL ruling 1: the verb retires INTO EXAM_LIST, and the
        // removal lands in the SAME change as the screen swap, so there is never a window where
        // two overlapping reads of one fact are both live. That change is 2026-08-25's assembly,
        // so the pin now asserts the far side: the name is gone from the wire and nothing can
        // reintroduce it quietly. Asked by NAME, because referring to the constant would not
        // compile and a build that does not compile tells a reader nothing about what was
        // missing — the same reasoning ExamListWiringGuardTest gives.
        assertThat(Verb.values())
                .as("the verb is deleted, not deprecated: both jars ship from one build, so "
                        + "the never-remove-a-header rule (cross-version compat) does not "
                        + "apply — precedent #47")
                .noneMatch(verb -> verb.name().equals("MY_APPROVALS_GET"));
        assertThatThrownBy(() -> Verb.valueOf("MY_APPROVALS_GET"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Verb.valueOf("EXAM_LIST"))
                .as("it retired INTO this one, which must therefore still be here")
                .isEqualTo(Verb.EXAM_LIST);
    }

    @Test
    @DisplayName("the exam builder adds no verb the contract deliberately refused")
    void deliberatelyAbsentExamBuilderVerbs() {
        // Contract sections 3 and 9, asserted so a handler author reading an older TODO cannot
        // reintroduce one. There is no exam delete (F3 never asks, C-2 retains versions, and a
        // released exam has attempts RESTRICT would refuse anyway); there is no bank-picker verb
        // (E7.12's picker is BANK_LIST, and a second browse verb would be a second set of scope
        // rules over the same rows); and there is no release verb (E9 owns that).
        assertThat(Arrays.stream(Verb.values()).map(Verb::name))
                .doesNotContain("EXAM_DELETE", "EXAM_VERSION_DELETE", "EXAM_QUESTIONS_LIST",
                        "EXAM_BANK_LIST", "EXAM_RELEASE");
    }

    @Test
    @DisplayName("the two report verbs exist, spelled as the E15 contract spells them")
    void reportVerbsExist() {
        // docs/contracts/REPORTS_WIRE_CONTRACT.md. Same reasoning as the checks above: a verb
        // travels by name between two separately-shipped JARs, so valueOf is the spelling
        // assertion and referring to the constant would survive a rename.
        assertThat(Verb.values()).contains(Verb.REPORT_SUBJECTS_GET, Verb.REPORT_GET);
        assertThat(Verb.valueOf("REPORT_SUBJECTS_GET")).isEqualTo(Verb.REPORT_SUBJECTS_GET);
        assertThat(Verb.valueOf("REPORT_GET")).isEqualTo(Verb.REPORT_GET);
        // Neither is a push: a report compares sittings that have already closed, so there is
        // nothing about one that could move while it is on screen.
        assertThat(Verb.REPORT_SUBJECTS_GET.isPush()).isFalse();
        assertThat(Verb.REPORT_GET.isPush()).isFalse();
    }

    @Test
    @DisplayName("the two data-browser verbs exist, spelled as amendment A1 spells them")
    void dataBrowseVerbsExist() {
        // docs/contracts/REPORTS_WIRE_CONTRACT.md amendment A1 (E15.2). Same reasoning again:
        // valueOf is the spelling assertion, because the name is what travels between two
        // separately-shipped JARs.
        assertThat(Verb.values()).contains(Verb.DATA_EXAMS_GET, Verb.DATA_RESULTS_GET);
        assertThat(Verb.valueOf("DATA_EXAMS_GET")).isEqualTo(Verb.DATA_EXAMS_GET);
        assertThat(Verb.valueOf("DATA_RESULTS_GET")).isEqualTo(Verb.DATA_RESULTS_GET);
        // Neither is a push: an exam catalogue and a list of closed sittings are things a
        // principal reads, and nothing about either moves while it is on screen.
        assertThat(Verb.DATA_EXAMS_GET.isPush()).isFalse();
        assertThat(Verb.DATA_RESULTS_GET.isPush()).isFalse();
        // ⚑ The bank tab of that screen adds NO verb. She has been on BANK_LIST's role list
        // since E6 (F9.3, BANK contract section 3), and a DATA_QUESTIONS_GET beside it would be
        // a second answer to a question that already has one.
        assertThat(java.util.Arrays.stream(Verb.values()).map(Enum::name))
                .as("the data browser's third tab reuses BANK_LIST rather than duplicating it")
                .doesNotContain("DATA_QUESTIONS_GET", "DATA_BANK_GET");
    }

    @Test
    @DisplayName("exactly eight push verbs are defined (adding one is a deliberate act)")
    void pushVerbCount() {
        // Seven until 2026-08-30. The eighth is PUSH_BANK_CHANGED (U-63, BANK amendment A3),
        // and this test doing its job is the deliberate act it asks for: a push verb is a new
        // way for the server to reach a client unbidden, so one arriving without a contract
        // amendment behind it should fail a build rather than pass a review.
        assertThat(Arrays.stream(Verb.values()).filter(Verb::isPush).count()).isEqualTo(8);
    }
}
