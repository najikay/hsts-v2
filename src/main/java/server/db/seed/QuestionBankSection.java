package server.db.seed;

import org.hibernate.Session;
import server.db.entities.Difficulty;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;

import java.time.Instant;
import java.util.List;

/**
 * Seed §7: the fifty-eight questions, and the three that ship with a second version (E2.15).
 *
 * <h2>⚑ U-42: eighteen questions in three new courses</h2>
 *
 * <p>2026-08-30, live session. Biology 31, Chemistry 41 and Physics 51 get six questions each,
 * transcribed from seed §7.6, §7.7 and §7.8. Every one of the three has the same shape, stated
 * once here rather than three times below: <b>two topics, three questions per topic, one of each
 * difficulty</b>, so each course holds two EASY, two MEDIUM and two HARD. Correct answers run
 * 1, 2, 3, 4, 1, 2 down each course, the same cycle §7.1 runs, so no course has a majority answer
 * a guesser could exploit. None carries an illustration and none has a second version - see
 * {@link FacultySection} on why the co-teacher clause stays a one-row clause.
 *
 * <p>The document numbers them §7.6 to §7.8, <em>after</em> the second-version table at §7.5,
 * which is appending rather than inserting. That is U-34's precedent (execution 5's tables are
 * §9.4, not a renumbering of §9.3) and it exists because every section number in that document
 * is quoted from a parser heading list, a javadoc, an acceptance case or a defect note.
 *
 * <h2>Transcription decisions, all flagged in the PR report</h2>
 *
 * <ol>
 *   <li><b>Markdown formatting is stripped.</b> The document writes formulas and identifiers as
 *       {@code `x = 4`} because it is markdown. The backticks are formatting, not content: what
 *       a student reads on screen is {@code x = 4}. The same applies to emphasis asterisks:
 *       question 22001's stem is written {@code filters rows *before* grouping} and is stored
 *       as {@code filters rows before grouping}. That is the only emphasis in the bank, and it
 *       is recorded here rather than left as a silent judgement call.</li>
 *   <li><b>{@code created_by} follows §7's stated rule and is no longer inferred.</b> It was a
 *       flagged assumption in PR 3a, when the document had no author column and no rule; the
 *       2026-08-20 amendment added the rule (D9), so this now transcribes rather than guesses.
 *       v1 is the course's first-listed teacher in §4; a second version in a <b>co-taught</b>
 *       course is the co-teacher. See {@link #authorOf}, which resolves it from
 *       {@link FacultySection} so a roster change moves it automatically.</li>
 *   <li><b>{@code created_at} is not in the document and is derived.</b> Also NOT NULL. Set to
 *       {@link #V1_DAYS_BEFORE} days before the load anchor, and second versions to
 *       {@link #V2_DAYS_BEFORE}, so a version is always older than its successor and the whole
 *       bank predates the graded execution at T-14d.</li>
 *   <li><b>No em dash is replaced any more</b> <i>(B-13, 2026-08-27)</i>. This entry read "Em
 *       dashes are replaced ... one occurrence, in {@code 21008}'s fourth option, which the
 *       document writes as 'Nothing — it is safe'. Stored with a comma." That was true when it
 *       was written and is now false in both halves: §7.3 writes {@code Nothing, it is safe},
 *       so this section transcribes it rather than transforming it, and there is no remaining
 *       occurrence to transform. The list this entry belongs to is the record of every place
 *       the loader deviates from the document, which is worth nothing once one entry is wrong,
 *       so the deviation is recorded as closed rather than deleted.</li>
 *   <li><b>Illustrations load from the classpath</b> <i>(B-8, 2026-08-26)</i>. Ten questions are
 *       marked {@code img} and each one's bytes are read from
 *       {@code /seed/img/q&lt;displayId&gt;.png}. <b>This said "load as NULL ... when real assets
 *       land under {@code docs/seed/img/}" until 2026-08-26, and that path was never workable</b>:
 *       nothing in this package reads from disk, {@code docs/} is not on the classpath, and the
 *       packaged-jar walk requires the seed to work with no working copy. The lead ruled the
 *       bytes into {@code src/main/resources} for exactly that reason. The name is derived from
 *       the display id rather than held in a second list, because two lists drift.
 *       <b>A missing resource fails the load</b>, loudly and by name - see
 *       {@link #illustrationFor}.</li>
 * </ol>
 *
 * <h2>The thin topic is deliberate</h2>
 *
 * <p>Recursion has exactly two questions and no HARD one. Seed §7.3 is explicit that this must
 * not be "fixed": it is the fixture that lets F3.3 auto-generation be demonstrated
 * <em>failing</em> live, reporting the shortfall and refusing to create the exam (T-3), without
 * anyone editing the database mid-defense.
 */
