package server.features.locks;

import common.dto.lock.EntityRef;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which entities of a given kind a caller is allowed to be told about (E18.9 — F10.3).
 *
 * <p>The edit lock service is generic and holds no domain knowledge at all: an
 * {@link EntityRef} is a {@code (type, long id)} pair and nothing in
 * {@link EditLockService} knows what a question, an exam version or a bot source
 * is. That genericity is worth keeping, and it is also what made
 * {@code LOCKS_SNAPSHOT} a one-directional existence oracle: the verb was
 * role-gated to the two teaching roles and scoped no further, so a
 * <b>present</b> entry proved that a row exists, that somebody is editing it and
 * who that somebody is — for a course the caller cannot read, while every bank
 * read verb answers {@code NOT_FOUND} out of scope and is indistinguishable from
 * a row that does not exist, on purpose (Member A, PR20 §5.3).
 *
 * <p>This registry is the seam that closes that without teaching the lock service
 * about courses. A feature installs a predicate for its own entity type at
 * wiring; the lock service consults it by type and never learns what the answer
 * means.
 *
 * <h2>The contract: an uninstalled type is unfiltered, and that is deliberate</h2>
 *
 * <p>{@link #reaches} answers {@code true} for any type nobody has installed a
 * scope for. That is the opposite of the fail-closed rule
 * {@code Authorization.CourseTeachers.UNWIRED} and its siblings follow, and the
 * inversion is the whole design decision, so it is stated rather than left to be
 * discovered:
 *
 * <ul>
 *   <li><b>A type nobody registered a scope for has made no scoping promise.</b>
 *       An {@code Authorization} guard is asked "may she?" and a missing data
 *       source means it cannot tell, so it must refuse. This is asked "is this
 *       one of hers?" about a type whose owning feature has not claimed the
 *       question is meaningful. Refusing would be inventing a policy on that
 *       feature's behalf.</li>
 *   <li><b>Fail-closed here would break four working features silently.</b>
 *       {@code exam-version} (E7), {@code bot-source} (E16), {@code execution}
 *       (E9) and {@code grade} (E12) all key locks through this service and none
 *       of them installs a scope. Under a fail-closed default every one of their
 *       snapshots would answer empty and every watch would register nothing —
 *       not an error a developer could see, but a chip that never lights and a
 *       banner that never appears. That is the exact failure mode P-10 is about,
 *       one tier down.</li>
 *   <li><b>The direction of the risk is not symmetric.</b> A missing
 *       {@code Authorization} directory would let a caller <em>write</em>
 *       somebody else's data. A missing scope here lets a caller learn that a
 *       lock exists on a row of a type nobody has decided is sensitive — which
 *       is precisely the state every one of these types is in today, and which
 *       this class does not make worse.</li>
 * </ul>
 *
 * <p>So installing a scope is how a feature <em>opts in</em> to being scoped, and
 * the absence of one is a reviewable fact rather than a silent default. The
 * question type opts in, in {@code HSTSServer}'s assembly.
 *
 * <h2>Instance rather than process-wide, unlike {@code Authorization}</h2>
 *
 * <p>The installation shape is {@code Authorization.useCourseTeachers}': a
 * {@link FunctionalInterface} seam a unit test can satisfy with a two-line
 * lambda, bound once where the server is assembled, with {@link #install}
 * returning the previous value so a caller can put it back. What it does
 * <em>not</em> copy is the static field. {@code Authorization}'s own javadoc
 * argues at length that services should prefer the overload that "depends on
 * nothing global", and a registry owned by the one {@link EditLockService}
 * instance gets that for free: two tests cannot leak scopes into each other, and
 * there is no restore step anybody can forget.
 */
public final class EntityScopes {

    /**
     * "Does this caller reach this entity?", for one kind of entity.
     *
     * <p>Ids on both sides and nothing else, which is what keeps the lock service
     * generic: the implementation knows that an {@code entityId} of type
     * {@code question} is a five-digit display id, and the caller of this
     * interface does not.
     *
     * <p><b>Membership, not permission.</b> Like
     * {@code Authorization.reachesCourse}, an implementation answers only "is
     * this one of hers", never "is she allowed to call this verb". The role gate
     * is the handler's and has already run.
     */
    @FunctionalInterface
    public interface EntityScope {

        /**
         * @param callerId the session's user id, never from a payload
         * @param entityId the id in the request, in whatever numbering this type uses
         * @return whether this caller may be told about this entity
         */
        boolean reaches(long callerId, long entityId);

        /** A scope that reaches nothing: for tests that need the closed direction. */
        EntityScope NOTHING = (callerId, entityId) -> false;
    }

    private final Map<String, EntityScope> installed = new ConcurrentHashMap<>();

    /**
     * Binds the scope for one entity type, at assembly.
     *
     * @param entityType the type, normalised by {@link EntityRef#normalizeType}
     * @param scope      the predicate, or {@code null} to remove the installed one
     *                   and go back to unfiltered
     * @return whatever was installed before, or {@code null} when nothing was —
     *         the reason this returns anything at all
     */
    public EntityScope install(String entityType, EntityScope scope) {
        String key = EntityRef.normalizeType(entityType);
        return scope == null ? installed.remove(key) : installed.put(key, scope);
    }

    /**
     * Whether a scope has been installed for this type at all.
     *
     * <p>Exists so the difference between "filtered and she reaches nothing" and
     * "not filtered" is assertable, because {@link #reaches} deliberately cannot
     * tell them apart.
     */
    public boolean isInstalled(String entityType) {
        return installed.containsKey(EntityRef.normalizeType(entityType));
    }

    /**
     * The consult.
     *
     * @param entityType the kind of thing
     * @param callerId   the session's user id
     * @param entityId   the id being asked about
     * @return the installed scope's answer, or {@code true} when no scope is
     *         installed for this type — see the class javadoc for why that
     *         direction is the deliberate one
     */
    public boolean reaches(String entityType, long callerId, long entityId) {
        EntityScope scope = installed.get(EntityRef.normalizeType(entityType));
        return scope == null || scope.reaches(callerId, entityId);
    }

    /** The same consult, for a caller that already holds the reference. */
    public boolean reaches(EntityRef entity, long callerId) {
        Objects.requireNonNull(entity, "entity");
        return reaches(entity.entityType(), callerId, entity.entityId());
    }
}
