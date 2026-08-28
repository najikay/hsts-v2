package client.ui.shell;

import client.core.Navigator;
import client.core.Route;
import client.ui.anim.Animations;
import client.ui.anim.Motion;
import client.ui.components.BackLink;
import client.ui.components.Buttons;
import client.ui.components.Icons;
import client.ui.components.Logo;
import client.ui.components.ReconnectBanner;
import client.ui.components.RoleBadge;
import client.ui.components.ToastStack;
import client.ui.components.WarnConfirm;
import client.ui.theme.ThemeMode;
import client.ui.theme.ThemeState;
import common.dto.auth.Role;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The chrome every signed-in screen lives inside (Presentation tier, E4.10).
 *
 * <p>A 56px top navbar (logo · breadcrumbs · bell · avatar chip) over a 224px
 * collapsible side rail and a content area — the Teacher/Student dashboard
 * mockups. Two design decisions carry the epic:
 *
 * <ul>
 *   <li><b>data-driven rail.</b> The shell is handed a {@code List<NavItem>};
 *       roles differ by their list, not by their shell (F1.2). Adding the
 *       Principal's read-only menu is a list of constants.</li>
 *   <li><b>state lives in {@link ShellState}.</b> Active item, badge counts and
 *       the responsive collapse rule are model decisions, unit-tested there;
 *       this class only rebuilds nodes when the model says something changed.</li>
 * </ul>
 *
 * <p>The shell also owns the two overlays every screen may need — the
 * {@link ToastStack} (F11.3) and the {@link ReconnectBanner} (E4.6) — so screens
 * reach them through the shell instead of each mounting their own.
 *
 * <p>It owns the way out of a screen for the same reason. The navbar carries a
 * Back control on every route the rail cannot reach; see
 * {@link #renderBackControl()} for why that belongs here and not on the screens.
 */
public final class AppShell extends BorderPane {

    private final ShellState state;
    private final Navigator navigator;

    private final VBox rail = new VBox();
    private final HBox backSlot = new HBox();
    private final HBox breadcrumbs = new HBox();
    private final StackPane contentHost = new StackPane();
    private final ToastStack toasts = new ToastStack();
    private final StackPane popovers = new StackPane();
    private final ReconnectBanner reconnectBanner = new ReconnectBanner();
    private final Button bell = Buttons.icon(Icons.BELL, "Notifications");
    private final StackPane bellBadge = new StackPane();
    private final Label bellBadgeText = new Label();
    private final HBox avatarChip = new HBox();
    private final Button railToggle = Buttons.icon(Icons.MENU, "Collapse menu");
    private final Button logoutButton = Buttons.icon(Icons.LOGOUT, "Sign out");
    private final ContextMenu profileMenu = new ContextMenu();

    /**
     * @param navigator routes rail clicks; the shell listens to it for the active
     *                  item and the breadcrumb trail
     * @param state     the shell model (usually a fresh {@link ShellState})
     */
    public AppShell(Navigator navigator, ShellState state) {
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.state = Objects.requireNonNull(state, "state");
        getStyleClass().add("hsts-shell");

        setTop(buildTop());
        setLeft(rail);
        setCenter(buildCenter());

        rail.getStyleClass().add("hsts-rail");
        railToggle.setOnAction(e -> {
            state.toggleCollapsed();
            Animations.fadeIn(rail, Motion.FAST_MS);
        });

        state.onChange(this::refresh);
        navigator.addListener(event -> {
            state.setActiveRoute(event.to().routeId());
            renderBreadcrumbs(event.to().route());
        });
        // The rail's width follows the window (PRD §4.1: collapse below 1400px).
        widthProperty().addListener((obs, old, width) -> state.applyWindowWidth(width.doubleValue()));

        refresh();
    }

    /** @return the shell model, for badge updates from push handlers. */
    public ShellState state() {
        return state;
    }

    /** @return the toast overlay every screen posts feedback to (F11.3). */
    public ToastStack toasts() {
        return toasts;
    }

    /** @return the reconnect banner (E4.6). */
    public ReconnectBanner reconnectBanner() {
        return reconnectBanner;
    }

    /** @return the notification bell, for wiring the panel in E5/F11.2. */
    public Button bell() {
        return bell;
    }

    /**
     * The layer anything anchored to the navbar drops down into: the
     * notifications panel today (E17.4), any future navbar menu.
     *
     * <p>A layer inside the shell rather than a {@code Popup} in its own window,
     * for three reasons: it inherits the scene's stylesheets and the dark-mode
     * root class for free, it moves with the window without a listener, and it is
     * reachable from a {@code scene.lookup(...)} so TestFX can assert on it.
     *
     * @return the popover host; add a node to open, remove it to close
     */
    public StackPane popovers() {
        return popovers;
    }

    /** Replaces the rail contents — called once the role is known. */
    public void setNavItems(List<NavItem> items) {
        state.setItems(items);
    }

    /** Fills the navbar's avatar chip with the signed-in user (F1.2). */
    public void setUser(String fullName, Role role) {
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(role, "role");

        Label monogram = new Label(Initials.of(fullName));
        StackPane avatar = new StackPane(monogram);
        avatar.getStyleClass().add("hsts-avatar");

        Label name = new Label(fullName);
        name.getStyleClass().add("avatar-name");

        avatarChip.getChildren().setAll(avatar, name, new RoleBadge(role));
        // setUser may run after setOnLogout (or again on a re-login); the sign-out
        // affordance must survive the rebuild.
        if (logoutButton.getOnAction() != null) {
            avatarChip.getChildren().add(logoutButton);
        }
        avatarChip.setAccessibleText(fullName + ", " + RoleBadge.displayName(role));
    }

    /**
     * Wires the sign-out affordance next to the avatar (F1.4, E5.7).
     *
     * <p>The confirmation lives here rather than in the caller because it is
     * presentation: {@link WarnConfirm} needs the owner window, and the sequence
     * that actually ends the session ({@code LOGOUT} → evict screens → clear
     * shell → Login) is the caller's business and is testable without a toolkit.
     *
     * @param action run only when the user confirms
     */
    public void setOnLogout(Runnable action) {
        Objects.requireNonNull(action, "action");
        logoutButton.setOnAction(e -> {
            boolean confirmed = WarnConfirm.show(getScene() == null ? null : getScene().getWindow(),
                    WarnConfirm.spec("Sign out?")
                            .explanation("You will be returned to the sign-in screen. "
                                    + "Anything you have not saved is lost.")
                            .confirmText("Sign out")
                            .cancelText("Stay signed in"));
            if (confirmed) {
                action.run();
            }
        });
        if (!avatarChip.getChildren().contains(logoutButton)) {
            avatarChip.getChildren().add(logoutButton);
        }
    }

    /** @return the sign-out button, for tests and keyboard shortcut wiring. */
    public Button logoutButton() {
        return logoutButton;
    }

    /**
     * Turns the avatar chip into a working menu (UI wave 1 — F-12).
     *
     * <p>The chip has always been styled as a control: a rounded, bordered,
     * hoverable pill with a name in it. It did nothing. A thing that looks
     * pressable and is not is worse than a plain label, because the user spends a
     * click finding out, and then spends the rest of the session unsure which of
     * the other pills are real.
     *
     * <p>Two decisions, both about scope. The menu carries the <b>theme quick
     * switch</b>, because "follow the OS / always dark" is the one preference a
     * user changes often enough to resent a trip to Settings for, and it is the
     * one the demo shows. It also carries <b>sign out</b>, which already had clean
     * plumbing — {@link #setOnLogout} owns the confirmation and the caller owns
     * the sequence — so the menu item runs the button's own action rather than
     * duplicating either. Nothing else is added: a profile menu that grows a
     * settings shortcut and an about box is a menu nobody reads.
     *
     * <p>A {@link ContextMenu} rather than a hand-built popover, because it
     * already closes on ESC, on a click outside and on a second click of its
     * owner, and re-implementing that here would be a second copy of what
     * {@code Popover} does for the bell.
     *
     * @param theme the state the radio items read and write; the menu is not
     *              installed at all when this is {@code null}, which is what a
     *              shell built outside the app (a component test) gets
     */
    public void installProfileMenu(ThemeState theme) {
        if (theme == null) {
            return;
        }
        avatarChip.getStyleClass().add("interactive");
        avatarChip.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        avatarChip.setOnMouseClicked(event -> {
            if (profileMenu.isShowing()) {
                profileMenu.hide();
                return;
            }
            buildProfileMenu(theme);
            profileMenu.show(avatarChip, Side.BOTTOM, 0, 6);
        });
    }

    /**
     * Rebuilt on every open rather than kept in sync, because the only state it
     * shows is which theme mode is selected, and re-reading it is cheaper and
     * safer than listening for changes made on the Settings screen.
     */
    private void buildProfileMenu(ThemeState theme) {
        profileMenu.getItems().clear();

        ToggleGroup modes = new ToggleGroup();
        for (ThemeMode mode : ThemeMode.values()) {
            RadioMenuItem item = new RadioMenuItem(mode.displayName());
            item.setToggleGroup(modes);
            item.setSelected(theme.mode() == mode);
            item.setOnAction(event -> theme.setMode(mode));
            profileMenu.getItems().add(item);
        }

        // Sign out only when it has somewhere to go. A menu item that is present
        // and inert is the defect this whole method exists to remove.
        if (logoutButton.getOnAction() != null) {
            profileMenu.getItems().add(new SeparatorMenuItem());
            MenuItem signOut = new MenuItem("Sign out");
            signOut.setOnAction(event -> logoutButton.fire());
            profileMenu.getItems().add(signOut);
        }
    }

    /** @return the profile menu, for tests. */
    public ContextMenu profileMenu() {
        return profileMenu;
    }

    /** Swaps the content area, with the house entrance transition. */
    public void setContent(Node content) {
        Objects.requireNonNull(content, "content");
        contentHost.getChildren().setAll(content);
        // UI wave 2's route transition. Every shell-hosted screen arrives through
        // here, which is why the spec is applied in one place rather than per screen.
        Animations.riseIn(content, Motion.RISE_DISTANCE, Motion.ROUTE_MS);
    }

    /** Re-renders the rail, the bell badge and the Back control from the model. */
    public void refresh() {
        renderRail();
        renderBellBadge();
        renderBackControl();
    }

    /**
     * @return the navbar's Back control, present only while the current route is
     *         one the rail cannot reach
     */
    public Optional<Button> backControl() {
        return backSlot.getChildren().stream().findFirst().map(Button.class::cast);
    }

    /**
     * Content, with the toast overlay and the popover layer floating over it.
     *
     * <p>The popover layer carries no background of its own and is
     * {@code pickOnBounds(false)}: a full-scene layer that painted even a
     * transparent fill would swallow every click on the content underneath it,
     * which is exactly the defect {@code GalleryInteractionTest} guards against.
     */
    private StackPane buildCenter() {
        StackPane center = toasts.over(contentHost);
        popovers.getStyleClass().add("hsts-popover-layer");
        popovers.setPickOnBounds(false);
        center.getChildren().add(popovers);
        StackPane.setAlignment(popovers, Pos.TOP_RIGHT);
        return center;
    }

    // ------------------------------------------------------------------ navbar

    private VBox buildTop() {
        HBox navbar = new HBox();
        navbar.getStyleClass().add("hsts-navbar");
        navbar.setAlignment(Pos.CENTER_LEFT);
        navbar.setSpacing(12);

        Label brand = new Label("HSTS");
        brand.getStyleClass().add("brand-name");

        backSlot.getStyleClass().add("hsts-shell-back");
        backSlot.setAlignment(Pos.CENTER_LEFT);

        breadcrumbs.getStyleClass().add("hsts-breadcrumbs");
        breadcrumbs.setAlignment(Pos.CENTER_LEFT);

        bellBadgeText.getStyleClass().add("badge-text");
        bellBadge.getStyleClass().add("hsts-badge");
        bellBadge.getChildren().add(bellBadgeText);
        bellBadge.setMouseTransparent(true);
        // A StackPane stretches resizable children to fill it; without this the
        // badge grows to the bell's size and covers the icon it annotates.
        bellBadge.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        StackPane bellStack = new StackPane(bell, bellBadge);
        bellStack.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(bellBadge, Pos.TOP_RIGHT);

        avatarChip.getStyleClass().add("hsts-avatar-chip");
        avatarChip.setAlignment(Pos.CENTER_LEFT);
        avatarChip.setSpacing(8);

        navbar.getChildren().addAll(
                railToggle, Logo.create(28), brand, separator(), backSlot, breadcrumbs,
                Buttons.spacer(), bellStack, avatarChip);

        // The banner sits under the navbar so it pushes content down rather than
        // covering it — a connection warning must not hide an exam question.
        return new VBox(navbar, reconnectBanner);
    }

    private void renderBreadcrumbs(Route route) {
        breadcrumbs.getChildren().clear();
        state.activeItem().ifPresent(item -> {
            if (!item.routeId().equals(route.id())) {
                breadcrumbs.getChildren().addAll(crumb(item.label(), item.routeId()), crumbSeparator());
            }
        });
        Label current = new Label(route.breadcrumb());
        current.getStyleClass().addAll("crumb", "current");
        breadcrumbs.getChildren().add(current);
    }

    private Label crumb(String text, String routeId) {
        Label label = new Label(text);
        label.getStyleClass().addAll("crumb", "crumb-link");
        label.setOnMouseClicked(e -> navigator.navigate(routeId));
        return label;
    }

    private Label crumbSeparator() {
        Label separator = new Label("/");
        separator.getStyleClass().add("crumb-separator");
        return separator;
    }

    /**
     * Puts a Back control in the navbar for every route the rail cannot reach.
     *
     * <p>The manual test round found the same defect on six screens and named the
     * rule it wanted: a screen that is not on the rail needs a way off it. Six
     * screens had built their own control, each in its own corner, and the ones
     * that had not were simply stuck. This is that rule as one control instead of
     * six, and it holds for the seventh screen too, because it reads the rail
     * rather than a list kept by hand.
     *
     * <p>A rail route deliberately gets nothing. The user can leave it by clicking
     * the item they are already looking at, and a Back beside a highlighted rail
     * item would be answering a question nobody asked.
     */
    private void renderBackControl() {
        backSlot.getChildren().clear();
        String routeId = state.activeRouteId();
        Optional<NavItem> fallback = backFallback(routeId);
        boolean show = routeId != null && state.item(routeId).isEmpty() && fallback.isPresent();
        backSlot.setVisible(show);
        backSlot.setManaged(show);
        if (!show) {
            return;
        }
        String fallbackRouteId = fallback.get().routeId();
        backSlot.getChildren().add(BackLink.action(fallback.get().label(), () -> {
            // History first, because it is where the user actually came from: a drill-in
            // is reachable from its list and from a notification, and only the back-stack
            // knows which of the two this was.
            if (navigator.canGoBack()) {
                navigator.back();
                return;
            }
            // No history means the screen was entered cold (a deep link, a notification
            // into a fresh session). reset() rather than navigate(), so pressing Back on
            // a screen with nothing behind it cannot build a history out of the failure.
            navigator.reset(fallbackRouteId);
        }));
    }

    /**
     * @return where Back goes when there is no history: the rail item this route is
     *         {@linkplain ShellState#alias aliased} to if it has one, and otherwise
     *         the role's home, which is the first rail item on every rail
     *         ({@link RoleNav} puts Dashboard first for all four roles)
     */
    private Optional<NavItem> backFallback(String routeId) {
        if (routeId == null) {
            return Optional.empty();
        }
        Optional<NavItem> aliased = state.activeItem();
        if (aliased.isPresent()) {
            return aliased;
        }
        List<NavItem> items = state.items();
        return items.isEmpty() ? Optional.empty() : Optional.of(items.get(0));
    }

    private void renderBellBadge() {
        String text = state.unreadBadgeText();
        boolean show = !text.isEmpty();
        bellBadgeText.setText(text);
        bellBadge.setVisible(show);
        bellBadge.setManaged(show);
        if (show) {
            Animations.scalePop(bellBadge);
        }
    }

    // -------------------------------------------------------------------- rail

    private void renderRail() {
        boolean collapsed = state.isCollapsed();
        rail.getStyleClass().remove("collapsed");
        if (collapsed) {
            rail.getStyleClass().add("collapsed");
        }
        railToggle.setTooltip(new Tooltip(collapsed ? "Expand menu" : "Collapse menu"));

        rail.getChildren().clear();
        for (NavItem item : state.items()) {
            rail.getChildren().add(railItem(item, collapsed));
        }
    }

    private HBox railItem(NavItem item, boolean collapsed) {
        HBox row = new HBox();
        row.getStyleClass().add("nav-item");
        row.setAlignment(collapsed ? Pos.CENTER : Pos.CENTER_LEFT);
        row.setSpacing(12);
        if (state.isActive(item)) {
            row.getStyleClass().add("active");
        }
        if (!item.enabled()) {
            row.getStyleClass().add("disabled");
        }
        row.getChildren().add(Icons.of(item.icon(), Icons.SIZE_DEFAULT, "nav-icon"));

        if (!collapsed) {
            Label label = new Label(item.label());
            label.getStyleClass().add("nav-label");
            row.getChildren().addAll(label, Buttons.spacer());
        }
        if (item.hasBadge()) {
            row.getChildren().add(badge(item, collapsed));
        }
        // Collapsed items lose their label, and a disabled item owes the user a
        // reason ("Arrives with E9"); both are the same affordance.
        if (collapsed || !item.enabled()) {
            Tooltip.install(row, new Tooltip(item.tooltipText()));
        }
        // Not setDisable(): a disabled Node swallows hover, and the tooltip
        // explaining WHY it is unavailable is the whole point of showing it.
        if (item.enabled()) {
            row.setOnMouseClicked(e -> navigator.navigate(item.routeId()));
        }
        row.setAccessibleText(item.enabled() ? item.label()
                : item.label() + ", unavailable: " + item.tooltipText());
        return row;
    }

    private Node badge(NavItem item, boolean collapsed) {
        StackPane badge = new StackPane();
        badge.getStyleClass().addAll("hsts-badge", "accent");
        if (item.isDotBadge() || collapsed) {
            badge.getStyleClass().add("dot");
        } else {
            badge.getChildren().add(new Label(item.badgeText()));
        }
        return badge;
    }

    private static Region separator() {
        Region line = new Region();
        line.getStyleClass().addAll("hsts-divider", "vertical");
        line.setMinHeight(20);
        line.setMaxHeight(20);
        return line;
    }
}