final class QuestionBankSection implements SeedSection {

    /** How far before the load anchor the bank was authored. Predates the T-14d execution. */
    static final int V1_DAYS_BEFORE = -30;

    /** Second versions are later than their originals, and still before any execution. */
    static final int V2_DAYS_BEFORE = -20;

    private record Q(String displayId, String topic, Difficulty difficulty, String text,
                     String a1, String a2, String a3, String a4, int correct, boolean image) { }

    /** A §7.5 second version: same question, new content, version 2. */
    private record V2(String displayId, String text,
                      String a1, String a2, String a3, String a4, int correct) { }

    private static final List<Q> QUESTIONS = List.of(
            // 7.1 Algebra (course 11), 11 questions
            new Q("11001", "Linear equations", Difficulty.EASY,
                    "Solve: 3x + 6 = 18",
                    "x = 4", "x = 6", "x = 2", "x = 12", 1, false),
            new Q("11002", "Linear equations", Difficulty.EASY,
                    "Solve: 5x - 7 = 2x + 8",
                    "x = 3", "x = 5", "x = 15", "x = 1", 2, false),
            new Q("11003", "Linear equations", Difficulty.MEDIUM,
                    "For which value of k does the system 2x + ky = 4, 4x + 6y = 8 have infinitely many solutions?",
                    "k = 2", "k = 6", "k = 3", "k = 12", 3, false),
            new Q("11004", "Linear equations", Difficulty.HARD,
                    "The digits of a two-digit number add up to 11. Swapping the digits increases the number by 27. What is the number?",
                    "29", "38", "56", "47", 4, false),
            new Q("11005", "Quadratic functions", Difficulty.EASY,
                    "What are the roots of x² - 5x + 6 = 0?",
                    "2, 3", "1, 6", "-2, -3", "0, 5", 1, true),
            new Q("11006", "Quadratic functions", Difficulty.EASY,
                    "What is the vertex of the parabola y = (x - 3)² + 4?",
                    "(-3, 4)", "(3, 4)", "(3, -4)", "(4, 3)", 2, true),
            new Q("11007", "Quadratic functions", Difficulty.MEDIUM,
                    "How many x-axis intercepts does the parabola y = x² + 2x + 5 have?",
                    "Two", "One", "None", "Infinitely many", 3, true),
            new Q("11008", "Quadratic functions", Difficulty.HARD,
                    "The parabola y = ax² + bx + c passes through (0,3), (1,2) and (-1,6). What is a?",
                    "3", "2", "-1", "1", 4, false),
            new Q("11009", "Inequalities", Difficulty.EASY,
                    "Solve: 2x - 4 > 6",
                    "x > 5", "x > 1", "x < 5", "x > 10", 1, false),
            new Q("11010", "Inequalities", Difficulty.MEDIUM,
                    "Solve: x² - 4 < 0",
                    "x < -2", "-2 < x < 2", "x > 2", "all real x", 2, true),
            new Q("11011", "Inequalities", Difficulty.HARD,
                    "For which values of x does (x-1)/(x+2) ≥ 0 hold?",
                    "x ≥ 1", "-2 < x ≤ 1", "x < -2 or x ≥ 1", "x ≤ -2 or x ≥ 1", 3, false),

            // 7.2 Calculus (course 12), 9 questions
            new Q("12001", "Limits", Difficulty.EASY,
                    "Evaluate: lim(x→2) (x² - 4)/(x - 2)",
                    "0", "does not exist", "2", "4", 4, false),
            new Q("12002", "Limits", Difficulty.MEDIUM,
                    "Evaluate: lim(x→∞) (3x² + x)/(x² - 5)",
                    "3", "0", "∞", "1/3", 1, false),
            new Q("12003", "Limits", Difficulty.HARD,
                    "Evaluate: lim(x→0) sin(3x)/x",
                    "1", "3", "0", "1/3", 2, false),
            new Q("12004", "Derivatives", Difficulty.EASY,
                    "What is the derivative of f(x) = x³?",
                    "3x", "x²", "3x²", "x⁴/4", 3, false),
            new Q("12005", "Derivatives", Difficulty.EASY,
                    "What is the derivative of f(x) = sin(x)?",
                    "-sin(x)", "tan(x)", "-cos(x)", "cos(x)", 4, false),
            new Q("12006", "Derivatives", Difficulty.MEDIUM,
                    "What is the derivative of f(x) = x·e^x?",
                    "(1 + x)·e^x", "x·e^x", "e^x", "(x - 1)·e^x", 1, false),
            new Q("12007", "Derivatives", Difficulty.HARD,
                    "The function f(x) = x³ - 3x has a local minimum at:",
                    "x = -1", "x = 1", "x = 0", "x = 3", 2, true),
            new Q("12008", "Integrals", Difficulty.EASY,
                    "Evaluate: ∫ 2x dx",
                    "x²/2 + C", "2 + C", "x² + C", "2x² + C", 3, false),
            new Q("12009", "Integrals", Difficulty.MEDIUM,
                    "Find the area under y = x² between x=0 and x=3",
                    "6", "27", "3", "9", 4, true),

            // 7.3 Java (course 21), 11 questions
            new Q("21001", "OOP Basics", Difficulty.EASY,
                    "Which keyword prevents a class from being subclassed?",
                    "final", "static", "private", "sealed", 1, false),
            new Q("21002", "OOP Basics", Difficulty.EASY,
                    "What is the default value of an uninitialised int field?",
                    "null", "0", "undefined", "-1", 2, false),
            new Q("21003", "OOP Basics", Difficulty.MEDIUM,
                    "A class implements two interfaces that both declare default void run(). What happens?",
                    "It compiles, the first interface wins",
                    "It compiles, the second interface wins",
                    "Compile error until the class overrides it",
                    "A runtime AmbiguousMethodError", 3, false),
            new Q("21004", "OOP Basics", Difficulty.HARD,
                    "Which statement about equals and hashCode is true?",
                    "Overriding equals alone is always safe",
                    "Equal hash codes mean the objects are equal",
                    "hashCode must be unique for every object",
                    "Equal objects must have equal hash codes", 4, false),
            new Q("21005", "Collections", Difficulty.EASY,
                    "Which collection forbids duplicate elements?",
                    "HashSet", "ArrayList", "LinkedList", "ArrayDeque", 1, false),
            new Q("21006", "Collections", Difficulty.EASY,
                    "Which interface does HashMap implement?",
                    "List", "Map", "Set", "Queue", 2, true),
            new Q("21007", "Collections", Difficulty.MEDIUM,
                    "What is the average-case time complexity of HashMap.get?",
                    "O(n)", "O(log n)", "O(1)", "O(n log n)", 3, false),
            new Q("21008", "Collections", Difficulty.HARD,
                    "Removing an element from an ArrayList inside a for-each loop throws:",
                    "ConcurrentModificationException", "IndexOutOfBoundsException",
                    "UnsupportedOperationException",
                    // Document writes "Nothing - it is safe" with an em dash; PRD 4.1 forbids it.
                    "Nothing, it is safe", 1, false),
            new Q("21009", "Exceptions", Difficulty.EASY,
                    "Which of these is a checked exception?",
                    "NullPointerException", "IOException", "ArithmeticException",
                    "IllegalStateException", 2, false),
            new Q("21010", "Recursion", Difficulty.EASY,
                    "What does a recursive method need in order to terminate?",
                    "An enclosing loop", "A static modifier", "A base case",
                    "A return null statement", 3, true),
            new Q("21011", "Recursion", Difficulty.MEDIUM,
                    "Recursion with no reachable base case fails with:",
                    "OutOfMemoryError", "An infinite loop and no error", "IllegalStateException",
                    "StackOverflowError", 4, false),

            // 7.4 Databases (course 22), 9 questions
            new Q("22001", "SQL Queries", Difficulty.EASY,
                    "Which clause filters rows before grouping?",
                    "WHERE", "HAVING", "ORDER BY", "LIMIT", 1, false),
            new Q("22002", "SQL Queries", Difficulty.EASY,
                    "Which join returns every row of the left table?",
                    "INNER JOIN", "LEFT JOIN", "CROSS JOIN", "SELF JOIN", 2, true),
            new Q("22003", "SQL Queries", Difficulty.MEDIUM,
                    "COUNT(column) differs from COUNT(*) because it:",
                    "Is always faster", "Counts distinct values only", "Ignores NULLs",
                    "Requires an index", 3, false),
            new Q("22004", "SQL Queries", Difficulty.HARD,
                    "A join of two tables returns more rows than either table holds. The cause is:",
                    "A missing index", "An implicit CROSS JOIN, always",
                    "A NULL in the ON clause", "Duplicate values in the join key", 4, false),
            new Q("22005", "Normalization", Difficulty.EASY,
                    "First normal form requires every column to be:",
                    "Atomic", "Indexed", "Unique", "Non-null", 1, false),
            new Q("22006", "Normalization", Difficulty.MEDIUM,
                    "Removing a partial dependency on part of a composite key achieves:",
                    "1NF", "2NF", "3NF", "BCNF", 2, true),
            new Q("22007", "Normalization", Difficulty.HARD,
                    "A table in 3NF but not in BCNF must contain:",
                    "A transitive dependency", "A repeating group",
                    "A determinant that is not a candidate key", "A surrogate key", 3, false),
            new Q("22008", "Transactions", Difficulty.EASY,
                    "What does the \"D\" in ACID stand for?",
                    "Distributed", "Deferred", "Deterministic", "Durability", 4, false),
            new Q("22009", "Transactions", Difficulty.MEDIUM,
                    "Which isolation level still permits a phantom read?",
                    "READ COMMITTED", "REPEATABLE READ", "SERIALIZABLE", "None of them", 1, false),

            // ⚑ U-42, 7.6 Biology (course 31), 6 questions
            new Q("31001", "Cells", Difficulty.EASY,
                    "Which organelle releases most of a cell's usable energy?",
                    "Mitochondrion", "Ribosome", "Golgi apparatus", "Lysosome", 1, false),
            new Q("31002", "Cells", Difficulty.MEDIUM,
                    "A plant cell is left in pure water until its cell wall stops it taking in any more. That state is called:",
                    "Plasmolysed", "Turgid", "Flaccid", "Lysed", 2, false),
            new Q("31003", "Cells", Difficulty.HARD,
                    "Ribosomes are prevented from binding the rough endoplasmic reticulum. Which product is affected first?",
                    "ATP made in the mitochondria", "Glucose made in the chloroplast",
                    "Proteins destined for secretion", "Water crossing the membrane", 3, false),
            new Q("31004", "Genetics", Difficulty.EASY,
                    "How many chromosomes does a normal human body cell contain?",
                    "23", "92", "24", "46", 4, false),
            new Q("31005", "Genetics", Difficulty.MEDIUM,
                    "Two parents are each carriers of the same recessive disorder. What fraction of their children is expected to be affected?",
                    "One quarter", "One half", "Three quarters", "None", 1, false),
            new Q("31006", "Genetics", Difficulty.HARD,
                    "Two individuals heterozygous for both of two independently assorting genes are crossed. What phenotype ratio is expected?",
                    "3:1", "9:3:3:1", "1:1:1:1", "1:2:1", 2, false),

            // ⚑ U-42, 7.7 Chemistry (course 41), 6 questions
            new Q("41001", "Atomic structure", Difficulty.EASY,
                    "Which particle in an atom carries a negative charge?",
                    "Electron", "Proton", "Neutron", "Nucleus", 1, false),
            new Q("41002", "Atomic structure", Difficulty.MEDIUM,
                    "An atom has 11 protons and 12 neutrons. What is its mass number?",
                    "11", "23", "12", "1", 2, false),
            new Q("41003", "Atomic structure", Difficulty.HARD,
                    "Why does the first ionisation energy fall going down a group?",
                    "The nuclear charge falls", "The atoms gain more protons",
                    "The outer electron is further from the nucleus and better shielded",
                    "The atoms become more electronegative", 3, false),
            new Q("41004", "Chemical reactions", Difficulty.EASY,
                    "What is the pH of a neutral aqueous solution at 25 degrees Celsius?",
                    "0", "14", "1", "7", 4, false),
            new Q("41005", "Chemical reactions", Difficulty.MEDIUM,
                    "How many molecules of water are produced when two molecules of hydrogen react completely with one molecule of oxygen?",
                    "2", "1", "3", "4", 1, false),
            new Q("41006", "Chemical reactions", Difficulty.HARD,
                    "A reaction at equilibrium is heated and the yield of product falls. What does that say about the forward reaction?",
                    "It is endothermic", "It is exothermic", "It is catalysed",
                    "It has stopped", 2, false),

            // ⚑ U-42, 7.8 Physics (course 51), 6 questions
            new Q("51001", "Motion", Difficulty.EASY,
                    "What is the SI unit of force?",
                    "Newton", "Joule", "Watt", "Pascal", 1, false),
            new Q("51002", "Motion", Difficulty.MEDIUM,
                    "A car accelerates uniformly from rest at 3 m/s². How fast is it moving after 4 seconds?",
                    "3 m/s", "12 m/s", "7 m/s", "0.75 m/s", 2, false),
            new Q("51003", "Motion", Difficulty.HARD,
                    "A ball is thrown straight up and caught again. Ignoring air resistance, what is its acceleration at the highest point?",
                    "Zero", "Upwards and increasing", "9.8 m/s² downwards",
                    "Equal to its initial speed", 3, false),
            new Q("51004", "Energy", Difficulty.EASY,
                    "Which quantity is measured in joules?",
                    "Power", "Momentum", "Frequency", "Energy", 4, false),
            new Q("51005", "Energy", Difficulty.MEDIUM,
                    "A 2 kg mass is lifted 5 m at constant speed. Taking g as 10 m/s², how much gravitational potential energy does it gain?",
                    "100 J", "10 J", "50 J", "20 J", 1, false),
            new Q("51006", "Energy", Difficulty.HARD,
                    "A pendulum swings with no friction. Where is its kinetic energy greatest?",
                    "At the highest point of the swing", "At the lowest point of the swing",
                    "It is the same everywhere", "Halfway between the two", 2, false));

