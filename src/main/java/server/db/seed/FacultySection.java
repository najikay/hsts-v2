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
 * <h2>⚑ U-42: three more dual-hat coordinators, and one consequence worth naming</h2>
 *
 * <p>2026-08-30, live session. Biology 31, Chemistry 41 and Physics 51 each get one teacher, and
 * that teacher coordinates her own subject. Each of them is therefore <b>the approver of her own
 * exams</b>, which nothing forbids - the {@code coordinators} primary key is the subject alone,
 * and S-1 asks for a coordinator per subject rather than for a second teacher to exist - but it
 * is the opposite of the approval story the demo tells, so it is recorded here rather than
 * discovered. That story stays on Mathematics and Computer Science, where {@code rina.barak}
 * approves what {@code dana.cohen} wrote and {@code michal.sharon} approves what the Java
 * teachers wrote, and where every approval and rejection fixture in seed §8.2 lives. The three
 * new subjects are breadth for the pickers and the reports; giving each a second teacher purely
 * so the approver could differ would have added three users nothing else in the dataset uses.
 *
 * <p><b>Java stays the only co-taught course</b>, deliberately: seed §7's authorship rule
 * resolves a second question version to the co-teacher, and that clause is proven by firing on
 * exactly one row ({@code 21003} v2). A second co-taught course would give it two, which costs
 * nothing and buys a second place to keep the "exactly one" assertion honest.
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
            new Teaches("22", "michal.sharon"),
            // ⚑ U-42. One teacher per new course, and she coordinates the subject below.
            new Teaches("31", "galit.stern"),
            new Teaches("41", "orly.navon"),
            new Teaches("51", "sivan.adler"));

    private static final List<Coordinates> COORDINATORS = List.of(
            new Coordinates("10", "rina.barak"),
            new Coordinates("20", "michal.sharon"),
            // ⚑ U-42. Dual-hat, like michal.sharon: each teaches her subject's only course.
            new Coordinates("30", "galit.stern"),
            new Coordinates("40", "orly.navon"),
            new Coordinates("50", "sivan.adler"));

    /**
     * A course's teachers in §4's document order.
     *
     * <p>Exposed because §7's authorship rule is defined in terms of that order: "v1 is the
     * course's <b>first-listed</b> teacher in §4" and "a second version in a co-taught course is
     * the <b>co-teacher</b>". {@code course_teachers} has no ordering column and no reason to
     * gain one, so the order lives here, in the section that transcribes §4, and
     * {@link QuestionBankSection} reads it rather than keeping a second copy that could disagree
     * after a roster change.
     *
     * @param course the two-character course code
     * @return its teachers' usernames, first-listed first
     */
    static List<String> teachersOf(String course) {
        return COURSE_TEACHERS.stream()
                .filter(row -> row.course().equals(course))
                .map(Teaches::username)
                .toList();
    }

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
