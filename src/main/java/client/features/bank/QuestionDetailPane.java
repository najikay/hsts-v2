package client.features.bank;

import client.ui.components.StatusChip;
import common.dto.bank.QuestionDetail;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One question, drawn read-only (Presentation tier, E6.9 / E15.2 — F2.4, F9.3).
 *
 * <p>Lifted out of {@code BankView} on 2026-08-30 (live session, U-44), when the principal's
 * Data browser gained a question detail of its own. Two screens now show one question: the
 * teacher's bank, which surrounds this with Edit and Delete, and the principal's
 * {@code DataQuestionView}, which surrounds it with nothing. What they share is exactly what is
 * here — the id, the course, the version line, the stem, the four options with the correct one
 * marked, the topic and difficulty, the illustration, and who wrote it — and it is here rather
 * than in both because a second copy is a second place for the correct-answer marking to be got
 * wrong, and only one of the two screens has a test that would notice.
 *
 * <h2>It draws no control at all, and that is the contract ⚑</h2>
 *
 * <p>Not one node this class returns is a {@code Button}, a field or a menu. That is what makes
 * it safe for the principal's screen, where T-11.3 has a reviewer looking for a create, edit or
 * delete control and finding none (S-7). The bank appends its own actions row <em>after</em>
 * calling this; the data browser appends nothing. So "the principal's question screen has no
 * mutating control" is a property of what the caller adds rather than of a flag passed in, and
 * there is no mode here for a later change to get the wrong way round.
 *
 * <p>The illustration arrives as a {@link Node} from the caller rather than being fetched here.
 * The bytes come from {@code QUESTION_IMAGE_GET} on a session, and a renderer that reached for a
 * session would be a renderer with a network dependency; each caller has its own load state and
 * its own three sentences for the three ways a picture can be absent.
 */
public final class QuestionDetailPane {

    private QuestionDetailPane() {
        // static renderer - no instances
    }

    /**
     * The question, top to bottom, with nothing that could change it.
     *
     * @param detail    the question at the version being shown
     * @param imageNode what to draw where the illustration goes: the picture, or the caller's
     *                  own sentence about why it is not there. {@code null} draws nothing at
     *                  all, which is right for a caller that does not fetch images
     * @return the nodes in reading order, for a caller to put in a column
     */
    public static List<Node> readOnly(QuestionDetail detail, Node imageNode) {
        Objects.requireNonNull(detail, "detail");
        List<Node> nodes = new ArrayList<>();

        Label id = new Label("#" + detail.displayId5());
        id.getStyleClass().addAll("h2", "bank-detail-id");
        Label course = new Label(detail.courseName());
        course.getStyleClass().addAll("small", "muted");
        Label version = new Label(BankCopy.versionLine(detail));
        version.getStyleClass().addAll("small", "muted", "bank-version-line");
        nodes.add(new VBox(2, id, course, version));

        Label text = new Label(detail.text());
        text.setWrapText(true);
        text.getStyleClass().add("bank-question-text");
        nodes.add(text);

        Label answersHeading = new Label(BankCopy.ANSWERS_HEADING);
        answersHeading.getStyleClass().addAll("small", "muted");
        nodes.add(answersHeading);

        nodes.add(answers(detail));

        HBox facts = new HBox(10, new Label(BankCopy.topic(detail.topic())),
                StatusChip.difficulty(detail.difficulty().name()));
        facts.setAlignment(Pos.CENTER_LEFT);
        facts.getStyleClass().add("bank-facts");
        nodes.add(facts);

        if (imageNode != null) {
            nodes.add(imageNode);
        }

        Label author = new Label(BankCopy.writtenBy(detail));
        author.getStyleClass().addAll("small", "muted");
        author.setWrapText(true);
        nodes.add(author);

        return nodes;
    }

    /**
     * The four options, with the key marked on the one it belongs to (C-8).
     *
     * <p>The marking is a word and a style class, never a colour alone: "Correct" survives a
     * printout, a screenshot and a colour-blind reader, which is the same rule E14's attempt
     * column follows.
     */
    private static VBox answers(QuestionDetail detail) {
        VBox answers = new VBox(6);
        answers.getStyleClass().add("bank-answers");
        List<String> options = detail.answers();
        for (int i = 0; i < options.size(); i++) {
            int oneBased = i + 1;
            Label label = new Label(BankCopy.answerLabel(oneBased));
            label.getStyleClass().addAll("small", "muted");
            Label value = new Label(options.get(i));
            value.setWrapText(true);
            HBox line = new HBox(8, label, value);
            line.getStyleClass().add("bank-answer");
            if (oneBased == detail.correctAnswer()) {
                Label mark = new Label(BankCopy.CORRECT_MARK);
                mark.getStyleClass().addAll("small", "bank-answer-correct");
                line.getChildren().add(mark);
                line.getStyleClass().add("correct");
            }
            answers.getChildren().add(line);
        }
        return answers;
    }

    /**
     * The version timeline, newest first (E6.12 — F2.3).
     *
     * <p>Also two screens' worth, and lifted for the same reason: the diff sentence beside each
     * entry is {@link BankCopy#changeSummary} either way, and a second copy of the loop that
     * pairs a version with the one before it is a second chance to pair it with the one after.
     *
     * @param entries the timeline, from {@link BankSession#timeline}
     * @return one node per version, in the order given; empty when there is no history
     */
    public static List<Node> history(List<BankSession.HistoryEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        List<Node> nodes = new ArrayList<>(entries.size());
        for (BankSession.HistoryEntry entry : entries) {
            Label headline = new Label(entry.headline());
            headline.getStyleClass().add(entry.isCurrent() ? "bank-history-current"
                    : "bank-history-past");
            Label changes = new Label(entry.changes());
            changes.getStyleClass().addAll("small", "muted");
            changes.setWrapText(true);
            VBox row = new VBox(2, headline, changes);
            row.getStyleClass().add("bank-history-entry");
            nodes.add(row);
        }
        return nodes;
    }
}
