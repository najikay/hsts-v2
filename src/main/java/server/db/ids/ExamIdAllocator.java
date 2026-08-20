package server.db.ids;

import org.hibernate.Session;

/**
 * Allocates the 6-digit exam display id: subject(2) + course(2) + serial(2) — S-10.
 *
 * <p>The subject is not stored on {@code exams}; it is read from the course row that is
 * being locked anyway, so the allocation costs no extra query.
 */
public final class ExamIdAllocator {

    /** {@code display_id6} is CHAR(6): subject, course, then only two for the serial. */
    static final int MAX_SERIAL = 99;

    /**
     * Allocates the next exam id for a course.
     *
     * <p>Must be called inside a transaction — see {@link QuestionIdAllocator#allocate}.
     *
     * @param session    the session inside the allocating transaction
     * @param courseCode the 2-character course code
     * @return the next serial and its display id
     * @throws IllegalArgumentException when no such course exists
     * @throws IllegalStateException    when the course has used all 99 serials
     */
    public AllocatedId allocate(Session session, String courseCode) {
        String subjectCode = CourseLock.lockAndReadSubject(session, courseCode);

        Integer highest = session.createNativeQuery(
                        "SELECT MAX(serial2) FROM exams WHERE course = :course", Integer.class)
                .setParameter("course", courseCode)
                .uniqueResult();

        int next = (highest == null ? 0 : highest) + 1;
        if (next > MAX_SERIAL) {
            throw new IllegalStateException("course " + courseCode + " has used all "
                    + MAX_SERIAL + " exam serials; display_id6 cannot represent " + next);
        }
        return new AllocatedId(next, subjectCode + courseCode + String.format("%02d", next));
    }
}
