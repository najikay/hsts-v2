package client.ui.components;

import client.ui.anim.Animations;
import client.ui.components.logic.ChipCatalog;
import client.ui.components.logic.ChipSpec;
import client.ui.components.logic.ChipTone;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;

import java.util.Locale;
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
 *
 * <h2>The compact form (2026-08-29, manual round 3, U-32)</h2>
 *
 * <p>{@link #examStatusIcon(String)} is the same chip wearing one glyph instead of its word,
 * for the places too narrow to hold "Pending approval" - the exam list's versions panel is
 * 320px wide and was ellipsising every chip on it to "PENDING...". The word is not dropped,
 * it moves: the tooltip and {@code accessibleText} both carry it in full, so a screen reader
 * and a hovering pointer read exactly what the wide chip says. <b>The colour does not change
 * with the form</b>, so an approved version is the same green whichever of the two is drawn,
 * and a table that has the room keeps the word.
 */
public final class StatusChip extends HBox {

    private static final double DOT_RADIUS = 4;

    private final Label label = new Label();
    private Circle dot;

    /** The single glyph of a compact chip, or {@code null} while the chip shows its word. */
    private Node glyph;

    /** The full word, installed on a compact chip so the pointer can still read it (U-32). */
    private Tooltip tip;

    /** Builds a chip from a decided spec. */
    public StatusChip(ChipSpec spec) {
        getStyleClass().add("hsts-chip");
        setAlignment(Pos.CENTER);
        setSpacing(6);
        label.getStyleClass().add("chip-label");
        getChildren().add(label);
        // 2026-08-31, U-58 (Naji, round 5): a chip inside a constrained table column was
        // ellipsised ("Pending appro…"). A chip is a word, not a paragraph: it keeps its
        // preferred width and the column makes room, the same pin Buttons.styled carries.
        label.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        set(spec);
    }

    /** @return a chip for an exam-version status (F3.6). */
    public static StatusChip examStatus(String status) {
        return new StatusChip(ChipCatalog.forExamStatus(status));
    }

    /**
     * The same exam-version chip as one glyph, for a column too narrow for the word (U-32).
     *
     * <p>A separate factory rather than a flag on {@link #examStatus(String)}, because the two
     * are not interchangeable: the TABLE has the room and keeps its words, and a caller who
     * cannot see which it is getting would pick the compact one by accident. The glyph mapping
     * is {@link #examGlyph(String)} and lives here rather than in {@code ChipCatalog}, which is
     * deliberately free of anything from the toolkit's side of the house.
     *
     * @param status the wire state, matched exactly as {@link ChipCatalog} matches it
     * @return a chip carrying one icon, its state's colour, and its word as tooltip and
     *         accessible text
     */
    public static StatusChip examStatusIcon(String status) {
        StatusChip chip = new StatusChip(ChipCatalog.forExamStatus(status));
        chip.compact(examGlyph(status));
        return chip;
    }

    /**
     * One glyph per exam-version state (U-32).
     *
     * <p>Four marks a teacher can tell apart at a glance and without colour, which is the point
     * of the exercise: a tick, a cross, a clock and a pencil differ in SHAPE, so the panel still
     * reads for someone who cannot tell the green chip from the red one. An unknown state gets
     * the neutral information mark, matching {@code ChipCatalog}'s neutral fallback for the
     * server that is one version ahead.
     */
    private static String examGlyph(String status) {
        return switch (status == null ? "" : status.trim().toUpperCase(Locale.ROOT)) {
            case "APPROVED" -> Icons.CHECK;
            case "REJECTED" -> Icons.CROSS;
            case "PENDING", "PENDING_APPROVAL" -> Icons.CLOCK;
            case "DRAFT" -> Icons.EDIT;
            default -> Icons.INFO;
        };
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
        // A compact chip re-rendered by a push must not grow its word back. Its label is not
        // in the chip any more, so the tooltip is the other place the new word has to reach;
        // setAccessibleText above is the first.
        if (tip != null) {
            tip.setText(spec.label());
        }
    }

    /**
     * Drops this chip down to one glyph, keeping its word where it can still be read (U-32).
     *
     * <p>The label is <b>removed</b> rather than hidden, because a zero-width child in an HBox
     * still takes the spacing between it and its neighbour, and a chip whose padding is
     * asymmetric by 6px reads as a rendering fault rather than as a design.
     *
     * @param iconLiteral an {@link Icons} constant; unknown literals draw a spacer, not a crash
     * @return this chip
     */
    public StatusChip compact(String iconLiteral) {
        getStyleClass().add("compact");
        getChildren().remove(label);
        if (glyph != null) {
            getChildren().remove(glyph);
        }
        glyph = Icons.of(iconLiteral, Icons.SIZE_DEFAULT, "chip-icon");
        getChildren().add(glyph);
        tip = new Tooltip(label.getText());
        Tooltip.install(this, tip);
        return this;
    }

    /** @return the word this chip stands for, which a compact chip shows only on hover. */
    public Tooltip tooltip() {
        return tip;
    }

    /** @return {@code true} when this chip is drawn as a single glyph (U-32). */
    public boolean isCompact() {
        return glyph != null;
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
