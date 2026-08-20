package common.dto.bot;

/**
 * What kind of material a study-bot source is, on the wire (Common tier, E16.11 —
 * F12.2).
 *
 * <p>Deliberately a separate enum from the server's {@code BotSourceType} entity
 * enum even though the three constants match today. The entity enum is a column
 * definition and the migration owns it; this one is the wire contract and the two
 * JARs ship separately. Keeping them apart means a schema change is a mapping
 * change in one place rather than a silent wire break, which is the same reason
 * {@code common.dto.auth.Role} is not {@code server.db.entities.UserRole}.
 *
 * <p>The three are exactly what S-28 allows a teacher to upload. The course
 * question bank is the fourth source of material, and it is deliberately
 * <b>not</b> here: it is not something anybody uploads, it is read from the bank
 * at prompt time.
 */
public enum BotSourceKind {

    /** A PDF the teacher uploaded; parsed server-side with PDFBox. */
    PDF,

    /** A Word document; parsed server-side with Apache POI. */
    DOCX,

    /** Free text the teacher typed or pasted; stored verbatim. */
    TEXT;

    /**
     * @param fileName a file name as chosen by the uploader
     * @return the kind implied by its extension, or {@link #TEXT} when nothing
     *         matches. Never throws: a source with an unfamiliar extension is
     *         offered as free text rather than refused before anyone has looked
     *         at it, and the extractor is what actually decides whether the bytes
     *         can be read (F12.2)
     */
    public static BotSourceKind ofFileName(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return PDF;
        }
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) {
            return DOCX;
        }
        return TEXT;
    }

    /** @return a short human label for the sources table ("PDF file", "Word file", "Free text"). */
    public String label() {
        return switch (this) {
            case PDF -> "PDF file";
            case DOCX -> "Word file";
            case TEXT -> "Free text";
        };
    }
}
