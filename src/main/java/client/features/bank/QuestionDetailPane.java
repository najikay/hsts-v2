package client.features.bank;

import client.ui.components.Buttons;
import client.ui.components.StatusChip;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionVersionDetail;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
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
 * <h2>It draws nothing that can change a question, and that is the contract ⚑</h2>
 *
 * <p>Not one node this class returns writes, submits or deletes. That is what makes it safe for
 * the principal's screen, where T-11.3 has a reviewer looking for a create, edit or delete
 * control and finding none (S-7). The bank appends its own actions row <em>after</em> calling
 * this; the data browser appends nothing. So "the principal's question screen has no mutating
 * control" is a property of what the caller adds rather than of a flag passed in, and there is
 * no mode here for a later change to get the wrong way round.
 *
 * <p><b>The claim was "not one node here is a {@code Button}", and it stopped being true on
 * 2026-08-30</b> (Findings.txt, U-50): each history entry now carries a toggle that opens the
 * version it describes. It is narrowed rather than deleted because the narrower claim is the one
 * that was ever load-bearing. A disclosure toggle shows what the server already sent this reader
 * and sends nothing back, so it changes what T-11.3's reviewer can see and not what she can do.
 * {@link #readOnly} itself is unchanged and still returns no control of any kind.
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

        nodes.add(answers(detail.answers(), detail.correctAnswer()));

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

    /** The options of the version this detail is at, with that version's own key marked. */
    private static VBox answers(QuestionDetail detail) {
        return answers(detail.answers(), detail.correctAnswer());
    }

    /**
     * The four options, with the key marked on the one it belongs to (C-8).
     *
     * <p>The marking is a word and a style class, never a colour alone: "Correct" survives a
     * printout, a screenshot and a colour-blind reader, which is the same rule E14's attempt
     * column follows.
     *
     * <p><b>Two values rather than a record</b>, since 2026-08-30 (Findings.txt, U-53). The exam
     * builder's picked rows expand to the answers of the version the paper <em>pins</em>, which
     * reaches the client as a {@code QuestionVersionDetail} rather than as the
     * {@link QuestionDetail} the two bank screens hold. Both carry the same two things this loop
     * reads, so taking those two puts the third caller on this renderer instead of on a copy of
     * it, which is the whole argument of this class: one place where an option is marked correct
     * is one place for that to be got wrong.
     *
     * @param options       the options as that version reads them, ordered 1..4
     * @param correctAnswer which of them the key names, 1-based. A number outside the list marks
     *                      nothing, which is what a caller holding no key should draw
     * @return the options in order, in a column of their own
     */
    public static VBox answers(List<String> options, int correctAnswer) {
        Objects.requireNonNull(options, "options");
        VBox answers = new VBox(6);
        answers.getStyleClass().add("bank-answers");
        for (int i = 0; i < options.size(); i++) {
            int oneBased = i + 1;
            Label label = new Label(BankCopy.answerLabel(oneBased));
            label.getStyleClass().addAll("small", "muted");
            Label value = new Label(options.get(i));
            value.setWrapText(true);
            HBox line = new HBox(8, label, value);
            line.getStyleClass().add("bank-answer");
            if (oneBased == correctAnswer) {
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
            nodes.add(historyEntry(entry));
        }
        return nodes;
    }

    /**
     * One entry: when and by whom, what moved, and the version itself behind a toggle.
     *
     * <p>The when and the by-whom were the whole entry until 2026-08-30 (Findings.txt, U-50),
     * and that was the panel failing the requirement it exists for. F2.3 asks for the previous
     * version to be <b>viewable</b>, and a timeline that names versions without showing any of
     * them tells a teacher that v1 said something without ever saying what. Everything needed is
     * already on the wire: {@code QuestionVersionDetail} has carried the stem, the four options
     * and the key since E6.3, on the same staff-only {@code QUESTION_VERSIONS} verb, and
     * {@code BankWireLeakGuardTest} licenses it in writing for exactly this reading.
     *
     * <p><b>Collapsed by default</b>, because the panel is read top-down as a timeline first: a
     * question edited ten times would otherwise open as ten full questions and the dates the
     * teacher came for would be a scroll apart. The headline is clickable as well as the toggle,
     * so the row behaves the way a disclosure row looks like it should, and the toggle carries
     * the words for a reader who is on the keyboard or cannot see the cursor change.
     */
    private static Node historyEntry(BankSession.HistoryEntry entry) {
        Label headline = new Label(entry.headline());
        headline.getStyleClass().add(entry.isCurrent() ? "bank-history-current"
                : "bank-history-past");
        headline.setWrapText(true);
        Label changes = new Label(entry.changes());
        changes.getStyleClass().addAll("small", "muted");
        changes.setWrapText(true);

        VBox version = new VBox(6);
        version.getStyleClass().add("bank-history-version");
        Button toggle = Buttons.styled(BankCopy.HISTORY_SHOW_VERSION, Buttons.LINK,
                Buttons.SMALL);
        toggle.getStyleClass().add("bank-history-toggle");
        toggle.setOnAction(event ->
                reveal(entry.version(), version, toggle, !version.isManaged()));
        reveal(entry.version(), version, toggle, false);

        VBox headlines = new VBox(2, headline, changes);
        headlines.getStyleClass().add("bank-history-headline");
        // On the headlines rather than on the row, so the toggle's own click is not counted
        // twice and cancelled by the handler above it.
        headlines.setOnMouseClicked(event ->
                reveal(entry.version(), version, toggle, !version.isManaged()));

        VBox row = new VBox(4, headlines, toggle, version);
        row.getStyleClass().add("bank-history-entry");
        return row;
    }

    /**
     * The version as it read, read-only: the stem, then the four options with the key marked.
     *
     * <p>The same shape as {@link #readOnly}'s middle, and marked by the same rule, because a
     * history a teacher has to translate into the layout of the pane above it is a history she
     * will read wrong. Deliberately not the id, the course or the author line: those either do
     * not vary between versions or are already on the headline over this.
     */
    private static List<Node> versionBody(QuestionVersionDetail version) {
        Label text = new Label(version.text());
        text.setWrapText(true);
        text.getStyleClass().add("bank-question-text");

        Label heading = new Label(BankCopy.ANSWERS_HEADING);
        heading.getStyleClass().addAll("small", "muted");

        return List.of(text, heading, answers(version.answers(), version.correctAnswer()));
    }

    /**
     * Shows or hides one version, and says on the toggle which way it will go next.
     *
     * <p><b>The body is built the first time it is opened, not before.</b> A collapsed node is
     * still in the scene graph, so a history of ten versions built eagerly is sixty labels
     * nobody asked for, and every one of them answers a {@code lookup} as if it were on screen.
     * Building on demand means an unopened version is not merely hidden, it is not there.
     *
     * <p>Both {@code visible} and {@code managed} for the hiding: a hidden node that is still
     * managed leaves its own height in the timeline, which is a run of gaps between entries that
     * nothing explains.
     */
    private static void reveal(QuestionVersionDetail version, VBox body, Button toggle,
                               boolean shown) {
        if (shown && body.getChildren().isEmpty()) {
            body.getChildren().setAll(versionBody(version));
        }
        body.setVisible(shown);
        body.setManaged(shown);
        toggle.setText(shown ? BankCopy.HISTORY_HIDE_VERSION : BankCopy.HISTORY_SHOW_VERSION);
    }
}
