package server.features.exambuild;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExamHandlers} has to be <b>assembled</b>, not merely written (E7.9).
 *
 * <p><b>This test is red on the PR that lands it, and that is the design.</b> Registration lives
 * in {@code server/core/HSTSServer.java}, which is not Member A's to edit; the handlers PR lands
 * the feature and the lead's assembly commit turns this green, exactly as #41 and #43 did for the
 * bank. A red build here says "the verbs exist and nobody can reach them", which is a true and
 * useful thing for CI to be saying in the window between the two commits.
 *
 * <h2>What it guards against, which is not what it looks like</h2>
 *
 * <p>The failure this exists for is not a missing class. It is a class that is built, unit-tested
 * to a high number, and never put on the router - #25's defect, where every verb compiled, every
 * test passed, and no teacher could reach any of it. Unit tests cannot see that hole from inside,
 * because from inside a handler's own test the handler is obviously reachable.
 *
 * <p>It reads the server's <em>source</em> rather than its bytecode, for the reason
 * {@code BankWiringGuardTest} records at length: a constant-pool scan cannot tell
 * {@code new ExamHandlers(...)} that is registered from one that is constructed and dropped, so
 * the bytecode version of this check passed on exactly the bug it was written to catch.
 *
 * <p>It is deliberately literal. Moving the statement into a {@code registerExamBuildFeature}
 * helper fails this test <em>even when the helper is called</em>. That is the safe direction: the
 * failure is loud and is fixed by pointing this test at the helper, which is preferable to a check
 * that quietly permits the one refactor that hides an uncalled method.
 */
class ExamBuildWiringGuardTest {

    private static final Path SOURCE =
            Path.of("src", "main", "java", "server", "core", "HSTSServer.java");

    /** This feature's own package, asked directly rather than guessed at by class name. */
    private static final Path FEATURE_SOURCES =
            Path.of("src", "main", "java", "server", "features", "exambuild");

    private static final String METHOD = "private MessageRouter defaultRouter(";
    private static final String END_OF_METHOD = "return router;";

    /**
     * The assembly this guard is waiting for, written out so the red build is actionable rather
     * than merely red. Three things in it are not obvious:
     *
     * <ul>
     *   <li>{@code ExamService} takes <b>two</b> repositories. The second,
     *       {@code ExamRepository}, serves {@code EXAM_LIST}'s question count, which
     *       {@code AuthoredVersionRow} deliberately does not carry.</li>
     *   <li>{@code ExamHandlers} needs the {@code ApprovalService} <b>instance</b> for
     *       {@code EXAM_SUBMIT}'s after-commit hook. {@code registerApprovalFeature} currently
     *       constructs it inline and returns {@code void}, so it has to hand the object back the
     *       way {@code registerGradingFeature} already hands back its listener.</li>
     *   <li>The {@code EditLockGuard} must wrap the <b>same</b> {@code EditLockService} instance
     *       registered above, not a second one: locks live in that object's map, and a copy would
     *       consult an empty world, refuse nothing, and look exactly like this line.</li>
     * </ul>
     */
    private static final String THE_LINE_THAT_IS_MISSING = """
            ApprovalService approvals = registerApprovalFeature(router, notifications, \
            sessionFactory);
            new ExamHandlers(sessionFactory,
                    new ExamService(new ExamBuildRepository(), new ExamRepository(),
                            new CourseRepository(), new EditLockGuard(locks), clock),
                    approvals)
                    .registerOn(router);""";

    /**
     * One handler class that has to be assembled, and what it would cost to lose it.
     *
     * @param type the handler class, as written in the source
     * @param cost what a teacher loses if this registration goes missing
     */
    private record Wiring(String type, String cost) {

        /** The whole registration statement, however it is wrapped across lines. */
        Pattern statement() {
            return Pattern.compile("new " + type + "\\(.*?\\.registerOn\\(router\\);",
                    Pattern.DOTALL);
        }

        /** What the construction looks like once whitespace is gone. */
        String construction() {
            return "new" + type + "(";
        }
    }

