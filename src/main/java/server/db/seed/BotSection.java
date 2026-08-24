package server.db.seed;

import org.hibernate.Session;
import server.db.entities.Bot;
import server.db.entities.BotMessage;
import server.db.entities.BotSession;
import server.db.entities.BotSource;
import server.db.entities.BotSourceType;
import server.db.entities.BotTranscript;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Seed §10: four bots, eight sources and eight recorded sessions (E2.15).
 *
 * <h2>Transcription decisions, all flagged in the PR report</h2>
 *
 * <ol>
 *   <li><b>Every source is {@code TEXT}, and the document contradicts itself about this.</b>
 *       §10's preamble rules that "all eight seeded sources are {@code type = TEXT}, with
 *       {@code raw} = the UTF-8 bytes of {@code extracted_text}", and gives the reasoning:
 *       shipping binary fixtures would mean the seed could only load from a checkout carrying
 *       those files, and the PDF extraction path is covered by E16's own tests and demoed live.
 *       The per-source list below it then labels five of the eight <b>PDF</b> or <b>DOCX</b>.
 *       Both values are legal in {@code type ENUM('PDF','DOCX','TEXT')}, so a mechanical
 *       transcription would store five wrong types and nothing would fail. The ruling wins over
 *       the labels, which is what "each source keeps its original document name in
 *       {@code title}, so the set still reads as mixed" means.</li>
 *   <li><b>{@code bot_sources.added_by} is inferred.</b> NOT NULL with a foreign key to
 *       {@code users}, and §10 supplies no value. Set to the bot's course teacher, following
 *       the same precedent D9 sets for question authorship: bots 1 and 2 to
 *       {@code dana.cohen}, bot 3 to the first-listed teacher of the only co-taught course, and
 *       bot 4 to {@code michal.sharon}.</li>
 *   <li><b>Timestamps are derived, not invented.</b> {@code bot_sources.updated_at} sits before
 *       any session that reads the source; {@code bot_sessions.started_at} and
 *       {@code updated_at} come from §10.2's own {@code asked} column.</li>
 *   <li><b>Em dashes are replaced</b> in two bot names, <b>four</b> source titles and one answer.
 *       PRD §4.1, and every one of those is rendered on a screen.</li>
 * </ol>
 *
 * <h2>The transcript is written the way the product writes it</h2>
 *
 * <p>{@code bot_sessions.transcript} is NOT NULL JSON and §10 does not specify its shape, but
 * E16 does: {@code JpaBotStore.appendExchange} appends two turns per exchange, the student's
 * question and the bot's answer, with roles {@code "student"} and {@code "bot"}, and dual-writes
 * the normalised {@code bot_messages} row in the same method "so there is no caller that could
 * write half of it" (F12.9, ARCHITECTURE §5).
 *
 * <p>This section produces exactly that shape rather than inventing one, because E16's queries
 * are what read these rows. A seed whose transcripts differed structurally from the product's
 * own would make the bot history screen look correct on live data and wrong on the demo data,
 * which is the worst way round.
 *
 * <h2>Bot 4 is inactive and has no sessions, on purpose</h2>
 *
 * <p>S-31 lets a student use a bot only if they are enrolled <em>and</em> the bot is active.
 * Without an inactive bot there is no way to demonstrate the second half of that rule, so bot 4
 * is seeded inactive and never used. Its two sources still exist: a bot that was switched off
 * did not lose its material.
 */
final class BotSection implements SeedSection {

    private record SeedBot(String course, String name, boolean active, String owner) { }

    private record SeedSource(int bot, String title, String body) { }

    private record SeedSession(int bot, String student, int daysAgo, String provider,
                               String question, String answer) { }

    private static final List<SeedBot> BOTS = List.of(
            // Two names carry an em dash in the document; PRD 4.1 forbids it on screen.
            new SeedBot("11", "Study assistant: Algebra", true, "dana.cohen"),
            new SeedBot("12", "Study assistant: Calculus", true, "dana.cohen"),
            new SeedBot("21", "Java Study Assistant", true, "avi.mizrahi"),
            // Inactive: the S-31 refusal demo has nothing to show without it.
            new SeedBot("22", "Databases Study Assistant", false, "michal.sharon"));

