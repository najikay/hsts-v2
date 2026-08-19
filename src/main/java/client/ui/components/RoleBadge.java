package client.ui.components;

import common.dto.auth.Role;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import java.util.Locale;
import java.util.Objects;

/**
 * The small pill naming a user's role (Presentation tier, E4.15).
 *
 * <p>Appears in the navbar avatar chip, in user lists and beside authorship
 * lines. Each role owns a fixed tone so "Coordinator" is the same amber
 * everywhere it is shown — with four roles and screens that differ only by
 * permissions (F1.2), a consistent role colour is real navigational information,
 * not decoration.
 *
 * <p>Colours come from {@code .role-badge.<role>} in {@code hsts.css}; this class
 * only picks the class name.
 */
public final class RoleBadge extends HBox {

    private final Label label = new Label();

    public RoleBadge(Role role) {
        Objects.requireNonNull(role, "role");
        getStyleClass().addAll("role-badge", role.name().toLowerCase(Locale.ROOT));
        setAlignment(Pos.CENTER);
        label.setText(displayName(role));
        getChildren().add(label);
        setAccessibleText(displayName(role) + " role");
    }

    /**
     * @return the label shown to users. {@code COORDINATOR} becomes
     *         "Coordinator" rather than the DB's screaming-snake constant.
     */
    public static String displayName(Role role) {
        Objects.requireNonNull(role, "role");
        String lower = role.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
