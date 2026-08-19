package client.ui.components.logic;

import java.util.Objects;

/**
 * The four states any data-backed view can be in (Presentation tier, E4.11 /
 * E4.16 / E4.17).
 *
 * <p>PRD §4.1's prime bar is "zero dead screens, zero mystery states". This tiny
 * type is how that is enforced structurally rather than by review: a
 * {@code DataTable} (and any screen using it) renders from an
 * {@code AsyncViewState}, and because the enum is exhaustive there is no way to
 * build one that forgets its loading skeleton or its empty state.
 */
public enum AsyncViewState {

    /** Nothing requested yet — render the skeleton, same as loading. */
    IDLE,

    /** Request in flight — skeleton rows / progress overlay. */
    LOADING,

    /** Data arrived and is non-empty — render the content. */
    READY,

    /** Data arrived and is empty — render the empty state, never a blank box. */
    EMPTY,

    /** The request failed — render the error state with a retry action. */
    ERROR;

    /**
     * Classifies a completed load.
     *
     * @return {@link #EMPTY} for a zero-length result, else {@link #READY}
     */
    public static AsyncViewState forResultSize(int size) {
        return size <= 0 ? EMPTY : READY;
    }

    /** @return the state for a completed load of the given collection. */
    public static AsyncViewState forResult(java.util.Collection<?> result) {
        Objects.requireNonNull(result, "result");
        return forResultSize(result.size());
    }

    /** @return {@code true} when skeleton placeholders should be on screen. */
    public boolean showsSkeleton() {
        return this == IDLE || this == LOADING;
    }

    /** @return {@code true} when the real content node should be on screen. */
    public boolean showsContent() {
        return this == READY;
    }

    /** @return {@code true} when the empty-state node should be on screen. */
    public boolean showsEmptyState() {
        return this == EMPTY;
    }

    /** @return {@code true} when the error node (with retry) should be on screen. */
    public boolean showsError() {
        return this == ERROR;
    }

    /** @return {@code true} when a request is currently in flight. */
    public boolean isBusy() {
        return this == LOADING;
    }
}
