package server.features.exambuild;

import common.dto.authoring.AutoComposeRequest;
import common.dto.authoring.AutoComposeResult;
import common.dto.authoring.ComposedQuestion;
import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.QuestionPin;
import common.dto.authoring.Shortfall;
import common.dto.authoring.TopicQuota;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import server.db.entities.Difficulty;
import server.db.projections.AutoCandidate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AutoComposer} — E7.4's proposal and, more importantly, its report (contract §7).
 *
 * <p>The fixture is the seed's own shape, because that is what gets demonstrated: Java 21 with
 * four topics, one of them <b>deliberately thin</b>. Recursion has two questions and no Hard one,
 * which is what makes the PRD's example sentence producible on stage without touching the
 * database.
 *
 * <h2>The test that matters</h2>
 *
 * <p>{@link TheNumberSheCanCheck}. §7.2 property 2 says {@code available} is the raw count in her
 * own bank and never a count net of what another quota consumed, because she is invited to go and
 * verify it by filtering the bank screen. A shortfall she can disprove makes the report worse than
 * nothing, and it is the one failure here that is worse than not building the feature. That class
 * asserts the property over generated shapes rather than over cases somebody thought of.
 */
class AutoComposerTest {

    private static final String COURSE = "21";

    // ===================== Fixture ========================================

    private static AutoCandidate q(long id, String topic, Difficulty difficulty) {
        return new AutoCandidate(id, id, "21" + String.format("%03d", id), "Question " + id,
                topic, difficulty, false, 1);
    }

    /**
     * The seed's Java bank, in the shape PRD §5 fixes it.
     *
     * <p>Recursion carries exactly two questions and no HARD one. That is not an oversight in the
     * fixture any more than it is in the seed: it is what T-3.5 and T-3.6 are aimed at.
     */
    private static List<AutoCandidate> seedBank() {
        return List.of(
                q(1, "OOP Basics", Difficulty.EASY),
                q(2, "OOP Basics", Difficulty.MEDIUM),
                q(3, "OOP Basics", Difficulty.HARD),
                q(4, "Collections", Difficulty.EASY),
                q(5, "Collections", Difficulty.MEDIUM),
                q(6, "Collections", Difficulty.HARD),
                q(7, "Exceptions", Difficulty.MEDIUM),
                q(8, "Exceptions", Difficulty.HARD),
                q(9, "Recursion", Difficulty.EASY),
                q(10, "Recursion", Difficulty.MEDIUM));
    }

    private static AutoComposeRequest criteria(TopicQuota... quotas) {
        return new AutoComposeRequest(COURSE, Arrays.asList(quotas), 42L);
    }

    private static long rawCount(List<AutoCandidate> pool, String topic, Difficulty difficulty) {
        return pool.stream()
                .filter(c -> topic == null || c.topic().equalsIgnoreCase(topic))
                .filter(c -> difficulty == null || c.difficulty() == difficulty)
                .count();
    }

    // ===================== The report =====================================

    @Nested
    @DisplayName("the infeasibility report (F3.3, §7)")
    class TheReport {

        @Test
        @DisplayName("⚑ the PRD's own sentence: requested 1 Hard from a topic that has none")
        void thePrdExample() {
            AutoComposeResult result = AutoComposer.compose(
                    criteria(new TopicQuota("Recursion", 0, 0, 1, 0)), seedBank());

            assertThat(result.feasible()).isFalse();
            assertThat(result.questions()).isEmpty();
            assertThat(result.shortfalls())
                    .as("Topic 'Recursion': requested 1 Hard, bank has 0 - the sentence F3.3 "
                            + "writes out and the seed exists to make demonstrable")
                    .containsExactly(new Shortfall("Recursion",
                            common.dto.bank.Difficulty.HARD, 1, 0));
        }

