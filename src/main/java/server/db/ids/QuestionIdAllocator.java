package server.db.ids;

import org.hibernate.Session;

/**
 * Allocates the 5-digit question display id: course(2) + serial(3) — S-8.
 *
 * <p>Called by E6 when a teacher creates a question, and by the E2.15 seed loader. See
 * {@code package-info} for why this is {@code MAX + 1} under a row lock rather than
 * {@code COUNT + 1}.
 */
public final class QuestionIdAllocator {

    /** {@code display_id5} is CHAR(5): two for the course, three for the serial. */
    static final int MAX_SERIAL = 999;

    /**
     * Allocates the next question id for a course.
     *
     * <p>Must be called inside a transaction: the row lock it takes is released when that
     * transaction ends, and outside one it would be released immediately, which would make
     * the serialisation silently useless.
     *
     * @param session    the session inside the allocating transaction
     * @param courseCode the 2-character course code
     * @return the next serial and its display id
     * @throws IllegalArgumentException when no such course exists
     * @throws IllegalStateException    when the course has used all 999 serials
     */
    public AllocatedId allocate(Session session, String courseCode) {
        CourseLock.lockAndReadSubject(session, courseCode);

        // MAX, not COUNT: a soft-deleted question keeps its serial (F2.5), so counting rows
        // would hand out a number that is already taken.
        Integer highest = session.createNativeQuery(
                        "SELECT MAX(serial3) FROM questions WHERE course = :course", Integer.class)
                .setParameter("course", courseCode)
                .uniqueResult();

        int next = (highest == null ? 0 : highest) + 1;
        if (next > MAX_SERIAL) {
            throw new IllegalStateException("course " + courseCode + " has used all "
                    + MAX_SERIAL + " question serials; display_id5 cannot represent " + next);
        }
        return new AllocatedId(next, courseCode + String.format("%03d", next));
    }
}
