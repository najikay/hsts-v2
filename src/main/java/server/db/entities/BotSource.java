package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * One piece of material a bot answers from — {@code bot_sources} (V6, §5, F12.2, F12.3).
 *
 * <p>Both {@link #raw} and {@link #extractedText} are mandatory and non-empty, enforced
 * by NOT NULL plus a length check in the migration. A source row only comes into
 * existence after a successful parse — F12.2 reports parse failures immediately rather
 * than storing half a source — so a row that appears in the teacher's list and
 * contributes nothing to the prompt cannot exist. For a {@link BotSourceType#TEXT}
 * source, the pasted text is stored as the raw bytes too (lead's decision, E2 PR 1
 * review round 2).
 *
 * <p><b>Two version numbers sit on this row and they are unrelated.</b>
 * {@link #version} is the domain one from §5, bumped on re-upload so a stale extraction
 * is detectable. {@link #lockVersion} is the JPA optimistic-lock backstop for two
 * teachers editing the same source at once (F10.4). Do not use either for the other's
 * purpose.
 *
 * <p><b>{@link #raw} loads with the row</b>, and there is no annotation that changes that:
 * {@code @Basic(fetch = LAZY)} on a basic attribute needs build-time bytecode enhancement,
 * which this build does not do, so the hint is silently ignored. The sources list of
 * F12.3 shows titles and types and must therefore query a scalar projection rather than
 * this entity — otherwise every row drags several megabytes of PDF with it.
 */
@Entity
@Table(name = "bot_sources")
public class BotSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "bot_id", nullable = false)
    private long botId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private BotSourceType type;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Lob
    @Column(name = "raw", length = ColumnSizes.MEDIUM, nullable = false)
    private byte[] raw;

    @Column(name = "extracted_text", length = ColumnSizes.MEDIUM, nullable = false)
    private String extractedText;

    @Column(name = "added_by", nullable = false)
    private long addedBy;

    @Column(name = "updated_at", nullable = false, precision = 3)
    private Instant updatedAt;

    /** The §5 source-content version — bumped on re-upload. Not the lock. */
    @Column(name = "version", nullable = false)
    private int version = 1;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    /** Required by JPA. */
    protected BotSource() {
    }

    public BotSource(long botId, BotSourceType type, String title,
                     byte[] raw, String extractedText, long addedBy, Instant updatedAt) {
        this.botId = botId;
        this.type = type;
        this.title = title;
        this.raw = raw == null ? null : raw.clone();
        this.extractedText = extractedText;
        this.addedBy = addedBy;
        this.updatedAt = updatedAt;
        this.version = 1;
    }

    public Long getId() {
        return id;
    }

    public long getBotId() {
        return botId;
    }

    public BotSourceType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    /** @return a defensive copy of the original bytes */
    public byte[] getRaw() {
        return raw == null ? null : raw.clone();
    }

    /** What actually reaches the provider prompt (F12.8). */
    public String getExtractedText() {
        return extractedText;
    }

    public long getAddedBy() {
        return addedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public int getVersion() {
        return version;
    }

    public int getLockVersion() {
        return lockVersion;
    }

    /** Replaces the content and bumps the domain version (F12.3). */
    public void replaceContent(byte[] raw, String extractedText, Instant updatedAt) {
        this.raw = raw == null ? null : raw.clone();
        this.extractedText = extractedText;
        this.updatedAt = updatedAt;
        this.version++;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
