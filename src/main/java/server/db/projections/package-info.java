/**
 * Read-only shapes returned by repositories instead of entities.
 *
 * <p>ARCHITECTURE §5 specifies repositories as "query-per-need, with projections for wire
 * DTOs". A projection is built by a JPQL constructor expression that names its columns, so
 * the columns it does not name are never fetched — which is what lets a projection carry a
 * guarantee an entity cannot. {@link server.db.projections.TakeExamQuestion} is the
 * defence-critical example (E2.12).
 *
 * <p>These are server-side types. They are not {@code Serializable} and must not become the
 * wire format: the client-facing DTOs live in {@code common/dto} and a service maps into
 * them.
 */
package server.db.projections;
