package client.features.bank;

import client.ui.components.logic.ChipCatalog;
import common.dto.bank.BankQuestionRow;
import common.dto.bank.BlockingExam;
import common.dto.bank.Difficulty;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionVersionDetail;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Every sentence the question bank screen prints (Presentation tier, E6.9 / E6.12 / E6.13 —
 * F2.4, F2.5, T-2).
 *
 * <p>FX-free and static, on the {@code DataCopy} pattern, so the empty panels, the refusal
 * sentences and the delete dialogs are unit tested rather than eyeballed, and so the view beside
 * it stays a renderer with no decisions in it.
 *
 * <h2>Why the delete sentences are here and not in a dialog</h2>
 *
 * <p>T-2.7 is a teacher pressing Delete on a question three exams are built from, and the
 * acceptance case is not "she is refused", it is that the refusal <b>names the exams</b>. A
 * sentence assembled inside a JavaFX dialog is a sentence no test can read without a toolkit,
 * which is exactly how a refusal ends up saying "cannot delete" on the day it matters.
 *
 * <h2>The server owns the refusals, this class owns the questions</h2>
 *
 * <p>Anything the server refused arrives as a {@code VALIDATION} message from {@code
 * BankMessages} and is shown verbatim: it names the field, and re-writing it here would be a
 * second copy that drifts. What this class writes is everything the server never sees, which is
 * the screen's own framing: the empty panels, the count lines, the confirmations, and the
 * sentence for a request that never reached a handler at all.
 *
 * <p>No em dashes anywhere in this file's constants (PRD section 4.1).
 */
public final class BankCopy {

    /** Local zone for every wire instant on this screen; the wire is UTC (ADR-010). */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static final DateTimeFormatter ROW_DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    private BankCopy() {
    }

    // ===================== The screen =====================================

    /** The screen's title. Matches the rail item's label. */
    public static final String TITLE = "Question bank";

    /** What the screen is for, in one line under the title. */
    public static final String SUBTITLE =
            "Browse, edit and retire the questions in the courses you can reach.";

    /** The prompt in the free-text box. */
    public static final String SEARCH_PROMPT = "Search the question text";

    /** The course picker's "do not filter" entry. */
    public static final String ALL_COURSES = "All courses";

    /** The difficulty picker's "do not filter" entry. */
    public static final String ALL_DIFFICULTIES = "Any difficulty";

    /** The topic picker's "do not filter" entry. */
    public static final String ALL_TOPICS = "Any topic";

    /**
     * Why the New question button did nothing.
     *
     * <p>QUESTION_CREATE carries a course and the server refuses one she does not teach, so the
     * course has to be known before the editor opens. With no course filter set there is nothing
     * to guess at, and guessing would put her question in the wrong bank.
     */
    public static final String PICK_A_COURSE_FIRST =
            "Choose a course first, so the question knows which bank it belongs to.";

    /** The button that puts every filter back. */
    public static final String CLEAR_FILTERS = "Clear filters";

    // ===================== Empty states ===================================

    /**
     * The panel shown where the list would be, when there is no list to show.
     *
     * <p>A record rather than two loose strings, for the reason {@code DataCopy.EmptyPanel} is
     * one: "your bank is empty" and "your filter matched nothing" are different facts and the
     * screen has to say which. One generic "nothing here" for both is the dead end PRD section
     * 4.1 forbids, and here it is worse than usual, because a teacher who is told her bank is
     * empty when it is merely filtered will go and write a question she already has.
     *
     * @param title the heading
     * @param hint  the explanation, which always says what would make the panel go away
     */
    public record EmptyPanel(String title, String hint) {

        public EmptyPanel {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(hint, "hint");
        }
    }

    /** She can reach questions, and the filters are hiding all of them. */
    public static final EmptyPanel NO_MATCHES = new EmptyPanel(
            "Nothing matches those filters",
            "Clear the search box, or widen the course, topic or difficulty, to see the whole "
                    + "bank again.");

    /** She can reach courses, and not one of them holds a question yet (T-2.1's first run). */
    public static final EmptyPanel NO_QUESTIONS = new EmptyPanel(
            "There are no questions yet",
            "Questions you add appear here, in every course you teach.");

