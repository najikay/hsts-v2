package client.ui.shell;

import client.ui.components.Icons;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ShellState}, {@link NavItem} and {@link Initials} — the app shell's model (E4.10). */
class ShellStateTest {

    private static final NavItem HOME = NavItem.of("home", "Dashboard", Icons.DASHBOARD);
    private static final NavItem BANK = NavItem.of("bank", "Question bank", Icons.BANK);
    private static final NavItem APPROVALS = NavItem.of("approvals", "Approvals", Icons.APPROVALS);

    private ShellState state;

    @BeforeEach
    void setUp() {
        state = new ShellState();
        state.setItems(List.of(HOME, BANK, APPROVALS));
    }

    @Nested
    @DisplayName("NavItem")
    class Items {

        @Test
        void carriesRouteLabelAndIcon() {
            assertThat(HOME.routeId()).isEqualTo("home");
            assertThat(HOME.label()).isEqualTo("Dashboard");
            assertThat(HOME.icon()).isEqualTo(Icons.DASHBOARD);
            assertThat(HOME.hasBadge()).isFalse();
        }

        @Test
        void badgesAreImmutableCopies() {
            NavItem badged = BANK.withBadge(3);

            assertThat(BANK.hasBadge()).isFalse();
            assertThat(badged.badge()).isEqualTo(3);
            assertThat(badged.routeId()).isEqualTo(BANK.routeId());
        }

        @ParameterizedTest
        @CsvSource({"0, ''", "1, 1", "9, 9", "10, 9+", "250, 9+"})
        void badgeTextIsCappedSoTheRailNeverStretches(int count, String expected) {
            assertThat(BANK.withBadge(count).badgeText()).isEqualTo(expected);
        }

        @Test
        void aDotBadgeCarriesNoNumber() {
            NavItem dotted = BANK.withDot();

            assertThat(dotted.hasBadge()).isTrue();
            assertThat(dotted.isDotBadge()).isTrue();
            assertThat(dotted.badgeText()).isEmpty();
            assertThat(dotted.badge()).isEqualTo(NavItem.BADGE_DOT);
        }

