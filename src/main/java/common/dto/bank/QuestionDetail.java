package common.dto.bank;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One question as the staff member who may edit it sees it, answer key included (Common tier,
 * E6.1/E6.3 — F2.1).
 *
 * <h2>This record carries correctness, and that is the licence rather than the leak</h2>
 *
 * <p>It is the mirror image of {@code common.dto.exam.ExamQuestion}, which has nowhere to put a
 * correct answer because its recipient is a student sitting the paper. Here the recipient is a
 * teacher authoring the question, and an editor that cannot show which answer is right cannot
 * be used to author. So the safety property on this path is not "no key on the wire" but "no
 * key on a path a student can reach", which is the weaker claim and therefore needs the
 * stronger guard: {@code server.db.repos.BankWireLeakGuardTest} scans this package, fails the
 * build on any key-bearing record that is not explicitly licensed, and names this one in its
 * outbound allow-list with the reasoning above written down.
 *
 * <p>The verb that produces it, {@code QUESTION_GET}, is staff-only and scoped: teacher to the
 * courses she teaches, coordinator to every course of the subject she coordinates, principal to
 * everything read-only. Anything out of reach answers {@code NOT_FOUND}.
 *
 * <p><b>The principal sees the key</b> (lead's ruling of 2026-08-21). One detail type serving
 * every staff reader, rather than a second keyless projection for a distinction whose threat
 * model is students and not staff. F9.3 gives her a read-only bank browse and zero mutating
 * verbs, which is where her limit is expressed.
 *
 * <h2>Two version numbers, on purpose</h2>
 *
 * <p>{@code versionNo} is the version being shown and {@code latestVersionNo} is the newest one
 * that exists. They differ exactly when the reader has opened history, which is what lets a
 * detail pane say "you are looking at v2 of 3" and what F2.3's newer-version indicator needs,
 * without a second round trip to discover it. {@link #isLatest()} is the comparison, made once
 * here so three screens do not each make it.
 *
 * <p>There is <b>no</b> {@code lockVersion}, deliberately: {@code questions} is the identity row
 * and never changes when a version is added, so its {@code @Version} column never increments and
 * an echoed token would match forever. {@code baseVersionNo} on {@link QuestionEdit} is what
 * actually catches two teachers who both opened v3, and shipping an inert token beside a working
 * one is how the working one stops being trusted.
 *
 * @param displayId5      the 5-digit id (S-8)
 * @param courseCode      the owning course's code
 * @param courseName      the owning course's name
 * @param versionNo       the version shown here
 * @param latestVersionNo the newest version that exists for this question
 * @param text            the full stem, never truncated (unlike {@link BankQuestionRow#text()})
 * @param answers         exactly four options, ordered 1..4; never {@code null}, defensively
 *                        copied
 * @param correctAnswer   which option is right, 1..4 (C-8)
 * @param topic           the question's topic
 * @param difficulty      how hard it is
 * @param hasImage        whether this version has an illustration; the bytes are fetched
 *                        separately by {@code QUESTION_IMAGE_GET} (F2.4)
 * @param authorName      who wrote this version, resolved server-side from the recorded author
 * @param createdAt       when this version was written, UTC (ADR-010)
 */
public record QuestionDetail(String displayId5,
                             String courseCode,
                             String courseName,
                             int versionNo,
                             int latestVersionNo,
                             String text,
                             List<String> answers,
                             int correctAnswer,
                             String topic,
                             Difficulty difficulty,
                             boolean hasImage,
                             String authorName,
                             Instant createdAt) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Every question has exactly four options (C-7/C-8). */
    public static final int ANSWER_COUNT = 4;

    public QuestionDetail {
        Objects.requireNonNull(displayId5, "displayId5");
        Objects.requireNonNull(courseCode, "courseCode");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(difficulty, "difficulty");
        // List.copyOf yields an immutable, Serializable list - safe on the wire.
        answers = answers == null ? List.of() : List.copyOf(answers);
    }

    /** @return whether this is the newest version, i.e. the one an edit would branch from. */
    public boolean isLatest() {
        return versionNo == latestVersionNo;
    }

    /**
     * @param index 1..4
     * @return that option's text
     * @throws IllegalArgumentException for anything outside 1..4, the only four values a
     *         single-select question can be asked about
     */
    public String answer(int index) {
        if (index < 1 || index > answers.size()) {
            throw new IllegalArgumentException(
                    "A question has answers 1.." + answers.size() + ", asked for " + index);
        }
        return answers.get(index - 1);
    }

    /** @return the text of the right answer, so a detail pane does not index by hand. */
    public String correctAnswerText() {
        return answer(correctAnswer);
    }
}
