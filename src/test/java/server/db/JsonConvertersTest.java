package server.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.converters.BotTranscriptConverter;
import server.db.converters.ExecutionStatsConverter;
import server.db.converters.ParticipationConverter;
import server.db.entities.BotTranscript;
import server.db.entities.ExecutionStats;
import server.db.entities.Participation;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Covers the JSON column converters (E2.9). No database needed.
 *
 * <p>These matter more than their size suggests: they are the only place stored data is
 * translated rather than copied, so a bug here is a bug in data that has already been
 * written and cannot be re-derived. The timestamp cases in particular guard the
 * hand-rolled {@code Instant} handling, which exists because {@code jackson-datatype-jsr310}
 * reaches this classpath only as a transitive dependency.
 */
class JsonConvertersTest {

    private final ExecutionStatsConverter statsConverter = new ExecutionStatsConverter();
    private final ParticipationConverter participationConverter = new ParticipationConverter();
    private final BotTranscriptConverter transcriptConverter = new BotTranscriptConverter();

    @Test
    @DisplayName("execution statistics survive the round trip intact")
    void statsRoundTrip() {
        ExecutionStats original = new ExecutionStats(72.5, 74.0, 11.25, 41, 98, 0.85,
                List.of(0, 0, 0, 0, 1, 2, 5, 8, 6, 3));

        ExecutionStats returned = statsConverter.convertToEntityAttribute(
                statsConverter.convertToDatabaseColumn(original));

        assertThat(returned).isEqualTo(original);
    }

    @Test
    @DisplayName("participation counts survive the round trip")
    void participationRoundTrip() {
        Participation original = new Participation(25, 23, 2);

        Participation returned = participationConverter.convertToEntityAttribute(
                participationConverter.convertToDatabaseColumn(original));

        assertThat(returned).isEqualTo(original);
    }

    @Test
    @DisplayName("a transcript keeps its Hebrew text and its timestamps to the millisecond")
    void transcriptRoundTripsHebrewAndInstants() {
        Instant asked = Instant.parse("2026-08-19T14:03:27.451Z");
        BotTranscript original = new BotTranscript(List.of(
                new BotTranscript.Turn("student", "מה ההבדל בין מערך לרשימה?", asked),
                new BotTranscript.Turn("bot", "מערך הוא בגודל קבוע…", asked.plusMillis(1200))));

        BotTranscript returned = transcriptConverter.convertToEntityAttribute(
                transcriptConverter.convertToDatabaseColumn(original));

        assertThat(returned).isEqualTo(original);
        assertThat(returned.turns().get(0).text()).isEqualTo("מה ההבדל בין מערך לרשימה?");
        assertThat(returned.turns().get(1).at()).isEqualTo(asked.plusMillis(1200));
    }

    @Test
    @DisplayName("timestamps are stored as readable ISO-8601, not epoch numbers")
    void instantsAreStoredAsIsoText() {
        BotTranscript transcript = new BotTranscript(List.of(
                new BotTranscript.Turn("student", "שאלה", Instant.parse("2026-08-19T14:03:27.451Z"))));

        String json = transcriptConverter.convertToDatabaseColumn(transcript);

        // Readable in a SELECT, sortable as text, and unambiguous about the zone — which
        // matters because §5 stores everything in UTC.
        assertThat(json).contains("2026-08-19T14:03:27.451Z");
    }

    @Test
    @DisplayName("null maps to a NULL column and back, without inventing an empty object")
    void nullsPassThrough() {
        assertThat(statsConverter.convertToDatabaseColumn(null)).isNull();
        assertThat(statsConverter.convertToEntityAttribute(null)).isNull();
        assertThat(participationConverter.convertToEntityAttribute("")).isNull();
        assertThat(transcriptConverter.convertToEntityAttribute("   ")).isNull();
    }

    @Test
    @DisplayName("an empty transcript round-trips rather than becoming null")
    void emptyTranscriptIsNotNull() {
        String json = transcriptConverter.convertToDatabaseColumn(BotTranscript.empty());

        // bot_sessions.transcript is NOT NULL, so a new session has to store something.
        assertThat(json).isNotBlank();
        assertThat(transcriptConverter.convertToEntityAttribute(json).turns()).isEmpty();
    }

    @Test
    @DisplayName("a column written by a newer version with extra fields still loads")
    void unknownPropertiesAreIgnored() {
        String fromTheFuture = "{\"started\":10,\"finished\":9,\"timedOut\":1,\"abandoned\":3}";

        assertThat(participationConverter.convertToEntityAttribute(fromTheFuture))
                .isEqualTo(new Participation(10, 9, 1));
    }

    @Test
    @DisplayName("a timestamp stored as a number fails as an IO problem, not a parse crash")
    void numericTimestampSurfacesAsTheDocumentedType() {
        // Reachable for real: any writer that did not disable WRITE_DATES_AS_TIMESTAMPS
        // produces this. Jackson does not wrap a RuntimeException thrown inside a
        // deserializer, so without explicit handling the caller sees a
        // DateTimeParseException — not the UncheckedIOException this API documents.
        String epochStyle = "{\"turns\":[{\"role\":\"student\",\"text\":\"שאלה\",\"at\":1700000000}]}";

        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> transcriptConverter.convertToEntityAttribute(epochStyle));
    }

    @Test
    @DisplayName("a null timestamp inside a transcript reads as null, not as the text \"null\"")
    void nullTimestampInsideATurn() {
        String withNullAt = "{\"turns\":[{\"role\":\"bot\",\"text\":\"תשובה\",\"at\":null}]}";

        BotTranscript returned = transcriptConverter.convertToEntityAttribute(withNullAt);

        assertThat(returned.turns()).hasSize(1);
        assertThat(returned.turns().get(0).at()).isNull();
    }

    @Test
    @DisplayName("corrupt stored text fails loudly instead of quietly reading as null")
    void corruptDataThrows() {
        // Returning null here would turn "this row is damaged" into "this row has no
        // statistics", which is the same thing an ungraded execution looks like.
        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> statsConverter.convertToEntityAttribute("{not json at all"));
    }
}
