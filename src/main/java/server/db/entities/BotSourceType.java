package server.db.entities;

/**
 * Kind of study-bot source material — {@code bot_sources.type} (V6, §5, F12.2).
 *
 * <p>Every source keeps both its original bytes and the text extracted from them, and
 * both are NOT NULL: a row only exists after a successful parse, because F12.2 reports
 * parse failures immediately rather than storing a half-source.
 *
 * <p>For {@link #TEXT} that means the pasted text is stored as the raw bytes too —
 * confirmed by the lead in the E2 PR 1 review, rather than making {@code raw} nullable
 * for one type.
 */
public enum BotSourceType {

    /** Uploaded PDF, parsed server-side with PDFBox. */
    PDF,

    /** Uploaded Word document, parsed server-side with POI. */
    DOCX,

    /** Free text typed by the teacher; stored verbatim as both raw and extracted. */
    TEXT
}
