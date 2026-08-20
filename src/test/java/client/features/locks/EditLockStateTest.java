package client.features.locks;

import common.dto.lock.EntityRef;
import common.dto.lock.LockChange;
import common.dto.lock.LockHolder;
import common.dto.lock.LockResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for the editor's lock state machine (E18.3).
 *
 * <p>The case worth the whole class is the last group: a lock becoming free
 * means two opposite things depending on who held it a moment before, and
 * getting that wrong would tell a user their editing session is fine when it has
 * silently ended.
 */
class EditLockStateTest {

    private static final long ME = 1001L;
    private static final EntityRef QUESTION = EntityRef.question(42);
    private static final LockHolder MYSELF = new LockHolder(ME, "Dana Cohen");
    private static final LockHolder RINA = new LockHolder(1002L, "Rina Barak");
    private static final Instant EXPIRY = Instant.parse("2026-08-19T09:00:40Z");

    private EditLockState state;

    @BeforeEach
    void setUp() {
        state = new EditLockState(ME);
    }

    @Test
    @DisplayName("a closed editor is idle and says nothing")
    void startsIdle() {
        assertThat(state.mode()).isEqualTo(EditLockState.Mode.IDLE);
        assertThat(state.snapshot().isEditable()).isFalse();
        assertThat(state.snapshot().isReadOnly()).isFalse();
        assertThat(state.snapshot().offersTakeover()).isFalse();
        assertThat(state.snapshot().bannerText("question")).isEmpty();
        assertThat(state.snapshot().holderName()).isEmpty();
    }

    @Test
    @DisplayName("opening shows 'checking', not read-only: nothing is known yet")
    void openingIsItsOwnState() {
        EditLockState.Snapshot snapshot = state.opening();

        assertThat(snapshot.mode()).isEqualTo(EditLockState.Mode.CHECKING);
        assertThat(snapshot.isEditable()).isFalse();
        assertThat(snapshot.isReadOnly())
                .as("claiming somebody else has it before the server answered would be a guess")
                .isFalse();
        assertThat(snapshot.bannerText("question")).contains(LockCopy.CHECKING);
    }

    @Nested
    @DisplayName("responses")
    class Responses {

        @BeforeEach
        void open() {
            state.opening();
        }

        @Test
        @DisplayName("a grant makes the editor editable")
        void grantOwnsIt() {
            EditLockState.Snapshot snapshot =
                    state.applyResponse(LockResponse.granted(QUESTION, MYSELF, EXPIRY));

            assertThat(snapshot.mode()).isEqualTo(EditLockState.Mode.OWNED);
            assertThat(snapshot.isEditable()).isTrue();
            assertThat(snapshot.bannerText("question")).isEmpty();
        }

        @Test
        @DisplayName("a refusal makes it read-only and names the holder")
        void refusalIsReadOnly() {
            EditLockState.Snapshot snapshot =
                    state.applyResponse(LockResponse.refused(QUESTION, RINA, EXPIRY));

            assertThat(snapshot.isReadOnly()).isTrue();
            assertThat(snapshot.isEditable()).isFalse();
            assertThat(snapshot.holderName()).contains(RINA);
            assertThat(snapshot.bannerText("question"))
                    .contains("Rina Barak is editing this question. It is read-only for you.");
        }

        @Test
        @DisplayName("a free answer to a reader offers a takeover as an opportunity")
        void freeAnswerToAReader() {
            state.applyResponse(LockResponse.refused(QUESTION, RINA, EXPIRY));

            EditLockState.Snapshot snapshot = state.applyResponse(LockResponse.free(QUESTION));

            assertThat(snapshot.offersTakeover()).isTrue();
            assertThat(snapshot.reason()).isEqualTo(TakeoverReason.AVAILABLE);
        }

        @Test
        @DisplayName("a free answer to the previous holder is a loss, and says so")
        void freeAnswerToTheFormerHolder() {
            state.applyResponse(LockResponse.granted(QUESTION, MYSELF, EXPIRY));

            EditLockState.Snapshot snapshot = state.applyResponse(LockResponse.free(QUESTION));

            assertThat(snapshot.offersTakeover()).isTrue();
            assertThat(snapshot.reason()).isEqualTo(TakeoverReason.LOST);
            assertThat(snapshot.bannerText("question"))
                    .contains(LockCopy.takeoverExplanation(TakeoverReason.LOST, "question"));
        }

        @Test
        @DisplayName("a failed request never leaves the screen claiming to hold the lock")
        void failureDropsTheClaim() {
            state.applyResponse(LockResponse.granted(QUESTION, MYSELF, EXPIRY));

            EditLockState.Snapshot snapshot = state.applyFailure();

            assertThat(snapshot.isEditable()).isFalse();
            assertThat(snapshot.reason()).isEqualTo(TakeoverReason.LOST);
        }

