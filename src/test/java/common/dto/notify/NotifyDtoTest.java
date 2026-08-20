package common.dto.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip and normalisation tests for the notification DTOs (E17.3).
 *
 * <p>These records deserialize through their canonical constructor, so every
 * compact-constructor rule — the clamped limit, the defensive list copy, the
 * null body becoming empty — runs again on the receiving side. That is what
 * these tests pin down, together with Hebrew survival (X-I18N).
 */
class NotifyDtoTest {

    private static final Instant WHEN = Instant.parse("2026-08-19T10:15:30Z");

    @Nested
    @DisplayName("NavRef")
    class Ref {

        @Test
        @DisplayName("a route and an id survive the wire")
        void roundTrips() throws Exception {
            NavRef restored = roundTrip(NavRef.to("exams", 4242L));

            assertThat(restored.route()).isEqualTo("exams");
            assertThat(restored.entityId()).isEqualTo(4242L);
            assertThat(restored.isNavigable()).isTrue();
        }

        @Test
        @DisplayName("a route with no entity is still navigable")
        void routeOnly() {
            NavRef ref = NavRef.to("settings");

            assertThat(ref.entityId()).isNull();
            assertThat(ref.isNavigable()).isTrue();
        }

        @Test
        @DisplayName("blank and null routes collapse to 'nowhere to go'")
        void blankRouteIsNotNavigable() throws Exception {
            assertThat(NavRef.none().isNavigable()).isFalse();
            assertThat(new NavRef("   ", 7L).route()).isNull();
            assertThat(new NavRef(null, null).isNavigable()).isFalse();
            assertThat(roundTrip(NavRef.none()).isNavigable()).isFalse();
        }

        @Test
        @DisplayName("surrounding whitespace is trimmed, so two spellings are one route")
        void trimsRoute() {
            assertThat(new NavRef("  exams  ", 1L).route()).isEqualTo("exams");
        }
    }

    @Nested
    @DisplayName("NotificationDto")
    class Row {

        @Test
        @DisplayName("round-trips, Hebrew included")
        void roundTrips() throws Exception {
            NotificationDto original = new NotificationDto(9L, NotificationType.GRADE_PUBLISHED,
                    "הציון שלך מוכן", "אלגברה 11", NavRef.to("grades", 3L), WHEN, null);

            NotificationDto restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.title()).isEqualTo("הציון שלך מוכן");
            assertThat(restored.isUnread()).isTrue();
        }

        @Test
        @DisplayName("a null body and a null ref normalise on both sides of the wire")
        void normalisesOptionalFields() throws Exception {
            NotificationDto row = new NotificationDto(1L, NotificationType.TIME_EXTENDED,
                    "Extra time added", null, null, WHEN, null);

            assertThat(row.body()).isEmpty();
            assertThat(row.ref()).isEqualTo(NavRef.none());
            assertThat(roundTrip(row).body()).isEmpty();
        }

        @Test
        @DisplayName("readAt() marks an unread row and leaves a read one alone")
        void readAtIsIdempotent() {
            NotificationDto unread = new NotificationDto(1L, NotificationType.TIME_EXTENDED,
                    "Extra time added", "", NavRef.none(), WHEN, null);
            Instant first = WHEN.plusSeconds(60);

            NotificationDto read = unread.readAt(first);

            assertThat(read.isUnread()).isFalse();
            assertThat(read.readAt()).isEqualTo(first);
            assertThat(read.readAt(WHEN.plusSeconds(600))).isSameAs(read);
        }