    private static final List<SeedSource> SOURCES = List.of(
            new SeedSource(1, "Linear equations: a summary",
                    "A linear equation is an equation in which the variable appears only to the "
                            + "first power, with no powers, roots or products of unknowns. Its general "
                            + "form is ax + b = 0 where a is not zero, and its single solution is x = "
                            + "-b/a. It is solved by isolating the variable: move terms across the equals "
                            + "sign changing their sign, collect like terms, and finally divide by the "
                            + "coefficient of the variable. When the equation contains fractions, "
                            + "multiply both sides by the common denominator to clear them before "
                            + "isolating. When it contains brackets, open them first using the "
                            + "distributive law. A system of two equations in two unknowns is solved by "
                            + "one of two methods. In substitution, isolate one variable in one of the "
                            + "equations and substitute the resulting expression into the second. In "
                            + "elimination, multiply the equations by suitable numbers so that the "
                            + "coefficients of one variable are opposite, then add the equations and that "
                            + "variable cancels out. Both methods give the same answer, and choosing "
                            + "between them is a matter of convenience: substitution is easy when one "
                            + "variable has a coefficient of one, and elimination is easy when the "
                            + "coefficients are already close. Every system has exactly three "
                            + "possibilities, and each of them has a geometric meaning. If the lines "
                            + "cross at one point there is a single solution. If both equations describe "
                            + "the same line there are infinitely many solutions, and the algebra ends in "
                            + "a true statement such as 0 = 0. If they describe parallel lines there is "
                            + "no solution at all, and the algebra ends in a false statement such as 0 = "
                            + "5. The most common mistake is forgetting to change the sign when moving a "
                            + "term across."),
            new SeedSource(1, "Quadratic functions: chapter 3",
                    "A quadratic function is a function of the form y = ax² + bx + c where a is "
                            + "not zero. Its graph is a parabola, and the coefficient a decides which way "
                            + "it opens: if a is positive the parabola opens upwards and has a minimum "
                            + "point, and if a is negative it opens downwards and has a maximum point. "
                            + "The larger the absolute value of a, the narrower the parabola. The roots "
                            + "of the function, meaning the points where it crosses the x axis, are found "
                            + "with the formula x = (-b ± √(b²-4ac)) / 2a. The expression b²-4ac is "
                            + "called the discriminant and is usually written as delta. It decides how "
                            + "many roots there are: if it is positive there are two different roots, if "
                            + "it is zero there is a single double root and the parabola touches the x "
                            + "axis, and if it is negative there are no real roots and the whole parabola "
                            + "lies either above the axis or below it. The point where the graph crosses "
                            + "the y axis is always found by substituting x = 0 and therefore equals c. "
                            + "The axis of symmetry of the parabola is the line x = -b/2a, and the vertex "
                            + "of the parabola lies on that axis. Substituting this value into the "
                            + "function gives the extreme value. When both roots are known, the axis of "
                            + "symmetry is also halfway between them, which is a quicker way to compute "
                            + "the vertex. The function can also be written in vertex form y = a(x-p)² + "
                            + "k, where (p,k) is the vertex. That form is convenient for sketching and "
                            + "for recognising translations of the graph."),
            new SeedSource(2, "Limits: definition and use",
                    "The limit of a function at a point describes what the values of the "
                            + "function approach as the variable approaches that point, without referring "
                            + "at all to the value of the function at the point itself. That distinction "
                            + "is essential: a function can be undefined at a point and still have a "
                            + "limit there, and conversely the value of the function at a point can "
                            + "differ from the limit. When the limit exists and equals the value of the "
                            + "function at the point, the function is said to be continuous at that "
                            + "point. A point can be approached from either direction, so a limit from "
                            + "the right and a limit from the left are defined as well. The limit exists "
                            + "if and only if both one-sided limits exist and are equal to each other. "
                            + "When they differ, the function jumps at that point and has no limit there. "
                            + "To compute one, try direct substitution first. If the substitution gives a "
                            + "number, that number is the limit. When direct substitution gives an "
                            + "expression of the form zero over zero, the limit is indeterminate and "
                            + "needs algebraic work before substituting. The three main techniques are "
                            + "factorising and cancelling the common factor that makes the denominator "
                            + "zero, multiplying by the conjugate when a root appears, and using known "
                            + "special limits. If the substitution gives a number other than zero over "
                            + "zero, the limit is infinity or minus infinity and the function has a "
                            + "vertical asymptote at that point. A limit at infinity describes the "
                            + "behaviour of the function far out. For a rational function, compare the "
                            + "powers of the numerator and the denominator: if the power in the "
                            + "denominator is larger the limit is zero, if they are equal the limit is "
                            + "the ratio of the leading coefficients, and if the power in the numerator "
                            + "is larger the limit is infinity. A finite limit at infinity indicates a "
                            + "horizontal asymptote."),
            new SeedSource(2, "Rules of differentiation",
                    "The derivative measures the rate of change of a function, and "
                            + "geometrically it is the slope of the tangent to the graph of the function "
                            + "at a point. It is defined as the limit of the difference quotient as the "
                            + "difference tends to zero, but in practice it is computed with the rules of "
                            + "differentiation rather than from the definition. The basic rules of "
                            + "differentiation: the derivative of a constant is zero; the derivative of x "
                            + "to the power n is n times x to the power n minus one; the derivative of a "
                            + "sum is the sum of the derivatives; and the derivative of a constant times "
                            + "a function is the constant times the derivative. The derivative of a "
                            + "product is given by f'g + fg', and note that it is not the product of the "
                            + "derivatives. The derivative of a quotient is given by (f'g - fg')/g², and "
                            + "the order in the numerator matters. The chain rule states that the "
                            + "derivative of a composition of functions is the outer derivative times the "
                            + "inner derivative, and it is the rule needed every time one function "
                            + "appears inside another. The main use of the derivative is investigating "
                            + "functions. Candidate extreme points are found where the derivative is "
                            + "zero. To decide which kind of extreme point it is, check the sign of the "
                            + "derivative on both sides of the point: a change from positive to negative "
                            + "indicates a maximum, and a change from negative to positive indicates a "
                            + "minimum. Alternatively use the second derivative test: if the second "
                            + "derivative at the point is positive it is a minimum, and if it is negative "
                            + "it is a maximum. The function increases on an interval where the "
                            + "derivative is positive and decreases on an interval where it is negative. "
                            + "Points where the second derivative is zero and changes sign are inflection "
                            + "points, where the concavity of the graph changes direction. A full "
                            + "investigation covers the domain, the intercepts with the axes, the "
                            + "intervals of increase and decrease, and the extreme points."),
            new SeedSource(3, "OOP Fundamentals: Lecture Notes",
                    "Object-oriented programming in Java rests on four ideas. Encapsulation "
                            + "keeps fields private and exposes behaviour through methods, so an object "
                            + "controls its own invariants. A class that lets callers write its fields "
                            + "directly cannot guarantee anything about its own state, because every "
                            + "caller becomes responsible for rules the class was supposed to enforce. "
                            + "Accessors are not the point; control over change is. Inheritance lets a "
                            + "class extend another and reuse its behaviour, establishing an is-a "
                            + "relationship. It is powerful and easy to overuse. Composition, where a "
                            + "class holds another as a field and delegates to it, is usually the better "
                            + "default: it can be changed at runtime, it does not expose a superclass's "
                            + "internals to its subclasses, and it avoids deep hierarchies that are hard "
                            + "to follow. Prefer inheritance only when a subtype genuinely is a kind of "
                            + "its supertype. Polymorphism means a reference of a supertype can hold any "
                            + "subtype, and the call dispatches to the subtype's implementation at "
                            + "runtime rather than at compile time. This is what lets one loop over a "
                            + "list of shapes call area on each without knowing which shapes are in it. "
                            + "Abstraction hides how something works behind an interface or an abstract "
                            + "class, so callers depend on what a type promises rather than on how it "
                            + "delivers. An interface declares behaviour with no state; an abstract class "
                            + "may provide shared fields and partial implementation. A class implements "
                            + "many interfaces but extends only one class."),
            new SeedSource(3, "The Collections Framework",
                    "The Java Collections Framework is organised around three interfaces, and "
                            + "choosing between their implementations is mostly a question of what "
                            + "operation you do most often. A List is an ordered sequence that allows "
                            + "duplicates and addresses elements by index. ArrayList is backed by an "
                            + "array and gives constant-time indexed access, but inserting or removing in "
                            + "the middle shifts every later element. LinkedList gives constant-time "
                            + "insertion and removal at the ends, but reaching index n means walking n "
                            + "links. ArrayList is the right default; LinkedList earns its place only "
                            + "when you are repeatedly adding at the front. A Set forbids duplicates. "
                            + "HashSet offers constant-time membership tests on average but makes no "
                            + "promise about iteration order. LinkedHashSet preserves insertion order at "
                            + "a small cost. TreeSet keeps elements sorted, which costs logarithmic time "
                            + "per operation and requires the elements to be comparable. A Map stores "
                            + "key-value pairs with unique keys. HashMap is the default choice and offers "
                            + "average constant-time lookup, degrading when many keys collide in the same "
                            + "bucket. TreeMap keeps keys sorted; LinkedHashMap preserves insertion "
                            + "order. Every hash-based collection depends on the equals and hashCode "
                            + "contract. Two objects that are equal must return the same hash code, and "
                            + "an object used as a key must not change in a way that alters its hash "
                            + "while it is in the collection. Breaking either rule produces a collection "
                            + "that appears to lose entries, which is a bug that is hard to find later."),
            new SeedSource(4, "Normalization in Practice",
                    "Normalization organises tables to remove redundancy and the update "
                            + "anomalies that come with it. When the same fact is stored in more than one "
                            + "row, three problems follow. An update anomaly changes one copy and leaves "
                            + "the others stale. An insertion anomaly makes it impossible to record one "
                            + "fact without inventing another. A deletion anomaly loses a fact you wanted "
                            + "to keep as a side effect of removing one you did not. The forms build on "
                            + "each other. First normal form requires atomic column values, with no "
                            + "repeating groups and no lists packed into a single field. Second normal "
                            + "form additionally forbids partial dependencies, where a non-key column "
                            + "depends on only part of a composite primary key; a table whose primary key "
                            + "is a single column satisfies it automatically. Third normal form forbids "
                            + "transitive dependencies, where a non-key column depends on another non-key "
                            + "column rather than on the key directly. Boyce-Codd normal form tightens "
                            + "third by requiring every determinant to be a candidate key, which matters "
                            + "only in tables with several overlapping candidate keys. The working rule "
                            + "of thumb is that every non-key column should depend on the key, the whole "
                            + "key, and nothing but the key. Normalization is not free. Splitting a table "
                            + "means joining it back together on every read, and a heavily normalized "
                            + "schema can be slower for reporting. Denormalizing deliberately, with the "
                            + "duplication documented, is a legitimate decision; duplicating by accident "
                            + "is not."),
            new SeedSource(4, "Transactions and Isolation",
                    "A transaction groups statements so they succeed or fail together, and the "
                            + "ACID properties describe the guarantees it makes. Atomicity means "
                            + "all-or-nothing: either every statement takes effect or none does, so a "
                            + "transfer cannot debit one account without crediting the other. Consistency "
                            + "means the database's constraints hold before the transaction and after it. "
                            + "Isolation means concurrent transactions do not observe each other's "
                            + "partial work. Durability means a committed change survives a crash, "
                            + "because the change was written to a durable log before the commit was "
                            + "acknowledged. Isolation is the property with a dial on it, because full "
                            + "isolation is expensive. Three phenomena are what the levels are defined "
                            + "against. A dirty read sees another transaction's uncommitted change, which "
                            + "may then be rolled back. A non-repeatable read sees a different value when "
                            + "reading the same row twice, because another transaction committed in "
                            + "between. A phantom read sees a different set of rows for the same query, "
                            + "because another transaction inserted or deleted matching rows. The SQL "
                            + "standard defines four levels against those. READ UNCOMMITTED permits dirty "
                            + "reads. READ COMMITTED prevents them but allows non-repeatable and phantom "
                            + "reads. REPEATABLE READ additionally prevents non-repeatable reads. "
                            + "SERIALIZABLE prevents phantom reads too, at the cost of concurrency. "
                            + "Isolation is usually implemented with locks, and locks make deadlock "
                            + "possible: two transactions each holding what the other needs. Databases "
                            + "detect this and abort one of them, so application code must be prepared to "
                            + "retry."));

