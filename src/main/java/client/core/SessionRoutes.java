package client.core;

import client.features.approval.ApprovalQueueView;
import client.features.approval.ExamPreviewView;
import client.features.approval.MyApprovalsView;
import client.features.bank.BankView;
import client.features.bank.QuestionEditorView;
import client.features.bank.QuestionsView;
import client.features.bot.BotAnalyticsView;
import client.features.bot.BotChatView;
import client.features.bot.BotHistoryView;
import client.features.bot.BotManagerView;
import client.features.data.DataView;
import client.features.exam.ExecutionMonitorView;
import client.features.exam.TakeExamView;
import client.features.home.CoordinatorHomeView;
import client.features.home.PrincipalHomeView;
import client.features.home.StudentHomeView;
import client.features.home.TeacherHomeView;
import client.features.grading.GradingQueueView;
import client.features.release.ReleaseManagerView;
import client.features.reports.ReportsView;
import client.features.results.CheckedFormView;
import client.features.results.MyGradesView;
import client.features.results.TeacherResultsView;
import client.features.settings.SettingsView;
import client.ui.screen.AbstractScreen;
import client.ui.screen.ScreenFactory;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * What a role can navigate to once it has signed in (Presentation tier, E5.4).
 *
 * <p>The route table ({@link Routes}) says what destinations <i>exist</i>; this
 * says which of them a given role gets, and which screen class serves each. It is
 * the client half of F1.2 — "menu items, home dashboard and permissions all derive
 * from role" — and it is deliberately the <b>only</b> place that maps a role to
 * screens, so the answer to "can a student reach the question bank?" is one list,
 * not a search through the codebase. (The real answer is the server's, which
 * re-authorises every request; this simply never offers the trip.)
 *
 * <p>Registration is idempotent on purpose. Sign out and back in — as the same
 * user or a different one — re-runs it, and both {@link Navigator} and
 * {@code ScreenFactory} reject duplicate ids by design. Skipping what is already
 * there is what lets the logout path stay a plain "go back to Login" instead of
 * having to unwind the registry.
 */
public final class SessionRoutes {

    private static final Logger log = LoggerFactory.getLogger(SessionRoutes.class);

    private SessionRoutes() {
    }

    /**
     * @param role the signed-in role
     * @return the routes that role may navigate to, home first
     */
    public static List<Route> routesFor(Role role) {
        Objects.requireNonNull(role, "role");
        List<Route> routes = new ArrayList<>();
        routes.add(Routes.home(role));
        routes.add(Routes.SETTINGS);
        if (teaches(role)) {
            // The legacy bank screen is the one feature screen that already works
            // end-to-end (over the DAO); E6 replaces it with the versioned bank.
            routes.add(Routes.QUESTIONS);
            // The versioned bank (E6.9). Teaching roles only: it carries Delete, and E6.10
            // adds Edit, so the principal reads the bank through the Data screen instead
            // (the lead's ruling on #41). Off the rail until the retirement PR; reached
            // from the legacy screen's banner.
            routes.add(Routes.BANK);
            // The question editor (E6.10). Registered with the bank, reached from it; the
            // server re-checks the write scope and the edit lock on every save, so this
            // list decides what is offered and never what is permitted.
            routes.add(Routes.QUESTION_EDIT);
            // The release manager (E9). Teaching roles only. It is also the screen the
            // monitor is normally reached from, which is why the two are registered
            // together: a monitor route with no way in would be a dead end.
            routes.add(Routes.RELEASES);
            // The live monitor (E11). Teaching roles only, and the server re-checks
            // ownership on every request: this list decides what is offered, never
            // what is permitted.
            routes.add(Routes.MONITOR);
            // The study bot's teacher half (E16). Analytics is reached from the
            // manager rather than from the rail: it is a view of one bot, and a
            // rail item that needed a course chosen first would be a dead end.
            routes.add(Routes.BOT_MANAGER);
            routes.add(Routes.BOT_ANALYTICS);
            // Results and statistics (E14). Offered to both teaching roles; the server
            // scopes every answer to the exams the caller wrote (S-35), so this list
            // decides what is offered and never what is permitted.
            routes.add(Routes.RESULTS);
            // Grading (E12). Offered to both teaching roles; the server scopes the queue to the
            // exams the caller wrote and re-checks ownership on every sitting she opens, so this
            // list decides what is offered and never what is permitted.
            routes.add(Routes.GRADING);
            // E8.6's teacher side. Every teacher gets it, coordinators included: it is the
            // surface F4.2's "the reason is visible on the exam" needs, and it is where the
            // rejection notification's reference points.
            routes.add(Routes.EXAMS);
        }
        if (role == Role.COORDINATOR) {
            // Approvals is the one item that separates a coordinator's rail from a
            // teacher's (PRD §3). The preview is registered with it rather than on its own
            // rail item: it is a view of one exam, reached from the queue or from a
            // notification, and the server re-checks the subject on every request.
            routes.add(Routes.APPROVALS);
            routes.add(Routes.EXAM_PREVIEW);
        }
        if (role == Role.STUDENT) {
            // Taking an exam is a student's, and only a student's (E10). A teacher who
            // reached this route would still be refused by the server, which is where
            // enrolment and identity are actually checked.
            routes.add(Routes.TAKE_EXAM);
            // The study bot's student half (E16). History is reached from the chat,
            // for the same reason analytics is reached from the manager.
            routes.add(Routes.BOT_CHAT);
            routes.add(Routes.BOT_HISTORY);
            // Her own grades (E13.3). Offered to students only, but the safety property is
            // not this list: MY_GRADES_GET takes no payload and answers from the session's
            // own id, so a caller reaching the verb by any other means still gets their own
            // grades and nobody else's (E13.1).
            routes.add(Routes.MY_GRADES);
            // The checked form is registered with My Grades rather than on its own rail
            // item: it is a view of one paper, reached from a row, and the server
            // re-checks all three of its conditions on every request.
            routes.add(Routes.CHECKED_FORM);
        }
        if (role == Role.PRINCIPAL) {
            // Her comparison reports (E15.4). A read: REPORT_SUBJECTS_GET and REPORT_GET are the
            // two verbs behind it and there is no third. S-7's "literally zero mutating verbs"
            // is enforced on the server by the role gate; this list is what stops the client
            // offering a trip that would be refused.
            routes.add(Routes.REPORTS);
            // Her data browser (E15.2). The second and last feature route this role gets, and
            // it is a read as well: BANK_LIST, DATA_EXAMS_GET and DATA_RESULTS_GET, all three
            // gated on her role and none of them able to change a row. Between this and the
            // line above, every route she has is a read, which is S-7 expressed as a list.
            routes.add(Routes.DATA);
        }
        return List.copyOf(routes);
    }