    /**
     * Nothing is selected, so the detail pane has nothing to describe.
     *
     * <p>Not an error and not a failure: it is the screen's resting state, and it says what to
     * do rather than merely noting that nothing is happening.
     */
    public static final EmptyPanel NOTHING_SELECTED = new EmptyPanel(
            "No question selected",
            "Choose a question on the left to read it, see its versions, or edit it.");

    // ===================== Failures =======================================

    /**
     * The list could not be loaded.
     *
     * <p>Deliberately not "an error occurred". A teacher can act on "try again" and cannot act
     * on a category name.
     */
    public static final String LIST_FAILED =
            "The question bank could not be loaded. It will load again the next time you open "
                    + "this screen.";

    /**
     * The heading over a question that would not open.
     *
     * <p>Its own heading rather than {@link #NOTHING_SELECTED}'s: the row is still highlighted
     * in the list, so a pane saying "No question selected" beside it would be the screen
     * contradicting itself.
     */
    public static final String DETAIL_FAILED_TITLE = "That question could not be opened";

    /** One question could not be opened. */
    public static final String DETAIL_FAILED =
            "The question is still there. Nothing was changed by the attempt to open it.";

    /** The action on the failed-detail panel. */
    public static final String RETRY = "Try again";

    /** The version history could not be read. */
    public static final String VERSIONS_FAILED =
            "The version history could not be read. Close and reopen the panel to retry.";

    /**
     * The illustration could not be fetched.
     *
     * <p>Its own sentence, because the rest of the question is on screen and readable: telling
     * her the question failed to load when only its picture did would send her away from a
     * question she can use.
     */
    public static final String IMAGE_FAILED =
            "The illustration could not be loaded. The rest of this question is unaffected.";

    /** The delete never reached a decision. */
    public static final String DELETE_FAILED =
            "That question could not be deleted. Nothing was changed. Try again in a moment.";

    /** The question has no illustration at all, which is not a failure. */
    public static final String NO_IMAGE = "No illustration";

    /** The illustration is on its way (E6.6's lazy fetch). */
    public static final String IMAGE_LOADING = "Loading the illustration";

    // ===================== The list =======================================

    /**
     * The line under the list saying how much of the bank is on screen.
     *
     * <p>Says both numbers whenever they differ, because "40 questions" on a screen showing a
     * filtered page is a number she cannot reconcile with anything.
     *
     * @param shown     rows on this page
     * @param total     rows the filters match, across every page
     * @param filtered  whether any filter is set
     * @return the sentence, never blank
     */
    public static String countLine(int shown, long total, boolean filtered) {
        if (total == 0) {
            return filtered ? "No questions match" : "No questions yet";
        }
        String noun = total == 1 ? "question" : "questions";
        String scope = filtered ? " match" : "";
        if (shown == total) {
            return total + " " + noun + scope;
        }
        return "Showing " + shown + " of " + total + " " + noun + scope;
    }

    /**
     * The page indicator.
     *
     * @param page       zero-based, as the wire counts
     * @param totalPages how many there are
     */
    public static String pageLine(int page, int totalPages) {
        int safe = Math.max(totalPages, 1);
        return "Page " + (page + 1) + " of " + safe;
    }

    /** The five-digit id as the teacher reads it (S-8). */
    public static String questionId(BankQuestionRow row) {
        return row == null ? "" : "#" + row.displayId5();
    }

    /** A row's date column. */
    public static String rowDate(Instant instant) {
        return instant == null ? "" : ROW_DATE.format(instant.atZone(ZONE));
    }

    /** A timestamp with the time in it, for the detail pane and the history. */
    public static String stamp(Instant instant) {
        return instant == null ? "" : STAMP.format(instant.atZone(ZONE));
    }

    /**
     * The difficulty as a word.
     *
     * <p>Goes through {@link ChipCatalog#forDifficulty(String)} rather than a second switch, so
     * the picker entry and the chip on the row can never disagree about what MEDIUM is called.
     */
    public static String difficulty(Difficulty difficulty) {
        return difficulty == null ? ALL_DIFFICULTIES
                : ChipCatalog.forDifficulty(difficulty.name()).label();
    }

    /** A row whose topic the teacher never filled in still has to render as something. */
    public static String topic(String topic) {
        return topic == null || topic.isBlank() ? "No topic" : topic;
    }

    // ===================== The detail pane ================================

