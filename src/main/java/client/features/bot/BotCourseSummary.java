package client.features.bot;

import java.util.Objects;

/**
 * One line of the Bot Manager's course list (Presentation tier, E16.12 — F12.1, S-30).
 *
 * <p>Added 2026-08-29, manual round 3, U-26. Deliberately <b>not</b> a wire type and
 * deliberately not in {@code common/dto/bot}: every field here is already on the client, either
 * in the sign-in payload ({@code CourseRef}) or in the {@code BotManagerPage} that course's own
 * {@code BOT_MANAGER_GET} answered with. A summary verb would have been a new amendment to a
 * frozen contract bought with nothing, for a teacher who has two or three courses.
 *
 * <p>{@code loaded} is the field the screen would otherwise get wrong. A card built before that
 * course's read lands has {@code hasBot == false} for the same reason a card for a course with
 * genuinely no bot does, and the two must not draw the same: one says "No study bot yet" beside
 * a Create button, the other says it is still looking. Telling a teacher she has no bot for a
 * third of a second is how she ends up creating one she already had.
 *
 * @param courseCode  the 2-character course code (S-30: one bot per course, so this identifies
 *                    the bot as well as the course)
 * @param courseName  the course's display name, from the bot's own page when it has one and
 *                    from the sign-in payload otherwise
 * @param botName     what the teacher called the bot, or {@code null} when there is none yet
 * @param hasBot      whether this course already has a bot
 * @param active      whether students may use it right now (F12.4); {@code false} without a bot
 * @param sourceCount how many pieces of material it answers from (F12.2)
 * @param loaded      whether this course's page has actually arrived
 * @param status      what the server last said about this course, or empty ⚑ (U-39). The
 *                    session already keeps a per-course status line and the list already keeps
 *                    a session per course; what was missing is that the card never showed it,
 *                    so a refusal aimed at one course had nowhere to appear except the detail
 *                    pane. The delete refusal is the one that made that a defect: "This bot has
 *                    4 student conversations" is about the card she pressed
 */
public record BotCourseSummary(String courseCode,
                               String courseName,
                               String botName,
                               boolean hasBot,
                               boolean active,
                               int sourceCount,
                               boolean loaded,
                               String status) {

    public BotCourseSummary {
        Objects.requireNonNull(courseCode, "courseCode");
        courseName = courseName == null || courseName.isBlank() ? courseCode : courseName;
        status = status == null ? "" : status;
        if (!hasBot) {
            // A card cannot claim a name, a state or a count for a bot that does not exist.
            // Enforced here rather than trusted to three call sites (S-30).
            botName = null;
            active = false;
            sourceCount = 0;
        }
    }

    /**
     * The seven-component constructor, retained (U-26's shape).
     *
     * <p>Same rule the wire records follow when a component is appended: the older shape keeps
     * working and delegates, so a caller that has nothing to say about status does not have to
     * say it. Kept because the card's status is a property of the session, not of the course,
     * and a summary built without one is a perfectly honest summary.
     */
    public BotCourseSummary(String courseCode, String courseName, String botName,
                            boolean hasBot, boolean active, int sourceCount, boolean loaded) {
        this(courseCode, courseName, botName, hasBot, active, sourceCount, loaded, "");
    }

    /** @return {@code true} when this card has a sentence from the server to show (U-39). */
    public boolean hasStatus() {
        return !status.isBlank();
    }

    /** @return the course line a card leads with, {@code "11 · Algebra 11"}. */
    public String courseLabel() {
        return courseCode + " · " + courseName;
    }

    /** @return the second line: the bot's name, or what to do about not having one. */
    public String botLabel() {
        if (!loaded) {
            return BotCopy.CARD_LOADING;
        }
        return hasBot ? botName : BotCopy.NO_BOT_YET;
    }

    /** @return the button's label: manage the bot she has, or create the one she has not. */
    public String actionLabel() {
        return hasBot ? BotCopy.MANAGE : BotCopy.CREATE_BOT;
    }

    /** @return the Active / Inactive chip's text (F12.4). */
    public String stateLabel() {
        return active ? BotCopy.ACTIVE_CHIP : BotCopy.INACTIVE_CHIP;
    }

    /** @return the material line, or empty for a course with no bot to count sources for. */
    public String sourcesLabel() {
        return hasBot ? BotCopy.sourceCountLabel(sourceCount) : "";
    }
}
