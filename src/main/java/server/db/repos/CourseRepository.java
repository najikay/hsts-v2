package server.db.repos;

import org.hibernate.Session;
import server.db.projections.CourseSummary;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Reads over {@code courses} and its two membership tables (E2.11).
 */
public final class CourseRepository {

    /**
     * Every course this user is attached to, whether by teaching it or by being enrolled in
     * it.
     *
     * <p><b>Both, not either.</b> {@code UserRecord}'s javadoc describes its courses as
     * "taught (teacher/coordinator) <em>or</em> enrolled in (student)", which reads as a
     * choice made by role. It is not: a teacher can be enrolled in another teacher's course,
     * and the nav has to show both. The union is taken here so no caller has to remember it.
     *
     * <p>Two queries rather than one HQL union: the union keeps the query portable across H2
     * and MySQL, and the merge is where the ordering is made deterministic.
     *
     * <p>Consumer: {@code RepositoryUserDirectory} building a login result.
     *
     * @param session the current session
     * @param userId  the user's internal id
     * @return the courses, by code, without duplicates
     */
    public List<CourseSummary> findForUser(Session session, long userId) {
        LinkedHashSet<CourseSummary> merged = new LinkedHashSet<>();
        merged.addAll(query(session, """
                select new server.db.projections.CourseSummary(c.code, c.name)
                from Course c, CourseTeacher ct
                where ct.id.courseCode = c.code and ct.id.teacherId = :userId
                order by c.code
                """, userId));
        merged.addAll(query(session, """
                select new server.db.projections.CourseSummary(c.code, c.name)
                from Course c, Enrollment e
                where e.id.courseCode = c.code and e.id.studentId = :userId
                order by c.code
                """, userId));
        return List.copyOf(merged);
    }

    /**
     * Whether this student may sit an exam of this course (S-15, F6.1).
     *
     * <p>A separate read rather than a scan of {@link #findForUser}, because that method
     * deliberately unions teaching and enrolment and a teacher who happens to teach a
     * course is emphatically not enrolled in it. The take-exam gate needs the narrow
     * question, and asking the wide one and filtering afterwards is how the wrong answer
     * eventually gets used.
     *
     * <p>Consumer: E10 join-by-code, which refuses a student who is not on the course with
     * its own message rather than a generic "no".
     *
     * @param session    the current session
     * @param studentId  the student
     * @param courseCode the 2-character course code
     * @return {@code true} when there is an enrolment row
     */
    public boolean isEnrolled(Session session, long studentId, String courseCode) {
        if (courseCode == null || courseCode.isBlank()) {
            return false;
        }
        return session.createQuery("""
                        select count(e) from Enrollment e
                        where e.id.studentId = :studentId and e.id.courseCode = :courseCode
                        """, Long.class)
                .setParameter("studentId", studentId)
                .setParameter("courseCode", courseCode)
                .getSingleResult() > 0;
    }

    private static List<CourseSummary> query(Session session, String hql, long userId) {
        return session.createQuery(hql, CourseSummary.class)
                .setParameter("userId", userId)
                .getResultList();
    }
}
