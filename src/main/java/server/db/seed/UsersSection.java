package server.db.seed;

import at.favre.lib.crypto.bcrypt.BCrypt;
import server.db.entities.User;
import server.db.entities.UserRole;

import java.util.List;

/**
 * Seed §3: the eighteen people (E2.15, E2.17).
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
 * matters because a reseed happens minutes before a demo: eighteen hashes at cost 12 would be
 * a noticeable pause where cost 10 is not.
 *
 * <p><b>Hashed per user, not once and copied.</b> The plaintext is the same for all eighteen,
 * so a single {@code hashToString} call would put one identical string in eighteen rows.
 * BCrypt generates a fresh salt per call, so hashing each user separately produces eighteen
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
            new SeedUser("principal.avia", "אביה שלו", UserRole.PRINCIPAL, "301548202"),
            new SeedUser("dana.cohen", "דנה כהן", UserRole.TEACHER, "214703951"),
            new SeedUser("rina.barak", "רינה ברק", UserRole.TEACHER, "248190639"),
            new SeedUser("avi.mizrahi", "אבי מזרחי", UserRole.TEACHER, "273056416"),
            new SeedUser("tamar.shani", "תמר שני", UserRole.TEACHER, "296481724"),
            new SeedUser("michal.sharon", "מיכל שרון", UserRole.TEACHER, "315729046"),
            new SeedUser("noa.friedman", "נועה פרידמן", UserRole.STUDENT, "338106727"),
            new SeedUser("itay.regev", "איתי רגב", UserRole.STUDENT, "349251082"),
            new SeedUser("shira.dahan", "שירה דהן", UserRole.STUDENT, "352074611"),
            new SeedUser("omer.katz", "עומר כץ", UserRole.STUDENT, "361489206"),
            new SeedUser("maya.levi", "מאיה לוי", UserRole.STUDENT, "374301851"),
            new SeedUser("noam.peretz", "נועם פרץ", UserRole.STUDENT, "385612098"),
            new SeedUser("yael.azulay", "יעל אזולאי", UserRole.STUDENT, "390745362"),
            new SeedUser("daniel.shapira", "דניאל שפירא", UserRole.STUDENT, "402186936"),
            new SeedUser("lior.gabay", "ליאור גבאי", UserRole.STUDENT, "413860529"),
            new SeedUser("tal.harari", "טל הררי", UserRole.STUDENT, "425097185"),
            new SeedUser("roni.malka", "רוני מלכה", UserRole.STUDENT, "436712400"),
            new SeedUser("eitan.solomon", "איתן סולומון", UserRole.STUDENT, "448521062"));

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
