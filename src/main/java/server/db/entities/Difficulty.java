package server.db.entities;

/**
 * Question difficulty — {@code question_versions.difficulty} (V2, ARCHITECTURE §5).
 *
 * <p>Part of the auto-generation criteria in F3.3 / S-13: the generator asks the bank
 * for a mix by topic and difficulty, and reports precisely which combination it could
 * not satisfy when the request is infeasible.
 */
public enum Difficulty {
    EASY,
    MEDIUM,
    HARD
}
