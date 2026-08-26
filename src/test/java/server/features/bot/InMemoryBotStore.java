package server.features.bot;

import common.dto.bot.BotSourceKind;
import common.dto.bot.BotSpeaker;
import common.dto.bot.BotTurn;
import server.db.entities.BotSourceType;
import server.db.projections.BotActivityCount;
import server.db.projections.BotBankQuestion;
import server.db.projections.BotSourceInfo;
import server.db.projections.BotSourceText;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * A {@link BotStore} in a few maps, for the unit tests of the rules (E16.8/E16.9).
 *
 * <p>The counterpart of {@code InMemoryExamStore}, and there for the same reason:
 * every guard, every C-4 branch and every refusal is a property of a service, not
 * of a database, and testing them through Hibernate would make a suite that is
 * slower, harder to read and no more convincing. The queries themselves are
 * covered by {@code BotFeatureRepositoryContract}, which runs against both engines.
 *
 * <p>It implements the interface honestly rather than conveniently: the scoping
 * rules that matter are reproduced here ({@code ownSession} really does check the
 * student, {@code removeSource} really does check the bot), so a test that passes
 * against this store is testing the same rule the JPA one enforces.
 */
final class InMemoryBotStore implements BotStore, BotData {

    /** Courses that exist, code to display name. */
    private final Map<String, String> courses = new LinkedHashMap<>();
    /** Course code to the set of teacher ids. */
    private final Map<String, List<Long>> teachers = new LinkedHashMap<>();
    /** Course code to the set of enrolled student ids. */
    private final Map<String, List<Long>> enrolments = new LinkedHashMap<>();
    private final Map<Long, String> names = new LinkedHashMap<>();
    private final Map<String, BotRecord> bots = new LinkedHashMap<>();
    private final Map<String, List<BotBankQuestion>> bank = new LinkedHashMap<>();
    private final List<StoredSource> sources = new ArrayList<>();
    private final List<StoredSession> sessions = new ArrayList<>();
    private final List<StoredMessage> messages = new ArrayList<>();

    private long nextBotId = 900;
    private long nextSourceId = 500;
    private long nextSessionId = 700;

    /** How many transactions have been opened; a test asserts the dual write is one. */
    int transactions;

    /** A source with its text, which the real table also keeps together. */
    private record StoredSource(long sourceId, long botId, BotSourceKind kind, String title,
                                String text, long addedBy, Instant updatedAt, int version) {
    }

    /** The analytics-facing row, dual-written with the transcript. */
    private record StoredMessage(long botId, long sessionId, long studentId,
                                 String question, String answer, String provider, Instant askedAt) {
    }

    // ===================== Fixture building ==============================

    InMemoryBotStore course(String code, String name) {
        courses.put(code, name);
        return this;
    }

    InMemoryBotStore teaches(String code, long teacherId) {
        teachers.computeIfAbsent(code, key -> new ArrayList<>()).add(teacherId);
        return this;
    }

    InMemoryBotStore enrols(String code, long studentId) {
        enrolments.computeIfAbsent(code, key -> new ArrayList<>()).add(studentId);
        return this;
    }

    InMemoryBotStore user(long id, String displayName) {
        names.put(id, displayName);
        return this;
    }

    InMemoryBotStore bot(String code, String name, boolean active) {
        long id = ++nextBotId;
        bots.put(code, new BotRecord(id, code, courses.getOrDefault(code, code), name, active));
        return this;
    }

    InMemoryBotStore source(String code, String title, String text, long addedBy) {
        long botId = bots.get(code).botId();
        sources.add(new StoredSource(++nextSourceId, botId, BotSourceKind.TEXT, title, text,
                addedBy, Instant.parse("2026-08-01T09:00:00Z"), 1));
        return this;
    }

    InMemoryBotStore bankQuestion(String code, BotBankQuestion question) {
        bank.computeIfAbsent(code, key -> new ArrayList<>()).add(question);
        return this;
    }

    /** @return the id of the bot for a course, for assertions. */
    long botIdOf(String code) {
        return bots.get(code).botId();
    }

