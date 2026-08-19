package client.ui.components;

import client.ui.anim.Animations;
import client.ui.components.logic.ChipCatalog;
import client.ui.components.logic.ChipSpec;
import client.ui.components.logic.ChipTone;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;

import java.util.Objects;

/**
 * The status pill used everywhere a state is displayed (Presentation tier, E4.15).
 *
 * <p>Thin by construction: it renders a {@link ChipSpec} and owns no rules about
 * what any state means — {@link ChipCatalog} decides that, and is unit-tested.
 * All this class does is build {@code [dot] label} and put the tone's style
 * class on the box.
 *
 * <p>The one piece of behaviour here is the LIVE treatment: a chip whose tone is
 * {@link ChipTone#LIVE} gets a slowly pulsing dot, so a teacher scanning a
 * release list sees which execution is running right now without reading.
 */
public final class StatusChip extends HBox {

    private static final double DOT_RADIUS = 4;

    private final Label label = new Label();
    private Circle dot;

    /** Builds a chip from a decided spec. */
    public StatusChip(ChipSpec spec) {
        getStyleClass().add("hsts-chip");
        setAlignment(Pos.CENTER);
        setSpacing(6);
        label.getStyleClass().add("chip-label");
        getChildren().add(label);
        set(spec);
    }

    /** @return a chip for an exam-version status (F3.6). */
    public static StatusChip examStatus(String status) {
        return new StatusChip(ChipCatalog.forExamStatus(status));
    }

    /** @return a chip for an execution status (F5.4) — {@code LIVE} pulses. */
    public static StatusChip executionStatus(String status) {
        return new StatusChip(ChipCatalog.forExecutionStatus(status));
    }

    /** @return a chip for an attempt status (F6). */
    public static StatusChip attemptStatus(String status) {
        return new StatusChip(ChipCatalog.forAttemptStatus(status));
    }

    /** @return a chip for a grade status (F8/C-3). */
    public static StatusChip gradeStatus(String status) {
        return new StatusChip(ChipCatalog.forGradeStatus(status));
    }

    /** @return a chip for a question difficulty (C-7). */
    public static StatusChip difficulty(String difficulty) {
        return new StatusChip(ChipCatalog.forDifficulty(difficulty));
    }

    /**
     * Re-renders the chip for a new state — used when a push changes a row in
     * place rather than rebuilding the list (NFR-18: no user-initiated refresh).
     */
    public void set(ChipSpec spec) {
        Objects.requireNonNull(spec, "spec");
        label.setText(spec.label());
        applyTone(spec.tone());
        applyDot(spec.dot(), spec.tone());
        setAccessibleText(spec.label());
    }

    /** @return this chip with the larger padding used in page headers. */
    public StatusChip large() {
        getStyleClass().add("large");
        return this;
    }

    private void applyTone(ChipTone tone) {
        for (ChipTone candidate : ChipTone.values()) {
            getStyleClass().remove(candidate.styleClass());
        }
        getStyleClass().add(tone.styleClass());
    }

    private void applyDot(boolean wanted, ChipTone tone) {
        if (!wanted) {
            removeDot();
            return;
        }
        if (dot == null) {
            dot = new Circle(DOT_RADIUS);
            dot.getStyleClass().add("chip-dot");
            getChildren().add(0, dot);
        }
        if (tone == ChipTone.LIVE) {
            Animations.pulse(dot, 0);
        } else {
            Animations.reset(dot);
        }
    }

    private void removeDot() {
        if (dot != null) {
            Animations.stop(dot);
            getChildren().remove(dot);
            dot = null;
        }
    }
}
