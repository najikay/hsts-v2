package client.ui.components.logic;

import client.ui.components.logic.ToastSpec.Variant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ToastQueue} and {@link ToastSpec} — the toast stack's decision layer (E4.14). */
class ToastQueueTest {

    private ToastQueue queue;
    private List<ToastSpec> shown;
    private List<ToastSpec> hidden;

    @BeforeEach
    void setUp() {
        queue = new ToastQueue(2);
        shown = new ArrayList<>();
        hidden = new ArrayList<>();
        queue.onShow(shown::add);
        queue.onHide(hidden::add);
    }

    @Nested
    @DisplayName("ToastSpec")
    class Specs {

        @Test
        void factoriesSetTheRightVariant() {
            assertThat(ToastSpec.success("t").variant()).isEqualTo(Variant.SUCCESS);
            assertThat(ToastSpec.error("t").variant()).isEqualTo(Variant.ERROR);
            assertThat(ToastSpec.info("t").variant()).isEqualTo(Variant.INFO);
            assertThat(ToastSpec.warn("t", "m").variant()).isEqualTo(Variant.WARN);
        }

        @Test
        void errorsLingerLongerThanSuccesses() {
            assertThat(ToastSpec.error("t").dwell()).isGreaterThan(ToastSpec.success("t").dwell());
            assertThat(ToastSpec.success("t").dwell()).isEqualTo(ToastSpec.DEFAULT_DWELL);
            assertThat(ToastSpec.error("t").dwell()).isEqualTo(ToastSpec.ERROR_DWELL);
        }

        @Test
        void theDetailLineIsOptional() {
            assertThat(ToastSpec.success("Saved").hasMessage()).isFalse();
            assertThat(ToastSpec.success("Saved", "Question 4").hasMessage()).isTrue();
            assertThat(new ToastSpec(Variant.INFO, "t", null, Duration.ofSeconds(1)).message())
                    .isEmpty();
        }

        @Test
        void dwellCanBeOverridden() {
            assertThat(ToastSpec.info("t").withDwell(Duration.ofSeconds(9)).dwell())
                    .isEqualTo(Duration.ofSeconds(9));
        }

        @Test
        void variantsCarryTheirStyleClasses() {
            assertThat(Variant.SUCCESS.styleClass()).isEqualTo("success");
            assertThat(Variant.ERROR.styleClass()).isEqualTo("error");
            assertThat(Variant.INFO.styleClass()).isEqualTo("info");
            assertThat(Variant.WARN.styleClass()).isEqualTo("warn");
        }

