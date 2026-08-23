package server.features.release;

import common.dto.release.ReleaseCreateRequest;

import java.util.Objects;
import java.util.Random;
import java.util.function.Predicate;

/**
 * The 4-character code a teacher reads out (Logic tier, E9.1 — C-1, S-16, S-17).
 *
 * <h2>Who picks it</h2>
 *
 * <p><b>The teacher, when she wants to.</b> §4 says she defines the code and T-5.3 has her
 * typing one, so the create dialog has a field and {@code ReleaseCreateRequest.code} carries
 * it. This class is what happens when she leaves it blank, plus the shape rule both tiers
 * share.
 *
 * <p>What the server owns is the <b>check</b>, not the choice. §5 makes uniqueness a service
 * rule because MySQL has no partial unique index and the constraint is partial (a code is free
 * again once its sitting is over), so "is this code free" can only be answered inside the
 * transaction that inserts — for a code she typed and for one this class rolled alike. A
 * supplied code that clashes comes back as {@code ReleaseCodeIssue.TAKEN} naming the way out;
 * a rolled one simply gets rolled again.
 *
 * <h2>The alphabet</h2>
 *
 * <p>Upper-case letters and digits, minus the four characters that are read out loud wrongly:
 * {@code O}/{@code 0} and {@code I}/{@code 1}. A code exists to survive being spoken across
 * a room and typed by thirty people under time pressure (S-17), and "oh or zero?" costs a
 * student minutes she cannot get back. Dropping them leaves 32 symbols and about 1.05
 * million codes, against a school that has a handful open at once.
 *
 * <p>The alphabet constrains <b>generation only</b>. A code a teacher typed is judged by
 * {@link #isWellFormed}, which is C-1's wide rule, so {@code 4821} and {@code IO01} are both
 * hers to choose even though this class would never roll them.
 *
 * <p>Codes are always stored upper-case and compared case-insensitively, so a student who
 * types lower case is right (C-1).
 *
 * <h2>Collisions</h2>
 *
 * <p>{@link #generate} re-rolls against the caller's "is this taken" predicate rather than
 * relying on a constraint, because there is no constraint to rely on. With a handful of live
 * releases a collision is a one-in-thirty-thousand event, so the loop effectively never runs
 * twice; the bound exists so a pathological state ends in a sentence rather than a hang.
 */
public final class ExecutionCodes {

    /**
     * The symbols a code is built from: 2-9 and A-Z without I and O.
     *
     * <p>Spoken-alphabet safe, per the class note. Ordering is irrelevant to correctness but
     * fixed so a seeded {@link Random} produces the same code twice, which is what lets a
     * test assert an exact code instead of a pattern.
     */
    public static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    /** Code length (C-1). Four characters, whatever the alphabet becomes. */
    public static final int LENGTH = 4;

    /**
     * How many times a collision is re-rolled before giving up.
     *
     * <p>Twenty, which with a million-symbol space is not a number anybody reaches by bad
     * luck: it is the number that turns "every code in the school is taken" from an infinite
     * loop into {@link ReleaseMessages#CODE_EXHAUSTED}.
     */
    public static final int MAX_ATTEMPTS = 20;

    private ExecutionCodes() {
    }

    /**
     * Generates a code no in-use release is holding.
     *
     * @param random the source of randomness; a seeded one in tests
     * @param taken  answers whether a candidate is already spoken for. Called inside the
     *               same transaction as the insert that follows, which is what makes the
     *               answer worth anything
     * @return a free code, upper case
     * @throws IllegalStateException after {@link #MAX_ATTEMPTS} collisions; the service
     *         turns that into {@link ReleaseMessages#CODE_EXHAUSTED} rather than a stack trace
     */
    public static String generate(Random random, Predicate<String> taken) {
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(taken, "taken");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = roll(random);
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No free execution code after " + MAX_ATTEMPTS + " attempts");
    }

    /**
     * One roll, without asking whether it is free.
     *
     * @param random the source of randomness
     * @return four characters from {@link #ALPHABET}
     */
    public static String roll(Random random) {
        Objects.requireNonNull(random, "random");
        StringBuilder code = new StringBuilder(LENGTH);
        for (int index = 0; index < LENGTH; index++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * Whether a string is a code this product would accept (C-1).
     *
     * <p>Delegates to {@link ReleaseCreateRequest#isWellFormedCode}, which is on the wire
     * because the create dialog runs the same rule as the teacher types. One definition, two
     * names: this one is what server code reaches for, and it must never drift from what the
     * client greys its button on.
     *
     * <p>Deliberately the <b>wide</b> rule, {@code [A-Za-z0-9]{4}}, rather than "made of
     * {@link #ALPHABET}". The narrow alphabet is a generation choice about being read aloud;
     * the seed and the demo both carry all-digit codes, and T-5.3 has a teacher typing
     * {@code 4821}. Refusing those would be this class's taste overriding the contract.
     *
     * @param code a candidate, as typed
     * @return {@code true} when it is four letters or digits
     */
    public static boolean isWellFormed(String code) {
        return ReleaseCreateRequest.isWellFormedCode(code);
    }

    /**
     * @param code a code as typed
     * @return it trimmed and upper case, the one form it is stored in (C-1). Blank answers
     *         empty rather than {@code null}, because a server-side caller normalising a code
     *         it already knows is present has nothing to branch on
     */
    public static String normalize(String code) {
        String normalized = ReleaseCreateRequest.normalizeCode(code);
        return normalized == null ? "" : normalized;
    }
}
