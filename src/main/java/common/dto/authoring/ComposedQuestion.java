package common.dto.authoring;

import common.dto.bank.Difficulty;

import java.io.Serializable;
import java.util.Objects;

/**
 * One question as it sits on a composed paper (Common tier, E7.2/E7.7 — F3.1, T-3.2).
 *
 * <h2>No answers and no key, and that is the whole reason this type exists</h2>
 *
 * <p>The builder shows <b>which</b> questions are on the paper and what they are worth. It is
 * not a preview of the paper. A teacher who wants to read a question opens it in the bank, where
 * {@code QUESTION_GET} already serves the key to exactly this audience under the frozen bank
 * contract, and where {@code BankWireLeakGuardTest} already carries the licence for it.
 *
 * <p>Keeping the key off this wire is what lets E7 add <b>no new type to the correctness
 * boundary</b>: {@code common.dto.authoring} is scanned by
 * {@code common.dto.WireDtoLeakGuardTest} along with every other wire package, and nothing here
 * appears on its licence list. That claim of the contract's section 9 — "the leak guard's
 * licensed list does not grow" — is asserted by the build rather than remembered by a reader,
 * which is the only version of it worth writing down.
 *
 * <p>There is nowhere to put an answer here even by accident, because there is no answers
 * component at all: this record carries a stem and nothing a student could not already see.
 *
 * <h2>{@code pinnedVersionNo} vs {@code latestVersionNo} is E7.7</h2>
 *
 * <p>They differ when the bank has moved on since this exam version pinned the question, and the
 * difference <em>is</em> the badge on the builder's row (E7.14). Both numbers travel because a
 * client comparing a pinned version against a bank it would have to fetch separately would be
 * two reads that can disagree, and the badge would flicker between them.
 *
 * @param questionVersionId  the exact bank version on this paper; the token
 *                           {@link QuestionPin#questionVersionId()} pins and E7.14 re-pins
 * @param questionDisplayId5 the 5-digit id staff quote when they talk about a question (S-8)
 * @param ord                position on the paper, <b>1-based</b>, matching {@code ck_evq_ord}.
 *                           Outbound it is a field; inbound it is the list index and nothing
 *                           else (see {@link QuestionPin})
 * @param points             what this question is worth, 1..100; the integers the live Σ/100
 *                           indicator sums (E7.3), never a percentage
 * @param text               the stem, <b>truncated server-side</b> exactly as
 *                           {@code BankQuestionRow.text} is, and for the same reason: a
 *                           composition of forty rows is a payload a builder screen feels
 * @param topic              the pinned version's topic, so a row is readable without a second
 *                           lookup
 * @param difficulty         the pinned version's difficulty, reusing the bank's enum rather
 *                           than a second copy of one concept
 * @param hasImage           whether the pinned version has an illustration, so the row can show
 *                           a marker without fetching bytes nobody asked for
 * @param pinnedVersionNo    the bank version number this paper is pinned to
 * @param latestVersionNo    the newest version number the bank now holds; equal to
 *                           {@code pinnedVersionNo} when nothing has moved
 * @param latestVersionId    the <b>id</b> of that newest version, which is what E7.14's update
 *                           action re-pins to. Added 2026-08-26 by the lead's ruling on the
 *                           E7.14 ask, as EXAM_BUILDER §4 amendment A1 records. The two numbers
 *                           above are enough to <em>draw</em> the badge and not enough to
 *                           <em>press</em> it: {@link QuestionPin} keys on an id, so a client
 *                           holding only version numbers can say the bank has moved on and
 *                           cannot say what to move to. Equal to {@code questionVersionId} when
 *                           nothing has moved, which is why re-pinning an up-to-date question is
 *                           a no-op rather than a special case
 */
public record ComposedQuestion(long questionVersionId,
                               String questionDisplayId5,
                               int ord,
                               int points,
                               String text,
                               String topic,
                               Difficulty difficulty,
                               boolean hasImage,
                               int pinnedVersionNo,
                               int latestVersionNo,
                               long latestVersionId) implements Serializable {

    /**
     * Bumped for {@code latestVersionId} (2026-08-26).
     *
     * <p>Client and server ship as one artifact, so a mismatched pair cannot arise in this
     * project. The bump is still correct rather than ceremonial: this record travels
     * {@link Serializable} over the wire, and a component added under an unchanged UID is the
     * one shape where two builds deserialise each other into silent nonsense.
     */
    private static final long serialVersionUID = 2L;

    /**
     * Null-checks what the server cannot build a meaningful row without.
     *
     * <p>Outbound, so this throws where {@link QuestionPin} normalises: a null here is a server
     * bug and must surface at build time rather than as an empty cell a teacher squints at.
     */
    public ComposedQuestion {
        Objects.requireNonNull(questionDisplayId5, "questionDisplayId5");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(difficulty, "difficulty");
        // The two halves of E7.7 must describe one row. uq_question_versions_no makes
        // (questionId, versionNo) unique, so "the latest number is the pinned number" and "the
        // latest id is the pinned id" are the same statement, and a build where they disagree
        // has resolved the id by a different rule than the number. That is precisely the defect
        // available here: selecting max(id) instead of the id AT max(versionNo) agrees with the
        // number right up until it does not, and the symptom would be an update action that
        // re-pins a question to the wrong version of itself.
        if (latestVersionNo == pinnedVersionNo && latestVersionId != questionVersionId) {
            throw new IllegalArgumentException("latestVersionId " + latestVersionId
                    + " disagrees with latestVersionNo " + latestVersionNo
                    + ", which equals pinnedVersionNo: the same version cannot have two ids");
        }
    }

    /**
     * @return {@code true} when the bank has moved on since this paper pinned the question,
     *         which is E7.7's badge. One expression of the comparison, so the builder row and
     *         the history panel cannot render it two different ways
     */
    public boolean hasNewerVersion() {
        return latestVersionNo > pinnedVersionNo;
    }
}
