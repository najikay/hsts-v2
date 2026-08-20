package server.db.repos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.entities.QuestionVersion;
import server.db.projections.TakeExamQuestion;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The E2.12 guarantee as a property of the type, provable without a database.
 *
 * <p>F6.6 and ARCHITECTURE §5 require the take-exam path to exclude {@code correct_answer}
 * <em>structurally</em> — "a type with nowhere to put it, not a query that happens to omit
 * it". This class is the proof of the first half; {@link TakeExamSqlH2Test} proves the query
 * never even fetches the column.
 */
class TakeExamProjectionShapeTest {

    /**
     * Exactly what a student may receive. A whitelist rather than a blacklist: any component
     * added to the projection fails this test until someone edits this list, which turns
     * "widen what students can see" into a deliberate, reviewable act.
     */
    private static final List<String> ALLOWED = List.of(
            "questionVersionId", "displayId", "ordinal", "points",
            "text", "answer1", "answer2", "answer3", "answer4", "image");

    @Test
    @DisplayName("the projection carries these ten components and nothing else")
    void componentsAreExactlyTheWhitelist() {
        List<String> actual = Arrays.stream(TakeExamQuestion.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(actual).containsExactlyInAnyOrderElementsOf(ALLOWED);
    }

    @Test
    @DisplayName("no component, field or method of the projection mentions correctness")
    void nothingNamedForCorrectness() {
        assertThat(names(TakeExamQuestion.class.getRecordComponents(), RecordComponent::getName))
                .noneMatch(TakeExamProjectionShapeTest::soundsLikeCorrectness);
        assertThat(names(TakeExamQuestion.class.getDeclaredFields(), Field::getName))
                .noneMatch(TakeExamProjectionShapeTest::soundsLikeCorrectness);
        assertThat(names(TakeExamQuestion.class.getDeclaredMethods(), Method::getName))
                .noneMatch(TakeExamProjectionShapeTest::soundsLikeCorrectness);
    }

    @Test
    @DisplayName("the entity really does still hold the field this projection excludes")
    void theGuardIsNotVacuous() {
        // Without this, the two tests above quietly stop guarding anything the day someone
        // renames QuestionVersion.correctAnswer: a name-based check that no longer matches
        // any real name passes for the wrong reason (it would pass against an empty schema
        // too). Assert the thing being excluded exists, spelled the way the checks expect.
        List<String> entityFields = names(QuestionVersion.class.getDeclaredFields(), Field::getName);

        assertThat(entityFields).contains("correctAnswer");
        assertThat(entityFields).anyMatch(TakeExamProjectionShapeTest::soundsLikeCorrectness);
    }

    @Test
    @DisplayName("the projection is not serialisable, so it cannot become the wire format")
    void notAWireType() {
        // common/dto owns the wire DTOs. A server-side projection that happened to be
        // Serializable is exactly how an unaudited type ends up on a socket.
        assertThat(Serializable.class.isAssignableFrom(TakeExamQuestion.class)).isFalse();
    }

    @Test
    @DisplayName("the illustration cannot be mutated through the projection")
    void imageIsCopiedBothWays() {
        byte[] original = {1, 2, 3};
        TakeExamQuestion question = new TakeExamQuestion(1L, "11001", 1, 20,
                "שאלה", "א", "ב", "ג", "ד", original);

        original[0] = 9;
        byte[] handedOut = question.image();
        handedOut[1] = 8;

        assertThat(question.image()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("two questions with the same illustration bytes are equal")
    void equalityComparesImageContent() {
        // A record's generated equals compares byte[] by reference, and the compact
        // constructor clones - so without the override these two would never be equal. It
        // would stay invisible while every seeded illustration is null and start failing when
        // real assets land, in list assertions far from anything that looks like the cause.
        TakeExamQuestion one = illustrated(new byte[] {1, 2, 3});
        TakeExamQuestion two = illustrated(new byte[] {1, 2, 3});

        assertThat(one).isEqualTo(two);
        assertThat(one).hasSameHashCodeAs(two);
        assertThat(List.of(one, two)).containsExactly(two, one);
    }

    @Test
    @DisplayName("different illustration bytes make two questions different")
    void differentImagesAreNotEqual() {
        assertThat(illustrated(new byte[] {1, 2, 3})).isNotEqualTo(illustrated(new byte[] {9}));
        assertThat(illustrated(new byte[] {1})).isNotEqualTo(illustrated(null));
    }

    private static TakeExamQuestion illustrated(byte[] image) {
        return new TakeExamQuestion(1L, "11001", 1, 20, "שאלה", "א", "ב", "ג", "ד", image);
    }

    @Test
    @DisplayName("a question with no illustration keeps a null image rather than an empty array")
    void nullImageStaysNull() {
        TakeExamQuestion question = new TakeExamQuestion(1L, "11001", 1, 20,
                "שאלה", "א", "ב", "ג", "ד", null);

        assertThat(question.image()).isNull();
    }

    private static <T> List<String> names(T[] items, java.util.function.Function<T, String> naming) {
        return Arrays.stream(items).map(naming).toList();
    }

    private static boolean soundsLikeCorrectness(String name) {
        // One definition, shared with CorrectnessLeakGuardTest: two copies would drift, and
        // these guards are only ever as good as this predicate.
        return CorrectnessNames.suggestsCorrectness(name);
    }
}