    private static final List<V2> SECOND_VERSIONS = List.of(
            // Rewords the stem; answers unchanged. Exam 1 stays pinned to v1 (7.5, 8.1).
            new V2("11005", "Find the roots of the equation x² - 5x + 6 = 0",
                    "2, 3", "1, 6", "-2, -3", "0, 5", 1),
            // Corrects an answer rather than a stem: AmbiguousMethodError is not a real error.
            new V2("21003",
                    "A class implements two interfaces that both declare default void run(). What happens?",
                    "It compiles, the first interface wins",
                    "It compiles, the second interface wins",
                    "Compile error until the class overrides it",
                    "A runtime IncompatibleClassChangeError", 3),
            // Clarifies a HARD question.
            new V2("22004",
                    "A join of two tables returns more rows than either table holds. The cause is: "
                            + "(assume no NULLs in the join key)",
                    "A missing index", "An implicit CROSS JOIN, always",
                    "A NULL in the ON clause", "Duplicate values in the join key", 4));

    /** Ten questions carry an illustration, loaded from {@code /seed/img/} on the classpath. */
    static long illustratedCount() {
        return QUESTIONS.stream().filter(Q::image).count();
    }

    /**
     * @return the display ids of every question the seed marks as illustrated, in seed order.
     *
     * <p>Exposed because {@link #illustratedCount} is a <b>number</b>, and a number cannot tell a
     * guard <em>which</em> ten. Without this, {@code SeedImagesTest} had no way to read the
     * flagged set and was driving itself from the generator's own list - so moving a flag from
     * one question to another left every test green on a seed that throws at load. Found by a
     * cold read.
     */
    static List<String> illustratedIds() {
        return QUESTIONS.stream().filter(Q::image).map(Q::displayId).toList();
    }

