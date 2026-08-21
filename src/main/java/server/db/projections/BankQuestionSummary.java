package server.db.projections;

import server.db.entities.Difficulty;

import java.time.Instant;

/**
 * One row of the question bank browse (E6.5 - F2.4, T-2.6).
 *
 * <p>Maps to {@code common.dto.bank.BankQuestionRow} at the service boundary.
 *
 * <h2>What is not here, and why each absence is deliberate</h2>
 *
 * <p><b>No answer key, and no answers at all.</b> The bank list is the highest-volume payload
 * in the feature: forty rows for a browse, re-rendered whenever a lock badge changes, and the
 * thing on screen when somebody shares a screenshot. The four options and the key are fetched
 * one question at a time by a verb that names a single question, which the lazy image load
 * already required a round trip for.
 *
 * <p><b>No image bytes, only {@link #hasImage}.</b> This is not a preference, it is the
 * difference between a list and an outage: {@code question_versions.image} is a
 * {@code MEDIUMBLOB} holding up to 2MB, so a projection that selected it would move up to 80MB
 * to render forty rows of text. The query computes the flag with a {@code case when image is
 * null} so the blob is never read.
 *
 * <p><b>Full stem, not truncated.</b> Truncation is a service concern while
 * {@code BANK_WIRE_CONTRACT} §7.5 is still an open ruling: doing it here would mean a query
 * change if the lead prefers the wire to carry everything, and doing it above means a constant.
 *
 * @param displayId    the 5-digit id a teacher quotes (S-8)
 * @param courseCode   2-digit course, for the filter chip
 * @param courseName   resolved here so the list does not need a second lookup per row
 * @param text         the question stem, whole
 * @param topic        what E7.4's auto generator selects on
 * @param difficulty   EASY, MEDIUM or HARD
 * @param versionNo    the <em>latest</em> version's number: the bank always lists the newest
 *                     (F2.3), while exams stay pinned to whichever version they were built from
 * @param hasImage     whether an illustration exists, so the detail pane knows to fetch one
 * @param lastVersionAt the latest version's {@code created_at}. Named for what it is: there is
 *                     no {@code updated_at} column and {@code questions} rows never change
 */
public record BankQuestionSummary(String displayId, String courseCode, String courseName,
                                  String text, String topic, Difficulty difficulty,
                                  int versionNo, boolean hasImage, Instant lastVersionAt) {
}
