package server.db.repos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.projections.TakeExamQuestion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The E2.12 guarantee enforced across the whole package rather than one class (E2.12).
 *
 * <h2>Why this exists on top of {@link TakeExamProjectionShapeTest}</h2>
 *
 * <p>An adversarial review found that both original guards were scoped to a single named
 * artifact: the shape test hardcodes {@code TakeExamQuestion.class}, and the SQL test inspects
 * only statements emitted by {@code findForTakeExam}. So the cheapest way to leak a correct
 * answer was never to touch either one — it was to add a <em>second</em> student-reachable
 * read beside them:
 *
 * <pre>{@code
 * public record ExamReviewQuestion(..., byte chosenAnswer, byte rightAnswer) { }
 * public List<ExamReviewQuestion> findForReview(Session session, long attemptId) { ... }
 * }</pre>
 *
 * <p>That is a genuinely scheduled feature — students reviewing a marked paper (E10/E11) — and
 * every existing test would have stayed green, because none of them looks at anything except
 * the one record and the one method they name.
 *
 * <p>These two tests scan instead of naming, so a new leak fails the build the moment it is
 * written rather than when someone happens to review it.
 *
 * <h2>The review feature arrived, and it is sanctioned rather than exempted</h2>
 *
 * <p>The hypothetical above is now E13.2's checked form, specified in the frozen grading wire
 * contract. It did not get a suppression: it got a second sanctioned suffix,
 * {@code ForCheckedForm}, on the same terms as {@code ForAuthoring} — the name says which
 * audience the read is for, and the three conditions that make that audience legitimate are
 * enforced and tested elsewhere. See {@link #SANCTIONED_SUFFIXES}.
 */
class CorrectnessLeakGuardTest {

    private static final Path COMPILED_PROJECTIONS =
            Path.of("target", "classes", "server", "db", "projections");
    private static final Path COMPILED_REPOSITORIES =
            Path.of("target", "classes", "server", "db", "repos");

    /**
     * The suffixes that license a read to return an answer key.
     *
     * <h2>{@code ForAuthoring} — teacher-only reads (E2.12)</h2>
     *
     * <p>The original sanction: a teacher composing a question is looking at the answer key
     * because that is what authoring is, and the suffix is what stops a student-facing caller
     * reaching for one of those reads by mistake.
     *
     * <h2>{@code ForCheckedForm} — the student's own marked paper (E13.2)</h2>
     *
     * <p>Added when the E12/E13 wire contract was frozen. A student <b>is</b> entitled to see
     * which answer was right, but only on their own paper and only after the marking is
     * finished, so {@code CHECKED_FORM_GET} serves correctness under three conditions that all
     * have to hold: the grade is the caller's, its state is {@code APPROVED}, and the execution
     * is closed. The reads behind that verb carry an answer key and can therefore never be
     * named {@code ForAuthoring} honestly, which would have left one legitimate feature with a
     * choice between a lie and a suppression.
     *
     * <p><b>What licenses it is not this list.</b> A suffix is a naming convention, not a
     * guard: it makes a leak deliberate rather than accidental. The actual enforcement is
     * E13.1's authorization tests, which prove all three conditions on the handler and prove
     * that failing any of them answers {@code NOT_FOUND} indistinguishably. If those tests ever
     * go away, this suffix stops being licensed and should come back out of this list — the
     * contract file (docs/contracts/GRADING_WIRE_CONTRACT.md) records that dependency.
     */
    private static final List<String> SANCTIONED_SUFFIXES = List.of("ForAuthoring", "ForCheckedForm");

    @Test
    @DisplayName("no projection anywhere in the package can hold an answer key")
    void noProjectionCarriesCorrectness() {
        List<Class<?>> records = classesIn(COMPILED_PROJECTIONS).stream()
                .filter(Class::isRecord)
                .toList();

        assertThat(records).as("the scan must actually find the projections").isNotEmpty();

        for (Class<?> projection : records) {
            List<String> components = Arrays.stream(projection.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(components)
                    .as("%s is returned to callers; it must not carry which answer is right",
                            projection.getSimpleName())
                    .noneMatch(CorrectnessNames::suggestsCorrectness);
        }
    }

    @Test
    @DisplayName("any repository read that hands back an answer key is named ForAuthoring or ForCheckedForm")
    void correctnessBearingReadsAreNamedForTheirAudience() {
        List<Class<?>> repositories = classesIn(COMPILED_REPOSITORIES);

        assertThat(repositories).as("the scan must actually find the repositories").isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (Class<?> repository : repositories) {
            for (Method method : repository.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                boolean leaks = returnedTypes(method).stream().anyMatch(CorrectnessNames::carriesCorrectness);
                if (leaks && !isSanctioned(method.getName())) {
                    offenders.add(repository.getSimpleName() + "." + method.getName());
                }
            }
        }

        assertThat(offenders)
                .as("a read returning a type that holds the correct answer must name the one "
                        + "audience it is for, so a caller serving anybody else cannot reach for "
                        + "it by accident: %s", SANCTIONED_SUFFIXES)
                .isEmpty();
    }

    @Test
    @DisplayName("the suffix rule names exactly two audiences, and nothing that merely looks like one")
    void onlyTwoSuffixesAreSanctioned() {
        // Adding a third is a deliberate act with a licensing argument behind it, which is
        // what SANCTIONED_SUFFIXES' javadoc records. A read that just mentions the words is
        // not sanctioned: the suffix has to be the end of the name.
        assertThat(isSanctioned("findVersionForAuthoring")).isTrue();
        assertThat(isSanctioned("findAnswersForCheckedForm")).isTrue();

        assertThat(isSanctioned("findForCheckedFormAndAlsoTheDashboard")).isFalse();
        assertThat(isSanctioned("findForAuthoringPreview")).isFalse();
        assertThat(isSanctioned("findForReview")).isFalse();
        assertThat(isSanctioned("findForTakeExam")).isFalse();
    }

    @Test
    @DisplayName("that naming check can fail - the authoring reads really do trip it")
    void theNamingCheckHasTeeth() {
        // Without this, the test above would also pass if carriesCorrectness() stopped
        // recognising anything at all - which is exactly what a rename of
        // QuestionVersion.correctAnswer would cause.
        assertThat(CorrectnessNames.carriesCorrectness(QuestionVersion.class)).isTrue();
        assertThat(CorrectnessNames.carriesCorrectness(TakeExamQuestion.class)).isFalse();
        assertThat(CorrectnessNames.carriesCorrectness(Question.class)).isFalse();

        List<String> authoringReads = Arrays.stream(QuestionRepository.class.getDeclaredMethods())
                .filter(method -> returnedTypes(method).stream()
                        .anyMatch(CorrectnessNames::carriesCorrectness))
                .map(Method::getName)
                .toList();

        assertThat(authoringReads)
                .as("the two authoring reads are the ones this rule is about")
                .containsExactlyInAnyOrder("findVersionForAuthoring", "findLatestVersionForAuthoring");
    }

    @Test
    @DisplayName("the four options a student may see are not mistaken for an answer key")
    void optionsAreNotFlaggedAsCorrectness() {
        // The predicate has to be wide enough to catch rightAnswer and answerIndex without
        // catching answer1..answer4, which are the whole point of the projection.
        assertThat(CorrectnessNames.suggestsCorrectness("answer1")).isFalse();
        assertThat(CorrectnessNames.suggestsCorrectness("answer4")).isFalse();
        assertThat(CorrectnessNames.suggestsCorrectness("text")).isFalse();

        assertThat(CorrectnessNames.suggestsCorrectness("correctAnswer")).isTrue();
        assertThat(CorrectnessNames.suggestsCorrectness("rightAnswer")).isTrue();
        assertThat(CorrectnessNames.suggestsCorrectness("answerIndex")).isTrue();
        assertThat(CorrectnessNames.suggestsCorrectness("solution")).isTrue();
        assertThat(CorrectnessNames.suggestsCorrectness("key")).isTrue();
    }

    /** @return whether this method name claims one of the two sanctioned audiences. */
    private static boolean isSanctioned(String methodName) {
        return SANCTIONED_SUFFIXES.stream().anyMatch(methodName::endsWith);
    }

    /** The return type plus anything it is generic over, so {@code List<X>} counts as X. */
    private static Set<Class<?>> returnedTypes(Method method) {
        Set<Class<?>> types = new LinkedHashSet<>();
        types.add(method.getReturnType());

        Type generic = method.getGenericReturnType();
        if (generic instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (argument instanceof Class<?> type) {
                    types.add(type);
                }
            }
        }
        return types;
    }

    private static List<Class<?>> classesIn(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".class"))
                    .filter(name -> !name.equals("package-info.class"))
                    .filter(name -> !name.contains("$"))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .<Class<?>>map(simpleName -> load(directory, simpleName))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not scan " + directory, e);
        }
    }

    private static Class<?> load(Path directory, String simpleName) {
        String packageName = directory.subpath(1, directory.getNameCount())
                .toString()
                .replace('\\', '.')
                .replace('/', '.')
                .substring("classes.".length());
        try {
            return Class.forName(packageName + "." + simpleName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("compiled class not loadable: " + simpleName, e);
        }
    }
}
