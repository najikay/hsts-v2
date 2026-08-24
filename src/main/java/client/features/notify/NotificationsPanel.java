package client.features.notify;

import client.core.Navigator;
import client.ui.anim.Animations;
import client.ui.components.Buttons;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.components.Popover;
import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * The bell's drop-down (Presentation tier, E17.4 — F11.2).
 *
 * <p>Header with a "Mark all read" action, then the newest notifications: type
 * icon, title, body, relative time, and an accent rail on the unread ones.
 * Clicking a row marks it read and navigates to whatever it is about.
 *
 * <p>Thin, like every view class here. It holds no state: it renders
 * {@link NotificationsModel} and re-renders whenever the model says something
 * changed, which is what makes "a push lands while the panel is open" work with
 * no code of its own. Every decision it needs — icon, toast flavour, relative
 * time — comes from {@link NotificationPresenter}, which is unit-tested.
 *
 * <p>Where it sits and how it is dismissed is not this class's problem either
 * (UI wave 1, F-6): {@link Popover} anchors it under the bell and closes it on a
 * click outside, on ESC, and on a second click of the bell. Before that it was
 * mounted straight into the popover layer, which centres its children, so it
 * read as a modal with no way out but the bell.
 */
public final class NotificationsPanel extends VBox {

    private static final Logger log = LoggerFactory.getLogger(NotificationsPanel.class);

    /**
     * Wide enough for two lines of body text without becoming a second screen.
     *
     * <p>360 after the wave-2 pass, down from 380: the row now carries a 34px
     * badge on its left, and the same body text in the same panel width would
     * have wrapped a line further.
     */
    private static final double WIDTH = 360;

    /** The rounded square behind a row's type icon. */
    private static final double BADGE = 34;

    /** The unread marker's diameter. */
    private static final double UNREAD_DOT = 7;

    /** Taller than this and the panel would cover the content it is annotating. */
    private static final double MAX_LIST_HEIGHT = 420;

    private final NotificationsModel model;
    private final NotificationsSession session;
    private final Navigator navigator;
    private final Popover popover;
    private final Clock clock;

    private final VBox list = new VBox();
    private final Button markAll = Buttons.styled("Mark all read", Buttons.LINK, Buttons.SMALL);

    /**
     * @param session   the conversation with the server; also the source of the model
     * @param navigator where click-through goes
     * @param host      the shell's popover layer ({@code AppShell.popovers()})
     */
    public NotificationsPanel(NotificationsSession session, Navigator navigator, StackPane host) {
        this(session, navigator, host, null, Clock.systemUTC());
    }

    /**
     * @param anchor the bell; the panel lines up under it and a click on it is
     *               not treated as a click outside. {@code null} parks the panel
     *               in the layer's top-right corner.
     * @param clock  time source for the relative times; a fixed clock in tests
     */
    public NotificationsPanel(NotificationsSession session, Navigator navigator,
                              StackPane host, Node anchor, Clock clock) {
        this.session = Objects.requireNonNull(session, "session");
        this.model = session.model();
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.popover = new Popover(Objects.requireNonNull(host, "host"), this, anchor);
        this.clock = Objects.requireNonNull(clock, "clock");

        getStyleClass().add("hsts-notification-panel");
        setPrefWidth(WIDTH);
        setMaxSize(WIDTH, javafx.scene.layout.Region.USE_PREF_SIZE);

        getChildren().addAll(buildHeader(), buildList());
        model.onChange(this::render);
        render();
    }

    // ===================== Open / close ==================================

    /** Opens the panel if closed, closes it if open. Wired to the navbar bell. */
    public void toggle() {
        if (isOpen()) {
            close();
        } else {
            open();
        }
    }

    /**
     * Shows the panel and fetches the newest notifications.
     *
     * <p>The fetch happens on every open rather than once: the list is cheap, and
     * anything the client missed while disconnected is only ever recovered here.
     */
    public void open() {
        popover.open();
        session.refresh();
    }

    /** Hides the panel. */
    public void close() {
        popover.close();
    }

    /** @return {@code true} while the panel is on screen. */
    public boolean isOpen() {
        return popover.isOpen();
    }

    // ===================== Rendering =====================================

