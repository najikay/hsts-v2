package server.db;

import common.entities.Question;

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
}