    private static final List<SeedSession> SESSIONS = List.of(
            new SeedSession(1, "noa.friedman", 12, "deepseek",
                    "How do you solve an equation with fractions?",
                    "Multiply both sides by the common denominator to clear the fractions, then solve as usual."),
            new SeedSession(1, "maya.levi", 10, "deepseek",
                    "What is a discriminant?",
                    "The expression b²-4ac. Its sign decides how many real roots the parabola has."),
            new SeedSession(1, "noa.friedman", 9, "deepseek",
                    "When does a parabola have no roots?",
                    "When the discriminant is negative, the whole parabola lies above the x axis or entirely below it."),
            new SeedSession(2, "tal.harari", 8, "deepseek",
                    "Why is the limit of sin(x)/x at zero equal to 1?",
                    "It is a special limit, proved geometrically with the unit circle and the squeeze theorem."),
            new SeedSession(3, "omer.katz", 6, "deepseek",
                    "When should I use a LinkedList instead of an ArrayList?",
                    "Only when you insert or remove at the ends far more often than you read by "
                            + "index."),
            // The one anthropic row: without it the provider column is a constant and the
            // ADR-009 fallback chain demonstrates nothing.
            new SeedSession(3, "roni.malka", 5, "anthropic",
                    "What is the difference between an interface and an abstract class?",
                    "An interface declares a contract and a class may implement many; an abstract "
                            + "class can hold state and a class may extend only one."),
            new SeedSession(3, "omer.katz", 4, "deepseek",
                    "Why did my for-each loop throw ConcurrentModificationException?",
                    "The list was structurally modified during iteration. Use an Iterator and "
                            + "call its remove method, or use removeIf."),
            new SeedSession(3, "noam.peretz", 2, "deepseek",
                    "What does the JVM do when recursion goes too deep?",
                    "Each call takes a stack frame; when the thread stack is exhausted the JVM "
                            + "throws StackOverflowError."));

