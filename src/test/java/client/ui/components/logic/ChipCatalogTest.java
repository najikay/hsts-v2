package client.ui.components.logic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ChipCatalog}, {@link ChipSpec} and {@link AsyncViewState} (E4.15, E4.11). */
class ChipCatalogTest {

    @Nested
    @DisplayName("exam status (F3.6)")
    class ExamStatus {

        @Test
        void mapsEveryWorkflowState() {
            assertThat(ChipCatalog.forExamStatus("DRAFT"))
                    .isEqualTo(ChipSpec.of("Draft", ChipTone.NEUTRAL));
            assertThat(ChipCatalog.forExamStatus("APPROVED"))
                    .isEqualTo(ChipSpec.of("Approved", ChipTone.OK));
            assertThat(ChipCatalog.forExamStatus("REJECTED"))
                    .isEqualTo(ChipSpec.of("Rejected", ChipTone.DANGER));
        }

        @ParameterizedTest
        @ValueSource(strings = {"PENDING", "PENDING_APPROVAL"})
        void bothPendingSpellingsMapToTheSameChip(String status) {
            assertThat(ChipCatalog.forExamStatus(status))
                    .isEqualTo(ChipSpec.of("Pending approval", ChipTone.WARN));
        }

        @Test
        void lookupIsCaseAndWhitespaceTolerant() {
            assertThat(ChipCatalog.forExamStatus("  approved ").tone()).isEqualTo(ChipTone.OK);
        }
    }

    @Nested
    @DisplayName("execution status (F5.4)")
    class ExecutionStatus {

        @Test
        void liveIsTheOnlyChipWithADot() {
            ChipSpec live = ChipCatalog.forExecutionStatus("LIVE");

            assertThat(live.label()).isEqualTo("Live");
            assertThat(live.tone()).isEqualTo(ChipTone.LIVE);
            assertThat(live.dot()).isTrue();

            assertThat(ChipCatalog.forExecutionStatus("SCHEDULED").dot()).isFalse();
            assertThat(ChipCatalog.forExecutionStatus("CLOSED").dot()).isFalse();
        }

        @Test
        void scheduledClosedAndCancelledAreCalm() {
            assertThat(ChipCatalog.forExecutionStatus("SCHEDULED").tone()).isEqualTo(ChipTone.INFO);
            assertThat(ChipCatalog.forExecutionStatus("CLOSED").tone()).isEqualTo(ChipTone.NEUTRAL);
            assertThat(ChipCatalog.forExecutionStatus("CANCELLED").tone()).isEqualTo(ChipTone.NEUTRAL);
        }

        @Test
        void bothCancelledSpellingsAreAccepted() {
            assertThat(ChipCatalog.forExecutionStatus("CANCELED").label()).isEqualTo("Cancelled");
        }
    }

    @Nested
    @DisplayName("attempt and grade status")
    class OtherStatuses {

        @Test
        void attemptStatesReadAsProgress() {
            assertThat(ChipCatalog.forAttemptStatus("NOT_STARTED").tone()).isEqualTo(ChipTone.NEUTRAL);
            assertThat(ChipCatalog.forAttemptStatus("IN_PROGRESS").dot()).isTrue();
            assertThat(ChipCatalog.forAttemptStatus("SUBMITTED").tone()).isEqualTo(ChipTone.OK);
            assertThat(ChipCatalog.forAttemptStatus("TIMED_OUT").tone()).isEqualTo(ChipTone.DANGER);
        }

        @Test
        void gradeStatesFollowTheApprovalRuleC3() {
            // Auto-checked but not approved must NOT read as done — the student
            // cannot see it yet (C-3).
            assertThat(ChipCatalog.forGradeStatus("AUTO"))
                    .isEqualTo(ChipSpec.of("Awaiting approval", ChipTone.WARN));
            assertThat(ChipCatalog.forGradeStatus("APPROVED"))
                    .isEqualTo(ChipSpec.of("Published", ChipTone.OK));
            assertThat(ChipCatalog.forGradeStatus("OVERRIDDEN").tone()).isEqualTo(ChipTone.INFO);
        }

        @Test
        void difficultyRunsEasyToHard() {
            assertThat(ChipCatalog.forDifficulty("EASY").tone()).isEqualTo(ChipTone.OK);
            assertThat(ChipCatalog.forDifficulty("MEDIUM").tone()).isEqualTo(ChipTone.WARN);
            assertThat(ChipCatalog.forDifficulty("HARD").tone()).isEqualTo(ChipTone.DANGER);
        }

        @Test
        void connectionStatesAllCarryADot() {
            assertThat(ChipCatalog.forConnection("CONNECTED").tone()).isEqualTo(ChipTone.OK);
            assertThat(ChipCatalog.forConnection("RECONNECTING").tone()).isEqualTo(ChipTone.WARN);
            assertThat(ChipCatalog.forConnection("CONNECTING").label()).isEqualTo("Reconnecting");
            assertThat(ChipCatalog.forConnection("LOST").tone()).isEqualTo(ChipTone.DANGER);
            assertThat(ChipCatalog.forConnection("DISCONNECTED").dot()).isTrue();
        }
    }

