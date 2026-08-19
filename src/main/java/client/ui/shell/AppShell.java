package client.ui.shell;

import client.core.Navigator;
import client.core.Route;
import client.ui.anim.Animations;
import client.ui.anim.Motion;
import client.ui.components.Buttons;
import client.ui.components.Icons;
import client.ui.components.Logo;
import client.ui.components.ReconnectBanner;
import client.ui.components.RoleBadge;
import client.ui.components.ToastStack;
import client.ui.components.WarnConfirm;
import common.dto.auth.Role;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;

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
 */
public final class AppShell extends BorderPane {

    private final ShellState state;
    private final Navigator navigator;

    private final VBox rail = new VBox();
    private final HBox breadcrumbs = new HBox();
    private final StackPane contentHost = new StackPane();
    private final ToastStack toasts = new ToastStack();
    private final ReconnectBanner reconnectBanner = new ReconnectBanner();
    private final Button bell = Buttons.icon(Icons.BELL, "Notifications");
    private final StackPane bellBadge = new StackPane();
    private final Label bellBadgeText = new Label();
    private final HBox avatarChip = new HBox();
    private final Button railToggle = Buttons.icon(Icons.MENU, "Collapse menu");
    private final Button logoutButton = Buttons.icon(Icons.LOGOUT, "Sign out");

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
        setCenter(toasts.over(contentHost));

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

    /** Swaps the content area, with the house entrance transition. */
    public void setContent(Node content) {
        Objects.requireNonNull(content, "content");
        contentHost.getChildren().setAll(content);
        Animations.fadeIn(content, Motion.BASE_MS);
    }

    /** Re-renders the rail and the bell badge from the model. */
    public void refresh() {
        renderRail();
        renderBellBadge();
    }

    // ------------------------------------------------------------------ navbar

    private VBox buildTop() {
        HBox navbar = new HBox();
        navbar.getStyleClass().add("hsts-navbar");
        navbar.setAlignment(Pos.CENTER_LEFT);
        navbar.setSpacing(12);

        Label brand = new Label("HSTS");
        brand.getStyleClass().add("brand-name");

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
                railToggle, Logo.create(28), brand, separator(), breadcrumbs,
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
