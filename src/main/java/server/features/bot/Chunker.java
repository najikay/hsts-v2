package server.features.bot;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a source's text into retrievable pieces (Logic tier, E16.5 — F12.2).
 *
 * <p>A whole textbook cannot go in a prompt, and a whole textbook is not what a
 * question needs. Chunking is what lets {@link ContextBuilder} put the three
 * paragraphs that are about the question in front of the model instead of the
 * forty pages they live in.
 *
 * <h2>Paragraph-aligned, not fixed-width</h2>
 *
 * <p>The target is {@link #TARGET_CHARACTERS}, but the boundary is a paragraph.
 * Cutting at exactly 500 characters would routinely split a definition from its
 * example, and then neither half scores well enough to be selected — the
 * retrieval gets worse in a way that is invisible until a student asks about the
 * thing that was split. So paragraphs are packed until adding the next one would
 * overshoot, and only a paragraph that is longer than
 * {@link #MAX_CHARACTERS} on its own is cut, at a sentence end where possible.
 *
 * <p>The whole class is deterministic and depends on nothing: same text in, same
 * chunks out, which is what lets {@code ChunkerTest} pin the boundaries by
 * example rather than by property.
 */
public final class Chunker {

    /** What a chunk aims to be: a few paragraphs, roughly a screenful. */
    public static final int TARGET_CHARACTERS = 500;

    /**
     * The hard ceiling before a single paragraph is cut.
     *
     * <p>Twice the target, so a slightly long paragraph stays whole (which is the
     * common case and the one worth protecting) while a wall of text that is one
     * paragraph in name only still gets divided.
     */
    public static final int MAX_CHARACTERS = 1000;

    private Chunker() {
        // static helper - no instances
    }

    /**
     * @param text normalised source text (see {@link TextNormaliser#normalise})
     * @return its chunks in document order; empty for empty text
     */
    public static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\n\\s*\n")) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > MAX_CHARACTERS) {
                flush(chunks, current);
                chunks.addAll(split(trimmed));
                continue;
            }
            if (current.length() > 0 && current.length() + trimmed.length() + 2 > TARGET_CHARACTERS) {
                flush(chunks, current);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(trimmed);
        }
        flush(chunks, current);
        return List.copyOf(chunks);
    }

    /**
     * Divides one over-long paragraph.
     *
     * <p>At a sentence end when there is one in the last quarter of the window,
     * and at the window otherwise. The quarter is the compromise: searching the
     * whole window for a full stop would produce wildly uneven pieces on text with
     * many short sentences, and not searching at all would cut mid-sentence in
     * text that had a perfectly good boundary two characters earlier.
     */
    private static List<String> split(String paragraph) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < paragraph.length()) {
            int end = Math.min(start + TARGET_CHARACTERS, paragraph.length());
            if (end < paragraph.length()) {
                int boundary = lastSentenceEnd(paragraph, start + (TARGET_CHARACTERS * 3 / 4), end);
                if (boundary > start) {
                    end = boundary;
                }
            }
            String piece = paragraph.substring(start, end).trim();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            start = end;
        }
        return pieces;
    }

    /** @return the index just after the last sentence end in {@code [from, to)}, or -1. */
    private static int lastSentenceEnd(String text, int from, int to) {
        for (int i = to - 1; i >= from && i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                return i + 1;
            }
        }
        return -1;
    }

    private static void flush(List<String> chunks, StringBuilder current) {
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
            current.setLength(0);
        }
    }
}
