package server.db.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.Question;
import server.db.entities.Subject;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The loader's own behaviour, proven without a row of real seed content (E2.15).
 *
 * <p>The section list is a constructor argument precisely so this is possible: the modes, the
 * confirmation, the summary and the transaction boundary are the parts a reviewer has to
 * trust, and they are easier to trust when they are tested against three fake sections than
 * when they are entangled with 1500 rows of transcription.
 *
 * <p>Inherits {@link RepositoryTestBase}'s fixture, which is the point rather than a
 * convenience: it puts real rows in the database that a reseed would destroy, so "the wipe
 * did not run" and "the wipe rolled back" are observable rather than assumed.
 */
abstract class SeedLoaderContract extends RepositoryTestBase {

    private static final Instant ANCHOR = Instant.parse("2026-08-20T15:30:00Z");
    private static final Clock FIXED = Clock.fixed(ANCHOR, ZoneOffset.UTC);

    @Test
    @DisplayName("LOAD_IF_MISSING runs the sections and leaves existing rows alone")
    void loadIfMissingDoesNotWipe() {
        long usersBefore = countUsers();

        SeedSummary summary = loader(insertSubject("30", "אזרחות"))
                .load(SeedMode.LOAD_IF_MISSING, Confirmation.refused());

        assertThat(summary.outcome()).isEqualTo(SeedOutcome.LOADED);
        assertThat(summary.rowsByTable()).containsEntry("subjects", 1);
        assertThat(countUsers()).isEqualTo(usersBefore);
    }

    @Test
    @DisplayName("LOAD_IF_MISSING never asks for confirmation, because it destroys nothing")
    void loadIfMissingDoesNotPrompt() {
        // A loader that prompted here would train the operator to click through the prompt
        // that matters.
        List<String> prompts = new ArrayList<>();

        loader(insertSubject("30", "אזרחות")).load(SeedMode.LOAD_IF_MISSING, prompt -> {
            prompts.add(prompt);
            return true;
        });

        assertThat(prompts).isEmpty();
    }

    @Test
    @DisplayName("a run that inserts nothing is UNCHANGED, not LOADED with a zero")
    void loadIfMissingWithNothingToDoIsUnchanged() {
        SeedSummary summary = loader(section("noop", context -> context.recordInserts("users", 0)))
                .load(SeedMode.LOAD_IF_MISSING, Confirmation.refused());

        assertThat(summary.outcome()).isEqualTo(SeedOutcome.UNCHANGED);
        assertThat(summary.totalRows()).isZero();
    }

    @Test
    @DisplayName("a declined reseed destroys nothing at all")
    void cancelledReseedLeavesTheDatabaseUntouched() {
        long usersBefore = countUsers();

        SeedSummary summary = loader(insertSubject("30", "אזרחות"))
                .load(SeedMode.RESEED, Confirmation.refused());

        assertThat(summary.outcome()).isEqualTo(SeedOutcome.CANCELLED);
        assertThat(summary.totalRows()).isZero();
        assertThat(countUsers())
                .as("declining must not delete anything, and must not run the sections either")
                .isEqualTo(usersBefore);
        assertThat(countSubjects("30")).isZero();
    }

    @Test
    @DisplayName("the confirmation is asked with the loader's own wording, not the caller's")
    void theLoaderOwnsThePromptText() {
        // Both entry points, the CLI flag and the E19.6 console button, must describe the
        // same destructive action identically. That only holds if neither writes the words.
        List<String> prompts = new ArrayList<>();

        loader(insertSubject("30", "אזרחות")).load(SeedMode.RESEED, prompt -> {
            prompts.add(prompt);
            return false;
        });

        assertThat(prompts).containsExactly(SeedLoader.RESEED_PROMPT);
        assertThat(SeedLoader.RESEED_PROMPT).contains("DELETE").contains("lost");
    }

    @Test
    @DisplayName("an approved reseed empties the database first, then loads")
    void approvedReseedWipesThenLoads() {
        assertThat(countUsers()).isPositive();

        SeedSummary summary = loader(insertSubject("30", "אזרחות"))
                .load(SeedMode.RESEED, Confirmation.preApproved());

        assertThat(summary.outcome()).isEqualTo(SeedOutcome.RESEEDED);
        assertThat(countUsers()).as("the fixture users are gone").isZero();
        assertThat(countSubjects("30")).as("and the section's row is there").isEqualTo(1);
    }

    @Test
    @DisplayName("a section that fails rolls back the wipe with it")
    void aFailedReseedLeavesTheOldDataInPlace() {
        // The failure mode this exists for happens on one machine and at the worst moment:
        // an operator reseeds the demo laptop, a section throws, and they are left with an
        // empty database minutes before a defense. Wipe and load share one transaction so
        // that cannot happen.
        long usersBefore = countUsers();

        assertThatThrownBy(() -> loader(insertSubject("30", "אזרחות"),
                section("explodes", context -> {
                    throw new IllegalStateException("seed section failed");
                })).load(SeedMode.RESEED, Confirmation.preApproved()))
                .as("Transactions.inTx rethrows the section's own exception unwrapped, so the "
                        + "operator sees which section failed rather than a wrapper")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("seed section failed");

        assertThat(countUsers())
                .as("the wipe must have rolled back with the failed load")
                .isEqualTo(usersBefore);
        assertThat(countSubjects("30")).isZero();
    }

