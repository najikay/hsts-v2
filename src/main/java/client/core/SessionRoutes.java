package client.core;

import client.features.bank.QuestionsView;
import client.features.exam.ExecutionMonitorView;
import client.features.exam.TakeExamView;
import client.features.home.CoordinatorHomeView;
import client.features.home.PrincipalHomeView;
import client.features.home.StudentHomeView;
import client.features.home.TeacherHomeView;
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
            // The live monitor (E11). Teaching roles only, and the server re-checks
            // ownership on every request: this list decides what is offered, never
            // what is permitted.
            routes.add(Routes.MONITOR);
        }
        if (role == Role.STUDENT) {
            // Taking an exam is a student's, and only a student's (E10). A teacher who
            // reached this route would still be refused by the server, which is where
            // enrolment and identity are actually checked.
            routes.add(Routes.TAKE_EXAM);
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
        if (Routes.TAKE_EXAM.id().equals(route.id())) {
            return TakeExamView::new;
        }
        if (Routes.MONITOR.id().equals(route.id())) {
            return ExecutionMonitorView::new;
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
