package client.features.bot;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import common.dto.auth.CourseRef;
import common.dto.bot.BotManagerPage;
import common.dto.bot.BotProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Every course the teacher teaches, and the state of each one's study bot (Presentation tier,
 * E16.12 — F12.1, S-30).
 *
 * <p>Added 2026-08-29, manual round 3, U-26. FX-free like {@link BotManagerSession}, so the
 * master half of the manager screen is a plain JUnit assertion too.
 *
 * <h2>One {@link BotManagerSession} per course, and nothing shared between them</h2>
 *
 * <p>This holds a session per taught course and owns no page of its own. That is the property
 * U-26 turns on: a create, a toggle or a source change addressed to one course reaches that
 * course's session, folds that course's answer in, and cannot touch a sibling — there is no
 * shared page for it to touch. The screen the list replaces built one session and rebuilt it on
 * every course change, which was correct for one bot at a time and is exactly what made a
 * two-course teacher read "one bot" off a screen showing one card.
 *
 * <h2>Why the list reads per course rather than asking for a summary</h2>
 *
 * <p>The BOT wire contract is frozen and every teacher verb is addressed by course code, so a
 * list could have been bought with an amendment adding a summary verb. It is not, and the reason
 * is arithmetic: a teacher has two or three courses, {@code BOT_MANAGER_GET} is a single indexed
 * read the server already serves, and the answer it gives is exactly the page the detail pane
 * needs the moment she selects that course. A summary verb would have been a second shape of the
 * same fact, kept in step with the first by hand. <b>No wire change (contract §10 unchanged).</b>
 *
 * <p>The cost is n requests on show instead of one. They are issued together and folded in as
 * they land, so the list fills in rather than blocking, and every card says which of the two it
 * is doing ({@link BotCourseSummary#loaded()}).
 */
public final class BotManagerListSession {

    private final List<CourseRef> courses;
    private final Map<String, BotManagerSession> byCourse = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    private String selectedCourse = "";

    /**
     * @param dispatcher the shared request correlator
     * @param poster     the FX-thread seam (M-4), handed to every child session
     * @param courses    the courses this teacher teaches, in the order the list shows them;
     *                   normally {@code LoginResult.courses()}
     */
    public BotManagerListSession(RequestDispatcher dispatcher, FxThreadPoster poster,
                                 List<CourseRef> courses) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(poster, "poster");
        this.courses = List.copyOf(Objects.requireNonNull(courses, "courses"));
        for (CourseRef course : this.courses) {
            BotManagerSession session = new BotManagerSession(dispatcher, poster, course.code());
            session.onChange(this::changed);
            byCourse.put(key(course.code()), session);
        }
        // The first taught course is selected up front, so a teacher of one course sees her bot
        // on arrival exactly as she did before U-26 and never pays a click for a list of one.
        selectedCourse = this.courses.isEmpty() ? "" : this.courses.get(0).code();
    }

    // ===================== Reads =========================================

    /** @return the courses the list draws a card for, in order. */
    public List<CourseRef> courses() {
        return courses;
    }

    /** @return {@code true} when this teacher is attached to no course at all. */
    public boolean isEmpty() {
        return courses.isEmpty();
    }

    /** @return the selected course code, or empty when there is nothing to select. */
    public String selectedCourse() {
        return selectedCourse;
    }

    /** @return the selected course's session, which is what the detail pane renders. */
    public Optional<BotManagerSession> selected() {
        return sessionFor(selectedCourse);
    }

    /**
     * @param courseCode a course code, matched the way the rest of this feature matches them
     * @return that course's own session, or empty when she does not teach it
     */
    public Optional<BotManagerSession> sessionFor(String courseCode) {
        return Optional.ofNullable(byCourse.get(key(courseCode)));
    }

    /**
     * @return one summary per taught course, in the list's order
     */
    public List<BotCourseSummary> summaries() {
        List<BotCourseSummary> rows = new ArrayList<>(courses.size());
        for (CourseRef course : courses) {
            rows.add(summaryOf(course));
        }
        return List.copyOf(rows);
    }

    /** @return {@code true} while any course's read or write is still in flight (NFR-21). */
    public boolean isBusy() {
        return byCourse.values().stream().anyMatch(BotManagerSession::isBusy);
    }

    // ===================== Selection =====================================

    /**
     * Selects a course, which is what the deep link and a click on a card both do.
     *
     * <p>A code she does not teach is refused rather than selected: the notification and the
     * analytics Back both carry a course code from somewhere else, and landing on a blank detail
     * pane is worse than landing on the course the screen was already showing.
     *
     * @param courseCode the course to show on the right
     * @return {@code true} when the selection moved to that course
     */
    public boolean select(String courseCode) {
        if (courseCode == null || !byCourse.containsKey(key(courseCode))) {
            return false;
        }
        if (!courseCode.equalsIgnoreCase(selectedCourse)) {
            selectedCourse = courseCode;
            changed();
        }
        return true;
    }

    /**
     * @param courseCode a course code
     * @return {@code true} when that is the course the detail pane is showing
     */
    public boolean isSelected(String courseCode) {
        return courseCode != null && courseCode.equalsIgnoreCase(selectedCourse);
    }

    // ===================== Requests ======================================

    /**
     * Reads every taught course's page (F12.1).
     *
     * <p>All of them are sent before any of them is waited on, so the list fills in as the
     * answers land rather than one course at a time. Each answer is folded into its own session,
     * so one course refusing leaves the other cards correct and puts its sentence on the course
     * it belongs to.
     *
     * @return a future completing when every course has answered, or failed
     */
    public CompletableFuture<Void> refreshAll() {
        CompletableFuture<?>[] reads = byCourse.values().stream()
                .map(BotManagerSession::refresh)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(reads);
    }

    // ===================== Change notification ===========================

    /** Subscribes a renderer; called on every child's change and on every selection. */
    public void onChange(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void changed() {
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }

    // ===================== Internals =====================================

    private BotCourseSummary summaryOf(CourseRef course) {
        BotManagerSession session = byCourse.get(key(course.code()));
        if (session == null) {
            return new BotCourseSummary(course.code(), course.name(), null,
                    false, false, 0, false);
        }
        BotManagerPage page = session.page();
        BotProfile bot = page.bot();
        // The bot's own courseName is preferred where there is one: it is the server's read of
        // the course, and the sign-in payload's copy is as old as the sign-in.
        String name = bot == null ? course.name() : bot.courseName();
        return new BotCourseSummary(course.code(), name,
                bot == null ? null : bot.name(),
                page.exists(),
                bot != null && bot.active(),
                page.sourceCount(),
                session.isLoaded());
    }

    private static String key(String courseCode) {
        return courseCode == null ? "" : courseCode.trim().toLowerCase(Locale.ROOT);
    }
}
