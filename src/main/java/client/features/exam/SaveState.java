package client.features.exam;

/**
 * What the autosave indicator is currently saying (Presentation tier, E10.11 — F6.3).
 *
 * <p>Three states rather than two, because "saved" and "not saved yet" leave out the one a
 * student under time pressure most needs to see: a write that failed and will be retried.
 * Silently swallowing it would let her finish an exam believing answers are stored that are
 * not, which is the worst outcome this feature has.
 */
public enum SaveState {

    /** Everything the student has chosen is on the server. */
    SAVED(ExamCopy.SAVED_INDICATOR, "saved"),

    /** A change is waiting to be sent, or is in flight. */
    SAVING(ExamCopy.SAVING_INDICATOR, "saving"),

    /**
     * The last write did not land. The next change retries, and so does the submit, so this
     * is a warning rather than an error: nothing is lost, it is simply not stored yet.
     */
    FAILED(ExamCopy.SAVE_FAILED_INDICATOR, "unsaved");

    private final String label;
    private final String styleClass;

    SaveState(String label, String styleClass) {
        this.label = label;
        this.styleClass = styleClass;
    }

    /** @return the sentence shown next to the indicator. */
    public String label() {
        return label;
    }

    /** @return the {@code hsts.css} modifier class. */
    public String styleClass() {
        return styleClass;
    }

    /** @return {@code true} when something the student chose is not on the server yet. */
    public boolean isPending() {
        return this != SAVED;
    }
}
