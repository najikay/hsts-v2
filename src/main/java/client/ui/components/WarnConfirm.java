package client.ui.components;

import client.ui.anim.Animations;
import client.ui.anim.Motion;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.Objects;
import java.util.Optional;

/**
 * The confirmation dialog for legal-but-unusual actions (Presentation tier,
 * E4.13, PRD §4.1 ⚑).
 *
 * <p>One dialog, reused for every "are you sure?" in the app: submitting an exam
 * with unanswered questions (F6.9), closing a live execution early (F5.5),
 * deleting a question (F2.5), overriding a grade (F8.3). It always carries an
 * icon, a title, an <b>explanation of the consequence</b> and an explicit confirm
 * — the explanation is the point: PRD §4.1 asks for informed confirmation, not a
 * reflexive "OK".
 *
 * <p>Usage is deliberately blocking and boolean, because every call site is
 * "ask, then act":
 * <pre>{@code
 * if (WarnConfirm.show(owner, WarnConfirm.spec("Close this execution early?")
 *         .explanation("Students still working will be submitted as they are.")
 *         .confirmText("Close now")
 *         .warn())) {
 *     service.closeEarly(executionId);
 * }
 * }</pre>
 */
public final class WarnConfirm {

    /** Severity, driving the icon disc tint and the confirm button variant. */
    public enum Severity {
        /** Cautionary: reversible or expected-but-unusual. Amber. */
        WARN("warn", Icons.WARNING, Buttons.WARN),
        /** Destructive: data or state is lost. Red. */
        DANGER("danger", Icons.ERROR, Buttons.DANGER),
        /** Purely informational confirmation. Accent. */
        INFO("info", Icons.INFO, Buttons.PRIMARY);

        private final String styleClass;
        private final String icon;
        private final String confirmVariant;

        Severity(String styleClass, String icon, String confirmVariant) {
            this.styleClass = styleClass;
            this.icon = icon;
            this.confirmVariant = confirmVariant;
        }
    }

    /**
     * What to ask. A builder rather than a six-argument call, because most call
     * sites override only the title and the confirm label.
     */
    public static final class Spec {
        private final String title;
        private String explanation = "";
        private String confirmText = "Confirm";
        private String cancelText = "Cancel";
        private Severity severity = Severity.WARN;
        private Node detail;

        private Spec(String title) {
            this.title = Objects.requireNonNull(title, "title");
        }

        /** The consequence, in plain language. Shown under the title. */
        public Spec explanation(String text) {
            this.explanation = text == null ? "" : text;
            return this;
        }

        /** Label of the confirming button — a verb, never "OK" ("Submit exam"). */
        public Spec confirmText(String text) {
            this.confirmText = Objects.requireNonNull(text, "confirmText");
            return this;
        }

        /** Label of the dismissing button ("Keep working"). */
        public Spec cancelText(String text) {
            this.cancelText = Objects.requireNonNull(text, "cancelText");
            return this;
        }

        /** Amber treatment (the default). */
        public Spec warn() {
            this.severity = Severity.WARN;
            return this;
        }

        /** Red treatment, for destructive actions. */
        public Spec danger() {
            this.severity = Severity.DANGER;
            return this;
        }

        /** Accent treatment, for informational confirmations. */
        public Spec info() {
            this.severity = Severity.INFO;
            return this;
        }

        /**
         * An extra node between explanation and buttons — F6.9's answered/unanswered
         * summary grid, or F2.5's list of exams blocking a delete.
         */
        public Spec detail(Node node) {
            this.detail = node;
            return this;
        }
    }

    private WarnConfirm() {
    }

    /** @return a spec builder for the given title (phrase it as a question). */
    public static Spec spec(String title) {
        return new Spec(title);
    }

    /**
     * Shows the dialog modally and blocks until the user answers.
     *
     * @param owner the window to dim and block; may be {@code null}
     * @return {@code true} when the user confirmed
     */
    public static boolean show(Window owner, Spec spec) {
        return showAndWait(owner, spec).orElse(false);
    }

    /**
     * <p>2026-08-28, manual round 1: the dialog is mounted through
     * {@link ModalHost}, so the scrim dims the whole owner window instead of
     * painting a dark rectangle around the dialog. The stage is still
     * transparent and the dialog still carries its own soft
     * {@code .hsts-dialog} shadow; what changed is how big the stage is.
     *
     * @return {@code Optional.of(true)} on confirm, {@code Optional.of(false)} on
     *         cancel; the {@code Optional} shape mirrors JavaFX's own dialogs for
     *         call sites that want to distinguish "dismissed" handling later
     */
    public static Optional<Boolean> showAndWait(Window owner, Spec spec) {
        Objects.requireNonNull(spec, "spec");
        boolean[] confirmed = {false};

        Stage stage = new Stage(StageStyle.TRANSPARENT);
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }

        Button confirm = Buttons.styled(spec.confirmText, spec.severity.confirmVariant);
        confirm.setDefaultButton(true);
        confirm.setOnAction(e -> {
            confirmed[0] = true;
            stage.close();
        });

        Button cancel = Buttons.secondary(spec.cancelText);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());

        VBox dialog = buildDialog(spec, confirm, cancel);
        StackPane scrim = ModalHost.mount(stage, owner, dialog);

        // UI wave 2: 160ms, scale 0.98 to 1, with the scrim fading in parallel.
        // A dialog is already the thing the user asked for, so it settles into
        // place rather than arriving from somewhere.
        Animations.fadeIn(scrim, Motion.DIALOG_MS);
        Animations.scaleIn(dialog, Motion.DIALOG_FROM_SCALE, Motion.DIALOG_MS);
        stage.showAndWait();
        return Optional.of(confirmed[0]);
    }

    private static VBox buildDialog(Spec spec, Button confirm, Button cancel) {
        StackPane iconDisc = new StackPane(Icons.of(spec.severity.icon, Icons.SIZE_LARGE, "dialog-icon"));
        iconDisc.getStyleClass().add("dialog-icon-disc");

        Label title = new Label(spec.title);
        title.getStyleClass().add("dialog-title");
        title.setWrapText(true);

        VBox headingText = new VBox(6, title);
        if (!spec.explanation.isBlank()) {
            Label explanation = new Label(spec.explanation);
            explanation.getStyleClass().add("dialog-explanation");
            explanation.setWrapText(true);
            headingText.getChildren().add(explanation);
        }
        HBox heading = new HBox(14, iconDisc, headingText);
        heading.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(headingText, javafx.scene.layout.Priority.ALWAYS);

        VBox dialog = new VBox(heading);
        dialog.getStyleClass().addAll("hsts-dialog", spec.severity.styleClass);
        if (spec.detail != null) {
            dialog.getChildren().add(spec.detail);
        }
        dialog.getChildren().add(Buttons.row(cancel, confirm));
        return dialog;
    }
}
