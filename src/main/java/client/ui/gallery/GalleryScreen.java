package client.ui.gallery;

import client.core.NavParams;
import client.core.Navigator;
import client.core.Route;
import client.ui.components.Buttons;
import client.ui.components.CountdownTimer;
import client.ui.components.DataTable;
import client.ui.components.EmptyState;
import client.ui.components.FormField;
import client.ui.components.Icons;
import client.ui.components.Logo;
import client.ui.components.ProgressOverlay;
import client.ui.components.ReconnectBanner;
import client.ui.components.RoleBadge;
import client.ui.components.Skeletons;
import client.ui.components.StatusChip;
import client.ui.components.ToastStack;
import client.ui.components.WarnConfirm;
import client.ui.components.logic.CountdownLogic;
import client.ui.components.logic.ValidationState;
import client.ui.screen.AbstractScreen;
import client.ui.shell.Initials;
import client.ui.shell.NavItem;
import client.ui.theme.ThemeControls;
import client.ui.theme.ThemeState;
import common.dto.auth.Role;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Every component, in every state, on one scrollable page (development aid).
 *
 * <p>Reached with {@code --gallery} (or {@code -Dhsts.gallery=true}) and boots
 * without a server, so the design system can be reviewed against the approved
 * mockups by anyone on the team in one command. The theme controls are pinned to
 * the top: flipping mode and palette while the whole library is on screen is the
 * fastest way to catch a component that hard-coded a colour instead of using a
 * token — which is the specific regression this screen exists to prevent.
 *
 * <p>Not a test, and not shipped to users: it has no route, is only reachable
 * through the flag, and is the one screen allowed to construct components with
 * fake data.
 */
public final class GalleryScreen extends AbstractScreen {

    /** A row in the demo table. */
    public record DemoRow(String id, String name, String status, String updated) {
    }

    private final ThemeState theme;
    private final ToastStack toasts = new ToastStack();
    private final ProgressOverlay overlay = new ProgressOverlay("Generating exam…");
    private final ReconnectBanner banner = new ReconnectBanner();

    public GalleryScreen(ThemeState theme) {
        this.theme = Objects.requireNonNull(theme, "theme");
    }

    @Override
    protected Parent build() {
        VBox content = new VBox(28,
                header(),
                section("Buttons", buttonsSection()),
                section("Status chips", chipsSection()),
                section("Role badges", badgesSection()),
                section("Form fields & validation", formSection()),
                section("Countdown timer (E4.18)", countdownSection()),
                section("Empty states", emptySection()),
                section("Skeleton loaders", skeletonSection()),
                section("Data table", tableSection()),
                section("App shell pieces", shellSection()),
                section("Dialogs, toasts & overlays", overlaySection()),
                section("Reconnect banner (E4.6)", bannerSection()),
                section("Typography & surfaces", typographySection()));
        content.setPadding(new Insets(24, 32, 48, 32));
        content.setMaxWidth(1120);

        StackPane centered = new StackPane(content);
        centered.setAlignment(Pos.TOP_CENTER);
        centered.getStyleClass().add("hsts-page");

        ScrollPane scroll = new ScrollPane(centered);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("hsts-page");

        StackPane page = toasts.over(new StackPane(scroll, overlay));
        page.getStyleClass().add("hsts-page");
        return page;
    }

    @Override
    public void onShow(NavParams params) {
        // Nothing to load — the gallery is deliberately data-free.
    }

    // -------------------------------------------------------------- sections

    private Node header() {
        Label title = new Label("HSTS design system");
        title.getStyleClass().add("h1");
        Label subtitle = new Label("Every component in every state. Switch mode and palette to verify tokens.");
        subtitle.getStyleClass().addAll("small", "muted");

        VBox text = new VBox(2, title, subtitle);
        HBox brand = new HBox(14, Logo.create(44), text);
        brand.setAlignment(Pos.CENTER_LEFT);

        HBox controls = new HBox(24,
                ThemeControls.modeToggle(theme),
                ThemeControls.paletteSwatches(theme));
        controls.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(16, brand, controls);
        box.getStyleClass().addAll("hsts-card", "raised");
        return box;
    }

