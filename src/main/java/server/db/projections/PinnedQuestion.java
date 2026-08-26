package server.db.projections;

import server.db.entities.Difficulty;

/**
 * One question as it sits on an exam version, in paper order (E7.2, E7.7).
 *
 * <p>The composition half of {@code EXAM_VERSION_GET}: the pin (points, ordinal) joined to the
 * pinned {@code question_versions} row and to the question's identity.
 *
 * <p><b>There is nowhere here to put the answer key, and that is the design.</b> The pinned row
 * is a {@code QuestionVersion}, which carries {@code correctAnswer}; returning the entity and
 * ignoring that field would make the boundary a promise. The constructor expression that builds
 * this record never names the column, so it is never fetched, and no caller can reach it through
 * a type that does not have it. That is E2.12's shape applied to the authoring side. This read
 * therefore takes <b>no</b> sanctioned suffix: {@code ForAuthoring} licenses a read that really
 * does carry a key, and claiming it here would spend the licence on something that does not need
 * it.
 *
 * <p><b>{@code pinnedVersionNo} against {@code latestVersionNo} is E7.7 by itself.</b> The
 * builder badges a question whose bank version has moved on since it was pinned, and the two
 * numbers are what the badge compares. They are read together so the screen cannot show a stale
 * comparison assembled from two reads taken at different moments.
 *
 * @param questionId          the {@code questions} row, which is what the duplicate rule is
 *                            about: the same question through two different versions of it is
 *                            still the same question (T-3.9)
 * @param questionVersionId   the pinned {@code question_versions} row
 * @param questionDisplayId5  the five-digit question display id
 * @param ord                 position on the paper, 1-based
 * @param points              this question's points, 1..100
 * @param text                the question stem
 * @param topic               its topic
 * @param difficulty          its difficulty
 * @param hasImage            whether the pinned version carries an illustration, without
 *                            fetching the bytes
 * @param pinnedVersionNo     the version number actually on the paper
 * @param latestVersionNo     the highest version number the bank now holds for this question
 * @param latestVersionId     the id of the row holding {@code latestVersionNo}, which is what
 *                            E7.14's update action re-pins to (2026-08-26, EXAM_BUILDER §4 A1).
 *                            Read in the same query as the number rather than resolved
 *                            afterwards, for the reason the number itself is: two reads taken at
 *                            different moments can describe different banks, and here the
 *                            disagreement would not badge a wrong row, it would <b>re-pin</b> one
 */
public record PinnedQuestion(long questionId,
                             long questionVersionId,
                             String questionDisplayId5,
                             int ord,
                             int points,
                             String text,
                             String topic,
                             Difficulty difficulty,
                             boolean hasImage,
                             int pinnedVersionNo,
                             int latestVersionNo,
                             long latestVersionId) {
}
