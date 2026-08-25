package common.dto.authoring;

import java.io.Serializable;

/**
 * One row of the auto-composer's criteria grid (Common tier, E7.4/E7.13 — F3.2, T-3.4).
 *
 * <p>A topic and how many questions of each difficulty are wanted from it. Four buckets, and the
 * fourth is the one that makes T-3.4's "mixed difficulty" expressible: {@link #any()} asks for
 * that many questions of <em>whatever</em> difficulty, which is a different request from asking
 * for a specific split and is not the same as leaving the three graded buckets at zero.
 *
 * <h2>There is deliberately no total field</h2>
 *
 * <p>The total is the sum of every bucket in every quota, derived in one place —
 * {@link #total()} here and {@link AutoComposeRequest#totalRequested()} across the request. A
 * request that carried its own total could carry one that disagreed with its own breakdown, and
 * that disagreement is the single most likely defect in this payload. It is removed rather than
 * validated.
 *
 * <h2>{@code null} topic means the whole course</h2>
 *
 * <p>A quota with no topic draws from every topic in the exam's course, which is how a teacher
 * asks for "ten more, anywhere". The compact constructor strips and folds blank to {@code null},
 * so a criteria row bound to an empty text field and one bound to nothing are the same row and
 * every later decision is a null test. That normalisation is also what makes the distinctness
 * rule below checkable at all: {@code ""} and {@code null} would otherwise be two spellings of
 * one bucket.
 *
 * <h2>What the handler checks, and this record does not</h2>
 *
 * <p>Per the package javadoc, {@code ExamValidator} owns the rules and answers
 * {@code VALIDATION} naming the field:
 *
 * <ul>
 *   <li><b>every bucket is at least zero and the total across all quotas is at least one</b>
 *       (contract section 5.3) — a request for nothing is not a composition, and a negative
 *       bucket would subtract from a sibling quota's demand;</li>
 *   <li><b>topics are distinct within one request</b> (section 5.3). Two quotas naming one topic
 *       break the disjointness that makes most-constrained-first selection produce true
 *       shortfalls: the two would compete for one candidate pool and the report could then name
 *       a shortfall the teacher can disprove by filtering her own bank, which section 7.2 calls
 *       the worst possible failure here.</li>
 * </ul>
 *
 * <p>Neither is checked in this constructor. A throw here would run on the socket read thread
 * during deserialization and kill the connection (E1.11) instead of answering a sentence.
 *
 * @param topic  the topic to draw from, or {@code null} for any topic in the course. Matched by
 *               <b>the collation's equality</b> — {@code utf8mb4_unicode_ci}, as measured in #48,
 *               reproduced service-side by {@code QuestionValidator.sameTopic}. Never Java
 *               {@code equals}, and never a fold stricter or looser than the column: the point of
 *               ruling 7.6 is that the auto-composer and the bank's own filter can never disagree
 *               about what a topic is, and both directions of that agreement are load-bearing.
 *               Said as "exact equality" here until 2026-08-25, which was loose rather than wrong
 *               — ruling 7.6 chose option A over a normalising filter, and the filter it ruled on
 *               runs in SQL (EXAM_BUILDER_WIRE_CONTRACT §7.3)
 * @param easy   how many EASY questions are wanted from it
 * @param medium how many MEDIUM questions are wanted from it
 * @param hard   how many HARD questions are wanted from it
 * @param any    how many questions of any difficulty are wanted from it (T-3.4)
 */
public record TopicQuota(String topic, int easy, int medium, int hard, int any)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Folds a blank topic to {@code null}, so "any topic" has one representation. */
    public TopicQuota {
        topic = ExamCreateRequest.blankToNull(topic);
    }

    /**
     * @param topic the topic to draw from
     * @param any   how many questions of any difficulty
     * @return the mixed-difficulty row T-3.4 walks, with the three graded buckets empty
     */
    public static TopicQuota ofAnyDifficulty(String topic, int any) {
        return new TopicQuota(topic, 0, 0, 0, any);
    }

    /** @return {@code true} when this quota draws from the whole course rather than one topic. */
    public boolean isCourseWide() {
        return topic == null;
    }

    /**
     * @return how many questions this row asks for in total. Derived, never sent: see the class
     *         javadoc
     */
    public int total() {
        return easy + medium + hard + any;
    }

    /** @return {@code true} when this row asks for nothing at all. */
    public boolean isEmpty() {
        return total() == 0;
    }
}