        @Test
        @DisplayName("⚑ every shortfall, not the first (§7.2 property 1)")
        void everyShortfallIsReported() {
            AutoComposeResult result = AutoComposer.compose(
                    criteria(new TopicQuota("Recursion", 0, 0, 3, 0),
                            new TopicQuota("Exceptions", 5, 0, 0, 0)), seedBank());

            assertThat(result.shortfalls())
                    .as("first-failure reporting makes her discover the second problem by fixing "
                            + "the first, which on a stage is a very long silence")
                    .hasSize(2)
                    .contains(new Shortfall("Recursion", common.dto.bank.Difficulty.HARD, 3, 0))
                    .contains(new Shortfall("Exceptions", common.dto.bank.Difficulty.EASY, 5, 0));
        }

        @Test
        @DisplayName("⚑ §7.3's worked example: every quota fits, the union does not")
        void theUnionCanBeShortWhenNoQuotaIs() {
            // §7.3's shape, with this bank's numbers: Recursion holds exactly two, so two is what
            // fits. Two Recursion and nine course-wide against a bank of ten. NEITHER ROW IS
            // SHORT - that is the whole point of the case - and eleven questions are asked for
            // while ten exist. The contract writes it as three-and-eight against a bank whose
            // Recursion is deeper; the property being tested is the same one.
            AutoComposeResult result = AutoComposer.compose(
                    criteria(TopicQuota.ofAnyDifficulty("Recursion", 2),
                            TopicQuota.ofAnyDifficulty(null, 9)), seedBank());

            assertThat(result.shortfalls())
                    .as("reporting either quota alone pairs a true count with a demand it does "
                            + "not belong to, and 'Requested 8 questions, bank has 10' is a "
                            + "sentence she can disprove")
                    .containsExactly(new Shortfall(null, null, 11, 10));
        }

        @Test
        @DisplayName("a raw-short row keeps its place beside the aggregate one (§7.3)")
        void rawShortAndAggregateCoexist() {
            AutoComposeResult result = AutoComposer.compose(
                    criteria(new TopicQuota("Recursion", 0, 0, 2, 0),
                            TopicQuota.ofAnyDifficulty(null, 9)), seedBank());

            assertThat(result.shortfalls())
                    .as("short on Recursion Hard AND over the course total: fixing one does not "
                            + "fix the other, so she is told both")
                    .contains(new Shortfall("Recursion", common.dto.bank.Difficulty.HARD, 2, 0))
                    .contains(new Shortfall(null, null, 11, 10));
        }

        @Test
        @DisplayName("topic-internal overlap reports at the topic level (§7.3, one level down)")
        void topicInternalOverlap() {
            // Recursion holds two questions, one EASY one MEDIUM. Asking for the EASY one plus
            // two of any leaves both leaves satisfiable and the topic over-subscribed.
            AutoComposeResult result = AutoComposer.compose(
                    criteria(new TopicQuota("Recursion", 1, 0, 0, 2)), seedBank());

            assertThat(result.shortfalls())
                    .containsExactly(new Shortfall("Recursion", null, 3, 2));
        }

        /**
         * The duplicate row a lone course-wide quota produced.
         *
         * <p>With one quota the course-level union <em>is</em> that quota, so the identical row
         * was emitted twice: once as its own leaf and once as the aggregate. She would read one
         * problem printed under two sentences and conclude there were two. Found by reading the
         * emission levels against each other, not by a failing case.
         */
        @Test
        @DisplayName("⚑ one problem is reported once, not once per level that notices it")
        void noDuplicateRows() {
            AutoComposeResult result = AutoComposer.compose(
                    criteria(TopicQuota.ofAnyDifficulty(null, 15)), seedBank());

            assertThat(result.shortfalls())
                    .containsExactly(new Shortfall(null, null, 15, 10));
        }

