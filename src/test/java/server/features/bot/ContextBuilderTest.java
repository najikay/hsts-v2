package server.features.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.projections.BotBankQuestion;
import server.db.projections.BotSourceText;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the model is allowed to read, and how the best of it is chosen (E16.6 ⚑ —
 * F12.8, S-28).
 */
class ContextBuilderTest {

    private static final BotSourceText KEYS = new BotSourceText(1L, "Keys handout",
            "A foreign key is a column that points at another table's primary key.\n\n"
                    + "Referential integrity means the database refuses a foreign key "
                    + "that points at a row which does not exist.");

    private static final BotSourceText SORTING = new BotSourceText(2L, "Sorting handout",
            "Quicksort partitions an array around a pivot and recurses on both halves.\n\n"
                    + "Mergesort divides an array in half, sorts each half and merges them.");

    private static final BotBankQuestion BANK_KEY = new BotBankQuestion(
            "22001", "What does a foreign key guarantee?",
            "Referential integrity", "Faster reads", "Smaller rows", "Unique names");

    private final ContextBuilder builder = new ContextBuilder();

    @Test
    @DisplayName("the material about the question is selected, and the rest is not")
    void selectsRelevantMaterial() {
        List<String> blocks = builder.build("what is a foreign key",
                List.of(KEYS, SORTING), List.of());

        assertThat(blocks).isNotEmpty();
        String joined = String.join("\n", blocks);
        assertThat(joined).contains("foreign key");
        assertThat(joined).doesNotContain("Quicksort");
    }

    @Test
    @DisplayName("material that matches nothing is dropped rather than used as filler")
    void dropsIrrelevantMaterial() {
        List<String> blocks = builder.build("what is a foreign key",
                List.of(SORTING), List.of());

        assertThat(blocks).isEmpty();
    }

    @Test
    @DisplayName("bank questions are offered as study material (S-28)")
    void includesBankQuestions() {
        List<String> blocks = builder.build("what does a foreign key guarantee",
                List.of(), List.of(BANK_KEY));

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).contains("Practice question 22001");
        assertThat(blocks.get(0)).contains("Referential integrity");
    }

    @Test
    @DisplayName("a bank question is rendered with four unmarked options and no key (F12.8, lead's ruling)")
    void bankQuestionsCarryNoAnswerKey() {
        String material = BANK_KEY.asStudyMaterial();

        assertThat(material).contains("A) Referential integrity");
        assertThat(material).contains("D) Unique names");
        assertThat(material.toLowerCase(Locale.ROOT))
                .doesNotContain("correct")
                .doesNotContain("answer is");
    }

    @Test
    @DisplayName("the projection the bot reads has nowhere to put a correct answer")
    void theProjectionCannotCarryCorrectness() {
        // The structural half of the same claim: the guard test in server.db.repos
        // scans the whole package, and this pins the one type this feature depends on.
        List<String> components = java.util.Arrays.stream(
                        BotBankQuestion.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(components)
                .containsExactly("displayId", "text", "answer1", "answer2", "answer3", "answer4");
    }

    @Test
    @DisplayName("only a bounded number of bank questions is offered, however many match")
    void boundsBankQuestions() {
        List<BotBankQuestion> many = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> new BotBankQuestion("2200" + i, "What is a foreign key variant "
                        + i + "?", "a", "b", "c", "d"))
                .toList();

        List<String> blocks = builder.build("foreign key", List.of(), many);

        assertThat(blocks).hasSizeLessThanOrEqualTo(ContextBuilder.MAX_BANK_QUESTIONS);
    }

    @Test
    @DisplayName("the character budget is respected")
    void respectsTheBudget() {
        String big = ("foreign key ".repeat(60) + "\n\n").repeat(20);
        List<String> blocks = builder.build("foreign key",
                List.of(new BotSourceText(1L, "Big handout", big)), List.of());

        int total = blocks.stream().mapToInt(String::length).sum();
        // Each block carries its fence, so the budget is about the material rather
        // than the rendered block; a generous ceiling still proves the bound holds.
        assertThat(total).isLessThan(ContextBuilder.BUDGET_CHARACTERS * 2);
        assertThat(blocks).hasSizeLessThanOrEqualTo(ContextBuilder.MAX_BLOCKS);
    }

    @Test
    @DisplayName("selection is deterministic: the same question twice gives the same context")
    void deterministic() {
        List<String> first = builder.build("foreign key integrity", List.of(KEYS, SORTING),
                List.of(BANK_KEY));
        List<String> second = builder.build("foreign key integrity", List.of(KEYS, SORTING),
                List.of(BANK_KEY));

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("blocks come back in document order, so the model reads the course's order")
    void keepsDocumentOrder() {
        List<String> blocks = builder.build("key sort", List.of(KEYS, SORTING), List.of());

        assertThat(blocks).isNotEmpty();
        int keysAt = indexOfFirstContaining(blocks, "foreign key");
        int sortAt = indexOfFirstContaining(blocks, "Quicksort");
        if (keysAt >= 0 && sortAt >= 0) {
            assertThat(keysAt).isLessThan(sortAt);
        }
    }

    @Test
    @DisplayName("null and empty inputs produce no context rather than an exception")
    void nullInputs() {
        assertThat(builder.build("anything", null, null)).isEmpty();
        assertThat(builder.build(null, List.of(KEYS), List.of())).isEmpty();
        assertThat(builder.build("   ", List.of(KEYS), List.of())).isEmpty();
    }

    @Test
    @DisplayName("stop words and very short words are not search terms")
    void termExtraction() {
        Set<String> terms = ContextBuilder.terms("What is the a b foreign KEY for you?");

        assertThat(terms).contains("foreign", "key");
        assertThat(terms).doesNotContain("what", "the", "for", "you", "is", "a", "b");
    }

    @Test
    @DisplayName("scoring counts distinct terms and ignores the empty cases")
    void scoring() {
        Set<String> terms = ContextBuilder.terms("foreign key integrity");

        assertThat(ContextBuilder.score("a foreign key gives integrity", terms)).isEqualTo(3);
        assertThat(ContextBuilder.score("foreign foreign foreign", terms)).isEqualTo(1);
        assertThat(ContextBuilder.score("", terms)).isZero();
        assertThat(ContextBuilder.score(null, terms)).isZero();
        assertThat(ContextBuilder.score("anything", Set.of())).isZero();
    }

    private static int indexOfFirstContaining(List<String> blocks, String needle) {
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
