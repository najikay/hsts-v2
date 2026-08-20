package client.features.notify;

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
import java.util.HashSet;
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

    @Test
    @DisplayName("distinct types mostly get distinct icons, so the panel scans quickly")
    void iconsAreMostlyDistinct() {
        Set<String> icons = new HashSet<>();
        EnumSet.allOf(NotificationType.class).forEach(type ->
                icons.add(NotificationPresenter.iconFor(type)));

        assertThat(icons).hasSizeGreaterThanOrEqualTo(NotificationType.values().length - 1);
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

    @Test
    @DisplayName("null arguments are refused")
    void nullsAreRefused() {
        assertThatNullPointerException().isThrownBy(() -> NotificationPresenter.iconFor(null));
        assertThatNullPointerException().isThrownBy(() -> NotificationPresenter.toastFor(null));
        assertThatNullPointerException().isThrownBy(() -> NotificationPresenter.ageOf(null, NOW));
        assertThatNullPointerException()
                .isThrownBy(() -> NotificationPresenter.accessibleTextOf(null, NOW));
    }

    private static NotificationDto row(NotificationType type, String title, String body) {
        return new NotificationDto(1L, type, title, body, NavRef.none(), NOW, null);
    }
}