    /** The classpath resource holding one question's illustration. Derived, never listed twice. */
    static String illustrationResource(String displayId) {
        return "/seed/img/q" + displayId + ".png";
    }

    /**
     * @param displayId   the question's display id, which the resource name is derived from
     * @param illustrated whether the seed marks this question as carrying a picture
     * @return the PNG bytes for an illustrated question, or {@code null} when it carries none
     * @throws IllegalStateException when the question is marked {@code img} and no resource
     *         answers, which is a broken build rather than a degraded demo
     */
    // Package-private, and taking two plain values rather than the private Q record, ON PURPOSE:
    // the first version was private and took Q, which made the refusal below unreachable from any
    // test. It was described as "fails loudly" in a javadoc and a PR report while never having
    // been executed. A signature no test can call is a guard nobody has watched fail (rule 4).
    static byte[] illustrationFor(String displayId, boolean illustrated) {
        if (!illustrated) {
            return null;
        }
        String resource = illustrationResource(displayId);
        // Loudly, not NULL. A seed that quietly ships without the illustrations is the exact gap
        // B-8 exists to close, and it is the kind noticed on stage rather than in a build. The
        // build-time guard in SeedImagesTest is what should catch this first; reaching here means
        // the jar was packaged without its resources, which the guard cannot see from a working
        // copy.
        try (var in = QuestionBankSection.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("question " + displayId
                        + " is marked as illustrated and " + resource + " is not on the classpath");
            }
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read " + resource + " for question "
                    + displayId, e);
        }
    }

    @Override
    public String name() {
        return "7 question bank";
    }

    @Override
    public void load(SeedContext context) {
        Session session = context.session();
        Instant firstVersions = context.times().dayOffsetAt(V1_DAYS_BEFORE, 9, 0);
        Instant secondVersions = context.times().dayOffsetAt(V2_DAYS_BEFORE, 9, 0);

        int questions = 0;
        int versions = 0;

        for (Q question : QUESTIONS) {
            if (SeedLookup.findQuestionId(session, question.displayId()).isPresent()) {
                continue;
            }
            String course = question.displayId().substring(0, 2);
            short serial = Short.parseShort(question.displayId().substring(2));

            Question row = new Question(course, serial, question.displayId());
            session.persist(row);
            questions++;

            session.persist(new QuestionVersion(row.getId(), 1, question.text(),
                    question.a1(), question.a2(), question.a3(), question.a4(),
                    (byte) question.correct(), question.topic(), question.difficulty(),
                    illustrationFor(question.displayId(), question.image()), authorOf(session, course, 1), firstVersions));
            versions++;
        }

        for (V2 second : SECOND_VERSIONS) {
            long questionId = SeedLookup.requireQuestionId(session, second.displayId());
            if (SeedLookup.findQuestionVersionId(session, questionId, 2).isPresent()) {
                continue;
            }
            Q original = originalOf(second.displayId());
            String course = second.displayId().substring(0, 2);

            // The illustration belongs to the QUESTION, not to a wording, so a second version
            // carries the same bytes. 11005 is the case that makes this load-bearing: it is
            // illustrated AND has a v2, the demo paper pins v1 (case 6.1) while the bank screen
            // shows the latest (case 2.6), so putting the image on one row leaves the other case
            // blank. Its v2 only rewords the stem - same equation, same answers - so one drawing
            // is honest for both.
            session.persist(new QuestionVersion(questionId, 2, second.text(),
                    second.a1(), second.a2(), second.a3(), second.a4(),
                    (byte) second.correct(), original.topic(), original.difficulty(),
                    illustrationFor(original.displayId(), original.image()), authorOf(session, course, 2), secondVersions));
            versions++;
        }

        context.recordInserts("questions", questions);
        context.recordInserts("question_versions", versions);
    }

    private static Q originalOf(String displayId) {
        return QUESTIONS.stream()
                .filter(q -> q.displayId().equals(displayId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "second version names a question that is not in the bank: " + displayId));
    }

    /**
     * §7's D9 authorship rule, resolved against §4's teacher order.
     *
     * <p>Version 1 is the course's first-listed teacher. A <b>second version in a co-taught
     * course</b> is the co-teacher, which today resolves to exactly one row, {@code 21003} v2,
     * because Java is the only co-taught course. Second versions elsewhere stay with the
     * first-listed teacher, so {@code 11005} v2 and {@code 22004} v2 do not move.
     *
     * <p>Read from {@link FacultySection} rather than from a list here, because §7 states this
     * as a rule precisely so it re-resolves when the roster changes. Two copies of the order
     * would drift the next time a course gains or loses a co-teacher, and the drift would be
     * invisible: every version would still have <em>an</em> author.
     *
     * <p>This clause was wrong until {@code SeedLoadedDbTest} compared the loaded rows against
     * the rule: 21003 v2 was attributed to the first-listed teacher along with everything else.
     */
    private static long authorOf(Session session, String course, int versionNo) {
        List<String> teachers = FacultySection.teachersOf(course);
        if (teachers.isEmpty()) {
            throw new IllegalStateException("no seed teacher for course " + course
                    + ", so §7's authorship rule cannot resolve");
        }
        String username = versionNo > 1 && teachers.size() > 1
                ? teachers.get(1)
                : teachers.get(0);
        return SeedLookup.requireUserId(session, username);
    }
}
