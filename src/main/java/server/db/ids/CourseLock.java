package server.db.ids;

import org.hibernate.Session;

/**
 * Serialises allocation for one course by locking its {@code courses} row.
 *
 * <p>Shared by both allocators so the locking rule is written once. Locking the parent row
 * rather than the child table means two allocations in different courses proceed in
 * parallel, which is the common case while several teachers author at once.
 */
final class CourseLock {

    private CourseLock() {
        // static helper - no instances
    }

    /**
     * Takes an exclusive lock on the course row and returns its subject.
     *
     * @param session    the session inside the allocating transaction
     * @param courseCode the 2-character course code
     * @return the course's subject code
     * @throws IllegalArgumentException when no such course exists, which is a programming
     *                                  error rather than a race: courses are seeded and
     *                                  read-only (S-3)
     */
    static String lockAndReadSubject(Session session, String courseCode) {
        // FOR UPDATE holds until the surrounding transaction ends, so every allocation for
        // this course queues behind whoever got here first.
        Object subject = session.createNativeQuery(
                        "SELECT subject_code FROM courses WHERE code2 = :course FOR UPDATE", String.class)
                .setParameter("course", courseCode)
                .uniqueResult();

        if (subject == null) {
            throw new IllegalArgumentException("no such course: " + courseCode);
        }
        return (String) subject;
    }
}
