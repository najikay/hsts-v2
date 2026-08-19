/**
 * JPA entities for the HSTS schema — one per table in {@code V1__core.sql} …
 * {@code V7__notifications.sql}, mapping ARCHITECTURE §5 (E2.9).
 *
 * <h2>Mapping conventions</h2>
 *
 * These hold for every class in this package. They exist because the schema is a
 * frozen contract that these classes only <em>describe</em>: where a convention
 * makes drift between the two impossible, it is preferred to one that merely makes
 * drift unlikely.
 *
 * <ol>
 *   <li><b>Every table and column name is written out.</b> Nothing relies on
 *       Hibernate's implicit naming strategy, because a renamed field would then
 *       silently stop matching the migration.</li>
 *
 *   <li><b>Every foreign key is a plain scalar field. There are no JPA associations
 *       in this package at all</b> — no {@code @ManyToOne}, no {@code @OneToMany}.
 *       This is deliberate and it is the convention most worth arguing about, so:
 *       §5 specifies repositories as "query-per-need, with projections for wire
 *       DTOs", which is a design that never navigates an object graph. Mapping one
 *       anyway would buy nothing and bring the whole familiar tax — N+1 selects,
 *       {@code LazyInitializationException} the moment a DTO is built outside the
 *       session, and cascade settings that quietly disagree with the database's own
 *       {@code ON DELETE} rules. Scalar keys also make the composite foreign key on
 *       {@code exam_version_questions} expressible at all, and leave these classes as
 *       plain data holders that can be constructed and asserted in a unit test
 *       without a session.</li>
 *
 *   <li><b>No {@code cascade} and no {@code orphanRemoval}</b> — which follows from
 *       the above, but is worth stating on its own. Deletion policy belongs to the
 *       database, which states {@code ON DELETE} explicitly on every foreign key.
 *       Restating it here would create a second source of truth that can disagree
 *       with the first, and the half that matters is the one that still applies when
 *       rows are deleted by a migration, by the seed loader, or by hand.</li>
 *
 *   <li><b>{@code @Version} maps to the {@code lock_version} column</b> on exactly
 *       the six entities §5 names: questions, exams, exam versions, executions, bot
 *       sources and grades. The field is named {@code lockVersion} to keep it clear
 *       of the domain {@code versionNo} it sits beside. {@code exam_attempts}
 *       deliberately has none — the submit-versus-expiry race is settled by a
 *       status-guarded atomic update (lead's decision, E2 PR 1 review).</li>
 *
 *   <li><b>Enums are {@link jakarta.persistence.EnumType#STRING}</b>, never ordinal:
 *       the columns are MySQL {@code ENUM}s of names, and an ordinal mapping would
 *       corrupt every row the day someone inserts a value in the middle.</li>
 *
 *   <li><b>Every timestamp is {@link java.time.Instant}</b>, against a
 *       {@code DATETIME(3)} column. §5 requires all stored times to be UTC, and
 *       {@code DATETIME} carries no zone of its own — so {@code HibernateUtil} pins
 *       {@code hibernate.jdbc.time_zone=UTC} and the type system carries the rest.
 *       That makes UTC structural rather than something each author has to
 *       remember.</li>
 * </ol>
 *
 * <p>The mapping is proved against the real schema rather than assumed: the MySQL
 * suite runs Flyway and then starts Hibernate with schema validation, so any
 * disagreement between a field here and a column there fails the build. H2 builds
 * its schema from these classes and therefore cannot detect that class of error at
 * all — it is a fast harness for repository logic, nothing more.
 */
package server.db.entities;
