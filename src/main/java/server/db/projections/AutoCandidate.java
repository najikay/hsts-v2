package server.db.projections;

import server.db.entities.Difficulty;

/**
 * One question the auto-composer may pick (E7.4 — F3.2, contract §7.3).
 *
 * <p>The latest version of a live question in one course, carrying exactly what
 * {@code AutoComposeResult} needs to describe a proposal and what the shortfall report needs to
 * count. It is the pool row and the counted row at once, deliberately: §7.2 property 2 requires
 * that {@code available} be a number the teacher can verify by filtering her own bank, and the
 * cheapest way to guarantee the count and the selection agree is for both to read the same rows.
 *
 * <h2>Carries no answer key</h2>
 *
 * <p>No {@code correctAnswer}, and no field one could be put in. The auto-composer decides which
 * questions go on a paper and never needs to know which option is right, so this projection has
 * the same shape as the bank's browse row rather than the authoring one. That keeps it outside
 * the family of key-bearing types the correctness-boundary tests police.
 *
 * <h2>Why the image is a boolean</h2>
 *
 * <p>Computed with a {@code case} in the query so the {@code MEDIUMBLOB} is never in the SELECT
 * list. A criteria grid asking for forty questions would otherwise move up to 80MB to decide
 * which forty display ids to propose.
 *
 * @param questionVersionId the version to pin, which is what a composition stores
 * @param questionId        the owning question, for the "no question twice, across quotas as well
 *                          as within one" rule of §7.4 - two versions of one question are one
 *                          candidate as far as duplication is concerned
 * @param displayId5        course(2) + serial(3), the id she reads (S-8)
 * @param text              the stem, for the proposal she reviews before saving
 * @param topic             the latest version's topic, as stored. Bucketed by
 *                          {@code QuestionValidator.sameTopic}, not by {@code equals}: bank
 *                          ruling 7.6's "exact" means the column's own
 *                          {@code utf8mb4_unicode_ci} exactness, which folds case and accents,
 *                          and NOT Java string equality. Reading it the other way is what would
 *                          split one candidate pool into two buckets
 * @param difficulty        the latest version's difficulty, the <b>entity</b> enum as every
 *                          projection carries, converted by name at the service boundary the way
 *                          {@code BankBrowseService} and {@code QuestionService} already do
 * @param hasImage          whether an illustration exists, without carrying it
 * @param versionNo         the latest version number; a fresh proposal pins the latest, so this
 *                          is both the pinned and the latest number on the wire record
 */
public record AutoCandidate(long questionVersionId,
                            long questionId,
                            String displayId5,
                            String text,
                            String topic,
                            Difficulty difficulty,
                            boolean hasImage,
                            int versionNo) {
}
