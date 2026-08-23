package common.dto.report;

import java.io.Serializable;
import java.util.Objects;

/**
 * "What can I run a {@code dimension} report about?" (Common tier, E15.3 — F9.4).
 *
 * <p>A dimension and nothing else. There is no scope field, no teacher id and no course filter,
 * because the principal's scope is the whole school (spec 7.3.1) and a field a client could set
 * would be a field a client could widen. The role gate on the verb is the entire authorization
 * story for this request (F9.3).
 *
 * @param dimension the dimension to list subjects of
 */
public record ReportSubjectsRequest(ReportDimension dimension) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** @throws NullPointerException when {@code dimension} is null */
    public ReportSubjectsRequest {
        Objects.requireNonNull(dimension, "dimension");
    }
}
