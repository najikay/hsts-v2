package common.dto.authoring;

import common.dto.bank.Difficulty;

import java.io.Serializable;

/**
 * One line of the infeasibility report: what was asked for, and what the bank actually holds
 * (Common tier, E7.4 — F3.3 ⚑).
 *
 * <p><b>This is the defense moment.</b> F3.3's requirement is not that generation fails
 * politely; it is that the report "states exactly what's missing", and the PRD writes the
 * example out in full:
 *
 * <blockquote>Topic 'Algebra': requested 5 Hard, bank has 2</blockquote>
 *
 * <p>Four fields, and every one of them is in that sentence. Nothing else is needed and nothing
 * else is carried.
 *
 * <h2>The report is data; the sentence is composed once, on the client</h2>
 *
 * <p>Lead's ruling 4 of 2026-08-23. No formatted string travels beside these fields, so the
 * structured report and the sentence cannot disagree — the failure mode carrying both would
 * invite. {@code ExamCopy} composes it in one place, because PRD §4.1's copy rules and the
 * Hebrew rendering around it are client concerns, and the PRD's example sentence is pinned by a
 * copy test rather than by this record's javadoc. This deviates from {@code BankMessages} and
 * {@code ReleaseMessages}, which compose server-side, and the deviation is recorded in the
 * contract rather than left to be discovered.
 *
 * <p>The four shapes the client renders, from contract section 7.1:
 *
 * <table border="1">
 *   <caption>Shortfall to sentence</caption>
 *   <tr><th>Shortfall</th><th>Sentence</th></tr>
 *   <tr><td>{@code ("Recursion", HARD, 1, 0)}</td>
 *       <td>Topic "Recursion": requested 1 Hard, bank has 0.</td></tr>
 *   <tr><td>{@code ("Recursion", null, 3, 2)}</td>
 *       <td>Topic "Recursion": requested 3 questions, bank has 2.</td></tr>
 *   <tr><td>{@code (null, HARD, 10, 4)}</td>
 *       <td>Requested 10 Hard, bank has 4.</td></tr>
 *   <tr><td>{@code (null, null, 40, 31)}</td>
 *       <td>Requested 40 questions, bank has 31.</td></tr>
 * </table>
 *
 * <h2>Two nulls, both meaningful</h2>
 *
 * <p>{@code topic} is {@code null} for a course-wide quota and {@code difficulty} is
 * {@code null} for the {@code any} bucket, mirroring {@link TopicQuota} exactly — a shortfall is
 * the answer to a quota, and answering in a different vocabulary from the question would make
 * the two impossible to line up. This is the one outbound record in the package with no
 * {@code requireNonNull}, for that reason and no other.
 *
 * <h2>What {@code available} counts, and why it has to be exact</h2>
 *
 * <p>The real count in her bank, under her own scope: in the exam's course, not soft-deleted,
 * and its <b>latest</b> version matching the topic and difficulty. Latest, not any — pinning an
 * old version because it used to be Hard would put a question on the paper the bank no longer
 * describes the way she asked for. Topic matching is exact equality, inherited from the bank
 * contract's ruling 7.6, so the auto-composer and the bank's own filter can never disagree about
 * what a topic is.
 *
 * <p>If the number in the sentence is not the number she would see by filtering the bank screen
 * to the same topic and difficulty, the report is worse than nothing, because she will go and
 * look.
 *
 * @param topic       the quota's topic, or {@code null} for a course-wide quota
 * @param difficulty  the quota's difficulty, or {@code null} for the {@code any} bucket
 * @param requested   how many she asked for
 * @param available   how many her bank actually holds, under her own scope
 */
public record Shortfall(String topic, Difficulty difficulty, int requested, int available)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /** @return {@code true} when this line is about one topic rather than the whole course. */
    public boolean isTopicScoped() {
        return topic != null;
    }

    /** @return {@code true} when this line is about one difficulty rather than the
     *          {@code any} bucket. */
    public boolean isDifficultyScoped() {
        return difficulty != null;
    }

    /**
     * @return how many questions are missing, never below zero. Derived rather than carried,
     *         for the reason {@link TopicQuota} carries no total: a third number that could
     *         disagree with the two it is computed from is a defect with somewhere to hide
     */
    public int missing() {
        return Math.max(0, requested - available);
    }
}