    /** Sources predate every session, so a bot always had material before anyone asked. */
    private static final int SOURCES_ADDED_DAYS_BEFORE = -20;

    /** Matches BotSpeaker.STUDENT.wireName() and BotSpeaker.BOT.wireName() in E16. */
    private static final String ROLE_STUDENT = "student";
    private static final String ROLE_BOT = "bot";

    @Override
    public String name() {
        return "10 bot content";
    }

    @Override
    public void load(SeedContext context) {
        Session session = context.session();
        Instant sourcesAddedAt = context.times().dayOffsetAt(SOURCES_ADDED_DAYS_BEFORE, 9, 0);

        List<Long> botIds = new ArrayList<>();
        int bots = 0;
        for (SeedBot bot : BOTS) {
            java.util.Optional<Long> existing = SeedLookup.findBotByCourse(session, bot.course());
            if (existing.isPresent()) {
                botIds.add(existing.get());
                continue;
            }
            Bot row = new Bot(bot.course(), bot.name());
            row.setActive(bot.active());
            session.persist(row);
            session.flush();
            botIds.add(row.getId());
            bots++;
        }
        context.recordInserts("bots", bots);

        int sources = 0;
        for (SeedSource source : SOURCES) {
            long botId = botIds.get(source.bot() - 1);
            if (SeedLookup.findBotSourceId(session, botId, source.title()).isPresent()) {
                continue;
            }
            long addedBy = SeedLookup.requireUserId(session, BOTS.get(source.bot() - 1).owner());
            session.persist(new BotSource(botId, BotSourceType.TEXT, source.title(),
                    source.body().getBytes(StandardCharsets.UTF_8), source.body(),
                    addedBy, sourcesAddedAt));
            sources++;
        }
        context.recordInserts("bot_sources", sources);

        int sessions = 0;
        int messages = 0;
        for (SeedSession recorded : SESSIONS) {
            long botId = botIds.get(recorded.bot() - 1);
            long studentId = SeedLookup.requireUserId(session, recorded.student());
            Instant askedAt = context.times().dayOffsetAt(-recorded.daysAgo(), 15, 0);

            if (SeedLookup.findBotMessageId(session, botId, studentId, recorded.question())
                    .isPresent()) {
                continue;
            }

            BotSession conversation = new BotSession(botId, studentId, askedAt);
            conversation.setTranscript(new BotTranscript(List.of(
                    new BotTranscript.Turn(ROLE_STUDENT, recorded.question(), askedAt),
                    new BotTranscript.Turn(ROLE_BOT, recorded.answer(), askedAt))), askedAt);
            session.persist(conversation);
            session.flush();
            sessions++;

            session.persist(new BotMessage(botId, conversation.getId(), studentId,
                    recorded.question(), recorded.answer(), recorded.provider(), askedAt));
            messages++;
        }
        context.recordInserts("bot_sessions", sessions);
        context.recordInserts("bot_messages", messages);
    }
}
