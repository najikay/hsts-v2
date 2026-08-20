package server.db.seed;

import org.hibernate.Session;
import server.db.entities.Enrollment;

import java.util.List;
import java.util.Map;

/**
 * Seed §6: which students are in which courses (E2.15).
 *
 * <p><b>Twenty-nine</b> rows, each student in two or three courses. Seed §6 states no row
 * total, only per-course figures, and those are the load-bearing numbers rather than their
 * sum: <b>course 11 has exactly eight students</b>, which is the roster of the fully graded
 * execution, and eight grades spread across five deciles is what makes the F9.3 histogram read
 * as a real class instead of a stub. Course 21 also has eight, matching execution 2; 12 has
 * six and 22 has seven. 8 + 6 + 8 + 7 is where the 29 comes from, and
 * {@code SeedDatasetContract} asserts each of the four separately so the total cannot be
 * satisfied by the wrong distribution.
 */
final class EnrollmentsSection implements SeedSection {

    /** Student username to the courses they are enrolled in, exactly as seed §6 lists them. */
    private static final Map<String, List<String>> ENROLMENTS = new java.util.LinkedHashMap<>();

    static {
        ENROLMENTS.put("noa.friedman", List.of("11", "21"));
        ENROLMENTS.put("itay.regev", List.of("11", "12", "21"));
        ENROLMENTS.put("shira.dahan", List.of("11", "22"));
        ENROLMENTS.put("omer.katz", List.of("11", "21", "22"));
        ENROLMENTS.put("maya.levi", List.of("11", "21", "22"));
        ENROLMENTS.put("noam.peretz", List.of("12", "21"));
        ENROLMENTS.put("yael.azulay", List.of("11", "12", "22"));
        ENROLMENTS.put("daniel.shapira", List.of("11", "21"));
        ENROLMENTS.put("lior.gabay", List.of("11", "12"));
        ENROLMENTS.put("tal.harari", List.of("12", "22"));
        ENROLMENTS.put("roni.malka", List.of("21", "22"));
        ENROLMENTS.put("eitan.solomon", List.of("12", "21", "22"));
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