    /**
     * One entry today. Whatever serves {@code EXAM_AUTO_COMPOSE} joins it when contract §7 is
     * ruled on and the generator lands; {@link #everyExamBuilderHandlerIsListed} is what makes
     * that addition impossible to forget, and it does not depend on what the class is named.
     */
    private static final List<Wiring> WIRINGS = List.of(
            new Wiring("ExamHandlers",
                    "no teacher can see her exams, open one, create one, save one, revise one or "
                            + "submit one for approval - the whole builder is unreachable, and "
                            + "F4.1's approval queue never receives anything"));

    @Test
    @DisplayName("defaultRouter constructs ExamHandlers and calls registerOn on it")
    void theExamBuilderIsRegistered() {
        String body = defaultRouterBody();

        for (Wiring wiring : WIRINGS) {
            assertThat(wires(body, wiring))
                    .as("HSTSServer.defaultRouter must construct %s AND call registerOn(router) "
                            + "on it, in one statement. Without it: %s.%n%nThe assembly this is "
                            + "waiting for:%n%n%s%n", wiring.type(), wiring.cost(),
                            THE_LINE_THAT_IS_MISSING)
                    .isTrue();
        }
    }

    /**
     * The teeth. Each mutation is a way the wiring could be broken; the check has to reject every
     * one of them or it is not checking anything.
     *
     * <p>{@code CONSTRUCTED_NOT_REGISTERED} is the one that matters: it is what the bytecode
     * version of this check let through, and it is #25's defect exactly.
     */
    @Test
    @DisplayName("the check rejects every way of not registering the exam builder")
    void theCheckRejectsTheMutations() {
        String body = defaultRouterBody();

        for (Wiring wiring : WIRINGS) {
            assertThat(wires(mutate(body, wiring, Mutation.DELETED), wiring))
                    .as("%s: statement deleted outright", wiring.type())
                    .isFalse();
            assertThat(wires(mutate(body, wiring, Mutation.CONSTRUCTED_NOT_REGISTERED), wiring))
                    .as("%s: constructed and never registered - the verbs compile, their unit "
                            + "tests pass, and nobody can reach them", wiring.type())
                    .isFalse();
            assertThat(wires(mutate(body, wiring, Mutation.COMMENTED_OUT), wiring))
                    .as("%s: commented out - the shape a 'temporarily disable this' leaves "
                            + "behind", wiring.type())
                    .isFalse();
            assertThat(wires(mutate(body, wiring, Mutation.EXTRACTED_TO_HELPER), wiring))
                    .as("%s: moved into a helper - flagged on purpose, since an extracted helper "
                            + "and an extracted-but-never-called helper look identical from here",
                            wiring.type())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the mutations are real: each one changes the source it is applied to")
    void theMutationsActuallyChangeSomething() {
        // Without this, a mutation whose pattern stopped matching would silently become a no-op
        // and the test above would be asserting isFalse() against the UNMUTATED body - failing
        // for the wrong reason, or quietly passing if the check ever regressed.
        String body = defaultRouterBody();

        for (Wiring wiring : WIRINGS) {
            for (Mutation mutation : Mutation.values()) {
                assertThat(mutate(body, wiring, mutation))
                        .as("%s: mutation %s did not change the source; its pattern has gone "
                                + "stale", wiring.type(), mutation)
                        .isNotEqualTo(body);
            }
        }
    }

    @Test
    @DisplayName("every handler class in this feature is on this test's list")
    void everyExamBuilderHandlerIsListed() {
        // The gap this closes: adding a second handler class to this feature and registering it
        // correctly leaves every assertion above green while the new one is guarded by nothing.
        // A list kept in step by memory is the shape method rule 9 warns about.
        //
        // IT ASKS THE FILESYSTEM, NOT defaultRouter, and the first version of this test did the
        // opposite. That version matched `new (\w*Exam\w*Handlers?)\(` against the router's
        // source and its own comment claimed it was "what will catch AutoComposeHandlers" -
        // which contains no "Exam" and could never have matched it. Worse, it currently matches
        // nothing at all, so the loop body never ran and the test asserted precisely nothing
        // while reading as coverage. That is the same too-narrow-regex defect a cold audit
        // already found in BankWiringGuardTest, reached a second time by writing the same shape.
        //
        // Scanning this package's own directory removes the guess: a handler in this feature is
        // a file in this feature, whatever somebody decides to call it, and E9/E10/E11's exam
        // handlers live in other packages so they cannot be dragged onto this list.
        List<String> handlerClasses = handlerClassesInThisFeature();

        assertThat(handlerClasses)
                .as("no handler class found in %s; if the package moved, this guard has to "
                        + "follow it rather than silently pass", FEATURE_SOURCES)
                .isNotEmpty();
        assertThat(WIRINGS).extracting(Wiring::type)
                .as("every handler class in this feature must be guarded here; add the missing "
                        + "one to WIRINGS with what its absence would cost")
                .containsExactlyInAnyOrderElementsOf(handlerClasses);
    }

    /** @return every {@code *Handlers.java} in this feature's own package, by simple name */
    private static List<String> handlerClassesInThisFeature() {
        try (Stream<Path> files = Files.list(FEATURE_SOURCES)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith("Handlers.java") || name.endsWith("Handler.java"))
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + FEATURE_SOURCES.toAbsolutePath()
                    + "; this guard reads the feature's own directory, so it runs from the "
                    + "repository root", e);
        }
    }

