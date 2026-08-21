package server.db.repos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.entities.Difficulty;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link BankQuery}'s two states and the distinction between them (E6.5).
 *
 * <p>No database: this is a value type, and the whole reason it exists is that "reaches
 * nothing" and "reaches everything" must never be one state. That is checkable in memory and
 * cheaper to check there.
 */
class BankQueryTest {

    @Test
    @DisplayName("an empty scope matches nothing; an unrestricted one does not")
    void emptyAndUnrestrictedAreDifferentStates() {
        // The distinction the record exists for. Collapsing them is how a scoping bug becomes
        // a leak rather than an empty screen, which BankBrowseContract proves end to end.
        assertThat(BankQuery.scopedTo(List.of(), null, null, null, null).matchesNothing())
                .isTrue();
        assertThat(BankQuery.everyCourse(null, null, null, null).matchesNothing())
                .isFalse();
        assertThat(BankQuery.scopedTo(List.of("11"), null, null, null, null).matchesNothing())
                .isFalse();
    }

    @Test
    @DisplayName("the principal's query is unrestricted and carries no course list")
    void everyCourseIsUnrestricted() {
        BankQuery all = BankQuery.everyCourse("11", "topic", Difficulty.HARD, "root");

        assertThat(all.allCourses()).isTrue();
        assertThat(all.reachableCourses()).isEmpty();
        // Filters still apply: unrestricted is about authorization, not about ignoring what
        // she asked for.
        assertThat(all.courseCode()).isEqualTo("11");
        assertThat(all.difficulty()).isEqualTo(Difficulty.HARD);
    }

    @Test
    @DisplayName("the course list is copied, so a caller cannot widen scope after the fact")
    void reachableCoursesAreDefensivelyCopied() {
        List<String> mutable = new ArrayList<>(List.of("11"));
        BankQuery query = BankQuery.scopedTo(mutable, null, null, null, null);

        mutable.add("21");

        assertThat(query.reachableCourses()).containsExactly("11");
    }

    @Test
    @DisplayName("a null course list is an empty one, not a null field")
    void nullReachableBecomesEmpty() {
        // Reachable only through the canonical constructor, which a record makes public
        // whether or not the factories are the intended door. An unguarded null here would
        // be a NullPointerException inside matchesNothing, at the point of deciding scope.
        BankQuery query = new BankQuery(false, null, null, null, null, null);

        assertThat(query.reachableCourses()).isEmpty();
        assertThat(query.matchesNothing()).isTrue();
    }

    @Test
    @DisplayName("scopedTo refuses a null list rather than silently meaning 'nothing'")
    void scopedToRejectsNull() {
        // Deliberately stricter than the canonical constructor. Through the factory a null
        // list is a caller bug, and turning it into "reaches nothing" would hide it behind an
        // empty screen that looks like a legitimate answer.
        assertThatNullPointerException().isThrownBy(
                () -> BankQuery.scopedTo(null, null, null, null, null));
    }
}
