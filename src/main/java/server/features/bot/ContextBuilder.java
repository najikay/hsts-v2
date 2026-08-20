package server.features.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.projections.BotBankQuestion;
import server.db.projections.BotSourceText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Chooses what the model gets to read (Logic tier, E16.6 ⚑ — F12.8, S-28).
 *
 * <p>This class is the entire content of the bot's context window, and therefore
 * the entire surface of the F12.8 security requirement. Its inputs are the two
 * things the specification allows and nothing else:
 *
 * <ul>
 *   <li>{@link BotSourceText} — the text teachers uploaded for this course;</li>
 *   <li>{@link BotBankQuestion} — this course's bank questions, <b>without</b> any
 *       marking of which answer is correct.</li>
 * </ul>
 *
 * <p>There is no third parameter, and adding one would mean editing this
 * signature. That is the point: exam definitions, exam-question membership,
 * execution codes, attempts and grades are not filtered out here, they are
 * <em>unreachable</em> — this package has no compile-time dependency on any of
 * their repositories, which {@code BotIsolationGuardTest} asserts by scanning the
 * compiled classes rather than by reading imports. A bot that has never been able
 * to see an exam cannot be talked into revealing one.
 *
 * <h2>How the top-k selection works, and why it is this simple</h2>
 *
 * <p>Keyword overlap: the question is reduced to its content words, every chunk is
 * scored by how many distinct ones it contains, and the best chunks are packed
 * into a {@link #BUDGET_CHARACTERS} budget in score order. No embeddings, no
 * vector store, no similarity model.
 *
 * <p>That is a deliberate choice rather than a shortcut. An embedding index would
 * need a model call per chunk at upload time, a place to keep the vectors, and a
 * reindex whenever either changes — three new failure modes in the feature whose
 * v1 version was dead at the defence. Overlap scoring over a course's worth of
 * handouts retrieves well, costs a scan of text already in memory, and is
 * <em>deterministic</em>: the same question over the same sources produces the
 * same context every time, which is why {@code ContextBuilderTest} can pin the
 * selection by example. The upgrade path is one class, and ADR-009 says so.
 *
 * <h2>Ties, and why order is fixed</h2>
 *
 * <p>Chunks that score equally are kept in source order (and sources in id order).
 * Without that, a map iteration order would decide what the model reads, and two
 * identical asks could get different context — which makes a bug report about a
 * bad answer impossible to reproduce.
 */
public final class ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    /**
     * How much course material one prompt may carry, in characters.
     *
     * <p>Roughly 1500 tokens of English, which leaves a comfortable margin inside
     * every model this chain talks to while still fitting several handout pages.
     * Characters rather than tokens because a character count is exact, free, and
     * identical for both providers; a token count would be an estimate that
     * differs per vendor and per tokeniser release.
     */
    public static final int BUDGET_CHARACTERS = 6000;

    /**
     * The most blocks a prompt carries.
     *
     * <p>A separate limit from the budget because forty tiny fragments and six
     * paragraphs can weigh the same and do not read the same: the fragments arrive
     * as a shredded document, and answers get worse.
     */
    public static final int MAX_BLOCKS = 8;

    /** How many bank questions may be offered as study material for one ask. */
    public static final int MAX_BANK_QUESTIONS = 4;

    /** The shortest word treated as a search term. */
    static final int MIN_TERM_LENGTH = 3;

    /**
     * Words that appear in every question and therefore distinguish nothing.
     *
     * <p>Kept short on purpose. A long stop-list is a maintenance burden and a
     * source of surprise ("why does asking about 'set' return nothing?"); these are
     * the handful that would otherwise dominate the score of every chunk in a
     * course's material.
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "are", "but", "not", "you", "your", "with", "that",
            "this", "what", "when", "which", "how", "why", "can", "does", "did",
            "was", "were", "has", "have", "had", "its", "it's", "about", "from",
            "into", "than", "then", "there", "their", "them", "they", "would",
            "could", "should", "please", "explain", "tell", "give", "show", "help");

    /**
     * One scored candidate. Kept package-private so the tests can assert on the
     * ranking itself rather than only on the text that survives it.
     *
     * @param title what the block is labelled as in the prompt
     * @param text  the material
     * @param score how many distinct question terms it matched
     * @param order its position in the input, which breaks ties deterministically
     */
    record Candidate(String title, String text, int score, int order) {
    }

    /**
     * Selects and fences the material for one question.
     *
     * @param question the student's question
     * @param sources  the bot's sources, in a stable order
     * @param bank     the course's bank questions, without correctness data (S-28)
     * @return fenced context blocks, most relevant first; empty when nothing in the
     *         material relates to the question, which is a legitimate outcome and
     *         one the system prompt tells the model how to handle
     */
    public List<String> build(String question,
                              List<BotSourceText> sources,
                              List<BotBankQuestion> bank) {
        Set<String> terms = terms(question);
        List<Candidate> candidates = new ArrayList<>();
        int order = 0;

        for (BotSourceText source : sources == null ? List.<BotSourceText>of() : sources) {
            for (String chunk : Chunker.chunk(source.text())) {
                candidates.add(new Candidate(source.title(), chunk, score(chunk, terms), order++));
            }
        }
        int bankAdded = 0;
        for (BotBankQuestion q : bank == null ? List.<BotBankQuestion>of() : bank) {
            if (bankAdded >= MAX_BANK_QUESTIONS) {
                break;
            }
            int score = score(q.searchableText(), terms);
            if (score > 0) {
                candidates.add(new Candidate("Practice question " + q.displayId(),
                        q.asStudyMaterial(), score, order++));
                bankAdded++;
            }
        }

        List<Candidate> chosen = select(candidates);
        List<String> blocks = chosen.stream()
                .map(candidate -> Guardrails.fenceContext(candidate.title(), candidate.text()))
                .toList();
        log.debug("Context: {} blocks from {} candidates for {} terms",
                blocks.size(), candidates.size(), terms.size());
        return blocks;
    }

    /**
     * Ranks and packs.
     *
     * <p>Zero-scoring chunks are dropped rather than used as filler. Material that
     * matches nothing in the question is not context, it is noise, and it is the
     * kind of noise that makes a model answer confidently about the wrong topic.
     */
    private static List<Candidate> select(List<Candidate> candidates) {
        List<Candidate> ranked = new ArrayList<>(candidates.stream()
                .filter(candidate -> candidate.score() > 0)
                .toList());
        ranked.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparingInt(Candidate::order));

        List<Candidate> chosen = new ArrayList<>();
        int used = 0;
        for (Candidate candidate : ranked) {
            if (chosen.size() >= MAX_BLOCKS) {
                break;
            }
            int cost = candidate.text().length();
            if (used + cost > BUDGET_CHARACTERS && !chosen.isEmpty()) {
                // Keep going rather than stop: a long chunk that does not fit must
                // not shut out the shorter, equally relevant one behind it.
                continue;
            }
            chosen.add(candidate);
            used += cost;
        }
        // Back to input order for the prompt itself. Ranking decides what is
        // included; the model reads the material in the order the course teaches it.
        chosen.sort(Comparator.comparingInt(Candidate::order));
        return List.copyOf(chosen);
    }

    /**
     * @param question a student's question
     * @return its distinct content words, lower case; never null
     */
    static Set<String> terms(String question) {
        Set<String> terms = new LinkedHashSet<>();
        if (question == null) {
            return terms;
        }
        for (String word : question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= MIN_TERM_LENGTH && !STOP_WORDS.contains(word)) {
                terms.add(word);
            }
        }
        return terms;
    }

    /**
     * @param text  a candidate chunk
     * @param terms the question's terms
     * @return how many distinct terms it contains
     */
    static int score(String text, Set<String> terms) {
        Objects.requireNonNull(terms, "terms");
        if (text == null || text.isBlank() || terms.isEmpty()) {
            return 0;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (haystack.contains(term)) {
                score++;
            }
        }
        return score;
    }
}
