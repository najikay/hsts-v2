package client.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link AppArgs}, {@link Routes}, {@link Route} and {@link NavEntry} (E4.1, E4.2). */
class AppArgsAndRoutesTest {

    @Nested
    @DisplayName("AppArgs")
    class Args {

        @Test
        void defaultsToEverythingOff() {
            assertThat(AppArgs.none().gallery()).isFalse();
            assertThat(AppArgs.parse(List.of(), key -> null).gallery()).isFalse();
        }

        @Test
        void readsTheGalleryFlagFromArguments() {
            assertThat(AppArgs.parse(List.of("--gallery"), key -> null).gallery()).isTrue();
            assertThat(AppArgs.parse(List.of("--other", "--gallery"), key -> null).gallery()).isTrue();
        }

        @Test
        void theFlagIsCaseInsensitiveAndTrimmed() {
            assertThat(AppArgs.parse(List.of("  --GALLERY "), key -> null).gallery()).isTrue();
        }

        @Test
        void ignoresNullArguments() {
            java.util.List<String> withNull = new java.util.ArrayList<>();
            withNull.add(null);
            withNull.add("--gallery");

            assertThat(AppArgs.parse(withNull, key -> null).gallery()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"true", "TRUE", " true ", ""})
        void readsTheGalleryFlagFromASystemProperty(String value) {
            Map<String, String> props = Map.of(AppArgs.PROP_GALLERY, value);

            assertThat(AppArgs.parse(List.of(), props::get).gallery()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"false", "no", "0"})
        void anythingElseInThePropertyMeansOff(String value) {
            Map<String, String> props = Map.of(AppArgs.PROP_GALLERY, value);

            assertThat(AppArgs.parse(List.of(), props::get).gallery()).isFalse();
        }

        @Test
        void parsesRealArgumentArraysIncludingNull() {
            assertThat(AppArgs.parse((String[]) null).gallery()).isFalse();
            assertThat(AppArgs.parse(new String[]{"--gallery"}).gallery()).isTrue();
        }

        @Test
        void rejectsNullInputs() {
            assertThatThrownBy(() -> AppArgs.parse(null, key -> null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> AppArgs.parse(List.of(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Route")
    class RouteRecord {

        @Test
        void standaloneRoutesDoNotUseTheShell() {
            Route route = Route.standalone("connect", "Connect to server");

            assertThat(route.requiresShell()).isFalse();
            assertThat(route.breadcrumb()).isEqualTo("Connect to server");
        }

        @Test
        void shellRoutesUseTheShell() {
            assertThat(Route.shell("home", "Dashboard").requiresShell()).isTrue();
        }

        @Test
        void aShellRouteMayCarryAShorterBreadcrumb() {
            Route route = Route.shell("bank.detail", "Question #21014", "Detail");

            assertThat(route.title()).isEqualTo("Question #21014");
            assertThat(route.breadcrumb()).isEqualTo("Detail");
        }

        @Test
        void rejectsBlankOrNullIds() {
            assertThatIllegalArgumentException().isThrownBy(() -> Route.shell("  ", "Title"));
            assertThatThrownBy(() -> new Route(null, "t", "b", false))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Route("id", null, "b", false))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Route("id", "t", null, false))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("NavEntry and NavigationEvent")
    class Entries {

        @Test
        void anEntryExposesItsRouteId() {
            NavEntry entry = NavEntry.of(Routes.CONNECT);

            assertThat(entry.routeId()).isEqualTo("connect");
            assertThat(entry.params()).isEqualTo(NavParams.empty());
        }

        @Test
        void rejectsNullComponents() {
            assertThatThrownBy(() -> new NavEntry(null, NavParams.empty()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new NavEntry(Routes.CONNECT, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void anEventWithoutAPreviousEntryIsTheFirstNavigation() {
            NavigationEvent event = new NavigationEvent(
                    null, NavEntry.of(Routes.CONNECT), NavigationEvent.Kind.PUSH);

            assertThat(event.previous()).isEmpty();
            assertThat(event.isBackwards()).isFalse();
        }

        @Test
        void backEventsReportThemselvesAsBackwards() {
            NavigationEvent event = new NavigationEvent(
                    NavEntry.of(Routes.QUESTIONS), NavEntry.of(Routes.CONNECT),
                    NavigationEvent.Kind.BACK);

            assertThat(event.isBackwards()).isTrue();
            assertThat(event.previous()).contains(NavEntry.of(Routes.QUESTIONS));
        }

        @Test
        void rejectsNullTargets() {
            assertThatThrownBy(() ->
                    new NavigationEvent(null, null, NavigationEvent.Kind.PUSH))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() ->
                    new NavigationEvent(null, NavEntry.of(Routes.CONNECT), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Routes table")
    class Table {

        @Test
        void declaresTheE4Routes() {
            assertThat(Routes.all())
                    .extracting(Route::id)
                    .containsExactly("connect", "settings", "questions");
        }

        @Test
        void registersEveryRouteOnANavigator() {
            Navigator navigator = new Navigator();
            Routes.registerAll(navigator);

            assertThat(navigator.routes()).containsExactlyElementsOf(Routes.all());
            assertThat(navigator.isRegistered(Routes.CONNECT.id())).isTrue();
        }

        @Test
        void connectIsFullBleedBecauseItPredatesTheShell() {
            assertThat(Routes.CONNECT.requiresShell()).isFalse();
        }

        @Test
        void routeIdsAreUnique() {
            assertThat(Routes.all()).extracting(Route::id).doesNotHaveDuplicates();
        }
    }
}
