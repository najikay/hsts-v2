package server.db.converters;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import server.db.entities.BotTranscript;

/**
 * Maps {@link BotTranscript} to the {@code bot_sessions.transcript} JSON column
 * (V6, §5, S-33).
 *
 * <p>The column is NOT NULL, so a new session stores an empty transcript rather than
 * nothing: {@link BotTranscript#empty()} round-trips to {@code {"turns":[]}}.
 */
@Converter
public class BotTranscriptConverter implements AttributeConverter<BotTranscript, String> {

    @Override
    public String convertToDatabaseColumn(BotTranscript attribute) {
        return JsonCodec.write(attribute);
    }

    @Override
    public BotTranscript convertToEntityAttribute(String dbData) {
        return JsonCodec.read(dbData, BotTranscript.class);
    }
}
