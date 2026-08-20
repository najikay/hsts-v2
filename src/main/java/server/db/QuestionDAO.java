package server.db;

import common.dto.bank.Question;
import common.dto.bank.QuestionUpdate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for the {@code Questions} table (Data tier).
 *
 * <p>Raw JDBC with {@link PreparedStatement}. All persistence flows through this
 * class so the rest of the application is decoupled from SQL — and so a future
 * {@code ai_metadata JSON} column can be added here without touching callers.
 */
public class QuestionDAO {

    private static final String SQL_SELECT_ALL =
            "SELECT id, question_text, answer FROM Questions ORDER BY id";

    private static final String SQL_UPDATE =
            "UPDATE Questions SET question_text = ?, answer = ? WHERE id = ?";

    /** Value-based optimistic guard (E18.4); {@code <=>} is MySQL's null-safe equality. */
    private static final String SQL_UPDATE_GUARDED =
            "UPDATE Questions SET question_text = ?, answer = ? "
                    + "WHERE id = ? AND question_text <=> ? AND answer <=> ?";

    private static final String SQL_EXISTS = "SELECT 1 FROM Questions WHERE id = ?";

    /** Fetches all questions ordered by id. */
    public List<Question> getAll() {
        List<Question> questions = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_ALL);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                questions.add(new Question(
                        rs.getInt("id"),
                        rs.getString("question_text"),
                        rs.getString("answer")
                ));
            }
        } catch (SQLException e) {
            System.err.println("[QuestionDAO] getAll failed: " + e.getMessage());
            e.printStackTrace();
        }
        return questions;
    }

    /**
     * Updates an existing question's text and answer (matched by id).
     *
     * @return true if exactly one row was updated.
     */
    public boolean update(Question question) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPDATE)) {

            ps.setString(1, question.getQuestionText());
            ps.setString(2, question.getAnswer());
            ps.setInt(3, question.getId());

            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            System.err.println("[QuestionDAO] update failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /** What a guarded update did (E18.4). */
    public enum UpdateOutcome {
        /** The row still said what the client last read, and now says what they typed. */
        SAVED,
        /** Somebody else changed the row after this client read it. Nothing was written. */
        STALE,
        /** There is no such row any more. */
        MISSING,
        /** The database could not be reached or the statement failed. Nothing is known about the row. */
        FAILED
    }

    /**
     * Optimistic, value-based update (E18.4): writes only if the row still holds
     * the values the client read.
     *
     * <p>The {@code WHERE} clause carries the old values, so the database itself
     * decides the race — no read-then-write window, and no lock held across a
     * user's thinking time. A zero-row result then means one of two different
     * things, and the caller has to tell a user which, so a second query
     * separates "somebody else saved first" from "it is gone".
     *
     * <p>{@code <=>} rather than {@code =} because {@code NULL = NULL} is unknown
     * in SQL: a question whose answer was never set would otherwise be
     * permanently unsaveable.
     *
     * @param update the edit plus the values it was based on
     * @return which of the three things happened
     */
    public UpdateOutcome updateGuarded(QuestionUpdate update) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_GUARDED)) {

            ps.setString(1, update.edited().getQuestionText());
            ps.setString(2, update.edited().getAnswer());
            ps.setInt(3, update.id());
            ps.setString(4, update.expectedText());
            ps.setString(5, update.expectedAnswer());

            if (ps.executeUpdate() == 1) {
                return UpdateOutcome.SAVED;
            }
            return exists(conn, update.id()) ? UpdateOutcome.STALE : UpdateOutcome.MISSING;
        } catch (SQLException e) {
            // FAILED, not MISSING: a database outage must not tell the user their
            // question was removed. The handler answers INTERNAL and they retry.
            System.err.println("[QuestionDAO] guarded update failed: " + e.getMessage());
            e.printStackTrace();
            return UpdateOutcome.FAILED;
        }
    }

    private boolean exists(Connection conn, int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_EXISTS)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
