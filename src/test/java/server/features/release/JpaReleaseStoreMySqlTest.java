package server.features.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;
import server.db.projections.ExecutionContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The release manager's data seam on the real Flyway schema, with every constraint live.
 *
 * <p>This is the configuration the running server uses, and it is where two of E9's
 * behaviours are actually established rather than approximated: production collation makes
 * the case-insensitive code comparison real (C-1), and the {@code char(4)} column and the
 * foreign keys make an insert a real insert.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class JpaReleaseStoreMySqlTest extends JpaReleaseStoreContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }

    @Test
    @DisplayName("⚑ a teacher's code clashes with a stored one of any case, per the collation")
    void suppliedCodeClashIsCaseInsensitive() {
        long examVersionId = approvedVersion();
        // Stored the way the service stores one: upper case, whatever she shifted.
        createExecution(examVersionId, "AB7Q");

        boolean lower = codeInUse("ab7q");
        boolean mixed = codeInUse("Ab7Q");
        boolean upper = codeInUse("AB7Q");
        boolean unrelated = codeInUse("ZZ99");

        // This is the leaf where it means something. H2 folds case in the HQL `lower(...)`
        // either way; MySQL's production collation is what actually decides that a student
        // typing "ab7q" reaches the sitting released as "AB7Q", which is why "ab7q" has to
        // count as taken when another teacher tries to claim it.
        assertThat(lower).isTrue();
        assertThat(mixed).isTrue();
        assertThat(upper).isTrue();
        assertThat(unrelated).isFalse();
    }

    @Test
    @DisplayName("a code stored through the seam round-trips as the upper-case form")
    void storedCodeKeepsItsNormalisedForm() {
        long examVersionId = approvedVersion();

        long executionId = createExecution(examVersionId, "4821");

        Optional<ExecutionContext> stored =
                store().inTx(data -> data.executionById(executionId));
        // char(4) on MySQL: a code that came back padded or folded would break the join
        // screen's display and the teacher's read-aloud alike.
        assertThat(stored.orElseThrow().code()).isEqualTo("4821");
    }
}
