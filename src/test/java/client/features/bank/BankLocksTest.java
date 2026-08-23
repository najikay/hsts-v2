package client.features.bank;

import common.dto.lock.EntityRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BankLocks} — the one place a question becomes a lock key (E6.14 ⚑).
 *
 * <p>Small, and load-bearing out of proportion to its size: every edit lock this feature takes is
 * keyed by what this class returns, and the lead's ruling of 2026-08-23 is that it must be the
 * display id rather than the primary key, so that after the legacy retirement there is exactly
 * one numbering scheme in play.
 */
class BankLocksTest {

    @Test
    @DisplayName("a question is keyed by its display id, under the shared question type")
    void keyedByDisplayId() {
        EntityRef ref = BankLocks.of("11005");

        assertThat(ref.entityId())
                .as("displayId5 is the only identifier the versioned bank's wire carries: "
                        + "BankQuestionRow and QuestionDetail have no primary key on them")
                .isEqualTo(11005L);
        assertThat(ref.entityType()).isEqualTo(EntityRef.QUESTION);
    }

    @Test
    @DisplayName("two different questions never share a key")
    void distinctQuestionsDistinctKeys() {
        assertThat(BankLocks.of("11005")).isNotEqualTo(BankLocks.of("11006"));
        assertThat(BankLocks.of("11005")).isEqualTo(BankLocks.of("11005"));
    }

    @Test
    @DisplayName("a leading zero survives, because a course code can start with one")
    void leadingZeroIsNotLost() {
        assertThat(BankLocks.of("01003").entityId())
                .as("course 01 serial 003. Parsed as a number the leading zero goes, and that is "
                        + "fine precisely because it goes the same way every time: the mapping "
                        + "only has to be injective, not reversible.")
                .isEqualTo(1003L);
        assertThat(BankLocks.of("01003")).isNotEqualTo(BankLocks.of("10030"));
    }

    @Test
    @DisplayName("surrounding whitespace is stripped rather than breaking the parse")
    void stripsBeforeParsing() {
        assertThat(BankLocks.of("  11005 ").entityId()).isEqualTo(11005L);
    }

    @Test
    @DisplayName("a non-numeric id is loud, because every lock on the screen would key on nothing")
    void nonNumericIsRefused() {
        assertThatThrownBy(() -> BankLocks.of("11-05"))
                .as("the ids come from the server, so this means the wire disagrees with S-8. "
                        + "Swallowing it would leave two teachers editing one question with no "
                        + "lock between them and no error anywhere.")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("11-05");
    }

    @Test
    @DisplayName("a missing id is refused rather than keyed on zero")
    void blankIsRefused() {
        assertThatThrownBy(() -> BankLocks.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BankLocks.of("   ")).isInstanceOf(IllegalArgumentException.class);
    }
}