    @Nested
    @DisplayName("forward compatibility (ARCHITECTURE §3)")
    class Unknown {

        @Test
        void anUnknownServerStateStaysReadable() {
            ChipSpec spec = ChipCatalog.forExamStatus("ARCHIVED_PENDING_REVIEW");

            assertThat(spec.label()).isEqualTo("Archived pending review");
            assertThat(spec.tone()).isEqualTo(ChipTone.NEUTRAL);
            assertThat(spec.dot()).isFalse();
        }

        @Test
        void everyLookupSurvivesNullAndBlank() {
            assertThat(ChipCatalog.forExamStatus(null).label()).isEqualTo("Unknown");
            assertThat(ChipCatalog.forExecutionStatus("").label()).isEqualTo("Unknown");
            assertThat(ChipCatalog.forAttemptStatus("   ").label()).isEqualTo("Unknown");
            assertThat(ChipCatalog.forGradeStatus(null).tone()).isEqualTo(ChipTone.NEUTRAL);
            assertThat(ChipCatalog.forDifficulty(null).tone()).isEqualTo(ChipTone.NEUTRAL);
            assertThat(ChipCatalog.forConnection(null).tone()).isEqualTo(ChipTone.NEUTRAL);
        }
    }

    @Nested
    @DisplayName("ChipSpec")
    class Specs {

        @Test
        void humanizesEnumConstants() {
            assertThat(ChipSpec.humanize("PENDING_APPROVAL")).isEqualTo("Pending approval");
            assertThat(ChipSpec.humanize("TIMED-OUT")).isEqualTo("Timed out");
            assertThat(ChipSpec.humanize("LIVE")).isEqualTo("Live");
            assertThat(ChipSpec.humanize("  draft ")).isEqualTo("Draft");
        }

        @Test
        void humanizingNothingStillProducesSomethingReadable() {
            assertThat(ChipSpec.humanize(null)).isEqualTo("Unknown");
            assertThat(ChipSpec.humanize("   ")).isEqualTo("Unknown");
        }

        @Test
        void withDotIsANonMutatingCopy() {
            ChipSpec plain = ChipSpec.of("Live", ChipTone.LIVE);
            ChipSpec dotted = plain.withDot();

            assertThat(plain.dot()).isFalse();
            assertThat(dotted.dot()).isTrue();
            assertThat(dotted.label()).isEqualTo(plain.label());
        }

        @Test
        void rejectsNulls() {
            assertThatThrownBy(() -> ChipSpec.of(null, ChipTone.OK))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ChipSpec.of("x", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @ParameterizedTest
        @EnumSource(ChipTone.class)
        void everyToneHasADistinctStyleClass(ChipTone tone) {
            assertThat(tone.styleClass()).isNotBlank();
            assertThat(java.util.Arrays.stream(ChipTone.values())
                    .filter(other -> other.styleClass().equals(tone.styleClass()))
                    .count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("AsyncViewState")
    class ViewStates {

        @Test
        void anEmptyResultLandsOnTheEmptyStateNotOnABlankTable() {
            assertThat(AsyncViewState.forResultSize(0)).isEqualTo(AsyncViewState.EMPTY);
            assertThat(AsyncViewState.forResultSize(-1)).isEqualTo(AsyncViewState.EMPTY);
            assertThat(AsyncViewState.forResult(java.util.List.of())).isEqualTo(AsyncViewState.EMPTY);
        }

        @Test
        void aNonEmptyResultIsReady() {
            assertThat(AsyncViewState.forResultSize(3)).isEqualTo(AsyncViewState.READY);
            assertThat(AsyncViewState.forResult(java.util.List.of("a"))).isEqualTo(AsyncViewState.READY);
        }

        @Test
        void exactlyOneNodeIsShownPerState() {
            for (AsyncViewState state : AsyncViewState.values()) {
                long shown = java.util.stream.Stream.of(
                                state.showsSkeleton(), state.showsContent(),
                                state.showsEmptyState(), state.showsError())
                        .filter(Boolean::booleanValue).count();

                assertThat(shown).as("state %s must show exactly one node", state).isEqualTo(1);
            }
        }

        @Test
        void idleAndLoadingBothShowTheSkeleton() {
            assertThat(AsyncViewState.IDLE.showsSkeleton()).isTrue();
            assertThat(AsyncViewState.LOADING.showsSkeleton()).isTrue();
            assertThat(AsyncViewState.LOADING.isBusy()).isTrue();
            assertThat(AsyncViewState.IDLE.isBusy()).isFalse();
        }

        @Test
        void rejectsANullResult() {
            assertThatThrownBy(() -> AsyncViewState.forResult(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