        @Test
        @DisplayName("the three fields a row cannot exist without are required")
        void rejectsMissingEssentials() {
            assertThatNullPointerException().isThrownBy(() -> new NotificationDto(
                    1L, null, "t", "", NavRef.none(), WHEN, null));
            assertThatNullPointerException().isThrownBy(() -> new NotificationDto(
                    1L, NotificationType.TIME_EXTENDED, null, "", NavRef.none(), WHEN, null));
            assertThatNullPointerException().isThrownBy(() -> new NotificationDto(
                    1L, NotificationType.TIME_EXTENDED, "t", "", NavRef.none(), null, null));
        }
    }

    @Nested
    @DisplayName("NotificationsPage")
    class Page {

        @Test
        @DisplayName("carries an unread count that may exceed the listed rows")
        void countIsIndependentOfTheList() throws Exception {
            NotificationsPage page = new NotificationsPage(List.of(row(1L)), 17);

            assertThat(roundTrip(page).unreadCount()).isEqualTo(17);
            assertThat(page.size()).isEqualTo(1);
            assertThat(page.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("copies the caller's list, so later mutation cannot reach it")
        void copiesItems() {
            List<NotificationDto> mutable = new ArrayList<>(List.of(row(1L)));

            NotificationsPage page = new NotificationsPage(mutable, 1);
            mutable.add(row(2L));

            assertThat(page.items()).hasSize(1);
            assertThatThrownBy(() -> page.items().add(row(3L)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("null items and a negative count normalise")
        void normalises() {
            NotificationsPage page = new NotificationsPage(null, -4);

            assertThat(page.items()).isEmpty();
            assertThat(page.unreadCount()).isZero();
            assertThat(NotificationsPage.EMPTY.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("requests")
    class Requests {

        @Test
        @DisplayName("a nonsense limit is clamped, not refused")
        void clampsLimit() throws Exception {
            assertThat(new NotificationsGetRequest(0).limit())
                    .isEqualTo(NotificationsGetRequest.DEFAULT_LIMIT);
            assertThat(new NotificationsGetRequest(-9).limit())
                    .isEqualTo(NotificationsGetRequest.DEFAULT_LIMIT);
            assertThat(new NotificationsGetRequest(1_000_000).limit())
                    .isEqualTo(NotificationsGetRequest.MAX_LIMIT);
            assertThat(new NotificationsGetRequest(5).limit()).isEqualTo(5);
            assertThat(roundTrip(NotificationsGetRequest.defaults()).limit())
                    .isEqualTo(NotificationsGetRequest.DEFAULT_LIMIT);
        }

        @Test
        @DisplayName("mark-read has a single-row and a mark-all shape")
        void markReadShapes() throws Exception {
            MarkReadRequest one = MarkReadRequest.one(42L);
            MarkReadRequest all = MarkReadRequest.markAll();

            assertThat(one.all()).isFalse();
            assertThat(one.notificationId()).isEqualTo(42L);
            assertThat(all.all()).isTrue();
            assertThat(roundTrip(one)).isEqualTo(one);
            assertThat(roundTrip(all)).isEqualTo(all);
        }

        @Test
        @DisplayName("no request carries a user id — the recipient is the session")
        void noUserIdOnTheWire() {
            String fields = java.util.Arrays.toString(MarkReadRequest.class.getRecordComponents())
                    + java.util.Arrays.toString(NotificationsGetRequest.class.getRecordComponents());

            assertThat(fields.toLowerCase(java.util.Locale.ROOT)).doesNotContain("userid");
        }
    }

    @ParameterizedTest
    @EnumSource(NotificationType.class)
    @DisplayName("every notification type survives a round-trip")
    void everyTypeRoundTrips(NotificationType type) throws Exception {
        NotificationDto restored = roundTrip(
                new NotificationDto(1L, type, "t", "b", NavRef.to("exams", 1L), WHEN, null));

        assertThat(restored.type()).isEqualTo(type);
    }

    @Test
    @DisplayName("the eight PRD emit points all have a type")
    void theEightTypesExist() {
        assertThat(NotificationType.values()).containsExactlyInAnyOrder(
                NotificationType.APPROVAL_REQUESTED,
                NotificationType.APPROVAL_APPROVED,
                NotificationType.APPROVAL_REJECTED,
                NotificationType.GRADE_PUBLISHED,
                NotificationType.TIME_EXTENDED,
                NotificationType.BOT_SOURCE_CHANGED,
                NotificationType.RELEASE_OPENING_SOON,
                NotificationType.INTEGRITY_ALERT);
    }

    private static NotificationDto row(long id) {
        return new NotificationDto(id, NotificationType.APPROVAL_REQUESTED, "Exam waiting", "",
                NavRef.none(), WHEN, null);
    }

    private static <T extends Serializable> T roundTrip(T original) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            @SuppressWarnings("unchecked")
            T restored = (T) in.readObject();
            return restored;
        }
    }
}
