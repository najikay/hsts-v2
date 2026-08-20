package common.dto.lock;

import java.io.Serializable;
import java.util.Objects;

/**
 * The thing an edit lock is about (Common tier, E18.1).
 *
 * <p>A pair of {@code (type, id)} rather than a table name and a row: the lock
 * service is generic and holds no domain knowledge at all, which is what lets
 * the exam builder, the bot source editor and the release schedule reuse it in
 * later epics without touching a line of it.
 *
 * <p>{@code entityType} is a short agreed literal — the constants below are the
 * ones this build uses. It is compared case-insensitively after trimming, so a
 * client that sends {@code "Question"} cannot open a second, parallel lock on a
 * row that is already held under {@code "question"}. That normalisation is the
 * whole security value of this record: the key IS the mutual exclusion.
 *
 * @param entityType what kind of thing, normalised to lower case
 * @param entityId   its id
 */
public record EntityRef(String entityType, long entityId) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The legacy question-bank row (E18.5 proof; E6 replaces it with the versioned bank). */
    public static final String QUESTION = "question";

    /** An exam version under composition (E7). */
    public static final String EXAM_VERSION = "exam-version";

    /** A course bot's information sources (E16). */
    public static final String BOT_SOURCE = "bot-source";

    /** A scheduled execution being edited (E9). */
    public static final String EXECUTION = "execution";

    /** A grade under review (E12). */
    public static final String GRADE = "grade";

    public EntityRef {
        Objects.requireNonNull(entityType, "entityType");
        entityType = entityType.trim().toLowerCase(java.util.Locale.ROOT);
        if (entityType.isEmpty()) {
            throw new IllegalArgumentException("An entity reference needs a type");
        }
    }

    /** @return a reference to a legacy question-bank row. */
    public static EntityRef question(long id) {
        return new EntityRef(QUESTION, id);
    }

    @Override
    public String toString() {
        return entityType + '#' + entityId;
    }
}
