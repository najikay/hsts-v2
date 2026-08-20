package client.features.exam;

import common.dto.exam.AttemptForm;
import common.dto.exam.AttemptOutcome;
import common.dto.exam.AttemptState;
import common.dto.exam.AttemptSummaryEntry;
import common.dto.exam.AttemptTiming;
import common.dto.exam.ExamHeader;
import common.dto.exam.ExamQuestion;
import common.dto.exam.SavedAnswer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The exam form's state, with no JavaFX toolkit anywhere (E10.10/E10.16).
 *
 * <p>Everything a student sees while sitting an exam is decided here, so everything a
 * student sees while sitting an exam is tested here: what is answered, where she is, what
 * the indicator says, and the one-way door that the takeover is.
 */
class AttemptModelTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant ENDS = NOW.plus(Duration.ofMinutes(45));

    private AttemptModel model;
    private List<String> changes;

    @BeforeEach
    void setUp() {
        changes = new ArrayList<>();
        model = new AttemptModel().onChange(() -> changes.add("changed"));
    }

    @Nested
    @DisplayName("adopting a form")
    class Adopting {

        @Test
        @DisplayName("a form replaces everything: paper, answers and clock")
        void adoptsTheWholeForm() {
            model.apply(form(AttemptState.IN_PROGRESS, List.of(new SavedAnswer(1001, 3))));

            assertThat(model.attemptId()).isEqualTo(42);
            assertThat(model.questionCount()).isEqualTo(3);
            assertThat(model.answeredCount()).isEqualTo(1);
            assertThat(model.answerFor(1001)).contains(3);
            assertThat(model.header().examName()).isEqualTo("Java Midterm");
            assertThat(model.endsAt()).contains(ENDS);
            assertThat(model.totalDuration()).isEqualTo(Duration.ofMinutes(45));
            assertThat(model.isLive()).isTrue();
            assertThat(changes).isNotEmpty();
        }

        @Test
        @DisplayName("a second form replaces rather than merges (E10.6)")
        void formsReplaceRatherThanMerge() {
            model.apply(form(AttemptState.IN_PROGRESS, List.of(new SavedAnswer(1001, 3))));

            model.apply(form(AttemptState.IN_PROGRESS, List.of(new SavedAnswer(1002, 1))));

            // The server holds the truth; a reconnect adopts it rather than reconciling.
            assertThat(model.answerFor(1001)).isEmpty();
            assertThat(model.answerFor(1002)).contains(1);
            assertThat(model.answeredCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("adopting a form clears a pending indicator: the server has the truth now")
        void adoptingResetsTheIndicator() {
            model.apply(form(AttemptState.IN_PROGRESS, List.of()));
            model.select(1001, 2);
            assertThat(model.saveState()).isEqualTo(SaveState.SAVING);

            model.apply(form(AttemptState.IN_PROGRESS, List.of(new SavedAnswer(1001, 2))));

            assertThat(model.saveState()).isEqualTo(SaveState.SAVED);
        }

        @Test
        @DisplayName("a reconnect keeps her where she was, not back at question one")
        void keepsThePosition() {
            model.apply(form(AttemptState.IN_PROGRESS, List.of()));
            model.goTo(2);

            model.apply(form(AttemptState.IN_PROGRESS, List.of()));

            assertThat(model.currentIndex()).isEqualTo(2);
        }

        @Test
        @DisplayName("a shorter paper clamps the position rather than pointing past the end")
        void clampsThePosition() {
            model.apply(form(AttemptState.IN_PROGRESS, List.of()));
            model.goTo(2);

            model.apply(new AttemptForm(42, header(), List.of(question(1)), List.of(),
                    timing(), AttemptState.IN_PROGRESS, null));

            assertThat(model.currentIndex()).isZero();
        }

        @Test
        @DisplayName("a null form is rejected at the boundary")
        void nullFormRejected() {
            assertThatNullPointerException().isThrownBy(() -> model.apply(null));
        }
    }

    @Nested
    @DisplayName("answering")
    class Answering {

        @BeforeEach
        void start() {
            model.apply(form(AttemptState.IN_PROGRESS, List.of()));
        }

        @Test
        @DisplayName("a choice appears at once and the indicator says it is not stored yet")
        void selectionIsOptimisticButHonest() {
            assertThat(model.select(1001, 3)).isTrue();

            assertThat(model.answerFor(1001)).contains(3);
            assertThat(model.saveState()).isEqualTo(SaveState.SAVING);
            assertThat(model.saveState().isPending()).isTrue();
        }

        @Test
        @DisplayName("re-picking the same option changes nothing and sends nothing")
        void repickingIsANoOp() {
            model.select(1001, 3);
            model.setSaveState(SaveState.SAVED);

            assertThat(model.select(1001, 3)).isFalse();
            assertThat(model.saveState()).isEqualTo(SaveState.SAVED);
        }

        @Test
        @DisplayName("changing her mind replaces the answer")
        void changingHerMind() {
            model.select(1001, 3);

            assertThat(model.select(1001, 1)).isTrue();
            assertThat(model.answerFor(1001)).contains(1);
            assertThat(model.answeredCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the progress line counts what is answered (E10.10)")
        void progressCounts() {
            model.select(1001, 1);
            model.select(1002, 2);

            assertThat(model.answeredCount()).isEqualTo(2);
            assertThat(model.unansweredCount()).isEqualTo(1);
            assertThat(model.progressLabel()).isEqualTo("Answered 2 of 3");
            assertThat(model.progress()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("an empty paper reports zero progress rather than dividing by zero")
        void emptyPaperProgress() {
            AttemptModel empty = new AttemptModel();

            assertThat(empty.progress()).isZero();
            assertThat(empty.questionCount()).isZero();
            assertThat(empty.currentQuestion()).isEmpty();
        }
    }

    @Nested
    @DisplayName("navigating")
    class Navigating {

        @BeforeEach
        void start() {
            model.apply(form(AttemptState.IN_PROGRESS, List.of()));
        }

        @Test
        @DisplayName("jumping moves the current question")
        void jumping() {
            model.goTo(1);

            assertThat(model.currentIndex()).isEqualTo(1);
            assertThat(model.currentQuestion()).get()
                    .extracting(ExamQuestion::ordinal).isEqualTo(2);
        }

        @Test
        @DisplayName("jumping past either end clamps rather than throwing")
        void jumpingIsClamped() {
            model.goTo(99);
            assertThat(model.currentIndex()).isEqualTo(2);

            model.goTo(-5);
            assertThat(model.currentIndex()).isZero();
        }

        @Test
        @DisplayName("jumping to where she already is fires no change")
        void jumpingNowhere() {
            changes.clear();

            model.goTo(0);

            assertThat(changes).isEmpty();
        }

        @Test
        @DisplayName("jumping in an empty paper does nothing")
        void jumpingAnEmptyPaper() {
            AttemptModel empty = new AttemptModel();

            empty.goTo(3);

            assertThat(empty.currentIndex()).isZero();
        }

        @Test
        @DisplayName("the chips mark answered, blank and current (F6.9)")
        void chipsCarryTheState() {
            model.select(1002, 4);
            model.goTo(1);

            List<QuestionChip> chips = model.chips();

            assertThat(chips).hasSize(3);
            assertThat(chips.get(0).answered()).isFalse();
            assertThat(chips.get(1).answered()).isTrue();
            assertThat(chips.get(1).current()).isTrue();
            assertThat(chips.get(0).styleClass()).isEqualTo("blank");
            assertThat(chips.get(1).styleClass()).isEqualTo("answered current");
            assertThat(chips.get(1).label()).isEqualTo("2");
            assertThat(chips.get(1).tooltip()).contains("answered").contains("21002");
            assertThat(chips.get(0).tooltip()).contains("not answered");
        }
    }

    @Nested
    @DisplayName("the ending is one-way (E10.14 ⚑)")
    class Ending {

        @BeforeEach
        void start() {
            model.apply(form(AttemptState.IN_PROGRESS, List.of()));
        }

        @Test
        @DisplayName("finishing locks the paper and carries the outcome")
        void finishingLocks() {
            model.finish(outcome(AttemptState.TIMED_OUT));

            assertThat(model.isFinished()).isTrue();
            assertThat(model.isLive()).isFalse();
            assertThat(model.state()).isEqualTo(AttemptState.TIMED_OUT);
            assertThat(model.outcome()).isPresent();
        }

        @Test
        @DisplayName("a click after the takeover is refused by the model itself")
        void selectionAfterFinishIsRefused() {
            model.finish(outcome(AttemptState.TIMED_OUT));

            assertThat(model.select(1001, 2)).isFalse();
            assertThat(model.answerFor(1001)).isEmpty();
        }

        @Test
        @DisplayName("a second ending cannot reopen or overwrite the first")
        void finishingIsOneWay() {
            model.finish(outcome(AttemptState.TIMED_OUT));

            model.finish(outcome(AttemptState.SUBMITTED));

            // A save answer that was in flight when the bell went must not be able to turn
            // a timeout into a submission.
            assertThat(model.state()).isEqualTo(AttemptState.TIMED_OUT);
        }

        @Test
        @DisplayName("a form that arrives already finished is the resume-into-takeover path")
        void adoptingAFinishedForm() {
            AttemptModel fresh = new AttemptModel();

            fresh.apply(form(AttemptState.TIMED_OUT, List.of(new SavedAnswer(1001, 1))));

            assertThat(fresh.isFinished()).isTrue();
            assertThat(fresh.outcome()).isPresent();
        }

        @Test
        @DisplayName("a null outcome is rejected at the boundary")
        void nullOutcomeRejected() {
            assertThatNullPointerException().isThrownBy(() -> model.finish(null));
        }
    }

    @Nested
    @DisplayName("the clock is the server's (S-18)")
    class Clock {

        @Test
        @DisplayName("timing is re-anchored, never advanced locally")
        void syncingTiming() {
            model.apply(form(AttemptState.IN_PROGRESS, List.of()));

            model.syncTiming(new AttemptTiming(NOW.plusSeconds(600),
                    ENDS.plus(Duration.ofMinutes(15)), Duration.ofMinutes(50).toMillis(),
                    Duration.ofMinutes(60).toMillis()));

            assertThat(model.endsAt()).contains(ENDS.plus(Duration.ofMinutes(15)));
            assertThat(model.remaining()).isEqualTo(Duration.ofMinutes(50));
            assertThat(model.totalDuration()).isEqualTo(Duration.ofMinutes(60));
        }

        @Test
        @DisplayName("a null timing is ignored rather than blanking the countdown")
        void nullTimingIsIgnored() {
            model.apply(form(AttemptState.IN_PROGRESS, List.of()));

            model.syncTiming(null);

            assertThat(model.endsAt()).contains(ENDS);
        }

        @Test
        @DisplayName("before any form there is no deadline and no time")
        void beforeAnyForm() {
            assertThat(model.endsAt()).isEmpty();
            assertThat(model.remaining()).isEqualTo(Duration.ZERO);
            assertThat(model.totalDuration()).isEqualTo(Duration.ZERO);
            assertThat(model.timing()).isNull();
            assertThat(model.header()).isNull();
        }
    }

    @Nested
    @DisplayName("the indicator")
    class Indicator {

        @Test
        @DisplayName("its three states each have their own words and style")
        void threeStates() {
            assertThat(SaveState.SAVED.label()).isEqualTo(ExamCopy.SAVED_INDICATOR);
            assertThat(SaveState.SAVING.label()).isEqualTo(ExamCopy.SAVING_INDICATOR);
            assertThat(SaveState.FAILED.label()).isEqualTo(ExamCopy.SAVE_FAILED_INDICATOR);

            assertThat(SaveState.SAVED.styleClass()).isEqualTo("saved");
            assertThat(SaveState.SAVING.styleClass()).isEqualTo("saving");
            assertThat(SaveState.FAILED.styleClass()).isEqualTo("unsaved");

            assertThat(SaveState.SAVED.isPending()).isFalse();
            assertThat(SaveState.FAILED.isPending()).isTrue();
        }

        @Test
        @DisplayName("setting the same state fires no change")
        void settingTheSameState() {
            model.apply(form(AttemptState.IN_PROGRESS, List.of()));
            changes.clear();

            model.setSaveState(SaveState.SAVED);

            assertThat(changes).isEmpty();
        }

        @Test
        @DisplayName("a null state is rejected at the boundary")
        void nullStateRejected() {
            assertThatNullPointerException().isThrownBy(() -> model.setSaveState(null));
        }
    }

    @Test
    @DisplayName("clearing leaves nothing of the previous attempt behind")
    void clearing() {
        model.apply(form(AttemptState.IN_PROGRESS, List.of(new SavedAnswer(1001, 2))));

        model.clear();

        assertThat(model.attemptId()).isZero();
        assertThat(model.questions()).isEmpty();
        assertThat(model.answeredCount()).isZero();
        assertThat(model.header()).isNull();
        assertThat(model.outcome()).isEmpty();
        assertThat(model.state()).isEqualTo(AttemptState.NOT_STARTED);
        assertThat(model.chips()).isEmpty();
    }

    @Test
    @DisplayName("a null change listener is rejected at the boundary")
    void nullListenerRejected() {
        assertThatNullPointerException().isThrownBy(() -> model.onChange(null));
    }

    // ===================== Fixture =======================================

    private static AttemptForm form(AttemptState state, List<SavedAnswer> answers) {
        return new AttemptForm(42, header(),
                List.of(question(1), question(2), question(3)), answers, timing(), state,
                state.isFinished() ? outcome(state) : null);
    }

    private static ExamHeader header() {
        return new ExamHeader(5001, "Java Midterm", "21", "Java Programming", 45,
                "Answer every question.", 3, AttemptState.IN_PROGRESS);
    }

    private static ExamQuestion question(int ordinal) {
        return new ExamQuestion(1000 + ordinal, "2100" + ordinal, ordinal, 10,
                "Question " + ordinal, "a", "b", "c", "d", null);
    }

    private static AttemptTiming timing() {
        return AttemptTiming.between(NOW, NOW, ENDS);
    }

    private static AttemptOutcome outcome(AttemptState state) {
        return new AttemptOutcome(42, state, "Java Midterm", ENDS, 45, 1, 3,
                List.of(new AttemptSummaryEntry(1, "21001", true),
                        new AttemptSummaryEntry(2, "21002", false),
                        new AttemptSummaryEntry(3, "21003", false)));
    }
}