    private Node buttonsSection() {
        FlowPane enabled = flow(
                Buttons.primary("Primary"),
                Buttons.secondary("Secondary"),
                Buttons.outline("Outline"),
                Buttons.danger("Danger"),
                Buttons.warn("Warn"),
                Buttons.styled("Ghost", Buttons.GHOST),
                Buttons.styled("Link", Buttons.LINK),
                Buttons.withIcon("With icon", Icons.EXAMS, Buttons.PRIMARY),
                Buttons.icon(Icons.BELL, "Icon only"));

        FlowPane disabled = flow(
                disable(Buttons.primary("Primary")),
                disable(Buttons.secondary("Secondary")),
                disable(Buttons.outline("Outline")),
                disable(Buttons.danger("Danger")));

        FlowPane sizes = flow(
                Buttons.styled("Small", Buttons.PRIMARY, Buttons.SMALL),
                Buttons.primary("Default"),
                Buttons.styled("Large", Buttons.PRIMARY, Buttons.LARGE));

        return new VBox(12, caption("Enabled"), enabled, caption("Disabled"), disabled,
                caption("Sizes"), sizes);
    }

    private Node chipsSection() {
        FlowPane exam = flow(StatusChip.examStatus("DRAFT"), StatusChip.examStatus("PENDING_APPROVAL"),
                StatusChip.examStatus("APPROVED"), StatusChip.examStatus("REJECTED"));
        FlowPane execution = flow(StatusChip.executionStatus("SCHEDULED"), StatusChip.executionStatus("LIVE"),
                StatusChip.executionStatus("CLOSED"), StatusChip.executionStatus("CANCELLED"));
        FlowPane attempt = flow(StatusChip.attemptStatus("NOT_STARTED"), StatusChip.attemptStatus("IN_PROGRESS"),
                StatusChip.attemptStatus("SUBMITTED"), StatusChip.attemptStatus("TIMED_OUT"));
        FlowPane grade = flow(StatusChip.gradeStatus("AUTO"), StatusChip.gradeStatus("APPROVED"),
                StatusChip.gradeStatus("OVERRIDDEN"));
        FlowPane difficulty = flow(StatusChip.difficulty("EASY"), StatusChip.difficulty("MEDIUM"),
                StatusChip.difficulty("HARD"), StatusChip.examStatus("SOME_FUTURE_STATE"));

        return new VBox(12,
                caption("Exam version (F3.6)"), exam,
                caption("Execution (F5.4) · LIVE pulses"), execution,
                caption("Attempt (F6)"), attempt,
                caption("Grade (F8)"), grade,
                caption("Difficulty, and an unknown server state"), difficulty);
    }

    private Node badgesSection() {
        FlowPane badges = flow(new RoleBadge(Role.STUDENT), new RoleBadge(Role.TEACHER),
                new RoleBadge(Role.COORDINATOR), new RoleBadge(Role.PRINCIPAL));
        return new VBox(12, badges, avatarRow());
    }

    private Node avatarRow() {
        return new HBox(16,
                avatarChip("Dana Cohen", Role.STUDENT),
                avatarChip("Dr. Yossi Levi-Ben Ari", Role.COORDINATOR),
                avatarChip("שרה מזרחי", Role.TEACHER));
    }

    private Node avatarChip(String name, Role role) {
        Label monogram = new Label(Initials.of(name));
        StackPane avatar = new StackPane(monogram);
        avatar.getStyleClass().add("hsts-avatar");

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("avatar-name");

        HBox chip = new HBox(8, avatar, nameLabel, new RoleBadge(role));
        chip.getStyleClass().add("hsts-avatar-chip");
        chip.setAlignment(Pos.CENTER_LEFT);
        return chip;
    }

