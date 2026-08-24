package server.features.locks;

import common.dto.lock.EntityRef;
import common.dto.lock.LockTiming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.core.SessionManager;
import server.realtime.PushGateway;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link EditLockGuard} — the write-path consult behind BANK §6's "edit-locked by someone
 * else" CONFLICT and E7's coming twin (ruled 2026-08-24).
 *
 * <p>Runs against a REAL {@link EditLockService} instance, not a mock: the guard's whole
 * value is that it inherits the service's one definition of "live" (the {@code live(...)}
 * filter behind {@link EditLockService#holderOf}), and a mock would re-state that definition
 * here, which is the drift this class exists to prevent. No database is involved anywhere on
 * this path — the service is a map — so there is deliberately no repository or two-engine
 * test; per-process scope is part of the guard's documented contract.
 */
class EditLockGuardTest {

    private static final long DANA = 11;
    private static final long RINA = 12;
    private static final EntityRef QUESTION = EntityRef.question(42);
    private static final Instant T0 = Instant.parse("2026-08-24T09:00:00Z");

    private static final DisplayNames NAMES = userId -> switch ((int) userId) {
        case (int) DANA -> Optional.of("Dana Cohen");
        case (int) RINA -> Optional.of("Rina Barak");
        default -> Optional.empty();
    };

    private MutableClock clock;
    private EditLockService locks;
    private EditLockGuard guard;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        locks = new EditLockService(new PushGateway(new SessionManager()), NAMES, clock);
        guard = new EditLockGuard(locks);
    }

    @Test
    @DisplayName("an unexpired lock held by another user is reported, with the holder's name")
    void anotherHolderBlocks() {
        locks.acquire(RINA, QUESTION);

        assertThat(guard.heldByAnother(QUESTION, DANA))
                .hasValueSatisfying(holder -> {
                    assertThat(holder.is(RINA)).isTrue();
                    assertThat(holder.displayName()).isEqualTo("Rina Barak");
                });
    }

    @Test
    @DisplayName("the caller's own lock never blocks her")
    void ownLockDoesNotBlock() {
        locks.acquire(DANA, QUESTION);

        assertThat(guard.heldByAnother(QUESTION, DANA)).isEmpty();
    }

    @Test
    @DisplayName("an unlocked entity does not block")
    void unlockedDoesNotBlock() {
        assertThat(guard.heldByAnother(QUESTION, DANA)).isEmpty();
    }

    @Test
    @DisplayName("an expired lock does not block: expiry is the sweeper's job, not the writer's")
    void expiredDoesNotBlock() {
        locks.acquire(RINA, QUESTION);
        clock.advance(LockTiming.TTL.plusSeconds(1));

        assertThat(guard.heldByAnother(QUESTION, DANA)).isEmpty();
    }

    @Test
    @DisplayName("a released lock does not block")
    void releasedDoesNotBlock() {
        locks.acquire(RINA, QUESTION);
        locks.release(RINA, QUESTION);

        assertThat(guard.heldByAnother(QUESTION, DANA)).isEmpty();
    }

    @Test
    @DisplayName("null inputs are refused at construction and at the consult")
    void nullsRefused() {
        assertThatNullPointerException().isThrownBy(() -> new EditLockGuard(null));
        assertThatNullPointerException().isThrownBy(() -> guard.heldByAnother(null, DANA));
    }
}
