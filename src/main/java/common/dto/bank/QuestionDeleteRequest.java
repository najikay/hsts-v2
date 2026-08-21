package common.dto.bank;

import java.io.Serializable;

/**
 * The {@code QUESTION_DELETE} payload (Common tier, E6.4 — F2.5).
 *
 * <p>It carries the same concurrency token an edit does, and that is the whole reason it is a
 * record rather than a bare display id: a delete racing a colleague's edit is decided by
 * {@code baseVersionNo} disagreeing with the current latest, which answers {@code CONFLICT},
 * rather than by whichever transaction happened to commit first.
 *
 * <p>The verb is named {@code DELETE} because that is what the teacher is doing, not what the
 * row does. Blocked while any exam version references any version of the question, and otherwise
 * soft: {@code deleted_at} is stamped, the question leaves every listing, the version history
 * survives so a marked paper stays explicable, and the 5-digit serial is never reused (T-2.8).
 *
 * @param displayId5    the question to remove
 * @param baseVersionNo the version the caller was looking at when she pressed delete
 */
public record QuestionDeleteRequest(String displayId5, int baseVersionNo) implements Serializable {

    private static final long serialVersionUID = 1L;
}
