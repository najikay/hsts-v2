package client.features.login;

import client.core.ConnectPrefs;
import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.core.ServerEndpoint;
import client.core.SessionRoutes;
import client.events.ConnectionWatcher;
import client.features.connect.ConnectFlow;
import client.features.connect.Reconnector;
import client.features.notify.NotificationPresenter;
import client.features.notify.NotificationsModel;
import client.features.notify.NotificationsPanel;
import client.features.notify.NotificationsSession;
import client.net.RequestDispatcher;
import client.ui.components.ReconnectBanner;
import client.ui.shell.AppShell;
import client.ui.shell.RoleNav;
import client.ui.shell.ShellState;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * The two moments that bracket a session: entering the shell and leaving it
 * (Presentation tier, E5.4 / E5.7).
 *
 * <p>Both are sequences, not decisions — every decision inside them belongs to a
 * class that is tested on its own ({@link RoleNav} for the rail, which since U-41
 * reads the sign-in answer's courses as well as its role,
 * {@link SessionRoutes} for the routes, {@link Routes#home} for the landing
 * screen). What is left here is the order, and the order is the part that has
 * bitten this project before:
 *
 * <ul>
 *   <li><b>on entry:</b> record the user first (the dashboards read their courses
 *       from there while building), register routes <i>before</i> navigating (or
 *       the navigation throws), install the shell before the first shell-hosted
 *       navigation (or the dashboard renders full-bleed);</li>
 *   <li><b>on exit:</b> tell the server first — while the socket is still the
 *       signed-in one — then evict every cached screen, then drop the shell and
 *       the user, and only then navigate. Evicting last would leave the previous
 *       user's dashboard instance alive behind the login screen.</li>
 * </ul>
 */
public final class ShellBoot {

    private static final Logger log = LoggerFactory.getLogger(ShellBoot.class);

    /**
     * The bus subscriber raising the reconnect banner for the current shell.
     *
     * <p>Static because the client has exactly one shell at a time — the same
     * reason {@link ScreenManager} is a singleton — and because logout must be
     * able to unregister the subscriber the previous login registered, or a
     * dropped socket would try to paint a banner on a shell that no longer exists.
     */
    private static ConnectionWatcher connectionWatcher;

    /**
     * The bell's session for the current shell (E17.4).
     *
     * <p>Static for the same reason as {@link #connectionWatcher}: there is one
     * shell at a time, and logout has to unsubscribe the subscriber that login
     * registered, or the next user's bell would still be fed by the previous
     * user's session.
     */
    private static NotificationsSession notifications;

    /**
     * How many times Retry has been pressed on the current banner (⚑ U-52).
     *
     * <p>Only the wording uses it — "Reconnecting… (attempt 3)" is the difference
     * between waiting and force-quitting — and it resets with the banner, which is
     * to say with the shell.
     */
    private static int retryAttempt;

    private ShellBoot() {
    }

    /**
     * Builds the role's shell and lands on its dashboard (F1.2, T-1).
     *
     * @param manager the app's screen manager
     * @param login   the server's answer to {@code LOGIN}
     */
    public static void enter(ScreenManager manager, LoginResult login) {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(login, "login");

        manager.setSignedInUser(login);

        AppShell shell = new AppShell(manager.navigator(), new ShellState());
        // Role and courses, not role alone (2026-08-30, live session, U-41): F1.2 derives
        // the shell from both, and a coordinator who teaches nothing was being handed six
        // rail items that could only open an empty screen. The list is the one the sign-in
        // answer already carries, so this costs no round trip and no new verb.
        shell.setNavItems(RoleNav.itemsFor(login.role(), login.courses()));
        // Drill-in routes name their rail parent (2026-08-28, manual round 1, U-8): the
        // shell's navbar Back falls back to it on a cold deep link with no history, and the
        // breadcrumb shows the parent crumb. Registered here, beside the rail itself,
        // because the pairing is role-independent and this is the one place every shell
        // is assembled.
        ShellState state = shell.state();
        // The preview's rail parent depends on who is looking, since 2026-08-30 (Findings.txt,
        // U-53) ⚑. It was Approvals unconditionally, which was right while only a coordinator
        // could reach the screen. A teacher now reaches it from her builder's Preview, and
        // Approvals is not on her rail: ShellState.activeItem looks the alias up IN the rail and
        // answers empty for an item that is not there, so the navbar Back fell through to the
        // first rail item and offered her "Dashboard" on the way out of her own exam. My exams
        // is the item her builder already hangs off, which makes the whole builder-to-preview
        // trip one branch of one rail item.
        state.alias(Routes.EXAM_PREVIEW.id(), login.role() == Role.COORDINATOR
                ? Routes.APPROVALS.id() : Routes.EXAMS.id());
        state.alias(Routes.BOT_HISTORY.id(), Routes.BOT_CHAT.id());
        state.alias(Routes.BOT_ANALYTICS.id(), Routes.BOT_MANAGER.id());
        state.alias(Routes.CHECKED_FORM.id(), Routes.MY_GRADES.id());
        state.alias(Routes.GRADE_REVIEW.id(), Routes.GRADING.id());
        state.alias(Routes.QUESTION_EDIT.id(), Routes.QUESTIONS.id());
        state.alias(Routes.EXAM_BUILD.id(), Routes.EXAMS.id());
        // The principal's three drill-ins (2026-08-30, live session, U-44). All three belong
        // to the Data rail item, because all three are one row of one of its three tabs.
        state.alias(Routes.DATA_QUESTION.id(), Routes.DATA.id());
        state.alias(Routes.DATA_EXAM.id(), Routes.DATA.id());
        state.alias(Routes.DATA_RESULTS.id(), Routes.DATA.id());
        shell.setUser(login.displayName(), login.role());
        startNotifications(manager, shell, login);
        shell.setOnLogout(() -> logout(manager));
        // F-12: the avatar chip has always looked pressable; now it is. Wired after
        // setOnLogout so the menu can offer sign out rather than only the theme.
        shell.installProfileMenu(manager.themeManager() == null
                ? null : manager.themeManager().state());

        watchConnection(manager, shell);
        SessionRoutes.register(manager.navigator(), manager.screens(), login.role());
        manager.setShell(shell);

        // reset(): the connect and login screens must not be reachable with Back
        // once a session exists.
        manager.navigator().reset(Routes.home(login.role()).id());
        log.info("Shell ready for {} ({})", login.displayName(), login.role());
    }

    /**
     * Ends the session and returns to the login screen (F1.4).
     *
     * <p>The {@code LOGOUT} request is fire-and-forget on purpose: the session is
     * freed by the server either way — on the verb, or on the socket drop when the
     * client eventually closes — and blocking a sign-out on a network round trip
     * would leave the user stuck on a screen they asked to leave.
     */
    public static void logout(ScreenManager manager) {
        Objects.requireNonNull(manager, "manager");

        RequestDispatcher dispatcher = manager.getDispatcher();
        if (dispatcher != null) {
            dispatcher.send(Verb.LOGOUT, null).whenComplete((response, failure) -> {
                if (failure != null) {
                    log.debug("LOGOUT was not acknowledged: {}", failure.toString());
                }
            });
        }

        endSessionLocally(manager, NavParams.empty());
        log.info("Signed out; back to the login screen");
    }

    /**
     * Everything a sign-out does on this side of the socket, without the verb
     * (⚑ U-52).
     *
     * <p>Split out of {@link #logout} because a reconnect needs exactly this half
     * and none of the other: the server already freed the session when the socket
     * dropped (F1.4), so sending {@code LOGOUT} down the brand-new anonymous
     * connection would ask a server that has never heard of her to forget her.
     *
     * <p>The order is {@link #logout}'s and is the part that has bitten this
     * project before: evict the cached screens first, then drop the shell and the
     * user, and only then navigate.
     *
     * @param params what the login screen is shown with; a reconnect carries her
     *               username and a sentence, an ordinary sign-out carries nothing
     */
    private static void endSessionLocally(ScreenManager manager, NavParams params) {
        stopWatchingConnection(manager);
        stopNotifications(manager);
        // Nothing of this session survives into the next one: every cached screen
        // goes, including the login screen itself, which therefore comes back blank.
        manager.screens().evictAll();
        manager.clearShell();
        manager.setSignedInUser(null);
        manager.navigator().reset(Routes.LOGIN.id(), params);
    }

    /**
     * Brings the navbar bell to life (E17.4/E17.5).
     *
     * <p>Order is the point again: the badge is seeded from the sign-in answer
     * <i>before</i> the first frame, so the user never sees a zero correct itself
     * a moment later. The list itself is fetched lazily, when the panel opens.
     *
     * <p>Three wires, one for each way notifications reach the user: the badge
     * follows the model, a foreground push also raises a toast (F11.3), and the
     * bell opens the panel.
     */
    private static void startNotifications(ScreenManager manager, AppShell shell, LoginResult login) {
        RequestDispatcher dispatcher = manager.getDispatcher();
        if (dispatcher == null) {
            // No connection (a test harness, or a shell built before connecting):
            // the bell stays silent rather than throwing on a null dispatcher.
            shell.state().setUnreadNotifications(login.unreadNotifications());
            return;
        }
        NotificationsSession session =
                new NotificationsSession(dispatcher, manager.eventBus(), new NotificationsModel());
        // The bell is handed over as the anchor: the panel lines up under it, and
        // a click on it counts as "the owner", not as a click outside (F-6).
        NotificationsPanel panel = new NotificationsPanel(session, manager.navigator(),
                shell.popovers(), shell.bell(), java.time.Clock.systemUTC());

        session.model().onChange(() ->
                shell.state().setUnreadNotifications(session.model().unreadCount()));
        session.onPushed(notification ->
                shell.toasts().show(NotificationPresenter.toastFor(notification)));
        shell.bell().setOnAction(e -> panel.toggle());

        session.start(login.unreadNotifications());
        notifications = session;
    }

    /** Unsubscribes the bell so the next user does not inherit this one's pushes. */
    private static void stopNotifications(ScreenManager manager) {
        if (notifications != null) {
            notifications.stop();
            notifications = null;
        }
    }

    /**
     * Subscribes the banner-raiser for this shell, replacing any previous one, and
     * gives its Retry something to do (⚑ U-52).
     *
     * <p>2026-08-30, Findings.txt, U-52. The banner has offered a Retry button
     * since E4.6 and nothing was ever wired to it here, so pressing it did
     * literally nothing on every screen except Take Exam, which wires its own.
     * That is the defect: the one affordance the product offers for a dropped
     * connection was decoration.
     */
    private static void watchConnection(ScreenManager manager, AppShell shell) {
        stopWatchingConnection(manager);
        retryAttempt = 0;
        ReconnectBanner banner = shell.reconnectBanner();
        banner.setOnRetry(() -> retryConnection(manager, banner));
        connectionWatcher = new ConnectionWatcher(event ->
                banner.showDisconnected(event.serverLabel()));
        manager.eventBus().register(connectionWatcher);
    }

    /**
     * Re-dials the server the banner is complaining about (⚑ U-52).
     *
     * <p>The address is not asked for, because it has not changed: the client lost
     * the network, not the server. {@link Reconnector} resolves it from the pin,
     * the remembered endpoint or the dead client, rebuilds the stack around the
     * dispatcher the app already holds (U-17), and opens the socket off the FX
     * thread. With no endpoint to dial at all there is nothing to guess, and the
     * connect screen is where a person supplies one.
     */
    static void retryConnection(ScreenManager manager, ReconnectBanner banner) {
        Reconnector reconnector =
                new Reconnector(manager, manager.eventBus(), ConnectPrefs.userHome());
        Optional<ServerEndpoint> target = reconnector.endpoint();
        if (target.isEmpty()) {
            banner.hide();
            manager.navigator().navigate(Routes.CONNECT.id());
            return;
        }
        ServerEndpoint endpoint = target.get();

        banner.showReconnecting(++retryAttempt);
        reconnector.redial(
                () -> afterReconnect(manager),
                failure -> banner.showRetryFailed(ConnectFlow.retryFailed(endpoint, failure)));
    }

    /**
     * The socket is back, and she is not signed in on it (⚑ U-52).
     *
     * <p>F1.4: the server frees a session when its connection drops, so the new
     * socket is anonymous whatever the shell still has on screen. Pretending
     * otherwise would leave her clicking a dashboard whose every request comes back
     * refused, which is the shape of the original defect rather than a fix for it.
     * So the shell signs her out locally and Login says what happened, with her
     * username already in the field.
     *
     * <p>Public because the exam screen reaches the same conclusion by a different
     * road: it re-dials, resumes, and the server answers that it has never heard of
     * this attempt. One session end, one sentence, one route.
     *
     * @param manager the app's screen manager
     */
    public static void afterReconnect(ScreenManager manager) {
        Objects.requireNonNull(manager, "manager");
        String username = manager.signedInUser() == null ? "" : manager.signedInUser().username();
        log.info("Reconnected; the session is gone, so {} signs in again",
                username.isBlank() ? "the user" : username);
        endSessionLocally(manager, NavParams.of(
                LoginView.PARAM_USERNAME, username,
                LoginView.PARAM_NOTICE, ConnectFlow.RECONNECTED_SIGN_IN_AGAIN));
    }

    private static void stopWatchingConnection(ScreenManager manager) {
        if (connectionWatcher != null) {
            manager.eventBus().unregister(connectionWatcher);
            connectionWatcher = null;
        }
    }
}
