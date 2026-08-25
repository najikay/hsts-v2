package server.features.bank;

import common.dto.lock.EntityRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link QuestionLockKey} — the one place a question becomes a lock key (E6.14 ⚑).
 *
 * <p>Small, and load-bearing out of proportion to its size: every edit lock this feature takes is
 * keyed by what this class returns, and the lead's ruling of 2026-08-23 is that it must be the
 * display id rather than the primary key, so that after the legacy retirement there is exactly
 * one numbering scheme in play.
 *
 * <p>Moved here with the class it covers when the write-path consult gave the rule a second
 * caller (2026-08-24). Every one of {@code BankLocksTest}'s six cases arrived intact; the last
 * method is the one the move added.
 */
class QuestionLockKeyTest {

    @Test
    @DisplayName("a question is keyed by its display id, under the shared question type")
    void keyedByDisplayId() {
        EntityRef ref = QuestionLockKey.of("11005");

        assertThat(ref.entityId())
                .as("displayId5 is the only identifier the versioned bank's wire carries: "
                        + "BankQuestionRow and QuestionDetail have no primary key on them")
                .isEqualTo(11005L);
        assertThat(ref.entityType()).isEqualTo(EntityRef.QUESTION);
    }

    @Test
    @DisplayName("two different questions never share a key")
    void distinctQuestionsDistinctKeys() {
        assertThat(QuestionLockKey.of("11005")).isNotEqualTo(QuestionLockKey.of("11006"));
        assertThat(QuestionLockKey.of("11005")).isEqualTo(QuestionLockKey.of("11005"));
    }

    @Test
    @DisplayName("a leading zero survives, because a course code can start with one")
    void leadingZeroIsNotLost() {
        assertThat(QuestionLockKey.of("01003").entityId())
                .as("course 01 serial 003. Parsed as a number the leading zero goes, and that is "
                        + "fine precisely because it goes the same way every time: the mapping "
                        + "only has to be injective, not reversible.")
                .isEqualTo(1003L);
        assertThat(QuestionLockKey.of("01003")).isNotEqualTo(QuestionLockKey.of("10030"));
    }

    @Test
    @DisplayName("surrounding whitespace is stripped rather than breaking the parse")
    void stripsBeforeParsing() {
        assertThat(QuestionLockKey.of("  11005 ").entityId()).isEqualTo(11005L);
    }

    @Test
    @DisplayName("a non-numeric id is loud, because every lock on the screen would key on nothing")
    void nonNumericIsRefused() {
        assertThatThrownBy(() -> QuestionLockKey.of("11-05"))
                .as("the ids come from the server, so this means the wire disagrees with S-8. "
                        + "Swallowing it would leave two teachers editing one question with no "
                        + "lock between them and no error anywhere.")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("11-05");
    }

    @Test
    @DisplayName("a missing id is refused rather than keyed on zero")
    void blankIsRefused() {
        assertThatThrownBy(() -> QuestionLockKey.of(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuestionLockKey.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an id of the wrong width is refused, which is the @throws clause and no more")
    void wrongWidthIsRefused() {
        // Deliberately NOT claiming this carves out a key space of its own. It does not:
        // leadingZeroIsNotLost above keys 01003 as question#1003, which any auto-increment
        // column reaches. That overlap mattered while the legacy screen keyed the same
        // EntityRef.QUESTION by primary key, and what kept the two apart was that screen taking
        // no lock at all - never this width check. The retirement PR deleted the screen, so
        // displayId5 is the sole scheme and there is nothing left to separate from; the point of
        // this comment is that the check must not be mistaken for a licence to key something
        // else through here.
        assertThatThrownBy(() -> QuestionLockKey.of("7"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 digits");
        assertThatThrownBy(() -> QuestionLockKey.of("110050"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===================== the inverse, E18.9 =============================

    @Test
    @DisplayName("the key round-trips back to the display id it was made from")
    void theInverseRoundTrips() {
        for (String displayId : new String[] {"11005", "01003", "00001", "99999"}) {
            assertThat(QuestionLockKey.displayIdOf(QuestionLockKey.of(displayId).entityId()))
                    .as("round trip of %s", displayId)
                    .isEqualTo(displayId);
        }
    }

    @Test
    @DisplayName("the inverse zero-pads, which is the whole of what it has to get right")
    void theInverseZeroPads() {
        // Long.toString would answer "1003" here, which matches no row in
        // uq_questions_display_id. The consequence is invisible rather than loud: the question
        // scope that calls this would resolve nothing, read it as "you do not reach it", and
        // silently hide every lock on every course whose code starts with a zero.
        assertThat(QuestionLockKey.displayIdOf(1003L)).isEqualTo("01003");
        assertThat(QuestionLockKey.displayIdOf(1L)).isEqualTo("00001");
        assertThat(QuestionLockKey.displayIdOf(11005L)).isEqualTo("11005");
    }

    @Test
    @DisplayName("an id no display id could hold is refused rather than padded into a lie")
    void theInverseRefusesImpossibleIds() {
        // The lock map is keyed by a raw long off the wire, so this is reachable input.
        assertThatThrownBy(() -> QuestionLockKey.displayIdOf(100_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5-digit");
        assertThatThrownBy(() -> QuestionLockKey.displayIdOf(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
