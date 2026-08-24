package server.db.repos;

import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The exam builder's write surface on the fast in-memory suite.
 *
 * <p>H2 runs the ordering, replacement and scope tests, and <b>also the two unique constraints</b>
 * on {@code exam_version_questions}: the entities declare them on {@code @Table} and H2's schema is
 * generated from the entities, so both are live here. That is what {@code H2Support} means by
 * "unique constraints <em>are</em> reproduced", and it is why the duplicate-question test for T-3.9
 * sits in the contract rather than in the MySQL leaf.
 *
 * <p>What is missing here is the composite foreign key and the {@code CHECK} constraints, which
 * schema generation does not emit because no association and no {@code @Check} is mapped. Those
 * live in the MySQL leaf.
 */
class ExamBuildRepositoryH2Test extends ExamBuildRepositoryContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
