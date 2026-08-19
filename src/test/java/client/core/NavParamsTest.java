package client.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link NavParams} — the typed, immutable navigation parameter bag (E4.2). */
class NavParamsTest {

    @Test
    void emptyBagIsEmpty() {
        assertThat(NavParams.empty().isEmpty()).isTrue();
        assertThat(NavParams.empty().size()).isZero();
        assertThat(NavParams.empty().keys()).isEmpty();
    }

    @Test
    void singleAndPairFactories() {
        assertThat(NavParams.of("a", 1).getInt("a", -1)).isEqualTo(1);

        NavParams pair = NavParams.of("a", 1, "b", "two");
        assertThat(pair.size()).isEqualTo(2);
        assertThat(pair.getString("b", "")).isEqualTo("two");
    }

    @Test
    void copyOfTakesASnapshotOfTheSourceMap() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", 5);
        NavParams params = NavParams.copyOf(source);

        source.put("id", 99);
        source.put("extra", true);

        assertThat(params.getInt("id", -1)).isEqualTo(5);
        assertThat(params.containsKey("extra")).isFalse();
    }

    @Test
    void copyOfHandlesNullAndEmpty() {
        assertThat(NavParams.copyOf(null)).isEqualTo(NavParams.empty());
        assertThat(NavParams.copyOf(Map.of())).isEqualTo(NavParams.empty());
    }

    @Test
    void withReturnsANewBagAndLeavesTheOriginalAlone() {
        NavParams original = NavParams.of("a", 1);
        NavParams extended = original.with("b", 2);

        assertThat(original.size()).isEqualTo(1);
        assertThat(extended.size()).isEqualTo(2);
        assertThat(extended).isNotSameAs(original);
    }

    @Test
    void withReplacesAnExistingKey() {
        assertThat(NavParams.of("a", 1).with("a", 2).getInt("a", -1)).isEqualTo(2);
    }

    @Test
    void withoutRemovesAKeyAndIsANoOpWhenAbsent() {
        NavParams params = NavParams.of("a", 1, "b", 2);

        assertThat(params.without("a").containsKey("a")).isFalse();
        assertThat(params.without("zzz")).isSameAs(params);
    }

    @Test
    void typedGetReturnsTheValue() {
        NavParams params = NavParams.of("name", "Dana");
        assertThat(params.get("name", String.class)).contains("Dana");
    }

    @Test
    void typedGetIsEmptyForAMissingKey() {
        assertThat(NavParams.empty().get("nope", String.class)).isEmpty();
    }

    @Test
    void typedGetFailsLoudlyOnATypeMismatch() {
        NavParams params = NavParams.of("id", "not-a-number");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> params.get("id", Integer.class))
                .withMessageContaining("id")
                .withMessageContaining("Integer");
    }

    @Test
    void requireReturnsThePresentValue() {
        assertThat(NavParams.of("id", 7L).require("id", Long.class)).isEqualTo(7L);
    }

    @Test
    void requireFailsWhenTheKeyIsAbsent() {
        assertThatThrownBy(() -> NavParams.empty().require("examId", Integer.class))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("examId");
    }

    @Test
    void primitiveAccessorsFallBackWhenAbsent() {
        NavParams empty = NavParams.empty();

        assertThat(empty.getString("s", "default")).isEqualTo("default");
        assertThat(empty.getInt("i", 42)).isEqualTo(42);
        assertThat(empty.getLong("l", 42L)).isEqualTo(42L);
        assertThat(empty.getBoolean("b", true)).isTrue();
    }

    @Test
    void primitiveAccessorsReadPresentValues() {
        NavParams params = NavParams.empty()
                .with("s", "x").with("i", 1).with("l", 2L).with("b", false);

        assertThat(params.getString("s", "")).isEqualTo("x");
        assertThat(params.getInt("i", -1)).isEqualTo(1);
        assertThat(params.getLong("l", -1L)).isEqualTo(2L);
        assertThat(params.getBoolean("b", true)).isFalse();
    }

    @Test
    void asMapIsUnmodifiable() {
        Map<String, Object> view = NavParams.of("a", 1).asMap();
        assertThatThrownBy(() -> view.put("b", 2)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void valueSemantics() {
        assertThat(NavParams.of("a", 1)).isEqualTo(NavParams.of("a", 1));
        assertThat(NavParams.of("a", 1)).hasSameHashCodeAs(NavParams.of("a", 1));
        assertThat(NavParams.of("a", 1)).isNotEqualTo(NavParams.of("a", 2));
        assertThat(NavParams.of("a", 1)).isNotEqualTo("not params");
        assertThat(NavParams.of("a", 1)).hasToString("NavParams{a=1}");
    }

    @Test
    void rejectsNullKeysAndTypes() {
        assertThatThrownBy(() -> NavParams.empty().with(null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NavParams.empty().get("a", null))
                .isInstanceOf(NullPointerException.class);
    }
}
