package client.ui.theme;

import client.core.InMemoryPropertiesStore;
import client.core.PropertiesStore;
import client.events.ClientEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The theme's whole decision layer: what the user picked, what that resolves to,
 * how it persists and who gets told (Presentation tier, E4.7).
 *
 * <p>Deliberately contains <b>no JavaFX</b>. Everything about theming that can
 * be wrong — resolving {@link ThemeMode#SYSTEM} against the OS, not writing the
 * preferences file on a no-op change, not firing listeners when nothing actually
 * changed, surviving a corrupt preferences file — is decided here and unit
 * tested. {@link ThemeManager} is the FX shell that only knows how to push the
 * outcome into a {@code Scene}.
 *
 * <p>Two notification channels, on purpose:
 * <ul>
 *   <li><b>direct listeners</b> — the {@link ThemeManager} instances that must
 *       re-apply stylesheets synchronously, before anything paints;</li>
 *   <li><b>{@link ClientEventBus}</b> — everything else in the app
 *       ({@link ThemeChangedEvent}), delivered on the FX thread like every other
 *       client event, so custom-painted nodes repaint without knowing this class
 *       exists.</li>
 * </ul>
 */
public final class ThemeState {

    private static final Logger log = LoggerFactory.getLogger(ThemeState.class);

    /** Preferences key for the selected mode. */
    public static final String KEY_MODE = "ui.theme.mode";

    /** Preferences key for the selected accent palette. */
    public static final String KEY_PALETTE = "ui.theme.palette";

    private final PropertiesStore store;
    private final BooleanSupplier systemDark;
    private final ClientEventBus eventBus;
    private final List<Consumer<ThemeChangedEvent>> listeners = new ArrayList<>();

    private ThemeMode mode;
    private AccentPalette palette;
    private boolean systemIsDark;

    /**
     * @param store      where the choice persists ({@code ~/.hsts/ui.properties} in production)
     * @param systemDark probe for the OS appearance, consulted only in SYSTEM mode
     * @param eventBus   bus the {@link ThemeChangedEvent} is published on
     */
    public ThemeState(PropertiesStore store, BooleanSupplier systemDark, ClientEventBus eventBus) {
        this.store = Objects.requireNonNull(store, "store");
        this.systemDark = Objects.requireNonNull(systemDark, "systemDark");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.systemIsDark = systemDark.getAsBoolean();
        loadFromStore();
    }

    /** @return a state persisting to {@code ~/.hsts/ui.properties}, probing the real OS. */
    public static ThemeState userHome(ClientEventBus eventBus) {
        return new ThemeState(
                client.core.FilePropertiesStore.inUserHome(client.core.FilePropertiesStore.UI_FILE),
                SystemAppearance::isDark,
                eventBus);
    }

    /** @return a throwaway state that persists nothing — used by the {@code --gallery} screen. */
    public static ThemeState ephemeral(ClientEventBus eventBus) {
        return new ThemeState(new InMemoryPropertiesStore(), () -> false, eventBus);
    }

    // ------------------------------------------------------------------ state

    /** @return the mode the user selected (may be {@link ThemeMode#SYSTEM}). */
    public ThemeMode mode() {
        return mode;
    }

    /** @return the selected accent palette. */
    public AccentPalette palette() {
        return palette;
    }

    /**
     * Resolves {@link ThemeMode#SYSTEM} against the last OS probe.
     *
     * @return {@link ThemeMode#LIGHT} or {@link ThemeMode#DARK} — never {@code SYSTEM}
     */
    public ThemeMode effectiveMode() {
        if (mode == ThemeMode.SYSTEM) {
            return systemIsDark ? ThemeMode.DARK : ThemeMode.LIGHT;
        }
        return mode;
    }

    /** @return {@code true} when the dark palette is what should be on screen. */
    public boolean isDark() {
        return effectiveMode() == ThemeMode.DARK;
    }

    /** @return the accent emphasis colour currently in force. */
    public String accentColor() {
        return palette.accent(isDark());
    }

    /** @return the accent soft tint currently in force. */
    public String accentSoftColor() {
        return palette.accentSoft(isDark());
    }

    /** @return the current state as the event that describes it. */
    public ThemeChangedEvent snapshot() {
        return new ThemeChangedEvent(mode, effectiveMode(), palette);
    }

    // --------------------------------------------------------------- mutation

    /** Selects a mode; persists and notifies only when the appearance actually changes. */
    public void setMode(ThemeMode newMode) {
        apply(Objects.requireNonNull(newMode, "mode"), palette);
    }

    /** Selects an accent palette; persists and notifies only on a real change. */
    public void setPalette(AccentPalette newPalette) {
        apply(mode, Objects.requireNonNull(newPalette, "palette"));
    }

    /** Selects both at once — one persist, one notification. */
    public void set(ThemeMode newMode, AccentPalette newPalette) {
        apply(Objects.requireNonNull(newMode, "mode"), Objects.requireNonNull(newPalette, "palette"));
    }

    /**
     * Cycles LIGHT → DARK → SYSTEM → LIGHT. Bound to the navbar's one-click theme
     * button; the settings screen offers the three explicitly.
     */
    public void cycleMode() {
        ThemeMode[] all = ThemeMode.values();
        setMode(all[(mode.ordinal() + 1) % all.length]);
    }

    /**
     * Re-probes the OS appearance (call on window focus).
     *
     * <p>Only matters in {@link ThemeMode#SYSTEM}: in an explicit mode the probe
     * result is recorded but changes nothing on screen, so no event is fired.
     *
     * @return {@code true} when the effective appearance changed as a result
     */
    public boolean refreshSystem() {
        boolean probed = systemDark.getAsBoolean();
        if (probed == systemIsDark) {
            return false;
        }
        ThemeMode before = effectiveMode();
        systemIsDark = probed;
        ThemeMode after = effectiveMode();
        if (before == after) {
            return false;
        }
        log.debug("OS appearance changed → {}", after);
        fire();
        return true;
    }

    /** Restores the shipped defaults (Indigo, follow the OS) and persists them. */
    public void resetToDefaults() {
        apply(ThemeMode.SYSTEM, AccentPalette.DEFAULT);
    }

    // -------------------------------------------------------------- listeners

    /**
     * Subscribes to theme changes and immediately delivers the current state, so
     * a listener attaching after startup does not have to also read the state to
     * paint itself correctly.
     */
    public void addListener(Consumer<ThemeChangedEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        listener.accept(snapshot());
    }

    /** Unsubscribes; safe when never subscribed. */
    public void removeListener(Consumer<ThemeChangedEvent> listener) {
        listeners.remove(listener);
    }

    /** @return the number of direct listeners (diagnostics / leak checks in tests). */
    public int listenerCount() {
        return listeners.size();
    }

    // ---------------------------------------------------------------- helpers

    private void apply(ThemeMode newMode, AccentPalette newPalette) {
        if (newMode == mode && newPalette == palette) {
            return;
        }
        mode = newMode;
        palette = newPalette;
        persist();
        fire();
    }

    private void loadFromStore() {
        Properties props = store.load();
        mode = ThemeMode.parse(props.getProperty(KEY_MODE)).orElse(ThemeMode.SYSTEM);
        palette = AccentPalette.parse(props.getProperty(KEY_PALETTE)).orElse(AccentPalette.DEFAULT);
        log.debug("theme loaded: mode={} palette={}", mode, palette);
    }

    private void persist() {
        Properties props = store.load();
        props.setProperty(KEY_MODE, mode.name());
        props.setProperty(KEY_PALETTE, palette.name());
        store.save(props);
    }

    private void fire() {
        ThemeChangedEvent event = snapshot();
        for (Consumer<ThemeChangedEvent> listener : List.copyOf(listeners)) {
            listener.accept(event);
        }
        eventBus.post(event);
    }
}
