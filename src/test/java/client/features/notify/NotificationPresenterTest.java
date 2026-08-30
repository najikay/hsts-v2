package client.features.notify;

import client.core.Routes;
import client.ui.components.logic.ToastSpec;
import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for the notification presentation rules (E17.4/E17.5).
 *
 * <p>Runs with no toolkit: the icon literals are compile-time string constants
 * and {@link ToastSpec} is a record, so the whole mapping is checkable without
 * ever creating a node.
 */
class NotificationPresenterTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    @ParameterizedTest
    @EnumSource(NotificationType.class)
    @DisplayName("every type has an icon and a toast, so no push can render blank")
    void everyTypeIsCovered(NotificationType type) {
        assertThat(NotificationPresenter.iconFor(type)).isNotBlank();
        assertThat(NotificationPresenter.toastFor(row(type, "Title", "Body"))).isNotNull();
    }

    /**
     * Types share an icon only in named families, so the panel still scans quickly.
     *
     * <p>Rewritten under U-63, and the rewrite is the point. This was
     * {@code icons.size() >= values().length - 1}: a tolerance of exactly one collision,
     * expressed as a magic number that said nothing about <em>which</em> one. Adding
     * {@code BOT_CHANGED} made it two and turned a deliberate design decision into an
     * arithmetic failure with no sentence attached. Spelled out rather than counted, on
     * {@code NotifyDtoTest.theTypesExist}'s stated rule: a new collision should now fail with
     * the two types named, and be either justified here or given its own icon.
     *
     * <p>Two families share, and both are events a reader groups anyway:
     *
     * <ul>
     *   <li><b>grading</b> — a grade published to a student and papers due for a teacher are
     *       the same subject seen from either side of the desk;</li>
     *   <li><b>the study bot</b> — sources changed, a bot created, a bot switched on or off.
     *       A reader scanning the panel is looking for "something about the study bot", and
     *       the sentence beside the icon says which something (BOT amendment A4).</li>
     * </ul>
     */
    @Test
    @DisplayName("icons are distinct except in two named families, so the panel scans quickly")
    void iconsAreDistinctOutsideTheNamedFamilies() {
        Map<String, Set<NotificationType>> byIcon = new HashMap<>();
        EnumSet.allOf(NotificationType.class).forEach(type ->
                byIcon.computeIfAbsent(NotificationPresenter.iconFor(type),
                        icon -> EnumSet.noneOf(NotificationType.class)).add(type));

        Set<Set<NotificationType>> allowedFamilies = Set.of(
                EnumSet.of(NotificationType.GRADE_PUBLISHED, NotificationType.GRADING_DUE),
                EnumSet.of(NotificationType.BOT_SOURCE_CHANGED, NotificationType.BOT_CHANGED));

        byIcon.forEach((icon, types) -> assertThat(types.size() == 1
                || allowedFamilies.contains(types))
                .as("%s share the icon %s and are not a named family. Give one of them its "
                        + "own icon, or add the family here with the reason", types, icon)
                .isTrue());
    }

    @Test
    @DisplayName("good news is a success toast")
    void goodNewsIsSuccess() {
        assertThat(NotificationPresenter.toastFor(row(NotificationType.APPROVAL_APPROVED, "t", "b"))
                .variant()).isEqualTo(ToastSpec.Variant.SUCCESS);
        assertThat(NotificationPresenter.toastFor(row(NotificationType.GRADE_PUBLISHED, "t", "b"))
                .variant()).isEqualTo(ToastSpec.Variant.SUCCESS);
    }

    @Test
    @DisplayName("something that needs a decision is a warn toast")
    void decisionsAreWarn() {
        assertThat(NotificationPresenter.toastFor(row(NotificationType.APPROVAL_REJECTED, "t", "b"))
                .variant()).isEqualTo(ToastSpec.Variant.WARN);
        assertThat(NotificationPresenter.toastFor(row(NotificationType.INTEGRITY_ALERT, "t", "b"))
                .variant()).isEqualTo(ToastSpec.Variant.WARN);
    }

    @Test
    @DisplayName("everything else is informational")
    void therestAreInfo() {
        assertThat(NotificationPresenter.toastFor(row(NotificationType.TIME_EXTENDED, "t", "b"))
                .variant()).isEqualTo(ToastSpec.Variant.INFO);
        assertThat(NotificationPresenter.toastFor(row(NotificationType.BOT_SOURCE_CHANGED, "t", "b"))
                .variant()).isEqualTo(ToastSpec.Variant.INFO);
        assertThat(NotificationPresenter.toastFor(row(NotificationType.RELEASE_OPENING_SOON, "t", "b"))
                .variant()).isEqualTo(ToastSpec.Variant.INFO);
        assertThat(NotificationPresenter.toastFor(row(NotificationType.APPROVAL_REQUESTED, "t", "b"))
                .variant()).isEqualTo(ToastSpec.Variant.INFO);
    }

    @ParameterizedTest
    @EnumSource(NotificationType.class)
    @DisplayName("no notification ever raises an error toast (F11.3)")
    void nothingIsAnErrorToast(NotificationType type) {
        assertThat(NotificationPresenter.toastFor(row(type, "t", "b")).variant())
                .as("an error toast means the user's own action failed, which this never is")
                .isNotEqualTo(ToastSpec.Variant.ERROR);
    }

    @Test
    @DisplayName("the toast reuses the notification's own words, verbatim")
    void toastReusesTheText() {
        ToastSpec toast = NotificationPresenter.toastFor(
                row(NotificationType.GRADE_PUBLISHED, "Your grade is ready", "Algebra Midterm."));

        assertThat(toast.title()).isEqualTo("Your grade is ready");
        assertThat(toast.message()).isEqualTo("Algebra Midterm.");
    }

    @Test
    @DisplayName("the age is the row's created time, read relatively")
    void ageIsRelative() {
        NotificationDto row = new NotificationDto(1L, NotificationType.TIME_EXTENDED, "t", "",
                NavRef.none(), NOW.minus(Duration.ofMinutes(5)), null);

        assertThat(NotificationPresenter.ageOf(row, NOW)).isEqualTo("5 min ago");
    }

    @Test
    @DisplayName("the accessible text says everything the styling says")
    void accessibleTextCarriesTheUnreadState() {
        NotificationDto unread = new NotificationDto(1L, NotificationType.GRADE_PUBLISHED,
                "Your grade is ready", "Algebra Midterm.", NavRef.none(), NOW, null);
        NotificationDto read = unread.readAt(NOW);

        assertThat(NotificationPresenter.accessibleTextOf(unread, NOW))
                .startsWith("Unread. ")
                .contains("Your grade is ready")
                .contains("Algebra Midterm.")
                .endsWith("just now");
        assertThat(NotificationPresenter.accessibleTextOf(read, NOW)).doesNotContain("Unread");
    }

    @Test
    @DisplayName("a row with no body still reads cleanly")
    void bodylessAccessibleText() {
        NotificationDto row = new NotificationDto(1L, NotificationType.TIME_EXTENDED,
                "Extra time added", "", NavRef.none(), NOW, NOW);

        assertThat(NotificationPresenter.accessibleTextOf(row, NOW))
                .isEqualTo("Extra time added. just now");
    }

    // ===================== UI wave 2: the row's icon badge ===============

    @ParameterizedTest
    @EnumSource(NotificationType.class)
    @DisplayName("every type gets a badge tone, so no row renders an untinted square")
    void everyTypeHasABadgeTone(NotificationType type) {
        assertThat(NotificationPresenter.badgeToneFor(type))
                .isIn("ok", "danger", "accent");
    }

    @Test
    @DisplayName("⚑ the badge agrees with the toast: one event never gets two colours")
    void theBadgeAndTheToastAgree() {
        // The push arrives as a toast and is then listed in the popover. A green
        // toast followed by a red badge has told the reader two different things
        // about one event, and the reader believes the second one.
        for (NotificationType type : NotificationType.values()) {
            ToastSpec toast = NotificationPresenter.toastFor(row(type, "Title", "Body"));
            String badge = NotificationPresenter.badgeToneFor(type);

            String expected = switch (toast.variant()) {
                case SUCCESS -> "ok";
                case WARN -> "danger";
                default -> "accent";
            };
            assertThat(badge).as("%s", type).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("good news is a green badge and a decision is a red one")
    void thePairingIsTheObviousOne() {
        assertThat(NotificationPresenter.badgeToneFor(NotificationType.GRADE_PUBLISHED))
                .isEqualTo("ok");
        assertThat(NotificationPresenter.badgeToneFor(NotificationType.APPROVAL_REJECTED))
                .isEqualTo("danger");
        assertThat(NotificationPresenter.badgeToneFor(NotificationType.INTEGRITY_ALERT))
                .isEqualTo("danger");
        assertThat(NotificationPresenter.badgeToneFor(NotificationType.APPROVAL_REQUESTED))
                .isEqualTo("accent");
    }

    @Test
    @DisplayName("null arguments are refused")
    void nullsAreRefused() {
        assertThatNullPointerException()
                .isThrownBy(() -> NotificationPresenter.badgeToneFor(null));
        assertThatNullPointerException().isThrownBy(() -> NotificationPresenter.iconFor(null));
        assertThatNullPointerException().isThrownBy(() -> NotificationPresenter.toastFor(null));
        assertThatNullPointerException().isThrownBy(() -> NotificationPresenter.ageOf(null, NOW));
        assertThatNullPointerException()
                .isThrownBy(() -> NotificationPresenter.accessibleTextOf(null, NOW));
        assertThatNullPointerException().isThrownBy(() -> NotificationPresenter.paramsFor(null));
    }

    // ===================== The deep link (⚑ every notification) ==========

    @Test
    @DisplayName("⚑ the reference's entity id becomes the parameter its screen reads")
    void theEntityIdBecomesAParam() {
        // The defect: NotificationsPanel.activate called the one-argument navigate, so
        // ref.entityId() was dropped for EVERY notification in the app. These are the exact
        // NavRefs NotificationCatalog writes, asserted against the exact keys the
        // destination screens' onShow(NavParams) look up.
        assertThat(NotificationPresenter.paramsFor(NavRef.to(Routes.EXAMS.id(), 11L))
                .getLong("examVersionId", 0))
                .as("F4.2: the rejection notification has to open the version it names, and "
                        + "ExamListView reads exactly this key")
                .isEqualTo(11L);
        assertThat(NotificationPresenter.paramsFor(NavRef.to(Routes.APPROVALS.id(), 55L))
                .getLong("examVersionId", 0))
                .isEqualTo(55L);
        assertThat(NotificationPresenter.paramsFor(NavRef.to(Routes.MONITOR.id(), 7L))
                .getLong("executionId", 0))
                .isEqualTo(7L);
        assertThat(NotificationPresenter.paramsFor(NavRef.to(Routes.TAKE_EXAM.id(), 8L))
                .getLong("executionId", 0))
                .isEqualTo(8L);
        assertThat(NotificationPresenter.paramsFor(NavRef.to(Routes.RELEASES.id(), 9L))
                .getLong("executionId", 0))
                .isEqualTo(9L);
        assertThat(NotificationPresenter.paramsFor(NavRef.to(Routes.MY_GRADES.id(), 3L))
                .getLong("attemptId", 0))
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("a reference with nothing to carry navigates with nothing")
    void referencesWithoutAnIdCarryNothing() {
        assertThat(NotificationPresenter.paramsFor(NavRef.none()).isEmpty()).isTrue();
        assertThat(NotificationPresenter.paramsFor(NavRef.to(Routes.EXAMS.id())).isEmpty())
                .as("a route that needs no argument must not gain an empty one")
                .isTrue();
    }

    @Test
    @DisplayName("bot.manager gets no param, because a bot id is not a course code ⚑")
    void theOneRouteThatCannotBeDeepLinked() {
        // botSourceChanged carries a bot id; BotManagerView.PARAM_COURSE reads a String
        // course code. Passing the Long under that key would throw IllegalArgumentException
        // out of NavParams.get on a bell click, which is worse than not deep-linking.
        assertThat(NotificationPresenter.paramsFor(NavRef.to(Routes.BOT_MANAGER.id(), 4L))
                .isEmpty())
                .isTrue();
        assertThat(NotificationPresenter.paramsFor(new NavRef("no.such.route", 4L)).isEmpty())
                .as("and a route from a later epic degrades the same way rather than guessing")
                .isTrue();
    }

    private static NotificationDto row(NotificationType type, String title, String body) {
        return new NotificationDto(1L, type, title, body, NavRef.none(), NOW, null);
    }
}
