package common.dto.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round trips and normalisation for the study bot's wire types (E16.11).
 *
 * <p>These are records, so deserialisation runs the compact constructor again on
 * the receiving side. That is the whole point of round-tripping them rather than
 * asserting on freshly constructed values: a defensive copy or a null-to-empty
 * rule that only works locally is a bug that appears once the two JARs are on two
 * machines, which is where nobody wants to find it.
 */
class BotDtoTest {

    private static final Instant WHEN = Instant.parse("2026-08-20T10:00:00Z");

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

    @Nested
    @DisplayName("asking and answering")
    class Asking {

        @Test
        @DisplayName("an ask round-trips, course code normalised to upper case")
        void askRoundTrips() throws Exception {
            BotAskRequest restored = roundTrip(
                    new BotAskRequest(" 22 ", 7L, "  what is a foreign key  ", false));

            assertThat(restored.courseCode()).isEqualTo("22");
            assertThat(restored.question()).isEqualTo("what is a foreign key");
            assertThat(restored.sessionId()).isEqualTo(7L);
            assertThat(restored.continuesSession()).isTrue();
        }

        @Test
        @DisplayName("a non-positive session id means a fresh conversation, not session zero")
        void zeroSessionIdIsNoSession() {
            assertThat(new BotAskRequest("22", 0L, "q", false).sessionId()).isNull();
            assertThat(new BotAskRequest("22", -3L, "q", false).continuesSession()).isFalse();
            assertThat(BotAskRequest.first("22", "q").continuesSession()).isFalse();
            assertThat(BotAskRequest.inSession("22", 5L, "q").sessionId()).isEqualTo(5L);
        }

        @Test
        @DisplayName("acknowledging keeps everything else about the ask")
        void acknowledged() {
            BotAskRequest acknowledged = BotAskRequest.inSession("22", 5L, "q").acknowledged();

            assertThat(acknowledged.integrityAcknowledged()).isTrue();
            assertThat(acknowledged.sessionId()).isEqualTo(5L);
            assertThat(acknowledged.question()).isEqualTo("q");
        }

        @Test
        @DisplayName("well-formed means a course and a question, and the length limit is separate")
        void wellFormed() {
            assertThat(BotAskRequest.first("22", "q").isWellFormed()).isTrue();
            assertThat(BotAskRequest.first("22", "  ").isWellFormed()).isFalse();
            assertThat(BotAskRequest.first("  ", "q").isWellFormed()).isFalse();
            assertThat(BotAskRequest.first("22", "x".repeat(BotAskRequest.MAX_QUESTION))
                    .isWithinLengthLimit()).isTrue();
            assertThat(BotAskRequest.first("22", "x".repeat(BotAskRequest.MAX_QUESTION + 1))
                    .isWithinLengthLimit()).isFalse();
        }

        @Test
        @DisplayName("an answer round-trips, Hebrew included")
        void answerRoundTrips() throws Exception {
            BotAnswer restored = roundTrip(new BotAnswer(7L, "מהו מפתח זר?",
                    "מפתח זר מצביע על מפתח ראשי.", WHEN));

            assertThat(restored.answer()).isEqualTo("מפתח זר מצביע על מפתח ראשי.");
            assertThat(restored.askedAt()).isEqualTo(WHEN);
            assertThat(restored.asked().isFromStudent()).isTrue();
            assertThat(restored.answered().speaker()).isEqualTo(BotSpeaker.BOT);
        }

        @Test
        @DisplayName("a blank answer becomes the S-32 sentence rather than an empty bubble")
        void blankAnswerBecomesTheFallback() {
            assertThat(new BotAnswer(1L, "q", "   ", WHEN).answer())
                    .isEqualTo(BotAnswer.S32_FALLBACK);
            assertThat(new BotAnswer(1L, "q", null, WHEN).isFallback()).isTrue();
            assertThat(BotAnswer.unanswered(1L, "q", WHEN).isFallback()).isTrue();
        }

