package client.core;

import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import org.greenrobot.eventbus.Subscribe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * {@link ScreenLifecycle} and {@link ScreenCache} — the FX-free half of the
 * screen framework (E4.2, E4.3).
 */
class ScreenFrameworkTest {

    /** A screen that records its lifecycle calls. */
    public static class RecordingScreen implements Screen {
        final List<String> calls = new ArrayList<>();
        NavParams lastParams;

        @Override
        public void onShow(NavParams params) {
            calls.add("show");
            lastParams = params;
        }

        @Override
        public void onHide() {
            calls.add("hide");
        }
    }

    /**
     * A screen that opts in to the bus and declares a subscriber method.
     *
     * <p>Public because greenrobot invokes {@code @Subscribe} methods reflectively
     * and cannot reach a member of a private class.
     */
    public static final class SubscribingScreen extends RecordingScreen {
        final List<Object> received = new ArrayList<>();

        @Override
        public boolean listensToEvents() {
            return true;
        }

        @Subscribe
        public void onEvent(String event) {
            received.add(event);
        }
    }

    @Nested
    @DisplayName("ScreenLifecycle")
    class Lifecycle {

        private ClientEventBus bus;
        private ScreenLifecycle lifecycle;

        @BeforeEach
        void setUp() {
            bus = new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster());
            lifecycle = new ScreenLifecycle(bus);
        }

        @Test
        void showsAScreenWithItsParams() {
            RecordingScreen screen = new RecordingScreen();
            lifecycle.show(screen, NavParams.of("id", 3));

            assertThat(screen.calls).containsExactly("show");
            assertThat(screen.lastParams.getInt("id", -1)).isEqualTo(3);
            assertThat(lifecycle.current()).isSameAs(screen);
            assertThat(lifecycle.isShowing(screen)).isTrue();
        }

        @Test
        void hidesThePreviousScreenBeforeShowingTheNext() {
            RecordingScreen first = new RecordingScreen();
            RecordingScreen second = new RecordingScreen();

            lifecycle.show(first, NavParams.empty());
            lifecycle.show(second, NavParams.empty());

            assertThat(first.calls).containsExactly("show", "hide");
            assertThat(second.calls).containsExactly("show");
            assertThat(lifecycle.isShowing(first)).isFalse();
        }

        @Test
        void reShowingTheCurrentScreenRefreshesItWithoutHiding() {
            RecordingScreen screen = new RecordingScreen();
            lifecycle.show(screen, NavParams.of("id", 1));
            lifecycle.show(screen, NavParams.of("id", 2));

            assertThat(screen.calls).containsExactly("show", "show");
            assertThat(screen.lastParams.getInt("id", -1)).isEqualTo(2);
        }

        @Test
        void hideCurrentIsIdempotent() {
            RecordingScreen screen = new RecordingScreen();
            lifecycle.show(screen, NavParams.empty());

            lifecycle.hideCurrent();
            lifecycle.hideCurrent();

            assertThat(screen.calls).containsExactly("show", "hide");
            assertThat(lifecycle.current()).isNull();
        }

        @Test
        void hideCurrentWithNothingShowingIsANoOp() {
            lifecycle.hideCurrent();
            assertThat(lifecycle.current()).isNull();
        }

        @Test
        void registersASubscribingScreenWhileItIsVisible() {
            SubscribingScreen screen = new SubscribingScreen();

            lifecycle.show(screen, NavParams.empty());
            assertThat(bus.isRegistered(screen)).isTrue();

            bus.post("while-visible");
            assertThat(screen.received).containsExactly("while-visible");
        }

        @Test
        void unregistersOnHideSoLatePushesCannotReachAHiddenScreen() {
            SubscribingScreen screen = new SubscribingScreen();
            lifecycle.show(screen, NavParams.empty());
            lifecycle.hideCurrent();

            assertThat(bus.isRegistered(screen)).isFalse();
            bus.post("after-hide");
            assertThat(screen.received).isEmpty();
        }

