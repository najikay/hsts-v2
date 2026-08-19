package client.features.settings;

import client.core.NavParams;
import client.core.ScreenManager;
import client.ui.anim.Animations;
import client.ui.anim.Motion;
import client.ui.components.Buttons;
import client.ui.screen.AbstractScreen;
import client.ui.theme.ThemeControls;
import client.ui.theme.ThemeState;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * Theme settings (Presentation tier, E4.9, PRD §4.1).
 *
 * <p>Mode toggle plus accent swatches, both applying live and persisting
 * immediately — there is no Save button because there is nothing to save
 * atomically: each control is a preference that takes effect the moment it is
 * chosen, which is also the only honest way to preview a theme.
 *
 * <p>The controls themselves come from {@link ThemeControls} (shared with the
 * gallery), and every decision they drive lives in {@link ThemeState}. This
 * screen is layout.
 */
public final class SettingsView extends AbstractScreen {

    private final ThemeState theme;

    public SettingsView() {
        this(ScreenManager.getInstance().themeManager().state());
    }

    public SettingsView(ThemeState theme) {
        this.theme = Objects.requireNonNull(theme, "theme");
    }

    @Override
    protected Parent build() {
        Label title = new Label("Settings");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Appearance preferences are saved per user and apply instantly.");
        subtitle.getStyleClass().add("page-subtitle");
        VBox header = new VBox(4, title, subtitle);
        header.getStyleClass().add("hsts-page-header");

        VBox appearance = new VBox(20,
                ThemeControls.labelled("Theme",
                        "Follow your operating system, or pick one and stay there.",
                        ThemeControls.modeToggle(theme)),
                divider(),
                ThemeControls.labelled("Accent colour",
                        "Used for primary actions, active menu items and highlights.",
                        ThemeControls.paletteSwatches(theme)));
        appearance.getStyleClass().add("hsts-card");

        HBox actions = new HBox(8, Buttons.secondary("Reset to defaults"));
        ((javafx.scene.control.Button) actions.getChildren().get(0))
                .setOnAction(e -> theme.resetToDefaults());

        VBox body = new VBox(16, appearance, actions);
        body.getStyleClass().add("hsts-page-body");
        body.setMaxWidth(640);

        VBox page = new VBox(header, body);
        page.getStyleClass().add("hsts-page");
        page.setPadding(new Insets(0, 0, 24, 0));

        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("hsts-page");
        return scroll;
    }

    @Override
    public void onShow(NavParams params) {
        Animations.fadeIn(view(), Motion.BASE_MS);
    }

    private static Region divider() {
        Region line = new Region();
        line.getStyleClass().add("hsts-divider");
        return line;
    }
}