        @Test
        @DisplayName("a response is required")
        void responseRequired() {
            assertThatNullPointerException().isThrownBy(() -> state.applyResponse(null));
            assertThatNullPointerException().isThrownBy(() -> state.applyChange(null));
        }
    }

    @Nested
    @DisplayName("pushes")
    class Pushes {

        @BeforeEach
        void open() {
            state.opening();
        }

        @Test
        @DisplayName("somebody else taking it turns the editor read-only, live")
        void someoneElseAcquires() {
            state.applyResponse(LockResponse.granted(QUESTION, MYSELF, EXPIRY));

            EditLockState.Snapshot snapshot = state.applyChange(LockChange.acquired(QUESTION, RINA));

            assertThat(snapshot.isReadOnly()).isTrue();
            assertThat(snapshot.holderName()).contains(RINA);
        }

        @Test
        @DisplayName("an acquisition by this very user is recognised as mine, by id")
        void myOwnAcquisition() {
            EditLockState.Snapshot snapshot =
                    state.applyChange(LockChange.acquired(QUESTION, new LockHolder(ME, "Dana Cohen")));

            assertThat(snapshot.isEditable()).isTrue();
        }

        @Test
        @DisplayName("a namesake is still somebody else, because identity is the id")
        void aNamesakeIsNotMe() {
            EditLockState.Snapshot snapshot =
                    state.applyChange(LockChange.acquired(QUESTION, new LockHolder(9999L, "Dana Cohen")));

            assertThat(snapshot.isReadOnly()).isTrue();
        }

        @Test
        @DisplayName("a release reaching a reader offers the takeover, it never grabs")
        void releaseOffersTakeover() {
            state.applyResponse(LockResponse.refused(QUESTION, RINA, EXPIRY));

            EditLockState.Snapshot snapshot = state.applyChange(LockChange.released(QUESTION));

            assertThat(snapshot.offersTakeover()).isTrue();
            assertThat(snapshot.isEditable())
                    .as("silently starting to edit is the one thing the prompt exists to prevent")
                    .isFalse();
            assertThat(snapshot.reason()).isEqualTo(TakeoverReason.AVAILABLE);
        }

        @Test
        @DisplayName("an expiry reaching the holder is reported as their own loss")
        void expiryOfMyOwnLock() {
            state.applyResponse(LockResponse.granted(QUESTION, MYSELF, EXPIRY));

            EditLockState.Snapshot snapshot = state.applyChange(LockChange.expired(QUESTION));

            assertThat(snapshot.reason()).isEqualTo(TakeoverReason.LOST);
        }
    }

    @Nested
    @DisplayName("closing and declining")
    class Closing {

        @Test
        @DisplayName("closing returns to idle")
        void closeGoesIdle() {
            state.opening();
            state.applyResponse(LockResponse.granted(QUESTION, MYSELF, EXPIRY));

            assertThat(state.closed().mode()).isEqualTo(EditLockState.Mode.IDLE);
        }

        @Test
        @DisplayName("a late answer to a closed editor changes nothing")
        void lateAnswersAreIgnored() {
            state.opening();
            state.closed();

            assertThat(state.applyResponse(LockResponse.granted(QUESTION, MYSELF, EXPIRY)).mode())
                    .isEqualTo(EditLockState.Mode.IDLE);
            assertThat(state.applyChange(LockChange.acquired(QUESTION, RINA)).mode())
                    .isEqualTo(EditLockState.Mode.IDLE);
            assertThat(state.applyFailure().mode()).isEqualTo(EditLockState.Mode.IDLE);
        }

        @Test
        @DisplayName("declining a takeover leaves the screen read-only and stops asking")
        void decliningStopsAsking() {
            state.opening();
            state.applyResponse(LockResponse.free(QUESTION));

            EditLockState.Snapshot snapshot = state.declineTakeover();

            assertThat(snapshot.isReadOnly()).isTrue();
            assertThat(snapshot.offersTakeover()).isFalse();
            assertThat(snapshot.holderName())
                    .map(LockHolder::displayName)
                    .contains(LockHolder.UNKNOWN_NAME);
        }

        @Test
        @DisplayName("declining when nothing was offered changes nothing")
        void decliningNothing() {
            state.opening();
            state.applyResponse(LockResponse.granted(QUESTION, MYSELF, EXPIRY));

            assertThat(state.declineTakeover().isEditable()).isTrue();
        }
    }
}