    // ===================== the check itself =====================

    /**
     * @param body   the source text of {@code defaultRouter}
     * @param wiring the handler to look for
     * @return whether it is built and registered in a single statement
     */
    private static boolean wires(String body, Wiring wiring) {
        // Comments out first: a commented-out registration must read as no registration.
        String dense = stripComments(body).replaceAll("\\s+", "");

        int constructed = dense.indexOf(wiring.construction());
        if (constructed < 0) {
            return false;
        }
        int registered = dense.indexOf(".registerOn(router)", constructed);
        if (registered < 0) {
            return false;
        }
        // The two have to be the same statement. Every other feature in the method also calls
        // registerOn(router), so without this a stray `new ExamHandlers(...);` followed later by
        // somebody else's registration would satisfy both searches.
        int endOfStatement = dense.indexOf(';', constructed);
        return endOfStatement >= 0 && endOfStatement > registered;
    }

    private static String stripComments(String source) {
        String withoutBlocks = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return withoutBlocks.replaceAll("//[^\\n]*", "");
    }

    private static String defaultRouterBody() {
        String source = read(SOURCE);

        int start = source.indexOf(METHOD);
        assertThat(start)
                .as("could not find `%s` in %s; if the method was renamed this guard has to "
                        + "follow it rather than silently pass", METHOD, SOURCE)
                .isNotNegative();

        int end = source.indexOf(END_OF_METHOD, start);
        assertThat(end)
                .as("could not find `%s` closing defaultRouter", END_OF_METHOD)
                .isNotNegative();

        return source.substring(start, end);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file.toAbsolutePath()
                    + "; this guard reads the server's own source, so it runs from the "
                    + "repository root", e);
        }
    }

    // ===================== mutations =====================

    private enum Mutation {
        DELETED,
        CONSTRUCTED_NOT_REGISTERED,
        COMMENTED_OUT,
        EXTRACTED_TO_HELPER
    }

    private static String mutate(String body, Wiring wiring, Mutation mutation) {
        Matcher found = wiring.statement().matcher(body);
        assertThat(found.find())
                .as("%s's registration is not in defaultRouter, so there is nothing to mutate "
                        + "and the check under test would be vacuous. THIS IS THE EXPECTED "
                        + "FAILURE until the assembly commit lands; see this class's javadoc and "
                        + "the message on theExamBuilderIsRegistered.", wiring.type())
                .isTrue();

        String statement = found.group();
        String replacement = switch (mutation) {
            case DELETED -> "";
            case CONSTRUCTED_NOT_REGISTERED ->
                    statement.replace(".registerOn(router);", ".toString();");
            case COMMENTED_OUT -> "// " + statement.replace("\n", "\n// ");
            case EXTRACTED_TO_HELPER ->
                    "registerExamBuildFeature(router, sessionFactory, locks, clock);";
        };
        return body.replace(statement, replacement);
    }
}
