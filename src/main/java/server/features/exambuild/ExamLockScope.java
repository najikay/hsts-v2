package server.features.exambuild;

import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.Transactions;
import server.db.repos.ExamBuildRepository;
import server.features.locks.EntityScopes;

/**
 * Who may see an {@code exam-version} lock at all (E18.5, E7 section 5.3).
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@link EntityScopes} is <b>unfiltered for any type nobody installs</b>, and until this class
 * the only installed type was {@code question}. Every E7 verb already refuses a non-author with
 * {@code NOT_FOUND}, deliberately indistinguishable from an unknown id, because the contract says
 * so in as many words: <i>"Naming the exam would tell a caller probing ids that it exists and who
 * owns it, which is the existence oracle P-5 is about."</i>
 *
 * <p>{@code LOCK_ACQUIRE} was the one door left open on the same rows. Its refusal names the
 * holder, so without a scope any teacher could walk the id space, learn which exam versions exist
 * and who is composing them, and - because {@code ExamService} consults
 * {@code EditLockGuard.heldByAnother} before every write - hold a colleague's builder read-only
 * for as long as she kept renewing.
 *
 * <p>The exposure was theoretical while no client took these locks. <b>The builder's edit lock is
 * what makes it reachable</b>, so it is closed in the same change rather than filed behind it.
 *
 * <h2>Author only, and why that is the whole rule</h2>
 *
 * <p>A coordinator approving an exam is not an omission. {@code EXAM_VERSION_GET} answers only
 * {@code authoredHeader}, so the builder is a screen no one but the author can open, and the
 * approval path reads through its own verbs and takes no lock. The set of people who can reach an
 * exam-version lock and the set who can author the exam are the same set, and this predicate says
 * that once instead of leaving the two to drift.
 *
 * <p>If a later feature gives a coordinator a lockable view of someone else's exam, this is the
 * one place that changes, and the compiler will not remind anybody. That is the honest limit of
 * a predicate rather than a mechanism.
 *
 * <h2>Not installed here</h2>
 *
 * <p>The install is one line in {@code HSTSServer}, beside {@code question}'s, and that file is
 * the lead's. Built rather than installed for the same reason {@code questionLockScope} is: so a
 * test can run <em>this exact lambda</em> against a real database rather than a copy of it that
 * agrees with the test.
 */
public final class ExamLockScope {

    private static final Logger log = LoggerFactory.getLogger(ExamLockScope.class);

    private ExamLockScope() {
        // static factory only
    }

    /**
     * Builds the {@code exam-version} scope.
     *
     * <p>Fails closed on every uncertainty. An id no exam version has is out of scope rather than
     * an exception: this predicate runs inside a snapshot that may be serving many rows, and one
     * hostile id must not take the whole answer down. A version whose row has gone answers false
     * for the same reason, and answering true on a missing row would hand back exactly the
     * existence signal the class exists to withhold.
     *
     * @param sessionFactory the pool to open one short transaction per consult on
     * @return the predicate to install under {@code EntityRef.EXAM_VERSION}
     */
    public static EntityScopes.EntityScope of(SessionFactory sessionFactory) {
        ExamBuildRepository exams = new ExamBuildRepository();
        return (callerId, entityId) ->
                Transactions.inTx(sessionFactory, session ->
                        exams.findCompositionHeader(session, entityId)
                                .map(header -> header.authorId() == callerId)
                                .orElseGet(() -> {
                                    log.debug("Lock scope: no exam version {}", entityId);
                                    return false;
                                }));
    }
}
