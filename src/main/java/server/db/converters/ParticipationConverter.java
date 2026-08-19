package server.db.converters;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import server.db.entities.Participation;

/**
 * Maps {@link Participation} to the {@code exam_executions.participation} JSON column
 * (V4, §5, S-21) — the counts frozen at close, never a live counter.
 */
@Converter
public class ParticipationConverter implements AttributeConverter<Participation, String> {

    @Override
    public String convertToDatabaseColumn(Participation attribute) {
        return JsonCodec.write(attribute);
    }

    @Override
    public Participation convertToEntityAttribute(String dbData) {
        return JsonCodec.read(dbData, Participation.class);
    }
}
