package server.features.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paragraph-aligned chunking (E16.5).
 *
 * <p>The property that matters is not "chunks are 500 characters" — it is that a
 * definition and its example stay together, because a split one retrieves worse
 * than either half would on its own.
 */
class ChunkerTest {

    @Test
    @DisplayName("empty text produces no chunks rather than one empty one")
    void emptyText() {
        assertThat(Chunker.chunk(null)).isEmpty();
        assertThat(Chunker.chunk("   ")).isEmpty();
    }

    @Test
    @DisplayName("a short document is one chunk")
    void shortDocument() {
        List<String> chunks = Chunker.chunk("A foreign key points at another table's primary key.");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).startsWith("A foreign key");
    }

    @Test
    @DisplayName("short paragraphs are packed together up to the target")
    void packsParagraphs() {
        String text = String.join("\n\n", "One.", "Two.", "Three.");

        List<String> chunks = Chunker.chunk(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("One.\n\nTwo.\n\nThree.");
    }

    @Test
    @DisplayName("packing stops before the target rather than after it")
    void startsANewChunkBeforeOvershooting() {
        String paragraph = "x".repeat(300);
        String text = paragraph + "\n\n" + paragraph;

        List<String> chunks = Chunker.chunk(text);

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.length()).isLessThanOrEqualTo(Chunker.TARGET_CHARACTERS));
    }

    @Test
    @DisplayName("a paragraph within the ceiling is never cut, even over the target")
    void keepsALongishParagraphWhole() {
        String paragraph = "y".repeat(Chunker.TARGET_CHARACTERS + 200);

        List<String> chunks = Chunker.chunk(paragraph);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).hasSize(Chunker.TARGET_CHARACTERS + 200);
    }

    @Test
    @DisplayName("a paragraph over the ceiling is divided")
    void dividesAWallOfText() {
        String paragraph = "z".repeat(Chunker.MAX_CHARACTERS + 500);

        List<String> chunks = Chunker.chunk(paragraph);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(String.join("", chunks)).hasSize(Chunker.MAX_CHARACTERS + 500);
    }

    @Test
    @DisplayName("an over-long paragraph is cut at a sentence end when there is one nearby")
    void cutsAtASentenceEnd() {
        String sentence = "This sentence is exactly the sort of thing a handout is made of. ";
        String paragraph = sentence.repeat(30);

        List<String> chunks = Chunker.chunk(paragraph);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0)).endsWith(".");
    }

    @Test
    @DisplayName("blank paragraphs between real ones are dropped, not chunked")
    void ignoresBlankParagraphs() {
        List<String> chunks = Chunker.chunk("One.\n\n   \n\nTwo.");

        assertThat(chunks).containsExactly("One.\n\nTwo.");
    }

    @Test
    @DisplayName("chunking is deterministic, which is what lets context selection be pinned")
    void deterministic() {
        String text = ("Paragraph about joins.\n\n" + "Paragraph about keys.\n\n").repeat(20);

        assertThat(Chunker.chunk(text)).isEqualTo(Chunker.chunk(text));
    }
}