    /** @return every stored conversation, for assertions about the dual write. */
    List<StoredSession> allSessions() {
        return List.copyOf(sessions);
    }

    /** @return every analytics row, for assertions about the dual write. */
    int messageCount() {
        return messages.size();
    }

    /** @return the provider recorded on the last exchange. */
    String lastProvider() {
        return messages.isEmpty() ? "" : messages.get(messages.size() - 1).provider();
    }

    /** @return the id of the last source added. */
    long lastSourceId() {
        return sources.isEmpty() ? 0 : sources.get(sources.size() - 1).sourceId();
    }

    // ===================== BotStore ======================================

    @Override
    public <T> T inTx(Function<BotData, T> work) {
        transactions++;
        return work.apply(this);
    }

    // ===================== BotData =======================================

    @Override
    public boolean isEnrolled(long studentId, String courseCode) {
        return enrolments.getOrDefault(courseCode, List.of()).contains(studentId);
    }

    @Override
    public boolean teaches(long teacherId, String courseCode) {
        return teachers.getOrDefault(courseCode, List.of()).contains(teacherId);
    }

    @Override
    public Optional<String> courseName(String courseCode) {
        return Optional.ofNullable(courses.get(courseCode));
    }

    @Override
    public List<Long> otherTeachersOf(String courseCode, long excluding) {
        return teachers.getOrDefault(courseCode, List.of()).stream()
                .filter(id -> id != excluding)
                .toList();
    }

    @Override
    public Map<Long, String> displayNames(Collection<Long> userIds) {
        Map<Long, String> found = new LinkedHashMap<>();
        for (Long id : userIds) {
            if (names.containsKey(id)) {
                found.put(id, names.get(id));
            }
        }
        return Map.copyOf(found);
    }

    @Override
    public Optional<BotRecord> botForCourse(String courseCode) {
        return Optional.ofNullable(bots.get(courseCode));
    }

    @Override
    public BotRecord createBot(String courseCode, String name) {
        BotRecord existing = bots.get(courseCode);
        if (existing != null) {
            return existing;
        }
        BotRecord created = new BotRecord(++nextBotId, courseCode,
                courses.getOrDefault(courseCode, courseCode), name, true);
        bots.put(courseCode, created);
        return created;
    }

    @Override
    public void setActive(long botId, boolean active) {
        bots.replaceAll((code, bot) -> bot.botId() == botId
                ? new BotRecord(bot.botId(), bot.courseCode(), bot.courseName(), bot.name(), active)
                : bot);
    }

    @Override
    public List<BotSourceInfo> sourceInfos(long botId) {
        return sources.stream()
                .filter(source -> source.botId() == botId)
                .map(source -> new BotSourceInfo(source.sourceId(), source.botId(),
                        BotSourceType.valueOf(source.kind().name()), source.title(),
                        source.addedBy(), source.updatedAt(), source.version(),
                        source.text().length()))
                .toList();
    }

    @Override
    public List<BotSourceText> sourceTexts(long botId) {
        return sources.stream()
                .filter(source -> source.botId() == botId)
                .map(source -> new BotSourceText(source.sourceId(), source.title(), source.text()))
                .toList();
    }

    @Override
    public long addSource(long botId, BotSourceKind kind, String title,
                          byte[] raw, String text, long addedBy, Instant at) {
        long id = ++nextSourceId;
        sources.add(new StoredSource(id, botId, kind, title, text, addedBy, at, 1));
        return id;
    }

    @Override
    public boolean updateSource(long botId, long sourceId, BotSourceKind kind, String title,
                                byte[] raw, String text, Instant at) {
        for (int index = 0; index < sources.size(); index++) {
            StoredSource stored = sources.get(index);
            if (stored.botId() != botId || stored.sourceId() != sourceId) {
                continue;
            }
            // Faithful about the two things the B-21 rules depend on: the id and the author
            // survive, and the domain version is bumped, exactly as BotSource.replaceContent
            // does. A fixture that reassigned either would let a delete-and-re-add pass as an
            // edit, which is the whole defect.
            sources.set(index, new StoredSource(sourceId, botId, kind, title, text,
                    stored.addedBy(), at, stored.version() + 1));
            return true;
        }
        return false;
    }