        /**
         * Two rows about one topic that render as the same sentence with different numbers ⚑
         *
         * <p>§7.1 gives {@code (topic, null, r, a)} exactly one sentence, so a leaf row for the
         * {@code any} bucket and a topic-level aggregate are <b>indistinguishable on the wire</b>:
         * the client cannot render them apart and she reads
         *
         * <pre>
         *   Topic "Recursion": requested 3 questions, bank has 2.
         *   Topic "Recursion": requested 4 questions, bank has 2.
         * </pre>
         *
         * <p>and concludes there are two problems, or that the report cannot count. The aggregate
         * wins because it is the actionable number: it covers her whole demand on that topic, so
         * acting on it fixes the topic in one round trip where acting on the leaf leaves the
         * graded bucket still over.
         */
        @Test
        @DisplayName("⚑ one topic never yields two rows the client renders identically")
        void oneTopicYieldsOneTopicWideRow() {
            AutoComposeResult result = AutoComposer.compose(
                    criteria(new TopicQuota("Recursion", 1, 0, 0, 3)), seedBank());

            assertThat(result.shortfalls())
                    .filteredOn(s -> s.isTopicScoped() && !s.isDifficultyScoped())
                    .as("both rows carry a correct raw available and both have something "
                            + "missing, so neither the property test nor addIfNew can see this")
                    .hasSize(1)
                    .first()
                    .as("the aggregate covers all four buckets, which is what she has to act on")
                    .isEqualTo(new Shortfall("Recursion", null, 4, 2));
        }

        @Test
        @DisplayName("an empty bank reports rather than proposing nothing")
        void anEmptyBankReports() {
            AutoComposeResult result = AutoComposer.compose(
                    criteria(TopicQuota.ofAnyDifficulty(null, 3)), List.of());

            assertThat(result.feasible()).isFalse();
            assertThat(result.shortfalls()).containsExactly(new Shortfall(null, null, 3, 0));
        }
    }

    // ===================== The property ===================================

    /**
     * §7.2 property 2, over generated shapes rather than remembered cases ⚑
     *
     * <p><b>Every {@code available} must be the raw count in her bank.</b> Not net of what another
     * quota consumed, not the count before soft-deleted rows went, not the count after this
     * composer took some. She is told to go and check it by filtering the bank screen to the same
     * topic and difficulty, and a number that does not match what she sees there makes the whole
     * report worse than not having one.
     */
    @Nested
    @DisplayName("the number she can check (§7.2 property 2) ⚑")
    class TheNumberSheCanCheck {

        @Test
        @DisplayName("⚑ every available in every report equals the raw bank count")
        void availableIsAlwaysTheRawCount() {
            Random shapes = new Random(20260825L);
            int reportsChecked = 0;

            for (int run = 0; run < 400; run++) {
                List<AutoCandidate> pool = randomPool(shapes);
                AutoComposeRequest request = randomLegalCriteria(shapes);
                if (ExamValidator.quotaProblem(request).isPresent()) {
                    continue;
                }

                AutoComposeResult result = AutoComposer.compose(request, pool);
                if (result.feasible()) {
                    continue;
                }
                reportsChecked++;
                for (Shortfall shortfall : result.shortfalls()) {
                    Difficulty entityDifficulty = shortfall.difficulty() == null ? null
                            : Difficulty.valueOf(shortfall.difficulty().name());
                    assertThat((long) shortfall.available())
                            .as("shortfall %s: she filters her bank to that topic and difficulty "
                                    + "and counts what she sees", shortfall)
                            .isEqualTo(rawCount(pool, shortfall.topic(), entityDifficulty));
                    assertThat(shortfall.missing())
                            .as("a shortfall claiming nothing is missing renders as a sentence "
                                    + "with no problem in it")
                            .isPositive();
                }
            }
            assertThat(reportsChecked)
                    .as("the generator must actually produce infeasible requests, or this test "
                            + "passes by checking nothing")
                    .isGreaterThan(50);
        }

        @Test
        @DisplayName("⚑ a feasible request is never reported as short, over generated shapes")
        void feasibleRequestsAreNeverReportedShort() {
            Random shapes = new Random(11081991L);
            int proposals = 0;

            for (int run = 0; run < 400; run++) {
                List<AutoCandidate> pool = randomPool(shapes);
                AutoComposeRequest request = randomLegalCriteria(shapes);
                if (ExamValidator.quotaProblem(request).isPresent()) {
                    continue;
                }

                AutoComposeResult result = AutoComposer.compose(request, pool);
                if (!result.feasible()) {
                    continue;
                }
                proposals++;
                assertThat(result.questions())
                        .as("a proposal must hold exactly what was asked for")
                        .hasSize(request.totalRequested());
                assertThat(result.totalPoints())
                        .as("savable in one click (T-3.4), so section 5.1 is already satisfied")
                        .isEqualTo(ExamCreateRequest.POINTS_TOTAL);
                assertThat(result.questions())
                        .extracting(ComposedQuestion::questionVersionId)
                        .doesNotHaveDuplicates();
            }
            assertThat(proposals).isGreaterThan(50);
        }

