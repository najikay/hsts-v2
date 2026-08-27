package common.dto.exam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Round-trips and invariants for the take-exam wire model (E10.2, E11.1).
 *
 * <p>These are records, and a record deserializes through its canonical constructor rather
 * than field by field, so every compact constructor here runs again on the receiving side.
 * That is what these tests pin: a normalisation that only happens on the sending tier is a
 * normalisation that does not happen, and this package's compact constructors are load
 * bearing — they clamp a negative remaining time, drop an outcome from a live form, and
 * clone illustration bytes.
 *
 * <p>Hebrew text is checked too, because the question bank is Hebrew (X-I18N) and a paper
 * that arrives as question marks is a demo that ends early.
 */
class ExamDtoTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant ENDS = NOW.plus(Duration.ofMinutes(45));

    @Nested
    @DisplayName("codes and identity")
    class Entry {

        @Test
        @DisplayName("a code is trimmed and upper-cased on the way out (C-1)")
        void codeNormalises() throws Exception {
            ExamJoinRequest request = new ExamJoinRequest("  4b7q ");

            assertThat(request.code()).isEqualTo("4B7Q");
            assertThat(request.isWellFormed()).isTrue();
            assertThat(roundTrip(request).code()).isEqualTo("4B7Q");
        }

        @Test
        @DisplayName("a null code becomes empty rather than travelling as null")
        void nullCode() {
            assertThat(new ExamJoinRequest(null).code()).isEmpty();
            assertThat(new ExamJoinRequest(null).isWellFormed()).isFalse();
        }

        @Test
        @DisplayName("well-formed means exactly four alphanumeric characters")
        void wellFormedRule() {
            assertThat(new ExamJoinRequest("AB12").isWellFormed()).isTrue();
            assertThat(new ExamJoinRequest("1234").isWellFormed()).isTrue();
            assertThat(new ExamJoinRequest("ABC").isWellFormed()).isFalse();
            assertThat(new ExamJoinRequest("ABCDE").isWellFormed()).isFalse();
            assertThat(new ExamJoinRequest("AB-2").isWellFormed()).isFalse();
        }

        @Test
        @DisplayName("an ID request trims what was typed and reports whether anything was")
        void identityNormalises() throws Exception {
            assertThat(new AttemptStartRequest(1, "  374301851 ").nationalId()).isEqualTo("374301851");
            assertThat(new AttemptStartRequest(1, "   ").hasIdentity()).isFalse();
            assertThat(new AttemptStartRequest(1, null).nationalId()).isEmpty();
            assertThat(roundTrip(new AttemptStartRequest(7, "374301851")).executionId()).isEqualTo(7);
        }

        @Test
        @DisplayName("a resume request round-trips")
        void resumeRoundTrips() throws Exception {
            assertThat(roundTrip(new AttemptResumeRequest(5001)).executionId()).isEqualTo(5001);
        }
    }

    @Nested
    @DisplayName("the exam header")
    class Header {

        @Test
        @DisplayName("it round-trips, Hebrew included (X-I18N)")
        void roundTripsHebrew() throws Exception {
            ExamHeader header = new ExamHeader(5001, "מבחן אמצע", "21", "תכנות מונחה עצמים",
                    45, "ענו על כל השאלות.", 20, AttemptState.NOT_STARTED);

            ExamHeader restored = roundTrip(header);

            assertThat(restored.examName()).isEqualTo("מבחן אמצע");
            assertThat(restored.generalText()).isEqualTo("ענו על כל השאלות.");
            assertThat(restored.hasGeneralText()).isTrue();
        }

        @Test
        @DisplayName("absent instructions render as an empty block, never as the word null")
        void nullTextNormalises() {
            ExamHeader header = new ExamHeader(1, null, null, null, 45, null, 0, null);

            assertThat(header.generalText()).isEmpty();
            assertThat(header.hasGeneralText()).isFalse();
            assertThat(header.examName()).isEmpty();
            assertThat(header.attemptState()).isEqualTo(AttemptState.NOT_STARTED);
        }

        @Test
        @DisplayName("⚑ a truncated sitting carries both numbers and says so (B-14, A8)")
        void sittingMinutesAndWindow() throws Exception {
            Instant closesAt = Instant.parse("2026-08-20T10:00:00Z");
            ExamHeader truncated = new ExamHeader(2075, "Midterm: Algebra", "11", "Algebra",
                    75, "", 7, AttemptState.NOT_STARTED, closesAt, 2);

            ExamHeader restored = roundTrip(truncated);

            assertThat(restored.durationMinutes())
                    .as("the paper is still worth 75 and the screen still says so")
                    .isEqualTo(75);
            assertThat(restored.sittingMinutes())
                    .as("and this sitting is worth 2, which is what she is actually given")
                    .isEqualTo(2);
            assertThat(restored.windowClosesAt()).isEqualTo(closesAt);
            assertThat(restored.isSittingShortened()).isTrue();
        }

        @Test
        @DisplayName("a wide-enough window says nothing, and the v1 shape still means what it did")
        void theNormalCaseAndTheOldConstructor() {
            ExamHeader roomy = new ExamHeader(2075, "Midterm: Algebra", "11", "Algebra", 75, "",
                    7, AttemptState.NOT_STARTED, Instant.parse("2026-08-20T13:00:00Z"), 75);
            assertThat(roomy.isSittingShortened()).isFalse();

            // The pre-A8 constructor: no window, and the sitting is the paper's own length.
            ExamHeader legacy = new ExamHeader(2075, "Midterm: Algebra", "11", "Algebra", 75, "",
                    7, AttemptState.NOT_STARTED);
            assertThat(legacy.windowClosesAt()).isNull();
            assertThat(legacy.sittingMinutes()).isEqualTo(75);
            assertThat(legacy.isSittingShortened())
                    .as("a header from code that predates the amendment claims nothing")
                    .isFalse();

            assertThat(new ExamHeader(1, "x", "21", "Java", 75, "", 1, null, null, -5)
                    .sittingMinutes())
                    .as("never negative; no screen has to remember to clamp")
                    .isZero();
        }

        @Test
        @DisplayName("the course label falls back to the code when there is no name")
        void courseLabel() {
            assertThat(new ExamHeader(1, "x", "21", "Java", 45, "", 1, null).courseLabel())
                    .isEqualTo("21 · Java");
            assertThat(new ExamHeader(1, "x", "21", "", 45, "", 1, null).courseLabel())
                    .isEqualTo("21");
        }
    }

    @Nested
    @DisplayName("the paper")
    class Paper {

        @Test
        @DisplayName("a question round-trips with its illustration")
        void questionRoundTrips() throws Exception {
            byte[] image = {1, 2, 3, 4};
            ExamQuestion question = new ExamQuestion(1001, "21001", 1, 10, "שאלה",
                    "א", "ב", "ג", "ד", image);

            ExamQuestion restored = roundTrip(question);

            assertThat(restored).isEqualTo(question);
            assertThat(restored.hasImage()).isTrue();
            assertThat(restored.image()).containsExactly(1, 2, 3, 4);
        }

        @Test
        @DisplayName("two questions built from the same input are equal, images and all")
        void valueEqualityIncludesImages() {
            // A record's generated equals compares byte[] by reference, and the compact
            // constructor clones, so without the custom equals these would never be equal.
            // Load-bearing since B-8 (2026-08-27): ten seeded questions carry real bytes,
            // so this is the fixture's own equality, not insurance against a future one.
            ExamQuestion first = question(new byte[]{9, 9});
            ExamQuestion second = question(new byte[]{9, 9});

            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
            assertThat(new java.util.HashSet<>(List.of(first, second)))
                    .as("and they collapse in a hash-based collection, as equal values must")
                    .hasSize(1);
        }

        @Test
        @DisplayName("the illustration bytes cannot be mutated from outside")
        void imageIsDefensivelyCopied() {
            byte[] source = {1, 2, 3};
            ExamQuestion question = question(source);

            source[0] = 99;
            question.image()[1] = 99;

            assertThat(question.image()).containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("a question with no illustration says so")
        void noImage() {
            assertThat(question(null).hasImage()).isFalse();
            assertThat(question(new byte[0]).hasImage()).isFalse();
            assertThat(question(null).image()).isNull();
        }

        @Test
        @DisplayName("options are addressed 1..4 and nothing else")
        void optionsAreOneToFour() {
            ExamQuestion question = new ExamQuestion(1, "1", 1, 10, "q", "a", "b", "c", "d", null);

            assertThat(question.option(1)).isEqualTo("a");
            assertThat(question.option(4)).isEqualTo("d");
            assertThatIllegalArgumentException().isThrownBy(() -> question.option(0));
            assertThatIllegalArgumentException().isThrownBy(() -> question.option(5));
        }

        @Test
        @DisplayName("its toString keeps illustration bytes out of the log")
        void toStringIsLogSafe() {
            assertThat(question(new byte[]{1, 2, 3}).toString())
                    .contains("3 bytes")
                    .doesNotContain("[B@");
            assertThat(question(null).toString()).contains("none");
        }

        @Test
        @DisplayName("a saved answer must be a real option")
        void savedAnswerIsBounded() {
            assertThat(new SavedAnswer(1001, 3).selected()).isEqualTo(3);
            assertThatIllegalArgumentException().isThrownBy(() -> new SavedAnswer(1001, 0));
            assertThatIllegalArgumentException().isThrownBy(() -> new SavedAnswer(1001, 5));
            assertThat(SavedAnswer.isSelectable(1)).isTrue();
            assertThat(SavedAnswer.isSelectable(5)).isFalse();
        }
    }

    @Nested
    @DisplayName("timing (S-18)")
    class Timing {

        @Test
        @DisplayName("it is derived from the two instants, and round-trips")
        void betweenComputesRemaining() throws Exception {
            AttemptTiming timing = AttemptTiming.between(NOW, NOW, ENDS);

            assertThat(timing.remainingMillis()).isEqualTo(Duration.ofMinutes(45).toMillis());
            assertThat(timing.totalMillis()).isEqualTo(Duration.ofMinutes(45).toMillis());
            assertThat(timing.remaining()).isEqualTo(Duration.ofMinutes(45));
            assertThat(timing.total()).isEqualTo(Duration.ofMinutes(45));
            assertThat(timing.hasExpired()).isFalse();
            assertThat(roundTrip(timing)).isEqualTo(timing);
        }

        @Test
        @DisplayName("a passed deadline clamps to zero, never to a negative countdown")
        void neverNegative() throws Exception {
            AttemptTiming timing = AttemptTiming.between(ENDS.plusSeconds(60), NOW, ENDS);

            assertThat(timing.remainingMillis()).isZero();
            assertThat(timing.hasExpired()).isTrue();
            // And again on the receiving side, because the compact constructor runs there too.
            assertThat(roundTrip(new AttemptTiming(NOW, ENDS, -5000, -1)).remainingMillis()).isZero();
            assertThat(roundTrip(new AttemptTiming(NOW, ENDS, -5000, -1)).totalMillis()).isZero();
        }

        @Test
        @DisplayName("a finished attempt keeps its total but has nothing left")
        void finishedTiming() {
            AttemptTiming timing = AttemptTiming.finished(NOW, ENDS, Duration.ofMinutes(45).toMillis());

            assertThat(timing.remainingMillis()).isZero();
            assertThat(timing.totalMillis()).isEqualTo(Duration.ofMinutes(45).toMillis());
        }
    }

    @Nested
    @DisplayName("the form and the outcome")
    class FormAndOutcome {

        @Test
        @DisplayName("a live form cannot carry an outcome, even if one is handed to it")
        void liveFormDropsAnOutcome() {
            AttemptForm form = new AttemptForm(42, header(), List.of(), List.of(),
                    AttemptTiming.between(NOW, NOW, ENDS), AttemptState.IN_PROGRESS, outcome());

            // A contradiction the client would have to arbitrate; dropped here so it cannot
            // arrive at all.
            assertThat(form.outcome()).isNull();
            assertThat(form.isLive()).isTrue();
        }

        @Test
        @DisplayName("a finished form keeps its outcome, which is what the takeover renders")
        void finishedFormKeepsIt() throws Exception {
            AttemptForm form = new AttemptForm(42, header(), List.of(question(null)),
                    List.of(new SavedAnswer(1001, 2)),
                    AttemptTiming.finished(NOW, ENDS, 1000), AttemptState.TIMED_OUT, outcome());

            AttemptForm restored = roundTrip(form);

            assertThat(restored.outcome()).isNotNull();
            assertThat(restored.state()).isEqualTo(AttemptState.TIMED_OUT);
            assertThat(restored.isLive()).isFalse();
            assertThat(restored.answeredCount()).isEqualTo(1);
            assertThat(restored.questionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("null lists normalise to empty ones")
        void nullListsNormalise() {
            AttemptForm form = new AttemptForm(1, header(), null, null,
                    AttemptTiming.between(NOW, NOW, ENDS), null, null);

            assertThat(form.questions()).isEmpty();
            assertThat(form.savedAnswers()).isEmpty();
            assertThat(form.state()).isEqualTo(AttemptState.IN_PROGRESS);
        }

        @Test
        @DisplayName("the outcome counts what is blank, and blanks score zero (§6)")
        void outcomeCountsBlanks() throws Exception {
            AttemptOutcome result = roundTrip(outcome());

            assertThat(result.questionCount()).isEqualTo(3);
            assertThat(result.answeredCount()).isEqualTo(1);
            assertThat(result.unansweredCount()).isEqualTo(2);
            assertThat(result.wasForced()).isTrue();
            assertThat(result.summary()).hasSize(3);
        }

        @Test
        @DisplayName("a submitted outcome is not a forced one")
        void submittedIsNotForced() {
            AttemptOutcome submitted = new AttemptOutcome(42, AttemptState.SUBMITTED, "Exam",
                    ENDS, 30, 3, 3, List.of());

            assertThat(submitted.wasForced()).isFalse();
            assertThat(submitted.unansweredCount()).isZero();
        }

        @Test
        @DisplayName("a summary entry survives the wire with its blank flag")
        void summaryEntry() throws Exception {
            AttemptSummaryEntry entry = roundTrip(new AttemptSummaryEntry(7, "21007", false));

            assertThat(entry.ordinal()).isEqualTo(7);
            assertThat(entry.displayId()).isEqualTo("21007");
            assertThat(entry.answered()).isFalse();
            assertThat(new AttemptSummaryEntry(1, null, true).displayId()).isEmpty();
        }

        @Test
        @DisplayName("the save result carries the count and the corrected clock together")
        void saveResult() throws Exception {
            SaveAnswerResult result = roundTrip(new SaveAnswerResult(1001, 3, 7, 20,
                    AttemptTiming.between(NOW, NOW, ENDS)));

            assertThat(result.selected()).isEqualTo(3);
            assertThat(result.progress()).isEqualTo(0.35);
            assertThat(result.timing().remainingMillis()).isPositive();
            assertThat(new SaveAnswerResult(1, null, 0, 0, null).progress()).isZero();
        }

        @Test
        @DisplayName("a save request knows whether its selection is even legal")
        void saveRequestValidation() {
            assertThat(new SaveAnswerRequest(1, 2, 3).isSelectionLegal()).isTrue();
            assertThat(new SaveAnswerRequest(1, 2, null).isSelectionLegal()).isTrue();
            assertThat(new SaveAnswerRequest(1, 2, 0).isSelectionLegal()).isFalse();
            assertThat(new SaveAnswerRequest(1, 2, 5).isSelectionLegal()).isFalse();
        }

        @Test
        @DisplayName("a submit request round-trips")
        void submitRequest() throws Exception {
            assertThat(roundTrip(new SubmitAttemptRequest(42)).attemptId()).isEqualTo(42);
        }
    }

    @Nested
    @DisplayName("attempt state")
    class State {

        @Test
        @DisplayName("the two terminal states are finished and neither is live")
        void terminalStates() {
            assertThat(AttemptState.SUBMITTED.isFinished()).isTrue();
            assertThat(AttemptState.TIMED_OUT.isFinished()).isTrue();
            assertThat(AttemptState.IN_PROGRESS.isFinished()).isFalse();
            assertThat(AttemptState.NOT_STARTED.isFinished()).isFalse();

            assertThat(AttemptState.IN_PROGRESS.isLive()).isTrue();
            assertThat(AttemptState.NOT_STARTED.isLive()).isFalse();
            assertThat(AttemptState.SUBMITTED.isLive()).isFalse();
        }
    }

    @Nested
    @DisplayName("extension and monitoring")
    class ExtensionAndMonitor {

        @Test
        @DisplayName("an extension names the teacher, the minutes and the new end (F7.1 ⚑)")
        void extensionCarriesTheSentence() throws Exception {
            TimerExtended extension = roundTrip(new TimerExtended(5001, "Java Midterm",
                    "Dana Cohen", 15, AttemptTiming.between(NOW, NOW, ENDS)));

            assertThat(extension.teacherName()).isEqualTo("Dana Cohen");
            assertThat(extension.extraMinutes()).isEqualTo(15);
            assertThat(extension.gained()).isEqualTo(Duration.ofMinutes(15));
            assertThat(extension.timing().endsAt()).isEqualTo(ENDS);
        }

        @Test
        @DisplayName("a nameless teacher still produces a sentence, never 'null added 15 minutes'")
        void teacherNameFallsBack() {
            assertThat(new TimerExtended(1, "x", null, 15, null).teacherName())
                    .isEqualTo("Your teacher");
            assertThat(new TimerExtended(1, "x", "  ", 15, null).teacherName())
                    .isEqualTo("Your teacher");
        }

        @Test
        @DisplayName("an extension amount is bounded on both sides (§6)")
        void extensionAmountRule() {
            assertThat(new ExtendTimeRequest(1, 15).isAmountLegal()).isTrue();
            assertThat(new ExtendTimeRequest(1, 0).isAmountLegal()).isFalse();
            assertThat(new ExtendTimeRequest(1, -5).isAmountLegal()).isFalse();
            assertThat(new ExtendTimeRequest(1, ExtendTimeRequest.MAX_MINUTES).isAmountLegal()).isTrue();
            assertThat(new ExtendTimeRequest(1, ExtendTimeRequest.MAX_MINUTES + 1).isAmountLegal())
                    .isFalse();
        }

        @Test
        @DisplayName("the counts derive who is still working")
        void countsDeriveInProgress() {
            assertThat(new MonitorCounts(10, 4, 2).inProgress()).isEqualTo(4);
            assertThat(MonitorCounts.NONE.inProgress()).isZero();
            // Never negative, whatever a caller hands it.
            assertThat(new MonitorCounts(1, 4, 2).inProgress()).isZero();
        }

        @Test
        @DisplayName("a monitor row never shows a negative countdown and always has a name")
        void rowNormalises() throws Exception {
            MonitorRow row = roundTrip(new MonitorRow(2001, "  ", null, NOW, null,
                    -500, 3, 20, null, null));

            assertThat(row.studentName()).isEqualTo("Unknown student");
            assertThat(row.state()).isEqualTo(AttemptState.NOT_STARTED);
            assertThat(row.remainingMillis()).isZero();
            assertThat(row.isFlagged()).isFalse();
            assertThat(row.progressLabel()).isEqualTo("3/20");
        }

        @Test
        @DisplayName("an integrity flag reads as an observation, not an accusation (C-4)")
        void integrityFlagWording() throws Exception {
            IntegrityFlag flag = roundTrip(new IntegrityFlag("11", "Algebra 11", NOW));

            assertThat(flag.label()).isEqualTo("used Algebra 11 bot");
            assertThat(flag.at()).isEqualTo(NOW);
            assertThat(new IntegrityFlag("11", null, NOW).courseName()).isEqualTo("11");
            assertThat(new IntegrityFlag(null, null, NOW).courseCode()).isEmpty();
        }

        @Test
        @DisplayName("a monitor snapshot round-trips and counts its flags")
        void monitorRoundTrips() throws Exception {
            ExecutionMonitor monitor = roundTrip(new ExecutionMonitor(5001, "Java Midterm", "21",
                    "4B7Q", true, NOW, ENDS, 15, 60, new MonitorCounts(2, 1, 0),
                    List.of(flaggedRow(), plainRow())));

            assertThat(monitor.rows()).hasSize(2);
            assertThat(monitor.flaggedCount()).isEqualTo(1);
            assertThat(monitor.isEmpty()).isFalse();
            assertThat(monitor.extraMinutes()).isEqualTo(15);
            assertThat(monitor.durationMinutes()).isEqualTo(60);
        }

        @Test
        @DisplayName("an empty snapshot normalises its nulls and says it is empty")
        void emptyMonitor() {
            ExecutionMonitor monitor = new ExecutionMonitor(1, null, null, null, false,
                    NOW, ENDS, 0, 45, null, null);

            assertThat(monitor.isEmpty()).isTrue();
            assertThat(monitor.counts()).isEqualTo(MonitorCounts.NONE);
            assertThat(monitor.examName()).isEmpty();
            assertThat(monitor.code()).isEmpty();
        }

        @Test
        @DisplayName("a monitor request round-trips")
        void monitorRequest() throws Exception {
            assertThat(roundTrip(new MonitorRequest(5001)).executionId()).isEqualTo(5001);
        }

        // ---- E11.7 attention events (F7.1b), additive to the frozen contract ----

        @Test
        @DisplayName("an attention report carries one number and clamps a negative one")
        void attentionReportRoundTrips() throws Exception {
            assertThat(roundTrip(new AttentionReport(12_000)).awayMillis()).isEqualTo(12_000);
            assertThat(new AttentionReport(-1).awayMillis())
                    .as("a negative absence is a broken clock, not a shorter absence")
                    .isZero();
        }

        @Test
        @DisplayName("an attention summary reads as an observation, and pluralises properly")
        void attentionSummaryWording() throws Exception {
            AttentionSummary summary = roundTrip(new AttentionSummary(3, 40_000, NOW));

            assertThat(summary.label()).isEqualTo("Left the exam view 3 times · 40s total");
            assertThat(summary.lastAt()).isEqualTo(NOW);
            assertThat(new AttentionSummary(1, 12_000, NOW).label())
                    .as("never 1 times, which reads as a bug in the software")
                    .isEqualTo("Left the exam view once · 12s total");
        }

        @Test
        @DisplayName("attention summaries add up, keeping the latest time")
        void attentionSummaryAccumulates() {
            AttentionSummary summary = new AttentionSummary(1, 12_000, NOW)
                    .plus(20_000, ENDS)
                    .plus(-5, null);

            assertThat(summary.count()).isEqualTo(3);
            assertThat(summary.totalAwayMillis())
                    .as("a negative duration contributes nothing rather than subtracting")
                    .isEqualTo(32_000);
            assertThat(summary.lastAt())
                    .as("a null time leaves the previous one standing")
                    .isEqualTo(ENDS);
        }

        @Test
        @DisplayName("away time is seconds up to a minute, then minutes and seconds")
        void attentionDurationFormatting() {
            assertThat(AttentionSummary.formatAway(0)).isEqualTo("0s");
            assertThat(AttentionSummary.formatAway(-9)).isEqualTo("0s");
            assertThat(AttentionSummary.formatAway(40_000)).isEqualTo("40s");
            assertThat(AttentionSummary.formatAway(59_999)).isEqualTo("59s");
            assertThat(AttentionSummary.formatAway(60_000)).isEqualTo("1m 00s");
            assertThat(AttentionSummary.formatAway(125_000)).isEqualTo("2m 05s");
        }

        @Test
        @DisplayName("a monitor row carries the summary across the wire, and null means nothing to report")
        void monitorRowCarriesAttention() throws Exception {
            MonitorRow watched = roundTrip(new MonitorRow(2001, "Maya Levi",
                    AttemptState.IN_PROGRESS, NOW, null, 1000, 3, 20, null, null,
                    new AttentionSummary(2, 40_000, NOW)));

            assertThat(watched.hasAttentionEvents()).isTrue();
            assertThat(watched.attention().count()).isEqualTo(2);

            assertThat(plainRow().attention())
                    .as("the pre-E11.7 shape still compiles and reports nothing")
                    .isNull();
            assertThat(plainRow().hasAttentionEvents()).isFalse();
        }
    }

    // ===================== Fixture =======================================

    private static ExamQuestion question(byte[] image) {
        return new ExamQuestion(1001, "21001", 1, 10, "q", "a", "b", "c", "d", image);
    }

    private static ExamHeader header() {
        return new ExamHeader(5001, "Java Midterm", "21", "Java", 45, "", 3,
                AttemptState.IN_PROGRESS);
    }

    private static AttemptOutcome outcome() {
        return new AttemptOutcome(42, AttemptState.TIMED_OUT, "Java Midterm", ENDS, 45, 1, 3,
                List.of(new AttemptSummaryEntry(1, "21001", true),
                        new AttemptSummaryEntry(2, "21002", false),
                        new AttemptSummaryEntry(3, "21003", false)));
    }

    private static MonitorRow flaggedRow() {
        return new MonitorRow(2001, "Maya Levi", AttemptState.IN_PROGRESS, NOW, null,
                1000, 3, 20, null, new IntegrityFlag("11", "Algebra 11", NOW));
    }

    private static MonitorRow plainRow() {
        return new MonitorRow(2002, "Noam Bar", AttemptState.SUBMITTED, NOW, ENDS,
                0, 20, 20, 45, null);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Serializable> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }
}
