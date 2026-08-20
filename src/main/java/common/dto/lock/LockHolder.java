package common.dto.lock;

import java.io.Serializable;
import java.util.Objects;

/**
 * Who is holding an edit lock (Common tier, E18.2).
 *
 * <p>The display name travels with the id because the banner has to say
 * "Rina Barak is editing this question", and a client that had to look a user id
 * up would need a directory endpoint that leaks the whole roster to everyone.
 * The name is the only user detail that crosses: no username, no role, no id of
 * anything else they are doing.
 *
 * <p>The user id is still here, and it is the only field the client compares: a
 * viewer decides "is this me?" by id, never by matching names, so two teachers
 * called Dana Cohen cannot end up sharing an editor.
 *
 * @param userId      the holder's internal user id
 * @param displayName the holder's full name, for the banner
 */
public record LockHolder(long userId, String displayName) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Shown when a user id cannot be resolved to a name (a deleted account, a test double). */
    public static final String UNKNOWN_NAME = "Another user";

    public LockHolder {
        displayName = displayName == null || displayName.isBlank() ? UNKNOWN_NAME : displayName.trim();
    }

    /** @return {@code true} when this holder is the given user. */
    public boolean is(long candidateUserId) {
        return userId == candidateUserId;
    }

    @Override
    public String toString() {
        return displayName + " (" + userId + ')';
    }
}
