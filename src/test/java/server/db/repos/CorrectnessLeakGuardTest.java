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
 * <h2>The review feature arrived, and it landed differently</h2>
 *
 * <p>The hypothetical above is now E13.2's checked form, specified in the frozen grading wire
 * contract. It was given a sanctioned suffix of its own when the contract was frozen,
 * {@code ForCheckedForm} — and then it was built without one, because {@code CHECKED_FORM_GET}
 * shares {@code GradeReviewService}'s assembler with the teacher's review and therefore reads
 * through {@code findVersionsForGrading}. The suffix sat in this list for a day with no method
 * anywhere named for it.
 *
 * <p>It has been removed (2026-08-23, lead's ruling on Member B's PR 17 finding). The javadoc
 * on {@link #SANCTIONED_SUFFIXES} says a suffix stops being licensed when the feature that
 * licensed it goes away, and a licensed name with no readers is the same hole from the other
 * direction: it is a permission standing open that nobody is using and nobody would notice
 * being used. The checked form is now served through the grading assembler under its own three
 * gates, and the contract says so.
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
     * <h2>{@code ForCheckedForm} — withdrawn, 2026-08-23</h2>
     *
     * <p>Sanctioned when the E12/E13 wire contract was frozen, for reads serving a student her
     * own marked paper, and <b>removed once E13.4 shipped without using it</b>. The checked
     * form reuses {@code GradeReviewService.answers}, the same assembler the teacher's review
     * uses, so its read is {@code findVersionsForGrading} and no method was ever named
     * {@code …ForCheckedForm}.
     *
     * <p><b>What licenses a suffix is not this list.</b> A suffix is a naming convention, not a
     * guard: it makes a leak deliberate rather than accidental. What actually enforces the
     * checked form's three conditions is E13.1's authorization tests, and those are unaffected
     * by this removal — they were always the licence, and they still hold.
     *
     * <p>Removing it is the same rule this javadoc already states in the other direction: a
     * suffix comes out of the list when the feature that licensed it is no longer behind it.
     * An unused sanctioned name is a permission nobody is exercising and nobody is watching,
     * and the next read that wanted to carry an answer key to a student could have taken it
     * without a single test noticing. Found by Member B (PR 17) and ruled by the lead.
     *
     * <h2>{@code ForGrading} — the server comparing a selection against the key (E12)</h2>
     *
     * <p>Added when E12.1's auto-grading landed. Scoring an attempt <b>is</b> comparing what the
     * student chose against what is right, so a grading read carries an answer key by definition;
     * so does {@code GRADE_REVIEW_GET}, which shows a teacher a marked paper. Neither is
     * authoring — nobody is composing a question — and neither is the student's own checked form,
     * which is a different audience under different conditions. Without a third name both would
     * have faced the same lie-or-suppression choice {@code ForCheckedForm} was created to avoid.
     *
     * <p>Since 2026-08-23 it is also the read behind the student's checked form, which shares
     * the assembler. The name still describes the read honestly — it is the grading read — and
     * what stands in front of it for that caller is E13.4's three gates rather than a name.
     *
     * <p><b>Audience:</b> grading services, the teacher-facing review and, through the shared
     * assembler, the student's own checked form. <b>Licensed by:</b> the frozen contract's rule
     * for teacher verbs —
     * {@code requireRole(TEACHER, COORDINATOR)} plus ownership resolved from repositories, the
     * caller being the execution's executing teacher or the exam's author. <b>Enforced by:</b>
     * the E12 handler tests that prove those gates, and {@code AutoGraderTest}, which proves the
     * key is used to score and never returned. On the same terms as the other two, if those tests
     * go away this suffix stops being licensed and comes back out of this list.
     */
    private static final List<String> SANCTIONED_SUFFIXES =
            List.of("ForAuthoring", "ForGrading");

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
    @DisplayName("any repository read that hands back an answer key names the audience it serves")
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
    @DisplayName("the sanctioned suffixes name two audiences, and nothing that merely looks like one")
    void eachSanctionedSuffixNamesOneRealAudience() {
        // Adding one is a deliberate act with a licensing argument behind it, which is what
        // SANCTIONED_SUFFIXES' javadoc records — two: authoring (E2.12) and grading (E12). A
        // read that merely mentions the words is not sanctioned: the suffix has to be the end
        // of the name.
        assertThat(SANCTIONED_SUFFIXES)
                .containsExactly("ForAuthoring", "ForGrading");

        assertThat(isSanctioned("findVersionForAuthoring")).isTrue();
        assertThat(isSanctioned("findVersionsForGrading")).isTrue();

        assertThat(isSanctioned("findForAuthoringPreview")).isFalse();
        assertThat(isSanctioned("findForGradingQueueBanner")).isFalse();
        assertThat(isSanctioned("findForReview")).isFalse();
        assertThat(isSanctioned("findForTakeExam")).isFalse();
    }

    @Test
    @DisplayName("ForCheckedForm is withdrawn: a name nobody reads through is a permission "
            + "standing open")
    void checkedFormSuffixIsNoLongerLicensed() {
        // E13.4 shipped sharing GradeReviewService's assembler, so no repository method is
        // named for the checked form and the suffix licensed nothing. This asserts the removal
        // rather than only the two that remain, because a merge that restored the entry would
        // otherwise put the hole back silently — the same reason the list is spelled out above.
        assertThat(SANCTIONED_SUFFIXES).doesNotContain("ForCheckedForm");
        assertThat(isSanctioned("findAnswersForCheckedForm")).isFalse();

        List<String> named = classesIn(COMPILED_REPOSITORIES).stream()
                .flatMap(repository -> Arrays.stream(repository.getDeclaredMethods()))
                .map(Method::getName)
                .filter(name -> name.endsWith("ForCheckedForm"))
                .toList();

        assertThat(named)
                .as("nothing reads through the withdrawn name; if this ever fails, the read "
                        + "came back and the licensing argument has to come back with it")
                .isEmpty();
    }

    @Test
    @DisplayName("that naming check can fail - the key-bearing reads really do trip it")
    void theNamingCheckHasTeeth() {
        // Without this, the test above would also pass if carriesCorrectness() stopped
        // recognising anything at all - which is exactly what a rename of
        // QuestionVersion.correctAnswer would cause.
        assertThat(CorrectnessNames.carriesCorrectness(QuestionVersion.class)).isTrue();
        assertThat(CorrectnessNames.carriesCorrectness(TakeExamQuestion.class)).isFalse();
        assertThat(CorrectnessNames.carriesCorrectness(Question.class)).isFalse();

        List<String> keyBearingReads = Arrays.stream(QuestionRepository.class.getDeclaredMethods())
                .filter(method -> returnedTypes(method).stream()
                        .anyMatch(CorrectnessNames::carriesCorrectness))
                .map(Method::getName)
                .toList();

        // This list is the inventory of every read that hands back an answer key, and it is
        // spelled out rather than counted so that adding one is a visible edit here — the same
        // deliberateness the suffix list itself is designed for. Three authoring reads (E2.12
        // plus E8.4's approval preview) and one grading read (E12.1).
        //
        // findAnswerKeyForAuthoring joined the list in E8: a coordinator deciding whether to
        // approve an exam has to be able to check that its answers are right, which is
        // authoring work on somebody else's exam rather than a fourth audience. It is reached
        // by EXAM_PREVIEW_GET only, behind requireRole(TEACHER, COORDINATOR, PRINCIPAL), then
        // either the caller authored the version or requireCoordinatorOf passes on its subject
        // (licence corrected 2026-08-21, Member A's rule-5 pass: the earlier wording was
        // narrower than the code). ApprovalServiceTest proves every refusal, including the
        // plain teacher who neither authored nor coordinates.
        // The PRINCIPAL joined that role list on 2026-08-30 (live session, U-44, APPROVAL
        // amendment A1) and skips the scope test rather than passing it: F9.3 gives her the
        // whole school, so there is no slice for a guard to compute. She is not a fourth
        // audience for this read in kind — she is staff, and the boundary this guard defends
        // is students, which is the same reasoning that sent her QuestionDetail with its key
        // on 2026-08-21. She reaches no verb in the approval group that writes.
        // findVersionsForAuthoring joined the list in E6's read half (2026-08-22). Its audience
        // is the same one findVersionForAuthoring and findLatestVersionForAuthoring already
        // serve, and it is not a new one: E6.12's version history panel, opened by a teacher on
        // her own bank behind QUESTION_VERSIONS, whose handler answers NOT_FOUND for any course
        // outside her reachable set. The key travels because the panel shows an old version
        // exactly as it was written, and a history that hid which answer used to be correct
        // would be a history of the wrong thing.
        //
        // Worth stating because it is the widest of the three: the other two return one version,
        // this returns all of them. That changes the volume of a leak, not its possibility, and
        // the guard on it is the same single one.
        assertThat(keyBearingReads)
                .as("every key-bearing read is accounted for, and each names its audience")
                .containsExactlyInAnyOrder(
                        "findVersionForAuthoring",
                        "findVersionsForAuthoring",
                        "findLatestVersionForAuthoring",
                        "findAnswerKeyForAuthoring",
                        "findVersionsForGrading");
        assertThat(keyBearingReads).allMatch(CorrectnessLeakGuardTest::isSanctioned);
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
