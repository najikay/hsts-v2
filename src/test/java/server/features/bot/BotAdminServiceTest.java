package server.features.bot;

import common.dto.auth.Role;
import common.dto.bot.BotActiveRequest;
import common.dto.bot.BotAnalytics;
import common.dto.bot.BotCourseRequest;
import common.dto.bot.BotCreateRequest;
import common.dto.bot.BotManagerPage;
import common.dto.bot.BotSourceKind;
import common.dto.bot.BotTopQuestion;
import common.dto.bot.SourceAddRequest;
import common.dto.bot.SourceRemoveRequest;
import common.dto.notify.NotificationType;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import server.core.AuthorizationException;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.core.SessionManager;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Managing a bot: role plus ownership, one bot per course, parse-then-store, and
 * the anonymous aggregate (E16.9/E16.10 — F12.1/F12.2/F12.3/F12.4/F12.11, S-34 ⚑).
 */
class BotAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");
    private static final String DATABASES = "22";
    private static final String JAVA = "21";
    private static final long DANA = 1001L;
    private static final long MICHAL = 1002L;
    private static final long OTHER_TEACHER = 1003L;
    private static final long MAYA = 3001L;

    private InMemoryBotStore store;
    private RecordingNotifier notifier;
    private BotAdminService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryBotStore()
                .course(DATABASES, "Databases 22")
                .course(JAVA, "Java Programming 21")
                .teaches(DATABASES, DANA)
                .teaches(DATABASES, MICHAL)
                .teaches(JAVA, OTHER_TEACHER)
                .user(DANA, "Dana Cohen")
                .user(MICHAL, "Michal Sharon");
        notifier = new RecordingNotifier();
        service = newService(BotAdminService.SourceLocks.OPEN);
    }

    private BotAdminService newService(BotAdminService.SourceLocks locks) {
        return new BotAdminService(store, new SourceExtractor(), notifier, locks,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CallerContext teacher(long id) {
        return CallerContext.authenticated(null, id, Role.TEACHER);
    }

    private Message managerPage(long teacherId, String course) {
        return service.managerPage(teacher(teacherId),
                Message.request(Verb.BOT_MANAGER_GET, new BotCourseRequest(course)));
    }

    private Message create(long teacherId, String course, String name) {
        return service.create(teacher(teacherId),
                Message.request(Verb.BOT_CREATE, new BotCreateRequest(course, name)));
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    // ===================== Authorisation =================================

    @Nested
    @DisplayName("role plus ownership, on every verb (P-5)")
    class Authorisation {

        @Test
        @DisplayName("a student is refused by the role gate before anything else runs")
        void studentsAreRefused() {
            CallerContext student = CallerContext.authenticated(null, MAYA, Role.STUDENT);

            assertThatThrownBy(() -> service.managerPage(student,
                    Message.request(Verb.BOT_MANAGER_GET, new BotCourseRequest(DATABASES))))
                    .isInstanceOf(AuthorizationException.class);
        }

        @Test
        @DisplayName("a teacher of another course is refused by the ownership check")
        void anotherCoursesTeacherIsRefused() {
            Message response = managerPage(OTHER_TEACHER, DATABASES);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(response.errorMessage()).isEqualTo(BotMessages.NOT_YOUR_COURSE);
        }

        @Test
        @DisplayName("a course that does not exist is not found, before ownership is even asked")
        void unknownCourse() {
            Message response = managerPage(DANA, "99");

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(response.errorMessage()).isEqualTo(BotMessages.NO_SUCH_COURSE);
        }

        @Test
        @DisplayName("a coordinator is a teacher for this purpose (PRD §3)")
        void coordinatorsMayManage() {
            CallerContext coordinator = CallerContext.authenticated(null, DANA, Role.COORDINATOR);

            Message response = service.managerPage(coordinator,
                    Message.request(Verb.BOT_MANAGER_GET, new BotCourseRequest(DATABASES)));

            assertThat(response.isOk()).isTrue();
        }

        @Test
        @DisplayName("every verb refuses a malformed payload rather than guessing")
        void malformedPayloads() {
            assertThat(service.managerPage(teacher(DANA),
                    Message.request(Verb.BOT_MANAGER_GET, "nope")).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION);
            assertThat(service.create(teacher(DANA),
                    Message.request(Verb.BOT_CREATE, "nope")).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION);
            assertThat(service.setActive(teacher(DANA),
                    Message.request(Verb.BOT_ACTIVE_SET, "nope")).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION);
            assertThat(service.addSource(teacher(DANA),
                    Message.request(Verb.BOT_SOURCE_ADD, "nope")).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION);
            assertThat(service.removeSource(teacher(DANA),
                    Message.request(Verb.BOT_SOURCE_REMOVE, "nope")).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION);
            assertThat(service.analytics(teacher(DANA),
                    Message.request(Verb.BOT_ANALYTICS_GET, "nope")).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION);
        }
    }

    // ===================== Creating and toggling =========================

    @Nested
    @DisplayName("one bot per course (S-30)")
    class Creating {

        @Test
        @DisplayName("a course with no bot gets an empty page to draw, not an error")
        void emptyPage() {
            Message response = managerPage(DANA, DATABASES);

            assertThat(response.isOk()).isTrue();
            BotManagerPage page = (BotManagerPage) response.getPayload();
            assertThat(page.exists()).isFalse();
            assertThat(page.sources()).isEmpty();
        }

        @Test
        @DisplayName("creating gives back the whole manager page, ready to render")
        void creates() {
            Message response = create(DANA, DATABASES, "Databases helper");

            BotManagerPage page = (BotManagerPage) response.getPayload();
            assertThat(page.exists()).isTrue();
            assertThat(page.bot().name()).isEqualTo("Databases helper");
            assertThat(page.bot().courseName()).isEqualTo("Databases 22");
            assertThat(page.bot().active()).isTrue();
        }

        @Test
        @DisplayName("a blank name is filled in from the course rather than left empty")
        void namesItselfAfterTheCourse() {
            Message response = create(DANA, DATABASES, "   ");

            assertThat(((BotManagerPage) response.getPayload()).bot().name())
                    .isEqualTo("Databases 22 study bot");
        }

        @Test
        @DisplayName("the second teacher joins the existing bot instead of getting a conflict")
        void secondTeacherJoins() {
            create(DANA, DATABASES, "Dana's bot");

            Message response = create(MICHAL, DATABASES, "Michal's bot");

            assertThat(response.isOk()).isTrue();
            assertThat(((BotManagerPage) response.getPayload()).bot().name())
                    .as("one bot per course: she contributes to Dana's, she does not replace it")
                    .isEqualTo("Dana's bot");
        }

        @Test
        @DisplayName("switching the bot off answers with the refreshed page (F12.4)")
        void togglesActive() {
            create(DANA, DATABASES, "bot");

            Message off = service.setActive(teacher(DANA), Message.request(Verb.BOT_ACTIVE_SET,
                    new BotActiveRequest(DATABASES, false)));

            assertThat(((BotManagerPage) off.getPayload()).bot().active()).isFalse();

            Message on = service.setActive(teacher(DANA), Message.request(Verb.BOT_ACTIVE_SET,
                    new BotActiveRequest(DATABASES, true)));

            assertThat(((BotManagerPage) on.getPayload()).bot().active()).isTrue();
        }

        @Test
        @DisplayName("toggling a bot that does not exist says to create one first")
        void togglingWithoutABot() {
            Message response = service.setActive(teacher(DANA),
                    Message.request(Verb.BOT_ACTIVE_SET, new BotActiveRequest(DATABASES, true)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(response.errorMessage()).isEqualTo(BotMessages.BOT_NOT_CREATED);
        }
    }

    // ===================== Sources =======================================

    @Nested
    @DisplayName("adding and removing material (F12.2/F12.3)")
    class Sources {

        @BeforeEach
        void createTheBot() {
            create(DANA, DATABASES, "Databases bot");
        }

        @Test
        @DisplayName("a text source is parsed, stored and listed with its size and author")
        void addsText() {
            Message response = service.addSource(teacher(DANA),
                    Message.request(Verb.BOT_SOURCE_ADD, new SourceAddRequest(DATABASES,
                            BotSourceKind.TEXT, "Week 3 handout",
                            text("A foreign key points at another table's primary key."))));

            BotManagerPage page = (BotManagerPage) response.getPayload();
            assertThat(page.sources()).hasSize(1);
            assertThat(page.sources().get(0).title()).isEqualTo("Week 3 handout");
            assertThat(page.sources().get(0).addedBy()).isEqualTo("Dana Cohen");
            assertThat(page.sources().get(0).characters()).isPositive();
            assertThat(page.sources().get(0).kind()).isEqualTo(BotSourceKind.TEXT);
        }

        @Test
        @DisplayName("a source that cannot be parsed is refused, and nothing is written (F12.2)")
        void parseFailureWritesNothing() {
            Message response = service.addSource(teacher(DANA),
                    Message.request(Verb.BOT_SOURCE_ADD, new SourceAddRequest(DATABASES,
                            BotSourceKind.PDF, "Scan", text("%PDF-1.4 not really"))));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage()).contains("could not be read");
            assertThat(store.sourceInfos(store.botIdOf(DATABASES))).isEmpty();
            assertThat(notifier.sent)
                    .as("nothing happened, so nobody is told anything")
                    .isEmpty();
        }

        @Test
        @DisplayName("a source with no title or no content is refused before parsing")
        void incompleteSource() {
            Message response = service.addSource(teacher(DANA),
                    Message.request(Verb.BOT_SOURCE_ADD, new SourceAddRequest(DATABASES,
                            BotSourceKind.TEXT, "  ", text("body"))));

            assertThat(response.errorMessage()).isEqualTo(BotMessages.SOURCE_INCOMPLETE);
        }

        @Test
        @DisplayName("an upload over the ceiling is refused before it is parsed")
        void tooLarge() {
            byte[] huge = new byte[SourceAddRequest.MAX_BYTES + 1];
            java.util.Arrays.fill(huge, (byte) 'a');

            Message response = service.addSource(teacher(DANA),
                    Message.request(Verb.BOT_SOURCE_ADD, new SourceAddRequest(DATABASES,
                            BotSourceKind.TEXT, "Huge", huge)));

            assertThat(response.errorMessage()).isEqualTo(BotMessages.SOURCE_TOO_LARGE);
        }

        @Test
        @DisplayName("adding to a course with no bot says to create one first")
        void addingWithoutABot() {
            store = new InMemoryBotStore().course(JAVA, "Java 21").teaches(JAVA, DANA);
            service = newService(BotAdminService.SourceLocks.OPEN);

            Message response = service.addSource(teacher(DANA),
                    Message.request(Verb.BOT_SOURCE_ADD, new SourceAddRequest(JAVA,
                            BotSourceKind.TEXT, "Notes", text("Some course material here."))));

            assertThat(response.errorMessage()).isEqualTo(BotMessages.BOT_NOT_CREATED);
        }

        @Test
        @DisplayName("co-teachers are told the material changed, and the editor is not (F12.3)")
        void notifiesCoTeachers() {
            service.addSource(teacher(DANA), Message.request(Verb.BOT_SOURCE_ADD,
                    new SourceAddRequest(DATABASES, BotSourceKind.TEXT, "Week 3",
                            text("A foreign key points at a primary key."))));

            assertThat(notifier.sent).hasSize(1);
            assertThat(notifier.recipients()).containsExactly(MICHAL);
            assertThat(notifier.sent.get(0).type()).isEqualTo(NotificationType.BOT_SOURCE_CHANGED);
            assertThat(notifier.sent.get(0).body()).contains("Dana Cohen").contains("Databases 22");
        }

        @Test
        @DisplayName("a solo-taught course notifies nobody rather than notifying the editor")
        void soloCourseNotifiesNobody() {
            store = new InMemoryBotStore().course(JAVA, "Java 21").teaches(JAVA, DANA)
                    .user(DANA, "Dana Cohen").bot(JAVA, "Java bot", true);
            service = newService(BotAdminService.SourceLocks.OPEN);

            service.addSource(teacher(DANA), Message.request(Verb.BOT_SOURCE_ADD,
                    new SourceAddRequest(JAVA, BotSourceKind.TEXT, "Notes",
                            text("Some course material here."))));

            assertThat(notifier.sent).isEmpty();
        }

        @Test
        @DisplayName("removing a source works and tells the co-teachers")
        void removes() {
            service.addSource(teacher(DANA), Message.request(Verb.BOT_SOURCE_ADD,
                    new SourceAddRequest(DATABASES, BotSourceKind.TEXT, "Week 3",
                            text("A foreign key points at a primary key."))));
            long sourceId = store.lastSourceId();
            notifier.sent.clear();

            Message response = service.removeSource(teacher(MICHAL),
                    Message.request(Verb.BOT_SOURCE_REMOVE,
                            new SourceRemoveRequest(DATABASES, sourceId)));

            assertThat(((BotManagerPage) response.getPayload()).sources()).isEmpty();
            assertThat(notifier.recipients()).containsExactly(DANA);
        }

        @Test
        @DisplayName("a source id from another course's bot is not found rather than removed")
        void cannotRemoveAnotherCoursesSource() {
            Message response = service.removeSource(teacher(DANA),
                    Message.request(Verb.BOT_SOURCE_REMOVE,
                            new SourceRemoveRequest(DATABASES, 424242L)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(response.errorMessage()).isEqualTo(BotMessages.SOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("a source another teacher is holding cannot be removed (E18.5)")
        void respectsTheEditLock() {
            service.addSource(teacher(DANA), Message.request(Verb.BOT_SOURCE_ADD,
                    new SourceAddRequest(DATABASES, BotSourceKind.TEXT, "Week 3",
                            text("A foreign key points at a primary key."))));
            long sourceId = store.lastSourceId();
            BotAdminService locked = newService((id, userId) -> false);

            Message response = locked.removeSource(teacher(MICHAL),
                    Message.request(Verb.BOT_SOURCE_REMOVE,
                            new SourceRemoveRequest(DATABASES, sourceId)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(response.errorMessage()).isEqualTo(BotMessages.SOURCE_LOCKED);
            assertThat(store.sourceInfos(store.botIdOf(DATABASES))).hasSize(1);
        }

        @Test
        @DisplayName("a teacher of another course cannot add or remove")
        void ownershipOnSourceVerbs() {
            assertThat(service.addSource(teacher(OTHER_TEACHER),
                    Message.request(Verb.BOT_SOURCE_ADD, new SourceAddRequest(DATABASES,
                            BotSourceKind.TEXT, "Notes", text("Some course material here."))))
                    .getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(service.removeSource(teacher(OTHER_TEACHER),
                    Message.request(Verb.BOT_SOURCE_REMOVE, new SourceRemoveRequest(DATABASES, 1L)))
                    .getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        }
    }

    // ===================== Analytics =====================================

    @Nested
    @DisplayName("the anonymised aggregate (F12.11, S-34 ⚑)")
    class Analytics {

        @BeforeEach
        void createTheBot() {
            create(DANA, DATABASES, "Databases bot");
        }

        private void recordQuestion(String question, Instant at) {
            store.appendExchange(null, store.botIdOf(DATABASES), MAYA, question,
                    "an answer", "deepseek", at);
        }

        @Test
        @DisplayName("a bot nobody has used reports an empty view rather than an error")
        void emptyAnalytics() {
            Message response = service.analytics(teacher(DANA),
                    Message.request(Verb.BOT_ANALYTICS_GET, new BotCourseRequest(DATABASES)));

            BotAnalytics analytics = (BotAnalytics) response.getPayload();
            assertThat(analytics.isEmpty()).isTrue();
            assertThat(analytics.courseName()).isEqualTo("Databases 22");
        }

        @Test
        @DisplayName("a course with no bot at all also reports an empty view")
        void noBotAnalytics() {
            store = new InMemoryBotStore().course(JAVA, "Java 21").teaches(JAVA, DANA);
            service = newService(BotAdminService.SourceLocks.OPEN);

            Message response = service.analytics(teacher(DANA),
                    Message.request(Verb.BOT_ANALYTICS_GET, new BotCourseRequest(JAVA)));

            assertThat(((BotAnalytics) response.getPayload()).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("questions are counted and bucketed by day")
        void countsAndBuckets() {
            recordQuestion("what is a foreign key", NOW);
            recordQuestion("what is a primary key", NOW);
            recordQuestion("what is normalisation", NOW.minus(java.time.Duration.ofDays(1)));

            Message response = service.analytics(teacher(DANA),
                    Message.request(Verb.BOT_ANALYTICS_GET, new BotCourseRequest(DATABASES)));

            BotAnalytics analytics = (BotAnalytics) response.getPayload();
            assertThat(analytics.totalQuestions()).isEqualTo(3);
            assertThat(analytics.activity()).hasSize(2);
            assertThat(analytics.peakPerDay()).isEqualTo(2);
        }

        @Test
        @DisplayName("differently spelled versions of one question are one row")
        void foldsFrequentQuestions() {
            recordQuestion("What is a foreign key?", NOW);
            recordQuestion("what is a foreign key", NOW);
            recordQuestion("  WHAT IS A FOREIGN KEY  ", NOW);
            recordQuestion("what is normalisation", NOW);

            Message response = service.analytics(teacher(DANA),
                    Message.request(Verb.BOT_ANALYTICS_GET, new BotCourseRequest(DATABASES)));

            List<BotTopQuestion> frequent = ((BotAnalytics) response.getPayload()).frequent();
            assertThat(frequent).hasSize(2);
            assertThat(frequent.get(0).question()).isEqualTo("what is a foreign key");
            assertThat(frequent.get(0).count()).isEqualTo(3);
            assertThat(frequent.get(0).timesLabel()).isEqualTo("3 times");
        }

        @Test
        @DisplayName("the fold is stable and bounded")
        void foldIsStableAndBounded() {
            List<String> questions = new java.util.ArrayList<>();
            for (int i = 0; i < BotAdminService.TOP_QUESTIONS + 5; i++) {
                questions.add("question number " + i);
            }

            List<BotTopQuestion> first = BotAdminService.fold(questions);
            List<BotTopQuestion> second = BotAdminService.fold(questions);

            assertThat(first).hasSize(BotAdminService.TOP_QUESTIONS);
            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("questions that fold to nothing are ignored rather than counted as blank rows")
        void ignoresEmptyKeys() {
            assertThat(BotAdminService.fold(List.of("   ", "???", ""))).isEmpty();
            assertThat(BotAdminService.fold(null)).isEmpty();
        }

        @Test
        @DisplayName("a teacher of another course cannot read the aggregate")
        void ownershipOnAnalytics() {
            Message response = service.analytics(teacher(OTHER_TEACHER),
                    Message.request(Verb.BOT_ANALYTICS_GET, new BotCourseRequest(DATABASES)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        }
    }

    @Test
    @DisplayName("all six teacher verbs register, and none of them is open")
    void registersItsVerbs() {
        MessageRouter router = new MessageRouter(new SessionManager());

        service.registerOn(router);

        List.of(Verb.BOT_MANAGER_GET, Verb.BOT_CREATE, Verb.BOT_ACTIVE_SET,
                Verb.BOT_SOURCE_ADD, Verb.BOT_SOURCE_REMOVE, Verb.BOT_ANALYTICS_GET)
                .forEach(verb -> {
                    assertThat(router.isRegistered(verb)).as("%s", verb).isTrue();
                    assertThat(router.isOpen(verb)).as("%s", verb).isFalse();
                });
    }
}
