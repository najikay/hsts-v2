package server.features.bank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bank is registered on the production router, checked by reading the assembly itself.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>PR #25 merged a question bank whose three write verbs were never registered. 4132 tests
 * passed. {@code BankHandlersTest.registersTheThreeWriteVerbs} called {@code registerOn} against
 * a real {@link server.core.MessageRouter} and asserted all three verbs landed; it passed while
 * the production server never constructed the class at all. From inside its own unit test a
 * handler nobody instantiates is indistinguishable from one that is wired.
 *
 * <p>That blind spot is not the bank's. <b>Every feature in {@code defaultRouter} has it</b>, and
 * the only thing standing between us and a repeat is somebody remembering.
 *
 * <h2>Why it reads source instead of bytecode</h2>
 *
 * <p>The first version of this test scanned the compiled constant pool of {@code HSTSServer.class}
 * for {@code BankHandlers}, {@code QuestionService} and {@code QuestionIdAllocator}. <b>It was a
 * decoration test and a cold audit caught it.</b> All three of those names are put in the pool by
 * the {@code new} expressions, so
 *
 * <pre>{@code new BankHandlers(sessionFactory, new QuestionService(...));   // never registered}</pre>
 *
 * passed every assertion while the bank was exactly as unreachable as it was in #25. Verified by
 * planting it: seven tokens removed, build green.
 *
 * <p>Bytecode cannot be made to answer this cheaply. The only pool entry that encodes registration
 * is a {@code Methodref} to {@code BankHandlers.registerOn}, and a {@code Methodref} is a pair of
 * indices, not text. Checked with {@code javap -v}: the string {@code registerOn} occurs as
 * <b>exactly one</b> {@code Utf8} entry, shared through one {@code NameAndType} by all eight
 * features that register. There is no byte sequence a crude scan could look for that says "the
 * bank registers" rather than "somebody registers".
 *
 * <p>So this reads {@code HSTSServer.java} and asserts that inside {@code defaultRouter}, a
 * {@code BankHandlers} is constructed and {@code registerOn(router)} is called <em>on it</em>, in
 * one statement. Comments are stripped first, so commenting the line out fails like deleting it.
 *
 * <h2>What it does NOT prove, stated so nobody trusts it further than it goes</h2>
 *
 * <p>It proves the registration is written in the method. It does not run it. {@code defaultRouter}
 * calls {@code HibernateUtil.sessionFactory()}, which boots a HikariCP pool against the real
 * {@code hsts_db} on first call - the ordering rule in that method's own javadoc, and the reason
 * every existing test of {@link server.core.HSTSServer} uses the bring-your-own-router constructor.
 * Nothing automated here can prove a teacher can add a question; the manual pass covers that.
 *
 * <p>It is also deliberately literal. Extracting the block into a {@code registerBankFeature}
 * helper the way the four neighbouring features do would fail this test <em>even if the helper is
 * called</em>. That is the safe direction and it is intended: the failure is loud, it is fixed by
 * pointing this test at the helper, and it is preferable to a check that quietly permits the one
 * refactor that hides an uncalled method.
 */
class BankWiringGuardTest {

    private static final Path SOURCE =
            Path.of("src", "main", "java", "server", "core", "HSTSServer.java");

    private static final String METHOD = "private MessageRouter defaultRouter(";
    private static final String END_OF_METHOD = "return router;";

    /** The whole registration statement, however it is wrapped across lines. */
    private static final Pattern REGISTRATION = Pattern.compile(
            "new BankHandlers\\(.*?\\.registerOn\\(router\\);", Pattern.DOTALL);

    @Test
    @DisplayName("defaultRouter constructs BankHandlers and calls registerOn on it")
    void theBankIsRegisteredOnTheProductionRouter() {
        assertThat(wiresTheBank(defaultRouterBody()))
                .as("HSTSServer.defaultRouter must construct BankHandlers over a QuestionService "
                        + "AND call registerOn(router) on it, in one statement. Constructing "
                        + "without registering is what shipped in #25: the three write verbs "
                        + "compile, their unit tests pass, and no teacher can reach them.")
                .isTrue();
    }

    /**
     * The teeth. Each mutation is a way the wiring has actually been, or plausibly could be,
     * broken; the check has to reject every one of them or it is not checking anything.
     *
     * <p>The second is the one that matters most: it is what the previous version of this test
     * let through, and it is #25's defect exactly.
     */
    @Test
    @DisplayName("the check rejects every way of not registering the bank")
    void theCheckRejectsTheMutations() {
        String body = defaultRouterBody();

        assertThat(wiresTheBank(mutate(body, Mutation.DELETED)))
                .as("statement deleted outright - #25 as it merged")
                .isFalse();
        assertThat(wiresTheBank(mutate(body, Mutation.CONSTRUCTED_NOT_REGISTERED)))
                .as("constructed and never registered - passed the bytecode version of this "
                        + "test, which is why that version was thrown away")
                .isFalse();
        assertThat(wiresTheBank(mutate(body, Mutation.COMMENTED_OUT)))
                .as("commented out - the shape a 'temporarily disable this' leaves behind")
                .isFalse();
        assertThat(wiresTheBank(mutate(body, Mutation.EXTRACTED_TO_HELPER)))
                .as("moved into a helper - flagged on purpose, since an EXTRACTED helper and an "
                        + "extracted-but-never-called helper look identical from here")
                .isFalse();
    }

    @Test
    @DisplayName("the mutations are real: each one changes the source it is applied to")
    void theMutationsActuallyChangeSomething() {
        // Without this, a mutation whose pattern stopped matching would silently become a
        // no-op, and the test above would be asserting isFalse() against the UNMUTATED body -
        // which would then be failing for the wrong reason, or worse, quietly passing if the
        // check ever regressed. Every mutation must be observable.
        String body = defaultRouterBody();

        for (Mutation mutation : Mutation.values()) {
            assertThat(mutate(body, mutation))
                    .as("mutation %s did not change the source; its pattern has gone stale",
                            mutation)
                    .isNotEqualTo(body);
        }
    }

    // ===================== the check itself =====================

    /**
     * @param body the source text of {@code defaultRouter}
     * @return whether a {@code BankHandlers} is built and registered in a single statement
     */
    private static boolean wiresTheBank(String body) {
        // Comments out first: a commented-out registration must read as no registration.
        String dense = stripComments(body).replaceAll("\\s+", "");

        int constructed = dense.indexOf("newBankHandlers(");
        if (constructed < 0) {
            return false;
        }
        int registered = dense.indexOf(".registerOn(router)", constructed);
        if (registered < 0) {
            return false;
        }
        // The two have to be the same statement. Every other feature in the method also calls
        // registerOn(router), so without this a stray `new BankHandlers(...);` followed later by
        // somebody else's registration would satisfy both searches.
        int endOfStatement = dense.indexOf(';', constructed);
        return endOfStatement > registered;
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

    private static String mutate(String body, Mutation mutation) {
        Matcher found = REGISTRATION.matcher(body);
        assertThat(found.find())
                .as("the registration statement is not in defaultRouter, so there is nothing to "
                        + "mutate; the check under test would be vacuous")
                .isTrue();

        String statement = found.group();
        String replacement = switch (mutation) {
            case DELETED -> "";
            case CONSTRUCTED_NOT_REGISTERED ->
                    statement.replace(".registerOn(router);", ".toString();");
            case COMMENTED_OUT -> "// " + statement.replace("\n", "\n// ");
            case EXTRACTED_TO_HELPER -> "registerBankFeature(router, sessionFactory, clock);";
        };
        return body.replace(statement, replacement);
    }
}