    private Node buildHeader() {
        Label title = new Label("Notifications");
        title.getStyleClass().add("panel-title");

        markAll.setOnAction(e -> session.markAllRead());

        HBox header = new HBox(8, title, Buttons.spacer(), markAll);
        header.getStyleClass().add("panel-header");
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private Node buildList() {
        list.getStyleClass().add("panel-list");
        ScrollPane scroller = new ScrollPane(list);
        scroller.setFitToWidth(true);
        scroller.setMaxHeight(MAX_LIST_HEIGHT);
        scroller.getStyleClass().add("panel-scroller");
        VBox.setVgrow(scroller, Priority.ALWAYS);
        return scroller;
    }

    /** Rebuilds the rows from the model. Called on every model change. */
    private void render() {
        markAll.setDisable(!model.hasUnread());
        List<NotificationDto> items = model.items();
        list.getChildren().clear();
        if (items.isEmpty()) {
            list.getChildren().add(new EmptyState(Icons.INBOX, "Nothing yet",
                    "Approvals, grades and exam updates will show up here."));
            return;
        }
        items.forEach(item -> list.getChildren().add(row(item)));
        // The rows stagger, not the panel: the panel's own entrance belongs to
        // Popover, and two entrances on one open read as a stutter.
        Animations.staggerRows(List.copyOf(list.getChildren()));
    }

    /**
     * One row (UI wave 2 visual pass).
     *
     * <p>Four parts, left to right: a rounded badge whose soft background says
     * what kind of news this is, the title and body, the relative time, and — on
     * an unread row — a small accent dot. The dot is a second signal beside the
     * tinted background rather than a replacement for it, because a tint alone
     * is a distinction some readers cannot make (PRD §4.1: state is never colour
     * only).
     *
     * <p>The behaviour is untouched from wave 1: the row still marks itself read
     * and deep-links, and the store behind it never knew this class changed.
     */
    private Node row(NotificationDto item) {
        Label title = new Label(item.title());
        title.getStyleClass().add("row-title");
        title.setWrapText(true);

        VBox text = new VBox(2, title);
        if (!item.body().isEmpty()) {
            Label body = new Label(item.body());
            body.getStyleClass().add("row-body");
            body.setWrapText(true);
            text.getChildren().add(body);
        }

        Label age = new Label(NotificationPresenter.ageOf(item, clock.instant()));
        age.getStyleClass().add("row-age");

        VBox trailing = new VBox(6, age);
        trailing.setAlignment(Pos.TOP_RIGHT);
        if (item.isUnread()) {
            Circle unread = new Circle(UNREAD_DOT / 2);
            unread.getStyleClass().add("row-unread-dot");
            VBox dotBox = new VBox(unread);
            dotBox.setAlignment(Pos.CENTER_RIGHT);
            trailing.getChildren().add(dotBox);
        }

        HBox row = new HBox(10, badge(item), text, trailing);
        row.getStyleClass().add("panel-row");
        if (item.isUnread()) {
            row.getStyleClass().add("unread");
        }
        row.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);
        row.setOnMouseClicked(e -> activate(item));
        row.setAccessibleText(NotificationPresenter.accessibleTextOf(item, clock.instant()));
        return row;
    }

    /** @return the rounded icon square, tinted by the notification's kind. */
    private static StackPane badge(NotificationDto item) {
        StackPane badge = new StackPane(
                Icons.of(NotificationPresenter.iconFor(item.type()), Icons.SIZE_DEFAULT,
                        "row-icon"));
        badge.getStyleClass().addAll("row-badge",
                NotificationPresenter.badgeToneFor(item.type()));
        badge.setMinSize(BADGE, BADGE);
        badge.setPrefSize(BADGE, BADGE);
        badge.setMaxSize(BADGE, BADGE);
        return badge;
    }

    /**
     * Marks a row read and follows its reference.
     *
     * <p>Marking and navigating are independent: a notification whose route this
     * build does not know still gets marked read and simply closes the panel.
     * Refusing to mark it would leave a badge the user cannot clear.
     */
    private void activate(NotificationDto item) {
        if (item.isUnread()) {
            session.markRead(item.id());
        }
        close();
        NavRef ref = item.ref();
        if (!ref.isNavigable()) {
            return;
        }
        if (!navigator.isRegistered(ref.route())) {
            // A notification pointing at a screen this build (or this role) does not
            // have. Normal during the epics; never an error the user should see.
            log.debug("Notification {} points at unknown route '{}'", item.id(), ref.route());
            return;
        }
        navigator.navigate(ref.route());
    }
}
