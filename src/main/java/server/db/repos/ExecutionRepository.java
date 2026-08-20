package server.db.repos;

import org.hibernate.Session;
import server.db.entities.ExamExecution;
import server.db.entities.ExecutionStatus;

import java.util.List;
import java.util.Optional;

/** Reads over {@code exam_executions} (E2.11). */
public final class ExecutionRepository {

    /**
     * Finds the executions using a 4-character code.
     *
     * <p>Returns a list, not one row, on purpose. Code uniqueness holds only among
     * non-closed executions and §5 states it is a <b>service</b> rule, because MySQL has no
     * partial unique index: a code is legitimately reused once its execution closes. A
     * method returning {@code Optional} here would be asserting a guarantee the schema does
     * not make, and would throw on perfectly valid data.
     *
     * <p>Codes are compared case-insensitively — students type them, and production
     * collation does this anyway while H2 does not.
     *
     * <p>Consumer: E10 join-by-code (C-1, S-18).
     *
     * @param session the current session
     * @param code    the 4-character code as typed
     * @return matching executions, newest first
     */
    public List<ExamExecution> findByCode(Session session, String code) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        return session.createQuery("""
                        from ExamExecution where lower(code) = lower(:code) order by openAt desc
                        """, ExamExecution.class)
                .setParameter("code", code.trim())
                .getResultList();
    }

    /**
     * The one execution a student may currently join with this code.
     *
     * <p>Consumer: E10 join-by-code, which needs exactly the live one.
     *
     * @param session the current session
     * @param code    the 4-character code as typed
     * @return the live execution, or empty
     */
    public Optional<ExamExecution> findJoinable(Session session, String code) {
        return findByCode(session, code).stream()
                .filter(execution -> execution.getStatus() == ExecutionStatus.LIVE)
                .findFirst();
    }
}
