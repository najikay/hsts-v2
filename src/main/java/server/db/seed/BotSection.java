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
                    "משוואה ליניארית היא משוואה שבה המשתנה מופיע בחזקה ראשונה בלבד, ללא חזקות, "
                            + "שורשים או מכפלות של נעלמים. הצורה הכללית היא ax + b = 0 כאשר a שונה מאפס, "
                            + "והפתרון היחיד הוא x = -b/a. הפתרון מתבצע על ידי בידוד המשתנה: מעבירים "
                            + "אגפים תוך שינוי סימן, מכנסים איברים דומים, ולבסוף מחלקים במקדם של המשתנה. "
                            + "כאשר יש שברים במשוואה נהוג לכפול את שני האגפים במכנה המשותף כדי להיפטר מהם "
                            + "לפני הבידוד. כאשר יש סוגריים פותחים אותם תחילה לפי חוק הפילוג. מערכת של "
                            + "שתי משוואות בשני נעלמים נפתרת באחת משתי שיטות. בשיטת ההצבה מבודדים משתנה "
                            + "אחד מאחת המשוואות ומציבים את הביטוי שהתקבל במשוואה השנייה. בשיטת החיבור "
                            + "והחיסור כופלים את המשוואות במספרים מתאימים כך שמקדמי אחד המשתנים יהיו "
                            + "נגדיים, ואז מחברים את המשוואות והמשתנה מצטמצם. שתי השיטות נותנות את אותה "
                            + "תשובה, והבחירה ביניהן היא עניין של נוחות: הצבה נוחה כשמקדם של אחד המשתנים "
                            + "הוא אחד, וחיבור וחיסור נוח כשהמקדמים כבר קרובים. לכל מערכת יש שלוש "
                            + "אפשרויות בלבד, ולכולן יש משמעות גאומטרית. אם הישרים נחתכים בנקודה אחת יש "
                            + "פתרון יחיד. אם שתי המשוואות מתארות את אותו ישר יש אינסוף פתרונות, ובפתרון "
                            + "האלגברי יתקבל פסוק אמת כמו 0 = 0. אם הן מתארות ישרים מקבילים אין פתרון "
                            + "כלל, ובפתרון יתקבל פסוק שקר כמו 0 = 5. הטעות הנפוצה ביותר היא שכחת שינוי "
                            + "הסימן במעבר אגף."),
            new SeedSource(1, "פונקציות ריבועיות: פרק 3",
                    "פונקציה ריבועית היא פונקציה מהצורה y = ax² + bx + c כאשר a שונה מאפס. הגרף "
                            + "שלה הוא פרבולה, ומקדם a קובע את כיוון הפתיחה: אם a חיובי הפרבולה פותחת "
                            + "כלפי מעלה ויש לה נקודת מינימום, ואם a שלילי היא פותחת כלפי מטה ויש לה "
                            + "נקודת מקסימום. ככל שהערך המוחלט של a גדול יותר, הפרבולה צרה יותר. שורשי "
                            + "הפונקציה, כלומר נקודות החיתוך עם ציר ה-x, נמצאים על ידי הנוסחה x = (-b ± "
                            + "√(b²-4ac)) / 2a. הביטוי b²-4ac נקרא דיסקרימיננטה ומסומן בדרך כלל באות "
                            + "דלתא. הוא קובע את מספר השורשים: אם הוא חיובי יש שני שורשים שונים, אם הוא "
                            + "אפס יש שורש כפול אחד והפרבולה משיקה לציר ה-x, ואם הוא שלילי אין שורשים "
                            + "ממשיים והפרבולה כולה נמצאת מעל הציר או מתחתיו. נקודת החיתוך עם ציר ה-y "
                            + "מתקבלת תמיד בהצבת x = 0 ולכן שווה ל-c. ציר הסימטריה של הפרבולה הוא הישר x "
                            + "= -b/2a, וקודקוד הפרבולה נמצא על ציר זה. הצבת ערך זה בפונקציה נותנת את ערך "
                            + "הקיצון. כאשר ידועים שני השורשים, ציר הסימטריה נמצא גם באמצע ביניהם, וזו "
                            + "דרך מהירה יותר לחשב את הקודקוד. ניתן לכתוב את הפונקציה גם בצורת קודקוד y = "
                            + "a(x-p)² + k, כאשר (p,k) הוא הקודקוד. צורה זו נוחה לשרטוט ולזיהוי הזזות של "
                            + "הגרף."),
            new SeedSource(2, "גבולות: הגדרה ושימוש",
                    "גבול של פונקציה בנקודה מתאר לאן מתקרבים ערכי הפונקציה כאשר המשתנה מתקרב "
                            + "לנקודה, בלי להתייחס כלל לערך הפונקציה בנקודה עצמה. זו הבחנה מהותית: "
                            + "פונקציה יכולה להיות לא מוגדרת בנקודה ובכל זאת יהיה לה גבול שם, ולהפך, ערך "
                            + "הפונקציה בנקודה יכול להיות שונה מהגבול. כאשר הגבול קיים ושווה לערך "
                            + "הפונקציה בנקודה, אומרים שהפונקציה רציפה באותה נקודה. ניתן להתקרב לנקודה "
                            + "משני הכיוונים, ולכן מוגדרים גם גבול מימין וגבול משמאל. הגבול קיים אם ורק "
                            + "אם שני הגבולות החד-צדדיים קיימים ושווים זה לזה. כאשר הם שונים, הפונקציה "
                            + "קופצת בנקודה ואין לה גבול שם. בחישוב מנסים תחילה הצבה ישירה. אם ההצבה "
                            + "נותנת מספר, זהו הגבול. כאשר הצבה ישירה נותנת ביטוי מהצורה אפס חלקי אפס "
                            + "מדובר בגבול לא מוגדר שדורש טיפול אלגברי לפני ההצבה. שלוש השיטות המרכזיות "
                            + "הן פירוק לגורמים וצמצום הגורם המשותף שמאפס את המכנה, הכפלה בצמוד כאשר "
                            + "מופיע שורש, ושימוש בגבולות מיוחדים ידועים. אם ההצבה נותנת מספר שונה מאפס "
                            + "חלקי אפס, הגבול הוא אינסוף או מינוס אינסוף ולפונקציה יש אסימפטוטה אנכית "
                            + "באותה נקודה. גבול באינסוף מתאר את התנהגות הפונקציה לטווח רחוק. בפונקציה "
                            + "רציונלית משווים את חזקות המונה והמכנה: אם החזקה במכנה גדולה יותר הגבול הוא "
                            + "אפס, אם הן שוות הגבול הוא יחס המקדמים המובילים, ואם החזקה במונה גדולה יותר "
                            + "הגבול הוא אינסוף. גבול סופי באינסוף מציין אסימפטוטה אופקית."),
            new SeedSource(2, "כללי גזירה",
                    "הנגזרת מודדת את קצב השינוי של פונקציה, ומבחינה גאומטרית היא שיפוע המשיק "
                            + "לגרף הפונקציה בנקודה. הגדרתה היא גבול של יחס ההפרשים כאשר ההפרש שואף לאפס, "
                            + "אך בפועל מחשבים אותה באמצעות כללי גזירה ולא מההגדרה. כללי הגזירה הבסיסיים: "
                            + "נגזרת של קבוע היא אפס; נגזרת של x בחזקת n היא n כפול x בחזקת n פחות אחת; "
                            + "נגזרת של סכום היא סכום הנגזרות; ונגזרת של קבוע כפול פונקציה היא הקבוע כפול "
                            + "הנגזרת. נגזרת של מכפלה נתונה על ידי f'g + fg', ושימו לב שהיא אינה מכפלת "
                            + "הנגזרות. נגזרת של מנה נתונה על ידי (f'g - fg')/g², והסדר במונה חשוב. כלל "
                            + "השרשרת קובע שנגזרת של הרכבת פונקציות היא מכפלת הנגזרת החיצונית בנגזרת "
                            + "הפנימית, והוא הכלל הנחוץ בכל פעם שמופיעה פונקציה בתוך פונקציה. השימוש "
                            + "המרכזי של הנגזרת הוא חקירת פונקציות. נקודות קיצון חשודות נמצאות היכן "
                            + "שהנגזרת מתאפסת. כדי לקבוע את סוג הקיצון בודקים את סימן הנגזרת משני צדי "
                            + "הנקודה: מעבר מחיובי לשלילי מציין מקסימום, ומעבר משלילי לחיובי מציין "
                            + "מינימום. לחלופין משתמשים במבחן הנגזרת השנייה: אם הנגזרת השנייה בנקודה "
                            + "חיובית מדובר במינימום, ואם היא שלילית מדובר במקסימום. הפונקציה עולה בקטע "
                            + "שבו הנגזרת חיובית ויורדת בקטע שבו היא שלילית. נקודות שבהן הנגזרת השנייה "
                            + "מתאפסת ומחליפה סימן הן נקודות פיתול, שבהן משתנה כיוון הקעירות של הגרף. "
                            + "חקירה מלאה כוללת תחום הגדרה, נקודות חיתוך עם הצירים, תחומי עלייה וירידה "
                            + "ונקודות הקיצון."),
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