    /** Heading over the four options. */
    public static final String ANSWERS_HEADING = "Answers";

    /** Marks the option the key names. */
    public static final String CORRECT_MARK = "Correct";

    /** Labels one option, one-based exactly as the wire numbers them (C-8). */
    public static String answerLabel(int oneBased) {
        return "Answer " + oneBased;
    }

    /** The author and when, under the question. */
    public static String writtenBy(QuestionDetail detail) {
        if (detail == null) {
            return "";
        }
        String who = detail.authorName() == null || detail.authorName().isBlank()
                ? "an unnamed author" : detail.authorName();
        return "Written by " + who + " on " + stamp(detail.createdAt());
    }

    /**
     * Which version is on screen, and whether it is the newest.
     *
     * <p>This is F2.3's indicator in its smallest form: a teacher reading v2 of 3 has to be told
     * so before she edits, because editing an old version is how she quietly reverts the newest.
     */
    public static String versionLine(QuestionDetail detail) {
        if (detail == null) {
            return "";
        }
        if (detail.versionNo() >= detail.latestVersionNo()) {
            return "Version " + detail.versionNo() + ", the newest";
        }
        return "Version " + detail.versionNo() + " of " + detail.latestVersionNo()
                + ", not the newest";
    }

    // ===================== Version history (E6.12) ========================

    /** The panel's heading. */
    public static final String HISTORY_TITLE = "Version history";

    /** The button that opens it. */
    public static final String HISTORY_OPEN = "Version history";

    /** The button that closes it. */
    public static final String HISTORY_CLOSE = "Hide history";

    /**
     * One entry in the timeline.
     *
     * @param version the version being described
     * @param latest  the newest version number the question has
     */
    public static String historyEntry(QuestionVersionDetail version, int latest) {
        if (version == null) {
            return "";
        }
        String tail = version.versionNo() >= latest ? " (current)" : "";
        // The author is named on every line, not only when it changes: on a co-taught course
        // "who wrote this one" is the first question a teacher asks of a history, and a panel
        // that answers only "when" sends her to the audit log for it.
        String who = version.authorName() == null || version.authorName().isBlank()
                ? "" : ", " + version.authorName();
        return "Version " + version.versionNo() + tail + ", " + stamp(version.createdAt()) + who;
    }

    /**
     * What changed between one version and the one before it, in words.
     *
     * <p>E6.12 asks for a diff highlight. The fields a question has are few and named, so the
     * honest form of that is a list of which ones moved, computed here where it can be tested,
     * rather than a colour applied in a cell renderer where it cannot.
     *
     * <p><b>It compares every field {@link QuestionVersionDetail} carries except one, and the
     * exception is why the no-change sentence is worded the way it is.</b> The wire has three
     * image actions (KEEP, REPLACE, REMOVE) and the DTO carries only {@code hasImage}, so a
     * teacher who swapped a wrong diagram for a right one and changed nothing else leaves two
     * versions this method cannot tell apart. Saying "nothing changed" there would be a false
     * sentence about the only reason the version exists, so the fallback says what is true
     * instead: nothing it can compare moved. The fix is a field on the DTO and it is raised in
     * this PR's report while the contract is still DRAFT and the change is additive.
     *
     * @param newer the later version
     * @param older the one before it, or {@code null} for the first version ever written
     * @return a sentence, or the first-version sentence when {@code older} is null
     */
    public static String changeSummary(QuestionVersionDetail newer, QuestionVersionDetail older) {
        Objects.requireNonNull(newer, "newer");
        if (older == null) {
            return "The first version.";
        }
        List<String> changed = new java.util.ArrayList<>();
        if (!Objects.equals(newer.text(), older.text())) {
            changed.add("the question");
        }
        if (!Objects.equals(newer.answers(), older.answers())) {
            changed.add("the answers");
        }
        if (newer.correctAnswer() != older.correctAnswer()) {
            changed.add("which answer is correct");
        }
        if (!Objects.equals(newer.topic(), older.topic())) {
            changed.add("the topic");
        }
        if (newer.difficulty() != older.difficulty()) {
            changed.add("the difficulty");
        }
        if (newer.hasImage() != older.hasImage()) {
            changed.add(newer.hasImage() ? "an illustration was added"
                    : "the illustration was removed");
        }
        if (!Objects.equals(newer.authorName(), older.authorName())) {
            // On a co-taught course this is the first thing a teacher asks of a history, and it
            // is on the DTO already: leaving it out was an omission, not a decision.
            changed.add("who wrote it");
        }
        if (changed.isEmpty()) {
            return newer.hasImage() && older.hasImage()
                    ? "Nothing this history can compare changed. Two versions can carry "
                            + "different illustrations, and the picture itself is not compared."
                    : "Nothing on the question itself changed.";
        }
        return "Changed: " + join(changed) + ".";
    }