    @Test
    @DisplayName("sections run in the order they were given")
    void sectionsRunInDependencyOrder() {
        // Order is the whole contract of the section list: a section may depend on everything
        // above it. If the loader ever ran them concurrently or sorted them, the first
        // symptom would be a foreign key violation deep in the content.
        List<String> ran = new ArrayList<>();

        loader(section("first", context -> ran.add("first")),
                section("second", context -> ran.add("second")),
                section("third", context -> ran.add("third")))
                .load(SeedMode.LOAD_IF_MISSING, Confirmation.refused());

        assertThat(ran).containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("every section shares one time anchor")
    void allSectionsSeeTheSameAnchor() {
        List<Instant> anchors = new ArrayList<>();

        loader(section("a", context -> anchors.add(context.times().anchor())),
                section("b", context -> anchors.add(context.times().anchor())))
                .load(SeedMode.LOAD_IF_MISSING, Confirmation.refused());

        assertThat(anchors).containsExactly(ANCHOR, ANCHOR);
    }

    @Test
    @DisplayName("counts from several sections writing one table are added together")
    void countsAccumulatePerTable() {
        SeedSummary summary = loader(insertSubject("30", "אזרחות"), insertSubject("40", "אנגלית"))
                .load(SeedMode.LOAD_IF_MISSING, Confirmation.refused());

        assertThat(summary.rowsByTable()).containsEntry("subjects", 2);
        assertThat(summary.totalRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("a missing dependency says the section order is wrong, not just 'not found'")
    void missingDependenciesNameTheRealProblem() {
        // These fire when a section runs before the section that creates what it needs. The
        // message matters more than the exception type: the alternative is a null travelling
        // into a persist and surfacing much later as a foreign key violation naming a column
        // nobody was thinking about.
        runInTx(session -> {
            assertThatThrownBy(() -> SeedLookup.requireUserId(session, "nobody.here"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no user 'nobody.here'")
                    .hasMessageContaining("section ran before");

            assertThatThrownBy(() -> SeedLookup.requireQuestionId(session, "99999"))
                    .hasMessageContaining("no question '99999'");

            assertThatThrownBy(() -> SeedLookup.requireExamId(session, "999999"))
                    .hasMessageContaining("no exam '999999'");

            assertThatThrownBy(() -> SeedLookup.requireQuestionVersionId(session, "99999", 1))
                    .hasMessageContaining("no question '99999'");
        });
    }

    @Test
    @DisplayName("asking for the latest version of a question that has none is an error")
    void latestVersionOfAVersionlessQuestionThrows() {
        // A Question row with no QuestionVersion should be impossible, so if composition ever
        // meets one the right answer is to stop rather than to compose an exam around a
        // question with no text.
        runInTx(session -> {
            Question orphan = new Question("11", (short) 999, "11999");
            session.persist(orphan);

            assertThatThrownBy(() -> SeedLookup.latestQuestionVersionNo(session, orphan.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("has no versions");
        });
    }

    @Test
    @DisplayName("the find forms answer empty rather than throwing, which is what drives idempotency")
    void findFormsAnswerEmpty() {
        runInTx(session -> {
            assertThat(SeedLookup.findUserId(session, "nobody.here")).isEmpty();
            assertThat(SeedLookup.findQuestionId(session, "99999")).isEmpty();
            assertThat(SeedLookup.findExamId(session, "999999")).isEmpty();
            assertThat(SeedLookup.findQuestionVersionId(session, -1L, 1)).isEmpty();
            assertThat(SeedLookup.findExamVersionId(session, -1L, 1)).isEmpty();
            assertThat(SeedLookup.subjectExists(session, "99")).isFalse();
            assertThat(SeedLookup.courseExists(session, "99")).isFalse();
        });
    }

    @Test
    @DisplayName("the production wiring loads the real dataset against the system clock")
    void standardWiresTheRealDataset() {
        // SeedLoader.standard is what the CLI and the E19.6 console button call. Nothing else
        // exercises it, so without this the one construction path that ships is untested.
        SeedSummary summary = SeedLoader.standard(factory())
                .load(SeedMode.RESEED, Confirmation.preApproved());

        assertThat(summary.outcome()).isEqualTo(SeedOutcome.RESEEDED);
        assertThat(summary.rowsByTable()).containsEntry("users", 18);
        assertThat(summary.rowsByTable()).containsEntry("questions", 40);
    }

    private SeedLoader loader(SeedSection... sections) {
        return new SeedLoader(factory(), FIXED, List.of(sections));
    }

    private static SeedSection insertSubject(String code, String name) {
        return section("subject " + code, context -> {
            context.session().persist(new Subject(code, name));
            context.recordInsert("subjects");
        });
    }

    private static SeedSection section(String name, java.util.function.Consumer<SeedContext> work) {
        return new SeedSection() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void load(SeedContext context) {
                work.accept(context);
            }
        };
    }

    private long countUsers() {
        return inTx(session -> session
                .createNativeQuery("SELECT COUNT(*) FROM users", Long.class)
                .getSingleResult());
    }

    private long countSubjects(String code) {
        return inTx(session -> session
                .createNativeQuery("SELECT COUNT(*) FROM subjects WHERE code2 = :code", Long.class)
                .setParameter("code", code)
                .getSingleResult());
    }
}