    @Override
    public Map<Long, String> textSourceBodies(long botId) {
        Map<Long, String> bodies = new LinkedHashMap<>();
        for (StoredSource source : sources) {
            if (source.botId() == botId && source.kind() == BotSourceKind.TEXT) {
                bodies.put(source.sourceId(), source.text());
            }
        }
        return bodies;
    }

    @Override
    public boolean removeSource(long botId, long sourceId) {
        return sources.removeIf(source ->
                source.botId() == botId && source.sourceId() == sourceId);
    }

    @Override
    public List<BotBankQuestion> bankQuestions(String courseCode, int limit) {
        return bank.getOrDefault(courseCode, List.of()).stream().limit(limit).toList();
    }

    @Override
    public Optional<StoredSession> ownSession(long sessionId, long studentId) {
        return sessions.stream()
                .filter(session -> session.sessionId() == sessionId)
                .filter(session -> ownerOf(session.sessionId()) == studentId)
                .findFirst();
    }

    @Override
    public List<StoredSession> ownSessions(long botId, long studentId) {
        return sessions.stream()
                .filter(session -> session.botId() == botId)
                .filter(session -> ownerOf(session.sessionId()) == studentId)
                .sorted(Comparator.comparing(StoredSession::updatedAt).reversed())
                .toList();
    }

    @Override
    public long appendExchange(Long sessionId, long botId, long studentId,
                               String question, String answer, String provider, Instant at) {
        StoredSession conversation = sessionId == null ? null
                : ownSession(sessionId, studentId).orElse(null);
        if (conversation == null) {
            conversation = new StoredSession(++nextSessionId, botId,
                    bots.values().stream().filter(bot -> bot.botId() == botId)
                            .map(BotRecord::courseCode).findFirst().orElse(""),
                    at, at, List.of());
            owners.put(conversation.sessionId(), studentId);
            sessions.add(conversation);
        }
        List<BotTurn> turns = new ArrayList<>(conversation.turns());
        turns.add(new BotTurn(BotSpeaker.STUDENT, question, at));
        turns.add(new BotTurn(BotSpeaker.BOT, answer, at));

        StoredSession updated = new StoredSession(conversation.sessionId(), conversation.botId(),
                conversation.courseCode(), conversation.startedAt(), at, turns);
        sessions.replaceAll(session ->
                session.sessionId() == updated.sessionId() ? updated : session);
        messages.add(new StoredMessage(botId, updated.sessionId(), studentId,
                question, answer, provider, at));
        return updated.sessionId();
    }

    @Override
    public long countMessages(long botId) {
        return messages.stream().filter(message -> message.botId() == botId).count();
    }

    @Override
    public List<BotActivityCount> activity(long botId, Instant since) {
        Map<String, Long> byDay = new LinkedHashMap<>();
        messages.stream()
                .filter(message -> message.botId() == botId && !message.askedAt().isBefore(since))
                .forEach(message -> byDay.merge(
                        message.askedAt().atZone(ZoneOffset.UTC).toLocalDate().toString(),
                        1L, Long::sum));
        List<BotActivityCount> counts = new ArrayList<>();
        byDay.forEach((day, count) -> {
            String[] parts = day.split("-");
            counts.add(new BotActivityCount(Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), count));
        });
        return counts;
    }

    @Override
    public List<String> recentQuestions(long botId, int limit) {
        List<String> questions = new ArrayList<>(messages.stream()
                .filter(message -> message.botId() == botId)
                .map(StoredMessage::question)
                .toList());
        java.util.Collections.reverse(questions);
        return questions.stream().limit(limit).toList();
    }

    /** Session ownership, kept beside the sessions because the record does not carry it. */
    private final Map<Long, Long> owners = new LinkedHashMap<>();

    private long ownerOf(long sessionId) {
        return owners.getOrDefault(sessionId, -1L);
    }
}
