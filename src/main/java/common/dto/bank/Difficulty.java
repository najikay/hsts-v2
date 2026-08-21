package common.dto.bank;

/**
 * How hard a bank question is, as it travels on the wire (Common tier, E6).
 *
 * <p>Same three members, same three names as {@code server.db.entities.Difficulty}, and
 * deliberately a second type. The rule the whole wire model rests on is that no entity type
 * travels: an enum is the most tempting exception, because the duplication is visible and the
 * mapping is one {@code valueOf} at the service boundary. It is still an exception, and the day
 * the entity gains a member the persistence layer needs, the wire would gain it too without
 * anyone deciding to.
 *
 * <p>Enums serialize by name, so the two stay interchangeable only for as long as the names
 * agree. {@code BankDtoTest} asserts member-for-member equality with the entity enum, which is
 * the check that turns "they happen to match" into "they are kept matching".
 */
public enum Difficulty {

    /** Recall or one-step questions. */
    EASY,

    /** The default weight of a bank, and what an auto-generated exam is mostly made of. */
    MEDIUM,

    /** Multi-step or synthesis questions. */
    HARD
}
