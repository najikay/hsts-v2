package server.features.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ExecutionCodes} — E9.1 (C-1, S-16, S-17). */
class ExecutionCodesTest {

    @Test
    @DisplayName("a code is four characters of the spoken-safe alphabet")
    void shapeOfACode() {
        String code = ExecutionCodes.roll(new Random(1));

        assertThat(code).hasSize(ExecutionCodes.LENGTH);
        assertThat(code.chars())
                .allSatisfy(character ->
                        assertThat(ExecutionCodes.ALPHABET.indexOf(character)).isNotNegative());
    }

    @Test
    @DisplayName("⚑ the alphabet has no character anyone mishears: no O, no 0, no I, no 1")
    void noAmbiguousCharacters() {
        // The whole reason the alphabet is narrower than [A-Z0-9]: this code is read out
        // loud to a room (S-17), and "oh or zero?" costs a student minutes she cannot get
        // back.
        assertThat(ExecutionCodes.ALPHABET)
                .doesNotContain("O").doesNotContain("0")
                .doesNotContain("I").doesNotContain("1");
        assertThat(ExecutionCodes.ALPHABET).hasSize(32);
    }

    @Test
    @DisplayName("generation asks whether a candidate is free, and re-rolls when it is not")
    void rerollsOnCollision() {
        String firstRoll = ExecutionCodes.roll(new Random(5));
        Set<String> taken = new HashSet<>(Set.of(firstRoll));

        String generated = ExecutionCodes.generate(new Random(5), taken::contains);

        assertThat(generated).isNotEqualTo(firstRoll);
    }

    @Test
    @DisplayName("a free first roll is used as it is, with no needless second one")
    void takesTheFirstFreeRoll() {
        String expected = ExecutionCodes.roll(new Random(5));

        assertThat(ExecutionCodes.generate(new Random(5), candidate -> false))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("a world where every code is taken ends in an exception the service can word")
    void givesUpEventually() {
        assertThatThrownBy(() -> ExecutionCodes.generate(new Random(5), candidate -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(ExecutionCodes.MAX_ATTEMPTS));
    }

    @Test
    @DisplayName("codes vary: a hundred rolls are not one code repeated")
    void codesVary() {
        Set<String> codes = new HashSet<>();
        Random random = new Random(11);
        for (int roll = 0; roll < 100; roll++) {
            codes.add(ExecutionCodes.roll(random));
        }

        assertThat(codes).hasSizeGreaterThan(90);
    }

    @ParameterizedTest
    @ValueSource(strings = {"4B7Q", "1234", "abcd", "aB3d"})
    @DisplayName("the accepted shape is the contract's wide one, digits included (C-1)")
    void wellFormedCodes(String code) {
        // Deliberately wider than the generator's alphabet: the seed and the demo both carry
        // all-digit codes, and a rule that rejected them would be this class's taste
        // overriding the contract.
        assertThat(ExecutionCodes.isWellFormed(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ABC", "ABCDE", "AB C", "AB-1", "אבגד"})
    @DisplayName("anything else is not a code")
    void malformedCodes(String code) {
        assertThat(ExecutionCodes.isWellFormed(code)).isFalse();
    }

    @Test
    @DisplayName("a null code is not well formed, rather than an exception")
    void nullIsNotACode() {
        assertThat(ExecutionCodes.isWellFormed(null)).isFalse();
    }

    @Test
    @DisplayName("codes normalise to trimmed upper case, because students type them (C-1)")
    void normalisation() {
        assertThat(ExecutionCodes.normalize("  4b7q ")).isEqualTo("4B7Q");
        assertThat(ExecutionCodes.normalize(null)).isEmpty();
    }
}
