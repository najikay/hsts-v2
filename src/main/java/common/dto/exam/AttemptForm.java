package common.dto.exam;

import java.io.Serializable;
import java.util.List;

/**
 * The whole paper, as it stands right now (Common tier, E10.1/E10.6 — F6.1, F6.3).
 *
 * <p>The answer to both {@code ATTEMPT_START} and {@code ATTEMPT_RESUME}, and deliberately
 * the same type for both: starting and resuming differ only in whether any answers have
 * been saved yet, so one shape means the client has one rendering path and a reconnect is
 * not a special case that can rot.
 *
 * <p>Three things travel together here, and the reason is E10.15's "disconnect loses
 * nothing": the questions, the answers the <em>server</em> is holding, and
 * {@link #timing}, which is the server's own word on the clock. A client that was killed
 * and restarted rebuilds its entire state from this one message; it never merges what it
 * remembers with what it is told.
 *
 * <p>{@link #outcome} is non-null exactly when {@link #state} is terminal. That is how a
 * student who was offline when her time ran out learns about it: she re-enters, gets a
 * form whose state is {@code TIMED_OUT}, and the client shows the Time Up takeover with
 * the summary already in hand (E10.14 ⚑). The questions are still carried in that case so
 * the takeover can render the grid it locks.
 *
 * @param attemptId     the attempt, for the save and submit verbs
 * @param header        what exam this is
 * @param questions     the paper, in order, with no correctness anywhere (F6.6)
 * @param savedAnswers  the choices the server holds; empty on a fresh start
 * @param timing        the authoritative clock (S-18)
 * @param state         where this attempt stands
 * @param outcome       how it ended, when it has; {@code null} while in progress
 */
public record AttemptForm(long attemptId,
                          ExamHeader header,
                          List<ExamQuestion> questions,
                          List<SavedAnswer> savedAnswers,
                          AttemptTiming timing,
                          AttemptState state,
                          AttemptOutcome outcome) implements Serializable {

    private static final long serialVersionUID = 1L;

    public AttemptForm {
        questions = questions == null ? List.of() : List.copyOf(questions);
        savedAnswers = savedAnswers == null ? List.of() : List.copyOf(savedAnswers);
        state = state == null ? AttemptState.IN_PROGRESS : state;
        // An outcome on a live attempt would be a contradiction the client would have to
        // arbitrate; drop it here so it cannot arrive.
        outcome = state.isFinished() ? outcome : null;
    }

    /** @return {@code true} while answers may still be saved. */
    public boolean isLive() {
        return state.isLive();
    }

    /** @return how many questions carry a choice right now. */
    public int answeredCount() {
        return savedAnswers.size();
    }

    /** @return the paper's length, the "y" of "answered x/y". */
    public int questionCount() {
        return questions.size();
    }
}
