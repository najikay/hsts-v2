package server.db.converters;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import server.db.entities.ExecutionStats;

/**
 * Maps {@link ExecutionStats} to the {@code exam_executions.stats} JSON column
 * (V4, §5, C-5).
 */
@Converter
public class ExecutionStatsConverter implements AttributeConverter<ExecutionStats, String> {

    @Override
    public String convertToDatabaseColumn(ExecutionStats attribute) {
        return JsonCodec.write(attribute);
    }

    @Override
    public ExecutionStats convertToEntityAttribute(String dbData) {
        return JsonCodec.read(dbData, ExecutionStats.class);
    }
}
