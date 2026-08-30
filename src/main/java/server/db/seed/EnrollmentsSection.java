package server.db.seed;

import org.hibernate.Session;
import server.db.entities.Enrollment;

import java.util.List;
import java.util.Map;

/**
 * Seed §6: which students are in which courses (E2.15).
 *
 * <p><b>Forty-seven</b> rows, each student in three to five courses. Seed §6 states no row
 * total, only per-course figures, and those are the load-bearing numbers rather than their
 * sum: <b>course 11 has exactly eight students</b>, which is the roster of the fully graded
 * execution, and eight grades spread across five deciles is what makes the F9.3 histogram read
 * as a real class instead of a stub. Course 21 also has eight, matching execution 2; 12 has
 * six and 22 has seven. {@code SeedDatasetContract} asserts each course separately so the total
 * cannot be satisfied by the wrong distribution.
 *
 * <p><b>⚑ U-42 (2026-08-30, live session) added eighteen rows</b>, six in each of Biology 31,
 * Chemistry 41 and Physics 51. They are spread over the whole roster rather than the convenient
 * half: six students gain two courses and six gain one, so nobody sits in two while somebody
 * else sits in five, and no new course's roster is a copy of another's. {@code maya.levi}'s and
 * {@code noam.peretz}'s enrolments keep every course {@code DEMO_ACCOUNTS.md} fixes for them and
 * gain one, because that file states which courses they are in and not that those are all of
 * them.
 *
 * <p><b>Six per course, not eight, and Biology's six is the load-bearing one.</b> It is the
 * roster of execution 7 (§9.6), which five of the six sat: a roster of six with five attempts is
 * the shape that makes "who did not sit it" answerable on a screen, where §9.1's eight-of-eight
 * makes the roster and the attempt list identical. The dataset now carries both shapes.
 */
final class EnrollmentsSection implements SeedSection {

    /** Student username to the courses they are enrolled in, exactly as seed §6 lists them. */
    private static final Map<String, List<String>> ENROLMENTS = new java.util.LinkedHashMap<>();

    static {
        ENROLMENTS.put("noa.friedman", List.of("11", "21", "31", "51"));
        ENROLMENTS.put("itay.regev", List.of("11", "12", "21", "41", "51"));
        ENROLMENTS.put("shira.dahan", List.of("11", "22", "31"));
        ENROLMENTS.put("omer.katz", List.of("11", "21", "22", "31"));
        ENROLMENTS.put("maya.levi", List.of("11", "21", "22", "31"));
        ENROLMENTS.put("noam.peretz", List.of("12", "21", "41"));
        ENROLMENTS.put("yael.azulay", List.of("11", "12", "22", "41", "51"));
        ENROLMENTS.put("daniel.shapira", List.of("11", "21", "41"));
        ENROLMENTS.put("lior.gabay", List.of("11", "12", "31", "51"));
        ENROLMENTS.put("tal.harari", List.of("12", "22", "31", "51"));
        ENROLMENTS.put("roni.malka", List.of("21", "22", "41", "51"));
        ENROLMENTS.put("eitan.solomon", List.of("12", "21", "22", "41"));
    }

    @Override
    public String name() {
        return "6 enrollments";
    }

    @Override
    public void load(SeedContext context) {
        Session session = context.session();
        int inserted = 0;

        for (Map.Entry<String, List<String>> student : ENROLMENTS.entrySet()) {
            long studentId = SeedLookup.requireUserId(session, student.getKey());
            for (String course : student.getValue()) {
                if (!enrolledAlready(session, course, studentId)) {
                    session.persist(new Enrollment(course, studentId));
                    inserted++;
                }
            }
        }

        context.recordInserts("enrollments", inserted);
    }

    private static boolean enrolledAlready(Session session, String course, long studentId) {
        return session.createQuery("""
                        select count(e) from Enrollment e
                        where e.id.courseCode = :course and e.id.studentId = :student
                        """, Long.class)
                .setParameter("course", course)
                .setParameter("student", studentId)
                .getSingleResult() > 0;
    }
}
