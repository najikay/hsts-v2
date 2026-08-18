package common.entities;

import java.io.Serializable;

/**
 * Domain entity representing a single exam question (Data/Common tier).
 *
 * <p>Implements {@link Serializable} so instances can be transmitted across the
 * OCSF network between the JavaFX client and the Fat Server (see Phase 3).
 */
public class Question implements Serializable {

    /** Keep stable so client and server stay wire-compatible. */
    private static final long serialVersionUID = 1L;

    private int id;
    private String questionText;
    private String answer;

    public Question() {
    }

    public Question(int id, String questionText, String answer) {
        this.id = id;
        this.questionText = questionText;
        this.answer = answer;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    @Override
    public String toString() {
        return "Question{id=" + id
                + ", questionText='" + questionText + '\''
                + ", answer='" + answer + '\'' + '}';
    }
}