        @Test
        void rejectsUnusableItems() {
            assertThatIllegalArgumentException().isThrownBy(() -> NavItem.of("  ", "L", "i"));
            assertThatIllegalArgumentException().isThrownBy(() -> NavItem.of("r", "  ", "i"));
            assertThatThrownBy(() -> NavItem.of(null, "L", "i"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> NavItem.of("r", "L", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("active item")
    class Active {

        @Test
        void nothingIsActiveBeforeTheFirstNavigation() {
            assertThat(state.activeItem()).isEmpty();
            assertThat(state.isActive(HOME)).isFalse();
        }

        @Test
        void theCurrentRouteIsActiveWhenItIsARailItem() {
            state.setActiveRoute("bank");

            assertThat(state.activeItem()).contains(BANK);
            assertThat(state.isActive(BANK)).isTrue();
            assertThat(state.isActive(HOME)).isFalse();
            assertThat(state.activeRouteId()).isEqualTo("bank");
        }

        @Test
        void aChildRouteKeepsItsRailParentHighlighted() {
            // Opening a question detail must not un-highlight "Question bank".
            state.alias("bank.detail", "bank");
            state.setActiveRoute("bank.detail");

            assertThat(state.activeItem()).contains(BANK);
            assertThat(state.isActive(BANK)).isTrue();
        }

        @Test
        void aRouteThatIsNeitherARailItemNorAliasedHighlightsNothing() {
            state.setActiveRoute("settings");

            assertThat(state.activeItem()).isEmpty();
        }

        @Test
        void anAliasPointingAtAMissingRailItemHighlightsNothing() {
            state.alias("bot.chat", "bot");
            state.setActiveRoute("bot.chat");

            assertThat(state.activeItem()).isEmpty();
        }

        @Test
        void rejectsNullAliases() {
            assertThatThrownBy(() -> state.alias(null, "bank"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.alias("x", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("badges")
    class Badges {

        @Test
        void aPushUpdatesOneItemInPlace() {
            assertThat(state.setBadge("approvals", 3)).isTrue();

            assertThat(state.item("approvals")).get()
                    .extracting(NavItem::badge).isEqualTo(3);
            // Order is preserved: the rail must not reshuffle when a count changes.
            assertThat(state.items()).extracting(NavItem::routeId)
                    .containsExactly("home", "bank", "approvals");
        }

        @Test
        void anUnchangedCountIsANoOp() {
            state.setBadge("approvals", 3);

            assertThat(state.setBadge("approvals", 3)).isFalse();
        }

        @Test
        void aPushForARouteThisRoleCannotSeeIsIgnored() {
            assertThat(state.setBadge("grading", 5)).isFalse();
        }

        @Test
        void clearBadgesEmptiesEveryCount() {
            state.setBadge("approvals", 3);
            state.setBadge("bank", 1);

            state.clearBadges();

            assertThat(state.items()).allSatisfy(item -> assertThat(item.hasBadge()).isFalse());
        }

        @Test
        void clearBadgesOnACleanRailChangesNothing() {
            AtomicInteger changes = new AtomicInteger();
            state.onChange(changes::incrementAndGet);

            state.clearBadges();

            assertThat(changes).hasValue(0);
        }

        @ParameterizedTest
        @CsvSource({"0, ''", "4, 4", "9, 9", "17, 9+"})
        void theBellBadgeIsCappedToo(int unread, String expected) {
            state.setUnreadNotifications(unread);

            assertThat(state.unreadNotifications()).isEqualTo(unread);
            assertThat(state.unreadBadgeText()).isEqualTo(expected);
        }

        @Test
        void aNegativeUnreadCountIsFlooredAtZero() {
            state.setUnreadNotifications(-4);

            assertThat(state.unreadNotifications()).isZero();
            assertThat(state.unreadBadgeText()).isEmpty();
        }

        @Test
        void anUnchangedUnreadCountDoesNotNotify() {
            state.setUnreadNotifications(2);
            AtomicInteger changes = new AtomicInteger();
            state.onChange(changes::incrementAndGet);

            state.setUnreadNotifications(2);

            assertThat(changes).hasValue(0);
        }
    }

    @Nested
    @DisplayName("responsive rail (PRD §4.1)")
    class Responsive {

        @Test
        void startsExpanded() {
            assertThat(state.isCollapsed()).isFalse();
            assertThat(state.railWidth()).isEqualTo(224);
        }

        @Test
        void collapsesBelowTheThreshold() {
            assertThat(state.applyWindowWidth(1280)).isTrue();

            assertThat(state.isCollapsed()).isTrue();
            assertThat(state.railWidth()).isEqualTo(64);
        }

        @Test
        void staysExpandedAtAndAboveTheThreshold() {
            assertThat(state.applyWindowWidth(ShellState.COLLAPSE_WIDTH_THRESHOLD)).isFalse();
            assertThat(state.applyWindowWidth(1600)).isFalse();
            assertThat(state.applyWindowWidth(1920)).isFalse();

            assertThat(state.isCollapsed()).isFalse();
        }

        @Test
        void reExpandsWhenTheWindowGrowsBack() {
            state.applyWindowWidth(1280);

            assertThat(state.applyWindowWidth(1600)).isTrue();
            assertThat(state.isCollapsed()).isFalse();
        }

        @Test
        void repeatedResizesInTheSameBandDoNothing() {
            state.applyWindowWidth(1280);

            assertThat(state.applyWindowWidth(1100)).isFalse();
        }

        @Test
        void aManualToggleOutranksTheResponsiveRuleForTheSession() {
            state.toggleCollapsed();
            assertThat(state.isCollapsed()).isTrue();
            assertThat(state.isExpansionPinned()).isTrue();

            // A wide window must not fight the user's explicit choice.
            assertThat(state.applyWindowWidth(1920)).isFalse();
            assertThat(state.isCollapsed()).isTrue();
        }

        @Test
        void unpinningHandsControlBackToTheResponsiveRule() {
            state.toggleCollapsed();
            state.unpinExpansion();

            assertThat(state.applyWindowWidth(1920)).isTrue();
            assertThat(state.isCollapsed()).isFalse();
        }

        @Test
        void togglingTwiceReturnsToExpanded() {
            state.toggleCollapsed();
            state.toggleCollapsed();

            assertThat(state.isCollapsed()).isFalse();
        }
    }

    @Nested
    @DisplayName("listeners")
    class Listeners {

        @Test
        void notifiesOnEveryModelChange() {
            AtomicInteger changes = new AtomicInteger();
            state.onChange(changes::incrementAndGet);

            state.setItems(List.of(HOME));
            state.setActiveRoute("home");
            state.setBadge("home", 2);
            state.toggleCollapsed();
            state.setUnreadNotifications(1);

            assertThat(changes).hasValue(5);
            assertThat(state.listenerCount()).isEqualTo(1);
        }

        @Test
        void rejectsNulls() {
            assertThatThrownBy(() -> state.onChange(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.setItems(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Initials")
    class Monogram {

        @Test
        void takesTheFirstAndLastWord() {
            assertThat(Initials.of("Dana Cohen")).isEqualTo("DC");
            assertThat(Initials.of("Anna Maria Rossi")).isEqualTo("AR");
        }

        @Test
        void aSingleWordNameGivesOneLetter() {
            assertThat(Initials.of("Madonna")).isEqualTo("M");
        }

        @Test
        void handlesUntidyWhitespace() {
            assertThat(Initials.of("  Dana   Cohen  ")).isEqualTo("DC");
        }

        @Test
        void skipsHonorificsAndLeadingPunctuation() {
            assertThat(Initials.of("Dr. Yossi Levi")).isEqualTo("DL");
            assertThat(Initials.of("Yossi -Ben Ari")).isEqualTo("YA");
        }

        @Test
        void handlesHebrewNamesWithoutMangling() {
            assertThat(Initials.of("שרה מזרחי")).isEqualTo("שמ");
        }

        @Test
        void nothingUsableFallsBackRatherThanRenderingEmpty() {
            assertThat(Initials.of(null)).isEqualTo(Initials.FALLBACK);
            assertThat(Initials.of("")).isEqualTo(Initials.FALLBACK);
            assertThat(Initials.of("   ")).isEqualTo(Initials.FALLBACK);
            assertThat(Initials.of("!!! ???")).isEqualTo(Initials.FALLBACK);
        }

        @Test
        void neverReturnsMoreThanTwoCharacters() {
            assertThat(Initials.of("A B C D E F G")).hasSize(2);
        }
    }
}
