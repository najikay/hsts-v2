package client.ui.components.logic;

import java.util.Locale;

/**
 * The single mapping from a domain state to its chip appearance
 * (Presentation tier, E4.15).
 *
 * <p>One place decides that {@code REJECTED} is danger and {@code LIVE} is a
 * pulsing green — so the approval queue, the release list, the execution monitor
 * and the student's grade list cannot disagree with each other, and a new state
 * is one line here instead of a hunt through screens.
 *
 * <p>Lookups take {@code String} rather than the domain enums on purpose. The
 * exam/execution/attempt/grade enums arrive with E5–E9; more importantly, taking
 * strings means a server that is one version ahead — sending a state this client
 * has never compiled against — still renders a readable neutral chip instead of
 * throwing (ARCHITECTURE §3, forward compatibility).
 */
public final class ChipCatalog {

    private ChipCatalog() {
    }

    /**
     * Exam-version workflow states (F3.6): {@code DRAFT}, {@code PENDING},
     * {@code PENDING_APPROVAL}, {@code APPROVED}, {@code REJECTED}.
     */
    public static ChipSpec forExamStatus(String status) {
        return switch (normalize(status)) {
            case "DRAFT" -> ChipSpec.of("Draft", ChipTone.NEUTRAL);
            case "PENDING", "PENDING_APPROVAL" -> ChipSpec.of("Pending approval", ChipTone.WARN);
            case "APPROVED" -> ChipSpec.of("Approved", ChipTone.OK);
            case "REJECTED" -> ChipSpec.of("Rejected", ChipTone.DANGER);
            default -> unknown(status);
        };
    }

    /**
     * Execution states (F5.4): {@code SCHEDULED}, {@code LIVE}, {@code CLOSED},
     * {@code CANCELLED}. {@code LIVE} is the only chip in the app that carries a
     * dot — it is the one state a teacher scans a list for.
     */
    public static ChipSpec forExecutionStatus(String status) {
        return switch (normalize(status)) {
            case "SCHEDULED" -> ChipSpec.of("Scheduled", ChipTone.INFO);
            case "LIVE" -> ChipSpec.of("Live", ChipTone.LIVE).withDot();
            case "CLOSED" -> ChipSpec.of("Closed", ChipTone.NEUTRAL);
            case "CANCELLED", "CANCELED" -> ChipSpec.of("Cancelled", ChipTone.NEUTRAL);
            default -> unknown(status);
        };
    }

    /**
     * Attempt states (F6): {@code IN_PROGRESS}, {@code SUBMITTED},
     * {@code TIMED_OUT}, plus the UI-only {@code NOT_STARTED} used by the
     * execution monitor for enrolled students who have not entered the code.
     */
    public static ChipSpec forAttemptStatus(String status) {
        return switch (normalize(status)) {
            case "NOT_STARTED" -> ChipSpec.of("Not started", ChipTone.NEUTRAL);
            case "IN_PROGRESS" -> ChipSpec.of("In progress", ChipTone.INFO).withDot();
            case "SUBMITTED" -> ChipSpec.of("Submitted", ChipTone.OK);
            case "TIMED_OUT" -> ChipSpec.of("Timed out", ChipTone.DANGER);
            default -> unknown(status);
        };
    }

    /**
     * Grade states (F8): {@code AUTO} (checked, not yet released) and
     * {@code APPROVED} (visible to the student, C-3).
     */
    public static ChipSpec forGradeStatus(String status) {
        return switch (normalize(status)) {
            case "AUTO" -> ChipSpec.of("Awaiting approval", ChipTone.WARN);
            case "APPROVED" -> ChipSpec.of("Published", ChipTone.OK);
            case "OVERRIDDEN" -> ChipSpec.of("Manually adjusted", ChipTone.INFO);
            default -> unknown(status);
        };
    }

    /** Question difficulty (C-7): {@code EASY}, {@code MEDIUM}, {@code HARD}. */
    public static ChipSpec forDifficulty(String difficulty) {
        return switch (normalize(difficulty)) {
            case "EASY" -> ChipSpec.of("Easy", ChipTone.OK);
            case "MEDIUM" -> ChipSpec.of("Medium", ChipTone.WARN);
            case "HARD" -> ChipSpec.of("Hard", ChipTone.DANGER);
            default -> unknown(difficulty);
        };
    }

    /** Connection state for the reconnect banner (E4.6). */
    public static ChipSpec forConnection(String state) {
        return switch (normalize(state)) {
            case "CONNECTED" -> ChipSpec.of("Connected", ChipTone.OK).withDot();
            case "CONNECTING", "RECONNECTING" -> ChipSpec.of("Reconnecting", ChipTone.WARN).withDot();
            case "DISCONNECTED", "LOST" -> ChipSpec.of("Disconnected", ChipTone.DANGER).withDot();
            default -> unknown(state);
        };
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    /** A state this client build does not know: readable, obviously not a real state. */
    private static ChipSpec unknown(String raw) {
        return ChipSpec.of(ChipSpec.humanize(raw), ChipTone.NEUTRAL);
    }
}
