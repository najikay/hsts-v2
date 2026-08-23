package common.dto.authoring;

import common.dto.bank.Difficulty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The infeasibility report and the invariant that makes it impossible to send an empty one
 * (E7.4 ⚑ — F3.3, contract section 7).
 *
 * <p>This is the defense moment of the E7 wire, so it gets a suite of its own rather than a
 * nested block in {@link AuthoringDtoTest}. Two things are proved here:
 *
 * <ul>
 *   <li><b>All four quadrants</b> of {@link AutoComposeResult}. Feasible with a proposal and
 *       infeasible with a report are the two legal answers; both-empty and a {@code feasible}
 *       flag that disagrees with the lists are refused in the compact constructor, which means
 *       they are refused on <em>deserialisation</em> too. "An auto-compose that selected nothing
 *       and explained nothing" is the failure F3.3 exists to prevent, and it must not be
 *       representable — not merely undocumented.</li>
 *   <li><b>The four {@link Shortfall} shapes</b> the PRD's example sentence is rendered from.
 *       No sentence travels (lead's ruling 4): the wire is structural and {@code ExamCopy}
 *       composes the sentence once, so what is pinned here is the data behind
 *       "Topic 'Algebra': requested 5 Hard, bank has 2".</li>
 * </ul>
 *
 * <p>The thin-topic fixture is the seed's deliberate one: PRD §5 puts "Recursion" in the bank
 * with two questions and none Hard, precisely so F3.3 can be demonstrated live without anybody
 * touching the database. T-3.5 and T-3.6 are the two shots.
 */
class AutoComposeResultTest {

    @SuppressWarnings("unchecked")
    private static <T extends Serializable> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    private static ComposedQuestion question(int ord, int points) {
        return new ComposedQuestion(4000L + ord, "1100" + ord, ord, points, "שאלה " + ord,
                "רקורסיה", Difficulty.MEDIUM, false, 1, 1);
    }

    /** Section 7.4's even distribution: three questions become 34, 33, 33. */
    private static List<ComposedQuestion> proposal() {
        return List.of(question(1, 34), question(2, 33), question(3, 33));
    }

    private static final Shortfall THIN_TOPIC = new Shortfall("Recursion", Difficulty.HARD, 1, 0);

    // ===================== the four quadrants ================================

    @Nested
    @DisplayName("the invariant: exactly one list is non-empty, and feasible says which")
    class Quadrants {

        @Test
        @DisplayName("quadrant 1 — feasible with a proposal is legal, and survives the wire")
        void feasibleWithQuestions() throws Exception {
            AutoComposeResult original = AutoComposeResult.composed(proposal());

            AutoComposeResult restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.feasible()).isTrue();
            assertThat(restored.questionCount()).isEqualTo(3);
            assertThat(restored.shortfallCount()).isZero();
            assertThat(restored.shortfalls()).isEmpty();
            // Section 7.4: every proposal already satisfies section 1, so the auto path is
            // savable in one click (T-3.4).
            assertThat(restored.totalPoints()).isEqualTo(ExamCreateRequest.POINTS_TOTAL);
        }

        @Test
        @DisplayName("quadrant 2 — infeasible with a report is legal, and survives the wire")
        void infeasibleWithShortfalls() throws Exception {
            AutoComposeResult original = AutoComposeResult.infeasible(List.of(THIN_TOPIC,
                    new Shortfall("Algebra", Difficulty.HARD, 5, 2)));

            AutoComposeResult restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.feasible()).isFalse();
            assertThat(restored.questions()).isEmpty();
            assertThat(restored.questionCount()).isZero();
            assertThat(restored.totalPoints()).isZero();
            // Section 7.2 property 1: EVERY shortfall, not the first one. A teacher short on two
            // topics gets two lines rather than discovering the second by fixing the first.
            assertThat(restored.shortfallCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("quadrant 3 — BOTH EMPTY is refused: it is the failure F3.3 exists to prevent")
        void bothEmptyIsRefused() {
            assertThatThrownBy(() -> new AutoComposeResult(false, List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("selected nothing and explained nothing");

            // And with the flag the other way round, so neither spelling of "nothing" gets out.
            assertThatThrownBy(() -> new AutoComposeResult(true, List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("selected nothing and explained nothing");
        }

        @Test
        @DisplayName("quadrant 4 — a feasible flag that disagrees with the lists is refused")
        void mismatchedFlagIsRefused() {
            assertThatThrownBy(() -> new AutoComposeResult(false, proposal(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("disagrees with the lists");

            assertThatThrownBy(() ->
                    new AutoComposeResult(true, List.of(), List.of(THIN_TOPIC)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("disagrees with the lists");
        }

        @Test
        @DisplayName("both lists populated is refused: a proposal and a report are two answers")
        void bothPopulatedIsRefused() {
            assertThatThrownBy(() ->
                    new AutoComposeResult(true, proposal(), List.of(THIN_TOPIC)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("never both");
            assertThatThrownBy(() ->
                    new AutoComposeResult(false, proposal(), List.of(THIN_TOPIC)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("never both");
        }

        @Test
        @DisplayName("a null list is a server bug and surfaces as one")
        void nullListsAreRefused() {
            assertThatThrownBy(() -> new AutoComposeResult(true, null, List.of()))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("questions");
            assertThatThrownBy(() -> new AutoComposeResult(false, List.of(), null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("shortfalls");
        }

        @Test
        @DisplayName("the copies are strict and immutable, both ways")
        void listsAreCopiedStrictly() {
            List<ComposedQuestion> mutable = new ArrayList<>(proposal());
            AutoComposeResult result = AutoComposeResult.composed(mutable);

            mutable.clear();

            assertThat(result.questionCount()).isEqualTo(3);
            assertThatThrownBy(() -> result.questions().add(question(4, 1)))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> result.shortfalls().add(THIN_TOPIC))
                    .isInstanceOf(UnsupportedOperationException.class);

            List<Shortfall> withHole = new ArrayList<>();
            withHole.add(null);
            assertThatThrownBy(() -> AutoComposeResult.infeasible(withHole))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ===================== the report's four shapes ==========================

    @Nested
    @DisplayName("the shortfall, which is data because the sentence is composed on the client")
    class Report {

        @Test
        @DisplayName("shape 1 — a topic and a difficulty: the PRD's own example")
        void topicAndDifficulty() throws Exception {
            // "Topic 'Algebra': requested 5 Hard, bank has 2" — PRD §6, written out in full in
            // contract section 7. The sentence itself is pinned by ExamCopy's test; what is
            // pinned here is that all four of its facts are on the wire and nothing else is.
            Shortfall original = new Shortfall("Algebra", Difficulty.HARD, 5, 2);

            Shortfall restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.topic()).isEqualTo("Algebra");
            assertThat(restored.difficulty()).isEqualTo(Difficulty.HARD);
            assertThat(restored.requested()).isEqualTo(5);
            assertThat(restored.available()).isEqualTo(2);
            assertThat(restored.isTopicScoped()).isTrue();
            assertThat(restored.isDifficultyScoped()).isTrue();
            assertThat(restored.missing()).isEqualTo(3);
        }

        @Test
        @DisplayName("shape 2 — a topic and the any bucket: difficulty is null")
        void topicOnly() throws Exception {
            Shortfall restored = roundTrip(new Shortfall("Recursion", null, 3, 2));

            assertThat(restored.isTopicScoped()).isTrue();
            assertThat(restored.isDifficultyScoped()).isFalse();
            assertThat(restored.missing()).isEqualTo(1);
        }

        @Test
        @DisplayName("shape 3 — course-wide with a difficulty: topic is null")
        void difficultyOnly() throws Exception {
            Shortfall restored = roundTrip(new Shortfall(null, Difficulty.HARD, 10, 4));

            assertThat(restored.isTopicScoped()).isFalse();
            assertThat(restored.isDifficultyScoped()).isTrue();
            assertThat(restored.missing()).isEqualTo(6);
        }

        @Test
        @DisplayName("shape 4 — course-wide, any difficulty: both nulls are meaningful")
        void neither() throws Exception {
            // Both nullable fields mirror TopicQuota's exactly, which is why this is the one
            // outbound record in the package with no requireNonNull at all.
            Shortfall restored = roundTrip(new Shortfall(null, null, 40, 31));

            assertThat(restored.isTopicScoped()).isFalse();
            assertThat(restored.isDifficultyScoped()).isFalse();
            assertThat(restored.missing()).isEqualTo(9);
        }

        @Test
        @DisplayName("missing() never goes below zero, so a stale count cannot read as a surplus")
        void missingIsClamped() {
            assertThat(new Shortfall("Recursion", Difficulty.HARD, 1, 0).missing()).isEqualTo(1);
            assertThat(new Shortfall("Recursion", Difficulty.HARD, 2, 5).missing()).isZero();
        }

        @Test
        @DisplayName("NO summary string travels: four fields and nothing else (ruling 4)")
        void noSentenceOnTheWire() {
            // Carrying both a structured report and a formatted sentence would be two
            // expressions of one fact, and they would disagree. The contract records the
            // deviation from BankMessages / ReleaseMessages rather than leaving it to be found.
            assertThat(java.util.Arrays.stream(Shortfall.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName))
                    .containsExactly("topic", "difficulty", "requested", "available");
        }

        @Test
        @DisplayName("a shortfall compares by value, so a report can be asserted on directly")
        void valueSemantics() {
            assertThat(new Shortfall("Recursion", Difficulty.HARD, 1, 0))
                    .isEqualTo(THIN_TOPIC)
                    .hasSameHashCodeAs(THIN_TOPIC);
            assertThat(new Shortfall("Recursion", Difficulty.HARD, 1, 1))
                    .isNotEqualTo(THIN_TOPIC);
        }
    }
}
