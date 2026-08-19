package client.ui.theme;

import client.core.InMemoryPropertiesStore;
import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import org.greenrobot.eventbus.Subscribe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ThemeState} — mode/palette resolution, persistence and notification
 * (E4.7). No JavaFX toolkit is involved: that is the point of the split.
 */
class ThemeStateTest {

    /**
     * Collects {@link ThemeChangedEvent}s posted on the client event bus.
     *
     * <p>Public because greenrobot invokes {@code @Subscribe} methods
     * reflectively and cannot reach a member of a private class.
     */
    public static final class BusSpy {
        final List<ThemeChangedEvent> events = new ArrayList<>();

        @Subscribe
        public void onThemeChanged(ThemeChangedEvent event) {
            events.add(event);
        }
    }

    private InMemoryPropertiesStore store;
    private ClientEventBus bus;
    private BusSpy busSpy;
    private AtomicBoolean systemDark;
    private List<ThemeChangedEvent> listenerEvents;

    @BeforeEach
    void setUp() {
        store = new InMemoryPropertiesStore();
        bus = new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster());
        busSpy = new BusSpy();
        bus.register(busSpy);
        systemDark = new AtomicBoolean(false);
        listenerEvents = new ArrayList<>();
    }

    private ThemeState newState() {
        return new ThemeState(store, systemDark::get, bus);
    }

    private ThemeState newListeningState() {
        ThemeState state = newState();
        state.addListener(listenerEvents::add);
        listenerEvents.clear();   // drop the immediate current-state delivery
        return state;
    }

    @Nested
    @DisplayName("defaults and loading")
    class Loading {

        @Test
        void defaultsToSystemModeAndIndigo() {
            ThemeState state = newState();

            assertThat(state.mode()).isEqualTo(ThemeMode.SYSTEM);
            assertThat(state.palette()).isEqualTo(AccentPalette.INDIGO);
            assertThat(state.palette()).isEqualTo(AccentPalette.DEFAULT);
        }

        @Test
        void restoresAPersistedChoice() {
            Properties saved = new Properties();
            saved.setProperty(ThemeState.KEY_MODE, "DARK");
            saved.setProperty(ThemeState.KEY_PALETTE, "EMERALD");
            store.save(saved);

            ThemeState state = newState();

            assertThat(state.mode()).isEqualTo(ThemeMode.DARK);
            assertThat(state.palette()).isEqualTo(AccentPalette.EMERALD);
        }

        @Test
        void aCorruptPreferencesFileFallsBackToDefaults() {
            Properties junk = new Properties();
            junk.setProperty(ThemeState.KEY_MODE, "MIDNIGHT");
            junk.setProperty(ThemeState.KEY_PALETTE, "TEAL");
            store.save(junk);

            ThemeState state = newState();

            assertThat(state.mode()).isEqualTo(ThemeMode.SYSTEM);
            assertThat(state.palette()).isEqualTo(AccentPalette.DEFAULT);
        }

        @Test
        void loadingDoesNotWriteBackOrNotify() {
            newState();

            assertThat(store.isEmpty()).isTrue();
            assertThat(busSpy.events).isEmpty();
        }

        @Test
        void anEphemeralStatePersistsNothing() {
            ThemeState state = ThemeState.ephemeral(bus);
            state.setMode(ThemeMode.DARK);

            assertThat(state.mode()).isEqualTo(ThemeMode.DARK);
            assertThat(store.isEmpty()).isTrue();   // the shared store was never touched
        }

        @Test
        void rejectsNullCollaborators() {
            assertThatThrownBy(() -> new ThemeState(null, () -> false, bus))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ThemeState(store, null, bus))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ThemeState(store, () -> false, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("effective mode resolution")
    class Resolution {

        @Test
        void explicitModesResolveToThemselves() {
            ThemeState state = newState();
            systemDark.set(true);

            state.setMode(ThemeMode.LIGHT);
            assertThat(state.effectiveMode()).isEqualTo(ThemeMode.LIGHT);
            assertThat(state.isDark()).isFalse();

            state.setMode(ThemeMode.DARK);
            assertThat(state.effectiveMode()).isEqualTo(ThemeMode.DARK);
            assertThat(state.isDark()).isTrue();
        }

        @Test
        void systemModeFollowsTheOsProbeTakenAtConstruction() {
            systemDark.set(true);
            ThemeState state = newState();

            assertThat(state.mode()).isEqualTo(ThemeMode.SYSTEM);
            assertThat(state.effectiveMode()).isEqualTo(ThemeMode.DARK);
            assertThat(state.isDark()).isTrue();
        }

        @Test
        void systemModeResolvesToLightWhenTheOsIsLight() {
            assertThat(newState().effectiveMode()).isEqualTo(ThemeMode.LIGHT);
        }

        @Test
        void accentColoursFollowTheEffectiveMode() {
            ThemeState state = newState();
            state.set(ThemeMode.LIGHT, AccentPalette.ROSE);

            assertThat(state.accentColor()).isEqualTo("#be123c");
            assertThat(state.accentSoftColor()).isEqualTo("#fff1f2");

            state.setMode(ThemeMode.DARK);
            assertThat(state.accentColor()).isEqualTo("#fb7185");
            assertThat(state.accentSoftColor()).isEqualTo("#4c1626");
        }

        @Test
        void snapshotDescribesBothTheChoiceAndTheOutcome() {
            systemDark.set(true);
            ThemeState state = newState();

            ThemeChangedEvent snapshot = state.snapshot();

            assertThat(snapshot.mode()).isEqualTo(ThemeMode.SYSTEM);
            assertThat(snapshot.effectiveMode()).isEqualTo(ThemeMode.DARK);
            assertThat(snapshot.dark()).isTrue();
            assertThat(snapshot.accent()).isEqualTo(AccentPalette.INDIGO.dark());
            assertThat(snapshot.accentSoft()).isEqualTo(AccentPalette.INDIGO.darkSoft());
        }
    }

    @Nested
    @DisplayName("mutation, persistence and notification")
    class Mutation {

        @Test
        void settingAModePersistsBothKeys() {
            newState().setMode(ThemeMode.DARK);

            Properties saved = store.load();
            assertThat(saved.getProperty(ThemeState.KEY_MODE)).isEqualTo("DARK");
            assertThat(saved.getProperty(ThemeState.KEY_PALETTE)).isEqualTo("INDIGO");
        }

        @Test
        void aChoiceSurvivesARestart() {
            newState().set(ThemeMode.DARK, AccentPalette.AMBER);

            ThemeState restarted = newState();

            assertThat(restarted.mode()).isEqualTo(ThemeMode.DARK);
            assertThat(restarted.palette()).isEqualTo(AccentPalette.AMBER);
        }

        @Test
        void notifiesListenersAndTheBus() {
            ThemeState state = newListeningState();

            state.setPalette(AccentPalette.SLATE);

            assertThat(listenerEvents).hasSize(1);
            assertThat(listenerEvents.get(0).palette()).isEqualTo(AccentPalette.SLATE);
            assertThat(busSpy.events).hasSize(1);
            assertThat(busSpy.events.get(0).palette()).isEqualTo(AccentPalette.SLATE);
        }

        @Test
        void aNoOpChangeNeitherPersistsNorNotifies() {
            ThemeState state = newListeningState();
            state.setMode(ThemeMode.DARK);
            store.save(new Properties());     // wipe, so a re-persist would show
            listenerEvents.clear();
            busSpy.events.clear();

            state.setMode(ThemeMode.DARK);

            assertThat(listenerEvents).isEmpty();
            assertThat(busSpy.events).isEmpty();
            assertThat(store.isEmpty()).isTrue();
        }

        @Test
        void settingBothAtOnceFiresExactlyOneEvent() {
            ThemeState state = newListeningState();

            state.set(ThemeMode.DARK, AccentPalette.ROSE);

            assertThat(listenerEvents).hasSize(1);
            assertThat(listenerEvents.get(0).effectiveMode()).isEqualTo(ThemeMode.DARK);
            assertThat(listenerEvents.get(0).palette()).isEqualTo(AccentPalette.ROSE);
        }

        @Test
        void cycleModeWalksLightDarkSystem() {
            ThemeState state = newState();
            state.setMode(ThemeMode.LIGHT);

            state.cycleMode();
            assertThat(state.mode()).isEqualTo(ThemeMode.DARK);
            state.cycleMode();
            assertThat(state.mode()).isEqualTo(ThemeMode.SYSTEM);
            state.cycleMode();
            assertThat(state.mode()).isEqualTo(ThemeMode.LIGHT);
        }

        @Test
        void resetToDefaultsRestoresSystemAndIndigo() {
            ThemeState state = newState();
            state.set(ThemeMode.DARK, AccentPalette.AMBER);

            state.resetToDefaults();

            assertThat(state.mode()).isEqualTo(ThemeMode.SYSTEM);
            assertThat(state.palette()).isEqualTo(AccentPalette.DEFAULT);
            assertThat(store.load().getProperty(ThemeState.KEY_PALETTE)).isEqualTo("INDIGO");
        }

        @Test
        void rejectsNullChoices() {
            ThemeState state = newState();

            assertThatThrownBy(() -> state.setMode(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.setPalette(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.set(ThemeMode.DARK, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("OS appearance refresh")
    class SystemRefresh {

        @Test
        void picksUpAnOsChangeWhileInSystemMode() {
            ThemeState state = newListeningState();
            assertThat(state.isDark()).isFalse();

            systemDark.set(true);

            assertThat(state.refreshSystem()).isTrue();
            assertThat(state.isDark()).isTrue();
            assertThat(listenerEvents).hasSize(1);
        }

        @Test
        void anUnchangedProbeChangesNothing() {
            ThemeState state = newListeningState();

            assertThat(state.refreshSystem()).isFalse();
            assertThat(listenerEvents).isEmpty();
        }

        @Test
        void anOsChangeIsInvisibleWhileAnExplicitModeIsSelected() {
            ThemeState state = newState();
            state.setMode(ThemeMode.LIGHT);
            state.addListener(listenerEvents::add);
            listenerEvents.clear();

            systemDark.set(true);

            assertThat(state.refreshSystem()).isFalse();
            assertThat(state.isDark()).isFalse();
            assertThat(listenerEvents).isEmpty();
        }

        @Test
        void anOsChangeRecordedWhileExplicitAppliesOnceSystemIsReselected() {
            ThemeState state = newState();
            state.setMode(ThemeMode.LIGHT);
            systemDark.set(true);
            state.refreshSystem();

            state.setMode(ThemeMode.SYSTEM);

            assertThat(state.isDark()).isTrue();
        }

        @Test
        void aChoiceDoesNotPersistTheProbeResult() {
            systemDark.set(true);
            ThemeState state = newState();
            state.setPalette(AccentPalette.ROSE);

            assertThat(store.load().getProperty(ThemeState.KEY_MODE)).isEqualTo("SYSTEM");
        }
    }

    @Nested
    @DisplayName("listeners")
    class Listeners {

        @Test
        void aNewListenerImmediatelyReceivesTheCurrentState() {
            ThemeState state = newState();
            state.set(ThemeMode.DARK, AccentPalette.SLATE);

            List<ThemeChangedEvent> received = new ArrayList<>();
            state.addListener(received::add);

            assertThat(received).hasSize(1);
            assertThat(received.get(0).palette()).isEqualTo(AccentPalette.SLATE);
            assertThat(received.get(0).dark()).isTrue();
        }

        @Test
        void listenersCanBeRemoved() {
            ThemeState state = newState();
            java.util.function.Consumer<ThemeChangedEvent> listener = listenerEvents::add;
            state.addListener(listener);
            listenerEvents.clear();
            assertThat(state.listenerCount()).isEqualTo(1);

            state.removeListener(listener);
            state.setMode(ThemeMode.DARK);

            assertThat(state.listenerCount()).isZero();
            assertThat(listenerEvents).isEmpty();
        }

        @Test
        void aListenerAddingAnotherListenerDoesNotBreakTheIteration() {
            ThemeState state = newState();
            state.addListener(event -> {
                if (state.listenerCount() < 3) {
                    state.addListener(listenerEvents::add);
                }
            });

            state.setMode(ThemeMode.DARK);

            assertThat(state.listenerCount()).isGreaterThan(1);
        }

        @Test
        void rejectsANullListener() {
            assertThatThrownBy(() -> newState().addListener(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
