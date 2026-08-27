/**
 * The <b>Data Access Object / Repository Pattern</b> boundary ⚑ (B-34) — the whole of the Data
 * tier's public surface (E2).
 *
 * <h2>Why this file exists</h2>
 *
 * <p>{@code PLAN.md} §2 claims "DAO/Repository" and NFR-20 asks that a claimed pattern be named
 * <em>where it is used</em> rather than only in the document. Acceptance case 20.2 found it
 * named nowhere in production javadoc: the pattern was real, twelve classes deep, and a
 * defence answer to <i>"show me the DAO"</i> had to be reconstructed rather than read. This is
 * the boundary, so this is where it says so.
 *
 * <h2>What the boundary actually guarantees</h2>
 *
 * <p>The claim is not "there are classes with Repository in the name". It is that <b>this
 * package is the only place in the server that speaks to the database</b>, and acceptance case
 * 20.1 checks it as an absence rather than a promise: <b>no service under
 * {@code server/features} opens a {@code java.sql.Connection}</b>, and no client class imports
 * {@code server.db.} or {@code org.hibernate} at all. A feature asks a repository for
 * <em>rows</em> or for a <em>projection</em>; it never assembles a query, and it never learns
 * that Hibernate is what answers.
 *
 * <p>Three consequences the design leans on:
 *
 * <ul>
 *   <li><b>Every read is parameterised.</b> String-concatenated SQL cannot exist in a feature
 *       that has no query to concatenate (X-SEC).</li>
 *   <li><b>Authorization is above this line, never inside it.</b> A repository method that
 *       silently filtered by caller would be a check that looks like scoping while depending on
 *       the caller passing the right id; ownership is settled in the service, and
 *       {@code GradeRepository.findResultRows} records that reasoning in its own javadoc.</li>
 *   <li><b>Correctness data has a naming convention.</b> Reads that carry answer keys wear a
 *       sanctioned suffix ({@code …ForCheckedForm}), which is what makes
 *       {@code CorrectnessLeakGuardTest} able to scan for the ones that should not exist
 *       (E2.12).</li>
 * </ul>
 *
 * <h2>DAO and Repository, and why both words are right here</h2>
 *
 * <p>The two names are used interchangeably in {@code PLAN.md} and that is accurate rather than
 * loose. These classes are <b>DAOs</b> in structure — one per aggregate, each a thin, stateless
 * set of methods over a {@code Session} the caller's transaction supplies — and
 * <b>Repositories</b> in vocabulary, because several return domain projections
 * ({@code server.db.projections}) rather than entities, which is a collection-of-domain-objects
 * abstraction and not a row mapper. {@code RepositoryUserDirectory} is the clearest case: it
 * implements a Logic-tier interface, so the feature that authenticates depends on
 * {@code UserDirectory} and this package supplies it.
 *
 * <p><b>The session is a parameter, never a field.</b> Every method here takes the
 * {@code org.hibernate.Session} it should work in, so the unit of work is drawn by
 * {@code server.db.Transactions} at the calling layer and a repository can never quietly open a
 * second transaction inside somebody else's.
 */
package server.db.repos;