    // ===================== Delete (E6.13, F2.5, T-2.7) ====================

    /** The action's label wherever it appears. */
    public static final String DELETE = "Delete question";

    /** Title of the ordinary confirmation. */
    public static final String DELETE_CONFIRM_TITLE = "Delete this question?";

    /** The confirm button. */
    public static final String DELETE_CONFIRM_BUTTON = "Delete";

    /** The cancel button, on both dialogs. */
    public static final String DELETE_CANCEL_BUTTON = "Keep it";

    /**
     * What deleting actually does, said plainly before she does it.
     *
     * <p>Soft delete is not obvious from the outside, and the two facts that matter to her are
     * opposite in feel: the question leaves her bank, and nothing that already used it changes.
     * A confirmation that says only "this cannot be undone" would be describing a stronger thing
     * than what happens, and one that says only "it is reversible" would be describing a weaker
     * one.
     */
    public static String deleteConfirmBody(QuestionDetail detail) {
        String id = detail == null ? "" : "#" + detail.displayId5() + " ";
        return "Question " + id + "leaves the bank and stops appearing in searches and pickers. "
                + "Exams that already use it keep working, and its version history is kept. "
                + "Its five-digit id is never given to another question.";
    }

    /** Title of the refusal (T-2.7). */
    public static final String DELETE_BLOCKED_TITLE = "This question is in use";

    /** The only button on the refusal: there is nothing to confirm. */
    public static final String DELETE_BLOCKED_BUTTON = "Close";

    /**
     * The refusal, naming the exams (T-2.7, F2.5).
     *
     * <p>The list is de-duplicated by exam rather than by exam version on the server, so an exam
     * that pins the question in two of its versions is named once. This sentence assumes that
     * and would read as a stutter if it ever stopped being true, which is why the contract's
     * section 4 says so where the DTO is declared.
     *
     * <p><b>It names the question as well as the exams.</b> The dialog is modal and runs a
     * nested event loop, so a list answer can land while it is open and change what the pane
     * behind it is showing. A refusal that said only "this question" would then be read as a
     * refusal about whichever question is now on screen.
     *
     * @param displayId5 the question the refusal is about, or {@code null} if unknown
     * @param exams      what the server named, never empty when this is shown
     */
    public static String deleteBlockedBody(String displayId5, List<BlockingExam> exams) {
        List<BlockingExam> safe = exams == null ? List.of() : exams;
        String subject = displayId5 == null || displayId5.isBlank()
                ? "that question" : "question #" + displayId5;
        if (safe.isEmpty()) {
            // Defensive: a blocked outcome with no exams is a server bug, and a blank dialog
            // would be the worst possible rendering of it.
            return "An exam uses " + subject + ", so it cannot be deleted.";
        }
        String noun = safe.size() == 1 ? "exam uses" : "exams use";
        StringBuilder body = new StringBuilder();
        body.append(safe.size()).append(' ').append(noun).append(' ').append(subject)
                .append(", so it cannot be deleted:\n");
        for (BlockingExam exam : safe) {
            body.append("\n    ").append(exam.name()).append("  (").append(exam.displayId6())
                    .append(')');
        }
        body.append("\n\nRemove it from those exams first, or edit it instead: editing writes a "
                + "new version and leaves every exam on the version it already pinned.");
        return body.toString();
    }

    /** The toast after a delete that went through. */
    public static String deleted(String displayId5) {
        return "Question #" + displayId5 + " was deleted.";
    }

    // ===================== Helpers ========================================

    /** "a", "a and b", "a, b and c" — the Oxford comma is deliberately absent. */
    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        String head = String.join(", ", parts.subList(0, parts.size() - 1));
        return head + " and " + parts.get(parts.size() - 1);
    }
}
