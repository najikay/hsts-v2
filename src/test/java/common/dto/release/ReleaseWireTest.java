package common.dto.release;

import common.dto.exam.MonitorCounts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The release wire model — amendments A3 to A7 of the exam wire contract (E9 — F5).
 *
 * <p>Three things are worth testing on records, and all three are here: the rules that live
 * on them rather than in a service (the window, and which actions a state allows), the
 * merge that {@code PUSH_EXECUTION_STATUS} depends on, and that everything survives Java
 * serialization, because that is how these reach the other machine.
 *
 * <p>The window rule is the one that matters most. It lives on
 * {@link ReleaseCreateRequest} rather than in the server so the create dialog can validate
 * as the teacher types with the <b>same</b> method the server refuses with; a second copy in
 * the client is how one of them ends up wrong.
 */
class ReleaseWireTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");

    @Nested
    @DisplayName("the window rule (F5.2)")
    class Window {

        @Test
        @DisplayName("a normal window is accepted")
        void legalWindow() {
            assertThat(request(NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2)))
                    .windowProblem(NOW, ReleaseCreateRequest.PAST_GRACE)).isNull();
        }

        @Test
        @DisplayName("a close that is not after the open is refused, and equal counts as not after")
        void closeMustBeAfterOpen() {
            Instant moment = NOW.plus(Duration.ofHours(1));
            assertThat(request(moment, moment).windowProblem(NOW, ReleaseCreateRequest.PAST_GRACE))
                    .isEqualTo(ReleaseWindow.CLOSE_NOT_AFTER_OPEN);
            assertThat(request(moment, moment.minusSeconds(1))
                    .windowProblem(NOW, ReleaseCreateRequest.PAST_GRACE))
                    .isEqualTo(ReleaseWindow.CLOSE_NOT_AFTER_OPEN);
        }

        @Test
        @DisplayName("a window shorter than a minute is a typo, not an exam")
        void tooShort() {
            Instant open = NOW.plus(Duration.ofHours(1));
            assertThat(request(open, open.plusSeconds(30))
                    .windowProblem(NOW, ReleaseCreateRequest.PAST_GRACE))
                    .isEqualTo(ReleaseWindow.TOO_SHORT);
        }

        @Test
        @DisplayName("⚑ an opening moment inside the grace is accepted: that is 'now' in a classroom")
        void graceCoversNow() {
            assertThat(request(NOW.minus(Duration.ofMinutes(4)), NOW.plus(Duration.ofHours(1)))
                    .windowProblem(NOW, ReleaseCreateRequest.PAST_GRACE)).isNull();
        }

        @Test
        @DisplayName("but one well behind the clock is refused")
        void beyondTheGrace() {
            assertThat(request(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)))
                    .windowProblem(NOW, ReleaseCreateRequest.PAST_GRACE))
                    .isEqualTo(ReleaseWindow.IN_THE_PAST);
        }

        @Test
        @DisplayName("an empty picker is its own refusal, not a crash")
        void missingDates() {
            assertThat(new ReleaseCreateRequest(1, null, NOW).windowProblem(NOW, null))
                    .isEqualTo(ReleaseWindow.MISSING);
            assertThat(new ReleaseCreateRequest(1, NOW, null).windowProblem(NOW, null))
                    .isEqualTo(ReleaseWindow.MISSING);
        }

        @Test
        @DisplayName("isWindowLegal is the same rule with the production grace")
        void convenienceMatchesTheRule() {
            assertThat(request(NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2)))
                    .isWindowLegal(NOW)).isTrue();
            assertThat(request(NOW.plus(Duration.ofHours(2)), NOW.plus(Duration.ofHours(1)))
                    .isWindowLegal(NOW)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(ReleaseWindow.class)
        @DisplayName("every window sentence obeys the copy rules (PRD §4.1)")
        void copyRules(ReleaseWindow problem) {
            String sentence = problem.sentence();
            assertThat(sentence).doesNotContain("—").doesNotContain("–");
            assertThat(sentence).endsWith(".");
            assertThat(sentence.charAt(0)).isUpperCase();
            assertThat(sentence.toLowerCase(Locale.ROOT))
                    .as("every message says what to do next: %s", sentence)
                    .containsAnyOf("try again", "pick ");
        }

        private ReleaseCreateRequest request(Instant open, Instant close) {
            return new ReleaseCreateRequest(7001, open, close);
        }
    }

    @Nested
    @DisplayName("the code rule (F5.3, C-1)")
    class Code {

        @Test
        @DisplayName("⚑ a blank code and no code are the same request: generate one")
        void blankIsAbsent() {
            // One representation of "you pick one", so every later check is a null test.
            assertThat(new ReleaseCreateRequest(1, NOW, NOW.plusSeconds(3600), "  ").code())
                    .isNull();
            assertThat(new ReleaseCreateRequest(1, NOW, NOW.plusSeconds(3600), null).hasCode())
                    .isFalse();
            assertThat(new ReleaseCreateRequest(1, NOW, NOW.plusSeconds(3600)).hasCode())
                    .isFalse();
        }

        @Test
        @DisplayName("a typed code is trimmed and upper-cased on the way in (C-1)")
        void normalisedOnTheWayIn() {
            ReleaseCreateRequest ask =
                    new ReleaseCreateRequest(1, NOW, NOW.plusSeconds(3600), " ab7q ");

            assertThat(ask.code()).isEqualTo("AB7Q");
            assertThat(ask.hasCode()).isTrue();
            assertThat(ask.codeProblem()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"4821", "ABCD", "abcd", "aB3d", "IO01"})
        @DisplayName("⚑ the accepted shape is C-1's wide one, digits and mishearable letters included")
        void wellFormedCodes(String typed) {
            // Deliberately wider than the generator's alphabet: that narrowing is a choice
            // about codes we invent, not a rule we may impose on a teacher. T-5.3 types 4821.
            assertThat(ReleaseCreateRequest.isWellFormedCode(typed)).isTrue();
            assertThat(new ReleaseCreateRequest(1, NOW, NOW.plusSeconds(3600), typed)
                    .codeProblem()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"12", "ABCDE", "AB C", "AB-1", "אבגד"})
        @DisplayName("⚑ acceptance case 5.3's refusals, worded once for both tiers")
        void malformedCodes(String typed) {
            assertThat(ReleaseCreateRequest.isWellFormedCode(typed)).isFalse();
            assertThat(new ReleaseCreateRequest(1, NOW, NOW.plusSeconds(3600), typed)
                    .codeProblem()).isEqualTo(ReleaseCodeIssue.MALFORMED);
        }

        @Test
        @DisplayName("a null code is not well formed, rather than an exception")
        void nullIsNotWellFormed() {
            assertThat(ReleaseCreateRequest.isWellFormedCode(null)).isFalse();
            assertThat(ReleaseCreateRequest.normalizeCode(null)).isNull();
        }

        @Test
        @DisplayName("'is it free' is never answered here: that is the server's transaction")
        void takenIsNotAClientQuestion() {
            // codeProblem can only ever say MALFORMED. A client that pre-checked uniqueness
            // would be showing a green field for a code somebody else takes a second later.
            for (String typed : new String[]{"4821", "ABCD", "12", null}) {
                assertThat(new ReleaseCreateRequest(1, NOW, NOW.plusSeconds(3600), typed)
                        .codeProblem()).isNotEqualTo(ReleaseCodeIssue.TAKEN);
            }
        }

        @ParameterizedTest
        @EnumSource(ReleaseCodeIssue.class)
        @DisplayName("both code sentences obey the copy rules and name the way out (PRD §4.1)")
        void copyRules(ReleaseCodeIssue issue) {
            String sentence = issue.sentence();
            assertThat(sentence).doesNotContain("—").doesNotContain("–");
            assertThat(sentence).endsWith(".");
            assertThat(sentence.charAt(0)).isUpperCase();
            assertThat(sentence.toLowerCase(Locale.ROOT))
                    .as("every message says what to do next: %s", sentence)
                    .contains("blank to generate one");
        }
    }

    @Nested
    @DisplayName("what a state allows (F5.5)")
    class States {

        @Test
        @DisplayName("only a scheduled release may be cancelled, only a live one closed early")
        void oneStateEach() {
            assertThat(ReleaseState.SCHEDULED.canCancel()).isTrue();
            assertThat(ReleaseState.SCHEDULED.canCloseEarly()).isFalse();
            assertThat(ReleaseState.LIVE.canCloseEarly()).isTrue();
            assertThat(ReleaseState.LIVE.canCancel()).isFalse();
        }

        @ParameterizedTest
        @EnumSource(value = ReleaseState.class, names = {"CLOSED", "CANCELLED"})
        @DisplayName("nothing may be done to a release that is over")
        void overIsOver(ReleaseState state) {
            assertThat(state.isOver()).isTrue();
            assertThat(state.canCancel()).isFalse();
            assertThat(state.canCloseEarly()).isFalse();
            assertThat(state.isLive()).isFalse();
        }

        @Test
        @DisplayName("a row delegates its rules to its state rather than keeping a copy")
        void rowDelegates() {
            assertThat(row(ReleaseState.SCHEDULED).canCancel()).isTrue();
            assertThat(row(ReleaseState.LIVE).canCloseEarly()).isTrue();
            assertThat(row(ReleaseState.CLOSED).canCancel()).isFalse();
        }
    }

    @Nested
    @DisplayName("a release row")
    class Row {

        @Test
        @DisplayName("the effective end includes extensions, and so does the allotted time (S-20)")
        void extensionsMoveTheEnd() {
            ReleaseRow extended = new ReleaseRow(1, 2, "Midterm", "11", "Algebra", "4B7Q",
                    NOW, NOW.plus(Duration.ofHours(1)), 15, 45, ReleaseState.LIVE,
                    MonitorCounts.NONE);

            assertThat(extended.effectiveCloseAt())
                    .isEqualTo(NOW.plus(Duration.ofMinutes(75)));
            assertThat(extended.allottedMinutes()).isEqualTo(60);
        }

        @Test
        @DisplayName("a release nobody joined has no monitor worth opening")
        void participantsDecideTheMonitorLink() {
            assertThat(row(ReleaseState.SCHEDULED).hasParticipants()).isFalse();
            ReleaseRow busy = new ReleaseRow(1, 2, "Midterm", "11", "Algebra", "4B7Q",
                    NOW, NOW.plus(Duration.ofHours(1)), 0, 45, ReleaseState.LIVE,
                    new MonitorCounts(12, 3, 0));
            assertThat(busy.hasParticipants()).isTrue();
        }

        @Test
        @DisplayName("nulls become empty rather than reaching a label as the word null")
        void nullsAreTamed() {
            ReleaseRow bare = new ReleaseRow(1, 2, null, null, null, null,
                    NOW, NOW.plus(Duration.ofHours(1)), 0, 45, null, null);

            assertThat(bare.examName()).isEmpty();
            assertThat(bare.code()).isEmpty();
            assertThat(bare.state()).isEqualTo(ReleaseState.SCHEDULED);
            assertThat(bare.counts()).isEqualTo(MonitorCounts.NONE);
        }
    }

    @Nested
    @DisplayName("the list, and the push that patches it")
    class Merge {

        @Test
        @DisplayName("a pushed row replaces the one it is about, in place")
        void replacesInPlace() {
            ReleaseList list = new ReleaseList(NOW, List.of(row(1, ReleaseState.SCHEDULED),
                    row(2, ReleaseState.SCHEDULED)));

            ReleaseList merged = list.with(row(1, ReleaseState.LIVE));

            assertThat(merged.rows()).extracting(ReleaseRow::executionId).containsExactly(1L, 2L);
            assertThat(merged.rows().get(0).state()).isEqualTo(ReleaseState.LIVE);
        }

        @Test
        @DisplayName("⚑ a row for a release this list has never seen is an insert, not a mistake")
        void insertsTheUnknown() {
            // A release created on her other machine has to appear without a refresh.
            ReleaseList list = new ReleaseList(NOW, List.of(row(1, ReleaseState.SCHEDULED)));

            ReleaseList merged = list.with(row(9, ReleaseState.SCHEDULED));

            assertThat(merged.rows()).extracting(ReleaseRow::executionId).containsExactly(9L, 1L);
        }

        @Test
        @DisplayName("a null push changes nothing")
        void nullIsIgnored() {
            ReleaseList list = new ReleaseList(NOW, List.of(row(1, ReleaseState.SCHEDULED)));

            assertThat(list.with(null)).isSameAs(list);
        }

        @Test
        @DisplayName("the header counts the live ones")
        void liveCount() {
            ReleaseList list = new ReleaseList(NOW, List.of(row(1, ReleaseState.LIVE),
                    row(2, ReleaseState.SCHEDULED), row(3, ReleaseState.LIVE)));

            assertThat(list.liveCount()).isEqualTo(2);
            assertThat(list.isEmpty()).isFalse();
            assertThat(ReleaseList.empty(NOW).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("the picker")
    class Options {

        @Test
        @DisplayName("the two empty states are distinguishable, because their next steps differ")
        void twoEmptyStates() {
            assertThat(new ReleaseOptions(List.of(), true).waitingOnApproval()).isTrue();
            assertThat(new ReleaseOptions(List.of(), false).waitingOnApproval()).isFalse();
            assertThat(ReleaseOptions.empty().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a version's label names the exam, its version and its course")
        void versionLabel() {
            ReleasableVersion version = new ReleasableVersion(7001, "101101", "Midterm", 2,
                    "11", "Algebra 11", 45, 12);

            assertThat(version.label()).isEqualTo("Midterm (v2) · Algebra 11 · 12 questions");
            assertThat(version.label()).doesNotContain("—");
            assertThat(version.is(7001)).isTrue();
            assertThat(version.is(7002)).isFalse();
        }

        @Test
        @DisplayName("one question does not read as '1 questions'")
        void singularQuestion() {
            assertThat(new ReleasableVersion(1, "1", "Quiz", 1, "11", "Algebra", 10, 1).label())
                    .endsWith("1 question");
        }

        @Test
        @DisplayName("toString names the version rather than dumping every field")
        void readableToString() {
            assertThat(new ReleasableVersion(7001, "101101", "Midterm", 1, "11", "A", 45, 3)
                    .toString()).contains("7001").contains("Midterm");
        }
    }

    @Test
    @DisplayName("⚑ every payload survives the wire, because that is how it reaches the other machine")
    void roundTrips() throws Exception {
        ReleaseRow row = new ReleaseRow(1, 2, "Midterm", "11", "Algebra", "4B7Q",
                NOW, NOW.plus(Duration.ofHours(1)), 15, 45, ReleaseState.LIVE,
                new MonitorCounts(12, 3, 1));
        ReleaseList list = new ReleaseList(NOW, List.of(row));
        ReleaseOptions options = new ReleaseOptions(
                List.of(new ReleasableVersion(7001, "101101", "Midterm", 1, "11", "A", 45, 3)), true);

        assertThat(roundTrip(row)).isEqualTo(row);
        assertThat(roundTrip(list)).isEqualTo(list);
        assertThat(roundTrip(options)).isEqualTo(options);
        assertThat(roundTrip(new ReleaseCreateRequest(7001, NOW, NOW.plusSeconds(3600))))
                .isEqualTo(new ReleaseCreateRequest(7001, NOW, NOW.plusSeconds(3600)));
        assertThat(roundTrip(new ReleaseCreateRequest(7001, NOW, NOW.plusSeconds(3600), "4821")))
                .isEqualTo(new ReleaseCreateRequest(7001, NOW, NOW.plusSeconds(3600), "4821"));
        assertThat(roundTrip(new ReleaseActionRequest(5001)))
                .isEqualTo(new ReleaseActionRequest(5001));
    }

    @Test
    @DisplayName("no release payload carries a teacher id (P-5)")
    void noCallerIdOnTheWire() {
        // Who is releasing, cancelling or closing is the session's answer. A field here
        // could only ever hold somebody else's id.
        assertThat(java.util.Arrays.stream(ReleaseCreateRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("teacherId", "userId", "createdBy");
        assertThat(java.util.Arrays.stream(ReleaseActionRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("teacherId", "userId", "createdBy");
    }

    @Test
    @DisplayName("the create request carries the teacher's code, and the action request does not")
    void codeTravelsOnlyWhereItMeansSomething() {
        // §4: the teacher defines the code, so it is on the create request. Cancel and close
        // early identify a sitting that already has one, so a code there could only be a way
        // to address somebody else's.
        assertThat(java.util.Arrays.stream(ReleaseCreateRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .contains("code");
        assertThat(java.util.Arrays.stream(ReleaseActionRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("code");
    }

    private static ReleaseRow row(ReleaseState state) {
        return row(1, state);
    }

    private static ReleaseRow row(long executionId, ReleaseState state) {
        return new ReleaseRow(executionId, 2, "Midterm", "11", "Algebra", "4B7Q",
                NOW, NOW.plus(Duration.ofHours(1)), 0, 45, state, MonitorCounts.NONE);
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T payload) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(payload);
        }
        try (ObjectInputStream in = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }
}
