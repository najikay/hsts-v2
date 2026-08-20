package server.features.bot;

import common.dto.bot.BotSourceKind;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Turning uploads into text, and telling the uploader when that fails (E16.5 —
 * F12.2).
 *
 * <p>The fixtures are built rather than checked in: a PDF written by PDFBox and a
 * DOCX written by POI, in this test, from text this test also asserts on. A
 * binary fixture on disk would be a file nobody can review in a diff and nobody
 * can regenerate when the library moves.
 */
class SourceExtractorTest {

    private final SourceExtractor extractor = new SourceExtractor();

    /** @return a real single-page PDF with the given lines of text. */
    private static byte[] pdf(String... lines) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setLeading(16f);
                content.newLineAtOffset(50, 700);
                for (String line : lines) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    /** @return a real .docx with the given paragraphs. */
    private static byte[] docx(String... paragraphs) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String paragraph : paragraphs) {
                document.createParagraph().createRun().setText(paragraph);
            }
            document.write(out);
            return out.toByteArray();
        }
    }

    /** @return a PDF with a page and no text on it, which is what a scan looks like. */
    private static byte[] emptyPdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("a PDF with text extracts and normalises")
    void extractsPdf() throws Exception {
        byte[] bytes = pdf("A foreign key points at another table's primary key.",
                "Referential integrity is what it guarantees.");

        String text = extractor.extract(BotSourceKind.PDF, bytes);

        assertThat(text).contains("foreign key").contains("Referential integrity");
        assertThat(text).doesNotContain("\r");
    }

    @Test
    @DisplayName("a Word file extracts")
    void extractsDocx() throws Exception {
        byte[] bytes = docx("Quicksort partitions an array around a pivot.",
                "Mergesort divides and merges.");

        String text = extractor.extract(BotSourceKind.DOCX, bytes);

        assertThat(text).contains("Quicksort").contains("Mergesort");
    }

    @Test
    @DisplayName("free text passes through, normalised")
    void passesTextThrough() throws Exception {
        byte[] bytes = "First idea.\r\n\r\n\r\n\r\nSecond idea.  ".getBytes(StandardCharsets.UTF_8);

        String text = extractor.extract(BotSourceKind.TEXT, bytes);

        assertThat(text).isEqualTo("First idea.\n\nSecond idea.");
    }

    @Test
    @DisplayName("Hebrew free text survives, which X-I18N requires")
    void hebrewText() throws Exception {
        byte[] bytes = "מפתח זר מצביע על מפתח ראשי בטבלה אחרת.".getBytes(StandardCharsets.UTF_8);

        assertThat(extractor.extract(BotSourceKind.TEXT, bytes))
                .isEqualTo("מפתח זר מצביע על מפתח ראשי בטבלה אחרת.");
    }

    @Test
    @DisplayName("an empty upload is refused with a sentence the uploader can act on")
    void emptyUpload() {
        assertThatThrownBy(() -> extractor.extract(BotSourceKind.PDF, new byte[0]))
                .isInstanceOf(SourceExtractionException.class)
                .hasMessageContaining("Choose the file again");
        assertThatThrownBy(() -> extractor.extract(BotSourceKind.TEXT, null))
                .isInstanceOf(SourceExtractionException.class);
    }

    @Test
    @DisplayName("a corrupt PDF is refused, and the message says what to try instead")
    void corruptPdf() {
        byte[] notAPdf = "%PDF-1.4 this is not really a pdf at all".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(BotSourceKind.PDF, notAPdf))
                .isInstanceOf(SourceExtractionException.class)
                .hasMessageContaining("could not be read")
                .hasMessageContaining("free text source");
    }

    @Test
    @DisplayName("a scanned PDF parses fine and yields nothing, and that gets its own message")
    void scannedPdf() throws Exception {
        byte[] bytes = emptyPdf();

        assertThatThrownBy(() -> extractor.extract(BotSourceKind.PDF, bytes))
                .isInstanceOf(SourceExtractionException.class)
                .hasMessageContaining("scan")
                .hasMessageContaining("selectable text");
    }

    @Test
    @DisplayName("an old .doc uploaded as .docx is refused with the right advice")
    void legacyDoc() {
        byte[] notADocx = new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 1, 2, 3, 4};

        assertThatThrownBy(() -> extractor.extract(BotSourceKind.DOCX, notADocx))
                .isInstanceOf(SourceExtractionException.class)
                .hasMessageContaining("Save it as .docx");
    }

    @Test
    @DisplayName("a Word file with nothing in it gets the empty message rather than the corrupt one")
    void emptyDocx() throws Exception {
        byte[] bytes = docx("   ");

        assertThatThrownBy(() -> extractor.extract(BotSourceKind.DOCX, bytes))
                .isInstanceOf(SourceExtractionException.class)
                .hasMessageContaining("no text in it");
    }

    @Test
    @DisplayName("text too short to be useful is refused rather than stored as a source that does nothing")
    void tooShort() {
        byte[] bytes = "hi".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(BotSourceKind.TEXT, bytes))
                .isInstanceOf(SourceExtractionException.class)
                .hasMessageContaining("at least a paragraph");
    }

    @Test
    @DisplayName("every refusal obeys the copy rules")
    void refusalsObeyTheCopyRules() throws Exception {
        List<byte[]> broken = List.of(
                "%PDF-1.4 broken".getBytes(StandardCharsets.UTF_8),
                emptyPdf());

        for (byte[] bytes : broken) {
            try {
                extractor.extract(BotSourceKind.PDF, bytes);
            } catch (SourceExtractionException e) {
                assertThat(e.getMessage()).doesNotContain("—");
                assertThat(e.getMessage().trim()).endsWith(".");
            }
        }
    }

    @Test
    @DisplayName("a file name decides the kind, and anything unfamiliar is offered as text")
    void kindFromFileName() {
        assertThat(BotSourceKind.ofFileName("week3.pdf")).isEqualTo(BotSourceKind.PDF);
        assertThat(BotSourceKind.ofFileName("WEEK3.PDF")).isEqualTo(BotSourceKind.PDF);
        assertThat(BotSourceKind.ofFileName("notes.docx")).isEqualTo(BotSourceKind.DOCX);
        assertThat(BotSourceKind.ofFileName("notes.doc")).isEqualTo(BotSourceKind.DOCX);
        assertThat(BotSourceKind.ofFileName("notes.txt")).isEqualTo(BotSourceKind.TEXT);
        assertThat(BotSourceKind.ofFileName(null)).isEqualTo(BotSourceKind.TEXT);
        assertThat(BotSourceKind.ofFileName("mystery.xyz")).isEqualTo(BotSourceKind.TEXT);
    }

    @Test
    @DisplayName("each kind has a human label for the sources table")
    void labels() {
        assertThat(BotSourceKind.PDF.label()).isEqualTo("PDF file");
        assertThat(BotSourceKind.DOCX.label()).isEqualTo("Word file");
        assertThat(BotSourceKind.TEXT.label()).isEqualTo("Free text");
    }
}
