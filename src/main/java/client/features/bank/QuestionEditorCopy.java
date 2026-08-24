package client.features.bank;

import java.util.Objects;

/**
 * Every sentence the question editor prints (Presentation tier, E6.10 / E6.11 — F2.1, F2.2,
 * C-8, T-2.2).
 *
 * <p>FX-free and static, on the {@link BankCopy} pattern.
 *
 * <h2>What is deliberately NOT here</h2>
 *
 * <p><b>The refusal sentences.</b> Every rule the editor checks live is a rule the server also
 * checks, and the server's wording lives in {@code BankMessages}. This class does not restate
 * one of them: {@link QuestionEditorSession} runs the shared rule and shows the shared sentence,
 * so the sentence a teacher sees while typing is the same sentence she would have got back from a
 * save. Two catalogues of one rule is how the live hint comes to promise something the save then
 * refuses, which is the defect E6.11 exists to prevent rather than to introduce.
 *
 * <p>What is here is everything the server never sees: the labels, the buttons, the titles, and
 * the sentences about the editor's own state.
 *
 * <p>No em dashes (PRD section 4.1).
 */
public final class QuestionEditorCopy {

    private QuestionEditorCopy() {
    }

    // ===================== Titles =========================================

    /** Heading when writing a new question (F2.2). */
    public static final String TITLE_NEW = "New question";

    /** Heading when editing an existing one. */
    public static String titleEdit(String displayId5) {
        return "Editing question #" + displayId5;
    }

    /**
     * The line under the heading in edit mode.
     *
     * <p>Says what saving will do, because it is not what a teacher expects: editing writes a new
     * version and leaves every exam on the version it already pinned (C-2, ADR-011). She should
     * know that before she saves, not after she goes looking for the old one.
     */
    public static String editSubtitle(int baseVersionNo) {
        return "Saving writes version " + (baseVersionNo + 1)
                + ". Version " + baseVersionNo + " is kept, and exams that use it are unaffected.";
    }

    /** The line under the heading in create mode. */
    public static final String NEW_SUBTITLE =
            "The question gets its five digit id when you save it.";

    // ===================== Fields =========================================

    /** The stem. */
    public static final String TEXT_LABEL = "Question";

    /** Prompt in the stem box. */
    public static final String TEXT_PROMPT = "What are you asking?";

    /** The radio group's label; the group itself is the C-8 guarantee. */
    public static final String ANSWERS_LABEL = "Answers, and which one is correct";

    /** Hint under the answer rows. */
    public static final String ANSWERS_HINT =
            "Four answers, all different, exactly one of them correct.";

    /** Prompt in one answer box. */
    public static String answerPrompt(int oneBased) {
        return "Answer " + oneBased;
    }

    /** The topic field. */
    public static final String TOPIC_LABEL = "Topic";

    /** Prompt in the topic box. */
    public static final String TOPIC_PROMPT = "For example: Equations";

    /** The difficulty picker. */
    public static final String DIFFICULTY_LABEL = "Difficulty";

    /** The image picker's label; the component owns its own refusals. */
    public static final String IMAGE_LABEL = "Illustration";

    // ===================== Buttons ========================================

    /** Saves a new question. */
    public static final String CREATE = "Add question";

    /** Saves an edit. */
    public static final String SAVE = "Save as a new version";

    /** Leaves without saving. */
    public static final String CANCEL = "Cancel";

    /** The button that opens the editor on a new question, on the bank screen. */
    public static final String NEW_QUESTION = "New question";

    /** The button that opens the editor on the selected question, on the bank screen. */
    public static final String EDIT_QUESTION = "Edit question";

    // ===================== State ==========================================

    /**
     * What the lock banner calls the thing being edited (E6.14).
     *
     * <p>Declared here rather than in {@code LockCopy}: that class is the shared lock vocabulary
     * and belongs to E18, and the noun is this screen's. The legacy screen declares its own for
     * the same reason.
     */
    public static final String LOCK_NOUN = "question";

