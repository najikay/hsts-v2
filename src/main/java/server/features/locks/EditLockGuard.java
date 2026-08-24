package server.features.locks;

import common.dto.lock.EntityRef;
import common.dto.lock.LockHolder;

import java.util.Objects;
import java.util.Optional;

/**
 * The write-path lock consult (E18, ruled 2026-08-24 on #43's thread).
 *
 * <p>{@code BANK_WIRE_CONTRACT} §6 promises {@code CONFLICT} when "the question is edit-locked
 * by someone else", and E7's {@code EXAM_VERSION_SAVE} makes the same promise about exam
 * versions. This class is where that consult lives, once, so the semantics cannot drift
 * between features. They are, as ruled:
 *
 * <ul>
 *   <li>An <b>unexpired</b> lock held by <b>another</b> user blocks the write; the caller
 *       answers {@code CONFLICT} with its own feature's sentence (the refusal wording belongs
 *       to the feature, not to this class).</li>
 *   <li>An expired lock does not block. Expiry is the sweeper's job, not the writer's, and
 *       {@link EditLockService#holderOf} already refuses to report a lapsed hold.</li>
 *   <li>The caller's own lock never blocks.</li>
 *   <li>The optimistic version check stays the <b>final arbiter, after</b> this consult. The
 *       lock is the polite refusal; the version check is the correctness guarantee.</li>
 * </ul>
 *
 * <p><b>Per-process and not durable, on purpose.</b> {@link EditLockService} keeps its holds
 * in memory, so this guard's answer is only as wide as the one running server. That matches a
 * single-server HSTS exactly, and it is stated here rather than discovered during a
 * two-machine rehearsal: a second server process would not see the first one's locks, and the
 * version check below this guard is what still holds in that world.
 */
public final class EditLockGuard {

    private final EditLockService locks;

    public EditLockGuard(EditLockService locks) {
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    /**
     * The consult: who, other than the caller, holds a live lock on this entity?
     *
     * @param entity   the row the caller wants to write
     * @param callerId the caller's user id
     * @return the live holder when it is somebody else; empty when the entity is unlocked,
     *         the hold has lapsed, or the caller holds it herself
     */
    public Optional<LockHolder> heldByAnother(EntityRef entity, long callerId) {
        Objects.requireNonNull(entity, "entity");
        return locks.holderOf(entity).filter(holder -> !holder.is(callerId));
    }
}
