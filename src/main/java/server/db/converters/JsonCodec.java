package server.db.converters;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * The one JSON mapper the persistence layer uses, shared by every
 * {@link jakarta.persistence.AttributeConverter} in this package.
 *
 * <h2>Why the hand-rolled {@link Instant} handling</h2>
 *
 * The obvious answer is Jackson's {@code JavaTimeModule}, and
 * {@code jackson-datatype-jsr310} does resolve on this classpath today — but only
 * <em>transitively</em>, pulled in by another library. Nothing in {@code pom.xml} asks
 * for it. Building stored-data serialization on a dependency that arrives by accident
 * means a future version bump elsewhere can silently break the reading of rows already
 * written, which is the worst class of breakage: it appears long after the change that
 * caused it, in data nobody can re-derive.
 *
 * <p>So timestamps are handled here, in about twenty lines, against
 * {@code jackson-databind}, which the pom declares outright. The wire format is
 * ISO-8601 with a {@code Z} suffix — human-readable in a {@code SELECT}, sortable as
 * text, and unambiguous about the zone, which matters because §5 stores everything in
 * UTC.
 *
 * <p>Unknown properties are ignored on read. A column written by a newer version of
 * the application with an extra field must still load in an older one rather than
 * failing the whole query.
 */
final class JsonCodec {

    private static final ObjectMapper MAPPER = build();

    private JsonCodec() {
        // static helper — no instances
    }

    private static ObjectMapper build() {
        SimpleModule instants = new SimpleModule();
        instants.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator generator,
                                  com.fasterxml.jackson.databind.SerializerProvider providers)
                    throws IOException {
                generator.writeString(DateTimeFormatter.ISO_INSTANT.format(value));
            }
        });
        instants.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser parser, DeserializationContext context)
                    throws IOException {
                if (parser.currentToken() == JsonToken.VALUE_NULL) {
                    return null;
                }
                String text = parser.getText();
                try {
                    return Instant.parse(text);
                } catch (DateTimeParseException e) {
                    // Jackson does not wrap a RuntimeException thrown inside a
                    // deserializer, so without this the caller would see a
                    // DateTimeParseException instead of the UncheckedIOException this
                    // class documents. Reachable in practice: a row written as an epoch
                    // number by any code that did not disable WRITE_DATES_AS_TIMESTAMPS.
                    throw new IOException("not an ISO-8601 instant: " + text, e);
                }
            }
        });

        return new ObjectMapper()
                .registerModule(instants)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * @param value the object to store; {@code null} maps to a NULL column
     * @return the JSON text for the column
     * @throws UncheckedIOException if the value cannot be serialized, which is a
     *                              programming error rather than a data condition
     */
    static String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not serialize " + value.getClass().getSimpleName(), e);
        }
    }

    /**
     * @param json the column contents, possibly {@code null} or blank
     * @param type what to read it as
     * @param <T>  the value type
     * @return the parsed value, or {@code null} when the column held nothing
     * @throws UncheckedIOException if the stored text is not valid JSON for the type —
     *                              deliberately loud, because silently returning null
     *                              would turn corrupt data into missing data
     */
    static <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read stored " + type.getSimpleName() + ": " + json, e);
        }
    }
}