        @Test
        @DisplayName("the integrity notice round-trips its sentence")
        void integrityNoticeRoundTrips() throws Exception {
            BotIntegrityNotice restored =
                    roundTrip(new BotIntegrityNotice("Databases 22", "You are taking an exam."));

            assertThat(restored.courseName()).isEqualTo("Databases 22");
            assertThat(restored.message()).isEqualTo("You are taking an exam.");
            assertThat(new BotIntegrityNotice(null, "m").courseName()).isEmpty();
        }
    }

    @Nested
    @DisplayName("conversations and history")
    class History {

        @Test
        @DisplayName("a turn round-trips with its speaker and time")
        void turnRoundTrips() throws Exception {
            BotTurn restored = roundTrip(BotTurn.answered("an answer", WHEN));

            assertThat(restored.speaker()).isEqualTo(BotSpeaker.BOT);
            assertThat(restored.isFromStudent()).isFalse();
            assertThat(restored.at()).isEqualTo(WHEN);
            assertThat(new BotTurn(BotSpeaker.STUDENT, null, WHEN).text()).isEmpty();
        }

        @Test
        @DisplayName("the transcript's role strings map both ways, and anything odd renders as the bot")
        void speakerWireNames() {
            assertThat(BotSpeaker.STUDENT.wireName()).isEqualTo("student");
            assertThat(BotSpeaker.BOT.wireName()).isEqualTo("bot");
            assertThat(BotSpeaker.fromWireName("student")).isEqualTo(BotSpeaker.STUDENT);
            assertThat(BotSpeaker.fromWireName("STUDENT")).isEqualTo(BotSpeaker.STUDENT);
            assertThat(BotSpeaker.fromWireName("assistant")).isEqualTo(BotSpeaker.BOT);
            assertThat(BotSpeaker.fromWireName(null)).isEqualTo(BotSpeaker.BOT);
        }

        @Test
        @DisplayName("a conversation copies its turns and counts her questions")
        void conversationRoundTrips() throws Exception {
            List<BotTurn> mutable = new ArrayList<>(List.of(
                    BotTurn.asked("q1", WHEN), BotTurn.answered("a1", WHEN),
                    BotTurn.asked("q2", WHEN), BotTurn.answered("a2", WHEN)));
            BotConversation conversation =
                    new BotConversation(7L, "22", "Databases 22", WHEN, WHEN, mutable);
            mutable.clear();

            BotConversation restored = roundTrip(conversation);

            assertThat(restored.turns()).hasSize(4);
            assertThat(restored.questionCount()).isEqualTo(2);
            assertThat(new BotConversation(7L, "22", null, WHEN, null, null).courseName())
                    .isEqualTo("22");
        }

        @Test
        @DisplayName("a history row previews the first question and collapses its whitespace")
        void sessionRowPreview() throws Exception {
            BotSessionRow restored = roundTrip(new BotSessionRow(7L, WHEN, WHEN, 3,
                    "  what\n  is a\tforeign key  "));

            assertThat(restored.preview()).isEqualTo("what is a foreign key");
            assertThat(restored.questionLabel()).isEqualTo("3 questions");
            assertThat(new BotSessionRow(7L, WHEN, WHEN, 1, "q").questionLabel())
                    .isEqualTo("1 question");
        }

        @Test
        @DisplayName("a long first question is truncated rather than stretching the row")
        void sessionRowTruncates() {
            String preview = new BotSessionRow(7L, WHEN, WHEN, 1, "x".repeat(200)).preview();

            assertThat(preview).hasSize(BotSessionRow.PREVIEW_LENGTH + 1).endsWith("…");
        }

