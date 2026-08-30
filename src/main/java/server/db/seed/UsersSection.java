package server.db.seed;

import at.favre.lib.crypto.bcrypt.BCrypt;
import server.db.entities.User;
import server.db.entities.UserRole;

import java.util.List;

/**
 * Seed §3: the twenty-one people (E2.15, E2.17).
 *
 * <p><b>⚑ U-42 (2026-08-30, live session)</b> added rows 19, 20 and 21: one TEACHER per new
 * subject. Their national ids continue the synthetic ascending series and are checksum-valid
 * Israeli ids like every other one here, and each of them teaches her subject's only course and
 * coordinates that subject - three more of {@code michal.sharon}'s dual-hat shape, per
 * {@link FacultySection}.
 *
 * <h2>The password</h2>
 *
 * <p>Every seeded user gets {@code demo123}, hashed here rather than stored in the document.
 * The value is fixed by {@code docs/DEMO_ACCOUNTS.md}, which E5's fixture directory already
 * uses: keeping it means the demo script does not change when the fixture is replaced by the
 * database, which is the whole point of E2.17 being a re-point rather than a rewrite.
 *
 * <p>Cost {@link #BCRYPT_COST} matches {@code InMemoryUserDirectory.DEV_COST} deliberately, so
 * sign-in takes the same time before and after the swap. It also keeps a reseed quick, which
 * matters because a reseed happens minutes before a demo: twenty-one hashes at cost 12 would be
 * a noticeable pause where cost 10 is not.
 *
 * <p><b>Hashed per user, not once and copied.</b> The plaintext is the same for all twenty-one,
 * so a single {@code hashToString} call would put one identical string in twenty-one rows.
 * BCrypt generates a fresh salt per call, so hashing each user separately produces twenty-one
 * different stored hashes from one password, which is what real data looks like and what
 * anyone inspecting the table during a defense would expect to see. The cost is about two
 * seconds on an operation that runs rarely. {@code InMemoryUserDirectory} hashes once and
 * reuses because it is a five-user fixture optimising server boot; this is a database.
 *
 * <p>Not a secret: a shared demo password in a document is the opposite of a credential leak,
 * and there is nothing here to protect. The verification path is the real one either way.
 *
 * <h2>No COORDINATOR</h2>
 *
 * <p>{@code users.role} is {@code ENUM('STUDENT','TEACHER','PRINCIPAL')}. {@code rina.barak}
 * and {@code michal.sharon} are seeded TEACHER and become wire-role COORDINATOR through the
 * {@code coordinators} rows in {@link FacultySection}, exactly as ARCHITECTURE §5 round-2
 * requires. Coordinator-ness is per-subject state and can never drift from a stored role,
 * because there is no stored role to drift from.
 */
final class UsersSection implements SeedSection {

    /** Fixed by DEMO_ACCOUNTS.md and mirrored in seed §3. Demo credential, not a secret. */
    static final String SEED_PASSWORD = "demo123";

    /** Matches {@code InMemoryUserDirectory.DEV_COST}: same sign-in latency after the swap. */
    static final int BCRYPT_COST = 10;

    private record SeedUser(String username, String fullName, UserRole role, String nationalId) { }

    private static final List<SeedUser> USERS = List.of(
            new SeedUser("principal.avia", "Avia Shalev", UserRole.PRINCIPAL, "301548202"),
            new SeedUser("dana.cohen", "Dana Cohen", UserRole.TEACHER, "214703951"),
            new SeedUser("rina.barak", "Rina Barak", UserRole.TEACHER, "248190639"),
            new SeedUser("avi.mizrahi", "Avi Mizrahi", UserRole.TEACHER, "273056416"),
            new SeedUser("tamar.shani", "Tamar Shani", UserRole.TEACHER, "296481724"),
            new SeedUser("michal.sharon", "Michal Sharon", UserRole.TEACHER, "315729046"),
            new SeedUser("noa.friedman", "Noa Friedman", UserRole.STUDENT, "338106727"),
            new SeedUser("itay.regev", "Itay Regev", UserRole.STUDENT, "349251082"),
            new SeedUser("shira.dahan", "Shira Dahan", UserRole.STUDENT, "352074611"),
            new SeedUser("omer.katz", "Omer Katz", UserRole.STUDENT, "361489206"),
            new SeedUser("maya.levi", "Maya Levi", UserRole.STUDENT, "374301851"),
            new SeedUser("noam.peretz", "Noam Peretz", UserRole.STUDENT, "385612098"),
            new SeedUser("yael.azulay", "Yael Azulay", UserRole.STUDENT, "390745362"),
            new SeedUser("daniel.shapira", "Daniel Shapira", UserRole.STUDENT, "402186936"),
            new SeedUser("lior.gabay", "Lior Gabay", UserRole.STUDENT, "413860529"),
            new SeedUser("tal.harari", "Tal Harari", UserRole.STUDENT, "425097185"),
            new SeedUser("roni.malka", "Roni Malka", UserRole.STUDENT, "436712400"),
            new SeedUser("eitan.solomon", "Eitan Solomon", UserRole.STUDENT, "448521062"),
            // ⚑ U-42. One teacher per new subject; each coordinates her own (FacultySection).
            new SeedUser("galit.stern", "Galit Stern", UserRole.TEACHER, "451936272"),
            new SeedUser("orly.navon", "Orly Navon", UserRole.TEACHER, "460748155"),
            new SeedUser("sivan.adler", "Sivan Adler", UserRole.TEACHER, "471603944"));

    @Override
    public String name() {
        return "3 users";
    }

    @Override
    public void load(SeedContext context) {
        int inserted = 0;

        for (SeedUser user : USERS) {
            if (SeedLookup.findUserId(context.session(), user.username()).isPresent()) {
                continue;
            }
            context.session().persist(new User(user.username(), hashSeedPassword(),
                    user.fullName(), user.role(), user.nationalId()));
            inserted++;
        }

        context.recordInserts("users", inserted);
    }

    /** @return a BCrypt hash of the seed password at {@link #BCRYPT_COST} */
    static String hashSeedPassword() {
        return BCrypt.withDefaults().hashToString(BCRYPT_COST, SEED_PASSWORD.toCharArray());
    }
}
