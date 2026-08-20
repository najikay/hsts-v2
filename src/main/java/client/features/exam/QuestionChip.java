package client.features.exam;

/**
 * One cell of the question navigator strip and of the answer-summary grid (Presentation
 * tier, E10.10/E10.13 — F6.9).
 *
 * <p>The same chip serves both, because they are the same thing seen twice: while the exam
 * runs it is a jump target, and in the submit dialog it is a jump target that also warns.
 * F6.9 asks for exactly that behaviour ("click a chip to jump to that question"), and one
 * shape means the two views cannot disagree about which questions are blank.
 *
 * @param index     position in the paper, 0-based, which is what a jump uses
 * @param label     what the chip shows, the 1-based position
 * @param displayId the 5-digit question id (S-8), for the tooltip
 * @param answered  whether a choice is saved for it
 * @param current   whether this is the question on screen right now
 */
public record QuestionChip(int index, String label, String displayId,
                           boolean answered, boolean current) {

    /** @return the {@code hsts.css} modifier classes for this chip's state. */
    public String styleClass() {
        StringBuilder classes = new StringBuilder(answered ? "answered" : "blank");
        if (current) {
            classes.append(" current");
        }
        return classes.toString();
    }

    /** @return the tooltip, which is the only place the question id is worth the space. */
    public String tooltip() {
        return "Question " + label + " · " + displayId
                + (answered ? " · answered" : " · not answered");
    }
}