    /** Shown while a save is in flight, so a second press is visibly pointless. */
    public static final String SAVING = "Saving";

    /** The unsaved-changes marker. */
    public static final String UNSAVED = "Unsaved changes";

    /** Title of the confirmation when leaving with unsaved work. */
    public static final String DISCARD_TITLE = "Leave without saving?";

    /** Body of that confirmation. */
    public static final String DISCARD_BODY =
            "The changes you have made will be lost. The question stays as it was.";

    /** Confirm button on that dialog. */
    public static final String DISCARD_CONFIRM = "Discard them";

    /** Cancel button on that dialog. */
    public static final String DISCARD_CANCEL = "Keep editing";

    // ===================== Outcomes =======================================

    /** Toast title after a successful save. */
    public static final String SAVED = "Saved";

    /** Toast body after a new question was created. */
    public static String created(String displayId5) {
        return "Question #" + displayId5 + " was added to the bank.";
    }

    /** Toast body after an edit. */
    public static String versionWritten(String displayId5, int versionNo) {
        return "Question #" + displayId5 + " is now at version " + versionNo + ".";
    }

    /**
     * The save reached the server and came back refused for a reason no field explains.
     *
     * <p>Only used when the server's sentence cannot be matched to a field. A refusal that names
     * a field is shown on that field instead, which is what T-2.2's three different sentences
     * need.
     */
    public static final String SAVE_REFUSED_TITLE = "That could not be saved";

    /** The save never reached a decision. */
    public static final String SAVE_FAILED =
            "The question could not be saved. Nothing was changed. Try again in a moment.";

    /**
     * Somebody else wrote a version while this editor was open (CONFLICT).
     *
     * <p>Deliberately does not offer to overwrite. The other version is somebody's work and the
     * honest move is to go and read it: this editor has no idea what changed in it.
     */
    public static final String STALE_TITLE = "Somebody else edited this question";

    /** Body of the stale-edit dialog. */
    public static final String STALE_BODY =
            "A newer version was written while this editor was open, so saving now would write a "
                    + "version on top of work you have not seen. Close the editor and open the "
                    + "question again to read it first.";

    /** The only button on the stale dialog. */
    public static final String STALE_CONFIRM = "Close the editor";

    /** The question stopped being reachable while the editor was open. */
    public static final String GONE_TITLE = "That question is no longer there";

    /** Body when the question was deleted or moved out of scope mid-edit. */
    public static final String GONE_BODY =
            "It may have been deleted while this editor was open. Nothing was saved.";

    // ===================== Which field a refusal belongs to ===============

    /**
     * The fields the editor can put a message on.
     *
     * <p>An enum rather than strings because the mapping from a server refusal to a box is the
     * thing E6.11 is judged on, and a typo in a field name would silently show the refusal
     * nowhere.
     */
    public enum Field {

        /** The stem. */
        TEXT,

        /** One of the four answer boxes; the position rides beside it. */
        ANSWER,

        /** The radio group. */
        CORRECT_ANSWER,

        /** The topic box. */
        TOPIC,

        /** The difficulty picker. */
        DIFFICULTY,

        /** The illustration. */
        IMAGE,

        /** Nowhere in particular: shown as a dialog rather than under a box. */
        FORM
    }

    /**
     * One refusal, and where it goes.
     *
     * @param field    which control shows it
     * @param position 1..4 for {@link Field#ANSWER}, 0 otherwise
     * @param message  the sentence, always the server's own wording where there is one
     */
    public record Refusal(Field field, int position, String message) {

        public Refusal {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(message, "message");
        }

        /** @return a refusal about the form as a whole */
        public static Refusal form(String message) {
            return new Refusal(Field.FORM, 0, message);
        }

        /** @return a refusal about one answer box */
        public static Refusal answer(int position, String message) {
            return new Refusal(Field.ANSWER, position, message);
        }

        /** @return a refusal about a field that has only one box */
        public static Refusal of(Field field, String message) {
            return new Refusal(field, 0, message);
        }
    }
}
