package common.dto.lock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Contract tests for the edit-lock DTOs (E18.2).
 *
 * <p>Two of these carry real security weight and are worth the ceremony:
 * {@link EntityRef}'s normalisation <i>is</i> the mutual exclusion (two
 * spellings of one entity would be two locks), and {@link LockRequest} carrying
 * no user id is what makes "you cannot release someone else's lock" structural
 * rather than a check somebody has to remember.
 */
class LockDtoTest {

    private static final Instant EXPIRY = Instant.parse("2026-08-19T10:16:10Z");

    @Nested
    @DisplayName("EntityRef")
    class Ref {

        @Test
        @DisplayName("case and whitespace normalise, so one row is one lock")
        void normalisesType() {
            assertThat(new EntityRef("Question", 5))
                    .isEqualTo(new EntityRef("  question ", 5))
                    .isEqualTo(EntityRef.question(5));
        }

        @Test
        @DisplayName("different ids and different types are different keys")
        void distinguishes() {
            assertThat(EntityRef.question(5)).isNotEqualTo(EntityRef.question(6));
            assertThat(new EntityRef(EntityRef.EXAM_VERSION, 5)).isNotEqualTo(EntityRef.question(5));
        }

        @Test
        @DisplayName("a type is mandatory and cannot be blank")
        void requiresType() {
            assertThatNullPointerException().isThrownBy(() -> new EntityRef(null, 1));
            assertThatIllegalArgumentException().isThrownBy(() -> new EntityRef("   ", 1));
        }

        @Test
        @DisplayName("round-trips and reads well in a log line")
        void roundTrips() throws Exception {
            assertThat(roundTrip(EntityRef.question(12))).isEqualTo(EntityRef.question(12));
            assertThat(EntityRef.question(12)).hasToString("question#12");
        }
    }

    @Nested
    @DisplayName("LockHolder")
    class Holder {

        @Test
        @DisplayName("a missing name degrades to a neutral label, never to null")
        void namelessHolder() {
            assertThat(new LockHolder(7, null).displayName()).isEqualTo(LockHolder.UNKNOWN_NAME);
            assertThat(new LockHolder(7, "   ").displayName()).isEqualTo(LockHolder.UNKNOWN_NAME);
        }

        @Test
        @DisplayName("identity is by id, so two people with one name are two people")
        void identityIsById() {
            LockHolder rina = new LockHolder(1002, "Rina Barak");
            LockHolder impostor = new LockHolder(9999, "Rina Barak");

            assertThat(rina.is(1002)).isTrue();
            assertThat(impostor.is(1002)).isFalse();
            assertThat(rina).isNotEqualTo(impostor);
        }

        @Test
        @DisplayName("round-trips, Hebrew included")
        void roundTrips() throws Exception {
            assertThat(roundTrip(new LockHolder(3, "רינה ברק")).displayName()).isEqualTo("רינה ברק");
        }

        @Test
        @DisplayName("a log line names the person and the id, so a race is traceable")
        void readsWellInALogLine() {
            assertThat(new LockHolder(1002, "Rina Barak")).hasToString("Rina Barak (1002)");
        }
    }

    @Nested
    @DisplayName("LockRequest and LockResponse")
    class Wire {

        @Test
        @DisplayName("a lock request carries the entity and nothing about who is asking")
        void requestHasNoIdentity() {
            var components = java.util.Arrays.toString(LockRequest.class.getRecordComponents());

            assertThat(components.toLowerCase(java.util.Locale.ROOT)).doesNotContain("userid");
            assertThat(LockRequest.of("question", 3).entity()).isEqualTo(EntityRef.question(3));
            assertThatNullPointerException().isThrownBy(() -> new LockRequest(null));
        }

        @Test
        @DisplayName("a grant, a refusal and a free entity are three distinct answers")
        void threeAnswers() throws Exception {
            EntityRef entity = EntityRef.question(3);
            LockHolder me = new LockHolder(1001, "Dana Cohen");
            LockHolder other = new LockHolder(1002, "Rina Barak");

            LockResponse granted = LockResponse.granted(entity, me, EXPIRY);
            LockResponse refused = LockResponse.refused(entity, other, EXPIRY);
            LockResponse free = LockResponse.free(entity);

            assertThat(granted.granted()).isTrue();
            assertThat(granted.isFree()).isFalse();
            assertThat(refused.granted()).isFalse();
            assertThat(refused.holder()).isEqualTo(other);
            assertThat(free.granted()).isFalse();
            assertThat(free.isFree()).isTrue();
            assertThat(roundTrip(refused)).isEqualTo(refused);
        }

        @Test
        @DisplayName("a grant or a refusal must name somebody")
        void holderRequiredWhereItMatters() {
            EntityRef entity = EntityRef.question(3);

            assertThatNullPointerException()
                    .isThrownBy(() -> LockResponse.granted(entity, null, EXPIRY));
            assertThatNullPointerException()
                    .isThrownBy(() -> LockResponse.refused(entity, null, EXPIRY));
            assertThatNullPointerException()
                    .isThrownBy(() -> new LockResponse(false, null, null, null));
        }
    }

    @Nested
    @DisplayName("LockChange")
    class Change {

        @Test
        @DisplayName("acquired names the new holder; released and expired name nobody")
        void kinds() throws Exception {
            EntityRef entity = EntityRef.question(3);
            LockHolder rina = new LockHolder(1002, "Rina Barak");

            assertThat(LockChange.acquired(entity, rina).holder()).isEqualTo(rina);
            assertThat(LockChange.acquired(entity, rina).isFree()).isFalse();
            assertThat(LockChange.released(entity).isFree()).isTrue();
            assertThat(LockChange.expired(entity).kind()).isEqualTo(LockChange.Kind.EXPIRED);
            assertThat(roundTrip(LockChange.released(entity))).isEqualTo(LockChange.released(entity));
        }

        @Test
        @DisplayName("an acquisition without a holder is a bug, refused at construction")
        void acquiredNeedsAHolder() {
            assertThatNullPointerException()
                    .isThrownBy(() -> LockChange.acquired(EntityRef.question(1), null));
            assertThatNullPointerException()
                    .isThrownBy(() -> new LockChange(null, LockChange.Kind.RELEASED, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> new LockChange(EntityRef.question(1), null, null));
        }
    }

    @Nested
    @DisplayName("LockTiming")
    class Timing {

        @Test
        @DisplayName("three heartbeats fit in one TTL, so one lost packet costs nothing")
        void heartbeatIsAThirdOfTheTtl() {
            assertThat(LockTiming.renewalsPerTtl())
                    .as("a live editor must survive more than one lost renewal")
                    .isGreaterThanOrEqualTo(3);
            assertThat(LockTiming.HEARTBEAT).isLessThan(LockTiming.TTL);
        }

        @Test
        @DisplayName("the TTL is short enough to wait out and long enough to survive a hiccup")
        void ttlIsInTheRightRange() {
            assertThat(LockTiming.TTL.toSeconds()).isBetween(20L, 60L);
        }
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
