package client.features.notify;

import client.core.Navigator;
import client.ui.anim.Animations;
import client.ui.anim.Motion;
import client.ui.components.Buttons;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
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
 */
public final class NotificationsPanel extends VBox {

    private static final Logger log = LoggerFactory.getLogger(NotificationsPanel.class);

    /** Wide enough for two lines of body text without becoming a second screen. */
    private static final double WIDTH = 380;

    /** Taller than this and the panel would cover the content it is annotating. */
    private static final double MAX_LIST_HEIGHT = 420;

    private final NotificationsModel model;
    private final NotificationsSession session;
    private final Navigator navigator;
    private final StackPane host;
    private final Clock clock;

    private final VBox list = new VBox();
    private final Button markAll = Buttons.styled("Mark all read", Buttons.LINK, Buttons.SMALL);

    /**
     * @param session   the conversation with the server; also the source of the model
     * @param navigator where click-through goes
     * @param host      the shell's popover layer ({@code AppShell.popovers()})
     */
    public NotificationsPanel(NotificationsSession session, Navigator navigator, StackPane host) {
        this(session, navigator, host, Clock.systemUTC());
    }

    /** @param clock time source for the relative times; a fixed clock in tests */
    public NotificationsPanel(NotificationsSession session, Navigator navigator,
                              StackPane host, Clock clock) {
        this.session = Objects.requireNonNull(session, "session");
        this.model = session.model();
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.host = Objects.requireNonNull(host, "host");
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
        if (!isOpen()) {
            host.getChildren().add(this);
        }
        Animations.slideInY(this, true, 8, Motion.BASE_MS);
        session.refresh();
    }

    /** Hides the panel. */
    public void close() {
        host.getChildren().remove(this);
    }

    /** @return {@code true} while the panel is on screen. */
    public boolean isOpen() {
        return host.getChildren().contains(this);
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
        Animations.staggerIn(List.copyOf(list.getChildren()));
    }

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
        text.getChildren().add(age);

        HBox row = new HBox(10,
                Icons.of(NotificationPresenter.iconFor(item.type()), Icons.SIZE_DEFAULT, "row-icon"),
                text);
        row.getStyleClass().add("panel-row");
        if (item.isUnread()) {
            row.getStyleClass().add("unread");
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        row.setOnMouseClicked(e -> activate(item));
        row.setAccessibleText(NotificationPresenter.accessibleTextOf(item, clock.instant()));
        return row;
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
