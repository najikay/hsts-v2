package common.dto.bot;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * The teacher's anonymised view of how her bot is used (Common tier, E16.11 —
 * F12.11, S-34 ⚑).
 *
 * <p>The answer to {@code BOT_ANALYTICS_GET}, and the type the S-34 requirement
 * is enforced on. "No student identities anywhere in that view <em>or its
 * DTOs</em>" is not a rule this record follows; it is a rule this record makes
 * unrepresentable, together with {@link BotActivityPoint} and
 * {@link BotTopQuestion}, which are the only two types it reaches. There is no
 * field here that could hold a name, a user id or a session id, so a mapper
 * cannot leak one and a future contributor cannot add one without editing this
 * file — which a reviewer sees, and which {@code BotAnalyticsIdentityGuardTest}
 * fails on.
 *
 * <p>The underlying rows do know who asked: {@code bot_messages.student_id} exists
 * so a student's own history can be reassembled and so a C-4 alert can name the
 * right person to the right teacher. It simply never travels on this verb, and
 * the projection behind it does not select the column at all — the same structural
 * move that keeps {@code correct_answer} out of the take-exam path (E2.12).
 *
 * @param courseName    the course, for the header; a course is not a person
 * @param totalQuestions how many questions the bot has been asked in this course
 * @param activity      questions per day, oldest first
 * @param frequent      the questions asked most often, most frequent first
 */
public record BotAnalytics(String courseName,
                           int totalQuestions,
                           List<BotActivityPoint> activity,
                           List<BotTopQuestion> frequent) implements Serializable {

    private static final long serialVersionUID = 1L;

    public BotAnalytics {
        Objects.requireNonNull(courseName, "courseName");
        totalQuestions = Math.max(0, totalQuestions);
        activity = activity == null ? List.of() : List.copyOf(activity);
        frequent = frequent == null ? List.of() : List.copyOf(frequent);
    }

    /** @return the view for a bot nobody has talked to yet. */
    public static BotAnalytics empty(String courseName) {
        return new BotAnalytics(courseName, 0, List.of(), List.of());
    }

    /** @return {@code true} when there is nothing to draw yet. */
    public boolean isEmpty() {
        return totalQuestions == 0;
    }

    /** @return the busiest day's count, or {@code 0}; what the bars scale against. */
    public int peakPerDay() {
        return activity.stream().mapToInt(BotActivityPoint::count).max().orElse(0);
    }
}
