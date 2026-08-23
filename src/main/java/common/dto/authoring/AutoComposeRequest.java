package common.dto.authoring;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The {@code EXAM_AUTO_COMPOSE} payload: what the teacher wants a generated paper to look like
 * (Common tier, E7.4/E7.13 — F3.2, F3.3 ⚑).
 *
 * <h2>The verb this travels on writes nothing at all</h2>
 *
 * <p>{@code EXAM_AUTO_COMPOSE} is a pure read that answers with a proposal or with a shortfall
 * report, and never with a row. That is what makes T-3.5's "<b>No exam is created</b>" true by
 * construction rather than by a rollback that has to work: there was never a write to undo, no
 * exam, no version, and no allocated serial. A feasible proposal comes back as
 * {@link AutoComposeResult#questions()} already totalling
 * {@link ExamCreateRequest#POINTS_TOTAL}, which the client then sends to {@code EXAM_CREATE} —
 * so the auto path is savable in one click, which is what T-3.4 walks.
 *
 * <h2>The seed, disclosed rather than found</h2>
 *
 * <p>{@link #seed()} is nullable and {@code null} means random; the real client sends
 * {@code null}. It is on the wire partly so tests can pin a selection, and the contract's
 * section 7.5 says so out loud rather than leaving a reviewer to notice it.
 *
 * <p>It would be here without the tests. A teacher who says "it gave me a strange set" cannot be
 * helped if nobody can reproduce it, and a seed echoed into the server log is the difference
 * between reproducing her result and asking her to try again.
 *
 * <h2>What the handler checks, and this record does not</h2>
 *
 * <p>Per the package javadoc: {@code requireTeachesCourse} <b>throws</b> {@code FORBIDDEN} on
 * {@code courseCode}, because a refusal naming a course she already named tells her nothing she
 * did not know. {@code ExamValidator} then owns the two quota rules of contract section 5.3 —
 * every bucket at least zero with a total of at least one across the request, and topics
 * distinct within it — each answering {@code VALIDATION} naming the field. See
 * {@link TopicQuota} for why distinctness in particular is load-bearing rather than tidy.
 *
 * <p>None of that is in this constructor, which normalises and never throws: a throw here runs
 * on the socket read thread during deserialization and kills the connection (E1.11).
 *
 * @param courseCode the course to draw from; {@code strip()}ped, never {@code trim()}ped, for
 *                   the reason and with the measured limit
 *                   {@link ExamCreateRequest#strip(String)} gives
 * @param quotas     the criteria grid; never {@code null} after construction, tolerantly copied
 * @param seed       pins the random choice within each quota, or {@code null} for a fresh one
 */
public record AutoComposeRequest(String courseCode, List<TopicQuota> quotas, Long seed)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Normalises the course code and takes a tolerant copy of the grid; never throws. */
    public AutoComposeRequest {
        courseCode = ExamCreateRequest.strip(courseCode);
        // NOT List.copyOf: it throws on a null ELEMENT, and this constructor runs on the
        // server's socket read thread during deserialization (E1.11). A null quota must survive
        // construction so ExamValidator can refuse it with a named VALIDATION sentence.
        quotas = quotas == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(quotas));
    }

    /**
     * The unseeded form, which is what the real client sends.
     *
     * @param courseCode the course to draw from
     * @param quotas     the criteria grid
     */
    public AutoComposeRequest(String courseCode, List<TopicQuota> quotas) {
        this(courseCode, quotas, null);
    }

    /** @return {@code true} when this request pins its random choice (section 7.5). */
    public boolean hasSeed() {
        return seed != null;
    }

    /**
     * @return how many questions this request asks for across every quota and every bucket.
     *         Derived here and nowhere else, so a request cannot carry a total that disagrees
     *         with its own breakdown. {@code null} quotas are skipped rather than thrown on,
     *         because a tolerant copy admits them and this method must not be the place a
     *         malformed payload explodes
     */
    public int totalRequested() {
        int total = 0;
        for (TopicQuota quota : quotas) {
            if (quota != null) {
                total += quota.total();
            }
        }
        return total;
    }
}
