package client.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Navigator} — route registry, current-route state and the back-stack
 * (E4.2).
 */
class NavigatorTest {

    private static final Route HOME = Route.shell("home", "Dashboard");
    private static final Route BANK = Route.shell("bank", "Question bank");
    private static final Route DETAIL = Route.shell("bank.detail", "Question", "Detail");

    private Navigator navigator;
    private List<NavigationEvent> events;

    @BeforeEach
    void setUp() {
        navigator = new Navigator();
        navigator.registerAll(HOME, BANK, DETAIL);
        events = new ArrayList<>();
        navigator.addListener(events::add);
    }

    @Nested
    @DisplayName("registry")
    class Registry {

        @Test
        void registersAndLooksUpRoutes() {
            assertThat(navigator.route("bank")).contains(BANK);
            assertThat(navigator.isRegistered("bank")).isTrue();
            assertThat(navigator.isRegistered("nope")).isFalse();
            assertThat(navigator.routes()).containsExactly(HOME, BANK, DETAIL);
        }

        @Test
        void rejectsDuplicateIds() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> navigator.register(Route.shell("bank", "Other")))
                    .withMessageContaining("bank");
        }

        @Test
        void unknownRouteFailsWithTheKnownIds() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> navigator.navigate("ghost"))
                    .withMessageContaining("ghost")
                    .withMessageContaining("bank");
        }

        @Test
        void rejectsANonPositiveBackStackLimit() {
            assertThatIllegalArgumentException().isThrownBy(() -> new Navigator(0));
        }
    }

    @Nested
    @DisplayName("navigation")
    class Navigation {

        @Test
        void startsWithNoCurrentEntry() {
            assertThat(navigator.current()).isEmpty();
            assertThat(navigator.currentRouteId()).isNull();
            assertThat(navigator.canGoBack()).isFalse();
        }

        @Test
        void navigateSetsCurrentAndFiresPush() {
            navigator.navigate("home");

            assertThat(navigator.currentRouteId()).isEqualTo("home");
            assertThat(navigator.isCurrent("home")).isTrue();
            assertThat(events).hasSize(1);
            assertThat(events.get(0).kind()).isEqualTo(NavigationEvent.Kind.PUSH);
            assertThat(events.get(0).previous()).isEmpty();
            assertThat(events.get(0).to().route()).isEqualTo(HOME);
        }

        @Test
        void navigateCarriesParamsThrough() {
            navigator.navigate("bank.detail", NavParams.of("questionId", 21014));

            assertThat(navigator.current()).get()
                    .extracting(entry -> entry.params().getInt("questionId", -1))
                    .isEqualTo(21014);
        }

        @Test
        void firstNavigationDoesNotGrowTheBackStack() {
            navigator.navigate("home");
            assertThat(navigator.backStackDepth()).isZero();
        }

        @Test
        void subsequentNavigationsPushTheLeftEntry() {
            navigator.navigate("home");
            navigator.navigate("bank");
            navigator.navigate("bank.detail");

            assertThat(navigator.backStackIds()).containsExactly("bank", "home");
            assertThat(navigator.canGoBack()).isTrue();
        }

        @Test
        void navigatingToTheCurrentRouteStillPushesIt() {
            // Re-entering a route with new params is a real forward step: going
            // back from question #2 must land on question #1, not on the list.
            navigator.navigate("bank.detail", NavParams.of("questionId", 1));
            navigator.navigate("bank.detail", NavParams.of("questionId", 2));

            assertThat(navigator.backStackIds()).containsExactly("bank.detail");
            navigator.back();
            assertThat(navigator.current()).get()
                    .extracting(entry -> entry.params().getInt("questionId", -1))
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("replace and reset")
    class ReplaceAndReset {

        @Test
        void replaceDoesNotGrowTheBackStack() {
            navigator.navigate("home");
            navigator.replace("bank");

            assertThat(navigator.currentRouteId()).isEqualTo("bank");
            assertThat(navigator.backStackDepth()).isZero();
            assertThat(events.get(1).kind()).isEqualTo(NavigationEvent.Kind.REPLACE);
        }

        @Test
        void replaceStillReportsWhereItCameFrom() {
            navigator.navigate("home");
            navigator.replace("bank", NavParams.empty());

            assertThat(events.get(1).previous()).get()
                    .extracting(NavEntry::routeId).isEqualTo("home");
        }

        @Test
        void resetClearsTheWholeHistory() {
            navigator.navigate("home");
            navigator.navigate("bank");
            navigator.navigate("bank.detail");

            navigator.reset("home");

            assertThat(navigator.currentRouteId()).isEqualTo("home");
            assertThat(navigator.canGoBack()).isFalse();
            assertThat(navigator.backStackIds()).isEmpty();
        }
    }

    @Nested
    @DisplayName("back")
    class Back {

        @Test
        void backAtTheRootIsANoOpAndReportsFalse() {
            navigator.navigate("home");

            assertThat(navigator.back()).isFalse();
            assertThat(navigator.currentRouteId()).isEqualTo("home");
            assertThat(events).hasSize(1);
        }

        @Test
        void backPopsTheMostRecentEntry() {
            navigator.navigate("home");
            navigator.navigate("bank");

            assertThat(navigator.back()).isTrue();
            assertThat(navigator.currentRouteId()).isEqualTo("home");
            assertThat(navigator.canGoBack()).isFalse();
        }

        @Test
        void backRestoresTheParamsTheEntryWasVisitedWith() {
            navigator.navigate("bank.detail", NavParams.of("questionId", 7));
            navigator.navigate("bank");

            navigator.back();

            assertThat(navigator.current()).get()
                    .extracting(entry -> entry.params().getInt("questionId", -1))
                    .isEqualTo(7);
        }

        @Test
        void backFiresABackwardsEvent() {
            navigator.navigate("home");
            navigator.navigate("bank");
            events.clear();

            navigator.back();

            assertThat(events).hasSize(1);
            assertThat(events.get(0).isBackwards()).isTrue();
            assertThat(events.get(0).kind()).isEqualTo(NavigationEvent.Kind.BACK);
        }

        @Test
        void clearHistoryLeavesTheCurrentEntryAlone() {
            navigator.navigate("home");
            navigator.navigate("bank");

            navigator.clearHistory();

            assertThat(navigator.currentRouteId()).isEqualTo("bank");
            assertThat(navigator.canGoBack()).isFalse();
        }
    }

    @Nested
    @DisplayName("back-stack bounds")
    class Bounds {

        @Test
        void dropsTheOldestEntryBeyondTheLimit() {
            Navigator bounded = new Navigator(2);
            bounded.registerAll(HOME, BANK, DETAIL);

            bounded.navigate("home");
            bounded.navigate("bank");
            bounded.navigate("bank.detail");
            bounded.navigate("bank");

            // home fell off the bottom; the two most recent survive.
            assertThat(bounded.backStackIds()).containsExactly("bank.detail", "bank");
            assertThat(bounded.backStackDepth()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("listeners")
    class Listeners {

        @Test
        void listenersCanBeRemoved() {
            List<NavigationEvent> second = new ArrayList<>();
            navigator.addListener(second::add);
            assertThat(navigator.listenerCount()).isEqualTo(2);

            navigator.removeListener(second::add);   // a different lambda instance
            assertThat(navigator.listenerCount()).isEqualTo(2);

            navigator.removeListener(events::add);
            assertThat(navigator.listenerCount()).isEqualTo(2);
        }

        @Test
        void removingAHeldListenerReferenceWorks() {
            List<NavigationEvent> captured = new ArrayList<>();
            java.util.function.Consumer<NavigationEvent> listener = captured::add;
            navigator.addListener(listener);
            navigator.removeListener(listener);

            navigator.navigate("home");
            assertThat(captured).isEmpty();
        }

        @Test
        void aListenerMayNavigateAgainWithoutBreakingTheIteration() {
            // The redirect case: "you are not logged in" bounces to another route
            // from inside the notification. Iterating a snapshot keeps this safe.
            navigator.addListener(event -> {
                if (event.to().routeId().equals("bank") && !navigator.isCurrent("home")) {
                    navigator.navigate("home");
                }
            });

            navigator.navigate("bank");

            assertThat(navigator.currentRouteId()).isEqualTo("home");
        }

        @Test
        void rejectsANullListener() {
            assertThatThrownBy(() -> navigator.addListener(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
