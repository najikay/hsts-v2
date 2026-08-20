package server.db.seed;

import org.hibernate.Session;
import server.db.entities.Coordinator;
import server.db.entities.CourseTeacher;

import java.util.List;

/**
 * Seed §4 and §5: who teaches what, and who coordinates each subject (E2.15).
 *
 * <p>These two rows-per-pair tables are what turn eighteen flat user rows into the demo's
 * shape, and the arrangement is deliberate rather than arbitrary.
 *
 * <h2>Two shapes of coordinator, on purpose (roster decision, 2026-08-20)</h2>
 *
 * <p>{@code rina.barak} is the <b>pure coordinator</b>: a {@code coordinators} row for subject
 * 10 and <b>zero {@code course_teachers} rows</b>. She approves {@code dana.cohen}'s Algebra
 * and Calculus exams and teaches nothing. {@code michal.sharon} is the <b>dual-hat</b> case:
 * she teaches Databases 22 and coordinates Computer Science 20.
 *
 * <p>Having both is what makes the derived wire role provable. Coordinator-ness lives entirely
 * in the {@code coordinators} table, so a teacher with no courses must still resolve to
 * {@code Role.COORDINATOR} at login. If every coordinator also taught something, an
 * implementation that accidentally derived the role from {@code course_teachers} would pass
 * every test. Rina is the row that catches it.
 *
 * <p>She previously co-taught Calculus; the seed document and {@code DEMO_ACCOUNTS.md} were
 * both updated on {@code main} when the decision was taken. Nothing else in the seed moved,
 * because she was already the approver-never-author by design.
 *
 * <p>Both tables have composite primary keys made of the natural keys themselves, so
 * idempotency is a lookup on the pair rather than on a surrogate id.
 */
final class FacultySection implements SeedSection {

    private record Teaches(String course, String username) { }

    private record Coordinates(String subject, String username) { }

    private static final List<Teaches> COURSE_TEACHERS = List.of(
            new Teaches("11", "dana.cohen"),
            // dana.cohen teaches Calculus alone. rina.barak co-taught it until the roster
            // decision of 2026-08-20, which made her a pure coordinator with no
            // course_teachers row at all. See the class javadoc for why that matters.
            new Teaches("12", "dana.cohen"),
            new Teaches("21", "avi.mizrahi"),
            new Teaches("21", "tamar.shani"),
            new Teaches("22", "michal.sharon"));

    private static final List<Coordinates> COORDINATORS = List.of(
            new Coordinates("10", "rina.barak"),
            new Coordinates("20", "michal.sharon"));

    @Override
    public String name() {
        return "4-5 course teachers and coordinators";
    }

    @Override
    public void load(SeedContext context) {
        Session session = context.session();

        int teachers = 0;
        for (Teaches row : COURSE_TEACHERS) {
            long teacherId = SeedLookup.requireUserId(session, row.username());
            if (!teachesAlready(session, row.course(), teacherId)) {
                session.persist(new CourseTeacher(row.course(), teacherId));
                teachers++;
            }
        }
        context.recordInserts("course_teachers", teachers);

        int coordinators = 0;
        for (Coordinates row : COORDINATORS) {
            if (!coordinatesAlready(session, row.subject())) {
                session.persist(new Coordinator(row.subject(),
                        SeedLookup.requireUserId(session, row.username())));
                coordinators++;
            }
        }
        context.recordInserts("coordinators", coordinators);
    }

    private static boolean teachesAlready(Session session, String course, long teacherId) {
        return session.createQuery("""
                        select count(ct) from CourseTeacher ct
                        where ct.id.courseCode = :course and ct.id.teacherId = :teacher
                        """, Long.class)
                .setParameter("course", course)
                .setParameter("teacher", teacherId)
                .getSingleResult() > 0;
    }

    /** Keyed on the subject alone: {@code pk_coordinators} allows exactly one per subject (S-1). */
    private static boolean coordinatesAlready(Session session, String subject) {
        return session.createQuery("""
                        select count(c) from Coordinator c where c.subjectCode = :subject
                        """, Long.class)
                .setParameter("subject", subject)
                .getSingleResult() > 0;
    }
}
