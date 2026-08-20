package server.features.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two folds this feature relies on (E16.5/E16.10).
 *
 * <p>They are tested together because the risk is that they drift into each
 * other's job: normalising must keep the meaning (a model reads it) and grouping
 * must lose quite a lot of it (it is only ever a key).
 */
class TextNormaliserTest {

    @Nested
    @DisplayName("normalising extracted material")
    class Normalise {

        @Test
        @DisplayName("null and empty are empty rather than an exception")
        void emptyInput() {
            assertThat(TextNormaliser.normalise(null)).isEmpty();
            assertThat(TextNormaliser.normalise("")).isEmpty();
        }

        @Test
        @DisplayName("Windows and old Mac line endings become one kind")
        void lineEndings() {
            assertThat(TextNormaliser.normalise("a\r\nb\rc")).isEqualTo("a\nb\nc");
        }

        @Test
        @DisplayName("paragraph structure survives, because the chunker is what reads it")
        void keepsParagraphs() {
            String text = TextNormaliser.normalise("First idea.\n\nSecond idea.");

            assertThat(text).isEqualTo("First idea.\n\nSecond idea.");
        }

        @Test
        @DisplayName("a run of blank lines collapses to exactly one paragraph break")
        void collapsesBlankLines() {
            assertThat(TextNormaliser.normalise("a\n\n\n\n\nb")).isEqualTo("a\n\nb");
        }

        @Test
        @DisplayName("non-breaking spaces, which PDFs are full of, become ordinary spaces")
        void exoticSpaces() {
            String text = TextNormaliser.normalise("a\u00A0b\u202Fc");

            assertThat(text).isEqualTo("a b c");
        }

        @Test
        @DisplayName("a form feed is a page break, so it becomes a line break")
        void formFeed() {
            assertThat(TextNormaliser.normalise("page one\fpage two")).contains("page one\npage two");
        }

        @Test
        @DisplayName("control characters are dropped, tabs become spaces")
        void controlCharacters() {
            String text = TextNormaliser.normalise("a\007b\tc");

            assertThat(text).isEqualTo("ab c");
        }

        @Test
        @DisplayName("runs of spaces collapse and the whole thing is trimmed")
        void collapsesSpaces() {
            assertThat(TextNormaliser.normalise("   a     b   ")).isEqualTo("a b");
        }

        @Test
        @DisplayName("Hebrew survives untouched, which is the point of X-I18N")
        void hebrewSurvives() {
            assertThat(TextNormaliser.normalise("מה זה מפתח זר?")).isEqualTo("מה זה מפתח זר?");
        }
    }

    @Nested
    @DisplayName("grouping questions for the S-34 aggregate")
    class GroupingKey {

        @Test
        @DisplayName("case, spacing and trailing punctuation all fold into one key")
        void foldsSpellings() {
            String canonical = TextNormaliser.groupingKey("what is a foreign key");

            assertThat(TextNormaliser.groupingKey("What is a foreign key?")).isEqualTo(canonical);
            assertThat(TextNormaliser.groupingKey("  What   Is A Foreign Key ??  "))
                    .isEqualTo(canonical);
        }

        @Test
        @DisplayName("punctuation inside the question is kept, because it can be the question")
        void keepsInternalPunctuation() {
            assertThat(TextNormaliser.groupingKey("what does a[i] mean?"))
                    .isEqualTo("what does a[i] mean");
        }

        @Test
        @DisplayName("two genuinely different questions stay different")
        void doesNotOvermerge() {
            assertThat(TextNormaliser.groupingKey("what is a foreign key"))
                    .isNotEqualTo(TextNormaliser.groupingKey("what is a primary key"));
        }

        @Test
        @DisplayName("null and blank produce an empty key, which the fold then ignores")
        void emptyKeys() {
            assertThat(TextNormaliser.groupingKey(null)).isEmpty();
            assertThat(TextNormaliser.groupingKey("   ")).isEmpty();
            assertThat(TextNormaliser.groupingKey("???")).isEmpty();
        }
    }
}