        @Test
        void rejectsAToastNobodyCouldRead() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ToastSpec.success("  "))
                    .withMessageContaining("title");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ToastSpec.info("t").withDwell(Duration.ZERO));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ToastSpec.info("t").withDwell(Duration.ofSeconds(-1)));
            assertThatThrownBy(() -> new ToastSpec(null, "t", "", Duration.ofSeconds(1)))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ToastSpec(Variant.INFO, null, "", Duration.ofSeconds(1)))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ToastSpec(Variant.INFO, "t", "", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("showing and queueing")
    class Showing {

        @Test
        void showsUpToTheVisibleLimit() {
            queue.enqueue(ToastSpec.info("one"));
            queue.enqueue(ToastSpec.info("two"));

            assertThat(queue.visibleCount()).isEqualTo(2);
            assertThat(queue.waitingCount()).isZero();
            assertThat(shown).hasSize(2);
        }

        @Test
        void overflowWaitsInsteadOfCoveringTheScreen() {
            queue.enqueue(ToastSpec.info("one"));
            queue.enqueue(ToastSpec.info("two"));
            queue.enqueue(ToastSpec.info("three"));

            assertThat(queue.visibleCount()).isEqualTo(2);
            assertThat(queue.waitingCount()).isEqualTo(1);
            assertThat(shown).hasSize(2);
            assertThat(queue.waiting()).extracting(ToastSpec::title).containsExactly("three");
        }

        @Test
        void dismissingPromotesTheNextWaitingToastFifo() {
            queue.enqueue(ToastSpec.info("one"));
            queue.enqueue(ToastSpec.info("two"));
            queue.enqueue(ToastSpec.info("three"));
            queue.enqueue(ToastSpec.info("four"));

            queue.dismiss(ToastSpec.info("one"));

            assertThat(shown).extracting(ToastSpec::title)
                    .containsExactly("one", "two", "three");
            assertThat(queue.visible()).extracting(ToastSpec::title)
                    .containsExactly("two", "three");
            assertThat(queue.waitingCount()).isEqualTo(1);
        }

        @Test
        void dismissingSomethingNotOnScreenReportsFalse() {
            queue.enqueue(ToastSpec.info("one"));

            assertThat(queue.dismiss(ToastSpec.info("elsewhere"))).isFalse();
            assertThat(hidden).isEmpty();
        }

        @Test
        void dismissOldestTakesTheFrontOfTheStack() {
            queue.enqueue(ToastSpec.info("one"));
            queue.enqueue(ToastSpec.info("two"));

            assertThat(queue.dismissOldest()).isTrue();

            assertThat(hidden).extracting(ToastSpec::title).containsExactly("one");
            assertThat(queue.visible()).extracting(ToastSpec::title).containsExactly("two");
        }

        @Test
        void dismissOldestWithNothingShowingReportsFalse() {
            assertThat(queue.dismissOldest()).isFalse();
        }

        @Test
        void clearRemovesEverythingAndFiresHideForWhatWasVisible() {
            queue.enqueue(ToastSpec.info("one"));
            queue.enqueue(ToastSpec.info("two"));
            queue.enqueue(ToastSpec.info("three"));

            queue.clear();

            assertThat(queue.visibleCount()).isZero();
            assertThat(queue.waitingCount()).isZero();
            assertThat(hidden).extracting(ToastSpec::title).containsExactly("one", "two");
        }
    }

    @Nested
    @DisplayName("duplicate collapsing")
    class Duplicates {

        @Test
        void aBurstOfIdenticalToastsCollapsesToOne() {
            // Auto-save (F6.3) firing on every keystroke must not paper the corner.
            ToastSpec saved = ToastSpec.success("Answer saved");
            for (int i = 0; i < 10; i++) {
                queue.enqueue(saved);
            }

            assertThat(queue.visibleCount()).isEqualTo(1);
            assertThat(queue.waitingCount()).isZero();
            assertThat(queue.suppressedDuplicates()).isEqualTo(9);
        }

        @Test
        void enqueueReportsWhetherTheToastWasAccepted() {
            assertThat(queue.enqueue(ToastSpec.success("Saved"))).isTrue();
            assertThat(queue.enqueue(ToastSpec.success("Saved"))).isFalse();
        }

        @Test
        void aDifferentToastBetweenTwoIdenticalOnesBreaksTheRun() {
            queue.enqueue(ToastSpec.success("Saved"));
            queue.enqueue(ToastSpec.error("Failed", "network"));
            queue.enqueue(ToastSpec.success("Saved"));

            assertThat(queue.suppressedDuplicates()).isZero();
            assertThat(shown).hasSize(2);
            assertThat(queue.waitingCount()).isEqualTo(1);
        }

        @Test
        void theSameMessageAgainAfterItLeftIsNewInformation() {
            // "Could not save" a second time, after the first vanished, means the
            // save failed again — the user must see it.
            ToastSpec failure = ToastSpec.error("Could not save", "retrying");
            queue.enqueue(failure);
            queue.dismiss(failure);

            assertThat(queue.enqueue(failure)).isTrue();
            assertThat(shown).hasSize(2);
        }

        @Test
        void aDuplicateOfSomethingStillWaitingIsAlsoCollapsed() {
            queue.enqueue(ToastSpec.info("one"));
            queue.enqueue(ToastSpec.info("two"));
            ToastSpec third = ToastSpec.info("three");
            queue.enqueue(third);

            assertThat(queue.enqueue(third)).isFalse();
            assertThat(queue.waitingCount()).isEqualTo(1);
        }

        @Test
        void clearResetsTheDuplicateRun() {
            ToastSpec saved = ToastSpec.success("Saved");
            queue.enqueue(saved);
            queue.clear();

            assertThat(queue.enqueue(saved)).isTrue();
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        void defaultsToThreeVisible() {
            assertThat(new ToastQueue().maxVisible()).isEqualTo(ToastQueue.DEFAULT_MAX_VISIBLE);
            assertThat(ToastQueue.DEFAULT_MAX_VISIBLE).isEqualTo(3);
        }

        @Test
        void rejectsAQueueThatCanNeverShowAnything() {
            assertThatIllegalArgumentException().isThrownBy(() -> new ToastQueue(0));
            assertThatIllegalArgumentException().isThrownBy(() -> new ToastQueue(-1));
        }

        @Test
        void rejectsNulls() {
            assertThatThrownBy(() -> queue.enqueue(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> queue.onShow(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> queue.onHide(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void visibleAndWaitingViewsAreSnapshots() {
            queue.enqueue(ToastSpec.info("one"));
            List<ToastSpec> snapshot = queue.visible();

            queue.enqueue(ToastSpec.info("two"));

            assertThat(snapshot).hasSize(1);
        }
    }
}