        @Test
        @DisplayName("a sessions page round-trips and defaults its course name")
        void sessionsPageRoundTrips() throws Exception {
            BotSessionsPage restored = roundTrip(new BotSessionsPage("22", null,
                    List.of(new BotSessionRow(7L, WHEN, WHEN, 1, "q"))));

            assertThat(restored.courseName()).isEqualTo("22");
            assertThat(restored.sessions()).hasSize(1);
            assertThat(BotSessionsPage.empty("22", "Databases 22").isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a session request is only well formed with a real id")
        void sessionRequest() {
            assertThat(new BotSessionRequest(7L).isWellFormed()).isTrue();
            assertThat(new BotSessionRequest(0L).isWellFormed()).isFalse();
        }
    }

    @Nested
    @DisplayName("managing the bot")
    class Managing {

        @Test
        @DisplayName("a profile fills in a missing name from the course")
        void profileDefaults() throws Exception {
            BotProfile restored = roundTrip(new BotProfile(9L, "22", "Databases 22", "  ", true));

            assertThat(restored.name()).isEqualTo("Databases 22 study bot");
            assertThat(restored.heading()).isEqualTo("Databases 22 study bot · Databases 22");
            assertThat(new BotProfile(9L, "22", null, null, false).courseName()).isEqualTo("22");
        }

        @Test
        @DisplayName("a source row round-trips and labels its size the way the table shows it")
        void sourceRow() throws Exception {
            BotSourceRow restored = roundTrip(new BotSourceRow(5L, BotSourceKind.PDF,
                    "Week 3 handout", "Dana Cohen", WHEN, 2, 1240));

            assertThat(restored.title()).isEqualTo("Week 3 handout");
            assertThat(restored.sizeLabel()).isEqualTo("1.2k characters");
            assertThat(new BotSourceRow(5L, BotSourceKind.TEXT, "t", "d", WHEN, 1, 999)
                    .sizeLabel()).isEqualTo("999 characters");
            assertThat(new BotSourceRow(5L, BotSourceKind.TEXT, "  ", null, WHEN, 0, -4).title())
                    .isEqualTo("Untitled source");
        }

        @Test
        @DisplayName("a manager page round-trips, and a course with no bot is a state not an error")
        void managerPage() throws Exception {
            BotManagerPage none = roundTrip(BotManagerPage.none());
            assertThat(none.exists()).isFalse();
            assertThat(none.sourceCount()).isZero();

            BotManagerPage page = roundTrip(BotManagerPage.of(
                    new BotProfile(9L, "22", "Databases 22", "bot", true),
                    List.of(new BotSourceRow(5L, BotSourceKind.TEXT, "t", "d", WHEN, 1, 10))));
            assertThat(page.exists()).isTrue();
            assertThat(page.sourceCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the request records normalise their course codes and validate themselves")
        void requestRecords() throws Exception {
            assertThat(roundTrip(new BotCourseRequest(" 22 ")).courseCode()).isEqualTo("22");
            assertThat(new BotCourseRequest("  ").isWellFormed()).isFalse();
            assertThat(new BotCreateRequest(" 22 ", "  name  ").name()).isEqualTo("name");
            assertThat(new BotCreateRequest("22", "x".repeat(200)).name())
                    .hasSize(BotCreateRequest.MAX_NAME);
            assertThat(new BotCreateRequest("  ", "n").isWellFormed()).isFalse();
            assertThat(roundTrip(new BotActiveRequest(" 22 ", true)).courseCode()).isEqualTo("22");
            assertThat(new BotActiveRequest("  ", true).isWellFormed()).isFalse();
            assertThat(new SourceRemoveRequest("22", 5L).isWellFormed()).isTrue();
            assertThat(new SourceRemoveRequest("22", 0L).isWellFormed()).isFalse();
            assertThat(roundTrip(new SourceRemoveRequest(" 22 ", 5L)).courseCode()).isEqualTo("22");
        }

        @Test
        @DisplayName("an upload copies its bytes in and out, and never prints them")
        void sourceAddRequest() throws Exception {
            byte[] bytes = "material".getBytes(StandardCharsets.UTF_8);
            SourceAddRequest request =
                    new SourceAddRequest("22", BotSourceKind.PDF, "Week 3", bytes);
            bytes[0] = 'X';

            assertThat(new String(request.content(), StandardCharsets.UTF_8))
                    .as("copied in: mutating the caller's array cannot change the request")
                    .isEqualTo("material");
            request.content()[0] = 'Y';
            assertThat(new String(request.content(), StandardCharsets.UTF_8))
                    .as("copied out: mutating what it hands back cannot change it either")
                    .isEqualTo("material");

            SourceAddRequest restored = roundTrip(request);
            assertThat(restored).isEqualTo(request);
            assertThat(restored.hashCode()).isEqualTo(request.hashCode());
            assertThat(restored.sizeBytes()).isEqualTo(8);
            assertThat(restored.toString()).contains("bytes=8").doesNotContain("material");
        }

        @Test
        @DisplayName("an upload validates its completeness and its size separately")
        void sourceAddValidation() {
            assertThat(new SourceAddRequest("22", BotSourceKind.TEXT, "t", new byte[1])
                    .isWellFormed()).isTrue();
            assertThat(new SourceAddRequest("22", BotSourceKind.TEXT, "  ", new byte[1])
                    .isWellFormed()).isFalse();
            assertThat(new SourceAddRequest("22", BotSourceKind.TEXT, "t", null)
                    .isWellFormed()).isFalse();
            assertThat(new SourceAddRequest("22", BotSourceKind.TEXT, "t",
                    new byte[SourceAddRequest.MAX_BYTES]).isWithinSizeLimit()).isTrue();
            assertThat(new SourceAddRequest("22", BotSourceKind.TEXT, "t",
                    new byte[SourceAddRequest.MAX_BYTES + 1]).isWithinSizeLimit()).isFalse();
            assertThat(new SourceAddRequest("22", BotSourceKind.TEXT, "x".repeat(300),
                    new byte[1]).title()).hasSize(SourceAddRequest.MAX_TITLE);
        }
    }

    @Nested
    @DisplayName("the anonymised aggregate")
    class Analytics {

        @Test
        @DisplayName("the aggregate round-trips and reports its peak")
        void analyticsRoundTrips() throws Exception {
            BotAnalytics restored = roundTrip(new BotAnalytics("Databases 22", 12,
                    List.of(new BotActivityPoint(LocalDate.of(2026, 8, 19), 4),
                            new BotActivityPoint(LocalDate.of(2026, 8, 20), 8)),
                    List.of(new BotTopQuestion("what is a foreign key", 5))));

            assertThat(restored.totalQuestions()).isEqualTo(12);
            assertThat(restored.peakPerDay()).isEqualTo(8);
            assertThat(restored.isEmpty()).isFalse();
            assertThat(restored.frequent().get(0).timesLabel()).isEqualTo("5 times");
        }

        @Test
        @DisplayName("an empty aggregate is a state the screen draws")
        void emptyAnalytics() {
            BotAnalytics empty = BotAnalytics.empty("Databases 22");

            assertThat(empty.isEmpty()).isTrue();
            assertThat(empty.peakPerDay()).isZero();
            assertThat(empty.activity()).isEmpty();
            assertThat(empty.frequent()).isEmpty();
        }

        @Test
        @DisplayName("negative counts are clamped rather than drawn as negative bars")
        void clampsNegatives() {
            assertThat(new BotActivityPoint(LocalDate.of(2026, 8, 20), -3).count()).isZero();
            assertThat(new BotTopQuestion(null, -1).count()).isZero();
            assertThat(new BotTopQuestion(null, 1).question()).isEmpty();
            assertThat(new BotAnalytics("c", -5, null, null).totalQuestions()).isZero();
        }
    }

    @Test
    @DisplayName("the source kinds label themselves for the table")
    void sourceKindLabels() {
        for (BotSourceKind kind : BotSourceKind.values()) {
            assertThat(kind.label()).isNotBlank();
        }
    }
}
