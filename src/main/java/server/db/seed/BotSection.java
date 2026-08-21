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
            new SeedBot("11", "עוזר הלימוד: אלגברה", true, "dana.cohen"),
            new SeedBot("12", "עוזר הלימוד: חדו\"א", true, "dana.cohen"),
            new SeedBot("21", "Java Study Assistant", true, "avi.mizrahi"),
            // Inactive: the S-31 refusal demo has nothing to show without it.
            new SeedBot("22", "Databases Study Assistant", false, "michal.sharon"));

    private static final List<SeedSource> SOURCES = List.of(
            new SeedSource(1, "משוואות ליניאריות: סיכום",
                    "משוואה ליניארית היא משוואה שבה המשתנה מופיע בחזקה ראשונה בלבד. הפתרון מתבצע "
                            + "על ידי בידוד המשתנה: מעבירים אגפים תוך שינוי סימן, מכנסים איברים "
                            + "דומים ולבסוף מחלקים במקדם. מערכת של שתי משוואות בשני נעלמים נפתרת "
                            + "בשיטת ההצבה או בשיטת החיבור והחיסור. אם שתי המשוואות מתארות את אותו "
                            + "ישר יש אינסוף פתרונות, ואם הן מתארות ישרים מקבילים אין פתרון כלל."),
            new SeedSource(1, "פונקציות ריבועיות: פרק 3",
                    "פונקציה ריבועית היא פונקציה מהצורה y = ax² + bx + c כאשר a שונה מאפס. הגרף "
                            + "שלה הוא פרבולה: אם a חיובי הפרבולה פותחת כלפי מעלה ויש לה מינימום, "
                            + "ואם a שלילי היא פותחת כלפי מטה ויש לה מקסימום. שורשי הפונקציה "
                            + "נמצאים על ידי הנוסחה x = (-b ± √(b²-4ac)) / 2a. הביטוי b²-4ac נקרא "
                            + "דיסקרימיננטה: אם הוא חיובי יש שני שורשים, אם הוא אפס יש שורש כפול, "
                            + "ואם הוא שלילי אין שורשים ממשיים."),
            new SeedSource(2, "גבולות: הגדרה ושימוש",
                    "גבול של פונקציה בנקודה מתאר לאן מתקרבים ערכי הפונקציה כאשר המשתנה מתקרב "
                            + "לנקודה, בלי להתייחס לערך הפונקציה בנקודה עצמה. כאשר הצבה ישירה "
                            + "נותנת ביטוי מהצורה 0/0 מדובר בגבול לא מוגדר שדורש טיפול: פירוק "
                            + "לגורמים וצמצום, הכפלה בצמוד, או שימוש בגבולות מיוחדים."),
            new SeedSource(2, "כללי גזירה",
                    "הנגזרת מודדת את קצב השינוי של פונקציה. כללי הגזירה הבסיסיים: נגזרת של x "
                            + "בחזקת n היא n כפול x בחזקת n פחות אחת; נגזרת של מכפלה נתונה על ידי "
                            + "f'g + fg'; נגזרת של מנה נתונה על ידי (f'g - fg')/g²; וכלל השרשרת "
                            + "קובע שנגזרת של הרכבה היא מכפלת הנגזרות. נקודות קיצון נמצאות היכן "
                            + "שהנגזרת מתאפסת, וסוג הקיצון נקבע לפי סימן הנגזרת השנייה."),
            new SeedSource(3, "OOP Fundamentals: Lecture Notes",
                    "Object-oriented programming in Java rests on four ideas. Encapsulation keeps "
                            + "fields private and exposes behaviour through methods, so an object "
                            + "controls its own invariants. Inheritance lets a class extend "
                            + "another and reuse its behaviour, though composition is usually the "
                            + "better default. Polymorphism means a reference of a supertype can "
                            + "hold any subtype and dispatch to the subtype's implementation at "
                            + "runtime. Abstraction hides how something works behind an interface "
                            + "or an abstract class, so callers depend on what a type promises "
                            + "rather than on how it delivers."),
            new SeedSource(3, "The Collections Framework",
                    "The Java Collections Framework is organised around three interfaces. A List "
                            + "is an ordered sequence that allows duplicates; ArrayList gives "
                            + "constant-time indexed access while LinkedList gives constant-time "
                            + "insertion at the ends. A Set forbids duplicates; HashSet offers "
                            + "constant-time membership tests but no ordering, while TreeSet keeps "
                            + "elements sorted at logarithmic cost. A Map stores key-value pairs "
                            + "with unique keys; HashMap is the default choice and offers average "
                            + "constant-time lookup, degrading when many keys collide."),
            new SeedSource(4, "Normalization in Practice",
                    "Normalization organises tables to remove redundancy and the update anomalies "
                            + "that come with it. First normal form requires atomic column values, "
                            + "with no repeating groups. Second normal form additionally forbids "
                            + "partial dependencies, where a non-key column depends on only part "
                            + "of a composite primary key. Third normal form forbids transitive "
                            + "dependencies, where a non-key column depends on another non-key "
                            + "column. Boyce-Codd normal form tightens this by requiring every "
                            + "determinant to be a candidate key."),
            new SeedSource(4, "Transactions and Isolation",
                    "A transaction groups statements so they succeed or fail together, and the "
                            + "ACID properties describe the guarantees. Atomicity means "
                            + "all-or-nothing. Consistency means constraints hold before and "
                            + "after. Isolation means concurrent transactions do not observe each "
                            + "other partially; the SQL standard defines four levels, from READ "
                            + "UNCOMMITTED, which permits dirty reads, through READ COMMITTED and "
                            + "REPEATABLE READ to SERIALIZABLE, which prevents phantom reads at "
                            + "the cost of concurrency. Durability means a committed change "
                            + "survives a crash."));

    private static final List<SeedSession> SESSIONS = List.of(
            new SeedSession(1, "noa.friedman", 12, "deepseek",
                    "איך פותרים משוואה עם שברים?",
                    "מכפילים את שני האגפים במכנה המשותף כדי להיפטר מהשברים, ואז פותרים כרגיל."),
            new SeedSession(1, "maya.levi", 10, "deepseek",
                    "מה זו דיסקרימיננטה?",
                    "הביטוי b²-4ac. הסימן שלו קובע כמה שורשים ממשיים יש לפרבולה."),
            new SeedSession(1, "noa.friedman", 9, "deepseek",
                    "מתי לפרבולה אין שורשים?",
                    "כאשר הדיסקרימיננטה שלילית, הפרבולה כולה מעל ציר x או כולה מתחתיו."),
            new SeedSession(2, "tal.harari", 8, "deepseek",
                    "למה הגבול של sin(x)/x באפס שווה 1?",
                    "זהו גבול מיוחד שמוכיחים גיאומטרית בעזרת מעגל היחידה וכלל הסנדוויץ."),
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
