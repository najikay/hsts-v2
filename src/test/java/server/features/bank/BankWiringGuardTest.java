package server.features.bank;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both halves of the bank are registered on the production router, checked by reading the
 * assembly itself.
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
 * is a {@code Methodref} to {@code registerOn}, and a {@code Methodref} is a pair of indices, not
 * text. Checked with {@code javap -v}: the string {@code registerOn} occurs as <b>exactly one</b>
 * {@code Utf8} entry, shared through one {@code NameAndType} by every feature that registers.
 * There is no byte sequence a crude scan could look for that says "the bank registers" rather than
 * "somebody registers".
 *
 * <p>So this reads {@code HSTSServer.java} and asserts that inside {@code defaultRouter}, each
 * handler class is constructed and {@code registerOn(router)} is called <em>on it</em>, in one
 * statement. Comments are stripped first, so commenting a line out fails like deleting it.
 *
 * <h2>What it does NOT prove, stated so nobody trusts it further than it goes</h2>
 *
 * <p>It proves the registrations are written in the method. It does not run them.
 * {@code defaultRouter} calls {@code HibernateUtil.sessionFactory()}, which boots a HikariCP pool
 * against the real {@code hsts_db} on first call - the ordering rule in that method's own javadoc,
 * and the reason every existing test of {@link server.core.HSTSServer} uses the
 * bring-your-own-router constructor. Nothing automated here can prove a teacher can add or open a
 * question; the manual pass covers that.
 *
 * <p>It is also deliberately literal. Extracting a block into a {@code registerBankFeature} helper
 * the way the four neighbouring features do would fail this test <em>even if the helper is
 * called</em>. That is the safe direction and it is intended: the failure is loud, it is fixed by
 * pointing this test at the helper, and it is preferable to a check that quietly permits the one
 * refactor that hides an uncalled method.
 */
class BankWiringGuardTest {

    private static final Path SOURCE =
            Path.of("src", "main", "java", "server", "core", "HSTSServer.java");

    private static final String METHOD = "private MessageRouter defaultRouter(";
    private static final String END_OF_METHOD = "return router;";

    /**
     * One handler class that has to be assembled, and what it would cost to lose it.
     *
     * @param type    the handler class, as written in the source
     * @param cost    what a teacher loses if this registration goes missing
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
     * Both halves, listed rather than assumed. Adding a third bank handler without adding it here
     * leaves it unguarded, which is why {@link #everyBankHandlerInTheSourceIsListed} exists.
     */
    private static final List<Wiring> WIRINGS = List.of(
            new Wiring("BankHandlers",
                    "no teacher can add, edit or delete a question - #25 exactly"),
            new Wiring("BankReadHandlers",
                    "the bank list, the detail pane and the version history all answer nothing"));
    // A third entry stood here: LegacyQuestionHandlers, on the list precisely because it was
    // legacy. It was a bank handler in this assembly, it answered GET_ALL_QUESTIONS and
    // UPDATE_QUESTION, and its Question.answer was a real key the leak scan could not see
    // (contract section 1). The entry was written to fail the build the day the pair retired,
    // so that its own removal could not be forgotten. The retirement PR landed (section 7.4)
    // and this shrink is that diff. Two entries, and both of them are live code.

    @Test
    @DisplayName("defaultRouter constructs each bank handler and calls registerOn on it")
    void bothHalvesOfTheBankAreRegistered() {
        String body = defaultRouterBody();

        for (Wiring wiring : WIRINGS) {
            assertThat(wires(body, wiring))
                    .as("HSTSServer.defaultRouter must construct %s AND call registerOn(router) "
                            + "on it, in one statement. Without it: %s. Constructing without "
                            + "registering is what shipped in #25 - the verbs compile, their unit "
                            + "tests pass, and nobody can reach them.", wiring.type(),
                            wiring.cost())
                    .isTrue();
        }
    }

    /**
     * The teeth. Each mutation is a way the wiring has actually been, or plausibly could be,
     * broken; the check has to reject every one of them or it is not checking anything.
     *
     * <p>The second is the one that matters most: it is what the previous version of this test
     * let through, and it is #25's defect exactly.
     */
    @Test
    @DisplayName("the check rejects every way of not registering a bank handler")
    void theCheckRejectsTheMutations() {
        String body = defaultRouterBody();

        for (Wiring wiring : WIRINGS) {
            assertThat(wires(mutate(body, wiring, Mutation.DELETED), wiring))
                    .as("%s: statement deleted outright - #25 as it merged", wiring.type())
                    .isFalse();
            assertThat(wires(mutate(body, wiring, Mutation.CONSTRUCTED_NOT_REGISTERED), wiring))
                    .as("%s: constructed and never registered - passed the bytecode version of "
                            + "this test, which is why that version was thrown away",
                            wiring.type())
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
        // Without this, a mutation whose pattern stopped matching would silently become a
        // no-op, and the test above would be asserting isFalse() against the UNMUTATED body -
        // which would then be failing for the wrong reason, or worse, quietly passing if the
        // check ever regressed. Every mutation must be observable.
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
    @DisplayName("every bank handler in the assembly is on this test's list")
    void everyBankHandlerInTheSourceIsListed() {
        // The gap this closes: adding another bank handler class and registering it correctly
        // leaves every assertion above green while the new one is guarded by nothing. A list
        // that has to be kept in step by memory is the shape rule 9 warns about, so the source
        // is asked instead of the author being trusted.
        //
        // The first version of this pattern was `new (Bank\w*Handlers)\(` and a cold audit found
        // its counterexample sitting in the very method it scans: LegacyQuestionHandlers was a
        // bank handler in that assembly and the pattern could not see it, so the universal in
        // the DisplayName was false on the day it was written. Widened to anything named for the
        // bank or for questions, singular or plural. That handler has since retired, but the
        // widening stays: it is what will catch the BankTopicsHandler section 7.6 has already
        // ruled is coming, and narrowing it back would re-open the hole the audit found.
        Matcher found = Pattern.compile("new (\\w*(?:Bank|Question)\\w*Handlers?)\\(")
                .matcher(defaultRouterBody());

        while (found.find()) {
            String type = found.group(1);
            assertThat(WIRINGS).extracting(Wiring::type)
                    .as("%s is assembled in defaultRouter but this test does not guard it; add "
                            + "it to WIRINGS with what its absence would cost", type)
                    .contains(type);
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
        // registerOn(router), so without this a stray `new BankHandlers(...);` followed later by
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
                        + "and the check under test would be vacuous", wiring.type())
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