    /**
     * Registers this role's routes on the navigator and their builders on the
     * screen factory. Safe to call again for the same or another role.
     */
    public static void register(Navigator navigator, ScreenFactory screens, Role role) {
        Objects.requireNonNull(navigator, "navigator");
        Objects.requireNonNull(screens, "screens");
        for (Route route : routesFor(role)) {
            if (!navigator.isRegistered(route.id())) {
                navigator.register(route);
            }
            if (!screens.isRegistered(route.id())) {
                screens.register(route.id(), builderFor(route, role));
            }
        }
        log.debug("session routes registered for {}: {}", role,
                routesFor(role).stream().map(Route::id).toList());
    }

    /** @return the dashboard this login lands on (T-1). */
    public static Route homeFor(LoginResult login) {
        Objects.requireNonNull(login, "login");
        return Routes.home(login.role());
    }

    /** @return {@code true} for the two roles that author course content (PRD §3). */
    public static boolean teaches(Role role) {
        return role == Role.TEACHER || role == Role.COORDINATOR;
    }

    private static Supplier<AbstractScreen> builderFor(Route route, Role role) {
        if (Routes.SETTINGS.id().equals(route.id())) {
            return SettingsView::new;
        }
        if (Routes.QUESTIONS.id().equals(route.id())) {
            return QuestionsView::new;
        }
        if (Routes.BANK.id().equals(route.id())) {
            return BankView::new;
        }
        if (Routes.QUESTION_EDIT.id().equals(route.id())) {
            return QuestionEditorView::new;
        }
        if (Routes.APPROVALS.id().equals(route.id())) {
            return ApprovalQueueView::new;
        }
        if (Routes.EXAM_PREVIEW.id().equals(route.id())) {
            return ExamPreviewView::new;
        }
        if (Routes.EXAMS.id().equals(route.id())) {
            return MyApprovalsView::new;
        }
        if (Routes.TAKE_EXAM.id().equals(route.id())) {
            return TakeExamView::new;
        }
        if (Routes.RELEASES.id().equals(route.id())) {
            return ReleaseManagerView::new;
        }
        if (Routes.MONITOR.id().equals(route.id())) {
            return ExecutionMonitorView::new;
        }
        if (Routes.BOT_CHAT.id().equals(route.id())) {
            return BotChatView::new;
        }
        if (Routes.BOT_HISTORY.id().equals(route.id())) {
            return BotHistoryView::new;
        }
        if (Routes.BOT_MANAGER.id().equals(route.id())) {
            return BotManagerView::new;
        }
        if (Routes.BOT_ANALYTICS.id().equals(route.id())) {
            return BotAnalyticsView::new;
        }
        if (Routes.RESULTS.id().equals(route.id())) {
            return TeacherResultsView::new;
        }
        if (Routes.MY_GRADES.id().equals(route.id())) {
            return MyGradesView::new;
        }
        if (Routes.CHECKED_FORM.id().equals(route.id())) {
            return CheckedFormView::new;
        }
        if (Routes.GRADING.id().equals(route.id())) {
            return GradingQueueView::new;
        }
        if (Routes.REPORTS.id().equals(route.id())) {
            return ReportsView::new;
        }
        if (Routes.DATA.id().equals(route.id())) {
            return DataView::new;
        }
        return homeBuilder(role);
    }

    private static Supplier<AbstractScreen> homeBuilder(Role role) {
        return switch (role) {
            case TEACHER -> TeacherHomeView::new;
            case COORDINATOR -> CoordinatorHomeView::new;
            case STUDENT -> StudentHomeView::new;
            case PRINCIPAL -> PrincipalHomeView::new;
        };
    }
}
