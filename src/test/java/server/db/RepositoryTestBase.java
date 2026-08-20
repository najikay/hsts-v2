package server.db;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import server.db.entities.Coordinator;
import server.db.entities.Course;
import server.db.entities.CourseTeacher;
import server.db.entities.Enrollment;
import server.db.entities.Subject;
import server.db.entities.User;
import server.db.entities.UserRole;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Template Method base for every repository test (E2.13).
 *
 * <p>The template is fixed here — open a database once, then wipe and reseed before each
 * test — and subclasses supply only {@link #openDatabase()}. That is what lets one set of
 * tests run against both engines: an abstract {@code …RepositoryContract} holds the
 * assertions, and two three-line subclasses bind it to H2 and to MySQL. Tests that only
 * mean something on one engine (foreign keys, CHECK constraints, collation, concurrency)
 * live in the MySQL subclass, where they can actually fail.
 *
 * <h2>Wiping</h2>
 *
 * <p>Delegated to {@link TestSchema#wipe}, which deletes in reverse dependency order and
 * never disables {@code FOREIGN_KEY_CHECKS} — see that class for why. It lives there rather
 * than here because the notification store contract needs the same wipe against the same
 * shared MySQL schema without inheriting this fixture.
 *
 * <h2>The fixture</h2>
 *
 * <p>Small on purpose — two subjects, four courses, four users and their memberships. It is
 * the reference data almost every query needs and nothing else; a test that needs questions
 * or exams builds them itself, so what it depends on is visible in the test rather than
 * buried here. The ids are captured after insert because the schema generates them.
 *
 * <p>{@code dana} is deliberately both a teacher of two courses <em>and</em> enrolled in a
 * third. That combination is what distinguishes "courses taught <b>or</b> enrolled in" from
 * "taught <b>and</b> enrolled", and the user directory (E2.11) has to surface both.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class RepositoryTestBase {

    protected static final String SUBJECT_MATH = "10";
    protected static final String SUBJECT_CS = "20";
    protected static final String COURSE_ALGEBRA = "11";
    protected static final String COURSE_CALCULUS = "12";
    protected static final String COURSE_JAVA = "21";
    protected static final String COURSE_DATABASES = "22";

    /** Not a real hash: no test here verifies a password, and a real one costs 100ms each. */
    protected static final String FAKE_HASH = "$2a$10$notarealbcrypthashusedonlybythefixture000000000000000";

    /** Teaches Algebra and Calculus, and is enrolled in Databases. */
    protected long danaId;
    /** Teaches Calculus, and coordinates Mathematics - so her wire role is COORDINATOR. */
    protected long rinaId;
    /** Enrolled in Algebra and Java. */
    protected long mayaId;
    /** Teaches nothing, coordinates nothing. */
    protected long principalId;

    private TestDatabase database;

    /**
     * The template hook: which database this run uses.
     *
     * @return an open database; this class closes it when the test class finishes
     */
    protected abstract TestDatabase openDatabase();

    @BeforeAll
    final void openDatabaseOnce() {
        database = openDatabase();
    }

    @AfterAll
    final void closeDatabaseOnce() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    final void wipeAndSeed() {
        wipe();
        seed();
    }

    /** @return the factory for the database this test class is bound to */
    protected final SessionFactory factory() {
        return database.factory();
    }

    /** Runs work in a transaction on this test's database. */
    protected final <T> T inTx(Function<Session, T> work) {
        return Transactions.inTx(factory(), work);
    }

    /** Runs work in a transaction on this test's database, returning nothing. */
    protected final void runInTx(Consumer<Session> work) {
        Transactions.runInTx(factory(), work);
    }

    /**
     * Deletes every row in every table, children first.
     *
     * <p>Protected rather than private so the wipe order itself can be tested: a test that
     * fills all twenty tables and then calls this proves the order is right, which nothing
     * else here would notice until a repository test failed for an unrelated-looking reason.
     */
    protected final void wipe() {
        TestSchema.wipe(factory());
    }

    private void seed() {
        runInTx(session -> {
            session.persist(new Subject(SUBJECT_MATH, "מתמטיקה"));
            session.persist(new Subject(SUBJECT_CS, "מדעי המחשב"));
            session.persist(new Course(COURSE_ALGEBRA, SUBJECT_MATH, "אלגברה"));
            session.persist(new Course(COURSE_CALCULUS, SUBJECT_MATH, "חשבון דיפרנציאלי"));
            session.persist(new Course(COURSE_JAVA, SUBJECT_CS, "תכנות מונחה עצמים"));
            session.persist(new Course(COURSE_DATABASES, SUBJECT_CS, "בסיסי נתונים"));

            User dana = new User("dana.cohen", FAKE_HASH, "דנה כהן", UserRole.TEACHER, "214703951");
            User rina = new User("rina.barak", FAKE_HASH, "רינה ברק", UserRole.TEACHER, "248190639");
            User maya = new User("maya.levi", FAKE_HASH, "מאיה לוי", UserRole.STUDENT, "374301851");
            User avia = new User("principal.avia", FAKE_HASH, "אביה שלו", UserRole.PRINCIPAL, "301548202");
            session.persist(dana);
            session.persist(rina);
            session.persist(maya);
            session.persist(avia);
            session.flush();

            danaId = dana.getId();
            rinaId = rina.getId();
            mayaId = maya.getId();
            principalId = avia.getId();

            session.persist(new CourseTeacher(COURSE_ALGEBRA, danaId));
            session.persist(new CourseTeacher(COURSE_CALCULUS, danaId));
            session.persist(new CourseTeacher(COURSE_CALCULUS, rinaId));

            session.persist(new Enrollment(COURSE_ALGEBRA, mayaId));
            session.persist(new Enrollment(COURSE_JAVA, mayaId));
            session.persist(new Enrollment(COURSE_DATABASES, danaId));

            session.persist(new Coordinator(SUBJECT_MATH, rinaId));
        });
    }
}
