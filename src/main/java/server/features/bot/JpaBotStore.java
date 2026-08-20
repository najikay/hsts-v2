package server.features.bot;

import common.dto.bot.BotSourceKind;
import common.dto.bot.BotSpeaker;
import common.dto.bot.BotTurn;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.Transactions;
import server.db.entities.Bot;
import server.db.entities.BotMessage;
import server.db.entities.BotSource;
import server.db.entities.BotSourceType;
import server.db.entities.BotTranscript;
import server.db.projections.BotActivityCount;
import server.db.projections.BotBankQuestion;
import server.db.projections.BotSourceInfo;
import server.db.projections.BotSourceText;
import server.db.repos.BotRepository;
import server.db.repos.CourseRepository;
import server.db.repos.QuestionRepository;
import server.db.repos.UserRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The real {@link BotStore}, over Hibernate (Logic tier, E16).
 *
 * <p>Repositories do the reading, this does the mapping, and the services above it
 * never see an entity. The split matters most in two places:
 *
 * <ul>
 *   <li><b>the transcript.</b> {@code bot_sessions.transcript} is JSON with
 *       {@code "student"} / {@code "bot"} role strings; the wire has a
 *       {@link BotSpeaker} enum. Both vocabularies are correct for their side, and
 *       this class is the one place they meet;</li>
 *   <li><b>the dual write.</b> {@link #appendExchange} is the F12.9 requirement,
 *       and it is one method rather than two so there is no caller that could
 *       write half of it.</li>
 * </ul>
 *
 * <p><b>What it does not import.</b> No exam, execution, attempt or grade
 * repository appears in this file, and none can be added without the compiled
 * class picking up a reference that {@code BotIsolationGuardTest} fails on. That
 * is the F12.8 boundary at its narrowest point: the class that could reach
 * anything is the class that reaches only this.
 */
public final class JpaBotStore implements BotStore {

    private static final Logger log = LoggerFactory.getLogger(JpaBotStore.class);

    private final SessionFactory factory;
    private final BotRepository bots = new BotRepository();
    private final CourseRepository courses = new CourseRepository();
    private final QuestionRepository questions = new QuestionRepository();
    private final UserRepository users = new UserRepository();

    public JpaBotStore(SessionFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public <T> T inTx(Function<BotData, T> work) {
        Objects.requireNonNull(work, "work");
        return Transactions.inTx(factory, session -> work.apply(new JpaBotData(session)));
    }

    /** One transaction's worth of access. */
    private final class JpaBotData implements BotData {

        private final Session session;

        private JpaBotData(Session session) {
            this.session = session;
        }

        // ===================== Courses and people ========================

        @Override
        public boolean isEnrolled(long studentId, String courseCode) {
            return courses.isEnrolled(session, studentId, courseCode);
        }

        @Override
        public boolean teaches(long teacherId, String courseCode) {
            return courses.teaches(session, teacherId, courseCode);
        }

        @Override
        public Optional<String> courseName(String courseCode) {
            return courses.findName(session, courseCode);
        }

        @Override
        public List<Long> otherTeachersOf(String courseCode, long excluding) {
            return courses.findOtherTeachers(session, courseCode, excluding);
        }

        @Override
        public Map<Long, String> displayNames(Collection<Long> userIds) {
            if (userIds == null || userIds.isEmpty()) {
                return Map.of();
            }
            Map<Long, String> names = new LinkedHashMap<>();
            for (Long id : userIds) {
                if (id == null || names.containsKey(id)) {
                    continue;
                }
                // session.get through the repository: inside one transaction the
                // second lookup of the same teacher is a first-level cache hit, so
                // a table of ten sources by two teachers is two reads.
                users.findById(session, id).ifPresent(user -> names.put(id, user.getFullName()));
            }
            return Map.copyOf(names);
        }

        // ===================== The bot and its material ==================

        @Override
        public Optional<BotRecord> botForCourse(String courseCode) {
            return bots.findByCourse(session, courseCode).map(this::toRecord);
        }

        @Override
        public BotRecord createBot(String courseCode, String name) {
            Optional<Bot> existing = bots.findByCourse(session, courseCode);
            if (existing.isPresent()) {
                // S-30: the second teacher of a course joins the bot that is there.
                // Not an error, and not a second bot - the unique key would refuse
                // one anyway, and this is the friendlier half of the same rule.
                log.debug("Bot for course {} already exists; joining it", courseCode);
                return toRecord(existing.get());
            }
            Bot bot = new Bot(courseCode, name);
            session.persist(bot);
            // Flush so the identity id exists before the caller uses it in the same
            // transaction (it immediately reads the sources back for the response).
            session.flush();
            log.info("Created study bot {} for course {}", bot.getId(), courseCode);
            return toRecord(bot);
        }

        @Override
        public void setActive(long botId, boolean active) {
            Bot bot = session.get(Bot.class, botId);
            if (bot != null) {
                bot.setActive(active);
            }
        }

        @Override
        public List<BotSourceInfo> sourceInfos(long botId) {
            return bots.findSourceInfos(session, botId);
        }

        @Override
        public List<BotSourceText> sourceTexts(long botId) {
            return bots.findSourceTexts(session, botId);
        }

        @Override
        public long addSource(long botId, BotSourceKind kind, String title,
                              byte[] raw, String text, long addedBy, Instant at) {
            // For a TEXT source the pasted text IS the original, so it is stored as
            // both columns rather than making `raw` nullable for one kind (the
            // lead's E2 PR 1 decision, recorded on BotSourceType).
            byte[] bytes = raw != null && raw.length > 0
                    ? raw : text.getBytes(StandardCharsets.UTF_8);
            BotSource source = new BotSource(botId, toEntityType(kind), title, bytes, text, addedBy, at);
            session.persist(source);
            session.flush();
            return source.getId();
        }

        @Override
        public boolean removeSource(long botId, long sourceId) {
            Optional<BotSource> found = bots.findSourceOfBot(session, botId, sourceId);
            found.ifPresent(session::remove);
            return found.isPresent();
        }

        @Override
        public List<BotBankQuestion> bankQuestions(String courseCode, int limit) {
            return questions.findBankForBot(session, courseCode, limit);
        }

        // ===================== Conversations =============================

        @Override
        public Optional<StoredSession> ownSession(long sessionId, long studentId) {
            return bots.findOwnSession(session, sessionId, studentId).map(this::toStored);
        }

        @Override
        public List<StoredSession> ownSessions(long botId, long studentId) {
            return bots.findSessionsOf(session, botId, studentId).stream()
                    .map(this::toStored)
                    .toList();
        }

        @Override
        public long appendExchange(Long sessionId, long botId, long studentId,
                                   String question, String answer, String provider, Instant at) {
            server.db.entities.BotSession conversation = sessionId == null
                    ? null : bots.findOwnSession(session, sessionId, studentId).orElse(null);
            if (conversation == null) {
                conversation = new server.db.entities.BotSession(botId, studentId, at);
                session.persist(conversation);
                session.flush();
            }

            // Half one: the student's own copy, appended to the JSON transcript.
            List<BotTranscript.Turn> turns = new ArrayList<>(conversation.getTranscript().turns());
            turns.add(new BotTranscript.Turn(BotSpeaker.STUDENT.wireName(), question, at));
            turns.add(new BotTranscript.Turn(BotSpeaker.BOT.wireName(), answer, at));
            conversation.setTranscript(new BotTranscript(turns), at);

            // Half two: the analytics-facing row. Same transaction, F12.9.
            session.persist(new BotMessage(botId, conversation.getId(), studentId,
                    question, answer, provider, at));
            return conversation.getId();
        }

        // ===================== Analytics =================================

        @Override
        public long countMessages(long botId) {
            return bots.countMessages(session, botId);
        }

        @Override
        public List<BotActivityCount> activity(long botId, Instant since) {
            return bots.findActivity(session, botId, since);
        }

        @Override
        public List<String> recentQuestions(long botId, int limit) {
            return bots.findRecentQuestions(session, botId, limit);
        }

        // ===================== Mapping ===================================

        private BotRecord toRecord(Bot bot) {
            String name = courses.findName(session, bot.getCourseCode()).orElse(bot.getCourseCode());
            return new BotRecord(bot.getId(), bot.getCourseCode(), name, bot.getName(), bot.isActive());
        }

        private StoredSession toStored(server.db.entities.BotSession stored) {
            List<BotTurn> turns = stored.getTranscript().turns().stream()
                    .map(turn -> new BotTurn(
                            BotSpeaker.fromWireName(turn.role()), turn.text(), turn.at()))
                    .toList();
            Bot bot = session.get(Bot.class, stored.getBotId());
            return new StoredSession(stored.getId(), stored.getBotId(),
                    bot == null ? "" : bot.getCourseCode(),
                    stored.getStartedAt(), stored.getUpdatedAt(), turns);
        }
    }

    /** The wire enum to the column enum; the only place the two meet. */
    private static BotSourceType toEntityType(BotSourceKind kind) {
        return switch (kind) {
            case PDF -> BotSourceType.PDF;
            case DOCX -> BotSourceType.DOCX;
            case TEXT -> BotSourceType.TEXT;
        };
    }

    /** The column enum to the wire enum; used when mapping rows back out. */
    public static BotSourceKind toWireKind(BotSourceType type) {
        return switch (type) {
            case PDF -> BotSourceKind.PDF;
            case DOCX -> BotSourceKind.DOCX;
            case TEXT -> BotSourceKind.TEXT;
        };
    }
}
