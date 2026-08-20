package server.features.bot;

import common.dto.bot.BotSourceKind;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Uploaded bytes in, readable text out (Logic tier, E16.5 — F12.2).
 *
 * <p>Three formats, three libraries, one contract: hand it a kind and some bytes
 * and it either returns text worth putting in a prompt or throws a
 * {@link SourceExtractionException} whose message the uploader can act on. There
 * is deliberately no third outcome — no "empty but successful" — because
 * {@code bot_sources} makes both its text columns NOT NULL precisely so a source
 * that contributes nothing to a prompt cannot exist as a row.
 *
 * <h2>Why the messages are written the way they are</h2>
 *
 * <p>Every failure message names the file kind, says what went wrong in ordinary
 * words, and says what to do next (PRD §4.1). "Could not read this PDF" is half a
 * message; "This PDF has no text in it. It may be a scan. Try a PDF with
 * selectable text, or paste the text as a free-text source." is the whole one,
 * and the scanned-textbook case is the failure a teacher is most likely to hit.
 *
 * <h2>A note on the scanned-PDF case</h2>
 *
 * <p>A PDF of photographed pages parses perfectly and yields nothing, because
 * there is no text layer to find. That is not an error PDFBox reports — it is a
 * successful extraction of zero characters — so it is checked for here explicitly
 * rather than left to surface later as a bot that answers "I do not have that in
 * my material" about a book its teacher believes she uploaded.
 */
public final class SourceExtractor {

    private static final Logger log = LoggerFactory.getLogger(SourceExtractor.class);

    /**
     * The shortest extraction worth storing.
     *
     * <p>Not zero: a PDF that yields a page number and nothing else has failed in
     * every sense the teacher cares about, and telling her now is better than
     * letting her find out from a student.
     */
    public static final int MIN_USEFUL_CHARACTERS = 20;

    /**
     * Extracts the text of one uploaded source.
     *
     * @param kind  what the bytes are
     * @param bytes the upload
     * @return normalised text, never blank
     * @throws SourceExtractionException when the bytes cannot be read, or read to
     *                                   nothing useful; the message is for the uploader
     */
    public String extract(BotSourceKind kind, byte[] bytes) throws SourceExtractionException {
        Objects.requireNonNull(kind, "kind");
        if (bytes == null || bytes.length == 0) {
            throw new SourceExtractionException(
                    "There was nothing to read in that upload. Choose the file again, "
                            + "or paste the text as a free text source.");
        }
        String raw = switch (kind) {
            case PDF -> fromPdf(bytes);
            case DOCX -> fromDocx(bytes);
            case TEXT -> new String(bytes, StandardCharsets.UTF_8);
        };
        String normalised = TextNormaliser.normalise(raw);
        if (normalised.length() < MIN_USEFUL_CHARACTERS) {
            throw new SourceExtractionException(emptyMessageFor(kind));
        }
        log.debug("Extracted {} characters from a {} source", normalised.length(), kind);
        return normalised;
    }

    private static String fromPdf(byte[] bytes) throws SourceExtractionException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            // Reading order rather than drawing order: a two-column handout
            // extracted in drawing order interleaves the columns line by line and
            // produces text that is technically present and practically unusable.
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (Exception e) {
            // Deliberately broad: PDFBox surfaces a corrupt or encrypted file as
            // several unrelated exception types, and every one of them means the
            // same thing to the teacher who is waiting.
            log.info("PDF extraction failed: {}", e.toString());
            throw new SourceExtractionException(
                    "This PDF could not be read. It may be damaged or password protected. "
                            + "Try saving it again from the original program, or paste the text "
                            + "as a free text source.", e);
        }
    }

    private static String fromDocx(byte[] bytes) throws SourceExtractionException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (Exception e) {
            log.info("DOCX extraction failed: {}", e.toString());
            throw new SourceExtractionException(
                    "This Word file could not be read. Older .doc files are not supported. "
                            + "Save it as .docx and upload it again, or paste the text as a "
                            + "free text source.", e);
        }
    }

    /** The "parsed fine, contained nothing" message, per kind. */
    private static String emptyMessageFor(BotSourceKind kind) {
        return switch (kind) {
            case PDF -> "This PDF has no text in it. It may be a scan of printed pages. "
                    + "Upload a PDF with selectable text, or paste the text as a free text source.";
            case DOCX -> "This Word file has no text in it. Check that you uploaded the right "
                    + "file, or paste the text as a free text source.";
            case TEXT -> "This source is too short to be useful. Paste at least a paragraph "
                    + "of course material.";
        };
    }
}
