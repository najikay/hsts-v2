package server.db.seed;

import org.hibernate.Session;
import server.db.entities.Difficulty;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;

import java.time.Instant;
import java.util.List;

/**
 * Seed §7: the forty questions, and the three that ship with a second version (E2.15).
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
 *   <li><b>{@code created_by} is not in the document and is inferred.</b>
 *       {@code question_versions.created_by} is NOT NULL, and seed §7's tables have no author
 *       column. After the 2026-08-20 roster decision only <b>course 21</b> has two teachers, so
 *       29 of the 40 questions have exactly one possible author and are unambiguous. The 11
 *       Java questions use the course's <em>first-listed</em> teacher in §4,
 *       {@code avi.mizrahi}. That one is a coin flip and it is recorded as one.</li>
 *   <li><b>{@code created_at} is not in the document and is derived.</b> Also NOT NULL. Set to
 *       {@link #V1_DAYS_BEFORE} days before the load anchor, and second versions to
 *       {@link #V2_DAYS_BEFORE}, so a version is always older than its successor and the whole
 *       bank predates the graded execution at T-14d.</li>
 *   <li><b>Em dashes are replaced.</b> PRD §4.1 forbids them in user-visible text and a question
 *       answer is about as user-visible as text gets. One occurrence, in {@code 21008}'s fourth
 *       option, which the document writes as "Nothing — it is safe". Stored with a comma. The
 *       change is listed in the report so the content owner can object; it alters no meaning and
 *       no score.</li>
 *   <li><b>Illustrations load as NULL.</b> Ten questions are marked {@code img} and no bytes are
 *       supplied. {@code image MEDIUMBLOB NULL} accepts that, and the loader stays idempotent
 *       when real assets land under {@code docs/seed/img/}. The flag is kept in the data below
 *       so the count stays assertable and so the follow-up knows which ten to fill.</li>
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

    /** Courses 12 and 21 have two teachers; this is the first listed in seed §4. */
    private static final List<String[]> COURSE_AUTHOR = List.of(
            new String[] {"11", "dana.cohen"},
            new String[] {"12", "dana.cohen"},
            new String[] {"21", "avi.mizrahi"},
            new String[] {"22", "michal.sharon"});

    private record Q(String displayId, String topic, Difficulty difficulty, String text,
                     String a1, String a2, String a3, String a4, int correct, boolean image) { }

    /** A §7.5 second version: same question, new content, version 2. */
    private record V2(String displayId, String text,
                      String a1, String a2, String a3, String a4, int correct) { }

    private static final List<Q> QUESTIONS = List.of(
            // 7.1 Algebra (course 11), 11 questions
            new Q("11001", "משוואות ליניאריות", Difficulty.EASY,
                    "פתרו: 3x + 6 = 18",
                    "x = 4", "x = 6", "x = 2", "x = 12", 1, false),
            new Q("11002", "משוואות ליניאריות", Difficulty.EASY,
                    "פתרו: 5x - 7 = 2x + 8",
                    "x = 3", "x = 5", "x = 15", "x = 1", 2, false),
            new Q("11003", "משוואות ליניאריות", Difficulty.MEDIUM,
                    "לאיזה ערך של k למערכת 2x + ky = 4, 4x + 6y = 8 יש אינסוף פתרונות?",
                    "k = 2", "k = 6", "k = 3", "k = 12", 3, false),
            new Q("11004", "משוואות ליניאריות", Difficulty.HARD,
                    "סכום הספרות של מספר דו-ספרתי הוא 11. אם מחליפים את הספרות, המספר גדל ב-27. מהו המספר?",
                    "47", "38", "56", "29", 1, false),
            new Q("11005", "פונקציות ריבועיות", Difficulty.EASY,
                    "מהם שורשי x² - 5x + 6 = 0?",
                    "1, 6", "2, 3", "-2, -3", "0, 5", 2, true),
            new Q("11006", "פונקציות ריבועיות", Difficulty.EASY,
                    "מהו קודקוד הפרבולה y = (x - 3)² + 4?",
                    "(3, 4)", "(-3, 4)", "(3, -4)", "(4, 3)", 1, true),
            new Q("11007", "פונקציות ריבועיות", Difficulty.MEDIUM,
                    "כמה נקודות חיתוך עם ציר x יש לפרבולה y = x² + 2x + 5?",
                    "שתיים", "אחת", "אף אחת", "אינסוף", 3, true),
            new Q("11008", "פונקציות ריבועיות", Difficulty.HARD,
                    "הפרבולה y = ax² + bx + c עוברת דרך (0,3), (1,2) ו-(-1,6). מהו a?",
                    "1", "2", "-1", "3", 1, false),
            new Q("11009", "אי-שוויונות", Difficulty.EASY,
                    "פתרו: 2x - 4 > 6",
                    "x > 5", "x > 1", "x < 5", "x > 10", 1, false),
            new Q("11010", "אי-שוויונות", Difficulty.MEDIUM,
                    "פתרו: x² - 4 < 0",
                    "x < -2", "x > 2", "-2 < x < 2", "כל x ממשי", 3, true),
            new Q("11011", "אי-שוויונות", Difficulty.HARD,
                    "לאילו ערכי x מתקיים (x-1)/(x+2) ≥ 0?",
                    "x ≥ 1", "x < -2 או x ≥ 1", "-2 < x ≤ 1", "x ≤ -2 או x ≥ 1", 2, false),

            // 7.2 Calculus (course 12), 9 questions
            new Q("12001", "גבולות", Difficulty.EASY,
                    "חשבו: lim(x→2) (x² - 4)/(x - 2)",
                    "0", "4", "2", "לא קיים", 2, false),
            new Q("12002", "גבולות", Difficulty.MEDIUM,
                    "חשבו: lim(x→∞) (3x² + x)/(x² - 5)",
                    "3", "0", "∞", "1/3", 1, false),
            new Q("12003", "גבולות", Difficulty.HARD,
                    "חשבו: lim(x→0) sin(3x)/x",
                    "1", "0", "3", "1/3", 3, false),
            new Q("12004", "נגזרות", Difficulty.EASY,
                    "מהי הנגזרת של f(x) = x³?",
                    "3x²", "x²", "3x", "x⁴/4", 1, false),
            new Q("12005", "נגזרות", Difficulty.EASY,
                    "מהי הנגזרת של f(x) = sin(x)?",
                    "-sin(x)", "cos(x)", "-cos(x)", "tan(x)", 2, false),
            new Q("12006", "נגזרות", Difficulty.MEDIUM,
                    "מהי הנגזרת של f(x) = x·e^x?",
                    "e^x", "x·e^x", "(1 + x)·e^x", "(x - 1)·e^x", 3, false),
            new Q("12007", "נגזרות", Difficulty.HARD,
                    "לפונקציה f(x) = x³ - 3x יש מינימום מקומי בנקודה:",
                    "x = 1", "x = -1", "x = 0", "x = 3", 1, true),
            new Q("12008", "אינטגרלים", Difficulty.EASY,
                    "חשבו: ∫ 2x dx",
                    "x² + C", "2 + C", "x²/2 + C", "2x² + C", 1, false),
            new Q("12009", "אינטגרלים", Difficulty.MEDIUM,
                    "חשבו את השטח מתחת ל-y = x² בין x=0 ל-x=3",
                    "9", "27", "3", "6", 1, true),

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
                    "Equal objects must have equal hash codes",
                    "Equal hash codes mean the objects are equal",
                    "hashCode must be unique for every object",
                    "Overriding equals alone is always safe", 1, false),
            new Q("21005", "Collections", Difficulty.EASY,
                    "Which collection forbids duplicate elements?",
                    "ArrayList", "HashSet", "LinkedList", "ArrayDeque", 2, false),
            new Q("21006", "Collections", Difficulty.EASY,
                    "Which interface does HashMap implement?",
                    "List", "Set", "Map", "Queue", 3, true),
            new Q("21007", "Collections", Difficulty.MEDIUM,
                    "What is the average-case time complexity of HashMap.get?",
                    "O(1)", "O(log n)", "O(n)", "O(n log n)", 1, false),
            new Q("21008", "Collections", Difficulty.HARD,
                    "Removing an element from an ArrayList inside a for-each loop throws:",
                    "IndexOutOfBoundsException", "ConcurrentModificationException",
                    "UnsupportedOperationException",
                    // Document writes "Nothing - it is safe" with an em dash; PRD 4.1 forbids it.
                    "Nothing, it is safe", 2, false),
            new Q("21009", "Exceptions", Difficulty.EASY,
                    "Which of these is a checked exception?",
                    "NullPointerException", "IOException", "ArithmeticException",
                    "IllegalStateException", 2, false),
            new Q("21010", "Recursion", Difficulty.EASY,
                    "What does a recursive method need in order to terminate?",
                    "A base case", "A static modifier", "An enclosing loop",
                    "A return null statement", 1, true),
            new Q("21011", "Recursion", Difficulty.MEDIUM,
                    "Recursion with no reachable base case fails with:",
                    "OutOfMemoryError", "StackOverflowError", "IllegalStateException",
                    "An infinite loop and no error", 2, false),

            // 7.4 Databases (course 22), 9 questions
            new Q("22001", "SQL Queries", Difficulty.EASY,
                    "Which clause filters rows before grouping?",
                    "HAVING", "WHERE", "ORDER BY", "LIMIT", 2, false),
            new Q("22002", "SQL Queries", Difficulty.EASY,
                    "Which join returns every row of the left table?",
                    "INNER JOIN", "LEFT JOIN", "CROSS JOIN", "SELF JOIN", 2, true),
            new Q("22003", "SQL Queries", Difficulty.MEDIUM,
                    "COUNT(column) differs from COUNT(*) because it:",
                    "Is always faster", "Ignores NULLs", "Counts distinct values only",
                    "Requires an index", 2, false),
            new Q("22004", "SQL Queries", Difficulty.HARD,
                    "A join of two tables returns more rows than either table holds. The cause is:",
                    "A missing index", "Duplicate values in the join key",
                    "A NULL in the ON clause", "An implicit CROSS JOIN, always", 2, false),
            new Q("22005", "Normalization", Difficulty.EASY,
                    "First normal form requires every column to be:",
                    "Indexed", "Atomic", "Unique", "Non-null", 2, false),
            new Q("22006", "Normalization", Difficulty.MEDIUM,
                    "Removing a partial dependency on part of a composite key achieves:",
                    "1NF", "2NF", "3NF", "BCNF", 2, true),
            new Q("22007", "Normalization", Difficulty.HARD,
                    "A table in 3NF but not in BCNF must contain:",
                    "A transitive dependency", "A determinant that is not a candidate key",
                    "A repeating group", "A surrogate key", 2, false),
            new Q("22008", "Transactions", Difficulty.EASY,
                    "What does the \"D\" in ACID stand for?",
                    "Distributed", "Durability", "Deterministic", "Deferred", 2, false),
            new Q("22009", "Transactions", Difficulty.MEDIUM,
                    "Which isolation level still permits a phantom read?",
                    "SERIALIZABLE", "REPEATABLE READ", "READ COMMITTED", "None of them", 3, false));

    private static final List<V2> SECOND_VERSIONS = List.of(
            // Rewords the stem; answers unchanged. Exam 1 stays pinned to v1 (7.5, 8.1).
            new V2("11005", "מצאו את שורשי המשוואה x² - 5x + 6 = 0",
                    "1, 6", "2, 3", "-2, -3", "0, 5", 2),
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
                    "A missing index", "Duplicate values in the join key",
                    "A NULL in the ON clause", "An implicit CROSS JOIN, always", 2));

    /** Ten questions carry an illustration; the bytes arrive later under docs/seed/img/. */
    static long illustratedCount() {
        return QUESTIONS.stream().filter(Q::image).count();
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
                    null, authorOf(session, course), firstVersions));
            versions++;
        }

        for (V2 second : SECOND_VERSIONS) {
            long questionId = SeedLookup.requireQuestionId(session, second.displayId());
            if (SeedLookup.findQuestionVersionId(session, questionId, 2).isPresent()) {
                continue;
            }
            Q original = originalOf(second.displayId());
            String course = second.displayId().substring(0, 2);

            session.persist(new QuestionVersion(questionId, 2, second.text(),
                    second.a1(), second.a2(), second.a3(), second.a4(),
                    (byte) second.correct(), original.topic(), original.difficulty(),
                    null, authorOf(session, course), secondVersions));
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

    private static long authorOf(Session session, String course) {
        String username = COURSE_AUTHOR.stream()
                .filter(pair -> pair[0].equals(course))
                .map(pair -> pair[1])
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no seed author for course " + course));
        return SeedLookup.requireUserId(session, username);
    }
}
