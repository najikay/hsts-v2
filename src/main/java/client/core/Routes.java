package client.core;

import common.dto.auth.Role;

import java.util.List;
import java.util.Objects;

/**
 * The client's route table (Presentation tier, E4.2).
 *
 * <p>Every destination is declared once, here, as a constant. Screens navigate
 * with {@code navigator().navigate(Routes.SETTINGS.id())} rather than with a
 * string literal, so a renamed route is a compile error instead of a runtime
 * "No route registered for id".
 *
 * <p>The table splits in two at sign-in, which is the shape of the app's flow
 * (F1.5 → F1.1 → F1.2):
 * <ul>
 *   <li>{@link #preLogin()} — Connect and Login. Registered at boot, full-bleed,
 *       no shell exists yet.</li>
 *   <li>everything else — registered once the role is known
 *       ({@code client.features.home.SessionRoutes}), rendered inside the app
 *       shell. A role only ever gets routes it has a screen for, so a student
 *       cannot navigate to a teacher route even by accident.</li>
 * </ul>
 */
public final class Routes {

    /** Host/port entry, before any session exists (F1.5). Full-bleed, no shell. */
    public static final Route CONNECT = Route.standalone("connect", "Connect to server");

    /** Username/password entry (F1.1). Full-bleed: the shell only exists after it. */
    public static final Route LOGIN = Route.standalone("login", "Sign in");

    /** Teacher dashboard (T-1). */
    public static final Route HOME_TEACHER = Route.shell("home.teacher", "Teacher dashboard", "Dashboard");

    /** Coordinator dashboard — a teacher's home plus the approval queue (T-1). */
    public static final Route HOME_COORDINATOR =
            Route.shell("home.coordinator", "Coordinator dashboard", "Dashboard");

    /** Student dashboard (T-1). */
    public static final Route HOME_STUDENT = Route.shell("home.student", "Student dashboard", "Dashboard");

    /** Principal dashboard — read-only, school-wide (T-1, S-7). */
    public static final Route HOME_PRINCIPAL =
            Route.shell("home.principal", "Principal dashboard", "Dashboard");

    /** Theme mode + accent palette (E4.9). Rendered inside the shell. */
    public static final Route SETTINGS = Route.shell("settings", "Settings");

    /**
     * The E0 prototype question list, kept reachable so the app stays runnable
     * end-to-end while E6 rewrites the real bank screens. Teaching roles only.
     */
    public static final Route QUESTIONS = Route.shell("questions", "Question bank");

    /**
     * Taking an exam: code, identity, paper, and the ending takeover (E10, F6).
     *
     * <p>One route for the whole flow, so "the exam is unreachable once it is
     * over" (F6.4) is a property of one screen rather than a guard on three
     * navigations. The id matches {@code NotificationCatalog.ROUTE_ATTEMPT}, so
     * the "extra time added" notification is clickable straight into it.
     */
    public static final Route TAKE_EXAM = Route.shell("attempt", "Take exam");

    /**
     * The live execution monitor (E11, F7.2). Teaching roles only.
     *
     * <p>The id matches {@code NotificationCatalog.ROUTE_MONITOR}, which is where
     * a C-4 integrity alert navigates.
     */
    public static final Route MONITOR = Route.shell("monitor", "Execution monitor", "Monitor");

    /**
     * The student's chat with a course's study bot (E16, F12.5).
     *
     * <p>One route for one course at a time; which course arrives as a nav
     * parameter, so a student in three courses uses one screen rather than three.
     */
    public static final Route BOT_CHAT = Route.shell("bot.chat", "Study bot", "Study bot");

    /** The student's own past conversations (E16, F12.10). */
    public static final Route BOT_HISTORY = Route.shell("bot.history", "Bot history", "History");

    /**
     * The teacher's Bot Manager (E16, F12.1/F12.3).
     *
     * <p>The id matches {@code NotificationCatalog.ROUTE_BOT_MANAGER}, so the
     * "study bot sources changed" notification is clickable straight into it.
     */
    public static final Route BOT_MANAGER = Route.shell("bot.manager", "Bot manager", "Study bot");

    /**
     * The coordinator's approval queue (E8, F4.1). Coordinators only.
     *
     * <p>The id matches {@code NotificationCatalog.ROUTE_APPROVALS}, so the "an exam is
     * waiting for your approval" notification is clickable straight into it.
     */
    public static final Route APPROVALS = Route.shell("approvals", "Approvals", "Approvals");

    /**
     * One exam version opened for review (E8.4 ⚑, F4.1).
     *
     * <p>Not on any rail: it is a view of one exam, and a rail item that needed an exam
     * chosen first would be a dead end. Reached from the queue, and from an approval
     * notification, both of which carry the version id as a nav parameter.
     */
    public static final Route EXAM_PREVIEW = Route.shell("approvals.preview", "Exam preview");

    /**
     * The teacher's own exams and what became of them (E8.6, F4.2).
     *
     * <p>The id matches {@code NotificationCatalog.ROUTE_EXAMS}, which is where the "your
     * exam was sent back" notification navigates: F4.2 requires the reason to be visible on
     * the exam and not only in a bell, so the notification has to land somewhere real.
     *
     * <p><b>E8 ships the approval-status half of this screen only.</b> E7 owns the exam
     * builder and list and replaces the screen behind this id when it lands; the id is E7's
     * either way, which is why it is spelled the way E7's rail item already spells it.
     */
    public static final Route EXAMS = Route.shell("exams", "My exams", "Exams");

    /** The teacher's anonymised bot usage view (E16, F12.11, S-34). */
    public static final Route BOT_ANALYTICS =
            Route.shell("bot.analytics", "Bot activity", "Bot activity");

    /**
     * Results and statistics for every exam the teacher wrote (E14, F9.2, S-35).
     *
     * <p>The id matches {@code RoleNav.ROUTE_RESULTS}, which is the rail item that has been
     * reserving this slot since E5.4.
     */
    public static final Route RESULTS = Route.shell("results", "Results", "Results");

    private Routes() {
    }

    /**
     * @param role the signed-in role
     * @return the dashboard that role lands on after login (T-1)
     */
    public static Route home(Role role) {
        Objects.requireNonNull(role, "role");
        return switch (role) {
            case TEACHER -> HOME_TEACHER;
            case COORDINATOR -> HOME_COORDINATOR;
            case STUDENT -> HOME_STUDENT;
            case PRINCIPAL -> HOME_PRINCIPAL;
        };
    }

    /** @return the routes reachable before a session exists. */
    public static List<Route> preLogin() {
        return List.of(CONNECT, LOGIN);
    }

    /** @return every route this build defines, for bulk registration and id checks. */
    public static List<Route> all() {
        return List.of(CONNECT, LOGIN, HOME_TEACHER, HOME_COORDINATOR, HOME_STUDENT,
                HOME_PRINCIPAL, SETTINGS, QUESTIONS, TAKE_EXAM, MONITOR,
                APPROVALS, EXAM_PREVIEW, EXAMS,
                BOT_CHAT, BOT_HISTORY, BOT_MANAGER, BOT_ANALYTICS, RESULTS);
    }

    /** Registers Connect and Login — everything the client needs at startup. */
    public static void registerPreLogin(Navigator navigator) {
        preLogin().forEach(navigator::register);
    }

    /** Registers every route on a navigator (tests, and any future single-shot boot). */
    public static void registerAll(Navigator navigator) {
        all().forEach(navigator::register);
    }
}