    private Node formSection() {
        FormField pristine = FormField.text("Server address", "localhost");
        FormField valid = FormField.text("Port", "5555");
        valid.textField().setText("5555");
        valid.apply(ValidationState.valid("Looks good"));
        FormField invalid = FormField.text("Port", "5555");
        invalid.textField().setText("99999");
        invalid.apply(ValidationState.invalid("Port must be between 1 and 65535"));
        FormField required = FormField.text("Execution code", "4 characters").required();
        required.hint("Your teacher reads this out. It is never shown in the app.");

        HBox row = new HBox(16, pristine, valid, invalid, required);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private Node countdownSection() {
        HBox row = new HBox(16,
                timer("Normal", Duration.ofMinutes(42), Duration.ofMinutes(60)),
                timer("Amber (25% left)", Duration.ofMinutes(12), Duration.ofMinutes(60)),
                timer("Red (≤5 min)", Duration.ofMinutes(3), Duration.ofMinutes(60)),
                timer("Expired", Duration.ZERO, Duration.ofMinutes(60)));
        row.setAlignment(Pos.CENTER_LEFT);

        CountdownTimer extendable = new CountdownTimer();
        extendable.start(Instant.now().plus(Duration.ofMinutes(20)), Duration.ofMinutes(60));
        Button extend = Buttons.secondary("Grant +15:00 (F7.1)");
        extend.setOnAction(e -> {
            extendable.logic().extendBy(Duration.ofMinutes(15));
            extendable.showExtension(Duration.ofMinutes(15));
            toasts.info("Time extended", "Your teacher added 15 minutes.");
        });

        HBox extensionRow = new HBox(16, extendable, extend);
        extensionRow.setAlignment(Pos.CENTER_LEFT);
        return new VBox(12, row, caption("Extension moment"), extensionRow);
    }

    private Node timer(String label, Duration remaining, Duration total) {
        CountdownLogic logic = new CountdownLogic(Instant::now);
        logic.syncTo(Instant.now().plus(remaining), total);
        CountdownTimer widget = new CountdownTimer(logic);
        widget.refresh();
        VBox box = new VBox(6, caption(label), widget);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    private Node emptySection() {
        HBox row = new HBox(24,
                new EmptyState(Icons.BANK, "No questions yet",
                        "This course's bank is empty.").action("Add the first question", () -> {}),
                EmptyState.noResults(),
                EmptyState.error("The server did not answer in time.", () -> {}));
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private Node skeletonSection() {
        VBox rows = Skeletons.list(4);
        HBox pieces = new HBox(16, Skeletons.circle(40), Skeletons.title(200), Skeletons.card(180, 64));
        pieces.setAlignment(Pos.CENTER_LEFT);
        return new VBox(16, rows, pieces);
    }

    private Node tableSection() {
        DataTable<DemoRow> table = new DataTable<DemoRow>()
                .title("Executions")
                .searchable("Search executions…",
                        (row, q) -> row.name().toLowerCase().contains(q) || row.id().contains(q));
        table.column("Code", DemoRow::id);
        table.column("Exam", DemoRow::name);
        table.column("Status", DemoRow::status);
        table.column("Updated", DemoRow::updated);
        table.setItems(List.of(
                new DemoRow("4821", "Algebra · midterm", "LIVE", "2 minutes ago"),
                new DemoRow("7C3A", "Calculus · quiz 3", "SCHEDULED", "yesterday"),
                new DemoRow("1190", "Java · final", "CLOSED", "3 days ago")));
        table.setPrefHeight(240);

        DataTable<DemoRow> loading = new DataTable<>();
        loading.column("Code", DemoRow::id);
        loading.showLoading();
        loading.setPrefHeight(200);

        DataTable<DemoRow> empty = new DataTable<>();
        empty.column("Code", DemoRow::id);
        empty.setItems(List.of());
        empty.setPrefHeight(200);

        return new VBox(16, table, caption("Loading and empty states"),
                new HBox(16, loading, empty));
    }

    private Node shellSection() {
        VBox rail = new VBox(2);
        rail.getStyleClass().add("hsts-rail");
        List<NavItem> items = List.of(
                NavItem.of("home", "Dashboard", Icons.DASHBOARD),
                NavItem.of("bank", "Question bank", Icons.BANK).withBadge(3),
                NavItem.of("exams", "Exams", Icons.EXAMS),
                NavItem.of("approvals", "Approvals", Icons.APPROVALS).withBadge(12),
                NavItem.of("bot", "Study bot", Icons.BOT).withDot());
        for (int i = 0; i < items.size(); i++) {
            rail.getChildren().add(staticNavItem(items.get(i), i == 1, false));
        }

        VBox collapsed = new VBox(2);
        collapsed.getStyleClass().addAll("hsts-rail", "collapsed");
        for (int i = 0; i < items.size(); i++) {
            collapsed.getChildren().add(staticNavItem(items.get(i), i == 1, true));
        }

        HBox row = new HBox(24, rail, collapsed, breadcrumbDemo());
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private Node staticNavItem(NavItem item, boolean active, boolean collapsed) {
        HBox row = new HBox(12, Icons.of(item.icon(), Icons.SIZE_DEFAULT, "nav-icon"));
        row.getStyleClass().add("nav-item");
        row.setAlignment(collapsed ? Pos.CENTER : Pos.CENTER_LEFT);
        if (active) {
            row.getStyleClass().add("active");
        }
        if (!collapsed) {
            Label label = new Label(item.label());
            label.getStyleClass().add("nav-label");
            row.getChildren().addAll(label, Buttons.spacer());
            if (item.hasBadge() && !item.isDotBadge()) {
                StackPane badge = new StackPane(new Label(item.badgeText()));
                badge.getStyleClass().addAll("hsts-badge", "accent");
                row.getChildren().add(badge);
            }
        }
        return row;
    }

    private Node breadcrumbDemo() {
        HBox crumbs = new HBox(6);
        crumbs.getStyleClass().add("hsts-breadcrumbs");
        crumbs.setAlignment(Pos.CENTER_LEFT);

        Label parent = new Label("Question bank");
        parent.getStyleClass().addAll("crumb", "crumb-link");
        Label sep = new Label("/");
        sep.getStyleClass().add("crumb-separator");
        Label current = new Label("Question #21014");
        current.getStyleClass().addAll("crumb", "current");
        crumbs.getChildren().addAll(parent, sep, current);

        VBox box = new VBox(12, caption("Breadcrumbs"), crumbs, caption("Bell + badge"), bellDemo());
        return box;
    }

    private Node bellDemo() {
        Button bell = Buttons.icon(Icons.BELL, "Notifications");
        StackPane badge = new StackPane(new Label("9+"));
        badge.getStyleClass().add("hsts-badge");
        badge.setMouseTransparent(true);
        // The badge must not grow to the stack's size, or it covers the bell.
        badge.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE,
                javafx.scene.layout.Region.USE_PREF_SIZE);

        StackPane stack = new StackPane(bell, badge);
        StackPane.setAlignment(badge, Pos.TOP_RIGHT);
        stack.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE,
                javafx.scene.layout.Region.USE_PREF_SIZE);

        HBox row = new HBox(stack);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node overlaySection() {
        Button warn = Buttons.warn("WarnConfirm (warn)");
        warn.setOnAction(e -> WarnConfirm.show(warn.getScene().getWindow(),
                WarnConfirm.spec("Submit with 3 unanswered questions?")
                        .explanation("Unanswered questions score 0. You have 12:04 left, so you can keep working.")
                        .confirmText("Submit anyway")
                        .cancelText("Keep working")
                        .warn()));

        Button danger = Buttons.danger("WarnConfirm (danger)");
        danger.setOnAction(e -> WarnConfirm.show(danger.getScene().getWindow(),
                WarnConfirm.spec("Delete this question?")
                        .explanation("It is not used by any exam. This cannot be undone.")
                        .confirmText("Delete")
                        .danger()));

        Button success = Buttons.secondary("Success toast");
        success.setOnAction(e -> toasts.success("Answer saved", "Question 4 of 20."));
        Button error = Buttons.secondary("Error toast");
        error.setOnAction(e -> toasts.error("Could not save", "The server did not answer. Retrying…"));
        Button info = Buttons.secondary("Info toast");
        info.setOnAction(e -> toasts.info("Time extended", "Your teacher added 15 minutes · new end 11:45"));
        Button burst = Buttons.secondary("Burst (dedup + queue)");
        burst.setOnAction(e -> {
            for (int i = 0; i < 6; i++) {
                toasts.success("Answer saved");
            }
            toasts.info("Different message", "This one is not a duplicate.");
        });

        Button progress = Buttons.secondary("Progress overlay (2s)");
        progress.setOnAction(e -> {
            overlay.show();
            javafx.animation.PauseTransition wait =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
            wait.setOnFinished(done -> overlay.hide());
            wait.play();
        });

        return flow(warn, danger, success, error, info, burst, progress);
    }

    private Node bannerSection() {
        Button lost = Buttons.secondary("Disconnected");
        lost.setOnAction(e -> banner.showDisconnected("192.168.1.42:5555"));
        Button retrying = Buttons.secondary("Reconnecting");
        retrying.setOnAction(e -> banner.showReconnecting(3));
        Button back = Buttons.secondary("Reconnected");
        back.setOnAction(e -> banner.showReconnected());
        banner.setOnRetry(() -> banner.showReconnecting(1));
        return new VBox(12, banner, flow(lost, retrying, back));
    }

    private Node typographySection() {
        VBox text = new VBox(6,
                styledLabel("Heading 1 · page titles", "h1"),
                styledLabel("Heading 2 · card titles", "h2"),
                styledLabel("Heading 3 · section titles", "h3"),
                styledLabel("Body · the default reading size", "body"),
                styledLabel("Muted · secondary metadata", "muted"),
                styledLabel("Faint · timestamps and hints", "faint"),
                styledLabel("Mono · codes and IDs: 4821 · #21014", "mono"));

        VBox card = new VBox(8, styledLabel("Card", "h3"),
                styledLabel("Default surface with a 1px border and 12px radius.", "muted"));
        card.getStyleClass().add("hsts-card");

        VBox raised = new VBox(8, styledLabel("Raised card", "h3"),
                styledLabel("Elevated surface used by dialogs and toasts.", "muted"));
        raised.getStyleClass().addAll("hsts-card", "raised");

        HBox surfaces = new HBox(16, card, raised);
        return new VBox(16, text, surfaces);
    }

    // ---------------------------------------------------------------- helpers

    private static Node section(String title, Node body) {
        Label heading = new Label(title);
        heading.getStyleClass().add("h2");
        VBox box = new VBox(14, heading, body);
        box.getStyleClass().add("hsts-card");
        return box;
    }

    private static Label caption(String text) {
        Label label = new Label(text);
        label.getStyleClass().addAll("small", "faint", "strong");
        return label;
    }

    private static Label styledLabel(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static FlowPane flow(Node... nodes) {
        FlowPane pane = new FlowPane(12, 12, nodes);
        pane.setAlignment(Pos.CENTER_LEFT);
        return pane;
    }

    private static Button disable(Button button) {
        button.setDisable(true);
        return button;
    }

    /**
     * The gallery is not on the route table; this exists so a future dev menu can
     * link to it without the screen inventing its own navigation.
     */
    public static Route devRoute() {
        return Route.standalone("dev.gallery", "Design system gallery");
    }

    /** Registers the gallery on a navigator, for a dev build that wants it routed. */
    public static void registerOn(Navigator navigator) {
        navigator.register(devRoute());
    }
}