        @Test
        void doesNotRegisterAScreenThatDidNotOptIn() {
            RecordingScreen screen = new RecordingScreen();
            lifecycle.show(screen, NavParams.empty());

            assertThat(bus.isRegistered(screen)).isFalse();
        }

        @Test
        void reRegistersOnASecondVisit() {
            SubscribingScreen screen = new SubscribingScreen();
            RecordingScreen other = new RecordingScreen();

            lifecycle.show(screen, NavParams.empty());
            lifecycle.show(other, NavParams.empty());
            lifecycle.show(screen, NavParams.empty());

            assertThat(bus.isRegistered(screen)).isTrue();
            bus.post("second-visit");
            assertThat(screen.received).containsExactly("second-visit");
        }

        @Test
        void aDefaultScreenUsesTheNoOpHooks() {
            Screen bare = new Screen() {
            };
            lifecycle.show(bare, NavParams.empty());
            lifecycle.hideCurrent();

            assertThat(bare.listensToEvents()).isFalse();
        }
    }

    @Nested
    @DisplayName("ScreenCache")
    class Cache {

        private ScreenCache<RecordingScreen> cache;
        private AtomicInteger builds;

        @BeforeEach
        void setUp() {
            cache = new ScreenCache<>();
            builds = new AtomicInteger();
        }

        @Test
        void buildsLazilyAndOnlyOnce() {
            cache.register("home", () -> {
                builds.incrementAndGet();
                return new RecordingScreen();
            });

            assertThat(builds).hasValue(0);
            assertThat(cache.isBuilt("home")).isFalse();

            RecordingScreen first = cache.get("home");
            RecordingScreen second = cache.get("home");

            assertThat(builds).hasValue(1);
            assertThat(second).isSameAs(first);
            assertThat(cache.isBuilt("home")).isTrue();
            assertThat(cache.size()).isEqualTo(1);
        }

        @Test
        void reportsRegistrationsAndCachedIds() {
            cache.register("a", RecordingScreen::new);
            cache.register("b", RecordingScreen::new);
            cache.get("a");

            assertThat(cache.isRegistered("a")).isTrue();
            assertThat(cache.isRegistered("zzz")).isFalse();
            assertThat(cache.registeredRouteIds()).containsExactly("a", "b");
            assertThat(cache.cachedRouteIds()).containsExactly("a");
        }

        @Test
        void rejectsDuplicateRegistration() {
            cache.register("a", RecordingScreen::new);

            assertThatIllegalStateException()
                    .isThrownBy(() -> cache.register("a", RecordingScreen::new))
                    .withMessageContaining("a");
        }

        @Test
        void unknownRouteFailsWithTheKnownIds() {
            cache.register("a", RecordingScreen::new);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> cache.get("ghost"))
                    .withMessageContaining("ghost")
                    .withMessageContaining("a");
        }

        @Test
        void aBuilderReturningNullFailsLoudly() {
            cache.register("bad", () -> null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> cache.get("bad"))
                    .withMessageContaining("bad");
        }

        @Test
        void evictDropsOneInstanceAndKeepsItsBuilder() {
            cache.register("a", () -> {
                builds.incrementAndGet();
                return new RecordingScreen();
            });
            cache.get("a");

            assertThat(cache.evict("a")).isTrue();
            assertThat(cache.evict("a")).isFalse();
            assertThat(cache.isBuilt("a")).isFalse();

            cache.get("a");
            assertThat(builds).hasValue(2);
        }

        @Test
        void evictAllClearsEveryInstance() {
            cache.register("a", RecordingScreen::new);
            cache.register("b", RecordingScreen::new);
            cache.get("a");
            cache.get("b");

            cache.evictAll();

            assertThat(cache.size()).isZero();
            assertThat(cache.registeredRouteIds()).containsExactly("a", "b");
        }
    }
}
