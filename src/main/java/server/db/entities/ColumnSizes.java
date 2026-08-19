package server.db.entities;

/**
 * Column lengths that pick a MySQL type, for the entities in this package.
 *
 * <p>MySQL has a family of text and blob types rather than one of each, and Hibernate
 * chooses between them from the declared {@code length}. Left unset, a {@code String}
 * becomes {@code varchar(255)} and a {@code @Lob byte[]} becomes {@code tinyblob} — a
 * 255-byte column where the migration created a 16 MB one. Nothing at runtime would
 * complain until a real question image arrived and the insert failed.
 *
 * <p>These constants exist so the choice is written down and named rather than appearing
 * as an unexplained number beside one field. They are MySQL's documented maxima:
 * exceeding {@link #TEXT} promotes the column to {@code MEDIUMTEXT}, and so on.
 *
 * <p>This is not theoretical. The {@code EntityMappingValidationTest} caught exactly this
 * on its first run — {@code bot_sources.raw} was mapped as {@code tinyblob} against a
 * {@code MEDIUMBLOB} column — which is precisely the drift an H2 suite generating its
 * schema from these same classes could never have seen.
 */
public final class ColumnSizes {

    /** 64 KB — MySQL {@code TEXT}. */
    public static final int TEXT = 65_535;

    /** 16 MB — MySQL {@code MEDIUMTEXT} and {@code MEDIUMBLOB}. */
    public static final int MEDIUM = 16_777_215;

    private ColumnSizes() {
        // constants only — no instances
    }
}