        private static List<AutoCandidate> randomPool(Random random) {
            String[] topics = {"OOP Basics", "Collections", "Exceptions", "Recursion"};
            Difficulty[] levels = Difficulty.values();
            int size = random.nextInt(14);
            List<AutoCandidate> pool = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                pool.add(q(i + 1, topics[random.nextInt(topics.length)],
                        levels[random.nextInt(levels.length)]));
            }
            return pool;
        }

        /**
         * Criteria in one of §7.3a's two legal shapes.
         *
         * <p>Generated legal rather than filtered legal: the nesting argument this composer rests
         * on is only claimed for these shapes, so feeding it others would test a promise nobody
         * made. {@code quotaProblem} still runs on every one, because the generator is not the
         * authority on what is legal.
         */
        private static AutoComposeRequest randomLegalCriteria(Random random) {
            String[] topics = {"OOP Basics", "Collections", "Exceptions", "Recursion"};
            List<TopicQuota> quotas = new ArrayList<>();

            if (random.nextBoolean()) {
                // Shape one: rows per topic, optionally a course-wide TOTAL.
                Set<String> used = new java.util.LinkedHashSet<>();
                int rows = 1 + random.nextInt(3);
                for (int i = 0; i < rows; i++) {
                    String topic = topics[random.nextInt(topics.length)];
                    if (!used.add(topic)) {
                        continue;
                    }
                    quotas.add(new TopicQuota(topic, random.nextInt(3), random.nextInt(3),
                            random.nextInt(3), random.nextInt(3)));
                }
                if (random.nextBoolean()) {
                    quotas.add(TopicQuota.ofAnyDifficulty(null, random.nextInt(6)));
                }
            } else {
                // Shape two: one course-wide row split by difficulty, standing alone.
                quotas.add(new TopicQuota(null, random.nextInt(4), random.nextInt(4),
                        random.nextInt(4), random.nextInt(4)));
            }
            return new AutoComposeRequest(COURSE, quotas, (long) random.nextInt(1000));
        }
    }

    // ===================== The proposal ===================================

    @Nested
    @DisplayName("the proposal (§7.4)")
    class TheProposal {

        @Test
        @DisplayName("⚑ most-constrained-first, or a real shortfall is reported that is not real")
        void narrowQuotasPickBeforeWideOnes() {
            // Two Recursion (the topic holds exactly two) and eight from anywhere. Ten questions
            // exist and ten are asked for, so it fits - but only if Recursion picks first. A wide
            // quota picking first can take Recursion's only candidates and leave it dry.
            AutoComposeResult result = AutoComposer.compose(
                    criteria(TopicQuota.ofAnyDifficulty("Recursion", 2),
                            TopicQuota.ofAnyDifficulty(null, 8)), seedBank());

            assertThat(result.feasible())
                    .as("failing here would report a shortfall she can disprove by counting her "
                            + "own bank, which section 7.4 calls the worst failure available")
                    .isTrue();
            assertThat(result.questions()).hasSize(10);
            assertThat(result.questions())
                    .extracting(ComposedQuestion::topic)
                    .filteredOn("Recursion"::equals)
                    .as("Recursion's two are both on the paper")
                    .hasSize(2);
        }

        @Test
        @DisplayName("no question twice, across quotas as well as within one (§7.4, §5.2)")
        void noQuestionTwice() {
            AutoComposeResult result = AutoComposer.compose(
                    criteria(TopicQuota.ofAnyDifficulty("Recursion", 2),
                            TopicQuota.ofAnyDifficulty(null, 5)), seedBank());

            assertThat(result.questions())
                    .extracting(ComposedQuestion::questionVersionId)
                    .as("caught during selection rather than discovered as a constraint "
                            + "violation at save (T-3.9)")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("points total exactly 100, remainder on the earliest (§7.4)")
        void pointsTotalOneHundred() {
            AutoComposeResult result = AutoComposer.compose(
                    criteria(TopicQuota.ofAnyDifficulty(null, 3)), seedBank());

            assertThat(result.questions()).extracting(ComposedQuestion::points)
                    .as("three questions become 34, 33, 33")
                    .containsExactly(34, 33, 33);
            assertThat(result.totalPoints()).isEqualTo(ExamCreateRequest.POINTS_TOTAL);
        }

        @Test
        @DisplayName("every question is worth at least the minimum, even at the ceiling")
        void everyQuestionClearsMinPoints() {
            List<AutoCandidate> big = new ArrayList<>();
            for (int i = 1; i <= ExamCreateRequest.POINTS_TOTAL; i++) {
                big.add(q(i, "OOP Basics", Difficulty.EASY));
            }

            AutoComposeResult result = AutoComposer.compose(
                    criteria(TopicQuota.ofAnyDifficulty(null, ExamCreateRequest.POINTS_TOTAL)),
                    big);

            assertThat(result.questions()).extracting(ComposedQuestion::points)
                    .as("100 questions at one point each is the boundary quotaProblem allows")
                    .allMatch(points -> points >= QuestionPin.MIN_POINTS);
            assertThat(result.totalPoints()).isEqualTo(ExamCreateRequest.POINTS_TOTAL);
        }

        @Test
        @DisplayName("ord is the selection order, 1-based (§7.4)")
        void ordIsOneBased() {
            AutoComposeResult result = AutoComposer.compose(
                    criteria(TopicQuota.ofAnyDifficulty(null, 4)), seedBank());

            assertThat(result.questions()).extracting(ComposedQuestion::ord)
                    .containsExactly(1, 2, 3, 4);
        }

        @Test
        @DisplayName("a fresh proposal pins the latest version, so no badge is owed")
        void pinsTheLatestVersion() {
            AutoComposeResult result = AutoComposer.compose(
                    criteria(TopicQuota.ofAnyDifficulty(null, 3)), seedBank());

            assertThat(result.questions())
                    .as("E7.7's newer-version badge must not light up on a paper composed a "
                            + "second ago")
                    .allMatch(question -> !question.hasNewerVersion());
        }

        @Test
        @DisplayName("the seed reproduces a selection, which is why it is on the wire (§7.5)")
        void theSeedReproduces() {
            List<String> first = idsOf(AutoComposer.compose(
                    new AutoComposeRequest(COURSE,
                            List.of(TopicQuota.ofAnyDifficulty(null, 4)), 7L), seedBank()));
            List<String> again = idsOf(AutoComposer.compose(
                    new AutoComposeRequest(COURSE,
                            List.of(TopicQuota.ofAnyDifficulty(null, 4)), 7L), seedBank()));

            assertThat(again)
                    .as("a teacher who says 'it gave me a strange set' cannot be helped if "
                            + "nobody can reproduce it")
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("different seeds really do select differently")
        void differentSeedsDiffer() {
            Set<List<String>> selections = new java.util.LinkedHashSet<>();
            for (long seed = 0; seed < 12; seed++) {
                selections.add(idsOf(AutoComposer.compose(
                        new AutoComposeRequest(COURSE,
                                List.of(TopicQuota.ofAnyDifficulty(null, 4)), seed), seedBank())));
            }

            assertThat(selections)
                    .as("a seed that changed nothing would make the parameter decoration and "
                            + "every reproducibility claim above it false")
                    .hasSizeGreaterThan(1);
        }

        private static List<String> idsOf(AutoComposeResult result) {
            return result.questions().stream()
                    .map(ComposedQuestion::questionDisplayId5)
                    .collect(Collectors.toList());
        }
    }
}
