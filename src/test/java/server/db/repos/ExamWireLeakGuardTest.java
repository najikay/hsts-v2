package server.db.repos;

import common.dto.exam.AttemptOutcome;
import common.dto.exam.ExamQuestion;
import common.dto.grading.AnswerReviewRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The E2.12 guarantee carried onto the <b>student's wire</b> (E10.2 ⚑ — F6.6).
 *
 * <h2>Why a second guard, next to {@link CorrectnessLeakGuardTest}</h2>
 *
 * <p>That one scans {@code server.db.projections} and {@code server.db.repos}: the answer
 * key cannot get out of the database on a student-facing read. But E10 added a whole
 * package of types that a student actually receives, and none of them is a projection or a
 * repository, so the existing scan does not look at them at all.
 *
 * <p>The cheapest way to reintroduce the v1 leak was therefore never to touch the
 * projection. It was to write, in the mapper that builds the exam form:
 *
 * <pre>{@code
 * public record ExamQuestion(..., byte correctAnswer) { }   // "for the review screen"
 * }</pre>
 *
 * <p>Every existing test stays green, because none of them looks at {@code common.dto.exam}.
 * This one scans it, so a component whose <em>name</em> reads like an answer key fails the
 * build the moment it is written rather than when somebody happens to review it.
 *
 * <h2>The scan is deliberately name-based, and that is enough</h2>
 *
 * <p>A determined author could still smuggle correctness through a component called
 * {@code hint}. That is not what this guard is for. v1's leak was not a conspiracy, it was
 * a DTO that carried the field because the entity did; the defence that matters is making
 * the honest version of that mistake impossible and the dishonest version visible in a
 * diff.
 *
 * <p><b>{@code common.dto.grading} is not scanned, on purpose.</b> Its
 * {@code AnswerReviewRow} carries {@code correct} and must: it is the teacher's marked
 * paper and the student's own checked form, both gated by the three conditions in the
 * frozen grading contract. Scanning it would force a suppression, and a suppression is
 * exactly what this pair of guards exists to avoid.
 */
class ExamWireLeakGuardTest {

    private static final Path COMPILED_EXAM_DTOS =
            Path.of("target", "classes", "common", "dto", "exam");

    @Test
    @DisplayName("no record on the take-exam wire can hold an answer key")
    void noExamDtoCarriesCorrectness() {
        List<Class<?>> records = classesIn().stream().filter(Class::isRecord).toList();

        assertThat(records)
                .as("the scan must actually find the take-exam wire package")
                .hasSizeGreaterThanOrEqualTo(15);

        for (Class<?> dto : records) {
            List<String> components = Arrays.stream(dto.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(components)
                    .as("%s travels to a student sitting an exam; it must not carry which "
                            + "answer is right (F6.6)", dto.getSimpleName())
                    .noneMatch(CorrectnessNames::suggestsCorrectness);
        }
    }

    @Test
    @DisplayName("nor can any of them, by declared field, whatever the record says")
    void noExamDtoDeclaresCorrectness() {
        // Components and fields are the same thing for a record today, but a future
        // non-record DTO in this package would slip past the check above.
        for (Class<?> dto : classesIn()) {
            assertThat(CorrectnessNames.carriesCorrectness(dto))
                    .as("%s must not declare an answer key field", dto.getSimpleName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("that check can fail: the grading row really does trip it")
    void theCheckHasTeeth() {
        // Without this, the two tests above would also pass if suggestsCorrectness() stopped
        // recognising anything at all. AnswerReviewRow is the honest positive: it carries
        // `correct` by design, gated by the three conditions in the grading contract.
        assertThat(CorrectnessNames.carriesCorrectness(AnswerReviewRow.class)).isTrue();
        assertThat(CorrectnessNames.carriesCorrectness(ExamQuestion.class)).isFalse();
        assertThat(CorrectnessNames.carriesCorrectness(AttemptOutcome.class)).isFalse();
    }

    @Test
    @DisplayName("the four options and the chosen answer are not mistaken for a key")
    void theStudentsOwnAnswersAreNotCorrectness() {
        // The predicate has to be wide enough to catch a smuggled `rightAnswer` without
        // catching option1..option4, `selected` or `answeredCount`, which are the whole
        // point of this package.
        assertThat(CorrectnessNames.suggestsCorrectness("option1")).isFalse();
        assertThat(CorrectnessNames.suggestsCorrectness("option4")).isFalse();
        assertThat(CorrectnessNames.suggestsCorrectness("selected")).isFalse();
        assertThat(CorrectnessNames.suggestsCorrectness("answeredCount")).isFalse();
        assertThat(CorrectnessNames.suggestsCorrectness("answered")).isFalse();
        assertThat(CorrectnessNames.suggestsCorrectness("summary")).isFalse();

        assertThat(CorrectnessNames.suggestsCorrectness("correctOption")).isTrue();
        assertThat(CorrectnessNames.suggestsCorrectness("answerKey")).isTrue();
        assertThat(CorrectnessNames.suggestsCorrectness("isCorrect")).isTrue();
    }

    @Test
    @DisplayName("the exam question really is the shape a student is served")
    void examQuestionIsTheStudentShape() {
        List<String> components = Arrays.stream(ExamQuestion.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(components).containsExactly("questionVersionId", "displayId", "ordinal",
                "points", "text", "option1", "option2", "option3", "option4", "image");
    }

    private static List<Class<?>> classesIn() {
        try (Stream<Path> files = Files.list(COMPILED_EXAM_DTOS)) {
            return files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".class"))
                    .filter(name -> !name.equals("package-info.class"))
                    .filter(name -> !name.contains("$"))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .<Class<?>>map(ExamWireLeakGuardTest::load)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not scan " + COMPILED_EXAM_DTOS, e);
        }
    }

    private static Class<?> load(String simpleName) {
        try {
            return Class.forName("common.dto.exam." + simpleName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("compiled class not loadable: " + simpleName, e);
        }
    }
}
